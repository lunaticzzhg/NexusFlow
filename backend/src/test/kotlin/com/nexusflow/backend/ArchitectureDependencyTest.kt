package com.nexusflow.backend

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue

class ArchitectureDependencyTest {
    private val repoRoot: Path = findRepoRoot()

    @Test
    fun `app does not depend on backend ai contracts`() {
        assertNoMatch(
            relativePath = "app/composeApp/src",
            forbidden = Regex("""com\.nexusflow\.contracts\.backendai"""),
            reason = "App may only use App-Backend contracts.",
        )
    }

    @Test
    fun `ai does not depend on app backend contracts`() {
        assertNoMatch(
            relativePath = "ai/src",
            forbidden = Regex("""com\.nexusflow\.contracts\.appbackend"""),
            reason = "AI may only use Backend-AI contracts.",
        )
    }

    @Test
    fun `backend domain does not depend on contracts`() {
        assertNoMatch(
            relativePath = "backend/src/main/kotlin/com/nexusflow/backend/feature/task/domain",
            forbidden = Regex("""com\.nexusflow\.contracts\."""),
            reason = "Backend domain models must remain independent from wire contracts.",
        )
    }

    @Test
    fun `contracts do not import implementation modules`() {
        assertNoMatch(
            relativePath = "contracts",
            forbidden = Regex("""com\.nexusflow\.(app|backend|ai)\."""),
            reason = "Contracts must not depend on App, Backend, or AI implementation packages.",
        )
    }

    @Test
    fun `backend does not import provider specific ai packages`() {
        assertNoMatch(
            relativePath = "backend/src",
            forbidden = Regex("""com\.nexusflow\.ai\.provider\.(openai|qwen|deepseek)"""),
            reason = "Provider-specific AI selection belongs in the AI module composition boundary.",
        )
    }

    private fun assertNoMatch(
        relativePath: String,
        forbidden: Regex,
        reason: String,
    ) {
        val offenders = kotlinFiles(repoRoot.resolve(relativePath))
            .filter { file -> forbidden.containsMatchIn(file.readText()) }
            .map { file -> repoRoot.relativize(file).toString() }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "$reason Offending files: ${offenders.joinToString()}",
        )
    }

    private fun kotlinFiles(root: Path): Sequence<Path> {
        if (!root.isDirectory()) return emptySequence()
        return Files.walk(root)
            .use { stream ->
                stream
                    .filter { file -> Files.isRegularFile(file) && file.name.endsWith(".kt") }
                    .toList()
            }.asSequence()
    }

    private fun findRepoRoot(): Path {
        var current = Path.of("").toAbsolutePath()
        while (current.parent != null) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) return current
            current = current.parent
        }
        error("Could not find repository root")
    }
}
