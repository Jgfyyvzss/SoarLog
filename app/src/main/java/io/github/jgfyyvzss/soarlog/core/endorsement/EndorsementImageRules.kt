package io.github.jgfyyvzss.soarlog.core.endorsement

import io.github.jgfyyvzss.soarlog.core.witness.WitnessPhotoRules

/**
 * How a camera photo or picked image becomes a stored endorsement image (docs/Endorsements.md): turned upright,
 * scaled to [MAX_SIDE] pixels on its longest side, and re-encoded as JPEG, which drops location and other metadata.
 * Scaling, sampling and turning upright follow the witness photo's rules, at this larger size.
 */
object EndorsementImageRules {
    /** A third of an A5 page of print stays clearly readable at this size. */
    const val MAX_SIDE = 1600
    val QUALITIES = listOf(85, 75, 65)
    /** Aimed for, so many endorsements stay well inside Android Auto Backup's 25 MB. */
    const val TARGET_BYTES = 500 * 1024
    /** Never exceeded: keeps every image row well inside the 2 MB a database row can be read back with. */
    const val MAX_BYTES = 1024 * 1024

    fun scaledSize(width: Int, height: Int): Pair<Int, Int> = WitnessPhotoRules.scaledSize(width, height, MAX_SIDE)

    fun sampleSize(width: Int, height: Int): Int = WitnessPhotoRules.sampleSize(width, height, MAX_SIDE)

    fun transform(exifOrientation: Int): WitnessPhotoRules.Transform = WitnessPhotoRules.transform(exifOrientation)

    /**
     * Encodes at each of [QUALITIES] in turn and keeps the first within [TARGET_BYTES], or else the smallest if it
     * is within [MAX_BYTES]. Null means the image is refused.
     */
    fun choose(encode: (quality: Int) -> ByteArray): ByteArray? {
        var smallest: ByteArray? = null
        for (q in QUALITIES) {
            val bytes = encode(q)
            if (bytes.size <= TARGET_BYTES) return bytes
            if (smallest == null || bytes.size < smallest.size) smallest = bytes
        }
        return smallest?.takeIf { it.size <= MAX_BYTES }
    }
}
