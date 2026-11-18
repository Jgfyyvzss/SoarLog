package io.github.jgfyyvzss.soarlog.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

// Schema as in Appendix A of docs/spec.md. Dates are ISO text, instants are epoch milliseconds in UTC,
// and deleted_at is null for live rows. The partial unique indexes are created in SchemaSql.

@Entity(
    tableName = "glider",
    indices = [Index("import_batch_id")],
)
data class GliderEntity(
    @PrimaryKey val id: String,
    val registration: String,
    @ColumnInfo(name = "registration_key") val registrationKey: String,
    val type: String,
    @ColumnInfo(name = "weglide_aircraft_id") val weglideAircraftId: Long? = null,
    @ColumnInfo(name = "logbook_total_minutes") val logbookTotalMinutes: Int? = null,
    @ColumnInfo(name = "logbook_total_flights") val logbookTotalFlights: Int? = null,
    @ColumnInfo(name = "logbook_total_date") val logbookTotalDate: String? = null,
    val incomplete: Boolean,
    val archived: Boolean = false,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "airfield",
    foreignKeys = [
        ForeignKey(entity = ClubEntity::class, parentColumns = ["id"], childColumns = ["default_club_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LaunchTypeEntity::class, parentColumns = ["id"], childColumns = ["default_launch_type_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("import_batch_id"), Index("default_club_id"), Index("default_launch_type_id")],
)
data class AirfieldEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    @ColumnInfo(name = "elevation_m") val elevationM: Int? = null,
    @ColumnInfo(name = "match_radius_m") val matchRadiusM: Int? = null,
    /** Pre-selected on the flight form when the airfield has more than one club. One of its linked clubs. */
    @ColumnInfo(name = "default_club_id") val defaultClubId: String? = null,
    /** Fills the flight form's launch type when the airfield is chosen, unless the pilot picked one. */
    @ColumnInfo(name = "default_launch_type_id") val defaultLaunchTypeId: String? = null,
    val incomplete: Boolean,
    val archived: Boolean = false,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** Optional, usually the ICAO code; stored as [io.github.jgfyyvzss.soarlog.core.Keys.airfieldCode] gives it. Unique among live airfields. */
    val code: String? = null,
)

@Entity(tableName = "club")
data class ClubEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(
    tableName = "airfield_club",
    primaryKeys = ["airfield_id", "club_id"],
    foreignKeys = [
        ForeignKey(entity = AirfieldEntity::class, parentColumns = ["id"], childColumns = ["airfield_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ClubEntity::class, parentColumns = ["id"], childColumns = ["club_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("club_id")],
)
data class AirfieldClubEntity(
    @ColumnInfo(name = "airfield_id") val airfieldId: String,
    @ColumnInfo(name = "club_id") val clubId: String,
)

@Entity(tableName = "launch_type")
data class LaunchTypeEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "name_key") val nameKey: String,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    val archived: Boolean = false,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

@Entity(tableName = "opening_balance")
data class OpeningBalanceEntity(
    @PrimaryKey val role: String,
    @ColumnInfo(name = "as_of_date") val asOfDate: String? = null,
    val minutes: Int = 0,
    val flights: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "import_batch")
data class ImportBatchEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "file_name") val fileName: String,
    @ColumnInfo(name = "flight_count") val flightCount: Int,
)

@Entity(
    tableName = "flight",
    foreignKeys = [
        ForeignKey(entity = GliderEntity::class, parentColumns = ["id"], childColumns = ["glider_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AirfieldEntity::class, parentColumns = ["id"], childColumns = ["airfield_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ClubEntity::class, parentColumns = ["id"], childColumns = ["club_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = LaunchTypeEntity::class, parentColumns = ["id"], childColumns = ["launch_type_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = AirfieldEntity::class, parentColumns = ["id"], childColumns = ["landing_airfield_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = WitnessSignoffEntity::class, parentColumns = ["id"], childColumns = ["witness_signoff_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [
        Index("date"),
        Index("glider_id"),
        Index("airfield_id"),
        Index("club_id"),
        Index("role"),
        Index("import_batch_id"),
        Index("launch_type_id"),
        Index("landing_airfield_id"),
        Index("witness_signoff_id"),
    ],
)
data class FlightEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "log_no") val logNo: Int,
    val date: String,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    @ColumnInfo(name = "glider_id") val gliderId: String,
    @ColumnInfo(name = "airfield_id") val airfieldId: String,
    @ColumnInfo(name = "club_id") val clubId: String? = null,
    @ColumnInfo(name = "launch_type_id") val launchTypeId: String,
    val role: String,
    @ColumnInfo(name = "pic_name") val picName: String,
    @ColumnInfo(name = "launch_time") val launchTime: String? = null,
    @ColumnInfo(name = "landing_time") val landingTime: String? = null,
    @ColumnInfo(name = "landing_airfield_id") val landingAirfieldId: String? = null,
    @ColumnInfo(name = "landing_place") val landingPlace: String? = null,
    val notes: String? = null,
    val source: String,
    @ColumnInfo(name = "import_batch_id") val importBatchId: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
    /** The sign-off that witnessed this flight; set together with [witnessSeal], or both null. */
    @ColumnInfo(name = "witness_signoff_id") val witnessSignoffId: String? = null,
    /** The SHA-256 seal over the signed fields when witnessed; see core/witness/WitnessSeal. */
    @ColumnInfo(name = "witness_seal") val witnessSeal: String? = null,
)

/**
 * One witness sign-off, which can cover many flights (docs/Witness_Sign-Off.md). The name and number are never
 * edited after signing; a correction means removing the mark and witnessing again. The photo is a small JPEG kept
 * in the row; removing it keeps its hash, so the seals stay valid.
 */
@Entity(tableName = "witness_signoff")
class WitnessSignoffEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "witness_name") val witnessName: String,
    /** The licence or membership number, when the witness gave one. */
    @ColumnInfo(name = "witness_identifier") val witnessIdentifier: String? = null,
    @ColumnInfo(name = "signed_at") val signedAt: Long,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val photo: ByteArray? = null,
    @ColumnInfo(name = "photo_sha256") val photoSha256: String? = null,
    @ColumnInfo(name = "photo_removed") val photoRemoved: Boolean = false,
    @ColumnInfo(name = "seal_version") val sealVersion: Int,
    @ColumnInfo(name = "consent_confirmed") val consentConfirmed: Boolean,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
) {
    fun copy(
        photo: ByteArray? = this.photo,
        photoRemoved: Boolean = this.photoRemoved,
        updatedAt: Long = this.updatedAt,
        deletedAt: Long? = this.deletedAt,
    ) = WitnessSignoffEntity(id, witnessName, witnessIdentifier, signedAt, photo, photoSha256, photoRemoved, sealVersion, consentConfirmed, updatedAt, deletedAt)
}

/**
 * A logbook endorsement (docs/Endorsements.md): a free-text type, the date on the page, and optionally a picture of
 * the page. [imageSha256] set with no image row means the image was left out of the backup it came from.
 */
@Entity(
    tableName = "endorsement",
    foreignKeys = [
        ForeignKey(entity = WitnessSignoffEntity::class, parentColumns = ["id"], childColumns = ["witness_signoff_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("date"), Index("witness_signoff_id")],
)
data class EndorsementEntity(
    @PrimaryKey val id: String,
    val type: String,
    val date: String,
    @ColumnInfo(name = "image_sha256") val imageSha256: String? = null,
    /** The sign-off that witnessed this endorsement; set together with [witnessSeal], or both null. */
    @ColumnInfo(name = "witness_signoff_id") val witnessSignoffId: String? = null,
    @ColumnInfo(name = "witness_seal") val witnessSeal: String? = null,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "deleted_at") val deletedAt: Long? = null,
)

/**
 * An endorsement's image, apart from its row so lists never load image bytes. At most 1 MB, which keeps it well
 * inside the 2 MB a row can be read back with. There is no foreign key: Merge and Restore replace the endorsement
 * rows while keeping images that did not change, and EndorsementRepository deletes an image with its endorsement.
 */
@Entity(tableName = "endorsement_image")
class EndorsementImageEntity(
    @PrimaryKey @ColumnInfo(name = "endorsement_id") val endorsementId: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val image: ByteArray,
)

/** Where a flight record came from. */
enum class FlightSource { MANUAL, IGC, CSV, OGN }
