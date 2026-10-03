package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DatabaseCompatibilityGateTest {
    private lateinit var context: Context
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("alarm_clock.db")
        if (dbFile.exists()) dbFile.delete()
    }

    @After
    fun tearDown() {
        if (dbFile.exists()) dbFile.delete()
    }

    @Test
    fun preflightWithNoDbFileReturnsCompatible() {
        val status = DatabaseCompatibilityGate.preflight(context)
        assertEquals(DatabaseCompatibilityStatus.COMPATIBLE, status)
    }

    @Test
    fun preflightWithCurrentVersionReturnsCompatible() {
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.version = AlarmDatabase.VERSION
        db.close()

        val status = DatabaseCompatibilityGate.preflight(context)
        assertEquals(DatabaseCompatibilityStatus.COMPATIBLE, status)
    }

    @Test
    fun preflightWithOlderVersionReturnsCompatible() {
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.version = 20
        db.close()

        val status = DatabaseCompatibilityGate.preflight(context)
        assertEquals(DatabaseCompatibilityStatus.COMPATIBLE, status)
    }

    @Test
    fun preflightWithNewerVersionReturnsIncompatible() {
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.version = AlarmDatabase.VERSION + 5
        db.close()

        val status = DatabaseCompatibilityGate.preflight(context)
        assertEquals(DatabaseCompatibilityStatus.INCOMPATIBLE_NEWER_SCHEMA, status)
    }

    @Test
    fun preflightIsReadOnlyAndPreservesSentinelData() {
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.version = AlarmDatabase.VERSION + 5
        db.execSQL("CREATE TABLE sentinel (id INTEGER PRIMARY KEY, val TEXT)")
        db.execSQL("INSERT INTO sentinel (id, val) VALUES (1, 'sentinel_data')")
        db.close()

        val status = DatabaseCompatibilityGate.preflight(context)
        assertEquals(DatabaseCompatibilityStatus.INCOMPATIBLE_NEWER_SCHEMA, status)

        // Verify sentinel data remains intact
        val readDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = readDb.rawQuery("SELECT val FROM sentinel WHERE id = 1", null)
        assertTrue(cursor.moveToFirst())
        assertEquals("sentinel_data", cursor.getString(0))
        cursor.close()
        readDb.close()
    }

    @Test
    fun attemptingRoomOpenOnNewerVersionFailsAndPreservesFile() {
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.version = AlarmDatabase.VERSION + 5
        db.execSQL("CREATE TABLE sentinel (id INTEGER PRIMARY KEY, val TEXT)")
        db.execSQL("INSERT INTO sentinel (id, val) VALUES (1, 'sentinel_data')")
        db.close()

        val roomDb = Room.databaseBuilder(context, AlarmDatabase::class.java, "alarm_clock.db")
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .build()

        val openResult = runCatching {
            roomDb.openHelper.writableDatabase
        }
        assertTrue("Attempting to open newer DB with Room without fallback must fail", openResult.isFailure)

        // Verify file and sentinel data are preserved and NOT destructively wiped
        assertTrue("DB file must still exist after Room open failure", dbFile.exists())
        val readDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = readDb.rawQuery("SELECT val FROM sentinel WHERE id = 1", null)
        assertTrue("Sentinel data must be preserved", cursor.moveToFirst())
        assertEquals("sentinel_data", cursor.getString(0))
        cursor.close()
        readDb.close()
    }

    @Test(expected = IllegalStateException::class)
    fun preflightOnCorruptFileFailsClosed() {
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("corrupt non-sqlite payload")
        DatabaseCompatibilityGate.preflight(context)
    }
}
