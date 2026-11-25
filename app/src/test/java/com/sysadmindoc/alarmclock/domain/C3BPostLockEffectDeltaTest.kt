package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.integration.hue.HueBridgeClient
import com.sysadmindoc.alarmclock.integration.hue.HueTrustStore
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.service.BedtimeZenRuleManager
import com.sysadmindoc.alarmclock.service.SmartAlarmService
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import com.sysadmindoc.alarmclock.worker.FireWatchdogWorker
import com.sysadmindoc.alarmclock.worker.HueSunriseWorker
import com.sysadmindoc.alarmclock.worker.WakeConfirmOccurrenceStore
import com.sysadmindoc.alarmclock.worker.WakeConfirmWorker
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
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
import java.time.DayOfWeek

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class C3BPostLockEffectDeltaTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var repository: AlarmRepository
    private lateinit var calculator: NextAlarmCalculator
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var holidayRepository: HolidayRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var weatherRepository: WeatherRepository
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: AlarmScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        runBlocking {
            db.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 10L, bootstrapState = "INITIALIZED")
            )
        }

        repository = AlarmRepository(
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao(),
            database = db
        )
        calculator = mockk()
        preferencesManager = mockk(relaxed = true)
        holidayRepository = mockk()
        incidentRepository = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        weatherRepository = mockk(relaxed = true)
        workManager = mockk(relaxed = true)

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any<Context>()) } returns workManager
        every { workManager.cancelUniqueWork(any()) } returns mockk(relaxed = true)
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(repeatMissedAlarms = true)
        every { preferencesManager.getCachedSettings() } returns AppSettings(repeatMissedAlarms = true)
        coEvery { holidayRepository.isHoliday(any()) } returns false

        DirectBootAlarmCache.clear(context)
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        context.getSharedPreferences("alarm_scheduler_armed_slots", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(WakeConfirmOccurrenceStore.DE_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences(WakeConfirmOccurrenceStore.CE_PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()

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
        DirectBootAlarmCache.clear(context)
        context.getSharedPreferences("alarm_scheduler_armed_slots", Context.MODE_PRIVATE).edit().clear().commit()
        unmockkAll()
        db.close()
    }

    // --- Area A: Post-L2 freshness re-verification ---

    @Test
    fun `C3B-A1 scheduleAt rejects stale operation when restoreEpoch advances before OS registration barrier`() = runBlocking {
        val alarmId = 8101L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, generation = 1, revision = 1)
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val spyAm = spyk(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        scheduler.testAlarmManager = spyAm
        scheduler.testOnPreOsRegistrationBarrier = { _, _ ->
            db.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 11L, bootstrapState = "INITIALIZED")
            )
        }

        val outcome = scheduler.schedule(alarm, requestWidgetUpdate = false)

        assertTrue(
            "Area A: schedule MUST reject when restoreEpoch advanced before OS registration, was $outcome",
            outcome is ScheduleOutcome.RegistrationFailed
        )
        verify(exactly = 0) { spyAm.setAlarmClock(any(), any()) }
        verify(exactly = 0) { spyAm.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun `C3B-A2 scheduleAt rejects when alarm is disabled or revision advances before OS registration barrier`() = runBlocking {
        val alarmId = 8102L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, generation = 1, revision = 1)
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val spyAm = spyk(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        scheduler.testAlarmManager = spyAm
        scheduler.testOnPreOsRegistrationBarrier = { id, _ ->
            repository.save(alarm.copy(id = id, isEnabled = false, revision = 2))
        }

        val outcome = scheduler.schedule(alarm, requestWidgetUpdate = false)

        assertFalse("Area A: schedule MUST NOT arm when DB became disabled/edited mid-flight, was $outcome", outcome.isArmed)
        verify(exactly = 0) { spyAm.setAlarmClock(any(), any()) }
        verify(exactly = 0) { spyAm.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    // --- Area C: Boot Truth Correction (no fingerprint/PI-only OS skip) ---

    @Test
    fun `C3B-C2 consecutive rescheduleAllInBatches in same boot re-registers OS truth and preserves DirectBoot`() = runBlocking {
        val alarmId = 8201L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 1,
            revision = 1,
            hueEnabled = true,
            huePreWakeMinutes = 15,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val spyAm = spyk(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        scheduler.testAlarmManager = spyAm

        // 1st pass (e.g. BootRescheduleWorker)
        val firstBatch = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(1, firstBatch.armedCount)
        assertEquals(0, firstBatch.failedCount)
        assertNotNull(DirectBootAlarmCache.read(context))
        verify(exactly = 1) { spyAm.setAlarmClock(any(), any()) }
        verify(exactly = 0) { workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), any(), any<OneTimeWorkRequest>()) }
        verify(exactly = 1) { workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId)) }
        verify(exactly = 1) { workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }

        // 2nd pass in same boot MUST NOT trust fingerprint/PI to skip OS registration
        val secondBatch = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(1, secondBatch.armedCount)
        assertEquals(0, secondBatch.failedCount)
        assertNotNull("DirectBoot snapshot MUST remain preserved after boot reconcile", DirectBootAlarmCache.read(context))
        verify(exactly = 2) { spyAm.setAlarmClock(any(), any()) }
        verify(exactly = 0) { workManager.enqueueUniqueWork(HueSunriseWorker.uniqueName(alarmId), any(), any<OneTimeWorkRequest>()) }
        verify(exactly = 2) { workManager.cancelUniqueWork(HueSunriseWorker.uniqueName(alarmId)) }
        verify(exactly = 2) { workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `C3B-R1 existing PendingIntent plus matching fingerprint without OS AlarmManager entry MUST NOT skip OS re-registration`() = runBlocking {
        val alarmId = 8202L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 1,
            revision = 1,
            hueEnabled = true,
            huePreWakeMinutes = 15,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        // Simulate stale PI + stale boot fingerprint surviving without any actual AlarmManager registration
        PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSharedPreferences("alarm_scheduler_armed_slots", Context.MODE_PRIVATE)
            .edit()
            .putString("armed_$alarmId", "$triggerTime|1|1|10|true|true|20|true|15|0")
            .commit()

        val realAm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        assertTrue("Precondition: OS AlarmManager has no scheduled alarms", shadowOf(realAm).scheduledAlarms.isEmpty())

        val spyAm = spyk(realAm)
        scheduler.testAlarmManager = spyAm

        val batch = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(1, batch.armedCount)
        assertEquals(0, batch.failedCount)
        verify(exactly = 1) { spyAm.setAlarmClock(any(), any()) }
        assertEquals(
            "OS AlarmManager MUST hold ONLY the Main alarm entry (SmartAlarm retired in Personal)",
            1,
            shadowOf(realAm).scheduledAlarms.size
        )
        val scheduledEntry = shadowOf(realAm).scheduledAlarms.single()
        val mainShadowPi = shadowOf(scheduledEntry.operation)
        assertTrue("Scheduled OS entry MUST be Broadcast Main PI", mainShadowPi.isBroadcast)
        assertEquals("com.sysadmindoc.alarmclock.ALARM_FIRE", mainShadowPi.savedIntent.action)
        assertEquals(alarmId, mainShadowPi.savedIntent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L))
        assertEquals(triggerTime, mainShadowPi.savedIntent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, -1L))
        assertNotNull(
            "Main PendingIntent identity MUST be preserved",
            PendingIntent.getBroadcast(
                context,
                alarmId.toInt(),
                Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        assertNull(
            "SmartAlarmStart PendingIntent MUST NOT exist in Personal",
            PendingIntent.getForegroundService(
                context,
                (alarmId + 50000).toInt(),
                Intent(context, SmartAlarmService::class.java).apply { action = SmartAlarmService.ACTION_START_SMART },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
        val dbSnapshot = DirectBootAlarmCache.read(context)
        assertNotNull(dbSnapshot)
        assertEquals(alarmId, dbSnapshot!!.alarmId)
        assertEquals(triggerTime, dbSnapshot.triggerTime)
    }

    @Test
    fun `C3B-R2 missing or disabled DB alarm MUST NOT falsely report Armed or register OS`() = runBlocking {
        val disabledId = 8203L
        val deletedId = 8204L
        val triggerTime = System.currentTimeMillis() + 3_600_000L

        // Subcase A: disabled DB alarm even with stale PI + fingerprint
        repository.save(Alarm(id = disabledId, hour = 6, minute = 0, isEnabled = false, nextTriggerTime = triggerTime))
        PendingIntent.getBroadcast(
            context,
            disabledId.toInt(),
            Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSharedPreferences("alarm_scheduler_armed_slots", Context.MODE_PRIVATE)
            .edit()
            .putString("armed_$disabledId", "$triggerTime|1|1|10|true|false|30|false|30|0")
            .commit()

        val spyAm = spyk(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        scheduler.testAlarmManager = spyAm

        val disabledBatch = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(0, disabledBatch.armedCount)
        verify(exactly = 0) { spyAm.setAlarmClock(any(), any()) }

        // Subcase B: DB alarm disappears mid-flight before OS registration barrier
        repository.save(Alarm(id = deletedId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime))
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime
        scheduler.testOnPreOsRegistrationBarrier = { id, _ ->
            if (id == deletedId) {
                db.alarmDao().deleteById(id)
            }
        }

        val deletedBatch = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals("Deleted DB alarm MUST NOT count as armed", 0, deletedBatch.armedCount)
        assertFalse(
            "Deleted DB alarm outcome MUST NOT be Armed",
            deletedBatch.outcomes[deletedId]?.isArmed == true
        )
        verify(exactly = 0) { spyAm.setAlarmClock(any(), any()) }
    }

    @Test
    fun `C3B-R3 restoreEpoch advance or generation bump prevents stale OS registration and follow-up arms fresh generation`() = runBlocking {
        val alarmId = 8205L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val gen1Alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime, generation = 1, revision = 1)
        repository.save(gen1Alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val spyAm = spyk(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        scheduler.testAlarmManager = spyAm
        scheduler.testOnPreOsRegistrationBarrier = { id, _ ->
            db.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 11L, bootstrapState = "INITIALIZED")
            )
            repository.save(gen1Alarm.copy(id = id, minute = 30, isEnabled = true))
        }

        val staleBatch = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(0, staleBatch.armedCount)
        assertEquals(1, staleBatch.failedCount)
        verify(exactly = 0) { spyAm.setAlarmClock(any(), any()) }

        // Next reconcile pass picks up generation 2 + epoch 11 cleanly
        scheduler.testOnPreOsRegistrationBarrier = null
        val freshBatch = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(1, freshBatch.armedCount)
        assertEquals(0, freshBatch.failedCount)
        assertEquals(2, repository.getById(alarmId)?.generation)
        verify(exactly = 1) { spyAm.setAlarmClock(any(), any()) }
    }

    @Test
    fun `C3B-R4 OS registration failure MUST NOT return Armed even when PI plus fingerprint existed`() = runBlocking {
        val alarmId = 8206L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            generation = 1,
            revision = 1
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        context.getSharedPreferences("alarm_scheduler_armed_slots", Context.MODE_PRIVATE)
            .edit()
            .putString("armed_$alarmId", "$triggerTime|1|1|10|true|false|30|false|30|0")
            .commit()

        val failingAm = spyk(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        every { failingAm.setAlarmClock(any(), any()) } throws IllegalStateException("Simulated AlarmManager quota failure")
        scheduler.testAlarmManager = failingAm

        val batch = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals("OS registration failure MUST NOT return Armed", 0, batch.armedCount)
        assertEquals(1, batch.failedCount)
        assertTrue(batch.outcomes[alarmId] is ScheduleOutcome.RegistrationFailed)
    }

    @Test
    fun `C3B-R5 normal consecutive boot reconcile satisfies registration BatchResult contract`() = runBlocking {
        val enabledId = 8207L
        val disabledId = 8208L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        repository.save(Alarm(id = enabledId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = triggerTime))
        repository.save(Alarm(id = disabledId, hour = 8, minute = 0, isEnabled = false, nextTriggerTime = 0L))
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val realAm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val spyAm = spyk(realAm)
        scheduler.testAlarmManager = spyAm

        val pass1 = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(1, pass1.totalProcessed)
        assertEquals(1, pass1.armedCount)
        assertEquals(0, pass1.failedCount)
        assertEquals(0, pass1.blockedCount)
        assertTrue(pass1.isFullSuccess)
        assertTrue(pass1.outcomes[enabledId] is ScheduleOutcome.Armed)
        assertEquals(1, shadowOf(realAm).scheduledAlarms.size)
        assertEquals(enabledId, DirectBootAlarmCache.read(context)?.alarmId)

        val pass2 = scheduler.rescheduleAllInBatches(forceRecalculate = false)
        assertEquals(1, pass2.totalProcessed)
        assertEquals(1, pass2.armedCount)
        assertEquals(0, pass2.failedCount)
        assertEquals(0, pass2.blockedCount)
        assertTrue(pass2.isFullSuccess)
        assertTrue(pass2.outcomes[enabledId] is ScheduleOutcome.Armed)
        assertEquals(1, shadowOf(realAm).scheduledAlarms.size)
        assertEquals(enabledId, DirectBootAlarmCache.read(context)?.alarmId)
        verify(exactly = 2) { spyAm.setAlarmClock(any(), any()) }
    }

    // --- Area D: Delayed worker / service side effects ---

    @Test
    fun `C3B-D1 HueSunriseWorker ignores disabled or rescheduled or superseded generation without probing bridge`() = runBlocking {
        val alarmId = 8301L
        val oldTrigger = System.currentTimeMillis() + 3_600_000L
        val newerTrigger = oldTrigger + 1_800_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = false,
            hueEnabled = true,
            huePreWakeMinutes = 20,
            nextTriggerTime = newerTrigger,
            generation = 2
        )
        repository.save(alarm)

        val bridgeClient = mockk<HueBridgeClient>(relaxed = true)
        val trustStore = mockk<HueTrustStore>(relaxed = true)
        val wp = mockk<WorkerParameters>(relaxed = true)
        every { wp.inputData } returns workDataOf(
            HueSunriseWorker.KEY_ALARM_ID to alarmId,
            HueSunriseWorker.KEY_RAMP_START to (oldTrigger - 20 * 60_000L),
            HueSunriseWorker.KEY_RAMP_END to oldTrigger
        )

        val result = HueSunriseWorker(
            context = context,
            workerParams = wp,
            repository = repository,
            preferencesManager = preferencesManager,
            hueBridgeClient = bridgeClient,
            hueTrustStore = trustStore
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { bridgeClient.probeV2(any(), any(), any(), any()) }
    }

    @Test
    fun `C3B-D2 SmartAlarmService rejects startAlarmService when DB alarm is disabled or rescheduled to new trigger`() = runBlocking {
        val alarmId = 8302L
        val staleTarget = System.currentTimeMillis() + 3_600_000L
        val newerTarget = staleTarget + 1_800_000L
        repository.save(
            Alarm(
                id = alarmId,
                hour = 7,
                minute = 30,
                isEnabled = true,
                smartAlarmEnabled = true,
                smartAlarmWindowMinutes = 20,
                nextTriggerTime = newerTarget,
                generation = 2
            )
        )

        val service = Robolectric.buildService(SmartAlarmService::class.java).get().apply {
            this.alarmRepository = repository
            this.preferencesManager = this@C3BPostLockEffectDeltaTest.preferencesManager
            this.alarmIncidentRepository = incidentRepository
            this.actigraphyRepository = mockk(relaxed = true)
            this.alarmScheduler = scheduler
        }
        SmartAlarmService::class.java.getDeclaredField("alarmId").apply { isAccessible = true }.set(service, alarmId)
        SmartAlarmService::class.java.getDeclaredField("targetTimeMs").apply { isAccessible = true }.set(service, staleTarget)

        service.callStartAlarmServiceForTesting()

        assertNull(
            "SmartAlarmService MUST NOT fire AlarmService for superseded triggerTime",
            shadowOf(context as Application).nextStartedService
        )
    }

    @Test
    fun `C3B-D3 FireWatchdogWorker rejects stale generation scheduled via AlarmScheduler`() = runBlocking {
        val alarmId = 8303L
        val scheduledAt = System.currentTimeMillis() - 120_000L
        val alarmGen1 = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = scheduledAt,
            generation = 1
        )
        repository.save(alarmGen1)
        every { calculator.calculate(any<Alarm>()) } returns scheduledAt
        every { calculator.calculate(any<Alarm>(), any()) } returns scheduledAt

        val watchdogSlot = mutableListOf<OneTimeWorkRequest>()
        every {
            workManager.enqueueUniqueWork(
                FireWatchdogWorker.uniqueName(alarmId),
                ExistingWorkPolicy.REPLACE,
                capture(watchdogSlot)
            )
        } returns mockk(relaxed = true)

        scheduler.scheduleAt(alarmGen1, scheduledAt, requestWidgetUpdate = false)
        val capturedInput = watchdogSlot.last().workSpec.input

        // User edits alarm schedule before watchdog fires -> generation bumps to 2
        repository.save(alarmGen1.copy(minute = 15, isEnabled = true))
        assertEquals(2, repository.getById(alarmId)?.generation)

        val wp = mockk<WorkerParameters>(relaxed = true)
        every { wp.inputData } returns capturedInput

        val result = FireWatchdogWorker(
            context = context,
            workerParams = wp,
            alarmRepository = repository,
            preferencesManager = preferencesManager,
            alarmIncidentRepository = incidentRepository
        ).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(
            "FireWatchdogWorker MUST NOT refire superseded generation",
            shadowOf(context as Application).nextStartedService
        )
    }

    @Test
    fun `C3B-D4 WakeConfirmWorker aborts before consumeRefireStep when repeating alarm is disabled during wait`() = runBlocking {
        val alarmId = 8304L
        val scheduledAt = 1_700_000_000_000L
        val enabledRepeating = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            repeatDays = setOf(DayOfWeek.MONDAY),
            wakeConfirmEnabled = true,
            wakeConfirmDelayMinutes = 1
        )

        var fetchCount = 0
        val mockRepo = mockk<AlarmRepository>(relaxed = true)
        every { mockRepo.database } returns db
        coEvery { mockRepo.getById(alarmId) } answers {
            fetchCount++
            if (fetchCount == 1) enabledRepeating else enabledRepeating.copy(isEnabled = false)
        }

        val commitRes = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = "fire-$alarmId",
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis(),
            delayMinutes = 1L
        )
        assertTrue(commitRes is WakeConfirmOccurrenceStore.CommitResult.Committed)
        val token = (commitRes as WakeConfirmOccurrenceStore.CommitResult.Committed).record.stepToken

        val wp = mockk<WorkerParameters>(relaxed = true)
        every { wp.inputData } returns workDataOf(
            WakeConfirmWorker.KEY_ALARM_ID to alarmId,
            WakeConfirmWorker.KEY_SCHEDULED_AT to scheduledAt,
            WakeConfirmWorker.KEY_ALARM_FIRE_ID to "fire-$alarmId",
            WakeConfirmWorker.KEY_REFIRE_COUNT to 0,
            WakeConfirmWorker.KEY_STEP_TOKEN to token
        )

        val worker = WakeConfirmWorker(
            context = context,
            workerParams = wp,
            alarmRepository = mockRepo,
            preferencesManager = preferencesManager,
            alarmIncidentRepository = incidentRepository
        ).apply {
            confirmWaitMsOverride = 60L
            pollIntervalMsOverride = 20L
        }

        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertNull(
            "WakeConfirmWorker MUST NOT refire after repeating alarm was disabled during wait",
            shadowOf(context as Application).nextStartedService
        )
    }
}
