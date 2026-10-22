package com.sysadmindoc.alarmclock.domain

enum class DatabaseState {
    COMPATIBLE,
    INCOMPATIBLE_NEWER,
    UNKNOWN
}

enum class JournalState {
    NONE,
    PREPARED,
    COMMITTED,
    CORRUPT,
    UNKNOWN
}

enum class AuthorityState {
    ABSENT_LEGACY,
    PREPARING,
    ENFORCED,
    CORRUPT,
    UNKNOWN
}

enum class OwnershipCoverage {
    COMPLETE,
    INCOMPLETE,
    UNKNOWN
}

enum class StartupReadinessDecision {
    DEFER_UNTIL_UNLOCK,
    STOP_INCOMPATIBLE_DATABASE,
    STOP_UNKNOWN_DATABASE,
    BLOCK_CORRUPT_JOURNAL,
    INSPECT_UNKNOWN_JOURNAL,
    RECOVER_PREPARED_FIRST,
    RECOVER_COMMITTED_FIRST,
    BLOCK_CORRUPT_AUTHORITY,
    INSPECT_UNKNOWN_AUTHORITY,
    REQUIRE_BOOTSTRAP,
    RESUME_BOOTSTRAP,
    BLOCK_ENFORCEMENT_INCOMPLETE,
    VERIFY_ENFORCEMENT_READINESS
}

data class StartupReadinessSnapshot(
    val userUnlocked: Boolean,
    val databaseState: DatabaseState,
    val journalState: JournalState,
    val authorityState: AuthorityState,
    val ownershipCoverage: OwnershipCoverage
)

object StartupReadinessPolicy {

    /**
     * Evaluates a startup readiness snapshot according to the normative
     * KNO-227 P1B0 Design Lock decision precedence order.
     */
    fun evaluate(snapshot: StartupReadinessSnapshot): StartupReadinessDecision {
        // 1. Locked User -> DEFER_UNTIL_UNLOCK
        if (!snapshot.userUnlocked) {
            return StartupReadinessDecision.DEFER_UNTIL_UNLOCK
        }

        // 2. Database Compatibility Checks
        when (snapshot.databaseState) {
            DatabaseState.INCOMPATIBLE_NEWER -> return StartupReadinessDecision.STOP_INCOMPATIBLE_DATABASE
            DatabaseState.UNKNOWN -> return StartupReadinessDecision.STOP_UNKNOWN_DATABASE
            DatabaseState.COMPATIBLE -> { /* proceed */ }
        }

        // 3. Restore Journal Checks
        when (snapshot.journalState) {
            JournalState.CORRUPT -> return StartupReadinessDecision.BLOCK_CORRUPT_JOURNAL
            JournalState.UNKNOWN -> return StartupReadinessDecision.INSPECT_UNKNOWN_JOURNAL
            JournalState.PREPARED -> return StartupReadinessDecision.RECOVER_PREPARED_FIRST
            JournalState.COMMITTED -> return StartupReadinessDecision.RECOVER_COMMITTED_FIRST
            JournalState.NONE -> { /* proceed */ }
        }

        // 4. Schedule Authority & Coverage Checks
        return when (snapshot.authorityState) {
            AuthorityState.CORRUPT -> StartupReadinessDecision.BLOCK_CORRUPT_AUTHORITY
            AuthorityState.UNKNOWN -> StartupReadinessDecision.INSPECT_UNKNOWN_AUTHORITY
            AuthorityState.ABSENT_LEGACY -> StartupReadinessDecision.REQUIRE_BOOTSTRAP
            AuthorityState.PREPARING -> StartupReadinessDecision.RESUME_BOOTSTRAP
            AuthorityState.ENFORCED -> {
                when (snapshot.ownershipCoverage) {
                    OwnershipCoverage.COMPLETE -> StartupReadinessDecision.VERIFY_ENFORCEMENT_READINESS
                    OwnershipCoverage.INCOMPLETE,
                    OwnershipCoverage.UNKNOWN -> StartupReadinessDecision.BLOCK_ENFORCEMENT_INCOMPLETE
                }
            }
        }
    }
}
