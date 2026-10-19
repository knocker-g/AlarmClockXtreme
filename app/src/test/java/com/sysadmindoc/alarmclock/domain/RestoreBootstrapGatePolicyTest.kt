package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RestoreBootstrapGatePolicyTest {

    private val allProvenSnapshot = RestoreBootstrapGateSnapshot(
        ceUnlocked = Evidence.PROVEN,
        databaseCompatible = Evidence.PROVEN,
        journalResolved = Evidence.PROVEN,
        restoreOriginClassified = Evidence.PROVEN,
        systemRestoreBarrierCleared = Evidence.PROVEN
    )

    @Test
    fun `all five PROVEN produces ALLOW_SHADOW_META_PREPARATION`() {
        val decision = RestoreBootstrapGatePolicy.evaluate(allProvenSnapshot)
        assertEquals(RestoreBootstrapGateDecision.ALLOW_SHADOW_META_PREPARATION, decision)
    }

    @Test
    fun `ceUnlocked NEGATED produces DEFER_CE_LOCKED`() {
        val snapshot = allProvenSnapshot.copy(ceUnlocked = Evidence.NEGATED)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.DEFER_CE_LOCKED, decision)
    }

    @Test
    fun `ceUnlocked UNKNOWN produces BLOCK_CE_UNKNOWN`() {
        val snapshot = allProvenSnapshot.copy(ceUnlocked = Evidence.UNKNOWN)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.BLOCK_CE_UNKNOWN, decision)
    }

    @Test
    fun `databaseCompatible NEGATED produces STOP_DATABASE_INCOMPATIBLE`() {
        val snapshot = allProvenSnapshot.copy(databaseCompatible = Evidence.NEGATED)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.STOP_DATABASE_INCOMPATIBLE, decision)
    }

    @Test
    fun `databaseCompatible UNKNOWN produces BLOCK_DATABASE_UNKNOWN`() {
        val snapshot = allProvenSnapshot.copy(databaseCompatible = Evidence.UNKNOWN)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.BLOCK_DATABASE_UNKNOWN, decision)
    }

    @Test
    fun `journalResolved NEGATED produces RECOVER_JOURNAL_FIRST`() {
        val snapshot = allProvenSnapshot.copy(journalResolved = Evidence.NEGATED)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.RECOVER_JOURNAL_FIRST, decision)
    }

    @Test
    fun `journalResolved UNKNOWN produces BLOCK_JOURNAL_UNKNOWN`() {
        val snapshot = allProvenSnapshot.copy(journalResolved = Evidence.UNKNOWN)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.BLOCK_JOURNAL_UNKNOWN, decision)
    }

    @Test
    fun `restoreOriginClassified UNKNOWN produces CLASSIFY_RESTORE_ORIGIN_FIRST`() {
        val snapshot = allProvenSnapshot.copy(restoreOriginClassified = Evidence.UNKNOWN)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.CLASSIFY_RESTORE_ORIGIN_FIRST, decision)
    }

    @Test
    fun `restoreOriginClassified NEGATED produces BLOCK_RESTORE_ORIGIN_NEGATED`() {
        val snapshot = allProvenSnapshot.copy(restoreOriginClassified = Evidence.NEGATED)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.BLOCK_RESTORE_ORIGIN_NEGATED, decision)
    }

    @Test
    fun `systemRestoreBarrierCleared NEGATED produces COMPLETE_SYSTEM_RESTORE_BARRIER_FIRST`() {
        val snapshot = allProvenSnapshot.copy(systemRestoreBarrierCleared = Evidence.NEGATED)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.COMPLETE_SYSTEM_RESTORE_BARRIER_FIRST, decision)
    }

    @Test
    fun `systemRestoreBarrierCleared UNKNOWN produces BLOCK_SYSTEM_RESTORE_BARRIER_UNKNOWN`() {
        val snapshot = allProvenSnapshot.copy(systemRestoreBarrierCleared = Evidence.UNKNOWN)
        val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)
        assertEquals(RestoreBootstrapGateDecision.BLOCK_SYSTEM_RESTORE_BARRIER_UNKNOWN, decision)
    }

    @Test
    fun `precedence ordering is strictly obeyed when multiple fields fail`() {
        // Precedence 1: CE > Database
        val ceOverDb = allProvenSnapshot.copy(
            ceUnlocked = Evidence.NEGATED,
            databaseCompatible = Evidence.NEGATED
        )
        assertEquals(RestoreBootstrapGateDecision.DEFER_CE_LOCKED, RestoreBootstrapGatePolicy.evaluate(ceOverDb))

        // Precedence 2: Database > Journal
        val dbOverJournal = allProvenSnapshot.copy(
            databaseCompatible = Evidence.NEGATED,
            journalResolved = Evidence.NEGATED
        )
        assertEquals(RestoreBootstrapGateDecision.STOP_DATABASE_INCOMPATIBLE, RestoreBootstrapGatePolicy.evaluate(dbOverJournal))

        // Precedence 3: Journal > Restore Origin
        val journalOverOrigin = allProvenSnapshot.copy(
            journalResolved = Evidence.NEGATED,
            restoreOriginClassified = Evidence.UNKNOWN
        )
        assertEquals(RestoreBootstrapGateDecision.RECOVER_JOURNAL_FIRST, RestoreBootstrapGatePolicy.evaluate(journalOverOrigin))

        // Precedence 4: Restore Origin > System Restore Barrier
        val originOverBarrier = allProvenSnapshot.copy(
            restoreOriginClassified = Evidence.UNKNOWN,
            systemRestoreBarrierCleared = Evidence.NEGATED
        )
        assertEquals(RestoreBootstrapGateDecision.CLASSIFY_RESTORE_ORIGIN_FIRST, RestoreBootstrapGatePolicy.evaluate(originOverBarrier))
    }

    @Test
    fun `exhaustive 243 input combinations test iff all PROVEN then ALLOW_SHADOW_META_PREPARATION`() {
        val evidenceValues = Evidence.entries
        var totalCombinations = 0
        var allowCount = 0

        for (ce in evidenceValues) {
            for (db in evidenceValues) {
                for (jr in evidenceValues) {
                    for (ro in evidenceValues) {
                        for (sb in evidenceValues) {
                            totalCombinations++
                            val snapshot = RestoreBootstrapGateSnapshot(
                                ceUnlocked = ce,
                                databaseCompatible = db,
                                journalResolved = jr,
                                restoreOriginClassified = ro,
                                systemRestoreBarrierCleared = sb
                            )
                            val decision = RestoreBootstrapGatePolicy.evaluate(snapshot)

                            val isAllProven = (ce == Evidence.PROVEN &&
                                    db == Evidence.PROVEN &&
                                    jr == Evidence.PROVEN &&
                                    ro == Evidence.PROVEN &&
                                    sb == Evidence.PROVEN)

                            if (isAllProven) {
                                assertEquals(RestoreBootstrapGateDecision.ALLOW_SHADOW_META_PREPARATION, decision)
                                allowCount++
                            } else {
                                assertNotEquals(RestoreBootstrapGateDecision.ALLOW_SHADOW_META_PREPARATION, decision)
                            }
                        }
                    }
                }
            }
        }

        assertEquals(243, totalCombinations)
        assertEquals(1, allowCount)
    }

    @Test
    fun `identical snapshots produce identical decisions`() {
        val decision1 = RestoreBootstrapGatePolicy.evaluate(allProvenSnapshot)
        val decision2 = RestoreBootstrapGatePolicy.evaluate(allProvenSnapshot)
        assertEquals(decision1, decision2)
        assertEquals(RestoreBootstrapGateDecision.ALLOW_SHADOW_META_PREPARATION, decision1)
    }
}
