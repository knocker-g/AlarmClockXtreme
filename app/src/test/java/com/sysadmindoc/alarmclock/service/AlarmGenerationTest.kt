package com.sysadmindoc.alarmclock.service

import android.content.Intent
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AlarmGenerationTest {

    @Before
    fun setUp() {
        AlarmServiceCallbackPolicy.resetState()
    }

    // --- Intent Parsing Tests ---

    @Test
    fun fromActionIntentRequiresExplicitFireIdAndScheduledAt() {
        val validActionIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }
        val gen = AlarmGeneration.fromActionIntent(validActionIntent)
        assertNotNull(gen)
        assertEquals(42L, gen?.alarmId)
        assertEquals(1000L, gen?.scheduledAt)
        assertEquals("fire-42", gen?.fireId)

        // Missing EXTRA_ALARM_FIRE_ID -> fails closed (null) for actions
        val missingFireIdIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
        }
        assertNull(AlarmGeneration.fromActionIntent(missingFireIdIntent))

        // Blank EXTRA_ALARM_FIRE_ID -> fails closed (null) for actions
        val blankFireIdIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "   ")
        }
        assertNull(AlarmGeneration.fromActionIntent(blankFireIdIntent))
    }

    @Test
    fun fromStartIntentAutoCompletesMissingFireIdForProducerCompatibility() {
        val startIntentWithFireId = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-42")
        }
        val genWithFireId = AlarmGeneration.fromStartIntent(startIntentWithFireId)
        assertNotNull(genWithFireId)
        assertEquals("fire-42", genWithFireId?.fireId)

        val startIntentWithoutFireId = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
        }
        val genAutoFireId = AlarmGeneration.fromStartIntent(startIntentWithoutFireId)
        assertNotNull(genAutoFireId)
        assertEquals(AlarmIncidentEvent.fireIdFor(42L, 1000L), genAutoFireId?.fireId)
    }

    @Test
    fun fromStartIntentReturnsNullWhenIdOrScheduledAtMissing() {
        val invalidIdIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
        }
        assertNull(AlarmGeneration.fromStartIntent(invalidIdIntent))

        val invalidScheduledAtIntent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 42L)
        }
        assertNull(AlarmGeneration.fromStartIntent(invalidScheduledAtIntent))
    }

    @Test
    fun matchesIdentifiesExactSameGeneration() {
        val gen1 = AlarmGeneration(42L, 1000L, "fire-42")
        val gen2 = AlarmGeneration(42L, 1000L, "fire-42")
        val genDifferentScheduledAt = AlarmGeneration(42L, 2000L, "fire-42")
        val genDifferentFireId = AlarmGeneration(42L, 1000L, "fire-42-b")
        val genDifferentAlarmId = AlarmGeneration(99L, 1000L, "fire-42")

        assertTrue(gen1.matches(gen2))
        assertFalse(gen1.matches(genDifferentScheduledAt))
        assertFalse(gen1.matches(genDifferentFireId))
        assertFalse(gen1.matches(genDifferentAlarmId))
        assertFalse(gen1.matches(null))
    }

    // --- Production Admission Policy Classifier Tests (12 Matrix Cases) ---

    @Test
    fun policyClassifiesFirstTimeStart() {
        val incoming = AlarmGeneration(42L, 1000L, "fire-42")
        val decision = AlarmServiceAdmissionPolicy.classifyStart(current = null, incoming = incoming)
        assertEquals(StartAdmissionDecision.ADMIT_NEW, decision)
    }

    @Test
    fun policyClassifiesDuplicateIdenticalStart() {
        val current = AlarmGeneration(42L, 1000L, "fire-42")
        val duplicate = AlarmGeneration(42L, 1000L, "fire-42")
        val decision = AlarmServiceAdmissionPolicy.classifyStart(current = current, incoming = duplicate)
        assertEquals(StartAdmissionDecision.DUPLICATE_IDEMPOTENT, decision)
    }

    @Test
    fun policyClassifiesStaleStart() {
        val current = AlarmGeneration(42L, 2000L, "fire-42-new")
        val stale = AlarmGeneration(42L, 1000L, "fire-42-old")
        val decision = AlarmServiceAdmissionPolicy.classifyStart(current = current, incoming = stale)
        assertEquals(StartAdmissionDecision.REJECT_STALE, decision)
    }

    @Test
    fun policyClassifiesConflictingStartSameScheduledAtDifferentFireId() {
        val current = AlarmGeneration(42L, 1000L, "fire-42-original")
        val conflicting = AlarmGeneration(42L, 1000L, "fire-42-conflicting")
        val decision = AlarmServiceAdmissionPolicy.classifyStart(current = current, incoming = conflicting)
        assertEquals(StartAdmissionDecision.REJECT_CONFLICTING, decision)
    }

    @Test
    fun policyClassifiesValidNewGenerationStart() {
        val current = AlarmGeneration(42L, 1000L, "fire-42")
        val newGen = AlarmGeneration(42L, 2000L, "fire-42-next")
        val decision = AlarmServiceAdmissionPolicy.classifyStart(current = current, incoming = newGen)
        assertEquals(StartAdmissionDecision.ADMIT_NEW, decision)

        val differentAlarm = AlarmGeneration(99L, 1000L, "fire-99")
        val decisionDifferent = AlarmServiceAdmissionPolicy.classifyStart(current = current, incoming = differentAlarm)
        assertEquals(StartAdmissionDecision.ADMIT_NEW, decisionDifferent)
    }

    @Test
    fun policyClassifiesExactMatchSnooze() {
        val current = AlarmGeneration(42L, 1000L, "fire-42")
        val snooze = AlarmGeneration(42L, 1000L, "fire-42")
        val decision = AlarmServiceAdmissionPolicy.classifyAction(
            current = current,
            incoming = snooze,
            isRestart = false,
            activeEvidence = null
        )
        assertEquals(ActionAdmissionDecision.ADMIT_EXACT_MATCH, decision)
    }

    @Test
    fun policyClassifiesStaleSnooze() {
        val current = AlarmGeneration(42L, 2000L, "fire-42-new")
        val staleSnooze = AlarmGeneration(42L, 1000L, "fire-42-old")
        val decision = AlarmServiceAdmissionPolicy.classifyAction(
            current = current,
            incoming = staleSnooze,
            isRestart = false,
            activeEvidence = null
        )
        assertEquals(ActionAdmissionDecision.REJECT_STALE, decision)
    }

    @Test
    fun policyClassifiesExactMatchDismiss() {
        val current = AlarmGeneration(42L, 1000L, "fire-42")
        val dismiss = AlarmGeneration(42L, 1000L, "fire-42")
        val decision = AlarmServiceAdmissionPolicy.classifyAction(
            current = current,
            incoming = dismiss,
            isRestart = false,
            activeEvidence = null
        )
        assertEquals(ActionAdmissionDecision.ADMIT_EXACT_MATCH, decision)
    }

    @Test
    fun policyClassifiesStaleDismiss() {
        val current = AlarmGeneration(42L, 2000L, "fire-42-new")
        val staleDismiss = AlarmGeneration(42L, 1000L, "fire-42-old")
        val decision = AlarmServiceAdmissionPolicy.classifyAction(
            current = current,
            incoming = staleDismiss,
            isRestart = false,
            activeEvidence = null
        )
        assertEquals(ActionAdmissionDecision.REJECT_STALE, decision)
    }

    @Test
    fun policyClassifiesIncompleteIdentityAction() {
        val decision = AlarmServiceAdmissionPolicy.classifyAction(
            current = null,
            incoming = null,
            isRestart = false,
            activeEvidence = null
        )
        assertEquals(ActionAdmissionDecision.REJECT_INCOMPLETE, decision)
    }

    @Test
    fun policyClassifiesServiceRestartWithMatchingActiveEvidence() {
        val activeSnapshot = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 500L
        )
        val restartSnooze = AlarmGeneration(42L, 1000L, "fire-42")
        val decision = AlarmServiceAdmissionPolicy.classifyAction(
            current = null,
            incoming = restartSnooze,
            isRestart = true,
            activeEvidence = activeSnapshot
        )
        assertEquals(ActionAdmissionDecision.ADMIT_RESTART, decision)
    }

    @Test
    fun policyClassifiesServiceRestartWithMissingOrMismatchedActiveEvidence() {
        val activeSnapshot = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = 42L,
            scheduledAt = 1000L,
            fireId = "fire-42",
            firedAt = 500L
        )
        val mismatchedAction = AlarmGeneration(42L, 9999L, "fire-42-stale")
        val decisionMismatched = AlarmServiceAdmissionPolicy.classifyAction(
            current = null,
            incoming = mismatchedAction,
            isRestart = true,
            activeEvidence = activeSnapshot
        )
        assertEquals(ActionAdmissionDecision.REJECT_RESTART_NO_EVIDENCE, decisionMismatched)

        val decisionNoEvidence = AlarmServiceAdmissionPolicy.classifyAction(
            current = null,
            incoming = AlarmGeneration(42L, 1000L, "fire-42"),
            isRestart = true,
            activeEvidence = null
        )
        assertEquals(ActionAdmissionDecision.REJECT_RESTART_NO_EVIDENCE, decisionNoEvidence)
    }

    // --- Production Callback & Playback Attempt Authority Tests ---

    @Test
    fun authorityInstancesAreIsolatedAndIndependent() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val auth1 = AlarmPlaybackAttemptAuthority()
        val auth2 = AlarmPlaybackAttemptAuthority()

        val token1 = auth1.mintNextToken(genA)
        val token2 = auth2.mintNextToken(genA)

        // Auth 1 active token is token1; Auth 2 active token is token2
        assertEquals(token1, auth1.activeAttemptToken())
        assertEquals(token2, auth2.activeAttemptToken())

        // Invalidating Auth 1 does NOT invalidate Auth 2
        auth1.invalidateTokens()
        assertNull(auth1.activeAttemptToken())
        assertEquals(token2, auth2.activeAttemptToken())

        // Auth 1 rejects token2, Auth 2 accepts token2
        assertFalse(auth1.shouldProcessCallback(genA, token2))
        assertTrue(auth2.shouldProcessCallback(genA, token2))
    }

    @Test
    fun callbackPolicyRejectsMismatchedGenerationOrAttemptToken() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")

        val tokenA1 = AlarmServiceCallbackPolicy.mintNextToken(genA)
        val playerA = FakeAlarmPlaybackPlayer()

        AlarmServiceCallbackPolicy.mintNextToken(genB)
        val playerB = FakeAlarmPlaybackPlayer()

        // Test 5: Different Generation callback -> Denied
        assertFalse(
            AlarmServiceCallbackPolicy.shouldProcessCallback(
                currentGen = genB,
                callbackToken = tokenA1,
                currentPlayback = playerB,
                callbackPlayback = playerA
            )
        )

        // Test 4: Same Generation, different Player / Attempt Token -> Denied
        AlarmServiceCallbackPolicy.mintNextToken(genA)
        assertFalse(
            AlarmServiceCallbackPolicy.shouldProcessCallback(
                currentGen = genA,
                callbackToken = tokenA1,
                currentPlayback = playerB,
                callbackPlayback = playerA
            )
        )
    }

    @Test
    fun callbackPolicyHandlesLegitimateFailureHandoffAndDoubleClaim() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = AlarmServiceCallbackPolicy.mintNextToken(genA)

        // Test 1: Legitimate Media3 failure -> Single Claim -> Fallback succeeds
        val handoff1 = AlarmServiceCallbackPolicy.tryClaimFallbackHandoff(
            currentGen = genA,
            failingToken = tokenA
        )
        assertNotNull(handoff1)
        assertEquals(tokenA, handoff1)

        // Test 2: Double claim for same token -> Second claim denied
        val handoff2 = AlarmServiceCallbackPolicy.tryClaimFallbackHandoff(
            currentGen = genA,
            failingToken = tokenA
        )
        assertNull(handoff2)

        // Test 6: Player released after valid claim -> Fallback handoff stays valid and can be consumed
        val isFallbackAllowed = AlarmServiceCallbackPolicy.tryConsumeFallbackHandoff(
            currentGen = genA,
            handoffToken = handoff1!!
        )
        assertTrue(isFallbackAllowed)

        // Second consumption fails (already consumed)
        assertFalse(
            AlarmServiceCallbackPolicy.tryConsumeFallbackHandoff(
                currentGen = genA,
                handoffToken = handoff1
            )
        )
    }

    @Test
    fun callbackPolicyRejectsFallbackIfReplacedBeforeExecution() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = AlarmServiceCallbackPolicy.mintNextToken(genA)

        // Test 1: Claim handoff
        val handoff = AlarmServiceCallbackPolicy.tryClaimFallbackHandoff(
            currentGen = genA,
            failingToken = tokenA
        )
        assertNotNull(handoff)

        // Test 3: Claimed handoff but another Player B installed before fallback executes
        AlarmServiceCallbackPolicy.mintNextToken(genA) // New attempt tokenB for same generation

        val isFallbackAllowed = AlarmServiceCallbackPolicy.tryConsumeFallbackHandoff(
            currentGen = genA,
            handoffToken = handoff!!
        )
        assertFalse(isFallbackAllowed)
    }

    @Test
    fun callbackPolicyHandlesWatchdogSpotifySetupFailureAndInvalidation() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA1 = AlarmServiceCallbackPolicy.mintNextToken(genA)
        val playerA = FakeAlarmPlaybackPlayer()

        AlarmServiceCallbackPolicy.mintNextToken(genA)
        val playerB = FakeAlarmPlaybackPlayer()

        // Test 7: Watchdog old owner cancellation / callback -> Denied
        assertFalse(
            AlarmServiceCallbackPolicy.shouldProcessCallback(
                currentGen = genA,
                callbackToken = tokenA1,
                currentPlayback = playerB,
                callbackPlayback = playerA
            )
        )

        // Test 8: Spotify no-player fallback uses attempt token and succeeds
        val spotifyToken = AlarmServiceCallbackPolicy.mintNextToken(genA)
        val spotifyCallbackAllowed = AlarmServiceCallbackPolicy.shouldProcessCallback(
            currentGen = genA,
            callbackToken = spotifyToken,
            currentPlayback = null,
            callbackPlayback = null
        )
        assertTrue(spotifyCallbackAllowed)

        val spotifyHandoff = AlarmServiceCallbackPolicy.tryClaimFallbackHandoff(
            currentGen = genA,
            failingToken = spotifyToken
        )
        assertNotNull(spotifyHandoff)

        // Test 9: Player creation/setup exception -> Claim succeeds iff activeToken matches
        val setupExceptionHandoffStale = AlarmServiceCallbackPolicy.tryClaimFallbackHandoff(
            currentGen = genA,
            failingToken = tokenA1
        )
        assertNull(setupExceptionHandoffStale)

        // Test 10: Dismiss / Snooze / New START -> Token invalidation
        AlarmServiceCallbackPolicy.invalidateTokens()

        val postDismissCallbackAllowed = AlarmServiceCallbackPolicy.shouldProcessCallback(
            currentGen = null,
            callbackToken = spotifyToken,
            currentPlayback = null,
            callbackPlayback = null
        )
        assertFalse(postDismissCallbackAllowed)

        val postDismissHandoffAllowed = AlarmServiceCallbackPolicy.tryClaimFallbackHandoff(
            currentGen = genA,
            failingToken = spotifyToken
        )
        assertNull(postDismissHandoffAllowed)
    }

    @Test
    fun callbackPolicyHandlesAtomicConcurrentClaims() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = AlarmServiceCallbackPolicy.mintNextToken(genA)

        val executor = Executors.newFixedThreadPool(10)
        val latch = CountDownLatch(1)
        val claimResults = java.util.concurrent.ConcurrentLinkedQueue<PlaybackAttemptToken?>()

        for (i in 0 until 10) {
            executor.execute {
                latch.await()
                val claim = AlarmServiceCallbackPolicy.tryClaimFallbackHandoff(genA, tokenA)
                claimResults.add(claim)
            }
        }

        latch.countDown()
        executor.shutdown()
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)

        val nonNullClaims = claimResults.filterNotNull()
        assertEquals(1, nonNullClaims.size)
        assertEquals(tokenA, nonNullClaims.first())
    }

    private class FakeAlarmPlaybackPlayer : AlarmPlaybackPlayer {
        override fun setVolume(left: Float, right: Float) {}
        override fun durationMs(): Long = 0L
        override fun seekTo(positionMs: Long) {}
        override fun stopAndRelease() {}
    }
}
