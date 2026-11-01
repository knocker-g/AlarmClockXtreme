package com.sysadmindoc.alarmclock.data.holiday

import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverrideSource
import java.time.LocalDate

sealed interface RangeMutationResult {
    data class Applied(
        val affectedDatesCount: Int,
        val reconcileResult: HolidayReconcileResult? = null
    ) : RangeMutationResult
    data class Conflict(val conflictingDates: List<LocalDate>) : RangeMutationResult
    data object NoOp : RangeMutationResult
}

interface ManualHolidayOverrideStore : ManualHolidayOverrideSource {
    val isCorrupted: Boolean
    val corruptionCause: Throwable?
    val lastReconcileResult: HolidayReconcileResult?

    suspend fun setOverride(date: LocalDate, override: ManualHolidayOverride): HolidayReconcileResult?
    suspend fun clearOverride(date: LocalDate): HolidayReconcileResult?
    suspend fun setRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        override: ManualHolidayOverride,
        overwriteExisting: Boolean
    ): RangeMutationResult
    suspend fun getAllOverrides(): Map<LocalDate, ManualHolidayOverride>
    suspend fun restoreOverrides(
        overrides: Map<LocalDate, ManualHolidayOverride>,
        triggerReconcile: Boolean = true
    ): HolidayReconcileResult?
    fun recoverFromCorruption(
        clearCorruptedFile: Boolean,
        initialOverrides: Map<LocalDate, ManualHolidayOverride> = emptyMap()
    )
}
