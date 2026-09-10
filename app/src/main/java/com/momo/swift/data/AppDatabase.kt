package com.momo.swift.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room database for the Swift Agent application.
 */
@Database(
    entities = [TransactionLogEntry::class, SavedContact::class],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionLogDao(): TransactionLogDao
    abstract fun savedContactDao(): SavedContactDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** Migration: create the saved_contacts table added in v2. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS saved_contacts (
                        phone TEXT NOT NULL PRIMARY KEY,
                        nickname TEXT NOT NULL DEFAULT '',
                        lastUsed INTEGER NOT NULL,
                        useCount INTEGER NOT NULL DEFAULT 1
                    )
                """.trimIndent())
                // Seed from existing transaction log so current users don't lose history
                db.execSQL("""
                    INSERT OR IGNORE INTO saved_contacts (phone, nickname, lastUsed, useCount)
                    SELECT phone, '', MAX(timestamp), COUNT(*)
                    FROM transaction_log
                    WHERE phone != ''
                    GROUP BY phone
                """.trimIndent())
            }
        }

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "j330momo.db"
                )
                .addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
            }
        }
    }
}
