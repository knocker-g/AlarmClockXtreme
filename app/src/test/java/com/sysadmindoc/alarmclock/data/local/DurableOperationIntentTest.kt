package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DurableOperationIntentTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var intentDao: DurableOperationIntentDao

    private val validIntent = DurableOperationIntent(
        operationToken = "intent-token-100",
        alarmId = 42L,
        operationEpoch = 1L,
        incarnationId = "inc-token-100",
        operationKind = "SET",
        expectedOwnerToken = "owner-token-42",
        expectedScheduleRevision = 1L,
        expectedRestoreEpoch = 100L,
        phase = DurableOperationIntent.PHASE_PENDING,
        intentRevision = 1L
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        intentDao = database.durableOperationIntentDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `1 - Fresh Room DB opens and installs SQLite triggers`() = runBlocking {
        intentDao.insertRawIntent(validIntent)
        val retrieved = intentDao.getIntentByToken("intent-token-100")
        assertNotNull(retrieved)
        assertEquals(validIntent, retrieved)

        // Inspect sqlite_master for trigger installation on fresh DB
        val cursor = database.openHelper.readableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' AND name LIKE 'trigger_durable_intent%'"
        )
        val triggerNames = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                triggerNames.add(it.getString(0))
            }
        }
        assertTrue("Triggers must be installed on Fresh DB", triggerNames.size >= 6)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `3 - Duplicate unresolved INSERT via raw SQL is rejected by SQLite trigger`(): Unit = runBlocking {
        val unresolved1 = validIntent.copy(operationToken = "tok-1", phase = DurableOperationIntent.PHASE_PENDING)
        val unresolved2 = validIntent.copy(operationToken = "tok-2", phase = DurableOperationIntent.PHASE_IN_FLIGHT)

        intentDao.insertRawIntent(unresolved1)
        // SQLite's BEFORE INSERT trigger rejects second unresolved intent for alarmId = 42L
        intentDao.insertRawIntent(unresolved2)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `4 - Raw UPDATE creating duplicate unresolved intent is rejected by SQLite trigger`(): Unit = runBlocking {
        val unresolved1 = validIntent.copy(operationToken = "tok-1", alarmId = 42L, phase = DurableOperationIntent.PHASE_PENDING)
        val completed2 = validIntent.copy(operationToken = "tok-2", alarmId = 42L, phase = DurableOperationIntent.PHASE_COMPLETED)

        intentDao.insertRawIntent(unresolved1)
        intentDao.insertRawIntent(completed2)

        // Raw SQL UPDATE attempting to transition completed2 -> IN_FLIGHT causes second unresolved for alarm 42L
        database.openHelper.writableDatabase.execSQL(
            "UPDATE durable_operation_intent SET phase = 'IN_FLIGHT' WHERE operationToken = 'tok-2'"
        )
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `5 - Invalid phase INSERT is rejected by SQLite trigger`(): Unit = runBlocking {
        val invalidPhaseIntent = validIntent.copy(phase = "INVALID_PHASE_STRING")
        intentDao.insertRawIntent(invalidPhaseIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `6 - Invalid phase UPDATE is rejected by SQLite trigger`(): Unit = runBlocking {
        intentDao.insertRawIntent(validIntent)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE durable_operation_intent SET phase = 'UNKNOWN_PHASE' WHERE operationToken = 'intent-token-100'"
        )
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `7 - Blank operationToken is rejected by SQLite trigger`(): Unit = runBlocking {
        val blankTokenIntent = validIntent.copy(operationToken = "   ")
        intentDao.insertRawIntent(blankTokenIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `8 - Nonpositive alarmId is rejected by SQLite trigger`(): Unit = runBlocking {
        val zeroAlarmIdIntent = validIntent.copy(alarmId = 0L)
        intentDao.insertRawIntent(zeroAlarmIdIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `8 - Nonpositive operationEpoch is rejected by SQLite trigger`(): Unit = runBlocking {
        val zeroEpochIntent = validIntent.copy(operationEpoch = 0L)
        intentDao.insertRawIntent(zeroEpochIntent)
    }

    @Test
    fun `9 - Distinct alarm IDs with unresolved operations succeed at SQLite layer`() = runBlocking {
        val alarm42Intent = validIntent.copy(alarmId = 42L, operationToken = "tok-42", phase = DurableOperationIntent.PHASE_PENDING)
        val alarm99Intent = validIntent.copy(alarmId = 99L, operationToken = "tok-99", phase = DurableOperationIntent.PHASE_PENDING)

        intentDao.insertRawIntent(alarm42Intent)
        intentDao.insertRawIntent(alarm99Intent)

        assertEquals("tok-42", intentDao.getUnresolvedIntentsByAlarmId(42L).first().operationToken)
        assertEquals("tok-99", intentDao.getUnresolvedIntentsByAlarmId(99L).first().operationToken)
    }

    @Test
    fun `10 - Terminal historical records are retained for replay audit`() = runBlocking {
        val completed = validIntent.copy(operationToken = "tok-comp", phase = DurableOperationIntent.PHASE_COMPLETED)
        val expired = validIntent.copy(operationToken = "tok-exp", phase = DurableOperationIntent.PHASE_EXPIRED)

        intentDao.insertRawIntent(completed)
        intentDao.insertRawIntent(expired)

        val all = intentDao.getAllIntents()
        assertEquals(2, all.size)
    }

    @Test
    fun `12 - Database close and reopen retains effective triggers and constraints`() = runBlocking {
        val dbFile = File(context.cacheDir, "test_reopen_triggers.db")
        if (dbFile.exists()) dbFile.delete()

        var fileDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        fileDb.durableOperationIntentDao().insertRawIntent(validIntent)
        fileDb.close()

        // Reopen same database file
        fileDb = Room.databaseBuilder(context, AlarmDatabase::class.java, dbFile.absolutePath)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()

        var thrown = false
        try {
            val duplicate = validIntent.copy(operationToken = "tok-dup-reopen", phase = DurableOperationIntent.PHASE_IN_FLIGHT)
            fileDb.durableOperationIntentDao().insertRawIntent(duplicate)
        } catch (_: SQLiteConstraintException) {
            thrown = true
        }

        fileDb.close()
        if (dbFile.exists()) dbFile.delete()

        assertTrue("Triggers must remain active on reopened file DB", thrown)
    }

    @Test
    fun `13 - Valid phase vocabulary helper functions correctly`() {
        assertTrue(DurableOperationIntent.isValidPhase("PENDING"))
        assertTrue(DurableOperationIntent.isValidPhase("IN_FLIGHT"))
        assertTrue(DurableOperationIntent.isValidPhase("COMPLETED"))
        assertTrue(DurableOperationIntent.isValidPhase("ABORTED"))
        assertTrue(DurableOperationIntent.isValidPhase("EXPIRED"))

        assertFalse(DurableOperationIntent.isValidPhase("INVALID_PHASE"))
        assertFalse(DurableOperationIntent.isValidPhase(""))
    }
}
