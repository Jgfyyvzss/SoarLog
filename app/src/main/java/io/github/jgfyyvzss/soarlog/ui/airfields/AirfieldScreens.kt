package io.github.jgfyyvzss.soarlog.ui.airfields

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.ui.UiText
import io.github.jgfyyvzss.soarlog.ui.launchTypeName
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.uiText
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.data.ClubEntity
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.SaveResult
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import androidx.compose.material3.Checkbox
import io.github.jgfyyvzss.soarlog.ui.Badge
import io.github.jgfyyvzss.soarlog.ui.PickerField
import io.github.jgfyyvzss.soarlog.ui.PickerOption
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import io.github.jgfyyvzss.soarlog.core.Keys
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The picker option meaning no default. */
private const val NONE = ""

/** The default IGC match radius, from Appendix D. */
const val DEFAULT_MATCH_RADIUS_M = 1000

@Composable
fun AirfieldListScreen(onOpen: (String) -> Unit, onNew: () -> Unit) {
    val app = appContainer()
    val rows by app.repository.observeAirfieldList().collectAsStateWithLifecycle(emptyList())
    val settings by app.settings.settings.collectAsStateWithLifecycle(Settings())
    Box(Modifier.fillMaxSize()) {
        if (rows.isEmpty()) Text(stringResource(R.string.airfields_none_yet), Modifier.padding(16.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(rows, key = { it.id }) { a ->
                ListItem(
                    headlineContent = { Text(a.name) },
                    supportingContent = { a.clubNames?.let { Text(it) } },
                    trailingContent = {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (a.id == settings.defaultAirfieldId && !a.archived) Badge(stringResource(R.string.gliders_default_badge))
                            if (a.incomplete) Badge(stringResource(R.string.common_incomplete))
                            if (a.archived) Badge(stringResource(R.string.common_archived))
                        }
                    },
                    modifier = Modifier.clickable { onOpen(a.id) },
                )
                HorizontalDivider()
            }
        }
        FloatingActionButton(onClick = onNew, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.title_new_airfield))
        }
    }
}

class AirfieldEditViewModel(private val repo: LogbookRepository, private val settings: SettingsRepository, private val id: String?) : ViewModel() {
    /** Whether new hand-entered flights start at this airfield. */
    var isDefault by mutableStateOf(false)
    var name by mutableStateOf("")
    var latitude by mutableStateOf("")
    var longitude by mutableStateOf("")
    var elevation by mutableStateOf("")
    var radius by mutableStateOf("")
    var archived by mutableStateOf(false)
    val clubs = mutableStateListOf<ClubEntity>()
    var defaultClubId by mutableStateOf<String?>(null)
    var defaultLaunchTypeId by mutableStateOf<String?>(null)
    var error by mutableStateOf<UiText?>(null)
    var loaded by mutableStateOf(id == null)
        private set
    var done by mutableStateOf(false)
        private set

    val allClubs = repo.observeClubs()
    val launchTypes = repo.observeLaunchTypes()

    init {
        if (id != null) viewModelScope.launch {
            repo.airfield(id)?.let { a ->
                name = a.name
                latitude = a.latitude?.toString() ?: ""
                longitude = a.longitude?.toString() ?: ""
                elevation = a.elevationM?.toString() ?: ""
                radius = a.matchRadiusM?.toString() ?: ""
                archived = a.archived
                defaultClubId = a.defaultClubId
                defaultLaunchTypeId = a.defaultLaunchTypeId
                clubs.addAll(repo.clubsFor(id))
            }
            isDefault = settings.settings.first().defaultAirfieldId == id
            loaded = true
        }
    }

    fun addClub(club: ClubEntity) {
        if (clubs.none { it.id == club.id }) clubs.add(club)
    }

    fun removeClub(club: ClubEntity) {
        clubs.remove(club)
        if (defaultClubId == club.id) defaultClubId = null
    }

    /** Creates a club by name, or reuses the existing one with that name, and links it. */
    fun addNewClub(clubName: String) = viewModelScope.launch {
        when (val r = repo.saveClub(null, clubName)) {
            is SaveResult.Saved -> repo.observeClubsOnce().firstOrNull { it.id == r.id }?.let(::addClub)
            is SaveResult.Rejected -> {
                val existing = repo.observeClubsOnce().firstOrNull {
                    Keys.nameKey(it.name) == Keys.nameKey(clubName)
                }
                if (existing != null) addClub(existing) else error = UiText.Message(r.message)
            }
        }
    }

    fun save() = viewModelScope.launch {
        fun parseDouble(s: String) = s.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        fun parseInt(s: String) = s.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
        if (latitude.isNotBlank() && parseDouble(latitude) == null) { error = uiText(R.string.airfield_bad_latitude); return@launch }
        if (longitude.isNotBlank() && parseDouble(longitude) == null) { error = uiText(R.string.airfield_bad_longitude); return@launch }
        if (elevation.isNotBlank() && parseInt(elevation) == null) { error = uiText(R.string.airfield_bad_elevation); return@launch }
        if (radius.isNotBlank() && parseInt(radius) == null) { error = uiText(R.string.airfield_bad_radius); return@launch }
        val r = repo.saveAirfield(
            id, name, parseDouble(latitude), parseDouble(longitude), parseInt(elevation), parseInt(radius),
            clubs.map { it.id }, archived, defaultClubId, defaultLaunchTypeId,
        )
        when (r) {
            is SaveResult.Saved -> {
                // Ticking the default moves it here from any other airfield; an archived airfield is never the default.
                val current = settings.settings.first().defaultAirfieldId
                when {
                    isDefault && !archived -> settings.setDefaultAirfield(r.id)
                    current == r.id -> settings.setDefaultAirfield(null)
                }
                done = true
            }
            is SaveResult.Rejected -> error = UiText.Message(r.message)
        }
    }
}

private suspend fun LogbookRepository.observeClubsOnce(): List<ClubEntity> =
    observeClubs().first()

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AirfieldEditScreen(id: String?, onDone: () -> Unit) {
    val vm: AirfieldEditViewModel = viewModel(factory = vmFactory { AirfieldEditViewModel(it.repository, it.settings, id) })
    LaunchedEffect(vm.done) { if (vm.done) onDone() }
    val allClubs by vm.allClubs.collectAsStateWithLifecycle(emptyList())
    val launchTypes by vm.launchTypes.collectAsStateWithLifecycle(emptyList())
    var newClubOpen by rememberSaveable { mutableStateOf(false) }
    if (!vm.loaded) return

    val decimal = KeyboardOptions(keyboardType = KeyboardType.Text)
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(vm.name, { vm.name = it; vm.error = null }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.latitude, { vm.latitude = it }, label = { Text(stringResource(R.string.airfield_latitude)) }, singleLine = true,
                keyboardOptions = decimal, modifier = Modifier.weight(1f))
            OutlinedTextField(vm.longitude, { vm.longitude = it }, label = { Text(stringResource(R.string.airfield_longitude)) }, singleLine = true,
                keyboardOptions = decimal, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.airfield_coordinates_hint), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(vm.elevation, { vm.elevation = it }, label = { Text(stringResource(R.string.airfield_elevation)) }, singleLine = true,
                keyboardOptions = decimal, modifier = Modifier.weight(1f))
            OutlinedTextField(vm.radius, { vm.radius = it }, label = { Text(stringResource(R.string.airfield_radius)) }, singleLine = true,
                placeholder = { Text("$DEFAULT_MATCH_RADIUS_M") }, keyboardOptions = decimal, modifier = Modifier.weight(1f))
        }

        Text(stringResource(R.string.title_clubs), style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            vm.clubs.forEach { c ->
                InputChip(
                    selected = false,
                    onClick = { vm.removeClub(c) },
                    label = { Text(c.name) },
                    trailingIcon = { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.airfield_remove_club, c.name)) },
                )
            }
        }
        val available = allClubs.filter { c -> vm.clubs.none { it.id == c.id } }
        PickerField(
            label = stringResource(R.string.airfield_add_club),
            selectedId = null,
            options = available.map { PickerOption(it.id, it.name) },
            onPick = { picked -> available.firstOrNull { it.id == picked }?.let(vm::addClub) },
        )
        OutlinedButton(onClick = { newClubOpen = true }) { Text(stringResource(R.string.clubs_new)) }

        Text(stringResource(R.string.airfield_defaults), style = MaterialTheme.typography.titleSmall)
        if (vm.clubs.size > 1) {
            PickerField(
                label = stringResource(R.string.airfield_default_club),
                selectedId = vm.defaultClubId ?: NONE,
                options = listOf(PickerOption(NONE, stringResource(R.string.common_none))) + vm.clubs.map { PickerOption(it.id, it.name) },
                onPick = { vm.defaultClubId = it.takeIf { id -> id != NONE } },
            )
        }
        PickerField(
            label = stringResource(R.string.airfield_default_launch_type),
            selectedId = vm.defaultLaunchTypeId ?: NONE,
            options = listOf(PickerOption(NONE, stringResource(R.string.common_none))) +
                launchTypes.filter { !it.archived || it.id == vm.defaultLaunchTypeId }.map { PickerOption(it.id, launchTypeName(it.id, it.name)) },
            onPick = { vm.defaultLaunchTypeId = it.takeIf { id -> id != NONE } },
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.airfield_default), Modifier.weight(1f))
            Checkbox(checked = vm.isDefault && !vm.archived, onCheckedChange = { vm.isDefault = it }, enabled = !vm.archived)
        }
        Text(stringResource(R.string.airfield_default_hint), style = MaterialTheme.typography.bodySmall)

        if (id != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.common_archived), Modifier.weight(1f))
                Switch(checked = vm.archived, onCheckedChange = { vm.archived = it })
            }
        }
        vm.error?.let { Text(it.text(), color = MaterialTheme.colorScheme.error) }
        Button(onClick = { vm.save() }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.common_save)) }
    }

    if (newClubOpen) {
        NameDialog(title = stringResource(R.string.clubs_new), initial = "", onDismiss = { newClubOpen = false }) {
            vm.addNewClub(it)
            newClubOpen = false
        }
    }
}

/** A dialog asking for one name. */
@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, extra: @Composable () -> Unit = {}, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true)
                extra()
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
