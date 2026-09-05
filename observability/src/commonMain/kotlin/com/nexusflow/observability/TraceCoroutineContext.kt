package com.nexusflow.observability

import kotlin.coroutines.CoroutineContext

expect class TraceContextElement(
    traceId: TraceId,
) : CoroutineContext.Element {
    val traceId: TraceId
    override val key: CoroutineContext.Key<TraceContextElement>

    companion object Key : CoroutineContext.Key<TraceContextElement>
}

object CoroutineTraceContext : TraceContext {
    override fun currentTraceId(): TraceId? = TraceContextStorage.current()
}
