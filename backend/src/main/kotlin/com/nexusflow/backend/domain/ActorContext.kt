package com.nexusflow.backend.domain

/**
 * Verified identity passed into every application use case.
 *
 * HTTP adapters must create this from a validated OIDC token; user identity
 * must never be accepted from a task request body.
 */
data class ActorContext(
    val tenantId: String,
    val userId: String,
    val scopes: Set<String> = emptySet(),
) {
    fun hasScope(scope: String): Boolean = scope in scopes
}
