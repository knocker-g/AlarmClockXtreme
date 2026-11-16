package com.sysadmindoc.alarmclock.directboot.d0contract

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Isolated D0-P0 contract-only model for SharedOutputAuthorityGate, ClaimNonce handoff,
 * WakeConfirm lineage verification, OutputResourceArbiter, and Room38/V2 cross-lane checks.
 * Strictly located under app/src/test (unreachable from production runtime).
 */
enum class OutputSinkType {
    ALARM_SERVICE_1001,
    DIRECT_BOOT_SERVICE_1011,
    FULLSCREEN_FALLBACK_1001
}

data class CrossLaneSnapshot(
    val v2LedgerKeys: Set<Pair<Long, Long>> = emptySet(),
    val v2DurableOwnerKeys: Set<Pair<Long, Long>> = emptySet(),
    val v2MissedKeys: Set<Pair<Long, Long>> = emptySet(),
    val ceTakeoverKeys: Set<Pair<Long, Long>> = emptySet(),
    val ceLocked: Boolean = false,
    val ceCorrupt: Boolean = false,
    val deV2Corrupt: Boolean = false,
    val deV2UnsupportedSchema: Boolean = false,
    val room38ReceiptKeys: Set<Pair<Long, Long>> = emptySet(),
    val roomPositiveDeliveryKeys: Set<Pair<Long, Long>> = emptySet(),
    val roomDismissedKeys: Set<Pair<Long, Long>> = emptySet(),
    val alarmEnabledById: Map<Long, Boolean> = emptyMap(),
    val alarmRecurringById: Map<Long, Boolean> = emptyMap()
)

sealed class OutputAuthorizationResult {
    data class Authorized(val activeNonce: String, val key: OutputOccurrenceKey) : OutputAuthorizationResult()
    data class DuplicateRejected(val reason: String) : OutputAuthorizationResult()
    data class StaleOrConflictRejected(val reason: String) : OutputAuthorizationResult()
    data class V2LaneOwnedRejected(val reason: String) : OutputAuthorizationResult()
    data class WakeConfirmUnauthorized(val reason: String) : OutputAuthorizationResult()
    data class StorageUnverifiableFailClosed(val reason: String) : OutputAuthorizationResult()
    data class CapacityExhaustedFailClosed(val currentCount: Int, val maxCapacity: Int) : OutputAuthorizationResult()
    data class UnknownStateBlocked(val reason: String) : OutputAuthorizationResult()
    data class PersistenceFailed(val reason: String) : OutputAuthorizationResult()
}

data class ActiveOutputHandle(
    val key: OutputOccurrenceKey,
    val sink: OutputSinkType,
    val activeNonce: String,
    val audioPlaying: Boolean,
    val vibrating: Boolean
)

class IsolatedOutputResourceArbiter {
    private val arbiterLock = ReentrantLock()
    var activeHandle: ActiveOutputHandle? = null
        private set
    val preemptedHandles = mutableListOf<ActiveOutputHandle>()

    fun clearForTesting() = arbiterLock.withLock {
        activeHandle = null
        preemptedHandles.clear()
    }

    internal fun acquirePending(
        key: OutputOccurrenceKey,
        sink: OutputSinkType,
        activeNonce: String
    ): Pair<Boolean, ActiveOutputHandle?> = arbiterLock.withLock {
        val current = activeHandle
        if (current != null) {
            if (current.key == key) {
                return@withLock false to null
            }
            preemptedHandles.add(current.copy(audioPlaying = false, vibrating = false))
            activeHandle = null
        }
        activeHandle = ActiveOutputHandle(
            key = key,
            sink = sink,
            activeNonce = activeNonce,
            audioPlaying = false,
            vibrating = false
        )
        true to current
    }

    internal fun markObserved(key: OutputOccurrenceKey, activeNonce: String): Boolean = arbiterLock.withLock {
        val current = activeHandle ?: return@withLock false
        if (current.key != key || current.activeNonce != activeNonce) return@withLock false
        activeHandle = current.copy(audioPlaying = true, vibrating = true)
        true
    }

    internal fun releaseIfMatches(key: OutputOccurrenceKey, activeNonce: String): Boolean = arbiterLock.withLock {
        val current = activeHandle ?: return@withLock true
        if (current.key == key) {
            if (current.activeNonce != activeNonce) {
                return@withLock false
            }
            activeHandle = null
        }
        true
    }
}

class IsolatedSharedOutputAuthorityGate(
    val claimStore: InMemoryLegacyClaimStore,
    val arbiter: IsolatedOutputResourceArbiter,
    var currentProcessSessionToken: String = "proc-1",
    var currentBootCount: Long = 10L
) {
    companion object {
        const val MAX_WAKE_CONFIRM_REFIRES = 3
    }

    var laneSnapshot: CrossLaneSnapshot = CrossLaneSnapshot()
    private var nonceCounter = 0

    @Synchronized
    fun nextNonce(prefix: String = "nonce"): String {
        nonceCounter++
        return "$prefix-$nonceCounter"
    }

    fun reserveForServiceStart(
        key: OutputOccurrenceKey,
        source: ClaimSource,
        directBootModeOnly: Boolean = false
    ): OutputAuthorizationResult = claimStore.withLock {
        checkStorageAndCrossLane(key, directBootModeOnly)?.let { return@withLock it }

        val existingRecords = when (val state = claimStore.inspect()) {
            is LegacyClaimStorageState.Absent,
            is LegacyClaimStorageState.ValidEmpty -> emptyList()
            is LegacyClaimStorageState.ValidWithRecords -> state.records
            is LegacyClaimStorageState.Corrupt ->
                return@withLock OutputAuthorizationResult.StorageUnverifiableFailClosed(state.reason)
            is LegacyClaimStorageState.UnsupportedSchema ->
                return@withLock OutputAuthorizationResult.StorageUnverifiableFailClosed("Unsupported schema ${state.foundVersion}")
        }

        existingRecords.firstOrNull { it.key == key }?.let { existing ->
            return@withLock if (existing.state == LegacyClaimState.UNKNOWN) {
                OutputAuthorizationResult.UnknownStateBlocked("Occurrence $key is UNKNOWN")
            } else {
                OutputAuthorizationResult.DuplicateRejected("Occurrence $key already in state ${existing.state}")
            }
        }

        if (arbiter.activeHandle?.key == key) {
            return@withLock OutputAuthorizationResult.DuplicateRejected("Occurrence $key already active in arbiter")
        }

        if (existingRecords.size >= claimStore.maxCapacity) {
            return@withLock OutputAuthorizationResult.CapacityExhaustedFailClosed(existingRecords.size, claimStore.maxCapacity)
        }

        val handoverNonce = nextNonce("handover")
        val newRecord = LegacyClaimRecord(
            alarmId = key.alarmId,
            scheduledAt = key.scheduledAt,
            refireCount = key.refireCount,
            source = source,
            claimNonce = handoverNonce,
            processSessionToken = currentProcessSessionToken,
            bootCount = currentBootCount,
            state = LegacyClaimState.START_REQUESTED
        )

        return@withLock when (val writeRes = claimStore.writeInitialClaim(newRecord)) {
            is ClaimStoreWriteResult.Committed -> OutputAuthorizationResult.Authorized(handoverNonce, key)
            is ClaimStoreWriteResult.RejectedCapacityExhausted ->
                OutputAuthorizationResult.CapacityExhaustedFailClosed(writeRes.currentSize, writeRes.maxCapacity)
            is ClaimStoreWriteResult.RejectedDuplicate ->
                OutputAuthorizationResult.DuplicateRejected(writeRes.reason)
            is ClaimStoreWriteResult.RejectedStorageCorrupt ->
                OutputAuthorizationResult.StorageUnverifiableFailClosed(writeRes.reason)
            is ClaimStoreWriteResult.RejectedUnsupportedSchema ->
                OutputAuthorizationResult.StorageUnverifiableFailClosed("Unsupported schema ${writeRes.foundVersion}")
            is ClaimStoreWriteResult.RejectedPersistenceFailed ->
                if (writeRes.isReadbackMismatch) {
                    OutputAuthorizationResult.StorageUnverifiableFailClosed(writeRes.reason)
                } else {
                    OutputAuthorizationResult.PersistenceFailed(writeRes.reason)
                }
            is ClaimStoreWriteResult.RejectedInvalidTransition ->
                OutputAuthorizationResult.StaleOrConflictRejected(writeRes.reason)
        }
    }

    fun claimOutput(
        key: OutputOccurrenceKey,
        incomingNonce: String,
        sink: OutputSinkType,
        directBootModeOnly: Boolean = false,
        beforePhase2RoomRead: (() -> Unit)? = null
    ): OutputAuthorizationResult {
        // PHASE 1: L1 -> L2 Prefs only (ZERO Room I/O)
        val phase1Res = phase1ClaimOutput(key, incomingNonce, directBootModeOnly)
        val activeNonce = when (phase1Res) {
            is OutputAuthorizationResult.Authorized -> phase1Res.activeNonce
            else -> return phase1Res
        }

        // L1 -> L2 LOCKS ARE NOW RELEASED BEFORE PHASE 2 ROOM READS (Zero Deadlock Invariant)
        beforePhase2RoomRead?.invoke()

        // PHASE 2: L0 Room Verification (skipped only for explicit Direct Boot mode where CE is locked)
        if (!directBootModeOnly) {
            val pair = key.alarmId to key.scheduledAt
            if (pair in laneSnapshot.v2LedgerKeys ||
                pair in laneSnapshot.v2DurableOwnerKeys ||
                pair in laneSnapshot.ceTakeoverKeys
            ) {
                abortClaiming(key, activeNonce, TerminalOutcome.DUPLICATE_ABORTED)
                return OutputAuthorizationResult.V2LaneOwnedRejected("V2 lane claimed ownership between Phase 1 and Phase 2")
            }

            if ((key.refireCount == 0 && pair in laneSnapshot.room38ReceiptKeys) ||
                pair in laneSnapshot.roomPositiveDeliveryKeys
            ) {
                abortClaiming(key, activeNonce, TerminalOutcome.DUPLICATE_ABORTED)
                return OutputAuthorizationResult.DuplicateRejected("Room38 or positive delivery receipt already committed")
            }

            val enabled = laneSnapshot.alarmEnabledById[key.alarmId]
            val recurring = laneSnapshot.alarmRecurringById[key.alarmId] ?: false
            val allowedByAlarmState = if (key.refireCount == 0) {
                enabled == true
            } else {
                // Disabled ONE_TIME alarm remains eligible for authorized WakeConfirm refire 1..3
                enabled == true || (enabled == false && !recurring)
            }
            if (!allowedByAlarmState) {
                abortClaiming(key, activeNonce, TerminalOutcome.REJECTED_NO_OUTPUT)
                return OutputAuthorizationResult.StaleOrConflictRejected("Alarm ${key.alarmId} disabled or missing in Room")
            }
        }

        // PHASE 3: Arbiter single-owner acquisition / cross-occurrence preemption
        val (acquired, preempted) = arbiter.acquirePending(key, sink, activeNonce)
        if (!acquired) {
            abortClaiming(key, activeNonce, TerminalOutcome.DUPLICATE_ABORTED)
            return OutputAuthorizationResult.DuplicateRejected("Occurrence $key already active in OutputResourceArbiter")
        }

        if (preempted != null) {
            claimStore.withLock {
                val prevRec = findRecord(preempted.key)
                if (prevRec != null && prevRec.state in setOf(
                        LegacyClaimState.OUTPUT_CLAIMING,
                        LegacyClaimState.OUTPUT_OBSERVED
                    )
                ) {
                    claimStore.transition(
                        key = preempted.key,
                        expectedFrom = prevRec.state,
                        toState = LegacyClaimState.TERMINAL,
                        expectedNonce = preempted.activeNonce,
                        newNonce = nextNonce("preempt"),
                        processSessionToken = currentProcessSessionToken,
                        bootCount = currentBootCount,
                        terminalOutcome = TerminalOutcome.CONSUMED_PREEMPTED
                    )
                }
            }
        }

        return OutputAuthorizationResult.Authorized(activeNonce, key)
    }

    fun claimOutputForFallback(
        key: OutputOccurrenceKey,
        expectedNonce: String
    ): OutputAuthorizationResult = claimOutput(
        key = key,
        incomingNonce = expectedNonce,
        sink = OutputSinkType.FULLSCREEN_FALLBACK_1001,
        directBootModeOnly = false
    )

    private fun phase1ClaimOutput(
        key: OutputOccurrenceKey,
        incomingNonce: String,
        directBootModeOnly: Boolean
    ): OutputAuthorizationResult = claimStore.withLock {
        if (incomingNonce.isBlank()) {
            return@withLock OutputAuthorizationResult.StaleOrConflictRejected("Blank claimNonce")
        }
        checkStorageHealth(directBootModeOnly)?.let { return@withLock it }

        val existing = findRecord(key)
            ?: return@withLock OutputAuthorizationResult.StaleOrConflictRejected("No claim record for $key")

        if (existing.state == LegacyClaimState.UNKNOWN) {
            return@withLock OutputAuthorizationResult.UnknownStateBlocked("Occurrence $key is UNKNOWN")
        }

        if (existing.processSessionToken != currentProcessSessionToken || existing.bootCount != currentBootCount) {
            if (existing.state != LegacyClaimState.TERMINAL) {
                claimStore.transition(
                    key = key,
                    expectedFrom = existing.state,
                    toState = LegacyClaimState.UNKNOWN,
                    expectedNonce = existing.claimNonce,
                    newNonce = nextNonce("unknown"),
                    processSessionToken = currentProcessSessionToken,
                    bootCount = currentBootCount
                )
            }
            return@withLock OutputAuthorizationResult.UnknownStateBlocked("Process/boot provenance mismatch")
        }

        checkV2CrossLane(key)?.let { v2Reject ->
            if (existing.state == LegacyClaimState.START_REQUESTED) {
                claimStore.transition(
                    key = key,
                    expectedFrom = LegacyClaimState.START_REQUESTED,
                    toState = LegacyClaimState.TERMINAL,
                    expectedNonce = existing.claimNonce,
                    newNonce = nextNonce("term-v2"),
                    processSessionToken = currentProcessSessionToken,
                    bootCount = currentBootCount,
                    terminalOutcome = TerminalOutcome.DUPLICATE_ABORTED
                )
            }
            return@withLock v2Reject
        }

        if (existing.state != LegacyClaimState.START_REQUESTED) {
            return@withLock if (existing.state in setOf(
                    LegacyClaimState.OUTPUT_CLAIMING,
                    LegacyClaimState.OUTPUT_OBSERVED
                )
            ) {
                OutputAuthorizationResult.DuplicateRejected("Claim already at ${existing.state}")
            } else {
                OutputAuthorizationResult.StaleOrConflictRejected("Claim in non-startable state ${existing.state}")
            }
        }

        if (existing.claimNonce != incomingNonce) {
            return@withLock OutputAuthorizationResult.StaleOrConflictRejected("ClaimNonce mismatch")
        }

        val rotated = nextNonce("active")
        val tr = claimStore.transition(
            key = key,
            expectedFrom = LegacyClaimState.START_REQUESTED,
            toState = LegacyClaimState.OUTPUT_CLAIMING,
            expectedNonce = incomingNonce,
            newNonce = rotated,
            processSessionToken = currentProcessSessionToken,
            bootCount = currentBootCount
        )
        mapWriteFailure(tr)
    }

    fun confirmOutputObserved(
        key: OutputOccurrenceKey,
        activeNonce: String,
        sink: OutputSinkType
    ): OutputAuthorizationResult = claimStore.withLock {
        val existing = findRecord(key)
            ?: return@withLock OutputAuthorizationResult.StaleOrConflictRejected("No claim for $key")
        if (existing.state == LegacyClaimState.UNKNOWN) {
            return@withLock OutputAuthorizationResult.UnknownStateBlocked("Occurrence $key is UNKNOWN")
        }
        if (existing.state != LegacyClaimState.OUTPUT_CLAIMING || existing.claimNonce != activeNonce) {
            return@withLock OutputAuthorizationResult.StaleOrConflictRejected("Invalid state/nonce for confirmOutputObserved")
        }
        if (existing.processSessionToken != currentProcessSessionToken || existing.bootCount != currentBootCount) {
            return@withLock OutputAuthorizationResult.UnknownStateBlocked("Process/boot provenance mismatch")
        }

        val tr = claimStore.transition(
            key = key,
            expectedFrom = LegacyClaimState.OUTPUT_CLAIMING,
            toState = LegacyClaimState.OUTPUT_OBSERVED,
            expectedNonce = activeNonce,
            newNonce = activeNonce, // Preserve session activeNonce for terminal dismiss/snooze/autostop fencing
            processSessionToken = currentProcessSessionToken,
            bootCount = currentBootCount
        )
        return@withLock when (tr) {
            is ClaimStoreWriteResult.Committed -> {
                arbiter.markObserved(key, activeNonce)
                OutputAuthorizationResult.Authorized(activeNonce, key)
            }
            is ClaimStoreWriteResult.RejectedPersistenceFailed ->
                if (tr.isReadbackMismatch) {
                    OutputAuthorizationResult.StorageUnverifiableFailClosed(tr.reason)
                } else {
                    OutputAuthorizationResult.PersistenceFailed(tr.reason)
                }
            is ClaimStoreWriteResult.RejectedStorageCorrupt ->
                OutputAuthorizationResult.StorageUnverifiableFailClosed(tr.reason)
            is ClaimStoreWriteResult.RejectedUnsupportedSchema ->
                OutputAuthorizationResult.StorageUnverifiableFailClosed("Unsupported schema")
            else -> OutputAuthorizationResult.StaleOrConflictRejected("Cannot confirm observed: $tr")
        }
    }

    fun observePhysicalOutput(
        key: OutputOccurrenceKey,
        activeNonce: String,
        sink: OutputSinkType,
        beforePhysicalOutput: (() -> Unit)? = null
    ): OutputAuthorizationResult {
        beforePhysicalOutput?.invoke()

        val pair = key.alarmId to key.scheduledAt
        if (pair in laneSnapshot.v2LedgerKeys ||
            pair in laneSnapshot.v2DurableOwnerKeys ||
            pair in laneSnapshot.ceTakeoverKeys
        ) {
            arbiter.releaseIfMatches(key, activeNonce)
            abortClaiming(key, activeNonce, TerminalOutcome.DUPLICATE_ABORTED)
            return OutputAuthorizationResult.V2LaneOwnedRejected("V2 lane claimed ownership immediately prior to physical output")
        }
        if (pair in laneSnapshot.room38ReceiptKeys || pair in laneSnapshot.roomPositiveDeliveryKeys) {
            arbiter.releaseIfMatches(key, activeNonce)
            abortClaiming(key, activeNonce, TerminalOutcome.DUPLICATE_ABORTED)
            return OutputAuthorizationResult.DuplicateRejected("Room38 receipt committed immediately prior to physical output")
        }

        return confirmOutputObserved(key, activeNonce, sink)
    }

    fun completeTerminal(
        key: OutputOccurrenceKey,
        activeNonce: String,
        outcome: TerminalOutcome,
        enableWakeConfirm: Boolean = false
    ): OutputAuthorizationResult = claimStore.withLock {
        val existing = findRecord(key)
            ?: return@withLock OutputAuthorizationResult.StaleOrConflictRejected("No claim for $key")
        if (existing.state == LegacyClaimState.TERMINAL) {
            return@withLock OutputAuthorizationResult.StaleOrConflictRejected("Claim already TERMINAL")
        }
        if (existing.state == LegacyClaimState.UNKNOWN) {
            return@withLock OutputAuthorizationResult.UnknownStateBlocked("Cannot complete UNKNOWN claim without CE reconciliation")
        }
        if (existing.claimNonce != activeNonce) {
            return@withLock OutputAuthorizationResult.StaleOrConflictRejected("Active claimNonce mismatch for $key")
        }
        if (!arbiter.releaseIfMatches(key, activeNonce)) {
            return@withLock OutputAuthorizationResult.StaleOrConflictRejected("Arbiter handle belongs to newer nonce")
        }

        val primaryKey = key.copy(refireCount = 0)
        val shouldAuthorizeWakeConfirm = outcome == TerminalOutcome.CONSUMED_DISMISSED &&
            enableWakeConfirm &&
            key.refireCount < MAX_WAKE_CONFIRM_REFIRES

        val stepLineage = if (shouldAuthorizeWakeConfirm) {
            WakeConfirmLineage(
                parentDismissed = true,
                wakeConfirmEnabled = true,
                nextAuthorizedRefire = key.refireCount + 1,
                highestClaimedRefire = key.refireCount,
                confirmed = false,
                stepToken = nextNonce("wc-step"),
                oneShotDisabledAllowed = true
            )
        } else if (existing.wakeConfirm != null) {
            existing.wakeConfirm.copy(parentDismissed = false, wakeConfirmEnabled = false, stepToken = "")
        } else {
            null
        }

        val tr = claimStore.transition(
            key = key,
            expectedFrom = existing.state,
            toState = LegacyClaimState.TERMINAL,
            expectedNonce = activeNonce,
            newNonce = nextNonce("terminal"),
            processSessionToken = currentProcessSessionToken,
            bootCount = currentBootCount,
            terminalOutcome = outcome,
            wakeConfirm = stepLineage
        )
        if (tr !is ClaimStoreWriteResult.Committed) {
            return@withLock mapWriteFailure(tr)
        }

        // Keep primary occurrence's WakeConfirmLineage synchronized when a refire step 1..2 is dismissed
        if (key.refireCount > 0 && stepLineage != null) {
            val primaryRec = findRecord(primaryKey)
            if (primaryRec != null) {
                val upd = claimStore.updateWakeConfirmLineage(primaryKey, stepLineage)
                if (upd !is ClaimStoreWriteResult.Committed) {
                    return@withLock mapWriteFailure(upd)
                }
            }
        }

        OutputAuthorizationResult.Authorized(tr.record.claimNonce, key)
    }

    fun confirmWakeAwake(alarmId: Long, scheduledAt: Long): Boolean = claimStore.withLock {
        val primaryKey = OutputOccurrenceKey(alarmId, scheduledAt, 0)
        val primaryRec = findRecord(primaryKey) ?: return@withLock false
        val wc = primaryRec.wakeConfirm ?: return@withLock false
        val res = claimStore.updateWakeConfirmLineage(
            primaryKey,
            wc.copy(confirmed = true, stepToken = "")
        )
        res is ClaimStoreWriteResult.Committed
    }

    fun reserveWakeConfirmRefire(
        alarmId: Long,
        scheduledAt: Long,
        requestedRefireCount: Int,
        stepToken: String
    ): OutputAuthorizationResult = claimStore.withLock {
        checkStorageHealth(directBootModeOnly = false)?.let { return@withLock it }

        if (requestedRefireCount !in 1..MAX_WAKE_CONFIRM_REFIRES || stepToken.isBlank()) {
            return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("Invalid refireCount=$requestedRefireCount or blank stepToken")
        }

        val primaryKey = OutputOccurrenceKey(alarmId, scheduledAt, 0)
        val primaryRec = findRecord(primaryKey)
            ?: return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("Primary occurrence $primaryKey not found")
        if (primaryRec.state != LegacyClaimState.TERMINAL || primaryRec.terminalOutcome != TerminalOutcome.CONSUMED_DISMISSED) {
            return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("Primary occurrence was not dismissed")
        }

        val wc = primaryRec.wakeConfirm
            ?: return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("WakeConfirmLineage absent")
        if (!wc.parentDismissed || !wc.wakeConfirmEnabled || wc.confirmed) {
            return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("WakeConfirm disabled, un-dismissed, or already confirmed")
        }
        if (requestedRefireCount != wc.nextAuthorizedRefire || requestedRefireCount <= wc.highestClaimedRefire) {
            return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized(
                "Refire sequence mismatch: requested=$requestedRefireCount, next=${wc.nextAuthorizedRefire}, highest=${wc.highestClaimedRefire}"
            )
        }
        if (wc.stepToken != stepToken) {
            return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("Single-use stepToken mismatch or already consumed")
        }

        // Verify disabled ONE_TIME vs disabled recurring alarm policy
        val enabled = laneSnapshot.alarmEnabledById[alarmId]
        val recurring = laneSnapshot.alarmRecurringById[alarmId] ?: false
        if (enabled == false && recurring) {
            return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("Recurring alarm is manually disabled")
        }

        // Check prior refire step (if k > 1) is not UNKNOWN
        if (requestedRefireCount > 1) {
            val prevRefire = findRecord(OutputOccurrenceKey(alarmId, scheduledAt, requestedRefireCount - 1))
            if (prevRefire == null || prevRefire.state == LegacyClaimState.UNKNOWN) {
                return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("Prior refire step unresolved/UNKNOWN")
            }
        }

        val refireKey = OutputOccurrenceKey(alarmId, scheduledAt, requestedRefireCount)
        checkV2CrossLane(refireKey)?.let { return@withLock it }

        val existingRecords = when (val st = claimStore.inspect()) {
            is LegacyClaimStorageState.ValidWithRecords -> st.records
            else -> emptyList()
        }
        if (existingRecords.any { it.key == refireKey }) {
            return@withLock OutputAuthorizationResult.WakeConfirmUnauthorized("Refire $refireKey already claimed")
        }
        if (existingRecords.size >= claimStore.maxCapacity) {
            return@withLock OutputAuthorizationResult.CapacityExhaustedFailClosed(existingRecords.size, claimStore.maxCapacity)
        }

        // Consume stepToken and advance highestClaimedRefire monotonically before writing refire reservation
        val consumedLineage = wc.copy(
            highestClaimedRefire = requestedRefireCount,
            nextAuthorizedRefire = requestedRefireCount + 1,
            stepToken = ""
        )
        val upd = claimStore.updateWakeConfirmLineage(primaryKey, consumedLineage)
        if (upd !is ClaimStoreWriteResult.Committed) {
            return@withLock mapWriteFailure(upd)
        }

        val handoverNonce = nextNonce("wc-handover")
        val refireRec = LegacyClaimRecord(
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            refireCount = requestedRefireCount,
            source = ClaimSource.WAKE_CONFIRM,
            claimNonce = handoverNonce,
            processSessionToken = currentProcessSessionToken,
            bootCount = currentBootCount,
            state = LegacyClaimState.START_REQUESTED
        )
        return@withLock when (val writeRes = claimStore.writeInitialClaim(refireRec)) {
            is ClaimStoreWriteResult.Committed -> OutputAuthorizationResult.Authorized(handoverNonce, refireKey)
            else -> mapWriteFailure(writeRes)
        }
    }

    fun simulateProcessRestartOrReboot(
        newProcessSessionToken: String,
        newBootCount: Long
    ) = claimStore.withLock {
        currentProcessSessionToken = newProcessSessionToken
        currentBootCount = newBootCount
        arbiter.clearForTesting()

        val state = claimStore.inspect() as? LegacyClaimStorageState.ValidWithRecords ?: return@withLock
        for (rec in state.records) {
            if (rec.state != LegacyClaimState.TERMINAL &&
                rec.state != LegacyClaimState.UNKNOWN &&
                (rec.processSessionToken != newProcessSessionToken || rec.bootCount != newBootCount)
            ) {
                claimStore.transition(
                    key = rec.key,
                    expectedFrom = rec.state,
                    toState = LegacyClaimState.UNKNOWN,
                    expectedNonce = rec.claimNonce,
                    newNonce = nextNonce("unknown"),
                    processSessionToken = newProcessSessionToken,
                    bootCount = newBootCount
                )
            }
        }
    }

    fun reconcilePostUnlock(): Int = claimStore.withLock {
        if (laneSnapshot.ceLocked || laneSnapshot.ceCorrupt || laneSnapshot.deV2Corrupt || laneSnapshot.deV2UnsupportedSchema) {
            return@withLock 0
        }
        val state = claimStore.inspect() as? LegacyClaimStorageState.ValidWithRecords ?: return@withLock 0
        var reconciledCount = 0
        for (rec in state.records) {
            if (rec.state == LegacyClaimState.UNKNOWN) {
                val pair = rec.alarmId to rec.scheduledAt
                val hasCeProof = pair in laneSnapshot.roomPositiveDeliveryKeys ||
                    pair in laneSnapshot.roomDismissedKeys ||
                    pair in laneSnapshot.room38ReceiptKeys
                if (hasCeProof) {
                    val tr = claimStore.transition(
                        key = rec.key,
                        expectedFrom = LegacyClaimState.UNKNOWN,
                        toState = LegacyClaimState.TERMINAL,
                        expectedNonce = rec.claimNonce,
                        newNonce = nextNonce("reconcile"),
                        processSessionToken = currentProcessSessionToken,
                        bootCount = currentBootCount,
                        terminalOutcome = TerminalOutcome.RECONCILED_CE
                    )
                    if (tr is ClaimStoreWriteResult.Committed) {
                        reconciledCount++
                    }
                }
            }
        }
        reconciledCount
    }

    private fun checkStorageAndCrossLane(
        key: OutputOccurrenceKey,
        directBootModeOnly: Boolean
    ): OutputAuthorizationResult? {
        checkStorageHealth(directBootModeOnly)?.let { return it }
        return checkV2CrossLane(key)
    }

    private fun checkStorageHealth(directBootModeOnly: Boolean): OutputAuthorizationResult? {
        when (val st = claimStore.inspect()) {
            is LegacyClaimStorageState.Corrupt ->
                return OutputAuthorizationResult.StorageUnverifiableFailClosed(st.reason)
            is LegacyClaimStorageState.UnsupportedSchema ->
                return OutputAuthorizationResult.StorageUnverifiableFailClosed("Unsupported schema ${st.foundVersion}")
            else -> Unit
        }
        if (laneSnapshot.deV2Corrupt) {
            return OutputAuthorizationResult.StorageUnverifiableFailClosed("DE V2 store corrupt")
        }
        if (laneSnapshot.deV2UnsupportedSchema) {
            return OutputAuthorizationResult.StorageUnverifiableFailClosed("DE V2 store unsupported schema")
        }
        if (!directBootModeOnly && laneSnapshot.ceCorrupt) {
            return OutputAuthorizationResult.StorageUnverifiableFailClosed("CE takeover store corrupt")
        }
        if (laneSnapshot.ceCorrupt) {
            return OutputAuthorizationResult.StorageUnverifiableFailClosed("CE store corrupt")
        }
        return null
    }

    private fun checkV2CrossLane(key: OutputOccurrenceKey): OutputAuthorizationResult? {
        val pair = key.alarmId to key.scheduledAt
        if (pair in laneSnapshot.v2LedgerKeys) {
            return OutputAuthorizationResult.V2LaneOwnedRejected("Owned by V2 DirectBootDeliveryLedger")
        }
        if (pair in laneSnapshot.v2DurableOwnerKeys) {
            return OutputAuthorizationResult.V2LaneOwnedRejected("Owned by V2 DirectBootDurableOwnerStore")
        }
        if (pair in laneSnapshot.v2MissedKeys) {
            return OutputAuthorizationResult.V2LaneOwnedRejected("Owned by V2 DirectBootMissedStore")
        }
        if (pair in laneSnapshot.ceTakeoverKeys) {
            return OutputAuthorizationResult.V2LaneOwnedRejected("Owned by V2 DirectBootCeTakeoverStore")
        }
        return null
    }

    private fun findRecord(key: OutputOccurrenceKey): LegacyClaimRecord? {
        val state = claimStore.inspect() as? LegacyClaimStorageState.ValidWithRecords ?: return null
        return state.records.firstOrNull { it.key == key }
    }

    private fun abortClaiming(key: OutputOccurrenceKey, activeNonce: String, outcome: TerminalOutcome) {
        claimStore.withLock {
            val rec = findRecord(key) ?: return@withLock
            if (rec.state == LegacyClaimState.OUTPUT_CLAIMING && rec.claimNonce == activeNonce) {
                claimStore.transition(
                    key = key,
                    expectedFrom = LegacyClaimState.OUTPUT_CLAIMING,
                    toState = LegacyClaimState.TERMINAL,
                    expectedNonce = activeNonce,
                    newNonce = nextNonce("abort"),
                    processSessionToken = currentProcessSessionToken,
                    bootCount = currentBootCount,
                    terminalOutcome = outcome
                )
            }
        }
    }

    private fun mapWriteFailure(res: ClaimStoreWriteResult): OutputAuthorizationResult = when (res) {
        is ClaimStoreWriteResult.RejectedCapacityExhausted ->
            OutputAuthorizationResult.CapacityExhaustedFailClosed(res.currentSize, res.maxCapacity)
        is ClaimStoreWriteResult.RejectedDuplicate ->
            OutputAuthorizationResult.DuplicateRejected(res.reason)
        is ClaimStoreWriteResult.RejectedStorageCorrupt ->
            OutputAuthorizationResult.StorageUnverifiableFailClosed(res.reason)
        is ClaimStoreWriteResult.RejectedUnsupportedSchema ->
            OutputAuthorizationResult.StorageUnverifiableFailClosed("Unsupported schema ${res.foundVersion}")
        is ClaimStoreWriteResult.RejectedPersistenceFailed ->
            if (res.isReadbackMismatch) {
                OutputAuthorizationResult.StorageUnverifiableFailClosed(res.reason)
            } else {
                OutputAuthorizationResult.PersistenceFailed(res.reason)
            }
        is ClaimStoreWriteResult.RejectedInvalidTransition ->
            OutputAuthorizationResult.StaleOrConflictRejected(res.reason)
        is ClaimStoreWriteResult.Committed ->
            OutputAuthorizationResult.Authorized(res.record.claimNonce, res.record.key)
    }
}
