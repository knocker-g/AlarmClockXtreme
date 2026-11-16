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

    private val postedNonces = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    @androidx.annotation.VisibleForTesting
    fun clearForTesting() {
        postedNonces.clear()
    }

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
        label: String = "",
        claimNonce: String? = null,
        refireCount: Int = 0,
        roomReceiptChecker: ((Long, Long) -> Boolean)? = null,
        authorityGate: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate? = null
    ): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return false

        val gate = authorityGate ?: com.sysadmindoc.alarmclock.directboot.outputauthority.SharedOutputAuthorityGate.getInstance(context)
        val activeNonce: String?
        val key = if (gate.isEnabled) {
            if (claimNonce.isNullOrBlank()) {
                Log.w(TAG, "Fallback rejected: claimNonce is null or blank when gate is enabled")
                return false
            }
            if (postedNonces.contains(claimNonce)) {
                Log.w(TAG, "Fallback rejected: double-post for claimNonce=$claimNonce")
                return false
            }
            val occKey = com.sysadmindoc.alarmclock.directboot.OutputOccurrenceKey(alarmId, scheduledAt, refireCount)
            val auth = gate.claimOutput(
                context = context,
                key = occKey,
                incomingNonce = claimNonce,
                sink = com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType.FULLSCREEN_FALLBACK_1001,
                directBootModeOnly = false,
                roomReceiptChecker = roomReceiptChecker
            )
            if (auth !is com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized) {
                Log.w(TAG, "Fallback rejected by authority gate: $auth")
                return false
            }
            postedNonces.add(claimNonce)
            activeNonce = auth.activeNonce
            occKey
        } else {
            activeNonce = null
            null
        }

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
            if (key != null && activeNonce != null) {
                val obs = gate.observePhysicalOutput(
                    context = context,
                    key = key,
                    activeNonce = activeNonce,
                    sink = com.sysadmindoc.alarmclock.directboot.outputauthority.OutputSinkType.FULLSCREEN_FALLBACK_1001,
                    roomReceiptChecker = roomReceiptChecker
                )
                if (obs !is com.sysadmindoc.alarmclock.directboot.outputauthority.OutputAuthorizationResult.Authorized) {
                    Log.w(TAG, "Fallback observePhysicalOutput failed: $obs")
                    if (claimNonce != null) postedNonces.remove(claimNonce)
                    return false
                }
            }
            NotificationManagerCompat.from(context)
                .notify(AlarmService.NOTIFICATION_ID, builder.build())
            true
        } catch (e: Exception) {
            Log.e(TAG, "Full-screen fallback failed for $alarmId", e)
            if (key != null && activeNonce != null) {
                gate.arbiter.releaseIfMatches(key, activeNonce)
                if (claimNonce != null) postedNonces.remove(claimNonce)
            }
            false
        }
    }
}
