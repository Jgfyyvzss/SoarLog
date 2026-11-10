package io.github.jgfyyvzss.soarlog.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import io.github.jgfyyvzss.soarlog.core.TypeTotal
import io.github.jgfyyvzss.soarlog.core.TypeTotals
import io.github.jgfyyvzss.soarlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.soarlog.ui.logbook.LogbookCalculatorSheet
import java.time.LocalDate
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.ui.label
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jgfyyvzss.soarlog.core.FlightFilter
import io.github.jgfyyvzss.soarlog.core.HoursFormat
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.RoleTotal
import io.github.jgfyyvzss.soarlog.core.backup.BackupSchedule
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.settings.lastBackupText

/**
 * New flight, From IGC, From logger, From OGN, Upload to WeGlide and the aircraft logbook calculator, a compact unfiltered
 * totals card, totals for the top aircraft types, and the last backup.
 */
@Composable
fun HomeScreen(
    onNewFlight: () -> Unit, onFromIgc: () -> Unit, onFromLogger: () -> Unit, onFromOgn: () -> Unit, onUpload: () -> Unit,
    onTotals: () -> Unit, onBackup: () -> Unit,
) {
    val app = appContainer()
    val tableFlow = remember { app.totals.observe(FlightFilter()) }
    val table by tableFlow.collectAsStateWithLifecycle(null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    val flightsFlow = remember { app.repository.observeTotalsByRole(FlightFilter()) }
    val byRole by flightsFlow.collectAsStateWithLifecycle(emptyMap())
    val hasFlights = byRole.values.any { it.flights > 0 }
    val gliderTotals by remember { app.repository.observeGliderTotals() }.collectAsStateWithLifecycle(emptyList())
    val aircraft by app.aircraft.list.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { app.aircraft.load() }
    val byType = remember(gliderTotals, aircraft) {
        TypeTotals.group(gliderTotals) { id -> aircraft?.aircraft?.firstOrNull { it.id == id }?.name }
    }
    var choosingGlider by rememberSaveable { mutableStateOf(false) }
    var calculatorFor by rememberSaveable { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = onNewFlight, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("  " + stringResource(R.string.home_new_flight))
        }
        OutlinedButton(onClick = onFromIgc, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.FileOpen, contentDescription = null)
            Text("  " + stringResource(R.string.home_from_igc))
        }
        OutlinedButton(onClick = onFromLogger, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.Bluetooth, contentDescription = null)
            Text("  " + stringResource(R.string.home_from_logger))
        }
        OutlinedButton(onClick = onFromOgn, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.Public, contentDescription = null)
            Text("  " + stringResource(R.string.home_from_ogn))
        }
        OutlinedButton(onClick = onUpload, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.CloudUpload, contentDescription = null)
            Text("  " + stringResource(R.string.home_upload))
        }
        OutlinedButton(onClick = { choosingGlider = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Icon(Icons.Default.Calculate, contentDescription = null)
            Text("  " + stringResource(R.string.flights_aircraft_logbook))
        }
        table?.let { t ->
            Card(Modifier.fillMaxWidth().clickable(onClick = onTotals)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.nav_totals), style = MaterialTheme.typography.titleMedium)
                    CardRow(Role.PIC.label() + if (t.picIncludesInstruction) " *" else "", t.rows.getValue(Role.PIC), settings)
                    CardRow(stringResource(R.string.totals_combined), t.combined, settings)
                }
            }
        }
        if (byType.isNotEmpty()) {
            TypeTotalsCard(byType, settings)
        }
        // Nudges when there are flights and the last backup is stale or missing.
        val stale = hasFlights && BackupSchedule.isStale(settings.lastBackupAt, System.currentTimeMillis())
        Text(
            stringResource(if (stale) R.string.home_last_backup_stale else R.string.home_last_backup, lastBackupText(settings.lastBackupAt)),
            color = if (stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().clickable(onClick = onBackup).padding(vertical = 8.dp),
        )
    }

    if (choosingGlider) {
        GliderChoice(onDismiss = { choosingGlider = false }) { id -> choosingGlider = false; calculatorFor = id }
    }
    calculatorFor?.let { id -> HomeCalculator(id) { calculatorFor = null } }
}

/** The pilot's own time on the [TYPE_ROWS] aircraft types with the most hours. */
@Composable
private fun TypeTotalsCard(rows: List<TypeTotal>, settings: Settings) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.home_by_type), style = MaterialTheme.typography.titleMedium)
            rows.take(TYPE_ROWS).forEach { t -> CardRow(t.label, RoleTotal(t.minutes, t.flights), settings) }
        }
    }
}

/** A fixed number, so the card fits every phone. */
private const val TYPE_ROWS = 3

/** Which glider's aircraft logbook to work on; gliders flown today come first. */
@Composable
private fun GliderChoice(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val repo = appContainer().repository
    val gliders by remember { repo.observeGliders() }.collectAsStateWithLifecycle(emptyList())
    var today by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) { today = repo.glidersOnDay(LocalDate.now()).map { it.id }.toSet() }
    val shown = gliders.filter { !it.archived || it.id in today }.sortedBy { it.id !in today }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flights_which_glider)) },
        text = {
            if (shown.isEmpty()) {
                Text(stringResource(R.string.picker_empty))
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(shown, key = { it.id }) { g ->
                        ListItem(
                            headlineContent = { Text(g.pickerLabel()) },
                            supportingContent = if (g.id in today) ({ Text(stringResource(R.string.home_flown_today)) }) else null,
                            modifier = Modifier.clickable { onPick(g.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * The calculator for today when the glider flew today, so today's flights are already in it; otherwise the manual
 * calculator, for paperwork on a glider the pilot did not fly.
 */
@Composable
private fun HomeCalculator(gliderId: String, onDismiss: () -> Unit) {
    val repo = appContainer().repository
    var day by remember { mutableStateOf<LocalDate?>(null) }
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(gliderId) {
        val now = LocalDate.now()
        day = now.takeIf { repo.glidersOnDay(now).any { it.id == gliderId } }
        checked = true
    }
    if (checked) LogbookCalculatorSheet(gliderId, day, onDismiss)
}

@Composable
private fun CardRow(label: String, total: RoleTotal, settings: Settings) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1.4f))
        Text(HoursFormat.format(total.minutes, settings.hoursDisplay), Modifier.weight(1f), textAlign = TextAlign.End)
        Text(pluralStringResource(R.plurals.home_flights, total.flights, total.flights), Modifier.weight(1.2f), textAlign = TextAlign.End)
    }
}
