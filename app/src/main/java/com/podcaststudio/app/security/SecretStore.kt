package com.podcaststudio.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SecretKeyName(val prefKey: String) {
    OPENAI("openai_api_key"),
    ELEVENLABS("elevenlabs_api_key"),
}

/** Write-mostly store: the UI only needs to know whether a secret exists, never its value. */
interface SecretStore {
    fun put(name: SecretKeyName, value: String)
    fun get(name: SecretKeyName): String?
    fun has(name: SecretKeyName): Boolean
    fun clear(name: SecretKeyName)
}

/**
 * AES-256-GCM with a non-exportable key held in the Android Keystore. Only ciphertext (IV + data)
 * reaches SharedPreferences, and that file is excluded from backup (see res/xml backup rules).
 */
class KeystoreSecretStore(context: Context) : SecretStore {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun put(name: SecretKeyName, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val blob = cipher.iv + encrypted
        prefs.edit().putString(name.prefKey, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    override fun get(name: SecretKeyName): String? {
        val encoded = prefs.getString(name.prefKey, null) ?: return null
        return try {
            val blob = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv)) }
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: Exception) {
            null // unreadable (e.g. keystore key lost): treat as not set; never log
        }
    }

    /** True only if a key is stored and can still be decrypted, so a lost Keystore key reads as "not set". */
    override fun has(name: SecretKeyName): Boolean = prefs.contains(name.prefKey) && get(name) != null

    override fun clear(name: SecretKeyName) {
        prefs.edit().remove(name.prefKey).apply()
    }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val PREFS_FILE = "secure_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "podcast_studio_secrets"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** In-memory store for tests and previews. */
class InMemorySecretStore : SecretStore {
    private val map = mutableMapOf<SecretKeyName, String>()
    override fun put(name: SecretKeyName, value: String) { map[name] = value }
    override fun get(name: SecretKeyName): String? = map[name]
    override fun has(name: SecretKeyName): Boolean = name in map
    override fun clear(name: SecretKeyName) { map.remove(name) }
}
