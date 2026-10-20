package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

enum class BootstrapDecision {
    ADOPT,
    RECONCILE,
    QUARANTINE
}

sealed interface OwnerResult {
    data class OsAccepted(val revision: Long, val actionKey: String?) : OwnerResult
    data class PermissionBlocked(val revision: Long) : OwnerResult
    data class Superseded(val currentRevision: Long) : OwnerResult
    data class OutcomeUnknown(val revision: Long, val cause: Throwable) : OwnerResult
    data class ConflictingAction(val actionKey: String) : OwnerResult
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
 * - Validates duplicate actionKey against operation kind and trigger target; rejects conflicting actions.
 * - Re-executes OS apply for pending RECONCILING actions; ignores only confirmed SETTLED matching duplicates.
 * - Checks exact alarm capability before claim and OS action for SET operations.
 * - CAS claims and increments revision by 1, with Long.MAX_VALUE overflow protection.
 * - Validates settlement with matching actionKey and claimed revision.
 * - Re-reads authority after OS action to detect post-operation convergence/superseding, re-converging OS slot to latest.
 * - Preserves coroutine CancellationException without swallowing.
 */
@Singleton
class ScheduleAuthorityOwner @Inject constructor(
    private val authorityDao: ScheduleAuthorityDao,
    private val exactPermissionChecker: () -> Boolean
) {

    private val mutexMap = ConcurrentHashMap<Long, Mutex>()

    private fun mutexFor(alarmId: Long): Mutex {
        return mutexMap.computeIfAbsent(alarmId) { Mutex() }
    }

    fun evaluateBootstrap(authority: ScheduleAuthority?): BootstrapDecision {
        if (authority == null) return BootstrapDecision.QUARANTINE
        return when (authority.reconcileState) {
            "SETTLED" -> BootstrapDecision.ADOPT
            "RECONCILING" -> BootstrapDecision.RECONCILE
            else -> BootstrapDecision.QUARANTINE
        }
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

            // Duplicate action token check
            if (actionKey != null && authority.actionKey == actionKey) {
                if (authority.desiredOperation != desiredOperation || authority.desiredTriggerMillis != desiredTriggerMillis) {
                    Log.w("ScheduleAuthorityOwner", "Conflicting action for actionKey '$actionKey' on alarm $alarmId; rejecting")
                    return@withLock OwnerResult.ConflictingAction(actionKey)
                }
                if (authority.reconcileState == "SETTLED") {
                    Log.i("ScheduleAuthorityOwner", "Duplicate actionKey '$actionKey' for alarm $alarmId already SETTLED; ignoring")
                    return@withLock OwnerResult.DuplicateIgnored
                }
                // If RECONCILING, proceed to re-execute OS action without ignoring
            }

            // Check exact alarm permission for SET operations BEFORE claim and OS action
            if (desiredOperation == "SET" && !exactPermissionChecker()) {
                Log.w("ScheduleAuthorityOwner", "Exact alarm capability unavailable for alarm $alarmId")
                return@withLock OwnerResult.PermissionBlocked(authority.scheduleRevision)
            }

            val expectedRev = authority.scheduleRevision
            if (expectedRev >= Long.MAX_VALUE) {
                Log.e("ScheduleAuthorityOwner", "scheduleRevision overflow for alarm $alarmId")
                return@withLock OwnerResult.Superseded(Long.MAX_VALUE)
            }

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
            } catch (e: CancellationException) {
                Log.w("ScheduleAuthorityOwner", "OS operation cancelled for alarm $alarmId at rev $claimedRev")
                throw e
            } catch (e: Exception) {
                Log.e("ScheduleAuthorityOwner", "OS operation threw exception for alarm $alarmId at rev $claimedRev", e)
                return@withLock OwnerResult.OutcomeUnknown(claimedRev, e)
            }

            if (!osSuccess) {
                Log.w("ScheduleAuthorityOwner", "OS operation returned false for alarm $alarmId at rev $claimedRev")
                return@withLock OwnerResult.OutcomeUnknown(claimedRev, IllegalStateException("OS action returned false"))
            }

            // Re-read current authority from DB after OS action to detect convergence/superseding
            val postOsAuth = authorityDao.getAuthorityByAlarmId(alarmId)
            if (postOsAuth != null && postOsAuth.scheduleRevision > claimedRev) {
                Log.w("ScheduleAuthorityOwner", "Post-OS re-read detected newer revision ${postOsAuth.scheduleRevision} for alarm $alarmId; re-converging OS slot")
                // Re-converge OS slot to latest desired state
                osAction(postOsAuth)
                return@withLock OwnerResult.Superseded(postOsAuth.scheduleRevision)
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
                if (current != null && current.scheduleRevision > claimedRev) {
                    osAction(current)
                }
                Log.w("ScheduleAuthorityOwner", "Settlement for alarm $alarmId at rev $claimedRev was superseded by rev ${current?.scheduleRevision}")
                return@withLock OwnerResult.Superseded(current?.scheduleRevision ?: claimedRev)
            }

            OwnerResult.OsAccepted(claimedRev, actionKey)
        }
    }
}
