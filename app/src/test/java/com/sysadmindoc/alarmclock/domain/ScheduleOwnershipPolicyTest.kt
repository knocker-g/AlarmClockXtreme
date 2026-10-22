package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ScheduleOwnershipPolicyTest {

    private val defaultOwner = DefinitionOwner(
        alarmId = 42L,
        instanceToken = "token-abc-123",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        exists = true,
        enabled = true,
        reconciliationRequired = false
    )

    private val validSetRequest = MutationRequest(
        alarmId = 42L,
        instanceToken = "token-abc-123",
        expectedDefinitionRevision = 1L,
        expectedScheduleRevision = 1L,
        operation = MutationOperation.SET,
        triggerAtMillis = 1_000_000L
    )

    private val validCancelRequest = MutationRequest(
        alarmId = 42L,
        instanceToken = "token-abc-123",
        expectedDefinitionRevision = 1L,
        expectedScheduleRevision = 1L,
        operation = MutationOperation.CANCEL,
        triggerAtMillis = null
    )

    @Test
    fun `matching SET on enabled owner produces ADMIT`() {
        val decision = ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest)
        assertEquals(ScheduleOwnershipDecision.ADMIT, decision)
    }

    @Test
    fun `matching CANCEL on enabled owner produces ADMIT`() {
        val decision = ScheduleOwnershipPolicy.evaluate(defaultOwner, validCancelRequest)
        assertEquals(ScheduleOwnershipDecision.ADMIT, decision)
    }

    @Test
    fun `CANCEL on disabled owner produces ADMIT`() {
        val disabledOwner = defaultOwner.copy(enabled = false)
        val decision = ScheduleOwnershipPolicy.evaluate(disabledOwner, validCancelRequest)
        assertEquals(ScheduleOwnershipDecision.ADMIT, decision)
    }

    @Test
    fun `SET on disabled owner produces REJECT_INVALID`() {
        val disabledOwner = defaultOwner.copy(enabled = false)
        val decision = ScheduleOwnershipPolicy.evaluate(disabledOwner, validSetRequest)
        assertEquals(ScheduleOwnershipDecision.REJECT_INVALID, decision)
    }

    @Test
    fun `missing or non-existent owner produces REJECT_DELETED`() {
        val missingOwner = defaultOwner.copy(exists = false)
        val decision = ScheduleOwnershipPolicy.evaluate(missingOwner, validSetRequest)
        assertEquals(ScheduleOwnershipDecision.REJECT_DELETED, decision)
    }

    @Test
    fun `tombstoned owner produces REJECT_DELETED`() {
        val tombstonedOwner = defaultOwner.copy(exists = false, enabled = false)
        val decision = ScheduleOwnershipPolicy.evaluate(tombstonedOwner, validCancelRequest)
        assertEquals(ScheduleOwnershipDecision.REJECT_DELETED, decision)
    }

    @Test
    fun `same numeric alarmId with different instanceToken produces REJECT_STALE`() {
        val request = validSetRequest.copy(instanceToken = "different-token-999")
        val decision = ScheduleOwnershipPolicy.evaluate(defaultOwner, request)
        assertEquals(ScheduleOwnershipDecision.REJECT_STALE, decision)
    }

    @Test
    fun `stale definitionRevision produces REJECT_STALE`() {
        val request = validSetRequest.copy(expectedDefinitionRevision = 2L)
        val decision = ScheduleOwnershipPolicy.evaluate(defaultOwner, request)
        assertEquals(ScheduleOwnershipDecision.REJECT_STALE, decision)
    }

    @Test
    fun `stale scheduleRevision produces REJECT_STALE`() {
        val request = validSetRequest.copy(expectedScheduleRevision = 2L)
        val decision = ScheduleOwnershipPolicy.evaluate(defaultOwner, request)
        assertEquals(ScheduleOwnershipDecision.REJECT_STALE, decision)
    }

    @Test
    fun `different alarmId produces REJECT_STALE`() {
        val request = validSetRequest.copy(alarmId = 99L)
        val decision = ScheduleOwnershipPolicy.evaluate(defaultOwner, request)
        assertEquals(ScheduleOwnershipDecision.REJECT_STALE, decision)
    }

    @Test
    fun `invalid request inputs produce REJECT_INVALID`() {
        // alarmId <= 0
        assertEquals(
            ScheduleOwnershipDecision.REJECT_INVALID,
            ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest.copy(alarmId = 0L))
        )
        // blank instanceToken
        assertEquals(
            ScheduleOwnershipDecision.REJECT_INVALID,
            ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest.copy(instanceToken = "  "))
        )
        // expectedDefinitionRevision <= 0
        assertEquals(
            ScheduleOwnershipDecision.REJECT_INVALID,
            ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest.copy(expectedDefinitionRevision = 0L))
        )
        // expectedScheduleRevision <= 0
        assertEquals(
            ScheduleOwnershipDecision.REJECT_INVALID,
            ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest.copy(expectedScheduleRevision = -1L))
        )
        // SET with null or <= 0 triggerAtMillis
        assertEquals(
            ScheduleOwnershipDecision.REJECT_INVALID,
            ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest.copy(triggerAtMillis = null))
        )
        assertEquals(
            ScheduleOwnershipDecision.REJECT_INVALID,
            ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest.copy(triggerAtMillis = 0L))
        )
        // CANCEL with non-null triggerAtMillis
        assertEquals(
            ScheduleOwnershipDecision.REJECT_INVALID,
            ScheduleOwnershipPolicy.evaluate(defaultOwner, validCancelRequest.copy(triggerAtMillis = 500L))
        )
    }

    @Test
    fun `corrupt owner fields produce NEEDS_RECONCILE`() {
        // alarmId <= 0
        assertEquals(
            ScheduleOwnershipDecision.NEEDS_RECONCILE,
            ScheduleOwnershipPolicy.evaluate(defaultOwner.copy(alarmId = -1L), validSetRequest)
        )
        // blank instanceToken
        assertEquals(
            ScheduleOwnershipDecision.NEEDS_RECONCILE,
            ScheduleOwnershipPolicy.evaluate(defaultOwner.copy(instanceToken = ""), validSetRequest)
        )
        // definitionRevision <= 0
        assertEquals(
            ScheduleOwnershipDecision.NEEDS_RECONCILE,
            ScheduleOwnershipPolicy.evaluate(defaultOwner.copy(definitionRevision = 0L), validSetRequest)
        )
        // scheduleRevision <= 0
        assertEquals(
            ScheduleOwnershipDecision.NEEDS_RECONCILE,
            ScheduleOwnershipPolicy.evaluate(defaultOwner.copy(scheduleRevision = -5L), validSetRequest)
        )
    }

    @Test
    fun `matching owner requiring reconciliation produces NEEDS_RECONCILE`() {
        val reconcileOwner = defaultOwner.copy(reconciliationRequired = true)
        val decision = ScheduleOwnershipPolicy.evaluate(reconcileOwner, validSetRequest)
        assertEquals(ScheduleOwnershipDecision.NEEDS_RECONCILE, decision)
    }

    @Test
    fun `stale request on reconciliation-pending owner produces REJECT_STALE by precedence`() {
        // Precedence Rule 4 (REJECT_STALE) must take precedence over Rule 5 (NEEDS_RECONCILE)
        val reconcileOwner = defaultOwner.copy(reconciliationRequired = true)
        val staleRequest = validSetRequest.copy(expectedDefinitionRevision = 99L)
        val decision = ScheduleOwnershipPolicy.evaluate(reconcileOwner, staleRequest)
        assertEquals(ScheduleOwnershipDecision.REJECT_STALE, decision)
    }

    @Test
    fun `identical inputs produce identical decisions`() {
        val decision1 = ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest)
        val decision2 = ScheduleOwnershipPolicy.evaluate(defaultOwner, validSetRequest)
        assertEquals(decision1, decision2)
        assertEquals(ScheduleOwnershipDecision.ADMIT, decision1)
    }
}
