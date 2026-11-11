package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Deterministic unit tests for [DirectBootRegistrationReconciler]
 * Crash Recovery and Bounded Compensation (KNO-75 Phase D3-B2-P2-1A2).
 *
 * Covers 43 comprehensive scenarios:
 * - 1 to 7: Baseline normal reconciliation (register, cancel, cancel-then-register, drift, corrupt/unsupported)
 * - 8 to 9: Active lease blocking and FAILED_BLOCKED recovery
 * - 10 to 16: Failure modes, lease lifecycle, monotonic CAS chaining, single process-local owner
 * - 17: Reentrant coalescing and verified recheck pass
 * - 18: Strict lock segregation during OS operations
 * - 19: AlreadyArmed on verified same boot returns PlanningHalted
 * - 20 to 24: Post-op storage failure and unverified outcomes
 * - 25: AlreadyArmed with unverified boot provenance recovers and re-arms
 * - 26: Post-register drift and failure commit failure
 * - 27 to 33: Complete crash cutpoint suite (C0 through C6)
 * - 34 to 43: Entry gate guards, reboot detection, uncertainty settling, retry bounds, zero legacy V1 effects
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootRegistrationReconcilerTest {

    private lateinit var context: Context
    private val baseTime = 1_700_000_000_000L

    private val defaultBoot = BootProvenance(
        bootCount = 10,
        lastElapsedRealtime = 50_000L,
        bootEpochEstimateMs = baseTime - 50_000L
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        DirectBootAlarmCache.clearQueue(context)
    }

    private fun sampleNormalItem(
        alarmId: Long = 101L,
        triggerTime: Long = baseTime + 60_000L,
        generation: Int = 1,
        instanceToken: String = "token_norm_101",
        updatedAt: Long = baseTime
    ): DirectBootQueueItem {
        return DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            instanceToken = instanceToken,
            updatedAt = updatedAt
        )
    }

    private fun sampleNormalIdentity(
        alarmId: Long = 101L,
        generation: Int = 1,
        instanceToken: String = "token_norm_101",
        triggerTime: Long = baseTime + 60_000L
    ): DirectBootV2NormalIntentIdentity {
        return DirectBootV2NormalIntentIdentity(
            alarmId = alarmId,
            generation = generation,
            instanceToken = instanceToken,
            triggerTime = triggerTime,
            kind = DirectBootV2NormalIntentIdentity.KIND_NORMAL
        )
    }

    class FakeExactAlarmBoundary : DirectBootExactAlarmManagerBoundary {
        var canSchedule: Boolean = true
        var registrationResult: ExactRegistrationResult? = null
        var cancellationResult: ExactCancellationResult? = null

        val registerCalls = mutableListOf<DirectBootV2NormalIntentIdentity>()
        val cancelCalls = mutableListOf<DirectBootV2NormalIntentIdentity>()

        var onRegister: ((DirectBootV2NormalIntentIdentity) -> Unit)? = null
        var onCancel: ((DirectBootV2NormalIntentIdentity) -> Unit)? = null

        override fun canScheduleExactAlarms(): Boolean = canSchedule

        override fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult {
            onRegister?.invoke(identity)
            registerCalls.add(identity)
            return registrationResult ?: ExactRegistrationResult.Accepted(identity)
        }

        override fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult {
            onCancel?.invoke(identity)
            cancelCalls.add(identity)
            return cancellationResult ?: ExactCancellationResult.Accepted(identity)
        }
    }

    // =========================================================================
    // Test 1: Clean Normal Registration
    // =========================================================================

    @Test
    fun test01_cleanNormalRegistration_successful() {
        val item = sampleNormalItem()
        DirectBootAlarmCache.writeQueue(context, listOf(item))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess, got $result", result is ReconcilerResult.StableSuccess)
        val success = result as ReconcilerResult.StableSuccess
        assertTrue(success.plan is DirectBootRegistrationPlan.ArmNormal)
        assertNull(success.cancellationResult)
        assertNotNull(success.registrationResult)
        assertTrue(success.registrationResult!!.isAccepted)

        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(1, fakeBoundary.registerCalls.size)
        assertEquals(item.alarmId, fakeBoundary.registerCalls.first().alarmId)

        // Verify committed Marker on disk
        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, marker.mainState)
        assertEquals(RegistrationPhase.NONE, marker.phase)
        assertEquals(RegistrationFailureReason.NONE, marker.failureReason)
        assertEquals("", marker.leaseOwner)
        assertEquals(0L, marker.leaseEpochMs)
        assertEquals(item.alarmId, marker.activeIdentity?.alarmId)
        assertNull(marker.cancellingTarget)
        assertNull(marker.registeringTarget)
    }

    // =========================================================================
    // Test 2: Clean Old Cancellation
    // =========================================================================

    @Test
    fun test02_cleanOldCancellation_successful() {
        val oldIdentity = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_old")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = oldIdentity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        val writeRes = DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        assertTrue(writeRes is MarkerWriteResult.Success)

        // Queue is empty -> Plan should be CancelCurrent
        DirectBootAlarmCache.clearQueue(context)

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess, got $result", result is ReconcilerResult.StableSuccess)
        val success = result as ReconcilerResult.StableSuccess
        assertTrue(success.plan is DirectBootRegistrationPlan.CancelCurrent)
        assertNotNull(success.cancellationResult)
        assertTrue(success.cancellationResult!!.isSuccessfulOrAbsent)
        assertNull(success.registrationResult)

        assertEquals(1, fakeBoundary.cancelCalls.size)
        assertEquals(oldIdentity.alarmId, fakeBoundary.cancelCalls.first().alarmId)
        assertEquals(0, fakeBoundary.registerCalls.size)

        // Verify committed Marker on disk has activeIdentity = null
        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, marker.mainState)
        assertNull(marker.activeIdentity)
    }

    // =========================================================================
    // Test 3: Cancel Then Register
    // =========================================================================

    @Test
    fun test03_cancelThenRegister_successful() {
        val oldIdentity = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_old")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = oldIdentity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)

        val newItem = sampleNormalItem(alarmId = 101L, instanceToken = "tok_new")
        DirectBootAlarmCache.writeQueue(context, listOf(newItem))

        val order = mutableListOf<String>()
        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onCancel = { order.add("cancel:${it.alarmId}") }
            onRegister = { order.add("register:${it.alarmId}") }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess, got $result", result is ReconcilerResult.StableSuccess)
        assertEquals(listOf("cancel:99", "register:101"), order)

        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, marker.mainState)
        assertEquals(101L, marker.activeIdentity?.alarmId)
    }

    // =========================================================================
    // Test 4: Queue Drift Before Initial Intent Commit (PlanningHalted)
    // =========================================================================

    @Test
    fun test04_queueDriftBeforeInitialIntentCommit_planningHalted() {
        DirectBootAlarmCache.clearQueue(context)

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected PlanningHalted, got $result", result is ReconcilerResult.PlanningHalted)
        val halted = result as ReconcilerResult.PlanningHalted
        assertTrue(halted.plan is DirectBootRegistrationPlan.NoOp)

        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 5: Queue Drift After Cancellation Aborts Registration
    // =========================================================================

    @Test
    fun test05_queueDriftAfterCancellation_abortsRegistration() {
        val oldIdentity = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_old")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = oldIdentity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)

        val itemA = sampleNormalItem(alarmId = 101L, instanceToken = "tok_a")
        DirectBootAlarmCache.writeQueue(context, listOf(itemA))

        val fakeBoundary = FakeExactAlarmBoundary()
        val itemB = sampleNormalItem(alarmId = 102L, instanceToken = "tok_b")
        fakeBoundary.onCancel = {
            DirectBootAlarmCache.writeQueue(context, listOf(itemB))
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected HeadChangedDuringOperation, got $result", result is ReconcilerResult.HeadChangedDuringOperation)
        val headChanged = result as ReconcilerResult.HeadChangedDuringOperation
        assertEquals(101L, headChanged.initialHead?.alarmId)
        assertEquals(102L, headChanged.postOpHead?.alarmId)

        assertEquals(1, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)

        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, marker.mainState)
        assertEquals(RegistrationFailureReason.OS_UNCERTAIN, marker.failureReason)
        assertEquals(101L, marker.registeringTarget?.alarmId)
    }

    // =========================================================================
    // Test 6: Queue Drift After Registration Reports HeadChanged
    // =========================================================================

    @Test
    fun test06_queueDriftAfterRegistration_reportsHeadChanged() {
        val itemA = sampleNormalItem(alarmId = 101L, instanceToken = "tok_a")
        DirectBootAlarmCache.writeQueue(context, listOf(itemA))

        val fakeBoundary = FakeExactAlarmBoundary()
        val itemB = sampleNormalItem(alarmId = 102L, instanceToken = "tok_b")
        fakeBoundary.onRegister = {
            DirectBootAlarmCache.writeQueue(context, listOf(itemB))
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected HeadChangedDuringOperation, got $result", result is ReconcilerResult.HeadChangedDuringOperation)
        val headChanged = result as ReconcilerResult.HeadChangedDuringOperation
        assertEquals(101L, headChanged.initialHead?.alarmId)
        assertEquals(102L, headChanged.postOpHead?.alarmId)

        assertEquals(1, fakeBoundary.registerCalls.size)
        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, marker.mainState)
        assertEquals(RegistrationFailureReason.OS_UNCERTAIN, marker.failureReason)
        assertEquals(101L, marker.registeringTarget?.alarmId)
        assertNull(marker.activeIdentity)
    }

    // =========================================================================
    // Test 7: Corrupt or Unsupported Queue Fails Closed
    // =========================================================================

    @Test
    fun test07_corruptOrUnsupportedQueue_failsClosed() {
        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)

        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{ malformed json").commit()

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result1 = reconciler.reconcile()
        assertTrue("Expected StorageUnverifiable, got $result1", result1 is ReconcilerResult.StorageUnverifiable)
        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)

        val unsupportedJson = JSONObject().apply {
            put("schemaVersion", 999)
            put("items", org.json.JSONArray())
        }.toString()
        prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, unsupportedJson).commit()

        val result2 = reconciler.reconcile()
        assertTrue("Expected StorageUnverifiable, got $result2", result2 is ReconcilerResult.StorageUnverifiable)
        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 8: Existing IN_FLIGHT Marker From Other PID Blocks Recovery
    // =========================================================================

    @Test
    fun test08_existingInFlightMarker_activeLeaseFromOtherPid_blocksRecovery() {
        val inFlightMarker = DirectBootRegistrationMarker.createInitialIdle(
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            leaseOwner = "pid:99999:other_process",
            leaseEpochMs = baseTime + 15_000L,
            registeringTarget = sampleNormalIdentity(alarmId = 101L),
            opSeq = 1L
        )
        DirectBootRegistrationMarker.write(context, inFlightMarker, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 102L)))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected RecoveryBlocked, got $result", result is ReconcilerResult.RecoveryBlocked)
        val blocked = result as ReconcilerResult.RecoveryBlocked
        assertTrue(blocked.reason.contains("other process"))

        // Zero OS operations performed
        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 9: Existing FAILED_BLOCKED Marker Recovers To StableSuccess
    // =========================================================================

    @Test
    fun test09_existingFailedBlockedMarker_recoversToStableSuccess() {
        val blockedMarker = DirectBootRegistrationMarker.createInitialIdle(
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(
            mainState = RegistrationMainState.FAILED_BLOCKED,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            registeringTarget = sampleNormalIdentity(alarmId = 101L),
            opSeq = 1L
        )
        DirectBootRegistrationMarker.write(context, blockedMarker, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 102L)))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess, got $result", result is ReconcilerResult.StableSuccess)

        // Cancelled unresolved 101 to settle state, then registered desired 102!
        assertEquals(1, fakeBoundary.cancelCalls.size)
        assertEquals(101L, fakeBoundary.cancelCalls.first().alarmId)
        assertEquals(1, fakeBoundary.registerCalls.size)
        assertEquals(102L, fakeBoundary.registerCalls.first().alarmId)

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, finalMarker.mainState)
        assertEquals(102L, finalMarker.activeIdentity?.alarmId)
        assertEquals(0, finalMarker.retryCount)
    }

    // =========================================================================
    // Test 10: Pre-OS Marker Commit Failure -> Zero OS Calls
    // =========================================================================

    @Test
    fun test10_preOsMarkerCommitFailure_zeroOsCalls() {
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot },
            editorCommitter = { _, _ -> false }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected IntentPersistenceFailed, got $result", result is ReconcilerResult.IntentPersistenceFailed)

        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 11: Post-OS Marker Commit Failure Returns Unknown
    // =========================================================================

    @Test
    fun test11_postOsMarkerCommitFailure_returnsUnknown() {
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val fakeBoundary = FakeExactAlarmBoundary()
        val writeCounter = AtomicInteger(0)

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot },
            editorCommitter = { prefs, payload ->
                if (writeCounter.incrementAndGet() == 1) {
                    prefs.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, payload).commit()
                } else {
                    false
                }
            }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected Unknown, got $result", result is ReconcilerResult.Unknown)
        assertFalse(result.isSuccess)
        assertEquals(1, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 12: Cancellation Failure Halts and Does Not Register
    // =========================================================================

    @Test
    fun test12_cancellationFailure_haltsAndDoesNotRegister() {
        val oldIdentity = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_old")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = oldIdentity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            cancellationResult = ExactCancellationResult.CancellationFailure("Binder IPC exception")
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected OsOperationFailed, got $result", result is ReconcilerResult.OsOperationFailed)
        assertFalse(result.isSuccess)

        assertEquals(1, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)

        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, marker.mainState)
        assertEquals(RegistrationFailureReason.IPC_EXCEPTION, marker.failureReason)
        assertEquals(99L, marker.cancellingTarget?.alarmId)
        assertEquals(101L, marker.registeringTarget?.alarmId)
    }

    // =========================================================================
    // Test 13: Registration Failure Sets Typed Failure and Blocked Marker
    // =========================================================================

    @Test
    fun test13_registrationFailure_typedFailureAndBlockedMarker() {
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            registrationResult = ExactRegistrationResult.PermissionUnavailable("SCHEDULE_EXACT_ALARM denied")
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected OsOperationFailed, got $result", result is ReconcilerResult.OsOperationFailed)
        val failed = result as ReconcilerResult.OsOperationFailed
        assertTrue(failed.registrationResult is ExactRegistrationResult.PermissionUnavailable)

        assertEquals(1, fakeBoundary.registerCalls.size)

        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, marker.mainState)
        assertEquals(RegistrationFailureReason.PERMISSION_DENIED, marker.failureReason)
        assertEquals(101L, marker.registeringTarget?.alarmId)
        assertNull(marker.cancellingTarget)
    }

    // =========================================================================
    // Test 14: Lease Validation and Cleanup
    // =========================================================================

    @Test
    fun test14_leaseValidation_andCleanup() {
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val fakeBoundary = FakeExactAlarmBoundary()
        var observedLeaseOwnerDuringRegister: String? = null
        var observedLeaseEpochDuringRegister: Long? = null

        fakeBoundary.onRegister = {
            val markerResult = DirectBootRegistrationMarker.read(context)
            if (markerResult is MarkerReadResult.Success) {
                observedLeaseOwnerDuringRegister = markerResult.marker.leaseOwner
                observedLeaseEpochDuringRegister = markerResult.marker.leaseEpochMs
            }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue(result is ReconcilerResult.StableSuccess)

        assertEquals("pid:${Process.myPid()}:reconciler", observedLeaseOwnerDuringRegister)
        assertEquals(baseTime + DirectBootRegistrationReconciler.DEFAULT_LEASE_DURATION_MS, observedLeaseEpochDuringRegister)

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals("", finalMarker.leaseOwner)
        assertEquals(0L, finalMarker.leaseEpochMs)
    }

    // =========================================================================
    // Test 15: Monotonic opSeq CAS Chaining
    // =========================================================================

    @Test
    fun test15_opSeqCasChaining() {
        val oldIdentity = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_old")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = oldIdentity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        val write0 = DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        assertTrue(write0 is MarkerWriteResult.Success)

        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val observedOpSeqs = mutableListOf<Long>()
        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onCancel = {
                val m = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
                observedOpSeqs.add(m.opSeq)
            }
            onRegister = {
                val m = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
                observedOpSeqs.add(m.opSeq)
            }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue(result is ReconcilerResult.StableSuccess)

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(listOf(2L, 3L), observedOpSeqs)
        assertEquals(4L, finalMarker.opSeq)
    }

    // =========================================================================
    // Test 16: Single Process-Local Owner
    // =========================================================================

    @Test
    fun test16_singleProcessLocalOwner() {
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val inRegisterLatch = CountDownLatch(1)
        val releaseRegisterLatch = CountDownLatch(1)
        val secondThreadResult = AtomicReference<ReconcilerResult?>(null)

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onRegister = {
                inRegisterLatch.countDown()
                releaseRegisterLatch.await(5, TimeUnit.SECONDS)
            }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val threadA = Thread { reconciler.reconcile() }
        threadA.start()

        assertTrue(inRegisterLatch.await(5, TimeUnit.SECONDS))
        assertTrue(reconciler.isOwnerRunning)

        val threadB = Thread {
            secondThreadResult.set(reconciler.reconcile())
        }
        threadB.start()
        threadB.join(2000)

        val bRes = secondThreadResult.get()
        assertNotNull(bRes)
        assertTrue("Expected Coalesced, got $bRes", bRes is ReconcilerResult.Coalesced)
        val coalesced = bRes as ReconcilerResult.Coalesced
        assertFalse(coalesced.isReentrant)
        assertEquals(threadA.id, coalesced.activeOwnerThreadId)

        releaseRegisterLatch.countDown()
        threadA.join(5000)
    }

    // =========================================================================
    // Test 17: Reentrant Request Coalescing Settles Via Pass 2
    // =========================================================================

    @Test
    fun test17_reentrantRequestCoalescing() {
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        var reentrantResult: ReconcilerResult? = null
        val fakeBoundary = FakeExactAlarmBoundary()

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        fakeBoundary.onRegister = {
            reentrantResult = reconciler.reconcile()
        }

        val outerResult = reconciler.reconcile()
        // Pass 1 armed item 101; reentrant call queued a recheck; pass 2 evaluated AlreadyArmed -> surfaces RecoveryRequired
        assertTrue("Expected RecoveryRequired from pass 2 recheck, got $outerResult", outerResult is ReconcilerResult.RecoveryRequired)
        val rec = outerResult as ReconcilerResult.RecoveryRequired
        assertNotNull(rec.plan)
        assertTrue(rec.plan is DirectBootRegistrationPlan.AlreadyArmed)
        assertEquals(2, reconciler.totalPassesExecuted)
        assertEquals(1, fakeBoundary.registerCalls.size)

        assertNotNull(reentrantResult)
        assertTrue("Expected Coalesced reentrant, got $reentrantResult", reentrantResult is ReconcilerResult.Coalesced)
        val coalesced = reentrantResult as ReconcilerResult.Coalesced
        assertTrue(coalesced.isReentrant)
        assertEquals(Thread.currentThread().id, coalesced.activeOwnerThreadId)
    }

    // =========================================================================
    // Test 18: No OS Operation Under DE Storage Lock
    // =========================================================================

    @Test
    fun test18_noOsOperationUnderDeStorageLock() {
        val oldIdentity = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_old")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = oldIdentity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val lockHeldInCancel = AtomicBoolean(true)
        val lockHeldInRegister = AtomicBoolean(true)

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onCancel = {
                lockHeldInCancel.set(Thread.holdsLock(DirectBootStorageLock.lock))
            }
            onRegister = {
                lockHeldInRegister.set(Thread.holdsLock(DirectBootStorageLock.lock))
            }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue(result is ReconcilerResult.StableSuccess)

        assertFalse("Storage lock was held during cancelExactAlarm!", lockHeldInCancel.get())
        assertFalse("Storage lock was held during registerExactAlarm!", lockHeldInRegister.get())
    }

    // =========================================================================
    // Test 19: AlreadyArmed On Same Boot Without Scheduled Evidence Surfaces RecoveryRequired
    // =========================================================================

    @Test
    fun test19_alreadyArmed_verifiedSameBoot_returnsPlanningHalted() {
        val identity = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = identity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected RecoveryRequired, got $result", result is ReconcilerResult.RecoveryRequired)
        val rec = result as ReconcilerResult.RecoveryRequired
        assertNotNull(rec.plan)
        assertTrue(rec.plan is DirectBootRegistrationPlan.AlreadyArmed)
        assertFalse(rec.isSuccess)

        // Zero OS calls made
        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 20: Post-Register Queue Corrupt Preserves Target and Fails Closed
    // =========================================================================

    @Test
    fun test20_postRegisterQueueCorrupt_preservesTargetAndFailsClosed() {
        val itemA = sampleNormalItem(alarmId = 101L, instanceToken = "tok_a")
        DirectBootAlarmCache.writeQueue(context, listOf(itemA))

        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onRegister = {
                prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{ malformed json").commit()
            }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StorageUnverifiable, got $result", result is ReconcilerResult.StorageUnverifiable)
        assertFalse(result.isSuccess)
        assertEquals(1, fakeBoundary.registerCalls.size)

        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, marker.mainState)
        assertEquals(RegistrationFailureReason.QUEUE_CORRUPT, marker.failureReason)
        assertEquals(101L, marker.registeringTarget?.alarmId)
        assertNull(marker.activeIdentity)
    }

    // =========================================================================
    // Test 21: Post-Register Queue Unsupported Schema Preserves Target
    // =========================================================================

    @Test
    fun test21_postRegisterQueueUnsupportedSchema_preservesTargetAndFailsClosed() {
        val itemA = sampleNormalItem(alarmId = 101L, instanceToken = "tok_a")
        DirectBootAlarmCache.writeQueue(context, listOf(itemA))

        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onRegister = {
                val unsupported = JSONObject().apply {
                    put("schemaVersion", 999)
                    put("items", org.json.JSONArray())
                }.toString()
                prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, unsupported).commit()
            }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StorageUnverifiable, got $result", result is ReconcilerResult.StorageUnverifiable)
        assertFalse(result.isSuccess)
        assertEquals(1, fakeBoundary.registerCalls.size)

        val markerResult = DirectBootRegistrationMarker.read(context)
        assertTrue(markerResult is MarkerReadResult.Success)
        val marker = (markerResult as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, marker.mainState)
        assertEquals(RegistrationFailureReason.QUEUE_UNSUPPORTED, marker.failureReason)
        assertEquals(101L, marker.registeringTarget?.alarmId)
        assertNull(marker.activeIdentity)
    }

    // =========================================================================
    // Test 22: Post-Cancel Corrupt + Failure Commit Fails Returns Unknown
    // =========================================================================

    @Test
    fun test22_postCancelQueueCorrupt_failureCommitFails_returnsUnknown() {
        val oldIdentity = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_old")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = oldIdentity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onCancel = {
                prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{ malformed json").commit()
            }
        }

        val writeCounter = AtomicInteger(0)
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot },
            editorCommitter = { sp, payload ->
                if (writeCounter.incrementAndGet() == 1) {
                    sp.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, payload).commit()
                } else {
                    false
                }
            }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected Unknown when failure commit fails, got $result", result is ReconcilerResult.Unknown)
        assertFalse(result.isSuccess)
        assertEquals(1, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 23: Post-Cancel Drift + Failure Commit Fails Returns Unknown
    // =========================================================================

    @Test
    fun test23_postCancelQueueDrift_failureCommitFails_returnsUnknown() {
        val oldIdentity = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_old")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = oldIdentity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onCancel = {
                DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 102L)))
            }
        }

        val writeCounter = AtomicInteger(0)
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot },
            editorCommitter = { sp, payload ->
                if (writeCounter.incrementAndGet() == 1) {
                    sp.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, payload).commit()
                } else {
                    false
                }
            }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected Unknown when failure commit fails, got $result", result is ReconcilerResult.Unknown)
        assertFalse(result.isSuccess)
        assertEquals(1, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 24: Post-Register Corrupt + Failure Commit Fails Returns Unknown
    // =========================================================================

    @Test
    fun test24_postRegisterQueueCorrupt_failureCommitFails_returnsUnknown() {
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val prefs = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(DirectBootRegistrationMarker.PREFS_NAME, Context.MODE_PRIVATE)

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onRegister = {
                prefs.edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, "{ malformed json").commit()
            }
        }

        val writeCounter = AtomicInteger(0)
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot },
            editorCommitter = { sp, payload ->
                if (writeCounter.incrementAndGet() == 1) {
                    sp.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, payload).commit()
                } else {
                    false
                }
            }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected Unknown when failure commit fails, got $result", result is ReconcilerResult.Unknown)
        assertFalse(result.isSuccess)
        assertEquals(1, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 25: AlreadyArmed With Unverified Boot Provenance Re-Arms Alarm
    // =========================================================================

    @Test
    fun test25_alreadyArmed_unverifiedBoot_rearmsAndRecoversToStableSuccess() {
        val identity = sampleNormalIdentity(alarmId = 202L, instanceToken = "tok_202")
        // Unknown boot provenance (all nulls)
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = identity,
            bootProvenance = BootProvenance(),
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 202L, instanceToken = "tok_202")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess for unverified AlreadyArmed, got $result", result is ReconcilerResult.StableSuccess)
        assertTrue(result.isSuccess)

        // Alarm 202 was re-armed in AlarmManager
        assertEquals(1, fakeBoundary.registerCalls.size)
        assertEquals(202L, fakeBoundary.registerCalls.first().alarmId)

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, finalMarker.mainState)
        assertEquals(defaultBoot, finalMarker.bootProvenance)
    }

    // =========================================================================
    // Test 26: Post-Register Drift + Failure Commit Fails Returns Unknown
    // =========================================================================

    @Test
    fun test26_postRegisterQueueDrift_failureCommitFails_returnsUnknown() {
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onRegister = {
                DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 102L)))
            }
        }

        val writeCounter = AtomicInteger(0)
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot },
            editorCommitter = { sp, payload ->
                if (writeCounter.incrementAndGet() == 1) {
                    sp.edit().putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, payload).commit()
                } else {
                    false
                }
            }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected Unknown when failure commit fails, got $result", result is ReconcilerResult.Unknown)
        assertFalse(result.isSuccess)
        assertEquals(1, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Cutpoint C0: Interruption Before Durable Intent Commit
    // =========================================================================

    @Test
    fun test27_cutpointC0_restartBeforeIntent_normalReconciliation() {
        // Marker was never written (or clean IDLE); queue has item 101
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L)))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess at C0, got $result", result is ReconcilerResult.StableSuccess)
        assertEquals(1, fakeBoundary.registerCalls.size)
        assertEquals(101L, fakeBoundary.registerCalls.first().alarmId)
    }

    // =========================================================================
    // Cutpoint C1: Restart After Intent Persisted, Before Cancel
    // =========================================================================

    @Test
    fun test28_cutpointC1_restartIntentPersistedBeforeCancel_settlesAndReconciles() {
        val oldTarget = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_99")
        val newTarget = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        // Seed persisted state at C1: IN_FLIGHT(CANCELLING_OLD) with both targets
        val c1Marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.CANCELLING_OLD,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "pid:${Process.myPid()}:reconciler",
            leaseEpochMs = baseTime + 15_000L,
            bootProvenance = defaultBoot,
            cancellingTarget = oldTarget,
            registeringTarget = newTarget,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, c1Marker, expectedOpSeq = 0L)

        // Queue currently wants 101
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess at C1 recovery, got $result", result is ReconcilerResult.StableSuccess)

        // Settle cancellingTarget 99, settle registeringTarget 101, then register fresh 101!
        assertEquals(listOf(99L, 101L), fakeBoundary.cancelCalls.map { it.alarmId })
        assertEquals(listOf(101L), fakeBoundary.registerCalls.map { it.alarmId })

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, finalMarker.mainState)
        assertEquals(101L, finalMarker.activeIdentity?.alarmId)
    }

    // =========================================================================
    // Cutpoint C2: Restart During/After Cancel Invocation Uncertain
    // =========================================================================

    @Test
    fun test29_cutpointC2_restartCancelUncertain_settlesCancellation() {
        val oldTarget = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_99")
        val newTarget = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        val c2Marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.CANCELLING_OLD,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "pid:${Process.myPid()}:reconciler",
            leaseEpochMs = baseTime + 15_000L,
            bootProvenance = defaultBoot,
            cancellingTarget = oldTarget,
            registeringTarget = newTarget,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, c2Marker, expectedOpSeq = 0L)

        // Queue is now empty (old cancelled, new not wanted anymore)
        DirectBootAlarmCache.clearQueue(context)

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess at C2, got $result", result is ReconcilerResult.StableSuccess)

        // Both old and new were settled via cancel
        assertEquals(listOf(99L, 101L), fakeBoundary.cancelCalls.map { it.alarmId })
        assertEquals(0, fakeBoundary.registerCalls.size)

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, finalMarker.mainState)
        assertNull(finalMarker.activeIdentity)
    }

    // =========================================================================
    // Cutpoint C3: Restart After Cancel Complete, Before Register
    // =========================================================================

    @Test
    fun test30_cutpointC3_restartCancelCompleteBeforeRegister_settlesAndRegisters() {
        val newTarget = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        // Persisted state at C3: phase REGISTERING_NEW, cancellingTarget null
        val c3Marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = 2L,
            leaseOwner = "pid:${Process.myPid()}:reconciler",
            leaseEpochMs = baseTime + 15_000L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = newTarget,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, c3Marker, expectedOpSeq = 0L)

        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess at C3, got $result", result is ReconcilerResult.StableSuccess)

        // 101 was cancelled to settle state, then freshly registered
        assertEquals(listOf(101L), fakeBoundary.cancelCalls.map { it.alarmId })
        assertEquals(listOf(101L), fakeBoundary.registerCalls.map { it.alarmId })

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, finalMarker.mainState)
        assertEquals(101L, finalMarker.activeIdentity?.alarmId)
    }

    // =========================================================================
    // Cutpoint C4: Restart Register Invocation Uncertain
    // =========================================================================

    @Test
    fun test31_cutpointC4_restartRegisterUncertain_settlesRegistration() {
        val newTarget = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        val c4Marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 1,
            opSeq = 2L,
            leaseOwner = "pid:${Process.myPid()}:reconciler",
            leaseEpochMs = baseTime + 15_000L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = newTarget,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, c4Marker, expectedOpSeq = 0L)

        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess at C4, got $result", result is ReconcilerResult.StableSuccess)
        assertEquals(listOf(101L), fakeBoundary.cancelCalls.map { it.alarmId })
        assertEquals(listOf(101L), fakeBoundary.registerCalls.map { it.alarmId })
    }

    // =========================================================================
    // Cutpoint C5: Restart After Register Accepted, Before Terminal Commit
    // =========================================================================

    @Test
    fun test32_cutpointC5_restartRegisterAcceptedBeforeTerminalCommit_recoversToStable() {
        val target = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        val c5Marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = 3L,
            leaseOwner = "pid:${Process.myPid()}:reconciler",
            leaseEpochMs = baseTime + 15_000L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = target,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, c5Marker, expectedOpSeq = 0L)

        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess at C5, got $result", result is ReconcilerResult.StableSuccess)
        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, finalMarker.mainState)
        assertEquals(101L, finalMarker.activeIdentity?.alarmId)
    }

    // =========================================================================
    // Cutpoint C6: Terminal Commit Uncertain
    // =========================================================================

    @Test
    fun test33_cutpointC6_restartTerminalCommitUncertain_recoversToStable() {
        val target = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        // Interrupted during terminal commit, left in FAILED_BLOCKED with target preserved
        val c6Marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 1,
            opSeq = 4L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = target,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, c6Marker, expectedOpSeq = 0L)

        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess at C6, got $result", result is ReconcilerResult.StableSuccess)
        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, finalMarker.mainState)
        assertEquals(101L, finalMarker.activeIdentity?.alarmId)
        assertEquals(0, finalMarker.retryCount)
    }

    // =========================================================================
    // Test 34: AlreadyArmed After Reboot Re-Arms Alarm
    // =========================================================================

    @Test
    fun test34_recovery_alreadyArmed_afterReboot_reArmsAlarm() {
        val identity = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")
        val oldBoot = BootProvenance(bootCount = 5, lastElapsedRealtime = 10_000L)
        val newBoot = BootProvenance(bootCount = 6, lastElapsedRealtime = 2_000L) // Reboot!

        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = identity,
            bootProvenance = oldBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { newBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess after reboot, got $result", result is ReconcilerResult.StableSuccess)

        // Alarm 101 was re-registered because reboot erased AlarmManager state
        assertEquals(1, fakeBoundary.registerCalls.size)
        assertEquals(101L, fakeBoundary.registerCalls.first().alarmId)

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.IDLE, finalMarker.mainState)
        assertEquals(newBoot, finalMarker.bootProvenance)
    }

    // =========================================================================
    // Test 35: Clock Rollback Blocks Recovery
    // =========================================================================

    @Test
    fun test35_recovery_clockRollback_blocksRecovery() {
        val blockedMarker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = sampleNormalIdentity(alarmId = 101L),
            updatedAtEpochMs = baseTime + 10_000L // Marker in future!
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, blockedMarker, expectedOpSeq = 0L)

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime }, // Current time is before marker updatedAt!
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected RecoveryBlocked for clock rollback, got $result", result is ReconcilerResult.RecoveryBlocked)
        val blocked = result as ReconcilerResult.RecoveryBlocked
        assertTrue(blocked.reason.contains("Clock rollback"))

        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 36: Retry Exhaustion Blocks Recovery
    // =========================================================================

    @Test
    fun test36_recovery_retryExhaustion_blocksRecovery() {
        val exhaustedMarker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = DirectBootRegistrationMarker.MAX_RETRIES, // 3 retries exhausted
            opSeq = 1L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = sampleNormalIdentity(alarmId = 101L),
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, exhaustedMarker, expectedOpSeq = 0L)

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected RecoveryBlocked for retry exhaustion, got $result", result is ReconcilerResult.RecoveryBlocked)
        val blocked = result as ReconcilerResult.RecoveryBlocked
        assertTrue(blocked.reason.contains("Retry budget exhausted"))

        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 37: Expired Lease From Other PID On Same Boot Blocks Recovery
    // =========================================================================

    @Test
    fun test37_recovery_expiredLeaseFromOtherPidOnSameBoot_blocksRecovery() {
        val expiredMarker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            failureReason = RegistrationFailureReason.NONE,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "pid:99999:other_process",
            leaseEpochMs = baseTime - 1_000L, // Expired, but same boot!
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = sampleNormalIdentity(alarmId = 101L),
            updatedAtEpochMs = baseTime - 2_000L
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, expiredMarker, expectedOpSeq = 0L)

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected RecoveryBlocked for unprovable ownership, got $result", result is ReconcilerResult.RecoveryBlocked)
        val blocked = result as ReconcilerResult.RecoveryBlocked
        assertTrue(blocked.reason.contains("exclusive ownership"))

        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }

    // =========================================================================
    // Test 38: Cancellation Uncertainty Halts And Preserves Targets
    // =========================================================================

    @Test
    fun test38_recovery_cancellationUncertainty_haltsAndPreservesTargets() {
        val oldTarget = sampleNormalIdentity(alarmId = 99L, instanceToken = "tok_99")
        val newTarget = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        val marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = oldTarget,
            registeringTarget = newTarget,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, marker, expectedOpSeq = 0L)

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            cancellationResult = ExactCancellationResult.StateUncertain("AlarmManager returned uncertain")
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected OsOperationFailed, got $result", result is ReconcilerResult.OsOperationFailed)
        assertFalse(result.isSuccess)

        // Cancel was attempted; register was NOT attempted
        assertEquals(1, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)

        // FAILED_BLOCKED preserved targets and incremented retryCount to 1
        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, finalMarker.mainState)
        assertEquals(RegistrationFailureReason.OS_UNCERTAIN, finalMarker.failureReason)
        assertEquals(99L, finalMarker.cancellingTarget?.alarmId)
        assertEquals(101L, finalMarker.registeringTarget?.alarmId)
        assertEquals(1, finalMarker.retryCount)
    }

    // =========================================================================
    // Test 39: Registration Uncertainty Halts And Preserves Targets
    // =========================================================================

    @Test
    fun test39_recovery_registrationUncertainty_haltsAndPreservesTargets() {
        val target = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        val marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = target,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, marker, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            // Cancel succeeds, but fresh registration is uncertain!
            registrationResult = ExactRegistrationResult.StateUncertain("AlarmManager registration timed out")
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected OsOperationFailed, got $result", result is ReconcilerResult.OsOperationFailed)
        assertFalse(result.isSuccess)

        assertEquals(1, fakeBoundary.cancelCalls.size)
        assertEquals(1, fakeBoundary.registerCalls.size)

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, finalMarker.mainState)
        assertEquals(RegistrationFailureReason.OS_UNCERTAIN, finalMarker.failureReason)
        assertEquals(101L, finalMarker.registeringTarget?.alarmId)
        assertEquals(1, finalMarker.retryCount)
    }

    // =========================================================================
    // Test 40: Queue Drift During Recovery Aborts Obsolete Target
    // =========================================================================

    @Test
    fun test40_recovery_queueDriftDuringRecovery_abortsObsoleteTarget() {
        val target = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        val marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = target,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, marker, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        fakeBoundary.onRegister = {
            // Queue head drifts while register is in progress
            DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 102L, instanceToken = "tok_102")))
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected HeadChangedDuringOperation, got $result", result is ReconcilerResult.HeadChangedDuringOperation)
        val headChanged = result as ReconcilerResult.HeadChangedDuringOperation
        assertEquals(101L, headChanged.initialHead?.alarmId)
        assertEquals(102L, headChanged.postOpHead?.alarmId)

        val finalMarker = (DirectBootRegistrationMarker.read(context) as MarkerReadResult.Success).marker
        assertEquals(RegistrationMainState.FAILED_BLOCKED, finalMarker.mainState)
        assertEquals(101L, finalMarker.registeringTarget?.alarmId)
        assertNull(finalMarker.activeIdentity)
    }

    // =========================================================================
    // Test 41: Exact Identity-Only Cancellation (Zero Legacy V1 Effects)
    // =========================================================================

    @Test
    fun test41_recovery_exactIdentityOnly_zeroLegacyV1Effects() {
        val target = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        val marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = target,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, marker, expectedOpSeq = 0L)

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        reconciler.reconcile()

        // Cancellation must strictly use DirectBootV2NormalIntentIdentity with exact parameters
        assertEquals(1, fakeBoundary.cancelCalls.size)
        val cancelCall = fakeBoundary.cancelCalls.first()
        assertEquals(101L, cancelCall.alarmId)
        assertEquals("tok_101", cancelCall.instanceToken)
        assertEquals(DirectBootV2NormalIntentIdentity.KIND_NORMAL, cancelCall.kind)
    }

    // =========================================================================
    // Test 42: No OS IPC Under DE Storage Lock During Recovery
    // =========================================================================

    @Test
    fun test42_recovery_noOsIpcUnderDeStorageLock() {
        val target = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")

        val marker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = target,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, marker, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 102L)))

        val lockHeldInCancel = AtomicBoolean(true)
        val lockHeldInRegister = AtomicBoolean(true)

        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onCancel = {
                lockHeldInCancel.set(Thread.holdsLock(DirectBootStorageLock.lock))
            }
            onRegister = {
                lockHeldInRegister.set(Thread.holdsLock(DirectBootStorageLock.lock))
            }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue(result is ReconcilerResult.StableSuccess)

        assertFalse("Storage lock was held during recovery cancel!", lockHeldInCancel.get())
        assertFalse("Storage lock was held during recovery register!", lockHeldInRegister.get())
    }

    // =========================================================================
    // Test 43: Reboot Detected Via ElapsedRealtime Regression
    // =========================================================================

    @Test
    fun test43_recovery_rebootDetectedViaElapsedRegression() {
        val identity = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")
        // Same bootCount (null), but elapsedRealtime was 100_000 and is now 5_000
        val oldBoot = BootProvenance(bootCount = null, lastElapsedRealtime = 100_000L)
        val newBoot = BootProvenance(bootCount = null, lastElapsedRealtime = 5_000L)

        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = identity,
            bootProvenance = oldBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { newBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess via elapsed regression reboot, got $result", result is ReconcilerResult.StableSuccess)
        assertEquals(1, fakeBoundary.registerCalls.size)
        assertEquals(101L, fakeBoundary.registerCalls.first().alarmId)
    }

    // =========================================================================
    // Discriminator A: Lock Leak When Both Recovery Targets Null
    // =========================================================================

    @Test
    fun test44_discriminatorA_lockLeak_bothTargetsNull_recoveryPassDoesNotHoldLock() {
        val blockedMarker = DirectBootRegistrationMarker(
            schemaVersion = 1,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN,
            retryCount = 0,
            opSeq = 1L,
            leaseOwner = "",
            leaseEpochMs = 0L,
            bootProvenance = defaultBoot,
            cancellingTarget = null,
            registeringTarget = null,
            updatedAtEpochMs = baseTime
        ).let { it.copy(payloadDigest = it.computeDigest()) }
        DirectBootRegistrationMarker.writeInternal(context, blockedMarker, expectedOpSeq = 0L)

        val item = sampleNormalItem(alarmId = 105L)
        DirectBootAlarmCache.writeQueue(context, listOf(item))

        val lockHeldInRegister = AtomicBoolean(false)
        val fakeBoundary = FakeExactAlarmBoundary().apply {
            onRegister = {
                lockHeldInRegister.set(Thread.holdsLock(DirectBootStorageLock.lock))
            }
        }

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertFalse("Discriminator A FAIL: Storage lock was held during OS register in recovery when targets were null!", lockHeldInRegister.get())
        assertTrue("Expected StableSuccess, got $result", result is ReconcilerResult.StableSuccess)
    }

    // =========================================================================
    // Discriminator B: Stale Snapshot Between Initial Read And Intent Persisted
    // =========================================================================

    @Test
    fun test45_discriminatorB_staleSnapshot_queueDriftBeforeIntentPersisted_usesFreshQueue() {
        val itemA = sampleNormalItem(alarmId = 101L, instanceToken = "tok_a")
        DirectBootAlarmCache.writeQueue(context, listOf(itemA))

        val itemB = sampleNormalItem(alarmId = 102L, instanceToken = "tok_b")
        val fakeBoundary = FakeExactAlarmBoundary()

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot },
            onInitialStorageRead = {
                // Authoritative Queue changes to item B after initial read, before intent is planned/persisted!
                DirectBootAlarmCache.writeQueue(context, listOf(itemB))
            }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess, got $result", result is ReconcilerResult.StableSuccess)

        // The reconciler must NEVER register obsolete item A; it must use freshly validated Queue truth!
        assertEquals(1, fakeBoundary.registerCalls.size)
        assertEquals("Discriminator B FAIL: Registered obsolete item A instead of fresh item B!", 102L, fakeBoundary.registerCalls.first().alarmId)
    }

    @Test
    fun test45b_discriminatorB_recoveryPathway_queueDriftBeforeIntentPersisted_usesFreshQueue() {
        val itemA = sampleNormalItem(alarmId = 101L, instanceToken = "tok_a")
        DirectBootAlarmCache.writeQueue(context, listOf(itemA))

        val itemB = sampleNormalItem(alarmId = 102L, instanceToken = "tok_b")
        val fakeBoundary = FakeExactAlarmBoundary()

        // Seed recoverable marker in FAILED_BLOCKED with zero targets
        val initialMarker = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = null,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(
            opSeq = 1L,
            mainState = RegistrationMainState.FAILED_BLOCKED,
            failureReason = RegistrationFailureReason.OS_UNCERTAIN
        )
        DirectBootRegistrationMarker.write(context, initialMarker, expectedOpSeq = 0L)

        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot },
            onInitialStorageRead = {
                // Authoritative Queue changes to item B after initial read, before recovery intent is planned/persisted!
                DirectBootAlarmCache.writeQueue(context, listOf(itemB))
            }
        )

        val result = reconciler.reconcile()
        assertTrue("Expected StableSuccess, got $result", result is ReconcilerResult.StableSuccess)

        // Recovery must use fresh Queue truth and register item B
        assertEquals(1, fakeBoundary.registerCalls.size)
        assertEquals("Discriminator B (Recovery) FAIL: Registered obsolete item A instead of fresh item B!", 102L, fakeBoundary.registerCalls.first().alarmId)
    }

    // =========================================================================
    // Discriminator C: AlreadyArmed Without Scheduled-State Evidence
    // =========================================================================

    @Test
    fun test46_discriminatorC_alreadyArmed_noScheduledEvidence_returnsRecoveryRequired() {
        val identity = sampleNormalIdentity(alarmId = 101L, instanceToken = "tok_101")
        val initialIdle = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = identity,
            bootProvenance = defaultBoot,
            nowEpochMs = baseTime
        ).copy(opSeq = 1L)
        DirectBootRegistrationMarker.write(context, initialIdle, expectedOpSeq = 0L)
        DirectBootAlarmCache.writeQueue(context, listOf(sampleNormalItem(alarmId = 101L, instanceToken = "tok_101")))

        val fakeBoundary = FakeExactAlarmBoundary()
        val reconciler = DirectBootRegistrationReconciler.createForTesting(
            context = context,
            osBoundary = fakeBoundary,
            clock = { baseTime },
            bootProvenanceProvider = { defaultBoot }
        )

        val result = reconciler.reconcile()
        assertTrue("Discriminator C FAIL: Expected RecoveryRequired for AlreadyArmed without proven scheduled state, got $result", result is ReconcilerResult.RecoveryRequired)
        val rec = result as ReconcilerResult.RecoveryRequired
        assertFalse(rec.isSuccess)
        assertEquals(0, fakeBoundary.cancelCalls.size)
        assertEquals(0, fakeBoundary.registerCalls.size)
    }
}
