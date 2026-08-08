package com.nexusflow.backend.bootstrap

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.identity.ActorResolver
import io.ktor.server.application.ApplicationCall

/**
 * Test-only adapter. Keeping it behind this port prevents controllers from
 * trusting request body identity while production uses a verified bearer token.
 */
object TestActorResolver : ActorResolver {
    override fun resolve(call: ApplicationCall): ActorContext = ActorContext(
        tenantId = call.request.headers["X-Orbit-Tenant"] ?: "local-tenant",
        userId = call.request.headers["X-Orbit-User"] ?: "local-user",
        scopes = setOf("orbit.tasks.read", "orbit.tasks.write"),
    )
}
