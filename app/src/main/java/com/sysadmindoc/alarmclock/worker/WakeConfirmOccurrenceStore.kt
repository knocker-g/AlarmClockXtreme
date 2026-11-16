package com.sysadmindoc.alarmclock.worker

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.VisibleForTesting
import com.sysadmindoc.alarmclock.directboot.DirectBootStorageLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Occurrence-scoped Device-Encrypted (DE) WakeConfirm lineage & CE confirmation store.
 *
 * Enforces S5-D safety invariants across both Feature Flag OFF and ON paths:
 * 1. Occurrence identity is strictly `(alarmId, scheduledAt)` with monotonic `refireCount` 1..3.
 * 2. All DE lineage writes use synchronous `commit()` + exact string readback verification
 *    under [DirectBootStorageLock.lock].
 * 3. Lock separation: CE SharedPreferences, Room DAOs, and WorkManager are NEVER accessed
 *    while holding [DirectBootStorageLock.lock].
 * 4. Legacy `confirmed_$alarmId` key alone never authorizes confirmation without 4 explicit proofs.
 */
object WakeConfirmOccurrenceStore {
    const val DE_PREFS_NAME = "wake_confirm_de_lineage"
    const val KEY_LINEAGE_PAYLOAD = "wake_confirm_de_lineage_payload"
    const val CE_PREFS_NAME = "wake_confirm"
    const val CURRENT_SCHEMA_VERSION = 1
    const val MAX_CAPACITY = 64

    data class Record(
        val alarmId: Long,
        val scheduledAt: Long,
        val fireId: String,
        val parentDismissed: Boolean,
        val dismissedAt: Long,
        val wakeConfirmEnabled: Boolean,
        val oneShotDisabledAllowed: Boolean,
        val nextAuthorizedRefire: Int,
        val highestClaimedRefire: Int,
        val confirmed: Boolean,
        val cancelled: Boolean,
        val stepToken: String,
        val enqueueConfirmed: Boolean,
        val legacyConfirmedKeyPreexistedAtDismiss: Boolean,
        val legacyCompatSeeded: Boolean = false
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("alarmId", alarmId)
            put("scheduledAt", scheduledAt)
            put("fireId", fireId)
            put("parentDismissed", parentDismissed)
            put("dismissedAt", dismissedAt)
            put("wakeConfirmEnabled", wakeConfirmEnabled)
            put("oneShotDisabledAllowed", oneShotDisabledAllowed)
            put("nextAuthorizedRefire", nextAuthorizedRefire)
            put("highestClaimedRefire", highestClaimedRefire)
            put("confirmed", confirmed)
            put("cancelled", cancelled)
            put("stepToken", stepToken)
            put("enqueueConfirmed", enqueueConfirmed)
            put("legacyConfirmedKeyPreexistedAtDismiss", legacyConfirmedKeyPreexistedAtDismiss)
            put("legacyCompatSeeded", legacyCompatSeeded)
        }

        companion object {
            fun fromJson(json: JSONObject): Record = Record(
                alarmId = json.getLong("alarmId"),
                scheduledAt = json.getLong("scheduledAt"),
                fireId = json.optString("fireId", ""),
                parentDismissed = json.getBoolean("parentDismissed"),
                dismissedAt = json.getLong("dismissedAt"),
                wakeConfirmEnabled = json.getBoolean("wakeConfirmEnabled"),
                oneShotDisabledAllowed = json.optBoolean("oneShotDisabledAllowed", true),
                nextAuthorizedRefire = json.getInt("nextAuthorizedRefire"),
                highestClaimedRefire = json.getInt("highestClaimedRefire"),
                confirmed = json.getBoolean("confirmed"),
                cancelled = json.getBoolean("cancelled"),
                stepToken = json.optString("stepToken", ""),
                enqueueConfirmed = json.optBoolean("enqueueConfirmed", false),
                legacyConfirmedKeyPreexistedAtDismiss = json.optBoolean("legacyConfirmedKeyPreexistedAtDismiss", false),
                legacyCompatSeeded = json.optBoolean("legacyCompatSeeded", false)
            )
        }
    }

    sealed class StorageState {
        object Absent : StorageState()
        object ValidEmpty : StorageState()
        data class Valid(val records: List<Record>) : StorageState()
        data class Corrupt(val reason: String) : StorageState()
        data class UnsupportedSchema(val foundVersion: Int) : StorageState()
    }

    sealed class CommitResult {
        data class Committed(val record: Record) : CommitResult()
        data class Failed(val reason: String, val isReadbackMismatch: Boolean = false) : CommitResult()
    }

    sealed class WorkerAuthResult {
        data class Authorized(val record: Record) : WorkerAuthResult()
        data class Rejected(val reason: String) : WorkerAuthResult()
    }

    sealed class RefireConsumeResult {
        data class Consumed(val nextRefireCount: Int, val record: Record) : RefireConsumeResult()
        data class Rejected(val reason: String) : RefireConsumeResult()
    }

    sealed class ConfirmationStatus {
        object Confirmed : ConfirmationStatus()
        object Unconfirmed : ConfirmationStatus()
        data class StorageUnverifiable(val reason: String) : ConfirmationStatus()
    }

    @VisibleForTesting
    internal var deCommitSeam: (SharedPreferences, String) -> Boolean = { prefs, payload ->
        prefs.edit().putString(KEY_LINEAGE_PAYLOAD, payload).commit()
    }

    @VisibleForTesting
    internal var deReadbackSeam: (SharedPreferences) -> String? = { prefs ->
        prefs.getString(KEY_LINEAGE_PAYLOAD, null)
    }

    @VisibleForTesting
    internal var ceCommitBooleanSeam: (SharedPreferences, String, String, Long) -> Boolean = { prefs, key, atKey, now ->
        prefs.edit()
            .putBoolean(key, true)
            .putLong(atKey, now)
            .commit()
    }

    @VisibleForTesting
    internal var ceReadbackBooleanSeam: (SharedPreferences, String) -> Boolean = { prefs, key ->
        prefs.getBoolean(key, false)
    }

    @VisibleForTesting
    internal fun resetSeamsForTesting() {
        deCommitSeam = { prefs, payload ->
            prefs.edit().putString(KEY_LINEAGE_PAYLOAD, payload).commit()
        }
        deReadbackSeam = { prefs ->
            prefs.getString(KEY_LINEAGE_PAYLOAD, null)
        }
        ceCommitBooleanSeam = { prefs, key, atKey, now ->
            prefs.edit()
                .putBoolean(key, true)
                .putLong(atKey, now)
                .commit()
        }
        ceReadbackBooleanSeam = { prefs, key ->
            prefs.getBoolean(key, false)
        }
    }

    fun confirmedKey(alarmId: Long, scheduledAt: Long): String = "confirmed_${alarmId}_${scheduledAt}"

    fun confirmedAtKey(alarmId: Long, scheduledAt: Long): String = "confirmed_at_${alarmId}_${scheduledAt}"

    fun legacyConfirmedKey(alarmId: Long): String = "confirmed_$alarmId"

    internal fun dePrefs(context: Context): SharedPreferences {
        val deCtx = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
        } else {
            context
        }
        return deCtx.getSharedPreferences(DE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    internal fun cePrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(CE_PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun inspectDeStorageState(context: Context): StorageState {
        val raw = try {
            deReadbackSeam(dePrefs(context))
        } catch (e: Exception) {
            return StorageState.Corrupt("DE readback threw: ${e.message}")
        } ?: return StorageState.Absent

        if (raw.isBlank()) return StorageState.Corrupt("Blank DE lineage payload")
        return try {
            val root = JSONObject(raw)
            if (!root.has("schemaVersion")) return StorageState.Corrupt("Missing schemaVersion")
            val version = root.getInt("schemaVersion")
            if (version != CURRENT_SCHEMA_VERSION) {
                return StorageState.UnsupportedSchema(version)
            }
            val arr = root.getJSONArray("records")
            val list = ArrayList<Record>(arr.length())
            val seen = HashSet<Pair<Long, Long>>()
            for (i in 0 until arr.length()) {
                val rec = Record.fromJson(arr.getJSONObject(i))
                if (rec.alarmId <= 0L || rec.scheduledAt <= 0L) {
                    return StorageState.Corrupt("Invalid key (${rec.alarmId}, ${rec.scheduledAt})")
                }
                if (!seen.add(rec.alarmId to rec.scheduledAt)) {
                    return StorageState.Corrupt("Duplicate occurrence (${rec.alarmId}, ${rec.scheduledAt})")
                }
                list.add(rec)
            }
            if (list.isEmpty()) StorageState.ValidEmpty else StorageState.Valid(list)
        } catch (e: Exception) {
            val fallbackReason = "Malformed DE lineage JSON"
            StorageState.Corrupt(e.message ?: fallbackReason)
        }
    }

    fun findRecord(context: Context, alarmId: Long, scheduledAt: Long): Record? {
        synchronized(DirectBootStorageLock.lock) {
            return when (val st = inspectDeStorageState(context)) {
                is StorageState.Valid -> st.records.firstOrNull { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
                else -> null
            }
        }
    }

    fun getPendingRetryableEnqueues(context: Context): List<Record> {
        synchronized(DirectBootStorageLock.lock) {
            return when (val st = inspectDeStorageState(context)) {
                is StorageState.Valid -> st.records.filter {
                    it.parentDismissed &&
                        it.wakeConfirmEnabled &&
                        !it.confirmed &&
                        !it.cancelled &&
                        !it.enqueueConfirmed &&
                        it.stepToken.isNotBlank() &&
                        it.nextAuthorizedRefire in 1..WakeConfirmWorker.MAX_REFIRES + 1
                }
                else -> emptyList()
            }
        }
    }

    /**
     * Commits DE WakeConfirm lineage after Room dismiss persistence has succeeded.
     * Reads CE legacy key state BEFORE acquiring [DirectBootStorageLock.lock].
     */
    fun commitDismissLineage(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        fireId: String,
        refireCount: Int,
        wakeConfirmEnabled: Boolean,
        isRecurringSchedule: Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): CommitResult {
        if (alarmId <= 0L || scheduledAt <= 0L) {
            return CommitResult.Failed("Invalid occurrence key ($alarmId, $scheduledAt)")
        }
        if (refireCount !in 0..WakeConfirmWorker.MAX_REFIRES) {
            return CommitResult.Failed("Invalid refireCount=$refireCount")
        }

        // Read CE prefs strictly outside DE lock
        val legacyKeyPreexisted = try {
            ceReadbackBooleanSeam(cePrefs(context), legacyConfirmedKey(alarmId))
        } catch (_: Exception) {
            false
        }

        synchronized(DirectBootStorageLock.lock) {
            val current = when (val st = inspectDeStorageState(context)) {
                is StorageState.Absent, is StorageState.ValidEmpty -> emptyList()
                is StorageState.Valid -> st.records
                is StorageState.Corrupt -> return CommitResult.Failed("DE storage corrupt: ${st.reason}", isReadbackMismatch = true)
                is StorageState.UnsupportedSchema -> return CommitResult.Failed("Unsupported DE schema: ${st.foundVersion}", isReadbackMismatch = true)
            }

            if (current.any { it.alarmId == alarmId && it.scheduledAt > scheduledAt }) {
                return CommitResult.Failed("Newer occurrence already exists for alarm $alarmId")
            }

            val existingIdx = current.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
            val existing = if (existingIdx >= 0) current[existingIdx] else null

            val updatedRecord = if (existing != null) {
                if (existing.confirmed || existing.cancelled) {
                    return CommitResult.Failed("Occurrence already finalized (confirmed=${existing.confirmed}, cancelled=${existing.cancelled})")
                }
                if (refireCount == 0 && existing.highestClaimedRefire == 0 && !existing.enqueueConfirmed && existing.stepToken.isNotBlank()) {
                    // Retry-safe idempotent reuse of pending primary step
                    existing.copy(
                        wakeConfirmEnabled = wakeConfirmEnabled,
                        oneShotDisabledAllowed = !isRecurringSchedule
                    )
                } else if (refireCount > 0 && existing.highestClaimedRefire <= refireCount) {
                    val newToken = if (refireCount < WakeConfirmWorker.MAX_REFIRES) UUID.randomUUID().toString() else ""
                    existing.copy(
                        wakeConfirmEnabled = wakeConfirmEnabled,
                        oneShotDisabledAllowed = !isRecurringSchedule,
                        highestClaimedRefire = refireCount,
                        nextAuthorizedRefire = refireCount + 1,
                        stepToken = newToken,
                        enqueueConfirmed = false
                    )
                } else {
                    return CommitResult.Failed("Stale or regressed dismiss refireCount=$refireCount vs highest=${existing.highestClaimedRefire}")
                }
            } else {
                val newToken = if (refireCount < WakeConfirmWorker.MAX_REFIRES) UUID.randomUUID().toString() else ""
                Record(
                    alarmId = alarmId,
                    scheduledAt = scheduledAt,
                    fireId = fireId,
                    parentDismissed = true,
                    dismissedAt = nowMillis,
                    wakeConfirmEnabled = wakeConfirmEnabled,
                    oneShotDisabledAllowed = !isRecurringSchedule,
                    nextAuthorizedRefire = refireCount + 1,
                    highestClaimedRefire = refireCount,
                    confirmed = false,
                    cancelled = false,
                    stepToken = newToken,
                    enqueueConfirmed = false,
                    legacyConfirmedKeyPreexistedAtDismiss = legacyKeyPreexisted,
                    legacyCompatSeeded = false
                )
            }

            val nextList = if (existingIdx >= 0) {
                current.toMutableList().apply { set(existingIdx, updatedRecord) }
            } else {
                val pruned = if (current.size >= MAX_CAPACITY) {
                    val terminalCandidate = current
                        .filter { it.confirmed || it.cancelled || it.nextAuthorizedRefire > WakeConfirmWorker.MAX_REFIRES }
                        .minByOrNull { it.dismissedAt }
                    if (terminalCandidate != null) {
                        current.filterNot { it.alarmId == terminalCandidate.alarmId && it.scheduledAt == terminalCandidate.scheduledAt }
                    } else {
                        return CommitResult.Failed("DE WakeConfirm capacity ($MAX_CAPACITY) exhausted with active lineages")
                    }
                } else {
                    current
                }
                pruned + updatedRecord
            }

            commitAndVerify(context, nextList)?.let { return it }
            return CommitResult.Committed(updatedRecord)
        }
    }

    fun markEnqueueConfirmed(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        stepToken: String
    ): CommitResult {
        synchronized(DirectBootStorageLock.lock) {
            val current = when (val st = inspectDeStorageState(context)) {
                is StorageState.Valid -> st.records
                is StorageState.Corrupt -> return CommitResult.Failed("DE corrupt: ${st.reason}", isReadbackMismatch = true)
                is StorageState.UnsupportedSchema -> return CommitResult.Failed("Unsupported DE schema: ${st.foundVersion}", isReadbackMismatch = true)
                else -> return CommitResult.Failed("No DE lineage record to confirm enqueue")
            }
            val idx = current.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
            if (idx < 0) return CommitResult.Failed("Missing lineage for ($alarmId, $scheduledAt)")
            val rec = current[idx]
            if (rec.confirmed || rec.cancelled) return CommitResult.Failed("Occurrence already finalized")
            if (rec.stepToken != stepToken) return CommitResult.Failed("stepToken mismatch on enqueue confirmation")

            val updated = rec.copy(enqueueConfirmed = true)
            val nextList = current.toMutableList().apply { set(idx, updated) }
            commitAndVerify(context, nextList)?.let { return it }
            return CommitResult.Committed(updated)
        }
    }

    /**
     * Authorizes [WakeConfirmWorker] execution BEFORE posting notification or polling.
     * Supports pre-S5-D legacy workers (blank [workerStepToken]) ONLY when Room dismiss proof
     * is verified outside [DirectBootStorageLock.lock].
     */
    suspend fun authorizeWorkerExecution(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        refireCount: Int,
        workerStepToken: String,
        roomDismissChecker: suspend () -> Boolean
    ): WorkerAuthResult {
        if (alarmId <= 0L || scheduledAt <= 0L) {
            return WorkerAuthResult.Rejected("Invalid occurrence key ($alarmId, $scheduledAt)")
        }
        if (refireCount !in 0..WakeConfirmWorker.MAX_REFIRES) {
            return WorkerAuthResult.Rejected("Refire count out of bounds 0..${WakeConfirmWorker.MAX_REFIRES}: $refireCount")
        }

        val initialSnapshot = synchronized(DirectBootStorageLock.lock) {
            when (val st = inspectDeStorageState(context)) {
                is StorageState.Absent, is StorageState.ValidEmpty -> emptyList<Record>() to null
                is StorageState.Valid -> st.records to null
                is StorageState.Corrupt -> null to "DE lineage corrupt: ${st.reason}"
                is StorageState.UnsupportedSchema -> null to "DE lineage unsupported schema: ${st.foundVersion}"
            }
        }
        initialSnapshot.second?.let { return WorkerAuthResult.Rejected(it) }
        val records = initialSnapshot.first!!

        if (records.any { it.alarmId == alarmId && it.scheduledAt > scheduledAt }) {
            return WorkerAuthResult.Rejected("Stale occurrence: newer occurrence exists for alarm $alarmId")
        }

        val existing = records.firstOrNull { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
        if (existing != null) {
            return validateExistingForWorker(existing, refireCount, workerStepToken)
        }

        // Absent in DE lineage store:
        if (workerStepToken.isNotBlank()) {
            return WorkerAuthResult.Rejected("Worker carries stepToken but DE lineage record is absent")
        }

        // Pre-S5-D legacy worker compat: must verify persisted Dismiss in Room OUTSIDE DE lock
        val hasRoomDismiss = try {
            roomDismissChecker()
        } catch (e: Exception) {
            return WorkerAuthResult.Rejected("Room dismiss verification threw: ${e.message}")
        }
        if (!hasRoomDismiss) {
            return WorkerAuthResult.Rejected("No committed dismiss proof in DE or Room for ($alarmId, $scheduledAt)")
        }

        // Atomically seed legacy-compat DE lineage under DE lock
        val legacyKeyPreexisted = try {
            ceReadbackBooleanSeam(cePrefs(context), legacyConfirmedKey(alarmId))
        } catch (_: Exception) {
            false
        }
        synchronized(DirectBootStorageLock.lock) {
            val fresh = when (val st = inspectDeStorageState(context)) {
                is StorageState.Absent, is StorageState.ValidEmpty -> emptyList()
                is StorageState.Valid -> st.records
                is StorageState.Corrupt -> return WorkerAuthResult.Rejected("DE corrupt: ${st.reason}")
                is StorageState.UnsupportedSchema -> return WorkerAuthResult.Rejected("Unsupported schema: ${st.foundVersion}")
            }
            if (fresh.any { it.alarmId == alarmId && it.scheduledAt > scheduledAt }) {
                return WorkerAuthResult.Rejected("Stale occurrence superseded during compat seed")
            }
            fresh.firstOrNull { it.alarmId == alarmId && it.scheduledAt == scheduledAt }?.let { raced ->
                return validateExistingForWorker(raced, refireCount, workerStepToken)
            }
            val seeded = Record(
                alarmId = alarmId,
                scheduledAt = scheduledAt,
                fireId = "alarm-$alarmId-$scheduledAt",
                parentDismissed = true,
                dismissedAt = System.currentTimeMillis(),
                wakeConfirmEnabled = true,
                oneShotDisabledAllowed = true,
                nextAuthorizedRefire = refireCount + 1,
                highestClaimedRefire = refireCount,
                confirmed = false,
                cancelled = false,
                stepToken = "",
                enqueueConfirmed = true,
                legacyConfirmedKeyPreexistedAtDismiss = legacyKeyPreexisted,
                legacyCompatSeeded = true
            )
            if (fresh.size >= MAX_CAPACITY) {
                return WorkerAuthResult.Rejected("DE lineage capacity exhausted")
            }
            commitAndVerify(context, fresh + seeded)?.let {
                return WorkerAuthResult.Rejected("Failed to persist compat DE lineage: ${it.reason}")
            }
            return WorkerAuthResult.Authorized(seeded)
        }
    }

    private fun validateExistingForWorker(
        rec: Record,
        refireCount: Int,
        workerStepToken: String
    ): WorkerAuthResult {
        if (!rec.parentDismissed || !rec.wakeConfirmEnabled) {
            return WorkerAuthResult.Rejected("Occurrence not dismissed or WakeConfirm disabled")
        }
        if (rec.confirmed) {
            return WorkerAuthResult.Rejected("Occurrence already confirmed")
        }
        if (rec.cancelled) {
            return WorkerAuthResult.Rejected("Occurrence cancelled (e.g. snoozed)")
        }
        if (refireCount != rec.highestClaimedRefire || rec.nextAuthorizedRefire != refireCount + 1) {
            return WorkerAuthResult.Rejected(
                "Refire state mismatch: worker=$refireCount, highest=${rec.highestClaimedRefire}, next=${rec.nextAuthorizedRefire}"
            )
        }
        if (rec.stepToken.isNotBlank()) {
            if (workerStepToken != rec.stepToken) {
                return WorkerAuthResult.Rejected("stepToken mismatch or blank worker token")
            }
        } else {
            // Blank stepToken in DE record is only valid for pre-S5-D compat seed or final cap check (refireCount == MAX_REFIRES)
            val isLegacyCompat = rec.legacyCompatSeeded && workerStepToken.isBlank()
            val isFinalCapCheck = refireCount == WakeConfirmWorker.MAX_REFIRES
            if (!isLegacyCompat && !isFinalCapCheck) {
                return WorkerAuthResult.Rejected("Step token already consumed or absent")
            }
        }
        return WorkerAuthResult.Authorized(rec)
    }

    /**
     * Marks an occurrence confirmed via [WakeConfirmActivity] with CE commit + readback,
     * and synchronizes DE lineage. Never writes legacy `confirmed_$alarmId`.
     */
    fun confirmOccurrence(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (alarmId <= 0L || scheduledAt <= 0L) return false

        val prefs = cePrefs(context)
        val key = confirmedKey(alarmId, scheduledAt)
        val atKey = confirmedAtKey(alarmId, scheduledAt)

        val committed = try {
            ceCommitBooleanSeam(prefs, key, atKey, nowMillis)
        } catch (_: Exception) {
            false
        }
        if (!committed) return false

        val readback = try {
            ceReadbackBooleanSeam(prefs, key)
        } catch (_: Exception) {
            false
        }
        if (!readback) return false

        synchronized(DirectBootStorageLock.lock) {
            val st = inspectDeStorageState(context)
            if (st !is StorageState.Valid) return true // CE durable confirmation succeeded
            val idx = st.records.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
            if (idx < 0) return true
            val rec = st.records[idx]
            if (rec.cancelled) return false
            val updated = rec.copy(confirmed = true, stepToken = "")
            val nextList = st.records.toMutableList().apply { set(idx, updated) }
            val fail = commitAndVerify(context, nextList)
            return fail == null
        }
    }

    /**
     * Checks occurrence confirmation, performing 4-proof crash-safe migration from legacy
     * `confirmed_$alarmId` only when ALL 4 proofs are satisfied.
     */
    suspend fun checkAndMigrateConfirmation(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        roomDismissChecker: suspend () -> Boolean,
        nowMillis: Long = System.currentTimeMillis()
    ): ConfirmationStatus {
        if (alarmId <= 0L || scheduledAt <= 0L) return ConfirmationStatus.Unconfirmed

        val deRecords = synchronized(DirectBootStorageLock.lock) {
            when (val st = inspectDeStorageState(context)) {
                is StorageState.Absent, is StorageState.ValidEmpty -> emptyList()
                is StorageState.Valid -> st.records
                is StorageState.Corrupt -> return ConfirmationStatus.StorageUnverifiable(st.reason)
                is StorageState.UnsupportedSchema -> return ConfirmationStatus.StorageUnverifiable("Unsupported schema ${st.foundVersion}")
            }
        }

        val matching = deRecords.firstOrNull { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
        if (matching?.confirmed == true) {
            return ConfirmationStatus.Confirmed
        }

        val prefs = cePrefs(context)
        val scopedKey = confirmedKey(alarmId, scheduledAt)
        val scopedConfirmed = try {
            ceReadbackBooleanSeam(prefs, scopedKey)
        } catch (_: Exception) {
            false
        }
        if (scopedConfirmed) {
            // Sync DE lineage if needed
            if (matching != null && !matching.confirmed && !matching.cancelled) {
                synchronized(DirectBootStorageLock.lock) {
                    val st = inspectDeStorageState(context)
                    if (st is StorageState.Valid) {
                        val idx = st.records.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
                        if (idx >= 0 && !st.records[idx].cancelled) {
                            val nextList = st.records.toMutableList().apply {
                                set(idx, st.records[idx].copy(confirmed = true, stepToken = ""))
                            }
                            commitAndVerify(context, nextList)
                        }
                    }
                }
            }
            // Complete interrupted cleanup if legacy key existed for this exact occurrence
            val legacyKey = legacyConfirmedKey(alarmId)
            if (matching != null &&
                !matching.legacyConfirmedKeyPreexistedAtDismiss &&
                !deRecords.any { it.alarmId == alarmId && it.scheduledAt != scheduledAt && it.dismissedAt >= matching.dismissedAt } &&
                prefs.getBoolean(legacyKey, false)
            ) {
                prefs.edit().remove(legacyKey).commit()
            }
            return ConfirmationStatus.Confirmed
        }

        // Legacy compatibility path: require ALL 4 explicit proofs
        val legacyKey = legacyConfirmedKey(alarmId)
        val legacyPresent = try {
            ceReadbackBooleanSeam(prefs, legacyKey)
        } catch (_: Exception) {
            false
        }
        if (!legacyPresent) return ConfirmationStatus.Unconfirmed

        // Proof 1: Exact primary occurrence (must be latest occurrence for alarmId)
        if (matching == null) return ConfirmationStatus.Unconfirmed
        val otherOccurrences = deRecords.filter { it.alarmId == alarmId && it.scheduledAt != scheduledAt }
        if (otherOccurrences.any { it.scheduledAt > scheduledAt }) {
            return ConfirmationStatus.Unconfirmed
        }

        // Proof 2: Persisted dismiss for this exact occurrence
        if (!matching.parentDismissed || matching.cancelled) {
            return ConfirmationStatus.Unconfirmed
        }
        val roomDismissed = if (matching.legacyCompatSeeded) {
            try {
                roomDismissChecker()
            } catch (_: Exception) {
                false
            }
        } else {
            true
        }
        if (!roomDismissed) return ConfirmationStatus.Unconfirmed

        // Proof 3: Temporal eligibility (legacy key must NOT have pre-existed prior to this dismiss)
        if (matching.legacyConfirmedKeyPreexistedAtDismiss || matching.dismissedAt <= 0L || nowMillis < matching.dismissedAt) {
            return ConfirmationStatus.Unconfirmed
        }

        // Proof 4: No other occurrence of alarmId could have written/overwritten the legacy key
        if (otherOccurrences.any { !it.confirmed && !it.cancelled } ||
            otherOccurrences.any { it.dismissedAt >= matching.dismissedAt }
        ) {
            return ConfirmationStatus.Unconfirmed
        }

        // Crash-safe migration:
        // Step 1: Commit + readback occurrence-scoped CE key FIRST
        val ceOk = try {
            ceCommitBooleanSeam(prefs, scopedKey, confirmedAtKey(alarmId, scheduledAt), nowMillis) &&
                ceReadbackBooleanSeam(prefs, scopedKey)
        } catch (_: Exception) {
            false
        }
        if (!ceOk) return ConfirmationStatus.Unconfirmed

        // Step 2: Commit + readback DE lineage confirmed = true
        val deOk = synchronized(DirectBootStorageLock.lock) {
            val st = inspectDeStorageState(context)
            if (st !is StorageState.Valid) return@synchronized false
            val idx = st.records.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
            if (idx < 0 || st.records[idx].cancelled) return@synchronized false
            val nextList = st.records.toMutableList().apply {
                set(idx, st.records[idx].copy(confirmed = true, stepToken = ""))
            }
            commitAndVerify(context, nextList) == null
        }
        if (!deOk) return ConfirmationStatus.Unconfirmed

        // Step 3: Remove legacy key ONLY AFTER both CE & DE commits verified
        prefs.edit().remove(legacyKey).commit()
        return ConfirmationStatus.Confirmed
    }

    /**
     * Atomically consumes the current WakeConfirm step and advances `highestClaimedRefire` &
     * `nextAuthorizedRefire` monotonically (1..3) with commit + readback verification.
     */
    fun consumeRefireStep(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        currentRefireCount: Int,
        workerStepToken: String
    ): RefireConsumeResult {
        val targetRefire = currentRefireCount + 1
        if (targetRefire !in 1..WakeConfirmWorker.MAX_REFIRES) {
            return RefireConsumeResult.Rejected("Target refire $targetRefire outside allowed range 1..${WakeConfirmWorker.MAX_REFIRES}")
        }

        synchronized(DirectBootStorageLock.lock) {
            val current = when (val st = inspectDeStorageState(context)) {
                is StorageState.Valid -> st.records
                is StorageState.Corrupt -> return RefireConsumeResult.Rejected("DE lineage corrupt: ${st.reason}")
                is StorageState.UnsupportedSchema -> return RefireConsumeResult.Rejected("Unsupported DE schema: ${st.foundVersion}")
                else -> return RefireConsumeResult.Rejected("No DE lineage record")
            }

            if (current.any { it.alarmId == alarmId && it.scheduledAt > scheduledAt }) {
                return RefireConsumeResult.Rejected("Newer occurrence exists for alarm $alarmId")
            }

            val idx = current.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
            if (idx < 0) return RefireConsumeResult.Rejected("Missing DE lineage for ($alarmId, $scheduledAt)")
            val rec = current[idx]

            if (!rec.parentDismissed || !rec.wakeConfirmEnabled || rec.confirmed || rec.cancelled) {
                return RefireConsumeResult.Rejected("Occurrence ineligible (dismissed=${rec.parentDismissed}, confirmed=${rec.confirmed}, cancelled=${rec.cancelled})")
            }
            if (targetRefire != rec.nextAuthorizedRefire || targetRefire <= rec.highestClaimedRefire) {
                return RefireConsumeResult.Rejected(
                    "Monotonic refire violation: target=$targetRefire, next=${rec.nextAuthorizedRefire}, highest=${rec.highestClaimedRefire}"
                )
            }
            if (rec.stepToken.isNotBlank()) {
                if (workerStepToken != rec.stepToken) {
                    return RefireConsumeResult.Rejected("Single-use stepToken mismatch or already consumed")
                }
            } else if (!rec.legacyCompatSeeded || workerStepToken.isNotBlank()) {
                return RefireConsumeResult.Rejected("No valid unconsumed stepToken")
            }

            val consumed = rec.copy(
                highestClaimedRefire = targetRefire,
                nextAuthorizedRefire = targetRefire + 1,
                stepToken = ""
            )
            val nextList = current.toMutableList().apply { set(idx, consumed) }
            commitAndVerify(context, nextList)?.let { fail ->
                return RefireConsumeResult.Rejected("DE lineage commit/readback failed: ${fail.reason}")
            }
            return RefireConsumeResult.Consumed(targetRefire, consumed)
        }
    }

    fun cancelOccurrence(context: Context, alarmId: Long, scheduledAt: Long): Boolean {
        if (alarmId <= 0L || scheduledAt <= 0L) return false
        synchronized(DirectBootStorageLock.lock) {
            val st = inspectDeStorageState(context)
            if (st !is StorageState.Valid) return false
            val idx = st.records.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
            if (idx < 0) return false
            val updated = st.records[idx].copy(cancelled = true, stepToken = "")
            val nextList = st.records.toMutableList().apply { set(idx, updated) }
            return commitAndVerify(context, nextList) == null
        }
    }

    fun cancelAllForAlarm(context: Context, alarmId: Long): Boolean {
        if (alarmId <= 0L) return false
        synchronized(DirectBootStorageLock.lock) {
            val st = inspectDeStorageState(context)
            if (st !is StorageState.Valid) return false
            var changed = false
            val nextList = st.records.map { rec ->
                if (rec.alarmId == alarmId && !rec.cancelled && !rec.confirmed) {
                    changed = true
                    rec.copy(cancelled = true, stepToken = "")
                } else {
                    rec
                }
            }
            if (!changed) return true
            return commitAndVerify(context, nextList) == null
        }
    }

    /**
     * Authorizes [AlarmService] start for a disabled `ONE_TIME` alarm ONLY when
     * WakeConfirm has an active, unconfirmed, uncancelled refire in progress (`1..3`).
     */
    fun isRefireServiceStartAuthorized(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        refireCount: Int,
        isRecurringSchedule: Boolean
    ): Boolean {
        if (alarmId <= 0L || scheduledAt <= 0L) return false
        if (refireCount !in 1..WakeConfirmWorker.MAX_REFIRES) return false
        if (isRecurringSchedule) return false

        synchronized(DirectBootStorageLock.lock) {
            val st = inspectDeStorageState(context) as? StorageState.Valid ?: return false
            if (st.records.any { it.alarmId == alarmId && it.scheduledAt > scheduledAt }) return false
            val rec = st.records.firstOrNull { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
                ?: return false
            return rec.parentDismissed &&
                rec.wakeConfirmEnabled &&
                rec.oneShotDisabledAllowed &&
                !rec.confirmed &&
                !rec.cancelled &&
                rec.highestClaimedRefire >= refireCount
        }
    }

    /**
     * Authorizes WakeConfirm refire progression for BOTH recurring and one-shot alarms
     * when Output Authority Gate reserves a refire slot.
     */
    fun isRefireAuthorizedForGate(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        refireCount: Int
    ): Boolean {
        if (alarmId <= 0L || scheduledAt <= 0L) return false
        if (refireCount !in 1..WakeConfirmWorker.MAX_REFIRES) return false

        synchronized(DirectBootStorageLock.lock) {
            val st = inspectDeStorageState(context) as? StorageState.Valid ?: return false
            if (st.records.any { it.alarmId == alarmId && it.scheduledAt > scheduledAt }) return false
            val rec = st.records.firstOrNull { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
                ?: return false
            return rec.parentDismissed &&
                rec.wakeConfirmEnabled &&
                !rec.confirmed &&
                !rec.cancelled &&
                rec.highestClaimedRefire >= refireCount
        }
    }

    /**
     * Retries WorkManager enqueue for a previously committed DE lineage record whose initial
     * enqueue failed (`enqueueConfirmed == false`), reusing its durable `stepToken`.
     * Never calls WorkManager under [DirectBootStorageLock.lock].
     */
    fun retryPendingWakeConfirmEnqueue(
        context: Context,
        alarm: com.sysadmindoc.alarmclock.data.model.Alarm,
        scheduledAt: Long
    ): Boolean {
        val rec = findRecord(context, alarm.id, scheduledAt) ?: return false
        if (!rec.parentDismissed || !rec.wakeConfirmEnabled || rec.confirmed || rec.cancelled || rec.enqueueConfirmed) {
            return false
        }
        if (rec.stepToken.isBlank() || rec.highestClaimedRefire !in 0 until WakeConfirmWorker.MAX_REFIRES) {
            return false
        }

        val plan = com.sysadmindoc.alarmclock.service.AlarmPostDismissController.wakeConfirmationPlan(
            alarm = alarm,
            fireId = rec.fireId,
            scheduledAt = rec.scheduledAt,
            refireCount = rec.highestClaimedRefire,
            stepToken = rec.stepToken
        )
        val request = androidx.work.OneTimeWorkRequestBuilder<WakeConfirmWorker>()
            .setInitialDelay(plan.delayMinutes, java.util.concurrent.TimeUnit.MINUTES)
            .setInputData(plan.inputData)
            .addTag(plan.tag)
            .build()
        val enqueued = try {
            androidx.work.WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork(
                    plan.uniqueWorkName,
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    request
                )
            true
        } catch (_: Exception) {
            false
        }
        if (!enqueued) return false
        return markEnqueueConfirmed(context, rec.alarmId, rec.scheduledAt, rec.stepToken) is CommitResult.Committed
    }

    private fun commitAndVerify(context: Context, records: List<Record>): CommitResult.Failed? {
        val root = JSONObject().apply {
            put("schemaVersion", CURRENT_SCHEMA_VERSION)
            val arr = JSONArray()
            records.forEach { arr.put(it.toJson()) }
            put("records", arr)
        }
        val encoded = root.toString()
        val prefs = dePrefs(context)
        val committed = try {
            deCommitSeam(prefs, encoded)
        } catch (e: Exception) {
            return CommitResult.Failed("deCommitSeam threw: ${e.message}")
        }
        if (!committed) {
            return CommitResult.Failed("deCommitSeam returned false")
        }
        val readback = try {
            deReadbackSeam(prefs)
        } catch (e: Exception) {
            return CommitResult.Failed("deReadbackSeam threw: ${e.message}", isReadbackMismatch = true)
        }
        if (readback != encoded) {
            return CommitResult.Failed("DE lineage readback mismatch after commit", isReadbackMismatch = true)
        }
        return null
    }
}
