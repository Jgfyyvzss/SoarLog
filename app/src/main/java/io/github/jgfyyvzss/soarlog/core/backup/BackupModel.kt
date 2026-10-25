package io.github.jgfyyvzss.soarlog.core.backup

import java.time.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The JSON backup format from Appendix B. Property order is the key order in the file, so do not reorder.
// Every field has a default, so a file from an older format version still decodes after its upgrade step.

const val BACKUP_FORMAT = "glider-logbook-backup"
/** Version 2 adds witness sign-offs (docs/Witness_Sign-Off.md). An app that only knows version 1 refuses the file. */
const val BACKUP_FORMAT_VERSION = 2

@Serializable
data class BackupFile(
    val format: String = BACKUP_FORMAT,
    @SerialName("format_version") val formatVersion: Int = BACKUP_FORMAT_VERSION,
    @SerialName("exported_at") val exportedAt: String = "",
    @SerialName("app_version") val appVersion: String = "",
    /** True when the file carries witness photos, so a file holding faces is recognisable before it is opened. */
    @SerialName("contains_photos") val containsPhotos: Boolean = false,
    @SerialName("launch_types") val launchTypes: List<BackupLaunchType> = emptyList(),
    val clubs: List<BackupClub> = emptyList(),
    val airfields: List<BackupAirfield> = emptyList(),
    val gliders: List<BackupGlider> = emptyList(),
    val flights: List<BackupFlight> = emptyList(),
    @SerialName("witness_signoffs") val witnessSignoffs: List<BackupWitnessSignoff> = emptyList(),
    @SerialName("opening_balances") val openingBalances: List<BackupOpeningBalance> = emptyList(),
    @SerialName("import_batches") val importBatches: List<BackupImportBatch> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

@Serializable
data class BackupLaunchType(
    val id: String,
    val name: String,
    @SerialName("name_key") val nameKey: String,
    @SerialName("sort_order") val sortOrder: Int = 0,
    val archived: Boolean = false,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class BackupClub(
    val id: String,
    val name: String,
    @SerialName("name_key") val nameKey: String,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

/** An airfield carries its club IDs in place of the link table. */
@Serializable
data class BackupAirfield(
    val id: String,
    val name: String,
    @SerialName("name_key") val nameKey: String,
    @SerialName("club_ids") val clubIds: List<String> = emptyList(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("elevation_m") val elevationM: Int? = null,
    @SerialName("match_radius_m") val matchRadiusM: Int? = null,
    @SerialName("default_club_id") val defaultClubId: String? = null,
    @SerialName("default_launch_type_id") val defaultLaunchTypeId: String? = null,
    val incomplete: Boolean = false,
    val archived: Boolean = false,
    @SerialName("import_batch_id") val importBatchId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class BackupGlider(
    val id: String,
    val registration: String,
    @SerialName("registration_key") val registrationKey: String,
    val type: String = "",
    @SerialName("weglide_aircraft_id") val weglideAircraftId: Long? = null,
    @SerialName("logbook_total_minutes") val logbookTotalMinutes: Int? = null,
    @SerialName("logbook_total_flights") val logbookTotalFlights: Int? = null,
    @SerialName("logbook_total_date") val logbookTotalDate: String? = null,
    val incomplete: Boolean = false,
    val archived: Boolean = false,
    @SerialName("import_batch_id") val importBatchId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class BackupFlight(
    val id: String,
    @SerialName("log_no") val logNo: Int,
    val date: String,
    @SerialName("duration_minutes") val durationMinutes: Int,
    @SerialName("glider_id") val gliderId: String,
    @SerialName("airfield_id") val airfieldId: String,
    @SerialName("club_id") val clubId: String? = null,
    @SerialName("launch_type_id") val launchTypeId: String,
    val role: String,
    @SerialName("pic_name") val picName: String,
    @SerialName("launch_time") val launchTime: String? = null,
    @SerialName("landing_time") val landingTime: String? = null,
    @SerialName("landing_airfield_id") val landingAirfieldId: String? = null,
    @SerialName("landing_place") val landingPlace: String? = null,
    val notes: String? = null,
    val source: String,
    @SerialName("import_batch_id") val importBatchId: String? = null,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
    /** Both null, or both set: the sign-off and this flight's seal. */
    @SerialName("witness_signoff_id") val witnessSignoffId: String? = null,
    @SerialName("witness_seal") val witnessSeal: String? = null,
)

/** One witness sign-off. [photo] is the stored JPEG in base64, or null when removed or left out of the export. */
@Serializable
data class BackupWitnessSignoff(
    val id: String,
    @SerialName("witness_name") val witnessName: String,
    @SerialName("witness_identifier") val witnessIdentifier: String? = null,
    @SerialName("signed_at") val signedAt: String,
    val photo: String? = null,
    @SerialName("photo_sha256") val photoSha256: String? = null,
    @SerialName("photo_removed") val photoRemoved: Boolean = false,
    @SerialName("seal_version") val sealVersion: Int,
    @SerialName("consent_confirmed") val consentConfirmed: Boolean,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("deleted_at") val deletedAt: String? = null,
)

@Serializable
data class BackupOpeningBalance(
    val role: String,
    @SerialName("as_of_date") val asOfDate: String? = null,
    val minutes: Int = 0,
    val flights: Int = 0,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class BackupImportBatch(
    val id: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("file_name") val fileName: String,
    @SerialName("flight_count") val flightCount: Int,
)

/** The non-secret settings that are backed up. Device-specific settings and credentials are never included. */
@Serializable
data class BackupSettings(
    @SerialName("hours_display") val hoursDisplay: String = "HM",
    @SerialName("instruction_counts_as_pic") val instructionCountsAsPic: Boolean = false,
    @SerialName("record_landing_place") val recordLandingPlace: Boolean = false,
    @SerialName("record_times") val recordTimes: Boolean = false,
    @SerialName("backup_after_changes") val backupAfterChanges: Int = BackupSchedule.DEFAULT_AFTER_CHANGES,
    @SerialName("backup_after_days") val backupAfterDays: Int = BackupSchedule.DEFAULT_AFTER_DAYS,
    @SerialName("pilot_name") val pilotName: String = "",
    @SerialName("pilot_identifiers") val pilotIdentifiers: List<BackupIdentifier> = emptyList(),
    @SerialName("witness_signoff") val witnessSignoff: Boolean = false,
    /** The glider new hand-entered flights start with, or null. */
    @SerialName("default_glider_id") val defaultGliderId: String? = null,
    /** The airfield new hand-entered flights start with, or null; its own defaults then fill club and launch type. */
    @SerialName("default_airfield_id") val defaultAirfieldId: String? = null,
)

@Serializable
data class BackupIdentifier(val label: String, val value: String)

/** Instants are epoch milliseconds in the database and ISO 8601 UTC strings in the file. */
object BackupTime {
    fun toIso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).toString()

    fun fromIso(text: String): Long? = runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
}
