package com.sysadmindoc.alarmclock.domain.evaluator

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.OsOperationOutcome
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
class Subtask4AdmissionFoundationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var durableDao: DurableOperationIntentDao

    private lateinit var admissionCoordinator: DurableReconcileDispatchAdmissionCoordinator

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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        authorityDao = db.scheduleAuthorityDao()
        durableDao = db.durableOperationIntentDao()

        admissionCoordinator = DurableReconcileDispatchAdmissionCoordinator(database = db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertTestSetup(
        alarmId: Long = 1L,
        generation: Int = 1,
        revision: Int = 1,
        instanceToken: String = "auth-token-1",
        restoreEpoch: Long = 100L,
        intentToken: String = "intent-token-1"
    ): Triple<Alarm, ScheduleAuthority, DurableOperationIntent> = runBlocking {
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
            repeatDays = setOf(DayOfWeek.WEDNESDAY),
            generation = generation,
            revision = revision,
            nextTriggerTime = wed0700Ms
        )
        val rowId = alarmDao.insert(alarm)
        val insertedAlarm = alarm.copy(id = rowId)

        val authority = ScheduleAuthority(
            alarmId = rowId,
            instanceToken = instanceToken,
            definitionRevision = revision.toLong(),
            scheduleRevision = revision.toLong(),
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = wed0700Ms,
            reconcileState = "SETTLED",
            restoreEpoch = restoreEpoch,
            actionKey = null
        )
        authorityDao.insertAuthority(authority)

        val intent = DurableOperationIntent(
            operationToken = intentToken,
            alarmId = rowId,
            operationEpoch = nowEpochMs,
            incarnationId = instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = instanceToken,
            expectedScheduleRevision = revision.toLong(),
            expectedRestoreEpoch = restoreEpoch,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(intent)

        return@runBlocking Triple(insertedAlarm, authority, intent)
    }

    @Test
    fun `1 Valid pending intent admission granted and transitioned to IN_FLIGHT`() = runBlocking {
        val (_, _, intent) = insertTestSetup()

        val result = admissionCoordinator.evaluateDispatchAdmission(
            intentToken = intent.operationToken,
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.ADMISSION_GRANTED_IN_FLIGHT, result.outcome)
        assertNotNull(result.updatedIntent)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, result.updatedIntent!!.phase)

        val dbIntent = durableDao.getIntentByToken(intent.operationToken)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, dbIntent!!.phase)
    }

    @Test
    fun `2 Stale owner token rejects admission and aborts intent in DB`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup()

        val staleIntent = DurableOperationIntent(
            operationToken = "stale-owner-admission",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = "stale-owner-token", // Mismatched owner token!
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(staleIntent)

        val result = admissionCoordinator.evaluateDispatchAdmission(
            intentToken = "stale-owner-admission",
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED, result.outcome)

        val dbIntent = durableDao.getIntentByToken("stale-owner-admission")
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `3 Schedule revision mismatch rejects admission and aborts intent`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup(revision = 1)

        val staleRevIntent = DurableOperationIntent(
            operationToken = "stale-rev-admission",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = authority.instanceToken,
            expectedScheduleRevision = 99L, // Mismatched revision!
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(staleRevIntent)

        val result = admissionCoordinator.evaluateDispatchAdmission(
            intentToken = "stale-rev-admission",
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED, result.outcome)

        val dbIntent = durableDao.getIntentByToken("stale-rev-admission")
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `4 Restore epoch mismatch rejects admission and aborts intent`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup(restoreEpoch = 100L)

        val staleEpochIntent = DurableOperationIntent(
            operationToken = "stale-epoch-admission",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = authority.instanceToken,
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = 999L, // Mismatched restoreEpoch!
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(staleEpochIntent)

        val result = admissionCoordinator.evaluateDispatchAdmission(
            intentToken = "stale-epoch-admission",
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED, result.outcome)

        val dbIntent = durableDao.getIntentByToken("stale-epoch-admission")
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `5 Duplicate concurrent admission rejected when lease is active IN_FLIGHT`() = runBlocking {
        val (_, _, intent) = insertTestSetup()

        // First admission succeeds
        val result1 = admissionCoordinator.evaluateDispatchAdmission(intent.operationToken, nowEpochMs)
        assertEquals(DurableDispatchOutcome.ADMISSION_GRANTED_IN_FLIGHT, result1.outcome)

        // Second concurrent admission attempt 10 seconds later while lease is active
        val result2 = admissionCoordinator.evaluateDispatchAdmission(intent.operationToken, nowEpochMs + 10000L)
        assertEquals(DurableDispatchOutcome.ADMISSION_REJECTED_IN_FLIGHT_LEASE_ACTIVE, result2.outcome)
    }

    @Test
    fun `6 Forged OS ACCEPTED proof rejected and PHASE_COMPLETED disabled`() = runBlocking {
        val (alarm, authority, intent) = insertTestSetup()

        // First admit intent to IN_FLIGHT
        admissionCoordinator.evaluateDispatchAdmission(intent.operationToken, nowEpochMs)

        val authenticProof = DurableDispatchProof(
            operationToken = intent.operationToken,
            alarmId = alarm.id,
            authorityInstanceToken = authority.instanceToken,
            scheduleRevision = authority.scheduleRevision,
            restoreEpoch = authority.restoreEpoch,
            targetTriggerTimeEpochMs = wed0700Ms
        )

        // Attempt settlement: un-connected OS adapter rejects ACCEPTED inputs to prevent forged PHASE_COMPLETED
        val result = admissionCoordinator.settleDispatchOutcome(
            intentToken = intent.operationToken,
            osOutcome = OsOperationOutcome.ACCEPTED,
            proof = authenticProof,
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF, result.outcome)

        // Verify intent phase remains PHASE_PENDING in Room DB (NOT PHASE_COMPLETED!)
        val dbIntent = durableDao.getIntentByToken(intent.operationToken)
        assertEquals(DurableOperationIntent.PHASE_PENDING, dbIntent!!.phase)
    }

    @Test
    fun `7 Settlement rejected for PENDING intent without IN_FLIGHT transition`() = runBlocking {
        val (_, _, intent) = insertTestSetup() // Intent is in PHASE_PENDING

        val result = admissionCoordinator.settleDispatchOutcome(
            intentToken = intent.operationToken,
            osOutcome = OsOperationOutcome.ACCEPTED,
            proof = null,
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF, result.outcome)

        // Verify intent phase remains PHASE_PENDING (NOT mutated)
        val dbIntent = durableDao.getIntentByToken(intent.operationToken)
        assertEquals(DurableOperationIntent.PHASE_PENDING, dbIntent!!.phase)
    }

    @Test
    fun `8 Settlement rejected for terminal COMPLETED intent without mutating phase`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup()

        val completedIntent = DurableOperationIntent(
            operationToken = "completed-intent",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = authority.instanceToken,
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_COMPLETED,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(completedIntent)

        val result = admissionCoordinator.settleDispatchOutcome(
            intentToken = "completed-intent",
            osOutcome = OsOperationOutcome.ACCEPTED,
            proof = null,
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF, result.outcome)

        // Verify terminal phase PHASE_COMPLETED is NOT mutated in Room DB!
        val dbIntent = durableDao.getIntentByToken("completed-intent")
        assertEquals(DurableOperationIntent.PHASE_COMPLETED, dbIntent!!.phase)
    }

    @Test
    fun `9 Settlement rejected for terminal ABORTED intent without mutating phase`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup()

        val abortedIntent = DurableOperationIntent(
            operationToken = "aborted-intent",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = authority.instanceToken,
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_ABORTED,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(abortedIntent)

        val result = admissionCoordinator.settleDispatchOutcome(
            intentToken = "aborted-intent",
            osOutcome = OsOperationOutcome.ACCEPTED,
            proof = null,
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF, result.outcome)

        // Verify terminal phase PHASE_ABORTED is NOT mutated in Room DB!
        val dbIntent = durableDao.getIntentByToken("aborted-intent")
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `10 Settlement with stale authority aborts intent in DB`() = runBlocking {
        val (alarm, authority, intent) = insertTestSetup()

        // Admit intent to IN_FLIGHT
        admissionCoordinator.evaluateDispatchAdmission(intent.operationToken, nowEpochMs)

        // Mutate ScheduleAuthority to stale instanceToken
        authorityDao.deleteAuthorityByAlarmId(alarm.id)
        authorityDao.insertAuthority(authority.copy(instanceToken = "new-owner-token"))

        val result = admissionCoordinator.settleDispatchOutcome(
            intentToken = intent.operationToken,
            osOutcome = OsOperationOutcome.ACCEPTED,
            proof = null,
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF, result.outcome)

        // Stale authority during settlement MUST abort intent in Room DB
        val dbIntent = durableDao.getIntentByToken(intent.operationToken)
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `11 RED-FIRST Negative Control Discriminator`() = runBlocking {
        val (alarm, authority, intent) = insertTestSetup()

        // RED: Stale owner token admission is rejected and intent is aborted
        val staleIntent = DurableOperationIntent(
            operationToken = "discriminator-stale-intent",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = "invalid-owner-token",
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(staleIntent)

        val redResult = admissionCoordinator.evaluateDispatchAdmission("discriminator-stale-intent", nowEpochMs)
        assertEquals(DurableDispatchOutcome.ADMISSION_REJECTED_QUARANTINED, redResult.outcome)

        // GREEN: Valid intent with matching owner token admission is granted and transitioned to IN_FLIGHT
        val greenResult = admissionCoordinator.evaluateDispatchAdmission(intent.operationToken, nowEpochMs)
        assertEquals(DurableDispatchOutcome.ADMISSION_GRANTED_IN_FLIGHT, greenResult.outcome)
    }
}
