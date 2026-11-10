package io.github.jgfyyvzss.soarlog.core.ogn

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One day at one airfield as OGN Flightbook reports it (docs/OGN Flightbook import scoping.md). */
data class OgnDay(
    /** The code the request asked for, as Flightbook echoes it. */
    val code: String,
    /** The date at the airfield, in its own time zone. */
    val date: LocalDate,
    /** Null when Flightbook does not hold the airfield, as are the position and elevation. */
    val name: String?,
    val latitude: Double?,
    val longitude: Double?,
    val elevationM: Int?,
    val timeZone: String?,
    /** In the order Flightbook lists them; [OgnFlight.index] is the position here. */
    val flights: List<OgnFlight>,
)

/** One flight. Times are local at the airfield, cut down to the minute; the duration is exact. */
data class OgnFlight(
    val index: Int,
    val registration: String?,
    val competition: String?,
    /** The aircraft type as text, such as "ASK-21". */
    val aircraft: String?,
    /** 1 glider or motor glider, 2 tow plane, 3 to 6 other; null when not given. */
    val aircraftType: Int?,
    /** False for an aircraft Flightbook keeps anonymous: no database entry, or not identified there. */
    val identified: Boolean,
    val start: LocalTime?,
    val stop: LocalTime?,
    val durationSeconds: Int?,
    /** Minutes of uncertainty in [start] or [stop]: up to 60 the time is a median, above 60 it was not adjusted. */
    val startDelta: Int?,
    val stopDelta: Int?,
    /** Set when the flight took off, or landed, at another airfield Flightbook knows. */
    val startCode: String?,
    val stopCode: String?,
    /** True for a tow plane's own flight. */
    val towing: Boolean,
    /** For a towed glider: the [index] of its tow plane's flight. */
    val towFlight: Int?,
    /** For a towed glider: the tow plane's highest point above the airfield, close to the release height. */
    val towHeightM: Int?,
    val warn: Boolean,
    /** Today only, for a flight still in the air: its expected duration in seconds. */
    val durationExpectedSeconds: Int?,
) {
    val isGlider: Boolean get() = aircraftType == 1 && !towing
}

sealed interface OgnResult {
    data class Ok(val day: OgnDay) : OgnResult
    data class Failed(val message: Msg) : OgnResult
}

/** Every Flightbook request goes through this interface, so tests use a fake or a local mock server. */
interface Flightbook {
    /** One request for [code] on [date]; never retried here. */
    suspend fun day(code: String, date: LocalDate): OgnResult
}

/**
 * Reads Flightbook's JSON. Unknown keys are ignored and every optional one may be missing, since the real responses
 * already differ from API.md (`a_day`, a top-level `code`, a per-device `tracked`).
 */
object FlightbookParser {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val time = Regex("""^(\d{1,2})h(\d{2})$""")

    fun parse(text: String): OgnResult {
        val raw = runCatching { json.decodeFromString(RawDay.serializer(), text) }.getOrNull()
            ?: return OgnResult.Failed(msg(MsgKey.OGN_UNREADABLE))
        val date = runCatching { LocalDate.parse(raw.date) }.getOrNull()
            ?: return OgnResult.Failed(msg(MsgKey.OGN_UNREADABLE))
        val a = raw.airfield
        val flights = raw.flights.mapIndexed { i, f ->
            val d = raw.devices.getOrNull(f.device ?: -1)
            val address = d?.address.orEmpty()
            OgnFlight(
                index = i,
                registration = d?.registration?.trim()?.takeIf { it.isNotEmpty() },
                competition = d?.competition?.trim()?.takeIf { it.isNotEmpty() },
                aircraft = d?.aircraft?.trim()?.takeIf { it.isNotEmpty() },
                aircraftType = d?.aircraftType,
                identified = d != null && !address.startsWith("~") && !address.startsWith("_"),
                start = localTime(f.start),
                stop = localTime(f.stop),
                durationSeconds = f.duration,
                startDelta = f.startDelta,
                stopDelta = f.stopDelta,
                startCode = f.startCode?.trim()?.takeIf { it.isNotEmpty() },
                stopCode = f.stopCode?.trim()?.takeIf { it.isNotEmpty() },
                towing = f.towing,
                towFlight = null,
                towHeightM = null,
                warn = f.warn,
                durationExpectedSeconds = f.durationExpected,
            )
        }
        // A tow link may point to a flight later in the list, so links are resolved once every flight is read.
        val linked = flights.mapIndexed { i, f ->
            val tow = raw.flights[i].tow?.takeIf { it in flights.indices && it != i } ?: return@mapIndexed f
            f.copy(towFlight = tow, towHeightM = raw.flights[tow].maxHeight)
        }
        return OgnResult.Ok(
            OgnDay(
                code = (a?.code ?: raw.code).orEmpty(),
                date = date,
                name = a?.name,
                latitude = a?.latlng?.getOrNull(0),
                longitude = a?.latlng?.getOrNull(1),
                elevationM = a?.elevation,
                timeZone = a?.timeInfo?.tzName,
                flights = linked,
            ),
        )
    }

    /** `HH'h'mm`, such as `12h26`; null when missing or unreadable. */
    fun localTime(text: String?): LocalTime? {
        val m = time.matchEntire(text?.trim() ?: return null) ?: return null
        val (h, min) = m.destructured
        return runCatching { LocalTime.of(h.toInt(), min.toInt()) }.getOrNull()
    }

    @Serializable
    private class RawDay(
        val code: String? = null,
        val date: String = "",
        val airfield: RawAirfield? = null,
        val devices: List<RawDevice> = emptyList(),
        val flights: List<RawFlight> = emptyList(),
    )

    @Serializable
    private class RawAirfield(
        val code: String? = null,
        val name: String? = null,
        val elevation: Int? = null,
        val latlng: List<Double>? = null,
        @SerialName("time_info") val timeInfo: RawTimeInfo? = null,
    )

    @Serializable
    private class RawTimeInfo(@SerialName("tz_name") val tzName: String? = null)

    @Serializable
    private class RawDevice(
        val address: String? = null,
        val aircraft: String? = null,
        @SerialName("aircraft_type") val aircraftType: Int? = null,
        val competition: String? = null,
        val registration: String? = null,
    )

    @Serializable
    private class RawFlight(
        val device: Int? = null,
        val duration: Int? = null,
        @SerialName("max_height") val maxHeight: Int? = null,
        val start: String? = null,
        val stop: String? = null,
        @SerialName("start_delta") val startDelta: Int? = null,
        @SerialName("stop_delta") val stopDelta: Int? = null,
        @SerialName("start_code") val startCode: String? = null,
        @SerialName("stop_code") val stopCode: String? = null,
        val tow: Int? = null,
        val towing: Boolean = false,
        val warn: Boolean = false,
        @SerialName("duration_expected") val durationExpected: Int? = null,
    )
}
