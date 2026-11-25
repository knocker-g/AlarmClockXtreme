package com.sysadmindoc.alarmclock.domain

import android.content.Context
import android.util.Log
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

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
class ScheduleAuthorityOwner private constructor(
    private val authorityDao: ScheduleAuthorityDao,
    private val mainSlotApplier: MainSlotApplier,
    private val exactPermissionChecker: () -> Boolean,
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") internalMarker: Boolean
) {

    @Inject
    constructor(
        authorityDao: ScheduleAuthorityDao,
        mainSlotApplier: MainSlotApplier,
        exactPermissionChecker: () -> Boolean,
        @ApplicationContext context: Context
    ) : this(authorityDao, mainSlotApplier, exactPermissionChecker, context, true)

    constructor(
        authorityDao: ScheduleAuthorityDao,
        mainSlotApplier: MainSlotApplier,
        exactPermissionChecker: () -> Boolean
    ) : this(authorityDao, mainSlotApplier, exactPermissionChecker, null, false)

    private class RecoveryGateLease(
        val leaseId: String,
        val canonicalJournalPath: String,
        val active: AtomicBoolean,
        @Volatile var gateJob: Job?
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    internal class ActiveRecoveryGateContext private constructor(
        private val lease: RecoveryGateLease?,
        private val scopeHop: Int
    ) : AbstractCoroutineContextElement(Key), CopyableThreadContextElement<Unit> {
        companion object Key : CoroutineContext.Key<ActiveRecoveryGateContext> {
            private val INERT = ActiveRecoveryGateContext(lease = null, scopeHop = 2)

            internal suspend fun <T> withIssuedGateContext(
                canonicalJournalPath: String,
                registerLease: (leaseId: String, leaseObj: Any) -> Unit,
                unregisterLease: (leaseId: String, leaseObj: Any) -> Unit,
                block: suspend () -> T
            ): T {
                val lease = RecoveryGateLease(
                    leaseId = UUID.randomUUID().toString(),
                    canonicalJournalPath = canonicalJournalPath,
                    active = AtomicBoolean(true),
                    gateJob = null
                )
                registerLease(lease.leaseId, lease)
                return try {
                    withContext(ActiveRecoveryGateContext(lease = lease, scopeHop = 0)) {
                        lease.gateJob = kotlin.coroutines.coroutineContext[Job]
                        block()
                    }
                } finally {
                    lease.active.set(false)
                    lease.gateJob = null
                    unregisterLease(lease.leaseId, lease)
                }
            }
        }

        val canonicalJournalPath: String?
            get() = lease?.canonicalJournalPath

        override fun updateThreadContext(context: CoroutineContext) = Unit

        override fun restoreThreadContext(context: CoroutineContext, oldState: Unit) = Unit

        override fun copyForChild(): CopyableThreadContextElement<Unit> =
            if (lease != null && scopeHop == 0) {
                ActiveRecoveryGateContext(lease = lease, scopeHop = 1)
            } else {
                INERT
            }

        override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext =
            overwritingElement

        internal fun matchesActiveLease(
            expectedCanonicalPath: String,
            currentJob: Job?,
            lookupLease: (String) -> Any?
        ): Boolean {
            if (scopeHop != 1) return false
            val currentLease = lease ?: return false
            if (currentLease.canonicalJournalPath != expectedCanonicalPath) return false
            if (!currentLease.active.get()) return false
            if (lookupLease(currentLease.leaseId) !== currentLease) return false
            val leaseJob = currentLease.gateJob ?: return false
            if (!leaseJob.isActive || currentJob == null || !currentJob.isActive) return false
            return isSelfOrActiveChild(leaseJob, currentJob)
        }
    }

    internal class ActiveMainSlotLockContext private constructor(
        private val heldSlotMutexes: List<Mutex>
    ) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<ActiveMainSlotLockContext> {
            fun empty(): ActiveMainSlotLockContext = ActiveMainSlotLockContext(emptyList())
        }

        fun isHeld(mutex: Mutex): Boolean =
            heldSlotMutexes.any { it === mutex }

        fun plus(mutex: Mutex): ActiveMainSlotLockContext =
            if (isHeld(mutex)) this else ActiveMainSlotLockContext(heldSlotMutexes + mutex)
    }

    private class OwnerSlotEntry(
        owner: ScheduleAuthorityOwner,
        val identityHash: Int,
        val mutexMap: ConcurrentHashMap<Long, Mutex>,
        queue: ReferenceQueue<ScheduleAuthorityOwner>
    ) : WeakReference<ScheduleAuthorityOwner>(owner, queue)

    companion object {
        private const val TAG = "ScheduleAuthorityOwner"
        private const val MAX_CONVERGENCE_ATTEMPTS = 5
        private val SUPPORTED_OPERATIONS = setOf("SET", "CANCEL")

        private val ownerRegistryLock = Any()
        private val ownerReferenceQueue = ReferenceQueue<ScheduleAuthorityOwner>()
        private val ownerSlotRegistry = HashMap<Int, MutableList<OwnerSlotEntry>>()

        private fun drainStaleOwnerEntriesLocked() {
            while (true) {
                val ref = ownerReferenceQueue.poll() as? OwnerSlotEntry ?: break
                val bucket = ownerSlotRegistry[ref.identityHash] ?: continue
                bucket.remove(ref)
                if (bucket.isEmpty()) {
                    ownerSlotRegistry.remove(ref.identityHash)
                }
            }
        }

        private fun registerOwnerMutexMap(
            owner: ScheduleAuthorityOwner,
            mutexMap: ConcurrentHashMap<Long, Mutex>
        ) {
            val identityHash = System.identityHashCode(owner)
            synchronized(ownerRegistryLock) {
                drainStaleOwnerEntriesLocked()
                val bucket = ownerSlotRegistry.getOrPut(identityHash) { mutableListOf() }
                val alreadyPresent = bucket.any { it.get() === owner }
                if (!alreadyPresent) {
                    bucket.add(OwnerSlotEntry(owner, identityHash, mutexMap, ownerReferenceQueue))
                }
            }
        }

        private val activeGateLeases = ConcurrentHashMap<String, Any>()

        private fun isSelfOrActiveChild(parent: Job, target: Job): Boolean {
            if (parent === target) return true
            if (!parent.isActive) return false
            return parent.children.any { child -> isSelfOrActiveChild(child, target) }
        }

        private fun hasValidActiveGate(
            context: Context,
            callerContext: CoroutineContext
        ): Boolean {
            val activeGate = callerContext[ActiveRecoveryGateContext] ?: return false
            val canonicalPath = RestoreJournalCoordinator.canonicalJournalPath(context)
            val currentJob = callerContext[Job]
            if (!activeGate.matchesActiveLease(canonicalPath, currentJob) { leaseId -> activeGateLeases[leaseId] }) {
                return false
            }
            return RestoreJournalCoordinator.recoveryMutexFor(context).isLocked
        }

        private suspend fun <T> executeWithNewGateLease(
            context: Context,
            block: suspend () -> T
        ): T {
            val canonicalPath = RestoreJournalCoordinator.canonicalJournalPath(context)
            return ActiveRecoveryGateContext.withIssuedGateContext(
                canonicalJournalPath = canonicalPath,
                registerLease = { leaseId, leaseObj -> activeGateLeases[leaseId] = leaseObj },
                unregisterLease = { leaseId, leaseObj -> activeGateLeases.remove(leaseId, leaseObj) },
                block = block
            )
        }

        internal suspend fun <T> withAuthenticatedRecoveryGate(
            context: Context,
            onBlocked: suspend () -> T,
            block: suspend () -> T
        ): T {
            if (hasValidActiveGate(context, kotlin.coroutines.coroutineContext)) {
                return block()
            }
            return RestoreJournalCoordinator.withRecoveryGate(
                context = context,
                onBlocked = onBlocked
            ) {
                executeWithNewGateLease(context, block)
            }
        }

        internal fun <T> withAuthenticatedSyncRecoveryGate(
            context: Context,
            onBlocked: () -> T,
            block: suspend () -> T
        ): T {
            return RestoreJournalCoordinator.withSyncRecoveryGateNonBlocking(
                context = context,
                onBlocked = onBlocked
            ) {
                runBlocking {
                    executeWithNewGateLease(context, block)
                }
            }
        }

        /**
         * Identity-based lookup of [owner]'s per-`alarmId` slot [Mutex] that NEVER calls
         * `.equals()`, `.hashCode()`, or any instance method on [owner] (safe for strict MockK mocks).
         */
        internal fun sharedSlotMutexForOrNull(owner: ScheduleAuthorityOwner, alarmId: Long): Mutex? {
            val identityHash = System.identityHashCode(owner)
            val map = synchronized(ownerRegistryLock) {
                drainStaleOwnerEntriesLocked()
                ownerSlotRegistry[identityHash]?.firstOrNull { it.get() === owner }?.mutexMap
            } ?: return null
            return map.computeIfAbsent(alarmId) { Mutex() }
        }
    }

    private val mutexMap = ConcurrentHashMap<Long, Mutex>()

    init {
        registerOwnerMutexMap(this, mutexMap)
    }

    private fun mutexFor(alarmId: Long): Mutex {
        return mutexMap.computeIfAbsent(alarmId) { Mutex() }
    }

    private suspend fun <T> withOwnerRecoveryAndSlotLock(
        alarmId: Long,
        onL1Blocked: suspend () -> T,
        block: suspend () -> T
    ): T {
        val runWithL2: suspend () -> T = {
            val mutex = mutexFor(alarmId)
            val activeLocks = coroutineContext[ActiveMainSlotLockContext]
            if (activeLocks != null && activeLocks.isHeld(mutex)) {
                block()
            } else {
                mutex.withLock {
                    val updatedLocks = (activeLocks ?: ActiveMainSlotLockContext.empty()).plus(mutex)
                    withContext(updatedLocks) {
                        block()
                    }
                }
            }
        }
        val ctx = context ?: return runWithL2()
        return withAuthenticatedRecoveryGate(
            context = ctx,
            onBlocked = onL1Blocked,
            block = runWithL2
        )
    }

    /**
     * Inactive `MAIN_V2` per-`alarmId` single-writer slot lock hook reusing [mutexMap].
     *
     * Concurrency & Reentrancy Contract:
     * - Scope: SINGLE-PROCESS ONLY (in-heap [ConcurrentHashMap] of Kotlin [Mutex]es;
     *   does not survive process death or lock across separate processes).
     * - Acquisition Order: Acquire [withMainV2SlotLock] as per-[alarmId] guard under `L1`.
     * - Reentrancy: Same-coroutine nested acquisition for the same [Mutex] is tracked via
     *   [ActiveMainSlotLockContext].
     * - Unlock Guarantee: [Mutex.withLock] guarantees unlock on normal return, exception, and
     *   coroutine [CancellationException].
     */
    internal suspend fun <T> withMainV2SlotLock(
        alarmId: Long,
        block: suspend () -> T
    ): T {
        require(alarmId > 0L) { "alarmId must be positive for MAIN_V2 slot lock (got $alarmId)" }
        val mutex = mutexFor(alarmId)
        val activeLocks = coroutineContext[ActiveMainSlotLockContext]
        if (activeLocks != null && activeLocks.isHeld(mutex)) {
            return block()
        }
        return mutex.withLock {
            val updatedLocks = (activeLocks ?: ActiveMainSlotLockContext.empty()).plus(mutex)
            withContext(updatedLocks) {
                block()
            }
        }
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
        expectedRevision: Long? = null
    ): OwnerResult {
        return withOwnerRecoveryAndSlotLock(
            alarmId = alarmId,
            onL1Blocked = {
                OwnerResult.OutcomeUnknown(
                    0L,
                    IllegalStateException("RESTORE_PREPARED_FENCE")
                )
            }
        ) {
            val initialAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                ?: return@withOwnerRecoveryAndSlotLock OwnerResult.AuthorityNotFound

            if (initialAuth.reconcileState == "QUARANTINE") {
                Log.w(TAG, "Alarm $alarmId is QUARANTINED; rejecting operation execution")
                return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(initialAuth.scheduleRevision)
            }

            val meta = authorityDao.getMetaById(1)
            if (meta == null || meta.restoreEpoch <= 0L || initialAuth.restoreEpoch <= 0L || meta.restoreEpoch != initialAuth.restoreEpoch) {
                Log.w(TAG, "Alarm $alarmId restoreEpoch ${initialAuth.restoreEpoch} failed strict Meta epoch fence (meta=$meta); superseding")
                return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(initialAuth.scheduleRevision)
            }

            if (desiredOperation !in SUPPORTED_OPERATIONS) {
                Log.e(TAG, "Unsupported desiredOperation '$desiredOperation' for alarm $alarmId")
                return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(
                    initialAuth.scheduleRevision,
                    IllegalArgumentException("Unsupported desiredOperation: '$desiredOperation'")
                )
            }

            // Duplicate action token check
            if (actionKey != null && initialAuth.actionKey == actionKey) {
                if (initialAuth.desiredOperation != desiredOperation || initialAuth.desiredTriggerMillis != desiredTriggerMillis) {
                    Log.w(TAG, "Conflicting action for actionKey '$actionKey' on alarm $alarmId; rejecting")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.ConflictingAction(actionKey)
                }
                if (initialAuth.reconcileState == "SETTLED") {
                    Log.i(TAG, "Duplicate actionKey '$actionKey' for alarm $alarmId already SETTLED; ignoring")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.DuplicateIgnored
                }
                // If RECONCILING, proceed to re-apply OS action for current authority
            } else {
                // Fail-Closed Caller-Qualified Stale Admission Gate for new actions:
                if (expectedRevision == null || initialAuth.scheduleRevision != expectedRevision) {
                    Log.w(TAG, "Caller expectedRevision $expectedRevision does not match current DB revision ${initialAuth.scheduleRevision} for alarm $alarmId; superseding")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(initialAuth.scheduleRevision)
                }
                // Check exact alarm permission for SET operations BEFORE claim
                if (desiredOperation == "SET" && !exactPermissionChecker()) {
                    Log.w(TAG, "Exact alarm capability unavailable for alarm $alarmId")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.PermissionBlocked(initialAuth.scheduleRevision)
                }

                val expectedRev = initialAuth.scheduleRevision
                if (expectedRev >= Long.MAX_VALUE) {
                    Log.e(TAG, "scheduleRevision overflow for alarm $alarmId")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(Long.MAX_VALUE)
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
                    return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(current?.scheduleRevision ?: expectedRev)
                }
            }

            // Bounded Convergence Loop: Apply OS operation for latest authority state (max 5 attempts)
            var attempts = 0
            while (attempts < MAX_CONVERGENCE_ATTEMPTS) {
                attempts++
                val currentAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                    ?: return@withOwnerRecoveryAndSlotLock OwnerResult.AuthorityNotFound

                // Verify full ownership fingerprint (instanceToken, restoreEpoch)
                if (currentAuth.instanceToken != initialAuth.instanceToken || currentAuth.restoreEpoch != initialAuth.restoreEpoch) {
                    Log.w(TAG, "Incarnation changed for alarm $alarmId during convergence; superseding")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(currentAuth.scheduleRevision)
                }

                if (currentAuth.desiredOperation !in SUPPORTED_OPERATIONS) {
                    Log.e(TAG, "Current desiredOperation '${currentAuth.desiredOperation}' unsupported for alarm $alarmId")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(
                        currentAuth.scheduleRevision,
                        IllegalArgumentException("Unsupported desiredOperation: '${currentAuth.desiredOperation}'")
                    )
                }

                // Check permission for SET before applying
                if (currentAuth.desiredOperation == "SET" && !exactPermissionChecker()) {
                    Log.w(TAG, "Exact alarm capability unavailable for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.PermissionBlocked(currentAuth.scheduleRevision)
                }

                // Apply OS action via injected MainSlotApplier
                val osSuccess = try {
                    mainSlotApplier.applySlotOperation(currentAuth)
                } catch (e: CancellationException) {
                    Log.w(TAG, "OS operation cancelled for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "OS operation threw exception for alarm $alarmId at rev ${currentAuth.scheduleRevision}", e)
                    return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(currentAuth.scheduleRevision, e)
                }

                if (!osSuccess) {
                    Log.w(TAG, "OS operation returned false for alarm $alarmId at rev ${currentAuth.scheduleRevision}")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(currentAuth.scheduleRevision, IllegalStateException("OS action returned false"))
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
                    return@withOwnerRecoveryAndSlotLock OwnerResult.OsAccepted(currentAuth.scheduleRevision, currentAuth.actionKey)
                }

                // Settlement failed due to concurrent claim; re-read and loop to converge!
                val postSettleAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                if (postSettleAuth != null && postSettleAuth.scheduleRevision > currentAuth.scheduleRevision) {
                    Log.w(TAG, "Settlement superseded for alarm $alarmId; re-converging to rev ${postSettleAuth.scheduleRevision}")
                    continue
                } else {
                    return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(currentAuth.scheduleRevision)
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
        return withOwnerRecoveryAndSlotLock(
            alarmId = alarmId,
            onL1Blocked = {
                OwnerResult.OutcomeUnknown(
                    0L,
                    IllegalStateException("RESTORE_PREPARED_FENCE")
                )
            }
        ) {
            val authority = authorityDao.getAuthorityByAlarmId(alarmId)
                ?: return@withOwnerRecoveryAndSlotLock OwnerResult.AuthorityNotFound

            if (authority.reconcileState == "QUARANTINE") {
                Log.w(TAG, "Boot replay skipped for alarm $alarmId: authority is QUARANTINED")
                return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(authority.scheduleRevision)
            }

            val meta = authorityDao.getMetaById(1)
            if (meta == null || meta.restoreEpoch <= 0L || authority.restoreEpoch <= 0L || meta.restoreEpoch != authority.restoreEpoch) {
                Log.w(TAG, "Boot replay skipped for alarm $alarmId: restoreEpoch ${authority.restoreEpoch} failed strict Meta epoch fence (meta=$meta)")
                return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(authority.scheduleRevision)
            }

            if (authority.lifecycle != "LIVE") {
                Log.i(TAG, "Boot replay skipped for alarm $alarmId: lifecycle is ${authority.lifecycle}")
                return@withOwnerRecoveryAndSlotLock OwnerResult.DuplicateIgnored
            }

            if (authority.desiredOperation !in SUPPORTED_OPERATIONS) {
                Log.e(TAG, "Unsupported desiredOperation '${authority.desiredOperation}' for boot replay on alarm $alarmId")
                return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(
                    authority.scheduleRevision,
                    IllegalArgumentException("Unsupported desiredOperation: '${authority.desiredOperation}'")
                )
            }

            // Check exact alarm permission for SET
            if (authority.desiredOperation == "SET" && !exactPermissionChecker()) {
                Log.w(TAG, "Exact alarm capability unavailable during boot replay for alarm $alarmId")
                return@withOwnerRecoveryAndSlotLock OwnerResult.PermissionBlocked(authority.scheduleRevision)
            }

            // Apply OS action via injected MainSlotApplier
            val osSuccess = try {
                mainSlotApplier.applySlotOperation(authority)
            } catch (e: CancellationException) {
                Log.w(TAG, "Boot OS replay cancelled for alarm $alarmId at rev ${authority.scheduleRevision}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Boot OS replay threw exception for alarm $alarmId at rev ${authority.scheduleRevision}", e)
                return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(authority.scheduleRevision, e)
            }

            if (!osSuccess) {
                Log.w(TAG, "Boot OS replay returned false for alarm $alarmId at rev ${authority.scheduleRevision}")
                return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(authority.scheduleRevision, IllegalStateException("Boot OS action returned false"))
            }

            // Post-OS re-read: verify no newer revision or changed incarnation arrived during OS apply
            val latestAuth = authorityDao.getAuthorityByAlarmId(alarmId)
            if (latestAuth == null || latestAuth.scheduleRevision != authority.scheduleRevision ||
                latestAuth.instanceToken != authority.instanceToken || latestAuth.restoreEpoch != authority.restoreEpoch) {
                Log.w(TAG, "Boot OS replay detected changed revision/incarnation for alarm $alarmId; superseding")
                return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(latestAuth?.scheduleRevision ?: authority.scheduleRevision)
            }

            // Ensure reconcileState is SETTLED in DB (retaining original scheduleRevision and actionKey 100% UNCHANGED!)
            if (latestAuth.reconcileState != "SETTLED") {
                val settledCount = authorityDao.settleAuthority(
                    alarmId = alarmId,
                    instanceToken = latestAuth.instanceToken,
                    restoreEpoch = latestAuth.restoreEpoch,
                    claimedRevision = latestAuth.scheduleRevision,
                    expectedActionKey = latestAuth.actionKey
                )
                if (settledCount != 1) {
                    val freshAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                    Log.w(TAG, "Boot OS replay settleAuthority returned 0 for alarm $alarmId; superseding")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(freshAuth?.scheduleRevision ?: latestAuth.scheduleRevision)
                }
            }

            OwnerResult.OsAccepted(latestAuth.scheduleRevision, latestAuth.actionKey)
        }
    }

    /**
     * Dedicated reconciliation replay contract for currently pending 'RECONCILING' authorities.
     * Re-applies OS slot operation using current ScheduleAuthority state WITHOUT creating new operation.
     */
    suspend fun replayPersistedOperation(alarmId: Long): OwnerResult {
        return withOwnerRecoveryAndSlotLock(
            alarmId = alarmId,
            onL1Blocked = {
                OwnerResult.OutcomeUnknown(
                    0L,
                    IllegalStateException("RESTORE_PREPARED_FENCE")
                )
            }
        ) {
            val authority = authorityDao.getAuthorityByAlarmId(alarmId)
                ?: return@withOwnerRecoveryAndSlotLock OwnerResult.AuthorityNotFound

            if (authority.reconcileState == "QUARANTINE") {
                Log.w(TAG, "Reconciler replay skipped for alarm $alarmId: authority is QUARANTINED")
                return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(authority.scheduleRevision)
            }

            val meta = authorityDao.getMetaById(1)
            if (meta == null || meta.restoreEpoch <= 0L || authority.restoreEpoch <= 0L || meta.restoreEpoch != authority.restoreEpoch) {
                Log.w(TAG, "Reconciler replay skipped for alarm $alarmId: restoreEpoch ${authority.restoreEpoch} failed strict Meta epoch fence (meta=$meta)")
                return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(authority.scheduleRevision)
            }

            if (authority.reconcileState != "RECONCILING") {
                Log.i(TAG, "Reconciler replay skipped for alarm $alarmId: state is ${authority.reconcileState}")
                return@withOwnerRecoveryAndSlotLock OwnerResult.DuplicateIgnored
            }

            if (authority.desiredOperation !in SUPPORTED_OPERATIONS) {
                Log.e(TAG, "Unsupported desiredOperation '${authority.desiredOperation}' for reconciler replay on alarm $alarmId")
                return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(
                    authority.scheduleRevision,
                    IllegalArgumentException("Unsupported desiredOperation: '${authority.desiredOperation}'")
                )
            }

            if (authority.desiredOperation == "SET" && !exactPermissionChecker()) {
                Log.w(TAG, "Exact alarm capability unavailable for alarm $alarmId at rev ${authority.scheduleRevision}")
                return@withOwnerRecoveryAndSlotLock OwnerResult.PermissionBlocked(authority.scheduleRevision)
            }

            val osSuccess = try {
                mainSlotApplier.applySlotOperation(authority)
            } catch (e: CancellationException) {
                Log.w(TAG, "OS operation cancelled for alarm $alarmId at rev ${authority.scheduleRevision}")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OS mainSlotApplier threw exception for alarm $alarmId at rev ${authority.scheduleRevision}", e)
                return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(authority.scheduleRevision, e)
            }

            if (!osSuccess) {
                Log.w(TAG, "Reconciler replay OS apply failed for alarm $alarmId at rev ${authority.scheduleRevision}")
                return@withOwnerRecoveryAndSlotLock OwnerResult.OutcomeUnknown(authority.scheduleRevision, IllegalStateException("Reconciler replay OS mainSlotApplier returned false"))
            }

            val latestAuth = authorityDao.getAuthorityByAlarmId(alarmId)
            if (latestAuth == null || latestAuth.scheduleRevision != authority.scheduleRevision ||
                latestAuth.instanceToken != authority.instanceToken || latestAuth.restoreEpoch != authority.restoreEpoch) {
                Log.w(TAG, "Reconciler replay detected changed revision/incarnation for alarm $alarmId; superseding")
                return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(latestAuth?.scheduleRevision ?: authority.scheduleRevision)
            }

            if (latestAuth.reconcileState != "SETTLED") {
                val settledCount = authorityDao.settleAuthority(
                    alarmId = alarmId,
                    instanceToken = latestAuth.instanceToken,
                    restoreEpoch = latestAuth.restoreEpoch,
                    claimedRevision = latestAuth.scheduleRevision,
                    expectedActionKey = latestAuth.actionKey
                )
                if (settledCount != 1) {
                    val freshAuth = authorityDao.getAuthorityByAlarmId(alarmId)
                    Log.w(TAG, "Reconciler replay settleAuthority returned 0 for alarm $alarmId; superseding")
                    return@withOwnerRecoveryAndSlotLock OwnerResult.Superseded(freshAuth?.scheduleRevision ?: latestAuth.scheduleRevision)
                }
            }

            Log.i(TAG, "Reconciler replay settled authority for alarm $alarmId at rev ${authority.scheduleRevision}")
            OwnerResult.OsAccepted(authority.scheduleRevision, authority.actionKey)
        }
    }
}
