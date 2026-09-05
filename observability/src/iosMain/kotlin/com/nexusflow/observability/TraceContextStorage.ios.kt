package com.nexusflow.observability

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
actual object TraceContextStorage {
    private var current: TraceId? = null

    actual fun current(): TraceId? = current

    actual fun replace(traceId: TraceId?): TraceId? {
        val previous = current
        current = traceId
        return previous
    }
}
