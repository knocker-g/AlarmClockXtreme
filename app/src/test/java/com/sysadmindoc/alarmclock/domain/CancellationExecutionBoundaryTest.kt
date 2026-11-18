package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CancellationExecutionBoundary] verifying all 20 Safety Cases,
 * mandatory non-executable invariants, and Pure Evidence Integrity contracts.
 */
class CancellationExecutionBoundaryTest {

    private fun createValidAlarm(
        id: Long = 42L,
        generation: Int = 1,
        revision: Int = 1,
        triggerTime: Long = 1_700_000_000_000L
    ): Alarm = Alarm(
        id = id,
        generation = generation,
        revision = revision,
        nextTriggerTime = triggerTime,
        isEnabled = true
    )

    private fun createValidAuthority(
        alarmId: Long = 42L,
        scheduleRevision: Long = 100L,
        instanceToken: String = "token-42-v1",
        restoreEpoch: Long = 1L
    ): ScheduleAuthority = ScheduleAuthority(
        alarmId = alarmId,
        instanceToken = instanceToken,
        definitionRevision = 10L,
        scheduleRevision = scheduleRevision,
        lifecycle = "ARMED",
        desiredOperation = "SCHEDULE",
        desiredTriggerMillis = 1_700_000_000_000L,
        reconcileState = "SYNCHRONIZED",
        restoreEpoch = restoreEpoch,
        actionKey = "action-key-42"
    )

    private fun createQualifiedCaller(
        alarmId: Long = 42L,
        generation: Int = 1,
        revision: Int = 1,
        triggerTime: Long = 1_700_000_000_000L,
        instanceToken: String = "token-42-v1",
        restoreEpoch: Long = 1L
    ): CallerOriginIdentity {
        val alarm = createValidAlarm(alarmId, generation, revision, triggerTime)
        val authority = createValidAuthority(alarmId, instanceToken = instanceToken, restoreEpoch = restoreEpoch)
        return CallerOriginIdentity.captureNormalExact(alarm, authority, 1000L)
    }

    private fun createAcceptedFence(caller: CallerOriginIdentity): CancellationFence.ExactOccurrence {
        val result = CallerOriginFenceConverter.toFenceCandidate(caller)
        assertTrue("Caller identity must be qualified", result is CallerOriginPropagationResult.QualifiedCandidate)
        return (result as CallerOriginPropagationResult.QualifiedCandidate).fence as CancellationFence.ExactOccurrence
    }

    private fun createAcceptedPlan(fence: CancellationFence.ExactOccurrence): CancelOutcome.Accepted {
        val plan = FencedCancellationPlan(
            alarmId = fence.alarmId,
            fenceKind = fence.kind,
            shouldCancelMainOsSlot = true,
            shouldAttemptDirectBootRemoval = true,
            directBootTargetTriggerTime = fence.expectedTriggerTime,
            shouldCancelFollowUpWork = true,
            revokeIssuedOccurrenceToken = fence.expectedInstanceToken,
            callerRoomMutationPermittedAfterOsCancel = true
        )
        return CancelOutcome.Accepted(alarmId = fence.alarmId, plan = plan)
    }

    // --- Mandatory Non-Executable Invariant Tests ---

    @Test
    fun invariant_allDecisionsHaveZeroExecutionAuthority() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            pendingIntentLookup = PendingIntentLookupStatus.LOOKUP_FOUND,
            osCancelInvocation = OsCancelInvocationStatus.INVOCATION_COMPLETED,
            osCancelProof = OsCancelProofStatus.PROVEN_CANCELLED,
            deLineageStatus = DeLineageStatus.VERIFIED_MATCH,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            isLegacyRed04Unresolved = false,
            includeFollowUpWorkers = true
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        assertTrue("Report must indicate all decisions are non-executable", report.isAllNonExecutable)
        assertEquals(8, report.decisions.size)

        report.decisions.values.forEach { decision ->
            assertFalse("isPermitted must be false", decision.isPermitted)
            assertFalse("allowsDestructiveCleanup must be false", decision.allowsDestructiveCleanup)
            assertFalse("isOsCancelCompleted must be false", decision.isOsCancelCompleted)
            assertFalse("executesCancellation must be false", decision.executesCancellation)
        }
    }

    // --- Safety Case 01 ---
    @Test
    fun safetyCase01_cancellationCompetesWithSuccessorB() {
        val caller = createQualifiedCaller(generation = 1)
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.SUCCESSOR_EXISTS
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue("Decision for $target must be Blocked", decision is PureEffectDecision.Blocked)
            val blocked = decision as PureEffectDecision.Blocked
            assertEquals(CancellationRejectionReason.STALE_GENERATION, blocked.reason)
        }
    }

    // --- Safety Case 02 ---
    @Test
    fun safetyCase02_sameAlarmIdAndTriggerDifferentIdentity() {
        val caller = createQualifiedCaller(alarmId = 42L, triggerTime = 1000L)
        val fence = createAcceptedFence(caller)

        val planMismatch = CancelOutcome.StaleRejected(
            alarmId = 42L,
            reason = CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH,
            detail = "Instance token mismatch: expected token-42-v1 but found token-42-v2"
        )

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = planMismatch
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue("Decision for $target must be Blocked", decision is PureEffectDecision.Blocked)
            val blocked = decision as PureEffectDecision.Blocked
            assertEquals(CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH, blocked.reason)
        }
    }

    // --- Safety Case 03 ---
    @Test
    fun safetyCase03_instanceTokenMismatch() {
        val caller = createQualifiedCaller(instanceToken = "token-A")
        val fence = createAcceptedFence(caller)
        val planMismatch = CancelOutcome.IdentityMismatch(
            alarmId = 42L,
            reason = CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH,
            detail = "Instance token mismatch"
        )

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = planMismatch
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue(decision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH, (decision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 04 ---
    @Test
    fun safetyCase04_restoreEpochMismatch() {
        val caller = createQualifiedCaller(restoreEpoch = 1L)
        val fence = createAcceptedFence(caller)
        val planMismatch = CancelOutcome.IdentityMismatch(
            alarmId = 42L,
            reason = CancellationRejectionReason.RESTORE_EPOCH_MISMATCH,
            detail = "Restore epoch mismatch"
        )

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = planMismatch
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue(decision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.RESTORE_EPOCH_MISMATCH, (decision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 05 ---
    @Test
    fun safetyCase05_osFailureOrUncertainty() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            osCancelInvocation = OsCancelInvocationStatus.INVOCATION_FAILED,
            osCancelProof = OsCancelProofStatus.UNCERTAIN,
            deLineageStatus = DeLineageStatus.VERIFIED_MATCH,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            isLegacyRed04Unresolved = false
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue("Decision must be EvidenceIncomplete due to OS failure", decision is PureEffectDecision.EvidenceIncomplete)
        assertEquals("OS_STATE_UNKNOWN", (decision as PureEffectDecision.EvidenceIncomplete).missingWitness)
    }

    // --- Safety Case 06 ---
    @Test
    fun safetyCase06_noExistingWithoutCleanupAuthority() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            osCancelProof = OsCancelProofStatus.PROVEN_NO_EXISTING,
            deLineageStatus = DeLineageStatus.VERIFIED_MATCH,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            isLegacyRed04Unresolved = false
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue(decision is PureEffectDecision.CandidateForFutureVerification)
        assertFalse("CandidateForFutureVerification must NOT grant execution permission", decision!!.allowsDestructiveCleanup)
        assertFalse("CandidateForFutureVerification must NOT grant isPermitted", decision.isPermitted)
    }

    // --- Safety Case 07 ---
    @Test
    fun safetyCase07_mutexExpiresBeforeCleanup() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXPIRED_OUTSIDE_MUTEX
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue("Decision for $target must be Deferred", decision is PureEffectDecision.Deferred)
            assertEquals(CancellationRejectionReason.OS_STATE_UNCERTAIN, (decision as PureEffectDecision.Deferred).reason)
        }
    }

    // --- Safety Case 08 ---
    @Test
    fun safetyCase08_legacyRed04UnprovenOsIdentity() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            isLegacyRed04Unresolved = true
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val osDecision = report.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        assertTrue(osDecision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.OS_STATE_UNCERTAIN, (osDecision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 09 ---
    @Test
    fun safetyCase09_deSnapshotLacksLineage() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            deLineageStatus = DeLineageStatus.LINEAGE_MISSING
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue(decision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.PERSISTENCE_UNCERTAIN, (decision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 10 ---
    @Test
    fun safetyCase10_workerIdentifiedByAlarmIdAloneWhenFollowUpFalse() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            includeFollowUpWorkers = false
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val guardianDecision = report.decisions[SideEffectTarget.GUARDIAN_WORKER]
        assertTrue(guardianDecision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH, (guardianDecision as PureEffectDecision.Blocked).reason)

        val wakeConfirmDecision = report.decisions[SideEffectTarget.WAKE_CONFIRM_WORKER_AND_STORE]
        assertTrue(wakeConfirmDecision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH, (wakeConfirmDecision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 11 ---
    @Test
    fun safetyCase11_normalQuickKindMismatch() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val planKindMismatch = CancelOutcome.StaleRejected(
            alarmId = 42L,
            reason = CancellationRejectionReason.KIND_MISMATCH,
            detail = "Kind mismatch: expected NORMAL but found QUICK"
        )

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = planKindMismatch
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue(decision is PureEffectDecision.Blocked)
            assertEquals(CancellationRejectionReason.KIND_MISMATCH, (decision as PureEffectDecision.Blocked).reason)
        }
    }

    // --- Safety Case 12 ---
    @Test
    fun safetyCase12_roomDeStateUncertain() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val planUncertain = CancelOutcome.AuthorityUnavailable(
            alarmId = 42L,
            reason = CancellationRejectionReason.AUTHORITY_UNAVAILABLE,
            detail = "Database locked"
        )

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = planUncertain
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue(decision is PureEffectDecision.Blocked)
            assertEquals(CancellationRejectionReason.AUTHORITY_UNAVAILABLE, (decision as PureEffectDecision.Blocked).reason)
        }
    }

    // --- Safety Case 13 ---
    @Test
    fun safetyCase13_osCallSucceedsButTargetsB() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            osCancelInvocation = OsCancelInvocationStatus.INVOCATION_COMPLETED,
            osCancelProof = OsCancelProofStatus.UNPROVEN_IDENTITY,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            isLegacyRed04Unresolved = false
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        assertTrue(decision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.TARGET_WITNESS_MISMATCH, (decision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 14 ---
    @Test
    fun safetyCase14_pendingIntentLookupReturnsNull() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            pendingIntentLookup = PendingIntentLookupStatus.LOOKUP_NULL,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            isLegacyRed04Unresolved = false
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        assertTrue(decision is PureEffectDecision.EvidenceIncomplete)
        assertEquals("PENDING_INTENT_LOOKUP_NULL", (decision as PureEffectDecision.EvidenceIncomplete).missingWitness)
    }

    // --- Safety Case 15 ---
    @Test
    fun safetyCase15_bRegisteredAfterSnapshotVerification() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.SUCCESSOR_EXISTS
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue(decision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.STALE_GENERATION, (decision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 16 ---
    @Test
    fun safetyCase16_sameTriggerDifferentInstanceToken() {
        val caller = createQualifiedCaller(instanceToken = "tok-1")
        val fence = createAcceptedFence(caller)
        val planMismatch = CancelOutcome.IdentityMismatch(
            alarmId = 42L,
            reason = CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH,
            detail = "Token mismatch"
        )

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = planMismatch
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.ISSUED_OCCURRENCE_TOKEN]
        assertTrue(decision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH, (decision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 17 ---
    @Test
    fun safetyCase17_changedExtrasButIdenticalPendingIntentIdentity() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            isLegacyRed04Unresolved = true
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        assertTrue(decision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.OS_STATE_UNCERTAIN, (decision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 18 ---
    @Test
    fun safetyCase18_sharedWorkManagerUniqueName() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.SUCCESSOR_EXISTS,
            includeFollowUpWorkers = true
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val hueDecision = report.decisions[SideEffectTarget.HUE_SUNRISE_WORKER]
        assertTrue(hueDecision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.STALE_GENERATION, (hueDecision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 19 ---
    @Test
    fun safetyCase19_deLineagePartiallyMissing() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            deLineageStatus = DeLineageStatus.LINEAGE_MISSING
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val decision = report.decisions[SideEffectTarget.DIRECT_BOOT_SNAPSHOT]
        assertTrue(decision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.PERSISTENCE_UNCERTAIN, (decision as PureEffectDecision.Blocked).reason)
    }

    // --- Safety Case 20 ---
    @Test
    fun safetyCase20_completeCallerOriginButUnknownEffectIdentity() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = null
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue(decision is PureEffectDecision.EvidenceIncomplete)
            assertEquals("PLANNER_OUTCOME_MISSING", (decision as PureEffectDecision.EvidenceIncomplete).missingWitness)
        }
    }

    // --- NEW RED Integrity Tests (Evidence Correction Phase) ---

    @Test
    fun red01_callerA_fenceB_mismatchedIdentity() {
        val callerA = createQualifiedCaller(alarmId = 42L)
        val callerB = createQualifiedCaller(alarmId = 99L)
        val fenceB = createAcceptedFence(callerB)
        val planB = createAcceptedPlan(fenceB)

        val context = BoundaryEvaluationContext(
            callerIdentity = callerA,
            cancellationFence = fenceB,
            plannerOutcome = planB
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue("Decision for $target must be Blocked due to cross-entity mismatch", decision is PureEffectDecision.Blocked)
            val blocked = decision as PureEffectDecision.Blocked
            assertEquals(CancellationRejectionReason.MISSING_OR_INVALID_ALARM_ID, blocked.reason)
        }
    }

    @Test
    fun red02_callerA_planB_mismatchedPlan() {
        val callerA = createQualifiedCaller(alarmId = 42L)
        val fenceA = createAcceptedFence(callerA)
        // planB has same alarmId = 42L but mismatched fenceKind = "QUICK"
        val planB = CancelOutcome.Accepted(
            alarmId = 42L,
            plan = FencedCancellationPlan(
                alarmId = 42L,
                fenceKind = Alarm.KIND_QUICK,
                shouldCancelMainOsSlot = true,
                shouldAttemptDirectBootRemoval = true,
                directBootTargetTriggerTime = 1000L,
                shouldCancelFollowUpWork = true,
                revokeIssuedOccurrenceToken = "token",
                callerRoomMutationPermittedAfterOsCancel = true
            )
        )

        val context = BoundaryEvaluationContext(
            callerIdentity = callerA,
            cancellationFence = fenceA,
            plannerOutcome = planB
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue("Decision for $target must be Blocked due to cross-entity plan mismatch", decision is PureEffectDecision.Blocked)
            val blocked = decision as PureEffectDecision.Blocked
            assertEquals(CancellationRejectionReason.TARGET_WITNESS_MISMATCH, blocked.reason)
        }
    }

    @Test
    fun red03_unqualifiedLegacyCaller() {
        val alarm = createValidAlarm(id = 42L)
        // Missing ScheduleAuthority -> Unqualified
        val unqualifiedIdentity = CallerOriginIdentity.captureNormalExact(alarm, null, 1000L)
        assertFalse(unqualifiedIdentity.instanceToken != null)

        val fence = CancellationFence.ExactOccurrence(
            alarmId = 42L,
            expectedGeneration = 1,
            expectedRevision = 1,
            expectedTriggerTime = 1_700_000_000_000L
        )

        val context = BoundaryEvaluationContext(
            callerIdentity = unqualifiedIdentity,
            cancellationFence = fence,
            plannerOutcome = null
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue("Unqualified legacy caller must return EvidenceIncomplete or Blocked", decision is PureEffectDecision.EvidenceIncomplete || decision is PureEffectDecision.Blocked)
        }
    }

    @Test
    fun red06_successorUnknownReturnsEvidenceIncomplete() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.UNKNOWN
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        SideEffectTarget.values().forEach { target ->
            val decision = report.decisions[target]
            assertTrue("Successor UNKNOWN must return EvidenceIncomplete", decision is PureEffectDecision.EvidenceIncomplete)
            assertEquals("SUCCESSOR_EXCLUSION_UNVERIFIED", (decision as PureEffectDecision.EvidenceIncomplete).missingWitness)
        }
    }

    @Test
    fun red08_workerSharedUniqueNameUnprovenIdentity() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            includeFollowUpWorkers = true
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val hueDecision = report.decisions[SideEffectTarget.HUE_SUNRISE_WORKER]
        assertTrue(hueDecision is PureEffectDecision.CandidateForFutureVerification)
        val candidate = hueDecision as PureEffectDecision.CandidateForFutureVerification
        assertFalse("Shared UniqueName cannot claim PROVEN_WORKER_OCCURRENCE_IDENTITY", candidate.verifiedWitnesses.contains("PROVEN_WORKER_OCCURRENCE_IDENTITY"))
    }

    @Test
    fun red09_issuedOccurrenceTokenUnverified() {
        val callerWithoutToken = createQualifiedCaller().copy(instanceToken = null)
        val fenceWithoutToken = createAcceptedFence(createQualifiedCaller()).copy(expectedInstanceToken = null)
        val plan = createAcceptedPlan(createAcceptedFence(createQualifiedCaller()))

        val context = BoundaryEvaluationContext(
            callerIdentity = callerWithoutToken,
            cancellationFence = fenceWithoutToken,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val tokenDecision = report.decisions[SideEffectTarget.ISSUED_OCCURRENCE_TOKEN]
        assertTrue("Unverified instanceToken must return EvidenceIncomplete", tokenDecision is PureEffectDecision.EvidenceIncomplete)
        assertEquals("INSTANCE_TOKEN_MISSING", (tokenDecision as PureEffectDecision.EvidenceIncomplete).missingWitness)
    }

    @Test
    fun red10_legacyRed04UnresolvedFalseAloneDoesNotPromoteToCandidate() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        // isLegacyRed04Unresolved = false, BUT osCancelProof = UNPROVEN_IDENTITY
        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            isLegacyRed04Unresolved = false,
            osCancelProof = OsCancelProofStatus.UNPROVEN_IDENTITY
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val osDecision = report.decisions[SideEffectTarget.MAIN_OS_PENDING_INTENT]
        assertTrue("UNPROVEN_IDENTITY must NOT be promoted to Candidate even if red04 is false", osDecision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.TARGET_WITNESS_MISMATCH, (osDecision as PureEffectDecision.Blocked).reason)
    }

    @Test
    fun red11_includeFollowUpWorkersFalseReasonCode() {
        val caller = createQualifiedCaller()
        val fence = createAcceptedFence(caller)
        val plan = createAcceptedPlan(fence)

        val context = BoundaryEvaluationContext(
            callerIdentity = caller,
            cancellationFence = fence,
            plannerOutcome = plan,
            successorExclusionStatus = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
            includeFollowUpWorkers = false
        )

        val report = CancellationExecutionBoundary.evaluate(context)

        val guardianDecision = report.decisions[SideEffectTarget.GUARDIAN_WORKER]
        assertTrue(guardianDecision is PureEffectDecision.Blocked)
        assertEquals(CancellationRejectionReason.AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH, (guardianDecision as PureEffectDecision.Blocked).reason)
    }
}
