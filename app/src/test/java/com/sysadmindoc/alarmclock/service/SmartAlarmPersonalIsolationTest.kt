package com.sysadmindoc.alarmclock.service

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.UserManager
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.ActigraphyRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmSnapshot
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.widget.WidgetUpdater
import com.sysadmindoc.alarmclock.worker.FireWatchdogWorker
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
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowPowerManager
import org.robolectric.shadows.ShadowSensor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class SmartAlarmPersonalIsolationTest {

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
    private lateinit var sensorManager: SensorManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ShadowLog.clear()
        ShadowPowerManager.reset()

        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        shadowOf(sensorManager).addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER))

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
        every { workManager.cancelAllWorkByTag(any()) } returns mockk(relaxed = true)
        every { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) } returns mockk(relaxed = true)

        mockkObject(WidgetUpdater)
        every { WidgetUpdater.requestUpdate(any()) } just Runs

        mockkObject(BedtimeZenRuleManager)
        every { BedtimeZenRuleManager.syncRule(any(), any(), any()) } returns mockk(relaxed = true)

        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(repeatMissedAlarms = true)
        every { preferencesManager.getCachedSettings() } returns AppSettings(repeatMissedAlarms = true)
        coEvery { holidayRepository.isHoliday(any()) } returns false

        DirectBootAlarmCache.clear(context)
        context.getSharedPreferences("alarm_scheduler_armed_slots", Context.MODE_PRIVATE).edit().clear().commit()

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
        ShadowPowerManager.reset()
        unmockkAll()
        db.close()
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

    private fun lookupSmartPendingIntent(alarmId: Long): PendingIntent? {
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

    private fun preArmLegacySmartPi(alarmId: Long, triggerTime: Long, smartAtMs: Long) {
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
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, smartAtMs, smartPi)
    }

    private fun buildSmartService(actigraphyRepo: ActigraphyRepository = mockk(relaxed = true)): SmartAlarmService {
        val controller = Robolectric.buildService(SmartAlarmService::class.java)
        val service = controller.get().apply {
            this.alarmRepository = repository
            this.preferencesManager = this@SmartAlarmPersonalIsolationTest.preferencesManager
            this.alarmIncidentRepository = incidentRepository
            this.actigraphyRepository = actigraphyRepo
            this.alarmScheduler = scheduler
        }
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AlarmClockXtreme::SmartAlarmWakeLock").apply {
            setReferenceCounted(false)
        }
        SmartAlarmService::class.java.getDeclaredField("sensorManager").apply { isAccessible = true }.set(service, sensorManager)
        SmartAlarmService::class.java.getDeclaredField("accelerometer").apply { isAccessible = true }
            .set(service, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))
        SmartAlarmService::class.java.getDeclaredField("wakeLock").apply { isAccessible = true }.set(service, wl)
        return service
    }

    private fun isServiceWakeLockHeld(service: SmartAlarmService): Boolean {
        val wl = SmartAlarmService::class.java.getDeclaredField("wakeLock").apply {
            isAccessible = true
        }.get(service) as? PowerManager.WakeLock
        return (wl?.isHeld == true) || (ShadowPowerManager.getLatestWakeLock()?.isHeld == true)
    }

    @Test
    fun `S01 scheduling smartAlarmEnabled alarm registers Main PI without Smart PI and preserves triggerTime`() = runBlocking {
        val alarmId = 9001L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)

        assertTrue("Main alarm MUST arm cleanly", outcome is ScheduleOutcome.Armed)
        assertEquals(triggerTime, (outcome as ScheduleOutcome.Armed).triggerTime)
        assertNotNull("Main PI MUST exist", lookupMainPendingIntent(alarmId))
        assertNull("Smart PI MUST NOT be registered in Personal", lookupSmartPendingIntent(alarmId))

        val shadowAm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertEquals("Only Main alarm entry may be registered in AlarmManager", 1, shadowAm.scheduledAlarms.size)
        val scheduledIntent = shadowOf(shadowAm.scheduledAlarms.single().operation).savedIntent
        assertEquals("com.sysadmindoc.alarmclock.ALARM_FIRE", scheduledIntent.action)
        assertEquals(alarmId, scheduledIntent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L))
        assertEquals(triggerTime, scheduledIntent.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, -1L))

        val savedAlarm = repository.getById(alarmId)
        assertNotNull(savedAlarm)
        assertTrue("Persisted smartAlarmEnabled flag must stay untouched", savedAlarm!!.smartAlarmEnabled)
        assertEquals("Normal triggerTime must remain unchanged", triggerTime, savedAlarm.nextTriggerTime)
        assertNotNull("DirectBoot snapshot must be saved", DirectBootAlarmCache.read(context))
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `S02 rescheduling alarm with pre-armed legacy Smart PI cancels Smart PI and preserves Main PI`() = runBlocking {
        val alarmId = 9002L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 30,
            isEnabled = true,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 30
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        preArmLegacySmartPi(alarmId, triggerTime, triggerTime - 1_800_000L)
        assertNotNull("Precondition: legacy Smart PI exists", lookupSmartPendingIntent(alarmId))

        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)

        assertTrue(outcome is ScheduleOutcome.Armed)
        assertNull("Legacy Smart PI MUST be cancelled on reschedule", lookupSmartPendingIntent(alarmId))
        assertNotNull("Main PI MUST be preserved", lookupMainPendingIntent(alarmId))

        val shadowAm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertEquals(1, shadowAm.scheduledAlarms.size)
        val scheduledIntent = shadowOf(shadowAm.scheduledAlarms.single().operation).savedIntent
        assertEquals("com.sysadmindoc.alarmclock.ALARM_FIRE", scheduledIntent.action)
    }

    @Test
    fun `S03 zero smart delay does not call startForegroundService for SmartAlarmService and keeps Main PI`() = runBlocking {
        val alarmId = 9003L
        val triggerTime = System.currentTimeMillis() + 5 * 60_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 30
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)

        assertTrue(outcome is ScheduleOutcome.Armed)
        assertNull(
            "startForegroundService(SmartAlarmService) MUST NOT be called when smart delay == 0",
            shadowOf(context as Application).nextStartedService
        )
        assertNull("Smart PI MUST remain null", lookupSmartPendingIntent(alarmId))
        assertNotNull("Main PI MUST still be armed", lookupMainPendingIntent(alarmId))
    }

    @Test
    fun `S04 rescheduleAllInBatches retires Smart PI while preserving Main PI DirectBoot and FireWatchdog`() = runBlocking {
        val alarmId = 9004L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 8,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        preArmLegacySmartPi(alarmId, triggerTime, triggerTime - 1_200_000L)
        assertNotNull(lookupSmartPendingIntent(alarmId))

        val batch = scheduler.rescheduleAllInBatches(forceRecalculate = false)

        assertEquals(1, batch.armedCount)
        assertEquals(0, batch.failedCount)
        assertNull("Boot/Restore reconcile MUST retire Smart PI", lookupSmartPendingIntent(alarmId))
        assertNotNull("Boot/Restore reconcile MUST keep Main PI", lookupMainPendingIntent(alarmId))
        val shadowAm = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        assertEquals(1, shadowAm.scheduledAlarms.size)
        assertNotNull(DirectBootAlarmCache.read(context))
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
    }

    @Test
    fun `S05 direct ACTION_START_SMART stops foreground and self immediately with zero WakeLock Sensor Actigraphy or AlarmService`() = runBlocking {
        val alarmId = 9005L
        val targetTime = System.currentTimeMillis() + 1_200_000L
        repository.save(
            Alarm(
                id = alarmId,
                hour = 7,
                minute = 0,
                isEnabled = true,
                nextTriggerTime = targetTime,
                smartAlarmEnabled = true,
                smartAlarmWindowMinutes = 20
            )
        )
        val actigraphyRepo = mockk<ActigraphyRepository>(relaxed = true)
        val service = spyk(buildSmartService(actigraphyRepo))

        val smartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
            putExtra(SmartAlarmService.EXTRA_ALARM_ID, alarmId)
            putExtra(SmartAlarmService.EXTRA_TARGET_TIME, targetTime)
        }

        val startReturn = service.onStartCommand(smartIntent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, startReturn)
        verify(exactly = 1) { service.startForeground(SmartAlarmService.NOTIF_ID_SMART, any()) }
        verify(exactly = 1) { service.stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        val shadowSvc = shadowOf(service)
        assertEquals(SmartAlarmService.NOTIF_ID_SMART, shadowSvc.lastForegroundNotificationId)
        assertTrue("stopForeground must be invoked immediately", shadowSvc.isForegroundStopped)
        assertNull("STOP_FOREGROUND_REMOVE clears active foreground notification", shadowSvc.lastForegroundNotification)
        assertTrue("stopSelfResult(startId) must terminate service", shadowSvc.isStoppedBySelf)

        assertFalse("WakeLock must NOT be held or acquired", isServiceWakeLockHeld(service))
        assertFalse("Sensor listener must NOT be registered", shadowOf(sensorManager).hasListener(service))
        coVerify(exactly = 0) {
            actigraphyRepo.record(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertNull("AlarmService MUST NOT be started", shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `S06 consecutive stale Smart intents startId 1 and 2 terminate cleanly with zero FGS leak or Main alarm impact`() = runBlocking {
        val alarmId = 9006L
        val targetTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = targetTime,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns targetTime
        every { calculator.calculate(any<Alarm>(), any()) } returns targetTime
        scheduler.scheduleAt(alarm, targetTime, requestWidgetUpdate = false)

        val service = buildSmartService()
        val intent1 = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
            putExtra(SmartAlarmService.EXTRA_ALARM_ID, alarmId)
            putExtra(SmartAlarmService.EXTRA_TARGET_TIME, targetTime)
        }
        val intent2 = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
            putExtra(SmartAlarmService.EXTRA_ALARM_ID, alarmId)
            putExtra(SmartAlarmService.EXTRA_TARGET_TIME, targetTime + 60_000L)
        }

        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent1, 0, 1))
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(intent2, 0, 2))

        val shadowSvc = shadowOf(service)
        assertTrue(shadowSvc.isForegroundStopped)
        assertTrue(shadowSvc.isStoppedBySelf)
        assertFalse(isServiceWakeLockHeld(service))
        assertFalse(shadowOf(sensorManager).hasListener(service))
        assertNull(shadowOf(context as Application).nextStartedService)
        assertNotNull("Main PI MUST remain armed after stale Smart intents", lookupMainPendingIntent(alarmId))
        assertEquals(targetTime, repository.getById(alarmId)?.nextTriggerTime)
    }

    @Test
    fun `S07 null and wrong-action intents stop service safely without early fire`() {
        val svcNull = buildSmartService()
        val retNull = svcNull.onStartCommand(null, 0, 1)
        assertEquals(Service.START_NOT_STICKY, retNull)
        assertTrue("Null intent MUST safely terminate service", shadowOf(svcNull).isStoppedBySelf)
        assertFalse(isServiceWakeLockHeld(svcNull))
        assertFalse(shadowOf(sensorManager).hasListener(svcNull))
        assertNull(shadowOf(context as Application).nextStartedService)

        val svcWrong = buildSmartService()
        val wrongIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = "com.sysadmindoc.alarmclock.UNEXPECTED_ACTION"
        }
        val retWrong = svcWrong.onStartCommand(wrongIntent, 0, 2)
        assertEquals(Service.START_NOT_STICKY, retWrong)
        assertTrue("Wrong-action intent MUST safely terminate service", shadowOf(svcWrong).isStoppedBySelf)
        assertFalse(isServiceWakeLockHeld(svcWrong))
        assertFalse(shadowOf(sensorManager).hasListener(svcWrong))
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `S08 cancelling Smart PI leaves Legacy Main PI and DirectBoot PI PendingIntent identities untouched`() = runBlocking {
        val earlierDirectBootId = 9007L
        val earlierTrigger = System.currentTimeMillis() + 1_800_000L
        val earlierAlarm = Alarm(id = earlierDirectBootId, hour = 6, minute = 30, isEnabled = true)

        val alarmId = 9008L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 15
        )
        repository.save(earlierAlarm)
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        preArmLegacySmartPi(alarmId, triggerTime, triggerTime - 900_000L)
        DirectBootAlarmCache.saveIfEarlier(context, earlierAlarm, earlierTrigger, earlierTrigger - 3_600_000L)
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(context, earlierTrigger - 3_600_000L))
        val directBootSnapshot = requireNotNull(DirectBootAlarmCache.read(context))
        val directBootPiBefore = DirectBootAlarmReceiver.pendingIntent(
            context,
            directBootSnapshot,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull(directBootPiBefore)

        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)

        assertTrue(outcome is ScheduleOutcome.Armed)
        assertNull("Smart PI MUST be cancelled", lookupSmartPendingIntent(alarmId))
        val mainPiAfter = lookupMainPendingIntent(alarmId)
        assertNotNull("Legacy Main PI MUST remain intact", mainPiAfter)
        val mainSavedIntent = shadowOf(mainPiAfter).savedIntent
        assertEquals("com.sysadmindoc.alarmclock.ALARM_FIRE", mainSavedIntent.action)
        assertEquals(alarmId, mainSavedIntent.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L))

        val directBootPiAfter = DirectBootAlarmReceiver.pendingIntent(
            context,
            directBootSnapshot,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("DirectBoot PI identity MUST remain intact", directBootPiAfter)
        assertEquals(
            shadowOf(directBootPiBefore).savedIntent.action,
            shadowOf(directBootPiAfter).savedIntent.action
        )
        assertEquals(
            shadowOf(directBootPiBefore).savedIntent.data,
            shadowOf(directBootPiAfter).savedIntent.data
        )
    }

    @Test
    fun `S09 retiring Smart PI does not cancel Guardian Legacy Containment work`() = runBlocking {
        val alarmId = 9009L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime
        preArmLegacySmartPi(alarmId, triggerTime, triggerTime - 1_200_000L)

        val outcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)

        assertTrue(outcome is ScheduleOutcome.Armed)
        verify(exactly = 0) {
            workManager.cancelUniqueWork(OwnerWorkIdentity.legacyUniqueName(alarmId, SupportingWorkType.GUARDIAN))
        }
        verify(exactly = 0) {
            workManager.cancelAllWorkByTag("guardian_$alarmId")
        }
    }

    @Test
    fun `S10 existing callStartAlarmServiceForTesting reflection path continues working for fresh occurrence`() = runBlocking {
        val alarmId = 9010L
        val targetTime = System.currentTimeMillis() + 1_800_000L
        repository.save(
            Alarm(
                id = alarmId,
                hour = 7,
                minute = 0,
                isEnabled = true,
                nextTriggerTime = targetTime,
                smartAlarmEnabled = true,
                smartAlarmWindowMinutes = 20
            )
        )
        val service = buildSmartService()
        SmartAlarmService::class.java.getDeclaredField("alarmId").apply { isAccessible = true }.set(service, alarmId)
        SmartAlarmService::class.java.getDeclaredField("targetTimeMs").apply { isAccessible = true }.set(service, targetTime)

        service.callStartAlarmServiceForTesting()

        val started = shadowOf(context as Application).nextStartedService
        assertNotNull("Existing reflection test seam callStartAlarmServiceForTesting must remain functional", started)
        assertEquals(AlarmService.ACTION_START_ALARM, started.action)
        assertEquals(alarmId, started.getLongExtra(AlarmScheduler.EXTRA_ALARM_ID, -1L))
        assertEquals(targetTime, started.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, -1L))
    }

    @Test
    fun `S11 Smart PI cancel failure does not fake cancel success preserves FireWatchdog and is blocked at Service ingress`() = runBlocking {
        val alarmId = 9011L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime

        preArmLegacySmartPi(alarmId, triggerTime, triggerTime - 1_200_000L)
        val smartPi = lookupSmartPendingIntent(alarmId)
        assertNotNull(smartPi)

        val realAm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val spyAm = spyk(realAm)
        every { spyAm.cancel(smartPi!!) } throws SecurityException("Simulated Smart PI cancel failure")
        scheduler.testAlarmManager = spyAm

        // 1. Schedule still arms Main PI & FireWatchdog despite Smart PI cancel error, and logs failure
        val scheduleOutcome = scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)
        assertTrue("Main alarm MUST still arm even if Smart PI cancel fails", scheduleOutcome is ScheduleOutcome.Armed)
        verify(exactly = 1) {
            workManager.enqueueUniqueWork(FireWatchdogWorker.uniqueName(alarmId), ExistingWorkPolicy.REPLACE, any<OneTimeWorkRequest>())
        }
        assertTrue(
            "Smart PI cancel failure MUST be logged and not hidden",
            ShadowLog.getLogsForTag("AlarmScheduler").any {
                it.type == android.util.Log.ERROR && it.msg.contains(alarmId.toString())
            }
        )

        // 2. Explicit cancellation MUST NOT falsely report true when Smart PI cancel threw
        val cancelSucceeded = scheduler.cancelScheduledEntriesWithResult(alarmId)
        assertFalse("cancelScheduledEntriesWithResult MUST NOT mask Smart PI cancel failure as true", cancelSucceeded)

        // 3. Double defense: uncancelled Smart PI firing into SmartAlarmService fails closed at ingress
        val service = buildSmartService()
        val firedSmartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
            putExtra(SmartAlarmService.EXTRA_ALARM_ID, alarmId)
            putExtra(SmartAlarmService.EXTRA_TARGET_TIME, triggerTime)
        }
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(firedSmartIntent, 0, 1))
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(isServiceWakeLockHeld(service))
        assertFalse(shadowOf(sensorManager).hasListener(service))
        assertNull(shadowOf(context as Application).nextStartedService)
    }

    @Test
    fun `S12 startForeground failure is diagnosable does not alter Main alarm and prevents monitoring or early fire`() = runBlocking {
        val alarmId = 9012L
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = triggerTime,
            smartAlarmEnabled = true,
            smartAlarmWindowMinutes = 20
        )
        repository.save(alarm)
        every { calculator.calculate(any<Alarm>()) } returns triggerTime
        every { calculator.calculate(any<Alarm>(), any()) } returns triggerTime
        scheduler.scheduleAt(alarm, triggerTime, requestWidgetUpdate = false)

        val actigraphyRepo = mockk<ActigraphyRepository>(relaxed = true)
        val realService = buildSmartService(actigraphyRepo)
        val spyService = spyk(realService)
        every { spyService.startForeground(any(), any()) } throws IllegalStateException("Simulated FGS start failure")

        val smartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
            putExtra(SmartAlarmService.EXTRA_ALARM_ID, alarmId)
            putExtra(SmartAlarmService.EXTRA_TARGET_TIME, triggerTime)
        }

        val ret = spyService.onStartCommand(smartIntent, 0, 1)

        assertEquals(Service.START_NOT_STICKY, ret)
        assertTrue("Service MUST terminate safely even when startForeground throws", shadowOf(realService).isStoppedBySelf)
        assertFalse("WakeLock MUST NOT be held", isServiceWakeLockHeld(realService))
        assertFalse("Sensor listener MUST NOT be registered", shadowOf(sensorManager).hasListener(spyService))
        assertNull("Early fire AlarmService MUST NOT start", shadowOf(context as Application).nextStartedService)
        coVerify(exactly = 0) {
            actigraphyRepo.record(any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        assertNotNull("Main PI MUST remain unchanged", lookupMainPendingIntent(alarmId))
        assertEquals(triggerTime, repository.getById(alarmId)?.nextTriggerTime)

        val incidents = db.alarmIncidentEventDao().getRecent(50).filter { it.alarmId == alarmId }
        val failedIncidents = incidents.filter {
            it.type == AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE &&
                it.status == AlarmIncidentEvent.STATUS_FAILED
        }
        assertTrue(
            "startForeground failure MUST be diagnosably recorded/logged",
            failedIncidents.isNotEmpty() || ShadowLog.getLogsForTag("SmartAlarmService").any {
                it.type == android.util.Log.ERROR
            }
        )
    }
}
