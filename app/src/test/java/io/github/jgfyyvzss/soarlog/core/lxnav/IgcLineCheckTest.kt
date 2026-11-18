package io.github.jgfyyvzss.soarlog.core.lxnav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IgcLineCheckTest {
    // A B record laid out as in Appendix D: time, latitude, longitude, validity, pressure and GNSS altitude.
    private val b = "B0125443644081S14710277EA0035500355"

    private fun checked(vararg lines: String) = IgcLineCheck().also { c -> lines.forEach(c::accept) }

    @Test fun aPlainBRecordHas35Characters() {
        assertEquals(35, b.length)
        assertNull(checked().problem(b))
        assertNotNull("cut short", checked().problem(b.dropLast(3)))
        assertNotNull("too long without an I record", checked().problem(b + "123"))
    }

    @Test fun theIRecordSetsTheBLength() {
        // Two extensions: FXA in bytes 36 to 38 and SIU in 39 to 40, so a B record has 40 characters.
        val i = "I023638FXA3940SIU"
        assertEquals(40, IgcLineCheck.extensionEnd(i))
        assertNull(checked(i).problem(b + "12305"))
        assertNotNull(checked(i).problem(b))
        assertNotNull("count and list disagree", checked().problem("I033638FXA3940SIU"))
    }

    @Test fun theJRecordSetsTheKLength() {
        val j = "J010812HDT"
        assertEquals(12, IgcLineCheck.extensionEnd(j))
        assertNull(checked(j).problem("K01254400090"))
        assertNotNull(checked(j).problem("K012544000"))
        assertNull("no J record yet: any length", checked().problem("K012544000"))
    }

    @Test fun bFieldsMustHaveTheirLayout() {
        assertNotNull("time", checked().problem("B2560443644081S14710277EA0035500355"))
        assertNotNull("hemisphere", checked().problem("B0125443644081X14710277EA0035500355"))
        assertNotNull("validity", checked().problem("B0125443644081S14710277EX0035500355"))
        assertNull("below sea level", checked().problem("B0125443644081S14710277EA-0010-0010"))
    }

    @Test fun bTimesRunForwardExceptPastMidnight() {
        assertNull(checked(b).problem("B0125453644081S14710277EA0035500355"))
        assertNull("the same second again", checked(b).problem(b))
        assertNotNull(checked(b).problem("B0125403644081S14710277EA0035500355"))
        assertNull(checked("B2359593644081S14710277EA0035500355").problem("B0000013644081S14710277EA0035500355"))
    }

    @Test fun otherRecordsNeedOnlyACapitalTypeAndNoControlCharacters() {
        assertNull(checked().problem("HFPLTPILOTINCHARGE:Pat Pilot"))
        assertNull(checked().problem("LLXVFLIGHT:1"))
        assertNotNull(checked().problem("hFPLTPILOTINCHARGE:Pat"))
        assertNotNull(checked().problem("HFPLT\u0001"))
        assertNotNull(checked().problem(""))
    }
}
