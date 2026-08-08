package com.nexusflow.backend.feature.auth.api

import com.nexusflow.backend.core.identity.ActorContext
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.core.identity.UnauthenticatedException
import com.nexusflow.backend.feature.auth.domain.AccessTokenVerifier
import com.nexusflow.backend.feature.auth.domain.IdentitySessionRepository
import com.nexusflow.backend.feature.auth.domain.InvalidAccessTokenException
import io.ktor.server.application.ApplicationCall
import java.time.Clock

class BearerActorResolver(
    private val accessTokenVerifier: AccessTokenVerifier,
    private val sessions: IdentitySessionRepository,
    private val clock: Clock = Clock.systemUTC(),
) : ActorResolver {
    override fun resolve(call: ApplicationCall): ActorContext {
        val token = call.request.headers["Authorization"]
            ?.takeIf { it.startsWith("Bearer ") }
            ?.removePrefix("Bearer ")
            ?.takeIf(String::isNotBlank)
            ?: throw UnauthenticatedException()
        return try {
            accessTokenVerifier.verify(token).let { verified ->
                if (!sessions.isSessionActive(verified.sessionId, clock.instant())) throw UnauthenticatedException()
                verified.actor
            }
        } catch (_: InvalidAccessTokenException) {
            throw UnauthenticatedException()
        }
    }
}
