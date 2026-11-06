package com.sysadmindoc.alarmclock.domain.session

/**
 * Minimal, immutable transition request encapsulating 6-fence CAS parameters
 * and deadline evaluation context for KNO-161 transition engine.
 */
data class AlarmSessionTransitionRequest(
    val sessionId: String,
    val expectedVersion: Long,
    val expectedState: String,
    val expectedAdmissionKey: String,
    val expectedGeneration: Int,
    val expectedRevision: Int,
    val currentWallClockMs: Long,
    val currentElapsedRealtimeMs: Long,
    val currentBootCount: Long,
    val event: AlarmSessionEvent
) {
    init {
        require(sessionId.isNotBlank()) { "sessionId cannot be blank" }
        require(expectedVersion > 0L) { "expectedVersion must be > 0" }
        require(expectedState.isNotBlank()) { "expectedState cannot be blank" }
        require(expectedAdmissionKey.isNotBlank()) { "expectedAdmissionKey cannot be blank" }
        require(expectedGeneration >= 0) { "expectedGeneration must be >= 0" }
        require(expectedRevision >= 0) { "expectedRevision must be >= 0" }
    }
}
