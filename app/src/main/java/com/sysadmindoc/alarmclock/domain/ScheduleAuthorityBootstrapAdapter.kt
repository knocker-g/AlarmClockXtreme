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
 * - Admits initial candidate as inert 'QUARANTINE' state inside Room [withTransaction].
 * - Closes promotion race by re-evaluating Definition, restoreEpoch, session evidence, and unresolved intents inside promotion [withTransaction].
 * - Promotes to 'RECONCILING' via generation-qualified CAS inside promotion transaction.
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

        val expectedLifecycle = if (initialAlarm.isEnabled) "LIVE" else "DISABLED"
        val currentDefRev = computeDefinitionRevision(initialAlarm)

        val existing = authorityDao.getAuthorityByAlarmId(alarmId)
        if (existing != null) {
            // Full ownership validation: instanceToken, restoreEpoch, definitionRevision, lifecycle
            if (existing.instanceToken != instanceToken ||
                existing.restoreEpoch != verifiedRestoreEpoch ||
                existing.definitionRevision != currentDefRev ||
                existing.lifecycle != expectedLifecycle) {
                Log.w(TAG, "Existing authority validation failed for alarm ${initialAlarm.id}; QUARANTINING")
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
        if (isSessionActiveOrUncertain(alarmId)) {
            Log.i(TAG, "Alarm $alarmId has active FIRING/SNOOZED session or uncertain session evidence; QUARANTINING")
            return BootstrapDecision.QUARANTINE
        }

        val currentSettings = preferencesManager.getCurrentSettings()
        if (currentSettings.pauseUntilMillis > System.currentTimeMillis()) {
            Log.i(TAG, "Pause mode is active; setting desiredOperation = CANCEL for alarm ${initialAlarm.id}")
            val pausedAuthority = ScheduleAuthority(
                alarmId = initialAlarm.id,
                instanceToken = instanceToken,
                definitionRevision = currentDefRev,
                scheduleRevision = 1L,
                lifecycle = expectedLifecycle,
                desiredOperation = "CANCEL",
                desiredTriggerMillis = null,
                reconcileState = "QUARANTINE", // Insert initial candidate as inert QUARANTINE!
                restoreEpoch = verifiedRestoreEpoch,
                actionKey = null
            )
            return admitAndPromoteAuthority(pausedAuthority)
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

        val candidateAuthority = ScheduleAuthority(
            alarmId = initialAlarm.id,
            instanceToken = instanceToken,
            definitionRevision = currentDefRev,
            scheduleRevision = 1L,
            lifecycle = expectedLifecycle,
            desiredOperation = desiredOp,
            desiredTriggerMillis = desiredTrigger,
            reconcileState = "QUARANTINE", // Insert initial candidate as inert QUARANTINE!
            restoreEpoch = verifiedRestoreEpoch,
            actionKey = null
        )

        // Step 3: Admit as inert QUARANTINE, then promote via Transactional CAS
        return admitAndPromoteAuthority(candidateAuthority)
    }

    private suspend fun isSessionActiveOrUncertain(alarmId: Long): Boolean {
        val currentSettings = preferencesManager.getCurrentSettings()
        val persistedActiveId = currentSettings.activeAlarmId
        val persistedState = currentSettings.activeAlarmState
        val memoryActiveSnapshot = AlarmService.activeAlarm.get()

        val isFiring = (persistedActiveId == alarmId && persistedState == "FIRING") ||
                       (memoryActiveSnapshot != null && memoryActiveSnapshot.alarmId == alarmId && memoryActiveSnapshot.state == "FIRING")

        val isSnoozed = (persistedActiveId == alarmId && persistedState == "SNOOZED") ||
                        (memoryActiveSnapshot != null && memoryActiveSnapshot.alarmId == alarmId && memoryActiveSnapshot.state == "SNOOZED")

        if (isFiring || isSnoozed) return true

        if (memoryActiveSnapshot != null && memoryActiveSnapshot.alarmId == alarmId && memoryActiveSnapshot.state !in setOf("FIRING", "SNOOZED")) {
            return true
        }

        return false
    }

    private suspend fun admitAndPromoteAuthority(candidate: ScheduleAuthority): BootstrapDecision {
        val inertAdmitted = try {
            database.withTransaction {
                // Re-verify inside Room transaction
                val latestAlarm = alarmRepository.getById(candidate.alarmId)?.sanitized()
                    ?: return@withTransaction false

                if (computeDefinitionRevision(latestAlarm) != candidate.definitionRevision) {
                    Log.w(TAG, "Definition edit detected inside transaction for alarm ${candidate.alarmId}; QUARANTINING")
                    return@withTransaction false
                }

                val meta = database.scheduleAuthorityDao().getMetaById(1)
                if (meta?.restoreEpoch != candidate.restoreEpoch) {
                    Log.w(TAG, "Restore epoch changed inside transaction for alarm ${candidate.alarmId}; QUARANTINING")
                    return@withTransaction false
                }

                val unresolvedIntents = database.durableOperationIntentDao().getUnresolvedIntentsByAlarmId(candidate.alarmId)
                if (unresolvedIntents.isNotEmpty()) {
                    Log.w(TAG, "Alarm ${candidate.alarmId} has unresolved DurableIntents inside transaction; QUARANTINING")
                    return@withTransaction false
                }

                val existingInTx = database.scheduleAuthorityDao().getAuthorityByAlarmId(candidate.alarmId)
                if (existingInTx != null) {
                    return@withTransaction false
                }

                // Insert initial authority as inert QUARANTINE state
                val inertCandidate = candidate.copy(reconcileState = "QUARANTINE")
                database.scheduleAuthorityDao().insertAuthority(inertCandidate)
                Log.i(TAG, "Admitted inert QUARANTINE authority inside Room transaction for alarm ${candidate.alarmId}")
                true
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
                return when (existingPostConflict.reconcileState) {
                    "SETTLED" -> BootstrapDecision.ADOPT
                    "RECONCILING" -> BootstrapDecision.RECONCILE
                    else -> BootstrapDecision.QUARANTINE
                }
            } else {
                return BootstrapDecision.QUARANTINE
            }
        } catch (e: Exception) {
            Log.e(TAG, "Database persistence failure admitting authority for alarm ${candidate.alarmId}", e)
            return BootstrapDecision.QUARANTINE
        }

        if (!inertAdmitted) {
            val existing = authorityDao.getAuthorityByAlarmId(candidate.alarmId)
            if (existing != null &&
                existing.instanceToken == candidate.instanceToken &&
                existing.restoreEpoch == candidate.restoreEpoch &&
                existing.definitionRevision == candidate.definitionRevision) {
                return when (existing.reconcileState) {
                    "SETTLED" -> BootstrapDecision.ADOPT
                    "RECONCILING" -> BootstrapDecision.RECONCILE
                    else -> BootstrapDecision.QUARANTINE
                }
            }
            return BootstrapDecision.QUARANTINE
        }

        // Cross-store post-admission revalidation fencing
        val postRead = localReader.readLocalEvidence()
        if (postRead !is LocalEvidenceReadResult.Success || postRead.localId != candidate.instanceToken) {
            Log.w(TAG, "Post-admission local incarnation marker changed or unavailable for alarm ${candidate.alarmId}; REMAINS INERT QUARANTINE")
            return BootstrapDecision.QUARANTINE
        }

        // Re-verify session eligibility before promotion
        if (isSessionActiveOrUncertain(candidate.alarmId)) {
            Log.w(TAG, "Active session detected immediately before promotion for alarm ${candidate.alarmId}; REMAINS INERT QUARANTINE")
            return BootstrapDecision.QUARANTINE
        }

        // Final Promotion Transaction: Close promotion race inside single Room withTransaction
        val promoted = try {
            database.withTransaction {
                val latestAlarmInTx = alarmRepository.getById(candidate.alarmId)?.sanitized()
                    ?: return@withTransaction false

                if (computeDefinitionRevision(latestAlarmInTx) != candidate.definitionRevision) {
                    Log.w(TAG, "Definition edit detected in promotion transaction for alarm ${candidate.alarmId}; QUARANTINING")
                    return@withTransaction false
                }

                val metaInTx = database.scheduleAuthorityDao().getMetaById(1)
                if (metaInTx?.restoreEpoch != candidate.restoreEpoch) {
                    Log.w(TAG, "Restore epoch changed in promotion transaction for alarm ${candidate.alarmId}; QUARANTINING")
                    return@withTransaction false
                }

                val unresolvedInTx = database.durableOperationIntentDao().getUnresolvedIntentsByAlarmId(candidate.alarmId)
                if (unresolvedInTx.isNotEmpty()) {
                    Log.w(TAG, "Unresolved DurableIntents detected in promotion transaction for alarm ${candidate.alarmId}; QUARANTINING")
                    return@withTransaction false
                }

                val existingInPromotionTx = database.scheduleAuthorityDao().getAuthorityByAlarmId(candidate.alarmId)
                if (existingInPromotionTx == null ||
                    existingInPromotionTx.instanceToken != candidate.instanceToken ||
                    existingInPromotionTx.restoreEpoch != candidate.restoreEpoch ||
                    existingInPromotionTx.scheduleRevision != candidate.scheduleRevision ||
                    existingInPromotionTx.lifecycle != candidate.lifecycle ||
                    existingInPromotionTx.reconcileState != "QUARANTINE") {
                    Log.w(TAG, "Promotion CAS pre-condition failed in transaction for alarm ${candidate.alarmId}; QUARANTINING")
                    return@withTransaction false
                }

                val updatedRows = database.scheduleAuthorityDao().activateAuthorityToReconciling(
                    alarmId = candidate.alarmId,
                    instanceToken = candidate.instanceToken,
                    restoreEpoch = candidate.restoreEpoch,
                    scheduleRevision = candidate.scheduleRevision,
                    lifecycle = candidate.lifecycle
                )

                updatedRows == 1
            }
        } catch (e: CancellationException) {
            throw e // DO NOT SWALLOW CANCELLATION!
        } catch (e: Exception) {
            Log.e(TAG, "Promotion transaction failed for alarm ${candidate.alarmId}; REMAINS INERT QUARANTINE", e)
            false
        }

        return if (promoted) {
            Log.i(TAG, "Successfully promoted authority for alarm ${candidate.alarmId} from QUARANTINE to RECONCILING in transaction")
            BootstrapDecision.RECONCILE
        } else {
            Log.w(TAG, "Promotion transaction returned false for alarm ${candidate.alarmId}; REMAINS INERT QUARANTINE")
            BootstrapDecision.QUARANTINE
        }
    }
}
