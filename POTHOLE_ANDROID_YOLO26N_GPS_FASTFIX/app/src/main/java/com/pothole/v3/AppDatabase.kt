package com.pothole.v3

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PotholeReport::class],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun reportDao(): ReportDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN firstSeenTimestampMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN firstSeenTimestampIso TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN confirmedTimestampMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN confirmedTimestampIso TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN firstSeenSource TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN firstSeenDistanceBand TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN confirmedDistanceBand TEXT NOT NULL DEFAULT 'UNKNOWN'")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN confirmationDelayMs INTEGER NOT NULL DEFAULT 0")
            }
        }


        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN maskAreaPx INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN centroidX REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN centroidY REAL NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN originalImageName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN originalImageUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN maskImageName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN maskImageUri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN overlayImageName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN overlayImageUri TEXT NOT NULL DEFAULT ''")
            }
        }


        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN locationTimestampMillis INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN locationAgeMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN locationProvider TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN placeName TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN addressLine TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN locality TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN subAdminArea TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN adminArea TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE pothole_reports ADD COLUMN countryName TEXT NOT NULL DEFAULT ''")
            }
        }

        fun get(context: Context): AppDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "pothole_reports.db"
            )
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                .also { INSTANCE = it }
        }
    }
}
