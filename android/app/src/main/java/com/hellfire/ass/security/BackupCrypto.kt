package com.hellfire.ass.security

import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object BackupCrypto {
    const val ITERATIONS = 600_000
    fun derive(password: CharArray, salt: ByteArray): PayloadCipher {
        require(password.size in 12..1024) { "Use a passphrase of at least 12 characters" }
        require(salt.size == 16)
        val spec = PBEKeySpec(password, salt, ITERATIONS, 256)
        val bytes = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded } finally { spec.clearPassword() }
        return try { PayloadCipher(SecretKeySpec(bytes, "AES")) } finally { bytes.fill(0) }
    }
}
