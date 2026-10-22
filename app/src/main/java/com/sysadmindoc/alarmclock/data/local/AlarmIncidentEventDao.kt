package com.sysadmindoc.alarmclock.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import kotlinx.coroutines.flow.Flow

@Dao
interface AlarmIncidentEventDao {
    @Insert
    suspend fun insert(event: AlarmIncidentEvent): Long

    @Query("SELECT * FROM alarm_incident_events ORDER BY eventAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 25): Flow<List<AlarmIncidentEvent>>

    @Query("SELECT * FROM alarm_incident_events ORDER BY eventAt DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 25): List<AlarmIncidentEvent>

    /**
     * Count incidents of a given [type] for a specific alarm occurrence. The
     * fire watchdog uses this with [AlarmIncidentEvent.TYPE_BROADCAST] to detect
     * whether AlarmManager ever delivered the fire for `(alarmId, scheduledAt)`:
     * zero broadcast rows means the alarm was silently suppressed.
     */
    @Query(
        "SELECT COUNT(*) FROM alarm_incident_events " +
            "WHERE alarmId = :alarmId AND scheduledAt = :scheduledAt AND type = :type"
    )
    suspend fun countByOccurrenceAndType(alarmId: Long, scheduledAt: Long, type: String): Int

    /**
     * Count positive accepted delivery evidence for a specific alarm occurrence.
     * Uses an EXPLICIT TUPLE ALLOWLIST matching exact verified user-facing delivery producer call sites:
     * - ('NOTIFICATION', 'SUCCEEDED', 'ALARM_NOTIFICATION_POSTED')
     * - ('AUDIO', 'SUCCEEDED', 'MEDIA3_PLAYER_STARTED')
     * - ('AUDIO', 'SUCCEEDED', 'MEDIA_PLAYER_STARTED')
     * - ('AUDIO', 'SUCCEEDED', 'DEFAULT_FALLBACK_STARTED')
     * - ('AUDIO', 'SUCCEEDED', 'MEDIA3_INTERNET_RADIO_STARTED')
     * - ('AUDIO', 'SUCCEEDED', 'SPOTIFY_PLAYBACK_CONFIRMED')
     *
     * Excludes START_COMMAND_RECEIVED, PLACEHOLDER_FOREGROUND_STARTED, volume/media-session updates,
     * duplicate command suppression, requested, failed, or unknown events.
     */
    @Query(
        """
        SELECT COUNT(*) FROM alarm_incident_events
        WHERE alarmId = :alarmId
          AND scheduledAt = :scheduledAt
          AND status = 'SUCCEEDED'
          AND (
            (type = 'NOTIFICATION' AND reasonCode = 'ALARM_NOTIFICATION_POSTED')
            OR (type = 'AUDIO' AND reasonCode IN (
                'MEDIA3_PLAYER_STARTED',
                'MEDIA_PLAYER_STARTED',
                'DEFAULT_FALLBACK_STARTED',
                'MEDIA3_INTERNET_RADIO_STARTED',
                'SPOTIFY_PLAYBACK_CONFIRMED'
            ))
          )
        """
    )
    suspend fun countPositiveAcceptedDeliveryByOccurrence(
        alarmId: Long,
        scheduledAt: Long
    ): Int

    @Query("DELETE FROM alarm_incident_events WHERE eventAt < :beforeMs")
    suspend fun deleteOlderThan(beforeMs: Long)

    @Query(
        """
        DELETE FROM alarm_incident_events
        WHERE id NOT IN (
            SELECT id FROM alarm_incident_events
            ORDER BY eventAt DESC, id DESC
            LIMIT :maxRows
        )
        """
    )
    suspend fun trimToLatest(maxRows: Int)

    @Query("DELETE FROM alarm_incident_events")
    suspend fun deleteAll()
}
