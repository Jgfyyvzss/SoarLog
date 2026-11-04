package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.backup.BackupTestData.T1
import io.github.jgfyyvzss.soarlog.english
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {
    private fun ok(text: String) = (BackupCodec.decode(text) as DecodeResult.Ok).file
    private fun refused(text: String) = (BackupCodec.decode(text) as DecodeResult.Refused).message.english()

    @Test fun encodeDecodeEncodeIsIdentical() {
        val first = BackupCodec.encode(BackupTestData.file())
        val second = BackupCodec.encode(ok(first))
        assertEquals(first, second)
    }

    @Test fun arraysAreSortedAndKeysInFixedOrder() {
        val text = BackupCodec.encode(BackupTestData.file())
        assertEquals(listOf("f0", "f1", "f2"), ok(text).flights.map { it.id })
        assertTrue(text.startsWith("{\n  \"format\": \"glider-logbook-backup\",\n  \"format_version\": 3,\n  \"exported_at\""))
        val order = listOf("\"launch_types\"", "\"clubs\"", "\"airfields\"", "\"gliders\"", "\"flights\"", "\"witness_signoffs\"", "\"opening_balances\"", "\"import_batches\"", "\"settings\"")
        assertEquals(order, order.sortedBy { text.indexOf(it) })
        assertTrue("nulls are written", text.contains("\"deleted_at\": null"))
        assertTrue(text.endsWith("}\n"))
    }

    @Test fun timesRoundTripToTheMillisecond() {
        val ms = BackupTime.fromIso(BackupTestData.T2)!!
        assertEquals(BackupTestData.T2, BackupTime.toIso(ms))
        assertEquals(T1, BackupTime.toIso(BackupTime.fromIso(T1)!!))
        assertEquals("1970-01-01T00:00:00Z", BackupTime.toIso(0))
    }

    @Test fun refusesOtherFilesAndNewerVersions() {
        assertTrue(refused("not json").contains("not valid JSON"))
        assertTrue(refused("""{"format":"something-else","format_version":1}""").contains("not a logbook backup"))
        assertTrue(refused("""{"format":"glider-logbook-backup"}""").contains("no format version"))
        assertTrue(refused("""{"format":"glider-logbook-backup","format_version":4}""").contains("newer version"))
    }

    @Test fun ignoresUnknownFieldsAndFillsDefaults() {
        val file = ok("""{"format":"glider-logbook-backup","format_version":3,"future_table":[1,2],"settings":{"pilot_name":"Sam","new_flag":true}}""")
        assertEquals("Sam", file.settings.pilotName)
        assertEquals(BackupSchedule.DEFAULT_AFTER_CHANGES, file.settings.backupAfterChanges)
        assertTrue(file.flights.isEmpty())
    }

    @Test fun theOldJsonVersionsAreRefusedWithTheWayBack() {
        for (v in 1..2) {
            val old = BackupCodec.encode(BackupTestData.file()).replace("\"format_version\": 3", "\"format_version\": $v")
            assertEquals(
                "This backup is a .json file from an older version of SoarLog. To use it, install SoarLog $LAST_JSON_BACKUP_APP, " +
                    "restore the backup there, then update the app.",
                refused(old),
            )
        }
    }

    @Test fun exportTimeIsIgnoredWhenComparing() {
        val a = BackupCodec.encode(BackupTestData.file())
        val b = BackupCodec.encode(BackupTestData.file().copy(exportedAt = T1))
        assertEquals(BackupCodec.withoutExportTime(a), BackupCodec.withoutExportTime(b))
    }
}
