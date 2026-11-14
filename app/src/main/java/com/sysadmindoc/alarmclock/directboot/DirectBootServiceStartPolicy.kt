package com.sysadmindoc.alarmclock.directboot

import android.content.Intent
import androidx.core.net.toUri

internal enum class CandidateLane {
    LEGACY_V1,
    V2_QUALIFIED,
    V2_MALFORMED_OR_PARTIAL
}

internal enum class ServiceStartDecision {
    REJECT_KEEP_OLD,
    DUPLICATE_KEEP_OLD,
    UNKNOWN_KEEP_OLD,
    PREPARED_NEEDS_OWNER,
    LEGACY_COMPATIBILITY_PENDING
}

internal enum class ServiceStartReason {
    EXTRAS_READ_FAILED,
    INVALID_ALARM_ID,
    STORAGE_UNVERIFIABLE,
    STORAGE_NOT_QUERIED,
    INVALID_TRIGGER_TIME,
    INVALID_GENERATION,
    MISSING_INSTANCE_TOKEN,
    INVALID_RESTORE_EPOCH,
    INVALID_KIND,
    INVALID_URI,
    INVALID_DIGEST,
    INVALID_TICKET_EXTRA,
    ACTIVE_DUPLICATE_V1,
    ACTIVE_DUPLICATE_V2,
    WITNESS_DUPLICATE_V2,
    WITNESS_CONFLICT_V2,
    STALE_RESTORE_EPOCH,
    STALE_GENERATION,
    STALE_TRIGGER_TIME,
    NOT_QUEUE_HEAD,
    LEDGER_CAPACITY_EXHAUSTED,
    OWNER_CONFLICT,
    OWNER_UNVERIFIABLE,
    CROSS_LANE_OWNER_REQUIRED,
    NEEDS_DURABLE_OWNER,
    LEGACY_V1_COMPATIBLE
}

internal enum class WitnessObservationStatus {
    NOT_QUERIED,
    ABSENT_OR_EMPTY,
    PRESENT_MATCHING_EXACT,
    PRESENT_CONFLICTING,
    CORRUPT,
    UNSUPPORTED_SCHEMA
}

internal enum class WitnessOutcomeObservation {
    NOT_APPLICABLE,
    OUTPUT_STATUS_UNKNOWN,
    OUTPUT_START_ACCEPTED,
    PARTIAL_OUTPUT_ACCEPTED,
    OUTPUT_START_FAILED,
    NO_OUTPUT_REQUESTED
}

internal enum class LedgerObservationStatus {
    NOT_QUERIED,
    ABSENT_OR_EMPTY,
    PRESENT_RECORDED,
    CAPACITY_EXHAUSTED,
    CORRUPT,
    UNSUPPORTED_SCHEMA
}

internal enum class QueueObservationStatus {
    NOT_QUERIED,
    ABSENT_OR_EMPTY,
    MATCHES_HEAD,
    NOT_QUEUE_HEAD,
    CORRUPT,
    UNSUPPORTED_SCHEMA
}

internal enum class DurableOwnerObservation {
    UNAVAILABLE_NO_DURABLE_OWNER,
    VERIFIED_OWNER_MATCH,
    OWNER_CONFLICT,
    OWNER_UNVERIFIABLE
}

internal data class ActiveServiceSnapshot(
    val isActive: Boolean = false,
    val alarmId: Long = -1L,
    val triggerTime: Long = 0L,
    val generation: Int = 0,
    val instanceToken: String = "",
    val restoreEpoch: Long = 0L,
    val isV2Managed: Boolean = false,
    val isAudibleOrVibrating: Boolean = false,
    val autoStopArmed: Boolean = false
) {
    override fun toString(): String {
        return "ActiveServiceSnapshot(isActive=$isActive, alarmId=$alarmId, triggerTime=$triggerTime, " +
            "generation=$generation, tokenPresent=${instanceToken.isNotBlank()}, restoreEpoch=$restoreEpoch, " +
            "isV2Managed=$isV2Managed, isAudibleOrVibrating=$isAudibleOrVibrating, autoStopArmed=$autoStopArmed)"
    }
}

internal data class IncomingStartCandidate(
    val alarmId: Long = -1L,
    val triggerTime: Long = 0L,
    val generation: Int = 0,
    val generationProvided: Boolean = false,
    val instanceToken: String = "",
    val instanceTokenProvided: Boolean = false,
    val restoreEpoch: Long = 0L,
    val restoreEpochProvided: Boolean = false,
    val kind: String = "",
    val kindProvided: Boolean = false,
    val dataUriString: String? = null,
    val identityDigest: String? = null,
    val identityDigestProvided: Boolean = false,
    val ticketId: String? = null,
    val ticketIdProvided: Boolean = false,
    val extrasReadFailed: Boolean = false,
    val playDefaultSound: Boolean = true,
    val vibrationEnabled: Boolean = true
) {
    override fun toString(): String {
        return "IncomingStartCandidate(alarmId=$alarmId, triggerTime=$triggerTime, generation=$generation, " +
            "generationProvided=$generationProvided, tokenPresent=${instanceToken.isNotBlank()}, " +
            "instanceTokenProvided=$instanceTokenProvided, restoreEpoch=$restoreEpoch, " +
            "restoreEpochProvided=$restoreEpochProvided, kind='$kind', kindProvided=$kindProvided, " +
            "uriPresent=${dataUriString != null}, digestPresent=${identityDigest != null}, " +
            "identityDigestProvided=$identityDigestProvided, ticketPresent=${ticketId != null}, " +
            "ticketIdProvided=$ticketIdProvided, extrasReadFailed=$extrasReadFailed, " +
            "playDefaultSound=$playDefaultSound, vibrationEnabled=$vibrationEnabled)"
    }
}

internal data class StartStorageObservation(
    val witnessStorageStatus: WitnessObservationStatus = WitnessObservationStatus.NOT_QUERIED,
    val witnessOutcome: WitnessOutcomeObservation = WitnessOutcomeObservation.NOT_APPLICABLE,
    val deliveryLedgerStatus: LedgerObservationStatus = LedgerObservationStatus.NOT_QUERIED,
    val queueStorageStatus: QueueObservationStatus = QueueObservationStatus.NOT_QUERIED,
    val ownerStatus: DurableOwnerObservation = DurableOwnerObservation.UNAVAILABLE_NO_DURABLE_OWNER
)

internal data class ServiceStartClassificationInput(
    val active: ActiveServiceSnapshot,
    val candidate: IncomingStartCandidate,
    val storage: StartStorageObservation = StartStorageObservation()
)

internal data class ServiceStartDecisionResult(
    val decision: ServiceStartDecision,
    val reason: ServiceStartReason,
    val lane: CandidateLane,
    val activeAudioConfirmed: Boolean,
    val historicalWitnessMatched: Boolean
)

/**
 * Pure, side-effect-free Direct Boot Service START candidate classifier (KNO-75 Phase C7-B1a).
 *
 * Invariants:
 * - Never invokes audio, vibration, WakeLock, Handler, NotificationManager, or SharedPreferences writes.
 * - Never mutates active Service state or bypasses the default Receiver Ticket gate.
 * - [ServiceStartDecision.PREPARED_NEEDS_OWNER] classifies a structurally valid V2 candidate only;
 *   it does NOT authorize playback, preemption, or runtime activation.
 */
internal object DirectBootServiceStartPolicy {

    fun extractCandidate(intent: Intent?): IncomingStartCandidate {
        if (intent == null) {
            return IncomingStartCandidate(extrasReadFailed = true)
        }
        return try {
            val alarmId = if (intent.hasExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID)) {
                intent.getLongExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L)
            } else {
                intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, -1L)
            }
            val triggerTime = if (intent.hasExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME)) {
                intent.getLongExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, -1L)
            } else if (intent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_TRIGGER_TIME)) {
                intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_TRIGGER_TIME, -1L)
            } else {
                0L
            }
            val generationProvided = intent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION)
            val generation = if (generationProvided) {
                intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, -1)
            } else {
                0
            }
            val instanceTokenProvided = intent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN)
            val instanceToken = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN).orEmpty()
            val restoreEpochProvided = intent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH)
            val restoreEpoch = if (restoreEpochProvided) {
                intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, -1L)
            } else {
                0L
            }
            val kindProvided = intent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND)
            val kind = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND).orEmpty()
            val dataUriString = intent.dataString
            val identityDigestProvided = intent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)
            val identityDigest = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)
            val ticketIdProvided = intent.hasExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID)
            val ticketId = intent.getStringExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID)
            val playDefaultSound = intent.getBooleanExtra(DirectBootAlarmReceiver.EXTRA_PLAY_DEFAULT_SOUND, true)
            val vibrationEnabled = intent.getBooleanExtra(DirectBootAlarmReceiver.EXTRA_VIBRATION_ENABLED, true)

            IncomingStartCandidate(
                alarmId = alarmId,
                triggerTime = triggerTime,
                generation = generation,
                generationProvided = generationProvided,
                instanceToken = instanceToken,
                instanceTokenProvided = instanceTokenProvided,
                restoreEpoch = restoreEpoch,
                restoreEpochProvided = restoreEpochProvided,
                kind = kind,
                kindProvided = kindProvided,
                dataUriString = dataUriString,
                identityDigest = identityDigest,
                identityDigestProvided = identityDigestProvided,
                ticketId = ticketId,
                ticketIdProvided = ticketIdProvided,
                extrasReadFailed = false,
                playDefaultSound = playDefaultSound,
                vibrationEnabled = vibrationEnabled
            )
        } catch (_: Exception) {
            IncomingStartCandidate(extrasReadFailed = true)
        }
    }

    fun classify(input: ServiceStartClassificationInput): ServiceStartDecisionResult {
        val candidate = input.candidate
        val active = input.active
        val storage = input.storage
        val liveAudio = active.isActive && active.isAudibleOrVibrating

        if (candidate.extrasReadFailed) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.UNKNOWN_KEEP_OLD,
                reason = ServiceStartReason.EXTRAS_READ_FAILED,
                lane = CandidateLane.V2_MALFORMED_OR_PARTIAL,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        if (candidate.alarmId <= 0L) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.REJECT_KEEP_OLD,
                reason = ServiceStartReason.INVALID_ALARM_ID,
                lane = if (hasAnyV2Signal(candidate)) CandidateLane.V2_MALFORMED_OR_PARTIAL else CandidateLane.LEGACY_V1,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        if (isLegacyV1Candidate(candidate)) {
            if (candidate.triggerTime < 0L) {
                return ServiceStartDecisionResult(
                    decision = ServiceStartDecision.REJECT_KEEP_OLD,
                    reason = ServiceStartReason.INVALID_TRIGGER_TIME,
                    lane = CandidateLane.LEGACY_V1,
                    activeAudioConfirmed = liveAudio,
                    historicalWitnessMatched = false
                )
            }
            if (isStorageUnverifiable(storage)) {
                return ServiceStartDecisionResult(
                    decision = ServiceStartDecision.UNKNOWN_KEEP_OLD,
                    reason = ServiceStartReason.STORAGE_UNVERIFIABLE,
                    lane = CandidateLane.LEGACY_V1,
                    activeAudioConfirmed = liveAudio,
                    historicalWitnessMatched = false
                )
            }
            if (active.isActive && active.isV2Managed) {
                return ServiceStartDecisionResult(
                    decision = ServiceStartDecision.UNKNOWN_KEEP_OLD,
                    reason = ServiceStartReason.CROSS_LANE_OWNER_REQUIRED,
                    lane = CandidateLane.LEGACY_V1,
                    activeAudioConfirmed = liveAudio,
                    historicalWitnessMatched = false
                )
            }
            if (active.isActive &&
                active.alarmId == candidate.alarmId &&
                active.triggerTime == candidate.triggerTime
            ) {
                return ServiceStartDecisionResult(
                    decision = ServiceStartDecision.DUPLICATE_KEEP_OLD,
                    reason = ServiceStartReason.ACTIVE_DUPLICATE_V1,
                    lane = CandidateLane.LEGACY_V1,
                    activeAudioConfirmed = liveAudio,
                    historicalWitnessMatched = false
                )
            }
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.LEGACY_COMPATIBILITY_PENDING,
                reason = ServiceStartReason.LEGACY_V1_COMPATIBLE,
                lane = CandidateLane.LEGACY_V1,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        val v2StructuralFailure = validateV2Structure(candidate)
        if (v2StructuralFailure != null) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.REJECT_KEEP_OLD,
                reason = v2StructuralFailure,
                lane = CandidateLane.V2_MALFORMED_OR_PARTIAL,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        if (isStorageUnverifiable(storage)) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.UNKNOWN_KEEP_OLD,
                reason = ServiceStartReason.STORAGE_UNVERIFIABLE,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        val witnessMatched = storage.witnessStorageStatus == WitnessObservationStatus.PRESENT_MATCHING_EXACT

        if (active.isActive &&
            active.isV2Managed &&
            active.alarmId == candidate.alarmId &&
            active.triggerTime == candidate.triggerTime &&
            active.generation == candidate.generation &&
            active.instanceToken == candidate.instanceToken &&
            active.restoreEpoch == candidate.restoreEpoch
        ) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.DUPLICATE_KEEP_OLD,
                reason = ServiceStartReason.ACTIVE_DUPLICATE_V2,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = witnessMatched
            )
        }

        if (witnessMatched) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.DUPLICATE_KEEP_OLD,
                reason = ServiceStartReason.WITNESS_DUPLICATE_V2,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = true
            )
        }

        if (storage.witnessStorageStatus == WitnessObservationStatus.PRESENT_CONFLICTING) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.REJECT_KEEP_OLD,
                reason = ServiceStartReason.WITNESS_CONFLICT_V2,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        if (active.isActive && active.isV2Managed) {
            if (candidate.restoreEpoch < active.restoreEpoch) {
                return ServiceStartDecisionResult(
                    decision = ServiceStartDecision.REJECT_KEEP_OLD,
                    reason = ServiceStartReason.STALE_RESTORE_EPOCH,
                    lane = CandidateLane.V2_QUALIFIED,
                    activeAudioConfirmed = liveAudio,
                    historicalWitnessMatched = false
                )
            }
            if (candidate.restoreEpoch == active.restoreEpoch && candidate.alarmId == active.alarmId) {
                if (candidate.generation < active.generation) {
                    return ServiceStartDecisionResult(
                        decision = ServiceStartDecision.REJECT_KEEP_OLD,
                        reason = ServiceStartReason.STALE_GENERATION,
                        lane = CandidateLane.V2_QUALIFIED,
                        activeAudioConfirmed = liveAudio,
                        historicalWitnessMatched = false
                    )
                }
                if (candidate.generation == active.generation && candidate.triggerTime < active.triggerTime) {
                    return ServiceStartDecisionResult(
                        decision = ServiceStartDecision.REJECT_KEEP_OLD,
                        reason = ServiceStartReason.STALE_TRIGGER_TIME,
                        lane = CandidateLane.V2_QUALIFIED,
                        activeAudioConfirmed = liveAudio,
                        historicalWitnessMatched = false
                    )
                }
            }
        }

        if (storage.deliveryLedgerStatus == LedgerObservationStatus.CAPACITY_EXHAUSTED) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.REJECT_KEEP_OLD,
                reason = ServiceStartReason.LEDGER_CAPACITY_EXHAUSTED,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        if (storage.queueStorageStatus == QueueObservationStatus.NOT_QUEUE_HEAD &&
            storage.deliveryLedgerStatus != LedgerObservationStatus.PRESENT_RECORDED
        ) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.REJECT_KEEP_OLD,
                reason = ServiceStartReason.NOT_QUEUE_HEAD,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        if (storage.witnessStorageStatus == WitnessObservationStatus.NOT_QUERIED ||
            storage.deliveryLedgerStatus == LedgerObservationStatus.NOT_QUERIED ||
            storage.queueStorageStatus == QueueObservationStatus.NOT_QUERIED
        ) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.UNKNOWN_KEEP_OLD,
                reason = ServiceStartReason.STORAGE_NOT_QUERIED,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        if (storage.queueStorageStatus == QueueObservationStatus.ABSENT_OR_EMPTY &&
            storage.deliveryLedgerStatus == LedgerObservationStatus.ABSENT_OR_EMPTY
        ) {
            return ServiceStartDecisionResult(
                decision = ServiceStartDecision.REJECT_KEEP_OLD,
                reason = ServiceStartReason.NOT_QUEUE_HEAD,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }

        return when (storage.ownerStatus) {
            DurableOwnerObservation.OWNER_CONFLICT -> ServiceStartDecisionResult(
                decision = ServiceStartDecision.REJECT_KEEP_OLD,
                reason = ServiceStartReason.OWNER_CONFLICT,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
            DurableOwnerObservation.OWNER_UNVERIFIABLE -> ServiceStartDecisionResult(
                decision = ServiceStartDecision.UNKNOWN_KEEP_OLD,
                reason = ServiceStartReason.OWNER_UNVERIFIABLE,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
            DurableOwnerObservation.UNAVAILABLE_NO_DURABLE_OWNER,
            DurableOwnerObservation.VERIFIED_OWNER_MATCH -> ServiceStartDecisionResult(
                decision = ServiceStartDecision.PREPARED_NEEDS_OWNER,
                reason = ServiceStartReason.NEEDS_DURABLE_OWNER,
                lane = CandidateLane.V2_QUALIFIED,
                activeAudioConfirmed = liveAudio,
                historicalWitnessMatched = false
            )
        }
    }

    private fun hasAnyV2Signal(c: IncomingStartCandidate): Boolean {
        return c.instanceToken.isNotBlank() ||
            c.restoreEpoch != 0L ||
            c.generation < 0 ||
            c.generation >= 2 ||
            c.kindProvided ||
            c.dataUriString != null ||
            c.identityDigestProvided ||
            c.identityDigest != null ||
            c.ticketIdProvided ||
            c.ticketId != null
    }

    private fun isLegacyV1Candidate(c: IncomingStartCandidate): Boolean {
        return c.instanceToken.isBlank() &&
            c.restoreEpoch == 0L &&
            c.generation in 0..1 &&
            !c.kindProvided &&
            c.dataUriString == null &&
            !c.identityDigestProvided &&
            c.identityDigest == null &&
            !c.ticketIdProvided &&
            c.ticketId == null
    }

    private fun isStorageUnverifiable(s: StartStorageObservation): Boolean {
        return s.witnessStorageStatus == WitnessObservationStatus.CORRUPT ||
            s.witnessStorageStatus == WitnessObservationStatus.UNSUPPORTED_SCHEMA ||
            s.deliveryLedgerStatus == LedgerObservationStatus.CORRUPT ||
            s.deliveryLedgerStatus == LedgerObservationStatus.UNSUPPORTED_SCHEMA ||
            s.queueStorageStatus == QueueObservationStatus.CORRUPT ||
            s.queueStorageStatus == QueueObservationStatus.UNSUPPORTED_SCHEMA
    }

    private fun validateV2Structure(c: IncomingStartCandidate): ServiceStartReason? {
        if (c.kindProvided && c.kind != DirectBootV2NormalIntentIdentity.KIND_NORMAL) {
            return ServiceStartReason.INVALID_KIND
        }
        if (c.ticketIdProvided && c.ticketId.isNullOrBlank()) {
            return ServiceStartReason.INVALID_TICKET_EXTRA
        }
        if (c.triggerTime <= 0L) return ServiceStartReason.INVALID_TRIGGER_TIME
        if (c.generation < 1) return ServiceStartReason.INVALID_GENERATION
        if (c.instanceToken.isBlank()) return ServiceStartReason.MISSING_INSTANCE_TOKEN
        if (c.restoreEpoch <= 0L) return ServiceStartReason.INVALID_RESTORE_EPOCH

        val identity = try {
            DirectBootV2NormalIntentIdentity(
                alarmId = c.alarmId,
                generation = c.generation,
                instanceToken = c.instanceToken,
                triggerTime = c.triggerTime,
                restoreEpoch = c.restoreEpoch,
                kind = DirectBootV2NormalIntentIdentity.KIND_NORMAL
            )
        } catch (_: Exception) {
            return ServiceStartReason.INVALID_ALARM_ID
        }

        if (c.dataUriString != null) {
            val uri = try {
                c.dataUriString.toUri()
            } catch (_: Exception) {
                return ServiceStartReason.INVALID_URI
            }
            if (DirectBootV2NormalIntentIdentity.validateUri(uri, identity) is UriValidationResult.Rejected) {
                return ServiceStartReason.INVALID_URI
            }
        }

        if ((c.identityDigestProvided || c.identityDigest != null) &&
            (c.identityDigest.isNullOrBlank() || c.identityDigest != identity.computeDigest())
        ) {
            return ServiceStartReason.INVALID_DIGEST
        }

        return null
    }
}
