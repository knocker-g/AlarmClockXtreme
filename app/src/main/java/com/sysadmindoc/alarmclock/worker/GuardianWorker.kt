package com.sysadmindoc.alarmclock.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.R
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * v1.2.0: Guardian Angel worker.
 *
 * If the alarm was not dismissed within `guardianDelaySec`, escalates to the
 * emergency contact via SMS. 
 *
 * Direct SMS is used when SEND_SMS is granted.
 *
 * The phone number is sanitised before it is used.
 */
@HiltWorker
class GuardianWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val rawPhone = inputData.getString("guardian_phone")?.trim().orEmpty()
        if (rawPhone.isBlank()) return Result.success()
        val phone = GuardianEscalationPolicy.sanitisePhone(rawPhone) ?: return Result.success()
        val label = inputData.getString("alarm_label").orEmpty()
        val messageRes = GuardianEscalationPolicy.messageResFor(label)
        val message = if (messageRes == R.string.guardian_alert_message_with_label) {
            applicationContext.getString(messageRes, label)
        } else {
            applicationContext.getString(messageRes)
        }
        val canSendDirectSms = GuardianEscalationPolicy.canSendDirectSms(
            hasSendSmsPermission = hasPermission(Manifest.permission.SEND_SMS)
        )

        if (canSendDirectSms) {
            sendDirectSms(phone, message)
        }

        return Result.success()
    }

    private fun sendDirectSms(phone: String, message: String): Boolean =
        try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            smsManager?.sendTextMessage(phone, null, message, null, null)
            smsManager != null
        } catch (_: Exception) {
            false
        }

    private fun hasPermission(name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}
