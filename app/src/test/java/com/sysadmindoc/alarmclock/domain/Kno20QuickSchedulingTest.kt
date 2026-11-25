package com.sysadmindoc.alarmclock.domain

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.model.AlarmKind
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
        val jFile = java.io.File(context.filesDir, com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (jFile.exists()) jFile.delete()
        val bFile = java.io.File(context.filesDir, "${com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        if (bFile.exists()) bFile.delete()
    }

    @After
    fun tearDown() {
        db.close()
        val jFile = java.io.File(context.filesDir, com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.JOURNAL_FILENAME)
        if (jFile.exists()) jFile.delete()
        val bFile = java.io.File(context.filesDir, "${com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        if (bFile.exists()) bFile.delete()
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

    @Test
    fun `C1-RED-03 - deleteQuickAlarm uses FLAG_NO_CREATE without creating or updating PI and isolates Legacy and MAIN_V2 PIs`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val shadowAm = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

        // 1. Absent Quick PI: deleteQuickAlarm MUST NOT create or update any PendingIntent
        val absentAlarm = Alarm(
            id = 8101L,
            hour = 12,
            minute = 0,
            label = "Absent PI Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        alarmRepo.save(absentAlarm)

        val absentLookupIntent = Intent(context, AlarmReceiver::class.java)
        val absentReqCode = (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 8101L).toInt()
        assertNull(
            "Precondition: Quick PI must not exist before deleteQuickAlarm",
            PendingIntent.getBroadcast(
                context,
                absentReqCode,
                absentLookupIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        val initialScheduledCount = shadowAm.scheduledAlarms.size

        quickScheduler.deleteQuickAlarm(absentAlarm)

        assertNull(
            "C1-RED-03: deleteQuickAlarm MUST NOT create a Quick PendingIntent when absent",
            PendingIntent.getBroadcast(
                context,
                absentReqCode,
                absentLookupIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        assertEquals(initialScheduledCount, shadowAm.scheduledAlarms.size)
        assertNull(alarmRepo.getById(8101L))

        // 2. Existing Quick PI (id=1 -> rc=80001) + Legacy V1 PI + MAIN_V2 PI (rc=80001)
        val quickId1 = Alarm(
            id = 1L,
            hour = 12,
            minute = 30,
            label = "Armed Quick 1",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 900_000L
        )
        alarmRepo.save(quickId1)
        val armedOutcome = quickScheduler.scheduleQuickAlarm(quickId1)
        assertTrue(armedOutcome is ScheduleOutcome.Armed)

        val quickReqCode1 = (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 1L).toInt()
        assertEquals(80_001, quickReqCode1)
        assertNotNull(
            PendingIntent.getBroadcast(
                context,
                quickReqCode1,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // Arm disjoint Legacy V1 PI (action=ALARM_FIRE, data=null, rc=1) and MAIN_V2 PI (rc=80001, data=alarmclock://...)
        val legacyIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
        }
        val legacyPi = PendingIntent.getBroadcast(
            context,
            1,
            legacyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mainV2Uri = Uri.parse("alarmclock://alarm/issue?alarmId=1&token=v2-token-1")
        val mainV2Intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
            data = mainV2Uri
        }
        val mainV2Pi = PendingIntent.getBroadcast(
            context,
            80_001,
            mainV2Intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull(legacyPi)
        assertNotNull(mainV2Pi)

        quickScheduler.deleteQuickAlarm(quickId1)

        assertNull(
            "Quick PendingIntent MUST be both AlarmManager-cancelled and PendingIntent.cancel()-invalidated",
            PendingIntent.getBroadcast(
                context,
                quickReqCode1,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        assertNotNull(
            "Legacy V1 PendingIntent MUST remain untouched by Quick delete",
            PendingIntent.getBroadcast(
                context,
                1,
                Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        assertNotNull(
            "MAIN_V2 PendingIntent MUST remain untouched by Quick delete even at identical rc=80001",
            PendingIntent.getBroadcast(
                context,
                80_001,
                Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
                    data = mainV2Uri
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    @Test
    fun `C1-RED-04 - concurrent deleteQuickAlarm and reconcileQuickAlarms serialize on reconcileMutex without re-arming or deadlock`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val quickAlarm = Alarm(
            id = 8102L,
            hour = 14,
            minute = 0,
            label = "Contended Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        alarmRepo.save(quickAlarm)
        assertTrue(quickScheduler.scheduleQuickAlarm(quickAlarm) is ScheduleOutcome.Armed)

        val shadowAm = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val enteredReconcile = CompletableDeferred<Unit>()
        val releaseReconcile = CompletableDeferred<Unit>()
        val spyRepo = spyk(alarmRepo)
        coEvery { spyRepo.getPendingQuickAlarms() } coAnswers {
            val list = this@Kno20QuickSchedulingTest.alarmRepo.getPendingQuickAlarms()
            enteredReconcile.complete(Unit)
            releaseReconcile.await()
            list
        }
        val contendedScheduler = QuickAlarmScheduler(context, spyRepo)

        withTimeout(5_000L) {
            val reconcileJob = async(Dispatchers.Default) {
                contendedScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
            }
            enteredReconcile.await()

            val deleteJob = async(Dispatchers.Default) {
                contendedScheduler.deleteQuickAlarm(quickAlarm)
            }

            // Yield briefly to let deleteJob reach reconcileMutex
            kotlinx.coroutines.delay(50L)
            assertFalse(
                "C1-RED-04: deleteQuickAlarm MUST wait on reconcileMutex while reconcileQuickAlarms holds lock",
                deleteJob.isCompleted
            )

            releaseReconcile.complete(Unit)
            reconcileJob.await()
            deleteJob.await()
        }

        assertNull("Deleted Quick alarm MUST be removed from DB", alarmRepo.getById(8102L))
        assertNull(
            "Deleted Quick PI MUST NOT be re-armed after reconcile+delete contention",
            PendingIntent.getBroadcast(
                context,
                (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 8102L).toInt(),
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        val quickPiReqCode = (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 8102L).toInt()
        val remainingQuickAlarms = shadowAm.scheduledAlarms.filter { scheduled ->
            val shadowPi = Shadows.shadowOf(scheduled.operation)
            shadowPi.savedIntent?.component?.className == AlarmReceiver::class.java.name &&
                shadowPi.requestCode == quickPiReqCode
        }
        assertTrue(
            "ShadowAlarmManager MUST NOT retain any scheduled alarm for deleted Quick alarm 8102L",
            remainingQuickAlarms.isEmpty()
        )
    }

    @Test
    fun `OS cancel failure preserves Quick Alarm DB record on deleteQuickAlarm and expired reconcileQuickAlarms`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val futureQuick = Alarm(
            id = 8103L,
            hour = 15,
            minute = 0,
            label = "OS Fail Delete Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        alarmRepo.save(futureQuick)
        assertTrue(quickScheduler.scheduleQuickAlarm(futureQuick) is ScheduleOutcome.Armed)

        val expiredQuick = Alarm(
            id = 8105L,
            hour = 15,
            minute = 5,
            label = "OS Fail Expired Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch - (15L * 60 * 1000L)
        )
        alarmRepo.save(expiredQuick)
        assertTrue(quickScheduler.scheduleQuickAlarm(expiredQuick) is ScheduleOutcome.Armed)

        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel error")
        }
        val spyContext = spyk(context) {
            every { getSystemService(Context.ALARM_SERVICE) } returns failingAm
        }
        val failingScheduler = QuickAlarmScheduler(spyContext, alarmRepo)

        val deleteResult = runCatching { failingScheduler.deleteQuickAlarm(futureQuick) }
        assertNotNull(
            "DB record MUST remain intact when deleteQuickAlarm encounters OS cancel exception",
            alarmRepo.getById(8103L)
        )
        if (deleteResult.isSuccess) {
            assertFalse("deleteQuickAlarm MUST NOT report true when OS cancel fails", (deleteResult.getOrNull() as? Boolean) == true)
        }

        val report = runCatching {
            failingScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        }.getOrNull()
        assertNotNull(
            "Expired Quick alarm DB record MUST remain intact when OS cancel throws",
            alarmRepo.getById(8105L)
        )
        if (report != null) {
            assertFalse(report.expiredAlarms.any { it.id == 8105L })
        }
    }

    @Test
    fun `residual crash window between OS cancel and DB delete allows reconcileQuickAlarms to re-arm future Quick alarm`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val quickAlarm = Alarm(
            id = 8104L,
            hour = 16,
            minute = 0,
            label = "Crash Window Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        alarmRepo.save(quickAlarm)
        assertTrue(quickScheduler.scheduleQuickAlarm(quickAlarm) is ScheduleOutcome.Armed)

        // Simulate crash strictly after OS cancel succeeds and before repository.delete(alarm) commits
        val crashingRepo = spyk(alarmRepo)
        coEvery { crashingRepo.delete(match { it.id == 8104L }) } throws RuntimeException("Simulated process death before DB delete")
        val crashingScheduler = QuickAlarmScheduler(context, crashingRepo)

        val crashResult = runCatching { crashingScheduler.deleteQuickAlarm(quickAlarm) }
        assertTrue("Simulated pre-DB-delete crash should abort deleteQuickAlarm", crashResult.isFailure)

        // OS PI was cancelled, but DB row still exists
        assertNull(
            PendingIntent.getBroadcast(
                context,
                (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 8104L).toInt(),
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        assertNotNull(alarmRepo.getById(8104L))

        // On restart without a delete journal/schema, reconcileQuickAlarms re-arms the surviving future DB record (RESIDUAL window)
        val restartReport = quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertTrue(restartReport.armedFutureAlarms.any { it.id == 8104L })
        assertNotNull(
            PendingIntent.getBroadcast(
                context,
                (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 8104L).toInt(),
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    @Test
    fun `Q1 - cancelQuickPendingIntentOnly cancels Quick PI with zero DB deletion and preserves Normal PI`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val oldQuick66 = Alarm(
            id = 66L,
            hour = 7,
            minute = 0,
            label = "Old Quick 66",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        alarmRepo.save(oldQuick66)
        assertTrue(quickScheduler.scheduleQuickAlarm(oldQuick66) is ScheduleOutcome.Armed)

        // Simulate Replace Restore overwriting id=66 with NEW Normal alarm while OLD Quick PI remains in system_server
        val newNormal66 = Alarm(
            id = 66L,
            hour = 8,
            minute = 30,
            label = "NEW Normal 66",
            isEnabled = true,
            kind = AlarmKind.NORMAL.name
        )
        alarmRepo.save(newNormal66)

        val legacyNormalPi = PendingIntent.getBroadcast(
            context,
            66,
            Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val mainV2Uri = Uri.parse("alarmclock://alarm/issue?alarmId=66&token=v2-token-66")
        val mainV2Pi = PendingIntent.getBroadcast(
            context,
            80_001,
            Intent(context, AlarmReceiver::class.java).apply {
                action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
                data = mainV2Uri
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull(legacyNormalPi)
        assertNotNull(mainV2Pi)

        val cancelled = quickScheduler.cancelQuickPendingIntentOnly(66L)
        assertTrue("Q1: cancelQuickPendingIntentOnly MUST return true when Quick PI cancelled", cancelled)

        val quickReqCode66 = (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 66L).toInt()
        assertNull(
            "Q1: Quick PendingIntent MUST be cancelled",
            PendingIntent.getBroadcast(
                context,
                quickReqCode66,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )

        val dbAlarm = alarmRepo.getById(66L)
        assertNotNull("Q1: NEW Normal 66 DB row MUST NOT be deleted by cancelQuickPendingIntentOnly", dbAlarm)
        assertEquals(AlarmKind.NORMAL.name, dbAlarm!!.kind)
        assertEquals("NEW Normal 66", dbAlarm.label)

        assertNotNull(
            "Q1: Legacy Normal PI MUST remain untouched",
            PendingIntent.getBroadcast(
                context,
                66,
                Intent(context, AlarmReceiver::class.java).apply { action = "com.sysadmindoc.alarmclock.ALARM_FIRE" },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        assertNotNull(
            "Q1: MAIN_V2 PI MUST remain untouched",
            PendingIntent.getBroadcast(
                context,
                80_001,
                Intent(context, AlarmReceiver::class.java).apply {
                    action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
                    data = mainV2Uri
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    @Test
    fun `Q2 - cancelQuickPendingIntentOnly handles absent PI, invalid id, OS exception, CancellationException, and mutex serialization`() = runBlocking {
        // Non-positive alarmIds fail closed
        assertFalse(quickScheduler.cancelQuickPendingIntentOnly(0L))
        assertFalse(quickScheduler.cancelQuickPendingIntentOnly(-10L))

        // Absent Quick PI returns true without creating a PI
        val absentReqCode = (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 999L).toInt()
        assertNull(
            PendingIntent.getBroadcast(
                context,
                absentReqCode,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        assertTrue("Q2: Absent Quick PI must return true without creating PI", quickScheduler.cancelQuickPendingIntentOnly(999L))
        assertNull(
            PendingIntent.getBroadcast(
                context,
                absentReqCode,
                Intent(context, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )

        // Null AlarmManager returns false
        val nullAmContext = spyk(context) {
            every { getSystemService(Context.ALARM_SERVICE) } returns null
        }
        assertFalse(QuickAlarmScheduler(nullAmContext, alarmRepo).cancelQuickPendingIntentOnly(999L))

        // Arm Quick 8200L, then throw RuntimeException vs CancellationException from AlarmManager.cancel
        val nowEpoch = System.currentTimeMillis()
        val armedQuick = Alarm(
            id = 8200L,
            hour = 13,
            minute = 0,
            label = "Quick 8200",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        alarmRepo.save(armedQuick)
        assertTrue(quickScheduler.scheduleQuickAlarm(armedQuick) is ScheduleOutcome.Armed)

        val throwingRuntimeAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel exception")
        }
        val throwingRuntimeContext = spyk(context) {
            every { getSystemService(Context.ALARM_SERVICE) } returns throwingRuntimeAm
        }
        assertFalse(
            "Q2: OS RuntimeException MUST return false",
            QuickAlarmScheduler(throwingRuntimeContext, alarmRepo).cancelQuickPendingIntentOnly(8200L)
        )

        val throwingCancelAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws kotlinx.coroutines.CancellationException("Cancelled")
        }
        val throwingCancelContext = spyk(context) {
            every { getSystemService(Context.ALARM_SERVICE) } returns throwingCancelAm
        }
        var caughtCancellation = false
        try {
            QuickAlarmScheduler(throwingCancelContext, alarmRepo).cancelQuickPendingIntentOnly(8200L)
        } catch (_: kotlinx.coroutines.CancellationException) {
            caughtCancellation = true
        }
        assertTrue("Q2: CancellationException MUST be rethrown", caughtCancellation)

        // Mutex serialization check with reconcileQuickAlarms
        val enteredReconcile = CompletableDeferred<Unit>()
        val releaseReconcile = CompletableDeferred<Unit>()
        val spyRepo = spyk(alarmRepo)
        coEvery { spyRepo.getPendingQuickAlarms() } coAnswers {
            val list = this@Kno20QuickSchedulingTest.alarmRepo.getPendingQuickAlarms()
            enteredReconcile.complete(Unit)
            releaseReconcile.await()
            list
        }
        val contendedScheduler = QuickAlarmScheduler(context, spyRepo)
        withTimeout(5_000L) {
            val reconcileJob = async(Dispatchers.Default) {
                contendedScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
            }
            enteredReconcile.await()
            val cancelOnlyJob = async(Dispatchers.Default) {
                contendedScheduler.cancelQuickPendingIntentOnly(8200L)
            }
            kotlinx.coroutines.delay(50L)
            assertFalse("Q2: cancelQuickPendingIntentOnly MUST wait on reconcileMutex", cancelOnlyJob.isCompleted)
            releaseReconcile.complete(Unit)
            reconcileJob.await()
            assertTrue(cancelOnlyJob.await())
        }
    }

    @Test
    fun `Q3 - reconcileQuickAlarms reports schedule failure, permission blocked, and OS cancel failure in failedAlarms`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()

        // Case A: Future Quick alarm RegistrationFailed (AlarmManager throws on setExactAndAllowWhileIdle)
        val futureFailQuick = Alarm(
            id = 8201L,
            hour = 14,
            minute = 0,
            label = "Future Fail Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        alarmRepo.save(futureFailQuick)

        val failSetAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws RuntimeException("Simulated OS set failure")
        }
        val failSetContext = spyk(context) {
            every { getSystemService(Context.ALARM_SERVICE) } returns failSetAm
        }
        val failSetReport = QuickAlarmScheduler(failSetContext, alarmRepo)
            .reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertTrue("Q3A: Failed schedule MUST NOT appear in armedFutureAlarms", failSetReport.armedFutureAlarms.isEmpty())
        assertEquals(listOf(8201L), failSetReport.failedAlarms.map { it.id })

        // Case B: Future Quick alarm PermissionBlocked
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val permBlockedReport = quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertTrue("Q3B: PermissionBlocked MUST NOT appear in armedFutureAlarms", permBlockedReport.armedFutureAlarms.isEmpty())
        assertEquals(listOf(8201L), permBlockedReport.failedAlarms.map { it.id })
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)

        // Remove 8201L and verify Case C: Expired Quick alarm where OS cancel fails
        alarmRepo.delete(futureFailQuick)
        val expiredFailQuick = Alarm(
            id = 8202L,
            hour = 14,
            minute = 10,
            label = "Expired OS Fail Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch - (15L * 60 * 1000L)
        )
        alarmRepo.save(expiredFailQuick)
        assertTrue(quickScheduler.scheduleQuickAlarm(expiredFailQuick) is ScheduleOutcome.Armed)

        val failCancelAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { cancel(any<PendingIntent>()) } throws RuntimeException("Simulated OS cancel failure")
        }
        val failCancelContext = spyk(context) {
            every { getSystemService(Context.ALARM_SERVICE) } returns failCancelAm
        }
        val failCancelReport = QuickAlarmScheduler(failCancelContext, alarmRepo)
            .reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertTrue("Q3C: Failed expired OS cancel MUST NOT appear in expiredAlarms", failCancelReport.expiredAlarms.isEmpty())
        assertEquals(listOf(8202L), failCancelReport.failedAlarms.map { it.id })
        assertNotNull("Q3C: DB record MUST remain when expired OS cancel fails", alarmRepo.getById(8202L))
    }

    @Test
    fun `CE-B01-A-Quick - active PHASE_PREPARED journal blocks scheduleQuickAlarm and reconcileQuickAlarms without deadlock`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val futureQuick = Alarm(
            id = 8301L,
            hour = 15,
            minute = 0,
            label = "Fenced Future Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        val expiredQuick = Alarm(
            id = 8302L,
            hour = 15,
            minute = 5,
            label = "Fenced Expired Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch - (15L * 60 * 1000L)
        )
        alarmRepo.save(futureQuick)
        alarmRepo.save(expiredQuick)

        val coordinator = com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator(
            context = context,
            database = db,
            preferencesManager = mockk(relaxed = true),
            alarmScheduler = mockk(relaxed = true),
            moshi = com.squareup.moshi.Moshi.Builder().build()
        )
        coordinator.prepareTransaction(
            com.sysadmindoc.alarmclock.data.preferences.AppSettings(),
            emptyList(),
            emptyList()
        )

        val shadowAm = Shadows.shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
        val initialCount = shadowAm.scheduledAlarms.size

        val scheduleOutcome = quickScheduler.scheduleQuickAlarm(futureQuick)
        assertTrue(
            "scheduleQuickAlarm MUST return NoArm(RESTORE_PREPARED_FENCE) during PHASE_PREPARED",
            scheduleOutcome is ScheduleOutcome.NoArm && scheduleOutcome.reason == "RESTORE_PREPARED_FENCE"
        )

        val reconcileReport = withTimeout(2_000L) {
            quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        }
        assertTrue(reconcileReport.armedFutureAlarms.isEmpty())
        assertTrue(reconcileReport.boundedLateRecoveries.isEmpty())
        assertTrue(reconcileReport.expiredAlarms.isEmpty())
        assertTrue(reconcileReport.failedAlarms.isEmpty())
        assertNotNull("Expired quick alarm MUST NOT be mutated/deleted during PHASE_PREPARED fence", alarmRepo.getById(8302L))
        assertEquals(initialCount, shadowAm.scheduledAlarms.size)
    }

    @Test
    fun `CE-B01-TOCTOU-Quick - scheduleQuickAlarm serializes with prepareTransaction and bak PHASE_PREPARED blocks Quick schedulers`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val quickAlarm = Alarm(
            id = 8303L,
            hour = 16,
            minute = 0,
            label = "TOCTOU Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch + 600_000L
        )
        alarmRepo.save(quickAlarm)

        val coordinator = com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator(
            context = context,
            database = db,
            preferencesManager = mockk(relaxed = true),
            alarmScheduler = mockk(relaxed = true),
            moshi = com.squareup.moshi.Moshi.Builder().build()
        )

        val enteredLock = java.util.concurrent.CountDownLatch(1)
        val releaseLock = java.util.concurrent.CountDownLatch(1)
        val barrierQuickScheduler = object : QuickAlarmScheduler(context, alarmRepo) {
            override fun currentBootCount(): Long {
                enteredLock.countDown()
                releaseLock.await(3, java.util.concurrent.TimeUnit.SECONDS)
                return -1L
            }
        }

        val journalFile = java.io.File(context.filesDir, com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.JOURNAL_FILENAME)
        withTimeout(5_000L) {
            val scheduleJob = async(Dispatchers.IO) {
                barrierQuickScheduler.scheduleQuickAlarm(quickAlarm)
            }
            assertTrue(enteredLock.await(3, java.util.concurrent.TimeUnit.SECONDS))

            val prepareJob = async(Dispatchers.Default) {
                coordinator.prepareTransaction(
                    com.sysadmindoc.alarmclock.data.preferences.AppSettings(),
                    emptyList(),
                    emptyList()
                )
            }
            kotlinx.coroutines.delay(80L)
            assertFalse("prepareTransaction MUST wait on recoveryMutex while scheduleQuickAlarm is in-flight", prepareJob.isCompleted)
            assertFalse("Journal MUST NOT enter PHASE_PREPARED mid-Quick-registration", journalFile.exists())

            releaseLock.countDown()
            assertTrue(scheduleJob.await() is ScheduleOutcome.Armed)
            prepareJob.await()
            assertTrue(journalFile.exists())
        }

        // Now move journal to .bak and verify .bak-only PHASE_PREPARED blocks scheduleQuickAlarm & reconcileQuickAlarms
        val bakFile = java.io.File(context.filesDir, "${com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator.JOURNAL_FILENAME}.bak")
        bakFile.writeText(journalFile.readText())
        assertTrue(journalFile.delete())

        val bakOutcome = quickScheduler.scheduleQuickAlarm(quickAlarm.copy(id = 8304L))
        assertTrue(
            "scheduleQuickAlarm MUST return NoArm(RESTORE_PREPARED_FENCE) when .bak holds PHASE_PREPARED",
            bakOutcome is ScheduleOutcome.NoArm && bakOutcome.reason == "RESTORE_PREPARED_FENCE"
        )
        val bakReport = quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertTrue(bakReport.armedFutureAlarms.isEmpty())
    }

    @Test
    fun `Q4 - reconcileQuickAlarms atomically overwrites Quick 66 PI with FLAG_UPDATE_CURRENT, embeds restoreEpoch, and enforces exact 10m boundary`() = runBlocking {
        val nowEpoch = System.currentTimeMillis()
        val t1 = nowEpoch + 600_000L
        val t2 = nowEpoch + 1_200_000L

        db.scheduleAuthorityDao().insertMeta(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                id = 1,
                restoreEpoch = 3L,
                bootstrapState = "COMPLETED"
            )
        )
        val oldQuick66 = Alarm(
            id = 66L,
            hour = 7,
            minute = 0,
            label = "Old Quick 66",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = t1
        )
        alarmRepo.save(oldQuick66)
        assertTrue(quickScheduler.scheduleQuickAlarm(oldQuick66) is ScheduleOutcome.Armed)

        val quickReqCode66 = (QuickAlarmScheduler.REQUEST_CODE_QUICK_BASE + 66L).toInt()
        val piBefore = PendingIntent.getBroadcast(
            context,
            quickReqCode66,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull(piBefore)
        val intentBefore = Shadows.shadowOf(piBefore!!).savedIntent
        assertEquals(t1, intentBefore.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L))
        assertEquals(3L, intentBefore.getLongExtra("restore_epoch", 0L))
        assertTrue(intentBefore.getBooleanExtra("EXTRA_QUICK_ALARM", false))

        // Update Quick 66 to t2 and advance restoreEpoch to 4L, then reconcile
        db.scheduleAuthorityDao().insertMeta(
            com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta(
                id = 1,
                restoreEpoch = 4L,
                bootstrapState = "COMPLETED"
            )
        )
        alarmRepo.save(oldQuick66.copy(label = "New Quick 66", quickTriggerEpochMs = t2))

        val reconcileReport = quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertEquals(listOf(66L), reconcileReport.armedFutureAlarms.map { it.id })

        val piAfter = PendingIntent.getBroadcast(
            context,
            quickReqCode66,
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        assertNotNull("Q4: Quick 66 PI MUST stay registered after FLAG_UPDATE_CURRENT overwrite", piAfter)
        val intentAfter = Shadows.shadowOf(piAfter!!).savedIntent
        assertEquals("Q4: Overwritten Quick 66 PI MUST carry updated t2 trigger", t2, intentAfter.getLongExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 0L))
        assertEquals("Q4: Overwritten Quick 66 PI MUST carry updated restore_epoch=4L", 4L, intentAfter.getLongExtra("restore_epoch", 0L))
        assertTrue("Q4: Overwritten Quick 66 PI MUST carry EXTRA_QUICK_ALARM=true", intentAfter.getBooleanExtra("EXTRA_QUICK_ALARM", false))

        // Clean up 66L and verify exact 10m vs 10m+1ms bounded late recovery boundary
        assertTrue(quickScheduler.deleteQuickAlarm(oldQuick66))

        val exact10mQuick = Alarm(
            id = 67L,
            hour = 8,
            minute = 0,
            label = "Exact 10m Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch - QuickAlarmScheduler.BOUNDED_LATE_RECOVERY_WINDOW_MS
        )
        val over10mQuick = Alarm(
            id = 68L,
            hour = 8,
            minute = 1,
            label = "Over 10m Quick",
            isEnabled = true,
            kind = AlarmKind.QUICK.name,
            quickTriggerEpochMs = nowEpoch - (QuickAlarmScheduler.BOUNDED_LATE_RECOVERY_WINDOW_MS + 1L)
        )
        alarmRepo.save(exact10mQuick)
        alarmRepo.save(over10mQuick)

        val boundaryReport = quickScheduler.reconcileQuickAlarms(ReconcileEntrySource.SYSTEM_RECOVERY, nowEpochMs = nowEpoch)
        assertEquals("Q4: latenessMs == 10m MUST be boundedLate", listOf(67L), boundaryReport.boundedLateRecoveries.map { it.id })
        assertEquals("Q4: latenessMs == 10m + 1ms MUST expire", listOf(68L), boundaryReport.expiredAlarms.map { it.id })
        assertNotNull(alarmRepo.getById(67L))
        assertNull(alarmRepo.getById(68L))
    }
}
