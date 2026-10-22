package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class BootstrapAdmissionPolicyTest {

    private val readyAbsentMetaSnapshot = BootstrapAdmissionSnapshot(
        prerequisitesReady = true,
        metaState = BootstrapMetaState.ABSENT,
        metaEpoch = null,
        ownerState = BootstrapOwnerState.ABSENT,
        expectedEpoch = null,
        intentState = BootstrapIntentState.RESOLVED_SET,
        definitionStillCurrent = true,
        expectedOwnerTokenMatches = false
    )

    private val readyPreparingMetaSnapshot = BootstrapAdmissionSnapshot(
        prerequisitesReady = true,
        metaState = BootstrapMetaState.PREPARING,
        metaEpoch = 100L,
        ownerState = BootstrapOwnerState.ABSENT,
        expectedEpoch = 100L,
        intentState = BootstrapIntentState.RESOLVED_SET,
        definitionStillCurrent = true,
        expectedOwnerTokenMatches = false
    )

    @Test
    fun `unmet prerequisites produce BLOCK_PREREQUISITES regardless of other fields`() {
        val snapshot = readyAbsentMetaSnapshot.copy(
            prerequisitesReady = false,
            metaState = BootstrapMetaState.CORRUPT,
            ownerState = BootstrapOwnerState.CONFLICT,
            intentState = BootstrapIntentState.INVALID
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_PREREQUISITES, decision)
    }

    @Test
    fun `valid ABSENT metadata with absent owner produces INITIALIZE_META`() {
        val decision = BootstrapAdmissionPolicy.evaluate(readyAbsentMetaSnapshot)
        assertEquals(BootstrapAdmissionDecision.INITIALIZE_META, decision)
    }

    @Test
    fun `ABSENT metadata with existing owner produces BLOCK_OWNER_CONFLICT`() {
        val snapshot = readyAbsentMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT, decision)
    }

    @Test
    fun `ABSENT metadata with non-null expectedEpoch fails closed to BLOCK_EPOCH`() {
        // ABSENT metadata with positive expectedEpoch (e.g. 100L) must fail closed to BLOCK_EPOCH
        val positiveExpectedEpochSnapshot = readyAbsentMetaSnapshot.copy(
            expectedEpoch = 100L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(positiveExpectedEpochSnapshot))

        // ABSENT metadata with nonpositive expectedEpoch
        val negativeExpectedEpochSnapshot = readyAbsentMetaSnapshot.copy(
            expectedEpoch = -5L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(negativeExpectedEpochSnapshot))

        // ABSENT metadata with non-null metaEpoch
        val nonNullMetaEpochSnapshot = readyAbsentMetaSnapshot.copy(
            metaEpoch = 100L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(nonNullMetaEpochSnapshot))
    }

    @Test
    fun `PREPARING metadata with matching epoch proceeds to owner evaluation`() {
        val snapshot = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = true
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.REUSE_OWNER, decision)
    }

    @Test
    fun `PREPARING metadata with mismatched or nonpositive epoch produces BLOCK_EPOCH`() {
        val mismatchedEpochSnapshot = readyPreparingMetaSnapshot.copy(
            metaEpoch = 100L,
            expectedEpoch = 200L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(mismatchedEpochSnapshot))

        val nullMetaEpochSnapshot = readyPreparingMetaSnapshot.copy(
            metaEpoch = null,
            expectedEpoch = 100L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(nullMetaEpochSnapshot))

        val zeroMetaEpochSnapshot = readyPreparingMetaSnapshot.copy(
            metaEpoch = 0L,
            expectedEpoch = 0L
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_EPOCH, BootstrapAdmissionPolicy.evaluate(zeroMetaEpochSnapshot))
    }

    @Test
    fun `corrupt metadata state produces BLOCK_INVALID_META`() {
        val snapshot = readyAbsentMetaSnapshot.copy(
            metaState = BootstrapMetaState.CORRUPT
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_INVALID_META, decision)
    }

    @Test
    fun `unknown metadata state produces BLOCK_UNKNOWN_META`() {
        val snapshot = readyAbsentMetaSnapshot.copy(
            metaState = BootstrapMetaState.UNKNOWN
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_UNKNOWN_META, decision)
    }

    @Test
    fun `enforced metadata state produces BLOCK_ENFORCED`() {
        val snapshot = readyAbsentMetaSnapshot.copy(
            metaState = BootstrapMetaState.ENFORCED
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_ENFORCED, decision)
    }

    @Test
    fun `stale alarm definition produces REPLAN_STALE_DEFINITION`() {
        val snapshot = readyPreparingMetaSnapshot.copy(
            definitionStillCurrent = false
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.REPLAN_STALE_DEFINITION, decision)
    }

    @Test
    fun `conflicting owner state produces BLOCK_OWNER_CONFLICT`() {
        val snapshot = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.CONFLICT
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT, decision)
    }

    @Test
    fun `unknown owner state produces INSPECT_UNKNOWN_OWNER`() {
        val snapshot = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.UNKNOWN
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.INSPECT_UNKNOWN_OWNER, decision)
    }

    @Test
    fun `VALID_CURRENT owner with token mismatch produces BLOCK_OWNER_CONFLICT regardless of intent state`() {
        // VALID_CURRENT + UNRESOLVED + token mismatch -> BLOCK_OWNER_CONFLICT
        val unresolvedMismatch = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = false,
            intentState = BootstrapIntentState.UNRESOLVED
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT, BootstrapAdmissionPolicy.evaluate(unresolvedMismatch))

        // VALID_CURRENT + INVALID + token mismatch -> BLOCK_OWNER_CONFLICT
        val invalidMismatch = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = false,
            intentState = BootstrapIntentState.INVALID
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT, BootstrapAdmissionPolicy.evaluate(invalidMismatch))

        // VALID_CURRENT + RESOLVED_SET + token mismatch -> BLOCK_OWNER_CONFLICT
        val setMismatch = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = false,
            intentState = BootstrapIntentState.RESOLVED_SET
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT, BootstrapAdmissionPolicy.evaluate(setMismatch))
    }

    @Test
    fun `VALID_CURRENT owner with matching token produces REUSE_OWNER even when intent is UNRESOLVED`() {
        // VALID_CURRENT + UNRESOLVED + matching token -> REUSE_OWNER
        val unresolvedMatching = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = true,
            intentState = BootstrapIntentState.UNRESOLVED
        )
        assertEquals(BootstrapAdmissionDecision.REUSE_OWNER, BootstrapAdmissionPolicy.evaluate(unresolvedMatching))

        // VALID_CURRENT + RESOLVED_SET + matching token -> REUSE_OWNER
        val setMatching = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = true,
            intentState = BootstrapIntentState.RESOLVED_SET
        )
        assertEquals(BootstrapAdmissionDecision.REUSE_OWNER, BootstrapAdmissionPolicy.evaluate(setMatching))
    }

    @Test
    fun `absent owner with resolved intent produces PREPARE_OWNER`() {
        val setSnapshot = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.ABSENT,
            intentState = BootstrapIntentState.RESOLVED_SET
        )
        assertEquals(BootstrapAdmissionDecision.PREPARE_OWNER, BootstrapAdmissionPolicy.evaluate(setSnapshot))

        val cancelSnapshot = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.ABSENT,
            intentState = BootstrapIntentState.RESOLVED_CANCEL
        )
        assertEquals(BootstrapAdmissionDecision.PREPARE_OWNER, BootstrapAdmissionPolicy.evaluate(cancelSnapshot))
    }

    @Test
    fun `absent owner with unresolved intent produces KEEP_UNRESOLVED`() {
        val snapshot = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.ABSENT,
            intentState = BootstrapIntentState.UNRESOLVED
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.KEEP_UNRESOLVED, decision)
    }

    @Test
    fun `absent owner with invalid intent produces BLOCK_INVALID_INTENT`() {
        val snapshot = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.ABSENT,
            intentState = BootstrapIntentState.INVALID
        )
        val decision = BootstrapAdmissionPolicy.evaluate(snapshot)
        assertEquals(BootstrapAdmissionDecision.BLOCK_INVALID_INTENT, decision)
    }

    @Test
    fun `priority conflict order is strictly obeyed`() {
        // Precedence 1: Prerequisites > Metadata
        val prereqOverMeta = readyPreparingMetaSnapshot.copy(
            prerequisitesReady = false,
            metaState = BootstrapMetaState.CORRUPT
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_PREREQUISITES, BootstrapAdmissionPolicy.evaluate(prereqOverMeta))

        // Precedence 2: Metadata > Definition Currency
        val metaOverDef = readyPreparingMetaSnapshot.copy(
            metaState = BootstrapMetaState.CORRUPT,
            definitionStillCurrent = false
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_INVALID_META, BootstrapAdmissionPolicy.evaluate(metaOverDef))

        // Precedence 3: Definition Currency > Owner State
        val defOverOwner = readyPreparingMetaSnapshot.copy(
            definitionStillCurrent = false,
            ownerState = BootstrapOwnerState.CONFLICT
        )
        assertEquals(BootstrapAdmissionDecision.REPLAN_STALE_DEFINITION, BootstrapAdmissionPolicy.evaluate(defOverOwner))

        // Precedence 4: Owner State > Intent State
        val ownerOverIntent = readyPreparingMetaSnapshot.copy(
            ownerState = BootstrapOwnerState.VALID_CURRENT,
            expectedOwnerTokenMatches = false,
            intentState = BootstrapIntentState.INVALID
        )
        assertEquals(BootstrapAdmissionDecision.BLOCK_OWNER_CONFLICT, BootstrapAdmissionPolicy.evaluate(ownerOverIntent))
    }

    @Test
    fun `identical snapshots produce identical decisions`() {
        val decision1 = BootstrapAdmissionPolicy.evaluate(readyAbsentMetaSnapshot)
        val decision2 = BootstrapAdmissionPolicy.evaluate(readyAbsentMetaSnapshot)
        assertEquals(decision1, decision2)
        assertEquals(BootstrapAdmissionDecision.INITIALIZE_META, decision1)
    }
}
