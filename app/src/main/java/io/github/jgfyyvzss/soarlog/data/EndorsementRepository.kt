package io.github.jgfyyvzss.soarlog.data

import androidx.room.withTransaction
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.backup.BackupWitness
import io.github.jgfyyvzss.soarlog.core.endorsement.EndorsementImageRules
import io.github.jgfyyvzss.soarlog.core.endorsement.EndorsementSeal
import io.github.jgfyyvzss.soarlog.core.endorsement.EndorsementSealInput
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import io.github.jgfyyvzss.soarlog.data.pdf.PdfEndorsement
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

enum class EndorsementRefusal { NO_TYPE, IMAGE_NOT_JPEG, IMAGE_TOO_LARGE }

sealed interface EndorsementSave {
    data class Saved(val id: String) : EndorsementSave
    data class Refused(val reason: EndorsementRefusal) : EndorsementSave
}

/**
 * Logbook endorsements (docs/Endorsements.md): a type, a date and an optional image, which a witness can sign one at
 * a time. The rules Room cannot express are kept here: a type, a JPEG image of at most 1 MB whose hash the row
 * carries, the image deleted with its endorsement, and the two witness columns set together or not at all.
 */
class EndorsementRepository(private val db: LogbookDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao = db.endorsementDao()
    private val witness = db.witnessDao()

    fun observeAll(): Flow<List<EndorsementEntity>> = dao.observeLive()
    fun observe(id: String): Flow<EndorsementEntity?> = dao.observe(id)
    fun observeSignoff(id: String): Flow<WitnessSignoffEntity?> = dao.observeSignoff(id)
    suspend fun get(id: String): EndorsementEntity? = dao.get(id)
    suspend fun image(id: String): ByteArray? = dao.image(id)

    /**
     * Saves a new endorsement ([id] null) or changes one. With [imageChanged], [image] replaces the stored image,
     * and null removes it; otherwise the image stays as it is.
     */
    suspend fun save(id: String?, type: String, date: LocalDate, imageChanged: Boolean, image: ByteArray?): EndorsementSave {
        if (type.isBlank()) return EndorsementSave.Refused(EndorsementRefusal.NO_TYPE)
        if (imageChanged && image != null) {
            if (!BackupWitness.isJpeg(image)) return EndorsementSave.Refused(EndorsementRefusal.IMAGE_NOT_JPEG)
            if (image.size > EndorsementImageRules.MAX_BYTES) return EndorsementSave.Refused(EndorsementRefusal.IMAGE_TOO_LARGE)
        }
        return db.withTransaction {
            val old = id?.let { dao.get(it) }?.takeIf { it.deletedAt == null }
            val row = EndorsementEntity(
                id = old?.id ?: Keys.newId(),
                type = type.trim(),
                date = date.toString(),
                imageSha256 = if (imageChanged) image?.let(WitnessSeal::sha256Hex) else old?.imageSha256,
                witnessSignoffId = old?.witnessSignoffId,
                witnessSeal = old?.witnessSeal,
                updatedAt = now(),
            )
            if (old == null) dao.insert(row) else dao.update(row)
            if (imageChanged) {
                if (image == null) dao.deleteImage(row.id) else dao.putImage(EndorsementImageEntity(row.id, image))
            }
            EndorsementSave.Saved(row.id)
        }
    }

    /** Deletes an endorsement, leaving a tombstone without its image; a sign-off nothing else uses goes with it. */
    suspend fun delete(id: String) {
        val e = dao.get(id) ?: return
        db.withTransaction {
            val time = now()
            dao.update(e.copy(updatedAt = time, deletedAt = time))
            dao.deleteImage(id)
            witness.deleteUnreferenced(time)
        }
    }

    /**
     * The witness's name when saving [type], [date] and [imageSha256] would break a seal that still matches, so the
     * pilot is asked first; null otherwise.
     */
    suspend fun sealWouldBreak(id: String, type: String, date: LocalDate, imageSha256: String?): String? {
        val e = dao.get(id) ?: return null
        val s = e.witnessSignoffId?.let { witness.get(it) } ?: return null
        if (status(e, s) != SealStatus.WITNESSED) return null
        val edited = e.copy(type = type.trim(), date = date.toString(), imageSha256 = imageSha256)
        return s.witnessName.takeIf { status(edited, s) != SealStatus.WITNESSED }
    }

    /** Saves a sign-off for one endorsement and seals it. An endorsement witnessed before is witnessed again. */
    suspend fun sign(endorsementId: String, name: String, identifier: String?, photo: ByteArray, consent: Boolean): SignResult {
        if (name.isBlank()) return SignResult.Refused(SignRefusal.NO_NAME)
        if (!consent) return SignResult.Refused(SignRefusal.NO_CONSENT)
        if (photo.isEmpty()) return SignResult.Refused(SignRefusal.NO_PHOTO)
        if (photo.size > WitnessRepository.MAX_PHOTO_BYTES) return SignResult.Refused(SignRefusal.PHOTO_TOO_LARGE)
        return db.withTransaction {
            val e = dao.get(endorsementId)?.takeIf { it.deletedAt == null }
                ?: return@withTransaction SignResult.Refused(SignRefusal.NO_FLIGHTS)
            val time = now()
            val signoff = WitnessSignoffEntity(
                id = Keys.newId(),
                witnessName = name.trim(),
                witnessIdentifier = identifier?.trim()?.takeIf { it.isNotEmpty() },
                signedAt = time,
                photo = photo,
                photoSha256 = WitnessSeal.sha256Hex(photo),
                sealVersion = EndorsementSeal.VERSION,
                consentConfirmed = true,
                updatedAt = time,
            )
            witness.insert(signoff)
            dao.setWitness(e.id, signoff.id, EndorsementSeal.seal(sealInput(e, signoff)), time)
            witness.deleteUnreferenced(time)
            SignResult.Signed(signoff.id)
        }
    }

    /** Detaches the witness; a sign-off nothing else refers to is then deleted with its photo. */
    suspend fun removeMark(id: String) = db.withTransaction {
        val time = now()
        dao.setWitness(id, null, null, time)
        witness.deleteUnreferenced(time)
    }

    /**
     * Every live endorsement for the logbook PDF, oldest first. The witness is named only [withWitness] and only while
     * the seal still matches, as for flights.
     */
    suspend fun forPdf(withWitness: Boolean): List<PdfEndorsement> = dao.liveOldestFirst().map { e ->
        val s = if (withWitness) e.witnessSignoffId?.let { witness.get(it) }?.takeIf { status(e, it) == SealStatus.WITNESSED } else null
        PdfEndorsement(
            id = e.id,
            type = e.type,
            date = runCatching { LocalDate.parse(e.date) }.getOrDefault(LocalDate.MIN),
            hasImage = e.imageSha256 != null && dao.hasImage(e.id),
            witnessName = s?.witnessName,
            witnessIdentifier = s?.witnessIdentifier,
        )
    }

    /** Every witnessed live endorsement's status, by endorsement ID, kept up to date. */
    fun observeStatuses(): Flow<Map<String, SealStatus>> = combine(dao.observeLive(), witness.observeLive()) { endorsements, signoffs ->
        val byId = signoffs.associateBy { it.id }
        endorsements.filter { it.witnessSignoffId != null }.associate { e ->
            e.id to (e.witnessSignoffId?.let { byId[it] }?.let { status(e, it) } ?: SealStatus.CANNOT_VERIFY)
        }
    }

    companion object {
        fun status(e: EndorsementEntity, s: WitnessSignoffEntity): SealStatus {
            val seal = e.witnessSeal ?: return SealStatus.CANNOT_VERIFY
            val input = runCatching { sealInput(e, s) }.getOrNull() ?: return SealStatus.CANNOT_VERIFY
            return EndorsementSeal.check(input, seal, s.sealVersion)
        }

        /** The endorsement's fields and the sign-off's details, as the seal covers them. */
        fun sealInput(e: EndorsementEntity, s: WitnessSignoffEntity) = EndorsementSealInput(
            type = e.type,
            date = LocalDate.parse(e.date),
            imageSha256 = e.imageSha256,
            witnessName = s.witnessName,
            witnessIdentifier = s.witnessIdentifier,
            signedAt = s.signedAt,
            photoSha256 = s.photoSha256,
        )
    }
}
