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
 * - Reads LocalIncarnationEvidenceReader and verifies cross-store consistency pre/post read.
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
        // Step 1: Pre-read Local Incarnation Marker
        val preLocalRead = try {
            localReader.readLocalEvidence()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local marker pre-read failed for alarm $alarmId", e)
            LocalEvidenceReadResult.StorageUnavailable
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
        val sessionResult = try {
            val settings = preferencesManager.getCurrentSettings()
            val activeId = settings.activeAlarmId
            if (activeId == alarmId) {
                val scheduledAt = settings.activeAlarmScheduledAt
                val fireId = settings.activeAlarmFireId
                val firedAt = settings.activeAlarmFiredAt
                val state = settings.activeAlarmState
                val refireAt = settings.activeAlarmRefireAt

                if (state.isBlank() || fireId.isBlank() || scheduledAt <= 0L) {
                    PersistedSessionEvidence(EvidenceReadStatus.CORRUPT_DATA, null)
                } else if (state !in setOf("FIRING", "SNOOZED")) {
                    PersistedSessionEvidence(EvidenceReadStatus.CORRUPT_DATA, null)
                } else {
                    val snapshot = ActiveAlarmSnapshot(
                        alarmId = activeId,
                        scheduledAt = scheduledAt,
                        fireId = fireId,
                        firedAt = firedAt,
                        state = state,
                        refireAt = refireAt
                    )
                    PersistedSessionEvidence(EvidenceReadStatus.PROVEN_PRESENT, snapshot)
                }
            } else {
                PersistedSessionEvidence(EvidenceReadStatus.PROVEN_ABSENT, null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "DataStore session read failed for alarm $alarmId", e)
            PersistedSessionEvidence(EvidenceReadStatus.READ_FAILURE, null)
        }

        // Step 4: Post-read Local Incarnation Marker & Cross-Store Validation
        val postLocalRead = try {
            localReader.readLocalEvidence()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Local marker post-read failed for alarm $alarmId", e)
            LocalEvidenceReadResult.StorageUnavailable
        }

        val localEvidence = when (postLocalRead) {
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

        // Validate cross-store pre/post local marker consistency
        val crossStoreConsistent = preLocalRead == postLocalRead

        val overallStatus = when {
            !crossStoreConsistent -> EvidenceReadStatus.UNPROVEN_CROSS_STORE_MISMATCH
            roomResult.status == EvidenceReadStatus.READ_FAILURE -> EvidenceReadStatus.READ_FAILURE
            sessionResult.status == EvidenceReadStatus.READ_FAILURE -> EvidenceReadStatus.READ_FAILURE
            sessionResult.status == EvidenceReadStatus.CORRUPT_DATA -> EvidenceReadStatus.CORRUPT_DATA
            localEvidence.status == EvidenceReadStatus.STORAGE_UNAVAILABLE -> EvidenceReadStatus.STORAGE_UNAVAILABLE
            localEvidence.status == EvidenceReadStatus.CORRUPT_DATA -> EvidenceReadStatus.CORRUPT_DATA
            else -> roomResult.status
        }

        Log.i(TAG, "Collected recovery evidence for alarm $alarmId: overallStatus=$overallStatus, room=${roomResult.status}, session=${sessionResult.status}, local=${localEvidence.status}")

        return RecoveryEvidenceCollectionReport(
            alarmId = alarmId,
            overallStatus = overallStatus,
            roomEvidence = roomResult,
            sessionEvidence = sessionResult,
            localMarkerEvidence = localEvidence
        )
    }
}
