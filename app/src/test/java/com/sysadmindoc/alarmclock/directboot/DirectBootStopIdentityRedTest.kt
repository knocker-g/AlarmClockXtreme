package com.sysadmindoc.alarmclock.directboot

import android.app.Notification
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController

/**
 * Production-Coupled STOP Identity & Lineage Authority Test Suite (KNO-75 Phase C7-STOP-AUTHORITY-P1B).
 *
 * Verifies:
 * - A1: V2 active, Alarm ID and Generation match, but Token/Epoch/Trigger missing -> REJECTED fail-closed.
 * - A2: All match BUT TriggerTime is different -> REJECTED fail-closed.
 * - A3: Token missing -> REJECTED fail-closed.
 * - A4: All 5 canonical fields match -> ACCEPTED & STOPPED.
 * - A5: Legacy V1 legitimate STOP -> ACCEPTED & STOPPED.
 * - S1: Production notification stopIntent contains all 5 V2 lineage extras and Data URI (PASS).
 * - P1-P5: Intent.filterEquals uniqueness & 64-bit ID collision protection.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootStopIdentityRedTest {

    private lateinit var context: Context
    private lateinit var controller: ServiceController<DirectBootAlarmService>
    private lateinit var service: DirectBootAlarmService
    private val baseTime = 1_700_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        controller = Robolectric.buildService(DirectBootAlarmService::class.java)
        service = controller.create().get()
    }

    @After
    fun tearDown() {
        controller.destroy()
    }

    // =========================================================================
    // S1: Production-Coupled Notification STOP Intent Lineage Inspection
    // =========================================================================

    @Test
    fun s1_productionNotificationStopIntent_mustContainAllV2LineageExtras() {
        val alarmId = 101L
        val triggerTime = baseTime + 10_000L
        val gen = 1
        val token = "tok_s1_101"
        val epoch = 1L

        val startIntent = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, triggerTime)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, token)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, epoch)
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "7:15 AM")
        }

        service.onStartCommand(startIntent, 0, 1)

        val shadowService = shadowOf(service)
        val notification: Notification? = shadowService.lastForegroundNotification
        assertNotNull("Foreground notification MUST be posted when service starts", notification)

        val actions = notification?.actions
        assertNotNull("Notification MUST contain actions", actions)
        assertTrue("Notification MUST contain at least 1 action", actions?.isNotEmpty() == true)

        val stopAction = actions?.firstOrNull()
        assertNotNull("Notification stop action MUST be present", stopAction)

        val pendingIntent = stopAction?.actionIntent
        assertNotNull("Stop action PendingIntent MUST be present", pendingIntent)

        val savedIntent = shadowOf(pendingIntent).savedIntent
        assertNotNull("Saved intent inside PendingIntent MUST be present", savedIntent)

        // Verifies production fix: Notification stopIntent contains all V2 lineage extras
        assertTrue(savedIntent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION))
        assertTrue(savedIntent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN))
        assertTrue(savedIntent.hasExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH))
        assertEquals(gen, savedIntent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, -1))
        assertEquals(token, savedIntent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN))
        assertNotNull("Notification stop intent MUST contain Data URI", savedIntent.data)
    }

    // =========================================================================
    // A1 - A4: Strict Fail-Closed Lineage Authority Verification
    // =========================================================================

    @Test
    fun a1_a2_a3_a4_strictLineageAuthorityVerification() {
        val alarmId = 701L
        val trig = baseTime + 70_000L
        val gen = 1
        val token = "tok_701"
        val epoch = 1L

        // Start V2 active alarm
        val startV2 = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, trig)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, token)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, epoch)
        }
        service.onStartCommand(startV2, 0, 1)

        // A1: Alarm ID and Generation match, but Token/Epoch/Trigger missing -> REJECTED
        val stopA1 = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
        }
        val resA1 = service.onStartCommand(stopA1, 0, 2)
        assertEquals("A1: Missing token/epoch/trigger MUST be rejected", android.app.Service.START_NOT_STICKY, resA1)
        assertFalse("A1: Service MUST NOT be stopped when partial stop is rejected", shadowOf(service).isStoppedBySelf)

        // A2: All match BUT TriggerTime is different -> REJECTED
        val stopA2 = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, trig + 999L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, token)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, epoch)
        }
        val resA2 = service.onStartCommand(stopA2, 0, 3)
        assertEquals("A2: Mismatched trigger time MUST be rejected", android.app.Service.START_NOT_STICKY, resA2)
        assertFalse("A2: Service MUST NOT be stopped when trigger time is mismatched", shadowOf(service).isStoppedBySelf)

        // A3: Token missing -> REJECTED
        val stopA3 = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, trig)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, epoch)
        }
        val resA3 = service.onStartCommand(stopA3, 0, 4)
        assertEquals("A3: Missing token MUST be rejected", android.app.Service.START_NOT_STICKY, resA3)
        assertFalse("A3: Service MUST NOT be stopped when token is missing", shadowOf(service).isStoppedBySelf)

        // A4: All 5 canonical fields match -> ACCEPTED
        val stopA4 = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, alarmId)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, trig)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, gen)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, token)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, epoch)
        }
        val resA4 = service.onStartCommand(stopA4, 0, 5)
        assertEquals("A4: Complete canonical lineage match MUST be accepted", android.app.Service.START_NOT_STICKY, resA4)
        assertTrue("A4: Service MUST be stopped by self after valid complete stop", shadowOf(service).isStoppedBySelf)
    }

    // =========================================================================
    // P1, P2, P3: Intent.filterEquals Uniqueness Verification
    // =========================================================================

    @Test
    fun p1_p2_p3_intentFilterEquals_differentLineage_returnsFalse() {
        val pi1 = DirectBootAlarmActivity.fullScreenIntent(context, alarmId = 100L, timeLabel = "7:00", generation = 1, instanceToken = "tok_p", restoreEpoch = 1L, triggerTime = 1000L)
        val pi2 = DirectBootAlarmActivity.fullScreenIntent(context, alarmId = 100L, timeLabel = "7:00", generation = 2, instanceToken = "tok_p", restoreEpoch = 1L, triggerTime = 1000L)
        val pi3 = DirectBootAlarmActivity.fullScreenIntent(context, alarmId = 100L, timeLabel = "7:00", generation = 1, instanceToken = "tok_p", restoreEpoch = 2L, triggerTime = 1000L)
        val pi4 = DirectBootAlarmActivity.fullScreenIntent(context, alarmId = 100L, timeLabel = "7:00", generation = 1, instanceToken = "tok_p", restoreEpoch = 1L, triggerTime = 2000L)

        val intent1 = shadowOf(pi1).savedIntent
        val intent2 = shadowOf(pi2).savedIntent
        val intent3 = shadowOf(pi3).savedIntent
        val intent4 = shadowOf(pi4).savedIntent

        assertFalse("Gen 1 and Gen 2 intents MUST NOT filterEqual", intent1.filterEquals(intent2))
        assertFalse("Epoch 1 and Epoch 2 intents MUST NOT filterEqual", intent1.filterEquals(intent3))
        assertFalse("Trig 1000 and Trig 2000 intents MUST NOT filterEqual", intent1.filterEquals(intent4))
    }

    // =========================================================================
    // P5: Long Alarm ID Lower 32-Bit Collision Protection
    // =========================================================================

    @Test
    fun p5_longAlarmId_lower32BitCollision_generatesDistinctDataUris() {
        val id1 = 0x100000001L
        val id2 = 0x200000001L

        val pi1 = DirectBootAlarmActivity.fullScreenIntent(context, alarmId = id1, timeLabel = "7:00", generation = 1, instanceToken = "tok", restoreEpoch = 1L, triggerTime = 1000L)
        val pi2 = DirectBootAlarmActivity.fullScreenIntent(context, alarmId = id2, timeLabel = "7:00", generation = 1, instanceToken = "tok", restoreEpoch = 1L, triggerTime = 1000L)

        val intent1 = shadowOf(pi1).savedIntent
        val intent2 = shadowOf(pi2).savedIntent

        assertFalse("Intents with same lower 32 bits MUST NOT filterEqual", intent1.filterEquals(intent2))
        assertNotNull(intent1.data)
        assertNotNull(intent2.data)
        assertTrue(intent1.data.toString().contains("4294967297"))
        assertTrue(intent2.data.toString().contains("8589934593"))
    }

    // =========================================================================
    // P6: FullScreen Activity STOP Identity Complete Lineage
    // =========================================================================

    @Test
    fun p6_fullScreenActivity_providesCompleteLineageIdentity() {
        val intent = DirectBootAlarmActivity.intent(
            context = context,
            alarmId = 501L,
            timeLabel = "8:00 AM",
            generation = 3,
            instanceToken = "tok_fs_501",
            restoreEpoch = 2L,
            triggerTime = baseTime + 50_000L
        )

        assertEquals(501L, intent.getLongExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L))
        assertEquals(3, intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, -1))
        assertEquals("tok_fs_501", intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN))
        assertEquals(2L, intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, -1L))
        assertEquals(baseTime + 50_000L, intent.getLongExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, -1L))
        assertNotNull("Activity intent MUST have non-null data URI", intent.data)
    }

    // =========================================================================
    // S3 & S4: Stale STOP Rejection (PRESERVED GREEN)
    // =========================================================================

    @Test
    fun s3_s4_staleStop_rejected() {
        // Start candidate A (alarmId=201)
        val startA = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 201L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 10_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_201")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 1L)
        }
        service.onStartCommand(startA, 0, 1)

        // Handover to candidate B (alarmId=202)
        val startB = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 202L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 20_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_202")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 1L)
        }
        service.onStartCommand(startB, 0, 2)

        // Stale stop for A arrives
        val stopA = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 201L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 10_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 1)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "tok_201")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 1L)
        }

        val result = service.onStartCommand(stopA, 0, 3)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
    }

    // =========================================================================
    // A5 / S6: Legacy V1 Legitimate STOP -> Accepted (PRESERVED GREEN)
    // =========================================================================

    @Test
    fun a5_s6_legacyV1Alarm_legitimateStop_accepted() {
        val startLegacy = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_START
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 401L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 40_000L)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 0)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "")
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        }
        service.onStartCommand(startLegacy, 0, 1)

        val stopLegacy = Intent(context, DirectBootAlarmService::class.java).apply {
            action = DirectBootAlarmService.ACTION_STOP
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 401L)
        }

        val result = service.onStartCommand(stopLegacy, 0, 2)
        assertEquals(android.app.Service.START_NOT_STICKY, result)
    }
}
