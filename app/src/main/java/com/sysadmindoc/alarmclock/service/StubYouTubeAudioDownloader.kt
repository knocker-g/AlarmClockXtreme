package com.sysadmindoc.alarmclock.service

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub implementation for the personal build. The yt-dlp library bundles a 
 * native Python interpreter that is omitted for simplicity and privacy. 
 * The UI checks [isAvailable] and hides the "Download from YouTube" entry 
 * point - but the interface still resolves so the rest of the app compiles cleanly.
 */
@Singleton
class StubYouTubeAudioDownloader @Inject constructor() : YouTubeAudioDownloader {
    override fun isAvailable(): Boolean = false

    override suspend fun updateEngine(): Result<YouTubeEngineUpdateResult> =
        Result.failure(
            UnsupportedOperationException(
                "YouTube downloader updates aren't available in this build."
            )
        )

    override suspend fun downloadAsAlarm(youtubeUrl: String, displayName: String): Result<String> =
        Result.failure(
            UnsupportedOperationException(
                "YouTube downloads aren't available in this build."
            )
        )

    override suspend fun searchAlarmSounds(
        query: String,
        maxDurationSeconds: Int,
    ): Result<List<YouTubeSearchHit>> =
        Result.failure(
            UnsupportedOperationException(
                "YouTube search isn't available in this build."
            )
        )

    override suspend fun getPreviewStreamUrl(youtubeUrl: String): Result<String> =
        Result.failure(
            UnsupportedOperationException(
                "YouTube preview isn't available in this build."
            )
        )
}

@Singleton
class StubYouTubeDownloadInitializer @Inject constructor() : YouTubeDownloadInitializer {
    override suspend fun initialize() { /* no-op */ }
}
