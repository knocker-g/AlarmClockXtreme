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

    // --- Required Focused Authority & Fallback Activation Tests ---

    @Test
    fun authorityTokensAreRejectedCrossAuthorityEvenWithIdenticalGenerationAndSequence() {
        // Test Scenario 1: Tokens minted by different Authorities are rejected cross-authority
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val auth1 = AlarmPlaybackAttemptAuthority()
        val auth2 = AlarmPlaybackAttemptAuthority()

        val token1 = auth1.mintNextToken(genA)
        val token2 = auth2.mintNextToken(genA)

        // Same generation, same attemptSequence=1, but different authorityId!
        assertEquals(token1.generation, token2.generation)
        assertEquals(token1.attemptSequence, token2.attemptSequence)
        assertFalse(token1.authorityId == token2.authorityId)

        // Auth 1 accepts token1, REJECTS token2 while both authorities are concurrently active
        assertTrue(auth1.shouldProcessCallback(genA, token1))
        assertFalse(auth1.shouldProcessCallback(genA, token2))

        // Auth 2 accepts token2, REJECTS token1
        assertTrue(auth2.shouldProcessCallback(genA, token2))
        assertFalse(auth2.shouldProcessCallback(genA, token1))
    }

    @Test
    fun claimAndConsumeAreOneShotAndRejectDuplicates() {
        // Test Scenario 2: Double claim and double consume rejection
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        // First claim succeeds
        val handoff1 = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNotNull(handoff1)

        // Second claim for same token fails (double claim)
        val handoff2 = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNull(handoff2)

        // First consume succeeds
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff1!!)
        assertNotNull(fallbackToken)

        // Second consume fails (double consume)
        val secondConsumeToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff1)
        assertNull(secondConsumeToken)
    }

    @Test
    fun newAttemptBeforeCommitRejectsFallbackClaimAndConsume() {
        // Test Scenario 3: Commit前に新Attemptへ切替 → 旧Fallback拒否
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        // Player B or new attempt started BEFORE claim or commit
        auth.mintNextToken(genA)

        // Claim for old tokenA rejected because new token is now active
        val claim = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNull(claim)
    }

    @Test
    fun postTokenActivationSupersessionRejectsPlayerPublicationAndAllSideEffects() {
        // Required Test 1 & 2: Token発行後、Fallback副作用開始前・Player公開前に別Attemptへ切替 → 旧副作用・Player公開拒否
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        // Step 1: Claim handoff & consume+activate -> yields fallbackToken
        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        // Step 2: AFTER fallbackToken is minted/activated, a superseding attempt tokenB is minted
        val tokenB = auth.mintNextToken(genA)

        // Step 3: Attempt side effects under fallbackToken -> ALL ATOMICALLY REJECTED
        var playerPublished = false
        val publishSuccess = auth.publishPlaybackIfActive(genA, fallbackToken!!) {
            playerPublished = true
        }
        assertFalse(publishSuccess)
        assertFalse(playerPublished)

        var watchdogCancelled = false
        val watchdogSuccess = auth.cancelWatchdogIfActive(genA, fallbackToken) {
            watchdogCancelled = true
        }
        assertFalse(watchdogSuccess)
        assertFalse(watchdogCancelled)

        var audioMutated = false
        val audioSuccess = auth.mutateAudioIfActive(genA, fallbackToken) {
            audioMutated = true
        }
        assertFalse(audioSuccess)
        assertFalse(audioMutated)

        // Active token remains tokenB
        assertEquals(tokenB, auth.activeAttemptToken())
    }

    @Test
    fun attemptBoundRoutingTokenIsClearedOnNewAttempt() {
        // Required Test: Attemptに紐付いたRouting状態と新Attemptでのリセット
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        // Set routing attempt token for fallback
        assertTrue(auth.setRoutingAttemptToken(fallbackToken!!))
        assertTrue(auth.isRoutingActive(fallbackToken))

        // New attempt arrives
        val tokenB = auth.mintNextToken(genA)

        // Routing is no longer active for fallbackToken or tokenB
        assertFalse(auth.isRoutingActive(fallbackToken))
        assertFalse(auth.isRoutingActive(tokenB))
    }

    @Test
    fun postTokenActivationSupersessionRejectsOldWatchdogCancellationAndPreservesNewAttemptWatchdog() {
        // Required Test 3 & 4: Token発行後、Watchdogキャンセル・音量変更前に別Attemptへ切替 → 新Watchdog・音量維持
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        // New attempt tokenB arrives and sets new watchdog / volume
        auth.mintNextToken(genA)

        // Old fallbackToken attempts to cancel watchdog -> REJECTED, new watchdog preserved
        var cancelledWatchdog = false
        val cancelResult = auth.cancelWatchdogIfActive(genA, fallbackToken!!) {
            cancelledWatchdog = true
        }
        assertFalse(cancelResult)
        assertFalse(cancelledWatchdog)

        // Old fallbackToken attempts to mutate audio -> REJECTED
        var mutatedVolume = false
        val mutateResult = auth.mutateAudioIfActive(genA, fallbackToken) {
            mutatedVolume = true
        }
        assertFalse(mutateResult)
        assertFalse(mutatedVolume)
    }

    @Test
    fun legitimateFallbackTokenSuccessfullyPublishesPlayerAndExecutesSideEffects() {
        // Required Test 5: 正当なFallback TokenでPlayer開始成功
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNotNull(handoff)

        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        // No superseding attempt -> Legitimate publication succeeds atomically!
        var publishedPlayer: AlarmPlaybackPlayer? = null
        val fakePlayer = FakeAlarmPlaybackPlayer()

        val publishSuccess = auth.publishPlaybackIfActive(genA, fallbackToken!!) {
            publishedPlayer = fakePlayer
        }

        assertTrue(publishSuccess)
        assertEquals(fakePlayer, publishedPlayer)
        assertEquals(fallbackToken, auth.activeAttemptToken())
    }

    @Test
    fun staleLegacyRadioFallbackIsExpiredAndRejected() {
        // Test Scenario 6: Legacy Radioの遅延Fallback失効
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenRadio = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenRadio)
        assertNotNull(handoff)

        // Radio error claims fallback, but user dismisses or new alarm starts before fallback coroutine runs
        auth.invalidateTokens()

        // Fallback activation is rejected after invalidation
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNull(fallbackToken)
    }

    @Test
    fun legitimateLegacyRadioFallbackSucceeds() {
        // Test Scenario 7: 正当なLegacy Radio Fallback成功
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenRadio = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenRadio)
        assertNotNull(handoff)

        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        // Fallback player callbacks are accepted under fallbackToken
        val player = FakeAlarmPlaybackPlayer()
        assertTrue(auth.shouldProcessCallback(genA, fallbackToken!!, currentPlayback = player, callbackPlayback = player))
    }

    @Test
    fun dismissSnoozeAndNewStartInvalidatePostActivationFallback() {
        // Required Test 6: Dismiss / Snooze / 新世代STARTで旧Attemptの副作用を拒否
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)

        // Snooze / Dismiss invalidates tokens
        auth.invalidateTokens()

        var sideEffectExecuted = false
        val executed = auth.executeIfActive(genA, fallbackToken!!) {
            sideEffectExecuted = true
        }
        assertFalse(executed)
        assertFalse(sideEffectExecuted)
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
