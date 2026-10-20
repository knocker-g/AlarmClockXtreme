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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RecoveryPlannerTest {

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

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Planner Test Alarm",
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
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `valid diagnostic NO_ACTION produces NO_ACTION plan outcome`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        assertEquals(RecoveryPlanOutcome.NO_ACTION, plan.outcome)
        assertTrue(plan.isDiagnosticOnly)
        assertFalse(plan.isExecutable)
        assertFalse(plan.isActionable)
        assertNotNull(plan.fingerprint.authorityFingerprint)
    }

    @Test
    fun `reconciling authority produces INSPECT_AUTHORITY plan outcome`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority.copy(reconcileState = "RECONCILING"))

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        assertEquals(RecoveryPlanOutcome.INSPECT_AUTHORITY, plan.outcome)
        assertTrue(plan.isDiagnosticOnly)
        assertFalse(plan.isExecutable)
    }

    @Test
    fun `session effects candidate produces INSPECT_SESSION_EFFECTS plan outcome`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val firingSettings = AppSettings(
            activeAlarmId = 42L,
            activeAlarmScheduledAt = 2000L,
            activeAlarmFireId = "fire-42",
            activeAlarmFiredAt = System.currentTimeMillis(),
            activeAlarmState = "FIRING"
        )
        coEvery { preferencesManager.getCurrentSettings() } returns firingSettings

        val intent = DurableOperationIntent(
            operationToken = "intent-42",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SET",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "COMPLETED",
            intentRevision = 1L,
            originalFireId = "fire-42",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = null
        )
        durableIntentDao.insertValidatedIntent(intent)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        assertEquals(RecoveryPlanOutcome.INSPECT_SESSION_EFFECTS, plan.outcome)
        assertNotNull(plan.fingerprint.sessionFingerprint)
        assertFalse(plan.isExecutable)
    }

    @Test
    fun `missing Authority produces BLOCKED_MISSING_EVIDENCE`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        assertEquals(RecoveryPlanOutcome.BLOCKED_MISSING_EVIDENCE, plan.outcome)
        assertFalse(plan.isExecutable)
    }

    @Test
    fun `ambiguous unresolved intents produces BLOCKED_CONFLICT`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val ambiguousBridgeReport = bridgeReport.copy(
            status = BridgeStatus.NOT_EVALUATED,
            reasonCode = "AMBIGUOUS_UNRESOLVED_INTENTS",
            collectionReport = bridgeReport.collectionReport.copy(hasMultipleUnresolvedIntents = true)
        )

        val plan = planner.planRecovery(ambiguousBridgeReport)

        assertEquals(RecoveryPlanOutcome.BLOCKED_CONFLICT, plan.outcome)
        assertFalse(plan.isExecutable)
    }

    @Test
    fun `conflicting generation instanceToken mismatch produces BLOCKED_CONFLICT`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority.copy(instanceToken = "token-STALE"))

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan = planner.planRecovery(bridgeReport)

        assertEquals(RecoveryPlanOutcome.BLOCKED_CONFLICT, plan.outcome)
    }

    @Test
    fun `deterministic repeated evaluation produces identical plan`() = runBlocking {
        database.alarmDao().insert(testAlarm)
        authorityDao.insertMeta(testMeta)
        authorityDao.insertAuthority(readyAuthority)

        val bridgeReport = bridge.evaluateDiagnosticRecovery(42L)
        val plan1 = planner.planRecovery(bridgeReport)
        val plan2 = planner.planRecovery(bridgeReport)

        assertEquals(plan1.outcome, plan2.outcome)
        assertEquals(plan1.reasonCode, plan2.reasonCode)
        assertEquals(plan1.fingerprint, plan2.fingerprint)
    }
}
