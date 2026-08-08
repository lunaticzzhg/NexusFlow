package com.nexusflow.backend.feature.auth

import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.core.identity.ActorResolver
import com.nexusflow.backend.feature.auth.api.BearerActorResolver
import com.nexusflow.backend.feature.auth.application.AuthService
import com.nexusflow.backend.feature.auth.domain.AccessTokenIssuer
import com.nexusflow.backend.feature.auth.domain.AccessTokenVerifier
import com.nexusflow.backend.feature.auth.domain.GoogleIdentityVerifier
import com.nexusflow.backend.feature.auth.domain.IdentitySessionRepository
import com.nexusflow.backend.feature.auth.infrastructure.GoogleJwtIdentityVerifier
import com.nexusflow.backend.feature.auth.infrastructure.JdbcIdentitySessionRepository
import com.nexusflow.backend.feature.auth.infrastructure.JwtAccessTokenCodec
import com.nexusflow.backend.feature.auth.infrastructure.accessTokenCodec
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureAuthDependencies() {
    dependencies {
        provide<GoogleIdentityVerifier> {
            GoogleJwtIdentityVerifier(resolve<BackendRuntimeConfig>().googleAllowedAudiences)
        }
        provide<IdentitySessionRepository> {
            JdbcIdentitySessionRepository(resolve<HikariDataSource>())
        }
        provide<JwtAccessTokenCodec> {
            resolve<BackendRuntimeConfig>().accessTokenCodec()
        }
        provide<AccessTokenIssuer> {
            resolve<JwtAccessTokenCodec>()
        }
        provide<AccessTokenVerifier> {
            resolve<JwtAccessTokenCodec>()
        }
        provide {
            val config = resolve<BackendRuntimeConfig>()
            AuthService(
                googleIdentityVerifier = resolve(),
                repository = resolve(),
                accessTokenIssuer = resolve(),
                accessLifetime = config.accessLifetime,
                refreshLifetime = config.refreshLifetime,
            )
        }
        provide<ActorResolver> {
            BearerActorResolver(resolve(), resolve())
        }
    }
}
