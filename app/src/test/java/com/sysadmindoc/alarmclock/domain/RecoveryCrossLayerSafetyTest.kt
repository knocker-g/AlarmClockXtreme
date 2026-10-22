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
class RecoveryCrossLayerSafetyTest {

    private lateinit var context: Context
    private lateinit var database: AlarmDatabase
    private lateinit var authorityDao: ScheduleAuthorityDao
    private lateinit var durableIntentDao: DurableOperationIntentDao
    private lateinit var localReader: LocalIncarnationEvidenceReader
    private lateinit var preferencesManager: PreferencesManager

    private lateinit var collector: RecoveryEvidenceCollector
    private lateinit var adjudicator: RecoveryAdjudicator
    private lateinit var bridge: RecoveryDiagnosticBridge
    private lateinit var planner: RecoveryPlanner
    private lateinit var fence: RecoveryExecutionFence

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Cross Layer Test Alarm",
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
        bridge = RecoveryDiagnosticBridge(collector = collector, adjudicator = adjudicator)
        planner = RecoveryPlanner()
        fence = RecoveryExecutionFence(collector = collector)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `end to end matching evidence pipeline stays strictly non actionable`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val collectionReport = collector.collectEvidence(42L)
        assertEquals(EvidenceReadStatus.PROVEN_PRESENT, collectionReport.overallStatus)
        assertEquals(CrossStoreCoherenceClassification.CONSISTENT_OBSERVATION, collectionReport.coherenceClassification)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        assertEquals(BridgeStatus.EVALUATED_DIAGNOSTICALLY, bridgeReport.status)
        assertTrue(bridgeReport.isDiagnosticOnly)
        assertFalse(bridgeReport.isActionable)

        val planReport = planner.planRecovery(bridgeReport)
        assertEquals(RecoveryPlanOutcome.NO_ACTION, planReport.outcome)
        assertTrue(planReport.isDiagnosticOnly)
        assertFalse(planReport.isExecutable)
        assertFalse(planReport.isActionable)

        val fenceReport = fence.verifyFence(planReport)
        assertEquals(ExecutionFenceVerificationOutcome.MATCHED_OBSERVATION, fenceReport.outcome)
        assertTrue(fenceReport.isDiagnosticOnly)
        assertFalse(fenceReport.isExecutable)
        assertFalse(fenceReport.isActionable)
    }

    @Test
    fun `end to end missing authority fails closed across all layers`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        // Missing ScheduleAuthority!

        val collectionReport = collector.collectEvidence(42L)
        assertEquals(EvidenceReadStatus.PROVEN_PRESENT, collectionReport.overallStatus)
        assertNull(collectionReport.roomEvidence.authority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        assertEquals(BridgeStatus.NOT_EVALUATED, bridgeReport.status)
        assertEquals("AUTHORITY_ABSENT", bridgeReport.reasonCode)
        assertFalse(bridgeReport.isActionable)

        val planReport = planner.planRecovery(bridgeReport)
        assertEquals(RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, planReport.outcome)
        assertFalse(planReport.isExecutable)

        val fenceReport = fence.verifyFence(planReport)
        assertEquals(ExecutionFenceVerificationOutcome.NEEDS_EVIDENCE, fenceReport.outcome)
        assertEquals("PLAN_BLOCKED_MISSING_EVIDENCE", fenceReport.reasonCode)
        assertFalse(fenceReport.isActionable)
    }

    @Test
    fun `end to end restore epoch mismatch fails closed across all layers`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta.copy(restoreEpoch = 200L)) // Meta = 200L vs Auth = 100L
        authorityDao.insertAuthority(readyAuthority)

        val collectionReport = collector.collectEvidence(42L)
        assertEquals(CrossStoreCoherenceClassification.CONTRADICTORY, collectionReport.coherenceClassification)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        assertEquals(BridgeStatus.NOT_EVALUATED, bridgeReport.status)
        assertEquals("COHERENCE_CONTRADICTORY", bridgeReport.reasonCode)

        val planReport = planner.planRecovery(bridgeReport)
        assertEquals(RecoveryPlanOutcome.BLOCKED_CONFLICT, planReport.outcome)

        val fenceReport = fence.verifyFence(planReport)
        assertEquals(ExecutionFenceVerificationOutcome.CONTRADICTORY_EVIDENCE, fenceReport.outcome)
        assertEquals("PLAN_BLOCKED_CONFLICT", fenceReport.reasonCode)
    }

    @Test
    fun `end to end instanceToken mismatch fails closed across all layers`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority.copy(instanceToken = "token-STALE"))

        val collectionReport = collector.collectEvidence(42L)
        assertEquals(CrossStoreCoherenceClassification.CONTRADICTORY, collectionReport.coherenceClassification)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        assertEquals(BridgeStatus.NOT_EVALUATED, bridgeReport.status)

        val planReport = planner.planRecovery(bridgeReport)
        assertEquals(RecoveryPlanOutcome.BLOCKED_CONFLICT, planReport.outcome)

        val fenceReport = fence.verifyFence(planReport)
        assertEquals(ExecutionFenceVerificationOutcome.CONTRADICTORY_EVIDENCE, fenceReport.outcome)
    }

    @Test
    fun `end to end session on another alarm produces CONTRADICTORY_EVIDENCE`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val otherSessionSettings = AppSettings(
            activeAlarmId = 99L, // Active session belongs to alarm 99L!
            activeAlarmScheduledAt = 1000L,
            activeAlarmFireId = "fire-99",
            activeAlarmFiredAt = System.currentTimeMillis(),
            activeAlarmState = "FIRING"
        )
        coEvery { preferencesManager.getCurrentSettings() } returns otherSessionSettings

        val collectionReport = collector.collectEvidence(42L)
        assertTrue(collectionReport.globalSessionEvidence.hasGlobalActiveSession)
        assertFalse(collectionReport.globalSessionEvidence.isSameAlarm)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        assertEquals(BridgeStatus.NOT_EVALUATED, bridgeReport.status)
        assertEquals("OTHER_ALARM_ACTIVE_SESSION", bridgeReport.reasonCode)

        val planReport = planner.planRecovery(bridgeReport)
        assertEquals(RecoveryPlanOutcome.BLOCKED_CONFLICT, planReport.outcome)

        val fenceReport = fence.verifyFence(planReport)
        assertEquals(ExecutionFenceVerificationOutcome.CONTRADICTORY_EVIDENCE, fenceReport.outcome)
        assertEquals("PLAN_BLOCKED_CONFLICT", fenceReport.reasonCode)
    }

    @Test
    fun `post planning schedule revision drift is detected by execution fence`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val planReport = planner.planRecovery(bridgeReport)

        // Mutate schedule revision post-planning
        authorityDao.deleteAuthorityByAlarmId(42L)
        authorityDao.insertAuthority(readyAuthority.copy(scheduleRevision = 2L))

        val fenceReport = fence.verifyFence(planReport)

        assertEquals(ExecutionFenceVerificationOutcome.STALE_GENERATION, fenceReport.outcome)
        assertEquals("SCHEDULE_REVISION_CHANGED", fenceReport.reasonCode)
        assertFalse(fenceReport.isActionable)
    }

    @Test
    fun `unknown OS operation outcome produces QUARANTINE in P1 and BLOCKED_CONFLICT in P3A`() = runBlocking {
        val snapshotWithUnknownOs = RecoveryEvidenceSnapshot(
            alarmId = 42L,
            authority = readyAuthority,
            restoreEpochMeta = 100L,
            durableIntent = null,
            activeSession = null,
            localIncarnationId = "token-verified-42",
            osOutcome = OsOperationOutcome.UNKNOWN, // UNKNOWN OS outcome!
            definitionExists = true
        )

        val p1Decision = adjudicator.adjudicate(snapshotWithUnknownOs)
        assertEquals(RecoveryDecisionOutcome.QUARANTINE, p1Decision.outcome)
        assertEquals("OS_OUTCOME_UNKNOWN", p1Decision.reasonCode)
    }
}
