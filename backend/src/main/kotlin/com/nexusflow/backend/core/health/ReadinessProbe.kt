package com.nexusflow.backend.core.health

/**
 * Answers whether this process can accept traffic that depends on its required runtime services.
 * Implementations return a boolean so readiness endpoints never disclose dependency internals.
 */
fun interface ReadinessProbe {
    fun isReady(): Boolean
}

object AlwaysReadyProbe : ReadinessProbe {
    override fun isReady(): Boolean = true
}
