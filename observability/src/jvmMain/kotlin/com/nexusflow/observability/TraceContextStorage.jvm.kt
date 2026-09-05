package com.nexusflow.observability

actual object TraceContextStorage {
    private val current = ThreadLocal<TraceId?>()

    actual fun current(): TraceId? = current.get()

    actual fun replace(traceId: TraceId?): TraceId? {
        val previous = current.get()
        if (traceId == null) {
            current.remove()
        } else {
            current.set(traceId)
        }
        return previous
    }
}
