package com.sysadmindoc.alarmclock.directboot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * C7-R1 Registration Truth & Safety Test Suite.
 * Covers all 16 mandatory behavioral invariants across normal registration,
 * exact fallback, failure classification, DE persistence preservation,
 * DEFAULT_DENY legacy continuity, DurableOwner fail-closed, post-OS recheck,
 * shared PendingIntent stale-cancel safety, cancellation truth, process-death
 * simulation, and Flag OFF compatibility.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DirectBootAlarmCacheRegistrationTruthTest {

    private lateinit var appContext: Context
    private lateinit var deContext: Context
    private val now = 1_700_000_000_000L

    private fun alarm(id: Long) = Alarm(
        id = id,
        hour = 7,
        minute = 0
    )

    private fun wrapWithAlarmManager(alarmManager: AlarmManager?): Context {
        val baseDe = appContext.createDeviceProtectedStorageContext()
        val deWrapper = object : ContextWrapper(baseDe) {
            override fun isDeviceProtectedStorage(): Boolean = true
            override fun createDeviceProtectedStorageContext(): Context = this
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE) return alarmManager
                return super.getSystemService(name)
            }
        }
        return object : ContextWrapper(appContext) {
            override fun isDeviceProtectedStorage(): Boolean = false
            override fun createDeviceProtectedStorageContext(): Context = deWrapper
            override fun getSystemService(name: String): Any? {
                if (name == Context.ALARM_SERVICE) return alarmManager
                return super.getSystemService(name)
            }
        }
    }

    private fun prefs() = deContext.getSharedPreferences("direct_boot_next_alarm", Context.MODE_PRIVATE)

    private fun v2Identity(
        alarmId: Long,
        triggerTime: Long,
        generation: Int = 1,
        token: String = "tok-$alarmId",
        restoreEpoch: Long = 1L
    ) = DirectBootV2NormalIntentIdentity(
        alarmId = alarmId,
        generation = generation,
        instanceToken = token,
        triggerTime = triggerTime,
        restoreEpoch = restoreEpoch,
        kind = DirectBootQueueItem.KIND_NORMAL
    )

    private fun queueItem(
        alarmId: Long,
        triggerTime: Long,
        generation: Int = 1,
        token: String = "tok-$alarmId",
        restoreEpoch: Long = 1L
    ) = DirectBootQueueItem(
        kind = DirectBootQueueItem.KIND_NORMAL,
        alarmId = alarmId,
        triggerTime = triggerTime,
        generation = generation,
        instanceToken = token,
        restoreEpoch = restoreEpoch,
        updatedAt = now
    )

    private class SimulatedProcessDeathException : RuntimeException("Simulated process death")

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        deContext = appContext.createDeviceProtectedStorageContext()
        prefs().edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    @Test
    fun t01_alarmClockRegistrationSuccess_returnsArmedAlarmClockAndUsesVerifiedSnapshot() {
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(101L), triggerTime, now)
        val expectedSnapshot = requireNotNull(DirectBootAlarmCache.read(appContext))

        var lockHeldDuringOsCall = true
        val realAm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ctx = wrapWithAlarmManager(realAm)

        val result = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now,
            canScheduleExactAlarms = { true },
            alarmClockRegistrar = { am, info, op ->
                lockHeldDuringOsCall = Thread.holdsLock(DirectBootStorageLock.lock)
                am.setAlarmClock(info, op)
            }
        )

        assertFalse("AlarmManager Binder IPC must NOT hold DirectBootStorageLock", lockHeldDuringOsCall)
        assertTrue(result is DirectBootFallbackScheduleResult.ArmedAlarmClock)
        val armed = result as DirectBootFallbackScheduleResult.ArmedAlarmClock
        assertTrue(armed.isArmed)
        assertEquals(OsRegistrationState.ARMED_CONFIRMED, armed.osState)
        assertEquals(101L, armed.alarmId)
        assertEquals(triggerTime, armed.triggerTime)

        val scheduled = Shadows.shadowOf(realAm).scheduledAlarms.last()
        val nextClock = requireNotNull(scheduled.alarmClockInfo)
        assertEquals(triggerTime, nextClock.triggerTime)

        val showIntentSaved = Shadows.shadowOf(nextClock.showIntent).savedIntent
        assertEquals(expectedSnapshot.alarmId, showIntentSaved.getLongExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L))
        assertEquals(expectedSnapshot.timeLabel, showIntentSaved.getStringExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL))

        val opIntentSaved = Shadows.shadowOf(scheduled.operation).savedIntent
        assertEquals(DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM, opIntentSaved.action)
        assertEquals(101L, opIntentSaved.getLongExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L))
        assertEquals(triggerTime, opIntentSaved.getLongExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, 0L))

        // Boolean compatibility wrapper agrees
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(wrapWithAlarmManager(mockk(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
        }), now))
    }

    @Test
    fun t02_alarmClockSecurityException_exactIdleFallbackSucceeds_neverCallsInexact() {
        val am = mockk<AlarmManager>(relaxed = true)
        every { am.canScheduleExactAlarms() } returns true
        val se = SecurityException("setAlarmClock denied")
        every { am.setAlarmClock(any(), any()) } throws se

        val ctx = wrapWithAlarmManager(am)
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(102L), triggerTime, now)

        var lockHeldInExactIdle = true
        val result = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now,
            exactIdleRegistrar = { alarmMgr, trig, op ->
                lockHeldInExactIdle = Thread.holdsLock(DirectBootStorageLock.lock)
                alarmMgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trig, op)
            }
        )

        assertFalse("exactIdleRegistrar must NOT hold DirectBootStorageLock", lockHeldInExactIdle)
        assertTrue(result is DirectBootFallbackScheduleResult.ArmedExactIdleFallback)
        val fallback = result as DirectBootFallbackScheduleResult.ArmedExactIdleFallback
        assertTrue(fallback.isArmed)
        assertEquals(OsRegistrationState.ARMED_CONFIRMED, fallback.osState)
        assertEquals(se, fallback.primarySecurityException)

        verify(exactly = 1) { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, any()) }
        verify(exactly = 0) { am.setAndAllowWhileIdle(any(), any(), any()) }
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(ctx, now))
    }

    @Test
    fun t03_bothAlarmClockAndExactIdleThrowSecurityException_returnsSecurityBlockedAndFalse() {
        val am = mockk<AlarmManager>(relaxed = true)
        every { am.canScheduleExactAlarms() } returns true
        every { am.setAlarmClock(any(), any()) } throws SecurityException("clock denied")
        every { am.setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("exact denied")

        val ctx = wrapWithAlarmManager(am)
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(103L), now + 60_000L, now)

        val result = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(result is DirectBootFallbackScheduleResult.SecurityBlocked)
        assertFalse(result.isArmed)
        assertEquals(OsRegistrationState.NOT_ARMED, result.osState)
        assertFalse(result.isRetryEligible)
        verify(exactly = 0) { am.setAndAllowWhileIdle(any(), any(), any()) }
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(ctx, now))
    }

    @Test
    fun t04_missingOperationOrShowPendingIntent_returnsPendingIntentUnavailableWithoutCallingOs() {
        val am = mockk<AlarmManager>(relaxed = true)
        every { am.canScheduleExactAlarms() } returns true
        val ctx = wrapWithAlarmManager(am)
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(104L), now + 60_000L, now)

        val opMissing = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now,
            operationPendingIntentProvider = { _, _ -> null }
        )
        assertTrue(opMissing is DirectBootFallbackScheduleResult.PendingIntentUnavailable)
        assertEquals(
            FallbackFailureStage.OPERATION_INTENT_UNAVAILABLE,
            (opMissing as DirectBootFallbackScheduleResult.PendingIntentUnavailable).stage
        )
        assertFalse(opMissing.isArmed)
        assertEquals(OsRegistrationState.NOT_ARMED, opMissing.osState)

        val showMissing = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now,
            showPendingIntentProvider = { _, _ -> null }
        )
        assertTrue(showMissing is DirectBootFallbackScheduleResult.PendingIntentUnavailable)
        assertEquals(
            FallbackFailureStage.SHOW_INTENT_UNAVAILABLE,
            (showMissing as DirectBootFallbackScheduleResult.PendingIntentUnavailable).stage
        )
        assertFalse(showMissing.isArmed)
        assertEquals(OsRegistrationState.NOT_ARMED, showMissing.osState)

        verify(exactly = 0) { am.setAlarmClock(any(), any()) }
        verify(exactly = 0) { am.setExactAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun t05_nonSecurityExceptionRegistrationFailure_returnsRegistrationFailedWithoutExactFallback() {
        val am = mockk<AlarmManager>(relaxed = true)
        every { am.canScheduleExactAlarms() } returns true
        every { am.setAlarmClock(any(), any()) } throws IllegalStateException("max 500 alarms")

        val ctx = wrapWithAlarmManager(am)
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(105L), now + 60_000L, now)

        val primaryFail = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(primaryFail is DirectBootFallbackScheduleResult.RegistrationFailed)
        primaryFail as DirectBootFallbackScheduleResult.RegistrationFailed
        assertEquals(FallbackFailureStage.ALARM_CLOCK_SET, primaryFail.stage)
        assertFalse(primaryFail.isArmed)
        assertTrue(primaryFail.isRetryEligible)
        assertEquals(OsRegistrationState.NOT_ARMED, primaryFail.osState)
        verify(exactly = 0) { am.setExactAndAllowWhileIdle(any(), any(), any()) }
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(ctx, now))

        // Fallback non-SecurityException after primary SecurityException
        val amFallbackFail = mockk<AlarmManager>(relaxed = true)
        every { amFallbackFail.canScheduleExactAlarms() } returns true
        every { amFallbackFail.setAlarmClock(any(), any()) } throws SecurityException("clock denied")
        every { amFallbackFail.setExactAndAllowWhileIdle(any(), any(), any()) } throws RuntimeException("binder dead")

        val fallbackFail = DirectBootAlarmCache.scheduleCachedAlarmWithResult(
            wrapWithAlarmManager(amFallbackFail),
            now
        )
        assertTrue(fallbackFail is DirectBootFallbackScheduleResult.RegistrationFailed)
        fallbackFail as DirectBootFallbackScheduleResult.RegistrationFailed
        assertEquals(FallbackFailureStage.EXACT_IDLE_FALLBACK_SET, fallbackFail.stage)
        assertNotNull(fallbackFail.primarySecurityException)
        assertTrue(fallbackFail.isRetryEligible)
        assertEquals(OsRegistrationState.NOT_ARMED, fallbackFail.osState)

        // Null AlarmManager
        val nullAmResult = DirectBootAlarmCache.scheduleCachedAlarmWithResult(
            wrapWithAlarmManager(null),
            now
        )
        assertTrue(nullAmResult is DirectBootFallbackScheduleResult.RegistrationFailed)
        assertEquals(
            FallbackFailureStage.ALARM_MANAGER_UNAVAILABLE,
            (nullAmResult as DirectBootFallbackScheduleResult.RegistrationFailed).stage
        )
    }

    @Test
    fun t06_exactAlarmPermissionDenied_returnsPermissionDeniedWithoutCallingOs() {
        val am = mockk<AlarmManager>(relaxed = true)
        every { am.canScheduleExactAlarms() } returns false

        val ctx = wrapWithAlarmManager(am)
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(106L), now + 60_000L, now)

        val result = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(result is DirectBootFallbackScheduleResult.PermissionDenied)
        assertFalse(result.isArmed)
        assertEquals(OsRegistrationState.NOT_ARMED, result.osState)
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(ctx, now))

        verify(exactly = 0) { am.setAlarmClock(any(), any()) }
        verify(exactly = 0) { am.setExactAndAllowWhileIdle(any(), any(), any()) }
        verify(exactly = 0) { am.setAndAllowWhileIdle(any(), any(), any()) }
    }

    @Test
    fun t07_failuresAndUnknownStatesPreserveV1SnapshotFiredWitnessAndV2Stores() {
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(107L), triggerTime, now)
        // Separate alarm fired witness in same prefs file
        DirectBootAlarmCache.recordFired(appContext, alarmId = 999L, triggerTime = now - 5_000L, firedAt = now - 4_000L)
        // V2 queue & marker
        assertTrue(DirectBootAlarmCache.writeQueue(appContext, listOf(queueItem(207L, now + 120_000L)), now))
        val markerWrite = DirectBootRegistrationMarker.write(
            appContext,
            DirectBootRegistrationMarker.createInitialIdle(
                activeIdentity = v2Identity(207L, now + 120_000L),
                nowEpochMs = now
            )
        )
        assertTrue(markerWrite.isSuccess)

        val initialSnapshot = requireNotNull(DirectBootAlarmCache.read(appContext))
        val initialQueueRaw = DirectBootAlarmCache.getRawQueuePayload(appContext)
        val initialMarkerRaw = prefs().getString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, null)

        // 1. PermissionDenied
        val deniedAm = mockk<AlarmManager>(relaxed = true) { every { canScheduleExactAlarms() } returns false }
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarmWithResult(wrapWithAlarmManager(deniedAm), now) is DirectBootFallbackScheduleResult.PermissionDenied)

        // 2. SecurityBlocked
        val blockedAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws SecurityException("denied")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("denied")
        }
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarmWithResult(wrapWithAlarmManager(blockedAm), now) is DirectBootFallbackScheduleResult.SecurityBlocked)

        // 3. RegistrationFailed
        val failedAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws IllegalStateException("fail")
        }
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarmWithResult(wrapWithAlarmManager(failedAm), now) is DirectBootFallbackScheduleResult.RegistrationFailed)

        // 4. RegistrationOutcomeUnknown via fired witness during registration (use separate alarm snapshot 108L vs witness 999L)
        val okAm = mockk<AlarmManager>(relaxed = true) { every { canScheduleExactAlarms() } returns true }
        val unknownResult = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = wrapWithAlarmManager(okAm),
            now = now,
            onAfterOsRegistrationBeforeRecheck = {
                DirectBootAlarmCache.saveIfEarlier(appContext, alarm(108L), now + 30_000L, now)
            }
        )
        assertTrue(unknownResult is DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown)

        // V1 snapshot was replaced ONLY by explicit saveIfEarlier(108L), not wiped; restore 107L and check witness/V2 stores
        assertEquals(initialQueueRaw, DirectBootAlarmCache.getRawQueuePayload(appContext))
        assertEquals(initialMarkerRaw, prefs().getString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, null))
        assertTrue(DirectBootAlarmCache.hasFiredWitness(appContext, 999L, now - 5_000L))
        assertEquals(108L, DirectBootAlarmCache.read(appContext)?.alarmId)

        // Also confirm snapshot preservation when only failure occurs
        DirectBootAlarmCache.clear(appContext)
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(107L), triggerTime, now)
        DirectBootAlarmCache.scheduleCachedAlarmWithResult(wrapWithAlarmManager(blockedAm), now)
        assertEquals(initialSnapshot, DirectBootAlarmCache.read(appContext))

        // Verify zero ungrounded legacy_reg_* keys were created
        val legacyRegKeys = prefs().all.keys.filter { it.startsWith("legacy_reg_") }
        assertTrue("No legacy_reg_* keys allowed in R1: $legacyRegKeys", legacyRegKeys.isEmpty())
    }

    @Test
    fun t08_defaultDenyWithV2QueueMarkerOrCorruptPayloads_continuesLegacyRegistrationNonDestructively() {
        // Verify public API exposes NO v2DeliveryCapable parameter
        val publicBooleanParams = DirectBootAlarmCache::class.java.declaredMethods
            .filter { it.name == "scheduleCachedAlarm" || it.name == "scheduleCachedAlarmWithResult" }
            .flatMap { it.parameterTypes.toList() }
        assertFalse(
            "Public API must not expose a Boolean v2DeliveryCapable toggle",
            publicBooleanParams.any { it == Boolean::class.javaPrimitiveType || it == Boolean::class.java }
        )

        val am = mockk<AlarmManager>(relaxed = true) { every { canScheduleExactAlarms() } returns true }
        val ctx = wrapWithAlarmManager(am)
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(108L), triggerTime, now)

        // (a) Matching occurrence in V2 Queue under DEFAULT_DENY -> Legacy continues with diagnostic
        assertTrue(DirectBootAlarmCache.writeQueue(ctx, listOf(queueItem(108L, triggerTime)), now))
        val resMatchingQueue = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(resMatchingQueue is DirectBootFallbackScheduleResult.ArmedAlarmClock)
        assertTrue(
            (resMatchingQueue as DirectBootFallbackScheduleResult.ArmedAlarmClock).v2Diagnostics
                .contains(V2DiagnosticFlag.QUEUE_MATCHING_OCCURRENCE_WHILE_DEFAULT_DENY)
        )

        // (b) Unrelated occurrence in V2 Queue -> Legacy continues with diagnostic
        assertTrue(DirectBootAlarmCache.writeQueue(ctx, listOf(queueItem(888L, triggerTime + 10_000L)), now))
        val resUnrelatedQueue = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(
            (resUnrelatedQueue as DirectBootFallbackScheduleResult.ArmedAlarmClock).v2Diagnostics
                .contains(V2DiagnosticFlag.QUEUE_UNRELATED_OCCURRENCE)
        )

        // (c) Marker IN_FLIGHT -> Legacy continues under DEFAULT_DENY
        val inFlightMarker = DirectBootRegistrationMarker(
            mainState = RegistrationMainState.IN_FLIGHT,
            phase = RegistrationPhase.REGISTERING_NEW,
            opSeq = 0L,
            leaseOwner = "reconciler-test",
            leaseEpochMs = now,
            registeringTarget = v2Identity(108L, triggerTime),
            updatedAtEpochMs = now
        )
        assertTrue(DirectBootRegistrationMarker.write(ctx, inFlightMarker).isSuccess)
        val resInFlight = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(
            (resInFlight as DirectBootFallbackScheduleResult.ArmedAlarmClock).v2Diagnostics
                .contains(V2DiagnosticFlag.MARKER_IN_FLIGHT_WHILE_DEFAULT_DENY)
        )

        // (d) Marker FAILED_BLOCKED -> Legacy continues
        val failedBlockedMarker = DirectBootRegistrationMarker(
            mainState = RegistrationMainState.FAILED_BLOCKED,
            phase = RegistrationPhase.NONE,
            failureReason = RegistrationFailureReason.SECURITY_EXCEPTION,
            opSeq = 1L,
            updatedAtEpochMs = now
        )
        assertTrue(DirectBootRegistrationMarker.write(ctx, failedBlockedMarker, expectedOpSeq = 1L).isSuccess)
        val resFailedBlocked = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(
            (resFailedBlocked as DirectBootFallbackScheduleResult.ArmedAlarmClock).v2Diagnostics
                .contains(V2DiagnosticFlag.MARKER_FAILED_BLOCKED)
        )

        // (e) Corrupt Queue, Corrupt Marker, Corrupt Owner -> Legacy continues AND corrupt bytes preserved untouched
        val corruptQueuePayload = "{corrupt_queue"
        val corruptMarkerPayload = "{corrupt_marker"
        val corruptOwnerPayload = "{corrupt_owner"
        prefs().edit()
            .putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptQueuePayload)
            .putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, corruptMarkerPayload)
            .putString(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER, corruptOwnerPayload)
            .commit()

        val resCorrupt = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(resCorrupt is DirectBootFallbackScheduleResult.ArmedAlarmClock)
        val diag = (resCorrupt as DirectBootFallbackScheduleResult.ArmedAlarmClock).v2Diagnostics
        assertTrue(diag.contains(V2DiagnosticFlag.QUEUE_CORRUPT))
        assertTrue(diag.contains(V2DiagnosticFlag.MARKER_CORRUPT))
        assertTrue(diag.contains(V2DiagnosticFlag.OWNER_CORRUPT_WHILE_DEFAULT_DENY))
        assertEquals(corruptQueuePayload, prefs().getString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, null))
        assertEquals(corruptMarkerPayload, prefs().getString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, null))
        assertEquals(corruptOwnerPayload, prefs().getString(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER, null))

        // (f) Simulated V2 delivery capable mode distinguishes AuthorityUnknown vs AuthorityRejected
        val resCapableCorruptOwner = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now,
            v2DeliveryCapable = true
        )
        assertTrue(resCapableCorruptOwner is DirectBootFallbackScheduleResult.AuthorityUnknown)

        prefs().edit().remove(DirectBootDurableOwnerStore.KEY_DIRECT_BOOT_DURABLE_OWNER).commit()
        assertTrue(DirectBootAlarmCache.clearQueue(ctx))
        assertTrue(DirectBootAlarmCache.writeQueue(ctx, listOf(queueItem(108L, triggerTime)), now))
        val validIdleWithActive = DirectBootRegistrationMarker.createInitialIdle(
            activeIdentity = v2Identity(108L, triggerTime),
            nowEpochMs = now
        )
        prefs().edit()
            .putString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, validIdleWithActive.toJsonString())
            .commit()
        val resCapableConfirmed = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now,
            v2DeliveryCapable = true
        )
        assertTrue(resCapableConfirmed is DirectBootFallbackScheduleResult.AuthorityRejected)
    }

    @Test
    fun t09_sameOccurrenceDurableOwnerHandoverOrV2Active_failsClosedAsAuthorityUnknown() {
        val am = mockk<AlarmManager>(relaxed = true) { every { canScheduleExactAlarms() } returns true }
        val ctx = wrapWithAlarmManager(am)
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(109L), triggerTime, now)

        // (a) Same-occurrence HANDOVER_PENDING
        val handoverOwner = DirectBootDurableOwner(
            state = OwnerState.HANDOVER_PENDING,
            ownerSeq = 0L,
            handoverClaim = HandoverClaim(
                handoverId = "ho-109",
                alarmId = 109L,
                triggerTime = triggerTime,
                generation = 1,
                instanceToken = "tok-109",
                restoreEpoch = 1L
            ),
            updatedAtEpochMs = now
        )
        assertTrue(DirectBootDurableOwnerStore.write(ctx, handoverOwner).isSuccess)

        val resHandover = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(resHandover is DirectBootFallbackScheduleResult.AuthorityUnknown)
        resHandover as DirectBootFallbackScheduleResult.AuthorityUnknown
        assertEquals(AuthorityUnknownReason.DURABLE_OWNER_SAME_OCCURRENCE_HANDOVER_PENDING, resHandover.reason)
        assertEquals(OsRegistrationState.UNKNOWN, resHandover.osState)
        assertFalse(resHandover.isArmed)
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(ctx, now))
        verify(exactly = 0) { am.setAlarmClock(any(), any()) }

        // (b) Same-occurrence V2_ACTIVE under DEFAULT_DENY
        val activeOwner = DirectBootDurableOwner(
            state = OwnerState.V2_ACTIVE,
            ownerSeq = 1L,
            activeSession = ActiveOwnerSession(
                alarmId = 109L,
                triggerTime = triggerTime,
                generation = 1,
                instanceToken = "tok-109",
                restoreEpoch = 1L
            ),
            updatedAtEpochMs = now
        )
        assertTrue(DirectBootDurableOwnerStore.write(ctx, activeOwner, expectedOwnerSeq = 1L).isSuccess)

        val resActive = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(resActive is DirectBootFallbackScheduleResult.AuthorityUnknown)
        resActive as DirectBootFallbackScheduleResult.AuthorityUnknown
        assertEquals(AuthorityUnknownReason.DURABLE_OWNER_SAME_OCCURRENCE_V2_ACTIVE, resActive.reason)
        assertEquals(OsRegistrationState.UNKNOWN, resActive.osState)
        assertFalse(resActive.isArmed)
        verify(exactly = 0) { am.setAlarmClock(any(), any()) }

        // (c) Unrelated occurrence in DurableOwner must NOT block Legacy alarm 109L
        val unrelatedOwner = DirectBootDurableOwner(
            state = OwnerState.V2_ACTIVE,
            ownerSeq = 2L,
            activeSession = ActiveOwnerSession(
                alarmId = 999L,
                triggerTime = triggerTime + 5_000L,
                generation = 1,
                instanceToken = "tok-999",
                restoreEpoch = 1L
            ),
            updatedAtEpochMs = now
        )
        assertTrue(DirectBootDurableOwnerStore.write(ctx, unrelatedOwner, expectedOwnerSeq = 2L).isSuccess)
        val resUnrelated = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(resUnrelated is DirectBootFallbackScheduleResult.ArmedAlarmClock)
        assertTrue(
            (resUnrelated as DirectBootFallbackScheduleResult.ArmedAlarmClock).v2Diagnostics
                .contains(V2DiagnosticFlag.OWNER_UNRELATED_OCCURRENCE_ACTIVE)
        )
    }

    @Test
    fun t10_clockAnomaliesOverdueAndFiredWitness_areNonDestructive() {
        val am = mockk<AlarmManager>(relaxed = true) { every { canScheduleExactAlarms() } returns true }
        val ctx = wrapWithAlarmManager(am)

        // (a) Absent snapshot
        val absentRes = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(absentRes is DirectBootFallbackScheduleResult.NoSchedulableTarget)
        assertEquals(NoTargetReason.SNAPSHOT_ABSENT, (absentRes as DirectBootFallbackScheduleResult.NoSchedulableTarget).reason)

        // Save snapshot for remaining checks
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(110L), triggerTime, now)

        // (b) Overdue (triggerTime <= now): non-destructive!
        val overdueRes = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now + 120_000L)
        assertTrue(overdueRes is DirectBootFallbackScheduleResult.NoSchedulableTarget)
        assertEquals(
            NoTargetReason.TRIGGER_NOT_IN_FUTURE_PRESERVED,
            (overdueRes as DirectBootFallbackScheduleResult.NoSchedulableTarget).reason
        )
        assertNotNull("Overdue check must NOT erase V1 snapshot", DirectBootAlarmCache.read(ctx))

        // (c) Invalid clock now <= 0: non-destructive!
        val invalidNowRes = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, 0L)
        assertTrue(invalidNowRes is DirectBootFallbackScheduleResult.NoSchedulableTarget)
        assertEquals(
            NoTargetReason.INVALID_CLOCK_NOW,
            (invalidNowRes as DirectBootFallbackScheduleResult.NoSchedulableTarget).reason
        )
        assertNotNull(DirectBootAlarmCache.read(ctx))

        // (d) Clock rollback (now < updatedAt while triggerTime > now): arms with diagnostic flag, preserves snapshot
        val rollbackRes = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now - 10_000L)
        assertTrue(rollbackRes is DirectBootFallbackScheduleResult.ArmedAlarmClock)
        assertTrue(
            (rollbackRes as DirectBootFallbackScheduleResult.ArmedAlarmClock).v2Diagnostics
                .contains(V2DiagnosticFlag.CLOCK_ROLLBACK_DETECTED)
        )
        assertNotNull(DirectBootAlarmCache.read(ctx))

        // (e) Already fired witness (restore snapshot key after recordFired to simulate stale/un-cleared snapshot)
        val snapBackup = requireNotNull(DirectBootAlarmCache.read(ctx))
        DirectBootAlarmCache.recordFired(ctx, alarmId = 110L, triggerTime = triggerTime, firedAt = now - 1_000L)
        prefs().edit()
            .putInt("schema_version", snapBackup.schemaVersion)
            .putLong("alarm_id", snapBackup.alarmId)
            .putLong("trigger_time", snapBackup.triggerTime)
            .putString("time_label", snapBackup.timeLabel)
            .putLong("updated_at", snapBackup.updatedAt)
            .commit()

        val witnessBlocked = DirectBootAlarmCache.scheduleCachedAlarmWithResult(ctx, now)
        assertTrue(witnessBlocked is DirectBootFallbackScheduleResult.NoSchedulableTarget)
        assertEquals(
            NoTargetReason.ALREADY_FIRED_WITNESS,
            (witnessBlocked as DirectBootFallbackScheduleResult.NoSchedulableTarget).reason
        )
        assertTrue(DirectBootAlarmCache.hasFiredWitness(ctx, 110L, triggerTime))
    }

    @Test
    fun t11_aThenBRegistrationDriftAndFiredWitnessDuringRegistration_returnsRegistrationOutcomeUnknownWithoutCancel() {
        val am = mockk<AlarmManager>(relaxed = true) { every { canScheduleExactAlarms() } returns true }
        val ctx = wrapWithAlarmManager(am)
        val triggerA = now + 60_000L
        val triggerB = now + 120_000L
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(111L), triggerA, now)

        // (a) A -> B snapshot drift mid-registration
        val driftResult = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now,
            onAfterOsRegistrationBeforeRecheck = {
                DirectBootAlarmCache.saveIfEarlier(ctx, alarm(111L), triggerB, now + 1L)
            }
        )
        assertTrue(driftResult is DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown)
        driftResult as DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown
        assertEquals(PostOsUnknownReason.SNAPSHOT_SUPERSEDED_DURING_REGISTRATION, driftResult.reason)
        assertEquals(OsRegistrationState.UNKNOWN, driftResult.osState)
        assertFalse(driftResult.isArmed)
        assertFalse(driftResult.compensationAttempted)
        assertEquals(triggerB, driftResult.observedSnapshot?.triggerTime)
        assertEquals(triggerB, DirectBootAlarmCache.read(ctx)?.triggerTime)
        verify(exactly = 0) { am.cancel(any<PendingIntent>()) }

        // (b) Fired witness recorded for B mid-registration
        val witnessResult = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now + 1L,
            onAfterOsRegistrationBeforeRecheck = {
                DirectBootAlarmCache.recordFired(ctx, alarmId = 111L, triggerTime = triggerB, firedAt = now + 2L)
            }
        )
        assertTrue(witnessResult is DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown)
        witnessResult as DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown
        assertEquals(
            PostOsUnknownReason.FIRED_WITNESS_DURING_REGISTRATION_COMPENSATION_WITHHELD,
            witnessResult.reason
        )
        assertEquals(OsRegistrationState.UNKNOWN, witnessResult.osState)
        assertFalse(witnessResult.isArmed)
        assertFalse(witnessResult.compensationAttempted)
        verify(exactly = 0) { am.cancel(any<PendingIntent>()) }
    }

    @Test
    fun t12_bThenARegistrationOrder_bRemainsArmedInOsAndADoesNotCancelSharedPendingIntent() {
        val realAm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerA = now + 60_000L
        val triggerB = now + 120_000L

        // Step 1: B registers first in OS AlarmManager
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(112L), triggerB, now)
        val bResult = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = wrapWithAlarmManager(realAm),
            now = now,
            canScheduleExactAlarms = { true }
        )
        assertTrue(bResult is DirectBootFallbackScheduleResult.ArmedAlarmClock)
        val snapB = requireNotNull(DirectBootAlarmCache.read(appContext))

        // Step 2: Simulated delayed A attempts registration, while B holds snapshot & OS PendingIntent
        // Force snapshot temporarily to A before OS call, then restore B + re-arm B before A's post-OS recheck
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(112L), triggerA, now)
        var cancelInvocations = 0
        val spyAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { cancel(any<PendingIntent>()) } answers {
                cancelInvocations++
            }
        }

        val delayedAResult = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = wrapWithAlarmManager(spyAm),
            now = now,
            canScheduleExactAlarms = { true },
            alarmClockRegistrar = { _, info, op ->
                // Delayed A writes to OS, then B immediately overwrites/re-arms B on real OS AlarmManager
                realAm.setAlarmClock(info, op)
            },
            onAfterOsRegistrationBeforeRecheck = {
                DirectBootAlarmCache.saveIfEarlier(appContext, alarm(112L), triggerB, now + 5L)
                val bReArm = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
                    context = wrapWithAlarmManager(realAm),
                    now = now + 5L,
                    canScheduleExactAlarms = { true }
                )
                assertTrue(bReArm is DirectBootFallbackScheduleResult.ArmedAlarmClock)
            }
        )

        assertTrue(delayedAResult is DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown)
        assertFalse((delayedAResult as DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown).compensationAttempted)
        assertEquals(0, cancelInvocations)

        // B's snapshot and OS AlarmClock & shared PendingIntent remain intact
        assertEquals(triggerB, DirectBootAlarmCache.read(appContext)?.triggerTime)
        val nextClock = requireNotNull(Shadows.shadowOf(realAm).scheduledAlarms.last().alarmClockInfo)
        assertEquals(triggerB, nextClock.triggerTime)
        assertNotNull(
            DirectBootAlarmReceiver.pendingIntent(
                deContext,
                snapB,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
    }

    @Test
    fun t13_staleADoesNotCancelNewBsSharedPendingIntentEvenWhenPendingIntentExistsInOs() {
        val realAm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ctx = wrapWithAlarmManager(realAm)
        val triggerA = now + 60_000L
        val triggerB = now + 180_000L

        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(113L), triggerA, now)
        val snapA = requireNotNull(DirectBootAlarmCache.read(appContext))
        val snapB = snapA.copy(triggerTime = triggerB, updatedAt = now + 1L)

        // Prove A and B share the exact same OS PendingIntent identity (data=null, requestCode=alarmId.toInt())
        val piA = requireNotNull(DirectBootAlarmReceiver.pendingIntent(deContext, snapA))
        val piB = requireNotNull(DirectBootAlarmReceiver.pendingIntent(deContext, snapB))
        assertEquals("Same-alarmId Legacy PendingIntents share OS token", piA, piB)

        val result = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = ctx,
            now = now,
            canScheduleExactAlarms = { true },
            onAfterOsRegistrationBeforeRecheck = {
                DirectBootAlarmCache.saveIfEarlier(appContext, alarm(113L), triggerB, now + 1L)
                DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
                    context = ctx,
                    now = now + 1L,
                    canScheduleExactAlarms = { true }
                )
            }
        )

        assertTrue(result is DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown)
        assertFalse((result as DirectBootFallbackScheduleResult.RegistrationOutcomeUnknown).compensationAttempted)
        assertNotNull(
            "Shared PendingIntent must remain active for B",
            DirectBootAlarmReceiver.pendingIntent(
                deContext,
                snapB,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
        )
        assertEquals(triggerB, Shadows.shadowOf(realAm).scheduledAlarms.last().alarmClockInfo?.triggerTime)
    }

    @Test
    fun t14_cancelScheduledFallbackWithResult_distinguishesCancelledAbsentAndCancelFailedUnknown() {
        // (a) No existing PendingIntent -> NoExistingPendingIntent
        val absentRes = DirectBootAlarmCache.cancelScheduledFallbackWithResultInternal(
            context = appContext,
            alarmId = 114L,
            pendingIntentLookup = { _, _ -> null }
        )
        assertTrue(absentRes is DirectBootFallbackCancelResult.NoExistingPendingIntent)
        assertTrue(absentRes.isCancelledOrAbsent)
        assertEquals(OsRegistrationState.NOT_ARMED, absentRes.osState)

        // (b) Existing PendingIntent + successful cancel (outside DirectBootStorageLock) -> Cancelled
        val snapshot = DirectBootAlarmSnapshot(
            alarmId = 114L,
            triggerTime = now + 60_000L,
            label = "",
            timeLabel = "7:00 AM",
            playDefaultSound = true,
            vibrationEnabled = true,
            updatedAt = now
        )
        assertNotNull(DirectBootAlarmReceiver.pendingIntent(deContext, snapshot))
        val okAm = mockk<AlarmManager>(relaxed = true)
        var lockHeldDuringCancel = true
        val okRes = DirectBootAlarmCache.cancelScheduledFallbackWithResultInternal(
            context = wrapWithAlarmManager(okAm),
            alarmId = 114L,
            alarmCanceller = { am, pi ->
                lockHeldDuringCancel = Thread.holdsLock(DirectBootStorageLock.lock)
                am.cancel(pi)
                pi.cancel()
            }
        )
        assertFalse("AlarmManager.cancel must NOT hold DirectBootStorageLock", lockHeldDuringCancel)
        assertTrue(okRes is DirectBootFallbackCancelResult.Cancelled)
        assertTrue(okRes.isCancelledOrAbsent)
        assertEquals(OsRegistrationState.NOT_ARMED, okRes.osState)

        // (c) Existing PendingIntent + AlarmManager.cancel throws -> CancelFailedUnknown
        assertNotNull(DirectBootAlarmReceiver.pendingIntent(deContext, snapshot))
        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws RuntimeException("binder dead")
        }
        val failRes = DirectBootAlarmCache.cancelScheduledFallbackWithResult(
            context = wrapWithAlarmManager(failingAm),
            alarmId = 114L
        )
        assertTrue(failRes is DirectBootFallbackCancelResult.CancelFailedUnknown)
        assertFalse(failRes.isCancelledOrAbsent)
        assertEquals(OsRegistrationState.UNKNOWN, failRes.osState)

        // (d) Backward-compatible cancelScheduledFallback does not throw on failure
        DirectBootAlarmCache.cancelScheduledFallback(wrapWithAlarmManager(failingAm), 114L)
    }

    @Test
    fun t15_processDeathMidRegistrationAfterOsSet_preservesSnapshotAndReentryArmsCleanly() {
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(115L), triggerTime, now)
        val initialSnapshot = requireNotNull(DirectBootAlarmCache.read(appContext))

        var aborted = false
        try {
            DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
                context = appContext,
                now = now,
                canScheduleExactAlarms = { true },
                onAfterOsRegistrationBeforeRecheck = {
                    throw SimulatedProcessDeathException()
                }
            )
        } catch (_: SimulatedProcessDeathException) {
            aborted = true
        }

        assertTrue(aborted)
        assertFalse(Thread.holdsLock(DirectBootStorageLock.lock))
        assertEquals(initialSnapshot, DirectBootAlarmCache.read(appContext))
        assertTrue(prefs().all.keys.none { it.startsWith("legacy_reg_") })

        // Re-entry after process restart arms cleanly
        val reentryResult = DirectBootAlarmCache.scheduleCachedAlarmWithResultInternal(
            context = appContext,
            now = now,
            canScheduleExactAlarms = { true }
        )
        assertTrue(reentryResult is DirectBootFallbackScheduleResult.ArmedAlarmClock)
        assertEquals(triggerTime, (reentryResult as DirectBootFallbackScheduleResult.ArmedAlarmClock).triggerTime)
    }

    @Test
    fun t16_flagOffCompatibility_sharedOutputAuthorityGateDisabled_andBooleanWrapperTruthfulness() {
        val gate = SharedOutputAuthorityGate()
        assertFalse("SharedOutputAuthorityGate default flag MUST be OFF", gate.isEnabled)
        assertEquals(
            OutputAuthorizationResult.GateDisabled,
            gate.reserveForServiceStart(
                context = appContext,
                key = OutputOccurrenceKey(alarmId = 116L, scheduledAt = now + 60_000L),
                source = LegacyClaimSource.DIRECT_BOOT_RECEIVER
            )
        )

        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(116L), triggerTime, now)

        // ArmedAlarmClock -> true
        val okAm = mockk<AlarmManager>(relaxed = true) { every { canScheduleExactAlarms() } returns true }
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(wrapWithAlarmManager(okAm), now))

        // ArmedExactIdleFallback -> true
        val fallbackAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws SecurityException("clock denied")
        }
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(wrapWithAlarmManager(fallbackAm), now))

        // PermissionDenied -> false
        val deniedAm = mockk<AlarmManager>(relaxed = true) { every { canScheduleExactAlarms() } returns false }
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(wrapWithAlarmManager(deniedAm), now))

        // SecurityBlocked -> false
        val blockedAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws SecurityException("clock denied")
            every { setExactAndAllowWhileIdle(any(), any(), any()) } throws SecurityException("exact denied")
        }
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(wrapWithAlarmManager(blockedAm), now))

        // RegistrationFailed -> false
        val failedAm = mockk<AlarmManager>(relaxed = true) {
            every { canScheduleExactAlarms() } returns true
            every { setAlarmClock(any(), any()) } throws IllegalStateException("fail")
        }
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(wrapWithAlarmManager(failedAm), now))

        // NoSchedulableTarget (overdue) -> false
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(wrapWithAlarmManager(okAm), now + 120_000L))

        // AuthorityUnknown (same-occurrence HANDOVER_PENDING) -> false
        assertTrue(
            DirectBootDurableOwnerStore.write(
                appContext,
                DirectBootDurableOwner(
                    state = OwnerState.HANDOVER_PENDING,
                    ownerSeq = 0L,
                    handoverClaim = HandoverClaim(
                        handoverId = "ho-116",
                        alarmId = 116L,
                        triggerTime = triggerTime,
                        generation = 1,
                        instanceToken = "tok-116",
                        restoreEpoch = 1L
                    ),
                    updatedAtEpochMs = now
                )
            ).isSuccess
        )
        assertFalse(DirectBootAlarmCache.scheduleCachedAlarm(wrapWithAlarmManager(okAm), now))
    }
}
