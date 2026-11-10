package io.github.jgfyyvzss.soarlog.core.backup

import io.github.jgfyyvzss.soarlog.core.ClockTime
import io.github.jgfyyvzss.soarlog.core.Durations
import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.msg
import java.time.LocalDate

/**
 * Checks a backup before anything is written: references, enum values, durations, timestamps and the
 * uniqueness rules that the database enforces on live rows. Returns the problems found, empty when valid.
 */
object BackupValidation {
    private val sources = setOf("MANUAL", "IGC", "CSV", "OGN")
    private val roles = Role.entries.map { it.name }.toSet()

    fun validate(file: BackupFile): List<Msg> {
        val p = mutableListOf<Msg>()
        fun ts(what: Msg, value: String?, nullable: Boolean = false) {
            if (value == null) {
                if (!nullable) p += msg(MsgKey.BK_NO_TIME, what)
                return
            }
            if (BackupTime.fromIso(value) == null) p += msg(MsgKey.BK_BAD_TIME, what, value)
        }
        fun <T> dupIds(table: MsgKey, rows: List<T>, id: (T) -> String) {
            rows.groupingBy(id).eachCount().filter { it.value > 1 }.keys.forEach { p += msg(MsgKey.BK_DUPLICATE_ID, msg(table), it) }
        }

        dupIds(MsgKey.BK_TABLE_LAUNCH_TYPES, file.launchTypes) { it.id }
        dupIds(MsgKey.BK_TABLE_CLUBS, file.clubs) { it.id }
        dupIds(MsgKey.BK_TABLE_AIRFIELDS, file.airfields) { it.id }
        dupIds(MsgKey.BK_TABLE_GLIDERS, file.gliders) { it.id }
        dupIds(MsgKey.BK_TABLE_FLIGHTS, file.flights) { it.id }
        dupIds(MsgKey.BK_TABLE_IMPORT_BATCHES, file.importBatches) { it.id }
        dupIds(MsgKey.BK_TABLE_WITNESS_SIGNOFFS, file.witnessSignoffs) { it.id }
        dupIds(MsgKey.BK_TABLE_ENDORSEMENTS, file.endorsements) { it.id }

        val launchTypeIds = file.launchTypes.map { it.id }.toSet()
        val clubIds = file.clubs.map { it.id }.toSet()
        val airfieldIds = file.airfields.map { it.id }.toSet()
        val gliderIds = file.gliders.map { it.id }.toSet()

        file.launchTypes.forEach { t ->
            val n = msg(MsgKey.BK_LAUNCH_TYPE, t.name.ifBlank { t.id })
            if (t.name.isBlank()) p += msg(MsgKey.BK_NO_NAME, n)
            ts(n, t.updatedAt); ts(n, t.deletedAt, nullable = true)
        }
        file.clubs.forEach { c ->
            val n = msg(MsgKey.BK_CLUB, c.name.ifBlank { c.id })
            if (c.name.isBlank()) p += msg(MsgKey.BK_NO_NAME, n)
            ts(n, c.updatedAt); ts(n, c.deletedAt, nullable = true)
        }
        file.airfields.forEach { a ->
            val n = msg(MsgKey.BK_AIRFIELD, a.name.ifBlank { a.id })
            if (a.name.isBlank()) p += msg(MsgKey.BK_NO_NAME, n)
            a.clubIds.filter { it !in clubIds }.forEach { p += msg(MsgKey.BK_MISSING_CLUB, n, it) }
            if (a.defaultClubId != null && a.defaultClubId !in a.clubIds) p += msg(MsgKey.BK_DEFAULT_CLUB, n)
            if (a.defaultLaunchTypeId != null && a.defaultLaunchTypeId !in launchTypeIds) p += msg(MsgKey.BK_MISSING_LAUNCH_TYPE, n)
            if ((a.latitude == null) != (a.longitude == null)) p += msg(MsgKey.BK_ONE_COORDINATE, n)
            a.latitude?.let { if (it !in -90.0..90.0) p += msg(MsgKey.BK_LATITUDE_RANGE, n) }
            a.longitude?.let { if (it !in -180.0..180.0) p += msg(MsgKey.BK_LONGITUDE_RANGE, n) }
            ts(n, a.updatedAt); ts(n, a.deletedAt, nullable = true)
        }
        file.gliders.forEach { g ->
            val n = msg(MsgKey.BK_GLIDER, g.registration.ifBlank { g.id })
            if (g.registration.isBlank()) p += msg(MsgKey.BK_NO_REGISTRATION, n)
            g.logbookTotalDate?.let { if (parseDate(it) == null) p += msg(MsgKey.BK_BAD_LOGBOOK_DATE, n) }
            ts(n, g.updatedAt); ts(n, g.deletedAt, nullable = true)
        }
        file.flights.forEach { f ->
            val n = msg(MsgKey.BK_FLIGHT, f.logNo)
            if (f.logNo <= 0) p += msg(MsgKey.BK_LOG_NO_POSITIVE, n)
            if (parseDate(f.date) == null) p += msg(MsgKey.BK_BAD_DATE, n, f.date)
            if (!Durations.isValidFlightDuration(f.durationMinutes)) p += msg(MsgKey.BK_DURATION_RANGE, n, f.durationMinutes)
            if (f.gliderId !in gliderIds) p += msg(MsgKey.BK_MISSING_GLIDER, n)
            if (f.airfieldId !in airfieldIds) p += msg(MsgKey.BK_MISSING_AIRFIELD, n)
            if (f.clubId != null && f.clubId !in clubIds) p += msg(MsgKey.BK_MISSING_CLUB, n, f.clubId)
            if (f.launchTypeId !in launchTypeIds) p += msg(MsgKey.BK_MISSING_LAUNCH_TYPE, n)
            if (f.landingAirfieldId != null && f.landingAirfieldId !in airfieldIds) p += msg(MsgKey.BK_MISSING_LANDING_AIRFIELD, n)
            if (f.landingAirfieldId != null && f.landingPlace != null) p += msg(MsgKey.BK_BOTH_LANDING, n)
            if (f.role !in roles) p += msg(MsgKey.BK_UNKNOWN_ROLE, n, f.role)
            if (f.source !in sources) p += msg(MsgKey.BK_UNKNOWN_SOURCE, n, f.source)
            if (f.picName.isBlank()) p += msg(MsgKey.BK_NO_PIC, n)
            val launch = f.launchTime?.let { ClockTime.parse(it) ?: run { p += msg(MsgKey.BK_BAD_LAUNCH_TIME, n); null } }
            val landing = f.landingTime?.let { ClockTime.parse(it) ?: run { p += msg(MsgKey.BK_BAD_LANDING_TIME, n); null } }
            if (launch != null && landing != null && ClockTime.minutesBetween(launch, landing) == null) p += msg(MsgKey.BK_LANDING_BEFORE_LAUNCH, n)
            ts(n, f.updatedAt); ts(n, f.deletedAt, nullable = true)
        }
        // Witness sign-offs: a missing photo or reference is repaired by BackupWitness, not refused here.
        file.witnessSignoffs.forEach { s ->
            val n = msg(MsgKey.BK_WITNESS, s.witnessName.ifBlank { s.id })
            if (s.witnessName.isBlank()) p += msg(MsgKey.BK_NO_NAME, n)
            if (!s.consentConfirmed) p += msg(MsgKey.BK_WITNESS_NO_CONSENT, n)
            ts(n, s.signedAt); ts(n, s.updatedAt); ts(n, s.deletedAt, nullable = true)
        }
        // Endorsements: a missing image or witness reference is repaired by BackupEndorsements, not refused here.
        file.endorsements.forEach { e ->
            val n = msg(MsgKey.BK_ENDORSEMENT, e.type.ifBlank { e.id })
            if (e.type.isBlank()) p += msg(MsgKey.BK_NO_TYPE, n)
            if (parseDate(e.date) == null) p += msg(MsgKey.BK_BAD_DATE, n, e.date)
            ts(n, e.updatedAt); ts(n, e.deletedAt, nullable = true)
        }
        file.openingBalances.forEach { b ->
            if (b.role !in roles) p += msg(MsgKey.BK_BALANCE_ROLE, b.role)
            if (b.minutes < 0 || b.flights < 0) p += msg(MsgKey.BK_BALANCE_NEGATIVE, b.role)
            b.asOfDate?.let { if (parseDate(it) == null) p += msg(MsgKey.BK_BALANCE_DATE, b.role) }
            ts(msg(MsgKey.BK_BALANCE, b.role), b.updatedAt)
        }
        file.openingBalances.groupingBy { it.role }.eachCount().filter { it.value > 1 }.keys
            .forEach { p += msg(MsgKey.BK_BALANCE_DUPLICATE, it) }
        file.importBatches.forEach { ts(msg(MsgKey.BK_BATCH, it.fileName), it.createdAt) }

        p += liveCollisions(file)
        return p
    }

    /** Live rows that would break a uniqueness rule, such as two different flights with one log number. */
    fun liveCollisions(file: BackupFile): List<Msg> {
        val p = mutableListOf<Msg>()
        val gliderReg = file.gliders.associate { it.id to it.registration }
        fun describe(f: BackupFlight) = msg(MsgKey.FLIGHT_ON, parseDate(f.date) ?: f.date, gliderReg[f.gliderId] ?: "?")
        file.flights.filter { it.deletedAt == null }.groupBy { it.logNo }.filter { it.value.size > 1 }.forEach { (logNo, flights) ->
            p += msg(MsgKey.BK_LOG_NO_COLLISION, logNo, describe(flights[0]), describe(flights[1]))
        }
        fun <T> unique(rows: List<T>, live: (T) -> Boolean, key: (T) -> String, what: (T) -> Msg) {
            rows.filter(live).groupBy(key).filter { it.value.size > 1 }.values
                .forEach { same -> p += msg(MsgKey.BK_DUPLICATE_KEY, what(same.first())) }
        }
        unique(file.gliders, { it.deletedAt == null }, { it.registrationKey }, { msg(MsgKey.BK_GLIDER, it.registration) })
        unique(file.airfields, { it.deletedAt == null }, { it.nameKey }, { msg(MsgKey.BK_AIRFIELD, it.name) })
        file.airfields.filter { it.deletedAt == null && it.code != null }.groupBy { Keys.airfieldCode(it.code) }
            .filter { it.value.size > 1 }
            .forEach { (code, same) -> p += msg(MsgKey.BK_DUPLICATE_CODE, code, same[0].name, same[1].name) }
        unique(file.clubs, { it.deletedAt == null }, { it.nameKey }, { msg(MsgKey.BK_CLUB, it.name) })
        unique(file.launchTypes, { it.deletedAt == null }, { it.nameKey }, { msg(MsgKey.BK_LAUNCH_TYPE, it.name) })
        return p
    }

    private fun parseDate(text: String): LocalDate? = runCatching { LocalDate.parse(text) }.getOrNull()
}
