package com.nexusflow.app.core.observability

typealias LogFields = com.nexusflow.observability.LogFields
typealias LogFieldsBuilder = com.nexusflow.observability.LogFieldsBuilder

fun logFields(block: LogFieldsBuilder.() -> Unit): LogFields = com.nexusflow.observability.logFields(block)
