package com.sysadmindoc.alarmclock.di

import com.sysadmindoc.alarmclock.data.health.HealthConnectSleepRepository
import com.sysadmindoc.alarmclock.data.health.StubHealthConnectSleepRepository
import com.sysadmindoc.alarmclock.service.StubYouTubeAudioDownloader
import com.sysadmindoc.alarmclock.service.StubYouTubeDownloadInitializer
import com.sysadmindoc.alarmclock.service.YouTubeAudioDownloader
import com.sysadmindoc.alarmclock.service.YouTubeDownloadInitializer
import com.sysadmindoc.alarmclock.ui.alarmfiring.challenges.DigitalInkChallengeRecognizer
import com.sysadmindoc.alarmclock.ui.alarmfiring.challenges.StubDigitalInkChallengeRecognizer
import com.sysadmindoc.alarmclock.wear.StubWearNextAlarmBridge
import com.sysadmindoc.alarmclock.wear.WearNextAlarmBridge
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Personal build module - binds stubs for features that are excluded
 * to keep the build lightweight and private.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PersonalModule {
    @Binds
    @Singleton
    abstract fun bindDownloader(impl: StubYouTubeAudioDownloader): YouTubeAudioDownloader

    @Binds
    @Singleton
    abstract fun bindInitializer(impl: StubYouTubeDownloadInitializer): YouTubeDownloadInitializer

    @Binds
    @Singleton
    abstract fun bindWearNextAlarmBridge(impl: StubWearNextAlarmBridge): WearNextAlarmBridge

    @Binds
    @Singleton
    abstract fun bindHealthConnectSleepRepository(
        impl: StubHealthConnectSleepRepository
    ): HealthConnectSleepRepository

    @Binds
    @Singleton
    abstract fun bindDigitalInkChallengeRecognizer(
        impl: StubDigitalInkChallengeRecognizer
    ): DigitalInkChallengeRecognizer
}
