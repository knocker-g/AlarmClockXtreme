package com.sysadmindoc.alarmclock.worker

import android.Manifest
import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.UserManager
import android.telephony.SmsManager
import android.util.AtomicFile
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmEvent
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmEventRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.domain.NextAlarmCalculator
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.ScheduleOutcome
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import com.sysadmindoc.alarmclock.integration.hue.HueBridgeClient
import com.sysadmindoc.alarmclock.integration.hue.HueTrustStore
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.service.AlarmServiceCallbackPolicy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class GuardianWorkerTest {

    private lateinit var context: Context
    private lateinit var db: AlarmDatabase
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var eventRepository: AlarmEventRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var alarmScheduler: AlarmScheduler
    private val serviceControllers = mutableListOf<ServiceController<AlarmService>>()

    private val fakePhone = "+15550100"
    private val altFakePhone = "+15550101"
    private val schedA = 1_700_000_000_000L
    private val schedC = 1_700_000_600_000L
    private val fireA = AlarmIncidentEvent.fireIdFor(42L, schedA)
    private val fireC = AlarmIncidentEvent.fireIdFor(42L, schedC)

    private data class FakeSmsCall(val phone: String, val message: String)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val app = context as Application
        shadowOf(app).grantPermissions(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.SEND_SMS
        )
        shadowOf(context.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        org.robolectric.shadows.ShadowAlarmManager.setCanScheduleExactAlarms(true)
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        AlarmServiceCallbackPolicy.resetState()
        AlarmService.activeAlarm.set(null)
        clearJournalFiles()

        db = Room.inMemoryDatabaseBuilder(context, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.scheduleAuthorityDao().insertMeta(
                ScheduleAuthorityMeta(id = 1, restoreEpoch = 7L, bootstrapState = "INITIALIZED")
            )
        }
        alarmRepository = AlarmRepository(
            database = db,
            dao = db.alarmDao(),
            groupDao = db.alarmGroupDao()
        )
        eventRepository = AlarmEventRepository(db.alarmEventDao())
        incidentRepository = AlarmIncidentRepository(db.alarmIncidentEventDao(), context)
        preferencesManager = mockk(relaxed = true)
        alarmScheduler = mockk(relaxed = true)
        every { alarmScheduler.canScheduleExactAlarms() } returns true
        coEvery { alarmScheduler.scheduleSnoozeAt(any(), any(), any()) } returns ScheduleOutcome.Armed(42L, schedA + 600_000L)
        every { preferencesManager.enqueueActiveAlarmUpdate(any(), any(), any()) } answers {
            val ack = thirdArg<CompletableDeferred<PreferencesManager.ActiveAlarmUpdateResult>?>()
            ack?.complete(PreferencesManager.ActiveAlarmUpdateResult.COMMITTED)
            true
        }
        setActiveSession(42L, schedA, fireA, "FIRING")
    }

    @After
    fun tearDown() {
        val smsManager = context.getSystemService(SmsManager::class.java)
        assertNull(
            "Real SmsManager MUST NEVER be invoked in offline tests",
            shadowOf(smsManager).lastSentTextMessageParams
        )
        serviceControllers.forEach { runCatching { it.destroy() } }
        serviceControllers.clear()
        clearJournalFiles()
        runCatching { db.close() }
        unmockkAll()
    }

    private fun clearJournalFiles() {
        File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME).delete()
        File(context.filesDir, "${RestoreJournalCoordinator.JOURNAL_FILENAME}.bak").delete()
    }

    private fun writePreparedRestoreJournal() {
        val snapshotJson = "{}"
        val formatVersion = RestoreJournalCoordinator.CURRENT_FORMAT_VERSION
        val phase = RestoreJournalCoordinator.PHASE_PREPARED
        val checksumBytes = MessageDigest.getInstance("SHA-256")
            .digest("$formatVersion:$phase:$snapshotJson".toByteArray(StandardCharsets.UTF_8))
        val checksum = checksumBytes.joinToString("") { "%02x".format(it) }
        val payload = JSONObject().apply {
            put("formatVersion", formatVersion)
            put("transactionId", "tx-guardian-test-1")
            put("phase", phase)
            put("snapshotJson", snapshotJson)
            put("checksum", checksum)
        }.toString()
        val atomic = AtomicFile(File(context.filesDir, RestoreJournalCoordinator.JOURNAL_FILENAME))
        val out = atomic.startWrite()
        out.write(payload.toByteArray(StandardCharsets.UTF_8))
        atomic.finishWrite(out)
    }

    private fun setActiveSession(
        alarmId: Long?,
        scheduledAt: Long,
        fireId: String,
        state: String,
        autoSilenceMinutes: Int = 10
    ) {
        val settings = AppSettings(
            activeAlarmId = alarmId,
            activeAlarmScheduledAt = scheduledAt,
            activeAlarmFireId = fireId,
            activeAlarmState = state,
            autoSilenceMinutes = autoSilenceMinutes
        )
        coEvery { preferencesManager.getCurrentSettings() } returns settings
        every { preferencesManager.getCachedSettings() } returns settings
    }

    private suspend fun insertGuardianAlarm(
        id: Long = 42L,
        isEnabled: Boolean = true,
        guardianEnabled: Boolean = true,
        phone: String = fakePhone,
        generation: Int = 2,
        maxSnoozeCount: Int = 3,
        isQuickAlarm: Boolean = false
    ): Alarm {
        val alarm = Alarm(
            id = id,
            hour = 7,
            minute = 0,
            label = "Wake",
            isEnabled = isEnabled,
            guardianEnabled = guardianEnabled,
            guardianPhone = phone,
            guardianDelaySec = 30,
            generation = generation,
            maxSnoozeCount = maxSnoozeCount,
            kind = if (isQuickAlarm) Alarm.KIND_QUICK else Alarm.KIND_NORMAL
        )
        alarmRepository.save(alarm)
        return alarm
    }

    private fun validInputData(
        alarmId: Long = 42L,
        scheduledAt: Long = schedA,
        fireId: String = fireA,
        expectedGeneration: Int = 2,
        expectedRestoreEpoch: Long = 7L,
        phone: String = fakePhone,
        label: String = "Wake"
    ) = workDataOf(
        GuardianWorker.KEY_ALARM_ID to alarmId,
        GuardianWorker.KEY_SCHEDULED_AT to scheduledAt,
        GuardianWorker.KEY_ALARM_FIRE_ID to fireId,
        GuardianWorker.KEY_EXPECTED_GENERATION to expectedGeneration,
        GuardianWorker.KEY_EXPECTED_RESTORE_EPOCH to expectedRestoreEpoch,
        GuardianWorker.KEY_GUARDIAN_PHONE to phone,
        GuardianWorker.KEY_ALARM_LABEL to label
    )

    private fun buildWorker(
        inputData: androidx.work.Data = validInputData(),
        repoOverride: AlarmRepository = alarmRepository,
        prefsOverride: PreferencesManager = preferencesManager,
        fakeSmsCalls: MutableList<FakeSmsCall> = mutableListOf(),
        smsSender: ((String, String) -> Boolean) = { phone, msg ->
            fakeSmsCalls.add(FakeSmsCall(phone, msg))
            true
        }
    ): GuardianWorker {
        return TestListenableWorkerBuilder<GuardianWorker>(context)
            .setInputData(inputData)
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ) = GuardianWorker(
                    context = appContext,
                    workerParams = workerParameters,
                    alarmRepository = repoOverride,
                    preferencesManager = prefsOverride,
                    alarmIncidentRepository = incidentRepository
                ).apply {
                    smsSenderOverride = smsSender
                }
            })
            .build()
    }

    private fun createInjectedService(): AlarmService {
        val controller = Robolectric.buildService(AlarmService::class.java)
        serviceControllers.add(controller)
        return controller.get().apply {
            repository = alarmRepository
            this.alarmScheduler = this@GuardianWorkerTest.alarmScheduler
            eventRepository = this@GuardianWorkerTest.eventRepository
            alarmIncidentRepository = this@GuardianWorkerTest.incidentRepository
            this.preferencesManager = this@GuardianWorkerTest.preferencesManager
            webhookService = mockk(relaxed = true)
            wearNextAlarmBridge = mockk(relaxed = true)
            dismissActionExecutor = mockk(relaxed = true)
            weatherRepository = mockk(relaxed = true)
            calendarRepository = mockk(relaxed = true)
        }
    }

    private suspend fun awaitLatchWithIdle(latch: CompletableDeferred<Unit>, timeoutMs: Long = 3_000L) {
        withTimeoutOrNull(timeoutMs) {
            while (!latch.isCompleted) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                delay(10L)
            }
        }
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    private suspend fun startAlarmInService(
        service: AlarmService,
        alarmId: Long,
        scheduledAt: Long,
        fireId: String,
        startId: Int = 1
    ) {
        val latch = CompletableDeferred<Unit>()
        service.testStartAlarmCompletionLatch = latch
        service.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_START_ALARM
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireId)
            },
            0,
            startId
        )
        awaitLatchWithIdle(latch)
    }

    // --- G01: Legitimate Legacy FIRING Occurrence -> Fake SMS 1 call ---

    @Test
    fun `G01 valid legacy FIRING occurrence sends 1 fake SMS and records diagnostic`() = runBlocking {
        insertGuardianAlarm()
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)

        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, fakeCalls.size)
        assertEquals(fakePhone, fakeCalls[0].phone)
        val incidents = db.alarmIncidentEventDao().getRecent(10)
        assertTrue(
            incidents.any {
                it.type == GuardianWorker.INCIDENT_TYPE_GUARDIAN &&
                    it.status == AlarmIncidentEvent.STATUS_SUCCEEDED &&
                    it.reasonCode == "GUARDIAN_SMS_SENT" &&
                    it.alarmId == 42L &&
                    it.scheduledAt == schedA &&
                    it.fireId == fireA
            }
        )
    }

    // --- G02: Dismissed occurrence -> Fake SMS 0 ---

    @Test
    fun `G02 dismissed occurrence fails closed with 0 fake SMS`() = runBlocking {
        insertGuardianAlarm()
        db.alarmEventDao().insert(
            AlarmEvent(
                alarmId = 42L,
                alarmLabel = "Wake",
                scheduledTime = schedA,
                firedAt = schedA,
                action = AlarmEvent.ACTION_DISMISSED,
                actionAt = schedA + 5_000L,
                dayOfWeek = 1
            )
        )
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)

        // Also verify AlarmService dismissAlarm cancels occurrence Guardian work even if alarm row missing
        val service = createInjectedService()
        startAlarmInService(service, 42L, schedA, fireA)
        val occName = OwnerWorkIdentity.occurrenceUniqueName(42L, schedA, SupportingWorkType.GUARDIAN)
        val wm = WorkManager.getInstance(context)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(occName).get(2, TimeUnit.SECONDS).first().state)

        val stopDone = CompletableDeferred<Unit>()
        service.testOnStopSelfCalled = { stopDone.complete(Unit) }
        service.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, schedA)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireA)
            },
            0,
            2
        )
        awaitLatchWithIdle(stopDone)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(occName).get(2, TimeUnit.SECONDS).first().state)
    }

    // --- G03: Snoozed occurrence -> Fake SMS 0 & AlarmService cancels even if guardianEnabled toggled off ---

    @Test
    fun `G03 snoozed occurrence fails closed and AlarmService cancels unconditionally`() = runBlocking {
        insertGuardianAlarm(guardianEnabled = true)
        val service = createInjectedService()
        startAlarmInService(service, 42L, schedA, fireA)

        val occName = OwnerWorkIdentity.occurrenceUniqueName(42L, schedA, SupportingWorkType.GUARDIAN)
        val wm = WorkManager.getInstance(context)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(occName).get(2, TimeUnit.SECONDS).first().state)

        // Toggle guardianEnabled=false in DB before snooze arrives; cancel must still happen
        insertGuardianAlarm(guardianEnabled = false)
        val snoozeDone = CompletableDeferred<Unit>()
        service.testOnSnoozeActionCompletionLatch = snoozeDone
        service.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, schedA)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireA)
            },
            0,
            2
        )
        awaitLatchWithIdle(snoozeDone)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(occName).get(2, TimeUnit.SECONDS).first().state)

        // Re-enable guardianEnabled in DB and verify worker still rejects SNOOZED occurrence
        insertGuardianAlarm(guardianEnabled = true)
        setActiveSession(42L, schedA, fireA, "SNOOZED")
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G04: Max-Snooze AUTO_DISMISS ended occurrence -> Fake SMS 0 ---

    @Test
    fun `G04 max snooze auto dismiss cancels Guardian work and worker sends 0 SMS`() = runBlocking {
        insertGuardianAlarm(maxSnoozeCount = 1)
        // Persist snoozeCount=1 so next ACTION_SNOOZE hits AUTO_DISMISS
        context.getSharedPreferences("alarm_runtime_state", Context.MODE_PRIVATE)
            .edit().putInt("snooze_count_42", 1).commit()

        val service = createInjectedService()
        startAlarmInService(service, 42L, schedA, fireA)
        val occName = OwnerWorkIdentity.occurrenceUniqueName(42L, schedA, SupportingWorkType.GUARDIAN)
        val wm = WorkManager.getInstance(context)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(occName).get(2, TimeUnit.SECONDS).first().state)

        val stopDone = CompletableDeferred<Unit>()
        service.testOnStopSelfCalled = { stopDone.complete(Unit) }
        service.onStartCommand(
            Intent(context, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, schedA)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, fireA)
            },
            0,
            2
        )
        awaitLatchWithIdle(stopDone)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(occName).get(2, TimeUnit.SECONDS).first().state)

        // Even if session snapshot remained stale FIRING, Room MAX_SNOOZE_DISMISSED/DISMISSED blocks SMS
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G05: AUTO_SILENCE ended occurrence -> Fake SMS 0 ---

    @Test
    fun `G05 auto silence terminal incident fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        incidentRepository.record(
            alarmId = 42L,
            fireId = fireA,
            scheduledAt = schedA,
            type = AlarmIncidentEvent.TYPE_AUTO_SILENCE,
            status = AlarmIncidentEvent.STATUS_SUCCEEDED,
            reasonCode = "AUTO_SILENCED_AFTER_10_MINUTES",
            source = "AlarmService"
        )
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G06: Preempted old A -> Fake SMS 0 ---

    @Test
    fun `G06 preempted old occurrence A fails closed and is cancelled on overlap preemption`() = runBlocking {
        insertGuardianAlarm(id = 42L)
        insertGuardianAlarm(id = 99L)
        val service = createInjectedService()
        startAlarmInService(service, 42L, schedA, fireA, startId = 1)

        val occA = OwnerWorkIdentity.occurrenceUniqueName(42L, schedA, SupportingWorkType.GUARDIAN)
        val wm = WorkManager.getInstance(context)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(occA).get(2, TimeUnit.SECONDS).first().state)

        val fire99 = AlarmIncidentEvent.fireIdFor(99L, schedC)
        startAlarmInService(service, 99L, schedC, fire99, startId = 2)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(occA).get(2, TimeUnit.SECONDS).first().state)

        // Worker for preempted A also fails closed on Room PREEMPTED_BY_OVERLAPPING_ALARM + session mismatch
        incidentRepository.record(
            alarmId = 42L,
            fireId = fireA,
            scheduledAt = schedA,
            type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
            status = AlarmIncidentEvent.STATUS_SUCCEEDED,
            reasonCode = "PREEMPTED_BY_OVERLAPPING_ALARM",
            source = "AlarmService"
        )
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val workerA = buildWorker(inputData = validInputData(42L, schedA, fireA), fakeSmsCalls = fakeCalls)
        assertEquals(ListenableWorker.Result.success(), workerA.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G07: Same alarmId A -> C: old A Fake SMS 0, C preserved and sends 1 ---

    @Test
    fun `G07 same alarmId A to C rejects stale A while preserving and delivering C`() = runBlocking {
        insertGuardianAlarm(id = 42L)
        val service = createInjectedService()
        startAlarmInService(service, 42L, schedA, fireA, startId = 1)
        val occA = OwnerWorkIdentity.occurrenceUniqueName(42L, schedA, SupportingWorkType.GUARDIAN)
        val occC = OwnerWorkIdentity.occurrenceUniqueName(42L, schedC, SupportingWorkType.GUARDIAN)
        val wm = WorkManager.getInstance(context)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(occA).get(2, TimeUnit.SECONDS).first().state)

        // Start newer occurrence C on the same alarmId=42
        startAlarmInService(service, 42L, schedC, fireC, startId = 2)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(occA).get(2, TimeUnit.SECONDS).first().state)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(occC).get(2, TimeUnit.SECONDS).first().state)

        // Mark A terminal in Room and active session now pointing to C
        db.alarmEventDao().insert(
            AlarmEvent(
                alarmId = 42L,
                alarmLabel = "Wake",
                scheduledTime = schedA,
                firedAt = schedA,
                action = AlarmEvent.ACTION_DISMISSED,
                actionAt = schedA + 5_000L,
                dayOfWeek = 1
            )
        )
        setActiveSession(42L, schedC, fireC, "FIRING")

        val callsA = mutableListOf<FakeSmsCall>()
        assertEquals(
            ListenableWorker.Result.success(),
            buildWorker(inputData = validInputData(42L, schedA, fireA), fakeSmsCalls = callsA).doWork()
        )
        assertEquals(0, callsA.size)

        val callsC = mutableListOf<FakeSmsCall>()
        assertEquals(
            ListenableWorker.Result.success(),
            buildWorker(inputData = validInputData(42L, schedC, fireC), fakeSmsCalls = callsC).doWork()
        )
        assertEquals(1, callsC.size)
    }

    // --- G08: activeAlarmId matches, scheduledAt mismatches -> Fake SMS 0 ---

    @Test
    fun `G08 activeAlarmId match alone without scheduledAt match fails closed`() = runBlocking {
        insertGuardianAlarm()
        setActiveSession(42L, schedC, fireA, "FIRING")
        val fakeCalls = mutableListOf<FakeSmsCall>()
        assertEquals(ListenableWorker.Result.success(), buildWorker(fakeSmsCalls = fakeCalls).doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G09: fireId mismatches -> Fake SMS 0 ---

    @Test
    fun `G09 fireId mismatch fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        setActiveSession(42L, schedA, fireC, "FIRING")
        val fakeCalls = mutableListOf<FakeSmsCall>()
        assertEquals(ListenableWorker.Result.success(), buildWorker(fakeSmsCalls = fakeCalls).doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G10: Generation mismatch -> Fake SMS 0 (and quick alarm preserves contract) ---

    @Test
    fun `G10 generation mismatch fails closed and quick alarm preserves existing contract`() = runBlocking {
        insertGuardianAlarm(generation = 5)
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(
            inputData = validInputData(expectedGeneration = 4),
            fakeSmsCalls = fakeCalls
        )
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)

        // Quick alarm without expectedGeneration (0) still honors quick contract when all other checks pass
        insertGuardianAlarm(id = 43L, isQuickAlarm = true, generation = 1)
        val fire43 = AlarmIncidentEvent.fireIdFor(43L, schedA)
        setActiveSession(43L, schedA, fire43, "FIRING")
        val quickCalls = mutableListOf<FakeSmsCall>()
        val quickWorker = buildWorker(
            inputData = validInputData(alarmId = 43L, fireId = fire43, expectedGeneration = 0),
            fakeSmsCalls = quickCalls
        )
        assertEquals(ListenableWorker.Result.success(), quickWorker.doWork())
        assertEquals(1, quickCalls.size)
    }

    // --- G11: RestoreEpoch mismatch -> Fake SMS 0 ---

    @Test
    fun `G11 restoreEpoch mismatch fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        db.scheduleAuthorityDao().insertMeta(
            ScheduleAuthorityMeta(id = 1, restoreEpoch = 99L, bootstrapState = "INITIALIZED")
        )
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(
            inputData = validInputData(expectedRestoreEpoch = 7L),
            fakeSmsCalls = fakeCalls
        )
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G12: RestoreJournal PREPARED -> Fake SMS 0 ---

    @Test
    fun `G12 RestoreJournal PHASE_PREPARED fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        writePreparedRestoreJournal()
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G13: isStopped = true -> Fake SMS 0 ---

    @Test
    fun `G13 stopped worker fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)
        worker.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP)

        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G14: Incomplete old WorkData -> Fake SMS 0 ---

    @Test
    fun `G14 incomplete legacy workData fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val legacyOnlyData = workDataOf(
            GuardianWorker.KEY_ALARM_ID to 42L,
            GuardianWorker.KEY_GUARDIAN_PHONE to fakePhone,
            GuardianWorker.KEY_ALARM_LABEL to "Wake"
        )
        val worker = buildWorker(inputData = legacyOnlyData, fakeSmsCalls = fakeCalls)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G15: Alarm disabled -> Fake SMS 0 ---

    @Test
    fun `G15 disabled alarm fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm(isEnabled = false)
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G16: guardianEnabled = false -> Fake SMS 0 ---

    @Test
    fun `G16 guardianEnabled false fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm(guardianEnabled = false)
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G17: Recipient mismatch -> Fake SMS 0 ---

    @Test
    fun `G17 recipient mismatch fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm(phone = altFakePhone)
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(
            inputData = validInputData(phone = fakePhone),
            fakeSmsCalls = fakeCalls
        )
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G18: Missing SEND_SMS permission -> Fake SMS 0 ---

    @Test
    fun `G18 missing SEND_SMS permission fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        shadowOf(context as Application).denyPermissions(Manifest.permission.SEND_SMS)
        val fakeCalls = mutableListOf<FakeSmsCall>()
        val worker = buildWorker(fakeSmsCalls = fakeCalls)
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G19: DB / DataStore read failure -> Fake SMS 0 ---

    @Test
    fun `G19 DB or DataStore read failure fails closed with 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        val failingRepo = mockk<AlarmRepository>(relaxed = true)
        coEvery { failingRepo.getById(any()) } throws IllegalStateException("DB read error")
        val dbFailCalls = mutableListOf<FakeSmsCall>()
        assertEquals(
            ListenableWorker.Result.success(),
            buildWorker(repoOverride = failingRepo, fakeSmsCalls = dbFailCalls).doWork()
        )
        assertEquals(0, dbFailCalls.size)

        val failingPrefs = mockk<PreferencesManager>(relaxed = true)
        coEvery { failingPrefs.getCurrentSettings() } throws IllegalStateException("DataStore read error")
        val prefsFailCalls = mutableListOf<FakeSmsCall>()
        assertEquals(
            ListenableWorker.Result.success(),
            buildWorker(prefsOverride = failingPrefs, fakeSmsCalls = prefsFailCalls).doWork()
        )
        assertEquals(0, prefsFailCalls.size)
    }

    // --- G20: Cancellation vs Worker execution race (pre-IPC stop guarantees 0 SMS) ---

    @Test
    fun `G20 cancellation before pre-send isStopped recheck guarantees 0 SMS`() = runBlocking {
        insertGuardianAlarm()
        lateinit var workerRef: GuardianWorker
        val racePrefs = mockk<PreferencesManager>(relaxed = true)
        coEvery { racePrefs.getCurrentSettings() } answers {
            // Simulate WorkManager cancelling the worker right after session read, before final pre-IPC check
            workerRef.stop(WorkInfo.STOP_REASON_CANCELLED_BY_APP)
            AppSettings(
                activeAlarmId = 42L,
                activeAlarmScheduledAt = schedA,
                activeAlarmFireId = fireA,
                activeAlarmState = "FIRING"
            )
        }
        val fakeCalls = mutableListOf<FakeSmsCall>()
        workerRef = buildWorker(prefsOverride = racePrefs, fakeSmsCalls = fakeCalls)

        assertEquals(ListenableWorker.Result.success(), workerRef.doWork())
        assertEquals(0, fakeCalls.size)
    }

    // --- G21: Old A cancellation does NOT destroy valid new occurrence C ---

    @Test
    fun `G21 old A cancellation and AlarmScheduler includeFollowUpWorkers contract preserve C`() = runBlocking {
        insertGuardianAlarm(id = 42L)
        val wm = WorkManager.getInstance(context)
        val occA = OwnerWorkIdentity.occurrenceUniqueName(42L, schedA, SupportingWorkType.GUARDIAN)
        val occC = OwnerWorkIdentity.occurrenceUniqueName(42L, schedC, SupportingWorkType.GUARDIAN)
        val tag = GuardianWorker.alarmTag(42L)

        // Start C so C is ENQUEUED
        val service = createInjectedService()
        startAlarmInService(service, 42L, schedC, fireC, startId = 1)
        // Manually enqueue A as a lingering old occurrence work with same alarmTag
        wm.enqueueUniqueWork(
            occA,
            ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<GuardianWorker>()
                .setInitialDelay(60, TimeUnit.SECONDS)
                .setInputData(validInputData(42L, schedA, fireA))
                .addTag(tag)
                .build()
        )

        // Simulate late dismiss of missing-row alarm / occurrence A using ACTION_DISMISS with A's generation while A's unique work exists
        // Also directly test occurrence-only cancel vs full AlarmScheduler sweep
        wm.cancelUniqueWork(occA)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(occA).get(2, TimeUnit.SECONDS).first().state)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(occC).get(2, TimeUnit.SECONDS).first().state)

        // Whole-alarm AlarmScheduler.cancelScheduledEntries with includeFollowUpWorkers=false preserves C, true sweeps tag
        val realScheduler = AlarmScheduler(
            context = context,
            repository = alarmRepository,
            calculator = NextAlarmCalculator(),
            preferencesManager = preferencesManager,
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = incidentRepository,
            weatherRepository = mockk(relaxed = true)
        )
        realScheduler.cancelScheduledEntries(42L, includeFollowUpWorkers = false)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfosForUniqueWork(occC).get(2, TimeUnit.SECONDS).first().state)

        realScheduler.cancelScheduledEntries(42L, includeFollowUpWorkers = true)
        assertEquals(WorkInfo.State.CANCELLED, wm.getWorkInfosForUniqueWork(occC).get(2, TimeUnit.SECONDS).first().state)
    }

    // --- G22: Fake SMS failure and exception record diagnostic FAILED without real SmsManager ---

    @Test
    fun `G22 fake SMS send failure and exception record diagnostic FAILED with 0 real SMS`() = runBlocking {
        insertGuardianAlarm()

        val failWorker = buildWorker(
            smsSender = { _, _ -> false }
        )
        assertEquals(ListenableWorker.Result.success(), failWorker.doWork())

        val excWorker = buildWorker(
            smsSender = { _, _ -> throw IllegalStateException("Simulated telephony IPC failure") }
        )
        assertEquals(ListenableWorker.Result.success(), excWorker.doWork())

        val incidents = db.alarmIncidentEventDao().getRecent(10)
        assertTrue(
            incidents.any {
                it.type == GuardianWorker.INCIDENT_TYPE_GUARDIAN &&
                    it.status == AlarmIncidentEvent.STATUS_FAILED &&
                    it.reasonCode == "GUARDIAN_SMS_FAILED"
            }
        )
        assertTrue(
            incidents.any {
                it.type == GuardianWorker.INCIDENT_TYPE_GUARDIAN &&
                    it.status == AlarmIncidentEvent.STATUS_FAILED &&
                    it.reasonCode == "GUARDIAN_SMS_EXCEPTION_ILLEGALSTATEEXCEPTION"
            }
        )
    }
}
