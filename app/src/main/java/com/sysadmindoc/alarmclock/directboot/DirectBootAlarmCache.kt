package com.sysadmindoc.alarmclock.directboot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.text.format.DateFormat
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.util.AlarmTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

object DirectBootAlarmCache {
    private const val TAG = "DirectBootAlarmCache"
    private const val PREFS_NAME = "direct_boot_next_alarm"
    private const val KEY_SCHEMA_VERSION = "schema_version"
    private const val KEY_ALARM_ID = "alarm_id"
    private const val KEY_TRIGGER_TIME = "trigger_time"
    private const val KEY_LABEL = "label"
    private const val KEY_TIME_LABEL = "time_label"
    private const val KEY_PLAY_DEFAULT_SOUND = "play_default_sound"
    private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
    private const val KEY_UPDATED_AT = "updated_at"
    private const val KEY_TIMEZONE_POLICY = "timezone_policy"
    private const val KEY_FIXED_TIMEZONE_ID = "fixed_timezone_id"
    private const val KEY_FIRED_ALARM_ID = "fired_alarm_id"
    private const val KEY_FIRED_TRIGGER_TIME = "fired_trigger_time"
    private const val KEY_FIRED_AT = "fired_at"
    private const val DIRECT_BOOT_SHOW_REQUEST_CODE = 91_700

    const val KEY_QUEUE_PAYLOAD = "direct_boot_queue_payload"
    const val QUEUE_MAX_CAPACITY = 5

    fun saveIfEarlier(
        context: Context,
        alarm: Alarm,
        triggerTime: Long,
        now: Long = System.currentTimeMillis()
    ) {
        if (triggerTime <= now || alarm.id <= 0L) return

        val snapshot = DirectBootAlarmSnapshot.fromAlarm(
            alarm = alarm,
            triggerTime = triggerTime,
            timeLabel = formatTriggerTime(context, triggerTime),
            now = now
        )
        synchronized(DirectBootStorageLock.lock) {
            val current = readLocked(context)
            if (current == null ||
                !current.isSchedulable(now) ||
                current.alarmId == snapshot.alarmId ||
                snapshot.triggerTime < current.triggerTime
            ) {
                writeLocked(context, snapshot)
            }
        }
    }

    fun clear(context: Context) {
        synchronized(DirectBootStorageLock.lock) {
            clearLocked(context)
        }
    }

    private fun clearLocked(context: Context) {
        prefs(context).edit()
            .remove(KEY_SCHEMA_VERSION)
            .remove(KEY_ALARM_ID)
            .remove(KEY_TRIGGER_TIME)
            .remove(KEY_LABEL)
            .remove(KEY_TIME_LABEL)
            .remove(KEY_PLAY_DEFAULT_SOUND)
            .remove(KEY_VIBRATION_ENABLED)
            .remove(KEY_UPDATED_AT)
            .remove(KEY_TIMEZONE_POLICY)
            .remove(KEY_FIXED_TIMEZONE_ID)
            .commit()
    }

    fun removeIfMatches(context: Context, alarmId: Long) {
        removeIfMatchesWithResult(context, alarmId)
    }

    /**
     * C7-R1 Slice 0 cancellation persistence & snapshot-drift containment.
     *
     * - Preserves V1 Snapshot, Fired Witness, and V2 Stores when OS cancellation returns
     *   [DirectBootFallbackCancelResult.CancelFailedUnknown].
     * - After OS cancellation confirms [DirectBootFallbackCancelResult.isCancelledOrAbsent],
     *   re-reads under [DirectBootStorageLock.lock] and clears ONLY when the full
     *   [DirectBootAlarmSnapshot] equals the pre-IPC snapshot (not merely matching alarmId).
     * - Known residual risk (KNO-75 R2 deferred): Legacy PendingIntents for the same alarmId
     *   share `(data=null, requestCode=alarmId.toInt())`, so cross-IPC A/B OS PendingIntent
     *   cancellation requires R2 Occurrence Fence + URI-scoped PendingIntent cutover.
     */
    fun removeIfMatchesWithResult(
        context: Context,
        alarmId: Long
    ): DirectBootFallbackCancelResult {
        val protectedContext = protectedContext(context)
        val preSnapshot = synchronized(DirectBootStorageLock.lock) {
            readLocked(protectedContext)?.takeIf { it.alarmId == alarmId }
        } ?: return DirectBootFallbackCancelResult.NoExistingPendingIntent(alarmId)

        val cancelResult = cancelScheduledFallbackWithResult(protectedContext, alarmId)
        if (cancelResult is DirectBootFallbackCancelResult.CancelFailedUnknown) {
            Log.w(
                TAG,
                "Preserving Direct-Boot snapshot for alarm $alarmId after CancelFailedUnknown (OS state UNKNOWN)",
                cancelResult.cause
            )
            return cancelResult
        }

        synchronized(DirectBootStorageLock.lock) {
            val postSnapshot = readLocked(protectedContext)
            if (postSnapshot == preSnapshot) {
                clearLocked(protectedContext)
            } else if (postSnapshot != null) {
                Log.i(
                    TAG,
                    "Preserving updated Direct-Boot snapshot for alarm $alarmId after concurrent drift during removal"
                )
            }
        }
        return cancelResult
    }

    /**
     * Legacy compatibility entrypoint. Returns `true` ONLY when OS exact registration
     * (`setAlarmClock` or post-`SecurityException` `setExactAndAllowWhileIdle`) succeeded and
     * post-OS recheck confirmed snapshot/witness consistency.
     */
    fun scheduleCachedAlarm(context: Context, now: Long = System.currentTimeMillis()): Boolean =
        scheduleCachedAlarmWithResult(context, now).isArmed

    /**
     * Authoritative C7-R1 typed fallback registration entrypoint.
     * Production runtime strictly enforces `V2_OUTPUT = DEFAULT_DENY` (`isV2DeliveryCapable = false`).
     */
    fun scheduleCachedAlarmWithResult(
        context: Context,
        now: Long = System.currentTimeMillis()
    ): DirectBootFallbackScheduleResult =
        scheduleCachedAlarmWithResultInternal(
            context = context,
            now = now,
            v2DeliveryCapable = false
        )

    @VisibleForTesting
    internal fun scheduleCachedAlarmWithResultInternal(
        context: Context,
        now: Long = System.currentTimeMillis(),
        v2DeliveryCapable: Boolean = false,
        alarmManagerProvider: (Context) -> AlarmManager? = { ctx ->
            ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        },
        canScheduleExactAlarms: (AlarmManager) -> Boolean = { am ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        },
        operationPendingIntentProvider: (Context, DirectBootAlarmSnapshot) -> PendingIntent? = { ctx, snap ->
            DirectBootAlarmReceiver.pendingIntent(ctx, snap)
        },
        showPendingIntentProvider: (Context, DirectBootAlarmSnapshot) -> PendingIntent? = { ctx, snap ->
            showIntent(ctx, snap)
        },
        alarmClockRegistrar: (AlarmManager, AlarmManager.AlarmClockInfo, PendingIntent) -> Unit = { am, info, op ->
            am.setAlarmClock(info, op)
        },
        exactIdleRegistrar: (AlarmManager, Long, PendingIntent) -> Unit = { am, triggerAt, op ->
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
        },
        onAfterOsRegistrationBeforeRecheck: (() -> Unit)? = null
    ): DirectBootFallbackScheduleResult {
        val protectedContext = protectedContext(context)
        val preCheck = synchronized(DirectBootStorageLock.lock) {
            val snapshot = readLocked(protectedContext)
            evaluatePreOsStateLocked(
                protectedContext = protectedContext,
                snapshot = snapshot,
                now = now,
                v2DeliveryCapable = v2DeliveryCapable
            )
        }

        val ready = when (preCheck) {
            is PreOsDecision.Blocked -> return preCheck.result
            is PreOsDecision.Ready -> preCheck
        }

        val osResult = scheduleFallback(
            context = protectedContext,
            snapshot = ready.snapshot,
            v2Diagnostics = ready.v2Diagnostics,
            alarmManagerProvider = alarmManagerProvider,
            canScheduleExactAlarms = canScheduleExactAlarms,
            operationPendingIntentProvider = operationPendingIntentProvider,
            showPendingIntentProvider = showPendingIntentProvider,
            alarmClockRegistrar = alarmClockRegistrar,
            exactIdleRegistrar = exactIdleRegistrar
        )

        if (!osResult.isArmed) {
            return osResult
        }

        onAfterOsRegistrationBeforeRecheck?.invoke()

        // Post-OS recheck under DE lock. Because Legacy A and B with the same alarmId share the exact
        // same PendingIntent token (data=null, requestCode=alarmId.toInt()), R1 strictly withholds
        // post-OS cancelScheduledFallback() and reports RegistrationOutcomeUnknown instead.
        return synchronized(DirectBootStorageLock.lock) {
            val postSnapshot = readLocked(protectedContext)
            val firedWitness = hasFiredWitnessLocked(
                protectedContext,
                ready.snapshot.alarmId,
                ready.snapshot.triggerTime
            )
            when {
                firedWitness -> DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown(
                    alarmId = ready.snapshot.alarmId,
                    triggerTime = ready.snapshot.triggerTime,
                    reason = PostOsUnknownReason.FIRED_WITNESS_DURING_REGISTRATION_COMPENSATION_WITHHELD,
                    observedSnapshot = postSnapshot,
                    compensationAttempted = false
                )
                postSnapshot != ready.snapshot -> DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown(
                    alarmId = ready.snapshot.alarmId,
                    triggerTime = ready.snapshot.triggerTime,
                    reason = PostOsUnknownReason.SNAPSHOT_SUPERSEDED_DURING_REGISTRATION,
                    observedSnapshot = postSnapshot,
                    compensationAttempted = false
                )
                else -> osResult
            }
        }
    }

    fun cancelScheduledFallback(context: Context, alarmId: Long) {
        val result = cancelScheduledFallbackWithResult(context, alarmId)
        if (result is DirectBootFallbackCancelResult.CancelFailedUnknown) {
            Log.w(TAG, "Direct-Boot fallback cancellation failed (OS state UNKNOWN) for alarm $alarmId", result.cause)
        }
    }

    fun cancelScheduledFallbackWithResult(
        context: Context,
        alarmId: Long
    ): DirectBootFallbackCancelResult = cancelScheduledFallbackWithResultInternal(context, alarmId)

    @VisibleForTesting
    internal fun cancelScheduledFallbackWithResultInternal(
        context: Context,
        alarmId: Long,
        pendingIntentLookup: (Context, Long) -> PendingIntent? = { ctx, id ->
            DirectBootAlarmReceiver.pendingIntent(
                context = ctx,
                snapshot = DirectBootAlarmSnapshot(
                    alarmId = id,
                    triggerTime = 1L,
                    label = "",
                    timeLabel = "",
                    playDefaultSound = true,
                    vibrationEnabled = true,
                    updatedAt = 0L
                ),
                flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        },
        alarmManagerProvider: (Context) -> AlarmManager? = { ctx ->
            ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        },
        alarmCanceller: (AlarmManager, PendingIntent) -> Unit = { am, pi ->
            am.cancel(pi)
            pi.cancel()
        }
    ): DirectBootFallbackCancelResult {
        if (alarmId <= 0L) {
            return DirectBootFallbackCancelResult.NoExistingPendingIntent(alarmId)
        }
        val protectedContext = protectedContext(context)
        return try {
            val pendingIntent = pendingIntentLookup(protectedContext, alarmId)
                ?: return DirectBootFallbackCancelResult.NoExistingPendingIntent(alarmId)
            val alarmManager = alarmManagerProvider(protectedContext)
                ?: return DirectBootFallbackCancelResult.CancelFailedUnknown(
                    alarmId = alarmId,
                    cause = IllegalStateException("AlarmManager service unavailable")
                )
            alarmCanceller(alarmManager, pendingIntent)
            DirectBootFallbackCancelResult.Cancelled(alarmId)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to cancel direct-boot fallback alarm $alarmId", t)
            DirectBootFallbackCancelResult.CancelFailedUnknown(alarmId, t)
        }
    }

    fun recordFired(context: Context, alarmId: Long, triggerTime: Long, firedAt: Long) {
        synchronized(DirectBootStorageLock.lock) {
            val edit = prefs(context).edit()
                .putLong(KEY_FIRED_ALARM_ID, alarmId)
                .putLong(KEY_FIRED_TRIGGER_TIME, triggerTime)
                .putLong(KEY_FIRED_AT, firedAt)
            readLocked(context)?.let { current ->
                if (current.alarmId == alarmId) {
                    edit
                        .remove(KEY_SCHEMA_VERSION)
                        .remove(KEY_ALARM_ID)
                        .remove(KEY_TRIGGER_TIME)
                        .remove(KEY_LABEL)
                        .remove(KEY_TIME_LABEL)
                        .remove(KEY_PLAY_DEFAULT_SOUND)
                        .remove(KEY_VIBRATION_ENABLED)
                        .remove(KEY_UPDATED_AT)
                        .remove(KEY_TIMEZONE_POLICY)
                        .remove(KEY_FIXED_TIMEZONE_ID)
                }
            }
            edit.commit()
        }
    }

    fun hasFiredWitness(context: Context, alarmId: Long, triggerTime: Long): Boolean =
        synchronized(DirectBootStorageLock.lock) {
            hasFiredWitnessLocked(context, alarmId, triggerTime)
        }

    private fun hasFiredWitnessLocked(context: Context, alarmId: Long, triggerTime: Long): Boolean {
        val prefs = prefs(context)
        val firedAlarmId = prefs.getLong(KEY_FIRED_ALARM_ID, -1L)
        val firedTriggerTime = prefs.getLong(KEY_FIRED_TRIGGER_TIME, 0L)
        return firedAlarmId == alarmId && (triggerTime <= 0L || firedTriggerTime == triggerTime)
    }

    fun consumeFiredOneShotMarker(context: Context, alarm: Alarm, now: Long): Boolean {
        if (alarm.isRecurringSchedule) return false

        synchronized(DirectBootStorageLock.lock) {
            val prefs = prefs(context)
            val firedAlarmId = prefs.getLong(KEY_FIRED_ALARM_ID, -1L)
            val firedTriggerTime = prefs.getLong(KEY_FIRED_TRIGGER_TIME, 0L)
            if (firedAlarmId != alarm.id || firedTriggerTime <= 0L || firedTriggerTime > now) {
                return false
            }

            val matchesPersistedTrigger = alarm.nextTriggerTime <= 0L ||
                alarm.nextTriggerTime == firedTriggerTime ||
                alarm.nextTriggerTime <= now
            if (!matchesPersistedTrigger) return false

            prefs.edit()
                .remove(KEY_FIRED_ALARM_ID)
                .remove(KEY_FIRED_TRIGGER_TIME)
                .remove(KEY_FIRED_AT)
                .commit()
            return true
        }
    }

    fun read(context: Context): DirectBootAlarmSnapshot? =
        synchronized(DirectBootStorageLock.lock) {
            readLocked(context)
        }

    private fun readLocked(context: Context): DirectBootAlarmSnapshot? {
        val prefs = prefs(context)
        val alarmId = prefs.getLong(KEY_ALARM_ID, -1L)
        val triggerTime = prefs.getLong(KEY_TRIGGER_TIME, 0L)
        if (alarmId <= 0L || triggerTime <= 0L) return null

        return DirectBootAlarmSnapshot(
            alarmId = alarmId,
            triggerTime = triggerTime,
            label = prefs.getString(KEY_LABEL, "").orEmpty(),
            timeLabel = prefs.getString(KEY_TIME_LABEL, "").orEmpty(),
            playDefaultSound = prefs.getBoolean(KEY_PLAY_DEFAULT_SOUND, true),
            vibrationEnabled = prefs.getBoolean(KEY_VIBRATION_ENABLED, true),
            updatedAt = prefs.getLong(KEY_UPDATED_AT, 0L),
            timezonePolicy = prefs.getString(
                KEY_TIMEZONE_POLICY,
                Alarm.TIMEZONE_POLICY_LOCAL
            ).orEmpty(),
            fixedTimezoneId = prefs.getString(KEY_FIXED_TIMEZONE_ID, "").orEmpty(),
            schemaVersion = prefs.getInt(
                KEY_SCHEMA_VERSION,
                DirectBootAlarmSnapshot.CURRENT_SCHEMA_VERSION
            )
        )
    }

    // =========================================================================
    // KNO-75 Phase 4 Inactive Versioned DE Queue Storage APIs
    // =========================================================================

    internal val queueLock: Any get() = DirectBootStorageLock.lock

    /**
     * Inspects the current storage state of the V2 queue.
     * Evaluates structural integrity without discarding expired items.
     */
    fun inspectQueueStorageState(context: Context): QueueStorageState {
        val rawPayload = prefs(context).getString(KEY_QUEUE_PAYLOAD, null)
        return parseQueueStorageState(rawPayload)
    }

    /**
     * Exposes the explicit classification of the V2 queue storage.
     */
    fun classifyQueueStorage(context: Context): QueueStorageClassification {
        return inspectQueueStorageState(context).classification
    }

    /**
     * Reads the V2 queue storage state.
     */
    fun readQueueStorage(context: Context): QueueStorageState {
        return inspectQueueStorageState(context)
    }

    /**
     * Diagnostic helper: returns the raw serialized V2 queue payload string.
     */
    fun getRawQueuePayload(context: Context): String? {
        return prefs(context).getString(KEY_QUEUE_PAYLOAD, null)
    }

    /**
     * Parses the raw string payload and classifies the V2 queue storage state.
     * Enforces fail-closed rules:
     * - More than [QUEUE_MAX_CAPACITY] (5) items is CORRUPT.
     * - Duplicate logical IDs is CORRUPT.
     * - Duplicate normal instance tokens is CORRUPT.
     * - Any structurally malformed item is CORRUPT (no silent partial salvage).
     * - Unsupported schema version is UNSUPPORTED_SCHEMA.
     * - Missing schemaVersion or items array is CORRUPT.
     */
    fun parseQueueStorageState(rawPayload: String?): QueueStorageState {
        if (rawPayload == null) {
            return QueueStorageState.Absent
        }
        if (rawPayload.isBlank()) {
            return QueueStorageState.Corrupt("Blank payload string")
        }
        return try {
            val root = JSONObject(rawPayload)
            if (!root.has("schemaVersion")) {
                return QueueStorageState.Corrupt("Missing schemaVersion field")
            }
            val schemaVersion = root.optInt("schemaVersion", -1)
            if (schemaVersion != DirectBootQueueItem.CURRENT_SCHEMA_VERSION && schemaVersion != 2) {
                return QueueStorageState.UnsupportedSchema(
                    foundVersion = schemaVersion,
                    expectedVersion = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
                )
            }
            if (!root.has("items")) {
                return QueueStorageState.Corrupt("Missing items array")
            }
            val itemsArray = root.optJSONArray("items")
                ?: return QueueStorageState.Corrupt("items is not a JSON array")

            if (itemsArray.length() == 0) {
                return QueueStorageState.ValidEmpty
            }

            // Payload integrity: stored items exceeding QUEUE_MAX_CAPACITY is strictly CORRUPT
            if (itemsArray.length() > QUEUE_MAX_CAPACITY) {
                return QueueStorageState.Corrupt(
                    "Stored items count (${itemsArray.length()}) exceeds QUEUE_MAX_CAPACITY ($QUEUE_MAX_CAPACITY)"
                )
            }

            val list = ArrayList<DirectBootQueueItem>(itemsArray.length())
            val seenLogicalIds = HashSet<QueueLogicalId>(itemsArray.length())
            val seenTokens = HashSet<String>(itemsArray.length())

            for (i in 0 until itemsArray.length()) {
                val itemObj = itemsArray.optJSONObject(i)
                    ?: return QueueStorageState.Corrupt("Item at index $i is not a JSON object")
                val item = try {
                    DirectBootQueueItem.fromJson(itemObj)
                } catch (e: Exception) {
                    return QueueStorageState.Corrupt("Failed to parse item at index $i: ${e.message}")
                }

                // Structural validity: now = 0L validates structure without expiring by current time
                val eligibility = item.evaluateEligibility(now = 0L, allowLegacyV1 = false)
                if (eligibility != QueueCandidateStatus.ELIGIBLE) {
                    return QueueStorageState.Corrupt("Item at index $i is structurally invalid: $eligibility")
                }

                // Payload integrity: duplicate logicalId in storage is strictly CORRUPT
                if (!seenLogicalIds.add(item.logicalId)) {
                    return QueueStorageState.Corrupt("Duplicate logicalId in storage: ${item.logicalId}")
                }

                // Payload integrity: duplicate non-blank instanceToken for Normal is strictly CORRUPT
                if (item.kind == DirectBootQueueItem.KIND_NORMAL && item.instanceToken.isNotBlank()) {
                    if (!seenTokens.add(item.instanceToken)) {
                        return QueueStorageState.Corrupt("Duplicate instanceToken in storage: ${item.instanceToken}")
                    }
                }

                list.add(item)
            }

            QueueStorageState.ValidWithItems(list.sortedWith(DirectBootQueueItem.QUEUE_COMPARATOR))
        } catch (e: Exception) {
            QueueStorageState.Corrupt("JSON parsing failed: ${e.message}")
        }
    }

    /**
     * Reads the active Direct Boot Queue from device-protected storage.
     *
     * Fallback behavior:
     * 1. If V2 queue payload is ValidWithItems, returns active items (filtered by [now] if now > 0L).
     * 2. If V2 queue payload is ValidEmpty, returns empty list (V2 is canonically empty).
     * 3. If V2 queue payload is Absent, Corrupt, or UnsupportedSchema, falls back to legacy V1 scalar prefs.
     * 4. If V1 scalar prefs represent an active schedulable alarm, converts and returns single-item queue.
     * 5. Otherwise returns empty list.
     */
    fun readQueue(context: Context, now: Long = 0L): List<DirectBootQueueItem> {
        val state = inspectQueueStorageState(context)
        when (state) {
            is QueueStorageState.ValidWithItems -> {
                return if (now > 0L) {
                    state.items.filter { it.triggerTime > now }
                } else {
                    state.items
                }
            }
            is QueueStorageState.ValidEmpty -> {
                return emptyList()
            }
            is QueueStorageState.Absent -> {
                val v1Snapshot = read(context)
                if (v1Snapshot != null && v1Snapshot.isSchedulable(now)) {
                    return listOf(DirectBootQueueItem.fromV1Snapshot(v1Snapshot))
                }
                return emptyList()
            }
            is QueueStorageState.Corrupt -> {
                Log.w(TAG, "Queue payload is corrupt (${state.reason}); checking legacy fallback")
                val v1Snapshot = read(context)
                if (v1Snapshot != null && v1Snapshot.isSchedulable(now)) {
                    return listOf(DirectBootQueueItem.fromV1Snapshot(v1Snapshot))
                }
                return emptyList()
            }
            is QueueStorageState.UnsupportedSchema -> {
                Log.w(TAG, "Queue payload has unsupported schema version ${state.foundVersion}; checking legacy fallback")
                val v1Snapshot = read(context)
                if (v1Snapshot != null && v1Snapshot.isSchedulable(now)) {
                    return listOf(DirectBootQueueItem.fromV1Snapshot(v1Snapshot))
                }
                return emptyList()
            }
        }
    }

    /**
     * Returns the earliest active queue candidate, or null if the queue is empty.
     */
    fun readQueueHead(context: Context, now: Long = 0L): DirectBootQueueItem? {
        return readQueue(context, now).firstOrNull()
    }

    /**
     * Persists the provided list of candidates into device-protected storage as a V2 envelope.
     * Sorts deterministically using [DirectBootQueueItem.QUEUE_COMPARATOR] BEFORE truncating to [QUEUE_MAX_CAPACITY].
     *
     * Invariants:
     * - Validates candidate eligibility prior to persistence.
     * - Rejects candidates with duplicate logical identities or duplicate normal instance tokens.
     * - Discards ineligible, stale, or malformed candidates without modifying storage.
     * - Fail-closed: rejects write if existing storage is CORRUPT or UNSUPPORTED_SCHEMA, preserving raw bytes.
     * - Synchronized via in-process [queueLock] with synchronous commit.
     * - Non-destructive: leaves V1 scalar keys intact to preserve legacy fallback until approved migration.
     */
    fun writeQueue(context: Context, items: List<DirectBootQueueItem>, now: Long = 0L): Boolean {
        // Reject duplicate logical identities
        val distinctLogicalIds = items.map { it.logicalId }.toSet()
        if (distinctLogicalIds.size != items.size) {
            Log.w(TAG, "writeQueue rejected: duplicate logical identities found in candidate list")
            return false
        }

        // Reject duplicate non-blank instanceTokens for Normal alarms
        val normalTokens = items
            .filter { it.kind == DirectBootQueueItem.KIND_NORMAL && it.instanceToken.isNotBlank() }
            .map { it.instanceToken }
        if (normalTokens.toSet().size != normalTokens.size) {
            Log.w(TAG, "writeQueue rejected: duplicate instanceTokens found in candidate list")
            return false
        }

        // Validate eligibility for each item (must be valid modern V2 candidate)
        for (item in items) {
            val eligibility = item.evaluateEligibility(now = now, allowLegacyV1 = false)
            if (eligibility != QueueCandidateStatus.ELIGIBLE) {
                Log.w(TAG, "writeQueue rejected: candidate ${item.logicalId} is ineligible: $eligibility")
                return false
            }
        }

        val sanitized = items
            .sortedWith(DirectBootQueueItem.QUEUE_COMPARATOR)
            .take(QUEUE_MAX_CAPACITY)

        synchronized(queueLock) {
            val state = inspectQueueStorageState(context)
            if (state is QueueStorageState.Corrupt) {
                Log.w(TAG, "writeQueue rejected: storage is corrupt (${state.reason}); fail-closed")
                return false
            }
            if (state is QueueStorageState.UnsupportedSchema) {
                Log.w(TAG, "writeQueue rejected: storage has unsupported schema ${state.foundVersion}; fail-closed")
                return false
            }

            return writeQueueInternal(context, sanitized)
        }
    }

    private fun writeQueueInternal(context: Context, sanitized: List<DirectBootQueueItem>): Boolean {
        val root = JSONObject().apply {
            put("schemaVersion", DirectBootQueueItem.CURRENT_SCHEMA_VERSION)
            val array = JSONArray()
            sanitized.forEach { array.put(it.toJson()) }
            put("items", array)
        }

        return try {
            prefs(context).edit()
                .putString(KEY_QUEUE_PAYLOAD, root.toString())
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist direct boot queue payload", e)
            false
        }
    }

    /**
     * Enqueues a candidate into the inactive versioned DE Queue.
     *
     * Invariants:
     * - Validates eligibility (returns [QueueEnqueueResult.RejectedIneligible] if missing required identity).
     * - Rejects mutation fail-closed if storage is CORRUPT or UNSUPPORTED_SCHEMA, preserving raw payload.
     * - Deduplicates by logical identity ([QueueLogicalId]):
     *   - Replaces older generation or same generation candidate with updated state.
     *   - Rejects stale candidates with older generation than currently stored.
     * - Limits queue to nearest [QUEUE_MAX_CAPACITY] (5) items sorted by [DirectBootQueueItem.QUEUE_COMPARATOR].
     * - If candidate falls outside nearest 5, returns [QueueEnqueueResult.DroppedByCapacity].
     * - Synchronized via in-process [queueLock] with synchronous commit.
     */
    fun enqueueCandidate(
        context: Context,
        candidate: DirectBootQueueItem,
        now: Long = System.currentTimeMillis()
    ): QueueEnqueueResult {
        val eligibility = candidate.evaluateEligibility(now)
        if (eligibility != QueueCandidateStatus.ELIGIBLE) {
            return QueueEnqueueResult.RejectedIneligible(eligibility)
        }

        synchronized(queueLock) {
            val state = inspectQueueStorageState(context)
            val currentItems: MutableList<DirectBootQueueItem> = when (state) {
                is QueueStorageState.Absent -> mutableListOf()
                is QueueStorageState.ValidEmpty -> mutableListOf()
                is QueueStorageState.ValidWithItems -> state.items.toMutableList()
                is QueueStorageState.Corrupt -> {
                    Log.w(TAG, "enqueueCandidate rejected: storage is corrupt (${state.reason}); fail-closed")
                    return QueueEnqueueResult.StorageCorrupt(state.reason)
                }
                is QueueStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "enqueueCandidate rejected: unsupported schema version ${state.foundVersion}; fail-closed")
                    return QueueEnqueueResult.UnsupportedSchema(
                        foundVersion = state.foundVersion,
                        expectedVersion = state.expectedVersion
                    )
                }
            }

            val existingIndex = currentItems.indexOfFirst { it.logicalId == candidate.logicalId }
            if (existingIndex >= 0) {
                val existing = currentItems[existingIndex]
                if (candidate.generation < existing.generation) {
                    return QueueEnqueueResult.RejectedStaleGeneration(existing.generation)
                }
                currentItems[existingIndex] = candidate
            } else {
                currentItems.add(candidate)
            }

            currentItems.sortWith(DirectBootQueueItem.QUEUE_COMPARATOR)

            val isCandidateRetained = currentItems.take(QUEUE_MAX_CAPACITY).any {
                it.logicalId == candidate.logicalId && it.triggerTime == candidate.triggerTime
            }

            val retained = currentItems.take(QUEUE_MAX_CAPACITY)
            if (!isCandidateRetained) {
                return QueueEnqueueResult.DroppedByCapacity(retained.size)
            }

            val persisted = writeQueueInternal(context, retained)
            return if (persisted) {
                QueueEnqueueResult.Enqueued(head = retained.first(), queueSize = retained.size)
            } else {
                QueueEnqueueResult.PersistenceFailed("SharedPreferences commit returned false")
            }
        }
    }

    /**
     * Removes an entry matching the logical identity ([kind], [alarmId]) from the DE queue.
     * Returns true if an entry was removed and queue persisted, false otherwise.
     * Rejects mutation fail-closed if storage is CORRUPT or UNSUPPORTED_SCHEMA.
     */
    fun removeCandidate(context: Context, kind: String, alarmId: Long): Boolean {
        synchronized(queueLock) {
            val state = inspectQueueStorageState(context)
            val currentItems: MutableList<DirectBootQueueItem> = when (state) {
                is QueueStorageState.Absent, is QueueStorageState.ValidEmpty -> return false
                is QueueStorageState.ValidWithItems -> state.items.toMutableList()
                is QueueStorageState.Corrupt -> {
                    Log.w(TAG, "removeCandidate rejected: storage is corrupt (${state.reason}); fail-closed")
                    return false
                }
                is QueueStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "removeCandidate rejected: unsupported schema version ${state.foundVersion}; fail-closed")
                    return false
                }
            }

            val initialSize = currentItems.size
            currentItems.removeAll { it.kind == kind && it.alarmId == alarmId }
            if (currentItems.size == initialSize) {
                return false
            }
            return writeQueueInternal(context, currentItems)
        }
    }

    /**
     * Clears only the V2 queue payload from device-protected storage.
     */
    fun clearQueue(context: Context): Boolean {
        synchronized(queueLock) {
            return try {
                prefs(context).edit().remove(KEY_QUEUE_PAYLOAD).commit()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear queue payload", e)
                false
            }
        }
    }

    /**
     * Atomically reconciles the Normal projection in the versioned DE Queue while preserving
     * existing non-Normal (e.g. Quick) queue entries.
     *
     * Invariants:
     * - Only modifies Normal entries; existing non-Normal entries retain their state and positions.
     * - Replaces all existing Normal entries with the authoritative [normalCandidates] (or clears them if empty).
     * - Sorts the merged queue using [DirectBootQueueItem.QUEUE_COMPARATOR] and truncates to [QUEUE_MAX_CAPACITY] (5).
     * - Fail-closed: rejects write if existing storage is CORRUPT or UNSUPPORTED_SCHEMA, preserving raw bytes.
     * - Non-destructive: leaves V1 scalar keys untouched.
     * - Synchronized via in-process [queueLock] with synchronous commit.
     */
    fun updateNormalQueue(
        context: Context,
        normalCandidates: List<DirectBootQueueItem>,
        now: Long = 0L
    ): NormalQueueUpdateResult {
        for (item in normalCandidates) {
            if (item.kind != DirectBootQueueItem.KIND_NORMAL) {
                return NormalQueueUpdateResult.RejectedIneligible(
                    "Candidate ${item.logicalId} has non-normal kind '${item.kind}'"
                )
            }
            val eligibility = item.evaluateEligibility(now = now, allowLegacyV1 = false)
            if (eligibility != QueueCandidateStatus.ELIGIBLE) {
                return NormalQueueUpdateResult.RejectedIneligible(
                    "Candidate ${item.logicalId} is ineligible: $eligibility"
                )
            }
        }

        val distinctLogicalIds = normalCandidates.map { it.logicalId }.toSet()
        if (distinctLogicalIds.size != normalCandidates.size) {
            return NormalQueueUpdateResult.RejectedIneligible(
                "Duplicate logical identities in normal candidate list"
            )
        }

        val normalTokens = normalCandidates
            .filter { it.instanceToken.isNotBlank() }
            .map { it.instanceToken }
        if (normalTokens.toSet().size != normalTokens.size) {
            return NormalQueueUpdateResult.RejectedIneligible(
                "Duplicate instanceTokens in normal candidate list"
            )
        }

        synchronized(queueLock) {
            val state = inspectQueueStorageState(context)
            val preservedNonNormal: List<DirectBootQueueItem> = when (state) {
                is QueueStorageState.Absent, is QueueStorageState.ValidEmpty -> emptyList()
                is QueueStorageState.ValidWithItems -> {
                    state.items.filter { it.kind != DirectBootQueueItem.KIND_NORMAL }
                }
                is QueueStorageState.Corrupt -> {
                    Log.w(TAG, "updateNormalQueue rejected: storage is corrupt (${state.reason}); fail-closed")
                    return NormalQueueUpdateResult.StorageCorrupt(state.reason)
                }
                is QueueStorageState.UnsupportedSchema -> {
                    Log.w(TAG, "updateNormalQueue rejected: storage has unsupported schema ${state.foundVersion}; fail-closed")
                    return NormalQueueUpdateResult.UnsupportedSchema(
                        foundVersion = state.foundVersion,
                        expectedVersion = state.expectedVersion
                    )
                }
            }

            val merged = ArrayList<DirectBootQueueItem>(preservedNonNormal.size + normalCandidates.size)
            merged.addAll(preservedNonNormal)
            merged.addAll(normalCandidates)

            merged.sortWith(DirectBootQueueItem.QUEUE_COMPARATOR)

            val retained = merged.take(QUEUE_MAX_CAPACITY)
            val retainedNormal = normalCandidates.filter { candidate ->
                retained.any { it.logicalId == candidate.logicalId && it.triggerTime == candidate.triggerTime }
            }

            val persisted = try {
                writeQueueInternal(context, retained)
            } catch (e: Exception) {
                Log.e(TAG, "Exception during writeQueueInternal in updateNormalQueue", e)
                return NormalQueueUpdateResult.PersistenceFailed(
                    details = "Exception during queue persistence: ${e.message}",
                    cause = e
                )
            }

            return if (persisted) {
                NormalQueueUpdateResult.Success(
                    completeQueue = retained,
                    retainedNormalCandidates = retainedNormal
                )
            } else {
                NormalQueueUpdateResult.PersistenceFailed("SharedPreferences commit returned false")
            }
        }
    }

    // =========================================================================
    // Legacy internal helpers
    // =========================================================================

    private sealed interface PreOsDecision {
        data class Ready(
            val snapshot: DirectBootAlarmSnapshot,
            val v2Diagnostics: Set<V2DiagnosticFlag>
        ) : PreOsDecision

        data class Blocked(val result: DirectBootFallbackScheduleResult) : PreOsDecision
    }

    private fun evaluatePreOsStateLocked(
        protectedContext: Context,
        snapshot: DirectBootAlarmSnapshot?,
        now: Long,
        v2DeliveryCapable: Boolean
    ): PreOsDecision {
        val diagnostics = LinkedHashSet<V2DiagnosticFlag>()

        val queueState = inspectQueueStorageState(protectedContext)
        val ownerResult = DirectBootDurableOwnerStore.read(protectedContext)
        val markerResult = DirectBootRegistrationMarker.read(protectedContext)

        val queueMatchesOccurrence = snapshot != null &&
            queueState is QueueStorageState.ValidWithItems &&
            queueState.items.any {
                it.kind == DirectBootQueueItem.KIND_NORMAL &&
                    it.alarmId == snapshot.alarmId &&
                    it.triggerTime == snapshot.triggerTime
            }

        when (queueState) {
            is QueueStorageState.Absent, is QueueStorageState.ValidEmpty -> Unit
            is QueueStorageState.ValidWithItems -> {
                if (queueMatchesOccurrence) {
                    if (!v2DeliveryCapable) {
                        diagnostics += V2DiagnosticFlag.QUEUE_MATCHING_OCCURRENCE_WHILE_DEFAULT_DENY
                    }
                } else {
                    diagnostics += V2DiagnosticFlag.QUEUE_UNRELATED_OCCURRENCE
                }
            }
            is QueueStorageState.Corrupt -> diagnostics += V2DiagnosticFlag.QUEUE_CORRUPT
            is QueueStorageState.UnsupportedSchema -> diagnostics += V2DiagnosticFlag.QUEUE_UNSUPPORTED_SCHEMA
        }

        when (markerResult) {
            is MarkerReadResult.Absent -> Unit
            is MarkerReadResult.Success -> {
                when (markerResult.marker.mainState) {
                    RegistrationMainState.IDLE -> Unit
                    RegistrationMainState.IN_FLIGHT -> {
                        if (!v2DeliveryCapable) {
                            diagnostics += V2DiagnosticFlag.MARKER_IN_FLIGHT_WHILE_DEFAULT_DENY
                        }
                    }
                    RegistrationMainState.FAILED_BLOCKED -> {
                        diagnostics += V2DiagnosticFlag.MARKER_FAILED_BLOCKED
                    }
                }
            }
            is MarkerReadResult.Corrupt -> diagnostics += V2DiagnosticFlag.MARKER_CORRUPT
            is MarkerReadResult.UnsupportedSchema -> diagnostics += V2DiagnosticFlag.MARKER_UNSUPPORTED_SCHEMA
        }

        var ownerMatchingHandover = false
        var ownerMatchingV2Active = false
        when (ownerResult) {
            is OwnerReadResult.Absent -> Unit
            is OwnerReadResult.Success -> {
                val owner = ownerResult.owner
                when (owner.state) {
                    OwnerState.IDLE -> Unit
                    OwnerState.HANDOVER_PENDING -> {
                        val claim = owner.handoverClaim
                        if (snapshot != null &&
                            claim != null &&
                            claim.alarmId == snapshot.alarmId &&
                            claim.triggerTime == snapshot.triggerTime
                        ) {
                            ownerMatchingHandover = true
                        } else {
                            diagnostics += V2DiagnosticFlag.OWNER_UNRELATED_OCCURRENCE_ACTIVE
                        }
                    }
                    OwnerState.V2_ACTIVE -> {
                        val session = owner.activeSession
                        if (snapshot != null &&
                            session != null &&
                            session.alarmId == snapshot.alarmId &&
                            session.triggerTime == snapshot.triggerTime
                        ) {
                            ownerMatchingV2Active = true
                        } else {
                            diagnostics += V2DiagnosticFlag.OWNER_UNRELATED_OCCURRENCE_ACTIVE
                        }
                    }
                }
            }
            is OwnerReadResult.Corrupt -> if (!v2DeliveryCapable) {
                diagnostics += V2DiagnosticFlag.OWNER_CORRUPT_WHILE_DEFAULT_DENY
            }
            is OwnerReadResult.UnsupportedSchema -> if (!v2DeliveryCapable) {
                diagnostics += V2DiagnosticFlag.OWNER_UNSUPPORTED_WHILE_DEFAULT_DENY
            }
        }

        // Snapshot target validation (strictly non-destructive: NEVER calls clear() on failure/overdue)
        if (snapshot == null) {
            return PreOsDecision.Blocked(
                DirectBootFallbackScheduleResult.NoSchedulableTarget(
                    reason = NoTargetReason.SNAPSHOT_ABSENT,
                    v2Diagnostics = diagnostics
                )
            )
        }

        if (snapshot.schemaVersion != DirectBootAlarmSnapshot.CURRENT_SCHEMA_VERSION ||
            snapshot.alarmId <= 0L ||
            snapshot.triggerTime <= 0L
        ) {
            return PreOsDecision.Blocked(
                DirectBootFallbackScheduleResult.NoSchedulableTarget(
                    reason = NoTargetReason.INVALID_SNAPSHOT,
                    alarmId = snapshot.alarmId,
                    triggerTime = snapshot.triggerTime,
                    v2Diagnostics = diagnostics
                )
            )
        }

        if (now <= 0L) {
            return PreOsDecision.Blocked(
                DirectBootFallbackScheduleResult.NoSchedulableTarget(
                    reason = NoTargetReason.INVALID_CLOCK_NOW,
                    alarmId = snapshot.alarmId,
                    triggerTime = snapshot.triggerTime,
                    v2Diagnostics = diagnostics
                )
            )
        }

        if (snapshot.updatedAt > 0L && now < snapshot.updatedAt) {
            diagnostics += V2DiagnosticFlag.CLOCK_ROLLBACK_DETECTED
            Log.w(
                TAG,
                "Clock rollback observed for alarm ${snapshot.alarmId}: now=$now < updatedAt=${snapshot.updatedAt}"
            )
        }

        if (snapshot.triggerTime <= now) {
            return PreOsDecision.Blocked(
                DirectBootFallbackScheduleResult.NoSchedulableTarget(
                    reason = NoTargetReason.TRIGGER_NOT_IN_FUTURE_PRESERVED,
                    alarmId = snapshot.alarmId,
                    triggerTime = snapshot.triggerTime,
                    v2Diagnostics = diagnostics
                )
            )
        }

        if (hasFiredWitnessLocked(protectedContext, snapshot.alarmId, snapshot.triggerTime)) {
            return PreOsDecision.Blocked(
                DirectBootFallbackScheduleResult.NoSchedulableTarget(
                    reason = NoTargetReason.ALREADY_FIRED_WITNESS,
                    alarmId = snapshot.alarmId,
                    triggerTime = snapshot.triggerTime,
                    v2Diagnostics = diagnostics
                )
            )
        }

        // Same-occurrence anomalous DurableOwner always fails closed as UNKNOWN
        if (ownerMatchingHandover) {
            return PreOsDecision.Blocked(
                DirectBootFallbackScheduleResult.AuthorityUnknown(
                    alarmId = snapshot.alarmId,
                    triggerTime = snapshot.triggerTime,
                    reason = AuthorityUnknownReason.DURABLE_OWNER_SAME_OCCURRENCE_HANDOVER_PENDING,
                    detail = "Same-occurrence DurableOwner HANDOVER_PENDING present"
                )
            )
        }
        if (ownerMatchingV2Active) {
            return if (v2DeliveryCapable) {
                PreOsDecision.Blocked(
                    DirectBootFallbackScheduleResult.AuthorityRejected(
                        alarmId = snapshot.alarmId,
                        triggerTime = snapshot.triggerTime,
                        detail = "Same-occurrence V2_ACTIVE already owns delivery"
                    )
                )
            } else {
                PreOsDecision.Blocked(
                    DirectBootFallbackScheduleResult.AuthorityUnknown(
                        alarmId = snapshot.alarmId,
                        triggerTime = snapshot.triggerTime,
                        reason = AuthorityUnknownReason.DURABLE_OWNER_SAME_OCCURRENCE_V2_ACTIVE,
                        detail = "Same-occurrence DurableOwner V2_ACTIVE present under DEFAULT_DENY"
                    )
                )
            }
        }

        // Additional authority checks ONLY when V2 delivery is genuinely capable (unreachable in R1 production)
        if (v2DeliveryCapable) {
            when (ownerResult) {
                is OwnerReadResult.Corrupt -> return PreOsDecision.Blocked(
                    DirectBootFallbackScheduleResult.AuthorityUnknown(
                        snapshot.alarmId,
                        snapshot.triggerTime,
                        AuthorityUnknownReason.DURABLE_OWNER_CORRUPT_WHILE_V2_CAPABLE,
                        ownerResult.reason
                    )
                )
                is OwnerReadResult.UnsupportedSchema -> return PreOsDecision.Blocked(
                    DirectBootFallbackScheduleResult.AuthorityUnknown(
                        snapshot.alarmId,
                        snapshot.triggerTime,
                        AuthorityUnknownReason.DURABLE_OWNER_UNSUPPORTED_WHILE_V2_CAPABLE,
                        "Unsupported owner schema ${ownerResult.foundVersion}"
                    )
                )
                else -> Unit
            }

            if (queueState is QueueStorageState.Corrupt || queueState is QueueStorageState.UnsupportedSchema) {
                return PreOsDecision.Blocked(
                    DirectBootFallbackScheduleResult.AuthorityUnknown(
                        snapshot.alarmId,
                        snapshot.triggerTime,
                        AuthorityUnknownReason.V2_QUEUE_CORRUPT_OR_UNSUPPORTED_WHILE_V2_CAPABLE,
                        "V2 queue unverifiable while V2 delivery capable"
                    )
                )
            }

            if (markerResult is MarkerReadResult.Success &&
                markerResult.marker.mainState == RegistrationMainState.IN_FLIGHT
            ) {
                return PreOsDecision.Blocked(
                    DirectBootFallbackScheduleResult.AuthorityUnknown(
                        snapshot.alarmId,
                        snapshot.triggerTime,
                        AuthorityUnknownReason.V2_MARKER_IN_FLIGHT_WHILE_V2_CAPABLE,
                        "V2 registration marker IN_FLIGHT"
                    )
                )
            }

            if (queueMatchesOccurrence) {
                val activeId = (markerResult as? MarkerReadResult.Success)?.marker?.activeIdentity
                val v2Confirmed = activeId != null &&
                    activeId.alarmId == snapshot.alarmId &&
                    activeId.triggerTime == snapshot.triggerTime
                return if (v2Confirmed) {
                    PreOsDecision.Blocked(
                        DirectBootFallbackScheduleResult.AuthorityRejected(
                            alarmId = snapshot.alarmId,
                            triggerTime = snapshot.triggerTime,
                            detail = "Verified V2 activeIdentity owns same occurrence"
                        )
                    )
                } else {
                    PreOsDecision.Blocked(
                        DirectBootFallbackScheduleResult.AuthorityUnknown(
                            alarmId = snapshot.alarmId,
                            triggerTime = snapshot.triggerTime,
                            reason = AuthorityUnknownReason.V2_QUEUE_MARKER_MISMATCH_WHILE_V2_CAPABLE,
                            detail = "V2 queue matches occurrence without confirmed activeIdentity"
                        )
                    )
                }
            }
        }

        return PreOsDecision.Ready(snapshot = snapshot, v2Diagnostics = diagnostics)
    }

    private fun writeLocked(context: Context, snapshot: DirectBootAlarmSnapshot) {
        prefs(context).edit()
            .putInt(KEY_SCHEMA_VERSION, snapshot.schemaVersion)
            .putLong(KEY_ALARM_ID, snapshot.alarmId)
            .putLong(KEY_TRIGGER_TIME, snapshot.triggerTime)
            .putString(KEY_LABEL, snapshot.label)
            .putString(KEY_TIME_LABEL, snapshot.timeLabel)
            .putBoolean(KEY_PLAY_DEFAULT_SOUND, snapshot.playDefaultSound)
            .putBoolean(KEY_VIBRATION_ENABLED, snapshot.vibrationEnabled)
            .putLong(KEY_UPDATED_AT, snapshot.updatedAt)
            .putString(KEY_TIMEZONE_POLICY, snapshot.timezonePolicy)
            .putString(KEY_FIXED_TIMEZONE_ID, snapshot.fixedTimezoneId)
            .commit()
    }

    @VisibleForTesting
    internal fun scheduleFallback(
        context: Context,
        snapshot: DirectBootAlarmSnapshot,
        v2Diagnostics: Set<V2DiagnosticFlag> = emptySet(),
        alarmManagerProvider: (Context) -> AlarmManager? = { ctx ->
            ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        },
        canScheduleExactAlarms: (AlarmManager) -> Boolean = { am ->
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        },
        operationPendingIntentProvider: (Context, DirectBootAlarmSnapshot) -> PendingIntent? = { ctx, snap ->
            DirectBootAlarmReceiver.pendingIntent(ctx, snap)
        },
        showPendingIntentProvider: (Context, DirectBootAlarmSnapshot) -> PendingIntent? = { ctx, snap ->
            showIntent(ctx, snap)
        },
        alarmClockRegistrar: (AlarmManager, AlarmManager.AlarmClockInfo, PendingIntent) -> Unit = { am, info, op ->
            am.setAlarmClock(info, op)
        },
        exactIdleRegistrar: (AlarmManager, Long, PendingIntent) -> Unit = { am, triggerAt, op ->
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, op)
        }
    ): DirectBootFallbackScheduleResult {
        val protectedContext = protectedContext(context)
        val alarmManager = alarmManagerProvider(protectedContext)
            ?: return DirectBootFallbackScheduleResult.RegistrationFailed(
                alarmId = snapshot.alarmId,
                triggerTime = snapshot.triggerTime,
                stage = FallbackFailureStage.ALARM_MANAGER_UNAVAILABLE,
                cause = IllegalStateException("AlarmManager service unavailable")
            )

        if (!canScheduleExactAlarms(alarmManager)) {
            Log.w(TAG, "Exact alarm permission denied during Direct Boot for alarm ${snapshot.alarmId}")
            return DirectBootFallbackScheduleResult.PermissionDenied(
                alarmId = snapshot.alarmId,
                triggerTime = snapshot.triggerTime,
                reason = "AlarmManager.canScheduleExactAlarms() returned false"
            )
        }

        val operation = operationPendingIntentProvider(protectedContext, snapshot)
            ?: return DirectBootFallbackScheduleResult.PendingIntentUnavailable(
                alarmId = snapshot.alarmId,
                triggerTime = snapshot.triggerTime,
                stage = FallbackFailureStage.OPERATION_INTENT_UNAVAILABLE
            )

        val showPendingIntent = showPendingIntentProvider(protectedContext, snapshot)
            ?: return DirectBootFallbackScheduleResult.PendingIntentUnavailable(
                alarmId = snapshot.alarmId,
                triggerTime = snapshot.triggerTime,
                stage = FallbackFailureStage.SHOW_INTENT_UNAVAILABLE
            )

        return try {
            alarmClockRegistrar(
                alarmManager,
                AlarmManager.AlarmClockInfo(snapshot.triggerTime, showPendingIntent),
                operation
            )
            DirectBootFallbackScheduleResult.ArmedAlarmClock(
                alarmId = snapshot.alarmId,
                triggerTime = snapshot.triggerTime,
                v2Diagnostics = v2Diagnostics
            )
        } catch (primarySe: SecurityException) {
            Log.w(TAG, "setAlarmClock denied during Direct Boot; attempting exact-idle fallback", primarySe)
            try {
                exactIdleRegistrar(alarmManager, snapshot.triggerTime, operation)
                DirectBootFallbackScheduleResult.ArmedExactIdleFallback(
                    alarmId = snapshot.alarmId,
                    triggerTime = snapshot.triggerTime,
                    primarySecurityException = primarySe,
                    v2Diagnostics = v2Diagnostics
                )
            } catch (fallbackSe: SecurityException) {
                Log.e(TAG, "Direct-Boot exact-idle fallback blocked by SecurityException", fallbackSe)
                DirectBootFallbackScheduleResult.SecurityBlocked(
                    alarmId = snapshot.alarmId,
                    triggerTime = snapshot.triggerTime,
                    primaryCause = primarySe,
                    fallbackCause = fallbackSe
                )
            } catch (fallbackEx: Throwable) {
                Log.e(TAG, "Direct-Boot exact-idle fallback failed", fallbackEx)
                DirectBootFallbackScheduleResult.RegistrationFailed(
                    alarmId = snapshot.alarmId,
                    triggerTime = snapshot.triggerTime,
                    stage = FallbackFailureStage.EXACT_IDLE_FALLBACK_SET,
                    cause = fallbackEx,
                    primarySecurityException = primarySe
                )
            }
        } catch (primaryEx: Throwable) {
            Log.e(TAG, "Direct-Boot alarm registration failed", primaryEx)
            DirectBootFallbackScheduleResult.RegistrationFailed(
                alarmId = snapshot.alarmId,
                triggerTime = snapshot.triggerTime,
                stage = FallbackFailureStage.ALARM_CLOCK_SET,
                cause = primaryEx
            )
        }
    }

    private fun showIntent(context: Context, snapshot: DirectBootAlarmSnapshot): PendingIntent? {
        val launch = DirectBootAlarmActivity.intent(
            context = context,
            alarmId = snapshot.alarmId,
            timeLabel = snapshot.timeLabel
        )
        return PendingIntent.getActivity(
            context,
            DIRECT_BOOT_SHOW_REQUEST_CODE,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun prefs(context: Context) = protectedContext(context).getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private fun protectedContext(context: Context): Context {
        return if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
    }

    private fun formatTriggerTime(context: Context, triggerTime: Long): String =
        AlarmTimeFormatter.format(triggerTime, DateFormat.is24HourFormat(context))
}

enum class OsRegistrationState {
    ARMED_CONFIRMED,
    NOT_ARMED,
    UNKNOWN
}

enum class NoTargetReason {
    SNAPSHOT_ABSENT,
    INVALID_SNAPSHOT,
    INVALID_CLOCK_NOW,
    TRIGGER_NOT_IN_FUTURE_PRESERVED,
    ALREADY_FIRED_WITNESS
}

enum class FallbackFailureStage {
    ALARM_MANAGER_UNAVAILABLE,
    OPERATION_INTENT_UNAVAILABLE,
    SHOW_INTENT_UNAVAILABLE,
    ALARM_CLOCK_SET,
    EXACT_IDLE_FALLBACK_SET
}

enum class AuthorityUnknownReason {
    DURABLE_OWNER_SAME_OCCURRENCE_HANDOVER_PENDING,
    DURABLE_OWNER_SAME_OCCURRENCE_V2_ACTIVE,
    DURABLE_OWNER_CORRUPT_WHILE_V2_CAPABLE,
    DURABLE_OWNER_UNSUPPORTED_WHILE_V2_CAPABLE,
    V2_MARKER_IN_FLIGHT_WHILE_V2_CAPABLE,
    V2_QUEUE_CORRUPT_OR_UNSUPPORTED_WHILE_V2_CAPABLE,
    V2_QUEUE_MARKER_MISMATCH_WHILE_V2_CAPABLE
}

enum class PostOsUnknownReason {
    FIRED_WITNESS_DURING_REGISTRATION_COMPENSATION_WITHHELD,
    SNAPSHOT_SUPERSEDED_DURING_REGISTRATION
}

enum class V2DiagnosticFlag {
    QUEUE_MATCHING_OCCURRENCE_WHILE_DEFAULT_DENY,
    QUEUE_UNRELATED_OCCURRENCE,
    QUEUE_CORRUPT,
    QUEUE_UNSUPPORTED_SCHEMA,
    MARKER_IN_FLIGHT_WHILE_DEFAULT_DENY,
    MARKER_FAILED_BLOCKED,
    MARKER_CORRUPT,
    MARKER_UNSUPPORTED_SCHEMA,
    OWNER_UNRELATED_OCCURRENCE_ACTIVE,
    OWNER_CORRUPT_WHILE_DEFAULT_DENY,
    OWNER_UNSUPPORTED_WHILE_DEFAULT_DENY,
    CLOCK_ROLLBACK_DETECTED
}

sealed interface DirectBootFallbackScheduleResult {
    val isArmed: Boolean
        get() = this is ArmedAlarmClock || this is ArmedExactIdleFallback
    val osState: OsRegistrationState
    val isRetryEligible: Boolean
        get() = this is RegistrationFailed

    data class ArmedAlarmClock(
        val alarmId: Long,
        val triggerTime: Long,
        val v2Diagnostics: Set<V2DiagnosticFlag> = emptySet()
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.ARMED_CONFIRMED
    }

    data class ArmedExactIdleFallback(
        val alarmId: Long,
        val triggerTime: Long,
        val primarySecurityException: SecurityException,
        val v2Diagnostics: Set<V2DiagnosticFlag> = emptySet()
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.ARMED_CONFIRMED
    }

    data class PermissionDenied(
        val alarmId: Long,
        val triggerTime: Long,
        val reason: String
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.NOT_ARMED
    }

    data class PendingIntentUnavailable(
        val alarmId: Long,
        val triggerTime: Long,
        val stage: FallbackFailureStage
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.NOT_ARMED
    }

    data class SecurityBlocked(
        val alarmId: Long,
        val triggerTime: Long,
        val primaryCause: SecurityException,
        val fallbackCause: SecurityException
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.NOT_ARMED
    }

    data class RegistrationFailed(
        val alarmId: Long,
        val triggerTime: Long,
        val stage: FallbackFailureStage,
        val cause: Throwable?,
        val primarySecurityException: SecurityException? = null
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.NOT_ARMED
    }

    data class NoSchedulableTarget(
        val reason: NoTargetReason,
        val alarmId: Long? = null,
        val triggerTime: Long? = null,
        val v2Diagnostics: Set<V2DiagnosticFlag> = emptySet()
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.NOT_ARMED
    }

    data class AuthorityUnknown(
        val alarmId: Long,
        val triggerTime: Long,
        val reason: AuthorityUnknownReason,
        val detail: String
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.UNKNOWN
    }

    data class AuthorityRejected(
        val alarmId: Long,
        val triggerTime: Long,
        val detail: String
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.NOT_ARMED
    }

    data class RegistrationOutcomeUnknown(
        val alarmId: Long,
        val triggerTime: Long,
        val reason: PostOsUnknownReason,
        val observedSnapshot: DirectBootAlarmSnapshot? = null,
        val compensationAttempted: Boolean = false
    ) : DirectBootFallbackScheduleResult {
        override val osState: OsRegistrationState = OsRegistrationState.UNKNOWN
    }
}

sealed interface DirectBootFallbackCancelResult {
    val isCancelledOrAbsent: Boolean
        get() = this is Cancelled || this is NoExistingPendingIntent
    val osState: OsRegistrationState

    data class Cancelled(val alarmId: Long) : DirectBootFallbackCancelResult {
        override val osState: OsRegistrationState = OsRegistrationState.NOT_ARMED
    }

    data class NoExistingPendingIntent(val alarmId: Long) : DirectBootFallbackCancelResult {
        override val osState: OsRegistrationState = OsRegistrationState.NOT_ARMED
    }

    data class CancelFailedUnknown(
        val alarmId: Long,
        val cause: Throwable
    ) : DirectBootFallbackCancelResult {
        override val osState: OsRegistrationState = OsRegistrationState.UNKNOWN
    }
}

enum class QueueStorageClassification {
    ABSENT,
    VALID_EMPTY,
    VALID_WITH_ITEMS,
    CORRUPT,
    UNSUPPORTED_SCHEMA
}

sealed class QueueStorageState {
    abstract val classification: QueueStorageClassification

    object Absent : QueueStorageState() {
        override val classification = QueueStorageClassification.ABSENT
    }

    object ValidEmpty : QueueStorageState() {
        override val classification = QueueStorageClassification.VALID_EMPTY
    }

    data class ValidWithItems(val items: List<DirectBootQueueItem>) : QueueStorageState() {
        override val classification = QueueStorageClassification.VALID_WITH_ITEMS
    }

    data class Corrupt(val reason: String) : QueueStorageState() {
        override val classification = QueueStorageClassification.CORRUPT
    }

    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : QueueStorageState() {
        override val classification = QueueStorageClassification.UNSUPPORTED_SCHEMA
    }
}

sealed class NormalQueueUpdateResult {
    data class Success(
        val completeQueue: List<DirectBootQueueItem>,
        val retainedNormalCandidates: List<DirectBootQueueItem>
    ) : NormalQueueUpdateResult()

    data class StorageCorrupt(val reason: String) : NormalQueueUpdateResult()
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : NormalQueueUpdateResult()
    data class RejectedIneligible(val reason: String) : NormalQueueUpdateResult()
    data class PersistenceFailed(val details: String, val cause: Throwable? = null) : NormalQueueUpdateResult()
}
