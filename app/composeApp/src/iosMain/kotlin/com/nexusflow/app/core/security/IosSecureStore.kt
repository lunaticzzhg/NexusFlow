package com.nexusflow.app.core.security

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Atomic Keychain operations implemented by the iOS host. */
interface IosKeychainExecutor {
    fun read(
        namespace: String,
        key: String,
    ): IosKeychainReadResult

    fun write(
        namespace: String,
        key: String,
        value: String,
    ): IosKeychainOperationResult

    fun remove(
        namespace: String,
        key: String,
    ): IosKeychainOperationResult

    fun clear(namespace: String): IosKeychainOperationResult
}

enum class IosKeychainStatus {
    SUCCESS,
    NOT_FOUND,
    FAILURE,
}

class IosKeychainReadResult(
    val status: IosKeychainStatus,
    val value: String?,
)

class IosKeychainOperationResult(
    val status: IosKeychainStatus,
)

/** Persists App secrets through the native iOS Keychain adapter. */
class IosSecureStore(
    private val keychain: IosKeychainExecutor,
) : SecureStore {
    override fun namespace(name: String): SecureStoreNamespace = IosSecureStoreNamespace(keychain, validateSecureNamespace(name))
}

private class IosSecureStoreNamespace(
    private val keychain: IosKeychainExecutor,
    private val namespace: String,
) : SecureStoreNamespace {
    override suspend fun read(key: SecureKey): String? =
        secureStorageCall {
            withContext(Dispatchers.Default) {
                val result = keychain.read(namespace, key.name)
                when (result.status) {
                    IosKeychainStatus.SUCCESS -> result.value ?: throw SecureStoreUnavailableException()
                    IosKeychainStatus.NOT_FOUND -> null
                    IosKeychainStatus.FAILURE -> throw SecureStoreUnavailableException()
                }
            }
        }

    override suspend fun write(
        key: SecureKey,
        value: String,
    ) {
        secureStorageCall {
            withContext(Dispatchers.Default) {
                requireSuccess(keychain.write(namespace, key.name, value))
            }
        }
    }

    override suspend fun remove(key: SecureKey) {
        secureStorageCall {
            withContext(Dispatchers.Default) {
                requireSuccess(keychain.remove(namespace, key.name), allowNotFound = true)
            }
        }
    }

    override suspend fun clear() {
        secureStorageCall {
            withContext(Dispatchers.Default) {
                requireSuccess(keychain.clear(namespace), allowNotFound = true)
            }
        }
    }

    private fun requireSuccess(
        result: IosKeychainOperationResult,
        allowNotFound: Boolean = false,
    ) {
        if (result.status != IosKeychainStatus.SUCCESS && !(allowNotFound && result.status == IosKeychainStatus.NOT_FOUND)) {
            throw SecureStoreUnavailableException()
        }
    }
}
