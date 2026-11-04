package io.github.jgfyyvzss.soarlog.core.witness

import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.Role
import io.github.jgfyyvzss.soarlog.core.msg
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

/** A ticked flight, as the sign-off checks see it. */
data class WitnessedFlight(val role: Role, val picName: String)

/** What the sign-off screen finds: problems that stop it, and warnings to show. */
data class WitnessCheck(val errors: List<Msg>, val warnings: List<Msg>) {
    val ok: Boolean get() = errors.isEmpty()
}

/**
 * The sign-off checks from `docs/Witness_Sign-Off.md`. The name and the photo are required and the number optional
 * (decided 2026-10-23): the photo is the evidence of who witnessed. A witness who is the pilot, by name or by any
 * of the profile's numbers, is refused.
 */
object WitnessChecks {
    /** Names match ignoring case and repeated or surrounding spaces. */
    fun nameKey(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    /** Numbers match ignoring case, spaces and hyphens, as registrations do. */
    fun numberKey(number: String): String = number.lowercase(Locale.ROOT).replace(" ", "").replace("-", "")

    fun check(
        name: String,
        identifier: String?,
        hasPhoto: Boolean,
        profileName: String,
        profileIdentifiers: List<String>,
        flights: List<WitnessedFlight>,
        /** False when an endorsement is witnessed, which has no flights to tick. */
        needsFlights: Boolean = true,
    ): WitnessCheck {
        val errors = mutableListOf<Msg>()
        val warnings = mutableListOf<Msg>()
        if (name.isBlank()) errors += msg(MsgKey.WITNESS_NAME_REQUIRED)
        if (needsFlights && flights.isEmpty()) errors += msg(MsgKey.WITNESS_NO_FLIGHTS)
        if (!hasPhoto) errors += msg(MsgKey.WITNESS_PHOTO_REQUIRED)
        val sameName = name.isNotBlank() && profileName.isNotBlank() && nameKey(name) == nameKey(profileName)
        val number = identifier?.let(::numberKey)?.takeIf { it.isNotEmpty() }
        val sameNumber = number != null && profileIdentifiers.any { numberKey(it) == number }
        if (sameName || sameNumber) errors += msg(MsgKey.WITNESS_IS_PILOT)
        studentPic(flights)?.let { pic -> if (name.isNotBlank() && nameKey(name) != nameKey(pic)) warnings += msg(MsgKey.WITNESS_NOT_PIC, pic) }
        return WitnessCheck(errors, warnings)
    }

    /**
     * The pilot in command shared by every ticked Student flight, to pre-fill the witness name; null when there are
     * no Student flights or they name different people.
     */
    fun studentPic(flights: List<WitnessedFlight>): String? {
        val pics = flights.filter { it.role == Role.STUDENT }.map { it.picName.trim() }
        if (pics.isEmpty() || pics.any { it.isEmpty() }) return null
        return pics.first().takeIf { first -> pics.all { nameKey(it) == nameKey(first) } }
    }

    /** Days between the flight and its witnessing, in the phone's time zone; 0 for the same day or earlier. */
    fun daysAfter(flightDate: LocalDate, signedAt: Long, zone: ZoneId): Long =
        ChronoUnit.DAYS.between(flightDate, Instant.ofEpochMilli(signedAt).atZone(zone).toLocalDate()).coerceAtLeast(0)
}
