package com.sysadmindoc.alarmclock.ui.alarmfiring.challenges

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stub implementation for the personal build. ML Kit is excluded.
 */
@Singleton
class StubDigitalInkChallengeRecognizer @Inject constructor() : DigitalInkChallengeRecognizer {
    override suspend fun recognize(
        request: DigitalInkRecognitionRequest
    ): DigitalInkRecognitionResult = DigitalInkRecognitionResult(
        candidates = emptyList(),
        unavailableReason = "Handwriting recognition is not included in this build. Type the word instead."
    )
}
