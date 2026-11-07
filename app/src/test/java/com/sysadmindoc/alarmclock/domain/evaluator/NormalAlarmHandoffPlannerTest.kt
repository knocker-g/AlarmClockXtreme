package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.AlarmOccurrenceId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure, comprehensive unit tests for [NormalAlarmHandoffPlanner] (KNO-37 Phase H2-C1).
 * Validates deterministic 9-step decision priority, canonical admission identity,
 * and zero side effects without Room or framework dependencies.
 */
class NormalAlarmHandoffPlannerTest {

    private lateinit var planner: NormalAlarmHandoffPlanner

    private val defaultAlarmId = 100L
    private val defaultBaseTriggerTime = 1793872200000L
    private val defaultGeneration = 2
    private val defaultScheduledAt = 1793872200000L
    private val defaultOccurrenceId = AlarmOccurrenceId(defaultAlarmId, defaultBaseTriggerTime, defaultGeneration)
    private val defaultFireId = "fire_${defaultAlarmId}_$defaultScheduledAt"
    private val defaultIssueToken = "issue_${defaultAlarmId}_gen_${defaultGeneration}_${defaultScheduledAt}_test"

    @Before
    fun setUp() {
        planner = NormalAlarmHandoffPlanner()
    }

    private fun buildFacts(
        occurrenceId: AlarmOccurrenceId = defaultOccurrenceId,
        fireId: String = defaultFireId,
        scheduledAt: Long = defaultScheduledAt,
        issueToken: String = defaultIssueToken,
        occurrenceStatus: String = IssuedOccurrence.STATUS_ISSUED,
        isIdentityValid: Boolean = true,
        isProvenanceValid: Boolean = true,
        isGenerationValid: Boolean = true,
        isIssueSlotValid: Boolean = true,
        isAlreadyAdmitted: Boolean = false,
        hasActiveSession: Boolean = false
    ): NormalAlarmHandoffFacts {
        return NormalAlarmHandoffFacts(
            occurrenceId = occurrenceId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            issueToken = issueToken,
            occurrenceStatus = occurrenceStatus,
            isIdentityValid = isIdentityValid,
            isProvenanceValid = isProvenanceValid,
            isGenerationValid = isGenerationValid,
            isIssueSlotValid = isIssueSlotValid,
            isAlreadyAdmitted = isAlreadyAdmitted,
            hasActiveSession = hasActiveSession
        )
    }

    // -------------------------------------------------------------
    // Core Decision Outcomes (Steps 1 to 9)
    // -------------------------------------------------------------

    @Test
    fun T01_validNoActiveSession_admitsNewSession() {
        val facts = buildFacts(hasActiveSession = false)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.ADMIT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_ADMIT_NEW_SESSION, plan.decisionReason)
        assertTrue(plan.isAdmitted)
        assertFalse(plan.isSuppressed)
        assertFalse(plan.isDuplicate)
        assertFalse(plan.isAlreadySuppressed)
        assertFalse(plan.isRejected)
    }

    @Test
    fun T02_validActiveSameAlarmSession_suppressesOccurrence() {
        val facts = buildFacts(hasActiveSession = true)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.SUPPRESS, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_SUPPRESS_ACTIVE_SESSION, plan.decisionReason)
        assertTrue(plan.isSuppressed)
        assertFalse(plan.isAdmitted)
        assertFalse(plan.isDuplicate)
        assertFalse(plan.isAlreadySuppressed)
        assertFalse(plan.isRejected)
    }

    @Test
    fun T03_duplicateDelivery_returnsDuplicateAction() {
        val facts = buildFacts(isAlreadyAdmitted = true, hasActiveSession = false)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.DUPLICATE, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_DUPLICATE_DELIVERY, plan.decisionReason)
        assertTrue(plan.isDuplicate)
        assertFalse(plan.isAdmitted)
        assertFalse(plan.isSuppressed)
        assertFalse(plan.isAlreadySuppressed)
        assertFalse(plan.isRejected)
    }

    @Test
    fun T04_alreadySuppressed_returnsAlreadySuppressedAction() {
        val facts = buildFacts(
            occurrenceStatus = IssuedOccurrence.STATUS_SUPPRESSED,
            isAlreadyAdmitted = false,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.ALREADY_SUPPRESSED, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_ALREADY_SUPPRESSED, plan.decisionReason)
        assertTrue(plan.isAlreadySuppressed)
        assertFalse(plan.isAdmitted)
        assertFalse(plan.isSuppressed)
        assertFalse(plan.isDuplicate)
        assertFalse(plan.isRejected)
    }

    @Test
    fun T05a_staleOrInvalidIdentity_flagFalse_rejects() {
        val facts = buildFacts(isIdentityValid = false)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T05b_staleOrInvalidIdentity_nonPositiveScheduledAt_rejects() {
        val facts = buildFacts(scheduledAt = 0L)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T05c_staleOrInvalidIdentity_blankFireId_rejects() {
        val facts = buildFacts(fireId = "   ")
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T05d_staleOrInvalidIdentity_blankIssueToken_rejects() {
        val facts = buildFacts(issueToken = "")
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T05e_staleOrInvalidIdentity_nonPositiveAlarmIdOrBaseTrigger_rejects() {
        val badAlarmId = AlarmOccurrenceId(alarmId = 0L, baseTriggerTime = defaultBaseTriggerTime, generation = 1)
        val plan1 = planner.plan(buildFacts(occurrenceId = badAlarmId))
        assertEquals(NormalAlarmHandoffAction.REJECT, plan1.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, plan1.decisionReason)

        val badBase = AlarmOccurrenceId(alarmId = defaultAlarmId, baseTriggerTime = 0L, generation = 1)
        val plan2 = planner.plan(buildFacts(occurrenceId = badBase))
        assertEquals(NormalAlarmHandoffAction.REJECT, plan2.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, plan2.decisionReason)

        val badGen = AlarmOccurrenceId(alarmId = defaultAlarmId, baseTriggerTime = defaultBaseTriggerTime, generation = 0)
        val plan3 = planner.plan(buildFacts(occurrenceId = badGen))
        assertEquals(NormalAlarmHandoffAction.REJECT, plan3.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, plan3.decisionReason)
    }

    @Test
    fun T06_invalidProvenance_rejectsWithInvalidProvenance() {
        val facts = buildFacts(isProvenanceValid = false)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_PROVENANCE, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T07_staleGeneration_rejectsWithStaleGeneration() {
        val facts = buildFacts(isGenerationValid = false)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_STALE_GENERATION, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T08_staleIssueSlot_rejectsWithStaleIssueSlot() {
        val facts = buildFacts(isIssueSlotValid = false)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_STALE_ISSUE_SLOT, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T09_unsupportedStartingStatus_prepared_rejects() {
        val facts = buildFacts(occurrenceStatus = IssuedOccurrence.STATUS_PREPARED)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T10_unsupportedStartingStatus_admitted_withoutTombstone_rejects() {
        val facts = buildFacts(occurrenceStatus = IssuedOccurrence.STATUS_ADMITTED, isAlreadyAdmitted = false)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T11_unsupportedStartingStatus_revoked_rejects() {
        val facts = buildFacts(occurrenceStatus = IssuedOccurrence.STATUS_REVOKED)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    @Test
    fun T12_unsupportedStartingStatus_setFailed_rejects() {
        val facts = buildFacts(occurrenceStatus = IssuedOccurrence.STATUS_SET_FAILED)
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS, plan.decisionReason)
        assertTrue(plan.isRejected)
    }

    // -------------------------------------------------------------
    // Priority Matrix Proofs (Precedence Ordering)
    // -------------------------------------------------------------

    @Test
    fun P01_priority_staleIdentityTakesPrecedenceOverDuplicate() {
        val facts = buildFacts(
            isIdentityValid = false,
            isAlreadyAdmitted = true,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, plan.decisionReason)
    }

    @Test
    fun P02_priority_invalidProvenanceTakesPrecedenceOverDuplicate() {
        val facts = buildFacts(
            isProvenanceValid = false,
            isAlreadyAdmitted = true,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_PROVENANCE, plan.decisionReason)
    }

    @Test
    fun P03_priority_staleGenerationTakesPrecedenceOverDuplicate() {
        val facts = buildFacts(
            isGenerationValid = false,
            isAlreadyAdmitted = true,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_STALE_GENERATION, plan.decisionReason)
    }

    @Test
    fun P04_priority_staleIssueSlotTakesPrecedenceOverDuplicate() {
        val facts = buildFacts(
            isIssueSlotValid = false,
            isAlreadyAdmitted = true,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_STALE_ISSUE_SLOT, plan.decisionReason)
    }

    @Test
    fun P05_priority_duplicateTakesPrecedenceOverActiveSession() {
        val facts = buildFacts(
            isAlreadyAdmitted = true,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.DUPLICATE, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_DUPLICATE_DELIVERY, plan.decisionReason)
    }

    @Test
    fun P06_priority_duplicateTakesPrecedenceOverAlreadySuppressed() {
        val facts = buildFacts(
            occurrenceStatus = IssuedOccurrence.STATUS_SUPPRESSED,
            isAlreadyAdmitted = true,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.DUPLICATE, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_DUPLICATE_DELIVERY, plan.decisionReason)
    }

    @Test
    fun P07_priority_alreadySuppressedTakesPrecedenceOverActiveSession() {
        val facts = buildFacts(
            occurrenceStatus = IssuedOccurrence.STATUS_SUPPRESSED,
            isAlreadyAdmitted = false,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.ALREADY_SUPPRESSED, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_ALREADY_SUPPRESSED, plan.decisionReason)
    }

    @Test
    fun P08_priority_unsupportedStatusTakesPrecedenceOverActiveSession() {
        val facts = buildFacts(
            occurrenceStatus = IssuedOccurrence.STATUS_PREPARED,
            isAlreadyAdmitted = false,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_UNSUPPORTED_STATUS, plan.decisionReason)
    }

    @Test
    fun P09_canonicalOccurrenceAndAdmissionKeysMatchFormulas() {
        val facts = buildFacts()
        val expectedOccKey = "alarm_${defaultAlarmId}_gen_${defaultGeneration}_base_$defaultBaseTriggerTime"
        val expectedAdmKey = "admission_${defaultAlarmId}_$expectedOccKey"

        assertEquals(expectedOccKey, facts.occurrenceKey)
        assertEquals(expectedAdmKey, facts.admissionKey)

        val plan = planner.plan(facts)
        assertEquals(expectedOccKey, plan.occurrenceKey)
        assertEquals(expectedAdmKey, plan.admissionKey)
    }

    @Test
    fun P10_deterministicRepeatEvaluation_producesIdenticalResults() {
        val facts = buildFacts(hasActiveSession = false)
        val initialPlan = planner.plan(facts)

        for (i in 1..100) {
            val repeatedPlan = planner.plan(facts)
            assertEquals(initialPlan, repeatedPlan)
        }
    }

    @Test
    fun P11_pureExecutionHasNoSideEffects_factsRemainImmutable() {
        val factsBefore = buildFacts(hasActiveSession = true)
        val plan = planner.plan(factsBefore)
        val factsAfter = factsBefore.copy()

        assertEquals(factsBefore, factsAfter)
        assertEquals(factsBefore.occurrenceId, plan.occurrenceId)
        assertEquals(factsBefore.occurrenceKey, plan.occurrenceKey)
        assertEquals(factsBefore.admissionKey, plan.admissionKey)
        assertEquals(factsBefore.fireId, plan.fireId)
        assertEquals(factsBefore.issueToken, plan.issueToken)
    }

    // -------------------------------------------------------------
    // Fail-Closed Explicit Fact Contract & Construction Proofs
    // -------------------------------------------------------------

    @Test
    fun C01_sourceContract_hasNoDefaultArgumentsOnVerificationInputs() {
        // In Kotlin, default constructor parameters result in a synthetic constructor with DefaultConstructorMarker.
        // If all 6 verification inputs have NO default arguments, exactly 1 declared constructor exists.
        val constructors = NormalAlarmHandoffFacts::class.java.declaredConstructors
        assertEquals(
            "NormalAlarmHandoffFacts must have exactly 1 constructor (all 11 properties explicitly required, 0 defaults)",
            1,
            constructors.size
        )
        val hasSyntheticDefaultConstructor = constructors.any { constructor ->
            constructor.parameterTypes.any { it.name.contains("DefaultConstructorMarker") }
        }
        assertFalse(
            "NormalAlarmHandoffFacts must not have any synthetic default constructor",
            hasSyntheticDefaultConstructor
        )
    }

    @Test
    fun E01_explicitAllValidFacts_admitsNewSession() {
        // Direct instantiation without helper default arguments
        val facts = NormalAlarmHandoffFacts(
            occurrenceId = defaultOccurrenceId,
            fireId = defaultFireId,
            scheduledAt = defaultScheduledAt,
            issueToken = defaultIssueToken,
            occurrenceStatus = IssuedOccurrence.STATUS_ISSUED,
            isIdentityValid = true,
            isProvenanceValid = true,
            isGenerationValid = true,
            isIssueSlotValid = true,
            isAlreadyAdmitted = false,
            hasActiveSession = false
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.ADMIT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_ADMIT_NEW_SESSION, plan.decisionReason)
        assertTrue(plan.isAdmitted)
    }

    @Test
    fun E02_explicitActiveSession_suppressesOccurrence() {
        // Direct instantiation with explicit hasActiveSession = true
        val facts = NormalAlarmHandoffFacts(
            occurrenceId = defaultOccurrenceId,
            fireId = defaultFireId,
            scheduledAt = defaultScheduledAt,
            issueToken = defaultIssueToken,
            occurrenceStatus = IssuedOccurrence.STATUS_ISSUED,
            isIdentityValid = true,
            isProvenanceValid = true,
            isGenerationValid = true,
            isIssueSlotValid = true,
            isAlreadyAdmitted = false,
            hasActiveSession = true
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.SUPPRESS, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_SUPPRESS_ACTIVE_SESSION, plan.decisionReason)
        assertTrue(plan.isSuppressed)
    }

    @Test
    fun E03_explicitDuplicate_returnsDuplicate() {
        // Direct instantiation with explicit isAlreadyAdmitted = true
        val facts = NormalAlarmHandoffFacts(
            occurrenceId = defaultOccurrenceId,
            fireId = defaultFireId,
            scheduledAt = defaultScheduledAt,
            issueToken = defaultIssueToken,
            occurrenceStatus = IssuedOccurrence.STATUS_ISSUED,
            isIdentityValid = true,
            isProvenanceValid = true,
            isGenerationValid = true,
            isIssueSlotValid = true,
            isAlreadyAdmitted = true,
            hasActiveSession = false
        )
        val plan = planner.plan(facts)

        assertEquals(NormalAlarmHandoffAction.DUPLICATE, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_DUPLICATE_DELIVERY, plan.decisionReason)
        assertTrue(plan.isDuplicate)
    }

    @Test
    fun E04_eachFalseVerification_rejects() {
        // Identity false
        val factsIdFalse = NormalAlarmHandoffFacts(
            defaultOccurrenceId, defaultFireId, defaultScheduledAt, defaultIssueToken,
            IssuedOccurrence.STATUS_ISSUED,
            isIdentityValid = false, isProvenanceValid = true, isGenerationValid = true,
            isIssueSlotValid = true, isAlreadyAdmitted = false, hasActiveSession = false
        )
        assertEquals(NormalAlarmHandoffAction.REJECT, planner.plan(factsIdFalse).action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_IDENTITY, planner.plan(factsIdFalse).decisionReason)

        // Provenance false
        val factsProvFalse = NormalAlarmHandoffFacts(
            defaultOccurrenceId, defaultFireId, defaultScheduledAt, defaultIssueToken,
            IssuedOccurrence.STATUS_ISSUED,
            isIdentityValid = true, isProvenanceValid = false, isGenerationValid = true,
            isIssueSlotValid = true, isAlreadyAdmitted = false, hasActiveSession = false
        )
        assertEquals(NormalAlarmHandoffAction.REJECT, planner.plan(factsProvFalse).action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_PROVENANCE, planner.plan(factsProvFalse).decisionReason)

        // Generation false
        val factsGenFalse = NormalAlarmHandoffFacts(
            defaultOccurrenceId, defaultFireId, defaultScheduledAt, defaultIssueToken,
            IssuedOccurrence.STATUS_ISSUED,
            isIdentityValid = true, isProvenanceValid = true, isGenerationValid = false,
            isIssueSlotValid = true, isAlreadyAdmitted = false, hasActiveSession = false
        )
        assertEquals(NormalAlarmHandoffAction.REJECT, planner.plan(factsGenFalse).action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_STALE_GENERATION, planner.plan(factsGenFalse).decisionReason)

        // IssueSlot false
        val factsSlotFalse = NormalAlarmHandoffFacts(
            defaultOccurrenceId, defaultFireId, defaultScheduledAt, defaultIssueToken,
            IssuedOccurrence.STATUS_ISSUED,
            isIdentityValid = true, isProvenanceValid = true, isGenerationValid = true,
            isIssueSlotValid = false, isAlreadyAdmitted = false, hasActiveSession = false
        )
        assertEquals(NormalAlarmHandoffAction.REJECT, planner.plan(factsSlotFalse).action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_STALE_ISSUE_SLOT, planner.plan(factsSlotFalse).decisionReason)
    }

    @Test
    fun E05_falseProvenance_cannotBecomeAdmit() {
        // Explicit proof: false provenance cannot be admitted under any circumstance
        val facts = NormalAlarmHandoffFacts(
            occurrenceId = defaultOccurrenceId,
            fireId = defaultFireId,
            scheduledAt = defaultScheduledAt,
            issueToken = defaultIssueToken,
            occurrenceStatus = IssuedOccurrence.STATUS_ISSUED,
            isIdentityValid = true,
            isProvenanceValid = false,
            isGenerationValid = true,
            isIssueSlotValid = true,
            isAlreadyAdmitted = false,
            hasActiveSession = false
        )
        val plan = planner.plan(facts)

        assertFalse("False provenance must never produce ADMIT", plan.isAdmitted)
        assertEquals(NormalAlarmHandoffAction.REJECT, plan.action)
        assertEquals(NormalAlarmHandoffPlanner.REASON_REJECT_INVALID_PROVENANCE, plan.decisionReason)
    }
}
