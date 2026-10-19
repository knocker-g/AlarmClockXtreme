package com.sysadmindoc.alarmclock.data.local

import com.sysadmindoc.alarmclock.domain.CoherenceStatus
import com.sysadmindoc.alarmclock.domain.CompletionState
import com.sysadmindoc.alarmclock.domain.CoverageValidationReport
import com.sysadmindoc.alarmclock.domain.IncarnationEvidenceSnapshot
import com.sysadmindoc.alarmclock.domain.MarkerState
import com.sysadmindoc.alarmclock.domain.MetaDescriptor
import com.sysadmindoc.alarmclock.domain.OwnerDescriptor
import com.sysadmindoc.alarmclock.domain.RawIncarnationEvidence
import com.sysadmindoc.alarmclock.domain.RecoveryEvidence
import com.sysadmindoc.alarmclock.domain.RecoveryOperationIdentity
import com.sysadmindoc.alarmclock.domain.RecoveryPhaseEvidence
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CombinedEvidenceResult {
    data class Success(
        val markerSnapshot: IncarnationEvidenceSnapshot,
        val meta: MetaDescriptor?,
        val owners: List<OwnerDescriptor>,
        val report: CoverageValidationReport
    ) : CombinedEvidenceResult

    data class NotDerivable(
        val details: String,
        val meta: MetaDescriptor?,
        val owners: List<OwnerDescriptor>,
        val report: CoverageValidationReport,
        val localState: MarkerState,
        val localId: String?,
        val localCompletion: CompletionState
    ) : CombinedEvidenceResult

    data class StorageUnavailable(val details: String) : CombinedEvidenceResult
    data class ReadFailure(val details: String) : CombinedEvidenceResult
}

@Singleton
class IncarnationEvidenceCombiner @Inject constructor(
    private val roomReader: RoomAuthorityEvidenceReader,
    private val localReader: LocalIncarnationEvidenceReader
) {
    /**
     * Reads Room and Local evidence sources sequentially and combines them into
     * an unproven-coherence observation result.
     *
     * CRITICAL COHERENCE BOUNDARY:
     * Sequential reads across Room and Local storage DO NOT establish atomic
     * cross-storage observation. Therefore, this combiner MUST NOT declare
     * [CoherenceStatus.COHERENT]. The coherence status remains strictly
     * [CoherenceStatus.UNPROVEN_COHERENCE].
     */
    suspend fun combineEvidence(): CombinedEvidenceResult {
        val roomResult = roomReader.readRoomEvidence()
        if (roomResult is RoomEvidenceReadResult.ReadFailure) {
            return CombinedEvidenceResult.ReadFailure(roomResult.details)
        }
        if (roomResult is RoomEvidenceReadResult.StorageUnavailable) {
            return CombinedEvidenceResult.StorageUnavailable("Room database storage is unavailable")
        }

        val localResult = localReader.readLocalEvidence()
        if (localResult is LocalEvidenceReadResult.StorageUnavailable) {
            return CombinedEvidenceResult.StorageUnavailable("Local incarnation marker storage is unavailable")
        }

        val (roomMeta, roomOwners, roomReport) = (roomResult as RoomEvidenceReadResult.Success)
        val (localState, localId, localCompletion) = (localResult as LocalEvidenceReadResult.Success)

        // Room v28/v29 schema contains scheduling authority metadata (MetaDescriptor)
        // and definition owner records (OwnerDescriptor), but does NOT persist a physical
        // Room-side incarnation marker ID or handshake completion state.
        // Therefore, Room-side incarnation marker fields are NOT DERIVABLE from Room schema.
        return CombinedEvidenceResult.NotDerivable(
            details = "Room schema v28/v29 contains no physical incarnation marker columns",
            meta = roomMeta,
            owners = roomOwners,
            report = roomReport,
            localState = localState,
            localId = localId,
            localCompletion = localCompletion
        )
    }

    /**
     * Helper to construct [RawIncarnationEvidence] with explicitly UNPROVEN coherence
     * for downstream adapter testing when a valid [CombinedEvidenceResult.Success] is available.
     *
     * CoherenceStatus MUST remain [CoherenceStatus.UNPROVEN_COHERENCE].
     */
    fun toRawUnprovenEvidence(
        combined: CombinedEvidenceResult.Success,
        initAuthority: RecoveryEvidence = RecoveryEvidence.UNKNOWN,
        historicalEnforcedLedger: RecoveryEvidence = RecoveryEvidence.UNKNOWN,
        operationIdentity: RecoveryOperationIdentity = RecoveryOperationIdentity(epoch = null, token = null),
        phaseEvidence: RecoveryPhaseEvidence = RecoveryPhaseEvidence(phaseName = "UNKNOWN")
    ): RawIncarnationEvidence {
        return RawIncarnationEvidence(
            markerSnapshot = combined.markerSnapshot,
            coherence = CoherenceStatus.UNPROVEN_COHERENCE, // MUST NOT be COHERENT
            initAuthority = initAuthority,
            historicalEnforcedLedger = historicalEnforcedLedger,
            operationIdentity = operationIdentity,
            phaseEvidence = phaseEvidence
        )
    }
}
