package com.hellfire.ass.security

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object DriveAuthorization {
    const val SCOPE = "https://www.googleapis.com/auth/drive.file"
    fun authorize(context: Context, email: String): AuthorizationResult = Tasks.await(
        Identity.getAuthorizationClient(context).authorize(AuthorizationRequest.builder()
            .setAccount(Account(email, "com.google"))
            .setRequestedScopes(listOf(Scope(SCOPE))).build()), 60, TimeUnit.SECONDS)

    fun token(result: AuthorizationResult): String {
        if (result.hasResolution() || SCOPE !in result.grantedScopes) throw DriveReconnectRequired()
        return result.accessToken?.takeIf { it.isNotBlank() } ?: throw DriveReconnectRequired()
    }
}

class DriveReconnectRequired : Exception()
class DriveHttpError(val status: Int) : Exception("Google Drive request failed ($status)")
data class DriveSnapshot(val id: String, val name: String, val createdAt: String, val size: Long)

/** Only encrypted .hfb archives cross this transport. Never follows a bearer-token redirect. */
class GoogleDriveBackup(private val connect: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection }) {
    companion object {
        const val MAX_BYTES = BackupReadiness.MAX_ARCHIVE
        private const val API = "https://www.googleapis.com/drive/v3/files"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
        fun validId(id: String): String { require(id.matches(Regex("[A-Za-z0-9_-]{1,200}"))); return id }
    }
    fun upload(file: File, token: String, checkActive: () -> Unit = {}): String {
        require(file.length() in 1..MAX_BYTES)
        val name = "HellFire-Ass-${java.time.Instant.now().toString().replace(':', '-')}.hfb"
        val metadata = JSONObject().put("name", name).put("mimeType", "application/octet-stream")
            .put("appProperties", JSONObject().put("hellfireBackup", "2"))
        val location = request("$UPLOAD?uploadType=resumable&fields=id,size,md5Checksum", token, "POST") { c ->
            c.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            c.setRequestProperty("X-Upload-Content-Type", "application/octet-stream")
            c.setRequestProperty("X-Upload-Content-Length", file.length().toString())
            val body = metadata.toString().toByteArray(Charsets.UTF_8)
            c.doOutput = true; c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            requireSuccess(c)
            c.getHeaderField("Location") ?: error("Drive upload session missing")
        }
        // The session location is supplied by the server. Restrict it before attaching the token.
        val session = URL(location)
        require(session.protocol == "https" && session.host == "www.googleapis.com" && session.port == -1 &&
            session.path == "/upload/drive/v3/files" && session.userInfo == null)
        val digest = MessageDigest.getInstance("MD5")
        val result = request(location, token, "PUT") { c ->
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.doOutput = true; c.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { input -> c.outputStream.use { output ->
                val buffer = ByteArray(65536)
                while (true) {
                    checkActive()
                    val count = input.read(buffer); if (count < 0) break
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
            } }
            requireSuccess(c)
            json(c)
        }
        check(result.getLong("size") == file.length() && result.getString("md5Checksum") == digest.digest().hex()) {
            "Drive did not confirm the complete archive"
        }
        return validId(result.getString("id"))
    }
    fun list(token: String): List<DriveSnapshot> {
        val query = URLEncoder.encode("trashed = false and appProperties has { key='hellfireBackup' and value='2' }", "UTF-8")
        return request("$API?q=$query&orderBy=createdTime%20desc&pageSize=100&fields=files(id,name,createdTime,size)", token) { c ->
            requireSuccess(c)
            val files = json(c).getJSONArray("files")
            (0 until files.length()).map { i -> files.getJSONObject(i).let {
                DriveSnapshot(validId(it.getString("id")), it.getString("name"), it.getString("createdTime"), it.getLong("size"))
            } }
        }
    }
    /** Deletes one app-created archive (drive.file scope only reaches files this app created or opened). */
    fun delete(id: String, token: String) {
        request("$API/${validId(id)}", token, "DELETE") { c ->
            if (c.responseCode == 404) return@request
            requireSuccess(c)
        }
    }
    fun download(snapshot: DriveSnapshot, file: File, token: String, checkActive: () -> Unit = {}) {
        require(snapshot.size in 1..MAX_BYTES)
        try {
            request("$API/${validId(snapshot.id)}?alt=media", token) { c ->
                requireSuccess(c)
                c.inputStream.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(65536); var total = 0L
                    while (true) {
                        checkActive()
                        val count = input.read(buffer); if (count < 0) break
                        total += count; require(total <= snapshot.size && total <= MAX_BYTES)
                        output.write(buffer, 0, count)
                    }
                    require(total == snapshot.size) { "Incomplete Drive backup" }
                } }
            }
        } catch (error: Exception) { file.delete(); throw error }
    }
    private fun <T> request(url: String, token: String, method: String = "GET", block: (HttpURLConnection) -> T): T {
        val c = connect(URL(url))
        try {
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000; c.readTimeout = 60_000
            c.requestMethod = method
            c.setRequestProperty("Authorization", "Bearer $token")
            return block(c)
        } finally { c.disconnect() }
    }
    private fun requireSuccess(c: HttpURLConnection) {
        if (c.responseCode == 401) throw DriveReconnectRequired()
        if (c.responseCode !in 200..299) throw DriveHttpError(c.responseCode)
    }
    private fun json(c: HttpURLConnection): JSONObject = c.inputStream.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = it.read(buffer); if (count < 0) break
            require(output.size() + count <= 1024 * 1024)
            output.write(buffer, 0, count)
        }
        JSONObject(output.toString(Charsets.UTF_8.name()))
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
