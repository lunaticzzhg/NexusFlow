package com.nexusflow.backend.bootstrap

import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertTrue

class DependencyLifecycleTest {
    @Test
    fun `DI closes application resources on shutdown`() {
        lateinit var resource: CloseTrackingResource

        testApplication {
            application {
                dependencies {
                    provide<CloseTrackingResource> {
                        CloseTrackingResource().also { resource = it }
                    }
                }
                resolveLifecycleResource()
            }
        }

        assertTrue(resource.closed)
    }
}

private fun Application.resolveLifecycleResource() {
    val resource: CloseTrackingResource by dependencies
    check(!resource.closed)
}

private class CloseTrackingResource : AutoCloseable {
    var closed = false
        private set

    override fun close() {
        closed = true
    }
}
