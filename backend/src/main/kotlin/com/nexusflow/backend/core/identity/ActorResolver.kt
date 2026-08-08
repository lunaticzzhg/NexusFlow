package com.nexusflow.backend.core.identity

import io.ktor.server.application.ApplicationCall

interface ActorResolver {
    fun resolve(call: ApplicationCall): ActorContext
}

class UnauthenticatedException : RuntimeException()
