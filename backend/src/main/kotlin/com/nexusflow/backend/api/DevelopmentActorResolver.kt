package com.nexusflow.backend.api

import com.nexusflow.backend.domain.ActorContext
import io.ktor.server.application.ApplicationCall

/**
 * Local-only adapter until the Keycloak OIDC adapter is connected. Keeping it
 * behind this port prevents controllers from trusting request body identity.
 */
class DevelopmentActorResolver(runtimeProfile: String) {
    init {
        require(runtimeProfile in setOf("local", "test")) {
            "Development identity resolver is forbidden outside local/test; configure the OIDC resolver instead"
        }
    }

    fun resolve(call: ApplicationCall): ActorContext = ActorContext(
        tenantId = call.request.headers["X-Orbit-Tenant"] ?: "local-tenant",
        userId = call.request.headers["X-Orbit-User"] ?: "local-user",
        scopes = setOf("orbit.tasks.read", "orbit.tasks.write"),
    )
}
