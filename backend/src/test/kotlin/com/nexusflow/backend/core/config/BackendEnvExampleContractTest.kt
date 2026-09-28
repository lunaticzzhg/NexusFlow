package com.nexusflow.backend.core.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class BackendEnvExampleContractTest {
    private val repoRoot: Path = findRepoRoot()

    @Test
    fun `backend reads process environment only at startup config boundaries`() {
        val allowed = setOf(
            "backend/src/main/kotlin/com/nexusflow/backend/core/config/BackendRuntimeConfig.kt",
            "backend/src/main/kotlin/com/nexusflow/backend/bootstrap/BackendRuntimeProfile.kt",
        )
        val offenders = Files.walk(repoRoot.resolve("backend/src/main/kotlin")).use { files ->
            files.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.readText().contains("System.getenv(") }
                .map { repoRoot.relativize(it).toString() }
                .filter { it !in allowed }
                .toList()
        }

        assertTrue(offenders.isEmpty(), "Direct environment reads outside startup config: ${offenders.joinToString()}")
    }

    @Test
    fun `env example documents every backend runtime environment key once`() {
        val sourceKeys = backendRuntimeSourceKeys()
        val envExampleKeys = envExampleKeys()

        assertTrue(
            sourceKeys.all(envExampleKeys::contains),
            "Missing .env.example keys: ${(sourceKeys - envExampleKeys).sorted().joinToString()}",
        )
    }

    @Test
    fun `env example does not duplicate keys`() {
        val keyCounts = envExampleLines()
            .mapNotNull(::envExampleKey)
            .groupingBy { it }
            .eachCount()
        val duplicates = keyCounts.filterValues { it > 1 }.keys.sorted()

        assertTrue(duplicates.isEmpty(), "Duplicate .env.example keys: ${duplicates.joinToString()}")
    }

    private fun backendRuntimeSourceKeys(): Set<String> {
        val environmentLookup = Regex("""environment\["([A-Z][A-Z0-9_]*)"]""")
        val environmentArgument = Regex("""\b[a-zA-Z][A-Za-z0-9]*\(environment,\s*"([A-Z][A-Z0-9_]*)"""")
        return listOf(
            repoRoot.resolve("backend/src/main/kotlin/com/nexusflow/backend/core/config/BackendRuntimeConfig.kt"),
            repoRoot.resolve("backend/src/main/kotlin/com/nexusflow/backend/bootstrap/BackendRuntimeProfile.kt"),
        ).flatMap { source ->
            val text = source.readText()
            environmentLookup.findAll(text).map { it.groupValues[1] } +
                environmentArgument.findAll(text).map { it.groupValues[1] }
        }.toSet()
    }

    private fun envExampleKeys(): Set<String> =
        envExampleLines()
            .mapNotNull(::envExampleKey)
            .toSet()

    private fun envExampleLines(): List<String> =
        repoRoot.resolve(".env.example")
            .readText()
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .toList()

    private fun envExampleKey(line: String): String? {
        val candidate = line.removePrefix("#").trim()
        val key = candidate.substringBefore("=", missingDelimiterValue = "")
        return key.takeIf { ENV_KEY.matches(it) }
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        while (current.parent != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent
        }
        error("Could not find repository root")
    }

    private companion object {
        val ENV_KEY = Regex("""[A-Z][A-Z0-9_]*""")
    }
}
