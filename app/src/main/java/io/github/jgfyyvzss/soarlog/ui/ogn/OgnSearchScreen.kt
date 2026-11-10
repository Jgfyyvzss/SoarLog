package io.github.jgfyyvzss.soarlog.ui.ogn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.igc.MatchPlace
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.ogn.OgnDay
import io.github.jgfyyvzss.soarlog.core.ogn.OgnMatching
import io.github.jgfyyvzss.soarlog.core.ogn.OgnResult
import io.github.jgfyyvzss.soarlog.data.AirfieldEntity
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.SaveResult
import io.github.jgfyyvzss.soarlog.data.ogn.AirfieldPrefill
import io.github.jgfyyvzss.soarlog.data.ogn.OgnSession
import io.github.jgfyyvzss.soarlog.ui.PickerField
import io.github.jgfyyvzss.soarlog.ui.PickerOption
import io.github.jgfyyvzss.soarlog.ui.gliders.pickerLabel
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface OgnSearchStatus {
    data object Idle : OgnSearchStatus
    data object Searching : OgnSearchStatus
    data class Failed(val message: Msg) : OgnSearchStatus
    data class Found(val day: OgnDay) : OgnSearchStatus
}

/** A code-less airfield near where Flightbook places a new code, offered instead of a second record. */
data class ExistingAirfieldOffer(val airfield: AirfieldEntity, val code: String, val distanceM: Int)

/**
 * The OGN search: an airfield with a code, a date and an optional glider. One request per search; a day already
 * fetched in this session is shown again without asking Flightbook.
 */
class OgnSearchViewModel(private val repo: LogbookRepository, private val session: OgnSession) : ViewModel() {
    val airfields = repo.observeAirfields()
    val gliders = repo.observeGliders()

    var airfieldId by mutableStateOf(session.airfieldId)
        private set
    var date by mutableStateOf(session.date ?: LocalDate.now().also { session.date = it })
        private set
    var glider by mutableStateOf(session.glider)
        private set
    var status by mutableStateOf<OgnSearchStatus>(OgnSearchStatus.Idle)
        private set

    /** An airfield picked without a code, waiting for one. */
    var askCodeFor by mutableStateOf<AirfieldEntity?>(null)
        private set
    /** "+ New airfield": waiting for the code to search. */
    var askNewCode by mutableStateOf(false)
        private set
    var codeError by mutableStateOf<Msg?>(null)
        private set
    var offer by mutableStateOf<ExistingAirfieldOffer?>(null)
        private set
    /** True when the new-airfield form should open, prefilled from [OgnSession.newAirfield]. */
    var openNewAirfield by mutableStateOf(false)
        private set

    /** A new code whose airfield is not in the table yet. It is selected once the form saves it. */
    private var pendingCode: String? = null

    init {
        viewModelScope.launch { showCached(selectedCode()) }
    }

    private suspend fun selectedCode(): String? = airfieldId?.let { repo.airfield(it) }?.code

    /** True once a search has found flights, until the results screen opens. */
    var openResults by mutableStateOf(false)
        private set

    private fun showCached(code: String?) {
        val day = code?.let { session.cached(it, date) }
        if (day != null) session.day = day
        status = day?.let { OgnSearchStatus.Found(it) } ?: OgnSearchStatus.Idle
    }

    private fun found(day: OgnDay) {
        session.day = day
        status = OgnSearchStatus.Found(day)
        if (day.flights.isNotEmpty()) openResults = true
    }

    fun resultsOpened() {
        openResults = false
    }

    private fun select(a: AirfieldEntity) {
        airfieldId = a.id
        session.airfieldId = a.id
        pendingCode = null
        showCached(a.code)
    }

    fun pickAirfield(id: String) = viewModelScope.launch {
        val a = repo.airfield(id) ?: return@launch
        if (a.code == null) {
            codeError = null
            askCodeFor = a
        } else {
            select(a)
        }
    }

    /** Saves the code on the airfield that lacked one, so no second record is made. */
    fun saveCode(code: String) = viewModelScope.launch {
        val a = askCodeFor ?: return@launch
        when (val r = repo.setAirfieldCode(a.id, code)) {
            is SaveResult.Saved -> {
                askCodeFor = null
                repo.airfield(a.id)?.let(::select)
            }
            is SaveResult.Rejected -> codeError = r.message
        }
    }

    fun cancelCode() {
        askCodeFor = null
        askNewCode = false
    }

    fun changeDate(d: LocalDate) = viewModelScope.launch {
        date = d
        session.date = d
        showCached(selectedCode())
    }

    fun changeGlider(text: String) {
        glider = text
        session.glider = text
    }

    fun search() = viewModelScope.launch {
        if (status == OgnSearchStatus.Searching) return@launch
        val code = selectedCode() ?: return@launch
        status = OgnSearchStatus.Searching
        when (val r = session.fetch(code, date)) {
            is OgnResult.Ok -> found(r.day)
            is OgnResult.Failed -> status = OgnSearchStatus.Failed(r.message)
        }
    }

    fun newAirfield() {
        codeError = null
        askNewCode = true
    }

    /**
     * Searches a code typed for a new airfield. A code already in the table selects that airfield. Otherwise a
     * code-less airfield within its match radius of Flightbook's position is offered, and failing that the
     * new-airfield form opens with what Flightbook knows.
     */
    fun searchNewCode(code: String) = viewModelScope.launch {
        if (status == OgnSearchStatus.Searching) return@launch
        val c = Keys.airfieldCode(code) ?: run { codeError = msg(MsgKey.AIRFIELD_CODE_REQUIRED); return@launch }
        askNewCode = false
        val known = repo.airfieldByCode(c)
        if (known != null) {
            select(known)
            search()
            return@launch
        }
        pendingCode = c
        airfieldId = null
        session.airfieldId = null
        status = OgnSearchStatus.Searching
        when (val r = session.fetch(c, date)) {
            is OgnResult.Failed -> status = OgnSearchStatus.Failed(r.message)
            is OgnResult.Ok -> {
                status = OgnSearchStatus.Idle
                offerOrCreate(c, r.day)
            }
        }
    }

    private suspend fun offerOrCreate(code: String, day: OgnDay) {
        session.newAirfield = AirfieldPrefill(code, day.name, day.latitude, day.longitude, day.elevationM)
        val lat = day.latitude
        val lon = day.longitude
        if (lat != null && lon != null) {
            val codeless = repo.observeAirfields().first().filter { it.code == null && it.latitude != null && it.longitude != null }
            val places = codeless.map { MatchPlace(it.id, it.latitude!!, it.longitude!!, it.matchRadiusM) }
            val near = OgnMatching.airfieldsNear(lat, lon, places).firstOrNull()
            if (near != null) {
                offer = ExistingAirfieldOffer(codeless.first { it.id == near.id }, code, near.distanceM.roundToInt())
                return
            }
        }
        openNewAirfield = true
    }

    fun acceptOffer() = viewModelScope.launch {
        val o = offer ?: return@launch
        offer = null
        when (val r = repo.setAirfieldCode(o.airfield.id, o.code)) {
            is SaveResult.Saved -> repo.airfield(o.airfield.id)?.let(::selectAndShow)
            is SaveResult.Rejected -> status = OgnSearchStatus.Failed(r.message)
        }
    }

    fun declineOffer() {
        offer = null
        openNewAirfield = true
    }

    fun dismissOffer() {
        offer = null
        pendingCode = null
    }

    fun newAirfieldOpened() {
        openNewAirfield = false
    }

    /** When the prefilled form has saved the new airfield, it is selected and its day's results open. */
    fun airfieldsChanged(list: List<AirfieldEntity>) {
        val c = pendingCode ?: return
        list.firstOrNull { it.code == c }?.let(::selectAndShow)
    }

    private fun selectAndShow(a: AirfieldEntity) {
        select(a)
        (status as? OgnSearchStatus.Found)?.let { found(it.day) }
    }

    fun retry() {
        val c = pendingCode
        if (c != null && airfieldId == null) searchNewCode(c) else search()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OgnSearchScreen(onNewAirfield: () -> Unit, onResults: () -> Unit) {
    val vm: OgnSearchViewModel = viewModel(factory = vmFactory { OgnSearchViewModel(it.repository, it.ognSession) })
    val airfields by vm.airfields.collectAsStateWithLifecycle(emptyList())
    val gliders by vm.gliders.collectAsStateWithLifecycle(emptyList())
    var pickDate by rememberSaveable { mutableStateOf(false) }
    var pickGlider by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(airfields) { vm.airfieldsChanged(airfields) }
    LaunchedEffect(vm.openNewAirfield) {
        if (vm.openNewAirfield) {
            vm.newAirfieldOpened()
            onNewAirfield()
        }
    }
    LaunchedEffect(vm.openResults) {
        if (vm.openResults) {
            vm.resultsOpened()
            onResults()
        }
    }

    val options = airfields.filter { !it.archived || it.id == vm.airfieldId }.map { a ->
        PickerOption(a.id, a.code?.let { stringResource(R.string.airfield_code_and_name, it, a.name) } ?: a.name)
    }
    val searching = vm.status == OgnSearchStatus.Searching

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PickerField(
            stringResource(R.string.flight_airfield), vm.airfieldId, options, { vm.pickAirfield(it) },
            addNewLabel = stringResource(R.string.title_new_airfield), onAddNew = vm::newAirfield,
        )
        Box {
            OutlinedTextField(
                vm.date.shortText(), {}, readOnly = true, label = { Text(stringResource(R.string.common_date)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.matchParentSize().clickable { pickDate = true })
        }
        OutlinedTextField(
            vm.glider, vm::changeGlider, label = { Text(stringResource(R.string.ogn_glider)) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            trailingIcon = {
                IconButton(onClick = { pickGlider = true }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = stringResource(R.string.common_choose))
                }
            },
            supportingText = { Text(stringResource(R.string.ogn_glider_hint)) },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { vm.search() }, enabled = vm.airfieldId != null && !searching, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ogn_search))
        }

        when (val s = vm.status) {
            OgnSearchStatus.Idle -> Unit
            OgnSearchStatus.Searching -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp))
                Text(stringResource(R.string.ogn_searching))
            }
            is OgnSearchStatus.Failed -> {
                Text(s.message.text(), color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = vm::retry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ogn_try_again)) }
            }
            is OgnSearchStatus.Found -> FoundSummary(s.day, vm.glider, onResults)
        }
    }

    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = vm.date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { vm.changeDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    pickDate = false
                }) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } },
        ) { DatePicker(state) }
    }

    if (pickGlider) {
        val shown = gliders.filter { !it.archived }
        AlertDialog(
            onDismissRequest = { pickGlider = false },
            title = { Text(stringResource(R.string.flight_glider)) },
            text = {
                if (shown.isEmpty()) {
                    Text(stringResource(R.string.picker_empty))
                } else {
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(shown, key = { it.id }) { g ->
                            ListItem(
                                headlineContent = { Text(g.pickerLabel()) },
                                modifier = Modifier.clickable { vm.changeGlider(g.registration); pickGlider = false },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vm.changeGlider(""); pickGlider = false }) { Text(stringResource(R.string.common_clear)) } },
            dismissButton = { TextButton(onClick = { pickGlider = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    vm.askCodeFor?.let { a ->
        CodeDialog(
            title = stringResource(R.string.ogn_code_title, a.name), text = stringResource(R.string.ogn_code_text),
            confirm = stringResource(R.string.common_save), error = vm.codeError, onDismiss = vm::cancelCode, onConfirm = { vm.saveCode(it) },
        )
    }
    if (vm.askNewCode) {
        CodeDialog(
            title = stringResource(R.string.ogn_new_code_title), text = stringResource(R.string.ogn_new_code_text),
            confirm = stringResource(R.string.ogn_search), error = vm.codeError, onDismiss = vm::cancelCode, onConfirm = { vm.searchNewCode(it) },
        )
    }
    vm.offer?.let { o ->
        AlertDialog(
            onDismissRequest = vm::dismissOffer,
            title = { Text(stringResource(R.string.ogn_use_existing_title, o.airfield.name)) },
            text = { Text(stringResource(R.string.ogn_use_existing_text, o.airfield.name, o.distanceM, o.code)) },
            confirmButton = { TextButton(onClick = { vm.acceptOffer() }) { Text(stringResource(R.string.ogn_use_existing_yes)) } },
            dismissButton = { TextButton(onClick = vm::declineOffer) { Text(stringResource(R.string.ogn_use_existing_no)) } },
        )
    }
}

/** What the search found, with a way back to the results after going back from them. */
@Composable
private fun FoundSummary(day: OgnDay, glider: String, onResults: () -> Unit) {
    if (day.flights.isEmpty()) {
        Text(stringResource(R.string.ogn_empty_day, day.code, day.date.shortText()))
        return
    }
    val gliderFlights = day.flights.filter { it.isGlider }
    Text(pluralStringResource(R.plurals.ogn_found, gliderFlights.size, gliderFlights.size))
    if (glider.isNotBlank()) {
        val mine = gliderFlights.count { OgnMatching.sameRegistration(it.registration, glider) }
        Text(pluralStringResource(R.plurals.ogn_found_glider, mine, mine, glider.trim()))
    }
    OutlinedButton(onClick = onResults, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ogn_show_flights)) }
}

/** Asks for an airfield code, usually the ICAO code. */
@Composable
private fun CodeDialog(title: String, text: String, confirm: String, error: Msg?, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    code, { code = it }, label = { Text(stringResource(R.string.airfield_code)) }, singleLine = true,
                    isError = error != null, supportingText = error?.let { { Text(it.text()) } },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(code) }, enabled = code.isNotBlank()) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
