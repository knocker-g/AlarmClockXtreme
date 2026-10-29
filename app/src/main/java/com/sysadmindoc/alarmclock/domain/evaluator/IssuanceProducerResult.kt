package com.sysadmindoc.alarmclock.domain.evaluator

import com.sysadmindoc.alarmclock.data.local.entity.IssueSlot
import com.sysadmindoc.alarmclock.data.local.entity.IssuedOccurrence

/**
 * Result value model returned by [OccurrenceIssuanceProducer] (KNO-154 Phase 0C-B Subtask 2).
 */
data class IssuanceProducerResult(
    val outcome: IssuanceProducerOutcome,
    val alarmId: Long,
    val issuedOccurrence: IssuedOccurrence?,
    val issueSlot: IssueSlot?,
    val decisionReason: String = ""
)
