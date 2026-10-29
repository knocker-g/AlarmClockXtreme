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
class DurableReconcileOfflineIntegrationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var exceptionDao: ScheduleExceptionDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var durableDao: DurableOperationIntentDao

    private lateinit var issuanceProducer: OccurrenceIssuanceProducer
    private lateinit var reconcileProducer: DurableReconcileProducer
    private lateinit var recoveryCoordinator: DurableReconcileRecoveryCoordinator
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
        reconcileProducer = DurableReconcileProducer(database = db, issuanceProducer = issuanceProducer)
        recoveryCoordinator = DurableReconcileRecoveryCoordinator(database = db)
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
        restoreEpoch: Long = 100L
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

        val slot = IssueSlot(alarmId = rowId, generationId = 1L, currentIssueToken = null)
        issuedDao.insertSlotInitial(slot)

        return@runBlocking Pair(insertedAlarm, authority)
    }

    @Test
    fun `1 End-to-end Pipeline - Reconcile to Issuance to Durable Intent`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val reconcileResult = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        assertEquals(DurableReconcileProducerOutcome.RECONCILE_COMMITTED_PENDING_OS, reconcileResult.outcome)
        assertNotNull(reconcileResult.updatedAlarm)
        assertEquals(nov040700Ms, reconcileResult.updatedAlarm!!.nextTriggerTime)

        assertNotNull(reconcileResult.issuedOccurrence)
        assertEquals(nov040700Ms, reconcileResult.issuedOccurrence!!.scheduledAt)

        assertNotNull(reconcileResult.durableIntent)
        val intent = reconcileResult.durableIntent!!
        assertEquals("RECONCILE_RESCHEDULE", intent.operationKind)
        assertEquals(DurableOperationIntent.PHASE_PENDING, intent.phase)
        assertEquals(authority.instanceToken, intent.incarnationId)
    }

    @Test
    fun `2 End-to-end Pipeline - Pending Intent to Recovery to Admission`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val reconcileResult = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        val intentToken = reconcileResult.durableIntent!!.operationToken

        // Recovery adjudication
        val recoveryResult = recoveryCoordinator.recoverUnresolvedIntent(intentToken, nowEpochMs)
        assertEquals(DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE, recoveryResult.outcome)

        // Dispatch admission evaluation
        val admissionResult = admissionCoordinator.evaluateDispatchAdmission(intentToken, nowEpochMs)
        assertEquals(DurableDispatchOutcome.ADMISSION_GRANTED_IN_FLIGHT, admissionResult.outcome)

        val dbIntent = durableDao.getIntentByToken(intentToken)
        assertEquals(DurableOperationIntent.PHASE_IN_FLIGHT, dbIntent!!.phase)
    }

    @Test
    fun `3 IN_FLIGHT duplicate admission rejection`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val reconcileResult = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        val intentToken = reconcileResult.durableIntent!!.operationToken

        // First admission granted
        val admission1 = admissionCoordinator.evaluateDispatchAdmission(intentToken, nowEpochMs)
        assertEquals(DurableDispatchOutcome.ADMISSION_GRANTED_IN_FLIGHT, admission1.outcome)

        // Second admission rejected as active in-flight lease
        val admission2 = admissionCoordinator.evaluateDispatchAdmission(intentToken, nowEpochMs + 5000L)
        assertEquals(DurableDispatchOutcome.ADMISSION_REJECTED_IN_FLIGHT_LEASE_ACTIVE, admission2.outcome)
    }

    @Test
    fun `4 Recovery after simulated process death with IN_FLIGHT intent`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val reconcileResult = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        val intentToken = reconcileResult.durableIntent!!.operationToken

        // Transition to IN_FLIGHT
        admissionCoordinator.evaluateDispatchAdmission(intentToken, nowEpochMs)

        // Simulate process death and app restart: recovery adjudicates in-flight intent against live DB authority
        val restartRecoveryResult = recoveryCoordinator.recoverUnresolvedIntent(intentToken, nowEpochMs + 10000L)
        assertEquals(DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE, restartRecoveryResult.outcome)
    }

    @Test
    fun `5 Authority generation change during recovery rejects intent`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val reconcileResult = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        val intentToken = reconcileResult.durableIntent!!.operationToken

        // Authority changed concurrently (new instanceToken)
        authorityDao.deleteAuthorityByAlarmId(alarm.id)
        authorityDao.insertAuthority(authority.copy(instanceToken = "new-authority-token-42"))

        val recoveryResult = recoveryCoordinator.recoverUnresolvedIntent(intentToken, nowEpochMs)
        assertEquals(DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED, recoveryResult.outcome)

        val dbIntent = durableDao.getIntentByToken(intentToken)
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `6 RestoreEpoch change during settlement rejects settlement`() = runBlocking {
        val (alarm, authority) = insertTestSetup(restoreEpoch = 100L)

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val reconcileResult = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        val intentToken = reconcileResult.durableIntent!!.operationToken
        admissionCoordinator.evaluateDispatchAdmission(intentToken, nowEpochMs)

        // Restore epoch changed in DB
        authorityDao.deleteAuthorityByAlarmId(alarm.id)
        authorityDao.insertAuthority(authority.copy(restoreEpoch = 200L))

        val proof = DurableDispatchProof(
            operationToken = intentToken,
            alarmId = alarm.id,
            authorityInstanceToken = authority.instanceToken,
            scheduleRevision = authority.scheduleRevision,
            restoreEpoch = 200L,
            targetTriggerTimeEpochMs = nov040700Ms
        )

        val settlementResult = admissionCoordinator.settleDispatchOutcome(intentToken, OsOperationOutcome.ACCEPTED, proof, nowEpochMs)
        assertEquals(DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF, settlementResult.outcome)

        val dbIntent = durableDao.getIntentByToken(intentToken)
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `7 OS unverified state preserves non-COMPLETED phase`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val reconcileResult = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        val intentToken = reconcileResult.durableIntent!!.operationToken

        // Recovery without OS registration evidence
        val recoveryResult = recoveryCoordinator.recoverUnresolvedIntent(intentToken, nowEpochMs, osOutcome = OsOperationOutcome.NOT_ATTEMPTED)
        assertEquals(DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE, recoveryResult.outcome)

        val dbIntent = durableDao.getIntentByToken(intentToken)
        assertEquals(DurableOperationIntent.PHASE_PENDING, dbIntent!!.phase)
    }

    @Test
    fun `8 Duplicate retry on already issued occurrence prevents duplicate issuance`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        // First reconcile run
        val res1 = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )
        assertEquals(DurableReconcileProducerOutcome.RECONCILE_COMMITTED_PENDING_OS, res1.outcome)

        val occs1 = issuedDao.getOccurrencesByAlarmAndScheduledAt(alarm.id, nov040700Ms)
        assertEquals(1, occs1.size)

        // Second reconcile run on updated projection (alarm.nextTriggerTime is now nov040700Ms)
        val res2 = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        assertEquals(DurableReconcileProducerOutcome.UNVERIFIED_OS_STATE_PENDING_RETRY, res2.outcome)

        val occs2 = issuedDao.getOccurrencesByAlarmAndScheduledAt(alarm.id, nov040700Ms)
        assertEquals(1, occs2.size) // Zero duplicate occurrence rows inserted!
    }

    @Test
    fun `9 Terminal intent phase protection`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val completedIntent = DurableOperationIntent(
            operationToken = "terminal-completed-intent",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = authority.instanceToken,
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_COMPLETED,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = nov040700Ms
        )
        durableDao.insertValidatedIntent(completedIntent)

        val recoveryResult = recoveryCoordinator.recoverUnresolvedIntent("terminal-completed-intent", nowEpochMs)
        assertEquals(DurableRecoveryOutcome.ALREADY_SETTLED_IDEMPOTENT, recoveryResult.outcome)

        val dbIntent = durableDao.getIntentByToken("terminal-completed-intent")
        assertEquals(DurableOperationIntent.PHASE_COMPLETED, dbIntent!!.phase)
    }

    @Test
    fun `10 Unconnected OS forged ACCEPTED input rejection`() = runBlocking {
        val (alarm, authority) = insertTestSetup()

        val skipException = ScheduleException(
            alarmId = alarm.id,
            baseTriggerTime = wed0700Ms,
            exceptionType = ScheduleException.TYPE_SKIP,
            generation = alarm.generation
        )
        exceptionDao.insert(skipException)

        val reconcileResult = reconcileProducer.produceReconcile(
            alarmId = alarm.id,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority),
            evaluationZoneId = zoneId
        )

        val intentToken = reconcileResult.durableIntent!!.operationToken
        admissionCoordinator.evaluateDispatchAdmission(intentToken, nowEpochMs)

        val proof = DurableDispatchProof(
            operationToken = intentToken,
            alarmId = alarm.id,
            authorityInstanceToken = authority.instanceToken,
            scheduleRevision = authority.scheduleRevision,
            restoreEpoch = authority.restoreEpoch,
            targetTriggerTimeEpochMs = nov040700Ms
        )

        val settlementResult = admissionCoordinator.settleDispatchOutcome(intentToken, OsOperationOutcome.ACCEPTED, proof, nowEpochMs)
        assertEquals(DurableDispatchOutcome.SETTLEMENT_REJECTED_FORGED_PROOF, settlementResult.outcome)

        val dbIntent = durableDao.getIntentByToken(intentToken)
        assertEquals(DurableOperationIntent.PHASE_PENDING, dbIntent!!.phase)
    }
}
