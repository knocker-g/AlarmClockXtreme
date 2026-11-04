package com.sysadmindoc.alarmclock.domain.projection

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.AlarmDao
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.domain.evaluator.HolidaySnapshot
import com.sysadmindoc.alarmclock.domain.evaluator.LocalHolidayClassifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Immutable coordinator state tracking active invalidations with monotonic generation counters (KNO-156 P1-C1).
 */
data class CoordinatorState(
    val nextGeneration: Long = 1L,
    val alarmGenerations: Map<Long, Long> = emptyMap(),
    val alarmReasons: Map<Long, InvalidationReason> = emptyMap(),
    val globalGeneration: Long? = null,
    val globalReason: InvalidationReason? = null,
    val version: Long = 0L
) {
    val reconcilingAlarmIds: Set<Long> get() = alarmGenerations.keys
    val isGlobalReconciling: Boolean get() = globalGeneration != null
}

@Singleton
class UpcomingScheduleReconcileCoordinator @Inject constructor(
    private val projector: UpcomingScheduleProjector,
    private val alarmDao: AlarmDao,
    private val scheduleExceptionDao: ScheduleExceptionDao,
    private val scheduleAuthorityDao: ScheduleAuthorityDao
) {
    companion object {
        private const val TAG = "UpcomingReconcileCoordinator"
    }

    private val mutex = Mutex()

    private val _state = MutableStateFlow(CoordinatorState())
    val state = _state.asStateFlow()

    private val _reconcilingAlarmIds = MutableStateFlow<Set<Long>>(emptySet())
    val reconcilingAlarmIds = _reconcilingAlarmIds.asStateFlow()

    private val _isGlobalReconciling = MutableStateFlow(false)
    val isGlobalReconciling = _isGlobalReconciling.asStateFlow()

    private val _lastInvalidationReasons = MutableStateFlow<Map<Long, InvalidationReason>>(emptyMap())
    val lastInvalidationReasons = _lastInvalidationReasons.asStateFlow()

    private val _lastGlobalInvalidationReason = MutableStateFlow<InvalidationReason?>(null)
    val lastGlobalInvalidationReason = _lastGlobalInvalidationReason.asStateFlow()

    private fun syncPublicStateFlows(s: CoordinatorState) {
        _reconcilingAlarmIds.value = s.reconcilingAlarmIds
        _isGlobalReconciling.value = s.isGlobalReconciling
        _lastInvalidationReasons.value = s.alarmReasons
        _lastGlobalInvalidationReason.value = s.globalReason
    }

    suspend fun invalidateAlarm(alarmId: Long, reason: InvalidationReason) {
        mutex.withLock {
            val gen = _state.value.nextGeneration
            val newState = _state.value.copy(
                nextGeneration = gen + 1,
                alarmGenerations = _state.value.alarmGenerations + (alarmId to gen),
                alarmReasons = _state.value.alarmReasons + (alarmId to reason),
                version = _state.value.version + 1
            )
            _state.value = newState
            syncPublicStateFlows(newState)
            Log.d(TAG, "Invalidated alarm $alarmId with reason $reason (gen $gen)")
        }
    }

    suspend fun invalidateAll(reason: InvalidationReason) {
        mutex.withLock {
            val gen = _state.value.nextGeneration
            val newState = _state.value.copy(
                nextGeneration = gen + 1,
                globalGeneration = gen,
                globalReason = reason,
                version = _state.value.version + 1
            )
            _state.value = newState
            syncPublicStateFlows(newState)
            Log.d(TAG, "Invalidated all projections with reason $reason (gen $gen)")
        }
    }

    fun isAlarmReconciling(alarmId: Long): Boolean {
        val s = _state.value
        return s.isGlobalReconciling || alarmId in s.reconcilingAlarmIds
    }

    fun isGlobalReconciling(): Boolean {
        return _state.value.isGlobalReconciling
    }

    suspend fun reconcile(
        alarmId: Long,
        nowEpochMs: Long = System.currentTimeMillis(),
        horizonDays: Int = UpcomingScheduleProjector.DEFAULT_HORIZON_DAYS,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        holidayClassifier: LocalHolidayClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
        holidaySnapshotProvider: ((LocalDate) -> HolidaySnapshot)? = null,
        activeFiringAlarmIds: Set<Long> = emptySet(),
        consumedOccurrenceKeys: Set<String> = emptySet(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): UpcomingScheduleProjection {
        val (startAlarmGen, isGlobalAtStart, reconcilingSnapshot) = mutex.withLock {
            val currentAlarmGen = _state.value.alarmGenerations[alarmId]
            val isGlobal = _state.value.isGlobalReconciling
            val snapshot = _state.value.reconcilingAlarmIds - alarmId
            Triple(currentAlarmGen, isGlobal, snapshot)
        }

        val projection = try {
            val alarms = alarmDao.getAllNormalAlarms()
            val exceptions = scheduleExceptionDao.getAll()
            val authorities = scheduleAuthorityDao.getAllAuthorities()

            projector.projectUpcomingSchedule(
                alarms = alarms,
                nowEpochMs = nowEpochMs,
                horizonDays = horizonDays,
                evaluationZoneId = evaluationZoneId,
                exceptions = exceptions,
                authorities = authorities,
                holidayClassifier = holidayClassifier,
                holidaySnapshotProvider = holidaySnapshotProvider,
                activeFiringAlarmIds = activeFiringAlarmIds,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                reconcilingAlarmIds = reconcilingSnapshot,
                isGlobalReconciling = isGlobalAtStart,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )
        } catch (e: Exception) {
            Log.e(TAG, "Reconcile failed for alarm $alarmId; preserving invalidation intent for retry", e)
            throw e
        }

        mutex.withLock {
            val currentAlarmGen = _state.value.alarmGenerations[alarmId]
            if (startAlarmGen != null && currentAlarmGen == startAlarmGen) {
                val newState = _state.value.copy(
                    alarmGenerations = _state.value.alarmGenerations - alarmId,
                    alarmReasons = _state.value.alarmReasons - alarmId,
                    version = _state.value.version + 1
                )
                _state.value = newState
                syncPublicStateFlows(newState)
            } else {
                val newState = _state.value.copy(
                    version = _state.value.version + 1
                )
                _state.value = newState
                syncPublicStateFlows(newState)
            }
        }
        return projection
    }

    suspend fun reconcileAll(
        nowEpochMs: Long = System.currentTimeMillis(),
        horizonDays: Int = UpcomingScheduleProjector.DEFAULT_HORIZON_DAYS,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        holidayClassifier: LocalHolidayClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
        holidaySnapshotProvider: ((LocalDate) -> HolidaySnapshot)? = null,
        activeFiringAlarmIds: Set<Long> = emptySet(),
        consumedOccurrenceKeys: Set<String> = emptySet(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): UpcomingScheduleProjection {
        val (startGlobalGen, startAlarmGens) = mutex.withLock {
            Pair(_state.value.globalGeneration, _state.value.alarmGenerations)
        }

        val projection = try {
            val alarms = alarmDao.getAllNormalAlarms()
            val exceptions = scheduleExceptionDao.getAll()
            val authorities = scheduleAuthorityDao.getAllAuthorities()

            projector.projectUpcomingSchedule(
                alarms = alarms,
                nowEpochMs = nowEpochMs,
                horizonDays = horizonDays,
                evaluationZoneId = evaluationZoneId,
                exceptions = exceptions,
                authorities = authorities,
                holidayClassifier = holidayClassifier,
                holidaySnapshotProvider = holidaySnapshotProvider,
                activeFiringAlarmIds = activeFiringAlarmIds,
                consumedOccurrenceKeys = consumedOccurrenceKeys,
                reconcilingAlarmIds = emptySet(),
                isGlobalReconciling = false,
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )
        } catch (e: Exception) {
            Log.e(TAG, "ReconcileAll failed; preserving invalidation intent for retry", e)
            throw e
        }

        mutex.withLock {
            val currentGlobalGen = _state.value.globalGeneration
            val currentAlarmGens = _state.value.alarmGenerations
            val currentAlarmReasons = _state.value.alarmReasons

            val newGlobalGen = if (startGlobalGen != null && currentGlobalGen == startGlobalGen) null else currentGlobalGen
            val newGlobalReason = if (newGlobalGen == null) null else _state.value.globalReason

            val updatedAlarmGens = currentAlarmGens.toMutableMap()
            val updatedAlarmReasons = currentAlarmReasons.toMutableMap()
            for ((alarmId, startGen) in startAlarmGens) {
                if (currentAlarmGens[alarmId] == startGen) {
                    updatedAlarmGens.remove(alarmId)
                    updatedAlarmReasons.remove(alarmId)
                }
            }

            val newState = _state.value.copy(
                alarmGenerations = updatedAlarmGens,
                alarmReasons = updatedAlarmReasons,
                globalGeneration = newGlobalGen,
                globalReason = newGlobalReason,
                version = _state.value.version + 1
            )
            _state.value = newState
            syncPublicStateFlows(newState)
        }
        return projection
    }

    suspend fun getCurrentProjection(
        nowEpochMs: Long = System.currentTimeMillis(),
        horizonDays: Int = UpcomingScheduleProjector.DEFAULT_HORIZON_DAYS,
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        holidayClassifier: LocalHolidayClassifier = LocalHolidayClassifier.ALL_UNKNOWN,
        holidaySnapshotProvider: ((LocalDate) -> HolidaySnapshot)? = null,
        activeFiringAlarmIds: Set<Long> = emptySet(),
        consumedOccurrenceKeys: Set<String> = emptySet(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): UpcomingScheduleProjection {
        val s = _state.value
        val alarms = alarmDao.getAllNormalAlarms()
        val exceptions = scheduleExceptionDao.getAll()
        val authorities = scheduleAuthorityDao.getAllAuthorities()

        return projector.projectUpcomingSchedule(
            alarms = alarms,
            nowEpochMs = nowEpochMs,
            horizonDays = horizonDays,
            evaluationZoneId = evaluationZoneId,
            exceptions = exceptions,
            authorities = authorities,
            holidayClassifier = holidayClassifier,
            holidaySnapshotProvider = holidaySnapshotProvider,
            activeFiringAlarmIds = activeFiringAlarmIds,
            consumedOccurrenceKeys = consumedOccurrenceKeys,
            reconcilingAlarmIds = s.reconcilingAlarmIds,
            isGlobalReconciling = s.isGlobalReconciling,
            allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
        )
    }

    fun observeProjection(
        nowEpochMsProvider: () -> Long = { System.currentTimeMillis() },
        horizonDays: Int = UpcomingScheduleProjector.DEFAULT_HORIZON_DAYS,
        evaluationZoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() },
        holidayClassifierProvider: suspend () -> LocalHolidayClassifier = { LocalHolidayClassifier.ALL_UNKNOWN },
        holidaySnapshotProvider: ((LocalDate) -> HolidaySnapshot)? = null,
        activeFiringAlarmIdsProvider: () -> Set<Long> = { emptySet() },
        consumedOccurrenceKeysProvider: () -> Set<String> = { emptySet() },
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): Flow<UpcomingScheduleProjection> {
        val alarmsDbFlow: Flow<Any?> = try {
            alarmDao.observeAll().drop(1)
        } catch (e: Throwable) {
            kotlinx.coroutines.flow.flow { throw e }
        }

        val exceptionsDbFlow: Flow<Any?> = try {
            scheduleExceptionDao.observeAll().drop(1)
        } catch (e: Throwable) {
            kotlinx.coroutines.flow.flow { throw e }
        }

        val authoritiesDbFlow: Flow<Any?> = try {
            scheduleAuthorityDao.observeAllAuthorities().drop(1)
        } catch (e: Throwable) {
            kotlinx.coroutines.flow.flow { throw e }
        }

        val triggers: Flow<Any?> = merge(_state, alarmsDbFlow, exceptionsDbFlow, authoritiesDbFlow)

        return triggers.map {
            getCurrentProjection(
                nowEpochMs = nowEpochMsProvider(),
                horizonDays = horizonDays,
                evaluationZoneId = evaluationZoneIdProvider(),
                holidayClassifier = holidayClassifierProvider(),
                holidaySnapshotProvider = holidaySnapshotProvider,
                activeFiringAlarmIds = activeFiringAlarmIdsProvider(),
                consumedOccurrenceKeys = consumedOccurrenceKeysProvider(),
                allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
            )
        }
    }

    suspend fun clearInvalidation(alarmId: Long) {
        mutex.withLock {
            val newState = _state.value.copy(
                alarmGenerations = _state.value.alarmGenerations - alarmId,
                alarmReasons = _state.value.alarmReasons - alarmId,
                version = _state.value.version + 1
            )
            _state.value = newState
            syncPublicStateFlows(newState)
        }
    }

    suspend fun clearAllInvalidations() {
        mutex.withLock {
            val newState = _state.value.copy(
                alarmGenerations = emptyMap(),
                alarmReasons = emptyMap(),
                globalGeneration = null,
                globalReason = null,
                version = _state.value.version + 1
            )
            _state.value = newState
            syncPublicStateFlows(newState)
        }
    }
}
