package io.github.jgfyyvzss.soarlog.data

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg
import io.github.jgfyyvzss.soarlog.core.backup.BackupAirfield
import io.github.jgfyyvzss.soarlog.core.backup.BackupClub
import io.github.jgfyyvzss.soarlog.core.backup.BackupCodec
import io.github.jgfyyvzss.soarlog.core.backup.BackupEndorsement
import io.github.jgfyyvzss.soarlog.core.backup.BackupEndorsements
import io.github.jgfyyvzss.soarlog.core.backup.BackupFile
import io.github.jgfyyvzss.soarlog.core.backup.BackupImages
import io.github.jgfyyvzss.soarlog.core.backup.BackupFlight
import io.github.jgfyyvzss.soarlog.core.backup.BackupGlider
import io.github.jgfyyvzss.soarlog.core.backup.BackupImportBatch
import io.github.jgfyyvzss.soarlog.core.backup.BackupLaunchType
import io.github.jgfyyvzss.soarlog.core.backup.BackupMerge
import io.github.jgfyyvzss.soarlog.core.backup.BackupOpeningBalance
import io.github.jgfyyvzss.soarlog.core.backup.BackupSchedule
import io.github.jgfyyvzss.soarlog.core.backup.BackupTime
import io.github.jgfyyvzss.soarlog.core.backup.BackupValidation
import io.github.jgfyyvzss.soarlog.core.backup.BackupWitness
import io.github.jgfyyvzss.soarlog.core.backup.BackupWitnessSignoff
import io.github.jgfyyvzss.soarlog.core.backup.BackupZip
import io.github.jgfyyvzss.soarlog.core.backup.BackupZipReader
import io.github.jgfyyvzss.soarlog.core.backup.ImageRead
import io.github.jgfyyvzss.soarlog.core.backup.MergeSummary
import io.github.jgfyyvzss.soarlog.core.backup.Opened
import io.github.jgfyyvzss.soarlog.core.backup.WitnessSummary
import io.github.jgfyyvzss.soarlog.core.endorsement.EndorsementImageRules
import java.io.Closeable
import java.io.File
import java.io.OutputStream
import java.util.concurrent.Callable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** What a backup file holds, shown before Restore or Merge commits. */
data class BackupContents(val flights: Int, val gliders: Int, val airfields: Int, val clubs: Int, val exportedAt: String)

/**
 * An opened backup zip and the witness photos and endorsement images in it that passed their checks. It stays open
 * until the Restore or Merge is committed or dismissed, so the images are read only then, one at a time.
 */
class BackupSource(private val reader: BackupZipReader, private val photos: Set<String>, private val images: Set<String>) : Closeable {
    fun photo(sha256: String): ByteArray? =
        if (sha256 in photos) (reader.read(sha256, BackupWitness.MAX_PHOTO_BYTES) as? ImageRead.Ok)?.bytes else null

    fun image(sha256: String): ByteArray? =
        if (sha256 in images) (reader.read(sha256, EndorsementImageRules.MAX_BYTES) as? ImageRead.Ok)?.bytes else null

    override fun close() = reader.close()
}

/** A checked file, ready to commit. [witness] is the seal summary and anything repaired, shown with the contents. */
sealed interface Prepared {
    data class Restore(val file: BackupFile, val contents: BackupContents, val witness: WitnessSummary, val source: BackupSource) : Prepared
    data class Merge(
        val merged: BackupFile, val contents: BackupContents, val summary: MergeSummary, val witness: WitnessSummary, val source: BackupSource,
    ) : Prepared
    /** Nothing is written. [problems] lists every reason, such as a log number collision. */
    data class Refused(val problems: List<Msg>) : Prepared
}

/** Closes the backup file a prepared Restore or Merge holds open. */
fun Prepared.close() = when (this) {
    is Prepared.Restore -> source.close()
    is Prepared.Merge -> source.close()
    is Prepared.Refused -> Unit
}

/** Export, Restore and Merge of the whole logbook as one backup zip. */
class BackupService(
    private val db: LogbookDatabase,
    private val settings: SettingsRepository,
    private val appVersion: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.backupDao()

    suspend fun isEmpty(): Boolean = withContext(Dispatchers.IO) { dao.userRecordCount() == 0 }

    suspend fun changesSinceLastBackup(): Int {
        val since = settings.settings.first().lastBackupAt ?: -1L
        return withContext(Dispatchers.IO) { dao.changesSince(since) }
    }

    /** Whether the automatic schedule calls for a backup now. Never true without a backup folder. */
    suspend fun isAutoBackupDue(): Boolean {
        val s = settings.settings.first()
        if (s.backupFolderUri == null) return false
        return BackupSchedule.isDue(changesSinceLastBackup(), s.lastBackupAt, now(), s.backupAfterChanges, s.backupAfterDays)
    }

    /**
     * Writes the whole logbook to [out] as a backup zip and closes it: every record, including archived ones and
     * tombstones, and the backed-up settings, then the photos one at a time. Without [includeImages] the photos are
     * left out; names, numbers, hashes and seals stay.
     */
    suspend fun export(out: OutputStream, includeImages: Boolean = true) {
        val (file, hashes) = take(includeImages)
        withContext(Dispatchers.IO) {
            BackupZip.write(out, BackupCodec.encode(file), hashes) { dao.witnessPhoto(it) ?: dao.endorsementImage(it) }
        }
    }

    /** This phone's logbook as backup records, without images. */
    suspend fun snapshot(): BackupFile = take(includeImages = false).first

    /** The records and the hashes of the images to write with them, read in one transaction. */
    private suspend fun take(includeImages: Boolean): Pair<BackupFile, List<String>> {
        val s = settings.settings.first()
        return withContext(Dispatchers.IO) {
            db.runInTransaction(Callable {
                val links = dao.allAirfieldClubs().groupBy({ it.airfieldId }, { it.clubId })
                val hashes = if (includeImages) (dao.witnessPhotoHashes() + dao.endorsementImageHashes()).distinct() else emptyList()
                BackupFile(
                    exportedAt = BackupTime.toIso(now()),
                    appVersion = appVersion,
                    containsImages = hashes.isNotEmpty(),
                    launchTypes = dao.allLaunchTypes().map { it.toBackup() },
                    clubs = dao.allClubs().map { it.toBackup() },
                    airfields = dao.allAirfields().map { it.toBackup(links[it.id].orEmpty()) },
                    gliders = dao.allGliders().map { it.toBackup() },
                    flights = dao.allFlights().map { it.toBackup() },
                    witnessSignoffs = dao.allWitnessSignoffs().map { it.toBackup() },
                    endorsements = dao.allEndorsements().map { it.toBackup() },
                    openingBalances = dao.allOpeningBalances().map { it.toBackup() },
                    importBatches = dao.allImportBatches().map { it.toBackup() },
                    settings = s.toBackup(),
                ) to hashes
            })
        }
    }

    private fun contents(f: BackupFile) = BackupContents(
        flights = f.flights.count { it.deletedAt == null },
        gliders = f.gliders.count { it.deletedAt == null },
        airfields = f.airfields.count { it.deletedAt == null },
        clubs = f.clubs.count { it.deletedAt == null },
        exportedAt = f.exportedAt,
    )

    /** Reads and checks a backup zip for Restore. Restore needs an empty logbook. */
    suspend fun prepareRestore(zip: File): Prepared = prepare(zip) { reader, file ->
        if (!isEmpty()) return@prepare Prepared.Refused(listOf(msg(MsgKey.BACKUP_NEEDS_EMPTY)))
        val problems = BackupValidation.validate(file)
        if (problems.isNotEmpty()) return@prepare Prepared.Refused(problems)
        val review = BackupWitness.review(file, reader)
        val endorsements = BackupEndorsements.review(review.file, reader)
        val witness = review.summary.copy(notes = review.summary.notes + endorsements.notes)
        Prepared.Restore(endorsements.file, contents(endorsements.file), witness, BackupSource(reader, review.photos, endorsements.images))
    }

    /** Reads a backup zip, merges it with this phone's logbook in memory, and checks the result. */
    suspend fun prepareMerge(zip: File): Prepared = prepare(zip) { reader, incoming ->
        BackupValidation.validate(incoming).takeIf { it.isNotEmpty() }?.let { return@prepare Prepared.Refused(it) }
        // Bad photos and dangling references in the file are reported first; the seals are counted on the result.
        val cleaned = BackupWitness.review(incoming, reader)
        val cleanedEndorsements = BackupEndorsements.review(cleaned.file, reader)
        val result = BackupMerge.merge(snapshot(), cleanedEndorsements.file)
        val problems = BackupValidation.validate(result.merged)
        if (problems.isNotEmpty()) return@prepare Prepared.Refused(problems)
        val review = BackupWitness.review(result.merged)
        val merged = BackupEndorsements.review(review.file, BackupImages { _, _ -> ImageRead.Missing }).file
        val witness = review.summary.copy(notes = (cleaned.summary.notes + cleanedEndorsements.notes + review.summary.notes).distinct())
        Prepared.Merge(merged, contents(incoming), result.summary, witness, BackupSource(reader, cleaned.photos, cleanedEndorsements.images))
    }

    /** Opens [zip] and runs [check] on it. The zip is closed again unless [check] returns a Restore or Merge holding it. */
    private suspend fun prepare(zip: File, check: suspend (BackupZipReader, BackupFile) -> Prepared): Prepared {
        val opened = withContext(Dispatchers.IO) { BackupZip.open(zip) }
        if (opened is Opened.Refused) return Prepared.Refused(listOf(opened.message))
        opened as Opened.Ok
        return try {
            withContext(Dispatchers.IO) { check(opened.reader, opened.file) }.also { if (it is Prepared.Refused) opened.reader.close() }
        } catch (e: Throwable) {
            opened.reader.close()
            throw e
        }
    }

    /** Commits a prepared Restore: the logbook and every backed-up setting, including the pilot profile. */
    suspend fun restore(prepared: Prepared.Restore) {
        check(isEmpty()) { "Restore needs an empty logbook" }
        replaceAll(prepared.file, prepared.source, keepLocalPhotos = false)
        settings.applyBackup(prepared.file.settings)
    }

    /** Commits a prepared Merge. Settings and the pilot profile stay as they are. */
    suspend fun merge(prepared: Prepared.Merge) = replaceAll(prepared.merged, prepared.source, keepLocalPhotos = true)

    /**
     * Replaces every table with [file] in one transaction, in dependency order. A sign-off's photo comes from the
     * backup by its hash, or with [keepLocalPhotos] from this phone. Endorsement images that did not change stay where
     * they are, and the others come from the backup one at a time. The partial unique indexes are dropped while rows
     * go in and recreated at the end, which fails and rolls back if the result breaks them.
     */
    private suspend fun replaceAll(file: BackupFile, source: BackupSource, keepLocalPhotos: Boolean): Unit = withContext(Dispatchers.IO) {
        db.runInTransaction(Runnable {
            // Read before the rows go, so a merged sign-off keeps a photo the file left out. Witness photos are at most 25 KB.
            val local = if (!keepLocalPhotos) emptyMap<String, ByteArray>() else dao.allWitnessSignoffs()
                .mapNotNull { s -> s.photo?.let { p -> s.photoSha256?.let { it.lowercase() to p } } }.toMap()
            fun photoFor(s: BackupWitnessSignoff): ByteArray? {
                if (s.photoRemoved || s.deletedAt != null) return null
                val sha = s.photoSha256?.lowercase() ?: return null
                return source.photo(sha) ?: local[sha]
            }
            // Which endorsement images this phone holds, and for which hash, before the rows go. The images
            // themselves stay in their table, which has no foreign key, so unchanged ones are never read.
            val imageIds = dao.endorsementImageIds().toSet()
            val heldSha = dao.allEndorsements().filter { it.id in imageIds }.associate { it.id to it.imageSha256?.lowercase() }

            val sql = db.openHelper.writableDatabase
            SchemaSql.dropPartialIndexes(sql::execSQL)
            dao.deleteFlights()
            dao.deleteEndorsements()
            dao.deleteWitnessSignoffs()
            dao.deleteAirfieldClubs()
            dao.deleteAirfields()
            dao.deleteGliders()
            dao.deleteClubs()
            dao.deleteLaunchTypes()
            dao.deleteOpeningBalances()
            dao.deleteImportBatches()

            dao.insertLaunchTypes(file.launchTypes.map { it.toEntity() })
            dao.insertClubs(file.clubs.map { it.toEntity() })
            dao.insertAirfields(file.airfields.map { it.toEntity() })
            dao.insertAirfieldClubs(file.airfields.flatMap { a -> a.clubIds.distinct().map { AirfieldClubEntity(a.id, it) } })
            dao.insertGliders(file.gliders.map { it.toEntity() })
            dao.insertImportBatches(file.importBatches.map { it.toEntity() })
            dao.insertWitnessSignoffs(file.witnessSignoffs.map { it.toEntity(photoFor(it)) })
            dao.insertEndorsements(file.endorsements.map { it.toEntity() })
            for (e in file.endorsements) {
                val wanted = e.imageSha256?.lowercase()?.takeIf { e.deletedAt == null }
                when {
                    wanted == null -> dao.deleteEndorsementImage(e.id)
                    e.id in imageIds && heldSha[e.id] == wanted -> Unit
                    else -> source.image(wanted)?.let { dao.putEndorsementImage(EndorsementImageEntity(e.id, it)) }
                        ?: dao.deleteEndorsementImage(e.id)
                }
            }
            val kept = file.endorsements.map { it.id }.toSet()
            imageIds.filter { it !in kept }.forEach(dao::deleteEndorsementImage)
            dao.insertFlights(file.flights.map { it.toEntity() })
            dao.deleteUnreferencedSignoffs(now())
            dao.insertOpeningBalances(file.openingBalances.map { it.toEntity() })
            SchemaSql.partialUniqueIndexes.forEach(sql::execSQL)
        })
    }
}

// Mapping between rows and the file. Times are epoch milliseconds in rows and ISO strings in the file.

private fun Long.iso() = BackupTime.toIso(this)
private fun String.ms() = BackupTime.fromIso(this) ?: error("unreadable time $this")

private fun LaunchTypeEntity.toBackup() = BackupLaunchType(id, name, nameKey, sortOrder, archived, updatedAt.iso(), deletedAt?.iso())
private fun BackupLaunchType.toEntity() = LaunchTypeEntity(id, name, nameKey, sortOrder, archived, updatedAt.ms(), deletedAt?.ms())

private fun ClubEntity.toBackup() = BackupClub(id, name, nameKey, updatedAt.iso(), deletedAt?.iso())
private fun BackupClub.toEntity() = ClubEntity(id, name, nameKey, updatedAt.ms(), deletedAt?.ms())

private fun AirfieldEntity.toBackup(clubIds: List<String>) = BackupAirfield(
    id = id, name = name, nameKey = nameKey, clubIds = clubIds, latitude = latitude, longitude = longitude,
    elevationM = elevationM, matchRadiusM = matchRadiusM, defaultClubId = defaultClubId, defaultLaunchTypeId = defaultLaunchTypeId,
    incomplete = incomplete, archived = archived, importBatchId = importBatchId, updatedAt = updatedAt.iso(), deletedAt = deletedAt?.iso(),
)
private fun BackupAirfield.toEntity() = AirfieldEntity(
    id = id, name = name, nameKey = nameKey, latitude = latitude, longitude = longitude, elevationM = elevationM,
    matchRadiusM = matchRadiusM, defaultClubId = defaultClubId, defaultLaunchTypeId = defaultLaunchTypeId,
    incomplete = incomplete, archived = archived, importBatchId = importBatchId, updatedAt = updatedAt.ms(), deletedAt = deletedAt?.ms(),
)

private fun GliderEntity.toBackup() = BackupGlider(
    id, registration, registrationKey, type, weglideAircraftId, logbookTotalMinutes, logbookTotalFlights, logbookTotalDate,
    incomplete, archived, importBatchId, updatedAt.iso(), deletedAt?.iso(),
)
private fun BackupGlider.toEntity() = GliderEntity(
    id, registration, registrationKey, type, weglideAircraftId, logbookTotalMinutes, logbookTotalFlights, logbookTotalDate,
    incomplete, archived, importBatchId, updatedAt.ms(), deletedAt?.ms(),
)

private fun FlightEntity.toBackup() = BackupFlight(
    id, logNo, date, durationMinutes, gliderId, airfieldId, clubId, launchTypeId, role, picName, launchTime, landingTime,
    landingAirfieldId, landingPlace, notes, source, importBatchId, updatedAt.iso(), deletedAt?.iso(), witnessSignoffId, witnessSeal,
)
private fun BackupFlight.toEntity() = FlightEntity(
    id, logNo, date, durationMinutes, gliderId, airfieldId, clubId, launchTypeId, role, picName, launchTime, landingTime,
    landingAirfieldId, landingPlace, notes, source, importBatchId, updatedAt.ms(), deletedAt?.ms(), witnessSignoffId, witnessSeal,
)

private fun WitnessSignoffEntity.toBackup() = BackupWitnessSignoff(
    id, witnessName, witnessIdentifier, signedAt.iso(), photoSha256, photoRemoved, sealVersion, consentConfirmed, updatedAt.iso(), deletedAt?.iso(),
)
private fun BackupWitnessSignoff.toEntity(photo: ByteArray?) = WitnessSignoffEntity(
    id, witnessName, witnessIdentifier, signedAt.ms(), photo, photoSha256, photoRemoved, sealVersion, consentConfirmed, updatedAt.ms(), deletedAt?.ms(),
)

private fun EndorsementEntity.toBackup() = BackupEndorsement(
    id, type, date, imageSha256, witnessSignoffId, witnessSeal, updatedAt.iso(), deletedAt?.iso(),
)
private fun BackupEndorsement.toEntity() = EndorsementEntity(
    id, type, date, imageSha256?.lowercase(), witnessSignoffId, witnessSeal, updatedAt.ms(), deletedAt?.ms(),
)

private fun OpeningBalanceEntity.toBackup() = BackupOpeningBalance(role, asOfDate, minutes, flights, updatedAt.iso())
private fun BackupOpeningBalance.toEntity() = OpeningBalanceEntity(role, asOfDate, minutes, flights, updatedAt.ms())

private fun ImportBatchEntity.toBackup() = BackupImportBatch(id, createdAt.iso(), fileName, flightCount)
private fun BackupImportBatch.toEntity() = ImportBatchEntity(id, createdAt.ms(), fileName, flightCount)
