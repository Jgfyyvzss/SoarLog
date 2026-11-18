package io.github.jgfyyvzss.soarlog.ui.flights

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.FlightField
import io.github.jgfyyvzss.soarlog.core.LandingChoice
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.igc.IgcFix
import io.github.jgfyyvzss.soarlog.ui.ColonTransformation
import io.github.jgfyyvzss.soarlog.ui.PickerField
import io.github.jgfyyvzss.soarlog.ui.PickerOption
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.soarlog.ui.label
import io.github.jgfyyvzss.soarlog.ui.launchTypeName
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import io.github.jgfyyvzss.soarlog.ui.witness.WitnessCard
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FlightEditScreen(
    id: String?, igcSegment: Int? = null, onDone: () -> Unit, onWitness: (String) -> Unit = {},
    /** A flight of the OGN import under review, by its index in the day; Save returns it to the review. */
    ognRow: Int? = null,
) {
    val app = appContainer()
    val vm: FlightEditViewModel = viewModel(
        factory = vmFactory {
            val review = ognRow?.let { i ->
                it.ognSession.reviewRow(i)?.let { r ->
                    ReviewDraft(
                        r.draft,
                        r.stub?.let { s -> it.getString(R.string.ogn_review_new_glider, "${s.registration} ${s.type}".trim()) },
                    ) { d -> it.ognSession.storeReviewed(i, d) }
                }
            }
            FlightEditViewModel(it.repository, it.settings, id, igcSegment?.let { i -> it.igcImport.segment(i) }, review)
        },
    )
    LaunchedEffect(vm.done) {
        if (vm.done) {
            igcSegment?.let { app.igcImport.markImported(it, vm.draft.gliderId) }
            onDone()
        }
    }
    if (!vm.loaded) return

    val settings by vm.settings.collectAsStateWithLifecycle()
    val gliders by vm.gliders.collectAsStateWithLifecycle()
    val airfields by vm.airfields.collectAsStateWithLifecycle()
    val launchTypes by vm.launchTypes.collectAsStateWithLifecycle()
    val d = vm.draft
    val e = vm.errors
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var newGlider by rememberSaveable { mutableStateOf(false) }
    var newAirfield by rememberSaveable { mutableStateOf(false) }

    // Archived records are hidden, except one this flight already uses.
    val gliderOptions = gliders.filter { !it.archived || it.id == d.gliderId }.map { PickerOption(it.id, it.pickerLabel()) }
    val airfieldOptions = airfields.filter { !it.archived || it.id == d.airfieldId || it.id == d.landingAirfieldId }
        .map { PickerOption(it.id, it.name) }
    val launchOptions = launchTypes.filter { !it.archived || it.id == d.launchTypeId }
        .map { PickerOption(it.id, launchTypeName(it.id, it.name)) }

    val numeric = KeyboardOptions(keyboardType = KeyboardType.Number)
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                d.logNoText, vm::setLogNo, label = { Text(stringResource(R.string.flight_log_no)) }, singleLine = true,
                isError = FlightField.LOG_NO in e,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.weight(1.4f)) {
                OutlinedTextField(
                    d.date?.shortText() ?: "", {}, readOnly = true, label = { Text(stringResource(R.string.common_date)) },
                    isError = FlightField.DATE in e, modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.matchParentSize().clickable { pickDate = true })
            }
        }
        (e[FlightField.LOG_NO] ?: e[FlightField.DATE])?.let { ErrorText(it) }
        vm.igcPreview?.let { (launch, landing) ->
            Text(stringResource(R.string.flight_igc_preview, launch, landing), style = MaterialTheme.typography.bodyMedium)
        }

        PickerField(
            stringResource(R.string.flight_glider), d.gliderId, gliderOptions, vm::setGlider, error = e[FlightField.GLIDER]?.text(),
            selectedLabel = vm.reviewGliderLabel,
            addNewLabel = stringResource(R.string.title_new_glider), onAddNew = { newGlider = true },
        )
        PickerField(
            stringResource(R.string.flight_airfield), d.airfieldId, airfieldOptions, vm::setAirfield, error = e[FlightField.AIRFIELD]?.text(),
            addNewLabel = stringResource(R.string.title_new_airfield), onAddNew = { newAirfield = true },
        )
        // Other airfields within range of the IGC launch point, with their distances.
        val others = vm.nearbyAirfields.filter { it.id != d.airfieldId }
        if (others.isNotEmpty()) {
            Text(stringResource(R.string.flight_igc_nearby), style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                others.forEach { n ->
                    val name = airfields.firstOrNull { it.id == n.id }?.name ?: return@forEach
                    AssistChip(
                        onClick = { vm.setAirfield(n.id) },
                        label = { Text(stringResource(R.string.flight_igc_nearby_item, name, n.distanceM.roundToInt())) },
                    )
                }
            }
        }
        if (vm.airfieldClubs.size > 1) {
            PickerField(
                stringResource(R.string.flight_club), d.clubId, vm.airfieldClubs.map { PickerOption(it.id, it.name) }, vm::setClub,
                error = e[FlightField.CLUB]?.text(),
            )
        } else {
            e[FlightField.CLUB]?.let { ErrorText(it) }
        }
        PickerField(
            stringResource(R.string.flight_launch_type), d.launchTypeId, launchOptions, vm::setLaunchType,
            error = e[FlightField.LAUNCH_TYPE]?.text(),
        )

        Text(stringResource(R.string.totals_role), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = d.role == Role.PIC, onClick = { vm.setRole(Role.PIC) }, label = { Text(Role.PIC.label()) })
            FilterChip(
                selected = d.role.isDual, onClick = { if (!d.role.isDual) vm.setRole(Role.STUDENT) },
                label = { Text(stringResource(R.string.role_dual)) },
            )
        }
        if (d.role.isDual) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Role.dualRoles.forEach { r ->
                    FilterChip(selected = d.role == r, onClick = { vm.setRole(r) }, label = { Text(r.label()) })
                }
            }
        }
        OutlinedTextField(
            d.picName, vm::setPicName, label = { Text(stringResource(R.string.flight_pic)) }, singleLine = true,
            isError = FlightField.PIC_NAME in e, supportingText = e[FlightField.PIC_NAME]?.let { { Text(it.text()) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth(),
        )

        if (settings.recordTimes) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    d.launchTimeText, vm::setLaunchTime, label = { Text(stringResource(R.string.flight_launch)) },
                    placeholder = { Text("13:05") },
                    singleLine = true, isError = FlightField.LAUNCH_TIME in e, keyboardOptions = numeric,
                    visualTransformation = ColonTransformation, modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    d.landingTimeText, vm::setLandingTime, label = { Text(stringResource(R.string.flight_landing)) },
                    placeholder = { Text("17:36") },
                    singleLine = true, isError = FlightField.LANDING_TIME in e, keyboardOptions = numeric,
                    visualTransformation = ColonTransformation, modifier = Modifier.weight(1f),
                )
            }
            (e[FlightField.LAUNCH_TIME] ?: e[FlightField.LANDING_TIME])?.let { ErrorText(it) }
        }

        OutlinedTextField(
            d.durationText, vm::setDuration, label = { Text(stringResource(R.string.flight_duration)) }, placeholder = { Text("3:24") },
            singleLine = true, isError = FlightField.DURATION in e, keyboardOptions = numeric,
            supportingText = { Text(e[FlightField.DURATION]?.text() ?: durationHint(d.durationText)) },
            visualTransformation = ColonTransformation,
            modifier = Modifier.fillMaxWidth(),
        )

        if (settings.recordLandingPlace) {
            Text(stringResource(R.string.flight_landing_place), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    d.landingChoice == LandingChoice.SAME_AS_DEPARTURE, { vm.setLandingChoice(LandingChoice.SAME_AS_DEPARTURE) },
                    label = { Text(stringResource(R.string.flight_same_as_departure)) },
                )
                FilterChip(
                    d.landingChoice == LandingChoice.AIRFIELD, { vm.setLandingChoice(LandingChoice.AIRFIELD) },
                    label = { Text(stringResource(R.string.flight_airfield)) },
                )
                FilterChip(
                    d.landingChoice == LandingChoice.OTHER, { vm.setLandingChoice(LandingChoice.OTHER) },
                    label = { Text(stringResource(R.string.flight_other_place)) },
                )
            }
            when (d.landingChoice) {
                LandingChoice.AIRFIELD -> PickerField(
                    stringResource(R.string.flight_landing_airfield), d.landingAirfieldId, airfieldOptions, vm::setLandingAirfield,
                    error = e[FlightField.LANDING_AIRFIELD]?.text(),
                )
                LandingChoice.OTHER -> OutlinedTextField(
                    d.landingPlace, vm::setLandingPlace, label = { Text(stringResource(R.string.flight_where_ended)) },
                    isError = FlightField.LANDING_PLACE in e,
                    supportingText = e[FlightField.LANDING_PLACE]?.let { { Text(it.text()) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                LandingChoice.SAME_AS_DEPARTURE -> Unit
            }
        }

        OutlinedTextField(
            d.notes, vm::setNotes, label = { Text(stringResource(R.string.flight_notes)) }, minLines = 2,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth(),
        )

        if (settings.witnessSignoff && id != null) {
            HorizontalDivider()
            WitnessCard(id, vm.savedDate, onWitness = { onWitness(id) })
            HorizontalDivider()
        }

        vm.warnings.forEach { Text(it.text(), color = MaterialTheme.colorScheme.tertiary) }
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (vm.warnings.isNotEmpty() && e.isEmpty()) R.string.flight_save_anyway else R.string.common_save))
        }
        if (!vm.isNew) {
            OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.flight_delete))
            }
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (d.date ?: LocalDate.now()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.setDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state) }
    }

    if (newGlider) NewGliderDialog(onDismiss = { newGlider = false }) { reg, type, report ->
        vm.addGlider(reg, type) { error -> if (error == null) newGlider = false else report(error) }
    }
    if (newAirfield) NewAirfieldDialog(onDismiss = { newAirfield = false }) { name, report ->
        vm.addAirfield(name) { error -> if (error == null) newAirfield = false else report(error) }
    }

    if (vm.offerAirfieldAtLaunch) vm.igcLaunchFix?.let { fix ->
        AirfieldAtLaunchDialog(fix, onDismiss = vm::dismissAirfieldOffer) { name, report ->
            vm.addAirfieldAtLaunch(name) { error -> if (error != null) report(error) }
        }
    }

    vm.witnessWarning?.let { witnessName ->
        AlertDialog(
            onDismissRequest = vm::dismissWitnessWarning,
            title = { Text(stringResource(R.string.witness_edit_title)) },
            text = { Text(stringResource(R.string.witness_edit_text, witnessName)) },
            confirmButton = { TextButton(onClick = vm::confirmWitnessChange) { Text(stringResource(R.string.witness_edit_confirm)) } },
            dismissButton = { TextButton(onClick = vm::dismissWitnessWarning) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.flight_delete_title, d.logNoText)) },
            text = { Text(stringResource(R.string.flight_delete_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete() }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** Adds a glider without leaving the flight form. The rest of its record can be filled in later. */
@Composable
private fun NewGliderDialog(onDismiss: () -> Unit, onSave: (String, String, (Msg) -> Unit) -> Unit) {
    var registration by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Msg?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_new_glider)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    registration, { registration = it; error = null }, label = { Text(stringResource(R.string.glider_registration)) },
                    singleLine = true, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
                OutlinedTextField(type, { type = it }, label = { Text(stringResource(R.string.glider_type_short)) }, singleLine = true)
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(registration, type) { error = it } }, enabled = registration.isNotBlank()) {
                Text(stringResource(R.string.common_add))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Adds an airfield by name without leaving the flight form. It shows as Incomplete until it has coordinates. */
@Composable
private fun NewAirfieldDialog(onDismiss: () -> Unit, onSave: (String, (Msg) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Msg?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.title_new_airfield)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true)
                Text(stringResource(R.string.flight_new_airfield_hint), style = MaterialTheme.typography.bodySmall)
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) { error = it } }, enabled = name.isNotBlank()) { Text(stringResource(R.string.common_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/**
 * Offered when no airfield is near an IGC launch point: creates one there, with the fix's altitude as its elevation.
 * Cancelling leaves the airfield blank for the pilot to choose.
 */
@Composable
private fun AirfieldAtLaunchDialog(fix: IgcFix, onDismiss: () -> Unit, onSave: (String, (Msg) -> Unit) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<Msg?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flight_igc_no_airfield)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        R.string.flight_igc_launch_point,
                        String.format(Locale.ROOT, "%.4f", fix.latitude), String.format(Locale.ROOT, "%.4f", fix.longitude), fix.altitudeM,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(name, { name = it; error = null }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true)
                error?.let { ErrorText(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name) { error = it } }, enabled = name.isNotBlank()) { Text(stringResource(R.string.flight_igc_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** Shows what typed digits mean, for example 324 as = 3:24. */
@Composable
private fun durationHint(text: String): String {
    val minutes = Durations.parseFormEntry(text)
    return when {
        minutes == null || text.isBlank() -> stringResource(R.string.flight_duration_hint)
        // The field already shows the colon, so only overflow such as 1:75 needs the result spelled out.
        ColonTransformation.display(text.trim()) == Durations.formatHm(minutes) -> " "
        else -> "= ${Durations.formatHm(minutes)}"
    }
}

@Composable
private fun ErrorText(message: Msg) {
    Text(message.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
