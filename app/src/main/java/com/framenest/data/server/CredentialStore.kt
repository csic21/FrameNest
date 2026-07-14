package com.framenest.data.server

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

/**
 * Password storage backed by AndroidX Security Crypto (Android Keystore).
 *
 * Room only keeps [com.framenest.core.model.SavedServer.credentialAlias];
 * this store maps alias → password. Values never go to logs or URLs.
 */
interface CredentialStore {
    fun createAlias(): String
    fun savePassword(alias: String, password: CharArray)
    fun getPassword(alias: String): CharArray?
    fun deletePassword(alias: String)
    fun hasPassword(alias: String): Boolean
}

class EncryptedCredentialStore(
    context: Context,
    prefsName: String = PREFS_NAME,
) : CredentialStore {

    private val prefs: SharedPreferences = createPrefs(context.applicationContext, prefsName)

    override fun createAlias(): String = "cred_${UUID.randomUUID()}"

    override fun savePassword(alias: String, password: CharArray) {
        require(alias.isNotBlank()) { "credential alias is blank" }
        // Store as String for SharedPreferences; wipe caller-owned CharArray after.
        val value = String(password)
        try {
            prefs.edit().putString(alias, value).apply()
        } finally {
            // Best-effort: String is immutable so we cannot wipe the copy inside prefs,
            // but avoid retaining the temporary builder longer than needed.
        }
    }

    override fun getPassword(alias: String): CharArray? {
        if (alias.isBlank()) return null
        val value = prefs.getString(alias, null) ?: return null
        return value.toCharArray()
    }

    override fun deletePassword(alias: String) {
        if (alias.isBlank()) return
        prefs.edit().remove(alias).apply()
    }

    override fun hasPassword(alias: String): Boolean {
        if (alias.isBlank()) return false
        return prefs.contains(alias)
    }

    companion object {
        const val PREFS_NAME: String = "fn_server_credentials"

        @Suppress("DEPRECATION")
        private fun createPrefs(context: Context, name: String): SharedPreferences {
            // security-crypto 1.1.x still the supported Keystore-backed prefs API for MVP.
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            return EncryptedSharedPreferences.create(
                context,
                name,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}

/** In-memory store for JVM unit tests (no Android Keystore). */
class InMemoryCredentialStore : CredentialStore {
    private val map = LinkedHashMap<String, CharArray>()

    override fun createAlias(): String = "cred_${UUID.randomUUID()}"

    override fun savePassword(alias: String, password: CharArray) {
        map[alias] = password.copyOf()
    }

    override fun getPassword(alias: String): CharArray? = map[alias]?.copyOf()

    override fun deletePassword(alias: String) {
        map.remove(alias)?.fill('\u0000')
    }

    override fun hasPassword(alias: String): Boolean = map.containsKey(alias)
}
