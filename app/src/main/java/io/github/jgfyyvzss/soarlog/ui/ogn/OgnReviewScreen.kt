package io.github.jgfyyvzss.soarlog.ui.ogn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import io.github.jgfyyvzss.soarlog.core.FlightRules
import io.github.jgfyyvzss.soarlog.core.FlightValidation
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.PicName
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.data.ClubEntity
import io.github.jgfyyvzss.soarlog.data.FlightLabel
import io.github.jgfyyvzss.soarlog.data.FlightListRow
import io.github.jgfyyvzss.soarlog.data.FlightSource
import io.github.jgfyyvzss.soarlog.data.ImportCreateResult
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import io.github.jgfyyvzss.soarlog.data.ogn.OgnSession
import io.github.jgfyyvzss.soarlog.data.ogn.ReviewRow
import io.github.jgfyyvzss.soarlog.ui.PickerField
import io.github.jgfyyvzss.soarlog.ui.PickerOption
import io.github.jgfyyvzss.soarlog.ui.flights.FlightRow
import io.github.jgfyyvzss.soarlog.ui.isoDateShort
import io.github.jgfyyvzss.soarlog.ui.label
import io.github.jgfyyvzss.soarlog.ui.launchTypeName
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.time.LocalDate
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A review row with how it will look in the Flights list and what stops it being created. */
data class ReviewItem(val row: ReviewRow, val preview: FlightListRow, val problems: List<Msg>)

/**
 * The ticked flights as Flights-page rows, not yet saved. Each opens the flight form; a bar sets launch type, role
 * or club for every row. Create writes them all in one transaction, as one import batch, or nothing.
 */
class OgnReviewViewModel(
    private val repo: LogbookRepository,
    private val settingsRepo: SettingsRepository,
    private val session: OgnSession,
) : ViewModel() {
    val settings = settingsRepo.settings
    val launchTypes = repo.observeLaunchTypes()

    var items by mutableStateOf<List<ReviewItem>>(emptyList())
        private set
    /** The clubs of every airfield the rows use, for the bulk club choice. */
    var clubsByAirfield by mutableStateOf<Map<String, List<ClubEntity>>>(emptyMap())
        private set
    /** Set while asking whether to go on with flights dated before the newest logged flight. */
    var earlier by mutableStateOf<FlightLabel?>(null)
        private set
    var outcome by mutableStateOf<ImportCreateResult?>(null)
        private set
    var creating by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch { session.review.collectLatest { rows -> items = build(rows) } }
    }

    private suspend fun build(rows: List<ReviewRow>): List<ReviewItem> {
        val gliders = repo.observeGliders().first().associateBy { it.id }
        val airfields = repo.observeAirfields().first().associateBy { it.id }
        val types = repo.observeLaunchTypes().first().associateBy { it.id }
        clubsByAirfield = rows.mapNotNull { it.draft.airfieldId }.distinct().associateWith { repo.clubsFor(it) }
        val numbers = rows.map { FlightRules.parseLogNo(it.draft.logNoText) }
        return rows.mapIndexed { i, r ->
            val d = r.draft
            val problems = mutableListOf<Msg>()
            (repo.validateFlight(null, d) as? FlightValidation.Invalid)?.let { problems += it.errors.values }
            numbers[i]?.let { n -> if (numbers.count { it == n } > 1) problems += msg(MsgKey.OGN_LOG_NO_REPEATED, n) }
            if (r.needsTimeCheck) problems += msg(MsgKey.OGN_CHECK_TIME, r.checkTimeMinutes)
            val glider = d.gliderId?.let { gliders[it] }
            val type = d.launchTypeId?.let { types[it] }
            val preview = FlightListRow(
                id = "ogn-${r.flightIndex}",
                logNo = numbers[i] ?: 0,
                date = d.date?.toString().orEmpty(),
                durationMinutes = Durations.parseFormEntry(d.durationText) ?: 0,
                role = d.role.name,
                registration = glider?.registration ?: r.stub?.registration.orEmpty(),
                gliderType = glider?.type ?: r.stub?.type.orEmpty(),
                airfieldName = d.airfieldId?.let { airfields[it]?.name }.orEmpty(),
                launchTypeName = type?.name.orEmpty(),
                launchTypeId = type?.id.orEmpty(),
                notesStart = d.notes.take(120).ifBlank { null },
            )
            ReviewItem(r, preview, problems)
        }
    }

    fun setLaunchTypeAll(id: String) = session.updateReview { rows -> rows.map { it.copy(draft = it.draft.copy(launchTypeId = id)) } }

    fun setRoleAll(role: Role) = viewModelScope.launch {
        val profile = settingsRepo.settings.first().pilotName
        session.updateReview { rows ->
            rows.map { it.copy(draft = it.draft.copy(role = role, picName = PicName.afterRoleChange(it.draft.picName, role, profile))) }
        }
    }

    /** Sets the club on every row whose airfield has it; other rows keep theirs. */
    fun setClubAll(clubId: String) = session.updateReview { rows ->
        rows.map { r ->
            val clubs = r.draft.airfieldId?.let { clubsByAirfield[it] }.orEmpty()
            if (clubs.any { it.id == clubId }) r.copy(draft = r.draft.copy(clubId = clubId)) else r
        }
    }

    val blocked: Boolean get() = items.isEmpty() || items.any { it.problems.isNotEmpty() }

    /** Warns first when any flight is dated before the newest flight already logged. */
    fun create(earlierConfirmed: Boolean = false) = viewModelScope.launch {
        if (blocked || creating) return@launch
        earlier = null
        if (!earlierConfirmed) {
            val newest = repo.newestFlight()
            val newestDate = newest?.let { runCatching { LocalDate.parse(it.date) }.getOrNull() }
            if (newestDate != null && items.any { i -> i.row.draft.date?.isBefore(newestDate) == true }) {
                earlier = newest
                return@launch
            }
        }
        creating = true
        val rows = session.review.value
        val stubs = rows.mapNotNull { r -> r.stub?.let { ReviewRow.stubId(it.registration) to (it.registration to it.type) } }.toMap()
        val day = session.day
        val result = repo.createImportedFlights(rows.map { it.draft }, stubs, "OGN ${day?.code.orEmpty()} ${day?.date ?: ""}".trim(), FlightSource.OGN)
        if (result is ImportCreateResult.Created) session.finish()
        outcome = result
        creating = false
    }

    fun cancelEarlier() {
        earlier = null
    }
}

@Composable
fun OgnReviewScreen(onOpen: (Int) -> Unit, onCreated: () -> Unit) {
    val vm: OgnReviewViewModel = viewModel(factory = vmFactory { OgnReviewViewModel(it.repository, it.settings, it.ognSession) })
    val settings by vm.settings.collectAsStateWithLifecycle(Settings())
    val launchTypes by vm.launchTypes.collectAsStateWithLifecycle(emptyList())
    val items = vm.items

    val created = vm.outcome as? ImportCreateResult.Created
    LaunchedEffect(created) { if (created != null && !created.renumbered) onCreated() }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.ogn_bulk_title), style = MaterialTheme.typography.titleSmall)
            fun <T> common(values: List<T>): T? = values.distinct().singleOrNull()
            PickerField(
                stringResource(R.string.flight_launch_type), common(items.map { it.row.draft.launchTypeId }),
                launchTypes.filter { !it.archived }.map { PickerOption(it.id, launchTypeName(it.id, it.name)) }, vm::setLaunchTypeAll,
            )
            PickerField(
                stringResource(R.string.totals_role), common(items.map { it.row.draft.role.name }),
                Role.entries.map { PickerOption(it.name, it.label()) }, { vm.setRoleAll(Role.valueOf(it)) },
            )
            val clubs = vm.clubsByAirfield.values.flatten().distinctBy { it.id }
            if (clubs.size > 1) {
                PickerField(
                    stringResource(R.string.flight_club), common(items.map { it.row.draft.clubId }),
                    clubs.map { PickerOption(it.id, it.name) }, vm::setClubAll,
                )
            }
        }
        HorizontalDivider()
        if (items.isEmpty()) Text(stringResource(R.string.ogn_review_empty), Modifier.padding(16.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(items, key = { it.row.flightIndex }) { item ->
                FlightRow(item.preview, settings.hoursDisplay, null, onClick = { onOpen(item.row.flightIndex) }) { m ->
                    Text(
                        stringResource(if (item.problems.isEmpty()) R.string.ogn_review_unsaved else R.string.ogn_review_needs_attention),
                        m, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        color = if (item.problems.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                    )
                }
                item.row.stub?.let {
                    Text(
                        stringResource(R.string.ogn_review_new_glider, "${it.registration} ${it.type}".trim()),
                        Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
                    )
                }
                item.problems.forEach {
                    Text(it.text(), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider(Modifier.padding(top = 6.dp))
            }
        }
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (vm.outcome as? ImportCreateResult.Failed)?.let { Text(it.message.text(), color = MaterialTheme.colorScheme.error) }
            if (vm.blocked && items.isNotEmpty()) Text(stringResource(R.string.ogn_review_problems), style = MaterialTheme.typography.bodySmall)
            val numbers = items.map { it.preview.logNo }
            val range = when {
                numbers.isEmpty() -> ""
                numbers.min() == numbers.max() -> stringResource(R.string.ogn_log_range_one, numbers.min())
                else -> stringResource(R.string.ogn_log_range, numbers.min(), numbers.max())
            }
            Button(onClick = { vm.create() }, enabled = !vm.blocked && !vm.creating, modifier = Modifier.fillMaxWidth()) {
                Text(pluralStringResource(R.plurals.ogn_create, items.size, items.size, range))
            }
        }
    }

    vm.earlier?.let { newest ->
        AlertDialog(
            onDismissRequest = vm::cancelEarlier,
            title = { Text(stringResource(R.string.ogn_earlier_title)) },
            text = { Text(stringResource(R.string.ogn_earlier_text, newest.logNo, isoDateShort(newest.date))) },
            confirmButton = { TextButton(onClick = { vm.create(earlierConfirmed = true) }) { Text(stringResource(R.string.ogn_continue)) } },
            dismissButton = { TextButton(onClick = vm::cancelEarlier) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (created != null && created.renumbered) {
        val range = if (created.firstLogNo == created.lastLogNo) stringResource(R.string.ogn_log_range_one, created.firstLogNo)
            else stringResource(R.string.ogn_log_range, created.firstLogNo, created.lastLogNo)
        AlertDialog(
            onDismissRequest = onCreated,
            title = { Text(stringResource(R.string.ogn_renumbered_title)) },
            text = { Text(stringResource(R.string.ogn_renumbered_text, range)) },
            confirmButton = { TextButton(onClick = onCreated) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}
