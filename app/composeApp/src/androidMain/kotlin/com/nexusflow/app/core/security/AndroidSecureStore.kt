package com.nexusflow.app.core.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSecureStore(
    context: Context,
) : SecureStore {
    private val backend = AndroidSecureStoreBackend(context.applicationContext)

    override fun namespace(name: String): SecureStoreNamespace = AndroidSecureStoreNamespace(backend, validateSecureNamespace(name))
}

private class AndroidSecureStoreNamespace(
    private val backend: AndroidSecureStoreBackend,
    private val namespace: String,
) : SecureStoreNamespace {
    override suspend fun read(key: SecureKey): String? =
        secureStorageCall {
            withContext(Dispatchers.IO) { backend.preferences().getString(physicalKey(key), null) }
        }

    override suspend fun write(
        key: SecureKey,
        value: String,
    ) {
        secureStorageCall {
            withContext(Dispatchers.IO) {
                check(backend.preferences().edit().putString(physicalKey(key), value).commit())
            }
        }
    }

    override suspend fun remove(key: SecureKey) {
        secureStorageCall {
            withContext(Dispatchers.IO) {
                check(backend.preferences().edit().remove(physicalKey(key)).commit())
            }
        }
    }

    override suspend fun clear() {
        secureStorageCall {
            withContext(Dispatchers.IO) {
                val prefix = "$namespace."
                val keys =
                    backend.preferences().all.keys.filter { key ->
                        key.startsWith(prefix)
                    }
                if (keys.isNotEmpty()) {
                    val editor = backend.preferences().edit()
                    keys.forEach(editor::remove)
                    check(editor.commit())
                }
            }
        }
    }

    private fun physicalKey(key: SecureKey): String = "$namespace.${key.name}"
}

private class AndroidSecureStoreBackend(
    private val context: Context,
) {
    private val encryptedPreferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            "nexusflow_secure_store",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun preferences(): SharedPreferences = encryptedPreferences
}
