package io.github.jgfyyvzss.soarlog.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import io.github.jgfyyvzss.soarlog.core.witness.WitnessPhotoRules
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The witness photo. The phone's own camera app takes it (through `ActivityResultContracts.TakePicture`), so the
 * app declares no CAMERA permission. The camera app writes into a private cache file handed over by the file
 * provider; [process] turns that into the stored JPEG and deletes the original at once.
 */
class WitnessPhoto(private val context: Context) {
    private val file get() = File(File(context.cacheDir, "witness"), "capture.jpg")

    /** Where the camera app should write: a fresh, empty file, shared through the app's file provider. */
    fun captureUri(): Uri {
        val f = file
        f.parentFile?.mkdirs()
        f.delete()
        return FileProvider.getUriForFile(context, context.packageName + ".files", f)
    }

    /** The stored photo from the camera app's file, or null if there is none or it cannot be read. The file is always deleted. */
    suspend fun process(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || file.length() == 0L) null else shrink(file)
        } finally {
            discard()
        }
    }

    /** Deletes the camera app's file, for example when the pilot cancels the sign-off. */
    fun discard() {
        file.delete()
    }

    companion object {
        /**
         * Turns a camera JPEG upright, scales it to [WitnessPhotoRules.MAX_SIDE] pixels on its longest side and
         * re-encodes it. Re-encoding from pixels writes no EXIF data, so location and camera details are gone.
         * Lower qualities are tried if the result is over [WitnessPhotoRules.MAX_BYTES].
         */
        fun shrink(source: File): ByteArray? {
            val upright = decodeUpright(source, WitnessPhotoRules::sampleSize, WitnessPhotoRules::scaledSize) ?: return null
            try {
                return WitnessPhotoRules.FALLBACK_QUALITIES.asSequence()
                    .map { q -> upright.jpeg(q) }
                    .firstOrNull { it.size <= WitnessPhotoRules.MAX_BYTES }
            } finally {
                upright.recycle()
            }
        }
    }
}

/**
 * Decodes a photo at a reduced sample size, so a full camera photo is never held in memory, then scales it and turns
 * it upright from its EXIF orientation. Shared by witness photos and endorsement images, which differ only in size.
 */
internal fun decodeUpright(source: File, sampleSize: (Int, Int) -> Int, scaledSize: (Int, Int) -> Pair<Int, Int>): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.path, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val decoded = BitmapFactory.decodeFile(
        source.path,
        BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight) },
    ) ?: return null
    val orientation = runCatching {
        ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    val t = WitnessPhotoRules.transform(orientation)
    val (w, h) = scaledSize(decoded.width, decoded.height)
    val scaled = if (w == decoded.width && h == decoded.height) decoded else Bitmap.createScaledBitmap(decoded, w, h, true)
    if (scaled !== decoded) decoded.recycle()
    if (t.degrees == 0 && !t.mirror) return scaled
    val m = Matrix().apply {
        postRotate(t.degrees.toFloat())
        if (t.mirror) postScale(-1f, 1f)
    }
    val upright = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.height, m, true)
    if (upright !== scaled) scaled.recycle()
    return upright
}

/** The bitmap as a JPEG at [quality]. Encoding from pixels writes no EXIF data, so location and camera details are gone. */
internal fun Bitmap.jpeg(quality: Int): ByteArray =
    ByteArrayOutputStream().also { compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
