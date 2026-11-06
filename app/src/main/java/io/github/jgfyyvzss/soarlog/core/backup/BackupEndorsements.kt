package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.endorsement.EndorsementImageRules
import io.github.jgfyyvzss.soarlog.core.msg

/** [images] holds the hashes of the endorsement images in the zip that passed their checks; only those are restored. */
data class EndorsementReview(val file: BackupFile, val notes: List<Msg>, val images: Set<String>)

/**
 * Endorsements on import (docs/Endorsements.md). As for witness data, nothing here blocks an import: a bad or missing
 * image is left out and the endorsement kept with its hash, and one whose sign-off is missing loses its witness mark.
 */
object BackupEndorsements {
    fun review(file: BackupFile, images: BackupImages): EndorsementReview {
        val notes = mutableListOf<Msg>()
        val good = mutableSetOf<String>()
        val problems = mutableMapOf<String, MsgKey?>()
        val signoffs = file.witnessSignoffs.associateBy { it.id }
        val endorsements = file.endorsements.map { e ->
            val n = msg(MsgKey.BK_ENDORSEMENT, e.type)
            val sha = e.imageSha256?.lowercase()
            if (sha != null && e.deletedAt == null) {
                if (sha !in problems) {
                    problems[sha] = when (val r = images.read(sha, EndorsementImageRules.MAX_BYTES)) {
                        is ImageRead.Ok -> null
                        ImageRead.Missing -> MsgKey.BK_IMAGE_MISSING
                        is ImageRead.Bad -> imageKey(r.problem)
                    }
                }
                when (val problem = problems[sha]) {
                    null -> good += sha
                    // A file made without images is missing them on purpose.
                    MsgKey.BK_IMAGE_MISSING -> if (file.containsImages) notes += msg(problem, n)
                    else -> notes += msg(problem, n)
                }
            }
            if (e.witnessSignoffId == null && e.witnessSeal == null) return@map e
            val s = e.witnessSignoffId?.let { signoffs[it] }
            // A live endorsement needs a live sign-off; a deleted one may keep one that was deleted with it.
            if (s == null || e.witnessSeal == null || (e.deletedAt == null && s.deletedAt != null)) {
                if (e.deletedAt == null) notes += msg(MsgKey.BK_WITNESS_DANGLING, n)
                return@map e.copy(witnessSignoffId = null, witnessSeal = null)
            }
            e
        }
        return EndorsementReview(file.copy(endorsements = endorsements), notes, good)
    }

    /** The zip reader reports photo problems; an endorsement's are about its image. */
    private fun imageKey(photoProblem: MsgKey): MsgKey = when (photoProblem) {
        MsgKey.BK_PHOTO_NOT_JPEG -> MsgKey.BK_IMAGE_NOT_JPEG
        MsgKey.BK_PHOTO_TOO_LARGE -> MsgKey.BK_IMAGE_TOO_LARGE
        MsgKey.BK_PHOTO_HASH -> MsgKey.BK_IMAGE_HASH
        else -> MsgKey.BK_IMAGE_UNREADABLE
    }
}
