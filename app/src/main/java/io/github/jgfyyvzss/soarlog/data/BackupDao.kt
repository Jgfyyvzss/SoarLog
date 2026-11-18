package io.github.jgfyyvzss.soarlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Whole-table reads and writes for backup, restore and merge. Blocking, for use inside one transaction. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM launch_type") fun allLaunchTypes(): List<LaunchTypeEntity>
    @Query("SELECT * FROM club") fun allClubs(): List<ClubEntity>
    @Query("SELECT * FROM airfield") fun allAirfields(): List<AirfieldEntity>
    @Query("SELECT * FROM airfield_club") fun allAirfieldClubs(): List<AirfieldClubEntity>
    @Query("SELECT * FROM glider") fun allGliders(): List<GliderEntity>
    @Query("SELECT * FROM flight") fun allFlights(): List<FlightEntity>
    @Query("SELECT * FROM opening_balance") fun allOpeningBalances(): List<OpeningBalanceEntity>
    @Query("SELECT * FROM import_batch") fun allImportBatches(): List<ImportBatchEntity>
    @Query("SELECT * FROM witness_signoff") fun allWitnessSignoffs(): List<WitnessSignoffEntity>

    /** The hashes of the witness photos still held, for the backup's images. */
    @Query("SELECT DISTINCT photo_sha256 FROM witness_signoff WHERE photo IS NOT NULL AND photo_sha256 IS NOT NULL")
    fun witnessPhotoHashes(): List<String>

    @Query("SELECT photo FROM witness_signoff WHERE photo_sha256 = :sha256 AND photo IS NOT NULL LIMIT 1")
    fun witnessPhoto(sha256: String): ByteArray?

    @Query("SELECT * FROM endorsement") fun allEndorsements(): List<EndorsementEntity>

    /** The hashes of the images of live endorsements, for the backup's images. */
    @Query(
        "SELECT DISTINCT e.image_sha256 FROM endorsement e JOIN endorsement_image i ON i.endorsement_id = e.id " +
            "WHERE e.deleted_at IS NULL AND e.image_sha256 IS NOT NULL",
    )
    fun endorsementImageHashes(): List<String>

    @Query(
        "SELECT i.image FROM endorsement_image i JOIN endorsement e ON e.id = i.endorsement_id " +
            "WHERE e.image_sha256 = :sha256 AND e.deleted_at IS NULL LIMIT 1",
    )
    fun endorsementImage(sha256: String): ByteArray?

    @Query("SELECT endorsement_id FROM endorsement_image") fun endorsementImageIds(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE) fun putEndorsementImage(row: EndorsementImageEntity)
    @Query("DELETE FROM endorsement_image WHERE endorsement_id = :id") fun deleteEndorsementImage(id: String)

    /** Rows of any kind, live or deleted, that the user created. Seeded launch types and balances do not count. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM flight) + (SELECT COUNT(*) FROM glider) + (SELECT COUNT(*) FROM airfield) + " +
            "(SELECT COUNT(*) FROM club) + (SELECT COUNT(*) FROM endorsement)",
    )
    fun userRecordCount(): Int

    /** Records changed after [since], for the backup schedule. Link changes bump the airfield's updated_at. */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM flight WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM glider WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM airfield WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM club WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM launch_type WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM opening_balance WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM witness_signoff WHERE updated_at > :since)
             + (SELECT COUNT(*) FROM endorsement WHERE updated_at > :since)
        """,
    )
    fun changesSince(since: Long): Int

    @Query("DELETE FROM flight") fun deleteFlights()
    @Query("DELETE FROM airfield_club") fun deleteAirfieldClubs()
    @Query("DELETE FROM airfield") fun deleteAirfields()
    @Query("DELETE FROM glider") fun deleteGliders()
    @Query("DELETE FROM club") fun deleteClubs()
    @Query("DELETE FROM launch_type") fun deleteLaunchTypes()
    @Query("DELETE FROM opening_balance") fun deleteOpeningBalances()
    @Query("DELETE FROM import_batch") fun deleteImportBatches()
    @Query("DELETE FROM witness_signoff") fun deleteWitnessSignoffs()
    @Query("DELETE FROM endorsement") fun deleteEndorsements()

    /** Deletes, as tombstones without photos, live sign-offs that no live flight or endorsement refers to, as WitnessDao does. */
    @Query(
        "UPDATE witness_signoff SET photo = NULL, updated_at = :time, deleted_at = :time WHERE deleted_at IS NULL AND id NOT IN " +
            "(SELECT witness_signoff_id FROM flight WHERE deleted_at IS NULL AND witness_signoff_id IS NOT NULL) AND id NOT IN " +
            "(SELECT witness_signoff_id FROM endorsement WHERE deleted_at IS NULL AND witness_signoff_id IS NOT NULL)",
    )
    fun deleteUnreferencedSignoffs(time: Long): Int

    @Insert fun insertLaunchTypes(rows: List<LaunchTypeEntity>)
    @Insert fun insertClubs(rows: List<ClubEntity>)
    @Insert fun insertAirfields(rows: List<AirfieldEntity>)
    @Insert fun insertAirfieldClubs(rows: List<AirfieldClubEntity>)
    @Insert fun insertGliders(rows: List<GliderEntity>)
    @Insert fun insertFlights(rows: List<FlightEntity>)
    @Insert fun insertOpeningBalances(rows: List<OpeningBalanceEntity>)
    @Insert fun insertImportBatches(rows: List<ImportBatchEntity>)
    @Insert fun insertWitnessSignoffs(rows: List<WitnessSignoffEntity>)
    @Insert fun insertEndorsements(rows: List<EndorsementEntity>)
}
