package com.nexusflow.app.feature.auth.di

import com.nexusflow.app.core.network.FirstPartyApiSession
import com.nexusflow.app.feature.auth.data.AuthRemoteDataSource
import com.nexusflow.app.feature.auth.data.AuthSessionStore
import com.nexusflow.app.feature.auth.data.DefaultAuthRepository
import com.nexusflow.app.feature.auth.data.createAuthApi
import com.nexusflow.app.feature.auth.domain.AuthRepository
import com.nexusflow.app.feature.auth.observability.AppLoggerAuthDiagnosticReporter
import com.nexusflow.app.feature.auth.observability.AuthDiagnosticReporter
import com.nexusflow.app.feature.auth.presentation.AuthSessionController
import de.jensklingenberg.ktorfit.Ktorfit
import org.koin.dsl.module

/** Authentication feature dependencies. Feature-local API and remote types remain private here. */
val authModule =
    module {
        single { AuthSessionStore(get()) }
        single<AuthRepository> {
            DefaultAuthRepository(
                authRemoteDataSource =
                    AuthRemoteDataSource(
                        api = get<Ktorfit>().createAuthApi(),
                        apiCalls = get(),
                    ),
                clock = get(),
            )
        }
        single<AuthDiagnosticReporter> { AppLoggerAuthDiagnosticReporter(get()) }
        single { AuthSessionController(get(), get(), get(), get(), get()) }
        single<FirstPartyApiSession> { get<AuthSessionController>() }
    }
