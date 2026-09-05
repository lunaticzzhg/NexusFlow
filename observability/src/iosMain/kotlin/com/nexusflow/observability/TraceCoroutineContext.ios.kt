package com.nexusflow.observability

import kotlin.coroutines.CoroutineContext

actual class TraceContextElement actual constructor(
    actual val traceId: TraceId,
) : CoroutineContext.Element {
    actual companion object Key : CoroutineContext.Key<TraceContextElement>

    actual override val key: CoroutineContext.Key<TraceContextElement>
        get() = Key
}
