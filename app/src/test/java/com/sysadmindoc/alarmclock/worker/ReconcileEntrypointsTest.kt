package com.sysadmindoc.alarmclock.worker

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReconcileEntrypointsTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepo: AlarmRepository
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var scheduler: AlarmScheduler
    private lateinit var workerParams: WorkerParameters

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

        workerParams = mockk(relaxed = true)
        every { workerParams.inputData } returns Data.EMPTY
        every { workerParams.runAttemptCount } returns 0

        every { preferencesManager.getCachedSettings() } returns AppSettings()
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()

        scheduler = AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = incidentRepo,
            weatherRepository = mockk(relaxed = true)
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `BootRescheduleWorker returns success when all items are successfully armed`() = runBlocking {
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 3,
            blockedCount = 0,
            failedCount = 0,
            noArmCount = 0,
            outcomes = mapOf(
                101L to ScheduleOutcome.Armed(101L, 1000L),
                102L to ScheduleOutcome.Armed(102L, 2000L),
                103L to ScheduleOutcome.Armed(103L, 3000L)
            )
        )

        val worker = BootRescheduleWorker(context, workerParams, mockScheduler)
        val result = worker.doWork()
        assertEquals(Result.success(), result)
    }

    @Test
    fun `BootRescheduleWorker returns retry when batch has registration failures and runAttemptCount is within limit`() = runBlocking {
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 2,
            blockedCount = 0,
            failedCount = 1,
            noArmCount = 0,
            outcomes = mapOf(
                101L to ScheduleOutcome.Armed(101L, 1000L),
                102L to ScheduleOutcome.RegistrationFailed(102L, IllegalStateException("OS Error"))
            )
        )

        every { workerParams.runAttemptCount } returns 1
        val worker = BootRescheduleWorker(context, workerParams, mockScheduler)
        val result = worker.doWork()
        assertEquals(Result.retry(), result)
    }

    @Test
    fun `BootRescheduleWorker returns success when blockedCount is greater than 0 but failedCount is 0`() = runBlocking {
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 0,
            blockedCount = 2,
            failedCount = 0,
            noArmCount = 0,
            outcomes = mapOf(
                101L to ScheduleOutcome.PermissionBlocked(101L, "EXACT_ALARM_PERMISSION_REVOKED"),
                102L to ScheduleOutcome.PermissionBlocked(102L, "EXACT_ALARM_PERMISSION_REVOKED")
            )
        )

        val worker = BootRescheduleWorker(context, workerParams, mockScheduler)
        val result = worker.doWork()
        // Must NOT retry continuously while permission is denied!
        assertEquals(Result.success(), result)
    }

    @Test
    fun `ExactAlarmPermissionRescheduleWorker returns success when permission is restored and batch succeeds`() = runBlocking {
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.rescheduleAllInBatches(forceRecalculate = true) } returns ScheduleOutcome.BatchResult(
            armedCount = 2,
            blockedCount = 0,
            failedCount = 0,
            noArmCount = 0,
            outcomes = mapOf(
                101L to ScheduleOutcome.Armed(101L, 1000L),
                102L to ScheduleOutcome.Armed(102L, 2000L)
            )
        )

        val worker = ExactAlarmPermissionRescheduleWorker(context, workerParams, mockScheduler)
        val result = worker.doWork()
        assertEquals(Result.success(), result)
    }

    @Test
    fun `BootRescheduleWorker stops retrying when runAttemptCount reaches MAX_ATTEMPTS`() = runBlocking {
        val mockScheduler = mockk<AlarmScheduler>(relaxed = true)
        coEvery { mockScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 1,
            blockedCount = 0,
            failedCount = 1,
            noArmCount = 0,
            outcomes = mapOf(
                101L to ScheduleOutcome.Armed(101L, 1000L),
                102L to ScheduleOutcome.RegistrationFailed(102L, IllegalStateException("OS Error"))
            )
        )

        every { workerParams.runAttemptCount } returns 3 // Max attempts reached
        val worker = BootRescheduleWorker(context, workerParams, mockScheduler)
        val result = worker.doWork()
        // Stops retrying when max attempts reached
        assertEquals(Result.success(), result)
    }

    @Test
    fun `BatchResult accurately classifies mixed outcome batch`() {
        val batch = ScheduleOutcome.BatchResult(
            armedCount = 1,
            blockedCount = 1,
            failedCount = 1,
            noArmCount = 1,
            outcomes = mapOf(
                201L to ScheduleOutcome.Armed(201L, 1000L),
                202L to ScheduleOutcome.PermissionBlocked(202L, "EXACT_ALARM_PERMISSION_REVOKED"),
                203L to ScheduleOutcome.RegistrationFailed(203L, IllegalStateException("OS Fail")),
                204L to ScheduleOutcome.NoArm(204L, "DISABLED")
            )
        )

        assertEquals(4, batch.totalProcessed)
        assertEquals(1, batch.armedCount)
        assertEquals(1, batch.blockedCount)
        assertEquals(1, batch.failedCount)
        assertEquals(1, batch.noArmCount)
        org.junit.Assert.assertFalse("Batch with failures is not full success", batch.isFullSuccess)
    }
}
