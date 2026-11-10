package io.github.jgfyyvzss.soarlog

import android.app.Application
import androidx.annotation.VisibleForTesting
import io.github.jgfyyvzss.soarlog.data.BackupFolder
import io.github.jgfyyvzss.soarlog.data.BackupScheduler
import io.github.jgfyyvzss.soarlog.data.BackupService
import io.github.jgfyyvzss.soarlog.data.CsvService
import io.github.jgfyyvzss.soarlog.data.EndorsementImage
import io.github.jgfyyvzss.soarlog.data.EndorsementRepository
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
import io.github.jgfyyvzss.soarlog.data.ogn.OgnSession
import io.github.jgfyyvzss.soarlog.data.ogn.OkHttpFlightbook
import io.github.jgfyyvzss.soarlog.data.weglide.AircraftCatalog
import io.github.jgfyyvzss.soarlog.data.weglide.CredentialStore
import io.github.jgfyyvzss.soarlog.data.weglide.UploadSelection
import io.github.jgfyyvzss.soarlog.data.weglide.OkHttpWeGlideApi
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class SoarLogApp : Application() {
    val database by lazy { testDatabase ?: LogbookDatabase.build(this) }
    val repository by lazy { LogbookRepository(database) }
    val witness by lazy { WitnessRepository(database) }
    val witnessPhoto by lazy { WitnessPhoto(this) }
    val endorsements by lazy { EndorsementRepository(database) }
    val endorsementImage by lazy { EndorsementImage(this) }
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
    /** Received IGC files, kept for a week for logging and uploading; see [IgcLibrary]. */
    val igcFiles by lazy { IgcFiles(IgcLibrary(File(noBackupFilesDir, "igc")), appScope) }
    val igcImport by lazy { IgcImportStore(this, appScope, igcFiles) }
    val loggers by lazy { BluetoothLoggers(this) }
    val weglideApi by lazy { OkHttpWeGlideApi(OkHttpWeGlideApi.userAgent(appVersion())) }
    /** OGN Flightbook, for the OGN import; only the airfield code and the date are ever sent. */
    val flightbook by lazy { OkHttpFlightbook(OkHttpFlightbook.userAgent(appVersion())) }
    /** The OGN search and the days fetched in this session, so Back never asks Flightbook again. */
    val ognSession by lazy { OgnSession(flightbook) }
    /** The WeGlide ID and date of birth, encrypted, outside every backup. */
    val weglideCredentials by lazy { CredentialStore(File(noBackupFilesDir, "weglide-credentials.bin")) }
    val uploadSelection = UploadSelection()
    /** The one unfinished logger download, kept out of Android's Auto Backup. */
    val loggerPartial by lazy { FilePartialDownload(File(noBackupFilesDir, "lxnav.part")) }

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
