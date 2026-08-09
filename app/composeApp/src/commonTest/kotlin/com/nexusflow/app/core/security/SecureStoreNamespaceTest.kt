package com.nexusflow.app.core.security

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SecureStoreNamespaceTest {
    @Test
    fun namespacesAreIsolatedAndClearOnlyRemovesTheSelectedNamespace() =
        runBlocking {
            val store = FakeSecureStore()
            val token = SecureKeys.string("token")
            val auth = store.namespace("auth")
            val profile = store.namespace("profile")

            auth.write(token, "auth-token")
            profile.write(token, "profile-token")
            auth.clear()

            assertEquals(null, auth.read(token))
            assertEquals("profile-token", profile.read(token))
        }

    @Test
    fun invalidNamespaceAndKeyNamesAreRejected() {
        assertFailsWith<IllegalArgumentException> { FakeSecureStore().namespace("Auth") }
        assertFailsWith<IllegalArgumentException> { SecureKeys.string("refresh-token") }
        assertFailsWith<IllegalArgumentException> { SecureKeys.string("1token") }
    }

    @Test
    fun storageFailureIsMappedAndCancellationIsRethrown() =
        runBlocking {
            val key = SecureKeys.string("token")

            assertFailsWith<SecureStoreUnavailableException> {
                FakeSecureStore(failure = IllegalStateException("secret-value")).namespace("auth").read(key)
            }
            assertFailsWith<CancellationException> {
                FakeSecureStore(failure = CancellationException("cancelled")).namespace("auth").read(key)
            }
            Unit
        }

    private class FakeSecureStore(
        private val values: MutableMap<String, String> = mutableMapOf(),
        private val failure: Exception? = null,
    ) : SecureStore {
        override fun namespace(name: String): SecureStoreNamespace =
            FakeSecureStoreNamespace(values, failure, validateSecureNamespace(name))
    }

    private class FakeSecureStoreNamespace(
        private val values: MutableMap<String, String>,
        private val failure: Exception?,
        private val namespace: String,
    ) : SecureStoreNamespace {
        override suspend fun read(key: SecureKey): String? =
            secureStorageCall {
                failIfConfigured()
                values["$namespace.${key.name}"]
            }

        override suspend fun write(
            key: SecureKey,
            value: String,
        ) {
            secureStorageCall {
                failIfConfigured()
                values["$namespace.${key.name}"] = value
            }
        }

        override suspend fun remove(key: SecureKey) {
            secureStorageCall {
                failIfConfigured()
                values.remove("$namespace.${key.name}")
            }
        }

        override suspend fun clear() {
            secureStorageCall {
                failIfConfigured()
                val prefix = "$namespace."
                values.keys.filter { it.startsWith(prefix) }.forEach(values::remove)
            }
        }

        private fun failIfConfigured() {
            failure?.let { throw it }
        }
    }
}
