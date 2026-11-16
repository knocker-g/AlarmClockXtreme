package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.BootProvenance
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwner
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.HandoverClaim
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.OwnerState
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.receiver.MissedAlarmReplayPolicy
import com.sysadmindoc.alarmclock.receiver.MissedAlarmUnlockReceiver
import com.sysadmindoc.alarmclock.service.AlarmFullScreenFallback
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.service.SmartAlarmService
import com.sysadmindoc.alarmclock.worker.FireWatchdogWorker
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class IngressOutputAuthorityIntegrationTest {

    private lateinit var context: Context
    private lateinit var gate: SharedOutputAuthorityGate
    private lateinit var alarmRepository: AlarmRepository
    private lateinit var incidentRepository: AlarmIncidentRepository
    private lateinit var preferencesManager: PreferencesManager

    private val testAlarm = Alarm(
        id = 301L,
        hour = 7,
        minute = 30,
        label = "Ingress Test Alarm",
        isEnabled = true,
        volume = 90
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        shadowOf(context as Application).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        clearStorage()
        AlarmFullScreenFallback.clearForTesting()
        runBlocking {
            MissedAlarmReplayPolicy.resetForTesting()
        }

        gate = SharedOutputAuthorityGate(
            bootCount = 10L,
            processSessionToken = "proc-test-session"
        )

        alarmRepository = mockk(relaxed = true)
        incidentRepository = mockk(relaxed = true)
        preferencesManager = mockk(relaxed = true)

        coEvery { alarmRepository.getById(301L) } returns testAlarm
        coEvery { preferencesManager.getCurrentSettings() } returns AppSettings(repeatMissedAlarms = true)
        every { preferencesManager.getCachedSettings() } returns AppSettings(repeatMissedAlarms = true)
        every { preferencesManager.isSettingsLoaded } returns true

        com.sysadmindoc.alarmclock.receiver.missedAlarmTestAlarmRepository = alarmRepository
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestIncidentRepository = incidentRepository
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestPreferencesManager = preferencesManager
    }

    @After
    fun tearDown() {
        clearStorage()
        gate.arbiter.clearForTesting()
        AlarmFullScreenFallback.clearForTesting()
        runBlocking {
            MissedAlarmReplayPolicy.resetForTesting()
        }
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = null
        com.sysadmindoc.alarmclock.receiver.roomReceiptCheckerSeam = null
        com.sysadmindoc.alarmclock.worker.outputAuthorityGateSeam = null
        com.sysadmindoc.alarmclock.worker.roomReceiptCheckerSeam = null
        com.sysadmindoc.alarmclock.service.smartAlarmOutputAuthorityGateSeam = null
        com.sysadmindoc.alarmclock.service.smartAlarmRoomReceiptCheckerSeam = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmOutputAuthorityGateSeam = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmRoomReceiptCheckerSeam = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestAlarmRepository = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestIncidentRepository = null
        com.sysadmindoc.alarmclock.receiver.missedAlarmTestPreferencesManager = null
    }

    private fun clearStorage() {
        DirectBootLegacyClaimStore.prefs(context)
            .edit()
            .remove(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD)
            .remove("direct_boot_v2_alarm_queue_payload")
            .remove(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER)
            .commit()
    }

    // --- ALARM RECEIVER TESTS ---

    @Test
    fun testAlarmReceiver_flagOff_maintainsLegacyBehavior() {
        gate.isEnabled = false
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 301L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1_000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-301-1")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(context, intent)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("Service must be started in legacy mode", nextIntent)
        assertNull("No claimNonce should be attached when gate is disabled", nextIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE))

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context)
        assertTrue("No DE claims written when gate is disabled", storage is LegacyClaimStorageState.Absent || storage is LegacyClaimStorageState.ValidEmpty)
    }

    @Test
    fun testAlarmReceiver_flagOn_reservesClaimAndPropagatesNonce() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 301L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1_000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-301-2")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(context, intent)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("Service must be started when authorized", nextIntent)
        val claimNonce = nextIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE)
        assertNotNull("claimNonce MUST be attached when gate is enabled", claimNonce)
        assertTrue("claimNonce must be non-blank", claimNonce!!.isNotBlank())

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
        val record = storage.records.firstOrNull { it.alarmId == 301L && it.scheduledAt == 1_000L }
        assertNotNull("DE claim must be persisted", record)
        assertEquals(claimNonce, record!!.claimNonce)
        assertEquals(LegacyClaimState.START_REQUESTED, record.state)
        assertEquals(LegacyClaimSource.ALARM_RECEIVER, record.source)
    }

    @Test
    fun testAlarmReceiver_flagOn_duplicateBroadcast_failsClosed() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 301L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 2_000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-301-dup")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(context, intent)
        val firstStarted = shadowOf(context as Application).nextStartedService
        assertNotNull("First broadcast must succeed", firstStarted)

        // Second broadcast with same occurrence
        receiver.onReceive(context, intent)
        val secondStarted = shadowOf(context as Application).nextStartedService
        assertNull("Duplicate broadcast MUST fail closed and NOT start service", secondStarted)
    }

    @Test
    fun testAlarmReceiver_flagOn_v2CrossLaneCollision_failsClosed() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate

        // Seed V2 durable owner
        val owner = DirectBootDurableOwner(
            state = OwnerState.HANDOVER_PENDING,
            ownerSeq = 1L,
            bootProvenance = BootProvenance(bootCount = 10),
            handoverClaim = HandoverClaim("h-1", 301L, 3_000L, 1, "tok-1", 10L),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        DirectBootDurableOwnerStore.write(context, owner, expectedOwnerSeq = 0L)

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 301L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 3_000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-301-v2")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(context, intent)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNull("Cross-lane collision with V2 MUST fail closed", nextIntent)
    }

    @Test
    fun testAlarmReceiver_flagOn_capacityExhausted_failsClosed() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate

        // Fill capacity to 32
        for (i in 1..32) {
            DirectBootLegacyClaimStore.writeInitialClaim(
                context = context,
                record = LegacyClaimRecord(
                    alarmId = i.toLong(),
                    scheduledAt = 10_000L + i,
                    refireCount = 0,
                    source = LegacyClaimSource.ALARM_RECEIVER,
                    claimNonce = "nonce-cap-$i",
                    processSessionToken = "proc-test-session",
                    bootCount = 10L,
                    state = LegacyClaimState.START_REQUESTED
                )
            )
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 301L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 50_000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-301-cap")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(context, intent)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNull("When DE store capacity 32 is exhausted, ingress MUST fail closed", nextIntent)
    }

    @Test
    fun testIngress_flagOn_corruptStore_failsClosed() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.receiver.outputAuthorityGateSeam = gate

        val prefs = DirectBootLegacyClaimStore.prefs(context)
        prefs.edit().putString(DirectBootLegacyClaimStore.KEY_LEGACY_CLAIM_STORE_PAYLOAD, "{invalid json}").commit()

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 301L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 77_000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-301-corrupt")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(context, intent)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNull("Corrupt storage state MUST fail closed and NOT start service", nextIntent)
    }

    // --- FIRE WATCHDOG TESTS ---

    @Test
    fun testFireWatchdog_flagOff_maintainsLegacyBehavior() {
        gate.isEnabled = false
        com.sysadmindoc.alarmclock.worker.outputAuthorityGateSeam = gate

        val scheduledAt = System.currentTimeMillis() - 2 * 60 * 1000L
        val worker = createWatchdogWorker(301L, scheduledAt)
        val result = runBlocking { worker.doWork() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("Watchdog should start service in legacy mode", nextIntent)
        assertNull("No claimNonce attached in legacy mode", nextIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE))
    }

    @Test
    fun testFireWatchdog_flagOn_validRefire_reservesClaimAndPropagatesNonce() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.worker.outputAuthorityGateSeam = gate

        val scheduledAt = System.currentTimeMillis() - 2 * 60 * 1000L
        val worker = createWatchdogWorker(301L, scheduledAt)
        val result = runBlocking { worker.doWork() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("Watchdog must start service when refire is authorized", nextIntent)
        val nonce = nextIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE)
        assertNotNull("Watchdog MUST propagate claimNonce to service", nonce)

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
        val record = storage.records.firstOrNull { it.alarmId == 301L && it.scheduledAt == scheduledAt }
        assertNotNull(record)
        assertEquals(LegacyClaimSource.FIRE_WATCHDOG, record!!.source)
        assertEquals(nonce, record.claimNonce)
    }

    @Test
    fun testFireWatchdog_flagOn_serviceAlreadyActive_rejectsClaim() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.worker.outputAuthorityGateSeam = gate

        val scheduledAt = System.currentTimeMillis() - 2 * 60 * 1000L
        val key = OutputOccurrenceKey(301L, scheduledAt, 0)
        gate.arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-active-service")

        val worker = createWatchdogWorker(301L, scheduledAt)
        val result = runBlocking { worker.doWork() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNull("Watchdog MUST NOT start service if service is already active in arbiter", nextIntent)
    }

    @Test
    fun testFireWatchdog_flagOn_fgsException_propagatesToFallback() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.worker.outputAuthorityGateSeam = gate
        com.sysadmindoc.alarmclock.worker.roomReceiptCheckerSeam = { _, _ -> false }

        val scheduledAt = System.currentTimeMillis() - 2 * 60 * 1000L

        val spyContext = spyk(context) {
            every { startForegroundService(any()) } throws IllegalStateException("FGS blocked")
        }

        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        val inputData = androidx.work.workDataOf(
            FireWatchdogWorker.KEY_ALARM_ID to 301L,
            FireWatchdogWorker.KEY_SCHEDULED_AT to scheduledAt
        )
        every { workerParams.inputData } returns inputData
        coEvery { incidentRepository.occurrenceDeliveryCount(301L, scheduledAt) } returns 0

        val worker = FireWatchdogWorker(
            context = spyContext,
            workerParams = workerParams,
            alarmRepository = alarmRepository,
            preferencesManager = preferencesManager,
            alarmIncidentRepository = incidentRepository
        )

        val result = runBlocking { worker.doWork() }
        assertEquals(androidx.work.ListenableWorker.Result.success(), result)

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
        val record = storage.records.firstOrNull { it.alarmId == 301L && it.scheduledAt == scheduledAt }
        assertNotNull(record)
        assertEquals(LegacyClaimState.OUTPUT_OBSERVED, record!!.state)
    }

    // --- SMART ALARM TESTS ---

    @Test
    fun testSmartAlarm_flagOff_maintainsLegacyBehavior() {
        gate.isEnabled = false
        com.sysadmindoc.alarmclock.service.smartAlarmOutputAuthorityGateSeam = gate

        val service = createSmartAlarmService(301L, 7_000L)
        service.callStartAlarmServiceForTesting()

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("SmartAlarm must start service in legacy mode", nextIntent)
        assertNull("No claimNonce in legacy mode", nextIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE))
    }

    @Test
    fun testSmartAlarm_flagOn_missingIdentification_failsClosedWithoutFabricatingScheduledAt() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.service.smartAlarmOutputAuthorityGateSeam = gate

        val service = createSmartAlarmService(-1L, 0L)
        service.callStartAlarmServiceForTesting()

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNull("SmartAlarm with missing identification MUST fail closed and NOT start service", nextIntent)
    }

    @Test
    fun testSmartAlarm_flagOn_validTarget_reservesClaimAndPropagatesNonce() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.service.smartAlarmOutputAuthorityGateSeam = gate

        val service = createSmartAlarmService(301L, 8_000L)
        service.callStartAlarmServiceForTesting()

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNotNull("SmartAlarm must start service when authorized", nextIntent)
        val nonce = nextIntent.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE)
        assertNotNull("SmartAlarm MUST propagate claimNonce", nonce)

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
        val record = storage.records.firstOrNull { it.alarmId == 301L && it.scheduledAt == 8_000L }
        assertNotNull(record)
        assertEquals(LegacyClaimSource.SMART_ALARM, record!!.source)
        assertEquals(nonce, record.claimNonce)
    }

    // --- MISSED ALARM UNLOCK TESTS ---

    @Test
    fun testMissedUnlock_flagOff_maintainsLegacyBehavior() {
        gate.isEnabled = false
        com.sysadmindoc.alarmclock.receiver.missedAlarmOutputAuthorityGateSeam = gate
        val missedAt = System.currentTimeMillis() - 60_000L
        seedMissedAlarmState(301L, missedAt)

        val receiver = MissedAlarmUnlockReceiver()
        val intent = Intent(Intent.ACTION_USER_PRESENT)
        receiver.onReceive(context, intent)

        var nextIntent: Intent? = null
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            nextIntent = shadowOf(context as Application).nextStartedService
            if (nextIntent != null) break
            Thread.sleep(50)
        }

        assertNotNull("Missed alarm replay must start service in legacy mode", nextIntent)
        assertNull("No claimNonce attached in legacy mode", nextIntent!!.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE))
    }

    @Test
    fun testMissedUnlock_flagOn_roomReceiptExists_failsClosed() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.receiver.missedAlarmOutputAuthorityGateSeam = gate
        val missedAt = System.currentTimeMillis() - 60_000L
        com.sysadmindoc.alarmclock.receiver.missedAlarmRoomReceiptCheckerSeam = { aId, sAt -> aId == 301L && sAt == missedAt }
        seedMissedAlarmState(301L, missedAt)

        val receiver = MissedAlarmUnlockReceiver()
        val intent = Intent(Intent.ACTION_USER_PRESENT)
        receiver.onReceive(context, intent)

        Thread.sleep(500)

        val nextIntent = shadowOf(context as Application).nextStartedService
        assertNull("Missed replay MUST fail closed if receipt exists in Room", nextIntent)
    }

    @Test
    fun testMissedUnlock_flagOn_validReplay_reservesClaimAndPropagatesNonce() {
        gate.isEnabled = true
        com.sysadmindoc.alarmclock.receiver.missedAlarmOutputAuthorityGateSeam = gate
        com.sysadmindoc.alarmclock.receiver.missedAlarmRoomReceiptCheckerSeam = { _, _ -> false }
        val missedAt = System.currentTimeMillis() - 60_000L
        seedMissedAlarmState(301L, missedAt)

        val receiver = MissedAlarmUnlockReceiver()
        val intent = Intent(Intent.ACTION_USER_PRESENT)
        receiver.onReceive(context, intent)

        var nextIntent: Intent? = null
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            nextIntent = shadowOf(context as Application).nextStartedService
            if (nextIntent != null) break
            Thread.sleep(50)
        }

        assertNotNull("Valid missed replay must start service", nextIntent)
        val nonce = nextIntent!!.getStringExtra(AlarmService.EXTRA_CLAIM_NONCE)
        assertNotNull("Missed replay MUST attach claimNonce", nonce)

        val storage = DirectBootLegacyClaimStore.inspectStorageState(context) as LegacyClaimStorageState.ValidWithRecords
        val record = storage.records.firstOrNull { it.alarmId == 301L && it.scheduledAt == missedAt }
        assertNotNull(record)
        assertEquals(LegacyClaimSource.MISSED_UNLOCK, record!!.source)
        assertEquals(nonce, record.claimNonce)
    }

    // --- CONCURRENCY & INTEGRITY TESTS ---

    @Test
    fun testIngress_concurrentIngress_onlyOneSucceeds() {
        gate.isEnabled = true
        val key = OutputOccurrenceKey(301L, 12_000L, 0)

        val executor = Executors.newFixedThreadPool(2)
        val barrier = java.util.concurrent.CyclicBarrier(2)
        val results = java.util.Collections.synchronizedList(mutableListOf<OutputAuthorizationResult>())

        val f1 = executor.submit {
            barrier.await()
            val r = gate.reserveForServiceStart(context, key, LegacyClaimSource.ALARM_RECEIVER)
            results.add(r)
        }
        val f2 = executor.submit {
            barrier.await()
            val r = gate.reserveForServiceStart(context, key, LegacyClaimSource.FIRE_WATCHDOG)
            results.add(r)
        }

        f1.get(5, TimeUnit.SECONDS)
        f2.get(5, TimeUnit.SECONDS)
        executor.shutdown()

        assertEquals(2, results.size)
        val authorized = results.filterIsInstance<OutputAuthorizationResult.Authorized>()
        val rejected = results.filterIsInstance<OutputAuthorizationResult.DuplicateRejected>()
        assertEquals("Exactly one concurrent ingress reservation MUST be authorized", 1, authorized.size)
        assertEquals("Conflicting concurrent ingress reservation MUST be rejected", 1, rejected.size)
    }

    // Helper functions

    private fun createWatchdogWorker(alarmId: Long, scheduledAt: Long): FireWatchdogWorker {
        val workerParams = mockk<androidx.work.WorkerParameters>(relaxed = true)
        val inputData = androidx.work.workDataOf(
            FireWatchdogWorker.KEY_ALARM_ID to alarmId,
            FireWatchdogWorker.KEY_SCHEDULED_AT to scheduledAt
        )
        every { workerParams.inputData } returns inputData
        coEvery { incidentRepository.occurrenceDeliveryCount(alarmId, scheduledAt) } returns 0

        val settings = AppSettings(repeatMissedAlarms = true)
        coEvery { preferencesManager.getCurrentSettings() } returns settings

        return FireWatchdogWorker(
            context = context,
            workerParams = workerParams,
            alarmRepository = alarmRepository,
            preferencesManager = preferencesManager,
            alarmIncidentRepository = incidentRepository
        )
    }

    private fun createSmartAlarmService(alarmId: Long, targetTime: Long): SmartAlarmService {
        val controller = Robolectric.buildService(SmartAlarmService::class.java)
        val service = controller.get()
        service.alarmRepository = alarmRepository
        service.preferencesManager = preferencesManager
        service.alarmIncidentRepository = incidentRepository
        service.actigraphyRepository = mockk(relaxed = true)
        service.alarmScheduler = mockk(relaxed = true)

        val idField = SmartAlarmService::class.java.getDeclaredField("alarmId").apply { isAccessible = true }
        idField.set(service, alarmId)
        val timeField = SmartAlarmService::class.java.getDeclaredField("targetTimeMs").apply { isAccessible = true }
        timeField.set(service, targetTime)

        return service
    }

    private fun seedMissedAlarmState(alarmId: Long, missedAt: Long) {
        val store = context.getSharedPreferences("missed_alarm_state", Context.MODE_PRIVATE)
        store.edit()
            .putLong("last_missed_id", alarmId)
            .putLong("last_missed_at", missedAt)
            .putLong("last_missed_dispatched_at", 0L)
            .commit()

        val settings = AppSettings(repeatMissedAlarms = true)
        coEvery { preferencesManager.getCurrentSettings() } returns settings
        coEvery { incidentRepository.occurrenceDeliveryCount(alarmId, missedAt) } returns 0
    }
}
