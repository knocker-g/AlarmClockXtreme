package com.sysadmindoc.alarmclock.domain.evaluator

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class DurableProvenanceVerifierTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao

    private lateinit var fireVerifier: OccurrenceIssuanceProvenanceVerifier
    private lateinit var reconcileVerifier: ReconcileProvenanceVerifier
    private val firePlanner = NormalAlarmFireSafetyPlanner()

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

        fireVerifier = OccurrenceIssuanceProvenanceVerifier(database = db)
        reconcileVerifier = ReconcileProvenanceVerifier(database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertTestAlarmAuthorityAndIssuance(
        alarmId: Long = 1L,
        generation: Int = 1,
        issueToken: String = "valid-issue-token-1",
        status: String = IssuedOccurrence.STATUS_ISSUED
    ): Triple<Alarm, ScheduleAuthority, IssuedOccurrence> = runBlocking {
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = generation,
            revision = 1,
            nextTriggerTime = wed0700Ms
        )
        val rowId = alarmDao.insert(alarm)
        val insertedAlarm = alarm.copy(id = rowId)

        val authority = ScheduleAuthority(
            alarmId = rowId,
            instanceToken = "auth-token-$rowId",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = wed0700Ms,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = null
        )
        authorityDao.insertAuthority(authority)

        val slot = IssueSlot(
            alarmId = rowId,
            generationId = 1L,
            currentIssueToken = issueToken
        )
        issuedDao.insertSlotInitial(slot)

        val issuedOccurrence = IssuedOccurrence(
            issueToken = issueToken,
            alarmId = rowId,
            scheduledAt = wed0700Ms,
            fireId = "fire-$rowId",
            generationId = 1L,
            status = status
        )
        issuedDao.insertOccurrence(issuedOccurrence)

        return@runBlocking Triple(insertedAlarm, authority, issuedOccurrence)
    }

    @Test
    fun `1 Fake issueToken absent in DB`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance()

        val decision = fireVerifier.verifyAndPlanFireSafety(
            alarmId = alarm.id,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = alarm.generation,
            issueToken = "fake-unregistered-token" // Absent in Room DB
        )

        assertEquals("Absent issueToken MUST fail-closed as UNVERIFIED_INTENT_DATA", FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse(decision.isFireAdmissionConfirmed)
        assertFalse(decision.isFireSuppressionRequired)
    }

    @Test
    fun `2 Stale IssueSlot old token`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance(issueToken = "old-token-1")

        // Update IssueSlot to new token
        issuedDao.casUpdateSlotCurrentTokenOnly(
            alarmId = alarm.id,
            expectedGenId = 1L,
            expectedToken = "old-token-1",
            newToken = "new-current-token-2"
        )

        val decision = fireVerifier.verifyAndPlanFireSafety(
            alarmId = alarm.id,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = alarm.generation,
            issueToken = "old-token-1" // Old token mismatch with slot currentIssueToken
        )

        assertEquals("Old slot token MUST fail-closed as UNVERIFIED_INTENT_DATA", FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `3 REVOKED IssuedOccurrence status`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance(status = IssuedOccurrence.STATUS_REVOKED)

        val decision = fireVerifier.verifyAndPlanFireSafety(
            alarmId = alarm.id,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = alarm.generation,
            issueToken = "valid-issue-token-1"
        )

        assertEquals("REVOKED status MUST fail-closed as UNVERIFIED_INTENT_DATA", FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `4 Authority instanceToken restoreEpoch mismatch`() = runBlocking {
        val (alarm, authority, _) = insertTestAlarmAuthorityAndIssuance()

        // Delete authority and insert mismatched instanceToken authority in DB
        authorityDao.deleteAuthorityByAlarmId(alarm.id)
        val newAuthority = authority.copy(instanceToken = "new-authority-token-42")
        authorityDao.insertAuthority(newAuthority)

        val oldProof = FireIssuanceProof(
            issueToken = "valid-issue-token-1",
            alarmId = alarm.id,
            scheduledAt = wed0700Ms,
            fireId = "fire-1",
            alarmGeneration = alarm.generation,
            authorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority) // Old authority fingerprint
        )

        val decision = firePlanner.planFireSafety(
            alarm = alarm,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = alarm.generation,
            exceptions = emptyList(),
            issuanceProof = oldProof,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(newAuthority)
        )

        assertEquals("Mismatched authority token MUST fail-closed as UNVERIFIED_INTENT_DATA", FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `5 BASE TARGET time mismatch`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance()

        val decision = fireVerifier.verifyAndPlanFireSafety(
            alarmId = alarm.id,
            firedTargetEpochMs = wed0700Ms + 3600000L, // Target mismatch
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = alarm.generation,
            issueToken = "valid-issue-token-1"
        )

        assertEquals("Mismatched scheduledAt target MUST fail-closed as UNVERIFIED_INTENT_DATA", FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `6 Schedule generation mismatch`() = runBlocking {
        val (alarmGen1, _, _) = insertTestAlarmAuthorityAndIssuance(generation = 1)

        // Alarm generation incremented to 2 in DB
        alarmDao.update(alarmGen1.copy(generation = 2))

        val decision = fireVerifier.verifyAndPlanFireSafety(
            alarmId = alarmGen1.id,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = 1, // Stale fired generation
            issueToken = "valid-issue-token-1"
        )

        assertEquals("Stale fired generation MUST be rejected as REJECT_STALE_GENERATION", FireSafetyOutcome.REJECT_STALE_GENERATION, decision.outcome)
        assertTrue(decision.isFireSuppressionRequired)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `7 Skip exception for old generation`() = runBlocking {
        val (alarmGen2, _, _) = insertTestAlarmAuthorityAndIssuance(generation = 2)

        // Insert skip exception for old generation 1
        val oldGenSkip = ScheduleException(
            alarmId = alarmGen2.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = 1
        )
        exceptionDao.insert(oldGenSkip)

        val reconcileDecision = reconcileVerifier.verifyAndPlanReconcile(
            alarmId = alarmGen2.id,
            nowEpochMs = nowEpochMs
        )

        // Reconcile ignores gen 1 skip for gen 2 alarm -> NO_ACTIVE_SKIP_EXCEPTION
        assertEquals(ReconcileOutcome.NO_ACTIVE_SKIP_EXCEPTION, reconcileDecision.outcome)
        assertFalse(reconcileDecision.isOsRescheduleCandidate)
    }

    @Test
    fun `8 Concurrent duplicate claims`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance()

        val decision1 = fireVerifier.verifyAndPlanFireSafety(alarm.id, wed0700Ms, wed0700Ms, 1, "valid-issue-token-1")
        val decision2 = fireVerifier.verifyAndPlanFireSafety(alarm.id, wed0700Ms, wed0700Ms, 1, "valid-issue-token-1")

        assertEquals(FireSafetyOutcome.ALLOW_FIRE_VERIFIED, decision1.outcome)
        assertEquals(FireSafetyOutcome.ALLOW_FIRE_VERIFIED, decision2.outcome)
    }

    @Test
    fun `9 Persisted skip mismatch with post-skip evaluated candidate`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance()

        // Insert persisted skip exception
        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        // Stored projection alarm.nextTriggerTime is wed0700Ms, while post-skip candidate is nov040700Ms
        val decision = reconcileVerifier.verifyAndPlanReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            evaluationZoneId = zoneId
        )

        assertEquals(ReconcileOutcome.PROJECTION_DIFFERS_RECONCILE_REQUIRED, decision.outcome)
        assertTrue(decision.isOsRescheduleCandidate)
        assertEquals(nov040700Ms, decision.evaluatedPostSkipNextTargetEpochMs)
    }

    @Test
    fun `10 Authentic IssuedOccurrence with matching skip`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val decision = fireVerifier.verifyAndPlanFireSafety(
            alarmId = alarm.id,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = alarm.generation,
            issueToken = "valid-issue-token-1"
        )

        assertEquals("Matching skip exception MUST suppress fire", FireSafetyOutcome.SUPPRESS_CONFIRMED_SKIP, decision.outcome)
        assertTrue(decision.isFireSuppressionRequired)
        assertFalse(decision.isFireAdmissionConfirmed)
    }

    @Test
    fun `11 Authentic IssuedOccurrence without skip`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance()

        val decision = fireVerifier.verifyAndPlanFireSafety(
            alarmId = alarm.id,
            firedTargetEpochMs = wed0700Ms,
            firedBaseTriggerTimeEpochMs = wed0700Ms,
            firedGeneration = alarm.generation,
            issueToken = "valid-issue-token-1"
        )

        assertEquals("Valid IssuedOccurrence without skip MUST allow verified fire", FireSafetyOutcome.ALLOW_FIRE_VERIFIED, decision.outcome)
        assertTrue(decision.isFireAdmissionConfirmed)
        assertFalse(decision.isFireSuppressionRequired)
    }

    @Test
    fun `12 In-memory DB transaction rollback leaves 0 issued occurrences`() = runBlocking {
        val (alarm, _, _) = insertTestAlarmAuthorityAndIssuance()

        try {
            db.withTransaction<Unit> {
                val newOccurrence = IssuedOccurrence(
                    issueToken = "rollback-test-token",
                    alarmId = alarm.id,
                    scheduledAt = wed0700Ms,
                    fireId = "fire-rollback",
                    generationId = 2L,
                    status = IssuedOccurrence.STATUS_ISSUED
                )
                issuedDao.insertOccurrence(newOccurrence)
                throw RuntimeException("Simulated transaction rollback error")
            }
            fail("Expected RuntimeException was not thrown")
        } catch (e: RuntimeException) {
            assertEquals("Simulated transaction rollback error", e.message)
        }

        // Verify rollback token is absent in Room DB
        val rolledBackOccurrence = issuedDao.getOccurrence("rollback-test-token")
        assertNull("Rolled back occurrence token MUST be null", rolledBackOccurrence)
    }
}
