package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import io.github.jgfyyvzss.soarlog.english
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The backup zip (docs/Endorsements.md, part 1): layout, round trip, and the checks on a damaged or hostile file. */
class BackupZipTest {
    @get:Rule val folder = TemporaryFolder()

    private fun jpeg(n: Int, seed: Int = 0) = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(n) { (it + seed).toByte() }
    private val photo = jpeg(3_000)
    private val other = jpeg(2_000, seed = 7)
    private val hash = WitnessSeal.sha256Hex(photo)
    private val otherHash = WitnessSeal.sha256Hex(other)
    private val json = BackupCodec.encode(BackupTestData.file().copy(containsImages = true))

    private fun written(hashes: List<String>, read: (String) -> ByteArray?): File =
        folder.newFile().also { f -> BackupZip.write(f.outputStream(), json, hashes, read) }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): File = folder.newFile().also { f ->
        ZipOutputStream(f.outputStream()).use { z -> entries.forEach { (name, bytes) -> z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry() } }
    }

    private fun opened(f: File) = BackupZip.open(f) as Opened.Ok
    private fun refused(f: File) = (BackupZip.open(f) as Opened.Refused).message

    @Test fun jsonFirstThenEachImageOnceInNameOrder() {
        val images = mapOf(hash to photo, otherHash to other)
        val f = written(listOf(hash, otherHash, hash.uppercase())) { images[it] }
        ZipFile(f).use { z ->
            val entries = z.entries().toList()
            assertEquals(listOf("backup.json") + listOf(hash, otherHash).sorted().map { "images/$it.jpg" }, entries.map { it.name })
            assertEquals(ZipEntry.DEFLATED, entries[0].method)
            assertTrue("JPEGs are stored as they are", entries.drop(1).all { it.method == ZipEntry.STORED })
        }
    }

    @Test fun theJsonAndImagesRoundTrip() {
        val o = opened(written(listOf(hash)) { photo })
        o.reader.use { r ->
            assertEquals(json, BackupCodec.encode(o.file))
            val read = r.read(hash, BackupWitness.MAX_PHOTO_BYTES)
            assertArrayEquals(photo, (read as ImageRead.Ok).bytes)
            assertEquals(ImageRead.Missing, r.read(otherHash, BackupWitness.MAX_PHOTO_BYTES))
        }
    }

    @Test fun anImageNoLongerThereIsLeftOut() {
        val f = written(listOf(hash, otherHash)) { if (it == hash) photo else null }
        ZipFile(f).use { z -> assertEquals(2, z.size()) }
    }

    @Test fun imagesAreCheckedWhenRead() {
        val notJpeg = ByteArray(500) { 1 }
        val f = zipOf(
            "backup.json" to json.toByteArray(),
            "images/$hash.jpg" to photo,
            "images/${WitnessSeal.sha256Hex(notJpeg)}.jpg" to notJpeg,
            "images/$otherHash.jpg" to photo, // named for another image
        )
        opened(f).reader.use { r ->
            assertEquals(ImageRead.Bad(MsgKey.BK_PHOTO_TOO_LARGE), r.read(hash, photo.size - 1))
            assertTrue(r.read(hash, photo.size) is ImageRead.Ok)
            assertEquals(ImageRead.Bad(MsgKey.BK_PHOTO_NOT_JPEG), r.read(WitnessSeal.sha256Hex(notJpeg), 100_000))
            assertEquals(ImageRead.Bad(MsgKey.BK_PHOTO_HASH), r.read(otherHash, 100_000))
            assertEquals("not a hash, so never a path", ImageRead.Bad(MsgKey.BK_PHOTO_HASH), r.read("../backup", 100_000))
        }
    }

    @Test fun otherEntriesAreIgnored() {
        val f = zipOf(
            "backup.json" to json.toByteArray(),
            "notes.txt" to "hello".toByteArray(),
            "images/${hash.uppercase()}.jpg" to photo,
        )
        opened(f).reader.use { r -> assertEquals("only lower-case hex names are read", ImageRead.Missing, r.read(hash, 100_000)) }
    }

    @Test fun aZipWithoutBackupJsonIsNotABackup() {
        assertEquals(MsgKey.BACKUP_NOT_BACKUP, refused(zipOf("images/$hash.jpg" to photo)).key)
        assertEquals(MsgKey.BACKUP_NOT_JSON, refused(zipOf("backup.json" to "not json".toByteArray())).key)
    }

    @Test fun anOverSizeBackupJsonIsRefused() {
        // Zeros compress to almost nothing, so a small zip can unpack to far more; the size is counted while reading.
        val big = folder.newFile().also { f ->
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("backup.json"))
                val block = ByteArray(1024 * 1024)
                repeat(BackupZip.MAX_JSON_BYTES / block.size + 1) { z.write(block) }
                z.closeEntry()
            }
        }
        assertTrue(big.length() < 1024 * 1024)
        assertEquals(MsgKey.BACKUP_TOO_LARGE, refused(big).key)
    }

    @Test fun anOldJsonBackupGetsTheWayBack() {
        val old = folder.newFile("glider-logbook-20261020-091502.json")
        old.writeText(json.replace("\"format_version\": 3", "\"format_version\": 2"))
        val m = refused(old)
        assertEquals(MsgKey.BACKUP_OLD_FORMAT, m.key)
        assertTrue(m.english().contains("install SoarLog $LAST_JSON_BACKUP_APP"))
    }

    @Test fun otherFilesAreNotBackups() {
        assertEquals(MsgKey.BACKUP_NOT_BACKUP, refused(folder.newFile().also { it.writeText("hello") }).key)
        assertEquals(MsgKey.BACKUP_NOT_BACKUP, refused(folder.newFile()).key)
    }

    @Test fun readAtMostCountsTheBytes() {
        fun input(n: Int): InputStream = ByteArrayInputStream(ByteArray(n) { 1 })
        assertEquals(10, BackupZip.readAtMost(input(10), 10)!!.size)
        assertNull(BackupZip.readAtMost(input(11), 10))
        assertEquals(10, BackupZip.readAtMost(input(40_000), 10, truncate = true)!!.size)
        assertEquals(0, BackupZip.readAtMost(input(0), 10)!!.size)
    }
}
