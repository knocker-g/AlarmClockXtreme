package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt

@Dao
interface DirectBootOccurrenceReceiptDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(receipt: DirectBootOccurrenceReceipt): Long

    @Query("SELECT * FROM direct_boot_occurrence_receipts WHERE occurrenceKey = :occurrenceKey LIMIT 1")
    fun findByOccurrenceKey(occurrenceKey: String): DirectBootOccurrenceReceipt?

    @Query("SELECT * FROM direct_boot_occurrence_receipts WHERE alarmId = :alarmId ORDER BY completedAt ASC")
    fun findByAlarmId(alarmId: Long): List<DirectBootOccurrenceReceipt>

    @Query("SELECT COUNT(*) FROM direct_boot_occurrence_receipts")
    fun count(): Long
}
