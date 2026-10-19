package com.sysadmindoc.alarmclock.data.local

import com.sysadmindoc.alarmclock.domain.CompletionState
import com.sysadmindoc.alarmclock.domain.MarkerState
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalIncarnationEvidenceReaderTest {

    private val markerStore: LocalIncarnationMarkerStore = mockk()
    private val reader = LocalIncarnationEvidenceReader(markerStore)

    @Test
    fun `Present PREPARING maps to PRESENT_VALID with PREPARING completion`() {
        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "token-preparing-100",
            completionState = CompletionState.PREPARING
        )
        every { markerStore.readMarker() } returns LocalMarkerReadResult.Present(payload)

        val result = reader.readLocalEvidence()
        assertTrue(result is LocalEvidenceReadResult.Success)
        val success = result as LocalEvidenceReadResult.Success

        assertEquals(MarkerState.PRESENT_VALID, success.localState)
        assertEquals("token-preparing-100", success.localId)
        assertEquals(CompletionState.PREPARING, success.localCompletion)
    }

    @Test
    fun `Present COMPLETE maps to PRESENT_VALID with COMPLETE completion`() {
        val payload = LocalMarkerPayload(
            formatVersion = 1,
            incarnationId = "token-complete-200",
            completionState = CompletionState.COMPLETE
        )
        every { markerStore.readMarker() } returns LocalMarkerReadResult.Present(payload)

        val result = reader.readLocalEvidence()
        assertTrue(result is LocalEvidenceReadResult.Success)
        val success = result as LocalEvidenceReadResult.Success

        assertEquals(MarkerState.PRESENT_VALID, success.localState)
        assertEquals("token-complete-200", success.localId)
        assertEquals(CompletionState.COMPLETE, success.localCompletion)
    }

    @Test
    fun `Explicit ABSENT maps to ABSENT with null ID and UNKNOWN completion`() {
        every { markerStore.readMarker() } returns LocalMarkerReadResult.Absent

        val result = reader.readLocalEvidence()
        assertTrue(result is LocalEvidenceReadResult.Success)
        val success = result as LocalEvidenceReadResult.Success

        assertEquals(MarkerState.ABSENT, success.localState)
        assertNull(success.localId)
        assertEquals(CompletionState.UNKNOWN, success.localCompletion)
    }

    @Test
    fun `CORRUPT maps to CORRUPT with null ID and UNKNOWN completion`() {
        every { markerStore.readMarker() } returns LocalMarkerReadResult.Corrupt

        val result = reader.readLocalEvidence()
        assertTrue(result is LocalEvidenceReadResult.Success)
        val success = result as LocalEvidenceReadResult.Success

        assertEquals(MarkerState.CORRUPT, success.localState)
        assertNull(success.localId)
        assertEquals(CompletionState.UNKNOWN, success.localCompletion)
    }

    @Test
    fun `ReadFailure maps to READ_FAILED with null ID and UNKNOWN completion`() {
        every { markerStore.readMarker() } returns LocalMarkerReadResult.ReadFailure

        val result = reader.readLocalEvidence()
        assertTrue(result is LocalEvidenceReadResult.Success)
        val success = result as LocalEvidenceReadResult.Success

        assertEquals(MarkerState.READ_FAILED, success.localState)
        assertNull(success.localId)
        assertEquals(CompletionState.UNKNOWN, success.localCompletion)
    }

    @Test
    fun `Unavailable maps to StorageUnavailable`() {
        every { markerStore.readMarker() } returns LocalMarkerReadResult.Unavailable

        val result = reader.readLocalEvidence()
        assertEquals(LocalEvidenceReadResult.StorageUnavailable, result)
    }
}
