package com.sysadmindoc.alarmclock.domain

enum class Evidence {
    PROVEN,
    NEGATED,
    UNKNOWN
}

enum class RestoreBootstrapGateDecision {
    ALLOW_SHADOW_META_PREPARATION,
    DEFER_CE_LOCKED,
    BLOCK_CE_UNKNOWN,
    STOP_DATABASE_INCOMPATIBLE,
    BLOCK_DATABASE_UNKNOWN,
    RECOVER_JOURNAL_FIRST,
    BLOCK_JOURNAL_UNKNOWN,
    CLASSIFY_RESTORE_ORIGIN_FIRST,
    BLOCK_RESTORE_ORIGIN_NEGATED,
    COMPLETE_SYSTEM_RESTORE_BARRIER_FIRST,
    BLOCK_SYSTEM_RESTORE_BARRIER_UNKNOWN
}

data class RestoreBootstrapGateSnapshot(
    val ceUnlocked: Evidence,
    val databaseCompatible: Evidence,
    val journalResolved: Evidence,
    val restoreOriginClassified: Evidence,
    val systemRestoreBarrierCleared: Evidence
)

object RestoreBootstrapGatePolicy {

    /**
     * Evaluates a restore bootstrap gate snapshot according to the normative
     * KNO-227 P1B1c1a Design Lock v29 decision precedence order.
     */
    fun evaluate(snapshot: RestoreBootstrapGateSnapshot): RestoreBootstrapGateDecision {
        // 1. CE Unlock Evidence Check
        when (snapshot.ceUnlocked) {
            Evidence.NEGATED -> return RestoreBootstrapGateDecision.DEFER_CE_LOCKED
            Evidence.UNKNOWN -> return RestoreBootstrapGateDecision.BLOCK_CE_UNKNOWN
            Evidence.PROVEN -> { /* proceed */ }
        }

        // 2. Database Compatibility Evidence Check
        when (snapshot.databaseCompatible) {
            Evidence.NEGATED -> return RestoreBootstrapGateDecision.STOP_DATABASE_INCOMPATIBLE
            Evidence.UNKNOWN -> return RestoreBootstrapGateDecision.BLOCK_DATABASE_UNKNOWN
            Evidence.PROVEN -> { /* proceed */ }
        }

        // 3. Restore Journal Evidence Check
        when (snapshot.journalResolved) {
            Evidence.NEGATED -> return RestoreBootstrapGateDecision.RECOVER_JOURNAL_FIRST
            Evidence.UNKNOWN -> return RestoreBootstrapGateDecision.BLOCK_JOURNAL_UNKNOWN
            Evidence.PROVEN -> { /* proceed */ }
        }

        // 4. Restore Origin Classification Evidence Check
        when (snapshot.restoreOriginClassified) {
            Evidence.UNKNOWN -> return RestoreBootstrapGateDecision.CLASSIFY_RESTORE_ORIGIN_FIRST
            Evidence.NEGATED -> return RestoreBootstrapGateDecision.BLOCK_RESTORE_ORIGIN_NEGATED
            Evidence.PROVEN -> { /* proceed */ }
        }

        // 5. System Restore Barrier Evidence Check
        when (snapshot.systemRestoreBarrierCleared) {
            Evidence.NEGATED -> return RestoreBootstrapGateDecision.COMPLETE_SYSTEM_RESTORE_BARRIER_FIRST
            Evidence.UNKNOWN -> return RestoreBootstrapGateDecision.BLOCK_SYSTEM_RESTORE_BARRIER_UNKNOWN
            Evidence.PROVEN -> { /* proceed */ }
        }

        // 6. All 5 Evidence fields == PROVEN -> ALLOW_SHADOW_META_PREPARATION
        return RestoreBootstrapGateDecision.ALLOW_SHADOW_META_PREPARATION
    }
}
