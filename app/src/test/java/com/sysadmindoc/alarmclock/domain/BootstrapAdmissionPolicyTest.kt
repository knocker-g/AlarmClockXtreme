package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapAdmissionPolicyTest {

    private val readySnapshot = BootstrapAdmissionSnapshot(
        prerequisitesReady = true,
        metaState = BootstrapMetaState.ABSENT,
        metaEpoch = null,
        ownerState = BootstrapOwnerState.ABSENT,
        expectedEpoch = 100L,
        intentState = BootstrapIntentState.RESOLVED_SET,
        definitionStillCurrent = true,
        expectedOwnerTokenMatches = false
    )

    @Test
    fun `unmet prerequisites produce BLOCK_PREREQUISITES regardless of other fields`() {
        val snapshot = readySnapshot.copy(
            prerequisitesReady = false,
            metaState = BootstrapMetaState.CORRUPT,
            ownerState = BootstrapOwnerState.CONFLICT,
            intentState = BootstrapIntentState.INVALID
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_PREREQUISITES, decision)
    }

    @Test
    fun `corrupt metadata state produces BLOCK_INVALID_META`() {
        val snapshot = readySnapshot.copy(
            metaState = BootstrapMetaState.CORRUPT
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_INVALID_META, decision)
    }

    @Test
    fun `unknown metadata state produces BLOCK_UNKNOWN_META`() {
        val snapshot = readySnapshot.copy(
            metaState = BootstrapMetaState.UNKNOWN
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_UNKNOWN_META, decision)
    }

    @Test
    fun `enforced metadata state produces BLOCK_ENFORCED`() {
        val snapshot = readySnapshot.copy(
            metaState = BootstrapMetaState.ENFORCED
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_ENFORCED, decision)
    }

    @Test
    fun `absent metadata with null or nonpositive expected epoch produces BLOCK_EPOCH`() {
        val nullEpochSnapshot = readySnapshot.copy(
            metaState = BootstrapMetaState.ABSENT,
            expectedEpoch = null
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(nullEpochSnapshot))

        val zeroEpochSnapshot = readySnapshot.copy(
            metaState = BootstrapMetaState.ABSENT,
            expectedEpoch = 0L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(zeroEpochSnapshot))
    }

    @Test
    fun `preparing metadata with mismatched or nonpositive epoch produces BLOCK_EPOCH`() {
        val mismatchedEpochSnapshot = readySnapshot.copy(
            metaState = BootstrapMetaState.PREPARING,
            metaEpoch = 100L,
            expectedEpoch = 200L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(mismatchedEpochSnapshot))

        val nullMetaEpochSnapshot = readySnapshot.copy(
            metaState = BootstrapMetaState.PREPARING,
            metaEpoch = null,
            expectedEpoch = 100L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(nullMetaEpochSnapshot))
    }

    @Test
    fun `stale alarm definition produces REPLAN_STALE_DEFINITION`() {
        val snapshot = readySnapshot.copy(
            definitionStillCurrent = false
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.REPLAN_STALE_DEFINITION, decision)
    }

    @Test
    fun `conflicting owner state produces BLOCK_OWNER_CONFLICT`() {
        val snapshot = readySnapshot.copy(
            ownerState = BootstrapOwnerState.CONFLICT
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT, decision)
    }

    @Test
    fun `unknown owner state produces INSPECT_UNKNOWN_OWNER`() {
        val snapshot = readySnapshot.copy(
            ownerState = BootstrapOwnerState.UNKNOWN
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.INSPECT_UNKNOWN_OWNER, decision)
    }

    @Test
    fun `invalid intent state produces BLOCK_INVALID_INTENT`() {
        val snapshot = readySnapshot.copy(
            intentState = BootstrapIntentState.INVALID
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_INVALID_INTENT, decision)
    }

    @Test
    fun `unresolved intent state produces KEEP_UNRESOLVED`() {
        val snapshot = readySnapshot.copy(
            intentState = BootstrapIntentState.UNRESOLVED
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.KEEP_UNRESOLVED, decision)
    }

    @Test
    fun `valid current owner with matching token produces REUSE_OWNER`() {
        val snapshot = readySnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = true,
            intentState = BootstrapIntentState.RESOLVED_SET
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.REUSE_OWNER, decision)
    }

    @Test
    fun `absent owner or mismatched token produces PREPARE_OWNER`() {
        // Absent owner -> PREPARE_OWNER
        val absentOwnerSnapshot = readySnapshot.copy(
            ownerState = BootstrapOwnerState.ABSENT,
            expectedOwnerTokenMatches = false,
            intentState = BootstrapIntentState.RESOLVED_SET
        )
        assertEquals(BootstrapAdmissionDecision.PREPARE_OWNER, BootstrapAdmissionPolicy.evaluate(absentOwnerSnapshot))

        // Mismatched token -> PREPARE_OWNER
        val mismatchedTokenSnapshot = readySnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = false,
            intentState = BootstrapIntentState.RESOLVED_CANCEL
        )
        assertEquals(BootstrapAdmissionDecision.PREPARE_OWNER, BootstrapAdmissionPolicy.evaluate(mismatchedTokenSnapshot))
    }

    @Test
    fun `priority conflict order is strictly obeyed`() {
        // Precedence 1: Prerequisites > Metadata
        val prereqOverMeta = readySnapshot.copy(
            prerequisitesReady = false,
            metaState = BootstrapMetaState.CORRUPT
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_PREREQUISITES, BootstrapAdmissionPolicy.evaluate(prereqOverMeta))

        // Precedence 2: Metadata > Definition Currency
        val metaOverDef = readySnapshot.copy(
            metaState = BootstrapMetaState.CORRUPT,
            definitionStillCurrent = false
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_INVALID_META, BootstrapAdmissionPolicy.evaluate(metaOverDef))

        // Precedence 3: Definition Currency > Owner State
        val defOverOwner = readySnapshot.copy(
            definitionStillCurrent = false,
            ownerState = BootstrapOwnerState.CONFLICT
        )
        assertEquals(BootstrapAdmissionDecision.REPLAN_STALE_DEFINITION, BootstrapAdmissionPolicy.evaluate(defOverOwner))

        // Precedence 4: Owner State > Intent State
        val ownerOverIntent = readySnapshot.copy(
            ownerState = BootstrapOwnerState.CONFLICT,
            intentState = BootstrapIntentState.INVALID
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT, BootstrapAdmissionPolicy.evaluate(ownerOverIntent))
    }

    @Test
    fun `identical snapshots produce identical decisions`() {
        val decision1 = BootstrapAdmissionPolicy.evaluate(readySnapshot)
        val decision2 = BootstrapAdmissionPolicy.evaluate(readySnapshot)
        assertEquals(decision1, decision2)
        assertEquals(BootstrapAdmissionDecision.PREPARE_OWNER, decision1)
    }
}
