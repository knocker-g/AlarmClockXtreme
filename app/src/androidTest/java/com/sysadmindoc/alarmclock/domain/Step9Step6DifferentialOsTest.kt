package com.sysadmindoc.alarmclock.domain

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.backup.JournalDiskState
import com.sysadmindoc.alarmclock.data.backup.RestoreJournalCoordinator
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.directboot.DirectBootAlarmReceiver
import com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate
import com.sysadmindoc.alarmclock.receiver.AlarmReceiver
import com.sysadmindoc.alarmclock.service.AlarmFireDismissContract
import com.sysadmindoc.alarmclock.service.AlarmService
import com.sysadmindoc.alarmclock.service.SmartAlarmService
import com.sysadmindoc.alarmclock.worker.FireWatchdogWorker
import com.sysadmindoc.alarmclock.worker.GuardianWorker
import com.sysadmindoc.alarmclock.worker.HueSunriseWorker
import dagger.hilt.android.EntryPointAccessors
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
import java.io.File
import java.util.UUID
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class Step9Step6DifferentialOsTest {

    private fun entryPoint(context: Context): AlarmClockApp.AppEntryPoint {
        return EntryPointAccessors.fromApplication(
            context,
            AlarmClockApp.AppEntryPoint::class.java
        )
    }

    private fun grantNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS
            )
        }
    }

    private fun executeShellOutput(command: String): String {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    private fun hasServiceRecord(servicesDump: String, serviceSimpleName: String): Boolean {
        return Regex("ServiceRecord\\{[^\\n]*\\.$serviceSimpleName[\\s}]").containsMatchIn(servicesDump)
    }

    private fun hasForegroundServiceRecord(servicesDump: String, serviceSimpleName: String): Boolean {
        val header = Regex("ServiceRecord\\{[^\\n]*\\.$serviceSimpleName[\\s}]")
        val lines = servicesDump.lines()
        for (i in lines.indices) {
            if (header.containsMatchIn(lines[i])) {
                var j = i + 1
                while (j < lines.size && !lines[j].contains("ServiceRecord{")) {
                    if (lines[j].contains("isForeground=true")) {
                        return true
                    }
                    j++
                }
            }
        }
        return false
    }

    private suspend fun awaitWorkFinished(
        workManager: WorkManager,
        workId: UUID,
        timeoutMs: Long = 15_000L
    ): WorkInfo {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val info = workManager.getWorkInfoById(workId).get()
            if (info != null && info.state.isFinished) {
                return info
            }
            delay(150L)
        }
        return workManager.getWorkInfoById(workId).get()
            ?: error("WorkInfo missing for $workId")
    }

    private fun writeSmokeTone(context: Context): File {
        val file = File(context.filesDir, "step9_c1_smoke.wav")
        val sampleRate = 8_000
        val samples = sampleRate / 2
        val dataSize = samples * 2

        file.outputStream().use { out ->
            fun ascii(value: String) = out.write(value.toByteArray(Charsets.US_ASCII))
            fun intLe(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
                out.write((value ushr 16) and 0xff)
                out.write((value ushr 24) and 0xff)
            }
            fun shortLe(value: Int) {
                out.write(value and 0xff)
                out.write((value ushr 8) and 0xff)
            }
            ascii("RIFF")
            intLe(36 + dataSize)
            ascii("WAVE")
            ascii("fmt ")
            intLe(16)
            shortLe(1)
            shortLe(1)
            intLe(sampleRate)
            intLe(sampleRate * 2)
            shortLe(2)
            shortLe(16)
            ascii("data")
            intLe(dataSize)
            for (i in 0 until samples) {
                val angle = 2.0 * PI * 440.0 * i / sampleRate
                val sample = (sin(angle) * Short.MAX_VALUE * 0.2).toInt()
                shortLe(sample)
            }
        }
        return file
    }

    @Test
    fun testC1_C5_C7_C8_normalAlarmScheduleFireDismiss_andNoDuplicateOrGuardianEscalation() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)

        val ep = entryPoint(context)
        val repo = ep.alarmRepository()
        val scheduler = ep.alarmScheduler()
        val incidentRepo = ep.alarmIncidentRepository()
        val eventDao = ep.alarmDatabase().alarmEventDao()
        val workManager = WorkManager.getInstance(context)

        val testAlarmId = 9993L
        val existing = repo.getById(testAlarmId)
        if (existing != null && existing.label != "Step9 C1 Test Alarm") {
            assumeFalse("PREEXISTING_DATA_PROTECTED: alarm $testAlarmId belongs to non-test data", true)
        } else if (existing != null) {
            repo.deleteById(testAlarmId)
        }

        val smokeTone = writeSmokeTone(context)
        val scheduledAt = System.currentTimeMillis() + 300_000L
        val fireId = AlarmIncidentEvent.fireIdFor(testAlarmId, scheduledAt)

        val savedId = repo.save(
            Alarm(
                id = testAlarmId,
                hour = 11,
                minute = 45,
                label = "Step9 C1 Test Alarm",
                isEnabled = true,
                ringtoneUri = smokeTone.toURI().toString(),
                vibrationEnabled = false,
                overrideSystemVolume = false,
                gradualVolumeSeconds = 0,
                challengeType = "NONE",
                smartAlarmEnabled = true,
                smartAlarmWindowMinutes = 30,
                hueEnabled = true,
                huePreWakeMinutes = 15,
                guardianEnabled = true,
                guardianPhone = "+15550199",
                guardianDelaySec = 60
            )
        )
        assertEquals(testAlarmId, savedId)
        val savedAlarm = repo.getById(testAlarmId)!!

        var alarmServiceStarted = false
        try {
            // 1. C1 Schedule check: Main PI armed when exact alarm allowed; Smart PI & Hue Work suppressed
            val outcome = scheduler.schedule(savedAlarm, requestWidgetUpdate = false)
            if (scheduler.canScheduleExactAlarms()) {
                assertTrue("Expected Armed outcome, got $outcome", outcome.isArmed)
                val mainPi = PendingIntent.getBroadcast(
                    context,
                    testAlarmId.toInt(),
                    Intent(context, AlarmReceiver::class.java).apply {
                        action = "com.sysadmindoc.alarmclock.ALARM_FIRE"
                    },
                    PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
                )
                assertNotNull("Main V1 PendingIntent must be armed for $testAlarmId", mainPi)
            }
            val smartPi = PendingIntent.getForegroundService(
                context,
                (testAlarmId + 50_000L).toInt(),
                Intent(context, SmartAlarmService::class.java).apply {
                    action = SmartAlarmService.ACTION_START_SMART
                },
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            assertNull("SmartAlarmService PI must NOT be scheduled in Personal edition", smartPi)
            val hueWorksAfterSchedule = workManager.getWorkInfosForUniqueWork(HueSunriseWorker.uniqueName(testAlarmId)).get()
            assertTrue(
                "HueSunriseWorker work must NOT be enqueued in Personal edition",
                hueWorksAfterSchedule.none { !it.state.isFinished }
            )

            // Cancel future scheduled alarm before starting immediate fire smoke
            scheduler.cancel(testAlarmId)

            // 2. C1 Fire AlarmService -> verify positive delivery evidence + active foreground service
            alarmServiceStarted = true
            ContextCompat.startForegroundService(
                context,
                AlarmFireDismissContract.startServiceIntent(context, testAlarmId, scheduledAt, fireId)
            )
            var started = false
            repeat(50) {
                val deliveryCount = incidentRepo.occurrenceDeliveryCount(testAlarmId, scheduledAt)
                val servicesDump = executeShellOutput("dumpsys activity services ${context.packageName}")
                if (deliveryCount > 0 && hasForegroundServiceRecord(servicesDump, "AlarmService")) {
                    started = true
                    return@repeat
                }
                delay(200L)
            }
            val startIncidents = incidentRepo.getRecent(500).filter { it.alarmId == testAlarmId }
            assertTrue(
                "AlarmService must record positive delivery & enter foreground for $fireId; incidents=$startIncidents",
                started
            )

            // 3. C1 Dismiss -> verify foreground service stops cleanly (C8) and terminal dismiss recorded
            context.startService(
                AlarmFireDismissContract.dismissServiceIntent(context, testAlarmId, scheduledAt, fireId)
            )
            var stopped = false
            repeat(40) {
                val servicesDump = executeShellOutput("dumpsys activity services ${context.packageName}")
                val terminalCount = eventDao.countTerminalByOccurrence(testAlarmId, scheduledAt)
                if (!hasForegroundServiceRecord(servicesDump, "AlarmService") && terminalCount > 0) {
                    stopped = true
                    return@repeat
                }
                delay(200L)
            }
            assertTrue("AlarmService foreground must stop and record terminal dismiss event", stopped)

            // Allow AlarmFiringActivity teardown (onDestroy ducking reset) to settle, then clear any idle non-FGS service
            delay(800L)
            context.stopService(Intent(context, AlarmService::class.java))
            delay(200L)

            // Guardian work enqueued during firing must be cancelled on dismiss
            val guardianTagWorks = workManager.getWorkInfosByTag(GuardianWorker.alarmTag(testAlarmId)).get()
            assertTrue(
                "Guardian work for dismissed alarm must not remain active",
                guardianTagWorks.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            )

            // 4. C5 & C7 GuardianWorker & FireWatchdogWorker post-dismiss non-escalation / non-duplication
            val metaEpoch = ep.alarmDatabase().scheduleAuthorityDao().getMetaById(1)?.restoreEpoch ?: 1L
            val guardianReq = OneTimeWorkRequestBuilder<GuardianWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(GuardianWorker.KEY_ALARM_ID, testAlarmId)
                        .putLong(GuardianWorker.KEY_SCHEDULED_AT, scheduledAt)
                        .putString(GuardianWorker.KEY_ALARM_FIRE_ID, fireId)
                        .putInt(GuardianWorker.KEY_EXPECTED_GENERATION, savedAlarm.generation)
                        .putLong(GuardianWorker.KEY_EXPECTED_RESTORE_EPOCH, metaEpoch)
                        .putString(GuardianWorker.KEY_GUARDIAN_PHONE, "+15550199")
                        .putString(GuardianWorker.KEY_ALARM_LABEL, savedAlarm.label)
                        .build()
                )
                .build()
            workManager.enqueue(guardianReq)
            val guardianInfo = awaitWorkFinished(workManager, guardianReq.id)
            assertEquals(WorkInfo.State.SUCCEEDED, guardianInfo.state)

            val guardianIncidents = incidentRepo.getRecent(500)
                .filter { it.fireId == fireId && it.type == GuardianWorker.INCIDENT_TYPE_GUARDIAN }
            assertTrue(
                "GuardianWorker must record SKIPPED diagnostic; got $guardianIncidents",
                guardianIncidents.any { it.status == AlarmIncidentEvent.STATUS_SKIPPED }
            )
            assertFalse(
                "GuardianWorker must NEVER send SMS after dismiss; got $guardianIncidents",
                guardianIncidents.any { it.reasonCode == "GUARDIAN_SMS_SENT" }
            )

            val watchdogReq = OneTimeWorkRequestBuilder<FireWatchdogWorker>()
                .setInputData(
                    Data.Builder()
                        .putLong(FireWatchdogWorker.KEY_ALARM_ID, testAlarmId)
                        .putLong(FireWatchdogWorker.KEY_SCHEDULED_AT, scheduledAt)
                        .putInt(FireWatchdogWorker.KEY_EXPECTED_GENERATION, savedAlarm.generation)
                        .putLong(FireWatchdogWorker.KEY_EXPECTED_RESTORE_EPOCH, metaEpoch)
                        .build()
                )
                .build()
            workManager.enqueue(watchdogReq)
            val watchdogInfo = awaitWorkFinished(workManager, watchdogReq.id)
            assertEquals(WorkInfo.State.SUCCEEDED, watchdogInfo.state)

            val watchdogRefires = incidentRepo.getRecent(500)
                .filter {
                    it.alarmId == testAlarmId &&
                        it.scheduledAt == scheduledAt &&
                        it.type == AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE &&
                        it.status == AlarmIncidentEvent.STATUS_REQUESTED
                }
            assertTrue(
                "FireWatchdogWorker must NOT refire after accepted delivery/dismiss; got $watchdogRefires",
                watchdogRefires.isEmpty()
            )

            // 5. C8 No FGS residual service
            delay(300L)
            val finalServicesDump = executeShellOutput("dumpsys activity services ${context.packageName}")
            assertFalse(
                "No AlarmService FGS may remain running:\n$finalServicesDump",
                hasForegroundServiceRecord(finalServicesDump, "AlarmService")
            )
            assertFalse(
                "No SmartAlarmService may remain running:\n$finalServicesDump",
                hasServiceRecord(finalServicesDump, "SmartAlarmService")
            )
        } finally {
            if (alarmServiceStarted) {
                runCatching {
                    context.startService(
                        AlarmFireDismissContract.dismissServiceIntent(context, testAlarmId, scheduledAt, fireId)
                    )
                }
                repeat(20) {
                    val dump = executeShellOutput("dumpsys activity services ${context.packageName}")
                    if (!hasForegroundServiceRecord(dump, "AlarmService")) return@repeat
                    delay(150L)
                }
                delay(800L)
                runCatching { context.stopService(Intent(context, AlarmService::class.java)) }
            }
            runCatching { scheduler.cancel(testAlarmId) }
            runCatching { smokeTone.delete() }
            runCatching { repo.deleteById(testAlarmId) }
            runCatching { workManager.pruneWork() }
        }
    }

    @Test
    fun testC2_C3_C8_smartAlarmServiceRetiredIngress_terminatesWithZeroWakeLockOrEarlyFire() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)

        // Ensure no leftover idle AlarmService from prior test teardown
        context.stopService(Intent(context, AlarmService::class.java))
        delay(200L)

        val staleTargetTime = System.currentTimeMillis() + 120_000L
        val smartIntent = Intent(context, SmartAlarmService::class.java).apply {
            action = SmartAlarmService.ACTION_START_SMART
            putExtra(SmartAlarmService.EXTRA_ALARM_ID, 9994L)
            putExtra(SmartAlarmService.EXTRA_TARGET_TIME, staleTargetTime)
        }

        ContextCompat.startForegroundService(context, smartIntent)

        var serviceStopped = false
        var lastDump = ""
        repeat(30) {
            delay(150L)
            lastDump = executeShellOutput("dumpsys activity services ${context.packageName}")
            if (!hasServiceRecord(lastDump, "SmartAlarmService")) {
                serviceStopped = true
                return@repeat
            }
        }
        assertTrue("SmartAlarmService retired ingress must self-terminate promptly:\n$lastDump", serviceStopped)

        val servicesAfter = executeShellOutput("dumpsys activity services ${context.packageName}")
        assertFalse(
            "SmartAlarmService must NOT start AlarmService early:\n$servicesAfter",
            hasServiceRecord(servicesAfter, "AlarmService")
        )

        val powerDump = executeShellOutput("dumpsys power")
        assertFalse("SmartAlarmWakeLock must NOT be held", powerDump.contains("SmartAlarmWakeLock"))

        val nm = context.getSystemService(NotificationManager::class.java)
        assertTrue(
            "Smart foreground notification must be removed",
            nm.activeNotifications.none { it.id == SmartAlarmService.NOTIF_ID_SMART }
        )
    }

    @Test
    fun testC4_C8_hueSunriseWorkerRetiredIngress_succeedsWithZeroContinuationOrNotification() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        grantNotificationPermission(context)
        context.stopService(Intent(context, AlarmService::class.java))
        delay(200L)

        val workManager = WorkManager.getInstance(context)
        val testAlarmId = 9994L
        val uniqueName = HueSunriseWorker.uniqueName(testAlarmId)

        val request = OneTimeWorkRequestBuilder<HueSunriseWorker>()
            .setInputData(
                Data.Builder()
                    .putLong(HueSunriseWorker.KEY_ALARM_ID, testAlarmId)
                    .build()
            )
            .build()

        try {
            workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
            val finished = awaitWorkFinished(workManager, request.id)
            assertEquals("HueSunriseWorker must succeed immediately when retired", WorkInfo.State.SUCCEEDED, finished.state)

            delay(300L)
            val works = workManager.getWorkInfosForUniqueWork(uniqueName).get()
            assertTrue(
                "HueSunriseWorker must NOT enqueue APPEND continuations",
                works.none { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
            )

            val nm = context.getSystemService(NotificationManager::class.java)
            val expectedNotifId = 800_000 + (testAlarmId % 100_000).toInt()
            assertTrue(
                "HueSunriseWorker must NOT post Hue notification",
                nm.activeNotifications.none { it.id == expectedNotifId }
            )

            val servicesDump = executeShellOutput("dumpsys activity services ${context.packageName}")
            assertFalse("No AlarmService residual:\n$servicesDump", hasServiceRecord(servicesDump, "AlarmService"))
        } finally {
            runCatching { workManager.cancelUniqueWork(uniqueName) }
            runCatching { workManager.pruneWork() }
        }
    }

    @Test
    fun testC6_productionFlagsLegacyOnly_and_restoreJournalSafe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertFalse(
            "ProductionSchedulingRouteAuthority MUST remain LEGACY_ONLY",
            ProductionSchedulingRouteAuthority().isOwnerRouteActive()
        )
        assertFalse(
            "SharedOutputAuthorityGate MUST remain disabled by default",
            SharedOutputAuthorityGate.getInstance(context).isEnabled
        )
        assertFalse(
            "DirectBootAlarmReceiver v2DeliveryCapableProvider MUST remain false by default",
            DirectBootAlarmReceiver.v2DeliveryCapableProvider()
        )

        val journalState = RestoreJournalCoordinator.readVerifiedDiskState(context)
        assertFalse(
            "RestoreJournalCoordinator must not be stuck in PHASE_PREPARED",
            journalState is JournalDiskState.Verified &&
                journalState.phase == RestoreJournalCoordinator.PHASE_PREPARED
        )
    }
}
