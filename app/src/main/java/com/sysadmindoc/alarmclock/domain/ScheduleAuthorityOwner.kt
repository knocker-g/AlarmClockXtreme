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
 * - Single injected owner-owned [MainSlotApplier] transport maps current authority to OS SET/CANCEL slot operation.
 * - Rejects unknown or unsupported desiredOperation values (only 'SET' and 'CANCEL' are valid).
 * - Full ownership validation including alarmId, instanceToken, restoreEpoch, scheduleRevision, actionKey, desiredOperation.
 * - Bounded convergence loop (max 5 attempts) to prevent infinite looping while leaving durable 'RECONCILING' state on timeout.
 * - Dedicated [replayBootOsOperation] handles post-boot OS re-registration without fabricating synthetic actionKeys or advancing revision.
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
    private val mainSlotApplier: MainSlotApplier,
    private val exactPermissionChecker: () -> Boolean
) {

    companion object {
        private const val TAG = "ScheduleAuthorityOwner"
        private const val MAX_CONVERGENCE_ATTEMPTS = 5
        private val SUPPORTED_OPERATIONS = setOf("SET", "CANCEL")
    }

    private val mutexMap = ConcurrentHashMap<Long, Mutex>()

    private fun mutexFor(alarmId: Long): Mutex {
        return mutexMap.computeIfAbsent(alarmId) { Mutex() }
    }

    fun evaluateBootstrap(
        authority: ScheduleAuthority?,
        definitionExists: Boolean,
        sessionValid: Boolean
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
        expectedRevision: Long? = null,
        bypassRevisionCheck: Boolean = false
    ): OwnerResult {
        return mutexFor(alarmId).withLock {
            val initialAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                ?: return@withLock OwnerResult.AuthorityNotFound

            if (initialAuth.reconcileState == "QUARANTINE") {
                Log.w(TAG, "Alarm $alarmId is QUARANTINED; rejecting operation execution")
                return@withLock OwnerResult.Superseded(initialAuth.scheduleRevision)
            }

            if (desiredOperation !in SUPPORTED_OPERATIONS) {
                Log.e(TAG, "Unsupported desiredOperation '$desiredOperation' for alarm $alarmId")
                return@withLock OwnerResult.OutcomeUnknown(
                    initialAuth.scheduleRevision,
                    IllegalArgumentException("Unsupported desiredOperation: '$desiredOperation'")
                )
            }

            // Duplicate action token check
            if (actionKey != null && initialAuth.actionKey == actionKey) {
                if (initialAuth.desiredOperation != desiredOperation || initialAuth.desiredTriggerMillis != desiredTriggerMillis) {
                    Log.w(TAG, "Conflicting action for actionKey '$actionKey' on alarm $alarmId; rejecting")
                    return@withLock OwnerResult.ConflictingAction(actionKey)
                }
                if (initialAuth.reconcileState == "SETTLED") {
                    Log.i(TAG, "Duplicate actionKey '$actionKey' for alarm $alarmId already SETTLED; ignoring")
                    return@withLock OwnerResult.DuplicateIgnored
                }
                // If RECONCILING, proceed to re-apply OS action for current authority
            } else {
                // Fail-Closed Caller-Qualified Stale Admission Gate for new actions:
                if (!bypassRevisionCheck) {
                    if (expectedRevision == null || initialAuth.scheduleRevision != expectedRevision) {
                        Log.w(TAG, "Caller expectedRevision $expectedRevision does not match current DB revision ${initialAuth.scheduleRevision} for alarm $alarmId; superseding")
                        return@withLock OwnerResult.Superseded(initialAuth.scheduleRevision)
                    }
                }
                // Check exact alarm permission for SET operations BEFORE claim
                if (desiredOperation == "SET" && !exactPermissionChecker()) {
                    Log.w(TAG, "Exact alarm capability unavailable for alarm $alarmId")
                    return@withLock OwnerResult.PermissionBlocked(initialAuth.scheduleRevision)
                }

                val expectedRev = initialAuth.scheduleRevision
                if (expectedRev >= Long.MAX_VALUE) {
                    Log.e(TAG, "scheduleRevision overflow for alarm $alarmId")
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

            // Bounded Convergence Loop: Apply OS operation for latest authority state (max 5 attempts)
            var attempts = 0
            while (attempts < MAX_CONVERGENCE_ATTEMPTS) {
                attempts++
                val currentAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                    ?: return@withLock OwnerResult.AuthorityNotFound

                // Verify full ownership fingerprint (instanceToken, restoreEpoch)
                if (currentAuth.instanceToken != initialAuth.instanceToken || currentAuth.restoreEpoch != initialAuth.restoreEpoch) {
                    Log.w(TAG, "Incarnation changed for alarm $alarmId during convergence; superseding")
                    return@withLock OwnerResult.Superseded(currentAuth.scheduleRevision)
                }

                if (currentAuth.desiredOperation !in SUPPORTED_OPERATIONS) {
                    Log.e(TAG, "Current desiredOperation '${currentAuth.desiredOperation}' unsupported for alarm $alarmId")
                    return@withLock OwnerResult.OutcomeUnknown(
                        currentAuth.scheduleRevision,
                        IllegalArgumentException("Unsupported desiredOperation: '${currentAuth.desiredOperation}'")
                    )
                }

                // Check permission for SET before applying
                if (currentAuth.desiredOperation == "SET" && !exactPermissionChecker()) {
                    Log.w(TAG, "Exact alarm capability unavailable for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    return@withLock OwnerResult.PermissionBlocked(currentAuth.scheduleRevision)
                }

                // Apply OS action via injected MainSlotApplier
                val osSuccess = try {
                    mainSlotApplier.applySlotOperation(currentAuth)
                } catch (e: CancellationException) {
                    Log.w(TAG, "OS operation cancelled for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "OS operation threw exception for alarm $alarmId at rev ${currentAuth.scheduleRevision}", e)
                    return@withLock OwnerResult.OutcomeUnknown(currentAuth.scheduleRevision, e)
                }

                if (!osSuccess) {
                    Log.w(TAG, "OS operation returned false for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    return@withLock OwnerResult.OutcomeUnknown(currentAuth.scheduleRevision, IllegalStateException("OS action returned false"))
                }

                // Re-read authority after OS apply to check if a newer revision was admitted concurrently
                val latestAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                if (latestAuth != null && (latestAuth.scheduleRevision > currentAuth.scheduleRevision ||
                        latestAuth.instanceToken != currentAuth.instanceToken ||
                        latestAuth.restoreEpoch != currentAuth.restoreEpoch)) {
                    Log.w(TAG, "Post-OS re-read detected newer revision/incarnation for alarm $alarmId; re-converging...")
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
                    Log.w(TAG, "Settlement superseded for alarm $alarmId; re-converging to rev ${postSettleAuth.scheduleRevision}")
                    continue
                } else {
                    return@withLock OwnerResult.Superseded(currentAuth.scheduleRevision)
                }
            }

            Log.e(TAG, "Bounded convergence limit ($MAX_CONVERGENCE_ATTEMPTS attempts) reached for alarm $alarmId")
            val finalUnsettled = authorityDao.getAuthorityByAlarmId(alarmId)
            OwnerResult.OutcomeUnknown(
                finalUnsettled?.scheduleRevision ?: 0L,
                IllegalStateException("Bounded convergence limit reached")
            )
        }
    }

    /**
     * Dedicated post-boot OS re-registration contract for live authorities.
     * Re-applies OS slot operation using current ScheduleAuthority state WITHOUT advancing scheduleRevision or fabricating actionKey!
     */
    suspend fun replayBootOsOperation(alarmId: Long): OwnerResult {
        return mutexFor(alarmId).withLock {
            val authority = authorityDao.getAuthorityByAlarmId(alarmId)
                ?: return@withLock OwnerResult.AuthorityNotFound

            if (authority.reconcileState == "QUARANTINE") {
                Log.w(TAG, "Boot replay skipped for alarm $alarmId: authority is QUARANTINED")
                return@withLock OwnerResult.Superseded(authority.scheduleRevision)
            }

            if (authority.lifecycle != "LIVE") {
                Log.i(TAG, "Boot replay skipped for alarm $alarmId: lifecycle is ${authority.lifecycle}")
                return@withLock OwnerResult.DuplicateIgnored
            }

            if (authority.desiredOperation !in SUPPORTED_OPERATIONS) {
                Log.e(TAG, "Unsupported desiredOperation '${authority.desiredOperation}' for boot replay on alarm $alarmId")
                return@withLock OwnerResult.OutcomeUnknown(
                    authority.scheduleRevision,
                    IllegalArgumentException("Unsupported desiredOperation: '${authority.desiredOperation}'")
                )
            }

            // Check exact alarm permission for SET
            if (authority.desiredOperation == "SET" && !exactPermissionChecker()) {
                Log.w(TAG, "Exact alarm capability unavailable during boot replay for alarm $alarmId")
                return@withLock OwnerResult.PermissionBlocked(authority.scheduleRevision)
            }

            // Apply OS action via injected MainSlotApplier
            val osSuccess = try {
                mainSlotApplier.applySlotOperation(authority)
            } catch (e: CancellationException) {
                Log.w(TAG, "Boot OS replay cancelled for alarm $alarmId at rev ${authority.scheduleRevision}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Boot OS replay threw exception for alarm $alarmId at rev ${authority.scheduleRevision}", e)
                return@withLock OwnerResult.OutcomeUnknown(authority.scheduleRevision, e)
            }

            if (!osSuccess) {
                Log.w(TAG, "Boot OS replay returned false for alarm $alarmId at rev ${authority.scheduleRevision}")
                return@withLock OwnerResult.OutcomeUnknown(authority.scheduleRevision, IllegalStateException("Boot OS action returned false"))
            }

            // Post-OS re-read: verify no newer revision or changed incarnation arrived during OS apply
            val latestAuth = authorityDao.getAuthorityByAlarmId(alarmId)
            if (latestAuth == null || latestAuth.scheduleRevision != authority.scheduleRevision ||
                latestAuth.instanceToken != authority.instanceToken || latestAuth.restoreEpoch != authority.restoreEpoch) {
                Log.w(TAG, "Boot OS replay detected changed revision/incarnation for alarm $alarmId; superseding")
                return@withLock OwnerResult.Superseded(latestAuth?.scheduleRevision ?: authority.scheduleRevision)
            }

            // Ensure reconcileState is SETTLED in DB (retaining original scheduleRevision and actionKey 100% UNCHANGED!)
            if (latestAuth.reconcileState != "SETTLED") {
                authorityDao.settleAuthority(
                    alarmId = alarmId,
                    instanceToken = latestAuth.instanceToken,
                    restoreEpoch = latestAuth.restoreEpoch,
                    claimedRevision = latestAuth.scheduleRevision,
                    expectedActionKey = latestAuth.actionKey
                )
            }

            OwnerResult.OsAccepted(latestAuth.scheduleRevision, latestAuth.actionKey)
        }
    }
}
