package com.sysadmindoc.alarmclock.directboot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Deterministic unit tests for [DirectBootRegistrationSingleFlight] under KNO-75 Phase D3-B2-P2-0 / P2-0A.
 *
 * Verifies:
 * - Single execution owner across concurrent requests.
 * - Request coalescing and fresh state evaluation.
 * - Reentrancy immunity without recursion or deadlock.
 * - Safe release on effect exception without owner leakage.
 * - Zero lost requests at the release boundary.
 * - Strict lock segregation ([DirectBootStorageLock.lock] never held during effects).
 * - Non-blocking behavior for non-owners.
 * - Fault boundary request survival: B coalesced before A throws is delivered to C.
 * - No automatic replay of uncertain failed effects.
 * - Bounded coalescing memory and pass budget exhaustion.
 */
class DirectBootRegistrationSingleFlightTest {

    @Test
    fun test01_concurrentRequests_exactlyOneActiveOwner() {
        val effectStartedLatch = CountDownLatch(1)
        val releaseEffectLatch = CountDownLatch(1)
        val activeConcurrentCount = AtomicInteger(0)
        val maxConcurrentCount = AtomicInteger(0)
        val passesExecuted = AtomicInteger(0)

        val singleFlight = DirectBootRegistrationSingleFlight<String>({ context ->
            val current = activeConcurrentCount.incrementAndGet()
            maxConcurrentCount.updateAndGet { maxOf(it, current) }
            passesExecuted.incrementAndGet()

            if (context.passNumber == 1) {
                effectStartedLatch.countDown()
                assertTrue(
                    "Timeout waiting for release latch",
                    releaseEffectLatch.await(5, TimeUnit.SECONDS)
                )
            }
            activeConcurrentCount.decrementAndGet()
            "PassResult-${context.passNumber}"
        })

        val threadAComplete = CountDownLatch(1)
        var resultA: SingleFlightResult<String>? = null
        val threadA = Thread {
            resultA = singleFlight.execute(RegistrationRequest(requestId = "REQ-A", reason = "InitA"))
            threadAComplete.countDown()
        }
        threadA.start()

        assertTrue("Timeout waiting for Thread A to start effect", effectStartedLatch.await(5, TimeUnit.SECONDS))

        val resultB = singleFlight.execute(RegistrationRequest(requestId = "REQ-B", reason = "InitB"))
        val resultC = singleFlight.execute(RegistrationRequest(requestId = "REQ-C", reason = "InitC"))

        assertTrue("Thread B must coalesce", resultB is SingleFlightResult.Coalesced)
        assertFalse("Thread B is not reentrant", (resultB as SingleFlightResult.Coalesced).isReentrant)
        @Suppress("DEPRECATION")
        val expectedOwnerId = threadA.id
        assertEquals("Thread B sees Thread A as owner", expectedOwnerId, resultB.activeOwnerThreadId)

        assertTrue("Thread C must coalesce", resultC is SingleFlightResult.Coalesced)
        assertFalse("Thread C is not reentrant", (resultC as SingleFlightResult.Coalesced).isReentrant)

        releaseEffectLatch.countDown()
        assertTrue("Timeout waiting for Thread A to finish", threadAComplete.await(5, TimeUnit.SECONDS))

        assertNotNull(resultA)
        assertTrue("Thread A result must be Executed", resultA is SingleFlightResult.Executed)
        val executedA = resultA as SingleFlightResult.Executed
        assertEquals("Total passes must be 2", 2, executedA.totalPasses)
        assertEquals("Last result must be from pass 2", "PassResult-2", executedA.lastResult)
        assertEquals("Pass results list size must be 2", listOf("PassResult-1", "PassResult-2"), executedA.allResults)

        assertEquals("Max concurrent effect execution must be exactly 1", 1, maxConcurrentCount.get())
        assertEquals("Total passes executed must be 2", 2, passesExecuted.get())
        assertEquals("Total runs must be 1", 1, singleFlight.totalExecutionRuns)
        assertEquals("Total coalesced must be 2", 2, singleFlight.totalRequestsCoalesced)
        assertFalse("Owner must not be running after completion", singleFlight.isOwnerRunning)
    }

    @Test
    fun test02_reentrantRequestDuringEffect_coalescedWithoutRecursion() {
        var reentrantResult: SingleFlightResult<String>? = null
        val passContexts = mutableListOf<EffectPassContext>()

        lateinit var singleFlight: DirectBootRegistrationSingleFlight<String>
        singleFlight = DirectBootRegistrationSingleFlight({ context ->
            passContexts.add(context)
            if (context.passNumber == 1) {
                reentrantResult = singleFlight.execute(
                    RegistrationRequest(requestId = "REQ-REENTRANT", reason = "NestedTrigger")
                )
            }
            "Result-${context.passNumber}"
        })

        val initialResult = singleFlight.execute(RegistrationRequest(requestId = "REQ-ROOT", reason = "Root"))

        assertNotNull(reentrantResult)
        assertTrue("Reentrant call must be Coalesced", reentrantResult is SingleFlightResult.Coalesced)
        assertTrue("Reentrant flag must be true", (reentrantResult as SingleFlightResult.Coalesced).isReentrant)

        assertTrue("Root execution must be Executed", initialResult is SingleFlightResult.Executed)
        val executed = initialResult as SingleFlightResult.Executed
        assertEquals("Total passes must be 2", 2, executed.totalPasses)
        assertEquals("Final result from pass 2", "Result-2", executed.lastResult)

        assertEquals("Exactly 2 passes recorded", 2, passContexts.size)
        assertEquals("Pass 1 has no coalesced requests", 0, passContexts[0].coalescedRequests.size)
        assertEquals("Pass 2 received the reentrant request", 1, passContexts[1].coalescedRequests.size)
        assertEquals("REQ-REENTRANT", passContexts[1].coalescedRequests[0].requestId)

        assertFalse(singleFlight.isOwnerRunning)
    }

    @Test
    fun test03_repeatedRequests_noParallelEffectExecution() {
        val threadCount = 10
        val barrier = CyclicBarrier(threadCount)
        val activeConcurrency = AtomicInteger(0)
        val maxConcurrency = AtomicInteger(0)
        val totalPasses = AtomicInteger(0)
        val pass1Latch = CountDownLatch(1)

        val singleFlight = DirectBootRegistrationSingleFlight<Int>({ context ->
            val count = activeConcurrency.incrementAndGet()
            maxConcurrency.updateAndGet { maxOf(it, count) }
            val pass = totalPasses.incrementAndGet()

            if (context.passNumber == 1) {
                assertTrue(pass1Latch.await(5, TimeUnit.SECONDS))
            }
            activeConcurrency.decrementAndGet()
            pass
        })

        val threads = (1..threadCount).map { i ->
            Thread {
                barrier.await()
                singleFlight.execute(RegistrationRequest(requestId = "REQ-$i", reason = "Rapid"))
            }
        }

        threads.forEach { it.start() }

        while (activeConcurrency.get() == 0) {
            Thread.yield()
        }
        pass1Latch.countDown()

        threads.forEach { it.join(5000) }

        assertEquals("Max concurrency must strictly be 1", 1, maxConcurrency.get())
        assertTrue("Total passes must be at least 1", totalPasses.get() >= 1)
        assertFalse(singleFlight.isOwnerRunning)
    }

    @Test
    fun test04_queueChangeWhileOwnerActive_latestQueueRecheckRetained() {
        var simulatedQueueState = "Snapshot-V1"
        val observedQueueStates = mutableListOf<String>()

        val pass1Entered = CountDownLatch(1)
        val pass1Release = CountDownLatch(1)

        val singleFlight = DirectBootRegistrationSingleFlight<String>({ context ->
            val currentSnapshot = simulatedQueueState
            observedQueueStates.add(currentSnapshot)

            if (context.passNumber == 1) {
                pass1Entered.countDown()
                assertTrue(pass1Release.await(5, TimeUnit.SECONDS))
            }
            "Processed-$currentSnapshot"
        })

        val ownerThread = Thread {
            singleFlight.execute(RegistrationRequest(requestId = "REQ-1", reason = "Init"))
        }
        ownerThread.start()

        assertTrue(pass1Entered.await(5, TimeUnit.SECONDS))

        simulatedQueueState = "Snapshot-V2"
        val coalescedResult = singleFlight.execute(RegistrationRequest(requestId = "REQ-2", reason = "QueueMutated"))
        assertTrue("Second request coalesces", coalescedResult is SingleFlightResult.Coalesced)

        pass1Release.countDown()
        ownerThread.join(5000)

        assertEquals(2, observedQueueStates.size)
        assertEquals("Pass 1 observed Snapshot-V1", "Snapshot-V1", observedQueueStates[0])
        assertEquals("Pass 2 observed fresh Snapshot-V2", "Snapshot-V2", observedQueueStates[1])
    }

    @Test
    fun test05_exceptionDuringEffect_ownerReleasedSafely() {
        var shouldThrow = true
        val singleFlight = DirectBootRegistrationSingleFlight<String>({ context ->
            if (shouldThrow) {
                throw IllegalStateException("Simulated OS failure during pass ${context.passNumber}")
            }
            "RecoveredPass-${context.passNumber}"
        })

        val failedResult = singleFlight.execute(RegistrationRequest(requestId = "REQ-FAIL", reason = "FailTest"))
        assertTrue("Result must be ExecutionFailed", failedResult is SingleFlightResult.ExecutionFailed)
        val error = (failedResult as SingleFlightResult.ExecutionFailed).error
        assertTrue("Error must be IllegalStateException", error is IllegalStateException)
        assertEquals(0, failedResult.passesCompleted)
        assertFalse("No recheck was pending", failedResult.hasUnresolvedRecheck)

        assertFalse("Owner must be released after exception", singleFlight.isOwnerRunning)
        assertFalse("Pending recheck must be false", singleFlight.hasPendingRecheckSignal)

        shouldThrow = false
        val recoveredResult = singleFlight.execute(RegistrationRequest(requestId = "REQ-OK", reason = "RecoveryTest"))
        assertTrue("Subsequent call must succeed", recoveredResult is SingleFlightResult.Executed)
        val executed = recoveredResult as SingleFlightResult.Executed
        assertEquals(1, executed.totalPasses)
        assertEquals("RecoveredPass-1", executed.lastResult)
        assertFalse(singleFlight.isOwnerRunning)
    }

    @Test
    fun test06_pendingRequestNotLostAtReleaseBoundary() {
        val iterations = 50
        for (i in 1..iterations) {
            val requestsProcessed = ConcurrentLinkedQueue<String>()
            val singleFlight = DirectBootRegistrationSingleFlight<Unit>({ context ->
                requestsProcessed.add(context.initialRequest.requestId)
                context.coalescedRequests.forEach { requestsProcessed.add(it.requestId) }
            })

            val latch = CountDownLatch(2)
            val thread1 = Thread {
                singleFlight.execute(RegistrationRequest(requestId = "ITER-$i-A"))
                latch.countDown()
            }
            val thread2 = Thread {
                singleFlight.execute(RegistrationRequest(requestId = "ITER-$i-B"))
                latch.countDown()
            }

            thread1.start()
            thread2.start()

            assertTrue("Timeout on iteration $i", latch.await(5, TimeUnit.SECONDS))
            thread1.join(1000)
            thread2.join(1000)

            assertTrue(
                "Request A missing in iteration $i",
                requestsProcessed.contains("ITER-$i-A")
            )
            assertTrue(
                "Request B missing in iteration $i",
                requestsProcessed.contains("ITER-$i-B")
            )
            assertFalse(singleFlight.isOwnerRunning)
        }
    }

    @Test
    fun test07_duplicateSameIdentityRequest_coalescesCleanly() {
        val pass1Started = CountDownLatch(1)
        val pass1Release = CountDownLatch(1)
        val passes = AtomicInteger(0)

        val singleFlight = DirectBootRegistrationSingleFlight<String>({ context ->
            passes.incrementAndGet()
            if (context.passNumber == 1) {
                pass1Started.countDown()
                assertTrue(pass1Release.await(5, TimeUnit.SECONDS))
            }
            "Result-${context.passNumber}"
        })

        val threadA = Thread {
            singleFlight.execute(
                RegistrationRequest(requestId = "ALARM-42", identityDigest = "DIGEST-ABC", reason = "First")
            )
        }
        threadA.start()

        assertTrue(pass1Started.await(5, TimeUnit.SECONDS))

        val duplicateResult = singleFlight.execute(
            RegistrationRequest(requestId = "ALARM-42", identityDigest = "DIGEST-ABC", reason = "Duplicate")
        )
        assertTrue("Duplicate request must coalesce", duplicateResult is SingleFlightResult.Coalesced)

        pass1Release.countDown()
        threadA.join(5000)

        assertEquals("Total passes must be 2", 2, passes.get())
        assertFalse(singleFlight.isOwnerRunning)
    }

    @Test
    fun test08_noStorageLockHeldDuringFakeEffect() {
        val effectEntered = CountDownLatch(1)
        val effectRelease = CountDownLatch(1)
        val otherThreadAcquiredStorageLock = AtomicBoolean(false)

        val singleFlight = DirectBootRegistrationSingleFlight<Unit>({
            assertFalse(
                "Storage lock must not be held by effect runner",
                Thread.holdsLock(DirectBootStorageLock.lock)
            )

            effectEntered.countDown()
            assertTrue(effectRelease.await(5, TimeUnit.SECONDS))
        })

        val ownerThread = Thread {
            singleFlight.execute(RegistrationRequest(requestId = "REQ-LOCK-TEST"))
        }
        ownerThread.start()

        assertTrue(effectEntered.await(5, TimeUnit.SECONDS))

        val competitorThread = Thread {
            synchronized(DirectBootStorageLock.lock) {
                otherThreadAcquiredStorageLock.set(true)
            }
        }
        competitorThread.start()
        competitorThread.join(2000)

        assertTrue(
            "Other thread must acquire storage lock without being blocked by effect",
            otherThreadAcquiredStorageLock.get()
        )

        effectRelease.countDown()
        ownerThread.join(5000)
        assertFalse(singleFlight.isOwnerRunning)
    }

    @Test
    fun test09_noDeadlockUnderContention() {
        val threadCount = 16
        val singleFlight = DirectBootRegistrationSingleFlight<Int>({ context ->
            context.passNumber
        })

        val latch = CountDownLatch(threadCount)
        val threads = (1..threadCount).map { id ->
            Thread {
                for (j in 1..20) {
                    singleFlight.execute(RegistrationRequest(requestId = "TH-$id-REQ-$j"))
                }
                latch.countDown()
            }
        }

        threads.forEach { it.start() }
        assertTrue("Execution must complete without deadlock", latch.await(10, TimeUnit.SECONDS))
        threads.forEach { it.join(1000) }

        assertFalse(singleFlight.isOwnerRunning)
        assertTrue("At least one run must have executed", singleFlight.totalExecutionRuns > 0)
        assertTrue("At least one pass must have executed", singleFlight.totalPassesExecuted > 0)
    }

    @Test
    fun test10_staleActorCannotDispatchEffectIndependently() {
        val effectExecutionThreads = ConcurrentLinkedQueue<Thread>()

        val singleFlight = DirectBootRegistrationSingleFlight<Unit>({
            effectExecutionThreads.add(Thread.currentThread())
        })

        val threadA = Thread {
            singleFlight.execute(RegistrationRequest(requestId = "ACTOR-A"))
        }

        threadA.start()
        threadA.join(5000)

        assertEquals("Effect executed by exactly one thread", 1, effectExecutionThreads.size)
        assertEquals("Effect executed solely on Thread A", threadA, effectExecutionThreads.poll())
    }

    @Test
    fun test11_requestValidation() {
        try {
            RegistrationRequest(requestId = "")
            fail("Blank requestId must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("requestId must not be blank"))
        }

        try {
            RegistrationRequest(requestId = "VALID", timestampEpochMs = -1L)
            fail("Negative timestamp must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("timestampEpochMs must be non-negative"))
        }

        try {
            EffectPassContext(passNumber = 0, initialRequest = RegistrationRequest("ID"), coalescedRequests = emptyList())
            fail("passNumber 0 must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("passNumber must be >= 1"))
        }

        try {
            DirectBootRegistrationSingleFlight<Unit>(effectRunner = {}, maxPassesPerRun = 0)
            fail("maxPassesPerRun 0 must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxPassesPerRun must be >= 1"))
        }

        try {
            DirectBootRegistrationSingleFlight<Unit>(effectRunner = {}, maxPendingSampleCapacity = 0)
            fail("maxPendingSampleCapacity 0 must throw")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("maxPendingSampleCapacity must be >= 1"))
        }
    }

    @Test
    fun test12_observableMetricsInspection() {
        val singleFlight = DirectBootRegistrationSingleFlight<String>({ context ->
            "Result-${context.passNumber}"
        })

        assertEquals(0, singleFlight.totalExecutionRuns)
        assertEquals(0, singleFlight.totalPassesExecuted)
        assertEquals(0, singleFlight.totalRequestsCoalesced)
        assertEquals(0, singleFlight.pendingRequestCount)
        assertFalse(singleFlight.isOwnerRunning)
        assertFalse(singleFlight.hasPendingRecheckSignal)

        val result = singleFlight.execute(RegistrationRequest(requestId = "REQ-METRIC"))
        assertTrue(result is SingleFlightResult.Executed)

        assertEquals(1, singleFlight.totalExecutionRuns)
        assertEquals(1, singleFlight.totalPassesExecuted)
        assertEquals(0, singleFlight.totalRequestsCoalesced)
        assertFalse(singleFlight.isOwnerRunning)
    }

    @Test
    fun test13_failureWithPendingRequest_bCoalescedBeforeAThrows_cReconcilesB() {
        // Step 1: A acquires owner.
        // Step 2: B submits request and receives Coalesced.
        // Step 3: A effect throws exception.
        // Step 4: Verify B's recheck request SURVIVED A's failure.
        // Step 5: C subsequently submits request and acquires owner, receiving B in coalescedRequests.

        val pass1Entered = CountDownLatch(1)
        val pass1Release = CountDownLatch(1)
        val passContexts = mutableListOf<EffectPassContext>()

        var shouldFailPass1 = true
        val singleFlight = DirectBootRegistrationSingleFlight<String>({ context ->
            passContexts.add(context)
            if (context.passNumber == 1 && shouldFailPass1) {
                pass1Entered.countDown()
                assertTrue(pass1Release.await(5, TimeUnit.SECONDS))
                throw RuntimeException("Simulated OS failure in A")
            }
            "SuccessPass-${context.passNumber}"
        })

        val threadAComplete = CountDownLatch(1)
        var resultA: SingleFlightResult<String>? = null
        val threadA = Thread {
            resultA = singleFlight.execute(RegistrationRequest(requestId = "REQ-A", reason = "InitA"))
            threadAComplete.countDown()
        }
        threadA.start()

        // Wait until A enters pass 1
        assertTrue(pass1Entered.await(5, TimeUnit.SECONDS))

        // B submits request while A is active -> must coalesce
        val resultB = singleFlight.execute(RegistrationRequest(requestId = "REQ-B", reason = "EnqueuedWhileAFlying"))
        assertTrue("B must receive Coalesced", resultB is SingleFlightResult.Coalesced)

        // Release A to throw exception
        pass1Release.countDown()
        assertTrue(threadAComplete.await(5, TimeUnit.SECONDS))

        // Assert A failed with explicit notification of unresolved recheck
        assertNotNull(resultA)
        assertTrue("A must fail with ExecutionFailed", resultA is SingleFlightResult.ExecutionFailed)
        val failedA = resultA as SingleFlightResult.ExecutionFailed
        assertEquals("Simulated OS failure in A", failedA.error.message)
        assertTrue("A must report hasUnresolvedRecheck = true", failedA.hasUnresolvedRecheck)
        assertEquals("A must report 1 pending recheck", 1, failedA.pendingRecheckCount)

        // Assert single-flight owner is cleanly released, but pending recheck signal SURVIVES
        assertFalse("Owner must not be running after failure", singleFlight.isOwnerRunning)
        assertTrue("Pending recheck signal must survive A failure", singleFlight.hasPendingRecheckSignal)
        assertEquals("Pending request list must retain B", 1, singleFlight.pendingRequestCount)

        // Step 5: C arrives subsequently
        shouldFailPass1 = false
        val resultC = singleFlight.execute(RegistrationRequest(requestId = "REQ-C", reason = "ReconcileC"))

        // Assert C succeeds as new owner and receives B in coalescedRequests
        assertTrue("C must succeed as Executed", resultC is SingleFlightResult.Executed)
        val executedC = resultC as SingleFlightResult.Executed
        assertEquals(1, executedC.totalPasses)

        // Verify C's pass context contained B
        val cPassContext = passContexts.last()
        assertEquals("C pass initial request is REQ-C", "REQ-C", cPassContext.initialRequest.requestId)
        assertEquals("C pass coalesced requests contains REQ-B", 1, cPassContext.coalescedRequests.size)
        assertEquals("REQ-B", cPassContext.coalescedRequests[0].requestId)

        // Verify all rechecks are now fully resolved
        assertFalse("No recheck signal pending after C success", singleFlight.hasPendingRecheckSignal)
        assertEquals("No pending requests remaining", 0, singleFlight.pendingRequestCount)
        assertFalse("Owner released after C", singleFlight.isOwnerRunning)
    }

    @Test
    fun test14_failurePreservesPendingRecheckSignalWhenNoNewCaller() {
        val pass1Entered = CountDownLatch(1)
        val pass1Release = CountDownLatch(1)

        val singleFlight = DirectBootRegistrationSingleFlight<String>({ context ->
            pass1Entered.countDown()
            assertTrue(pass1Release.await(5, TimeUnit.SECONDS))
            throw IllegalStateException("OS crash")
        })

        val threadA = Thread {
            singleFlight.execute(RegistrationRequest(requestId = "REQ-A"))
        }
        threadA.start()

        assertTrue(pass1Entered.await(5, TimeUnit.SECONDS))
        val resultB = singleFlight.execute(RegistrationRequest(requestId = "REQ-B"))
        assertTrue(resultB is SingleFlightResult.Coalesced)

        pass1Release.countDown()
        threadA.join(5000)

        // Verify state without any subsequent caller C
        assertFalse("Owner is released", singleFlight.isOwnerRunning)
        assertTrue("Pending recheck is preserved", singleFlight.hasPendingRecheckSignal)
        assertEquals("Pending recheck counter is 1", 1, singleFlight.totalPendingRecheckCount)
        assertEquals("Pending request sample is 1", 1, singleFlight.pendingRequestCount)
    }

    @Test
    fun test15_noAutomaticReplayOfUncertainEffect() {
        val effectCallCount = AtomicInteger(0)

        val singleFlight = DirectBootRegistrationSingleFlight<String>({
            effectCallCount.incrementAndGet()
            throw RuntimeException("OS State Uncertain")
        })

        val result = singleFlight.execute(RegistrationRequest(requestId = "REQ-UNCERTAIN"))
        assertTrue(result is SingleFlightResult.ExecutionFailed)

        // Must NOT attempt automatic replay in the loop
        assertEquals("Effect must be called exactly once; no automatic retry", 1, effectCallCount.get())
        assertFalse(singleFlight.isOwnerRunning)
    }

    @Test
    fun test16_passBudgetExhaustion_releasesOwnerAndPreservesRecheck() {
        // maxPassesPerRun = 3. On each pass, a reentrant request arrives.
        val passesSeen = mutableListOf<Int>()
        lateinit var singleFlight: DirectBootRegistrationSingleFlight<String>

        singleFlight = DirectBootRegistrationSingleFlight(
            effectRunner = { context ->
                passesSeen.add(context.passNumber)
                // Always queue another recheck to test budget limit
                val coalesced = singleFlight.execute(
                    RegistrationRequest(requestId = "CHAIN-${context.passNumber}")
                )
                assertTrue(coalesced is SingleFlightResult.Coalesced)
                "PassResult-${context.passNumber}"
            },
            maxPassesPerRun = 3
        )

        val result = singleFlight.execute(RegistrationRequest(requestId = "INITIAL-TRIGGER"))

        assertTrue("Result must be PassBudgetExhausted", result is SingleFlightResult.PassBudgetExhausted)
        val exhausted = result as SingleFlightResult.PassBudgetExhausted
        assertEquals("Total passes capped at maxPassesPerRun", 3, exhausted.totalPasses)
        assertEquals("PassResult-3", exhausted.lastResult)
        assertTrue("Pending recheck must be preserved on budget exhaustion", exhausted.pendingRecheckPreserved)
        assertTrue("Pending recheck count > 0", exhausted.pendingRecheckCount >= 1)

        // Owner must be released to prevent starvation
        assertFalse("Owner must be released on budget exhaustion", singleFlight.isOwnerRunning)
        assertTrue("Recheck signal preserved", singleFlight.hasPendingRecheckSignal)
        assertEquals(listOf(1, 2, 3), passesSeen)

        // Next caller immediately acquires owner and continues next run from fresh state
        val nextRunSeen = mutableListOf<Int>()
        val nextSingleFlight = DirectBootRegistrationSingleFlight<String>(
            effectRunner = { context ->
                nextRunSeen.add(context.passNumber)
                "NextRun-${context.passNumber}"
            },
            maxPassesPerRun = 3
        )
        val nextResult = nextSingleFlight.execute(RegistrationRequest(requestId = "NEXT-RUN"))
        assertTrue(nextResult is SingleFlightResult.Executed)
        assertEquals(1, (nextResult as SingleFlightResult.Executed).totalPasses)
    }

    @Test
    fun test17_highVolumeCoalescing_memoryBoundedBySampleCapacity() {
        val sampleCapacity = 16
        val pass1Started = CountDownLatch(1)
        val pass1Release = CountDownLatch(1)
        var receivedCoalescedSampleCount = 0
        var receivedTotalCoalescedCount = 0

        val singleFlight = DirectBootRegistrationSingleFlight<Unit>(
            effectRunner = { context ->
                if (context.passNumber == 1) {
                    pass1Started.countDown()
                    assertTrue(pass1Release.await(5, TimeUnit.SECONDS))
                } else if (context.passNumber == 2) {
                    receivedCoalescedSampleCount = context.coalescedRequests.size
                    receivedTotalCoalescedCount = context.totalCoalescedCount
                }
            },
            maxPassesPerRun = 5,
            maxPendingSampleCapacity = sampleCapacity
        )

        val threadA = Thread {
            singleFlight.execute(RegistrationRequest(requestId = "REQ-A"))
        }
        threadA.start()

        assertTrue(pass1Started.await(5, TimeUnit.SECONDS))

        // Fire 500 concurrent coalesced requests while pass 1 is in-flight
        val floodCount = 500
        for (i in 1..floodCount) {
            val res = singleFlight.execute(RegistrationRequest(requestId = "FLOOD-$i"))
            assertTrue(res is SingleFlightResult.Coalesced)
        }

        // Assert memory buffer never exceeded sampleCapacity
        assertEquals("Sample list strictly bounded by sampleCapacity", sampleCapacity, singleFlight.pendingRequestCount)
        assertEquals("Total pending count accurately records all 500", floodCount, singleFlight.totalPendingRecheckCount)

        pass1Release.countDown()
        threadA.join(5000)

        // Assert pass 2 received bounded sample and full count
        assertEquals("Pass 2 coalesced sample count capped at 16", sampleCapacity, receivedCoalescedSampleCount)
        assertEquals("Pass 2 total coalesced count is 500", floodCount, receivedTotalCoalescedCount)
        assertEquals(0, singleFlight.pendingRequestCount)
        assertFalse(singleFlight.isOwnerRunning)
    }

    @Test
    fun test18_reentrantRequestOnEveryPass_hitsPassBudgetCleanly() {
        val maxPasses = 4
        val executedPasses = AtomicInteger(0)
        lateinit var singleFlight: DirectBootRegistrationSingleFlight<Int>

        singleFlight = DirectBootRegistrationSingleFlight(
            effectRunner = { context ->
                val p = executedPasses.incrementAndGet()
                singleFlight.execute(RegistrationRequest(requestId = "REENTRANT-$p"))
                p
            },
            maxPassesPerRun = maxPasses
        )

        val result = singleFlight.execute(RegistrationRequest(requestId = "START"))
        assertTrue("Must be PassBudgetExhausted", result is SingleFlightResult.PassBudgetExhausted)
        val exhausted = result as SingleFlightResult.PassBudgetExhausted
        assertEquals(maxPasses, exhausted.totalPasses)
        assertEquals(maxPasses, executedPasses.get())
        assertFalse("Owner must be released", singleFlight.isOwnerRunning)
        assertTrue("Recheck signal must survive", singleFlight.hasPendingRecheckSignal)
    }

    @Test
    fun test19_reproduce_cConsumesB_thenThrows_bSurvives_dReconciles() {
        // Step 1: A acquires owner.
        // Step 2: B submits request and receives Coalesced.
        // Step 3: A effect throws exception -> B survives.
        // Step 4: C acquires owner, consumes B into in-flight pass, then throws.
        // Step 5: B must NOT be lost: in-flight recheck is restored to pending state.
        // Step 6: D acquires owner, consumes restored B, and completes successfully.

        val pass1LatchA = CountDownLatch(1)
        val pass1ReleaseA = CountDownLatch(1)

        var shouldThrowInPass = true
        val passContexts = mutableListOf<EffectPassContext>()

        val singleFlight = DirectBootRegistrationSingleFlight<String>({ context ->
            passContexts.add(context)
            if (shouldThrowInPass) {
                if (context.initialRequest.requestId == "REQ-A") {
                    pass1LatchA.countDown()
                    assertTrue(pass1ReleaseA.await(5, TimeUnit.SECONDS))
                    throw RuntimeException("A failed")
                } else if (context.initialRequest.requestId == "REQ-C") {
                    throw RuntimeException("C failed after consuming B")
                }
            }
            "Success-${context.initialRequest.requestId}"
        })

        // Step 1: A becomes owner
        val threadA = Thread {
            singleFlight.execute(RegistrationRequest(requestId = "REQ-A"))
        }
        threadA.start()

        assertTrue(pass1LatchA.await(5, TimeUnit.SECONDS))

        // Step 2: B submits request and coalesces
        val resB = singleFlight.execute(RegistrationRequest(requestId = "REQ-B"))
        assertTrue(resB is SingleFlightResult.Coalesced)

        // Step 3: A throws
        pass1ReleaseA.countDown()
        threadA.join(5000)

        // B survived A's failure
        assertTrue("B survived A failure", singleFlight.hasPendingRecheckSignal)
        assertEquals(1, singleFlight.pendingRequestCount)

        // Step 4: C becomes owner and attempts execution, consuming B
        val resC = singleFlight.execute(RegistrationRequest(requestId = "REQ-C"))
        assertTrue("C must fail", resC is SingleFlightResult.ExecutionFailed)

        // Step 5: Verify whether B's recheck was lost when C threw
        val failedC = resC as SingleFlightResult.ExecutionFailed
        assertTrue("C must report unresolved recheck because B was not settled", failedC.hasUnresolvedRecheck)
        assertEquals(1, failedC.pendingRecheckCount)
        assertTrue("SingleFlight must retain pending recheck signal after C failure", singleFlight.hasPendingRecheckSignal)
        assertEquals("B must be restored to pending requests", 1, singleFlight.pendingRequestCount)
        assertEquals("Total pending count is 1", 1, singleFlight.totalPendingRecheckCount)
        assertFalse("Owner is released after C failure", singleFlight.isOwnerRunning)

        // Step 6: D arrives subsequently, acquires owner, receives restored B, and succeeds
        shouldThrowInPass = false
        val resD = singleFlight.execute(RegistrationRequest(requestId = "REQ-D"))
        assertTrue("D must succeed", resD is SingleFlightResult.Executed)
        val executedD = resD as SingleFlightResult.Executed
        assertEquals(1, executedD.totalPasses)

        val dContext = passContexts.last()
        assertEquals("D pass initial request is REQ-D", "REQ-D", dContext.initialRequest.requestId)
        assertEquals("D pass consumed restored REQ-B", 1, dContext.coalescedRequests.size)
        assertEquals("REQ-B", dContext.coalescedRequests[0].requestId)

        // All rechecks are now completely settled
        assertFalse("No recheck signal pending", singleFlight.hasPendingRecheckSignal)
        assertEquals(0, singleFlight.pendingRequestCount)
        assertEquals(0, singleFlight.totalPendingRecheckCount)
        assertFalse("Owner is released", singleFlight.isOwnerRunning)
    }

    @Test
    fun test20_inFlightFailureWithConcurrentArrivals_bothSurvive() {
        // Step 1: Pre-populate pending queue with B via failing setup owner.
        // Step 2: C starts pass 1, consuming B into in-flight state.
        // Step 3: While C is in-flight, E arrives and coalesces.
        // Step 4: C's pass throws.
        // Step 5: Assert C fails with hasUnresolvedRecheck = true and pendingRecheckCount = 2.
        // Step 6: Assert both B (restored from in-flight) and E (arrived concurrently) survive.
        // Step 7: D arrives, consumes both B and E, and succeeds.

        val pass1EnteredC = CountDownLatch(1)
        val pass1ReleaseC = CountDownLatch(1)
        val dummyLatch = CountDownLatch(1)
        val dummyRelease = CountDownLatch(1)
        val passContexts = mutableListOf<EffectPassContext>()

        var cShouldThrow = true
        var dummyThrows = true

        val sf = DirectBootRegistrationSingleFlight<String>({ context ->
            passContexts.add(context)
            if (context.initialRequest.requestId == "REQ-PRE" && dummyThrows) {
                dummyLatch.countDown()
                assertTrue(dummyRelease.await(5, TimeUnit.SECONDS))
                throw RuntimeException("Pre-crash")
            }
            if (context.initialRequest.requestId == "REQ-C" && cShouldThrow) {
                pass1EnteredC.countDown()
                assertTrue(pass1ReleaseC.await(5, TimeUnit.SECONDS))
                throw RuntimeException("C crash")
            }
            "Success-${context.initialRequest.requestId}"
        })

        // Step 1: Prepopulate with B
        val thPre = Thread { sf.execute(RegistrationRequest(requestId = "REQ-PRE")) }
        thPre.start()
        assertTrue(dummyLatch.await(5, TimeUnit.SECONDS))
        val resB = sf.execute(RegistrationRequest(requestId = "REQ-B"))
        assertTrue(resB is SingleFlightResult.Coalesced)
        dummyRelease.countDown()
        thPre.join(5000)
        assertTrue(sf.hasPendingRecheckSignal)
        assertEquals(1, sf.pendingRequestCount)

        // Step 2: C starts pass 1 on sf
        var resultC: SingleFlightResult<String>? = null
        val thC = Thread {
            resultC = sf.execute(RegistrationRequest(requestId = "REQ-C"))
        }
        thC.start()
        assertTrue(pass1EnteredC.await(5, TimeUnit.SECONDS))

        // While C is in-flight: inFlightRequestCount should be 1 (B)
        assertEquals("B is staged in-flight", 1, sf.inFlightRequestCount)
        assertEquals(0, sf.pendingRequestCount)

        // Step 3: E arrives while C is in-flight
        val resE = sf.execute(RegistrationRequest(requestId = "REQ-E"))
        assertTrue("E must coalesce", resE is SingleFlightResult.Coalesced)
        assertEquals("E is in pendingRequests", 1, sf.pendingRequestCount)
        assertEquals("B is still in-flight", 1, sf.inFlightRequestCount)

        // Step 4: Release C to throw
        pass1ReleaseC.countDown()
        thC.join(5000)

        // Step 5: Assert C failed with typed failure reporting 2 pending rechecks
        assertNotNull(resultC)
        assertTrue(resultC is SingleFlightResult.ExecutionFailed)
        val failedC = resultC as SingleFlightResult.ExecutionFailed
        assertTrue(failedC.hasUnresolvedRecheck)
        assertEquals("Reported pending recheck count is 2 (B + E)", 2, failedC.pendingRecheckCount)

        // Step 6: Assert both B and E survived in pendingRequests
        assertFalse(sf.isOwnerRunning)
        assertTrue(sf.hasPendingRecheckSignal)
        assertEquals("Pending request count is 2", 2, sf.pendingRequestCount)
        assertEquals("Total pending recheck count is 2", 2, sf.totalPendingRecheckCount)

        // Step 7: D arrives subsequently and reconciles both B and E
        cShouldThrow = false
        val resD = sf.execute(RegistrationRequest(requestId = "REQ-D"))
        assertTrue(resD is SingleFlightResult.Executed)

        val dContext = passContexts.last()
        assertEquals("REQ-D", dContext.initialRequest.requestId)
        assertEquals(2, dContext.coalescedRequests.size)
        assertEquals("REQ-B", dContext.coalescedRequests[0].requestId)
        assertEquals("REQ-E", dContext.coalescedRequests[1].requestId)

        assertFalse(sf.hasPendingRecheckSignal)
        assertEquals(0, sf.pendingRequestCount)
    }

    @Test
    fun test21_multiplePassSequenceFailure_settledRequestsCommitted_onlyUnsettledRestored() {
        // Multi-pass scenario:
        // Pass 1: executes REQ-INIT, with pre-existing REQ-B. Pass 1 succeeds -> B is committed as settled!
        // During pass 1, REQ-D arrives and coalesces.
        // Pass 2: starts, consumes REQ-D into in-flight state. Pass 2 throws!
        // Outcome: Only REQ-D is restored to pending state; REQ-B is NOT restored because pass 1 committed it.

        val pass1Entered = CountDownLatch(1)
        val pass1Release = CountDownLatch(1)
        val pass2Entered = CountDownLatch(1)
        val pass2Release = CountDownLatch(1)
        val contextsSeen = mutableListOf<EffectPassContext>()

        // Prepopulate with REQ-B
        val setupLatch = CountDownLatch(1)
        val setupRelease = CountDownLatch(1)
        val sf = DirectBootRegistrationSingleFlight<String>(
            effectRunner = { ctx ->
                contextsSeen.add(ctx)
                if (ctx.initialRequest.requestId == "SETUP") {
                    setupLatch.countDown()
                    assertTrue(setupRelease.await(5, TimeUnit.SECONDS))
                    throw RuntimeException("Setup fail")
                }
                if (ctx.passNumber == 1) {
                    pass1Entered.countDown()
                    assertTrue(pass1Release.await(5, TimeUnit.SECONDS))
                    "Pass1-OK"
                } else if (ctx.passNumber == 2) {
                    pass2Entered.countDown()
                    assertTrue(pass2Release.await(5, TimeUnit.SECONDS))
                    throw RuntimeException("Pass 2 OS failure")
                } else {
                    "PassOther"
                }
            },
            maxPassesPerRun = 5
        )

        // Step 1: Pre-populate B
        val thSetup = Thread { sf.execute(RegistrationRequest(requestId = "SETUP")) }
        thSetup.start()
        assertTrue(setupLatch.await(5, TimeUnit.SECONDS))
        sf.execute(RegistrationRequest(requestId = "REQ-B"))
        setupRelease.countDown()
        thSetup.join(5000)
        assertEquals(1, sf.pendingRequestCount)

        // Step 2: Start multi-pass run with REQ-A
        var resultA: SingleFlightResult<String>? = null
        val thA = Thread {
            resultA = sf.execute(RegistrationRequest(requestId = "REQ-A"))
        }
        thA.start()

        // Wait for pass 1 to enter
        assertTrue(pass1Entered.await(5, TimeUnit.SECONDS))
        // While pass 1 is in-flight, D arrives
        val resD = sf.execute(RegistrationRequest(requestId = "REQ-D"))
        assertTrue(resD is SingleFlightResult.Coalesced)

        // Release pass 1 to succeed
        pass1Release.countDown()

        // Pass 2 will now enter to process D
        assertTrue(pass2Entered.await(5, TimeUnit.SECONDS))
        // Release pass 2 to throw
        pass2Release.countDown()
        thA.join(5000)

        // Step 3: Assert A's outcome
        assertNotNull(resultA)
        assertTrue(resultA is SingleFlightResult.ExecutionFailed)
        val failedA = resultA as SingleFlightResult.ExecutionFailed
        assertEquals("Pass 1 was completed successfully", 1, failedA.passesCompleted)
        assertTrue("Pass 2 failure left unresolved recheck", failedA.hasUnresolvedRecheck)
        assertEquals("Pending recheck count is 1 (only D, not B)", 1, failedA.pendingRecheckCount)

        // Step 4: Verify that only D is pending; B was settled in pass 1
        assertEquals("Only 1 request pending", 1, sf.pendingRequestCount)
        assertEquals("Total pending count is 1", 1, sf.totalPendingRecheckCount)

        // Step 5: Next caller E arrives and reconciles
        val resE = sf.execute(RegistrationRequest(requestId = "REQ-E"))
        assertTrue(resE is SingleFlightResult.Executed)

        val eContext = contextsSeen.last()
        assertEquals("REQ-E", eContext.initialRequest.requestId)
        assertEquals("Only REQ-D is coalesced in E", 1, eContext.coalescedRequests.size)
        assertEquals("REQ-D", eContext.coalescedRequests[0].requestId)
    }

    @Test
    fun test22_highVolumeInFlightRestoration_boundsAndCounterArithmetic() {
        // High-volume test:
        // Capacity = 16.
        // Stage 16 requests into in-flight state.
        // While pass is in flight, 100 new requests arrive and coalesce.
        // Pass throws.
        // Merged state must have:
        // - totalPendingRecheckCount = 116 (exact arithmetic, no double count or loss)
        // - pendingRequestCount = 16 (strictly bounded by maxPendingSampleCapacity)
        // - pendingRequests preserves the first 16 in FIFO order

        val sampleCapacity = 16
        val pass1Entered = CountDownLatch(1)
        val pass1Release = CountDownLatch(1)
        val setupEnter = CountDownLatch(1)
        val setupRel = CountDownLatch(1)
        var setupShouldThrow = true

        val sfMulti = DirectBootRegistrationSingleFlight<String>(
            effectRunner = { ctx ->
                if (setupShouldThrow) {
                    setupEnter.countDown()
                    assertTrue(setupRel.await(5, TimeUnit.SECONDS))
                    throw RuntimeException("Setup crash")
                }
                if (ctx.initialRequest.requestId == "REQ-OWNER") {
                    pass1Entered.countDown()
                    assertTrue(pass1Release.await(5, TimeUnit.SECONDS))
                    throw RuntimeException("Failing pass crash")
                }
                "OK"
            },
            maxPendingSampleCapacity = sampleCapacity
        )

        val thSetup = Thread { sfMulti.execute(RegistrationRequest(requestId = "SETUP")) }
        thSetup.start()
        assertTrue(setupEnter.await(5, TimeUnit.SECONDS))
        // Coalesce 16 requests
        for (i in 1..16) {
            sfMulti.execute(RegistrationRequest(requestId = "BATCH1-$i"))
        }
        setupRel.countDown()
        thSetup.join(5000)

        assertEquals(16, sfMulti.pendingRequestCount)
        assertEquals(16, sfMulti.totalPendingRecheckCount)

        // Now REQ-OWNER starts and consumes the 16 staged requests
        setupShouldThrow = false
        var resultOwner: SingleFlightResult<String>? = null
        val thOwner = Thread {
            resultOwner = sfMulti.execute(RegistrationRequest(requestId = "REQ-OWNER"))
        }
        thOwner.start()
        assertTrue(pass1Entered.await(5, TimeUnit.SECONDS))

        assertEquals("16 staged in-flight", 16, sfMulti.inFlightRequestCount)
        assertEquals("0 in pendingRequests while in flight", 0, sfMulti.pendingRequestCount)

        // While in-flight, 100 new requests arrive
        for (i in 1..100) {
            val res = sfMulti.execute(RegistrationRequest(requestId = "BATCH2-$i"))
            assertTrue(res is SingleFlightResult.Coalesced)
        }

        // Release failing pass
        pass1Release.countDown()
        thOwner.join(5000)

        // Assert failure result
        assertNotNull(resultOwner)
        assertTrue(resultOwner is SingleFlightResult.ExecutionFailed)
        val failed = resultOwner as SingleFlightResult.ExecutionFailed
        assertTrue(failed.hasUnresolvedRecheck)
        assertEquals("16 in-flight + 100 arriving = 116 total", 116, failed.pendingRecheckCount)

        // Assert capacity bound and counter arithmetic
        assertEquals("Sample list capped at sampleCapacity (16)", 16, sfMulti.pendingRequestCount)
        assertEquals("Total counter is exactly 116", 116, sfMulti.totalPendingRecheckCount)
        assertFalse(sfMulti.isOwnerRunning)
    }

    @Test
    fun test23_passBudgetExhaustion_followedByFailureInSubsequentRun_persistsRecheck() {
        // Run 1: Hits maxPassesPerRun (budget exhausted) -> returns PassBudgetExhausted, recheck preserved.
        // Run 2: Caller C arrives, stages preserved recheck, then pass 1 throws -> ExecutionFailed, recheck preserved.
        // Run 3: Caller D arrives, executes pass 1, settles preserved recheck -> Executed.

        val maxPasses = 2
        var runNumber = 0
        lateinit var singleFlight: DirectBootRegistrationSingleFlight<String>
        singleFlight = DirectBootRegistrationSingleFlight(
            effectRunner = { ctx ->
                if (runNumber == 1) {
                    // In run 1, add reentrant request on pass 1 and pass 2 to exhaust budget
                    singleFlight.execute(RegistrationRequest(requestId = "REENTRANT-${ctx.passNumber}"))
                    "Run1-Pass${ctx.passNumber}"
                } else if (runNumber == 2) {
                    throw RuntimeException("Run 2 OS crash")
                } else {
                    "Run3-Success"
                }
            },
            maxPassesPerRun = maxPasses
        )

        // Run 1: Budget exhaustion
        runNumber = 1
        val res1 = singleFlight.execute(RegistrationRequest(requestId = "RUN1-START"))
        assertTrue("Run 1 must exhaust budget", res1 is SingleFlightResult.PassBudgetExhausted)
        val exhausted = res1 as SingleFlightResult.PassBudgetExhausted
        assertEquals(maxPasses, exhausted.totalPasses)
        assertTrue(exhausted.pendingRecheckPreserved)
        assertTrue(singleFlight.hasPendingRecheckSignal)
        assertFalse(singleFlight.isOwnerRunning)

        // Run 2: Failure in subsequent run
        runNumber = 2
        val res2 = singleFlight.execute(RegistrationRequest(requestId = "RUN2-START"))
        assertTrue("Run 2 must fail", res2 is SingleFlightResult.ExecutionFailed)
        val failed2 = res2 as SingleFlightResult.ExecutionFailed
        assertTrue("Run 2 must report unresolved recheck from Run 1", failed2.hasUnresolvedRecheck)
        assertTrue("SingleFlight retains recheck signal after Run 2 failure", singleFlight.hasPendingRecheckSignal)
        assertFalse(singleFlight.isOwnerRunning)

        // Run 3: Reconciliation by D
        runNumber = 3
        val res3 = singleFlight.execute(RegistrationRequest(requestId = "RUN3-START"))
        assertTrue("Run 3 must succeed", res3 is SingleFlightResult.Executed)
        val executed3 = res3 as SingleFlightResult.Executed
        assertEquals(1, executed3.totalPasses)
        assertFalse("Recheck fully settled after Run 3", singleFlight.hasPendingRecheckSignal)
        assertEquals(0, singleFlight.pendingRequestCount)
        assertEquals(0, singleFlight.totalPendingRecheckCount)
    }
}
