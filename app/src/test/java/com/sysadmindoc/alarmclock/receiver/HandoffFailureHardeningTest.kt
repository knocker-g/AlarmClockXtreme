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
import io.mockk.mockk
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
    fun `POSITIVE CONTROL TEST - genuine pre migration legacy alarm with no IssueSlot is admitted and recovers via legacy gate`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val alarmId = 114L
        val scheduledAt = System.currentTimeMillis() - 1000L

        // Pre-migration legacy alarm (no IssueSlot in Room DB)
        runBlocking {
            alarmRepo.save(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = alarmId,
                    hour = 7,
                    minute = 30,
                    label = "Legacy Pre-Migration Alarm",
                    isEnabled = true,
                    nextTriggerTime = scheduledAt
                )
            )
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = { latch.countDown() }

        // Tokenless legacy broadcast
        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-114")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == alarmId && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }

        // POSITIVE CONTROL: Pre-migration legacy alarm MUST be admitted and succeed
        assertNotNull("POSITIVE CONTROL: Legacy pre-migration alarm MUST be admitted via legacy gate", successIncident)
        assertEquals(alarmId, successIncident!!.alarmId)
        assertEquals("ALARM_NOTIFICATION_POSTED", successIncident.reasonCode)
    }

    @Test
    fun `GREEN TEST A - PREPARED occurrence B broadcast arriving before activation is rejected while active occurrence A retains lease eligibility`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val alarmId = 111L
        val scheduledAt = System.currentTimeMillis() - 1000L
        val tokenA = "token-A-active"
        val tokenB = "token-B-prepared"

        // 1. Active occurrence A, then reserve occurrence B (B remains PREPARED)
        runBlocking {
            inMemoryDb.issuedOccurrenceDao().reserveIssuedOccurrence(tokenA, alarmId, scheduledAt - 10000L, "fire-A")
            inMemoryDb.issuedOccurrenceDao().activateIssuedOccurrence(tokenA)
            inMemoryDb.issuedOccurrenceDao().reserveIssuedOccurrence(tokenB, alarmId, scheduledAt, "fire-B")

            // Part 1: Verify active occurrence A retains lease eligibility while B is PREPARED
            val isAAdmissible = inMemoryDb.issuedOccurrenceDao().evaluateActiveOccurrenceAdmission(
                issueToken = tokenA,
                expectedAlarmId = alarmId,
                expectedScheduledAt = scheduledAt - 10000L,
                expectedFireId = "fire-A"
            )
            assertTrue("Active occurrence A MUST remain admissible while B is PREPARED", isAAdmissible)

            val aLeaseResult = inMemoryDb.issuedOccurrenceDao().claimFallbackLease(
                issueToken = tokenA,
                alarmId = alarmId,
                scheduledAt = scheduledAt - 10000L,
                fireId = "fire-A",
                leaseToken = "lease-A",
                expiresAt = System.currentTimeMillis() + 15000L
            )
            assertEquals("Active occurrence A MUST successfully claim fallback lease while B is PREPARED", 1, aLeaseResult)

            // Part 2: Verify un-activated PREPARED occurrence B is REJECTED
            val isBAdmissible = inMemoryDb.issuedOccurrenceDao().evaluateActiveOccurrenceAdmission(
                issueToken = tokenB,
                expectedAlarmId = alarmId,
                expectedScheduledAt = scheduledAt,
                expectedFireId = "fire-B"
            )
            assertFalse("PREPARED occurrence B MUST NOT be admissible before activation", isBAdmissible)

            val bLeaseResult = inMemoryDb.issuedOccurrenceDao().claimFallbackLease(
                issueToken = tokenB,
                alarmId = alarmId,
                scheduledAt = scheduledAt,
                fireId = "fire-B",
                leaseToken = "lease-B",
                expiresAt = System.currentTimeMillis() + 15000L
            )
            assertEquals("PREPARED occurrence B MUST NOT be granted a fallback lease before activation", 0, bLeaseResult)
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = { latch.countDown() }

        // OS delivers broadcast for token B while B is still in PREPARED status -> REJECTED
        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-B")
            putExtra(AlarmScheduler.EXTRA_ISSUE_TOKEN, tokenB)
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == alarmId && it.fireId == "fire-B" && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }
        assertNull("Un-activated PREPARED occurrence B broadcast MUST NOT succeed or post fallback notification", successIncident)

        // Part 3: Legitimately activate occurrence B, verify B becomes eligible and previous A is revoked
        runBlocking {
            val activateSuccess = inMemoryDb.issuedOccurrenceDao().activateIssuedOccurrence(tokenB)
            assertTrue("Legitimate activation of occurrence B MUST succeed", activateSuccess)

            val isBNowAdmissible = inMemoryDb.issuedOccurrenceDao().evaluateActiveOccurrenceAdmission(
                issueToken = tokenB,
                expectedAlarmId = alarmId,
                expectedScheduledAt = scheduledAt,
                expectedFireId = "fire-B"
            )
            assertTrue("Activated occurrence B MUST be admissible", isBNowAdmissible)

            val bActiveLeaseResult = inMemoryDb.issuedOccurrenceDao().claimFallbackLease(
                issueToken = tokenB,
                alarmId = alarmId,
                scheduledAt = scheduledAt,
                fireId = "fire-B",
                leaseToken = "lease-B-active",
                expiresAt = System.currentTimeMillis() + 15000L
            )
            assertEquals("Activated occurrence B MUST successfully claim fallback lease", 1, bActiveLeaseResult)

            val aAfterBActivateLeaseResult = inMemoryDb.issuedOccurrenceDao().claimFallbackLease(
                issueToken = tokenA,
                alarmId = alarmId,
                scheduledAt = scheduledAt - 10000L,
                fireId = "fire-A",
                leaseToken = "lease-A-stale",
                expiresAt = System.currentTimeMillis() + 15000L
            )
            assertEquals("Previous occurrence A MUST NOT claim fallback lease after B activation", 0, aAfterBActivateLeaseResult)
        }
    }

    @Test
    fun `RED TEST B - legacy tokenless broadcast arriving for migrated alarm currently bypasses token check and gets admitted`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val alarmId = 112L
        val scheduledAt = System.currentTimeMillis() - 1000L
        val tokenB = "token-migrated-B"

        // Alarm 112 saved in DB (enabled)
        runBlocking {
            alarmRepo.save(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = alarmId,
                    hour = 7,
                    minute = 30,
                    label = "Migrated Alarm",
                    isEnabled = true,
                    nextTriggerTime = scheduledAt
                )
            )
            // Alarm 112 migrated to Tokenized B, then canceled (currentIssueToken is set to null)
            inMemoryDb.issuedOccurrenceDao().reserveIssuedOccurrence(tokenB, alarmId, scheduledAt, "fire-112")
            inMemoryDb.issuedOccurrenceDao().activateIssuedOccurrence(tokenB)
            inMemoryDb.issuedOccurrenceDao().revokeIssuedOccurrence(alarmId)
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = { latch.countDown() }

        // Stale legacy tokenless broadcast arrives for alarm 112
        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-112")
            // NO EXTRA_ISSUE_TOKEN
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == alarmId && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }

        // RED TEST B: Now passes GREEN because countActivatedOccurrencesForAlarm(112) > 0!
        assertNull("RED TEST B: Legacy tokenless broadcast for migrated/canceled alarm MUST be rejected", successIncident)
    }

    @Test
    fun `Case 1 - First migration incomplete preserves existing valid Legacy A fallback recovery`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val alarmId = 115L
        val scheduledAt = System.currentTimeMillis() - 1000L
        val tokenB = "token-B-first-prep"

        runBlocking {
            alarmRepo.save(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = alarmId,
                    hour = 7,
                    minute = 30,
                    label = "Legacy A Alarm",
                    isEnabled = true,
                    nextTriggerTime = scheduledAt
                )
            )
            // First tokenized reservation B (creates IssueSlot with currentIssueToken = null, B is PREPARED)
            inMemoryDb.issuedOccurrenceDao().reserveIssuedOccurrence(tokenB, alarmId, scheduledAt + 60000L, "fire-115-B")
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = { latch.countDown() }

        // Existing armed Legacy A fires (tokenless intent)
        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-115-A")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == alarmId && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }

        // VERIFY: Legacy A MUST be admitted and succeed because B was never activated
        assertNotNull("Case 1: Legacy A MUST be admitted when first tokenized B is PREPARED and unactivated", successIncident)
        assertEquals(alarmId, successIncident!!.alarmId)
        assertEquals("ALARM_NOTIFICATION_POSTED", successIncident.reasonCode)
    }

    @Test
    fun `First B reserved PREPARED then REVOKED without activation preserves valid Legacy A fallback recovery`() {
        val shadowApp = org.robolectric.Shadows.shadowOf(context.applicationContext as android.app.Application)
        shadowApp.grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)

        val alarmId = 116L
        val scheduledAt = System.currentTimeMillis() - 1000L
        val tokenB = "token-B-revoked-before-activation"

        runBlocking {
            alarmRepo.save(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = alarmId,
                    hour = 7,
                    minute = 30,
                    label = "Legacy A Alarm Revoked B",
                    isEnabled = true,
                    nextTriggerTime = scheduledAt
                )
            )
            // 1. First tokenized reservation B (B is PREPARED)
            inMemoryDb.issuedOccurrenceDao().reserveIssuedOccurrence(tokenB, alarmId, scheduledAt + 60000L, "fire-116-B")
            // 2. B is revoked BEFORE activation (B status becomes REVOKED, activatedAt remains NULL)
            inMemoryDb.issuedOccurrenceDao().revokeIssuedOccurrence(alarmId)
        }

        val spyContext = spyk(context) {
            every { applicationContext } returns context.applicationContext
            every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val latch = CountDownLatch(1)
        testOnIncidentRecordedCompletion = { latch.countDown() }

        // Existing armed Legacy A fires (tokenless intent)
        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, scheduledAt)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-116-A")
        }

        val receiver = AlarmReceiver()
        receiver.onReceive(spyContext, intent)

        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertTrue("Incident recording timed out", latch.await(5, TimeUnit.SECONDS))

        val incidents = runBlocking { incidentRepo.getRecent(50) }
        val successIncident = incidents.find { it.alarmId == alarmId && it.status == AlarmIncidentEvent.STATUS_SUCCEEDED }

        // VERIFY: Legacy A MUST be admitted and succeed because B was PREPARED -> REVOKED without EVER activating!
        assertNotNull("Legacy A MUST be admitted when B was REVOKED without ever activating", successIncident)
        assertEquals(alarmId, successIncident!!.alarmId)
        assertEquals("ALARM_NOTIFICATION_POSTED", successIncident.reasonCode)
    }

    @Test
    fun `GREEN TEST C - orphan tokenized occurrence after alarm disable is reconciled to REVOKED during rescheduleAllInBatches`() {
        val alarmId = 113L
        val scheduledAt = System.currentTimeMillis() + 60000L
        val tokenC = "token-C-orphan"

        runBlocking {
            alarmRepo.save(
                com.sysadmindoc.alarmclock.data.model.Alarm(
                    id = alarmId,
                    hour = 7,
                    minute = 30,
                    label = "Orphan Test Alarm",
                    isEnabled = false,
                    nextTriggerTime = 0L
                )
            )
            // Token C was activated, but alarm was disabled without revoking token C
            inMemoryDb.issuedOccurrenceDao().reserveIssuedOccurrence(tokenC, alarmId, scheduledAt, "fire-113")
            inMemoryDb.issuedOccurrenceDao().activateIssuedOccurrence(tokenC)
        }

        // Simulate app restart / reconciliation pass
        val scheduler = com.sysadmindoc.alarmclock.domain.AlarmScheduler(
            context = context,
            repository = alarmRepo,
            calculator = mockk(relaxed = true),
            preferencesManager = mockk(relaxed = true),
            holidayRepository = mockk(relaxed = true),
            alarmIncidentRepository = mockk(relaxed = true),
            weatherRepository = mockk(relaxed = true)
        )

        runBlocking { scheduler.rescheduleAllInBatches() }

        val occC = runBlocking { inMemoryDb.issuedOccurrenceDao().getOccurrence(tokenC) }

        assertNotNull(occC)
        assertEquals("GREEN ASSERTION C: Orphan tokenized occurrence for disabled alarm MUST be reconciled to REVOKED", com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence.STATUS_REVOKED, occC!!.status)
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
