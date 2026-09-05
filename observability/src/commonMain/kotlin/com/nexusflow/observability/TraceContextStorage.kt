package com.nexusflow.observability

expect object TraceContextStorage {
    fun current(): TraceId?

    fun replace(traceId: TraceId?): TraceId?
}
