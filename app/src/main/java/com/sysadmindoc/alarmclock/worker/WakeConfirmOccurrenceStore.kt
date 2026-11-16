package com.sysadmindoc.alarmclock.worker

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.VisibleForTesting
import androidx.core.os.UserManagerCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.directboot.DirectBootStorageLock
import com.sysadmindoc.alarmclock.domain.OwnerWorkIdentity
import com.sysadmindoc.alarmclock.domain.SupportingWorkType
import com.sysadmindoc.alarmclock.service.AlarmPostDismissController
import com.sysadmindoc.alarmclock.service.WakeConfirmationPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

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

    private val recoveryMutex = Mutex()

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
        val legacyCompatSeeded: Boolean = false,
        val scheduledDueAtMillis: Long = 0L
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
            put("scheduledDueAtMillis", scheduledDueAtMillis)
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
                legacyCompatSeeded = json.optBoolean("legacyCompatSeeded", false),
                scheduledDueAtMillis = json.optLong("scheduledDueAtMillis", 0L)
            )
        }
    }

    internal sealed class EnqueueVerificationResult {
        object Enqueued : EnqueueVerificationResult()
        object Reused : EnqueueVerificationResult()
        data class Failed(val reason: String) : EnqueueVerificationResult()
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

    sealed class ConfirmOccurrenceResult {
        data class Confirmed(val deUpdated: Boolean) : ConfirmOccurrenceResult()
        data class CeConfirmedDePendingReconciliation(val reason: String) : ConfirmOccurrenceResult()
        data class Rejected(val reason: String) : ConfirmOccurrenceResult()
    }

    sealed class LegacyOccurrenceResolution {
        data class Resolved(val scheduledAt: Long, val fireId: String) : LegacyOccurrenceResolution()
        data class Rejected(val reason: String) : LegacyOccurrenceResolution()
    }

    enum class RecoveryTrigger {
        REBOOT,
        UNLOCK,
        PROCESS_DEATH,
        HEALTH_CHECK
    }

    data class RecoveryResult(
        val trigger: RecoveryTrigger,
        val inspectedCount: Int = 0,
        val enqueuedCount: Int = 0,
        val reusedCount: Int = 0,
        val skippedCount: Int = 0,
        val failedCount: Int = 0
    )

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
                is StorageState.Valid -> st.records.filter { rec ->
                    val hasNewerOccurrence = st.records.any { other ->
                        other.alarmId == rec.alarmId && other.scheduledAt > rec.scheduledAt
                    }
                    !hasNewerOccurrence &&
                        rec.parentDismissed &&
                        rec.wakeConfirmEnabled &&
                        !rec.confirmed &&
                        !rec.cancelled &&
                        !rec.enqueueConfirmed &&
                        rec.stepToken.isNotBlank() &&
                        rec.highestClaimedRefire in 0 until WakeConfirmWorker.MAX_REFIRES &&
                        rec.nextAuthorizedRefire in 1..WakeConfirmWorker.MAX_REFIRES
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
        nowMillis: Long = System.currentTimeMillis(),
        delayMinutes: Long = 0L,
        scheduledDueAtMillis: Long = if (delayMinutes > 0L && nowMillis > 0L) {
            nowMillis + delayMinutes * 60_000L
        } else {
            0L
        }
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
                        oneShotDisabledAllowed = !isRecurringSchedule,
                        scheduledDueAtMillis = if (existing.scheduledDueAtMillis > 0L) existing.scheduledDueAtMillis else scheduledDueAtMillis
                    )
                } else if (refireCount > 0 && existing.highestClaimedRefire == refireCount && !existing.enqueueConfirmed && existing.stepToken.isNotBlank()) {
                    // Retry-safe idempotent reuse of pending refire step
                    existing.copy(
                        wakeConfirmEnabled = wakeConfirmEnabled,
                        oneShotDisabledAllowed = !isRecurringSchedule,
                        scheduledDueAtMillis = if (existing.scheduledDueAtMillis > 0L) existing.scheduledDueAtMillis else scheduledDueAtMillis
                    )
                } else if (
                    refireCount > 0 &&
                    (existing.highestClaimedRefire < refireCount ||
                        (existing.highestClaimedRefire == refireCount && !existing.enqueueConfirmed && existing.stepToken.isBlank()))
                ) {
                    val newToken = if (refireCount < WakeConfirmWorker.MAX_REFIRES) UUID.randomUUID().toString() else ""
                    existing.copy(
                        dismissedAt = if (nowMillis > 0L) nowMillis else existing.dismissedAt,
                        wakeConfirmEnabled = wakeConfirmEnabled,
                        oneShotDisabledAllowed = !isRecurringSchedule,
                        highestClaimedRefire = refireCount,
                        nextAuthorizedRefire = refireCount + 1,
                        stepToken = newToken,
                        enqueueConfirmed = false,
                        scheduledDueAtMillis = if (existing.highestClaimedRefire == refireCount && existing.scheduledDueAtMillis > 0L) {
                            existing.scheduledDueAtMillis
                        } else {
                            scheduledDueAtMillis
                        }
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
                    legacyCompatSeeded = false,
                    scheduledDueAtMillis = scheduledDueAtMillis
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
     *
     * Distinguishes CE durable confirmation from DE lineage synchronization:
     * - Returns [ConfirmOccurrenceResult.Rejected] if key invalid, cancelled, or CE commit/readback fails.
     * - Returns [ConfirmOccurrenceResult.CeConfirmedDePendingReconciliation] if CE succeeded
     *   but DE storage was Corrupt/UnsupportedSchema or DE commit/readback failed (CE is preserved).
     * - Returns [ConfirmOccurrenceResult.Confirmed] only when CE succeeded and DE was updated
     *   (or DE legitimately has no record for this occurrence).
     */
    fun confirmOccurrenceDetailed(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): ConfirmOccurrenceResult {
        if (alarmId <= 0L || scheduledAt <= 0L) {
            return ConfirmOccurrenceResult.Rejected("Invalid occurrence key ($alarmId, $scheduledAt)")
        }

        // Pre-check DE state outside CE write so a cancelled occurrence never writes CE confirmation
        val preCheckRejection: ConfirmOccurrenceResult.Rejected? = synchronized(DirectBootStorageLock.lock) {
            when (val st = inspectDeStorageState(context)) {
                is StorageState.Valid -> {
                    val rec = st.records.firstOrNull { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
                    if (rec?.cancelled == true) {
                        ConfirmOccurrenceResult.Rejected("Occurrence ($alarmId, $scheduledAt) is cancelled")
                    } else {
                        null
                    }
                }
                else -> null
            }
        }
        if (preCheckRejection != null) return preCheckRejection

        val prefs = cePrefs(context)
        val key = confirmedKey(alarmId, scheduledAt)
        val atKey = confirmedAtKey(alarmId, scheduledAt)

        val committed = try {
            ceCommitBooleanSeam(prefs, key, atKey, nowMillis)
        } catch (_: Exception) {
            false
        }
        if (!committed) {
            return ConfirmOccurrenceResult.Rejected("CE confirmation commit failed")
        }

        val readback = try {
            ceReadbackBooleanSeam(prefs, key)
        } catch (_: Exception) {
            false
        }
        if (!readback) {
            return ConfirmOccurrenceResult.Rejected("CE confirmation readback failed")
        }

        var removeCeDueToConcurrentCancel = false
        val deResult = synchronized(DirectBootStorageLock.lock) {
            when (val st = inspectDeStorageState(context)) {
                is StorageState.Absent, is StorageState.ValidEmpty ->
                    ConfirmOccurrenceResult.Confirmed(deUpdated = false)
                is StorageState.Corrupt ->
                    ConfirmOccurrenceResult.CeConfirmedDePendingReconciliation("DE storage corrupt: ${st.reason}")
                is StorageState.UnsupportedSchema ->
                    ConfirmOccurrenceResult.CeConfirmedDePendingReconciliation("Unsupported DE schema: ${st.foundVersion}")
                is StorageState.Valid -> {
                    val idx = st.records.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
                    if (idx < 0) {
                        ConfirmOccurrenceResult.Confirmed(deUpdated = false)
                    } else {
                        val rec = st.records[idx]
                        if (rec.cancelled) {
                            removeCeDueToConcurrentCancel = true
                            ConfirmOccurrenceResult.Rejected("Occurrence ($alarmId, $scheduledAt) cancelled concurrently")
                        } else {
                            val updated = rec.copy(confirmed = true, stepToken = "")
                            val nextList = st.records.toMutableList().apply { set(idx, updated) }
                            val fail = commitAndVerify(context, nextList)
                            if (fail == null) {
                                ConfirmOccurrenceResult.Confirmed(deUpdated = true)
                            } else {
                                ConfirmOccurrenceResult.CeConfirmedDePendingReconciliation(fail.reason)
                            }
                        }
                    }
                }
            }
        }
        if (removeCeDueToConcurrentCancel) {
            runCatching { prefs.edit().remove(key).remove(atKey).commit() }
        }
        return deResult
    }

    /**
     * Legacy boolean wrapper over [confirmOccurrenceDetailed].
     * Returns `true` ONLY when [ConfirmOccurrenceResult.Confirmed] (never returns `true` on
     * Corrupt, UnsupportedSchema, DE commit failure, DE readback mismatch, or cancelled occurrence).
     */
    fun confirmOccurrence(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = confirmOccurrenceDetailed(context, alarmId, scheduledAt, nowMillis) is ConfirmOccurrenceResult.Confirmed

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
        if (matching?.cancelled == true) {
            return ConfirmationStatus.Unconfirmed
        }
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
            // Reconcile DE lineage if needed; NEVER ignore DE commit/readback failure or Corrupt/UnsupportedSchema
            if (matching != null && !matching.confirmed) {
                val deSyncFailure: ConfirmationStatus? = synchronized(DirectBootStorageLock.lock) {
                    when (val st = inspectDeStorageState(context)) {
                        is StorageState.Corrupt -> ConfirmationStatus.StorageUnverifiable(st.reason)
                        is StorageState.UnsupportedSchema -> ConfirmationStatus.StorageUnverifiable("Unsupported schema ${st.foundVersion}")
                        is StorageState.Absent, is StorageState.ValidEmpty ->
                            ConfirmationStatus.StorageUnverifiable("DE lineage missing during confirmation reconciliation")
                        is StorageState.Valid -> {
                            val idx = st.records.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
                            if (idx < 0) {
                                ConfirmationStatus.StorageUnverifiable("Missing DE record during confirmation reconciliation")
                            } else if (st.records[idx].cancelled) {
                                ConfirmationStatus.Unconfirmed
                            } else {
                                val nextList = st.records.toMutableList().apply {
                                    set(idx, st.records[idx].copy(confirmed = true, stepToken = ""))
                                }
                                val fail = commitAndVerify(context, nextList)
                                if (fail != null) ConfirmationStatus.StorageUnverifiable(fail.reason) else null
                            }
                        }
                    }
                }
                if (deSyncFailure != null) return deSyncFailure
            }
            // Complete interrupted cleanup if legacy key existed for this exact occurrence
            val legacyKey = legacyConfirmedKey(alarmId)
            val legacyPresentNow = try {
                ceReadbackBooleanSeam(prefs, legacyKey)
            } catch (_: Exception) {
                false
            }
            if (matching != null &&
                !matching.legacyConfirmedKeyPreexistedAtDismiss &&
                !deRecords.any { it.alarmId == alarmId && it.scheduledAt != scheduledAt && it.dismissedAt >= matching.dismissedAt } &&
                legacyPresentNow
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
        val deStep2Status: ConfirmationStatus? = synchronized(DirectBootStorageLock.lock) {
            when (val st = inspectDeStorageState(context)) {
                is StorageState.Corrupt -> ConfirmationStatus.StorageUnverifiable(st.reason)
                is StorageState.UnsupportedSchema -> ConfirmationStatus.StorageUnverifiable("Unsupported schema ${st.foundVersion}")
                is StorageState.Absent, is StorageState.ValidEmpty ->
                    ConfirmationStatus.StorageUnverifiable("DE lineage missing during legacy migration")
                is StorageState.Valid -> {
                    val idx = st.records.indexOfFirst { it.alarmId == alarmId && it.scheduledAt == scheduledAt }
                    if (idx < 0) {
                        ConfirmationStatus.StorageUnverifiable("Missing DE record during legacy migration")
                    } else if (st.records[idx].cancelled) {
                        ConfirmationStatus.Unconfirmed
                    } else {
                        val nextList = st.records.toMutableList().apply {
                            set(idx, st.records[idx].copy(confirmed = true, stepToken = ""))
                        }
                        val fail = commitAndVerify(context, nextList)
                        if (fail != null) ConfirmationStatus.StorageUnverifiable(fail.reason) else null
                    }
                }
            }
        }
        if (deStep2Status != null) return deStep2Status

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
                stepToken = "",
                enqueueConfirmed = false,
                scheduledDueAtMillis = 0L
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
     * Resolves missing/zero `scheduledAt` for legacy pre-S5-D workers ONLY when a single
     * unambiguous occurrence can be proven via DE lineage or persisted Room Dismiss records.
     * Fails closed on 0 candidates, >= 2 ambiguous candidates, mismatch, or corrupt DE storage.
     */
    suspend fun resolveLegacyMissingScheduledAt(
        context: Context,
        alarmId: Long,
        workerFireId: String,
        workerStepToken: String,
        roomDismissedTimesFetcher: suspend (Long) -> List<Long>
    ): LegacyOccurrenceResolution {
        if (alarmId <= 0L) {
            return LegacyOccurrenceResolution.Rejected("Invalid alarmId=$alarmId")
        }

        val deSnapshot: Pair<List<Record>?, String?> = synchronized(DirectBootStorageLock.lock) {
            when (val st = inspectDeStorageState(context)) {
                is StorageState.Absent, is StorageState.ValidEmpty -> emptyList<Record>() to null
                is StorageState.Valid -> st.records to null
                is StorageState.Corrupt -> null to "DE lineage corrupt: ${st.reason}"
                is StorageState.UnsupportedSchema -> null to "Unsupported DE schema: ${st.foundVersion}"
            }
        }
        deSnapshot.second?.let { return LegacyOccurrenceResolution.Rejected(it) }
        val forAlarm = deSnapshot.first!!.filter { it.alarmId == alarmId }
        if (forAlarm.isNotEmpty()) {
            val active = forAlarm.filter {
                it.parentDismissed && it.wakeConfirmEnabled && !it.confirmed && !it.cancelled
            }
            if (active.size != 1) {
                return LegacyOccurrenceResolution.Rejected(
                    "Expected 1 active DE record for alarm $alarmId, found ${active.size}"
                )
            }
            val candidate = active.single()
            if (forAlarm.any { it.scheduledAt > candidate.scheduledAt }) {
                return LegacyOccurrenceResolution.Rejected("Active DE record superseded by newer occurrence")
            }
            if (workerFireId.isNotBlank() && candidate.fireId.isNotBlank() && workerFireId != candidate.fireId) {
                return LegacyOccurrenceResolution.Rejected("Legacy worker fireId mismatch")
            }
            if (candidate.stepToken.isNotBlank() && workerStepToken.isNotBlank() && workerStepToken != candidate.stepToken) {
                return LegacyOccurrenceResolution.Rejected("Legacy worker stepToken mismatch")
            }
            val resolvedFireId = candidate.fireId.ifBlank {
                AlarmIncidentEvent.fireIdFor(alarmId, candidate.scheduledAt)
            }
            return LegacyOccurrenceResolution.Resolved(candidate.scheduledAt, resolvedFireId)
        }

        // DE lineage absent for this alarmId: pre-S5-D worker must have blank stepToken and Room dismiss proof
        if (workerStepToken.isNotBlank()) {
            return LegacyOccurrenceResolution.Rejected("Worker carries stepToken while DE lineage is absent")
        }
        val distinctTimes = try {
            roomDismissedTimesFetcher(alarmId).filter { it > 0L }.distinct()
        } catch (e: Exception) {
            return LegacyOccurrenceResolution.Rejected("Room dismiss lookup failed: ${e.message}")
        }
        val filteredByFireId = if (workerFireId.isNotBlank()) {
            distinctTimes.filter { ts -> AlarmIncidentEvent.fireIdFor(alarmId, ts) == workerFireId }
        } else {
            distinctTimes
        }
        if (filteredByFireId.size != 1) {
            return LegacyOccurrenceResolution.Rejected(
                "Cannot uniquely prove single dismissed occurrence in Room (candidates=${filteredByFireId.size})"
            )
        }
        val resolvedScheduledAt = filteredByFireId.single()
        val resolvedFireId = workerFireId.ifBlank {
            AlarmIncidentEvent.fireIdFor(alarmId, resolvedScheduledAt)
        }
        return LegacyOccurrenceResolution.Resolved(resolvedScheduledAt, resolvedFireId)
    }

    internal fun occurrenceTag(alarmId: Long, scheduledAt: Long): String =
        OwnerWorkIdentity.occurrenceUniqueName(alarmId, scheduledAt, SupportingWorkType.WAKE_CONFIRM)

    internal fun resolveOriginalDueAtMillis(
        rec: Record,
        alarm: com.sysadmindoc.alarmclock.data.model.Alarm
    ): Long? {
        if (rec.scheduledDueAtMillis > 0L) {
            if (rec.dismissedAt > 0L && rec.scheduledDueAtMillis < rec.dismissedAt) {
                return null
            }
            return rec.scheduledDueAtMillis
        }
        if (rec.legacyCompatSeeded || rec.dismissedAt <= 0L) {
            return null
        }
        val delayMs = alarm.wakeConfirmDelayMinutes.coerceAtLeast(1).toLong() * 60_000L
        return rec.dismissedAt + delayMs
    }

    private fun workInfoMatchesOccurrence(
        info: WorkInfo,
        plan: WakeConfirmationPlan,
        alarmId: Long,
        scheduledAt: Long,
        expectedRequestId: java.util.UUID? = null
    ): Boolean {
        if (info.state == WorkInfo.State.CANCELLED) return false
        if (expectedRequestId == null) {
            if (info.state.isFinished) return false
        } else {
            if (info.id != expectedRequestId && info.state.isFinished) return false
        }
        if (!info.tags.contains(plan.tag)) return false
        if (!info.tags.contains(WakeConfirmWorker::class.java.name)) return false
        val expectedOccTag = occurrenceTag(alarmId, scheduledAt)
        val occTags = info.tags.filter { it.startsWith("acx_occ_${SupportingWorkType.WAKE_CONFIRM.id}_") }
        return occTags.isEmpty() || occTags.contains(expectedOccTag)
    }

    internal suspend fun enqueueAndVerifyWakeConfirmWork(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        plan: WakeConfirmationPlan,
        initialDelayMs: Long,
        policy: ExistingWorkPolicy,
        reuseExistingIfPresent: Boolean
    ): EnqueueVerificationResult {
        check(!Thread.holdsLock(DirectBootStorageLock.lock)) {
            "WorkManager MUST NOT be accessed while holding DirectBootStorageLock.lock"
        }
        return withContext(Dispatchers.IO) {
            val wm = try {
                WorkManager.getInstance(context.applicationContext)
            } catch (e: Exception) {
                return@withContext EnqueueVerificationResult.Failed("WorkManager.getInstance failed: ${e.message}")
            }

            if (reuseExistingIfPresent) {
                val existingInfos = try {
                    wm.getWorkInfosForUniqueWork(plan.uniqueWorkName).get()
                } catch (e: Exception) {
                    return@withContext EnqueueVerificationResult.Failed("WorkManager lookup failed: ${e.message}")
                }
                if (existingInfos.any { workInfoMatchesOccurrence(it, plan, alarmId, scheduledAt) }) {
                    return@withContext EnqueueVerificationResult.Reused
                }
            }

            val request = OneTimeWorkRequestBuilder<WakeConfirmWorker>()
                .setInitialDelay(initialDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setInputData(plan.inputData)
                .addTag(plan.tag)
                .addTag(occurrenceTag(alarmId, scheduledAt))
                .build()

            val opState = try {
                val operation = wm.enqueueUniqueWork(plan.uniqueWorkName, policy, request)
                operation.result.get()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withContext EnqueueVerificationResult.Failed("WorkManager enqueue operation failed: ${e.message}")
            }

            if (opState !is Operation.State.SUCCESS) {
                return@withContext EnqueueVerificationResult.Failed("WorkManager operation non-success state: $opState")
            }

            val verifiedInfos = try {
                wm.getWorkInfosForUniqueWork(plan.uniqueWorkName).get()
            } catch (e: Exception) {
                return@withContext EnqueueVerificationResult.Failed("WorkManager post-enqueue lookup failed: ${e.message}")
            }

            if (verifiedInfos.none { workInfoMatchesOccurrence(it, plan, alarmId, scheduledAt, expectedRequestId = request.id) }) {
                return@withContext EnqueueVerificationResult.Failed(
                    "WorkInfo identity mismatch for ${plan.uniqueWorkName}"
                )
            }

            EnqueueVerificationResult.Enqueued
        }
    }

    /**
     * Retries WorkManager enqueue for a previously committed DE lineage record whose initial
     * enqueue failed (`enqueueConfirmed == false`), reusing its durable `stepToken`.
     * Inspects existing WorkManager occurrence unique work BEFORE enqueueing so active jobs are reused.
     * Never calls WorkManager under [DirectBootStorageLock.lock] or on the Main thread.
     */
    suspend fun retryPendingWakeConfirmEnqueue(
        context: Context,
        alarm: com.sysadmindoc.alarmclock.data.model.Alarm,
        scheduledAt: Long,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean = recoveryMutex.withLock {
        val candidate: Record? = synchronized(DirectBootStorageLock.lock) {
            val st = inspectDeStorageState(context) as? StorageState.Valid ?: return@synchronized null
            if (st.records.any { it.alarmId == alarm.id && it.scheduledAt > scheduledAt }) {
                return@synchronized null
            }
            st.records.firstOrNull { it.alarmId == alarm.id && it.scheduledAt == scheduledAt }
        }
        val rec = candidate ?: return@withLock false
        if (!rec.parentDismissed || !rec.wakeConfirmEnabled || rec.confirmed || rec.cancelled || rec.enqueueConfirmed) {
            return@withLock false
        }
        if (rec.stepToken.isBlank() ||
            rec.highestClaimedRefire !in 0 until WakeConfirmWorker.MAX_REFIRES ||
            rec.nextAuthorizedRefire !in 1..WakeConfirmWorker.MAX_REFIRES
        ) {
            return@withLock false
        }

        val originalDueAtMs = resolveOriginalDueAtMillis(rec, alarm) ?: return@withLock false
        val remainingDelayMs = (originalDueAtMs - nowMillis).coerceAtLeast(0L)

        val plan = AlarmPostDismissController.wakeConfirmationPlan(
            alarm = alarm,
            fireId = rec.fireId.ifBlank { AlarmIncidentEvent.fireIdFor(alarm.id, rec.scheduledAt) },
            scheduledAt = rec.scheduledAt,
            refireCount = rec.highestClaimedRefire,
            stepToken = rec.stepToken
        )

        val enqueueRes = enqueueAndVerifyWakeConfirmWork(
            context = context,
            alarmId = rec.alarmId,
            scheduledAt = rec.scheduledAt,
            plan = plan,
            initialDelayMs = remainingDelayMs,
            policy = ExistingWorkPolicy.KEEP,
            reuseExistingIfPresent = true
        )
        if (enqueueRes is EnqueueVerificationResult.Failed) {
            return@withLock false
        }

        markEnqueueConfirmed(context, rec.alarmId, rec.scheduledAt, rec.stepToken) is CommitResult.Committed
    }

    /**
     * Bounded recovery pass for pending WakeConfirm enqueues (`enqueueConfirmed == false`).
     * Invoked from existing recovery entrypoints ([BootRescheduleWorker], [AlarmHealthWorker],
     * [com.sysadmindoc.alarmclock.AlarmClockApp]) with explicit [RecoveryTrigger] distinction.
     *
     * Contracts:
     * - Serialized via [recoveryMutex] so concurrent App / Boot / Health triggers never duplicate work.
     * - Defers cleanly when CE storage is still locked ([UserManagerCompat.isUserUnlocked] == false).
     * - Verifies Room Dismiss proof and CE/DE confirmation state strictly OUTSIDE [DirectBootStorageLock.lock].
     * - Never holds DE or Room locks while inspecting/calling WorkManager, and never blocks Main thread.
     * - Restores remaining delay relative to original due time (0ms if elapsed; fails closed if unknown).
     * - Reuses existing unfinished occurrence unique work without replacing or altering its schedule.
     * - Awaits WorkManager [Operation] completion and verifies [WorkInfo] identity before marking confirmed.
     */
    suspend fun recoverPendingEnqueues(
        context: Context,
        alarmRepository: AlarmRepository,
        trigger: RecoveryTrigger,
        alarmIncidentRepository: AlarmIncidentRepository? = null,
        nowMillis: Long = System.currentTimeMillis()
    ): RecoveryResult = recoveryMutex.withLock {
        if (!UserManagerCompat.isUserUnlocked(context)) {
            return@withLock RecoveryResult(trigger = trigger)
        }

        val pending = getPendingRetryableEnqueues(context)
        if (pending.isEmpty()) {
            return@withLock RecoveryResult(trigger = trigger)
        }

        var enqueued = 0
        var reused = 0
        var skipped = 0
        var failed = 0

        for (rec in pending) {
            val alarm = try {
                alarmRepository.getById(rec.alarmId)
            } catch (_: Exception) {
                null
            }
            if (alarm == null || !alarm.wakeConfirmEnabled || (!alarm.isEnabled && alarm.isRecurringSchedule)) {
                skipped++
                continue
            }

            val roomDismissChecker: suspend () -> Boolean = {
                val db = alarmRepository.database
                val eventCount = db.alarmEventDao().countDismissedByOccurrence(rec.alarmId, rec.scheduledAt)
                val incidentCount = db.alarmIncidentEventDao().countDismissedIncidentsByOccurrence(rec.alarmId, rec.scheduledAt)
                eventCount > 0 || incidentCount > 0
            }

            val hasRoomDismiss = try {
                roomDismissChecker()
            } catch (_: Exception) {
                false
            }
            if (!hasRoomDismiss) {
                skipped++
                continue
            }

            when (
                checkAndMigrateConfirmation(
                    context = context,
                    alarmId = rec.alarmId,
                    scheduledAt = rec.scheduledAt,
                    roomDismissChecker = roomDismissChecker
                )
            ) {
                is ConfirmationStatus.Confirmed -> {
                    skipped++
                    continue
                }
                is ConfirmationStatus.StorageUnverifiable -> {
                    failed++
                    continue
                }
                is ConfirmationStatus.Unconfirmed -> Unit
            }

            // Re-verify fresh DE record is still non-stale, non-cancelled, unconfirmed, and pending
            val freshValid: Record? = synchronized(DirectBootStorageLock.lock) {
                val st = inspectDeStorageState(context) as? StorageState.Valid ?: return@synchronized null
                if (st.records.any { it.alarmId == rec.alarmId && it.scheduledAt > rec.scheduledAt }) {
                    return@synchronized null
                }
                st.records.firstOrNull { it.alarmId == rec.alarmId && it.scheduledAt == rec.scheduledAt }
                    ?.takeIf {
                        it.parentDismissed &&
                            it.wakeConfirmEnabled &&
                            !it.confirmed &&
                            !it.cancelled &&
                            !it.enqueueConfirmed &&
                            it.stepToken == rec.stepToken &&
                            it.stepToken.isNotBlank() &&
                            it.highestClaimedRefire in 0 until WakeConfirmWorker.MAX_REFIRES &&
                            it.nextAuthorizedRefire in 1..WakeConfirmWorker.MAX_REFIRES
                    }
            }
            if (freshValid == null) {
                skipped++
                continue
            }

            val originalDueAtMs = resolveOriginalDueAtMillis(freshValid, alarm)
            if (originalDueAtMs == null) {
                failed++
                continue
            }
            val remainingDelayMs = (originalDueAtMs - nowMillis).coerceAtLeast(0L)

            val fireId = freshValid.fireId.ifBlank {
                AlarmIncidentEvent.fireIdFor(alarm.id, freshValid.scheduledAt)
            }
            val plan = AlarmPostDismissController.wakeConfirmationPlan(
                alarm = alarm,
                fireId = fireId,
                scheduledAt = freshValid.scheduledAt,
                refireCount = freshValid.highestClaimedRefire,
                stepToken = freshValid.stepToken
            )

            // WorkManager inspection & enqueue strictly outside DE/Room locks and off Main thread
            when (
                enqueueAndVerifyWakeConfirmWork(
                    context = context,
                    alarmId = freshValid.alarmId,
                    scheduledAt = freshValid.scheduledAt,
                    plan = plan,
                    initialDelayMs = remainingDelayMs,
                    policy = ExistingWorkPolicy.KEEP,
                    reuseExistingIfPresent = true
                )
            ) {
                is EnqueueVerificationResult.Reused -> {
                    val markRes = markEnqueueConfirmed(context, freshValid.alarmId, freshValid.scheduledAt, freshValid.stepToken)
                    if (markRes is CommitResult.Committed) {
                        reused++
                        runCatching {
                            alarmIncidentRepository?.record(
                                alarmId = freshValid.alarmId,
                                fireId = fireId,
                                scheduledAt = freshValid.scheduledAt,
                                type = AlarmIncidentEvent.TYPE_WAKE_CONFIRM,
                                status = AlarmIncidentEvent.STATUS_SUCCEEDED,
                                reasonCode = "WAKE_CONFIRM_RECOVERY_REUSED_${trigger.name}",
                                source = "WakeConfirmRecovery"
                            )
                        }
                    } else {
                        failed++
                    }
                }
                is EnqueueVerificationResult.Enqueued -> {
                    val markRes = markEnqueueConfirmed(context, freshValid.alarmId, freshValid.scheduledAt, freshValid.stepToken)
                    if (markRes is CommitResult.Committed) {
                        enqueued++
                        runCatching {
                            alarmIncidentRepository?.record(
                                alarmId = freshValid.alarmId,
                                fireId = fireId,
                                scheduledAt = freshValid.scheduledAt,
                                type = AlarmIncidentEvent.TYPE_WAKE_CONFIRM,
                                status = AlarmIncidentEvent.STATUS_SUCCEEDED,
                                reasonCode = "WAKE_CONFIRM_RECOVERY_ENQUEUED_${trigger.name}",
                                source = "WakeConfirmRecovery"
                            )
                        }
                    } else {
                        failed++
                    }
                }
                is EnqueueVerificationResult.Failed -> {
                    failed++
                }
            }
        }

        RecoveryResult(
            trigger = trigger,
            inspectedCount = pending.size,
            enqueuedCount = enqueued,
            reusedCount = reused,
            skippedCount = skipped,
            failedCount = failed
        )
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
