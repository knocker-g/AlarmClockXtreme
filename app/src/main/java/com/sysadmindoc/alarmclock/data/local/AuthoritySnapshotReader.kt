package com.sysadmindoc.alarmclock.data.local

import androidx.room.withTransaction
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthoritySnapshotReader @Inject constructor(
    private val database: AlarmDatabase
) {
    /**
     * Reads a consistent transactional snapshot of Alarms, Authorities, and Metadata
     * from Room inside a single `withTransaction` block, then validates it via
     * [AuthorityCoverageValidator].
     *
     * Returns a typed [CoverageValidationReport] indicating whether authority coverage
     * is UNINITIALIZED, INCOMPLETE, INVALID, COMPLETE, or READ_FAILED.
     */
    suspend fun readAndValidateSnapshot(): CoverageValidationReport {
        val (alarms, authorities, metaRows) = try {
            database.withTransaction {
                Triple(
                    database.alarmDao().getAll(),
                    database.scheduleAuthorityDao().getAllAuthorities(),
                    database.scheduleAuthorityDao().getAllMeta()
                )
            }
        } catch (e: Exception) {
            return CoverageValidationReport(
                result = CoverageResult.READ_FAILED,
                reasonCode = CoverageReasonCode.NONE,
                details = "Failed to read authority snapshot from Room database: ${e.message}"
            )
        }

        // Evaluate metadata row counts according to Design LOCK v1.6 Rules
        val metaDescriptor: MetaDescriptor? = when (metaRows.size) {
            0 -> null
            1 -> metaRows.single().toDescriptor()
            else -> {
                return CoverageValidationReport(
                    result = CoverageResult.INVALID,
                    reasonCode = CoverageReasonCode.META_ID_INVALID,
                    details = "Multiple schedule_authority_meta rows detected: ${metaRows.size}"
                )
            }
        }

        val alarmDescriptors = alarms.map { it.toDescriptor() }
        val ownerDescriptors = authorities.map { it.toDescriptor() }

        return AuthorityCoverageValidator.validate(
            alarms = alarmDescriptors,
            owners = ownerDescriptors,
            meta = metaDescriptor
        )
    }

    private fun Alarm.toDescriptor(): AlarmDescriptor {
        return AlarmDescriptor(
            id = id,
            enabled = isEnabled
        )
    }

    private fun ScheduleAuthority.toDescriptor(): OwnerDescriptor {
        return OwnerDescriptor(
            alarmId = alarmId,
            instanceToken = instanceToken,
            definitionRevision = definitionRevision,
            scheduleRevision = scheduleRevision,
            lifecycle = lifecycle,
            desiredOperation = desiredOperation,
            desiredTriggerMillis = desiredTriggerMillis,
            reconcileState = reconcileState,
            restoreEpoch = restoreEpoch
        )
    }

    private fun ScheduleAuthorityMeta.toDescriptor(): MetaDescriptor {
        return MetaDescriptor(
            id = id,
            restoreEpoch = restoreEpoch,
            bootstrapState = bootstrapState
        )
    }
}
