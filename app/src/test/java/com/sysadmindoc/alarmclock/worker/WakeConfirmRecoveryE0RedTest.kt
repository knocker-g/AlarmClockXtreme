package com.sysadmindoc.alarmclock.worker

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.UserManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.utils.futures.SettableFuture
import androidx.work.testing.WorkManagerTestInitHelper
import com.google.common.util.concurrent.ListenableFuture
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.AlarmEventDao
import com.sysadmindoc.alarmclock.data.local.AlarmIncidentEventDao
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootStorageLock
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakeConfirmRecoveryE0RedTest {

    private lateinit var context: Context
    private lateinit var alarmDatabase: AlarmDatabase
    private lateinit var alarmEventDao: AlarmEventDao
    private lateinit var alarmIncidentEventDao: AlarmIncidentEventDao
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var alarmIncidentRepository: AlarmIncidentRepository
    private val recordedIncidents = CopyOnWriteArrayList<String>()

    private val trig1 = 1_700_000_000_000L

    private val alarm10Min = Alarm(
        id = 42L,
        hour = 7,
        minute = 0,
        label = "Morning Check",
        isEnabled = true,
        repeatDays = setOf(DayOfWeek.MONDAY),
        wakeConfirmEnabled = true,
        wakeConfirmDelayMinutes = 10
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val userManager = context.getSystemService(UserManager::class.java)
        shadowOf(userManager).setUserUnlocked(true)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        clearPrefs()
        recordedIncidents.clear()

        alarmDatabase = mockk(relaxed = true)
        alarmEventDao = mockk(relaxed = true)
        alarmIncidentEventDao = mockk(relaxed = true)
        every { alarmDatabase.alarmEventDao() } returns alarmEventDao
        every { alarmDatabase.alarmIncidentEventDao() } returns alarmIncidentEventDao
        coEvery { alarmEventDao.countDismissedByOccurrence(any(), any()) } returns 1
        coEvery { alarmIncidentEventDao.countDismissedIncidentsByOccurrence(any(), any()) } returns 0

        alarmRepository = mockk(relaxed = true)
        every { alarmRepository.database } returns alarmDatabase
        coEvery { alarmRepository.getById(42L) } returns alarm10Min

        alarmIncidentRepository = mockk(relaxed = true)
        coEvery {
            alarmIncidentRepository.record(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            recordedIncidents.add(arg(6))
            1L
        }
    }

    @After
    fun tearDown() {
        unmockkStatic(WorkManager::class)
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        clearPrefs()
    }

    private fun clearPrefs() {
        context.getSharedPreferences(WakeConfirmOccurrenceStore.CE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(WakeConfirmOccurrenceStore.DE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `e0_01 recoverPendingEnqueues computes remaining delay from original dismissedAt plus delay instead of full delay`() = runBlocking {
        val now = System.currentTimeMillis()
        val dismissedFourMinutesAgo = now - 4 * 60_000L

        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = dismissedFourMinutesAgo
        )

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH,
            alarmIncidentRepository = alarmIncidentRepository
        )
        assertEquals(1, res.enqueuedCount)

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().single()
        val workSpec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())
        assertNotNull(workSpec)
        val initialDelayMs = workSpec!!.initialDelay
        // Original delay was 10 min; 4 min elapsed -> remaining delay MUST be ~6 min (certainly < 7 min, NOT 10 min!)
        assertTrue(
            "Expected remaining delay around 6 minutes (<= 420_000ms), but got ${initialDelayMs}ms",
            initialDelayMs in 300_000L..420_000L
        )
    }

    @Test
    fun `e0_02 elapsed dueAt recovers with zero delay in both recoverPendingEnqueues and retryPendingWakeConfirmEnqueue`() = runBlocking {
        val now = System.currentTimeMillis()
        val dismissedFifteenMinutesAgo = now - 15 * 60_000L

        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = dismissedFifteenMinutesAgo
        )

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.REBOOT,
            alarmIncidentRepository = alarmIncidentRepository
        )
        assertEquals(1, res.enqueuedCount)

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val info = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().single()
        val spec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!
        assertEquals(
            "Elapsed dueAt MUST schedule with 0ms delay instead of reapplying 10 minutes",
            0L,
            spec.initialDelay
        )

        // Also test retryPendingWakeConfirmEnqueue
        clearPrefs()
        WorkManager.getInstance(context).cancelUniqueWork(uniqueName).result.get()
        WorkManager.getInstance(context).pruneWork().result.get()
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = dismissedFifteenMinutesAgo
        )
        assertTrue(WakeConfirmOccurrenceStore.retryPendingWakeConfirmEnqueue(context, alarm10Min, trig1))
        val retryInfo = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get()
            .single { it.state != WorkInfo.State.CANCELLED }
        val retrySpec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(retryInfo.id.toString())!!
        assertEquals(
            "retryPendingWakeConfirmEnqueue with elapsed dueAt MUST schedule with 0ms delay",
            0L,
            retrySpec.initialDelay
        )
    }

    @Test
    fun `e0_03 unknown dismissedAt fails closed without fabricating dueAt from current time`() = runBlocking {
        val unknownTimeJson = """{"schemaVersion":1,"records":[{
            "alarmId":42,"scheduledAt":$trig1,"fireId":"alarm-42-$trig1",
            "parentDismissed":true,"dismissedAt":0,"wakeConfirmEnabled":true,
            "oneShotDisabledAllowed":false,"nextAuthorizedRefire":1,
            "highestClaimedRefire":0,"confirmed":false,"cancelled":false,
            "stepToken":"tok-unknown-dismiss","enqueueConfirmed":false,"legacyConfirmedKeyPreexistedAtDismiss":false
        }]}"""
        synchronized(DirectBootStorageLock.lock) {
            WakeConfirmOccurrenceStore.dePrefs(context)
                .edit()
                .putString(WakeConfirmOccurrenceStore.KEY_LINEAGE_PAYLOAD, unknownTimeJson)
                .commit()
        }

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.HEALTH_CHECK
        )
        assertEquals("Unknown dismissedAt MUST fail closed (0 enqueued)", 0, res.enqueuedCount)
        assertEquals(1, res.failedCount)
        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().isEmpty())
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
    }

    @Test
    fun `e0_04 async WorkManager Operation failure leaves enqueueConfirmed false and pending`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        )

        val realWm = WorkManager.getInstance(context)
        val mockWm = mockk<WorkManager>(relaxed = true)
        val failedFuture = SettableFuture.create<Operation.State.SUCCESS>().apply {
            setException(IllegalStateException("Async WorkManager DB commit failure"))
        }
        val failedOp = object : Operation {
            override fun getState(): LiveData<Operation.State> =
                MutableLiveData(Operation.State.FAILURE(IllegalStateException("Async WorkManager DB commit failure")))
            override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = failedFuture
        }
        every { mockWm.getWorkInfosForUniqueWork(any()) } answers { realWm.getWorkInfosForUniqueWork(firstArg()) }
        every {
            mockWm.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<androidx.work.OneTimeWorkRequest>())
        } returns failedOp

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockWm

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH
        )
        assertEquals(0, res.enqueuedCount)
        assertEquals(1, res.failedCount)
        assertFalse(
            "Async Operation failure MUST preserve enqueueConfirmed=false",
            WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed
        )
    }

    @Test
    fun `e0_05 WorkInfo identity mismatch after enqueue prevents marking enqueueConfirmed true`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        )

        val mockWm = mockk<WorkManager>(relaxed = true)
        val successFuture = SettableFuture.create<Operation.State.SUCCESS>().apply {
            set(Operation.SUCCESS)
        }
        val okOp = object : Operation {
            override fun getState(): LiveData<Operation.State> = MutableLiveData(Operation.SUCCESS)
            override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = successFuture
        }
        val emptyListFuture = SettableFuture.create<List<WorkInfo>>().apply {
            set(emptyList())
        }
        every { mockWm.getWorkInfosForUniqueWork(any()) } returns emptyListFuture
        every {
            mockWm.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<androidx.work.OneTimeWorkRequest>())
        } returns okOp

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns mockWm

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.UNLOCK
        )
        assertEquals(0, res.enqueuedCount)
        assertEquals(1, res.failedCount)
        assertFalse(
            "Missing WorkInfo verification MUST keep enqueueConfirmed=false",
            WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed
        )
    }

    @Test
    fun `e0_06 concurrent App Boot Health recoveries do not duplicate WorkManager ops or incidents and never run on Main thread`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis() - 60_000L
        )

        val realWm = WorkManager.getInstance(context)
        val spyWm = mockk<WorkManager>(relaxed = true)
        val callerThreads = CopyOnWriteArrayList<Boolean>()
        val heldLockDuringWm = CopyOnWriteArrayList<Boolean>()

        every { spyWm.getWorkInfosForUniqueWork(any()) } answers {
            callerThreads.add(Looper.myLooper() == Looper.getMainLooper())
            heldLockDuringWm.add(Thread.holdsLock(DirectBootStorageLock.lock))
            realWm.getWorkInfosForUniqueWork(firstArg())
        }
        every {
            spyWm.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<androidx.work.OneTimeWorkRequest>())
        } answers {
            callerThreads.add(Looper.myLooper() == Looper.getMainLooper())
            heldLockDuringWm.add(Thread.holdsLock(DirectBootStorageLock.lock))
            realWm.enqueueUniqueWork(firstArg(), secondArg(), thirdArg<androidx.work.OneTimeWorkRequest>())
        }

        mockkStatic(WorkManager::class)
        every { WorkManager.getInstance(any()) } returns spyWm

        val jobs = listOf(
            async(Dispatchers.Default) {
                WakeConfirmOccurrenceStore.recoverPendingEnqueues(
                    context, alarmRepository, WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH, alarmIncidentRepository
                )
            },
            async(Dispatchers.Default) {
                WakeConfirmOccurrenceStore.recoverPendingEnqueues(
                    context, alarmRepository, WakeConfirmOccurrenceStore.RecoveryTrigger.REBOOT, alarmIncidentRepository
                )
            },
            async(Dispatchers.Default) {
                WakeConfirmOccurrenceStore.recoverPendingEnqueues(
                    context, alarmRepository, WakeConfirmOccurrenceStore.RecoveryTrigger.HEALTH_CHECK, alarmIncidentRepository
                )
            }
        )
        val results = jobs.awaitAll()
        assertEquals(1, results.sumOf { it.enqueuedCount })
        assertEquals(0, results.sumOf { it.reusedCount })
        assertEquals(1, recordedIncidents.size)
        assertTrue("WorkManager MUST never be invoked on Main thread", callerThreads.none { it })
        assertTrue("WorkManager MUST never be invoked under DE lock", heldLockDuringWm.none { it })
    }
}
