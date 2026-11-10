package io.github.jgfyyvzss.soarlog.data

import androidx.paging.PagingSource
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import io.github.jgfyyvzss.soarlog.core.FilterSql
import io.github.jgfyyvzss.soarlog.core.FlightFilter
import kotlinx.coroutines.flow.Flow

@Dao
interface GliderDao {
    @Query("SELECT * FROM glider WHERE deleted_at IS NULL ORDER BY archived, registration_key")
    fun observeAll(): Flow<List<GliderEntity>>

    @Query("SELECT * FROM glider WHERE id = :id")
    suspend fun get(id: String): GliderEntity?

    @Query("SELECT * FROM glider WHERE id = :id")
    fun observe(id: String): Flow<GliderEntity?>

    @Query("SELECT * FROM glider WHERE deleted_at IS NULL AND registration_key = :key AND id != :exceptId LIMIT 1")
    suspend fun findLiveByKey(key: String, exceptId: String = ""): GliderEntity?

    @Insert suspend fun insert(glider: GliderEntity)
    @Update suspend fun update(glider: GliderEntity)
}

/** An airfield row for lists, with its club names joined in name order. */
data class AirfieldListRow(
    val id: String,
    val name: String,
    val code: String?,
    val incomplete: Boolean,
    val archived: Boolean,
    @ColumnInfo(name = "club_names") val clubNames: String?,
)

@Dao
interface AirfieldDao {
    @Query(
        """
        SELECT a.id, a.name, a.code, a.incomplete, a.archived,
               (SELECT group_concat(name, ', ') FROM
                  (SELECT c.name FROM airfield_club ac JOIN club c ON c.id = ac.club_id
                   WHERE ac.airfield_id = a.id ORDER BY c.name_key)) AS club_names
        FROM airfield a WHERE a.deleted_at IS NULL ORDER BY a.archived, a.name_key
        """,
    )
    fun observeList(): Flow<List<AirfieldListRow>>

    @Query("SELECT * FROM airfield WHERE deleted_at IS NULL ORDER BY archived, name_key")
    fun observeAll(): Flow<List<AirfieldEntity>>

    @Query("SELECT * FROM airfield WHERE id = :id")
    suspend fun get(id: String): AirfieldEntity?

    @Query("SELECT * FROM airfield WHERE deleted_at IS NULL AND name_key = :key AND id != :exceptId LIMIT 1")
    suspend fun findLiveByKey(key: String, exceptId: String = ""): AirfieldEntity?

    /** [code] as Keys.airfieldCode stores it. */
    @Query("SELECT * FROM airfield WHERE deleted_at IS NULL AND code = :code AND id != :exceptId LIMIT 1")
    suspend fun findLiveByCode(code: String, exceptId: String = ""): AirfieldEntity?

    @Query(
        "SELECT c.* FROM club c JOIN airfield_club ac ON ac.club_id = c.id " +
            "WHERE ac.airfield_id = :airfieldId AND c.deleted_at IS NULL ORDER BY c.name_key",
    )
    suspend fun clubsFor(airfieldId: String): List<ClubEntity>

    @Insert suspend fun insert(airfield: AirfieldEntity)
    @Update suspend fun update(airfield: AirfieldEntity)

    @Query("DELETE FROM airfield_club WHERE airfield_id = :airfieldId")
    suspend fun deleteLinks(airfieldId: String)

    @Insert suspend fun insertLinks(links: List<AirfieldClubEntity>)

    /** Rows are deleted outright when links change, and the caller bumps the airfield's updated_at. */
    @Transaction
    suspend fun replaceLinks(airfieldId: String, clubIds: List<String>) {
        deleteLinks(airfieldId)
        insertLinks(clubIds.distinct().map { AirfieldClubEntity(airfieldId, it) })
    }
}

@Dao
interface ClubDao {
    @Query("SELECT * FROM club WHERE deleted_at IS NULL ORDER BY name_key")
    fun observeAll(): Flow<List<ClubEntity>>

    @Query("SELECT * FROM club WHERE id = :id")
    suspend fun get(id: String): ClubEntity?

    @Query("SELECT * FROM club WHERE deleted_at IS NULL AND name_key = :key AND id != :exceptId LIMIT 1")
    suspend fun findLiveByKey(key: String, exceptId: String = ""): ClubEntity?

    @Insert suspend fun insert(club: ClubEntity)
    @Update suspend fun update(club: ClubEntity)
}

@Dao
interface LaunchTypeDao {
    @Query("SELECT * FROM launch_type WHERE deleted_at IS NULL ORDER BY archived, sort_order, name_key")
    fun observeAll(): Flow<List<LaunchTypeEntity>>

    @Query("SELECT * FROM launch_type WHERE id = :id")
    suspend fun get(id: String): LaunchTypeEntity?

    @Query("SELECT * FROM launch_type WHERE deleted_at IS NULL AND name_key = :key AND id != :exceptId LIMIT 1")
    suspend fun findLiveByKey(key: String, exceptId: String = ""): LaunchTypeEntity?

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM launch_type")
    suspend fun maxSortOrder(): Int

    @Insert suspend fun insert(launchType: LaunchTypeEntity)
    @Update suspend fun update(launchType: LaunchTypeEntity)
}

/** A flight for the two-line list row. */
data class FlightListRow(
    val id: String,
    @ColumnInfo(name = "log_no") val logNo: Int,
    val date: String,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    val role: String,
    val registration: String,
    @ColumnInfo(name = "glider_type") val gliderType: String,
    @ColumnInfo(name = "airfield_name") val airfieldName: String,
    @ColumnInfo(name = "launch_type_name") val launchTypeName: String,
    @ColumnInfo(name = "launch_type_id") val launchTypeId: String,
    /** The first characters of the notes, enough for the list row. */
    @ColumnInfo(name = "notes_start") val notesStart: String? = null,
    /** Set when the flight is witnessed; its seal status comes from [WitnessRepository]. */
    @ColumnInfo(name = "witness_signoff_id") val witnessSignoffId: String? = null,
)

/** Enough about a flight to name it in a duplicate log number message. */
data class FlightLabel(
    @ColumnInfo(name = "log_no") val logNo: Int,
    val date: String,
    val registration: String,
)

@Dao
interface FlightDao {
    /** Uses the partial unique index on live log numbers. */
    @Query("SELECT MAX(log_no) FROM flight WHERE deleted_at IS NULL")
    suspend fun maxLogNo(): Int?

    @Query(
        "SELECT f.log_no, f.date, g.registration FROM flight f JOIN glider g ON g.id = f.glider_id " +
            "WHERE f.deleted_at IS NULL AND f.log_no = :logNo AND f.id != :exceptId LIMIT 1",
    )
    suspend fun liveHolderOf(logNo: Int, exceptId: String = ""): FlightLabel?

    @Query("SELECT * FROM flight WHERE id = :id")
    suspend fun get(id: String): FlightEntity?

    /** The newest live flight by date, then log number, for the OGN import's warning about earlier flights. */
    @Query(
        "SELECT f.log_no, f.date, g.registration FROM flight f JOIN glider g ON g.id = f.glider_id " +
            "WHERE f.deleted_at IS NULL ORDER BY f.date DESC, f.log_no DESC LIMIT 1",
    )
    suspend fun newest(): FlightLabel?

    /** A glider's live flights on one day, for the aircraft logbook calculator. */
    @Query("SELECT * FROM flight WHERE deleted_at IS NULL AND glider_id = :gliderId AND date = :date ORDER BY log_no")
    suspend fun onDay(gliderId: String, date: String): List<FlightEntity>

    /** Every live flight on one day, for witnessing a day's group and the OGN import's Already in logbook check. */
    @Query("SELECT * FROM flight WHERE deleted_at IS NULL AND date = :date ORDER BY log_no")
    suspend fun onDate(date: String): List<FlightEntity>

    /** The gliders flown on one day, ignoring any filter. */
    @Query(
        "SELECT DISTINCT g.* FROM glider g JOIN flight f ON f.glider_id = g.id " +
            "WHERE f.deleted_at IS NULL AND f.date = :date ORDER BY g.registration_key",
    )
    suspend fun glidersOnDay(date: String): List<GliderEntity>

    /** Live flights per glider, all roles, for the Home screen's totals by type. */
    @Query(
        "SELECT g.type AS type, g.weglide_aircraft_id AS weglide_aircraft_id, SUM(f.duration_minutes) AS minutes, COUNT(*) AS flights " +
            "FROM flight f JOIN glider g ON g.id = f.glider_id WHERE f.deleted_at IS NULL GROUP BY g.id",
    )
    fun observeGliderTotals(): Flow<List<GliderTotalRow>>
    /** The filtered list, built by [FlightQueries.list]. */
    @RawQuery(observedEntities = [FlightEntity::class, GliderEntity::class, AirfieldEntity::class, LaunchTypeEntity::class])
    fun pagedList(query: SupportSQLiteQuery): PagingSource<Int, FlightListRow>

    /** A batch of filtered flights for the logbook PDF, oldest first, built by [FlightQueries.pdfBatch]. */
    @RawQuery
    suspend fun pdfBatch(query: SupportSQLiteQuery): List<PdfFlightRow>

    /** How many flights match a filter, built by [FlightQueries.count]. */
    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int

    /** Minutes and flights per role for the filtered flights, built by [FlightQueries.totalsByRole]. */
    @RawQuery(observedEntities = [FlightEntity::class])
    fun totalsByRole(query: SupportSQLiteQuery): Flow<List<RoleTotalRow>>

    @Insert suspend fun insert(flight: FlightEntity)
    @Update suspend fun update(flight: FlightEntity)
}

data class RoleTotalRow(val role: String, val minutes: Int, val flights: Int)

/** A flight with every name the logbook PDF prints. */
data class PdfFlightRow(
    val id: String,
    @ColumnInfo(name = "log_no") val logNo: Int,
    val date: String,
    val registration: String,
    @ColumnInfo(name = "glider_type") val gliderType: String,
    @ColumnInfo(name = "airfield_name") val airfieldName: String,
    @ColumnInfo(name = "club_name") val clubName: String?,
    @ColumnInfo(name = "launch_type_id") val launchTypeId: String,
    @ColumnInfo(name = "launch_type_name") val launchTypeName: String,
    val role: String,
    @ColumnInfo(name = "pic_name") val picName: String,
    @ColumnInfo(name = "duration_minutes") val durationMinutes: Int,
    @ColumnInfo(name = "launch_time") val launchTime: String?,
    @ColumnInfo(name = "landing_time") val landingTime: String?,
    @ColumnInfo(name = "landing_airfield_name") val landingAirfieldName: String?,
    @ColumnInfo(name = "landing_place") val landingPlace: String?,
    val notes: String?,
)

data class GliderTotalRow(val type: String, @ColumnInfo(name = "weglide_aircraft_id") val weglideAircraftId: Long?, val minutes: Int, val flights: Int)

/** Raw queries whose WHERE clause comes from the filter. */
object FlightQueries {
    fun list(filter: FlightFilter): SupportSQLiteQuery {
        val where = FilterSql.where(filter)
        return SimpleSQLiteQuery(
            """
            SELECT f.id, f.log_no, f.date, f.duration_minutes, f.role,
                   g.registration, g.type AS glider_type, a.name AS airfield_name, lt.name AS launch_type_name, f.launch_type_id,
                   substr(f.notes, 1, 80) AS notes_start, f.witness_signoff_id
            FROM flight f
            JOIN glider g ON g.id = f.glider_id
            JOIN airfield a ON a.id = f.airfield_id
            JOIN launch_type lt ON lt.id = f.launch_type_id
            WHERE ${where.sql}
            ORDER BY f.date DESC, f.log_no DESC
            """.trimIndent(),
            where.args.toTypedArray(),
        )
    }

    /** One batch for the logbook PDF: oldest first, like a paper logbook, with the same filter as the list. */
    fun pdfBatch(filter: FlightFilter, limit: Int, offset: Int): SupportSQLiteQuery {
        val where = FilterSql.where(filter)
        return SimpleSQLiteQuery(
            """
            SELECT f.id, f.log_no, f.date, g.registration, g.type AS glider_type, a.name AS airfield_name, c.name AS club_name,
                   f.launch_type_id, lt.name AS launch_type_name, f.role, f.pic_name, f.duration_minutes,
                   f.launch_time, f.landing_time, la.name AS landing_airfield_name, f.landing_place, f.notes
            FROM flight f
            JOIN glider g ON g.id = f.glider_id
            JOIN airfield a ON a.id = f.airfield_id
            JOIN launch_type lt ON lt.id = f.launch_type_id
            LEFT JOIN club c ON c.id = f.club_id
            LEFT JOIN airfield la ON la.id = f.landing_airfield_id
            WHERE ${where.sql}
            ORDER BY f.date ASC, f.log_no ASC
            LIMIT ? OFFSET ?
            """.trimIndent(),
            (where.args + limit + offset).toTypedArray(),
        )
    }

    fun count(filter: FlightFilter): SupportSQLiteQuery {
        val where = FilterSql.where(filter)
        return SimpleSQLiteQuery("SELECT COUNT(*) FROM flight f WHERE ${where.sql}", where.args.toTypedArray())
    }

    fun totalsByRole(filter: FlightFilter): SupportSQLiteQuery {
        val where = FilterSql.where(filter)
        return SimpleSQLiteQuery(
            "SELECT f.role AS role, COALESCE(SUM(f.duration_minutes), 0) AS minutes, COUNT(*) AS flights " +
                "FROM flight f WHERE ${where.sql} GROUP BY f.role",
            where.args.toTypedArray(),
        )
    }
}

@Dao
interface OpeningBalanceDao {
    @Query("SELECT * FROM opening_balance")
    fun observeAll(): Flow<List<OpeningBalanceEntity>>

    @Update suspend fun update(balances: List<OpeningBalanceEntity>)
}

/** A previous witness, for the quick-pick list. */
data class WitnessPick(
    @ColumnInfo(name = "witness_name") val name: String,
    @ColumnInfo(name = "witness_identifier") val identifier: String?,
)

@Dao
interface WitnessDao {
    @Insert suspend fun insert(signoff: WitnessSignoffEntity)
    @Update suspend fun update(signoff: WitnessSignoffEntity)

    @Query("SELECT * FROM witness_signoff WHERE id = :id")
    suspend fun get(id: String): WitnessSignoffEntity?

    @Query("SELECT * FROM witness_signoff WHERE deleted_at IS NULL")
    suspend fun live(): List<WitnessSignoffEntity>

    /** Distinct name and number pairs of live sign-offs, most recently used first. */
    @Query(
        "SELECT witness_name, witness_identifier FROM witness_signoff WHERE deleted_at IS NULL " +
            "GROUP BY witness_name, witness_identifier ORDER BY MAX(signed_at) DESC",
    )
    fun observePicks(): Flow<List<WitnessPick>>

    @Query("SELECT * FROM flight WHERE deleted_at IS NULL AND witness_signoff_id IS NOT NULL")
    suspend fun witnessedFlights(): List<FlightEntity>

    @Query("SELECT * FROM flight WHERE deleted_at IS NULL AND witness_signoff_id IS NOT NULL")
    fun observeWitnessedFlights(): Flow<List<FlightEntity>>

    @Query("SELECT * FROM witness_signoff WHERE deleted_at IS NULL")
    fun observeLive(): Flow<List<WitnessSignoffEntity>>

    /** The sign-off a flight points to, kept up to date as the flight is witnessed again or its mark removed. */
    @Query("SELECT s.* FROM witness_signoff s JOIN flight f ON f.witness_signoff_id = s.id WHERE f.id = :flightId")
    fun observeForFlight(flightId: String): Flow<WitnessSignoffEntity?>

    @Query("UPDATE flight SET witness_signoff_id = :signoffId, witness_seal = :seal, updated_at = :time WHERE id = :flightId")
    suspend fun setWitness(flightId: String, signoffId: String?, seal: String?, time: Long)

    /**
     * Deletes, as tombstones without their photos, the sign-offs that no live flight or endorsement refers to.
     * Called after a mark is removed, a flight or endorsement is deleted or witnessed again, or an import is undone.
     */
    @Query(
        "UPDATE witness_signoff SET photo = NULL, updated_at = :time, deleted_at = :time WHERE deleted_at IS NULL AND id NOT IN " +
            "(SELECT witness_signoff_id FROM flight WHERE deleted_at IS NULL AND witness_signoff_id IS NOT NULL) AND id NOT IN " +
            "(SELECT witness_signoff_id FROM endorsement WHERE deleted_at IS NULL AND witness_signoff_id IS NOT NULL)",
    )
    suspend fun deleteUnreferenced(time: Long): Int
}

@Dao
interface EndorsementDao {
    @Insert suspend fun insert(e: EndorsementEntity)
    @Update suspend fun update(e: EndorsementEntity)

    /** Newest first by date, as the Flights list is. */
    @Query("SELECT * FROM endorsement WHERE deleted_at IS NULL ORDER BY date DESC, updated_at DESC")
    fun observeLive(): Flow<List<EndorsementEntity>>

    @Query("SELECT * FROM endorsement WHERE deleted_at IS NULL ORDER BY date, updated_at")
    suspend fun liveOldestFirst(): List<EndorsementEntity>

    @Query("SELECT * FROM endorsement WHERE id = :id")
    suspend fun get(id: String): EndorsementEntity?

    @Query("SELECT * FROM endorsement WHERE id = :id")
    fun observe(id: String): Flow<EndorsementEntity?>

    @Query("SELECT image FROM endorsement_image WHERE endorsement_id = :id")
    suspend fun image(id: String): ByteArray?

    @Query("SELECT EXISTS(SELECT 1 FROM endorsement_image WHERE endorsement_id = :id)")
    suspend fun hasImage(id: String): Boolean

    /** Replaces any earlier image. */
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putImage(row: EndorsementImageEntity)

    @Query("DELETE FROM endorsement_image WHERE endorsement_id = :id")
    suspend fun deleteImage(id: String)

    @Query("UPDATE endorsement SET witness_signoff_id = :signoffId, witness_seal = :seal, updated_at = :time WHERE id = :id")
    suspend fun setWitness(id: String, signoffId: String?, seal: String?, time: Long)

    /** The sign-off an endorsement points to, kept up to date as it is witnessed again or its mark removed. */
    @Query("SELECT s.* FROM witness_signoff s JOIN endorsement e ON e.witness_signoff_id = s.id WHERE e.id = :id")
    fun observeSignoff(id: String): Flow<WitnessSignoffEntity?>
}