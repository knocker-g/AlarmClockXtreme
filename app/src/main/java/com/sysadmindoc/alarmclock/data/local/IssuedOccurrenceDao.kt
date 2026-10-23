package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence

@Dao
abstract class IssuedOccurrenceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertOccurrence(occurrence: IssuedOccurrence): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertSlotInitial(slot: IssueSlot): Long

    @Query("SELECT * FROM issue_slots WHERE alarmId = :alarmId")
    abstract suspend fun getSlot(alarmId: Long): IssueSlot?

    @Query("SELECT * FROM issued_occurrences WHERE issueToken = :issueToken")
    abstract suspend fun getOccurrence(issueToken: String): IssuedOccurrence?

    @Query("SELECT * FROM issued_occurrences WHERE alarmId = :alarmId AND scheduledAt = :scheduledAt")
    abstract suspend fun getOccurrencesByAlarmAndScheduledAt(alarmId: Long, scheduledAt: Long): List<IssuedOccurrence>

    @Query("SELECT currentIssueToken FROM issue_slots WHERE alarmId = :alarmId")
    abstract suspend fun getSlotCurrentToken(alarmId: Long): String?

    @Query("UPDATE issued_occurrences SET status = :newStatus, updatedAt = :updatedAt WHERE issueToken = :issueToken AND status = :expectedStatus")
    abstract suspend fun casUpdateOccurrenceStatus(issueToken: String, expectedStatus: String, newStatus: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE issue_slots
        SET currentIssueToken = :newToken, generationId = generationId + 1
        WHERE alarmId = :alarmId
          AND generationId = :expectedGenId
          AND generationId < 9223372036854775807
          AND (currentIssueToken = :expectedToken OR (currentIssueToken IS NULL AND :expectedToken IS NULL))
        """
    )
    abstract suspend fun casIncrementSlotGeneration(
        alarmId: Long,
        expectedGenId: Long,
        expectedToken: String?,
        newToken: String?
    ): Int

    @Query(
        """
        UPDATE issue_slots
        SET currentIssueToken = :newToken
        WHERE alarmId = :alarmId
          AND generationId = :expectedGenId
          AND (currentIssueToken = :expectedToken OR (currentIssueToken IS NULL AND :expectedToken IS NULL))
        """
    )
    abstract suspend fun casUpdateSlotCurrentTokenOnly(
        alarmId: Long,
        expectedGenId: Long,
        expectedToken: String?,
        newToken: String?
    ): Int

    @Query("UPDATE issued_occurrences SET status = 'REVOKED', updatedAt = :updatedAt WHERE alarmId = :alarmId AND status IN ('PREPARED', 'ISSUED')")
    abstract suspend fun revokePendingOccurrencesForAlarm(alarmId: Long, updatedAt: Long): Int

    @Transaction
    open suspend fun reserveIssuedOccurrenceTransaction(
        issueToken: String,
        alarmId: Long,
        scheduledAt: Long,
        fireId: String,
        now: Long = System.currentTimeMillis()
    ): IssuedOccurrence {
        var slot = getSlot(alarmId)
        if (slot == null) {
            insertSlotInitial(IssueSlot(alarmId = alarmId, currentIssueToken = null, generationId = 1L))
            slot = getSlot(alarmId) ?: throw IllegalStateException("Failed to initialize slot for alarm $alarmId")
        }

        if (slot.generationId >= Long.MAX_VALUE - 100L) {
            throw IllegalStateException("Generation ID overflow for alarm $alarmId")
        }

        val nextGenId = slot.generationId + 1L
        val occurrence = IssuedOccurrence(
            issueToken = issueToken,
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            fireId = fireId,
            generationId = nextGenId,
            status = IssuedOccurrence.STATUS_PREPARED,
            outcome = IssuedOccurrence.OUTCOME_PENDING,
            createdAt = now,
            updatedAt = now
        )

        val insertResult = insertOccurrence(occurrence)
        if (insertResult <= 0L) {
            throw IllegalStateException("Failed to insert occurrence $issueToken")
        }

        val casResult = casIncrementSlotGeneration(
            alarmId = alarmId,
            expectedGenId = slot.generationId,
            expectedToken = slot.currentIssueToken,
            newToken = slot.currentIssueToken
        )

        if (casResult != 1) {
            throw IllegalStateException("Slot generation CAS increment failed for alarm $alarmId")
        }

        return occurrence
    }

    suspend fun reserveIssuedOccurrence(
        issueToken: String,
        alarmId: Long,
        scheduledAt: Long,
        fireId: String,
        now: Long = System.currentTimeMillis()
    ): IssuedOccurrence? {
        return try {
            reserveIssuedOccurrenceTransaction(issueToken, alarmId, scheduledAt, fireId, now)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    suspend fun evaluateActiveOccurrenceAdmission(issueToken: String): Boolean {
        val occurrence = getOccurrence(issueToken) ?: return false
        val slot = getSlot(occurrence.alarmId) ?: return false

        return (occurrence.status == IssuedOccurrence.STATUS_ISSUED || occurrence.status == IssuedOccurrence.STATUS_ADMITTED) &&
            slot.currentIssueToken == issueToken &&
            occurrence.generationId <= slot.generationId
    }

    @Transaction
    open suspend fun activateIssuedOccurrenceTransaction(
        issueToken: String,
        now: Long = System.currentTimeMillis()
    ) {
        val occurrence = getOccurrence(issueToken)
            ?: throw IllegalStateException("Occurrence $issueToken not found")

        if (occurrence.status != IssuedOccurrence.STATUS_PREPARED) {
            throw IllegalStateException("Occurrence $issueToken status is ${occurrence.status}, expected PREPARED")
        }

        val slot = getSlot(occurrence.alarmId)
            ?: throw IllegalStateException("Slot for alarm ${occurrence.alarmId} not found")

        if (slot.generationId != occurrence.generationId) {
            throw IllegalStateException("Slot generation ${slot.generationId} mismatch with occurrence generation ${occurrence.generationId}")
        }

        val previousToken = slot.currentIssueToken

        // Step 1: Promote B PREPARED -> ISSUED
        val statusCas = casUpdateOccurrenceStatus(
            issueToken = issueToken,
            expectedStatus = IssuedOccurrence.STATUS_PREPARED,
            newStatus = IssuedOccurrence.STATUS_ISSUED,
            updatedAt = now
        )
        if (statusCas != 1) {
            throw IllegalStateException("Occurrence status update CAS failed")
        }

        // Step 2: Revoke previous active occurrence A (if any and status is ISSUED)
        if (previousToken != null && previousToken != issueToken) {
            val previousOcc = getOccurrence(previousToken)
            if (previousOcc != null && previousOcc.status == IssuedOccurrence.STATUS_ISSUED) {
                val revokeCas = casUpdateOccurrenceStatus(
                    issueToken = previousToken,
                    expectedStatus = IssuedOccurrence.STATUS_ISSUED,
                    newStatus = IssuedOccurrence.STATUS_REVOKED,
                    updatedAt = now
                )
                if (revokeCas != 1) {
                    throw IllegalStateException("Failed to revoke previous active occurrence $previousToken")
                }
            }
        }

        // Step 3: Update active slot token to issueToken_B
        val slotCas = casUpdateSlotCurrentTokenOnly(
            alarmId = occurrence.alarmId,
            expectedGenId = slot.generationId,
            expectedToken = previousToken,
            newToken = issueToken
        )
        if (slotCas != 1) {
            throw IllegalStateException("Slot token update CAS failed")
        }
    }

    suspend fun activateIssuedOccurrence(
        issueToken: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            activateIssuedOccurrenceTransaction(issueToken, now)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }

    @Transaction
    open suspend fun revokeIssuedOccurrenceTransaction(
        alarmId: Long,
        now: Long = System.currentTimeMillis()
    ) {
        val slot = getSlot(alarmId) ?: throw IllegalStateException("Slot for alarm $alarmId not found")
        val currentToken = slot.currentIssueToken

        val slotCas = casIncrementSlotGeneration(
            alarmId = alarmId,
            expectedGenId = slot.generationId,
            expectedToken = currentToken,
            newToken = null
        )
        if (slotCas != 1) {
            throw IllegalStateException("Slot revocation CAS failed")
        }

        revokePendingOccurrencesForAlarm(alarmId = alarmId, updatedAt = now)
    }

    suspend fun revokeIssuedOccurrence(
        alarmId: Long,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        return try {
            revokeIssuedOccurrenceTransaction(alarmId, now)
            true
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            false
        }
    }
}
