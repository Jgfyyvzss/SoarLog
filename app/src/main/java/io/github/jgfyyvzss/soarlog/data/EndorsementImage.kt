package io.github.jgfyyvzss.soarlog.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import io.github.jgfyyvzss.soarlog.core.endorsement.EndorsementImageRules
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An endorsement's image, from the phone's own camera app or the system photo picker, so the app declares no CAMERA
 * or storage permission. Either way the picture lands in a private cache file, which [process] or [fromPicked] turn
 * into the stored JPEG and delete at once.
 */
class EndorsementImage(private val context: Context) {
    private val file get() = File(File(context.cacheDir, "endorsement"), "capture.jpg")

    /** Where the camera app should write: a fresh, empty file, shared through the app's file provider. */
    fun captureUri(): Uri {
        val f = file
        f.parentFile?.mkdirs()
        f.delete()
        return FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    /** The stored image from the camera app's file, or null if there is none or it cannot be used. The file is always deleted. */
    suspend fun process(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) null else shrink(file)
        } finally {
            discard()
        }
    }

    /**
     * The stored image from a picked one. It is copied to the cache first, because its EXIF orientation and size are
     * read from a file. Null if it cannot be read or is too large even after shrinking.
     */
    suspend fun fromPicked(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            file.parentFile?.mkdirs()
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } }
            }.getOrNull()
            if (copied == null || copied == 0L) null else shrink(file)
        } finally {
            discard()
        }
    }

    /** Deletes the work file, for example when the pilot leaves the screen. */
    fun discard() {
        file.delete()
    }

    companion object {
        /**
         * Turns a picture upright, scales it to [EndorsementImageRules.MAX_SIDE] pixels on its longest side and
         * re-encodes it at the first quality that fits, as [EndorsementImageRules.choose] decides.
         */
        fun shrink(source: File): ByteArray? {
            val upright = decodeUpright(source, EndorsementImageRules::sampleSize, EndorsementImageRules::scaledSize) ?: return null
            try {
                return EndorsementImageRules.choose { q -> upright.jpeg(q) }
            } finally {
                upright.recycle()
            }
        }
    }
}
