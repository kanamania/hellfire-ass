package com.hellfire.ass.security

import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.media.*
import kotlinx.coroutines.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.*
import java.io.*
import java.security.*
import java.util.*
import java.util.zip.*

class PortableBackup(private val app: HellFireApp) {
    private val prefix = "HFABACK2".toByteArray(Charsets.US_ASCII)
    suspend fun export(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(!com.hellfire.ass.audio.MicrophoneStatus.busy.value) { "Stop microphone recording first" }
        val salt = SecureRandom().generateSeed(16)
        val portable = try { BackupCrypto.derive(password, salt) } finally { password.fill('\u0000') }
        val encrypted = File(app.noBackupFilesDir, "backup-${UUID.randomUUID()}.hfm")
        try {
            app.media.exclusive {
                coroutineScope {
                    val pipe = PipedInputStream(65536)
                    val output = PipedOutputStream(pipe)
                    val producer = async(Dispatchers.IO) { output.use { ZipOutputStream(it).use { zip ->
                        var media = emptyList<MediaItem>()
                        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(JSONObject().put("format", 1).put("schema", 8).toString().toByteArray()); zip.closeEntry()
                        val counts = app.database.withTransaction {
                            media = app.database.media().all()
                            require(media.none { it.state == "DELETING" })
                            require(media.fold(0L) { total, item -> Math.addExact(total, item.size) } <= 7L * 1024 * 1024 * 1024) { "Originals exceed the 7 GiB backup limit" }
                            BackupTables.write(app.database.openHelper.writableDatabase, zip, app.cipher)
                        }
                        for (item in media) {
                            ensureActive()
                            zip.putNextEntry(ZipEntry("media/${item.id}"))
                            app.media.reader(item).use { reader -> reader.stream().use { input -> copyBounded(input, zip, item.size) } }
                            zip.closeEntry()
                        }
                        zip.putNextEntry(ZipEntry("complete.json")); zip.write(JSONObject().put("tables", JSONObject(counts)).put("media", JSONArray(media.map { it.id })).toString().toByteArray()); zip.closeEntry()
                    } } }
                    try { pipe.use { ChunkedMedia.write(it, encrypted, UUID.randomUUID().toString(), false, portable) }; producer.await() }
                    finally { runCatching { pipe.close() }; runCatching { output.close() } }
                }
            }
            app.contentResolver.openOutputStream(uri, "wt")!!.use { output ->
                output.write(prefix); output.write(salt)
                encrypted.inputStream().use { copyBounded(it, output, 9L * 1024 * 1024 * 1024) }
            }
        } finally { encrypted.delete() }
    }
    suspend fun restore(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(!com.hellfire.ass.audio.MicrophoneStatus.busy.value)
        require(!com.hellfire.ass.sms.SmsCaptureSettings.enabled(app)) { "Disable SMS capture before restore" }
        require(!androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) { "Disable notification access before restore" }
        require(app.secrets.read("mail") == null && app.secrets.read("gateway") == null) { "Disconnect integrations before restore" }
        requireEmpty()
        val stageId = "restore-${UUID.randomUUID()}"
        val folder = File(app.noBackupFilesDir, stageId).apply { mkdirs() }
        val encrypted = File(folder, "archive.hfm")
        var staging: EventDatabase? = null
        val moved = mutableListOf<String>()
        var committed = false
        try {
            val portable = app.contentResolver.openInputStream(uri)!!.use { input ->
                val header = ByteArray(24); DataInputStream(input).readFully(header)
                require(header.copyOfRange(0, 8).contentEquals(prefix)) { "Unsupported backup format" }
                val key = try { BackupCrypto.derive(password, header.copyOfRange(8, 24)) } finally { password.fill('\u0000') }
                encrypted.outputStream().use { copyBounded(input, it, 9L * 1024 * 1024 * 1024) }; key
            }
            val stageKey = Base64.getEncoder().encode(SecureRandom().generateSeed(32))
            staging = try { Room.databaseBuilder(app, EventDatabase::class.java, File(folder, "stage.db").path)
                .openHelperFactory(SupportOpenHelperFactory(stageKey)).build().also { it.openHelper.writableDatabase } }
                finally { stageKey.fill(0) }
            val db = staging
            ChunkedMedia.Reader(encrypted, portable).use { reader ->
                ZipInputStream(BufferedInputStream(reader.stream())).use { zip ->
                    require(zip.nextEntry?.name == "manifest.json")
                    val manifest = JSONObject(readSmall(zip))
                    require(manifest.getInt("format") == 1 && manifest.getInt("schema") == 8) { "Backup schema requires a matching app version" }
                    val counts = linkedMapOf<String, Long>()
                    val budget = BackupTables.Budget()
                    db.withTransaction {
                        for (table in BackupTables.tables) {
                            require(zip.nextEntry?.name == "tables/$table.jsonl") { "Backup tables are missing or reordered" }
                            counts[table] = BackupTables.readTable(db.openHelper.writableDatabase, zip, table, app.cipher, budget)
                        }
                    }
                    val items = db.media().all()
                    require(items.size <= 100000)
                    require(items.all { it.size > 0 } && items.fold(0L) { total, item -> Math.addExact(total, item.size) } <= 7L * 1024 * 1024 * 1024)
                    val restored = linkedSetOf<String>()
                    repeat(items.size) {
                        val entry = zip.nextEntry ?: error("Original missing from backup")
                        val id = entry.name.removePrefix("media/")
                        UUID.fromString(id)
                        val metadata = items.firstOrNull { it.id == id } ?: error("Unexpected original")
                        require(entry.name == "media/$id" && restored.add(id))
                        val digest = MessageDigest.getInstance("SHA-256")
                        val result = ChunkedMedia.write(DigestInputStream(NonClosingInput(zip), digest), File(folder, "$id.hfm"), id,
                            metadata.mime == "audio/pcm", app.cipher)
                        require(result.size == metadata.size)
                        require(digest.digest().joinToString("") { "%02x".format(it) } == metadata.contentHash) { "Original hash mismatch" }
                    }
                    require(zip.nextEntry?.name == "complete.json") { "Incomplete backup" }
                    val complete = JSONObject(readSmall(zip))
                    val expected = complete.getJSONObject("tables")
                    require(expected.keys().asSequence().toSet() == counts.keys && counts.all { (table, count) -> expected.getLong(table) == count })
                    val mediaIds = complete.getJSONArray("media").let { a -> (0 until a.length()).map { a.getString(it) } }
                    require(mediaIds.toSet() == restored && mediaIds.size == restored.size)
                    require(zip.nextEntry == null) { "Unexpected backup data" }
                }
            }
            BackupTables.revokeRestoredApprovals(db.openHelper.writableDatabase)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { app.media.exclusive {
                app.database.withTransaction {
                    requireEmpty()
                    val items = db.media().all()
                    require(items.none { app.media.file(it.id).exists() }) { "Existing orphan originals require recovery before restore" }
                    app.secrets.save("restore-journal", JSONObject().put("ids", JSONArray(items.map { it.id })))
                    for (item in items) {
                        java.nio.file.Files.move(File(folder, "${item.id}.hfm").toPath(), app.media.file(item.id).toPath())
                        moved += item.id
                    }
                    val target = app.database.openHelper.writableDatabase
                    for (table in listOf("capture_audit", "notification_policy", "daily_review")) target.execSQL("DELETE FROM $table")
                    BackupTables.copy(db.openHelper.writableDatabase, target)
                }
                committed = true
                app.secrets.remove("restore-journal")
            } }
            runCatching { com.hellfire.ass.tasks.TaskScheduler.restore(app); MediaWork.recover(app) }
        } finally {
            password.fill('\u0000')
            runCatching { staging?.close() }
            if (!committed) moved.forEach { app.media.file(it).delete() }
            if (!committed) app.secrets.remove("restore-journal")
            folder.deleteRecursively()
        }
    }
    private suspend fun requireEmpty() {
        val db = app.database.openHelper.writableDatabase
        for (table in listOf("source_event", "finance_account", "ledger_entry", "assistant_task", "outbound_draft", "memory_item", "media_item")) {
            db.query("SELECT COUNT(*) FROM $table").use { require(it.moveToFirst() && it.getLong(0) == 0L) { "Restore requires an empty local database; existing data is never replaced" } }
        }
    }
    private fun readSmall(input: InputStream): String {
        val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
        while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 8 * 1024 * 1024); output.write(buffer, 0, count) }
        return output.toString(Charsets.UTF_8.name())
    }
    private suspend fun copyBounded(input: InputStream, output: OutputStream, maximum: Long) {
        val buffer = ByteArray(65536); var count = 0L
        try { while (true) { currentCoroutineContext().ensureActive(); val n = input.read(buffer); if (n < 0) break
            require(n > 0); count += n; require(count <= maximum); output.write(buffer, 0, n) }
        } finally { buffer.fill(0) }
    }
    private class NonClosingInput(input: InputStream) : FilterInputStream(input) { override fun close() {} }
}
