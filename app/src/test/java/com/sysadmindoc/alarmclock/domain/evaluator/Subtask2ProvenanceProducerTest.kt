package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
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
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
class Subtask2ProvenanceProducerTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var durableDao: DurableOperationIntentDao

    private lateinit var issuanceProducer: OccurrenceIssuanceProducer
    private lateinit var reconcileProducer: DurableReconcileProducer

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

        issuanceProducer = OccurrenceIssuanceProducer(database = db)
        reconcileProducer = DurableReconcileProducer(database = db)
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

        val slot = IssueSlot(
            alarmId = rowId,
            generationId = 1L,
            currentIssueToken = null
        )
        issuedDao.insertSlotInitial(slot)

        return@runBlocking Pair(insertedAlarm, authority)
    }

    @Test
    fun `1 Authentic V35 provenance issuance committed`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)

        val result = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = fingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals(IssuanceProducerOutcome.ISSUED_COMMITTED, result.outcome)
        assertNotNull(result.issuedOccurrence)

        val occ = result.issuedOccurrence!!
        assertEquals(alarm.id, occ.alarmId)
        assertEquals(wed0700Ms, occ.scheduledAt)
        assertEquals(wed0700Ms, occ.baseTriggerTime)
        assertEquals(alarm.generation, occ.alarmGeneration)
        assertEquals(authority.instanceToken, occ.authorityInstanceToken)
        assertEquals(authority.restoreEpoch, occ.restoreEpoch)
        assertEquals(authority.scheduleRevision, occ.scheduleRevision)
        assertEquals(authority.definitionRevision, occ.definitionRevision)

        // Verify persisted in Room DB
        val retrieved = issuedDao.getOccurrence(occ.issueToken)
        assertNotNull(retrieved)
        assertEquals(occ.issueToken, retrieved!!.issueToken)
    }

    @Test
    fun `2 Missing or mismatched authority fail-closed`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()
        authorityDao.deleteAuthorityByAlarmId(alarm.id)

        val result = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(IssuanceProducerOutcome.MISSING_AUTHORITY, result.outcome)
        assertNull(result.issuedOccurrence)
    }

    @Test
    fun `3 Stale authority fingerprint fail-closed`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val staleFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority).copy(instanceToken = "stale-token")

        val result = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = staleFingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals(IssuanceProducerOutcome.STALE_GENERATION, result.outcome)
        assertNull(result.issuedOccurrence)
    }

    @Test
    fun `4 Disabled alarm or Quick alarm rejected`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        alarmDao.update(alarm.copy(isEnabled = false))

        val result = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        assertEquals(IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE, result.outcome)
        assertNull(result.issuedOccurrence)
    }

    @Test
    fun `5 Durable reconcile producer commits updated projection and intent`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()
        val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)

        // Insert persisted skip exception for wed0700Ms
        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val result = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = fingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableReconcileProducerOutcome.RECONCILE_COMMITTED_PENDING_OS, result.outcome)
        assertNotNull(result.updatedAlarm)
        assertEquals(nov040700Ms, result.updatedAlarm!!.nextTriggerTime)

        assertNotNull(result.issuedOccurrence)
        assertEquals(nov040700Ms, result.issuedOccurrence!!.scheduledAt)
        assertEquals(alarm.generation, result.issuedOccurrence!!.alarmGeneration)

        assertNotNull(result.durableIntent)
        assertEquals("RECONCILE_RESCHEDULE", result.durableIntent!!.operationKind)
        assertEquals(authority.instanceToken, result.durableIntent!!.expectedOwnerToken)
        assertEquals(DurableOperationIntent.PHASE_PENDING, result.durableIntent!!.phase)

        // Verify Room DB persisted state
        val dbAlarm = alarmDao.getById(alarm.id)
        assertEquals(nov040700Ms, dbAlarm!!.nextTriggerTime)
    }

    @Test
    fun `6 Item D - Unverified OS state returns UNVERIFIED_OS_STATE_PENDING_RETRY`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority(nextTriggerTime = nov040700Ms)
        val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val result = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = fingerprint,
            evaluationZoneId = zoneId
        )

        assertEquals("Unverified OS state MUST return UNVERIFIED_OS_STATE_PENDING_RETRY", DurableReconcileProducerOutcome.UNVERIFIED_OS_STATE_PENDING_RETRY, result.outcome)
        assertNull(result.durableIntent)
    }

    @Test
    fun `7 Item B - Atomic transaction rollback on issuance failure`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()

        // Insert skip exception to require reconcile
        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        // Delete authority so that in-transaction issuance inside produceReconcile fails
        authorityDao.deleteAuthorityByAlarmId(alarm.id)

        val result = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(DurableReconcileProducerOutcome.STALE_REJECTED, result.outcome)

        // Verify stored projection alarm.nextTriggerTime was NOT changed in DB (rolled back 100%)
        val dbAlarm = alarmDao.getById(alarm.id)
        assertEquals("Failed reconcile MUST roll back alarm.nextTriggerTime in DB", wed0700Ms, dbAlarm!!.nextTriggerTime)
    }

    @Test
    fun `8 Item A - IssueSlot CAS update failure triggers fail-closed conflict`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val slot = issuedDao.getSlot(alarm.id)!!

        // Verify CAS query returns 0 updated rows when expectedToken mismatches
        val failedCasCount = issuedDao.casUpdateSlotCurrentTokenOnly(
            alarmId = alarm.id,
            expectedGenId = slot.generationId,
            expectedToken = "mismatched-expected-token",
            newToken = "new-token-attempt"
        )

        assertEquals("Mismatched expectedToken MUST return 0 updated rows in CAS query", 0, failedCasCount)

        // Verify IssueSlot currentIssueToken in DB remains untouched (null)
        val refreshedSlot = issuedDao.getSlot(alarm.id)!!
        assertNull("Failed CAS MUST NOT update currentIssueToken in DB", refreshedSlot.currentIssueToken)
    }

    @Test
    fun `9 Modification A - DurableOperationIntent incarnationId MUST match ScheduleAuthority instanceToken`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val result = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        assertEquals(DurableReconcileProducerOutcome.RECONCILE_COMMITTED_PENDING_OS, result.outcome)
        val intent = result.durableIntent!!

        // Modification A: Verify incarnationId is equal to authority.instanceToken per RecoveryAdjudicator.kt:96,114
        assertEquals("intent.incarnationId MUST equal authority.instanceToken", authority.instanceToken, intent.incarnationId)
        assertEquals("intent.expectedOwnerToken MUST equal authority.instanceToken", authority.instanceToken, intent.expectedOwnerToken)
        assertEquals(authority.scheduleRevision, intent.expectedScheduleRevision)
        assertEquals(authority.restoreEpoch, intent.expectedRestoreEpoch)
        assertEquals(DurableOperationIntent.PHASE_PENDING, intent.phase)
    }

    @Test
    fun `10 Modification B - Non-issuable or mismatched EvaluatedOccurrence rejected`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        // Construct non-issuable EvaluatedOccurrence (decision = SKIPPED_BY_EXCEPTION)
        val nonIssuableEvaluated = EvaluatedOccurrence(
            occurrenceId = AlarmOccurrenceId(alarm.id, wed0700Ms, alarm.generation),
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            targetTriggerTimeEpochMs = wed0700Ms,
            decision = OccurrenceDecision.SKIPPED_BY_EXCEPTION,
            generation = alarm.generation,
            revision = alarm.revision
        )

        val result = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluatedOccurrence = nonIssuableEvaluated,
            evaluationZoneId = zoneId
        )

        assertEquals("Non-issuable decision (SKIPPED_BY_EXCEPTION) MUST be rejected as UNSUPPORTED_SCHEDULE", IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE, result.outcome)
        assertNull(result.issuedOccurrence)
    }

    @Test
    fun `11 RED-FIRST Negative Control Discriminator`() = runBlocking {
        val (alarm, _) = insertTestAlarmAndAuthority()

        // RED: Missing authority in DB causes issuance producer to fail-closed
        authorityDao.deleteAuthorityByAlarmId(alarm.id)
        val redResult = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )
        assertEquals(IssuanceProducerOutcome.MISSING_AUTHORITY, redResult.outcome)
        assertNull(redResult.issuedOccurrence)

        // GREEN: Restoring valid authority causes issuance producer to succeed with full V35 provenance
        val validAuth = ScheduleAuthority(
            alarmId = alarm.id,
            instanceToken = "auth-restored-1",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = wed0700Ms,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        authorityDao.insertAuthority(validAuth)

        val greenResult = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(validAuth),
            evaluationZoneId = zoneId
        )
        assertEquals(IssuanceProducerOutcome.ISSUED_COMMITTED, greenResult.outcome)
        assertNotNull(greenResult.issuedOccurrence)
        assertEquals(validAuth.instanceToken, greenResult.issuedOccurrence!!.authorityInstanceToken)
    }

    @Test
    fun `12 Mismatched BASE or TARGET timestamp MUST be rejected as UNSUPPORTED_SCHEDULE`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        // Test 1: Mismatched TARGET timestamp (wed0700Ms + 3600000L != wed0700Ms)
        val resultTargetMismatch = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms + 3600000L, // Mismatched target!
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        assertEquals("Mismatched target timestamp MUST be rejected", IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE, resultTargetMismatch.outcome)
        assertNull(resultTargetMismatch.issuedOccurrence)

        // Test 2: Mismatched BASE timestamp (wed0700Ms + 1000L != wed0700Ms)
        val resultBaseMismatch = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms + 1000L, // Mismatched base!
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        assertEquals("Mismatched base timestamp MUST be rejected", IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE, resultBaseMismatch.outcome)
        assertNull(resultBaseMismatch.issuedOccurrence)
    }

    @Test
    fun `13 Forged caller EvaluatedOccurrence or null occurrenceId rejected`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        // Test 1: Forged caller EvaluatedOccurrence with mismatched targetTriggerTimeEpochMs
        val forgedEvaluated = EvaluatedOccurrence(
            occurrenceId = AlarmOccurrenceId(alarm.id, wed0700Ms, alarm.generation),
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            targetTriggerTimeEpochMs = wed0700Ms + 3600000L, // Forged target time!
            decision = OccurrenceDecision.NORMAL_TRIGGER,
            generation = alarm.generation,
            revision = alarm.revision
        )

        val resultForged = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluatedOccurrence = forgedEvaluated,
            evaluationZoneId = zoneId
        )

        assertEquals("Forged caller EvaluatedOccurrence MUST be rejected", IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE, resultForged.outcome)
        assertNull(resultForged.issuedOccurrence)

        // Test 2: EvaluatedOccurrence with null occurrenceId
        val nullOccIdEvaluated = EvaluatedOccurrence(
            occurrenceId = null, // Null occurrenceId!
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            targetTriggerTimeEpochMs = wed0700Ms,
            decision = OccurrenceDecision.NORMAL_TRIGGER,
            generation = alarm.generation,
            revision = alarm.revision
        )

        val resultNullOccId = issuanceProducer.issueOccurrence(
            alarmId = alarm.id,
            baseTriggerTimeEpochMs = wed0700Ms,
            scheduledTargetEpochMs = wed0700Ms,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluatedOccurrence = nullOccIdEvaluated,
            evaluationZoneId = zoneId
        )

        assertEquals("EvaluatedOccurrence with null occurrenceId MUST be rejected", IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE, resultNullOccId.outcome)
        assertNull(resultNullOccId.issuedOccurrence)
    }

    @Test
    fun `14 DurableReconcileProducer rolls back projection when evaluation returns null without BASE fallback`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        // Insert skip exception
        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        // Corrupt alarm scheduleKind in DB to make occurrence evaluation return null
        alarmDao.update(alarm.copy(scheduleKind = "INVALID_KIND"))

        val result = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        assertEquals("Invalid schedule kind MUST cause reconcile to fail-closed as UNSUPPORTED_SCHEDULE", DurableReconcileProducerOutcome.UNSUPPORTED_SCHEDULE, result.outcome)
        assertNull(result.updatedAlarm)
        assertNull(result.issuedOccurrence)

        // Verify stored projection in DB remains untouched (wed0700Ms, scheduleKind = INVALID_KIND)
        val dbAlarm = alarmDao.getById(alarm.id)!!
        assertEquals("Stored projection MUST NOT be updated when evaluation fails", wed0700Ms, dbAlarm.nextTriggerTime)
    }

    @Test
    fun `15 Item B - True null evaluation from ONE_TIME alarm in past returns UNSUPPORTED_SCHEDULE`() = runBlocking {
        // ONE_TIME alarm in the past
        val pastTriggerMs = wed0700Ms - 86400000L * 30
        val alarm = Alarm(
            id = 200L,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
            specificDate = "2026-09-01",
            generation = 1,
            revision = 1,
            nextTriggerTime = pastTriggerMs
        )
        alarmDao.insert(alarm)

        val authority = ScheduleAuthority(
            alarmId = 200L,
            instanceToken = "auth-token-200",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = pastTriggerMs,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        authorityDao.insertAuthority(authority)

        val slot = IssueSlot(alarmId = 200L, generationId = 1L, currentIssueToken = null)
        issuedDao.insertSlotInitial(slot)

        val result = issuanceProducer.issueOccurrence(
            alarmId = 200L,
            baseTriggerTimeEpochMs = pastTriggerMs,
            scheduledTargetEpochMs = pastTriggerMs,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        assertEquals("Expired ONE_TIME alarm returning null occurrence MUST return UNSUPPORTED_SCHEDULE", IssuanceProducerOutcome.UNSUPPORTED_SCHEDULE, result.outcome)
        assertNull(result.issuedOccurrence)
    }

    @Test
    fun `16 Modification A - Symmetrical propagation of evaluation context parameters`() = runBlocking {
        val (alarm, authority) = insertTestAlarmAndAuthority()

        // Insert skip exception so reconcile is required
        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        // Create holiday snapshot provider returning IS_HOLIDAY for 2026-10-28 ONLY
        val wedDate = LocalDate.of(2026, 10, 28)
        val holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { date ->
            if (date == wedDate) {
                HolidaySnapshot(state = HolidayState.IS_HOLIDAY, holidayName = "National Holiday", checkedAtEpochMs = nowEpochMs)
            } else {
                HolidaySnapshot(state = HolidayState.NOT_HOLIDAY, checkedAtEpochMs = nowEpochMs)
            }
        }

        // Call produceReconcile with EXCLUDE policy
        val result = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            holidaySnapshotProvider = holidaySnapshotProvider,
            holidayPolicyOverride = HolidayPolicy.EXCLUDE,
            evaluationZoneId = zoneId
        )

        // EXCLUDE policy shifts candidate from 2026-10-28 holiday to next week (nov040700Ms)
        assertEquals(DurableReconcileProducerOutcome.RECONCILE_COMMITTED_PENDING_OS, result.outcome)
        assertNotNull(result.updatedAlarm)
        assertEquals(nov040700Ms, result.updatedAlarm!!.nextTriggerTime)

        assertNotNull(result.issuedOccurrence)
        assertEquals(nov040700Ms, result.issuedOccurrence!!.scheduledAt)
    }
}
