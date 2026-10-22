package com.sysadmindoc.alarmclock.data.local

import com.sysadmindoc.alarmclock.domain.CoverageValidationReport
import com.sysadmindoc.alarmclock.domain.MetaDescriptor
import com.sysadmindoc.alarmclock.domain.OwnerDescriptor
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RoomEvidenceReadResult {
    data class Success(
        val meta: MetaDescriptor?,
        val owners: List<OwnerDescriptor>,
        val report: CoverageValidationReport
    ) : RoomEvidenceReadResult

    data class ReadFailure(val details: String) : RoomEvidenceReadResult
    object StorageUnavailable : RoomEvidenceReadResult
}

@Singleton
class RoomAuthorityEvidenceReader @Inject constructor(
    private val snapshotReader: AuthoritySnapshotReader
) {
    /**
     * Reads Room schedule authority and metadata evidence via [AuthoritySnapshotReader]
     * inside a single transaction and returns a typed [RoomEvidenceReadResult].
     *
     * Mapped Room fields (v28 schema):
     * - `schedule_authority_meta` (`id`, `restoreEpoch`, `bootstrapState`) -> [MetaDescriptor]
     * - `schedule_authority` (`alarmId`, `instanceToken`, `definitionRevision`, `scheduleRevision`,
     *   `lifecycle`, `desiredOperation`, `desiredTriggerMillis`, `reconcileState`, `restoreEpoch`)
     *   -> [OwnerDescriptor]
     */
    suspend fun readRoomEvidence(): RoomEvidenceReadResult {
        return when (val snapshotResult = snapshotReader.readAuthoritySnapshot()) {
            is RoomSnapshotReadResult.Success -> {
                RoomEvidenceReadResult.Success(
                    meta = snapshotResult.meta,
                    owners = snapshotResult.owners,
                    report = snapshotResult.report
                )
            }
            is RoomSnapshotReadResult.Failure -> {
                RoomEvidenceReadResult.ReadFailure(snapshotResult.details)
            }
        }
    }
}
