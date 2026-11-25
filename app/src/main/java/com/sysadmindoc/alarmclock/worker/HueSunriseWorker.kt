package com.sysadmindoc.alarmclock.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.integration.hue.HueBridgeClient
import com.sysadmindoc.alarmclock.integration.hue.HuePinResult
import com.sysadmindoc.alarmclock.integration.hue.HueTrustStore
import com.sysadmindoc.alarmclock.integration.hue.HueV2ProbeResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * F15: Philips Hue sunrise simulation.
 * Gradually ramps up warm-white light from 0->254 brightness over [huePreWakeMinutes] minutes.
 * Input: KEY_ALARM_ID. Reads bridge IP, API key, and comma-separated light IDs from preferences.
 *
 * v1.11.5 (roadmap N5): API v2 only (HTTPS, header auth, CLIP v2 resource
 * shape). Each run proves v2 reachability and the current certificate before
 * sending light commands.
 *
 * v1.14.x: TOFU (Trust On First Use) certificate pinning for v2 HTTPS.
 * On first successful connection, the bridge cert SHA-256 fingerprint is saved
 * to DataStore. Subsequent connections reject certificate changes, surfacing
 * a blocking warning in Settings. The v1 plain-HTTP path is gone: cleartext is
 * blocked at this targetSdk, so it could never have connected.
 */
@HiltWorker
class HueSunriseWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: AlarmRepository,
    private val preferencesManager: PreferencesManager,
    private val hueBridgeClient: HueBridgeClient,
    private val hueTrustStore: HueTrustStore
) : CoroutineWorker(context, workerParams) {

    companion object {
        const val KEY_ALARM_ID = "alarm_id"

        /**
         * Wall-clock bounds of the ramp, carried across segments so a
         * continuation resumes at the right brightness instead of restarting.
         * Absent on the first run, which derives them from the alarm.
         */
        const val KEY_RAMP_START = "ramp_start"
        const val KEY_RAMP_END = "ramp_end"
        const val KEY_EXPECTED_GENERATION = "expected_generation"
        const val KEY_EXPECTED_RESTORE_EPOCH = "expected_restore_epoch"

        private val JSON = "application/json".toMediaType()
        private const val WARM_CT = 500       // ~2000K warm white (Hue range 153-500)

        fun uniqueName(alarmId: Long): String = "hue_sunrise_$alarmId"
    }

    private suspend fun isOccurrenceStillValid(
        alarmId: Long,
        expectedGeneration: Int,
        expectedRestoreEpoch: Long,
        expectedRampEnd: Long
    ): Boolean {
        val currentAlarm = repository.getById(alarmId) ?: return false
        if (!currentAlarm.isEnabled || !currentAlarm.hueEnabled) return false
        if (expectedGeneration > 0 && !currentAlarm.isQuickAlarm && currentAlarm.generation != expectedGeneration) {
            return false
        }
        if (expectedRampEnd > 0L && currentAlarm.nextTriggerTime != expectedRampEnd) {
            return false
        }
        if (expectedRestoreEpoch > 0L) {
            val currentEpoch = try {
                repository.database.scheduleAuthorityDao().getMetaById(1)?.restoreEpoch
            } catch (_: Throwable) {
                null
            }
            if (currentEpoch == null || currentEpoch <= 0L || currentEpoch != expectedRestoreEpoch) {
                return false
            }
        }
        return true
    }

    override suspend fun doWork(): Result {
        // KNO-75 Step 6 / KNO-76: Personal edition retires Hue Sunrise runtime.
        // Fail closed at worker ingress for any pre-upgrade queued work or APPEND
        // continuation without probing the bridge, building TOFU clients, mutating
        // HueTrustStore, posting notifications, enqueueing APPEND continuations,
        // or requesting retries. Unconditional HueSunriseNotifications.cancel is
        // skipped because notificationId = 800_000 + (alarmId % 100_000) can
        // collide across alarmIds (e.g., 42 vs 100_042) with no per-alarmId tag.
        val alarmId = inputData.getLong(KEY_ALARM_ID, -1L)
        android.util.Log.i(
            "HueSunriseWorker",
            "HueSunriseWorker retired in Personal edition; ignoring work for alarmId=$alarmId"
        )
        return Result.success()
    }

    /**
     * Queues the next segment of the same ramp under the same unique name, so a
     * cancel or a reschedule still tears the whole chain down.
     */
    private fun enqueueContinuation(
        alarmId: Long,
        rampStart: Long,
        rampEnd: Long,
        expectedGeneration: Int,
        expectedRestoreEpoch: Long
    ) {
        val dataBuilder = androidx.work.Data.Builder()
            .putLong(KEY_ALARM_ID, alarmId)
            .putLong(KEY_RAMP_START, rampStart)
            .putLong(KEY_RAMP_END, rampEnd)
        if (expectedGeneration > 0) {
            dataBuilder.putInt(KEY_EXPECTED_GENERATION, expectedGeneration)
        }
        if (expectedRestoreEpoch > 0L) {
            dataBuilder.putLong(KEY_EXPECTED_RESTORE_EPOCH, expectedRestoreEpoch)
        }
        val request = androidx.work.OneTimeWorkRequestBuilder<HueSunriseWorker>()
            .setInputData(dataBuilder.build())
            .build()
        // APPEND, not APPEND_OR_REPLACE: if the chain was cancelled while this
        // segment was finishing, the continuation must stay cancelled rather
        // than start a fresh ramp for an alarm that no longer exists.
        androidx.work.WorkManager.getInstance(applicationContext).enqueueUniqueWork(
            uniqueName(alarmId),
            androidx.work.ExistingWorkPolicy.APPEND,
            request
        )
    }

    private fun putLightStateV2(
        client: OkHttpClient,
        bridgeIp: String, apiKey: String, lightId: String,
        on: Boolean, bri: Int, ct: Int
    ): Boolean {
        return try {
            val brightnessPct = (bri * 100f / 254f).coerceIn(1f, 100f)
            val body = buildString {
                append("{")
                append("\"on\":{\"on\":$on},")
                append("\"dimming\":{\"brightness\":")
                append(String.format(java.util.Locale.US, "%.2f", brightnessPct))
                append("},")
                append("\"color_temperature\":{\"mirek\":$ct}")
                append("}")
            }
            val request = Request.Builder()
                .url("https://$bridgeIp/clip/v2/resource/light/$lightId")
                .header("hue-application-key", apiKey)
                .put(body.toRequestBody(JSON))
                .build()
            client.newCall(request).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

}
