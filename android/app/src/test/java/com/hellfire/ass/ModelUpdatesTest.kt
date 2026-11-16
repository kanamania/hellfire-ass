package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.ai.GemmaModel
import com.hellfire.ass.ai.ModelDownloader
import com.hellfire.ass.ai.ModelRelease
import com.hellfire.ass.ai.ModelUpdates
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Synthetic bytes stand in for the model; no network or model runtime is used. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ModelUpdatesTest {
    private val bytes = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val release = ModelRelease("a".repeat(40), MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, bytes.size.toLong())

    private class Fake(url: URL, val code: Int, val body: ByteArray = byteArrayOf(), val headers: Map<String, String> = emptyMap()) : HttpURLConnection(url) {
        override fun getResponseCode() = code
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getHeaderField(name: String?) = headers[name]
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
    }

    /** Serves the file like the repository: a redirect to the file host, which honours Range unless [ignoreRange]. */
    private fun server(ranges: MutableList<String?>, ignoreRange: Boolean = false, body: ByteArray = bytes): (URL) -> HttpURLConnection = { url ->
        if (url.host == "huggingface.co") Fake(url, 302, headers = mapOf("Location" to "https://cdn.example.invalid/file"))
        else object : HttpURLConnection(url) {
            private val code by lazy { ranges += getRequestProperty("Range"); if (start() > 0 && !ignoreRange) 206 else 200 }
            override fun getResponseCode(): Int = code
            private fun start() = getRequestProperty("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            override fun getInputStream() = ByteArrayInputStream(body, if (ignoreRange) 0 else start(), body.size)
            override fun getHeaderField(name: String?) = if (name == "Content-Range") "bytes ${start()}-${body.size - 1}/${body.size}" else null
            override fun connect() {}
            override fun disconnect() {}
            override fun usingProxy() = false
        }
    }

    private fun temp() = File.createTempFile("model", ".part")

    @Test fun `repository metadata yields the model file's release`() {
        val json = JSONObject().put("sha", "b".repeat(40)).put("siblings", JSONArray()
            .put(JSONObject().put("rfilename", "README.md").put("size", 10))
            .put(JSONObject().put("rfilename", GemmaModel.FILE).put("size", 2_588_147_712).put("lfs", JSONObject().put("sha256", "c".repeat(64)))))
        assertEquals(ModelRelease("b".repeat(40), "c".repeat(64), 2_588_147_712), GemmaModel.parse(json))
        assertNull(GemmaModel.parse(JSONObject(json.toString()).put("sha", "not-a-commit")))
        assertNull(GemmaModel.parse(JSONObject().put("sha", "b".repeat(40)).put("siblings", JSONArray())))
        assertEquals("https://huggingface.co/${GemmaModel.REPO}/resolve/${GemmaModel.PINNED.commit}/${GemmaModel.FILE}", GemmaModel.downloadUrl(GemmaModel.PINNED))
    }

    @Test fun `a full download is accepted only with the expected checksum`() {
        val part = temp(); part.delete()
        try {
            ModelDownloader(server(mutableListOf())).download(release, part)
            assertArrayEquals(bytes, part.readBytes())
            val wrong = release.copy(sha256 = "0".repeat(64))
            part.delete()
            assertTrue(runCatching { ModelDownloader(server(mutableListOf())).download(wrong, part) }.isFailure)
            assertFalse(part.exists())
        } finally { part.delete() }
    }

    @Test fun `an interrupted download resumes from the saved bytes`() {
        val part = temp()
        try {
            part.writeBytes(bytes.copyOf(120_000))
            val ranges = mutableListOf<String?>()
            ModelDownloader(server(ranges)).download(release, part)
            assertEquals(listOf<String?>("bytes=120000-"), ranges)
            assertArrayEquals(bytes, part.readBytes())
        } finally { part.delete() }
    }

    @Test fun `a server that ignores the range restarts the file instead of corrupting it`() {
        val part = temp()
        try {
            part.writeBytes(bytes.copyOf(50_000))
            ModelDownloader(server(mutableListOf(), ignoreRange = true)).download(release, part)
            assertArrayEquals(bytes, part.readBytes())
        } finally { part.delete() }
    }

    @Test fun `redirects must stay on HTTPS`() {
        val part = temp(); part.delete()
        val insecure: (URL) -> HttpURLConnection = { url -> Fake(url, 302, headers = mapOf("Location" to "http://cdn.example.invalid/file")) }
        assertTrue(runCatching { ModelDownloader(insecure).download(release, part) }.isFailure)
        assertFalse(part.exists())
        val failing: (URL) -> HttpURLConnection = { url -> Fake(url, 404) }
        assertTrue(runCatching { ModelDownloader(failing).download(release, part) }.exceptionOrNull() is ModelDownloader.HttpError)
    }

    @Test fun `updates are offered only for the downloaded model when a different file is published`() {
        val context = RuntimeEnvironment.getApplication()
        ModelUpdates.markInstalled(context, release)
        assertNull(ModelUpdates.pendingUpdate(ModelUpdates.state(context)))
        ModelUpdates.markAvailable(context, release)
        assertNull(ModelUpdates.pendingUpdate(ModelUpdates.state(context)))
        val newer = ModelRelease("d".repeat(40), "e".repeat(64), 2_000_000_000)
        ModelUpdates.markAvailable(context, newer)
        assertEquals(newer, ModelUpdates.pendingUpdate(ModelUpdates.state(context)))
        ModelUpdates.markImported(context)
        assertNull("the owner's own model is never replaced by an update", ModelUpdates.pendingUpdate(ModelUpdates.state(context)))
        ModelUpdates.markRemoved(context)
        assertTrue(ModelUpdates.state(context).declined)
    }
}
