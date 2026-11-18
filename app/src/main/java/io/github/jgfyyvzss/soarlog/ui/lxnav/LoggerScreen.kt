package io.github.jgfyyvzss.soarlog.ui.lxnav

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.jgfyyvzss.soarlog.R
import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavFile
import io.github.jgfyyvzss.soarlog.data.lxnav.BluetoothStatus
import io.github.jgfyyvzss.soarlog.data.lxnav.PairedDevice
import io.github.jgfyyvzss.soarlog.ui.appContainer
import io.github.jgfyyvzss.soarlog.ui.shortText
import io.github.jgfyyvzss.soarlog.ui.text
import io.github.jgfyyvzss.soarlog.ui.vmFactory
import java.time.ZoneId
import kotlinx.coroutines.delay

/** Files shown before More, since a day's log usually needs only the latest few. */
private const val RECENT_FILES = 3

@Composable
fun LoggerScreen(forUpload: Boolean = false, onDone: () -> Unit = {}) {
    val vm: LoggerViewModel = viewModel(
        factory = vmFactory { app ->
            LoggerViewModel(app.loggers, app.loggerPartial, onDownloaded = { file ->
                // For an upload the file goes back to the upload screen; otherwise into the logbook import.
                if (forUpload) app.uploadSelection.select(app.igcFiles.add(file.name, file.bytes).id)
                else app.igcImport.openDownloaded(file.name, file.bytes)
            })
        },
    )
    // Back to the upload screen once a new file has been downloaded for it.
    val selection = appContainer().uploadSelection
    val onEntry = remember { selection.selected.value }
    val chosen by selection.selected.collectAsStateWithLifecycle()
    LaunchedEffect(chosen) { if (forUpload && chosen != null && chosen != onEntry) onDone() }
    val state by vm.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (val s = state) {
            is LoggerState.Devices -> Devices(s.status, onRefresh = vm::refresh, onOpen = vm::open)
            is LoggerState.Connecting -> Text(stringResource(R.string.logger_connecting, s.device.name))
            is LoggerState.Files -> Files(s, onDownload = { vm.download(s.device, it) }, onMore = { vm.loadMore(s.device) }, onBack = vm::backToDevices)
            is LoggerState.Downloading -> Downloading(s, onCancel = vm::cancelDownload)
            is LoggerState.Failed -> {
                Text(s.message.text(), color = MaterialTheme.colorScheme.error)
                Button(onClick = s.retry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logger_try_again)) }
                OutlinedButton(onClick = vm::backToDevices, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logger_devices)) }
            }
        }
    }
}

@Composable
private fun Devices(status: BluetoothStatus, onRefresh: () -> Unit, onOpen: (PairedDevice) -> Unit) {
    val context = LocalContext.current
    val bluetoothSettings = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
    when (status) {
        BluetoothStatus.Unsupported -> Text(stringResource(R.string.logger_no_bluetooth))
        BluetoothStatus.NeedsPermission -> {
            // Asked for the first time the pilot opens From logger, and again from the button.
            var asked by rememberSaveable { mutableStateOf(false) }
            val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onRefresh() }
            LaunchedEffect(Unit) {
                if (!asked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    asked = true
                    request.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }
            }
            Text(stringResource(R.string.logger_permission))
            OutlinedButton(
                onClick = { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) request.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.logger_allow)) }
            // If the pilot declined twice, Android no longer asks, so offer the app's own settings page.
            TextButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
            }) { Text(stringResource(R.string.logger_app_settings)) }
        }
        BluetoothStatus.Off -> {
            Text(stringResource(R.string.logger_bluetooth_off))
            OutlinedButton(onClick = bluetoothSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logger_bluetooth_settings)) }
        }
        is BluetoothStatus.Ready -> {
            if (status.devices.isEmpty()) {
                Text(stringResource(R.string.logger_none_paired))
            } else {
                Text(stringResource(R.string.logger_choose), style = MaterialTheme.typography.titleMedium)
                status.devices.forEach { d ->
                    Text(
                        d.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onOpen(d) }.padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
                Text(stringResource(R.string.logger_pair_hint), style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = bluetoothSettings, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logger_bluetooth_settings)) }
        }
    }
}

@Composable
private fun Files(s: LoggerState.Files, onDownload: (LxnavFile) -> Unit, onMore: () -> Unit, onBack: () -> Unit) {
    var showAll by rememberSaveable { mutableStateOf(false) }
    Text(s.device.name, style = MaterialTheme.typography.titleMedium)
    if (s.files.isEmpty() && s.unread == 0) Text(stringResource(R.string.logger_no_files))
    val shown = if (showAll) s.files else s.files.take(RECENT_FILES)
    shown.forEach { f ->
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onDownload(f) }.padding(vertical = 8.dp)) {
            Text(whenText(f), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.logger_file_detail, f.name, sizeText(f.sizeBytes), clock(f.estimatedSeconds)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()
    }
    // More first shows the rest of the flights already read, then reads the next page of older ones from the logger.
    val hidden = s.files.size - shown.size
    if (s.loadingMore) {
        Text(stringResource(R.string.logger_connecting, s.device.name), style = MaterialTheme.typography.bodySmall)
    } else if (hidden + s.unread > 0) {
        TextButton(onClick = { if (hidden > 0) showAll = true else onMore() }) {
            Text(stringResource(R.string.logger_more, hidden + s.unread))
        }
    }
    Text(stringResource(R.string.logger_tap_to_download), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logger_devices)) }
}

@Composable
private fun Downloading(s: LoggerState.Downloading, onCancel: () -> Unit) {
    // A large file takes minutes, so the screen stays on and the progress keeps moving.
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val p = s.progress
    val elapsed = ((now - s.startedAt) / 1000).coerceAtLeast(0)
    val total = p.totalLines
    val fresh = p.lines - p.resumedFrom
    // Measured from this download once it has run a few seconds, otherwise from the logbook size.
    val left = when {
        total != null && fresh > 0 && elapsed >= 3 -> (total - p.lines) * elapsed / fresh
        total != null && total > 0 -> s.file.estimatedSeconds * (total - p.lines) / total
        else -> s.file.estimatedSeconds
    }

    Text(whenText(s.file), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.logger_downloading, s.file.name, sizeText(s.file.sizeBytes)))
    if (total == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(progress = { if (total == 0) 0f else p.lines.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        Text(stringResource(R.string.logger_lines, p.lines, total))
    }
    if (p.resumedFrom > 0) Text(stringResource(R.string.logger_resumed, p.resumedFrom), style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.logger_time, clock(elapsed), clock(left)))
    Text(stringResource(R.string.logger_keep_close), style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.logger_cancel)) }
}

/** The flight's local date and start time; the logbook gives them in UTC. */
@Composable
private fun whenText(f: LxnavFile): String {
    val start = f.start?.atZone(ZoneId.systemDefault()) ?: return f.name
    return stringResource(R.string.logger_file_when, start.toLocalDate().shortText(), start.toLocalTime().withSecond(0).withNano(0).toString())
}

@Composable
private fun sizeText(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** Minutes and seconds, for example 5:22. */
private fun clock(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)
