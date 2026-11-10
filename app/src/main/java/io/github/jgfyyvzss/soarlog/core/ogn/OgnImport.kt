package io.github.jgfyyvzss.soarlog.core.ogn

import io.github.jgfyyvzss.soarlog.core.ClockTime
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.FlightDraft
import io.github.jgfyyvzss.soarlog.core.LandingChoice
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.msg
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

/** A glider from the pilot's table, as matching needs it. */
data class OgnGlider(val id: String, val registration: String)

/** A flight already in the logbook on the searched date, for the Already in logbook badge. */
data class LoggedFlight(val gliderId: String, val launchTime: ClockTime?, val durationMinutes: Int)

enum class GliderMatch {
    /** Exactly one of the pilot's gliders has this registration. */
    MATCHED,
    /** Several do; the pilot picks. */
    AMBIGUOUS,
    /** None does: Glider not in your list. */
    NOT_IN_LIST,
    /** No registration, or an anonymous address: Unidentified aircraft. */
    UNIDENTIFIED,
}

/** One results row: a flight with what the logbook knows about it. */
data class OgnRow(
    val flight: OgnFlight,
    val gliderId: String?,
    val gliderMatch: GliderMatch,
    val alreadyLogged: Boolean,
    /** Another row for the same registration starts within [OgnImport.DUPLICATE_START_MINUTES]: two trackers. */
    val likelyDuplicate: Boolean,
) {
    private val deltas get() = listOfNotNull(flight.startDelta, flight.stopDelta).filter { it > 0 }

    /** The largest uncertainty when it is at most an hour, so the time is a median: Time approximate. */
    val approximateMinutes: Int? get() = deltas.filter { it <= OgnImport.APPROXIMATE_LIMIT_MINUTES }.maxOrNull()
        ?.takeIf { checkTimeMinutes == null }

    /** The largest uncertainty when it is over an hour, so the time was not adjusted: Check time. */
    val checkTimeMinutes: Int? get() = deltas.filter { it > OgnImport.APPROXIMATE_LIMIT_MINUTES }.maxOrNull()

    val noLanding: Boolean get() = flight.stop == null
}

/** What the caller resolved from the database for one flight's draft. */
data class DraftInput(
    val logNo: Int,
    val gliderId: String?,
    /** The searched airfield, or the `start_code` airfield; null when that code is not in the table. */
    val airfieldId: String?,
    val clubId: String?,
    /** Aero when the flight has a tow link, else the airfield's default, else null. */
    val launchTypeId: String?,
    /** The `stop_code` airfield, when there is one and it is in the table. */
    val landingAirfieldId: String?,
    val recordTimes: Boolean,
    val recordLandingPlace: Boolean,
    val profileName: String,
    /** The notes, already in the pilot's language, from [OgnImport.notes]. */
    val notes: String,
)

/** Turning a Flightbook day into results rows and flight-form drafts (docs/OGN Flightbook import scoping.md). */
object OgnImport {
    const val ALREADY_LOGGED_LAUNCH_MINUTES = 5
    const val ALREADY_LOGGED_DURATION_MINUTES = 2
    const val DUPLICATE_START_MINUTES = 2
    const val APPROXIMATE_LIMIT_MINUTES = 60
    private const val FEET_PER_METRE = 3.28084

    fun clock(t: LocalTime): ClockTime = ClockTime(t.hour * 60 + t.minute)

    /** Flightbook's exact seconds, rounded to the nearest minute and at least 1. */
    fun durationMinutes(f: OgnFlight): Int? = f.durationSeconds?.takeIf { it >= 0 }?.let { maxOf(1, (it + 30) / 60) }

    fun matchGlider(f: OgnFlight, gliders: List<OgnGlider>): Pair<String?, GliderMatch> {
        val registration = f.registration
        if (!f.identified || registration == null) return null to GliderMatch.UNIDENTIFIED
        val hits = gliders.filter { OgnMatching.sameRegistration(it.registration, registration) }
        return when (hits.size) {
            1 -> hits.single().id to GliderMatch.MATCHED
            0 -> null to GliderMatch.NOT_IN_LIST
            else -> null to GliderMatch.AMBIGUOUS
        }
    }

    /**
     * Same glider and date, and a launch time within 5 minutes, or, when either side has no launch time, a duration
     * within 2 minutes.
     */
    fun alreadyLogged(f: OgnFlight, gliderId: String?, logged: List<LoggedFlight>): Boolean {
        if (gliderId == null) return false
        val start = f.start?.let(::clock)
        val minutes = durationMinutes(f)
        return logged.any { l ->
            l.gliderId == gliderId && if (start != null && l.launchTime != null) {
                abs(l.launchTime.minuteOfDay - start.minuteOfDay) <= ALREADY_LOGGED_LAUNCH_MINUTES
            } else {
                minutes != null && abs(l.durationMinutes - minutes) <= ALREADY_LOGGED_DURATION_MINUTES
            }
        }
    }

    /** Every flight of the day as a row, most recent start at the top as on the Flights page; no start goes last. */
    fun rows(day: OgnDay, gliders: List<OgnGlider>, logged: List<LoggedFlight>): List<OgnRow> {
        val rows = day.flights.map { f ->
            val (gliderId, match) = matchGlider(f, gliders)
            val twin = day.flights.any { o ->
                o.index != f.index && f.registration != null && o.registration != null &&
                    OgnMatching.sameRegistration(o.registration, f.registration) &&
                    f.start != null && o.start != null &&
                    abs(clock(f.start).minuteOfDay - clock(o.start).minuteOfDay) <= DUPLICATE_START_MINUTES
            }
            OgnRow(f, gliderId, match, alreadyLogged(f, gliderId, logged), twin)
        }
        return rows.sortedWith(compareBy<OgnRow> { it.flight.start == null }.thenByDescending { it.flight.start }.thenBy { it.flight.index })
    }

    /** Glider flights only unless [showAll], narrowed to the search's glider when one was given. */
    fun visible(rows: List<OgnRow>, showAll: Boolean, glider: String): List<OgnRow> = rows.filter {
        (showAll || it.flight.isGlider) && (glider.isBlank() || OgnMatching.sameRegistration(it.flight.registration, glider))
    }

    /** Log numbers for the ticked flights, from [first] in start-time order. */
    fun logNumbers(flights: List<OgnFlight>, first: Int): Map<Int, Int> =
        flights.sortedWith(compareBy<OgnFlight> { it.start == null }.thenBy { it.start }.thenBy { it.index })
            .mapIndexed { i, f -> f.index to first + i }.toMap()

    /** The airfield code a flight left from: its `start_code`, or the searched airfield. */
    fun launchCode(f: OgnFlight, day: OgnDay): String = f.startCode ?: day.code

    /** The `stop_code` when the flight landed at another airfield than it left from. */
    fun landedElsewhere(f: OgnFlight, day: OgnDay): String? =
        f.stopCode?.takeIf { !it.equals(launchCode(f, day), ignoreCase = true) }

    /**
     * The notes OGN gives a flight: the tug's height above the airfield for a linked aerotow, metres rounded to 10 and
     * feet to 50, and where it landed when the landing-place setting is off. Joined with "; " by the caller.
     */
    fun notes(f: OgnFlight, day: OgnDay, recordLandingPlace: Boolean): List<Msg> = buildList {
        f.towHeightM?.let { m -> add(msg(MsgKey.OGN_NOTE_AEROTOW, roundTo(m.toDouble(), 10), roundTo(m * FEET_PER_METRE, 50))) }
        if (!recordLandingPlace) landedElsewhere(f, day)?.let { add(msg(MsgKey.OGN_NOTE_LANDED_AT, it)) }
    }

    private fun roundTo(value: Double, step: Int): Int = (value / step).roundToInt() * step

    /**
     * The flight form's draft. Times only with the times setting on; then the duration is landing minus launch as
     * stored, which avoids the form's warning that they disagree. Otherwise Flightbook's duration in minutes.
     */
    fun draft(f: OgnFlight, day: OgnDay, input: DraftInput): FlightDraft {
        val start = f.start?.let(::clock)
        val stop = f.stop?.let(::clock)
        val fromTimes = if (input.recordTimes && start != null && stop != null) ClockTime.minutesBetween(start, stop) else null
        val minutes = fromTimes ?: durationMinutes(f)
        val landedAt = landedElsewhere(f, day)
        return FlightDraft(
            logNoText = input.logNo.toString(),
            date = day.date,
            durationText = minutes?.takeIf { it >= 0 }?.let(Durations::formatHm).orEmpty(),
            gliderId = input.gliderId,
            airfieldId = input.airfieldId,
            clubId = input.clubId,
            launchTypeId = input.launchTypeId,
            role = Role.PIC,
            picName = input.profileName.trim(),
            launchTimeText = if (input.recordTimes) start?.toString().orEmpty() else "",
            landingTimeText = if (input.recordTimes) stop?.toString().orEmpty() else "",
            landingChoice = if (input.recordLandingPlace && landedAt != null) LandingChoice.AIRFIELD else LandingChoice.SAME_AS_DEPARTURE,
            landingAirfieldId = if (input.recordLandingPlace && landedAt != null) input.landingAirfieldId else null,
            notes = input.notes,
        )
    }
}
