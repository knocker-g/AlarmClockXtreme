package com.sysadmindoc.alarmclock.data.repository

import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.AlarmGroupDao
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.model.Alarm
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

class StaleAlarmException(message: String) : IllegalStateException(message)

@Singleton
class AlarmRepository @Inject constructor(
    val database: AlarmDatabase,
    private val dao: AlarmDao,
    private val groupDao: AlarmGroupDao
) {
    fun observeAll(): Flow<List<Alarm>> = dao.observeAll()
    fun observeEnabled(): Flow<List<Alarm>> = dao.observeEnabled()
    fun observeNextAlarm(
        clock: () -> Long = { System.currentTimeMillis() },
        refreshIntervalMs: Long = 30_000L
    ): Flow<Alarm?> = combine(
        dao.observeEnabled(),
        flow {
            emit(Unit)
            while (true) {
                delay(refreshIntervalMs)
                emit(Unit)
            }
        }
    ) { list, _ ->
        val now = clock()
        list.filter { it.nextTriggerTime > now }.minByOrNull { it.nextTriggerTime }
    }.distinctUntilChanged()

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
        alarm.requireValidHolidayPolicy()
        return database.withTransaction {
            val isTrueNewAlarm = (alarm.id == 0L)
            val existing = if (!isTrueNewAlarm) dao.getById(alarm.id) else null
            val versioned = prepareVersionedAlarmForSave(alarm, existing)
            val sanitized = versioned.sanitized()
            if (sanitized.group.isNotBlank()) {
                groupDao.insert(AlarmGroup(sanitized.group))
            }
            val ordered = if (sanitized.id == 0L && sanitized.sortOrder == 0) {
                sanitized.copy(sortOrder = dao.maxSortOrder() + AlarmDao.SORT_ORDER_STEP)
            } else {
                sanitized
            }
            val savedId = dao.insert(ordered)

            // When a brand-new Normal Alarm (id == 0) is created, admit V35 ScheduleAuthority & IssueSlot
            if (isTrueNewAlarm && !sanitized.isQuickAlarm) {
                val authorityDao = database.scheduleAuthorityDao()
                val issuedDao = database.issuedOccurrenceDao()

                val meta = authorityDao.getMetaById(1)
                    ?: throw IllegalStateException("Cannot admit ScheduleAuthority: ScheduleAuthorityMeta missing")

                val currentDefRev = com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(sanitized)
                val authority = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                    alarmId = savedId,
                    instanceToken = "auth_${savedId}_gen_${sanitized.generation}_${java.util.UUID.randomUUID()}",
                    definitionRevision = currentDefRev,
                    scheduleRevision = sanitized.revision.toLong(),
                    lifecycle = if (sanitized.isEnabled) "LIVE" else "DISABLED",
                    desiredOperation = if (sanitized.isEnabled) "SET" else "CANCEL",
                    desiredTriggerMillis = if (sanitized.isEnabled) sanitized.nextTriggerTime else null,
                    reconcileState = "SETTLED",
                    restoreEpoch = meta.restoreEpoch,
                    actionKey = null
                )
                authorityDao.insertAuthority(authority)

                if (issuedDao.getSlot(savedId) == null) {
                    val slot = com.sysadmindoc.alarmclock.data.local.entity.IssueSlot(
                        alarmId = savedId,
                        generationId = sanitized.generation.toLong(),
                        currentIssueToken = null
                    )
                    issuedDao.insertSlotInitial(slot)
                }
            }

            savedId
        }
    }

    suspend fun update(alarm: Alarm) {
        alarm.requireValidHolidayPolicy()
        database.withTransaction {
            val authorityDao = database.scheduleAuthorityDao()
            val issuedDao = database.issuedOccurrenceDao()
            val existing = if (alarm.id != 0L) dao.getById(alarm.id) else null
            val versioned = prepareVersionedAlarmForUpdate(alarm, existing)
            val sanitized = versioned.sanitized()
            dao.update(sanitized)

            // KNO-30 P2 / KNO-159 P2: Fenced CAS update for ScheduleAuthority and revoke obsolete occurrences on schedule change
            val existingAuthority = authorityDao.getAuthorityByAlarmId(sanitized.id)
            if (existingAuthority != null && !sanitized.isQuickAlarm) {
                val isScheduleChanged = existing != null && (
                    existing.hour != sanitized.hour ||
                    existing.minute != sanitized.minute ||
                    existing.repeatDays != sanitized.repeatDays ||
                    existing.specificDate != sanitized.specificDate ||
                    existing.scheduleKind != sanitized.scheduleKind ||
                    existing.canonicalHolidayPolicy() != sanitized.canonicalHolidayPolicy() ||
                    existing.skipOnHolidays != sanitized.skipOnHolidays ||
                    existing.solarOffsetMinutes != sanitized.solarOffsetMinutes ||
                    existing.solarAnchor != sanitized.solarAnchor ||
                    existing.timezonePolicy != sanitized.timezonePolicy ||
                    existing.fixedTimezoneId != sanitized.fixedTimezoneId ||
                    existing.shiftPattern != sanitized.shiftPattern ||
                    existing.shiftPatternStartDate != sanitized.shiftPatternStartDate
                )

                val newDefRev = com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(sanitized)
                val newLifecycle = if (sanitized.isEnabled) "LIVE" else "DISABLED"
                val newDesiredOperation = if (sanitized.isEnabled) "SET" else "CANCEL"
                val newDesiredTriggerMillis = if (sanitized.isEnabled) sanitized.nextTriggerTime else null

                val rows = if (isScheduleChanged) {
                    authorityDao.casUpdateScheduleAuthority(
                        alarmId = sanitized.id,
                        instanceToken = existingAuthority.instanceToken,
                        restoreEpoch = existingAuthority.restoreEpoch,
                        expectedRevision = existingAuthority.scheduleRevision,
                        newDefinitionRevision = newDefRev,
                        newLifecycle = newLifecycle,
                        desiredOperation = newDesiredOperation,
                        desiredTriggerMillis = newDesiredTriggerMillis
                    )
                } else {
                    authorityDao.casUpdateNonScheduleAuthority(
                        alarmId = sanitized.id,
                        instanceToken = existingAuthority.instanceToken,
                        restoreEpoch = existingAuthority.restoreEpoch,
                        expectedRevision = existingAuthority.scheduleRevision,
                        newDefinitionRevision = newDefRev,
                        newLifecycle = newLifecycle,
                        desiredOperation = newDesiredOperation,
                        desiredTriggerMillis = newDesiredTriggerMillis
                    )
                }

                if (rows == 0) {
                    throw StaleAlarmException("ScheduleAuthority CAS update failed for alarm ${sanitized.id}: stale token, revision, or epoch")
                }

                if (isScheduleChanged) {
                    issuedDao.revokePendingOccurrencesForAlarm(sanitized.id, System.currentTimeMillis())
                }
            }
        }
    }

    private fun prepareVersionedAlarmForSave(alarm: Alarm, existing: Alarm?): Alarm {
        return prepareVersionedAlarmInternal(alarm, existing, isUpdateCall = false)
    }

    private fun prepareVersionedAlarmForUpdate(alarm: Alarm, existing: Alarm?): Alarm {
        return prepareVersionedAlarmInternal(alarm, existing, isUpdateCall = true)
    }

    private fun prepareVersionedAlarmInternal(alarm: Alarm, existing: Alarm?, isUpdateCall: Boolean): Alarm {
        val withKind = alarm.withDerivedScheduleKind()
        if (isUpdateCall && !alarm.isQuickAlarm && alarm.id != 0L && existing == null) {
            throw StaleAlarmException("Alarm ${alarm.id} was deleted in another session and cannot be updated")
        }
        if (existing == null) {
            return withKind.copy(
                generation = if (withKind.generation > 0) withKind.generation else 1,
                revision = if (withKind.revision > 0) withKind.revision else 1
            )
        }

        // Optimistic Concurrency Control Check (Normal Alarms in update flows):
        // Enforce exact version match: reject drafts with outdated or invalid future versions.
        if (isUpdateCall && !existing.isQuickAlarm && !alarm.isQuickAlarm) {
            if (alarm.generation != existing.generation || alarm.revision != existing.revision) {
                throw StaleAlarmException(
                    "Alarm version mismatch: draft (gen ${alarm.generation}, rev ${alarm.revision}) does not match DB version (gen ${existing.generation}, rev ${existing.revision})"
                )
            }
        }

        val isScheduleChanged = existing.hour != withKind.hour ||
                existing.minute != withKind.minute ||
                existing.repeatDays != withKind.repeatDays ||
                existing.specificDate != withKind.specificDate ||
                existing.shiftPattern != withKind.shiftPattern ||
                existing.shiftPatternStartDate != withKind.shiftPatternStartDate ||
                existing.timezonePolicy != withKind.timezonePolicy ||
                existing.fixedTimezoneId != withKind.fixedTimezoneId ||
                existing.scheduleKind != withKind.scheduleKind ||
                existing.canonicalHolidayPolicy() != withKind.canonicalHolidayPolicy() ||
                existing.skipOnHolidays != withKind.skipOnHolidays ||
                existing.solarOffsetMinutes != withKind.solarOffsetMinutes ||
                existing.solarAnchor != withKind.solarAnchor

        val isSecondaryChanged = existing.label != withKind.label ||
                existing.ringtoneUri != withKind.ringtoneUri ||
                existing.vibrationEnabled != withKind.vibrationEnabled ||
                existing.volume != withKind.volume ||
                existing.group != withKind.group ||
                existing.snoozeDurationMinutes != withKind.snoozeDurationMinutes ||
                existing.maxSnoozeCount != withKind.maxSnoozeCount ||
                existing.vibrationPattern != withKind.vibrationPattern ||
                existing.challengeType != withKind.challengeType

        return when {
            isScheduleChanged -> withKind.copy(generation = existing.generation + 1, revision = 1)
            isSecondaryChanged -> withKind.copy(generation = existing.generation, revision = existing.revision + 1)
            else -> withKind.copy(generation = existing.generation, revision = existing.revision)
        }
    }

    suspend fun createGroupAndAlarm(groupName: String, alarm: Alarm): Long {
        alarm.requireValidHolidayPolicy()
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

    suspend fun importDisabledAtomically(alarms: List<Alarm>): List<Long> {
        alarms.forEach { it.requireValidHolidayPolicy() }
        return database.withTransaction {
            val authorityDao = database.scheduleAuthorityDao()
            val issuedDao = database.issuedOccurrenceDao()

            val meta = authorityDao.getMetaById(1)
                ?: throw IllegalStateException("Cannot import alarms: ScheduleAuthorityMeta missing")

            val savedIds = mutableListOf<Long>()
            for (alarm in alarms) {
                val disabledAlarm = alarm.copy(id = 0L, isEnabled = false, nextTriggerTime = 0L).sanitized()
                val savedId = dao.insert(disabledAlarm)
                savedIds.add(savedId)

                if (!disabledAlarm.isQuickAlarm) {
                    val currentDefRev = com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(disabledAlarm)
                    val authority = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                        alarmId = savedId,
                        instanceToken = "auth_${savedId}_gen_${disabledAlarm.generation}_${java.util.UUID.randomUUID()}",
                        definitionRevision = currentDefRev,
                        scheduleRevision = disabledAlarm.revision.toLong(),
                        lifecycle = "DISABLED",
                        desiredOperation = "CANCEL",
                        desiredTriggerMillis = null,
                        reconcileState = "RECONCILING",
                        restoreEpoch = meta.restoreEpoch,
                        actionKey = null
                    )
                    authorityDao.insertAuthority(authority)

                    if (issuedDao.getSlot(savedId) == null) {
                        val slot = com.sysadmindoc.alarmclock.data.local.entity.IssueSlot(
                            alarmId = savedId,
                            generationId = disabledAlarm.generation.toLong(),
                            currentIssueToken = null
                        )
                        issuedDao.insertSlotInitial(slot)
                    }
                }
            }
            savedIds
        }
    }
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

    suspend fun setEnabled(id: Long, enabled: Boolean, nextTrigger: Long) {
        database.withTransaction {
            dao.setEnabled(id, enabled, nextTrigger)

            val authorityDao = database.scheduleAuthorityDao()
            val existingAuthority = authorityDao.getAuthorityByAlarmId(id)
            if (existingAuthority != null) {
                val rows = authorityDao.casUpdateToggleAuthority(
                    alarmId = id,
                    instanceToken = existingAuthority.instanceToken,
                    restoreEpoch = existingAuthority.restoreEpoch,
                    expectedRevision = existingAuthority.scheduleRevision,
                    newLifecycle = if (enabled) "LIVE" else "DISABLED",
                    desiredOperation = if (enabled) "SET" else "CANCEL",
                    desiredTriggerMillis = if (enabled) nextTrigger else null
                )
                if (rows == 0) {
                    throw StaleAlarmException("ScheduleAuthority CAS toggle failed for alarm $id: stale token, revision, or epoch")
                }
            }
        }
    }

    suspend fun updateAlarmsEnabledTransaction(alarmIds: List<Long>, enabled: Boolean, triggerTimeCalculator: (Alarm) -> Long) {
        database.withTransaction {
            val authorityDao = database.scheduleAuthorityDao()
            val alarms = alarmIds.mapNotNull { dao.getById(it) }
            for (alarm in alarms) {
                if (alarm.isQuickAlarm) continue
                val nextTrigger = if (enabled) triggerTimeCalculator(alarm) else 0L
                val updatedAlarm = alarm.copy(isEnabled = enabled, nextTriggerTime = nextTrigger)
                dao.update(updatedAlarm)

                val existingAuthority = authorityDao.getAuthorityByAlarmId(alarm.id)
                if (existingAuthority != null) {
                    val rows = authorityDao.casUpdateToggleAuthority(
                        alarmId = alarm.id,
                        instanceToken = existingAuthority.instanceToken,
                        restoreEpoch = existingAuthority.restoreEpoch,
                        expectedRevision = existingAuthority.scheduleRevision,
                        newLifecycle = if (enabled) "LIVE" else "DISABLED",
                        desiredOperation = if (enabled) "SET" else "CANCEL",
                        desiredTriggerMillis = if (enabled) nextTrigger else null
                    )
                    if (rows == 0) {
                        throw StaleAlarmException("ScheduleAuthority CAS group toggle failed for alarm ${alarm.id}: stale token, revision, or epoch")
                    }
                }
            }
        }
    }

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

    suspend fun getAlarmsByGroup(groupName: String): List<Alarm> =
        dao.getAlarmsByGroup(groupName)

    suspend fun updateAlarmsEnabledTransaction(alarms: List<Alarm>) {
        database.withTransaction {
            dao.updateAlarmsEnabled(alarms)
        }
    }

    suspend fun updateSortOrders(idsInOrder: List<Long>) =
        dao.updateSortOrders(idsInOrder)

    suspend fun restoreAlarmsTransaction(
        alarms: List<Alarm>,
        isReplace: Boolean
    ): List<Long> {
        alarms.forEach { it.requireValidHolidayPolicy() }
        return database.withTransaction {
            val authorityDao = database.scheduleAuthorityDao()
            val issuedDao = database.issuedOccurrenceDao()

            if (isReplace) {
                dao.deleteAll()
                authorityDao.getAllAuthorities().forEach { authorityDao.deleteAuthorityByAlarmId(it.alarmId) }
                issuedDao.deleteAllSlots()
            }

            val meta = authorityDao.getMetaById(1)
                ?: throw IllegalStateException("Cannot restore alarms: ScheduleAuthorityMeta missing")

            val savedIds = mutableListOf<Long>()
            for (alarm in alarms) {
                val sanitized = alarm.sanitized()
                if (sanitized.group.isNotBlank()) {
                    groupDao.insert(AlarmGroup(sanitized.group))
                }
                val prepared = if (isReplace) sanitized else sanitized.copy(id = 0L)
                val savedId = dao.insert(prepared)
                savedIds.add(savedId)

                if (!prepared.isQuickAlarm) {
                    issuedDao.deleteSlotByAlarmId(savedId)
                    val currentDefRev = com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(prepared)
                    val authority = com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority(
                        alarmId = savedId,
                        instanceToken = "auth_${savedId}_gen_${prepared.generation}_${java.util.UUID.randomUUID()}",
                        definitionRevision = currentDefRev,
                        scheduleRevision = prepared.revision.toLong(),
                        lifecycle = if (prepared.isEnabled) "LIVE" else "DISABLED",
                        desiredOperation = if (prepared.isEnabled) "SET" else "CANCEL",
                        desiredTriggerMillis = if (prepared.isEnabled) prepared.nextTriggerTime else null,
                        reconcileState = "RECONCILING",
                        restoreEpoch = meta.restoreEpoch,
                        actionKey = null
                    )
                    authorityDao.insertAuthority(authority)

                    val slot = com.sysadmindoc.alarmclock.data.local.entity.IssueSlot(
                        alarmId = savedId,
                        generationId = prepared.generation.toLong(),
                        currentIssueToken = null
                    )
                    issuedDao.insertSlotInitial(slot)
                }
            }
            savedIds
        }
    }
}
