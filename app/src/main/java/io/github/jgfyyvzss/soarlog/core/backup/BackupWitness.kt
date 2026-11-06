package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.witness.SealInput
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import java.time.LocalDate

/** The witness part of an import summary: how the live witnessed flights' seals compare, and what was repaired. */
data class WitnessSummary(val verified: Int, val changed: Int, val cannotVerify: Int, val notes: List<Msg>) {
    val isEmpty get() = verified == 0 && changed == 0 && cannotVerify == 0 && notes.isEmpty()
}

/** [photos] holds the hashes of the photos in the zip that passed their checks; only those are restored. */
data class WitnessReview(val file: BackupFile, val summary: WitnessSummary, val photos: Set<String> = emptySet())

/**
 * Witness data on import (docs/Witness_Sign-Off.md). None of it blocks an import: a bad or missing photo is left out
 * and the sign-off kept, a flight whose sign-off is missing keeps its data but loses its witness mark, and a seal
 * that no longer matches simply shows as changed.
 */
object BackupWitness {
    /** The largest photo accepted, as for a new sign-off. */
    const val MAX_PHOTO_BYTES = 100 * 1024

    /** A JPEG starts with the SOI marker FF D8 followed by another marker. */
    fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /**
     * Checks the photos of live sign-offs in [images] and the witness references and seals in [file]. Without
     * [images] the photos are not looked at, as when counting the seals of a merged logbook.
     */
    fun review(file: BackupFile, images: BackupImages? = null): WitnessReview {
        val notes = mutableListOf<Msg>()
        val photos = mutableSetOf<String>()
        if (images != null) {
            // Each photo is read once and only its problem, or null for a good one, is kept, so none stays in memory.
            val problems = mutableMapOf<String, MsgKey?>()
            for (s in file.witnessSignoffs) {
                val sha = s.photoSha256?.lowercase() ?: continue
                if (s.photoRemoved || s.deletedAt != null) continue
                if (sha !in problems) {
                    problems[sha] = when (val r = images.read(sha, MAX_PHOTO_BYTES)) {
                        is ImageRead.Ok -> null
                        ImageRead.Missing -> MsgKey.BK_PHOTO_MISSING
                        is ImageRead.Bad -> r.problem
                    }
                }
                when (val problem = problems[sha]) {
                    null -> photos += sha
                    // A file made without images is missing them on purpose.
                    MsgKey.BK_PHOTO_MISSING -> if (file.containsImages) notes += msg(problem, msg(MsgKey.BK_WITNESS, s.witnessName))
                    else -> notes += msg(problem, msg(MsgKey.BK_WITNESS, s.witnessName))
                }
            }
        }
        val byId = file.witnessSignoffs.associateBy { it.id }
        var verified = 0
        var changed = 0
        var cannotVerify = 0
        val flights = file.flights.map { f ->
            if (f.witnessSignoffId == null && f.witnessSeal == null) return@map f
            val s = f.witnessSignoffId?.let { byId[it] }
            // A live flight needs a live sign-off; a deleted flight may keep one that was deleted with it.
            if (s == null || f.witnessSeal == null || (f.deletedAt == null && s.deletedAt != null)) {
                if (f.deletedAt == null) notes += msg(MsgKey.BK_WITNESS_DANGLING, msg(MsgKey.BK_FLIGHT, f.logNo))
                return@map f.copy(witnessSignoffId = null, witnessSeal = null)
            }
            if (f.deletedAt == null) {
                when (status(f, s)) {
                    SealStatus.WITNESSED -> verified++
                    SealStatus.CHANGED -> changed++
                    SealStatus.CANNOT_VERIFY -> cannotVerify++
                }
            }
            f
        }
        return WitnessReview(file.copy(flights = flights), WitnessSummary(verified, changed, cannotVerify, notes), photos)
    }

    /** The flight's seal checked against its fields as they are in the file. */
    fun status(f: BackupFlight, s: BackupWitnessSignoff): SealStatus {
        val seal = f.witnessSeal ?: return SealStatus.CANNOT_VERIFY
        val signedAt = BackupTime.fromIso(s.signedAt) ?: return SealStatus.CANNOT_VERIFY
        val date = runCatching { LocalDate.parse(f.date) }.getOrNull() ?: return SealStatus.CANNOT_VERIFY
        val input = SealInput(
            logNo = f.logNo, date = date, durationMinutes = f.durationMinutes, gliderId = f.gliderId, airfieldId = f.airfieldId,
            launchTypeId = f.launchTypeId, role = f.role, picName = f.picName, launchTime = f.launchTime, landingTime = f.landingTime,
            landingAirfieldId = f.landingAirfieldId, landingPlace = f.landingPlace, witnessName = s.witnessName,
            witnessIdentifier = s.witnessIdentifier, signedAt = signedAt, photoSha256 = s.photoSha256,
        )
        return WitnessSeal.check(input, seal, s.sealVersion)
    }
}
