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

    /**
     * Reads the active Direct Boot Queue from device-protected storage.
     *
     * Fallback behavior:
     * 1. If V2 queue payload is present and valid, returns deserialized sorted list.
     * 2. If V2 queue payload is absent, or if corrupt/unsupported, checks legacy V1 scalar prefs.
     * 3. If V1 scalar prefs represent an active schedulable alarm, converts and returns single-item queue.
     * 4. Otherwise returns empty list.
     */
    fun readQueue(context: Context, now: Long = 0L): List<DirectBootQueueItem> {
        val prefs = prefs(context)
        val rawPayload = prefs.getString(KEY_QUEUE_PAYLOAD, null)
        if (rawPayload != null) {
            val parsed = parseQueuePayload(rawPayload, now)
            if (parsed != null) {
                return parsed
            }
            Log.w(TAG, "Queue payload present but invalid or unsupported schema version; checking legacy fallback")
        }

        // V1 scalar fallback (only if V2 payload is absent or corrupt/incompatible)
        val v1Snapshot = read(context)
        if (v1Snapshot != null && v1Snapshot.isSchedulable(now)) {
            val v1Item = DirectBootQueueItem.fromV1Snapshot(v1Snapshot)
            return listOf(v1Item)
        }

        return emptyList()
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
     * - Deduplicates by logical identity ([QueueLogicalId]):
     *   - Replaces older generation or same generation candidate with updated state.
     *   - Rejects stale candidates with older generation than currently stored.
     * - Limits queue to nearest [QUEUE_MAX_CAPACITY] (5) items sorted by [DirectBootQueueItem.QUEUE_COMPARATOR].
     * - If candidate falls outside nearest 5, returns [QueueEnqueueResult.DroppedByCapacity].
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

        val rawPayload = prefs(context).getString(KEY_QUEUE_PAYLOAD, null)
        val currentItems = if (rawPayload != null) {
            (parseQueuePayload(rawPayload, now) ?: emptyList()).toMutableList()
        } else {
            mutableListOf()
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

        val persisted = writeQueue(context, retained, now)
        return if (persisted) {
            QueueEnqueueResult.Enqueued(head = retained.first(), queueSize = retained.size)
        } else {
            QueueEnqueueResult.PersistenceFailed("SharedPreferences commit returned false or validation failed")
        }
    }

    /**
     * Removes an entry matching the logical identity ([kind], [alarmId]) from the DE queue.
     * Returns true if an entry was removed and queue persisted, false otherwise.
     */
    fun removeCandidate(context: Context, kind: String, alarmId: Long): Boolean {
        val rawPayload = prefs(context).getString(KEY_QUEUE_PAYLOAD, null) ?: return false
        val currentItems = (parseQueuePayload(rawPayload, 0L) ?: return false).toMutableList()
        val initialSize = currentItems.size
        currentItems.removeAll { it.kind == kind && it.alarmId == alarmId }
        if (currentItems.size == initialSize) {
            return false
        }
        return writeQueue(context, currentItems)
    }

    /**
     * Clears only the V2 queue payload from device-protected storage.
     */
    fun clearQueue(context: Context): Boolean {
        return try {
            prefs(context).edit().remove(KEY_QUEUE_PAYLOAD).commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear queue payload", e)
            false
        }
    }

    private fun parseQueuePayload(rawPayload: String, now: Long): List<DirectBootQueueItem>? {
        return try {
            val root = JSONObject(rawPayload)
            val schemaVersion = root.optInt("schemaVersion", -1)
            if (schemaVersion != DirectBootQueueItem.CURRENT_SCHEMA_VERSION) {
                Log.w(TAG, "Unsupported queue schema version: $schemaVersion (expected ${DirectBootQueueItem.CURRENT_SCHEMA_VERSION})")
                return null
            }
            val itemsArray = root.optJSONArray("items") ?: return emptyList()
            val list = ArrayList<DirectBootQueueItem>(itemsArray.length())
            val seenLogicalIds = HashSet<QueueLogicalId>()
            val seenTokens = HashSet<String>()

            for (i in 0 until itemsArray.length()) {
                val itemObj = itemsArray.optJSONObject(i) ?: continue
                val item = DirectBootQueueItem.fromJson(itemObj)

                // Ensure item is a valid V2 candidate
                val eligibility = item.evaluateEligibility(now = now, allowLegacyV1 = false)
                if (eligibility != QueueCandidateStatus.ELIGIBLE) {
                    Log.w(TAG, "Skipping malformed or ineligible item in queue payload (${item.logicalId}): $eligibility")
                    continue
                }

                // Deduplicate logical IDs in case of malformed stored payload
                if (!seenLogicalIds.add(item.logicalId)) {
                    Log.w(TAG, "Skipping duplicate logicalId in queue payload: ${item.logicalId}")
                    continue
                }

                // Deduplicate non-blank instanceTokens for Normal alarms
                if (item.kind == DirectBootQueueItem.KIND_NORMAL && item.instanceToken.isNotBlank()) {
                    if (!seenTokens.add(item.instanceToken)) {
                        Log.w(TAG, "Skipping duplicate instanceToken in queue payload: ${item.instanceToken}")
                        continue
                    }
                }

                list.add(item)
            }
            list.sortedWith(DirectBootQueueItem.QUEUE_COMPARATOR).take(QUEUE_MAX_CAPACITY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse queue payload; corrupt JSON", e)
            null
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
