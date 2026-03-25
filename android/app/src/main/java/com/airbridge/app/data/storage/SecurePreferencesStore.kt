package com.airbridge.app.data.storage

import android.content.Context
import android.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class SecurePreferencesStore(
    context: Context,
    private val json: Json,
) {
    private val sharedPreferences = context.getSharedPreferences("air_bridge_secure_store", Context.MODE_PRIVATE)
    private val keyAlias = "air_bridge_master_key_v1"

    fun <T> read(key: String, serializer: KSerializer<T>): T? {
        val encoded = sharedPreferences.getString(key, null) ?: return null
        val decrypted = decrypt(encoded) ?: return null
        return json.decodeFromString(serializer, decrypted.toString(StandardCharsets.UTF_8))
    }

    fun <T> write(key: String, serializer: KSerializer<T>, value: T) {
        val encoded = encrypt(json.encodeToString(serializer, value).toByteArray(StandardCharsets.UTF_8))
        sharedPreferences.edit().putString(key, encoded).apply()
    }

    fun remove(key: String) {
        sharedPreferences.edit().remove(key).apply()
    }

    private fun encrypt(bytes: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val payload = cipher.iv + cipher.doFinal(bytes)
        return Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    private fun decrypt(encoded: String): ByteArray? = runCatching {
        val payload = Base64.decode(encoded, Base64.NO_WRAP)
        val iv = payload.copyOfRange(0, 12)
        val ciphertext = payload.copyOfRange(12, payload.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(), GCMParameterSpec(128, iv))
        cipher.doFinal(ciphertext)
    }.getOrNull()

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(keyAlias, null)
        if (existing is SecretKey) {
            return existing
        }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}

