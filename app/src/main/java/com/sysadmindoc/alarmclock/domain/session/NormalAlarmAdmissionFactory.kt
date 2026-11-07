package com.sysadmindoc.alarmclock.domain.session

import com.squareup.moshi.Moshi
import com.sysadmindoc.alarmclock.data.local.entity.AlarmAdmissionTombstoneEntity
import com.sysadmindoc.alarmclock.data.local.entity.AlarmConfigurationSnapshot
import com.sysadmindoc.alarmclock.data.local.entity.AlarmSessionEntity
import java.util.UUID

/**
 * Result of canonical alarm session admission preparation (KNO-37 Phase H2-C2-A).
 * Holds immutable, unpersisted session and tombstone entities ready for atomic Room insertion.
 */
data class PreparedAdmission(
    val session: AlarmSessionEntity,
    val tombstone: AlarmAdmissionTombstoneEntity
)

/**
 * Canonical factory that prepares [AlarmSessionEntity] and [AlarmAdmissionTombstoneEntity]
 * from [AlarmSessionEvent.AdmitSession] without performing database operations (KNO-37 Phase H2-C2-A).
 *
 * Single source of truth for admission entity construction, shared between [AlarmSessionTransitionEngine]
 * and transactional coordinators (such as future NormalAlarmHandoffCoordinator).
 */
open class NormalAlarmAdmissionFactory(
    private val uuidSupplier: () -> String = { UUID.randomUUID().toString() }
) {
    private val moshi: Moshi = Moshi.Builder().build()
    private val snapshotAdapter = moshi.adapter(AlarmConfigurationSnapshot::class.java)

    /**
     * Prepares [AlarmSessionEntity] and [AlarmAdmissionTombstoneEntity] deterministically.
     *
     * Exact semantics preserved from KNO-161:
     * - Snapshot serialization via Moshi
     * - admissionKey format is admission_<alarmId>_<occurrenceKey>
     * - sessionId format is session_<alarmId>_<occurrenceKey>_<uuid>
     * - Absolute deadline is Math.addExact(event.nowEpochMs, 18_000_000L) (5 hours)
     * - Initial sessionState is FIRING, sessionVersion = 1L, creationSequence = 0L (placeholder)
     * - Tombstone horizon is absoluteDeadlineMs
     */
    open fun prepareAdmission(event: AlarmSessionEvent.AdmitSession): PreparedAdmission {
        val snapshot = AlarmConfigurationSnapshot.from(
            alarm = event.alarm,
            settings = event.settings,
            effectiveNormalAutoSilenceMinutes = event.effectiveNormalAutoSilenceMinutes
        )
        val snapshotJson = snapshotAdapter.toJson(snapshot)

        val admissionKey = "admission_${event.alarm.id}_${event.occurrenceKey}"
        val sessionId = "session_${event.alarm.id}_${event.occurrenceKey}_${uuidSupplier()}"

        // Absolute Timeout is exactly 5 hours (18,000,000 ms) from Session initial start (createdAtEpochMs)
        val absoluteDeadlineMs = Math.addExact(event.nowEpochMs, 18_000_000L)

        val sessionEntity = AlarmSessionEntity(
            sessionId = sessionId,
            admissionKey = admissionKey,
            alarmId = event.alarm.id,
            originKind = snapshot.kind,
            originScheduleKind = event.alarm.scheduleKind,
            generation = event.alarm.generation,
            revision = event.alarm.revision,
            fireId = event.fireId,
            occurrenceKey = event.occurrenceKey,
            canonicalBaseTriggerMs = event.canonicalBaseTriggerMs,
            scheduledTargetEpochMs = event.scheduledTargetEpochMs,
            sessionState = AlarmSessionState.FIRING.name,
            sessionVersion = 1L,
            creationSequence = 0L,
            snoozeCount = 0,
            refireEpochMs = 0L,
            challengeUnlocked = false,
            createdAtEpochMs = event.nowEpochMs,
            createdElapsedRealtimeMs = event.elapsedRealtimeMs,
            bootCount = event.bootCount,
            updatedAtEpochMs = event.nowEpochMs,
            absoluteDeadlineEpochMs = absoluteDeadlineMs,
            configurationSnapshotJson = snapshotJson,
            snapshotVersion = 1,
            terminalReason = null,
            completedAtEpochMs = null
        )

        val tombstoneEntity = AlarmAdmissionTombstoneEntity(
            admissionKey = admissionKey,
            alarmId = event.alarm.id,
            sessionId = sessionId,
            occurrenceKey = event.occurrenceKey,
            createdAtEpochMs = event.nowEpochMs,
            horizonEpochMs = absoluteDeadlineMs
        )

        return PreparedAdmission(
            session = sessionEntity,
            tombstone = tombstoneEntity
        )
    }
}
