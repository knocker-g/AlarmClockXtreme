package com.sysadmindoc.alarmclock.domain

import android.util.Log
import com.sysadmindoc.alarmclock.data.local.ScheduleAuthorityDao
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Startup and background reconciler for durable [ScheduleAuthority] operations.
 *
 * Source-level entrypoints:
 * - [com.sysadmindoc.alarmclock.worker.AlarmHealthWorker]
 * - [com.sysadmindoc.alarmclock.worker.BootRescheduleWorker]
 *
 * Guarantees:
 * - Re-reads CURRENT authority from DB for every row before applying (never replays stale job targets).
 * - Re-evaluates exact alarm capability.
 * - [reconcilePendingAuthorities] handles routine 'RECONCILING' state reconciliation.
 * - [reconcileBootAuthorities] handles dedicated post-boot OS re-registration for 'SETTLED' and 'RECONCILING' live authorities because OS AlarmManager registrations do NOT survive reboot.
 */
@Singleton
class ScheduleAuthorityReconciler @Inject constructor(
    private val authorityDao: ScheduleAuthorityDao,
    private val owner: ScheduleAuthorityOwner
) {

    companion object {
        private const val TAG = "ScheduleAuthorityReconciler"
    }

    /** Routine health-check reconciliation for pending 'RECONCILING' authorities. */
    suspend fun reconcilePendingAuthorities(): Int {
        val allAuthorities = authorityDao.getAllAuthorities()
        val reconcilingList = allAuthorities.filter { it.reconcileState == "RECONCILING" }

        var reconciledCount = 0
        for (auth in reconcilingList) {
            val currentAuth = authorityDao.getAuthorityByAlarmId(auth.alarmId) ?: continue
            if (currentAuth.reconcileState != "RECONCILING") continue

            Log.i(TAG, "Reconciling pending authority for alarm ${currentAuth.alarmId} at rev ${currentAuth.scheduleRevision}")
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

    /** Dedicated post-boot OS re-registration for live authorities. */
    suspend fun reconcileBootAuthorities(): Int {
        val allAuthorities = authorityDao.getAllAuthorities()
        val bootTargets = allAuthorities.filter {
            it.lifecycle == "LIVE" && (it.reconcileState == "RECONCILING" || it.reconcileState == "SETTLED")
        }

        var reRegisteredCount = 0
        for (auth in bootTargets) {
            val currentAuth = authorityDao.getAuthorityByAlarmId(auth.alarmId) ?: continue
            if (currentAuth.lifecycle != "LIVE") continue

            // If SET, verify trigger is still in future
            if (currentAuth.desiredOperation == "SET" && (currentAuth.desiredTriggerMillis == null || currentAuth.desiredTriggerMillis <= System.currentTimeMillis())) {
                Log.i(TAG, "Boot target for alarm ${currentAuth.alarmId} is in the past; skipping OS re-registration")
                continue
            }

            Log.i(TAG, "Boot OS re-registration for alarm ${currentAuth.alarmId} at rev ${currentAuth.scheduleRevision}")
            // Use dedicated boot actionKey to force OS apply re-execution
            val bootActionKey = "act-boot-${currentAuth.alarmId}-${currentAuth.scheduleRevision}"
            val result = owner.executeOperation(
                alarmId = currentAuth.alarmId,
                desiredOperation = currentAuth.desiredOperation,
                desiredTriggerMillis = currentAuth.desiredTriggerMillis,
                actionKey = bootActionKey
            )

            if (result is OwnerResult.OsAccepted || result is OwnerResult.DuplicateIgnored) {
                reRegisteredCount++
            }
        }
        return reRegisteredCount
    }
}
