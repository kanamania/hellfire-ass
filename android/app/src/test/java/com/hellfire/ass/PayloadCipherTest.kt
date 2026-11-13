package com.hellfire.ass

import com.hellfire.ass.security.PayloadCipher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.AEADBadTagException
import javax.crypto.KeyGenerator
import org.junit.Assert.*
import org.junit.Test

class PayloadCipherTest {
    private val cipher = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())

    @Test fun `payload round trip preserves unicode`() {
        val bytes = "Habari · encrypted synthetic text".toByteArray()
        val encrypted = cipher.encrypt(bytes)
        assertArrayEquals(bytes, cipher.decrypt(encrypted))
        assertFalse(encrypted.toString(Charsets.UTF_8).contains("Habari"))
    }
    @Test fun `each write has a fresh nonce`() {
        assertFalse(cipher.encrypt(byteArrayOf(1)).contentEquals(cipher.encrypt(byteArrayOf(1))))
    }
    @Test(expected = AEADBadTagException::class) fun `tampering fails closed`() {
        val encrypted = cipher.encrypt(byteArrayOf(1, 2, 3))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        cipher.decrypt(encrypted)
    }
    @Test(expected = IllegalArgumentException::class) fun `invalid envelope is rejected`() {
        cipher.decrypt(byteArrayOf(1))
    }
    @Test fun `streamed audio uses authenticated envelope without plaintext files`() {
        val audio = ByteArray(80000) { (it % 127).toByte() }
        val output = ByteArrayOutputStream()
        cipher.encryptStream(ByteArrayInputStream(audio), output)
        assertArrayEquals(audio, cipher.decrypt(output.toByteArray()))
    }
    @Test(expected = AEADBadTagException::class) fun `different device key cannot decrypt`() {
        val other = PayloadCipher(KeyGenerator.getInstance("AES").apply { init(256) }.generateKey())
        other.decrypt(cipher.encrypt(byteArrayOf(42)))
    }
}
