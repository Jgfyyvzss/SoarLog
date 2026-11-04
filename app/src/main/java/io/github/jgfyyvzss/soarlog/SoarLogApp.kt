package io.github.jgfyyvzss.soarlog

import android.app.Application
import androidx.annotation.VisibleForTesting
import io.github.jgfyyvzss.soarlog.data.BackupFolder
import io.github.jgfyyvzss.soarlog.data.BackupScheduler
import io.github.jgfyyvzss.soarlog.data.BackupService
import io.github.jgfyyvzss.soarlog.data.CsvService
import io.github.jgfyyvzss.soarlog.data.FilterStore
import io.github.jgfyyvzss.soarlog.core.igc.IgcLibrary
import io.github.jgfyyvzss.soarlog.data.IgcFiles
import io.github.jgfyyvzss.soarlog.data.IgcImportStore
import io.github.jgfyyvzss.soarlog.data.LogbookDatabase
import io.github.jgfyyvzss.soarlog.data.LogbookRepository
import io.github.jgfyyvzss.soarlog.data.SettingsRepository
import io.github.jgfyyvzss.soarlog.data.TotalsService
import io.github.jgfyyvzss.soarlog.data.WitnessPhoto
import io.github.jgfyyvzss.soarlog.data.WitnessRepository
import io.github.jgfyyvzss.soarlog.core.lxnav.FilePartialDownload
import io.github.jgfyyvzss.soarlog.data.lxnav.BluetoothLoggers
import io.github.jgfyyvzss.soarlog.data.lxnav.LoggerLog
import io.github.jgfyyvzss.soarlog.data.weglide.AircraftCatalog
import io.github.jgfyyvzss.soarlog.data.weglide.CredentialStore
import io.github.jgfyyvzss.soarlog.data.weglide.UploadSelection
import io.github.jgfyyvzss.soarlog.data.weglide.OkHttpWeGlideApi
import android.os.Environment
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SoarLogApp : Application() {
    val database by lazy { testDatabase ?: LogbookDatabase.build(this) }
    val repository by lazy { LogbookRepository(database) }
    val witness by lazy { WitnessRepository(database) }
    val witnessPhoto by lazy { WitnessPhoto(this) }
    val settings by lazy { SettingsRepository(this) }
    val filter = FilterStore()
    val totals by lazy { TotalsService(repository, settings) }
    val backup by lazy { BackupService(database, settings, appVersion()) }
    val backupFolder by lazy { BackupFolder(this, backup, settings) }
    val aircraft by lazy {
        AircraftCatalog(
            savedFile = File(filesDir, "weglide-aircraft.json"),
            bundled = { assets.open("weglide-aircraft.json").use { it.readBytes().decodeToString() } },
            api = weglideApi,
        )
    }
    val csv by lazy { CsvService(database, aircraftName = { id -> aircraft.find(id)?.name }) }
    /**
     * Received IGC files, kept for a week for logging and uploading; see [IgcLibrary]. The files go in
     * `Android/media/<package>`, where file managers can see and copy them; their details stay in the no-backup
     * folder. Files stored the old way, in the no-backup `igc` folder, are moved across at start-up.
     */
    val igcFiles by lazy {
        val details = File(noBackupFilesDir, "igc-details")
        val library = IgcLibrary(sharedMediaDir() ?: File(noBackupFilesDir, "igc-files"), details)
        IgcFiles(library, appScope, moveFrom = File(noBackupFilesDir, "igc"))
    }

    /**
     * The app's folder in `Android/media`, or null when shared storage is not available. `getExternalMediaDirs` is
     * deprecated from API 30 in favour of MediaStore, but still works with no permission (Android reference, checked
     * 2026-10-28), and unlike MediaStore it needs none below API 29 either.
     */
    @Suppress("DEPRECATION")
    private fun sharedMediaDir(): File? = externalMediaDirs.firstOrNull { d ->
        d != null && Environment.getExternalStorageState(d) == Environment.MEDIA_MOUNTED && (d.isDirectory || d.mkdirs())
    }
    val igcImport by lazy { IgcImportStore(this, appScope, igcFiles) }
    val loggers by lazy { BluetoothLoggers(this) }
    val weglideApi by lazy { OkHttpWeGlideApi(OkHttpWeGlideApi.userAgent(appVersion())) }
    /** The WeGlide ID and date of birth, encrypted, outside every backup. */
    val weglideCredentials by lazy { CredentialStore(File(noBackupFilesDir, "weglide-credentials.bin")) }
    val uploadSelection = UploadSelection()
    /** The one unfinished logger download, kept out of Android's Auto Backup. */
    val loggerPartial by lazy { FilePartialDownload(File(noBackupFilesDir, "lxnav.part")) }
    /**
     * Logger communication logs, in `Android/media/<package>/logs` beside the received IGC files, so a file manager
     * can reach them; in the cache when shared storage is unavailable. Logs from the old cache folder move across.
     */
    val loggerLog by lazy {
        val oldDir = File(cacheDir, "logger-logs")
        val shared = sharedMediaDir()?.let { File(it, "logs") }
        LoggerLog(shared ?: oldDir).also { log ->
            if (shared != null) appScope.launch(Dispatchers.IO) { runCatching { log.moveFrom(oldDir) } }
        }
    }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var schedulerStarted = false

    /**
     * Starts watching for changes that make an automatic backup due. Called from the first screen rather than
     * onCreate, so UI tests can supply their database first.
     */
    fun startBackupScheduler() {
        if (schedulerStarted || testDatabase != null) return
        schedulerStarted = true
        BackupScheduler(this, database, backup).start(appScope)
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: ""

    companion object {
        /** Set by UI tests before the first screen opens, so they use an in-memory database. */
        @VisibleForTesting
        var testDatabase: LogbookDatabase? = null
    }
}
