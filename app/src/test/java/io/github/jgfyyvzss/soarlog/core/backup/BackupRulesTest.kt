package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.backup.BackupTestData.T1
import io.github.jgfyyvzss.soarlog.core.backup.BackupTestData.T2
import io.github.jgfyyvzss.soarlog.core.backup.BackupTestData.flight
import io.github.jgfyyvzss.soarlog.english
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRulesTest {
    private val base = BackupTestData.file()

    @Test fun validFileHasNoProblems() {
        assertEquals(emptyList<String>(), BackupValidation.validate(base))
    }

    @Test fun reportsBrokenReferencesEnumsAndDurations() {
        val bad = base.copy(
            flights = listOf(
                flight("x1", 1, T1).copy(gliderId = "missing", role = "DUAL", durationMinutes = 0),
                flight("x2", 2, "yesterday").copy(landingAirfieldId = "a1", landingPlace = "Paddock"),
                flight("x3", 3, T1).copy(launchTime = "13:00", landingTime = "12:00", source = "FAX"),
            ),
            airfields = base.airfields.map { it.copy(clubIds = listOf("c1", "c9"), defaultLaunchTypeId = "nope") },
        )
        val p = BackupValidation.validate(bad).map { it.english() }
        listOf(
            "Flight 1 refers to a missing glider", "Flight 1: unknown role DUAL", "Flight 1: duration 0 minutes is out of range",
            "Flight 2 has an unreadable time: yesterday", "Flight 2 has both a landing airfield and a landing place",
            "Flight 3: landing is not after launch", "Flight 3: unknown source FAX",
            "Airfield YMBT refers to a missing club c9", "Airfield YMBT refers to a missing launch type",
        ).forEach { assertTrue("expected: $it in $p", it in p) }
    }

    @Test fun reportsLiveLogNumberCollisionsButNotTombstones() {
        assertTrue(BackupValidation.liveCollisions(base).isEmpty())
        val clash = base.copy(flights = base.flights + flight("f9", 341, T1))
        assertEquals(
            listOf("Log number 341 is used by two different flights: the flight on 2026-10-17 in FQM and the flight on 2026-10-17 in FQM"),
            BackupValidation.liveCollisions(clash).map { it.english() },
        )
    }

    @Test fun mergeKeepsTheNewerCopyByIdAndKeepsLocalSettings() {
        val local = base.copy(settings = BackupSettings(pilotName = "Local"))
        val incoming = base.copy(
            flights = listOf(
                flight("f1", 340, T2, notes = "newer"), // newer edit wins
                flight("f2", 341, T1, notes = "older"), // older copy loses
                flight("f3", 342, T1),                  // new flight is added
            ),
            settings = BackupSettings(pilotName = "Incoming"),
        )
        val r = BackupMerge.merge(local, incoming)
        val byId = r.merged.flights.associateBy { it.id }
        assertEquals("newer", byId.getValue("f1").notes)
        assertEquals(null, byId.getValue("f2").notes)
        assertTrue("f3" in byId)
        assertTrue("f0" in byId)
        assertEquals("Local", r.merged.settings.pilotName)
        assertEquals(1, r.summary.added)
        assertEquals(1, r.summary.updated)
    }

    @Test fun newerTombstoneDeletesAndOlderOneDoesNot() {
        val deleted = BackupMerge.merge(base, base.copy(flights = listOf(flight("f1", 340, "2026-10-20T00:00:00Z", deletedAt = "2026-10-20T00:00:00Z"))))
        assertEquals("2026-10-20T00:00:00Z", deleted.merged.flights.single { it.id == "f1" }.deletedAt)
        val kept = BackupMerge.merge(base, base.copy(flights = listOf(flight("f1", 340, "2026-01-01T00:00:00Z", deletedAt = "2026-01-01T00:00:00Z"))))
        assertEquals(null, kept.merged.flights.single { it.id == "f1" }.deletedAt)
    }

    @Test fun mergeThatCreatesALogNumberCollisionIsCaught() {
        val incoming = base.copy(flights = listOf(flight("other", 341, T2)))
        val merged = BackupMerge.merge(base, incoming).merged
        assertTrue(BackupValidation.validate(merged).any { it.english().startsWith("Log number 341 is used by two different flights") })
    }

    @Test fun openingBalancesMatchByRole() {
        val incoming = base.copy(openingBalances = listOf(BackupOpeningBalance("PIC", "2025-12-31", 6000, 300, T2)))
        val pic = BackupMerge.merge(base, incoming).merged.openingBalances.single { it.role == "PIC" }
        assertEquals(6000, pic.minutes)
    }

    @Test fun scheduleTriggers() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        assertFalse("nothing changed", BackupSchedule.isDue(0, null, now, 20, 7))
        assertTrue("enough changes", BackupSchedule.isDue(20, now, now, 20, 7))
        assertFalse(BackupSchedule.isDue(19, now - day, now, 20, 7))
        assertTrue("days passed", BackupSchedule.isDue(1, now - 7 * day, now, 20, 7))
        assertTrue("never backed up", BackupSchedule.isDue(1, null, now, 20, 7))
        assertFalse("changes trigger off", BackupSchedule.isDue(500, now, now, 0, 7))
        assertFalse("both off", BackupSchedule.isDue(500, null, now, 0, 0))
        assertTrue(BackupSchedule.isStale(null, now))
        assertFalse(BackupSchedule.isStale(now - 7 * day, now))
        assertTrue(BackupSchedule.isStale(now - 7 * day - 1, now))
    }

    @Test fun fileNamesAndPruning() {
        assertEquals("glider-logbook-20261020-091502.zip", BackupSchedule.fileName(LocalDateTime.of(2026, 10, 20, 9, 15, 2)))
        val names = (1..7).map { "glider-logbook-202610${"%02d".format(it)}-120000.zip" } +
            "notes.txt" + "glider-logbook-old.zip" + "glider-logbook-20261001-120000.json"
        assertEquals(
            "the newest 5 zips stay; other files and old .json backups are never touched",
            listOf("glider-logbook-20261002-120000.zip", "glider-logbook-20261001-120000.zip"),
            BackupSchedule.filesToPrune(names),
        )
    }
}
