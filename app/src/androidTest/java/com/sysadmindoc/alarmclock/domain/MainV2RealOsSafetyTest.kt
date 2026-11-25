package com.sysadmindoc.alarmclock.domain

import android.Manifest
import android.app.AlarmManager
import android.app.Instrumentation
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.UserManager
import android.provider.Settings
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.local.entity.DirectBootOccurrenceReceipt
import com.sysadmindoc.alarmclock.data.local.entity.DurableOperationIntent
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.directboot.D4ExactCleanupResult
import com.sysadmindoc.alarmclock.directboot.D4IsolatedCapabilityGrant
import com.sysadmindoc.alarmclock.directboot.D4IsolatedOccurrenceGrant
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmCache
import com.sysadmindoc.alarmclock.directboot.DirectBootDeliveryLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootDurableOwnerStore
import com.sysadmindoc.alarmclock.directboot.DirectBootFiredWitnessLedger
import com.sysadmindoc.alarmclock.directboot.DirectBootQueueItem
import com.sysadmindoc.alarmclock.directboot.DirectBootV2NormalIntentIdentity
import com.sysadmindoc.alarmclock.directboot.LedgerStorageState
import com.sysadmindoc.alarmclock.directboot.OwnerReadResult
import com.sysadmindoc.alarmclock.directboot.QueueStorageState
import com.sysadmindoc.alarmclock.directboot.WitnessLedgerState
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MainV2RealOsSafetyTest {

    private fun getEntryPoint(context: Context): AlarmClockApp.AppEntryPoint {
        return EntryPointAccessors.fromApplication(
            context,
            AlarmClockApp.AppEntryPoint::class.java
        )
    }

    private fun grantNotificationAndExactAlarm(context: Context, instrumentation: Instrumentation) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                runCatching {
                    instrumentation.uiAutomation.grantRuntimePermission(
                        context.packageName,
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                }
            }
        }
    }

    private fun executeShellOutput(instrumentation: Instrumentation, command: String): String {
        val descriptor = instrumentation.uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    // =========================================================================
    // D1: MAIN_V2 B vs C Non-Aliasing & OS Delivery Contract
    // =========================================================================

    @Test
    fun testD1_mainV2_tokenized_non_aliasing_and_fire() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<Context>()
            grantNotificationAndExactAlarm(context, instrumentation)

            val entryPoint = getEntryPoint(context)
            val alarmScheduler = entryPoint.alarmScheduler()
            val incidentRepository = entryPoint.alarmIncidentRepository()
            incidentRepository.clearHistory()

            val alarmId = 9991L
            val tokenB = UUID.randomUUID().toString()
            val tokenC = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val triggerB = now + 15_000L
            val triggerC = now + 25_000L

            val piB = alarmScheduler.createTokenizedPendingIntent(alarmId, triggerB, "fire-D1-B-$tokenB", tokenB)
            val piC = alarmScheduler.createTokenizedPendingIntent(alarmId, triggerC, "fire-D1-C-$tokenC", tokenC)

            assertNotNull("D1 piB creation failed", piB)
            assertNotNull("D1 piC creation failed", piC)

            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerB, piB)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerC, piC)

                val checkB = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB)
                val checkC = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
                assertNotNull("D1 tokenB must be armed in OS before cancel", checkB)
                assertNotNull("D1 tokenC must be armed in OS before cancel", checkC)

                // Cancel tokenB at T+5s
                am.cancel(piB)
                piB.cancel()

                val afterCancelB = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB)
                val afterCancelC = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
                assertNull("D1 tokenB must be canceled (FLAG_NO_CREATE null)", afterCancelB)
                assertNotNull("D1 tokenC must survive tokenB cancellation", afterCancelC)

                val dumpsys = executeShellOutput(instrumentation, "dumpsys alarm")
                assertTrue("Dumpsys alarm must contain app package", dumpsys.contains(context.packageName))
                assertTrue("Token C URI parity and encoding must be verified", alarmScheduler.verifyMainV2UriParity(alarmId, tokenC))
            } finally {
                runCatching { am.cancel(piB); piB.cancel() }
                runCatching { am.cancel(piC); piC.cancel() }
            }
        }
    }

    // =========================================================================
    // D2: MAIN_V2 vs QuickAlarm(id=1) vs DirectBoot 3-Lane Isolation Contract
    // =========================================================================

    @Test
    fun testD2_quick_directboot_mainv2_lane_isolation() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<Context>()
            grantNotificationAndExactAlarm(context, instrumentation)

            val entryPoint = getEntryPoint(context)
            val alarmScheduler = entryPoint.alarmScheduler()

            val alarmId = 1L
            val tokenB = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            // 1. QuickAlarm (requestCode = 80001, data = null)
            val quickIntent = Intent(context, AlarmReceiver::class.java).apply {
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, now + 20_000L)
                putExtra(AlarmScheduler.EXTRA_QUICK_ALARM, true)
            }
            val quickPi = PendingIntent.getBroadcast(
                context,
                80001,
                quickIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 2. DirectBoot Alarm (requestCode = 1, data = null, distinct action)
            val directBootIntent = Intent("com.sysadmindoc.alarmclock.DIRECT_BOOT_ALARM_FIRE").apply {
                setPackage(context.packageName)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
                putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, now + 30_000L)
            }
            val directBootPi = PendingIntent.getBroadcast(
                context,
                1,
                directBootIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 3. MAIN_V2 (requestCode = 80001, data = alarmclock://alarm/issue?alarmId=1&token=$tokenB)
            val mainV2Pi = alarmScheduler.createTokenizedPendingIntent(alarmId, now + 15_000L, "fire-mainv2-$tokenB", tokenB)

            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 20_000L, quickPi)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 30_000L, directBootPi)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 15_000L, mainV2Pi)

                val lookupQuick = PendingIntent.getBroadcast(
                    context, 80001, Intent(context, AlarmReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                val lookupDirectBoot = PendingIntent.getBroadcast(
                    context, 1, Intent("com.sysadmindoc.alarmclock.DIRECT_BOOT_ALARM_FIRE").setPackage(context.packageName),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                val lookupMainV2 = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB)

                assertNotNull("QuickAlarm PI must exist before cancel", lookupQuick)
                assertNotNull("DirectBoot PI must exist before cancel", lookupDirectBoot)
                assertNotNull("MAIN_V2 PI must exist before cancel", lookupMainV2)

                // Cancel MAIN_V2
                am.cancel(mainV2Pi)
                mainV2Pi.cancel()

                val afterCancelMainV2 = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB)
                val afterCancelQuick = PendingIntent.getBroadcast(
                    context, 80001, Intent(context, AlarmReceiver::class.java),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                val afterCancelDirectBoot = PendingIntent.getBroadcast(
                    context, 1, Intent("com.sysadmindoc.alarmclock.DIRECT_BOOT_ALARM_FIRE").setPackage(context.packageName),
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )

                assertNull("MAIN_V2 PI must be canceled", afterCancelMainV2)
                assertNotNull("QuickAlarm PI must survive MAIN_V2 cancel", afterCancelQuick)
                assertNotNull("DirectBoot PI must survive MAIN_V2 cancel", afterCancelDirectBoot)
            } finally {
                runCatching { am.cancel(quickPi); quickPi.cancel() }
                runCatching { am.cancel(directBootPi); directBootPi.cancel() }
                runCatching { am.cancel(mainV2Pi); mainV2Pi.cancel() }
            }
        }
    }

    // =========================================================================
    // D3a: SIGKILL Warm Recovery Multi-Phase Contract
    // =========================================================================

    @Test
    fun testD3a_phase1_setup() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<Context>()
            grantNotificationAndExactAlarm(context, instrumentation)

            val entryPoint = getEntryPoint(context)
            val alarmScheduler = entryPoint.alarmScheduler()
            val repo = entryPoint.alarmRepository()
            val db = entryPoint.alarmDatabase()

            val alarmId = 9993L
            val tokenB = "d3a-token-b-static"
            val tokenC = "d3a-token-c-static"
            val now = System.currentTimeMillis()

            // 1. PREEXISTING_DATA_PROTECTED: Check pre-existing alarm definition for alarmId = 9993L
            val existingAlarm = repo.getById(alarmId)
            if (existingAlarm != null && existingAlarm.label != "D3a Test Alarm") {
                assumeFalse("PREEXISTING_DATA_PROTECTED: Pre-existing alarm $alarmId does not belong to D3a test suite", true)
            } else if (existingAlarm != null && existingAlarm.label == "D3a Test Alarm") {
                // Purge previous test run DB data cleanly
                repo.deleteById(alarmId)
            }

            // 2. FIXED_TOKEN_FAIL_FAST: Fail-fast if tokenB intent already exists to prevent re-insert collision
            val existingIntent = db.durableOperationIntentDao().getIntentByToken(tokenB)
            if (existingIntent != null) {
                assumeFalse("FIXED_TOKEN_FAIL_FAST: Pre-existing intent $tokenB present (phase=${existingIntent.phase}), halting setup to prevent re-insert collision", true)
            }

            // Save test-owned Alarm DB definition
            val testAlarm = com.sysadmindoc.alarmclock.data.model.Alarm(
                id = alarmId,
                hour = 8,
                minute = 30,
                label = "D3a Test Alarm",
                isEnabled = true
            )
            repo.save(testAlarm)

            // Insert real PHASE_IN_FLIGHT DurableOperationIntent
            val intentB = DurableOperationIntent(
                operationToken = tokenB,
                alarmId = alarmId,
                operationEpoch = now,
                incarnationId = UUID.randomUUID().toString(),
                operationKind = "CANCEL",
                expectedOwnerToken = "authority-token-d3a",
                expectedScheduleRevision = 1L,
                expectedRestoreEpoch = 1L,
                phase = DurableOperationIntent.PHASE_IN_FLIGHT,
                intentRevision = 2L,
                originalFireId = "fire-D3a-B",
                originalScheduledAt = now + 300_000L
            )
            db.durableOperationIntentDao().insertValidatedIntent(intentB)

            val piB = alarmScheduler.createTokenizedPendingIntent(alarmId, now + 300_000L, "fire-D3a-B", tokenB)
            val piC = alarmScheduler.createTokenizedPendingIntent(alarmId, now + 450_000L, "fire-D3a-C", tokenC)

            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 300_000L, piB)
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 450_000L, piC)

            val checkB = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB)
            val checkC = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)

            assertNotNull("D3a phase1 piB must be armed in OS", checkB)
            assertNotNull("D3a phase1 piC must be armed in OS", checkC)
            // DO NOT cancel PIs here; left armed for host SIGKILL!
        }
    }

    @Test
    fun testD3a_phase2_recovery() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val entryPoint = getEntryPoint(context)
            val db = entryPoint.alarmDatabase()

            val tokenB = "d3a-token-b-static"
            val persistedIntent = db.durableOperationIntentDao().getIntentByToken(tokenB)
            assertNotNull("D3a phase2: Durable IN_FLIGHT intent must be persisted in Room DB", persistedIntent)
            assertEquals("D3a phase2: Intent phase must be IN_FLIGHT", DurableOperationIntent.PHASE_IN_FLIGHT, persistedIntent!!.phase)
            // Automated recovery reconciler activation is CUTOVER_GATED under LEGACY_ONLY
        }
    }

    @Test
    fun testD3a_phase3_cleanup() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val entryPoint = getEntryPoint(context)
            val alarmScheduler = entryPoint.alarmScheduler()
            val repo = entryPoint.alarmRepository()
            val db = entryPoint.alarmDatabase()
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val alarmId = 9993L
            val tokenB = "d3a-token-b-static"
            val tokenC = "d3a-token-c-static"

            var cleanupFailures = 0
            val cleanupReceipts = mutableListOf<String>()

            // PHASE3_PI_CLEANUP: Cancel both tokenB and tokenC PendingIntents and verify post-lookup
            listOf(tokenB, tokenC).forEach { token ->
                try {
                    val piBefore = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, token)
                    if (piBefore != null) {
                        am.cancel(piBefore)
                        piBefore.cancel()
                    }
                    val piAfter = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, token)
                    if (piAfter != null) {
                        cleanupFailures++
                        cleanupReceipts.add("Residual PI present for token $token")
                    }
                } catch (e: Throwable) {
                    cleanupFailures++
                    cleanupReceipts.add("Cleanup error for token $token: ${e.message}")
                }
            }

            // DURABLE_PHASE_COMPLETED & ROWS_UPDATED_CONTRACT
            try {
                val rowsUpdated = db.durableOperationIntentDao().completeDurableIntentAtomic(tokenB, alarmId)
                val checkIntent = db.durableOperationIntentDao().getIntentByToken(tokenB)
                assertEquals("ROWS_UPDATED_CONTRACT: completeDurableIntentAtomic MUST return 1 row updated", 1, rowsUpdated)
                assertNotNull("DURABLE_PHASE_COMPLETED: Intent must exist in DB", checkIntent)
                assertEquals("DURABLE_PHASE_COMPLETED: Intent phase MUST be PHASE_COMPLETED", DurableOperationIntent.PHASE_COMPLETED, checkIntent!!.phase)
            } catch (e: Throwable) {
                cleanupFailures++
                cleanupReceipts.add("Durable intent completion exception for $tokenB: ${e.message}")
            }

            // PHASE3_DB_CLEANUP: Delete test-owned DB alarm
            try {
                repo.deleteById(alarmId)
                if (repo.getById(alarmId) != null) {
                    cleanupFailures++
                    cleanupReceipts.add("Residual DB alarm $alarmId present after deletion")
                }
            } catch (e: Throwable) {
                cleanupFailures++
                cleanupReceipts.add("DB deletion error for alarm $alarmId: ${e.message}")
            }

            if (cleanupFailures > 0) {
                throw IllegalStateException("D3a Cleanup Failed: $cleanupFailures errors: $cleanupReceipts")
            }
        }
    }

    // =========================================================================
    // D3b: am force-stop Negative Control Multi-Phase Contract
    // =========================================================================

    @Test
    fun testD3b_phase1_setup() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<Context>()
            grantNotificationAndExactAlarm(context, instrumentation)

            val entryPoint = getEntryPoint(context)
            val alarmScheduler = entryPoint.alarmScheduler()
            val db = entryPoint.alarmDatabase()

            val alarmId = 9994L
            val tokenB = "d3b-token-b-static"
            val now = System.currentTimeMillis()

            // Insert real IN_FLIGHT IssuedOccurrence DB row
            val occurrenceB = IssuedOccurrence(
                issueToken = tokenB,
                alarmId = alarmId,
                scheduledAt = now + 300_000L,
                fireId = "fire-D3b-B",
                generationId = 1L,
                status = IssuedOccurrence.STATUS_ISSUED,
                outcome = IssuedOccurrence.OUTCOME_PENDING
            )
            db.issuedOccurrenceDao().insertOccurrence(occurrenceB)

            val piB = alarmScheduler.createTokenizedPendingIntent(alarmId, now + 300_000L, "fire-D3b-B", tokenB)
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + 300_000L, piB)

            val checkB = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB)
            assertNotNull("D3b phase1 piB must be armed prior to force-stop", checkB)
            // DO NOT cancel PIs here; left armed for host am force-stop!
        }
    }

    @Test
    fun testD3b_phase2_verify() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val entryPoint = getEntryPoint(context)
            val alarmScheduler = entryPoint.alarmScheduler()
            val db = entryPoint.alarmDatabase()

            val tokenB = "d3b-token-b-static"

            val checkB = alarmScheduler.lookupTokenizedPendingIntentForCancel(9994L, tokenB)
            assertNull("D3b phase2: force-stop must purge all PIs from OS", checkB)

            // Strict assertion: occurrence MUST NOT be null, and outcome MUST remain PENDING (COMPLETED_SETTLEMENT == 0)
            val occurrence = db.issuedOccurrenceDao().getOccurrence(tokenB)
            assertNotNull("D3b phase2: IssuedOccurrence MUST be present in DB", occurrence)
            assertEquals("D3b phase2: Outcome must remain PENDING after force-stop purge", IssuedOccurrence.OUTCOME_PENDING, occurrence!!.outcome)
        }
    }

    @Test
    fun testD3b_phase3_cleanup() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val entryPoint = getEntryPoint(context)
            entryPoint.alarmRepository().deleteById(9994L)
        }
    }

    // =========================================================================
    // =========================================================================
    // D4: FBE Cold Boot 2-Alarm Isolated Capability Sequence Contract
    // =========================================================================

    @Test
    fun testD4_phase1_setup() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<Context>()
            grantNotificationAndExactAlarm(context, instrumentation)

            val entryPoint = getEntryPoint(context)
            val repo = entryPoint.alarmRepository()
            val alarmScheduler = entryPoint.alarmScheduler()
            val deContext = context.createDeviceProtectedStorageContext()

            val alarmId1 = 9996L
            val alarmId2 = 9999L
            val label1 = "D4 FBE Probe 1"
            val label2 = "D4 FBE Probe 2"
            val now = System.currentTimeMillis()
            val triggerTime1 = now + 180_000L // T+3min
            val triggerTime2 = now + 300_000L // T+5min
            val sessionToken = "d4-sess-" + UUID.randomUUID().toString().take(8)
            val restoreEpoch = now
            val token1 = "d4-v2-token-$alarmId1-$sessionToken"
            val token2 = "d4-v2-token-$alarmId2-$sessionToken"

            // 1. PREEXISTING_DATA_PROTECTED: Check pre-existing alarm definitions for 9996L & 9999L
            // Require proven D4 test-session ownership via DE grant before deleting any pre-existing row
            val existing1 = repo.getById(alarmId1)
            if (existing1 != null) {
                if (DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deContext, existing1, label1)) {
                    repo.deleteById(alarmId1)
                } else {
                    assumeFalse("PREEXISTING_DATA_PROTECTED: Pre-existing alarm $alarmId1 lacks proven D4 session ownership", true)
                }
            }

            val existing2 = repo.getById(alarmId2)
            if (existing2 != null) {
                if (DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deContext, existing2, label2)) {
                    repo.deleteById(alarmId2)
                } else {
                    assumeFalse("PREEXISTING_DATA_PROTECTED: Pre-existing alarm $alarmId2 lacks proven D4 session ownership", true)
                }
            }

            // 2. V1 & Non-D4 Queue Isolation Pre-Check
            val existingV1Snapshot = DirectBootAlarmCache.read(deContext)
            if (existingV1Snapshot != null && existingV1Snapshot.triggerTime in now..(now + 35 * 60_000L)) {
                assumeFalse("PREEXISTING_V1_CONFLICT: Active V1 DirectBoot snapshot ${existingV1Snapshot.alarmId} triggers within D4 35m window", true)
            }
            val conflictingEnabled = repo.getEnabled().filter {
                it.id != alarmId1 && it.id != alarmId2 && it.nextTriggerTime in now..(now + 35 * 60_000L)
            }
            if (conflictingEnabled.isNotEmpty()) {
                assumeFalse("PREEXISTING_ENABLED_ALARM_CONFLICT: Enabled CE alarms conflict within D4 35m window", true)
            }
            val preQueueState = DirectBootAlarmCache.inspectQueueStorageState(deContext)
            if (preQueueState is QueueStorageState.Corrupt || preQueueState is QueueStorageState.UnsupportedSchema) {
                assumeFalse("PREEXISTING_QUEUE_UNVERIFIABLE: DE Queue storage is $preQueueState", true)
            }
            if (preQueueState is QueueStorageState.ValidWithItems && preQueueState.items.isNotEmpty()) {
                val preGrant = DirectBootAlarmCache.readD4IsolatedCapability(deContext)
                val hasUnownedItem = preQueueState.items.any { item ->
                    preGrant == null ||
                        preGrant.revoked ||
                        preGrant.allowedOccurrences.none { occ ->
                            occ.alarmId == item.alarmId &&
                                occ.generation == item.generation &&
                                occ.instanceToken == item.instanceToken &&
                                occ.triggerTime == item.triggerTime &&
                                occ.restoreEpoch == item.restoreEpoch &&
                                item.instanceToken.contains(preGrant.sessionToken)
                        }
                }
                if (hasUnownedItem) {
                    assumeFalse("PREEXISTING_QUEUE_PROTECTED: DE Queue contains items without proven D4 session ownership", true)
                }
            }

            // 3. Read pre-reboot BOOT_COUNT strictly
            val preBootCount = try {
                Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
            } catch (t: Throwable) {
                throw IllegalStateException("D4 phase1 failed: BOOT_COUNT unavailable", t)
            }
            assertTrue("D4 phase1: preBootCount must be >= 0 (was $preBootCount)", preBootCount >= 0)

            // 4. Save test-owned Room definitions for Alarm 1 (9996L) & Alarm 2 (9999L)
            // Under LEGACY_ONLY, AlarmScheduler projection hook is SkippedLegacyOnly; repo.save only writes CE Room.
            val alarm1 = com.sysadmindoc.alarmclock.data.model.Alarm(
                id = alarmId1,
                hour = 8,
                minute = 0,
                label = label1,
                isEnabled = true,
                nextTriggerTime = triggerTime1
            )
            val alarm2 = com.sysadmindoc.alarmclock.data.model.Alarm(
                id = alarmId2,
                hour = 8,
                minute = 2,
                label = label2,
                isEnabled = true,
                nextTriggerTime = triggerTime2
            )
            repo.save(alarm1)
            repo.save(alarm2)

            // 5. Persist D4 Isolated Capability Grant targeting targetBootCount = preBootCount + 1 (TTL = 30m)
            val grant = D4IsolatedCapabilityGrant(
                schemaVersion = 1,
                testSuiteTag = "D4_REAL_OS_FBE",
                sessionToken = sessionToken,
                issuedAtEpochMs = now,
                expiryEpochMs = now + 30 * 60_000L,
                targetBootCount = preBootCount + 1,
                allowedOccurrences = listOf(
                    D4IsolatedOccurrenceGrant(
                        alarmId = alarmId1,
                        generation = 1,
                        instanceToken = token1,
                        triggerTime = triggerTime1,
                        restoreEpoch = restoreEpoch,
                        admitted = false
                    ),
                    D4IsolatedOccurrenceGrant(
                        alarmId = alarmId2,
                        generation = 1,
                        instanceToken = token2,
                        triggerTime = triggerTime2,
                        restoreEpoch = restoreEpoch,
                        admitted = false
                    )
                ),
                revoked = false
            )
            assertTrue(
                "D4 phase1: writeD4IsolatedCapability must succeed",
                DirectBootAlarmCache.writeD4IsolatedCapability(deContext, grant)
            )

            // 6. Write DE Queue directly (DO NOT call saveIfEarlier or DirectBootRegistrationReconciler.reconcile pre-reboot)
            val queueItem1 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = alarmId1,
                triggerTime = triggerTime1,
                generation = 1,
                instanceToken = token1,
                restoreEpoch = restoreEpoch,
                timeLabel = "08:00"
            )
            val queueItem2 = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = alarmId2,
                triggerTime = triggerTime2,
                generation = 1,
                instanceToken = token2,
                restoreEpoch = restoreEpoch,
                timeLabel = "08:02"
            )
            assertTrue(
                "D4 phase1: writeQueue must succeed",
                DirectBootAlarmCache.writeQueue(deContext, listOf(queueItem1, queueItem2), now = now)
            )

            // 7. Verify zero V1 PI, zero V1 D4 scalar snapshot, and zero pre-reboot V2 PI
            assertNull("D4 phase1: zero V1 PI may be pre-armed", alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId1, token1))
            assertNull("D4 phase1: zero V1 PI may be pre-armed", alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId2, token2))
            val postV1Snapshot = DirectBootAlarmCache.read(deContext)
            assertFalse(
                "D4 phase1: V1 scalar snapshot must NOT be written for D4 alarms",
                postV1Snapshot != null && (postV1Snapshot.alarmId == alarmId1 || postV1Snapshot.alarmId == alarmId2)
            )
            val id1 = DirectBootV2NormalIntentIdentity.fromQueueItem(queueItem1)
            val id2 = DirectBootV2NormalIntentIdentity.fromQueueItem(queueItem2)
            assertNull("D4 phase1: zero pre-reboot V2 PI for 9996L", id1.lookupPendingIntentForCancel(context))
            assertNull("D4 phase1: zero pre-reboot V2 PI for 9999L", id2.lookupPendingIntentForCancel(context))
        }
    }

    @Test
    fun testD4_phase2_locked_verify() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val userManager = context.getSystemService(UserManager::class.java)
            val isUnlocked = userManager?.isUserUnlocked ?: true

            assertTrue(
                "D4 Phase 2 MUST be executed when device is locked in DirectBoot state (isUserUnlocked == false)",
                !isUnlocked
            )

            val deContext = context.createDeviceProtectedStorageContext()
            val grant = DirectBootAlarmCache.readD4IsolatedCapability(deContext)
            assertNotNull("D4 Phase 2: D4 isolated capability grant must exist in DE storage", grant)
            assertFalse("D4 Phase 2: D4 grant must not be revoked", grant!!.revoked)

            val currentBootCount = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
            assertEquals("D4 Phase 2: currentBootCount must match grant.targetBootCount", grant.targetBootCount, currentBootCount)

            // Verify DE domain health during DirectBoot locked state
            val queueState = DirectBootAlarmCache.inspectQueueStorageState(deContext)
            assertTrue("D4 Phase 2: DE Queue state must not be corrupt/unsupported ($queueState)", queueState !is QueueStorageState.Corrupt && queueState !is QueueStorageState.UnsupportedSchema)
            val ledgerState = DirectBootDeliveryLedger.inspectStorageState(deContext)
            assertTrue("D4 Phase 2: DE DeliveryLedger must not be corrupt/unsupported ($ledgerState)", ledgerState !is LedgerStorageState.Corrupt && ledgerState !is LedgerStorageState.UnsupportedSchema)
            val witnessState = DirectBootFiredWitnessLedger.readWitnesses(deContext)
            assertTrue("D4 Phase 2: DE FiredWitnessLedger must not be corrupt/unsupported ($witnessState)", witnessState !is WitnessLedgerState.Corrupt && witnessState !is WitnessLedgerState.UnsupportedSchema)
            val ownerState = DirectBootDurableOwnerStore.read(deContext)
            assertTrue("D4 Phase 2: DE DurableOwner must not be corrupt/unsupported ($ownerState)", ownerState !is OwnerReadResult.Corrupt && ownerState !is OwnerReadResult.UnsupportedSchema)

            // DirectBoot locked CE verification: CE-backed Room DB read query MUST fail with IllegalStateException
            var ceAccessed = false
            try {
                val entryPoint = getEntryPoint(context)
                val repo = entryPoint.alarmRepository()
                repo.getById(9996L)
                ceAccessed = true
            } catch (e: IllegalStateException) {
                // Expected: CE Room DB query inaccessible under DirectBoot
            } catch (e: Throwable) {
                if (e !is IllegalStateException && e.cause !is IllegalStateException) {
                    throw IllegalStateException("D4 Locked Verify: Unexpected non-CE exception during query", e)
                }
            }
            assertFalse("CE Room DB query MUST NOT succeed during DirectBoot locked state", ceAccessed)
        }
    }

    @Test
    fun testD4_phase3_unlocked_verify() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val userManager = context.getSystemService(UserManager::class.java)
            val isUnlocked = userManager?.isUserUnlocked ?: true

            assertTrue("D4 phase3: User must be unlocked", isUnlocked)

            val deContext = context.createDeviceProtectedStorageContext()
            val grant = DirectBootAlarmCache.readD4IsolatedCapability(deContext)
            assertNotNull("D4 phase3: D4 grant must exist for sessionToken/restoreEpoch correlation", grant)
            val occ1 = grant!!.allowedOccurrences.first { it.alarmId == 9996L }
            val occ2 = grant.allowedOccurrences.first { it.alarmId == 9999L }

            val entryPoint = getEntryPoint(context)
            val repo = entryPoint.alarmRepository()
            val db = entryPoint.alarmDatabase()
            val receiptDao = db.directBootOccurrenceReceiptDao()

            assertNotNull("D4 phase3: Alarm 1 (9996L) must be recovered in CE DB after unlock", repo.getById(9996L))
            assertNotNull("D4 phase3: Alarm 2 (9999L) must be recovered in CE DB after unlock", repo.getById(9999L))

            // Wait boundedly for unlock handoff/consumer retirement into Room 38 receipts
            var receipt1 = receiptDao.findByAlarmId(9996L).firstOrNull {
                it.instanceToken == occ1.instanceToken &&
                    it.restoreEpoch == occ1.restoreEpoch &&
                    it.consumerOutcome == "CONSUMED_DELIVERED"
            }
            var receipt2 = receiptDao.findByAlarmId(9999L).firstOrNull {
                it.instanceToken == occ2.instanceToken &&
                    it.restoreEpoch == occ2.restoreEpoch &&
                    it.consumerOutcome == "CONSUMED_DELIVERED"
            }
            val deadlineMs = System.currentTimeMillis() + 15_000L
            while ((receipt1 == null || receipt2 == null) && System.currentTimeMillis() < deadlineMs) {
                delay(250L)
                if (receipt1 == null) {
                    receipt1 = receiptDao.findByAlarmId(9996L).firstOrNull {
                        it.instanceToken == occ1.instanceToken &&
                            it.restoreEpoch == occ1.restoreEpoch &&
                            it.consumerOutcome == "CONSUMED_DELIVERED"
                    }
                }
                if (receipt2 == null) {
                    receipt2 = receiptDao.findByAlarmId(9999L).firstOrNull {
                        it.instanceToken == occ2.instanceToken &&
                            it.restoreEpoch == occ2.restoreEpoch &&
                            it.consumerOutcome == "CONSUMED_DELIVERED"
                    }
                }
            }
            assertNotNull("D4 phase3: Room 38 CONSUMED_DELIVERED receipt required for Alarm 9996L", receipt1)
            assertNotNull("D4 phase3: Room 38 CONSUMED_DELIVERED receipt required for Alarm 9999L", receipt2)
        }
    }

    private data class D4TargetSpec(
        val alarmId: Long,
        val expectedHour: Int,
        val expectedMinute: Int,
        val expectedLabel: String
    )

    private data class D4VerifiedTargetPlan(
        val spec: D4TargetSpec,
        val occurrence: D4IsolatedOccurrenceGrant,
        val receipt: DirectBootOccurrenceReceipt,
        val expectedCeAlarm: Alarm?
    )

    private fun lookupCanonicalV1AlarmReceiverPendingIntent(
        context: Context,
        alarmId: Long
    ): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
        }
        return PendingIntent.getBroadcast(
            context,
            alarmId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun isValidD4RolloverTrigger(
        alarm: Alarm,
        occ: D4IsolatedOccurrenceGrant,
        expectedHour: Int,
        expectedMinute: Int,
        calculator: NextAlarmCalculator,
        nowMs: Long
    ): Boolean {
        if (!alarm.isEnabled) return false
        if (alarm.nextTriggerTime <= occ.triggerTime) return false
        if ((alarm.nextTriggerTime - occ.triggerTime) > 7L * 24L * 60L * 60L * 1000L) return false
        val scheduleZone = alarm.schedulingZone(ZoneId.systemDefault())
        val triggerZdt = ZonedDateTime.ofInstant(Instant.ofEpochMilli(alarm.nextTriggerTime), scheduleZone)
        if (triggerZdt.hour != expectedHour ||
            triggerZdt.minute != expectedMinute ||
            triggerZdt.second != 0 ||
            triggerZdt.nano != 0
        ) {
            return false
        }
        val firstRollover = calculator.calculate(
            alarm,
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(occ.triggerTime), scheduleZone)
        )
        val latestRollover = calculator.calculate(
            alarm,
            ZonedDateTime.ofInstant(Instant.ofEpochMilli(maxOf(nowMs, occ.triggerTime)), scheduleZone)
        )
        return firstRollover > occ.triggerTime &&
            latestRollover >= firstRollover &&
            alarm.nextTriggerTime in firstRollover..latestRollover
    }

    @Test
    fun testD4_phase4_cleanup() {
        runBlocking {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val entryPoint = getEntryPoint(context)
            val repo = entryPoint.alarmRepository()
            val db = entryPoint.alarmDatabase()
            val receiptDao = db.directBootOccurrenceReceiptDao()
            val alarmScheduler = entryPoint.alarmScheduler()
            val nextAlarmCalculator = entryPoint.nextAlarmCalculator()
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val deContext = context.createDeviceProtectedStorageContext()

            val targetSpecs = listOf(
                D4TargetSpec(9996L, 8, 0, "D4 FBE Probe 1"),
                D4TargetSpec(9999L, 8, 2, "D4 FBE Probe 2")
            )
            val targetIds = targetSpecs.map { it.alarmId }.toSet()

            var cleanupFailures = 0
            val cleanupReceipts = mutableListOf<String>()

            // Stage 1: Exact V2 PendingIntent cancellation from active D4 grant occurrences.
            // Fail-closed: any PI cancellation error or residual PI halts all later destructive stages and preserves Grant.
            val grant = DirectBootAlarmCache.readD4IsolatedCapability(deContext)
            var stage1Ok = true
            if (grant != null && !grant.revoked) {
                for (occ in grant.allowedOccurrences) {
                    try {
                        val identity = DirectBootV2NormalIntentIdentity(
                            alarmId = occ.alarmId,
                            generation = occ.generation,
                            instanceToken = occ.instanceToken,
                            triggerTime = occ.triggerTime,
                            restoreEpoch = occ.restoreEpoch
                        )
                        val piBefore = identity.lookupPendingIntentForCancel(context)
                        if (piBefore != null) {
                            am.cancel(piBefore)
                            piBefore.cancel()
                        }
                        val piAfter = identity.lookupPendingIntentForCancel(context)
                        if (piAfter != null) {
                            cleanupFailures++
                            cleanupReceipts.add("Residual V2 PI present for alarm ${occ.alarmId}")
                            stage1Ok = false
                            break
                        }
                    } catch (e: Throwable) {
                        cleanupFailures++
                        cleanupReceipts.add("Cleanup V2 PI error for alarm ${occ.alarmId}: ${e.message}")
                        stage1Ok = false
                        break
                    }
                }
            }

            // Stage 2: Exact 7-field removal of D4 items from DE Queue (never alarmId-only, clear(), or clearQueue()).
            // Gated on Stage 1 success; Blocked / PersistenceFailed / Throwable halts Stage 3 and Stage 4.
            var stage2Ok = false
            if (stage1Ok && cleanupFailures == 0) {
                try {
                    when (val exactCleanup = DirectBootAlarmCache.removeD4QueueItemsExact(deContext)) {
                        is D4ExactCleanupResult.Removed,
                        is D4ExactCleanupResult.NothingToRemove -> {
                            stage2Ok = true
                        }
                        is D4ExactCleanupResult.Blocked -> {
                            cleanupFailures++
                            cleanupReceipts.add("DE Queue exact cleanup BLOCKED: ${exactCleanup.reason}")
                        }
                        is D4ExactCleanupResult.PersistenceFailed -> {
                            cleanupFailures++
                            cleanupReceipts.add("DE Queue exact cleanup failed: ${exactCleanup.reason}")
                        }
                    }
                } catch (e: Throwable) {
                    cleanupFailures++
                    cleanupReceipts.add("DE Queue exact removal error: ${e.message}")
                }
            }

            // Stage 3 (Stage 3a Ownership Proof -> Stage 3b V1 PI Cancel -> Stage 3c CE Delete)
            // Serialized with AlarmScheduler/BootRescheduleWorker via ScheduleAuthorityOwner.withAuthenticatedRecoveryGate.
            var stage3Ok = false
            if (stage1Ok && stage2Ok && cleanupFailures == 0) {
                ScheduleAuthorityOwner.withAuthenticatedRecoveryGate(
                    context = context,
                    onBlocked = {
                        cleanupFailures++
                        cleanupReceipts.add("BLOCKED: ScheduleAuthorityOwner recovery gate blocked Stage 3")
                    }
                ) {
                    val nowMs = System.currentTimeMillis()
                    val liveGrant = DirectBootAlarmCache.readD4IsolatedCapability(deContext)
                    if (liveGrant == null || liveGrant.revoked) {
                        val hasLingeringArtifacts = targetSpecs.any { spec ->
                            repo.getById(spec.alarmId) != null ||
                                lookupCanonicalV1AlarmReceiverPendingIntent(context, spec.alarmId) != null
                        }
                        if (hasLingeringArtifacts) {
                            cleanupFailures++
                            cleanupReceipts.add("BLOCKED: D4 grant missing or revoked while CE/V1 D4 artifacts still exist")
                        } else {
                            stage3Ok = true
                        }
                        return@withAuthenticatedRecoveryGate
                    }

                    // Stage 3a: Validate Grant + ALL target Occurrences + Room 38 Receipts + CE Ownership/Rollover BEFORE any mutation.
                    val verifiedPlans = mutableListOf<D4VerifiedTargetPlan>()
                    var proofFailed = false

                    val grantStructuralValid =
                        liveGrant.schemaVersion == 1 &&
                            liveGrant.testSuiteTag == "D4_REAL_OS_FBE" &&
                            !liveGrant.revoked &&
                            liveGrant.sessionToken.isNotBlank() &&
                            liveGrant.targetBootCount >= 0 &&
                            liveGrant.issuedAtEpochMs > 0L &&
                            liveGrant.expiryEpochMs > liveGrant.issuedAtEpochMs &&
                            (liveGrant.expiryEpochMs - liveGrant.issuedAtEpochMs) <= 30L * 60_000L &&
                            liveGrant.allowedOccurrences.all { it.alarmId in targetIds }

                    if (!grantStructuralValid) {
                        cleanupFailures++
                        cleanupReceipts.add("BLOCKED: D4 grant failed structural validation")
                        proofFailed = true
                    }

                    if (!proofFailed) {
                        for (spec in targetSpecs) {
                            try {
                                val matchingOccs = liveGrant.allowedOccurrences.filter { it.alarmId == spec.alarmId }
                                if (matchingOccs.size > 1) {
                                    cleanupFailures++
                                    cleanupReceipts.add("BLOCKED: Multiple Grant occurrences for alarm ${spec.alarmId}")
                                    proofFailed = true
                                    break
                                }
                                val existingCe = repo.getById(spec.alarmId)
                                val existingV1Pi = lookupCanonicalV1AlarmReceiverPendingIntent(context, spec.alarmId)
                                if (matchingOccs.isEmpty()) {
                                    if (existingCe != null || existingV1Pi != null) {
                                        cleanupFailures++
                                        cleanupReceipts.add("BLOCKED: D4 target ${spec.alarmId} has artifacts outside active Grant")
                                        proofFailed = true
                                        break
                                    }
                                    continue
                                }

                                val occ = matchingOccs.single()
                                val expectedToken = "d4-v2-token-${spec.alarmId}-${liveGrant.sessionToken}"
                                val occValid =
                                    occ.generation >= 1 &&
                                        occ.triggerTime > 0L &&
                                        occ.restoreEpoch > 0L &&
                                        occ.instanceToken == expectedToken &&
                                        occ.triggerTime in liveGrant.issuedAtEpochMs..liveGrant.expiryEpochMs
                                if (!occValid) {
                                    cleanupFailures++
                                    cleanupReceipts.add("BLOCKED: D4 occurrence identity/window invalid for ${spec.alarmId}")
                                    proofFailed = true
                                    break
                                }

                                // Rules 9-11: Mandatory, unique, exact 5-tuple Room 38 CONSUMED_DELIVERED receipt
                                val receipts = receiptDao.findByAlarmId(spec.alarmId)
                                if (receipts.size != 1) {
                                    cleanupFailures++
                                    cleanupReceipts.add("BLOCKED: Expected exactly 1 Room 38 receipt for ${spec.alarmId}, found ${receipts.size}")
                                    proofFailed = true
                                    break
                                }
                                val receipt = receipts.single()
                                val receiptExactMatch =
                                    receipt.alarmId == occ.alarmId &&
                                        receipt.generation == occ.generation &&
                                        receipt.instanceToken == occ.instanceToken &&
                                        receipt.triggerTime == occ.triggerTime &&
                                        receipt.restoreEpoch == occ.restoreEpoch &&
                                        receipt.consumerOutcome == "CONSUMED_DELIVERED" &&
                                        receipt.completedAt in liveGrant.issuedAtEpochMs..liveGrant.expiryEpochMs
                                if (!receiptExactMatch) {
                                    cleanupFailures++
                                    cleanupReceipts.add("BLOCKED: Room 38 receipt mismatch for ${spec.alarmId}")
                                    proofFailed = true
                                    break
                                }

                                // Rule 14: Expired Grant requires delivery-admission evidence (occ.admitted == true) + valid Room 38 receipt
                                if (nowMs > liveGrant.expiryEpochMs && !occ.admitted) {
                                    cleanupFailures++
                                    cleanupReceipts.add("BLOCKED: Expired D4 grant lacks delivery admission proof for ${spec.alarmId}")
                                    proofFailed = true
                                    break
                                }

                                // Rules 12-13: Validate CE Alarm identity + exact trigger OR proven BootRescheduleWorker rollover
                                if (existingCe != null) {
                                    val identityMatch =
                                        !existingCe.isQuickAlarm &&
                                            existingCe.id == spec.alarmId &&
                                            existingCe.label == spec.expectedLabel &&
                                            existingCe.hour == spec.expectedHour &&
                                            existingCe.minute == spec.expectedMinute &&
                                            existingCe.generation == occ.generation
                                    val triggerMatch = if (existingCe.nextTriggerTime == occ.triggerTime) {
                                        DirectBootAlarmCache.isCeAlarmOwnedByD4Grant(deContext, existingCe, spec.expectedLabel)
                                    } else {
                                        isValidD4RolloverTrigger(
                                            alarm = existingCe,
                                            occ = occ,
                                            expectedHour = spec.expectedHour,
                                            expectedMinute = spec.expectedMinute,
                                            calculator = nextAlarmCalculator,
                                            nowMs = nowMs
                                        )
                                    }
                                    if (!identityMatch || !triggerMatch) {
                                        cleanupFailures++
                                        cleanupReceipts.add("BLOCKED: Refused CE/V1 cleanup for alarm ${spec.alarmId} due to identity/trigger mismatch")
                                        proofFailed = true
                                        break
                                    }
                                }

                                verifiedPlans.add(D4VerifiedTargetPlan(spec, occ, receipt, existingCe))
                            } catch (e: Throwable) {
                                cleanupFailures++
                                cleanupReceipts.add("Stage 3a error for alarm ${spec.alarmId}: ${e.message}")
                                proofFailed = true
                                break
                            }
                        }
                    }

                    // Stage 3b: Pre-3b TOCTOU Re-Check + Exact Canonical V1 PI Cancellation
                    var stage3bOk = false
                    if (!proofFailed && cleanupFailures == 0) {
                        var v1Failed = false
                        for (plan in verifiedPlans) {
                            try {
                                val currentGrant = DirectBootAlarmCache.readD4IsolatedCapability(deContext)
                                val currentReceipts = receiptDao.findByAlarmId(plan.spec.alarmId)
                                val currentCe = repo.getById(plan.spec.alarmId)
                                val unchangedBefore3b =
                                    currentGrant == liveGrant &&
                                        currentReceipts.size == 1 &&
                                        currentReceipts.single() == plan.receipt &&
                                        currentCe == plan.expectedCeAlarm
                                if (!unchangedBefore3b) {
                                    cleanupFailures++
                                    cleanupReceipts.add("TOCTOU_PRE_3B_STATE_CHANGED:${plan.spec.alarmId}")
                                    v1Failed = true
                                    break
                                }

                                if (!alarmScheduler.applyRawOsCancel(plan.spec.alarmId)) {
                                    cleanupFailures++
                                    cleanupReceipts.add("Stage 3b: applyRawOsCancel returned false for ${plan.spec.alarmId}")
                                    v1Failed = true
                                    break
                                }
                                if (lookupCanonicalV1AlarmReceiverPendingIntent(context, plan.spec.alarmId) != null) {
                                    cleanupFailures++
                                    cleanupReceipts.add("Stage 3b: Residual V1 PI present for ${plan.spec.alarmId}")
                                    v1Failed = true
                                    break
                                }
                            } catch (e: Throwable) {
                                cleanupFailures++
                                cleanupReceipts.add("Stage 3b error for ${plan.spec.alarmId}: ${e.message}")
                                v1Failed = true
                                break
                            }
                        }
                        if (!v1Failed && cleanupFailures == 0) {
                            stage3bOk = true
                        }
                    }

                    // Stage 3c: Pre-3c Grant Re-Check + Atomic SQLite Transaction CE Re-Verify & Delete
                    if (stage3bOk && cleanupFailures == 0) {
                        var ceFailed = false
                        for (plan in verifiedPlans) {
                            try {
                                val currentGrant = DirectBootAlarmCache.readD4IsolatedCapability(deContext)
                                if (currentGrant != liveGrant) {
                                    cleanupFailures++
                                    cleanupReceipts.add("TOCTOU_PRE_3C_GRANT_CHANGED:${plan.spec.alarmId}")
                                    ceFailed = true
                                    break
                                }

                                val expectedExisting = plan.expectedCeAlarm
                                if (expectedExisting != null) {
                                    db.withTransaction {
                                        val txCe = repo.getById(plan.spec.alarmId)
                                        val txReceipts = receiptDao.findByAlarmId(plan.spec.alarmId)
                                        if (txCe != expectedExisting ||
                                            txReceipts.size != 1 ||
                                            txReceipts.single() != plan.receipt
                                        ) {
                                            throw IllegalStateException("TOCTOU_IN_TX_STATE_CHANGED:${plan.spec.alarmId}")
                                        }
                                        repo.deleteById(plan.spec.alarmId)
                                        if (repo.getById(plan.spec.alarmId) != null) {
                                            throw IllegalStateException("Residual DB alarm ${plan.spec.alarmId} present after deletion")
                                        }
                                    }
                                }
                            } catch (e: Throwable) {
                                cleanupFailures++
                                cleanupReceipts.add("Stage 3c error for alarm ${plan.spec.alarmId}: ${e.message}")
                                ceFailed = true
                                break
                            }
                        }
                        if (!ceFailed && cleanupFailures == 0) {
                            stage3Ok = true
                        }
                    }
                }
            }

            // Stage 4: Target-scoped revocation of D4 isolated capability grant ONLY after Stages 1, 2, and 3 ALL succeeded.
            if (stage1Ok && stage2Ok && stage3Ok && cleanupFailures == 0) {
                try {
                    val currentGrant = DirectBootAlarmCache.readD4IsolatedCapability(deContext)
                    if (currentGrant != null && !currentGrant.revoked) {
                        if (!DirectBootAlarmCache.revokeD4IsolatedCapability(deContext)) {
                            cleanupFailures++
                            cleanupReceipts.add("Failed to revoke D4 isolated capability grant")
                        }
                    }
                } catch (e: Throwable) {
                    cleanupFailures++
                    cleanupReceipts.add("D4 capability revocation error: ${e.message}")
                }
            }

            if (cleanupFailures > 0) {
                throw IllegalStateException("D4 Cleanup Failed: $cleanupFailures errors: $cleanupReceipts")
            }
        }
    }

    // =========================================================================
    // D5: Binder Concurrency Smoke Probe (N=20) Contract
    // =========================================================================

    /**
     * D5 (E1 Fail-Closed Contract): Under LEGACY_ONLY route, production AlarmScheduler
     * tokenized scheduling APIs (applyTokenizedOsSet & cancelTokenizedScheduledAlarm)
     * fail-closed (return false) because ScheduleAuthorityOwner is dormant.
     */
    @Test
    fun testD5_e1_binder_concurrency_fail_closed_smoke_n20() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<Context>()
            grantNotificationAndExactAlarm(context, instrumentation)

            val entryPoint = getEntryPoint(context)
            val alarmScheduler = entryPoint.alarmScheduler()
            val alarmId = 9995L
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val trackedTokens = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val cleanupReceipts = java.util.concurrent.ConcurrentHashMap<String, String>()

            var primaryException: Throwable? = null

            try {
                coroutineScope {
                    val jobs = (0 until 20).map { iteration ->
                        async {
                            val tokenB = UUID.randomUUID().toString()
                            val tokenC = UUID.randomUUID().toString()
                            val now = System.currentTimeMillis()

                            // Track created token identities for cleanup in try/finally
                            trackedTokens.add(tokenB)
                            trackedTokens.add(tokenC)

                            // Under LEGACY_ONLY route, applyTokenizedOsSet returns false (fail-closed)
                            val applyB = alarmScheduler.applyTokenizedOsSet(alarmId, now + 15_000L, tokenB)
                            val applyC = alarmScheduler.applyTokenizedOsSet(alarmId, now + 25_000L, tokenC)

                            // Under LEGACY_ONLY route, cancelTokenizedScheduledAlarm returns false (fail-closed)
                            val cancelB = alarmScheduler.cancelTokenizedScheduledAlarm(alarmId, tokenB)

                            // E1 Fail-Closed Assertions: Confirm LEGACY_ONLY route refuses owner mutation
                            assertTrue("E1 Fail-Closed: applyTokenizedOsSet B must return false under LEGACY_ONLY", !applyB)
                            assertTrue("E1 Fail-Closed: applyTokenizedOsSet C must return false under LEGACY_ONLY", !applyC)
                            assertTrue("E1 Fail-Closed: cancelTokenizedScheduledAlarm B must return false under LEGACY_ONLY", !cancelB)
                        }
                    }
                    jobs.awaitAll()
                }

                assertEquals("D5 E1: Exactly 20 concurrent iterations completed", 20, trackedTokens.size / 2)
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                // Robust Cleanup: Attempt cleanup and record receipts for all tracked token identities
                var cleanupFailureCount = 0
                trackedTokens.forEach { token ->
                    try {
                        val piBefore = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, token)
                        if (piBefore != null) {
                            am.cancel(piBefore)
                            piBefore.cancel()
                        }
                        val piAfter = alarmScheduler.lookupTokenizedPendingIntentForCancel(alarmId, token)
                        val status = if (piAfter == null) "PURGED_OR_ABSENT" else "RESIDUAL_PI_PRESENT"
                        cleanupReceipts[token] = status
                        if (piAfter != null) {
                            cleanupFailureCount++
                        }
                    } catch (e: Throwable) {
                        cleanupReceipts[token] = "EXCEPTIONAL: ${e.message}"
                        cleanupFailureCount++
                    }
                }

                if (primaryException == null && cleanupFailureCount > 0) {
                    throw IllegalStateException("D5 Cleanup Verification Failed: $cleanupFailureCount residual PIs detected or cleanup exceptions recorded: $cleanupReceipts")
                }
            }
        }
    }

    /**
     * D5 (E2 Normal System Contract): Tests normal single-writer slot lock serialization & cancellation
     * when owner route is active via test-scoped routeAuthority injection with safe future triggers.
     */
    @Test
    fun testD5_e2_normal_cancellation_under_owner_route() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<Context>()
            grantNotificationAndExactAlarm(context, instrumentation)

            val entryPoint = getEntryPoint(context)
            val baseScheduler = entryPoint.alarmScheduler()
            val alarmId = 9997L
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            // Verify production default route authority remains LEGACY_ONLY 100% unchanged
            val defaultProdAuthority = ProductionSchedulingRouteAuthority()
            assertFalse("Production default route MUST remain LEGACY_ONLY", defaultProdAuthority.isOwnerRouteActive())

            val mockOwnerRouteAuthority = ProductionSchedulingRouteAuthority()
            mockOwnerRouteAuthority.javaClass.getDeclaredField("currentRoute").apply {
                isAccessible = true
                set(mockOwnerRouteAuthority, ProductionSchedulingRoute.OWNER_EXCLUSIVE)
            }
            assertTrue("Test-scoped route authority MUST evaluate isOwnerRouteActive = true", mockOwnerRouteAuthority.isOwnerRouteActive())

            // Create test-scoped owner scheduler without touching default production LEGACY_ONLY route
            val ownerScheduler = AlarmScheduler(
                context = context,
                repository = entryPoint.alarmRepository(),
                calculator = entryPoint.nextAlarmCalculator(),
                preferencesManager = baseScheduler.let {
                    it.javaClass.getDeclaredField("preferencesManager").apply { isAccessible = true }.get(it) as com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
                },
                holidayRepository = baseScheduler.let {
                    it.javaClass.getDeclaredField("holidayRepository").apply { isAccessible = true }.get(it) as com.sysadmindoc.alarmclock.data.repository.HolidayRepository
                },
                alarmIncidentRepository = entryPoint.alarmIncidentRepository(),
                weatherRepository = baseScheduler.let {
                    it.javaClass.getDeclaredField("weatherRepository").apply { isAccessible = true }.get(it) as com.sysadmindoc.alarmclock.data.repository.WeatherRepository
                },
                scheduleAuthorityOwnerProvider = baseScheduler.let {
                    @Suppress("UNCHECKED_CAST")
                    it.javaClass.getDeclaredField("scheduleAuthorityOwnerProvider").apply { isAccessible = true }.get(it) as javax.inject.Provider<ScheduleAuthorityOwner>
                },
                routeAuthority = mockOwnerRouteAuthority
            )

            val tokenB = UUID.randomUUID().toString()
            val tokenC = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            // Safe future trigger times (+5 minutes and +7.5 minutes) to avoid accidental near-fire execution
            val triggerB = now + 300_000L
            val triggerC = now + 450_000L

            var primaryException: Throwable? = null

            try {
                // 1. SET B -> true
                val setB = ownerScheduler.applyTokenizedOsSet(alarmId, triggerB, tokenB)
                assertTrue("E2 Step 1: applyTokenizedOsSet B must succeed under owner route", setB)

                val checkB = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB)
                assertNotNull("E2 Step 1: tokenB must be armed in OS before reschedule", checkB)

                // 2. SET C -> true (automatically replaces B in slot and retires B)
                val setC = ownerScheduler.applyTokenizedOsSet(alarmId, triggerC, tokenC)
                assertTrue("E2 Step 2: applyTokenizedOsSet C must succeed under owner route", setC)

                // 3. Stale B Cancel -> false (expected false: tokenB was already automatically retired by C)
                val cancelB = ownerScheduler.cancelTokenizedScheduledAlarm(alarmId, tokenB)
                assertFalse("E2 Step 3: cancelTokenizedScheduledAlarm B MUST return false because tokenB was already retired by successor C", cancelB)

                // 4. Verify Stale B PI does not exist
                val checkAfterCancelB = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB)
                assertNull("E2 Step 4: tokenB PI must be absent/canceled", checkAfterCancelB)

                // 5. Verify Successor C PI exists in OS and slot
                val checkAfterCancelC = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
                assertNotNull("E2 Step 5: tokenC PI must survive as current active slot token", checkAfterCancelC)

                // 6. Active C Cancel -> true
                val cancelC = ownerScheduler.cancelTokenizedScheduledAlarm(alarmId, tokenC)
                assertTrue("E2 Step 6: cancelTokenizedScheduledAlarm C MUST succeed for current active slot token C", cancelC)

                // 7. Verify C PI does not exist after active cancellation
                val checkAfterCancelC2 = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC)
                assertNull("E2 Step 7: tokenC PI must be canceled after active slot cancellation", checkAfterCancelC2)
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                // 8. Robust Cleanup: Attempt cleanup for both B and C in finally block
                var cleanupFailures = 0
                val cleanupErrors = mutableListOf<String>()

                listOf(tokenB, tokenC).forEach { token ->
                    try {
                        val piBefore = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, token)
                        if (piBefore != null) {
                            am.cancel(piBefore)
                            piBefore.cancel()
                        }
                        val piAfter = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, token)
                        if (piAfter != null) {
                            cleanupFailures++
                            cleanupErrors.add("Residual PI present for token $token")
                        }
                    } catch (e: Throwable) {
                        cleanupFailures++
                        cleanupErrors.add("Cleanup exception for token $token: ${e.message}")
                    }
                }

                if (primaryException == null && cleanupFailures > 0) {
                    throw IllegalStateException("D5 E2 Cleanup Failed: $cleanupFailures residual PIs or cleanup errors recorded: $cleanupErrors")
                }
            }
        }
    }

    /**
     * D5 (E3 Comprehensive Orphan C Recovery Sweep Contract): Tests physical OS cancellation,
     * DB settlement to STATUS_REVOKED, 2nd sweep NOOP, and non-interference with Old B & Active C.
     */
    @Test
    fun testD5_e3_orphan_c_recovery_sweep_under_owner_route() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val context = ApplicationProvider.getApplicationContext<Context>()
            grantNotificationAndExactAlarm(context, instrumentation)

            val entryPoint = getEntryPoint(context)
            val baseScheduler = entryPoint.alarmScheduler()
            val db = entryPoint.alarmDatabase()
            val issuedDao = db.issuedOccurrenceDao()
            val repo = entryPoint.alarmRepository()
            val alarmId = 9998L
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

            val mockOwnerRouteAuthority = ProductionSchedulingRouteAuthority()
            mockOwnerRouteAuthority.javaClass.getDeclaredField("currentRoute").apply {
                isAccessible = true
                set(mockOwnerRouteAuthority, ProductionSchedulingRoute.OWNER_EXCLUSIVE)
            }

            val ownerScheduler = AlarmScheduler(
                context = context,
                repository = repo,
                calculator = entryPoint.nextAlarmCalculator(),
                preferencesManager = baseScheduler.let {
                    it.javaClass.getDeclaredField("preferencesManager").apply { isAccessible = true }.get(it) as com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
                },
                holidayRepository = baseScheduler.let {
                    it.javaClass.getDeclaredField("holidayRepository").apply { isAccessible = true }.get(it) as com.sysadmindoc.alarmclock.data.repository.HolidayRepository
                },
                alarmIncidentRepository = entryPoint.alarmIncidentRepository(),
                weatherRepository = baseScheduler.let {
                    it.javaClass.getDeclaredField("weatherRepository").apply { isAccessible = true }.get(it) as com.sysadmindoc.alarmclock.data.repository.WeatherRepository
                },
                scheduleAuthorityOwnerProvider = baseScheduler.let {
                    @Suppress("UNCHECKED_CAST")
                    it.javaClass.getDeclaredField("scheduleAuthorityOwnerProvider").apply { isAccessible = true }.get(it) as javax.inject.Provider<ScheduleAuthorityOwner>
                },
                routeAuthority = mockOwnerRouteAuthority
            )

            // 1. PREEXISTING_DATA_PROTECTED: Check pre-existing alarm definition for alarmId = 9998L
            val existingAlarm = repo.getById(alarmId)
            if (existingAlarm != null && existingAlarm.label != "D5 E3 Test Alarm") {
                assumeFalse("PREEXISTING_DATA_PROTECTED: Pre-existing alarm $alarmId does not belong to D5 E3 test suite", true)
            } else if (existingAlarm != null && existingAlarm.label == "D5 E3 Test Alarm") {
                // RERUN_ISOLATION: Safely purge previous test run DB records for alarmId = 9998L
                repo.deleteById(alarmId)
            }

            val tokenB = UUID.randomUUID().toString()
            val tokenC1 = UUID.randomUUID().toString()
            val tokenC2 = UUID.randomUUID().toString()
            val tokenActiveC = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val triggerTime = now + 300_000L
            val preparedTime = now - 120_000L

            var primaryException: Throwable? = null

            try {
                // Save test-owned Alarm DB definition for alarmId = 9998L before scheduling
                val testAlarm = com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = alarmId,
                    hour = 9,
                    minute = 30,
                    label = "D5 E3 Test Alarm",
                    isEnabled = true
                )
                repo.save(testAlarm)

                // 1. Setup Old B (STATUS_REVOKED)
                issuedDao.reserveIssuedOccurrence(tokenB, alarmId, triggerTime, "fire-b-$tokenB", now = preparedTime - 1000L)
                val piB = ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-b-$tokenB", tokenB)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, piB)
                issuedDao.casUpdateOccurrenceStatus(tokenB, IssuedOccurrence.STATUS_PREPARED, IssuedOccurrence.STATUS_REVOKED, updatedAt = now)

                // 2. Setup Orphan C1 (STATUS_PREPARED, createdAt = T-120s)
                issuedDao.reserveIssuedOccurrence(tokenC1, alarmId, triggerTime, "fire-orphan-c1-$tokenC1", now = preparedTime)
                val piC1 = ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-orphan-c1-$tokenC1", tokenC1)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, piC1)

                // 3. Setup Orphan C2 (STATUS_SET_FAILED, createdAt = T-120s)
                issuedDao.reserveIssuedOccurrence(tokenC2, alarmId, triggerTime, "fire-orphan-c2-$tokenC2", now = preparedTime)
                val piC2 = ownerScheduler.createTokenizedPendingIntent(alarmId, triggerTime, "fire-orphan-c2-$tokenC2", tokenC2)
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, piC2)
                issuedDao.casUpdateOccurrenceStatus(tokenC2, IssuedOccurrence.STATUS_PREPARED, IssuedOccurrence.STATUS_SET_FAILED, updatedAt = now)

                // 4. Setup Active C (STATUS_ISSUED, currentIssueToken = tokenActiveC)
                val activeCSetSuccess = ownerScheduler.applyTokenizedOsSet(alarmId, triggerTime + 60_000L, tokenActiveC)
                assertTrue("ACTIVE_C_SET_ASSERTED: Active C applyTokenizedOsSet MUST succeed", activeCSetSuccess)

                // Verify pre-conditions: All 4 PendingIntents are armed in OS
                assertNotNull("E3 Pre: piB must be armed in OS", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB))
                assertNotNull("E3 Pre: piC1 must be armed in OS", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC1))
                assertNotNull("E3 Pre: piC2 must be armed in OS", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC2))
                assertNotNull("E3 Pre: piActiveC must be armed in OS", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenActiveC))

                // 5. Execute 1st Recovery Sweep
                val cleanedCount1 = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
                assertEquals("E3 1st Sweep: Exactly 2 orphan C occurrences (C1 PREPARED + C2 SET_FAILED) MUST be cleaned", 2, cleanedCount1)

                // 6. Post-sweep OS & DB verification for Orphan C1 & C2
                assertNull("E3 Post: Orphan C1 PI MUST be canceled from OS", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC1))
                assertNull("E3 Post: Orphan C2 PI MUST be canceled from OS", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenC2))
                assertEquals("E3 Post: Orphan C1 status MUST settle to STATUS_REVOKED", IssuedOccurrence.STATUS_REVOKED, issuedDao.getOccurrence(tokenC1)!!.status)
                assertEquals("E3 Post: Orphan C2 status MUST settle to STATUS_REVOKED", IssuedOccurrence.STATUS_REVOKED, issuedDao.getOccurrence(tokenC2)!!.status)

                // 7. Non-Interference Verification: Active C and Old B MUST survive untouched
                assertNotNull("B_C_NONINTERFERENCE: Old B PendingIntent MUST survive sweep in OS", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenB))
                assertEquals("B_C_NONINTERFERENCE: Old B DB status MUST remain STATUS_REVOKED", IssuedOccurrence.STATUS_REVOKED, issuedDao.getOccurrence(tokenB)!!.status)

                assertNotNull("B_C_NONINTERFERENCE: Active C PendingIntent MUST survive sweep in OS", ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, tokenActiveC))
                assertEquals("B_C_NONINTERFERENCE: Active C DB status MUST remain STATUS_ISSUED", IssuedOccurrence.STATUS_ISSUED, issuedDao.getOccurrence(tokenActiveC)!!.status)

                // 8. 2nd Sweep NOOP Verification
                val cleanedCount2 = ownerScheduler.reconcileOrphanPreparedOccurrences(thresholdMs = 60_000L)
                assertEquals("E3 2nd Sweep: 2nd sweep MUST be NOOP (0 items cleaned)", 0, cleanedCount2)
            } catch (t: Throwable) {
                primaryException = t
                throw t
            } finally {
                // 10. TEST_DATA_ONLY_CLEANUP: Cancel test-owned PendingIntents and verify post-conditions
                var cleanupFailures = 0
                val cleanupReceipts = mutableListOf<String>()

                listOf(tokenB, tokenC1, tokenC2, tokenActiveC).forEach { token ->
                    try {
                        val piBefore = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, token)
                        if (piBefore != null) {
                            am.cancel(piBefore)
                            piBefore.cancel()
                        }
                        // PI_POST_CLEANUP_VERIFIED: Confirm PI is absent in OS
                        val piAfter = ownerScheduler.lookupTokenizedPendingIntentForCancel(alarmId, token)
                        if (piAfter != null) {
                            cleanupFailures++
                            cleanupReceipts.add("Residual PI present for token $token")
                        }
                    } catch (e: Throwable) {
                        cleanupFailures++
                        cleanupReceipts.add("Cleanup error for token $token: ${e.message}")
                    }
                }

                // DB Cleanup for test-owned alarm definition
                try {
                    repo.deleteById(alarmId)
                    // DB_POST_CLEANUP_VERIFIED: Confirm DB record deleted
                    if (repo.getById(alarmId) != null) {
                        cleanupFailures++
                        cleanupReceipts.add("Residual DB alarm $alarmId present after deletion")
                    }
                } catch (e: Throwable) {
                    cleanupFailures++
                    cleanupReceipts.add("DB deletion error for alarm $alarmId: ${e.message}")
                }

                // PRIMARY_EXCEPTION_PRESERVED & CLEANUP_FAILURE_REPORTED
                if (cleanupFailures > 0) {
                    val cleanupError = IllegalStateException("CLEANUP_FAILURE_REPORTED: $cleanupFailures errors during cleanup: $cleanupReceipts")
                    if (primaryException != null) {
                        primaryException.addSuppressed(cleanupError)
                    } else {
                        throw cleanupError
                    }
                }
            }
        }
    }
}
