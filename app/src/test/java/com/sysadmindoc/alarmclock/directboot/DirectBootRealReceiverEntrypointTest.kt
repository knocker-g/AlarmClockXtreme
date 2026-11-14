package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real Receiver Entrypoint Owner Safety Test Suite for KNO-75 Phase C7-OWNER-ARCHITECTURE-DECISION (G1 - G5).
 *
 * Executes real [AlarmReceiver.onReceive] and [DirectBootAlarmReceiver.onReceive] calls
 * to verify bi-directional delivery fencing at the actual OS entrypoint boundaries.
 */
@RunWith(RobolectricTestRunner::class)
class DirectBootRealReceiverEntrypointTest {

    private lateinit var deContext: Context
    private lateinit var ceContext: Context
    private lateinit var database: AlarmDatabase
    private val baseTime = 1_700_000_000_000L

    private fun clearDeStorage() {
        val dePrefs = deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
        dePrefs.edit()
            .remove(DirectBootDeliveryLedger.KEY_DELIVERY_LEDGER_PAYLOAD)
            .remove(DirectBootMissedStore.KEY_MISSED_STORE_PAYLOAD)
            .remove(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD)
            .commit()
    }

    @Before
    fun setUp() {
        val appContext = ApplicationProvider.getApplicationContext<Context>()
        deContext = appContext.createDeviceProtectedStorageContext()
        ceContext = appContext
        database = Room.inMemoryDatabaseBuilder(appContext, AlarmDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        DirectBootCeTakeoverStore.unlockGateSeam = { true }
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootAlarmReceiver.resetSeamsForTesting()
        clearDeStorage()
    }

    @After
    fun tearDown() {
        database.close()
        DirectBootCeTakeoverStore.clear(ceContext)
        DirectBootCeTakeoverStore.resetSeamsForTesting()
        DirectBootAlarmReceiver.resetSeamsForTesting()
        clearDeStorage()
    }

    // =========================================================================
    // G2.1: Legacy-first -> DirectBoot Entrypoint Rejected
    // =========================================================================

    @Test
    fun g2_1_legacyFirst_directBootReceiverRejectsDelivery() {
        val alarmId = 101L
        val triggerTime = baseTime + 10_000L

        // Legacy delivers first and appends delivery record to DE ledger
        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 10_010L,
            generation = 1,
            instanceToken = "tok_legacy_101",
            provenance = "LEGACY_DELIVERY",
            restoreEpoch = 1L
        )
        val appendRes = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
        assertTrue(appendRes is DeliveryAppendResult.Appended)

        // Seed Queue for DirectBoot Receiver check
        val itemA = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_legacy_101",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(itemA), now = baseTime)

        val authorizer = DirectBootDeliveryAuthorizer()
        var directBootServiceStarts = 0

        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            object : DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { baseTime + 10_020L }) {
                override fun adaptDelivery(
                    ticketId: String,
                    identity: DirectBootV2NormalIntentIdentity,
                    deliveredAt: Long
                ): ServiceDeliveryAdaptationResult {
                    val res = super.adaptDelivery(ticketId, identity, deliveredAt)
                    if (res is ServiceDeliveryAdaptationResult.TransitionCommitted) {
                        directBootServiceStarts++
                    }
                    return res
                }
            }
        }

        val identityA = DirectBootV2NormalIntentIdentity.fromQueueItem(itemA)
        val ticketRes = authorizer.issueTicket(identityA, nowEpochMs = baseTime + 10_020L)
        assertTrue(ticketRes is TicketIssueResult.Success)
        val ticketId = (ticketRes as TicketIssueResult.Success).ticket.ticketId

        val firingIntent = identityA.createIntent(ceContext).apply {
            putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticketId)
        }

        // DirectBootAlarmReceiver.onReceive runs
        val dbReceiver = DirectBootAlarmReceiver()
        dbReceiver.onReceive(ceContext, firingIntent)

        // DirectBoot service start count MUST be 0
        assertEquals("DirectBoot receiver MUST NOT start service when Legacy has already delivered occurrence", 0, directBootServiceStarts)
    }

    // =========================================================================
    // G2.2: DirectBoot-first -> Legacy Receiver Drops Intent
    // =========================================================================

    @Test
    fun g2_2_directBootFirst_legacyReceiverDropsIntent() {
        val alarmId = 102L
        val triggerTime = baseTime + 20_000L

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 20_010L,
            generation = 1,
            instanceToken = "tok_db_102",
            provenance = "V2_INTENT_DELIVERY",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Legacy AlarmReceiver receives legacy broadcast
        val legacyIntent = Intent("com.sysadmindoc.alarmclock.ALARM_FIRED").apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, triggerTime)
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(ceContext, legacyIntent)

        // Verify isOccurrenceDeliveredByDirectBoot returns true
        val isBlocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, alarmId, triggerTime)
        assertTrue("Legacy receiver MUST drop intent for occurrence delivered by DirectBoot", isBlocked)
    }

    // =========================================================================
    // G2.3: Concurrent Receiver Entry -> Exactly 1 Winner
    // =========================================================================

    @Test
    fun g2_3_concurrentReceiverEntry_exactlyOneWinner() = runBlocking {
        val alarmId = 103L
        val triggerTime = baseTime + 30_000L

        val item = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = alarmId,
            triggerTime = triggerTime,
            generation = 1,
            instanceToken = "tok_conc_103",
            restoreEpoch = 1L
        )
        DirectBootAlarmCache.writeQueue(deContext, listOf(item), now = baseTime)

        val deliveryWins = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(2)

        val task1 = executor.submit {
            val delRecord = DirectBootDeliveryRecord.createNormal(
                alarmId = alarmId,
                triggerTime = triggerTime,
                deliveredAt = baseTime + 30_050L,
                generation = 1,
                instanceToken = "tok_conc_103",
                provenance = "LEGACY_RECEIVER",
                restoreEpoch = 1L
            )
            val res = DirectBootDeliveryLedger.appendRecord(deContext, delRecord)
            if (res is DeliveryAppendResult.Appended) deliveryWins.incrementAndGet()
        }

        val task2 = executor.submit {
            val res = DirectBootDeliveryTransitionEngine.transitionNormal(
                context = deContext,
                firingIdentity = FiredOccurrenceIdentity(
                    kind = "NORMAL",
                    alarmId = alarmId,
                    triggerTime = triggerTime,
                    generation = 1,
                    instanceToken = "tok_conc_103",
                    restoreEpoch = 1L,
                    provenance = "DIRECT_BOOT_RECEIVER"
                ),
                deliveredAt = baseTime + 30_050L
            )
            if (res is DeliveryTransitionResult.Confirmed || res is DeliveryTransitionResult.CancellationAfterCommit) {
                deliveryWins.incrementAndGet()
            }
        }

        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)

        assertEquals("EXACTLY 1 receiver MUST win delivery ownership under concurrent entry", 1, deliveryWins.get())
    }

    // =========================================================================
    // G2.4: Delayed Legacy Broadcast after DE ACK
    // =========================================================================

    @Test
    fun g2_4_delayedLegacyAfterDeAck_blockedByCeTakeoverStore() {
        val alarmId = 104L
        val triggerTime = baseTime + 40_000L

        val delRecord = DirectBootDeliveryRecord.createNormal(
            alarmId = alarmId,
            triggerTime = triggerTime,
            deliveredAt = baseTime + 40_010L,
            generation = 1,
            instanceToken = "tok_ack_104",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecord)

        // Handoff to CE + ACK
        val intakeRes = DirectBootCeTakeoverStore.recordDelivered(ceContext, delRecord, baseTime + 40_050L)
        assertTrue(intakeRes is CeTakeoverIntakeResult.RecordedConfirmed)
        val confirmedReceipt = CeHandoffResult.Confirmed(
            ceRecord = (intakeRes as CeTakeoverIntakeResult.RecordedConfirmed).record,
            totalCeRecords = 1,
            alreadyRecordedInCe = false,
            writePerformed = true
        )

        DirectBootCeHandoffCoordinator.acknowledgeDeliveredWithCeProof(
            deContext = deContext,
            ceContext = ceContext,
            candidateRecord = delRecord,
            confirmedReceipt = confirmedReceipt,
            unlockGate = { true }
        )

        // Delayed legacy broadcast arrives
        val isBlocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, alarmId, triggerTime)
        assertTrue("Delayed legacy broadcast MUST be blocked by active CE Takeover Record", isBlocked)
    }

    // =========================================================================
    // G2.6: Same alarmId, Different Generation -> Unrelated Gen NOT Blocked
    // =========================================================================

    @Test
    fun g2_6_sameAlarmIdDifferentGeneration_notBlocked() {
        val delRecordGen1 = DirectBootDeliveryRecord.createNormal(
            alarmId = 106L,
            triggerTime = baseTime + 60_000L,
            deliveredAt = baseTime + 60_010L,
            generation = 1,
            instanceToken = "tok_gen1_106",
            restoreEpoch = 1L
        )
        DirectBootDeliveryLedger.appendRecord(deContext, delRecordGen1)

        val isGen2Blocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 106L, baseTime + 120_000L)
        assertFalse("Unrelated generation / triggerTime for alarm 106 MUST NOT be blocked", isGen2Blocked)
    }

    // =========================================================================
    // G2.8: Legacy-Only Alarm -> Delivered Cleanly
    // =========================================================================

    @Test
    fun g2_8_legacyOnlyAlarm_deliveredCleanly() {
        val isBlocked = AlarmReceiver.isOccurrenceDeliveredByDirectBoot(deContext, 888L, baseTime + 80_000L)
        assertFalse("Legacy-only repeating alarm NOT in DirectBoot Ledger MUST be delivered cleanly", isBlocked)
    }

    // =========================================================================
    // KNO-75 C7-A0-v3: Receiver Validation, Privacy & Isolation (A0-05..A0-10)
    // =========================================================================

    private class RecordingServiceContext(base: Context) : android.content.ContextWrapper(base) {
        val startedServiceIntents = mutableListOf<Intent>()

        override fun startForegroundService(service: Intent?): android.content.ComponentName {
            if (service != null) {
                startedServiceIntents.add(service)
            }
            return android.content.ComponentName(packageName, DirectBootAlarmService::class.java.name)
        }
    }

    @Test
    fun a0_05_malformedV2Intents_safeRejectWithoutCrashStateMutationOrServiceStart() {
        val queueItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 501L,
            triggerTime = baseTime + 50_000L,
            generation = 1,
            instanceToken = "tok_valid_501",
            restoreEpoch = 1L
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(queueItem), now = baseTime))
        val queueBefore = DirectBootAlarmCache.getRawQueuePayload(deContext)

        val recordingCtx = RecordingServiceContext(ceContext)
        val validIdentity = DirectBootV2NormalIntentIdentity.fromQueueItem(queueItem)

        val malformedIntents = listOf(
            // Missing all identity extras
            Intent(ceContext, DirectBootAlarmReceiver::class.java).apply {
                action = DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM
            },
            // Invalid alarmId <= 0
            validIdentity.createIntent(ceContext).apply {
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, -1L)
            },
            // Invalid generation < 1
            validIdentity.createIntent(ceContext).apply {
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 0)
            },
            // Blank instanceToken
            validIdentity.createIntent(ceContext).apply {
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "   ")
            },
            // Invalid triggerTime <= 0
            validIdentity.createIntent(ceContext).apply {
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_TRIGGER_TIME, 0L)
            },
            // RestoreEpoch == 0
            DirectBootV2NormalIntentIdentity(
                alarmId = 501L,
                generation = 1,
                instanceToken = "tok_valid_501",
                triggerTime = baseTime + 50_000L,
                restoreEpoch = 0L
            ).createIntent(ceContext),
            // Foreign URI
            validIdentity.createIntent(ceContext).apply {
                data = android.net.Uri.parse("https://evil.example.com/alarm/501")
            },
            // Tampered digest
            validIdentity.createIntent(ceContext).apply {
                putExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST, "0".repeat(64))
            },
            // Missing digest
            validIdentity.createIntent(ceContext).apply {
                removeExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)
            }
        )

        val receiver = DirectBootAlarmReceiver()
        malformedIntents.forEachIndexed { idx, badIntent ->
            receiver.onReceive(recordingCtx, badIntent)
            assertEquals("Malformed intent #$idx MUST NOT start service", 0, recordingCtx.startedServiceIntents.size)
        }

        assertEquals("Queue MUST remain untouched on malformed V2 intents", queueBefore, DirectBootAlarmCache.getRawQueuePayload(deContext))
        assertTrue("Ledger MUST remain absent on malformed V2 intents", DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.Absent)
        assertTrue("MissedStore MUST remain absent on malformed V2 intents", DirectBootMissedStore.readUnacknowledgedRecords(deContext) is MissedStoreStorageState.Absent)
    }

    @Test
    fun a0_06_corruptStorage_receiverFailsClosedWithoutServiceStart() {
        val corruptQueue = """{"schemaVersion":3,"items":[CORRUPT"""
        deContext.getSharedPreferences(DirectBootDeliveryLedger.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(DirectBootAlarmCache.KEY_QUEUE_PAYLOAD, corruptQueue).commit()

        val identity = DirectBootV2NormalIntentIdentity(
            alarmId = 601L,
            generation = 1,
            instanceToken = "tok_601",
            triggerTime = baseTime + 60_000L,
            restoreEpoch = 1L
        )
        val authorizer = DirectBootDeliveryAuthorizer()
        val ticket = (authorizer.issueTicket(identity, nowEpochMs = baseTime + 60_000L) as TicketIssueResult.Success).ticket
        val savedSeam = DirectBootAlarmReceiver.serviceAdapterSeam
        DirectBootAlarmReceiver.serviceAdapterSeam = { ctx ->
            DirectBootServiceDeliveryAdapter(ctx, authorizer, clock = { baseTime + 60_000L })
        }

        val recordingCtx = RecordingServiceContext(ceContext)
        try {
            val intent = identity.createIntent(ceContext).apply {
                putExtra(DirectBootDeliveryAuthorizer.EXTRA_TICKET_ID, ticket.ticketId)
            }
            DirectBootAlarmReceiver().onReceive(recordingCtx, intent)
        } finally {
            DirectBootAlarmReceiver.serviceAdapterSeam = savedSeam
        }

        assertEquals("Corrupt queue MUST prevent service start", 0, recordingCtx.startedServiceIntents.size)
        assertEquals(corruptQueue, DirectBootAlarmCache.getRawQueuePayload(deContext))
    }

    @Test
    fun a0_07_defaultV2Ingress_noServiceOutput() {
        val queueItem = DirectBootQueueItem(
            kind = DirectBootQueueItem.KIND_NORMAL,
            alarmId = 701L,
            triggerTime = baseTime + 70_000L,
            generation = 1,
            instanceToken = "tok_default_701",
            restoreEpoch = 1L
        )
        assertTrue(DirectBootAlarmCache.writeQueue(deContext, listOf(queueItem), now = baseTime))

        val identity = DirectBootV2NormalIntentIdentity.fromQueueItem(queueItem)
        val defaultV2Intent = identity.createIntent(ceContext)

        val recordingCtx = RecordingServiceContext(ceContext)
        DirectBootAlarmReceiver().onReceive(recordingCtx, defaultV2Intent)

        assertEquals("Default V2 ingress without authorized ticket MUST NOT produce Service output", 0, recordingCtx.startedServiceIntents.size)
        assertEquals(1, DirectBootAlarmCache.readQueue(deContext, now = baseTime).size)
        assertTrue(DirectBootDeliveryLedger.readUnacknowledgedRecords(deContext) is LedgerStorageState.Absent)
    }

    @Test
    fun a0_08_legacyV1Receiver_unchangedBehavior() {
        val v1Intent = Intent(ceContext, DirectBootAlarmReceiver::class.java).apply {
            action = DirectBootAlarmReceiver.ACTION_FIRE_DIRECT_BOOT_ALARM
            putExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, 801L)
            putExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, baseTime + 80_000L)
            putExtra(DirectBootAlarmReceiver.EXTRA_LABEL, "Legacy V1 Morning")
            putExtra(DirectBootAlarmReceiver.EXTRA_TIME_LABEL, "08:00")
            putExtra(DirectBootAlarmReceiver.EXTRA_PLAY_DEFAULT_SOUND, true)
            putExtra(DirectBootAlarmReceiver.EXTRA_VIBRATION_ENABLED, false)
        }

        val recordingCtx = RecordingServiceContext(ceContext)
        DirectBootAlarmReceiver().onReceive(recordingCtx, v1Intent)

        assertEquals("Legacy V1 receiver MUST still start DirectBootAlarmService", 1, recordingCtx.startedServiceIntents.size)
        val started = recordingCtx.startedServiceIntents.single()
        assertEquals(DirectBootAlarmService.ACTION_START, started.action)
        assertEquals(801L, started.getLongExtra(DirectBootAlarmReceiver.EXTRA_ALARM_ID, -1L))
        assertEquals(baseTime + 80_000L, started.getLongExtra(DirectBootAlarmReceiver.EXTRA_TRIGGER_TIME, -1L))
        assertEquals("Legacy V1 Morning", started.getStringExtra(DirectBootAlarmReceiver.EXTRA_LABEL))
        assertFalse(started.getBooleanExtra(DirectBootAlarmReceiver.EXTRA_VIBRATION_ENABLED, true))
    }

    @Test
    fun a0_09_quickIsolation_receiverRejectsQuickV2IntentSafely() {
        val identity = DirectBootV2NormalIntentIdentity(
            alarmId = 901L,
            generation = 1,
            instanceToken = "tok_901",
            triggerTime = baseTime + 90_000L,
            restoreEpoch = 1L
        )
        val quickV2Intent = identity.createIntent(ceContext).apply {
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND, DirectBootQueueItem.KIND_QUICK)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, 0)
            putExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN, "")
        }

        val recordingCtx = RecordingServiceContext(ceContext)
        DirectBootAlarmReceiver().onReceive(recordingCtx, quickV2Intent)

        assertEquals("QUICK V2 intent MUST be safely rejected without service start", 0, recordingCtx.startedServiceIntents.size)
    }

    @Test
    fun a0_10_sensitiveDiagnostics_noPrintlnAndRedactedLogs() {
        val secretToken = "secret_receiver_tok_a010"
        val privateLabel = "Private Medical Appointment A010"
        val identity = DirectBootV2NormalIntentIdentity(
            alarmId = 1010L,
            generation = 1,
            instanceToken = secretToken,
            triggerTime = baseTime + 100_000L,
            restoreEpoch = 1L
        )
        val v2Intent = identity.createIntent(ceContext).apply {
            putExtra(DirectBootAlarmReceiver.EXTRA_LABEL, privateLabel)
        }

        val originalOut = System.out
        val outCapture = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(outCapture))
        org.robolectric.shadows.ShadowLog.clear()

        val recordingCtx = RecordingServiceContext(ceContext)
        try {
            DirectBootAlarmReceiver().onReceive(recordingCtx, v2Intent)
        } finally {
            System.setOut(originalOut)
        }

        val printed = outCapture.toString()
        assertFalse("Receiver MUST NOT emit unstructured V2 ADAPT RESULT println", printed.contains("V2 ADAPT RESULT"))
        assertFalse("Receiver stdout MUST NOT leak raw instanceToken", printed.contains(secretToken))
        assertFalse("Receiver stdout MUST NOT leak private alarm label", printed.contains(privateLabel))

        val logs = org.robolectric.shadows.ShadowLog.getLogsForTag("DirectBootAlarmReceiver")
        assertTrue("Receiver logs MUST NOT leak raw instanceToken", logs.none { it.msg.contains(secretToken) })
        assertTrue("Receiver logs MUST NOT leak private alarm label", logs.none { it.msg.contains(privateLabel) })
    }
}
