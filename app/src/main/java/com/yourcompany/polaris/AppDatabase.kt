package com.yourcompany.polaris

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

// In AppDatabase.kt

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Change the version from 1 to 2
@Database(entities = [NetworkLog::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun networkLogDao(): NetworkLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        // Define the migration
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE network_logs ADD COLUMN rscp INTEGER")
                database.execSQL("ALTER TABLE network_logs ADD COLUMN ecno INTEGER")
                database.execSQL("ALTER TABLE network_logs ADD COLUMN rxlev INTEGER")
                database.execSQL("ALTER TABLE network_logs ADD COLUMN arfcn INTEGER")
                database.execSQL("ALTER TABLE network_logs ADD COLUMN band TEXT")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "polaris_database"
                )
                    .addMigrations(MIGRATION_1_2) // Add the migration to the builder
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}