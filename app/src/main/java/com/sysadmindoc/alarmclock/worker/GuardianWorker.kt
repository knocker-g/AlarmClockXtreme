package com.sysadmindoc.alarmclock.worker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
 * Direct SMS is used when SEND_SMS is granted. Without permission, it falls 
 * back to opening a prefilled SMS composer.
 *
 * The phone number is sanitised before it is used in smsto: targets.
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

        val directSmsSent = canSendDirectSms && sendDirectSms(phone, message)
        if (!directSmsSent) {
            openSmsComposer(phone, message)
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

    private fun openSmsComposer(phone: String, message: String): Boolean =
        try {
            val smsIntent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)).apply {
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(smsIntent)
            true
        } catch (_: Exception) {
            false
        }

    private fun hasPermission(name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}
