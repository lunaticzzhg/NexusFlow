package com.nexusflow.backend.core.observability

import com.nexusflow.observability.TraceContext
import com.nexusflow.observability.TraceContextStorage
import com.nexusflow.observability.TraceId
import org.slf4j.MDC

internal object BackendTraceContext : TraceContext {
    override fun currentTraceId(): TraceId? =
        TraceContextStorage.current()
            ?: MDC.get(MDC_TRACE_ID)
                ?.takeIf(TraceId::isValid)
                ?.let(TraceId::requireValid)
}

internal const val MDC_TRACE_ID = "traceId"
