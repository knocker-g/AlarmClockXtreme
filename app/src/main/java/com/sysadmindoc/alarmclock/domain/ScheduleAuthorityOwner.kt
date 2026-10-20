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

fun interface MainSlotApplier {
    suspend fun applySlotOperation(authority: ScheduleAuthority): Boolean
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
 * - Uses [MainSlotApplier] to map current authority to OS SET/CANCEL slot operation.
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

    fun evaluateBootstrap(
        authority: ScheduleAuthority?,
        definitionExists: Boolean = true,
        sessionValid: Boolean = true
    ): BootstrapDecision {
        if (authority == null || !definitionExists) return BootstrapDecision.QUARANTINE
        return when {
            authority.reconcileState == "SETTLED" && sessionValid -> BootstrapDecision.ADOPT
            authority.reconcileState == "RECONCILING" -> BootstrapDecision.RECONCILE
            else -> BootstrapDecision.QUARANTINE
        }
    }

    suspend fun executeOperation(
        alarmId: Long,
        desiredOperation: String,
        desiredTriggerMillis: Long?,
        actionKey: String?,
        applier: MainSlotApplier
    ): OwnerResult {
        return mutexFor(alarmId).withLock {
            val initialAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                ?: return@withLock OwnerResult.AuthorityNotFound

            // Duplicate action token check
            if (actionKey != null && initialAuth.actionKey == actionKey) {
                if (initialAuth.desiredOperation != desiredOperation || initialAuth.desiredTriggerMillis != desiredTriggerMillis) {
                    Log.w("ScheduleAuthorityOwner", "Conflicting action for actionKey '$actionKey' on alarm $alarmId; rejecting")
                    return@withLock OwnerResult.ConflictingAction(actionKey)
                }
                if (initialAuth.reconcileState == "SETTLED") {
                    Log.i("ScheduleAuthorityOwner", "Duplicate actionKey '$actionKey' for alarm $alarmId already SETTLED; ignoring")
                    return@withLock OwnerResult.DuplicateIgnored
                }
                // If RECONCILING, proceed to re-apply OS action for current authority
            } else {
                // Check exact alarm permission for SET operations BEFORE claim
                if (desiredOperation == "SET" && !exactPermissionChecker()) {
                    Log.w("ScheduleAuthorityOwner", "Exact alarm capability unavailable for alarm $alarmId")
                    return@withLock OwnerResult.PermissionBlocked(initialAuth.scheduleRevision)
                }

                val expectedRev = initialAuth.scheduleRevision
                if (expectedRev >= Long.MAX_VALUE) {
                    Log.e("ScheduleAuthorityOwner", "scheduleRevision overflow for alarm $alarmId")
                    return@withLock OwnerResult.Superseded(Long.MAX_VALUE)
                }

                val claimed = authorityDao.casClaimAuthority(
                    alarmId = alarmId,
                    instanceToken = initialAuth.instanceToken,
                    restoreEpoch = initialAuth.restoreEpoch,
                    expectedRevision = expectedRev,
                    expectedLifecycle = initialAuth.lifecycle,
                    desiredOperation = desiredOperation,
                    desiredTriggerMillis = desiredTriggerMillis,
                    actionKey = actionKey
                )

                if (claimed != 1) {
                    val current = authorityDao.getAuthorityByAlarmId(alarmId)
                    return@withLock OwnerResult.Superseded(current?.scheduleRevision ?: expectedRev)
                }
            }

            // Convergence Loop: Apply OS operation for latest authority state
            while (true) {
                val currentAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                    ?: return@withLock OwnerResult.AuthorityNotFound

                // Check permission for SET before applying
                if (currentAuth.desiredOperation == "SET" && !exactPermissionChecker()) {
                    Log.w("ScheduleAuthorityOwner", "Exact alarm capability unavailable for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    return@withLock OwnerResult.PermissionBlocked(currentAuth.scheduleRevision)
                }

                // Apply OS action via MainSlotApplier
                val osSuccess = try {
                    applier.applySlotOperation(currentAuth)
                } catch (e: CancellationException) {
                    Log.w("ScheduleAuthorityOwner", "OS operation cancelled for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    throw e
                } catch (e: Exception) {
                    Log.e("ScheduleAuthorityOwner", "OS operation threw exception for alarm $alarmId at rev ${currentAuth.scheduleRevision}", e)
                    return@withLock OwnerResult.OutcomeUnknown(currentAuth.scheduleRevision, e)
                }

                if (!osSuccess) {
                    Log.w("ScheduleAuthorityOwner", "OS operation returned false for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    return@withLock OwnerResult.OutcomeUnknown(currentAuth.scheduleRevision, IllegalStateException("OS action returned false"))
                }

                // Re-read authority after OS apply to check if a newer revision was admitted concurrently
                val latestAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                if (latestAuth != null && latestAuth.scheduleRevision > currentAuth.scheduleRevision) {
                    Log.w("ScheduleAuthorityOwner", "Post-OS re-read detected newer revision ${latestAuth.scheduleRevision} > ${currentAuth.scheduleRevision} for alarm $alarmId; converging...")
                    continue
                }

                // Settle authority
                val settled = authorityDao.settleAuthority(
                    alarmId = alarmId,
                    instanceToken = currentAuth.instanceToken,
                    restoreEpoch = currentAuth.restoreEpoch,
                    claimedRevision = currentAuth.scheduleRevision,
                    expectedActionKey = currentAuth.actionKey
                )

                if (settled == 1) {
                    return@withLock OwnerResult.OsAccepted(currentAuth.scheduleRevision, currentAuth.actionKey)
                }

                // Settlement failed due to concurrent claim; re-read and loop to converge!
                val postSettleAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                if (postSettleAuth != null && postSettleAuth.scheduleRevision > currentAuth.scheduleRevision) {
                    Log.w("ScheduleAuthorityOwner", "Settlement superseded for alarm $alarmId; re-converging to rev ${postSettleAuth.scheduleRevision}")
                    continue
                } else {
                    return@withLock OwnerResult.Superseded(currentAuth.scheduleRevision)
                }
            }

            @Suppress("UNREACHABLE_CODE")
            OwnerResult.AuthorityNotFound
        }
    }
}
