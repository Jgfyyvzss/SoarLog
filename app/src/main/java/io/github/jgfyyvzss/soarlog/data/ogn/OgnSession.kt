package io.github.jgfyyvzss.soarlog.data.ogn

import io.github.jgfyyvzss.soarlog.core.FlightDraft
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.ogn.Flightbook
import io.github.jgfyyvzss.soarlog.core.ogn.OgnDay
import io.github.jgfyyvzss.soarlog.core.ogn.OgnResult
import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What Flightbook knows about an airfield, to prefill the new-airfield form. */
data class AirfieldPrefill(val code: String, val name: String?, val latitude: Double?, val longitude: Double?, val elevationM: Int?)

/** A glider to create at Create, for a registration the pilot's list does not have. */
data class GliderStub(val registration: String, val type: String)

/** One ticked flight under review: the draft exactly as it will be stored. */
data class ReviewRow(
    /** The flight's index in the day, [io.github.jgfyyvzss.soarlog.core.ogn.OgnFlight.index]. */
    val flightIndex: Int,
    val draft: FlightDraft,
    /** Set when OGN was over an hour unsure of a time; a problem until the pilot opens and saves the row. */
    val checkTimeMinutes: Int?,
    val timeConfirmed: Boolean = false,
    /** Set while the draft's glider is [stubId] for this registration. */
    val stub: GliderStub? = null,
) {
    val needsTimeCheck: Boolean get() = checkTimeMinutes != null && !timeConfirmed

    companion object {
        /** The glider ID a draft holds until Create makes the stub glider. */
        fun stubId(registration: String) = "ogn-new-glider:" + Keys.registrationKey(registration)
    }
}

/**
 * The OGN import for this app session: the last search, every day already fetched, the ticked flights and the
 * review, so going back never asks Flightbook again. A failed request is not kept, so Try again makes a new one.
 * Nothing is stored on disk.
 */
class OgnSession(private val flightbook: Flightbook) {
    var airfieldId: String? = null
    /** Null until the search screen first opens, which sets today. */
    var date: LocalDate? = null
    /** A registration typed or picked; it filters the fetched flights and never changes the request. */
    var glider: String = ""
    /** Set before the new-airfield form opens from the search. */
    var newAirfield: AirfieldPrefill? = null
    /** Tow planes and other aircraft shown on the results. */
    var showAll: Boolean = false

    private val days = mutableMapOf<Pair<String, LocalDate>, OgnDay>()

    /** The day the results screen shows. Changing it clears the ticks. */
    var day: OgnDay? = null
        set(value) {
            if (value?.code != field?.code || value?.date != field?.date) _ticked.value = emptySet()
            field = value
        }

    private val _ticked = MutableStateFlow<Set<Int>>(emptySet())
    /** Ticked flights by [io.github.jgfyyvzss.soarlog.core.ogn.OgnFlight.index]. Nothing starts ticked. */
    val ticked: StateFlow<Set<Int>> = _ticked.asStateFlow()

    fun setTicked(indexes: Set<Int>) { _ticked.value = indexes }

    private val _review = MutableStateFlow<List<ReviewRow>>(emptyList())
    val review: StateFlow<List<ReviewRow>> = _review.asStateFlow()

    fun startReview(rows: List<ReviewRow>) { _review.value = rows }

    fun reviewRow(flightIndex: Int): ReviewRow? = _review.value.firstOrNull { it.flightIndex == flightIndex }

    fun updateReview(change: (List<ReviewRow>) -> List<ReviewRow>) = _review.update(change)

    /** The flight form saved a row: its draft replaces the row's, and any Check time is confirmed. */
    fun storeReviewed(flightIndex: Int, draft: FlightDraft) = _review.update { rows ->
        rows.map { r ->
            if (r.flightIndex != flightIndex) r
            else r.copy(draft = draft, timeConfirmed = true, stub = r.stub?.takeIf { draft.gliderId == ReviewRow.stubId(it.registration) })
        }
    }

    /** After Create: the ticks and the review end; fetched days stay for the session. */
    fun finish() {
        _ticked.value = emptySet()
        _review.value = emptyList()
    }

    fun cached(code: String, date: LocalDate): OgnDay? = Keys.airfieldCode(code)?.let { days[it to date] }

    /** The cached day, or one request to Flightbook. */
    suspend fun fetch(code: String, date: LocalDate): OgnResult {
        val key = Keys.airfieldCode(code) ?: return OgnResult.Failed(msg(MsgKey.AIRFIELD_CODE_REQUIRED))
        days[key to date]?.let { return OgnResult.Ok(it) }
        return flightbook.day(key, date).also { if (it is OgnResult.Ok) days[key to date] = it.day }
    }
}
