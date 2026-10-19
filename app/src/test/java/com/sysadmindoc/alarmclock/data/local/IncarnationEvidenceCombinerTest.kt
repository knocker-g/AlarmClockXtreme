package com.sysadmindoc.alarmclock.data.local

import com.sysadmindoc.alarmclock.domain.CoherenceStatus
import com.sysadmindoc.alarmclock.domain.CompletionState
import com.sysadmindoc.alarmclock.domain.CoverageReasonCode
import com.sysadmindoc.alarmclock.domain.CoverageResult
import com.sysadmindoc.alarmclock.domain.CoverageValidationReport
import com.sysadmindoc.alarmclock.domain.MarkerState
import com.sysadmindoc.alarmclock.domain.MetaDescriptor
import com.sysadmindoc.alarmclock.domain.OwnerDescriptor
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncarnationEvidenceCombinerTest {

    private val roomReader: RoomAuthorityEvidenceReader = mockk()
    private val localReader: LocalIncarnationEvidenceReader = mockk()
    private val combiner = IncarnationEvidenceCombiner(roomReader, localReader)

    private val validMeta = MetaDescriptor(id = 1, restoreEpoch = 100L, bootstrapState = "ENFORCED")
    private val validOwner = OwnerDescriptor(
        alarmId = 42L,
        instanceToken = "token-comb-42",
        definitionRevision = 1L,
        scheduleRevision = 1L,
        lifecycle = "LIVE",
        desiredOperation = "SET",
        desiredTriggerMillis = 1000L,
        reconcileState = "SETTLED",
        restoreEpoch = 100L
    )

    @Test
    fun `combineEvidence combines Room and Local evidence without declaring COHERENT`() = runBlocking {
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = validMeta,
            owners = listOf(validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        val localSuccess = LocalEvidenceReadResult.Success(
            localState = MarkerState.PRESENT_VALID,
            localId = "token-comb-42",
            localCompletion = CompletionState.COMPLETE
        )

        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns localSuccess

        val result = combiner.combineEvidence()
        assertTrue(result is CombinedEvidenceResult.Success)
        val success = result as CombinedEvidenceResult.Success

        assertEquals(MarkerState.PRESENT_VALID, success.markerSnapshot.roomState)
        assertEquals("token-comb-42", success.markerSnapshot.roomId)
        assertEquals(CompletionState.COMPLETE, success.markerSnapshot.roomCompletion)

        assertEquals(MarkerState.PRESENT_VALID, success.markerSnapshot.localState)
        assertEquals("token-comb-42", success.markerSnapshot.localId)
        assertEquals(CompletionState.COMPLETE, success.markerSnapshot.localCompletion)

        // Convert to RawIncarnationEvidence and verify COHERENT IS NEVER DECLARED
        val rawEvidence = combiner.toRawUnprovenEvidence(success)
        assertEquals(CoherenceStatus.UNPROVEN_COHERENCE, rawEvidence.coherence)
        assertFalse("CoherenceStatus MUST NOT be COHERENT", rawEvidence.coherence == CoherenceStatus.COHERENT)
    }

    @Test
    fun `combineEvidence handles Room read failure cleanly`() = runBlocking {
        coEvery { roomReader.readRoomEvidence() } returns RoomEvidenceReadResult.ReadFailure("Room read failed")

        val result = combiner.combineEvidence()
        assertTrue(result is CombinedEvidenceResult.ReadFailure)
        assertEquals("Room read failed", (result as CombinedEvidenceResult.ReadFailure).details)
    }

    @Test
    fun `combineEvidence handles Local storage unavailable cleanly`() = runBlocking {
        val roomSuccess = RoomEvidenceReadResult.Success(
            meta = validMeta,
            owners = listOf(validOwner),
            report = CoverageValidationReport(CoverageResult.COMPLETE, CoverageReasonCode.COVERAGE_VALID_COMPLETE)
        )
        coEvery { roomReader.readRoomEvidence() } returns roomSuccess
        every { localReader.readLocalEvidence() } returns LocalEvidenceReadResult.StorageUnavailable

        val result = combiner.combineEvidence()
        assertTrue(result is CombinedEvidenceResult.StorageUnavailable)
        assertTrue((result as CombinedEvidenceResult.StorageUnavailable).details.contains("unavailable"))
    }
}
