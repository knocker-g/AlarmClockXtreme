package com.sysadmindoc.alarmclock.directboot.outputauthority

import android.content.Context
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootLegacyClaimStore
import com.sysadmindoc.alarmclock.directboot.DirectBootStorageLock
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRecord
import com.sysadmindoc.alarmclock.directboot.LegacyClaimRetirementResult
import com.sysadmindoc.alarmclock.directboot.LegacyClaimSource
import com.sysadmindoc.alarmclock.directboot.LegacyClaimState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimStorageState
import com.sysadmindoc.alarmclock.directboot.LegacyClaimWriteResult
import com.sysadmindoc.alarmclock.directboot.LegacyTerminalOutcome
import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import com.sysadmindoc.alarmclock.directboot.WakeConfirmLineage

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
    object GateDisabled : OutputAuthorizationResult()
}

/**
 * Authoritative production foundation coordinating output authority across Legacy and DirectBoot lanes.
 * Initial state is OFF (isEnabled = false).
 */
class SharedOutputAuthorityGate(
    val arbiter: OutputResourceArbiter = OutputResourceArbiter(),
    val verifier: LegacyRetirementProofVerifier = LegacyRetirementProofVerifier,
    var processSessionToken: String = "proc-${android.os.Process.myPid()}",
    var bootCount: Long = 1L
) {
    /**
     * Feature flag controlling activation of Shared Output Authority.
     * Default: false (OFF). When false, the gate is inactive and does not alter legacy paths.
     */
    @Volatile
    var isEnabled: Boolean = false

    private var nonceCounter = 0L

    @Synchronized
    fun nextNonce(prefix: String = "nonce"): String {
        nonceCounter++
        return "$prefix-$nonceCounter-${System.currentTimeMillis()}"
    }

    fun reserveForServiceStart(
        context: Context,
        key: OutputOccurrenceKey,
        source: LegacyClaimSource,
        directBootModeOnly: Boolean = false
    ): OutputAuthorizationResult {
        if (!isEnabled) return OutputAuthorizationResult.GateDisabled

        if (key.alarmId <= 0L || key.scheduledAt <= 0L || key.refireCount !in 0..3) {
            return OutputAuthorizationResult.StaleOrConflictRejected("Invalid occurrence key: $key")
        }

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        // Cross-lane check against V2 before reserving
        checkV2CrossLane(deContext, key)?.let { return it }

        synchronized(DirectBootStorageLock.lock) {
            val existingRecords = when (val state = DirectBootLegacyClaimStore.inspectStorageState(deContext)) {
                is LegacyClaimStorageState.Absent,
                is LegacyClaimStorageState.ValidEmpty -> emptyList()
                is LegacyClaimStorageState.ValidWithRecords -> state.records
                is LegacyClaimStorageState.Corrupt ->
                    return OutputAuthorizationResult.StorageUnverifiableFailClosed(state.reason)
                is LegacyClaimStorageState.UnsupportedSchema ->
                    return OutputAuthorizationResult.StorageUnverifiableFailClosed("Unsupported schema ${state.foundVersion}")
            }

            existingRecords.firstOrNull { it.key == key }?.let { existing ->
                return if (existing.state == LegacyClaimState.UNKNOWN) {
                    OutputAuthorizationResult.UnknownStateBlocked("Occurrence $key is UNKNOWN")
                } else {
                    OutputAuthorizationResult.DuplicateRejected("Occurrence $key already in state ${existing.state}")
                }
            }

            if (arbiter.activeHandle?.key == key) {
                return OutputAuthorizationResult.DuplicateRejected("Occurrence $key already active in arbiter")
            }

            if (existingRecords.size >= DirectBootLegacyClaimStore.MAX_CAPACITY) {
                return OutputAuthorizationResult.CapacityExhaustedFailClosed(
                    existingRecords.size,
                    DirectBootLegacyClaimStore.MAX_CAPACITY
                )
            }

            val handoverNonce = nextNonce("handover")
            val newRecord = LegacyClaimRecord(
                alarmId = key.alarmId,
                scheduledAt = key.scheduledAt,
                refireCount = key.refireCount,
                source = source,
                claimNonce = handoverNonce,
                processSessionToken = processSessionToken,
                bootCount = bootCount,
                state = LegacyClaimState.START_REQUESTED
            )

            return when (val writeRes = DirectBootLegacyClaimStore.writeInitialClaim(deContext, newRecord)) {
                is LegacyClaimWriteResult.Committed -> OutputAuthorizationResult.Authorized(handoverNonce, key)
                is LegacyClaimWriteResult.RejectedCapacityExhausted ->
                    OutputAuthorizationResult.CapacityExhaustedFailClosed(writeRes.currentSize, writeRes.maxCapacity)
                is LegacyClaimWriteResult.RejectedDuplicate ->
                    OutputAuthorizationResult.DuplicateRejected(writeRes.reason)
                is LegacyClaimWriteResult.RejectedStorageCorrupt ->
                    OutputAuthorizationResult.StorageUnverifiableFailClosed(writeRes.reason)
                is LegacyClaimWriteResult.RejectedUnsupportedSchema ->
                    OutputAuthorizationResult.StorageUnverifiableFailClosed("Unsupported schema ${writeRes.foundVersion}")
                is LegacyClaimWriteResult.RejectedPersistenceFailed ->
                    if (writeRes.isReadbackMismatch) {
                        OutputAuthorizationResult.StorageUnverifiableFailClosed(writeRes.reason)
                    } else {
                        OutputAuthorizationResult.PersistenceFailed(writeRes.reason)
                    }
                is LegacyClaimWriteResult.RejectedInvalidTransition ->
                    OutputAuthorizationResult.StaleOrConflictRejected(writeRes.reason)
            }
        }
    }

    fun claimOutput(
        context: Context,
        key: OutputOccurrenceKey,
        incomingNonce: String,
        sink: OutputSinkType,
        directBootModeOnly: Boolean = false,
        roomReceiptChecker: ((Long, Long) -> Boolean)? = null
    ): OutputAuthorizationResult {
        if (!isEnabled) return OutputAuthorizationResult.GateDisabled

        if (incomingNonce.isBlank()) {
            return OutputAuthorizationResult.StaleOrConflictRejected("Blank claimNonce")
        }

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        // PHASE 1: Under DE Lock, validate and transition to OUTPUT_CLAIMING
        val activeNonce: String
        synchronized(DirectBootStorageLock.lock) {
            val state = DirectBootLegacyClaimStore.inspectStorageState(deContext)
            val records = when (state) {
                is LegacyClaimStorageState.ValidWithRecords -> state.records
                is LegacyClaimStorageState.Corrupt -> return OutputAuthorizationResult.StorageUnverifiableFailClosed(state.reason)
                is LegacyClaimStorageState.UnsupportedSchema -> return OutputAuthorizationResult.StorageUnverifiableFailClosed("Unsupported schema")
                else -> emptyList()
            }

            val existing = records.firstOrNull { it.key == key }
                ?: return OutputAuthorizationResult.StaleOrConflictRejected("No claim record for $key")

            if (existing.state == LegacyClaimState.UNKNOWN) {
                return OutputAuthorizationResult.UnknownStateBlocked("Occurrence $key is UNKNOWN")
            }

            // Provenance check
            if (existing.processSessionToken != processSessionToken || existing.bootCount != bootCount) {
                if (existing.state != LegacyClaimState.TERMINAL) {
                    DirectBootLegacyClaimStore.transition(
                        context = deContext,
                        key = key,
                        expectedFrom = existing.state,
                        toState = LegacyClaimState.UNKNOWN,
                        expectedNonce = existing.claimNonce,
                        newNonce = nextNonce("unknown"),
                        processSessionToken = processSessionToken,
                        bootCount = bootCount
                    )
                }
                return OutputAuthorizationResult.UnknownStateBlocked("Process/boot provenance mismatch")
            }

            // Cross-lane check
            checkV2CrossLane(deContext, key)?.let { v2Reject ->
                if (existing.state == LegacyClaimState.START_REQUESTED) {
                    DirectBootLegacyClaimStore.transition(
                        context = deContext,
                        key = key,
                        expectedFrom = LegacyClaimState.START_REQUESTED,
                        toState = LegacyClaimState.TERMINAL,
                        expectedNonce = existing.claimNonce,
                        newNonce = nextNonce("term-v2"),
                        processSessionToken = processSessionToken,
                        bootCount = bootCount,
                        terminalOutcome = LegacyTerminalOutcome.DUPLICATE_ABORTED
                    )
                }
                return v2Reject
            }

            if (existing.state != LegacyClaimState.START_REQUESTED) {
                return if (existing.state in setOf(LegacyClaimState.OUTPUT_CLAIMING, LegacyClaimState.OUTPUT_OBSERVED)) {
                    OutputAuthorizationResult.DuplicateRejected("Claim already at ${existing.state}")
                } else {
                    OutputAuthorizationResult.StaleOrConflictRejected("Claim in non-startable state ${existing.state}")
                }
            }

            if (existing.claimNonce != incomingNonce) {
                return OutputAuthorizationResult.StaleOrConflictRejected("ClaimNonce mismatch")
            }

            val rotated = nextNonce("active")
            val tr = DirectBootLegacyClaimStore.transition(
                context = deContext,
                key = key,
                expectedFrom = LegacyClaimState.START_REQUESTED,
                toState = LegacyClaimState.OUTPUT_CLAIMING,
                expectedNonce = incomingNonce,
                newNonce = rotated,
                processSessionToken = processSessionToken,
                bootCount = bootCount
            )

            when (tr) {
                is LegacyClaimWriteResult.Committed -> activeNonce = rotated
                is LegacyClaimWriteResult.RejectedPersistenceFailed ->
                    return if (tr.isReadbackMismatch) OutputAuthorizationResult.StorageUnverifiableFailClosed(tr.reason)
                    else OutputAuthorizationResult.PersistenceFailed(tr.reason)
                else -> return OutputAuthorizationResult.StaleOrConflictRejected("Transition failed: $tr")
            }
        }

        // DE LOCK IS NOW RELEASED!
        // PHASE 2: Room verification outside DE lock
        if (!directBootModeOnly && roomReceiptChecker != null) {
            val receiptExists = try {
                roomReceiptChecker(key.alarmId, key.scheduledAt)
            } catch (e: Exception) {
                abortClaiming(deContext, key, activeNonce, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                return OutputAuthorizationResult.StorageUnverifiableFailClosed("Room check exception: ${e.message}")
            }

            if (receiptExists) {
                abortClaiming(deContext, key, activeNonce, LegacyTerminalOutcome.DUPLICATE_ABORTED)
                return OutputAuthorizationResult.DuplicateRejected("Room receipt already exists")
            }
        }

        // PHASE 3: Arbiter acquisition
        val (acquired, preempted) = arbiter.acquirePending(key, sink, activeNonce)
        if (!acquired) {
            abortClaiming(deContext, key, activeNonce, LegacyTerminalOutcome.DUPLICATE_ABORTED)
            return OutputAuthorizationResult.DuplicateRejected("Occurrence $key already active in OutputResourceArbiter")
        }

        if (preempted != null) {
            synchronized(DirectBootStorageLock.lock) {
                DirectBootLegacyClaimStore.transition(
                    context = deContext,
                    key = preempted.key,
                    expectedFrom = LegacyClaimState.OUTPUT_CLAIMING,
                    toState = LegacyClaimState.TERMINAL,
                    expectedNonce = preempted.activeNonce,
                    newNonce = nextNonce("preempt"),
                    processSessionToken = processSessionToken,
                    bootCount = bootCount,
                    terminalOutcome = LegacyTerminalOutcome.CONSUMED_PREEMPTED
                )
            }
        }

        return OutputAuthorizationResult.Authorized(activeNonce, key)
    }

    fun observePhysicalOutput(
        context: Context,
        key: OutputOccurrenceKey,
        activeNonce: String,
        sink: OutputSinkType,
        roomReceiptChecker: ((Long, Long) -> Boolean)? = null
    ): OutputAuthorizationResult {
        if (!isEnabled) return OutputAuthorizationResult.GateDisabled

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        // Check V2 cross-lane
        checkV2CrossLane(deContext, key)?.let {
            arbiter.releaseIfMatches(key, activeNonce)
            abortClaiming(deContext, key, activeNonce, LegacyTerminalOutcome.DUPLICATE_ABORTED)
            return it
        }

        // Check Room receipt
        if (roomReceiptChecker != null && roomReceiptChecker(key.alarmId, key.scheduledAt)) {
            arbiter.releaseIfMatches(key, activeNonce)
            abortClaiming(deContext, key, activeNonce, LegacyTerminalOutcome.DUPLICATE_ABORTED)
            return OutputAuthorizationResult.DuplicateRejected("Room receipt committed prior to physical output")
        }

        synchronized(DirectBootStorageLock.lock) {
            val tr = DirectBootLegacyClaimStore.transition(
                context = deContext,
                key = key,
                expectedFrom = LegacyClaimState.OUTPUT_CLAIMING,
                toState = LegacyClaimState.OUTPUT_OBSERVED,
                expectedNonce = activeNonce,
                newNonce = activeNonce, // Preserve activeNonce for terminal dismiss/snooze/preemption
                processSessionToken = processSessionToken,
                bootCount = bootCount
            )
            return when (tr) {
                is LegacyClaimWriteResult.Committed -> {
                    arbiter.confirmObserved(key, activeNonce)
                    OutputAuthorizationResult.Authorized(activeNonce, key)
                }
                else -> {
                    arbiter.releaseIfMatches(key, activeNonce)
                    OutputAuthorizationResult.StaleOrConflictRejected("observePhysicalOutput failed: $tr")
                }
            }
        }
    }

    fun completeTerminal(
        context: Context,
        key: OutputOccurrenceKey,
        activeNonce: String,
        outcome: LegacyTerminalOutcome,
        wakeConfirm: WakeConfirmLineage? = null
    ): OutputAuthorizationResult {
        if (!isEnabled) return OutputAuthorizationResult.GateDisabled

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        synchronized(DirectBootStorageLock.lock) {
            val tr = DirectBootLegacyClaimStore.transition(
                context = deContext,
                key = key,
                expectedFrom = LegacyClaimState.OUTPUT_OBSERVED,
                toState = LegacyClaimState.TERMINAL,
                expectedNonce = activeNonce,
                newNonce = nextNonce("term"),
                processSessionToken = processSessionToken,
                bootCount = bootCount,
                terminalOutcome = outcome,
                wakeConfirm = wakeConfirm
            )

            arbiter.releaseIfMatches(key, activeNonce)

            return when (tr) {
                is LegacyClaimWriteResult.Committed -> OutputAuthorizationResult.Authorized(activeNonce, key)
                else -> OutputAuthorizationResult.StaleOrConflictRejected("completeTerminal failed: $tr")
            }
        }
    }

    fun retireClaimWithProof(
        context: Context,
        database: AlarmDatabase,
        key: OutputOccurrenceKey
    ): LegacyClaimRetirementResult {
        val verification = verifier.verify(context, database, key)
        val verifiedEvidence = when (verification) {
            is RetirementVerificationResult.Verified -> verification.evidence
            is RetirementVerificationResult.Rejected ->
                return LegacyClaimRetirementResult.Rejected("Retirement verification rejected: ${verification.reason}")
        }

        return DirectBootLegacyClaimStore.retireQualifiedTerminal(
            context = context,
            key = key,
            hasCeDurableProof = true,
            isAbsentFromV2QueueAndOwner = true,
            ageMsOnly = false
        )
    }

    private fun abortClaiming(
        context: Context,
        key: OutputOccurrenceKey,
        activeNonce: String,
        outcome: LegacyTerminalOutcome
    ) {
        synchronized(DirectBootStorageLock.lock) {
            DirectBootLegacyClaimStore.transition(
                context = context,
                key = key,
                expectedFrom = LegacyClaimState.OUTPUT_CLAIMING,
                toState = LegacyClaimState.TERMINAL,
                expectedNonce = activeNonce,
                newNonce = nextNonce("abort"),
                processSessionToken = processSessionToken,
                bootCount = bootCount,
                terminalOutcome = outcome
            )
        }
    }

    private fun checkV2CrossLane(context: Context, key: OutputOccurrenceKey): OutputAuthorizationResult? {
        val ownerResult = DirectBootDurableOwnerStore.read(context)
        val inOwner = when (ownerResult) {
            is com.sysadmindoc.alarmclock.directboot.OwnerReadResult.Success -> {
                val owner = ownerResult.owner
                val inClaim = owner.handoverClaim?.let { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt } ?: false
                val inSession = owner.activeSession?.let { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt } ?: false
                inClaim || inSession
            }
            is com.sysadmindoc.alarmclock.directboot.OwnerReadResult.Corrupt ->
                return OutputAuthorizationResult.StorageUnverifiableFailClosed("Durable owner corrupt: ${ownerResult.reason}")
            is com.sysadmindoc.alarmclock.directboot.OwnerReadResult.UnsupportedSchema ->
                return OutputAuthorizationResult.StorageUnverifiableFailClosed("Durable owner unsupported schema: ${ownerResult.foundVersion}")
            else -> false
        }
        if (inOwner) {
            return OutputAuthorizationResult.V2LaneOwnedRejected("Occurrence $key is owned by V2 Durable Owner")
        }

        val ledgerState = DirectBootDeliveryLedger.readUnacknowledgedRecords(context)
        val inLedger = when (ledgerState) {
            is com.sysadmindoc.alarmclock.directboot.LedgerStorageState.ValidWithRecords ->
                ledgerState.records.any { it.alarmId == key.alarmId && it.triggerTime == key.scheduledAt && it.provenance != "LEGACY_DELIVERY" }
            is com.sysadmindoc.alarmclock.directboot.LedgerStorageState.Corrupt ->
                return OutputAuthorizationResult.StorageUnverifiableFailClosed("Delivery ledger corrupt")
            else -> false
        }
        if (inLedger) {
            return OutputAuthorizationResult.V2LaneOwnedRejected("Occurrence $key has unacknowledged V2 delivery record")
        }

        return null
    }

    companion object {
        @Volatile
        private var instance: SharedOutputAuthorityGate? = null

        fun getInstance(context: Context): SharedOutputAuthorityGate {
            return instance ?: synchronized(this) {
                instance ?: SharedOutputAuthorityGate().also { instance = it }
            }
        }
    }
}
