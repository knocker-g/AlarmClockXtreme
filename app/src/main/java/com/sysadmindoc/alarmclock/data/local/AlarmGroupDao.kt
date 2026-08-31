package com.sysadmindoc.alarmclock.data.local

import androidx.room.*
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmGroupDao {
    @Query("SELECT * FROM alarm_groups ORDER BY name ASC")
    fun observeAll(): Flow<List<AlarmGroup>>

    @Query("SELECT * FROM alarm_groups ORDER BY name ASC")
    suspend fun getAll(): List<AlarmGroup>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(group: AlarmGroup)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(groups: List<AlarmGroup>)

    @Delete
    suspend fun delete(group: AlarmGroup)
}
