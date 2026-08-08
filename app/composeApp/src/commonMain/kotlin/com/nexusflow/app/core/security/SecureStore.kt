package com.nexusflow.app.core.security

/** Stores small secrets only; values must never be exposed to UI state or logs. */
interface SecureStore {
    fun read(key: String): String?

    fun write(
        key: String,
        value: String,
    )

    fun remove(key: String)
}

class SecureStoreUnavailableException : IllegalStateException()
