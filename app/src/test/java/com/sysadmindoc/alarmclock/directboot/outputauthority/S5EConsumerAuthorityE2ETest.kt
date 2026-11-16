package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.BootProvenance
import com.sysadmindoc.alarmclock.directboot.CeSlotReleaseResult
import com.sysadmindoc.alarmclock.directboot.CeTakeoverIntakeResult
import com.sysadmindoc.alarmclock.directboot.CeTakeoverStorageState
import com.sysadmindoc.alarmclock.directboot.DeliveryAppendResult
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmService
import com.sysadmindoc.alarmclock.directboot.DirectBootCeTakeoverStore
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryRecord
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwner
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootEventConsumerEngine
import com.sysadmindoc.alarmclock.directboot.DirectBootFiredWitnessLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootFiredWitnessRecord
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.DirectBootMissedRecord
import com.sysadmindoc.alarmclock.directboot.DirectBootMissedStore
import com.sysadmindoc.alarmclock.directboot.DirectBootQueueItem
import com.sysadmindoc.alarmclock.directboot.DirectBootStorageLock
import com.sysadmindoc.alarmclock.directboot.DirectBootV2NormalIntentIdentity
import com.sysadmindoc.alarmclock.directboot.HandoverClaim
import com.sysadmindoc.alarmclock.directboot.LedgerStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRetirementResult
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimWriteResult
import com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome
import com.sysadmindoc.alarmclock.directboot.MissedRecordResult
import com.sysadmindoc.alarmclock.directboot.MissedStoreStorageState
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.OwnerState
import com.sysadmindoc.alarmclock.directboot.WitnessAppendResult
import com.sysadmindoc.alarmclock.directboot.WitnessLedgerState
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.receiver.MissedAlarmReplayPolicy
import com.sysadmindoc.alarmclock.receiver.MissedAlarmUnlockReceiver
import com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.service.SmartAlarmService
import com.sysadmindoc.alarmclock.worker.FireWatchdogWorker
import com.sysadmindoc.alarmclock.worker.WakeConfirmOccurrenceStore
import com.sysadmindoc.alarmclock.worker.WakeConfirmWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Isolated Flag-ON End-to-End Integration, Consumer Step 1..7, and Crash/Rollback Falsification Suite
 * for KNO-75 S5-E.
 *
 * IMPORTANT: Every test isolates Flag ON to this fixture and restores SharedOutputAuthorityGate.getInstance(context)
 * back to `isEnabled = false` in [tearDown]. Production default Flag remains OFF.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class S5EConsumerAuthorityE2ETest {

    private lateinit var context: Context
    private lateinit var deContext: Context
    private lateinit var ceContext: Context
    private lateinit var database: AlarmDatabase
    private lateinit var gate: SharedOutputAuthorityGate
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler

    private val baseTime = 1_700_000_000_000L

    private val primaryAlarm = Alarm(
        id = 500L,
        hour = 7,
        minute = 0,
        label = "S5E E2E Alarm",
        isEnabled = true,
        volume = 85,
        wakeConfirmEnabled = true,
        wakeConfirmDelayMinutes = 10
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deContext = context.createDeviceProtectedStorageContext()
        ceContext = context
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val userManager = context.getSystemService(UserManager::class.java)
        shadowOf(userManager).setUserUnlocked(true)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        resetAllStoresAndSeams()

        gate = SharedOutputAuthorityGate.getInstance(ceContext).apply {
            isEnabled = true
            bootCount = 10L
            bootCountProvider = null
            processSessionToken = "proc-e2e-session"
            arbiter.clearForTesting()
        }

        alarmRepository = mockk(relaxed = true)
        incidentRepository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        coEvery { alarmRepository.getById(500L) } returns primaryAlarm
        coEvery { alarmRepository.getById(501L) } returns primaryAlarm.copy(id = 501L, label = "Second Alarm")
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(repeatMissedAlarms = true)
        every { preferencesManager.getCachedSettings() } returns AppSettings(repeatMissedAlarms = true)
        every { preferencesManager.isSettingsLoaded } returns true

        com.sysadmindoc.alarmclock.receiver.missedAlarmTestAlarmRepository = alarmRepository
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestIncidentRepository = incidentRepository
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestPreferencesManager = preferencesManager
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
        resetAllStoresAndSeams()
        database.close()
    }

    private fun resetAllStoresAndSeams() {
        SharedOutputAuthorityGate.getInstance(ceContext).apply {
            isEnabled = false
            bootCount = null
            bootCountProvider = null
            processSessionToken = SharedOutputAuthorityGate.generateProcessSessionToken()
            arbiter.clearForTesting()
        }
        DirectBootLegacyClaimStore.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootMissedStore.resetSeamsForTesting()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootAlarmReceiver.resetSeamsForTesting()
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        AlarmFullScreenFallback.clearForTesting()
        runBlocking { MissedAlarmReplayPolicy.resetForTesting() }

        deContext.getSharedPreferences(DirectBootLegacyClaimStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        deContext.getSharedPreferences(WakeConfirmOccurrenceStore.DE_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ceContext.getSharedPreferences(WakeConfirmOccurrenceStore.CE_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        ceContext.getSharedPreferences("missed_alarm_state", Context.MODE_PRIVATE).edit().clear().commit()
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootCeTakeoverStore.unlockGateSeam = { true }

        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = null
        com.sysadmindoc.alarmclock.receiver.roomReceiptCheckerSeam = null
        com.sysadmindoc.alarmclock.worker.outputAuthorityGateSeam = null
        com.sysadmindoc.alarmclock.worker.roomReceiptCheckerSeam = null
        com.sysadmindoc.alarmclock.service.smartAlarmOutputAuthorityGateSeam = null
        com.sysadmindoc.alarmclock.service.smartAlarmRoomReceiptCheckerSeam = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmOutputAuthorityGateSeam = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmRoomReceiptCheckerSeam = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestAlarmRepository = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestIncidentRepository = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestPreferencesManager = null
    }

    private fun seedTerminalLegacyClaim(
        key: OutputOccurrenceKey,
        source: LegacyClaimSource = LegacyClaimSource.ALARM_RECEIVER,
        outcome: LegacyTerminalOutcome = LegacyTerminalOutcome.CONSUMED_DISMISSED
    ) {
        val rec = LegacyClaimRecord(
            alarmId = key.alarmId,
            scheduledAt = key.scheduledAt,
            refireCount = key.refireCount,
            source = source,
            claimNonce = "n-init-${key.alarmId}-${key.scheduledAt}-${key.refireCount}",
            processSessionToken = gate.processSessionToken,
            bootCount = 10L,
            state = LegacyClaimState.START_REQUESTED
        )
        assertTrue(DirectBootLegacyClaimStore.writeInitialClaim(deContext, rec) is LegacyClaimWriteResult.Committed)
        assertTrue(
            DirectBootLegacyClaimStore.transition(
                context = deContext,
                key = key,
                expectedFrom = LegacyClaimState.START_REQUESTED,
                toState = LegacyClaimState.TERMINAL,
                expectedNonce = rec.claimNonce,
                newNonce = "n-term-${key.alarmId}-${key.scheduledAt}-${key.refireCount}",
                processSessionToken = gate.processSessionToken,
                bootCount = 10L,
                terminalOutcome = outcome
            ) is LegacyClaimWriteResult.Committed
        )
    }

    private fun seedUnknownLegacyClaim(
        key: OutputOccurrenceKey,
        procToken: String = "proc-dead-prior",
        bootCount: Long = 9L,
        nonce: String = "n-unk-${key.alarmId}-${key.scheduledAt}"
    ) {
        val rec = LegacyClaimRecord(
            alarmId = key.alarmId,
            scheduledAt = key.scheduledAt,
            refireCount = key.refireCount,
            source = LegacyClaimSource.ALARM_RECEIVER,
            claimNonce = nonce,
            processSessionToken = procToken,
            bootCount = bootCount,
            state = LegacyClaimState.START_REQUESTED
        )
        assertTrue(DirectBootLegacyClaimStore.writeInitialClaim(deContext, rec) is LegacyClaimWriteResult.Committed)
        assertTrue(
            DirectBootLegacyClaimStore.transition(
                context = deContext,
                key = key,
                expectedFrom = LegacyClaimState.START_REQUESTED,
                toState = LegacyClaimState.UNKNOWN,
                expectedNonce = nonce,
                newNonce = "n-unk-pinned-${key.alarmId}",
                processSessionToken = procToken,
                bootCount = bootCount
            ) is LegacyClaimWriteResult.Committed
        )
    }

    private fun createInjectedAlarmService(): AlarmService {
        val service = Robolectric.buildService(AlarmService::class.java).get()
        service.repository = alarmRepository
        service.alarmScheduler = alarmScheduler
        service.eventRepository = mockk(relaxed = true)
        service.alarmIncidentRepository = incidentRepository
        service.preferencesManager = preferencesManager
        service.webhookService = mockk(relaxed = true)
        service.wearNextAlarmBridge = mockk(relaxed = true)
        service.dismissActionExecutor = mockk(relaxed = true)
        service.weatherRepository = mockk(relaxed = true)
        service.calendarRepository = mockk(relaxed = true)
        service.outputAuthorityGate = gate
        service.roomReceiptCheckerSeam = { alarmId, schedAt ->
            database.directBootOccurrenceReceiptDao()
                .findByAlarmId(alarmId)
                .any { it.triggerTime == schedAt }
        }
        return service
    }

    // =========================================================================
    // SECTION 3: CONSUMER INTEGRATION (STEPS 1..7)
    // =========================================================================

    @Test
    fun s5e_consumer_deliveredWithProvenance_retiresSteps1To7InOrder() = runBlocking {
        val trigger = baseTime + 10_000L
        val key = OutputOccurrenceKey(500L, trigger, 0)
        seedTerminalLegacyClaim(key)

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigger,
            deliveredAt = trigger + 50L,
            generation = 1,
            instanceToken = "tok_del_prov",
            provenance = "BOOT",
            restoreEpoch = 2L
        )
        val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigger,
            generation = 1,
            instanceToken = "tok_del_prov",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = trigger + 55L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord) is WitnessAppendResult.Appended)
        assertTrue(DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, trigger + 100L) is CeTakeoverIntakeResult.RecordedConfirmed)

        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue("Expected Success across steps 1..7, got $result", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(500L, trigger, 1, "tok_del_prov")
        val receipt = database.directBootOccurrenceReceiptDao().findByOccurrenceKey(occKey)
        assertNotNull("Room 38 receipt must exist", receipt)
        assertEquals("CONSUMED_DELIVERED", receipt?.consumerOutcome)
        assertTrue("DE DeliveryLedger must be ValidEmpty", DirectBootDeliveryLedger.inspectStorageState(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue("DE WitnessLedger must be ValidEmpty", DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue("CE TakeoverStore must be ValidEmpty", DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)

        val claimState = DirectBootLegacyClaimStore.inspectStorageState(deContext)
        assertTrue(
            "Step 7 MUST retire matching TERMINAL Legacy claim when Delivered provenance=\"BOOT\" is verified, got $claimState",
            claimState is LegacyClaimStorageState.ValidEmpty
        )
    }

    @Test
    fun s5e_consumer_missedWithClassification_retiresTerminalClaimOnlyAfterCeRelease() = runBlocking {
        val trigger = baseTime + 20_000L
        val key = OutputOccurrenceKey(500L, trigger, 0)
        seedTerminalLegacyClaim(key)

        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigger,
            missedAt = trigger + 500L,
            generation = 1,
            instanceToken = "tok_missed_overdue",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 3L
        )
        assertTrue(DirectBootMissedStore.recordMissed(deContext, missedRecord) is MissedRecordResult.Recorded)
        assertTrue(DirectBootCeTakeoverStore.recordMissed(ceContext, missedRecord, trigger + 600L) is CeTakeoverIntakeResult.RecordedConfirmed)

        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue("Expected Success across steps 1..7 for MISSED record, got $result", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(500L, trigger, 1, "tok_missed_overdue")
        val receipt = database.directBootOccurrenceReceiptDao().findByOccurrenceKey(occKey)
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)
        assertTrue("DE MissedStore must be ValidEmpty", DirectBootMissedStore.inspectStorageState(deContext) is MissedStoreStorageState.ValidEmpty)
        assertTrue("CE TakeoverStore must be ValidEmpty", DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidEmpty)

        val claimState = DirectBootLegacyClaimStore.inspectStorageState(deContext)
        assertTrue(
            "Step 7 MUST retire matching TERMINAL Legacy claim for MISSED classification=\"OVERDUE\", got $claimState",
            claimState is LegacyClaimStorageState.ValidEmpty
        )
    }

    @Test
    fun s5e_consumer_receiptForTrigger1_doesNotRetireTrigger2OrWakeConfirmRefire1() = runBlocking {
        val trig1 = baseTime + 30_000L
        val trig2 = baseTime + 90_000L
        val keyTrig1 = OutputOccurrenceKey(500L, trig1, 0)
        val keyTrig2 = OutputOccurrenceKey(500L, trig2, 0)
        val keyRefire1 = OutputOccurrenceKey(500L, trig1, 1)

        seedTerminalLegacyClaim(keyTrig1)
        seedTerminalLegacyClaim(keyTrig2)
        seedTerminalLegacyClaim(keyRefire1, source = LegacyClaimSource.WAKE_CONFIRM)

        val del1 = DirectBootDeliveryRecord.createNormal(
            alarmId = 500L,
            triggerTime = trig1,
            deliveredAt = trig1 + 50L,
            generation = 1,
            instanceToken = "tok_trig1",
            provenance = "BOOT",
            restoreEpoch = 2L
        )
        val witness1 = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 500L,
            triggerTime = trig1,
            generation = 1,
            instanceToken = "tok_trig1",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = trig1 + 55L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, del1)
        DirectBootFiredWitnessLedger.appendWitness(deContext, witness1)
        DirectBootCeTakeoverStore.recordDelivered(ceContext, del1, trig1 + 100L)

        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue("Expected Success, got $result", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val claims = (DirectBootLegacyClaimStore.inspectStorageState(deContext) as LegacyClaimStorageState.ValidWithRecords).records
        assertFalse("trig1 primary claim MUST be retired", claims.any { it.key == keyTrig1 })
        assertTrue("trig2 claim MUST NOT be retired by trig1 receipt", claims.any { it.key == keyTrig2 })
        assertTrue("WakeConfirm refireCount=1 claim MUST NOT be retired by trig1 receipt", claims.any { it.key == keyRefire1 })
    }

    @Test
    fun s5e_consumer_unknownClaim_preservedOnRestoreEpochConflict_reconciledAndRetiredWithQualifiedCeProof() = runBlocking {
        // Subcase A: RestoreEpochConflict (epoch = 0L) -> UNKNOWN MUST stay pinned!
        val trigA = baseTime + 40_000L
        val keyA = OutputOccurrenceKey(500L, trigA, 0)
        seedUnknownLegacyClaim(keyA)

        val legacyEpoch0 = DirectBootDeliveryRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigA,
            deliveredAt = trigA + 50L,
            generation = 1,
            instanceToken = "tok_epoch0",
            restoreEpoch = 0L,
            schemaVersion = 1
        )
        DirectBootCeTakeoverStore.recordDelivered(ceContext, legacyEpoch0, trigA + 100L)

        val resA = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue(resA is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        val afterA = (DirectBootLegacyClaimStore.inspectStorageState(deContext) as LegacyClaimStorageState.ValidWithRecords).records
        assertEquals(
            "UNKNOWN claim MUST stay pinned when CE proof is RestoreEpochConflict",
            LegacyClaimState.UNKNOWN,
            afterA.first { it.key == keyA }.state
        )

        // Release quarantined CE slot manually before testing Subcase B on a new occurrence
        DirectBootCeTakeoverStore.releaseSlotWithResult(
            ceContext,
            DirectBootCeTakeoverStore.computeOccurrenceKey(500L, trigA, 1, "tok_epoch0")
        )

        // Subcase B: Qualified CE Proof + V2 absent -> UNKNOWN reconciles to TERMINAL(RECONCILED_CE) & retires!
        val trigB = baseTime + 41_000L
        val keyB = OutputOccurrenceKey(500L, trigB, 0)
        seedUnknownLegacyClaim(keyB, procToken = "proc-dead-prior", bootCount = 9L)

        val qualifiedDel = DirectBootDeliveryRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigB,
            deliveredAt = trigB + 50L,
            generation = 1,
            instanceToken = "tok_qualified_b",
            provenance = "COLD_BOOT",
            restoreEpoch = 4L
        )
        val qualifiedWitness = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigB,
            generation = 1,
            instanceToken = "tok_qualified_b",
            restoreEpoch = 4L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = trigB + 55L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, qualifiedDel)
        DirectBootFiredWitnessLedger.appendWitness(deContext, qualifiedWitness)
        DirectBootCeTakeoverStore.recordDelivered(ceContext, qualifiedDel, trigB + 100L)

        val resB = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue("Expected Success reconciling UNKNOWN with qualified CE proof, got $resB", resB is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        val afterB = (DirectBootLegacyClaimStore.inspectStorageState(deContext) as LegacyClaimStorageState.ValidWithRecords).records
        assertFalse("Reconciled UNKNOWN claim keyB MUST be retired", afterB.any { it.key == keyB })
        assertEquals("Unqualified UNKNOWN claim keyA MUST STILL stay pinned", LegacyClaimState.UNKNOWN, afterB.first { it.key == keyA }.state)
    }

    @Test
    fun s5e_consumer_activeNonTerminalClaim_neverRetiredInStep7() = runBlocking {
        val trigger = baseTime + 45_000L
        val key = OutputOccurrenceKey(500L, trigger, 0)
        val res = gate.reserveForServiceStart(ceContext, key, LegacyClaimSource.ALARM_RECEIVER)
        val nonce = (res as OutputAuthorizationResult.Authorized).activeNonce
        val claim = gate.claimOutput(ceContext, key, nonce, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce
        gate.observePhysicalOutput(ceContext, key, activeNonce, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigger,
            deliveredAt = trigger + 50L,
            generation = 1,
            instanceToken = "tok_non_term",
            restoreEpoch = 2L
        )
        val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigger,
            generation = 1,
            instanceToken = "tok_non_term",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = trigger + 55L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord)
        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, trigger + 100L)

        val execRes = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue(
            "Active non-terminal OUTPUT_OBSERVED claim MUST fail Step 7 fail-closed, got $execRes",
            execRes is DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement
        )
        val inc = execRes as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement
        assertEquals("LEGACY_RETIREMENT", inc.failures.single().stage)
        val claimRec = (DirectBootLegacyClaimStore.inspectStorageState(deContext) as LegacyClaimStorageState.ValidWithRecords).records.single()
        assertEquals(LegacyClaimState.OUTPUT_OBSERVED, claimRec.state)
    }

    // =========================================================================
    // SECTION 4: E2E AUTHORITY CONTRACT (15 SCENARIOS)
    // =========================================================================

    @Test
    fun s5e_e2e_primaryLegacyAlarm_fullLifecycleAndRetirement() {
        val trigger = baseTime + 50_000L
        val key = OutputOccurrenceKey(500L, trigger, 0)
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate

        val rxIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 500L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, trigger)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-e2e-500")
        }
        AlarmReceiver().onReceive(ceContext, rxIntent)
        val svcIntent = shadowOf(ceContext as Application).nextStartedService
        assertNotNull(svcIntent)
        val handoverNonce = svcIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE)!!

        val claim = gate.claimOutput(ceContext, key, handoverNonce, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
        val activeNonce = (claim as OutputAuthorizationResult.Authorized).activeNonce
        assertTrue(gate.observePhysicalOutput(ceContext, key, activeNonce, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false }) is OutputAuthorizationResult.Authorized)
        assertTrue(gate.completeTerminal(ceContext, key, activeNonce, LegacyTerminalOutcome.CONSUMED_DISMISSED) is OutputAuthorizationResult.Authorized)

        val receipt = DirectBootOccurrenceReceipt(
            occurrenceKey = "occ_500_$trigger",
            sourceRecordId = "rec-500",
            recordType = "DELIVERED",
            alarmId = 500L,
            triggerTime = trigger,
            generation = 1,
            instanceToken = "tok-500",
            restoreEpoch = 5L,
            sourcePayloadHash = DirectBootEventConsumerEngine.calculateCanonicalPayloadHashV2(
                "rec-500", "DELIVERED", 500L, trigger, trigger + 100L, 1, "tok-500", 5L, ""
            ),
            consumerOutcome = "CONSUMED_DELIVERED",
            completedAt = trigger + 100L
        )
        database.directBootOccurrenceReceiptDao().insert(receipt)

        val retired = gate.retireClaimWithProof(ceContext, database, key)
        assertTrue("Expected Retired, got $retired", retired is LegacyClaimRetirementResult.Retired)
        assertTrue(DirectBootLegacyClaimStore.inspectStorageState(deContext) is LegacyClaimStorageState.ValidEmpty)
    }

    @Test
    fun s5e_e2e_serviceAndFullScreenFallbackRace_singleWinnerOnly() {
        val trigger = baseTime + 51_000L
        val key = OutputOccurrenceKey(500L, trigger, 0)
        val handoverNonce = (gate.reserveForServiceStart(ceContext, key, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce

        val serviceClaim = gate.claimOutput(ceContext, key, handoverNonce, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
        assertTrue(serviceClaim is OutputAuthorizationResult.Authorized)

        val fallbackPosted = AlarmFullScreenFallback.post(
            context = ceContext,
            alarmId = 500L,
            scheduledAt = trigger,
            fireId = "fire-race",
            hideLabel = false,
            label = "Race",
            claimNonce = handoverNonce,
            roomReceiptChecker = { _, _ -> false },
            authorityGate = gate
        )
        assertFalse("Racing Fallback MUST fail closed after Service claimed nonce", fallbackPosted)
        assertEquals(OutputSinkType.ALARM_SERVICE_1001, gate.arbiter.activeHandle?.sink)
    }

    @Test
    fun s5e_e2e_multiIngress_receiverWatchdogSmartAlarmMissedUnlock_coherence() {
        val trigger = baseTime + 52_000L
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate
        com.sysadmindoc.alarmclock.worker.outputAuthorityGateSeam = gate
        com.sysadmindoc.alarmclock.worker.roomReceiptCheckerSeam = { _, _ -> false }
        com.sysadmindoc.alarmclock.service.smartAlarmOutputAuthorityGateSeam = gate
        com.sysadmindoc.alarmclock.receiver.missedAlarmOutputAuthorityGateSeam = gate
        com.sysadmindoc.alarmclock.receiver.missedAlarmRoomReceiptCheckerSeam = { _, _ -> false }

        // 1. AlarmReceiver wins occurrence (500, trigger)
        AlarmReceiver().onReceive(ceContext, Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 500L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, trigger)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-multi-1")
        })
        assertNotNull(shadowOf(ceContext as Application).nextStartedService)

        // 2. FireWatchdogWorker for the SAME occurrence MUST reject
        val wp = mockk<WorkerParameters>(relaxed = true)
        every { wp.inputData } returns androidx.work.workDataOf(
            FireWatchdogWorker.KEY_ALARM_ID to 500L,
            FireWatchdogWorker.KEY_SCHEDULED_AT to trigger
        )
        coEvery { incidentRepository.occurrenceDeliveryCount(500L, trigger) } returns 0
        val wdRes = runBlocking {
            FireWatchdogWorker(ceContext, wp, alarmRepository, preferencesManager, incidentRepository).doWork()
        }
        assertEquals(ListenableWorker.Result.success(), wdRes)
        assertNull("FireWatchdog MUST NOT duplicate service start", shadowOf(ceContext as Application).nextStartedService)

        // 3. SmartAlarmService for a DIFFERENT occurrence (500, trigger + 1000) succeeds with nonce
        val smartSvc = Robolectric.buildService(SmartAlarmService::class.java).get().apply {
            this.alarmRepository = this@S5EConsumerAuthorityE2ETest.alarmRepository
            this.preferencesManager = this@S5EConsumerAuthorityE2ETest.preferencesManager
            this.alarmIncidentRepository = this@S5EConsumerAuthorityE2ETest.incidentRepository
            this.actigraphyRepository = mockk(relaxed = true)
            this.alarmScheduler = mockk(relaxed = true)
        }
        SmartAlarmService::class.java.getDeclaredField("alarmId").apply { isAccessible = true }.set(smartSvc, 500L)
        SmartAlarmService::class.java.getDeclaredField("targetTimeMs").apply { isAccessible = true }.set(smartSvc, trigger + 1000L)
        smartSvc.callStartAlarmServiceForTesting()
        val smartStarted = shadowOf(ceContext as Application).nextStartedService
        assertNotNull("SmartAlarm for distinct occurrence must start", smartStarted)
        assertNotNull(smartStarted.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE))

        // 4. MissedAlarmUnlockReceiver for SAME occurrence as trigger MUST fail closed
        ceContext.getSharedPreferences("missed_alarm_state", Context.MODE_PRIVATE).edit()
            .putLong("last_missed_id", 500L)
            .putLong("last_missed_at", trigger)
            .putLong("last_missed_dispatched_at", 0L)
            .commit()
        coEvery { incidentRepository.occurrenceDeliveryCount(500L, trigger) } returns 0
        MissedAlarmUnlockReceiver().onReceive(ceContext, Intent(Intent.ACTION_USER_PRESENT))
        Thread.sleep(300)
        assertNull("MissedUnlock MUST NOT duplicate already-claimed occurrence", shadowOf(ceContext as Application).nextStartedService)
    }

    @Test
    fun s5e_e2e_directBootCrossLaneCollision_andV2DefaultDenyLegacyOnlyHeld() {
        val trigger = baseTime + 53_000L
        val key = OutputOccurrenceKey(500L, trigger, 0)

        // Cross-lane A: DirectBootMissedStore owns occurrence -> Legacy reserve MUST reject V2LaneOwnedRejected
        val missedV2 = DirectBootMissedRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigger,
            missedAt = trigger + 100L,
            generation = 1,
            instanceToken = "tok_cross_missed",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 2L
        )
        assertTrue(DirectBootMissedStore.recordMissed(deContext, missedV2) is MissedRecordResult.Recorded)
        val crossRes = gate.reserveForServiceStart(ceContext, key, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue(
            "Legacy reserve MUST reject V2LaneOwnedRejected when DirectBootMissedStore holds occurrence, got $crossRes",
            crossRes is OutputAuthorizationResult.V2LaneOwnedRejected
        )

        // Cross-lane B: Verify V2_OUTPUT: DEFAULT_DENY and KNO-228: LEGACY_ONLY on DirectBootAlarmService
        val v2StartIntent = Intent(ceContext, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 501L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, trigger + 1000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, DirectBootV2NormalIntentIdentity.KIND_NORMAL)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_v2_deny")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 2L)
        }
        val dbSvcController = Robolectric.buildService(DirectBootAlarmService::class.java).create()
        val dbSvc = dbSvcController.get()
        dbSvc.onStartCommand(v2StartIntent, 0, 1)
        assertTrue("V2 output MUST remain DEFAULT_DENY and stop self when not active", shadowOf(dbSvc).isStoppedBySelf)
    }

    @Test
    fun s5e_e2e_wakeConfirmRefire1To3_andOneTimeDisabledLegitimateRefire() {
        val trigger = baseTime + 54_000L
        val key0 = OutputOccurrenceKey(500L, trigger, 0)
        val h0 = (gate.reserveForServiceStart(ceContext, key0, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val a0 = (gate.claimOutput(ceContext, key0, h0, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false }) as OutputAuthorizationResult.Authorized).activeNonce
        gate.observePhysicalOutput(ceContext, key0, a0, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
        gate.completeTerminal(ceContext, key0, a0, LegacyTerminalOutcome.CONSUMED_DISMISSED)

        // Commit dismiss lineage for ONE_TIME alarm (isEnabled = false allowed)
        val commit0 = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = deContext,
            alarmId = 500L,
            scheduledAt = trigger,
            fireId = "fire-500-0",
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = false,
            nowMillis = trigger + 1000L,
            delayMinutes = 10,
            scheduledDueAtMillis = trigger + 601_000L
        )
        assertTrue("Expected Committed, got $commit0", commit0 is WakeConfirmOccurrenceStore.CommitResult.Committed)

        for (refire in 1..3) {
            val prevToken = WakeConfirmOccurrenceStore.findRecord(deContext, 500L, trigger)!!.stepToken
            val consumeRes = WakeConfirmOccurrenceStore.consumeRefireStep(deContext, 500L, trigger, refire - 1, prevToken)
            assertTrue("Expected Consumed for refire $refire, got $consumeRes", consumeRes is WakeConfirmOccurrenceStore.RefireConsumeResult.Consumed)

            val keyR = OutputOccurrenceKey(500L, trigger, refire)
            val rRes = gate.reserveWakeConfirmRefire(ceContext, keyR)
            assertTrue("Refire $refire MUST be authorized, got $rRes", rRes is OutputAuthorizationResult.Authorized)
            val nonceR = (rRes as OutputAuthorizationResult.Authorized).activeNonce
            val cR = (gate.claimOutput(ceContext, keyR, nonceR, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false }) as OutputAuthorizationResult.Authorized).activeNonce
            gate.observePhysicalOutput(ceContext, keyR, cR, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
            gate.completeTerminal(ceContext, keyR, cR, LegacyTerminalOutcome.CONSUMED_DISMISSED)

            if (refire < 3) {
                val commitR = WakeConfirmOccurrenceStore.commitDismissLineage(
                    context = deContext,
                    alarmId = 500L,
                    scheduledAt = trigger,
                    fireId = "fire-500-$refire",
                    refireCount = refire,
                    wakeConfirmEnabled = true,
                    isRecurringSchedule = false,
                    nowMillis = trigger + (refire + 1) * 1000L,
                    delayMinutes = 10,
                    scheduledDueAtMillis = trigger + (refire + 1) * 600_000L
                )
                assertTrue("Expected Committed for refire $refire, got $commitR", commitR is WakeConfirmOccurrenceStore.CommitResult.Committed)
            }
        }

        // 4th refire MUST be rejected
        val r4 = gate.reserveWakeConfirmRefire(ceContext, OutputOccurrenceKey(500L, trigger, 4))
        assertTrue("4th refire MUST be rejected, got $r4", r4 is OutputAuthorizationResult.WakeConfirmUnauthorized)
    }

    @Test
    fun s5e_e2e_roomDeCeCrashRecovery_convergesWithoutDuplicate() = runBlocking {
        val trigger = baseTime + 55_000L
        val key = OutputOccurrenceKey(500L, trigger, 0)
        seedTerminalLegacyClaim(key)

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigger,
            deliveredAt = trigger + 50L,
            generation = 1,
            instanceToken = "tok_crash_conv",
            provenance = "BOOT",
            restoreEpoch = 2L
        )
        val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 500L,
            triggerTime = trigger,
            generation = 1,
            instanceToken = "tok_crash_conv",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = trigger + 55L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord)
        DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, trigger + 100L)

        // Crash at DE_ACK -> WITNESS_ACK -> CE_RELEASE -> LEGACY_RETIREMENT
        DirectBootDeliveryLedger.commitSeam = { _, _ -> false }
        val r1 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertEquals("DE_ACK", (r1 as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement).failures.single().stage)

        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootFiredWitnessLedger.commitSeam = { false }
        val r2 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertEquals("WITNESS_ACK", (r2 as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement).failures.single().stage)

        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootCeTakeoverStore.commitSeam = { _, _ -> false }
        val r3 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertEquals("CE_RELEASE", (r3 as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement).failures.single().stage)

        // Clean convergence
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        val r4 = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertTrue("Final retry MUST converge cleanly, got $r4", r4 is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertEquals(1, database.directBootOccurrenceReceiptDao().findByAlarmId(500L).size)
        assertTrue(DirectBootLegacyClaimStore.inspectStorageState(deContext) is LegacyClaimStorageState.ValidEmpty)
    }

    @Test
    fun s5e_e2e_restoreEpochConflict_andNonceDoubleConsumption() {
        val trigger = baseTime + 56_000L
        val key = OutputOccurrenceKey(500L, trigger, 0)
        val handover = (gate.reserveForServiceStart(ceContext, key, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce

        val first = gate.claimOutput(ceContext, key, handover, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
        assertTrue(first is OutputAuthorizationResult.Authorized)
        val second = gate.claimOutput(ceContext, key, handover, OutputSinkType.FULLSCREEN_FALLBACK_1001, roomReceiptChecker = { _, _ -> false })
        assertTrue("Double consumption of handover nonce MUST be rejected", second !is OutputAuthorizationResult.Authorized)

        val activeNonce = (first as OutputAuthorizationResult.Authorized).activeNonce
        gate.observePhysicalOutput(ceContext, key, activeNonce, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
        gate.completeTerminal(ceContext, key, activeNonce, LegacyTerminalOutcome.CONSUMED_DISMISSED)

        // Insert restoreEpoch = 0L receipt -> retire MUST reject
        database.directBootOccurrenceReceiptDao().insert(
            DirectBootOccurrenceReceipt(
                occurrenceKey = "occ_epoch0",
                sourceRecordId = "rec-epoch0",
                recordType = "DELIVERED",
                alarmId = 500L,
                triggerTime = trigger,
                generation = 1,
                instanceToken = "tok-ep0",
                restoreEpoch = 0L,
                sourcePayloadHash = DirectBootEventConsumerEngine.calculateCanonicalPayloadHashV2(
                    "rec-epoch0", "DELIVERED", 500L, trigger, trigger, 1, "tok-ep0", 0L, ""
                ),
                consumerOutcome = "CONSUMED_DELIVERED",
                completedAt = trigger
            )
        )
        assertTrue(gate.retireClaimWithProof(ceContext, database, key) is LegacyClaimRetirementResult.Rejected)
    }

    @Test
    fun s5e_e2e_capacityExhaustion32Claims_andConcurrentIngress() {
        for (i in 1L..32L) {
            assertTrue(
                gate.reserveForServiceStart(
                    ceContext,
                    OutputOccurrenceKey(600L + i, baseTime + i, 0),
                    LegacyClaimSource.ALARM_RECEIVER
                ) is OutputAuthorizationResult.Authorized
            )
        }
        val res33 = gate.reserveForServiceStart(
            ceContext,
            OutputOccurrenceKey(699L, baseTime + 99L, 0),
            LegacyClaimSource.ALARM_RECEIVER
        )
        assertTrue("33rd claim MUST fail closed with CapacityExhaustedFailClosed", res33 is OutputAuthorizationResult.CapacityExhaustedFailClosed)

        // Concurrent ingress on a fresh store: only 1 winner
        deContext.getSharedPreferences(DirectBootLegacyClaimStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        val concurrentKey = OutputOccurrenceKey(700L, baseTime + 700L, 0)
        val exec = Executors.newFixedThreadPool(4)
        val barrier = CyclicBarrier(4)
        val results = Collections.synchronizedList(mutableListOf<OutputAuthorizationResult>())
        try {
            val futures = (1..4).map { idx ->
                exec.submit {
                    barrier.await(5, TimeUnit.SECONDS)
                    results.add(
                        gate.reserveForServiceStart(
                            ceContext,
                            concurrentKey,
                            if (idx % 2 == 0) LegacyClaimSource.ALARM_RECEIVER else LegacyClaimSource.FIRE_WATCHDOG
                        )
                    )
                }
            }
            futures.forEach { it.get(5, TimeUnit.SECONDS) }
        } finally {
            exec.shutdownNow()
        }
        assertEquals(1, results.count { it is OutputAuthorizationResult.Authorized })
        assertEquals(3, results.count { it is OutputAuthorizationResult.DuplicateRejected })
    }

    @Test
    fun s5e_e2e_processDeathBootChange_andOutputPreemption() {
        // Process death & boot change -> UNKNOWN blocked
        val key1 = OutputOccurrenceKey(500L, baseTime + 57_000L, 0)
        val nonce1 = (gate.reserveForServiceStart(ceContext, key1, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        gate.bootCount = 11L // boot count changed
        val afterBoot = gate.claimOutput(ceContext, key1, nonce1, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })
        assertTrue("BootCount change MUST block output and mark UNKNOWN", afterBoot is OutputAuthorizationResult.UnknownStateBlocked)

        // Output preemption with stopPreemptedOwner
        val key2 = OutputOccurrenceKey(501L, baseTime + 58_000L, 0)
        val n2 = (gate.reserveForServiceStart(ceContext, key2, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        gate.claimOutput(ceContext, key2, n2, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false })

        val key3 = OutputOccurrenceKey(502L, baseTime + 59_000L, 0)
        val n3 = (gate.reserveForServiceStart(ceContext, key3, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val unconfirmed = gate.claimOutput(
            ceContext, key3, n3, OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false },
            stopPreemptedOwner = { false }
        )
        assertTrue("Unconfirmed preemption stop MUST reject new owner", unconfirmed !is OutputAuthorizationResult.Authorized)
        assertEquals(key2, gate.arbiter.activeHandle?.key)

        val key4 = OutputOccurrenceKey(503L, baseTime + 59_500L, 0)
        val n4 = (gate.reserveForServiceStart(ceContext, key4, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val confirmed = gate.claimOutput(
            ceContext, key4, n4, OutputSinkType.ALARM_SERVICE_1001,
            roomReceiptChecker = { _, _ -> false },
            stopPreemptedOwner = { handle -> gate.arbiter.requestStop(handle) }
        )
        assertTrue(confirmed is OutputAuthorizationResult.Authorized)
        assertEquals(key4, gate.arbiter.activeHandle?.key)
    }

    @Test
    fun s5e_e2e_storageCorruption_failClosedPreservesRawBytes() {
        val rawCorrupt = "{corrupt-legacy-claim!!"
        val prefs = DirectBootLegacyClaimStore.prefs(deContext)
        prefs.edit().putString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, rawCorrupt).commit()

        val key = OutputOccurrenceKey(500L, baseTime + 60_000L, 0)
        val res = gate.reserveForServiceStart(ceContext, key, LegacyClaimSource.ALARM_RECEIVER)
        assertTrue(res is OutputAuthorizationResult.StorageUnverifiableFailClosed)
        assertEquals(rawCorrupt, prefs.getString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, null))
    }

    // =========================================================================
    // SECTION 6: CRASH / ROLLBACK VERIFICATION (13 BOUNDARIES)
    // =========================================================================

    @Test
    fun s5e_crashRollback_all13PersistenceBoundariesFailClosed_zeroFallbackDuplicateFire() = runBlocking {
        // 1. Room Receipt Commit failure
        val trig1 = baseTime + 101_000L
        val del1 = DirectBootDeliveryRecord.createNormal(500L, trig1, trig1 + 10L, 1, "tok_b1", restoreEpoch = 2L)
        val wit1 = DirectBootFiredWitnessRecord.createNormal(
            500L, trig1, 1, "tok_b1", 2L,
            DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            trig1 + 15L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, del1)
        DirectBootFiredWitnessLedger.appendWitness(deContext, wit1)
        DirectBootCeTakeoverStore.recordDelivered(ceContext, del1, trig1 + 20L)

        val brokenDao = mockk<com.sysadmindoc.alarmclock.data.local.DirectBootOccurrenceReceiptDao>(relaxed = true)
        val inMemBrokenDb = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        val brokenDb = spyk(inMemBrokenDb) { every { directBootOccurrenceReceiptDao() } returns brokenDao }
        coEvery { brokenDao.findByOccurrenceKey(any()) } returns null
        coEvery { brokenDao.insert(any()) } throws RuntimeException("Room commit failure")
        // Verify processSingleRecord propagates / does not emit CE release
        val ceRecord1 = (DirectBootCeTakeoverStore.inspectStorageState(ceContext) as CeTakeoverStorageState.ValidWithRecords).records.single()
        var roomThrew = false
        try {
            DirectBootEventConsumerEngine.processSingleRecord(ceContext, brokenDb, ceRecord1)
        } catch (_: RuntimeException) {
            roomThrew = true
        } finally {
            inMemBrokenDb.close()
        }
        assertTrue("1. Room receipt commit failure must not succeed silently", roomThrew)
        assertTrue("1. CE slot MUST remain intact", DirectBootCeTakeoverStore.inspectStorageState(ceContext) is CeTakeoverStorageState.ValidWithRecords)
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootDeliveryLedger.resetSeamsForTesting()
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

        // 2. Receipt Readback mismatch -> ReadbackFailed, CE/DE untouched
        val trig2 = baseTime + 102_000L
        val del2 = DirectBootDeliveryRecord.createNormal(500L, trig2, trig2 + 10L, 1, "tok_b2", restoreEpoch = 2L)
        val wit2 = DirectBootFiredWitnessRecord.createNormal(
            500L, trig2, 1, "tok_b2", 2L,
            DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            trig2 + 15L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, del2)
        DirectBootFiredWitnessLedger.appendWitness(deContext, wit2)
        DirectBootCeTakeoverStore.recordDelivered(ceContext, del2, trig2 + 20L)
        val mismatchDao = mockk<com.sysadmindoc.alarmclock.data.local.DirectBootOccurrenceReceiptDao>(relaxed = true)
        val mismatchDb = Room.inMemoryDatabaseBuilder(ceContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .build()
        val spyMismatchDb = spyk(mismatchDb) { every { directBootOccurrenceReceiptDao() } returns mismatchDao }
        coEvery { mismatchDao.findByOccurrenceKey(any()) } returns null
        val ceRec2 = (DirectBootCeTakeoverStore.inspectStorageState(ceContext) as CeTakeoverStorageState.ValidWithRecords).records.single()
        val rbOutcome = try {
            DirectBootEventConsumerEngine.processSingleRecord(ceContext, spyMismatchDb, ceRec2)
        } finally {
            mismatchDb.close()
        }
        assertTrue("2. Receipt readback mismatch MUST return ReadbackFailed", rbOutcome is DirectBootEventConsumerEngine.RecordConsumptionOutcome.ReadbackFailed)

        // 3, 4, 5: DE ACK failure, Witness ACK failure, CE Release failure already preserve downstream & Legacy
        // Verify Legacy claim survives when DE_ACK fails:
        val key3 = OutputOccurrenceKey(500L, trig2, 0)
        seedTerminalLegacyClaim(key3)
        DirectBootDeliveryLedger.commitSeam = { _, _ -> false }
        val rDeFail = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertEquals("DE_ACK", (rDeFail as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement).failures.single().stage)
        assertTrue("3. Legacy claim preserved on DE_ACK fail", DirectBootLegacyClaimStore.inspectStorageState(deContext) is LegacyClaimStorageState.ValidWithRecords)
        DirectBootDeliveryLedger.resetSeamsForTesting()

        // Witness ACK fail:
        DirectBootFiredWitnessLedger.commitSeam = { false }
        val rWitFail = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertEquals("WITNESS_ACK", (rWitFail as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement).failures.single().stage)
        assertTrue("4. Legacy claim preserved on WITNESS_ACK fail", DirectBootLegacyClaimStore.inspectStorageState(deContext) is LegacyClaimStorageState.ValidWithRecords)
        DirectBootFiredWitnessLedger.resetSeamsForTesting()

        // CE Release fail:
        DirectBootCeTakeoverStore.commitSeam = { _, _ -> false }
        val rCeFail = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database)
        assertEquals("CE_RELEASE", (rCeFail as DirectBootEventConsumerEngine.ConsumerExecutionResult.IncompleteRetirement).failures.single().stage)
        assertTrue("5. Legacy claim preserved on CE_RELEASE fail", DirectBootLegacyClaimStore.inspectStorageState(deContext) is LegacyClaimStorageState.ValidWithRecords)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        // Finish retiring key3
        assertTrue(DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, ceContext, database) is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertTrue(DirectBootLegacyClaimStore.inspectStorageState(deContext) is LegacyClaimStorageState.ValidEmpty)

        // 6. Retirement Proof expired (>30s) OR future-dated
        val trig6 = baseTime + 106_000L
        val key6 = OutputOccurrenceKey(500L, trig6, 0)
        seedTerminalLegacyClaim(key6)
        val expiredEv = VerifiedRetirementEvidence(
            key = key6,
            receiptId = "rec-6",
            occurrenceKey = "occ-6",
            payloadHash = "h6",
            restoreEpoch = 2L,
            consumerOutcome = "CONSUMED_DELIVERED",
            verifiedAt = System.currentTimeMillis() - 60_000L
        )
        assertTrue("6. Expired evidence MUST be rejected", DirectBootLegacyClaimStore.retireWithEvidence(deContext, expiredEv) is LegacyClaimRetirementResult.Rejected)
        val futureEv = expiredEv.copy(verifiedAt = System.currentTimeMillis() + 60_000L)
        assertTrue("6b. Future-dated evidence MUST be rejected", DirectBootLegacyClaimStore.retireWithEvidence(deContext, futureEv) is LegacyClaimRetirementResult.Rejected)

        // 7. V2 Queue/Owner reappearance AND corrupt V2 store check under lock in retireWithEvidence
        val freshEv = expiredEv.copy(verifiedAt = System.currentTimeMillis())
        DirectBootAlarmCache.writeQueue(
            deContext,
            listOf(
                DirectBootQueueItem(
                    kind = DirectBootQueueItem.KIND_NORMAL,
                    alarmId = 500L,
                    triggerTime = trig6,
                    generation = 1,
                    instanceToken = "tok_reappear",
                    restoreEpoch = 2L
                )
            )
        )
        assertTrue("7a. V2 Queue reappearance MUST reject retirement", DirectBootLegacyClaimStore.retireWithEvidence(deContext, freshEv) is LegacyClaimRetirementResult.Rejected)

        // Corrupt V2 queue payload MUST also fail-closed in retireWithEvidence (not treated as absent!)
        DirectBootLegacyClaimStore.prefs(deContext).edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{corrupt-v2-queue").commit()
        assertTrue(
            "7b. Corrupt V2 queue MUST fail-closed in retireWithEvidence",
            DirectBootLegacyClaimStore.retireWithEvidence(deContext, freshEv) is LegacyClaimRetirementResult.Rejected
        )
        DirectBootLegacyClaimStore.prefs(deContext).edit().remove(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD).commit()

        // 8. Claim Commit failure -> AlarmReceiver MUST NOT fall back to starting AlarmService
        val trig8 = baseTime + 108_000L
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate
        DirectBootLegacyClaimStore.commitSeam = { _, _ -> false }
        try {
            AlarmReceiver().onReceive(ceContext, Intent().apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 500L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, trig8)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-claim-commit-fail")
            })
        } finally {
            DirectBootLegacyClaimStore.resetSeamsForTesting()
        }
        assertNull("8. Claim commit failure MUST NOT start AlarmService (no fallback duplicate)", shadowOf(ceContext as Application).nextStartedService)

        // 9. WakeConfirm Enqueue failure -> enqueueConfirmed remains false, pending retry preserved
        val trig9 = baseTime + 109_000L
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = deContext,
            alarmId = 500L,
            scheduledAt = trig9,
            fireId = "fire-wm-fail",
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = trig9 + 1000L,
            delayMinutes = 10,
            scheduledDueAtMillis = trig9 + 601_000L
        )
        val failingWm = mockk<WorkManager>(relaxed = true)
        val failedOp = mockk<androidx.work.Operation>(relaxed = true)
        val failedFuture = androidx.work.impl.utils.futures.SettableFuture.create<androidx.work.Operation.State.SUCCESS>().apply {
            setException(RuntimeException("WM failure"))
        }
        every { failedOp.result } returns failedFuture
        every { failingWm.enqueueUniqueWork(any(), any(), any<androidx.work.OneTimeWorkRequest>()) } returns failedOp
        mockkStatic(WorkManager::class)
        val enqRes = try {
            every { WorkManager.getInstance(any()) } returns failingWm
            val stepToken9 = WakeConfirmOccurrenceStore.findRecord(deContext, 500L, trig9)!!.stepToken
            val plan9 = com.sysadmindoc.alarmclock.service.AlarmPostDismissController.wakeConfirmationPlan(
                alarm = primaryAlarm,
                fireId = "fire-wm-fail",
                scheduledAt = trig9,
                refireCount = 0,
                stepToken = stepToken9
            )
            WakeConfirmOccurrenceStore.enqueueAndVerifyWakeConfirmWork(
                context = ceContext,
                alarmId = 500L,
                scheduledAt = trig9,
                plan = plan9,
                initialDelayMs = 600_000L,
                policy = androidx.work.ExistingWorkPolicy.REPLACE,
                reuseExistingIfPresent = false
            )
        } finally {
            unmockkStatic(WorkManager::class)
        }
        assertTrue("9. WakeConfirm enqueue failure MUST return Failed, got $enqRes", enqRes is WakeConfirmOccurrenceStore.EnqueueVerificationResult.Failed)
        assertFalse("9. enqueueConfirmed MUST stay false", WakeConfirmOccurrenceStore.findRecord(deContext, 500L, trig9)!!.enqueueConfirmed)
        assertEquals(1, WakeConfirmOccurrenceStore.getPendingRetryableEnqueues(deContext).count { it.scheduledAt == trig9 })

        // 10 & 13: Process Death + BootCount change -> UNKNOWN + blocks fallback
        val trig10 = baseTime + 110_000L
        val key10 = OutputOccurrenceKey(500L, trig10, 0)
        val n10 = (gate.reserveForServiceStart(ceContext, key10, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        gate.processSessionToken = "proc-after-death"
        assertTrue("10. Process death MUST block claimOutput", gate.claimOutput(ceContext, key10, n10, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false }) is OutputAuthorizationResult.UnknownStateBlocked)

        // 11. Lock Inversion guard in abortClaim (Arbiter NEVER called while holding DirectBootStorageLock.lock)
        val trig11 = baseTime + 111_000L
        val key11 = OutputOccurrenceKey(500L, trig11, 0)
        val h11 = (gate.reserveForServiceStart(ceContext, key11, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val a11 = (gate.claimOutput(ceContext, key11, h11, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false }) as OutputAuthorizationResult.Authorized).activeNonce
        assertFalse("11. DirectBootStorageLock must not be held outside lock", Thread.holdsLock(DirectBootStorageLock.lock))
        assertTrue("11. abortClaim succeeds with lock separation check", gate.abortClaim(ceContext, key11, a11))

        // 12. Stale Nonce -> rejected without disturbing current owner
        val trig12 = baseTime + 112_000L
        val key12 = OutputOccurrenceKey(500L, trig12, 0)
        val h12 = (gate.reserveForServiceStart(ceContext, key12, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        val a12 = (gate.claimOutput(ceContext, key12, h12, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false }) as OutputAuthorizationResult.Authorized).activeNonce
        assertFalse("12. Stale handover nonce CANNOT abort active owner", gate.abortClaim(ceContext, key12, h12))
        assertEquals(a12, gate.arbiter.activeHandle?.activeNonce)
        assertTrue(gate.abortClaim(ceContext, key12, a12))

        // 13. BootCount change -> UNKNOWN + blocks duplicate fire
        val trig13 = baseTime + 113_000L
        val key13 = OutputOccurrenceKey(500L, trig13, 0)
        val n13 = (gate.reserveForServiceStart(ceContext, key13, LegacyClaimSource.ALARM_RECEIVER) as OutputAuthorizationResult.Authorized).activeNonce
        gate.bootCount = 99L
        assertTrue("13. BootCount change MUST block claimOutput", gate.claimOutput(ceContext, key13, n13, OutputSinkType.ALARM_SERVICE_1001, roomReceiptChecker = { _, _ -> false }) is OutputAuthorizationResult.UnknownStateBlocked)
    }
}
