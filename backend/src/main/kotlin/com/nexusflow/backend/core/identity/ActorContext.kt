package com.nexusflow.backend.core.identity

/**
 * Verified identity passed into every application use case.
 *
 * HTTP adapters must create this from a validated business access token; user identity
 * must never be accepted from a request body.
 */
data class ActorContext(
    val tenantId: String,
    val userId: String,
    val scopes: Set<String> = emptySet(),
) {
    fun hasScope(scope: String): Boolean = scope in scopes
}
