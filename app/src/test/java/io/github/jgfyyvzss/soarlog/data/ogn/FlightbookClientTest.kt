package io.github.jgfyyvzss.soarlog.data.ogn

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.ogn.OgnResult
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The Flightbook client against a local mock server. No test calls the real Flightbook. */
class FlightbookClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After fun tearDown() = server.shutdown()

    private fun api() = OkHttpFlightbook(OkHttpFlightbook.userAgent("1.2.3"), baseUrl = server.url("/").toString())
    private val date = LocalDate.of(2026, 11, 9)

    @Test fun asksForOneDayAndSendsOnlyTheCodeAndDate() = runTest {
        val body = listOf(File("../testdata"), File("testdata")).first { it.isDirectory }.resolve("Flightbook_YMBT_2026-11-09.json").readText()
        server.enqueue(MockResponse().setResponseCode(200).setBody(body))
        val r = api().day(" YMBT ", date)
        assertEquals(4, (r as OgnResult.Ok).day.flights.size)
        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api/logbook/YMBT/2026-11-09", request.path)
        assertEquals("SoarLog/1.2.3", request.getHeader("User-Agent"))
        assertEquals(0L, request.bodySize)
        assertEquals("one request", 1, server.requestCount)
    }

    @Test fun anUnofficialCodeCannotChangeThePath() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"date":"2026-11-09","flights":[]}"""))
        api().day("A/B?x", date)
        assertEquals("/api/logbook/A%2FB%3Fx/2026-11-09", server.takeRequest().path)
    }

    @Test fun refusalsAndErrorsAreFailures() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        assertEquals(MsgKey.OGN_REFUSED, (api().day("YMBT", date) as OgnResult.Failed).message.key)
        server.enqueue(MockResponse().setResponseCode(403))
        assertEquals(MsgKey.OGN_REFUSED, (api().day("YMBT", date) as OgnResult.Failed).message.key)
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(MsgKey.OGN_STATUS, (api().day("YMBT", date) as OgnResult.Failed).message.key)
        server.enqueue(MockResponse().setResponseCode(200).setBody("<html>"))
        assertEquals(MsgKey.OGN_UNREADABLE, (api().day("YMBT", date) as OgnResult.Failed).message.key)
        assertEquals("never retried", 4, server.requestCount)
    }

    @Test fun anUnreachableServerIsAFailure() = runTest {
        server.shutdown()
        assertTrue(api().day("YMBT", date) is OgnResult.Failed)
    }
}
