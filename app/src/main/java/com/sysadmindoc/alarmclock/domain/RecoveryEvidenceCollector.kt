package com.sysadmindoc.alarmclock.domain

import android.util.Log
import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.LocalEvidenceReadResult
import com.sysadmindoc.alarmclock.data.local.LocalIncarnationEvidenceReader
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.service.AlarmService.Companion.ActiveAlarmSnapshot
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

enum class EvidenceReadStatus {
    PROVEN_PRESENT,
    PROVEN_ABSENT,
    READ_FAILURE,
    CORRUPT_DATA,
    STORAGE_UNAVAILABLE,
    UNPROVEN_CROSS_STORE_MISMATCH
}

enum class CrossStoreCoherenceClassification {
    CONSISTENT_OBSERVATION,
    UNPROVEN,
    CONTRADICTORY,
    UNAVAILABLE
}

data class GlobalSessionEvidence(
    val hasGlobalActiveSession: Boolean,
    val activeAlarmId: Long?,
    val isSameAlarm: Boolean
)

data class PersistedSessionEvidence(
    val status: EvidenceReadStatus,
    val snapshot: ActiveAlarmSnapshot?
)

data class LocalMarkerEvidence(
    val status: EvidenceReadStatus,
    val localId: String?
)

data class RoomEvidence(
    val status: EvidenceReadStatus,
    val alarm: Alarm?,
    val authority: ScheduleAuthority?,
    val restoreEpoch: Long?,
    val intents: List<DurableOperationIntent>
)

data class RecoveryEvidenceCollectionReport(
    val alarmId: Long,
    val overallStatus: EvidenceReadStatus,
    val coherenceClassification: CrossStoreCoherenceClassification,
    val globalSessionEvidence: GlobalSessionEvidence,
    val hasMultipleUnresolvedIntents: Boolean,
    val roomEvidence: RoomEvidence,
    val sessionEvidence: PersistedSessionEvidence,
    val localMarkerEvidence: LocalMarkerEvidence
)

/**
 * Read-only recovery evidence collector for gathering all recovery evidence for a specified alarmId.
 *
 * Guarantees:
 * - Read-only: does NOT perform OS operations, start Services, or mutate persistence.
 * - Reads Room records within database.withTransaction for atomicity.
 * - Reads authoritative DataStore persisted ActiveAlarmSnapshot (does NOT rely on memory).
 * - Distinguishes per-source EvidenceReadStatus from CrossStoreCoherenceClassification.
 * - Captures global active session status when another alarm has an active persisted session.
 * - Preserves all DurableOperationIntents without silently discarding multiple or conflicting intents.
 */
@Singleton
class RecoveryEvidenceCollector @Inject constructor(
    private val database: AlarmDatabase,
    private val localReader: LocalIncarnationEvidenceReader,
    private val preferencesManager: PreferencesManager
) {

    companion object {
        private const val TAG = "RecoveryEvidenceCollector"
    }

    suspend fun collectEvidence(alarmId: Long): RecoveryEvidenceCollectionReport {
        // Step 1: Pre-read Local Incarnation Marker & Pre-read Session
        val preLocalRead = try {
            localReader.readLocalEvidence()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local marker pre-read failed for alarm $alarmId", e)
            null
        }

        val preSessionSettings = try {
            preferencesManager.getCurrentSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Pre-read settings failed for alarm $alarmId", e)
            null
        }

        // Step 2: Read Room evidence within database.withTransaction
        val roomResult = try {
            database.withTransaction {
                val alarm = database.alarmDao().getById(alarmId)?.sanitized()
                val authority = database.scheduleAuthorityDao().getAuthorityByAlarmId(alarmId)
                val meta = database.scheduleAuthorityDao().getMetaById(1)
                val intents = database.durableOperationIntentDao().getIntentsByAlarmId(alarmId)

                RoomEvidence(
                    status = if (alarm != null) EvidenceReadStatus.PROVEN_PRESENT else EvidenceReadStatus.PROVEN_ABSENT,
                    alarm = alarm,
                    authority = authority,
                    restoreEpoch = meta?.restoreEpoch,
                    intents = intents
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Room evidence read failed for alarm $alarmId", e)
            RoomEvidence(
                status = EvidenceReadStatus.READ_FAILURE,
                alarm = null,
                authority = null,
                restoreEpoch = null,
                intents = emptyList()
            )
        }

        // Step 3: Read DataStore Persisted Active Session
        val postSessionSettings = try {
            preferencesManager.getCurrentSettings()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "DataStore session read failed for alarm $alarmId", e)
            null
        }

        val sessionResult: PersistedSessionEvidence
        val globalSession: GlobalSessionEvidence

        if (postSessionSettings != null) {
            val activeId = postSessionSettings.activeAlarmId
            if (activeId != null) {
                globalSession = GlobalSessionEvidence(
                    hasGlobalActiveSession = true,
                    activeAlarmId = activeId,
                    isSameAlarm = activeId == alarmId
                )
                if (activeId == alarmId) {
                    val scheduledAt = postSessionSettings.activeAlarmScheduledAt
                    val fireId = postSessionSettings.activeAlarmFireId
                    val firedAt = postSessionSettings.activeAlarmFiredAt
                    val state = postSessionSettings.activeAlarmState
                    val refireAt = postSessionSettings.activeAlarmRefireAt

                    if (state.isBlank() || fireId.isBlank() || scheduledAt <= 0L) {
                        sessionResult = PersistedSessionEvidence(EvidenceReadStatus.CORRUPT_DATA, null)
                    } else if (state !in setOf("FIRING", "SNOOZED")) {
                        sessionResult = PersistedSessionEvidence(EvidenceReadStatus.CORRUPT_DATA, null)
                    } else if (state == "SNOOZED" && refireAt <= 0L) {
                        sessionResult = PersistedSessionEvidence(EvidenceReadStatus.CORRUPT_DATA, null)
                    } else {
                        val snapshot = ActiveAlarmSnapshot(
                            alarmId = activeId,
                            scheduledAt = scheduledAt,
                            fireId = fireId,
                            firedAt = firedAt,
                            state = state,
                            refireAt = refireAt
                        )
                        sessionResult = PersistedSessionEvidence(EvidenceReadStatus.PROVEN_PRESENT, snapshot)
                    }
                } else {
                    sessionResult = PersistedSessionEvidence(EvidenceReadStatus.PROVEN_ABSENT, null)
                }
            } else {
                globalSession = GlobalSessionEvidence(
                    hasGlobalActiveSession = false,
                    activeAlarmId = null,
                    isSameAlarm = false
                )
                sessionResult = PersistedSessionEvidence(EvidenceReadStatus.PROVEN_ABSENT, null)
            }
        } else {
            globalSession = GlobalSessionEvidence(
                hasGlobalActiveSession = false,
                activeAlarmId = null,
                isSameAlarm = false
            )
            sessionResult = PersistedSessionEvidence(EvidenceReadStatus.READ_FAILURE, null)
        }

        // Step 4: Post-read Local Incarnation Marker & Cross-Store Validation
        var localExceptionThrown = false
        val postLocalRead = try {
            localReader.readLocalEvidence()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local marker post-read failed for alarm $alarmId", e)
            localExceptionThrown = true
            null
        }

        val localEvidence = if (postLocalRead != null) {
            when (postLocalRead) {
                is LocalEvidenceReadResult.Success -> {
                    when (postLocalRead.localState) {
                        MarkerState.PRESENT_VALID -> {
                            if (postLocalRead.localId.isNullOrBlank()) {
                                LocalMarkerEvidence(EvidenceReadStatus.CORRUPT_DATA, null)
                            } else {
                                LocalMarkerEvidence(EvidenceReadStatus.PROVEN_PRESENT, postLocalRead.localId)
                            }
                        }
                        MarkerState.CORRUPT -> LocalMarkerEvidence(EvidenceReadStatus.CORRUPT_DATA, null)
                        MarkerState.ABSENT -> LocalMarkerEvidence(EvidenceReadStatus.PROVEN_ABSENT, null)
                        MarkerState.READ_FAILED -> LocalMarkerEvidence(EvidenceReadStatus.READ_FAILURE, null)
                    }
                }
                LocalEvidenceReadResult.StorageUnavailable -> LocalMarkerEvidence(EvidenceReadStatus.STORAGE_UNAVAILABLE, null)
            }
        } else {
            LocalMarkerEvidence(if (localExceptionThrown) EvidenceReadStatus.READ_FAILURE else EvidenceReadStatus.STORAGE_UNAVAILABLE, null)
        }

        // Validate cross-store pre/post local marker and DataStore consistency
        val localConsistent = preLocalRead != null && postLocalRead != null && preLocalRead == postLocalRead
        val sessionConsistent = preSessionSettings != null && postSessionSettings != null && preSessionSettings.activeAlarmId == postSessionSettings.activeAlarmId

        // Detect multiple unresolved intents
        val unresolvedIntents = roomResult.intents.filter { it.phase in setOf("PENDING", "IN_FLIGHT") }
        val hasMultipleUnresolvedIntents = unresolvedIntents.size > 1

        // Coherence Classification
        val coherenceClassification = when {
            localEvidence.status == EvidenceReadStatus.STORAGE_UNAVAILABLE -> CrossStoreCoherenceClassification.UNAVAILABLE
            !localConsistent || !sessionConsistent -> CrossStoreCoherenceClassification.UNPROVEN
            roomResult.status == EvidenceReadStatus.READ_FAILURE -> CrossStoreCoherenceClassification.UNPROVEN
            sessionResult.status == EvidenceReadStatus.READ_FAILURE -> CrossStoreCoherenceClassification.UNPROVEN
            localEvidence.status == EvidenceReadStatus.READ_FAILURE -> CrossStoreCoherenceClassification.UNPROVEN
            
            // Check for contradictory evidence
            roomResult.authority != null && localEvidence.localId != null && roomResult.authority.instanceToken != localEvidence.localId -> {
                CrossStoreCoherenceClassification.CONTRADICTORY
            }
            roomResult.authority != null && roomResult.restoreEpoch != null && roomResult.authority.restoreEpoch != roomResult.restoreEpoch -> {
                CrossStoreCoherenceClassification.CONTRADICTORY
            }
            globalSession.hasGlobalActiveSession && !globalSession.isSameAlarm -> {
                CrossStoreCoherenceClassification.CONTRADICTORY
            }
            sessionResult.status == EvidenceReadStatus.CORRUPT_DATA || localEvidence.status == EvidenceReadStatus.CORRUPT_DATA -> {
                CrossStoreCoherenceClassification.CONTRADICTORY
            }
            else -> CrossStoreCoherenceClassification.CONSISTENT_OBSERVATION
        }

        val overallStatus = when {
            coherenceClassification == CrossStoreCoherenceClassification.UNPROVEN -> EvidenceReadStatus.UNPROVEN_CROSS_STORE_MISMATCH
            roomResult.status == EvidenceReadStatus.READ_FAILURE -> EvidenceReadStatus.READ_FAILURE
            sessionResult.status == EvidenceReadStatus.READ_FAILURE -> EvidenceReadStatus.READ_FAILURE
            sessionResult.status == EvidenceReadStatus.CORRUPT_DATA -> EvidenceReadStatus.CORRUPT_DATA
            localEvidence.status == EvidenceReadStatus.STORAGE_UNAVAILABLE -> EvidenceReadStatus.STORAGE_UNAVAILABLE
            localEvidence.status == EvidenceReadStatus.CORRUPT_DATA -> EvidenceReadStatus.CORRUPT_DATA
            else -> roomResult.status
        }

        Log.i(TAG, "Collected recovery evidence for alarm $alarmId: overallStatus=$overallStatus, coherence=$coherenceClassification, room=${roomResult.status}, session=${sessionResult.status}, local=${localEvidence.status}")

        return RecoveryEvidenceCollectionReport(
            alarmId = alarmId,
            overallStatus = overallStatus,
            coherenceClassification = coherenceClassification,
            globalSessionEvidence = globalSession,
            hasMultipleUnresolvedIntents = hasMultipleUnresolvedIntents,
            roomEvidence = roomResult,
            sessionEvidence = sessionResult,
            localMarkerEvidence = localEvidence
        )
    }
}
