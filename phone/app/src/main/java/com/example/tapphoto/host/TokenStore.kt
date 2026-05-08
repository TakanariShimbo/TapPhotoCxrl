package com.example.tapphoto.host

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object TokenStore {
    private const val PREFS_NAME = "cxrl_secure_prefs"
    private const val KEY_TOKEN = "cxrl_token"

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private fun prefs(context: Context) =
        EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            MasterKey.Builder(context.applicationContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )

    fun load(context: Context) {
        _token.value = prefs(context).getString(KEY_TOKEN, null)
    }

    fun save(context: Context, value: String) {
        prefs(context).edit().putString(KEY_TOKEN, value).apply()
        _token.value = value
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
        _token.value = null
    }
}
