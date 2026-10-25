package io.github.jgfyyvzss.soarlog.data

import androidx.room.withTransaction
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.csv.AirfieldCsv
import io.github.jgfyyvzss.soarlog.core.csv.FlightCsv
import io.github.jgfyyvzss.soarlog.core.csv.FlightImport
import io.github.jgfyyvzss.soarlog.core.csv.GliderCsv
import io.github.jgfyyvzss.soarlog.core.csv.KnownNames
import io.github.jgfyyvzss.soarlog.core.csv.ParsedAirfield
import io.github.jgfyyvzss.soarlog.core.csv.ParsedGlider
import io.github.jgfyyvzss.soarlog.core.csv.RecordImport
import io.github.jgfyyvzss.soarlog.core.ClockTime
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.csv.CsvError
import io.github.jgfyyvzss.soarlog.core.csv.ExistingRows
import io.github.jgfyyvzss.soarlog.core.csv.ParsedFlight
import io.github.jgfyyvzss.soarlog.core.msg
import java.time.LocalDate
import io.github.jgfyyvzss.soarlog.core.witness.SealStatus
import java.time.Instant
import java.time.ZoneId

/** How the confirmation screen resolved what the flights file left open. */
data class FlightImportChoices(
    /** For each unmatched glider key: the id of an existing glider to use, or null to create a stub. */
    val gliders: Map<String, String?> = emptyMap(),
    /** For each unmatched airfield key: the id of an existing airfield to use, or null to create a stub. */
    val airfields: Map<String, String?> = emptyMap(),
    /** For rows with a blank launch type. */
    val defaultLaunchTypeId: String? = null,
    /** For blank PIC cells on PIC, Instructor and Coach rows. */
    val profileName: String = "",
)

/** [flights] is the new flights; [updated] the existing ones an overwrite changed, and [skipped] those Skip existing left. */
data class ImportOutcome(val flights: Int, val gliders: Int, val airfields: Int, val clubs: Int, val updated: Int = 0, val skipped: Int = 0)

/**
 * What an overwrite would do to the flights a file matches. [differs] lists matches whose date or glider is not the
 * logbook's, which usually means a wrong number in the file. [problems] are rows whose times would no longer fit
 * with the flight's other time, and they block Overwrite (Skip existing is still possible).
 */
data class OverwriteCheck(val matches: Int, val differs: List<Msg>, val witnessed: Int, val problems: List<Msg>)

/** What Undo last import would remove, shown before it runs. */
data class UndoPreview(val batch: ImportBatchEntity, val flights: Int, val gliders: Int, val airfields: Int)

/** CSV export and import, with stub records for unmatched names, batches, and Undo last import. */
class CsvService(
    private val db: LogbookDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    /** WeGlide's name for an aircraft ID, written to the gliders export for readability. */
    private val aircraftName: suspend (Long) -> String? = { null },
) {
    private val dao = db.csvDao()

    // Export

    /** With [witness] (the Setting is on), the witness columns are filled for flights whose seal still matches. */
    suspend fun exportFlights(witness: Boolean = false): String {
        val witnessed = if (witness) witnessCells() else emptyMap()
        val gliders = dao.liveGliders().associateBy { it.id }
        val airfields = dao.liveAirfields().associateBy { it.id }
        val clubs = dao.liveClubs().associateBy { it.id }
        val types = dao.liveLaunchTypes().associateBy { it.id }
        return FlightCsv.export(
            dao.liveFlights().map { f ->
                FlightCsv.ExportRow(
                    logNo = f.logNo, date = f.date, glider = gliders[f.gliderId]?.registration ?: "",
                    airfield = airfields[f.airfieldId]?.name ?: "", club = f.clubId?.let { clubs[it]?.name },
                    launchType = types[f.launchTypeId]?.name ?: "", role = f.role, pic = f.picName,
                    durationMinutes = f.durationMinutes, launchTime = f.launchTime, landingTime = f.landingTime,
                    landingAirfield = f.landingAirfieldId?.let { airfields[it]?.name }, landingPlace = f.landingPlace, notes = f.notes,
                    witnessedBy = witnessed[f.id]?.first, witnessedOn = witnessed[f.id]?.second,
                )
            },
            witness = witness,
        )
    }

    /** Witness and signing date (YYYY-MM-DD on this phone) by flight ID, for flights still matching their seal. */
    private suspend fun witnessCells(): Map<String, Pair<String, String>> {
        val statuses = WitnessRepository(db, now).statuses()
        val signoffs = db.witnessDao().live().associateBy { it.id }
        val zone = ZoneId.systemDefault()
        return db.witnessDao().witnessedFlights().mapNotNull { f ->
            val s = f.witnessSignoffId?.let { signoffs[it] }?.takeIf { statuses[f.id] == SealStatus.WITNESSED } ?: return@mapNotNull null
            f.id to (FlightCsv.witnessedBy(s.witnessName, s.witnessIdentifier) to Instant.ofEpochMilli(s.signedAt).atZone(zone).toLocalDate().toString())
        }.toMap()
    }

    suspend fun exportGliders(): String =
        GliderCsv.export(dao.liveGliders().map { GliderCsv.ExportRow(it.registration, it.type, it.weglideAircraftId, it.weglideAircraftId?.let { id -> aircraftName(id) }, it.archived) })

    suspend fun exportAirfields(): String {
        val clubs = dao.liveClubs().associateBy { it.id }
        val links = dao.links().groupBy({ it.airfieldId }, { it.clubId })
        return AirfieldCsv.export(
            dao.liveAirfields().map { a ->
                AirfieldCsv.ExportRow(
                    a.name, a.latitude, a.longitude, a.elevationM, a.matchRadiusM,
                    links[a.id].orEmpty().mapNotNull { clubs[it]?.name }.sortedBy(Keys::nameKey),
                    a.archived,
                )
            },
        )
    }

    // Flights import

    suspend fun known(): KnownNames {
        val types = dao.liveLaunchTypes().sortedWith(compareBy({ it.archived }, { it.sortOrder }))
        return KnownNames(
            gliderKeys = dao.liveGliders().map { it.registrationKey }.toSet(),
            airfieldKeys = dao.liveAirfields().map { it.nameKey }.toSet(),
            launchTypes = types.associate { it.nameKey to it.id },
            launchTypeNames = types.filter { !it.archived }.map { it.name },
            liveLogNumbers = dao.liveLogNumbers().toSet(),
        )
    }

    suspend fun prepareFlights(text: String): FlightImport = FlightCsv.parse(text, known())

    /** Compares each matched row with the live flight holding its number, before the pilot chooses Overwrite. */
    suspend fun overwriteCheck(imp: FlightImport): OverwriteCheck? {
        if (imp.existingLogNos.isEmpty()) return null
        val byLogNo = dao.liveFlights().associateBy { it.logNo }
        val gliders = dao.liveGliders().associateBy { it.id }
        val differs = mutableListOf<Msg>()
        val problems = mutableListOf<Msg>()
        var witnessed = 0
        imp.flights.filter(imp::matches).forEach { f ->
            val e = byLogNo[f.logNo] ?: return@forEach
            val reg = gliders[e.gliderId]?.registration ?: ""
            if (f.date.toString() != e.date || Keys.registrationKey(f.glider) != Keys.registrationKey(reg)) {
                differs += msg(MsgKey.CSV_OVERWRITE_DIFFERS, f.row, e.logNo, LocalDate.parse(e.date), reg)
            }
            if (e.witnessSignoffId != null) witnessed++
            // A file with only one of the two time columns keeps the flight's other time, which must still fit.
            val launch = if ("launch_time" in imp.columns) f.launchTime else e.launchTime?.let(ClockTime::parse)
            val landing = if ("landing_time" in imp.columns) f.landingTime else e.landingTime?.let(ClockTime::parse)
            if (launch != null && landing != null && ClockTime.minutesBetween(launch, landing) == null) {
                problems += CsvError(f.row, "landing_time", landing.toString(), msg(MsgKey.CSV_EXPECT_AFTER_LAUNCH, launch.toString())).message
            }
        }
        return OverwriteCheck(imp.existingLogNos.size, differs, witnessed, problems)
    }

    /** The first log number used when the file has no log_no column. */
    suspend fun nextLogNo(): Int = (dao.liveLogNumbers().maxOrNull() ?: 0) + 1

    /**
     * Commits a checked flights file in one transaction tagged with a new batch. With matches in the file, [existing]
     * says whether they are skipped or overwritten. An overwritten flight keeps its ID, source, import batch and
     * witness mark, so Undo last import never removes it; a column the file leaves out keeps the flight's value.
     */
    suspend fun commitFlights(imp: FlightImport, choices: FlightImportChoices, fileName: String, existing: ExistingRows? = null): ImportOutcome {
        require(imp.errors.isEmpty()) { "The file has errors" }
        require(imp.existingLogNos.isEmpty() || existing != null) { "Choose Skip existing or Overwrite" }
        require(imp.launchTypeNeeded(existing) == 0 || choices.defaultLaunchTypeId != null) { "Choose a default launch type" }
        require(imp.profileNameNeeded(existing) == 0 || choices.profileName.isNotBlank()) { "A pilot in command name is needed" }
        val rows = imp.rowsFor(existing)
        return db.withTransaction {
            val time = now()
            val batch = Keys.newId()
            val gliderIds = dao.liveGliders().associate { it.registrationKey to it.id }.toMutableMap()
            val airfieldIds = dao.liveAirfields().associate { it.nameKey to it.id }.toMutableMap()
            val clubIds = dao.liveClubs().associate { it.nameKey to it.id }.toMutableMap()
            val links = dao.links().map { it.airfieldId to it.clubId }.toMutableSet()

            val newGliders = mutableListOf<GliderEntity>()
            imp.unmatchedGlidersFor(existing).forEach { (key, written) ->
                gliderIds[key] = choices.gliders[key] ?: Keys.newId().also { id ->
                    newGliders += GliderEntity(id, written, key, "", incomplete = true, importBatchId = batch, updatedAt = time)
                }
            }
            val newAirfields = mutableListOf<AirfieldEntity>()
            imp.unmatchedAirfieldsFor(existing).forEach { (key, written) ->
                airfieldIds[key] = choices.airfields[key] ?: Keys.newId().also { id ->
                    newAirfields += AirfieldEntity(id, written, key, incomplete = true, importBatchId = batch, updatedAt = time)
                }
            }
            dao.insertGliders(newGliders)
            dao.insertAirfields(newAirfields)

            // Named clubs are created when unknown and linked to the flight's airfield.
            val newClubs = mutableListOf<ClubEntity>()
            val newLinks = mutableListOf<AirfieldClubEntity>()
            rows.forEach { f ->
                val name = f.club ?: return@forEach
                val key = Keys.nameKey(name)
                val clubId = clubIds.getOrPut(key) { Keys.newId().also { newClubs += ClubEntity(it, name.trim(), key, time) } }
                val airfieldId = airfieldIds.getValue(Keys.nameKey(f.airfield))
                if (links.add(airfieldId to clubId)) newLinks += AirfieldClubEntity(airfieldId, clubId)
            }
            dao.insertClubs(newClubs)
            dao.insertLinks(newLinks)
            dao.touchAirfields(newLinks.map { it.airfieldId }.distinct(), time)
            val clubsByAirfield = links.groupBy({ it.first }, { it.second })

            val byLogNo = if (existing == ExistingRows.OVERWRITE) dao.liveFlights().associateBy { it.logNo } else emptyMap()
            val updates = rows.filter(imp::matches).mapNotNull { f ->
                val e = byLogNo[f.logNo] ?: return@mapNotNull null
                overwrite(e, f, imp.columns, choices, gliderIds, airfieldIds, clubIds, clubsByAirfield, time)
            }
            dao.updateFlights(updates)

            var nextLogNo = if (imp.needsNumbering) nextLogNo() else 0
            val flights = rows.filter { !imp.matches(it) }.map { f ->
                val airfieldId = airfieldIds.getValue(Keys.nameKey(f.airfield))
                // A blank club is filled only when the airfield has exactly one club.
                val clubId = f.club?.let { clubIds.getValue(Keys.nameKey(it)) }
                    ?: clubsByAirfield[airfieldId]?.singleOrNull()
                FlightEntity(
                    id = Keys.newId(),
                    logNo = f.logNo ?: nextLogNo++,
                    date = f.date.toString(),
                    durationMinutes = f.durationMinutes,
                    gliderId = gliderIds.getValue(Keys.registrationKey(f.glider)),
                    airfieldId = airfieldId,
                    clubId = clubId,
                    launchTypeId = f.launchTypeId ?: choices.defaultLaunchTypeId!!,
                    role = f.role.name,
                    picName = f.pic ?: choices.profileName.trim(),
                    launchTime = f.launchTime?.toString(),
                    landingTime = f.landingTime?.toString(),
                    landingAirfieldId = f.landingAirfield?.let { airfieldIds.getValue(Keys.nameKey(it)) },
                    landingPlace = f.landingPlace,
                    notes = f.notes,
                    source = FlightSource.CSV.name,
                    importBatchId = batch,
                    updatedAt = time,
                )
            }
            dao.insertFlights(flights)
            dao.insertBatch(ImportBatchEntity(batch, time, fileName, flights.size))
            ImportOutcome(flights.size, newGliders.size, newAirfields.size, newClubs.size, updates.size, imp.flights.size - rows.size)
        }
    }

    /**
     * An existing flight with a matched row's values: every column the file has replaces the flight's value, and a
     * blank optional cell clears it. Required columns are always in the file. The flight keeps its ID, source,
     * import batch and witness mark.
     */
    private fun overwrite(
        e: FlightEntity, f: ParsedFlight, columns: Set<String>, choices: FlightImportChoices,
        gliderIds: Map<String, String>, airfieldIds: Map<String, String>, clubIds: Map<String, String>,
        clubsByAirfield: Map<String, List<String>>, time: Long,
    ): FlightEntity {
        fun has(c: String) = c in columns
        val airfieldId = airfieldIds.getValue(Keys.nameKey(f.airfield))
        val clubs = clubsByAirfield[airfieldId].orEmpty()
        // Without a club column the flight keeps its club while that club is still one of the airfield's.
        val clubId = if (has("club")) f.club?.let { clubIds.getValue(Keys.nameKey(it)) } ?: clubs.singleOrNull()
            else e.clubId?.takeIf { it in clubs } ?: clubs.singleOrNull()
        var landingAirfieldId = if (has("landing_airfield")) f.landingAirfield?.let { airfieldIds.getValue(Keys.nameKey(it)) } else e.landingAirfieldId
        var landingPlace = if (has("landing_place")) f.landingPlace else e.landingPlace
        // The file's landing wins over the one it would otherwise sit beside, so both are never set.
        if (has("landing_airfield") && landingAirfieldId != null) landingPlace = null
        if (has("landing_place") && landingPlace != null) landingAirfieldId = null
        return e.copy(
            date = f.date.toString(),
            durationMinutes = f.durationMinutes,
            gliderId = gliderIds.getValue(Keys.registrationKey(f.glider)),
            airfieldId = airfieldId,
            clubId = clubId,
            launchTypeId = if (has("launch_type")) f.launchTypeId ?: choices.defaultLaunchTypeId!! else e.launchTypeId,
            role = f.role.name,
            picName = if (has("pic")) f.pic ?: choices.profileName.trim() else e.picName,
            launchTime = if (has("launch_time")) f.launchTime?.toString() else e.launchTime,
            landingTime = if (has("landing_time")) f.landingTime?.toString() else e.landingTime,
            landingAirfieldId = landingAirfieldId,
            landingPlace = landingPlace,
            notes = if (has("notes")) f.notes else e.notes,
            updatedAt = time,
        )
    }

    // Gliders and airfields import

    suspend fun prepareGliders(text: String): RecordImport<ParsedGlider> =
        GliderCsv.parse(text, dao.liveGliders().map { it.registrationKey }.toSet())

    suspend fun commitGliders(imp: RecordImport<ParsedGlider>, fileName: String): ImportOutcome {
        require(imp.errors.isEmpty()) { "The file has errors" }
        return db.withTransaction {
            val time = now()
            val batch = Keys.newId()
            dao.insertGliders(
                imp.records.map {
                    GliderEntity(Keys.newId(), it.registration, Keys.registrationKey(it.registration), it.type,
                        weglideAircraftId = it.weglideAircraftId, incomplete = false, archived = it.archived, importBatchId = batch, updatedAt = time)
                },
            )
            dao.insertBatch(ImportBatchEntity(batch, time, fileName, 0))
            ImportOutcome(0, imp.records.size, 0, 0)
        }
    }

    suspend fun prepareAirfields(text: String): RecordImport<ParsedAirfield> =
        AirfieldCsv.parse(text, dao.liveAirfields().map { it.nameKey }.toSet())

    suspend fun commitAirfields(imp: RecordImport<ParsedAirfield>, fileName: String): ImportOutcome {
        require(imp.errors.isEmpty()) { "The file has errors" }
        return db.withTransaction {
            val time = now()
            val batch = Keys.newId()
            val clubIds = dao.liveClubs().associate { it.nameKey to it.id }.toMutableMap()
            val newClubs = mutableListOf<ClubEntity>()
            val airfields = mutableListOf<AirfieldEntity>()
            val links = mutableListOf<AirfieldClubEntity>()
            imp.records.forEach { a ->
                val id = Keys.newId()
                airfields += AirfieldEntity(
                    id, a.name, Keys.nameKey(a.name), a.latitude, a.longitude, a.elevationM, a.matchRadiusM,
                    incomplete = a.latitude == null, archived = a.archived, importBatchId = batch, updatedAt = time,
                )
                a.clubs.forEach { name ->
                    val key = Keys.nameKey(name)
                    val clubId = clubIds.getOrPut(key) { Keys.newId().also { newClubs += ClubEntity(it, name, key, time) } }
                    links += AirfieldClubEntity(id, clubId)
                }
            }
            dao.insertClubs(newClubs)
            dao.insertAirfields(airfields)
            dao.insertLinks(links)
            dao.insertBatch(ImportBatchEntity(batch, time, fileName, 0))
            ImportOutcome(0, 0, airfields.size, newClubs.size)
        }
    }

    // Undo last import

    suspend fun undoPreview(): UndoPreview? {
        val batch = dao.lastBatch() ?: return null
        return UndoPreview(batch, dao.liveFlightsIn(batch.id), dao.removableGliders(batch.id), dao.removableAirfields(batch.id))
    }

    /**
     * Removes the most recent import: its flights, then the gliders and airfields it created that nothing else uses,
     * then the batch row. Removed records are kept as tombstones so a later Merge respects the removal. Clubs the
     * import created, and club links it added, stay.
     */
    suspend fun undo(batchId: String) = db.withTransaction {
        val time = now()
        dao.deleteFlightsIn(batchId, time)
        dao.deleteUnusedGliders(batchId, time)
        dao.deleteUnusedAirfields(batchId, time)
        dao.deleteBatch(batchId)
        // Imported flights start unwitnessed, but one may have been witnessed since.
        db.witnessDao().deleteUnreferenced(time)
    }
}
