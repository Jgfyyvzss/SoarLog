package io.github.jgfyyvzss.soarlog.data.ogn

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.ogn.Flightbook
import io.github.jgfyyvzss.soarlog.core.ogn.OgnDay
import io.github.jgfyyvzss.soarlog.core.ogn.OgnResult
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The session keeps each fetched day, so Back and a repeated search never ask Flightbook again. */
class OgnSessionTest {
    private class FakeFlightbook(var fail: Boolean = false) : Flightbook {
        val asked = mutableListOf<Pair<String, LocalDate>>()
        override suspend fun day(code: String, date: LocalDate): OgnResult {
            asked += code to date
            return if (fail) OgnResult.Failed(msg(MsgKey.OGN_STATUS, 500))
            else OgnResult.Ok(OgnDay(code, date, null, null, null, null, null, emptyList()))
        }
    }

    private val day = LocalDate.of(2026, 11, 9)

    @Test fun aFetchedDayIsAskedForOnce() = runTest {
        val fake = FakeFlightbook()
        val session = OgnSession(fake)
        assertTrue(session.fetch("ymbt", day) is OgnResult.Ok)
        assertTrue(session.fetch(" YMBT ", day) is OgnResult.Ok)
        assertEquals("the code is sent trimmed and in capitals, once", listOf("YMBT" to day), fake.asked)
        assertNotNull(session.cached("YMBT", day))
        assertNull(session.cached("YMBT", day.minusDays(1)))
        session.fetch("YMBT", day.minusDays(1))
        assertEquals(2, fake.asked.size)
    }

    @Test fun aFailureIsNotKeptSoTryAgainAsksAgain() = runTest {
        val fake = FakeFlightbook(fail = true)
        val session = OgnSession(fake)
        assertTrue(session.fetch("YMBT", day) is OgnResult.Failed)
        assertNull(session.cached("YMBT", day))
        fake.fail = false
        assertTrue(session.fetch("YMBT", day) is OgnResult.Ok)
        assertEquals(2, fake.asked.size)
    }

    @Test fun aBlankCodeIsNeverSent() = runTest {
        val fake = FakeFlightbook()
        val r = OgnSession(fake).fetch("  ", day)
        assertEquals(MsgKey.AIRFIELD_CODE_REQUIRED, (r as OgnResult.Failed).message.key)
        assertTrue(fake.asked.isEmpty())
    }
}
