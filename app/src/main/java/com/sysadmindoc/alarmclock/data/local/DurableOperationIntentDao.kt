package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.DurableUnresolvedIntentSlot

@Dao
interface DurableOperationIntentDao {
    /**
     * Inserts an inert durable operation intent using ABORT on conflict.
     * Prevents silent replacement or deletion of historical operation records.
     * Note: Insert is a persistence primitive only and does NOT constitute active operation admission.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRawIntent(intent: DurableOperationIntent)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertUnresolvedSlot(slot: DurableUnresolvedIntentSlot)

    @Query("DELETE FROM durable_unresolved_intent_slot WHERE alarmId = :alarmId")
    suspend fun deleteUnresolvedSlotByAlarmId(alarmId: Long)

    /**
     * Inserts a validated durable operation intent.
     * Enforces physical SQLite PRIMARY KEY(alarmId) uniqueness on unresolved intents
     * via the [DurableUnresolvedIntentSlot] table, preventing duplicate unresolved operations
     * on both Fresh and Migrated databases.
     */
    @Transaction
    suspend fun insertValidatedIntent(intent: DurableOperationIntent) {
        DurableOperationIntent.validateForInsert(intent)

        insertRawIntent(intent)

        if (DurableOperationIntent.isUnresolvedPhase(intent.phase)) {
            val slot = DurableUnresolvedIntentSlot(
                alarmId = intent.alarmId,
                operationToken = intent.operationToken,
                operationEpoch = intent.operationEpoch,
                incarnationId = intent.incarnationId,
                operationKind = intent.operationKind,
                phase = intent.phase
            )
            insertUnresolvedSlot(slot)
        }
    }

    @Query("SELECT * FROM durable_operation_intent WHERE operationToken = :operationToken")
    suspend fun getIntentByToken(operationToken: String): DurableOperationIntent?

    @Query("SELECT * FROM durable_operation_intent WHERE alarmId = :alarmId ORDER BY operationEpoch ASC")
    suspend fun getIntentsByAlarmId(alarmId: Long): List<DurableOperationIntent>

    @Query("SELECT * FROM durable_unresolved_intent_slot WHERE alarmId = :alarmId")
    suspend fun getUnresolvedSlotByAlarmId(alarmId: Long): DurableUnresolvedIntentSlot?

    @Query("SELECT * FROM durable_operation_intent ORDER BY operationEpoch ASC")
    suspend fun getAllIntents(): List<DurableOperationIntent>
}
