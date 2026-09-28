package com.nexusflow.backend.bootstrap

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BackendRuntimeProfileTest {
    @Test
    fun `missing profile defaults to production`() {
        assertEquals(BackendRuntimeProfile.Production, BackendRuntimeProfile.fromEnvironment(emptyMap()))
    }

    @Test
    fun `documented profile values are accepted`() {
        assertEquals(BackendRuntimeProfile.Production, profile("local"))
        assertEquals(BackendRuntimeProfile.Test, profile("test"))
        assertEquals(BackendRuntimeProfile.Production, profile("prod"))
        assertEquals(BackendRuntimeProfile.Production, profile("production"))
    }

    @Test
    fun `blank and unknown explicit profile values fail`() {
        assertFailsWith<IllegalStateException> {
            profile("")
        }
        assertFailsWith<IllegalStateException> {
            profile("development")
        }
    }

    private fun profile(value: String): BackendRuntimeProfile =
        BackendRuntimeProfile.fromEnvironment(mapOf("ORBIT_RUNTIME_PROFILE" to value))
}
