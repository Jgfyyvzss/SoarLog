package io.github.jgfyyvzss.soarlog.core.csv

import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Msg
import io.github.jgfyyvzss.soarlog.core.MsgKey
import io.github.jgfyyvzss.soarlog.core.msg

/** A checked gliders.csv or airfields.csv: rows to add, rows skipped because the record exists, and errors. */
data class RecordImport<T>(val records: List<T>, val skipped: List<Msg>, val errors: List<CsvError>, val warnings: List<Msg>)

data class ParsedGlider(val row: Int, val registration: String, val type: String, val weglideAircraftId: Long?, val archived: Boolean = false)

data class ParsedAirfield(
    val row: Int, val name: String, val latitude: Double?, val longitude: Double?,
    val elevationM: Int?, val matchRadiusM: Int?, val clubs: List<String>, val archived: Boolean = false,
    /** As Keys.airfieldCode stores it, or null. */
    val code: String? = null,
)

/** The optional `archived` column: `yes`, `no` or blank (not archived), ignoring case. Null means unreadable. */
internal fun archivedCell(cell: String): Boolean? = when (cell.trim().lowercase()) {
    "", "no" -> false
    "yes" -> true
    else -> null
}

internal fun archivedText(archived: Boolean) = if (archived) "yes" else ""

/** gliders.csv from Appendix C. The WeGlide name is written for readability and ignored on import. */
object GliderCsv {
    val columns = listOf("registration", "type", "weglide_aircraft_id", "weglide_aircraft_name", "archived")

    data class ExportRow(val registration: String, val type: String, val weglideAircraftId: Long?, val weglideAircraftName: String?, val archived: Boolean = false)

    fun export(rows: List<ExportRow>): String = Csv.file(
        columns,
        rows.map { listOf(it.registration, it.type, it.weglideAircraftId?.toString().orEmpty(), it.weglideAircraftName.orEmpty(), archivedText(it.archived)) },
    )

    /** Rows whose registration already exists, here or earlier in the file, are skipped and reported, never updated. */
    fun parse(text: String, existingKeys: Set<String>): RecordImport<ParsedGlider> {
        val table = CsvTable.read(text, columns.toSet()) ?: return RecordImport(emptyList(), emptyList(), listOf(CsvError(1, null, null, msg(MsgKey.CSV_EXPECT_HEADER))), emptyList())
        val errors = listOf("registration", "type").filter { !table.has(it) }.map { CsvError(1, it, null, msg(MsgKey.CSV_EXPECT_COLUMN, it)) }
        if (errors.isNotEmpty()) return RecordImport(emptyList(), emptyList(), errors, emptyList())
        val out = mutableListOf<ParsedGlider>()
        val skipped = mutableListOf<Msg>()
        val errs = mutableListOf<CsvError>()
        val seen = mutableSetOf<String>()
        for (r in table.rows) {
            val reg = r["registration"]
            val type = r["type"]
            val weglideCell = r["weglide_aircraft_id"]
            val weglide = weglideCell.toLongOrNull()
            val before = errs.size
            if (reg.isEmpty()) errs += CsvError(r.number, "registration", reg, msg(MsgKey.CSV_EXPECT_REGISTRATION))
            if (type.isEmpty()) errs += CsvError(r.number, "type", type, msg(MsgKey.CSV_EXPECT_GLIDER_TYPE))
            if (weglideCell.isNotEmpty() && (weglide == null || weglide <= 0)) errs += CsvError(r.number, "weglide_aircraft_id", weglideCell, msg(MsgKey.CSV_EXPECT_POSITIVE))
            val archived = archivedCell(r["archived"]) ?: run { errs += CsvError(r.number, "archived", r["archived"], msg(MsgKey.CSV_EXPECT_YES_NO)); false }
            if (errs.size > before) continue
            val key = Keys.registrationKey(reg)
            when (key) {
                in existingKeys -> skipped += msg(MsgKey.CSV_SKIP_GLIDER_EXISTS, r.number, reg)
                in seen -> skipped += msg(MsgKey.CSV_SKIP_REPEAT, r.number, reg)
                else -> { seen += key; out += ParsedGlider(r.number, reg, type, weglide, archived) }
            }
        }
        return RecordImport(out, skipped, errs, table.unknownColumns.map { msg(MsgKey.CSV_UNUSED_COLUMN, it) })
    }
}

/** airfields.csv from Appendix C. Club names are separated by semicolons; `code` is optional (added for OGN import). */
object AirfieldCsv {
    val columns = listOf("name", "code", "latitude", "longitude", "elevation_m", "match_radius_m", "clubs", "archived")

    data class ExportRow(
        val name: String, val latitude: Double?, val longitude: Double?, val elevationM: Int?, val matchRadiusM: Int?, val clubs: List<String>,
        val archived: Boolean = false, val code: String? = null,
    )

    fun export(rows: List<ExportRow>): String = Csv.file(
        columns,
        rows.map {
            listOf(
                it.name, it.code.orEmpty(), it.latitude?.toString().orEmpty(), it.longitude?.toString().orEmpty(),
                it.elevationM?.toString().orEmpty(), it.matchRadiusM?.toString().orEmpty(), it.clubs.joinToString(";"), archivedText(it.archived),
            )
        },
    )

    /**
     * Rows whose name already exists, or whose code is already used by another airfield, here or earlier in the
     * file, are skipped and reported, never updated. [existingCodes] maps each stored code to its airfield's name.
     */
    fun parse(text: String, existingKeys: Set<String>, existingCodes: Map<String, String> = emptyMap()): RecordImport<ParsedAirfield> {
        val table = CsvTable.read(text, columns.toSet()) ?: return RecordImport(emptyList(), emptyList(), listOf(CsvError(1, null, null, msg(MsgKey.CSV_EXPECT_HEADER))), emptyList())
        if (!table.has("name")) return RecordImport(emptyList(), emptyList(), listOf(CsvError(1, "name", null, msg(MsgKey.CSV_EXPECT_COLUMN, "name"))), emptyList())
        val out = mutableListOf<ParsedAirfield>()
        val skipped = mutableListOf<Msg>()
        val errs = mutableListOf<CsvError>()
        val seen = mutableSetOf<String>()
        val seenCodes = mutableMapOf<String, String>()
        for (r in table.rows) {
            val before = errs.size
            val name = r["name"].also { if (it.isEmpty()) errs += CsvError(r.number, "name", it, msg(MsgKey.CSV_EXPECT_AIRFIELD)) }
            fun dbl(col: String, range: ClosedFloatingPointRange<Double>, what: Msg): Double? {
                val cell = r[col]
                if (cell.isEmpty()) return null
                return cell.toDoubleOrNull()?.takeIf { it in range } ?: run { errs += CsvError(r.number, col, cell, what); null }
            }
            fun int(col: String, positive: Boolean): Int? {
                val cell = r[col]
                if (cell.isEmpty()) return null
                return cell.toIntOrNull()?.takeIf { !positive || it > 0 }
                    ?: run { errs += CsvError(r.number, col, cell, msg(if (positive) MsgKey.CSV_EXPECT_POSITIVE else MsgKey.CSV_EXPECT_WHOLE)); null }
            }
            val lat = dbl("latitude", -90.0..90.0, msg(MsgKey.CSV_EXPECT_LATITUDE))
            val lon = dbl("longitude", -180.0..180.0, msg(MsgKey.CSV_EXPECT_LONGITUDE))
            if ((r["latitude"].isEmpty()) != (r["longitude"].isEmpty())) errs += CsvError(r.number, "longitude", r["longitude"], msg(MsgKey.CSV_EXPECT_BOTH_COORDINATES))
            val elevation = int("elevation_m", positive = false)
            val radius = int("match_radius_m", positive = true)
            val archived = archivedCell(r["archived"]) ?: run { errs += CsvError(r.number, "archived", r["archived"], msg(MsgKey.CSV_EXPECT_YES_NO)); false }
            if (errs.size > before) continue
            val key = Keys.nameKey(name)
            val code = Keys.airfieldCode(r["code"])
            val codeOwner = code?.let { existingCodes[it] ?: seenCodes[it] }
            when {
                key in existingKeys -> skipped += msg(MsgKey.CSV_SKIP_AIRFIELD_EXISTS, r.number, name)
                key in seen -> skipped += msg(MsgKey.CSV_SKIP_REPEAT, r.number, name)
                codeOwner != null -> skipped += msg(MsgKey.CSV_SKIP_AIRFIELD_CODE, r.number, code, codeOwner)
                else -> {
                    seen += key
                    if (code != null) seenCodes[code] = name
                    val clubs = r["clubs"].split(';').map { it.trim() }.filter { it.isNotEmpty() }.distinctBy(Keys::nameKey)
                    out += ParsedAirfield(r.number, name, lat, lon, elevation, radius, clubs, archived, code)
                }
            }
        }
        return RecordImport(out, skipped, errs, table.unknownColumns.map { msg(MsgKey.CSV_UNUSED_COLUMN, it) })
    }
}
