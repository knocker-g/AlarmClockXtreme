package com.sysadmindoc.alarmclock.directboot

import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthority
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleAuthorityMeta
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.util.Collections

/**
 * Immutable canonical fingerprint representing the authoritative Credential-Encrypted (CE)
 * state of all Normal alarms and authority metadata at snapshot time.
 *
 * Invariants:
 * - Exact structural equality: strongly typed immutable class with ordered collections; no lossy hash.
 * - Deterministic ordering: facts are sorted strictly by ID ascending.
 * - Complete coverage: evaluates all Normal alarms (both enabled and disabled) and all authorities.
 * - Quick alarms excluded: Quick alarms are never projected by the Normal coordinator.
 * - Orphan authorities preserved: authorities not mapped to any alarm are captured in [orphanAuthorities]
 *   and never silently discarded.
 * - Privacy-safe: excludes user-authored labels, secrets, contact info, coordinates, and audio URIs.
 * - Immutable by construction: backed by unmodifiable defensive copies; private primary constructor.
 * - No copy() bypass: ordinary class with explicit value semantics (equals, hashCode, toString)
 *   preventing synthetic data class copy methods from breaching encapsulation.
 */
class CeSnapshotFingerprint private constructor(
    val metaRows: List<MetaFact>,
    val normalAlarms: List<NormalAlarmFact>,
    val normalAuthorities: List<NormalAuthorityFact>,
    val orphanAuthorities: List<NormalAuthorityFact>
) {
    val restoreEpoch: Long? get() = metaRows.firstOrNull()?.restoreEpoch
    val bootstrapState: String? get() = metaRows.firstOrNull()?.bootstrapState
    val normalAlarmCount: Int get() = normalAlarms.size
    val enabledNormalAlarmCount: Int get() = normalAlarms.count { it.isEnabled }
    val orphanAuthorityCount: Int get() = orphanAuthorities.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CeSnapshotFingerprint) return false
        return metaRows == other.metaRows &&
                normalAlarms == other.normalAlarms &&
                normalAuthorities == other.normalAuthorities &&
                orphanAuthorities == other.orphanAuthorities
    }

    override fun hashCode(): Int {
        var result = metaRows.hashCode()
        result = 31 * result + normalAlarms.hashCode()
        result = 31 * result + normalAuthorities.hashCode()
        result = 31 * result + orphanAuthorities.hashCode()
        return result
    }

    override fun toString(): String = buildString {
        append("CeSnapshotFingerprint(")
        append("restoreEpoch=").append(restoreEpoch)
        append(", bootstrapState=").append(bootstrapState)
        append(", metaRowCount=").append(metaRows.size)
        append(", normalAlarmCount=").append(normalAlarmCount)
        append(", enabledNormalAlarmCount=").append(enabledNormalAlarmCount)
        append(", normalAuthorityCount=").append(normalAuthorities.size)
        append(", orphanAuthorityCount=").append(orphanAuthorityCount)
        append(')')
    }

    data class MetaFact(
        val id: Int,
        val restoreEpoch: Long,
        val bootstrapState: String
    )

    data class NormalAlarmFact(
        val alarmId: Long,
        val isEnabled: Boolean,
        val triggerTime: Long,
        val generation: Int,
        val alarmRevision: Int,
        val scheduleKind: String,
        val playDefaultSound: Boolean,
        val vibrationEnabled: Boolean,
        val timezonePolicy: String,
        val fixedTimezoneId: String
    )

    data class NormalAuthorityFact(
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

    companion object {
        fun create(
            alarms: List<Alarm>,
            authorities: List<ScheduleAuthority>,
            metaRows: List<ScheduleAuthorityMeta>
        ): CeSnapshotFingerprint {
            val metaFacts = metaRows
                .map { MetaFact(it.id, it.restoreEpoch, it.bootstrapState) }
                .sortedBy { it.id }
                .toList()

            val normalAlarmsList = alarms
                .filter { !it.isQuickAlarm && it.kind != Alarm.KIND_QUICK }

            val normalAlarmIds = normalAlarmsList
                .map { it.id }
                .toSet()

            val quickAlarmIds = alarms
                .filter { it.isQuickAlarm || it.kind == Alarm.KIND_QUICK }
                .map { it.id }
                .toSet()

            val normalAlarmFacts = normalAlarmsList
                .map { alarm ->
                    NormalAlarmFact(
                        alarmId = alarm.id,
                        isEnabled = alarm.isEnabled,
                        triggerTime = alarm.nextTriggerTime,
                        generation = alarm.generation,
                        alarmRevision = alarm.revision,
                        scheduleKind = alarm.scheduleKind,
                        playDefaultSound = alarm.ringtoneUri.isBlank(),
                        vibrationEnabled = alarm.vibrationEnabled,
                        timezonePolicy = alarm.timezonePolicy,
                        fixedTimezoneId = alarm.fixedTimezoneId
                    )
                }
                .sortedBy { it.alarmId }
                .toList()

            // Authorities linked to confirmed Normal alarms
            val normalAuthorityFacts = authorities
                .filter { normalAlarmIds.contains(it.alarmId) }
                .map { auth ->
                    NormalAuthorityFact(
                        alarmId = auth.alarmId,
                        instanceToken = auth.instanceToken,
                        definitionRevision = auth.definitionRevision,
                        scheduleRevision = auth.scheduleRevision,
                        lifecycle = auth.lifecycle,
                        desiredOperation = auth.desiredOperation,
                        desiredTriggerMillis = auth.desiredTriggerMillis,
                        reconcileState = auth.reconcileState,
                        restoreEpoch = auth.restoreEpoch
                    )
                }
                .sortedBy { it.alarmId }
                .toList()

            // Authorities whose alarmId matches neither Normal nor Quick alarms in the snapshot (orphan/unknown)
            // MUST NOT be silently discarded!
            val orphanAuthorityFacts = authorities
                .filter { !normalAlarmIds.contains(it.alarmId) && !quickAlarmIds.contains(it.alarmId) }
                .map { auth ->
                    NormalAuthorityFact(
                        alarmId = auth.alarmId,
                        instanceToken = auth.instanceToken,
                        definitionRevision = auth.definitionRevision,
                        scheduleRevision = auth.scheduleRevision,
                        lifecycle = auth.lifecycle,
                        desiredOperation = auth.desiredOperation,
                        desiredTriggerMillis = auth.desiredTriggerMillis,
                        reconcileState = auth.reconcileState,
                        restoreEpoch = auth.restoreEpoch
                    )
                }
                .sortedBy { it.alarmId }
                .toList()

            return CeSnapshotFingerprint(
                metaRows = Collections.unmodifiableList(metaFacts),
                normalAlarms = Collections.unmodifiableList(normalAlarmFacts),
                normalAuthorities = Collections.unmodifiableList(normalAuthorityFacts),
                orphanAuthorities = Collections.unmodifiableList(orphanAuthorityFacts)
            )
        }

        fun fromFacts(
            metaRows: List<MetaFact>,
            normalAlarms: List<NormalAlarmFact>,
            normalAuthorities: List<NormalAuthorityFact>,
            orphanAuthorities: List<NormalAuthorityFact> = emptyList()
        ): CeSnapshotFingerprint {
            return CeSnapshotFingerprint(
                metaRows = Collections.unmodifiableList(metaRows.sortedBy { it.id }.toList()),
                normalAlarms = Collections.unmodifiableList(normalAlarms.sortedBy { it.alarmId }.toList()),
                normalAuthorities = Collections.unmodifiableList(normalAuthorities.sortedBy { it.alarmId }.toList()),
                orphanAuthorities = Collections.unmodifiableList(orphanAuthorities.sortedBy { it.alarmId }.toList())
            )
        }
    }
}
