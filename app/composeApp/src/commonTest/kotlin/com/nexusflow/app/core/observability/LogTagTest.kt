package com.nexusflow.app.core.observability

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogTagTest {
    @Test
    fun preservesValidTagExactly() {
        assertEquals("Startup_2", LogTag.of("Startup_2").value)
    }

    @Test
    fun rejectsInvalidTags() {
        listOf("", "2startup", "startup-tag", "启动", "a".repeat(24)).forEach { value ->
            assertFailsWith<IllegalArgumentException> { LogTag.of(value) }
        }
    }
}
