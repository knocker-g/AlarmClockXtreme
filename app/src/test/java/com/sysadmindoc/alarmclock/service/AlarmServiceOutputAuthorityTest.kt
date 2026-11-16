package com.sysadmindoc.alarmclock.service

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.outputauthority.ActiveOutputHandle
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AlarmServiceOutputAuthorityTest {

    private lateinit var context: Context
    private lateinit var gate: SharedOutputAuthorityGate
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var preferencesManager: PreferencesManager

    private val testAlarm = Alarm(
        id = 201L,
        hour = 8,
        minute = 0,
        label = "Authority Alarm",
        isEnabled = true,
        volume = 80
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.Shadows.shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        clearStorage()

        gate = SharedOutputAuthorityGate(
            bootCount = 10L,
            processSessionToken = "proc-test-session"
        )

        alarmScheduler = mockk(relaxed = true)
        alarmRepository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        every { preferencesManager.isSettingsLoaded } returns true
        coEvery { alarmRepository.getById(201L) } returns testAlarm
        coEvery { alarmRepository.getById(202L) } returns testAlarm.copy(id = 202L, label = "Preempting Alarm")
    }

    @After
    fun tearDown() {
        clearStorage()
        gate.arbiter.clearForTesting()
    }

    private fun clearStorage() {
        DirectBootLegacyClaimStore.prefs(context).edit().clear().commit()
    }

    private fun createInjectedService(): AlarmService {
        val controller = Robolectric.buildService(AlarmService::class.java)
        val service = controller.get()
        service.repository = alarmRepository
        service.alarmScheduler = alarmScheduler
        service.eventRepository = mockk(relaxed = true)
        service.alarmIncidentRepository = mockk(relaxed = true)
        service.preferencesManager = preferencesManager
        service.webhookService = mockk(relaxed = true)
        service.wearNextAlarmBridge = mockk(relaxed = true)
        service.dismissActionExecutor = mockk(relaxed = true)
        service.weatherRepository = mockk(relaxed = true)
        service.calendarRepository = mockk(relaxed = true)
        service.outputAuthorityGate = gate
        return service
    }

    @Test
    fun testAlarmService_flagOff_maintainsLegacyBehavior() {
        gate.isEnabled = false
        val service = createInjectedService()

        val intent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 201L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-201")
        }

        val startResult = service.onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, startResult)
        assertNull(gate.arbiter.activeHandle)
    }

    @Test
    fun testAlarmService_flagOn_nullClaimNonce_failsClosed() {
        gate.isEnabled = true
        val service = createInjectedService()

        val intent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 201L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-201")
        }

        service.onStartCommand(intent, 0, 1)
        assertFalse("Audio MUST NOT start without claimNonce when gate is enabled", service.testAudioStarted.get())
        assertFalse("Vibration MUST NOT start without claimNonce when gate is enabled", service.testVibrationStarted.get())
    }

    @Test
    fun testAlarmService_flagOn_nullRoomChecker_failsClosed() {
        gate.isEnabled = true
        val service = createInjectedService()
        service.roomReceiptCheckerSeam = null

        val key = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce = "nonce-srv-1"
        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val intent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 201L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-201")
            putExtra(AlarmService.EXTRA_CLAIM_NONCE, nonce)
        }

        service.onStartCommand(intent, 0, 1)
        assertFalse("Audio MUST NOT start when Room checker is null in CE mode", service.testAudioStarted.get())
        assertFalse("Vibration MUST NOT start when Room checker is null in CE mode", service.testVibrationStarted.get())
    }

    @Test
    fun testAlarmService_flagOn_roomCheckerException_failsClosed() {
        gate.isEnabled = true
        val service = createInjectedService()
        service.roomReceiptCheckerSeam = { _, _ -> throw IllegalStateException("Simulated Room DB error") }

        val key = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce = "nonce-srv-2"
        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val intent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 201L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-201")
            putExtra(AlarmService.EXTRA_CLAIM_NONCE, nonce)
        }

        service.onStartCommand(intent, 0, 1)
        assertFalse("Audio MUST NOT start when Room checker throws exception", service.testAudioStarted.get())
        assertFalse("Vibration MUST NOT start when Room checker throws exception", service.testVibrationStarted.get())
    }

    @Test
    fun testAlarmService_flagOn_roomReceiptExists_failsClosed() {
        gate.isEnabled = true
        val service = createInjectedService()
        service.roomReceiptCheckerSeam = { _, _ -> true } // Receipt already exists in Room

        val key = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce = "nonce-srv-3"
        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val intent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 201L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-201")
            putExtra(AlarmService.EXTRA_CLAIM_NONCE, nonce)
        }

        service.onStartCommand(intent, 0, 1)
        assertFalse("Audio MUST NOT start when Room receipt already exists", service.testAudioStarted.get())
        assertFalse("Vibration MUST NOT start when Room receipt already exists", service.testVibrationStarted.get())
    }

    @Test
    fun testAlarmService_flagOn_staleNonce_failsClosed() {
        gate.isEnabled = true
        val service = createInjectedService()
        service.roomReceiptCheckerSeam = { _, _ -> false }

        val key = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce = "nonce-srv-4"
        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val intent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 201L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-201")
            putExtra(AlarmService.EXTRA_CLAIM_NONCE, "nonce-stale-mismatch")
        }

        service.onStartCommand(intent, 0, 1)
        assertFalse("Audio MUST NOT start with stale claimNonce", service.testAudioStarted.get())
        assertFalse("Vibration MUST NOT start with stale claimNonce", service.testVibrationStarted.get())
    }

    @Test
    fun testAlarmService_flagOn_preemption_unconfirmedStop_rejectsNewOwner() {
        gate.isEnabled = true
        val service = createInjectedService()

        val key1 = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce1 = "nonce-owner-1"
        gate.arbiter.acquirePending(key1, OutputSinkType.ALARM_SERVICE_1001, nonce1)

        val key2 = OutputOccurrenceKey(202L, 2000L, 0)
        val nonce2 = "nonce-owner-2"

        val (acquired, preempted) = gate.arbiter.acquirePending(
            key = key2,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            activeNonce = nonce2,
            stopPreemptedOwner = { false } // Stop failed!
        )

        assertFalse("New owner MUST NOT be authorized when preemption stop is not confirmed", acquired)
        assertEquals("Preceding owner MUST remain active when stop fails", nonce1, gate.arbiter.activeHandle?.activeNonce)
        assertNull(preempted)
    }

    @Test
    fun testAlarmService_flagOn_preemption_confirmedStop_authorizesNewOwner() {
        gate.isEnabled = true
        val service = createInjectedService()

        val key1 = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce1 = "nonce-owner-1"
        gate.arbiter.acquirePending(key1, OutputSinkType.ALARM_SERVICE_1001, nonce1)
        service.currentActiveNonce = nonce1
        service.testAudioStarted.set(true)

        val key2 = OutputOccurrenceKey(202L, 2000L, 0)
        val nonce2 = "nonce-owner-2"

        var stopExecuted = false
        val (acquired, preempted) = gate.arbiter.acquirePending(
            key = key2,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            activeNonce = nonce2,
            stopPreemptedOwner = { handle ->
                stopExecuted = service.handlePreemptedOwner(handle)
                stopExecuted
            }
        )

        assertTrue("Preemption stop must have been executed", stopExecuted)
        assertTrue("New owner MUST be authorized after confirmed stop", acquired)
        assertEquals(key2, gate.arbiter.activeHandle?.key)
        assertEquals(nonce2, gate.arbiter.activeHandle?.activeNonce)
        assertFalse("Audio of preempted owner MUST be stopped", service.testAudioStarted.get())
    }

    @Test
    fun testAlarmService_flagOn_preemption_betweenAudioAndVibration_abortsVibration() {
        gate.isEnabled = true
        val service = createInjectedService()

        val key1 = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce1 = "nonce-owner-1"
        gate.arbiter.acquirePending(key1, OutputSinkType.ALARM_SERVICE_1001, nonce1)
        service.currentActiveNonce = nonce1

        service.testAudioStarted.set(true)

        // Between audio and vibration, another alarm preempts in arbiter!
        val key2 = OutputOccurrenceKey(202L, 2000L, 0)
        val nonce2 = "nonce-owner-2"
        gate.arbiter.acquirePending(key2, OutputSinkType.ALARM_SERVICE_1001, nonce2) { true }

        val canVibrate = (gate.arbiter.activeHandle?.activeNonce == nonce1)
        assertFalse("Vibration MUST be aborted if preemption occurred after audio started", canVibrate)
    }

    @Test
    fun testAlarmService_flagOn_staleHandleStopRequest_rejected() {
        val service = createInjectedService()
        service.currentActiveNonce = "nonce-active-current"

        val staleHandle = ActiveOutputHandle(
            key = OutputOccurrenceKey(201L, 1000L, 0),
            sink = OutputSinkType.ALARM_SERVICE_1001,
            activeNonce = "nonce-stale-different"
        )

        val stopResult = service.handlePreemptedOwner(staleHandle)
        assertFalse("Stale handle stop request MUST be rejected fail-closed", stopResult)
    }

    @Test
    fun testAlarmService_flagOn_fgsPlaceholder_postedSynchronously_audioWaitsForAuthority() {
        gate.isEnabled = true
        val service = createInjectedService()
        service.roomReceiptCheckerSeam = { _, _ -> false }

        val key = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce = "nonce-srv-5"
        DirectBootLegacyClaimStore.writeInitialClaim(
            context = context,
            record = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = LegacyClaimSource.ALARM_RECEIVER,
                claimNonce = nonce,
                processSessionToken = "proc-test-session",
                bootCount = 10L,
                state = LegacyClaimState.START_REQUESTED
            )
        )

        val intent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 201L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-201")
            putExtra(AlarmService.EXTRA_CLAIM_NONCE, nonce)
        }

        val res = service.onStartCommand(intent, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, res)
        assertEquals(201L, service.currentAlarmId)
    }

    @Test
    fun testAlarmService_flagOn_serviceStopped_staleCallbackRejected() {
        gate.isEnabled = true
        val service = createInjectedService()

        val key = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce = "nonce-srv-6"
        gate.arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, nonce)
        service.currentActiveNonce = nonce

        service.handlePreemptedOwner(
            ActiveOutputHandle(key, OutputSinkType.ALARM_SERVICE_1001, nonce)
        )

        val staleObserve = gate.arbiter.confirmObserved(key, nonce)
        assertFalse("After stop, old nonce confirmation in arbiter MUST fail", staleObserve)
    }

    @Test
    fun testAlarmService_preemption_lifecycleSequence_verified() {
        gate.isEnabled = true
        val service = createInjectedService()

        val key1 = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce1 = "nonce-owner-life-1"
        gate.arbiter.acquirePending(key1, OutputSinkType.ALARM_SERVICE_1001, nonce1)
        service.currentActiveNonce = nonce1
        service.currentAlarmId = 201L
        service.recordPlaybackState(AlarmService.PlaybackLifecycleState.START_REQUESTED)
        service.recordPlaybackState(AlarmService.PlaybackLifecycleState.START_ACCEPTED)
        service.recordPlaybackState(AlarmService.PlaybackLifecycleState.PLAYBACK_ACTIVE)

        val stopConfirmed = service.handlePreemptedOwner(
            ActiveOutputHandle(key1, OutputSinkType.ALARM_SERVICE_1001, nonce1)
        )

        assertTrue("Preemption stop must succeed", stopConfirmed)
        val history = service.playbackStateHistory
        assertTrue("Must contain STOP_REQUESTED", history.contains(AlarmService.PlaybackLifecycleState.STOP_REQUESTED))
        assertTrue("Must contain STOP_ACCEPTED", history.contains(AlarmService.PlaybackLifecycleState.STOP_ACCEPTED))
        assertTrue("Must contain STOP_CONFIRMED", history.contains(AlarmService.PlaybackLifecycleState.STOP_CONFIRMED))
        assertEquals(AlarmService.PlaybackLifecycleState.STOP_CONFIRMED, history.last())
    }

    @Test
    fun testAlarmService_preemption_identityMismatch_failsClosed() {
        gate.isEnabled = true
        val service = createInjectedService()

        val key1 = OutputOccurrenceKey(201L, 1000L, 0)
        val nonce1 = "nonce-owner-life-2"
        service.currentActiveNonce = nonce1
        service.currentAlarmId = 201L

        // Attempt stop with mismatched alarmId
        val mismatchedHandle = ActiveOutputHandle(OutputOccurrenceKey(999L, 1000L, 0), OutputSinkType.ALARM_SERVICE_1001, nonce1)
        val stopResult = service.handlePreemptedOwner(mismatchedHandle)
        assertFalse("Stop MUST fail closed on alarmId mismatch", stopResult)
        assertFalse("Must not reach STOP_CONFIRMED on mismatch", service.playbackStateHistory.contains(AlarmService.PlaybackLifecycleState.STOP_CONFIRMED))
    }
}
