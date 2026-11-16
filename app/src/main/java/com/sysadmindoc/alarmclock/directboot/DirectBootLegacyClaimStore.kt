package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import org.json.JSONObject

/**
 * Isolated Device-Encrypted (DE) Legacy Claim Store for KNO-75 C7-B1c S2.
 *
 * Responsibilities & Invariants:
 * 1. Maximum capacity bounded at 32 unresolved records ([MAX_CAPACITY]). Rejects 33rd unresolved claim fail-closed.
 * 2. Typed State and strictly monotonic state transitions:
 *    CLAIMED_RECEIVER -> START_REQUESTED -> SERVICE_ACCEPTED -> OUTPUT_CLAIMING -> OUTPUT_OBSERVED -> TERMINAL.
 *    Any regression or terminal reopen is rejected fail-closed.
 * 3. UNKNOWN state is pinned until explicit durable proof from CE resolves it.
 * 4. Corrupt and UnsupportedSchema payloads fail closed without clearing or modifying raw payload on disk.
 * 5. Commit false, exceptions, and readback mismatches are classified with typed persistence failure.
 * 6. Duplicate occurrence claims are rejected idempotently.
 * 7. Isolated migration contract for legacy delivery records from the old 16-slot DeliveryLedger.
 * 8. Rejects cross-lane duplicate claims and ineligible claim fields.
 * 9. Binds process and boot provenance to each claim state advancement.
 * 10. Elapsed time alone never authorizes deletion or retirement of unresolved claims.
 *
 * All mutations are synchronized under [DirectBootStorageLock.lock] and persist synchronously via
 * [SharedPreferences.Editor.commit].
 */

enum class LegacyClaimState {
    CLAIMED_RECEIVER,
    START_REQUESTED,
    SERVICE_ACCEPTED,
    OUTPUT_CLAIMING,
    OUTPUT_OBSERVED,
    TERMINAL,
    UNKNOWN
}

enum class LegacyTerminalOutcome {
    CONSUMED_DISMISSED,
    CONSUMED_SNOOZED,
    CONSUMED_AUTO_SILENCED,
    CONSUMED_PREEMPTED,
    DUPLICATE_ABORTED,
    REJECTED_NO_OUTPUT,
    ABORTED_PRE_IPC,
    RECONCILED_CE,
    MIGRATED_RETIRED
}

enum class LegacyClaimSource {
    ALARM_RECEIVER,
    DIRECT_BOOT_RECEIVER,
    FIRE_WATCHDOG,
    SMART_ALARM,
    MISSED_UNLOCK,
    WAKE_CONFIRM,
    LEGACY_LEDGER_MIGRATION
}

data class OutputOccurrenceKey(
    val alarmId: Long,
    val scheduledAt: Long,
    val refireCount: Int = 0
)

data class WakeConfirmLineage(
    val parentDismissed: Boolean,
    val wakeConfirmEnabled: Boolean,
    val nextAuthorizedRefire: Int,
    val highestClaimedRefire: Int,
    val confirmed: Boolean,
    val stepToken: String,
    val oneShotDisabledAllowed: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("parentDismissed", parentDismissed)
        put("wakeConfirmEnabled", wakeConfirmEnabled)
        put("nextAuthorizedRefire", nextAuthorizedRefire)
        put("highestClaimedRefire", highestClaimedRefire)
        put("confirmed", confirmed)
        put("stepToken", stepToken)
        put("oneShotDisabledAllowed", oneShotDisabledAllowed)
    }

    companion object {
        fun fromJson(json: JSONObject): WakeConfirmLineage = WakeConfirmLineage(
            parentDismissed = json.optBoolean("parentDismissed", false),
            wakeConfirmEnabled = json.optBoolean("wakeConfirmEnabled", false),
            nextAuthorizedRefire = json.optInt("nextAuthorizedRefire", 0),
            highestClaimedRefire = json.optInt("highestClaimedRefire", 0),
            confirmed = json.optBoolean("confirmed", false),
            stepToken = json.optString("stepToken", ""),
            oneShotDisabledAllowed = json.optBoolean("oneShotDisabledAllowed", true)
        )
    }
}

data class LegacyClaimRecord(
    val alarmId: Long,
    val scheduledAt: Long,
    val refireCount: Int,
    val source: LegacyClaimSource,
    val claimNonce: String,
    val processSessionToken: String,
    val bootCount: Long,
    val state: LegacyClaimState,
    val terminalOutcome: LegacyTerminalOutcome? = null,
    val wakeConfirm: WakeConfirmLineage? = null,
    val migratedFromLedgerRecordId: String? = null
) {
    val key: OutputOccurrenceKey get() = OutputOccurrenceKey(alarmId, scheduledAt, refireCount)

    fun validate(): String? = when {
        alarmId <= 0L -> "alarmId must be positive: $alarmId"
        scheduledAt <= 0L -> "scheduledAt must be positive: $scheduledAt"
        refireCount !in 0..3 -> "refireCount must be in 0..3: $refireCount"
        claimNonce.isBlank() -> "claimNonce must not be blank"
        processSessionToken.isBlank() -> "processSessionToken must not be blank"
        bootCount < 0L -> "bootCount must be >= 0: $bootCount"
        state == LegacyClaimState.TERMINAL && terminalOutcome == null -> "TERMINAL requires terminalOutcome"
        state != LegacyClaimState.TERMINAL && terminalOutcome != null -> "Non-TERMINAL must not have terminalOutcome"
        else -> null
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("alarmId", alarmId)
        put("scheduledAt", scheduledAt)
        put("refireCount", refireCount)
        put("source", source.name)
        put("claimNonce", claimNonce)
        put("processSessionToken", processSessionToken)
        put("bootCount", bootCount)
        put("state", state.name)
        put("terminalOutcome", terminalOutcome?.name ?: JSONObject.NULL)
        put("wakeConfirm", wakeConfirm?.toJson() ?: JSONObject.NULL)
        put("migratedFromLedgerRecordId", migratedFromLedgerRecordId ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): LegacyClaimRecord {
            val sourceStr = json.getString("source")
            val stateStr = json.getString("state")
            val terminalOutcomeStr = if (json.has("terminalOutcome") && !json.isNull("terminalOutcome")) {
                json.getString("terminalOutcome")
            } else null
            val migratedFromLedgerRecordIdStr = if (json.has("migratedFromLedgerRecordId") && !json.isNull("migratedFromLedgerRecordId")) {
                json.getString("migratedFromLedgerRecordId")
            } else null
            val wakeConfirmLineage = if (json.has("wakeConfirm") && !json.isNull("wakeConfirm")) {
                WakeConfirmLineage.fromJson(json.getJSONObject("wakeConfirm"))
            } else null

            return LegacyClaimRecord(
                alarmId = json.getLong("alarmId"),
                scheduledAt = json.getLong("scheduledAt"),
                refireCount = json.getInt("refireCount"),
                source = LegacyClaimSource.valueOf(sourceStr),
                claimNonce = json.getString("claimNonce"),
                processSessionToken = json.getString("processSessionToken"),
                bootCount = json.getLong("bootCount"),
                state = LegacyClaimState.valueOf(stateStr),
                terminalOutcome = terminalOutcomeStr?.let { LegacyTerminalOutcome.valueOf(it) },
                wakeConfirm = wakeConfirmLineage,
                migratedFromLedgerRecordId = migratedFromLedgerRecordIdStr
            )
        }
    }
}

sealed class LegacyClaimStorageState {
    object Absent : LegacyClaimStorageState()
    object ValidEmpty : LegacyClaimStorageState()
    data class ValidWithRecords(val records: List<LegacyClaimRecord>) : LegacyClaimStorageState()
    data class Corrupt(val reason: String) : LegacyClaimStorageState()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : LegacyClaimStorageState()
}

sealed class LegacyClaimWriteResult {
    data class Committed(val record: LegacyClaimRecord, val activeCount: Int) : LegacyClaimWriteResult()
    data class RejectedInvalidTransition(val from: LegacyClaimState?, val to: LegacyClaimState, val reason: String) : LegacyClaimWriteResult()
    data class RejectedDuplicate(val existing: LegacyClaimRecord, val reason: String) : LegacyClaimWriteResult()
    data class RejectedCapacityExhausted(val currentSize: Int, val maxCapacity: Int) : LegacyClaimWriteResult()
    data class RejectedStorageCorrupt(val reason: String) : LegacyClaimWriteResult()
    data class RejectedUnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : LegacyClaimWriteResult()
    data class RejectedPersistenceFailed(val reason: String, val isReadbackMismatch: Boolean = false) : LegacyClaimWriteResult()
}

sealed class LegacyClaimRetirementResult {
    data class Retired(val key: OutputOccurrenceKey, val remainingCount: Int) : LegacyClaimRetirementResult()
    data class Rejected(val reason: String) : LegacyClaimRetirementResult()
}

data class LegacyLedgerMigrationRecord(
    val recordId: String,
    val kind: String,
    val alarmId: Long,
    val triggerTime: Long,
    val deliveredAt: Long,
    val generation: Int,
    val instanceToken: String,
    val provenance: String,
    val restoreEpoch: Long
)

sealed class LegacyMigrationResult {
    data class MigratedAndRetired(val migratedRecord: LegacyClaimRecord, val retiredLedgerId: String) : LegacyMigrationResult()
    data class CopiedPendingRetire(val migratedRecord: LegacyClaimRecord, val reason: String) : LegacyMigrationResult()
    data class IgnoredNonLegacy(val recordId: String) : LegacyMigrationResult()
    data class Rejected(val reason: String) : LegacyMigrationResult()
}

object DirectBootLegacyClaimStore {
    private const val TAG = "DirectBootLegacyClaimStore"
    const val PREFS_NAME = "direct_boot_next_alarm"
    const val KEY_LEGACY_CLAIM_STORE_PAYLOAD = "direct_boot_legacy_claim_store_payload"
    const val MAX_CAPACITY = 32
    const val CURRENT_SCHEMA_VERSION = 1

    internal val storageLock: Any get() = DirectBootStorageLock.lock

    @VisibleForTesting
    internal var commitSeam: (SharedPreferences, String) -> Boolean = { prefs, payload ->
        prefs.edit().putString(KEY_LEGACY_CLAIM_STORE_PAYLOAD, payload).commit()
    }

    @VisibleForTesting
    internal var readbackSeam: (SharedPreferences) -> String? = { prefs ->
        prefs.getString(KEY_LEGACY_CLAIM_STORE_PAYLOAD, null)
    }

    @VisibleForTesting
    internal fun resetSeamsForTesting() {
        commitSeam = { prefs, payload ->
            prefs.edit().putString(KEY_LEGACY_CLAIM_STORE_PAYLOAD, payload).commit()
        }
        readbackSeam = { prefs ->
            prefs.getString(KEY_LEGACY_CLAIM_STORE_PAYLOAD, null)
        }
    }

    @VisibleForTesting
    internal fun prefs(context: Context): SharedPreferences {
        val storageContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return storageContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun inspectStorageState(context: Context): LegacyClaimStorageState {
        val raw = try {
            readbackSeam(prefs(context))
        } catch (e: Exception) {
            return LegacyClaimStorageState.Corrupt("Readback failed: ${e.message}")
        } ?: return LegacyClaimStorageState.Absent

        if (raw.isBlank()) {
            return LegacyClaimStorageState.Corrupt("Blank payload")
        }

        return try {
            val root = JSONObject(raw)
            if (!root.has("schemaVersion")) {
                return LegacyClaimStorageState.Corrupt("Missing schemaVersion")
            }
            val schemaVersion = root.getInt("schemaVersion")
            if (schemaVersion != CURRENT_SCHEMA_VERSION) {
                return LegacyClaimStorageState.UnsupportedSchema(
                    foundVersion = schemaVersion,
                    expectedVersion = CURRENT_SCHEMA_VERSION
                )
            }
            if (!root.has("records")) {
                return LegacyClaimStorageState.Corrupt("Missing records array")
            }
            val arr = root.getJSONArray("records")
            val records = ArrayList<LegacyClaimRecord>(arr.length())
            val seenKeys = HashSet<OutputOccurrenceKey>()
            for (i in 0 until arr.length()) {
                val recJson = arr.getJSONObject(i)
                val rec = LegacyClaimRecord.fromJson(recJson)
                rec.validate()?.let { err ->
                    return LegacyClaimStorageState.Corrupt(err)
                }
                if (!seenKeys.add(rec.key)) {
                    return LegacyClaimStorageState.Corrupt("Duplicate occurrence key: ${rec.key}")
                }
                records.add(rec)
            }
            if (records.isEmpty()) {
                LegacyClaimStorageState.ValidEmpty
            } else {
                LegacyClaimStorageState.ValidWithRecords(records)
            }
        } catch (e: Exception) {
            LegacyClaimStorageState.Corrupt(e.message ?: "Malformed JSON payload")
        }
    }

    fun writeInitialClaim(context: Context, record: LegacyClaimRecord): LegacyClaimWriteResult {
        record.validate()?.let {
            return LegacyClaimWriteResult.RejectedInvalidTransition(null, record.state, it)
        }
        if (record.state !in setOf(LegacyClaimState.CLAIMED_RECEIVER, LegacyClaimState.START_REQUESTED)) {
            return LegacyClaimWriteResult.RejectedInvalidTransition(
                from = null,
                to = record.state,
                reason = "Initial claim must start at CLAIMED_RECEIVER or START_REQUESTED"
            )
        }

        synchronized(storageLock) {
            val currentRecords = when (val state = inspectStorageState(context)) {
                is LegacyClaimStorageState.Absent,
                is LegacyClaimStorageState.ValidEmpty -> emptyList()
                is LegacyClaimStorageState.ValidWithRecords -> state.records
                is LegacyClaimStorageState.Corrupt ->
                    return LegacyClaimWriteResult.RejectedStorageCorrupt(state.reason)
                is LegacyClaimStorageState.UnsupportedSchema ->
                    return LegacyClaimWriteResult.RejectedUnsupportedSchema(state.foundVersion, state.expectedVersion)
            }

            currentRecords.firstOrNull { it.key == record.key }?.let { existing ->
                return LegacyClaimWriteResult.RejectedDuplicate(existing, "Claim already exists for ${record.key}")
            }

            if (currentRecords.size >= MAX_CAPACITY) {
                return LegacyClaimWriteResult.RejectedCapacityExhausted(currentRecords.size, MAX_CAPACITY)
            }

            val updated = currentRecords + record
            commitAndVerify(context, updated)?.let { failure ->
                return failure
            }
            return LegacyClaimWriteResult.Committed(record, updated.size)
        }
    }

    fun transition(
        context: Context,
        key: OutputOccurrenceKey,
        expectedFrom: LegacyClaimState,
        toState: LegacyClaimState,
        expectedNonce: String?,
        newNonce: String,
        processSessionToken: String,
        bootCount: Long,
        terminalOutcome: LegacyTerminalOutcome? = null,
        wakeConfirm: WakeConfirmLineage? = null
    ): LegacyClaimWriteResult {
        if (newNonce.isBlank() || processSessionToken.isBlank() || bootCount < 0L) {
            return LegacyClaimWriteResult.RejectedInvalidTransition(expectedFrom, toState, "Invalid provenance/nonce")
        }

        synchronized(storageLock) {
            val currentRecords = when (val state = inspectStorageState(context)) {
                is LegacyClaimStorageState.Absent,
                is LegacyClaimStorageState.ValidEmpty ->
                    return LegacyClaimWriteResult.RejectedInvalidTransition(null, toState, "Store empty")
                is LegacyClaimStorageState.ValidWithRecords -> state.records
                is LegacyClaimStorageState.Corrupt ->
                    return LegacyClaimWriteResult.RejectedStorageCorrupt(state.reason)
                is LegacyClaimStorageState.UnsupportedSchema ->
                    return LegacyClaimWriteResult.RejectedUnsupportedSchema(state.foundVersion, state.expectedVersion)
            }

            val idx = currentRecords.indexOfFirst { it.key == key }
            if (idx < 0) {
                return LegacyClaimWriteResult.RejectedInvalidTransition(null, toState, "Missing claim for $key")
            }
            val existing = currentRecords[idx]
            if (existing.state != expectedFrom) {
                return LegacyClaimWriteResult.RejectedInvalidTransition(
                    from = existing.state,
                    to = toState,
                    reason = "Expected state $expectedFrom but found ${existing.state}"
                )
            }
            if (expectedNonce.isNullOrBlank() || existing.claimNonce != expectedNonce) {
                return LegacyClaimWriteResult.RejectedInvalidTransition(
                    from = existing.state,
                    to = toState,
                    reason = "ClaimNonce mismatch or null"
                )
            }
            if (toState != LegacyClaimState.UNKNOWN && (existing.processSessionToken != processSessionToken || existing.bootCount != bootCount)) {
                return LegacyClaimWriteResult.RejectedInvalidTransition(
                    from = existing.state,
                    to = toState,
                    reason = "Provenance mismatch (processSessionToken or bootCount)"
                )
            }
            if (!isTransitionAllowed(existing.state, toState, terminalOutcome)) {
                return LegacyClaimWriteResult.RejectedInvalidTransition(
                    from = existing.state,
                    to = toState,
                    reason = "Disallowed state transition ${existing.state} -> $toState"
                )
            }

            val updatedRecord = existing.copy(
                claimNonce = newNonce,
                processSessionToken = processSessionToken,
                bootCount = bootCount,
                state = toState,
                terminalOutcome = terminalOutcome,
                wakeConfirm = wakeConfirm ?: existing.wakeConfirm
            )
            updatedRecord.validate()?.let { err ->
                return LegacyClaimWriteResult.RejectedInvalidTransition(existing.state, toState, err)
            }

            val updatedList = currentRecords.toMutableList().apply { set(idx, updatedRecord) }
            commitAndVerify(context, updatedList)?.let { failure ->
                return failure
            }
            return LegacyClaimWriteResult.Committed(updatedRecord, updatedList.size)
        }
    }

    fun retireQualifiedTerminal(
        context: Context,
        key: OutputOccurrenceKey,
        hasCeDurableProof: Boolean,
        isAbsentFromV2QueueAndOwner: Boolean,
        ageMsOnly: Boolean = false
    ): LegacyClaimRetirementResult {
        if (ageMsOnly) {
            return LegacyClaimRetirementResult.Rejected("Age-only retirement is strictly prohibited")
        }
        if (!hasCeDurableProof) {
            return LegacyClaimRetirementResult.Rejected("Missing CE durable proof")
        }
        if (!isAbsentFromV2QueueAndOwner) {
            return LegacyClaimRetirementResult.Rejected("Occurrence still present in V2 queue or owner")
        }

        synchronized(storageLock) {
            val currentRecords = when (val state = inspectStorageState(context)) {
                is LegacyClaimStorageState.Absent,
                is LegacyClaimStorageState.ValidEmpty ->
                    return LegacyClaimRetirementResult.Rejected("No records to retire")
                is LegacyClaimStorageState.ValidWithRecords -> state.records
                is LegacyClaimStorageState.Corrupt ->
                    return LegacyClaimRetirementResult.Rejected("Storage corrupt: ${state.reason}")
                is LegacyClaimStorageState.UnsupportedSchema ->
                    return LegacyClaimRetirementResult.Rejected("Unsupported schema: ${state.foundVersion}")
            }

            val existing = currentRecords.firstOrNull { it.key == key }
                ?: return LegacyClaimRetirementResult.Rejected("Record not found for $key")

            if (existing.state != LegacyClaimState.TERMINAL) {
                return LegacyClaimRetirementResult.Rejected("Only TERMINAL claims may retire; found ${existing.state}")
            }

            val remaining = currentRecords.filterNot { it.key == key }
            commitAndVerify(context, remaining)?.let { failure ->
                return LegacyClaimRetirementResult.Rejected("Persistence failed during retirement: $failure")
            }
            return LegacyClaimRetirementResult.Retired(key, remaining.size)
        }
    }

    fun retireWithEvidence(
        context: Context,
        evidence: com.sysadmindoc.alarmclock.directboot.outputauthority.VerifiedRetirementEvidence,
        evidenceMaxAgeMs: Long = 30_000L
    ): LegacyClaimRetirementResult {
        val now = System.currentTimeMillis()
        if (now - evidence.verifiedAt > evidenceMaxAgeMs) {
            return LegacyClaimRetirementResult.Rejected("Retirement evidence expired (age=${now - evidence.verifiedAt}ms, max=$evidenceMaxAgeMs)")
        }
        if (evidence.restoreEpoch <= 0L) {
            return LegacyClaimRetirementResult.Rejected("Invalid restoreEpoch in evidence: ${evidence.restoreEpoch}")
        }

        synchronized(storageLock) {
            val currentRecords = when (val state = inspectStorageState(context)) {
                is LegacyClaimStorageState.Absent,
                is LegacyClaimStorageState.ValidEmpty ->
                    return LegacyClaimRetirementResult.Rejected("No records to retire")
                is LegacyClaimStorageState.ValidWithRecords -> state.records
                is LegacyClaimStorageState.Corrupt ->
                    return LegacyClaimRetirementResult.Rejected("Storage corrupt: ${state.reason}")
                is LegacyClaimStorageState.UnsupportedSchema ->
                    return LegacyClaimRetirementResult.Rejected("Unsupported schema: ${state.foundVersion}")
            }

            val existing = currentRecords.firstOrNull { it.key == evidence.key }
                ?: return LegacyClaimRetirementResult.Rejected("Record not found for ${evidence.key}")

            if (existing.state != LegacyClaimState.TERMINAL) {
                return LegacyClaimRetirementResult.Rejected("Only TERMINAL claims may retire; found ${existing.state}")
            }
            if (existing.source != evidence.source) {
                return LegacyClaimRetirementResult.Rejected("Source mismatch: expected ${existing.source}, found ${evidence.source}")
            }

            // Atomically re-verify V2 absence under DE storageLock right before removal
            val inCache = when (val cacheState = DirectBootAlarmCache.inspectQueueStorageState(context)) {
                is QueueStorageState.ValidWithItems ->
                    cacheState.items.any { it.alarmId == evidence.key.alarmId && it.triggerTime == evidence.key.scheduledAt }
                else -> false
            }
            if (inCache) {
                return LegacyClaimRetirementResult.Rejected("Occurrence re-appeared in V2 queue prior to retirement commit")
            }

            val inOwner = when (val ownerResult = DirectBootDurableOwnerStore.read(context)) {
                is OwnerReadResult.Success -> {
                    val owner = ownerResult.owner
                    val inClaim = owner.handoverClaim?.let { it.alarmId == evidence.key.alarmId && it.triggerTime == evidence.key.scheduledAt } ?: false
                    val inSession = owner.activeSession?.let { it.alarmId == evidence.key.alarmId && it.triggerTime == evidence.key.scheduledAt } ?: false
                    inClaim || inSession
                }
                else -> false
            }
            if (inOwner) {
                return LegacyClaimRetirementResult.Rejected("Occurrence re-appeared in V2 durable owner prior to retirement commit")
            }

            val remaining = currentRecords.filterNot { it.key == evidence.key }
            commitAndVerify(context, remaining)?.let { failure ->
                return LegacyClaimRetirementResult.Rejected("Persistence failed during retirement: $failure")
            }
            return LegacyClaimRetirementResult.Retired(evidence.key, remaining.size)
        }
    }

    fun migrateOldLedgerLegacyClaim(
        context: Context,
        ledgerRecord: LegacyLedgerMigrationRecord,
        hasCeTerminalProof: Boolean,
        isAbsentFromV2QueueAndOwner: Boolean,
        retireFromOldLedger: (String) -> Boolean
    ): LegacyMigrationResult {
        if (ledgerRecord.provenance != "LEGACY_DELIVERY") {
            return LegacyMigrationResult.IgnoredNonLegacy(ledgerRecord.recordId)
        }
        if (ledgerRecord.alarmId <= 0L || ledgerRecord.triggerTime <= 0L || ledgerRecord.recordId.isBlank()) {
            return LegacyMigrationResult.Rejected("Invalid legacy ledger record fields")
        }

        synchronized(storageLock) {
            val currentRecords = when (val state = inspectStorageState(context)) {
                is LegacyClaimStorageState.Absent,
                is LegacyClaimStorageState.ValidEmpty -> emptyList()
                is LegacyClaimStorageState.ValidWithRecords -> state.records
                is LegacyClaimStorageState.Corrupt ->
                    return LegacyMigrationResult.Rejected("Storage corrupt: ${state.reason}")
                is LegacyClaimStorageState.UnsupportedSchema ->
                    return LegacyMigrationResult.Rejected("Unsupported schema: ${state.foundVersion}")
            }

            currentRecords.firstOrNull { it.migratedFromLedgerRecordId == ledgerRecord.recordId }?.let { alreadyCopied ->
                val retired = try {
                    retireFromOldLedger(ledgerRecord.recordId)
                } catch (_: Exception) {
                    false
                }
                return if (retired) {
                    LegacyMigrationResult.MigratedAndRetired(alreadyCopied, ledgerRecord.recordId)
                } else {
                    LegacyMigrationResult.CopiedPendingRetire(alreadyCopied, "Old ledger retirement failed on retry")
                }
            }

            val targetKey = OutputOccurrenceKey(ledgerRecord.alarmId, ledgerRecord.triggerTime, 0)
            if (currentRecords.any { it.key == targetKey }) {
                return LegacyMigrationResult.Rejected("Occurrence $targetKey already exists in LegacyClaimStore")
            }
            if (currentRecords.size >= MAX_CAPACITY) {
                return LegacyMigrationResult.Rejected("LegacyClaimStore at capacity ($MAX_CAPACITY)")
            }

            val migratedState = if (hasCeTerminalProof && isAbsentFromV2QueueAndOwner) {
                LegacyClaimState.TERMINAL
            } else {
                LegacyClaimState.UNKNOWN
            }
            val migratedOutcome = if (migratedState == LegacyClaimState.TERMINAL) {
                LegacyTerminalOutcome.MIGRATED_RETIRED
            } else {
                null
            }

            val migratedRecord = LegacyClaimRecord(
                alarmId = ledgerRecord.alarmId,
                scheduledAt = ledgerRecord.triggerTime,
                refireCount = 0,
                source = LegacyClaimSource.LEGACY_LEDGER_MIGRATION,
                claimNonce = "migrated-${ledgerRecord.recordId}",
                processSessionToken = "migration",
                bootCount = 0L,
                state = migratedState,
                terminalOutcome = migratedOutcome,
                wakeConfirm = null,
                migratedFromLedgerRecordId = ledgerRecord.recordId
            )

            commitAndVerify(context, currentRecords + migratedRecord)?.let { failure ->
                return LegacyMigrationResult.Rejected("Copy commit/readback failed: $failure")
            }

            val retired = try {
                retireFromOldLedger(ledgerRecord.recordId)
            } catch (_: Exception) {
                false
            }

            return if (retired) {
                LegacyMigrationResult.MigratedAndRetired(migratedRecord, ledgerRecord.recordId)
            } else {
                LegacyMigrationResult.CopiedPendingRetire(migratedRecord, "Copied with readback, pending old ledger retirement")
            }
        }
    }

    private fun isTransitionAllowed(
        from: LegacyClaimState,
        to: LegacyClaimState,
        terminalOutcome: LegacyTerminalOutcome?
    ): Boolean {
        if (from == LegacyClaimState.TERMINAL) return false
        if (to == LegacyClaimState.TERMINAL) {
            if (terminalOutcome == null) return false
            if (from == LegacyClaimState.UNKNOWN) {
                return terminalOutcome == LegacyTerminalOutcome.RECONCILED_CE ||
                    terminalOutcome == LegacyTerminalOutcome.MIGRATED_RETIRED
            }
            return true
        }
        if (terminalOutcome != null) return false
        if (to == LegacyClaimState.UNKNOWN) return from != LegacyClaimState.UNKNOWN
        if (from == LegacyClaimState.UNKNOWN) return false

        fun rank(s: LegacyClaimState): Int = when (s) {
            LegacyClaimState.CLAIMED_RECEIVER -> 0
            LegacyClaimState.START_REQUESTED -> 1
            LegacyClaimState.SERVICE_ACCEPTED -> 2
            LegacyClaimState.OUTPUT_CLAIMING -> 3
            LegacyClaimState.OUTPUT_OBSERVED -> 4
            LegacyClaimState.TERMINAL, LegacyClaimState.UNKNOWN -> -1
        }
        return rank(to) > rank(from)
    }

    private fun commitAndVerify(
        context: Context,
        records: List<LegacyClaimRecord>
    ): LegacyClaimWriteResult.RejectedPersistenceFailed? {
        val root = JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            put("records", arr)
        }
        val encoded = root.toString()
        val prefs = prefs(context)
        val committed = try {
            commitSeam(prefs, encoded)
        } catch (e: Exception) {
            return LegacyClaimWriteResult.RejectedPersistenceFailed("commitSeam threw: ${e.message}")
        }
        if (!committed) {
            return LegacyClaimWriteResult.RejectedPersistenceFailed("commitSeam returned false")
        }
        val readback = try {
            readbackSeam(prefs)
        } catch (e: Exception) {
            return LegacyClaimWriteResult.RejectedPersistenceFailed("readbackSeam threw: ${e.message}", isReadbackMismatch = true)
        }
        if (readback != encoded) {
            return LegacyClaimWriteResult.RejectedPersistenceFailed("Readback mismatch after commit", isReadbackMismatch = true)
        }
        return null
    }
}
