package com.sysadmindoc.alarmclock.directboot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.core.os.UserManagerCompat
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/**
 * Post-Unlock Receiver for Direct Boot takeover event consumption (KNO-75 Phase C7-I4).
 *
 * Listens for [Intent.ACTION_USER_UNLOCKED] with android:directBootAware="true".
 * Upon device unlock, executes [DirectBootEventConsumerEngine.consumePendingTakeoverRecords]
 * inside a background coroutine using [goAsync], safely committing Room 38 receipts
 * and releasing CE active slots without blocking the main thread.
 */
class DirectBootUnlockReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_USER_UNLOCKED) return

        if (!UserManagerCompat.isUserUnlocked(context)) {
            Log.w(TAG, "Received USER_UNLOCKED but UserManager reports user is still locked")
            return
        }

        val pendingResult = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        scope.launch {
            try {
                withTimeout(8_000L) {
                    val deContext = if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()
                    val ceContext = context.applicationContext
                    val database = databaseSeam(ceContext)

                    val result = DirectBootEventConsumerEngine.consumePendingTakeoverRecords(
                        context = deContext,
                        ceContext = ceContext,
                        database = database
                    )
                    Log.i(TAG, "Direct Boot takeover consumption completed: $result")
                    onConsumerFinishedSeam(result)
                }
            } catch (e: TimeoutCancellationException) {
                Log.e(TAG, "Direct Boot takeover consumption timed out after 8s", e)
            } catch (t: Throwable) {
                Log.e(TAG, "Error consuming Direct Boot takeover records post-unlock", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DirectBootUnlockReceiver"

        @VisibleForTesting
        internal var databaseSeam: (Context) -> AlarmDatabase = { ctx ->
            androidx.room.Room.databaseBuilder(
                ctx,
                AlarmDatabase::class.java,
                "alarm_clock.db"
            ).addMigrations(*AlarmDatabase.ALL_MIGRATIONS).build()
        }

        @VisibleForTesting
        internal var onConsumerFinishedSeam: (DirectBootEventConsumerEngine.ConsumerExecutionResult) -> Unit = {}
    }
}
