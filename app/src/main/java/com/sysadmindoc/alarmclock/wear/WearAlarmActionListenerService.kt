package com.sysadmindoc.alarmclock.wear

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Stub service for the personal build. Incoming Wear OS actions are not handled.
 */
class WearAlarmActionListenerService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
