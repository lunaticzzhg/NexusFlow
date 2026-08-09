package com.nexusflow.app.core.security

import kotlinx.coroutines.CancellationException

/** Entry point for small secrets. Features must select an owned namespace before accessing values. */
interface SecureStore {
    fun namespace(name: String): SecureStoreNamespace
}

/** A feature-owned namespace for sensitive string values. */
interface SecureStoreNamespace {
    suspend fun read(key: SecureKey): String?

    suspend fun write(
        key: SecureKey,
        value: String,
    )

    suspend fun remove(key: SecureKey)

    /** Removes all values in this namespace. */
    suspend fun clear()
}

/** A validated key for one secure string value. */
class SecureKey internal constructor(
    internal val name: String,
)

object SecureKeys {
    fun string(name: String): SecureKey = SecureKey(validateName(name, "Secure key"))
}

class SecureStoreUnavailableException : IllegalStateException()

internal fun validateSecureNamespace(name: String): String = validateName(name, "Secure namespace")

internal suspend fun <T> secureStorageCall(block: suspend () -> T): T =
    try {
        block()
    } catch (cause: Exception) {
        if (cause is CancellationException) throw cause
        if (cause is SecureStoreUnavailableException) throw cause
        throw SecureStoreUnavailableException()
    }

private fun validateName(
    name: String,
    type: String,
): String {
    require(SECURE_NAME_PATTERN.matches(name)) {
        "$type names must be 1..64 lowercase ASCII letters, numbers, or underscores and start with a letter."
    }
    return name
}

private val SECURE_NAME_PATTERN = Regex("[a-z][a-z0-9_]{0,63}")
