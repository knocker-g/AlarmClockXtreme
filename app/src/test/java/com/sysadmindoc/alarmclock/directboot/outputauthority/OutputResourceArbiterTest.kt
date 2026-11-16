package com.sysadmindoc.alarmclock.directboot.outputauthority

import com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OutputResourceArbiterTest {

    private lateinit var arbiter: OutputResourceArbiter

    @Before
    fun setUp() {
        arbiter = OutputResourceArbiter()
    }

    @Test
    fun testAcquirePending_singleOwnerSuccess() {
        val key = OutputOccurrenceKey(1L, 1000L, 0)
        val (acquired, preempted) = arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-1")
        assertTrue(acquired)
        assertNull(preempted)
        assertEquals(key, arbiter.activeHandle?.key)
        assertEquals("nonce-1", arbiter.activeHandle?.activeNonce)
    }

    @Test
    fun testAcquirePending_rejectsBlankNonce() {
        val key = OutputOccurrenceKey(1L, 1000L, 0)
        val (acquired, preempted) = arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "  ")
        assertFalse(acquired)
        assertNull(preempted)
        assertNull(arbiter.activeHandle)
    }

    @Test
    fun testAcquirePending_idempotentSameOccurrenceAndNonce() {
        val key = OutputOccurrenceKey(1L, 1000L, 0)
        arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-1")
        val (acquired2, preempted2) = arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-1")
        assertTrue(acquired2)
        assertNull(preempted2)
        assertEquals(1L, arbiter.activeHandle?.key?.alarmId)
    }

    @Test
    fun testAcquirePending_rejectsStaleNonceForSameOccurrence() {
        val key = OutputOccurrenceKey(1L, 1000L, 0)
        arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-1")
        val (acquired2, preempted2) = arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-old")
        assertFalse(acquired2)
        assertNull(preempted2)
        assertEquals("nonce-1", arbiter.activeHandle?.activeNonce)
    }

    @Test
    fun testPreemption_returnsPreemptedHandleWithoutHoldingLocks() {
        val key1 = OutputOccurrenceKey(1L, 1000L, 0)
        val key2 = OutputOccurrenceKey(2L, 2000L, 0)

        arbiter.acquirePending(key1, OutputSinkType.ALARM_SERVICE_1001, "nonce-1")
        val (acquired2, preempted) = arbiter.acquirePending(key2, OutputSinkType.ALARM_SERVICE_1001, "nonce-2") { true }

        assertTrue(acquired2)
        assertNotNull(preempted)
        assertEquals(key1, preempted?.key)
        assertEquals("nonce-1", preempted?.activeNonce)
        assertEquals(key2, arbiter.activeHandle?.key)
        assertEquals("nonce-2", arbiter.activeHandle?.activeNonce)
        assertEquals(1, arbiter.preemptedHandles.size)
        assertEquals(key1, arbiter.preemptedHandles.first().key)
    }

    @Test
    fun testConfirmObserved_requiresMatchingKeyAndNonce() {
        val key = OutputOccurrenceKey(1L, 1000L, 0)
        arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-1")

        assertFalse(arbiter.confirmObserved(key, "wrong-nonce"))
        assertFalse(arbiter.confirmObserved(OutputOccurrenceKey(2L, 2000L, 0), "nonce-1"))
        assertTrue(arbiter.confirmObserved(key, "nonce-1"))
    }

    @Test
    fun testReleaseIfMatches_releasesOnlyMatchingKeyAndNonce() {
        val key = OutputOccurrenceKey(1L, 1000L, 0)
        arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-1")

        // Nonce mismatch leaves active handle intact
        assertFalse(arbiter.releaseIfMatches(key, "wrong-nonce"))
        assertNotNull(arbiter.activeHandle)

        // Matching release clears active handle
        assertTrue(arbiter.releaseIfMatches(key, "nonce-1"))
        assertNull(arbiter.activeHandle)
    }

    // =========================================================================
    // S5-A1 Safety Corrections: Preemption Stop Confirmation & Stale Handle
    // =========================================================================

    @Test
    fun testPreemption_requiresConfirmedStopBeforeAuthorizingNewOwner() {
        val key1 = OutputOccurrenceKey(1L, 1000L, 0)
        val key2 = OutputOccurrenceKey(2L, 2000L, 0)
        val (acq1, _) = arbiter.acquirePending(key1, OutputSinkType.ALARM_SERVICE_1001, "nonce-1")
        assertTrue(acq1)

        // Preemption where stop confirmation returns false must NOT acquire new owner
        val (acq2, preempted) = arbiter.acquirePending(
            key = key2,
            sink = OutputSinkType.FULLSCREEN_FALLBACK_1001,
            activeNonce = "nonce-2",
            stopPreemptedOwner = { handle ->
                assertEquals(key1, handle.key)
                false // Stop failed!
            }
        )
        assertFalse("New owner must not be acquired when stop confirmation fails", acq2)
        assertEquals("Prior owner must remain active handle", key1, arbiter.activeHandle?.key)
        assertEquals("nonce-1", arbiter.activeHandle?.activeNonce)
    }

    @Test
    fun testRequestStop_rejectsStaleHandle() {
        val key = OutputOccurrenceKey(1L, 1000L, 0)
        arbiter.acquirePending(key, OutputSinkType.ALARM_SERVICE_1001, "nonce-active")

        val staleHandle = ActiveOutputHandle(
            key = key,
            sink = OutputSinkType.ALARM_SERVICE_1001,
            activeNonce = "nonce-stale"
        )
        val stopped = arbiter.requestStop(staleHandle)
        assertFalse("Stale handle stop request must be rejected", stopped)
        assertEquals("Active handle must not be cleared by stale stop", key, arbiter.activeHandle?.key)
    }
}
