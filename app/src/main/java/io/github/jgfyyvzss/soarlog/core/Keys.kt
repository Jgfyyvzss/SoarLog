package io.github.jgfyyvzss.soarlog.core

import java.util.Locale
import java.util.UUID

/** Matching keys stored alongside user-entered names. */
object Keys {
    /** Registration lower-cased with spaces and hyphens removed and nothing else changed, so `?` stays valid. */
    fun registrationKey(registration: String): String =
        registration.lowercase(Locale.ROOT).replace(" ", "").replace("-", "")

    /** Airfield, club and launch type names match ignoring case after trimming. */
    fun nameKey(name: String): String = name.trim().lowercase(Locale.ROOT)

    /**
     * An airfield code as stored: trimmed and in capitals, or null when blank. Usually the ICAO code, but any text is
     * accepted, so a private field can have an unofficial one. Storing capitals makes codes unique ignoring case.
     */
    fun airfieldCode(code: String?): String? = code?.trim()?.uppercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    /** The seeded launch types, in display order. */
    val seededLaunchTypes = listOf("Aero", "Winch", "Self", "Auto")

    /** Fixed UUID for a seeded launch type, derived from its name so restores cannot duplicate it. */
    fun seededLaunchTypeId(name: String): String =
        UUID.nameUUIDFromBytes("launch_type:${nameKey(name)}".toByteArray(Charsets.UTF_8)).toString()

    fun newId(): String = UUID.randomUUID().toString()
}
