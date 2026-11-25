package com.hellfire.ass.guard

import android.util.Base64
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.security.PayloadCipher
import org.json.JSONObject
import java.net.URI
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection

/** Dedicated key: the device-local PayloadCipher key never leaves Android Keystore. */
object GuardRelay {
    const val SECRETS = "guard-relay"
    private val aad = "hellfire-guard-v1".toByteArray()
    fun validate(url: String, token: String, key: String): ByteArray {
        val uri = URI(url)
        require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null && uri.path.orEmpty() in listOf("", "/")) { "Use the relay HTTPS origin without a path, query or password" }
        require(token.length in 32..512 && token.none { it.isWhitespace() || it.isISOControl() }) { "Use the existing device bearer token (32–512 characters)" }
        val decoded = Base64.decode(key, Base64.NO_WRAP)
        require(decoded.size == 32) { "Guard transport key must contain 32 base64-encoded bytes" }
        return decoded
    }
    fun post(app: HellFireApp, path: String, plaintext: ByteArray, url: String): JSONObject {
        require(path in listOf("heartbeat", "evidence"))
        val secret = app.secrets.read(SECRETS) ?: error("GUARD_RELAY_NOT_CONFIGURED")
        val key = validate(url, secret.getString("token"), secret.getString("key"))
        val payload = try { PayloadCipher(SecretKeySpec(key, "AES")).encrypt(plaintext, aad) } finally { key.fill(0) }
        val body = JSONObject().put("payload", Base64.encodeToString(payload, Base64.NO_WRAP)).toString().toByteArray()
        val connection = URI(url.trimEnd('/') + "/device/theft/$path").toURL().openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000; connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false; connection.doOutput = true
            connection.setRequestProperty("Authorization", "Bearer " + secret.getString("token"))
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            check(connection.responseCode == 200) { "GUARD_RELAY_HTTP_${connection.responseCode}" }
            val response = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 4096) { "GUARD_RELAY_RESPONSE_TOO_LARGE" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(response.toString(Charsets.UTF_8))
        } finally { body.fill(0); connection.disconnect() }
    }
}
