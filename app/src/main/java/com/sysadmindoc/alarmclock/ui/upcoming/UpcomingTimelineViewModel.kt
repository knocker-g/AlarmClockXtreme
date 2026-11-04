package com.sysadmindoc.alarmclock.ui.upcoming

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.local.ScheduleExceptionDao
import com.sysadmindoc.alarmclock.data.local.entity.ScheduleException
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.domain.evaluator.DurableOneTimeOverrideAdapter
import com.sysadmindoc.alarmclock.domain.evaluator.LocalHolidayClassifier
import com.sysadmindoc.alarmclock.domain.evaluator.OneTimeOverrideOutcome
import com.sysadmindoc.alarmclock.domain.evaluator.asHolidaySnapshotProvider
import com.sysadmindoc.alarmclock.domain.evaluator.asLocalHolidayClassifier
import com.sysadmindoc.alarmclock.domain.projection.UpcomingOccurrenceOutcome
import com.sysadmindoc.alarmclock.domain.projection.UpcomingOccurrenceReason
import com.sysadmindoc.alarmclock.domain.projection.UpcomingScheduleProjector
import com.sysadmindoc.alarmclock.domain.projection.UpcomingScheduleReconcileCoordinator
import com.sysadmindoc.alarmclock.ui.alarmlist.components.OneTimeOverrideTarget
import com.sysadmindoc.alarmclock.ui.alarmlist.components.isEligibleForOneTimeOverride
import com.sysadmindoc.alarmclock.util.AlarmTimeFormatter
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * ViewModel driving the Upcoming Timeline UI and One-Time Override operations (KNO-157 / KNO-34 Slice C).
 *
 * Implements strict presentation rules:
 * - Collects canonical projection via [UpcomingScheduleReconcileCoordinator.observeProjection].
 * - Enforces Next-only eligibility for One-Time Override (Skip Once, Change Time Once).
 * - Routes Normal ONE_TIME occurrences to canonical Alarm Edit navigation.
 * - Rejects mutation attempts on Farther-future occurrences (read-only presentation).
 * - Excludes Quick Alarms and internal Session deadlines (handled at canonical projection layer).
 * - Provides concurrency protection (atomic submission guard) against duplicate tap execution.
 */
@HiltViewModel
class UpcomingTimelineViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: UpcomingScheduleReconcileCoordinator,
    private val alarmRepository: AlarmRepository,
    private val overrideAdapter: DurableOneTimeOverrideAdapter,
    private val preferencesManager: PreferencesManager,
    private val scheduleExceptionDao: ScheduleExceptionDao,
    private val holidayRepository: HolidayRepository
) : ViewModel() {

    private val _overrideTarget = MutableStateFlow<OneTimeOverrideTarget?>(null)
    private val _userFeedback = MutableStateFlow<String?>(null)
    private val _isSubmitting = MutableStateFlow(false)
    private val isSubmittingAtomic = AtomicBoolean(false)

    internal var nowEpochMsProvider: () -> Long = { System.currentTimeMillis() }
    internal var zoneIdProvider: () -> ZoneId = { ZoneId.systemDefault() }

    internal suspend fun getHolidayClassifier(): LocalHolidayClassifier {
        val now = nowEpochMsProvider()
        val zone = zoneIdProvider()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val horizon = UpcomingScheduleProjector.DEFAULT_HORIZON_DAYS
        val endDateInclusive = today.plusDays(horizon.toLong())
        val authorityWindow = holidayRepository.captureAuthorityWindow(today, endDateInclusive)
        return authorityWindow.asLocalHolidayClassifier()
    }

    val uiState: StateFlow<UpcomingTimelineUiState> = combine(
        coordinator.observeProjection(
            nowEpochMsProvider = { nowEpochMsProvider() },
            evaluationZoneIdProvider = { zoneIdProvider() },
            holidayClassifierProvider = { getHolidayClassifier() }
        ),
        preferencesManager.settings,
        _overrideTarget,
        _isSubmitting,
        _userFeedback
    ) { projection, settings, overrideTarget, isSubmitting, feedback ->
        UpcomingTimelineUiState(
            projection = projection,
            isLoading = false,
            is24Hour = settings.is24HourFormat,
            overrideTarget = overrideTarget,
            isSubmitting = isSubmitting,
            userFeedback = feedback
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = UpcomingTimelineUiState(isLoading = true)
    )

    fun openOneTimeOverride(occurrence: UpcomingOccurrenceOutcome) {
        if (!occurrence.isOneTimeOverrideEligible || !occurrence.isNextForAlarm) {
            _userFeedback.value = context.getString(R.string.one_time_override_ineligible)
            return
        }

        if (isSubmittingAtomic.get()) return

        viewModelScope.launch {
            val alarm = alarmRepository.getById(occurrence.alarmId)
            if (alarm == null || !alarm.isEnabled) {
                _userFeedback.value = context.getString(R.string.upcoming_timeline_stale_occurrence_error)
                return@launch
            }

            // Stale occurrence validation: reject if alarm definition generation has changed
            if (alarm.generation != occurrence.generation) {
                _userFeedback.value = context.getString(R.string.upcoming_timeline_stale_occurrence_error)
                return@launch
            }

            val cachedSettings = preferencesManager.getCachedSettings()
            val isActiveSession = alarm.id == cachedSettings.activeAlarmId &&
                    (cachedSettings.activeAlarmState == "FIRING" || cachedSettings.activeAlarmState == "SNOOZED")

            if (!alarm.isEligibleForOneTimeOverride(isActiveSession)) {
                _userFeedback.value = context.getString(R.string.one_time_override_ineligible)
                return@launch
            }

            val currentEx = scheduleExceptionDao.getExceptionForOccurrence(
                alarmId = alarm.id,
                baseTriggerTime = occurrence.baseTriggerTimeEpochMs,
                generation = occurrence.generation
            )
            val baseTrigger = occurrence.baseTriggerTimeEpochMs.takeIf { it > 0L }
                ?: (currentEx?.baseTriggerTime ?: alarm.nextTriggerTime)
            val targetDate = occurrence.localDate

            val initialTime = if (occurrence.reason == UpcomingOccurrenceReason.CHANGE_TIME_ONCE) {
                occurrence.localDisplayTime
            } else {
                LocalTime.of(alarm.hour, alarm.minute)
            }

            val repeatLabel = alarm.repeatDayNames().ifBlank {
                context.getString(R.string.alarm_repeat_once)
            }

            _overrideTarget.value = OneTimeOverrideTarget(
                alarmId = alarm.id,
                expectedScheduledAt = occurrence.targetTriggerTimeEpochMs,
                expectedBaseTriggerTime = baseTrigger,
                expectedGeneration = alarm.generation,
                expectedRevision = currentEx?.revision ?: occurrence.revision,
                alarmLabel = alarm.label.ifBlank {
                    AlarmTimeFormatter.format(alarm.hour, alarm.minute, cachedSettings.is24HourFormat)
                },
                baseHour = alarm.hour,
                baseMinute = alarm.minute,
                repeatSummary = repeatLabel,
                targetDate = targetDate,
                selectedHour = initialTime.hour,
                selectedMinute = initialTime.minute,
                activeException = currentEx
            )
        }
    }

    fun updateOverrideSelectedTime(hour: Int, minute: Int) {
        _overrideTarget.update { target ->
            target?.copy(selectedHour = hour, selectedMinute = minute)
        }
    }

    fun closeOneTimeOverride() {
        _overrideTarget.value = null
    }

    fun applySkipOnce(occurrence: UpcomingOccurrenceOutcome? = null) {
        if (!isSubmittingAtomic.compareAndSet(false, true)) {
            return // Drop duplicate concurrent / rapid taps
        }
        _isSubmitting.value = true

        viewModelScope.launch {
            try {
                val effectiveAlarmId = occurrence?.alarmId ?: _overrideTarget.value?.alarmId ?: return@launch
                val effectiveScheduledAt = occurrence?.targetTriggerTimeEpochMs ?: _overrideTarget.value?.expectedScheduledAt ?: return@launch
                val effectiveBaseTrigger = occurrence?.baseTriggerTimeEpochMs ?: _overrideTarget.value?.expectedBaseTriggerTime
                val expectedGeneration = occurrence?.generation ?: _overrideTarget.value?.expectedGeneration

                // 1. Confirm operation applies only to current REPEATING next occurrence
                if (occurrence != null && (!occurrence.isNextForAlarm || !occurrence.isOneTimeOverrideEligible)) {
                    _userFeedback.value = context.getString(R.string.one_time_override_ineligible)
                    _overrideTarget.value = null
                    return@launch
                }

                // 2. Resolve latest Alarm from repository
                val alarm = alarmRepository.getById(effectiveAlarmId)
                if (alarm == null || !alarm.isEnabled) {
                    _userFeedback.value = context.getString(R.string.upcoming_timeline_stale_occurrence_error)
                    _overrideTarget.value = null
                    return@launch
                }

                // 3. Stale occurrence validation: compare generation
                if (expectedGeneration != null && alarm.generation != expectedGeneration) {
                    _userFeedback.value = context.getString(R.string.upcoming_timeline_stale_occurrence_error)
                    _overrideTarget.value = null
                    return@launch
                }

                // 4. Confirm not in active firing/snooze session
                val currentSettings = preferencesManager.getCurrentSettings()
                if (alarm.id == currentSettings.activeAlarmId &&
                    (currentSettings.activeAlarmState == "FIRING" || currentSettings.activeAlarmState == "SNOOZED")
                ) {
                    _userFeedback.value = context.getString(R.string.alarmlist_cannot_modify_active_alarm)
                    _overrideTarget.value = null
                    return@launch
                }

                _overrideTarget.update { it?.copy(isSubmitting = true) }
                val classifier = getHolidayClassifier()
                val result = overrideAdapter.executeSkipOnce(
                    alarmId = effectiveAlarmId,
                    expectedScheduledAt = effectiveScheduledAt,
                    nowEpochMs = nowEpochMsProvider(),
                    expectedBaseTriggerTimeEpochMs = effectiveBaseTrigger,
                    holidaySnapshotProvider = classifier.asHolidaySnapshotProvider(),
                    evaluationZoneId = zoneIdProvider()
                )
                when (result.outcome) {
                    OneTimeOverrideOutcome.SUCCESS_COMMITTED,
                    OneTimeOverrideOutcome.ALREADY_COMMITTED -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_badge_skipped)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.STALE_OCCURRENCE -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_stale_target)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.CONFLICT_REJECTED -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_error_conflict)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.ACTIVE_SESSION -> {
                        _userFeedback.value = context.getString(R.string.alarmlist_cannot_modify_active_alarm)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_ineligible)
                        _overrideTarget.value = null
                    }
                    else -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_failed)
                        _overrideTarget.value = null
                    }
                }
            } finally {
                isSubmittingAtomic.set(false)
                _isSubmitting.value = false
                _overrideTarget.update { it?.copy(isSubmitting = false) }
            }
        }
    }

    fun applyChangeTimeOnce(hour: Int? = null, minute: Int? = null) {
        val currentTarget = _overrideTarget.value ?: return
        if (!isSubmittingAtomic.compareAndSet(false, true)) {
            return // Drop duplicate concurrent / rapid taps
        }
        _isSubmitting.value = true

        val selHour = hour ?: currentTarget.selectedHour
        val selMinute = minute ?: currentTarget.selectedMinute

        viewModelScope.launch {
            try {
                // 1. Resolve current Alarm from repository
                val alarm = alarmRepository.getById(currentTarget.alarmId)
                if (alarm == null || !alarm.isEnabled) {
                    _userFeedback.value = context.getString(R.string.upcoming_timeline_stale_occurrence_error)
                    _overrideTarget.value = null
                    return@launch
                }

                // 2. Stale generation check
                if (alarm.generation != currentTarget.expectedGeneration) {
                    _userFeedback.value = context.getString(R.string.upcoming_timeline_stale_occurrence_error)
                    _overrideTarget.value = null
                    return@launch
                }

                // 3. Confirm active session
                val currentSettings = preferencesManager.getCurrentSettings()
                if (alarm.id == currentSettings.activeAlarmId &&
                    (currentSettings.activeAlarmState == "FIRING" || currentSettings.activeAlarmState == "SNOOZED")
                ) {
                    _userFeedback.value = context.getString(R.string.alarmlist_cannot_modify_active_alarm)
                    _overrideTarget.value = null
                    return@launch
                }

                _overrideTarget.update { it?.copy(isSubmitting = true) }
                val newTime = LocalTime.of(selHour, selMinute)
                val classifier = getHolidayClassifier()
                val result = overrideAdapter.executeChangeTimeOnce(
                    alarmId = currentTarget.alarmId,
                    expectedScheduledAt = currentTarget.expectedScheduledAt,
                    newTime = newTime,
                    nowEpochMs = nowEpochMsProvider(),
                    expectedBaseTriggerTimeEpochMs = currentTarget.expectedBaseTriggerTime,
                    holidaySnapshotProvider = classifier.asHolidaySnapshotProvider(),
                    evaluationZoneId = zoneIdProvider()
                )
                when (result.outcome) {
                    OneTimeOverrideOutcome.SUCCESS_COMMITTED -> {
                        val formatted = AlarmTimeFormatter.format(
                            selHour,
                            selMinute,
                            preferencesManager.getCachedSettings().is24HourFormat
                        )
                        _userFeedback.value = context.getString(R.string.one_time_override_success_time, formatted)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.STALE_OCCURRENCE -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_stale_target)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.CONFLICT_REJECTED -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_error_conflict)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.INVALID_TARGET_TIME -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_error_past_time)
                        _overrideTarget.update { it?.copy(isSubmitting = false) }
                    }
                    OneTimeOverrideOutcome.ACTIVE_SESSION -> {
                        _userFeedback.value = context.getString(R.string.alarmlist_cannot_modify_active_alarm)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.UNSUPPORTED_SCHEDULE -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_ineligible)
                        _overrideTarget.value = null
                    }
                    else -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_failed)
                        _overrideTarget.value = null
                    }
                }
            } finally {
                isSubmittingAtomic.set(false)
                _isSubmitting.value = false
            }
        }
    }

    fun cancelOneTimeOverride(occurrence: UpcomingOccurrenceOutcome? = null) {
        val alarmId = occurrence?.alarmId ?: _overrideTarget.value?.alarmId ?: return
        val expectedScheduledAt = occurrence?.targetTriggerTimeEpochMs ?: _overrideTarget.value?.expectedScheduledAt ?: return
        val expectedBaseTriggerTime = occurrence?.baseTriggerTimeEpochMs ?: _overrideTarget.value?.expectedBaseTriggerTime
        val expectedGeneration = occurrence?.generation ?: _overrideTarget.value?.expectedGeneration

        if (!isSubmittingAtomic.compareAndSet(false, true)) {
            return // Drop duplicate concurrent / rapid taps
        }
        _isSubmitting.value = true

        viewModelScope.launch {
            try {
                // 1. Resolve current Alarm from repository
                val alarm = alarmRepository.getById(alarmId)
                if (alarm == null || !alarm.isEnabled) {
                    _userFeedback.value = context.getString(R.string.upcoming_timeline_stale_occurrence_error)
                    _overrideTarget.value = null
                    return@launch
                }

                // 2. Stale generation check
                if (expectedGeneration != null && alarm.generation != expectedGeneration) {
                    _userFeedback.value = context.getString(R.string.upcoming_timeline_stale_occurrence_error)
                    _overrideTarget.value = null
                    return@launch
                }

                _overrideTarget.update { it?.copy(isSubmitting = true) }
                val classifier = getHolidayClassifier()
                val result = overrideAdapter.cancelOverride(
                    alarmId = alarmId,
                    expectedScheduledAt = expectedScheduledAt,
                    nowEpochMs = nowEpochMsProvider(),
                    expectedBaseTriggerTimeEpochMs = expectedBaseTriggerTime,
                    holidaySnapshotProvider = classifier.asHolidaySnapshotProvider(),
                    evaluationZoneId = zoneIdProvider()
                )
                when (result.outcome) {
                    OneTimeOverrideOutcome.SUCCESS_COMMITTED -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_success_cancel)
                        _overrideTarget.value = null
                    }
                    OneTimeOverrideOutcome.STALE_OCCURRENCE -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_stale_target)
                        _overrideTarget.value = null
                    }
                    else -> {
                        _userFeedback.value = context.getString(R.string.one_time_override_failed)
                        _overrideTarget.value = null
                    }
                }
            } finally {
                isSubmittingAtomic.set(false)
                _isSubmitting.value = false
                _overrideTarget.update { it?.copy(isSubmitting = false) }
            }
        }
    }

    fun clearFeedback() {
        _userFeedback.value = null
    }
}
