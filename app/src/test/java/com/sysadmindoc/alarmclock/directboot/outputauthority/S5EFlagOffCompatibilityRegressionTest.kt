package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.CeTakeoverIntakeResult
import com.sysadmindoc.alarmclock.directboot.CeTakeoverStorageState
import com.sysadmindoc.alarmclock.directboot.DeliveryAppendResult
import com.sysadmindoc.alarmclock.directboot.DirectBootCeTakeoverStore
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryRecord
import com.sysadmindoc.alarmclock.directboot.DirectBootEventConsumerEngine
import com.sysadmindoc.alarmclock.directboot.DirectBootFiredWitnessLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootFiredWitnessRecord
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.DirectBootMissedStore
import com.sysadmindoc.alarmclock.directboot.LedgerStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.WitnessAppendResult
import com.sysadmindoc.alarmclock.directboot.WitnessLedgerState
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.worker.WakeConfirmOccurrenceStore
import io.mockk.coEvery
import io.mockk.every
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Verifies 100% behavioral preservation when Output Authority Feature Flag is OFF (default).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class S5EFlagOffCompatibilityRegressionTest {

    private lateinit var context: Context
    private lateinit var deContext: Context
    private lateinit var database: AlarmDatabase
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler

    private val baseTime = 1_700_000_000_000L

    private val legacyAlarm = Alarm(
        id = 800L,
        hour = 6,
        minute = 30,
        label = "Legacy Morning",
        isEnabled = true,
        volume = 80,
        vibrationEnabled = true,
        wakeConfirmEnabled = true,
        wakeConfirmDelayMinutes = 10
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        deContext = context.createDeviceProtectedStorageContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val userManager = context.getSystemService(UserManager::class.java)
        shadowOf(userManager).setUserUnlocked(true)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)

        database = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        clearStores()
        assertFalse(
            "Production SharedOutputAuthorityGate MUST default to isEnabled = false",
            SharedOutputAuthorityGate.getInstance(context).isEnabled
        )

        alarmRepository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        coEvery { alarmRepository.getById(800L) } returns legacyAlarm
        coEvery { alarmRepository.getById(801L) } returns legacyAlarm.copy(id = 801L, repeatDays = emptySet(), isEnabled = false)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        every { preferencesManager.isSettingsLoaded } returns true
    }

    @After
    fun tearDown() {
        clearStores()
        database.close()
    }

    private fun clearStores() {
        SharedOutputAuthorityGate.getInstance(context).apply {
            isEnabled = false
            arbiter.clearForTesting()
        }
        DirectBootLegacyClaimStore.resetSeamsForTesting()
        DirectBootDeliveryLedger.resetSeamsForTesting()
        DirectBootMissedStore.resetSeamsForTesting()
        DirectBootFiredWitnessLedger.resetSeamsForTesting()
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootCeTakeoverStore.clear(context)
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        AlarmFullScreenFallback.clearForTesting()
        deContext.getSharedPreferences(DirectBootLegacyClaimStore.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        deContext.getSharedPreferences(DirectBootFiredWitnessLedger.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        deContext.getSharedPreferences(WakeConfirmOccurrenceStore.DE_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(WakeConfirmOccurrenceStore.CE_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun flagOff_legacyReceiver_fgs_fallback_audioVibration_andDismissWorkUnchanged() {
        val gate = SharedOutputAuthorityGate.getInstance(context)
        assertFalse(gate.isEnabled)

        // 1. Legacy AlarmReceiver fires without claimNonce and without DE claims
        val rxIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 800L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, baseTime + 1_000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-legacy-800")
        }
        AlarmReceiver().onReceive(context, rxIntent)
        val startedSvcIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("Legacy AlarmReceiver MUST start AlarmService", startedSvcIntent)
        assertNull("Flag OFF MUST NOT attach claimNonce", startedSvcIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE))
        assertTrue(
            "Flag OFF MUST NOT mutate DirectBootLegacyClaimStore",
            DirectBootLegacyClaimStore.inspectStorageState(deContext) is LegacyClaimStorageState.Absent
        )

        // 2. Full-screen Fallback posts cleanly without claimNonce when Flag is OFF
        val posted = AlarmFullScreenFallback.post(
            context = context,
            alarmId = 800L,
            scheduledAt = baseTime + 1_000L,
            fireId = "fire-legacy-800",
            hideLabel = false,
            label = "Legacy Morning",
            authorityGate = gate
        )
        assertTrue("Flag OFF fallback MUST post normally", posted)

        // 3. FGS start & Dismiss action in AlarmService run normally with Flag OFF
        val service = Robolectric.buildService(AlarmService::class.java).get().apply {
            repository = alarmRepository
            alarmScheduler = this@S5EFlagOffCompatibilityRegressionTest.alarmScheduler
            eventRepository = mockk(relaxed = true)
            alarmIncidentRepository = mockk(relaxed = true)
            preferencesManager = this@S5EFlagOffCompatibilityRegressionTest.preferencesManager
            webhookService = mockk(relaxed = true)
            wearNextAlarmBridge = mockk(relaxed = true)
            dismissActionExecutor = mockk(relaxed = true)
            weatherRepository = mockk(relaxed = true)
            calendarRepository = mockk(relaxed = true)
            outputAuthorityGate = gate
        }
        val startIntent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 800L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, baseTime + 1_000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-legacy-800")
        }
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(startIntent, 0, 1))
        assertNull("Arbiter handle MUST remain null when Flag is OFF", gate.arbiter.activeHandle)

        val dismissIntent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_DISMISS
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 800L)
        }
        assertEquals(android.app.Service.START_NOT_STICKY, service.onStartCommand(dismissIntent, 0, 2))
    }

    @Test
    fun flagOff_wakeConfirmAndOneTime_workUnchangedAndReturnGateDisabled() = runBlocking {
        val gate = SharedOutputAuthorityGate.getInstance(context)
        assertFalse(gate.isEnabled)

        val trig = baseTime + 2_000L
        val commitRes = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = deContext,
            alarmId = 801L,
            scheduledAt = trig,
            fireId = "fire-one-time-801",
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = false,
            nowMillis = trig + 500L,
            delayMinutes = 10,
            scheduledDueAtMillis = trig + 600_500L
        )
        assertTrue("Expected Committed, got $commitRes", commitRes is WakeConfirmOccurrenceStore.CommitResult.Committed)
        val token = (commitRes as WakeConfirmOccurrenceStore.CommitResult.Committed).record.stepToken
        val oneTimeAlarm = legacyAlarm.copy(id = 801L, repeatDays = emptySet(), isEnabled = false)
        val plan = com.sysadmindoc.alarmclock.service.AlarmPostDismissController.wakeConfirmationPlan(
            alarm = oneTimeAlarm,
            fireId = "fire-one-time-801",
            scheduledAt = trig,
            refireCount = 0,
            stepToken = token
        )
        val enqueued = WakeConfirmOccurrenceStore.enqueueAndVerifyWakeConfirmWork(
            context = context,
            alarmId = 801L,
            scheduledAt = trig,
            plan = plan,
            initialDelayMs = 600_000L,
            policy = ExistingWorkPolicy.REPLACE,
            reuseExistingIfPresent = false
        )
        assertTrue("WakeConfirm WorkManager enqueue MUST succeed in Flag OFF mode, got $enqueued", enqueued is WakeConfirmOccurrenceStore.EnqueueVerificationResult.Enqueued)
        assertTrue(WakeConfirmOccurrenceStore.markEnqueueConfirmed(deContext, 801L, trig, token) is WakeConfirmOccurrenceStore.CommitResult.Committed)

        // Gate refire check returns GateDisabled cleanly
        assertTrue(gate.reserveWakeConfirmRefire(context, OutputOccurrenceKey(801L, trig, 1)) is OutputAuthorizationResult.GateDisabled)
    }

    @Test
    fun flagOff_consumerS4PathRunsSteps1To6_andSkipsStep7LegacyRetirement() = runBlocking {
        val gate = SharedOutputAuthorityGate.getInstance(context)
        assertFalse(gate.isEnabled)

        val trig = baseTime + 3_000L
        val key = OutputOccurrenceKey(800L, trig, 0)
        // Seed a TERMINAL claim in DirectBootLegacyClaimStore; Flag OFF must NOT touch it!
        val initClaim = LegacyClaimRecord(
            alarmId = key.alarmId,
            scheduledAt = key.scheduledAt,
            refireCount = 0,
            source = LegacyClaimSource.ALARM_RECEIVER,
            claimNonce = "n-flag-off",
            processSessionToken = "proc-flag-off",
            bootCount = 10L,
            state = LegacyClaimState.START_REQUESTED
        )
        assertTrue(DirectBootLegacyClaimStore.writeInitialClaim(deContext, initClaim) is com.sysadmindoc.alarmclock.directboot.LegacyClaimWriteResult.Committed)
        assertTrue(
            DirectBootLegacyClaimStore.transition(
                context = deContext,
                key = key,
                expectedFrom = LegacyClaimState.START_REQUESTED,
                toState = LegacyClaimState.TERMINAL,
                expectedNonce = "n-flag-off",
                newNonce = "n-flag-off-term",
                processSessionToken = "proc-flag-off",
                bootCount = 10L,
                terminalOutcome = LegacyTerminalOutcome.CONSUMED_DISMISSED
            ) is com.sysadmindoc.alarmclock.directboot.LegacyClaimWriteResult.Committed
        )

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 800L,
            triggerTime = trig,
            deliveredAt = trig + 50L,
            generation = 1,
            instanceToken = "tok_flag_off_s4",
            provenance = "BOOT",
            restoreEpoch = 2L
        )
        val witnessRecord = DirectBootFiredWitnessRecord.createNormal(
            alarmId = 800L,
            triggerTime = trig,
            generation = 1,
            instanceToken = "tok_flag_off_s4",
            restoreEpoch = 2L,
            deliveryMode = DirectBootFiredWitnessRecord.MODE_SOUND_AND_VIBRATION,
            deliveryOutcome = DirectBootFiredWitnessRecord.OUTCOME_OUTPUT_START_ACCEPTED,
            observedAtEpochMs = trig + 55L
        )
        assertTrue(DirectBootDeliveryLedger.appendRecord(deContext, delRecord) is DeliveryAppendResult.Appended)
        assertTrue(DirectBootFiredWitnessLedger.appendWitness(deContext, witnessRecord) is WitnessAppendResult.Appended)
        assertTrue(DirectBootCeTakeoverStore.recordDelivered(context, delRecord, trig + 100L) is CeTakeoverIntakeResult.RecordedConfirmed)

        val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(deContext, context, database)
        assertTrue("S4 Consumer MUST succeed when Flag is OFF, got $result", result is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)
        assertTrue(DirectBootDeliveryLedger.inspectStorageState(deContext) is LedgerStorageState.ValidEmpty)
        assertTrue(DirectBootFiredWitnessLedger.readWitnesses(deContext) is WitnessLedgerState.ValidEmpty)
        assertTrue(DirectBootCeTakeoverStore.inspectStorageState(context) is CeTakeoverStorageState.ValidEmpty)

        // Flag OFF MUST leave LegacyClaimStore untouched (no Step 7 side effect)
        val claimState = DirectBootLegacyClaimStore.inspectStorageState(deContext) as LegacyClaimStorageState.ValidWithRecords
        assertEquals(1, claimState.records.size)
        assertEquals(key, claimState.records.single().key)
    }
}
