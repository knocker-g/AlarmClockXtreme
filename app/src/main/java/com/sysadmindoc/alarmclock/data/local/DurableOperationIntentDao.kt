package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator

@Dao
abstract class DurableOperationIntentDao {
    /**
     * Inserts an inert durable operation intent using ABORT on conflict.
     * Prevents silent replacement or deletion of historical operation records.
     * Enforced at the SQLite database layer via triggers on both fresh and migrated databases.
     * Note: Insert is a persistence primitive only and does NOT constitute active operation admission.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertRawIntent(intent: DurableOperationIntent)

    /**
     * Inserts a validated durable operation intent.
     * Enforces non-blank identities, positive epoch, and valid phase before insertion.
     */
    open suspend fun insertValidatedIntent(intent: DurableOperationIntent) {
        DurableOperationIntent.validateForInsert(intent)
        insertRawIntent(intent)
    }

    @Query("SELECT * FROM durable_operation_intent WHERE operationToken = :operationToken")
    abstract suspend fun getIntentByToken(operationToken: String): DurableOperationIntent?

    @Query("SELECT * FROM durable_operation_intent WHERE alarmId = :alarmId ORDER BY operationEpoch ASC")
    abstract suspend fun getIntentsByAlarmId(alarmId: Long): List<DurableOperationIntent>

    @Query("SELECT * FROM durable_operation_intent WHERE alarmId = :alarmId AND phase IN ('PENDING', 'IN_FLIGHT')")
    abstract suspend fun getUnresolvedIntentsByAlarmId(alarmId: Long): List<DurableOperationIntent>

    @Query("SELECT * FROM durable_operation_intent WHERE operationKind = 'CANCEL' AND phase IN ('PENDING', 'IN_FLIGHT') ORDER BY operationEpoch ASC")
    abstract suspend fun getUnresolvedCancellationIntents(): List<DurableOperationIntent>

    @Query("UPDATE durable_operation_intent SET phase = :newPhase WHERE operationToken = :operationToken")
    abstract suspend fun updateIntentPhase(operationToken: String, newPhase: String): Int

    @Query(
        """
        UPDATE durable_operation_intent
        SET phase = 'IN_FLIGHT'
        WHERE operationToken = :operationToken
          AND alarmId = :alarmId
          AND operationKind = 'CANCEL'
          AND intentRevision = 2
          AND phase = 'PENDING'
          AND expectedOwnerToken = :expectedOwnerToken
          AND expectedOwnerToken IS NOT NULL
          AND expectedOwnerToken != ''
          AND expectedRestoreEpoch = (SELECT restoreEpoch FROM schedule_authority WHERE alarmId = :alarmId)
          AND expectedScheduleRevision = (SELECT scheduleRevision FROM schedule_authority WHERE alarmId = :alarmId)
          AND operationToken IN (
              SELECT o.issueToken FROM issued_occurrences o
              INNER JOIN issue_slots s ON s.alarmId = o.alarmId
              INNER JOIN schedule_authority sa ON sa.alarmId = o.alarmId
              INNER JOIN alarms a ON a.id = o.alarmId
              WHERE o.issueToken = :operationToken
                AND o.alarmId = :alarmId
                AND o.status = 'PREPARED'
                AND o.fireId = (SELECT originalFireId FROM durable_operation_intent WHERE operationToken = :operationToken)
                AND o.scheduledAt = (SELECT originalScheduledAt FROM durable_operation_intent WHERE operationToken = :operationToken)
                AND o.generationId = :expectedSlotGenId
                AND s.generationId = :expectedSlotGenId
                AND (s.currentIssueToken = :expectedSlotOwnerToken OR (s.currentIssueToken IS NULL AND :expectedSlotOwnerToken IS NULL))
                AND (s.currentIssueToken IS NULL OR s.currentIssueToken != :operationToken)
                AND o.authorityInstanceToken = sa.instanceToken
                AND sa.instanceToken = :expectedOwnerToken
                AND o.restoreEpoch = sa.restoreEpoch
                AND o.scheduleRevision = sa.scheduleRevision
                AND o.definitionRevision = sa.definitionRevision
                AND o.alarmGeneration = a.generation
          )
        """
    )
    abstract suspend fun casTransitionBSpecificCancelIntentToInFlight(
        operationToken: String,
        alarmId: Long,
        expectedSlotGenId: Long,
        expectedSlotOwnerToken: String?,
        expectedOwnerToken: String
    ): Int

    @Transaction
    open suspend fun issueBSpecificCancelIntentTransaction(
        issuedDao: IssuedOccurrenceDao,
        scheduleAuthorityDao: ScheduleAuthorityDao,
        alarmDao: AlarmDao,
        targetIssueToken: String,
        now: Long = System.currentTimeMillis()
    ): DurableOperationIntent {
        // 1. Fetch DB records
        val occB = issuedDao.getOccurrence(targetIssueToken)
            ?: throw IllegalStateException("Occurrence $targetIssueToken missing for cancellation intent")

        val alarmId = occB.alarmId
        val slot = issuedDao.getSlot(alarmId)
            ?: throw IllegalStateException("Slot for alarm $alarmId missing")

        val sa = scheduleAuthorityDao.getAuthorityByAlarmId(alarmId)
            ?: throw IllegalStateException("ScheduleAuthority for alarm $alarmId missing")

        val alarm = alarmDao.getById(alarmId)
            ?: throw IllegalStateException("Alarm $alarmId missing")

        // 2. Validate strict B-specific binding using Production Validator
        val intentCandidate = DurableOperationIntent(
            operationToken = targetIssueToken,
            alarmId = alarmId,
            operationEpoch = now,
            incarnationId = occB.fireId,
            operationKind = "CANCEL",
            expectedOwnerToken = sa.instanceToken,
            expectedScheduleRevision = sa.scheduleRevision,
            expectedRestoreEpoch = sa.restoreEpoch,
            phase = DurableOperationIntent.PHASE_PENDING,
            intentRevision = 2L,
            originalFireId = occB.fireId,
            originalScheduledAt = occB.scheduledAt
        )

        val isAuthentic = DurableCancelIntentValidator.verifyBSpecificCancelBinding(
            intent = intentCandidate,
            targetOccurrence = occB,
            currentSlot = slot
        )

        if (!isAuthentic || occB.alarmGeneration != alarm.generation || occB.definitionRevision != sa.definitionRevision) {
            throw IllegalStateException("Occurrence B fails production validation or DB truth check for alarm $alarmId")
        }

        // 3. Insert validated intent into Room DB (throws SQLiteConstraintException on duplicate PK or unresolved conflict)
        insertValidatedIntent(intentCandidate)

        // 4. Readback intent from DB to verify persistence
        return getIntentByToken(targetIssueToken)
            ?: throw IllegalStateException("Failed to retrieve inserted cancellation intent for $targetIssueToken")
    }

    @Query("UPDATE durable_operation_intent SET phase = 'COMPLETED' WHERE operationToken = :operationToken AND phase IN ('PENDING', 'IN_FLIGHT') AND alarmId = :alarmId")
    abstract suspend fun completeDurableIntentAtomic(operationToken: String, alarmId: Long): Int

    @Query("SELECT * FROM durable_operation_intent ORDER BY operationEpoch ASC")
    abstract suspend fun getAllIntents(): List<DurableOperationIntent>
}
