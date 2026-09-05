package com.nexusflow.backend.core.persistence

import com.nexusflow.backend.core.config.BackendRuntimeConfig
import com.nexusflow.backend.core.observability.backendStructuredLogger
import com.nexusflow.observability.StructuredLogger
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import org.flywaydb.core.Flyway

fun Application.configureDatabaseDependencies() {
    dependencies {
        provide { BackendRuntimeConfig.fromEnvironment() }
        provide<StructuredLogger> {
            backendStructuredLogger(resolve<BackendRuntimeConfig>().logging)
        }
        provide<HikariDataSource> {
            resolve<BackendRuntimeConfig>().dataSource()
        }
        provide {
            Flyway.configure()
                .dataSource(resolve<HikariDataSource>())
                .load()
        }
    }
}

private fun BackendRuntimeConfig.dataSource(): HikariDataSource = HikariDataSource(HikariConfig().apply {
    jdbcUrl = databaseUrl
    username = databaseUser
    password = databasePassword
    maximumPoolSize = 8
    minimumIdle = 1
    poolName = "nexusflow-backend"
})
