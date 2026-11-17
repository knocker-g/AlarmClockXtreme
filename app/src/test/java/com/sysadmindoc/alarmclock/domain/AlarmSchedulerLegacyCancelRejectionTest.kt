package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Operation
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmSnapshot
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.service.AlarmGeneration
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.service.SmartAlarmService
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import com.sysadmindoc.alarmclock.worker.FireWatchdogWorker
import com.sysadmindoc.alarmclock.worker.HueSunriseWorker
import com.sysadmindoc.alarmclock.worker.WakeConfirmOccurrenceStore
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
import kotlinx.coroutines.test.runTest
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
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * KNO-75 P0 Legacy Cancellation Rejection Regression Suite.
 *
 * Verifies that under the active LEGACY_ONLY route, stale cancellation rejections
 * (expectedGeneration / targetGen mismatch) and OS cancellation failures MUST NOT
 * fall through into DirectBoot cache/OS fallback removal or follow-up worker cleanup.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], application = Application::class)
class AlarmSchedulerLegacyCancelRejectionTest {

    private lateinit var context: Context
    private lateinit var repository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var workManager: WorkManager
    private lateinit var realAlarmManager: AlarmManager

    @Before
    fun setUp() {
        val baseContext: Context = ApplicationProvider.getApplicationContext()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        realAlarmManager = baseContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        context = wrapWithAlarmManager(baseContext, realAlarmManager)

        DirectBootAlarmCache.clear(context)
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()

        repository = mockk(relaxed = true)
        calculator = mockk()
        preferencesManager = mockk()
        holidayRepository = mockk()
        incidentRepository = mockk(relaxed = true)
        weatherRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk<Operation>(relaxed = true)
        every { workManager.cancelAllWorkByTag(any()) } returns mockk<Operation>(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        mockkObject(WakeConfirmOccurrenceStore)
        every { WakeConfirmOccurrenceStore.cancelAllForAlarm(any(), any()) } returns true

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { holidayRepository.isHoliday(any()) } returns false
        every { weatherRepository.getCachedWeather() } returns null
    }

    @After
    fun tearDown() {
        DirectBootAlarmCache.clear(context)
        unmockkAll()
    }

    private fun wrapWithAlarmManager(base: Context, alarmManager: AlarmManager): Context {
        val baseDe = base.createDeviceProtectedStorageContext()
        val deWrapper = object : ContextWrapper(baseDe) {
            override fun isDeviceProtectedStorage(): Boolean = true
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE) return alarmManager
                return super.getSystemService(name)
            }
        }
        return object : ContextWrapper(base) {
            override fun isDeviceProtectedStorage(): Boolean = false
            override fun createDeviceProtectedStorageContext(): Context = deWrapper
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE) return alarmManager
                return super.getSystemService(name)
            }
        }
    }

    private fun newLegacyScheduler(
        ownerProvider: javax.inject.Provider<ScheduleAuthorityOwner>? = null,
        routeAuthority: ProductionSchedulingRouteAuthority = ProductionSchedulingRouteAuthority()
    ): AlarmScheduler = AlarmScheduler(
        context = context,
        repository = repository,
        calculator = calculator,
        preferencesManager = preferencesManager,
        holidayRepository = holidayRepository,
        alarmIncidentRepository = incidentRepository,
        weatherRepository = weatherRepository,
        scheduleAuthorityOwnerProvider = ownerProvider,
        routeAuthority = routeAuthority
    )

    private fun alarm(id: Long, generation: Int, triggerTime: Long): Alarm = Alarm(
        id = id,
        hour = 7,
        minute = 30,
        label = "Morning",
        isEnabled = true,
        repeatDays = setOf(DayOfWeek.MONDAY),
        nextTriggerTime = triggerTime,
        generation = generation
    )

    private fun armAllStateForB(alarmId: Long, generation: Int, triggerTime: Long) {
        // 1. Arm Main OS AlarmReceiver PendingIntent
        val mainIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, AlarmIncidentEvent.fireIdFor(alarmId, triggerTime))
        }
        val mainPi = PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        realAlarmManager.setAlarmClock(
            AlarmManager.AlarmClockInfo(triggerTime, mainPi),
            mainPi
        )

        // 2. Arm DirectBoot Snapshot + DirectBoot OS PendingIntent
        val now = triggerTime - 600_000L
        val alarmB = alarm(alarmId, generation = generation, triggerTime = triggerTime)
        DirectBootAlarmCache.saveIfEarlier(context, alarmB, triggerTime, now)
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(context, now))

        // 3. Arm SmartAlarmStart ForegroundService PendingIntent
        val smartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
            putExtra(SmartAlarmService.EXTRA_ALARM_ID, alarmId)
            putExtra(SmartAlarmService.EXTRA_TARGET_TIME, triggerTime)
        }
        val smartPi = PendingIntent.getForegroundService(
            context,
            (alarmId + 50000).toInt(),
            smartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        realAlarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerTime - 60_000L,
            smartPi
        )
    }

    private fun lookupMainPendingIntent(alarmId: Long): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun lookupDirectBootPendingIntent(alarmId: Long): PendingIntent? =
        DirectBootAlarmReceiver.pendingIntent(
            context = context,
            snapshot = DirectBootAlarmSnapshot(
                alarmId = alarmId,
                triggerTime = 1L,
                label = "",
                timeLabel = "",
                playDefaultSound = true,
                vibrationEnabled = true,
                updatedAt = 0L
            ),
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    private fun lookupSmartAlarmPendingIntent(alarmId: Long): PendingIntent? {
        val smartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
        }
        return PendingIntent.getForegroundService(
            context,
            (alarmId + 50000).toInt(),
            smartIntent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Test
    fun `red01 generation mismatch under LEGACY_ONLY preserves main OS DE snapshot DE PI and follow-up workers`() = runTest {
        val alarmId = 42L
        val triggerB = System.currentTimeMillis() + 1_200_000L
        val currentB = alarm(alarmId, generation = 2, triggerTime = triggerB)
        coEvery { repository.getById(alarmId) } returns currentB

        armAllStateForB(alarmId, generation = 2, triggerTime = triggerB)
        val shadowAm = Shadows.shadowOf(realAlarmManager)
        val initialScheduledCount = shadowAm.scheduledAlarms.size
        assertEquals(3, initialScheduledCount)

        val scheduler = newLegacyScheduler()
        assertFalse(ProductionSchedulingRouteAuthority().isOwnerRouteActive())

        // Stale cancellation from generation 1 must be rejected without running cleanup
        scheduler.cancelScheduledEntries(
            alarmId = alarmId,
            includeFollowUpWorkers = true,
            expectedGeneration = 1
        )

        assertNotNull("Main OS PendingIntent B MUST NOT be cancelled", lookupMainPendingIntent(alarmId))
        assertEquals("DE Snapshot B MUST NOT be removed", triggerB, DirectBootAlarmCache.read(context)?.triggerTime)
        assertNotNull("DirectBoot OS PendingIntent B MUST NOT be cancelled", lookupDirectBootPendingIntent(alarmId))
        assertNotNull("SmartAlarmStart PendingIntent B MUST NOT be cancelled", lookupSmartAlarmPendingIntent(alarmId))
        assertEquals("No OS AlarmManager entry may be cancelled on stale generation rejection", initialScheduledCount, shadowAm.scheduledAlarms.size)

        verify(exactly = 0) { workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId)) }
        verify(exactly = 0) { workManager.cancelUniqueWork(FireWatchdogWorker.uniqueName(alarmId)) }
        verify(exactly = 0) { workManager.cancelUniqueWork("guardian_$alarmId") }
        verify(exactly = 0) { workManager.cancelUniqueWork("wake_confirm_$alarmId") }
        verify(exactly = 0) { workManager.cancelAllWorkByTag("wake_confirm_$alarmId") }
        verify(exactly = 0) { WakeConfirmOccurrenceStore.cancelAllForAlarm(any(), alarmId) }
    }

    @Test
    fun `red02 targetGen trigger mismatch under LEGACY_ONLY preserves main OS DE snapshot DE PI and follow-up workers`() = runTest {
        val alarmId = 43L
        val now = System.currentTimeMillis()
        val triggerT1 = now + 600_000L
        val triggerT2 = now + 1_800_000L
        val currentB = alarm(alarmId, generation = 2, triggerTime = triggerT2)
        coEvery { repository.getById(alarmId) } returns currentB

        armAllStateForB(alarmId, generation = 2, triggerTime = triggerT2)
        val shadowAm = Shadows.shadowOf(realAlarmManager)
        val initialScheduledCount = shadowAm.scheduledAlarms.size
        assertEquals(3, initialScheduledCount)

        val staleTargetGen = AlarmGeneration(
            alarmId = alarmId,
            scheduledAt = triggerT1,
            fireId = AlarmIncidentEvent.fireIdFor(alarmId, triggerT1)
        )

        val scheduler = newLegacyScheduler()
        scheduler.cancelScheduledEntries(
            alarmId = alarmId,
            includeFollowUpWorkers = true,
            targetGen = staleTargetGen
        )

        assertNotNull("Main OS PendingIntent B MUST NOT be cancelled", lookupMainPendingIntent(alarmId))
        assertEquals("DE Snapshot B MUST NOT be removed", triggerT2, DirectBootAlarmCache.read(context)?.triggerTime)
        assertNotNull("DirectBoot OS PendingIntent B MUST NOT be cancelled", lookupDirectBootPendingIntent(alarmId))
        assertNotNull("SmartAlarmStart PendingIntent B MUST NOT be cancelled", lookupSmartAlarmPendingIntent(alarmId))
        assertEquals("No OS AlarmManager entry may be cancelled on stale targetGen rejection", initialScheduledCount, shadowAm.scheduledAlarms.size)

        verify(exactly = 0) { workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId)) }
        verify(exactly = 0) { workManager.cancelUniqueWork(FireWatchdogWorker.uniqueName(alarmId)) }
        verify(exactly = 0) { workManager.cancelUniqueWork("guardian_$alarmId") }
        verify(exactly = 0) { workManager.cancelUniqueWork("wake_confirm_$alarmId") }
        verify(exactly = 0) { workManager.cancelAllWorkByTag("wake_confirm_$alarmId") }
        verify(exactly = 0) { WakeConfirmOccurrenceStore.cancelAllForAlarm(any(), alarmId) }
    }

    @Test
    fun `control03 valid legacy cancellation cancels main OS DE snapshot DE PI and follow-up workers`() = runTest {
        val alarmId = 44L
        val triggerB = System.currentTimeMillis() + 1_200_000L
        val currentB = alarm(alarmId, generation = 2, triggerTime = triggerB)
        coEvery { repository.getById(alarmId) } returns currentB

        armAllStateForB(alarmId, generation = 2, triggerTime = triggerB)
        val shadowAm = Shadows.shadowOf(realAlarmManager)
        assertEquals(3, shadowAm.scheduledAlarms.size)

        val scheduler = newLegacyScheduler()
        scheduler.cancelScheduledEntries(
            alarmId = alarmId,
            includeFollowUpWorkers = true,
            expectedGeneration = 2
        )

        assertNull("Main OS PendingIntent MUST be cancelled on valid cancel", lookupMainPendingIntent(alarmId))
        assertNull("DE Snapshot MUST be cleared on valid cancel", DirectBootAlarmCache.read(context))
        assertNull("DirectBoot OS PendingIntent MUST be cancelled on valid cancel", lookupDirectBootPendingIntent(alarmId))
        assertNull("SmartAlarmStart PendingIntent MUST be cancelled on valid cancel", lookupSmartAlarmPendingIntent(alarmId))
        assertEquals("All 3 OS AlarmManager entries MUST be cancelled", 0, shadowAm.scheduledAlarms.size)

        verify(exactly = 1) { workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId)) }
        verify(exactly = 1) { workManager.cancelUniqueWork(FireWatchdogWorker.uniqueName(alarmId)) }
        verify(exactly = 1) { workManager.cancelUniqueWork("guardian_$alarmId") }
        verify(exactly = 1) { workManager.cancelUniqueWork("wake_confirm_$alarmId") }
        verify(exactly = 1) { workManager.cancelAllWorkByTag("wake_confirm_$alarmId") }
        verify(exactly = 1) { WakeConfirmOccurrenceStore.cancelAllForAlarm(any(), alarmId) }
    }

    @Test
    fun `control04 OWNER_EXCLUSIVE rejection preserves existing owner route guard and side effects`() = runTest {
        val alarmId = 45L
        val triggerB = System.currentTimeMillis() + 1_200_000L
        val currentB = alarm(alarmId, generation = 2, triggerTime = triggerB)
        coEvery { repository.getById(alarmId) } returns currentB

        val mockOwner = mockk<ScheduleAuthorityOwner>()
        coEvery {
            mockOwner.executeOperation(alarmId, "CANCEL", null, any(), eq(1L))
        } returns OwnerResult.Superseded(2L)

        val ownerRouteAuthority = mockk<ProductionSchedulingRouteAuthority>()
        every { ownerRouteAuthority.isOwnerRouteActive() } returns true

        armAllStateForB(alarmId, generation = 2, triggerTime = triggerB)
        val shadowAm = Shadows.shadowOf(realAlarmManager)
        assertEquals(3, shadowAm.scheduledAlarms.size)

        val scheduler = newLegacyScheduler(
            ownerProvider = { mockOwner },
            routeAuthority = ownerRouteAuthority
        )
        scheduler.cancelScheduledEntries(
            alarmId = alarmId,
            includeFollowUpWorkers = true,
            expectedRevision = 1L,
            expectedGeneration = 2
        )

        coVerify(exactly = 1) { mockOwner.executeOperation(alarmId, "CANCEL", null, any(), eq(1L)) }
        assertNotNull("Main OS PendingIntent MUST remain intact on OWNER Superseded", lookupMainPendingIntent(alarmId))
        assertEquals("DE Snapshot MUST remain intact on OWNER Superseded", triggerB, DirectBootAlarmCache.read(context)?.triggerTime)
        assertNotNull("DirectBoot OS PendingIntent MUST remain intact on OWNER Superseded", lookupDirectBootPendingIntent(alarmId))
        assertEquals(3, shadowAm.scheduledAlarms.size)
        verify(exactly = 0) { workManager.cancelUniqueWork(any()) }
        verify(exactly = 0) { WakeConfirmOccurrenceStore.cancelAllForAlarm(any(), alarmId) }
    }

    @Test
    fun `control05 OS cancel failure under LEGACY_ONLY withholds DE and follow-up cleanup without conflating rejection`() = runTest {
        val alarmId = 46L
        val triggerB = System.currentTimeMillis() + 1_200_000L
        val currentB = alarm(alarmId, generation = 2, triggerTime = triggerB)
        coEvery { repository.getById(alarmId) } returns currentB

        armAllStateForB(alarmId, generation = 2, triggerTime = triggerB)

        val failingAm = mockk<AlarmManager>(relaxed = true)
        every { failingAm.cancel(any<PendingIntent>()) } answers {
            throw RuntimeException("Simulated OS AlarmManager cancel failure")
        }

        val scheduler = newLegacyScheduler().apply {
            testAlarmManager = failingAm
        }

        scheduler.cancelScheduledEntries(
            alarmId = alarmId,
            includeFollowUpWorkers = true,
            expectedGeneration = 2
        )

        verify(exactly = 1) { failingAm.cancel(any<PendingIntent>()) }
        assertEquals("DE Snapshot MUST be preserved when main OS cancel fails (OS state UNKNOWN)", triggerB, DirectBootAlarmCache.read(context)?.triggerTime)
        assertNotNull("DirectBoot OS PendingIntent MUST be preserved when main OS cancel fails", lookupDirectBootPendingIntent(alarmId))
        assertNotNull("SmartAlarmStart PendingIntent MUST be preserved when main OS cancel fails", lookupSmartAlarmPendingIntent(alarmId))
        verify(exactly = 0) { workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId)) }
        verify(exactly = 0) { workManager.cancelUniqueWork(FireWatchdogWorker.uniqueName(alarmId)) }
        verify(exactly = 0) { workManager.cancelUniqueWork("guardian_$alarmId") }
        verify(exactly = 0) { workManager.cancelUniqueWork("wake_confirm_$alarmId") }
        verify(exactly = 0) { workManager.cancelAllWorkByTag("wake_confirm_$alarmId") }
        verify(exactly = 0) { WakeConfirmOccurrenceStore.cancelAllForAlarm(any(), alarmId) }
    }
}
