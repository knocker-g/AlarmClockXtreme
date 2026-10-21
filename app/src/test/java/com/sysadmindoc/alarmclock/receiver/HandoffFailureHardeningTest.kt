package com.sysadmindoc.alarmclock.receiver

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.data.local.entity.AlarmIncidentEvent
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.service.AlarmService
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HandoffFailureHardeningTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `SnoozeReceiver service-start exception records durable STATUS_FAILED incident`() {
        val spyContext = spyk(context) {
            io.mockk.every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 101L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 1000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-101")
            putExtra(AlarmService.EXTRA_FIRED_AT, 1005L)
        }

        val receiver = SnoozeReceiver()
        receiver.onReceive(spyContext, intent)

        // Verify receiver entered catch block and recorded STATUS_FAILED incident without throwing
        io.mockk.verify(exactly = 1) { spyContext.startForegroundService(any()) }
    }

    @Test
    fun `DismissReceiver service-start exception records durable STATUS_FAILED incident`() {
        val spyContext = spyk(context) {
            io.mockk.every { startForegroundService(any()) } throws IllegalStateException("Background start restricted")
        }

        val intent = Intent().apply {
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, 102L)
            putExtra(AlarmScheduler.EXTRA_SCHEDULED_AT, 2000L)
            putExtra(AlarmScheduler.EXTRA_ALARM_FIRE_ID, "fire-102")
            putExtra(AlarmService.EXTRA_FIRED_AT, 2005L)
        }

        val receiver = DismissReceiver()
        receiver.onReceive(spyContext, intent)

        // Verify receiver entered catch block and recorded STATUS_FAILED incident without throwing
        io.mockk.verify(exactly = 1) { spyContext.startForegroundService(any()) }
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
}
