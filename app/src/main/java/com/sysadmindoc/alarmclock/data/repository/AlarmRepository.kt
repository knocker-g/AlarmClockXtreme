package com.sysadmindoc.alarmclock.data.repository

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.AlarmGroupDao
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlarmRepository @Inject constructor(
    private val database: AlarmDatabase,
    private val dao: AlarmDao,
    private val groupDao: AlarmGroupDao
) {
    fun observeAll(): Flow<List<Alarm>> = dao.observeAll()
    fun observeEnabled(): Flow<List<Alarm>> = dao.observeEnabled()
    fun observeNextAlarm(): Flow<Alarm?> = dao.observeNextAlarm()

    suspend fun getById(id: Long): Alarm? = dao.getById(id)
    suspend fun getEnabled(): List<Alarm> = dao.getEnabled()
    suspend fun getNextAlarm(): Alarm? = dao.getNextAlarm()
    suspend fun getAll(): List<Alarm> = dao.getAll()

    suspend fun save(alarm: Alarm): Long {
        val sanitized = alarm.sanitized()
        if (sanitized.group.isNotBlank()) {
            groupDao.insert(AlarmGroup(sanitized.group))
        }
        val ordered = if (sanitized.id == 0L && sanitized.sortOrder == 0) {
            sanitized.copy(sortOrder = nextSortOrder())
        } else {
            sanitized
        }
        return dao.insert(ordered)
    }

    fun observeGroups(): Flow<List<String>> =
        groupDao.observeAll().map { list -> list.map { it.name } }

    suspend fun getAllGroups(): List<String> =
        groupDao.getAll().map { it.name }

    suspend fun addGroup(name: String) {
        if (name.isNotBlank()) {
            groupDao.insert(AlarmGroup(name))
        }
    }

    suspend fun countAlarmsByGroup(groupName: String, excludedId: Long): Int =
        dao.countAlarmsByGroup(groupName, excludedId)

    suspend fun deleteGroupWithAlarms(groupName: String) {
        database.withTransaction {
            dao.clearGroupFromAlarms(groupName)
            groupDao.delete(AlarmGroup(groupName))
        }
    }

    suspend fun importDisabledAtomically(alarms: List<Alarm>): List<Long> =
        dao.insertAllWithStableOrder(
            alarms.map {
                it.copy(id = 0L, isEnabled = false, nextTriggerTime = 0L).sanitized()
            }
        )
    suspend fun update(alarm: Alarm) = dao.update(alarm.sanitized())
    suspend fun delete(alarm: Alarm) = dao.delete(alarm)
    suspend fun deleteById(id: Long) = dao.deleteById(id)

    suspend fun setEnabled(id: Long, enabled: Boolean, nextTrigger: Long) =
        dao.setEnabled(id, enabled, nextTrigger)

    suspend fun updateNextTrigger(id: Long, nextTrigger: Long) =
        dao.updateNextTrigger(id, nextTrigger)

    suspend fun nextSortOrder(): Int = dao.maxSortOrder() + AlarmDao.SORT_ORDER_STEP

    suspend fun updateSortOrders(idsInOrder: List<Long>) =
        dao.updateSortOrders(idsInOrder)
}
