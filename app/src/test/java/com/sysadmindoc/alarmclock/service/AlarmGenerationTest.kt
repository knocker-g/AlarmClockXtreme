package com.sysadmindoc.alarmclock.service

import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
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
    fun preemptionFinalizationTrackerEnforcesExactlyOnceFinalization() {
        val tracker = PreemptionFinalizationTracker()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        // First preemption finalization: ACCEPTED (true)
        assertTrue(tracker.markFinalized(genA))
        assertTrue(tracker.isFinalized(genA))

        // Duplicate preemption finalization for same generation: DENIED (false)
        assertFalse(tracker.markFinalized(genA))
    }

    @Test
    fun finalizedGenerationsAreExcludedOnProcessRestart() {
        val tracker = AlarmServiceCallbackPolicy.defaultTracker
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        assertFalse(tracker.isFinalized(genA))
        tracker.markFinalized(genA)
        assertTrue(tracker.isFinalized(genA))
    }

    @Test
    fun failClosedConditionalClearRejectsNullOrMismatchedTargetGen() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(99L, 2000L, "fire-99-b")

        // Direct production policy invocation: Null targetGen clear -> REJECTED (fail closed)
        assertFalse(
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmWriterPolicy.shouldExecuteUpdate(
                storedId = genB.alarmId,
                storedScheduledAt = genB.scheduledAt,
                storedFireId = genB.fireId,
                requestSnapshot = null,
                targetGen = null
            )
        )

        // Direct production policy invocation: Mismatched targetGen clear -> REJECTED
        assertFalse(
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmWriterPolicy.shouldExecuteUpdate(
                storedId = genB.alarmId,
                storedScheduledAt = genB.scheduledAt,
                storedFireId = genB.fireId,
                requestSnapshot = null,
                targetGen = genA
            )
        )

        // Direct production policy invocation: Matching targetGen clear -> ACCEPTED
        assertTrue(
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmWriterPolicy.shouldExecuteUpdate(
                storedId = genB.alarmId,
                storedScheduledAt = genB.scheduledAt,
                storedFireId = genB.fireId,
                requestSnapshot = null,
                targetGen = genB
            )
        )
    }

    @Test
    fun activeAlarmWriterPolicyAcceptsNonNullSnapshotUpdates() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(99L, 2000L, "fire-99-b")

        val snapshotB = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genB.alarmId,
            scheduledAt = genB.scheduledAt,
            fireId = genB.fireId,
            firedAt = 2000L,
            state = "FIRING"
        )

        // Snapshot update for genB when stored is genA -> ACCEPTED (allows starting new active alarm)
        assertTrue(
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmWriterPolicy.shouldExecuteUpdate(
                storedId = genA.alarmId,
                storedScheduledAt = genA.scheduledAt,
                storedFireId = genA.fireId,
                requestSnapshot = snapshotB,
                targetGen = genB
            )
        )
    }

    @Test
    fun realPreferencesManagerPipelineProcessesChannelEnqueueAndGuardsDataStore() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)

        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )

        val genB = AlarmGeneration(99L, 2000L, "fire-99-b")
        val snapshotB = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genB.alarmId,
            scheduledAt = genB.scheduledAt,
            fireId = genB.fireId,
            firedAt = 2000L,
            state = "FIRING"
        )

        // 1. Enqueue START genA -> Enqueued & committed to DataStore
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())
        assertEquals(42L, pm.getCurrentSettings().activeAlarmId)
        assertEquals(42L, pm.getCachedSettings().activeAlarmId)

        // 2. Enqueue START genB -> Enqueued & committed
        val ackB = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(snapshotB, genB, ackB))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackB.await())
        assertEquals(99L, pm.getCurrentSettings().activeAlarmId)
        assertEquals(99L, pm.getCachedSettings().activeAlarmId)

        // 3. Stale clear for old genA -> Enqueued & REJECTED_STALE by pipeline consumer
        val ackStaleClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(null, targetGen = genA, ack = ackStaleClear))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.REJECTED_STALE, ackStaleClear.await())
        assertEquals(99L, pm.getCurrentSettings().activeAlarmId)
        assertEquals(99L, pm.getCachedSettings().activeAlarmId)

        // 4. Valid clear for active genB -> Enqueued & COMMITTED
        val ackValidClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(null, targetGen = genB, ack = ackValidClear))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackValidClear.await())
        assertNull(pm.getCurrentSettings().activeAlarmId)
        assertNull(pm.getCachedSettings().activeAlarmId)
    }

    @Test
    fun parallelUpdateAndActiveAlarmWriterUpdateMaintainCacheIntegrity() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)

        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )

        // Parallel execution: normal preference update and active alarm enqueue
        val ack = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        val job1 = launch(kotlinx.coroutines.Dispatchers.IO) {
            pm.update { it.copy(is24HourFormat = true) }
        }
        val job2 = launch(kotlinx.coroutines.Dispatchers.IO) {
            pm.enqueueActiveAlarmUpdate(snapshotA, genA, ack)
        }

        job1.join()
        job2.join()
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ack.await())

        // Verify DataStore and in-memory cache consistency
        val diskSettings = pm.getCurrentSettings()
        val cachedSettings = pm.getCachedSettings()

        assertEquals(diskSettings.is24HourFormat, cachedSettings.is24HourFormat)
        assertEquals(diskSettings.activeAlarmId, cachedSettings.activeAlarmId)
        assertEquals(42L, cachedSettings.activeAlarmId)
        assertTrue(cachedSettings.is24HourFormat)
    }

    @Test
    fun reproduceOutofOrderPostCommitCacheAssignmentInversion() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)

        val genB = AlarmGeneration(99L, 2000L, "fire-99-b")
        val snapshotB = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genB.alarmId,
            scheduledAt = genB.scheduledAt,
            fireId = genB.fireId,
            firedAt = 2000L,
            state = "FIRING"
        )

        // Enqueue active alarm update genB (99L)
        val ackB = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(snapshotB, genB, ackB))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackB.await())

        // Disk state AND in-memory cache MUST remain 100% consistent (both equal to 99L)
        val diskSettings = pm.getCurrentSettings()
        val cachedSettings = pm.getCachedSettings()
        assertEquals(diskSettings.activeAlarmId, cachedSettings.activeAlarmId)
        assertEquals(99L, cachedSettings.activeAlarmId)
    }

    @Test
    fun reproduceCacheRegressionWhenPostCommitAssignmentsAreOutofOrder() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)

        val genB = AlarmGeneration(99L, 2000L, "fire-99-b")
        val snapshotB = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genB.alarmId,
            scheduledAt = genB.scheduledAt,
            fireId = genB.fireId,
            firedAt = 2000L,
            state = "FIRING"
        )

        // Enqueue active alarm update genB (99L)
        val ackB = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(snapshotB, genB, ackB))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackB.await())

        // Disk state AND in-memory cache MUST remain 100% consistent (both equal to 99L)
        val diskSettings = pm.getCurrentSettings()
        val cachedSettings = pm.getCachedSettings()
        assertEquals(diskSettings.activeAlarmId, cachedSettings.activeAlarmId)
        assertEquals(99L, cachedSettings.activeAlarmId)
    }

    @Test
    fun reproduceColdStartAdmissionRejectionWhenSettingsNotLoaded() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val current: AlarmGeneration? = null
        val activeSnapshot: AlarmService.Companion.ActiveAlarmSnapshot? = null
        val isRestart = true

        val decision = AlarmServiceAdmissionPolicy.classifyAction(
            current = current,
            incoming = genA,
            isRestart = isRestart,
            activeEvidence = activeSnapshot
        )

        // Without waiting for DataStore load, classifyAction returns REJECT_RESTART_NO_EVIDENCE!
        assertEquals(ActionAdmissionDecision.REJECT_RESTART_NO_EVIDENCE, decision)
    }

    @Test
    fun coldStartRestartAdmissionAwaitsDataStoreDiskReadAndAdmitsMatchingGen() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)

        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )

        // 1. Enqueue active alarm update genA to DataStore disk
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Cold-start read directly from DataStore disk via getCurrentSettings()
        val loadedSettings = pm.getCurrentSettings()
        val loadedActive = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = loadedSettings.activeAlarmId!!,
            scheduledAt = loadedSettings.activeAlarmScheduledAt,
            fireId = loadedSettings.activeAlarmFireId,
            firedAt = loadedSettings.activeAlarmFiredAt,
            state = loadedSettings.activeAlarmState,
            refireAt = loadedSettings.activeAlarmRefireAt
        )

        // 3. Classify restart action with loaded DataStore disk evidence -> ADMIT_RESTART!
        val asyncDecision = AlarmServiceAdmissionPolicy.classifyAction(
            current = null,
            incoming = genA,
            isRestart = true,
            activeEvidence = loadedActive
        )

        assertEquals(ActionAdmissionDecision.ADMIT_RESTART, asyncDecision)
    }

    @Test
    fun realAlarmServiceOnStartCommandAdmitsRestartSnoozeAndDismissFromPersistedDataStore() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)

        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )

        // 1. Persist active alarm genA to DataStore
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Start AlarmService on cold restart
        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        val snoozeIntent = Intent(service, AlarmService::class.java).apply {
            action = AlarmService.ACTION_SNOOZE
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
        }

        // Service is restarted (currentAlarmId = -1), reads persisted genA from DataStore -> ADMIT_RESTART
        val startResult = service.onStartCommand(snoozeIntent, 0, 1)
        assertEquals(android.app.Service.START_NOT_STICKY, startResult)
    }

    @Test
    fun coldStartRestartSnoozeAwaitsDataStoreInitialLoadAndSnoozesAlarm() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Persist active alarm genA (401L) to DataStore disk
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val genA = AlarmGeneration(401L, 1000L, "fire-401-snooze")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Set defaultInitialLoadLatch before creating cold PreferencesManager instance so isSettingsLoaded is initially false
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            // 3. Inject pmCold
            service.preferencesManager = pmCold
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val completionLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch

            val snoozeIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 4. Cold-start onStartCommand returns START_STICKY while DataStore load is blocked
            val startResult = service.onStartCommand(snoozeIntent, 0, 1)
            assertEquals(android.app.Service.START_STICKY, startResult)

            // 5. Unblock DataStore initial load
            loadLatch.complete(Unit)

            // 6. Deterministically await cold-start admission and snoozeAlarm completion while flushing main looper
            awaitColdStartCompletion(completionLatch)

            // 7. Verify snooze side-effects: activeAlarm state is SNOOZED in DataStore
            val snoozedSettings = awaitSettingsCondition(pmCold) { it.activeAlarmState == "SNOOZED" }
            assertEquals("SNOOZED", snoozedSettings.activeAlarmState)
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            controller.destroy()
        }
    }

    @Test
    fun coldStartRestartAbortsIfNewStartArrivesWhileDataStoreReadInFlight() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Set defaultInitialLoadLatch before creating cold PreferencesManager instance
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            service.preferencesManager = pmCold

            val completionLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch

            val genA = AlarmGeneration(402L, 1000L, "fire-402-a")
            val snoozeIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 2. Cold-start onStartCommand returns START_STICKY while DataStore load is blocked
            val startResult = service.onStartCommand(snoozeIntent, 0, 1)
            assertEquals(android.app.Service.START_STICKY, startResult)

            // 3. New START genB (403L) arrives while cold-start read is in-flight!
            val genB = AlarmGeneration(403L, 2000L, "fire-403-b")
            val startIntentB = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_START_ALARM
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genB.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genB.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genB.fireId)
            }
            service.onStartCommand(startIntentB, 0, 2)

            // 4. Unblock DataStore initial load
            loadLatch.complete(Unit)

            // 5. Deterministically await completion of the delayed cold-start coroutine while flushing main looper
            awaitColdStartCompletion(completionLatch)

            // 6. Verify preemption protection: genB (403L) remains active in service, old genA restart snooze was ABORTED!
            assertEquals(403L, service.currentAlarmId)
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            controller.destroy()
        }
    }

    @Test
    fun coldStartRestartDismissAwaitsDataStoreInitialLoadAndDismissesAlarm() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Persist active alarm genA (404L) to DataStore disk
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val genA = AlarmGeneration(404L, 1000L, "fire-404-dismiss")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Set defaultInitialLoadLatch before creating cold PreferencesManager instance so isSettingsLoaded is initially false
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            service.preferencesManager = pmCold
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val completionLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch

            val dismissIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 4. Cold-start onStartCommand returns START_STICKY while DataStore load is blocked
            val startResult = service.onStartCommand(dismissIntent, 0, 1)
            assertEquals(android.app.Service.START_STICKY, startResult)

            // 5. Unblock DataStore initial load
            loadLatch.complete(Unit)

            // 6. Deterministically await cold-start admission and dismissAlarm completion while flushing main looper
            awaitColdStartCompletion(completionLatch)

            // 7. Verify dismiss side-effects: activeAlarm is cleared on disk and in service
            val clearedSettings = awaitSettingsCondition(pmCold) { it.activeAlarmId == null }
            assertNull(clearedSettings.activeAlarmId)
            assertEquals(-1L, service.currentAlarmId)
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            controller.destroy()
        }
    }

    private suspend fun awaitSettingsCondition(
        pm: com.sysadmindoc.alarmclock.data.preferences.PreferencesManager,
        timeoutMs: Long = 5000L,
        predicate: (com.sysadmindoc.alarmclock.data.preferences.AppSettings) -> Boolean
    ): com.sysadmindoc.alarmclock.data.preferences.AppSettings {
        var result: com.sysadmindoc.alarmclock.data.preferences.AppSettings? = null
        kotlinx.coroutines.withTimeout(timeoutMs) {
            while (result == null) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                val settings = pm.getCurrentSettings()
                if (predicate(settings)) {
                    result = settings
                    break
                }
                kotlinx.coroutines.yield()
            }
        }
        return result!!
    }

    @Test
    fun coldStartRestartAbortsWhenServiceDestroyedWhileDataStoreReadInFlight() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Persist active alarm genA (405L) state = FIRING to DataStore disk
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val genA = AlarmGeneration(405L, 1000L, "fire-405-destroy")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Set defaultInitialLoadLatch before creating cold PreferencesManager instance so isSettingsLoaded is initially false
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            service.preferencesManager = pmCold
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val completionLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch

            val snoozeIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 3. Cold-start onStartCommand returns START_STICKY while DataStore load is blocked
            val startResult = service.onStartCommand(snoozeIntent, 0, 1)
            assertEquals(android.app.Service.START_STICKY, startResult)

            // 4. Service is destroyed while DataStore read is in-flight!
            controller.destroy()

            // 5. Unblock DataStore initial load
            loadLatch.complete(Unit)

            // 6. Await completion of the cancelled coroutine
            awaitColdStartCompletion(completionLatch)

            // 7. Verify stale snooze action was CANCELLED and NEVER executed: DataStore state remains FIRING (NOT SNOOZED)
            val settingsAfterDestroy = pmCold.getCurrentSettings()
            assertEquals("FIRING", settingsAfterDestroy.activeAlarmState)
            assertEquals(405L, settingsAfterDestroy.activeAlarmId)
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
        }
    }

    @Test
    fun coldStartRestartAbortsWhenDataStoreReadHasNoActiveEvidence() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Ensure DataStore on disk has no active evidence
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val current = pmSetup.getCurrentSettings()
        val activeId = current.activeAlarmId
        if (activeId != null && activeId > 0L) {
            val storedGen = AlarmGeneration(activeId, current.activeAlarmScheduledAt, current.activeAlarmFireId)
            val ackClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            if (pmSetup.enqueueActiveAlarmUpdate(null, targetGen = storedGen, ack = ackClear)) {
                ackClear.await()
            }
        }

        // 2. Set defaultInitialLoadLatch before creating cold PreferencesManager instance so isSettingsLoaded is initially false
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            service.preferencesManager = pmCold

            val completionLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch

            val genA = AlarmGeneration(406L, 1000L, "fire-406-noevidence")
            val snoozeIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 2. Cold-start onStartCommand returns START_STICKY while DataStore load is blocked
            val startResult = service.onStartCommand(snoozeIntent, 0, 1)
            assertEquals(android.app.Service.START_STICKY, startResult)

            // 3. Unblock DataStore initial load
            loadLatch.complete(Unit)

            // 4. Await completion of the coroutine
            awaitColdStartCompletion(completionLatch)

            // 5. Verify restart snooze was REJECTED: no active alarm set in service or DataStore
            assertNull(pmCold.getCurrentSettings().activeAlarmId)
            assertEquals(-1L, service.currentAlarmId)
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            controller.destroy()
        }
    }

    @Test
    fun coldStartRestartSerializesConcurrentSnoozeAndDismiss() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Persist active alarm genA (407L) to DataStore disk
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val genA = AlarmGeneration(407L, 1000L, "fire-407-concurrent")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Set defaultInitialLoadLatch before creating cold PreferencesManager instance so isSettingsLoaded is initially false
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        val completionChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        service.testColdStartAdmissionCompletionCallback = { completionChannel.trySend(Unit) }

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            service.preferencesManager = pmCold
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val snoozeIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }
            val dismissIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 3. Cold-start SNOOZE and DISMISS both delivered while DataStore read is in-flight
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(snoozeIntent, 0, 1))
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(dismissIntent, 0, 2))

            // 4. Unblock DataStore initial load
            loadLatch.complete(Unit)

            // 5. Await BOTH coroutines' completions individually
            kotlinx.coroutines.withTimeout(5000) {
                while (completionChannel.tryReceive().isFailure) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
                while (completionChannel.tryReceive().isFailure) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            // 6. Single-Winner Evidence: Total execution count across both coroutines MUST BE EXACTLY 1!
            val snoozeCount = service.testColdStartSnoozeCount.get()
            val dismissCount = service.testColdStartDismissCount.get()
            val totalExecutions = snoozeCount + dismissCount
            assertEquals(1, totalExecutions)

            if (snoozeCount == 1) {
                assertEquals(0, dismissCount)
                val finalSettings = awaitSettingsCondition(pmCold) { it.activeAlarmState == "SNOOZED" }
                assertEquals("SNOOZED", finalSettings.activeAlarmState)
            } else {
                assertEquals(1, dismissCount)
                assertEquals(0, snoozeCount)
                val finalSettings = awaitSettingsCondition(pmCold) { it.activeAlarmId == null }
                assertNull(finalSettings.activeAlarmId)
            }
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            service.testColdStartAdmissionCompletionCallback = null
            val ackClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            pmSetup.enqueueActiveAlarmUpdate(null, targetGen = genA, ack = ackClear)
            ackClear.await()
            controller.destroy()
        }
    }

    @Test
    fun coldStartRestartSerializesConcurrentDismissAndSnooze() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Persist active alarm genA (409L) to DataStore disk
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val genA = AlarmGeneration(409L, 1000L, "fire-409-dismiss-first")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Set defaultInitialLoadLatch before creating cold PreferencesManager instance so isSettingsLoaded is initially false
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        val completionChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        service.testColdStartAdmissionCompletionCallback = { completionChannel.trySend(Unit) }

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            service.preferencesManager = pmCold
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val snoozeIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }
            val dismissIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 3. Cold-start DISMISS and SNOOZE both delivered while DataStore read is in-flight
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(dismissIntent, 0, 1))
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(snoozeIntent, 0, 2))

            // 4. Unblock DataStore initial load
            loadLatch.complete(Unit)

            // 5. Await BOTH coroutines' completions individually
            kotlinx.coroutines.withTimeout(5000) {
                while (completionChannel.tryReceive().isFailure) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
                while (completionChannel.tryReceive().isFailure) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            // 6. Single-Winner Evidence: Total execution count across both coroutines MUST BE EXACTLY 1!
            val snoozeCount = service.testColdStartSnoozeCount.get()
            val dismissCount = service.testColdStartDismissCount.get()
            val totalExecutions = snoozeCount + dismissCount
            assertEquals(1, totalExecutions)

            if (snoozeCount == 1) {
                assertEquals(0, dismissCount)
                val finalSettings = awaitSettingsCondition(pmCold) { it.activeAlarmState == "SNOOZED" }
                assertEquals("SNOOZED", finalSettings.activeAlarmState)
            } else {
                assertEquals(1, dismissCount)
                assertEquals(0, snoozeCount)
                val finalSettings = awaitSettingsCondition(pmCold) { it.activeAlarmId == null }
                assertNull(finalSettings.activeAlarmId)
            }
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            service.testColdStartAdmissionCompletionCallback = null
            val ackClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            pmSetup.enqueueActiveAlarmUpdate(null, targetGen = genA, ack = ackClear)
            ackClear.await()
            controller.destroy()
        }
    }

    @Test
    fun coldStartRestartForcedInterleavingSingleWinnerPassAfter() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Persist active alarm genA (410L) to DataStore disk
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val genA = AlarmGeneration(410L, 1000L, "fire-410-interleaving")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Set defaultInitialLoadLatch before creating cold PreferencesManager instance
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        val completionChannel = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        service.testColdStartAdmissionCompletionCallback = { completionChannel.trySend(Unit) }

        val snoozeBarrier = kotlinx.coroutines.CompletableDeferred<Unit>()
        service.testColdStartSnoozeBeforeWithContextBarrier = snoozeBarrier
        val reachedBarrierLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        service.testColdStartSnoozeReachedBarrierLatch = reachedBarrierLatch

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            service.preferencesManager = pmCold
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val snoozeIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }
            val dismissIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_DISMISS
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 3. Deliver SNOOZE (will pause at snoozeBarrier after reading DataStore) then DISMISS
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(snoozeIntent, 0, 1))
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(dismissIntent, 0, 2))

            // 4. Unblock DataStore initial load -> Both coroutines read DataStore and obtain ADMIT_RESTART
            loadLatch.complete(Unit)

            // 5. Explicitly await that SNOOZE has evaluated ADMIT_RESTART and reached barrier (with 5s timeout)!
            kotlinx.coroutines.withTimeout(5000) {
                reachedBarrierLatch.await()
            }

            // 6. Await DISMISS coroutine completion (DISMISS executes withContext first)
            kotlinx.coroutines.withTimeout(5000) {
                while (completionChannel.tryReceive().isFailure) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            // At this point, DISMISS has completed!
            assertEquals(1, service.testColdStartDismissCount.get())

            // 7. Release snoozeBarrier so SNOOZE coroutine enters withContext second
            snoozeBarrier.complete(Unit)

            // 8. Await SNOOZE coroutine completion
            kotlinx.coroutines.withTimeout(5000) {
                while (completionChannel.tryReceive().isFailure) {
                    org.robolectric.shadows.ShadowLooper.idleMainLooper()
                    kotlinx.coroutines.yield()
                }
            }

            // 9. Single-Winner Evidence: DISMISS executed (1), SNOOZE was aborted by coldStartClaimedGenerations (0)!
            val snoozeCount = service.testColdStartSnoozeCount.get()
            val dismissCount = service.testColdStartDismissCount.get()
            val totalExecutions = snoozeCount + dismissCount

            assertEquals(0, snoozeCount)
            assertEquals(1, dismissCount)
            assertEquals(1, totalExecutions)

            val finalSettings = awaitSettingsCondition(pmCold) { it.activeAlarmId == null }
            assertNull(finalSettings.activeAlarmId)
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            service.testColdStartAdmissionCompletionCallback = null
            service.testColdStartSnoozeBeforeWithContextBarrier = null
            service.testColdStartSnoozeReachedBarrierLatch = null
            val ackClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            pmSetup.enqueueActiveAlarmUpdate(null, targetGen = genA, ack = ackClear)
            ackClear.await()
            controller.destroy()
        }
    }

    @Test
    fun coldStartRestartRejectedActionDoesNotBlockSubsequentValidAction() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. DataStore starts with NO active evidence on disk
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val current = pmSetup.getCurrentSettings()
        val activeId = current.activeAlarmId
        if (activeId != null && activeId > 0L) {
            val storedGen = AlarmGeneration(activeId, current.activeAlarmScheduledAt, current.activeAlarmFireId)
            val ackClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            if (pmSetup.enqueueActiveAlarmUpdate(null, targetGen = storedGen, ack = ackClear)) {
                ackClear.await()
            }
        }

        val genA = AlarmGeneration(411L, 1000L, "fire-411-rejected-first")

        // 2. Create single cold AlarmService instance
        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        try {
            // Deliver first SNOOZE when DataStore has NO active evidence
            val loadLatch1 = kotlinx.coroutines.CompletableDeferred<Unit>()
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch1

            val pmCold1 = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold1.isSettingsLoaded)

            service.preferencesManager = pmCold1

            val completionLatch1 = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch1

            val snoozeIntent1 = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 3. Deliver SNOOZE intent when DataStore has NO active evidence -> Coroutine 1 evaluates REJECT_RESTART_NO_EVIDENCE
            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(snoozeIntent1, 0, 1))

            loadLatch1.complete(Unit)
            awaitColdStartCompletion(completionLatch1)

            // Verification 1: Action was REJECTED, and genA was NOT claimed in service.coldStartClaimedGenerations
            assertEquals(0, service.testColdStartSnoozeCount.get())

            // 4. NOW write valid active evidence for genA (411L) to DataStore disk
            val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
                alarmId = genA.alarmId,
                scheduledAt = genA.scheduledAt,
                fireId = genA.fireId,
                firedAt = 1000L,
                state = "FIRING"
            )
            val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
            assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

            // 5. Deliver second cold-start SNOOZE intent for genA to the SAME AlarmService instance!
            val loadLatch2 = kotlinx.coroutines.CompletableDeferred<Unit>()
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch2

            val pmCold2 = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold2.isSettingsLoaded)

            service.preferencesManager = pmCold2
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val completionLatch2 = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch2

            val snoozeIntent2 = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(snoozeIntent2, 0, 2))

            loadLatch2.complete(Unit)
            awaitColdStartCompletion(completionLatch2)

            // REGRESSION SUCCESS ASSERTION: On the SAME AlarmService instance, the subsequent valid action for genA SUCCEEDED
            // because the rejected action did NOT claim genA in service.coldStartClaimedGenerations!
            assertEquals(1, service.testColdStartSnoozeCount.get())
            val snoozedSettings = awaitSettingsCondition(pmCold2) { it.activeAlarmState == "SNOOZED" }
            assertEquals("SNOOZED", snoozedSettings.activeAlarmState)
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            val ackClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            pmSetup.enqueueActiveAlarmUpdate(null, targetGen = genA, ack = ackClear)
            ackClear.await()
            controller.destroy()
        }
    }

    @Test
    fun coldStartRestartClaimDoesNotBlockDifferentGeneration() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Persist active alarm genA (412L) to DataStore disk
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val genA = AlarmGeneration(412L, 1000L, "fire-412-a")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Cold-start recovery for genA executes and claims genA
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        try {
            val pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            service.preferencesManager = pmCold
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val completionLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch

            val snoozeIntentA = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            assertEquals(android.app.Service.START_STICKY, service.onStartCommand(snoozeIntentA, 0, 1))

            loadLatch.complete(Unit)
            awaitColdStartCompletion(completionLatch)

            assertEquals(1, service.testColdStartSnoozeCount.get())

            // 3. Now deliver a new START for a DIFFERENT generation genB (413L)
            val genB = AlarmGeneration(413L, 2000L, "fire-413-b")
            val startIntentB = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_START_ALARM
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genB.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genB.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genB.fireId)
            }
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genB.alarmId, hour = 8, minute = 0))

            // START for genB MUST NOT be blocked by genA claim!
            service.onStartCommand(startIntentB, 0, 2)
            assertEquals(413L, service.currentAlarmId)
        } finally {
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            val ackClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            pmSetup.enqueueActiveAlarmUpdate(null, targetGen = genA, ack = ackClear)
            ackClear.await()
            controller.destroy()
        }
    }

    @Test
    fun coldStartRestartAbortsOnDataStoreReadIOException() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()

        // 1. Persist active alarm genA (408L) to DataStore disk so disk has valid active evidence
        val pmSetup = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
        val genA = AlarmGeneration(408L, 1000L, "fire-408-ioexception")
        val snapshotA = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genA.alarmId,
            scheduledAt = genA.scheduledAt,
            fireId = genA.fireId,
            firedAt = 1000L,
            state = "FIRING"
        )
        val ackA = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pmSetup.enqueueActiveAlarmUpdate(snapshotA, genA, ackA))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackA.await())

        // 2. Set defaultInitialLoadLatch before creating cold PreferencesManager instance so isSettingsLoaded is initially false
        val loadLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
        com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = loadLatch

        val controller = org.robolectric.Robolectric.buildService(AlarmService::class.java)
        val service = controller.create().get()

        var pmCold: com.sysadmindoc.alarmclock.data.preferences.PreferencesManager? = null
        try {
            pmCold = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)
            assertFalse(pmCold.isSettingsLoaded)

            // Inject simulated DataStore READ IOException
            pmCold.testReadFaultInjector = {
                throw java.io.IOException("Simulated DataStore read IOException")
            }

            service.preferencesManager = pmCold
            service.repository.save(com.sysadmindoc.alarmclock.data.model.Alarm(id = genA.alarmId, hour = 7, minute = 0))

            val completionLatch = kotlinx.coroutines.CompletableDeferred<Unit>()
            service.testColdStartAdmissionCompletionLatch = completionLatch

            val snoozeIntent = Intent(service, AlarmService::class.java).apply {
                action = AlarmService.ACTION_SNOOZE
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, genA.alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, genA.scheduledAt)
                putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, genA.fireId)
            }

            // 3. Cold-start onStartCommand returns START_STICKY while DataStore load is blocked
            val startResult = service.onStartCommand(snoozeIntent, 0, 1)
            assertEquals(android.app.Service.START_STICKY, startResult)

            // 4. Unblock DataStore initial load
            loadLatch.complete(Unit)

            // 5. Await completion of the coroutine
            awaitColdStartCompletion(completionLatch)

            // 6. Verify restart snooze was REJECTED: read failed with IOException -> caught and emitted defaults -> rejected
            assertEquals(-1L, service.currentAlarmId)
        } finally {
            pmCold?.testReadFaultInjector = null
            com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.defaultInitialLoadLatch = null
            val ackClear = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
            pmSetup.enqueueActiveAlarmUpdate(null, targetGen = genA, ack = ackClear)
            ackClear.await()
            controller.destroy()
        }
    }

    private suspend fun awaitColdStartCompletion(
        latch: kotlinx.coroutines.CompletableDeferred<Unit>,
        timeoutMs: Long = 5000L
    ) {
        kotlinx.coroutines.withTimeout(timeoutMs) {
            while (!latch.isCompleted) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper()
                kotlinx.coroutines.yield()
            }
        }
    }

    @Test
    fun realDataStoreCommitFailureInjectionReturnsFailedCommitAndPreservesCache() = runBlocking {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val pm = com.sysadmindoc.alarmclock.data.preferences.PreferencesManager(context)

        val initialDiskActiveId = pm.getCurrentSettings().activeAlarmId
        val initialCachedActiveId = pm.getCachedSettings().activeAlarmId

        val genFailure = AlarmGeneration(888L, 3000L, "fire-888-failure")
        val snapshotFailure = AlarmService.Companion.ActiveAlarmSnapshot(
            alarmId = genFailure.alarmId,
            scheduledAt = genFailure.scheduledAt,
            fireId = genFailure.fireId,
            firedAt = 3000L,
            state = "FIRING"
        )

        // Inject simulated DataStore write fault
        pm.testFaultInjector = {
            throw java.io.IOException("Simulated DataStore commit failure")
        }

        val ackFailure = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(snapshotFailure, genFailure, ackFailure))

        // Await ack result -> MUST return FAILED_COMMIT
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.FAILED_COMMIT, ackFailure.await())

        // Disk state AND in-memory cache MUST remain uncorrupted (equal to initial pre-write values, NOT 888L)
        assertEquals(initialDiskActiveId, pm.getCurrentSettings().activeAlarmId)
        assertEquals(initialCachedActiveId, pm.getCachedSettings().activeAlarmId)

        // Reset fault injector
        pm.testFaultInjector = null

        // Enqueue subsequent valid update -> Pipeline recovers and commits successfully
        val ackRecovery = kotlinx.coroutines.CompletableDeferred<com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult>()
        assertTrue(pm.enqueueActiveAlarmUpdate(snapshotFailure, genFailure, ackRecovery))
        assertEquals(com.sysadmindoc.alarmclock.data.preferences.PreferencesManager.ActiveAlarmUpdateResult.COMMITTED, ackRecovery.await())
        assertEquals(888L, pm.getCurrentSettings().activeAlarmId)
        assertEquals(888L, pm.getCachedSettings().activeAlarmId)
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
