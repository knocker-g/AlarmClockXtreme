package com.sysadmindoc.alarmclock.data.backup

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.sysadmindoc.alarmclock.data.local.AlarmDatabase
import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.data.local.entity.AlarmGroup
import com.sysadmindoc.alarmclock.data.preferences.AppSettings
import com.sysadmindoc.alarmclock.data.preferences.PreferencesManager
import com.sysadmindoc.alarmclock.domain.AlarmScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RestoreJournalCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AlarmDatabase,
    private val preferencesManager: PreferencesManager,
    private val alarmScheduler: AlarmScheduler,
    moshi: Moshi
) {
    private val journalFile = File(context.filesDir, JOURNAL_FILENAME)
    private val atomicFile = AtomicFile(journalFile)

    private val backupDataAdapter = moshi.adapter(BackupData::class.java)
    private val groupListType = Types.newParameterizedType(List::class.java, AlarmGroup::class.java)
    private val groupListAdapter = moshi.adapter<List<AlarmGroup>>(groupListType)

    suspend fun prepareTransaction(
        settings: AppSettings,
        alarms: List<Alarm>,
        groups: List<AlarmGroup>
    ) {
        val backupData = BackupData(
            alarms = alarms.map { it.toAlarmBackup() },
            settings = SettingsBackup(
                is24HourFormat = settings.is24HourFormat,
                defaultSnoozeDuration = settings.defaultSnoozeDuration,
                defaultGradualVolume = settings.defaultGradualVolume,
                usePhoneSpeakers = settings.usePhoneSpeakers,
                showAlarmClockIcon = settings.showAlarmClockIcon,
                hideAlarmLabelsOnPublicSurfaces = settings.hideAlarmLabelsOnPublicSurfaces,
                vacationModeEnabled = settings.vacationModeEnabled,
                vacationStartMillis = settings.vacationStartMillis,
                vacationEndMillis = settings.vacationEndMillis,
                showWeatherOnDashboard = settings.showWeatherOnDashboard,
                showCalendarOnDashboard = settings.showCalendarOnDashboard,
                postDismissSummaryEnabled = settings.postDismissSummaryEnabled,
                temperatureUnit = settings.temperatureUnit,
                bedtimeEnabled = settings.bedtimeEnabled,
                bedtimeHour = settings.bedtimeHour,
                bedtimeMinute = settings.bedtimeMinute,
                sleepGoalHours = settings.sleepGoalHours,
                sleepGoalMinutes = settings.sleepGoalMinutes,
                bedtimeReminderMinutes = settings.bedtimeReminderMinutes,
                chronotypeAnswers = settings.chronotypeAnswers,
                jetLagTargetWakeMinutes = settings.jetLagTargetWakeMinutes,
                jetLagAdjustmentDays = settings.jetLagAdjustmentDays,
                jetLagDirection = settings.jetLagDirection,
                bedtimeDndEnabled = settings.bedtimeDndEnabled,
                flipToSnoozeEnabled = settings.flipToSnoozeEnabled,
                webhookEnabled = settings.webhookEnabled,
                webhookUrl = settings.webhookUrl,
                webhookIncludeLabel = settings.webhookIncludeLabel,
                webhookSigningSecret = settings.webhookSigningSecret,
                holidayAutoSkipEnabled = settings.holidayAutoSkipEnabled,
                holidayCountryCode = settings.holidayCountryCode,
                hueBridgeIp = settings.hueBridgeIp,
                hueApiKey = settings.hueApiKey,
                hueLightIds = settings.hueLightIds,
                accentColor = settings.accentColor,
                adaptiveDifficultyEnabled = settings.adaptiveDifficultyEnabled,
                calendarAutoAlarmEnabled = settings.calendarAutoAlarmEnabled,
                calendarAutoAlarmMinutesBefore = settings.calendarAutoAlarmMinutesBefore,
                calendarCommuteAwareEnabled = settings.calendarCommuteAwareEnabled,
                calendarCommuteBaselineMinutes = settings.calendarCommuteBaselineMinutes,
                calendarCommuteWeatherExtraMinutes = settings.calendarCommuteWeatherExtraMinutes,
                googleRoutesApiKey = settings.googleRoutesApiKey,
                customTypingPhrases = settings.customTypingPhrases,
                showMotivationalQuotes = settings.showMotivationalQuotes,
                dynamicColorEnabled = settings.dynamicColorEnabled,
                expressiveModeEnabled = settings.expressiveModeEnabled,
                coverToSnoozeEnabled = settings.coverToSnoozeEnabled,
                bedtimeChecklist = settings.bedtimeChecklist,
                sleepSoundTimerMinutes = settings.sleepSoundTimerMinutes,
                sleepSoundFadeSeconds = settings.sleepSoundFadeSeconds,
                repeatMissedAlarms = settings.repeatMissedAlarms,
                napDefaultMinutes = settings.napDefaultMinutes,
                pauseUntilMillis = settings.pauseUntilMillis,
                healthConnectEnabled = settings.healthConnectEnabled,
                newsFeedUrl = settings.newsFeedUrl,
                autoSilenceMinutes = settings.autoSilenceMinutes,
                locationName = settings.locationName,
                useManualLocation = settings.useManualLocation,
                lastKnownLatitude = settings.lastKnownLatitude,
                lastKnownLongitude = settings.lastKnownLongitude,
                showDashboardTab = settings.showDashboardTab,
                showTimerTab = settings.showTimerTab,
                showWorldClockTab = settings.showWorldClockTab,
                showNewsTab = settings.showNewsTab,
                showRadarEmbed = settings.showRadarEmbed,
                cancellationLockMinutes = settings.cancellationLockMinutes,
                holdToDismissMillis = settings.holdToDismissMillis,
                hueBridgeCertFingerprint = settings.hueBridgeCertFingerprint,
                firingControlMode = settings.firingControlMode,
                challengeBypassEnabled = settings.challengeBypassEnabled,
                challengeBypassDelaySeconds = settings.challengeBypassDelaySeconds
            )
        )
        val backupJson = backupDataAdapter.toJson(backupData)
            ?: throw IllegalStateException("Failed to serialize BackupData snapshot")
        val groupsJson = groupListAdapter.toJson(groups)
            ?: throw IllegalStateException("Failed to serialize AlarmGroup snapshot")

        val payloadToHash = backupJson + groupsJson
        val checksum = sha256(payloadToHash)

        val json = JSONObject().apply {
            put("formatVersion", 1)
            put("transactionId", UUID.randomUUID().toString())
            put("phase", PHASE_PREPARED)
            put("createdAt", System.currentTimeMillis())
            put("backupJson", backupJson)
            put("groupsJson", groupsJson)
            put("checksum", checksum)
        }
        writeJournalAtomic(json.toString())
    }

    suspend fun markCommitted() {
        val jsonStr = readJournalString() ?: return
        val json = JSONObject(jsonStr)
        json.put("phase", PHASE_COMMITTED)
        writeJournalAtomic(json.toString())
    }

    suspend fun cleanup() {
        if (journalFile.exists()) {
            atomicFile.delete()
        }
    }

    suspend fun checkAndRecover() {
        if (!journalFile.exists()) return

        val jsonStr = runCatching { readJournalString() }.getOrElse {
            throw IllegalStateException("Corrupt or unparseable restore transaction journal. Startup aborted for safety.", it)
        }

        if (jsonStr == null) {
            atomicFile.delete()
            return
        }

        val json = runCatching { JSONObject(jsonStr) }.getOrElse {
            throw SecurityException("Malformed journal JSON payload. Fails closed.", it)
        }

        val formatVersion = json.optInt("formatVersion", -1)
        if (formatVersion != 1) {
            throw SecurityException("Unsupported or missing restore journal format version: $formatVersion. Fails closed.")
        }

        val phase = json.optString("phase", "")
        if (phase != PHASE_PREPARED && phase != PHASE_COMMITTED) {
            throw SecurityException("Unknown or malformed journal phase: $phase. Fails closed.")
        }

        val backupJson = json.optString("backupJson", "")
        val groupsJson = json.optString("groupsJson", "")
        val checksum = json.optString("checksum", "")

        val expectedChecksum = sha256(backupJson + groupsJson)
        if (expectedChecksum != checksum) {
            throw SecurityException("Restore journal checksum mismatch. Fails closed for data safety.")
        }

        when (phase) {
            PHASE_PREPARED -> {
                val backupData = backupDataAdapter.fromJson(backupJson)
                    ?: throw IllegalStateException("Failed to decode BackupData snapshot in PREPARED journal")
                val oldSettings = backupData.settings?.let { applyBackup(AppSettings(), it) } ?: AppSettings()
                val oldAlarms = backupData.alarms.mapNotNull { it.toAlarmOrNull() }
                val oldGroups = groupListAdapter.fromJson(groupsJson)
                    ?: throw IllegalStateException("Failed to decode AlarmGroup snapshot in PREPARED journal")

                database.withTransaction {
                    val alarmDao = database.alarmDao()
                    val groupDao = database.alarmGroupDao()
                    alarmDao.deleteAll()
                    groupDao.deleteAll()
                    if (oldAlarms.isNotEmpty()) alarmDao.insertAll(oldAlarms)
                    if (oldGroups.isNotEmpty()) groupDao.insertAll(oldGroups)
                }

                preferencesManager.update { oldSettings }

                // Reconcile old truth
                alarmScheduler.rescheduleAllInBatches()

                // Cleanup journal after successful old-truth reconcile attempt
                atomicFile.delete()
            }
            PHASE_COMMITTED -> {
                // Reconcile new truth
                alarmScheduler.rescheduleAllInBatches()

                // Cleanup journal after reconcile attempt
                atomicFile.delete()
            }
        }
    }

    private fun applyBackup(current: AppSettings, s: SettingsBackup): AppSettings = current.copy(
        is24HourFormat = s.is24HourFormat,
        defaultSnoozeDuration = s.defaultSnoozeDuration,
        defaultGradualVolume = s.defaultGradualVolume,
        usePhoneSpeakers = s.usePhoneSpeakers,
        showAlarmClockIcon = s.showAlarmClockIcon,
        hideAlarmLabelsOnPublicSurfaces = s.hideAlarmLabelsOnPublicSurfaces,
        vacationModeEnabled = s.vacationModeEnabled,
        vacationStartMillis = s.vacationStartMillis,
        vacationEndMillis = s.vacationEndMillis,
        showWeatherOnDashboard = s.showWeatherOnDashboard,
        showCalendarOnDashboard = s.showCalendarOnDashboard,
        postDismissSummaryEnabled = s.postDismissSummaryEnabled,
        temperatureUnit = s.temperatureUnit,
        bedtimeEnabled = s.bedtimeEnabled,
        bedtimeHour = s.bedtimeHour,
        bedtimeMinute = s.bedtimeMinute,
        sleepGoalHours = s.sleepGoalHours,
        sleepGoalMinutes = s.sleepGoalMinutes,
        bedtimeReminderMinutes = s.bedtimeReminderMinutes,
        chronotypeAnswers = s.chronotypeAnswers,
        jetLagTargetWakeMinutes = s.jetLagTargetWakeMinutes,
        jetLagAdjustmentDays = s.jetLagAdjustmentDays,
        jetLagDirection = s.jetLagDirection,
        bedtimeDndEnabled = s.bedtimeDndEnabled,
        flipToSnoozeEnabled = s.flipToSnoozeEnabled,
        webhookEnabled = s.webhookEnabled,
        webhookUrl = s.webhookUrl,
        webhookIncludeLabel = s.webhookIncludeLabel,
        webhookSigningSecret = s.webhookSigningSecret,
        holidayAutoSkipEnabled = s.holidayAutoSkipEnabled,
        holidayCountryCode = s.holidayCountryCode,
        hueBridgeIp = s.hueBridgeIp,
        hueApiKey = s.hueApiKey,
        hueLightIds = s.hueLightIds,
        accentColor = s.accentColor,
        adaptiveDifficultyEnabled = s.adaptiveDifficultyEnabled,
        calendarAutoAlarmEnabled = s.calendarAutoAlarmEnabled,
        calendarAutoAlarmMinutesBefore = s.calendarAutoAlarmMinutesBefore,
        calendarCommuteAwareEnabled = s.calendarCommuteAwareEnabled,
        calendarCommuteBaselineMinutes = s.calendarCommuteBaselineMinutes,
        calendarCommuteWeatherExtraMinutes = s.calendarCommuteWeatherExtraMinutes,
        googleRoutesApiKey = s.googleRoutesApiKey,
        customTypingPhrases = s.customTypingPhrases,
        showMotivationalQuotes = s.showMotivationalQuotes,
        dynamicColorEnabled = s.dynamicColorEnabled,
        expressiveModeEnabled = s.expressiveModeEnabled,
        coverToSnoozeEnabled = s.coverToSnoozeEnabled,
        bedtimeChecklist = s.bedtimeChecklist,
        sleepSoundTimerMinutes = s.sleepSoundTimerMinutes,
        sleepSoundFadeSeconds = s.sleepSoundFadeSeconds,
        repeatMissedAlarms = s.repeatMissedAlarms,
        napDefaultMinutes = s.napDefaultMinutes,
        pauseUntilMillis = s.pauseUntilMillis,
        healthConnectEnabled = s.healthConnectEnabled,
        newsFeedUrl = s.newsFeedUrl,
        autoSilenceMinutes = s.autoSilenceMinutes,
        locationName = s.locationName,
        useManualLocation = s.useManualLocation,
        lastKnownLatitude = s.lastKnownLatitude,
        lastKnownLongitude = s.lastKnownLongitude,
        showDashboardTab = s.showDashboardTab,
        showTimerTab = s.showTimerTab,
        showWorldClockTab = s.showWorldClockTab,
        showNewsTab = s.showNewsTab,
        showRadarEmbed = s.showRadarEmbed,
        cancellationLockMinutes = s.cancellationLockMinutes,
        holdToDismissMillis = s.holdToDismissMillis,
        hueBridgeCertFingerprint = s.hueBridgeCertFingerprint,
        firingControlMode = s.firingControlMode,
        challengeBypassEnabled = s.challengeBypassEnabled,
        challengeBypassDelaySeconds = s.challengeBypassDelaySeconds
    )

    private fun writeJournalAtomic(jsonContent: String) {
        val bytes = jsonContent.toByteArray(StandardCharsets.UTF_8)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(bytes)
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
            throw e
        }
    }

    private fun readJournalString(): String? {
        if (!journalFile.exists()) return null
        val bytes = atomicFile.readFully()
        return String(bytes, StandardCharsets.UTF_8)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(StandardCharsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val JOURNAL_FILENAME = "restore_transaction.journal"
        const val PHASE_PREPARED = "PREPARED"
        const val PHASE_COMMITTED = "COMMITTED"
    }
}
