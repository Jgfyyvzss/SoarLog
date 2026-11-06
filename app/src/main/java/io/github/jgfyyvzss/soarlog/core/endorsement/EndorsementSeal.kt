package io.github.jgfyyvzss.soarlog.core.endorsement

import io.github.jgfyyvzss.soarlog.core.backup.BackupTime
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import java.time.LocalDate

/** Everything an endorsement's witness seal covers: the endorsement as recorded and the sign-off's details. */
data class EndorsementSealInput(
    /** The type as the pilot typed it, for example "Winch launch". */
    val type: String,
    val date: LocalDate,
    /** The image's SHA-256 in lower-case hex, or null when there is no image, so adding, replacing or removing one shows. */
    val imageSha256: String?,
    val witnessName: String,
    val witnessIdentifier: String?,
    /** Epoch milliseconds, UTC. */
    val signedAt: Long,
    /** The witness photo's SHA-256 in lower-case hex. */
    val photoSha256: String?,
)

/**
 * The endorsement seal from docs/Endorsements.md: the flight seal's rules over the endorsement's own fields. The
 * `record=endorsement` line means a flight seal and an endorsement seal can never be the same text.
 */
object EndorsementSeal {
    /** Version 1 of the endorsement field list, numbered apart from the flight seal's versions. */
    const val VERSION = 1

    fun canonical(input: EndorsementSealInput, version: Int = VERSION): String {
        require(version == VERSION) { "unknown endorsement seal version $version" }
        return WitnessSeal.text(
            listOf(
                "seal_version" to version.toString(),
                "record" to "endorsement",
                "type" to input.type,
                "date" to input.date.toString(),
                "image_sha256" to input.imageSha256,
                "witness_name" to input.witnessName,
                "witness_identifier" to input.witnessIdentifier,
                "signed_at" to BackupTime.toIso(input.signedAt),
                "photo_sha256" to input.photoSha256,
            ),
        )
    }

    /** The seal: SHA-256 of the canonical text as UTF-8, in lower-case hex. */
    fun seal(input: EndorsementSealInput, version: Int = VERSION): String =
        WitnessSeal.sha256Hex(canonical(input, version).toByteArray(Charsets.UTF_8))

    /** Compares a stored seal with the endorsement as it is now. */
    fun check(input: EndorsementSealInput, storedSeal: String, storedVersion: Int): SealStatus = when {
        storedVersion != VERSION -> SealStatus.CANNOT_VERIFY
        seal(input, storedVersion).equals(storedSeal.trim(), ignoreCase = true) -> SealStatus.WITNESSED
        else -> SealStatus.CHANGED
    }
}
