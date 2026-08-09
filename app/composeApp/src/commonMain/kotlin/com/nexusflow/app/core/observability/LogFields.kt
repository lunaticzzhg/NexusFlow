package com.nexusflow.app.core.observability

/** Immutable structured fields accepted by [AppLogger]. */
class LogFields private constructor(
    internal val values: Map<String, String>,
) {
    companion object {
        val Empty = LogFields(emptyMap())

        internal fun from(values: Map<String, String>): LogFields = LogFields(values)
    }
}

/** Builds safe-to-format values without accepting arbitrary objects or collections. */
fun logFields(block: LogFieldsBuilder.() -> Unit): LogFields = LogFieldsBuilder().apply(block).build()

class LogFieldsBuilder internal constructor() {
    private val values = linkedMapOf<String, String>()

    infix fun String.value(value: String?) = put(value)

    infix fun String.value(value: Boolean?) = put(value?.toString())

    infix fun String.value(value: Byte?) = put(value?.toString())

    infix fun String.value(value: Short?) = put(value?.toString())

    infix fun String.value(value: Int?) = put(value?.toString())

    infix fun String.value(value: Long?) = put(value?.toString())

    infix fun String.value(value: Float?) = put(value?.toString())

    infix fun String.value(value: Double?) = put(value?.toString())

    infix fun String.value(value: UByte?) = put(value?.toString())

    infix fun String.value(value: UShort?) = put(value?.toString())

    infix fun String.value(value: UInt?) = put(value?.toString())

    infix fun String.value(value: ULong?) = put(value?.toString())

    infix fun String.value(value: Enum<*>?) = put(value?.name)

    private fun String.put(value: String?) {
        if (value != null && value.isNotEmpty()) {
            values[this] = value
        }
    }

    internal fun build(): LogFields = LogFields.from(values.toMap())
}
