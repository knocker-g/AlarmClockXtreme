package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException

/**
 * Inactive DE -> CE Evidence Handoff & Qualified DE ACK Coordinator for KNO-75
 * (Phase D3-C2-P3D-P1 & Phase D3-C2-P3D-P2B).
 *
 * Scope & Safety Boundaries:
 * - Canonical Lock Order: Always acquires [DirectBootStorageLock.lock] (outer DE monitor)
 *   before [DirectBootCeTakeoverStore.ceStorageLock] (inner CE monitor). Reverse acquisition (`CE -> DE`) is prohibited.
 * - P1 Non-Destructive Handoff Entrypoints ([handoffDelivered], [handoffMissed], [handoffByOccurrenceIdentity]):
 *   - Execute DE Record Read -> Canonical Occurrence Identity Verification -> Idempotent CE Commit -> Independent CE Readback.
 *   - Preserve all DE records intact (`deRecordPreserved = true`, `deAckEligible = false`).
 * - P2B Qualified DE ACK Entrypoints ([acknowledgeDeliveredWithCeProof], [acknowledgeMissedWithCeProof],
 *   [handoffAndAcknowledgeDelivered], [handoffAndAcknowledgeMissed]):
 *   - Require live in-lock CE verification under `DirectBootStorageLock.lock -> ceStorageLock`.
 *   - Perform surgical single-record retirement preserving a valid versioned JSON envelope (`{"schemaVersion":1,"records":[...]}`)
 *     even when the last record is retired (`ValidEmpty`), without ever calling `clear()` or removing the payload key.
 * - Zero `AlarmSession`, `IssuedOccurrence`, or `AlarmEvent` mutations.
 * - Zero `Alarm` definition mutations (`ONE_TIME` `isEnabled` remains invariant).
 */
object DirectBootCeHandoffCoordinator {

    private sealed class DeStoresSnapshot {
        data class Valid(
            val deliveredRecords: List<DirectBootDeliveryRecord>,
            val missedRecords: List<DirectBootMissedRecord>
        ) : DeStoresSnapshot()
        data class Failed(val result: CeHandoffResult.Rejected) : DeStoresSnapshot()
    }

    private fun checkCePrerequisites(
        ceContext: Context,
        unlockGate: (Context) -> Boolean
    ): CeHandoffResult.Rejected? {
        if (ceContext.isDeviceProtectedStorage) {
            return CeHandoffResult.Rejected.CeContextInvalid(
                "Device-protected context rejected as CE destination context"
            )
        }
        val unlocked = try {
            unlockGate(ceContext)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return CeHandoffResult.Rejected.CeLocked(
                "Exception checking CE unlock state: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
        if (!unlocked) {
            return CeHandoffResult.Rejected.CeLocked("User CE storage is locked; DE->CE handoff rejected")
        }
        return null
    }

    private fun checkCePrerequisitesForAck(
        ceContext: Context,
        unlockGate: (Context) -> Boolean
    ): CeQualifiedAckResult.Rejected? {
        if (ceContext.isDeviceProtectedStorage) {
            return CeQualifiedAckResult.Rejected.CeContextInvalid(
                "Device-protected context rejected as CE proof context"
            )
        }
        val unlocked = try {
            unlockGate(ceContext)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return CeQualifiedAckResult.Rejected.CeLocked(
                "Exception checking CE unlock state: ${t.javaClass.simpleName}: ${t.message}"
            )
        }
        if (!unlocked) {
            return CeQualifiedAckResult.Rejected.CeLocked("User CE storage is locked; qualified DE ACK rejected")
        }
        return null
    }

    private fun inspectBothDeStores(deContext: Context): DeStoresSnapshot {
        val deliveredList: List<DirectBootDeliveryRecord> =
            when (val ledgerState = DirectBootDeliveryLedger.inspectStorageState(deContext)) {
                is LedgerStorageState.Absent,
                is LedgerStorageState.ValidEmpty -> emptyList()
                is LedgerStorageState.ValidWithRecords -> ledgerState.records
                is LedgerStorageState.Corrupt ->
                    return DeStoresSnapshot.Failed(
                        CeHandoffResult.Rejected.DeStorageCorrupt("DirectBootDeliveryLedger", ledgerState.reason)
                    )
                is LedgerStorageState.UnsupportedSchema ->
                    return DeStoresSnapshot.Failed(
                        CeHandoffResult.Rejected.DeUnsupportedSchema(
                            "DirectBootDeliveryLedger",
                            ledgerState.foundVersion,
                            ledgerState.expectedVersion
                        )
                    )
            }

        val missedList: List<DirectBootMissedRecord> =
            when (val missedState = DirectBootMissedStore.inspectStorageState(deContext)) {
                is MissedStoreStorageState.Absent,
                is MissedStoreStorageState.ValidEmpty -> emptyList()
                is MissedStoreStorageState.ValidWithRecords -> missedState.records
                is MissedStoreStorageState.Corrupt ->
                    return DeStoresSnapshot.Failed(
                        CeHandoffResult.Rejected.DeStorageCorrupt("DirectBootMissedStore", missedState.reason)
                    )
                is MissedStoreStorageState.UnsupportedSchema ->
                    return DeStoresSnapshot.Failed(
                        CeHandoffResult.Rejected.DeUnsupportedSchema(
                            "DirectBootMissedStore",
                            missedState.foundVersion,
                            missedState.expectedVersion
                        )
                    )
                is MissedStoreStorageState.PersistenceUncertain ->
                    return DeStoresSnapshot.Failed(
                        CeHandoffResult.Rejected.DeStorageUncertain("DirectBootMissedStore", missedState.reason)
                    )
            }

        return DeStoresSnapshot.Valid(deliveredList, missedList)
    }

    private fun mapDeFailedToAckRejected(failed: CeHandoffResult.Rejected): CeQualifiedAckResult.Rejected {
        return when (failed) {
            is CeHandoffResult.Rejected.DeStorageCorrupt ->
                CeQualifiedAckResult.Rejected.DeStorageCorrupt(failed.storeDomain, failed.reason)
            is CeHandoffResult.Rejected.DeUnsupportedSchema ->
                CeQualifiedAckResult.Rejected.DeUnsupportedSchema(
                    failed.storeDomain,
                    failed.foundVersion,
                    failed.expectedVersion
                )
            is CeHandoffResult.Rejected.DeStorageUncertain ->
                CeQualifiedAckResult.Rejected.DeStorageUncertain(failed.storeDomain, failed.reason)
            else -> CeQualifiedAckResult.Rejected.InvalidRecordOrIdentity("Unexpected DE failure: $failed")
        }
    }

    // =========================================================================
    // P1 Non-Destructive Handoff Entrypoints
    // =========================================================================

    fun handoffDelivered(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootDeliveryRecord,
        recordedAtEpochMs: Long = System.currentTimeMillis()
    ): CeHandoffResult {
        return handoffDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = candidateRecord,
            recordedAtEpochMs = recordedAtEpochMs,
            unlockGate = DirectBootCeTakeoverStore.unlockGateSeam,
            preCommitHook = {},
            postCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun handoffDelivered(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootDeliveryRecord,
        recordedAtEpochMs: Long = System.currentTimeMillis(),
        unlockGate: (Context) -> Boolean,
        preCommitHook: () -> Unit = {},
        postCommitHook: () -> Unit = {}
    ): CeHandoffResult {
        checkCePrerequisites(ceContext, unlockGate)?.let { return it }

        if (candidateRecord.kind == "QUICK") {
            return CeHandoffResult.Rejected.QuickNotQualified(
                "QUICK delivery records are out of scope for CE takeover handoff"
            )
        }
        if (candidateRecord.kind != DirectBootDeliveryRecord.KIND_NORMAL ||
            candidateRecord.alarmId <= 0L ||
            candidateRecord.triggerTime <= 0L ||
            candidateRecord.deliveredAt <= 0L ||
            candidateRecord.generation < 1 ||
            candidateRecord.instanceToken.isBlank() ||
            (candidateRecord.schemaVersion != DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION && candidateRecord.schemaVersion != 1) ||
            recordedAtEpochMs <= 0L
        ) {
            return CeHandoffResult.Rejected.InvalidRecordOrIdentity(
                "Invalid DirectBootDeliveryRecord fields or recordedAtEpochMs: $candidateRecord"
            )
        }

        val expectedRecordId = DirectBootDeliveryRecord.computeRecordId(
            kind = candidateRecord.kind,
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )
        if (candidateRecord.recordId != expectedRecordId) {
            return CeHandoffResult.Rejected.InvalidRecordOrIdentity(
                "Forged or mismatched delivery recordId: expected '$expectedRecordId', got '${candidateRecord.recordId}'"
            )
        }

        synchronized(DirectBootStorageLock.lock) {
            val snapshot = when (val s = inspectBothDeStores(deContext)) {
                is DeStoresSnapshot.Failed -> return s.result
                is DeStoresSnapshot.Valid -> s
            }

            // Mutual exclusion check across DE stores for the same occurrence
            val conflictingDeMissed = snapshot.missedRecords.firstOrNull {
                it.alarmId == candidateRecord.alarmId &&
                    it.triggerTime == candidateRecord.triggerTime &&
                    it.generation == candidateRecord.generation &&
                    it.instanceToken == candidateRecord.instanceToken
            }
            if (conflictingDeMissed != null) {
                return CeHandoffResult.Rejected.DeCrossStoreConflict(
                    deliveryRecord = candidateRecord,
                    missedRecord = conflictingDeMissed,
                    details = "Mutual exclusion violation: occurrence exists in both DE DeliveryLedger and DE MissedStore"
                )
            }

            val authoritativeDeRecord = snapshot.deliveredRecords.firstOrNull {
                it.recordId == expectedRecordId ||
                    (it.alarmId == candidateRecord.alarmId &&
                        it.triggerTime == candidateRecord.triggerTime &&
                        it.generation == candidateRecord.generation &&
                        it.instanceToken == candidateRecord.instanceToken)
            } ?: return CeHandoffResult.Rejected.DeRecordNotFound(
                "Delivery record '$expectedRecordId' not present in DE DirectBootDeliveryLedger"
            )

            val matchesContent = authoritativeDeRecord.recordId == candidateRecord.recordId &&
                authoritativeDeRecord.kind == candidateRecord.kind &&
                authoritativeDeRecord.alarmId == candidateRecord.alarmId &&
                authoritativeDeRecord.triggerTime == candidateRecord.triggerTime &&
                authoritativeDeRecord.deliveredAt == candidateRecord.deliveredAt &&
                authoritativeDeRecord.generation == candidateRecord.generation &&
                authoritativeDeRecord.instanceToken == candidateRecord.instanceToken &&
                authoritativeDeRecord.provenance == candidateRecord.provenance &&
                (candidateRecord.restoreEpoch == 0L || authoritativeDeRecord.restoreEpoch == candidateRecord.restoreEpoch)

            if (!matchesContent) {
                return CeHandoffResult.Rejected.DeRecordContentMismatch(
                    "Candidate delivery record differs from authoritative DE ledger record: expected=$authoritativeDeRecord, candidate=$candidateRecord"
                )
            }

            val intakeResult = DirectBootCeTakeoverStore.recordDelivered(
                ceContext = ceContext,
                deliveryRecord = authoritativeDeRecord,
                recordedAtEpochMs = recordedAtEpochMs,
                unlockGate = unlockGate,
                preCommitHook = preCommitHook,
                postCommitHook = postCommitHook
            )
            return mapIntakeResult(intakeResult)
        }
    }

    fun handoffMissed(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootMissedRecord,
        recordedAtEpochMs: Long = System.currentTimeMillis()
    ): CeHandoffResult {
        return handoffMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = candidateRecord,
            recordedAtEpochMs = recordedAtEpochMs,
            unlockGate = DirectBootCeTakeoverStore.unlockGateSeam,
            preCommitHook = {},
            postCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun handoffMissed(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootMissedRecord,
        recordedAtEpochMs: Long = System.currentTimeMillis(),
        unlockGate: (Context) -> Boolean,
        preCommitHook: () -> Unit = {},
        postCommitHook: () -> Unit = {}
    ): CeHandoffResult {
        checkCePrerequisites(ceContext, unlockGate)?.let { return it }

        if (candidateRecord.kind == "QUICK") {
            return CeHandoffResult.Rejected.QuickNotQualified(
                "QUICK missed records are out of scope for CE takeover handoff"
            )
        }
        if (candidateRecord.kind != DirectBootMissedRecord.KIND_NORMAL ||
            candidateRecord.alarmId <= 0L ||
            candidateRecord.triggerTime <= 0L ||
            candidateRecord.missedAt <= 0L ||
            candidateRecord.generation < 1 ||
            candidateRecord.instanceToken.isBlank() ||
            candidateRecord.classification !in DirectBootMissedRecord.APPROVED_CLASSIFICATIONS ||
            (candidateRecord.schemaVersion != DirectBootMissedStore.CURRENT_SCHEMA_VERSION && candidateRecord.schemaVersion != 1) ||
            recordedAtEpochMs <= 0L
        ) {
            return CeHandoffResult.Rejected.InvalidRecordOrIdentity(
                "Invalid DirectBootMissedRecord fields or recordedAtEpochMs: $candidateRecord"
            )
        }

        val expectedRecordId = DirectBootMissedRecord.computeRecordId(
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )
        if (candidateRecord.recordId != expectedRecordId) {
            return CeHandoffResult.Rejected.InvalidRecordOrIdentity(
                "Forged or mismatched missed recordId: expected '$expectedRecordId', got '${candidateRecord.recordId}'"
            )
        }

        synchronized(DirectBootStorageLock.lock) {
            val snapshot = when (val s = inspectBothDeStores(deContext)) {
                is DeStoresSnapshot.Failed -> return s.result
                is DeStoresSnapshot.Valid -> s
            }

            // Mutual exclusion check across DE stores for the same occurrence
            val conflictingDeDelivered = snapshot.deliveredRecords.firstOrNull {
                it.alarmId == candidateRecord.alarmId &&
                    it.triggerTime == candidateRecord.triggerTime &&
                    it.generation == candidateRecord.generation &&
                    it.instanceToken == candidateRecord.instanceToken
            }
            if (conflictingDeDelivered != null) {
                return CeHandoffResult.Rejected.DeCrossStoreConflict(
                    deliveryRecord = conflictingDeDelivered,
                    missedRecord = candidateRecord,
                    details = "Mutual exclusion violation: occurrence exists in both DE DeliveryLedger and DE MissedStore"
                )
            }

            val authoritativeDeRecord = snapshot.missedRecords.firstOrNull {
                it.recordId == expectedRecordId ||
                    (it.alarmId == candidateRecord.alarmId &&
                        it.triggerTime == candidateRecord.triggerTime &&
                        it.generation == candidateRecord.generation &&
                        it.instanceToken == candidateRecord.instanceToken)
            } ?: return CeHandoffResult.Rejected.DeRecordNotFound(
                "Missed record '$expectedRecordId' not present in DE DirectBootMissedStore"
            )

            val matchesContent = authoritativeDeRecord.recordId == candidateRecord.recordId &&
                authoritativeDeRecord.kind == candidateRecord.kind &&
                authoritativeDeRecord.alarmId == candidateRecord.alarmId &&
                authoritativeDeRecord.triggerTime == candidateRecord.triggerTime &&
                authoritativeDeRecord.missedAt == candidateRecord.missedAt &&
                authoritativeDeRecord.generation == candidateRecord.generation &&
                authoritativeDeRecord.instanceToken == candidateRecord.instanceToken &&
                authoritativeDeRecord.classification == candidateRecord.classification &&
                (candidateRecord.restoreEpoch == 0L || authoritativeDeRecord.restoreEpoch == candidateRecord.restoreEpoch)

            if (!matchesContent) {
                return CeHandoffResult.Rejected.DeRecordContentMismatch(
                    "Candidate missed record differs from authoritative DE missed record: expected=$authoritativeDeRecord, candidate=$candidateRecord"
                )
            }

            val intakeResult = DirectBootCeTakeoverStore.recordMissed(
                ceContext = ceContext,
                missedRecord = authoritativeDeRecord,
                recordedAtEpochMs = recordedAtEpochMs,
                unlockGate = unlockGate,
                preCommitHook = preCommitHook,
                postCommitHook = postCommitHook
            )
            return mapIntakeResult(intakeResult)
        }
    }

    fun handoffByOccurrenceIdentity(
        deContext: Context,
        ceContext: Context,
        identity: FiredOccurrenceIdentity,
        recordedAtEpochMs: Long = System.currentTimeMillis()
    ): CeHandoffResult {
        return handoffByOccurrenceIdentity(
            deContext = deContext,
            ceContext = ceContext,
            identity = identity,
            recordedAtEpochMs = recordedAtEpochMs,
            unlockGate = DirectBootCeTakeoverStore.unlockGateSeam,
            preCommitHook = {},
            postCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun handoffByOccurrenceIdentity(
        deContext: Context,
        ceContext: Context,
        identity: FiredOccurrenceIdentity,
        recordedAtEpochMs: Long = System.currentTimeMillis(),
        unlockGate: (Context) -> Boolean,
        preCommitHook: () -> Unit = {},
        postCommitHook: () -> Unit = {}
    ): CeHandoffResult {
        checkCePrerequisites(ceContext, unlockGate)?.let { return it }

        if (identity.kind == "QUICK") {
            return CeHandoffResult.Rejected.QuickNotQualified(
                "QUICK occurrences are out of scope for CE takeover handoff"
            )
        }
        if (identity.kind != DirectBootDeliveryRecord.KIND_NORMAL ||
            identity.alarmId <= 0L ||
            identity.triggerTime <= 0L ||
            identity.generation < 1 ||
            identity.instanceToken.isBlank() ||
            recordedAtEpochMs <= 0L
        ) {
            return CeHandoffResult.Rejected.InvalidRecordOrIdentity(
                "Invalid FiredOccurrenceIdentity or recordedAtEpochMs: $identity"
            )
        }

        synchronized(DirectBootStorageLock.lock) {
            val snapshot = when (val s = inspectBothDeStores(deContext)) {
                is DeStoresSnapshot.Failed -> return s.result
                is DeStoresSnapshot.Valid -> s
            }

            val matchedDelivered = snapshot.deliveredRecords.firstOrNull {
                it.kind == identity.kind &&
                    it.alarmId == identity.alarmId &&
                    it.triggerTime == identity.triggerTime &&
                    it.generation == identity.generation &&
                    it.instanceToken == identity.instanceToken
            }
            val matchedMissed = snapshot.missedRecords.firstOrNull {
                it.kind == identity.kind &&
                    it.alarmId == identity.alarmId &&
                    it.triggerTime == identity.triggerTime &&
                    it.generation == identity.generation &&
                    it.instanceToken == identity.instanceToken
            }

            if (matchedDelivered != null && matchedMissed != null) {
                return CeHandoffResult.Rejected.DeCrossStoreConflict(
                    deliveryRecord = matchedDelivered,
                    missedRecord = matchedMissed,
                    details = "Occurrence $identity exists in both DE DeliveryLedger and DE MissedStore"
                )
            }

            if (matchedDelivered != null) {
                return handoffDelivered(
                    deContext = deContext,
                    ceContext = ceContext,
                    candidateRecord = matchedDelivered,
                    recordedAtEpochMs = recordedAtEpochMs,
                    unlockGate = unlockGate,
                    preCommitHook = preCommitHook,
                    postCommitHook = postCommitHook
                )
            }

            if (matchedMissed != null) {
                return handoffMissed(
                    deContext = deContext,
                    ceContext = ceContext,
                    candidateRecord = matchedMissed,
                    recordedAtEpochMs = recordedAtEpochMs,
                    unlockGate = unlockGate,
                    preCommitHook = preCommitHook,
                    postCommitHook = postCommitHook
                )
            }

            return CeHandoffResult.Rejected.DeRecordNotFound(
                "No DE Delivered or Missed record found for occurrence identity $identity"
            )
        }
    }

    // =========================================================================
    // P2B Qualified DE ACK & Surgical Single-Record Retirement Entrypoints
    // =========================================================================

    fun acknowledgeDeliveredWithCeProof(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootDeliveryRecord,
        confirmedReceipt: CeHandoffResult.Confirmed? = null
    ): CeQualifiedAckResult {
        return acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = candidateRecord,
            confirmedReceipt = confirmedReceipt,
            unlockGate = DirectBootCeTakeoverStore.unlockGateSeam,
            preAckCommitHook = {},
            postAckCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun acknowledgeDeliveredWithCeProof(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootDeliveryRecord,
        confirmedReceipt: CeHandoffResult.Confirmed? = null,
        unlockGate: (Context) -> Boolean,
        preAckCommitHook: () -> Unit = {},
        postAckCommitHook: () -> Unit = {}
    ): CeQualifiedAckResult {
        return acknowledgeDeliveredInternal(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = candidateRecord,
            confirmedReceipt = confirmedReceipt,
            allowIdempotentWhenDeAbsent = confirmedReceipt != null,
            unlockGate = unlockGate,
            preAckCommitHook = preAckCommitHook,
            postAckCommitHook = postAckCommitHook
        )
    }

    fun acknowledgeMissedWithCeProof(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootMissedRecord,
        confirmedReceipt: CeHandoffResult.Confirmed? = null
    ): CeQualifiedAckResult {
        return acknowledgeMissedWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = candidateRecord,
            confirmedReceipt = confirmedReceipt,
            unlockGate = DirectBootCeTakeoverStore.unlockGateSeam,
            preAckCommitHook = {},
            postAckCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun acknowledgeMissedWithCeProof(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootMissedRecord,
        confirmedReceipt: CeHandoffResult.Confirmed? = null,
        unlockGate: (Context) -> Boolean,
        preAckCommitHook: () -> Unit = {},
        postAckCommitHook: () -> Unit = {}
    ): CeQualifiedAckResult {
        return acknowledgeMissedInternal(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = candidateRecord,
            confirmedReceipt = confirmedReceipt,
            allowIdempotentWhenDeAbsent = confirmedReceipt != null,
            unlockGate = unlockGate,
            preAckCommitHook = preAckCommitHook,
            postAckCommitHook = postAckCommitHook
        )
    }

    fun handoffAndAcknowledgeDelivered(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootDeliveryRecord,
        recordedAtEpochMs: Long = System.currentTimeMillis()
    ): CeQualifiedAckResult {
        return handoffAndAcknowledgeDelivered(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = candidateRecord,
            recordedAtEpochMs = recordedAtEpochMs,
            unlockGate = DirectBootCeTakeoverStore.unlockGateSeam,
            preCeCommitHook = {},
            postCeCommitHook = {},
            preAckCommitHook = {},
            postAckCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun handoffAndAcknowledgeDelivered(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootDeliveryRecord,
        recordedAtEpochMs: Long = System.currentTimeMillis(),
        unlockGate: (Context) -> Boolean,
        preCeCommitHook: () -> Unit = {},
        postCeCommitHook: () -> Unit = {},
        preAckCommitHook: () -> Unit = {},
        postAckCommitHook: () -> Unit = {}
    ): CeQualifiedAckResult {
        checkCePrerequisitesForAck(ceContext, unlockGate)?.let { return it }
        validateCandidateDeliveryForAck(candidateRecord, recordedAtEpochMs)?.let { return it }

        val expectedRecordId = DirectBootDeliveryRecord.computeRecordId(
            kind = candidateRecord.kind,
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )

        synchronized(DirectBootStorageLock.lock) {
            synchronized(DirectBootCeTakeoverStore.ceStorageLock) {
                checkCePrerequisitesForAck(ceContext, unlockGate)?.let { return it }

                val snapshot = when (val s = inspectBothDeStores(deContext)) {
                    is DeStoresSnapshot.Failed -> return mapDeFailedToAckRejected(s.result)
                    is DeStoresSnapshot.Valid -> s
                }

                // Check 10: Opposite DE store mutual exclusion
                val conflictingDeMissed = snapshot.missedRecords.firstOrNull {
                    it.alarmId == candidateRecord.alarmId &&
                        it.triggerTime == candidateRecord.triggerTime &&
                        it.generation == candidateRecord.generation &&
                        it.instanceToken == candidateRecord.instanceToken
                }
                if (conflictingDeMissed != null) {
                    return CeQualifiedAckResult.Rejected.DeCrossStoreConflict(
                        deliveryRecord = candidateRecord,
                        missedRecord = conflictingDeMissed,
                        details = "Mutual exclusion violation: occurrence exists in both DE DeliveryLedger and DE MissedStore"
                    )
                }

                val persistedDeRecord = snapshot.deliveredRecords.firstOrNull {
                    it.recordId == expectedRecordId ||
                        (it.alarmId == candidateRecord.alarmId &&
                            it.triggerTime == candidateRecord.triggerTime &&
                            it.generation == candidateRecord.generation &&
                            it.instanceToken == candidateRecord.instanceToken)
                }

                // If DE record is already retired, verify whether CE holds the matching record (idempotent retry)
                if (persistedDeRecord == null) {
                    val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate)
                    if (ceState is CeTakeoverStorageState.Absent || ceState is CeTakeoverStorageState.ValidEmpty) {
                        return CeQualifiedAckResult.Rejected.DeRecordNotFound(
                            "Delivery record '$expectedRecordId' not present in DE DeliveryLedger or CE Takeover Store"
                        )
                    }
                    if (ceState is CeTakeoverStorageState.ValidWithRecords) {
                        val expectedOccKey = DirectBootCeTakeoverStore.computeOccurrenceKey(
                            alarmId = candidateRecord.alarmId,
                            triggerTime = candidateRecord.triggerTime,
                            generation = candidateRecord.generation,
                            instanceToken = candidateRecord.instanceToken
                        )
                        val match = ceState.records.firstOrNull {
                            it.occurrenceKey == expectedOccKey || it.sourceRecordId == expectedRecordId
                        }
                        if (match == null) {
                            return CeQualifiedAckResult.Rejected.DeRecordNotFound(
                                "Delivery record '$expectedRecordId' not present in DE DeliveryLedger or CE Takeover Store"
                            )
                        }
                    }
                    return acknowledgeDeliveredInternal(
                        deContext = deContext,
                        ceContext = ceContext,
                        candidateRecord = candidateRecord,
                        confirmedReceipt = null,
                        allowIdempotentWhenDeAbsent = true,
                        unlockGate = unlockGate,
                        preAckCommitHook = preAckCommitHook,
                        postAckCommitHook = postAckCommitHook
                    )
                }

                if (persistedDeRecord != candidateRecord) {
                    return CeQualifiedAckResult.Rejected.DeRecordModifiedConflict(
                        "Candidate delivery record differs from authoritative DE ledger record: expected=$persistedDeRecord, candidate=$candidateRecord"
                    )
                }

                val intakeResult = DirectBootCeTakeoverStore.recordDelivered(
                    ceContext = ceContext,
                    deliveryRecord = persistedDeRecord,
                    recordedAtEpochMs = recordedAtEpochMs,
                    unlockGate = unlockGate,
                    preCommitHook = preCeCommitHook,
                    postCommitHook = postCeCommitHook
                )

                val confirmedReceipt: CeHandoffResult.Confirmed = when (intakeResult) {
                    is CeTakeoverIntakeResult.RecordedConfirmed -> CeHandoffResult.Confirmed(
                        ceRecord = intakeResult.record,
                        totalCeRecords = intakeResult.totalRecords,
                        alreadyRecordedInCe = false,
                        writePerformed = true
                    )
                    is CeTakeoverIntakeResult.AlreadyRecordedConfirmed -> CeHandoffResult.Confirmed(
                        ceRecord = intakeResult.existingRecord,
                        totalCeRecords = intakeResult.totalRecords,
                        alreadyRecordedInCe = true,
                        writePerformed = false
                    )
                    is CeTakeoverIntakeResult.PostCommitFailure -> {
                        return CeQualifiedAckResult.Rejected.PreAckCommitFailure(
                            details = "Post-CE-commit hook failed prior to DE ACK commit: ${intakeResult.cause.javaClass.simpleName}: ${intakeResult.cause.message}",
                            cause = intakeResult.cause
                        )
                    }
                    is CeTakeoverIntakeResult.CancellationAfterConfirm -> throw intakeResult.cancellation
                    else -> return mapIntakeFailureToAckResult(intakeResult)
                }

                return acknowledgeDeliveredInternal(
                    deContext = deContext,
                    ceContext = ceContext,
                    candidateRecord = persistedDeRecord,
                    confirmedReceipt = confirmedReceipt,
                    allowIdempotentWhenDeAbsent = true,
                    unlockGate = unlockGate,
                    preAckCommitHook = preAckCommitHook,
                    postAckCommitHook = postAckCommitHook
                )
            }
        }
    }

    fun handoffAndAcknowledgeMissed(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootMissedRecord,
        recordedAtEpochMs: Long = System.currentTimeMillis()
    ): CeQualifiedAckResult {
        return handoffAndAcknowledgeMissed(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = candidateRecord,
            recordedAtEpochMs = recordedAtEpochMs,
            unlockGate = DirectBootCeTakeoverStore.unlockGateSeam,
            preCeCommitHook = {},
            postCeCommitHook = {},
            preAckCommitHook = {},
            postAckCommitHook = {}
        )
    }

    @VisibleForTesting
    internal fun handoffAndAcknowledgeMissed(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootMissedRecord,
        recordedAtEpochMs: Long = System.currentTimeMillis(),
        unlockGate: (Context) -> Boolean,
        preCeCommitHook: () -> Unit = {},
        postCeCommitHook: () -> Unit = {},
        preAckCommitHook: () -> Unit = {},
        postAckCommitHook: () -> Unit = {}
    ): CeQualifiedAckResult {
        checkCePrerequisitesForAck(ceContext, unlockGate)?.let { return it }
        validateCandidateMissedForAck(candidateRecord, recordedAtEpochMs)?.let { return it }

        val expectedRecordId = DirectBootMissedRecord.computeRecordId(
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )

        synchronized(DirectBootStorageLock.lock) {
            synchronized(DirectBootCeTakeoverStore.ceStorageLock) {
                checkCePrerequisitesForAck(ceContext, unlockGate)?.let { return it }

                val snapshot = when (val s = inspectBothDeStores(deContext)) {
                    is DeStoresSnapshot.Failed -> return mapDeFailedToAckRejected(s.result)
                    is DeStoresSnapshot.Valid -> s
                }

                // Check 10: Opposite DE store mutual exclusion
                val conflictingDeDelivered = snapshot.deliveredRecords.firstOrNull {
                    it.alarmId == candidateRecord.alarmId &&
                        it.triggerTime == candidateRecord.triggerTime &&
                        it.generation == candidateRecord.generation &&
                        it.instanceToken == candidateRecord.instanceToken
                }
                if (conflictingDeDelivered != null) {
                    return CeQualifiedAckResult.Rejected.DeCrossStoreConflict(
                        deliveryRecord = conflictingDeDelivered,
                        missedRecord = candidateRecord,
                        details = "Mutual exclusion violation: occurrence exists in both DE DeliveryLedger and DE MissedStore"
                    )
                }

                val persistedDeRecord = snapshot.missedRecords.firstOrNull {
                    it.recordId == expectedRecordId ||
                        (it.alarmId == candidateRecord.alarmId &&
                            it.triggerTime == candidateRecord.triggerTime &&
                            it.generation == candidateRecord.generation &&
                            it.instanceToken == candidateRecord.instanceToken)
                }

                if (persistedDeRecord == null) {
                    val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate)
                    if (ceState is CeTakeoverStorageState.Absent || ceState is CeTakeoverStorageState.ValidEmpty) {
                        return CeQualifiedAckResult.Rejected.DeRecordNotFound(
                            "Missed record '$expectedRecordId' not present in DE MissedStore or CE Takeover Store"
                        )
                    }
                    if (ceState is CeTakeoverStorageState.ValidWithRecords) {
                        val expectedOccKey = DirectBootCeTakeoverStore.computeOccurrenceKey(
                            alarmId = candidateRecord.alarmId,
                            triggerTime = candidateRecord.triggerTime,
                            generation = candidateRecord.generation,
                            instanceToken = candidateRecord.instanceToken
                        )
                        val match = ceState.records.firstOrNull {
                            it.occurrenceKey == expectedOccKey || it.sourceRecordId == expectedRecordId
                        }
                        if (match == null) {
                            return CeQualifiedAckResult.Rejected.DeRecordNotFound(
                                "Missed record '$expectedRecordId' not present in DE MissedStore or CE Takeover Store"
                            )
                        }
                    }
                    return acknowledgeMissedInternal(
                        deContext = deContext,
                        ceContext = ceContext,
                        candidateRecord = candidateRecord,
                        confirmedReceipt = null,
                        allowIdempotentWhenDeAbsent = true,
                        unlockGate = unlockGate,
                        preAckCommitHook = preAckCommitHook,
                        postAckCommitHook = postAckCommitHook
                    )
                }

                if (persistedDeRecord != candidateRecord) {
                    return CeQualifiedAckResult.Rejected.DeRecordModifiedConflict(
                        "Candidate missed record differs from authoritative DE missed record: expected=$persistedDeRecord, candidate=$candidateRecord"
                    )
                }

                val intakeResult = DirectBootCeTakeoverStore.recordMissed(
                    ceContext = ceContext,
                    missedRecord = persistedDeRecord,
                    recordedAtEpochMs = recordedAtEpochMs,
                    unlockGate = unlockGate,
                    preCommitHook = preCeCommitHook,
                    postCommitHook = postCeCommitHook
                )

                val confirmedReceipt: CeHandoffResult.Confirmed = when (intakeResult) {
                    is CeTakeoverIntakeResult.RecordedConfirmed -> CeHandoffResult.Confirmed(
                        ceRecord = intakeResult.record,
                        totalCeRecords = intakeResult.totalRecords,
                        alreadyRecordedInCe = false,
                        writePerformed = true
                    )
                    is CeTakeoverIntakeResult.AlreadyRecordedConfirmed -> CeHandoffResult.Confirmed(
                        ceRecord = intakeResult.existingRecord,
                        totalCeRecords = intakeResult.totalRecords,
                        alreadyRecordedInCe = true,
                        writePerformed = false
                    )
                    is CeTakeoverIntakeResult.PostCommitFailure -> {
                        return CeQualifiedAckResult.Rejected.PreAckCommitFailure(
                            details = "Post-CE-commit hook failed prior to DE ACK commit: ${intakeResult.cause.javaClass.simpleName}: ${intakeResult.cause.message}",
                            cause = intakeResult.cause
                        )
                    }
                    is CeTakeoverIntakeResult.CancellationAfterConfirm -> throw intakeResult.cancellation
                    else -> return mapIntakeFailureToAckResult(intakeResult)
                }

                return acknowledgeMissedInternal(
                    deContext = deContext,
                    ceContext = ceContext,
                    candidateRecord = persistedDeRecord,
                    confirmedReceipt = confirmedReceipt,
                    allowIdempotentWhenDeAbsent = true,
                    unlockGate = unlockGate,
                    preAckCommitHook = preAckCommitHook,
                    postAckCommitHook = postAckCommitHook
                )
            }
        }
    }

    private fun validateCandidateDeliveryForAck(
        candidateRecord: DirectBootDeliveryRecord,
        recordedAtEpochMs: Long = 1L
    ): CeQualifiedAckResult.Rejected? {
        if (candidateRecord.kind == "QUICK") {
            return CeQualifiedAckResult.Rejected.QuickNotQualified(
                "QUICK delivery records are out of scope for qualified DE ACK"
            )
        }
        if (candidateRecord.kind != DirectBootDeliveryRecord.KIND_NORMAL ||
            candidateRecord.alarmId <= 0L ||
            candidateRecord.triggerTime <= 0L ||
            candidateRecord.deliveredAt <= 0L ||
            candidateRecord.generation < 1 ||
            candidateRecord.instanceToken.isBlank() ||
            (candidateRecord.schemaVersion != DirectBootDeliveryLedger.CURRENT_SCHEMA_VERSION && candidateRecord.schemaVersion != 1) ||
            recordedAtEpochMs <= 0L
        ) {
            return CeQualifiedAckResult.Rejected.InvalidRecordOrIdentity(
                "Invalid DirectBootDeliveryRecord fields: $candidateRecord"
            )
        }
        val expectedRecordId = DirectBootDeliveryRecord.computeRecordId(
            kind = candidateRecord.kind,
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )
        if (candidateRecord.recordId != expectedRecordId) {
            return CeQualifiedAckResult.Rejected.InvalidRecordOrIdentity(
                "Forged or mismatched delivery recordId: expected '$expectedRecordId', got '${candidateRecord.recordId}'"
            )
        }
        return null
    }

    private fun validateCandidateMissedForAck(
        candidateRecord: DirectBootMissedRecord,
        recordedAtEpochMs: Long = 1L
    ): CeQualifiedAckResult.Rejected? {
        if (candidateRecord.kind == "QUICK") {
            return CeQualifiedAckResult.Rejected.QuickNotQualified(
                "QUICK missed records are out of scope for qualified DE ACK"
            )
        }
        if (candidateRecord.kind != DirectBootMissedRecord.KIND_NORMAL ||
            candidateRecord.alarmId <= 0L ||
            candidateRecord.triggerTime <= 0L ||
            candidateRecord.missedAt <= 0L ||
            candidateRecord.generation < 1 ||
            candidateRecord.instanceToken.isBlank() ||
            candidateRecord.classification !in DirectBootMissedRecord.APPROVED_CLASSIFICATIONS ||
            (candidateRecord.schemaVersion != DirectBootMissedStore.CURRENT_SCHEMA_VERSION && candidateRecord.schemaVersion != 1) ||
            recordedAtEpochMs <= 0L
        ) {
            return CeQualifiedAckResult.Rejected.InvalidRecordOrIdentity(
                "Invalid DirectBootMissedRecord fields: $candidateRecord"
            )
        }
        val expectedRecordId = DirectBootMissedRecord.computeRecordId(
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )
        if (candidateRecord.recordId != expectedRecordId) {
            return CeQualifiedAckResult.Rejected.InvalidRecordOrIdentity(
                "Forged or mismatched missed recordId: expected '$expectedRecordId', got '${candidateRecord.recordId}'"
            )
        }
        return null
    }

    private fun acknowledgeDeliveredInternal(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootDeliveryRecord,
        confirmedReceipt: CeHandoffResult.Confirmed?,
        allowIdempotentWhenDeAbsent: Boolean,
        unlockGate: (Context) -> Boolean,
        preAckCommitHook: () -> Unit,
        postAckCommitHook: () -> Unit
    ): CeQualifiedAckResult {
        // Check 1 & Check 2 (outside lock fast-fail)
        checkCePrerequisitesForAck(ceContext, unlockGate)?.let { return it }
        validateCandidateDeliveryForAck(candidateRecord)?.let { return it }

        val expectedRecordId = DirectBootDeliveryRecord.computeRecordId(
            kind = candidateRecord.kind,
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )
        val expectedOccurrenceKey = DirectBootCeTakeoverStore.computeOccurrenceKey(
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )

        synchronized(DirectBootStorageLock.lock) {
            synchronized(DirectBootCeTakeoverStore.ceStorageLock) {
                // Check 1 & Check 2 live inside lock
                checkCePrerequisitesForAck(ceContext, unlockGate)?.let { return it }

                // Check 10: Both DE stores healthy + no opposite DE store conflict
                val snapshot = when (val s = inspectBothDeStores(deContext)) {
                    is DeStoresSnapshot.Failed -> return mapDeFailedToAckRejected(s.result)
                    is DeStoresSnapshot.Valid -> s
                }
                val conflictingDeMissed = snapshot.missedRecords.firstOrNull {
                    it.alarmId == candidateRecord.alarmId &&
                        it.triggerTime == candidateRecord.triggerTime &&
                        it.generation == candidateRecord.generation &&
                        it.instanceToken == candidateRecord.instanceToken
                }
                if (conflictingDeMissed != null) {
                    return CeQualifiedAckResult.Rejected.DeCrossStoreConflict(
                        deliveryRecord = candidateRecord,
                        missedRecord = conflictingDeMissed,
                        details = "Mutual exclusion violation: occurrence exists in both DE DeliveryLedger and DE MissedStore"
                    )
                }

                // Check 3 & Check 4: Live CE schema & record existence
                val ceRecords: List<CeTakeoverRecord> =
                    when (val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate)) {
                        is CeTakeoverStorageState.CeContextRejected ->
                            return CeQualifiedAckResult.Rejected.CeContextInvalid(ceState.reason)
                        is CeTakeoverStorageState.CeLocked ->
                            return CeQualifiedAckResult.Rejected.CeLocked(ceState.reason)
                        is CeTakeoverStorageState.Corrupt ->
                            return CeQualifiedAckResult.Rejected.CeStorageCorrupt(ceState.reason)
                        is CeTakeoverStorageState.UnsupportedSchema ->
                            return CeQualifiedAckResult.Rejected.CeUnsupportedSchema(
                                foundVersion = ceState.foundVersion,
                                expectedVersion = ceState.expectedVersion
                            )
                        is CeTakeoverStorageState.Absent,
                        is CeTakeoverStorageState.ValidEmpty ->
                            return CeQualifiedAckResult.Rejected.CeEvidenceMissing(
                                "CE Takeover Store contains no records for delivery '$expectedRecordId'"
                            )
                        is CeTakeoverStorageState.ValidWithRecords -> ceState.records
                    }

                // Validate caller-supplied receipt identity & type first if provided (Check 11)
                if (confirmedReceipt != null) {
                    val rCe = confirmedReceipt.ceRecord
                    if (rCe.occurrenceKey != expectedOccurrenceKey || rCe.sourceRecordId != expectedRecordId) {
                        return CeQualifiedAckResult.Rejected.ProofRecordMismatch(
                            "Confirmed receipt record (${rCe.occurrenceKey} / ${rCe.sourceRecordId}) does not match target delivery record ($expectedOccurrenceKey / $expectedRecordId)"
                        )
                    }
                    if (rCe.recordType != CeTakeoverRecord.TYPE_DELIVERED) {
                        return CeQualifiedAckResult.Rejected.CrossTypeConflict(
                            "Confirmed receipt recordType is '${rCe.recordType}', expected '${CeTakeoverRecord.TYPE_DELIVERED}'"
                        )
                    }
                }

                val liveCeRecord = ceRecords.firstOrNull {
                    it.occurrenceKey == expectedOccurrenceKey || it.sourceRecordId == expectedRecordId
                } ?: return CeQualifiedAckResult.Rejected.CeEvidenceMissing(
                    "Target occurrence '$expectedOccurrenceKey' ('$expectedRecordId') is missing from live CE Takeover Store"
                )

                // Check 7: Same Delivered/Missed type
                if (liveCeRecord !is CeTakeoverRecord.Delivered) {
                    return CeQualifiedAckResult.Rejected.CrossTypeConflict(
                        "Live CE record for '$expectedOccurrenceKey' has type '${liveCeRecord.recordType}', expected '${CeTakeoverRecord.TYPE_DELIVERED}'"
                    )
                }

                // Check 6 & Check 8: Same occurrence identity and sourceRecordId
                if (liveCeRecord.occurrenceKey != expectedOccurrenceKey ||
                    liveCeRecord.sourceRecordId != expectedRecordId ||
                    liveCeRecord.kind != candidateRecord.kind ||
                    liveCeRecord.alarmId != candidateRecord.alarmId ||
                    liveCeRecord.triggerTime != candidateRecord.triggerTime ||
                    liveCeRecord.generation != candidateRecord.generation ||
                    liveCeRecord.instanceToken != candidateRecord.instanceToken
                ) {
                    return CeQualifiedAckResult.Rejected.ProofRecordMismatch(
                        "Live CE record identity/sourceRecordId mismatch for '$expectedRecordId': live=$liveCeRecord"
                    )
                }

                // Check 9: Source payload complete match
                val expectedCeFromCandidate = CeTakeoverRecord.fromDeliveryRecord(
                    record = candidateRecord,
                    recordedAtEpochMs = liveCeRecord.recordedAtEpochMs,
                    overrideRestoreEpoch = liveCeRecord.restoreEpoch
                )
                if (!liveCeRecord.matchesSemanticContent(expectedCeFromCandidate)) {
                    return CeQualifiedAckResult.Rejected.CePayloadMismatch(
                        "Live CE Delivered record payload does not match candidate DE record: live=$liveCeRecord, expected=$expectedCeFromCandidate"
                    )
                }

                // Check 11: Stale proof rejection when receipt is provided
                if (confirmedReceipt != null) {
                    if (confirmedReceipt.ceRecord.recordedAtEpochMs != liveCeRecord.recordedAtEpochMs) {
                        return CeQualifiedAckResult.Rejected.StaleProofRejected(
                            receiptRecordedAtEpochMs = confirmedReceipt.ceRecord.recordedAtEpochMs,
                            liveRecordedAtEpochMs = liveCeRecord.recordedAtEpochMs,
                            details = "Stale proof receipt rejected: receipt recordedAt=${confirmedReceipt.ceRecord.recordedAtEpochMs}, live CE recordedAt=${liveCeRecord.recordedAtEpochMs}"
                        )
                    }
                    if (confirmedReceipt.ceRecord != liveCeRecord) {
                        return CeQualifiedAckResult.Rejected.CePayloadMismatch(
                            "Proof receipt differs from live CE record: receipt=${confirmedReceipt.ceRecord}, live=$liveCeRecord"
                        )
                    }
                }

                // Check 12: Live CE re-verification
                val ceReadbackCheck = DirectBootCeTakeoverStore.verifyRecordReadableInCe(
                    ceContext = ceContext,
                    expectedRecord = liveCeRecord,
                    unlockGate = unlockGate
                )
                val verifiedCeRecord = when (ceReadbackCheck) {
                    is CeReadbackVerificationResult.Verified -> ceReadbackCheck.record
                    is CeReadbackVerificationResult.Unverified ->
                        return CeQualifiedAckResult.Uncertain.CeReadbackFailed(
                            details = ceReadbackCheck.reason,
                            observedState = ceReadbackCheck.observedState
                        )
                }

                // Check 5: DE record existence & content match
                val currentDeRecords = snapshot.deliveredRecords
                val persistedDeRecord = currentDeRecords.firstOrNull {
                    it.recordId == expectedRecordId ||
                        (it.alarmId == candidateRecord.alarmId &&
                            it.triggerTime == candidateRecord.triggerTime &&
                            it.generation == candidateRecord.generation &&
                            it.instanceToken == candidateRecord.instanceToken)
                }

                if (persistedDeRecord == null) {
                    if (allowIdempotentWhenDeAbsent) {
                        return CeQualifiedAckResult.AlreadyAcknowledgedConfirmed(
                            retiredDeRecordId = expectedRecordId,
                            confirmedCeRecord = verifiedCeRecord,
                            remainingDeCount = currentDeRecords.size,
                            writePerformed = false
                        )
                    }
                    return CeQualifiedAckResult.Rejected.DeRecordNotFound(
                        "Delivery record '$expectedRecordId' is absent from DE DirectBootDeliveryLedger"
                    )
                }

                if (persistedDeRecord != candidateRecord) {
                    return CeQualifiedAckResult.Rejected.DeRecordModifiedConflict(
                        "Persisted DE delivery record was modified since handoff: persisted=$persistedDeRecord, candidate=$candidateRecord"
                    )
                }

                // Surgical single-record retirement
                val remainingDeRecords = currentDeRecords.filterNot { it.recordId == expectedRecordId }
                val priorDeCount = currentDeRecords.size
                val priorRawDePayload = DirectBootDeliveryLedger.getRawPayload(deContext)
                val intendedDeJson = DirectBootDeliveryLedger.serializeRecordsToJson(remainingDeRecords)

                // Stage 1: PRE_ACK_COMMIT
                try {
                    preAckCommitHook()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    return CeQualifiedAckResult.Rejected.PreAckCommitFailure(
                        details = "preAckCommitHook failed before DE ACK commit: ${t.javaClass.simpleName}: ${t.message}",
                        cause = t
                    )
                }

                // Stage 2: ACK_COMMIT_INVOKED
                val dePrefs = DirectBootDeliveryLedger.getDePrefs(deContext)
                val commitSuccess: Boolean = try {
                    DirectBootDeliveryLedger.commitSeam(dePrefs, intendedDeJson)
                } catch (t: Throwable) {
                    val obs = DirectBootDeliveryLedger.evaluateReadback(deContext, priorRawDePayload, intendedDeJson)
                    return CeQualifiedAckResult.Uncertain.DeCommitException(
                        details = "DE DeliveryLedger ACK commit threw ${t.javaClass.simpleName}: ${t.message}",
                        cause = t,
                        observation = obs,
                        priorDeCount = priorDeCount,
                        rawDePayloadAfter = DirectBootDeliveryLedger.getRawPayload(deContext)
                    )
                }

                if (!commitSuccess) {
                    val obs = DirectBootDeliveryLedger.evaluateReadback(deContext, priorRawDePayload, intendedDeJson)
                    return CeQualifiedAckResult.Uncertain.DeCommitFailed(
                        details = "DE DeliveryLedger ACK commit returned false",
                        observation = obs,
                        priorDeCount = priorDeCount,
                        rawDePayloadAfter = DirectBootDeliveryLedger.getRawPayload(deContext)
                    )
                }

                // Independent DE readback verification
                val deStateAfter = DirectBootDeliveryLedger.inspectStorageState(deContext)
                val deReadbackValid = when {
                    remainingDeRecords.isEmpty() -> deStateAfter is LedgerStorageState.ValidEmpty
                    else -> deStateAfter is LedgerStorageState.ValidWithRecords &&
                        deStateAfter.records == remainingDeRecords &&
                        deStateAfter.records.none { it.recordId == expectedRecordId }
                }
                if (!deReadbackValid) {
                    return CeQualifiedAckResult.Uncertain.DeReadbackFailed(
                        details = "Independent DE DeliveryLedger readback mismatch after ACK commit: state=$deStateAfter",
                        rawDePayloadAfter = DirectBootDeliveryLedger.getRawPayload(deContext)
                    )
                }

                // Stage 3: ACK_COMMIT_CONFIRMED
                try {
                    postAckCommitHook()
                } catch (e: CancellationException) {
                    return CeQualifiedAckResult.CancellationAfterAck(
                        retiredDeRecordId = expectedRecordId,
                        confirmedCeRecord = verifiedCeRecord,
                        remainingDeCount = remainingDeRecords.size,
                        cancellation = e
                    )
                } catch (t: Throwable) {
                    return CeQualifiedAckResult.PostAckFailure(
                        retiredDeRecordId = expectedRecordId,
                        confirmedCeRecord = verifiedCeRecord,
                        remainingDeCount = remainingDeRecords.size,
                        cause = t
                    )
                }

                return CeQualifiedAckResult.AcknowledgedConfirmed(
                    retiredDeRecordId = expectedRecordId,
                    confirmedCeRecord = verifiedCeRecord,
                    remainingDeCount = remainingDeRecords.size,
                    writePerformed = true
                )
            }
        }
    }

    private fun acknowledgeMissedInternal(
        deContext: Context,
        ceContext: Context,
        candidateRecord: DirectBootMissedRecord,
        confirmedReceipt: CeHandoffResult.Confirmed?,
        allowIdempotentWhenDeAbsent: Boolean,
        unlockGate: (Context) -> Boolean,
        preAckCommitHook: () -> Unit,
        postAckCommitHook: () -> Unit
    ): CeQualifiedAckResult {
        // Check 1 & Check 2 (outside lock fast-fail)
        checkCePrerequisitesForAck(ceContext, unlockGate)?.let { return it }
        validateCandidateMissedForAck(candidateRecord)?.let { return it }

        val expectedRecordId = DirectBootMissedRecord.computeRecordId(
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )
        val expectedOccurrenceKey = DirectBootCeTakeoverStore.computeOccurrenceKey(
            alarmId = candidateRecord.alarmId,
            triggerTime = candidateRecord.triggerTime,
            generation = candidateRecord.generation,
            instanceToken = candidateRecord.instanceToken
        )

        synchronized(DirectBootStorageLock.lock) {
            synchronized(DirectBootCeTakeoverStore.ceStorageLock) {
                // Check 1 & Check 2 live inside lock
                checkCePrerequisitesForAck(ceContext, unlockGate)?.let { return it }

                // Check 10: Both DE stores healthy + no opposite DE store conflict
                val snapshot = when (val s = inspectBothDeStores(deContext)) {
                    is DeStoresSnapshot.Failed -> return mapDeFailedToAckRejected(s.result)
                    is DeStoresSnapshot.Valid -> s
                }
                val conflictingDeDelivered = snapshot.deliveredRecords.firstOrNull {
                    it.alarmId == candidateRecord.alarmId &&
                        it.triggerTime == candidateRecord.triggerTime &&
                        it.generation == candidateRecord.generation &&
                        it.instanceToken == candidateRecord.instanceToken
                }
                if (conflictingDeDelivered != null) {
                    return CeQualifiedAckResult.Rejected.DeCrossStoreConflict(
                        deliveryRecord = conflictingDeDelivered,
                        missedRecord = candidateRecord,
                        details = "Mutual exclusion violation: occurrence exists in both DE DeliveryLedger and DE MissedStore"
                    )
                }

                // Check 3 & Check 4: Live CE schema & record existence
                val ceRecords: List<CeTakeoverRecord> =
                    when (val ceState = DirectBootCeTakeoverStore.inspectStorageState(ceContext, unlockGate)) {
                        is CeTakeoverStorageState.CeContextRejected ->
                            return CeQualifiedAckResult.Rejected.CeContextInvalid(ceState.reason)
                        is CeTakeoverStorageState.CeLocked ->
                            return CeQualifiedAckResult.Rejected.CeLocked(ceState.reason)
                        is CeTakeoverStorageState.Corrupt ->
                            return CeQualifiedAckResult.Rejected.CeStorageCorrupt(ceState.reason)
                        is CeTakeoverStorageState.UnsupportedSchema ->
                            return CeQualifiedAckResult.Rejected.CeUnsupportedSchema(
                                foundVersion = ceState.foundVersion,
                                expectedVersion = ceState.expectedVersion
                            )
                        is CeTakeoverStorageState.Absent,
                        is CeTakeoverStorageState.ValidEmpty ->
                            return CeQualifiedAckResult.Rejected.CeEvidenceMissing(
                                "CE Takeover Store contains no records for missed '$expectedRecordId'"
                            )
                        is CeTakeoverStorageState.ValidWithRecords -> ceState.records
                    }

                // Validate caller-supplied receipt identity & type first if provided (Check 11)
                if (confirmedReceipt != null) {
                    val rCe = confirmedReceipt.ceRecord
                    if (rCe.occurrenceKey != expectedOccurrenceKey || rCe.sourceRecordId != expectedRecordId) {
                        return CeQualifiedAckResult.Rejected.ProofRecordMismatch(
                            "Confirmed receipt record (${rCe.occurrenceKey} / ${rCe.sourceRecordId}) does not match target missed record ($expectedOccurrenceKey / $expectedRecordId)"
                        )
                    }
                    if (rCe.recordType != CeTakeoverRecord.TYPE_MISSED) {
                        return CeQualifiedAckResult.Rejected.CrossTypeConflict(
                            "Confirmed receipt recordType is '${rCe.recordType}', expected '${CeTakeoverRecord.TYPE_MISSED}'"
                        )
                    }
                }

                val liveCeRecord = ceRecords.firstOrNull {
                    it.occurrenceKey == expectedOccurrenceKey || it.sourceRecordId == expectedRecordId
                } ?: return CeQualifiedAckResult.Rejected.CeEvidenceMissing(
                    "Target occurrence '$expectedOccurrenceKey' ('$expectedRecordId') is missing from live CE Takeover Store"
                )

                // Check 7: Same Delivered/Missed type
                if (liveCeRecord !is CeTakeoverRecord.Missed) {
                    return CeQualifiedAckResult.Rejected.CrossTypeConflict(
                        "Live CE record for '$expectedOccurrenceKey' has type '${liveCeRecord.recordType}', expected '${CeTakeoverRecord.TYPE_MISSED}'"
                    )
                }

                // Check 6 & Check 8: Same occurrence identity and sourceRecordId
                if (liveCeRecord.occurrenceKey != expectedOccurrenceKey ||
                    liveCeRecord.sourceRecordId != expectedRecordId ||
                    liveCeRecord.kind != candidateRecord.kind ||
                    liveCeRecord.alarmId != candidateRecord.alarmId ||
                    liveCeRecord.triggerTime != candidateRecord.triggerTime ||
                    liveCeRecord.generation != candidateRecord.generation ||
                    liveCeRecord.instanceToken != candidateRecord.instanceToken
                ) {
                    return CeQualifiedAckResult.Rejected.ProofRecordMismatch(
                        "Live CE record identity/sourceRecordId mismatch for '$expectedRecordId': live=$liveCeRecord"
                    )
                }

                // Check 9: Source payload complete match
                val expectedCeFromCandidate = CeTakeoverRecord.fromMissedRecord(
                    record = candidateRecord,
                    recordedAtEpochMs = liveCeRecord.recordedAtEpochMs,
                    overrideRestoreEpoch = liveCeRecord.restoreEpoch
                )
                if (!liveCeRecord.matchesSemanticContent(expectedCeFromCandidate)) {
                    return CeQualifiedAckResult.Rejected.CePayloadMismatch(
                        "Live CE Missed record payload does not match candidate DE record: live=$liveCeRecord, expected=$expectedCeFromCandidate"
                    )
                }

                // Check 11: Stale proof rejection when receipt is provided
                if (confirmedReceipt != null) {
                    if (confirmedReceipt.ceRecord.recordedAtEpochMs != liveCeRecord.recordedAtEpochMs) {
                        return CeQualifiedAckResult.Rejected.StaleProofRejected(
                            receiptRecordedAtEpochMs = confirmedReceipt.ceRecord.recordedAtEpochMs,
                            liveRecordedAtEpochMs = liveCeRecord.recordedAtEpochMs,
                            details = "Stale proof receipt rejected: receipt recordedAt=${confirmedReceipt.ceRecord.recordedAtEpochMs}, live CE recordedAt=${liveCeRecord.recordedAtEpochMs}"
                        )
                    }
                    if (confirmedReceipt.ceRecord != liveCeRecord) {
                        return CeQualifiedAckResult.Rejected.CePayloadMismatch(
                            "Proof receipt differs from live CE record: receipt=${confirmedReceipt.ceRecord}, live=$liveCeRecord"
                        )
                    }
                }

                // Check 12: Live CE re-verification
                val ceReadbackCheck = DirectBootCeTakeoverStore.verifyRecordReadableInCe(
                    ceContext = ceContext,
                    expectedRecord = liveCeRecord,
                    unlockGate = unlockGate
                )
                val verifiedCeRecord = when (ceReadbackCheck) {
                    is CeReadbackVerificationResult.Verified -> ceReadbackCheck.record
                    is CeReadbackVerificationResult.Unverified ->
                        return CeQualifiedAckResult.Uncertain.CeReadbackFailed(
                            details = ceReadbackCheck.reason,
                            observedState = ceReadbackCheck.observedState
                        )
                }

                // Check 5: DE record existence & content match
                val currentDeRecords = snapshot.missedRecords
                val persistedDeRecord = currentDeRecords.firstOrNull {
                    it.recordId == expectedRecordId ||
                        (it.alarmId == candidateRecord.alarmId &&
                            it.triggerTime == candidateRecord.triggerTime &&
                            it.generation == candidateRecord.generation &&
                            it.instanceToken == candidateRecord.instanceToken)
                }

                if (persistedDeRecord == null) {
                    if (allowIdempotentWhenDeAbsent) {
                        return CeQualifiedAckResult.AlreadyAcknowledgedConfirmed(
                            retiredDeRecordId = expectedRecordId,
                            confirmedCeRecord = verifiedCeRecord,
                            remainingDeCount = currentDeRecords.size,
                            writePerformed = false
                        )
                    }
                    return CeQualifiedAckResult.Rejected.DeRecordNotFound(
                        "Missed record '$expectedRecordId' is absent from DE DirectBootMissedStore"
                    )
                }

                if (persistedDeRecord != candidateRecord) {
                    return CeQualifiedAckResult.Rejected.DeRecordModifiedConflict(
                        "Persisted DE missed record was modified since handoff: persisted=$persistedDeRecord, candidate=$candidateRecord"
                    )
                }

                // Surgical single-record retirement
                val remainingDeRecords = currentDeRecords.filterNot { it.recordId == expectedRecordId }
                val priorDeCount = currentDeRecords.size
                val priorRawDePayload = DirectBootMissedStore.getRawPayload(deContext)
                val intendedDeJson = DirectBootMissedStore.serializeRecordsToJson(remainingDeRecords)

                // Stage 1: PRE_ACK_COMMIT
                try {
                    preAckCommitHook()
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    return CeQualifiedAckResult.Rejected.PreAckCommitFailure(
                        details = "preAckCommitHook failed before DE ACK commit: ${t.javaClass.simpleName}: ${t.message}",
                        cause = t
                    )
                }

                // Stage 2: ACK_COMMIT_INVOKED
                val dePrefs = DirectBootMissedStore.getDePrefs(deContext)
                val outcome = try {
                    DirectBootMissedStore.commitSeam(
                        dePrefs.edit().putString(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD, intendedDeJson)
                    )
                } catch (t: Throwable) {
                    CommitExecutionOutcome.Threw(t)
                }

                when (outcome) {
                    is CommitExecutionOutcome.Success -> Unit
                    is CommitExecutionOutcome.CommitFalse -> {
                        val obs = DirectBootMissedStore.evaluateReadback(deContext, priorRawDePayload, intendedDeJson)
                        return CeQualifiedAckResult.Uncertain.DeCommitFailed(
                            details = "DE MissedStore ACK commit returned false",
                            observation = obs,
                            priorDeCount = priorDeCount,
                            rawDePayloadAfter = DirectBootMissedStore.getRawPayload(deContext)
                        )
                    }
                    is CommitExecutionOutcome.Threw -> {
                        val obs = DirectBootMissedStore.evaluateReadback(deContext, priorRawDePayload, intendedDeJson)
                        return CeQualifiedAckResult.Uncertain.DeCommitException(
                            details = "DE MissedStore ACK commit threw ${outcome.throwable.javaClass.simpleName}: ${outcome.throwable.message}",
                            cause = outcome.throwable,
                            observation = obs,
                            priorDeCount = priorDeCount,
                            rawDePayloadAfter = DirectBootMissedStore.getRawPayload(deContext)
                        )
                    }
                }

                // Independent DE readback verification
                val deStateAfter = DirectBootMissedStore.inspectStorageState(deContext)
                val deReadbackValid = when {
                    remainingDeRecords.isEmpty() -> deStateAfter is MissedStoreStorageState.ValidEmpty
                    else -> deStateAfter is MissedStoreStorageState.ValidWithRecords &&
                        deStateAfter.records == remainingDeRecords &&
                        deStateAfter.records.none { it.recordId == expectedRecordId }
                }
                if (!deReadbackValid) {
                    return CeQualifiedAckResult.Uncertain.DeReadbackFailed(
                        details = "Independent DE MissedStore readback mismatch after ACK commit: state=$deStateAfter",
                        rawDePayloadAfter = DirectBootMissedStore.getRawPayload(deContext)
                    )
                }

                // Stage 3: ACK_COMMIT_CONFIRMED
                try {
                    postAckCommitHook()
                } catch (e: CancellationException) {
                    return CeQualifiedAckResult.CancellationAfterAck(
                        retiredDeRecordId = expectedRecordId,
                        confirmedCeRecord = verifiedCeRecord,
                        remainingDeCount = remainingDeRecords.size,
                        cancellation = e
                    )
                } catch (t: Throwable) {
                    return CeQualifiedAckResult.PostAckFailure(
                        retiredDeRecordId = expectedRecordId,
                        confirmedCeRecord = verifiedCeRecord,
                        remainingDeCount = remainingDeRecords.size,
                        cause = t
                    )
                }

                return CeQualifiedAckResult.AcknowledgedConfirmed(
                    retiredDeRecordId = expectedRecordId,
                    confirmedCeRecord = verifiedCeRecord,
                    remainingDeCount = remainingDeRecords.size,
                    writePerformed = true
                )
            }
        }
    }

    private fun mapIntakeFailureToAckResult(intakeResult: CeTakeoverIntakeResult): CeQualifiedAckResult {
        return when (intakeResult) {
            is CeTakeoverIntakeResult.Rejected.CeLocked ->
                CeQualifiedAckResult.Rejected.CeLocked(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.CeContextRejected ->
                CeQualifiedAckResult.Rejected.CeContextInvalid(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.QuickNotQualified ->
                CeQualifiedAckResult.Rejected.QuickNotQualified(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.InvalidRecord ->
                CeQualifiedAckResult.Rejected.InvalidRecordOrIdentity(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.StorageCorrupt ->
                CeQualifiedAckResult.Rejected.CeStorageCorrupt(intakeResult.reason)
            is CeTakeoverIntakeResult.Rejected.UnsupportedSchema ->
                CeQualifiedAckResult.Rejected.CeUnsupportedSchema(
                    foundVersion = intakeResult.foundVersion,
                    expectedVersion = intakeResult.expectedVersion
                )
            is CeTakeoverIntakeResult.Rejected.ContentConflict ->
                CeQualifiedAckResult.Rejected.CePayloadMismatch(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.DeliveredMissedConflict ->
                CeQualifiedAckResult.Rejected.CrossTypeConflict(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.CapacityBlocked ->
                CeQualifiedAckResult.Rejected.CeCapacityBlocked(
                    currentCount = intakeResult.currentCount,
                    provisionalMaxCapacity = intakeResult.provisionalMaxCapacity
                )
            is CeTakeoverIntakeResult.Rejected.PreCommitFailure ->
                CeQualifiedAckResult.Rejected.PreCeCommitFailure(
                    details = intakeResult.details,
                    cause = intakeResult.cause
                )
            is CeTakeoverIntakeResult.Uncertain.CommitFailed ->
                CeQualifiedAckResult.Uncertain.CeCommitFailed(intakeResult.details)
            is CeTakeoverIntakeResult.Uncertain.CommitException ->
                CeQualifiedAckResult.Uncertain.CeCommitException(intakeResult.details, intakeResult.cause)
            is CeTakeoverIntakeResult.Uncertain.ReadbackMismatch ->
                CeQualifiedAckResult.Uncertain.CeReadbackFailed(
                    details = intakeResult.details,
                    observedState = intakeResult.observedState
                )
            else -> CeQualifiedAckResult.Rejected.InvalidRecordOrIdentity("Unexpected intake result: $intakeResult")
        }
    }

    private fun mapIntakeResult(intakeResult: CeTakeoverIntakeResult): CeHandoffResult {
        return when (intakeResult) {
            is CeTakeoverIntakeResult.RecordedConfirmed -> CeHandoffResult.Confirmed(
                ceRecord = intakeResult.record,
                totalCeRecords = intakeResult.totalRecords,
                alreadyRecordedInCe = false,
                writePerformed = true
            )
            is CeTakeoverIntakeResult.AlreadyRecordedConfirmed -> CeHandoffResult.Confirmed(
                ceRecord = intakeResult.existingRecord,
                totalCeRecords = intakeResult.totalRecords,
                alreadyRecordedInCe = true,
                writePerformed = false
            )
            is CeTakeoverIntakeResult.PostCommitFailure -> CeHandoffResult.PostCommitFailure(
                confirmedCeRecord = intakeResult.confirmedRecord,
                totalCeRecords = intakeResult.totalRecords,
                cause = intakeResult.cause
            )
            is CeTakeoverIntakeResult.CancellationAfterConfirm -> CeHandoffResult.CancellationAfterConfirm(
                confirmedCeRecord = intakeResult.confirmedRecord,
                totalCeRecords = intakeResult.totalRecords,
                cancellation = intakeResult.cancellation
            )
            is CeTakeoverIntakeResult.Rejected.CeLocked ->
                CeHandoffResult.Rejected.CeLocked(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.CeContextRejected ->
                CeHandoffResult.Rejected.CeContextInvalid(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.QuickNotQualified ->
                CeHandoffResult.Rejected.QuickNotQualified(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.InvalidRecord ->
                CeHandoffResult.Rejected.InvalidRecordOrIdentity(intakeResult.details)
            is CeTakeoverIntakeResult.Rejected.StorageCorrupt ->
                CeHandoffResult.Rejected.CeStorageCorrupt(intakeResult.reason)
            is CeTakeoverIntakeResult.Rejected.UnsupportedSchema ->
                CeHandoffResult.Rejected.CeUnsupportedSchema(
                    foundVersion = intakeResult.foundVersion,
                    expectedVersion = intakeResult.expectedVersion
                )
            is CeTakeoverIntakeResult.Rejected.ContentConflict ->
                CeHandoffResult.Rejected.CeContentConflict(
                    existingRecord = intakeResult.existingRecord,
                    incomingRecord = intakeResult.incomingRecord,
                    details = intakeResult.details
                )
            is CeTakeoverIntakeResult.Rejected.DeliveredMissedConflict ->
                CeHandoffResult.Rejected.CeDeliveredMissedConflict(
                    existingRecord = intakeResult.existingRecord,
                    incomingRecord = intakeResult.incomingRecord,
                    details = intakeResult.details
                )
            is CeTakeoverIntakeResult.Rejected.CapacityBlocked ->
                CeHandoffResult.Rejected.CeCapacityBlocked(
                    currentCount = intakeResult.currentCount,
                    provisionalMaxCapacity = intakeResult.provisionalMaxCapacity
                )
            is CeTakeoverIntakeResult.Rejected.PreCommitFailure ->
                CeHandoffResult.Rejected.PreCommitFailure(
                    details = intakeResult.details,
                    cause = intakeResult.cause
                )
            is CeTakeoverIntakeResult.Uncertain.CommitFailed ->
                CeHandoffResult.Uncertain.CeCommitFailed(intakeResult.details)
            is CeTakeoverIntakeResult.Uncertain.CommitException ->
                CeHandoffResult.Uncertain.CeCommitException(intakeResult.details, intakeResult.cause)
            is CeTakeoverIntakeResult.Uncertain.ReadbackMismatch ->
                CeHandoffResult.Uncertain.CeReadbackFailed(
                    details = intakeResult.details,
                    observedState = intakeResult.observedState
                )
        }
    }
}

sealed class CeHandoffResult {
    abstract val isGuaranteedNoCeWrite: Boolean
    abstract val ceWriteAttempted: Boolean
    val deRecordPreserved: Boolean get() = true
    val deAckEligible: Boolean get() = false

    data class Confirmed(
        val ceRecord: CeTakeoverRecord,
        val totalCeRecords: Int,
        val alreadyRecordedInCe: Boolean,
        val writePerformed: Boolean
    ) : CeHandoffResult() {
        override val isGuaranteedNoCeWrite: Boolean get() = !writePerformed
        override val ceWriteAttempted: Boolean get() = writePerformed
    }

    data class PostCommitFailure(
        val confirmedCeRecord: CeTakeoverRecord,
        val totalCeRecords: Int,
        val cause: Throwable
    ) : CeHandoffResult() {
        override val isGuaranteedNoCeWrite: Boolean get() = false
        override val ceWriteAttempted: Boolean get() = true
    }

    data class CancellationAfterConfirm(
        val confirmedCeRecord: CeTakeoverRecord,
        val totalCeRecords: Int,
        val cancellation: CancellationException
    ) : CeHandoffResult() {
        override val isGuaranteedNoCeWrite: Boolean get() = false
        override val ceWriteAttempted: Boolean get() = true
    }

    sealed class Rejected : CeHandoffResult() {
        override val isGuaranteedNoCeWrite: Boolean get() = true
        override val ceWriteAttempted: Boolean get() = false

        data class CeLocked(val details: String) : Rejected()
        data class CeContextInvalid(val details: String) : Rejected()
        data class QuickNotQualified(val details: String) : Rejected()
        data class InvalidRecordOrIdentity(val details: String) : Rejected()
        data class DeStorageCorrupt(val storeDomain: String, val reason: String) : Rejected()
        data class DeUnsupportedSchema(val storeDomain: String, val foundVersion: Int, val expectedVersion: Int) : Rejected()
        data class DeStorageUncertain(val storeDomain: String, val reason: String) : Rejected()
        data class DeCrossStoreConflict(
            val deliveryRecord: DirectBootDeliveryRecord,
            val missedRecord: DirectBootMissedRecord,
            val details: String
        ) : Rejected()
        data class DeRecordNotFound(val details: String) : Rejected()
        data class DeRecordContentMismatch(val details: String) : Rejected()
        data class CeStorageCorrupt(val reason: String) : Rejected()
        data class CeUnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : Rejected()
        data class CeContentConflict(
            val existingRecord: CeTakeoverRecord,
            val incomingRecord: CeTakeoverRecord,
            val details: String
        ) : Rejected()
        data class CeDeliveredMissedConflict(
            val existingRecord: CeTakeoverRecord,
            val incomingRecord: CeTakeoverRecord,
            val details: String
        ) : Rejected()
        data class CeCapacityBlocked(
            val currentCount: Int,
            val provisionalMaxCapacity: Int = DirectBootCeTakeoverStore.PROVISIONAL_MAX_CAPACITY
        ) : Rejected()
        data class PreCommitFailure(val details: String, val cause: Throwable) : Rejected()
    }

    sealed class Uncertain : CeHandoffResult() {
        override val isGuaranteedNoCeWrite: Boolean get() = false
        override val ceWriteAttempted: Boolean get() = true
        abstract val details: String

        data class CeCommitFailed(override val details: String) : Uncertain()
        data class CeCommitException(override val details: String, val cause: Throwable) : Uncertain()
        data class CeReadbackFailed(
            override val details: String,
            val observedState: CeTakeoverStorageState? = null
        ) : Uncertain()
    }
}

sealed class CeQualifiedAckResult {
    abstract val isGuaranteedNoDeWrite: Boolean
    abstract val deWriteAttempted: Boolean
    abstract val deRecordRetired: Boolean

    data class AcknowledgedConfirmed(
        val retiredDeRecordId: String,
        val confirmedCeRecord: CeTakeoverRecord,
        val remainingDeCount: Int,
        val writePerformed: Boolean = true
    ) : CeQualifiedAckResult() {
        override val isGuaranteedNoDeWrite: Boolean get() = !writePerformed
        override val deWriteAttempted: Boolean get() = writePerformed
        override val deRecordRetired: Boolean get() = true
    }

    data class AlreadyAcknowledgedConfirmed(
        val retiredDeRecordId: String,
        val confirmedCeRecord: CeTakeoverRecord,
        val remainingDeCount: Int,
        val writePerformed: Boolean = false
    ) : CeQualifiedAckResult() {
        override val isGuaranteedNoDeWrite: Boolean get() = true
        override val deWriteAttempted: Boolean get() = false
        override val deRecordRetired: Boolean get() = true
    }

    data class PostAckFailure(
        val retiredDeRecordId: String,
        val confirmedCeRecord: CeTakeoverRecord,
        val remainingDeCount: Int,
        val cause: Throwable
    ) : CeQualifiedAckResult() {
        override val isGuaranteedNoDeWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
        override val deRecordRetired: Boolean get() = true
    }

    data class CancellationAfterAck(
        val retiredDeRecordId: String,
        val confirmedCeRecord: CeTakeoverRecord,
        val remainingDeCount: Int,
        val cancellation: CancellationException
    ) : CeQualifiedAckResult() {
        override val isGuaranteedNoDeWrite: Boolean get() = false
        override val deWriteAttempted: Boolean get() = true
        override val deRecordRetired: Boolean get() = true
    }

    sealed class Rejected : CeQualifiedAckResult() {
        override val isGuaranteedNoDeWrite: Boolean get() = true
        override val deWriteAttempted: Boolean get() = false
        override val deRecordRetired: Boolean get() = false

        data class CeLocked(val details: String) : Rejected()
        data class CeContextInvalid(val details: String) : Rejected()
        data class QuickNotQualified(val details: String) : Rejected()
        data class InvalidRecordOrIdentity(val details: String) : Rejected()
        data class CeStorageCorrupt(val reason: String) : Rejected()
        data class CeUnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : Rejected()
        data class CeEvidenceMissing(val details: String) : Rejected()
        data class CePayloadMismatch(val details: String) : Rejected()
        data class StaleProofRejected(
            val receiptRecordedAtEpochMs: Long,
            val liveRecordedAtEpochMs: Long,
            val details: String
        ) : Rejected()
        data class ProofRecordMismatch(val details: String) : Rejected()
        data class CrossTypeConflict(val details: String) : Rejected()
        data class DeStorageCorrupt(val storeDomain: String, val reason: String) : Rejected()
        data class DeUnsupportedSchema(val storeDomain: String, val foundVersion: Int, val expectedVersion: Int) : Rejected()
        data class DeStorageUncertain(val storeDomain: String, val reason: String) : Rejected()
        data class DeCrossStoreConflict(
            val deliveryRecord: DirectBootDeliveryRecord,
            val missedRecord: DirectBootMissedRecord,
            val details: String
        ) : Rejected()
        data class DeRecordModifiedConflict(val details: String) : Rejected()
        data class DeRecordNotFound(val details: String) : Rejected()
        data class CeCapacityBlocked(
            val currentCount: Int,
            val provisionalMaxCapacity: Int = DirectBootCeTakeoverStore.PROVISIONAL_MAX_CAPACITY
        ) : Rejected()
        data class PreCeCommitFailure(val details: String, val cause: Throwable) : Rejected()
        data class PreAckCommitFailure(val details: String, val cause: Throwable) : Rejected()
    }

    sealed class Uncertain : CeQualifiedAckResult() {
        override val isGuaranteedNoDeWrite: Boolean get() = false
        override val deRecordRetired: Boolean get() = false
        abstract val details: String

        data class CeCommitFailed(override val details: String) : Uncertain() {
            override val deWriteAttempted: Boolean get() = false
            override val isGuaranteedNoDeWrite: Boolean get() = true
        }
        data class CeCommitException(override val details: String, val cause: Throwable) : Uncertain() {
            override val deWriteAttempted: Boolean get() = false
            override val isGuaranteedNoDeWrite: Boolean get() = true
        }
        data class CeReadbackFailed(
            override val details: String,
            val observedState: CeTakeoverStorageState? = null
        ) : Uncertain() {
            override val deWriteAttempted: Boolean get() = false
            override val isGuaranteedNoDeWrite: Boolean get() = true
        }
        data class DeCommitFailed(
            override val details: String,
            val observation: CommitReadbackObservation,
            val priorDeCount: Int,
            val rawDePayloadAfter: String?
        ) : Uncertain() {
            override val deWriteAttempted: Boolean get() = true
        }
        data class DeCommitException(
            override val details: String,
            val cause: Throwable,
            val observation: CommitReadbackObservation,
            val priorDeCount: Int,
            val rawDePayloadAfter: String?
        ) : Uncertain() {
            override val deWriteAttempted: Boolean get() = true
        }
        data class DeReadbackFailed(
            override val details: String,
            val rawDePayloadAfter: String?
        ) : Uncertain() {
            override val deWriteAttempted: Boolean get() = true
        }
    }
}
