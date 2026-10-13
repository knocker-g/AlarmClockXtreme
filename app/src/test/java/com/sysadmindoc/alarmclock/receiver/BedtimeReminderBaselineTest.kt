package com.sysadmindoc.alarmclock.receiver

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.service.BedtimeNoiseBaselineSampler
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The bedtime reminder used to take its own microphone reading. A broadcast
 * receiver runs in the background, and from API 30 a background app is handed
 * silence by AudioRecord, so every reminder stored "the room is quiet" with a
 * fresh timestamp and buried whatever the Bedtime screen had measured.
 *
 * These assert on the notification text rather than on the stored file,
 * because the file cannot tell the two behaviours apart here: Robolectric's
 * AudioRecord returns no samples, so the old code's sample simply failed and
 * wrote nothing. What it did do was hand a null baseline to the message
 * chooser, which is visible.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "en")
class BedtimeReminderBaselineTest {

    private lateinit var context: Context
    private var previousDefaultLocale: Locale? = null

    @Before
    fun setUp() {
        previousDefaultLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("bedtime_noise_baseline", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @After
    fun tearDown() {
        previousDefaultLocale?.let { Locale.setDefault(it) }
    }

    private fun fireReminder(): Notification? {
        BedtimeReceiver().onReceive(context, Intent(BedtimeReceiver.ACTION_BEDTIME_REMINDER))
        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        return manager.allNotifications.lastOrNull()
    }

    @Test
    fun `the reminder repeats the last in-app measurement instead of taking one`() {
        val prefs = context.getSharedPreferences("bedtime_noise_baseline", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("level", "LOUD")
            .putFloat("dbfs", -20f)
            .putLong("measured_at", 1_787_600_000_000L)
            .commit()

        val manager = shadowOf(context.getSystemService(NotificationManager::class.java))
        System.err.println("=== KNO-220 DIAGNOSTIC BEFORE FIRE ===")
        System.err.println("Prefs Level: ${prefs.getString("level", null)}")
        System.err.println("Prefs DBFS: ${prefs.getFloat("dbfs", Float.NaN)}")
        System.err.println("Prefs MeasuredAt: ${prefs.getLong("measured_at", -1L)}")
        System.err.println("Existing Notifications Count: ${manager.allNotifications.size}")

        val notification = fireReminder()

        val expectedAdvice = context.getString(R.string.bedtime_room_loud_advice)
        val selectedText = (notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?: notification?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()
        val selectedTitle = notification?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        val direct3001Notif = manager.getNotification(3001)
        val direct3001Text = (direct3001Notif?.extras?.getCharSequence(Notification.EXTRA_TEXT)
            ?: direct3001Notif?.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()
        val direct3001Title = direct3001Notif?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()

        System.err.println("=== KNO-220 DIAGNOSTIC AFTER FIRE ===")
        System.err.println("Expected Loud Advice String: '$expectedAdvice'")
        System.err.println("Selected Notification Title: '$selectedTitle'")
        System.err.println("Selected Notification Text: '$selectedText'")
        System.err.println("Direct NOTIFICATION_ID 3001 Found: ${direct3001Notif != null}")
        System.err.println("Direct 3001 Title: '$direct3001Title'")
        System.err.println("Direct 3001 Text: '$direct3001Text'")
        System.err.println("Total Notifications In Manager: ${manager.allNotifications.size}")
        manager.allNotifications.forEachIndexed { index, notif ->
            val t = notif.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
            val txt = notif.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            val btxt = notif.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            System.err.println("  Notif #$index: title='$t', extra_text='$txt', extra_big_text='$btxt'")
        }
        val postSnapshot = BedtimeNoiseBaselineSampler.readSnapshot(context)
        System.err.println("Post Snapshot Level: ${postSnapshot.baseline?.level}")
        System.err.println("Post Snapshot DBFS: ${postSnapshot.baseline?.dbfs}")
        System.err.println("Post Snapshot MeasuredAt: ${postSnapshot.measuredAtMillis}")
        System.err.println("======================================")

        assertNotNull(notification)
        val actualLoudText = (notification!!.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()
        assertEquals(
            context.getString(R.string.bedtime_room_loud_advice),
            actualLoudText
        )
        // Sampling from the background would have replaced the measurement.
        assertEquals(
            -20f,
            BedtimeNoiseBaselineSampler.readSnapshot(context).baseline?.dbfs
        )
    }

    @Test
    fun `with nothing measured yet the reminder says the plain wind-down line`() {
        val notification = fireReminder()

        assertNotNull(notification)
        val actualDefaultText = (notification!!.extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()
        assertEquals(
            context.getString(R.string.bedtime_reminder_default),
            actualDefaultText
        )
        assertEquals(0L, BedtimeNoiseBaselineSampler.readSnapshot(context).measuredAtMillis)
    }
}
