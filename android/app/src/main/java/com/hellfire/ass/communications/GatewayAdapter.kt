package com.hellfire.ass.communications

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.events.NotificationNormalizer
import java.net.URI
import javax.net.ssl.HttpsURLConnection
import java.security.KeyStore
import java.security.KeyPairGenerator
import java.security.spec.MGF1ParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

class GatewayAdapter(private val app: HellFireApp) {
    private val alias = "hellfire-relay-rsa-v1"
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun publicKey(): String {
        val keys = store()
        if (!keys.containsAlias(alias)) KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").apply {
            initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP).build())
        }.generateKeyPair()
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.encodeToString(store().getCertificate(alias).publicKey.encoded, Base64.NO_WRAP)
            .chunked(64).joinToString("\n") + "\n-----END PUBLIC KEY-----\n"
    }
    private fun unwrap(box: JSONObject): JSONObject {
        require(box.getInt("version") == 1)
        val decode = { key: String -> Base64.decode(box.getString(key), Base64.DEFAULT) }
        val nonce = decode("nonce").also { require(it.size == 12) }
        val wrapped = decode("key").also { require(it.size == 256) }
        val data = decode("data").also { require(it.size <= 1024 * 1024) }
        val key = Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
            init(Cipher.DECRYPT_MODE, store().getKey(alias, null), OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT))
        }.doFinal(wrapped)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD("hellfire-relay-v1".toByteArray())
        }.doFinal(data)
        key.fill(0)
        return JSONObject(plain.toString(Charsets.UTF_8)).also { plain.fill(0) }
    }
    private fun request(config: JSONObject, path: String, body: JSONObject? = null): JSONObject {
        val base = URI(config.getString("url"))
        require(base.scheme == "https" && !base.host.isNullOrBlank() && base.userInfo == null && base.query == null && base.fragment == null)
        val connection = URI(base.toString().trimEnd('/') + path).toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("Authorization", "Bearer " + config.getString("token"))
        try {
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            check(connection.responseCode in 200..299) { "GATEWAY_REQUEST_REJECTED" }
            val result = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    require(output.size() + size <= 2 * 1024 * 1024)
                    output.write(buffer, 0, size)
                }
                output.toString("UTF-8")
            }
            return JSONObject(result)
        } finally { connection.disconnect() }
    }
    suspend fun sync(config: JSONObject): Boolean {
        publicKey()
        val transport = config.optString("transport", "meta")
        val channel = GatewayEvents.channel(transport)
        val response = request(config, "/device/events")
        require(response.optString("transport", "meta") == transport) { "GATEWAY_TRANSPORT_MISMATCH" }
        val events = response.getJSONArray("events")
        for (index in 0 until events.length()) {
            val envelope = events.getJSONObject(index)
            val id = envelope.getString("id").also { require(it.matches(Regex("[a-f0-9]{64}"))) }
            val event = unwrap(envelope.getJSONObject("box"))
            val item = event.optJSONObject("item")
            val collection = event.optString("collection")
            val timestamp = item?.optString("timestamp")?.toLongOrNull()?.let { Math.multiplyExact(it, 1000) }
                ?: Math.multiplyExact(envelope.getLong("created"), 1000)
            val snapshot = GatewayEvents.snapshot(event, id, config.getString("id"), transport, timestamp, System.currentTimeMillis())
            val safe = NotificationNormalizer.normalize(snapshot, app.packageName)!!
            app.events.capture(snapshot, app.packageName)
            if (item != null && collection in setOf("messages", "message_echoes")) {
                val external = item.optString("id").ifBlank { id }
                val identity = if (transport == "web") "wa-web:${config.getString("id")}:$external" else "wa:$external"
                app.database.communications().message(CommunicationMessage(NotificationNormalizer.sha256(identity),
                    channel, config.getString("id"), external, safe.id, GatewayEvents.thread(item, collection, transport),
                    safe.snapshot.fields["sender_hint"]?.firstOrNull().orEmpty(), safe.snapshot.fields["recipient_hint"]?.firstOrNull().orEmpty(), "",
                    safe.snapshot.fields["text"]?.firstOrNull().orEmpty(), timestamp, if (collection == "messages") "INCOMING" else "OUTGOING"))
            } else if (transport == "meta" && item != null && collection == "statuses") {
                val state = when (item.optString("status")) { "read" -> "READ"; "delivered" -> "DELIVERED"; "sent" -> "SENT_CONFIRMED"; else -> null }
                if (state != null) app.database.communications().whatsappStatus(item.optString("id"), state)
            }
            // Only acknowledge after local durable capture/normalization succeeds.
            request(config, "/device/events/$id/ack", JSONObject())
        }
        val capture = response.optJSONObject("capture_status")
        app.database.communications().integration(IntegrationState("WHATSAPP_GATEWAY",
            if (transport == "web") capture?.optString("state", "WORKER_UNAVAILABLE") ?: "WORKER_UNAVAILABLE" else "CONNECTED",
            System.currentTimeMillis(), capture?.optString("error")?.takeUnless { it.isBlank() || it == "null" }))
        return response.optBoolean("has_more", events.length() == 100)
    }
    fun send(config: JSONObject, draft: OutboundDraft): String {
        require(config.optString("transport", "meta") == "meta") { "WhatsApp Web capture cannot send messages" }
        require(draft.recipient.matches(Regex("[0-9]{6,20}")) && draft.body.length in 1..4096)
        val remote = request(config, "/drafts", JSONObject().put("to", draft.recipient).put("text", draft.body))
        val id = remote.getString("id").also { require(it.matches(Regex("[a-f0-9]{32}"))) }
        val approval = JSONObject().put("nonce", remote.getString("nonce"))
        request(config, "/drafts/$id/approve", approval)
        return request(config, "/drafts/$id/send", approval).getString("message_id")
    }
}
