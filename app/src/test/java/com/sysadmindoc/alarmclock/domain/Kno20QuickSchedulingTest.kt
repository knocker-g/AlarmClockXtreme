package com.sysadmindoc.alarmclock.domain

import android.content.Context
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmKind
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
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
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class Kno20QuickSchedulingTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmDao: AlarmDao
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var quickScheduler: QuickAlarmScheduler

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        alarmDao = db.alarmDao()
        alarmRepo = AlarmRepository(db, alarmDao, db.alarmGroupDao())
        quickScheduler = QuickAlarmScheduler(context, alarmRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `exact scheduling uses relative elapsed time in same boot and epoch time across boots`() = runBlocking {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()

        // 1. Same-boot relative Quick alarm (boot count matches > 0)
        val sameBootQuick = Alarm(
            hour = 12,
            minute = 0,
            label = "Same Boot Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L,
            quickRelativeElapsedMs = nowElapsed + 600_000L,
            quickBootCount = 1L
        )

        val id1 = alarmRepo.save(sameBootQuick)
        val saved1 = alarmRepo.getById(id1)!!

        // Custom scheduler with boot count 1L
        val customScheduler = object : QuickAlarmScheduler(context, alarmRepo) {
            override fun currentBootCount(): Long = 1L
        }

        val outcome1 = customScheduler.scheduleQuickAlarm(saved1)
        assertTrue("Outcome MUST be Armed", outcome1 is ScheduleOutcome.Armed)
        val armed1 = outcome1 as ScheduleOutcome.Armed
        assertEquals(nowElapsed + 600_000L, armed1.triggerTime)

        // 2. Cross-boot / reboot Quick alarm (boot count differs)
        val crossBootQuick = Alarm(
            hour = 12,
            minute = 0,
            label = "Cross Boot Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L,
            quickRelativeElapsedMs = nowElapsed + 600_000L,
            quickBootCount = 1L
        )

        val id2 = alarmRepo.save(crossBootQuick)
        val saved2 = alarmRepo.getById(id2)!!

        // Boot count changed to 2L (reboot occurred)
        val rebootedScheduler = object : QuickAlarmScheduler(context, alarmRepo) {
            override fun currentBootCount(): Long = 2L
        }

        val outcome2 = rebootedScheduler.scheduleQuickAlarm(saved2)
        assertTrue("Outcome MUST be Armed", outcome2 is ScheduleOutcome.Armed)
        val armed2 = outcome2 as ScheduleOutcome.Armed
        assertEquals(nowEpoch + 600_000L, armed2.triggerTime)
    }

    @Test
    fun `exact scheduling returns PermissionBlocked when exact alarm permission is revoked`() = runBlocking {
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)

        val nowEpoch = System.currentTimeMillis()
        val quickAlarm = Alarm(
            hour = 12,
            minute = 0,
            label = "Blocked Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )

        val id = alarmRepo.save(quickAlarm)
        val saved = alarmRepo.getById(id)!!

        val outcome = quickScheduler.scheduleQuickAlarm(saved)
        assertTrue("ScheduleOutcome MUST be PermissionBlocked when exact alarm permission revoked", outcome is ScheduleOutcome.PermissionBlocked)

        val reloaded = alarmRepo.getById(id)
        assertNotNull("Quick Alarm intent MUST remain persisted in DB when permission blocked", reloaded)
        assertEquals(AlarmKind.QUICK.name, reloaded?.kind)
    }

    @Test
    fun `reconcileQuickAlarms 9m59s late is eligible for bounded late recovery on SYSTEM_RECOVERY`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val late9m59s = Alarm(
            id = 501L,
            hour = 10,
            minute = 0,
            label = "9m59s Late Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch - (9L * 60 * 1000 + 59 * 1000)
        )
        alarmRepo.save(late9m59s)

        val report = quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertEquals(1, report.boundedLateRecoveries.size)
        assertEquals(501L, report.boundedLateRecoveries[0].id)
        assertEquals(0, report.expiredAlarms.size)
    }

    @Test
    fun `reconcileQuickAlarms 10m01s late is expired and deleted`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val late10m01s = Alarm(
            id = 502L,
            hour = 10,
            minute = 0,
            label = "10m01s Late Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch - (10L * 60 * 1000 + 1 * 1000)
        )
        alarmRepo.save(late10m01s)

        val report = quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertEquals(0, report.boundedLateRecoveries.size)
        assertEquals(1, report.expiredAlarms.size)
        assertEquals(502L, report.expiredAlarms[0].id)

        assertNull("Expired Quick Alarm MUST be deleted from DB", alarmRepo.getById(502L))
    }

    @Test
    fun `MANUAL_APP_LAUNCH never late-fires an overdue Quick alarm`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val late2m = Alarm(
            id = 503L,
            hour = 10,
            minute = 0,
            label = "2m Late Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch - (2L * 60 * 1000)
        )
        alarmRepo.save(late2m)

        val report = quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.MANUAL_APP_LAUNCH, nowEpochMs = nowEpoch)
        assertEquals("MANUAL_APP_LAUNCH MUST NOT late-fire overdue Quick alarm", 0, report.boundedLateRecoveries.size)
        assertEquals(1, report.expiredAlarms.size)
        assertEquals(503L, report.expiredAlarms[0].id)

        assertNull("Overdue Quick Alarm MUST be deleted from DB on MANUAL_APP_LAUNCH", alarmRepo.getById(503L))
    }

    @Test
    fun `same-boot relative Quick remains future across wall-clock RTC jumps`() = runBlocking {
        val nowElapsed = SystemClock.elapsedRealtime()
        val nowEpoch = System.currentTimeMillis()

        val relativeQuick = Alarm(
            id = 504L,
            hour = 12,
            minute = 0,
            label = "Relative Quick Jump Test",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L,
            quickRelativeElapsedMs = nowElapsed + 600_000L,
            quickBootCount = 1L
        )
        alarmRepo.save(relativeQuick)

        val customScheduler = object : QuickAlarmScheduler(context, alarmRepo) {
            override fun currentBootCount(): Long = 1L
        }

        // Simulate wall-clock jumping forward 20 minutes (nowEpoch + 1,200,000 ms),
        // but monotonic elapsed time advancing by only 1 second (nowElapsed + 1,000 ms).
        val jumpedEpoch = nowEpoch + 1200_000L
        val advancedElapsed = nowElapsed + 1_000L

        val report = customScheduler.reconcileQuickAlarms(
            ReconcileEntrySource.SYSTEM_RECOVERY,
            nowEpochMs = jumpedEpoch,
            nowElapsedMs = advancedElapsed
        )

        assertEquals("Same-boot relative Quick MUST remain future when monotonic elapsed time is unexpired", 1, report.armedFutureAlarms.size)
        assertEquals(504L, report.armedFutureAlarms[0].id)
        assertEquals(0, report.expiredAlarms.size)
        assertEquals(0, report.boundedLateRecoveries.size)
    }

    @Test
    fun `scheduleQuickAlarm rejects Normal or quarantined malformed kinds`() = runBlocking {
        val normalAlarm = Alarm(id = 601L, kind = Alarm.KIND_NORMAL)
        val malformedAlarm = Alarm(id = 602L, kind = "MALFORMED_KIND")

        val outcomeNormal = quickScheduler.scheduleQuickAlarm(normalAlarm)
        assertTrue("Quick scheduler MUST reject Normal alarm", outcomeNormal is ScheduleOutcome.NoArm)

        val outcomeMalformed = quickScheduler.scheduleQuickAlarm(malformedAlarm)
        assertTrue("Quick scheduler MUST reject malformed kind alarm", outcomeMalformed is ScheduleOutcome.NoArm)
    }
}
