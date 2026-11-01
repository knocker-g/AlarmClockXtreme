package com.sysadmindoc.alarmclock.ui.holiday

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmindoc.alarmclock.R
import com.sysadmindoc.alarmclock.data.holiday.ManualHolidayOverrideStore
import com.sysadmindoc.alarmclock.data.holiday.RangeMutationResult
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.repository.HolidayClassificationKind
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileCoordinator
import com.sysadmindoc.alarmclock.data.repository.HolidayReconcileResult
import com.sysadmindoc.alarmclock.data.repository.HolidayRepository
import com.sysadmindoc.alarmclock.data.repository.ManualHolidayOverride
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject
import javax.inject.Provider

enum class HolidayFeedbackKind {
    RECONCILE_SUCCESS,
    RECONCILE_PENDING,
    SAVE_FAILED,
    RESET_SUCCESS,
    RESET_FAILED
}

data class HolidayDayUiModel(
    val date: LocalDate,
    val isCurrentMonth: Boolean,
    val isToday: Boolean,
    val isProviderHoliday: Boolean,
    val manualOverride: ManualHolidayOverride,
    val resolvedClassification: HolidayClassificationKind,
    val isHoliday: Boolean
)

data class RangeConflictDialogState(
    val startDate: LocalDate,
    val endDateInclusive: LocalDate,
    val override: ManualHolidayOverride,
    val conflictingDates: List<LocalDate>
)

data class HolidayCalendarUiState(
    val displayedMonth: YearMonth = YearMonth.now(),
    val countryCode: String = "",
    val days: List<HolidayDayUiModel> = emptyList(),
    val isCorrupted: Boolean = false,
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val statusMessage: String? = null,
    val lastFeedbackKind: HolidayFeedbackKind? = null,
    val pendingRangeConflict: RangeConflictDialogState? = null,
    val showResetConfirmationDialog: Boolean = false
)

@HiltViewModel
class HolidayCalendarViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val holidayRepository: HolidayRepository,
    private val manualOverrideStore: ManualHolidayOverrideStore,
    private val preferencesManager: PreferencesManager,
    private val holidayReconcileCoordinatorProvider: Provider<HolidayReconcileCoordinator>
) : ViewModel() {

    companion object {
        const val KEY_DISPLAYED_MONTH = "holiday_displayed_month"
    }

    private val _uiState: MutableStateFlow<HolidayCalendarUiState>

    init {
        val savedMonth = savedStateHandle.get<String>(KEY_DISPLAYED_MONTH)?.let {
            try { YearMonth.parse(it) } catch (_: Exception) { null }
        } ?: YearMonth.now()
        _uiState = MutableStateFlow(HolidayCalendarUiState(displayedMonth = savedMonth))
        loadMonth(savedMonth)
    }

    val uiState: StateFlow<HolidayCalendarUiState> = _uiState.asStateFlow()

    fun previousMonth() {
        val prev = _uiState.value.displayedMonth.minusMonths(1)
        loadMonth(prev)
    }

    fun nextMonth() {
        val next = _uiState.value.displayedMonth.plusMonths(1)
        loadMonth(next)
    }

    fun setMonth(yearMonth: YearMonth) {
        loadMonth(yearMonth)
    }

    fun refresh() {
        loadMonth(_uiState.value.displayedMonth)
    }

    fun loadMonth(month: YearMonth) {
        savedStateHandle[KEY_DISPLAYED_MONTH] = month.toString()
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, displayedMonth = month) }
            try {
                val start = month.atDay(1)
                val end = month.atEndOfMonth()
                val authority = holidayRepository.captureAuthorityWindow(start, end)
                val settings = preferencesManager.getCurrentSettings()
                val today = LocalDate.now()
                val corrupted = manualOverrideStore.isCorrupted

                val days = (1..month.lengthOfMonth()).map { dayNum ->
                    val date = month.atDay(dayNum)
                    val classification = authority.classify(date)
                    val manual = authority.manualOverrides[date] ?: ManualHolidayOverride.AUTO
                    val isProvider = authority.providerSnapshot?.years?.get(date.year)?.dates?.contains(date) == true
                    HolidayDayUiModel(
                        date = date,
                        isCurrentMonth = true,
                        isToday = date == today,
                        isProviderHoliday = isProvider,
                        manualOverride = manual,
                        resolvedClassification = classification.kind,
                        isHoliday = classification.kind == HolidayClassificationKind.HOLIDAY
                    )
                }

                _uiState.update {
                    it.copy(
                        displayedMonth = month,
                        countryCode = settings.holidayCountryCode,
                        days = days,
                        isCorrupted = corrupted,
                        isLoading = false
                    )
                }
            } catch (_: Throwable) {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun setDateOverride(date: LocalDate, override: ManualHolidayOverride) {
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                val reconcileResult = if (override == ManualHolidayOverride.AUTO) {
                    manualOverrideStore.clearOverride(date)
                } else {
                    manualOverrideStore.setOverride(date, override)
                }
                val (msg, kind) = when (reconcileResult) {
                    is HolidayReconcileResult.Success -> {
                        context.getString(R.string.holiday_calendar_reconcile_success) to HolidayFeedbackKind.RECONCILE_SUCCESS
                    }
                    is HolidayReconcileResult.PendingRetry -> {
                        context.getString(R.string.holiday_calendar_reconcile_pending) to HolidayFeedbackKind.RECONCILE_PENDING
                    }
                    else -> {
                        context.getString(R.string.holiday_calendar_reconcile_success) to HolidayFeedbackKind.RECONCILE_SUCCESS
                    }
                }
                _uiState.update { it.copy(statusMessage = msg, lastFeedbackKind = kind) }
                loadMonth(_uiState.value.displayedMonth)
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        statusMessage = context.getString(R.string.holiday_calendar_save_failed),
                        lastFeedbackKind = HolidayFeedbackKind.SAVE_FAILED
                    )
                }
            } finally {
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun forceHoliday(date: LocalDate) = setDateOverride(date, ManualHolidayOverride.FORCE_HOLIDAY)
    fun forceWorkday(date: LocalDate) = setDateOverride(date, ManualHolidayOverride.FORCE_WORKDAY)
    fun resetToAuto(date: LocalDate) = setDateOverride(date, ManualHolidayOverride.AUTO)
    fun clearOverride(date: LocalDate) = resetToAuto(date)

    fun applyRange(
        startDate: LocalDate,
        endDateInclusive: LocalDate,
        override: ManualHolidayOverride,
        overwriteExisting: Boolean = false
    ) {
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                val result = manualOverrideStore.setRange(
                    startDate = startDate,
                    endDateInclusive = endDateInclusive,
                    override = override,
                    overwriteExisting = overwriteExisting
                )
                when (result) {
                    is RangeMutationResult.Conflict -> {
                        _uiState.update {
                            it.copy(
                                pendingRangeConflict = RangeConflictDialogState(
                                    startDate = startDate,
                                    endDateInclusive = endDateInclusive,
                                    override = override,
                                    conflictingDates = result.conflictingDates
                                )
                            )
                        }
                    }
                    is RangeMutationResult.Applied -> {
                        val (msg, kind) = when (result.reconcileResult) {
                            is HolidayReconcileResult.Success -> {
                                context.getString(R.string.holiday_calendar_reconcile_success) to HolidayFeedbackKind.RECONCILE_SUCCESS
                            }
                            is HolidayReconcileResult.PendingRetry -> {
                                context.getString(R.string.holiday_calendar_reconcile_pending) to HolidayFeedbackKind.RECONCILE_PENDING
                            }
                            else -> {
                                context.getString(R.string.holiday_calendar_reconcile_success) to HolidayFeedbackKind.RECONCILE_SUCCESS
                            }
                        }
                        _uiState.update {
                            it.copy(
                                statusMessage = msg,
                                lastFeedbackKind = kind,
                                pendingRangeConflict = null
                            )
                        }
                        loadMonth(_uiState.value.displayedMonth)
                    }
                    RangeMutationResult.NoOp -> {
                        _uiState.update { it.copy(pendingRangeConflict = null) }
                        loadMonth(_uiState.value.displayedMonth)
                    }
                }
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        statusMessage = context.getString(R.string.holiday_calendar_save_failed),
                        lastFeedbackKind = HolidayFeedbackKind.SAVE_FAILED
                    )
                }
            } finally {
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun confirmConflictOverwrite() {
        val conflict = _uiState.value.pendingRangeConflict ?: return
        applyRange(
            startDate = conflict.startDate,
            endDateInclusive = conflict.endDateInclusive,
            override = conflict.override,
            overwriteExisting = true
        )
    }

    fun dismissConflictDialog() {
        _uiState.update { it.copy(pendingRangeConflict = null) }
    }

    fun openResetConfirmation() {
        _uiState.update { it.copy(showResetConfirmationDialog = true) }
    }

    fun dismissResetConfirmation() {
        _uiState.update { it.copy(showResetConfirmationDialog = false) }
    }

    fun confirmReset() {
        _uiState.update { it.copy(showResetConfirmationDialog = false) }
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true) }
        viewModelScope.launch {
            try {
                manualOverrideStore.recoverFromCorruption(clearCorruptedFile = true)
                val coordinator = holidayReconcileCoordinatorProvider.get()
                coordinator.reconcile(
                    isMaterialChange = true,
                    previousClassifier = null,
                    evaluationEpochMs = System.currentTimeMillis()
                )
                _uiState.update {
                    it.copy(
                        statusMessage = context.getString(R.string.holiday_calendar_reset_success),
                        lastFeedbackKind = HolidayFeedbackKind.RESET_SUCCESS,
                        isCorrupted = false
                    )
                }
                loadMonth(_uiState.value.displayedMonth)
            } catch (_: Throwable) {
                _uiState.update {
                    it.copy(
                        statusMessage = context.getString(R.string.holiday_calendar_reset_failed),
                        lastFeedbackKind = HolidayFeedbackKind.RESET_FAILED
                    )
                }
            } finally {
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }

    fun clearStatusMessage() {
        _uiState.update { it.copy(statusMessage = null) }
    }
}
