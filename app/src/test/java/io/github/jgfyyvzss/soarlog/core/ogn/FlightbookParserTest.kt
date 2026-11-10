package io.github.jgfyyvzss.soarlog.core.ogn

import io.github.jgfyyvzss.soarlog.core.MsgKey
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Flightbook parser against the real responses saved in testdata (docs/OGN Flightbook import scoping.md). */
class FlightbookParserTest {
    private fun fixture(name: String): String =
        listOf(File("../testdata"), File("testdata")).first { it.isDirectory }.resolve(name).readText()

    private fun day(name: String) = (FlightbookParser.parse(fixture(name)) as OgnResult.Ok).day

    @Test fun ymbtGivesTheAirfieldAndFourGliderFlights() {
        val d = day("Flightbook_YMBT_2026-11-09.json")
        assertEquals("YMBT", d.code)
        assertEquals(LocalDate.of(2026, 11, 9), d.date)
        assertEquals("Mount Beauty", d.name)
        assertEquals(-36.72895, d.latitude!!, 1e-9)
        assertEquals(147.1671, d.longitude!!, 1e-9)
        assertEquals(334, d.elevationM)
        assertEquals("Australia/Sydney", d.timeZone)
        assertEquals(listOf("GVS", "VH-GJF", "VH-GVA", "VH-GMK"), d.flights.map { it.registration })
        assertTrue(d.flights.all { it.isGlider && it.identified && it.towFlight == null })

        val first = d.flights[0]
        assertEquals(LocalTime.of(12, 26), first.start)
        assertEquals(LocalTime.of(16, 49), first.stop)
        assertEquals(15740, first.durationSeconds)
        assertEquals("not adjusted: over 60", 654, first.startDelta)
        assertNull(first.stopDelta)
        assertEquals("ASK-21 MI", first.aircraft)
        assertEquals(1, d.flights[3].startDelta)
        assertTrue(d.flights.none { it.warn })
    }

    @Test fun ytemLinksEachGliderToItsTow() {
        val d = day("Flightbook_YTEM_2026-11-08.json")
        assertEquals("TEMORA", d.name)
        assertEquals(277, d.elevationM)
        assertEquals(22, d.flights.size)
        val (tows, gliders) = d.flights.partition { it.towing }
        assertEquals(11, tows.size)
        assertEquals(11, gliders.size)
        assertTrue(gliders.all { it.isGlider && it.towFlight != null })
        assertTrue("a tug flight is never a glider flight", tows.none { it.isGlider })

        val ls8 = d.flights[7]
        assertEquals("VH-LSL", ls8.registration)
        assertEquals(6, ls8.towFlight)
        assertEquals("the tug's height, not the glider's own 2392 m", 636, ls8.towHeightM)

        val last = d.flights[20]
        assertEquals("a link to a flight later in the list", 21, last.towFlight)
        assertEquals(689, last.towHeightM)
        assertEquals("VHUKB", d.flights[9].registration)
        assertEquals("warn is set on the local Janus B flights", listOf(0, 2, 13, 19, 20), d.flights.filter { it.warn }.map { it.index })
    }

    @Test fun anUnreadableReplyIsAFailure() {
        listOf("not json", "{}", """{"date":"yesterday"}""").forEach {
            assertEquals(it, MsgKey.OGN_UNREADABLE, (FlightbookParser.parse(it) as OgnResult.Failed).message.key)
        }
    }

    @Test fun missingOptionalFieldsAreTolerated() {
        val d = (FlightbookParser.parse("""{"date":"2026-11-09","airfield":{"code":"XXXX","name":null},"devices":[],"flights":[]}""") as OgnResult.Ok).day
        assertEquals("XXXX", d.code)
        assertNull(d.name)
        assertNull(d.latitude)
        assertTrue(d.flights.isEmpty())
    }

    @Test fun timesAreReadAsHoursAndMinutes() {
        assertEquals(LocalTime.of(9, 5), FlightbookParser.localTime("09h05"))
        assertEquals(LocalTime.of(9, 5), FlightbookParser.localTime("9h05"))
        assertNull(FlightbookParser.localTime(null))
        assertNull(FlightbookParser.localTime("25h00"))
        assertNull(FlightbookParser.localTime("12:30"))
        assertFalse(FlightbookParser.localTime("12h30") == null)
    }
}
