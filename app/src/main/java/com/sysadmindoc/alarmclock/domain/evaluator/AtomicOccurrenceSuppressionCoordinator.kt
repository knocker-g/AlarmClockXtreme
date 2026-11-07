package com.sysadmindoc.alarmclock.domain.evaluator

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import kotlinx.coroutines.CancellationException

/**
 * Immutable request contract for atomic occurrence suppression (KNO-37 Phase H2-A2).
 * All fields are validated against actual Room records inside a single transaction.
 */
data class AtomicSuppressionRequest(
    val issueToken: String,
    val expectedAlarmId: Long,
    val expectedFireId: String,
    val expectedScheduledAt: Long,
    val expectedBaseTriggerTime: Long,
    val expectedAlarmGeneration: Int,
    val expectedSlotGenerationId: Long,
    val expectedAuthorityFingerprint: ScheduleAuthorityFingerprint,
    val nowEpochMs: Long = System.currentTimeMillis()
)

/**
 * Explicit, typed outcomes for atomic occurrence suppression.
 */
enum class AtomicSuppressionOutcome {
    SUPPRESSED,
    ALREADY_SUPPRESSED,
    NO_ACTIVE_SESSION,
    STALE_IDENTITY,
    STALE_GENERATION,
    STALE_AUTHORITY,
    NOT_CURRENT_SLOT,
    INVALID_STATUS,
    CAS_CONFLICT,
    REPOSITORY_FAILURE
}

/**
 * Result of an atomic suppression operation.
 */
data class AtomicSuppressionResult(
    val outcome: AtomicSuppressionOutcome,
    val diagnosticDetail: String? = null,
    val rowsUpdated: Int = 0
)

/**
 * Inactive, fail-closed coordinator that atomically transitions an issued Normal Occurrence
 * from ISSUED to SUPPRESSED within a single Room database transaction when the same Alarm
 * has an active Session (KNO-37 Phase H2-A2).
 */
class AtomicOccurrenceSuppressionCoordinator(
    private val database: AlarmDatabase
) {

    companion object {
        val ALLOWED_ACTIVE_SESSION_STATES = setOf(
            "FIRING",
            "SNOOZED",
            "WAKE_CHECK_WAITING",
            "WAKE_CHECK_ACTIVE"
        )
    }

    /**
     * Executes guarded atomic occurrence suppression inside a single Room transaction.
     */
    suspend fun suppressOccurrence(request: AtomicSuppressionRequest): AtomicSuppressionResult {
        return try {
            database.withTransaction {
                executeSuppression(request)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.REPOSITORY_FAILURE,
                diagnosticDetail = "Database transaction failed: ${e.message}",
                rowsUpdated = 0
            )
        }
    }

    /**
     * Executes the validation and CAS logic inside the current transaction boundary.
     */
    internal suspend fun executeSuppression(request: AtomicSuppressionRequest): AtomicSuppressionResult {
        val issuedDao = database.issuedOccurrenceDao()
        val alarmDao = database.alarmDao()
        val authorityDao = database.scheduleAuthorityDao()
        val sessionDao = database.alarmSessionDao()

        // 1. Re-read IssuedOccurrence
        val occurrence = issuedDao.getOccurrence(request.issueToken)
            ?: return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_IDENTITY,
                diagnosticDetail = "Occurrence not found: ${request.issueToken}"
            )

        // 2. Re-read current IssueSlot
        val slot = issuedDao.getSlot(request.expectedAlarmId)
            ?: return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.NOT_CURRENT_SLOT,
                diagnosticDetail = "IssueSlot not found for alarm ${request.expectedAlarmId}"
            )

        // 3. Re-read Alarm
        val alarm = alarmDao.getById(request.expectedAlarmId)
            ?: return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_IDENTITY,
                diagnosticDetail = "Alarm not found: ${request.expectedAlarmId}"
            )

        // 4. Re-read ScheduleAuthority
        val authority = authorityDao.getAuthorityByAlarmId(request.expectedAlarmId)
            ?: return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_AUTHORITY,
                diagnosticDetail = "ScheduleAuthority not found for alarm ${request.expectedAlarmId}"
            )

        // 5. Validate full provenance and generation
        if (occurrence.alarmId != request.expectedAlarmId ||
            occurrence.fireId != request.expectedFireId ||
            occurrence.scheduledAt != request.expectedScheduledAt ||
            occurrence.baseTriggerTime != request.expectedBaseTriggerTime ||
            alarm.id != request.expectedAlarmId
        ) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_IDENTITY,
                diagnosticDetail = "Provenance mismatch between request and persisted occurrence/alarm"
            )
        }

        if (alarm.generation != request.expectedAlarmGeneration ||
            occurrence.alarmGeneration != request.expectedAlarmGeneration
        ) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_GENERATION,
                diagnosticDetail = "Generation mismatch: expected ${request.expectedAlarmGeneration}, alarm=${alarm.generation}, occurrence=${occurrence.alarmGeneration}"
            )
        }

        if (slot.generationId != request.expectedSlotGenerationId ||
            occurrence.generationId != request.expectedSlotGenerationId
        ) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.NOT_CURRENT_SLOT,
                diagnosticDetail = "Slot generation mismatch: expected ${request.expectedSlotGenerationId}, slot=${slot.generationId}, occurrence=${occurrence.generationId}"
            )
        }

        if (authority.alarmId != request.expectedAlarmId) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_AUTHORITY,
                diagnosticDetail = "ScheduleAuthority alarmId mismatch: authority=${authority.alarmId}, expected=${request.expectedAlarmId}"
            )
        }

        val currentFingerprint = ScheduleAuthorityFingerprint.fromAuthority(authority)
        if (currentFingerprint != request.expectedAuthorityFingerprint) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_AUTHORITY,
                diagnosticDetail = "Authority fingerprint mismatch: expected ${request.expectedAuthorityFingerprint}, current $currentFingerprint"
            )
        }

        // Validate persisted IssuedOccurrence provenance against actual current ScheduleAuthority
        if (occurrence.authorityInstanceToken.isNullOrBlank() ||
            occurrence.authorityInstanceToken != currentFingerprint.instanceToken
        ) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_AUTHORITY,
                diagnosticDetail = "Occurrence authorityInstanceToken mismatch or blank: occurrence=${occurrence.authorityInstanceToken}, current=${currentFingerprint.instanceToken}"
            )
        }

        if (occurrence.restoreEpoch != currentFingerprint.restoreEpoch) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_AUTHORITY,
                diagnosticDetail = "Occurrence restoreEpoch mismatch: occurrence=${occurrence.restoreEpoch}, current=${currentFingerprint.restoreEpoch}"
            )
        }

        if (occurrence.scheduleRevision != currentFingerprint.scheduleRevision) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_AUTHORITY,
                diagnosticDetail = "Occurrence scheduleRevision mismatch: occurrence=${occurrence.scheduleRevision}, current=${currentFingerprint.scheduleRevision}"
            )
        }

        if (occurrence.definitionRevision != currentFingerprint.definitionRevision) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.STALE_AUTHORITY,
                diagnosticDetail = "Occurrence definitionRevision mismatch: occurrence=${occurrence.definitionRevision}, current=${currentFingerprint.definitionRevision}"
            )
        }

        // 6. Validate IssueSlot.currentIssueToken
        if (slot.currentIssueToken != request.issueToken) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.NOT_CURRENT_SLOT,
                diagnosticDetail = "Slot currentIssueToken (${slot.currentIssueToken}) does not match request (${request.issueToken})"
            )
        }

        // Idempotency: Return ALREADY_SUPPRESSED only after canonical identity verification
        if (occurrence.status == IssuedOccurrence.STATUS_SUPPRESSED) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.ALREADY_SUPPRESSED,
                diagnosticDetail = "Occurrence ${request.issueToken} is already SUPPRESSED",
                rowsUpdated = 0
            )
        }

        // Validate status is ISSUED
        if (occurrence.status != IssuedOccurrence.STATUS_ISSUED) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.INVALID_STATUS,
                diagnosticDetail = "Occurrence status is ${occurrence.status}, expected ISSUED"
            )
        }

        // 7. Confirm active Session for SAME alarm
        val activeSessions = sessionDao.getActiveSessionsForAlarm(request.expectedAlarmId)
        val hasActiveSession = activeSessions.any { session ->
            session.alarmId == request.expectedAlarmId && session.sessionState in ALLOWED_ACTIVE_SESSION_STATES
        }
        if (!hasActiveSession) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.NO_ACTIVE_SESSION,
                diagnosticDetail = "No active session in states $ALLOWED_ACTIVE_SESSION_STATES found for alarm ${request.expectedAlarmId}"
            )
        }

        // 8. Perform guarded status CAS: ISSUED -> SUPPRESSED
        val updatedCount = issuedDao.casUpdateOccurrenceStatus(
            issueToken = request.issueToken,
            expectedStatus = IssuedOccurrence.STATUS_ISSUED,
            newStatus = IssuedOccurrence.STATUS_SUPPRESSED,
            updatedAt = request.nowEpochMs
        )

        // 9. Require updated row count exactly 1
        if (updatedCount != 1) {
            return AtomicSuppressionResult(
                outcome = AtomicSuppressionOutcome.CAS_CONFLICT,
                diagnosticDetail = "Guarded status CAS failed: expected 1 row updated, got $updatedCount",
                rowsUpdated = updatedCount
            )
        }

        return AtomicSuppressionResult(
            outcome = AtomicSuppressionOutcome.SUPPRESSED,
            diagnosticDetail = "Occurrence ${request.issueToken} successfully suppressed",
            rowsUpdated = 1
        )
    }
}
