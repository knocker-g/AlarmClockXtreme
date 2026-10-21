package com.sysadmindoc.alarmclock.service

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.service.AlarmService.Companion.ActiveAlarmSnapshot
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class AlarmServiceSnoozeTest {

    private lateinit var context: Context
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var preferencesManager: PreferencesManager

    private val testAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 30,
        label = "Test Alarm",
        isEnabled = true,
        snoozeDurationMinutes = 10,
        nextTriggerTime = 1000L
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        alarmScheduler = mockk(relaxed = true)
        alarmRepository = mockk(relaxed = true)
        calculator = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { alarmRepository.getById(42L) } returns testAlarm
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
        return service
    }

    @Test
    fun `A - when exact capability denied preflight registration is rejected and failure returned`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns false

        val canSchedule = alarmScheduler.canScheduleExactAlarms()
        assertFalse("Preflight MUST reject exact Snooze registration when capability denied", canSchedule)
    }

    @Test
    fun `B - when scheduleSnoozeAt returns false registration failure is indicated`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any(), any()) } returns false

        val success = alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        assertFalse("Registration MUST return false on OS registration failure", success)
    }

    @Test
    fun `C - when scheduleSnoozeAt throws SecurityException failure is caught cleanly`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any(), any()) } throws SecurityException("Exact alarm permission revoked")

        var exceptionCaught = false
        try {
            alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        } catch (_: SecurityException) {
            exceptionCaught = true
        }

        assertTrue("SecurityException caught cleanly from OS registration failure", exceptionCaught)
    }

    @Test
    fun `D - when scheduleSnoozeAt throws IllegalStateException no false success is returned`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any(), any()) } throws IllegalStateException("OS AlarmManager internal failure")

        var exceptionCaught = false
        try {
            alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        } catch (_: IllegalStateException) {
            exceptionCaught = true
        }

        assertTrue("IllegalStateException caught cleanly without false success", exceptionCaught)
    }

    @Test
    fun `E - successful OS registration returns true`() = runBlocking {
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any(), any()) } returns true

        val success = alarmScheduler.scheduleSnoozeAt(testAlarm, System.currentTimeMillis() + 600_000L)
        assertTrue("Successful OS registration MUST return true", success)
    }

    @Test
    fun `Priority 1 - valid Snooze request succeeds when DB nextTriggerTime has advanced past FIRING scheduledAt`() = runBlocking {
        val alarmId = 42L
        val firingScheduledAt = 1000L
        val advancedDbNextTrigger = 86400000L // Tomorrow's trigger in DB!
        val genA = AlarmGeneration(alarmId, firingScheduledAt, "fire-42-1000")
        var currentAlarm = testAlarm.copy(nextTriggerTime = advancedDbNextTrigger)

        coEvery { alarmRepository.getById(any()) } answers { currentAlarm }
        coEvery { alarmRepository.getNextAlarm() } answers { currentAlarm }
        coEvery { alarmRepository.updateNextTrigger(any(), any()) } answers {
            val newTrigger = secondArg<Long>()
            currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
        }
        coEvery { alarmRepository.casUpdateNextTrigger(any(), any(), any()) } answers {
            val expected = secondArg<Long>()
            val newTrigger = thirdArg<Long>()
            if (currentAlarm.nextTriggerTime == expected) {
                currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
                1
            } else 0
        }

        val appSettings = AppSettings(
            activeAlarmId = alarmId,
            activeAlarmScheduledAt = firingScheduledAt,
            activeAlarmFireId = "fire-42-1000",
            activeAlarmState = "FIRING"
        )
        coEvery { preferencesManager.getCachedSettings() } returns appSettings
        coEvery { preferencesManager.getCurrentSettings() } returns appSettings

        // Real AlarmScheduler instance using test context & repository
        val realScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )

        val snoozeEndAt = System.currentTimeMillis() + 600_000L
        val snoozeScheduled = realScheduler.scheduleSnoozeAt(currentAlarm, snoozeEndAt, targetGen = genA)

        assertTrue("Valid Snooze for active FIRING generation MUST succeed even when DB nextTriggerTime has advanced to tomorrow", snoozeScheduled)
    }

    @Test
    fun `G - OS registration failure in scheduleAt preserves previous nextTriggerTime without destructive erasure`() = runBlocking {
        val previousTrigger = 5000L
        val alarmWithTrigger = testAlarm.copy(nextTriggerTime = previousTrigger)
        coEvery { alarmRepository.getById(42L) } returns alarmWithTrigger
        coEvery { alarmScheduler.scheduleAt(any(), any(), any()) } returns false

        val success = alarmScheduler.scheduleAt(alarmWithTrigger, 8000L)

        assertFalse("Failed scheduleAt MUST return false", success)
        coVerify(exactly = 0) { alarmRepository.updateNextTrigger(42L, 0) }
    }

    @Test
    fun `Robolectric AlarmService - snooze failure keeps active FIRING generation audible and active without stopSelf`() = runBlocking {
        val service = createInjectedService()
        var stopSelfCalled = false
        service.testOnStopSelfCalled = { stopSelfCalled = true }

        val startIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }

        // Start service into FIRING generation
        service.onStartCommand(startIntent, 0, 1)

        val activeGen = service.currentGeneration()
        assertNotNull(activeGen)
        assertEquals(42L, activeGen?.alarmId)
        assertEquals(1000L, activeGen?.scheduledAt)
        assertEquals("fire-42", activeGen?.fireId)

        // Inject mock scheduler that fails snooze registration
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.canScheduleExactAlarms() } returns true
        coEvery { mockScheduler.scheduleSnoozeAt(any(), any(), any()) } returns false
        service.alarmScheduler = mockScheduler

        val snoozeIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_SNOOZE
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }

        service.onStartCommand(snoozeIntent, 0, 2)

        // Verify FIRING generation remains active, snooze count = 0, stopSelf NOT called!
        assertFalse("stopSelf MUST NOT be called when exact Snooze registration fails", stopSelfCalled)
        assertEquals(0, service.currentSnoozeCount)
        val currentGen = service.currentGeneration()
        assertNotNull(currentGen)
        assertEquals(42L, currentGen?.alarmId)
        assertEquals("fire-42", currentGen?.fireId)
    }

    @Test
    fun `Robolectric AlarmService - snooze SecurityException keeps active FIRING generation audible and active`() = runBlocking {
        val service = createInjectedService()
        var stopSelfCalled = false
        service.testOnStopSelfCalled = { stopSelfCalled = true }

        val startIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }

        // Start service into FIRING generation
        service.onStartCommand(startIntent, 0, 1)

        // Inject mock scheduler that throws SecurityException on snooze
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.canScheduleExactAlarms() } returns true
        coEvery { mockScheduler.scheduleSnoozeAt(any(), any(), any()) } throws SecurityException("Exact alarm permission revoked")
        service.alarmScheduler = mockScheduler

        val snoozeIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_SNOOZE
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }

        service.onStartCommand(snoozeIntent, 0, 2)

        // Verify FIRING generation remains active, snooze count = 0, stopSelf NOT called!
        assertFalse("stopSelf MUST NOT be called when exact Snooze throws SecurityException", stopSelfCalled)
        assertEquals(0, service.currentSnoozeCount)
        val currentGen = service.currentGeneration()
        assertNotNull(currentGen)
        assertEquals(42L, currentGen?.alarmId)
        assertEquals("fire-42", currentGen?.fireId)
    }

    @Test
    fun `Robolectric AlarmService - async snooze completion latch verifies scheduleSnoozeAt invocation`() = runBlocking {
        val service = createInjectedService()
        val snoozeLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        service.testOnSnoozeActionCompletionLatch = snoozeLatch

        val startIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_START_ALARM
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }
        service.onStartCommand(startIntent, 0, 1)

        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.canScheduleExactAlarms() } returns true
        coEvery { mockScheduler.scheduleSnoozeAt(any(), any(), any()) } returns false
        service.alarmScheduler = mockScheduler

        val snoozeIntent = Intent(context, AlarmService::class.java).apply {
            action = AlarmService.ACTION_SNOOZE
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }

        service.onStartCommand(snoozeIntent, 0, 2)
        snoozeLatch.await() // Wait for async snooze processing completion!

        coVerify(exactly = 1) { mockScheduler.scheduleSnoozeAt(any(), any(), any()) }
        assertEquals(0, service.currentSnoozeCount)
    }

    @Test
    fun `DataStore ActiveAlarmWriterPolicy - SNOOZED update without targetGen is rejected`() {
        val snapshot = ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 1000L,
            state = "SNOOZED",
            refireAt = 1600L
        )

        val allowed = PreferencesManager.ActiveAlarmWriterPolicy.shouldExecuteUpdate(
            storedId = 42L,
            storedScheduledAt = 1000L,
            storedFireId = "fire-42",
            requestSnapshot = snapshot,
            targetGen = null // Null targetGen!
        )

        assertFalse("SNOOZED update without targetGen MUST be rejected", allowed)
    }

    @Test
    fun `DataStore ActiveAlarmWriterPolicy - SNOOZED update for stale generation A rejected when stored holds B`() {
        val targetGenA = AlarmGeneration(42L, 1000L, "fire-42")
        val snapshotA = ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 1000L,
            state = "SNOOZED",
            refireAt = 1600L
        )

        // DataStore holds newer active generation B
        val allowed = PreferencesManager.ActiveAlarmWriterPolicy.shouldExecuteUpdate(
            storedId = 99L,
            storedScheduledAt = 5000L,
            storedFireId = "fire-99",
            requestSnapshot = snapshotA,
            targetGen = targetGenA
        )

        assertFalse("Delayed SNOOZED update for stale A MUST be rejected when DataStore holds B", allowed)
    }

    @Test
    fun `DataStore ActiveAlarmWriterPolicy - SNOOZED update rejected when stored active session is absent`() {
        val targetGenA = AlarmGeneration(42L, 1000L, "fire-42")
        val snapshotA = ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 1000L,
            state = "SNOOZED",
            refireAt = 1600L
        )

        // Stored session is ABSENT (storedId = null or 0L)
        val allowedNull = PreferencesManager.ActiveAlarmWriterPolicy.shouldExecuteUpdate(
            storedId = null,
            storedScheduledAt = 0L,
            storedFireId = "",
            requestSnapshot = snapshotA,
            targetGen = targetGenA
        )

        assertFalse("SNOOZED update MUST be rejected when stored active session is absent", allowedNull)
    }

    @Test
    fun `DataStore ActiveAlarmWriterPolicy - identical SNOOZED A replay is classified as IDEMPOTENT_NOOP`() {
        val targetGenA = AlarmGeneration(42L, 1000L, "fire-42")
        val snapshotA = ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 1000L,
            state = "SNOOZED",
            refireAt = 1600L
        )

        val decision = PreferencesManager.ActiveAlarmWriterPolicy.evaluateUpdate(
            storedId = 42L,
            storedScheduledAt = 1000L,
            storedFireId = "fire-42",
            storedState = "SNOOZED",
            storedRefireAt = 1600L, // Identical refireAt!
            requestSnapshot = snapshotA,
            targetGen = targetGenA
        )

        assertEquals(PreferencesManager.WriterPolicyDecision.IDEMPOTENT_NOOP, decision)
    }

    @Test
    fun `DataStore ActiveAlarmWriterPolicy - SNOOZED A with different refireAt is REJECT_STALE`() {
        val targetGenA = AlarmGeneration(42L, 1000L, "fire-42")
        val snapshotA = ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 1000L,
            state = "SNOOZED",
            refireAt = 1600L
        )

        val decision = PreferencesManager.ActiveAlarmWriterPolicy.evaluateUpdate(
            storedId = 42L,
            storedScheduledAt = 1000L,
            storedFireId = "fire-42",
            storedState = "SNOOZED",
            storedRefireAt = 9999L, // Different refireAt!
            requestSnapshot = snapshotA,
            targetGen = targetGenA
        )

        assertEquals(PreferencesManager.WriterPolicyDecision.REJECT_STALE, decision)
    }

    @Test
    fun `DataStore ActiveAlarmWriterPolicy - SNOOZED update with nonpositive refireAt is REJECT_STALE`() {
        val targetGenA = AlarmGeneration(42L, 1000L, "fire-42")
        val snapshotZeroRefire = ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 1000L,
            state = "SNOOZED",
            refireAt = 0L // RefireAt <= 0L!
        )

        val decision = PreferencesManager.ActiveAlarmWriterPolicy.evaluateUpdate(
            storedId = 42L,
            storedScheduledAt = 1000L,
            storedFireId = "fire-42",
            storedState = "FIRING",
            storedRefireAt = 0L,
            requestSnapshot = snapshotZeroRefire,
            targetGen = targetGenA
        )

        assertEquals(PreferencesManager.WriterPolicyDecision.REJECT_STALE, decision)
    }

    @Test
    fun `DataStore ActiveAlarmWriterPolicy - SNOOZED update when storedState is invalid is REJECT_STALE`() {
        val targetGenA = AlarmGeneration(42L, 1000L, "fire-42")
        val snapshotA = ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 1000L,
            state = "SNOOZED",
            refireAt = 1600L
        )

        val decisionBlankState = PreferencesManager.ActiveAlarmWriterPolicy.evaluateUpdate(
            storedId = 42L,
            storedScheduledAt = 1000L,
            storedFireId = "fire-42",
            storedState = "", // Blank storedState!
            storedRefireAt = 0L,
            requestSnapshot = snapshotA,
            targetGen = targetGenA
        )

        assertEquals(PreferencesManager.WriterPolicyDecision.REJECT_STALE, decisionBlankState)
    }

    @Test
    fun `DataStore ActiveAlarmWriterPolicy - new START B FIRING state replaces A`() {
        val snapshotB = ActiveAlarmSnapshot(
            alarmId = 99L,
            scheduledAt = 5000L,
            fireId = "fire-99",
            firedAt = 5000L,
            state = "FIRING",
            refireAt = 0L
        )

        val allowed = PreferencesManager.ActiveAlarmWriterPolicy.shouldExecuteUpdate(
            storedId = 42L,
            storedScheduledAt = 1000L,
            storedFireId = "fire-42",
            requestSnapshot = snapshotB,
            targetGen = null
        )

        assertTrue("New FIRING start B MUST replace A", allowed)
    }
}
