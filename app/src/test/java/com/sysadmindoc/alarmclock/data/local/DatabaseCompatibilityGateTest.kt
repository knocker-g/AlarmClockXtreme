package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
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
    fun preflightWithNewerVersionReturnsIncompatible() {
        dbFile.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(dbFile, null)
        db.version = AlarmDatabase.VERSION + 5
        db.close()

        val status = DatabaseCompatibilityGate.preflight(context)
        assertEquals(DatabaseCompatibilityStatus.INCOMPATIBLE_NEWER_SCHEMA, status)
    }
}
