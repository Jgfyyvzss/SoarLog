package io.github.jgfyyvzss.soarlog.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

/** Queries for CSV import, export and Undo last import. */
@Dao
interface CsvDao {
    @Query("SELECT log_no FROM flight WHERE deleted_at IS NULL")
    suspend fun liveLogNumbers(): List<Int>

    @Query("SELECT * FROM flight WHERE deleted_at IS NULL ORDER BY log_no")
    suspend fun liveFlights(): List<FlightEntity>

    @Query("SELECT * FROM glider WHERE deleted_at IS NULL ORDER BY registration_key")
    suspend fun liveGliders(): List<GliderEntity>

    @Query("SELECT * FROM airfield WHERE deleted_at IS NULL ORDER BY name_key")
    suspend fun liveAirfields(): List<AirfieldEntity>

    @Query("SELECT * FROM club WHERE deleted_at IS NULL")
    suspend fun liveClubs(): List<ClubEntity>

    @Query("SELECT * FROM launch_type WHERE deleted_at IS NULL")
    suspend fun liveLaunchTypes(): List<LaunchTypeEntity>

    @Query("SELECT * FROM airfield_club")
    suspend fun links(): List<AirfieldClubEntity>

    @Insert suspend fun insertGliders(rows: List<GliderEntity>)
    @Insert suspend fun insertAirfields(rows: List<AirfieldEntity>)
    @Insert suspend fun insertClubs(rows: List<ClubEntity>)
    @Insert suspend fun insertLinks(rows: List<AirfieldClubEntity>)
    @Insert suspend fun insertFlights(rows: List<FlightEntity>)
    @Update suspend fun updateFlights(rows: List<FlightEntity>)
    @Insert suspend fun insertBatch(batch: ImportBatchEntity)

    @Query("UPDATE airfield SET updated_at = :now WHERE id IN (:ids)")
    suspend fun touchAirfields(ids: List<String>, now: Long)

    // Undo last import

    @Query("SELECT * FROM import_batch ORDER BY created_at DESC LIMIT 1")
    suspend fun lastBatch(): ImportBatchEntity?

    @Query("SELECT COUNT(*) FROM flight WHERE import_batch_id = :batch AND deleted_at IS NULL")
    suspend fun liveFlightsIn(batch: String): Int

    /** Gliders the batch created that no live flight outside the batch uses. */
    @Query(
        """
        SELECT COUNT(*) FROM glider WHERE import_batch_id = :batch AND deleted_at IS NULL AND id NOT IN
          (SELECT glider_id FROM flight WHERE deleted_at IS NULL AND (import_batch_id IS NULL OR import_batch_id != :batch))
        """,
    )
    suspend fun removableGliders(batch: String): Int

    @Query(
        """
        SELECT COUNT(*) FROM airfield WHERE import_batch_id = :batch AND deleted_at IS NULL
          AND id NOT IN (SELECT airfield_id FROM flight WHERE deleted_at IS NULL AND (import_batch_id IS NULL OR import_batch_id != :batch))
          AND id NOT IN (SELECT landing_airfield_id FROM flight WHERE landing_airfield_id IS NOT NULL AND deleted_at IS NULL
                         AND (import_batch_id IS NULL OR import_batch_id != :batch))
        """,
    )
    suspend fun removableAirfields(batch: String): Int

    @Query("UPDATE flight SET deleted_at = :now, updated_at = :now WHERE import_batch_id = :batch AND deleted_at IS NULL")
    suspend fun deleteFlightsIn(batch: String, now: Long)

    /** Run after [deleteFlightsIn], so only flights outside the batch keep a record. */
    @Query(
        """
        UPDATE glider SET deleted_at = :now, updated_at = :now WHERE import_batch_id = :batch AND deleted_at IS NULL
          AND id NOT IN (SELECT glider_id FROM flight WHERE deleted_at IS NULL)
        """,
    )
    suspend fun deleteUnusedGliders(batch: String, now: Long)

    @Query(
        """
        UPDATE airfield SET deleted_at = :now, updated_at = :now WHERE import_batch_id = :batch AND deleted_at IS NULL
          AND id NOT IN (SELECT airfield_id FROM flight WHERE deleted_at IS NULL)
          AND id NOT IN (SELECT landing_airfield_id FROM flight WHERE landing_airfield_id IS NOT NULL AND deleted_at IS NULL)
        """,
    )
    suspend fun deleteUnusedAirfields(batch: String, now: Long)

    @Query("DELETE FROM import_batch WHERE id = :batch")
    suspend fun deleteBatch(batch: String)
}
