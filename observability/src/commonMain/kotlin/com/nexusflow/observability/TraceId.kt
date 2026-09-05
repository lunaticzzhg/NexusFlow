package com.nexusflow.observability

import kotlin.jvm.JvmInline
import kotlin.random.Random

@JvmInline
value class TraceId private constructor(
    val value: String,
) {
    companion object {
        const val Length = 32
        const val Zero = "00000000000000000000000000000000"

        fun parse(value: String): TraceId? =
            value.takeIf(::isValid)?.let(::TraceId)

        fun requireValid(value: String): TraceId =
            parse(value) ?: error("traceId must be 32 lowercase hex characters and non-zero")

        fun isValid(value: String): Boolean =
            value.length == Length &&
                value != Zero &&
                value.all { character -> character in '0'..'9' || character in 'a'..'f' }
    }
}

object TraceHeaders {
    const val TraceId = "X-Trace-Id"
}

fun interface TraceIdGenerator {
    fun newTraceId(): TraceId
}

object RandomTraceIdGenerator : TraceIdGenerator {
    override fun newTraceId(): TraceId {
        while (true) {
            val bytes = Random.Default.nextBytes(TraceId.Length / 2)
            val value = buildString(TraceId.Length) {
                bytes.forEach { byte ->
                    val unsigned = byte.toInt() and 0xff
                    append(HEX[unsigned ushr 4])
                    append(HEX[unsigned and 0x0f])
                }
            }
            TraceId.parse(value)?.let { return it }
        }
    }

    private const val HEX = "0123456789abcdef"
}

fun interface TraceContext {
    fun currentTraceId(): TraceId?
}

object EmptyTraceContext : TraceContext {
    override fun currentTraceId(): TraceId? = null
}
