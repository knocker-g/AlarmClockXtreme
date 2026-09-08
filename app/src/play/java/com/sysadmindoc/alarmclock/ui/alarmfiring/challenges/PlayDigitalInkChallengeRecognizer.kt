package com.sysadmindoc.alarmclock.ui.alarmfiring.challenges

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.sysadmindoc.alarmclock.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine

@Singleton
class PlayDigitalInkChallengeRecognizer @Inject constructor(
    @ApplicationContext private val context: Context
) : DigitalInkChallengeRecognizer {
    override suspend fun recognize(
        request: DigitalInkRecognitionRequest
    ): DigitalInkRecognitionResult = withContext(Dispatchers.IO) {
        val model = DigitalInkRecognitionModel.builder(DigitalInkRecognitionModelIdentifier.EN_US).build()
        val manager = RemoteModelManager.getInstance()

        runCatching {
            if (!manager.isModelDownloaded(model).awaitTask()) {
                val conditions = DownloadConditions.Builder().build()
                manager.download(model, conditions).awaitTask()
            }
            val recognizer = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
            try {
                val result = recognizer.recognize(request.toInk()).awaitTask()
                DigitalInkRecognitionResult(
                    candidates = result.candidates.map { it.text }
                )
            } finally {
                recognizer.close()
            }
        }.getOrElse { error ->
            DigitalInkRecognitionResult(
                candidates = emptyList(),
                unavailableReason = error.message
                    ?: context.getString(R.string.alarmfiring_handwriting_recognition_is_unavailable_type_the)
            )
        }
    }

    private fun DigitalInkRecognitionRequest.toInk(): Ink {
        val ink = Ink.builder()
        strokes
            .filter { it.points.size >= 2 }
            .forEach { stroke ->
                val strokeBuilder = Ink.Stroke.builder()
                stroke.points.forEach { point ->
                    strokeBuilder.addPoint(
                        Ink.Point.create(point.x, point.y, point.timestampMillis)
                    )
                }
                ink.addStroke(strokeBuilder.build())
            }
        return ink.build()
    }
}

private suspend fun <T> Task<T>.awaitTask(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            continuation.resume(value)
        }
        addOnFailureListener { error ->
            continuation.resumeWithException(error)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }
