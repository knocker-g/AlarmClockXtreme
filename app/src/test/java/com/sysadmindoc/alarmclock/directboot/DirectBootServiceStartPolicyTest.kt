package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pure Policy Contract Test Suite for KNO-75 Phase C7-B1a (Inactive ServiceStart Classifier).
 *
 * Scope boundary:
 * - Proves the pure side-effect-free [DirectBootServiceStartPolicy] contract only.
 * - Does NOT claim production [DirectBootAlarmService] integration or audio/vibration gating (deferred to B1b/B1c).
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootServiceStartPolicyTest {

    private lateinit var context: Context
    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun verifiedClearV2Storage(
        ownerStatus: DurableOwnerObservation = DurableOwnerObservation.UNAVAILABLE_NO_DURABLE_OWNER
    ): StartStorageObservation {
        return StartStorageObservation(
            witnessStorageStatus = WitnessObservationStatus.ABSENT_OR_EMPTY,
            witnessOutcome = WitnessOutcomeObservation.NOT_APPLICABLE,
            deliveryLedgerStatus = LedgerObservationStatus.ABSENT_OR_EMPTY,
            queueStorageStatus = QueueObservationStatus.MATCHES_HEAD,
            ownerStatus = ownerStatus
        )
    }

    private fun qualifiedV2Candidate(
        alarmId: Long = 101L,
        triggerTime: Long = baseTime + 10_000L,
        generation: Int = 1,
        instanceToken: String = "tok_v2_101",
        restoreEpoch: Long = 1L,
        kind: String = DirectBootV2NormalIntentIdentity.KIND_NORMAL,
        kindProvided: Boolean = false,
        dataUriString: String? = null,
        identityDigest: String? = null,
        identityDigestProvided: Boolean = false,
        ticketId: String? = null,
        ticketIdProvided: Boolean = false
    ): IncomingStartCandidate {
        return IncomingStartCandidate(
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = generation,
            generationProvided = true,
            instanceToken = instanceToken,
            instanceTokenProvided = true,
            restoreEpoch = restoreEpoch,
            restoreEpochProvided = true,
            kind = kind,
            kindProvided = kindProvided,
            dataUriString = dataUriString,
            identityDigest = identityDigest,
            identityDigestProvided = identityDigestProvided,
            ticketId = ticketId,
            ticketIdProvided = ticketIdProvided
        )
    }

    // =========================================================================
    // B1a-F01: Idle + Qualified V2 -> PREPARED_NEEDS_OWNER (Non-Admitting)
    // =========================================================================

    @Test
    fun b1a_f01_idle_qualifiedV2_withVerifiedStorage_returnsPreparedNeedsOwner() {
        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(),
                storage = verifiedClearV2Storage()
            )
        )

        assertEquals(ServiceStartDecision.PREPARED_NEEDS_OWNER, result.decision)
        assertEquals(ServiceStartReason.NEEDS_DURABLE_OWNER, result.reason)
        assertEquals(CandidateLane.V2_QUALIFIED, result.lane)
        assertFalse(result.activeAudioConfirmed)
        assertFalse(result.historicalWitnessMatched)
    }

    @Test
    fun b1a_f01b_qualifiedV2_withLedgerRecordedAfterReceiverTransition_returnsPreparedNeedsOwner() {
        val storage = StartStorageObservation(
            witnessStorageStatus = WitnessObservationStatus.ABSENT_OR_EMPTY,
            deliveryLedgerStatus = LedgerObservationStatus.PRESENT_RECORDED,
            queueStorageStatus = QueueObservationStatus.ABSENT_OR_EMPTY,
            ownerStatus = DurableOwnerObservation.UNAVAILABLE_NO_DURABLE_OWNER
        )

        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(),
                storage = storage
            )
        )

        assertEquals(ServiceStartDecision.PREPARED_NEEDS_OWNER, result.decision)
        assertEquals(ServiceStartReason.NEEDS_DURABLE_OWNER, result.reason)
    }

    // =========================================================================
    // B1a-F02 & F03: Duplicate V2 & Historical Witness Distinction
    // =========================================================================

    @Test
    fun b1a_f02_activeV2_duplicateV2_returnsDuplicateKeepOld() {
        val active = ActiveServiceSnapshot(
            isActive = true,
            alarmId = 101L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = "tok_v2_101",
            restoreEpoch = 1L,
            isV2Managed = true,
            isAudibleOrVibrating = true,
            autoStopArmed = true
        )

        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = active,
                candidate = qualifiedV2Candidate(),
                storage = verifiedClearV2Storage()
            )
        )

        assertEquals(ServiceStartDecision.DUPLICATE_KEEP_OLD, result.decision)
        assertEquals(ServiceStartReason.ACTIVE_DUPLICATE_V2, result.reason)
        assertEquals(CandidateLane.V2_QUALIFIED, result.lane)
        assertTrue(result.activeAudioConfirmed)
    }

    @Test
    fun b1a_f03_idleOrRestart_matchingWitness_returnsDuplicateKeepOld_withoutClaimingActiveAudio() {
        val storage = StartStorageObservation(
            witnessStorageStatus = WitnessObservationStatus.PRESENT_MATCHING_EXACT,
            witnessOutcome = WitnessOutcomeObservation.OUTPUT_START_ACCEPTED,
            deliveryLedgerStatus = LedgerObservationStatus.PRESENT_RECORDED,
            queueStorageStatus = QueueObservationStatus.ABSENT_OR_EMPTY
        )

        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false, isAudibleOrVibrating = false),
                candidate = qualifiedV2Candidate(),
                storage = storage
            )
        )

        assertEquals(ServiceStartDecision.DUPLICATE_KEEP_OLD, result.decision)
        assertEquals(ServiceStartReason.WITNESS_DUPLICATE_V2, result.reason)
        assertFalse("Historical witness MUST NOT be reported as active audio playback", result.activeAudioConfirmed)
        assertTrue(result.historicalWitnessMatched)
    }

    @Test
    fun b1a_f03b_duplicateWitness_withOutcomeUnknown_returnsDuplicateKeepOld() {
        val storage = StartStorageObservation(
            witnessStorageStatus = WitnessObservationStatus.PRESENT_MATCHING_EXACT,
            witnessOutcome = WitnessOutcomeObservation.OUTPUT_STATUS_UNKNOWN,
            deliveryLedgerStatus = LedgerObservationStatus.PRESENT_RECORDED,
            queueStorageStatus = QueueObservationStatus.ABSENT_OR_EMPTY
        )

        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(),
                storage = storage
            )
        )

        assertEquals(ServiceStartDecision.DUPLICATE_KEEP_OLD, result.decision)
        assertEquals(ServiceStartReason.WITNESS_DUPLICATE_V2, result.reason)
        assertFalse(result.activeAudioConfirmed)
        assertTrue(result.historicalWitnessMatched)
    }

    // =========================================================================
    // B1a-F04, F05, F06: Malformed / Partial V2 (No Silent V1 Downgrade)
    // =========================================================================

    @Test
    fun b1a_f04_partialV2_tokenPresentZeroEpoch_returnsRejectKeepOld() {
        val active = ActiveServiceSnapshot(
            isActive = true,
            alarmId = 99L,
            triggerTime = baseTime + 5_000L,
            generation = 1,
            instanceToken = "tok_active",
            restoreEpoch = 2L,
            isV2Managed = true,
            isAudibleOrVibrating = true,
            autoStopArmed = true
        )

        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = active,
                candidate = qualifiedV2Candidate(instanceToken = "tok_zero_epoch", restoreEpoch = 0L),
                storage = verifiedClearV2Storage()
            )
        )

        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, result.decision)
        assertEquals(ServiceStartReason.INVALID_RESTORE_EPOCH, result.reason)
        assertEquals(CandidateLane.V2_MALFORMED_OR_PARTIAL, result.lane)
    }

    @Test
    fun b1a_f05_partialV2_tokenAbsentNonzeroEpoch_returnsRejectKeepOld_withoutV1Downgrade() {
        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = true, alarmId = 101L, isAudibleOrVibrating = true),
                candidate = IncomingStartCandidate(
                    alarmId = 202L,
                    triggerTime = baseTime + 20_000L,
                    generation = 1,
                    generationProvided = true,
                    instanceToken = "",
                    instanceTokenProvided = false,
                    restoreEpoch = 5L,
                    restoreEpochProvided = true
                ),
                storage = verifiedClearV2Storage()
            )
        )

        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, result.decision)
        assertEquals(ServiceStartReason.MISSING_INSTANCE_TOKEN, result.reason)
        assertEquals(CandidateLane.V2_MALFORMED_OR_PARTIAL, result.lane)
    }

    @Test
    fun b1a_f06a_malformedV2_quickKind_returnsRejectKeepOld() {
        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(kind = DirectBootQueueItem.KIND_QUICK, kindProvided = true),
                storage = verifiedClearV2Storage()
            )
        )

        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, result.decision)
        assertEquals(ServiceStartReason.INVALID_KIND, result.reason)
        assertEquals(CandidateLane.V2_MALFORMED_OR_PARTIAL, result.lane)
    }

    @Test
    fun b1a_f06b_malformedV2_invalidOrTamperedUri_returnsRejectKeepOld() {
        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(
                    dataUriString = "acx-directboot://normal/alarm/101/v2/tampered_digest"
                ),
                storage = verifiedClearV2Storage()
            )
        )

        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, result.decision)
        assertEquals(ServiceStartReason.INVALID_URI, result.reason)
        assertEquals(CandidateLane.V2_MALFORMED_OR_PARTIAL, result.lane)
    }

    @Test
    fun b1a_f06c_malformedV2_mismatchedOrBlankDigest_returnsRejectKeepOld() {
        val identity = DirectBootV2NormalIntentIdentity(
            alarmId = 101L,
            generation = 1,
            instanceToken = "tok_v2_101",
            triggerTime = baseTime + 10_000L,
            restoreEpoch = 1L
        )

        val mismatched = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(
                    dataUriString = identity.toUriString(),
                    identityDigest = "deadbeef",
                    identityDigestProvided = true
                ),
                storage = verifiedClearV2Storage()
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, mismatched.decision)
        assertEquals(ServiceStartReason.INVALID_DIGEST, mismatched.reason)

        val explicitlyBlank = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(
                    dataUriString = identity.toUriString(),
                    identityDigest = "",
                    identityDigestProvided = true
                ),
                storage = verifiedClearV2Storage()
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, explicitlyBlank.decision)
        assertEquals(ServiceStartReason.INVALID_DIGEST, explicitlyBlank.reason)
    }

    @Test
    fun b1a_f06d_missingVsExplicitlyBlankFields_rejectedWithoutV1Downgrade() {
        // Explicit blank EXTRA_KIND="" with V1-looking gen=0/token=""/epoch=0 must NOT downgrade to V1
        val explicitBlankKind = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = IncomingStartCandidate(
                    alarmId = 101L,
                    triggerTime = baseTime + 10_000L,
                    generation = 0,
                    generationProvided = true,
                    instanceToken = "",
                    instanceTokenProvided = false,
                    restoreEpoch = 0L,
                    restoreEpochProvided = false,
                    kind = "",
                    kindProvided = true
                )
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, explicitBlankKind.decision)
        assertEquals(ServiceStartReason.INVALID_KIND, explicitBlankKind.reason)
        assertEquals(CandidateLane.V2_MALFORMED_OR_PARTIAL, explicitBlankKind.lane)

        // Explicit blank EXTRA_TICKET_ID="" without V2 fields must NOT downgrade to V1
        val explicitBlankTicket = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = IncomingStartCandidate(
                    alarmId = 101L,
                    triggerTime = baseTime + 10_000L,
                    generation = 1,
                    generationProvided = true,
                    ticketId = "",
                    ticketIdProvided = true
                )
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, explicitBlankTicket.decision)
        assertEquals(ServiceStartReason.INVALID_TICKET_EXTRA, explicitBlankTicket.reason)
        assertEquals(CandidateLane.V2_MALFORMED_OR_PARTIAL, explicitBlankTicket.lane)

        // Explicit generation=0 paired with token -> INVALID_GENERATION
        val genZeroWithToken = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(generation = 0)
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, genZeroWithToken.decision)
        assertEquals(ServiceStartReason.INVALID_GENERATION, genZeroWithToken.reason)
    }

    // =========================================================================
    // B1a-F07: Stale Epoch / Generation / TriggerTime
    // =========================================================================

    @Test
    fun b1a_f07_staleV2_epochGenerationTriggerTime_returnsRejectKeepOld() {
        val active = ActiveServiceSnapshot(
            isActive = true,
            alarmId = 301L,
            triggerTime = baseTime + 50_000L,
            generation = 3,
            instanceToken = "tok_active_301",
            restoreEpoch = 4L,
            isV2Managed = true,
            isAudibleOrVibrating = true,
            autoStopArmed = true
        )

        val staleEpoch = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = active,
                candidate = qualifiedV2Candidate(alarmId = 302L, triggerTime = baseTime + 60_000L, generation = 5, instanceToken = "tok_302", restoreEpoch = 3L),
                storage = verifiedClearV2Storage()
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, staleEpoch.decision)
        assertEquals(ServiceStartReason.STALE_RESTORE_EPOCH, staleEpoch.reason)

        val staleGen = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = active,
                candidate = qualifiedV2Candidate(alarmId = 301L, triggerTime = baseTime + 60_000L, generation = 2, instanceToken = "tok_301_old", restoreEpoch = 4L),
                storage = verifiedClearV2Storage()
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, staleGen.decision)
        assertEquals(ServiceStartReason.STALE_GENERATION, staleGen.reason)

        val staleTrig = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = active,
                candidate = qualifiedV2Candidate(alarmId = 301L, triggerTime = baseTime + 40_000L, generation = 3, instanceToken = "tok_301_trig", restoreEpoch = 4L),
                storage = verifiedClearV2Storage()
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, staleTrig.decision)
        assertEquals(ServiceStartReason.STALE_TRIGGER_TIME, staleTrig.reason)
    }

    // =========================================================================
    // B1a-F08: Corrupt / Unsupported Storage & Invalid Input Precedence
    // =========================================================================

    @Test
    fun b1a_f08a_corruptOrUnsupportedStorage_withValidCandidate_returnsUnknownKeepOld() {
        val corruptWitness = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = true, alarmId = 10L, isV2Managed = true, isAudibleOrVibrating = true),
                candidate = qualifiedV2Candidate(),
                storage = verifiedClearV2Storage().copy(witnessStorageStatus = WitnessObservationStatus.CORRUPT)
            )
        )
        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, corruptWitness.decision)
        assertEquals(ServiceStartReason.STORAGE_UNVERIFIABLE, corruptWitness.reason)

        val unsupportedQueue = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(),
                storage = verifiedClearV2Storage().copy(queueStorageStatus = QueueObservationStatus.UNSUPPORTED_SCHEMA)
            )
        )
        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, unsupportedQueue.decision)
        assertEquals(ServiceStartReason.STORAGE_UNVERIFIABLE, unsupportedQueue.reason)
    }

    @Test
    fun b1a_f08b_invalidInputPlusCorruptStorage_precedence() {
        val corruptStorage = StartStorageObservation(
            witnessStorageStatus = WitnessObservationStatus.CORRUPT,
            deliveryLedgerStatus = LedgerObservationStatus.CORRUPT,
            queueStorageStatus = QueueObservationStatus.CORRUPT
        )

        // Unreadable intent fails to UNKNOWN_KEEP_OLD (EXTRAS_READ_FAILED)
        val unreadable = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = IncomingStartCandidate(extrasReadFailed = true),
                storage = corruptStorage
            )
        )
        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, unreadable.decision)
        assertEquals(ServiceStartReason.EXTRAS_READ_FAILED, unreadable.reason)

        // Structurally invalid alarmId / malformed V2 fails to REJECT_KEEP_OLD before trusting/consulting storage
        val invalidAlarmId = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = IncomingStartCandidate(alarmId = -1L),
                storage = corruptStorage
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, invalidAlarmId.decision)
        assertEquals(ServiceStartReason.INVALID_ALARM_ID, invalidAlarmId.reason)

        val malformedV2 = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(restoreEpoch = 0L),
                storage = corruptStorage
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, malformedV2.decision)
        assertEquals(ServiceStartReason.INVALID_RESTORE_EPOCH, malformedV2.reason)
    }

    // =========================================================================
    // B1a-F09: Same-UID Direct START Without Durable Owner
    // =========================================================================

    @Test
    fun b1a_f09_sameUidDirectStart_qualifiedV2WithoutOwner_isNonAdmitting() {
        // Simulates same-UID caller invoking DirectBootAlarmService directly with valid V2 fields
        val noOwner = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = true, alarmId = 50L, isV2Managed = false, isAudibleOrVibrating = true),
                candidate = qualifiedV2Candidate(alarmId = 101L),
                storage = verifiedClearV2Storage(ownerStatus = DurableOwnerObservation.UNAVAILABLE_NO_DURABLE_OWNER)
            )
        )
        assertEquals(ServiceStartDecision.PREPARED_NEEDS_OWNER, noOwner.decision)
        assertEquals(ServiceStartReason.NEEDS_DURABLE_OWNER, noOwner.reason)

        val ownerConflict = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(),
                storage = verifiedClearV2Storage(ownerStatus = DurableOwnerObservation.OWNER_CONFLICT)
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, ownerConflict.decision)
        assertEquals(ServiceStartReason.OWNER_CONFLICT, ownerConflict.reason)

        val ownerUnverifiable = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = qualifiedV2Candidate(),
                storage = verifiedClearV2Storage(ownerStatus = DurableOwnerObservation.OWNER_UNVERIFIABLE)
            )
        )
        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, ownerUnverifiable.decision)
        assertEquals(ServiceStartReason.OWNER_UNVERIFIABLE, ownerUnverifiable.reason)
    }

    // =========================================================================
    // B1a-F10, F11, F12: Legacy V1 Compatibility (gen=0 & gen=1) & Cross-Lane
    // =========================================================================

    @Test
    fun b1a_f10_f11_legitimateLegacyV1_generationZeroAndOne_returnsLegacyCompatibilityPending() {
        val v1Gen0 = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = IncomingStartCandidate(
                    alarmId = 201L,
                    triggerTime = baseTime + 20_000L,
                    generation = 0,
                    generationProvided = true,
                    instanceToken = "",
                    instanceTokenProvided = true,
                    restoreEpoch = 0L,
                    restoreEpochProvided = true
                )
            )
        )
        assertEquals(ServiceStartDecision.LEGACY_COMPATIBILITY_PENDING, v1Gen0.decision)
        assertEquals(ServiceStartReason.LEGACY_V1_COMPATIBLE, v1Gen0.reason)
        assertEquals(CandidateLane.LEGACY_V1, v1Gen0.lane)

        val v1ReceiverGen1 = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = IncomingStartCandidate(
                    alarmId = 801L,
                    triggerTime = baseTime + 80_000L,
                    generation = 1,
                    generationProvided = true,
                    instanceToken = "",
                    instanceTokenProvided = true,
                    restoreEpoch = 0L,
                    restoreEpochProvided = true
                )
            )
        )
        assertEquals(ServiceStartDecision.LEGACY_COMPATIBILITY_PENDING, v1ReceiverGen1.decision)
        assertEquals(ServiceStartReason.LEGACY_V1_COMPATIBLE, v1ReceiverGen1.reason)
        assertEquals(CandidateLane.LEGACY_V1, v1ReceiverGen1.lane)

        // Exact active V1 duplicate preserves existing V1 alarm without restart
        val v1Dup = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(
                    isActive = true,
                    alarmId = 801L,
                    triggerTime = baseTime + 80_000L,
                    generation = 1,
                    isV2Managed = false,
                    isAudibleOrVibrating = true
                ),
                candidate = IncomingStartCandidate(
                    alarmId = 801L,
                    triggerTime = baseTime + 80_000L,
                    generation = 1,
                    generationProvided = true
                )
            )
        )
        assertEquals(ServiceStartDecision.DUPLICATE_KEEP_OLD, v1Dup.decision)
        assertEquals(ServiceStartReason.ACTIVE_DUPLICATE_V1, v1Dup.reason)
    }

    @Test
    fun b1a_f12_crossLane_legacyV1OverActiveV2_returnsUnknownKeepOld() {
        val activeV2 = ActiveServiceSnapshot(
            isActive = true,
            alarmId = 500L,
            triggerTime = baseTime + 50_000L,
            generation = 1,
            instanceToken = "tok_active_500",
            restoreEpoch = 2L,
            isV2Managed = true,
            isAudibleOrVibrating = true,
            autoStopArmed = true
        )

        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = activeV2,
                candidate = IncomingStartCandidate(
                    alarmId = 801L,
                    triggerTime = baseTime + 80_000L,
                    generation = 1,
                    generationProvided = true
                )
            )
        )

        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, result.decision)
        assertEquals(ServiceStartReason.CROSS_LANE_OWNER_REQUIRED, result.reason)
        assertEquals(CandidateLane.LEGACY_V1, result.lane)
    }

    // =========================================================================
    // B1a-F13: Secret Redaction in Input toString() & Result
    // =========================================================================

    @Test
    fun b1a_f13_secretRedaction_inputToStringAndDecisionResult_neverExposeSentinelTokens() {
        val sentinelToken = "SECRET_SENTINEL_TOKEN_B1A_999"
        val sentinelTicket = "SECRET_SENTINEL_TICKET_B1A_888"
        val sentinelDigest = "abcdef1234567890_sentinel_digest"

        val active = ActiveServiceSnapshot(
            isActive = true,
            alarmId = 101L,
            triggerTime = baseTime + 10_000L,
            generation = 1,
            instanceToken = sentinelToken,
            restoreEpoch = 1L,
            isV2Managed = true,
            isAudibleOrVibrating = true
        )
        val candidate = qualifiedV2Candidate(
            instanceToken = sentinelToken,
            identityDigest = sentinelDigest,
            identityDigestProvided = true,
            ticketId = sentinelTicket,
            ticketIdProvided = true
        )

        assertFalse("ActiveServiceSnapshot.toString() MUST NOT expose raw instanceToken", active.toString().contains(sentinelToken))
        assertFalse("IncomingStartCandidate.toString() MUST NOT expose raw instanceToken", candidate.toString().contains(sentinelToken))
        assertFalse("IncomingStartCandidate.toString() MUST NOT expose raw ticketId", candidate.toString().contains(sentinelTicket))
        assertFalse("IncomingStartCandidate.toString() MUST NOT expose raw identityDigest", candidate.toString().contains(sentinelDigest))

        val result = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = active,
                candidate = candidate,
                storage = verifiedClearV2Storage()
            )
        )
        val resultStr = result.toString()
        assertFalse("ServiceStartDecisionResult.toString() MUST NOT expose raw instanceToken", resultStr.contains(sentinelToken))
        assertFalse("ServiceStartDecisionResult.reason.name MUST NOT expose raw token", result.reason.name.contains(sentinelToken))
        assertFalse("ServiceStartDecisionResult.lane.name MUST NOT expose raw token", result.lane.name.contains(sentinelToken))

        val sentinelKind = "ATTACKER_KIND_SENTINEL_B1A_777"
        val sentinelUri = "acx-directboot://normal/alarm/101/v2/SECRET_URI_SENTINEL_666"
        val untrustedKindCandidate = qualifiedV2Candidate(
            instanceToken = sentinelToken,
            kind = sentinelKind,
            kindProvided = true,
            dataUriString = sentinelUri,
            identityDigest = sentinelDigest,
            identityDigestProvided = true,
            ticketId = sentinelTicket,
            ticketIdProvided = true
        )
        val untrustedKindCandidateStr = untrustedKindCandidate.toString()
        assertFalse("IncomingStartCandidate.toString() MUST NOT expose raw kind", untrustedKindCandidateStr.contains(sentinelKind))
        assertFalse("IncomingStartCandidate.toString() MUST NOT expose raw dataUriString", untrustedKindCandidateStr.contains(sentinelUri))
        assertFalse("IncomingStartCandidate.toString() MUST NOT expose raw instanceToken", untrustedKindCandidateStr.contains(sentinelToken))
        assertFalse("IncomingStartCandidate.toString() MUST NOT expose raw ticketId", untrustedKindCandidateStr.contains(sentinelTicket))
        assertFalse("IncomingStartCandidate.toString() MUST NOT expose raw identityDigest", untrustedKindCandidateStr.contains(sentinelDigest))
        assertTrue("IncomingStartCandidate.toString() should indicate kind presence safely", untrustedKindCandidateStr.contains("kindPresent=true"))
        assertTrue("IncomingStartCandidate.toString() should indicate URI presence safely", untrustedKindCandidateStr.contains("uriPresent=true"))

        val rejectedKindResult = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = active,
                candidate = untrustedKindCandidate,
                storage = verifiedClearV2Storage()
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, rejectedKindResult.decision)
        assertEquals(ServiceStartReason.INVALID_KIND, rejectedKindResult.reason)
        assertFalse("ServiceStartDecisionResult.toString() MUST NOT expose raw kind", rejectedKindResult.toString().contains(sentinelKind))
    }

    // =========================================================================
    // B1a-F14: NOT_QUERIED vs Verified Absence & Queue/Ledger Boundaries
    // =========================================================================

    @Test
    fun b1a_f14_notQueriedStorage_neverTreatedAsVerifiedAbsence() {
        val v2Candidate = qualifiedV2Candidate()

        val allNotQueried = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = v2Candidate,
                storage = StartStorageObservation()
            )
        )
        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, allNotQueried.decision)
        assertEquals(ServiceStartReason.STORAGE_NOT_QUERIED, allNotQueried.reason)

        val witnessNotQueriedOnly = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = v2Candidate,
                storage = verifiedClearV2Storage().copy(witnessStorageStatus = WitnessObservationStatus.NOT_QUERIED)
            )
        )
        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, witnessNotQueriedOnly.decision)
        assertEquals(ServiceStartReason.STORAGE_NOT_QUERIED, witnessNotQueriedOnly.reason)

        val ledgerNotQueriedOnly = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = v2Candidate,
                storage = verifiedClearV2Storage().copy(deliveryLedgerStatus = LedgerObservationStatus.NOT_QUERIED)
            )
        )
        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, ledgerNotQueriedOnly.decision)
        assertEquals(ServiceStartReason.STORAGE_NOT_QUERIED, ledgerNotQueriedOnly.reason)

        val queueNotQueriedOnly = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = v2Candidate,
                storage = verifiedClearV2Storage().copy(queueStorageStatus = QueueObservationStatus.NOT_QUERIED)
            )
        )
        assertEquals(ServiceStartDecision.UNKNOWN_KEEP_OLD, queueNotQueriedOnly.decision)
        assertEquals(ServiceStartReason.STORAGE_NOT_QUERIED, queueNotQueriedOnly.reason)

        // Not queue head and not recorded in delivery ledger -> REJECT_KEEP_OLD
        val notQueueHead = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = v2Candidate,
                storage = verifiedClearV2Storage().copy(queueStorageStatus = QueueObservationStatus.NOT_QUEUE_HEAD)
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, notQueueHead.decision)
        assertEquals(ServiceStartReason.NOT_QUEUE_HEAD, notQueueHead.reason)

        // Capacity exhausted -> REJECT_KEEP_OLD
        val capacityExhausted = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = v2Candidate,
                storage = verifiedClearV2Storage().copy(deliveryLedgerStatus = LedgerObservationStatus.CAPACITY_EXHAUSTED)
            )
        )
        assertEquals(ServiceStartDecision.REJECT_KEEP_OLD, capacityExhausted.decision)
        assertEquals(ServiceStartReason.LEDGER_CAPACITY_EXHAUSTED, capacityExhausted.reason)
    }

    // =========================================================================
    // B1a-F15: Android Intent Extraction Boundary
    // =========================================================================

    @Test
    fun b1a_f15_extractCandidate_parsesIntentsAndHandlesNullOrMalformedSafely() {
        val nullCandidate = DirectBootServiceStartPolicy.extractCandidate(null)
        assertTrue(nullCandidate.extrasReadFailed)

        val receiverV1Intent = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 801L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 80_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        }
        val parsedV1 = DirectBootServiceStartPolicy.extractCandidate(receiverV1Intent)
        assertFalse(parsedV1.extrasReadFailed)
        assertEquals(801L, parsedV1.alarmId)
        assertEquals(1, parsedV1.generation)
        assertEquals("", parsedV1.instanceToken)
        assertEquals(0L, parsedV1.restoreEpoch)
        val v1Decision = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = parsedV1
            )
        )
        assertEquals(ServiceStartDecision.LEGACY_COMPATIBILITY_PENDING, v1Decision.decision)

        val v2Identity = DirectBootV2NormalIntentIdentity(
            alarmId = 901L,
            generation = 2,
            instanceToken = "tok_extract_901",
            triggerTime = baseTime + 90_000L,
            restoreEpoch = 3L
        )
        val v2Intent = v2Identity.createIntent(context)
        val parsedV2 = DirectBootServiceStartPolicy.extractCandidate(v2Intent)
        assertFalse(parsedV2.extrasReadFailed)
        assertEquals(901L, parsedV2.alarmId)
        assertEquals(2, parsedV2.generation)
        assertEquals("tok_extract_901", parsedV2.instanceToken)
        assertEquals(3L, parsedV2.restoreEpoch)
        assertEquals(v2Identity.toUriString(), parsedV2.dataUriString)
        assertEquals(v2Identity.computeDigest(), parsedV2.identityDigest)

        val v2Decision = DirectBootServiceStartPolicy.classify(
            ServiceStartClassificationInput(
                active = ActiveServiceSnapshot(isActive = false),
                candidate = parsedV2,
                storage = verifiedClearV2Storage()
            )
        )
        assertEquals(ServiceStartDecision.PREPARED_NEEDS_OWNER, v2Decision.decision)
    }
}
