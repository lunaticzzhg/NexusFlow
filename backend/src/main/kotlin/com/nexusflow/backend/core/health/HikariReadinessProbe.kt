package com.nexusflow.backend.core.health

import com.zaxxer.hikari.HikariDataSource

class HikariReadinessProbe(
    private val dataSource: HikariDataSource,
) : ReadinessProbe {
    override fun isReady(): Boolean = runCatching {
        dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT 1").use { statement ->
                statement.executeQuery().use { result -> result.next() }
            }
        }
    }.getOrDefault(false)
}
