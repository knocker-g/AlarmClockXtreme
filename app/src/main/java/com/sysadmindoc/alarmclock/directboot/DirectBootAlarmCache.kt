package com.sysadmindoc.alarmclock.directboot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.text.format.DateFormat
import android.util.Log
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
        val current = read(context)
        if (current == null ||
            !current.isSchedulable(now) ||
            current.alarmId == snapshot.alarmId ||
            snapshot.triggerTime < current.triggerTime
        ) {
            write(context, snapshot)
        }
    }

    fun clear(context: Context) {
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
        val current = read(context)
        if (current?.alarmId == alarmId) {
            cancelScheduledFallback(context, alarmId)
            clear(context)
        }
    }

    fun scheduleCachedAlarm(context: Context, now: Long = System.currentTimeMillis()): Boolean {
        val snapshot = read(context)
        if (snapshot?.isSchedulable(now) != true) {
            clear(context)
            return false
        }

        return try {
            scheduleFallback(context, snapshot)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule direct-boot fallback alarm", e)
            false
        }
    }

    fun cancelScheduledFallback(context: Context, alarmId: Long) {
        val protectedContext = protectedContext(context)
        val pendingIntent = DirectBootAlarmReceiver.pendingIntent(
            context = protectedContext,
            snapshot = DirectBootAlarmSnapshot(
                alarmId = alarmId,
                triggerTime = 1L,
                label = "",
                timeLabel = "",
                playDefaultSound = true,
                vibrationEnabled = true,
                updatedAt = 0L
            ),
            flags = PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        val alarmManager = protectedContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    fun recordFired(context: Context, alarmId: Long, triggerTime: Long, firedAt: Long) {
        val edit = prefs(context).edit()
            .putLong(KEY_FIRED_ALARM_ID, alarmId)
            .putLong(KEY_FIRED_TRIGGER_TIME, triggerTime)
            .putLong(KEY_FIRED_AT, firedAt)
        read(context)?.let { current ->
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
        edit.apply()
    }

    fun consumeFiredOneShotMarker(context: Context, alarm: Alarm, now: Long): Boolean {
        if (alarm.isRecurringSchedule) return false

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
            .apply()
        return true
    }

    fun read(context: Context): DirectBootAlarmSnapshot? {
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

    private val queueLock = Any()

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
            if (schemaVersion != DirectBootQueueItem.CURRENT_SCHEMA_VERSION) {
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

    private fun write(context: Context, snapshot: DirectBootAlarmSnapshot) {
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
            .apply()
    }

    private fun scheduleFallback(context: Context, snapshot: DirectBootAlarmSnapshot) {
        val protectedContext = protectedContext(context)
        val alarmManager = protectedContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val operation = DirectBootAlarmReceiver.pendingIntent(protectedContext, snapshot)
            ?: return

        try {
            alarmManager.setAlarmClock(
                AlarmManager.AlarmClockInfo(snapshot.triggerTime, showIntent(protectedContext)),
                operation
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "setAlarmClock denied during Direct Boot; using exact fallback", e)
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    snapshot.triggerTime,
                    operation
                )
            } catch (fallback: Exception) {
                Log.e(TAG, "Direct-Boot exact fallback also failed", fallback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Direct-Boot alarm registration failed", e)
        }
    }

    private fun showIntent(context: Context): PendingIntent {
        val snapshot = read(context)
        val launch = DirectBootAlarmActivity.intent(
            context = context,
            alarmId = snapshot?.alarmId ?: -1L,
            timeLabel = snapshot?.timeLabel.orEmpty()
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
        return context.applicationContext.createDeviceProtectedStorageContext()
    }

    private fun formatTriggerTime(context: Context, triggerTime: Long): String =
        AlarmTimeFormatter.format(triggerTime, DateFormat.is24HourFormat(context))
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
