package com.sysadmindoc.alarmclock.directboot

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Isolated concurrency proof test for KNO-75 B1C3.
 *
 * Verifies that Room's [withTransaction] boundary strictly serializes concurrent
 * CE mutations, preventing interleaving or stale commits while a transaction is held.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DirectBootTransactionConcurrencyProofTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var repository: AlarmRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        repository = AlarmRepository(
            database = database,
            dao = database.alarmDao(),
            groupDao = database.alarmGroupDao()
        )

        runBlocking {
            database.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 1L, bootstrapState = "READY")
            )
        }
    }

    @After
    fun tearDown() {
        database.close()
    }

    private suspend fun seedAlarmAndAuthority(
        alarmId: Long,
        triggerTime: Long,
        scheduleRevision: Long = 1L
    ): Pair<Alarm, ScheduleAuthority> {
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 1,
            revision = 1
        )
        database.alarmDao().insert(alarm)

        val authority = ScheduleAuthority(
            alarmId = alarmId,
            instanceToken = "auth_${alarmId}_token",
            definitionRevision = 1L,
            scheduleRevision = scheduleRevision,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = triggerTime,
            reconcileState = "SETTLED",
            restoreEpoch = 1L,
            actionKey = null
        )
        database.scheduleAuthorityDao().insertAuthority(authority)
        return Pair(alarm, authority)
    }

    @Test
    fun repositoryAlarmMutation_isBlockedWhileTransactionIsHeld() = runBlocking(Dispatchers.IO) {
        val alarmId = 100L
        seedAlarmAndAuthority(alarmId, triggerTime = 1000L)

        val events = CopyOnWriteArrayList<String>()
        val aEntered = CompletableDeferred<Unit>()
        val aRelease = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()

        // Transaction A: Simulates Strategy C Projection Validation holding Room transaction
        val jobA = launch(Dispatchers.IO) {
            database.withTransaction {
                events.add("A_ENTERED")
                val alarms = database.alarmDao().getEnabledNormalAlarms()
                assertEquals(1, alarms.size)
                aEntered.complete(Unit)

                // Hold transaction at barrier
                aRelease.await()
                events.add("A_COMPLETED")
            }
        }

        withTimeout(2000L) { aEntered.await() }

        // Transaction B: Concurrent repository-style mutation (e.g. user toggles alarm in UI)
        val jobB = launch(Dispatchers.IO) {
            bStarted.complete(Unit)
            events.add("B_CALLING_SET_ENABLED")
            repository.setEnabled(alarmId, enabled = false, nextTrigger = 0L)
            events.add("B_COMPLETED")
        }

        withTimeout(2000L) { bStarted.await() }

        // Bounded delay to verify B cannot commit while A is held
        delay(150L)
        assertFalse("Transaction B must NOT complete while A holds transaction", jobB.isCompleted)
        assertFalse("B_COMPLETED event must NOT have occurred", events.contains("B_COMPLETED"))

        // Release Transaction A
        aRelease.complete(Unit)
        withTimeout(2000L) { jobA.join() }

        // Now Transaction B must unblock and complete
        withTimeout(2000L) { jobB.join() }
        assertTrue("Transaction B must complete after A releases", jobB.isCompleted)
        assertTrue("B_COMPLETED event must have occurred", events.contains("B_COMPLETED"))

        // Verify strictly serialized execution ordering
        val indexACompleted = events.indexOf("A_COMPLETED")
        val indexBCompleted = events.indexOf("B_COMPLETED")
        assertTrue("A must complete before B can complete", indexACompleted < indexBCompleted)

        // Verify post-condition: alarm is now disabled in DB
        val finalAlarm = database.alarmDao().getById(alarmId)
        assertNotNull(finalAlarm)
        assertFalse("Alarm must be disabled after B completes", finalAlarm!!.isEnabled)
    }

    @Test
    fun directDaoCasUpdate_isBlockedWhileTransactionIsHeld() = runBlocking(Dispatchers.IO) {
        val alarmId = 200L
        val triggerTime = 2000L
        seedAlarmAndAuthority(alarmId, triggerTime, scheduleRevision = 10L)

        val events = CopyOnWriteArrayList<String>()
        val aEntered = CompletableDeferred<Unit>()
        val aRelease = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()

        // Transaction A: Holds Room transaction
        val jobA = launch(Dispatchers.IO) {
            database.withTransaction {
                events.add("A_ENTERED")
                val authorities = database.scheduleAuthorityDao().getAllAuthorities()
                assertEquals(1, authorities.size)
                aEntered.complete(Unit)

                aRelease.await()
                events.add("A_COMPLETED")
            }
        }

        withTimeout(2000L) { aEntered.await() }

        // Transaction B: Direct DAO CAS claim update
        val jobB = launch(Dispatchers.IO) {
            bStarted.complete(Unit)
            events.add("B_CALLING_CAS")
            val rows = database.scheduleAuthorityDao().casClaimAuthority(
                alarmId = alarmId,
                instanceToken = "auth_${alarmId}_token",
                restoreEpoch = 1L,
                expectedRevision = 10L,
                expectedLifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTime + 500L,
                actionKey = "action_key_test"
            )
            assertEquals("CAS claim must succeed when executed", 1, rows)
            events.add("B_COMPLETED")
        }

        withTimeout(2000L) { bStarted.await() }

        delay(150L)
        assertFalse("Direct CAS update B must NOT complete while A holds transaction", jobB.isCompleted)
        assertFalse("B_COMPLETED must not be in events", events.contains("B_COMPLETED"))

        // Release A
        aRelease.complete(Unit)
        withTimeout(2000L) { jobA.join() }
        withTimeout(2000L) { jobB.join() }

        assertTrue("A must complete before B completes", events.indexOf("A_COMPLETED") < events.indexOf("B_COMPLETED"))

        val finalAuth = database.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
        assertNotNull(finalAuth)
        assertEquals("scheduleRevision must increment to 11", 11L, finalAuth!!.scheduleRevision)
        assertEquals("reconcileState must transition to RECONCILING", "RECONCILING", finalAuth.reconcileState)
    }

    @Test
    fun cancellationWhileTransactionIsHeld_rollsBackAndUnblocksSubsequentTransactions() = runBlocking(Dispatchers.IO) {
        val alarmId = 300L
        seedAlarmAndAuthority(alarmId, triggerTime = 3000L)

        val aEntered = CompletableDeferred<Unit>()
        val aBlocker = CompletableDeferred<Unit>()

        val jobA = launch(Dispatchers.IO) {
            try {
                database.withTransaction {
                    // Perform an uncommitted change inside A
                    database.alarmDao().setEnabled(alarmId, enabled = false, nextTrigger = 0L)
                    aEntered.complete(Unit)
                    aBlocker.await() // Holds transaction until cancelled
                }
            } catch (e: CancellationException) {
                // Expected cancellation propagation
                throw e
            }
        }

        withTimeout(2000L) { aEntered.await() }

        // Cancel job A while inside transaction
        jobA.cancel()
        jobA.join()
        assertTrue("Job A must be cancelled", jobA.isCancelled)

        // Verify that A's uncommitted mutation rolled back
        val currentAlarm = database.alarmDao().getById(alarmId)
        assertNotNull(currentAlarm)
        assertTrue("Alarm must remain ENABLED because A rolled back", currentAlarm!!.isEnabled)

        // Subsequent transaction B must proceed immediately without hang
        val bCompleted = CompletableDeferred<Boolean>()
        launch(Dispatchers.IO) {
            database.withTransaction {
                val alarm = database.alarmDao().getById(alarmId)
                bCompleted.complete(alarm?.isEnabled == true)
            }
        }

        val result = withTimeout(2000L) { bCompleted.await() }
        assertTrue("Transaction B must succeed and read valid rolled-back state", result)
    }

    @Test
    fun exceptionBeforeTransactionCompletion_rollsBackCleanly() = runBlocking(Dispatchers.IO) {
        val alarmId = 400L
        seedAlarmAndAuthority(alarmId, triggerTime = 4000L)

        var thrownException: Throwable? = null
        try {
            database.withTransaction {
                database.alarmDao().setEnabled(alarmId, enabled = false, nextTrigger = 0L)
                throw IllegalStateException("Simulated mid-transaction failure")
            }
        } catch (e: Throwable) {
            thrownException = e
        }

        assertNotNull("Exception must be caught", thrownException)
        assertEquals("Simulated mid-transaction failure", thrownException?.message)

        // Verify clean rollback: alarm is still enabled
        val alarm = database.alarmDao().getById(alarmId)
        assertNotNull(alarm)
        assertTrue("Alarm must remain enabled after exception rollback", alarm!!.isEnabled)
    }

    @Test
    fun repeatedSimultaneousProjectionAttempts_areStrictlySerialized() = runBlocking(Dispatchers.IO) {
        val alarmId = 500L
        seedAlarmAndAuthority(alarmId, triggerTime = 5000L)

        val executionLog = CopyOnWriteArrayList<String>()
        val startSignal = CompletableDeferred<Unit>()

        val jobs = (1..3).map { index ->
            launch(Dispatchers.IO) {
                startSignal.await()
                database.withTransaction {
                    executionLog.add("START_$index")
                    delay(50L) // Simulate bounded work inside transaction
                    executionLog.add("END_$index")
                }
            }
        }

        startSignal.complete(Unit)
        jobs.forEach { withTimeout(5000L) { it.join() } }

        assertEquals("All 3 transactions must execute start and end (total 6 events)", 6, executionLog.size)

        // Verify strict serialization: each transaction's END must immediately follow its START
        for (i in 0 until executionLog.size step 2) {
            val startEvent = executionLog[i]
            val endEvent = executionLog[i + 1]
            val startIndex = startEvent.substringAfter("START_")
            val endIndex = endEvent.substringAfter("END_")
            assertEquals("Transaction execution must not be interleaved", startIndex, endIndex)
        }
    }
}
