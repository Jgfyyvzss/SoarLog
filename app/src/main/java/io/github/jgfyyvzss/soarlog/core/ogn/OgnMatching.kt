package io.github.jgfyyvzss.soarlog.core.ogn

import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.igc.Geo
import io.github.jgfyyvzss.soarlog.core.igc.IgcParameters
import io.github.jgfyyvzss.soarlog.core.igc.MatchPlace
import io.github.jgfyyvzss.soarlog.core.igc.NearbyPlace

/** Matching OGN data to the pilot's own gliders and airfields (docs/OGN Flightbook import scoping.md). */
object OgnMatching {
    /**
     * Registrations agree ignoring case, hyphens and spaces. Failing that, the part after the first hyphen on one side
     * is compared with the whole of the other, so `GVS` finds `VH-GVS` and `VH-GVS` finds `GVS`.
     */
    fun sameRegistration(a: String?, b: String?): Boolean {
        val fullA = a?.let(Keys::registrationKey)?.takeIf { it.isNotEmpty() } ?: return false
        val fullB = b?.let(Keys::registrationKey)?.takeIf { it.isNotEmpty() } ?: return false
        if (fullA == fullB) return true
        return afterHyphen(a) == fullB || afterHyphen(b) == fullA
    }

    private fun afterHyphen(registration: String): String? =
        registration.trim().substringAfter('-', "").let(Keys::registrationKey).takeIf { it.isNotEmpty() }

    /**
     * Airfields whose match radius contains the position Flightbook gives for an airfield, nearest first. The caller
     * passes only airfields without a code, which are the ones that may be the same place under another name.
     */
    fun airfieldsNear(latitude: Double, longitude: Double, places: List<MatchPlace>): List<NearbyPlace> =
        places.mapNotNull { p ->
            val d = Geo.distanceM(latitude, longitude, p.latitude, p.longitude)
            if (d <= (p.radiusM ?: IgcParameters.DEFAULT_MATCH_RADIUS_M)) NearbyPlace(p.id, d) else null
        }.sortedBy { it.distanceM }
}
