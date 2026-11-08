package com.sysadmindoc.alarmclock.domain.evaluator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic unit tests for [NormalAlarmPostFirePlanner] (KNO-37 Phase H2-C4-P1).
 *
 * Verifies post-fire schedule planning without database, OS, or coroutine side-effects.
 */
class NormalAlarmPostFirePlannerTest {

    private val planner = NormalAlarmPostFirePlanner()

    private val defaultAlarmId = 42L
    private val defaultScheduledAt = 1_700_000_000_000L
    private val defaultSessionId = "session-test-uuid-42"
    private val defaultAdmissionKey = "admission_42_occ-100"

    private fun createHandoffResult(
        outcome: NormalAlarmHandoffOutcome,
        sessionId: String? = defaultSessionId,
        actionTaken: NormalAlarmHandoffAction? = null,
        diagnosticDetail: String? = null
    ): NormalAlarmHandoffResult {
        return NormalAlarmHandoffResult(
            outcome = outcome,
            diagnosticDetail = diagnosticDetail ?: "Test outcome: ${outcome.name}",
            sessionId = sessionId,
            admissionKey = defaultAdmissionKey,
            actionTaken = actionTaken,
            decisionReason = outcome.name
        )
    }

    private fun createFacts(
        outcome: NormalAlarmHandoffOutcome,
        isRecurringSchedule: Boolean = true,
        alarmId: Long = defaultAlarmId,
        scheduledAt: Long = defaultScheduledAt,
        sessionId: String? = defaultSessionId,
        actionTaken: NormalAlarmHandoffAction? = null,
        activeSessionId: String? = null,
        activeSessionState: String? = null
    ): NormalAlarmPostFireFacts {
        return NormalAlarmPostFireFacts(
            alarmId = alarmId,
            isRecurringSchedule = isRecurringSchedule,
            scheduledAt = scheduledAt,
            handoffResult = createHandoffResult(outcome, sessionId, actionTaken),
            activeSessionId = activeSessionId,
            activeSessionState = activeSessionState
        )
    }

    // ==========================================
    // 1. ADMITTED Outcomes
    // ==========================================

    @Test
    fun admitted_recurring_proposesScheduleAdvancement() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            isRecurringSchedule = true,
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.ADVANCE_REPEATING_SCHEDULE, plan.action)
        assertTrue(plan.isAdvancing)
        assertFalse(plan.isDisabling)
        assertFalse(plan.isAttaching)
        assertFalse(plan.isDropped)
        assertEquals(defaultAlarmId, plan.alarmId)
        assertEquals(defaultScheduledAt + 60_000L, plan.notBeforeMillis)
        assertEquals(defaultSessionId, plan.targetSessionId)
        assertEquals(NormalAlarmPostFirePlanner.REASON_ADMITTED_REPEATING_ADVANCE, plan.decisionReason)
    }

    @Test
    fun admitted_oneTime_proposesDefinitionDisablement() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            isRecurringSchedule = false,
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.DISABLE_ONE_TIME, plan.action)
        assertTrue(plan.isDisabling)
        assertFalse(plan.isAdvancing)
        assertFalse(plan.isAttaching)
        assertFalse(plan.isDropped)
        assertEquals(defaultAlarmId, plan.alarmId)
        assertEquals(0L, plan.notBeforeMillis)
        assertEquals(defaultSessionId, plan.targetSessionId)
        assertEquals(NormalAlarmPostFirePlanner.REASON_ADMITTED_ONE_TIME_DISABLE, plan.decisionReason)
    }

    @Test
    fun admitted_missingSessionId_failsClosedDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            isRecurringSchedule = true,
            sessionId = null,
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertTrue(plan.isDropped)
        assertEquals(NormalAlarmPostFirePlanner.REASON_CONTRADICTORY_ADMISSION, plan.decisionReason)
    }

    @Test
    fun admitted_blankSessionId_failsClosedDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            isRecurringSchedule = true,
            sessionId = "   ",
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertTrue(plan.isDropped)
        assertEquals(NormalAlarmPostFirePlanner.REASON_CONTRADICTORY_ADMISSION, plan.decisionReason)
    }

    // ==========================================
    // 2. SUPPRESSED Outcomes
    // ==========================================

    @Test
    fun suppressed_recurring_withProvenSuppressionAction_proposesScheduleAdvancement() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.SUPPRESSED,
            isRecurringSchedule = true,
            actionTaken = NormalAlarmHandoffAction.SUPPRESS
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.ADVANCE_REPEATING_SCHEDULE, plan.action)
        assertTrue(plan.isAdvancing)
        assertEquals(defaultAlarmId, plan.alarmId)
        assertEquals(defaultScheduledAt + 60_000L, plan.notBeforeMillis)
        assertNull(plan.targetSessionId)
        assertEquals(NormalAlarmPostFirePlanner.REASON_SUPPRESSED_REPEATING_ADVANCE, plan.decisionReason)
    }

    @Test
    fun suppressed_oneTime_withProvenSuppressionAction_dropsWithoutDisable() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.SUPPRESSED,
            isRecurringSchedule = false,
            actionTaken = NormalAlarmHandoffAction.SUPPRESS
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertTrue(plan.isDropped)
        assertEquals(NormalAlarmPostFirePlanner.REASON_SUPPRESSED_ONE_TIME_NOOP, plan.decisionReason)
    }

    @Test
    fun suppressed_unprovenActionTaken_failsClosedDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.SUPPRESSED,
            isRecurringSchedule = true,
            actionTaken = NormalAlarmHandoffAction.REJECT // Inconsistent
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertTrue(plan.isDropped)
        assertEquals(NormalAlarmPostFirePlanner.REASON_SUPPRESSED_UNPROVEN_ACTION, plan.decisionReason)
    }

    // ==========================================
    // 3. DUPLICATE Outcomes
    // ==========================================

    @Test
    fun duplicate_activeFiringSession_proposesAttach() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.DUPLICATE,
            actionTaken = NormalAlarmHandoffAction.DUPLICATE,
            sessionId = "session-active-1",
            activeSessionId = "session-active-1",
            activeSessionState = "FIRING"
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.ATTACH_TO_ACTIVE_SESSION, plan.action)
        assertTrue(plan.isAttaching)
        assertEquals("session-active-1", plan.targetSessionId)
        assertEquals(NormalAlarmPostFirePlanner.REASON_DUPLICATE_ACTIVE_ATTACH, plan.decisionReason)
    }

    @Test
    fun duplicate_activeSnoozedSession_proposesAttach() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.DUPLICATE,
            actionTaken = NormalAlarmHandoffAction.DUPLICATE,
            sessionId = "session-snoozed-1",
            activeSessionId = "session-snoozed-1",
            activeSessionState = "SNOOZED"
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.ATTACH_TO_ACTIVE_SESSION, plan.action)
        assertTrue(plan.isAttaching)
        assertEquals("session-snoozed-1", plan.targetSessionId)
        assertEquals(NormalAlarmPostFirePlanner.REASON_DUPLICATE_ACTIVE_ATTACH, plan.decisionReason)
    }

    @Test
    fun duplicate_activeWakeCheckWaitingSession_proposesAttach() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.DUPLICATE,
            actionTaken = NormalAlarmHandoffAction.DUPLICATE,
            sessionId = "session-wake-check-1",
            activeSessionId = "session-wake-check-1",
            activeSessionState = "WAKE_CHECK_WAITING"
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.ATTACH_TO_ACTIVE_SESSION, plan.action)
        assertTrue(plan.isAttaching)
        assertEquals("session-wake-check-1", plan.targetSessionId)
        assertEquals(NormalAlarmPostFirePlanner.REASON_DUPLICATE_ACTIVE_ATTACH, plan.decisionReason)
    }

    @Test
    fun duplicate_activeWakeCheckActiveSession_proposesAttach() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.DUPLICATE,
            actionTaken = NormalAlarmHandoffAction.DUPLICATE,
            sessionId = "session-wake-active-1",
            activeSessionId = "session-wake-active-1",
            activeSessionState = "WAKE_CHECK_ACTIVE"
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.ATTACH_TO_ACTIVE_SESSION, plan.action)
        assertTrue(plan.isAttaching)
        assertEquals("session-wake-active-1", plan.targetSessionId)
        assertEquals(NormalAlarmPostFirePlanner.REASON_DUPLICATE_ACTIVE_ATTACH, plan.decisionReason)
    }

    @Test
    fun duplicate_completedSession_proposesDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.DUPLICATE,
            actionTaken = NormalAlarmHandoffAction.DUPLICATE,
            sessionId = "session-completed-1",
            activeSessionId = "session-completed-1",
            activeSessionState = "COMPLETED"
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertTrue(plan.isDropped)
        assertEquals(NormalAlarmPostFirePlanner.REASON_DUPLICATE_COMPLETED_DROP, plan.decisionReason)
    }

    @Test
    fun duplicate_mismatchedSessionId_proposesDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.DUPLICATE,
            actionTaken = NormalAlarmHandoffAction.DUPLICATE,
            sessionId = "session-expected-1",
            activeSessionId = "session-different-2",
            activeSessionState = "FIRING"
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertTrue(plan.isDropped)
        assertEquals(NormalAlarmPostFirePlanner.REASON_DUPLICATE_COMPLETED_DROP, plan.decisionReason)
    }

    @Test
    fun duplicate_nullActiveSession_proposesDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.DUPLICATE,
            actionTaken = NormalAlarmHandoffAction.DUPLICATE,
            sessionId = "session-1",
            activeSessionId = null,
            activeSessionState = null
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertTrue(plan.isDropped)
        assertEquals(NormalAlarmPostFirePlanner.REASON_DUPLICATE_NO_ACTIVE_SESSION_DROP, plan.decisionReason)
    }

    // ==========================================
    // 4. Negative / Fail-Closed Outcomes
    // ==========================================

    @Test
    fun alreadySuppressed_dropsWithoutAction() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ALREADY_SUPPRESSED,
            actionTaken = NormalAlarmHandoffAction.ALREADY_SUPPRESSED
        )

        val plan = planner.plan(facts)

        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertTrue(plan.isDropped)
        assertEquals(NormalAlarmPostFirePlanner.REASON_ALREADY_SUPPRESSED_DROP, plan.decisionReason)
    }

    @Test
    fun rejectedStaleIdentity_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_IDENTITY)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_REJECTED_DROP, plan.decisionReason)
    }

    @Test
    fun rejectedStaleProvenance_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_PROVENANCE)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_REJECTED_DROP, plan.decisionReason)
    }

    @Test
    fun rejectedStaleGeneration_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_GENERATION)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_REJECTED_DROP, plan.decisionReason)
    }

    @Test
    fun rejectedStaleIssueSlot_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.REJECTED_STALE_ISSUE_SLOT)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_REJECTED_DROP, plan.decisionReason)
    }

    @Test
    fun rejectedUnsupportedStatus_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_STATUS)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_REJECTED_DROP, plan.decisionReason)
    }

    @Test
    fun rejectedUnsupportedSchedule_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.REJECTED_UNSUPPORTED_SCHEDULE)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_REJECTED_DROP, plan.decisionReason)
    }

    @Test
    fun corruptedState_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.CORRUPTED_STATE)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_CORRUPTED_STATE_DROP, plan.decisionReason)
    }

    @Test
    fun casConflict_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.CAS_CONFLICT)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_CAS_CONFLICT_DROP, plan.decisionReason)
    }

    @Test
    fun repositoryFailure_dropsWithoutAction() {
        val facts = createFacts(outcome = NormalAlarmHandoffOutcome.REPOSITORY_FAILURE)
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_REPOSITORY_FAILURE_DROP, plan.decisionReason)
    }

    // ==========================================
    // 5. Input Validation & Boundary Overflows
    // ==========================================

    @Test
    fun invalidAlarmId_zero_failsClosedDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            alarmId = 0L,
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_INVALID_ALARM_ID, plan.decisionReason)
    }

    @Test
    fun invalidAlarmId_negative_failsClosedDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            alarmId = -1L,
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_INVALID_ALARM_ID, plan.decisionReason)
    }

    @Test
    fun invalidScheduledAt_zero_failsClosedDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            scheduledAt = 0L,
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_INVALID_SCHEDULED_AT, plan.decisionReason)
    }

    @Test
    fun invalidScheduledAt_negative_failsClosedDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            scheduledAt = -1000L,
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_INVALID_SCHEDULED_AT, plan.decisionReason)
    }

    @Test
    fun scheduledAt_overflow_failsClosedDrop() {
        val facts = createFacts(
            outcome = NormalAlarmHandoffOutcome.ADMITTED,
            scheduledAt = Long.MAX_VALUE - 30_000L, // Overflow when adding 60_000L
            actionTaken = NormalAlarmHandoffAction.ADMIT
        )
        val plan = planner.plan(facts)
        assertEquals(NormalAlarmPostFireAction.NO_OP_DROP, plan.action)
        assertEquals(NormalAlarmPostFirePlanner.REASON_TIMESTAMP_OVERFLOW, plan.decisionReason)
    }
}
