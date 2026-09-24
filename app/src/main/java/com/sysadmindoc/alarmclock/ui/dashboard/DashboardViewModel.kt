package com.sysadmindoc.alarmclock.ui.dashboard

import com.sysadmindoc.alarmclock.R
import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.data.remote.AirQualityResponse
import com.sysadmindoc.alarmclock.data.remote.CurrentAirQuality
import com.sysadmindoc.alarmclock.data.remote.CurrentWeather
import com.sysadmindoc.alarmclock.data.remote.DailyWeather
import com.sysadmindoc.alarmclock.data.remote.GeocodingApi
import com.sysadmindoc.alarmclock.data.remote.GeocodingResult
import com.sysadmindoc.alarmclock.data.remote.HourlyWeather
import com.sysadmindoc.alarmclock.data.remote.WeatherCodes
import com.sysadmindoc.alarmclock.data.repository.CalendarEvent
import com.sysadmindoc.alarmclock.data.repository.CalendarRepository
import com.sysadmindoc.alarmclock.data.repository.AlarmRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherAlertsRepository
import com.sysadmindoc.alarmclock.data.repository.WeatherRepository
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import com.sysadmindoc.alarmclock.ui.alarmlist.repeatLabel
import com.sysadmindoc.alarmclock.util.AlarmTimeFormatter
import com.sysadmindoc.alarmclock.util.LocationHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import javax.inject.Inject

data class DashboardUiState(
    val todayDate: String = "",
    val nextAlarmTime: String = "",
    val nextAlarmLabel: String = "",
    val nextAlarmSchedule: String = "",
    val showWeather: Boolean = true,
    val showCalendar: Boolean = true,
    val is24HourFormat: Boolean = false,
    // v1.8.0: Windy radar embed toggle (lifted from settings) + lat/lon so
    // the WebView centers the radar over the user's actual weather location.
    val showRadar: Boolean = true,
    val latitude: Double? = null,
    val longitude: Double? = null,
    // v1.9.0: dynamic sky theming. The sunrise/sunset strings above are
    // already formatted for display; we keep their parsed `LocalTime` form
    // here for the keyframe interpolator. `currentWeatherCode` drives
    // storm overrides; `tornadoAlertActive` drives the tornado visual.
    val sunriseLocal: LocalTime? = null,
    val sunsetLocal: LocalTime? = null,
    val currentWeatherCode: Int? = null,
    val tornadoAlertActive: Boolean = false,
    val severeWeatherHeadline: String? = null,
    // Weather
    val weatherLoading: Boolean = false,
    val refreshing: Boolean = false,
    val temperature: String = "",
    val feelsLike: String = "",
    val humidity: String = "",
    val windSpeed: String = "",
    val weatherDescription: String = "",
    val weatherIcon: String = "",
    val highTemp: String = "",
    val lowTemp: String = "",
    val precipChance: String = "",
    val weatherError: String? = null,
    val hasLocation: Boolean = false,
    val locationName: String = "",
    val tempUnit: String = "F", // "F" or "C"
    val windUnit: String = "mph", // "mph" or "km/h"
    // v1.7.4: ZeusWatch-inspired additions
    val sunrise: String = "",          // "6:24 AM"
    val sunset: String = "",           // "8:11 PM"
    val uvIndex: String = "",          // "6 (high)"
    val hourly: List<HourlyForecast> = emptyList(),
    // v1.10.1: Open-Meteo air-quality companion card for AQI, pollutants, and pollen.
    val airQuality: AirQualitySummary? = null,
    // Calendar
    val calendarEvents: List<CalendarEvent> = emptyList(),
    val calendarError: String? = null,
    val calendarPermissionNeeded: Boolean = false,
    // Forecast
    val forecast: List<ForecastDay> = emptyList(),
    val weatherLastUpdatedMillis: Long? = null,
    val weatherStale: Boolean = false,
    val weatherStaleMessage: String? = null,
    // Location search
    val showLocationPicker: Boolean = false,
    val locationSearchResults: List<GeocodingResult> = emptyList(),
    val locationSearching: Boolean = false,
    // ALA-107: Preferred schedule app package
    val scheduleAppPackage: String = ""
)

data class ForecastDay(
    val date: String,
    val dayName: String,
    val high: String,
    val low: String,
    val description: String,
    val precipChance: String,
    val icon: String = ""
)

/** A single hourly forecast cell — ported lightly from ZeusWatch's HourlyForecastStrip. */
data class HourlyForecast(
    val timeLabel: String,    // "Now", "7 AM", "8 AM"
    val temperature: String,  // "68"
    val icon: String,         // WMO icon key
    val precipChance: String, // "30%" — empty when 0
)

data class AirQualitySummary(
    val aqi: String,
    val band: String,
    val detail: String,
    val level: AirQualityLevel,
    val pollutantMetrics: List<AirQualityMetric>,
    val pollenRows: List<PollenMetric>,
    val hasPollenData: Boolean
)

data class AirQualityMetric(
    val label: String,
    val value: String
)

data class PollenMetric(
    val label: String,
    val value: String,
    val band: String,
    val level: PollenLevel
)

enum class AirQualityLevel {
    GOOD,
    MODERATE,
    SENSITIVE,
    UNHEALTHY,
    VERY_UNHEALTHY,
    HAZARDOUS,
    UNKNOWN
}

enum class PollenLevel {
    NONE,
    LOW,
    MODERATE,
    HIGH,
    VERY_HIGH,
    UNAVAILABLE
}

@HiltViewModel
class DashboardViewModel @Inject constructor(
    application: Application,
    private val weatherRepository: WeatherRepository,
    private val weatherAlertsRepository: WeatherAlertsRepository,
    private val calendarRepository: CalendarRepository,
    private val alarmRepository: AlarmRepository,
    private val preferencesManager: PreferencesManager,
    private val alarmScheduler: AlarmScheduler,
    private val geocodingApi: GeocodingApi
) : AndroidViewModel(application) {
    /** For strings that end up in UI state built here rather than on screen. */
    private val appContext: Context get() = getApplication()


    companion object {
        private const val SOLAR_RESCHEDULE_LOCATION_DELTA = 0.1
    }

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        val today = LocalDate.now()
        _uiState.update { it.copy(
            todayDate = today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d"))
        ) }
        viewModelScope.launch {
            combine(
                alarmRepository.observeNextAlarm(),
                preferencesManager.settings
            ) { alarm, settings ->
                if (alarm == null) {
                    Triple("", "", "")
                } else {
                    Triple(
                        alarm.time.format(AlarmTimeFormatter.formatter(settings.is24HourFormat)),
                        alarm.label.ifBlank {
                            getApplication<Application>().getString(R.string.notif_alarm_title)
                        },
                        alarm.repeatLabel(getApplication<Application>())
                    )
                }
            }.collect { (time, label, schedule) ->
                _uiState.update {
                    it.copy(
                        nextAlarmTime = time,
                        nextAlarmLabel = label,
                        nextAlarmSchedule = schedule
                    )
                }
            }
        }
        loadData()
    }

    /**
     * Coordinated data fetch for the Today tab.
     * @param isManualRefresh When true, uses the [refreshing] state for Pull-to-Refresh
     * coordination and waits for all sub-jobs (Weather, AQI, Schedule) to complete.
     */
    fun loadData(isManualRefresh: Boolean = false) {
        viewModelScope.launch {
            if (isManualRefresh) {
                _uiState.update { it.copy(refreshing = true) }
            }

            val settings = preferencesManager.getCurrentSettings()
            _uiState.update { it.copy(
                showWeather = settings.showWeatherOnDashboard,
                showCalendar = settings.showCalendarOnDashboard,
                is24HourFormat = settings.is24HourFormat,
                showRadar = settings.showRadarEmbed,
                scheduleAppPackage = settings.scheduleAppPackage
            ) }

            coroutineScope {
                if (settings.showWeatherOnDashboard) {
                    launch { performWeatherLoad(isManualRefresh) }
                } else {
                    _uiState.update { it.copy(
                        weatherLoading = false,
                        weatherError = null,
                        forecast = emptyList(),
                        weatherLastUpdatedMillis = null,
                        weatherStale = false,
                        weatherStaleMessage = null,
                        airQuality = null
                    ) }
                }

                if (settings.showCalendarOnDashboard) {
                    launch { performCalendarLoad(isManualRefresh) }
                } else {
                    _uiState.update { it.copy(
                        calendarEvents = emptyList(),
                        calendarError = null,
                        calendarPermissionNeeded = false
                    ) }
                }
            }

            if (isManualRefresh) {
                _uiState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun loadWeather(isManualRefresh: Boolean = false) {
        viewModelScope.launch {
            performWeatherLoad(isManualRefresh)
        }
    }

    private suspend fun performWeatherLoad(isManualRefresh: Boolean) {
        if (!isManualRefresh) {
            _uiState.update { it.copy(weatherLoading = true, weatherError = null) }
        }

        val settings = preferencesManager.getCurrentSettings()
        val isCelsius = settings.temperatureUnit == "celsius"
        val tempUnitLabel = if (isCelsius) "C" else "F"
        val windUnitLabel = if (isCelsius) "km/h" else "mph"
        val apiTempUnit = if (isCelsius) "celsius" else "fahrenheit"
        val apiWindUnit = if (isCelsius) "kmh" else "mph"

        // Check for manual location first
        val lat: Double
        val lon: Double
        val locName: String

        if (settings.useManualLocation && settings.locationName.isNotBlank()) {
            lat = settings.lastKnownLatitude
            lon = settings.lastKnownLongitude
            locName = settings.locationName
        } else {
            // Try GPS
            val context = getApplication<Application>()
            val location = LocationHelper.getLastKnownLocation(context)

            if (location == null &&
                settings.lastKnownLatitude == 0.0 &&
                settings.lastKnownLongitude == 0.0
            ) {
                val usableData = _uiState.value.temperature.isNotBlank()
                val preserve = isManualRefresh && usableData
                _uiState.update { state ->
                    state.copy(
                        weatherLoading = false,
                        hasLocation = if (preserve) state.hasLocation else false,
                        locationName = if (preserve) state.locationName else "",
                        temperature = if (preserve) state.temperature else "",
                        feelsLike = if (preserve) state.feelsLike else "",
                        humidity = if (preserve) state.humidity else "",
                        windSpeed = if (preserve) state.windSpeed else "",
                        weatherDescription = if (preserve) state.weatherDescription else "",
                        weatherIcon = if (preserve) state.weatherIcon else "",
                        highTemp = if (preserve) state.highTemp else "",
                        lowTemp = if (preserve) state.lowTemp else "",
                        precipChance = if (preserve) state.precipChance else "",
                        forecast = if (preserve) state.forecast else emptyList(),
                        weatherLastUpdatedMillis = if (preserve) state.weatherLastUpdatedMillis else null,
                        weatherStale = preserve,
                        weatherStaleMessage = if (preserve) appContext.getString(R.string.dashboard_weather_refresh_failed) else null,
                        airQuality = if (preserve) state.airQuality else null,
                        weatherError = if (!preserve) appContext.getString(R.string.dashboard_weather_set_location) else null
                    )
                }
                return
            }
            if (location != null) {
                lat = location.latitude
                lon = location.longitude
                locName = appContext.getString(R.string.dashboard_weather_current_location)
            } else {
                lat = settings.lastKnownLatitude
                lon = settings.lastKnownLongitude
                locName = settings.locationName.ifBlank {
                    getApplication<Application>().getString(R.string.dashboard_last_location)
                }
            }

            val shouldRescheduleSolarAlarms = shouldRescheduleSolarAlarms(
                previous = settings,
                newLatitude = lat,
                newLongitude = lon,
                newLocationName = "",
                useManualLocation = false
            )
            preferencesManager.update {
                it.copy(lastKnownLatitude = lat, lastKnownLongitude = lon)
            }
            if (shouldRescheduleSolarAlarms) {
                alarmScheduler.rescheduleAll(forceRecalculate = true)
            }
        }

        val weatherResult = weatherRepository.getWeather(lat, lon, apiTempUnit, apiWindUnit)
        if (weatherResult.isSuccess) {
            val snapshot = weatherResult.getOrThrow()
            val response = snapshot.response
            val current = response.current
            val daily = response.daily
            val hourly = response.hourly

                _uiState.update { it.copy(
                    weatherLoading = false,
                    hasLocation = true,
                    locationName = locName,
                    latitude = lat,
                    longitude = lon,
                    tempUnit = tempUnitLabel,
                    windUnit = windUnitLabel,
                    temperature = current?.temperature?.let { "${it.toInt()}" } ?: "--",
                    feelsLike = current?.feelsLike?.let { appContext.getString(R.string.weather_feels_like, it.toInt().toString()) } ?: "",
                    humidity = current?.humidity?.let { "${it}%" } ?: "",
                    windSpeed = current?.windSpeed?.let { "${it.toInt()} $windUnitLabel" } ?: "",
                    weatherDescription = current?.weatherCode
                        ?.let { appContext.getString(WeatherCodes.describeRes(it)) }
                        ?: "",
                    weatherIcon = current?.weatherCode?.let { WeatherCodes.icon(it) } ?: "unknown",
                    currentWeatherCode = current?.weatherCode,
                    highTemp = daily?.maxTemp?.firstOrNull()?.let { "${it.toInt()}" } ?: "--",
                    lowTemp = daily?.minTemp?.firstOrNull()?.let { "${it.toInt()}" } ?: "--",
                    precipChance = daily?.precipChance?.firstOrNull()?.let { "${it}%" } ?: "",
                    sunrise = formatTimeOfDay(daily?.sunrise?.firstOrNull()),
                    sunset = formatTimeOfDay(daily?.sunset?.firstOrNull()),
                    sunriseLocal = parseTimeOfDay(daily?.sunrise?.firstOrNull()),
                    sunsetLocal = parseTimeOfDay(daily?.sunset?.firstOrNull()),
                    uvIndex = formatUv(current?.uvIndex ?: daily?.uvIndexMax?.firstOrNull()),
                    hourly = buildHourly(hourly),
                    airQuality = it.airQuality,
                    forecast = buildForecast(daily),
                    weatherLastUpdatedMillis = snapshot.fetchedAtMillis,
                    weatherStale = snapshot.isStale,
                    weatherStaleMessage = if (snapshot.isStale) {
                        appContext.getString(R.string.dashboard_weather_refresh_failed)
                    } else {
                        null
                    },
                    weatherError = null
                ) }

            coroutineScope {
                // Fetch NWS alerts.
                launch {
                    val flags = weatherAlertsRepository.fetch(lat, lon)
                    if (_uiState.value.latitude == lat && _uiState.value.longitude == lon) {
                        _uiState.update { it.copy(
                            tornadoAlertActive = flags.tornadoActive,
                            severeWeatherHeadline = flags.headline,
                        ) }
                    }
                }

                // Air quality companion card for AQI, pollutants, and pollen.
                launch {
                    val airQualityResult = weatherRepository.getAirQuality(lat, lon)
                    if (airQualityResult.isSuccess) {
                        val airQuality = airQualityResult.getOrNull()?.let(::buildAirQualitySummary)
                        if (_uiState.value.latitude == lat && _uiState.value.longitude == lon) {
                            _uiState.update { it.copy(airQuality = airQuality) }
                        }
                    }
                }
            }
        } else {
            val usableData = _uiState.value.temperature.isNotBlank()
            val preserve = isManualRefresh && usableData
            _uiState.update { state ->
                state.copy(
                    weatherLoading = false,
                    hasLocation = if (preserve) state.hasLocation else (locName.isNotBlank()),
                    locationName = if (preserve) state.locationName else locName,
                    temperature = if (preserve) state.temperature else "",
                    feelsLike = if (preserve) state.feelsLike else "",
                    humidity = if (preserve) state.humidity else "",
                    windSpeed = if (preserve) state.windSpeed else "",
                    weatherDescription = if (preserve) state.weatherDescription else "",
                    weatherIcon = if (preserve) state.weatherIcon else "",
                    highTemp = if (preserve) state.highTemp else "",
                    lowTemp = if (preserve) state.lowTemp else "",
                    precipChance = if (preserve) state.precipChance else "",
                    sunrise = if (preserve) state.sunrise else "",
                    sunset = if (preserve) state.sunset else "",
                    uvIndex = if (preserve) state.uvIndex else "",
                    hourly = if (preserve) state.hourly else emptyList(),
                    forecast = if (preserve) state.forecast else emptyList(),
                    weatherLastUpdatedMillis = if (preserve) state.weatherLastUpdatedMillis else null,
                    weatherStale = preserve,
                    weatherStaleMessage = if (preserve) appContext.getString(R.string.dashboard_weather_refresh_failed) else null,
                    airQuality = if (preserve) state.airQuality else null,
                    weatherError = if (!preserve) appContext.getString(R.string.dashboard_weather_unavailable) else null
                )
            }
        }
    }

    fun showLocationPicker() {
        _uiState.update { it.copy(showLocationPicker = true, locationSearchResults = emptyList()) }
    }

    fun hideLocationPicker() {
        _uiState.update { it.copy(showLocationPicker = false, locationSearchResults = emptyList()) }
    }

    private var searchJob: Job? = null

    fun searchLocation(query: String) {
        if (query.length < 2) {
            searchJob?.cancel()
            _uiState.update { it.copy(locationSearchResults = emptyList(), locationSearching = false) }
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _uiState.update { it.copy(locationSearching = true) }
            delay(300) // Debounce 300ms
            try {
                val response = geocodingApi.search(query)
                _uiState.update { it.copy(
                    locationSearchResults = response.results ?: emptyList(),
                    locationSearching = false
                ) }
            } catch (e: Exception) {
                _uiState.update { it.copy(
                    locationSearchResults = emptyList(),
                    locationSearching = false
                ) }
            }
        }
    }

    fun selectLocation(result: GeocodingResult) {
        viewModelScope.launch {
            val lat = result.latitude ?: return@launch
            val lon = result.longitude ?: return@launch
            val settings = preferencesManager.getCurrentSettings()
            val displayName = result.displayName(
                appContext.getString(R.string.geocoding_unknown_place)
            )
            val shouldRescheduleSolarAlarms = shouldRescheduleSolarAlarms(
                previous = settings,
                newLatitude = lat,
                newLongitude = lon,
                newLocationName = displayName,
                useManualLocation = true
            )
            preferencesManager.update {
                it.copy(
                    lastKnownLatitude = lat,
                    lastKnownLongitude = lon,
                    locationName = displayName,
                    useManualLocation = true
                )
            }
            if (shouldRescheduleSolarAlarms) {
                alarmScheduler.rescheduleAll(forceRecalculate = true)
            }
            _uiState.update { it.copy(
                showLocationPicker = false,
                locationSearchResults = emptyList()
            ) }
            loadWeather()
        }
    }

    fun useDeviceLocation() {
        viewModelScope.launch {
            preferencesManager.update {
                it.copy(useManualLocation = false, locationName = "")
            }
            _uiState.update { it.copy(
                showLocationPicker = false,
                locationSearchResults = emptyList()
            ) }
            loadWeather()
        }
    }

    fun loadCalendar() {
        viewModelScope.launch {
            performCalendarLoad(false)
        }
    }

    private suspend fun performCalendarLoad(isManualRefresh: Boolean) {
        withContext(Dispatchers.IO) {
            val result = calendarRepository.getTodayEvents()
            result.onSuccess { events ->
                _uiState.update { it.copy(
                    calendarEvents = events,
                    calendarError = null,
                    calendarPermissionNeeded = false
                ) }
            }.onFailure { e ->
                val usableData = _uiState.value.calendarEvents.isNotEmpty()
                val preserve = isManualRefresh && usableData

                if (e is SecurityException) {
                    _uiState.update { it.copy(
                        calendarEvents = if (preserve) it.calendarEvents else emptyList(),
                        calendarPermissionNeeded = true,
                        calendarError = "Calendar permission needed"
                    ) }
                } else {
                    _uiState.update { it.copy(
                        calendarEvents = if (preserve) it.calendarEvents else emptyList(),
                        calendarError = "Unable to load calendar"
                    ) }
                }
            }
        }
    }

    private fun buildForecast(daily: DailyWeather?): List<ForecastDay> {
        if (daily == null) return emptyList()
        val dates = daily.time ?: return emptyList()

        // Tolerate malformed entries: a single bad date string from the upstream
        // weather API should not crash the entire dashboard, so each row is
        // built independently and skipped on parse failure.
        return dates.mapIndexedNotNull { i, dateStr ->
            val date = runCatching { LocalDate.parse(dateStr) }.getOrNull() ?: return@mapIndexedNotNull null
            val code = daily.weatherCode?.getOrNull(i)
            ForecastDay(
                date = dateStr,
                dayName = if (i == 0) appContext.getString(R.string.dashboard_today) else date.format(DateTimeFormatter.ofPattern("EEEE")),
                high = daily.maxTemp?.getOrNull(i)?.let { "${it.toInt()}" } ?: "--",
                low = daily.minTemp?.getOrNull(i)?.let { "${it.toInt()}" } ?: "--",
                description = code
                    ?.let { appContext.getString(WeatherCodes.describeRes(it)) }
                    ?: "",
                precipChance = daily.precipChance?.getOrNull(i)?.let { "${it}%" } ?: "",
                icon = code?.let { WeatherCodes.icon(it) } ?: "unknown"
            )
        }
    }

    /**
     * Build the next-12-hours strip — first cell is "Now", subsequent cells
     * label by hour. Skips past-now entries and tolerates partial / malformed
     * arrays (the upstream Open-Meteo response sometimes lags by an hour at
     * timezone boundaries).
     */
    private fun buildHourly(hourly: HourlyWeather?): List<HourlyForecast> {
        if (hourly == null) return emptyList()
        val times = hourly.time ?: return emptyList()
        val now = LocalDateTime.now()
        // v1.7.5: only the FIRST cell ever gets the "Now" label. The previous
        // implementation tagged every cell within 45 minutes of `now`, which
        // produced "Now / Now / 7 PM / …" when the response straddled the
        // top of the hour.
        var firstNowAssigned = false
        return times.mapIndexedNotNull { i, timeStr ->
            val parsed = runCatching { LocalDateTime.parse(timeStr) }.getOrNull()
                ?: return@mapIndexedNotNull null
            if (parsed.isBefore(now.minusMinutes(30))) return@mapIndexedNotNull null
            val temp = hourly.temperature?.getOrNull(i)?.let { "${it.toInt()}" } ?: "--"
            val code = hourly.weatherCode?.getOrNull(i) ?: -1
            val pop = hourly.precipChance?.getOrNull(i) ?: 0
            val labelIsNow = !firstNowAssigned
            firstNowAssigned = true
            HourlyForecast(
                timeLabel = if (labelIsNow) appContext.getString(R.string.dashboard_now)
                    else parsed.format(DateTimeFormatter.ofPattern("h a")),
                temperature = temp,
                icon = WeatherCodes.icon(code),
                precipChance = if (pop >= 20) "${pop}%" else "",
            )
        }.take(8)
    }

    /**
     * Open-Meteo returns sunrise/sunset as ISO local-without-timezone strings
     * like "2026-04-29T06:24" (we requested timezone=auto so they're in the
     * user's location TZ already). Format to a friendly "6:24 AM".
     */
    private fun formatTimeOfDay(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        val parsed = runCatching { LocalDateTime.parse(iso) }.getOrNull() ?: return ""
        return parsed.format(
            AlarmTimeFormatter.formatter(
                preferencesManager.getCachedSettings().is24HourFormat
            )
        )
    }

    /**
     * Same source ISO as [formatTimeOfDay] but returns the parsed `LocalTime`
     * for the keyframe sky engine. Decoupled so the formatted display string
     * and the engine input stay in sync.
     */
    private fun parseTimeOfDay(iso: String?): LocalTime? {
        if (iso.isNullOrBlank()) return null
        return runCatching { LocalDateTime.parse(iso).toLocalTime() }.getOrNull()
    }

    /**
     * UV scale labels mirror EPA / WMO conventions — short, scannable.
     * Anything over 11 is "extreme" but in practice that's vanishingly rare
     * outside high altitude / equator + summer.
     */
    private fun formatUv(uv: Double?): String {
        if (uv == null) return ""
        val rounded = uv.roundToInt()
        val band = when {
            rounded < 3 -> appContext.getString(R.string.dashboard_uv_low)
            rounded < 6 -> appContext.getString(R.string.dashboard_uv_moderate)
            rounded < 8 -> appContext.getString(R.string.dashboard_uv_high)
            rounded < 11 -> appContext.getString(R.string.dashboard_very_high)
            else -> appContext.getString(R.string.dashboard_uv_extreme)
        }
        return "$rounded · $band"
    }

    private fun buildAirQualitySummary(response: AirQualityResponse): AirQualitySummary? {
        val current = response.current ?: return null
        val units = response.currentUnits
        val pollutantMetrics = listOfNotNull(
            current.pm25?.let { AirQualityMetric(appContext.getString(R.string.dashboard_pollutant_pm25), formatAirMeasure(it, units?.pm25)) },
            current.pm10?.let { AirQualityMetric(appContext.getString(R.string.dashboard_pollutant_pm10), formatAirMeasure(it, units?.pm10)) },
            current.ozone?.let { AirQualityMetric(appContext.getString(R.string.dashboard_pollutant_ozone), formatAirMeasure(it, units?.ozone)) }
        )
        val pollenRows = buildPollenRows(current, response)
        if (current.usAqi == null && pollutantMetrics.isEmpty() && pollenRows.none { it.level != PollenLevel.UNAVAILABLE }) {
            return null
        }

        val aqiDescription = describeUsAqi(current.usAqi)
        return AirQualitySummary(
            aqi = current.usAqi?.toString() ?: "—",
            band = aqiDescription.first,
            detail = aqiDescription.second,
            level = aqiLevel(current.usAqi),
            pollutantMetrics = pollutantMetrics,
            pollenRows = pollenRows,
            hasPollenData = pollenRows.any { it.level != PollenLevel.UNAVAILABLE }
        )
    }

    private fun buildPollenRows(current: CurrentAirQuality, response: AirQualityResponse): List<PollenMetric> {
        val tree = maxNullable(current.alderPollen, current.birchPollen, current.olivePollen)
        val grass = current.grassPollen
        val weed = maxNullable(current.mugwortPollen, current.ragweedPollen)
        val unit = response.currentUnits?.grassPollen
            ?: response.currentUnits?.birchPollen
            ?: appContext.getString(R.string.dashboard_air_quality_grains_m3)
        return listOf(
            pollenMetric(appContext.getString(R.string.dashboard_pollen_tree), tree, unit),
            pollenMetric(appContext.getString(R.string.dashboard_pollen_grass), grass, unit),
            pollenMetric(appContext.getString(R.string.dashboard_pollen_weed), weed, unit)
        )
    }

    private fun pollenMetric(label: String, value: Double?, unit: String): PollenMetric {
        if (value == null) {
            return PollenMetric(
                label = label,
                value = getApplication<Application>()
                    .getString(R.string.dashboard_pollen_not_reported),
                band = getApplication<Application>()
                    .getString(R.string.dashboard_pollen_band_unavailable),
                level = PollenLevel.UNAVAILABLE
            )
        }
        val level = pollenLevel(value)
        return PollenMetric(
            label = label,
            value = "${formatAirNumber(value)} $unit",
            band = pollenBand(level),
            level = level
        )
    }

    private fun formatAirMeasure(value: Double, unit: String?): String {
        return "${formatAirNumber(value)} ${unit ?: "µg/m³"}"
    }

    private fun formatAirNumber(value: Double): String {
        return if (value >= 100 || value % 1.0 == 0.0) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    private fun describeUsAqi(aqi: Int?): Pair<String, String> = when {
        aqi == null -> appContext.getString(R.string.dashboard_aqi_unavailable) to appContext.getString(R.string.dashboard_aqi_unavailable_detail)
        aqi <= 50 -> appContext.getString(R.string.dashboard_good) to appContext.getString(R.string.dashboard_aqi_good_detail)
        aqi <= 100 -> appContext.getString(R.string.dashboard_moderate) to appContext.getString(R.string.dashboard_aqi_moderate_detail)
        aqi <= 150 -> appContext.getString(R.string.dashboard_sensitive_groups) to appContext.getString(R.string.dashboard_aqi_sensitive_detail)
        aqi <= 200 -> appContext.getString(R.string.dashboard_unhealthy) to appContext.getString(R.string.dashboard_aqi_unhealthy_detail)
        aqi <= 300 -> appContext.getString(R.string.dashboard_very_unhealthy) to appContext.getString(R.string.dashboard_aqi_very_unhealthy_detail)
        else -> appContext.getString(R.string.dashboard_hazardous) to appContext.getString(R.string.dashboard_aqi_hazardous_detail)
    }

    private fun aqiLevel(aqi: Int?): AirQualityLevel = when {
        aqi == null -> AirQualityLevel.UNKNOWN
        aqi <= 50 -> AirQualityLevel.GOOD
        aqi <= 100 -> AirQualityLevel.MODERATE
        aqi <= 150 -> AirQualityLevel.SENSITIVE
        aqi <= 200 -> AirQualityLevel.UNHEALTHY
        aqi <= 300 -> AirQualityLevel.VERY_UNHEALTHY
        else -> AirQualityLevel.HAZARDOUS
    }

    private fun pollenLevel(value: Double): PollenLevel = when {
        value <= 0.0 -> PollenLevel.NONE
        value < 10.0 -> PollenLevel.LOW
        value < 50.0 -> PollenLevel.MODERATE
        value < 100.0 -> PollenLevel.HIGH
        else -> PollenLevel.VERY_HIGH
    }

    private fun pollenBand(level: PollenLevel): String = when (level) {
        PollenLevel.NONE -> appContext.getString(R.string.alarm_edit_none)
        PollenLevel.LOW -> appContext.getString(R.string.dashboard_low)
        PollenLevel.MODERATE -> appContext.getString(R.string.dashboard_moderate)
        PollenLevel.HIGH -> appContext.getString(R.string.dashboard_high)
        PollenLevel.VERY_HIGH -> appContext.getString(R.string.dashboard_very_high_2)
        PollenLevel.UNAVAILABLE -> appContext.getString(R.string.settings_health_unavailable)
    }

    private fun maxNullable(vararg values: Double?): Double? {
        return values.filterNotNull().maxOrNull()
    }

    private fun shouldRescheduleSolarAlarms(
        previous: AppSettings,
        newLatitude: Double,
        newLongitude: Double,
        newLocationName: String,
        useManualLocation: Boolean
    ): Boolean {
        if (previous.useManualLocation != useManualLocation) return true
        if (useManualLocation && previous.locationName != newLocationName) return true
        return abs(previous.lastKnownLatitude - newLatitude) >= SOLAR_RESCHEDULE_LOCATION_DELTA ||
            abs(previous.lastKnownLongitude - newLongitude) >= SOLAR_RESCHEDULE_LOCATION_DELTA
    }
}
