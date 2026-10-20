package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

sealed interface OwnerResult {
    data class OsAccepted(val revision: Long, val actionKey: String?) : OwnerResult
    data class PermissionBlocked(val revision: Long) : OwnerResult
    data class Superseded(val currentRevision: Long) : OwnerResult
    data class OutcomeUnknown(val revision: Long, val cause: Throwable) : OwnerResult
    object DuplicateIgnored : OwnerResult
    object AuthorityNotFound : OwnerResult
}

/**
 * Shared serialized owner abstraction for MAIN_ALARM_SLOT operations.
 * Maintained behind an inactive integration boundary — existing runtime AlarmScheduler calls remain unaffected.
 *
 * Guarantees:
 * - OS effects must be serialized per alarmId via in-process Mutex map.
 * - Reads current ScheduleAuthority state before claim.
 * - CAS claims and increments revision by 1.
 * - Duplicate same-action submissions skip revision advance.
 * - Validates settlement with matching actionKey and claimed revision.
 */
@Singleton
class ScheduleAuthorityOwner @Inject constructor(
    private val authorityDao: ScheduleAuthorityDao,
    private val exactPermissionChecker: () -> Boolean = { true }
) {

    private val mutexMap = ConcurrentHashMap<Long, Mutex>()

    private fun mutexFor(alarmId: Long): Mutex {
        return mutexMap.computeIfAbsent(alarmId) { Mutex() }
    }

    suspend fun executeOperation(
        alarmId: Long,
        desiredOperation: String,
        desiredTriggerMillis: Long?,
        actionKey: String?,
        osAction: suspend (authority: ScheduleAuthority) -> Boolean
    ): OwnerResult {
        return mutexFor(alarmId).withLock {
            val authority = authorityDao.getAuthorityByAlarmId(alarmId)
                ?: return@withLock OwnerResult.AuthorityNotFound

            // Duplicate action token check: ignore duplicate submission without advancing revision
            if (actionKey != null && authority.actionKey == actionKey) {
                Log.i("ScheduleAuthorityOwner", "Duplicate actionKey '$actionKey' for alarm $alarmId ignored without revision advance")
                return@withLock OwnerResult.DuplicateIgnored
            }

            val expectedRev = authority.scheduleRevision
            val claimedRev = expectedRev + 1L

            // CAS claim authority transition to RECONCILING
            val claimed = authorityDao.casClaimAuthority(
                alarmId = alarmId,
                instanceToken = authority.instanceToken,
                restoreEpoch = authority.restoreEpoch,
                expectedRevision = expectedRev,
                expectedLifecycle = authority.lifecycle,
                desiredOperation = desiredOperation,
                desiredTriggerMillis = desiredTriggerMillis,
                actionKey = actionKey
            )

            if (claimed != 1) {
                val current = authorityDao.getAuthorityByAlarmId(alarmId)
                return@withLock OwnerResult.Superseded(current?.scheduleRevision ?: expectedRev)
            }

            // Check exact alarm permission for SET operations
            if (desiredOperation == "SET" && !exactPermissionChecker()) {
                Log.w("ScheduleAuthorityOwner", "Exact alarm capability unavailable for alarm $alarmId")
                return@withLock OwnerResult.PermissionBlocked(claimedRev)
            }

            // Apply OS action
            val osSuccess = try {
                val updatedAuthority = authority.copy(
                    scheduleRevision = claimedRev,
                    desiredOperation = desiredOperation,
                    desiredTriggerMillis = desiredTriggerMillis,
                    reconcileState = "RECONCILING",
                    actionKey = actionKey
                )
                osAction(updatedAuthority)
            } catch (e: Exception) {
                Log.e("ScheduleAuthorityOwner", "OS operation threw exception for alarm $alarmId at rev $claimedRev", e)
                return@withLock OwnerResult.OutcomeUnknown(claimedRev, e)
            }

            if (!osSuccess) {
                Log.w("ScheduleAuthorityOwner", "OS operation returned false for alarm $alarmId at rev $claimedRev")
                return@withLock OwnerResult.OutcomeUnknown(claimedRev, IllegalStateException("OS action returned false"))
            }

            // Settle authority after successful OS apply
            val settled = authorityDao.settleAuthority(
                alarmId = alarmId,
                instanceToken = authority.instanceToken,
                restoreEpoch = authority.restoreEpoch,
                claimedRevision = claimedRev,
                expectedActionKey = actionKey
            )

            if (settled != 1) {
                val current = authorityDao.getAuthorityByAlarmId(alarmId)
                Log.w("ScheduleAuthorityOwner", "Settlement for alarm $alarmId at rev $claimedRev was superseded by rev ${current?.scheduleRevision}")
                return@withLock OwnerResult.Superseded(current?.scheduleRevision ?: claimedRev)
            }

            OwnerResult.OsAccepted(claimedRev, actionKey)
        }
    }
}
