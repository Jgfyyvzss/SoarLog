package io.github.jgfyyvzss.soarlog.core.ogn

import io.github.jgfyyvzss.soarlog.core.ClockTime
import io.github.jgfyyvzss.soarlog.core.LandingChoice
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.msg
import java.io.File
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Results rows and flight drafts from the real Flightbook responses in testdata. */
class OgnImportTest {
    private fun day(name: String): OgnDay {
        val text = listOf(File("../testdata"), File("testdata")).first { it.isDirectory }.resolve(name).readText()
        return (FlightbookParser.parse(text) as OgnResult.Ok).day
    }

    private val ymbt by lazy { day("Flightbook_YMBT_2026-11-09.json") }
    private val ytem by lazy { day("Flightbook_YTEM_2026-11-08.json") }

    private fun input(recordTimes: Boolean = false, recordLandingPlace: Boolean = false, landingAirfieldId: String? = null) = DraftInput(
        logNo = 342, gliderId = "g", airfieldId = "a", clubId = null, launchTypeId = "lt", landingAirfieldId = landingAirfieldId,
        recordTimes = recordTimes, recordLandingPlace = recordLandingPlace, profileName = " Peter ", notes = "",
    )

    @Test fun rowsShowTheMostRecentFlightFirst() {
        val rows = OgnImport.rows(ymbt, emptyList(), emptyList())
        val starts = rows.map { it.flight.start }
        assertEquals(starts.sortedDescending(), starts)
        assertEquals(4, rows.size)
    }

    @Test fun towPlanesAreHiddenUntilShownAndTheGliderFilterNarrows() {
        val rows = OgnImport.rows(ytem, emptyList(), emptyList())
        assertEquals(11, OgnImport.visible(rows, showAll = false, glider = "").size)
        assertEquals(22, OgnImport.visible(rows, showAll = true, glider = "").size)
        val ls8 = OgnImport.visible(rows, showAll = false, glider = "lsl")
        assertTrue(ls8.isNotEmpty())
        assertTrue(ls8.all { it.flight.registration == "VH-LSL" })
    }

    @Test fun gliderMatchingHandlesMissingPrefixesAndAmbiguity() {
        val gvs = ymbt.flights.first { it.registration == "GVS" }
        assertEquals("1" to GliderMatch.MATCHED, OgnImport.matchGlider(gvs, listOf(OgnGlider("1", "VH-GVS"), OgnGlider("2", "VH-GVA"))))
        assertEquals(null to GliderMatch.NOT_IN_LIST, OgnImport.matchGlider(gvs, listOf(OgnGlider("2", "VH-GVA"))))
        assertEquals(null to GliderMatch.AMBIGUOUS, OgnImport.matchGlider(gvs, listOf(OgnGlider("1", "VH-GVS"), OgnGlider("3", "GVS"))))
        assertEquals(null to GliderMatch.UNIDENTIFIED, OgnImport.matchGlider(gvs.copy(identified = false), listOf(OgnGlider("1", "VH-GVS"))))
    }

    @Test fun alreadyLoggedByLaunchTimeOrDuration() {
        val f = ymbt.flights[0] // 12h26, 15740 s
        val launch = ClockTime(12 * 60 + 30)
        assertTrue(OgnImport.alreadyLogged(f, "g", listOf(LoggedFlight("g", launch, 100))))
        assertFalse("another glider", OgnImport.alreadyLogged(f, "g", listOf(LoggedFlight("h", launch, 262))))
        assertFalse("6 minutes apart", OgnImport.alreadyLogged(f, "g", listOf(LoggedFlight("g", ClockTime(12 * 60 + 32), 262))))
        assertTrue("no time: duration within 2", OgnImport.alreadyLogged(f, "g", listOf(LoggedFlight("g", null, 264))))
        assertFalse("no time: duration 3 apart", OgnImport.alreadyLogged(f, "g", listOf(LoggedFlight("g", null, 265))))
        assertFalse("unmatched glider", OgnImport.alreadyLogged(f, null, listOf(LoggedFlight("g", launch, 262))))
    }

    @Test fun uncertainTimesAreApproximateUpToAnHourAndCheckedAbove() {
        val rows = OgnImport.rows(ymbt, emptyList(), emptyList()).associateBy { it.flight.index }
        assertEquals(654, rows.getValue(0).checkTimeMinutes)
        assertNull(rows.getValue(0).approximateMinutes)
        assertEquals(1, rows.getValue(3).approximateMinutes)
        assertNull(rows.getValue(3).checkTimeMinutes)
        assertNull(rows.getValue(1).approximateMinutes)
    }

    @Test fun twoTrackersOnOneFlightAreMarked() {
        val f = ymbt.flights[1]
        val twin = f.copy(index = 99, start = f.start!!.plusMinutes(1))
        val rows = OgnImport.rows(ymbt.copy(flights = ymbt.flights + twin), emptyList(), emptyList())
        assertEquals(setOf(f.index, 99), rows.filter { it.likelyDuplicate }.map { it.flight.index }.toSet())
    }

    @Test fun logNumbersFollowStartTime() {
        val picked = ymbt.flights
        val numbers = OgnImport.logNumbers(picked, 342)
        val byStart = picked.sortedBy { it.start }.map { numbers.getValue(it.index) }
        assertEquals(listOf(342, 343, 344, 345), byStart)
    }

    @Test fun aerotowNoteUsesTheTugHeightRounded() {
        val ls8 = ytem.flights[7] // tug's max_height 636 m
        assertEquals(listOf(msg(MsgKey.OGN_NOTE_AEROTOW, 640, 2100)), OgnImport.notes(ls8, ytem, recordLandingPlace = false))
        assertTrue("no tow link, no note", OgnImport.notes(ymbt.flights[0], ymbt, recordLandingPlace = false).isEmpty())
    }

    @Test fun landingElsewhereGoesInNotesOnlyWithThePlaceSettingOff() {
        val away = ymbt.flights[1].copy(stopCode = "YCOR")
        assertEquals(listOf(msg(MsgKey.OGN_NOTE_LANDED_AT, "YCOR")), OgnImport.notes(away, ymbt, recordLandingPlace = false))
        assertTrue(OgnImport.notes(away, ymbt, recordLandingPlace = true).isEmpty())
        assertNull("landing back home is not elsewhere", OgnImport.landedElsewhere(ymbt.flights[1].copy(stopCode = "ymbt"), ymbt))

        val on = OgnImport.draft(away, ymbt, input(recordLandingPlace = true, landingAirfieldId = "ycor"))
        assertEquals(LandingChoice.AIRFIELD, on.landingChoice)
        assertEquals("ycor", on.landingAirfieldId)
        val off = OgnImport.draft(away, ymbt, input(recordLandingPlace = false, landingAirfieldId = "ycor"))
        assertEquals(LandingChoice.SAME_AS_DEPARTURE, off.landingChoice)
        assertNull(off.landingAirfieldId)
    }

    @Test fun durationIsTheTimesDifferenceWhenTimesAreRecorded() {
        val f = ymbt.flights[0] // 12h26 to 16h49, 15740 s
        val withTimes = OgnImport.draft(f, ymbt, input(recordTimes = true))
        assertEquals("12:26", withTimes.launchTimeText)
        assertEquals("16:49", withTimes.landingTimeText)
        assertEquals("4:23", withTimes.durationText)

        val without = OgnImport.draft(f, ymbt, input(recordTimes = false))
        assertEquals("", without.launchTimeText)
        assertEquals("4:22", without.durationText)
        assertEquals("342", without.logNoText)
        assertEquals(ymbt.date, without.date)
        assertEquals(Role.PIC, without.role)
        assertEquals("Peter", without.picName)
    }

    @Test fun noLandingLeavesTheDurationForThePilot() {
        val open = ymbt.flights[0].copy(stop = null, durationSeconds = null)
        val d = OgnImport.draft(open, ymbt, input(recordTimes = true))
        assertEquals("", d.durationText)
        assertEquals("", d.landingTimeText)
        assertTrue(OgnImport.rows(ymbt.copy(flights = listOf(open)), emptyList(), emptyList()).single().noLanding)
    }

    @Test fun clockIsCutToTheMinute() {
        assertEquals("09:05", OgnImport.clock(LocalTime.of(9, 5, 59)).toString())
    }
}
