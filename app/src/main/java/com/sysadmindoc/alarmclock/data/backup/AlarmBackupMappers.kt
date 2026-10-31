package com.sysadmindoc.alarmclock.data.backup

import com.sysadmindoc.alarmclock.data.model.Alarm
import com.sysadmindoc.alarmclock.domain.evaluator.HolidayPolicy
import java.time.DayOfWeek
import java.util.Locale

fun Alarm.toAlarmBackup(): AlarmBackup {
    val canonicalPolicy = canonicalHolidayPolicy()
    return AlarmBackup(
        id = id,
        hour = hour,
        minute = minute,
        label = label,
        isEnabled = isEnabled,
        repeatDays = repeatDays.map { it.name },
        ringtoneUri = ringtoneUri,
        vibrationEnabled = vibrationEnabled,
        vibrationIntensity = vibrationIntensity,
        volume = volume,
        overrideSystemVolume = overrideSystemVolume,
        gradualVolumeSeconds = gradualVolumeSeconds,
        snoozeDurationMinutes = snoozeDurationMinutes,
        maxSnoozeCount = maxSnoozeCount,
        showOnLockScreen = showOnLockScreen,
        challengeType = challengeType,
        group = group,
        flashWake = flashWake,
        vibrationPattern = vibrationPattern,
        ttsEnabled = ttsEnabled,
        walkStepsRequired = walkStepsRequired,
        wakeConfirmEnabled = wakeConfirmEnabled,
        wakeConfirmDelayMinutes = wakeConfirmDelayMinutes,
        smartAlarmEnabled = smartAlarmEnabled,
        smartAlarmWindowMinutes = smartAlarmWindowMinutes,
        skipOnHolidays = skipOnHolidays,
        nfcTagId = nfcTagId,
        barcodeValue = barcodeValue,
        spotifyUri = spotifyUri,
        hueEnabled = hueEnabled,
        huePreWakeMinutes = huePreWakeMinutes,
        photoMatchUri = photoMatchUri,
        challengeChain = challengeChain,
        progressiveSnooze = progressiveSnooze,
        backupSoundEnabled = backupSoundEnabled,
        backupSoundDelaySec = backupSoundDelaySec,
        sunriseSimulation = sunriseSimulation,
        sunriseMinutes = sunriseMinutes,
        specificDate = specificDate,
        profileName = profileName,
        earlyDismissMinutes = earlyDismissMinutes,
        guardianEnabled = guardianEnabled,
        guardianPhone = guardianPhone,
        guardianDelaySec = guardianDelaySec,
        locationDismissEnabled = locationDismissEnabled,
        locationDismissLat = locationDismissLat,
        locationDismissLng = locationDismissLng,
        locationDismissRadius = locationDismissRadius,
        wifiDismissSsid = wifiDismissSsid,
        internetRadioUrl = internetRadioUrl,
        flashlightStrobe = flashlightStrobe,
        morningRoutine = morningRoutine,
        hardwareButtonAction = hardwareButtonAction,
        dismissAtRingtoneEnd = dismissAtRingtoneEnd,
        ringtonePool = ringtonePool,
        solarOffsetMinutes = solarOffsetMinutes,
        solarAnchor = solarAnchor,
        holdToDismissEnabled = holdToDismissEnabled,
        vibrationDelaySeconds = vibrationDelaySeconds,
        weatherEarlyMinutes = weatherEarlyMinutes,
        requiredSquats = requiredSquats,
        dismissActionType = dismissActionType,
        dismissActionPayload = dismissActionPayload,
        firingBackgroundImageEnabled = firingBackgroundImageEnabled,
        firingBackgroundImageUri = firingBackgroundImageUri,
        firingBackgroundBlurEnabled = firingBackgroundBlurEnabled,
        sortOrder = sortOrder,
        shiftPattern = shiftPattern,
        shiftPatternStartDate = shiftPatternStartDate,
        timezonePolicy = timezonePolicy,
        fixedTimezoneId = fixedTimezoneId,
        scheduleKind = scheduleKind,
        holidayPolicy = canonicalPolicy.name
    )
}

fun AlarmBackup.toAlarmOrNull(): Alarm? {
    return runCatching {
        val resolvedHolidayPolicy = when {
            holidayPolicy == null -> HolidayPolicy.fromLegacySkipOnHolidays(skipOnHolidays)
            else -> HolidayPolicy.parseCanonicalOrNull(holidayPolicy)
                ?: throw IllegalArgumentException("Unsupported holidayPolicy: $holidayPolicy")
        }
        val parsedRepeatDays = repeatDays.mapNotNull {
            runCatching { DayOfWeek.valueOf(it.trim().uppercase(Locale.US)) }.getOrNull()
        }.toSet()
        val resolvedScheduleKind = when {
            resolvedHolidayPolicy == HolidayPolicy.INCLUDE -> Alarm.SCHEDULE_KIND_REPEATING
            !scheduleKind.isNullOrBlank() -> {
                val normalizedKind = scheduleKind.trim().uppercase(Locale.US)
                require(
                    normalizedKind == Alarm.SCHEDULE_KIND_ONE_TIME ||
                        normalizedKind == Alarm.SCHEDULE_KIND_REPEATING
                ) { "Unsupported scheduleKind: $scheduleKind" }
                normalizedKind
            }
            parsedRepeatDays.isNotEmpty() || (shiftPattern.isNotBlank() && shiftPatternStartDate.isNotBlank()) ->
                Alarm.SCHEDULE_KIND_REPEATING
            else -> Alarm.SCHEDULE_KIND_ONE_TIME
        }

        Alarm(
            id = id,
            hour = hour,
            minute = minute,
            label = label,
            isEnabled = isEnabled,
            repeatDays = parsedRepeatDays,
            ringtoneUri = ringtoneUri,
            vibrationEnabled = vibrationEnabled,
            vibrationIntensity = vibrationIntensity,
            volume = volume,
            overrideSystemVolume = overrideSystemVolume,
            gradualVolumeSeconds = gradualVolumeSeconds,
            snoozeDurationMinutes = snoozeDurationMinutes,
            maxSnoozeCount = maxSnoozeCount,
            showOnLockScreen = showOnLockScreen,
            challengeType = challengeType,
            group = group,
            flashWake = flashWake,
            vibrationPattern = vibrationPattern,
            ttsEnabled = ttsEnabled,
            walkStepsRequired = walkStepsRequired,
            wakeConfirmEnabled = wakeConfirmEnabled,
            wakeConfirmDelayMinutes = wakeConfirmDelayMinutes,
            smartAlarmEnabled = smartAlarmEnabled,
            smartAlarmWindowMinutes = smartAlarmWindowMinutes,
            skipOnHolidays = skipOnHolidays,
            nfcTagId = nfcTagId,
            barcodeValue = barcodeValue,
            spotifyUri = spotifyUri,
            hueEnabled = hueEnabled,
            huePreWakeMinutes = huePreWakeMinutes,
            photoMatchUri = photoMatchUri,
            challengeChain = challengeChain,
            progressiveSnooze = progressiveSnooze,
            backupSoundEnabled = backupSoundEnabled,
            backupSoundDelaySec = backupSoundDelaySec,
            sunriseSimulation = sunriseSimulation,
            sunriseMinutes = sunriseMinutes,
            specificDate = specificDate,
            profileName = profileName,
            earlyDismissMinutes = earlyDismissMinutes,
            guardianEnabled = guardianEnabled,
            guardianPhone = guardianPhone,
            guardianDelaySec = guardianDelaySec,
            locationDismissEnabled = locationDismissEnabled,
            locationDismissLat = locationDismissLat,
            locationDismissLng = locationDismissLng,
            locationDismissRadius = locationDismissRadius,
            wifiDismissSsid = wifiDismissSsid,
            internetRadioUrl = internetRadioUrl,
            flashlightStrobe = flashlightStrobe,
            morningRoutine = morningRoutine,
            hardwareButtonAction = hardwareButtonAction,
            dismissAtRingtoneEnd = dismissAtRingtoneEnd,
            ringtonePool = ringtonePool,
            solarOffsetMinutes = solarOffsetMinutes,
            solarAnchor = solarAnchor,
            holdToDismissEnabled = holdToDismissEnabled,
            vibrationDelaySeconds = vibrationDelaySeconds,
            weatherEarlyMinutes = weatherEarlyMinutes,
            requiredSquats = requiredSquats,
            dismissActionType = dismissActionType,
            dismissActionPayload = dismissActionPayload,
            firingBackgroundImageEnabled = firingBackgroundImageEnabled,
            firingBackgroundImageUri = firingBackgroundImageUri,
            firingBackgroundBlurEnabled = firingBackgroundBlurEnabled,
            sortOrder = sortOrder,
            shiftPattern = shiftPattern,
            shiftPatternStartDate = shiftPatternStartDate,
            timezonePolicy = timezonePolicy,
            fixedTimezoneId = fixedTimezoneId,
            scheduleKind = resolvedScheduleKind,
            holidayPolicy = resolvedHolidayPolicy.name
        ).sanitized()
    }.getOrNull()
}
