package com.nexusflow.app.core.security

import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertSame

class SecureStoreModuleTest {
    @Test
    fun resolvesTheSecureStoreProvidedByTheCompositionRoot() {
        val secureStore = InMemorySecureStore()
        val application = koinApplication { modules(secureStoreModule(secureStore)) }

        try {
            assertSame(secureStore, application.koin.get<SecureStore>())
        } finally {
            application.close()
        }
    }
}

private class InMemorySecureStore : SecureStore {
    override fun read(key: String): String? = null

    override fun write(
        key: String,
        value: String,
    ) = Unit

    override fun remove(key: String) = Unit
}
