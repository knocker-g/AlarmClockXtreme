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
 * - Requires verified restoreEpoch from ScheduleAuthorityMeta (does NOT manufacture restoreEpoch).
 * - Requires verified LocalEvidenceReadResult.Success with non-blank matching localId. All other outcomes return QUARANTINE.
 * - Detects Definition edits by checking calculated definition fingerprint vs stored definitionRevision.
 * - Recomputes target trigger time using [NextAlarmCalculator] with Pause/Holiday suppression rules.
 * - Returns [BootstrapDecision.QUARANTINE] for unsupported or invalid policy evidence rather than guessing an OS target.
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
        val alarm = alarmRepository.getById(alarmId)?.sanitized()
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

        val currentDefRev = computeDefinitionRevision(alarm)
        val existing = authorityDao.getAuthorityByAlarmId(alarmId)
        if (existing != null) {
            if (existing.definitionRevision != currentDefRev) {
                Log.w(TAG, "Definition edit detected for alarm ${alarm.id} (stored ${existing.definitionRevision} vs current $currentDefRev)")
                return BootstrapDecision.QUARANTINE
            }
            return when (existing.reconcileState) {
                "SETTLED" -> BootstrapDecision.ADOPT
                "RECONCILING" -> BootstrapDecision.RECONCILE
                else -> BootstrapDecision.QUARANTINE
            }
        }

        // Evaluate Pause settings
        if (preferencesManager.getCurrentSettings().pauseUntilMillis > System.currentTimeMillis()) {
            Log.i(TAG, "Pause mode is active; setting desiredOperation = CANCEL for alarm ${alarm.id}")
            val pausedAuthority = ScheduleAuthority(
                alarmId = alarm.id,
                instanceToken = instanceToken,
                definitionRevision = currentDefRev,
                scheduleRevision = 1L,
                lifecycle = if (alarm.isEnabled) "LIVE" else "DISABLED",
                desiredOperation = "CANCEL",
                desiredTriggerMillis = null,
                reconcileState = "RECONCILING",
                restoreEpoch = verifiedRestoreEpoch,
                actionKey = null
            )
            authorityDao.insertAuthority(pausedAuthority)
            return BootstrapDecision.RECONCILE
        }

        val now = ZonedDateTime.now()
        var computedTriggerMillis = if (alarm.isEnabled) {
            try {
                calculator.calculate(alarm, now)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute next trigger for alarm ${alarm.id}", e)
                return BootstrapDecision.QUARANTINE
            }
        } else null

        // Evaluate Holiday suppression rule
        if (alarm.isEnabled && alarm.skipOnHolidays && computedTriggerMillis != null) {
            val date = Instant.ofEpochMilli(computedTriggerMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            if (holidayRepository.isHoliday(date)) {
                Log.i(TAG, "Target date $date is a holiday for alarm ${alarm.id}; quarantining for policy evaluation")
                return BootstrapDecision.QUARANTINE
            }
        }

        val desiredOp = if (alarm.isEnabled && computedTriggerMillis != null && computedTriggerMillis > System.currentTimeMillis()) {
            "SET"
        } else {
            "CANCEL"
        }

        val desiredTrigger = if (desiredOp == "SET") computedTriggerMillis else null

        val newAuthority = ScheduleAuthority(
            alarmId = alarm.id,
            instanceToken = instanceToken,
            definitionRevision = currentDefRev,
            scheduleRevision = 1L,
            lifecycle = if (alarm.isEnabled) "LIVE" else "DISABLED",
            desiredOperation = desiredOp,
            desiredTriggerMillis = desiredTrigger,
            reconcileState = "RECONCILING", // Requires reconciliation; NOT assumed SETTLED!
            restoreEpoch = verifiedRestoreEpoch,
            actionKey = null
        )

        authorityDao.insertAuthority(newAuthority)
        Log.i(TAG, "Bootstrapped missing authority for alarm ${alarm.id} as RECONCILING with calculated trigger $desiredTrigger")
        return BootstrapDecision.RECONCILE
    }
}
