package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** One image asked for from a backup. */
sealed interface ImageRead {
    class Ok(val bytes: ByteArray) : ImageRead
    data object Missing : ImageRead
    data class Bad(val problem: MsgKey) : ImageRead
}

/** The images a backup carries, by SHA-256. Each is read and checked only when asked for, so few are in memory at once. */
fun interface BackupImages {
    fun read(sha256: String, maxBytes: Int): ImageRead
}

sealed interface Opened {
    /** A readable backup: its decoded `backup.json` and the open zip for its images. The caller closes [reader]. */
    class Ok(val reader: BackupZipReader, val file: BackupFile) : Opened
    data class Refused(val message: Msg) : Opened
}

/**
 * The backup file from docs/Endorsements.md: a zip holding `backup.json`, compressed, then `images/<sha256>.jpg`,
 * stored as they are since JPEGs do not shrink. An image is named by the hash its record already carries, so one
 * shared by several records is stored once.
 */
object BackupZip {
    const val JSON_ENTRY = "backup.json"
    /** Far beyond any real logbook; a larger `backup.json` is refused rather than read into memory. */
    const val MAX_JSON_BYTES = 50 * 1024 * 1024
    /** How much of a file that is not a zip is read to recognise an old .json backup. */
    private const val SNIFF_BYTES = 64 * 1024

    private val sha256Pattern = Regex("^[0-9a-f]{64}$")

    fun imageEntry(sha256: String) = "images/$sha256.jpg"

    /**
     * Writes [json] and then each image in [hashes], in name order, to [out], and closes it. [read] gives an
     * image's bytes, or null for one that is no longer there, which is then left out.
     */
    fun write(out: OutputStream, json: String, hashes: Collection<String>, read: (String) -> ByteArray?) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY).apply { method = ZipEntry.DEFLATED })
            zip.write(json.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            for (sha in hashes.map { it.lowercase() }.distinct().sorted()) {
                val bytes = read(sha) ?: continue
                // A stored entry needs its size and CRC before its data.
                zip.putNextEntry(
                    ZipEntry(imageEntry(sha)).apply {
                        method = ZipEntry.STORED
                        size = bytes.size.toLong()
                        compressedSize = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    },
                )
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    /** Opens [file] and decodes its `backup.json`, or says why it cannot be used. Nothing is extracted to disk. */
    fun open(file: File): Opened {
        val zip = try {
            ZipFile(file)
        } catch (e: IOException) {
            return Opened.Refused(notAZip(file))
        }
        val reader = BackupZipReader(zip)
        val result = reader.decode()
        if (result !is Opened.Ok) reader.close()
        return result
    }

    /** An old .json backup gets the way back through an older app; anything else is not a backup. */
    private fun notAZip(file: File): Msg {
        val start = runCatching { file.inputStream().use { readAtMost(it, SNIFF_BYTES, truncate = true) } }.getOrNull()
            ?.decodeToString().orEmpty()
        val oldJson = Regex(""""format"\s*:\s*"${Regex.escape(BACKUP_FORMAT)}"""").containsMatchIn(start)
        return if (oldJson) msg(MsgKey.BACKUP_OLD_FORMAT, LAST_JSON_BACKUP_APP) else msg(MsgKey.BACKUP_NOT_BACKUP)
    }

    internal fun isSha256(text: String) = sha256Pattern.matches(text)

    /** At most [max] bytes of [input]: null when there are more, or the first [max] when [truncate] is set. */
    internal fun readAtMost(input: InputStream, max: Int, truncate: Boolean = false): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (out.size() <= max) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            out.write(buffer, 0, n)
        }
        return if (truncate) out.toByteArray().copyOf(max) else null
    }
}

/**
 * An open backup zip. Only `backup.json` and `images/<sha256>.jpg` for a hash asked for are ever read, so other
 * entries are ignored, and sizes are counted while reading rather than trusted from the zip's headers.
 */
class BackupZipReader internal constructor(private val zip: ZipFile) : BackupImages, Closeable {
    internal fun decode(): Opened {
        val entry = zip.getEntry(BackupZip.JSON_ENTRY) ?: return Opened.Refused(msg(MsgKey.BACKUP_NOT_BACKUP))
        val bytes = try {
            zip.getInputStream(entry).use { BackupZip.readAtMost(it, BackupZip.MAX_JSON_BYTES) }
        } catch (e: IOException) {
            return Opened.Refused(msg(MsgKey.BACKUP_DAMAGED, e.message?.take(200) ?: e.javaClass.simpleName))
        } ?: return Opened.Refused(msg(MsgKey.BACKUP_TOO_LARGE))
        return when (val d = BackupCodec.decode(bytes.decodeToString())) {
            is DecodeResult.Ok -> Opened.Ok(this, d.file)
            is DecodeResult.Refused -> Opened.Refused(d.message)
        }
    }

    /** The image with [sha256], if it is a JPEG of at most [maxBytes] whose hash matches its name. */
    override fun read(sha256: String, maxBytes: Int): ImageRead {
        val sha = sha256.lowercase()
        if (!BackupZip.isSha256(sha)) return ImageRead.Bad(MsgKey.BK_PHOTO_HASH)
        val entry = zip.getEntry(BackupZip.imageEntry(sha)) ?: return ImageRead.Missing
        val bytes = try {
            zip.getInputStream(entry).use { BackupZip.readAtMost(it, maxBytes) }
        } catch (e: IOException) {
            return ImageRead.Bad(MsgKey.BK_PHOTO_UNREADABLE)
        } ?: return ImageRead.Bad(MsgKey.BK_PHOTO_TOO_LARGE)
        return when {
            !BackupWitness.isJpeg(bytes) -> ImageRead.Bad(MsgKey.BK_PHOTO_NOT_JPEG)
            WitnessSeal.sha256Hex(bytes) != sha -> ImageRead.Bad(MsgKey.BK_PHOTO_HASH)
            else -> ImageRead.Ok(bytes)
        }
    }

    override fun close() = zip.close()
}
