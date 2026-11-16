package com.sysadmindoc.alarmclock.directboot.d0contract

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Isolated D0-P0 contract-only pure model for Legacy Claim lifecycle & capacity.
 * Strictly located under app/src/test (unreachable from production runtime).
 *
 * Uses pure-Kotlin deterministic JSON serialization & strict validation with zero
 * Android framework dependencies so all contract tests run fast on the JVM.
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

enum class TerminalOutcome {
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

enum class ClaimSource {
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
)

data class LegacyClaimRecord(
    val alarmId: Long,
    val scheduledAt: Long,
    val refireCount: Int,
    val source: ClaimSource,
    val claimNonce: String,
    val processSessionToken: String,
    val bootCount: Long,
    val state: LegacyClaimState,
    val terminalOutcome: TerminalOutcome? = null,
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
}

sealed class LegacyClaimStorageState {
    object Absent : LegacyClaimStorageState()
    object ValidEmpty : LegacyClaimStorageState()
    data class ValidWithRecords(val records: List<LegacyClaimRecord>) : LegacyClaimStorageState()
    data class Corrupt(val reason: String) : LegacyClaimStorageState()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : LegacyClaimStorageState()
}

sealed class ClaimStoreWriteResult {
    data class Committed(val record: LegacyClaimRecord, val activeCount: Int) : ClaimStoreWriteResult()
    data class RejectedInvalidTransition(val from: LegacyClaimState?, val to: LegacyClaimState, val reason: String) : ClaimStoreWriteResult()
    data class RejectedDuplicate(val existing: LegacyClaimRecord, val reason: String) : ClaimStoreWriteResult()
    data class RejectedCapacityExhausted(val currentSize: Int, val maxCapacity: Int) : ClaimStoreWriteResult()
    data class RejectedStorageCorrupt(val reason: String) : ClaimStoreWriteResult()
    data class RejectedUnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : ClaimStoreWriteResult()
    data class RejectedPersistenceFailed(val reason: String, val isReadbackMismatch: Boolean = false) : ClaimStoreWriteResult()
}

sealed class ClaimRetirementResult {
    data class Retired(val key: OutputOccurrenceKey, val remainingCount: Int) : ClaimRetirementResult()
    data class Rejected(val reason: String) : ClaimRetirementResult()
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

sealed class MigrationResult {
    data class MigratedAndRetired(val migratedRecord: LegacyClaimRecord, val retiredLedgerId: String) : MigrationResult()
    data class CopiedPendingRetire(val migratedRecord: LegacyClaimRecord, val reason: String) : MigrationResult()
    data class IgnoredNonLegacy(val recordId: String) : MigrationResult()
    data class Rejected(val reason: String) : MigrationResult()
}

class InMemoryLegacyClaimStore(
    val maxCapacity: Int = MAX_CAPACITY,
    var schemaVersion: Int = CURRENT_SCHEMA_VERSION
) {
    companion object {
        const val MAX_CAPACITY = 32
        const val CURRENT_SCHEMA_VERSION = 1
    }

    private val lock = ReentrantLock()

    var rawPayload: String? = null
    var commitSeam: (String) -> Boolean = { payload ->
        rawPayload = payload
        true
    }
    var readbackSeam: () -> String? = { rawPayload }

    fun <T> withLock(block: () -> T): T = lock.withLock(block)

    fun inspect(): LegacyClaimStorageState = lock.withLock {
        val raw = try {
            readbackSeam()
        } catch (e: Exception) {
            return@withLock LegacyClaimStorageState.Corrupt("Readback failed: ${e.message}")
        } ?: return@withLock LegacyClaimStorageState.Absent

        if (raw.isBlank()) {
            return@withLock LegacyClaimStorageState.Corrupt("Blank payload")
        }

        return@withLock try {
            val parsed = LegacyClaimJsonCodec.decodeEnvelope(raw)
            if (parsed.schemaVersion != CURRENT_SCHEMA_VERSION) {
                return@withLock LegacyClaimStorageState.UnsupportedSchema(
                    foundVersion = parsed.schemaVersion,
                    expectedVersion = CURRENT_SCHEMA_VERSION
                )
            }
            val seenKeys = HashSet<OutputOccurrenceKey>()
            for (rec in parsed.records) {
                rec.validate()?.let { err ->
                    return@withLock LegacyClaimStorageState.Corrupt(err)
                }
                if (!seenKeys.add(rec.key)) {
                    return@withLock LegacyClaimStorageState.Corrupt("Duplicate occurrence key: ${rec.key}")
                }
            }
            if (parsed.records.isEmpty()) {
                LegacyClaimStorageState.ValidEmpty
            } else {
                LegacyClaimStorageState.ValidWithRecords(parsed.records)
            }
        } catch (e: Exception) {
            LegacyClaimStorageState.Corrupt(e.message ?: "Malformed JSON payload")
        }
    }

    fun writeInitialClaim(record: LegacyClaimRecord): ClaimStoreWriteResult = lock.withLock {
        record.validate()?.let {
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(null, record.state, it)
        }
        if (record.state !in setOf(LegacyClaimState.CLAIMED_RECEIVER, LegacyClaimState.START_REQUESTED)) {
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(
                from = null,
                to = record.state,
                reason = "Initial claim must start at CLAIMED_RECEIVER or START_REQUESTED"
            )
        }

        val currentRecords = when (val state = inspect()) {
            is LegacyClaimStorageState.Absent,
            is LegacyClaimStorageState.ValidEmpty -> emptyList()
            is LegacyClaimStorageState.ValidWithRecords -> state.records
            is LegacyClaimStorageState.Corrupt ->
                return@withLock ClaimStoreWriteResult.RejectedStorageCorrupt(state.reason)
            is LegacyClaimStorageState.UnsupportedSchema ->
                return@withLock ClaimStoreWriteResult.RejectedUnsupportedSchema(state.foundVersion, state.expectedVersion)
        }

        currentRecords.firstOrNull { it.key == record.key }?.let { existing ->
            return@withLock ClaimStoreWriteResult.RejectedDuplicate(existing, "Claim already exists for ${record.key}")
        }

        if (currentRecords.size >= maxCapacity) {
            return@withLock ClaimStoreWriteResult.RejectedCapacityExhausted(currentRecords.size, maxCapacity)
        }

        val updated = currentRecords + record
        commitAndVerify(updated)?.let { failure ->
            return@withLock failure
        }
        ClaimStoreWriteResult.Committed(record, updated.size)
    }

    fun transition(
        key: OutputOccurrenceKey,
        expectedFrom: LegacyClaimState,
        toState: LegacyClaimState,
        expectedNonce: String?,
        newNonce: String,
        processSessionToken: String,
        bootCount: Long,
        terminalOutcome: TerminalOutcome? = null,
        wakeConfirm: WakeConfirmLineage? = null
    ): ClaimStoreWriteResult = lock.withLock {
        if (newNonce.isBlank() || processSessionToken.isBlank() || bootCount < 0L) {
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(expectedFrom, toState, "Invalid provenance/nonce")
        }
        val currentRecords = when (val state = inspect()) {
            is LegacyClaimStorageState.Absent,
            is LegacyClaimStorageState.ValidEmpty ->
                return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(null, toState, "Store empty")
            is LegacyClaimStorageState.ValidWithRecords -> state.records
            is LegacyClaimStorageState.Corrupt ->
                return@withLock ClaimStoreWriteResult.RejectedStorageCorrupt(state.reason)
            is LegacyClaimStorageState.UnsupportedSchema ->
                return@withLock ClaimStoreWriteResult.RejectedUnsupportedSchema(state.foundVersion, state.expectedVersion)
        }

        val idx = currentRecords.indexOfFirst { it.key == key }
        if (idx < 0) {
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(null, toState, "Missing claim for $key")
        }
        val existing = currentRecords[idx]
        if (existing.state != expectedFrom) {
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(
                from = existing.state,
                to = toState,
                reason = "Expected state $expectedFrom but found ${existing.state}"
            )
        }
        if (expectedNonce != null && existing.claimNonce != expectedNonce) {
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(
                from = existing.state,
                to = toState,
                reason = "ClaimNonce mismatch"
            )
        }
        if (!isTransitionAllowed(existing.state, toState, terminalOutcome)) {
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(
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
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(existing.state, toState, err)
        }

        val updatedList = currentRecords.toMutableList().apply { set(idx, updatedRecord) }
        commitAndVerify(updatedList)?.let { failure ->
            return@withLock failure
        }
        ClaimStoreWriteResult.Committed(updatedRecord, updatedList.size)
    }

    internal fun updateWakeConfirmLineage(
        key: OutputOccurrenceKey,
        updatedLineage: WakeConfirmLineage
    ): ClaimStoreWriteResult = lock.withLock {
        val currentRecords = when (val state = inspect()) {
            is LegacyClaimStorageState.Absent,
            is LegacyClaimStorageState.ValidEmpty ->
                return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(null, LegacyClaimState.TERMINAL, "Store empty")
            is LegacyClaimStorageState.ValidWithRecords -> state.records
            is LegacyClaimStorageState.Corrupt ->
                return@withLock ClaimStoreWriteResult.RejectedStorageCorrupt(state.reason)
            is LegacyClaimStorageState.UnsupportedSchema ->
                return@withLock ClaimStoreWriteResult.RejectedUnsupportedSchema(state.foundVersion, state.expectedVersion)
        }
        val idx = currentRecords.indexOfFirst { it.key == key }
        if (idx < 0) {
            return@withLock ClaimStoreWriteResult.RejectedInvalidTransition(null, LegacyClaimState.TERMINAL, "Missing record for $key")
        }
        val updatedRecord = currentRecords[idx].copy(wakeConfirm = updatedLineage)
        val updatedList = currentRecords.toMutableList().apply { set(idx, updatedRecord) }
        commitAndVerify(updatedList)?.let { return@withLock it }
        ClaimStoreWriteResult.Committed(updatedRecord, updatedList.size)
    }

    fun retireQualifiedTerminal(
        key: OutputOccurrenceKey,
        hasCeDurableProof: Boolean,
        isAbsentFromV2QueueAndOwner: Boolean,
        ageMsOnly: Boolean = false
    ): ClaimRetirementResult = lock.withLock {
        if (ageMsOnly) {
            return@withLock ClaimRetirementResult.Rejected("Age-only retirement is strictly prohibited")
        }
        if (!hasCeDurableProof) {
            return@withLock ClaimRetirementResult.Rejected("Missing CE durable proof")
        }
        if (!isAbsentFromV2QueueAndOwner) {
            return@withLock ClaimRetirementResult.Rejected("Occurrence still present in V2 queue or owner")
        }

        val currentRecords = when (val state = inspect()) {
            is LegacyClaimStorageState.Absent,
            is LegacyClaimStorageState.ValidEmpty ->
                return@withLock ClaimRetirementResult.Rejected("No records to retire")
            is LegacyClaimStorageState.ValidWithRecords -> state.records
            is LegacyClaimStorageState.Corrupt ->
                return@withLock ClaimRetirementResult.Rejected("Storage corrupt: ${state.reason}")
            is LegacyClaimStorageState.UnsupportedSchema ->
                return@withLock ClaimRetirementResult.Rejected("Unsupported schema: ${state.foundVersion}")
        }

        val existing = currentRecords.firstOrNull { it.key == key }
            ?: return@withLock ClaimRetirementResult.Rejected("Record not found for $key")

        if (existing.state != LegacyClaimState.TERMINAL) {
            return@withLock ClaimRetirementResult.Rejected("Only TERMINAL claims may retire; found ${existing.state}")
        }

        val remaining = currentRecords.filterNot { it.key == key }
        commitAndVerify(remaining)?.let { failure ->
            return@withLock ClaimRetirementResult.Rejected("Persistence failed during retirement: $failure")
        }
        ClaimRetirementResult.Retired(key, remaining.size)
    }

    fun migrateOldLedgerLegacyClaim(
        ledgerRecord: LegacyLedgerMigrationRecord,
        hasCeTerminalProof: Boolean,
        isAbsentFromV2QueueAndOwner: Boolean,
        retireFromOldLedger: (String) -> Boolean
    ): MigrationResult = lock.withLock {
        if (ledgerRecord.provenance != "LEGACY_DELIVERY") {
            return@withLock MigrationResult.IgnoredNonLegacy(ledgerRecord.recordId)
        }
        if (ledgerRecord.alarmId <= 0L || ledgerRecord.triggerTime <= 0L || ledgerRecord.recordId.isBlank()) {
            return@withLock MigrationResult.Rejected("Invalid legacy ledger record fields")
        }

        val currentRecords = when (val state = inspect()) {
            is LegacyClaimStorageState.Absent,
            is LegacyClaimStorageState.ValidEmpty -> emptyList()
            is LegacyClaimStorageState.ValidWithRecords -> state.records
            is LegacyClaimStorageState.Corrupt ->
                return@withLock MigrationResult.Rejected("Storage corrupt: ${state.reason}")
            is LegacyClaimStorageState.UnsupportedSchema ->
                return@withLock MigrationResult.Rejected("Unsupported schema: ${state.foundVersion}")
        }

        // Idempotent recovery for crash mid-migration after copy+readback but before old ledger retirement
        currentRecords.firstOrNull { it.migratedFromLedgerRecordId == ledgerRecord.recordId }?.let { alreadyCopied ->
            val retired = try {
                retireFromOldLedger(ledgerRecord.recordId)
            } catch (_: Exception) {
                false
            }
            return@withLock if (retired) {
                MigrationResult.MigratedAndRetired(alreadyCopied, ledgerRecord.recordId)
            } else {
                MigrationResult.CopiedPendingRetire(alreadyCopied, "Old ledger retirement failed on retry")
            }
        }

        val targetKey = OutputOccurrenceKey(ledgerRecord.alarmId, ledgerRecord.triggerTime, 0)
        if (currentRecords.any { it.key == targetKey }) {
            return@withLock MigrationResult.Rejected("Occurrence $targetKey already exists in LegacyClaimStore")
        }
        if (currentRecords.size >= maxCapacity) {
            return@withLock MigrationResult.Rejected("LegacyClaimStore at capacity ($maxCapacity)")
        }

        val migratedState = if (hasCeTerminalProof && isAbsentFromV2QueueAndOwner) {
            LegacyClaimState.TERMINAL
        } else {
            LegacyClaimState.UNKNOWN
        }
        val migratedOutcome = if (migratedState == LegacyClaimState.TERMINAL) {
            TerminalOutcome.MIGRATED_RETIRED
        } else {
            null
        }

        val migratedRecord = LegacyClaimRecord(
            alarmId = ledgerRecord.alarmId,
            scheduledAt = ledgerRecord.triggerTime,
            refireCount = 0,
            source = ClaimSource.LEGACY_LEDGER_MIGRATION,
            claimNonce = "migrated-${ledgerRecord.recordId}",
            processSessionToken = "migration",
            bootCount = 0L,
            state = migratedState,
            terminalOutcome = migratedOutcome,
            wakeConfirm = null,
            migratedFromLedgerRecordId = ledgerRecord.recordId
        )

        commitAndVerify(currentRecords + migratedRecord)?.let { failure ->
            return@withLock MigrationResult.Rejected("Copy commit/readback failed: $failure")
        }

        val retired = try {
            retireFromOldLedger(ledgerRecord.recordId)
        } catch (_: Exception) {
            false
        }

        return@withLock if (retired) {
            MigrationResult.MigratedAndRetired(migratedRecord, ledgerRecord.recordId)
        } else {
            MigrationResult.CopiedPendingRetire(migratedRecord, "Copied with readback, pending old ledger retirement")
        }
    }

    private fun isTransitionAllowed(
        from: LegacyClaimState,
        to: LegacyClaimState,
        terminalOutcome: TerminalOutcome?
    ): Boolean {
        if (from == LegacyClaimState.TERMINAL) return false
        if (to == LegacyClaimState.TERMINAL) {
            if (terminalOutcome == null) return false
            if (from == LegacyClaimState.UNKNOWN) {
                return terminalOutcome == TerminalOutcome.RECONCILED_CE ||
                    terminalOutcome == TerminalOutcome.MIGRATED_RETIRED
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

    private fun commitAndVerify(records: List<LegacyClaimRecord>): ClaimStoreWriteResult.RejectedPersistenceFailed? {
        val previousRaw = rawPayload
        val encoded = LegacyClaimJsonCodec.encodeEnvelope(schemaVersion, records)
        val committed = try {
            commitSeam(encoded)
        } catch (e: Exception) {
            rawPayload = previousRaw
            return ClaimStoreWriteResult.RejectedPersistenceFailed("commitSeam threw: ${e.message}")
        }
        if (!committed) {
            rawPayload = previousRaw
            return ClaimStoreWriteResult.RejectedPersistenceFailed("commitSeam returned false")
        }
        val readback = try {
            readbackSeam()
        } catch (e: Exception) {
            rawPayload = previousRaw
            return ClaimStoreWriteResult.RejectedPersistenceFailed("readbackSeam threw: ${e.message}", isReadbackMismatch = true)
        }
        if (readback != encoded) {
            rawPayload = previousRaw
            return ClaimStoreWriteResult.RejectedPersistenceFailed("Readback mismatch after commit", isReadbackMismatch = true)
        }
        return null
    }
}

/**
 * Pure-Kotlin strict JSON codec for [InMemoryLegacyClaimStore] payloads.
 */
internal object LegacyClaimJsonCodec {
    data class Envelope(val schemaVersion: Int, val records: List<LegacyClaimRecord>)

    fun encodeEnvelope(schemaVersion: Int, records: List<LegacyClaimRecord>): String {
        val sb = StringBuilder()
        sb.append("{\"schemaVersion\":").append(schemaVersion).append(",\"records\":[")
        records.forEachIndexed { idx, r ->
            if (idx > 0) sb.append(',')
            encodeRecord(sb, r)
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun encodeRecord(sb: StringBuilder, r: LegacyClaimRecord) {
        sb.append('{')
        sb.append("\"alarmId\":").append(r.alarmId).append(',')
        sb.append("\"scheduledAt\":").append(r.scheduledAt).append(',')
        sb.append("\"refireCount\":").append(r.refireCount).append(',')
        sb.append("\"source\":").append(q(r.source.name)).append(',')
        sb.append("\"claimNonce\":").append(q(r.claimNonce)).append(',')
        sb.append("\"processSessionToken\":").append(q(r.processSessionToken)).append(',')
        sb.append("\"bootCount\":").append(r.bootCount).append(',')
        sb.append("\"state\":").append(q(r.state.name)).append(',')
        sb.append("\"terminalOutcome\":").append(r.terminalOutcome?.let { q(it.name) } ?: "null").append(',')
        sb.append("\"migratedFromLedgerRecordId\":").append(r.migratedFromLedgerRecordId?.let { q(it) } ?: "null").append(',')
        sb.append("\"wakeConfirm\":")
        val wc = r.wakeConfirm
        if (wc == null) {
            sb.append("null")
        } else {
            sb.append('{')
            sb.append("\"parentDismissed\":").append(wc.parentDismissed).append(',')
            sb.append("\"wakeConfirmEnabled\":").append(wc.wakeConfirmEnabled).append(',')
            sb.append("\"nextAuthorizedRefire\":").append(wc.nextAuthorizedRefire).append(',')
            sb.append("\"highestClaimedRefire\":").append(wc.highestClaimedRefire).append(',')
            sb.append("\"confirmed\":").append(wc.confirmed).append(',')
            sb.append("\"stepToken\":").append(q(wc.stepToken)).append(',')
            sb.append("\"oneShotDisabledAllowed\":").append(wc.oneShotDisabledAllowed)
            sb.append('}')
        }
        sb.append('}')
    }

    private fun q(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    fun decodeEnvelope(raw: String): Envelope {
        val parser = StrictJsonParser(raw)
        val root = parser.parseObject()
        parser.expectEnd()

        val schemaNum = (root["schemaVersion"] as? Number)
            ?: throw IllegalArgumentException("Missing or non-numeric schemaVersion")
        val schemaVersion = schemaNum.toIntExact("schemaVersion")
        if (schemaVersion != InMemoryLegacyClaimStore.CURRENT_SCHEMA_VERSION) {
            return Envelope(schemaVersion, emptyList())
        }

        @Suppress("UNCHECKED_CAST")
        val rawRecords = (root["records"] as? List<Any?>)
            ?: throw IllegalArgumentException("Missing or non-array records")

        val records = rawRecords.map { item ->
            @Suppress("UNCHECKED_CAST")
            val obj = (item as? Map<String, Any?>)
                ?: throw IllegalArgumentException("Record entry is not an object")
            decodeRecord(obj)
        }
        return Envelope(schemaVersion, records)
    }

    private fun decodeRecord(obj: Map<String, Any?>): LegacyClaimRecord {
        val alarmId = (obj["alarmId"] as? Number)?.toLongExact("alarmId")
            ?: throw IllegalArgumentException("Missing alarmId")
        val scheduledAt = (obj["scheduledAt"] as? Number)?.toLongExact("scheduledAt")
            ?: throw IllegalArgumentException("Missing scheduledAt")
        val refireCount = (obj["refireCount"] as? Number)?.toIntExact("refireCount")
            ?: throw IllegalArgumentException("Missing refireCount")
        val source = ClaimSource.valueOf(obj["source"] as? String ?: throw IllegalArgumentException("Missing source"))
        val claimNonce = obj["claimNonce"] as? String ?: throw IllegalArgumentException("Missing claimNonce")
        val procToken = obj["processSessionToken"] as? String ?: throw IllegalArgumentException("Missing processSessionToken")
        val bootCount = (obj["bootCount"] as? Number)?.toLongExact("bootCount")
            ?: throw IllegalArgumentException("Missing bootCount")
        val state = LegacyClaimState.valueOf(obj["state"] as? String ?: throw IllegalArgumentException("Missing state"))
        val terminalOutcome = (obj["terminalOutcome"] as? String)?.let { TerminalOutcome.valueOf(it) }
        val migratedId = obj["migratedFromLedgerRecordId"] as? String

        @Suppress("UNCHECKED_CAST")
        val wcMap = obj["wakeConfirm"] as? Map<String, Any?>
        val wakeConfirm = wcMap?.let { wc ->
            WakeConfirmLineage(
                parentDismissed = wc["parentDismissed"] as? Boolean ?: throw IllegalArgumentException("Missing parentDismissed"),
                wakeConfirmEnabled = wc["wakeConfirmEnabled"] as? Boolean ?: throw IllegalArgumentException("Missing wakeConfirmEnabled"),
                nextAuthorizedRefire = (wc["nextAuthorizedRefire"] as? Number)?.toIntExact("nextAuthorizedRefire")
                    ?: throw IllegalArgumentException("Missing nextAuthorizedRefire"),
                highestClaimedRefire = (wc["highestClaimedRefire"] as? Number)?.toIntExact("highestClaimedRefire")
                    ?: throw IllegalArgumentException("Missing highestClaimedRefire"),
                confirmed = wc["confirmed"] as? Boolean ?: throw IllegalArgumentException("Missing confirmed"),
                stepToken = wc["stepToken"] as? String ?: throw IllegalArgumentException("Missing stepToken"),
                oneShotDisabledAllowed = wc["oneShotDisabledAllowed"] as? Boolean ?: true
            )
        }
        return LegacyClaimRecord(
            alarmId = alarmId,
            scheduledAt = scheduledAt,
            refireCount = refireCount,
            source = source,
            claimNonce = claimNonce,
            processSessionToken = procToken,
            bootCount = bootCount,
            state = state,
            terminalOutcome = terminalOutcome,
            wakeConfirm = wakeConfirm,
            migratedFromLedgerRecordId = migratedId
        )
    }

    private fun Number.toLongExact(field: String): Long = when (this) {
        is Long -> this
        is Int -> this.toLong()
        else -> throw IllegalArgumentException("$field must be integer")
    }

    private fun Number.toIntExact(field: String): Int = when (this) {
        is Int -> this
        is Long -> {
            if (this < Int.MIN_VALUE || this > Int.MAX_VALUE) throw IllegalArgumentException("$field overflow")
            this.toInt()
        }
        else -> throw IllegalArgumentException("$field must be integer")
    }

    private class StrictJsonParser(private val src: String) {
        private var pos = 0

        fun expectEnd() {
            skipWs()
            if (pos != src.length) throw IllegalArgumentException("Trailing characters at $pos")
        }

        fun parseObject(): Map<String, Any?> {
            skipWs()
            expect('{')
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                pos++
                return map
            }
            while (true) {
                skipWs()
                val key = parseString()
                skipWs()
                expect(':')
                val value = parseValue()
                map[key] = value
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return map
                    }
                    else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val list = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                pos++
                return list
            }
            while (true) {
                list.add(parseValue())
                skipWs()
                when (peek()) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return list
                    }
                    else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                }
            }
        }

        private fun parseValue(): Any? {
            skipWs()
            return when (val ch = peek()) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't', 'f' -> parseBoolean()
                'n' -> parseNull()
                '-', in '0'..'9' -> parseLong()
                else -> throw IllegalArgumentException("Unexpected char '$ch' at $pos")
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (pos < src.length) {
                val c = src[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) throw IllegalArgumentException("Unterminated escape")
                        val esc = src[pos++]
                        when (esc) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            else -> throw IllegalArgumentException("Unsupported escape \\$esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("Unterminated string")
        }

        private fun parseBoolean(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4; true
            } else if (src.startsWith("false", pos)) {
                pos += 5; false
            } else {
                throw IllegalArgumentException("Invalid boolean at $pos")
            }
        }

        private fun parseNull(): Any? {
            if (src.startsWith("null", pos)) {
                pos += 4; return null
            }
            throw IllegalArgumentException("Invalid null at $pos")
        }

        private fun parseLong(): Long {
            val start = pos
            if (src[pos] == '-') pos++
            if (pos >= src.length || src[pos] !in '0'..'9') throw IllegalArgumentException("Invalid number at $start")
            while (pos < src.length && src[pos] in '0'..'9') pos++
            return src.substring(start, pos).toLong()
        }

        private fun skipWs() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun peek(): Char {
            if (pos >= src.length) throw IllegalArgumentException("Unexpected EOF")
            return src[pos]
        }

        private fun expect(c: Char) {
            if (pos >= src.length || src[pos] != c) {
                throw IllegalArgumentException("Expected '$c' at $pos")
            }
            pos++
        }
    }
}
