package com.nexusflow.observability

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceIdTest {
    @Test
    fun `validates required lowercase hex trace id shape`() {
        assertTrue(TraceId.isValid("4bf92f3577b34da6a3ce929d0e0e4736"))
        assertFalse(TraceId.isValid("4bf92f35-7b34-da6a-3ce9-29d0e0e4736"))
        assertFalse(TraceId.isValid("4bf92f3577b34da6a3ce929d0e0e473"))
        assertFalse(TraceId.isValid("4bf92f3577b34da6a3ce929d0e0e47361"))
        assertFalse(TraceId.isValid("4bf92f3577b34da6a3ce929d0e0e473g"))
        assertFalse(TraceId.isValid("00000000000000000000000000000000"))
    }

    @Test
    fun `generator creates valid non-zero trace ids`() {
        repeat(100) {
            assertTrue(TraceId.isValid(RandomTraceIdGenerator.newTraceId().value))
        }
    }
}
