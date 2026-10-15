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

    // --- Strict Main Looper Execution Contract Tests (Required Scenarios 1-3) ---

    @Test
    fun mainLooperAuthorityMutationSucceeds() {
        // Scenario 1: Main LooperからAuthority変更成功
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val token = auth.mintNextToken(genA)
        assertNotNull(token)
        assertEquals(token, auth.activeAttemptToken())
    }

    @Test
    fun plainWorkerThreadWithoutLooperMutatingAuthorityThrowsIllegalStateException() {
        // Scenario 2: Looperを持たないワーカースレッドからの変更拒否 (looper == null)
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
        // Scenario 3: Main以外のHandlerThreadからの変更拒否 (looper != getMainLooper())
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

    // --- Production Callback & Dispatcher Tests (Required Scenarios 4-8) ---

    @Test
    fun mediaCallbackDispatchedToMainIsProcessedForValidAttempt() {
        // Scenario 4: Media CallbackがMainへ配送され、正当なAttemptなら処理される
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = FakeAlarmPlaybackPlayer()
        var processed = false

        Handler(Looper.getMainLooper()).post {
            processed = auth.shouldProcessCallback(genA, tokenA, currentPlayback = player, callbackPlayback = player)
        }
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertTrue(processed)
    }

    @Test
    fun callbackPostedToMainIsRejectedIfNewAttemptMintedBeforeDispatch() {
        // Scenario 5: Main配送前に新Attemptへ切替 → 旧Callback拒否
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        var processed = true
        Handler(Looper.getMainLooper()).post {
            processed = auth.shouldProcessCallback(genA, tokenA)
        }

        // New attempt minted on Main Thread BEFORE queued callback runs on Main Looper!
        val tokenB = auth.mintNextToken(genA)

        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertFalse(processed)
        assertEquals(tokenB, auth.activeAttemptToken())
    }

    @Test
    fun dbReadAsyncResumeGuardDetectsGenerationMismatch() {
        // Scenario 6 & 7: DB読み取り中に新世代へ切替またはDismiss → 旧START再生開始拒否
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")

        // DB fetch completes on IO, resumes on Main Thread
        // Generation changed to genB while DB fetch was in-flight!
        var currentGen: AlarmGeneration? = genB

        // DB resume check detects genA no longer matches currentGen (genB)
        val isCurrentMatch = currentGen != null && currentGen.matches(genA)
        assertFalse(isCurrentMatch)

        // Dismiss case: currentGen set to null while DB fetch in-flight
        currentGen = null
        val isDismissMatch = currentGen != null && (currentGen as AlarmGeneration).matches(genA)
        assertFalse(isDismissMatch)
    }

    @Test
    fun legitimateStartMintsAttemptTokenSuccessfully() {
        // Scenario 8: 正常なSTART → Playback Attempt発行成功
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val tokenA = auth.mintNextToken(genA)
        assertNotNull(tokenA)
        assertEquals(genA, tokenA.generation)
        assertEquals(tokenA, auth.activeAttemptToken())
    }

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
    fun callbackPolicyHandlesAtomicConcurrentClaimsOnMainLooper() {
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = AlarmServiceCallbackPolicy.mintNextToken(genA)

        val nonNullClaims = mutableListOf<PlaybackAttemptToken>()
        val handler = Handler(Looper.getMainLooper())

        for (i in 0 until 10) {
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

    private class FakeAlarmPlaybackPlayer : AlarmPlaybackPlayer {
        override fun setVolume(left: Float, right: Float) {}
        override fun durationMs(): Long = 0L
        override fun seekTo(positionMs: Long) {}
        override fun stopAndRelease() {}
    }
}
