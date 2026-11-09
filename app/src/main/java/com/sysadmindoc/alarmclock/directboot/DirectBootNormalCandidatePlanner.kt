package com.sysadmindoc.alarmclock.directboot

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.AlarmDescriptor
import com.sysadmindoc.alarmclock.domain.AuthorityCoverageValidator
import com.sysadmindoc.alarmclock.domain.CoverageReasonCode
import com.sysadmindoc.alarmclock.domain.CoverageResult
import com.sysadmindoc.alarmclock.domain.CoverageValidationReport
import com.sysadmindoc.alarmclock.domain.MetaDescriptor
import com.sysadmindoc.alarmclock.domain.OwnerDescriptor
import com.sysadmindoc.alarmclock.domain.ScheduleAuthorityBootstrapAdapter

/**
 * Pure, deterministic candidate planner for KNO-75.
 * Evaluates an already-supplied Credential-Encrypted (CE) snapshot to produce
 * at most five authoritative Normal alarm Direct Boot candidates.
 *
 * Invariants enforced:
 * - Pure function without Context, Room, SharedPreferences, or AlarmManager dependencies.
 * - Operates strictly on Normal alarms; Quick alarms are excluded.
 * - Enforces enabled status and strictly future trigger times.
 * - Validates authentic ScheduleAuthority instanceToken, generation, revisions, and restore epoch.
 * - Validates canonical Definition Revision via [ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision].
 * - Enforces trigger alignment between Alarm and ScheduleAuthority for enabled alarms.
 * - Distinguishes legitimately excluded alarms, eligible SETTLED candidates, and unresolved authorities.
 * - Unresolved authorities (PENDING, QUARANTINE, RETRY_REQUIRED) produce an explicit blocked result
 *   ([NormalCandidatePlanResult.UnresolvedAuthority]) to prohibit partial queue replacement.
 * - Recomputes coverage directly from input snapshot entities; missing or invalid Meta fails closed.
 * - Does not accept an externally supplied COMPLETE coverage report if inconsistent with snapshot entities.
 * - Employs [DirectBootQueueItem.QUEUE_COMPARATOR] for deterministic chronological ordering.
 * - Preserves immutable input collections.
 */
sealed interface NormalCandidatePlanResult {
    /**
     * Successfully produced an authoritative plan with 1 to 5 candidates.
     */
    data class ValidWithCandidates(
        val candidates: List<DirectBootQueueItem>
    ) : NormalCandidatePlanResult

    /**
     * Successfully evaluated a coherent snapshot where zero Normal alarms are eligible
     * (all Normal alarms are legitimately excluded as disabled or expired).
     */
    data object ValidEmpty : NormalCandidatePlanResult

    /**
     * Blocked because one or more active Normal alarms have unresolved or unverified
     * authorities (e.g. reconcileState is PENDING, QUARANTINE, or RETRY_REQUIRED).
     * Prohibits partial authoritative replacement.
     */
    data class UnresolvedAuthority(
        val alarmId: Long,
        val reconcileState: String,
        val reason: String
    ) : NormalCandidatePlanResult

    /**
     * Failed because coverage validation report was not COMPLETE or authority coverage is invalid.
     */
    data class InvalidCoverage(
        val result: CoverageResult,
        val reasonCode: CoverageReasonCode,
        val details: String
    ) : NormalCandidatePlanResult

    /**
     * Failed due to restore epoch mismatch, negative epoch, or corrupted/blank identity token.
     */
    data class InvalidEpochOrIdentity(
        val reason: String
    ) : NormalCandidatePlanResult

    /**
     * Failed due to internal snapshot inconsistency (e.g. duplicate IDs, drift between
     * Alarm nextTriggerTime and ScheduleAuthority desiredTriggerMillis, definition revision drift).
     */
    data class InconsistentSnapshot(
        val reason: String
    ) : NormalCandidatePlanResult
}

object DirectBootNormalCandidatePlanner {

    const val MAX_CANDIDATE_CAPACITY = 5

    /**
     * Evaluates a supplied CE snapshot and returns a deterministic [NormalCandidatePlanResult].
     */
    fun planCandidates(
        alarms: List<Alarm>,
        authorities: List<ScheduleAuthority>,
        meta: ScheduleAuthorityMeta?,
        coverageReport: CoverageValidationReport? = null,
        now: Long
    ): NormalCandidatePlanResult {
        // Step 1: Validate uniqueness and positivity of Alarm IDs
        val seenAlarmIds = HashSet<Long>()
        for (alarm in alarms) {
            if (alarm.id <= 0L) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Invalid non-positive alarm ID: ${alarm.id}"
                )
            }
            if (!seenAlarmIds.add(alarm.id)) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Duplicate alarm ID detected in snapshot: ${alarm.id}"
                )
            }
        }

        // Step 2: Validate uniqueness and positivity of Authority alarm IDs
        val seenAuthIds = HashSet<Long>()
        for (auth in authorities) {
            if (auth.alarmId <= 0L) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Invalid non-positive authority alarmId: ${auth.alarmId}"
                )
            }
            if (!seenAuthIds.add(auth.alarmId)) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Duplicate authority alarmId detected in snapshot: ${auth.alarmId}"
                )
            }
        }

        // Step 3: Missing or invalid Meta must fail closed. Do not manufacture restore epoch or bootstrap state.
        if (meta == null) {
            return NormalCandidatePlanResult.InvalidCoverage(
                result = CoverageResult.UNINITIALIZED,
                reasonCode = CoverageReasonCode.META_ABSENT_AUTHORITY_EMPTY,
                details = "ScheduleAuthorityMeta is absent from snapshot"
            )
        }
        if (meta.id != 1) {
            return NormalCandidatePlanResult.InvalidCoverage(
                result = CoverageResult.INVALID,
                reasonCode = CoverageReasonCode.META_ID_INVALID,
                details = "Metadata ID must be 1: ${meta.id}"
            )
        }
        if (meta.restoreEpoch <= 0L) {
            return NormalCandidatePlanResult.InvalidEpochOrIdentity(
                "Metadata restoreEpoch must be positive: ${meta.restoreEpoch}"
            )
        }
        if (meta.bootstrapState != "ENFORCED") {
            return NormalCandidatePlanResult.InvalidCoverage(
                result = CoverageResult.INVALID,
                reasonCode = CoverageReasonCode.BOOTSTRAP_PREPARING,
                details = "Bootstrap state is not ENFORCED: ${meta.bootstrapState}"
            )
        }

        val normalAlarms = alarms.filter { it.kind == Alarm.KIND_NORMAL }
        val authorityMap = authorities.associateBy { it.alarmId }

        // Step 4: Validate active Normal alarms and detect unresolved authorities
        val activeEligibleAlarms = ArrayList<Pair<Alarm, ScheduleAuthority>>()
        for (alarm in normalAlarms) {
            // Legitimately excluded: disabled alarms
            if (!alarm.isEnabled) {
                continue
            }

            // Legitimately excluded: expired past or non-future triggers
            if (alarm.nextTriggerTime <= now) {
                continue
            }

            // Active normal alarm with future trigger strictly requires ScheduleAuthority
            val authority = authorityMap[alarm.id]
                ?: return NormalCandidatePlanResult.InvalidCoverage(
                    result = CoverageResult.INCOMPLETE,
                    reasonCode = CoverageReasonCode.UNCOVERED_ALARM,
                    details = "Active normal alarm ${alarm.id} is missing ScheduleAuthority record"
                )

            // Validate generation
            if (alarm.generation < 1) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Alarm ${alarm.id} has non-positive generation: ${alarm.generation}"
                )
            }

            // Validate revision
            if (alarm.revision < 1) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Alarm ${alarm.id} has non-positive revision: ${alarm.revision}"
                )
            }

            // Canonical Definition Revision verification
            val expectedDefRev = ScheduleAuthorityBootstrapAdapter.computeDefinitionRevision(alarm)
            if (authority.definitionRevision != expectedDefRev) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Definition revision mismatch on alarm ${alarm.id}: expected=$expectedDefRev, actual=${authority.definitionRevision}"
                )
            }

            // Validate scheduleRevision
            if (authority.scheduleRevision <= 0L) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Authority for alarm ${alarm.id} has non-positive scheduleRevision: ${authority.scheduleRevision}"
                )
            }

            // Validate restore epoch match
            if (authority.restoreEpoch != meta.restoreEpoch) {
                return NormalCandidatePlanResult.InvalidEpochOrIdentity(
                    "Authority restoreEpoch ${authority.restoreEpoch} mismatches meta restoreEpoch ${meta.restoreEpoch} for alarm ${alarm.id}"
                )
            }

            // Validate instanceToken
            if (authority.instanceToken.isBlank()) {
                return NormalCandidatePlanResult.InvalidEpochOrIdentity(
                    "Authority instanceToken is blank for alarm ${alarm.id}"
                )
            }

            // Validate trigger consistency
            if (authority.desiredTriggerMillis == null || authority.desiredTriggerMillis != alarm.nextTriggerTime) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Trigger mismatch for alarm ${alarm.id}: alarm.nextTriggerTime=${alarm.nextTriggerTime}, authority.desiredTriggerMillis=${authority.desiredTriggerMillis}"
                )
            }

            // Validate operation
            if (authority.desiredOperation != "SET") {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Enabled alarm ${alarm.id} with future trigger has unexpected desiredOperation: ${authority.desiredOperation}"
                )
            }

            // Validate lifecycle
            if (authority.lifecycle != "LIVE") {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Enabled alarm ${alarm.id} with future trigger has unexpected lifecycle: ${authority.lifecycle}"
                )
            }

            // Unresolved authority check: PENDING, QUARANTINE, or RETRY_REQUIRED
            // Prohibits partial authoritative replacement by returning an explicit blocked result.
            if (authority.reconcileState != "SETTLED") {
                return NormalCandidatePlanResult.UnresolvedAuthority(
                    alarmId = alarm.id,
                    reconcileState = authority.reconcileState,
                    reason = "Authority for active normal alarm ${alarm.id} is unresolved: ${authority.reconcileState}"
                )
            }

            activeEligibleAlarms.add(alarm to authority)
        }

        // Step 5: Coverage Recomputation from snapshot entities
        val alarmsDesc = normalAlarms.map { AlarmDescriptor(it.id, it.isEnabled) }
        val ownersDesc = authorities.map {
            OwnerDescriptor(
                alarmId = it.alarmId,
                instanceToken = it.instanceToken,
                definitionRevision = it.definitionRevision,
                scheduleRevision = it.scheduleRevision,
                lifecycle = it.lifecycle,
                desiredOperation = it.desiredOperation,
                desiredTriggerMillis = it.desiredTriggerMillis,
                reconcileState = it.reconcileState,
                restoreEpoch = it.restoreEpoch
            )
        }
        val metaDesc = MetaDescriptor(meta.id, meta.restoreEpoch, meta.bootstrapState)

        val computedReport = AuthorityCoverageValidator.validate(alarmsDesc, ownersDesc, metaDesc)
        if (computedReport.result != CoverageResult.COMPLETE) {
            return NormalCandidatePlanResult.InvalidCoverage(
                result = computedReport.result,
                reasonCode = computedReport.reasonCode,
                details = computedReport.details
            )
        }

        // Do not accept a supplied CoverageValidationReport.COMPLETE as sufficient proof
        // when it is inconsistent with the supplied snapshot or indicates an external failure.
        if (coverageReport != null && coverageReport.result != CoverageResult.COMPLETE) {
            return NormalCandidatePlanResult.InvalidCoverage(
                result = coverageReport.result,
                reasonCode = coverageReport.reasonCode,
                details = coverageReport.details
            )
        }

        // Step 6: Empty vs populated resolution
        if (activeEligibleAlarms.isEmpty()) {
            return NormalCandidatePlanResult.ValidEmpty
        }

        // Step 7: Construct DirectBootQueueItem candidates
        val eligibleCandidates = ArrayList<DirectBootQueueItem>(activeEligibleAlarms.size)
        for ((alarm, authority) in activeEligibleAlarms) {
            val item = DirectBootQueueItem(
                kind = DirectBootQueueItem.KIND_NORMAL,
                alarmId = alarm.id,
                triggerTime = alarm.nextTriggerTime,
                generation = alarm.generation,
                scheduleKind = alarm.scheduleKind,
                instanceToken = authority.instanceToken,
                timeLabel = DirectBootQueueItem.generateSafeTimeLabel(
                    alarm.nextTriggerTime,
                    alarm.timezonePolicy,
                    alarm.fixedTimezoneId
                ),
                playDefaultSound = alarm.ringtoneUri.isBlank(),
                vibrationEnabled = alarm.vibrationEnabled,
                updatedAt = now,
                timezonePolicy = alarm.timezonePolicy,
                fixedTimezoneId = alarm.fixedTimezoneId,
                schemaVersion = DirectBootQueueItem.CURRENT_SCHEMA_VERSION
            )

            val eligibility = item.evaluateEligibility(now = now)
            if (eligibility != QueueCandidateStatus.ELIGIBLE) {
                return NormalCandidatePlanResult.InconsistentSnapshot(
                    "Generated candidate is ineligible: $eligibility"
                )
            }

            eligibleCandidates.add(item)
        }

        // Step 8: Deterministic stable sorting and truncation to at most 5
        val sortedCandidates = eligibleCandidates
            .sortedWith(DirectBootQueueItem.QUEUE_COMPARATOR)
            .take(MAX_CANDIDATE_CAPACITY)

        return NormalCandidatePlanResult.ValidWithCandidates(sortedCandidates)
    }

    /**
     * Overload supporting raw metaRows list from Room query.
     */
    fun planCandidates(
        alarms: List<Alarm>,
        authorities: List<ScheduleAuthority>,
        metaRows: List<ScheduleAuthorityMeta>,
        coverageReport: CoverageValidationReport? = null,
        now: Long
    ): NormalCandidatePlanResult {
        if (metaRows.size > 1) {
            return NormalCandidatePlanResult.InvalidCoverage(
                result = CoverageResult.INVALID,
                reasonCode = CoverageReasonCode.META_ID_INVALID,
                details = "Multiple schedule_authority_meta rows detected: ${metaRows.size}"
            )
        }
        return planCandidates(
            alarms = alarms,
            authorities = authorities,
            meta = metaRows.singleOrNull(),
            coverageReport = coverageReport,
            now = now
        )
    }
}
