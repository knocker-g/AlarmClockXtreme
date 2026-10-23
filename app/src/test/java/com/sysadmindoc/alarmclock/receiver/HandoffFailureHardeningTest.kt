package com.sysadmindoc.alarmclock.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.AlarmClockApp
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.data.repository.AlarmIncidentRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.AlarmService
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.spyk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
@Config(sdk = [33])
class HandoffFailureHardeningTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var incidentRepo: AlarmIncidentRepository
    private lateinit var alarmRepo: com.sysadmindoc.alarmclock.data.repository.AlarmRepository
    private lateinit var inMemoryDb: com.sysadmindoc.alarmclock.data.local.AlarmDatabase

    @Before
    fun setUp() {
        inMemoryDb = androidx.room.Room.inMemoryDatabaseBuilder(
            context,
            com.sysadmindoc.alarmclock.data.local.AlarmDatabase::class.java
        ).allowMainThreadQueries().build()

        alarmRepo = com.sysadmindoc.alarmclock.data.repository.AlarmRepository(
            dao = inMemoryDb.alarmDao(),
            groupDao = inMemoryDb.alarmGroupDao(),
            database = inMemoryDb
        )
        incidentRepo = AlarmIncidentRepository(
            dao = inMemoryDb.alarmIncidentEventDao(),
            context = context
        )
        testAlarmRepository = alarmRepo
        testIncidentRepository = incidentRepo
        testIssuedOccurrenceDao = inMemoryDb.issuedOccurrenceDao()
    }

    @After
    fun tearDown() {
        testOnIncidentRecordedCompletion = null
        testAlarmRepository = null
        testIncidentRepository = null
        testIssuedOccurrenceDao = null
        inMemoryDb.close()
    }

    @Test
    fun `SnoozeReceiver service-start exception persists durable STATUS_FAILED incident in Room DB`() {
        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = {
            latch.countDown()
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 101L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-101")
            putExtra(AlarmService.EXTRA_FIRED_AT, 1005L)
        }

        val receiver = SnoozeReceiver()
        receiver.onReceive(spyContext, intent)

        // Wait deterministically for async background incident insertion to complete
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Verify actual persisted row in real Room SQLite DB
        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val failureIncident = incidents.find { it.alarmId == 101L && it.reasonCode.startsWith("SNOOZE_SERVICE_START_FAILED") }

        assertNotNull("Persisted failure incident MUST be present in Room DB", failureIncident)
        assertEquals(101L, failureIncident!!.alarmId)
        assertTrue("fireId must not be blank, was: ${failureIncident.fireId}", failureIncident.fireId.isNotBlank())
        assertEquals("scheduledAt mismatch", 1000L, failureIncident.scheduledAt)
        assertEquals("type mismatch", AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE, failureIncident.type)
        assertEquals("status mismatch", AlarmIncidentEvent.STATUS_FAILED, failureIncident.status)
        assertTrue("reasonCode must start with SNOOZE_SERVICE_START_FAILED, was: ${failureIncident.reasonCode}", failureIncident.reasonCode.startsWith("SNOOZE_SERVICE_START_FAILED"))
        assertEquals("source mismatch", "SnoozeReceiver", failureIncident.source)
    }

    @Test
    fun `DismissReceiver service-start exception persists durable STATUS_FAILED incident in Room DB`() {
        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = {
            latch.countDown()
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 102L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 2000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-102")
            putExtra(AlarmService.EXTRA_FIRED_AT, 2005L)
        }

        val receiver = DismissReceiver()
        receiver.onReceive(spyContext, intent)

        // Wait deterministically for async background incident insertion to complete
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        // Verify actual persisted row in real Room SQLite DB
        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val failureIncident = incidents.find { it.alarmId == 102L && it.reasonCode.startsWith("DISMISS_SERVICE_START_FAILED") }

        assertNotNull("Persisted failure incident MUST be present in Room DB", failureIncident)
        assertEquals(102L, failureIncident!!.alarmId)
        assertTrue("fireId must not be blank, was: ${failureIncident.fireId}", failureIncident.fireId.isNotBlank())
        assertEquals("scheduledAt mismatch", 2000L, failureIncident.scheduledAt)
        assertEquals("type mismatch", AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE, failureIncident.type)
        assertEquals("status mismatch", AlarmIncidentEvent.STATUS_FAILED, failureIncident.status)
        assertTrue("reasonCode must start with DISMISS_SERVICE_START_FAILED, was: ${failureIncident.reasonCode}", failureIncident.reasonCode.startsWith("DISMISS_SERVICE_START_FAILED"))
        assertEquals("source mismatch", "DismissReceiver", failureIncident.source)
    }

    @Test
    fun `failure incident status is STATUS_FAILED and never mistaken for success`() {
        val alarmId = 103L
        val fireId = "fire-103"
        val scheduledAt = 3000L

        val failureIncident = ReceiverAlarmIncident(
            alarmId = alarmId,
            fireId = fireId,
            scheduledAt = scheduledAt,
            type = AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE,
            status = AlarmIncidentEvent.STATUS_FAILED,
            reasonCode = "SNOOZE_SERVICE_START_FAILED_IllegalStateException",
            source = "SnoozeReceiver"
        )

        assertEquals(AlarmIncidentEvent.STATUS_FAILED, failureIncident.status)
        assertNotEquals(AlarmIncidentEvent.STATUS_SUCCEEDED, failureIncident.status)
        assertEquals("SNOOZE_SERVICE_START_FAILED_IllegalStateException", failureIncident.reasonCode)
        assertEquals(alarmId, failureIncident.alarmId)
        assertEquals(fireId, failureIncident.fireId)
        assertEquals(scheduledAt, failureIncident.scheduledAt)
    }

    @Test
    fun `AlarmReceiver startForegroundService fails and fallback fails records STATUS_FAILED incident`() {
        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = {
            latch.countDown()
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 104L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 4000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-104")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val failureIncident = incidents.find { it.alarmId == 104L && it.status == AlarmIncidentEvent.STATUS_FAILED }

        assertNotNull("Persisted failure incident MUST be present in Room DB", failureIncident)
        assertEquals(104L, failureIncident!!.alarmId)
        assertEquals(AlarmIncidentEvent.STATUS_FAILED, failureIncident.status)
        assertEquals(0, runBlocking { incidentRepo.occurrenceDeliveryCount(104L, 4000L) })
    }

    @Test
    fun `AlarmReceiver startForegroundService fails but fallback succeeds persists positive delivery proof and suppresses watchdog`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val validScheduledAt = System.currentTimeMillis() - 1000L
        runBlocking {
            alarmRepo.save(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = 105L,
                    hour = 7,
                    minute = 30,
                    label = "Morning Test",
                    isEnabled = true,
                    nextTriggerTime = validScheduledAt
                )
            )
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = {
            latch.countDown()
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 105L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, validScheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-105")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == 105L && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }

        assertNotNull("Persisted success incident MUST be present in Room DB", successIncident)
        assertEquals(105L, successIncident!!.alarmId)
        assertEquals(AlarmIncidentEvent.TYPE_NOTIFICATION, successIncident.type)
        assertEquals(AlarmIncidentEvent.STATUS_SUCCEEDED, successIncident.status)
        assertEquals("ALARM_NOTIFICATION_POSTED", successIncident.reasonCode)

        val deliveryCount = runBlocking { incidentRepo.occurrenceDeliveryCount(105L, validScheduledAt) }
        assertEquals(1, deliveryCount)

        val watchdogDecision = com.sysadmindoc.alarmclock.worker.FireWatchdogPolicy.decide(
            repeatMissedEnabled = true,
            alarmExists = true,
            isEnabled = true,
            deliveryCount = deliveryCount,
            scheduledAtMs = validScheduledAt,
            nowMs = validScheduledAt + com.sysadmindoc.alarmclock.worker.FireWatchdogPolicy.WATCHDOG_DELAY_MS
        )
        assertEquals(com.sysadmindoc.alarmclock.worker.FireWatchdogPolicy.Decision.SKIP_ALREADY_FIRED, watchdogDecision)
        assertFalse(watchdogDecision.shouldRefire)
    }

    @Test
    fun `AlarmReceiver startForegroundService succeeds no fallback called and records STATUS_REQUESTED incident`() {
        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } returns io.mockk.mockk()
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = {
            latch.countDown()
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 106L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 6000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-106")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val requestedIncident = incidents.find { it.alarmId == 106L && it.status == AlarmIncidentEvent.STATUS_REQUESTED }

        assertNotNull("Persisted requested incident MUST be present in Room DB", requestedIncident)
        assertEquals(106L, requestedIncident!!.alarmId)
        assertEquals(AlarmIncidentEvent.TYPE_FOREGROUND_SERVICE, requestedIncident.type)
        assertEquals(AlarmIncidentEvent.STATUS_REQUESTED, requestedIncident.status)

        val deliveryCount = runBlocking { incidentRepo.occurrenceDeliveryCount(106L, 6000L) }
        assertEquals(0, deliveryCount)
    }

    @Test
    fun `tokenized occurrence FGS start failure evaluates token admission, posts fallback, and records positive proof`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val alarmId = 109L
        val scheduledAt = System.currentTimeMillis() - 1000L
        val token = "token-p2b3-valid"

        // Reserve and activate token in Room DB
        runBlocking {
            inMemoryDb.issuedOccurrenceDao().reserveIssuedOccurrence(token, alarmId, scheduledAt, "fire-109")
            inMemoryDb.issuedOccurrenceDao().activateIssuedOccurrence(token)
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = {
            latch.countDown()
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-109")
            putExtra(AlarmScheduler.EXTRA_ISSUE_TOKEN, token)
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == alarmId && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }

        assertNotNull("Persisted success incident MUST be present in Room DB for valid token", successIncident)
        assertEquals(alarmId, successIncident!!.alarmId)
        assertEquals(AlarmIncidentEvent.TYPE_NOTIFICATION, successIncident.type)
        assertEquals(AlarmIncidentEvent.STATUS_SUCCEEDED, successIncident.status)
        assertEquals("ALARM_NOTIFICATION_POSTED", successIncident.reasonCode)
    }

    @Test
    fun `tokenized occurrence with stale issueToken FGS start failure is rejected by evaluateActiveOccurrenceAdmission`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val alarmId = 110L
        val scheduledAt = System.currentTimeMillis() - 1000L
        val staleToken = "token-p2b3-stale"

        // Reserve token-p2b3-stale but do NOT activate it (status remains PREPARED)
        runBlocking {
            inMemoryDb.issuedOccurrenceDao().reserveIssuedOccurrence(staleToken, alarmId, scheduledAt, "fire-110")
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = {
            latch.countDown()
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-110")
            putExtra(AlarmScheduler.EXTRA_ISSUE_TOKEN, staleToken)
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == alarmId && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }

        assertNull("Stale PREPARED token MUST be rejected and skip fallback", successIncident)
    }

    @Test
    fun `AlarmReceiver FGS start fails on disabled alarm skips fallback and records no positive delivery proof`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val scheduledAt = System.currentTimeMillis() - 1000L
        runBlocking {
            alarmRepo.save(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = 108L,
                    hour = 7,
                    minute = 30,
                    label = "Disabled Test",
                    isEnabled = false,
                    nextTriggerTime = 0L
                )
            )
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = {
            latch.countDown()
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 108L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-108")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == 108L && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }

        assertNull("Disabled alarm MUST NOT post fallback or record STATUS_SUCCEEDED", successIncident)
        assertEquals(0, runBlocking { incidentRepo.occurrenceDeliveryCount(108L, scheduledAt) })
    }
}
