package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent

@Dao
interface DurableOperationIntentDao {
    /**
     * Inserts an inert durable operation intent using ABORT on conflict.
     * Prevents silent replacement or deletion of historical operation records.
     * Enforced at the SQLite database layer via triggers on both fresh and migrated databases.
     * Note: Insert is a persistence primitive only and does NOT constitute active operation admission.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRawIntent(intent: DurableOperationIntent)

    /**
     * Inserts a validated durable operation intent.
     * Enforces non-blank identities, positive epoch, and valid phase before insertion.
     */
    suspend fun insertValidatedIntent(intent: DurableOperationIntent) {
        DurableOperationIntent.validateForInsert(intent)
        insertRawIntent(intent)
    }

    @Query("SELECT * FROM durable_operation_intent WHERE operationToken = :operationToken")
    suspend fun getIntentByToken(operationToken: String): DurableOperationIntent?

    @Query("SELECT * FROM durable_operation_intent WHERE alarmId = :alarmId ORDER BY operationEpoch ASC")
    suspend fun getIntentsByAlarmId(alarmId: Long): List<DurableOperationIntent>

    @Query("SELECT * FROM durable_operation_intent WHERE alarmId = :alarmId AND phase IN ('PENDING', 'IN_FLIGHT')")
    suspend fun getUnresolvedIntentsByAlarmId(alarmId: Long): List<DurableOperationIntent>

    @Query("SELECT * FROM durable_operation_intent ORDER BY operationEpoch ASC")
    suspend fun getAllIntents(): List<DurableOperationIntent>
}
