package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import java.time.LocalDate
import java.time.ZoneId

/**
 * Pure, side-effect-free In-Database Reconcile Provenance Verifier (KNO-154 Phase 0C-B).
 * Queries Room DB (`Alarm`, `ScheduleAuthority`, `ScheduleException`) to verify whether
 * a stored projection differs from the evaluated post-skip next candidate occurrence.
 */
class ReconcileProvenanceVerifier(
    private val database: AlarmDatabase,
    private val reconcilePlanner: NormalScheduleReconcilePlanner = NormalScheduleReconcilePlanner()
) {
    /**
     * Queries Room DB and evaluates reconciliation decision for [alarmId] at [nowEpochMs].
     */
    suspend fun verifyAndPlanReconcile(
        alarmId: Long,
        nowEpochMs: Long,
        holidaySnapshotProvider: (LocalDate) -> HolidaySnapshot = { HolidaySnapshot() },
        holidayPolicyOverride: HolidayPolicy? = null,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        evaluationZoneId: ZoneId = ZoneId.systemDefault(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): ReconcileDecision {
        val alarmDao = database.alarmDao()
        val exceptionDao = database.scheduleExceptionDao()
        val authorityDao = database.scheduleAuthorityDao()

        val alarm = alarmDao.getById(alarmId)
            ?: return ReconcileDecision(
                outcome = ReconcileOutcome.UNSUPPORTED_SCHEDULE,
                alarmId = alarmId,
                currentStoredNextTriggerTimeEpochMs = 0L,
                evaluatedPostSkipNextTargetEpochMs = null,
                activeGeneration = 0,
                isOsRescheduleCandidate = false,
                decisionReason = "Alarm not found in database"
            )

        val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
        val expectedAuthorityFingerprint = storedAuthority?.let { ScheduleAuthorityFingerprint.fromAuthority(it) }
        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarmId, alarm.generation)

        return reconcilePlanner.planReconcile(
            alarm = alarm,
            storedAuthority = storedAuthority,
            exceptions = exceptions,
            nowEpochMs = nowEpochMs,
            expectedAuthorityFingerprint = expectedAuthorityFingerprint,
            holidaySnapshotProvider = holidaySnapshotProvider,
            holidayPolicyOverride = holidayPolicyOverride,
            consumedOccurrenceKeys = consumedOccurrenceKeys,
            evaluationZoneId = evaluationZoneId,
            allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
        )
    }
}
