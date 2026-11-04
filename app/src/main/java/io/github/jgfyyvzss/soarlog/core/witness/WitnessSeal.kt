package io.github.jgfyyvzss.soarlog.core.witness

import io.github.jgfyyvzss.soarlog.core.backup.BackupTime
import java.security.MessageDigest
import java.time.LocalDate

/**
 * Everything a witness seal covers: a flight's signed fields and the sign-off's details. Notes, club, source, import
 * batch and WeGlide data are not signed, so they can change without breaking the seal.
 */
data class SealInput(
    val logNo: Int,
    val date: LocalDate,
    val durationMinutes: Int,
    /** Signed by ID, so renaming a glider, airfield or launch type keeps the seal, while choosing another does not. */
    val gliderId: String,
    val airfieldId: String,
    val launchTypeId: String,
    /** The stored role name, for example STUDENT. */
    val role: String,
    val picName: String,
    /** HH:MM, or null. */
    val launchTime: String?,
    val landingTime: String?,
    val landingAirfieldId: String?,
    val landingPlace: String?,
    val witnessName: String,
    val witnessIdentifier: String?,
    /** Epoch milliseconds, UTC. */
    val signedAt: Long,
    /** The photo's SHA-256 in lower-case hex, or null when no photo was taken. */
    val photoSha256: String?,
)

/** Whether a flight still matches its seal. Never stored: worked out whenever it is shown or imported. */
enum class SealStatus { WITNESSED, CHANGED, CANNOT_VERIFY }

/**
 * The witness seal from `docs/Witness_Sign-Off.md`: a SHA-256 over a fixed text, so an edit to a signed field shows.
 * It has no secret, so it detects edits but cannot stop someone who recomputes it.
 */
object WitnessSeal {
    /** The field list below is version 1. A new list needs a new version, and old seals keep their own. */
    const val VERSION = 1

    /**
     * One `key=value` line per field, in a fixed order, each ended by LF, and empty for a null. Values are written
     * as stored; the date as YYYY-MM-DD and the signing time as ISO 8601 UTC (as in the JSON backup). A backslash,
     * CR or LF inside a value is escaped as `\\`, `\r` or `\n`, so every field stays on its own line.
     */
    fun canonical(input: SealInput, version: Int = VERSION): String {
        require(version == VERSION) { "unknown seal version $version" }
        val fields = listOf(
            "seal_version" to version.toString(),
            "log_no" to input.logNo.toString(),
            "date" to input.date.toString(),
            "duration_minutes" to input.durationMinutes.toString(),
            "glider_id" to input.gliderId,
            "airfield_id" to input.airfieldId,
            "launch_type_id" to input.launchTypeId,
            "role" to input.role,
            "pic_name" to input.picName,
            "launch_time" to input.launchTime,
            "landing_time" to input.landingTime,
            "landing_airfield_id" to input.landingAirfieldId,
            "landing_place" to input.landingPlace,
            "witness_name" to input.witnessName,
            "witness_identifier" to input.witnessIdentifier,
            "signed_at" to BackupTime.toIso(input.signedAt),
            "photo_sha256" to input.photoSha256,
        )
        return text(fields)
    }

    /** The canonical text for [fields], in order; shared with the endorsement seal so both follow the same rules. */
    internal fun text(fields: List<Pair<String, String?>>): String =
        fields.joinToString("") { (key, value) -> "$key=${escape(value.orEmpty())}\n" }

    /** The seal: SHA-256 of the canonical text as UTF-8, in lower-case hex. */
    fun seal(input: SealInput, version: Int = VERSION): String = sha256Hex(canonical(input, version).toByteArray(Charsets.UTF_8))

    /** Compares a stored seal with the flight as it is now. */
    fun check(input: SealInput, storedSeal: String, storedVersion: Int): SealStatus = when {
        storedVersion != VERSION -> SealStatus.CANNOT_VERIFY
        seal(input, storedVersion).equals(storedSeal.trim(), ignoreCase = true) -> SealStatus.WITNESSED
        else -> SealStatus.CHANGED
    }

    /** Lower-case hex, written by hand so the phone's language can never change the digits. */
    fun sha256Hex(bytes: ByteArray): String {
        val hex = "0123456789abcdef"
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { b ->
            val v = b.toInt() and 0xff
            "${hex[v shr 4]}${hex[v and 0xf]}"
        }
    }

    private fun escape(value: String): String =
        value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n")
}
