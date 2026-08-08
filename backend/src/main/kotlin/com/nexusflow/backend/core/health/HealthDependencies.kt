package com.nexusflow.backend.core.health

import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies

fun Application.configureProductionHealthDependencies() {
    dependencies {
        provide<ReadinessProbe> { HikariReadinessProbe(resolve<HikariDataSource>()) }
    }
}

fun Application.configureTestHealthDependencies() {
    dependencies {
        provide<ReadinessProbe> { AlwaysReadyProbe }
    }
}
