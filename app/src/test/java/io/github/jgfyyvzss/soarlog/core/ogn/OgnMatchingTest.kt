package io.github.jgfyyvzss.soarlog.core.ogn

import io.github.jgfyyvzss.soarlog.core.igc.MatchPlace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OgnMatchingTest {
    @Test fun registrationsMatchIgnoringCaseHyphensAndThePrefix() {
        // The three formats seen in the YMBT and YTEM fixtures.
        assertTrue(OgnMatching.sameRegistration("VH-GJF", "vh-gjf"))
        assertTrue(OgnMatching.sameRegistration("VHUKB", "VH-UKB"))
        assertTrue("GVS finds VH-GVS", OgnMatching.sameRegistration("GVS", "VH-GVS"))
        assertTrue("VH-GVS finds GVS", OgnMatching.sameRegistration("VH-GVS", "GVS"))
        assertTrue(OgnMatching.sameRegistration(" VH GVS ", "VH-GVS"))
    }

    @Test fun differentOrMissingRegistrationsDoNotMatch() {
        assertFalse(OgnMatching.sameRegistration("VH-GVS", "VH-GVA"))
        assertFalse("only one side is cut at the hyphen", OgnMatching.sameRegistration("VH-GVS", "D-GVS"))
        assertFalse(OgnMatching.sameRegistration(null, "VH-GVS"))
        assertFalse(OgnMatching.sameRegistration("", ""))
        assertFalse("nothing after the hyphen", OgnMatching.sameRegistration("VH-", "GVS"))
    }

    @Test fun airfieldsWithinTheirRadiusAreFoundNearestFirst() {
        // Mount Beauty as Flightbook places it (fixture 1).
        val lat = -36.72895
        val lon = 147.1671
        val places = listOf(
            MatchPlace("far", -36.80, 147.1671, null), // about 8 km south
            MatchPlace("wide", -36.7350, 147.1671, 2000), // about 670 m, inside its own 2 km
            MatchPlace("near", -36.7295, 147.1671, null), // about 60 m, inside the default 1 km
        )
        assertEquals(listOf("near", "wide"), OgnMatching.airfieldsNear(lat, lon, places).map { it.id })
        assertTrue(OgnMatching.airfieldsNear(lat, lon, places.take(1)).isEmpty())
    }
}
