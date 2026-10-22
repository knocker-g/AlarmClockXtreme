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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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

    @Before
    fun setUp() {
        context.deleteDatabase("alarm_clock.db")
        val entryPoint = EntryPointAccessors.fromApplication(
            context.applicationContext,
            AlarmClockApp.AppEntryPoint::class.java
        )
        incidentRepo = entryPoint.alarmIncidentRepository()
    }

    @After
    fun tearDown() {
        testOnIncidentRecordedCompletion = null
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
}
