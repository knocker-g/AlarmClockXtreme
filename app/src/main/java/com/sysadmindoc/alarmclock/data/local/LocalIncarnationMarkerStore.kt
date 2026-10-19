package com.sysadmindoc.alarmclock.data.local

import android.content.Context
import androidx.core.util.AtomicFile
import com.sysadmindoc.alarmclock.domain.CompletionState
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

enum class LocalMarkerStatus {
    PRESENT,
    ABSENT,
    CORRUPT,
    READ_FAILURE,
    UNAVAILABLE
}

data class LocalMarkerPayload(
    val formatVersion: Int = 1,
    val incarnationId: String,
    val completionState: CompletionState
)

sealed interface LocalMarkerReadResult {
    data class Present(val payload: LocalMarkerPayload) : LocalMarkerReadResult
    object Absent : LocalMarkerReadResult
    object Corrupt : LocalMarkerReadResult
    object ReadFailure : LocalMarkerReadResult
    object Unavailable : LocalMarkerReadResult
}

sealed interface LocalMarkerWriteResult {
    object Success : LocalMarkerWriteResult
    data class InvalidPayload(val reason: String) : LocalMarkerWriteResult
    data class WriteFailure(val reason: String) : LocalMarkerWriteResult
    data class ReadbackMismatch(val expected: LocalMarkerPayload, val actual: LocalMarkerReadResult) : LocalMarkerWriteResult
    object StorageUnavailable : LocalMarkerWriteResult
}

@Singleton
class LocalIncarnationMarkerStore @Inject constructor(
    private val context: Context
) {
    companion object {
        const val CURRENT_FORMAT_VERSION = 1
        const val MARKER_FILE_NAME = "incarnation_marker.json"
        const val BACKUP_FILE_NAME = "incarnation_marker.json.bak"
    }

    private fun isCredentialStorageAccessible(): Boolean {
        return try {
            val userManager = context.getSystemService(Context.USER_SERVICE) as? android.os.UserManager
            if (userManager != null && !androidx.core.os.UserManagerCompat.isUserUnlocked(context)) {
                return false
            }
            context.noBackupFilesDir != null
        } catch (_: Exception) {
            false
        }
    }

    private fun getMarkerFileForRead(): File? {
        if (!isCredentialStorageAccessible()) return null
        return try {
            val noBackupDir = context.noBackupFilesDir ?: return null
            // Return file path without creating directories on read
            File(noBackupDir, MARKER_FILE_NAME)
        } catch (_: Exception) {
            null
        }
    }

    private fun getMarkerFileForWrite(): File? {
        if (!isCredentialStorageAccessible()) return null
        return try {
            val noBackupDir = context.noBackupFilesDir ?: return null
            if (!noBackupDir.exists() && !noBackupDir.mkdirs()) {
                null
            } else {
                File(noBackupDir, MARKER_FILE_NAME)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Reads the persisted local incarnation marker file from credential-protected noBackupFilesDir.
     * Uses [AtomicFile] to recover valid state from backup (.bak) if an earlier atomic write was interrupted.
     *
     * Returns [LocalMarkerReadResult]:
     * - [LocalMarkerReadResult.Present] if valid format version 1, non-blank incarnationId, and valid completionState.
     * - [LocalMarkerReadResult.Absent] if neither base file nor backup file exists.
     * - [LocalMarkerReadResult.Corrupt] if file exists but is malformed, truncated, or invalid format version.
     * - [LocalMarkerReadResult.ReadFailure] if an unexpected I/O exception occurs.
     * - [LocalMarkerReadResult.Unavailable] if storage directory or credential storage is locked/inaccessible.
     */
    fun readMarker(): LocalMarkerReadResult {
        val file = getMarkerFileForRead() ?: return LocalMarkerReadResult.Unavailable
        val backupFile = File(file.parentFile, BACKUP_FILE_NAME)

        // Check if neither primary file nor recoverable backup file exists
        if (!file.exists() && !backupFile.exists()) {
            return LocalMarkerReadResult.Absent
        }

        val content = try {
            val atomicFile = AtomicFile(file)
            atomicFile.openRead().use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        } catch (e: IOException) {
            return LocalMarkerReadResult.ReadFailure
        } catch (e: Exception) {
            return LocalMarkerReadResult.ReadFailure
        }

        if (content.isBlank()) {
            return LocalMarkerReadResult.Corrupt
        }

        return try {
            val json = JSONObject(content)
            val version = json.optInt("formatVersion", -1)
            if (version != CURRENT_FORMAT_VERSION) {
                return LocalMarkerReadResult.Corrupt
            }

            val incarnationId = json.optString("incarnationId", "")
            if (incarnationId.isBlank()) {
                return LocalMarkerReadResult.Corrupt
            }

            val completionStateName = json.optString("completionState", "")
            val completionState = when (completionStateName) {
                "COMPLETE" -> CompletionState.COMPLETE
                "PREPARING" -> CompletionState.PREPARING
                else -> return LocalMarkerReadResult.Corrupt
            }

            LocalMarkerReadResult.Present(
                LocalMarkerPayload(
                    formatVersion = version,
                    incarnationId = incarnationId,
                    completionState = completionState
                )
            )
        } catch (e: Exception) {
            LocalMarkerReadResult.Corrupt
        }
    }

    /**
     * Writes a local incarnation marker payload to noBackupFilesDir using [AtomicFile].
     *
     * Validates the payload before writing, verifies the write via post-commit readback,
     * and returns [LocalMarkerWriteResult].
     */
    fun writeMarker(payload: LocalMarkerPayload): LocalMarkerWriteResult {
        // 1. Validate payload fields
        if (payload.formatVersion != CURRENT_FORMAT_VERSION) {
            return LocalMarkerWriteResult.InvalidPayload("Unsupported format version: ${payload.formatVersion}")
        }
        if (payload.incarnationId.isBlank()) {
            return LocalMarkerWriteResult.InvalidPayload("Incarnation ID must not be blank")
        }
        if (payload.completionState != CompletionState.COMPLETE && payload.completionState != CompletionState.PREPARING) {
            return LocalMarkerWriteResult.InvalidPayload("Completion state must be COMPLETE or PREPARING")
        }

        // 2. Resolve storage file for write
        val file = getMarkerFileForWrite() ?: return LocalMarkerWriteResult.StorageUnavailable

        // 3. Serialize payload to JSON
        val json = JSONObject().apply {
            put("formatVersion", payload.formatVersion)
            put("incarnationId", payload.incarnationId)
            put("completionState", payload.completionState.name)
        }
        val jsonString = json.toString(2)

        // 4. Perform atomic write using AtomicFile
        val atomicFile = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            stream = atomicFile.startWrite()
            stream.write(jsonString.toByteArray(Charsets.UTF_8))
            atomicFile.finishWrite(stream)
        } catch (e: Exception) {
            if (stream != null) {
                atomicFile.failWrite(stream)
            }
            return LocalMarkerWriteResult.WriteFailure("Failed to write atomic marker file: ${e.message}")
        }

        // 5. Post-write readback verification
        val readbackResult = readMarker()
        if (readbackResult is LocalMarkerReadResult.Present && readbackResult.payload == payload) {
            return LocalMarkerWriteResult.Success
        }

        return LocalMarkerWriteResult.ReadbackMismatch(expected = payload, actual = readbackResult)
    }
}
