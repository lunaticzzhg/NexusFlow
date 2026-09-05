package com.nexusflow.observability

import kotlinx.coroutines.ThreadContextElement
import kotlin.coroutines.CoroutineContext

actual class TraceContextElement actual constructor(
    actual val traceId: TraceId,
) : ThreadContextElement<TraceId?> {
    actual companion object Key : CoroutineContext.Key<TraceContextElement>

    actual override val key: CoroutineContext.Key<TraceContextElement>
        get() = Key

    override fun updateThreadContext(context: CoroutineContext): TraceId? = TraceContextStorage.replace(traceId)

    override fun restoreThreadContext(
        context: CoroutineContext,
        oldState: TraceId?,
    ) {
        TraceContextStorage.replace(oldState)
    }
}
