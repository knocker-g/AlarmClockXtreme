package com.sysadmindoc.alarmclock.data.local

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent

@Dao
interface DurableOperationIntentDao {
    /**
     * Inserts an inert durable operation intent using ABORT on conflict.
     * Prevents silent replacement or deletion of historical operation records.
     * Note: Insert is a persistence primitive only and does NOT constitute active operation admission.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRawIntent(intent: DurableOperationIntent)

    /**
     * Inserts a validated durable operation intent, enforcing that:
     * - Payload fields are valid (non-blank identities, positive epoch, valid phase).
     * - If the intent is unresolved (PENDING or IN_FLIGHT), no existing unresolved intent exists for the target alarmId.
     * - Uses ABORT strategy on primary key / constraint collisions.
     */
    @Transaction
    suspend fun insertValidatedIntent(intent: DurableOperationIntent) {
        DurableOperationIntent.validateForInsert(intent)

        if (DurableOperationIntent.isUnresolvedPhase(intent.phase)) {
            val existingUnresolved = getUnresolvedIntentsByAlarmId(intent.alarmId)
            if (existingUnresolved.isNotEmpty() && existingUnresolved.none { it.operationToken == intent.operationToken }) {
                throw SQLiteConstraintException(
                    "Cannot insert unresolved operation intent for alarmId=${intent.alarmId}: an unresolved operation (${existingUnresolved.first().operationToken}) already exists"
                )
            }
        }

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
