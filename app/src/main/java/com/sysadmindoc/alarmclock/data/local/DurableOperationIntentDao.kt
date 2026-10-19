package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent

@Dao
interface DurableOperationIntentDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertIntent(intent: DurableOperationIntent)

    @Query("SELECT * FROM durable_operation_intent WHERE operationToken = :operationToken")
    suspend fun getIntentByToken(operationToken: String): DurableOperationIntent?

    @Query("SELECT * FROM durable_operation_intent WHERE alarmId = :alarmId ORDER BY operationEpoch ASC")
    suspend fun getIntentsByAlarmId(alarmId: Long): List<DurableOperationIntent>

    @Query("SELECT * FROM durable_operation_intent ORDER BY operationEpoch ASC")
    suspend fun getAllIntents(): List<DurableOperationIntent>

    @Query("DELETE FROM durable_operation_intent WHERE operationToken = :operationToken")
    suspend fun deleteIntentByToken(operationToken: String)
}
