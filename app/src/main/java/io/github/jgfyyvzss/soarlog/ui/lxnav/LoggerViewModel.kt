package io.github.jgfyyvzss.soarlog.ui.lxnav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavClient
import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavFailure
import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavFile
import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavProgress
import io.github.jgfyyvzss.soarlog.core.lxnav.PartialDownload
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.data.weglide.IgcFile
import io.github.jgfyyvzss.soarlog.core.lxnav.LxnavTrace
import io.github.jgfyyvzss.soarlog.core.lxnav.BlockSize
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import io.github.jgfyyvzss.soarlog.data.lxnav.BluetoothLoggers
import io.github.jgfyyvzss.soarlog.data.lxnav.LoggerLog
import java.io.File
import kotlinx.coroutines.flow.first
import io.github.jgfyyvzss.soarlog.data.lxnav.BluetoothStatus
import io.github.jgfyyvzss.soarlog.data.lxnav.PairedDevice
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface LoggerState {
    data class Devices(val status: BluetoothStatus) : LoggerState
    data class Connecting(val device: PairedDevice) : LoggerState
    /** The logger's files, newest first. */
    data class Files(val device: PairedDevice, val files: List<LxnavFile>) : LoggerState
    data class Downloading(val device: PairedDevice, val file: LxnavFile, val progress: LxnavProgress, val startedAt: Long) : LoggerState
    data class Failed(val device: PairedDevice?, val message: Msg, val retry: () -> Unit) : LoggerState
}

/**
 * From logger: paired devices, the chosen logger's files, and a download that hands the file to the IGC import.
 * Each step opens its own session, so a dropped link between steps costs nothing.
 */
class LoggerViewModel(
    private val loggers: BluetoothLoggers,
    private val partial: PartialDownload,
    private val log: LoggerLog,
    private val settings: SettingsRepository,
    /** Where a finished download goes: the logbook import, or the WeGlide upload screen that opened this one. */
    private val onDownloaded: suspend (IgcFile) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val _state = MutableStateFlow<LoggerState>(LoggerState.Devices(loggers.status()))
    val state: StateFlow<LoggerState> = _state
    private var job: Job? = null
    private var files: List<LxnavFile> = emptyList()

    /** Re-reads Bluetooth, for example after the pilot returns from the system settings. */
    fun refresh() {
        if (_state.value is LoggerState.Devices) _state.value = LoggerState.Devices(loggers.status())
    }

    fun backToDevices() {
        job?.cancel()
        _state.value = LoggerState.Devices(loggers.status())
    }

    fun open(device: PairedDevice) {
        job?.cancel()
        _state.value = LoggerState.Connecting(device)
        job = viewModelScope.launch {
            attempt(device, retry = { open(device) }) {
                val book = logged(device, "logbook") { trace -> LxnavClient(loggers.connector(device.address), trace).session { logbook() } }
                files = book.sortedByDescending { it.start }
                _state.value = LoggerState.Files(device, files)
            }
        }
    }

    fun download(device: PairedDevice, file: LxnavFile) {
        job?.cancel()
        val started = now()
        _state.value = LoggerState.Downloading(device, file, LxnavProgress(0, null, 0), started)
        job = viewModelScope.launch {
            attempt(device, retry = { download(device, file) }) {
                val size = BlockSize.fromSetting(settings.settings.first().loggerBlockLines)
                val bytes = logged(device, "download") { trace ->
                    LxnavClient(loggers.connector(device.address), trace).session {
                        download(file, partial, size) { p -> _state.value = LoggerState.Downloading(device, file, p, started) }
                    }
                }
                _state.value = LoggerState.Files(device, files)
                onDownloaded(IgcFile(file.name, bytes))
            }
        }
    }

    /** Stops the download and discards what was saved; FILE_CANCEL is sent as the session closes. */
    fun cancelDownload() {
        val s = _state.value as? LoggerState.Downloading ?: return
        job?.cancel()
        partial.delete()
        _state.value = LoggerState.Files(s.device, files)
    }

    /** Runs one session with a communication log while the setting is on, and closes the log however it ends. */
    private suspend fun <T> logged(device: PairedDevice, purpose: String, block: suspend (LxnavTrace) -> T): T {
        if (!settings.settings.first().loggerCommLog) return block(LxnavTrace.NONE)
        val session = withContext(Dispatchers.IO) { log.start(device.name, purpose) }
        try {
            return block(session)
        } finally {
            session.close()
            _latestLog.value = log.latest()
        }
    }

    private val _latestLog = MutableStateFlow(log.latest())
    /** The newest communication log, to share. */
    val latestLog: StateFlow<File?> = _latestLog

    fun setCommLog(on: Boolean) = viewModelScope.launch { settings.setLoggerCommLog(on) }

    fun setBlockLines(lines: Int) = viewModelScope.launch { settings.setLoggerBlockLines(lines) }

    /** The logger's answer to the NMEA rates query, as received; empty when it sent none. Null before a query. */
    private val _rates = MutableStateFlow<List<String>?>(null)
    val rates: StateFlow<List<String>?> = _rates
    private val _querying = MutableStateFlow(false)
    val querying: StateFlow<Boolean> = _querying

    /**
     * Test only: asks the logger for its NMEA output rates with a read query, so item 7 can learn the reply format.
     * Logged like any session while the communication log is on.
     */
    fun queryRates(device: PairedDevice) {
        if (_querying.value) return
        _querying.value = true
        viewModelScope.launch {
            _rates.value = try {
                withContext(Dispatchers.Default) {
                    logged(device, "nmearate") { trace -> LxnavClient(loggers.connector(device.address), trace).session { query(NMEA_RATES) } }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            } finally {
                _querying.value = false
            }
        }
    }

    private companion object {
        const val NMEA_RATES = "PLXV0,NMEARATE,R"
    }

    private suspend fun attempt(device: PairedDevice, retry: () -> Unit, block: suspend () -> Unit) {
        try {
            withContext(Dispatchers.Default) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: LxnavFailure) {
            _state.value = LoggerState.Failed(device, e.shown, retry)
        } catch (e: Exception) {
            _state.value = LoggerState.Failed(device, msg(MsgKey.LXNAV_CONNECT_FAILED), retry)
        }
    }
}
