package com.sysadmindoc.alarmclock.directboot

/**
 * Shared in-process monitor lock for Device-Protected (DE) storage in "direct_boot_next_alarm".
 *
 * Coordinates serialized access across:
 * - [DirectBootAlarmCache] (V2 Queue operations)
 * - [DirectBootDeliveryLedger] (Ledger append/ACK operations)
 * - [DirectBootDeliveryTransitionEngine] (Atomic Queue + Ledger delivery transitions)
 *
 * Enforces single-owner serialization within the host process without multi-process atomicity claims.
 */
internal object DirectBootStorageLock {
    val lock = Any()
}
