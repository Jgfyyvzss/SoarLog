package io.github.jgfyyvzss.soarlog.data

import androidx.paging.PagingSource
import androidx.room.withTransaction
import io.github.jgfyyvzss.soarlog.core.FlightContext
import io.github.jgfyyvzss.soarlog.core.FlightDraft
import io.github.jgfyyvzss.soarlog.core.FlightFilter
import io.github.jgfyyvzss.soarlog.core.GliderTotal
import io.github.jgfyyvzss.soarlog.core.pdf.PdfFlight
import io.github.jgfyyvzss.soarlog.core.FlightRules
import io.github.jgfyyvzss.soarlog.core.FlightValidation
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.RoleTotal
import io.github.jgfyyvzss.soarlog.core.ValidFlight
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import io.github.jgfyyvzss.soarlog.core.witness.WitnessSeal
import io.github.jgfyyvzss.soarlog.core.weglide.WeGlideAircraft
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/** The outcome of saving a record. */
sealed interface SaveResult {
    data class Saved(val id: String) : SaveResult
    data class Rejected(val message: Msg) : SaveResult
}

/** The outcome of writing a reviewed import, such as the OGN import's flights. */
sealed interface ImportCreateResult {
    /** [renumbered] when another flight took a number during review, so the rows were numbered again. */
    data class Created(val flights: Int, val firstLogNo: Int, val lastLogNo: Int, val renumbered: Boolean) : ImportCreateResult
    /** Nothing was written. */
    data class Failed(val message: Msg) : ImportCreateResult
}

class LogbookRepository(
    private val db: LogbookDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val gliders = db.gliderDao()
    private val airfields = db.airfieldDao()
    private val clubs = db.clubDao()
    private val launchTypes = db.launchTypeDao()
    private val flights = db.flightDao()
    private val openingBalances = db.openingBalanceDao()

    // Gliders

    fun observeGliders(): Flow<List<GliderEntity>> = gliders.observeAll()
    suspend fun glider(id: String) = gliders.get(id)

    suspend fun saveGlider(id: String?, registration: String, type: String, archived: Boolean): SaveResult {
        val reg = registration.trim()
        if (reg.isEmpty()) return SaveResult.Rejected(msg(MsgKey.REGISTRATION_REQUIRED))
        val key = Keys.registrationKey(reg)
        gliders.findLiveByKey(key, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.GLIDER_EXISTS, it.registration))
        }
        val existing = id?.let { gliders.get(it) }
        val row = (existing ?: GliderEntity(id = Keys.newId(), registration = reg, registrationKey = key, type = "", incomplete = true, updatedAt = 0))
            .copy(registration = reg, registrationKey = key, type = type.trim(), incomplete = type.isBlank(), archived = archived, updatedAt = now())
        if (existing == null) gliders.insert(row) else gliders.update(row)
        return SaveResult.Saved(row.id)
    }

    fun observeGlider(id: String): Flow<GliderEntity?> = gliders.observe(id)

    /**
     * Stores the total last written in the aircraft logbook, as the calculator's default. Minutes and date are set
     * together; the flight count is optional. Passing null minutes clears all three.
     */
    suspend fun saveLogbookTotal(gliderId: String, minutes: Int?, flights: Int?, date: LocalDate?): SaveResult {
        val g = gliders.get(gliderId) ?: return SaveResult.Rejected(msg(MsgKey.GLIDER_GONE))
        if (minutes != null && (minutes < 0 || date == null)) return SaveResult.Rejected(msg(MsgKey.LOGBOOK_HOURS_AND_DATE))
        if (flights != null && flights < 0) return SaveResult.Rejected(msg(MsgKey.FLIGHTS_NEGATIVE))
        gliders.update(
            g.copy(
                logbookTotalMinutes = minutes,
                logbookTotalFlights = flights.takeIf { minutes != null },
                logbookTotalDate = date?.toString().takeIf { minutes != null },
                updatedAt = now(),
            ),
        )
        return SaveResult.Saved(gliderId)
    }

    /**
     * Sets the glider's WeGlide aircraft and replaces its type with WeGlide's name, which is lost once saved but stays
     * editable by hand. Null clears the ID and keeps the type as it is.
     */
    suspend fun setWeGlideAircraft(gliderId: String, aircraft: WeGlideAircraft?): SaveResult {
        val g = gliders.get(gliderId) ?: return SaveResult.Rejected(msg(MsgKey.GLIDER_GONE))
        val type = aircraft?.name ?: g.type
        gliders.update(g.copy(weglideAircraftId = aircraft?.id, type = type, incomplete = type.isBlank(), updatedAt = now()))
        return SaveResult.Saved(gliderId)
    }

    /** The glider's live flights on [date], and the gliders flown that day. */
    suspend fun flightsOnDay(gliderId: String, date: LocalDate) = flights.onDay(gliderId, date.toString())
    suspend fun glidersOnDay(date: LocalDate) = flights.glidersOnDay(date.toString())

    /**
     * Fills in the flights changed since witnessed when the filter's Witness choice needs them; SQL cannot work
     * out a seal, so every filtered query goes through this first.
     */
    suspend fun resolve(filter: FlightFilter): FlightFilter =
        if (filter.needsChangedIds) filter.copy(changedIds = WitnessRepository(db).changedIds()) else filter

    /** The same, kept current as flights and sign-offs change, for the Flights list and Totals. */
    fun observeResolved(filter: FlightFilter): Flow<FlightFilter> =
        if (!filter.needsChangedIds) flowOf(filter)
        else WitnessRepository(db).observeStatuses()
            .map { s -> filter.copy(changedIds = s.filterValues { it == SealStatus.CHANGED }.keys) }
            .distinctUntilChanged()

    suspend fun countFlights(filter: FlightFilter): Int = flights.count(FlightQueries.count(resolve(filter)))

    /**
     * Filtered flights for the logbook PDF, oldest first, read [batch] at a time so a long logbook is never all in
     * memory. With [withWitness], a flight whose seal still matches carries its witness for the Witness column.
     */
    fun pdfFlights(filter: FlightFilter, batch: Int = 500, withWitness: Boolean = false): Flow<PdfFlight> = flow {
        val resolved = resolve(filter)
        val witnesses = if (!withWitness) emptyMap() else {
            val statuses = WitnessRepository(db).statuses()
            val signoffs = db.witnessDao().live().associateBy { it.id }
            db.witnessDao().witnessedFlights()
                .filter { statuses[it.id] == SealStatus.WITNESSED }
                .mapNotNull { f -> f.witnessSignoffId?.let { signoffs[it] }?.let { f.id to it } }
                .toMap()
        }
        var offset = 0
        while (true) {
            val rows = flights.pdfBatch(FlightQueries.pdfBatch(resolved, batch, offset))
            rows.forEach { r ->
                emit(
                    PdfFlight(
                        r.logNo, LocalDate.parse(r.date), r.registration, r.gliderType, r.airfieldName, r.clubName,
                        r.launchTypeId, r.launchTypeName, Role.entries.firstOrNull { it.name == r.role } ?: Role.PIC, r.picName,
                        r.durationMinutes, r.launchTime, r.landingTime, r.landingAirfieldName ?: r.landingPlace, r.notes,
                        witnesses[r.id]?.witnessName, witnesses[r.id]?.witnessIdentifier,
                    ),
                )
            }
            if (rows.size < batch) break
            offset += batch
        }
    }

    fun observeGliderTotals(): Flow<List<GliderTotal>> =
        flights.observeGliderTotals().map { rows -> rows.map { GliderTotal(it.type, it.weglideAircraftId, it.minutes, it.flights) } }

    // Airfields and clubs

    fun observeAirfieldList(): Flow<List<AirfieldListRow>> = airfields.observeList()
    fun observeAirfields(): Flow<List<AirfieldEntity>> = airfields.observeAll()
    suspend fun airfield(id: String) = airfields.get(id)
    suspend fun clubsFor(airfieldId: String) = airfields.clubsFor(airfieldId)

    suspend fun saveAirfield(
        id: String?,
        name: String,
        latitude: Double?,
        longitude: Double?,
        elevationM: Int?,
        matchRadiusM: Int?,
        clubIds: List<String>,
        archived: Boolean,
        defaultClubId: String? = null,
        defaultLaunchTypeId: String? = null,
        /** Optional; blank clears it. Only the airfield form changes an existing airfield's code. */
        code: String? = null,
    ): SaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveResult.Rejected(msg(MsgKey.AIRFIELD_NAME_REQUIRED))
        val storedCode = Keys.airfieldCode(code)
        if ((latitude == null) != (longitude == null)) return SaveResult.Rejected(msg(MsgKey.COORDINATES_BOTH))
        if (latitude != null && latitude !in -90.0..90.0) return SaveResult.Rejected(msg(MsgKey.LATITUDE_RANGE))
        if (longitude != null && longitude !in -180.0..180.0) return SaveResult.Rejected(msg(MsgKey.LONGITUDE_RANGE))
        if (matchRadiusM != null && matchRadiusM <= 0) return SaveResult.Rejected(msg(MsgKey.RADIUS_POSITIVE))
        val key = Keys.nameKey(trimmed)
        airfields.findLiveByKey(key, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.AIRFIELD_EXISTS, it.name))
        }
        if (storedCode != null) airfields.findLiveByCode(storedCode, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.AIRFIELD_CODE_EXISTS, storedCode, it.name))
        }
        return db.withTransaction {
            val existing = id?.let { airfields.get(it) }
            val row = (existing ?: AirfieldEntity(id = Keys.newId(), name = trimmed, nameKey = key, incomplete = true, updatedAt = 0))
                .copy(
                    name = trimmed, nameKey = key, code = storedCode, latitude = latitude, longitude = longitude,
                    elevationM = elevationM, matchRadiusM = matchRadiusM,
                    // The default club must be one of the airfield's own clubs.
                    defaultClubId = defaultClubId?.takeIf { it in clubIds },
                    defaultLaunchTypeId = defaultLaunchTypeId,
                    incomplete = latitude == null, archived = archived, updatedAt = now(),
                )
            if (existing == null) airfields.insert(row) else airfields.update(row)
            airfields.replaceLinks(row.id, clubIds)
            SaveResult.Saved(row.id)
        }
    }

    /** The live airfield with this code, archived or not. */
    suspend fun airfieldByCode(code: String): AirfieldEntity? = Keys.airfieldCode(code)?.let { airfields.findLiveByCode(it) }

    /** Adds a code to an existing airfield, leaving the rest of it as it is. Used by the OGN search. */
    suspend fun setAirfieldCode(id: String, code: String): SaveResult {
        val storedCode = Keys.airfieldCode(code) ?: return SaveResult.Rejected(msg(MsgKey.AIRFIELD_CODE_REQUIRED))
        airfields.findLiveByCode(storedCode, id)?.let {
            return SaveResult.Rejected(msg(MsgKey.AIRFIELD_CODE_EXISTS, storedCode, it.name))
        }
        val existing = airfields.get(id) ?: return SaveResult.Rejected(msg(MsgKey.AIRFIELD_GONE))
        airfields.update(existing.copy(code = storedCode, updatedAt = now()))
        return SaveResult.Saved(id)
    }

    fun observeClubs(): Flow<List<ClubEntity>> = clubs.observeAll()

    suspend fun saveClub(id: String?, name: String): SaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveResult.Rejected(msg(MsgKey.CLUB_NAME_REQUIRED))
        val key = Keys.nameKey(trimmed)
        clubs.findLiveByKey(key, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.CLUB_EXISTS, it.name))
        }
        val existing = id?.let { clubs.get(it) }
        val row = (existing ?: ClubEntity(id = Keys.newId(), name = trimmed, nameKey = key, updatedAt = 0))
            .copy(name = trimmed, nameKey = key, updatedAt = now())
        if (existing == null) clubs.insert(row) else clubs.update(row)
        return SaveResult.Saved(row.id)
    }

    // Launch types

    fun observeLaunchTypes(): Flow<List<LaunchTypeEntity>> = launchTypes.observeAll()

    suspend fun saveLaunchType(id: String?, name: String, archived: Boolean): SaveResult {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return SaveResult.Rejected(msg(MsgKey.LAUNCH_TYPE_NAME_REQUIRED))
        val key = Keys.nameKey(trimmed)
        launchTypes.findLiveByKey(key, id ?: "")?.let {
            return SaveResult.Rejected(msg(MsgKey.LAUNCH_TYPE_EXISTS, it.name))
        }
        val existing = id?.let { launchTypes.get(it) }
        val row = existing?.copy(name = trimmed, nameKey = key, archived = archived, updatedAt = now())
            ?: LaunchTypeEntity(
                id = Keys.newId(), name = trimmed, nameKey = key,
                sortOrder = launchTypes.maxSortOrder() + 1, archived = archived, updatedAt = now(),
            )
        if (existing == null) launchTypes.insert(row) else launchTypes.update(row)
        return SaveResult.Saved(row.id)
    }

    // Flights

    fun pagedFlights(filter: FlightFilter = FlightFilter()): PagingSource<Int, FlightListRow> =
        flights.pagedList(FlightQueries.list(filter))

    /** Minutes and flights per role for the live flights matching [filter]. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observeTotalsByRole(filter: FlightFilter): Flow<Map<Role, RoleTotal>> =
        observeResolved(filter).flatMapLatest { flights.totalsByRole(FlightQueries.totalsByRole(it)) }.map { rows ->
            rows.mapNotNull { r -> Role.entries.firstOrNull { it.name == r.role }?.let { it to RoleTotal(r.minutes, r.flights) } }.toMap()
        }

    // Opening balances

    fun observeOpeningBalances(): Flow<List<OpeningBalanceEntity>> = openingBalances.observeAll()

    /** Saves the five balances together. Minutes and flights must not be negative. */
    suspend fun saveOpeningBalances(balances: List<OpeningBalanceEntity>): SaveResult {
        if (balances.any { it.minutes < 0 || it.flights < 0 }) return SaveResult.Rejected(msg(MsgKey.BALANCES_NEGATIVE))
        val time = now()
        openingBalances.update(balances.map { it.copy(updatedAt = time) })
        return SaveResult.Saved("opening_balance")
    }
    suspend fun flight(id: String) = flights.get(id)

    /** The highest live log number plus one, or 1 for the first flight. */
    suspend fun nextLogNo(): Int = (flights.maxLogNo() ?: 0) + 1

    suspend fun validateFlight(id: String?, draft: FlightDraft): FlightValidation {
        val logNo = FlightRules.parseLogNo(draft.logNoText)
        val holder = logNo?.let { flights.liveHolderOf(it, id ?: "") }
        val clubCount = draft.airfieldId?.let { airfields.clubsFor(it).size } ?: 0
        return FlightRules.validate(draft, FlightContext(clubCount, holder?.describe()))
    }

    /**
     * Validates and saves. Warnings do not block saving; the form shows them before the pilot confirms. The source
     * applies to a new flight; an edited one keeps its own.
     */
    suspend fun saveFlight(id: String?, draft: FlightDraft, source: FlightSource = FlightSource.MANUAL): Pair<FlightValidation, String?> =
        db.withTransaction {
            val result = validateFlight(id, draft)
            if (result !is FlightValidation.Valid) return@withTransaction result to null
            val existing = id?.let { flights.get(it) }
            val row = rowFor(result.flight, existing, source)
            if (existing == null) flights.insert(row) else flights.update(row)
            result to row.id
        }

    /**
     * The witness name when saving [draft] over a flight that still matches its seal would change a signed field,
     * so the form can ask first; null when nothing signed changes or the flight is not witnessed.
     */
    suspend fun witnessAffectedBy(id: String, draft: FlightDraft): String? {
        val existing = flights.get(id) ?: return null
        val signoff = existing.witnessSignoffId?.let { db.witnessDao().get(it) } ?: return null
        val seal = existing.witnessSeal ?: return null
        if (WitnessSeal.check(WitnessRepository.sealInput(existing, signoff), seal, signoff.sealVersion) != SealStatus.WITNESSED) return null
        val valid = validateFlight(id, draft) as? FlightValidation.Valid ?: return null
        val after = rowFor(valid.flight, existing, FlightSource.MANUAL)
        val unchanged = WitnessSeal.check(WitnessRepository.sealInput(after, signoff), seal, signoff.sealVersion) == SealStatus.WITNESSED
        return if (unchanged) null else signoff.witnessName
    }

    /** The row a validated flight is saved as; an edit keeps the source, batch and witness mark. */
    private fun rowFor(f: ValidFlight, existing: FlightEntity?, source: FlightSource) =
            FlightEntity(
                id = existing?.id ?: Keys.newId(),
                logNo = f.logNo,
                date = f.date.toString(),
                durationMinutes = f.durationMinutes,
                gliderId = f.gliderId,
                airfieldId = f.airfieldId,
                clubId = f.clubId,
                launchTypeId = f.launchTypeId,
                role = f.role.name,
                picName = f.picName,
                launchTime = f.launchTime?.toString(),
                landingTime = f.landingTime?.toString(),
                landingAirfieldId = f.landingAirfieldId,
                landingPlace = f.landingPlace,
                notes = f.notes,
                source = existing?.source ?: source.name,
                importBatchId = existing?.importBatchId,
                updatedAt = now(),
                // An edit keeps the witness mark; if a signed field changed, the seal no longer matches and the
                // flight shows Changed since witnessed.
                witnessSignoffId = existing?.witnessSignoffId,
                witnessSeal = existing?.witnessSeal,
            )

    /**
     * Deletes a flight by leaving a tombstone, which frees its log number. A witness sign-off that no live flight
     * refers to any more is deleted with it, photo and all.
     */
    suspend fun deleteFlight(id: String) {
        db.withTransaction {
            val existing = flights.get(id) ?: return@withTransaction
            val time = now()
            flights.update(existing.copy(updatedAt = time, deletedAt = time))
            db.witnessDao().deleteUnreferenced(time)
        }
    }

    // OGN import

    suspend fun flightsOnDate(date: LocalDate): List<FlightEntity> = flights.onDate(date.toString())

    suspend fun newestFlight(): FlightLabel? = flights.newest()

    /**
     * Writes reviewed imported flights in one transaction, as one import batch so Undo last import removes them.
     * A glider ID found in [stubs] is a glider still to be created, as CSV import does: registration and type,
     * marked incomplete. If another flight took one of the numbers during review, every row is renumbered from the
     * next free number, keeping their order. Either every flight is written or none.
     */
    suspend fun createImportedFlights(
        drafts: List<FlightDraft>,
        stubs: Map<String, Pair<String, String>>,
        batchName: String,
        source: FlightSource,
    ): ImportCreateResult = db.withTransaction {
        val time = now()
        val numbers = drafts.map { FlightRules.parseLogNo(it.logNoText) }
        val clash = numbers.any { n -> n != null && flights.liveHolderOf(n) != null }
        val numbered = if (!clash) drafts else {
            var next = nextLogNo()
            val renumber = drafts.indices.sortedBy { numbers[it] ?: Int.MAX_VALUE }.associateWith { next++ }
            drafts.mapIndexed { i, d -> d.copy(logNoText = renumber.getValue(i).toString()) }
        }
        val valid = numbered.map { d ->
            when (val v = validateFlight(null, d)) {
                is FlightValidation.Valid -> v.flight
                is FlightValidation.Invalid -> return@withTransaction ImportCreateResult.Failed(v.errors.values.first())
            }
        }
        val batch = Keys.newId()
        val stubIds = stubs.mapValues { (_, value) ->
            val (registration, type) = value
            val key = Keys.registrationKey(registration)
            gliders.findLiveByKey(key)?.id ?: Keys.newId().also { id ->
                gliders.insert(GliderEntity(id, registration.trim(), key, type.trim(), incomplete = true, importBatchId = batch, updatedAt = time))
            }
        }
        valid.forEach { f ->
            val row = rowFor(f.copy(gliderId = stubIds[f.gliderId] ?: f.gliderId), null, source)
            flights.insert(row.copy(importBatchId = batch, updatedAt = time))
        }
        db.csvDao().insertBatch(ImportBatchEntity(batch, time, batchName, valid.size))
        ImportCreateResult.Created(valid.size, valid.minOf { it.logNo }, valid.maxOf { it.logNo }, renumbered = clash)
    }

    /** "the flight on 17/10/26 in FQM", with the date shown in the pilot's format by the UI. */
    private fun FlightLabel.describe(): Msg =
        msg(MsgKey.FLIGHT_ON, runCatching { LocalDate.parse(date) }.getOrDefault(date), registration)
}
