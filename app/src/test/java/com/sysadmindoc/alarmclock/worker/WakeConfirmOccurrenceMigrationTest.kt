package com.sysadmindoc.alarmclock.worker

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import androidx.work.workDataOf
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.AlarmEventDao
import com.sysadmindoc.alarmclock.data.local.AlarmIncidentEventDao
import com.sysadmindoc.alarmclock.data.local.DirectBootOccurrenceReceiptDao
import com.sysadmindoc.alarmclock.data.local.entity.AlarmEvent
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.DirectBootStorageLock
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import com.sysadmindoc.alarmclock.service.AlarmPostDismissController
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.service.AlarmServiceCallbackPolicy
import com.sysadmindoc.alarmclock.ui.alarmfiring.WakeConfirmActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class WakeConfirmOccurrenceMigrationTest {

    private lateinit var context: Context
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var alarmDatabase: AlarmDatabase
    private lateinit var alarmEventDao: AlarmEventDao
    private lateinit var alarmIncidentEventDao: AlarmIncidentEventDao
    private lateinit var receiptDao: DirectBootOccurrenceReceiptDao
    private lateinit var alarmEventRepository: AlarmEventRepository
    private lateinit var alarmIncidentRepository: AlarmIncidentRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var gate: SharedOutputAuthorityGate

    private val recordedIncidents = CopyOnWriteArrayList<String>()
    private val recordedEvents = CopyOnWriteArrayList<AlarmEvent>()

    private val trig1 = 1_700_000_000_000L
    private val trig2 = trig1 + 86_400_000L

    private val recurringAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 0,
        label = "Wake Up",
        isEnabled = true,
        repeatDays = setOf(java.time.DayOfWeek.MONDAY, java.time.DayOfWeek.TUESDAY),
        wakeConfirmEnabled = true,
        wakeConfirmDelayMinutes = 1
    )

    private val oneShotDisabledAlarm = Alarm(
        id = 55L,
        hour = 7,
        minute = 30,
        label = "OneShot Alarm",
        isEnabled = false,
        repeatDays = emptySet(),
        specificDate = "",
        wakeConfirmEnabled = true,
        wakeConfirmDelayMinutes = 1
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AlarmServiceCallbackPolicy.resetState()
        AlarmService.activeAlarm.set(null)
        DirectBootLegacyClaimStore.resetSeamsForTesting()
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        clearPrefs()

        recordedIncidents.clear()
        recordedEvents.clear()

        alarmDatabase = mockk(relaxed = true)
        alarmEventDao = mockk(relaxed = true)
        alarmIncidentEventDao = mockk(relaxed = true)
        receiptDao = mockk(relaxed = true)
        every { alarmDatabase.alarmEventDao() } returns alarmEventDao
        every { alarmDatabase.alarmIncidentEventDao() } returns alarmIncidentEventDao
        every { alarmDatabase.directBootOccurrenceReceiptDao() } returns receiptDao

        alarmRepository = mockk(relaxed = true)
        every { alarmRepository.database } returns alarmDatabase
        coEvery { alarmRepository.getById(42L) } returns recurringAlarm
        coEvery { alarmRepository.getById(55L) } returns oneShotDisabledAlarm

        alarmEventRepository = mockk(relaxed = true)
        coEvery { alarmEventRepository.record(any()) } coAnswers {
            val ev = firstArg<AlarmEvent>()
            recordedEvents.add(ev)
            1L
        }
        every { alarmEventRepository.recordAsync(any()) } answers {
            val ev = firstArg<AlarmEvent>()
            recordedEvents.add(ev)
        }

        alarmIncidentRepository = mockk(relaxed = true)
        coEvery {
            alarmIncidentRepository.record(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            val reason = arg<String>(6)
            recordedIncidents.add(reason)
            1L
        }
        every {
            alarmIncidentRepository.recordAsync(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            val reason = arg<String>(6)
            recordedIncidents.add(reason)
        }

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()
        every { preferencesManager.isSettingsLoaded } returns true
        every { preferencesManager.enqueueActiveAlarmUpdate(any(), any(), any()) } answers {
            val ack = arg<CompletableDeferred<PreferencesManager.ActiveAlarmUpdateResult>?>(2)
            ack?.complete(PreferencesManager.ActiveAlarmUpdateResult.COMMITTED)
            true
        }

        alarmScheduler = mockk(relaxed = true)
        coEvery { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any(), any(), any()) } returns ScheduleOutcome.Armed(42L, trig1 + 600_000L)

        gate = SharedOutputAuthorityGate(
            bootCount = 10L,
            processSessionToken = "proc-s5d-test"
        ).apply {
            isEnabled = false
        }
    }

    private val serviceControllers = mutableListOf<org.robolectric.android.controller.ServiceController<AlarmService>>()

    @After
    fun tearDown() {
        serviceControllers.forEach { runCatching { it.destroy() } }
        serviceControllers.clear()
        DirectBootLegacyClaimStore.resetSeamsForTesting()
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        AlarmServiceCallbackPolicy.resetState()
        AlarmService.activeAlarm.set(null)
        gate.arbiter.clearForTesting()
        clearPrefs()
    }

    private fun clearPrefs() {
        context.getSharedPreferences("wake_confirm", Context.MODE_PRIVATE).edit().clear().commit()
        DirectBootLegacyClaimStore.prefs(context).edit().clear().commit()
        val deCtx = context.createDeviceProtectedStorageContext()
        deCtx.getSharedPreferences("wake_confirm_de_lineage", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun createInjectedService(): AlarmService {
        val controller = Robolectric.buildService(AlarmService::class.java)
        serviceControllers.add(controller)
        val service = controller.get()
        service.repository = alarmRepository
        service.alarmScheduler = alarmScheduler
        service.eventRepository = alarmEventRepository
        service.alarmIncidentRepository = alarmIncidentRepository
        service.preferencesManager = preferencesManager
        service.webhookService = mockk(relaxed = true)
        service.wearNextAlarmBridge = mockk(relaxed = true)
        service.dismissActionExecutor = mockk(relaxed = true)
        service.weatherRepository = mockk(relaxed = true)
        service.calendarRepository = mockk(relaxed = true)
        service.outputAuthorityGate = gate
        return service
    }

    private suspend fun awaitLatchWithIdle(
        latch: CompletableDeferred<Unit>,
        timeoutMs: Long = 3000L
    ) {
        withTimeoutOrNull(timeoutMs) {
            while (!latch.isCompleted) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                delay(10L)
            }
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private fun buildWorker(
        alarmId: Long,
        scheduledAt: Long,
        fireId: String = AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt),
        refireCount: Int = 0,
        stepToken: String? = null,
        confirmWaitMsOverride: Long? = null,
        pollIntervalMsOverride: Long? = null,
        authorityGateOverride: SharedOutputAuthorityGate? = null
    ): WakeConfirmWorker {
        val resolvedToken = stepToken ?: WakeConfirmOccurrenceStore.findRecord(context, alarmId, scheduledAt)?.stepToken.orEmpty()
        val data = workDataOf(
            WakeConfirmWorker.KEY_ALARM_ID to alarmId,
            WakeConfirmWorker.KEY_ALARM_FIRE_ID to fireId,
            WakeConfirmWorker.KEY_SCHEDULED_AT to scheduledAt,
            WakeConfirmWorker.KEY_REFIRE_COUNT to refireCount,
            WakeConfirmWorker.KEY_STEP_TOKEN to resolvedToken
        )
        return TestListenableWorkerBuilder<WakeConfirmWorker>(context)
            .setInputData(data)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = WakeConfirmWorker(
                    context = appContext,
                    workerParams = workerParameters,
                    alarmRepository = alarmRepository,
                    preferencesManager = preferencesManager,
                    alarmIncidentRepository = alarmIncidentRepository
                ).apply {
                    this.confirmWaitMsOverride = confirmWaitMsOverride
                    this.pollIntervalMsOverride = pollIntervalMsOverride
                    this.authorityGateOverride = authorityGateOverride
                }
            })
            .build()
    }

    private fun startAndDismiss(
        service: AlarmService,
        alarm: Alarm,
        scheduledAt: Long,
        refireCount: Int = 0,
        claimNonce: String? = null
    ) = runBlocking {
        val fireId = AlarmIncidentEvent.fireIdFor(alarm.id, scheduledAt)
        val startDone = CompletableDeferred<Unit>()
        service.testStartAlarmCompletionLatch = startDone
        service.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_START_ALARM
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireId)
                if (refireCount > 0) {
                    putExtra(AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT, refireCount)
                }
                claimNonce?.let { putExtra(AlarmService.EXTRA_CLAIM_NONCE, it) }
            },
            0,
            1
        )
        awaitLatchWithIdle(startDone)
        val dismissDone = CompletableDeferred<Unit>()
        service.testOnStopSelfCalled = { dismissDone.complete(Unit) }
        service.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarm.id)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireId)
            },
            0,
            2
        )
        awaitLatchWithIdle(dismissDone)
    }

    // 1. Same Alarm ID with two different scheduledAt occurrences must be strictly isolated
    @Test
    fun `01 two occurrences same alarmId different scheduledAt have isolated uniqueWorkName and notifications`() {
        val plan1 = AlarmPostDismissController.wakeConfirmationPlan(
            alarm = recurringAlarm,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            scheduledAt = trig1,
            refireCount = 0
        )
        val plan2 = AlarmPostDismissController.wakeConfirmationPlan(
            alarm = recurringAlarm,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig2),
            scheduledAt = trig2,
            refireCount = 0
        )

        assertNotEquals(
            "Different occurrences of same alarmId MUST NOT share WorkManager uniqueWorkName",
            plan1.uniqueWorkName,
            plan2.uniqueWorkName
        )
        assertEquals(
            OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM),
            plan1.uniqueWorkName
        )
        assertEquals(
            OwnerWorkIdentity.occurrenceUniqueName(42L, trig2, SupportingWorkType.WAKE_CONFIRM),
            plan2.uniqueWorkName
        )

        val notif1 = WakeConfirmWorker.notificationId(42L, trig1)
        val notif2 = WakeConfirmWorker.notificationId(42L, trig2)
        assertNotEquals("Different occurrences MUST have distinct notification IDs", notif1, notif2)
        assertTrue(notif1 in 500_000 until 600_000)
        assertTrue(notif2 in 500_000 until 600_000)
    }

    // 2. Stale legacy confirmed_$alarmId key from previous day must not confirm new occurrence
    @Test
    fun `02 stale legacy confirmed key from previous occurrence does not confirm new occurrence`() = runBlocking {
        // Seed stale pre-existing legacy key BEFORE trig2 is dismissed (e.g. left over from yesterday)
        val prefs = context.getSharedPreferences("wake_confirm", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("confirmed_42", true).commit()

        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig2)

        // Simulate late confirmation tap from WakeConfirmActivity belonging to trig1
        assertTrue(WakeConfirmActivity.handleConfirmAwake(context, 42L, trig1))
        assertTrue("WakeConfirmActivity MUST write occurrence-scoped confirmed_42_$trig1 key", prefs.getBoolean("confirmed_42_${trig1}", false))

        val worker2 = buildWorker(
            42L,
            trig2,
            confirmWaitMsOverride = 120L,
            pollIntervalMsOverride = 20L
        )
        val result = withTimeoutOrNull(800L) {
            worker2.doWork()
        }

        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(
            "trig2 worker MUST NOT treat trig1 confirmation or stale un-scoped legacy key as confirmed for trig2",
            recordedIncidents.contains("WAKE_CONFIRM_CONFIRMED")
        )
        assertTrue(
            "trig2 worker MUST time out when only trig1/stale legacy key exists",
            recordedIncidents.contains("WAKE_CONFIRM_TIMED_OUT")
        )
    }

    // 3. Delayed old worker for trig1 must not cancel trig2 notification or clear trig2 confirmation
    @Test
    fun `03 delayed stale worker for trig1 does not cancel trig2 prompt notification or clear trig2 confirmation`() = runBlocking {
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig2)

        val nm = context.getSystemService(NotificationManager::class.java)
        val trig2NotifId = WakeConfirmWorker.notificationId(42L, trig2)
        nm.notify(trig2NotifId, Notification())
        assertTrue(shadowOf(nm).getNotification(trig2NotifId) != null)

        val worker1 = buildWorker(42L, trig1, refireCount = 0, stepToken = "stale-token")
        val result = withTimeoutOrNull(800L) {
            worker1.doWork()
        }

        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(
            "Stale trig1 worker MUST NOT post a prompt when trig2 has superseded",
            recordedIncidents.contains("WAKE_CONFIRM_PROMPT_POSTED")
        )
        assertNotNull(
            "Stale trig1 worker MUST NOT cancel trig2 notification",
            shadowOf(nm).getNotification(trig2NotifId)
        )
    }

    // 4. Worker executing before dismiss commit must fail closed without prompt or refire
    @Test
    fun `04 worker executed before dismiss is committed fails closed without posting prompt or refiring`() = runBlocking {
        val worker = buildWorker(42L, trig1, refireCount = 0, stepToken = "")
        val result = withTimeoutOrNull(800L) {
            worker.doWork()
        }

        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(
            "Worker without committed dismiss MUST NOT request or post WakeConfirm prompt",
            recordedIncidents.contains("WAKE_CONFIRM_PROMPT_REQUESTED")
        )
        assertNull(
            "Worker without committed dismiss MUST NOT start AlarmService",
            shadowOf(context as Application).nextStartedService
        )
    }

    // 5. Synchronous Room dismiss commit failure must abort DE lineage and WakeConfirmWorker enqueue
    @Test
    fun `05 dismiss commit failure aborts WakeConfirmWorker enqueue`() = runBlocking {
        coEvery { alarmEventRepository.record(any()) } throws IllegalStateException("Simulated Room dismiss commit failure")

        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1)

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosByTag("wake_confirm_42").get()
        assertTrue(
            "WakeConfirmWorker MUST NOT be enqueued when synchronous Room dismiss persistence fails, but found: $infos",
            infos.isEmpty()
        )
        assertNull(
            "DE WakeConfirm lineage MUST NOT be committed when Room dismiss fails",
            WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)
        )
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_ABORTED_DISMISS_COMMIT_FAILED"))
    }

    // 6. DE lineage readback failure must abort WakeConfirmWorker enqueue
    @Test
    fun `06 DE lineage readback failure aborts WakeConfirmWorker enqueue`() = runBlocking {
        WakeConfirmOccurrenceStore.deReadbackSeam = { null }
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1)

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosByTag("wake_confirm_42").get()
        assertTrue(
            "WakeConfirmWorker MUST NOT be enqueued when DE lineage readback fails",
            infos.isEmpty()
        )
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_DE_LINEAGE_COMMIT_FAILED"))
    }

    // 7. Enqueue failure preserves durable DE lineage with enqueueConfirmed=false and succeeds on retry
    @Test
    fun `07 enqueue failure preserves durable stepToken and enqueueConfirmed=false and succeeds on retry`() = runBlocking {
        val service = createInjectedService()
        io.mockk.mockkStatic(WorkManager::class)
        try {
            every { WorkManager.getInstance(any()) } throws IllegalStateException("Simulated WorkManager enqueue failure")
            runCatching { startAndDismiss(service, recurringAlarm, trig1) }
        } finally {
            io.mockk.unmockkStatic(WorkManager::class)
        }

        val pendingRec = WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)
        assertNotNull("Durable DE WakeConfirm lineage MUST be committed BEFORE WorkManager enqueue", pendingRec)
        assertFalse("enqueueConfirmed MUST remain false after failed enqueue", pendingRec!!.enqueueConfirmed)
        assertTrue("stepToken MUST remain durable for retry", pendingRec.stepToken.isNotBlank())
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_ENQUEUE_FAILED_PENDING_RETRY"))

        // Retry outside locks using preserved stepToken
        assertTrue(WakeConfirmOccurrenceStore.retryPendingWakeConfirmEnqueue(context, recurringAlarm, trig1))
        val confirmedRec = WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)
        assertTrue("enqueueConfirmed MUST become true after retry", confirmedRec!!.enqueueConfirmed)
        assertEquals("Retry MUST preserve original stepToken", pendingRec.stepToken, confirmedRec.stepToken)

        val wm = WorkManager.getInstance(context)
        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val infos = wm.getWorkInfosForUniqueWork(uniqueName).get()
        assertTrue(infos.any { it.state == WorkInfo.State.ENQUEUED })
    }

    // 8. Process death during legacy confirmed_$alarmId migration preserves confirmation safely,
    //    while pre-existing legacy key is rejected fail-closed.
    @Test
    fun `08 process death during legacy confirmed key migration preserves occurrence confirmation safely`() = runBlocking {
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1)

        // Simulate legacy activity writing confirmed_42 AFTER dismiss (legacyConfirmedKeyPreexistedAtDismiss == false)
        val prefs = context.getSharedPreferences("wake_confirm", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("confirmed_42", true).commit()

        val worker = buildWorker(42L, trig1, refireCount = 0)
        val res = withTimeoutOrNull(800L) { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(), res)
        assertTrue(
            "Migration MUST commit occurrence-scoped confirmed_42_${trig1} key",
            prefs.getBoolean("confirmed_42_${trig1}", false)
        )
        assertFalse(
            "Legacy confirmed_42 key MUST be removed only after occurrence key is committed",
            prefs.contains("confirmed_42")
        )
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.confirmed)

        // Contrast: pre-existing legacy key BEFORE dismiss must fail closed
        clearPrefs()
        recordedIncidents.clear()
        prefs.edit().putBoolean("confirmed_42", true).commit()
        val service2 = createInjectedService()
        startAndDismiss(service2, recurringAlarm, trig2)
        val workerPre = buildWorker(
            42L,
            trig2,
            refireCount = 0,
            confirmWaitMsOverride = 80L,
            pollIntervalMsOverride = 20L
        )
        withTimeoutOrNull(800L) { workerPre.doWork() }
        assertFalse(
            "Pre-existing legacy confirmed_42 from before dismiss MUST NOT confirm trig2",
            recordedIncidents.contains("WAKE_CONFIRM_CONFIRMED")
        )
    }

    // 9. RefireCount 1..3 monotonic progression in WakeConfirmWorker + cap at 3
    @Test
    fun `09 refire 1 to 3 monotonic progression enforces stepToken and stops at cap 3`() = runBlocking {
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1, refireCount = 0)

        // Step 0 -> timeouts -> consumes stepToken -> requests refire 1
        val w0 = buildWorker(42L, trig1, refireCount = 0, confirmWaitMsOverride = 60L, pollIntervalMsOverride = 10L)
        assertEquals(ListenableWorker.Result.success(), w0.doWork())
        val intent1 = shadowOf(context as Application).nextStartedService
        assertNotNull("Timeout at refireCount=0 MUST start refire 1", intent1)
        assertEquals(1, intent1.getIntExtra(AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT, -1))

        // Refire 1 dismissed -> refireCount=1 scheduled -> timeouts -> requests refire 2
        val svc1 = createInjectedService()
        startAndDismiss(svc1, recurringAlarm, trig1, refireCount = 1)
        val w1 = buildWorker(42L, trig1, refireCount = 1, confirmWaitMsOverride = 60L, pollIntervalMsOverride = 10L)
        assertEquals(ListenableWorker.Result.success(), w1.doWork())
        val intent2 = shadowOf(context as Application).nextStartedService
        assertNotNull("Timeout at refireCount=1 MUST start refire 2", intent2)
        assertEquals(2, intent2.getIntExtra(AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT, -1))

        // Refire 2 dismissed -> refireCount=2 scheduled -> timeouts -> requests refire 3
        val svc2 = createInjectedService()
        startAndDismiss(svc2, recurringAlarm, trig1, refireCount = 2)
        val w2 = buildWorker(42L, trig1, refireCount = 2, confirmWaitMsOverride = 60L, pollIntervalMsOverride = 10L)
        assertEquals(ListenableWorker.Result.success(), w2.doWork())
        val intent3 = shadowOf(context as Application).nextStartedService
        assertNotNull("Timeout at refireCount=2 MUST start refire 3", intent3)
        assertEquals(3, intent3.getIntExtra(AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT, -1))

        // Refire 3 dismissed -> refireCount=3 scheduled -> timeouts -> cap reached, NO refire 4
        val svc3 = createInjectedService()
        startAndDismiss(svc3, recurringAlarm, trig1, refireCount = 3)
        recordedIncidents.clear()
        val w3 = buildWorker(42L, trig1, refireCount = 3, confirmWaitMsOverride = 60L, pollIntervalMsOverride = 10L)
        assertEquals(ListenableWorker.Result.success(), w3.doWork())
        assertNull("Cap 3 MUST NOT start a 4th refire", shadowOf(context as Application).nextStartedService)
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_REFIRE_CAP_REACHED"))
    }

    // 10. RefireCount rewind to 0 or >= 4 must be rejected immediately fail-closed
    @Test
    fun `10 refireCount rewind or exceeding max 3 is rejected immediately before prompt or refire`() = runBlocking {
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1)

        val rewindWorker = buildWorker(42L, trig1, refireCount = -1)
        val rewindRes = withTimeoutOrNull(800L) { rewindWorker.doWork() }
        assertEquals(ListenableWorker.Result.success(), rewindRes)
        assertFalse(
            "Negative refireCount MUST NOT post prompt or refire",
            recordedIncidents.contains("WAKE_CONFIRM_PROMPT_REQUESTED")
        )

        recordedIncidents.clear()

        val overCapWorker = buildWorker(42L, trig1, refireCount = 4)
        val overCapRes = withTimeoutOrNull(800L) { overCapWorker.doWork() }
        assertEquals(ListenableWorker.Result.success(), overCapRes)
        assertFalse(
            "refireCount > 3 MUST NOT post prompt",
            recordedIncidents.contains("WAKE_CONFIRM_PROMPT_REQUESTED")
        )
    }

    // 11. Reusing same stepToken / worker execution must never produce duplicate refire
    @Test
    fun `11 reusing worker stepToken or replaying same worker execution fails closed`() = runBlocking {
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1)

        val issuedToken = WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.stepToken
        assertTrue(issuedToken.isNotBlank())

        // First run times out and consumes issuedToken -> starts refire 1
        val worker1 = buildWorker(
            42L,
            trig1,
            refireCount = 0,
            stepToken = issuedToken,
            confirmWaitMsOverride = 60L,
            pollIntervalMsOverride = 10L
        )
        assertEquals(ListenableWorker.Result.success(), worker1.doWork())
        val firstIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("First execution must trigger refire 1", firstIntent)

        // Second execution reusing same stepToken MUST fail closed without duplicate refire
        recordedIncidents.clear()
        val replayWorker = buildWorker(
            42L,
            trig1,
            refireCount = 0,
            stepToken = issuedToken,
            confirmWaitMsOverride = 60L,
            pollIntervalMsOverride = 10L
        )
        assertEquals(ListenableWorker.Result.success(), replayWorker.doWork())
        assertNull("Replayed stepToken MUST NOT start AlarmService again", shadowOf(context as Application).nextStartedService)
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_UNAUTHORIZED"))
    }

    // 12. Disabled ONE_TIME alarm legitimately refires 1..3 in AlarmService when WakeConfirm lineage is active
    @Test
    fun `12 disabled ONE_TIME alarm legitimately starts in AlarmService for authorized WakeConfirm refire 1`() = runBlocking {
        val service = createInjectedService()
        val enabledOneShot = oneShotDisabledAlarm.copy(isEnabled = true)
        coEvery { alarmRepository.getById(55L) } returns enabledOneShot
        startAndDismiss(service, enabledOneShot, trig1)

        // Timeout worker 0 to advance DE lineage to highestClaimedRefire=1
        val w0 = buildWorker(55L, trig1, refireCount = 0, confirmWaitMsOverride = 60L, pollIntervalMsOverride = 10L)
        w0.doWork()
        assertNotNull(shadowOf(context as Application).nextStartedService)

        // Room now reflects disabled ONE_TIME alarm after handleAlarmFired()
        coEvery { alarmRepository.getById(55L) } returns oneShotDisabledAlarm
        recordedIncidents.clear()

        // Start refire 1 in AlarmService -> MUST be authorized by active WakeConfirm lineage
        val refireService = createInjectedService()
        val refireStartDone = CompletableDeferred<Unit>()
        refireService.testStartAlarmCompletionLatch = refireStartDone
        refireService.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_START_ALARM
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 55L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, trig1)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, AlarmIncidentEvent.fireIdFor(55L, trig1))
                putExtra(AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT, 1)
            },
            0,
            3
        )
        awaitLatchWithIdle(refireStartDone)

        assertFalse(
            "Disabled ONE_TIME alarm during authorized WakeConfirm refire 1 MUST NOT fail with ALARM_DISABLED_START_REJECTED",
            recordedIncidents.contains("ALARM_DISABLED_START_REJECTED")
        )
        assertTrue("Authorized ONE_TIME refire 1 MUST start audio", refireService.testAudioStarted.get())
    }

    // 13. Snooze cancels only target occurrence WakeConfirm work and invalidates its lineage
    @Test
    fun `13 snooze cancels only target occurrence WakeConfirm and keeps other occurrence intact`() = runBlocking {
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1)
        startAndDismiss(service, recurringAlarm, trig2)

        // Advance trig1 to refire 1 so AlarmService accepts refire 1 start
        buildWorker(42L, trig1, refireCount = 0, confirmWaitMsOverride = 60L, pollIntervalMsOverride = 10L).doWork()

        val wm = WorkManager.getInstance(context)
        val work1Name = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val work2Name = OwnerWorkIdentity.occurrenceUniqueName(42L, trig2, SupportingWorkType.WAKE_CONFIRM)

        // Start refire 1 for trig1, then user snoozes trig1
        val snoozeService = createInjectedService()
        val startDone = CompletableDeferred<Unit>()
        snoozeService.testStartAlarmCompletionLatch = startDone
        snoozeService.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_START_ALARM
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, trig1)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, AlarmIncidentEvent.fireIdFor(42L, trig1))
                putExtra(AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT, 1)
            },
            0,
            4
        )
        awaitLatchWithIdle(startDone)

        val snoozeDone = CompletableDeferred<Unit>()
        snoozeService.testOnSnoozeActionCompletionLatch = snoozeDone
        snoozeService.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, trig1)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, AlarmIncidentEvent.fireIdFor(42L, trig1))
            },
            0,
            5
        )
        awaitLatchWithIdle(snoozeDone)

        val infos1 = wm.getWorkInfosForUniqueWork(work1Name).get()
        val infos2 = wm.getWorkInfosForUniqueWork(work2Name).get()
        assertTrue(
            "Target occurrence trig1 WakeConfirm work MUST be cancelled on snooze (found $infos1)",
            infos1.isEmpty() || infos1.all { it.state == WorkInfo.State.CANCELLED }
        )
        assertTrue(
            "Target occurrence trig1 DE lineage MUST be marked cancelled on snooze",
            WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.cancelled
        )
        assertTrue(
            "Separate occurrence trig2 WakeConfirm work MUST remain ENQUEUED (found $infos2)",
            infos2.any { it.state == WorkInfo.State.ENQUEUED }
        )
        assertFalse(
            "Separate occurrence trig2 DE lineage MUST remain uncancelled",
            WakeConfirmOccurrenceStore.findRecord(context, 42L, trig2)!!.cancelled
        )
    }

    // 14. Worker retry vs new Primary fire must not collide or lose trig2 schedule
    @Test
    fun `14 worker retry on trig1 does not collide with new primary fire on trig2`() = runBlocking {
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1)
        startAndDismiss(service, recurringAlarm, trig2)

        val wm = WorkManager.getInstance(context)
        val allTagInfos = wm.getWorkInfosByTag("wake_confirm_42").get()
        val activeInfos = allTagInfos.filter { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        assertEquals(
            "Both trig1 and trig2 WakeConfirm workers must have distinct WorkManager entries without overwriting each other",
            2,
            activeInfos.size
        )
    }

    // 15. Flag OFF maintains legacy output contract without GateDisabled failure,
    //     and pre-S5-D worker (blank stepToken) works ONLY when Room has persisted dismiss.
    @Test
    fun `15 flagOff preserves legacy output contract and supports pre-S5-D worker with Room dismiss proof`() = runBlocking {
        gate.isEnabled = false
        val service = createInjectedService()
        startAndDismiss(service, recurringAlarm, trig1)

        val wm = WorkManager.getInstance(context)
        val infos = wm.getWorkInfosByTag("wake_confirm_42").get()
        assertTrue(
            "Flag OFF dismiss must schedule WakeConfirm work without requiring OutputAuthorityGate enabled",
            infos.any { it.state == WorkInfo.State.ENQUEUED }
        )

        // Pre-S5-D worker upgrade path: DE lineage absent, blank stepToken, Room dismiss present
        clearPrefs()
        recordedIncidents.clear()
        coEvery { alarmEventDao.countDismissedByOccurrence(42L, trig1) } returns 1
        val legacyWorker = buildWorker(
            42L,
            trig1,
            refireCount = 0,
            stepToken = "",
            confirmWaitMsOverride = 60L,
            pollIntervalMsOverride = 10L
        )
        assertEquals(ListenableWorker.Result.success(), legacyWorker.doWork())
        assertTrue(
            "Pre-S5-D worker with Room dismiss proof MUST proceed to prompt & refire",
            recordedIncidents.contains("WAKE_CONFIRM_PROMPT_POSTED")
        )
    }

    // 16. Flag ON gate coordination: primary terminal syncs WakeConfirmLineage, worker reserves refire slot,
    //     and confirmWakeAwake checks occurrence confirmation.
    @Test
    fun `16 flagOn OutputAuthorityGate reserves WakeConfirm refire and verifies confirmation`() = runBlocking {
        gate.isEnabled = true
        val primaryKey = OutputOccurrenceKey(42L, trig1, 0)
        val deCtx = context.createDeviceProtectedStorageContext()
        synchronized(DirectBootStorageLock.lock) {
            DirectBootLegacyClaimStore.writeInitialClaim(
                deCtx,
                LegacyClaimRecord(
                    alarmId = 42L,
                    scheduledAt = trig1,
                    refireCount = 0,
                    source = LegacyClaimSource.ALARM_RECEIVER,
                    claimNonce = "ingress-nonce-0",
                    processSessionToken = "proc-s5d-test",
                    bootCount = 10L,
                    state = LegacyClaimState.START_REQUESTED
                )
            )
        }
        // Room receipt does NOT exist (normal CE alarm not already delivered by Direct Boot consumer)
        val roomChecker: (Long, Long) -> Boolean = { _, _ -> false }
        coEvery { receiptDao.findByAlarmId(42L) } returns emptyList()

        val service = createInjectedService()
        service.roomReceiptCheckerSeam = roomChecker
        startAndDismiss(service, recurringAlarm, trig1, refireCount = 0, claimNonce = "ingress-nonce-0")

        val primaryClaim = DirectBootLegacyClaimStore.inspectStorageState(deCtx)
        val primaryRec = (primaryClaim as LegacyClaimStorageState.ValidWithRecords).records
            .first { it.key == primaryKey }
        assertEquals(LegacyClaimState.TERMINAL, primaryRec.state)
        assertEquals(LegacyTerminalOutcome.CONSUMED_DISMISSED, primaryRec.terminalOutcome)
        assertNotNull("Primary dismiss with Flag ON MUST attach WakeConfirmLineage", primaryRec.wakeConfirm)

        // Worker timeout with Flag ON reserves refire 1 through gate.reserveWakeConfirmRefire
        val w0 = buildWorker(
            42L,
            trig1,
            refireCount = 0,
            confirmWaitMsOverride = 60L,
            pollIntervalMsOverride = 10L,
            authorityGateOverride = gate
        )
        assertEquals(ListenableWorker.Result.success(), w0.doWork())
        val refireIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("Flag ON refire MUST start AlarmService", refireIntent)
        val refireNonce = refireIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE)
        assertFalse("Flag ON refire MUST carry reserved EXTRA_CLAIM_NONCE", refireNonce.isNullOrBlank())

        // confirmWakeAwake rejects before confirmOccurrence and authorizes after
        assertTrue(gate.confirmWakeAwake(context, primaryKey) is OutputAuthorizationResult.WakeConfirmUnauthorized)
        assertTrue(WakeConfirmActivity.handleConfirmAwake(context, 42L, trig1))
        assertTrue(gate.confirmWakeAwake(context, primaryKey) is OutputAuthorizationResult.Authorized)
    }
}

