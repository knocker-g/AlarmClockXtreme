package com.sysadmindoc.alarmclock.domain.evaluator

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AtomicOccurrenceSuppressionCoordinatorTest {

    private lateinit var context: Context
    private lateinit var dbFile: File
    private lateinit var db: AlarmDatabase
    private lateinit var coordinator: AtomicOccurrenceSuppressionCoordinator
    private lateinit var verifier: OccurrenceIssuanceProvenanceVerifier

    private val alarmId = 100L
    private val nowMs = 1793796000000L
    private val scheduledAt = 1793796000000L
    private val baseTriggerTime = 1793796000000L
    private val fireId = "fire-100"
    private val token = "issue_token_100"
    private val generationId = 2L
    private val alarmGen = 1

    private val authority = ScheduleAuthority(
        alarmId = alarmId,
        instanceToken = "inst-token-100",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "ACTIVE",
        desiredOperation = "FIRE",
        desiredTriggerMillis = scheduledAt,
        reconcileState = "RECONCILED",
        restoreEpoch = 1000L,
        actionKey = "action-100"
    )

    private val fingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = context.getDatabasePath("test_atomic_suppression.db")
        if (dbFile.exists()) dbFile.delete()
        dbFile.parentFile?.mkdirs()

        db = createDatabase(dbFile)
        coordinator = AtomicOccurrenceSuppressionCoordinator(db)
        verifier = OccurrenceIssuanceProvenanceVerifier(db)
    }

    private fun createDatabase(file: File): AlarmDatabase {
        return Room.databaseBuilder(context, AlarmDatabase::class.java, file.absolutePath)
            .allowMainThreadQueries()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(AlarmDatabase.DATABASE_CALLBACK)
            .build()
    }

    @After
    fun tearDown() {
        try { db.close() } catch (_: Exception) {}
        if (dbFile.exists()) dbFile.delete()
    }

    private suspend fun seedBaseFixtures(
        occurrenceStatus: String = IssuedOccurrence.STATUS_ISSUED,
        slotCurrentToken: String? = token,
        slotGen: Long = generationId,
        alarmGeneration: Int = alarmGen,
        authorityFingerprintToUse: ScheduleAuthority = authority
    ) {
        val alarm = Alarm(
            id = alarmId,
            hour = 7,
            minute = 0,
            isEnabled = true,
            generation = alarmGeneration,
            revision = 1
        )
        db.alarmDao().insert(alarm)

        db.scheduleAuthorityDao().insertAuthority(authorityFingerprintToUse)

        val slot = IssueSlot(
            alarmId = alarmId,
            currentIssueToken = slotCurrentToken,
            generationId = slotGen
        )
        db.issuedOccurrenceDao().insertSlotInitial(slot)

        val occurrence = IssuedOccurrence(
            issueToken = token,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = fireId,
            generationId = slotGen,
            status = occurrenceStatus,
            baseTriggerTime = baseTriggerTime,
            alarmGeneration = alarmGeneration,
            authorityInstanceToken = authorityFingerprintToUse.instanceToken,
            restoreEpoch = authorityFingerprintToUse.restoreEpoch,
            scheduleRevision = authorityFingerprintToUse.scheduleRevision,
            definitionRevision = authorityFingerprintToUse.definitionRevision,
            createdAt = nowMs,
            updatedAt = nowMs
        )
        db.issuedOccurrenceDao().insertOccurrence(occurrence)
    }

    private fun createSessionEntity(
        sessionId: String = UUID.randomUUID().toString(),
        targetAlarmId: Long = alarmId,
        state: String = "FIRING"
    ): AlarmSessionEntity {
        return AlarmSessionEntity(
            sessionId = sessionId,
            admissionKey = "adm_${targetAlarmId}_$sessionId",
            alarmId = targetAlarmId,
            originKind = "NORMAL",
            originScheduleKind = "ONE_TIME",
            generation = 1,
            revision = 1,
            fireId = fireId,
            occurrenceKey = "occ-100",
            canonicalBaseTriggerMs = nowMs,
            scheduledTargetEpochMs = nowMs,
            sessionState = state,
            sessionVersion = 1L,
            creationSequence = 1L,
            createdAtEpochMs = nowMs,
            createdElapsedRealtimeMs = 1000L,
            bootCount = 1L,
            updatedAtEpochMs = nowMs,
            absoluteDeadlineEpochMs = nowMs + 18_000_000L,
            configurationSnapshotJson = "{}"
        )
    }

    private fun createValidRequest(): AtomicSuppressionRequest {
        return AtomicSuppressionRequest(
            issueToken = token,
            expectedAlarmId = alarmId,
            expectedFireId = fireId,
            expectedScheduledAt = scheduledAt,
            expectedBaseTriggerTime = baseTriggerTime,
            expectedAlarmGeneration = alarmGen,
            expectedSlotGenerationId = generationId,
            expectedAuthorityFingerprint = fingerprint,
            nowEpochMs = nowMs + 1000L
        )
    }

    // T01: valid active FIRING -> SUPPRESSED
    @Test
    fun T01_validActiveFiring_transitionsToSuppressed() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val result = coordinator.suppressOccurrence(createValidRequest())

        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)
        assertEquals(1, result.rowsUpdated)

        val updated = db.issuedOccurrenceDao().getOccurrence(token)
        assertNotNull(updated)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, updated?.status)
    }

    // T02: valid active SNOOZED -> SUPPRESSED
    @Test
    fun T02_validActiveSnoozed_transitionsToSuppressed() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "SNOOZED"))

        val result = coordinator.suppressOccurrence(createValidRequest())

        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)
        assertEquals(1, result.rowsUpdated)

        val updated = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, updated?.status)
    }

    // T03: WAKE_CHECK_WAITING -> SUPPRESSED
    @Test
    fun T03_validActiveWakeCheckWaiting_transitionsToSuppressed() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "WAKE_CHECK_WAITING"))

        val result = coordinator.suppressOccurrence(createValidRequest())

        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)
        assertEquals(1, result.rowsUpdated)

        val updated = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, updated?.status)
    }

    // T04: WAKE_CHECK_ACTIVE -> SUPPRESSED
    @Test
    fun T04_validActiveWakeCheckActive_transitionsToSuppressed() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "WAKE_CHECK_ACTIVE"))

        val result = coordinator.suppressOccurrence(createValidRequest())

        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)
        assertEquals(1, result.rowsUpdated)

        val updated = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, updated?.status)
    }

    // T05: no active Session -> no mutation
    @Test
    fun T05_noActiveSession_returnsNoActiveSessionAndDoesNotMutate() = runBlocking {
        seedBaseFixtures()
        // Insert a COMPLETED session (not active)
        db.alarmSessionDao().insertSession(createSessionEntity(state = "COMPLETED"))

        val result = coordinator.suppressOccurrence(createValidRequest())

        assertEquals(AtomicSuppressionOutcome.NO_ACTIVE_SESSION, result.outcome)
        assertEquals(0, result.rowsUpdated)

        val occurrence = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occurrence?.status)
    }

    // T06: duplicate suppression -> idempotent
    @Test
    fun T06_duplicateSuppression_returnsAlreadySuppressed() = runBlocking {
        seedBaseFixtures(occurrenceStatus = IssuedOccurrence.STATUS_SUPPRESSED)
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val result = coordinator.suppressOccurrence(createValidRequest())

        assertEquals(AtomicSuppressionOutcome.ALREADY_SUPPRESSED, result.outcome)
        assertEquals(0, result.rowsUpdated)

        val occurrence = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, occurrence?.status)
    }

    // T07: stale alarm generation -> reject
    @Test
    fun T07_staleAlarmGeneration_returnsStaleGeneration() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val staleRequest = createValidRequest().copy(expectedAlarmGeneration = 99)
        val result = coordinator.suppressOccurrence(staleRequest)

        assertEquals(AtomicSuppressionOutcome.STALE_GENERATION, result.outcome)
        assertEquals(0, result.rowsUpdated)

        val occurrence = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occurrence?.status)
    }

    // T08: stale authority fingerprint -> reject
    @Test
    fun T08_staleAuthorityFingerprint_returnsStaleAuthority() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val staleFingerprint = fingerprint.copy(scheduleRevision = 999L)
        val staleRequest = createValidRequest().copy(expectedAuthorityFingerprint = staleFingerprint)
        val result = coordinator.suppressOccurrence(staleRequest)

        assertEquals(AtomicSuppressionOutcome.STALE_AUTHORITY, result.outcome)
        assertEquals(0, result.rowsUpdated)

        val occurrence = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occurrence?.status)
    }

    // T09: wrong current IssueSlot -> reject
    @Test
    fun T09_wrongCurrentIssueSlot_returnsNotCurrentSlot() = runBlocking {
        seedBaseFixtures(slotCurrentToken = "different_token")
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val result = coordinator.suppressOccurrence(createValidRequest())

        assertEquals(AtomicSuppressionOutcome.NOT_CURRENT_SLOT, result.outcome)
        assertEquals(0, result.rowsUpdated)

        val occurrence = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occurrence?.status)
    }

    // T10: wrong fireId / scheduledAt -> reject
    @Test
    fun T10_wrongFireIdOrScheduledAt_returnsStaleIdentity() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val wrongFireIdRequest = createValidRequest().copy(expectedFireId = "wrong_fire_id")
        val result1 = coordinator.suppressOccurrence(wrongFireIdRequest)
        assertEquals(AtomicSuppressionOutcome.STALE_IDENTITY, result1.outcome)

        val wrongScheduledAtRequest = createValidRequest().copy(expectedScheduledAt = 12345L)
        val result2 = coordinator.suppressOccurrence(wrongScheduledAtRequest)
        assertEquals(AtomicSuppressionOutcome.STALE_IDENTITY, result2.outcome)

        val occurrence = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occurrence?.status)
    }

    // T11: revoked or admitted status -> reject
    @Test
    fun T11_revokedOrAdmittedStatus_returnsInvalidStatus() = runBlocking {
        seedBaseFixtures(occurrenceStatus = IssuedOccurrence.STATUS_REVOKED)
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val resultRevoked = coordinator.suppressOccurrence(createValidRequest())
        assertEquals(AtomicSuppressionOutcome.INVALID_STATUS, resultRevoked.outcome)

        val updatedRevoked = IssuedOccurrence(
            issueToken = "admitted_token",
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = fireId,
            generationId = generationId,
            status = IssuedOccurrence.STATUS_ADMITTED,
            baseTriggerTime = baseTriggerTime,
            alarmGeneration = alarmGen,
            authorityInstanceToken = authority.instanceToken,
            restoreEpoch = authority.restoreEpoch,
            scheduleRevision = authority.scheduleRevision,
            definitionRevision = authority.definitionRevision
        )
        db.issuedOccurrenceDao().insertOccurrence(updatedRevoked)
        db.issuedOccurrenceDao().casUpdateSlotCurrentTokenOnly(alarmId, generationId, token, "admitted_token")

        val resultAdmitted = coordinator.suppressOccurrence(createValidRequest().copy(issueToken = "admitted_token"))
        assertEquals(AtomicSuppressionOutcome.INVALID_STATUS, resultAdmitted.outcome)
    }

    // T12: competing transaction / CAS -> one winner
    @Test
    fun T12_competingTransactionCas_onlyOneWinner() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        // Simulate racing CAS: concurrent transaction changes status to SUPPRESSED first
        db.issuedOccurrenceDao().casUpdateOccurrenceStatus(
            issueToken = token,
            expectedStatus = IssuedOccurrence.STATUS_ISSUED,
            newStatus = IssuedOccurrence.STATUS_REVOKED,
            updatedAt = nowMs
        )

        // Now coordinator tries CAS expecting ISSUED -> fails
        val result = coordinator.suppressOccurrence(createValidRequest())
        assertEquals(AtomicSuppressionOutcome.INVALID_STATUS, result.outcome)
        assertEquals(0, result.rowsUpdated)
    }

    // T13: no tombstone or Session created
    @Test
    fun T13_noTombstoneOrSessionCreated() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val sessionsBefore = db.alarmSessionDao().getActiveSessions().size
        val seqBefore = db.alarmSessionDao().getLastSequence()

        val result = coordinator.suppressOccurrence(createValidRequest())
        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)

        val sessionsAfter = db.alarmSessionDao().getActiveSessions().size
        val seqAfter = db.alarmSessionDao().getLastSequence()

        assertEquals(sessionsBefore, sessionsAfter)
        assertEquals(seqBefore, seqAfter)
    }

    // T14: rollback leaves original state intact
    @Test
    fun T14_rollbackLeavesOriginalStateIntact() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        try {
            db.withTransaction {
                val res = coordinator.executeSuppression(createValidRequest())
                assertEquals(AtomicSuppressionOutcome.SUPPRESSED, res.outcome)
                throw RuntimeException("Simulated post-CAS failure to force transaction rollback")
            }
            fail("Expected exception was not thrown")
        } catch (_: RuntimeException) {
            // Expected
        }

        // Verify state in DB rolled back and remains ISSUED
        val occurrence = db.issuedOccurrenceDao().getOccurrence(token)
        assertEquals(IssuedOccurrence.STATUS_ISSUED, occurrence?.status)
    }

    // T15: subsequent existing fire-safety check rejects committed SUPPRESSED
    @Test
    fun T15_subsequentFireSafetyCheckRejectsCommittedSuppressed() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        // Suppress the occurrence
        val result = coordinator.suppressOccurrence(createValidRequest())
        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)

        // Verify via OccurrenceIssuanceProvenanceVerifier
        val decision = verifier.verifyAndPlanFireSafety(
            alarmId = alarmId,
            firedTargetEpochMs = scheduledAt,
            firedBaseTriggerTimeEpochMs = baseTriggerTime,
            firedGeneration = alarmGen,
            issueToken = token
        )

        assertEquals(FireSafetyOutcome.UNVERIFIED_INTENT_DATA, decision.outcome)
        assertFalse("Fire admission must NOT be confirmed", decision.isFireAdmissionConfirmed)
        assertTrue("Fire suppression MUST be required", decision.isFireSuppressionRequired)
        assertEquals(NormalAlarmFireSafetyPlanner.REASON_SUPPRESSED_ISSUANCE, decision.decisionReason)
    }

    // --- RECOVERY FENCE TESTS (TASK 6) ---

    // T16: Suppressed remains suppressed after DB reopen
    @Test
    fun T16_recoveryFence_suppressedRemainsAfterDatabaseReopen() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val result = coordinator.suppressOccurrence(createValidRequest())
        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)

        // Close and reopen database
        db.close()
        db = createDatabase(dbFile)

        val reopened = db.issuedOccurrenceDao().getOccurrence(token)
        assertNotNull(reopened)
        assertEquals(IssuedOccurrence.STATUS_SUPPRESSED, reopened?.status)
    }

    // T17: Fallback lease cannot be claimed on SUPPRESSED occurrence
    @Test
    fun T17_recoveryFence_fallbackLeaseCannotBeClaimedOnSuppressed() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val result = coordinator.suppressOccurrence(createValidRequest())
        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)

        val leaseResult = db.issuedOccurrenceDao().claimFallbackLease(
            issueToken = token,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = fireId,
            leaseToken = "lease_token_test",
            expiresAt = nowMs + 60_000L,
            now = nowMs
        )

        assertEquals("Fallback lease claim MUST fail (0 rows updated) for SUPPRESSED occurrence", 0, leaseResult)
    }

    // T18: Cannot replay as active occurrence admission
    @Test
    fun T18_recoveryFence_cannotReplayAsActiveOccurrenceAdmission() = runBlocking {
        seedBaseFixtures()
        db.alarmSessionDao().insertSession(createSessionEntity(state = "FIRING"))

        val result = coordinator.suppressOccurrence(createValidRequest())
        assertEquals(AtomicSuppressionOutcome.SUPPRESSED, result.outcome)

        val isAdmitted = db.issuedOccurrenceDao().evaluateActiveOccurrenceAdmission(
            issueToken = token,
            expectedAlarmId = alarmId,
            expectedScheduledAt = scheduledAt,
            expectedFireId = fireId
        )

        assertFalse("SUPPRESSED occurrence must NOT be admitted by evaluateActiveOccurrenceAdmission", isAdmitted)
    }
}
