package io.github.jgfyyvzss.soarlog.ui.flights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.paging.insertSeparators
import androidx.paging.map
import io.github.jgfyyvzss.soarlog.data.GliderEntity
import io.github.jgfyyvzss.soarlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.soarlog.ui.logbook.LogbookCalculatorSheet
import java.time.LocalDate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.ui.label
import io.github.jgfyyvzss.soarlog.ui.launchTypeName
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import io.github.jgfyyvzss.soarlog.core.HoursDisplay
import io.github.jgfyyvzss.soarlog.core.HoursFormat
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.data.FlightListRow
import io.github.jgfyyvzss.soarlog.data.FilterStore
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.isoDateShort
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flatMapLatest
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.data.WitnessRepository

/** A list entry: a day header, or a flight. */
sealed interface FlightListItem {
    data class Day(val date: String) : FlightListItem
    data class Flight(val row: FlightListRow) : FlightListItem
}

class FlightListViewModel(private val repo: LogbookRepository, filterStore: FilterStore, witness: WitnessRepository) : ViewModel() {
    val filter = filterStore.filter

    /** Each witnessed flight's seal status, for the Witnessed and Changed marks. */
    val statuses = witness.observeStatuses()

    /** Flights with a header before the first flight of each day. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val flights = filterStore.filter.flatMapLatest { repo.observeResolved(it) }.flatMapLatest { f ->
        Pager(PagingConfig(pageSize = 60, enablePlaceholders = false)) { repo.pagedFlights(f) }.flow.map { page ->
            page.map<FlightListRow, FlightListItem> { FlightListItem.Flight(it) }.insertSeparators { before, after ->
                val a = (after as? FlightListItem.Flight)?.row ?: return@insertSeparators null
                val b = (before as? FlightListItem.Flight)?.row
                if (b == null || b.date != a.date) FlightListItem.Day(a.date) else null
            }
        }
    }.cachedIn(viewModelScope)

    /** The day and glider the calculator is open for. */
    var calculator by mutableStateOf<Pair<String, LocalDate>?>(null)
    /** Gliders to choose from when more than one flew on the chosen day. */
    var chooseGlider by mutableStateOf<Pair<LocalDate, List<GliderEntity>>?>(null)

    /** Opens the calculator for a day, straight away for one glider or after a choice for several. */
    fun openCalculator(date: LocalDate) = viewModelScope.launch {
        val gliders = repo.glidersOnDay(date)
        when (gliders.size) {
            0 -> Unit
            1 -> calculator = gliders.single().id to date
            else -> chooseGlider = date to gliders
        }
    }
}

/** Newest first by date then log number, filtered by the shared filter, with an aircraft logbook button per day. */
@Composable
fun FlightListScreen(onOpen: (String) -> Unit, onNew: () -> Unit, onWitnessDay: (String) -> Unit) {
    val vm: FlightListViewModel = viewModel(factory = vmFactory { FlightListViewModel(it.repository, it.filter, it.witness) })
    val items = vm.flights.collectAsLazyPagingItems()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val settings by appContainer().settings.settings.collectAsStateWithLifecycle(Settings())
    val statuses by vm.statuses.collectAsStateWithLifecycle(emptyMap())

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            FilterBar()
            if (items.itemCount == 0) {
                Text(stringResource(if (filter.isActive) R.string.flights_none_match else R.string.flights_none_yet), Modifier.padding(16.dp))
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(
                    items.itemCount,
                    key = items.itemKey { if (it is FlightListItem.Day) "day-${it.date}" else (it as FlightListItem.Flight).row.id },
                ) { index ->
                    when (val item = items[index]) {
                        is FlightListItem.Day -> DayHeader(
                            item.date,
                            onLogbook = { vm.openCalculator(LocalDate.parse(item.date)) },
                            onWitness = if (settings.witnessSignoff) ({ onWitnessDay(item.date) }) else null,
                        )
                        is FlightListItem.Flight -> {
                            val status = if (settings.witnessSignoff) statuses[item.row.id] else null
                            FlightRow(item.row, settings.hoursDisplay, status, onClick = { onOpen(item.row.id) })
                            HorizontalDivider()
                        }
                        null -> Unit
                    }
                }
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.home_new_flight))
        }
    }

    vm.chooseGlider?.let { (date, gliders) ->
        AlertDialog(
            onDismissRequest = { vm.chooseGlider = null },
            title = { Text(stringResource(R.string.flights_which_glider)) },
            text = {
                Column {
                    gliders.forEach { g ->
                        ListItem(
                            headlineContent = { Text(g.pickerLabel()) },
                            modifier = Modifier.clickable { vm.chooseGlider = null; vm.calculator = g.id to date },
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { vm.chooseGlider = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    vm.calculator?.let { (gliderId, date) -> LogbookCalculatorSheet(gliderId, date) { vm.calculator = null } }
}

@Composable
private fun DayHeader(isoDate: String, onLogbook: () -> Unit, onWitness: (() -> Unit)?) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(isoDateShort(isoDate), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
        onWitness?.let { TextButton(onClick = it) { Text(stringResource(R.string.witness_action)) } }
        TextButton(onClick = onLogbook) { Text(stringResource(R.string.flights_aircraft_logbook)) }
    }
}

/** A small Witnessed, Changed or Cannot verify mark; nothing for a flight that is not witnessed. */
@Composable
fun WitnessMark(status: SealStatus?, modifier: Modifier = Modifier) {
    val (text, color) = when (status ?: return) {
        SealStatus.WITNESSED -> stringResource(R.string.witness_mark_witnessed) to MaterialTheme.colorScheme.primary
        SealStatus.CHANGED -> stringResource(R.string.witness_mark_changed) to MaterialTheme.colorScheme.error
        SealStatus.CANNOT_VERIFY -> stringResource(R.string.witness_mark_cannot_verify) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, modifier, style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1)
}

/**
 * Key items stay in the same places on every row: number, date and role on the left with the duration at the right
 * edge, then aircraft, airfield and launch type, then the note if there is one. Each line is cut rather than wrapped.
 * [mark] replaces the witness mark, for rows that are not saved yet, such as the OGN import's review.
 */
@Composable
fun FlightRow(
    row: FlightListRow, display: HoursDisplay, status: SealStatus?, onClick: () -> Unit,
    mark: (@Composable (Modifier) -> Unit)? = null,
) {
    val note = row.notesStart?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(row.firstLine(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(HoursFormat.format(row.durationMinutes, display), Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                row.secondLine(),
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (mark != null) mark(Modifier.padding(start = 12.dp)) else WitnessMark(status, Modifier.padding(start = 12.dp))
        }
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** For example `320 · 1/9/26 · PIC`, with the role in the pilot's language; the duration sits at the right edge. */
@Composable
fun FlightListRow.firstLine(): String {
    val roleLabel = Role.entries.firstOrNull { it.name == role }?.label() ?: role
    return listOf(logNo.toString(), isoDateShort(date), roleLabel).joinToString(" · ")
}

/** For example `FQM Hornet · YMBT · Winch`, with a seeded launch type in the pilot's language. */
@Composable
fun FlightListRow.secondLine(): String =
    listOf("$registration $gliderType".trim(), airfieldName, launchTypeName(launchTypeId, launchTypeName)).joinToString(" · ")
