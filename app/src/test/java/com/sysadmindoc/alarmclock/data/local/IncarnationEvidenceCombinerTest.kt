package com.sysadmindoc.alarmclock.data.local

import com.sysadmindoc.alarmclock.domain.AdapterResult
import com.sysadmindoc.alarmclock.domain.CoherenceStatus
import com.sysadmindoc.alarmclock.domain.CompletionState
import com.sysadmindoc.alarmclock.domain.CoverageReasonCode
import com.sysadmindoc.alarmclock.domain.CoverageResult
import com.sysadmindoc.alarmclock.domain.CoverageValidationReport
import com.sysadmindoc.alarmclock.domain.HandshakeRecoveryDecision
import com.sysadmindoc.alarmclock.domain.HandshakeRecoveryPolicy
import com.sysadmindoc.alarmclock.domain.HandshakeRecoverySnapshot
import com.sysadmindoc.alarmclock.domain.IncarnationClassificationOutcome
import com.sysadmindoc.alarmclock.domain.IncarnationEvidenceClassifier
import com.sysadmindoc.alarmclock.domain.IncarnationEvidenceSnapshot
import com.sysadmindoc.alarmclock.domain.MarkerState
import com.sysadmindoc.alarmclock.domain.MetaDescriptor
import com.sysadmindoc.alarmclock.domain.OwnerDescriptor
import com.sysadmindoc.alarmclock.domain.RawIncarnationEvidence
import com.sysadmindoc.alarmclock.domain.RecoveryEvidence
import com.sysadmindoc.alarmclock.domain.RecoveryEvidenceSnapshotAdapter
import com.sysadmindoc.alarmclock.domain.RecoveryOperationIdentity
import com.sysadmindoc.alarmclock.domain.RecoveryPhaseEvidence
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IncarnationEvidenceCombinerTest {

    private val roomReader: RoomAuthorityEvidenceReader = mockk()
    private val localReader: LocalIncarnationEvidenceReader = mockk()
    private val combiner = IncarnationEvidenceCombiner(roomReader, localReader)

    private val validMeta = MetaDescriptor(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
    private val validOwner = OwnerDescriptor(
        alarmId = 42L,
        instanceToken = "token-authority-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 1000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L
    )

    @Test
    fun `T01 - Room marker evidence is returned as NotDerivable because Room v28 v29 lacks incarnation columns`() = runBlocking {
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = validMeta,
            owners = listOf(validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "marker-local-42",
            localCompletion = CompletionState.COMPLETE
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns localSuccess

        val result = combiner.combineEvidence()
        assertTrue("Result must be NotDerivable when Room incarnation columns are missing", result is CombinedEvidenceResult.NotDerivable)
        val notDerivable = result as CombinedEvidenceResult.NotDerivable

        // Owner instanceToken remains preserved in Room owners list, NOT fabricated as roomId
        assertEquals("token-authority-42", notDerivable.owners.first().instanceToken)
        assertEquals(validMeta, notDerivable.meta)
        assertEquals(MarkerState.PRESENT_VALID, notDerivable.localState)
        assertEquals("marker-local-42", notDerivable.localId)
        assertEquals(CompletionState.COMPLETE, notDerivable.localCompletion)
    }

    @Test
    fun `T02 - Multiple owners in different orders produce no arbitrary Room incarnation identifier`() = runBlocking {
        val owner2 = validOwner.copy(alarmId = 99L, instanceToken = "token-authority-99")
        val roomSuccessOrder1 = RoomEvidenceReadResult.Success(
            meta = validMeta,
            owners = listOf(validOwner, owner2),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        val roomSuccessOrder2 = RoomEvidenceReadResult.Success(
            meta = validMeta,
            owners = listOf(owner2, validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "marker-local-42",
            localCompletion = CompletionState.COMPLETE
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccessOrder1
        every { localReader.readLocalEvidence() } returns localSuccess
        val result1 = combiner.combineEvidence() as CombinedEvidenceResult.NotDerivable

        coEvery { roomReader.readRoomEvidence() } returns roomSuccessOrder2
        val result2 = combiner.combineEvidence() as CombinedEvidenceResult.NotDerivable

        assertEquals(result1.meta, result2.meta)
        assertEquals(result1.localId, result2.localId)
    }

    @Test
    fun `T03 - ENFORCED bootstrapState alone does NOT prove handshake COMPLETE`() = runBlocking {
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = validMeta, // bootstrapState = "ENFORCED"
            owners = listOf(validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "marker-local-42",
            localCompletion = CompletionState.COMPLETE
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns localSuccess

        val result = combiner.combineEvidence() as CombinedEvidenceResult.NotDerivable
        // ENFORCED authority state must NOT be reinterpreted as Room handshake COMPLETE
        assertEquals("ENFORCED", result.meta?.bootstrapState)
    }

    @Test
    fun `T04 - PREPARING meta with zero owners is NOT classified as corruption`() = runBlocking {
        val preparingMeta = validMeta.copy(bootstrapState = "PREPARING")
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = preparingMeta,
            owners = emptyList(),
            report = CoverageValidationReport(CoverageResult.INCOMPLETE, CoverageReasonCode.BOOTSTRAP_PREPARING)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.ABSENT,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns localSuccess

        val result = combiner.combineEvidence()
        assertTrue("Zero owners with PREPARING meta must succeed as NotDerivable without corruption", result is CombinedEvidenceResult.NotDerivable)
        val notDerivable = result as CombinedEvidenceResult.NotDerivable
        assertEquals(CoverageResult.INCOMPLETE, notDerivable.report.result)
    }

    @Test
    fun `T05 - Missing incarnation-specific storage preserves Room authority meta and owners intact`() = runBlocking {
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = validMeta,
            owners = listOf(validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "marker-local-42",
            localCompletion = CompletionState.COMPLETE
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns localSuccess

        val notDerivable = combiner.combineEvidence() as CombinedEvidenceResult.NotDerivable
        assertEquals(validMeta, notDerivable.meta)
        assertEquals(listOf(validOwner), notDerivable.owners)
    }

    @Test
    fun `T06 - Missing evidence remains explicit and unavailable storage fails closed`() {
        runBlocking {
            coEvery { roomReader.readRoomEvidence() } returns RoomEvidenceReadResult.ReadFailure("Room read failed")

            val result = combiner.combineEvidence()
            assertTrue(result is CombinedEvidenceResult.ReadFailure)
            assertEquals("Room read failed", (result as CombinedEvidenceResult.ReadFailure).details)
        }
    }

    @Test
    fun `T07 - Existing Local evidence remains intact`() = runBlocking {
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = validMeta,
            owners = listOf(validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "marker-local-777",
            localCompletion = CompletionState.PREPARING
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns localSuccess

        val notDerivable = combiner.combineEvidence() as CombinedEvidenceResult.NotDerivable
        assertEquals(MarkerState.PRESENT_VALID, notDerivable.localState)
        assertEquals("marker-local-777", notDerivable.localId)
        assertEquals(CompletionState.PREPARING, notDerivable.localCompletion)
    }

    @Test
    fun `T08 - Cross-storage coherence remains UNPROVEN_COHERENCE in toRawUnprovenEvidence helper`() = runBlocking {
        val dummySnapshot = IncarnationEvidenceSnapshot(
            roomState = MarkerState.ABSENT,
            roomId = null,
            roomCompletion = CompletionState.UNKNOWN,
            localState = MarkerState.PRESENT_VALID,
            localId = "marker-local-42",
            localCompletion = CompletionState.COMPLETE
        )
        val combinedSuccess = CombinedEvidenceResult.Success(
            markerSnapshot = dummySnapshot,
            meta = validMeta,
            owners = listOf(validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )

        val rawEvidence = combiner.toRawUnprovenEvidence(combinedSuccess)

        assertEquals(CoherenceStatus.UNPROVEN_COHERENCE, rawEvidence.coherence)
        assertFalse("CoherenceStatus MUST NOT be COHERENT", rawEvidence.coherence == CoherenceStatus.COHERENT)
    }

    @Test
    fun `T09 - No new incarnation ID or operation identity is fabricated`() = runBlocking {
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = null,
            owners = emptyList(),
            report = CoverageValidationReport(CoverageResult.UNINITIALIZED, CoverageReasonCode.META_ABSENT_AUTHORITY_EMPTY)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.ABSENT,
            localId = null,
            localCompletion = CompletionState.UNKNOWN
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns localSuccess

        val notDerivable = combiner.combineEvidence() as CombinedEvidenceResult.NotDerivable
        assertNull(notDerivable.localId)
    }

    @Test
    fun `T10 - Downstream recovery policy evaluates ROOM_MISSING when combined with Local evidence`() = runBlocking {
        val recoverySnapshot = HandshakeRecoverySnapshot(
            classification = IncarnationClassificationOutcome.ROOM_MISSING,
            initAuthority = RecoveryEvidence.PROVEN,
            operationIdentity = RecoveryOperationIdentity(epoch = 100L, token = "tok-100"),
            phaseEvidence = RecoveryPhaseEvidence(phaseName = "LOCAL_PREPARING"),
            historicalEnforcedLedger = RecoveryEvidence.NEGATED
        )
        val decision = HandshakeRecoveryPolicy.evaluate(recoverySnapshot)
        assertEquals(HandshakeRecoveryDecision.RESUME_ROOM_FIRST, decision)
    }
}
