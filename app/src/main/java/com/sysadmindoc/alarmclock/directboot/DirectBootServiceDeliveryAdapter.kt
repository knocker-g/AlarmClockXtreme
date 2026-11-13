package com.sysadmindoc.alarmclock.directboot

import android.content.Context
import kotlinx.coroutines.CancellationException

/**
 * Service-side Delivery Adapter coordinating process-local ticket consumption
 * with the authoritative DE Queue-to-Ledger transition.
 *
 * All platform delivery side effects (AlarmManager, notification, audio, vibration,
 * wake locks, reconciler) remain strictly prohibited in this phase.
 */
internal open class DirectBootServiceDeliveryAdapter(
    private val context: Context,
    private val authorizer: DirectBootDeliveryAuthorizer,
    private val transitionFunction: (Context, FiredOccurrenceIdentity, Long) -> DeliveryTransitionResult = { ctx, firingId, deliveredAt ->
        DirectBootDeliveryTransitionEngine.transitionNormal(ctx, firingId, deliveredAt)
    },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    open fun adaptDelivery(
        ticketId: String,
        identity: DirectBootV2NormalIntentIdentity,
        deliveredAt: Long = clock()
    ): ServiceDeliveryAdaptationResult {
        // 1. Consume ticket from authorizer
        val ticketValidation = authorizer.validateAndConsumeTicket(
            ticketId = ticketId,
            expectedIdentity = identity,
            nowEpochMs = deliveredAt
        )

        val validTicket = when (ticketValidation) {
            is TicketValidationResult.Valid -> ticketValidation.ticket
            else -> return ServiceDeliveryAdaptationResult.TicketRejected(ticketValidation)
        }

        // 2. Map identity to FiredOccurrenceIdentity
        val firingIdentity = FiredOccurrenceIdentity(
            kind = identity.kind,
            alarmId = identity.alarmId,
            triggerTime = identity.triggerTime,
            generation = identity.generation,
            instanceToken = identity.instanceToken,
            restoreEpoch = identity.restoreEpoch,
            provenance = "V2_INTENT_DELIVERY"
        )

        // 3. Delegate to authoritative transition engine
        val transitionResult = try {
            transitionFunction(context, firingIdentity, deliveredAt)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            return ServiceDeliveryAdaptationResult.TransitionUncertain(
                ticket = validTicket,
                transition = DeliveryTransitionResult.Uncertain.CommitException(
                    details = "Transition threw unexpected exception: ${e.message}",
                    cause = e
                )
            )
        }

        // 4. Map DeliveryTransitionResult 1:1 to ServiceDeliveryAdaptationResult
        return when (transitionResult) {
            is DeliveryTransitionResult.Confirmed -> {
                val req = PostCommitReconciliationRequest(
                    nextCandidateToArm = transitionResult.nextCandidateToArm,
                    remainingQueueCount = transitionResult.remainingQueue.size,
                    triggerReason = "V2_POST_DELIVERY_TRANSITION",
                    timestampEpochMs = deliveredAt
                )
                ServiceDeliveryAdaptationResult.TransitionCommitted(
                    ticket = validTicket,
                    transition = transitionResult,
                    reconciliationRequest = req
                )
            }
            is DeliveryTransitionResult.CancellationAfterCommit -> {
                val req = PostCommitReconciliationRequest(
                    nextCandidateToArm = transitionResult.nextCandidateToArm,
                    remainingQueueCount = transitionResult.remainingQueue.size,
                    triggerReason = "V2_POST_DELIVERY_TRANSITION",
                    timestampEpochMs = deliveredAt
                )
                ServiceDeliveryAdaptationResult.CancellationAfterCommit(
                    ticket = validTicket,
                    transition = transitionResult,
                    reconciliationRequest = req,
                    cancellation = transitionResult.cancellation
                )
            }
            is DeliveryTransitionResult.Rejected -> {
                ServiceDeliveryAdaptationResult.TransitionRejected(
                    ticket = validTicket,
                    transition = transitionResult
                )
            }
            is DeliveryTransitionResult.Uncertain -> {
                ServiceDeliveryAdaptationResult.TransitionUncertain(
                    ticket = validTicket,
                    transition = transitionResult
                )
            }
        }
    }
}

/**
 * Pure data descriptor for post-commit reconciliation intent.
 * Does NOT execute reconciliation or mutate AlarmManager.
 */
data class PostCommitReconciliationRequest(
    val nextCandidateToArm: DirectBootQueueItem?,
    val remainingQueueCount: Int,
    val triggerReason: String = "V2_POST_DELIVERY_TRANSITION",
    val timestampEpochMs: Long = System.currentTimeMillis()
)

/**
 * Sealed hierarchy of adaptation outcomes.
 *
 * Invariant: [audioAuthorized] is strictly false across all outcomes in this phase.
 */
sealed class ServiceDeliveryAdaptationResult {
    abstract val isDurableCommitConfirmed: Boolean
    val audioAuthorized: Boolean get() = false

    data class TicketRejected(
        val reason: TicketValidationResult
    ) : ServiceDeliveryAdaptationResult() {
        override val isDurableCommitConfirmed: Boolean get() = false
    }

    data class TransitionRejected(
        val ticket: DeliveryTicket,
        val transition: DeliveryTransitionResult.Rejected
    ) : ServiceDeliveryAdaptationResult() {
        override val isDurableCommitConfirmed: Boolean get() = false
    }

    data class TransitionUncertain(
        val ticket: DeliveryTicket,
        val transition: DeliveryTransitionResult.Uncertain
    ) : ServiceDeliveryAdaptationResult() {
        override val isDurableCommitConfirmed: Boolean get() = false
    }

    data class TransitionCommitted(
        val ticket: DeliveryTicket,
        val transition: DeliveryTransitionResult.Confirmed,
        val reconciliationRequest: PostCommitReconciliationRequest
    ) : ServiceDeliveryAdaptationResult() {
        override val isDurableCommitConfirmed: Boolean get() = true
    }

    data class CancellationAfterCommit(
        val ticket: DeliveryTicket,
        val transition: DeliveryTransitionResult.CancellationAfterCommit,
        val reconciliationRequest: PostCommitReconciliationRequest,
        val cancellation: CancellationException
    ) : ServiceDeliveryAdaptationResult() {
        override val isDurableCommitConfirmed: Boolean get() = true
    }
}
