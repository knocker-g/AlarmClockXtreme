package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup and background reconciler for durable [ScheduleAuthority] operations in 'RECONCILING' state.
 *
 * Source-level entrypoints:
 * - [com.sysadmindoc.alarmclock.worker.AlarmHealthWorker]
 * - [com.sysadmindoc.alarmclock.receiver.BootRescheduleReceiver]
 *
 * Guarantees:
 * - Re-reads CURRENT authority from DB for every row before applying (never replays stale job targets).
 * - Re-evaluates exact alarm capability.
 * - Applies raw OS slot operation via [ScheduleAuthorityOwner].
 * - On success, settles authority state to 'SETTLED'.
 */
@Singleton
class ScheduleAuthorityReconciler @Inject constructor(
    private val authorityDao: ScheduleAuthorityDao,
    private val owner: ScheduleAuthorityOwner
) {

    companion object {
        private const val TAG = "ScheduleAuthorityReconciler"
    }

    suspend fun reconcilePendingAuthorities(): Int {
        val allAuthorities = authorityDao.getAllAuthorities()
        val reconcilingList = allAuthorities.filter { it.reconcileState == "RECONCILING" }

        var reconciledCount = 0
        for (auth in reconcilingList) {
            // Re-read CURRENT authority from DB before applying to avoid stale job targets
            val currentAuth = authorityDao.getAuthorityByAlarmId(auth.alarmId) ?: continue
            if (currentAuth.reconcileState != "RECONCILING") continue

            Log.i(TAG, "Reconciling authority for alarm ${currentAuth.alarmId} at rev ${currentAuth.scheduleRevision}")
            val result = owner.executeOperation(
                alarmId = currentAuth.alarmId,
                desiredOperation = currentAuth.desiredOperation,
                desiredTriggerMillis = currentAuth.desiredTriggerMillis,
                actionKey = currentAuth.actionKey
            )

            if (result is OwnerResult.OsAccepted || result is OwnerResult.DuplicateIgnored) {
                reconciledCount++
            }
        }
        return reconciledCount
    }
}
