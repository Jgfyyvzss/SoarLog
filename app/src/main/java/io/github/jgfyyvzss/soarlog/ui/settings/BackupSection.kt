package io.github.jgfyyvzss.soarlog.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.SoarLogApp
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.backup.BackupTime
import io.github.jgfyyvzss.soarlog.core.backup.WitnessSummary
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.data.BackupOutcome
import io.github.jgfyyvzss.soarlog.data.Prepared
import io.github.jgfyyvzss.soarlog.data.Settings
import io.github.jgfyyvzss.soarlog.data.close
import io.github.jgfyyvzss.soarlog.ui.UiText
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.uiText
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Backup folder, schedule, Back up now, Restore and Merge. Also drives the first-run Restore. */
class BackupViewModel(private val app: SoarLogApp) : ViewModel() {
    val settings = app.settings.settings
    var folderName by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<UiText?>(null)
    var busy by mutableStateOf(false)
        private set
    /** A checked file waiting for the pilot to confirm, or the reasons it was refused. */
    var pending by mutableStateOf<Prepared?>(null)
        private set
    var restored by mutableStateOf(false)
        private set

    fun loadFolderName(uri: String?) = viewModelScope.launch { folderName = app.backupFolder.folderName(uri) }

    fun chooseFolder(tree: Uri) = viewModelScope.launch {
        runCatching { app.backupFolder.choose(tree) }
            .onSuccess { message = uiText(R.string.backup_folder_set) }
            .onFailure { message = uiText(R.string.backup_folder_unusable) }
    }

    fun setSchedule(afterChanges: Int, afterDays: Int) = viewModelScope.launch { app.settings.setBackupSchedule(afterChanges, afterDays) }

    fun setImages(on: Boolean) = viewModelScope.launch { app.settings.setBackupImages(on) }

    fun backUpNow() = run {
        busy = true
        viewModelScope.launch {
            message = when (val r = app.backupFolder.backUpNow()) {
                is BackupOutcome.Written -> uiText(R.string.backup_written, r.fileName)
                BackupOutcome.NoFolder -> uiText(R.string.backup_no_folder)
                is BackupOutcome.Failed -> uiText(R.string.backup_failed, r.message)
            }
            busy = false
        }
    }

    fun openForRestore(uri: Uri) = open(uri) { app.backup.prepareRestore(it) }
    fun openForMerge(uri: Uri) = open(uri) { app.backup.prepareMerge(it) }

    /** The picked file is copied to the cache first, because a zip is read by seeking and a picked file may not allow it. */
    private val openedCopy get() = File(app.cacheDir, "backup-opened.zip")

    private fun open(uri: Uri, prepare: suspend (File) -> Prepared) = run {
        busy = true
        viewModelScope.launch {
            val copy = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)?.use { input -> openedCopy.outputStream().use { input.copyTo(it) } }
                        ?.let { openedCopy }
                }.getOrNull()
            }
            pending = if (copy == null) Prepared.Refused(listOf(msg(MsgKey.BACKUP_FILE_UNREADABLE))) else prepare(copy)
            if (pending is Prepared.Refused) release(null)
            busy = false
        }
    }

    /** Closes the opened backup, if any, and deletes its copy. */
    private fun release(p: Prepared?) {
        p?.close()
        openedCopy.delete()
    }

    fun confirm() {
        val p = pending ?: return
        pending = null
        busy = true
        viewModelScope.launch {
            runCatching {
                when (p) {
                    is Prepared.Restore -> {
                        app.backup.restore(p); app.settings.setFirstRunDone(); restored = true
                        uiText(R.string.backup_restored)
                    }
                    is Prepared.Merge -> { app.backup.merge(p); uiText(R.string.backup_merged) }
                    is Prepared.Refused -> null
                }
            }.onSuccess { message = it }
                .onFailure { message = uiText(R.string.common_nothing_changed, it.message ?: it.javaClass.simpleName) }
            release(p)
            busy = false
        }
    }

    fun dismiss() {
        release(pending)
        pending = null
    }

    override fun onCleared() = release(pending)
}

/** What the Restore and Merge pickers offer. Old .json backups stay pickable, so they get the way back. */
private val backupTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "text/plain")

@Composable
fun backupViewModel(): BackupViewModel = viewModel(factory = vmFactory { BackupViewModel(it) })

@Composable
fun BackupSection(vm: BackupViewModel = backupViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle(Settings())
    LaunchedEffect(settings.backupFolderUri) { vm.loadFolderName(settings.backupFolderUri) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::chooseFolder) }
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openForRestore) }
    val pickMerge = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openForMerge) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.backup_folder))
        Text(
            when {
                settings.backupFolderUri == null -> stringResource(R.string.backup_folder_not_set)
                vm.folderName == null -> stringResource(R.string.backup_folder_lost)
                else -> vm.folderName!!
            },
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_choose_folder)) }
        Text(stringResource(R.string.backup_last, lastBackupText(settings.lastBackupAt)), style = MaterialTheme.typography.bodyMedium)

        Text(stringResource(R.string.backup_after), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            NumberField(stringResource(R.string.backup_changes), settings.backupAfterChanges, Modifier.weight(1f)) {
                vm.setSchedule(it, settings.backupAfterDays)
            }
            NumberField(stringResource(R.string.backup_days), settings.backupAfterDays, Modifier.weight(1f)) {
                vm.setSchedule(settings.backupAfterChanges, it)
            }
        }
        Text(
            stringResource(
                if (settings.backupAfterChanges == 0 && settings.backupAfterDays == 0) R.string.backup_auto_off else R.string.backup_auto_hint,
            ),
            style = MaterialTheme.typography.bodySmall,
        )

        // Shown always: endorsement images exist whether or not Witness sign-off is on.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.backup_images))
                Text(stringResource(R.string.backup_images_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.backupImages, onCheckedChange = { vm.setImages(it) })
        }

        Button(onClick = vm::backUpNow, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.backup_now)) }
        // One per row: side by side, longer translations such as Wiederherstellen do not fit half the width.
        OutlinedButton(onClick = { pickRestore.launch(backupTypes) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_restore))
        }
        OutlinedButton(onClick = { pickMerge.launch(backupTypes) }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.backup_merge))
        }
        Text(stringResource(R.string.backup_restore_merge_hint), style = MaterialTheme.typography.bodySmall)
        vm.message?.let { Text(it.text(), style = MaterialTheme.typography.bodyMedium) }
    }

    PreparedDialog(vm)
}

@Composable
private fun NumberField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    var text by rememberSaveable(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        text,
        { t ->
            text = t.filter(Char::isDigit).take(4)
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** Confirms a Restore or Merge, or lists why a file was refused. */
@Composable
fun PreparedDialog(vm: BackupViewModel) {
    when (val p = vm.pending) {
        null -> Unit
        is Prepared.Refused -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(stringResource(R.string.backup_nothing_changed)) },
            text = {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(p.problems.take(200)) { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
                    if (p.problems.size > 200) item { Text(stringResource(R.string.backup_more_problems, p.problems.size - 200)) }
                }
            },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.common_ok)) } },
        )
        is Prepared.Restore -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(stringResource(R.string.backup_restore_title)) },
            text = {
                val c = p.contents
                WithWitness(p.witness) {
                    Text(
                        stringResource(
                            R.string.backup_restore_text, isoTimeText(c.exportedAt),
                            pluralStringResource(R.plurals.count_flights, c.flights, c.flights),
                            pluralStringResource(R.plurals.count_gliders, c.gliders, c.gliders),
                            pluralStringResource(R.plurals.count_airfields, c.airfields, c.airfields),
                            pluralStringResource(R.plurals.count_clubs, c.clubs, c.clubs),
                        ),
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::confirm) { Text(stringResource(R.string.backup_restore)) } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
        is Prepared.Merge -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(stringResource(R.string.backup_merge_title)) },
            text = {
                WithWitness(p.witness) {
                    Text(
                        stringResource(
                            R.string.backup_merge_text, isoTimeText(p.contents.exportedAt),
                            pluralStringResource(R.plurals.count_flights, p.contents.flights, p.contents.flights),
                            p.summary.added, p.summary.updated, p.summary.unchanged,
                        ),
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::confirm) { Text(stringResource(R.string.backup_merge)) } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

/** A dialog's text followed by the witness seal summary and anything repaired, when the file has witness data. */
@Composable
private fun WithWitness(w: WitnessSummary, content: @Composable () -> Unit) {
    if (w.isEmpty) return content()
    LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { content() }
        if (w.verified + w.changed + w.cannotVerify > 0) {
            item { Text(stringResource(R.string.backup_witness_summary, w.verified, w.changed, w.cannotVerify)) }
        }
        items(w.notes.take(200)) { Text(it.text(), style = MaterialTheme.typography.bodySmall) }
        if (w.notes.size > 200) item { Text(stringResource(R.string.backup_more_problems, w.notes.size - 200)) }
    }
}

/** Shown on an empty logbook the first time the app opens. */
@Composable
fun FirstRunScreen(onDone: () -> Unit) {
    val vm = backupViewModel()
    val pickRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openForRestore) }
    val scope = rememberCoroutineScope()
    val container = appContainer()
    LaunchedEffect(vm.restored) { if (vm.restored) onDone() }

    // This screen is shown outside the main Scaffold, so the Surface supplies the themed background and text colour.
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Text(stringResource(R.string.first_run_welcome), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.first_run_text))
            Button(onClick = {
                scope.launch { container.settings.setFirstRunDone(); onDone() }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.first_run_fresh)) }
            OutlinedButton(
                onClick = { pickRestore.launch(backupTypes) },
                enabled = !vm.busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.first_run_restore)) }
            vm.message?.let { Text(it.text()) }
        }
    }
    PreparedDialog(vm)
}

/** The date and time of the last backup in the device's format, or "never". */
@Composable
fun lastBackupText(at: Long?): String =
    at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)) }
        ?: stringResource(R.string.common_never)

@Composable
private fun isoTimeText(iso: String): String =
    BackupTime.fromIso(iso)?.let { lastBackupText(it) } ?: stringResource(R.string.backup_unknown_date)
