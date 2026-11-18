package io.github.jgfyyvzss.soarlog.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jgfyyvzss.soarlog.core.FlightDraft
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.backup.BackupCodec
import io.github.jgfyyvzss.soarlog.core.backup.DecodeResult
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.ui.text
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Step 3 done criteria with the backup zip: identical round trip, Restore into an empty database, and Merge collisions. */
@RunWith(AndroidJUnit4::class)
class BackupServiceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var source: LogbookDatabase
    private lateinit var target: LogbookDatabase
    private val settings = SettingsRepository(context)
    private var clock = 1_760_000_000_000L
    private val files = mutableListOf<File>()

    @Before fun open() {
        source = LogbookDatabase.inMemory(context)
        target = LogbookDatabase.inMemory(context)
    }

    @After fun close() {
        source.close()
        target.close()
        files.forEach { it.delete() }
    }

    private fun service(db: LogbookDatabase) = BackupService(db, settings, "test", now = { clock })
    private fun repo(db: LogbookDatabase) = LogbookRepository(db, now = { ++clock })

    private fun SaveResult.id() = (this as SaveResult.Saved).id

    private fun newFile(suffix: String = ".zip") = File.createTempFile("backup", suffix, context.cacheDir).also { files += it }

    /** The logbook in [db] as a backup zip. */
    private suspend fun export(db: LogbookDatabase, includeImages: Boolean = true): File =
        newFile().also { service(db).export(it.outputStream(), includeImages) }

    private fun jsonOf(zip: File): String = ZipFile(zip).use { z -> z.getInputStream(z.getEntry("backup.json")).readBytes().decodeToString() }
    private fun entries(zip: File): Int = ZipFile(zip).use { it.size() }

    private suspend fun restore(db: LogbookDatabase, zip: File) {
        val p = service(db).prepareRestore(zip) as Prepared.Restore
        try { service(db).restore(p) } finally { p.close() }
    }

    private suspend fun merge(db: LogbookDatabase, zip: File) {
        val p = service(db).prepareMerge(zip) as Prepared.Merge
        try { service(db).merge(p) } finally { p.close() }
    }

    /** A logbook with links, defaults, a deleted flight, an edited balance and an extra launch type. */
    private suspend fun seed(db: LogbookDatabase): LogbookRepository {
        val r = repo(db)
        val c1 = r.saveClub(null, "MBGC").id()
        val c2 = r.saveClub(null, "Bogong").id()
        val aero = r.saveLaunchType(null, "Bungee", archived = false).id()
        val a = r.saveAirfield(null, "YMBT", -36.7314, 147.17, 300, 1500, listOf(c1, c2), archived = false,
            defaultClubId = c2, defaultLaunchTypeId = aero).id()
        val g = r.saveGlider(null, "VH-FQM", "Hornet", archived = false).id()
        r.saveGlider(null, "B4", "", archived = true)
        fun draft(logNo: Int, minutes: String) = FlightDraft(
            logNoText = logNo.toString(), date = LocalDate.of(2026, 10, 17), durationText = minutes, gliderId = g,
            airfieldId = a, clubId = c1, launchTypeId = Keys.seededLaunchTypeId("Winch"), role = Role.PIC, picName = "Pat",
            launchTimeText = "1305", landingTimeText = "1736", notes = "Wave, \"quoted\" and ünïcode",
        )
        r.saveFlight(null, draft(1, "431"))
        val second = r.saveFlight(null, draft(2, "45").copy(launchTimeText = "", landingTimeText = "")).second!!
        r.saveFlight(null, draft(3, "100").copy(launchTimeText = "", landingTimeText = ""))
        r.deleteFlight(second)
        val balances = r.observeOpeningBalances().first().map {
            if (it.role == Role.PIC.name) it.copy(minutes = 6000, flights = 300, asOfDate = "2025-12-31") else it
        }
        r.saveOpeningBalances(balances)
        return r
    }

    @Test fun exportRestoreExportIsIdentical() = runTest {
        seed(source)
        val first = export(source)

        val restore = service(target).prepareRestore(first)
        assertTrue("$restore", restore is Prepared.Restore)
        try { service(target).restore(restore as Prepared.Restore) } finally { restore.close() }
        val second = export(target)

        assertEquals(BackupCodec.withoutExportTime(jsonOf(first)), BackupCodec.withoutExportTime(jsonOf(second)))
        assertEquals(2, (BackupCodec.decode(jsonOf(second)) as DecodeResult.Ok).file.flights.count { it.deletedAt == null })
    }

    /** Witnesses flights 1 and 3 of [seed] with a small JPEG-like photo. */
    private suspend fun witness(db: LogbookDatabase): ByteArray {
        val photo = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(3_000) { it.toByte() }
        val live = db.backupDao().allFlights().filter { it.deletedAt == null }.map { it.id }
        WitnessRepository(db, now = { ++clock }).sign(live, "Lee Instructor", "GFA 1", photo, consent = true)
        return photo
    }

    @Test fun witnessDataRoundTripsWithAndWithoutImages() = runTest {
        seed(source)
        val photo = witness(source)
        val full = export(source)
        assertTrue(jsonOf(full).contains("\"contains_images\": true"))
        assertEquals("backup.json and one photo", 2, entries(full))

        val restore = service(target).prepareRestore(full) as Prepared.Restore
        assertEquals(2, restore.witness.verified)
        try { service(target).restore(restore) } finally { restore.close() }
        assertEquals(BackupCodec.withoutExportTime(jsonOf(full)), BackupCodec.withoutExportTime(jsonOf(export(target))))
        val statuses = WitnessRepository(target).statuses()
        assertEquals(listOf(SealStatus.WITNESSED, SealStatus.WITNESSED), statuses.values.toList())
        assertTrue(photo.contentEquals(target.backupDao().allWitnessSignoffs().single().photo))

        val bare = export(source, includeImages = false)
        val bareJson = jsonOf(bare)
        assertTrue(bareJson.contains("\"contains_images\": false"))
        assertEquals("backup.json only", 1, entries(bare))
        assertTrue("names and seals stay", bareJson.contains("Lee Instructor") && bareJson.contains("\"witness_seal\": \""))
        // Merging the image-less file into the full logbook keeps the photo.
        merge(target, bare)
        assertTrue(photo.contentEquals(target.backupDao().allWitnessSignoffs().single().photo))
    }

    @Test fun mergingAFullBackupFillsAMissingPhoto() = runTest {
        seed(source)
        val photo = witness(source)
        restore(target, export(source, includeImages = false))
        val restored = target.backupDao().allWitnessSignoffs().single()
        assertNull("restored without images", restored.photo)
        assertEquals(SealStatus.WITNESSED, WitnessRepository(target).statuses().values.first())

        merge(target, export(source))
        assertTrue(photo.contentEquals(target.backupDao().allWitnessSignoffs().single().photo))
    }

    @Test fun restoredLogbookWorksNormally() = runTest {
        seed(source)
        restore(target, export(source))
        assertEquals(4, repo(target).nextLogNo())
        // The partial unique indexes were recreated after the rows went in.
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' AND name LIKE 'live_%'").use {
            it.moveToFirst()
            assertEquals(SchemaSql.partialIndexNames.size, it.getInt(0))
        }
        assertTrue(service(target).isEmpty().not())
    }

    @Test fun restoreNeedsAnEmptyLogbook() = runTest {
        seed(source)
        repo(target).saveGlider(null, "GZY", "Junior", archived = false)
        val p = service(target).prepareRestore(export(source))
        assertTrue(p is Prepared.Refused && p.problems.single().text(context).startsWith("Restore needs an empty logbook"))
    }

    @Test fun refusesAFileFromANewerFormat() = runTest {
        val zip = newFile().also { f ->
            ZipOutputStream(f.outputStream()).use { z ->
                z.putNextEntry(ZipEntry("backup.json"))
                z.write("""{"format":"glider-logbook-backup","format_version":99}""".toByteArray())
                z.closeEntry()
            }
        }
        val p = service(target).prepareRestore(zip)
        assertTrue(p is Prepared.Refused && p.problems.single().text(context).contains("newer version"))
    }

    @Test fun refusesAnOldJsonBackupWithTheWayBack() = runTest {
        val json = newFile(".json").also { it.writeText("""{"format":"glider-logbook-backup","format_version":2}""") }
        val p = service(target).prepareRestore(json)
        assertTrue("$p", p is Prepared.Refused && p.problems.single().text(context).contains("older version of SoarLog"))
    }

    @Test fun mergeAddsNewerRecordsAndReportsCollisions() = runTest {
        seed(source)
        val backup = export(source)

        // Merging into a copy that already has everything changes nothing.
        restore(target, backup)
        val same = service(target).prepareMerge(backup) as Prepared.Merge
        same.close()
        assertEquals(0, same.summary.added)
        assertEquals(0, same.summary.updated)

        // A flight added on the other phone merges in.
        val r = repo(source)
        val g = r.observeGliders().first().first { it.registration == "VH-FQM" }.id
        val a = r.observeAirfields().first().single().id
        val c = r.clubsFor(a).first().id
        val wt = Keys.seededLaunchTypeId("Winch")
        r.saveFlight(null, FlightDraft(logNoText = "4", date = LocalDate.of(2026, 10, 19), durationText = "50",
            gliderId = g, airfieldId = a, clubId = c, launchTypeId = wt, role = Role.STUDENT, picName = "Sam"))
        val merge = service(target).prepareMerge(export(source)) as Prepared.Merge
        assertEquals(1, merge.summary.added)
        try { service(target).merge(merge) } finally { merge.close() }
        assertEquals(5, repo(target).nextLogNo())

        // A different flight with log number 4 on this phone collides, and nothing is committed.
        r.saveFlight(null, FlightDraft(logNoText = "5", date = LocalDate.of(2026, 10, 20), durationText = "50",
            gliderId = g, airfieldId = a, clubId = c, launchTypeId = wt, role = Role.PIC, picName = "Pat"))
        val t = repo(target)
        val (_, local5) = t.saveFlight(null, FlightDraft(logNoText = "5", date = LocalDate.of(2026, 10, 21), durationText = "20",
            gliderId = g, airfieldId = a, clubId = c, launchTypeId = wt, role = Role.PIC, picName = "Pat"))
        val clash = service(target).prepareMerge(export(source))
        assertTrue("$clash", clash is Prepared.Refused && clash.problems.any { it.text(context).startsWith("Log number 5 is used by two different flights") })
        assertEquals(20, t.flight(local5!!)!!.durationMinutes)
    }
}
