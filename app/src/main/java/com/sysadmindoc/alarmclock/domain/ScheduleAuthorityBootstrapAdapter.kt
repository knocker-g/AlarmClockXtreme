package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult
import com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Adapter to safely initialize [ScheduleAuthority] rows for existing enabled [Alarm] definitions lacking authority.
 *
 * Guarantees:
 * - Prepares candidate without DB writes or OS operations.
 * - Requires verified restoreEpoch from ScheduleAuthorityMeta (does NOT manufacture restoreEpoch).
 * - Requires verified LocalEvidenceReadResult.Success with non-blank matching localId. All other outcomes return QUARANTINE.
 * - Re-reads current Alarm definition before admission to detect definition mutations.
 * - Recomputes target trigger time using [NextAlarmCalculator] with Pause/Holiday suppression rules.
 * - Admits via atomic Room insertion as 'RECONCILING'; handles concurrent insertion conflicts gracefully.
 * - Returns [BootstrapDecision.QUARANTINE] for unsupported or invalid policy evidence rather than guessing an OS target.
 * - QUARANTINE never issues OS CANCEL, preserving legacy alarm state.
 */
@Singleton
class ScheduleAuthorityBootstrapAdapter @Inject constructor(
    private val authorityDao: ScheduleAuthorityDao,
    private val alarmRepository: AlarmRepository,
    private val calculator: NextAlarmCalculator,
    private val localReader: LocalIncarnationEvidenceReader,
    private val preferencesManager: PreferencesManager,
    private val holidayRepository: HolidayRepository
) {

    companion object {
        private const val TAG = "ScheduleAuthorityBootstrapAdapter"

        fun computeDefinitionRevision(alarm: Alarm): Long {
            val content = "${alarm.id}:${alarm.hour}:${alarm.minute}:${alarm.repeatDays}:${alarm.label}:${alarm.ringtoneUri}"
            return (content.hashCode().toLong() and 0x7FFFFFFFFFFFFFFFL).coerceAtLeast(1L)
        }
    }

    suspend fun bootstrapAuthorityForAlarm(alarmId: Long, instanceToken: String?): BootstrapDecision {
        // Step 1: Prepare (inspect authoritative providers without DB/OS writes)
        val initialAlarm = alarmRepository.getById(alarmId)?.sanitized()
            ?: return BootstrapDecision.QUARANTINE

        val meta = authorityDao.getMetaById(1)
        val verifiedRestoreEpoch = meta?.restoreEpoch
            ?: return BootstrapDecision.QUARANTINE // Cannot bootstrap without verified restore epoch!

        if (instanceToken.isNullOrBlank()) {
            return BootstrapDecision.QUARANTINE // Cannot bootstrap without verified instance token!
        }

        // Strict Incarnation Verification: local evidence MUST be Success with matching non-blank localId
        val localRead = localReader.readLocalEvidence()
        if (localRead !is LocalEvidenceReadResult.Success || localRead.localId.isNullOrBlank() || localRead.localId != instanceToken) {
            Log.w(TAG, "Local evidence read was NOT Success or localId mismatches instanceToken; QUARANTINING")
            return BootstrapDecision.QUARANTINE
        }

        val existing = authorityDao.getAuthorityByAlarmId(alarmId)
        if (existing != null) {
            val currentDefRev = computeDefinitionRevision(initialAlarm)
            if (existing.definitionRevision != currentDefRev) {
                Log.w(TAG, "Definition edit detected for alarm ${initialAlarm.id} (stored ${existing.definitionRevision} vs current $currentDefRev)")
                return BootstrapDecision.QUARANTINE
            }
            return when (existing.reconcileState) {
                "SETTLED" -> BootstrapDecision.ADOPT
                "RECONCILING" -> BootstrapDecision.RECONCILE
                else -> BootstrapDecision.QUARANTINE
            }
        }

        // Step 2: Validate (re-read current Definition and evaluate full scheduling policy)
        val currentAlarm = alarmRepository.getById(alarmId)?.sanitized()
            ?: return BootstrapDecision.QUARANTINE

        val preparedDefRev = computeDefinitionRevision(currentAlarm)

        // Evaluate Pause settings
        if (preferencesManager.getCurrentSettings().pauseUntilMillis > System.currentTimeMillis()) {
            Log.i(TAG, "Pause mode is active; setting desiredOperation = CANCEL for alarm ${currentAlarm.id}")
            val pausedAuthority = ScheduleAuthority(
                alarmId = currentAlarm.id,
                instanceToken = instanceToken,
                definitionRevision = preparedDefRev,
                scheduleRevision = 1L,
                lifecycle = if (currentAlarm.isEnabled) "LIVE" else "DISABLED",
                desiredOperation = "CANCEL",
                desiredTriggerMillis = null,
                reconcileState = "RECONCILING",
                restoreEpoch = verifiedRestoreEpoch,
                actionKey = null
            )
            return admitAuthorityAtomically(pausedAuthority)
        }

        val now = ZonedDateTime.now()
        val computedTriggerMillis = if (currentAlarm.isEnabled) {
            try {
                calculator.calculate(currentAlarm, now)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute next trigger for alarm ${currentAlarm.id}", e)
                return BootstrapDecision.QUARANTINE
            }
        } else null

        // Evaluate Holiday suppression rule
        if (currentAlarm.isEnabled && currentAlarm.skipOnHolidays && computedTriggerMillis != null) {
            val date = Instant.ofEpochMilli(computedTriggerMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            if (holidayRepository.isHoliday(date)) {
                Log.i(TAG, "Target date $date is a holiday for alarm ${currentAlarm.id}; quarantining for policy evaluation")
                return BootstrapDecision.QUARANTINE
            }
        }

        val desiredOp = if (currentAlarm.isEnabled && computedTriggerMillis != null && computedTriggerMillis > System.currentTimeMillis()) {
            "SET"
        } else {
            "CANCEL"
        }

        val desiredTrigger = if (desiredOp == "SET") computedTriggerMillis else null

        val candidateAuthority = ScheduleAuthority(
            alarmId = currentAlarm.id,
            instanceToken = instanceToken,
            definitionRevision = preparedDefRev,
            scheduleRevision = 1L,
            lifecycle = if (currentAlarm.isEnabled) "LIVE" else "DISABLED",
            desiredOperation = desiredOp,
            desiredTriggerMillis = desiredTrigger,
            reconcileState = "RECONCILING", // Always RECONCILING, never SETTLED!
            restoreEpoch = verifiedRestoreEpoch,
            actionKey = null
        )

        // Step 3: Admit (atomic Room insertion with conflict handling)
        return admitAuthorityAtomically(candidateAuthority)
    }

    private suspend fun admitAuthorityAtomically(candidate: ScheduleAuthority): BootstrapDecision {
        return try {
            authorityDao.insertAuthority(candidate)
            Log.i(TAG, "Admitted authority for alarm ${candidate.alarmId} as RECONCILING with trigger ${candidate.desiredTriggerMillis}")
            BootstrapDecision.RECONCILE
        } catch (e: Exception) {
            Log.w(TAG, "Concurrent insertion conflict for alarm ${candidate.alarmId}; re-reading DB authority", e)
            val existingPostConflict = authorityDao.getAuthorityByAlarmId(candidate.alarmId)
            if (existingPostConflict != null) {
                when (existingPostConflict.reconcileState) {
                    "SETTLED" -> BootstrapDecision.ADOPT
                    "RECONCILING" -> BootstrapDecision.RECONCILE
                    else -> BootstrapDecision.QUARANTINE
                }
            } else {
                BootstrapDecision.QUARANTINE
            }
        }
    }
}
