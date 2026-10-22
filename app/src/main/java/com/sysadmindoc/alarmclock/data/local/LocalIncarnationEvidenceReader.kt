package com.sysadmindoc.alarmclock.data.local

import com.sysadmindoc.alarmclock.domain.CompletionState
import com.sysadmindoc.alarmclock.domain.MarkerState
import javax.inject.Inject
import javax.inject.Singleton

sealed interface LocalEvidenceReadResult {
    data class Success(
        val localState: MarkerState,
        val localId: String?,
        val localCompletion: CompletionState
    ) : LocalEvidenceReadResult

    object StorageUnavailable : LocalEvidenceReadResult
}

@Singleton
class LocalIncarnationEvidenceReader @Inject constructor(
    private val markerStore: LocalIncarnationMarkerStore
) {
    /**
     * Reads local incarnation evidence from [LocalIncarnationMarkerStore] and maps it to
     * domain evidence fields ([MarkerState], [localId], [CompletionState]).
     *
     * Returns [LocalEvidenceReadResult]:
     * - [LocalEvidenceReadResult.Success] containing mapped local evidence.
     * - [LocalEvidenceReadResult.StorageUnavailable] if storage cannot be accessed.
     */
    fun readLocalEvidence(): LocalEvidenceReadResult {
        return when (val result = markerStore.readMarker()) {
            is LocalMarkerReadResult.Present -> {
                LocalEvidenceReadResult.Success(
                    localState = MarkerState.PRESENT_VALID,
                    localId = result.payload.incarnationId,
                    localCompletion = result.payload.completionState
                )
            }
            LocalMarkerReadResult.Absent -> {
                LocalEvidenceReadResult.Success(
                    localState = MarkerState.ABSENT,
                    localId = null,
                    localCompletion = CompletionState.UNKNOWN
                )
            }
            LocalMarkerReadResult.Corrupt -> {
                LocalEvidenceReadResult.Success(
                    localState = MarkerState.CORRUPT,
                    localId = null,
                    localCompletion = CompletionState.UNKNOWN
                )
            }
            LocalMarkerReadResult.ReadFailure -> {
                LocalEvidenceReadResult.Success(
                    localState = MarkerState.READ_FAILED,
                    localId = null,
                    localCompletion = CompletionState.UNKNOWN
                )
            }
            LocalMarkerReadResult.Unavailable -> {
                LocalEvidenceReadResult.StorageUnavailable
            }
        }
    }
}
