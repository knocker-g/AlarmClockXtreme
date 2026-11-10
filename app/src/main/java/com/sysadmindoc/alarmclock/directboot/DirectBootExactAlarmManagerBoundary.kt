package com.sysadmindoc.alarmclock.directboot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * Narrow injectable OS operation interface for Direct Boot V2 exact alarm registration and cancellation.
 *
 * Invariants & Boundaries:
 * - Direct Boot V2 scheduling uses [AlarmManager.setAlarmClock] exclusively.
 * - Silent fallback to inexact or non-AlarmClock APIs following [SecurityException] is strictly PROHIBITED.
 * - An accepted registration return means solely that AlarmManager accepted the request;
 *   it does NOT guarantee actual OS intent delivery.
 * - Cancellation uses [DirectBootV2NormalIntentIdentity.lookupPendingIntentForCancel] (FLAG_NO_CREATE);
 *   it never cancels legacy V1 PendingIntents (request code alarmId.toInt()).
 * - [AndroidDirectBootExactAlarmManager] is internal and intentionally unreachable from existing
 *   production entrypoints.
 */
interface DirectBootExactAlarmManagerBoundary {

    /**
     * Checks if exact alarm scheduling is permitted by the OS.
     */
    fun canScheduleExactAlarms(): Boolean

    /**
     * Requests exact alarm registration with OS AlarmManager.
     */
    fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult

    /**
     * Requests cancellation of an existing exact alarm with OS AlarmManager.
     */
    fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult
}

/**
 * Result hierarchy for [DirectBootExactAlarmManagerBoundary.registerExactAlarm].
 */
sealed class ExactRegistrationResult {

    /**
     * Registration was accepted by AlarmManager.
     */
    data class Accepted(val identity: DirectBootV2NormalIntentIdentity) : ExactRegistrationResult()

    /**
     * Exact alarm permission is not granted on this device / OS version.
     */
    data class PermissionUnavailable(val details: String) : ExactRegistrationResult()

    /**
     * [SecurityException] was thrown during [AlarmManager.setAlarmClock].
     * Fallback to another API is prohibited.
     */
    data class SecurityError(
        val details: String,
        val cause: SecurityException
    ) : ExactRegistrationResult()

    /**
     * Another non-security exception occurred during registration.
     */
    data class RegistrationException(
        val details: String,
        val cause: Throwable
    ) : ExactRegistrationResult()

    /**
     * OS registration state is uncertain (e.g. timeout or IPC abort).
     */
    data class StateUncertain(
        val details: String,
        val cause: Throwable? = null
    ) : ExactRegistrationResult()

    val isAccepted: Boolean get() = this is Accepted
}

/**
 * Result hierarchy for [DirectBootExactAlarmManagerBoundary.cancelExactAlarm].
 */
sealed class ExactCancellationResult {

    /**
     * Exact alarm was found and cancelled in AlarmManager.
     */
    data class Accepted(val identity: DirectBootV2NormalIntentIdentity) : ExactCancellationResult()

    /**
     * No matching PendingIntent existed in AlarmManager (lookup returned null).
     */
    data class NoExistingPendingIntent(
        val identity: DirectBootV2NormalIntentIdentity
    ) : ExactCancellationResult()

    /**
     * An exception occurred during cancellation.
     */
    data class CancellationFailure(
        val details: String,
        val cause: Throwable? = null
    ) : ExactCancellationResult()

    /**
     * OS cancellation state is uncertain.
     */
    data class StateUncertain(
        val details: String,
        val cause: Throwable? = null
    ) : ExactCancellationResult()

    val isSuccessfulOrAbsent: Boolean
        get() = this is Accepted || this is NoExistingPendingIntent
}

/**
 * Factory for creating occurrence-distinct V2 showIntent instances.
 *
 * Prevents different Normal occurrences from aliasing through identical filterEquals identities
 * by attaching an independent V2 showIntent URI derived from the accepted D3-A cryptographic digest.
 *
 * Invariants:
 * - Independent namespace: scheme "acx-directboot", authority "show-normal".
 * - Opaque occurrence URI: contains [identity.alarmId] and [identity.computeDigest()]; zero raw instanceToken.
 * - Stable across process recreation: pure deterministic function of identity.
 * - Different generation / token / trigger timestamp yields distinct digests and therefore distinct filterEquals identities.
 * - Preserves existing V1 behavior: V1 uses DIRECT_BOOT_SHOW_REQUEST_CODE (91_700) without URI.
 * - Zero new public activities or runtime wiring: targets existing internal DirectBootAlarmActivity.
 */
object DirectBootV2ShowIntentFactory {
    const val URI_SCHEME = "acx-directboot"
    const val URI_AUTHORITY_SHOW = "show-normal"
    const val SHOW_REQUEST_CODE_V2 = 91_702

    fun createShowUriString(identity: DirectBootV2NormalIntentIdentity): String {
        val scheme = URI_SCHEME
        val auth = URI_AUTHORITY_SHOW
        val id = identity.alarmId
        val digest = identity.computeDigest()
        val s = "$scheme://$auth/alarm/$id/v2/$digest"
        return s
    }

    fun createShowUri(identity: DirectBootV2NormalIntentIdentity): Uri {
        return Uri.parse(createShowUriString(identity))
    }

    fun createShowIntent(context: Context, identity: DirectBootV2NormalIntentIdentity): Intent {
        val intent = DirectBootAlarmActivity.intent(
            context = context,
            alarmId = identity.alarmId,
            timeLabel = ""
        )
        intent.data = createShowUri(identity)
        return intent
    }

    fun createShowPendingIntent(context: Context, identity: DirectBootV2NormalIntentIdentity): PendingIntent {
        return PendingIntent.getActivity(
            context,
            SHOW_REQUEST_CODE_V2,
            createShowIntent(context, identity),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/**
 * Production Android AlarmManager adapter.
 * Kept strictly internal and unreachable from existing production entrypoints until runtime activation.
 */
internal class AndroidDirectBootExactAlarmManager(
    private val context: Context
) : DirectBootExactAlarmManagerBoundary {

    private val protectedContext: Context
        get() = context.applicationContext.createDeviceProtectedStorageContext()

    private val alarmManager: AlarmManager
        get() = protectedContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    override fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    override fun registerExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactRegistrationResult {
        if (!canScheduleExactAlarms()) {
            val msg = "Exact alarm scheduling permission is not granted by the OS"
            return ExactRegistrationResult.PermissionUnavailable(msg)
        }

        return try {
            val operation = identity.createPendingIntent(protectedContext)
            val showIntent = DirectBootV2ShowIntentFactory.createShowPendingIntent(protectedContext, identity)
            val clockInfo = AlarmManager.AlarmClockInfo(identity.triggerTime, showIntent)

            alarmManager.setAlarmClock(clockInfo, operation)
            ExactRegistrationResult.Accepted(identity)
        } catch (se: SecurityException) {
            Log.w(TAG, "Exact alarm permission denied during setAlarmClock for alarm ${identity.alarmId}", se)
            val msg = "SecurityException during setAlarmClock: ${se.message}"
            ExactRegistrationResult.SecurityError(msg, se)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register exact alarm for alarm ${identity.alarmId}", e)
            val msg = "Registration failed with exception: ${e.message}"
            ExactRegistrationResult.RegistrationException(msg, e)
        }
    }

    override fun cancelExactAlarm(identity: DirectBootV2NormalIntentIdentity): ExactCancellationResult {
        return try {
            val pendingIntent = identity.lookupPendingIntentForCancel(protectedContext)
            if (pendingIntent == null) {
                ExactCancellationResult.NoExistingPendingIntent(identity)
            } else {
                alarmManager.cancel(pendingIntent)
                pendingIntent.cancel()
                ExactCancellationResult.Accepted(identity)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to cancel exact alarm for alarm ${identity.alarmId}", e)
            val msg = "Cancellation failed with exception: ${e.message}"
            ExactCancellationResult.CancellationFailure(msg, e)
        }
    }

    companion object {
        private const val TAG = "AndroidExactAlarmMgr"
    }
}
