package com.sysadmindoc.alarmclock.worker

import android.app.Application
import android.content.Context
import android.os.Looper
import android.os.UserManager
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import androidx.work.WorkerFactory
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
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import com.sysadmindoc.alarmclock.service.AlarmPostDismissController
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.util.UUID
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
        val wmConfig = Configuration.Builder()
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker {
                    return object : ListenableWorker(appContext, workerParameters) {
                        override fun startWork(): ListenableFuture<Result> =
                            SettableFuture.create<Result>().apply { set(Result.success()) }
                    }
                }
            })
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, wmConfig)
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

    private fun mockWorkInfo(
        id: UUID,
        state: WorkInfo.State,
        tags: Set<String>
    ): WorkInfo = mockk<WorkInfo>(relaxed = true).apply {
        every { this@apply.id } returns id
        every { this@apply.state } returns state
        every { this@apply.tags } returns tags
    }

    private fun okOperation(): Operation {
        val successFuture = SettableFuture.create<Operation.State.SUCCESS>().apply {
            set(Operation.SUCCESS)
        }
        return object : Operation {
            override fun getState(): LiveData<Operation.State> = MutableLiveData(Operation.SUCCESS)
            override fun getResult(): ListenableFuture<Operation.State.SUCCESS> = successFuture
        }
    }

    @Test
    fun `s5e1_01 new work rejects UUID mismatched ENQUEUED RUNNING and BLOCKED WorkInfo`() = runBlocking {
        val commit = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed

        val plan = AlarmPostDismissController.wakeConfirmationPlan(
            alarm = alarm10Min,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            scheduledAt = trig1,
            refireCount = 0,
            stepToken = commit.record.stepToken
        )

        val validTags = setOf(
            WakeConfirmWorker::class.java.name,
            plan.tag,
            WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1),
            "acx_wc_refire_0",
            "acx_wc_token_${commit.record.stepToken}"
        )

        for (state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)) {
            val capturedReq = slot<OneTimeWorkRequest>()
            val mockWm = mockk<WorkManager>(relaxed = true)
            every { mockWm.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), capture(capturedReq)) } returns okOperation()
            every { mockWm.getWorkInfosForUniqueWork(plan.uniqueWorkName) } answers {
                val wrongUuid = UUID.randomUUID()
                assertTrue(wrongUuid != capturedReq.captured.id)
                SettableFuture.create<List<WorkInfo>>().apply {
                    set(listOf(mockWorkInfo(wrongUuid, state, validTags)))
                }
            }

            mockkStatic(WorkManager::class)
            val enqRes = try {
                every { WorkManager.getInstance(any()) } returns mockWm
                WakeConfirmOccurrenceStore.enqueueAndVerifyWakeConfirmWork(
                    context = context,
                    alarmId = 42L,
                    scheduledAt = trig1,
                    plan = plan,
                    initialDelayMs = 600_000L,
                    policy = ExistingWorkPolicy.REPLACE,
                    reuseExistingIfPresent = false
                )
            } finally {
                unmockkStatic(WorkManager::class)
            }
            assertTrue(
                "UUID-mismatched $state WorkInfo MUST be rejected, got $enqRes",
                enqRes is WakeConfirmOccurrenceStore.EnqueueVerificationResult.Failed
            )
            assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
        }
    }

    @Test
    fun `s5e1_02 new work rejects UUID mismatched SUCCEEDED FAILED and CANCELLED WorkInfo and same-UUID FAILED or CANCELLED`() = runBlocking {
        val commit = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed

        val plan = AlarmPostDismissController.wakeConfirmationPlan(
            alarm = alarm10Min,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            scheduledAt = trig1,
            refireCount = 0,
            stepToken = commit.record.stepToken
        )
        val validTags = setOf(
            WakeConfirmWorker::class.java.name,
            plan.tag,
            WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1),
            "acx_wc_refire_0",
            "acx_wc_token_${commit.record.stepToken}"
        )

        // Mismatched UUIDs with terminal states
        for (state in listOf(WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            val capturedReq = slot<OneTimeWorkRequest>()
            val mockWm = mockk<WorkManager>(relaxed = true)
            every { mockWm.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), capture(capturedReq)) } returns okOperation()
            every { mockWm.getWorkInfosForUniqueWork(plan.uniqueWorkName) } answers {
                val unrelatedUuid = UUID.randomUUID()
                assertTrue(unrelatedUuid != capturedReq.captured.id)
                SettableFuture.create<List<WorkInfo>>().apply {
                    set(listOf(mockWorkInfo(unrelatedUuid, state, validTags)))
                }
            }

            mockkStatic(WorkManager::class)
            val enqRes = try {
                every { WorkManager.getInstance(any()) } returns mockWm
                WakeConfirmOccurrenceStore.enqueueAndVerifyWakeConfirmWork(
                    context = context,
                    alarmId = 42L,
                    scheduledAt = trig1,
                    plan = plan,
                    initialDelayMs = 600_000L,
                    policy = ExistingWorkPolicy.REPLACE,
                    reuseExistingIfPresent = false
                )
            } finally {
                unmockkStatic(WorkManager::class)
            }
            assertTrue(
                "Unrelated $state WorkInfo MUST be rejected, got $enqRes",
                enqRes is WakeConfirmOccurrenceStore.EnqueueVerificationResult.Failed
            )
        }

        // Same UUID with FAILED or CANCELLED must also be rejected
        for (badSameState in listOf(WorkInfo.State.FAILED, WorkInfo.State.CANCELLED)) {
            val capturedReq = slot<OneTimeWorkRequest>()
            val mockWm = mockk<WorkManager>(relaxed = true)
            every { mockWm.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), capture(capturedReq)) } returns okOperation()
            every { mockWm.getWorkInfosForUniqueWork(plan.uniqueWorkName) } answers {
                SettableFuture.create<List<WorkInfo>>().apply {
                    set(listOf(mockWorkInfo(capturedReq.captured.id, badSameState, validTags)))
                }
            }

            mockkStatic(WorkManager::class)
            val enqRes = try {
                every { WorkManager.getInstance(any()) } returns mockWm
                WakeConfirmOccurrenceStore.enqueueAndVerifyWakeConfirmWork(
                    context = context,
                    alarmId = 42L,
                    scheduledAt = trig1,
                    plan = plan,
                    initialDelayMs = 600_000L,
                    policy = ExistingWorkPolicy.REPLACE,
                    reuseExistingIfPresent = false
                )
            } finally {
                unmockkStatic(WorkManager::class)
            }
            assertTrue(
                "Same-UUID $badSameState WorkInfo MUST be rejected, got $enqRes",
                enqRes is WakeConfirmOccurrenceStore.EnqueueVerificationResult.Failed
            )
        }
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
    }

    @Test
    fun `s5e1_03 existing work reuse rejects WorkInfo missing occurrence tag even when alarm tag matches`() = runBlocking {
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

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val wm = WorkManager.getInstance(context)
        val legacyOnlyReq = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(120, TimeUnit.SECONDS)
            .addTag("wake_confirm_42")
            .build()
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, legacyOnlyReq).result.get()

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH
        )
        assertEquals(0, res.reusedCount)
        assertEquals(0, res.enqueuedCount)
        assertEquals(1, res.failedCount)
        assertFalse(
            "Missing occurrence tag MUST fail closed and keep enqueueConfirmed=false",
            WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed
        )
    }

    @Test
    fun `s5e1_04 existing work reuse rejects WorkInfo with wrong scheduledAt`() = runBlocking {
        val trig2 = trig1 + 86_400_000L
        val commit = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig2,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig2),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed
        coEvery { alarmEventDao.countDismissedByOccurrence(42L, trig2) } returns 1

        val uniqueName2 = OwnerWorkIdentity.occurrenceUniqueName(42L, trig2, SupportingWorkType.WAKE_CONFIRM)
        val wm = WorkManager.getInstance(context)
        val wrongSchedReq = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(120, TimeUnit.SECONDS)
            .addTag("wake_confirm_42")
            .addTag(WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1))
            .addTag("acx_wc_refire_0")
            .addTag("acx_wc_token_${commit.record.stepToken}")
            .build()
        wm.enqueueUniqueWork(uniqueName2, ExistingWorkPolicy.REPLACE, wrongSchedReq).result.get()

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH
        )
        assertEquals(0, res.reusedCount)
        assertEquals(0, res.enqueuedCount)
        assertEquals(1, res.failedCount)
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig2)!!.enqueueConfirmed)
    }

    @Test
    fun `s5e1_05 existing work reuse rejects WorkInfo with wrong refire step`() = runBlocking {
        val c0 = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed
        assertTrue(
            WakeConfirmOccurrenceStore.consumeRefireStep(context, 42L, trig1, 0, c0.record.stepToken)
                is WakeConfirmOccurrenceStore.RefireConsumeResult.Consumed
        )
        val c1 = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 1,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val wm = WorkManager.getInstance(context)
        val wrongRefireReq = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(120, TimeUnit.SECONDS)
            .addTag("wake_confirm_42")
            .addTag(WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1))
            .addTag("acx_wc_refire_0")
            .addTag("acx_wc_token_${c1.record.stepToken}")
            .build()
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, wrongRefireReq).result.get()

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH
        )
        assertEquals(0, res.reusedCount)
        assertEquals(0, res.enqueuedCount)
        assertEquals(1, res.failedCount)
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
    }

    @Test
    fun `s5e1_06 existing work reuse rejects WorkInfo with stale stepToken`() = runBlocking {
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

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val wm = WorkManager.getInstance(context)
        val staleTokenReq = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(120, TimeUnit.SECONDS)
            .addTag("wake_confirm_42")
            .addTag(WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1))
            .addTag("acx_wc_refire_0")
            .addTag("acx_wc_token_stale-previous-step-token")
            .build()
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, staleTokenReq).result.get()

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH
        )
        assertEquals(0, res.reusedCount)
        assertEquals(0, res.enqueuedCount)
        assertEquals(1, res.failedCount)
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
    }

    @Test
    fun `s5e1_07 valid new work passes exact UUID and identity verification and persists DE enqueueConfirmed and workRequestId`() = runBlocking {
        val commit = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis() - 60_000L
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH,
            alarmIncidentRepository = alarmIncidentRepository
        )
        assertEquals(1, res.enqueuedCount)
        assertEquals(0, res.failedCount)

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val wmInfo = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().single()
        assertEquals(WorkInfo.State.ENQUEUED, wmInfo.state)
        assertTrue(wmInfo.tags.contains(WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1)))
        assertTrue(wmInfo.tags.contains("acx_wc_refire_0"))
        assertTrue(wmInfo.tags.contains("acx_wc_token_${commit.record.stepToken}"))

        val rec = WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!
        assertTrue(rec.enqueueConfirmed)
        val rawPayload = WakeConfirmOccurrenceStore.dePrefs(context)
            .getString(WakeConfirmOccurrenceStore.KEY_LINEAGE_PAYLOAD, null)!!
        val storedReqId = JSONObject(rawPayload).getJSONArray("records").getJSONObject(0).optString("workRequestId", "")
        assertEquals(wmInfo.id.toString(), storedReqId)
    }

    @Test
    fun `s5e1_08 valid existing work with matching occurrence refire step and stepToken is idempotently reused`() = runBlocking {
        val commit = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis() - 60_000L
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val wm = WorkManager.getInstance(context)
        val validExistingReq = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(120, TimeUnit.SECONDS)
            .addTag("wake_confirm_42")
            .addTag(WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1))
            .addTag("acx_wc_refire_0")
            .addTag("acx_wc_token_${commit.record.stepToken}")
            .build()
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, validExistingReq).result.get()

        val res = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH,
            alarmIncidentRepository = alarmIncidentRepository
        )
        assertEquals(1, res.reusedCount)
        assertEquals(0, res.enqueuedCount)
        assertEquals(0, res.failedCount)
        val infoAfter = wm.getWorkInfosForUniqueWork(uniqueName).get().single()
        assertEquals(validExistingReq.id, infoAfter.id)
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
    }

    @Test
    fun `s5e1_09 KEEP retaining unrelated pre-existing work fails closed and preserves DE pending and sibling`() = runBlocking {
        val c42 = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed
        // Sibling occurrence (alarm 43) must never be affected
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 43L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(43L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        )

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val wm = WorkManager.getInstance(context)
        val unrelatedReq = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(300, TimeUnit.SECONDS)
            .addTag("wake_confirm_42")
            .addTag(WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1))
            .build()
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, unrelatedReq).result.get()

        // Path A: direct KEEP call where KEEP retains unrelated work
        val plan = AlarmPostDismissController.wakeConfirmationPlan(
            alarm = alarm10Min,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            scheduledAt = trig1,
            refireCount = 0,
            stepToken = c42.record.stepToken
        )
        val directKeepRes = WakeConfirmOccurrenceStore.enqueueAndVerifyWakeConfirmWork(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            plan = plan,
            initialDelayMs = 600_000L,
            policy = ExistingWorkPolicy.KEEP,
            reuseExistingIfPresent = false
        )
        assertTrue(
            "KEEP retaining unrelated work MUST fail closed, got $directKeepRes",
            directKeepRes is WakeConfirmOccurrenceStore.EnqueueVerificationResult.Failed
        )
        assertEquals(unrelatedReq.id, wm.getWorkInfosForUniqueWork(uniqueName).get().single().id)

        // Path B: recoverPendingEnqueues with KEEP
        val recRes = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.HEALTH_CHECK
        )
        assertEquals(0, recRes.reusedCount)
        assertEquals(0, recRes.enqueuedCount)
        assertEquals(1, recRes.failedCount)
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
        assertNotNull(WakeConfirmOccurrenceStore.findRecord(context, 43L, trig1))
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 43L, trig1)!!.enqueueConfirmed)
    }

    @Test
    fun `s5e1_10 WorkInfo lookup exception preserves DE pending state and sibling occurrences`() = runBlocking {
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
        val siblingCommit = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 43L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(43L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis()
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed

        val mockWm = mockk<WorkManager>(relaxed = true)
        every { mockWm.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } returns okOperation()
        every { mockWm.getWorkInfosForUniqueWork(any()) } throws IllegalStateException("WorkManager SQLite lookup failure")

        mockkStatic(WorkManager::class)
        val retryOk = try {
            every { WorkManager.getInstance(any()) } returns mockWm
            WakeConfirmOccurrenceStore.retryPendingWakeConfirmEnqueue(context, alarm10Min, trig1)
        } finally {
            unmockkStatic(WorkManager::class)
        }
        assertFalse(retryOk)
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
        val siblingRec = WakeConfirmOccurrenceStore.findRecord(context, 43L, trig1)!!
        assertEquals(siblingCommit.record.stepToken, siblingRec.stepToken)
        assertFalse(siblingRec.enqueueConfirmed)
    }

    @Test
    fun `s5e1_11 post-process-death re-verification reconciles matching persisted WorkInfo and rejects UUID mismatch`() = runBlocking {
        // Part A: crash after WorkManager enqueue before markEnqueueConfirmed -> post-process-death recovery reconciles
        val c42 = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis() - 30_000L
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed
        val unique42 = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val wm = WorkManager.getInstance(context)
        val preCrashReq = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(120, TimeUnit.SECONDS)
            .addTag("wake_confirm_42")
            .addTag(WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1))
            .addTag("acx_wc_refire_0")
            .addTag("acx_wc_token_${c42.record.stepToken}")
            .build()
        wm.enqueueUniqueWork(unique42, ExistingWorkPolicy.REPLACE, preCrashReq).result.get()

        val resA = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH
        )
        assertEquals(1, resA.reusedCount)
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)

        // Part B: DE record already bound to expectedUuid, but WorkManager has different UUID with matching tags -> reject
        clearPrefs()
        val c42b = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis() - 30_000L
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed
        val expectedUuid = UUID.randomUUID()
        val mismatchedUuid = UUID.randomUUID()
        val raw = WakeConfirmOccurrenceStore.dePrefs(context)
            .getString(WakeConfirmOccurrenceStore.KEY_LINEAGE_PAYLOAD, null)!!
        val json = JSONObject(raw)
        json.getJSONArray("records").getJSONObject(0).put("workRequestId", expectedUuid.toString())
        synchronized(DirectBootStorageLock.lock) {
            WakeConfirmOccurrenceStore.dePrefs(context)
                .edit().putString(WakeConfirmOccurrenceStore.KEY_LINEAGE_PAYLOAD, json.toString()).commit()
        }

        val matchingTags = setOf(
            WakeConfirmWorker::class.java.name,
            "wake_confirm_42",
            WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1),
            "acx_wc_refire_0",
            "acx_wc_token_${c42b.record.stepToken}"
        )
        val mockWm = mockk<WorkManager>(relaxed = true)
        every { mockWm.getWorkInfosForUniqueWork(unique42) } answers {
            SettableFuture.create<List<WorkInfo>>().apply {
                set(listOf(mockWorkInfo(mismatchedUuid, WorkInfo.State.ENQUEUED, matchingTags)))
            }
        }
        every { mockWm.enqueueUniqueWork(any<String>(), any<ExistingWorkPolicy>(), any<OneTimeWorkRequest>()) } returns okOperation()

        mockkStatic(WorkManager::class)
        val resB = try {
            every { WorkManager.getInstance(any()) } returns mockWm
            WakeConfirmOccurrenceStore.recoverPendingEnqueues(
                context = context,
                alarmRepository = alarmRepository,
                trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH
            )
        } finally {
            unmockkStatic(WorkManager::class)
        }
        assertEquals(0, resB.reusedCount)
        assertEquals(0, resB.enqueuedCount)
        assertEquals(1, resB.failedCount)
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
    }

    @Test
    fun `s5e1_12 Flag OFF legacy compatibility succeeds for enqueue recovery and worker refire authorization`() = runBlocking {
        val gate = SharedOutputAuthorityGate.getInstance(context)
        assertFalse(gate.isEnabled)

        val commit = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true,
            nowMillis = System.currentTimeMillis() - 30_000L
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed

        val recRes = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH
        )
        assertEquals(1, recRes.enqueuedCount)
        assertEquals(0, recRes.failedCount)
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)

        val auth = WakeConfirmOccurrenceStore.authorizeWorkerExecution(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            refireCount = 0,
            workerStepToken = commit.record.stepToken,
            roomDismissChecker = { true }
        )
        assertTrue(auth is WakeConfirmOccurrenceStore.WorkerAuthResult.Authorized)
    }
}
