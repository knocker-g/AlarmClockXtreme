package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlin.reflect.KVisibility
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.full.memberProperties
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * KNO-75 C7-R2-S1-B1 Contract Test Suite (B1-01 through B1-12).
 *
 * Proves pure caller-origin identity capture, validation, and propagation into S1-A
 * [CancellationFence] / [FencedCancellationPlanner] without synthesizing newer authority,
 * mutating Room, or executing OS/DirectBoot/WorkManager cancellation side effects.
 */
class CallerOriginFenceConverterTest {

    private val alarmId = 42L
    private val triggerA = 1_800_000_000_000L
    private val triggerB = 1_800_003_600_000L
    private val capturedAt = 1_799_999_900_000L

    private fun normalAlarm(
        id: Long = alarmId,
        kind: String = Alarm.KIND_NORMAL,
        isEnabled: Boolean = true,
        generation: Int = 1,
        revision: Int = 1,
        nextTriggerTime: Long = triggerA
    ): Alarm = Alarm(
        id = id,
        hour = 7,
        minute = 30,
        isEnabled = isEnabled,
        kind = kind,
        generation = generation,
        revision = revision,
        nextTriggerTime = nextTriggerTime
    )

    private fun authorityRow(
        id: Long = alarmId,
        instanceToken: String = "inst-A",
        definitionRevision: Long = 101L,
        scheduleRevision: Long = 2L,
        lifecycle: String = "LIVE",
        desiredOperation: String = "SET",
        desiredTriggerMillis: Long? = triggerA,
        restoreEpoch: Long = 10L
    ): ScheduleAuthority = ScheduleAuthority(
        alarmId = id,
        instanceToken = instanceToken,
        definitionRevision = definitionRevision,
        scheduleRevision = scheduleRevision,
        lifecycle = lifecycle,
        desiredOperation = desiredOperation,
        desiredTriggerMillis = desiredTriggerMillis,
        reconcileState = "SETTLED",
        restoreEpoch = restoreEpoch,
        actionKey = null
    )

    private fun quickAlarm(
        id: Long = 99L,
        triggerEpochMs: Long = triggerA,
        relativeElapsedMs: Long = 900_000L,
        bootCount: Long = 5L
    ): Alarm = Alarm(
        id = id,
        hour = 8,
        minute = 0,
        isEnabled = true,
        kind = Alarm.KIND_QUICK,
        quickTriggerEpochMs = triggerEpochMs,
        quickRelativeElapsedMs = relativeElapsedMs,
        quickBootCount = bootCount,
        nextTriggerTime = triggerEpochMs
    )

    private fun normalContext(
        alarm: Alarm = normalAlarm(),
        authority: ScheduleAuthority? = authorityRow(),
        authorityRequired: Boolean = true,
        fireId: String? = "fire-${alarm.id}-A"
    ): CancellationEvaluationContext = CancellationEvaluationContext(
        currentAlarm = FencedAlarmSnapshot.fromAlarm(alarm),
        currentAuthority = authority?.let { FencedAuthoritySnapshot.fromAuthority(it) },
        authorityRequired = authorityRequired,
        expectedFireIdForCurrentTrigger = fireId
    )

    @Test
    fun b1_01_completeCallerOriginIdentity_producesExpectedFenceAndPlannedCandidate() {
        val origin = CallerOriginIdentity.captureNormalExact(
            alarm = normalAlarm(),
            authority = authorityRow(),
            capturedAtMillis = capturedAt,
            targetFireId = "fire-42-A",
            sourceTag = "TEST_B1_01"
        )

        val candidate = CallerOriginFenceConverter.toFenceCandidate(origin)
        assertTrue(candidate is CallerOriginPropagationResult.QualifiedCandidate)
        val qualified = candidate as CallerOriginPropagationResult.QualifiedCandidate
        assertTrue(qualified.isQualifiedCandidate)
        assertFalse(qualified.isPlannerAccepted)
        assertFalse(qualified.allowsDestructiveCleanup)
        assertFalse(qualified.isOsCancelCompleted)
        assertFalse(qualified.executesCancellation)

        val expectedFence = CancellationFence.ExactOccurrence(
            alarmId = alarmId,
            kind = Alarm.KIND_NORMAL,
            expectedGeneration = 1,
            expectedRevision = 1,
            expectedTriggerTime = triggerA,
            expectedScheduleRevision = 2L,
            expectedDefinitionRevision = 101L,
            expectedInstanceToken = "inst-A",
            expectedRestoreEpoch = 10L,
            targetScheduledAt = triggerA,
            targetFireId = "fire-42-A"
        )
        assertEquals(expectedFence, qualified.fence)

        val planned = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = origin,
            context = normalContext(),
            includeFollowUpWorkers = true
        )
        assertTrue(planned is CallerOriginPropagationResult.PlannedCandidate)
        val plannedCandidate = planned as CallerOriginPropagationResult.PlannedCandidate
        assertEquals(expectedFence, plannedCandidate.fence)
        assertTrue(plannedCandidate.isPlannerAccepted)
        assertFalse(plannedCandidate.allowsDestructiveCleanup)
        assertFalse(plannedCandidate.isOsCancelCompleted)
        assertFalse(plannedCandidate.executesCancellation)
        assertFalse(plannedCandidate.outcome.allowsDestructiveCleanup)
        assertFalse(plannedCandidate.outcome.isOsCancelCompleted)
    }

    @Test
    fun b1_02_newerGenerationBExists_preservesOriginAIdentityAndRejectsStale() {
        val originA = CallerOriginIdentity.captureNormalExact(
            alarm = normalAlarm(generation = 1, revision = 1, nextTriggerTime = triggerA),
            authority = authorityRow(instanceToken = "inst-A", scheduleRevision = 2L, definitionRevision = 101L),
            capturedAtMillis = capturedAt,
            targetFireId = "fire-42-A"
        )
        val newerAlarmB = normalAlarm(generation = 2, revision = 1, nextTriggerTime = triggerB)
        val newerAuthorityB = authorityRow(
            instanceToken = "inst-B",
            scheduleRevision = 3L,
            definitionRevision = 202L,
            desiredTriggerMillis = triggerB
        )

        val candidate = CallerOriginFenceConverter.toFenceCandidate(originA)
        assertTrue(candidate is CallerOriginPropagationResult.QualifiedCandidate)
        val exactFence = (candidate as CallerOriginPropagationResult.QualifiedCandidate).fence as CancellationFence.ExactOccurrence
        assertEquals(1, exactFence.expectedGeneration)
        assertEquals(triggerA, exactFence.expectedTriggerTime)
        assertEquals("inst-A", exactFence.expectedInstanceToken)

        val result = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = originA,
            context = normalContext(alarm = newerAlarmB, authority = newerAuthorityB, fireId = "fire-42-B")
        )
        assertTrue(result is CallerOriginPropagationResult.Rejected)
        val rejected = result as CallerOriginPropagationResult.Rejected
        assertFalse(rejected.isQualifiedCandidate)
        assertFalse(rejected.isPlannerAccepted)
        assertFalse(rejected.allowsDestructiveCleanup)
        assertEquals(1, rejected.originIdentity.generation)
        assertEquals(triggerA, rejected.originIdentity.scheduledTriggerMillis)
        assertEquals("inst-A", rejected.originIdentity.instanceToken)
        assertEquals(1, (rejected.fence as CancellationFence.ExactOccurrence).expectedGeneration)
        assertTrue(rejected.outcome is CancelOutcome.StaleRejected || rejected.outcome is CancelOutcome.IdentityMismatch)
    }

    @Test
    fun b1_03_sameAlarmIdAndSameTrigger_distinguishesDifferentTokens() {
        val originTokenA = CallerOriginIdentity.captureNormalExact(
            alarm = normalAlarm(nextTriggerTime = triggerA),
            authority = authorityRow(instanceToken = "inst-A", desiredTriggerMillis = triggerA),
            capturedAtMillis = capturedAt
        )
        val originTokenB = CallerOriginIdentity.captureNormalExact(
            alarm = normalAlarm(nextTriggerTime = triggerA),
            authority = authorityRow(instanceToken = "inst-B", desiredTriggerMillis = triggerA),
            capturedAtMillis = capturedAt
        )

        val candA = CallerOriginFenceConverter.toFenceCandidate(originTokenA) as CallerOriginPropagationResult.QualifiedCandidate
        val candB = CallerOriginFenceConverter.toFenceCandidate(originTokenB) as CallerOriginPropagationResult.QualifiedCandidate
        assertNotEquals(candA.fence, candB.fence)

        val crossEval = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = originTokenA,
            context = normalContext(
                alarm = normalAlarm(nextTriggerTime = triggerA),
                authority = authorityRow(instanceToken = "inst-B", desiredTriggerMillis = triggerA)
            )
        )
        assertTrue(crossEval is CallerOriginPropagationResult.Rejected)
        val rejected = crossEval as CallerOriginPropagationResult.Rejected
        assertEquals(CancellationRejectionReason.INSTANCE_TOKEN_MISMATCH, rejected.reason)
        assertFalse(rejected.isQualifiedCandidate)
        assertFalse(rejected.allowsDestructiveCleanup)
    }

    @Test
    fun b1_04_missingRequiredLineage_failsClosedAsUnqualified() {
        val missingAuthorityOrigin = CallerOriginIdentity.captureNormalExact(
            alarm = normalAlarm(),
            authority = null,
            capturedAtMillis = capturedAt
        )
        val resMissingAuth = CallerOriginFenceConverter.toFenceCandidate(missingAuthorityOrigin)
        assertTrue(resMissingAuth is CallerOriginPropagationResult.Unqualified)
        val unqAuth = resMissingAuth as CallerOriginPropagationResult.Unqualified
        assertEquals(CallerOriginUnqualifiedReason.MISSING_AUTHORITY_LINEAGE, unqAuth.unqualifiedReason)
        assertTrue(unqAuth.missingFields.containsAll(listOf("instanceToken", "restoreEpoch", "scheduleAuthorityRevision", "definitionRevision")))
        assertFalse(unqAuth.isQualifiedCandidate)
        assertFalse(unqAuth.allowsDestructiveCleanup)

        val missingGenRev = missingAuthorityOrigin.copy(
            generation = null,
            revision = null,
            instanceToken = "inst-A",
            restoreEpoch = 10L,
            scheduleAuthorityRevision = 2L,
            definitionRevision = 101L
        )
        val resMissingGen = CallerOriginFenceConverter.toFenceCandidate(missingGenRev)
        assertTrue(resMissingGen is CallerOriginPropagationResult.Unqualified)
        assertEquals(
            CallerOriginUnqualifiedReason.MISSING_GENERATION_OR_REVISION,
            (resMissingGen as CallerOriginPropagationResult.Unqualified).unqualifiedReason
        )

        val missingMutationOrder = CallerOriginIdentity(
            alarmId = alarmId,
            alarmKind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            scheduledTriggerMillis = triggerA,
            scheduleAuthorityRevision = 2L,
            definitionRevision = 101L,
            instanceToken = "inst-A",
            restoreEpoch = 10L,
            operationKind = CallerOperationKind.DELETE,
            mutationOrder = null,
            capturedAtMillis = capturedAt
        )
        val resMissingOrder = CallerOriginFenceConverter.toFenceCandidate(missingMutationOrder)
        assertTrue(resMissingOrder is CallerOriginPropagationResult.Unqualified)
        assertEquals(
            CallerOriginUnqualifiedReason.MISSING_MUTATION_ORDER,
            (resMissingOrder as CallerOriginPropagationResult.Unqualified).unqualifiedReason
        )
    }

    @Test
    fun b1_05_forbidsSynthesizingMissingIdentityFromLatestRoomAuthority() {
        val incompleteOriginA = CallerOriginIdentity(
            alarmId = alarmId,
            alarmKind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            scheduledTriggerMillis = triggerA,
            scheduleAuthorityRevision = null,
            definitionRevision = null,
            instanceToken = null,
            restoreEpoch = null,
            operationKind = CallerOperationKind.EXACT_OCCURRENCE,
            capturedAtMillis = capturedAt
        )
        val latestAuthorityBContext = normalContext(
            alarm = normalAlarm(generation = 3, revision = 5, nextTriggerTime = triggerB),
            authority = authorityRow(
                instanceToken = "inst-B-latest",
                definitionRevision = 999L,
                scheduleRevision = 15L,
                restoreEpoch = 20L,
                desiredTriggerMillis = triggerB
            ),
            authorityRequired = false
        )

        val result = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = incompleteOriginA,
            context = latestAuthorityBContext
        )

        assertTrue(result is CallerOriginPropagationResult.Unqualified)
        val unqualified = result as CallerOriginPropagationResult.Unqualified
        assertEquals(CallerOriginUnqualifiedReason.MISSING_AUTHORITY_LINEAGE, unqualified.unqualifiedReason)
        assertNull(unqualified.originIdentity.instanceToken)
        assertNull(unqualified.originIdentity.restoreEpoch)
        assertNull(unqualified.originIdentity.scheduleAuthorityRevision)
        assertNull(unqualified.originIdentity.definitionRevision)
        assertFalse(unqualified.isQualifiedCandidate)
        assertFalse(unqualified.isPlannerAccepted)
        assertFalse(unqualified.allowsDestructiveCleanup)
    }

    @Test
    fun b1_06_deleteAndDisable_preserveMutationOrderThroughFenceAndPlan() {
        val beforeCancelDisable = CallerOriginIdentity.captureNormalDefinition(
            alarm = normalAlarm(isEnabled = false),
            authority = authorityRow(lifecycle = "DISABLED", desiredOperation = "CANCEL", desiredTriggerMillis = null),
            actionKind = DefinitionActionKind.DISABLE,
            mutationOrder = RoomMutationOrder.BEFORE_CANCEL,
            capturedAtMillis = capturedAt
        )
        val afterCancelDelete = CallerOriginIdentity.captureNormalDefinition(
            alarm = normalAlarm(isEnabled = true),
            authority = authorityRow(lifecycle = "LIVE", desiredOperation = "SET"),
            actionKind = DefinitionActionKind.DELETE,
            mutationOrder = RoomMutationOrder.AFTER_CANCEL,
            capturedAtMillis = capturedAt
        )

        val beforeRes = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = beforeCancelDisable,
            context = normalContext(
                alarm = normalAlarm(isEnabled = false),
                authority = authorityRow(lifecycle = "DISABLED", desiredOperation = "CANCEL", desiredTriggerMillis = null)
            )
        ) as CallerOriginPropagationResult.PlannedCandidate
        val beforeFence = beforeRes.fence as CancellationFence.DefinitionDeleteOrDisable
        assertEquals(DefinitionActionKind.DISABLE, beforeFence.actionKind)
        assertEquals(RoomMutationOrder.BEFORE_CANCEL, beforeFence.roomMutationOrder)
        assertFalse(beforeRes.outcome.plan.callerRoomMutationPermittedAfterOsCancel)

        val afterRes = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = afterCancelDelete,
            context = normalContext(
                alarm = normalAlarm(isEnabled = true),
                authority = authorityRow(lifecycle = "LIVE", desiredOperation = "SET")
            )
        ) as CallerOriginPropagationResult.PlannedCandidate
        val afterFence = afterRes.fence as CancellationFence.DefinitionDeleteOrDisable
        assertEquals(DefinitionActionKind.DELETE, afterFence.actionKind)
        assertEquals(RoomMutationOrder.AFTER_CANCEL, afterFence.roomMutationOrder)
        assertTrue(afterRes.outcome.plan.callerRoomMutationPermittedAfterOsCancel)

        // Post-mutation missing row under AFTER_CANCEL must not become Accepted
        val absentAfterDelete = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = afterCancelDelete,
            context = CancellationEvaluationContext(currentAlarm = null, currentAuthority = null)
        )
        assertTrue(absentAfterDelete is CallerOriginPropagationResult.Deferred)
        assertFalse(absentAfterDelete.isPlannerAccepted)
        assertFalse(absentAfterDelete.allowsDestructiveCleanup)
    }

    @Test
    fun b1_07_replaceBeforeSave_neverTargetsNewOccurrenceAfterReplace() {
        val oldOccurrenceAOrigin = CallerOriginIdentity.captureNormalDefinition(
            alarm = normalAlarm(generation = 1, revision = 1, nextTriggerTime = triggerA),
            authority = authorityRow(instanceToken = "inst-A", definitionRevision = 101L, scheduleRevision = 2L),
            actionKind = DefinitionActionKind.REPLACE_BEFORE_SAVE,
            mutationOrder = RoomMutationOrder.BEFORE_CANCEL,
            capturedAtMillis = capturedAt
        )
        // New occurrence B committed to Room after replace
        val postSaveAlarmB = normalAlarm(generation = 2, revision = 1, nextTriggerTime = triggerB)
        val postSaveAuthorityB = authorityRow(
            instanceToken = "inst-A",
            definitionRevision = 102L,
            scheduleRevision = 3L,
            lifecycle = "LIVE",
            desiredOperation = "SET",
            desiredTriggerMillis = triggerB
        )

        val result = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = oldOccurrenceAOrigin,
            context = normalContext(alarm = postSaveAlarmB, authority = postSaveAuthorityB)
        )

        assertTrue(result is CallerOriginPropagationResult.Rejected)
        val rejected = result as CallerOriginPropagationResult.Rejected
        assertFalse(rejected.isQualifiedCandidate)
        assertFalse(rejected.isPlannerAccepted)
        assertFalse(rejected.allowsDestructiveCleanup)
        val fence = rejected.fence as CancellationFence.DefinitionDeleteOrDisable
        assertEquals(DefinitionActionKind.REPLACE_BEFORE_SAVE, fence.actionKind)
        assertEquals(1, fence.expectedGeneration)
        assertEquals(triggerA, fence.expectedTriggerTime)
    }

    @Test
    fun b1_08_separatesNormalAndQuickIdentitiesStrictly() {
        val quickOrigin = CallerOriginIdentity.captureQuick(quickAlarm(), capturedAtMillis = capturedAt)
        val quickCand = CallerOriginFenceConverter.toFenceCandidate(quickOrigin)
        assertTrue(quickCand is CallerOriginPropagationResult.QualifiedCandidate)
        assertTrue((quickCand as CallerOriginPropagationResult.QualifiedCandidate).fence is CancellationFence.QuickOccurrence)

        // Quick origin evaluated against Normal alarm snapshot must fail closed with KIND_MISMATCH
        val quickAgainstNormal = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = quickOrigin,
            context = normalContext(alarm = normalAlarm(id = 99L, kind = Alarm.KIND_NORMAL), authority = null, authorityRequired = false)
        )
        assertTrue(quickAgainstNormal is CallerOriginPropagationResult.Rejected)
        assertEquals(CancellationRejectionReason.KIND_MISMATCH, (quickAgainstNormal as CallerOriginPropagationResult.Rejected).reason)

        // Normal operation with KIND_QUICK must be rejected before fence creation
        val mismatchedNormalOpOnQuick = CallerOriginIdentity.captureNormalExact(
            alarm = quickAlarm(),
            authority = authorityRow(id = 99L)
        )
        val res1 = CallerOriginFenceConverter.toFenceCandidate(mismatchedNormalOpOnQuick)
        assertTrue(res1 is CallerOriginPropagationResult.Rejected)
        assertEquals(CancellationRejectionReason.KIND_MISMATCH, (res1 as CallerOriginPropagationResult.Rejected).reason)

        // Quick operation with KIND_NORMAL must be rejected before fence creation
        val mismatchedQuickOpOnNormal = CallerOriginIdentity.captureQuick(normalAlarm())
        val res2 = CallerOriginFenceConverter.toFenceCandidate(mismatchedQuickOpOnNormal)
        assertTrue(res2 is CallerOriginPropagationResult.Rejected)
        assertEquals(CancellationRejectionReason.KIND_MISMATCH, (res2 as CallerOriginPropagationResult.Rejected).reason)
    }

    @Test
    fun b1_09_conversionAndEvaluation_areDeterministicAndIdempotent() {
        val origin = CallerOriginIdentity.captureNormalExact(
            alarm = normalAlarm(),
            authority = authorityRow(),
            capturedAtMillis = capturedAt,
            targetFireId = "fire-42-A"
        )
        val ctx = normalContext()

        val cand1 = CallerOriginFenceConverter.toFenceCandidate(origin)
        val cand2 = CallerOriginFenceConverter.toFenceCandidate(origin)
        assertEquals(cand1, cand2)

        val eval1 = CallerOriginFenceConverter.evaluateWithPlanner(origin, ctx)
        val eval2 = CallerOriginFenceConverter.evaluateWithPlanner(origin, ctx)
        assertEquals(eval1, eval2)
    }

    @Test
    fun b1_10_pureConversion_hasZeroOsDirectBootRoomOrWorkerDependencies() {
        val identityForbiddenDeps = setOf("android.content.Context", "AlarmManager", "DirectBootAlarmCache", "AlarmRepository", "WorkManager", "AlarmDatabase")
        val originProps = CallerOriginIdentity::class.memberProperties.map { it.returnType.toString() }
        identityForbiddenDeps.forEach { forbidden ->
            assertFalse("CallerOriginIdentity must not reference $forbidden", originProps.any { it.contains(forbidden) })
        }

        val converterParams = CallerOriginFenceConverter::class.declaredFunctions.flatMap { fn ->
            fn.parameters.map { it.type.toString() }
        }
        identityForbiddenDeps.forEach { forbidden ->
            assertFalse("CallerOriginFenceConverter must not accept $forbidden", converterParams.any { it.contains(forbidden) })
        }

        assertTrue(CallerOriginIdentity::class.memberProperties.all { it.visibility == KVisibility.PUBLIC && it !is kotlin.reflect.KMutableProperty<*> })

        val origin = CallerOriginIdentity.captureNormalExact(normalAlarm(), authorityRow(), capturedAtMillis = capturedAt)
        val candidate = CallerOriginFenceConverter.toFenceCandidate(origin)
        assertFalse(candidate.executesCancellation)
        assertFalse(candidate.isOsCancelCompleted)
        assertFalse(candidate.allowsDestructiveCleanup)
    }

    @Test
    fun b1_11_cancelOutcomeAllowsDestructiveCleanup_remainsFalseAcrossAllResults() {
        val origin = CallerOriginIdentity.captureNormalExact(
            alarm = normalAlarm(),
            authority = authorityRow(),
            capturedAtMillis = capturedAt,
            targetFireId = "fire-42-A"
        )
        val planned = CallerOriginFenceConverter.evaluateWithPlanner(origin, normalContext())
            as CallerOriginPropagationResult.PlannedCandidate
        assertFalse(planned.allowsDestructiveCleanup)
        assertFalse(planned.outcome.allowsDestructiveCleanup)
        assertFalse(planned.outcome.isOsCancelCompleted)
        assertFalse(planned.isOsCancelCompleted)
        assertFalse(planned.executesCancellation)

        val stale = CallerOriginFenceConverter.evaluateWithPlanner(
            origin = origin,
            context = normalContext(alarm = normalAlarm(generation = 2))
        )
        assertFalse(stale.allowsDestructiveCleanup)
        assertFalse(stale.isOsCancelCompleted)
        assertFalse(stale.executesCancellation)
    }

    @Test
    fun b1_12_unqualifiedCallers_neverUnconditionallyPromoteToAccepted() {
        val alarmIdOnly = CallerOriginIdentity.unqualifiedAlarmIdOnly(alarmId, sourceTag = "AlarmListViewModel.deleteSelected")
        val legacyPartialGenOnly = CallerOriginIdentity.fromLegacyCancelArgs(
            alarmId = alarmId,
            expectedGeneration = 1,
            expectedRevision = null,
            sourceTag = "HolidayReconcileCoordinator"
        )
        val permissiveContext = normalContext(
            alarm = normalAlarm(),
            authority = null,
            authorityRequired = false
        )

        val resIdOnly = CallerOriginFenceConverter.evaluateWithPlanner(alarmIdOnly, permissiveContext)
        assertTrue(resIdOnly is CallerOriginPropagationResult.Unqualified)
        assertEquals(
            CallerOriginUnqualifiedReason.LEGACY_ALARM_ID_ONLY_CALLER,
            (resIdOnly as CallerOriginPropagationResult.Unqualified).unqualifiedReason
        )
        assertFalse(resIdOnly.isQualifiedCandidate)
        assertFalse(resIdOnly.isPlannerAccepted)
        assertFalse(resIdOnly.allowsDestructiveCleanup)

        val resPartialGen = CallerOriginFenceConverter.evaluateWithPlanner(legacyPartialGenOnly, permissiveContext)
        assertTrue(resPartialGen is CallerOriginPropagationResult.Unqualified)
        assertFalse(resPartialGen.isQualifiedCandidate)
        assertFalse(resPartialGen.isPlannerAccepted)
        assertFalse(resPartialGen.allowsDestructiveCleanup)
    }

    @Test
    fun b1_13_mismatchedAlarmAndAuthorityIds_refusesAuthorityCaptureAndFailsClosedAsUnqualified() {
        val alarm = normalAlarm(id = 42L)
        val mismatchedAuthority = authorityRow(id = 99L, instanceToken = "inst-99")

        val origin = CallerOriginIdentity.captureNormalExact(
            alarm = alarm,
            authority = mismatchedAuthority,
            capturedAtMillis = capturedAt,
            targetFireId = "fire-42-A"
        )

        assertNull(origin.instanceToken)
        assertNull(origin.restoreEpoch)
        assertNull(origin.scheduleAuthorityRevision)
        assertNull(origin.definitionRevision)

        val candidate = CallerOriginFenceConverter.toFenceCandidate(origin)
        assertTrue(candidate is CallerOriginPropagationResult.Unqualified)
        val unqualified = candidate as CallerOriginPropagationResult.Unqualified
        assertEquals(CallerOriginUnqualifiedReason.MISSING_AUTHORITY_LINEAGE, unqualified.unqualifiedReason)
        assertFalse(unqualified.isQualifiedCandidate)
        assertFalse(unqualified.allowsDestructiveCleanup)

        val evalResult = CallerOriginFenceConverter.evaluateWithPlanner(origin, normalContext(alarm = alarm))
        assertTrue(evalResult is CallerOriginPropagationResult.Unqualified)
        assertFalse(evalResult.isQualifiedCandidate)
        assertFalse(evalResult.isPlannerAccepted)
        assertFalse(evalResult.allowsDestructiveCleanup)
    }

    @Test
    fun b1_14_mismatchedDefinitionAlarmAndAuthorityIds_refusesAuthorityCaptureAndFailsClosedAsUnqualified() {
        val alarm = normalAlarm(id = 42L)
        val mismatchedAuthority = authorityRow(id = 99L, instanceToken = "inst-99")

        val origin = CallerOriginIdentity.captureNormalDefinition(
            alarm = alarm,
            authority = mismatchedAuthority,
            actionKind = DefinitionActionKind.DELETE,
            mutationOrder = RoomMutationOrder.AFTER_CANCEL,
            capturedAtMillis = capturedAt
        )

        assertNull(origin.instanceToken)
        assertNull(origin.restoreEpoch)
        assertNull(origin.scheduleAuthorityRevision)
        assertNull(origin.definitionRevision)

        val candidate = CallerOriginFenceConverter.toFenceCandidate(origin)
        assertTrue(candidate is CallerOriginPropagationResult.Unqualified)
        val unqualified = candidate as CallerOriginPropagationResult.Unqualified
        assertEquals(CallerOriginUnqualifiedReason.MISSING_AUTHORITY_LINEAGE, unqualified.unqualifiedReason)
        assertFalse(unqualified.isQualifiedCandidate)
        assertFalse(unqualified.allowsDestructiveCleanup)
    }
}
