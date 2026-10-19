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

        // Map Room-side marker state from Room meta & owners
        val (roomState, roomId, roomCompletion) = deriveRoomMarkerState(roomMeta, roomOwners)

        val markerSnapshot = IncarnationEvidenceSnapshot(
            roomState = roomState,
            roomId = roomId,
            roomCompletion = roomCompletion,
            localState = localState,
            localId = localId,
            localCompletion = localCompletion
        )

        return CombinedEvidenceResult.Success(
            markerSnapshot = markerSnapshot,
            meta = roomMeta,
            owners = roomOwners,
            report = roomReport
        )
    }

    /**
     * Maps Room-side metadata and owners into Room-side [MarkerState], ID, and [CompletionState].
     *
     * Note: Room v28 schema persists scheduling authority metadata ([MetaDescriptor]) and
     * definition owner records ([OwnerDescriptor]), but does NOT persist a physical Room-side
     * incarnation marker ID or handshake completion state.
     *
     * Therefore, Room-side incarnation marker fields are non-derivable and represent explicit
     * absence ([MarkerState.ABSENT], null ID, [CompletionState.UNKNOWN]). Owner instanceToken and
     * bootstrapState must NOT be reinterpreted as incarnation marker evidence.
     */
    private fun deriveRoomMarkerState(
        meta: MetaDescriptor?,
        owners: List<OwnerDescriptor>
    ): Triple<MarkerState, String?, CompletionState> {
        return Triple(MarkerState.ABSENT, null, CompletionState.UNKNOWN)
    }

    /**
     * Helper to construct [RawIncarnationEvidence] with explicitly UNPROVEN coherence
     * for downstream adapter testing.
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
