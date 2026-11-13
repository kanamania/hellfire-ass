package com.hellfire.ass.security

import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

class PayloadCipher(private val key: SecretKey) {
    fun encrypt(plaintext: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        val cipher = encryptor()
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return byteArrayOf(1) + cipher.iv + cipher.doFinal(plaintext)
    }

    fun decrypt(envelope: ByteArray, aad: ByteArray = byteArrayOf()): ByteArray {
        require(envelope.size >= 29 && envelope[0] == 1.toByte()) { "Invalid encrypted payload" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(envelope.copyOfRange(13, envelope.size))
    }

    fun encryptStream(input: InputStream, output: OutputStream) {
        val cipher = encryptor()
        output.write(byteArrayOf(1) + cipher.iv)
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val size = input.read(buffer)
            if (size < 0) break
            cipher.update(buffer, 0, size)?.let(output::write)
        }
        output.write(cipher.doFinal())
    }

    private fun encryptor(): Cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(Cipher.ENCRYPT_MODE, key)
        check(iv.size == 12)
    }

    companion object {
        @Synchronized
        fun deviceCipher(): PayloadCipher {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val alias = "hellfire-local-v1"
            val key = (store.getKey(alias, null) as? SecretKey) ?: KeyGenerator
                .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder(alias,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256).build())
                }.generateKey()
            return PayloadCipher(key)
        }
    }
}
