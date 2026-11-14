package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import android.content.Intent
import com.sysadmindoc.alarmclock.data.model.Alarm
import java.util.UUID

/**
 * Inactive Delivery Authorizer Foundation for KNO-75 (Phase D3-C1A0 / D3-C1A0-S).
 *
 * Responsibilities & Invariants:
 * - Read-only prequalification under [DirectBootStorageLock.lock]:
 *   Evaluates incoming V2 firing intent or identity against current DE Queue and Delivery Ledger states.
 *   Does NOT mutate Queue, Ledger, SharedPreferences, or any persistent state.
 * - Fail-closed storage integrity:
 *   Rejects prequalification with [DeliveryPrequalificationResult.StorageUnverifiable] if either
 *   Queue or Ledger storage is CORRUPT or has an UNSUPPORTED_SCHEMA.
 * - Queue head qualification:
 *   Rejects prequalification with [DeliveryPrequalificationResult.NotQueueHead] if Queue is absent,
 *   empty, or if the intent identity does not exactly match the authoritative Queue head.
 * - Idempotency & capacity boundaries:
 *   Rejects with [DeliveryPrequalificationResult.DuplicateDelivery] if the exact recordId already exists in Ledger.
 *   Rejects with [DeliveryPrequalificationResult.CapacityExhausted] if Ledger is at maximum capacity (16 records).
 * - Occurrence Staleness Boundary (DESIGN_BLOCKED):
 *   Canonical missed-alarm auto-silence policy and maximum occurrence staleness thresholds are not
 *   formally qualified for Direct Boot DE storage in this slice. To avoid inventing unverified
 *   staleness grace periods, prequalification preserves existing delivery behavior and leaves
 *   temporal validation strictly to authoritative [DirectBootDeliveryTransitionEngine.transitionNormal].
 * - Process-local single-use ticket mechanism:
 *   Issues bounded [DeliveryTicket]s with short TTL (default 15s) and wall-clock rollback fencing.
 *   [issueTicket] does NOT itself prove prior Queue/Ledger prequalification.
 *   Tickets are strictly process-local (in-memory only; not durable across processes or reboots).
 *   Validates and atomically consumes tickets on first use (single-use; not restored on downstream failure).
 *   Ticket consumption does NOT prove Service start, notification posting, or audible delivery.
 *   Possession of a consumed ticket does NOT authorize delivery; fresh [transitionNormal] under
 *   [DirectBootStorageLock.lock] remains strictly authoritative.
 */
class DirectBootDeliveryAuthorizer(
    val ticketTtlMs: Long = DEFAULT_TICKET_TTL_MS,
    val maxActiveTickets: Int = DEFAULT_MAX_ACTIVE_TICKETS
) {

    init {
        require(ticketTtlMs > 0L) { "ticketTtlMs MUST be positive, found $ticketTtlMs" }
        require(maxActiveTickets > 0) { "maxActiveTickets MUST be positive, found $maxActiveTickets" }
    }

    private val ticketLock = Any()
    private val activeTickets = mutableMapOf<String, DeliveryTicket>()

    /**
     * Tracks the maximum caller wall-clock timestamp observed in ticket operations to detect
     * backward clock jumps (clock rollback fencing). This is a wall-clock epoch millisecond tracker,
     * NOT a true monotonic elapsed-realtime clock.
     */
    private var lastRecordedWallClockEpochMs: Long = 0L

    /**
     * Performs read-only prequalification of an incoming firing [Intent].
     *
     * Validates:
     * 1. Action matches [DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM].
     * 2. Identity extras are present, non-blank, and valid.
     * 3. Intent data URI is structurally valid and canonical for the parsed identity.
     * 4. Identity digest extra is mandatory, non-blank, and matches canonical computed digest.
     * 5. Storage state under [DirectBootStorageLock.lock] (Queue head matches, Ledger not duplicate/full).
     */
    fun preQualify(
        context: Context,
        intent: Intent,
        nowEpochMs: Long = System.currentTimeMillis()
    ): DeliveryPrequalificationResult {
        if (intent.action != DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM) {
            return DeliveryPrequalificationResult.InvalidIdentity(
                "Invalid Intent action: '${intent.action}', expected '${DirectBootV2NormalIntentIdentity.ACTION_FIRE_V2_NORMAL_ALARM}'"
            )
        }

        val alarmId = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_ALARM_ID, -1L)
        val triggerTime = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_TRIGGER_TIME, -1L)
        val generation = intent.getIntExtra(DirectBootV2NormalIntentIdentity.EXTRA_GENERATION, -1)
        val instanceToken = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_INSTANCE_TOKEN) ?: ""
        val restoreEpoch = intent.getLongExtra(DirectBootV2NormalIntentIdentity.EXTRA_RESTORE_EPOCH, 0L)
        val kind = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_KIND) ?: DirectBootV2NormalIntentIdentity.KIND_NORMAL

        if (alarmId <= 0L || generation < 1 || instanceToken.isBlank() || triggerTime <= 0L || kind != DirectBootV2NormalIntentIdentity.KIND_NORMAL) {
            return DeliveryPrequalificationResult.InvalidIdentity(
                "Intent missing or invalid identity extras: alarmId=$alarmId, generation=$generation, instanceToken='$instanceToken', triggerTime=$triggerTime, kind='$kind'"
            )
        }

        val identity = try {
            DirectBootV2NormalIntentIdentity(
                alarmId = alarmId,
                generation = generation,
                instanceToken = instanceToken,
                triggerTime = triggerTime,
                restoreEpoch = restoreEpoch,
                kind = kind
            )
        } catch (e: Exception) {
            return DeliveryPrequalificationResult.InvalidIdentity("Failed to construct identity: ${e.message}")
        }

        val uri = intent.data
            ?: return DeliveryPrequalificationResult.InvalidIdentity("Intent missing data URI")

        val uriValidation = DirectBootV2NormalIntentIdentity.validateUri(uri, identity)
        if (uriValidation is UriValidationResult.Rejected) {
            return DeliveryPrequalificationResult.InvalidIdentity(uriValidation.reason)
        }

        val extraDigest = intent.getStringExtra(DirectBootV2NormalIntentIdentity.EXTRA_IDENTITY_DIGEST)
        if (extraDigest.isNullOrBlank()) {
            return DeliveryPrequalificationResult.InvalidIdentity(
                "Intent missing or blank required extra digest"
            )
        }
        if (extraDigest != identity.computeDigest()) {
            return DeliveryPrequalificationResult.InvalidIdentity(
                "Intent extra digest '$extraDigest' does not match computed identity digest '${identity.computeDigest()}'"
            )
        }

        return preQualify(context, identity, nowEpochMs)
    }

    /**
     * Performs read-only prequalification of a verified [DirectBootV2NormalIntentIdentity]
     * under [DirectBootStorageLock.lock].
     */
    fun preQualify(
        context: Context,
        identity: DirectBootV2NormalIntentIdentity,
        nowEpochMs: Long = System.currentTimeMillis()
    ): DeliveryPrequalificationResult {
        return synchronized(DirectBootStorageLock.lock) {
            // 1. Inspect Queue Storage
            val queueState = DirectBootAlarmCache.inspectQueueStorageState(context)
            val headItem = when (queueState) {
                is QueueStorageState.Absent -> {
                    return@synchronized DeliveryPrequalificationResult.NotQueueHead(
                        "Queue storage is absent",
                        identity,
                        null
                    )
                }
                is QueueStorageState.ValidEmpty -> {
                    return@synchronized DeliveryPrequalificationResult.NotQueueHead(
                        "Queue storage is empty",
                        identity,
                        null
                    )
                }
                is QueueStorageState.Corrupt -> {
                    return@synchronized DeliveryPrequalificationResult.StorageUnverifiable(
                        "Queue storage is corrupt: ${queueState.reason}"
                    )
                }
                is QueueStorageState.UnsupportedSchema -> {
                    return@synchronized DeliveryPrequalificationResult.StorageUnverifiable(
                        "Queue storage has unsupported schema version ${queueState.foundVersion} (expected ${queueState.expectedVersion})"
                    )
                }
                is QueueStorageState.ValidWithItems -> {
                    val head = queueState.items.firstOrNull()
                    if (head == null) {
                        return@synchronized DeliveryPrequalificationResult.NotQueueHead(
                            "Queue storage has no items",
                            identity,
                            null
                        )
                    }
                    val matchesHead = head.kind == identity.kind &&
                        head.alarmId == identity.alarmId &&
                        head.generation == identity.generation &&
                        head.instanceToken == identity.instanceToken &&
                        head.triggerTime == identity.triggerTime

                    if (!matchesHead) {
                        return@synchronized DeliveryPrequalificationResult.NotQueueHead(
                            "Intent identity does not match queue head: head is (kind=${head.kind}, alarmId=${head.alarmId}, gen=${head.generation}, token='${head.instanceToken}', trigger=${head.triggerTime})",
                            identity,
                            head
                        )
                    }
                    head
                }
            }

            // 2. Inspect Delivery Ledger Storage
            val ledgerState = DirectBootDeliveryLedger.inspectStorageState(context)
            when (ledgerState) {
                is LedgerStorageState.Corrupt -> {
                    return@synchronized DeliveryPrequalificationResult.StorageUnverifiable(
                        "Delivery ledger is corrupt: ${ledgerState.reason}"
                    )
                }
                is LedgerStorageState.UnsupportedSchema -> {
                    return@synchronized DeliveryPrequalificationResult.StorageUnverifiable(
                        "Delivery ledger has unsupported schema version ${ledgerState.foundVersion} (expected ${ledgerState.expectedVersion})"
                    )
                }
                is LedgerStorageState.ValidWithRecords -> {
                    val targetRecordId = DirectBootDeliveryRecord.computeRecordId(
                        kind = identity.kind,
                        alarmId = identity.alarmId,
                        triggerTime = identity.triggerTime,
                        generation = identity.generation,
                        instanceToken = identity.instanceToken
                    )
                    val isDuplicate = ledgerState.records.any { it.recordId == targetRecordId }
                    if (isDuplicate) {
                        return@synchronized DeliveryPrequalificationResult.DuplicateDelivery(
                            "Record '$targetRecordId' already exists in delivery ledger",
                            targetRecordId
                        )
                    }
                    if (ledgerState.records.size >= DirectBootDeliveryLedger.MAX_CAPACITY) {
                        return@synchronized DeliveryPrequalificationResult.CapacityExhausted(
                            "Delivery ledger capacity exhausted (${ledgerState.records.size}/${DirectBootDeliveryLedger.MAX_CAPACITY})"
                        )
                    }
                }
                is LedgerStorageState.Absent, is LedgerStorageState.ValidEmpty -> {
                    // Empty ledger is valid and ready for new record
                }
            }

            DeliveryPrequalificationResult.Eligible(
                identity = identity,
                headItem = headItem
            )
        }
    }

    /**
     * Issues an in-process, single-use [DeliveryTicket] for an eligible identity.
     * Enforces active capacity limit, safe arithmetic bounds (overflow protection), and
     * wall-clock rollback fencing.
     *
     * Note: This method does NOT prove prior Queue/Ledger prequalification and does NOT
     * establish durable or cross-process guarantees.
     */
    fun issueTicket(
        identity: DirectBootV2NormalIntentIdentity,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TicketIssueResult {
        synchronized(ticketLock) {
            if (nowEpochMs < lastRecordedWallClockEpochMs) {
                return TicketIssueResult.ClockRollback(
                    "Clock rollback detected during ticket issue: now=$nowEpochMs < lastRecorded=$lastRecordedWallClockEpochMs"
                )
            }
            if (Long.MAX_VALUE - nowEpochMs < ticketTtlMs) {
                return TicketIssueResult.ArithmeticOverflow(
                    "Arithmetic overflow in ticket expiry calculation: nowEpochMs ($nowEpochMs) + ticketTtlMs ($ticketTtlMs) exceeds Long.MAX_VALUE"
                )
            }
            lastRecordedWallClockEpochMs = maxOf(lastRecordedWallClockEpochMs, nowEpochMs)

            // Purge expired tickets before capacity check
            activeTickets.entries.removeIf { (_, ticket) -> ticket.expiresAtEpochMs <= nowEpochMs }

            if (activeTickets.size >= maxActiveTickets) {
                return TicketIssueResult.CapacityExhausted(
                    "Active ticket capacity ($maxActiveTickets) exhausted"
                )
            }

            val ticketId = "ticket_${UUID.randomUUID()}"
            val ticket = DeliveryTicket(
                ticketId = ticketId,
                identity = identity,
                issuedAtEpochMs = nowEpochMs,
                expiresAtEpochMs = nowEpochMs + ticketTtlMs
            )
            activeTickets[ticketId] = ticket
            return TicketIssueResult.Success(ticket)
        }
    }

    /**
     * Validates and atomically consumes a [DeliveryTicket] by ticket ID.
     * Enforces wall-clock rollback detection, single-use consumption, expiration, and identity match.
     * Ticket is consumed immediately upon lookup and is never restored on downstream failure.
     *
     * Note: Validating a ticket does NOT prove that the alarm service started, that a notification
     * was posted, or that audio was played. Authoritative delivery is owned exclusively by
     * [DirectBootDeliveryTransitionEngine.transitionNormal].
     */
    fun validateAndConsumeTicket(
        ticketId: String,
        expectedIdentity: DirectBootV2NormalIntentIdentity,
        nowEpochMs: Long = System.currentTimeMillis()
    ): TicketValidationResult {
        synchronized(ticketLock) {
            if (nowEpochMs < lastRecordedWallClockEpochMs) {
                return TicketValidationResult.ClockRollback(
                    "Clock rollback detected during ticket validation: now=$nowEpochMs < lastRecorded=$lastRecordedWallClockEpochMs"
                )
            }
            lastRecordedWallClockEpochMs = maxOf(lastRecordedWallClockEpochMs, nowEpochMs)

            // Atomically remove ticket (single-use)
            val ticket = activeTickets.remove(ticketId)
                ?: return TicketValidationResult.NotFound("Ticket '$ticketId' not found or already consumed")

            if (ticket.expiresAtEpochMs <= nowEpochMs) {
                return TicketValidationResult.Expired(
                    "Ticket '$ticketId' expired at ${ticket.expiresAtEpochMs} (now=$nowEpochMs)",
                    ticket
                )
            }

            val identityMatches = ticket.identity.alarmId == expectedIdentity.alarmId &&
                ticket.identity.generation == expectedIdentity.generation &&
                ticket.identity.instanceToken == expectedIdentity.instanceToken &&
                ticket.identity.triggerTime == expectedIdentity.triggerTime &&
                (ticket.identity.restoreEpoch == 0L || expectedIdentity.restoreEpoch == 0L || ticket.identity.restoreEpoch == expectedIdentity.restoreEpoch)

            if (!identityMatches) {
                return TicketValidationResult.IdentityMismatch(
                    "Ticket identity (${ticket.identity}) does not match expected ($expectedIdentity)",
                    ticket
                )
            }

            return TicketValidationResult.Valid(ticket)
        }
    }

    companion object {
        const val EXTRA_TICKET_ID = "direct_boot_ticket_id"
        const val DEFAULT_TICKET_TTL_MS = 15_000L
        const val DEFAULT_MAX_ACTIVE_TICKETS = 16
    }
}

/**
 * Result hierarchy for [DirectBootDeliveryAuthorizer.preQualify].
 */
sealed class DeliveryPrequalificationResult {
    abstract val status: PrequalificationStatus

    data class Eligible(
        val identity: DirectBootV2NormalIntentIdentity,
        val headItem: DirectBootQueueItem
    ) : DeliveryPrequalificationResult() {
        override val status: PrequalificationStatus = PrequalificationStatus.ELIGIBLE
    }

    data class InvalidIdentity(
        val reason: String
    ) : DeliveryPrequalificationResult() {
        override val status: PrequalificationStatus = PrequalificationStatus.INVALID_IDENTITY
    }

    data class NotQueueHead(
        val reason: String,
        val identity: DirectBootV2NormalIntentIdentity,
        val headItem: DirectBootQueueItem?
    ) : DeliveryPrequalificationResult() {
        override val status: PrequalificationStatus = PrequalificationStatus.NOT_QUEUE_HEAD
    }

    data class DuplicateDelivery(
        val reason: String,
        val recordId: String
    ) : DeliveryPrequalificationResult() {
        override val status: PrequalificationStatus = PrequalificationStatus.DUPLICATE_DELIVERY
    }

    data class CapacityExhausted(
        val reason: String
    ) : DeliveryPrequalificationResult() {
        override val status: PrequalificationStatus = PrequalificationStatus.CAPACITY_EXHAUSTED
    }

    data class StorageUnverifiable(
        val reason: String
    ) : DeliveryPrequalificationResult() {
        override val status: PrequalificationStatus = PrequalificationStatus.STORAGE_UNVERIFIABLE
    }

    data class ExpiredOrStale(
        val reason: String,
        val identity: DirectBootV2NormalIntentIdentity? = null
    ) : DeliveryPrequalificationResult() {
        override val status: PrequalificationStatus = PrequalificationStatus.EXPIRED_OR_STALE
    }
}

enum class PrequalificationStatus {
    ELIGIBLE,
    INVALID_IDENTITY,
    NOT_QUEUE_HEAD,
    DUPLICATE_DELIVERY,
    CAPACITY_EXHAUSTED,
    STORAGE_UNVERIFIABLE,
    EXPIRED_OR_STALE,
    UNKNOWN
}

data class DeliveryTicket(
    val ticketId: String,
    val identity: DirectBootV2NormalIntentIdentity,
    val issuedAtEpochMs: Long,
    val expiresAtEpochMs: Long
)

sealed class TicketIssueResult {
    data class Success(val ticket: DeliveryTicket) : TicketIssueResult()
    data class CapacityExhausted(val reason: String) : TicketIssueResult()
    data class ClockRollback(val reason: String) : TicketIssueResult()
    data class ArithmeticOverflow(val reason: String) : TicketIssueResult()
}

sealed class TicketValidationResult {
    data class Valid(val ticket: DeliveryTicket) : TicketValidationResult()
    data class NotFound(val reason: String) : TicketValidationResult()
    data class Expired(val reason: String, val ticket: DeliveryTicket) : TicketValidationResult()
    data class IdentityMismatch(val reason: String, val ticket: DeliveryTicket) : TicketValidationResult()
    data class ClockRollback(val reason: String) : TicketValidationResult()
}
