package com.sysadmindoc.alarmclock.data.local

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
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
            .build()
        intentDao = database.durableOperationIntentDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `insertValidatedIntent and getByToken stores and reads durable operation intent correctly`() = runBlocking {
        intentDao.insertValidatedIntent(validIntent)

        val retrieved = intentDao.getIntentByToken("intent-token-100")
        assertNotNull(retrieved)
        assertEquals(validIntent, retrieved)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `duplicate operationToken throws SQLiteConstraintException on ABORT strategy`(): Unit = runBlocking {
        intentDao.insertValidatedIntent(validIntent)

        val duplicateTokenIntent = validIntent.copy(
            operationEpoch = 2L,
            phase = DurableOperationIntent.PHASE_COMPLETED
        )
        // ABORT strategy prevents silent overwrite or deletion of existing token
        intentDao.insertValidatedIntent(duplicateTokenIntent)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `same alarm with two unresolved operations throws SQLiteConstraintException`(): Unit = runBlocking {
        val unresolved1 = validIntent.copy(operationToken = "tok-1", phase = DurableOperationIntent.PHASE_PENDING)
        val unresolved2 = validIntent.copy(operationToken = "tok-2", phase = DurableOperationIntent.PHASE_IN_FLIGHT)

        intentDao.insertValidatedIntent(unresolved1)
        // Attempting to insert a second unresolved intent for alarmId = 42L fails closed
        intentDao.insertValidatedIntent(unresolved2)
    }

    @Test
    fun `two different alarms with unresolved operations succeeds`() = runBlocking {
        val alarm42Intent = validIntent.copy(alarmId = 42L, operationToken = "tok-42", phase = DurableOperationIntent.PHASE_PENDING)
        val alarm99Intent = validIntent.copy(alarmId = 99L, operationToken = "tok-99", phase = DurableOperationIntent.PHASE_PENDING)

        intentDao.insertValidatedIntent(alarm42Intent)
        intentDao.insertValidatedIntent(alarm99Intent)

        assertEquals(1, intentDao.getUnresolvedIntentsByAlarmId(42L).size)
        assertEquals(1, intentDao.getUnresolvedIntentsByAlarmId(99L).size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid phase at persistence boundary throws IllegalArgumentException`(): Unit = runBlocking {
        val invalidPhaseIntent = validIntent.copy(phase = "UNKNOWN_PHASE")
        intentDao.insertValidatedIntent(invalidPhaseIntent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank operation token throws IllegalArgumentException`(): Unit = runBlocking {
        val blankTokenIntent = validIntent.copy(operationToken = "   ")
        intentDao.insertValidatedIntent(blankTokenIntent)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `nonpositive epoch throws IllegalArgumentException`(): Unit = runBlocking {
        val zeroEpochIntent = validIntent.copy(operationEpoch = 0L)
        intentDao.insertValidatedIntent(zeroEpochIntent)
    }

    @Test
    fun `getUnresolvedIntentsByAlarmId returns PENDING and IN_FLIGHT intents only`() = runBlocking {
        val pendingIntent = validIntent.copy(operationToken = "tok-pending", phase = DurableOperationIntent.PHASE_PENDING)
        val completedIntent = validIntent.copy(operationToken = "tok-completed", phase = DurableOperationIntent.PHASE_COMPLETED)
        val abortedIntent = validIntent.copy(operationToken = "tok-aborted", phase = DurableOperationIntent.PHASE_ABORTED)

        intentDao.insertValidatedIntent(pendingIntent)
        intentDao.insertValidatedIntent(completedIntent)
        intentDao.insertValidatedIntent(abortedIntent)

        val unresolved = intentDao.getUnresolvedIntentsByAlarmId(42L)
        assertEquals(1, unresolved.size)
        assertEquals("tok-pending", unresolved.first().operationToken)

        val allIntents = intentDao.getIntentsByAlarmId(42L)
        assertEquals(3, allIntents.size)
    }

    @Test
    fun `historical terminal intents are retained for replay audit`() = runBlocking {
        val completed = validIntent.copy(operationToken = "tok-comp", phase = DurableOperationIntent.PHASE_COMPLETED)
        val expired = validIntent.copy(operationToken = "tok-exp", phase = DurableOperationIntent.PHASE_EXPIRED)

        intentDao.insertValidatedIntent(completed)
        intentDao.insertValidatedIntent(expired)

        val all = intentDao.getAllIntents()
        assertEquals(2, all.size)
    }

    @Test
    fun `read queries perform no state mutations`() = runBlocking {
        intentDao.insertValidatedIntent(validIntent)

        val read1 = intentDao.getIntentByToken("intent-token-100")
        val read2 = intentDao.getAllIntents()

        assertEquals(read1, validIntent)
        assertEquals(1, read2.size)
    }

    @Test
    fun `valid phase vocabulary is correctly identified`() {
        assertTrue(DurableOperationIntent.isValidPhase("PENDING"))
        assertTrue(DurableOperationIntent.isValidPhase("IN_FLIGHT"))
        assertTrue(DurableOperationIntent.isValidPhase("COMPLETED"))
        assertTrue(DurableOperationIntent.isValidPhase("ABORTED"))
        assertTrue(DurableOperationIntent.isValidPhase("EXPIRED"))

        assertFalse(DurableOperationIntent.isValidPhase("INVALID_PHASE"))
        assertFalse(DurableOperationIntent.isValidPhase(""))
    }
}
