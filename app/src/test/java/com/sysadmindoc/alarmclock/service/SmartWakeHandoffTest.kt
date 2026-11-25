package com.sysadmindoc.alarmclock.service

import android.app.AlarmManager
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.MainSlotApplier
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityOwner
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import io.mockk.verify
import javax.inject.Provider
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmartWakeHandoffTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var scheduler: AlarmScheduler

    private val calculator: com.sysadmindoc.alarmclock.domain.NextAlarmCalculator = mockk(relaxed = true)
    private val preferencesManager: PreferencesManager = mockk(relaxed = true)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        alarmRepo = AlarmRepository(
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao(),
            database = db
        )

        incidentRepo = AlarmIncidentRepository(
            dao = db.alarmIncidentEventDao(),
            context = context
        )

        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()

        val owner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = MainSlotApplier { true },
            exactPermissionChecker = { true }
        )

        scheduler = spyk(
            AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = mockk(relaxed = true),
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = incidentRepo,
                weatherRepository = mockk(relaxed = true),
                scheduleAuthorityOwnerProvider = Provider { owner }
            )
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `cancelMainScheduledAlarm rejects stale trigger when alarm nextTriggerTime is newer or earlier`() = runBlocking {
        val alarmId = 101L
        val originalTargetTime = 1700000000000L
        val newerTargetTime = 1800000000000L
        val earlierTargetTime = 1600000000000L

        // Case 1: Alarm 101 rescheduled newer
        alarmRepo.save(Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = newerTargetTime))
        val cancelledNewer = scheduler.cancelMainScheduledAlarm(alarmId, originalTargetTime)
        assertFalse("Stale SmartWake cancellation MUST be rejected when newer trigger exists", cancelledNewer)

        // Case 2: Alarm 101 rescheduled earlier
        alarmRepo.save(Alarm(id = alarmId, hour = 6, minute = 0, isEnabled = true, nextTriggerTime = earlierTargetTime))
        val cancelledEarlier = scheduler.cancelMainScheduledAlarm(alarmId, originalTargetTime)
        assertFalse("Stale SmartWake cancellation MUST be rejected when earlier trigger exists", cancelledEarlier)
    }

    @Test
    fun `cancelMainScheduledAlarm returns false when AlarmManager cancel throws exception`() = runBlocking {
        val alarmId = 104L
        val targetTime = System.currentTimeMillis() + 3600_000L

        every { calculator.calculate(any()) } returns targetTime

        val spyAlarmManager = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<android.app.PendingIntent>()) } throws SecurityException("Permission revoked")
        }
        scheduler.testAlarmManager = spyAlarmManager

        val alarm = Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = targetTime)
        alarmRepo.save(alarm)
        scheduler.schedule(alarm, requestWidgetUpdate = false)

        val cancelled = scheduler.cancelMainScheduledAlarm(alarmId, targetTime)
        assertFalse("cancelMainScheduledAlarm MUST return false when OS cancel throws exception", cancelled)

        scheduler.testAlarmManager = null
    }

    @Test
    fun `cancelMainScheduledAlarm succeeds when target time matches current alarm trigger`() = runBlocking {
        val alarmId = 102L
        val targetTime = System.currentTimeMillis() + 3600_000L

        every { calculator.calculate(any()) } returns targetTime

        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = targetTime
        )
        alarmRepo.save(alarm)
        val scheduleSuccess = scheduler.scheduleAt(alarm, targetTime, requestWidgetUpdate = false)
        assertTrue("scheduleAt MUST succeed", scheduleSuccess.isArmed)

        val cancelled = scheduler.cancelMainScheduledAlarm(alarmId, targetTime)
        assertTrue("SmartWake cancellation MUST succeed when target time matches current trigger and PendingIntent exists", cancelled)
    }

    @Test
    fun `cancelMainScheduledAlarm rejects cancellation when expectedIssueToken mismatches active slot token even if target time is identical`() = runBlocking {
        val alarmId = 105L
        val targetTime = System.currentTimeMillis() + 3600_000L
        val tokenC = "token-C-generation1"
        val tokenD = "token-D-generation2"

        alarmRepo.save(Alarm(id = alarmId, hour = 7, minute = 0, isEnabled = true, nextTriggerTime = targetTime))

        // Production default (LEGACY_ONLY) must fail closed on tokenized OS set
        assertFalse(
            "LEGACY_ONLY fail-closed MUST reject applyTokenizedOsSet",
            scheduler.applyTokenizedOsSet(alarmId, targetTime, tokenC)
        )
        assertEquals(null, db.issuedOccurrenceDao().getSlotCurrentToken(alarmId))

        // Explicit test Owner Route fixture exercises tokenized OS set + expectedIssueToken fence
        val owner = ScheduleAuthorityOwner(
            authorityDao = db.scheduleAuthorityDao(),
            mainSlotApplier = MainSlotApplier { true },
            exactPermissionChecker = { true }
        )
        val ownerRouteAuthority = mockk<com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority> {
            every { isOwnerRouteActive() } returns true
        }
        val ownerScheduler = spyk(
            AlarmScheduler(
                context = context,
                repository = alarmRepo,
                calculator = calculator,
                preferencesManager = preferencesManager,
                holidayRepository = mockk(relaxed = true),
                alarmIncidentRepository = incidentRepo,
                weatherRepository = mockk(relaxed = true),
                scheduleAuthorityOwnerProvider = Provider { owner },
                routeAuthority = ownerRouteAuthority
            )
        )

        // Schedule token C (Generation 1)
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmId, targetTime, tokenC))

        // Reschedule token D (Generation 2) for same targetTime
        assertTrue(ownerScheduler.applyTokenizedOsSet(alarmId, targetTime, tokenD))
        assertEquals(tokenD, db.issuedOccurrenceDao().getSlotCurrentToken(alarmId))

        // Stale command C attempts to cancel with expectedIssueToken = tokenC
        val cancelledStaleC = ownerScheduler.cancelMainScheduledAlarm(alarmId, targetTime, expectedIssueToken = tokenC)
        assertFalse("Stale SmartWake command C MUST be rejected when active slot token is tokenD", cancelledStaleC)

        // Tokenless command attempts to cancel tokenized alarm -> fails closed
        val cancelledTokenless = ownerScheduler.cancelMainScheduledAlarm(alarmId, targetTime, expectedIssueToken = null)
        assertFalse("Tokenless SmartWake command MUST be rejected when active slot token is tokenD", cancelledTokenless)

        // Active command D attempts to cancel with expectedIssueToken = tokenD
        val cancelledActiveD = ownerScheduler.cancelMainScheduledAlarm(alarmId, targetTime, expectedIssueToken = tokenD)
        assertTrue("Active SmartWake command D MUST succeed when expectedIssueToken matches active slot token", cancelledActiveD)
    }

    @Test
    fun `positive delivery proof count returns 0 for REQUESTED status alone and 1 for SUCCEEDED status`() = runBlocking {
        val alarmId = 103L
        val scheduledAt = 1700000000000L
        val fireId = AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt)

        // 1. Record STATUS_REQUESTED incident (FGS start requested)
        incidentRepo.record(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            type = AlarmIncidentEvent.TYPE_BROADCAST,
            status = AlarmIncidentEvent.STATUS_REQUESTED,
            reasonCode = "START_FOREGROUND_SERVICE",
            source = "Test"
        )

        // STATUS_REQUESTED is NOT delivery proof
        val requestedProof = incidentRepo.occurrenceDeliveryCount(alarmId, scheduledAt)
        assertEquals("STATUS_REQUESTED alone MUST NOT constitute positive delivery proof", 0, requestedProof)

        // 2. Record STATUS_SUCCEEDED incident (Alarm notification posted)
        incidentRepo.record(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            type = AlarmIncidentEvent.TYPE_NOTIFICATION,
            status = AlarmIncidentEvent.STATUS_SUCCEEDED,
            reasonCode = "ALARM_NOTIFICATION_POSTED",
            source = "Test"
        )

        // STATUS_SUCCEEDED constitutes positive delivery proof
        val succeededProof = incidentRepo.occurrenceDeliveryCount(alarmId, scheduledAt)
        assertEquals("STATUS_SUCCEEDED notification MUST constitute positive delivery proof", 1, succeededProof)
    }

    @Test
    fun `T1_SmartWake - cancelMainScheduledAlarm under normal-state cross-thread L1 contention succeeds without drop`() = runBlocking {
        val alarmId = 106L
        val targetTime = System.currentTimeMillis() + 3600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = targetTime
        )
        alarmRepo.save(alarm)
        assertTrue(scheduler.scheduleAt(alarm, targetTime, requestWidgetUpdate = false).isArmed)

        val shadowAm = org.robolectric.Shadows.shadowOf(
            context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        )
        assertTrue(
            "T1_SmartWake: ScheduledAlarm for alarmId=$alarmId MUST exist in AlarmManager before cancel",
            shadowAm.scheduledAlarms.any {
                org.robolectric.Shadows.shadowOf(it.operation).savedIntent.getLongExtra(
                    AlarmScheduler.EXTRA_ALARM_ID,
                    -1L
                ) == alarmId
            }
        )

        val mutex = com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.recoveryMutexFor(context)
        val lockAcquired = kotlinx.coroutines.CompletableDeferred<Unit>()
        val holderJob = launch(kotlinx.coroutines.Dispatchers.Default) {
            mutex.lock()
            try {
                lockAcquired.complete(Unit)
                kotlinx.coroutines.delay(120L)
            } finally {
                mutex.unlock()
            }
        }
        lockAcquired.await()

        val cancelled = scheduler.cancelMainScheduledAlarm(alarmId, targetTime)
        holderJob.join()

        assertTrue(
            "T1_SmartWake: cancelMainScheduledAlarm MUST wait for normal-state L1 holder instead of dropping",
            cancelled
        )
        assertTrue(
            "T1_SmartWake: PendingIntent for alarmId=$alarmId in AlarmManager MUST be cancelled after L1 release",
            shadowAm.scheduledAlarms.none {
                org.robolectric.Shadows.shadowOf(it.operation).savedIntent.getLongExtra(
                    AlarmScheduler.EXTRA_ALARM_ID,
                    -1L
                ) == alarmId
            }
        )
        val remainingPi = android.app.PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            android.content.Intent(context, com.sysadmindoc.alarmclock.receiver.AlarmReceiver::class.java).apply {
                action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            },
            android.app.PendingIntent.FLAG_NO_CREATE or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        org.junit.Assert.assertNull(
            "T1_SmartWake: Broadcast PendingIntent for alarmId=$alarmId MUST be cancelled",
            remainingPi
        )
    }

    @Test
    fun `T2_SmartWake - cancelMainScheduledAlarm fails closed with zero OS cancel during PHASE_PREPARED`() = runBlocking {
        val alarmId = 107L
        val targetTime = System.currentTimeMillis() + 3600_000L
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            nextTriggerTime = targetTime
        )
        alarmRepo.save(alarm)
        assertTrue(scheduler.scheduleAt(alarm, targetTime, requestWidgetUpdate = false).isArmed)

        val spyAlarmManager = mockk<AlarmManager>(relaxed = true)
        scheduler.testAlarmManager = spyAlarmManager

        val restoreCoordinator = com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator(
            context = context,
            database = db,
            preferencesManager = preferencesManager,
            alarmScheduler = scheduler,
            moshi = com.squareup.moshi.Moshi.Builder().build()
        )
        restoreCoordinator.prepareTransaction(AppSettings(), listOf(alarm), emptyList())

        try {
            val cancelled = scheduler.cancelMainScheduledAlarm(alarmId, targetTime)
            assertFalse("T2_SmartWake: MUST fail closed during PHASE_PREPARED", cancelled)
            verify(exactly = 0) { spyAlarmManager.cancel(any<android.app.PendingIntent>()) }
        } finally {
            scheduler.testAlarmManager = null
            java.io.File(context.filesDir, com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.JOURNAL_FILENAME).delete()
            java.io.File(context.filesDir, "${com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.JOURNAL_FILENAME}.bak").delete()
        }
    }
}
