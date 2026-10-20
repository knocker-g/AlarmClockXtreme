package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult
import com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import io.mockk.coEvery
import io.mockk.mockk
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
class RecoveryDiagnosticBridgeTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var durableIntentDao: DurableOperationIntentDao
    private lateinit var localReader: LocalIncarnationEvidenceReader
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var collector: RecoveryEvidenceCollector
    private lateinit var adjudicator: RecoveryAdjudicator
    private lateinit var bridge: RecoveryDiagnosticBridge

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Bridge Test Alarm",
        isEnabled = true,
        nextTriggerTime = 1000L
    )

    private val testMeta = ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")

    private val readyAuthority = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-verified-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 2000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L,
        actionKey = "act-42"
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
        authorityDao = database.scheduleAuthorityDao()
        durableIntentDao = database.durableOperationIntentDao()
        localReader = mockk()
        preferencesManager = mockk()

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "token-verified-42",
            localCompletion = CompletionState.COMPLETE
        )

        collector = RecoveryEvidenceCollector(
            database = database,
            localReader = localReader,
            preferencesManager = preferencesManager
        )
        adjudicator = RecoveryAdjudicator()

        bridge = RecoveryDiagnosticBridge(
            collector = collector,
            adjudicator = adjudicator
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `complete matching evidence evaluates diagnostically as non-actionable`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val report = bridge.evaluateDiagnosticRecovery(42L)

        assertEquals(42L, report.alarmId)
        assertEquals(BridgeStatus.EVALUATED_DIAGNOSTICALLY, report.status)
        assertTrue("Bridge report MUST be diagnostic only", report.isDiagnosticOnly)
        assertFalse("Bridge report MUST NEVER be actionable", report.isActionable)
        assertNotNull(report.diagnosticDecision)
        assertEquals(RecoveryDecisionOutcome.NO_ACTION, report.diagnosticDecision?.outcome)
    }

    @Test
    fun `report copy cannot override isActionable or isDiagnosticOnly`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val report = bridge.evaluateDiagnosticRecovery(42L)
        val copiedReport = report.copy(status = BridgeStatus.NOT_EVALUATED)

        assertTrue("Copied bridge report MUST remain diagnostic only", copiedReport.isDiagnosticOnly)
        assertFalse("Copied bridge report MUST remain non-actionable", copiedReport.isActionable)
    }

    @Test
    fun `missing Authority returns NOT_EVALUATED with AUTHORITY_ABSENT`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)

        val report = bridge.evaluateDiagnosticRecovery(42L)

        assertEquals(BridgeStatus.NOT_EVALUATED, report.status)
        assertEquals("AUTHORITY_ABSENT", report.reasonCode)
        assertNull(report.diagnosticDecision)
        assertFalse("Bridge report MUST NEVER be actionable", report.isActionable)
    }

    @Test
    fun `missing restore epoch returns NOT_EVALUATED with RESTORE_EPOCH_MISSING`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertAuthority(readyAuthority)

        val report = bridge.evaluateDiagnosticRecovery(42L)

        assertEquals(BridgeStatus.NOT_EVALUATED, report.status)
        assertEquals("RESTORE_EPOCH_MISSING", report.reasonCode)
        assertNull(report.diagnosticDecision)
    }

    @Test
    fun `incomplete local marker returns NOT_EVALUATED`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "token-verified-42",
            localCompletion = CompletionState.PREPARING
        )

        val report = bridge.evaluateDiagnosticRecovery(42L)

        assertEquals(BridgeStatus.NOT_EVALUATED, report.status)
        assertNull(report.diagnosticDecision)
    }

    @Test
    fun `cross-store contradiction returns NOT_EVALUATED`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority.copy(instanceToken = "token-STALE"))

        val report = bridge.evaluateDiagnosticRecovery(42L)

        assertEquals(BridgeStatus.NOT_EVALUATED, report.status)
        assertEquals("COHERENCE_CONTRADICTORY", report.reasonCode)
        assertNull(report.diagnosticDecision)
    }

    @Test
    fun `other active Session returns NOT_EVALUATED with OTHER_ALARM_ACTIVE_SESSION`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val otherSessionSettings = AppSettings(
            activeAlarmId = 99L,
            activeAlarmScheduledAt = 1000L,
            activeAlarmFireId = "fire-99",
            activeAlarmFiredAt = System.currentTimeMillis(),
            activeAlarmState = "FIRING"
        )
        coEvery { preferencesManager.getCurrentSettings() } returns otherSessionSettings

        val report = bridge.evaluateDiagnosticRecovery(42L)

        assertEquals(BridgeStatus.NOT_EVALUATED, report.status)
        assertEquals("OTHER_ALARM_ACTIVE_SESSION", report.reasonCode)
        assertNull(report.diagnosticDecision)
    }

    @Test
    fun `multiple historical intents without unresolved returns NOT_EVALUATED with AMBIGUOUS_HISTORICAL_INTENTS`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val intent1 = DurableOperationIntent(
            operationToken = "intent-hist-1",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SET",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "COMPLETED",
            intentRevision = 1L,
            originalFireId = "fire-1",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = null
        )

        val intent2 = DurableOperationIntent(
            operationToken = "intent-hist-2",
            alarmId = 42L,
            operationEpoch = 101L,
            incarnationId = "token-verified-42",
            operationKind = "SET",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "ABORTED",
            intentRevision = 1L,
            originalFireId = "fire-1",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = null
        )

        durableIntentDao.insertValidatedIntent(intent1)
        durableIntentDao.insertValidatedIntent(intent2)

        val report = bridge.evaluateDiagnosticRecovery(42L)

        assertEquals(BridgeStatus.NOT_EVALUATED, report.status)
        assertEquals("AMBIGUOUS_HISTORICAL_INTENTS", report.reasonCode)
        assertNull(report.diagnosticDecision)
    }

    @Test
    fun `actionable-looking P1 decision remains strictly non-actionable in bridge report`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority.copy(reconcileState = "RECONCILING"))

        val report = bridge.evaluateDiagnosticRecovery(42L)

        assertEquals(BridgeStatus.EVALUATED_DIAGNOSTICALLY, report.status)
        assertEquals(RecoveryDecisionOutcome.RECONCILE_AUTHORITY, report.diagnosticDecision?.outcome)
        assertTrue("Bridge report MUST be diagnostic only", report.isDiagnosticOnly)
        assertFalse("Bridge report MUST NEVER be actionable", report.isActionable)
    }
}
