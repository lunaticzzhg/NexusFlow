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
    override fun namespace(name: String): SecureStoreNamespace = EmptySecureStoreNamespace
}

private object EmptySecureStoreNamespace : SecureStoreNamespace {
    override suspend fun read(key: SecureKey): String? = null

    override suspend fun write(
        key: SecureKey,
        value: String,
    ) = Unit

    override suspend fun remove(key: SecureKey) = Unit

    override suspend fun clear() = Unit
}
