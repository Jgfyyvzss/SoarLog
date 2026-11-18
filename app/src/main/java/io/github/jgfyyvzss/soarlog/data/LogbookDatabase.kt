package io.github.jgfyyvzss.soarlog.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        GliderEntity::class,
        AirfieldEntity::class,
        ClubEntity::class,
        AirfieldClubEntity::class,
        LaunchTypeEntity::class,
        OpeningBalanceEntity::class,
        ImportBatchEntity::class,
        FlightEntity::class,
        WitnessSignoffEntity::class,
        EndorsementEntity::class,
        EndorsementImageEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
abstract class LogbookDatabase : RoomDatabase() {
    abstract fun gliderDao(): GliderDao
    abstract fun airfieldDao(): AirfieldDao
    abstract fun clubDao(): ClubDao
    abstract fun launchTypeDao(): LaunchTypeDao
    abstract fun flightDao(): FlightDao
    abstract fun openingBalanceDao(): OpeningBalanceDao
    abstract fun backupDao(): BackupDao
    abstract fun csvDao(): CsvDao
    abstract fun witnessDao(): WitnessDao
    abstract fun endorsementDao(): EndorsementDao

    /**
     * Creates the partial indexes and triggers and seeds launch types and opening balances. The indexes and
     * triggers are also ensured on every open, so a migration that rebuilds a table cannot leave them out.
     */
    object CreateCallback : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            SchemaSql.createExtras(db::execSQL)
            SchemaSql.seed(db::execSQL)
        }

        override fun onOpen(db: SupportSQLiteDatabase) {
            SchemaSql.createExtras(db::execSQL)
        }
    }

    companion object {
        const val FILE_NAME = "logbook.db"

        fun build(context: Context): LogbookDatabase =
            Room.databaseBuilder(context, LogbookDatabase::class.java, FILE_NAME)
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(CreateCallback)
                .build()

        fun inMemory(context: Context): LogbookDatabase =
            Room.inMemoryDatabaseBuilder(context, LogbookDatabase::class.java)
                .addCallback(CreateCallback)
                .build()
    }
}
