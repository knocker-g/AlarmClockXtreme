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
        SET currentIssueToken = :newToken, generationId = :newGenId
        WHERE alarmId = :alarmId
          AND generationId = :expectedGenId
          AND (currentIssueToken = :expectedToken OR (currentIssueToken IS NULL AND :expectedToken IS NULL))
        """
    )
    suspend fun casUpdateSlotToken(
        alarmId: Long,
        expectedGenId: Long,
        expectedToken: String?,
        newToken: String?,
        newGenId: Long
    ): Int
}
