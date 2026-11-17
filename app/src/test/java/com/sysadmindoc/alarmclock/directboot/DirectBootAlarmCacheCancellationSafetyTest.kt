package com.sysadmindoc.alarmclock.directboot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * C7-R1 Containment Slice 0 Cancellation Safety Verification Suite.
 *
 * Verifies S0-01 (CancelFailedUnknown preservation of V1 snapshot, Fired Witness, and V2 stores)
 * and S0-02 (full-snapshot equality check against concurrent drift during cancel IPC),
 * while preserving S0-03 known residual risk red04 as separately tracked and UNRESOLVED
 * until R2 Occurrence Fence + URI-scoped PendingIntent cutover.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class DirectBootAlarmCacheCancellationSafetyTest {

    private lateinit var appContext: Context
    private lateinit var deContext: Context
    private val now = 1_700_000_000_000L

    private fun alarm(
        id: Long,
        vibrationEnabled: Boolean = true
    ) = Alarm(
        id = id,
        hour = 7,
        minute = 0,
        vibrationEnabled = vibrationEnabled
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

    private fun v2Identity(alarmId: Long, triggerTime: Long) = DirectBootV2NormalIntentIdentity(
        alarmId = alarmId,
        generation = 1,
        instanceToken = "tok-$alarmId",
        triggerTime = triggerTime,
        restoreEpoch = 1L,
        kind = DirectBootQueueItem.KIND_NORMAL
    )

    private fun queueItem(alarmId: Long, triggerTime: Long) = DirectBootQueueItem(
        kind = DirectBootQueueItem.KIND_NORMAL,
        alarmId = alarmId,
        triggerTime = triggerTime,
        generation = 1,
        instanceToken = "tok-$alarmId",
        restoreEpoch = 1L,
        updatedAt = now
    )

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        deContext = appContext.createDeviceProtectedStorageContext()
        prefs().edit().clear().commit()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        prefs().edit().clear().commit()
    }

    @Test
    fun red01_alarmManagerCancelException_mustPreserveLegacySnapshotWitnessAndV2Stores() {
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(201L), triggerTime, now)
        val expectedSnapshot = requireNotNull(DirectBootAlarmCache.read(appContext))
        assertNotNull(DirectBootAlarmReceiver.pendingIntent(deContext, expectedSnapshot))

        // Seed Fired Witness (separate occurrence) and V2 stores to prove zero collateral mutation
        DirectBootAlarmCache.recordFired(appContext, alarmId = 901L, triggerTime = now - 5_000L, firedAt = now - 4_000L)
        assertTrue(DirectBootAlarmCache.writeQueue(appContext, listOf(queueItem(201L, triggerTime)), now))
        assertTrue(
            DirectBootRegistrationMarker.write(
                appContext,
                DirectBootRegistrationMarker.createInitialIdle(
                    activeIdentity = v2Identity(201L, triggerTime),
                    nowEpochMs = now
                )
            ).isSuccess
        )
        val queueRawBefore = DirectBootAlarmCache.getRawQueuePayload(deContext)
        val markerRawBefore = prefs().getString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, null)

        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws SecurityException("AlarmManager.cancel binder failure")
        }

        DirectBootAlarmCache.removeIfMatches(wrapWithAlarmManager(failingAm), 201L)

        assertEquals(expectedSnapshot, DirectBootAlarmCache.read(appContext))
        assertTrue(DirectBootAlarmCache.hasFiredWitness(appContext, 901L, now - 5_000L))
        assertEquals(queueRawBefore, DirectBootAlarmCache.getRawQueuePayload(deContext))
        assertEquals(markerRawBefore, prefs().getString(DirectBootRegistrationMarker.KEY_V2_REGISTRATION_MARKER, null))
    }

    @Test
    fun red02_cancelFailedUnknown_mustReturnUnknownAndPreserveRecoveryState() {
        val triggerTime = now + 60_000L
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(202L), triggerTime, now)
        val expectedSnapshot = requireNotNull(DirectBootAlarmCache.read(appContext))
        assertNotNull(DirectBootAlarmReceiver.pendingIntent(deContext, expectedSnapshot))

        val failingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } throws IllegalStateException("OS cancel failed")
        }

        val cancelResult = DirectBootAlarmCache.removeIfMatchesWithResult(wrapWithAlarmManager(failingAm), 202L)
        assertTrue(cancelResult is DirectBootFallbackCancelResult.CancelFailedUnknown)
        assertFalse(cancelResult.isCancelledOrAbsent)
        assertEquals(OsRegistrationState.UNKNOWN, cancelResult.osState)
        assertEquals(expectedSnapshot, DirectBootAlarmCache.read(appContext))

        // Null AlarmManager also yields CancelFailedUnknown and preserves snapshot
        val nullAmResult = DirectBootAlarmCache.removeIfMatchesWithResult(wrapWithAlarmManager(null), 202L)
        assertTrue(nullAmResult is DirectBootFallbackCancelResult.CancelFailedUnknown)
        assertFalse(nullAmResult.isCancelledOrAbsent)
        assertEquals(OsRegistrationState.UNKNOWN, nullAmResult.osState)
        assertEquals(expectedSnapshot, DirectBootAlarmCache.read(appContext))

        // Unit compatibility entrypoint also preserves snapshot on CancelFailedUnknown
        DirectBootAlarmCache.removeIfMatches(wrapWithAlarmManager(failingAm), 202L)
        assertEquals(expectedSnapshot, DirectBootAlarmCache.read(appContext))
    }

    @Test
    fun red03_concurrentSnapshotBUpdateDuringRemovalOfA_mustNotDeleteSnapshotB() {
        val triggerA = now + 60_000L
        val triggerB = now + 120_000L
        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(203L), triggerA, now)
        val snapA = requireNotNull(DirectBootAlarmCache.read(appContext))
        assertNotNull(DirectBootAlarmReceiver.pendingIntent(deContext, snapA))

        val racingAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } answers {
                DirectBootAlarmCache.saveIfEarlier(appContext, alarm(203L), triggerB, now + 10_000L)
            }
        }

        val result = DirectBootAlarmCache.removeIfMatchesWithResult(wrapWithAlarmManager(racingAm), 203L)
        assertTrue(result is DirectBootFallbackCancelResult.Cancelled)

        val remaining = DirectBootAlarmCache.read(appContext)
        assertNotNull("Newer snapshot B must NOT be deleted when A removes", remaining)
        assertEquals(triggerB, remaining?.triggerTime)
        assertEquals(now + 10_000L, remaining?.updatedAt)

        // Same alarmId AND same triggerTime, but metadata/updatedAt updated concurrently -> full snapshot equality preserves B
        assertNotNull(DirectBootAlarmReceiver.pendingIntent(deContext, remaining!!))
        val sameTriggerUpdatedAm = mockk<AlarmManager>(relaxed = true) {
            every { cancel(any<PendingIntent>()) } answers {
                DirectBootAlarmCache.saveIfEarlier(
                    appContext,
                    alarm(203L, vibrationEnabled = false),
                    triggerB,
                    now + 20_000L
                )
            }
        }
        DirectBootAlarmCache.removeIfMatches(wrapWithAlarmManager(sameTriggerUpdatedAm), 203L)
        val remainingMetadataUpdated = DirectBootAlarmCache.read(appContext)
        assertNotNull(remainingMetadataUpdated)
        assertFalse(remainingMetadataUpdated!!.vibrationEnabled)
        assertEquals(now + 20_000L, remainingMetadataUpdated.updatedAt)
    }

    @Ignore(
        "KNO-75 KNOWN_RED04_UNRESOLVED: Legacy PendingIntents for the same alarmId share " +
            "(data=null, requestCode=alarmId.toInt()), so stale A removal cancels B's armed OS " +
            "PendingIntent across Binder IPC. Retained un-weakened for R2 URI-PI / Occurrence Fence closure."
    )
    @Test
    fun red04_staleRemovalOfA_mustNotCancelNewBsSharedOsPendingIntent() {
        val triggerA = now + 60_000L
        val triggerB = now + 120_000L
        val realAm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ctx = wrapWithAlarmManager(realAm)

        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(204L), triggerA, now)
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(204L), triggerB, now + 5_000L)
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(ctx, now + 5_000L))
        assertEquals(1, Shadows.shadowOf(realAm).scheduledAlarms.size)

        DirectBootAlarmCache.removeIfMatches(ctx, 204L)

        assertEquals(
            "New occurrence B's armed OS PendingIntent must NOT be cancelled by stale A removal",
            1,
            Shadows.shadowOf(realAm).scheduledAlarms.size
        )
        assertEquals(triggerB, DirectBootAlarmCache.read(ctx)?.triggerTime)
    }

    @Test
    fun control05_normalCancellation_maintainsLegacyDeleteAndOsCancelBehavior() {
        val triggerTime = now + 60_000L
        val realAm = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val ctx = wrapWithAlarmManager(realAm)
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(205L), triggerTime, now)
        DirectBootAlarmCache.recordFired(ctx, alarmId = 905L, triggerTime = now - 5_000L, firedAt = now - 4_000L)
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(ctx, now))
        assertEquals(1, Shadows.shadowOf(realAm).scheduledAlarms.size)

        val result = DirectBootAlarmCache.removeIfMatchesWithResult(ctx, 205L)

        assertTrue(result is DirectBootFallbackCancelResult.Cancelled)
        assertEquals(0, Shadows.shadowOf(realAm).scheduledAlarms.size)
        assertNull(DirectBootAlarmCache.read(ctx))
        assertTrue(DirectBootAlarmCache.hasFiredWitness(ctx, 905L, now - 5_000L))

        // Non-matching alarmId leaves different snapshot untouched
        DirectBootAlarmCache.saveIfEarlier(ctx, alarm(205L), triggerTime, now)
        val nonMatching = DirectBootAlarmCache.removeIfMatchesWithResult(ctx, 999L)
        assertTrue(nonMatching is DirectBootFallbackCancelResult.NoExistingPendingIntent)
        assertEquals(205L, DirectBootAlarmCache.read(ctx)?.alarmId)
    }

    @Test
    fun control06_flagOffAndDefaultDeny_maintainsCompatibilityAndPreservesV2Stores() {
        val gate = SharedOutputAuthorityGate()
        assertFalse(gate.isEnabled)

        assertTrue(DirectBootAlarmCache.writeQueue(appContext, listOf(queueItem(206L, now + 60_000L)), now))
        val rawQueueBefore = DirectBootAlarmCache.getRawQueuePayload(deContext)

        DirectBootAlarmCache.saveIfEarlier(appContext, alarm(206L), now + 60_000L, now)
        assertTrue(DirectBootAlarmCache.scheduleCachedAlarm(appContext, now))
        DirectBootAlarmCache.removeIfMatches(appContext, 206L)

        assertNull(DirectBootAlarmCache.read(appContext))
        assertEquals(rawQueueBefore, DirectBootAlarmCache.getRawQueuePayload(deContext))
    }
}
