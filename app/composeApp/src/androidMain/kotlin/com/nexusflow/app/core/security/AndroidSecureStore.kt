package com.nexusflow.app.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class AndroidSecureStore(context: Context) : SecureStore {
    private val preferences =
        EncryptedSharedPreferences.create(
            context,
            "nexusflow_secure_store",
            MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    override fun read(key: String): String? = preferences.getString(key, null)

    override fun write(
        key: String,
        value: String,
    ) {
        check(preferences.edit().putString(key, value).commit())
    }

    override fun remove(key: String) {
        check(preferences.edit().remove(key).commit())
    }
}
