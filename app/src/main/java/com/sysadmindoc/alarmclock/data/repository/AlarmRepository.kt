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
    fun observeNextAlarm(nowEpochMs: Long = System.currentTimeMillis()): Flow<Alarm?> = dao.observeEnabled().map { list ->
        list.filter { it.nextTriggerTime > nowEpochMs }.minByOrNull { it.nextTriggerTime }
    }

    suspend fun getById(id: Long): Alarm? = dao.getById(id)
    suspend fun getEnabled(): List<Alarm> = dao.getEnabled()
    suspend fun getEnabledNormalAlarms(): List<Alarm> = dao.getEnabled().filter { it.isNormalAlarm }
    suspend fun getNextAlarm(nowEpochMs: Long = System.currentTimeMillis()): Alarm? =
        dao.getEnabled().filter { it.nextTriggerTime > nowEpochMs }.minByOrNull { it.nextTriggerTime }
    suspend fun getAll(): List<Alarm> = dao.getAll()
    suspend fun getAllNormalAlarms(): List<Alarm> = dao.getAll().filter { it.kind == Alarm.KIND_NORMAL || it.kind.isBlank() }
    suspend fun getPendingQuickAlarms(): List<Alarm> = dao.getPendingQuickAlarms()
    suspend fun getAuthorityByAlarmId(alarmId: Long) = database.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
    fun issuedOccurrenceDao(): com.sysadmindoc.alarmclock.data.local.IssuedOccurrenceDao = database.issuedOccurrenceDao()
    fun durableOperationIntentDao(): com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao = database.durableOperationIntentDao()

    suspend fun save(alarm: Alarm): Long {
        return database.withTransaction {
            val sanitized = alarm.sanitized()
            if (sanitized.group.isNotBlank()) {
                groupDao.insert(AlarmGroup(sanitized.group))
            }
            val ordered = if (sanitized.id == 0L && sanitized.sortOrder == 0) {
                sanitized.copy(sortOrder = dao.maxSortOrder() + AlarmDao.SORT_ORDER_STEP)
            } else {
                sanitized
            }
            dao.insert(ordered)
        }
    }

    suspend fun createGroupAndAlarm(groupName: String, alarm: Alarm): Long {
        return database.withTransaction {
            if (groupName.isNotBlank()) {
                groupDao.insert(AlarmGroup(groupName))
            }
            val sanitized = alarm.copy(group = groupName).sanitized()
            val ordered = if (sanitized.id == 0L && sanitized.sortOrder == 0) {
                sanitized.copy(sortOrder = dao.maxSortOrder() + AlarmDao.SORT_ORDER_STEP)
            } else {
                sanitized
            }
            dao.insert(ordered)
        }
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

    suspend fun deleteWithCleanup(alarm: Alarm) {
        database.withTransaction {
            dao.delete(alarm)
            val groupName = alarm.group
            if (groupName.isNotBlank() && dao.countAlarmsByGroup(groupName, alarm.id) == 0) {
                groupDao.delete(AlarmGroup(groupName))
            }
        }
    }

    suspend fun setEnabled(id: Long, enabled: Boolean, nextTrigger: Long) =
        dao.setEnabled(id, enabled, nextTrigger)

    suspend fun updateNextTrigger(id: Long, nextTrigger: Long) =
        dao.updateNextTrigger(id, nextTrigger)

    suspend fun casUpdateNextTrigger(id: Long, expectedTrigger: Long, newTrigger: Long): Int =
        dao.casUpdateNextTrigger(id, expectedTrigger, newTrigger)

    suspend fun casDisableOneShot(id: Long, expectedTrigger: Long): Int =
        dao.casDisableOneShot(id, expectedTrigger)

    suspend fun casUpdateNextTriggerWithStrictAuthority(
        id: Long,
        expectedTrigger: Long,
        newTrigger: Long,
        expectedRevision: Long?,
        instanceToken: String?,
        restoreEpoch: Long?,
        definitionRevision: Long?
    ): Int {
        return if (expectedRevision != null && instanceToken != null && restoreEpoch != null && definitionRevision != null) {
            dao.casUpdateNextTriggerWithStrictAuthority(
                id = id,
                expectedTrigger = expectedTrigger,
                newTrigger = newTrigger,
                expectedRevision = expectedRevision,
                instanceToken = instanceToken,
                restoreEpoch = restoreEpoch,
                definitionRevision = definitionRevision
            )
        } else {
            0
        }
    }

    suspend fun casUpdateNextTriggerInitialWithAuthority(
        id: Long,
        newTrigger: Long,
        expectedRevision: Long?,
        instanceToken: String?,
        restoreEpoch: Long?,
        definitionRevision: Long?
    ): Int {
        return if (expectedRevision != null && instanceToken != null && restoreEpoch != null && definitionRevision != null) {
            dao.casUpdateNextTriggerInitialWithAuthority(
                id = id,
                newTrigger = newTrigger,
                expectedRevision = expectedRevision,
                instanceToken = instanceToken,
                restoreEpoch = restoreEpoch,
                definitionRevision = definitionRevision
            )
        } else {
            0
        }
    }

    suspend fun casSetEnabledWithStrictAuthority(
        id: Long,
        enabled: Boolean,
        nextTrigger: Long,
        expectedRevision: Long?,
        instanceToken: String?,
        restoreEpoch: Long?,
        definitionRevision: Long?
    ): Int {
        return if (expectedRevision != null && instanceToken != null && restoreEpoch != null && definitionRevision != null) {
            dao.casSetEnabledWithStrictAuthority(
                id = id,
                enabled = enabled,
                nextTrigger = nextTrigger,
                expectedRevision = expectedRevision,
                instanceToken = instanceToken,
                restoreEpoch = restoreEpoch,
                definitionRevision = definitionRevision
            )
        } else {
            0
        }
    }

    suspend fun nextSortOrder(): Int = dao.maxSortOrder() + AlarmDao.SORT_ORDER_STEP

    suspend fun updateSortOrders(idsInOrder: List<Long>) =
        dao.updateSortOrders(idsInOrder)

    suspend fun restoreAlarmsTransaction(
        alarms: List<Alarm>,
        isReplace: Boolean
    ): List<Long> {
        return database.withTransaction {
            if (isReplace) {
                dao.deleteAll()
            }
            val prepared = alarms.map { alarm ->
                val sanitized = alarm.sanitized()
                if (sanitized.group.isNotBlank()) {
                    groupDao.insert(AlarmGroup(sanitized.group))
                }
                if (isReplace) {
                    sanitized
                } else {
                    sanitized.copy(id = 0L)
                }
            }
            if (prepared.isNotEmpty()) {
                dao.insertAll(prepared)
            } else {
                emptyList()
            }
        }
    }
}
