package com.sysadmindoc.alarmclock.domain

import android.database.sqlite.SQLiteConstraintException
import android.util.Log
import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult
import com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.service.AlarmService
import kotlinx.coroutines.CancellationException
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
 * - Quarantines unsupported policy configurations (Weather pre-wake lead, SmartWake window, Hue pre-wake).
 * - Protects active FIRING and SNOOZED sessions (quarantines uncommitted/missing session evidence).
 * - Revalidates Definition, restoreEpoch, incarnation marker, and unresolved DurableIntents atomically inside Room [withTransaction].
 * - Performs post-admission cross-store revalidation fencing; quarantines if local marker changed post-admission.
 * - Catches SQLiteConstraintException specifically for insertion conflicts; does NOT treat generic DB failures as conflicts.
 * - Preserves CancellationException without swallowing.
 * - Returns [BootstrapDecision.QUARANTINE] for unsupported or invalid policy evidence rather than guessing an OS target.
 * - QUARANTINE never issues OS CANCEL, preserving legacy alarm state.
 */
@Singleton
class ScheduleAuthorityBootstrapAdapter @Inject constructor(
    private val database: AlarmDatabase,
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

        // Policy Quarantine for unsupported configurations (Weather lead, SmartWake window, Hue pre-wake)
        if (initialAlarm.weatherEarlyMinutes > 0 || initialAlarm.smartAlarmEnabled || initialAlarm.hueEnabled) {
            Log.w(TAG, "Alarm ${initialAlarm.id} uses unsupported policy (WeatherLead/SmartWake/Hue); QUARANTINING for safety")
            return BootstrapDecision.QUARANTINE
        }

        // Step 2: Validate Active Session Evidence
        val activeSnapshot = AlarmService.activeAlarm.get()
        if (activeSnapshot != null && activeSnapshot.alarmId == alarmId) {
            when (activeSnapshot.state) {
                "FIRING" -> {
                    Log.i(TAG, "Alarm $alarmId is currently FIRING in active snapshot; preserving active session without overwriting")
                    return BootstrapDecision.QUARANTINE
                }
                "SNOOZED" -> {
                    if (activeSnapshot.refireAt <= System.currentTimeMillis()) {
                        Log.w(TAG, "Alarm $alarmId has expired or uncommitted SNOOZED session proof; QUARANTINING")
                        return BootstrapDecision.QUARANTINE
                    }
                }
            }
        }

        val currentSettings = preferencesManager.getCurrentSettings()
        if (currentSettings.pauseUntilMillis > System.currentTimeMillis()) {
            Log.i(TAG, "Pause mode is active; setting desiredOperation = CANCEL for alarm ${initialAlarm.id}")
            val pausedAuthority = ScheduleAuthority(
                alarmId = initialAlarm.id,
                instanceToken = instanceToken,
                definitionRevision = computeDefinitionRevision(initialAlarm),
                scheduleRevision = 1L,
                lifecycle = if (initialAlarm.isEnabled) "LIVE" else "DISABLED",
                desiredOperation = "CANCEL",
                desiredTriggerMillis = null,
                reconcileState = "RECONCILING",
                restoreEpoch = verifiedRestoreEpoch,
                actionKey = null
            )
            return admitAuthorityAtomically(pausedAuthority)
        }

        val now = ZonedDateTime.now()
        val computedTriggerMillis = if (initialAlarm.isEnabled) {
            try {
                calculator.calculate(initialAlarm, now)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute next trigger for alarm ${initialAlarm.id}", e)
                return BootstrapDecision.QUARANTINE
            }
        } else null

        // Evaluate Holiday suppression rule
        if (initialAlarm.isEnabled && initialAlarm.skipOnHolidays && computedTriggerMillis != null) {
            val date = Instant.ofEpochMilli(computedTriggerMillis).atZone(ZoneId.systemDefault()).toLocalDate()
            if (holidayRepository.isHoliday(date)) {
                Log.i(TAG, "Target date $date is a holiday for alarm ${initialAlarm.id}; quarantining for policy evaluation")
                return BootstrapDecision.QUARANTINE
            }
        }

        val desiredOp = if (initialAlarm.isEnabled && computedTriggerMillis != null && computedTriggerMillis > System.currentTimeMillis()) {
            "SET"
        } else {
            "CANCEL"
        }

        val desiredTrigger = if (desiredOp == "SET") computedTriggerMillis else null

        val preparedDefRev = computeDefinitionRevision(initialAlarm)
        val candidateAuthority = ScheduleAuthority(
            alarmId = initialAlarm.id,
            instanceToken = instanceToken,
            definitionRevision = preparedDefRev,
            scheduleRevision = 1L,
            lifecycle = if (initialAlarm.isEnabled) "LIVE" else "DISABLED",
            desiredOperation = desiredOp,
            desiredTriggerMillis = desiredTrigger,
            reconcileState = "RECONCILING", // Always RECONCILING, never SETTLED!
            restoreEpoch = verifiedRestoreEpoch,
            actionKey = null
        )

        // Step 3: Admit (atomic Room withTransaction with cross-store post-admission revalidation)
        return admitAuthorityAtomically(candidateAuthority)
    }

    private suspend fun admitAuthorityAtomically(candidate: ScheduleAuthority): BootstrapDecision {
        val decision = try {
            database.withTransaction {
                // Re-verify inside Room transaction
                val latestAlarm = alarmRepository.getById(candidate.alarmId)?.sanitized()
                    ?: return@withTransaction BootstrapDecision.QUARANTINE

                if (computeDefinitionRevision(latestAlarm) != candidate.definitionRevision) {
                    Log.w(TAG, "Definition edit detected inside transaction for alarm ${candidate.alarmId}; QUARANTINING")
                    return@withTransaction BootstrapDecision.QUARANTINE
                }

                val meta = database.scheduleAuthorityDao().getMetaById(1)
                if (meta?.restoreEpoch != candidate.restoreEpoch) {
                    Log.w(TAG, "Restore epoch changed inside transaction for alarm ${candidate.alarmId}; QUARANTINING")
                    return@withTransaction BootstrapDecision.QUARANTINE
                }

                val unresolvedIntents = database.durableOperationIntentDao().getUnresolvedIntentsByAlarmId(candidate.alarmId)
                if (unresolvedIntents.isNotEmpty()) {
                    Log.w(TAG, "Alarm ${candidate.alarmId} has unresolved DurableIntents inside transaction; QUARANTINING")
                    return@withTransaction BootstrapDecision.QUARANTINE
                }

                val existingInTx = database.scheduleAuthorityDao().getAuthorityByAlarmId(candidate.alarmId)
                if (existingInTx != null) {
                    return@withTransaction when (existingInTx.reconcileState) {
                        "SETTLED" -> BootstrapDecision.ADOPT
                        "RECONCILING" -> BootstrapDecision.RECONCILE
                        else -> BootstrapDecision.QUARANTINE
                    }
                }

                database.scheduleAuthorityDao().insertAuthority(candidate)
                Log.i(TAG, "Admitted authority inside Room transaction for alarm ${candidate.alarmId} as RECONCILING with trigger ${candidate.desiredTriggerMillis}")
                BootstrapDecision.RECONCILE
            }
        } catch (e: CancellationException) {
            throw e // DO NOT SWALLOW CANCELLATION!
        } catch (e: SQLiteConstraintException) {
            Log.w(TAG, "Concurrent insertion conflict for alarm ${candidate.alarmId}; re-reading DB authority", e)
            val existingPostConflict = authorityDao.getAuthorityByAlarmId(candidate.alarmId)
            if (existingPostConflict != null &&
                existingPostConflict.instanceToken == candidate.instanceToken &&
                existingPostConflict.restoreEpoch == candidate.restoreEpoch &&
                existingPostConflict.definitionRevision == candidate.definitionRevision) {
                when (existingPostConflict.reconcileState) {
                    "SETTLED" -> BootstrapDecision.ADOPT
                    "RECONCILING" -> BootstrapDecision.RECONCILE
                    else -> BootstrapDecision.QUARANTINE
                }
            } else {
                BootstrapDecision.QUARANTINE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database persistence failure admitting authority for alarm ${candidate.alarmId}", e)
            return BootstrapDecision.QUARANTINE
        }

        if (decision == BootstrapDecision.RECONCILE) {
            // Step 4: Cross-store post-admission revalidation fencing
            val postRead = localReader.readLocalEvidence()
            if (postRead !is LocalEvidenceReadResult.Success || postRead.localId != candidate.instanceToken) {
                Log.w(TAG, "Post-admission local incarnation marker changed or unavailable for alarm ${candidate.alarmId}; QUARANTINING")
                return BootstrapDecision.QUARANTINE
            }
        }

        return decision
    }
}
