package com.nexusflow.backend.core.observability

import com.nexusflow.observability.LogFieldsBuilder

data class OperationLogContext(
    val operationType: String,
    val operationId: String,
    val branch: String? = null,
    val stage: String? = null,
)

fun LogFieldsBuilder.addOperationFields(
    context: OperationLogContext?,
    step: String? = null,
    outcome: String? = null,
) {
    if (context == null) return
    "operation_type" value context.operationType
    "operation_id" value context.operationId
    "branch" value context.branch
    "stage" value context.stage
    "step" value step
    "outcome" value outcome
}
