package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.DurableOperationIntentDao
import com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult
import com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.service.AlarmService
import javax.inject.Inject
import javax.inject.Singleton

enum class AlarmCohortClassification {
    OWNER_READY,
    ACTIVE_SESSION_OWNED,
    EXPLICITLY_DISABLED,
    QUARANTINED,
    UNKNOWN
}

data class CutoverReadinessReport(
    val totalAlarmCount: Int,
    val enabledAlarmCount: Int,
    val ownerReadyCount: Int,
    val activeSessionCount: Int,
    val quarantinedCount: Int,
    val unknownCount: Int,
    val legacyBypassesReachable: Boolean,
    val cutoverReady: Boolean,
    val classifications: Map<Long, AlarmCohortClassification>
)

/**
 * Read-only cutover readiness assessor for the current Alarm cohort.
 * Does NOT change OS alarms, Authority states, or production routing.
 */
@Singleton
class ScheduleAuthorityPreflightAssessor @Inject constructor(
    private val alarmRepository: AlarmRepository,
    private val authorityDao: ScheduleAuthorityDao,
    private val durableIntentDao: DurableOperationIntentDao,
    private val localReader: LocalIncarnationEvidenceReader,
    private val preferencesManager: PreferencesManager,
    private val scheduleAuthorityOwnerProvider: javax.inject.Provider<ScheduleAuthorityOwner>? = null
) {

    companion object {
        private const val TAG = "ScheduleAuthorityPreflightAssessor"
    }

    suspend fun assessCutoverReadiness(): CutoverReadinessReport {
        val allAlarms = alarmRepository.getAll()
        val classifications = mutableMapOf<Long, AlarmCohortClassification>()

        var enabledCount = 0
        var ownerReadyCount = 0
        var activeSessionCount = 0
        var quarantinedCount = 0
        var unknownCount = 0

        val meta = authorityDao.getMetaById(1)
        val verifiedRestoreEpoch = meta?.restoreEpoch

        val localRead = localReader.readLocalEvidence()
        val currentLocalId = if (localRead is LocalEvidenceReadResult.Success) localRead.localId else null

        val currentSettings = preferencesManager.getCurrentSettings()
        val persistedActiveId = currentSettings.activeAlarmId
        val persistedState = currentSettings.activeAlarmState
        val memoryActiveSnapshot = AlarmService.activeAlarm.get()

        for (alarm in allAlarms) {
            if (!alarm.isEnabled) {
                classifications[alarm.id] = AlarmCohortClassification.EXPLICITLY_DISABLED
                continue
            }

            enabledCount++

            // Check active session
            val isFiring = (persistedActiveId == alarm.id && persistedState == "FIRING") ||
                           (memoryActiveSnapshot != null && memoryActiveSnapshot.alarmId == alarm.id && memoryActiveSnapshot.state == "FIRING")
            val isSnoozed = (persistedActiveId == alarm.id && persistedState == "SNOOZED") ||
                            (memoryActiveSnapshot != null && memoryActiveSnapshot.alarmId == alarm.id && memoryActiveSnapshot.state == "SNOOZED")

            if (isFiring || isSnoozed) {
                classifications[alarm.id] = AlarmCohortClassification.ACTIVE_SESSION_OWNED
                activeSessionCount++
                continue
            }

            // Check missing evidence / restore epoch / incarnation
            if (verifiedRestoreEpoch == null || currentLocalId.isNullOrBlank()) {
                classifications[alarm.id] = AlarmCohortClassification.UNKNOWN
                unknownCount++
                continue
            }

            // Check authority row in DB
            val authority = authorityDao.getAuthorityByAlarmId(alarm.id)
            if (authority == null) {
                classifications[alarm.id] = AlarmCohortClassification.UNKNOWN
                unknownCount++
                continue
            }

            val expectedDefRev = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm)
            val expectedLifecycle = "LIVE"

            // Check unresolved DurableOperationIntents
            val unresolvedIntents = durableIntentDao.getUnresolvedIntentsByAlarmId(alarm.id)
            if (unresolvedIntents.isNotEmpty()) {
                classifications[alarm.id] = AlarmCohortClassification.QUARANTINED
                quarantinedCount++
                continue
            }

            // Check policy unsupported features
            if (alarm.weatherEarlyMinutes > 0 || alarm.smartAlarmEnabled || alarm.hueEnabled) {
                classifications[alarm.id] = AlarmCohortClassification.QUARANTINED
                quarantinedCount++
                continue
            }

            // Validate authority identity
            if (authority.instanceToken != currentLocalId ||
                authority.restoreEpoch != verifiedRestoreEpoch ||
                authority.definitionRevision != expectedDefRev ||
                authority.lifecycle != expectedLifecycle ||
                authority.reconcileState == "QUARANTINE") {
                classifications[alarm.id] = AlarmCohortClassification.QUARANTINED
                quarantinedCount++
                continue
            }

            if (authority.reconcileState == "SETTLED" || authority.reconcileState == "RECONCILING") {
                classifications[alarm.id] = AlarmCohortClassification.OWNER_READY
                ownerReadyCount++
            } else {
                classifications[alarm.id] = AlarmCohortClassification.QUARANTINED
                quarantinedCount++
            }
        }

        // Source-level writer gate: check if legacy bypasses remain reachable
        val legacyBypassesReachable = scheduleAuthorityOwnerProvider == null || scheduleAuthorityOwnerProvider.get() == null

        val isReady = enabledCount > 0 &&
                ownerReadyCount == enabledCount &&
                activeSessionCount == 0 &&
                quarantinedCount == 0 &&
                unknownCount == 0 &&
                !legacyBypassesReachable

        Log.i(TAG, "Assessed cutover readiness: total=${allAlarms.size}, enabled=$enabledCount, ready=$ownerReadyCount, legacyBypassesReachable=$legacyBypassesReachable -> cutoverReady=$isReady")

        return CutoverReadinessReport(
            totalAlarmCount = allAlarms.size,
            enabledAlarmCount = enabledCount,
            ownerReadyCount = ownerReadyCount,
            activeSessionCount = activeSessionCount,
            quarantinedCount = quarantinedCount,
            unknownCount = unknownCount,
            legacyBypassesReachable = legacyBypassesReachable,
            cutoverReady = isReady,
            classifications = classifications
        )
    }
}
