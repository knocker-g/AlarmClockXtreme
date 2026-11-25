package com.sysadmindoc.alarmclock.domain

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.evaluator.DurableCancelIntentValidator
import kotlinx.coroutines.CancellationException

enum class RealOsEligibility {
    BLOCKED
}

enum class BSpecificCancelIntegrationOutcome {
    FAKE_OS_CANCEL_OBSERVED_NOT_REAL_PROOF,
    ADMISSION_REJECTED_STALE_OR_MISMATCH,
    DUPLICATE_REQUEST_REJECTED,
    PI_IDENTITY_REJECTED,
    B_TO_C_RACE_ABORTED,
    PI_ABSENT_NO_DESTRUCTIVE_CLEANUP,
    OS_OUTCOME_UNKNOWN_PRESERVED,
    OS_EXCEPTION_PRESERVED
}

data class BSpecificCancelRequest(
    val alarmId: Long,
    val targetIssueToken: String,
    val targetFireId: String,
    val targetScheduledAt: Long,
    val expectedAlarmGeneration: Int,
    val expectedAlarmRevision: Int,
    val expectedSlotGenId: Long,
    val expectedSlotOwnerToken: String?,
    val expectedOwnerToken: String,
    val expectedRestoreEpoch: Long,
    val expectedScheduleRevision: Long,
    val expectedDefinitionRevision: Long,
    val nowEpochMs: Long,
    val deLineageStatus: DeLineageStatus = DeLineageStatus.UNCERTAIN,
    val includeFollowUpWorkers: Boolean = false
)

sealed interface FakeOsLookupObservation {
    data class Found(
        val descriptor: PendingIntentIdentityDescriptor,
        val lineage: OccurrenceLineageDescriptor
    ) : FakeOsLookupObservation

    data object Absent : FakeOsLookupObservation
}

sealed interface FakeOsCancelOutcome {
    data object Cancelled : FakeOsCancelOutcome
    data object NotFound : FakeOsCancelOutcome
    data class Unknown(val reason: String) : FakeOsCancelOutcome
    data class ExceptionThrown(val cause: Throwable) : FakeOsCancelOutcome
}

/**
 * Fake-only OS boundary interface for inactive Rev2 B-specific cancellation verification.
 * Intentionally has ZERO real Android AlarmManager / PendingIntent implementations in production.
 */
interface BSpecificOsCancelGateway {
    fun lookupMainSlot(
        alarmId: Long,
        targetIssueToken: String,
        expectedDescriptor: PendingIntentIdentityDescriptor
    ): FakeOsLookupObservation

    fun cancelMainSlot(
        alarmId: Long,
        targetIssueToken: String,
        verifiedDescriptor: PendingIntentIdentityDescriptor
    ): FakeOsCancelOutcome
}

data class BSpecificCancelIntegrationResult(
    val outcome: BSpecificCancelIntegrationOutcome,
    val alarmId: Long,
    val targetIssueToken: String,
    val p3cIntentIssued: Boolean,
    val p3cCasRowsUpdated: Int,
    val durableIntentPhaseAfter: String?,
    val identitySafety: CrossIdentitySafety?,
    val boundaryReport: BoundaryEvaluationReport,
    val bToCRaceDetected: Boolean,
    val fakeOsLookupInvoked: Boolean,
    val fakeOsCancelInvoked: Boolean,
    val realOsEligibility: RealOsEligibility = RealOsEligibility.BLOCKED,
    val realOsEligibilityReason: String = "POST_DB_CAS_BINDER_TOCTOU_UNEXCLUDED",
    val realOsCalls: Int = 0
)

/**
 * Test/classification cutpoints for G2 inactive Rev2 replay recovery (no Room schema or persisted status added).
 */
enum class Rev2ReplayCrashCutpoint {
    C0_PRE_INTENT_ISSUE,
    C1_POST_PENDING_PERSISTED,
    C2_AROUND_IN_FLIGHT_CAS,
    C3_AROUND_OS_LOOKUP,
    C4_AROUND_FAKE_CANCEL_REQUEST,
    C5_AROUND_FAKE_RESULT_RECEIPT,
    C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME
}

enum class Rev2ReplayRoute {
    DELEGATED_TO_LEGACY_REV1,
    REV2_INACTIVE_REPLAY,
    UNKNOWN_REVISION_FAIL_CLOSED
}

enum class Rev2ReplayRecoveryOutcome {
    LEGACY_REV1_PRESERVED,
    UNKNOWN_REVISION_FAIL_CLOSED,
    C0_PRE_INTENT_ABSENT,
    PENDING_SAFE_B_CANDIDATE_HOLD,
    PENDING_STALE_OR_CONFLICT_ISOLATED,
    IN_FLIGHT_CRASH_EVIDENCE_MISSING_HOLD,
    PI_ABSENT_HOLD,
    PI_IDENTITY_REJECTED_ISOLATED,
    IN_FLIGHT_FAKE_RESULT_NOT_REAL_PROOF_HOLD,
    IN_FLIGHT_UNKNOWN_OS_RESTART_HOLD,
    B_TO_C_RACE_ISOLATED
}

data class Rev2ReplayInspectionContext(
    val alarmId: Long,
    val targetIssueToken: String,
    val evaluationTimeMillis: Long,
    val expectedAlarmGeneration: Int? = null,
    val expectedAlarmRevision: Int? = null,
    val expectedSlotGenId: Long? = null,
    val expectedSlotOwnerToken: String? = null,
    val expectedOwnerToken: String? = null,
    val expectedRestoreEpoch: Long? = null,
    val expectedScheduleRevision: Long? = null,
    val expectedDefinitionRevision: Long? = null,
    val crashCutpointHint: Rev2ReplayCrashCutpoint? = null,
    val priorFakeOsOutcome: FakeOsCancelOutcome? = null,
    val deLineageStatus: DeLineageStatus = DeLineageStatus.UNCERTAIN,
    val includeFollowUpWorkers: Boolean = false
)

data class Rev2ReplayRecoveryResult(
    val route: Rev2ReplayRoute,
    val outcome: Rev2ReplayRecoveryOutcome,
    val crashCutpoint: Rev2ReplayCrashCutpoint,
    val alarmId: Long,
    val targetIssueToken: String,
    val intentPhaseBefore: String?,
    val intentPhaseAfter: String?,
    val completedSettlementCount: Int = 0,
    val abortedTransitionCount: Int = 0,
    val asyncCleanupScheduled: Boolean = false,
    val successorCProtected: Boolean = true,
    val adjudicatorDecision: RecoveryDecision?,
    val identitySafety: CrossIdentitySafety?,
    val boundaryReport: BoundaryEvaluationReport,
    val fakeOsLookupInvoked: Boolean,
    val fakeOsCancelInvoked: Boolean = false,
    val realOsCalls: Int = 0,
    val realOsEligibility: RealOsEligibility = RealOsEligibility.BLOCKED,
    val detail: String
)

data class MainV2FakeSetRequest(
    val alarmId: Long,
    val issueToken: String,
    val fireId: String,
    val scheduledAt: Long,
    val expectedAlarmGeneration: Int,
    val expectedOwnerToken: String,
    val expectedRestoreEpoch: Long,
    val expectedScheduleRevision: Long,
    val expectedDefinitionRevision: Long,
    val nowEpochMs: Long
)

enum class MainV2FakeSetOutcome {
    FAKE_OS_SET_ACTIVATED_INACTIVE,
    REJECTED_INVALID_INPUT,
    REJECTED_DB_RESERVATION_OR_WITNESS,
    REJECTED_FAKE_OS_FAILURE
}

data class MainV2FakeSetResult(
    val outcome: MainV2FakeSetOutcome,
    val alarmId: Long,
    val issueToken: String,
    val activated: Boolean,
    val currentSlotTokenAfter: String?,
    val realOsCalls: Int = 0,
    val realOsEligibility: RealOsEligibility = RealOsEligibility.BLOCKED
)

interface MainV2FakeOsSetGateway {
    fun setMainSlot(
        alarmId: Long,
        issueToken: String,
        scheduledAt: Long,
        descriptor: PendingIntentIdentityDescriptor,
        lineage: OccurrenceLineageDescriptor
    ): Boolean
}

enum class BootCountDisposition {
    HOLD
}

enum class Schema38BootCountClassificationReason {
    MISSING_SIDECAR,
    BOOT_COUNT_UNAVAILABLE,
    BOOT_COUNT_MISMATCH,
    DB_SIDECAR_INCONSISTENCY,
    OS_RESULT_UNKNOWN,
    CROSS_RESTORE_ABA_SUSPECTED,
    LEGACY_PI_ALIAS_SUSPECTED,
    MATCHED_WARM_REBOOT_NOT_OS_COMPLETION_PROOF,
    WITNESS_MISMATCH_OR_STALE
}

enum class PersistedSidecarBootCountStatus {
    PRESENT,
    MISSING_SIDECAR,
    CORRUPTED
}

data class OptionalBootCountSidecarEvidence(
    val status: PersistedSidecarBootCountStatus,
    val recordedOperationToken: String? = null,
    val recordedAlarmId: Long? = null,
    val recordedBootCount: Long? = null,
    val recordedRestoreEpoch: Long? = null,
    val recordedOwnerToken: String? = null
)

data class CurrentBootCountEvidence(
    val isAvailable: Boolean,
    val currentBootCount: Long? = null
)

data class Schema38BootCountClassificationInput(
    val persistedIntent: DurableOperationIntent,
    val sidecarEvidence: OptionalBootCountSidecarEvidence?,
    val currentBootCount: CurrentBootCountEvidence,
    val currentAlarm: Alarm?,
    val currentAuthority: ScheduleAuthority?,
    val targetOccurrence: IssuedOccurrence?,
    val currentSlot: IssueSlot?,
    val expectedAlarmGeneration: Int? = null,
    val expectedAlarmRevision: Int? = null,
    val expectedOwnerToken: String? = null,
    val expectedRestoreEpoch: Long? = null,
    val expectedScheduleRevision: Long? = null,
    val expectedDefinitionRevision: Long? = null,
    val observedOsLookupStatus: PendingIntentLookupStatus = PendingIntentLookupStatus.NOT_ATTEMPTED,
    val observedOsCancelOutcome: FakeOsCancelOutcome? = null,
    val observedPiDescriptor: PendingIntentIdentityDescriptor? = null,
    val priorExpectedRestoreEpoch: Long? = null
)

data class Schema38BootCountClassificationResult(
    val disposition: BootCountDisposition = BootCountDisposition.HOLD,
    val reason: Schema38BootCountClassificationReason,
    val provesOsCancelSuccess: Boolean = false,
    val completedSettlementCount: Int = 0,
    val abortedSettlementFromOsCount: Int = 0,
    val realOsEligibility: RealOsEligibility = RealOsEligibility.BLOCKED,
    val flagNoCreateAloneInsufficient: Boolean = true,
    val detail: String
)

data class Rev2BatchReplayReport(
    val results: List<Rev2ReplayRecoveryResult>,
    val totalUnresolvedScanned: Int,
    val legacyRev1DelegatedCount: Int,
    val rev2ClassifiedCount: Int,
    val unknownFailClosedCount: Int,
    val completedSettlementCount: Int = 0,
    val abortedTransitionCount: Int = 0,
    val realOsCalls: Int = 0
)

/**
 * Inactive Production Integration for KNO-75 G1/G2 Rev2 B-specific cancellation & replay recovery.
 *
 * Architectural guarantees:
 * - Inactive / dormant: NOT wired into any active production scheduling or recovery path.
 * - Uses a single [AlarmDatabase] instance for all DAOs (`issuedOccurrenceDao`, `scheduleAuthorityDao`,
 *   `alarmDao`, `durableOperationIntentDao`).
 * - Reuses P3C `issueBSpecificCancelIntentTransaction()` and `casTransitionBSpecificCancelIntentToInFlight()`
 *   without relaxing CAS conditions or introducing a generic Admission Engine / Coordinator.
 * - Verifies MAIN PendingIntent identity via [PendingIntentIdentityComparator.evaluateSafety] before
 *   invoking any Fake OS cancellation.
 * - Reuses [CancellationExecutionBoundary.evaluate] for all 8 side-effect classifications.
 * - Reuses [RecoveryAdjudicator.adjudicate] for authority/epoch/definition/OS-outcome checks without
 *   blindly applying Legacy `incarnationId == instanceToken` assumptions to Rev2 B-specific intents.
 * - Never executes real `AlarmManager.cancel`, never promotes Fake OS results to `PHASE_COMPLETED`,
 *   and always keeps [RealOsEligibility.BLOCKED] because DB CAS cannot exclude post-CAS Binder TOCTOU.
 */
class InactiveBSpecificCancelIntegration(
    private val database: AlarmDatabase,
    private val adjudicator: RecoveryAdjudicator = RecoveryAdjudicator(),
    private val scheduleAuthorityOwner: ScheduleAuthorityOwner = ScheduleAuthorityOwner(
        authorityDao = database.scheduleAuthorityDao(),
        mainSlotApplier = MainSlotApplier { true },
        exactPermissionChecker = { true }
    )
) {
    companion object {
        const val REQUEST_CODE_ISSUED_OCCURRENCE = 80001
        const val MAIN_PACKAGE = "com.sysadmindoc.alarmclock"
        const val MAIN_RECEIVER = "com.sysadmindoc.alarmclock.receiver.AlarmReceiver"
        const val MAIN_ACTION = "com.sysadmindoc.alarmclock.ALARM_FIRE"

        fun expectedMainV2Descriptor(
            alarmId: Long,
            targetIssueToken: String
        ): PendingIntentIdentityDescriptor {
            val encodedToken = PendingIntentIdentityComparator.encodePercentUtf8(targetIssueToken)
            return PendingIntentIdentityDescriptor(
                operationType = PendingIntentOperationType.BROADCAST,
                creatorPackage = MAIN_PACKAGE,
                creatorUserScope = 0,
                targetComponent = MAIN_RECEIVER,
                intentAction = MAIN_ACTION,
                intentDataUri = "alarmclock://alarm/issue?alarmId=$alarmId&token=$encodedToken",
                intentMimeType = null,
                intentCategories = emptySet(),
                requestCode = REQUEST_CODE_ISSUED_OCCURRENCE,
                flags = PendingIntentIdentityComparator.FLAG_IMMUTABLE,
                isLegacyV1Alias = false
            )
        }

        /**
         * Pure, fail-closed Schema 38 bootCount recovery classification (G4-B B3).
         * - Zero sidecar SharedPreferences/AtomicFile I/O.
         * - Zero Room schema changes (Schema 38).
         * - Never interprets matching bootCount or FLAG_NO_CREATE == null as OS cancel proof.
         * - Always fixes COMPLETED_SETTLEMENT=0, ABORTED_SETTLEMENT_FROM_OS=0, REAL_OS_ELIGIBILITY=BLOCKED.
         */
        fun classifySchema38BootCountRecovery(
            input: Schema38BootCountClassificationInput
        ): Schema38BootCountClassificationResult {
            fun hold(
                reason: Schema38BootCountClassificationReason,
                detail: String
            ): Schema38BootCountClassificationResult = Schema38BootCountClassificationResult(
                disposition = BootCountDisposition.HOLD,
                reason = reason,
                provesOsCancelSuccess = false,
                completedSettlementCount = 0,
                abortedSettlementFromOsCount = 0,
                realOsEligibility = RealOsEligibility.BLOCKED,
                flagNoCreateAloneInsufficient = true,
                detail = detail
            )

            val intent = input.persistedIntent
            val alarm = input.currentAlarm
            val sa = input.currentAuthority
            val occ = input.targetOccurrence
            val slot = input.currentSlot
            val sidecar = input.sidecarEvidence

            if (input.observedPiDescriptor != null) {
                val pi = input.observedPiDescriptor
                if (pi.computedIsLegacyV1Alias ||
                    !pi.isPositiveTokenizedV2 ||
                    pi.requestCode != REQUEST_CODE_ISSUED_OCCURRENCE
                ) {
                    return hold(
                        Schema38BootCountClassificationReason.LEGACY_PI_ALIAS_SUSPECTED,
                        "Observed PendingIntent lacks strict MAIN_V2 identity or aliases Legacy/non-MAIN_V2 PI; holding"
                    )
                }
            }

            if (sa != null && (
                    (input.priorExpectedRestoreEpoch != null && sa.restoreEpoch != input.priorExpectedRestoreEpoch) ||
                        (intent.expectedRestoreEpoch != null && sa.restoreEpoch != intent.expectedRestoreEpoch) ||
                        (intent.expectedOwnerToken != null && sa.instanceToken != intent.expectedOwnerToken)
                    )
            ) {
                return hold(
                    Schema38BootCountClassificationReason.CROSS_RESTORE_ABA_SUSPECTED,
                    "Authority restoreEpoch/instanceToken shifted relative to persisted Rev2 intent; holding against cross-restore ABA"
                )
            }
            if (sidecar != null && sa != null &&
                sidecar.recordedRestoreEpoch != null &&
                sidecar.recordedRestoreEpoch != sa.restoreEpoch
            ) {
                return hold(
                    Schema38BootCountClassificationReason.CROSS_RESTORE_ABA_SUSPECTED,
                    "Sidecar restoreEpoch ${sidecar.recordedRestoreEpoch} differs from authority ${sa.restoreEpoch}; holding"
                )
            }

            val bindingValid = DurableCancelIntentValidator.verifyBSpecificCancelBinding(
                intent = intent,
                targetOccurrence = occ,
                currentSlot = slot
            )
            val witnessMatch = bindingValid &&
                alarm != null &&
                sa != null &&
                occ != null &&
                occ.alarmGeneration == alarm.generation &&
                occ.definitionRevision == sa.definitionRevision &&
                (input.expectedAlarmGeneration == null || alarm.generation == input.expectedAlarmGeneration) &&
                (input.expectedAlarmRevision == null || alarm.revision == input.expectedAlarmRevision) &&
                (input.expectedOwnerToken == null || sa.instanceToken == input.expectedOwnerToken) &&
                (input.expectedRestoreEpoch == null || sa.restoreEpoch == input.expectedRestoreEpoch) &&
                (input.expectedScheduleRevision == null || sa.scheduleRevision == input.expectedScheduleRevision) &&
                (input.expectedDefinitionRevision == null || sa.definitionRevision == input.expectedDefinitionRevision)

            if (!witnessMatch) {
                return hold(
                    Schema38BootCountClassificationReason.WITNESS_MISMATCH_OR_STALE,
                    "Persisted Rev2 / authority / slot / occurrence witnesses do not match strictly; holding"
                )
            }

            if (sidecar == null || sidecar.status == PersistedSidecarBootCountStatus.MISSING_SIDECAR) {
                return hold(
                    Schema38BootCountClassificationReason.MISSING_SIDECAR,
                    "No bootCount sidecar persisted for operationToken=${intent.operationToken}; fail-closed HOLD"
                )
            }

            if (sidecar.status == PersistedSidecarBootCountStatus.CORRUPTED ||
                (sidecar.recordedOperationToken != null && sidecar.recordedOperationToken != intent.operationToken) ||
                (sidecar.recordedAlarmId != null && sidecar.recordedAlarmId != intent.alarmId) ||
                (sidecar.recordedOwnerToken != null && sidecar.recordedOwnerToken != intent.expectedOwnerToken)
            ) {
                return hold(
                    Schema38BootCountClassificationReason.DB_SIDECAR_INCONSISTENCY,
                    "Sidecar record corrupted or inconsistent with persisted Rev2 intent; fail-closed HOLD"
                )
            }

            val recordedBoot = sidecar.recordedBootCount
            val currentBoot = input.currentBootCount.currentBootCount
            if (!input.currentBootCount.isAvailable ||
                currentBoot == null ||
                currentBoot <= 0L ||
                recordedBoot == null ||
                recordedBoot <= 0L
            ) {
                return hold(
                    Schema38BootCountClassificationReason.BOOT_COUNT_UNAVAILABLE,
                    "System or sidecar bootCount unavailable/non-positive; fail-closed HOLD"
                )
            }

            if (input.observedOsCancelOutcome is FakeOsCancelOutcome.Unknown ||
                input.observedOsCancelOutcome is FakeOsCancelOutcome.ExceptionThrown
            ) {
                return hold(
                    Schema38BootCountClassificationReason.OS_RESULT_UNKNOWN,
                    "OS cancellation result is UNKNOWN/exception; fail-closed HOLD"
                )
            }

            if (recordedBoot != currentBoot) {
                return hold(
                    Schema38BootCountClassificationReason.BOOT_COUNT_MISMATCH,
                    "Cold reboot detected (recordedBoot=$recordedBoot != currentBoot=$currentBoot); OS cleared pre-boot alarms, NOT cancel completion proof"
                )
            }

            return hold(
                Schema38BootCountClassificationReason.MATCHED_WARM_REBOOT_NOT_OS_COMPLETION_PROOF,
                "bootCount matched ($currentBoot) but matching bootCount or FLAG_NO_CREATE(${input.observedOsLookupStatus}) cannot prove OS cancel completion; HOLD"
            )
        }
    }

    private class StaleAdmissionRollbackException(message: String) : RuntimeException(message)

    private sealed interface AdmissionStepResult {
        data class Admitted(val intentIssued: Boolean, val casRows: Int) : AdmissionStepResult
        data class DuplicateRejected(val existingPhase: String, val casRows: Int) : AdmissionStepResult
        data class Rejected(val reason: String, val existingPhase: String?) : AdmissionStepResult
    }

    /**
     * Inactive MAIN_V2 Fake-only Set path (C-Set) serialized on the SAME per-alarmId
     * [ScheduleAuthorityOwner] mutex used by [executeFakeBoundaryCancel] (B-Cancel).
     * Never invokes real Android AlarmManager or re-enters executeOperation/scheduleAt.
     */
    suspend fun executeFakeMainV2Set(
        request: MainV2FakeSetRequest,
        gateway: MainV2FakeOsSetGateway,
        onInsideLockBeforeFakeSet: (suspend () -> Unit)? = null
    ): MainV2FakeSetResult {
        val validToken = request.issueToken.isNotBlank() &&
            request.issueToken.none { it.isWhitespace() || it.isISOControl() } &&
            request.issueToken.all { c ->
                (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') ||
                    c == '-' || c == '_' || c == '.' || c == '~'
            }
        if (request.alarmId <= 0L ||
            request.scheduledAt <= 0L ||
            request.fireId.isBlank() ||
            !validToken ||
            request.expectedOwnerToken.isBlank() ||
            request.expectedRestoreEpoch <= 0L
        ) {
            return MainV2FakeSetResult(
                outcome = MainV2FakeSetOutcome.REJECTED_INVALID_INPUT,
                alarmId = request.alarmId,
                issueToken = request.issueToken,
                activated = false,
                currentSlotTokenAfter = null
            )
        }

        return scheduleAuthorityOwner.withMainV2SlotLock(request.alarmId) {
            val issuedDao = database.issuedOccurrenceDao()
            val reserved = issuedDao.reserveIssuedOccurrenceWithAuthority(
                issueToken = request.issueToken,
                alarmId = request.alarmId,
                scheduledAt = request.scheduledAt,
                fireId = request.fireId,
                authorityInstanceToken = request.expectedOwnerToken,
                restoreEpoch = request.expectedRestoreEpoch,
                scheduleRevision = request.expectedScheduleRevision,
                definitionRevision = request.expectedDefinitionRevision,
                alarmGeneration = request.expectedAlarmGeneration,
                now = request.nowEpochMs
            )
            if (reserved == null) {
                return@withMainV2SlotLock MainV2FakeSetResult(
                    outcome = MainV2FakeSetOutcome.REJECTED_DB_RESERVATION_OR_WITNESS,
                    alarmId = request.alarmId,
                    issueToken = request.issueToken,
                    activated = false,
                    currentSlotTokenAfter = issuedDao.getSlotCurrentToken(request.alarmId)
                )
            }

            onInsideLockBeforeFakeSet?.invoke()

            val descriptor = expectedMainV2Descriptor(request.alarmId, request.issueToken)
            val lineage = OccurrenceLineageDescriptor(
                alarmId = request.alarmId,
                alarmKind = Alarm.KIND_NORMAL,
                scheduledTriggerMillis = request.scheduledAt,
                generation = request.expectedAlarmGeneration,
                revision = 1,
                issueToken = request.issueToken,
                instanceToken = request.expectedOwnerToken,
                restoreEpoch = request.expectedRestoreEpoch,
                scheduleRevision = request.expectedScheduleRevision,
                definitionRevision = request.expectedDefinitionRevision
            )

            val fakeSetOk = try {
                gateway.setMainSlot(
                    alarmId = request.alarmId,
                    issueToken = request.issueToken,
                    scheduledAt = request.scheduledAt,
                    descriptor = descriptor,
                    lineage = lineage
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                false
            }

            if (!fakeSetOk) {
                issuedDao.casUpdateOccurrenceStatus(
                    issueToken = request.issueToken,
                    expectedStatus = IssuedOccurrence.STATUS_PREPARED,
                    newStatus = IssuedOccurrence.STATUS_SET_FAILED,
                    updatedAt = request.nowEpochMs
                )
                return@withMainV2SlotLock MainV2FakeSetResult(
                    outcome = MainV2FakeSetOutcome.REJECTED_FAKE_OS_FAILURE,
                    alarmId = request.alarmId,
                    issueToken = request.issueToken,
                    activated = false,
                    currentSlotTokenAfter = issuedDao.getSlotCurrentToken(request.alarmId)
                )
            }

            val activated = issuedDao.activateIssuedOccurrence(request.issueToken, request.nowEpochMs)
            val slotTokenAfter = issuedDao.getSlotCurrentToken(request.alarmId)
            MainV2FakeSetResult(
                outcome = if (activated) {
                    MainV2FakeSetOutcome.FAKE_OS_SET_ACTIVATED_INACTIVE
                } else {
                    MainV2FakeSetOutcome.REJECTED_DB_RESERVATION_OR_WITNESS
                },
                alarmId = request.alarmId,
                issueToken = request.issueToken,
                activated = activated,
                currentSlotTokenAfter = slotTokenAfter
            )
        }
    }

    suspend fun executeFakeBoundaryCancel(
        request: BSpecificCancelRequest,
        gateway: BSpecificOsCancelGateway,
        onAfterCasBeforePreBinderCheck: (suspend () -> Unit)? = null,
        onBeforeFakeCancelInvocation: (suspend () -> Unit)? = null
    ): BSpecificCancelIntegrationResult {
        val (callerIdentity, fence) = buildCallerAndFence(request)

        if (callerIdentity == null || fence == null ||
            request.alarmId <= 0L ||
            request.targetIssueToken.isBlank() ||
            request.targetFireId.isBlank() ||
            request.targetScheduledAt <= 0L ||
            request.expectedOwnerToken.isBlank()
        ) {
            val rejectedPlan = CancelOutcome.InvalidFence(
                alarmId = request.alarmId,
                reason = CancellationRejectionReason.MISSING_ORPHAN_TOKEN_IDENTITY,
                detail = "Invalid B-specific cancel request witnesses"
            )
            return BSpecificCancelIntegrationResult(
                outcome = BSpecificCancelIntegrationOutcome.ADMISSION_REJECTED_STALE_OR_MISMATCH,
                alarmId = request.alarmId,
                targetIssueToken = request.targetIssueToken,
                p3cIntentIssued = false,
                p3cCasRowsUpdated = 0,
                durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                identitySafety = null,
                boundaryReport = evaluateBoundary(callerIdentity, fence, rejectedPlan, request),
                bToCRaceDetected = false,
                fakeOsLookupInvoked = false,
                fakeOsCancelInvoked = false
            )
        }

        return scheduleAuthorityOwner.withMainV2SlotLock(request.alarmId) {
            executeFakeBoundaryCancelLocked(
                request = request,
                callerIdentity = callerIdentity,
                fence = fence,
                gateway = gateway,
                onAfterCasBeforePreBinderCheck = onAfterCasBeforePreBinderCheck,
                onBeforeFakeCancelInvocation = onBeforeFakeCancelInvocation
            )
        }
    }

    private suspend fun executeFakeBoundaryCancelLocked(
        request: BSpecificCancelRequest,
        callerIdentity: CallerOriginIdentity,
        fence: CancellationFence,
        gateway: BSpecificOsCancelGateway,
        onAfterCasBeforePreBinderCheck: (suspend () -> Unit)?,
        onBeforeFakeCancelInvocation: (suspend () -> Unit)?
    ): BSpecificCancelIntegrationResult {

        val admissionResult = try {
            admitBSpecificCancelViaP3C(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SQLiteConstraintException) {
            val existing = database.durableOperationIntentDao().getIntentByToken(request.targetIssueToken)
            AdmissionStepResult.DuplicateRejected(
                existingPhase = existing?.phase ?: "CONFLICT",
                casRows = 0
            )
        } catch (e: StaleAdmissionRollbackException) {
            AdmissionStepResult.Rejected(
                reason = e.message ?: "STALE_ADMISSION",
                existingPhase = readIntentPhaseSafely(request.targetIssueToken)
            )
        } catch (e: IllegalStateException) {
            AdmissionStepResult.Rejected(
                reason = e.message ?: "P3C_BINDING_REJECTED",
                existingPhase = readIntentPhaseSafely(request.targetIssueToken)
            )
        }

        when (admissionResult) {
            is AdmissionStepResult.DuplicateRejected -> {
                val stalePlan = CancelOutcome.StaleRejected(
                    alarmId = request.alarmId,
                    reason = CancellationRejectionReason.AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH,
                    detail = "Duplicate B-specific cancel request rejected; phase=${admissionResult.existingPhase}"
                )
                return BSpecificCancelIntegrationResult(
                    outcome = BSpecificCancelIntegrationOutcome.DUPLICATE_REQUEST_REJECTED,
                    alarmId = request.alarmId,
                    targetIssueToken = request.targetIssueToken,
                    p3cIntentIssued = false,
                    p3cCasRowsUpdated = admissionResult.casRows,
                    durableIntentPhaseAfter = admissionResult.existingPhase,
                    identitySafety = null,
                    boundaryReport = evaluateBoundary(callerIdentity, fence, stalePlan, request),
                    bToCRaceDetected = false,
                    fakeOsLookupInvoked = false,
                    fakeOsCancelInvoked = false
                )
            }

            is AdmissionStepResult.Rejected -> {
                val rejectedPlan = CancelOutcome.StaleRejected(
                    alarmId = request.alarmId,
                    reason = CancellationRejectionReason.STALE_GENERATION,
                    detail = admissionResult.reason
                )
                return BSpecificCancelIntegrationResult(
                    outcome = BSpecificCancelIntegrationOutcome.ADMISSION_REJECTED_STALE_OR_MISMATCH,
                    alarmId = request.alarmId,
                    targetIssueToken = request.targetIssueToken,
                    p3cIntentIssued = false,
                    p3cCasRowsUpdated = 0,
                    durableIntentPhaseAfter = admissionResult.existingPhase,
                    identitySafety = null,
                    boundaryReport = evaluateBoundary(callerIdentity, fence, rejectedPlan, request),
                    bToCRaceDetected = false,
                    fakeOsLookupInvoked = false,
                    fakeOsCancelInvoked = false
                )
            }

            is AdmissionStepResult.Admitted -> Unit
        }

        // Allow tests to inject concurrent Successor C reservation after P3C DB CAS succeeds
        onAfterCasBeforePreBinderCheck?.invoke()

        val acceptedPlan = CancelOutcome.Accepted(
            alarmId = request.alarmId,
            plan = FencedCancellationPlan(
                alarmId = request.alarmId,
                fenceKind = Alarm.KIND_NORMAL,
                shouldCancelMainOsSlot = true,
                shouldAttemptDirectBootRemoval = false,
                directBootTargetTriggerTime = null,
                shouldCancelFollowUpWork = request.includeFollowUpWorkers,
                revokeIssuedOccurrenceToken = request.targetIssueToken,
                callerRoomMutationPermittedAfterOsCancel = false,
                requiresOsVerificationBeforeDestructiveCleanup = true,
                solvesLegacySharedPendingIntentRed04 = false
            )
        )

        // Pre-Binder DB witness re-check to detect B->C race before OS invocation
        if (!verifyBStillExclusiveCandidateInDb(request)) {
            val raceReport = evaluateBoundary(
                caller = callerIdentity,
                fence = fence,
                outcome = acceptedPlan,
                request = request,
                successorExclusion = SuccessorExclusionStatus.SUCCESSOR_EXISTS,
                isLegacyRed04Unresolved = false
            )
            return BSpecificCancelIntegrationResult(
                outcome = BSpecificCancelIntegrationOutcome.B_TO_C_RACE_ABORTED,
                alarmId = request.alarmId,
                targetIssueToken = request.targetIssueToken,
                p3cIntentIssued = admissionResult.intentIssued,
                p3cCasRowsUpdated = admissionResult.casRows,
                durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                identitySafety = null,
                boundaryReport = raceReport,
                bToCRaceDetected = true,
                fakeOsLookupInvoked = false,
                fakeOsCancelInvoked = false
            )
        }

        // Inspect Fake MAIN PendingIntent slot without executing OS cancel
        val expectedDescriptor = expectedMainV2Descriptor(request.alarmId, request.targetIssueToken)
        val expectedLineage = OccurrenceLineageDescriptor(
            alarmId = request.alarmId,
            alarmKind = Alarm.KIND_NORMAL,
            scheduledTriggerMillis = request.targetScheduledAt,
            generation = request.expectedAlarmGeneration,
            revision = request.expectedAlarmRevision,
            issueToken = request.targetIssueToken,
            instanceToken = request.expectedOwnerToken,
            restoreEpoch = request.expectedRestoreEpoch,
            scheduleRevision = request.expectedScheduleRevision,
            definitionRevision = request.expectedDefinitionRevision
        )

        val lookupObservation = gateway.lookupMainSlot(
            alarmId = request.alarmId,
            targetIssueToken = request.targetIssueToken,
            expectedDescriptor = expectedDescriptor
        )

        if (lookupObservation is FakeOsLookupObservation.Absent) {
            val absentReport = evaluateBoundary(
                caller = callerIdentity,
                fence = fence,
                outcome = acceptedPlan,
                request = request,
                lookupStatus = PendingIntentLookupStatus.LOOKUP_NULL,
                osCancelInvocation = OsCancelInvocationStatus.NOT_ATTEMPTED,
                osCancelProof = OsCancelProofStatus.UNCERTAIN,
                successorExclusion = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
                isLegacyRed04Unresolved = false
            )
            return BSpecificCancelIntegrationResult(
                outcome = BSpecificCancelIntegrationOutcome.PI_ABSENT_NO_DESTRUCTIVE_CLEANUP,
                alarmId = request.alarmId,
                targetIssueToken = request.targetIssueToken,
                p3cIntentIssued = admissionResult.intentIssued,
                p3cCasRowsUpdated = admissionResult.casRows,
                durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                identitySafety = null,
                boundaryReport = absentReport,
                bToCRaceDetected = false,
                fakeOsLookupInvoked = true,
                fakeOsCancelInvoked = false
            )
        }

        val found = lookupObservation as FakeOsLookupObservation.Found
        val safety = PendingIntentIdentityComparator.evaluateSafety(
            osKeyA = expectedDescriptor,
            osKeyB = found.descriptor,
            lineageA = expectedLineage,
            lineageB = found.lineage
        )

        if (safety !is CrossIdentitySafety.ExactCandidate) {
            val isLegacyAlias = found.descriptor.computedIsLegacyV1Alias
            val boundaryForAlias = if (isLegacyAlias) {
                evaluateBoundary(
                    caller = callerIdentity,
                    fence = fence,
                    outcome = acceptedPlan,
                    request = request,
                    lookupStatus = PendingIntentLookupStatus.LOOKUP_FOUND,
                    osCancelInvocation = OsCancelInvocationStatus.NOT_ATTEMPTED,
                    osCancelProof = OsCancelProofStatus.UNCERTAIN,
                    successorExclusion = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
                    isLegacyRed04Unresolved = true
                )
            } else {
                val rejectionReason = when (safety) {
                    is CrossIdentitySafety.TargetMismatch -> safety.reason
                    is CrossIdentitySafety.UnsafeAlias -> safety.reason
                    is CrossIdentitySafety.EvidenceIncomplete -> CancellationRejectionReason.OS_STATE_UNCERTAIN
                    is CrossIdentitySafety.ExactCandidate -> CancellationRejectionReason.OS_STATE_UNCERTAIN
                }
                val mismatchPlan = CancelOutcome.IdentityMismatch(
                    alarmId = request.alarmId,
                    reason = rejectionReason,
                    detail = "PendingIntent identity verification rejected candidate: $safety"
                )
                evaluateBoundary(callerIdentity, fence, mismatchPlan, request)
            }

            return BSpecificCancelIntegrationResult(
                outcome = BSpecificCancelIntegrationOutcome.PI_IDENTITY_REJECTED,
                alarmId = request.alarmId,
                targetIssueToken = request.targetIssueToken,
                p3cIntentIssued = admissionResult.intentIssued,
                p3cCasRowsUpdated = admissionResult.casRows,
                durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                identitySafety = safety,
                boundaryReport = boundaryForAlias,
                bToCRaceDetected = false,
                fakeOsLookupInvoked = true,
                fakeOsCancelInvoked = false
            )
        }

        // Allow tests to inject B->C race in the post-check / pre-Binder TOCTOU window
        onBeforeFakeCancelInvocation?.invoke()

        val fakeOutcome = try {
            gateway.cancelMainSlot(
                alarmId = request.alarmId,
                targetIssueToken = request.targetIssueToken,
                verifiedDescriptor = expectedDescriptor
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            FakeOsCancelOutcome.ExceptionThrown(e)
        }

        val postBinderRaceDetected = !verifyBStillExclusiveCandidateInDb(request)

        if (postBinderRaceDetected) {
            val raceReport = evaluateBoundary(
                caller = callerIdentity,
                fence = fence,
                outcome = acceptedPlan,
                request = request,
                lookupStatus = PendingIntentLookupStatus.LOOKUP_FOUND,
                osCancelInvocation = when (fakeOutcome) {
                    is FakeOsCancelOutcome.ExceptionThrown -> OsCancelInvocationStatus.INVOCATION_FAILED
                    is FakeOsCancelOutcome.Cancelled,
                    is FakeOsCancelOutcome.Unknown -> OsCancelInvocationStatus.INVOCATION_COMPLETED
                    is FakeOsCancelOutcome.NotFound -> OsCancelInvocationStatus.NOT_ATTEMPTED
                },
                osCancelProof = OsCancelProofStatus.UNCERTAIN,
                successorExclusion = SuccessorExclusionStatus.SUCCESSOR_EXISTS,
                isLegacyRed04Unresolved = false
            )
            return BSpecificCancelIntegrationResult(
                outcome = BSpecificCancelIntegrationOutcome.B_TO_C_RACE_ABORTED,
                alarmId = request.alarmId,
                targetIssueToken = request.targetIssueToken,
                p3cIntentIssued = admissionResult.intentIssued,
                p3cCasRowsUpdated = admissionResult.casRows,
                durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                identitySafety = safety,
                boundaryReport = raceReport,
                bToCRaceDetected = true,
                fakeOsLookupInvoked = true,
                fakeOsCancelInvoked = true
            )
        }

        return when (fakeOutcome) {
            is FakeOsCancelOutcome.Cancelled -> {
                val report = evaluateBoundary(
                    caller = callerIdentity,
                    fence = fence,
                    outcome = acceptedPlan,
                    request = request,
                    lookupStatus = PendingIntentLookupStatus.LOOKUP_FOUND,
                    osCancelInvocation = OsCancelInvocationStatus.INVOCATION_COMPLETED,
                    osCancelProof = OsCancelProofStatus.PROVEN_CANCELLED,
                    successorExclusion = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
                    isLegacyRed04Unresolved = false
                )
                BSpecificCancelIntegrationResult(
                    outcome = BSpecificCancelIntegrationOutcome.FAKE_OS_CANCEL_OBSERVED_NOT_REAL_PROOF,
                    alarmId = request.alarmId,
                    targetIssueToken = request.targetIssueToken,
                    p3cIntentIssued = admissionResult.intentIssued,
                    p3cCasRowsUpdated = admissionResult.casRows,
                    durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                    identitySafety = safety,
                    boundaryReport = report,
                    bToCRaceDetected = false,
                    fakeOsLookupInvoked = true,
                    fakeOsCancelInvoked = true
                )
            }

            is FakeOsCancelOutcome.NotFound -> {
                val report = evaluateBoundary(
                    caller = callerIdentity,
                    fence = fence,
                    outcome = acceptedPlan,
                    request = request,
                    lookupStatus = PendingIntentLookupStatus.LOOKUP_NULL,
                    osCancelInvocation = OsCancelInvocationStatus.NOT_ATTEMPTED,
                    osCancelProof = OsCancelProofStatus.UNCERTAIN,
                    successorExclusion = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
                    isLegacyRed04Unresolved = false
                )
                BSpecificCancelIntegrationResult(
                    outcome = BSpecificCancelIntegrationOutcome.PI_ABSENT_NO_DESTRUCTIVE_CLEANUP,
                    alarmId = request.alarmId,
                    targetIssueToken = request.targetIssueToken,
                    p3cIntentIssued = admissionResult.intentIssued,
                    p3cCasRowsUpdated = admissionResult.casRows,
                    durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                    identitySafety = safety,
                    boundaryReport = report,
                    bToCRaceDetected = false,
                    fakeOsLookupInvoked = true,
                    fakeOsCancelInvoked = true
                )
            }

            is FakeOsCancelOutcome.Unknown -> {
                val report = evaluateBoundary(
                    caller = callerIdentity,
                    fence = fence,
                    outcome = acceptedPlan,
                    request = request,
                    lookupStatus = PendingIntentLookupStatus.LOOKUP_FOUND,
                    osCancelInvocation = OsCancelInvocationStatus.INVOCATION_COMPLETED,
                    osCancelProof = OsCancelProofStatus.UNCERTAIN,
                    successorExclusion = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
                    isLegacyRed04Unresolved = false
                )
                BSpecificCancelIntegrationResult(
                    outcome = BSpecificCancelIntegrationOutcome.OS_OUTCOME_UNKNOWN_PRESERVED,
                    alarmId = request.alarmId,
                    targetIssueToken = request.targetIssueToken,
                    p3cIntentIssued = admissionResult.intentIssued,
                    p3cCasRowsUpdated = admissionResult.casRows,
                    durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                    identitySafety = safety,
                    boundaryReport = report,
                    bToCRaceDetected = false,
                    fakeOsLookupInvoked = true,
                    fakeOsCancelInvoked = true
                )
            }

            is FakeOsCancelOutcome.ExceptionThrown -> {
                val report = evaluateBoundary(
                    caller = callerIdentity,
                    fence = fence,
                    outcome = acceptedPlan,
                    request = request,
                    lookupStatus = PendingIntentLookupStatus.LOOKUP_FOUND,
                    osCancelInvocation = OsCancelInvocationStatus.INVOCATION_FAILED,
                    osCancelProof = OsCancelProofStatus.UNCERTAIN,
                    successorExclusion = SuccessorExclusionStatus.EXCLUDED_IN_MUTEX,
                    isLegacyRed04Unresolved = false
                )
                BSpecificCancelIntegrationResult(
                    outcome = BSpecificCancelIntegrationOutcome.OS_EXCEPTION_PRESERVED,
                    alarmId = request.alarmId,
                    targetIssueToken = request.targetIssueToken,
                    p3cIntentIssued = admissionResult.intentIssued,
                    p3cCasRowsUpdated = admissionResult.casRows,
                    durableIntentPhaseAfter = readIntentPhaseSafely(request.targetIssueToken),
                    identitySafety = safety,
                    boundaryReport = report,
                    bToCRaceDetected = false,
                    fakeOsLookupInvoked = true,
                    fakeOsCancelInvoked = true
                )
            }
        }
    }

    private suspend fun admitBSpecificCancelViaP3C(request: BSpecificCancelRequest): AdmissionStepResult {
        val issuedDao = database.issuedOccurrenceDao()
        val authorityDao = database.scheduleAuthorityDao()
        val alarmDao = database.alarmDao()
        val intentDao = database.durableOperationIntentDao()

        return database.withTransaction {
            val existing = intentDao.getIntentByToken(request.targetIssueToken)
            if (existing != null) {
                if (!DurableCancelIntentValidator.isBSpecificCancelIntent(existing)) {
                    return@withTransaction AdmissionStepResult.Rejected(
                        reason = "EXISTING_INTENT_NOT_REV2_B_SPECIFIC",
                        existingPhase = existing.phase
                    )
                }
                if (existing.phase != DurableOperationIntent.PHASE_PENDING) {
                    val casRows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
                        operationToken = request.targetIssueToken,
                        alarmId = request.alarmId,
                        expectedSlotGenId = request.expectedSlotGenId,
                        expectedSlotOwnerToken = request.expectedSlotOwnerToken,
                        expectedOwnerToken = request.expectedOwnerToken
                    )
                    return@withTransaction AdmissionStepResult.DuplicateRejected(
                        existingPhase = existing.phase,
                        casRows = casRows
                    )
                }
                if (!verifyAlarmAndAuthorityRevisionsInTx(request)) {
                    return@withTransaction AdmissionStepResult.Rejected(
                        reason = "ALARM_OR_AUTHORITY_WITNESS_MISMATCH",
                        existingPhase = existing.phase
                    )
                }
                val casRows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
                    operationToken = request.targetIssueToken,
                    alarmId = request.alarmId,
                    expectedSlotGenId = request.expectedSlotGenId,
                    expectedSlotOwnerToken = request.expectedSlotOwnerToken,
                    expectedOwnerToken = request.expectedOwnerToken
                )
                return@withTransaction if (casRows == 1) {
                    AdmissionStepResult.Admitted(intentIssued = false, casRows = casRows)
                } else {
                    AdmissionStepResult.DuplicateRejected(existingPhase = existing.phase, casRows = casRows)
                }
            }

            if (!verifyAlarmAndAuthorityRevisionsInTx(request)) {
                throw StaleAdmissionRollbackException("ALARM_OR_AUTHORITY_WITNESS_MISMATCH")
            }

            val issuedIntent = intentDao.issueBSpecificCancelIntentTransaction(
                issuedDao = issuedDao,
                scheduleAuthorityDao = authorityDao,
                alarmDao = alarmDao,
                targetIssueToken = request.targetIssueToken,
                now = request.nowEpochMs
            )

            if (issuedIntent.originalFireId != request.targetFireId ||
                issuedIntent.originalScheduledAt != request.targetScheduledAt ||
                issuedIntent.expectedOwnerToken != request.expectedOwnerToken ||
                issuedIntent.expectedRestoreEpoch != request.expectedRestoreEpoch ||
                issuedIntent.expectedScheduleRevision != request.expectedScheduleRevision
            ) {
                throw StaleAdmissionRollbackException("CALLER_WITNESS_MISMATCH_WITH_ISSUED_INTENT")
            }

            val casRows = intentDao.casTransitionBSpecificCancelIntentToInFlight(
                operationToken = request.targetIssueToken,
                alarmId = request.alarmId,
                expectedSlotGenId = request.expectedSlotGenId,
                expectedSlotOwnerToken = request.expectedSlotOwnerToken,
                expectedOwnerToken = request.expectedOwnerToken
            )
            if (casRows != 1) {
                throw StaleAdmissionRollbackException("P3C_CAS_RETURNED_$casRows")
            }

            AdmissionStepResult.Admitted(intentIssued = true, casRows = casRows)
        }
    }

    private suspend fun verifyAlarmAndAuthorityRevisionsInTx(request: BSpecificCancelRequest): Boolean {
        val alarm = database.alarmDao().getById(request.alarmId) ?: return false
        val sa = database.scheduleAuthorityDao().getAuthorityByAlarmId(request.alarmId) ?: return false
        val occB = database.issuedOccurrenceDao().getOccurrence(request.targetIssueToken) ?: return false
        val slot = database.issuedOccurrenceDao().getSlot(request.alarmId) ?: return false

        return alarm.generation == request.expectedAlarmGeneration &&
            alarm.revision == request.expectedAlarmRevision &&
            sa.instanceToken == request.expectedOwnerToken &&
            sa.restoreEpoch == request.expectedRestoreEpoch &&
            sa.scheduleRevision == request.expectedScheduleRevision &&
            sa.definitionRevision == request.expectedDefinitionRevision &&
            occB.alarmId == request.alarmId &&
            occB.status == IssuedOccurrence.STATUS_PREPARED &&
            occB.fireId == request.targetFireId &&
            occB.scheduledAt == request.targetScheduledAt &&
            occB.generationId == request.expectedSlotGenId &&
            slot.generationId == request.expectedSlotGenId &&
            slot.currentIssueToken == request.expectedSlotOwnerToken &&
            slot.currentIssueToken != request.targetIssueToken
    }

    private suspend fun verifyBStillExclusiveCandidateInDb(request: BSpecificCancelRequest): Boolean {
        return database.withTransaction {
            val intent = database.durableOperationIntentDao().getIntentByToken(request.targetIssueToken)
            intent != null &&
                intent.phase == DurableOperationIntent.PHASE_IN_FLIGHT &&
                verifyAlarmAndAuthorityRevisionsInTx(request)
        }
    }

    private suspend fun readIntentPhaseSafely(token: String): String? {
        if (token.isBlank()) return null
        return try {
            database.durableOperationIntentDao().getIntentByToken(token)?.phase
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun buildCallerAndFence(
        request: BSpecificCancelRequest
    ): Pair<CallerOriginIdentity?, CancellationFence?> {
        if (request.alarmId <= 0L ||
            request.expectedAlarmGeneration < 1 ||
            request.expectedAlarmRevision < 1 ||
            request.targetScheduledAt <= 0L ||
            request.expectedOwnerToken.isBlank() ||
            request.expectedRestoreEpoch <= 0L
        ) {
            return null to null
        }

        val caller = CallerOriginIdentity(
            alarmId = request.alarmId,
            alarmKind = Alarm.KIND_NORMAL,
            generation = request.expectedAlarmGeneration,
            revision = request.expectedAlarmRevision,
            scheduledTriggerMillis = request.targetScheduledAt,
            scheduleAuthorityRevision = request.expectedScheduleRevision,
            definitionRevision = request.expectedDefinitionRevision,
            instanceToken = request.expectedOwnerToken,
            restoreEpoch = request.expectedRestoreEpoch,
            operationKind = CallerOperationKind.EXACT_OCCURRENCE,
            capturedAtMillis = request.nowEpochMs,
            targetScheduledAt = request.targetScheduledAt,
            targetFireId = request.targetFireId,
            sourceTag = "REV2_B_SPECIFIC_CANCEL_INTEGRATION"
        )
        val propagated = CallerOriginFenceConverter.toFenceCandidate(caller)
        val fence = (propagated as? CallerOriginPropagationResult.QualifiedCandidate)?.fence
        return caller to fence
    }

    private fun evaluateBoundary(
        caller: CallerOriginIdentity?,
        fence: CancellationFence?,
        outcome: CancelOutcome,
        request: BSpecificCancelRequest,
        lookupStatus: PendingIntentLookupStatus = PendingIntentLookupStatus.NOT_ATTEMPTED,
        osCancelInvocation: OsCancelInvocationStatus = OsCancelInvocationStatus.NOT_ATTEMPTED,
        osCancelProof: OsCancelProofStatus = OsCancelProofStatus.UNCERTAIN,
        successorExclusion: SuccessorExclusionStatus = SuccessorExclusionStatus.UNKNOWN,
        isLegacyRed04Unresolved: Boolean = true
    ): BoundaryEvaluationReport {
        return CancellationExecutionBoundary.evaluate(
            BoundaryEvaluationContext(
                callerIdentity = caller,
                cancellationFence = fence,
                plannerOutcome = outcome,
                pendingIntentLookup = lookupStatus,
                osCancelInvocation = osCancelInvocation,
                osCancelProof = osCancelProof,
                deLineageStatus = request.deLineageStatus,
                successorExclusionStatus = successorExclusion,
                isLegacyRed04Unresolved = isLegacyRed04Unresolved,
                includeFollowUpWorkers = request.includeFollowUpWorkers
            )
        )
    }

    // --- G2: Inactive Rev2 Replay Recovery Classification (Zero Real OS Calls, Zero COMPLETED/ABORTED) ---

    suspend fun replayUnresolvedCancelIntents(
        evaluationTimeMillis: Long,
        gateway: BSpecificOsCancelGateway? = null,
        perTokenContext: Map<String, Rev2ReplayInspectionContext> = emptyMap()
    ): Rev2BatchReplayReport {
        val unresolved = database.withTransaction {
            database.durableOperationIntentDao().getUnresolvedCancellationIntents()
        }
        val results = unresolved.map { intent ->
            val ctx = perTokenContext[intent.operationToken] ?: Rev2ReplayInspectionContext(
                alarmId = intent.alarmId,
                targetIssueToken = intent.operationToken,
                evaluationTimeMillis = evaluationTimeMillis
            )
            classifyIntentRecordForReplay(intent = intent, context = ctx, gateway = gateway)
        }
        return Rev2BatchReplayReport(
            results = results,
            totalUnresolvedScanned = unresolved.size,
            legacyRev1DelegatedCount = results.count { it.route == Rev2ReplayRoute.DELEGATED_TO_LEGACY_REV1 },
            rev2ClassifiedCount = results.count { it.route == Rev2ReplayRoute.REV2_INACTIVE_REPLAY },
            unknownFailClosedCount = results.count { it.route == Rev2ReplayRoute.UNKNOWN_REVISION_FAIL_CLOSED }
        )
    }

    suspend fun replaySingleRev2CancelIntent(
        context: Rev2ReplayInspectionContext,
        gateway: BSpecificOsCancelGateway? = null
    ): Rev2ReplayRecoveryResult {
        val intent = if (context.targetIssueToken.isBlank()) {
            null
        } else {
            database.withTransaction {
                database.durableOperationIntentDao().getIntentByToken(context.targetIssueToken)
            }
        }
        if (intent == null) {
            val report = blockedBoundaryReport(
                alarmId = context.alarmId,
                reason = CancellationRejectionReason.MISSING_ORPHAN_TOKEN_IDENTITY,
                detail = "C0 pre-intent state: no unresolved DurableOperationIntent persisted for token=${context.targetIssueToken}"
            )
            return Rev2ReplayRecoveryResult(
                route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                outcome = Rev2ReplayRecoveryOutcome.C0_PRE_INTENT_ABSENT,
                crashCutpoint = context.crashCutpointHint ?: Rev2ReplayCrashCutpoint.C0_PRE_INTENT_ISSUE,
                alarmId = context.alarmId,
                targetIssueToken = context.targetIssueToken,
                intentPhaseBefore = null,
                intentPhaseAfter = null,
                adjudicatorDecision = null,
                identitySafety = null,
                boundaryReport = report,
                fakeOsLookupInvoked = false,
                detail = "C0_PRE_INTENT_ABSENT"
            )
        }
        return classifyIntentRecordForReplay(intent = intent, context = context, gateway = gateway)
    }

    private suspend fun classifyIntentRecordForReplay(
        intent: DurableOperationIntent,
        context: Rev2ReplayInspectionContext,
        gateway: BSpecificOsCancelGateway?
    ): Rev2ReplayRecoveryResult {
        return when (DurableCancelIntentValidator.classifyCancelIntentRevision(intent)) {
            DurableCancelIntentValidator.CancelIntentRevisionClass.LEGACY_REV1 -> {
                val report = blockedBoundaryReport(
                    alarmId = intent.alarmId,
                    reason = CancellationRejectionReason.AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH,
                    detail = "Rev1 CANCEL intent isolated from Rev2 replay and delegated to Legacy path"
                )
                Rev2ReplayRecoveryResult(
                    route = Rev2ReplayRoute.DELEGATED_TO_LEGACY_REV1,
                    outcome = Rev2ReplayRecoveryOutcome.LEGACY_REV1_PRESERVED,
                    crashCutpoint = context.crashCutpointHint ?: defaultCutpointForPhase(intent.phase),
                    alarmId = intent.alarmId,
                    targetIssueToken = intent.operationToken,
                    intentPhaseBefore = intent.phase,
                    intentPhaseAfter = intent.phase,
                    adjudicatorDecision = null,
                    identitySafety = null,
                    boundaryReport = report,
                    fakeOsLookupInvoked = false,
                    detail = "LEGACY_REV1_PRESERVED"
                )
            }

            DurableCancelIntentValidator.CancelIntentRevisionClass.UNKNOWN_FAIL_CLOSED,
            null -> {
                val report = blockedBoundaryReport(
                    alarmId = intent.alarmId,
                    reason = CancellationRejectionReason.AUTHORITY_LIFECYCLE_OR_OPERATION_MISMATCH,
                    detail = "Unknown CANCEL intentRevision=${intent.intentRevision} kind=${intent.operationKind} fail-closed"
                )
                Rev2ReplayRecoveryResult(
                    route = Rev2ReplayRoute.UNKNOWN_REVISION_FAIL_CLOSED,
                    outcome = Rev2ReplayRecoveryOutcome.UNKNOWN_REVISION_FAIL_CLOSED,
                    crashCutpoint = context.crashCutpointHint ?: defaultCutpointForPhase(intent.phase),
                    alarmId = intent.alarmId,
                    targetIssueToken = intent.operationToken,
                    intentPhaseBefore = intent.phase,
                    intentPhaseAfter = intent.phase,
                    adjudicatorDecision = null,
                    identitySafety = null,
                    boundaryReport = report,
                    fakeOsLookupInvoked = false,
                    detail = "UNKNOWN_REVISION_FAIL_CLOSED_${intent.intentRevision}"
                )
            }

            DurableCancelIntentValidator.CancelIntentRevisionClass.REV2_B_SPECIFIC ->
                classifyRev2IntentWithDbTruth(intent = intent, context = context, gateway = gateway)
        }
    }

    private suspend fun classifyRev2IntentWithDbTruth(
        intent: DurableOperationIntent,
        context: Rev2ReplayInspectionContext,
        gateway: BSpecificOsCancelGateway?
    ): Rev2ReplayRecoveryResult {
        val dbState = database.withTransaction {
            val alarm = database.alarmDao().getById(intent.alarmId)
            val sa = database.scheduleAuthorityDao().getAuthorityByAlarmId(intent.alarmId)
            val occB = database.issuedOccurrenceDao().getOccurrence(intent.operationToken)
            val slot = database.issuedOccurrenceDao().getSlot(intent.alarmId)
            DbReplaySnapshot(alarm = alarm, sa = sa, occB = occB, slot = slot)
        }

        val osOutcomeInput = when (context.priorFakeOsOutcome) {
            is FakeOsCancelOutcome.Cancelled -> OsOperationOutcome.ACCEPTED
            is FakeOsCancelOutcome.NotFound -> OsOperationOutcome.NOT_ATTEMPTED
            is FakeOsCancelOutcome.Unknown,
            is FakeOsCancelOutcome.ExceptionThrown -> OsOperationOutcome.UNKNOWN
            null -> if (context.crashCutpointHint == Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME) {
                OsOperationOutcome.UNKNOWN
            } else {
                OsOperationOutcome.NOT_ATTEMPTED
            }
        }

        // Reuse RecoveryAdjudicator for authority/restoreEpoch/definition/OS-outcome safety
        // without passing Legacy `durableIntent.incarnationId == auth.instanceToken` assumption.
        val authoritySnapshot = RecoveryEvidenceSnapshot(
            alarmId = intent.alarmId,
            authority = dbState.sa,
            restoreEpochMeta = context.expectedRestoreEpoch ?: intent.expectedRestoreEpoch ?: dbState.sa?.restoreEpoch,
            durableIntent = null,
            activeSession = null,
            localIncarnationId = context.expectedOwnerToken ?: intent.expectedOwnerToken ?: dbState.sa?.instanceToken,
            osOutcome = osOutcomeInput,
            definitionExists = dbState.alarm != null,
            evaluationTimeMillis = context.evaluationTimeMillis
        )
        val adjudicatorDecision = adjudicator.adjudicate(authoritySnapshot)

        val alarm = dbState.alarm
        val sa = dbState.sa
        val occB = dbState.occB
        val slot = dbState.slot

        val p3cBindingValid = DurableCancelIntentValidator.verifyBSpecificCancelBinding(
            intent = intent,
            targetOccurrence = occB,
            currentSlot = slot
        )

        val bToCRaceDetected = (slot != null && occB != null && occB.generationId != slot.generationId) ||
            (occB != null && slot != null && slot.currentIssueToken == occB.issueToken) ||
            (occB != null && occB.status != IssuedOccurrence.STATUS_PREPARED) ||
            (context.expectedSlotOwnerToken != null && slot != null && slot.currentIssueToken != context.expectedSlotOwnerToken) ||
            (context.expectedSlotGenId != null && (slot?.generationId != context.expectedSlotGenId || occB?.generationId != context.expectedSlotGenId))

        val witnessMatch = alarm != null &&
            sa != null &&
            occB != null &&
            slot != null &&
            occB.alarmGeneration == alarm.generation &&
            occB.definitionRevision == sa.definitionRevision &&
            sa.instanceToken == intent.expectedOwnerToken &&
            sa.restoreEpoch == intent.expectedRestoreEpoch &&
            sa.scheduleRevision == intent.expectedScheduleRevision &&
            (context.expectedAlarmGeneration == null || alarm.generation == context.expectedAlarmGeneration) &&
            (context.expectedAlarmRevision == null || alarm.revision == context.expectedAlarmRevision) &&
            (context.expectedOwnerToken == null || (sa.instanceToken == context.expectedOwnerToken && intent.expectedOwnerToken == context.expectedOwnerToken)) &&
            (context.expectedRestoreEpoch == null || (sa.restoreEpoch == context.expectedRestoreEpoch && intent.expectedRestoreEpoch == context.expectedRestoreEpoch)) &&
            (context.expectedScheduleRevision == null || (sa.scheduleRevision == context.expectedScheduleRevision && intent.expectedScheduleRevision == context.expectedScheduleRevision)) &&
            (context.expectedDefinitionRevision == null || (sa.definitionRevision == context.expectedDefinitionRevision && occB.definitionRevision == context.expectedDefinitionRevision))

        val authorityQuarantinedOrMismatched = adjudicatorDecision.outcome == RecoveryDecisionOutcome.QUARANTINE &&
            adjudicatorDecision.reasonCode != "OS_OUTCOME_UNKNOWN"

        if (bToCRaceDetected) {
            val report = blockedBoundaryReport(
                alarmId = intent.alarmId,
                reason = CancellationRejectionReason.STALE_GENERATION,
                detail = "B->C race or slot advancement detected during Rev2 replay; successor C protected"
            )
            return Rev2ReplayRecoveryResult(
                route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                outcome = Rev2ReplayRecoveryOutcome.B_TO_C_RACE_ISOLATED,
                crashCutpoint = context.crashCutpointHint ?: defaultCutpointForPhase(intent.phase),
                alarmId = intent.alarmId,
                targetIssueToken = intent.operationToken,
                intentPhaseBefore = intent.phase,
                intentPhaseAfter = intent.phase,
                adjudicatorDecision = adjudicatorDecision,
                identitySafety = null,
                boundaryReport = report,
                fakeOsLookupInvoked = false,
                detail = "B_TO_C_RACE_ISOLATED"
            )
        }

        if (!p3cBindingValid || !witnessMatch || authorityQuarantinedOrMismatched) {
            val outcome = if (intent.phase == DurableOperationIntent.PHASE_IN_FLIGHT) {
                Rev2ReplayRecoveryOutcome.IN_FLIGHT_UNKNOWN_OS_RESTART_HOLD
            } else {
                Rev2ReplayRecoveryOutcome.PENDING_STALE_OR_CONFLICT_ISOLATED
            }
            val report = blockedBoundaryReport(
                alarmId = intent.alarmId,
                reason = CancellationRejectionReason.STALE_GENERATION,
                detail = "Stale or mismatched Rev2 witness during replay; phase=${intent.phase} adjudicator=${adjudicatorDecision.reasonCode}"
            )
            return Rev2ReplayRecoveryResult(
                route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                outcome = outcome,
                crashCutpoint = context.crashCutpointHint ?: defaultCutpointForPhase(intent.phase),
                alarmId = intent.alarmId,
                targetIssueToken = intent.operationToken,
                intentPhaseBefore = intent.phase,
                intentPhaseAfter = intent.phase,
                adjudicatorDecision = adjudicatorDecision,
                identitySafety = null,
                boundaryReport = report,
                fakeOsLookupInvoked = false,
                detail = "WITNESS_MISMATCH_FAIL_CLOSED_${adjudicatorDecision.reasonCode}"
            )
        }

        // Authentic B witnesses verified against Room DB truth (alarm, sa, occB, slot all non-null & matched)
        val verifiedAlarm = alarm!!
        val verifiedSa = sa!!
        val verifiedOccB = occB!!

        if (intent.phase == DurableOperationIntent.PHASE_IN_FLIGHT) {
            when (val priorOutcome = context.priorFakeOsOutcome) {
                is FakeOsCancelOutcome.Unknown,
                is FakeOsCancelOutcome.ExceptionThrown -> {
                    val report = blockedBoundaryReport(
                        alarmId = intent.alarmId,
                        reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
                        detail = "IN_FLIGHT Rev2 replay observed uncertain/failed Fake OS outcome; preserving IN_FLIGHT"
                    )
                    return Rev2ReplayRecoveryResult(
                        route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                        outcome = Rev2ReplayRecoveryOutcome.IN_FLIGHT_UNKNOWN_OS_RESTART_HOLD,
                        crashCutpoint = context.crashCutpointHint ?: Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME,
                        alarmId = intent.alarmId,
                        targetIssueToken = intent.operationToken,
                        intentPhaseBefore = intent.phase,
                        intentPhaseAfter = intent.phase,
                        adjudicatorDecision = adjudicatorDecision,
                        identitySafety = null,
                        boundaryReport = report,
                        fakeOsLookupInvoked = false,
                        detail = "IN_FLIGHT_UNKNOWN_OS_RESTART_HOLD"
                    )
                }

                is FakeOsCancelOutcome.NotFound -> {
                    val report = blockedBoundaryReport(
                        alarmId = intent.alarmId,
                        reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
                        detail = "IN_FLIGHT Rev2 replay observed Fake NotFound; no destructive cleanup or COMPLETED"
                    )
                    return Rev2ReplayRecoveryResult(
                        route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                        outcome = Rev2ReplayRecoveryOutcome.PI_ABSENT_HOLD,
                        crashCutpoint = context.crashCutpointHint ?: Rev2ReplayCrashCutpoint.C5_AROUND_FAKE_RESULT_RECEIPT,
                        alarmId = intent.alarmId,
                        targetIssueToken = intent.operationToken,
                        intentPhaseBefore = intent.phase,
                        intentPhaseAfter = intent.phase,
                        adjudicatorDecision = adjudicatorDecision,
                        identitySafety = null,
                        boundaryReport = report,
                        fakeOsLookupInvoked = false,
                        detail = "PI_ABSENT_HOLD_NOT_FOUND"
                    )
                }

                is FakeOsCancelOutcome.Cancelled -> {
                    val report = blockedBoundaryReport(
                        alarmId = intent.alarmId,
                        reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
                        detail = "C5 Fake Cancelled observed only; real-OS proof absent, COMPLETED forbidden"
                    )
                    return Rev2ReplayRecoveryResult(
                        route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                        outcome = Rev2ReplayRecoveryOutcome.IN_FLIGHT_FAKE_RESULT_NOT_REAL_PROOF_HOLD,
                        crashCutpoint = context.crashCutpointHint ?: Rev2ReplayCrashCutpoint.C5_AROUND_FAKE_RESULT_RECEIPT,
                        alarmId = intent.alarmId,
                        targetIssueToken = intent.operationToken,
                        intentPhaseBefore = intent.phase,
                        intentPhaseAfter = intent.phase,
                        adjudicatorDecision = adjudicatorDecision,
                        identitySafety = null,
                        boundaryReport = report,
                        fakeOsLookupInvoked = false,
                        detail = "IN_FLIGHT_FAKE_RESULT_NOT_REAL_PROOF_HOLD"
                    )
                }

                null -> {
                    if (context.crashCutpointHint == Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME) {
                        val report = blockedBoundaryReport(
                            alarmId = intent.alarmId,
                            reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
                            detail = "C6 reboot with unknown OS outcome; preserving IN_FLIGHT"
                        )
                        return Rev2ReplayRecoveryResult(
                            route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                            outcome = Rev2ReplayRecoveryOutcome.IN_FLIGHT_UNKNOWN_OS_RESTART_HOLD,
                            crashCutpoint = Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME,
                            alarmId = intent.alarmId,
                            targetIssueToken = intent.operationToken,
                            intentPhaseBefore = intent.phase,
                            intentPhaseAfter = intent.phase,
                            adjudicatorDecision = adjudicatorDecision,
                            identitySafety = null,
                            boundaryReport = report,
                            fakeOsLookupInvoked = false,
                            detail = "C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME"
                        )
                    }
                }
            }
        }

        // Optional read-only Fake PendingIntent lookup & identity verification (never calls cancelMainSlot)
        if (gateway != null) {
            val expectedDescriptor = expectedMainV2Descriptor(intent.alarmId, intent.operationToken)
            val expectedLineage = OccurrenceLineageDescriptor(
                alarmId = intent.alarmId,
                alarmKind = Alarm.KIND_NORMAL,
                scheduledTriggerMillis = verifiedOccB.scheduledAt,
                generation = verifiedAlarm.generation,
                revision = verifiedAlarm.revision,
                issueToken = verifiedOccB.issueToken,
                instanceToken = verifiedSa.instanceToken,
                restoreEpoch = verifiedSa.restoreEpoch,
                scheduleRevision = verifiedSa.scheduleRevision,
                definitionRevision = verifiedSa.definitionRevision
            )
            when (val observation = gateway.lookupMainSlot(intent.alarmId, intent.operationToken, expectedDescriptor)) {
                is FakeOsLookupObservation.Absent -> {
                    val report = blockedBoundaryReport(
                        alarmId = intent.alarmId,
                        reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
                        detail = "Replay lookup found no MAIN PendingIntent; preserving phase=${intent.phase}"
                    )
                    return Rev2ReplayRecoveryResult(
                        route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                        outcome = Rev2ReplayRecoveryOutcome.PI_ABSENT_HOLD,
                        crashCutpoint = context.crashCutpointHint ?: Rev2ReplayCrashCutpoint.C3_AROUND_OS_LOOKUP,
                        alarmId = intent.alarmId,
                        targetIssueToken = intent.operationToken,
                        intentPhaseBefore = intent.phase,
                        intentPhaseAfter = intent.phase,
                        adjudicatorDecision = adjudicatorDecision,
                        identitySafety = null,
                        boundaryReport = report,
                        fakeOsLookupInvoked = true,
                        detail = "PI_ABSENT_HOLD"
                    )
                }

                is FakeOsLookupObservation.Found -> {
                    val safety = PendingIntentIdentityComparator.evaluateSafety(
                        osKeyA = expectedDescriptor,
                        osKeyB = observation.descriptor,
                        lineageA = expectedLineage,
                        lineageB = observation.lineage
                    )
                    if (safety !is CrossIdentitySafety.ExactCandidate) {
                        val report = blockedBoundaryReport(
                            alarmId = intent.alarmId,
                            reason = CancellationRejectionReason.TARGET_WITNESS_MISMATCH,
                            detail = "Replay lookup rejected PI alias or lineage mismatch: $safety"
                        )
                        return Rev2ReplayRecoveryResult(
                            route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
                            outcome = Rev2ReplayRecoveryOutcome.PI_IDENTITY_REJECTED_ISOLATED,
                            crashCutpoint = context.crashCutpointHint ?: Rev2ReplayCrashCutpoint.C3_AROUND_OS_LOOKUP,
                            alarmId = intent.alarmId,
                            targetIssueToken = intent.operationToken,
                            intentPhaseBefore = intent.phase,
                            intentPhaseAfter = intent.phase,
                            adjudicatorDecision = adjudicatorDecision,
                            identitySafety = safety,
                            boundaryReport = report,
                            fakeOsLookupInvoked = true,
                            detail = "PI_IDENTITY_REJECTED_ISOLATED"
                        )
                    }
                    return classifyVerifiedSafeCandidateOrInFlightHold(
                        intent = intent,
                        context = context,
                        adjudicatorDecision = adjudicatorDecision,
                        identitySafety = safety,
                        fakeOsLookupInvoked = true
                    )
                }
            }
        }

        return classifyVerifiedSafeCandidateOrInFlightHold(
            intent = intent,
            context = context,
            adjudicatorDecision = adjudicatorDecision,
            identitySafety = null,
            fakeOsLookupInvoked = false
        )
    }

    private fun classifyVerifiedSafeCandidateOrInFlightHold(
        intent: DurableOperationIntent,
        context: Rev2ReplayInspectionContext,
        adjudicatorDecision: RecoveryDecision,
        identitySafety: CrossIdentitySafety?,
        fakeOsLookupInvoked: Boolean
    ): Rev2ReplayRecoveryResult {
        val isInFlight = intent.phase == DurableOperationIntent.PHASE_IN_FLIGHT
        val outcome = if (isInFlight) {
            Rev2ReplayRecoveryOutcome.IN_FLIGHT_CRASH_EVIDENCE_MISSING_HOLD
        } else {
            Rev2ReplayRecoveryOutcome.PENDING_SAFE_B_CANDIDATE_HOLD
        }
        val report = blockedBoundaryReport(
            alarmId = intent.alarmId,
            reason = CancellationRejectionReason.OS_STATE_UNCERTAIN,
            detail = if (isInFlight) {
                "IN_FLIGHT Rev2 replay held without OS completion proof; COMPLETED/ABORTED/cleanup disabled"
            } else {
                "PENDING Rev2 authentic B verified and classified as safe candidate only; real OS cancel disabled"
            }
        )
        return Rev2ReplayRecoveryResult(
            route = Rev2ReplayRoute.REV2_INACTIVE_REPLAY,
            outcome = outcome,
            crashCutpoint = context.crashCutpointHint ?: defaultCutpointForPhase(intent.phase),
            alarmId = intent.alarmId,
            targetIssueToken = intent.operationToken,
            intentPhaseBefore = intent.phase,
            intentPhaseAfter = intent.phase,
            adjudicatorDecision = adjudicatorDecision,
            identitySafety = identitySafety,
            boundaryReport = report,
            fakeOsLookupInvoked = fakeOsLookupInvoked,
            detail = outcome.name
        )
    }

    private fun defaultCutpointForPhase(phase: String): Rev2ReplayCrashCutpoint = when (phase) {
        DurableOperationIntent.PHASE_PENDING -> Rev2ReplayCrashCutpoint.C1_POST_PENDING_PERSISTED
        DurableOperationIntent.PHASE_IN_FLIGHT -> Rev2ReplayCrashCutpoint.C2_AROUND_IN_FLIGHT_CAS
        else -> Rev2ReplayCrashCutpoint.C6_REBOOT_WITH_UNKNOWN_OS_OUTCOME
    }

    private fun blockedBoundaryReport(
        alarmId: Long,
        reason: CancellationRejectionReason,
        detail: String
    ): BoundaryEvaluationReport {
        val blockedOutcome = CancelOutcome.OsStateUncertain(
            alarmId = alarmId,
            reason = reason,
            detail = detail
        )
        val caller = CallerOriginIdentity(
            alarmId = if (alarmId > 0L) alarmId else 1L,
            alarmKind = Alarm.KIND_NORMAL,
            generation = 1,
            revision = 1,
            scheduledTriggerMillis = 1L,
            scheduleAuthorityRevision = 1L,
            definitionRevision = 1L,
            instanceToken = "replay-boundary-hold",
            restoreEpoch = 1L,
            operationKind = CallerOperationKind.EXACT_OCCURRENCE,
            capturedAtMillis = 1L,
            targetScheduledAt = 1L,
            targetFireId = "replay-boundary-hold",
            sourceTag = "REV2_REPLAY_RECOVERY"
        )
        val fence = (CallerOriginFenceConverter.toFenceCandidate(caller) as CallerOriginPropagationResult.QualifiedCandidate).fence
        return CancellationExecutionBoundary.evaluate(
            BoundaryEvaluationContext(
                callerIdentity = caller,
                cancellationFence = fence,
                plannerOutcome = blockedOutcome,
                pendingIntentLookup = PendingIntentLookupStatus.NOT_ATTEMPTED,
                osCancelInvocation = OsCancelInvocationStatus.NOT_ATTEMPTED,
                osCancelProof = OsCancelProofStatus.UNCERTAIN,
                deLineageStatus = DeLineageStatus.UNCERTAIN,
                successorExclusionStatus = SuccessorExclusionStatus.UNKNOWN,
                isLegacyRed04Unresolved = false,
                includeFollowUpWorkers = false
            )
        )
    }

    private data class DbReplaySnapshot(
        val alarm: Alarm?,
        val sa: ScheduleAuthority?,
        val occB: IssuedOccurrence?,
        val slot: IssueSlot?
    )
}
