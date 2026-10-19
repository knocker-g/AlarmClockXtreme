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
import org.junit.Assert.assertNull
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
    fun `insertIntent and getByToken stores and reads durable operation intent correctly`() = runBlocking {
        intentDao.insertIntent(validIntent)

        val retrieved = intentDao.getIntentByToken("intent-token-100")
        assertNotNull(retrieved)
        assertEquals(validIntent, retrieved)
    }

    @Test(expected = SQLiteConstraintException::class)
    fun `duplicate operationToken throws SQLiteConstraintException on ABORT strategy`(): Unit = runBlocking {
        intentDao.insertIntent(validIntent)

        val duplicateTokenIntent = validIntent.copy(
            operationEpoch = 2L,
            phase = DurableOperationIntent.PHASE_COMPLETED
        )
        // ABORT strategy prevents silent overwrite or deletion of existing token
        intentDao.insertIntent(duplicateTokenIntent)
    }

    @Test
    fun `getUnresolvedIntentsByAlarmId returns PENDING and IN_FLIGHT intents only`() = runBlocking {
        val pendingIntent = validIntent.copy(operationToken = "tok-pending", phase = DurableOperationIntent.PHASE_PENDING)
        val inFlightIntent = validIntent.copy(operationToken = "tok-inflight", phase = DurableOperationIntent.PHASE_IN_FLIGHT)
        val completedIntent = validIntent.copy(operationToken = "tok-completed", phase = DurableOperationIntent.PHASE_COMPLETED)
        val abortedIntent = validIntent.copy(operationToken = "tok-aborted", phase = DurableOperationIntent.PHASE_ABORTED)

        intentDao.insertIntent(pendingIntent)
        intentDao.insertIntent(inFlightIntent)
        intentDao.insertIntent(completedIntent)
        intentDao.insertIntent(abortedIntent)

        val unresolved = intentDao.getUnresolvedIntentsByAlarmId(42L)
        assertEquals(2, unresolved.size)
        val unresolvedTokens = unresolved.map { it.operationToken }.toSet()
        assertTrue(unresolvedTokens.contains("tok-pending"))
        assertTrue(unresolvedTokens.contains("tok-inflight"))

        val allIntents = intentDao.getIntentsByAlarmId(42L)
        assertEquals(4, allIntents.size)
    }

    @Test
    fun `historical terminal intents are retained for replay audit`() = runBlocking {
        val completed = validIntent.copy(operationToken = "tok-comp", phase = DurableOperationIntent.PHASE_COMPLETED)
        val expired = validIntent.copy(operationToken = "tok-exp", phase = DurableOperationIntent.PHASE_EXPIRED)

        intentDao.insertIntent(completed)
        intentDao.insertIntent(expired)

        val all = intentDao.getAllIntents()
        assertEquals(2, all.size)
    }

    @Test
    fun `read queries perform no state mutations`() = runBlocking {
        intentDao.insertIntent(validIntent)

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
