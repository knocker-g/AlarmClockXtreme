package com.sysadmindoc.alarmclock.domain.evaluator

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class DurableSkipClaimCoordinatorTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var coordinator: DurableSkipClaimCoordinator

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
        coordinator = DurableSkipClaimCoordinator(database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertTestAlarmAndAuthority(
        alarmId: Long = 1L,
        hour: Int = 7,
        minute: Int = 0,
        generation: Int = 1,
        revision: Int = 1,
        nextTriggerTime: Long = wed0700Ms
    ): Pair<Alarm, ScheduleAuthority> = runBlocking {
        val alarm = Alarm(
            id = alarmId,
            hour = hour,
            minute = minute,
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

        return@runBlocking Pair(insertedAlarm, authority)
    }

    @Test
    fun `1 正常なSKIP永続化`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result = coordinator.claimDurableSkip(
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result.outcome)
        assertNotNull(result.committedSkipException)
        assertEquals(ScheduleException.TYPE_SKIP, result.committedSkipException!!.exceptionType)
        assertEquals(wed0700Ms, result.committedSkipException!!.baseTriggerTime)
        assertEquals(nov040700Ms, result.nextTargetTriggerTimeEpochMs)

        // Verify persisted in Room DB
        val persistedList = exceptionDao.getExceptionsForAlarmGeneration(alarm.id, alarm.generation)
        assertEquals(1, persistedList.size)
        assertEquals(ScheduleException.TYPE_SKIP, persistedList[0].exceptionType)
    }

    @Test
    fun `2 同一SKIPの冪等再実行`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        // 1st claim
        val result1 = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result1.outcome)

        // 2nd claim (identical)
        val result2 = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.ALREADY_COMMITTED, result2.outcome)
        assertNotNull(result2.committedSkipException)

        // DB still contains exactly 1 exception row
        val persistedList = exceptionDao.getExceptionsForAlarmGeneration(alarm.id, alarm.generation)
        assertEquals(1, persistedList.size)
    }

    @Test
    fun `3 TIME_OVERRIDEとの競合`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val overrideTimeMs = wed0700Ms + 3600000L

        // Insert pre-existing TIME_OVERRIDE exception on same base occurrence
        val overrideException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            overrideTriggerTime = overrideTimeMs,
            generation = alarm.generation,
            revision = alarm.revision,
            createdAt = nowEpochMs
        )
        exceptionDao.insert(overrideException)

        // Update stored nextTriggerTime projection to match overrideTimeMs
        alarmDao.update(alarm.copy(nextTriggerTime = overrideTimeMs))

        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = overrideTimeMs,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)

        assertEquals("Pre-existing TIME_OVERRIDE exception MUST return CONFLICT_OVERRIDE_EXISTS", DurableSkipClaimOutcome.CONFLICT_OVERRIDE_EXISTS, result.outcome)
        assertNull(result.committedSkipException)
    }

    @Test
    fun `4 旧Generationの拒否`() = runBlocking {
        val (alarmGen2, authority) = insertTestAlarmAndAuthority(generation = 2)

        val staleGen1Snapshot = CurrentNextActionSnapshot(
            alarmId = alarmGen2.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1, // Stale generation
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result = coordinator.claimDurableSkip(staleGen1Snapshot, nowEpochMs, evaluationZoneId = zoneId)

        assertEquals(DurableSkipClaimOutcome.STALE_OCCURRENCE, result.outcome)
        assertNull(result.committedSkipException)
    }

    @Test
    fun `5 Authority fingerprint変更の拒否`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val staleFingerprint = ScheduleAuthorityFingerprint(
            alarmId = alarm.id,
            instanceToken = "old-instance-token", // Mismatched instanceToken
            restoreEpoch = 100L,
            scheduleRevision = 1L,
            definitionRevision = 1L
        )

        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = staleFingerprint
        )

        val result = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)

        assertEquals(DurableSkipClaimOutcome.STALE_OCCURRENCE, result.outcome)
        assertNull(result.committedSkipException)
    }

    @Test
    fun `6 stale TARGETの拒否`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val staleTargetSnapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = 1000000L, // Mismatched target
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result = coordinator.claimDurableSkip(staleTargetSnapshot, nowEpochMs, evaluationZoneId = zoneId)

        assertEquals(DurableSkipClaimOutcome.STALE_OCCURRENCE, result.outcome)
        assertNull(result.committedSkipException)
    }

    @Test
    fun `7 Pre-existing TIME_OVERRIDE on Time Once occurrence fails closed as CONFLICT_OVERRIDE_EXISTS`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val wedOverride0930Ms = LocalDate.of(2026, 10, 28)
            .atTime(LocalTime.of(9, 30))
            .atZone(zoneId)
            .toInstant()
            .toEpochMilli()

        // Insert pre-existing TIME_OVERRIDE exception
        val overrideException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_TIME_OVERRIDE,
            overrideTriggerTime = wedOverride0930Ms,
            generation = alarm.generation,
            revision = alarm.revision
        )
        exceptionDao.insert(overrideException)

        // Alarm nextTriggerTime updated to 09:30
        alarmDao.update(alarm.copy(nextTriggerTime = wedOverride0930Ms))

        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wedOverride0930Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)

        // Pre-existing TIME_OVERRIDE on same base occurrence key prevents overwriting
        assertEquals(DurableSkipClaimOutcome.CONFLICT_OVERRIDE_EXISTS, result.outcome)
        assertNull(result.committedSkipException)
    }

    @Test
    fun `8 非Schedule revision変更後の正当なSKIP`() = runBlocking {
        val (alarmRev1, authority) = insertTestAlarmAndAuthority(revision = 1)

        // Label update in Room DB increments revision to 5, generation remains 1
        val alarmRev5 = alarmRev1.copy(label = "Updated Label", revision = 5)
        alarmDao.update(alarmRev5)

        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarmRev1.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)

        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result.outcome)
        assertNotNull(result.committedSkipException)
        assertEquals(1, result.committedSkipException!!.generation)
    }

    @Test
    fun `9 Sequential duplicate claim returns ALREADY_COMMITTED idempotently`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val snapshot1 = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result1 = coordinator.claimDurableSkip(snapshot1, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result1.outcome)

        // Concurrent/duplicate request for same occurrence returns ALREADY_COMMITTED
        val result2 = coordinator.claimDurableSkip(snapshot1, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.ALREADY_COMMITTED, result2.outcome)
    }

    @Test
    fun `10 Active FIRINGは拒否`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result = coordinator.claimDurableSkip(
            actionSnapshot = snapshot,
            nowEpochMs = nowEpochMs,
            activeFiringAlarmId = alarm.id, // Active FIRING session
            evaluationZoneId = zoneId
        )

        assertEquals(DurableSkipClaimOutcome.ACTIVE_SESSION, result.outcome)
        assertNull(result.committedSkipException)
    }

    @Test
    fun `11 Real Room Rollback on write exception leaves DB unchanged`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        // Execute simulated Room transaction failure
        try {
            db.withTransaction {
                val skipException = ScheduleException(
                    alarmId = alarm.id,
                    baseTriggerTime = wed0700Ms,
                    exceptionType = ScheduleException.TYPE_SKIP,
                    generation = alarm.generation
                )
                exceptionDao.insert(skipException)
                throw RuntimeException("Simulated Room transaction write failure")
            }
            fail("Expected RuntimeException was not thrown")
        } catch (e: RuntimeException) {
            assertEquals("Simulated Room transaction write failure", e.message)
        }

        // Verify Room DB state was completely rolled back: 0 exceptions persisted!
        val persistedExceptions = exceptionDao.getExceptionsForAlarm(alarm.id)
        assertEquals("Room transaction failure MUST rollback and leave 0 exceptions", 0, persistedExceptions.size)

        // Subsequent clean claim via coordinator succeeds normally
        val cleanResult = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, cleanResult.outcome)
        assertEquals(1, exceptionDao.getExceptionsForAlarm(alarm.id).size)
    }

    @Test
    fun `12 永続化後の次回候補計算でSKIPが適用されること`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        val result = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)

        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result.outcome)
        // Next target trigger time is advanced to Nov 4 07:00
        assertEquals(nov040700Ms, result.nextTargetTriggerTimeEpochMs)
    }

    @Test
    fun `13 Stale generation duplicate skip request is rejected as STALE_OCCURRENCE`() = runBlocking {
        val (alarmGen1, authority) = insertTestAlarmAndAuthority(generation = 1)

        val snapshotGen1 = CurrentNextActionSnapshot(
            alarmId = alarmGen1.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = 1,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        // First claim on gen 1 succeeds
        val result1 = coordinator.claimDurableSkip(snapshotGen1, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result1.outcome)

        // Alarm edited: generation incremented to 2 in Room DB
        alarmDao.update(alarmGen1.copy(generation = 2))

        // Action request for stale gen 1 MUST NOT return ALREADY_COMMITTED; MUST return STALE_OCCURRENCE!
        val result2 = coordinator.claimDurableSkip(snapshotGen1, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals("Stale action generation MUST return STALE_OCCURRENCE even if old skip exists", DurableSkipClaimOutcome.STALE_OCCURRENCE, result2.outcome)
        assertNull(result2.committedSkipException)
    }

    @Test
    fun `14 Disabled alarm with existing TYPE_SKIP in DB is rejected as UNSUPPORTED_SCHEDULE`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        // First claim succeeds and persists TYPE_SKIP in DB
        val result1 = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result1.outcome)

        // Alarm is disabled in DB
        alarmDao.update(alarm.copy(isEnabled = false))

        // Duplicate claim on disabled alarm MUST return UNSUPPORTED_SCHEDULE, NOT ALREADY_COMMITTED!
        val resultDisabled = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.UNSUPPORTED_SCHEDULE, resultDisabled.outcome)
    }

    @Test
    fun `15 Active FIRING with existing TYPE_SKIP in DB is rejected as ACTIVE_SESSION`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        // First claim succeeds and persists TYPE_SKIP in DB
        val result1 = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result1.outcome)

        // Duplicate claim while alarm is in active FIRING session MUST return ACTIVE_SESSION!
        val resultFiring = coordinator.claimDurableSkip(snapshot, nowEpochMs, activeFiringAlarmId = alarm.id, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.ACTIVE_SESSION, resultFiring.outcome)
    }

    @Test
    fun `16 Mismatched authority fingerprint with existing TYPE_SKIP in DB is rejected as STALE_OCCURRENCE`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val snapshot = CurrentNextActionSnapshot(
            alarmId = alarm.id,
            expectedScheduledTargetEpochMs = wed0700Ms,
            expectedBaseTriggerTimeEpochMs = wed0700Ms,
            expectedGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        )

        // First claim succeeds and persists TYPE_SKIP in DB
        val result1 = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.COMMITTED_PENDING_RECONCILE, result1.outcome)

        // Authority is updated in DB (e.g. scheduleRevision incremented to 2)
        authorityDao.deleteAuthorityByAlarmId(alarm.id)
        authorityDao.insertAuthority(authority.copy(scheduleRevision = 2L))

        // Duplicate claim with old fingerprint MUST return STALE_OCCURRENCE!
        val resultStaleAuth = coordinator.claimDurableSkip(snapshot, nowEpochMs, evaluationZoneId = zoneId)
        assertEquals(DurableSkipClaimOutcome.STALE_OCCURRENCE, resultStaleAuth.outcome)
    }
}
