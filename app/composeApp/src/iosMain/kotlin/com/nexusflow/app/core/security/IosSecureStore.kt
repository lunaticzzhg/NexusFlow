package com.nexusflow.app.core.security

/** Atomic Keychain operations implemented by the iOS host. */
interface IosKeychainExecutor {
    fun read(key: String): IosKeychainReadResult

    fun write(
        key: String,
        value: String,
    ): IosKeychainOperationResult

    fun remove(key: String): IosKeychainOperationResult
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

/** Persists App session material through the native iOS Keychain adapter. */
class IosSecureStore(
    private val keychain: IosKeychainExecutor,
) : SecureStore {
    override fun read(key: String): String? {
        val result = keychain.read(key)
        return when (result.status) {
            IosKeychainStatus.SUCCESS -> result.value ?: throw SecureStoreUnavailableException()
            IosKeychainStatus.NOT_FOUND -> null
            IosKeychainStatus.FAILURE -> throw SecureStoreUnavailableException()
        }
    }

    override fun write(
        key: String,
        value: String,
    ) {
        requireSuccess(keychain.write(key, value))
    }

    override fun remove(key: String) {
        requireSuccess(keychain.remove(key), allowNotFound = true)
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
