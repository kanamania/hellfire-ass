package com.hellfire.ass.sync

import com.hellfire.ass.library.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.Base64
import javax.net.ssl.HttpsURLConnection



/** The encrypted library routes of web/api. */
interface LibraryApi : LibrarySync.BlobApi {
    fun get(path: String): JSONObject
    fun post(path: String, body: JSONObject): JSONObject
    override fun open(contentHash: String, size: Long, chunkSize: Int, chunkCount: Int) =
        post("/api/library/blobs", JSONObject().put("contentHash", contentHash).put("size", size).put("chunkSize", chunkSize).put("chunkCount", chunkCount))
    override fun putChunk(blobId: String, index: Int, sha256: String, bytes: ByteArray) =
        post("/api/library/blobs/$blobId/chunks/$index", JSONObject().put("sha256", sha256).put("bytes", Base64.getEncoder().encodeToString(bytes)))
}

class LibraryClient(private val url: String, private val token: String) : LibraryApi {
    override fun get(path: String) = send("GET", path, null)
    override fun post(path: String, body: JSONObject) = send("POST", path, body)

    /** A rate-limited (429) call waits and tries again a few times before the run gives up. */
    private fun send(method: String, path: String, body: JSONObject?): JSONObject {
        var attempt = 0
        while (true) {
            try { return once(method, path, body) } catch (e: WebSync.Rejected) {
                if (e.status != 429 || attempt >= 3) throw e
                Thread.sleep(2_000L shl attempt++)
            }
        }
    }

    private fun once(method: String, path: String, body: JSONObject?): JSONObject {
        val connection = URI(url.trim().trimEnd('/') + path).toURL().openConnection() as HttpsURLConnection
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 15_000
        connection.readTimeout = 60_000
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer $token")
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val output = ByteArrayOutputStream()
            stream?.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= 4 * 1024 * 1024) { "Reply too large" }
                    output.write(buffer, 0, read)
                }
            }
            if (code == 413) throw LibrarySync.Quota()
            if (code !in 200..299) throw WebSync.Rejected(code)
            val text = output.toString("UTF-8")
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally { connection.disconnect() }
    }
}
