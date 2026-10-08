package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
        val exception = openResult.exceptionOrNull()
        assertNotNull("Exception must be present on open failure", exception)
        val isIncompatibilityOrDowngradeError = exception is IllegalStateException ||
            exception is android.database.sqlite.SQLiteException ||
            exception?.cause is IllegalStateException ||
            exception?.cause is android.database.sqlite.SQLiteException ||
            exception?.message?.contains("downgrade", ignoreCase = true) == true ||
            exception?.message?.contains("version", ignoreCase = true) == true ||
            exception?.message?.contains("Can't downgrade", ignoreCase = true) == true
        assertTrue("Failure must be categorized as version/downgrade incompatibility: ${exception?.message}", isIncompatibilityOrDowngradeError)

        // Verify file and sentinel data are preserved and NOT destructively wiped
        assertTrue("DB file must still exist after Room open failure", dbFile.exists())
        val readDb = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        val cursor = readDb.rawQuery("SELECT val FROM sentinel WHERE id = 1", null)
        assertTrue("Sentinel data must be preserved", cursor.moveToFirst())
        assertEquals("sentinel_data", cursor.getString(0))
        cursor.close()
        readDb.close()
        roomDb.close()
    }

    @Test
    fun compatibleDatabaseOpensNormallyWithRoom() {
        val roomDb = Room.databaseBuilder(context, AlarmDatabase::class.java, "alarm_clock.db")
            .addMigrations(*AlarmDatabase.ALL_MIGRATIONS)
            .allowMainThreadQueries()
            .build()

        val db = roomDb.openHelper.writableDatabase
        assertNotNull(db)
        assertTrue(db.isOpen)
        assertEquals(AlarmDatabase.VERSION, db.version)
        roomDb.close()
    }

    @Test
    fun explicitUpgradeMigrationSucceeds() {
        dbFile.parentFile?.mkdirs()
        val initDb = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        initDb.version = 21
        initDb.execSQL("CREATE TABLE alarms (id INTEGER PRIMARY KEY NOT NULL)")
        initDb.close()

        val frameworkDb = FrameworkSQLiteOpenHelperFactory()
            .create(
                SupportSQLiteOpenHelper.Configuration.builder(context)
                    .name("alarm_clock.db")
                    .callback(object : SupportSQLiteOpenHelper.Callback(22) {
                        override fun onCreate(db: SupportSQLiteDatabase) {}
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                            AlarmDatabase.MIGRATION_21_22.migrate(db)
                        }
                    })
                    .build()
            ).writableDatabase

        assertNotNull(frameworkDb)
        val cursor = frameworkDb.query(SimpleSQLiteQuery("PRAGMA table_info(alarms)"))
        var hasShiftPattern = false
        while (cursor.moveToNext()) {
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex >= 0 && cursor.getString(nameIndex) == "shiftPattern") {
                hasShiftPattern = true
            }
        }
        cursor.close()
        frameworkDb.close()
        assertTrue("MIGRATION_21_22 must add shiftPattern column to alarms table", hasShiftPattern)
    }

    @Test(expected = IllegalStateException::class)
    fun preflightOnCorruptFileFailsClosed() {
        dbFile.parentFile?.mkdirs()
        dbFile.writeText("corrupt non-sqlite payload")
        DatabaseCompatibilityGate.preflight(context)
    }
}
