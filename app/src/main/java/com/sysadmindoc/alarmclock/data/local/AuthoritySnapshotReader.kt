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
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface RoomSnapshotReadResult {
    data class Success(
        val meta: MetaDescriptor?,
        val owners: List<OwnerDescriptor>,
        val report: CoverageValidationReport
    ) : RoomSnapshotReadResult

    data class Failure(val details: String) : RoomSnapshotReadResult
}

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
        val result = readAuthoritySnapshot()
        return when (result) {
            is RoomSnapshotReadResult.Success -> result.report
            is RoomSnapshotReadResult.Failure -> CoverageValidationReport(
                result = CoverageResult.READ_FAILED,
                reasonCode = CoverageReasonCode.NONE,
                details = result.details
            )
        }
    }

    /**
     * Reads a consistent transactional snapshot of Alarms, Authorities, and Metadata
     * from Room inside a single `withTransaction` block and returns [RoomSnapshotReadResult].
     */
    suspend fun readAuthoritySnapshot(): RoomSnapshotReadResult {
        val (alarms, authorities, metaRows) = try {
            database.withTransaction {
                Triple(
                    database.alarmDao().getAll(),
                    database.scheduleAuthorityDao().getAllAuthorities(),
                    database.scheduleAuthorityDao().getAllMeta()
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return RoomSnapshotReadResult.Failure("Failed to read authority snapshot from Room database")
        }

        // Evaluate metadata row counts according to Design LOCK v1.6 Rules
        val metaDescriptor: MetaDescriptor? = when (metaRows.size) {
            0 -> null
            1 -> metaRows.single().toDescriptor()
            else -> {
                return RoomSnapshotReadResult.Success(
                    meta = null,
                    owners = authorities.map { it.toDescriptor() },
                    report = CoverageValidationReport(
                        result = CoverageResult.INVALID,
                        reasonCode = CoverageReasonCode.META_ID_INVALID,
                        details = "Multiple schedule_authority_meta rows detected: ${metaRows.size}"
                    )
                )
            }
        }

        val alarmDescriptors = alarms.map { it.toDescriptor() }
        val ownerDescriptors = authorities.map { it.toDescriptor() }

        val validationReport = AuthorityCoverageValidator.validate(
            alarms = alarmDescriptors,
            owners = ownerDescriptors,
            meta = metaDescriptor
        )

        return RoomSnapshotReadResult.Success(
            meta = metaDescriptor,
            owners = ownerDescriptors,
            report = validationReport
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
