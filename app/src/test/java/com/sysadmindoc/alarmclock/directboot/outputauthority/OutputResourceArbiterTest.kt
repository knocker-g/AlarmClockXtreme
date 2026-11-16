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
        val (acquired2, preempted) = arbiter.acquirePending(key2, OutputSinkType.ALARM_SERVICE_1001, "nonce-2")

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
}
