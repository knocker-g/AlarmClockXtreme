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

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSlot(slot: IssueSlot): Long

    @Query("SELECT * FROM issue_slots WHERE alarmId = :alarmId")
    suspend fun getSlot(alarmId: Long): IssueSlot?

    @Query("SELECT * FROM issued_occurrences WHERE issueToken = :issueToken")
    suspend fun getOccurrence(issueToken: String): IssuedOccurrence?

    @Query("SELECT * FROM issued_occurrences WHERE alarmId = :alarmId AND scheduledAt = :scheduledAt")
    suspend fun getOccurrencesByAlarmAndScheduledAt(alarmId: Long, scheduledAt: Long): List<IssuedOccurrence>

    @Query("SELECT currentIssueToken FROM issue_slots WHERE alarmId = :alarmId")
    suspend fun getSlotCurrentToken(alarmId: Long): String?

    @Query("UPDATE issued_occurrences SET status = :status, updatedAt = :updatedAt WHERE issueToken = :issueToken")
    suspend fun updateOccurrenceStatus(issueToken: String, status: String, updatedAt: Long): Int

    @Query("UPDATE issued_occurrences SET status = :newStatus, updatedAt = :updatedAt WHERE issueToken = :issueToken AND status = :expectedStatus")
    suspend fun casUpdateOccurrenceStatus(issueToken: String, expectedStatus: String, newStatus: String, updatedAt: Long): Int

    @Query("UPDATE issue_slots SET currentIssueToken = :newToken, generationId = :newGenId WHERE alarmId = :alarmId")
    suspend fun updateSlotToken(alarmId: Long, newToken: String?, newGenId: Long): Int

    @Query("UPDATE issue_slots SET currentIssueToken = :newToken, generationId = :newGenId WHERE alarmId = :alarmId AND generationId = :expectedGenId")
    suspend fun casUpdateSlotToken(alarmId: Long, expectedGenId: Long, newToken: String?, newGenId: Long): Int

    @Query("DELETE FROM issued_occurrences WHERE alarmId = :alarmId")
    suspend fun deleteOccurrencesForAlarm(alarmId: Long): Int

    @Query("DELETE FROM issue_slots WHERE alarmId = :alarmId")
    suspend fun deleteSlotForAlarm(alarmId: Long): Int
}
