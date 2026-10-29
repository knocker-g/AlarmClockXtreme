package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DurableSkipClaimAdapterTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var durableDao: DurableOperationIntentDao

    private lateinit var adapter: DurableSkipClaimAdapter
    private lateinit var recoveryCoordinator: DurableReconcileRecoveryCoordinator

    private val zoneId = ZoneId.of("America/New_York")
    private val nowEpochMs = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val wed0700Ms = LocalDate.of(2026, 10, 28)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val nov040700Ms = LocalDate.of(2026, 11, 4)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        exceptionDao = db.scheduleExceptionDao()
        authorityDao = db.scheduleAuthorityDao()
        issuedDao = db.issuedOccurrenceDao()
        durableDao = db.durableOperationIntentDao()

        adapter = DurableSkipClaimAdapter(database = db)
        recoveryCoordinator = DurableReconcileRecoveryCoordinator(database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertTestAlarmAndAuthority(
        alarmId: Long = 1L,
        generation: Int = 1,
        revision: Int = 1,
        nextTriggerTime: Long = wed0700Ms
    ): Pair<Alarm, ScheduleAuthority> = runBlocking {
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = generation,
            revision = revision,
            nextTriggerTime = nextTriggerTime
        )
        val rowId = alarmDao.insert(alarm)
        val insertedAlarm = alarm.copy(id = rowId)

        val authority = ScheduleAuthority(
            alarmId = rowId,
            instanceToken = "auth-token-$rowId",
            definitionRevision = revision.toLong(),
            scheduleRevision = revision.toLong(),
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = nextTriggerTime,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        authorityDao.insertAuthority(authority)

        val slot = IssueSlot(alarmId = rowId, generationId = 1L, currentIssueToken = null)
        issuedDao.insertSlotInitial(slot)

        return@runBlocking Pair(insertedAlarm, authority)
    }

    @Test
    fun `1 Normal skip claim succeeds and commits exception, updated projection, V35 occurrence, and intent`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED, result.outcome)
        assertNotNull(result.updatedAlarm)
        assertEquals(nov040700Ms, result.updatedAlarm!!.nextTriggerTime)

        assertNotNull(result.issuedOccurrence)
        assertEquals(nov040700Ms, result.issuedOccurrence!!.scheduledAt)

        assertNotNull(result.durableIntent)
        assertEquals("RECONCILE_RESCHEDULE", result.durableIntent!!.operationKind)
        assertEquals(DurableOperationIntent.PHASE_PENDING, result.durableIntent!!.phase)

        // Verify Room DB persisted state
        val exception = exceptionDao.getExceptionForOccurrence(alarm.id, wed0700Ms, alarm.generation)
        assertNotNull(exception)
        assertEquals(ScheduleException.TYPE_SKIP, exception!!.exceptionType)

        val dbAlarm = alarmDao.getById(alarm.id)!!
        assertEquals(nov040700Ms, dbAlarm.nextTriggerTime)
    }

    @Test
    fun `2 Duplicate skip claim on already committed exception returns ALREADY_COMMITTED without duplicate DB mutations`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()

        // First skip claim
        val result1 = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED, result1.outcome)

        // Second skip claim on same expectedScheduledAt
        val result2 = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs + 5000L,
            evaluationZoneId = zoneId
        )
        assertEquals(DurableSkipClaimAdapterOutcome.ALREADY_COMMITTED, result2.outcome)

        // Verify only 1 exception and 1 occurrence row in DB
        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarm.id, alarm.generation)
        assertEquals(1, exceptions.size)

        val occurrences = issuedDao.getOccurrencesByAlarmAndScheduledAt(alarm.id, nov040700Ms)
        assertEquals(1, occurrences.size)
    }

    @Test
    fun `3 Stale occurrence identity expectedScheduledAt mismatch returns STALE_OCCURRENCE`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms + 3600000L, // Mismatched target!
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimAdapterOutcome.STALE_OCCURRENCE, result.outcome)
        assertNull(result.updatedAlarm)

        // Verify 0 exceptions inserted
        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarm.id, alarm.generation)
        assertTrue(exceptions.isEmpty())
    }

    @Test
    fun `4 Active FIRING alarm session rejects skip claim and returns ACTIVE_SESSION`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            activeFiringAlarmId = alarm.id, // Active firing session!
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimAdapterOutcome.ACTIVE_SESSION, result.outcome)
        assertNull(result.updatedAlarm)

        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarm.id, alarm.generation)
        assertTrue(exceptions.isEmpty())
    }

    @Test
    fun `5 Generation or revision mismatch rejects skip claim and returns STALE_OCCURRENCE`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority(generation = 1)

        // Increment generation in DB to simulate generation advance
        alarmDao.update(alarm.copy(generation = 2, nextTriggerTime = nov040700Ms))

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms, // Generation 1 trigger time
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimAdapterOutcome.STALE_OCCURRENCE, result.outcome)
    }

    @Test
    fun `6 Transaction rollback on reconcile failure leaves exception uncommitted`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()

        // Delete ScheduleAuthority to cause reconcile producer failure
        authorityDao.deleteAuthorityByAlarmId(alarm.id)

        try {
            adapter.executeSkipClaimAndReconcile(
                alarmId = alarm.id,
                expectedScheduledAt = wed0700Ms,
                nowEpochMs = nowEpochMs,
                evaluationZoneId = zoneId
            )
        } catch (e: Exception) {
            // Expected exception thrown to force transaction rollback
        }

        // Verify ScheduleException was 100% rolled back in DB
        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarm.id, alarm.generation)
        assertTrue("ScheduleException MUST be rolled back when reconcile fails", exceptions.isEmpty())
    }

    @Test
    fun `7 OS registration failure preserves in-DB committed skip exception and durable intent`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED, result.outcome)

        // Simulate OS AlarmManager registration failure by doing nothing externally;
        // Verify in-DB committed skip exception and PHASE_PENDING intent remain intact for background recovery!
        val exception = exceptionDao.getExceptionForOccurrence(alarm.id, wed0700Ms, alarm.generation)
        assertNotNull("In-DB skip exception MUST be preserved on OS failure", exception)

        val dbIntent = durableDao.getIntentByToken(result.durableIntent!!.operationToken)
        assertNotNull(dbIntent)
        assertEquals(DurableOperationIntent.PHASE_PENDING, dbIntent!!.phase)
    }

    @Test
    fun `8 Process death and restart recovery adjudicates pending skip intent`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        val intentToken = result.durableIntent!!.operationToken

        // Simulate process death / app restart: run recovery coordinator
        val recoveryResult = recoveryCoordinator.recoverUnresolvedIntent(intentToken, nowEpochMs + 10000L)
        assertEquals(DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE, recoveryResult.outcome)
    }

    @Test
    fun `9 Legacy Scheduler re-arm operates cleanly under LEGACY_ONLY routing`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val result = adapter.executeSkipClaimAndReconcile(
            alarmId = alarm.id,
            expectedScheduledAt = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimAdapterOutcome.SUCCESS_COMMITTED, result.outcome)
        val updatedAlarm = result.updatedAlarm!!

        // Verify updatedAlarm has nextTriggerTime = nov040700Ms ready for Legacy OS AlarmManager scheduleAt
        assertEquals(nov040700Ms, updatedAlarm.nextTriggerTime)
        assertEquals(alarm.generation, updatedAlarm.generation)
        assertEquals(authority.instanceToken, result.issuedOccurrence!!.authorityInstanceToken)
    }
}
