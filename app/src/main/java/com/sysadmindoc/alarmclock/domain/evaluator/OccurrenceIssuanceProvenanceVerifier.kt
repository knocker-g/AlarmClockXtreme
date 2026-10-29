package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence

/**
 * Pure, side-effect-free In-Database Occurrence Issuance Provenance Verifier (KNO-154 Phase 0C-B).
 * Queries Room DB (`IssuedOccurrence`, `IssueSlot`, `ScheduleAuthority`, `Alarm`) to verify
 * whether a firing Intent is backed by an authentic, active, non-revoked issuance record.
 */
class OccurrenceIssuanceProvenanceVerifier(
    private val database: AlarmDatabase,
    private val fireSafetyPlanner: NormalAlarmFireSafetyPlanner = NormalAlarmFireSafetyPlanner()
) {
    /**
     * Verifies firing safety for an incoming trigger by checking actual Room DB issuance provenance records.
     */
    suspend fun verifyAndPlanFireSafety(
        alarmId: Long,
        firedTargetEpochMs: Long,
        firedBaseTriggerTimeEpochMs: Long,
        firedGeneration: Int,
        issueToken: String?,
        consumedOccurrenceKeys: Set<String> = emptySet(),
        allowHidePersonalRuntimeFeatures: Boolean = false
    ): FireSafetyDecision {
        val alarmDao = database.alarmDao()
        val exceptionDao = database.scheduleExceptionDao()
        val authorityDao = database.scheduleAuthorityDao()
        val issuedDao = database.issuedOccurrenceDao()

        // 1. Re-read Alarm definition from DB
        val alarm = alarmDao.getById(alarmId)
            ?: return FireSafetyDecision(
                outcome = FireSafetyOutcome.UNVERIFIED_INTENT_DATA,
                alarmId = alarmId,
                firedTargetEpochMs = firedTargetEpochMs,
                firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
                firedGeneration = firedGeneration,
                isFireSuppressionRequired = false,
                isFireAdmissionConfirmed = false,
                decisionReason = "Alarm not found in database"
            )

        // 2. Read persisted ScheduleExceptions
        val exceptions = exceptionDao.getExceptionsForAlarmGeneration(alarmId, alarm.generation)

        // 3. Read ScheduleAuthority
        val storedAuthority = authorityDao.getAuthorityByAlarmId(alarmId)
        val expectedAuthorityFingerprint = storedAuthority?.let { ScheduleAuthorityFingerprint.fromAuthority(it) }

        // 4. Read IssuedOccurrence and IssueSlot from Room DB if issueToken provided
        val issuanceProof: FireIssuanceProof? = if (!issueToken.isNullOrBlank()) {
            val issuedOccurrence = issuedDao.getOccurrence(issueToken)
            val slot = issuedDao.getSlot(alarmId)

            if (issuedOccurrence != null &&
                issuedOccurrence.alarmId == alarmId &&
                issuedOccurrence.scheduledAt == firedTargetEpochMs &&
                (slot == null || slot.currentIssueToken == issueToken)
            ) {
                FireIssuanceProof(
                    issueToken = issuedOccurrence.issueToken,
                    alarmId = issuedOccurrence.alarmId,
                    scheduledAt = issuedOccurrence.scheduledAt,
                    fireId = issuedOccurrence.fireId,
                    alarmGeneration = alarm.generation,
                    authorityFingerprint = expectedAuthorityFingerprint,
                    status = issuedOccurrence.status
                )
            } else null
        } else null

        // 5. Evaluate Fire Safety via NormalAlarmFireSafetyPlanner
        return fireSafetyPlanner.planFireSafety(
            alarm = alarm,
            firedTargetEpochMs = firedTargetEpochMs,
            firedBaseTriggerTimeEpochMs = firedBaseTriggerTimeEpochMs,
            firedGeneration = firedGeneration,
            exceptions = exceptions,
            issuanceProof = issuanceProof,
            expectedAuthorityFingerprint = expectedAuthorityFingerprint,
            consumedOccurrenceKeys = consumedOccurrenceKeys,
            allowHidePersonalRuntimeFeatures = allowHidePersonalRuntimeFeatures
        )
    }
}
