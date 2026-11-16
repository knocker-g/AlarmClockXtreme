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
import java.util.UUID

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
 *
 * Invariants enforced:
 * 1. Fail-closed Room 38 verification: In CE available mode, null/failing Room checkers reject fail-closed.
 * 2. Strict Lock Separation: DE StorageLock is never held while invoking Arbiter, Room I/O, external callbacks, or Binder.
 * 3. Preemption Stop Confirmation: Preceding output owner must be confirmed stopped before new owner is authorized.
 * 4. Provenance Protection: Process session token is uniquely bound to process lifetime; bootCount is dynamically resolved.
 * 5. Retirement Proof Freshness: DirectBootLegacyClaimStore retires claims exclusively via typed, non-expired evidence.
 */
class SharedOutputAuthorityGate(
    val arbiter: OutputResourceArbiter = OutputResourceArbiter(),
    val verifier: LegacyRetirementProofVerifier = LegacyRetirementProofVerifier,
    var processSessionToken: String = generateProcessSessionToken(),
    var bootCount: Long? = null
) {
    var bootCountProvider: ((Context) -> Long?)? = null

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

    fun resolveBootCount(context: Context): Long? {
        bootCountProvider?.let { return it(context) }
        bootCount?.let { return it }
        return try {
            val count = android.provider.Settings.Global.getInt(
                context.contentResolver,
                android.provider.Settings.Global.BOOT_COUNT
            )
            if (count >= 0) count.toLong() else null
        } catch (_: Throwable) {
            null
        }
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

        val effectiveBootCount = resolveBootCount(context)
            ?: return OutputAuthorizationResult.StorageUnverifiableFailClosed("Boot count unobtainable")

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
                bootCount = effectiveBootCount,
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
        roomReceiptChecker: ((Long, Long) -> Boolean)? = null,
        stopPreemptedOwner: ((ActiveOutputHandle) -> Boolean)? = null
    ): OutputAuthorizationResult {
        if (!isEnabled) return OutputAuthorizationResult.GateDisabled

        if (incomingNonce.isBlank()) {
            return OutputAuthorizationResult.StaleOrConflictRejected("Blank claimNonce")
        }

        val effectiveBootCount = resolveBootCount(context)
            ?: return OutputAuthorizationResult.StorageUnverifiableFailClosed("Boot count unobtainable")

        // Fail-closed verification: in CE mode, roomReceiptChecker is mandatory
        if (!directBootModeOnly && roomReceiptChecker == null) {
            return OutputAuthorizationResult.StorageUnverifiableFailClosed("Room receipt checker required when CE is available")
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
            if (existing.processSessionToken != processSessionToken || existing.bootCount != effectiveBootCount) {
                if (existing.state != LegacyClaimState.TERMINAL) {
                    DirectBootLegacyClaimStore.transition(
                        context = deContext,
                        key = key,
                        expectedFrom = existing.state,
                        toState = LegacyClaimState.UNKNOWN,
                        expectedNonce = existing.claimNonce,
                        newNonce = nextNonce("unknown"),
                        processSessionToken = processSessionToken,
                        bootCount = effectiveBootCount
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
                        bootCount = effectiveBootCount,
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
                bootCount = effectiveBootCount
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
                abortClaiming(deContext, key, activeNonce, effectiveBootCount, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                return OutputAuthorizationResult.StorageUnverifiableFailClosed("Room check exception: ${e.message}")
            }

            if (receiptExists) {
                abortClaiming(deContext, key, activeNonce, effectiveBootCount, LegacyTerminalOutcome.DUPLICATE_ABORTED)
                return OutputAuthorizationResult.DuplicateRejected("Room receipt already exists")
            }
        }

        // PHASE 3: Arbiter acquisition outside DE lock with stop confirmation
        val (acquired, preempted) = arbiter.acquirePending(
            key = key,
            sink = sink,
            activeNonce = activeNonce,
            stopPreemptedOwner = stopPreemptedOwner
        )
        if (!acquired) {
            abortClaiming(deContext, key, activeNonce, effectiveBootCount, LegacyTerminalOutcome.DUPLICATE_ABORTED)
            return OutputAuthorizationResult.DuplicateRejected("Occurrence $key unconfirmed in OutputResourceArbiter")
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
                    bootCount = effectiveBootCount,
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

        val effectiveBootCount = resolveBootCount(context)
            ?: return OutputAuthorizationResult.StorageUnverifiableFailClosed("Boot count unobtainable")

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        // Cross-lane check against V2
        checkV2CrossLane(deContext, key)?.let {
            arbiter.releaseIfMatches(key, activeNonce)
            abortClaiming(deContext, key, activeNonce, effectiveBootCount, LegacyTerminalOutcome.DUPLICATE_ABORTED)
            return it
        }

        // Room receipt check outside lock
        if (roomReceiptChecker != null) {
            val receiptExists = try {
                roomReceiptChecker(key.alarmId, key.scheduledAt)
            } catch (e: Exception) {
                arbiter.releaseIfMatches(key, activeNonce)
                abortClaiming(deContext, key, activeNonce, effectiveBootCount, LegacyTerminalOutcome.REJECTED_NO_OUTPUT)
                return OutputAuthorizationResult.StorageUnverifiableFailClosed("Room check exception: ${e.message}")
            }
            if (receiptExists) {
                arbiter.releaseIfMatches(key, activeNonce)
                abortClaiming(deContext, key, activeNonce, effectiveBootCount, LegacyTerminalOutcome.DUPLICATE_ABORTED)
                return OutputAuthorizationResult.DuplicateRejected("Room receipt committed prior to physical output")
            }
        }

        // DE transition under DE lock
        val tr = synchronized(DirectBootStorageLock.lock) {
            DirectBootLegacyClaimStore.transition(
                context = deContext,
                key = key,
                expectedFrom = LegacyClaimState.OUTPUT_CLAIMING,
                toState = LegacyClaimState.OUTPUT_OBSERVED,
                expectedNonce = activeNonce,
                newNonce = activeNonce, // Preserve activeNonce
                processSessionToken = processSessionToken,
                bootCount = effectiveBootCount
            )
        }

        // OUTSIDE DE lock: update arbiter and confirm handle
        return when (tr) {
            is LegacyClaimWriteResult.Committed -> {
                val confirmed = arbiter.confirmObserved(key, activeNonce)
                if (confirmed) {
                    OutputAuthorizationResult.Authorized(activeNonce, key)
                } else {
                    arbiter.releaseIfMatches(key, activeNonce)
                    abortClaiming(deContext, key, activeNonce, effectiveBootCount, LegacyTerminalOutcome.CONSUMED_PREEMPTED)
                    OutputAuthorizationResult.StaleOrConflictRejected("Arbiter handle modified before physical observation confirmed")
                }
            }
            else -> {
                arbiter.releaseIfMatches(key, activeNonce)
                OutputAuthorizationResult.StaleOrConflictRejected("observePhysicalOutput failed: $tr")
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

        val effectiveBootCount = resolveBootCount(context)
            ?: return OutputAuthorizationResult.StorageUnverifiableFailClosed("Boot count unobtainable")

        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        // DE transition under DE lock
        val tr = synchronized(DirectBootStorageLock.lock) {
            DirectBootLegacyClaimStore.transition(
                context = deContext,
                key = key,
                expectedFrom = LegacyClaimState.OUTPUT_OBSERVED,
                toState = LegacyClaimState.TERMINAL,
                expectedNonce = activeNonce,
                newNonce = nextNonce("term"),
                processSessionToken = processSessionToken,
                bootCount = effectiveBootCount,
                terminalOutcome = outcome,
                wakeConfirm = wakeConfirm
            )
        }

        // OUTSIDE DE lock: release arbiter handle
        arbiter.releaseIfMatches(key, activeNonce)

        return when (tr) {
            is LegacyClaimWriteResult.Committed -> OutputAuthorizationResult.Authorized(activeNonce, key)
            else -> OutputAuthorizationResult.StaleOrConflictRejected("completeTerminal failed: $tr")
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

        return DirectBootLegacyClaimStore.retireWithEvidence(
            context = context,
            evidence = verifiedEvidence
        )
    }

    fun abortClaim(
        context: Context,
        key: OutputOccurrenceKey,
        activeNonce: String,
        outcome: LegacyTerminalOutcome = LegacyTerminalOutcome.REJECTED_NO_OUTPUT
    ): Boolean {
        if (!isEnabled) return false
        if (activeNonce.isBlank()) return false

        // OS Binder / Settings read and DE context resolution strictly outside DE StorageLock
        val effectiveBootCount = resolveBootCount(context) ?: return false
        val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

        val committed = synchronized(DirectBootStorageLock.lock) {
            val state = DirectBootLegacyClaimStore.inspectStorageState(deContext)
            val records = when (state) {
                is LegacyClaimStorageState.ValidWithRecords -> state.records
                is LegacyClaimStorageState.Corrupt,
                is LegacyClaimStorageState.UnsupportedSchema,
                is LegacyClaimStorageState.Absent,
                is LegacyClaimStorageState.ValidEmpty -> return@synchronized false
            }
            val existing = records.firstOrNull { it.key == key } ?: return@synchronized false
            if (existing.state == LegacyClaimState.UNKNOWN || existing.state == LegacyClaimState.TERMINAL) {
                return@synchronized false
            }
            if (existing.claimNonce != activeNonce) {
                return@synchronized false
            }
            if (existing.processSessionToken != processSessionToken || existing.bootCount != effectiveBootCount) {
                return@synchronized false
            }
            if (existing.state == LegacyClaimState.OUTPUT_CLAIMING ||
                existing.state == LegacyClaimState.OUTPUT_OBSERVED ||
                existing.state == LegacyClaimState.START_REQUESTED
            ) {
                val tr = DirectBootLegacyClaimStore.transition(
                    context = deContext,
                    key = key,
                    expectedFrom = existing.state,
                    toState = LegacyClaimState.TERMINAL,
                    expectedNonce = activeNonce,
                    newNonce = nextNonce("abort"),
                    processSessionToken = processSessionToken,
                    bootCount = effectiveBootCount,
                    terminalOutcome = outcome
                )
                tr is LegacyClaimWriteResult.Committed
            } else {
                false
            }
        }

        if (!committed) return false

        // Strictly outside DirectBootStorageLock.lock: release Arbiter handle only after DE commit+readback succeeded
        check(!Thread.holdsLock(DirectBootStorageLock.lock)) {
            "DirectBootStorageLock must NOT be held during Arbiter operations"
        }
        return arbiter.releaseIfMatches(key, activeNonce)
    }

    private fun abortClaiming(
        context: Context,
        key: OutputOccurrenceKey,
        activeNonce: String,
        bootCount: Long,
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
        fun generateProcessSessionToken(): String {
            val pid = android.os.Process.myPid()
            val time = System.currentTimeMillis()
            val uuid = UUID.randomUUID().toString().take(8)
            val sessionToken = "proc-$pid-$time-$uuid"
            return sessionToken
        }

        @Volatile
        private var instance: SharedOutputAuthorityGate? = null

        fun getInstance(context: Context): SharedOutputAuthorityGate {
            return instance ?: synchronized(this) {
                instance ?: SharedOutputAuthorityGate().also { instance = it }
            }
        }
    }
}
