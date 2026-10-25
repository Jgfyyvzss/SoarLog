package io.github.jgfyyvzss.soarlog.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.jgfyyvzss.soarlog.core.HoursDisplay
import io.github.jgfyyvzss.soarlog.core.backup.BackupIdentifier
import io.github.jgfyyvzss.soarlog.core.backup.BackupSchedule
import io.github.jgfyyvzss.soarlog.core.backup.BackupSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class PilotIdentifier(val label: String, val value: String)

data class Settings(
    // Backed up with the logbook.
    val pilotName: String = "",
    val pilotIdentifiers: List<PilotIdentifier> = emptyList(),
    val recordLandingPlace: Boolean = false,
    val recordTimes: Boolean = false,
    val hoursDisplay: HoursDisplay = HoursDisplay.HM,
    /** Whether the Totals PIC row also counts Instructor and Coach flights. */
    val instructionCountsAsPic: Boolean = false,
    /** Back up automatically after this many changes; 0 is off. */
    val backupAfterChanges: Int = BackupSchedule.DEFAULT_AFTER_CHANGES,
    /** Back up automatically after this many days with changes; 0 is off. */
    val backupAfterDays: Int = BackupSchedule.DEFAULT_AFTER_DAYS,
    /** Shows the witness sign-off screens; off hides them and keeps any data. Backed up from format version 2. */
    val witnessSignoff: Boolean = false,
    /** The glider a new flight entered by hand starts with; never used by IGC or CSV import. */
    val defaultGliderId: String? = null,
    /** The airfield a new flight entered by hand starts with, which also brings its default club and launch type. */
    val defaultAirfieldId: String? = null,
    // Specific to this device, never backed up.
    /** Whether backups to the pilot's folder carry the witness photos. On by default. */
    val backupWitnessPhotos: Boolean = true,
    val backupFolderUri: String? = null,
    val lastBackupAt: Long? = null,
    val firstRunDone: Boolean = false,
) {
    fun toBackup() = BackupSettings(
        hoursDisplay = hoursDisplay.name,
        instructionCountsAsPic = instructionCountsAsPic,
        recordLandingPlace = recordLandingPlace,
        recordTimes = recordTimes,
        backupAfterChanges = backupAfterChanges,
        backupAfterDays = backupAfterDays,
        pilotName = pilotName,
        pilotIdentifiers = pilotIdentifiers.map { BackupIdentifier(it.label, it.value) },
        witnessSignoff = witnessSignoff,
        defaultGliderId = defaultGliderId,
        defaultAirfieldId = defaultAirfieldId,
    )
}

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.settingsStore
    private val json = Json { ignoreUnknownKeys = true }
    private val identifierList = ListSerializer(PilotIdentifier.serializer())

    private object K {
        val pilotName = stringPreferencesKey("pilot_name")
        val pilotIdentifiers = stringPreferencesKey("pilot_identifiers")
        val recordLandingPlace = booleanPreferencesKey("record_landing_place")
        val recordTimes = booleanPreferencesKey("record_times")
        val hoursDisplay = stringPreferencesKey("hours_display")
        val instructionCountsAsPic = booleanPreferencesKey("instruction_counts_as_pic")
        val backupAfterChanges = intPreferencesKey("backup_after_changes")
        val backupAfterDays = intPreferencesKey("backup_after_days")
        val witnessSignoff = booleanPreferencesKey("witness_signoff")
        val defaultGliderId = stringPreferencesKey("default_glider_id")
        val defaultAirfieldId = stringPreferencesKey("default_airfield_id")
        val backupWitnessPhotos = booleanPreferencesKey("backup_witness_photos")
        val backupFolderUri = stringPreferencesKey("backup_folder_uri")
        val lastBackupAt = longPreferencesKey("last_backup_at")
        val firstRunDone = booleanPreferencesKey("first_run_done")
    }

    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    private fun Preferences.toSettings() = Settings(
        pilotName = this[K.pilotName] ?: "",
        pilotIdentifiers = this[K.pilotIdentifiers]
            ?.let { runCatching { json.decodeFromString(identifierList, it) }.getOrNull() }
            ?: emptyList(),
        recordLandingPlace = this[K.recordLandingPlace] ?: false,
        recordTimes = this[K.recordTimes] ?: false,
        hoursDisplay = HoursDisplay.entries.firstOrNull { it.name == this[K.hoursDisplay] } ?: HoursDisplay.HM,
        instructionCountsAsPic = this[K.instructionCountsAsPic] ?: false,
        backupAfterChanges = this[K.backupAfterChanges] ?: BackupSchedule.DEFAULT_AFTER_CHANGES,
        backupAfterDays = this[K.backupAfterDays] ?: BackupSchedule.DEFAULT_AFTER_DAYS,
        witnessSignoff = this[K.witnessSignoff] ?: false,
        defaultGliderId = this[K.defaultGliderId],
        defaultAirfieldId = this[K.defaultAirfieldId],
        backupWitnessPhotos = this[K.backupWitnessPhotos] ?: true,
        backupFolderUri = this[K.backupFolderUri],
        lastBackupAt = this[K.lastBackupAt],
        firstRunDone = this[K.firstRunDone] ?: false,
    )

    private fun encodeIdentifiers(identifiers: List<PilotIdentifier>) = json.encodeToString(
        identifierList,
        identifiers.map { id -> PilotIdentifier(id.label.trim(), id.value.trim()) }
            .filter { id -> id.label.isNotEmpty() || id.value.isNotEmpty() },
    )

    /** The profile changes only through this call, made from the Settings screen, and through Restore. */
    suspend fun setPilotProfile(name: String, identifiers: List<PilotIdentifier>) {
        store.edit {
            it[K.pilotName] = name.trim()
            it[K.pilotIdentifiers] = encodeIdentifiers(identifiers)
        }
    }

    suspend fun setRecordLandingPlace(on: Boolean) {
        store.edit { it[K.recordLandingPlace] = on }
    }

    suspend fun setRecordTimes(on: Boolean) {
        store.edit { it[K.recordTimes] = on }
    }

    suspend fun setHoursDisplay(display: HoursDisplay) {
        store.edit { it[K.hoursDisplay] = display.name }
    }

    suspend fun setInstructionCountsAsPic(on: Boolean) {
        store.edit { it[K.instructionCountsAsPic] = on }
    }

    suspend fun setWitnessSignoff(on: Boolean) {
        store.edit { it[K.witnessSignoff] = on }
    }

    /** One glider at most: setting a new one replaces the old, and null clears it. */
    suspend fun setDefaultGlider(id: String?) {
        store.edit { if (id == null) it.remove(K.defaultGliderId) else it[K.defaultGliderId] = id }
    }

    /** One airfield at most, as for the glider. */
    suspend fun setDefaultAirfield(id: String?) {
        store.edit { if (id == null) it.remove(K.defaultAirfieldId) else it[K.defaultAirfieldId] = id }
    }

    suspend fun setBackupWitnessPhotos(on: Boolean) {
        store.edit { it[K.backupWitnessPhotos] = on }
    }

    suspend fun setBackupSchedule(afterChanges: Int, afterDays: Int) {
        store.edit {
            it[K.backupAfterChanges] = afterChanges.coerceAtLeast(0)
            it[K.backupAfterDays] = afterDays.coerceAtLeast(0)
        }
    }

    suspend fun setBackupFolder(uri: String?) {
        store.edit { if (uri == null) it.remove(K.backupFolderUri) else it[K.backupFolderUri] = uri }
    }

    suspend fun setLastBackupAt(time: Long) {
        store.edit { it[K.lastBackupAt] = time }
    }

    suspend fun setFirstRunDone() {
        store.edit { it[K.firstRunDone] = true }
    }

    /** Restore only: replaces every backed-up setting, including the pilot profile. */
    suspend fun applyBackup(s: BackupSettings) {
        store.edit {
            it[K.hoursDisplay] = HoursDisplay.entries.firstOrNull { d -> d.name == s.hoursDisplay }?.name ?: HoursDisplay.HM.name
            it[K.instructionCountsAsPic] = s.instructionCountsAsPic
            it[K.recordLandingPlace] = s.recordLandingPlace
            it[K.recordTimes] = s.recordTimes
            it[K.backupAfterChanges] = s.backupAfterChanges.coerceAtLeast(0)
            it[K.backupAfterDays] = s.backupAfterDays.coerceAtLeast(0)
            it[K.pilotName] = s.pilotName.trim()
            it[K.pilotIdentifiers] = encodeIdentifiers(s.pilotIdentifiers.map { id -> PilotIdentifier(id.label, id.value) })
            it[K.witnessSignoff] = s.witnessSignoff
            if (s.defaultGliderId == null) it.remove(K.defaultGliderId) else it[K.defaultGliderId] = s.defaultGliderId
            if (s.defaultAirfieldId == null) it.remove(K.defaultAirfieldId) else it[K.defaultAirfieldId] = s.defaultAirfieldId
        }
    }
}
