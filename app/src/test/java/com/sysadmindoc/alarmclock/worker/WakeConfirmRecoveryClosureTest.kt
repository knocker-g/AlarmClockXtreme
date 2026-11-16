package com.sysadmindoc.alarmclock.worker

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.UserManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.OneTimeWorkRequestBuilder
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
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootStorageLock
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.ProductionSchedulingRouteAuthority
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.ui.alarmfiring.WakeConfirmActivity
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
class WakeConfirmRecoveryClosureTest {

    private lateinit var context: Context
    private lateinit var alarmDatabase: AlarmDatabase
    private lateinit var alarmEventDao: AlarmEventDao
    private lateinit var alarmIncidentEventDao: AlarmIncidentEventDao
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var alarmIncidentRepository: AlarmIncidentRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler
    private lateinit var workerParams: WorkerParameters

    private val recordedIncidents = CopyOnWriteArrayList<String>()
    private val trig1 = 1_700_000_000_000L
    private val trig2 = trig1 + 86_400_000L

    private val recurringAlarm = Alarm(
        id = 42L,
        hour = 7,
        minute = 0,
        label = "Morning Check",
        isEnabled = true,
        repeatDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY),
        wakeConfirmEnabled = true,
        wakeConfirmDelayMinutes = 2
    )

    private val oneShotAutoDisabledAlarm = Alarm(
        id = 44L,
        hour = 9,
        minute = 0,
        label = "OneShot Check",
        isEnabled = false,
        repeatDays = emptySet(),
        wakeConfirmEnabled = true,
        wakeConfirmDelayMinutes = 2
    )

    private val otherAlarm = Alarm(
        id = 43L,
        hour = 8,
        minute = 0,
        label = "Second Alarm",
        isEnabled = true,
        repeatDays = setOf(DayOfWeek.WEDNESDAY),
        wakeConfirmEnabled = true,
        wakeConfirmDelayMinutes = 2
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
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

        alarmRepository = mockk(relaxed = true)
        every { alarmRepository.database } returns alarmDatabase
        coEvery { alarmRepository.getById(42L) } returns recurringAlarm
        coEvery { alarmRepository.getById(43L) } returns otherAlarm
        coEvery { alarmRepository.getById(44L) } returns oneShotAutoDisabledAlarm
        coEvery { alarmRepository.getEnabled() } returns listOf(recurringAlarm, otherAlarm)

        alarmIncidentRepository = mockk(relaxed = true)
        coEvery {
            alarmIncidentRepository.record(any(), any(), any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            recordedIncidents.add(arg(6))
            1L
        }

        preferencesManager = mockk(relaxed = true)
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings()
        every { preferencesManager.getCachedSettings() } returns AppSettings()

        alarmScheduler = mockk(relaxed = true)
        coEvery { alarmScheduler.rescheduleAllInBatches(any(), any()) } returns ScheduleOutcome.BatchResult(
            armedCount = 1,
            blockedCount = 0,
            failedCount = 0,
            noArmCount = 0,
            outcomes = emptyMap()
        )

        workerParams = mockk(relaxed = true)
        every { workerParams.inputData } returns Data.EMPTY
        every { workerParams.runAttemptCount } returns 0
    }

    @After
    fun tearDown() {
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        clearPrefs()
    }

    private fun clearPrefs() {
        context.getSharedPreferences(WakeConfirmOccurrenceStore.CE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        val deCtx = context.createDeviceProtectedStorageContext()
        deCtx.getSharedPreferences(WakeConfirmOccurrenceStore.DE_PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun buildWakeConfirmWorker(
        alarmId: Long,
        scheduledAt: Long,
        fireId: String = AlarmIncidentEvent.fireIdFor(alarmId, scheduledAt),
        refireCount: Int = 0,
        stepToken: String = ""
    ): WakeConfirmWorker {
        val data = workDataOf(
            WakeConfirmWorker.KEY_ALARM_ID to alarmId,
            WakeConfirmWorker.KEY_ALARM_FIRE_ID to fireId,
            WakeConfirmWorker.KEY_SCHEDULED_AT to scheduledAt,
            WakeConfirmWorker.KEY_REFIRE_COUNT to refireCount,
            WakeConfirmWorker.KEY_STEP_TOKEN to stepToken
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
                    confirmWaitMsOverride = 60L
                    pollIntervalMsOverride = 15L
                    authorityGateOverride = SharedOutputAuthorityGate(
                        bootCount = 1L,
                        processSessionToken = "test-proc"
                    ).apply { isEnabled = false }
                }
            })
            .build()
    }

    @Test
    fun `01 crash after DE commit before enqueue recovers via AlarmHealthWorker even for auto-disabled one-shot and skips without Room dismiss proof`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 44L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(44L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = false
        )
        // Missing Room dismiss proof first -> must NOT enqueue
        coEvery { alarmEventDao.countDismissedByOccurrence(44L, trig1) } returns 0
        coEvery { alarmIncidentEventDao.countDismissedIncidentsByOccurrence(44L, trig1) } returns 0
        coEvery { alarmRepository.getEnabled() } returns emptyList()

        val healthWorker = AlarmHealthWorker(
            context = context,
            workerParams = workerParams,
            alarmRepository = alarmRepository,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = mockk(relaxed = true),
            routeAuthority = ProductionSchedulingRouteAuthority()
        )
        assertEquals(ListenableWorker.Result.success(), healthWorker.doWork())
        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(44L, trig1, SupportingWorkType.WAKE_CONFIRM)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().isEmpty())
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 44L, trig1)!!.enqueueConfirmed)

        // Now Room dismiss proof is present -> recovers BEFORE hasEnabledAlarms early return
        coEvery { alarmEventDao.countDismissedByOccurrence(44L, trig1) } returns 1
        assertEquals(ListenableWorker.Result.success(), healthWorker.doWork())

        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get()
        assertTrue("AlarmHealthWorker MUST recover pending WakeConfirm enqueue", infos.any { it.state == WorkInfo.State.ENQUEUED })
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 44L, trig1)!!.enqueueConfirmed)
    }

    @Test
    fun `02 crash after enqueue before markEnqueueConfirmed reuses existing WorkManager work idempotently`() = runBlocking {
        val commitRes = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed
        coEvery { alarmEventDao.countDismissedByOccurrence(42L, trig1) } returns 1

        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        val wm = WorkManager.getInstance(context)
        val preReq = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(120, TimeUnit.SECONDS)
            .addTag("wake_confirm_42")
            .addTag(WakeConfirmOccurrenceStore.occurrenceTag(42L, trig1))
            .addTag(WakeConfirmOccurrenceStore.refireStepTag(0))
            .addTag(WakeConfirmOccurrenceStore.stepTokenTag(commitRes.record.stepToken))
            .build()
        wm.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, preReq).result.get()
        val originalWorkId = wm.getWorkInfosForUniqueWork(uniqueName).get().single().id

        val recoveryRes = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.PROCESS_DEATH,
            alarmIncidentRepository = alarmIncidentRepository
        )
        assertEquals(1, recoveryRes.reusedCount)
        assertEquals(0, recoveryRes.enqueuedCount)

        val infosAfter = wm.getWorkInfosForUniqueWork(uniqueName).get()
        assertEquals(1, infosAfter.size)
        assertEquals("Existing WorkManager job ID MUST be preserved without REPLACE", originalWorkId, infosAfter.single().id)
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_RECOVERY_REUSED_PROCESS_DEATH"))
    }

    @Test
    fun `03 BootRescheduleWorker distinguishes REBOOT vs UNLOCK and defers safely when CE locked`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )
        coEvery { alarmEventDao.countDismissedByOccurrence(42L, trig1) } returns 1

        val userManager = context.getSystemService(UserManager::class.java)
        shadowOf(userManager).setUserUnlocked(false)

        val bootParams = mockk<WorkerParameters>(relaxed = true)
        every { bootParams.inputData } returns workDataOf("source_action" to Intent.ACTION_BOOT_COMPLETED)
        every { bootParams.runAttemptCount } returns 0

        val lockedBootWorker = BootRescheduleWorker(
            context = context,
            workerParams = bootParams,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = null,
            routeAuthority = ProductionSchedulingRouteAuthority(),
            alarmRepository = alarmRepository
        )
        assertEquals(ListenableWorker.Result.success(), lockedBootWorker.doWork())
        val uniqueName = OwnerWorkIdentity.occurrenceUniqueName(42L, trig1, SupportingWorkType.WAKE_CONFIRM)
        assertTrue("While locked, CE/WorkManager recovery must defer", WorkManager.getInstance(context).getWorkInfosForUniqueWork(uniqueName).get().isEmpty())
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.enqueueConfirmed)

        // Unlock arrives -> recovers with UNLOCK trigger
        shadowOf(userManager).setUserUnlocked(true)
        val unlockParams = mockk<WorkerParameters>(relaxed = true)
        every { unlockParams.inputData } returns workDataOf("source_action" to Intent.ACTION_USER_UNLOCKED)
        every { unlockParams.runAttemptCount } returns 0

        val unlockRes = WakeConfirmOccurrenceStore.recoverPendingEnqueues(
            context = context,
            alarmRepository = alarmRepository,
            trigger = WakeConfirmOccurrenceStore.RecoveryTrigger.UNLOCK,
            alarmIncidentRepository = alarmIncidentRepository
        )
        assertEquals(1, unlockRes.enqueuedCount)
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_RECOVERY_ENQUEUED_UNLOCK"))

        // Also verify BootRescheduleWorker UNLOCK wiring directly
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 43L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(43L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )
        coEvery { alarmEventDao.countDismissedByOccurrence(43L, trig1) } returns 1
        val unlockedBootWorker = BootRescheduleWorker(
            context = context,
            workerParams = unlockParams,
            alarmScheduler = alarmScheduler,
            scheduleAuthorityReconciler = null,
            routeAuthority = ProductionSchedulingRouteAuthority(),
            alarmRepository = alarmRepository
        )
        assertEquals(ListenableWorker.Result.success(), unlockedBootWorker.doWork())
        val unique43 = OwnerWorkIdentity.occurrenceUniqueName(43L, trig1, SupportingWorkType.WAKE_CONFIRM)
        assertTrue(WorkManager.getInstance(context).getWorkInfosForUniqueWork(unique43).get().any { it.state == WorkInfo.State.ENQUEUED })
    }

    @Test
    fun `04 stale occurrence excluded from getPendingRetryableEnqueues`() {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig2,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig2),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )

        val pending = WakeConfirmOccurrenceStore.getPendingRetryableEnqueues(context)
        assertEquals("Only latest occurrence trig2 should be pending", listOf(trig2), pending.map { it.scheduledAt })
    }

    @Test
    fun `05 DE confirmation commit false never returns confirmed in checkAndMigrateConfirmation or confirmOccurrence`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )
        WakeConfirmOccurrenceStore.deCommitSeam = { _, _ -> false }

        val detailed = WakeConfirmActivity.handleConfirmAwakeDetailed(context, 42L, trig1)
        assertTrue(
            "CE commit succeeded + DE commit false MUST return CeConfirmedDePendingReconciliation, got $detailed",
            detailed is WakeConfirmOccurrenceStore.ConfirmOccurrenceResult.CeConfirmedDePendingReconciliation
        )
        assertFalse(
            "confirmOccurrence boolean wrapper MUST return false when DE commit fails",
            WakeConfirmOccurrenceStore.confirmOccurrence(context, 42L, trig1)
        )
        assertTrue(
            "CE confirmation key MUST be preserved for safe reconciliation",
            context.getSharedPreferences(WakeConfirmOccurrenceStore.CE_PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(WakeConfirmOccurrenceStore.confirmedKey(42L, trig1), false)
        )

        val status = WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            roomDismissChecker = { true }
        )
        assertTrue(
            "checkAndMigrateConfirmation MUST return StorageUnverifiable when DE commit fails, got $status",
            status is WakeConfirmOccurrenceStore.ConfirmationStatus.StorageUnverifiable
        )
    }

    @Test
    fun `06 Corrupt, UnsupportedSchema, and DE readback mismatch fail closed in confirmOccurrence and checkAndMigrateConfirmation`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )

        // Readback mismatch on commit (return stale pre-commit payload after commit)
        val dePrefs = WakeConfirmOccurrenceStore.dePrefs(context)
        val stalePayload = dePrefs.getString(WakeConfirmOccurrenceStore.KEY_LINEAGE_PAYLOAD, null)
        WakeConfirmOccurrenceStore.deReadbackSeam = { stalePayload }

        val mismatchRes = WakeConfirmOccurrenceStore.confirmOccurrenceDetailed(context, 42L, trig1)
        assertTrue(
            "DE readback mismatch MUST return CeConfirmedDePendingReconciliation, got $mismatchRes",
            mismatchRes is WakeConfirmOccurrenceStore.ConfirmOccurrenceResult.CeConfirmedDePendingReconciliation
        )
        assertFalse(WakeConfirmOccurrenceStore.confirmOccurrence(context, 42L, trig1))
        val mismatchCheck = WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(context, 42L, trig1, { true })
        assertTrue(mismatchCheck is WakeConfirmOccurrenceStore.ConfirmationStatus.StorageUnverifiable)

        // Corrupt JSON
        WakeConfirmOccurrenceStore.deReadbackSeam = { "{corrupt_json" }
        assertFalse(
            "Corrupt DE storage MUST NOT return success in confirmOccurrence",
            WakeConfirmOccurrenceStore.confirmOccurrence(context, 42L, trig1)
        )
        assertTrue(
            WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(context, 42L, trig1, { true })
                is WakeConfirmOccurrenceStore.ConfirmationStatus.StorageUnverifiable
        )

        // UnsupportedSchema
        WakeConfirmOccurrenceStore.deReadbackSeam = { """{"schemaVersion":99,"records":[]}""" }
        assertFalse(
            "UnsupportedSchema MUST NOT return success in confirmOccurrence",
            WakeConfirmOccurrenceStore.confirmOccurrence(context, 42L, trig1)
        )
        assertTrue(
            WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(context, 42L, trig1, { true })
                is WakeConfirmOccurrenceStore.ConfirmationStatus.StorageUnverifiable
        )
    }

    @Test
    fun `07 legacy migration Step 2 DE failure returns StorageUnverifiable and heals on recovery`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )
        val cePrefs = context.getSharedPreferences(WakeConfirmOccurrenceStore.CE_PREFS_NAME, Context.MODE_PRIVATE)
        cePrefs.edit().putBoolean(WakeConfirmOccurrenceStore.legacyConfirmedKey(42L), true).commit()

        WakeConfirmOccurrenceStore.deCommitSeam = { _, _ -> false }
        val statusOnDeFail = WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            roomDismissChecker = { true }
        )
        assertTrue(
            "Legacy migration Step 2 DE failure MUST return StorageUnverifiable, got $statusOnDeFail",
            statusOnDeFail is WakeConfirmOccurrenceStore.ConfirmationStatus.StorageUnverifiable
        )
        // Step 1 CE scoped key committed, legacy key retained until DE reconciles
        assertTrue(cePrefs.getBoolean(WakeConfirmOccurrenceStore.confirmedKey(42L, trig1), false))
        assertTrue(cePrefs.getBoolean(WakeConfirmOccurrenceStore.legacyConfirmedKey(42L), false))

        // Recovery: DE storage becomes writable again -> reconciles cleanly to Confirmed and cleans up legacy key
        WakeConfirmOccurrenceStore.resetSeamsForTesting()
        val healed = WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            roomDismissChecker = { true }
        )
        assertEquals(WakeConfirmOccurrenceStore.ConfirmationStatus.Confirmed, healed)
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.confirmed)
        assertFalse(cePrefs.contains(WakeConfirmOccurrenceStore.legacyConfirmedKey(42L)))
    }

    @Test
    fun `08 cancelled occurrence never confirms or recovers even when CE key present`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )
        assertTrue(WakeConfirmOccurrenceStore.cancelOccurrence(context, 42L, trig1))

        val cePrefs = context.getSharedPreferences(WakeConfirmOccurrenceStore.CE_PREFS_NAME, Context.MODE_PRIVATE)
        cePrefs.edit().putBoolean(WakeConfirmOccurrenceStore.confirmedKey(42L, trig1), true).commit()

        assertEquals(
            WakeConfirmOccurrenceStore.ConfirmationStatus.Unconfirmed,
            WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(context, 42L, trig1, { true })
        )
        assertTrue(
            WakeConfirmOccurrenceStore.confirmOccurrenceDetailed(context, 42L, trig1)
                is WakeConfirmOccurrenceStore.ConfirmOccurrenceResult.Rejected
        )
        assertTrue(WakeConfirmOccurrenceStore.getPendingRetryableEnqueues(context).isEmpty())
    }

    @Test
    fun `09 confirming one occurrence never mutates sibling occurrence confirmation state`() = runBlocking {
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig2,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig2),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )
        WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 43L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(43L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        )

        val confirmRes = WakeConfirmOccurrenceStore.confirmOccurrenceDetailed(context, 42L, trig1)
        assertEquals(WakeConfirmOccurrenceStore.ConfirmOccurrenceResult.Confirmed(deUpdated = true), confirmRes)

        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.confirmed)
        assertFalse("Sibling occurrence trig2 of same alarm MUST remain unconfirmed", WakeConfirmOccurrenceStore.findRecord(context, 42L, trig2)!!.confirmed)
        assertFalse("Other alarm occurrence MUST remain unconfirmed", WakeConfirmOccurrenceStore.findRecord(context, 43L, trig1)!!.confirmed)

        assertEquals(
            WakeConfirmOccurrenceStore.ConfirmationStatus.Unconfirmed,
            WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(context, 42L, trig2, { true })
        )
        assertEquals(
            WakeConfirmOccurrenceStore.ConfirmationStatus.Unconfirmed,
            WakeConfirmOccurrenceStore.checkAndMigrateConfirmation(context, 43L, trig1, { true })
        )
    }

    @Test
    fun `10 over-cap nextAuthorizedRefire MAX_REFIRES + 1 and final check step never returned by getPendingRetryableEnqueues`() {
        val overCapJson = """{"schemaVersion":1,"records":[{
            "alarmId":42,"scheduledAt":$trig1,"fireId":"alarm-42-$trig1",
            "parentDismissed":true,"dismissedAt":1700000001000,"wakeConfirmEnabled":true,
            "oneShotDisabledAllowed":false,"nextAuthorizedRefire":${WakeConfirmWorker.MAX_REFIRES + 1},
            "highestClaimedRefire":${WakeConfirmWorker.MAX_REFIRES},"confirmed":false,"cancelled":false,
            "stepToken":"forged-overcap-token","enqueueConfirmed":false,"legacyConfirmedKeyPreexistedAtDismiss":false
        }]}"""
        synchronized(DirectBootStorageLock.lock) {
            WakeConfirmOccurrenceStore.dePrefs(context)
                .edit()
                .putString(WakeConfirmOccurrenceStore.KEY_LINEAGE_PAYLOAD, overCapJson)
                .commit()
        }

        assertTrue(
            "Over-cap nextAuthorizedRefire=${WakeConfirmWorker.MAX_REFIRES + 1} MUST NOT be returned by getPendingRetryableEnqueues",
            WakeConfirmOccurrenceStore.getPendingRetryableEnqueues(context).isEmpty()
        )
    }

    @Test
    fun `11 legacy worker missing scheduledAt resolves only when single occurrence uniquely provable and fails closed otherwise`() = runBlocking {
        // Case A: 1 unique Room dismiss candidate -> resolves & posts prompt
        coEvery { alarmEventDao.findRecentDismissedScheduledTimes(42L) } returns listOf(trig1)
        coEvery { alarmEventDao.countDismissedByOccurrence(42L, trig1) } returns 1
        val workerUnique = buildWakeConfirmWorker(alarmId = 42L, scheduledAt = 0L, fireId = AlarmIncidentEvent.fireIdFor(42L, trig1))
        val resUnique = withTimeoutOrNull(800L) { workerUnique.doWork() }
        assertEquals(ListenableWorker.Result.success(), resUnique)
        assertTrue(
            "Uniquely provable legacy worker missing scheduledAt MUST post prompt",
            recordedIncidents.contains("WAKE_CONFIRM_PROMPT_POSTED")
        )

        // Reset seeded DE record and test Case B: 0 Room candidates -> fail closed
        clearPrefs()
        recordedIncidents.clear()
        coEvery { alarmEventDao.findRecentDismissedScheduledTimes(42L) } returns emptyList()
        coEvery { alarmIncidentEventDao.findRecentDismissedScheduledTimes(42L) } returns emptyList()
        val workerZero = buildWakeConfirmWorker(alarmId = 42L, scheduledAt = 0L, fireId = "")
        assertEquals(ListenableWorker.Result.success(), withTimeoutOrNull(800L) { workerZero.doWork() })
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_INVALID_OCCURRENCE"))
        assertFalse(recordedIncidents.contains("WAKE_CONFIRM_PROMPT_POSTED"))

        // Case C: 2 ambiguous Room candidates with blank fireId -> fail closed
        clearPrefs()
        recordedIncidents.clear()
        coEvery { alarmEventDao.findRecentDismissedScheduledTimes(42L) } returns listOf(trig1, trig2)
        val workerAmbiguous = buildWakeConfirmWorker(alarmId = 42L, scheduledAt = 0L, fireId = "")
        assertEquals(ListenableWorker.Result.success(), withTimeoutOrNull(800L) { workerAmbiguous.doWork() })
        assertTrue(recordedIncidents.contains("WAKE_CONFIRM_INVALID_OCCURRENCE"))
        assertFalse(recordedIncidents.contains("WAKE_CONFIRM_PROMPT_POSTED"))
    }

    @Test
    fun `12 notification collision isolation, cancel scope isolation, and Flag OFF refire compatibility`() = runBlocking {
        // 12a: Notification tag isolation on numeric ID collision
        val targetId = WakeConfirmWorker.notificationId(42L, trig1)
        var collidingTrig: Long = trig1 + 1_000L
        while (WakeConfirmWorker.notificationId(43L, collidingTrig) != targetId) {
            collidingTrig += 1_000L
        }
        coEvery { alarmEventDao.countDismissedByOccurrence(42L, trig1) } returns 1
        coEvery { alarmEventDao.countDismissedByOccurrence(43L, collidingTrig) } returns 1

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.notify(targetId, android.app.Notification())

        val commitRes = WakeConfirmOccurrenceStore.commitDismissLineage(
            context = context,
            alarmId = 42L,
            scheduledAt = trig1,
            fireId = AlarmIncidentEvent.fireIdFor(42L, trig1),
            refireCount = 0,
            wakeConfirmEnabled = true,
            isRecurringSchedule = true
        ) as WakeConfirmOccurrenceStore.CommitResult.Committed

        val w42 = buildWakeConfirmWorker(
            alarmId = 42L,
            scheduledAt = trig1,
            stepToken = commitRes.record.stepToken
        )
        assertEquals(ListenableWorker.Result.success(), withTimeoutOrNull(800L) { w42.doWork() })
        assertNotNull(
            "Worker 42 cancelling its own occurrence notification MUST NOT cancel untagged/other colliding notification at $targetId",
            shadowOf(nm).getNotification(targetId)
        )

        // 12c: Flag OFF refire compatibility — must start AlarmService with refireCount=1
        val startedIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("Flag OFF WakeConfirm timeout MUST start AlarmService for refire #1", startedIntent)
        assertEquals(AlarmService.ACTION_START_ALARM, startedIntent.action)
        assertEquals(1, startedIntent.getIntExtra(AlarmService.EXTRA_WAKE_CONFIRM_REFIRE_COUNT, -1))

        // 12b: Cancel scope isolation — occurrence cancel leaves sibling active; cancelAllForAlarm leaves other alarm active
        clearPrefs()
        WakeConfirmOccurrenceStore.commitDismissLineage(context, 42L, trig1, "f1", 0, true, true)
        WakeConfirmOccurrenceStore.commitDismissLineage(context, 42L, trig2, "f2", 0, true, true)
        WakeConfirmOccurrenceStore.commitDismissLineage(context, 43L, trig1, "f3", 0, true, true)

        assertTrue(WakeConfirmOccurrenceStore.cancelOccurrence(context, 42L, trig1))
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig1)!!.cancelled)
        assertFalse("Occurrence cancel MUST NOT cancel sibling occurrence", WakeConfirmOccurrenceStore.findRecord(context, 42L, trig2)!!.cancelled)
        assertFalse(WakeConfirmOccurrenceStore.findRecord(context, 43L, trig1)!!.cancelled)

        assertTrue(WakeConfirmOccurrenceStore.cancelAllForAlarm(context, 42L))
        assertTrue(WakeConfirmOccurrenceStore.findRecord(context, 42L, trig2)!!.cancelled)
        assertFalse("cancelAllForAlarm(42) MUST NOT cancel alarm 43", WakeConfirmOccurrenceStore.findRecord(context, 43L, trig1)!!.cancelled)
    }
}
