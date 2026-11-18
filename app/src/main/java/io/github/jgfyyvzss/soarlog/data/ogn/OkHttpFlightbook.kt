package io.github.jgfyyvzss.soarlog.data.ogn

import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.ogn.Flightbook
import io.github.jgfyyvzss.soarlog.core.ogn.FlightbookParser
import io.github.jgfyyvzss.soarlog.core.ogn.OgnResult
import java.io.IOException
import java.net.UnknownHostException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OGN Flightbook over HTTPS: `GET /api/logbook/<code>/<yyyy-MM-dd>`, with no key. The request carries only the code
 * and the date, and the User-Agent names the app. One request per search and no retries, since Flightbook may block
 * a client that asks too often.
 */
class OkHttpFlightbook(
    private val userAgent: String,
    private val baseUrl: String = BASE_URL,
    private val client: OkHttpClient = defaultClient(),
) : Flightbook {
    override suspend fun day(code: String, date: LocalDate): OgnResult = withContext(Dispatchers.IO) {
        // Path segments are encoded here, so an unofficial code can never change the path.
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/logbook")
            .addPathSegment(code.trim())
            .addPathSegment(date.toString())
            .build()
        try {
            client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).get().build()).execute().use { response ->
                when (response.code) {
                    200 -> FlightbookParser.parse(response.body.string())
                    403, 429 -> OgnResult.Failed(msg(MsgKey.OGN_REFUSED, response.code))
                    else -> OgnResult.Failed(msg(MsgKey.OGN_STATUS, response.code))
                }
            }
        } catch (e: UnknownHostException) {
            // Also what Android reports when the app's data use is switched off in its app settings.
            OgnResult.Failed(msg(MsgKey.OGN_NO_HOST))
        } catch (e: IOException) {
            OgnResult.Failed(msg(MsgKey.OGN_UNREACHABLE, e.message ?: e.javaClass.simpleName))
        }
    }

    companion object {
        /** Not named in API.md; the host a third-party client uses, confirmed by real calls on 2026-11-10. */
        const val BASE_URL = "https://flightbook.glidernet.org/"

        /** One request, one 15 second limit (docs/OGN Flightbook import scoping.md). */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()

        /** For example `SoarLog/0.3.0` (decision 4). */
        fun userAgent(appVersion: String) = "SoarLog/$appVersion"
    }
}
