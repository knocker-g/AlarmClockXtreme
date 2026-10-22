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
    val blockerReasons: List<String>,
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

        const val BLOCKER_LEGACY_WRITER_REACHABLE = "LEGACY_WRITER_REACHABLE"
        const val BLOCKER_OS_REGISTRATION_UNPROVEN = "OS_REGISTRATION_UNPROVEN"
        const val BLOCKER_PENDING_RECONCILIATION = "PENDING_RECONCILING"
        const val BLOCKER_DIRECT_BOOT_ORDER_UNSAFE = "DIRECT_BOOT_ORDER_UNSAFE"
        const val BLOCKER_DUPLICATE_NOT_OS_PROOF = "DUPLICATE_NOT_OS_PROOF"
        const val BLOCKER_ACTIVE_SESSION_PROTECTED = "ACTIVE_SESSION_PROTECTED"
        const val BLOCKER_UNRESOLVED_DURABLE_INTENT = "UNRESOLVED_DURABLE_INTENT"
        const val BLOCKER_UNSUPPORTED_SCHEDULING_POLICY = "UNSUPPORTED_SCHEDULING_POLICY"
        const val BLOCKER_UNEXPOSED_AUTHORITY = "UNEXPOSED_AUTHORITY"
    }

    suspend fun assessCutoverReadiness(): CutoverReadinessReport {
        val allAlarms = alarmRepository.getAll()
        val classifications = mutableMapOf<Long, AlarmCohortClassification>()
        val blockerReasons = mutableSetOf<String>()

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
                blockerReasons.add(BLOCKER_ACTIVE_SESSION_PROTECTED)
                continue
            }

            // Check missing evidence / restore epoch / incarnation
            if (verifiedRestoreEpoch == null || currentLocalId.isNullOrBlank()) {
                classifications[alarm.id] = AlarmCohortClassification.UNKNOWN
                unknownCount++
                blockerReasons.add(BLOCKER_UNEXPOSED_AUTHORITY)
                continue
            }

            // Check authority row in DB
            val authority = authorityDao.getAuthorityByAlarmId(alarm.id)
            if (authority == null) {
                classifications[alarm.id] = AlarmCohortClassification.UNKNOWN
                unknownCount++
                blockerReasons.add(BLOCKER_UNEXPOSED_AUTHORITY)
                continue
            }

            val expectedDefRev = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm)
            val expectedLifecycle = "LIVE"

            // Check unresolved DurableOperationIntents
            val unresolvedIntents = durableIntentDao.getUnresolvedIntentsByAlarmId(alarm.id)
            if (unresolvedIntents.isNotEmpty()) {
                classifications[alarm.id] = AlarmCohortClassification.QUARANTINED
                quarantinedCount++
                blockerReasons.add(BLOCKER_UNRESOLVED_DURABLE_INTENT)
                continue
            }

            // Check policy unsupported features
            if (alarm.weatherEarlyMinutes > 0 || alarm.smartAlarmEnabled || alarm.hueEnabled) {
                classifications[alarm.id] = AlarmCohortClassification.QUARANTINED
                quarantinedCount++
                blockerReasons.add(BLOCKER_UNSUPPORTED_SCHEDULING_POLICY)
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
                blockerReasons.add(BLOCKER_UNEXPOSED_AUTHORITY)
                continue
            }

            if (authority.reconcileState == "RECONCILING") {
                classifications[alarm.id] = AlarmCohortClassification.QUARANTINED
                quarantinedCount++
                blockerReasons.add(BLOCKER_PENDING_RECONCILIATION)
            } else if (authority.reconcileState == "SETTLED") {
                classifications[alarm.id] = AlarmCohortClassification.OWNER_READY
                ownerReadyCount++
            } else {
                classifications[alarm.id] = AlarmCohortClassification.QUARANTINED
                quarantinedCount++
                blockerReasons.add(BLOCKER_UNEXPOSED_AUTHORITY)
            }
        }

        // Hard-block cutover: legacy OS writers remain active in production AlarmScheduler/AlarmService
        val legacyBypassesReachable = true
        blockerReasons.add(BLOCKER_LEGACY_WRITER_REACHABLE)
        blockerReasons.add(BLOCKER_DIRECT_BOOT_ORDER_UNSAFE)
        blockerReasons.add(BLOCKER_DUPLICATE_NOT_OS_PROOF)
        blockerReasons.add(BLOCKER_OS_REGISTRATION_UNPROVEN)

        // Hard-blocked cutoverReady = false until Slice D exclusive owner cutover!
        val isReady = false

        Log.i(TAG, "Assessed cutover readiness: total=${allAlarms.size}, enabled=$enabledCount, ready=$ownerReadyCount, legacyBypassesReachable=$legacyBypassesReachable -> cutoverReady=$isReady, blockers=$blockerReasons")

        return CutoverReadinessReport(
            totalAlarmCount = allAlarms.size,
            enabledAlarmCount = enabledCount,
            ownerReadyCount = ownerReadyCount,
            activeSessionCount = activeSessionCount,
            quarantinedCount = quarantinedCount,
            unknownCount = unknownCount,
            legacyBypassesReachable = legacyBypassesReachable,
            cutoverReady = isReady,
            blockerReasons = blockerReasons.toList().sorted(),
            classifications = classifications
        )
    }
}
