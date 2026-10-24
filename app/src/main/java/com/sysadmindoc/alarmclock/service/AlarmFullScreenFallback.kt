package com.sysadmindoc.alarmclock.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.model.Alarm

/**
 * Last resort when a background component cannot start the alarm service.
 *
 * On API 31+ a foreground-service start from the background is refused unless
 * the app holds a battery-optimisation exemption, and on background-restricted
 * OEMs it is refused outright. Both of the safety nets that exist for a
 * silently missed alarm (the fire watchdog and the wake-confirmation re-fire)
 * used to swallow that failure and do nothing visible. A high-importance
 * full-screen-intent notification still gets through, so the alarm surfaces
 * over the lock screen and one tap opens the firing screen.
 */
object AlarmFullScreenFallback {
    private const val TAG = "AlarmFullScreenFallback"

    /**
     * Posts the fallback for [alarm]. Returns false when notifications are not
     * permitted or the post itself failed, so the caller can record that too.
     */
    fun post(
        context: Context,
        alarm: Alarm,
        scheduledAt: Long,
        fireId: String,
        hideLabel: Boolean
    ): Boolean {
        return post(
            context = context,
            alarmId = alarm.id,
            scheduledAt = scheduledAt,
            fireId = fireId,
            hideLabel = hideLabel,
            label = alarm.label
        )
    }

    /**
     * Posts the fallback for [alarmId] without requiring an Alarm model fetch.
     * Uses generic public/private text when label is empty or hideLabel is true.
     */
    fun post(
        context: Context,
        alarmId: Long,
        scheduledAt: Long,
        fireId: String,
        hideLabel: Boolean,
        label: String = ""
    ): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        return try {
            val firingIntent = AlarmFireDismissContract.firingActivityIntent(
                context, alarmId, scheduledAt, fireId
            )
            val fullScreenPi = PendingIntent.getActivity(
                context,
                alarmId.toInt() + 30000,
                firingIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = NotificationCompat.Builder(context, AlarmService.CHANNEL_ALARM)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(context.getString(R.string.notif_alarm_title))
                .setContentText(label.ifBlank { context.getString(R.string.notif_alarm_title) })
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setFullScreenIntent(fullScreenPi, true)
                .setContentIntent(fullScreenPi)
                .setAutoCancel(true)
                .setOngoing(false)
            if (hideLabel || label.isBlank()) {
                val publicVersion = NotificationCompat.Builder(context, AlarmService.CHANNEL_ALARM)
                    .setSmallIcon(R.drawable.ic_alarm)
                    .setContentTitle(context.getString(R.string.notif_alarm_title))
                    .setContentText(context.getString(R.string.notif_alarm_ringing))
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setCategory(NotificationCompat.CATEGORY_ALARM)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                    .setContentIntent(fullScreenPi)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build()
                builder
                    .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setPublicVersion(publicVersion)
            } else {
                builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            }
            NotificationManagerCompat.from(context)
                .notify(AlarmService.NOTIFICATION_ID, builder.build())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Full-screen fallback failed for $alarmId", e)
            false
        }
    }
}
