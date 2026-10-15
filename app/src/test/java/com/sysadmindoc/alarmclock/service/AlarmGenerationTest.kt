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

    // --- Production Seam & Async Callback Orchestration Tests (Required Scenarios 1-12) ---

    @Test
    fun normalOnPreparedStartsPlayerOnce() {
        // Scenario 1: 正常onPrepared → startが1回
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer()
        var publishedPlayer: AlarmPlaybackPlayer? = null

        val success = handleLegacyOnPrepared(
            authority = auth,
            currentGen = genA,
            token = tokenA,
            player = player,
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackConsumer = {}
        )

        assertTrue(success)
        assertEquals(1, player.startCount)
        assertEquals(0, player.releaseCount)
        assertEquals(player, publishedPlayer)
    }

    @Test
    fun onPreparedStartExceptionReleasesPlayerAndTriggersFallbackOnce() {
        // Scenario 2: onPrepared内のstart例外 → 旧Player解放・Fallback要求1回
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer().apply { throwOnStart = true }
        var publishedPlayer: AlarmPlaybackPlayer? = null
        var fallbackTokenReceived: PlaybackAttemptToken? = null

        val success = handleLegacyOnPrepared(
            authority = auth,
            currentGen = genA,
            token = tokenA,
            player = player,
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackConsumer = { fallbackTokenReceived = it }
        )

        assertFalse(success)
        assertEquals(1, player.releaseCount)
        assertNull(publishedPlayer)
        assertNotNull(fallbackTokenReceived)
    }

    @Test
    fun onPreparedSetVolumeExceptionReleasesPlayerAndTriggersFallbackOnce() {
        // Scenario 3: setVolume例外 → 旧Player解放・Fallback要求1回
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer().apply { throwOnVolume = true }
        var publishedPlayer: AlarmPlaybackPlayer? = null
        var fallbackTokenReceived: PlaybackAttemptToken? = null

        val success = handleLegacyOnPrepared(
            authority = auth,
            currentGen = genA,
            token = tokenA,
            player = player,
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackConsumer = { fallbackTokenReceived = it }
        )

        assertFalse(success)
        assertEquals(1, player.releaseCount)
        assertNull(publishedPlayer)
        assertNotNull(fallbackTokenReceived)
    }

    @Test
    fun onPreparedExceptionFollowedByDelayedOnErrorPreventsDuplicateFallback() {
        // Scenario 4: onPrepared例外後のonError → Fallback重複なし
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer().apply { throwOnStart = true }
        var fallbackCount = 0

        // 1. onPrepared throws exception -> triggers fallback 1 time
        handleLegacyOnPrepared(
            authority = auth,
            currentGen = genA,
            token = tokenA,
            player = player,
            currentPlaybackSupplier = { null },
            publishPlaybackConsumer = {},
            clearPlaybackConsumer = {},
            triggerFallbackConsumer = { fallbackCount++ }
        )
        assertEquals(1, fallbackCount)

        // 2. Delayed onError fires for same player -> claims handoff, but claim is one-shot -> 0 additional fallback!
        handleLegacyOnError(
            authority = auth,
            currentGen = genA,
            token = tokenA,
            player = player,
            currentPlaybackSupplier = { null },
            clearPlaybackConsumer = {},
            triggerFallbackConsumer = { fallbackCount++ }
        )

        assertEquals(1, fallbackCount) // Still exactly 1 fallback!
    }

    @Test
    fun onErrorFollowedByDelayedOnPreparedRejectsOldPlayerStart() {
        // Scenario 5: onError後のonPrepared → 旧Player開始なし
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer()
        var fallbackCount = 0

        // 1. onError fires first -> claims handoff & activates fallback token
        handleLegacyOnError(
            authority = auth,
            currentGen = genA,
            token = tokenA,
            player = player,
            currentPlaybackSupplier = { null },
            clearPlaybackConsumer = {},
            triggerFallbackConsumer = { fallbackCount++ }
        )
        assertEquals(1, fallbackCount)

        // 2. Delayed onPrepared fires for old player -> publishPlaybackIfActive returns false!
        val preparedResult = handleLegacyOnPrepared(
            authority = auth,
            currentGen = genA,
            token = tokenA,
            player = player,
            currentPlaybackSupplier = { null },
            publishPlaybackConsumer = {},
            clearPlaybackConsumer = {},
            triggerFallbackConsumer = { fallbackCount++ }
        )

        assertFalse(preparedResult)
        assertEquals(0, player.startCount)
        assertEquals(2, player.releaseCount) // Initial onError release + onPrepared rejection release
        assertEquals(1, fallbackCount) // Fallback not re-triggered
    }

    @Test
    fun dismissAfterPrepareRejectsOldPlayerStartAndReleasesResource() {
        // Scenario 6: Dismiss後のonPrepared → startなし・旧Playerのみ解放
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val player = ControllableFakePlayer()

        // User dismisses alarm -> tokens invalidated
        auth.invalidateTokens()

        var publishedPlayer: AlarmPlaybackPlayer? = null
        val preparedResult = handleLegacyOnPrepared(
            authority = auth,
            currentGen = genA,
            token = tokenA,
            player = player,
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null },
            triggerFallbackConsumer = {}
        )

        assertFalse(preparedResult)
        assertEquals(0, player.startCount)
        assertEquals(1, player.releaseCount)
        assertNull(publishedPlayer)
    }

    @Test
    fun newStartFollowedByOldOnErrorPreservesNewPlayerState() {
        // Scenario 7: 新START後のonError → 新Playerの共有状態維持
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")

        val tokenA = auth.mintNextToken(genA)
        val playerA = ControllableFakePlayer()

        // New START tokenB arrives with new playerB
        val genB = AlarmGeneration(42L, 2000L, "fire-42-b")
        auth.mintNextToken(genB)
        val playerB = ControllableFakePlayer()
        var currentPlayback: AlarmPlaybackPlayer? = playerB

        // Delayed onError fires for old playerA
        handleLegacyOnError(
            authority = auth,
            currentGen = genB,
            token = tokenA,
            player = playerA,
            currentPlaybackSupplier = { currentPlayback },
            clearPlaybackConsumer = { currentPlayback = null },
            triggerFallbackConsumer = {}
        )

        // PlayerA released, but new playerB remains active in currentPlayback!
        assertEquals(1, playerA.releaseCount)
        assertEquals(playerB, currentPlayback)
    }

    @Test
    fun normalDefaultFallbackOnPreparedStartsPlayerOnce() {
        // Scenario 8: Default Fallbackの正常準備 → startが1回
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)
        assertNotNull(fallbackToken)

        val fallbackPlayer = ControllableFakePlayer()
        var publishedPlayer: AlarmPlaybackPlayer? = null

        val success = handleDefaultFallbackOnPrepared(
            authority = auth,
            currentGen = genA,
            fallbackToken = fallbackToken!!,
            player = fallbackPlayer,
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null }
        )

        assertTrue(success)
        assertEquals(1, fallbackPlayer.startCount)
        assertEquals(0, fallbackPlayer.releaseCount)
        assertEquals(fallbackPlayer, publishedPlayer)
    }

    @Test
    fun defaultFallbackOnPreparedExceptionReleasesPlayerWithoutInfiniteRetry() {
        // Scenario 9: Default FallbackのonPrepared例外 → release・再Fallbackなし
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)

        val fallbackPlayer = ControllableFakePlayer().apply { throwOnStart = true }
        var publishedPlayer: AlarmPlaybackPlayer? = null

        val success = handleDefaultFallbackOnPrepared(
            authority = auth,
            currentGen = genA,
            fallbackToken = fallbackToken!!,
            player = fallbackPlayer,
            currentPlaybackSupplier = { publishedPlayer },
            publishPlaybackConsumer = { publishedPlayer = it },
            clearPlaybackConsumer = { publishedPlayer = null }
        )

        assertFalse(success)
        assertEquals(1, fallbackPlayer.releaseCount)
        assertNull(publishedPlayer)
    }

    @Test
    fun defaultFallbackSynchronousSetupExceptionReleasesPlayerAndClearsSharedRef() {
        // Scenario 10 & 11: Default Fallbackの同期セットアップ例外 → release・共有参照クリア・Incident記録
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        val handoff = auth.tryClaimFallbackHandoff(genA, tokenA)
        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff!!)

        val fallbackPlayer = ControllableFakePlayer()
        var currentPlayback: AlarmPlaybackPlayer? = fallbackPlayer

        // Simulate setup exception in Default Fallback (e.g. setDataSource throws)
        handleDefaultFallbackSetupException(
            authority = auth,
            currentGen = genA,
            fallbackToken = fallbackToken!!,
            player = fallbackPlayer,
            currentPlaybackSupplier = { currentPlayback },
            clearPlaybackConsumer = { currentPlayback = null }
        )

        assertEquals(1, fallbackPlayer.releaseCount)
        assertNull(currentPlayback)
    }

    @Test
    fun concurrentWatchdogAndPlayerErrorDoNotTriggerDuplicateFallback() {
        // Scenario 12: WatchdogとPlayer Errorが競合してもFallback重複なし
        val auth = AlarmPlaybackAttemptAuthority()
        val genA = AlarmGeneration(42L, 1000L, "fire-42-a")
        val tokenA = auth.mintNextToken(genA)

        // 1. Watchdog timeout fires -> claims handoff
        val handoff1 = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNotNull(handoff1)

        val fallbackToken = auth.tryConsumeFallbackHandoffAndActivate(genA, handoff1!!)
        assertNotNull(fallbackToken)

        // 2. Player onError fires concurrently for same tokenA -> claim fails
        val handoff2 = auth.tryClaimFallbackHandoff(genA, tokenA)
        assertNull(handoff2)

        assertEquals(fallbackToken, auth.activeAttemptToken())
    }

    // --- Production Callback Orchestrators for Testing ---

    private fun handleLegacyOnPrepared(
        authority: AlarmPlaybackAttemptAuthority,
        currentGen: AlarmGeneration?,
        token: PlaybackAttemptToken,
        player: ControllableFakePlayer,
        currentPlaybackSupplier: () -> AlarmPlaybackPlayer?,
        publishPlaybackConsumer: (AlarmPlaybackPlayer) -> Unit,
        clearPlaybackConsumer: () -> Unit,
        triggerFallbackConsumer: (PlaybackAttemptToken) -> Unit
    ): Boolean {
        return runCatching {
            var published = false
            authority.publishPlaybackIfActive(currentGen, token) {
                publishPlaybackConsumer(player)
                published = true
            }
            if (published) {
                player.setVolume(1f, 1f)
                player.start()
                true
            } else {
                player.stopAndRelease()
                false
            }
        }.getOrElse { _ ->
            player.stopAndRelease()
            if (authority.shouldReleaseGlobalPlayback(currentGen, token, currentPlaybackSupplier(), player)) {
                clearPlaybackConsumer()
            }
            val handoff = authority.tryClaimFallbackHandoff(currentGen, token)
            if (handoff != null) {
                val fallbackToken = authority.tryConsumeFallbackHandoffAndActivate(currentGen, handoff)
                if (fallbackToken != null) {
                    triggerFallbackConsumer(fallbackToken)
                }
            }
            false
        }
    }

    private fun handleLegacyOnError(
        authority: AlarmPlaybackAttemptAuthority,
        currentGen: AlarmGeneration?,
        token: PlaybackAttemptToken,
        player: ControllableFakePlayer,
        currentPlaybackSupplier: () -> AlarmPlaybackPlayer?,
        clearPlaybackConsumer: () -> Unit,
        triggerFallbackConsumer: (PlaybackAttemptToken) -> Unit
    ): Boolean {
        player.stopAndRelease()
        if (authority.shouldReleaseGlobalPlayback(currentGen, token, currentPlaybackSupplier(), player)) {
            clearPlaybackConsumer()
        }
        val handoff = authority.tryClaimFallbackHandoff(currentGen, token)
        if (handoff != null) {
            val fallbackToken = authority.tryConsumeFallbackHandoffAndActivate(currentGen, handoff)
            if (fallbackToken != null) {
                triggerFallbackConsumer(fallbackToken)
            }
        }
        return true
    }

    private fun handleDefaultFallbackOnPrepared(
        authority: AlarmPlaybackAttemptAuthority,
        currentGen: AlarmGeneration?,
        fallbackToken: PlaybackAttemptToken,
        player: ControllableFakePlayer,
        currentPlaybackSupplier: () -> AlarmPlaybackPlayer?,
        publishPlaybackConsumer: (AlarmPlaybackPlayer) -> Unit,
        clearPlaybackConsumer: () -> Unit
    ): Boolean {
        return runCatching {
            var published = false
            authority.publishPlaybackIfActive(currentGen, fallbackToken) {
                publishPlaybackConsumer(player)
                published = true
            }
            if (published) {
                player.setVolume(1f, 1f)
                player.start()
                true
            } else {
                player.stopAndRelease()
                false
            }
        }.getOrElse { _ ->
            player.stopAndRelease()
            if (authority.shouldReleaseGlobalPlayback(currentGen, fallbackToken, currentPlaybackSupplier(), player)) {
                clearPlaybackConsumer()
            }
            false
        }
    }

    private fun handleDefaultFallbackSetupException(
        authority: AlarmPlaybackAttemptAuthority,
        currentGen: AlarmGeneration?,
        fallbackToken: PlaybackAttemptToken,
        player: ControllableFakePlayer,
        currentPlaybackSupplier: () -> AlarmPlaybackPlayer?,
        clearPlaybackConsumer: () -> Unit
    ) {
        player.stopAndRelease()
        if (authority.shouldReleaseGlobalPlayback(currentGen, fallbackToken, currentPlaybackSupplier(), player)) {
            clearPlaybackConsumer()
        }
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
