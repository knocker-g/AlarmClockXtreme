package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DirectBootRegistrationPlannerTest {

    private lateinit var context: Context

    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        DirectBootAlarmCache.clear(context)
        DirectBootAlarmCache.clearQueue(context)
    }

    private fun createNormalItem(
        alarmId: Long = 101L,
        triggerTime: Long = baseTime + 60_000L,
        generation: Int = 1,
        instanceToken: String = "token_norm_101",
        updatedAt: Long = baseTime,
        restoreEpoch: Long = 1L
    ): DirectBootQueueItem {
        return DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            instanceToken = instanceToken,
            updatedAt = updatedAt,
            restoreEpoch = restoreEpoch
        )
    }

    private fun createQuickItem(
        alarmId: Long = 202L,
        triggerTime: Long = baseTime + 30_000L,
        updatedAt: Long = baseTime
    ): DirectBootQueueItem {
        return DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_QUICK,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 0,
            instanceToken = "",
            updatedAt = updatedAt
        )
    }

    // =========================================================================
    // 1. Absent & Valid Empty Queue
    // =========================================================================

    @Test
    fun test01_absentQueue_withoutArmed_plansNoOp() {
        val plan = DirectBootRegistrationPlanner.plan(
            queueState = QueueStorageState.Absent,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )
        assertTrue(plan is DirectBootRegistrationPlan.NoOp)
    }

    @Test
    fun test02_absentQueue_withArmed_plansCancelCurrent() {
        val armed = DirectBootV2NormalIntentIdentity(
            alarmId = 1L,
            generation = 1,
            triggerTime = baseTime + 10_000L,
            instanceToken = "tok_old"
        )
        val plan = DirectBootRegistrationPlanner.plan(
            queueState = QueueStorageState.Absent,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = armed
        )
        assertTrue(plan is DirectBootRegistrationPlan.CancelCurrent)
        val cancelPlan = plan as DirectBootRegistrationPlan.CancelCurrent
        assertEquals(armed, cancelPlan.oldIdentityToCancel)
        assertEquals(DirectBootCancelReason.QUEUE_ABSENT, cancelPlan.reason)
    }

    @Test
    fun test03_validEmptyQueue_withoutArmed_plansNoOp() {
        val plan = DirectBootRegistrationPlanner.plan(
            queueState = QueueStorageState.ValidEmpty,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )
        assertTrue(plan is DirectBootRegistrationPlan.NoOp)
    }

    @Test
    fun test04_validEmptyQueue_withArmed_plansCancelCurrent() {
        val armed = DirectBootV2NormalIntentIdentity(
            alarmId = 2L,
            generation = 1,
            triggerTime = baseTime + 20_000L,
            instanceToken = "tok_armed"
        )
        val plan = DirectBootRegistrationPlanner.plan(
            queueState = QueueStorageState.ValidEmpty,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = armed
        )
        assertTrue(plan is DirectBootRegistrationPlan.CancelCurrent)
        val cancelPlan = plan as DirectBootRegistrationPlan.CancelCurrent
        assertEquals(armed, cancelPlan.oldIdentityToCancel)
        assertEquals(DirectBootCancelReason.QUEUE_EMPTY, cancelPlan.reason)
    }

    // =========================================================================
    // 2. Normal Earliest Head
    // =========================================================================

    @Test
    fun test05_normalEarliestHead_unarmed_plansArmNormal() {
        val item = createNormalItem(alarmId = 10L, triggerTime = baseTime + 50_000L)
        val state = QueueStorageState.ValidWithItems(listOf(item))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )

        assertTrue(plan is DirectBootRegistrationPlan.ArmNormal)
        val armPlan = plan as DirectBootRegistrationPlan.ArmNormal
        assertEquals(10L, armPlan.newIdentity.alarmId)
        assertEquals(1, armPlan.newIdentity.generation)
        assertEquals(item.triggerTime, armPlan.newIdentity.triggerTime)
        assertEquals(item.instanceToken, armPlan.newIdentity.instanceToken)
        assertNull(armPlan.oldIdentityToCancel)
        assertEquals(item, armPlan.queueHead)
    }

    @Test
    fun test06_normalEarliestHead_withDifferentArmed_plansArmNormalAndCancelOld() {
        val item = createNormalItem(alarmId = 20L, triggerTime = baseTime + 60_000L)
        val oldArmed = DirectBootV2NormalIntentIdentity(
            alarmId = 15L,
            generation = 1,
            triggerTime = baseTime + 30_000L,
            instanceToken = "tok_old_15"
        )
        val state = QueueStorageState.ValidWithItems(listOf(item))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmed
        )

        assertTrue(plan is DirectBootRegistrationPlan.ArmNormal)
        val armPlan = plan as DirectBootRegistrationPlan.ArmNormal
        assertEquals(20L, armPlan.newIdentity.alarmId)
        assertEquals(oldArmed, armPlan.oldIdentityToCancel)
    }

    @Test
    fun test07_distinctNormalGenerations_plansArmNewGenerationAndCancelOld() {
        val itemGen2 = createNormalItem(alarmId = 30L, generation = 2, instanceToken = "tok_gen2")
        val oldArmedGen1 = DirectBootV2NormalIntentIdentity(
            alarmId = 30L,
            generation = 1,
            triggerTime = itemGen2.triggerTime,
            instanceToken = "tok_gen1"
        )
        val state = QueueStorageState.ValidWithItems(listOf(itemGen2))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmedGen1
        )

        assertTrue(plan is DirectBootRegistrationPlan.ArmNormal)
        val armPlan = plan as DirectBootRegistrationPlan.ArmNormal
        assertEquals(2, armPlan.newIdentity.generation)
        assertEquals(oldArmedGen1, armPlan.oldIdentityToCancel)
    }

    // =========================================================================
    // 3. Quick Head Explicit Blocking
    // =========================================================================

    @Test
    fun test08_quickEarliestHead_blocksScheduling_doesNotSkipToLaterNormal() {
        val quickHead = createQuickItem(alarmId = 50L, triggerTime = baseTime + 10_000L)
        val laterNormal = createNormalItem(alarmId = 51L, triggerTime = baseTime + 20_000L)

        // Queue contains Quick head followed by Normal item
        val state = QueueStorageState.ValidWithItems(listOf(quickHead, laterNormal))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )

        // Must explicitly block, NEVER skip to laterNormal
        assertTrue(plan is DirectBootRegistrationPlan.BlockedByQuickHead)
        val blocked = plan as DirectBootRegistrationPlan.BlockedByQuickHead
        assertEquals(quickHead, blocked.quickHead)
        assertNull(blocked.oldIdentityToCancel)
    }

    @Test
    fun test09_quickEarliestHead_withArmedNormal_plansBlockAndCancelPriorNormal() {
        val quickHead = createQuickItem(alarmId = 60L, triggerTime = baseTime + 10_000L)
        val oldArmed = DirectBootV2NormalIntentIdentity(
            alarmId = 61L,
            generation = 1,
            triggerTime = baseTime + 30_000L,
            instanceToken = "tok_61"
        )
        val state = QueueStorageState.ValidWithItems(listOf(quickHead))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmed
        )

        assertTrue(plan is DirectBootRegistrationPlan.BlockedByQuickHead)
        val blocked = plan as DirectBootRegistrationPlan.BlockedByQuickHead
        assertEquals(quickHead, blocked.quickHead)
        assertEquals(oldArmed, blocked.oldIdentityToCancel)
    }

    // =========================================================================
    // 4. Same Registered Identity (Already Armed)
    // =========================================================================

    @Test
    fun test10_sameRegisteredIdentity_plansAlreadyArmed_noOp() {
        val item = createNormalItem(alarmId = 70L, triggerTime = baseTime + 40_000L, generation = 1)
        val matchingArmed = DirectBootV2NormalIntentIdentity.fromQueueItem(item)
        val state = QueueStorageState.ValidWithItems(listOf(item))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = matchingArmed
        )

        assertTrue(plan is DirectBootRegistrationPlan.AlreadyArmed)
        val alreadyArmed = plan as DirectBootRegistrationPlan.AlreadyArmed
        assertEquals(matchingArmed, alreadyArmed.currentIdentity)
        assertEquals(item, alreadyArmed.queueHead)
    }

    // =========================================================================
    // 5. Overdue Head Policy (Canonical Blocked Result)
    // =========================================================================

    @Test
    fun test11_overdueTrigger_blocksOverdueNeedsCanonicalPolicy_generatesZeroNewRegistrations() {
        // Trigger is 5 minutes in the past
        val pastTrigger = baseTime - 300_000L
        val overdueHead = createNormalItem(alarmId = 80L, triggerTime = pastTrigger)
        val state = QueueStorageState.ValidWithItems(listOf(overdueHead))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )

        // Must return BlockedOverdueNeedsCanonicalPolicy, NEVER ArmNormal, and NEVER StorageCorrupt
        assertTrue(plan is DirectBootRegistrationPlan.BlockedOverdueNeedsCanonicalPolicy)
        val blocked = plan as DirectBootRegistrationPlan.BlockedOverdueNeedsCanonicalPolicy
        assertEquals(overdueHead, blocked.overdueHead)
        assertNull(blocked.oldIdentityToCancel)

        // Coordinator execution must result in ZERO calls to registerExactAlarm
        val fakeOs = FakeDirectBootExactAlarmManager()
        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null,
            osBoundary = fakeOs,
            reReadQueueState = { state }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.PlanningHalted)
        assertEquals(0, fakeOs.registered.size)
        assertEquals(0, fakeOs.cancelled.size)
    }

    @Test
    fun test12_overdueTrigger_withPriorArmed_cancelsOldAndBlocksNewArm() {
        val pastTrigger = baseTime - 60_000L
        val overdueHead = createNormalItem(alarmId = 85L, triggerTime = pastTrigger)
        val oldArmed = DirectBootV2NormalIntentIdentity(
            alarmId = 84L,
            generation = 1,
            triggerTime = baseTime + 10_000L,
            instanceToken = "tok_84"
        )
        val state = QueueStorageState.ValidWithItems(listOf(overdueHead))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmed
        )

        assertTrue(plan is DirectBootRegistrationPlan.BlockedOverdueNeedsCanonicalPolicy)
        val blocked = plan as DirectBootRegistrationPlan.BlockedOverdueNeedsCanonicalPolicy
        assertEquals(oldArmed, blocked.oldIdentityToCancel)

        // Coordinator cancels old identity, but generates ZERO new registrations
        val fakeOs = FakeDirectBootExactAlarmManager()
        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmed,
            osBoundary = fakeOs,
            reReadQueueState = { state }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.StableSuccess)
        assertEquals(1, fakeOs.cancelled.size)
        assertEquals(84L, fakeOs.cancelled[0].alarmId)
        assertEquals(0, fakeOs.registered.size) // Zero new registrations!
    }

    // =========================================================================
    // 6. Fail-Closed Storage Corruption & Unsupported Schema
    // =========================================================================

    @Test
    fun test13_corruptQueueStorage_failClosed_preservesRaw() {
        val corruptState = QueueStorageState.Corrupt("Truncated JSON array")
        val plan = DirectBootRegistrationPlanner.plan(
            queueState = corruptState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )

        assertTrue(plan is DirectBootRegistrationPlan.StorageCorrupt)
        val corruptPlan = plan as DirectBootRegistrationPlan.StorageCorrupt
        assertEquals("Truncated JSON array", corruptPlan.reason)
    }

    @Test
    fun test14_unsupportedSchema_failClosed_preservesRaw() {
        val unsupportedState = QueueStorageState.UnsupportedSchema(
            foundVersion = 99,
            expectedVersion = 2
        )
        val plan = DirectBootRegistrationPlanner.plan(
            queueState = unsupportedState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )

        assertTrue(plan is DirectBootRegistrationPlan.UnsupportedSchema)
        val schemaPlan = plan as DirectBootRegistrationPlan.UnsupportedSchema
        assertEquals(99, schemaPlan.foundVersion)
        assertEquals(2, schemaPlan.expectedVersion)
    }

    // =========================================================================
    // 7. Invalid Normal Head Identity
    // =========================================================================

    @Test
    fun test15_invalidNormalHead_missingInstanceToken_plansInvalidNormalHead() {
        val invalidItem = createNormalItem(alarmId = 90L, instanceToken = "")
        val state = QueueStorageState.ValidWithItems(listOf(invalidItem))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )

        assertTrue(plan is DirectBootRegistrationPlan.InvalidNormalHead)
        val invalidPlan = plan as DirectBootRegistrationPlan.InvalidNormalHead
        assertEquals(QueueCandidateStatus.INELIGIBLE_MISSING_INSTANCE_TOKEN, invalidPlan.status)
        assertEquals(invalidItem, invalidPlan.invalidHead)
    }

    @Test
    fun test16_invalidNormalHead_invalidGeneration_plansInvalidNormalHead() {
        val invalidItem = createNormalItem(alarmId = 91L, generation = 0)
        val state = QueueStorageState.ValidWithItems(listOf(invalidItem))

        val plan = DirectBootRegistrationPlanner.plan(
            queueState = state,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null
        )

        assertTrue(plan is DirectBootRegistrationPlan.InvalidNormalHead)
        val invalidPlan = plan as DirectBootRegistrationPlan.InvalidNormalHead
        assertEquals(QueueCandidateStatus.INELIGIBLE_INVALID_GENERATION, invalidPlan.status)
    }

    // =========================================================================
    // 8. Coordinator: Cancellation-Before-Registration Safety
    // =========================================================================

    @Test
    fun test17_coordinator_stableSuccess_armsNewCandidate() {
        val item = createNormalItem(alarmId = 200L, triggerTime = baseTime + 100_000L)
        val initialState = QueueStorageState.ValidWithItems(listOf(item))
        val fakeOs = FakeDirectBootExactAlarmManager()

        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = initialState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null,
            osBoundary = fakeOs,
            reReadQueueState = { initialState }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.StableSuccess)
        val success = outcome as RegistrationCoordinatorResult.StableSuccess
        assertTrue(success.plan is DirectBootRegistrationPlan.ArmNormal)
        assertEquals(1, fakeOs.registered.size)
        assertEquals(200L, fakeOs.registered[0].alarmId)
        assertEquals(0, fakeOs.cancelled.size)
    }

    @Test
    fun test18_coordinator_stableSuccess_replacesOldWithNew_cancelsFirst() {
        val newItem = createNormalItem(alarmId = 201L, triggerTime = baseTime + 120_000L)
        val oldArmed = DirectBootV2NormalIntentIdentity(
            alarmId = 199L,
            generation = 1,
            triggerTime = baseTime + 80_000L,
            instanceToken = "tok_old_199"
        )
        val initialState = QueueStorageState.ValidWithItems(listOf(newItem))
        val fakeOs = FakeDirectBootExactAlarmManager()

        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = initialState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmed,
            osBoundary = fakeOs,
            reReadQueueState = { initialState }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.StableSuccess)
        // Verify cancellation happened before registration
        assertEquals(listOf(199L), fakeOs.cancelled.map { it.alarmId })
        assertEquals(listOf(201L), fakeOs.registered.map { it.alarmId })
        assertEquals(listOf("CANCEL:199", "REGISTER:201"), fakeOs.eventLog)
    }

    @Test
    fun test19_coordinator_cancelFails_doesNotRegisterNewAlarm() {
        val newItem = createNormalItem(alarmId = 205L, triggerTime = baseTime + 150_000L)
        val oldArmed = DirectBootV2NormalIntentIdentity(
            alarmId = 204L,
            generation = 1,
            triggerTime = baseTime + 80_000L,
            instanceToken = "tok_204"
        )
        val initialState = QueueStorageState.ValidWithItems(listOf(newItem))
        val fakeOs = FakeDirectBootExactAlarmManager().apply {
            simulatedCancelException = RuntimeException("Cancellation IPC failed")
        }

        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = initialState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmed,
            osBoundary = fakeOs,
            reReadQueueState = { initialState }
        )

        // Must classify as OsOperationFailed
        assertTrue(outcome is RegistrationCoordinatorResult.OsOperationFailed)
        val failed = outcome as RegistrationCoordinatorResult.OsOperationFailed
        assertNotNull(failed.cancellationResult)
        assertFalse(failed.cancellationResult!!.isSuccessfulOrAbsent)
        // CRITICAL: Registration MUST NOT have been attempted!
        assertNull(failed.registrationResult)
        assertEquals(0, fakeOs.registered.size)
        assertEquals(listOf("CANCEL:204"), fakeOs.eventLog)
    }

    @Test
    fun test20_coordinator_cancelUncertain_doesNotRegisterNewAlarm() {
        val newItem = createNormalItem(alarmId = 207L, triggerTime = baseTime + 160_000L)
        val oldArmed = DirectBootV2NormalIntentIdentity(
            alarmId = 206L,
            generation = 1,
            triggerTime = baseTime + 90_000L,
            instanceToken = "tok_206"
        )
        val initialState = QueueStorageState.ValidWithItems(listOf(newItem))
        val fakeOs = FakeDirectBootExactAlarmManager().apply {
            simulateUncertainCancel = true
        }

        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = initialState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmed,
            osBoundary = fakeOs,
            reReadQueueState = { initialState }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.OsStateUncertain)
        val uncertain = outcome as RegistrationCoordinatorResult.OsStateUncertain
        assertNotNull(uncertain.cancellationResult)
        assertTrue(uncertain.cancellationResult is ExactCancellationResult.StateUncertain)
        // CRITICAL: Registration MUST NOT have been attempted!
        assertNull(uncertain.registrationResult)
        assertEquals(0, fakeOs.registered.size)
        assertEquals(listOf("CANCEL:206"), fakeOs.eventLog)
    }

    @Test
    fun test21_coordinator_oldCancelSuccess_newRegistrationFailure_classifiesFailed() {
        val newItem = createNormalItem(alarmId = 401L)
        val oldArmed = DirectBootV2NormalIntentIdentity(
            alarmId = 400L,
            generation = 1,
            triggerTime = baseTime + 50_000L,
            instanceToken = "tok_400"
        )
        val initialState = QueueStorageState.ValidWithItems(listOf(newItem))
        val fakeOs = FakeDirectBootExactAlarmManager().apply {
            simulatedRegisterException = SecurityException("Permission denial for exact alarm")
        }

        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = initialState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = oldArmed,
            osBoundary = fakeOs,
            reReadQueueState = { initialState }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.OsOperationFailed)
        val failed = outcome as RegistrationCoordinatorResult.OsOperationFailed
        assertNotNull(failed.cancellationResult)
        assertTrue(failed.cancellationResult!!.isSuccessfulOrAbsent)
        assertNotNull(failed.registrationResult)
        assertTrue(failed.registrationResult is ExactRegistrationResult.SecurityError)
    }

    // =========================================================================
    // 9. Post-Operation Storage Verification
    // =========================================================================

    @Test
    fun test22_coordinator_postOpCorruptStorage_returnsPostOpStorageUnverifiable() {
        val item = createNormalItem(alarmId = 501L)
        val initialState = QueueStorageState.ValidWithItems(listOf(item))
        val corruptState = QueueStorageState.Corrupt("Corrupted storage during OS call")
        val fakeOs = FakeDirectBootExactAlarmManager()

        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = initialState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null,
            osBoundary = fakeOs,
            reReadQueueState = { corruptState }
        )

        // Must strictly return PostOpStorageUnverifiable.StorageCorrupt, NEVER StableSuccess
        assertTrue(outcome is RegistrationCoordinatorResult.PostOpStorageUnverifiable.StorageCorrupt)
        val unverifiable = outcome as RegistrationCoordinatorResult.PostOpStorageUnverifiable.StorageCorrupt
        assertEquals("Corrupted storage during OS call", unverifiable.reason)
        assertEquals(corruptState, unverifiable.finalQueueState)
    }

    @Test
    fun test23_coordinator_postOpUnsupportedSchema_returnsPostOpStorageUnverifiable() {
        val item = createNormalItem(alarmId = 502L)
        val initialState = QueueStorageState.ValidWithItems(listOf(item))
        val unsupportedState = QueueStorageState.UnsupportedSchema(foundVersion = 42, expectedVersion = 2)
        val fakeOs = FakeDirectBootExactAlarmManager()

        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = initialState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null,
            osBoundary = fakeOs,
            reReadQueueState = { unsupportedState }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.PostOpStorageUnverifiable.UnsupportedSchema)
        val unverifiable = outcome as RegistrationCoordinatorResult.PostOpStorageUnverifiable.UnsupportedSchema
        assertEquals(42, unverifiable.foundVersion)
        assertEquals(2, unverifiable.expectedVersion)
    }

    @Test
    fun test24_coordinator_headChangedDuringOperation_detectsConcurrencyDrift() {
        val headA = createNormalItem(alarmId = 301L, triggerTime = baseTime + 200_000L)
        val headB = createNormalItem(alarmId = 302L, triggerTime = baseTime + 100_000L)

        val stateA = QueueStorageState.ValidWithItems(listOf(headA))
        val stateB = QueueStorageState.ValidWithItems(listOf(headB, headA))

        val fakeOs = FakeDirectBootExactAlarmManager()

        // Re-read returns stateB (queue was updated concurrently during OS call)
        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = stateA,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null,
            osBoundary = fakeOs,
            reReadQueueState = { stateB }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.HeadChangedDuringOperation)
        val drift = outcome as RegistrationCoordinatorResult.HeadChangedDuringOperation
        assertEquals(headA, drift.initialQueueHead)
        assertEquals(headB, drift.postOpQueueHead)
        assertEquals(stateB, drift.finalQueueState)
        assertEquals(listOf(301L), fakeOs.registered.map { it.alarmId })
    }

    // =========================================================================
    // 10. AlarmClockInfo showIntent Identity & Non-Aliasing
    // =========================================================================

    @Test
    fun test25_showIntent_independentNamespace_noRawToken_occurrenceSpecific() {
        val identity1 = DirectBootV2NormalIntentIdentity(
            alarmId = 42L,
            generation = 1,
            triggerTime = baseTime + 60_000L,
            instanceToken = "secret_token_123"
        )
        val identity2Gen2 = DirectBootV2NormalIntentIdentity(
            alarmId = 42L,
            generation = 2, // distinct generation
            triggerTime = baseTime + 60_000L,
            instanceToken = "secret_token_123"
        )
        val identity3DiffTime = DirectBootV2NormalIntentIdentity(
            alarmId = 42L,
            generation = 1,
            triggerTime = baseTime + 120_000L, // distinct triggerTime
            instanceToken = "secret_token_123"
        )
        val identity4DiffToken = DirectBootV2NormalIntentIdentity(
            alarmId = 42L,
            generation = 1,
            triggerTime = baseTime + 60_000L,
            instanceToken = "other_token_456" // distinct token
        )

        // 1. URI namespace verification
        val uri1 = DirectBootV2ShowIntentFactory.createShowUri(identity1)
        assertEquals("acx-directboot", uri1.scheme)
        assertEquals("show-normal", uri1.authority)
        assertEquals("/alarm/42/v2/${identity1.computeDigest()}", uri1.path)

        // 2. No raw instanceToken in URI
        val uri1String = DirectBootV2ShowIntentFactory.createShowUriString(identity1)
        assertFalse(uri1String.contains("secret_token_123"))

        // 3. Stable across recreation
        assertEquals(uri1String, DirectBootV2ShowIntentFactory.createShowUriString(identity1))

        // 4. Distinct generation / trigger / token produce different URIs
        assertNotEquals(uri1String, DirectBootV2ShowIntentFactory.createShowUriString(identity2Gen2))
        assertNotEquals(uri1String, DirectBootV2ShowIntentFactory.createShowUriString(identity3DiffTime))
        assertNotEquals(uri1String, DirectBootV2ShowIntentFactory.createShowUriString(identity4DiffToken))

        // 5. Intent.filterEquals non-aliasing
        val intent1 = DirectBootV2ShowIntentFactory.createShowIntent(context, identity1)
        val intent2Gen2 = DirectBootV2ShowIntentFactory.createShowIntent(context, identity2Gen2)
        val intent3DiffTime = DirectBootV2ShowIntentFactory.createShowIntent(context, identity3DiffTime)

        assertFalse(intent1.filterEquals(intent2Gen2))
        assertFalse(intent1.filterEquals(intent3DiffTime))

        // Identical identity produces filterEquals true
        val intent1Recreated = DirectBootV2ShowIntentFactory.createShowIntent(context, identity1)
        assertTrue(intent1.filterEquals(intent1Recreated))
    }

    // =========================================================================
    // 11. Storage-Backed Integration & V1 Namespace Isolation
    // =========================================================================

    @Test
    fun test26_storageBacked_endToEndPlanningAndCoordination() {
        val item = createNormalItem(alarmId = 701L, triggerTime = baseTime + 90_000L)
        assertTrue(DirectBootAlarmCache.writeQueue(context, listOf(item)))

        val fakeOs = FakeDirectBootExactAlarmManager()

        val outcome = DirectBootRegistrationCoordinator.coordinateWithStorage(
            context = context,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = null,
            osBoundary = fakeOs
        )

        assertTrue(outcome is RegistrationCoordinatorResult.StableSuccess)
        assertEquals(1, fakeOs.registered.size)
        assertEquals(701L, fakeOs.registered[0].alarmId)

        // Repeat with armed identity -> halts as AlreadyArmed
        val armed = fakeOs.registered[0]
        val secondOutcome = DirectBootRegistrationCoordinator.coordinateWithStorage(
            context = context,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = armed,
            osBoundary = fakeOs
        )

        assertTrue(secondOutcome is RegistrationCoordinatorResult.PlanningHalted)
        val secondHalted = secondOutcome as RegistrationCoordinatorResult.PlanningHalted
        assertTrue(secondHalted.plan is DirectBootRegistrationPlan.AlreadyArmed)
    }

    @Test
    fun test27_v1NamespaceIsolation_neverCancelsV1Alarms() {
        // Actual V1 identity uses snapshot.alarmId.toInt() in DirectBootAlarmReceiver.pendingIntent,
        // and DIRECT_BOOT_SHOW_REQUEST_CODE (91_700) for showIntent.
        // DirectBootRegistrationCoordinator and planner strictly accept DirectBootV2NormalIntentIdentity
        // which targets REQUEST_CODE_V2_NORMAL (91_701) and acx-directboot:// URI scheme.
        val fakeOs = FakeDirectBootExactAlarmManager()

        val emptyState = QueueStorageState.ValidEmpty
        val armedV2 = DirectBootV2NormalIntentIdentity(
            alarmId = 1001L,
            generation = 1,
            triggerTime = baseTime + 20_000L,
            instanceToken = "tok_v2"
        )

        val outcome = DirectBootRegistrationCoordinator.coordinate(
            initialQueueState = emptyState,
            nowEpochMs = baseTime,
            currentlyArmedIdentity = armedV2,
            osBoundary = fakeOs,
            reReadQueueState = { emptyState }
        )

        assertTrue(outcome is RegistrationCoordinatorResult.StableSuccess)
        // Only armedV2 is targeted for cancel; V1 (alarmId.toInt()) is never touched
        assertEquals(listOf(1001L), fakeOs.cancelled.map { it.alarmId })
        assertEquals(DirectBootV2NormalIntentIdentity.URI_SCHEME, armedV2.toUri().scheme)
    }
}

/**
 * Deterministic fake OS interface for testing AlarmManager interactions and failure windows.
 */
class FakeDirectBootExactAlarmManager : DirectBootExactAlarmManagerBoundary {
    var exactPermissionGranted: Boolean = true
    var simulatedRegisterException: Exception? = null
    var simulatedCancelException: Exception? = null
    var simulateUncertainRegister: Boolean = false
    var simulateUncertainCancel: Boolean = false

    val registered = mutableListOf<DirectBootV2NormalIntentIdentity>()
    val cancelled = mutableListOf<DirectBootV2NormalIntentIdentity>()
    val eventLog = mutableListOf<String>()

    override fun canScheduleExactAlarms(): Boolean = exactPermissionGranted

    override fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult {
        eventLog.add("REGISTER:${identity.alarmId}")
        if (!exactPermissionGranted) {
            val msg = "Exact permission denied by policy"
            return ExactRegistrationResult.PermissionUnavailable(msg)
        }
        if (simulateUncertainRegister) {
            val msg = "IPC aborted or timed out"
            return ExactRegistrationResult.StateUncertain(msg)
        }
        simulatedRegisterException?.let { e ->
            if (e is SecurityException) {
                val msg = "SecurityException during setAlarmClock: ${e.message}"
                return ExactRegistrationResult.SecurityError(msg, e)
            } else {
                val msg = "Exception during setAlarmClock: ${e.message}"
                return ExactRegistrationResult.RegistrationException(msg, e)
            }
        }

        registered.add(identity)
        return ExactRegistrationResult.Accepted(identity)
    }

    override fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult {
        eventLog.add("CANCEL:${identity.alarmId}")
        if (simulateUncertainCancel) {
            val msg = "IPC aborted during cancellation"
            return ExactCancellationResult.StateUncertain(msg)
        }
        simulatedCancelException?.let { e ->
            val msg = "Exception during cancel: ${e.message}"
            return ExactCancellationResult.CancellationFailure(msg, e)
        }

        cancelled.add(identity)
        return ExactCancellationResult.Accepted(identity)
    }
}
