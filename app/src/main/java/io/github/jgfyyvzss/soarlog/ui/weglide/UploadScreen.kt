package io.github.jgfyyvzss.soarlog.ui.weglide

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.weglide.UploadedFlight
import io.github.jgfyyvzss.soarlog.core.weglide.WeGlideCredentials
import io.github.jgfyyvzss.soarlog.ui.igc.fileSummary
import io.github.jgfyyvzss.soarlog.ui.igc.logbookStatus
import io.github.jgfyyvzss.soarlog.ui.igc.weglideStatus
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.launch

/**
 * Upload to WeGlide, opened from Home or from the logbook import. Any received IGC file can be uploaded, logged or
 * not; the file's logbook status is shown with a Log flight button, so uploading never hides an unlogged flight.
 * Nothing here changes the logbook, apart from storing a picked WeGlide type on a glider without one.
 */
@Composable
fun UploadScreen(onFromLogger: () -> Unit) {
    val app = appContainer()
    val vm: UploadViewModel = viewModel(
        factory = vmFactory {
            UploadViewModel(it.repository, it.aircraft, it.weglideApi, it.weglideCredentials, it.igcFiles, it.uploadSelection)
        },
    )
    val aircraftList by app.aircraft.list.collectAsStateWithLifecycle()
    val files by vm.files.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { app.igcFiles.add(app.contentResolver, uri)?.let { app.uploadSelection.select(it.id) } ?: vm.unreadable() }
    }
    var choosingAircraft by rememberSaveable { mutableStateOf(false) }
    val status = vm.status
    val entry = vm.fileId?.let { id -> files.firstOrNull { it.id == id } }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.upload_intro), style = MaterialTheme.typography.bodyMedium)
        vm.fileProblem?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }

        if (entry != null) {
            // The chosen file, with both of its jobs.
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium)
                    Text(fileSummary(entry), style = MaterialTheme.typography.bodySmall)
                    Text(logbookStatus(entry))
                    Text(weglideStatus(entry))
                }
            }
            if (entry.flights > 0 && !entry.fullyLogged) {
                OutlinedButton(onClick = { app.igcImport.openStored(entry.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.igc_log_flight))
                }
            }

            if (!entry.uploaded || status is UploadStatus.Done) {
                HorizontalDivider()
                Text(stringResource(R.string.weglide_aircraft), style = MaterialTheme.typography.titleMedium)
                val glider = vm.glider
                Text(
                    vm.aircraft?.label
                        ?: if (glider != null) stringResource(R.string.upload_glider_no_type, glider.registration)
                        else stringResource(R.string.upload_choose_aircraft_hint),
                )
                if (!entry.uploaded) {
                    OutlinedButton(onClick = { choosingAircraft = true }) { Text(stringResource(R.string.common_choose)) }
                }
            }
        }

        HorizontalDivider()
        Credentials(vm)

        if (entry != null) {
            HorizontalDivider()
            if (entry.uploaded && status !is UploadStatus.Done) {
                Text(stringResource(R.string.upload_already))
            } else if (!entry.uploaded) {
                Button(onClick = vm::upload, enabled = vm.canUpload(entry), modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.upload_button))
                }
            }
        }
        when (status) {
            UploadStatus.Idle -> Unit
            UploadStatus.Uploading -> {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.upload_uploading))
            }
            is UploadStatus.Failed -> {
                Text(status.message.text(), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.upload_failed_hint), style = MaterialTheme.typography.bodySmall)
                Button(onClick = vm::upload, enabled = vm.canUpload(entry)) { Text(stringResource(R.string.logger_try_again)) }
            }
            is UploadStatus.Done -> Result(status.flight)
        }

        if (entry != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::close) { Text(stringResource(R.string.upload_use_other)) }
                TextButton(onClick = vm::remove) { Text(stringResource(R.string.upload_remove)) }
            }
        } else {
            // No file chosen: received files with their statuses, so a half-done one stands out, then other sources.
            HorizontalDivider()
            Text(stringResource(R.string.upload_recent), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.upload_recent_hint, LocalContext.current.packageName), style = MaterialTheme.typography.bodySmall)
            if (files.isEmpty()) Text(stringResource(R.string.upload_no_file))
            files.forEach { f ->
                Column(
                    Modifier.fillMaxWidth().clickable { app.uploadSelection.select(f.id) }.padding(vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(f.name, style = MaterialTheme.typography.bodyLarge)
                    Text(fileSummary(f), style = MaterialTheme.typography.bodySmall)
                    Text("${logbookStatus(f)} · ${weglideStatus(f)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
            OutlinedButton(onClick = { pick.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.upload_choose_file))
            }
            OutlinedButton(onClick = onFromLogger, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.home_from_logger)) }
        }
    }

    if (choosingAircraft) {
        val glider = vm.glider
        AircraftPicker(
            aircraft = aircraftList?.aircraft.orEmpty(),
            initialQuery = glider?.type ?: "",
            // A pick for a glider without a type is stored on it, so the type change is shown first.
            currentType = glider?.takeIf { it.weglideAircraftId == null }?.type,
            onDismiss = { choosingAircraft = false },
        ) { picked ->
            vm.pickAircraft(picked)
            choosingAircraft = false
        }
    }
}
/** The WeGlide ID and date of birth: asked for when missing, and otherwise only shown as saved. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Credentials(vm: UploadViewModel) {
    Text(stringResource(R.string.upload_account), style = MaterialTheme.typography.titleMedium)
    if (!vm.credentialsLoaded) return
    if (!vm.editingCredentials) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.upload_account_saved), Modifier.weight(1f))
            TextButton(onClick = vm::editCredentials) { Text(stringResource(R.string.upload_change)) }
        }
        return
    }
    val saved = vm.credentials
    var userId by rememberSaveable { mutableStateOf(saved?.userId ?: "") }
    var dob by rememberSaveable { mutableStateOf(saved?.dateOfBirth?.toString() ?: "") }
    var pickDate by rememberSaveable { mutableStateOf(false) }
    val date = runCatching { LocalDate.parse(dob) }.getOrNull()

    OutlinedTextField(
        userId, { userId = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.upload_weglide_id)) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
        supportingText = { Text(stringResource(R.string.upload_weglide_id_hint)) },
    )
    Box {
        OutlinedTextField(
            date?.shortText() ?: "", {}, readOnly = true, label = { Text(stringResource(R.string.upload_date_of_birth)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.matchParentSize().clickable { pickDate = true })
    }
    Text(stringResource(R.string.upload_account_privacy), style = MaterialTheme.typography.bodySmall)
    Button(
        onClick = { date?.let { vm.saveCredentials(userId, it) } },
        enabled = WeGlideCredentials.validUserId(userId) && date != null,
    ) { Text(stringResource(R.string.common_save)) }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date?.atStartOfDay()?.toInstant(ZoneOffset.UTC)?.toEpochMilli(),
            initialDisplayMode = DisplayMode.Input,
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { dob = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    pickDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state) }
    }
}

/** What WeGlide reported for the new flight. */
@Composable
private fun Result(flight: UploadedFlight?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.upload_done), style = MaterialTheme.typography.titleMedium)
            if (flight == null) {
                Text(stringResource(R.string.upload_done_no_details))
                return@Column
            }
            listOfNotNull(
                flight.id?.let { stringResource(R.string.upload_result_flight, it.toString()) },
                flight.scoringDate?.let { stringResource(R.string.upload_result_date, it) },
                flight.pilotName?.let { stringResource(R.string.upload_result_pilot, it) },
                flight.aircraftName?.let { stringResource(R.string.upload_result_aircraft, it) },
                flight.registration?.let { stringResource(R.string.upload_result_registration, it) },
                flight.competitionId?.let { stringResource(R.string.upload_result_competition_id, it) },
            ).forEach { Text(it) }
        }
    }
}
