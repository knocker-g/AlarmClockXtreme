package com.sysadmindoc.alarmclock.directboot

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.domain.CoverageReasonCode
import com.sysadmindoc.alarmclock.domain.CoverageResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result of coordinating CE snapshot reading and Normal candidate planning.
 * Pure in-memory representation; does not authorize or perform storage mutations.
 */
sealed interface DirectBootNormalCoordinationResult {
    /**
     * Successfully produced 1 to 5 authoritative Normal Direct Boot candidates.
     */
    data class ReadyWithCandidates(
        val candidates: List<DirectBootQueueItem>
    ) : DirectBootNormalCoordinationResult

    /**
     * Successfully evaluated a coherent snapshot with zero eligible Normal alarms.
     * Indicates all Normal alarms are disabled or in the past.
     * Does NOT authorize clearing mixed-kind DE queues.
     */
    data object ReadyEmpty : DirectBootNormalCoordinationResult

    /**
     * CE storage is locked (Direct Boot state) or unlock check failed closed.
     * Database was not queried.
     */
    data object CeLocked : DirectBootNormalCoordinationResult

    /**
     * CE Room database read failure (e.g. SQLite error or I/O failure).
     * Details are sanitized to avoid leaking user alarm data.
     */
    data class CeReadFailure(
        val details: String
    ) : DirectBootNormalCoordinationResult

    /**
     * Blocked because one or more active Normal alarms have unresolved authorities
     * (reconcileState is PENDING, QUARANTINE, or RETRY_REQUIRED).
     * Prohibits partial authoritative replacement.
     */
    data class BlockedUnresolvedAuthority(
        val alarmId: Long,
        val reconcileState: String,
        val reason: String
    ) : DirectBootNormalCoordinationResult

    /**
     * Blocked because ScheduleAuthority coverage validation failed.
     */
    data class BlockedInvalidCoverage(
        val result: CoverageResult,
        val reasonCode: CoverageReasonCode,
        val details: String
    ) : DirectBootNormalCoordinationResult

    /**
     * Blocked due to restore epoch mismatch or blank identity token.
     */
    data class BlockedInvalidEpochOrIdentity(
        val reason: String
    ) : DirectBootNormalCoordinationResult

    /**
     * Blocked due to internal snapshot inconsistency (e.g. definition revision drift).
     */
    data class BlockedInconsistentSnapshot(
        val reason: String
    ) : DirectBootNormalCoordinationResult
}

/**
 * Inactive in-memory coordinator combining [DirectBootNormalSnapshotReader]
 * and [DirectBootNormalCandidatePlanner].
 *
 * Enforces:
 * - Pure read-only orchestration without touching DE storage, V1 storage, or AlarmManager.
 * - Single explicit evaluation timestamp [now] passed to candidate planner.
 * - Explicit handling of metadata cardinality (0 -> canonical Planner missing-meta, 1 -> meta, >1 -> BlockedInvalidCoverage).
 * - Fail-closed mapping of all reader and planner outcomes.
 * - Explicit [kotlinx.coroutines.CancellationException] propagation.
 */
@Singleton
class DirectBootNormalProjectionCoordinator @Inject constructor(
    private val snapshotReader: DirectBootNormalSnapshotReader
) {
    /**
     * Captures wall-clock time once and delegates to [coordinate(now)].
     */
    suspend fun coordinate(): DirectBootNormalCoordinationResult {
        return coordinate(now = System.currentTimeMillis())
    }

    /**
     * Reads a coherent CE snapshot from Room via [snapshotReader] and evaluates it
     * into authoritative Normal Direct Boot candidates via [DirectBootNormalCandidatePlanner]
     * using the explicit evaluation timestamp [now].
     */
    suspend fun coordinate(now: Long): DirectBootNormalCoordinationResult {
        val readerResult = snapshotReader.readSnapshot()

        return when (readerResult) {
            is DirectBootNormalSnapshotResult.CeLocked -> {
                DirectBootNormalCoordinationResult.CeLocked
            }
            is DirectBootNormalSnapshotResult.ReadFailure -> {
                DirectBootNormalCoordinationResult.CeReadFailure(readerResult.details)
            }
            is DirectBootNormalSnapshotResult.Success -> {
                val metaRows = readerResult.snapshot.metaRows
                val meta: ScheduleAuthorityMeta? = when (metaRows.size) {
                    0 -> null
                    1 -> metaRows[0]
                    else -> {
                        return DirectBootNormalCoordinationResult.BlockedInvalidCoverage(
                            result = CoverageResult.INVALID,
                            reasonCode = CoverageReasonCode.META_ID_INVALID,
                            details = "Multiple schedule_authority_meta rows detected: ${metaRows.size}"
                        )
                    }
                }

                when (val planResult = DirectBootNormalCandidatePlanner.planCandidates(
                    alarms = readerResult.snapshot.alarms,
                    authorities = readerResult.snapshot.authorities,
                    meta = meta,
                    coverageReport = null,
                    now = now
                )) {
                    is NormalCandidatePlanResult.ValidWithCandidates -> {
                        DirectBootNormalCoordinationResult.ReadyWithCandidates(planResult.candidates)
                    }
                    is NormalCandidatePlanResult.ValidEmpty -> {
                        DirectBootNormalCoordinationResult.ReadyEmpty
                    }
                    is NormalCandidatePlanResult.UnresolvedAuthority -> {
                        DirectBootNormalCoordinationResult.BlockedUnresolvedAuthority(
                            alarmId = planResult.alarmId,
                            reconcileState = planResult.reconcileState,
                            reason = planResult.reason
                        )
                    }
                    is NormalCandidatePlanResult.InvalidCoverage -> {
                        DirectBootNormalCoordinationResult.BlockedInvalidCoverage(
                            result = planResult.result,
                            reasonCode = planResult.reasonCode,
                            details = planResult.details
                        )
                    }
                    is NormalCandidatePlanResult.InvalidEpochOrIdentity -> {
                        DirectBootNormalCoordinationResult.BlockedInvalidEpochOrIdentity(planResult.reason)
                    }
                    is NormalCandidatePlanResult.InconsistentSnapshot -> {
                        DirectBootNormalCoordinationResult.BlockedInconsistentSnapshot(planResult.reason)
                    }
                }
            }
        }
    }
}
