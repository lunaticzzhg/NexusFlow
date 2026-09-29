package com.nexusflow.backend.core.external

/** One admission per interval in this process. A caller denied admission can use another source. */
class RequestIntervalGate(
    private val intervalNanos: Long = 1_000_000_000L,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private var lastAdmission: Long? = null

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = nanoTime()
        val last = lastAdmission
        if (last != null && now - last < intervalNanos) return false
        lastAdmission = now
        return true
    }
}

fun String.hasPublicSourceContact(): Boolean =
    contains(Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)) ||
        contains(Regex("https?://[^\\s)]+", RegexOption.IGNORE_CASE))

fun requirePublicSourceContact(userAgent: String, provider: String, operation: String) {
    if (!userAgent.hasPublicSourceContact()) throw ExternalSourceDisabledException(provider, operation)
}
