package com.sysadmindoc.alarmclock.domain

import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.service.AlarmService.Companion.ActiveAlarmSnapshot
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RecoveryAdjudicatorTest {

    private lateinit var adjudicator: RecoveryAdjudicator

    private val baseAuthority = ScheduleAuthority(
        alarmId = 42L,
        instanceToken = "token-verified-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 2000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L,
        actionKey = "act-42"
    )

    private val baseSnapshot = RecoveryEvidenceSnapshot(
        alarmId = 42L,
        authority = baseAuthority,
        restoreEpochMeta = 100L,
        durableIntent = null,
        activeSession = null,
        localIncarnationId = "token-verified-42",
        osOutcome = OsOperationOutcome.NOT_ATTEMPTED,
        definitionExists = true
    )

    @Before
    fun setUp() {
        adjudicator = RecoveryAdjudicator()
    }

    @Test
    fun `matching current generation settled authority produces NO_ACTION`() {
        val decision = adjudicator.adjudicate(baseSnapshot)
        assertEquals(RecoveryDecisionOutcome.NO_ACTION, decision.outcome)
        assertEquals("AUTHORITY_SETTLED_NO_ACTION", decision.reasonCode)
    }

    @Test
    fun `stale incarnation produces QUARANTINE`() {
        val snapshot = baseSnapshot.copy(localIncarnationId = "token-NEW-INC")
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.QUARANTINE, decision.outcome)
        assertEquals("STALE_INCARNATION", decision.reasonCode)
    }

    @Test
    fun `restore epoch mismatch produces QUARANTINE`() {
        val snapshot = baseSnapshot.copy(restoreEpochMeta = 200L) // Meta 200L vs Auth 100L
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.QUARANTINE, decision.outcome)
        assertEquals("RESTORE_EPOCH_MISMATCH", decision.reasonCode)
    }

    @Test
    fun `missing definition produces QUARANTINE`() {
        val snapshot = baseSnapshot.copy(definitionExists = false)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.QUARANTINE, decision.outcome)
        assertEquals("DEFINITION_MISSING", decision.reasonCode)
    }

    @Test
    fun `pending durable intent produces RECONCILE_AUTHORITY`() {
        val intent = DurableOperationIntent(
            operationToken = "intent-42",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SET",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "PENDING",
            intentRevision = 1L,
            originalFireId = "fire-42",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = null
        )
        val snapshot = baseSnapshot.copy(durableIntent = intent)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.RECONCILE_AUTHORITY, decision.outcome)
        assertEquals("DURABLE_INTENT_PENDING", decision.reasonCode)
    }

    @Test
    fun `in flight durable intent produces RECONCILE_AUTHORITY`() {
        val intent = DurableOperationIntent(
            operationToken = "intent-42",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SET",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "IN_FLIGHT",
            intentRevision = 1L,
            originalFireId = "fire-42",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = null
        )
        val snapshot = baseSnapshot.copy(durableIntent = intent)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.RECONCILE_AUTHORITY, decision.outcome)
        assertEquals("DURABLE_INTENT_IN_FLIGHT", decision.reasonCode)
    }

    @Test
    fun `completed intent with session evidence produces RESUME_COMMITTED_SESSION_EFFECTS`() {
        val intent = DurableOperationIntent(
            operationToken = "intent-42",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SNOOZE",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "COMPLETED",
            intentRevision = 1L,
            originalFireId = "fire-42",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = 2600L
        )
        val activeSession = ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 2000L,
            fireId = "fire-42",
            firedAt = 2000L,
            state = "SNOOZED",
            refireAt = 2600L
        )
        val snapshot = baseSnapshot.copy(durableIntent = intent, activeSession = activeSession)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.RESUME_COMMITTED_SESSION_EFFECTS, decision.outcome)
        assertEquals("COMPLETED_INTENT_WITH_SESSION", decision.reasonCode)
    }

    @Test
    fun `completed intent missing session evidence produces QUARANTINE`() {
        val intent = DurableOperationIntent(
            operationToken = "intent-42",
            alarmId = 42L,
            operationEpoch = 100L,
            incarnationId = "token-verified-42",
            operationKind = "SNOOZE",
            expectedOwnerToken = "token-verified-42",
            expectedScheduleRevision = 1L,
            expectedRestoreEpoch = 100L,
            phase = "COMPLETED",
            intentRevision = 1L,
            originalFireId = "fire-42",
            originalScheduledAt = 2000L,
            candidateSnoozeAt = 2600L
        )
        val snapshot = baseSnapshot.copy(durableIntent = intent, activeSession = null)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.QUARANTINE, decision.outcome)
        assertEquals("COMPLETED_INTENT_MISSING_SESSION_EVIDENCE", decision.reasonCode)
    }

    @Test
    fun `unknown OS outcome produces QUARANTINE`() {
        val snapshot = baseSnapshot.copy(osOutcome = OsOperationOutcome.UNKNOWN)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.QUARANTINE, decision.outcome)
        assertEquals("OS_OUTCOME_UNKNOWN", decision.reasonCode)
    }

    @Test
    fun `reconciling authority produces RECONCILE_AUTHORITY`() {
        val reconciling = baseAuthority.copy(reconcileState = "RECONCILING")
        val snapshot = baseSnapshot.copy(authority = reconciling)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.RECONCILE_AUTHORITY, decision.outcome)
        assertEquals("AUTHORITY_RECONCILING", decision.reasonCode)
    }

    @Test
    fun `quarantined authority produces QUARANTINE`() {
        val quarantined = baseAuthority.copy(reconcileState = "QUARANTINE")
        val snapshot = baseSnapshot.copy(authority = quarantined)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.QUARANTINE, decision.outcome)
        assertEquals("AUTHORITY_QUARANTINED", decision.reasonCode)
    }

    @Test
    fun `missing restore epoch meta produces NEEDS_EVIDENCE`() {
        val snapshot = baseSnapshot.copy(restoreEpochMeta = null)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.NEEDS_EVIDENCE, decision.outcome)
        assertEquals("RESTORE_EPOCH_META_MISSING", decision.reasonCode)
    }

    @Test
    fun `missing local incarnation produces NEEDS_EVIDENCE`() {
        val snapshot = baseSnapshot.copy(localIncarnationId = null)
        val decision = adjudicator.adjudicate(snapshot)
        assertEquals(RecoveryDecisionOutcome.NEEDS_EVIDENCE, decision.outcome)
        assertEquals("LOCAL_INCARNATION_MISSING", decision.reasonCode)
    }
}
