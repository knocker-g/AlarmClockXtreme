package com.sysadmindoc.alarmclock.receiver

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.time.DayOfWeek
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.service.WebhookService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class)
class SkipTransitionTest {

    @get:org.junit.Rule
    val tempFolder = org.junit.rules.TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var eventRepo: AlarmEventRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var mockEntryPoint: AlarmClockApp.AppEntryPoint

    private val calculator: NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)
    private val webhookService: WebhookService = mockk(relaxed = true)

    private val zoneId = ZoneId.systemDefault()
    private val baseDate = LocalDate.of(2026, 11, 2)
    private val fixedNowMs = baseDate
        .atTime(LocalTime.of(6, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val testClock: java.time.Clock = java.time.Clock.fixed(
        java.time.Instant.ofEpochMilli(fixedNowMs),
        zoneId
    )

    private val monday0700Ms = baseDate
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val tuesday0700Ms = baseDate.plusDays(1)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    private val mondayNextWeek0700Ms = baseDate.plusWeeks(1)
        .atTime(LocalTime.of(7, 0))
        .atZone(zoneId)
        .toInstant()
        .toEpochMilli()

    @Before
    fun setUp() {
        runBlocking {
            context = ApplicationProvider.getApplicationContext()
            org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
            db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
                .addCallback(AlarmDatabase.DATABASE_CALLBACK)
                .allowMainThreadQueries()
                .build()

            alarmRepo = AlarmRepository(
                database = db,
                dao = db.alarmDao(),
                groupDao = db.alarmGroupDao()
            )

            incidentRepo = AlarmIncidentRepository(
                dao = db.alarmIncidentEventDao(),
                context = context
            )

            eventRepo = AlarmEventRepository(
                dao = db.alarmEventDao()
            )

            every { preferencesManager.getCachedSettings() } returns AppSettings(activeAlarmId = null)
            coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(activeAlarmId = null)

            scheduler = AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = incidentRepo,
                weatherRepository = mockk(relaxed = true)
            )

            mockEntryPoint = mockk<AlarmClockApp.AppEntryPoint>(relaxed = true)
            every { mockEntryPoint.alarmRepository() } returns alarmRepo
            every { mockEntryPoint.alarmDatabase() } returns db
            every { mockEntryPoint.alarmScheduler() } returns scheduler
            every { mockEntryPoint.nextAlarmCalculator() } returns calculator
            every { mockEntryPoint.alarmEventRepository() } returns eventRepo
            every { mockEntryPoint.webhookService() } returns webhookService
            every { mockEntryPoint.preferencesManager() } returns preferencesManager
        }
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun insertAuthorityForAlarm(alarmId: Long, triggerMillis: Long) {
        runBlocking {
            if (db.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId) == null) {
                val authority = ScheduleAuthority(
                    alarmId = alarmId,
                    instanceToken = "auth-token-$alarmId",
                    definitionRevision = 1L,
                    scheduleRevision = 1L,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = triggerMillis,
                    reconcileState = "SETTLED",
                    restoreEpoch = 100L,
                    actionKey = null
                )
                db.scheduleAuthorityDao().insertAuthority(authority)
            }
            if (db.issuedOccurrenceDao().getSlot(alarmId) == null) {
                db.issuedOccurrenceDao().insertSlotInitial(com.sysadmindoc.alarmclock.data.local.entity.IssueSlot(alarmId = alarmId, generationId = 1L, currentIssueToken = null))
            }
        }
    }

    @Test
    fun `SkipNextReceiver accepts valid expectedScheduledAt and advances repeating alarm`() = runBlocking {
        val currentTrigger = monday0700Ms
        val nextTrigger = tuesday0700Ms

        every { calculator.calculate(any(), any()) } returns nextTrigger
        every { calculator.calculate(any()) } returns nextTrigger

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
                repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                nextTriggerTime = currentTrigger
            )
        )
        insertAuthorityForAlarm(alarmId, currentTrigger)

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, currentTrigger)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver.onReceive(context, intent)

        val updated = alarmRepo.getById(alarmId)
        assertNotNull("Updated alarm must exist in DB", updated)
        assertNotEquals("Next trigger MUST advance after skip", currentTrigger, updated?.nextTriggerTime)
        assertEquals(nextTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects stale expectedScheduledAt without mutating DB or recording event`() = runBlocking {
        val currentTrigger = monday0700Ms
        val staleExpectedTrigger = currentTrigger - 3600_000L

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = currentTrigger
            )
        )
        insertAuthorityForAlarm(alarmId, currentTrigger)

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, staleExpectedTrigger)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver.onReceive(context, intent)

        val updated = alarmRepo.getById(alarmId)
        assertEquals("DB trigger MUST remain unchanged when occurrence identity is missing", currentTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects skip when EXTRA_SCHEDULED_AT is missing`() = runBlocking {
        val currentTrigger = monday0700Ms

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = currentTrigger
            )
        )
        insertAuthorityForAlarm(alarmId, currentTrigger)

        // Missing EXTRA_SCHEDULED_AT
        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver.onReceive(context, intent)

        val updated = alarmRepo.getById(alarmId)
        assertEquals("DB trigger MUST remain unchanged when occurrence identity is missing", currentTrigger, updated?.nextTriggerTime)
    }

    @Test
    fun `SkipNextReceiver rejects duplicate skip on same occurrence`() = runBlocking {
        val currentTrigger = monday0700Ms
        val nextTrigger = mondayNextWeek0700Ms

        every { calculator.calculate(any(), any()) } returns nextTrigger
        every { calculator.calculate(any()) } returns nextTrigger

        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                repeatDays = setOf(DayOfWeek.MONDAY),
                nextTriggerTime = currentTrigger
            )
        )
        insertAuthorityForAlarm(alarmId, currentTrigger)

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, currentTrigger)
        }

        val receiver1 = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver1.onReceive(context, intent)

        val firstUpdated = alarmRepo.getById(alarmId)
        assertEquals("First skip MUST advance trigger to nextTrigger", nextTrigger, firstUpdated?.nextTriggerTime)

        // Duplicate skip targeting original currentTrigger
        val receiver2 = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver2.onReceive(context, intent)

        val secondUpdated = alarmRepo.getById(alarmId)
        assertEquals("Duplicate skip MUST be rejected and DB trigger MUST stay at nextTrigger", nextTrigger, secondUpdated?.nextTriggerTime)
    }

    @Test
    fun `R1 - one-shot skip when cancelSuspending fails persists cancel witness keeps DB disabled and enqueues BootRescheduleWorker`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val triggerTime = monday0700Ms
        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                repeatDays = emptySet(),
                nextTriggerTime = triggerTime
            )
        )
        insertAuthorityForAlarm(alarmId, triggerTime)

        val failingScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { failingScheduler.cancelSuspending(alarmId) } returns false
        every { mockEntryPoint.alarmScheduler() } returns failingScheduler

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }
        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver.onReceive(context, intent)

        val dbAlarm = alarmRepo.getById(alarmId)!!
        assertFalse("Post-CAS DB disabled state MUST be preserved without rollback", dbAlarm.isEnabled)
        assertEquals(0L, dbAlarm.nextTriggerTime)

        val unresolvedCancels = db.durableOperationIntentDao().getUnresolvedCancellationIntents()
            .filter { it.alarmId == alarmId }
        assertTrue("Durable cancel witness MUST be persisted when cancelSuspending fails", unresolvedCancels.isNotEmpty())

        val workInfos = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(com.sysadmindoc.alarmclock.worker.BootRescheduleWorker.WORK_NAME)
            .get()
        assertTrue("BootRescheduleWorker MUST be enqueued on one-shot post-CAS cancel failure", workInfos.isNotEmpty())
    }

    @Test
    fun `R2 - after one-shot post-CAS cancel failure rescheduleAllInBatches cancels orphaned Legacy V1 OS PendingIntent`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val triggerTime = monday0700Ms
        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                repeatDays = emptySet(),
                nextTriggerTime = triggerTime
            )
        )
        insertAuthorityForAlarm(alarmId, triggerTime)

        // Arm Legacy V1 PendingIntent in real AlarmManager
        val fireIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            fireIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        realAm.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(triggerTime, pi), pi)
        assertEquals(1, shadowAm.scheduledAlarms.size)

        // Simulate OS cancel failure during SkipNextReceiver
        val failingAm = mockk<android.app.AlarmManager>(relaxed = true)
        io.mockk.every { failingAm.cancel(any<android.app.PendingIntent>()) } throws RuntimeException("Simulated OS failure")
        scheduler.testAlarmManager = failingAm

        val intent = Intent(context, SkipNextReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }
        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver.onReceive(context, intent)

        // Orphaned OS alarm still armed right after failed cancel
        assertEquals(1, shadowAm.scheduledAlarms.size)

        // Simulate recovery worker run with working AlarmManager
        scheduler.testAlarmManager = null
        val batchResult = scheduler.rescheduleAllInBatches()
        assertEquals(0, batchResult.failedCount)

        val lookupPi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        assertEquals("Orphaned Legacy V1 OS PendingIntent MUST be cancelled on recovery", null, lookupPi)
        assertEquals(0, shadowAm.scheduledAlarms.size)
        assertTrue(
            "Durable cancel witness MUST be settled",
            db.durableOperationIntentDao().getUnresolvedCancellationIntents().none { it.alarmId == alarmId }
        )
    }

    @Test
    fun `R3 - re-enabling with new generation before recovery protects new OS PendingIntent from stale cancel witness`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val triggerTime = monday0700Ms
        val newTriggerTime = System.currentTimeMillis() + 86_400_000L
        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                repeatDays = emptySet(),
                nextTriggerTime = triggerTime
            )
        )
        insertAuthorityForAlarm(alarmId, triggerTime)

        val failingAm = mockk<android.app.AlarmManager>(relaxed = true)
        io.mockk.every { failingAm.cancel(any<android.app.PendingIntent>()) } throws RuntimeException("Simulated OS failure")
        scheduler.testAlarmManager = failingAm

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver.onReceive(
            context,
            Intent(context, SkipNextReceiver::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
            }
        )

        // User edits & re-enables alarm (new generation) before recovery runs
        val current = alarmRepo.getById(alarmId)!!
        val reenabled = current.copy(
            hour = 8,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = newTriggerTime
        )
        alarmRepo.update(reenabled)
        every { calculator.calculate(any()) } returns newTriggerTime
        every { calculator.calculate(any(), any()) } returns newTriggerTime
        scheduler.testAlarmManager = null
        scheduler.schedule(alarmRepo.getById(alarmId)!!)

        val batchResult = scheduler.rescheduleAllInBatches()
        assertEquals(0, batchResult.failedCount)

        val newPi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("New-generation OS PendingIntent MUST remain armed after recovery", newPi)
        assertEquals(1, org.robolectric.Shadows.shadowOf(realAm).scheduledAlarms.size)
        assertTrue(
            "Stale cancel witness MUST be retired without cancelling new generation",
            db.durableOperationIntentDao().getUnresolvedCancellationIntents().none { it.alarmId == alarmId }
        )
    }

    @Test
    fun `R5 - missing DB row during cancel witness recovery fails closed without blindly cancelling OS`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val triggerTime = monday0700Ms
        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                repeatDays = emptySet(),
                nextTriggerTime = triggerTime
            )
        )
        insertAuthorityForAlarm(alarmId, triggerTime)

        val fireIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            fireIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        realAm.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(triggerTime, pi), pi)

        val failingAm = mockk<android.app.AlarmManager>(relaxed = true)
        io.mockk.every { failingAm.cancel(any<android.app.PendingIntent>()) } throws RuntimeException("Simulated OS failure")
        scheduler.testAlarmManager = failingAm

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = mockEntryPoint
            clock = testClock
        }
        receiver.onReceive(
            context,
            Intent(context, SkipNextReceiver::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
            }
        )

        // Delete DB row behind recovery's back
        alarmRepo.deleteById(alarmId)
        scheduler.testAlarmManager = null

        val batchResult = scheduler.rescheduleAllInBatches()
        assertTrue("Recovery MUST fail closed (failedCount > 0) when DB row is missing", batchResult.failedCount > 0)
        assertNotNull("OS PendingIntent MUST NOT be blindly cancelled when DB identity is missing",
            android.app.PendingIntent.getBroadcast(
                context,
                alarmId.toInt(),
                Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    private fun openFileBackedDb(name: String): AlarmDatabase =
        Room.databaseBuilder(context, AlarmDatabase::class.java, tempFolder.newFile(name).absolutePath)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .allowMainThreadQueries()
            .build()

    private fun reopenFileBackedDb(file: java.io.File): AlarmDatabase =
        Room.databaseBuilder(context, AlarmDatabase::class.java, file.absolutePath)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .allowMainThreadQueries()
            .build()

    private fun newRepo(database: AlarmDatabase): AlarmRepository =
        AlarmRepository(database = database, dao = database.alarmDao(), groupDao = database.alarmGroupDao())

    private fun newSchedulerFor(repo: AlarmRepository): AlarmScheduler =
        AlarmScheduler(
            context = context,
            repository = repo,
            calculator = calculator,
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = AlarmIncidentRepository(dao = repo.database.alarmIncidentEventDao(), context = context),
            weatherRepository = mockk(relaxed = true)
        )

    private fun newEntryPointFor(database: AlarmDatabase, repo: AlarmRepository, sched: AlarmScheduler): AlarmClockApp.AppEntryPoint =
        mockk<AlarmClockApp.AppEntryPoint>(relaxed = true).also { ep ->
            every { ep.alarmRepository() } returns repo
            every { ep.alarmDatabase() } returns database
            every { ep.alarmScheduler() } returns sched
            every { ep.nextAlarmCalculator() } returns calculator
            every { ep.alarmEventRepository() } returns AlarmEventRepository(dao = database.alarmEventDao())
            every { ep.webhookService() } returns webhookService
            every { ep.preferencesManager() } returns preferencesManager
        }

    private fun seedAuthorityAndSlot(database: AlarmDatabase, alarmId: Long, triggerMillis: Long) {
        runBlocking {
            if (database.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId) == null) {
                database.scheduleAuthorityDao().insertAuthority(
                    ScheduleAuthority(
                        alarmId = alarmId,
                        instanceToken = "auth-token-$alarmId",
                        definitionRevision = 1L,
                        scheduleRevision = 1L,
                        lifecycle = "LIVE",
                        desiredOperation = "SET",
                        desiredTriggerMillis = triggerMillis,
                        reconcileState = "SETTLED",
                        restoreEpoch = 1L,
                        actionKey = null
                    )
                )
            }
            if (database.issuedOccurrenceDao().getSlot(alarmId) == null) {
                database.issuedOccurrenceDao().insertSlotInitial(
                    com.sysadmindoc.alarmclock.data.local.entity.IssueSlot(alarmId = alarmId, generationId = 1L, currentIssueToken = null)
                )
            }
        }
    }

    private fun armLegacyPi(alarmId: Long, triggerTime: Long) {
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val fireIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            fireIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        realAm.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(triggerTime, pi), pi)
    }

    @Test
    fun `W1 - crash between one-shot CAS and witness persistence rolls back atomically and preserves armed alarm across restart`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val dbFile = tempFolder.newFile("skip-w1.db")
        val db1 = reopenFileBackedDb(dbFile)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarmId: Long
        try {
            val repo1 = newRepo(db1)
            alarmId = repo1.save(
                Alarm(
                    hour = 7,
                    minute = 0,
                    isEnabled = true,
                    scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = emptySet(),
                    nextTriggerTime = triggerTime
                )
            )
            seedAuthorityAndSlot(db1, alarmId, triggerTime)
            armLegacyPi(alarmId, triggerTime)
            assertEquals(1, shadowAm.scheduledAlarms.size)

            // Simulate crash / failure immediately after CAS and before witness insert
            db1.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER test_abort_w1 BEFORE INSERT ON durable_operation_intent BEGIN SELECT RAISE(ABORT, 'W1 simulated crash before witness'); END;"
            )
            val sched1 = newSchedulerFor(repo1)
            val ep1 = newEntryPointFor(db1, repo1, sched1)
            val receiver = SkipNextReceiver().apply {
                testScope = CoroutineScope(Dispatchers.Unconfined)
                testEntryPoint = ep1
                clock = testClock
            }
            receiver.onReceive(
                context,
                Intent(context, SkipNextReceiver::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
                }
            )

            val afterFail = repo1.getById(alarmId)!!
            assertTrue("W1: DB alarm MUST stay enabled when witness insert aborts (atomic rollback)", afterFail.isEnabled)
            assertEquals("W1: nextTriggerTime MUST stay intact after rollback", triggerTime, afterFail.nextTriggerTime)
            assertTrue("W1: outcome MUST be RegistrationFailed, not NoArm", receiver.lastOutcome is ScheduleOutcome.RegistrationFailed)
            assertEquals("W1: OS PendingIntent MUST remain armed", 1, shadowAm.scheduledAlarms.size)
            db1.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS test_abort_w1;")
        } finally {
            db1.close()
        }

        // Process-restart equivalent with fresh database, repository, and scheduler
        val db2 = reopenFileBackedDb(dbFile)
        try {
            val repo2 = newRepo(db2)
            val sched2 = newSchedulerFor(repo2)
            every { calculator.calculate(any()) } returns triggerTime
            every { calculator.calculate(any(), any()) } returns triggerTime
            val batch = sched2.rescheduleAllInBatches()
            assertEquals(0, batch.failedCount)
            assertTrue(repo2.getById(alarmId)!!.isEnabled)
            assertEquals(1, shadowAm.scheduledAlarms.size)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `W2 - witness persistence failure fails closed instead of silent null and blocks skip without cancelling OS`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val dbFile = tempFolder.newFile("skip-w2.db")
        val db1 = reopenFileBackedDb(dbFile)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        try {
            val repo1 = newRepo(db1)
            val alarmId = repo1.save(
                Alarm(
                    hour = 7,
                    minute = 0,
                    isEnabled = true,
                    scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = emptySet(),
                    nextTriggerTime = triggerTime
                )
            )
            seedAuthorityAndSlot(db1, alarmId, triggerTime)
            armLegacyPi(alarmId, triggerTime)

            db1.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER test_abort_w2 BEFORE INSERT ON durable_operation_intent BEGIN SELECT RAISE(ABORT, 'W2 forced insert failure'); END;"
            )
            val alarm = repo1.getById(alarmId)!!
            var directThrew = false
            try {
                AlarmScheduler.persistPendingLegacyV1CancelWitness(repo1, alarm, triggerTime)
            } catch (_: Throwable) {
                directThrew = true
            }
            assertTrue("W2: persistPendingLegacyV1CancelWitness MUST fail closed (throw) instead of returning silent null", directThrew)

            val sched1 = newSchedulerFor(repo1)
            val ep1 = newEntryPointFor(db1, repo1, sched1)
            val receiver = SkipNextReceiver().apply {
                testScope = CoroutineScope(Dispatchers.Unconfined)
                testEntryPoint = ep1
                clock = testClock
            }
            receiver.onReceive(
                context,
                Intent(context, SkipNextReceiver::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
                }
            )

            assertTrue("W2: DB MUST remain enabled on witness persistence failure", repo1.getById(alarmId)!!.isEnabled)
            assertEquals("W2: OS PI MUST NOT be cancelled when witness failed to persist", 1, shadowAm.scheduledAlarms.size)
            assertTrue("W2: outcome MUST be RegistrationFailed", receiver.lastOutcome is ScheduleOutcome.RegistrationFailed)
        } finally {
            db1.close()
        }
    }

    @Test
    fun `W4 - post-OS-cancel witness settlement failure enqueues recovery and fresh instance settles witness cleanly`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val dbFile = tempFolder.newFile("skip-w4.db")
        val db1 = reopenFileBackedDb(dbFile)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val alarmId: Long
        try {
            val repo1 = newRepo(db1)
            alarmId = repo1.save(
                Alarm(
                    hour = 7,
                    minute = 0,
                    isEnabled = true,
                    scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = emptySet(),
                    nextTriggerTime = triggerTime
                )
            )
            seedAuthorityAndSlot(db1, alarmId, triggerTime)
            armLegacyPi(alarmId, triggerTime)
            assertEquals(1, shadowAm.scheduledAlarms.size)

            // Block phase UPDATE (settlement) while allowing witness INSERT
            db1.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER test_abort_w4 BEFORE UPDATE ON durable_operation_intent BEGIN SELECT RAISE(ABORT, 'W4 settlement failure'); END;"
            )
            val sched1 = newSchedulerFor(repo1)
            val ep1 = newEntryPointFor(db1, repo1, sched1)
            val receiver = SkipNextReceiver().apply {
                testScope = CoroutineScope(Dispatchers.Unconfined)
                testEntryPoint = ep1
                clock = testClock
            }
            receiver.onReceive(
                context,
                Intent(context, SkipNextReceiver::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
                }
            )

            assertFalse("W4: DB MUST be disabled after CAS + OS cancel", repo1.getById(alarmId)!!.isEnabled)
            assertEquals("W4: OS PI MUST be cancelled", 0, shadowAm.scheduledAlarms.size)
            assertEquals(
                "W4: Witness MUST remain PENDING when settlement failed",
                1,
                db1.durableOperationIntentDao().getUnresolvedCancellationIntents().count { it.alarmId == alarmId }
            )
            val workInfos = androidx.work.WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(com.sysadmindoc.alarmclock.worker.BootRescheduleWorker.WORK_NAME)
                .get()
            assertTrue("W4: Settlement failure MUST enqueue BootRescheduleWorker instead of silently swallowing", workInfos.isNotEmpty())
        } finally {
            db1.close()
        }

        // Fresh instance hitting another settlement failure during recovery must report failedCount > 0
        val db2 = reopenFileBackedDb(dbFile)
        try {
            val sched2 = newSchedulerFor(newRepo(db2))
            val failedBatch = sched2.rescheduleAllInBatches()
            assertTrue("W4: Recovery settlement failure MUST increment failedCount instead of swallowing", failedBatch.failedCount > 0)
            db2.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS test_abort_w4;")
        } finally {
            db2.close()
        }

        // Fresh process-restart instance settles witness cleanly
        val db3 = reopenFileBackedDb(dbFile)
        try {
            val sched3 = newSchedulerFor(newRepo(db3))
            val okBatch = sched3.rescheduleAllInBatches()
            assertEquals(0, okBatch.failedCount)
            assertTrue(
                "W4: Fresh recovery instance MUST settle witness to COMPLETED",
                db3.durableOperationIntentDao().getUnresolvedCancellationIntents().none { it.alarmId == alarmId }
            )
            val settled = db3.durableOperationIntentDao().getIntentsByAlarmId(alarmId).single()
            assertEquals(com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_COMPLETED, settled.phase)
        } finally {
            db3.close()
        }
    }

    @Test
    fun `W5 - concurrent new generation update rejects stale one-shot skip and protects new OS PendingIntent across restart`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val dbFile = tempFolder.newFile("skip-w5.db")
        val db1 = reopenFileBackedDb(dbFile)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val t1 = System.currentTimeMillis() + 3_600_000L
        val t2 = System.currentTimeMillis() + 86_400_000L
        val alarmId: Long
        try {
            val repo1 = newRepo(db1)
            alarmId = repo1.save(
                Alarm(
                    hour = 7,
                    minute = 0,
                    isEnabled = true,
                    scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                    repeatDays = emptySet(),
                    nextTriggerTime = t1
                )
            )
            seedAuthorityAndSlot(db1, alarmId, t1)

            // Concurrent update bumps generation to 2 and schedules t2
            val current = repo1.getById(alarmId)!!
            repo1.update(current.copy(hour = 8, minute = 0, isEnabled = true, nextTriggerTime = t2))
            val gen2Alarm = repo1.getById(alarmId)!!
            assertEquals(2, gen2Alarm.generation)
            every { calculator.calculate(any()) } returns t2
            every { calculator.calculate(any(), any()) } returns t2
            val sched1 = newSchedulerFor(repo1)
            assertTrue(sched1.scheduleAt(gen2Alarm, t2, requestWidgetUpdate = false).isArmed)
            assertEquals(1, shadowAm.scheduledAlarms.size)

            // Stale skip targeting t1 fires
            val ep1 = newEntryPointFor(db1, repo1, sched1)
            val receiver = SkipNextReceiver().apply {
                testScope = CoroutineScope(Dispatchers.Unconfined)
                testEntryPoint = ep1
                clock = testClock
            }
            receiver.onReceive(
                context,
                Intent(context, SkipNextReceiver::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, t1)
                }
            )

            assertTrue(repo1.getById(alarmId)!!.isEnabled)
            assertEquals(2, repo1.getById(alarmId)!!.generation)
            assertTrue(db1.durableOperationIntentDao().getIntentsByAlarmId(alarmId).isEmpty())
            assertEquals(1, shadowAm.scheduledAlarms.size)
        } finally {
            db1.close()
        }

        val db2 = reopenFileBackedDb(dbFile)
        try {
            val sched2 = newSchedulerFor(newRepo(db2))
            val batch = sched2.rescheduleAllInBatches()
            assertEquals(0, batch.failedCount)
            assertEquals(1, shadowAm.scheduledAlarms.size)
            assertTrue(newRepo(db2).getById(alarmId)!!.isEnabled)
        } finally {
            db2.close()
        }
    }

    @Test
    fun `W6 - existing unresolved Rev2 and UNKNOWN intents block one-shot skip atomically without aborting intents or cancelling OS`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val dbFile = tempFolder.newFile("skip-w6.db")
        val db1 = reopenFileBackedDb(dbFile)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val triggerTime = System.currentTimeMillis() + 3_600_000L
        val rev2AlarmId: Long
        val unknownAlarmId: Long
        try {
            val repo1 = newRepo(db1)
            rev2AlarmId = repo1.save(
                Alarm(hour = 7, minute = 0, isEnabled = true, scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME, repeatDays = emptySet(), nextTriggerTime = triggerTime)
            )
            unknownAlarmId = repo1.save(
                Alarm(hour = 8, minute = 0, isEnabled = true, scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME, repeatDays = emptySet(), nextTriggerTime = triggerTime + 3_600_000L)
            )
            seedAuthorityAndSlot(db1, rev2AlarmId, triggerTime)
            seedAuthorityAndSlot(db1, unknownAlarmId, triggerTime + 3_600_000L)
            armLegacyPi(rev2AlarmId, triggerTime)
            armLegacyPi(unknownAlarmId, triggerTime + 3_600_000L)

            db1.durableOperationIntentDao().insertValidatedIntent(
                com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                    operationToken = "rev2-cancel-$rev2AlarmId",
                    alarmId = rev2AlarmId,
                    operationEpoch = 100L,
                    incarnationId = "fire-rev2-$rev2AlarmId",
                    operationKind = "CANCEL",
                    expectedOwnerToken = "auth-token-$rev2AlarmId",
                    expectedScheduleRevision = 1L,
                    expectedRestoreEpoch = 1L,
                    phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                    intentRevision = 2L,
                    originalFireId = "fire-rev2-$rev2AlarmId",
                    originalScheduledAt = triggerTime
                )
            )
            db1.durableOperationIntentDao().insertValidatedIntent(
                com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent(
                    operationToken = "unknown-intent-$unknownAlarmId",
                    alarmId = unknownAlarmId,
                    operationEpoch = 100L,
                    incarnationId = "fire-unk-$unknownAlarmId",
                    operationKind = "CANCEL",
                    expectedOwnerToken = "auth-token-$unknownAlarmId",
                    expectedScheduleRevision = 1L,
                    expectedRestoreEpoch = 1L,
                    phase = com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_IN_FLIGHT,
                    intentRevision = 99L
                )
            )

            val sched1 = newSchedulerFor(repo1)
            val ep1 = newEntryPointFor(db1, repo1, sched1)

            val rRev2 = SkipNextReceiver().apply {
                testScope = CoroutineScope(Dispatchers.Unconfined)
                testEntryPoint = ep1
                clock = testClock
            }
            rRev2.onReceive(
                context,
                Intent(context, SkipNextReceiver::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, rev2AlarmId)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
                }
            )
            assertTrue("W6(Rev2): DB alarm MUST remain enabled", repo1.getById(rev2AlarmId)!!.isEnabled)
            assertEquals(
                "W6(Rev2): Existing Rev2 intent MUST remain PENDING (never ABORTED)",
                com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_PENDING,
                db1.durableOperationIntentDao().getIntentByToken("rev2-cancel-$rev2AlarmId")?.phase
            )
            assertTrue("W6(Rev2): outcome MUST be RegistrationFailed", rRev2.lastOutcome is ScheduleOutcome.RegistrationFailed)

            val rUnknown = SkipNextReceiver().apply {
                testScope = CoroutineScope(Dispatchers.Unconfined)
                testEntryPoint = ep1
                clock = testClock
            }
            rUnknown.onReceive(
                context,
                Intent(context, SkipNextReceiver::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, unknownAlarmId)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime + 3_600_000L)
                }
            )
            assertTrue("W6(UNKNOWN): DB alarm MUST remain enabled", repo1.getById(unknownAlarmId)!!.isEnabled)
            assertEquals(
                "W6(UNKNOWN): Existing UNKNOWN intent MUST remain IN_FLIGHT (never ABORTED)",
                com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_IN_FLIGHT,
                db1.durableOperationIntentDao().getIntentByToken("unknown-intent-$unknownAlarmId")?.phase
            )
            assertTrue("W6(UNKNOWN): outcome MUST be RegistrationFailed", rUnknown.lastOutcome is ScheduleOutcome.RegistrationFailed)
            assertEquals("W6: Both OS PendingIntents MUST remain armed", 2, shadowAm.scheduledAlarms.size)
        } finally {
            db1.close()
        }
    }

    @Test
    fun `R4 - after post-CAS OS cancel succeeds and settlement crashes re-enabling with new generation protects new OS PendingIntent on fresh recovery`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val dbFile = tempFolder.newFile("skip-r4.db")
        val db1 = reopenFileBackedDb(dbFile)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        val t1 = System.currentTimeMillis() + 3_600_000L
        val t2 = System.currentTimeMillis() + 86_400_000L
        val alarmId: Long
        try {
            val repo1 = newRepo(db1)
            alarmId = repo1.save(
                Alarm(hour = 7, minute = 0, isEnabled = true, scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME, repeatDays = emptySet(), nextTriggerTime = t1)
            )
            seedAuthorityAndSlot(db1, alarmId, t1)
            armLegacyPi(alarmId, t1)

            // Block settlement so OS cancel runs but witness remains PHASE_PENDING (W4 crash point)
            db1.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER test_abort_r4 BEFORE UPDATE ON durable_operation_intent BEGIN SELECT RAISE(ABORT, 'R4 crash before settlement'); END;"
            )
            val sched1 = newSchedulerFor(repo1)
            val ep1 = newEntryPointFor(db1, repo1, sched1)
            val receiver = SkipNextReceiver().apply {
                testScope = CoroutineScope(Dispatchers.Unconfined)
                testEntryPoint = ep1
                clock = testClock
            }
            receiver.onReceive(
                context,
                Intent(context, SkipNextReceiver::class.java).apply {
                    putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                    putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, t1)
                }
            )
            assertEquals("R4: gen1 OS PI must be cancelled", 0, shadowAm.scheduledAlarms.size)
            db1.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS test_abort_r4;")

            // Re-enable with new generation (gen = 2) and arm new OS PendingIntent before recovery
            val disabled = repo1.getById(alarmId)!!
            repo1.update(disabled.copy(hour = 8, minute = 30, isEnabled = true, nextTriggerTime = t2))
            val gen2 = repo1.getById(alarmId)!!
            assertEquals(2, gen2.generation)
            every { calculator.calculate(any()) } returns t2
            every { calculator.calculate(any(), any()) } returns t2
            assertTrue(sched1.scheduleAt(gen2, t2, requestWidgetUpdate = false).isArmed)
            assertEquals(1, shadowAm.scheduledAlarms.size)
        } finally {
            db1.close()
        }

        // Fresh process-restart instance runs recovery
        val db2 = reopenFileBackedDb(dbFile)
        try {
            val repo2 = newRepo(db2)
            val sched2 = newSchedulerFor(repo2)
            every { calculator.calculate(any()) } returns t2
            every { calculator.calculate(any(), any()) } returns t2
            val batch = sched2.rescheduleAllInBatches()
            assertEquals(0, batch.failedCount)

            val gen2Pi = android.app.PendingIntent.getBroadcast(
                context,
                alarmId.toInt(),
                Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
                android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            assertNotNull("R4: gen2 OS PendingIntent MUST remain armed after recovery", gen2Pi)
            assertEquals(1, shadowAm.scheduledAlarms.size)
            val witness = db2.durableOperationIntentDao().getIntentsByAlarmId(alarmId).single()
            assertEquals(
                "R4: stale gen1 cancel witness MUST be aborted",
                com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent.PHASE_ABORTED,
                witness.phase
            )
        } finally {
            db2.close()
        }
    }

    @Test
    fun `RED-C41-04 - repeating skip rejects post-claim backup restore advancing restoreEpoch before scheduleAt`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        shadowAm.scheduledAlarms.clear()

        val currentTrigger = monday0700Ms
        val nextTrigger = tuesday0700Ms
        val restoredTrigger = mondayNextWeek0700Ms
        every { calculator.calculate(any(), any()) } returns nextTrigger
        every { calculator.calculate(any()) } returns nextTrigger

        db.scheduleAuthorityDao().insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED")
        )
        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_REPEATING,
                repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
                nextTriggerTime = currentTrigger
            )
        )
        insertAuthorityForAlarm(alarmId, currentTrigger)

        val spiedScheduler = io.mockk.spyk(scheduler)
        coEvery {
            spiedScheduler.scheduleAt(any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            // Simulate restore completing AFTER DurableSkipClaimAdapter commits and BEFORE scheduleAt runs
            db.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 101L, bootstrapState = "INITIALIZED")
            )
            db.scheduleAuthorityDao().insertAuthority(
                ScheduleAuthority(
                    alarmId = alarmId,
                    instanceToken = "auth-token-restored-$alarmId",
                    definitionRevision = 1L,
                    scheduleRevision = 1L,
                    lifecycle = "LIVE",
                    desiredOperation = "SET",
                    desiredTriggerMillis = restoredTrigger,
                    reconcileState = "SETTLED",
                    restoreEpoch = 101L,
                    actionKey = null
                )
            )
            db.alarmDao().updateNextTrigger(alarmId, restoredTrigger)
            callOriginal()
        }

        val epWithSpy = mockk<AlarmClockApp.AppEntryPoint>(relaxed = true)
        every { epWithSpy.alarmRepository() } returns alarmRepo
        every { epWithSpy.alarmDatabase() } returns db
        every { epWithSpy.alarmScheduler() } returns spiedScheduler
        every { epWithSpy.nextAlarmCalculator() } returns calculator
        every { epWithSpy.alarmEventRepository() } returns eventRepo
        every { epWithSpy.webhookService() } returns webhookService
        every { epWithSpy.preferencesManager() } returns preferencesManager

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = epWithSpy
            clock = testClock
        }
        receiver.onReceive(
            context,
            Intent(context, SkipNextReceiver::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, currentTrigger)
            }
        )

        assertTrue(
            "RED-C41-04: repeating skip MUST reject post-claim restore epoch advance with RegistrationFailed, got ${receiver.lastOutcome}",
            receiver.lastOutcome is ScheduleOutcome.RegistrationFailed
        )
        assertEquals("RED-C41-04: pre-restore skipped trigger MUST NOT arm OS after restore", 0, shadowAm.scheduledAlarms.size)
        assertEquals("RED-C41-04: restored alarm nextTriggerTime MUST be preserved", restoredTrigger, alarmRepo.getById(alarmId)?.nextTriggerTime)
    }

    @Test
    fun `RED-C41-05 - one-shot skip rejects post-CAS restore recreate and preserves restored OS PendingIntent`() = runBlocking {
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(context)
        val realAm = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        val shadowAm = org.robolectric.Shadows.shadowOf(realAm)
        shadowAm.scheduledAlarms.clear()

        val t1 = monday0700Ms
        val tRestored = mondayNextWeek0700Ms

        db.scheduleAuthorityDao().insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 100L, bootstrapState = "INITIALIZED")
        )
        val alarmId = alarmRepo.save(
            Alarm(
                hour = 7,
                minute = 0,
                isEnabled = true,
                scheduleKind = Alarm.SCHEDULE_KIND_ONE_TIME,
                repeatDays = emptySet(),
                nextTriggerTime = t1
            )
        )
        insertAuthorityForAlarm(alarmId, t1)

        // Arm initial Legacy V1 PendingIntent
        val piIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, t1)
        }
        val pi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            piIntent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        realAm.setAlarmClock(android.app.AlarmManager.AlarmClockInfo(t1, pi), pi)
        assertEquals(1, shadowAm.scheduledAlarms.size)

        val spiedScheduler = io.mockk.spyk(scheduler)
        val simulateRestoreBetweenCasAndCancel = {
            runBlocking {
                db.scheduleAuthorityDao().insertMeta(
                    ScheduleAuthorityMeta(id = 1, restoreEpoch = 101L, bootstrapState = "INITIALIZED")
                )
                val restored = alarmRepo.getById(alarmId)!!.copy(
                    isEnabled = true,
                    generation = 1,
                    revision = 1,
                    nextTriggerTime = tRestored
                )
                db.alarmDao().update(restored)
                db.scheduleAuthorityDao().insertAuthority(
                    ScheduleAuthority(
                        alarmId = alarmId,
                        instanceToken = "auth-token-restored-oneshot-$alarmId",
                        definitionRevision = 1L,
                        scheduleRevision = 1L,
                        lifecycle = "LIVE",
                        desiredOperation = "SET",
                        desiredTriggerMillis = tRestored,
                        reconcileState = "SETTLED",
                        restoreEpoch = 101L,
                        actionKey = null
                    )
                )
            }
        }
        coEvery { spiedScheduler.cancelSuspending(any()) } coAnswers {
            simulateRestoreBetweenCasAndCancel()
            callOriginal()
        }
        every {
            spiedScheduler.cancelScheduledEntriesWithResult(any(), any(), any(), any(), any(), any(), any())
        } answers {
            simulateRestoreBetweenCasAndCancel()
            callOriginal()
        }

        val epWithSpy = mockk<AlarmClockApp.AppEntryPoint>(relaxed = true)
        every { epWithSpy.alarmRepository() } returns alarmRepo
        every { epWithSpy.alarmDatabase() } returns db
        every { epWithSpy.alarmScheduler() } returns spiedScheduler
        every { epWithSpy.nextAlarmCalculator() } returns calculator
        every { epWithSpy.alarmEventRepository() } returns eventRepo
        every { epWithSpy.webhookService() } returns webhookService
        every { epWithSpy.preferencesManager() } returns preferencesManager

        val receiver = SkipNextReceiver().apply {
            testScope = CoroutineScope(Dispatchers.Unconfined)
            testEntryPoint = epWithSpy
            clock = testClock
        }
        receiver.onReceive(
            context,
            Intent(context, SkipNextReceiver::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, t1)
            }
        )

        assertTrue(
            "RED-C41-05: one-shot skip cancel MUST fail closed on post-CAS restore epoch advance, got ${receiver.lastOutcome}",
            receiver.lastOutcome is ScheduleOutcome.RegistrationFailed
        )
        val postPi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("RED-C41-05: restored alarm OS PendingIntent MUST NOT be cancelled by pre-restore skip", postPi)
        assertEquals(1, shadowAm.scheduledAlarms.size)
    }
}
