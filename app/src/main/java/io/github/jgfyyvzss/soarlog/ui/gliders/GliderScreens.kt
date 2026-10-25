package io.github.jgfyyvzss.soarlog.ui.gliders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.HoursFormat
import io.github.jgfyyvzss.soarlog.core.LogbookCalculator
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.ui.isoDateShort
import io.github.jgfyyvzss.soarlog.ui.logbook.LogbookCalculatorSheet
import io.github.jgfyyvzss.soarlog.ui.weglide.WeGlideTypeSection
import io.github.jgfyyvzss.soarlog.ui.shortText
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.ui.text
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.data.GliderEntity
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.SaveResult
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import kotlinx.coroutines.flow.first
import io.github.jgfyyvzss.soarlog.ui.Badge
import io.github.jgfyyvzss.soarlog.ui.ColonTransformation
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import kotlinx.coroutines.launch

@Composable
fun GliderListScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val app = appContainer()
    val gliders by app.repository.observeGliders().collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    Box(Modifier.fillMaxSize()) {
        if (gliders.isEmpty()) {
            Text(stringResource(R.string.gliders_none_yet), Modifier.padding(16.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(gliders, key = { it.id }) { g ->
                ListItem(
                    headlineContent = { Text(g.registration) },
                    supportingContent = { if (g.type.isNotEmpty()) Text(g.type) },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (g.id == settings.defaultGliderId && !g.archived) Badge(stringResource(R.string.gliders_default_badge))
                            if (g.incomplete) Badge(stringResource(R.string.common_incomplete))
                            if (g.weglideAircraftId == null && !g.archived) Badge(stringResource(R.string.gliders_no_weglide))
                            if (g.archived) Badge(stringResource(R.string.common_archived))
                        }
                    },
                    modifier = Modifier.clickable { onOpen(g.id) },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.title_new_glider))
        }
    }
}

class GliderEditViewModel(private val repo: LogbookRepository, private val settings: SettingsRepository, private val id: String?) : ViewModel() {
    var registration by mutableStateOf("")
    var type by mutableStateOf("")
    var archived by mutableStateOf(false)
    /** Whether new hand-entered flights start with this glider. */
    var isDefault by mutableStateOf(false)
    var error by mutableStateOf<Msg?>(null)
    var loaded by mutableStateOf(id == null)
        private set
    var done by mutableStateOf(false)
        private set

    init {
        if (id != null) viewModelScope.launch {
            repo.glider(id)?.let { registration = it.registration; type = it.type; archived = it.archived }
            isDefault = settings.settings.first().defaultGliderId == id
            loaded = true
        }
    }

    /** Ticking the default moves it here from any other glider; an archived glider is never the default. */
    fun save() = viewModelScope.launch {
        when (val r = repo.saveGlider(id, registration, type, archived)) {
            is SaveResult.Saved -> {
                val current = settings.settings.first().defaultGliderId
                when {
                    isDefault && !archived -> settings.setDefaultGlider(r.id)
                    current == r.id -> settings.setDefaultGlider(null)
                }
                done = true
            }
            is SaveResult.Rejected -> error = r.message
        }
    }
}

@Composable
fun GliderEditScreen(id: String?, onDone: () -> Unit) {
    val vm: GliderEditViewModel = viewModel(factory = vmFactory { GliderEditViewModel(it.repository, it.settings, id) })
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    if (!vm.loaded) return
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = vm.registration,
            onValueChange = { vm.registration = it; vm.error = null },
            label = { Text(stringResource(R.string.glider_registration)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vm.type,
            onValueChange = { vm.type = it },
            label = { Text(stringResource(R.string.glider_type)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.glider_default), Modifier.weight(1f))
            Checkbox(checked = vm.isDefault && !vm.archived, onCheckedChange = { vm.isDefault = it }, enabled = !vm.archived)
        }
        Text(stringResource(R.string.glider_default_hint), style = MaterialTheme.typography.bodySmall)
        if (id != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.common_archived), Modifier.weight(1f))
                Switch(checked = vm.archived, onCheckedChange = { vm.archived = it })
            }
            Text(stringResource(R.string.gliders_archived_hint), style = MaterialTheme.typography.bodySmall)
        }
        vm.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_save)) }
        if (id != null) {
            WeGlideTypeSection(id) { vm.type = it }
            LogbookTotalSection(id)
        }
    }
}

/** The total last written in the aircraft logbook, never a computed airframe total, and the manual calculator. */
@Composable
private fun LogbookTotalSection(gliderId: String) {
    val app = appContainer()
    val glider by remember { app.repository.observeGlider(gliderId) }.collectAsStateWithLifecycle(null)
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    var calculator by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val g = glider ?: return

    HorizontalDivider()
    Text(stringResource(R.string.flights_aircraft_logbook), style = MaterialTheme.typography.titleMedium)
    Text(
        g.logbookTotalMinutes?.let { m ->
            val hours = HoursFormat.format(m, settings.hoursDisplay)
            val date = g.logbookTotalDate?.let { isoDateShort(it) } ?: ""
            g.logbookTotalFlights?.let { stringResource(R.string.glider_total_with_flights, hours, it, date) }
                ?: stringResource(R.string.glider_total, hours, date)
        } ?: stringResource(R.string.glider_no_total),
    )
    Text(
        stringResource(R.string.glider_total_hint),

        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { calculator = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.glider_calculator)) }
        OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.glider_edit_total)) }
    }

    if (calculator) LogbookCalculatorSheet(gliderId, day = null) { calculator = false }
    if (editing) EditTotalDialog(g, onDismiss = { editing = false })
}

/** Edit or clear the stored logbook total by hand. Hours and date go together; the flight count is optional. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditTotalDialog(g: GliderEntity, onDismiss: () -> Unit) {
    val app = appContainer()
    val scope = rememberCoroutineScope()
    var hours by rememberSaveable { mutableStateOf(g.logbookTotalMinutes?.let(Durations::formatHm) ?: "") }
    var flights by rememberSaveable { mutableStateOf(g.logbookTotalFlights?.toString() ?: "") }
    var date by remember { mutableStateOf(g.logbookTotalDate?.let(LocalDate::parse) ?: LocalDate.now()) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val needHours = stringResource(R.string.glider_total_need_hours)
    val badHours = stringResource(R.string.logbook_bad_hours)
    var pickDate by rememberSaveable { mutableStateOf(false) }

    fun save(minutes: Int?, count: Int?) = scope.launch {
        when (val r = app.repository.saveLogbookTotal(g.id, minutes, count, if (minutes == null) null else date)) {
            is SaveResult.Saved -> onDismiss()
            is SaveResult.Rejected -> error = r.message.text(context)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.glider_total_title, g.registration)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hours, { hours = it; error = null }, label = { Text(stringResource(R.string.common_hours)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    supportingText = { Text(stringResource(R.string.logbook_hours_hint)) }, visualTransformation = ColonTransformation)
                OutlinedTextField(flights, { flights = it.filter(Char::isDigit); error = null }, label = { Text(stringResource(R.string.common_flights_optional)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(date.shortText(), {}, readOnly = true, label = { Text(stringResource(R.string.common_as_of)) }, modifier = Modifier.fillMaxWidth())
                    Box(Modifier.matchParentSize().clickable { pickDate = true })
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = LogbookCalculator.parseHours(hours)
                when {
                    hours.isBlank() -> error = needHours
                    m == null -> error = badHours
                    else -> save(m, flights.toIntOrNull())
                }
            }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { save(null, null) }) { Text(stringResource(R.string.common_clear)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state) }
    }
}

/** Glider rows in dropdowns read like `FQM, Hornet`. */
fun GliderEntity.pickerLabel(): String = if (type.isBlank()) registration else "$registration, $type"
