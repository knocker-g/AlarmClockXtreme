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
    fun `T01 - Owner instanceToken is NOT mapped as Room incarnation ID`() = runBlocking {
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
        assertTrue(result is CombinedEvidenceResult.Success)
        val success = result as CombinedEvidenceResult.Success

        // Room-side incarnation marker ID MUST NOT be fabricated from owner instanceToken
        assertNull("Room-side incarnation ID must be null", success.markerSnapshot.roomId)
        assertEquals(MarkerState.ABSENT, success.markerSnapshot.roomState)
        // Owner instanceToken remains preserved in Room owners list
        assertEquals("token-authority-42", success.owners.first().instanceToken)
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
        val result1 = (combiner.combineEvidence() as CombinedEvidenceResult.Success).markerSnapshot

        coEvery { roomReader.readRoomEvidence() } returns roomSuccessOrder2
        val result2 = (combiner.combineEvidence() as CombinedEvidenceResult.Success).markerSnapshot

        assertNull(result1.roomId)
        assertNull(result2.roomId)
        assertEquals(result1.roomId, result2.roomId)
        assertEquals(result1.roomState, result2.roomState)
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

        val success = combiner.combineEvidence() as CombinedEvidenceResult.Success
        // ENFORCED authority state must NOT be reinterpreted as Room handshake COMPLETE
        assertEquals(CompletionState.UNKNOWN, success.markerSnapshot.roomCompletion)
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
        assertTrue("Zero owners with PREPARING meta must succeed without corruption", result is CombinedEvidenceResult.Success)
        val success = result as CombinedEvidenceResult.Success
        assertNotEquals(MarkerState.CORRUPT, success.markerSnapshot.roomState)
        assertEquals(MarkerState.ABSENT, success.markerSnapshot.roomState)
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

        val success = combiner.combineEvidence() as CombinedEvidenceResult.Success
        assertEquals(validMeta, success.meta)
        assertEquals(listOf(validOwner), success.owners)
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

        val success = combiner.combineEvidence() as CombinedEvidenceResult.Success
        assertEquals(MarkerState.PRESENT_VALID, success.markerSnapshot.localState)
        assertEquals("marker-local-777", success.markerSnapshot.localId)
        assertEquals(CompletionState.PREPARING, success.markerSnapshot.localCompletion)
    }

    @Test
    fun `T08 - Cross-storage coherence remains UNPROVEN`() = runBlocking {
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

        val success = combiner.combineEvidence() as CombinedEvidenceResult.Success
        val rawEvidence = combiner.toRawUnprovenEvidence(success)

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

        val success = combiner.combineEvidence() as CombinedEvidenceResult.Success
        assertNull(success.markerSnapshot.roomId)
        assertNull(success.markerSnapshot.localId)

        val raw = combiner.toRawUnprovenEvidence(success)
        assertNull(raw.operationIdentity.epoch)
        assertNull(raw.operationIdentity.token)
    }

    @Test
    fun `T10 - RoomMissing classification produces RESUME_ROOM_FIRST when combined with Local evidence`() = runBlocking {
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = validMeta,
            owners = listOf(validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "marker-local-100",
            localCompletion = CompletionState.PREPARING
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns localSuccess

        val success = combiner.combineEvidence() as CombinedEvidenceResult.Success
        assertEquals(IncarnationClassificationOutcome.ROOM_MISSING, IncarnationEvidenceClassifier.classify(success.markerSnapshot))

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
