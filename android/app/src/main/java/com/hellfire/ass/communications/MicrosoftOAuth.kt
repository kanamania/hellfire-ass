package com.hellfire.ass.communications

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.net.URLEncoder
import javax.net.ssl.HttpsURLConnection

/**
 * Owner decision 2026-11-27: Microsoft 365 / Outlook mail through OAuth2 (XOAUTH2 for IMAP and SMTP), since Exchange
 * Online refuses passwords. Device-code sign-in: the owner enters a short code at microsoft.com/devicelogin on any
 * device. Uses the owner's own Azure app registration (client ID); no library, plain HTTPS to login.microsoftonline.com.
 */
object MicrosoftOAuth {
    const val IMAP_HOST = "outlook.office365.com"
    const val SMTP_HOST = "smtp.office365.com"
    const val SCOPE = "offline_access https://outlook.office.com/IMAP.AccessAsUser.All https://outlook.office.com/SMTP.Send"

    /** Form POST returning (HTTP status, JSON body). Injectable for tests. */
    fun interface Post { fun send(url: String, form: Map<String, String>): Pair<Int, JSONObject> }

    data class DeviceCode(val userCode: String, val verificationUri: String, val deviceCode: String, val intervalSeconds: Int, val expiresAt: Long)
    data class Tokens(val accessToken: String, val refreshToken: String)
    sealed interface Poll { object Pending : Poll; object SlowDown : Poll; data class Done(val tokens: Tokens) : Poll; data class Failed(val code: String) : Poll }

    fun validClientId(id: String) = id.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"))
    fun validTenant(tenant: String) = tenant.matches(Regex("common|organizations|consumers|[0-9a-fA-F-]{36}|[A-Za-z0-9.-]{3,100}"))
    private fun base(tenant: String) = "https://login.microsoftonline.com/$tenant/oauth2/v2.0"

    fun start(clientId: String, tenant: String, post: Post = https, now: Long = System.currentTimeMillis()): DeviceCode {
        require(validClientId(clientId) && validTenant(tenant)) { "Enter the Application (client) ID of your Azure app registration." }
        val (status, body) = post.send("${base(tenant)}/devicecode", mapOf("client_id" to clientId, "scope" to SCOPE))
        require(status == 200) { "Microsoft refused the sign-in request (${body.optString("error", "HTTP $status")})." }
        return DeviceCode(body.getString("user_code"), body.getString("verification_uri"), body.getString("device_code"),
            body.optInt("interval", 5).coerceIn(1, 60), now + body.optLong("expires_in", 900) * 1000)
    }

    fun poll(clientId: String, tenant: String, deviceCode: String, post: Post = https): Poll {
        val (status, body) = post.send("${base(tenant)}/token", mapOf("client_id" to clientId,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code", "device_code" to deviceCode))
        if (status == 200) return Poll.Done(tokens(body))
        return when (body.optString("error")) {
            "authorization_pending" -> Poll.Pending
            "slow_down" -> Poll.SlowDown
            else -> Poll.Failed(body.optString("error", "HTTP_$status").uppercase())
        }
    }

    /** New access token from the stored refresh token; Microsoft may rotate the refresh token, so callers save it. */
    fun refresh(clientId: String, tenant: String, refreshToken: String, post: Post = https): Tokens {
        val (status, body) = post.send("${base(tenant)}/token", mapOf("client_id" to clientId, "grant_type" to "refresh_token",
            "refresh_token" to refreshToken, "scope" to SCOPE))
        if (status != 200) throw javax.mail.AuthenticationFailedException("Microsoft sign-in expired (${body.optString("error", "HTTP $status")}). Sign in again.")
        return tokens(body).let { if (it.refreshToken.isEmpty()) it.copy(refreshToken = refreshToken) else it }
    }

    private fun tokens(body: JSONObject) = Tokens(body.getString("access_token"), body.optString("refresh_token"))

    val https = Post { url, form ->
        val connection = URI(url).toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000; connection.readTimeout = 30_000
        connection.requestMethod = "POST"; connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            connection.outputStream.use { out ->
                out.write(form.entries.joinToString("&") { "${URLEncoder.encode(it.key, "UTF-8")}=${URLEncoder.encode(it.value, "UTF-8")}" }.toByteArray())
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use { input -> val buffer = ByteArray(8192); while (true) { val n = input.read(buffer); if (n < 0) break
                require(output.size() + n <= 256 * 1024) { "Reply too large" }; output.write(buffer, 0, n) } }
            status to runCatching { JSONObject(output.toString("UTF-8")) }.getOrDefault(JSONObject())
        } finally { connection.disconnect() }
    }
}
