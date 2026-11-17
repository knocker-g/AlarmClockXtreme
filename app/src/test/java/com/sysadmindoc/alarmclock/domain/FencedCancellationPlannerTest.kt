package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlin.reflect.KVisibility
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KNO-75 C7-R2-S1-A Option B Contract Test Suite (S1A-T01 through S1A-T12 + boundary invariants).
 *
 * Pure JVM unit tests proving that disconnected Fenced Core primitives enforce fail-closed
 * occurrence identity, never synthesize newer authority, emit deterministic plans with zero
 * runtime side effects, and explicitly preserve `KNOWN_RED04_UNRESOLVED` scope boundaries.
 */
class FencedCancellationPlannerTest {

    private val alarmId = 42L
    private val triggerA = 1_800_000_000_000L
    private val triggerB = 1_800_003_600_000L
    private val capturedAt = 1_799_999_900_000L

    private fun normalAlarmSnapshot(
        id: Long = alarmId,
        kind: String = Alarm.KIND_NORMAL,
        isEnabled: Boolean = true,
        generation: Int = 1,
        revision: Int = 1,
        nextTriggerTime: Long = triggerA
    ): FencedAlarmSnapshot = FencedAlarmSnapshot(
        id = id,
        kind = kind,
        isEnabled = isEnabled,
        generation = generation,
        revision = revision,
        nextTriggerTime = nextTriggerTime
    )

    private fun authoritySnapshot(
        id: Long = alarmId,
        instanceToken: String = "inst-A",
        definitionRevision: Long = 1L,
        scheduleRevision: Long = 2L,
        lifecycle: String = "LIVE",
        desiredOperation: String = "SET",
        desiredTriggerMillis: Long? = triggerA,
        restoreEpoch: Long = 10L,
        reconcileState: String = "SETTLED"
    ): FencedAuthoritySnapshot = FencedAuthoritySnapshot(
        alarmId = id,
        instanceToken = instanceToken,
        definitionRevision = definitionRevision,
        scheduleRevision = scheduleRevision,
        lifecycle = lifecycle,
        desiredOperation = desiredOperation,
        desiredTriggerMillis = desiredTriggerMillis,
        restoreEpoch = restoreEpoch,
        reconcileState = reconcileState
    )

    private fun exactFence(
        id: Long = alarmId,
        kind: String = Alarm.KIND_NORMAL,
        generation: Int = 1,
        revision: Int = 1,
        triggerTime: Long = triggerA,
        scheduleRevision: Long? = 2L,
        definitionRevision: Long? = 1L,
        instanceToken: String? = "inst-A",
        restoreEpoch: Long? = 10L,
        targetScheduledAt: Long? = triggerA,
        targetFireId: String? = "fire-42-A",
        allowZeroedDisabledRoom: Boolean = false,
        allowZeroTriggerPreArm: Boolean = false
    ): CancellationFence.ExactOccurrence = CancellationFence.ExactOccurrence(
        alarmId = id,
        kind = kind,
        expectedGeneration = generation,
        expectedRevision = revision,
        expectedTriggerTime = triggerTime,
        expectedScheduleRevision = scheduleRevision,
        expectedDefinitionRevision = definitionRevision,
        expectedInstanceToken = instanceToken,
        expectedRestoreEpoch = restoreEpoch,
        targetScheduledAt = targetScheduledAt,
        targetFireId = targetFireId,
        allowZeroedDisabledRoom = allowZeroedDisabledRoom,
        allowZeroTriggerPreArm = allowZeroTriggerPreArm
    )

    @Test
    fun s1a_t01_matchingFence_returnsAcceptedPlan_andIsNotOsCancelCompleted() {
        val fence = exactFence()
        val ctx = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(),
            currentAuthority = authoritySnapshot(),
            currentDirectBootSnapshot = FencedDirectBootSnapshot(
                alarmId = alarmId,
                triggerTime = triggerA,
                updatedAt = capturedAt,
                generation = 1,
                revision = 1,
                instanceToken = "inst-A",
                restoreEpoch = 10L
            ),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )

        val outcome = FencedCancellationPlanner.evaluate(fence, ctx, includeFollowUpWorkers = true)

        assertTrue(outcome is CancelOutcome.Accepted)
        val accepted = outcome as CancelOutcome.Accepted
        assertTrue(accepted.isAccepted)
        assertTrue(accepted.isCancellationPlanAllowed)
        assertFalse(accepted.allowsDestructiveCleanup)
        // Evaluation/plan acceptance explicitly does NOT mean OS cancellation already completed.
        assertFalse(accepted.isOsCancelCompleted)
        assertEquals(alarmId, accepted.alarmId)
        assertTrue(accepted.plan.shouldCancelMainOsSlot)
        assertTrue(accepted.plan.shouldAttemptDirectBootRemoval)
        assertEquals(triggerA, accepted.plan.directBootTargetTriggerTime)
        assertTrue(accepted.plan.shouldCancelFollowUpWork)
        assertTrue(accepted.plan.requiresOsVerificationBeforeDestructiveCleanup)
        assertFalse(accepted.plan.solvesLegacySharedPendingIntentRed04)
    }

    @Test
    fun s1a_t02_staleGeneration_returnsStaleRejected_andWithholdsCleanup() {
        val staleFenceA = exactFence(generation = 1, triggerTime = triggerA)
        val ctxWithGenB = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(generation = 2, nextTriggerTime = triggerB),
            currentAuthority = authoritySnapshot(
                instanceToken = "inst-A",
                definitionRevision = 1L,
                scheduleRevision = 2L,
                restoreEpoch = 10L
            ),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )

        val outcome = FencedCancellationPlanner.evaluate(staleFenceA, ctxWithGenB)

        assertTrue(outcome is CancelOutcome.StaleRejected)
        val rejected = outcome as CancelOutcome.StaleRejected
        assertEquals(CancellationRejectionReason.STALE_GENERATION, rejected.reason)
        assertFalse(rejected.isAccepted)
        assertFalse(rejected.isCancellationPlanAllowed)
        assertFalse(rejected.allowsDestructiveCleanup)
        assertFalse(rejected.isOsCancelCompleted)
    }

    @Test
    fun s1a_t03_triggerMismatch_returnsIdentityMismatch_evenWithMatchingAlarmId() {
        val fenceA = exactFence(triggerTime = triggerA, targetScheduledAt = null, targetFireId = null)
        val ctxWithTriggerB = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(nextTriggerTime = triggerB),
            currentAuthority = authoritySnapshot(desiredTriggerMillis = triggerB)
        )

        val outcome = FencedCancellationPlanner.evaluate(fenceA, ctxWithTriggerB)

        assertTrue(outcome is CancelOutcome.IdentityMismatch)
        val mismatch = outcome as CancelOutcome.IdentityMismatch
        assertEquals(CancellationRejectionReason.TRIGGER_TIME_MISMATCH, mismatch.reason)
        assertFalse(mismatch.isAccepted)
        assertFalse(mismatch.allowsDestructiveCleanup)
    }

    @Test
    fun s1a_t04_revisionMismatch_staleOrMismatched_rejectedFailClosed() {
        val fenceRev1 = exactFence(revision = 1, scheduleRevision = 2L)
        val ctxAlarmRev2 = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(revision = 2),
            currentAuthority = authoritySnapshot(scheduleRevision = 2L),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )

        val alarmRevOutcome = FencedCancellationPlanner.evaluate(fenceRev1, ctxAlarmRev2)
        assertTrue(alarmRevOutcome is CancelOutcome.StaleRejected)
        assertEquals(
            CancellationRejectionReason.STALE_REVISION,
            (alarmRevOutcome as CancelOutcome.StaleRejected).reason
        )

        val ctxScheduleRev3 = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(revision = 1),
            currentAuthority = authoritySnapshot(scheduleRevision = 3L),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )
        val scheduleRevOutcome = FencedCancellationPlanner.evaluate(fenceRev1, ctxScheduleRev3)
        assertTrue(scheduleRevOutcome is CancelOutcome.StaleRejected)
        assertEquals(
            CancellationRejectionReason.STALE_SCHEDULE_REVISION,
            (scheduleRevOutcome as CancelOutcome.StaleRejected).reason
        )

        val futureFenceRev5 = exactFence(revision = 5, scheduleRevision = 2L)
        val ctxAlarmRev2ForFutureFence = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(revision = 2),
            currentAuthority = authoritySnapshot(scheduleRevision = 2L),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )
        val futureRevOutcome = FencedCancellationPlanner.evaluate(futureFenceRev5, ctxAlarmRev2ForFutureFence)
        assertTrue(futureRevOutcome is CancelOutcome.IdentityMismatch)
        assertEquals(
            CancellationRejectionReason.REVISION_MISMATCH,
            (futureRevOutcome as CancelOutcome.IdentityMismatch).reason
        )
    }

    @Test
    fun s1a_t05_instanceTokenMismatch_rejectedAsSeparateOccurrence() {
        val fenceA = exactFence(instanceToken = "inst-A")
        val ctxWithTokenB = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(),
            currentAuthority = authoritySnapshot(instanceToken = "inst-B"),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )

        val outcome = FencedCancellationPlanner.evaluate(fenceA, ctxWithTokenB)

        assertTrue(outcome is CancelOutcome.IdentityMismatch)
        val mismatch = outcome as CancelOutcome.IdentityMismatch
        assertEquals(CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH, mismatch.reason)
        assertFalse(mismatch.isAccepted)
    }

    @Test
    fun s1a_t06_restoreEpochMismatch_rejectedWithoutElevatingAuthority() {
        val fenceEpoch10 = exactFence(restoreEpoch = 10L)
        val ctxEpoch11 = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(),
            currentAuthority = authoritySnapshot(restoreEpoch = 11L),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )

        val outcome = FencedCancellationPlanner.evaluate(fenceEpoch10, ctxEpoch11)

        assertTrue(outcome is CancelOutcome.IdentityMismatch)
        val mismatch = outcome as CancelOutcome.IdentityMismatch
        assertEquals(CancellationRejectionReason.RESTORE_EPOCH_MISMATCH, mismatch.reason)
        assertFalse(mismatch.isAccepted)
        assertFalse(mismatch.allowsDestructiveCleanup)
        assertEquals(10L, fenceEpoch10.expectedRestoreEpoch)
    }

    @Test
    fun s1a_t07_missingOrInvalidIdentity_failsClosed_andNeverSynthesizesCurrentAuthority() {
        // 1. Invalid alarmId
        val invalidIdOutcome = FencedCancellationPlanner.evaluate(
            exactFence(id = 0L),
            CancellationEvaluationContext(currentAlarm = normalAlarmSnapshot(), currentAuthority = authoritySnapshot())
        )
        assertTrue(invalidIdOutcome is CancelOutcome.InvalidFence)
        assertEquals(
            CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID,
            (invalidIdOutcome as CancelOutcome.InvalidFence).reason
        )

        // 2. Fence missing authority lineage while DB authority B exists -> must NOT synthesize B
        val missingAuthorityFieldsFence = exactFence(
            instanceToken = null,
            restoreEpoch = null,
            scheduleRevision = null
        )
        val ctxWithAuthorityB = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(generation = 1),
            currentAuthority = authoritySnapshot(instanceToken = "inst-B", restoreEpoch = 99L, scheduleRevision = 5L),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )
        val missingLineageOutcome = FencedCancellationPlanner.evaluate(missingAuthorityFieldsFence, ctxWithAuthorityB)
        assertTrue(missingLineageOutcome is CancelOutcome.InvalidFence)
        assertEquals(
            CancellationRejectionReason.MISSING_AUTHORITY_LINEAGE,
            (missingLineageOutcome as CancelOutcome.InvalidFence).reason
        )

        // 3. Authority missing in DB when required -> AuthorityUnavailable
        val missingAuthorityInDbOutcome = FencedCancellationPlanner.evaluate(
            exactFence(),
            CancellationEvaluationContext(
                currentAlarm = normalAlarmSnapshot(),
                currentAuthority = null,
                authorityRequired = true
            )
        )
        assertTrue(missingAuthorityInDbOutcome is CancelOutcome.AuthorityUnavailable)
        assertFalse(missingAuthorityInDbOutcome.isAccepted)
    }

    @Test
    fun s1a_t08_duplicateEvaluation_isDeterministicAndIdempotent_withoutSideEffects() {
        val fence = exactFence()
        val ctx = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(),
            currentAuthority = authoritySnapshot(),
            currentDirectBootSnapshot = FencedDirectBootSnapshot(
                alarmId = alarmId,
                triggerTime = triggerA,
                updatedAt = capturedAt,
                generation = 1,
                revision = 1,
                instanceToken = "inst-A",
                restoreEpoch = 10L
            ),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )

        val first = FencedCancellationPlanner.evaluate(fence, ctx, includeFollowUpWorkers = true)
        val second = FencedCancellationPlanner.evaluate(fence, ctx, includeFollowUpWorkers = true)
        val third = FencedCancellationPlanner.evaluate(fence, ctx, includeFollowUpWorkers = true)

        assertEquals(first, second)
        assertEquals(second, third)
        assertTrue(first is CancelOutcome.Accepted)
    }

    @Test
    fun s1a_t09_pureEvaluation_hasZeroRuntimeSideEffects() {
        // Structural proof: FencedCancellationPlanner exposes only pure Kotlin functions taking
        // immutable value snapshots; it holds no Android Context, AlarmManager, WorkManager,
        // DirectBootAlarmCache, Room DAO, or mutable runtime state.
        val plannerProperties = FencedCancellationPlanner::class.memberProperties
            .filter { it.visibility == KVisibility.PUBLIC }
        assertTrue(plannerProperties.isEmpty())

        val ctx = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(),
            currentAuthority = authoritySnapshot(),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )
        val outcome = FencedCancellationPlanner.evaluate(exactFence(), ctx)
        assertTrue(outcome is CancelOutcome.Accepted)
        val accepted = outcome as CancelOutcome.Accepted
        // Plan only describes intended steps; nothing is executed or cancelled yet.
        assertFalse(accepted.isOsCancelCompleted)
        assertTrue(accepted.plan.requiresOsVerificationBeforeDestructiveCleanup)
    }

    @Test
    fun s1a_t10_fenceIdentity_isImmutablePostConstruction() {
        val fenceClasses = listOf(
            CancellationFence.ExactOccurrence::class,
            CancellationFence.DefinitionDeleteOrDisable::class,
            CancellationFence.OrphanToken::class,
            CancellationFence.QuickOccurrence::class
        )
        fenceClasses.forEach { kClass ->
            val mutableProps = kClass.memberProperties.filterIsInstance<kotlin.reflect.KMutableProperty<*>>()
            assertTrue("${kClass.simpleName} must have zero mutable properties", mutableProps.isEmpty())
        }

        val fence = exactFence(generation = 1, instanceToken = "inst-A")
        assertEquals(1, fence.expectedGeneration)
        assertEquals("inst-A", fence.expectedInstanceToken)
    }

    @Test
    fun s1a_t11_differentGenerationSameTrigger_rejected_andExplicitlyDoesNotSolveLegacyRed04() {
        // 1. Same alarmId + same triggerTime + same instanceToken, but newer generation=2 -> StaleRejected.
        val staleFenceA = exactFence(
            id = alarmId,
            generation = 1,
            revision = 1,
            triggerTime = triggerA,
            instanceToken = "inst-A"
        )
        val ctxGen2SameToken = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(
                id = alarmId,
                generation = 2,
                revision = 1,
                nextTriggerTime = triggerA
            ),
            currentAuthority = authoritySnapshot(
                id = alarmId,
                instanceToken = "inst-A",
                definitionRevision = 1L,
                scheduleRevision = 2L
            ),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )

        val staleGenOutcome = FencedCancellationPlanner.evaluate(staleFenceA, ctxGen2SameToken)
        assertTrue(staleGenOutcome is CancelOutcome.StaleRejected)
        assertEquals(
            CancellationRejectionReason.STALE_GENERATION,
            (staleGenOutcome as CancelOutcome.StaleRejected).reason
        )

        // 2. Same alarmId + same triggerTime, different instanceToken ("inst-B") -> IdentityMismatch.
        val ctxGen2DifferentToken = ctxGen2SameToken.copy(
            currentAuthority = authoritySnapshot(
                id = alarmId,
                instanceToken = "inst-B",
                definitionRevision = 2L,
                scheduleRevision = 2L
            )
        )
        val tokenMismatchOutcome = FencedCancellationPlanner.evaluate(staleFenceA, ctxGen2DifferentToken)
        assertTrue(tokenMismatchOutcome is CancelOutcome.IdentityMismatch)
        assertEquals(
            CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH,
            (tokenMismatchOutcome as CancelOutcome.IdentityMismatch).reason
        )

        // 3. Even for a matching fence, the emitted plan explicitly records that it does NOT
        // solve legacy V1 shared PendingIntent collisions (KNOWN_RED04_UNRESOLVED).
        val accepted = FencedCancellationPlanner.evaluate(
            exactFence(generation = 2, instanceToken = "inst-B", definitionRevision = 2L),
            ctxGen2DifferentToken
        ) as CancelOutcome.Accepted
        assertFalse(accepted.plan.solvesLegacySharedPendingIntentRed04)
    }

    @Test
    fun s1a_t12_outcomeClassification_rejectedAndUncertainOutcomesNeverMapToAcceptedOrOsCompleted() {
        val samplePlan = FencedCancellationPlan(
            alarmId = alarmId,
            fenceKind = "EXACT_OCCURRENCE",
            shouldCancelMainOsSlot = true,
            shouldAttemptDirectBootRemoval = true,
            directBootTargetTriggerTime = triggerA,
            shouldCancelFollowUpWork = true,
            revokeIssuedOccurrenceToken = null,
            callerRoomMutationPermittedAfterOsCancel = true
        )
        val outcomes: List<CancelOutcome> = listOf(
            CancelOutcome.Accepted(alarmId, samplePlan),
            CancelOutcome.NoOpAbsent(alarmId, CancellationRejectionReason.ALREADY_DELETED_OR_ABSENT, "absent"),
            CancelOutcome.StaleRejected(alarmId, CancellationRejectionReason.STALE_GENERATION, "stale"),
            CancelOutcome.IdentityMismatch(alarmId, CancellationRejectionReason.TRIGGER_TIME_MISMATCH, "mismatch"),
            CancelOutcome.AuthorityUnavailable(alarmId, detail = "missing"),
            CancelOutcome.OsStateUncertain(alarmId, detail = "os unknown"),
            CancelOutcome.PersistenceUncertain(alarmId, detail = "db uncertain"),
            CancelOutcome.InvalidFence(alarmId, CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID, "invalid"),
            CancelOutcome.KindMismatch(alarmId, detail = "kind mismatch")
        )

        outcomes.forEach { outcome ->
            assertFalse("Pure planning outcome must never report OS cancel completed", outcome.isOsCancelCompleted)
            assertFalse("Pure planning outcome must never allow destructive cleanup", outcome.allowsDestructiveCleanup)
            if (outcome !is CancelOutcome.Accepted) {
                assertFalse("${outcome::class.simpleName} must not be accepted", outcome.isAccepted)
                assertFalse("${outcome::class.simpleName} must not allow cancel plan", outcome.isCancellationPlanAllowed)
            }
        }

        // Also verify evaluate() returns OsStateUncertain and PersistenceUncertain when probed
        val osUncertain = FencedCancellationPlanner.evaluate(
            exactFence(),
            CancellationEvaluationContext(
                currentAlarm = normalAlarmSnapshot(),
                currentAuthority = authoritySnapshot(),
                osState = OsCancelProbeState.FAILED_UNKNOWN
            )
        )
        assertTrue(osUncertain is CancelOutcome.OsStateUncertain)
        assertFalse(osUncertain.isAccepted)

        val persistenceUncertain = FencedCancellationPlanner.evaluate(
            exactFence(),
            CancellationEvaluationContext(
                currentAlarm = normalAlarmSnapshot(),
                currentAuthority = authoritySnapshot(),
                persistenceState = PersistenceProbeState.UNCERTAIN
            )
        )
        assertTrue(persistenceUncertain is CancelOutcome.PersistenceUncertain)
        assertFalse(persistenceUncertain.isAccepted)
    }

    @Test
    fun s1a_t13_definitionDeleteOrDisable_beforeAndAfterCancel_enforcesRoomOrderAndAuthority() {
        // BEFORE_CANCEL: concurrent re-enable in Room -> StaleRejected
        val beforeDisableFence = CancellationFence.DefinitionDeleteOrDisable(
            alarmId = alarmId,
            actionKind = DefinitionActionKind.DISABLE,
            roomMutationOrder = RoomMutationOrder.BEFORE_CANCEL,
            expectedGeneration = 1,
            expectedRevision = 1,
            expectedTriggerTime = triggerA,
            expectedInstanceToken = "inst-A",
            expectedRestoreEpoch = 10L,
            expectedDefinitionRevision = 1L,
            expectedScheduleRevision = 3L,
            capturedAtMillis = capturedAt
        )
        val reEnabledOutcome = FencedCancellationPlanner.evaluate(
            beforeDisableFence,
            CancellationEvaluationContext(
                currentAlarm = normalAlarmSnapshot(isEnabled = true),
                currentAuthority = authoritySnapshot(lifecycle = "LIVE", desiredOperation = "SET", scheduleRevision = 4L)
            )
        )
        assertTrue(reEnabledOutcome is CancelOutcome.StaleRejected)

        // BEFORE_CANCEL valid disabled state -> Accepted
        val validBeforeOutcome = FencedCancellationPlanner.evaluate(
            beforeDisableFence,
            CancellationEvaluationContext(
                currentAlarm = normalAlarmSnapshot(isEnabled = false, nextTriggerTime = 0L),
                currentAuthority = authoritySnapshot(
                    lifecycle = "DISABLED",
                    desiredOperation = "CANCEL",
                    desiredTriggerMillis = null,
                    scheduleRevision = 3L
                )
            )
        )
        assertTrue(validBeforeOutcome is CancelOutcome.Accepted)

        // AFTER_CANCEL on already-deleted row -> NoOpAbsent (and zero destructive Room ops needed)
        val afterDeleteFence = CancellationFence.DefinitionDeleteOrDisable(
            alarmId = alarmId,
            actionKind = DefinitionActionKind.DELETE,
            roomMutationOrder = RoomMutationOrder.AFTER_CANCEL,
            expectedGeneration = 1,
            expectedRevision = 1,
            expectedTriggerTime = triggerA,
            expectedInstanceToken = "inst-A",
            expectedRestoreEpoch = 10L,
            expectedDefinitionRevision = 1L,
            expectedScheduleRevision = 2L,
            capturedAtMillis = capturedAt
        )
        val alreadyDeletedOutcome = FencedCancellationPlanner.evaluate(
            afterDeleteFence,
            CancellationEvaluationContext(
                currentAlarm = null,
                currentAuthority = null,
                authorityRequired = false
            )
        )
        assertTrue(alreadyDeletedOutcome is CancelOutcome.NoOpAbsent)
        assertFalse(alreadyDeletedOutcome.isAccepted)
    }

    @Test
    fun s1a_t14_orphanToken_rejectsReEnabledAlarmOrSupersededSlot_andAllowsRevokePlanOnDisabledOrphan() {
        val orphanFence = CancellationFence.OrphanToken(
            alarmId = alarmId,
            issueToken = "tok-old",
            expectedScheduledAt = triggerA,
            expectedFireId = "fire-42-A",
            expectedSlotGenerationId = 3L
        )
        val activeOccurrence = FencedIssuedOccurrenceSnapshot(
            issueToken = "tok-old",
            alarmId = alarmId,
            scheduledAt = triggerA,
            fireId = "fire-42-A",
            generationId = 3L,
            status = IssuedOccurrence.STATUS_ISSUED
        )

        // Re-enabled between query and cancel -> StaleRejected
        val enabledOutcome = FencedCancellationPlanner.evaluate(
            orphanFence,
            CancellationEvaluationContext(
                currentAlarm = normalAlarmSnapshot(isEnabled = true),
                currentAuthority = null,
                authorityRequired = false,
                currentSlot = FencedIssueSlotSnapshot(alarmId, "tok-old", 3L),
                currentOccurrence = activeOccurrence
            )
        )
        assertTrue(enabledOutcome is CancelOutcome.StaleRejected)
        assertEquals(
            CancellationRejectionReason.ORPHAN_ALARM_ENABLED,
            (enabledOutcome as CancelOutcome.StaleRejected).reason
        )

        // Disabled orphan with matching slot/token -> Accepted with token revocation target
        val validOrphanOutcome = FencedCancellationPlanner.evaluate(
            orphanFence,
            CancellationEvaluationContext(
                currentAlarm = normalAlarmSnapshot(isEnabled = false, nextTriggerTime = 0L),
                currentAuthority = null,
                authorityRequired = false,
                currentSlot = FencedIssueSlotSnapshot(alarmId, "tok-old", 3L),
                currentOccurrence = activeOccurrence
            )
        )
        assertTrue(validOrphanOutcome is CancelOutcome.Accepted)
        val plan = (validOrphanOutcome as CancelOutcome.Accepted).plan
        assertEquals("tok-old", plan.revokeIssuedOccurrenceToken)
    }

    @Test
    fun s1a_t15_quickOccurrence_isolatedFromNormalDirectBoot_andRejectsMismatchOrKindMismatch() {
        val quickFence = CancellationFence.QuickOccurrence(
            alarmId = 77L,
            quickTriggerEpochMs = triggerA,
            quickRelativeElapsedMs = 300_000L,
            quickBootCount = 5L
        )

        val normalRowCtx = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(id = 77L, kind = Alarm.KIND_NORMAL),
            authorityRequired = false
        )
        assertTrue(FencedCancellationPlanner.evaluate(quickFence, normalRowCtx) is CancelOutcome.KindMismatch)

        val matchingQuickRow = FencedAlarmSnapshot(
            id = 77L,
            kind = Alarm.KIND_QUICK,
            isEnabled = true,
            generation = 1,
            revision = 1,
            nextTriggerTime = triggerA,
            quickTriggerEpochMs = triggerA,
            quickRelativeElapsedMs = 300_000L,
            quickBootCount = 5L
        )
        val quickAccepted = FencedCancellationPlanner.evaluate(
            quickFence,
            CancellationEvaluationContext(
                currentAlarm = matchingQuickRow,
                authorityRequired = false,
                currentDirectBootSnapshot = FencedDirectBootSnapshot(alarmId = 77L, triggerTime = triggerA, updatedAt = capturedAt)
            )
        )
        assertTrue(quickAccepted is CancelOutcome.Accepted)
        // Quick domain must never attempt V1 Normal DirectBoot removal.
        assertFalse((quickAccepted as CancelOutcome.Accepted).plan.shouldAttemptDirectBootRemoval)
        assertNull(quickAccepted.plan.directBootTargetTriggerTime)
    }

    @Test
    fun s1a_t16_directBootSnapshotEvaluation_preservesNewerBOnTriggerOrLineageDrift() {
        val fenceA = exactFence(
            generation = 1,
            revision = 1,
            triggerTime = triggerA,
            instanceToken = "inst-A",
            restoreEpoch = 10L
        )
        val matchingSnapA = FencedDirectBootSnapshot(
            alarmId = alarmId,
            triggerTime = triggerA,
            updatedAt = capturedAt,
            generation = 1,
            revision = 1,
            instanceToken = "inst-A",
            restoreEpoch = 10L
        )
        val driftedTriggerSnapB = matchingSnapA.copy(triggerTime = triggerB)
        val driftedGenSnapB = matchingSnapA.copy(generation = 2)
        val driftedTokenSnapB = matchingSnapA.copy(instanceToken = "inst-B")

        assertTrue(FencedCancellationPlanner.shouldRemoveDirectBootSnapshot(fenceA, matchingSnapA))
        assertFalse(FencedCancellationPlanner.shouldRemoveDirectBootSnapshot(fenceA, driftedTriggerSnapB))
        assertFalse(FencedCancellationPlanner.shouldRemoveDirectBootSnapshot(fenceA, driftedGenSnapB))
        assertFalse(FencedCancellationPlanner.shouldRemoveDirectBootSnapshot(fenceA, driftedTokenSnapB))
        assertFalse(FencedCancellationPlanner.shouldRemoveDirectBootSnapshot(fenceA, null))
    }

    @Test
    fun s1a_t17_purePlanningOutcome_neverAllowsDestructiveCleanupAcrossOsStatesAndRejections() {
        val baseContext = CancellationEvaluationContext(
            currentAlarm = normalAlarmSnapshot(),
            currentAuthority = authoritySnapshot(),
            currentDirectBootSnapshot = FencedDirectBootSnapshot(
                alarmId = alarmId,
                triggerTime = triggerA,
                updatedAt = capturedAt,
                generation = 1,
                revision = 1,
                instanceToken = "inst-A",
                restoreEpoch = 10L
            ),
            expectedFireIdForCurrentTrigger = "fire-42-A"
        )

        // 1-3. Accepted + NOT_ATTEMPTED / CANCELLED / NO_EXISTING -> plan accepted, cleanup disallowed
        listOf(
            OsCancelProbeState.NOT_ATTEMPTED to true,
            OsCancelProbeState.CANCELLED to true,
            OsCancelProbeState.NO_EXISTING to false
        ).forEach { (osState, expectedMainOsCancel) ->
            val outcome = FencedCancellationPlanner.evaluate(
                exactFence(),
                baseContext.copy(osState = osState)
            )
            assertTrue("Expected Accepted for osState=$osState", outcome is CancelOutcome.Accepted)
            val accepted = outcome as CancelOutcome.Accepted
            assertTrue(accepted.isAccepted)
            assertTrue(accepted.isCancellationPlanAllowed)
            assertFalse("Pure planner must never allow destructive cleanup for osState=$osState", accepted.allowsDestructiveCleanup)
            assertFalse("Pure planner must never claim OS cancel completed for osState=$osState", accepted.isOsCancelCompleted)
            assertTrue(accepted.plan.requiresOsVerificationBeforeDestructiveCleanup)
            assertEquals(expectedMainOsCancel, accepted.plan.shouldCancelMainOsSlot)
        }

        // 4. FAILED_UNKNOWN -> OsStateUncertain, cleanup disallowed
        val osUnknown = FencedCancellationPlanner.evaluate(
            exactFence(),
            baseContext.copy(osState = OsCancelProbeState.FAILED_UNKNOWN)
        )
        assertTrue(osUnknown is CancelOutcome.OsStateUncertain)
        assertFalse(osUnknown.isCancellationPlanAllowed)
        assertFalse(osUnknown.allowsDestructiveCleanup)
        assertFalse(osUnknown.isOsCancelCompleted)

        // 5. StaleRejected -> cleanup disallowed
        val stale = FencedCancellationPlanner.evaluate(
            exactFence(generation = 1),
            baseContext.copy(currentAlarm = normalAlarmSnapshot(generation = 2))
        )
        assertTrue(stale is CancelOutcome.StaleRejected)
        assertFalse(stale.isCancellationPlanAllowed)
        assertFalse(stale.allowsDestructiveCleanup)

        // 6. IdentityMismatch -> cleanup disallowed
        val mismatch = FencedCancellationPlanner.evaluate(
            exactFence(triggerTime = triggerA, targetScheduledAt = null, targetFireId = null),
            baseContext.copy(currentAlarm = normalAlarmSnapshot(nextTriggerTime = triggerB))
        )
        assertTrue(mismatch is CancelOutcome.IdentityMismatch)
        assertFalse(mismatch.isCancellationPlanAllowed)
        assertFalse(mismatch.allowsDestructiveCleanup)

        // 7. PersistenceUncertain -> cleanup disallowed
        val persistenceUncertain = FencedCancellationPlanner.evaluate(
            exactFence(),
            baseContext.copy(persistenceState = PersistenceProbeState.UNCERTAIN)
        )
        assertTrue(persistenceUncertain is CancelOutcome.PersistenceUncertain)
        assertFalse(persistenceUncertain.isCancellationPlanAllowed)
        assertFalse(persistenceUncertain.allowsDestructiveCleanup)
    }
}
