package com.sysadmindoc.alarmclock.domain

data class AlarmDescriptor(
    val id: Long,
    val enabled: Boolean
)

data class OwnerDescriptor(
    val alarmId: Long,
    val instanceToken: String,
    val definitionRevision: Long,
    val scheduleRevision: Long,
    val lifecycle: String,
    val desiredOperation: String,
    val desiredTriggerMillis: Long?,
    val reconcileState: String,
    val restoreEpoch: Long
)

data class MetaDescriptor(
    val id: Int,
    val restoreEpoch: Long,
    val bootstrapState: String
)

enum class CoverageResult {
    UNINITIALIZED,
    INCOMPLETE,
    INVALID,
    COMPLETE,
    READ_FAILED
}

enum class CoverageReasonCode {
    NONE,
    META_ABSENT_AUTHORITY_EMPTY,
    META_ABSENT_AUTHORITY_PRESENT,
    META_ID_INVALID,
    META_EPOCH_NEGATIVE,
    META_BOOTSTRAP_STATE_INVALID,
    INVALID_ALARM_IDENTIFIER,
    DUPLICATE_ALARM_ID,
    DUPLICATE_OWNER_ID,
    MALFORMED_OWNER_IDENTIFIER,
    NONPOSITIVE_REVISION,
    MISMATCHED_RESTORE_EPOCH,
    INVALID_LIFECYCLE,
    INVALID_OPERATION,
    INVALID_RECONCILE_STATE,
    INVALID_TRIGGER_FOR_OPERATION,
    TOMBSTONE_ALARM_COLLISION,
    DISABLED_ALARM_DESIRED_SET,
    ORPHAN_LIVE_OWNER,
    UNCOVERED_ALARM,
    BOOTSTRAP_PREPARING,
    COVERAGE_VALID_COMPLETE
}

data class CoverageValidationReport(
    val result: CoverageResult,
    val reasonCode: CoverageReasonCode,
    val details: String = ""
)

object AuthorityCoverageValidator {

    private val VALID_LIFECYCLES = setOf("LIVE", "TOMBSTONE")
    private val VALID_OPERATIONS = setOf("SET", "CANCEL")
    private val VALID_RECONCILE_STATES = setOf("PENDING", "SETTLED", "RETRY_REQUIRED")
    private val VALID_BOOTSTRAP_STATES = setOf("PREPARING", "ENFORCED")

    /**
     * Validates structural and relational authority coverage for a given snapshot
     * of alarms, owners, and metadata according to the normative P1B1a Design Lock.
     */
    fun validate(
        alarms: List<AlarmDescriptor>,
        owners: List<OwnerDescriptor>,
        meta: MetaDescriptor?
    ): CoverageValidationReport {
        // 1. Missing Metadata Rules
        if (meta == null) {
            return if (owners.isEmpty()) {
                CoverageValidationReport(
                    result = CoverageResult.UNINITIALIZED,
                    reasonCode = CoverageReasonCode.META_ABSENT_AUTHORITY_EMPTY,
                    details = "Metadata is absent and authority table is empty"
                )
            } else {
                CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.META_ABSENT_AUTHORITY_PRESENT,
                    details = "Metadata is absent but authority records are present"
                )
            }
        }

        // 2. Metadata Field Validation
        if (meta.id != 1) {
            return CoverageValidationReport(
                result = CoverageResult.INVALID,
                reasonCode = CoverageReasonCode.META_ID_INVALID,
                details = "Metadata ID must be 1"
            )
        }
        if (meta.restoreEpoch <= 0L) {
            return CoverageValidationReport(
                result = CoverageResult.INVALID,
                reasonCode = CoverageReasonCode.META_EPOCH_NEGATIVE,
                details = "Metadata restoreEpoch must be positive"
            )
        }
        if (meta.bootstrapState !in VALID_BOOTSTRAP_STATES) {
            return CoverageValidationReport(
                result = CoverageResult.INVALID,
                reasonCode = CoverageReasonCode.META_BOOTSTRAP_STATE_INVALID,
                details = "Invalid bootstrapState"
            )
        }

        // Sort input lists deterministically by ID to ensure permutation independence
        val sortedAlarms = alarms.sortedBy { it.id }
        val sortedOwners = owners.sortedBy { it.alarmId }

        // 3. Alarm Validation & Duplicate ID Checks
        val alarmIds = mutableSetOf<Long>()
        for (alarm in sortedAlarms) {
            if (alarm.id <= 0L) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.INVALID_ALARM_IDENTIFIER,
                    details = "Alarm ID must be positive"
                )
            }
            if (!alarmIds.add(alarm.id)) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.DUPLICATE_ALARM_ID,
                    details = "Duplicate Alarm ID detected"
                )
            }
        }

        val ownerIds = mutableSetOf<Long>()
        for (owner in sortedOwners) {
            if (!ownerIds.add(owner.alarmId)) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.DUPLICATE_OWNER_ID,
                    details = "Duplicate Owner ID detected"
                )
            }
        }

        // 4. Owner Field Structural Validation
        val alarmMap = sortedAlarms.associateBy { it.id }
        for (owner in sortedOwners) {
            if (owner.alarmId <= 0L || owner.instanceToken.isBlank()) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.MALFORMED_OWNER_IDENTIFIER,
                    details = "Malformed owner identifier or blank token"
                )
            }
            if (owner.definitionRevision <= 0L || owner.scheduleRevision <= 0L) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.NONPOSITIVE_REVISION,
                    details = "Definition or schedule revision must be positive"
                )
            }
            if (owner.restoreEpoch != meta.restoreEpoch) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.MISMATCHED_RESTORE_EPOCH,
                    details = "Owner restoreEpoch does not match metadata restoreEpoch"
                )
            }
            if (owner.lifecycle !in VALID_LIFECYCLES) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.INVALID_LIFECYCLE,
                    details = "Invalid owner lifecycle"
                )
            }
            if (owner.desiredOperation !in VALID_OPERATIONS) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.INVALID_OPERATION,
                    details = "Invalid desiredOperation"
                )
            }
            if (owner.reconcileState !in VALID_RECONCILE_STATES) {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.INVALID_RECONCILE_STATE,
                    details = "Invalid reconcileState"
                )
            }

            if (owner.desiredOperation == "SET") {
                if (owner.desiredTriggerMillis == null || owner.desiredTriggerMillis <= 0L) {
                    return CoverageValidationReport(
                        result = CoverageResult.INVALID,
                        reasonCode = CoverageReasonCode.INVALID_TRIGGER_FOR_OPERATION,
                        details = "Operation SET requires a positive desiredTriggerMillis"
                    )
                }
            } else if (owner.desiredOperation == "CANCEL") {
                if (owner.desiredTriggerMillis != null) {
                    return CoverageValidationReport(
                        result = CoverageResult.INVALID,
                        reasonCode = CoverageReasonCode.INVALID_TRIGGER_FOR_OPERATION,
                        details = "Operation CANCEL requires a null desiredTriggerMillis"
                    )
                }
            }

            // 5. Cross-Entity Relational Rules
            val matchingAlarm = alarmMap[owner.alarmId]
            if (owner.lifecycle == "TOMBSTONE") {
                if (matchingAlarm != null) {
                    return CoverageValidationReport(
                        result = CoverageResult.INVALID,
                        reasonCode = CoverageReasonCode.TOMBSTONE_ALARM_COLLISION,
                        details = "TOMBSTONE owner cannot reference an existing Alarm"
                    )
                }
            } else if (owner.lifecycle == "LIVE") {
                if (matchingAlarm == null) {
                    return CoverageValidationReport(
                        result = CoverageResult.INVALID,
                        reasonCode = CoverageReasonCode.ORPHAN_LIVE_OWNER,
                        details = "LIVE owner has no corresponding Alarm definition"
                    )
                } else {
                    if (owner.desiredOperation == "SET" && !matchingAlarm.enabled) {
                        return CoverageValidationReport(
                            result = CoverageResult.INVALID,
                            reasonCode = CoverageReasonCode.DISABLED_ALARM_DESIRED_SET,
                            details = "Disabled Alarm cannot have desiredOperation SET"
                        )
                    }
                }
            }
        }

        // 6. Completeness Rule: Every Alarm must have a LIVE owner
        val ownerMap = sortedOwners.associateBy { it.alarmId }
        for (alarm in sortedAlarms) {
            val owner = ownerMap[alarm.id]
            if (owner == null || owner.lifecycle != "LIVE") {
                return CoverageValidationReport(
                    result = CoverageResult.INCOMPLETE,
                    reasonCode = CoverageReasonCode.UNCOVERED_ALARM,
                    details = "Alarm has no corresponding LIVE owner"
                )
            }
        }

        // 7. Bootstrap State Gate
        if (meta.bootstrapState == "PREPARING") {
            return CoverageValidationReport(
                result = CoverageResult.INCOMPLETE,
                reasonCode = CoverageReasonCode.BOOTSTRAP_PREPARING,
                details = "Metadata bootstrapState is PREPARING"
            )
        }

        return CoverageValidationReport(
            result = CoverageResult.COMPLETE,
            reasonCode = CoverageReasonCode.COVERAGE_VALID_COMPLETE,
            details = "Authority coverage is fully valid and COMPLETE"
        )
    }
}
