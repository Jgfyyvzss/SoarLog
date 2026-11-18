package io.github.jgfyyvzss.soarlog.data

import io.github.jgfyyvzss.soarlog.core.Keys
import io.github.jgfyyvzss.soarlog.core.Role

/**
 * SQL that Room's annotations cannot express: partial unique indexes over live rows, triggers that
 * enforce the column rules from Appendix A, and the seed rows. Created on creation and ensured on every open.
 *
 * Room's schema validation after a migration rejects indexes it does not know, so every migration drops the
 * partial indexes first (see [logbookMigration]) and the open callback recreates them.
 */
object SchemaSql {
    /** Partial unique index name to its table and column. */
    private val partialIndexDefs = listOf(
        Triple("live_flight_log_no", "flight", "log_no"),
        Triple("live_glider_registration_key", "glider", "registration_key"),
        Triple("live_airfield_name_key", "airfield", "name_key"),
        // A null code is never a duplicate, since SQLite treats every NULL as distinct.
        Triple("live_airfield_code", "airfield", "code"),
        Triple("live_club_name_key", "club", "name_key"),
        Triple("live_launch_type_name_key", "launch_type", "name_key"),
    )

    val partialIndexNames = partialIndexDefs.map { it.first }

    val partialUniqueIndexes = partialIndexDefs.map { (name, table, column) ->
        "CREATE UNIQUE INDEX IF NOT EXISTS $name ON $table($column) WHERE deleted_at IS NULL"
    }

    fun dropPartialIndexes(execSQL: (String) -> Unit) {
        partialIndexNames.forEach { execSQL("DROP INDEX IF EXISTS $it") }
    }

    private val roleList = Role.entries.joinToString(",") { "'${it.name}'" }

    /** The flight rule checks, applied to both inserts and updates. */
    private val flightCheck = """
        SELECT CASE
          WHEN NEW.log_no <= 0 THEN RAISE(ABORT, 'log_no must be positive')
          WHEN NEW.duration_minutes NOT BETWEEN 1 AND 1440 THEN RAISE(ABORT, 'duration_minutes must be 1 to 1440')
          WHEN trim(NEW.pic_name) = '' THEN RAISE(ABORT, 'pic_name must not be blank')
          WHEN NEW.role NOT IN ($roleList) THEN RAISE(ABORT, 'unknown role')
          WHEN NEW.source NOT IN ('MANUAL','IGC','CSV','OGN') THEN RAISE(ABORT, 'unknown source')
          WHEN NEW.landing_airfield_id IS NOT NULL AND NEW.landing_place IS NOT NULL
            THEN RAISE(ABORT, 'landing_airfield_id and landing_place are both set')
          WHEN NEW.launch_time IS NOT NULL AND NEW.landing_time IS NOT NULL AND NEW.landing_time <= NEW.launch_time
            THEN RAISE(ABORT, 'landing_time must be after launch_time')
        END;
    """.trimIndent()

    val triggers = listOf(
        "CREATE TRIGGER IF NOT EXISTS flight_check_insert BEFORE INSERT ON flight BEGIN $flightCheck END",
        "CREATE TRIGGER IF NOT EXISTS flight_check_update BEFORE UPDATE ON flight BEGIN $flightCheck END",
    )

    /** For a migration that changes a trigger's rules: the open callback then creates them afresh. */
    fun dropTriggers(execSQL: (String) -> Unit) {
        execSQL("DROP TRIGGER IF EXISTS flight_check_insert")
        execSQL("DROP TRIGGER IF EXISTS flight_check_update")
    }

    fun createExtras(execSQL: (String) -> Unit) {
        (partialUniqueIndexes + triggers).forEach(execSQL)
    }

    /** Seed rows use updated_at 0, so any restored or merged row wins over them. */
    fun seed(execSQL: (String) -> Unit) {
        Keys.seededLaunchTypes.forEachIndexed { index, name ->
            execSQL(
                "INSERT OR IGNORE INTO launch_type (id, name, name_key, sort_order, archived, updated_at, deleted_at) " +
                    "VALUES ('${Keys.seededLaunchTypeId(name)}', '$name', '${Keys.nameKey(name)}', $index, 0, 0, NULL)",
            )
        }
        Role.entries.forEach { role ->
            execSQL(
                "INSERT OR IGNORE INTO opening_balance (role, as_of_date, minutes, flights, updated_at) " +
                    "VALUES ('${role.name}', NULL, 0, 0, 0)",
            )
        }
    }
}
