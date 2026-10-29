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
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
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
class Subtask3RecoveryFoundationTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var issuedDao: IssuedOccurrenceDao
    private lateinit var durableDao: DurableOperationIntentDao

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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        authorityDao = db.scheduleAuthorityDao()
        issuedDao = db.issuedOccurrenceDao()
        durableDao = db.durableOperationIntentDao()

        recoveryCoordinator = DurableReconcileRecoveryCoordinator(database = db)
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

        val slot = IssueSlot(alarmId = rowId, generationId = 1L, currentIssueToken = null)
        issuedDao.insertSlotInitial(slot)

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
    fun `1 Valid pending intent is eligible for dispatch retry`() = runBlocking {
        val (_, _, intent) = insertTestSetup()

        val result = recoveryCoordinator.recoverUnresolvedIntent(
            intentToken = intent.operationToken,
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE, result.outcome)
        assertEquals(intent.operationToken, result.intentToken)
        assertNotNull(result.updatedIntent)
        assertEquals(DurableOperationIntent.PHASE_PENDING, result.updatedIntent!!.phase)
    }

    @Test
    fun `2 Stale owner token causes intent to be quarantined and aborted in DB`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup()

        // Insert intent with stale expectedOwnerToken
        val staleIntent = DurableOperationIntent(
            operationToken = "stale-owner-intent",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = "stale-owner-token", // Mismatched!
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(staleIntent)

        val result = recoveryCoordinator.recoverUnresolvedIntent(
            intentToken = "stale-owner-intent",
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED, result.outcome)
        assertEquals("INTENT_OWNER_TOKEN_MISMATCH", result.decisionReason)

        // Verify intent phase updated to PHASE_ABORTED in Room DB
        val dbIntent = durableDao.getIntentByToken("stale-owner-intent")
        assertNotNull(dbIntent)
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `3 Restore epoch mismatch causes intent to be quarantined and aborted`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup(restoreEpoch = 100L)

        val staleEpochIntent = DurableOperationIntent(
            operationToken = "stale-epoch-intent",
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

        val result = recoveryCoordinator.recoverUnresolvedIntent(
            intentToken = "stale-epoch-intent",
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED, result.outcome)
        assertEquals("INTENT_RESTORE_EPOCH_MISMATCH", result.decisionReason)

        val dbIntent = durableDao.getIntentByToken("stale-epoch-intent")
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `4 Schedule revision mismatch causes intent to be quarantined and aborted`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup(revision = 1)

        val staleRevIntent = DurableOperationIntent(
            operationToken = "stale-rev-intent",
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

        val result = recoveryCoordinator.recoverUnresolvedIntent(
            intentToken = "stale-rev-intent",
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED, result.outcome)

        val dbIntent = durableDao.getIntentByToken("stale-rev-intent")
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `5 Issued occurrence in DB without explicit OS registration evidence keeps intent PENDING for dispatch retry`() = runBlocking {
        val (alarm, authority, intent) = insertTestSetup()

        // Pre-insert IssuedOccurrence in Room DB
        val occurrence = IssuedOccurrence(
            issueToken = "already-issued-token-1",
            alarmId = alarm.id,
            scheduledAt = wed0700Ms,
            fireId = "fire-1",
            generationId = 1L,
            status = IssuedOccurrence.STATUS_ISSUED,
            createdAt = nowEpochMs,
            updatedAt = nowEpochMs,
            baseTriggerTime = wed0700Ms,
            alarmGeneration = alarm.generation,
            authorityInstanceToken = authority.instanceToken,
            restoreEpoch = authority.restoreEpoch,
            scheduleRevision = authority.scheduleRevision,
            definitionRevision = authority.definitionRevision
        )
        issuedDao.insertOccurrence(occurrence)

        val result = recoveryCoordinator.recoverUnresolvedIntent(
            intentToken = intent.operationToken,
            nowEpochMs = nowEpochMs,
            osOutcome = OsOperationOutcome.NOT_ATTEMPTED
        )

        // Must NOT transition to PHASE_COMPLETED without explicit OS registration confirmation!
        assertEquals(DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE, result.outcome)

        // Verify intent phase remains PHASE_PENDING in Room DB (NOT PHASE_COMPLETED!)
        val dbIntent = durableDao.getIntentByToken(intent.operationToken)
        assertEquals("Intent phase MUST remain PHASE_PENDING for OS dispatch retry", DurableOperationIntent.PHASE_PENDING, dbIntent!!.phase)

        // Verify 0 duplicate occurrence rows created
        val occurrences = issuedDao.getOccurrencesByAlarmAndScheduledAt(alarm.id, wed0700Ms)
        assertEquals(1, occurrences.size)
    }

    @Test
    fun `6 Incarnation mismatch causes intent to be quarantined and aborted`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup()

        val staleIncarnationIntent = DurableOperationIntent(
            operationToken = "stale-inc-intent",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = "stale-inc-token", // Mismatched incarnation!
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = authority.instanceToken,
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(staleIncarnationIntent)

        val result = recoveryCoordinator.recoverUnresolvedIntent(
            intentToken = "stale-inc-intent",
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED, result.outcome)
        assertEquals("INTENT_STALE_INCARNATION", result.decisionReason)

        val dbIntent = durableDao.getIntentByToken("stale-inc-intent")
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `7 Missing ScheduleAuthority causes intent to be quarantined and aborted`() = runBlocking {
        val (alarm, _, intent) = insertTestSetup()
        authorityDao.deleteAuthorityByAlarmId(alarm.id)

        val result = recoveryCoordinator.recoverUnresolvedIntent(
            intentToken = intent.operationToken,
            nowEpochMs = nowEpochMs
        )

        assertEquals(DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED, result.outcome)

        val dbIntent = durableDao.getIntentByToken(intent.operationToken)
        assertEquals(DurableOperationIntent.PHASE_ABORTED, dbIntent!!.phase)
    }

    @Test
    fun `8 RED-FIRST Negative Control Discriminator`() = runBlocking {
        val (alarm, authority, _) = insertTestSetup()

        // RED: Stale owner token intent is quarantined and aborted
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

        val redResult = recoveryCoordinator.recoverUnresolvedIntent("discriminator-stale-intent", nowEpochMs)
        assertEquals(DurableRecoveryOutcome.INTENT_QUARANTINED_ABORTED, redResult.outcome)

        // GREEN: Valid intent with matching owner token is eligible for dispatch retry
        val validIntent = DurableOperationIntent(
            operationToken = "discriminator-valid-intent",
            alarmId = alarm.id,
            operationEpoch = nowEpochMs,
            incarnationId = authority.instanceToken,
            operationKind = "RECONCILE_RESCHEDULE",
            expectedOwnerToken = authority.instanceToken,
            expectedScheduleRevision = authority.scheduleRevision,
            expectedRestoreEpoch = authority.restoreEpoch,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = alarm.revision.toLong(),
            originalScheduledAt = wed0700Ms
        )
        durableDao.insertValidatedIntent(validIntent)

        val greenResult = recoveryCoordinator.recoverUnresolvedIntent("discriminator-valid-intent", nowEpochMs)
        assertEquals(DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE, greenResult.outcome)
    }

    @Test
    fun `9 Un-connected OS ACCEPTED input maintains PHASE_PENDING until KNO-228 trusted OS adapter attachment`() = runBlocking {
        val (alarm, authority, intent) = insertTestSetup()

        val result = recoveryCoordinator.recoverUnresolvedIntent(
            intentToken = intent.operationToken,
            nowEpochMs = nowEpochMs,
            osOutcome = OsOperationOutcome.ACCEPTED
        )

        assertEquals(DurableRecoveryOutcome.RECOVERY_DISPATCH_ELIGIBLE, result.outcome)
        assertEquals("OS_REGISTRATION_PENDING_RETRY", result.decisionReason)

        val dbIntent = durableDao.getIntentByToken(intent.operationToken)
        assertEquals(DurableOperationIntent.PHASE_PENDING, dbIntent!!.phase)
    }
}
