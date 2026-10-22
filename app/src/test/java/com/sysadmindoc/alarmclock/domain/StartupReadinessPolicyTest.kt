package com.sysadmindoc.alarmclock.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StartupReadinessPolicyTest {

    private val readyUnlockedSnapshot = StartupReadinessSnapshot(
        userUnlocked = true,
        databaseState = DatabaseState.COMPATIBLE,
        journalState = JournalState.NONE,
        authorityState = AuthorityState.ENFORCED,
        ownershipCoverage = OwnershipCoverage.COMPLETE
    )

    @Test
    fun `locked user always produces DEFER_UNTIL_UNLOCK regardless of database or journal errors`() {
        val snapshot = readyUnlockedSnapshot.copy(
            userUnlocked = false,
            databaseState = DatabaseState.INCOMPATIBLE_NEWER,
            journalState = JournalState.CORRUPT,
            authorityState = AuthorityState.CORRUPT,
            ownershipCoverage = OwnershipCoverage.UNKNOWN
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.DEFER_UNTIL_UNLOCK, decision)
    }

    @Test
    fun `unlocked user with incompatible database produces STOP_INCOMPATIBLE_DATABASE`() {
        val snapshot = readyUnlockedSnapshot.copy(
            databaseState = DatabaseState.INCOMPATIBLE_NEWER
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.STOP_INCOMPATIBLE_DATABASE, decision)
    }

    @Test
    fun `unlocked user with unknown database produces STOP_UNKNOWN_DATABASE`() {
        val snapshot = readyUnlockedSnapshot.copy(
            databaseState = DatabaseState.UNKNOWN
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.STOP_UNKNOWN_DATABASE, decision)
    }

    @Test
    fun `unlocked user with corrupt journal produces BLOCK_CORRUPT_JOURNAL`() {
        val snapshot = readyUnlockedSnapshot.copy(
            journalState = JournalState.CORRUPT
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.BLOCK_CORRUPT_JOURNAL, decision)
    }

    @Test
    fun `unlocked user with unknown journal produces INSPECT_UNKNOWN_JOURNAL`() {
        val snapshot = readyUnlockedSnapshot.copy(
            journalState = JournalState.UNKNOWN
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.INSPECT_UNKNOWN_JOURNAL, decision)
    }

    @Test
    fun `PREPARED journal recovery takes precedence over COMMITTED and authority states`() {
        val preparedSnapshot = readyUnlockedSnapshot.copy(
            journalState = JournalState.PREPARED,
            authorityState = AuthorityState.ABSENT_LEGACY
        )
        val decision = StartupReadinessPolicy.evaluate(preparedSnapshot)
        assertEquals(StartupReadinessDecision.RECOVER_PREPARED_FIRST, decision)
    }

    @Test
    fun `COMMITTED journal recovery produces RECOVER_COMMITTED_FIRST`() {
        val committedSnapshot = readyUnlockedSnapshot.copy(
            journalState = JournalState.COMMITTED,
            authorityState = AuthorityState.ENFORCED
        )
        val decision = StartupReadinessPolicy.evaluate(committedSnapshot)
        assertEquals(StartupReadinessDecision.RECOVER_COMMITTED_FIRST, decision)
    }

    @Test
    fun `corrupt authority state produces BLOCK_CORRUPT_AUTHORITY`() {
        val snapshot = readyUnlockedSnapshot.copy(
            authorityState = AuthorityState.CORRUPT
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.BLOCK_CORRUPT_AUTHORITY, decision)
    }

    @Test
    fun `unknown authority state produces INSPECT_UNKNOWN_AUTHORITY`() {
        val snapshot = readyUnlockedSnapshot.copy(
            authorityState = AuthorityState.UNKNOWN
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.INSPECT_UNKNOWN_AUTHORITY, decision)
    }

    @Test
    fun `legacy absent authority state produces REQUIRE_BOOTSTRAP`() {
        val snapshot = readyUnlockedSnapshot.copy(
            authorityState = AuthorityState.ABSENT_LEGACY,
            ownershipCoverage = OwnershipCoverage.UNKNOWN
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.REQUIRE_BOOTSTRAP, decision)
    }

    @Test
    fun `interrupted preparing authority state produces RESUME_BOOTSTRAP`() {
        val snapshot = readyUnlockedSnapshot.copy(
            authorityState = AuthorityState.PREPARING,
            ownershipCoverage = OwnershipCoverage.INCOMPLETE
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.RESUME_BOOTSTRAP, decision)
    }

    @Test
    fun `ENFORCED authority with incomplete ownership coverage produces BLOCK_ENFORCEMENT_INCOMPLETE`() {
        val snapshot = readyUnlockedSnapshot.copy(
            authorityState = AuthorityState.ENFORCED,
            ownershipCoverage = OwnershipCoverage.INCOMPLETE
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.BLOCK_ENFORCEMENT_INCOMPLETE, decision)
    }

    @Test
    fun `ENFORCED authority with unknown ownership coverage produces BLOCK_ENFORCEMENT_INCOMPLETE`() {
        val snapshot = readyUnlockedSnapshot.copy(
            authorityState = AuthorityState.ENFORCED,
            ownershipCoverage = OwnershipCoverage.UNKNOWN
        )
        val decision = StartupReadinessPolicy.evaluate(snapshot)
        assertEquals(StartupReadinessDecision.BLOCK_ENFORCEMENT_INCOMPLETE, decision)
    }

    @Test
    fun `ENFORCED authority with complete ownership coverage produces VERIFY_ENFORCEMENT_READINESS`() {
        val decision = StartupReadinessPolicy.evaluate(readyUnlockedSnapshot)
        assertEquals(StartupReadinessDecision.VERIFY_ENFORCEMENT_READINESS, decision)
    }

    @Test
    fun `priority conflict order is strictly obeyed`() {
        // Precedence 1: Lock > Database
        val lockOverDb = readyUnlockedSnapshot.copy(
            userUnlocked = false,
            databaseState = DatabaseState.INCOMPATIBLE_NEWER
        )
        assertEquals(StartupReadinessDecision.DEFER_UNTIL_UNLOCK, StartupReadinessPolicy.evaluate(lockOverDb))

        // Precedence 2: Database > Journal
        val dbOverJournal = readyUnlockedSnapshot.copy(
            databaseState = DatabaseState.INCOMPATIBLE_NEWER,
            journalState = JournalState.CORRUPT
        )
        assertEquals(StartupReadinessDecision.STOP_INCOMPATIBLE_DATABASE, StartupReadinessPolicy.evaluate(dbOverJournal))

        // Precedence 3: Journal > Authority
        val journalOverAuthority = readyUnlockedSnapshot.copy(
            journalState = JournalState.CORRUPT,
            authorityState = AuthorityState.CORRUPT
        )
        assertEquals(StartupReadinessDecision.BLOCK_CORRUPT_JOURNAL, StartupReadinessPolicy.evaluate(journalOverAuthority))
    }

    @Test
    fun `identical snapshots produce identical decisions`() {
        val decision1 = StartupReadinessPolicy.evaluate(readyUnlockedSnapshot)
        val decision2 = StartupReadinessPolicy.evaluate(readyUnlockedSnapshot)
        assertEquals(decision1, decision2)
        assertEquals(StartupReadinessDecision.VERIFY_ENFORCEMENT_READINESS, decision1)
    }
}
