package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Typed classification of Direct Boot Normal Projection results.
 * Explicitly distinguishes cross-storage failure and verification states.
 */
sealed interface DirectBootNormalProjectionResult {
    /**
     * True if a DE storage write was attempted (i.e. commit was invoked on SharedPreferences).
     * If true, callers cannot assume previous state is preserved.
     */
    val deWriteAttempted: Boolean

    /**
     * True ONLY if DE storage was guaranteed NOT to have been accessed or modified.
     * Guaranteed no-write holds for rejections occurring before SharedPreferences commit.
     */
    val isGuaranteedNoWrite: Boolean

    /**
     * Coordinator blocked projection (e.g. CE storage locked, unresolved authority, invalid coverage).
     * DE storage was not accessed or modified.
     */
    data class CoordinatorBlocked(
        val coordinationResult: DirectBootNormalCoordinationResult
    ) : DirectBootNormalProjectionResult {
        override val deWriteAttempted: Boolean get() = false
        override val isGuaranteedNoWrite: Boolean get() = true
    }

    /**
     * Re-read CE state inside Room transaction did not match planning snapshot fingerprint.
     * Indicates concurrent CE mutation occurred during planning.
     * DE storage was not modified.
     */
    data class FingerprintMismatch(
        val expectedFingerprint: CeSnapshotFingerprint,
        val actualFingerprint: CeSnapshotFingerprint
    ) : DirectBootNormalProjectionResult {
        override val deWriteAttempted: Boolean get() = false
        override val isGuaranteedNoWrite: Boolean get() = true
    }

    /**
     * DE Queue storage rejected write before modification (e.g. storage corrupt, unsupported schema, candidate provenance mismatch).
     * DE storage was not modified and raw bytes were preserved.
     */
    data class DeWriteRejected(
        val reason: DeRejectionReason
    ) : DirectBootNormalProjectionResult {
        override val deWriteAttempted: Boolean get() = false
        override val isGuaranteedNoWrite: Boolean get() = true
    }

    /**
     * Pre-write failure: an exception occurred before DE writer was invoked (e.g. SQLite read error).
     * DE storage was NOT accessed or modified (guaranteed no-write).
     */
    data class PreWriteFailure(
        val details: String,
        val cause: Throwable? = null
    ) : DirectBootNormalProjectionResult {
        override val deWriteAttempted: Boolean get() = false
        override val isGuaranteedNoWrite: Boolean get() = true
    }

    /**
     * DE commit returned false or threw an exception during persistence.
     * Persistence was attempted; the resulting state in storage or in-memory is UNCERTAIN (NOT a guaranteed no-write).
     * Room transaction rollback does NOT revert SharedPreferences memory or disk modifications.
     * Reconciliation requires re-reading fresh CE state and projecting anew.
     */
    data class DeCommitFailed(
        val details: String,
        val cause: Throwable? = null
    ) : DirectBootNormalProjectionResult {
        override val deWriteAttempted: Boolean get() = true
        override val isGuaranteedNoWrite: Boolean get() = false
    }

    /**
     * Normal projection successfully written to DE storage and confirmed.
     * Contains only the Normal candidates actually retained within the global 5-item capacity.
     */
    data class Success(
        val enqueuedNormalCandidates: List<DirectBootQueueItem>,
        val completeQueue: List<DirectBootQueueItem>,
        val fingerprint: CeSnapshotFingerprint
    ) : DirectBootNormalProjectionResult {
        override val deWriteAttempted: Boolean get() = true
        override val isGuaranteedNoWrite: Boolean get() = false
    }

    /**
     * Coroutine cancellation occurred after DE persistence was confirmed.
     * DE contains the updated state, but the caller's coroutine was cancelled.
     */
    data class CancellationAfterWrite(
        val fingerprint: CeSnapshotFingerprint,
        val cancellation: CancellationException
    ) : DirectBootNormalProjectionResult {
        override val deWriteAttempted: Boolean get() = true
        override val isGuaranteedNoWrite: Boolean get() = false
    }

    /**
     * Ambiguous outcome: DE write was attempted or confirmed, but subsequent operations
     * (such as post-write hook or Room transaction closure) failed with an exception.
     */
    data class AmbiguousOutcome(
        val deWriteConfirmed: Boolean,
        val details: String,
        val cause: Throwable? = null
    ) : DirectBootNormalProjectionResult {
        override val deWriteAttempted: Boolean get() = true
        override val isGuaranteedNoWrite: Boolean get() = false
    }
}

/**
 * Typed reasons for DE write rejection before modification.
 */
sealed interface DeRejectionReason {
    data class StorageCorrupt(val details: String) : DeRejectionReason
    data class UnsupportedSchema(val foundVersion: Int, val expectedVersion: Int) : DeRejectionReason
    data class IneligibleCandidate(val details: String) : DeRejectionReason
    data class ProvenanceViolation(val details: String) : DeRejectionReason
}

/**
 * Inactive Normal Direct Boot Queue Projector.
 * Orchestrates atomic CE snapshot re-verification and bounded owner-aware DE queue update.
 *
 * Enforces:
 * - Direct Boot Normal Projector owns Normal projection only.
 * - Existing Quick entries retain Quick ownership.
 * - Enters Room [withTransaction] to re-read authoritative facts and recompute fingerprint.
 * - Strict structural fingerprint equality: rejects stale snapshots without modifying DE.
 * - Strict candidate provenance verification: revalidates supplied candidates against canonical planner.
 * - If verified, delegates bounded queue update to [DirectBootAlarmCache.updateNormalQueue] under in-process lock.
 * - Exits Room transaction only after DE persistence produces an explicit result.
 * - Distinguishes cross-storage failure boundaries: Room rollback cannot reverse DE write.
 * - Zero runtime activation or V1 modification.
 */
@Singleton
class DirectBootNormalProjector(
    @ApplicationContext private val context: Context,
    private val database: AlarmDatabase,
    private val coordinator: DirectBootNormalProjectionCoordinator,
    private val queueUpdater: (Context, List<DirectBootQueueItem>, Long) -> NormalQueueUpdateResult = { ctx, items, now ->
        DirectBootAlarmCache.updateNormalQueue(ctx, items, now)
    },
    private val preWriteHook: suspend () -> Unit = {},
    private val postWriteHook: suspend () -> Unit = {}
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        database: AlarmDatabase,
        coordinator: DirectBootNormalProjectionCoordinator
    ) : this(
        context = context,
        database = database,
        coordinator = coordinator,
        queueUpdater = { ctx, items, now ->
            DirectBootAlarmCache.updateNormalQueue(ctx, items, now)
        },
        preWriteHook = {},
        postWriteHook = {}
    )

    private enum class ExecutionStage {
        BEFORE_WRITE,
        WRITER_INVOKED,
        WRITE_CONFIRMED
    }

    /**
     * Evaluates Coordinator at wall-clock time [now] and projects results to DE queue.
     */
    suspend fun project(
        now: Long = System.currentTimeMillis()
    ): DirectBootNormalProjectionResult {
        val coordinationResult = coordinator.coordinate(now)
        return project(coordinationResult = coordinationResult, now = now)
    }

    /**
     * Projects a known [DirectBootNormalCoordinationResult] to the DE queue with atomic CE re-verification.
     */
    suspend fun project(
        coordinationResult: DirectBootNormalCoordinationResult,
        now: Long = System.currentTimeMillis()
    ): DirectBootNormalProjectionResult {
        val (expectedFingerprint, plannedCandidates) = when (coordinationResult) {
            is DirectBootNormalCoordinationResult.ReadyWithCandidates -> {
                coordinationResult.fingerprint to coordinationResult.candidates
            }
            is DirectBootNormalCoordinationResult.ReadyEmpty -> {
                coordinationResult.fingerprint to emptyList<DirectBootQueueItem>()
            }
            is DirectBootNormalCoordinationResult.CeLocked,
            is DirectBootNormalCoordinationResult.CeReadFailure,
            is DirectBootNormalCoordinationResult.BlockedUnresolvedAuthority,
            is DirectBootNormalCoordinationResult.BlockedInvalidCoverage,
            is DirectBootNormalCoordinationResult.BlockedInvalidEpochOrIdentity,
            is DirectBootNormalCoordinationResult.BlockedInconsistentSnapshot -> {
                // All blocked Coordinator outcomes must produce zero DE mutations.
                return DirectBootNormalProjectionResult.CoordinatorBlocked(coordinationResult)
            }
        }

        var stage = ExecutionStage.BEFORE_WRITE
        return try {
            database.withTransaction {
                val alarms = database.alarmDao().getAll()
                val authorities = database.scheduleAuthorityDao().getAllAuthorities()
                val metaRows = database.scheduleAuthorityDao().getAllMeta()
                val currentFingerprint = CeSnapshotFingerprint.create(alarms, authorities, metaRows)

                if (currentFingerprint != expectedFingerprint) {
                    return@withTransaction DirectBootNormalProjectionResult.FingerprintMismatch(
                        expectedFingerprint = expectedFingerprint,
                        actualFingerprint = currentFingerprint
                    )
                }

                // -------------------------------------------------------------
                // Candidate Provenance & Immutability Verification (B1C5B)
                // -------------------------------------------------------------
                val canonicalPlan = DirectBootNormalCandidatePlanner.planCandidates(
                    alarms = alarms,
                    authorities = authorities,
                    metaRows = metaRows,
                    now = now
                )
                val canonicalCandidates: List<DirectBootQueueItem> = when (canonicalPlan) {
                    is NormalCandidatePlanResult.ValidWithCandidates -> canonicalPlan.candidates
                    NormalCandidatePlanResult.ValidEmpty -> emptyList()
                    is NormalCandidatePlanResult.UnresolvedAuthority -> {
                        return@withTransaction DirectBootNormalProjectionResult.CoordinatorBlocked(
                            DirectBootNormalCoordinationResult.BlockedUnresolvedAuthority(
                                alarmId = canonicalPlan.alarmId,
                                reconcileState = canonicalPlan.reconcileState,
                                reason = canonicalPlan.reason
                            )
                        )
                    }
                    is NormalCandidatePlanResult.InvalidCoverage -> {
                        return@withTransaction DirectBootNormalProjectionResult.CoordinatorBlocked(
                            DirectBootNormalCoordinationResult.BlockedInvalidCoverage(
                                result = canonicalPlan.result,
                                reasonCode = canonicalPlan.reasonCode,
                                details = canonicalPlan.details
                            )
                        )
                    }
                    is NormalCandidatePlanResult.InvalidEpochOrIdentity -> {
                        return@withTransaction DirectBootNormalProjectionResult.CoordinatorBlocked(
                            DirectBootNormalCoordinationResult.BlockedInvalidEpochOrIdentity(canonicalPlan.reason)
                        )
                    }
                    is NormalCandidatePlanResult.InconsistentSnapshot -> {
                        return@withTransaction DirectBootNormalProjectionResult.CoordinatorBlocked(
                            DirectBootNormalCoordinationResult.BlockedInconsistentSnapshot(canonicalPlan.reason)
                        )
                    }
                }

                if (plannedCandidates != canonicalCandidates) {
                    return@withTransaction DirectBootNormalProjectionResult.DeWriteRejected(
                        reason = DeRejectionReason.ProvenanceViolation(
                            "Supplied candidates do not match authoritative planner output for verified snapshot"
                        )
                    )
                }

                preWriteHook()

                // Mark writer invoked before calling queueUpdater
                stage = ExecutionStage.WRITER_INVOKED
                val updateResult = queueUpdater(context, canonicalCandidates.toList(), now)
                when (updateResult) {
                    is NormalQueueUpdateResult.Success -> {
                        stage = ExecutionStage.WRITE_CONFIRMED
                        postWriteHook()
                        DirectBootNormalProjectionResult.Success(
                            enqueuedNormalCandidates = updateResult.retainedNormalCandidates,
                            completeQueue = updateResult.completeQueue,
                            fingerprint = currentFingerprint
                        )
                    }
                    is NormalQueueUpdateResult.StorageCorrupt -> {
                        stage = ExecutionStage.BEFORE_WRITE
                        DirectBootNormalProjectionResult.DeWriteRejected(
                            DeRejectionReason.StorageCorrupt(updateResult.reason)
                        )
                    }
                    is NormalQueueUpdateResult.UnsupportedSchema -> {
                        stage = ExecutionStage.BEFORE_WRITE
                        DirectBootNormalProjectionResult.DeWriteRejected(
                            DeRejectionReason.UnsupportedSchema(
                                foundVersion = updateResult.foundVersion,
                                expectedVersion = updateResult.expectedVersion
                            )
                        )
                    }
                    is NormalQueueUpdateResult.RejectedIneligible -> {
                        stage = ExecutionStage.BEFORE_WRITE
                        DirectBootNormalProjectionResult.DeWriteRejected(
                            DeRejectionReason.IneligibleCandidate(updateResult.reason)
                        )
                    }
                    is NormalQueueUpdateResult.PersistenceFailed -> {
                        DirectBootNormalProjectionResult.DeCommitFailed(
                            details = updateResult.details,
                            cause = updateResult.cause
                        )
                    }
                }
            }
        } catch (cancellation: CancellationException) {
            when (stage) {
                ExecutionStage.WRITE_CONFIRMED -> {
                    DirectBootNormalProjectionResult.CancellationAfterWrite(
                        fingerprint = expectedFingerprint,
                        cancellation = cancellation
                    )
                }
                ExecutionStage.WRITER_INVOKED -> {
                    DirectBootNormalProjectionResult.AmbiguousOutcome(
                        deWriteConfirmed = false,
                        details = "Cancellation during or immediately following DE writer invocation",
                        cause = cancellation
                    )
                }
                ExecutionStage.BEFORE_WRITE -> {
                    throw cancellation
                }
            }
        } catch (e: Exception) {
            when (stage) {
                ExecutionStage.WRITE_CONFIRMED -> {
                    DirectBootNormalProjectionResult.AmbiguousOutcome(
                        deWriteConfirmed = true,
                        details = "Room transaction closure failed after confirmed DE write: ${e.message}",
                        cause = e
                    )
                }
                ExecutionStage.WRITER_INVOKED -> {
                    DirectBootNormalProjectionResult.AmbiguousOutcome(
                        deWriteConfirmed = false,
                        details = "Exception during DE persistence attempt: ${e.message}",
                        cause = e
                    )
                }
                ExecutionStage.BEFORE_WRITE -> {
                    DirectBootNormalProjectionResult.PreWriteFailure(
                        details = "Pre-write failure inside Room transaction: ${e.message}",
                        cause = e
                    )
                }
            }
        }
    }
}
