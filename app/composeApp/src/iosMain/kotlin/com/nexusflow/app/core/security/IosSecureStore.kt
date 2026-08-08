package com.nexusflow.app.core.security

/**
 * This delivery has no iOS Google host, so it cannot create an authenticated session on iOS.
 * It deliberately reports no restored session and refuses writes; a future iOS Google host must
 * replace it with a Keychain-backed store before it can persist credentials.
 */
class IosSecureStore : SecureStore {
    override fun read(key: String): String? = null

    override fun write(
        key: String,
        value: String,
    ) = throw SecureStoreUnavailableException()

    override fun remove(key: String) = throw SecureStoreUnavailableException()
}
