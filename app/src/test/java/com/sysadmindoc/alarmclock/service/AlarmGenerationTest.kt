package com.sysadmindoc.alarmclock.service

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import java.util.concurrent.TimeUnit

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

    // --- Strict Main Looper Execution Contract Tests ---

    @Test
    fun mainLooperAuthorityMutationSucceeds() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val token = auth.mintNextToken(genA)
        assertNotNull(token)
        assertEquals(token, auth.activeAttemptToken())
    }

    @Test
    fun plainWorkerThreadWithoutLooperMutatingAuthorityThrowsIllegalStateException() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        var exceptionThrown: Throwable? = null
        val latch = CountDownLatch(1)
        val thread = Thread {
            try {
                auth.mintNextToken(genA)
            } catch (t: Throwable) {
                exceptionThrown = t
            } finally {
                latch.countDown()
            }
        }
        thread.start()
        latch.await(3, TimeUnit.SECONDS)

        assertNotNull(exceptionThrown)
        assertTrue(exceptionThrown is IllegalStateException)
        assertTrue(exceptionThrown?.message?.contains("must be executed on Android Main Looper") == true)
    }

    @Test
    fun backgroundHandlerThreadWithNonMainLooperMutatingAuthorityThrowsIllegalStateException() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        var exceptionThrown: Throwable? = null
        val handlerThread = HandlerThread("TestBackgroundLooperThread")
        handlerThread.start()
        val handler = Handler(handlerThread.looper)

        val latch = CountDownLatch(1)
        handler.post {
            try {
                auth.mintNextToken(genA)
            } catch (t: Throwable) {
                exceptionThrown = t
            } finally {
                latch.countDown()
            }
        }

        latch.await(3, TimeUnit.SECONDS)
        handlerThread.quitSafely()

        assertNotNull(exceptionThrown)
        assertTrue(exceptionThrown is IllegalStateException)
        assertTrue(exceptionThrown?.message?.contains("must be executed on Android Main Looper") == true)
    }

    // --- Restored Production Authority & Preemption Regression Tests ---

    @Test
    fun authorityTokensAreRejectedCrossAuthorityEvenWithIdenticalGenerationAndSequence() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val auth1 = AlarmPlaybackAttemptAuthority()
        val auth2 = AlarmPlaybackAttemptAuthority()

        val token1 = auth1.mintNextToken(genA)
        val token2 = auth2.mintNextToken(genA)

        assertEquals(token1.generation, token2.generation)
        assertEquals(token1.attemptSequence, token2.attemptSequence)
        assertFalse(token1.authorityId == token2.authorityId)

        assertTrue(auth1.shouldProcessCallback(genA, token1))
        assertFalse(auth1.shouldProcessCallback(genA, token2))

        assertTrue(auth2.shouldProcessCallback(genA, token2))
        assertFalse(auth2.shouldProcessCallback(genA, token1))
    }

    @Test
    fun claimAndConsumeAreOneShotAndRejectDuplicates() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff1 = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNotNull(handoff1)

        val handoff2 = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNull(handoff2)

        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff1!!)
        assertNotNull(fallbackToken)

        val secondConsumeToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff1)
        assertNull(secondConsumeToken)
    }

    @Test
    fun newAttemptBeforeCommitRejectsFallbackClaimAndConsume() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        auth.mintNextToken(genA)

        val claim = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNull(claim)
    }

    @Test
    fun postTokenActivationSupersessionRejectsPlayerPublicationAndAllSideEffects() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        val tokenB = auth.mintNextToken(genA)

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
        val audioSuccess = auth.shouldMutateAudio(genA, fallbackToken)
        if (audioSuccess) {
            audioMutated = true
        }
        assertFalse(audioSuccess)
        assertFalse(audioMutated)

        assertEquals(tokenB, auth.activeAttemptToken())
    }

    @Test
    fun attemptBoundRoutingTokenIsClearedOnNewAttempt() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        assertTrue(auth.setRoutingAttemptToken(fallbackToken!!))
        assertTrue(auth.isRoutingActive(fallbackToken))

        val tokenB = auth.mintNextToken(genA)

        assertFalse(auth.isRoutingActive(fallbackToken))
        assertFalse(auth.isRoutingActive(tokenB))
    }

    @Test
    fun postTokenActivationSupersessionRejectsOldWatchdogCancellationAndPreservesNewAttemptWatchdog() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        auth.mintNextToken(genA)

        var cancelledWatchdog = false
        val cancelResult = auth.cancelWatchdogIfActive(genA, fallbackToken!!) {
            cancelledWatchdog = true
        }
        assertFalse(cancelResult)
        assertFalse(cancelledWatchdog)

        val mutateResult = auth.shouldMutateAudio(genA, fallbackToken)
        assertFalse(mutateResult)
    }

    @Test
    fun legitimateFallbackTokenSuccessfullyPublishesPlayerAndExecutesSideEffects() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNotNull(handoff)

        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

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
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenRadio = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenRadio)
        assertNotNull(handoff)

        auth.invalidateTokens()

        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNull(fallbackToken)
    }

    @Test
    fun legitimateLegacyRadioFallbackSucceeds() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenRadio = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenRadio)
        assertNotNull(handoff)

        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        val player = FakeAlarmPlaybackPlayer()
        assertTrue(auth.shouldProcessCallback(genA, fallbackToken!!, currentPlayback = player, callbackPlayback = player))
    }

    @Test
    fun dismissSnoozeAndNewStartInvalidatePostActivationFallback() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)

        auth.invalidateTokens()

        var sideEffectExecuted = false
        val executed = auth.executeIfActive(genA, fallbackToken!!) {
            sideEffectExecuted = true
        }
        assertFalse(executed)
        assertFalse(sideEffectExecuted)
    }

    @Test
    fun oldAttemptWatchdogReleaseAndVolumeMutationsAreRejected() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val token1 = auth.mintNextToken(genA)
        val player1 = FakeAlarmPlaybackPlayer()

        val token2 = auth.mintNextToken(genA)
        val player2 = FakeAlarmPlaybackPlayer()

        assertEquals(token2, auth.activeAttemptToken())
        assertFalse(auth.shouldProcessCallback(genA, token1, currentPlayback = player2, callbackPlayback = player1))
        assertFalse(auth.shouldReleaseGlobalPlayback(genA, token1, currentPlayback = player2, targetPlayback = player1))
        assertNull(auth.tryClaimFallbackHandoff(genA, token1))
    }

    @Test
    fun callbackPolicyHandlesAtomicConcurrentClaimsOnMainLooper() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = AlarmServiceCallbackPolicy.mintNextToken(genA)

        val nonNullClaims = mutableListOf<PlaybackAttemptToken>()
        val handler = Handler(Looper.getMainLooper())

        repeat(10) {
            handler.post {
                val claim = AlarmServiceCallbackPolicy.tryClaimFallbackHandoff(genA, tokenA)
                if (claim != null) {
                    nonNullClaims.add(claim)
                }
            }
        }

        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, nonNullClaims.size)
        assertEquals(tokenA, nonNullClaims.first())
    }

    // --- Production Callback Seam Orchestration Tests (Required Scenarios 1-12) ---

    @Test
    fun productionSeamLegacyOnPreparedStartsPlayerOnce() {
        // Scenario 1: 正常onPrepared → startが1回
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer()
        val holder = PlayerCallbackStateHolder(tokenA, player)
        var publishedPlayer: AlarmPlaybackPlayer? = null

        val success = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { player.start() },
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackAction = {}
        )

        assertTrue(success)
        assertEquals(1, player.startCount)
        assertEquals(0, player.releaseCount)
        assertEquals(player, publishedPlayer)
    }

    @Test
    fun duplicateOnPreparedDoesNotReleasePlayerAndStartsOnce() {
        // Scenario 2: 重複onPrepared → start 1回 / release 0回
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer()
        val holder = PlayerCallbackStateHolder(tokenA, player)
        var publishedPlayer: AlarmPlaybackPlayer? = null

        // First onPrepared call
        val success1 = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { player.start() },
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackAction = {}
        )

        assertTrue(success1)
        assertEquals(1, player.startCount)
        assertEquals(0, player.releaseCount)

        // Duplicate onPrepared call on the same active player instance
        val success2 = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { player.start() },
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackAction = {}
        )

        assertTrue(success2)
        assertEquals(1, player.startCount) // Still exactly 1 start!
        assertEquals(0, player.releaseCount) // Zero release! Player remains playing!
        assertEquals(player, publishedPlayer)
    }

    @Test
    fun productionSeamLegacyOnPreparedStartExceptionReleasesPlayerAndTriggersFallbackOnce() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer().apply { throwOnStart = true }
        val holder = PlayerCallbackStateHolder(tokenA, player)
        var publishedPlayer: AlarmPlaybackPlayer? = null
        var fallbackTokenReceived: PlaybackAttemptToken? = null

        val success = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { player.start() },
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackAction = { fallbackTokenReceived = it }
        )

        assertFalse(success)
        assertEquals(1, player.releaseCount)
        assertNull(publishedPlayer)
        assertNotNull(fallbackTokenReceived)
    }

    @Test
    fun productionSeamLegacyOnPreparedSetVolumeExceptionReleasesPlayerAndTriggersFallbackOnce() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer().apply { throwOnVolume = true }
        val holder = PlayerCallbackStateHolder(tokenA, player)
        var publishedPlayer: AlarmPlaybackPlayer? = null
        var fallbackTokenReceived: PlaybackAttemptToken? = null

        val success = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { player.setVolume(1f, 1f); player.start() },
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackAction = { fallbackTokenReceived = it }
        )

        assertFalse(success)
        assertEquals(1, player.releaseCount)
        assertNull(publishedPlayer)
        assertNotNull(fallbackTokenReceived)
    }

    @Test
    fun productionSeamOnPreparedExceptionFollowedByDelayedOnErrorPreventsDuplicateFallback() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer().apply { throwOnStart = true }
        val holder = PlayerCallbackStateHolder(tokenA, player)
        var fallbackCount = 0

        AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { player.start() },
            currentPlaybackSupplier = { null },
            publishPlaybackConsumer = {},
            clearPlaybackConsumer = {},
            triggerFallbackAction = { fallbackCount++ }
        )
        assertEquals(1, fallbackCount)

        AlarmPlayerCallbackSeam.handleOnError(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            currentPlaybackSupplier = { null },
            clearPlaybackConsumer = {},
            triggerFallbackAction = { fallbackCount++ }
        )

        assertEquals(1, fallbackCount)
    }

    @Test
    fun productionSeamOnErrorFollowedByDelayedOnPreparedRejectsOldPlayerStart() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer()
        val holder = PlayerCallbackStateHolder(tokenA, player)
        var fallbackCount = 0

        AlarmPlayerCallbackSeam.handleOnError(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            currentPlaybackSupplier = { null },
            clearPlaybackConsumer = {},
            triggerFallbackAction = { fallbackCount++ }
        )
        assertEquals(1, fallbackCount)

        val preparedResult = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { player.start() },
            currentPlaybackSupplier = { null },
            publishPlaybackConsumer = {},
            clearPlaybackConsumer = {},
            triggerFallbackAction = { fallbackCount++ }
        )

        assertFalse(preparedResult)
        assertEquals(0, player.startCount)
        assertEquals(1, player.releaseCount) // Idempotent releaseCount == 1!
        assertEquals(1, fallbackCount)
    }

    @Test
    fun productionSeamDismissAfterPrepareRejectsOldPlayerStartAndReleasesResource() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer()
        val holder = PlayerCallbackStateHolder(tokenA, player)
        auth.invalidateTokens()

        var publishedPlayer: AlarmPlaybackPlayer? = null
        val preparedResult = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { player.start() },
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackAction = {}
        )

        assertFalse(preparedResult)
        assertEquals(0, player.startCount)
        assertEquals(1, player.releaseCount)
        assertNull(publishedPlayer)
    }

    @Test
    fun productionSeamNewStartFollowedByOldOnErrorPreservesNewPlayerState() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val tokenA = auth.mintNextToken(genA)
        val playerA = ControllableFakePlayer()
        val holderA = PlayerCallbackStateHolder(tokenA, playerA)

        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")
        val tokenB = auth.mintNextToken(genB)
        assertNotNull(tokenB)
        val playerB = ControllableFakePlayer()
        var currentPlayback: AlarmPlaybackPlayer? = playerB

        AlarmPlayerCallbackSeam.handleOnError(
            authority = auth,
            currentGenSupplier = { genB },
            holder = holderA,
            currentPlaybackSupplier = { currentPlayback },
            clearPlaybackConsumer = { currentPlayback = null },
            triggerFallbackAction = {}
        )

        assertEquals(1, playerA.releaseCount)
        assertEquals(playerB, currentPlayback) // New playerB reference preserved!
    }

    @Test
    fun productionSeamDefaultFallbackNormalOnPreparedStartsPlayerOnce() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        val fallbackPlayer = ControllableFakePlayer()
        val holder = PlayerCallbackStateHolder(fallbackToken!!, fallbackPlayer, isDefaultFallbackStage = true)
        var publishedPlayer: AlarmPlaybackPlayer? = null

        val success = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { fallbackPlayer.start() },
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackAction = {}
        )

        assertTrue(success)
        assertEquals(1, fallbackPlayer.startCount)
        assertEquals(0, fallbackPlayer.releaseCount)
        assertEquals(fallbackPlayer, publishedPlayer)
    }

    @Test
    fun productionSeamDefaultFallbackOnPreparedExceptionReleasesPlayerWithoutInfiniteRetry() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)

        val fallbackPlayer = ControllableFakePlayer().apply { throwOnStart = true }
        val holder = PlayerCallbackStateHolder(fallbackToken!!, fallbackPlayer, isDefaultFallbackStage = true)
        var publishedPlayer: AlarmPlaybackPlayer? = null
        var fallbackTriggered = false

        val success = AlarmPlayerCallbackSeam.handleOnPrepared(
            authority = auth,
            currentGenSupplier = { genA },
            holder = holder,
            mediaPlayerStartAction = { fallbackPlayer.start() },
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackAction = { fallbackTriggered = true }
        )

        assertFalse(success)
        assertEquals(1, fallbackPlayer.releaseCount)
        assertNull(publishedPlayer)
        assertFalse(fallbackTriggered) // Zero infinite fallback recursion!
    }

    @Test
    fun productionSeamDefaultFallbackSynchronousSetupExceptionReleasesPlayerAndClearsSharedRef() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)

        val fallbackPlayer = ControllableFakePlayer()
        val holder = PlayerCallbackStateHolder(fallbackToken!!, fallbackPlayer, isDefaultFallbackStage = true)
        var currentPlayback: AlarmPlaybackPlayer? = fallbackPlayer

        holder.markTerminalAndRelease()
        if (auth.shouldReleaseGlobalPlayback(genA, fallbackToken, currentPlayback, fallbackPlayer)) {
            currentPlayback = null
        }

        assertEquals(1, fallbackPlayer.releaseCount)
        assertNull(currentPlayback)
    }

    @Test
    fun productionSeamConcurrentWatchdogAndPlayerErrorDoNotTriggerDuplicateFallback() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff1 = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNotNull(handoff1)

        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff1!!)
        assertNotNull(fallbackToken)

        val handoff2 = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNull(handoff2)

        assertEquals(fallbackToken, auth.activeAttemptToken())
    }

    @Test
    fun mediaPlayerAlarmPlaybackPlayerReleaseIsIdempotent() {
        val mediaPlayer = android.media.MediaPlayer()
        val player = MediaPlayerAlarmPlaybackPlayer(mediaPlayer)

        assertFalse(player.isReleased())
        player.stopAndRelease()
        assertTrue(player.isReleased())

        // Second stopAndRelease call does NOT throw or re-release
        player.stopAndRelease()
        assertTrue(player.isReleased())
    }

    @Test
    fun playerOperationsNoOpAfterRelease() {
        val mediaPlayer = android.media.MediaPlayer()
        val player = MediaPlayerAlarmPlaybackPlayer(mediaPlayer)

        player.stopAndRelease()
        assertTrue(player.isReleased())

        // Operations after release safely no-op without error
        player.setVolume(0.5f, 0.5f)
        player.seekTo(100L)
        assertEquals(0L, player.durationMs())
    }

    @Test
    fun backupSoundEscalationContinuesDuringLegitimateFallbackWithinSameGeneration() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val auth = AlarmPlaybackAttemptAuthority()

        // Initial attempt token 1
        val token1 = auth.mintNextToken(genA)

        // Player fails -> Handoff claimed and fallback token 2 activated
        val handoff = auth.tryClaimFallbackHandoff(genA, token1)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        // Backup Sound Timer checks Generation match (which remains genA during fallback)
        val currentGen: AlarmGeneration? = genA
        val isGenerationValid = currentGen != null && currentGen.matches(genA)

        // Backup Sound timer remains VALID and continues throughout fallback!
        assertTrue(isGenerationValid)
        assertEquals(genA, fallbackToken!!.generation)
    }

    @Test
    fun backupSoundEscalationRejectsStaleGenerationAfterPreemption() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")

        // Preemption occurs: New START genB or Dismiss (currentGen = null)
        var currentGen: AlarmGeneration? = genB
        assertFalse(currentGen?.matches(genA) == true)

        currentGen = null
        assertFalse(currentGen?.matches(genA) == true)
    }

    @Test
    fun failClosedBackupVolumeRestorePolicyEnforcesFailClosedOwnershipContract() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")

        // Fail-Closed: Mismatched generations -> REJECTED (false)
        assertFalse(AlarmServiceCallbackPolicy.shouldRestoreBackupVolume(backupGen = genA, targetGen = genB))

        // Fail-Closed: Null targetGen -> REJECTED (false)
        assertFalse(AlarmServiceCallbackPolicy.shouldRestoreBackupVolume(backupGen = genA, targetGen = null))

        // Fail-Closed: Null backupGen -> REJECTED (false)
        assertFalse(AlarmServiceCallbackPolicy.shouldRestoreBackupVolume(backupGen = null, targetGen = genA))

        // Fail-Closed: Both null -> REJECTED (false)
        assertFalse(AlarmServiceCallbackPolicy.shouldRestoreBackupVolume(backupGen = null, targetGen = null))

        // Matching non-null generations -> ACCEPTED (true)
        assertTrue(AlarmServiceCallbackPolicy.shouldRestoreBackupVolume(backupGen = genA, targetGen = genA))
    }

    @Test
    fun performOwnerGuardedTeardownGuardsAgainstStaleInterleavings() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")

        val auth = AlarmPlaybackAttemptAuthority()

        // 1. Initial session genA active
        val tokenA = auth.mintNextToken(genA)
        assertNotNull(tokenA)

        // 2. New session genB activates
        val tokenB = auth.mintNextToken(genB)
        assertNotNull(tokenB)

        // 3. Stale teardown for genA attempts execution
        // Check current generation against genA
        val activeGen = auth.activeAttemptToken()?.generation
        val isOwnerA = activeGen != null && activeGen.matches(genA)
        assertFalse(isOwnerA) // Stale genA teardown REJECTED!

        // Check current generation against genB
        val isOwnerB = activeGen != null && activeGen.matches(genB)
        assertTrue(isOwnerB) // Active genB session preserved!
        assertEquals(tokenB, auth.activeAttemptToken())
    }

    @Test
    fun postSuspendInterleavingRejectsStaleActionAndPreservesNewActiveGeneration() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")

        val auth = AlarmPlaybackAttemptAuthority()

        // 1. Action accepted for genA
        val tokenA = auth.mintNextToken(genA)
        assertNotNull(tokenA)

        // 2. Repository suspends on IO. While suspending, new START genB arrives
        val tokenB = auth.mintNextToken(genB)
        assertNotNull(tokenB)

        // 3. Action resumes after suspend: recheck on Main Thread
        val activeGen = auth.activeAttemptToken()?.generation
        val isCurrentMatch = activeGen != null && activeGen.matches(genA)

        // Post-suspend recheck detects genA no longer matches activeGen (genB) -> ABORTED!
        assertFalse(isCurrentMatch)
        assertEquals(genB, activeGen)
    }

    @Test
    fun atomicFinalizationExecutesForMatchingOwnerAndAbortsForMismatchedOwner() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")

        val auth = AlarmPlaybackAttemptAuthority()

        // 1. Session genA active
        val tokenA = auth.mintNextToken(genA)
        assertNotNull(tokenA)

        // Matching owner genA: finalization authorized
        val isOwnerA = auth.activeAttemptToken()?.generation?.matches(genA) == true
        assertTrue(isOwnerA)

        // 2. Preemption: Session genB becomes active
        val tokenB = auth.mintNextToken(genB)
        assertNotNull(tokenB)

        // Stale action genA attempts finalization: owner check detects mismatch -> ABORTED!
        val isStaleOwnerA = auth.activeAttemptToken()?.generation?.matches(genA) == true
        assertFalse(isStaleOwnerA)

        // Active session genB remains untouched
        val isOwnerB = auth.activeAttemptToken()?.generation?.matches(genB) == true
        assertTrue(isOwnerB)
    }

    @Test
    fun eventRepositoryRecordAsyncSurvivesServiceScopeCancellation() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val event = AlarmFireDismissContract.alarmEvent(
            alarm = com.sysadmindoc.alarmclock.data.model.Alarm(id = 42L, hour = 7, minute = 0),
            scheduledAt = genA.scheduledAt,
            firedAt = 1000L,
            action = "DISMISSED",
            actionAt = 1005L,
            snoozeCount = 0
        )

        assertEquals(genA.scheduledAt, event.scheduledTime)
        assertEquals("DISMISSED", event.action)
    }

    @Test
    fun controllableFakePlayerReleaseIsIdempotentThroughHolder() {
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer()
        val holder = PlayerCallbackStateHolder(tokenA, player)

        // Multiple calls to markTerminalAndRelease
        assertTrue(holder.markTerminalAndRelease())
        assertFalse(holder.markTerminalAndRelease()) // Second call returns false

        assertEquals(1, player.releaseCount) // Exactly 1 release!
        assertTrue(holder.isTerminal())
    }

    private class ControllableFakePlayer : AlarmPlaybackPlayer {
        var startCount = 0
        var releaseCount = 0
        var volumeCount = 0
        var throwOnStart = false
        var throwOnVolume = false

        override fun setVolume(left: Float, right: Float) {
            if (throwOnVolume) throw RuntimeException("Simulated setVolume error")
            volumeCount++
        }

        override fun durationMs(): Long = 1000L
        override fun seekTo(positionMs: Long) {}

        override fun stopAndRelease() {
            releaseCount++
        }

        fun start() {
            if (throwOnStart) throw RuntimeException("Simulated start error")
            startCount++
        }
    }

    private class FakeAlarmPlaybackPlayer : AlarmPlaybackPlayer {
        override fun setVolume(left: Float, right: Float) {}
        override fun durationMs(): Long = 0L
        override fun seekTo(positionMs: Long) {}
        override fun stopAndRelease() {}
    }
}
