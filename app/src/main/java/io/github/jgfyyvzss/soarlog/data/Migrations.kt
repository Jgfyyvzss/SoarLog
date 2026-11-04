package io.github.jgfyyvzss.soarlog.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * A migration that first drops the partial unique indexes, which Room's post-migration validation would
 * otherwise reject as unknown. [LogbookDatabase.CreateCallback] recreates them when the database opens.
 */
fun logbookMigration(from: Int, to: Int, migrate: (SupportSQLiteDatabase) -> Unit): Migration =
    object : Migration(from, to) {
        override fun migrate(db: SupportSQLiteDatabase) {
            SchemaSql.dropPartialIndexes(db::execSQL)
            migrate(db)
        }
    }

/** Version 2 adds the airfield's default club and default launch type. */
val MIGRATION_1_2 = logbookMigration(1, 2) { db ->
    db.execSQL(
        "ALTER TABLE airfield ADD COLUMN default_club_id TEXT " +
            "REFERENCES club(id) ON UPDATE NO ACTION ON DELETE RESTRICT",
    )
    db.execSQL(
        "ALTER TABLE airfield ADD COLUMN default_launch_type_id TEXT " +
            "REFERENCES launch_type(id) ON UPDATE NO ACTION ON DELETE RESTRICT",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_airfield_default_club_id ON airfield(default_club_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_airfield_default_launch_type_id ON airfield(default_launch_type_id)")
}

/**
 * Version 3 adds witness sign-off (docs/Witness_Sign-Off.md): the `witness_signoff` table and two nullable flight
 * columns pointing to it. The SQL matches what Room generates for [WitnessSignoffEntity], so validation passes.
 */
val MIGRATION_2_3 = logbookMigration(2, 3) { db ->
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `witness_signoff` (`id` TEXT NOT NULL, `witness_name` TEXT NOT NULL, " +
            "`witness_identifier` TEXT, `signed_at` INTEGER NOT NULL, `photo` BLOB, `photo_sha256` TEXT, " +
            "`photo_removed` INTEGER NOT NULL, `seal_version` INTEGER NOT NULL, `consent_confirmed` INTEGER NOT NULL, " +
            "`updated_at` INTEGER NOT NULL, `deleted_at` INTEGER, PRIMARY KEY(`id`))",
    )
    db.execSQL(
        "ALTER TABLE flight ADD COLUMN witness_signoff_id TEXT " +
            "REFERENCES witness_signoff(id) ON UPDATE NO ACTION ON DELETE RESTRICT",
    )
    db.execSQL("ALTER TABLE flight ADD COLUMN witness_seal TEXT")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_flight_witness_signoff_id ON flight(witness_signoff_id)")
}

/**
 * Version 4 adds endorsements (docs/Endorsements.md): the `endorsement` table and its images in `endorsement_image`.
 * The SQL matches what Room generates for [EndorsementEntity] and [EndorsementImageEntity], so validation passes.
 */
val MIGRATION_3_4 = logbookMigration(3, 4) { db ->
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `endorsement` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, `date` TEXT NOT NULL, " +
            "`image_sha256` TEXT, `witness_signoff_id` TEXT, `witness_seal` TEXT, `updated_at` INTEGER NOT NULL, " +
            "`deleted_at` INTEGER, PRIMARY KEY(`id`), FOREIGN KEY(`witness_signoff_id`) REFERENCES `witness_signoff`(`id`) " +
            "ON UPDATE NO ACTION ON DELETE RESTRICT )",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_endorsement_date` ON `endorsement` (`date`)")
    db.execSQL("CREATE INDEX IF NOT EXISTS `index_endorsement_witness_signoff_id` ON `endorsement` (`witness_signoff_id`)")
    db.execSQL(
        "CREATE TABLE IF NOT EXISTS `endorsement_image` (`endorsement_id` TEXT NOT NULL, `image` BLOB NOT NULL, " +
            "PRIMARY KEY(`endorsement_id`))",
    )
}

val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
