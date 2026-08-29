package com.nexusflow.backend.test

import org.junit.AssumptionViolatedException

object PostgresTestGate {
    private const val RequiredPostgresTests = "NEXUSFLOW_REQUIRE_POSTGRES_TESTS"

    fun unavailable(
        testFamily: String,
        cause: IllegalStateException,
    ): Nothing {
        val message = "Docker is not available for $testFamily PostgreSQL integration tests"
        if (System.getenv(RequiredPostgresTests).equals("true", ignoreCase = true)) {
            throw AssertionError("$message; $RequiredPostgresTests=true requires these tests to run", cause)
        }
        throw AssumptionViolatedException(message, cause)
    }
}
