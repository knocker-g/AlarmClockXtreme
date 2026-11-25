package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.receiver.BootReceiver
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Integrated Runtime Wiring Test Suite for KNO-75 Phase D3-C2-P3D-P3-C7-I1..I5 & P1..P4 Safety Corrections.
 *
 * Exercises:
 * - C7-I1 & P2: Legacy vs DirectBoot Owner Safety & Symmetric Fencing (T1 - T8).
 * - C7-I2 & P3: Locked Boot Queue Progression (A -> B -> C) & Rearm Exception Resilience.
 * - C7-I3 & P1: Stale Stop Lineage Fencing & 5-Step Audio Handover.
 * - C7-I4 & P4: OS Receiver Wiring & Unlock Receiver Timeout Safety.
 * - C7-I5: Full Integration & Regression.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootRuntimeWiringTest {

    private lateinit var deContext: Context
    private lateinit var ceContext: Context
    private lateinit var database: AlarmDatabase
    private val baseTime = 1_700_000_000_000L

    private fun clearDeStorage() {
        val dePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        dePrefs.edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .remove(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD)
            .commit()
    }

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        deContext = appContext.createDeviceProtectedStorageContext()
        ceContext = appContext
        database = Room.inMemoryDatabaseBuilder(appContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        DirectBootCeTakeoverStore.clear(ceContext)
        clearDeStorage()
    }

    @After
    fun tearDown() {
        database.close()
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        clearDeStorage()
    }

    // =========================================================================
    // C7-I1 & P2: Legacy vs DirectBoot Owner Safety & Symmetric Fencing
    // =========================================================================

    @Test
    fun t1_t2_t4_legacyReceiverRejectsAlreadyDeliveredOccurrence() {
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = 101L,
            triggerTime = baseTime + 10_000L,
            deliveredAt = baseTime + 10_050L,
            generation = 1,
            instanceToken = "tok_101",
            restoreEpoch = 1L
        )

        // Seed record in DE Delivery Ledger as delivered by DirectBoot
        val appendRes = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        assertTrue(appendRes is DeliveryAppendResult.Appended)

        // Legacy AlarmReceiver query returns true -> legacy delivery rejected
        val isDelivered = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 101L, baseTime + 10_000L)
        assertTrue("Legacy AlarmReceiver MUST reject occurrence already delivered by DirectBoot", isDelivered)
    }

    @Test
    fun t8_legacyOnlyRepeatingAlarm_isNotDeliveredByDirectBoot() {
        val isDelivered = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 999L, baseTime + 99_000L)
        assertFalse("Legacy-only alarm not in DirectBoot Ledger MUST NOT be blocked", isDelivered)
    }

    // =========================================================================
    // C7-I2 & P3: Locked Boot Queue Progression & Rearm Exception Resilience
    // =========================================================================

    @Test
    fun i2_queueProgression_A_to_B_armsNextCandidateOnCommit() {
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 201L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_201",
            restoreEpoch = 1L
        )
        val itemB = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 202L,
            triggerTime = baseTime + 20_000L,
            generation = 1,
            instanceToken = "tok_202",
            restoreEpoch = 1L
        )

        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA, itemB), now = baseTime)

        val fakeBoundary = FakeExactAlarmBoundary()
        val bootProv = BootProvenance(bootCount = 1, lastElapsedRealtime = 10_000L)

        DirectBootAlarmReceiver.reconcilerSeam = { ctx ->
            DirectBootRegistrationReconciler.createForTesting(
                context = ctx,
                osBoundary = fakeBoundary,
                clock = { baseTime + 10_050L },
                bootProvenanceProvider = { bootProv }
            )
        }

        // Simulate V2 firing broadcast for A
        val authorizer = DirectBootDeliveryAuthorizer()
        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { baseTime + 10_000L })
        }

        val identityA = DirectBootV2NormalIntentIdentity.fromQueueItem(itemA)
        val ticketRes = authorizer.issueTicket(identityA, nowEpochMs = baseTime + 10_000L)
        assertTrue(ticketRes is TicketIssueResult.Success)
        val ticketId = (ticketRes as TicketIssueResult.Success).ticket.ticketId

        val firingIntent = identityA.createIntent(ceContext).apply {
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketId)
        }

        val gate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(ceContext).apply { isEnabled = true }
        DirectBootAlarmReceiver.outputAuthorityGateSeam = gate
        DirectBootAlarmReceiver.v2DeliveryCapableProvider = { true }

        val receiver = DirectBootAlarmReceiver()
        try {
            receiver.onReceive(ceContext, firingIntent)

            // Verify next candidate B is armed on boundary
            val calls = fakeBoundary.registerCalls
            assertFalse("Reconciler MUST be invoked for next candidate after A's commit", calls.isEmpty())
            assertEquals(202L, calls.last().alarmId)
        } finally {
            DirectBootAlarmReceiver.resetSeamsForTesting()
            gate.isEnabled = false
        }
    }

    @Test
    fun p3_rearmException_preservesQueueAndMarkerFailedState() {
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 301L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_301",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA), now = baseTime)

        val throwingBoundary = object : DirectBootExactAlarmManagerBoundary {
            override fun canScheduleExactAlarms(): Boolean = true
            override fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult {
                return ExactRegistrationResult.SecurityError("Permission revoked during rearm", SecurityException("Revoked"))
            }
            override fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult {
                return ExactCancellationResult.Accepted(identity)
            }
        }

        val bootProv = BootProvenance(bootCount = 1, lastElapsedRealtime = 10_000L)
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = deContext,
            osBoundary = throwingBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { bootProv }
        )

        val result = reconciler.reconcile()
        assertTrue("Failed registration must NOT return StableSuccess, got $result", result !is ReconcilerResult.StableSuccess)

        // Verify Queue remains intact (never deleted on error)
        val queueState = DirectBootAlarmCache.inspectQueueStorageState(deContext)
        assertTrue(queueState is QueueStorageState.ValidWithItems)
        assertEquals(1, (queueState as QueueStorageState.ValidWithItems).items.size)

        // Verify Marker recorded Security Exception
        val markerResult = DirectBootRegistrationMarker.read(deContext)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, marker.mainState)
        assertEquals(RegistrationFailureReason.SECURITY_EXCEPTION, marker.failureReason)
    }

    // =========================================================================
    // C7-I4 & P4: Unlock Receiver Execution & Idempotency
    // =========================================================================

    @Test
    fun i4_unlockReceiver_consumesPendingCeRecordsPostUnlock() = runBlocking {
        val missedRecord = DirectBootMissedRecord.createNormal(
            alarmId = 401L,
            triggerTime = baseTime + 40_000L,
            missedAt = baseTime + 40_500L,
            generation = 1,
            instanceToken = "tok_401",
            classification = DirectBootMissedRecord.CLASSIFICATION_OVERDUE,
            restoreEpoch = 1L
        )

        DirectBootCeTakeoverStore.recordMissed(ceContext, missedRecord, baseTime + 40_600L)

        // Inject database seam into UnlockReceiver
        DirectBootUnlockReceiver.databaseSeam = { database }

        val latch = CountDownLatch(1)
        var consumerOutcome: DirectBootEventConsumerEngine.ConsumerExecutionResult? = null
        DirectBootUnlockReceiver.onConsumerFinishedSeam = { res ->
            consumerOutcome = res
            latch.countDown()
        }

        val unlockIntent = Intent(Intent.ACTION_USER_UNLOCKED)
        val receiver = DirectBootUnlockReceiver()
        receiver.onReceive(ceContext, unlockIntent)

        latch.await(5, TimeUnit.SECONDS)

        assertNotNull(consumerOutcome)
        assertTrue(consumerOutcome is DirectBootEventConsumerEngine.ConsumerExecutionResult.Success)

        // Verify Room 38 receipt written
        val dao = database.directBootOccurrenceReceiptDao()
        assertEquals(1L, dao.count())
        val occKey = DirectBootCeTakeoverStore.computeOccurrenceKey(401L, baseTime + 40_000L, 1, "tok_401")
        val receipt = dao.findByOccurrenceKey(occKey)
        assertNotNull(receipt)
        assertEquals("CONSUMED_MISSED", receipt?.consumerOutcome)

        // Verify CE slot released
        val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext)
        assertTrue(ceState is CeTakeoverStorageState.ValidEmpty)
    }

    // Fake Exact Alarm Boundary helper for test
    private class FakeExactAlarmBoundary : DirectBootExactAlarmManagerBoundary {
        val registerCalls = mutableListOf<DirectBootV2NormalIntentIdentity>()
        val cancelCalls = mutableListOf<DirectBootV2NormalIntentIdentity>()

        override fun canScheduleExactAlarms(): Boolean = true

        override fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult {
            registerCalls.add(identity)
            return ExactRegistrationResult.Accepted(identity)
        }

        override fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult {
            cancelCalls.add(identity)
            return ExactCancellationResult.Accepted(identity)
        }
    }

    // =========================================================================
    // KNO-75 G4-STEP6-P23 RED: M1 Two-Alarm Preemption, M5 Rollback, Cross-Lane
    // =========================================================================

    private class CapturingStartServiceContext(
        base: Context,
        private val shouldThrowOnStart: Boolean = false
    ) : android.content.ContextWrapper(base) {
        val startedIntents = mutableListOf<Intent>()
        override fun startForegroundService(service: Intent?): android.content.ComponentName? {
            if (shouldThrowOnStart) {
                throw IllegalStateException("Background foreground service start blocked")
            }
            if (service != null) startedIntents.add(service)
            return android.content.ComponentName(packageName, DirectBootAlarmService::class.java.name)
        }
    }

    @Test
    fun p23_m1_twoAlarmSequentialPreemption_stopAndAutoStopFencing_andCleanIdleRelease() {
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        DirectBootServiceTestFixture.configureAudioAndVibrationEnvironment(ceContext)
        DirectBootAlarmReceiver.resetSeamsForTesting()

        val now = System.currentTimeMillis()
        val t1 = now - 20_000L
        val t2 = now - 10_000L
        val q1 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 810L,
            triggerTime = t1,
            generation = 1,
            instanceToken = "tok_m1_810",
            restoreEpoch = 2L
        )
        val q2 = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 820L,
            triggerTime = t2,
            generation = 1,
            instanceToken = "tok_m1_820",
            restoreEpoch = 2L
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(q1, q2), now = t1 - 5_000L))

        val fakeBoundary = FakeExactAlarmBoundary()
        DirectBootAlarmReceiver.reconcilerSeam = { ctx ->
            DirectBootRegistrationReconciler.createForTesting(
                context = ctx,
                osBoundary = fakeBoundary,
                clock = { now },
                bootProvenanceProvider = { BootProvenance(bootCount = null, lastElapsedRealtime = android.os.SystemClock.elapsedRealtime(), bootEpochEstimateMs = now - android.os.SystemClock.elapsedRealtime()) }
            )
        }

        val gate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(ceContext).apply { isEnabled = true }
        DirectBootAlarmReceiver.outputAuthorityGateSeam = gate
        DirectBootAlarmReceiver.v2DeliveryCapableProvider = { true }

        val captureCtx = CapturingStartServiceContext(ceContext)
        val receiver = DirectBootAlarmReceiver()
        val svcController = org.robolectric.Robolectric.buildService(DirectBootAlarmService::class.java)
        val service = svcController.create().get()
        service.outputAuthorityGate = gate
        service.v2DeliveryCapableProvider = { true }
        try {
            // Alarm 1 fires (ticketless OS intent) -> Receiver commits HANDOVER_PENDING(seq=1) -> Service promotes V2_ACTIVE(seq=2)
            receiver.onReceive(captureCtx, DirectBootV2NormalIntentIdentity.fromQueueItem(q1).createIntent(ceContext))
            assertEquals(1, captureCtx.startedIntents.size)
            service.onStartCommand(captureCtx.startedIntents[0], 0, 1)

            val snap1 = DirectBootServiceTestFixture.captureEffects(service)
            assertEquals(810L, snap1.alarmId)
            assertEquals("tok_m1_810", snap1.instanceToken)
            assertTrue("M1: Alarm 1 MUST play audio", snap1.mediaPlayerPlaying)
            val owner1 = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
            assertEquals(OwnerState.V2_ACTIVE, owner1.owner.state)
            assertEquals(810L, owner1.owner.activeSession?.alarmId)
            val oldPlayer = snap1.mediaPlayer

            // Alarm 2 arrives -> Receiver commits HANDOVER_PENDING(A2, seq=3) -> Service stops A1 & promotes V2_ACTIVE(A2, seq=4)
            receiver.onReceive(captureCtx, DirectBootV2NormalIntentIdentity.fromQueueItem(q2).createIntent(ceContext))
            assertEquals(2, captureCtx.startedIntents.size)
            service.onStartCommand(captureCtx.startedIntents[1], 0, 2)

            val snap2 = DirectBootServiceTestFixture.captureEffects(service)
            assertEquals(820L, snap2.alarmId)
            assertEquals("tok_m1_820", snap2.instanceToken)
            assertTrue("M1: Alarm 2 MUST play audio", snap2.mediaPlayerPlaying)
            assertTrue("M1: Alarm 1 player MUST be replaced", oldPlayer !== snap2.mediaPlayer)
            val owner2 = DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success
            assertEquals(OwnerState.V2_ACTIVE, owner2.owner.state)
            assertEquals(820L, owner2.owner.activeSession?.alarmId)

            // Stale STOP for Alarm 1 MUST be rejected without stopping Alarm 2
            val staleStopA1 = Intent(ceContext, DirectBootAlarmService::class.java).apply {
                action = DirectBootAlarmService.ACTION_STOP
                putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 810L)
                putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, t1)
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_m1_810")
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 2L)
            }
            service.onStartCommand(staleStopA1, 0, 3)
            assertFalse("Stale A1 STOP MUST NOT stop active A2", DirectBootServiceTestFixture.captureEffects(service).stoppedBySelf)
            assertEquals(OwnerState.V2_ACTIVE, (DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success).owner.state)

            // Valid STOP for Alarm 2 cleanly stops output and CAS-releases V2_ACTIVE -> IDLE (M11)
            val stopA2 = Intent(ceContext, DirectBootAlarmService::class.java).apply {
                action = DirectBootAlarmService.ACTION_STOP
                putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 820L)
                putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, t2)
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_m1_820")
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 2L)
            }
            service.onStartCommand(stopA2, 0, 4)
            val snapAfterStop = DirectBootServiceTestFixture.captureEffects(service)
            assertTrue("M11: Valid STOP for A2 MUST stopSelf", snapAfterStop.stoppedBySelf)
            assertFalse(snapAfterStop.mediaPlayerPlaying)
            assertEquals(OwnerState.IDLE, (DirectBootDurableOwnerStore.read(deContext) as OwnerReadResult.Success).owner.state)
        } finally {
            svcController.destroy()
            DirectBootAlarmReceiver.resetSeamsForTesting()
            DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        }
    }

    @Test
    fun p23_m5_serviceStartFailure_recordsMissedAndReleasesHandoverPendingToIdle() {
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        DirectBootAlarmReceiver.resetSeamsForTesting()
        try {
            val now = System.currentTimeMillis()
            val t1 = now - 5_000L
            val q1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 830L,
                triggerTime = t1,
                generation = 1,
                instanceToken = "tok_m5_830",
                restoreEpoch = 2L
            )
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(q1), now = t1 - 5_000L))

            val gate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(ceContext).apply { isEnabled = true }
            DirectBootAlarmReceiver.outputAuthorityGateSeam = gate
            DirectBootAlarmReceiver.v2DeliveryCapableProvider = { true }

            val throwingCtx = CapturingStartServiceContext(ceContext, shouldThrowOnStart = true)
            DirectBootAlarmReceiver().onReceive(throwingCtx, DirectBootV2NormalIntentIdentity.fromQueueItem(q1).createIntent(ceContext))

            val missed = DirectBootMissedStore.readUnacknowledgedRecords(deContext)
            assertTrue(missed is MissedStoreStorageState.ValidWithRecords)
            assertEquals(DirectBootMissedRecord.CLASSIFICATION_SERVICE_START_FAILED, (missed as MissedStoreStorageState.ValidWithRecords).records.single().classification)

            val ownerRead = DirectBootDurableOwnerStore.read(deContext)
            assertTrue("M5: Owner MUST be released to IDLE on startForegroundService failure, got $ownerRead", ownerRead is OwnerReadResult.Success)
            assertEquals(OwnerState.IDLE, (ownerRead as OwnerReadResult.Success).owner.state)
        } finally {
            DirectBootAlarmReceiver.resetSeamsForTesting()
            DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        }
    }

    @Test
    fun p23_crossLane_gateEnabled_blocksSameOccurrenceLegacyV1ClaimAndPreemptsDifferentOccurrenceViaArbiter() {
        DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        DirectBootServiceTestFixture.configureAudioAndVibrationEnvironment(ceContext)
        DirectBootAlarmReceiver.resetSeamsForTesting()

        val gate = com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate().apply {
            isEnabled = true
            bootCount = 1L
        }
        DirectBootAlarmReceiver.outputAuthorityGateSeam = gate
        DirectBootAlarmReceiver.v2DeliveryCapableProvider = { true }
        try {
            val now = System.currentTimeMillis()
            val t1 = now - 20_000L
            val t2 = now - 10_000L

            // Case A: Same occurrence already claimed by Legacy V1 -> V2 Receiver MUST reject fail-closed
            val v1Key = OutputOccurrenceKey(alarmId = 840L, scheduledAt = t1, refireCount = 0)
            val reserveRes = gate.reserveForServiceStart(ceContext, v1Key, LegacyClaimSource.ALARM_RECEIVER, directBootModeOnly = true)
            assertTrue(reserveRes is com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized)

            val qSame = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 840L,
                triggerTime = t1,
                generation = 1,
                instanceToken = "tok_same_840",
                restoreEpoch = 2L
            )
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(qSame), now = t1 - 5_000L))
            val captureSame = CapturingStartServiceContext(ceContext)
            DirectBootAlarmReceiver().onReceive(captureSame, DirectBootV2NormalIntentIdentity.fromQueueItem(qSame).createIntent(ceContext))
            assertEquals("Same-occurrence Legacy V1 claim MUST block V2 Receiver", 0, captureSame.startedIntents.size)
            assertEquals(1, DirectBootAlarmCache.readQueue(deContext, now = t1 - 5_000L).size)

            // Case B: Different occurrence (841, t2) arrives while V1 (840, t1) holds Arbiter -> Arbiter stops V1 before V2 plays
            val claimNonce = (reserveRes as com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized).activeNonce
            val claimed = gate.claimOutput(ceContext, v1Key, claimNonce, com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType.DIRECT_BOOT_SERVICE_1011, directBootModeOnly = true)
            assertTrue(claimed is com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized)
            val v1ActiveNonce = (claimed as com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized).activeNonce
            gate.observePhysicalOutput(ceContext, v1Key, v1ActiveNonce, com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType.DIRECT_BOOT_SERVICE_1011)

            val qOther = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = 841L,
                triggerTime = t2,
                generation = 1,
                instanceToken = "tok_other_841",
                restoreEpoch = 2L
            )
            assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(qOther), now = t2 - 5_000L))
            val captureOther = CapturingStartServiceContext(ceContext)
            DirectBootAlarmReceiver().onReceive(captureOther, DirectBootV2NormalIntentIdentity.fromQueueItem(qOther).createIntent(ceContext))
            assertEquals(1, captureOther.startedIntents.size)

            val svcController = org.robolectric.Robolectric.buildService(DirectBootAlarmService::class.java)
            val service = svcController.create().get()
            service.outputAuthorityGate = gate
            service.v2DeliveryCapableProvider = { true }
            try {
                // Inject matching active handle state on Service or let Arbiter confirm preempted handle via handlePreemptedOwner:
                DirectBootServiceTestFixture.seedActiveState(
                    service = service,
                    context = ceContext,
                    alarmId = 840L,
                    triggerTime = t1,
                    generation = 0,
                    instanceToken = "",
                    restoreEpoch = 0L
                )
                DirectBootAlarmService::class.java.getDeclaredField("currentActiveNonce").apply { isAccessible = true }.set(service, v1ActiveNonce)
                DirectBootAlarmService::class.java.getDeclaredField("currentActiveOccurrenceKey").apply { isAccessible = true }.set(service, v1Key)

                service.onStartCommand(captureOther.startedIntents[0], 0, 1)
                val snap = DirectBootServiceTestFixture.captureEffects(service)
                assertEquals(841L, snap.alarmId)
                assertTrue(snap.mediaPlayerPlaying)
                assertEquals(841L, gate.arbiter.activeHandle?.key?.alarmId)
            } finally {
                svcController.destroy()
            }
        } finally {
            gate.arbiter.clearForTesting()
            DirectBootAlarmReceiver.resetSeamsForTesting()
            DirectBootServiceTestFixture.clearAllDirectBootPrefs(deContext)
        }
    }
}

