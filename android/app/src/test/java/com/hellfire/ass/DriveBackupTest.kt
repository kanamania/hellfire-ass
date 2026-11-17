package com.hellfire.ass

import android.app.Application
import com.hellfire.ass.security.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DriveBackupTest {
    private class Connection(url: URL, private val code: Int = 200, private val body: ByteArray = ByteArray(0), private val location: String? = null) : HttpURLConnection(url) {
        val output = ByteArrayOutputStream()
        var closed = false
        override fun getOutputStream() = output
        override fun getInputStream() = ByteArrayInputStream(body)
        override fun getResponseCode() = code
        override fun getHeaderField(name: String) = if (name == "Location") location else null
        override fun disconnect() { closed = true }
        override fun usingProxy() = false
        override fun connect() {}
    }
    private fun archive(): File = File.createTempFile("synthetic-", ".hfb", RuntimeEnvironment.getApplication().cacheDir)
    @Test fun `upload confirms size and checksum before reporting success`() {
        val file = archive().apply { writeText("synthetic encrypted fixture") }
        val hash = MessageDigest.getInstance("MD5").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val connections = mutableListOf<Connection>()
        val drive = GoogleDriveBackup { url ->
            Connection(url, body = if (connections.isEmpty()) byteArrayOf() else """{"id":"fixture_id","size":"${file.length()}","md5Checksum":"$hash"}""".toByteArray(),
                location = "https://www.googleapis.com/upload/drive/v3/files?upload_id=fixture").also { connections += it }
        }
        assertEquals("fixture_id", drive.upload(file, "fixture-token"))
        assertTrue(connections[0].output.toString().contains("hellfireBackup"))
        assertArrayEquals(file.readBytes(), connections[1].output.toByteArray())
        assertTrue(connections.all { it.closed && !it.instanceFollowRedirects })
        file.delete()
    }
    @Test fun `untrusted upload redirect cannot receive bearer token`() {
        val file = archive().apply { writeText("fixture") }; var requests = 0
        val drive = GoogleDriveBackup { url -> requests++; Connection(url, location = "https://attacker.invalid/upload") }
        assertThrows(IllegalArgumentException::class.java) { drive.upload(file, "fixture-token") }
        assertEquals(1, requests); file.delete()
    }
    @Test fun `unconfirmed uploaded checksum is rejected`() {
        val file = archive().apply { writeText("fixture") }; var requests = 0
        val drive = GoogleDriveBackup { url ->
            Connection(url, body = """{"id":"fixture","size":"7","md5Checksum":"incorrect"}""".toByteArray(),
                location = "https://www.googleapis.com/upload/drive/v3/files?upload_id=fixture").also { requests++ }
        }
        assertThrows(IllegalStateException::class.java) { drive.upload(file, "fixture-token") }
        assertEquals(2, requests); file.delete()
    }
    @Test fun `listing limits Drive query to this app archives and validates identifiers`() {
        val drive = GoogleDriveBackup { url ->
            assertTrue(url.query.contains("hellfireBackup")); assertTrue(url.query.contains("pageSize=100"))
            Connection(url, body = """{"files":[{"id":"fixture-id","name":"Fixture.hfb","createdTime":"2026-11-14T00:00:00Z","size":"9"}]}""".toByteArray())
        }
        assertEquals(listOf(DriveSnapshot("fixture-id", "Fixture.hfb", "2026-11-14T00:00:00Z", 9)), drive.list("fixture"))
        assertThrows(IllegalArgumentException::class.java) { GoogleDriveBackup.validId("../other?token=x") }
    }
    @Test fun `retention listing includes archives beyond the first page`() {
        var calls = 0
        val drive = GoogleDriveBackup { url ->
            val next = calls++ == 0
            if (!next) assertTrue(url.query.contains("pageToken=next-fixture"))
            val suffix = if (next) ",\"nextPageToken\":\"next-fixture\"" else ""
            val id = if (next) "first" else "older"
            Connection(url, body = """{"files":[{"id":"$id","name":"Fixture","createdTime":"2026-11-14","size":"9"}]$suffix}""".toByteArray())
        }
        assertEquals(listOf("first", "older"), drive.list("fixture").map { it.id })
        assertEquals(2, calls)
    }
    @Test fun `short oversized and cancelled downloads remove partial local archive`() {
        for ((bytes, size) in listOf("short" to 6L, "oversized" to 3L)) {
            val file = archive()
            val drive = GoogleDriveBackup { url -> Connection(url, body = bytes.toByteArray()) }
            assertThrows(IllegalArgumentException::class.java) { drive.download(DriveSnapshot("fixture", "Fixture", "", size), file, "token") }
            assertFalse(file.exists())
        }
        val file = archive()
        val drive = GoogleDriveBackup { url -> Connection(url, body = "fixture".toByteArray()) }
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            drive.download(DriveSnapshot("fixture", "Fixture", "", 7), file, "token") { throw kotlinx.coroutines.CancellationException() }
        }
        assertFalse(file.exists())
    }
    @Test fun `expired authorization does not masquerade as an empty backup list`() {
        val drive = GoogleDriveBackup { url -> Connection(url, code = 401) }
        assertThrows(DriveReconnectRequired::class.java) { drive.list("expired") }
    }
    @Test fun `activity recreation and background require new authentication`() {
        val session = AppLockSession()
        assertTrue(session.requiresUnlock(true)); assertFalse(session.requiresUnlock(false))
        session.authenticate(); assertFalse(session.requiresUnlock(true))
        session.lock(); assertTrue(session.requiresUnlock(true))
        session.authenticate(); assertTrue(AppLockSession().requiresUnlock(true))
    }
    @Test fun `persisted app lock survives recreation and corrupt preference fails closed`() {
        val context = RuntimeEnvironment.getApplication()
        AppLock.setEnabled(context, true); assertTrue(AppLock.enabled(context))
        context.getSharedPreferences("app-lock", 0).edit().putString("enabled", "invalid").commit()
        assertTrue(AppLock.enabled(context))
        AppLock.setEnabled(context, false); assertFalse(AppLock.enabled(context))
    }
}
