package io.github.jgfyyvzss.soarlog.ui.ogn

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.ClockTime
import io.github.jgfyyvzss.soarlog.core.ogn.DraftInput
import io.github.jgfyyvzss.soarlog.core.ogn.GliderMatch
import io.github.jgfyyvzss.soarlog.core.ogn.LoggedFlight
import io.github.jgfyyvzss.soarlog.core.ogn.OgnDay
import io.github.jgfyyvzss.soarlog.core.ogn.OgnGlider
import io.github.jgfyyvzss.soarlog.core.ogn.OgnImport
import io.github.jgfyyvzss.soarlog.core.ogn.OgnRow
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import io.github.jgfyyvzss.soarlog.data.ogn.GliderStub
import io.github.jgfyyvzss.soarlog.data.ogn.OgnSession
import io.github.jgfyyvzss.soarlog.data.ogn.ReviewRow
import io.github.jgfyyvzss.soarlog.ui.Badge
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The fetched day's flights with tick boxes. Nothing starts ticked; tow planes are hidden until the switch is on. */
class OgnResultsViewModel(
    private val context: Context,
    private val repo: LogbookRepository,
    private val settings: SettingsRepository,
    private val session: OgnSession,
) : ViewModel() {
    val day: OgnDay? = session.day
    val ticked = session.ticked
    val glider = session.glider

    var rows by mutableStateOf<List<OgnRow>>(emptyList())
        private set
    var loaded by mutableStateOf(false)
        private set
    var showAll by mutableStateOf(session.showAll)
        private set
    var openReview by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val d = day ?: return@launch
            val gliders = repo.observeGliders().first().map { OgnGlider(it.id, it.registration) }
            val logged = repo.flightsOnDate(d.date).map {
                LoggedFlight(it.gliderId, it.launchTime?.let { t -> ClockTime.parse(t) }, it.durationMinutes)
            }
            rows = OgnImport.rows(d, gliders, logged)
            loaded = true
        }
    }

    val visible: List<OgnRow> get() = OgnImport.visible(rows, showAll, glider)

    fun changeShowAll(on: Boolean) {
        showAll = on
        session.showAll = on
    }

    fun toggle(index: Int) {
        val t = ticked.value
        session.setTicked(if (index in t) t - index else t + index)
    }

    fun selectAll() = session.setTicked(ticked.value + visible.map { it.flight.index })
    fun selectNone() = session.setTicked(emptySet())

    /**
     * Builds the review: each ticked flight as the draft the flight form will show, numbered from the next free log
     * number in start-time order, with defaults from the airfield and the profile.
     */
    fun review() = viewModelScope.launch {
        val d = day ?: return@launch
        val picked = rows.filter { it.flight.index in ticked.value }
        if (picked.isEmpty()) return@launch
        val s = settings.settings.first()
        val numbers = OgnImport.logNumbers(picked.map { it.flight }, repo.nextLogNo())
        val aeroId = Keys.seededLaunchTypeId("Aero")
        val aero = repo.observeLaunchTypes().first().firstOrNull { it.id == aeroId && !it.archived }?.id
        val reviewRows = picked.sortedBy { numbers[it.flight.index] }.map { row ->
            val f = row.flight
            val airfield = f.startCode?.let { repo.airfieldByCode(it) } ?: session.airfieldId?.takeIf { f.startCode == null }?.let { repo.airfield(it) }
            val clubs = airfield?.let { repo.clubsFor(it.id) }.orEmpty()
            val clubId = clubs.singleOrNull()?.id ?: airfield?.defaultClubId?.takeIf { c -> clubs.any { it.id == c } }
            val launchType = if (f.towFlight != null) aero ?: airfield?.defaultLaunchTypeId else airfield?.defaultLaunchTypeId
            val landingAirfieldId = OgnImport.landedElsewhere(f, d)?.let { repo.airfieldByCode(it)?.id }
            val stub = f.registration?.takeIf { row.gliderMatch == GliderMatch.NOT_IN_LIST }?.let { GliderStub(it, f.aircraft.orEmpty()) }
            val notes = OgnImport.notes(f, d, s.recordLandingPlace).joinToString("; ") { it.text(context) }
            val draft = OgnImport.draft(
                f, d,
                DraftInput(
                    logNo = numbers.getValue(f.index),
                    gliderId = row.gliderId ?: stub?.let { ReviewRow.stubId(it.registration) },
                    airfieldId = airfield?.id,
                    clubId = clubId,
                    launchTypeId = launchType,
                    landingAirfieldId = landingAirfieldId,
                    recordTimes = s.recordTimes,
                    recordLandingPlace = s.recordLandingPlace,
                    profileName = s.pilotName,
                    notes = notes,
                ),
            )
            ReviewRow(f.index, draft, row.checkTimeMinutes, stub = stub)
        }
        session.startReview(reviewRows)
        openReview = true
    }

    fun reviewOpened() {
        openReview = false
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OgnResultsScreen(onReview: () -> Unit) {
    val vm: OgnResultsViewModel = viewModel(factory = vmFactory { OgnResultsViewModel(it, it.repository, it.settings, it.ognSession) })
    val ticked by vm.ticked.collectAsStateWithLifecycle()
    LaunchedEffect(vm.openReview) {
        if (vm.openReview) {
            vm.reviewOpened()
            onReview()
        }
    }
    val day = vm.day ?: return
    if (!vm.loaded) return
    val visible = vm.visible

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ogn_results_heading, day.name ?: day.code, day.date.shortText()), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.ogn_tracked_only), style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ogn_show_all), Modifier.weight(1f))
                Switch(checked = vm.showAll, onCheckedChange = vm::changeShowAll)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { vm.selectAll() }) { Text(stringResource(R.string.ogn_select_all)) }
                TextButton(onClick = { vm.selectNone() }) { Text(stringResource(R.string.ogn_select_none)) }
                Text(pluralStringResource(R.plurals.ogn_ticked, ticked.size, ticked.size), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
        HorizontalDivider()
        if (visible.isEmpty()) Text(stringResource(R.string.ogn_no_rows), Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(visible, key = { it.flight.index }) { row ->
                ResultRow(row, day, row.flight.index in ticked) { vm.toggle(row.flight.index) }
                HorizontalDivider()
            }
        }
        Button(onClick = { vm.review() }, enabled = ticked.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(stringResource(R.string.ogn_review))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultRow(row: OgnRow, day: OgnDay, checked: Boolean, onToggle: () -> Unit) {
    val f = row.flight
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val name = listOfNotNull(f.registration, f.competition?.let { "($it)" }, f.aircraft).joinToString(" ")
            Text(name.ifEmpty { stringResource(R.string.ogn_badge_unidentified) }, style = MaterialTheme.typography.bodyLarge)
            val times = stringResource(R.string.ogn_times, f.start?.let(OgnImport::clock)?.toString() ?: "?", f.stop?.let(OgnImport::clock)?.toString() ?: "")
            val duration = OgnImport.durationMinutes(f)?.takeIf { f.stop != null }?.let(Durations::formatHm)
            Text(listOfNotNull(times, duration).joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (f.towing) Badge(stringResource(R.string.ogn_badge_tow))
                if (row.alreadyLogged) Badge(stringResource(R.string.ogn_badge_logged))
                if (row.noLanding) Badge(stringResource(R.string.ogn_badge_no_landing))
                // For today's flights still in the air: shown as a hint, never stored.
                if (row.noLanding && day.date == LocalDate.now()) f.durationExpectedSeconds?.let { s ->
                    Badge(stringResource(R.string.ogn_badge_expected, Durations.formatHm(maxOf(0, s / 60))))
                }
                row.approximateMinutes?.let { Badge(stringResource(R.string.ogn_badge_approximate, it)) }
                row.checkTimeMinutes?.let { Badge(stringResource(R.string.ogn_badge_check_time, it)) }
                f.startCode?.takeIf { !it.equals(day.code, ignoreCase = true) }?.let { Badge(stringResource(R.string.ogn_badge_launched_at, it)) }
                OgnImport.landedElsewhere(f, day)?.let { Badge(stringResource(R.string.ogn_badge_landed_at, it)) }
                when (row.gliderMatch) {
                    GliderMatch.MATCHED -> Unit
                    GliderMatch.AMBIGUOUS -> Badge(stringResource(R.string.ogn_badge_ambiguous))
                    GliderMatch.NOT_IN_LIST -> Badge(stringResource(R.string.ogn_badge_not_in_list))
                    GliderMatch.UNIDENTIFIED -> Badge(stringResource(R.string.ogn_badge_unidentified))
                }
                if (row.likelyDuplicate) Badge(stringResource(R.string.ogn_badge_duplicate))
            }
        }
    }
}
