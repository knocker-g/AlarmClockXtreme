package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence

@Dao
interface IssuedOccurrenceDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOccurrence(occurrence: IssuedOccurrence): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSlotInitial(slot: IssueSlot): Long

    @Query("SELECT * FROM issue_slots WHERE alarmId = :alarmId")
    suspend fun getSlot(alarmId: Long): IssueSlot?

    @Query("SELECT * FROM issued_occurrences WHERE issueToken = :issueToken")
    suspend fun getOccurrence(issueToken: String): IssuedOccurrence?

    @Query("SELECT * FROM issued_occurrences WHERE alarmId = :alarmId AND scheduledAt = :scheduledAt")
    suspend fun getOccurrencesByAlarmAndScheduledAt(alarmId: Long, scheduledAt: Long): List<IssuedOccurrence>

    @Query("SELECT currentIssueToken FROM issue_slots WHERE alarmId = :alarmId")
    suspend fun getSlotCurrentToken(alarmId: Long): String?

    @Query("UPDATE issued_occurrences SET status = :newStatus, updatedAt = :updatedAt WHERE issueToken = :issueToken AND status = :expectedStatus")
    suspend fun casUpdateOccurrenceStatus(issueToken: String, expectedStatus: String, newStatus: String, updatedAt: Long): Int

    @Query(
        """
        UPDATE issue_slots
        SET currentIssueToken = :newToken, generationId = generationId + 1
        WHERE alarmId = :alarmId
          AND generationId = :expectedGenId
          AND (currentIssueToken = :expectedToken OR (currentIssueToken IS NULL AND :expectedToken IS NULL))
        """
    )
    suspend fun casIncrementSlotGeneration(
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
        """
    )
    suspend fun casUpdateSlotCurrentTokenOnly(
        alarmId: Long,
        expectedGenId: Long,
        newToken: String?
    ): Int

    @androidx.room.Transaction
    suspend fun reserveIssuedOccurrenceTransaction(
        issueToken: String,
        alarmId: Long,
        scheduledAt: Long,
        fireId: String,
        now: Long = System.currentTimeMillis()
    ): IssuedOccurrence? {
        var slot = getSlot(alarmId)
        if (slot == null) {
            insertSlotInitial(IssueSlot(alarmId = alarmId, currentIssueToken = null, generationId = 1L))
            slot = getSlot(alarmId) ?: return null
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

        try {
            val insertResult = insertOccurrence(occurrence)
            if (insertResult <= 0L) return null
        } catch (_: Exception) {
            return null
        }

        val casResult = casIncrementSlotGeneration(
            alarmId = alarmId,
            expectedGenId = slot.generationId,
            expectedToken = slot.currentIssueToken,
            newToken = slot.currentIssueToken
        )

        return if (casResult == 1) occurrence else null
    }

    @androidx.room.Transaction
    suspend fun activateIssuedOccurrenceTransaction(
        issueToken: String,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val occurrence = getOccurrence(issueToken) ?: return false
        if (occurrence.status != IssuedOccurrence.STATUS_PREPARED) return false

        val slot = getSlot(occurrence.alarmId) ?: return false
        if (slot.generationId != occurrence.generationId) return false

        val statusCas = casUpdateOccurrenceStatus(
            issueToken = issueToken,
            expectedStatus = IssuedOccurrence.STATUS_PREPARED,
            newStatus = IssuedOccurrence.STATUS_ISSUED,
            updatedAt = now
        )
        if (statusCas != 1) return false

        val slotCas = casUpdateSlotCurrentTokenOnly(
            alarmId = occurrence.alarmId,
            expectedGenId = slot.generationId,
            newToken = issueToken
        )
        return slotCas == 1
    }

    @androidx.room.Transaction
    suspend fun revokeIssuedOccurrenceTransaction(
        alarmId: Long,
        now: Long = System.currentTimeMillis()
    ): Int {
        val slot = getSlot(alarmId) ?: return 0
        val currentToken = slot.currentIssueToken

        val slotCas = casIncrementSlotGeneration(
            alarmId = alarmId,
            expectedGenId = slot.generationId,
            expectedToken = currentToken,
            newToken = null
        )
        if (slotCas != 1) return 0

        if (currentToken != null) {
            casUpdateOccurrenceStatus(
                issueToken = currentToken,
                expectedStatus = IssuedOccurrence.STATUS_ISSUED,
                newStatus = IssuedOccurrence.STATUS_REVOKED,
                updatedAt = now
            )
        }
        return 1
    }
}
