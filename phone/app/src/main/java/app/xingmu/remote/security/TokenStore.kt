package app.xingmu.remote.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.xingmu.remote.BuildConfig
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TokenStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    init {
        migrateProductionEndpoint()
    }

    val baseUrl: String
        get() = preferences.getString(KEY_BASE_URL, null)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: BuildConfig.DEFAULT_BACKEND_URL

    val username: String
        get() = preferences.getString(KEY_USERNAME, "admin").orEmpty()

    fun saveSession(baseUrl: String, username: String, token: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_BASE_URL, baseUrl)
            .putString(KEY_USERNAME, username)
            .putString(KEY_TOKEN_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_TOKEN_VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .apply()
    }

    fun readToken(): String? {
        val iv = preferences.getString(KEY_TOKEN_IV, null) ?: return null
        val value = preferences.getString(KEY_TOKEN_VALUE, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(),
                GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
            )
            String(cipher.doFinal(Base64.decode(value, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    fun clearSession() {
        preferences.edit().remove(KEY_TOKEN_IV).remove(KEY_TOKEN_VALUE).apply()
    }

    fun saveEndpoint(baseUrl: String) {
        preferences.edit().apply {
            putString(KEY_BASE_URL, baseUrl)
            if (BuildConfig.USE_PRODUCTION_BACKEND) {
                putInt(KEY_BACKEND_MIGRATION_VERSION, BACKEND_MIGRATION_VERSION)
            }
        }.apply()
    }

    var controlChannelMode: String
        get() = preferences.getString(KEY_CONTROL_CHANNEL, "AUTO").orEmpty()
            .takeIf { it in setOf("AUTO", "P2P", "RELAY") } ?: "AUTO"
        set(value) {
            preferences.edit().putString(
                KEY_CONTROL_CHANNEL,
                value.takeIf { it in setOf("AUTO", "P2P", "RELAY") } ?: "AUTO",
            ).apply()
        }

    var lastUpdateCheck: Long
        get() = preferences.getLong(KEY_LAST_UPDATE_CHECK, 0L)
        set(value) { preferences.edit().putLong(KEY_LAST_UPDATE_CHECK, value.coerceAtLeast(0L)).apply() }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun migrateProductionEndpoint() {
        if (!BuildConfig.USE_PRODUCTION_BACKEND ||
            preferences.getInt(KEY_BACKEND_MIGRATION_VERSION, 0) >= BACKEND_MIGRATION_VERSION
        ) return

        val saved = preferences.getString(KEY_BASE_URL, null).orEmpty().trim()
        val editor = preferences.edit()
            .putInt(KEY_BACKEND_MIGRATION_VERSION, BACKEND_MIGRATION_VERSION)
        if (saved.isEmpty() || isPrivateBackend(saved)) {
            editor.putString(KEY_BASE_URL, BuildConfig.DEFAULT_BACKEND_URL)
                .remove(KEY_TOKEN_IV)
                .remove(KEY_TOKEN_VALUE)
        }
        editor.apply()
    }

    private fun isPrivateBackend(value: String): Boolean = runCatching {
        val host = URI(value).host?.lowercase().orEmpty()
        when {
            host == "localhost" || host == "0.0.0.0" -> true
            host.startsWith("127.") || host.startsWith("10.") -> true
            host.startsWith("192.168.") || host.startsWith("169.254.") -> true
            host.startsWith("172.") -> host.split('.').getOrNull(1)?.toIntOrNull() in 16..31
            else -> false
        }
    }.getOrDefault(false)

    private companion object {
        const val BACKEND_MIGRATION_VERSION = 1
        const val PREFERENCES = "secure_session"
        const val KEY_ALIAS = "starlight_mobile_token_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BASE_URL = "base_url"
        const val KEY_BACKEND_MIGRATION_VERSION = "backend_migration_version"
        const val KEY_USERNAME = "username"
        const val KEY_TOKEN_IV = "token_iv"
        const val KEY_TOKEN_VALUE = "token_value"
        const val KEY_LAST_UPDATE_CHECK = "last_update_check"
        const val KEY_CONTROL_CHANNEL = "control_channel"
    }
}
