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
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
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
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
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

        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

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
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        var currentAlarm200 = enabledAlarm(id = 200L).copy(nextTriggerTime = triggerTime)
        coEvery { repository.getById(200L) } answers { currentAlarm200 }
        coEvery { repository.updateNextTrigger(200L, any()) } answers {
            currentAlarm200 = currentAlarm200.copy(nextTriggerTime = secondArg())
        }
        coEvery { repository.casUpdateNextTriggerInitialWithAuthority(any(), any(), any(), any(), any(), any()) } returns 1

        val mockAuth = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = 200L, instanceToken = "inst-200", definitionRevision = 1L, scheduleRevision = 1L,
            lifecycle = "LIVE", desiredOperation = "SET", desiredTriggerMillis = triggerTime,
            reconcileState = "SETTLED", restoreEpoch = 100L, actionKey = "act-init"
        )
        coEvery { repository.getAuthorityByAlarmId(200L) } returns mockAuth

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(eq(200L), eq("SET"), eq(triggerTime), any(), any()) } returns OwnerResult.OsAccepted(1L, "act-sched-200")

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
        every { DirectBootAlarmCache.saveIfEarlier(any(), any(), any()) } just Runs

        schedulerWithOwner.schedule(alarm, requestWidgetUpdate = false)

        verify(exactly = 1) { DirectBootAlarmCache.cancelScheduledFallback(any(), 200L) }
    }

    @Test
    fun `DuplicateIgnored outcome does NOT cancel DirectBoot fallback when OWNER_EXCLUSIVE route active`() = runTest {
        val alarm = enabledAlarm(id = 201L)
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        var currentAlarm201 = enabledAlarm(id = 201L).copy(nextTriggerTime = triggerTime)
        coEvery { repository.getById(201L) } answers { currentAlarm201 }
        coEvery { repository.updateNextTrigger(201L, any()) } answers {
            currentAlarm201 = currentAlarm201.copy(nextTriggerTime = secondArg())
        }

        val mockAuth = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = 201L, instanceToken = "inst-201", definitionRevision = 1L, scheduleRevision = 1L,
            lifecycle = "LIVE", desiredOperation = "SET", desiredTriggerMillis = triggerTime,
            reconcileState = "SETTLED", restoreEpoch = 100L, actionKey = "act-init"
        )
        coEvery { repository.getAuthorityByAlarmId(201L) } returns mockAuth

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(eq(201L), eq("SET"), eq(triggerTime), any(), any()) } returns OwnerResult.DuplicateIgnored

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
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        var currentAlarm202 = enabledAlarm(id = 202L).copy(nextTriggerTime = triggerTime)
        coEvery { repository.getById(202L) } answers { currentAlarm202 }
        coEvery { repository.updateNextTrigger(202L, any()) } answers {
            currentAlarm202 = currentAlarm202.copy(nextTriggerTime = secondArg())
        }

        val mockAuth = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = 202L, instanceToken = "inst-202", definitionRevision = 1L, scheduleRevision = 1L,
            lifecycle = "LIVE", desiredOperation = "SET", desiredTriggerMillis = triggerTime,
            reconcileState = "SETTLED", restoreEpoch = 100L, actionKey = "act-init"
        )
        coEvery { repository.getAuthorityByAlarmId(202L) } returns mockAuth

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery { mockOwner.executeOperation(eq(202L), eq("SET"), eq(triggerTime), any(), any()) } returns OwnerResult.OutcomeUnknown(1L, IllegalStateException("OS Error"))

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

        assertFalse("Stale operation A MUST abort when B updated nextTriggerTime", result.isArmed)
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

        assertTrue(resultA.isArmed)
        assertTrue(resultB.isArmed)
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

        assertFalse("Stale operation A scheduleAt MUST be rejected when DB holds generation B", resultA.isArmed)
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

        assertFalse("Stale A with different fireId token MUST be rejected even if triggerTime matches", resultA.isArmed)
    }

    @Test
    fun `scheduleAt rejects stale same-generation newer revision in DB`() = runTest {
        val alarmId = 708L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 20000L

        // DB current alarm has same generation 1 but advanced revision 2
        val currentDbAlarm = enabledAlarm(id = alarmId).copy(generation = 1, revision = 2)
        coEvery { repository.getById(alarmId) } returns currentDbAlarm

        // Stale request A presents same generation 1 but older revision 1
        val staleAlarmA = enabledAlarm(id = alarmId).copy(generation = 1, revision = 1)
        val resultA = scheduler.scheduleAt(staleAlarmA, triggerTimeA)

        assertFalse("scheduleAt MUST be rejected when DB revision (2) > requested revision (1)", resultA.isArmed)
        assertTrue("Outcome MUST be RegistrationFailed", resultA is ScheduleOutcome.RegistrationFailed)
    }

    @Test
    fun `scheduleAt rejects disabled or deleted alarm in DB`() = runTest {
        val alarmId = 709L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 20000L

        // DB current alarm is disabled
        val disabledDbAlarm = enabledAlarm(id = alarmId).copy(isEnabled = false)
        coEvery { repository.getById(alarmId) } returns disabledDbAlarm

        val staleAlarmA = enabledAlarm(id = alarmId)
        val resultA = scheduler.scheduleAt(staleAlarmA, triggerTimeA)

        assertFalse("scheduleAt MUST be rejected when DB alarm is disabled", resultA.isArmed)
        assertTrue("Outcome MUST be NoArm(DISABLED)", resultA is ScheduleOutcome.NoArm && resultA.reason == "DISABLED")
    }

    @Test
    fun `Required Real Test - stale operation A with expectedRevision 1L is rejected when B becomes current at revision 2L`() = runTest {
        val alarmId = 710L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 20000L

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRouteAuthority.isOwnerRouteActive() } returns true

        // Stale operation A presents expectedRevision = 1L when DB has advanced to revision 2L
        coEvery {
            mockOwner.executeOperation(alarmId, "SET", triggerTimeA, any(), expectedRevision = 1L)
        } returns OwnerResult.Superseded(2L)

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

        // Stale A calls scheduleAt passing expectedRevision = 1L
        val resultA = schedulerWithOwner.scheduleAt(
            enabledAlarm(id = alarmId),
            triggerTimeA,
            expectedRevision = 1L
        )

        assertFalse("Stale operation A with expectedRevision 1L MUST be rejected", resultA.isArmed)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `Part 4 Real Integration Test - origin-bound token with real Owner and real CAS rules rejects stale A when B becomes current`() = runTest {
        val alarmId = 800L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 20000L
        val triggerTimeB = now + 90000L

        var currentAuth = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = alarmId,
            instanceToken = "inst-$alarmId",
            definitionRevision = 1L,
            scheduleRevision = 1L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = triggerTimeA,
            reconcileState = "SETTLED",
            restoreEpoch = 100L,
            actionKey = "act-init"
        )

        val mockDao = mockk<com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao>(relaxed = true)
        coEvery { mockDao.getMetaById(1) } returns com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
            id = 1,
            restoreEpoch = 100L,
            bootstrapState = "COMPLETED"
        )
        coEvery { mockDao.getAuthorityByAlarmId(alarmId) } answers { currentAuth }
        coEvery { mockDao.casClaimAuthority(any(), any(), any(), any(), any(), any(), any(), any()) } answers {
            val expRev = arg<Long>(3)
            val newOp = arg<String>(5)
            val newTrig = arg<Long?>(6)
            val newKey = arg<String?>(7)
            if (currentAuth.scheduleRevision == expRev) {
                currentAuth = currentAuth.copy(
                    scheduleRevision = expRev + 1L,
                    desiredOperation = newOp,
                    desiredTriggerMillis = newTrig,
                    actionKey = newKey,
                    reconcileState = "RECONCILING"
                )
                1
            } else 0
        }
        coEvery { mockDao.settleAuthority(any(), any(), any(), any(), any()) } answers {
            val claimedRev = arg<Long>(3)
            if (currentAuth.scheduleRevision == claimedRev) {
                currentAuth = currentAuth.copy(reconcileState = "SETTLED")
                1
            } else 0
        }

        var currentAlarm = enabledAlarm(id = alarmId).copy(nextTriggerTime = triggerTimeA)
        coEvery { repository.getAuthorityByAlarmId(alarmId) } answers { currentAuth }
        coEvery { repository.getById(alarmId) } answers { currentAlarm }
        coEvery { repository.getNextAlarm() } answers { currentAlarm }
        coEvery { repository.updateNextTrigger(any(), any()) } answers {
            val newTrigger = secondArg<Long>()
            currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
        }
        coEvery { repository.casUpdateNextTriggerInitialWithAuthority(any(), any(), any(), any(), any(), any()) } answers {
            val expRev = arg<Long>(2)
            if (currentAuth.scheduleRevision == expRev) {
                val newTrigger = secondArg<Long>()
                currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
                1
            } else {
                0
            }
        }
        coEvery { repository.casUpdateNextTriggerWithStrictAuthority(any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { repository.casUpdateNextTrigger(any(), any(), any()) } returns 1

        val fakeOsSlot = mutableListOf<String>()
        val barrierB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val barrierEnteredB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val realMainSlotApplier = MainSlotApplier { auth ->
            if (auth.desiredTriggerMillis == triggerTimeB) {
                barrierEnteredB.complete(Unit)
                barrierB.await()
            }
            fakeOsSlot.add("${auth.desiredOperation}_${auth.desiredTriggerMillis}")
            true
        }

        val realOwner = ScheduleAuthorityOwner(
            authorityDao = mockDao,
            mainSlotApplier = realMainSlotApplier,
            exactPermissionChecker = { true }
        )

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
            scheduleAuthorityOwnerProvider = { realOwner },
            routeAuthority = ownerRouteAuthority
        )

        // Step 1: Operation A captures origin-bound authority at revision 1L
        val originAuthA = repository.getAuthorityByAlarmId(alarmId)
        assertNotNull(originAuthA)
        val capturedRevisionA = originAuthA!!.scheduleRevision
        assertEquals(1L, capturedRevisionA)

        // Step 2: Operation B originates, captures origin-bound revision 1L, and acquires L2 slot lock first
        val originAuthB = repository.getAuthorityByAlarmId(alarmId)
        val capturedRevisionB = originAuthB!!.scheduleRevision
        var resB: OwnerResult? = null
        val jobB = launch {
            resB = realOwner.executeOperation(
                alarmId = alarmId,
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTimeB,
                actionKey = "act-B",
                expectedRevision = capturedRevisionB
            )
        }
        barrierEnteredB.await()

        // Launch stale Operation A concurrently while B holds shared L2 slot lock -> A MUST serialize on L2
        var resultA: ScheduleOutcome? = null
        val jobA = launch {
            resultA = schedulerWithOwner.scheduleAt(
                enabledAlarm(id = alarmId),
                triggerTimeA,
                expectedRevision = capturedRevisionA,
                expectedInstanceToken = originAuthA.instanceToken,
                expectedRestoreEpoch = originAuthA.restoreEpoch,
                expectedDefinitionRevision = originAuthA.definitionRevision
            )
        }
        runCurrent()
        assertFalse("Operation A MUST wait on shared L2 slot lock while B holds it", jobA.isCompleted)

        // Unblock B -> advances DB to 2L, then A enters L2 and is rejected
        barrierB.complete(Unit)
        advanceUntilIdle()
        jobB.join()
        jobA.join()

        assertTrue("Operation B MUST succeed and advance scheduleRevision to 2L", resB is OwnerResult.OsAccepted)

        // Step 3: Assert A is REJECTED by both Scheduler and direct Owner invocation
        assertNotNull(resultA)
        assertFalse("Stale operation A MUST be rejected when B became current", resultA!!.isArmed)
        val directStaleA = realOwner.executeOperation(alarmId, "SET", triggerTimeA, "act-A-stale", expectedRevision = capturedRevisionA)
        assertTrue("Direct stale Owner call for A MUST return Superseded", directStaleA is OwnerResult.Superseded)

        // Step 4: Verify Room DB authority and fake OS slot remain B (90000L)
        val finalAuth = repository.getAuthorityByAlarmId(alarmId)
        assertEquals("Final DB scheduleRevision MUST remain 2L", 2L, finalAuth?.scheduleRevision)
        assertEquals("Final DB trigger MUST remain B (90000L)", triggerTimeB, finalAuth?.desiredTriggerMillis)
        assertEquals("Fake OS slot MUST contain SET_90000L", listOf("SET_$triggerTimeB"), fakeOsSlot)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `Part 3 Real Database Test - real Room DB and real ScheduleAuthorityOwner rejects stale A without DAO mocks`() = runTest {
        val alarmId = 850L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 20000L
        val triggerTimeB = now + 90000L

        // Initialize actual in-memory Room Database and ScheduleAuthorityDao
        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        val realDao = roomDb.scheduleAuthorityDao()
        realDao.insertMeta(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                id = 1,
                restoreEpoch = 100L,
                bootstrapState = "INITIALIZED"
            )
        )

        // Insert initial ScheduleAuthority row into actual Room SQLite table
        realDao.insertAuthority(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "inst-$alarmId",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTimeA,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = "act-init"
            )
        )

        val fakeOsSlot = mutableListOf<String>()
        val barrierB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val barrierEnteredB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val realMainSlotApplier = MainSlotApplier { auth ->
            if (auth.desiredTriggerMillis == triggerTimeB) {
                barrierEnteredB.complete(Unit)
                barrierB.await()
            }
            fakeOsSlot.add("${auth.desiredOperation}_${auth.desiredTriggerMillis}")
            true
        }

        val realOwner = ScheduleAuthorityOwner(
            authorityDao = realDao,
            mainSlotApplier = realMainSlotApplier,
            exactPermissionChecker = { true }
        )

        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRouteAuthority.isOwnerRouteActive() } returns true

        var currentAlarm = enabledAlarm(id = alarmId).copy(nextTriggerTime = triggerTimeA)
        every { calculator.calculate(any()) } returns triggerTimeB
        every { calculator.calculate(any(), any()) } returns triggerTimeB
        coEvery { repository.getAuthorityByAlarmId(alarmId) } coAnswers {
            realDao.getAuthorityByAlarmId(alarmId)
        }
        coEvery { repository.getById(alarmId) } answers { currentAlarm }
        coEvery { repository.getNextAlarm() } answers { currentAlarm }
        coEvery { repository.updateNextTrigger(any(), any()) } answers {
            val newTrigger = secondArg<Long>()
            currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
        }
        coEvery { repository.casUpdateNextTriggerInitialWithAuthority(any(), any(), any(), any(), any(), any()) } coAnswers {
            val expRev = arg<Long>(2)
            if (realDao.getAuthorityByAlarmId(alarmId)?.scheduleRevision == expRev) {
                val newTrigger = secondArg<Long>()
                currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
                1
            } else {
                0
            }
        }
        coEvery { repository.casUpdateNextTriggerWithStrictAuthority(any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { repository.casUpdateNextTrigger(any(), any(), any()) } returns 1

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { realOwner },
            routeAuthority = ownerRouteAuthority
        )

        // Step 1: Operation A captures origin-bound authority revision 1L from real Room DB
        val originAuthA = realDao.getAuthorityByAlarmId(alarmId)
        assertNotNull(originAuthA)
        val capturedRevisionA = originAuthA!!.scheduleRevision
        assertEquals(1L, capturedRevisionA)

        // Step 2: Operation B originates, captures revision 1L from real Room DB, and enters L2 slot lock first
        val originAuthB = realDao.getAuthorityByAlarmId(alarmId)
        val capturedRevisionB = originAuthB!!.scheduleRevision
        currentAlarm = currentAlarm.copy(nextTriggerTime = triggerTimeB)
        var resB: OwnerResult? = null
        val jobB = launch {
            resB = realOwner.executeOperation(
                alarmId = alarmId,
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTimeB,
                actionKey = "act-B",
                expectedRevision = capturedRevisionB
            )
        }
        barrierEnteredB.await()

        // Launch stale Operation A concurrently while B holds shared L2 slot lock
        var resultA: ScheduleOutcome? = null
        val jobA = launch {
            resultA = schedulerWithOwner.scheduleAt(
                currentAlarm,
                triggerTimeA,
                expectedRevision = capturedRevisionA,
                expectedInstanceToken = originAuthA.instanceToken,
                expectedRestoreEpoch = originAuthA.restoreEpoch,
                expectedDefinitionRevision = originAuthA.definitionRevision
            )
        }
        runCurrent()
        assertFalse("Operation A MUST wait on shared L2 slot lock while B holds it", jobA.isCompleted)

        // Unblock B -> settles rev 2L in real Room DB, then A enters L2 and is rejected
        barrierB.complete(Unit)
        advanceUntilIdle()
        jobB.join()
        jobA.join()

        assertTrue("Operation B MUST succeed in real Room DB and advance scheduleRevision to 2L", resB is OwnerResult.OsAccepted)

        // Step 3: Assert A is REJECTED by both Scheduler and real Room DAO CAS
        assertNotNull(resultA)
        assertFalse("Stale operation A MUST be rejected when B became current", resultA!!.isArmed)
        val directStaleA = realOwner.executeOperation(alarmId, "SET", triggerTimeA, "act-A-stale", expectedRevision = capturedRevisionA)
        assertTrue("Stale operation A MUST be rejected by real Room DAO CAS", directStaleA is OwnerResult.Superseded)

        // Step 4: Verify real Room DB authority and fake OS slot remain B (triggerTimeB) and currentAlarm nextTriggerTime is preserved
        val finalAuth = realDao.getAuthorityByAlarmId(alarmId)
        assertEquals("Final real Room DB scheduleRevision MUST remain 2L", 2L, finalAuth?.scheduleRevision)
        assertEquals("Final real Room DB trigger MUST remain B", triggerTimeB, finalAuth?.desiredTriggerMillis)
        assertEquals("Fake OS slot MUST contain SET_B", listOf("SET_$triggerTimeB"), fakeOsSlot)
        assertEquals("Current DB nextTriggerTime MUST remain B", triggerTimeB, currentAlarm.nextTriggerTime)

        roomDb.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `Part 3 Real Database Test - B earlier than A - stale A cleanup preserves B earlier trigger when B is successor revision`() = runTest {
        val alarmId = 860L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 90000L // A is LATER (90s)
        val triggerTimeB = now + 20000L // B is EARLIER (20s)

        // Initialize actual in-memory Room Database and ScheduleAuthorityDao
        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        val realDao = roomDb.scheduleAuthorityDao()
        realDao.insertMeta(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                id = 1,
                restoreEpoch = 100L,
                bootstrapState = "INITIALIZED"
            )
        )

        // Insert initial ScheduleAuthority row into actual Room SQLite table
        realDao.insertAuthority(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "inst-$alarmId",
                definitionRevision = 1L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTimeA,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = "act-init"
            )
        )

        val fakeOsSlot = mutableListOf<String>()
        val barrierB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val barrierEnteredB = kotlinx.coroutines.CompletableDeferred<Unit>()
        val realMainSlotApplier = MainSlotApplier { auth ->
            if (auth.desiredTriggerMillis == triggerTimeB) {
                barrierEnteredB.complete(Unit)
                barrierB.await()
            }
            fakeOsSlot.add("${auth.desiredOperation}_${auth.desiredTriggerMillis}")
            true
        }

        val realOwner = ScheduleAuthorityOwner(
            authorityDao = realDao,
            mainSlotApplier = realMainSlotApplier,
            exactPermissionChecker = { true }
        )

        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRouteAuthority.isOwnerRouteActive() } returns true

        var currentAlarm = enabledAlarm(id = alarmId).copy(nextTriggerTime = triggerTimeA)
        coEvery { repository.getAuthorityByAlarmId(alarmId) } coAnswers {
            realDao.getAuthorityByAlarmId(alarmId)
        }
        coEvery { repository.getById(alarmId) } answers { currentAlarm }
        coEvery { repository.getNextAlarm() } answers { currentAlarm }
        coEvery { repository.updateNextTrigger(any(), any()) } answers {
            val newTrigger = secondArg<Long>()
            currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
        }
        coEvery { repository.casUpdateNextTriggerInitialWithAuthority(any(), any(), any(), any(), any(), any()) } coAnswers {
            val expRev = arg<Long>(2)
            if (realDao.getAuthorityByAlarmId(alarmId)?.scheduleRevision == expRev) {
                val newTrigger = secondArg<Long>()
                currentAlarm = currentAlarm.copy(nextTriggerTime = newTrigger)
                1
            } else {
                0
            }
        }
        coEvery { repository.casUpdateNextTriggerWithStrictAuthority(any(), any(), any(), any(), any(), any(), any()) } returns 1
        coEvery { repository.casUpdateNextTrigger(any(), any(), any()) } returns 1

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            scheduleAuthorityOwnerProvider = { realOwner },
            routeAuthority = ownerRouteAuthority
        )

        // Step 1: Operation A captures origin-bound authority revision 1L from real Room DB
        val originAuthA = realDao.getAuthorityByAlarmId(alarmId)
        assertNotNull(originAuthA)
        val capturedRevisionA = originAuthA!!.scheduleRevision
        assertEquals(1L, capturedRevisionA)

        // Step 2: Operation B (earlier trigger 20s) originates, captures revision 1L, and enters L2 slot lock first
        val originAuthB = realDao.getAuthorityByAlarmId(alarmId)
        val capturedRevisionB = originAuthB!!.scheduleRevision
        currentAlarm = currentAlarm.copy(nextTriggerTime = triggerTimeB)
        var resB: OwnerResult? = null
        val jobB = launch {
            resB = realOwner.executeOperation(
                alarmId = alarmId,
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTimeB,
                actionKey = "act-B-earlier",
                expectedRevision = capturedRevisionB
            )
        }
        barrierEnteredB.await()

        var resultA: ScheduleOutcome? = null
        val jobA = launch {
            resultA = schedulerWithOwner.scheduleAt(
                currentAlarm,
                triggerTimeA,
                expectedRevision = capturedRevisionA,
                expectedInstanceToken = originAuthA.instanceToken,
                expectedRestoreEpoch = originAuthA.restoreEpoch,
                expectedDefinitionRevision = originAuthA.definitionRevision
            )
        }
        runCurrent()
        assertFalse("Operation A MUST wait on shared L2 slot lock while B holds it", jobA.isCompleted)

        // Unblock B -> settles rev 2L in real Room DB, then A enters L2 and is rejected
        barrierB.complete(Unit)
        advanceUntilIdle()
        jobB.join()
        jobA.join()

        assertTrue("Operation B (earlier trigger) MUST succeed in real Room DB and advance scheduleRevision to 2L", resB is OwnerResult.OsAccepted)

        // Step 3: Assert A is REJECTED
        assertNotNull(resultA)
        assertFalse("Stale operation A MUST be rejected when B became current", resultA!!.isArmed)
        val directStaleA = realOwner.executeOperation(alarmId, "SET", triggerTimeA, "act-A-stale-earlier", expectedRevision = capturedRevisionA)
        assertTrue("Stale operation A MUST be rejected by real Room DAO CAS", directStaleA is OwnerResult.Superseded)

        // Step 4: Verify real Room DB authority and fake OS slot remain B's earlier trigger (triggerTimeB) and scheduleRevision 2L
        val finalAuth = realDao.getAuthorityByAlarmId(alarmId)
        assertEquals("Final real Room DB scheduleRevision MUST remain 2L", 2L, finalAuth?.scheduleRevision)
        assertEquals("Final real Room DB trigger MUST remain B's earlier trigger (20s)", triggerTimeB, finalAuth?.desiredTriggerMillis)
        assertEquals("Fake OS slot MUST contain SET_B_earlier", listOf("SET_$triggerTimeB"), fakeOsSlot)

        roomDb.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `Strict Authority Fingerprint Test - casUpdateNextTriggerWithStrictAuthority verifies complete fingerprint`() = runTest {
        val alarmId = 880L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 50000L

        // Initialize actual in-memory Room Database
        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        val alarmDao = roomDb.alarmDao()
        val authDao = roomDb.scheduleAuthorityDao()

        // Insert alarm row and schedule_authority row into real Room SQLite DB
        alarmDao.insert(enabledAlarm(id = alarmId).copy(nextTriggerTime = triggerTimeA))
        authDao.insertAuthority(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "inst-$alarmId",
                definitionRevision = 10L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTimeA,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = "act-init"
            )
        )

        // 1. Full fingerprint matches: update 1
        val resMatch = alarmDao.casUpdateNextTriggerWithStrictAuthority(
            id = alarmId,
            expectedTrigger = triggerTimeA,
            newTrigger = now + 10000L,
            expectedRevision = 1L,
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(1, resMatch)
        assertEquals(now + 10000L, alarmDao.getById(alarmId)?.nextTriggerTime)

        // Reset trigger back to triggerTimeA
        alarmDao.updateNextTrigger(alarmId, triggerTimeA)

        // 2. Wrong scheduleRevision: update 0
        val resWrongRev = alarmDao.casUpdateNextTriggerWithStrictAuthority(
            id = alarmId,
            expectedTrigger = triggerTimeA,
            newTrigger = 0L,
            expectedRevision = 99L, // Wrong scheduleRevision
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(0, resWrongRev)

        // 3. Wrong instanceToken: update 0
        val resWrongToken = alarmDao.casUpdateNextTriggerWithStrictAuthority(
            id = alarmId,
            expectedTrigger = triggerTimeA,
            newTrigger = 0L,
            expectedRevision = 1L,
            instanceToken = "inst-$alarmId-WRONG",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(0, resWrongToken)

        // 4. Wrong restoreEpoch: update 0
        val resWrongEpoch = alarmDao.casUpdateNextTriggerWithStrictAuthority(
            id = alarmId,
            expectedTrigger = triggerTimeA,
            newTrigger = 0L,
            expectedRevision = 1L,
            instanceToken = "inst-$alarmId",
            restoreEpoch = 999L,
            definitionRevision = 10L
        )
        assertEquals(0, resWrongEpoch)

        // 5. Wrong definitionRevision: update 0
        val resWrongDefRev = alarmDao.casUpdateNextTriggerWithStrictAuthority(
            id = alarmId,
            expectedTrigger = triggerTimeA,
            newTrigger = 0L,
            expectedRevision = 1L,
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            definitionRevision = 999L
        )
        assertEquals(0, resWrongDefRev)

        // 6. Missing authority: update 0
        val resMissingAuth = alarmDao.casUpdateNextTriggerWithStrictAuthority(
            id = 9999L,
            expectedTrigger = triggerTimeA,
            newTrigger = 0L,
            expectedRevision = 1L,
            instanceToken = "inst-9999",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(0, resMissingAuth)

        // 7. Identical trigger / new incarnation: stale update 0
        authDao.quarantineAuthority(alarmId, "inst-$alarmId", 1L)
        val resIdenticalTriggerNewIncarnation = alarmDao.casUpdateNextTriggerWithStrictAuthority(
            id = alarmId,
            expectedTrigger = triggerTimeA,
            newTrigger = 0L,
            expectedRevision = 1L,
            instanceToken = "inst-$alarmId-OLD",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(0, resIdenticalTriggerNewIncarnation)

        roomDb.close()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `Pre-Owner Atomic Writes Test - casUpdateNextTriggerInitialWithAuthority and casSetEnabledWithStrictAuthority protect pre-OS writes`() = runTest {
        val alarmId = 890L
        val now = System.currentTimeMillis()
        val triggerTimeA = now + 50000L
        val triggerTimeB = now + 30000L

        // Initialize actual in-memory Room Database
        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        val alarmDao = roomDb.alarmDao()
        val authDao = roomDb.scheduleAuthorityDao()

        // Insert alarm row and schedule_authority row into real Room SQLite DB
        alarmDao.insert(enabledAlarm(id = alarmId).copy(nextTriggerTime = triggerTimeA))
        authDao.insertAuthority(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                alarmId = alarmId,
                instanceToken = "inst-$alarmId",
                definitionRevision = 10L,
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTimeA,
                reconcileState = "SETTLED",
                restoreEpoch = 100L,
                actionKey = "act-init"
            )
        )

        // 1. Current generation initial write succeeds (updates 1 row)
        val initialMatch = alarmDao.casUpdateNextTriggerInitialWithAuthority(
            id = alarmId,
            newTrigger = triggerTimeA,
            expectedRevision = 1L,
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(1, initialMatch)

        // 2. B intervenes and increments scheduleRevision to 2L
        authDao.casClaimAuthority(
            alarmId = alarmId,
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            expectedRevision = 1L,
            expectedLifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = triggerTimeB,
            actionKey = "act-B"
        )

        // 3. Stale A initial write after B is REJECTED (updates 0 rows)
        val staleInitialWrite = alarmDao.casUpdateNextTriggerInitialWithAuthority(
            id = alarmId,
            newTrigger = triggerTimeA,
            expectedRevision = 1L, // Stale!
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(0, staleInitialWrite)

        // 4. Stale A early zeroing after B is REJECTED (updates 0 rows)
        val staleZeroing = alarmDao.casUpdateNextTriggerInitialWithAuthority(
            id = alarmId,
            newTrigger = 0L,
            expectedRevision = 1L, // Stale!
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(0, staleZeroing)

        // 5. Stale A expired one-shot setEnabled(false) vs newer B is REJECTED (updates 0 rows)
        val staleSetEnabled = alarmDao.casSetEnabledWithStrictAuthority(
            id = alarmId,
            enabled = false,
            nextTrigger = 0L,
            expectedRevision = 1L, // Stale!
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(0, staleSetEnabled)

        // 6. Valid current generation setEnabled(false) with revision 2L succeeds (updates 1 row)
        val validSetEnabled = alarmDao.casSetEnabledWithStrictAuthority(
            id = alarmId,
            enabled = false,
            nextTrigger = 0L,
            expectedRevision = 2L, // Current revision 2L!
            instanceToken = "inst-$alarmId",
            restoreEpoch = 100L,
            definitionRevision = 10L
        )
        assertEquals(1, validSetEnabled)
        assertEquals(false, alarmDao.getById(alarmId)?.isEnabled)

        roomDb.close()
    }

    @Test
    fun `Discriminator B - same target timestamp with different generation proves value-level equality limitation`() = runTest {
        val alarmId = 702L
        val sameTarget = System.currentTimeMillis() + 60000L
        val alarmA = enabledAlarm(id = alarmId).copy(nextTriggerTime = sameTarget)

        coEvery { repository.getById(alarmId) } returns alarmA

        val resultA = scheduler.scheduleAt(alarmA, sameTarget)
        val resultB = scheduler.scheduleAt(alarmA, sameTarget)

        assertTrue(resultA.isArmed)
        assertTrue(resultB.isArmed)

        // Demonstrates that value-level nextTriggerTime equality alone cannot distinguish generation A from B
        assertEquals(sameTarget, repository.getById(alarmId)?.nextTriggerTime)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test
    fun `ownerActiveFailClosedRouteGuardTest`() = runTest {
        val alarmId = 910L
        val triggerTime = System.currentTimeMillis() + 600_000L
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val mockAuth = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
            alarmId = alarmId, instanceToken = "inst-$alarmId", definitionRevision = 1L, scheduleRevision = 1L,
            lifecycle = "LIVE", desiredOperation = "SET", desiredTriggerMillis = triggerTime,
            reconcileState = "SETTLED", restoreEpoch = 100L, actionKey = "act-init"
        )

        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRouteAuthority.isOwnerRouteActive() } returns true

        val mockOwner = mockk<ScheduleAuthorityOwner>()

        val schedulerWithOwner = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            routeAuthority = ownerRouteAuthority,
            scheduleAuthorityOwnerProvider = { mockOwner }
        )

        // 1. Missing expectedRevision on scheduleAt under OWNER_EXCLUSIVE -> aborts write & OS call
        coEvery { repository.getAuthorityByAlarmId(alarmId) } returns mockAuth
        val scheduled = schedulerWithOwner.scheduleAt(
            enabledAlarm(id = alarmId),
            triggerTime,
            requestWidgetUpdate = false,
            expectedRevision = null // Missing expectedRevision!
        )

        assertFalse("scheduleAt MUST fail closed when expectedRevision is missing under OWNER_EXCLUSIVE", scheduled.isArmed)
        coVerify(exactly = 0) { repository.updateNextTrigger(alarmId, any()) }
        coVerify(exactly = 0) { repository.casUpdateNextTriggerInitialWithAuthority(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { mockOwner.executeOperation(any(), any(), any(), any(), any()) }

        // 2. Missing originRevision on early zeroing under OWNER_EXCLUSIVE -> aborts schedule BEFORE cancel or zeroing
        coEvery { repository.getAuthorityByAlarmId(alarmId) } returns null // Incomplete origin authority!
        schedulerWithOwner.schedule(enabledAlarm(id = alarmId), requestWidgetUpdate = false)

        coVerify(exactly = 0) { repository.updateNextTrigger(alarmId, 0) }
        coVerify(exactly = 0) { repository.casUpdateNextTriggerInitialWithAuthority(alarmId, 0, any(), any(), any(), any()) }
        coVerify(exactly = 0) { mockOwner.executeOperation(alarmId, "CANCEL", null, any(), any()) }

        // 3. Rejected cancel under OWNER_EXCLUSIVE (Superseded) -> skips worker/cache cancellations
        coEvery { repository.getAuthorityByAlarmId(alarmId) } returns mockAuth
        coEvery { mockOwner.executeOperation(alarmId, "CANCEL", null, any(), eq(1L)) } returns OwnerResult.Superseded(2L)

        // Seed DirectBoot cache with alarmId
        com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.saveIfEarlier(
            context,
            enabledAlarm(id = alarmId),
            triggerTime,
            now = triggerTime - 1000L
        )
        assertNotNull(com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context))

        // Execute schedule(disabledAlarm) with stale revision 1L -> owner returns Superseded(2L)
        schedulerWithOwner.schedule(enabledAlarm(id = alarmId).copy(isEnabled = false), requestWidgetUpdate = false)

        // Verify DirectBoot cache remains intact (NOT cleared/removed)!
        assertNotNull("DirectBoot cache MUST remain intact when cancellation is Superseded", com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.read(context))
        com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache.clear(context)

        // 3. LEGACY_ONLY route regression check -> executes legacy updateNextTrigger when isOwnerRouteActive() == false
        val legacyRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { legacyRouteAuthority.isOwnerRouteActive() } returns false

        val legacyScheduler = AlarmScheduler(
            context = context,
            repository = repository,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = holidayRepository,
            alarmIncidentRepository = incidentRepository,
            weatherRepository = weatherRepository,
            routeAuthority = legacyRouteAuthority
        )

        var legacyAlarmState = enabledAlarm(id = alarmId)
        coEvery { repository.getById(alarmId) } answers { legacyAlarmState }
        coEvery { repository.updateNextTrigger(alarmId, any()) } answers {
            legacyAlarmState = legacyAlarmState.copy(nextTriggerTime = secondArg())
        }

        val legacyScheduled = legacyScheduler.scheduleAt(
            enabledAlarm(id = alarmId),
            triggerTime,
            requestWidgetUpdate = false,
            expectedRevision = null
        )

        assertTrue("scheduleAt MUST succeed under LEGACY_ONLY route without expectedRevision", legacyScheduled.isArmed)
        coVerify { repository.updateNextTrigger(alarmId, triggerTime) }
    }

    @Test
    fun `RED-C41-01 - snooze rejects concurrent ScheduleAuthority update under LEGACY_ONLY`() = runTest {
        val alarmId = 951L
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L
        val snoozeAtMillis = now + 900_000L

        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val realDao = roomDb.scheduleAuthorityDao()
            realDao.insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 100L,
                    bootstrapState = "INITIALIZED"
                )
            )
            val realRepo = AlarmRepository(
                dao = roomDb.alarmDao(),
                groupDao = roomDb.alarmGroupDao(),
                database = roomDb
            )
            val savedId = realRepo.save(
                Alarm(
                    hour = 7,
                    minute = 30,
                    label = "Snooze Race",
                    isEnabled = true,
                    repeatDays = setOf(DayOfWeek.MONDAY),
                    nextTriggerTime = triggerTime
                )
            )
            val seededAuth = realDao.getAuthorityByAlarmId(savedId)!!
            val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
            shadowAm.scheduledAlarms.clear()

            val legacyRoute = mockk<ProductionSchedulingRouteAuthority>()
            every { legacyRoute.isOwnerRouteActive() } returns false

            val localScheduler = AlarmScheduler(
                context = context,
                repository = realRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = holidayRepository,
                alarmIncidentRepository = incidentRepository,
                weatherRepository = weatherRepository,
                routeAuthority = legacyRoute
            )

            // Part A: scheduleSnooze hits concurrent ScheduleAuthority mutation at pre-OS barrier
            localScheduler.testOnPreOsRegistrationBarrier = { id, _ ->
                if (id == savedId) {
                    val current = realDao.getAuthorityByAlarmId(savedId)!!
                    realDao.insertAuthority(
                        current.copy(
                            scheduleRevision = current.scheduleRevision + 1L,
                            instanceToken = "tok-snooze-edited"
                        )
                    )
                }
            }

            val alarmBeforeSnooze = realRepo.getById(savedId)!!
            val snoozeOutcome = localScheduler.scheduleSnooze(alarmBeforeSnooze, customMinutes = 10)
            assertFalse(
                "RED-C41-01A: scheduleSnooze MUST reject concurrent ScheduleAuthority change under LEGACY_ONLY",
                snoozeOutcome.isArmed
            )
            assertTrue(snoozeOutcome is ScheduleOutcome.RegistrationFailed)
            assertEquals("OS AlarmManager MUST remain unarmed after snooze authority race", 0, shadowAm.scheduledAlarms.size)

            // Part B: scheduleSnoozeAt receives stale expectedRevision = 1L while DB is already at rev 2L
            localScheduler.testOnPreOsRegistrationBarrier = null
            val staleAtOutcome = localScheduler.scheduleSnoozeAt(
                alarm = alarmBeforeSnooze,
                snoozeAtMillis = snoozeAtMillis,
                expectedRevision = seededAuth.scheduleRevision
            )
            assertFalse(
                "RED-C41-01B: scheduleSnoozeAt MUST reject stale expectedRevision under LEGACY_ONLY",
                staleAtOutcome.isArmed
            )
            assertTrue(staleAtOutcome is ScheduleOutcome.RegistrationFailed)
            assertEquals("OS AlarmManager MUST remain unarmed on stale scheduleSnoozeAt", 0, shadowAm.scheduledAlarms.size)
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun `RED-C41-02 - reconcileRegistration rejects concurrent ScheduleAuthority update under LEGACY_ONLY`() = runTest {
        val alarmId = 952L
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L
        val targetTrigger = now + 1_200_000L

        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val realDao = roomDb.scheduleAuthorityDao()
            realDao.insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 100L,
                    bootstrapState = "INITIALIZED"
                )
            )
            val realRepo = AlarmRepository(
                dao = roomDb.alarmDao(),
                groupDao = roomDb.alarmGroupDao(),
                database = roomDb
            )
            val savedId = realRepo.save(
                Alarm(
                    hour = 8,
                    minute = 0,
                    label = "Reconcile Race",
                    isEnabled = true,
                    repeatDays = setOf(DayOfWeek.TUESDAY),
                    nextTriggerTime = triggerTime
                )
            )
            val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
            shadowAm.scheduledAlarms.clear()

            val legacyRoute = mockk<ProductionSchedulingRouteAuthority>()
            every { legacyRoute.isOwnerRouteActive() } returns false

            val localScheduler = AlarmScheduler(
                context = context,
                repository = realRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = holidayRepository,
                alarmIncidentRepository = incidentRepository,
                weatherRepository = weatherRepository,
                routeAuthority = legacyRoute
            )

            localScheduler.testOnPreOsRegistrationBarrier = { id, _ ->
                if (id == savedId) {
                    val current = realDao.getAuthorityByAlarmId(savedId)!!
                    realDao.insertAuthority(
                        current.copy(
                            scheduleRevision = current.scheduleRevision + 1L,
                            instanceToken = "tok-reconcile-edited"
                        )
                    )
                }
            }

            val originAlarm = realRepo.getById(savedId)!!
            val outcome = localScheduler.reconcileRegistration(originAlarm, targetTrigger, isOsConfirmed = false)
            assertFalse(
                "RED-C41-02: reconcileRegistration MUST reject concurrent ScheduleAuthority change under LEGACY_ONLY",
                outcome.isArmed
            )
            assertTrue(outcome is ScheduleOutcome.RegistrationFailed)
            assertEquals("OS AlarmManager MUST remain unarmed after reconcile authority race", 0, shadowAm.scheduledAlarms.size)
            assertEquals("nextTriggerTime MUST be rolled back to origin trigger", triggerTime, realRepo.getById(savedId)?.nextTriggerTime)
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun `RED-C41-03 - scheduleAt fails closed on missing ScheduleAuthorityMeta epoch and on LEGACY_ONLY revision or missing authority mismatch`() = runTest {
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L

        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val realDao = roomDb.scheduleAuthorityDao()
            val realRepo = AlarmRepository(
                dao = roomDb.alarmDao(),
                groupDao = roomDb.alarmGroupDao(),
                database = roomDb
            )
            val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
            shadowAm.scheduledAlarms.clear()

            val legacyRoute = mockk<ProductionSchedulingRouteAuthority>()
            every { legacyRoute.isOwnerRouteActive() } returns false

            val localScheduler = AlarmScheduler(
                context = context,
                repository = realRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = holidayRepository,
                alarmIncidentRepository = incidentRepository,
                weatherRepository = weatherRepository,
                routeAuthority = legacyRoute
            )

            // Subcase A: ScheduleAuthorityMeta is ABSENT (null), caller explicitly passed expectedRestoreEpoch = 100L
            val alarmA = enabledAlarm(id = 953L).copy(nextTriggerTime = triggerTime)
            roomDb.alarmDao().insert(alarmA)
            val missingMetaOutcome = localScheduler.scheduleAt(
                alarm = alarmA,
                triggerTime = triggerTime,
                requestWidgetUpdate = false,
                expectedRestoreEpoch = 100L
            )
            assertFalse(
                "RED-C41-03A: caller-supplied expectedRestoreEpoch MUST NOT be dropped when ScheduleAuthorityMeta is missing",
                missingMetaOutcome.isArmed
            )
            assertTrue(missingMetaOutcome is ScheduleOutcome.RegistrationFailed)
            assertEquals(0, shadowAm.scheduledAlarms.size)

            // Subcase B: Meta exists (100L), ScheduleAuthority.scheduleRevision advanced 1 -> 2 with same instanceToken & restoreEpoch under LEGACY_ONLY
            realDao.insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 100L,
                    bootstrapState = "INITIALIZED"
                )
            )
            val savedBId = realRepo.save(
                Alarm(
                    id = 0L,
                    hour = 9,
                    minute = 0,
                    label = "Rev Mismatch",
                    isEnabled = true,
                    repeatDays = setOf(DayOfWeek.WEDNESDAY),
                    nextTriggerTime = triggerTime
                )
            )
            val originAuthB = realDao.getAuthorityByAlarmId(savedBId)!!
            realDao.insertAuthority(
                originAuthB.copy(scheduleRevision = originAuthB.scheduleRevision + 1L)
            )
            val alarmB = realRepo.getById(savedBId)!!
            val revMismatchOutcome = localScheduler.scheduleAt(
                alarm = alarmB,
                triggerTime = triggerTime,
                requestWidgetUpdate = false,
                expectedRevision = originAuthB.scheduleRevision,
                expectedInstanceToken = originAuthB.instanceToken,
                expectedRestoreEpoch = originAuthB.restoreEpoch,
                expectedDefinitionRevision = originAuthB.definitionRevision
            )
            assertFalse(
                "RED-C41-03B: LEGACY_ONLY scheduleAt MUST reject stale expectedRevision even when token and epoch match",
                revMismatchOutcome.isArmed
            )
            assertTrue(revMismatchOutcome is ScheduleOutcome.RegistrationFailed)
            assertEquals(0, shadowAm.scheduledAlarms.size)

            // Subcase C: ScheduleAuthority row deleted while caller supplied expectedInstanceToken -> fail closed
            realDao.deleteAuthorityByAlarmId(savedBId)
            val deletedAuthOutcome = localScheduler.scheduleAt(
                alarm = alarmB,
                triggerTime = triggerTime,
                requestWidgetUpdate = false,
                expectedRevision = originAuthB.scheduleRevision,
                expectedInstanceToken = originAuthB.instanceToken,
                expectedRestoreEpoch = originAuthB.restoreEpoch,
                expectedDefinitionRevision = originAuthB.definitionRevision
            )
            assertFalse(
                "RED-C41-03C: LEGACY_ONLY scheduleAt MUST reject missing ScheduleAuthority when caller passed expectedInstanceToken",
                deletedAuthOutcome.isArmed
            )
            assertTrue(deletedAuthOutcome is ScheduleOutcome.RegistrationFailed)
            assertEquals(0, shadowAm.scheduledAlarms.size)
        } finally {
            roomDb.close()
        }
    }

    @Test
    fun `P1A legacyOnly route leaves DE Queue untouched and keeps V1 scheduling intact`() = runTest {
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)
        val dePrefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()

        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            roomDb.scheduleAuthorityDao().insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 1L,
                    bootstrapState = "ENFORCED"
                )
            )
            val alarm = enabledAlarm(id = 960L).copy(nextTriggerTime = triggerTime, generation = 1, revision = 1)
            roomDb.alarmDao().insert(alarm)
            roomDb.scheduleAuthorityDao().insertAuthority(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                    alarmId = 960L,
                    instanceToken = "tok-p1-legacy-960",
                    definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
                    scheduleRevision = 1L,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = triggerTime,
                    reconcileState = "SETTLED",
                    restoreEpoch = 1L,
                    actionKey = "act-960"
                )
            )

            val realRepo = AlarmRepository(
                dao = roomDb.alarmDao(),
                groupDao = roomDb.alarmGroupDao(),
                database = roomDb
            )
            every { calculator.calculate(any<Alarm>()) } returns triggerTime
            every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

            val snapshotReader = com.sysadmindoc.alarmclock.directboot.DirectBootNormalSnapshotReader(roomDb) { true }
            val coordinator = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionCoordinator(snapshotReader)
            val projector = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector(
                context = context,
                database = roomDb,
                coordinator = coordinator
            )

            val localScheduler = AlarmScheduler(
                context = context,
                repository = realRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = holidayRepository,
                alarmIncidentRepository = incidentRepository,
                weatherRepository = weatherRepository,
                routeAuthority = ProductionSchedulingRouteAuthority(), // LEGACY_ONLY
                directBootNormalProjectorProvider = javax.inject.Provider { projector }
            )

            val outcome = localScheduler.schedule(alarm, requestWidgetUpdate = false)
            assertTrue("V1 scheduling must remain Armed, was $outcome", outcome is ScheduleOutcome.Armed)
            assertEquals(
                AlarmScheduler.DirectBootProjectionHookOutcome.SkippedLegacyOnly,
                localScheduler.lastDirectBootProjectionOutcome
            )
            assertEquals(
                com.sysadmindoc.alarmclock.directboot.QueueStorageState.Absent,
                DirectBootAlarmCache.inspectQueueStorageState(context)
            )
            assertEquals(null, DirectBootAlarmCache.getRawQueuePayload(context))
            assertEquals(960L, DirectBootAlarmCache.read(context)?.alarmId)
        } finally {
            DirectBootAlarmCache.clear(context)
            DirectBootAlarmCache.clearQueue(context)
            dePrefs.edit().clear().commit()
            roomDb.close()
        }
    }

    @Test
    fun `P1A ownerExclusive enforced fixture projects on schedule cancel and batch reschedule and blocks on nonEnforced`() = runTest {
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)
        val dePrefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()

        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            val authDao = roomDb.scheduleAuthorityDao()
            authDao.insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 1L,
                    bootstrapState = "ENFORCED"
                )
            )
            val alarm = enabledAlarm(id = 961L).copy(nextTriggerTime = triggerTime, generation = 1, revision = 1)
            roomDb.alarmDao().insert(alarm)
            val auth = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                alarmId = 961L,
                instanceToken = "tok-p1-owner-961",
                definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
                scheduleRevision = 1L,
                lifecycle = "LIVE",
                desiredOperation = "SET",
                desiredTriggerMillis = triggerTime,
                reconcileState = "SETTLED",
                restoreEpoch = 1L,
                actionKey = "act-961"
            )
            authDao.insertAuthority(auth)

            val realRepo = AlarmRepository(
                dao = roomDb.alarmDao(),
                groupDao = roomDb.alarmGroupDao(),
                database = roomDb
            )
            every { calculator.calculate(any<Alarm>()) } returns triggerTime
            every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

            val mockOwner = mockk<ScheduleAuthorityOwner>(relaxed = true)
            coEvery {
                mockOwner.executeOperation(eq(961L), eq("SET"), eq(triggerTime), any(), any())
            } returns OwnerResult.OsAccepted(1L, "act-sched-961")
            coEvery {
                mockOwner.executeOperation(eq(961L), eq("CANCEL"), isNull<Long>(), any(), any())
            } returns OwnerResult.OsAccepted(2L, "act-cancel-961")

            val ownerRoute = mockk<ProductionSchedulingRouteAuthority>()
            every { ownerRoute.isOwnerRouteActive() } returns true

            val snapshotReader = com.sysadmindoc.alarmclock.directboot.DirectBootNormalSnapshotReader(roomDb) { true }
            val coordinator = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionCoordinator(snapshotReader)
            val projector = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector(
                context = context,
                database = roomDb,
                coordinator = coordinator
            )

            val localScheduler = AlarmScheduler(
                context = context,
                repository = realRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = holidayRepository,
                alarmIncidentRepository = incidentRepository,
                weatherRepository = weatherRepository,
                scheduleAuthorityOwnerProvider = { mockOwner },
                routeAuthority = ownerRoute,
                directBootNormalProjectorProvider = javax.inject.Provider { projector }
            )

            // 1. Schedule projects Normal candidate to DE Queue under DEFAULT_DENY (no reconciler called)
            val schedOutcome = localScheduler.schedule(alarm, requestWidgetUpdate = false)
            assertTrue("Expected Armed, was $schedOutcome", schedOutcome is ScheduleOutcome.Armed)
            assertTrue(localScheduler.lastDirectBootProjectionOutcome is AlarmScheduler.DirectBootProjectionHookOutcome.Succeeded)
            assertEquals(listOf(961L), DirectBootAlarmCache.readQueue(context, now = now).map { it.alarmId })
            assertEquals(null, localScheduler.lastDirectBootReconcileResult)

            // 2. Cancel updates DE Queue to exclude cancelled alarm
            roomDb.alarmDao().update(alarm.copy(isEnabled = false, nextTriggerTime = 0L))
            authDao.insertAuthority(auth.copy(desiredOperation = "CANCEL", desiredTriggerMillis = null))
            val cancelOk = localScheduler.cancelSuspending(961L)
            assertTrue(cancelOk)
            assertTrue(localScheduler.lastDirectBootProjectionOutcome is AlarmScheduler.DirectBootProjectionHookOutcome.Succeeded)
            assertTrue(DirectBootAlarmCache.readQueue(context, now = now).isEmpty())

            // 3. Batch reschedule projects once after batch
            roomDb.alarmDao().update(alarm.copy(isEnabled = true, nextTriggerTime = triggerTime))
            authDao.insertAuthority(auth.copy(desiredOperation = "SET", desiredTriggerMillis = triggerTime))
            val batchOutcome = localScheduler.rescheduleAllInBatches(forceRecalculate = false, batchSize = 10)
            assertEquals(1, batchOutcome.armedCount)
            assertTrue(localScheduler.lastDirectBootProjectionOutcome is AlarmScheduler.DirectBootProjectionHookOutcome.Succeeded)
            assertEquals(listOf(961L), DirectBootAlarmCache.readQueue(context, now = now).map { it.alarmId })

            // 4. Non-ENFORCED (INITIALIZED) blocks projection without modifying DE Queue
            DirectBootAlarmCache.clearQueue(context)
            authDao.insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 1L,
                    bootstrapState = "INITIALIZED"
                )
            )
            localScheduler.schedule(alarm, requestWidgetUpdate = false)
            assertTrue(localScheduler.lastDirectBootProjectionOutcome is AlarmScheduler.DirectBootProjectionHookOutcome.Blocked)
            assertEquals(
                com.sysadmindoc.alarmclock.directboot.QueueStorageState.Absent,
                DirectBootAlarmCache.inspectQueueStorageState(context)
            )
            assertEquals(null, DirectBootAlarmCache.getRawQueuePayload(context))
        } finally {
            DirectBootAlarmCache.clear(context)
            DirectBootAlarmCache.clearQueue(context)
            dePrefs.edit().clear().commit()
            roomDb.close()
        }
    }

    @Test
    fun `P1A projection failure surfaces Failed outcome without breaking V1 ScheduleOutcome Armed`() = runTest {
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)
        val dePrefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()

        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            roomDb.scheduleAuthorityDao().insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 1L,
                    bootstrapState = "ENFORCED"
                )
            )
            val alarm = enabledAlarm(id = 962L).copy(nextTriggerTime = triggerTime, generation = 1, revision = 1)
            roomDb.alarmDao().insert(alarm)
            roomDb.scheduleAuthorityDao().insertAuthority(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                    alarmId = 962L,
                    instanceToken = "tok-p1-fail-962",
                    definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
                    scheduleRevision = 1L,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = triggerTime,
                    reconcileState = "SETTLED",
                    restoreEpoch = 1L,
                    actionKey = "act-962"
                )
            )

            // Corrupt DeliveryLedger so projection fails fail-closed
            dePrefs.edit().putString(
                com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD,
                "{corrupt_ledger"
            ).commit()

            val realRepo = AlarmRepository(
                dao = roomDb.alarmDao(),
                groupDao = roomDb.alarmGroupDao(),
                database = roomDb
            )
            every { calculator.calculate(any<Alarm>()) } returns triggerTime
            every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

            val mockOwner = mockk<ScheduleAuthorityOwner>(relaxed = true)
            coEvery {
                mockOwner.executeOperation(eq(962L), eq("SET"), eq(triggerTime), any(), any())
            } returns OwnerResult.OsAccepted(1L, "act-sched-962")

            val ownerRoute = mockk<ProductionSchedulingRouteAuthority>()
            every { ownerRoute.isOwnerRouteActive() } returns true

            val snapshotReader = com.sysadmindoc.alarmclock.directboot.DirectBootNormalSnapshotReader(roomDb) { true }
            val coordinator = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionCoordinator(snapshotReader)
            val projector = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector(
                context = context,
                database = roomDb,
                coordinator = coordinator
            )

            val localScheduler = AlarmScheduler(
                context = context,
                repository = realRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = holidayRepository,
                alarmIncidentRepository = incidentRepository,
                weatherRepository = weatherRepository,
                scheduleAuthorityOwnerProvider = { mockOwner },
                routeAuthority = ownerRoute,
                directBootNormalProjectorProvider = javax.inject.Provider { projector }
            )

            val outcome = localScheduler.schedule(alarm, requestWidgetUpdate = false)
            assertTrue("V1/OS ScheduleOutcome must still be Armed, was $outcome", outcome is ScheduleOutcome.Armed)
            val projOutcome = localScheduler.lastDirectBootProjectionOutcome
            assertTrue("Projection failure must NOT be hidden as Succeeded, was $projOutcome", projOutcome is AlarmScheduler.DirectBootProjectionHookOutcome.Failed)
            assertTrue((projOutcome as AlarmScheduler.DirectBootProjectionHookOutcome.Failed).result is com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionResult.DeWriteRejected)
        } finally {
            DirectBootAlarmCache.clear(context)
            DirectBootAlarmCache.clearQueue(context)
            dePrefs.edit().clear().commit()
            roomDb.close()
        }
    }

    @Test
    fun `P1C defaultDeny blocks V2 OS registration and reconciler runs strictly after projector when isolated seam enabled`() = runTest {
        val now = System.currentTimeMillis()
        val triggerTime = now + 600_000L
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)
        val dePrefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
        dePrefs.edit().clear().commit()

        val roomDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        try {
            roomDb.scheduleAuthorityDao().insertMeta(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                    id = 1,
                    restoreEpoch = 1L,
                    bootstrapState = "ENFORCED"
                )
            )
            val alarm = enabledAlarm(id = 963L).copy(nextTriggerTime = triggerTime, generation = 1, revision = 1)
            roomDb.alarmDao().insert(alarm)
            roomDb.scheduleAuthorityDao().insertAuthority(
                com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                    alarmId = 963L,
                    instanceToken = "tok-p1c-963",
                    definitionRevision = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm),
                    scheduleRevision = 1L,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = triggerTime,
                    reconcileState = "SETTLED",
                    restoreEpoch = 1L,
                    actionKey = "act-963"
                )
            )

            val realRepo = AlarmRepository(
                dao = roomDb.alarmDao(),
                groupDao = roomDb.alarmGroupDao(),
                database = roomDb
            )
            every { calculator.calculate(any<Alarm>()) } returns triggerTime
            every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

            val mockOwner = mockk<ScheduleAuthorityOwner>(relaxed = true)
            coEvery {
                mockOwner.executeOperation(eq(963L), eq("SET"), eq(triggerTime), any(), any())
            } returns OwnerResult.OsAccepted(1L, "act-sched-963")

            val ownerRoute = mockk<ProductionSchedulingRouteAuthority>()
            every { ownerRoute.isOwnerRouteActive() } returns true

            val snapshotReader = com.sysadmindoc.alarmclock.directboot.DirectBootNormalSnapshotReader(roomDb) { true }
            val coordinator = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjectionCoordinator(snapshotReader)
            val projector = com.sysadmindoc.alarmclock.directboot.DirectBootNormalProjector(
                context = context,
                database = roomDb,
                coordinator = coordinator
            )

            val localScheduler = AlarmScheduler(
                context = context,
                repository = realRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = holidayRepository,
                alarmIncidentRepository = incidentRepository,
                weatherRepository = weatherRepository,
                scheduleAuthorityOwnerProvider = { mockOwner },
                routeAuthority = ownerRoute,
                directBootNormalProjectorProvider = javax.inject.Provider { projector }
            )

            // Part 1: Production DEFAULT_DENY (v2RegistrationCapableProvider defaults to false)
            var reconcilerInvocations = 0
            val events = mutableListOf<String>()
            localScheduler.testDirectBootReconcilerHook = { ctx ->
                reconcilerInvocations++
                val queueIdsDuringReconcile = DirectBootAlarmCache.readQueue(ctx, now = now).map { it.alarmId }
                events.add("RECONCILER:$queueIdsDuringReconcile")
                com.sysadmindoc.alarmclock.directboot.ReconcilerResult.PlanningHalted(
                    com.sysadmindoc.alarmclock.directboot.DirectBootRegistrationPlan.NoOp("test")
                )
            }

            localScheduler.schedule(alarm, requestWidgetUpdate = false)
            assertEquals("DEFAULT_DENY must NOT invoke reconciler", 0, reconcilerInvocations)
            assertEquals(null, localScheduler.lastDirectBootReconcileResult)

            // Part 2: Isolated opt-in capability proves Projector -> Reconciler ordering
            localScheduler.v2RegistrationCapableProvider = { true }
            localScheduler.schedule(alarm, requestWidgetUpdate = false)
            assertEquals(1, reconcilerInvocations)
            assertEquals(listOf("RECONCILER:[963]"), events)
            assertTrue(localScheduler.lastDirectBootReconcileResult is com.sysadmindoc.alarmclock.directboot.ReconcilerResult.PlanningHalted)
        } finally {
            DirectBootAlarmCache.clear(context)
            DirectBootAlarmCache.clearQueue(context)
            dePrefs.edit().clear().commit()
            roomDb.close()
        }
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
