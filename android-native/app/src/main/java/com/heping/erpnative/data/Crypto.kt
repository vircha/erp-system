package com.heping.erpnative.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 用 AndroidKeyStore 内的 AES-GCM 密钥对敏感字段（登录 token / 信任设备 token）做静态加密，
 * 避免明文落盘被其他应用或 adb 备份提取。密钥由系统密钥库保管，不出现在代码或文件中。
 */
object Crypto {
    private const val PROVIDER = "AndroidKeyStore"
    private const val ALIAS = "erp_session_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_LEN = 128

    private val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    @Synchronized
    private fun key(): SecretKey {
        if (!keyStore.containsAlias(ALIAS)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
            kg.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setUserAuthenticationRequired(false)
                    .build()
            )
            kg.generateKey()
        }
        return keyStore.getKey(ALIAS, null) as SecretKey
    }

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val enc = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val out = ByteArray(iv.size + enc.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(enc, 0, out, iv.size, enc.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    fun decrypt(cipherText: String): String {
        val data = Base64.decode(cipherText, Base64.NO_WRAP)
        val iv = data.copyOfRange(0, IV_LEN)
        val enc = data.copyOfRange(IV_LEN, data.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_LEN, iv))
        return String(cipher.doFinal(enc), Charsets.UTF_8)
    }
}
