package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import android.database.sqlite.SQLiteDatabase

enum class DatabaseCompatibilityStatus {
    COMPATIBLE,
    INCOMPATIBLE_NEWER_SCHEMA
}

object DatabaseCompatibilityGate {
    const val CURRENT_SCHEMA_VERSION = AlarmDatabase.VERSION

    fun preflight(context: Context, dbName: String = "alarm_clock.db"): DatabaseCompatibilityStatus {
        val dbFile = context.getDatabasePath(dbName)
        if (!dbFile.exists()) {
            return DatabaseCompatibilityStatus.COMPATIBLE
        }

        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val storedVersion = db.version
            if (storedVersion > CURRENT_SCHEMA_VERSION) {
                DatabaseCompatibilityStatus.INCOMPATIBLE_NEWER_SCHEMA
            } else {
                DatabaseCompatibilityStatus.COMPATIBLE
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to inspect database version for compatibility preflight", e)
        } finally {
            try {
                db?.close()
            } catch (_: Exception) {}
        }
    }
}
