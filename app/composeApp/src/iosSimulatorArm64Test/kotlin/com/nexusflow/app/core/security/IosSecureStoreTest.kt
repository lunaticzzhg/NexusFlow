package com.nexusflow.app.core.security

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class IosSecureStoreTest {
    @Test
    fun namespaceUsesAnIndependentKeychainServiceAndClearDoesNotAffectOtherNamespaces() =
        runBlocking {
            val keychain = FakeKeychainExecutor()
            val store = IosSecureStore(keychain)
            val token = SecureKeys.string("token")

            store.namespace("auth").write(token, "auth-token")
            store.namespace("profile").write(token, "profile-token")
            store.namespace("auth").clear()

            assertEquals(null, store.namespace("auth").read(token))
            assertEquals("profile-token", store.namespace("profile").read(token))
            assertEquals(listOf("auth"), keychain.clearedNamespaces)
        }

    private class FakeKeychainExecutor : IosKeychainExecutor {
        private val values = mutableMapOf<Pair<String, String>, String>()
        val clearedNamespaces = mutableListOf<String>()

        override fun read(
            namespace: String,
            key: String,
        ): IosKeychainReadResult =
            values[namespace to key]?.let { value ->
                IosKeychainReadResult(IosKeychainStatus.SUCCESS, value)
            } ?: IosKeychainReadResult(IosKeychainStatus.NOT_FOUND, null)

        override fun write(
            namespace: String,
            key: String,
            value: String,
        ): IosKeychainOperationResult {
            values[namespace to key] = value
            return IosKeychainOperationResult(IosKeychainStatus.SUCCESS)
        }

        override fun remove(
            namespace: String,
            key: String,
        ): IosKeychainOperationResult {
            values.remove(namespace to key)
            return IosKeychainOperationResult(IosKeychainStatus.SUCCESS)
        }

        override fun clear(namespace: String): IosKeychainOperationResult {
            values.keys.filter { (storedNamespace, _) -> storedNamespace == namespace }.forEach(values::remove)
            clearedNamespaces += namespace
            return IosKeychainOperationResult(IosKeychainStatus.SUCCESS)
        }
    }
}
