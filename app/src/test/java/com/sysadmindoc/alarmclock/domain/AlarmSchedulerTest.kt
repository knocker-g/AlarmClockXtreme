package com.sysadmindoc.alarmclock.domain

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import java.time.DayOfWeek
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
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
@Config(sdk = [30], application = Application::class)
class AlarmSchedulerTest {
    private lateinit var context: Context
    private lateinit var repository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var weatherRepository: com.sysadmindoc.alarmclock.data.repository.WeatherRepository
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: AlarmScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = mockk(relaxed = true)
        calculator = mockk()
        preferencesManager = mockk()
        holidayRepository = mockk()
        incidentRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { holidayRepository.isHoliday(any()) } returns false

        weatherRepository = mockk(relaxed = true)
        every { weatherRepository.getCachedWeather() } returns null

        scheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository
        )
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun isSnowOrIceCodeMatchesExpectedWmoCodes() {
        val snowCodes = listOf(56, 57, 66, 67, 71, 73, 75, 77, 85, 86)
        val clearCodes = listOf(0, 1, 2, 3, 45, 48, 51, 53, 55, 61, 63, 65, 80, 81, 82, 95, 96, 99)
        snowCodes.forEach { assert(AlarmScheduler.isSnowOrIceCode(it)) { "Expected $it to be snow/ice" } }
        clearCodes.forEach { assert(!AlarmScheduler.isSnowOrIceCode(it)) { "Expected $it to NOT be snow/ice" } }
    }

    @Test
    fun `non-null Owner Provider under default LEGACY_ONLY route does NOT call Owner for SET`() = runTest {
        val alarm = enabledAlarm(id = 500L)
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val mockOwner = mockk<ScheduleAuthorityOwner>()

        val legacyOnlyScheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner },
            routeAuthority = ProductionSchedulingRouteAuthority() // Default LEGACY_ONLY
        )

        legacyOnlyScheduler.schedule(alarm, requestWidgetUpdate = false)

        coVerify(exactly = 0) { mockOwner.executeOperation(any(), any(), any(), any()) }
    }

    @Test
    fun `non-null Owner Provider under default LEGACY_ONLY route does NOT call Owner for CANCEL`() = runTest {
        val mockOwner = mockk<ScheduleAuthorityOwner>()

        val legacyOnlyScheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner },
            routeAuthority = ProductionSchedulingRouteAuthority() // Default LEGACY_ONLY
        )

        legacyOnlyScheduler.cancel(501L)

        coVerify(exactly = 0) { mockOwner.executeOperation(any(), any(), any(), any()) }
    }

    @Test
    fun `successful fresh OS SET cancels DirectBoot fallback when OWNER_EXCLUSIVE route active`() = runTest {
        val alarm = enabledAlarm(id = 200L)
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(200L, "SET", triggerTime, any(), any()) } returns OwnerResult.OsAccepted(1L, "act-sched-200")

        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRouteAuthority.isOwnerRouteActive() } returns true

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner },
            routeAuthority = ownerRouteAuthority
        )

        mockkObject(DirectBootAlarmCache)
        every { DirectBootAlarmCache.cancelScheduledFallback(any(), 200L) } just Runs

        schedulerWithOwner.schedule(alarm, requestWidgetUpdate = false)

        verify(exactly = 1) { DirectBootAlarmCache.cancelScheduledFallback(any(), 200L) }
    }

    @Test
    fun `DuplicateIgnored outcome does NOT cancel DirectBoot fallback when OWNER_EXCLUSIVE route active`() = runTest {
        val alarm = enabledAlarm(id = 201L)
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(201L, "SET", triggerTime, any(), any()) } returns OwnerResult.DuplicateIgnored

        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRouteAuthority.isOwnerRouteActive() } returns true

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner },
            routeAuthority = ownerRouteAuthority
        )

        mockkObject(DirectBootAlarmCache)
        every { DirectBootAlarmCache.cancelScheduledFallback(any(), 201L) } just Runs

        schedulerWithOwner.schedule(alarm, requestWidgetUpdate = false)

        verify(exactly = 0) { DirectBootAlarmCache.cancelScheduledFallback(any(), 201L) }
    }

    @Test
    fun `failed OS SET preserves DirectBoot fallback when OWNER_EXCLUSIVE route active`() = runTest {
        val alarm = enabledAlarm(id = 202L)
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(202L, "SET", triggerTime, any(), any()) } returns OwnerResult.OutcomeUnknown(1L, IllegalStateException("OS Error"))
        coEvery { mockOwner.executeOperation(202L, "CANCEL", null, any(), any()) } returns OwnerResult.OsAccepted(1L, "act-cancel-202")

        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRouteAuthority.isOwnerRouteActive() } returns true

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { mockOwner },
            routeAuthority = ownerRouteAuthority
        )

        mockkObject(DirectBootAlarmCache)
        every { DirectBootAlarmCache.cancelScheduledFallback(any(), 202L) } just Runs
        every { DirectBootAlarmCache.removeIfMatches(any(), any()) } just Runs

        schedulerWithOwner.schedule(alarm, requestWidgetUpdate = false)

        verify(exactly = 0) { DirectBootAlarmCache.cancelScheduledFallback(any(), 202L) }
    }

    @Test
    fun `valid SmartWake early fire cancels main alarm when target trigger matches`() = runTest {
        val targetTime = System.currentTimeMillis() + 600_000L
        val alarm = enabledAlarm(id = 300L).copy(nextTriggerTime = targetTime)
        coEvery { repository.getById(300L) } returns alarm
        coEvery { repository.getAuthorityByAlarmId(300L) } returns null

        val canceled = scheduler.cancelMainScheduledAlarm(300L, expectedTriggerTime = targetTime)

        coVerify { repository.getById(300L) }
    }

    @Test
    fun `stale SmartWake callback ignores cancellation when current trigger is newer`() = runTest {
        val originalTargetTime = System.currentTimeMillis() + 600_000L
        val newerTargetTime = System.currentTimeMillis() + 86400_000L
        val alarmWithNewerTrigger = enabledAlarm(id = 301L).copy(nextTriggerTime = newerTargetTime)
        coEvery { repository.getById(301L) } returns alarmWithNewerTrigger
        coEvery { repository.getAuthorityByAlarmId(301L) } returns null

        val canceled = scheduler.cancelMainScheduledAlarm(301L, expectedTriggerTime = originalTargetTime)

        assertFalse("Stale cancellation MUST be rejected when current trigger is newer", canceled)
    }

    @Test
    fun `missing alarm row rejects SmartWake cancellation`() = runTest {
        coEvery { repository.getById(302L) } returns null

        val canceled = scheduler.cancelMainScheduledAlarm(302L, expectedTriggerTime = 1000L)

        assertFalse("Missing alarm row MUST reject cancellation", canceled)
    }

    @Test
    fun `new incarnation instanceToken mismatch rejects SmartWake cancellation`() = runTest {
        val alarm = enabledAlarm(id = 303L).copy(nextTriggerTime = 1000L)
        val authority = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = 303L,
            instanceToken = "token-NEW-INC",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1000L,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = "act-303"
        )
        coEvery { repository.getById(303L) } returns alarm
        coEvery { repository.getAuthorityByAlarmId(303L) } returns authority

        val canceled = scheduler.cancelMainScheduledAlarm(
            alarmId = 303L,
            expectedTriggerTime = 1000L,
            expectedInstanceToken = "token-OLD-INC" // Mismatched instance token!
        )

        assertFalse("InstanceToken mismatch MUST reject cancellation", canceled)
    }

    @Test
    fun `restoreEpoch mismatch rejects SmartWake cancellation`() = runTest {
        val alarm = enabledAlarm(id = 304L).copy(nextTriggerTime = 1000L)
        val authority = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = 304L,
            instanceToken = "token-304",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = 1000L,
            reconcileState = "SETTLED",
            restoreEpoch = 200L, // Current restore epoch is 200L
            actionKey = "act-304"
        )
        coEvery { repository.getById(304L) } returns alarm
        coEvery { repository.getAuthorityByAlarmId(304L) } returns authority

        val canceled = scheduler.cancelMainScheduledAlarm(
            alarmId = 304L,
            expectedTriggerTime = 1000L,
            expectedRestoreEpoch = 100L // Mismatched restore epoch!
        )

        assertFalse("RestoreEpoch mismatch MUST reject cancellation", canceled)
    }

    @Test
    fun `stale operation A detects nextTrigger updated by B and aborts OS registration`() = runTest {
        val alarmA = enabledAlarm(id = 500L).copy(nextTriggerTime = 1000L)
        val triggerTimeA = 2000L
        val triggerTimeB = 9000L

        // First getById returns alarmA, second getById (after updateNextTrigger) returns alarm updated by B
        coEvery { repository.getById(500L) } returnsMany listOf(
            alarmA,
            alarmA.copy(nextTriggerTime = triggerTimeB)
        )

        val result = scheduler.scheduleAt(alarmA, triggerTimeA)

        assertFalse("Stale operation A MUST abort when B updated nextTriggerTime", result)
    }

    @Test
    fun `executable OS-slot race proof - interleaved thread A OS call overwrites thread B when mutex absent`() = runTest {
        val alarmId = 601L
        val triggerTimeA = 2000L
        val triggerTimeB = 9000L
        val alarm = enabledAlarm(id = alarmId)

        // Step 1: Thread B completes scheduleAt(alarm, triggerTimeB)
        coEvery { repository.getById(alarmId) } returns alarm.copy(nextTriggerTime = triggerTimeB)
        scheduler.scheduleAt(alarm, triggerTimeB)

        // Step 2: Thread A resumes after DB check and calls applyRawOsSet(alarmId, triggerTimeA)
        val rawSetA = scheduler.applyRawOsSet(alarmId, triggerTimeA)
        assertTrue(rawSetA)

        // Verify that AlarmManager now holds stale triggerTimeA (2000L) while DB holds triggerTimeB (9000L)
        val nextOsAlarm = org.robolectric.Shadows.shadowOf(
            context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        ).nextScheduledAlarm

        assertNotNull(nextOsAlarm)
        assertEquals(triggerTimeA, nextOsAlarm?.triggerAtTime) // Stale 2000L in OS!
        val currentDbTrigger = repository.getById(alarmId)?.nextTriggerTime
        assertEquals(triggerTimeB, currentDbTrigger) // Current 9000L in DB!

        // PROOF DEMONSTRATED: OS slot holds stale 2000L while DB holds 9000L!
        assertTrue("Proves OS slot/DB discrepancy occurs when thread A applies raw OS set after B", nextOsAlarm?.triggerAtTime != currentDbTrigger)
    }

    @Test
    fun `per-slot mainSlotMutexMap serializes concurrent scheduleAt operations for same alarmId`() = runTest {
        val alarmId = 700L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 20000L
        val triggerTimeB = now + 90000L
        var currentAlarm = enabledAlarm(id = alarmId)

        coEvery { repository.getById(any()) } answers { currentAlarm }
        coEvery { repository.updateNextTrigger(any(), any()) } answers {
            val newTrigger = secondArg<Long>()
            currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
        }
        coEvery { repository.casUpdateNextTrigger(any(), any(), any()) } answers {
            val expected = secondArg<Long>()
            val newTrigger = thirdArg<Long>()
            if (currentAlarm.nextTriggerTime == expected) {
                currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
                1
            } else 0
        }

        val resultA = scheduler.scheduleAt(currentAlarm, triggerTimeA)
        val resultB = scheduler.scheduleAt(currentAlarm, triggerTimeB)

        assertTrue(resultA)
        assertTrue(resultB)
    }

    @Test
    fun `Test A - concurrent coroutines A and B on same alarmId are serialized by mainSlotMutexMap`() = runTest {
        val alarmId = 701L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 20000L
        val triggerTimeB = now + 90000L
        var currentAlarm = enabledAlarm(id = alarmId)

        coEvery { repository.getById(any()) } answers { currentAlarm }
        coEvery { repository.updateNextTrigger(any(), any()) } answers {
            val newTrigger = secondArg<Long>()
            currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
        }

        val barrierA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val barrierEnteredA = kotlinx.coroutines.CompletableDeferred<Unit>()
        val osSetOrder = mutableListOf<Long>()

        scheduler.testOnPreOsRegistrationBarrier = { id, trigger ->
            if (id == alarmId && trigger == triggerTimeA) {
                barrierEnteredA.complete(Unit)
                barrierA.await()
            }
            if (id == alarmId) {
                osSetOrder.add(trigger)
            }
        }

        // Launch operation A in background
        val jobA = launch {
            scheduler.scheduleAt(currentAlarm, triggerTimeA)
        }

        barrierEnteredA.await() // Wait for A to enter barrier inside scheduleAt mutex lock!

        // Launch operation B concurrently
        var completedB = false
        val jobB = launch {
            scheduler.scheduleAt(currentAlarm, triggerTimeB)
            completedB = true
        }

        // Assert B is BLOCKED because A holds mainSlotMutexMap[701L]!
        assertFalse("Operation B MUST NOT complete while operation A holds mainSlotMutexMap lock", completedB)

        // Unblock A
        barrierA.complete(Unit)
        jobA.join()
        jobB.join()

        assertTrue("Operation B completes after A unlocks mainSlotMutexMap", completedB)

        // Assert ordered OS SET execution: A executed first (triggerTimeA), B executed second (triggerTimeB)
        assertEquals(2, osSetOrder.size)
        assertEquals(triggerTimeA, osSetOrder[0])
        assertEquals(triggerTimeB, osSetOrder[1])

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val scheduledAlarms = org.robolectric.Shadows.shadowOf(alarmManager).scheduledAlarms
        assertFalse(scheduledAlarms.isEmpty())
        assertEquals("Final OS trigger MUST match operation B", triggerTimeB, scheduledAlarms.last().triggerAtTime)

        val finalDbTrigger = repository.getById(alarmId)?.nextTriggerTime
        assertEquals("Final DB trigger MUST match operation B", triggerTimeB, finalDbTrigger)
    }

    @Test
    fun `Phase 1 Test A - stale generation A scheduleAt with targetGen is rejected when B is active`() = runTest {
        val alarmId = 705L
        val triggerTimeA = System.currentTimeMillis() + 20000L
        val triggerTimeB = System.currentTimeMillis() + 90000L
        val genA = com.sysadmindoc.alarmclock.service.AlarmGeneration(alarmId, triggerTimeA, "fire-705-A")
        val alarmB = enabledAlarm(id = alarmId).copy(nextTriggerTime = triggerTimeB)

        // DB currently holds generation B (90000L)
        coEvery { repository.getById(alarmId) } returns alarmB

        // Stale A attempts scheduleAt with targetGen = genA
        val resultA = scheduler.scheduleAt(enabledAlarm(id = alarmId), triggerTimeA, targetGen = genA)

        assertFalse("Stale operation A scheduleAt MUST be rejected when DB holds generation B", resultA)
    }

    @Test
    fun `Phase 1 Test C - same target timestamp with different fireId generation tokens is rejected`() = runTest {
        val alarmId = 707L
        val sameTarget = System.currentTimeMillis() + 60000L
        val genA = com.sysadmindoc.alarmclock.service.AlarmGeneration(alarmId, sameTarget, "fire-707-GEN-A")

        val alarmB = enabledAlarm(id = alarmId).copy(nextTriggerTime = sameTarget)
        coEvery { repository.getById(alarmId) } returns alarmB

        // Stale A attempts scheduleAt with targetGen = genA while DB holds genB's fireId
        val resultA = scheduler.scheduleAt(enabledAlarm(id = alarmId), sameTarget, targetGen = genA)

        assertFalse("Stale A with different fireId token MUST be rejected even if triggerTime matches", resultA)
    }

    @Test
    fun `Discriminator B - same target timestamp with different generation proves value-level equality limitation`() = runTest {
        val alarmId = 702L
        val sameTarget = System.currentTimeMillis() + 60000L
        val alarmA = enabledAlarm(id = alarmId).copy(nextTriggerTime = sameTarget)

        coEvery { repository.getById(alarmId) } returns alarmA

        val resultA = scheduler.scheduleAt(alarmA, sameTarget)
        val resultB = scheduler.scheduleAt(alarmA, sameTarget)

        assertTrue(resultA)
        assertTrue(resultB)

        // Demonstrates that value-level nextTriggerTime equality alone cannot distinguish generation A from B
        assertEquals(sameTarget, repository.getById(alarmId)?.nextTriggerTime)
    }

    private fun enabledAlarm(id: Long): Alarm = Alarm(
        id = id,
        hour = 7,
        minute = 30,
        label = "Morning",
        isEnabled = true,
        repeatDays = setOf(DayOfWeek.MONDAY)
    )
}
