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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecoveryExecutionFenceTest {

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
        label = "Fence Test Alarm",
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
    fun `exact fingerprint match produces MATCHED_OBSERVATION`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        val fenceReport = fence.verifyFence(plan)

        assertEquals(ExecutionFenceVerificationOutcome.MATCHED_OBSERVATION, fenceReport.outcome)
        assertEquals("MATCHED_OBSERVATION", fenceReport.reasonCode)
        assertTrue(fenceReport.isDiagnosticOnly)
        assertFalse(fenceReport.isExecutable)
        assertFalse(fenceReport.isActionable)
    }

    @Test
    fun `schedule revision changed produces STALE_GENERATION`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        // Mutate DB schedule revision post-planning
        authorityDao.deleteAuthorityByAlarmId(42L)
        authorityDao.insertAuthority(readyAuthority.copy(scheduleRevision = 2L))

        val fenceReport = fence.verifyFence(plan)

        assertEquals(ExecutionFenceVerificationOutcome.STALE_GENERATION, fenceReport.outcome)
        assertEquals("SCHEDULE_REVISION_CHANGED", fenceReport.reasonCode)
    }

    @Test
    fun `incarnation changed produces STALE_GENERATION`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        // Mutate local marker and authority post-planning
        authorityDao.deleteAuthorityByAlarmId(42L)
        authorityDao.insertAuthority(readyAuthority.copy(instanceToken = "token-NEW-INC"))
        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "token-NEW-INC",
            localCompletion = CompletionState.COMPLETE
        )

        val fenceReport = fence.verifyFence(plan)

        assertEquals(ExecutionFenceVerificationOutcome.STALE_GENERATION, fenceReport.outcome)
        assertEquals("INCARNATION_TOKEN_CHANGED", fenceReport.reasonCode)
    }

    @Test
    fun `restore epoch changed produces STALE_GENERATION`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        // Mutate restore epoch post-planning
        authorityDao.insertMeta(testMeta.copy(restoreEpoch = 200L))
        authorityDao.deleteAuthorityByAlarmId(42L)
        authorityDao.insertAuthority(readyAuthority.copy(restoreEpoch = 200L))

        val fenceReport = fence.verifyFence(plan)

        assertEquals(ExecutionFenceVerificationOutcome.STALE_GENERATION, fenceReport.outcome)
        assertEquals("RESTORE_EPOCH_CHANGED", fenceReport.reasonCode)
    }

    @Test
    fun `intent phase changed produces STALE_GENERATION`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val intent = DurableOperationIntent(
            operationToken = "intent-42",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SET",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "IN_FLIGHT",
            intentRevision = 1L,
            originalFireId = "fire-42",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = null
        )
        durableIntentDao.insertValidatedIntent(intent)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        // Fresh collection post-planning observes phase changed to COMPLETED
        val baseCollection = collector.collectEvidence(42L)
        val mutatedCollection = baseCollection.copy(
            roomEvidence = baseCollection.roomEvidence.copy(
                intents = listOf(intent.copy(phase = "COMPLETED"))
            )
        )
        val mockCollector = mockk<RecoveryEvidenceCollector>()
        coEvery { mockCollector.collectEvidence(42L) } returns mutatedCollection

        val mockFence = RecoveryExecutionFence(collector = mockCollector)
        val fenceReport = mockFence.verifyFence(plan)

        assertEquals(ExecutionFenceVerificationOutcome.STALE_GENERATION, fenceReport.outcome)
        assertEquals("INTENT_PHASE_CHANGED", fenceReport.reasonCode)
    }

    @Test
    fun `new global active session produces CONTRADICTORY_EVIDENCE`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        // Mutate active session to another alarm post-planning
        val otherSessionSettings = AppSettings(
            activeAlarmId = 99L,
            activeAlarmScheduledAt = 1000L,
            activeAlarmFireId = "fire-99",
            activeAlarmFiredAt = System.currentTimeMillis(),
            activeAlarmState = "FIRING"
        )
        coEvery { preferencesManager.getCurrentSettings() } returns otherSessionSettings

        val fenceReport = fence.verifyFence(plan)

        assertEquals(ExecutionFenceVerificationOutcome.CONTRADICTORY_EVIDENCE, fenceReport.outcome)
        assertEquals("NEW_GLOBAL_ACTIVE_SESSION", fenceReport.reasonCode)
    }

    @Test
    fun `missing local marker produces NEEDS_EVIDENCE`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        // Local marker unavailable post-planning
        coEvery { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.StorageUnavailable

        val fenceReport = fence.verifyFence(plan)

        assertEquals(ExecutionFenceVerificationOutcome.NEEDS_EVIDENCE, fenceReport.outcome)
        assertEquals("FRESH_STORAGE_UNAVAILABLE", fenceReport.reasonCode)
    }
}
