package com.hellfire.ass.security

import android.net.Uri
import androidx.room.Room
import androidx.room.withTransaction
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.data.EventDatabase
import com.hellfire.ass.library.BackupItemRecord
import com.hellfire.ass.library.BackupManifest
import com.hellfire.ass.media.*
import kotlinx.coroutines.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.*
import java.io.*
import java.security.*
import java.util.*
import java.util.zip.*


class PortableBackup(
    private val app: HellFireApp,
    private val stagingDatabase: (File, ByteArray) -> EventDatabase = { file, key ->
        Room.databaseBuilder(app, EventDatabase::class.java, file.path).openHelperFactory(SupportOpenHelperFactory(key)).build()
    },
) {
    private val prefix = "HFABACK2".toByteArray(Charsets.US_ASCII)
    suspend fun export(uri: Uri, password: CharArray, incremental: Boolean = false): BackupOutcome =
        export(password, incremental, uri.toString(), true, { app.contentResolver.openInputStream(uri) ?: error("Backup destination cannot be read back") }) {
            app.contentResolver.openOutputStream(uri, "wt") ?: error("Destination unavailable") }
    suspend fun export(file: File, password: CharArray, incremental: Boolean = false): BackupOutcome =
        export(password, incremental, file.toURI().toString(), true, { file.inputStream() }) { file.outputStream() }
    /**
     * Writes a full archive to [file] for upload. Nothing is marked backed up here: call [commit] only after the
     * destination confirmed the complete archive. Blocked originals are listed in the outcome instead of thrown.
     */
    suspend fun exportForUpload(file: File, password: CharArray): BackupOutcome =
        export(password, false, file.toURI().toString(), false, { file.inputStream() }) { file.outputStream() }
    /** Records an [exportForUpload] archive as backed up once [destination] confirmed it under [remoteRef]. */
    suspend fun commit(outcome: BackupOutcome, destination: String, remoteRef: String) = withContext(Dispatchers.IO) {
        val pending = requireNotNull(outcome.pending) { "Archive has no pending record" }
        record(pending.id, pending.plan, pending.verified, pending.previous, pending.baseId, pending.archiveHash, remoteRef, destination)
    }
    private suspend fun export(password: CharArray, incremental: Boolean, destinationRef: String, commit: Boolean,
        readBack: () -> InputStream, destination: () -> OutputStream): BackupOutcome = withContext(Dispatchers.IO) {
        require(!com.hellfire.ass.audio.MicrophoneStatus.busy.value) { "Stop microphone recording first" }
        val archiveId = UUID.randomUUID().toString()
        val salt = SecureRandom().generateSeed(16)
        val encrypted = File(app.noBackupFilesDir, "backup-${UUID.randomUUID()}.hfm")
        var portable: PayloadCipher? = null
        try {
            // Rows of permanently deleted drawer items must not be carried into a new archive.
            com.hellfire.ass.drawer.DrawerInbox.purgeDeletedRows(app)
            var planned: BackupPlan? = null
            var previous = emptySet<String>()
            var baseId: String? = null
            var media = emptyList<MediaItem>()
            app.media.exclusive {
                media = app.database.media().all()
                require(media.none { it.state == "DELETING" })
                baseId = if (incremental) availableBackups().firstOrNull() else null
                previous = verifiedHashes(baseId)
                planned = BackupPlanner.plan(media, previous, app.noBackupFilesDir.usableSpace, incremental) { app.media.file(it.id).isFile }
            }
            portable = try { BackupCrypto.derive(password, salt) } finally { password.fill('\u0000') }
            val key = portable!!
            app.media.exclusive {
                media = app.database.media().all()
                require(media.none { it.state == "DELETING" })
                baseId = if (incremental) availableBackups().firstOrNull() else null
                previous = verifiedHashes(baseId)
                planned = BackupPlanner.plan(media, previous, app.noBackupFilesDir.usableSpace, incremental) { app.media.file(it.id).isFile }
                val current = planned!!
                val dbBytes = app.getDatabasePath("hellfire.db").length().coerceAtMost(1024L * 1024 * 1024)
                require(app.noBackupFilesDir.usableSpace >= BackupReadiness.stagingBytes(current.bytesIncluded(), dbBytes)) { "Insufficient free space for encrypted backup staging" }
                coroutineScope {
                    val pipe = PipedInputStream(65536)
                    val output = PipedOutputStream(pipe)
                    val producer = async(Dispatchers.IO) { output.use { ZipOutputStream(it).use { zip -> writeFormat2(zip, archiveId, current, media, baseId) } } }
                    try { pipe.use { ChunkedMedia.write(it, encrypted, UUID.randomUUID().toString(), false, key, maxBytes = BackupReadiness.MAX_ARCHIVE) }; producer.await() }
                    finally { runCatching { pipe.close() }; runCatching { output.close() } }
                }
            }
            val written = planned!!
            val verified = verifyFormat2(encrypted, key, written)
            if (verified != written.includedHashes.toSet()) error("Backup verification failed")
            var archiveHash = ""
            destination().use { output ->
                output.write(prefix); output.write(salt)
                archiveHash = hashCopy(encrypted, output)
            }
            // Verify what the destination returns, not the local staging copy.
            readBack().use { input ->
                val header = ByteArray(24); DataInputStream(input).readFully(header)
                require(header.contentEquals(prefix + salt)) { "Backup destination returned a different archive" }
                val (actual, size) = BackupStream.copy(input, Discard(), encrypted.length())
                require(actual == archiveHash && size == encrypted.length()) { "Backup destination returned a different archive" }
            }
            val pending = PendingBackup(archiveId, written, verified, previous, baseId, archiveHash)
            val outcome = BackupOutcome(archiveId, written.kind, written.includedHashes.size, written.items.count { it.action == BackupAction.OMITTED },
                written.blocked, true, pending.takeUnless { commit })
            if (commit) {
                record(archiveId, written, verified, previous, baseId, archiveHash, destinationRef, "portable")

            }
            outcome
        } finally { password.fill('\u0000'); encrypted.delete() }
    }
    suspend fun restore(uri: Uri, password: CharArray): RestoreReport = restore(listOf(uri), password)
    /**
     * Restores one archive, or an incremental archive together with the older archives it builds on, chosen in any
     * order: the newest archive supplies the database and the others supply the originals it omitted.
     */
    suspend fun restore(uris: List<Uri>, password: CharArray, selection: BackupSelection? = null): RestoreReport =
        restoreSet(uris.map { uri -> { app.contentResolver.openInputStream(uri) ?: error("Backup unavailable") } }, password, selection)
    suspend fun restoreFiles(files: List<File>, password: CharArray, selection: BackupSelection? = null): RestoreReport =
        restoreSet(files.map { file -> { file.inputStream() } }, password, selection)
    suspend fun restore(file: File, password: CharArray, parents: List<File> = emptyList(), selection: BackupSelection? = null): RestoreReport =
        restore(password, parents = parents.map { parent -> { parent.inputStream() } }, selection = selection) { file.inputStream() }
    /** Verify-only dry run of the newest selected archive that also lists what it holds. Current data is not changed. */
    suspend fun inspect(uris: List<Uri>, password: CharArray): RestoreReport =
        restoreSet(uris.map { uri -> { app.contentResolver.openInputStream(uri) ?: error("Backup unavailable") } }, password, null, verifyOnly = true)
    suspend fun inspectFiles(files: List<File>, password: CharArray): RestoreReport =
        restoreSet(files.map { file -> { file.inputStream() } }, password, null, verifyOnly = true)
    private suspend fun restoreSet(sources: List<() -> InputStream>, password: CharArray, selection: BackupSelection?, verifyOnly: Boolean = false): RestoreReport = try {
        require(sources.isNotEmpty()) { "Select a backup archive" }
        val ordered = if (sources.size == 1) sources else withContext(Dispatchers.IO) {
            sources.map { it to manifestOf(password.copyOf(), it).optLong("createdAt") }.sortedByDescending { it.second }.map { it.first }
        }
        restore(password, verifyOnly, ordered.drop(1), selection, ordered.first())
    } finally { password.fill('\u0000') }
    suspend fun catalog(file: File, password: CharArray): List<PlannedItem> = withContext(Dispatchers.IO) {
        val folder = File(app.noBackupFilesDir, "catalog-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val key = openHeader(password, file.inputStream(), File(folder, "archive.hfm"))
            ChunkedMedia.Reader(File(folder, "archive.hfm"), key, maxBytes = BackupReadiness.MAX_ARCHIVE).use { reader ->
                ZipInputStream(BufferedInputStream(reader.stream())).use { zip ->
                    require(zip.nextEntry?.name == "manifest.json")
                    val manifest = JSONObject(readSmall(zip))
                    if (manifest.optInt("format", 1) != 2) return@withContext emptyList()
                    require(zip.nextEntry?.name == "catalog.jsonl")
                    readCatalog(zip)
                }
            }
        } finally { password.fill('\u0000'); folder.deleteRecursively() }
    }
    suspend fun verify(uri: Uri, password: CharArray): RestoreReport =
        restore(password, verifyOnly = true) { app.contentResolver.openInputStream(uri) ?: error("Backup unavailable") }
    private suspend fun restore(password: CharArray, verifyOnly: Boolean = false, parents: List<() -> InputStream> = emptyList(), selection: BackupSelection? = null, source: () -> InputStream): RestoreReport = withContext(Dispatchers.IO) {
        if (!verifyOnly && selection == null) checkRestoreAllowed()
        else if (!verifyOnly) require(!com.hellfire.ass.audio.MicrophoneStatus.busy.value) { "Stop microphone recording first" }
        val parentPassword = if (parents.isNotEmpty()) password.copyOf() else null
        val stageId = "restore-${UUID.randomUUID()}"
        val folder = File(app.noBackupFilesDir, stageId).apply { mkdirs() }
        val encrypted = File(folder, "archive.hfm")
        var staging: EventDatabase? = null
        val moved = mutableListOf<String>()
        var committed = false
        var format2 = false
        var selectedIds = emptyList<String>()
        var unavailable = emptySet<String>()
        var manifestJson: JSONObject? = null
        try {
            val portable = source().use { input ->
                val header = ByteArray(24); DataInputStream(input).readFully(header)
                require(header.copyOfRange(0, 8).contentEquals(prefix)) { "Unsupported backup format" }
                val key = try { BackupCrypto.derive(password, header.copyOfRange(8, 24)) } finally { password.fill('\u0000') }
                encrypted.outputStream().use { copyBounded(input, it, BackupReadiness.MAX_ARCHIVE) }; key
            }
            val stageKey = Base64.getEncoder().encode(SecureRandom().generateSeed(32))
            staging = try { stagingDatabase(File(folder, "stage.db"), stageKey).also { it.openHelper.writableDatabase } }
                finally { stageKey.fill(0) }
            val db = staging
            ChunkedMedia.Reader(encrypted, portable, maxBytes = BackupReadiness.MAX_ARCHIVE).use { reader ->
                ZipInputStream(BufferedInputStream(reader.stream())).use { zip ->
                    require(zip.nextEntry?.name == "manifest.json")
                    val manifest = JSONObject(readSmall(zip))
                    manifestJson = manifest
                    val format = manifest.optInt("format", 1)
                    if (format == 2) {
                        format2 = true
                        val read = readFormat2(zip, db, folder, manifest, verifyOnly, selection, parents, parentPassword)
                        selectedIds = read.wanted; unavailable = read.unavailable
                        return@use
                    }
                    require(format == 1) { "Backup format requires a matching app version" }
                    val backupTables = BackupTables.tablesFor(manifest.getInt("schema"))
                    val counts = linkedMapOf<String, Long>()
                    val budget = BackupTables.Budget()
                    db.withTransaction {
                        for (table in backupTables) {
                            require(zip.nextEntry?.name == "tables/$table.jsonl") { "Backup tables are missing or reordered" }
                            counts[table] = BackupTables.readTable(db.openHelper.writableDatabase, zip, table, app.cipher, budget)
                        }
                    }
                    val items = db.media().all()
                    require(items.size <= 100000)
                    require(items.all { it.size > 0 } && items.fold(0L) { total, item -> Math.addExact(total, item.size) } <= BackupReadiness.MAX_ORIGINALS)
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
            com.hellfire.ass.contacts.ContactSchema.backfill(db.openHelper.writableDatabase)
            com.hellfire.ass.library.LibrarySchema.backfill(db.openHelper.writableDatabase)
            val missing = db.media().all().filter { it.id in unavailable }.map { it.name }
            if (verifyOnly) {
                app.getSharedPreferences("ui", 0).edit().putLong("last_backup_verified_at", System.currentTimeMillis()).apply()
                return@withContext describe(db, missing)
            }
            BackupTables.revokeRestoredApprovals(db.openHelper.writableDatabase)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { require(!it.moveToFirst()) }
            currentCoroutineContext().ensureActive()
            if (format2 && selection != null) {
                withContext(NonCancellable) { app.media.exclusive {
                    app.database.withTransaction {
                        for (id in selectedIds) {
                            val local = app.database.media().item(id) ?: continue
                            val incoming = requireNotNull(db.media().item(id))
                            require(local.storage == incoming.storage && local.contentHash == incoming.contentHash && local.size == incoming.size && local.state !in setOf("DELETING", "SYNC_REMOVED")) {
                                "Existing local original differs; it was not replaced"
                            }
                        }
                        val newlyAdded = selectedIds.filter { app.database.media().item(it) == null && !app.media.file(it).exists() }
                        app.secrets.save("restore-journal", JSONObject().put("ids", JSONArray(newlyAdded)))
                        SelectiveRestore.copy(db.openHelper.writableDatabase, app.database.openHelper.writableDatabase, selectedIds)
                        for (id in selectedIds) {
                            val staged = File(folder, "$id.hfm")
                            if (!staged.exists()) continue
                            val dest = app.media.file(id)
                            if (dest.exists()) { staged.delete(); continue }
                            java.nio.file.Files.move(staged.toPath(), dest.toPath())
                            moved += id
                            app.database.library().setAvailability(id, "AVAILABLE")
                        }
                        app.database.library().rebuildSearch()
                    }
                    committed = true
                    app.secrets.remove("restore-journal")
                } }
            } else {
                withContext(NonCancellable) { app.media.exclusive {
                    app.database.withTransaction {
                        requireEmpty()
                        val items = db.media().all()
                        require(items.none { app.media.file(it.id).exists() }) { "Existing orphan originals require recovery before restore" }
                        app.secrets.save("restore-journal", JSONObject().put("ids", JSONArray(items.map { it.id })))
                        for (item in items) {
                            if (item.storage != "MANAGED" || item.id in unavailable) continue
                            val staged = File(folder, "${item.id}.hfm")
                            require(staged.exists()) { "Original missing from backup" }
                            java.nio.file.Files.move(staged.toPath(), app.media.file(item.id).toPath())
                            moved += item.id
                        }
                        val target = app.database.openHelper.writableDatabase
                        for (table in listOf("capture_audit", "notification_policy", "daily_review")) target.execSQL("DELETE FROM $table")
                        BackupTables.copy(db.openHelper.writableDatabase, target)
                    }
                    committed = true
                    app.secrets.remove("restore-journal")
                } }
                if (format2) applySettings(manifestJson)
                runCatching { com.hellfire.ass.tasks.TaskScheduler.restore(app) }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }
                runCatching { MediaWork.recover(app) }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }
                runCatching { com.hellfire.ass.data.SearchBackfill.reset(app) }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }
                runCatching { app.database.library().rebuildSearch() }.onFailure { com.hellfire.ass.Diagnostics.warn(it) }
            }
            RestoreReport(moved.size, missing)
        } finally {
            parentPassword?.fill('\u0000')
            password.fill('\u0000')
            runCatching { staging?.close() }
            if (!committed) moved.forEach { app.media.file(it).delete() }
            if (!committed && !verifyOnly) app.secrets.remove("restore-journal")
            folder.deleteRecursively()
        }
    }
    suspend fun checkRestoreAllowed() = withContext(Dispatchers.IO) {
        require(!com.hellfire.ass.audio.MicrophoneStatus.busy.value)
        require(!com.hellfire.ass.sms.SmsCaptureSettings.enabled(app)) { "Disable SMS capture before restore" }
        require(!androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(app).contains(app.packageName)) { "Disable notification access before restore" }
        require(!com.hellfire.ass.communications.Accounts.any(app.secrets)) { "Disconnect email and WhatsApp accounts before restore" }
        requireEmpty()
    }
    private suspend fun requireEmpty() {
        val db = app.database.openHelper.writableDatabase
        for (table in BackupTables.tables.filterNot { it in setOf("capture_audit", "notification_policy", "daily_review") }) {
            db.query("SELECT COUNT(*) FROM $table").use { require(it.moveToFirst() && it.getLong(0) == 0L) { "Restore requires an empty local database; existing data is never replaced" } }
        }
    }

    private suspend fun writeFormat2(zip: ZipOutputStream, id: String, plan: BackupPlan, media: List<MediaItem>, baseId: String?) {
        val manifest = JSONObject()
            .put("format", 2).put("schema", BackupTables.SCHEMA).put("id", id).put("kind", plan.kind)
            .put("baseId", baseId ?: JSONObject.NULL).put("createdAt", System.currentTimeMillis()).put("keep", keep())
            .put("recovery", BackupPlanner.recoveryMetadata()).put("settings", settingsSnapshot())
        require(!manifest.getJSONObject("recovery").getBoolean("keyStored"))
        require(!manifest.toString().contains("passphrase", ignoreCase = true))
        zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest.toString().toByteArray()); zip.closeEntry()
        zip.putNextEntry(ZipEntry("catalog.jsonl"))
        for (item in plan.items) zip.write((item.toJson().toString() + "\n").toByteArray())
        zip.closeEntry()
        val counts = app.database.withTransaction { BackupTables.write(app.database.openHelper.writableDatabase, zip, app.cipher) }
        for ((index, hash) in plan.includedHashes.withIndex()) {
            currentCoroutineContext().ensureActive()
            app.getSharedPreferences("ui", 0).edit().putString("backup_progress", "${index + 1}/${plan.includedHashes.size}").apply()
            val includedId = plan.items.first { it.action == BackupAction.INCLUDE && it.contentHash == hash }.id
            val item = media.first { it.id == includedId }
            zip.putNextEntry(ZipEntry("blobs/$hash"))
            app.media.reader(item).use { reader -> reader.stream().use { input ->
                val (actual, size) = BackupStream.copy(input, zip, item.size)
                require(actual == hash && size == item.size) { "Original hash mismatch" }
            } }
            zip.closeEntry()
        }
        val complete = JSONObject().put("tables", JSONObject(counts))
            .put("included", JSONArray(plan.includedHashes))
            .put("omitted", JSONArray(plan.items.filter { it.action == BackupAction.OMITTED }.map { it.id }))
            .put("blocked", JSONArray(plan.blocked.map { it.toJson() }))
        zip.putNextEntry(ZipEntry("complete.json")); zip.write(complete.toString().toByteArray()); zip.closeEntry()
    }

    private fun verifyFormat2(file: File, portable: PayloadCipher, plan: BackupPlan): Set<String> {
        val verified = linkedSetOf<String>()
        ChunkedMedia.Reader(file, portable, maxBytes = BackupReadiness.MAX_ARCHIVE).use { reader ->
            ZipInputStream(BufferedInputStream(reader.stream())).use { zip ->
                require(zip.nextEntry?.name == "manifest.json")
                val manifest = JSONObject(readSmall(zip))
                require(manifest.getInt("format") == 2 && !manifest.getJSONObject("recovery").getBoolean("keyStored"))
                require(zip.nextEntry?.name == "catalog.jsonl")
                val catalog = readCatalog(zip)
                require(catalog.map { it.id } == plan.items.map { it.id })
                for (table in BackupTables.tablesFor(manifest.getInt("schema"))) {
                    require(zip.nextEntry?.name == "tables/$table.jsonl")
                    discard(zip)
                }
                for (hash in plan.includedHashes) {
                    require(zip.nextEntry?.name == "blobs/$hash")
                    val expected = catalog.first { it.action == BackupAction.INCLUDE && it.contentHash == hash }
                    val (actual, size) = BackupStream.copy(NonClosingInput(zip), Discard(), expected.size)
                    if (actual == hash && size == expected.size) verified += hash
                }
                require(zip.nextEntry?.name == "complete.json")
                require(zip.nextEntry == null)
            }
        }
        return verified
    }

    private class Format2(val wanted: List<String>, val unavailable: Set<String>)

    private suspend fun readFormat2(zip: ZipInputStream, db: EventDatabase, folder: File, manifest: JSONObject, verifyOnly: Boolean, selection: BackupSelection?, parents: List<() -> InputStream>, parentPassword: CharArray?): Format2 {
        require(!manifest.getJSONObject("recovery").getBoolean("keyStored"))
        require(zip.nextEntry?.name == "catalog.jsonl")
        val catalog = readCatalog(zip)
        val tables = BackupTables.tablesFor(manifest.getInt("schema"))
        val counts = linkedMapOf<String, Long>()
        val budget = BackupTables.Budget()
        db.withTransaction {
            for (table in tables) {
                require(zip.nextEntry?.name == "tables/$table.jsonl") { "Backup tables are missing or reordered" }
                counts[table] = BackupTables.readTable(db.openHelper.writableDatabase, zip, table, app.cipher, budget)
            }
        }
        // Blocked and metadata-only rows keep their records (bookmarks, transcripts, notes) and are restored as MISSING.
        val unavailable = catalog.filter { it.action == BackupAction.BLOCKED || it.action == BackupAction.METADATA_ONLY }.map { it.id }.toSet()
        if (selection != null && selection.itemIds.any { id -> id in unavailable }) error("Selected original was not included in this backup")
        for (id in unavailable) db.openHelper.writableDatabase.execSQL("UPDATE media_item SET availability = 'MISSING' WHERE id = ?", arrayOf(id))
        val wanted = if (selection == null) db.media().all().map { it.id }
            else SelectiveRestore.resolve(db.openHelper.writableDatabase, selection).filter { it !in unavailable }
        db.openHelper.writableDatabase.execSQL("UPDATE media_item SET availability = 'UNVERIFIED' WHERE storage = 'EXTERNAL'")
        val items = db.media().all().filter { it.storage == "MANAGED" && it.id in wanted && it.id !in unavailable }
        val byHash = items.groupBy { requireNotNull(it.contentHash) }
        for (hash in catalog.filter { it.action == BackupAction.INCLUDE }.map { it.contentHash }.distinct()) {
            require(zip.nextEntry?.name == "blobs/$hash")
            val group = byHash[hash].orEmpty()
            if (verifyOnly || group.isEmpty()) {
                val (actual, size) = BackupStream.copy(NonClosingInput(zip), Discard(), BackupReadiness.MAX_ARCHIVE)
                require(actual == hash && size == catalog.first { it.contentHash == hash && it.action == BackupAction.INCLUDE }.size)
            } else stageBlob(zip, hash, group.first().size, group, folder)
        }
        require(zip.nextEntry?.name == "complete.json") { "Incomplete backup" }
        val complete = JSONObject(readSmall(zip))
        require(complete.getJSONObject("tables").let { expected -> expected.keys().asSequence().toSet() == counts.keys && counts.all { (table, count) -> expected.getLong(table) == count } })
        require(zip.nextEntry == null)
        if (!verifyOnly) {
            val missing = items.filter { !File(folder, "${it.id}.hfm").exists() }.groupBy { requireNotNull(it.contentHash) }
            if (missing.isNotEmpty()) {
                require(parentPassword != null && parents.isNotEmpty()) { "Incremental backup is missing ${missing.size} originals; select the older archives it builds on too" }
                fillFromParents(parents, parentPassword, missing, folder)
            }
        }
        return Format2(wanted, unavailable)
    }

    private fun stageBlob(zip: InputStream, hash: String, size: Long, items: List<MediaItem>, folder: File) {
        val first = items.first()
        val digest = MessageDigest.getInstance("SHA-256")
        val result = ChunkedMedia.write(DigestInputStream(NonClosingInput(zip), digest), File(folder, "${first.id}.hfm"), first.id, first.mime == "audio/pcm", app.cipher)
        require(result.size == size && digest.digest().joinToString("") { "%02x".format(it) } == hash) { "Original hash mismatch" }
        for (extra in items.drop(1)) copyVault(File(folder, "${first.id}.hfm"), first, extra, folder)
    }

    private fun copyVault(source: File, from: MediaItem, to: MediaItem, folder: File) {
        ChunkedMedia.Reader(source, app.cipher, from.id, from.size).use { reader ->
            reader.stream().use { input -> ChunkedMedia.write(input, File(folder, "${to.id}.hfm"), to.id, to.mime == "audio/pcm", app.cipher) }
        }
    }

    private suspend fun fillFromParents(parents: List<() -> InputStream>, password: CharArray, needed: Map<String, List<MediaItem>>, folder: File) {
        val remaining = needed.keys.toMutableSet()
        for (parent in parents) {
            if (remaining.isEmpty()) break
            val copy = password.copyOf()
            val temp = File(folder, "parent-${UUID.randomUUID()}.hfm")
            try {
                val key = openHeader(copy, parent(), temp)
                ChunkedMedia.Reader(temp, key, maxBytes = BackupReadiness.MAX_ARCHIVE).use { reader ->
                    ZipInputStream(BufferedInputStream(reader.stream())).use { zip ->
                        var entry = zip.nextEntry
                        while (entry != null && remaining.isNotEmpty()) {
                            when {
                                entry.name.startsWith("blobs/") -> {
                                    val hash = entry.name.removePrefix("blobs/")
                                    val group = needed[hash]
                                    if (group != null && hash in remaining) { stageBlob(zip, hash, group.first().size, group, folder); remaining.remove(hash) }
                                    else discard(zip)
                                }
                                entry.name.startsWith("media/") -> {
                                    val id = entry.name.removePrefix("media/")
                                    val match = needed.values.flatten().firstOrNull { it.id == id && it.contentHash in remaining }
                                    if (match != null) {
                                        stageBlob(zip, match.contentHash!!, match.size, needed[match.contentHash]!!, folder)
                                        remaining.remove(match.contentHash)
                                    } else discard(zip)
                                }
                                else -> discard(zip)
                            }
                            entry = zip.nextEntry
                        }
                    }
                }
            } finally { copy.fill('\u0000'); temp.delete() }
        }
        require(remaining.isEmpty()) { "Incremental backup is missing ${remaining.size} originals; select the older archives it builds on too" }
    }

    private suspend fun openHeader(password: CharArray, input: InputStream, encrypted: File): PayloadCipher = input.use {
        val header = ByteArray(24); DataInputStream(it).readFully(header)
        require(header.copyOfRange(0, 8).contentEquals(prefix)) { "Unsupported backup format" }
        val key = try { BackupCrypto.derive(password, header.copyOfRange(8, 24)) } finally { password.fill('\u0000') }
        encrypted.outputStream().use { output -> copyBounded(it, output, BackupReadiness.MAX_ARCHIVE) }
        key
    }

    private fun readCatalog(zip: ZipInputStream): List<PlannedItem> {
        val items = mutableListOf<PlannedItem>()
        val line = StringBuilder()
        while (true) {
            val char = zip.read()
            if (char < 0 || char == 10) {
                if (line.isEmpty()) break
                items += PlannedItem.fromJson(JSONObject(line.toString()))
                require(items.size <= 100000)
                line.clear()
                if (char < 0) break
            } else {
                require(line.length < 64 * 1024)
                line.append(char.toChar())
            }
        }
        return items
    }

    private suspend fun record(id: String, plan: BackupPlan, verified: Set<String>, previously: Set<String>, baseId: String?, archiveHash: String, destinationRef: String, destination: String) {
        val now = System.currentTimeMillis()
        val backed = BackupPlanner.backedUpIds(plan, verified, previously)
        val state = if (plan.blocked.isEmpty()) "VERIFIED" else "PARTIAL"
        app.database.sync().saveManifest(BackupManifest(id, destination, plan.kind, baseId, BackupTables.SCHEMA, state, plan.includedHashes.size, plan.bytesIncluded(), archiveHash, destinationRef, now, now, now,
            plan.blocked.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.id}: ${it.reason}" }))
        val rows = plan.items.filter { (it.action == BackupAction.INCLUDE || it.action == BackupAction.DEDUPE) && it.contentHash in verified }
            .map { BackupItemRecord(id, it.id, it.contentHash, it.size, now, now) }
        if (rows.isNotEmpty()) app.database.sync().saveBackupItems(rows)
        val touched = plan.items.map { it.id }.toSet()
        val blocked = plan.blocked.map { it.id }.toSet()
        val noOriginal = plan.metadataOnly.map { it.id }.toSet()
        val members = mutableMapOf<String, MutableList<String>>()
        app.database.openHelper.readableDatabase.query("SELECT inboxId, itemId FROM inbox_member").use { rowsCursor ->
            while (rowsCursor.moveToNext()) members.getOrPut(rowsCursor.getString(0)) { mutableListOf() } += rowsCursor.getString(1)
        }
        for ((inbox, itemIds) in members) {
            if (itemIds.none { it in touched }) continue
            val inboxState = when {
                itemIds.all { it in backed || it in noOriginal } -> "BACKED_UP"
                itemIds.any { it in blocked } -> "FAILED"
                else -> "PENDING"
            }
            app.database.inbox().setBackupState(inbox, inboxState, now)
        }
        // An incomplete drawer record has no original; its row is in this verified archive.
        app.database.openHelper.writableDatabase.execSQL("UPDATE inbox_item SET backupState = 'BACKED_UP', updatedAt = ? WHERE backupState = 'PENDING' AND NOT EXISTS (SELECT 1 FROM inbox_member m WHERE m.inboxId = inbox_item.id)", arrayOf(now))
        val listed = mutableListOf<Pair<String, Long>>()
        val parents = mutableMapOf<String, String?>()
        app.database.openHelper.readableDatabase.query("SELECT id, createdAt, baseId FROM backup_manifest").use { cursor ->
            while (cursor.moveToNext()) {
                listed += cursor.getString(0) to cursor.getLong(1)
                parents[cursor.getString(0)] = if (cursor.isNull(2)) null else cursor.getString(2)
            }
        }
        val writable = app.database.openHelper.writableDatabase
        for (old in BackupPlanner.retain(listed, keep(), parents)) writable.execSQL("DELETE FROM backup_manifest WHERE id = ?", arrayOf(old))
    }

    private fun availableBackups(): List<String> {
        val ids = mutableListOf<String>()
        app.database.openHelper.readableDatabase.query("SELECT id, remoteRef FROM backup_manifest WHERE destination = 'portable' AND state IN ('VERIFIED', 'PARTIAL') AND verifiedAt IS NOT NULL ORDER BY createdAt DESC").use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.isNull(1)) continue
                val readable = runCatching {
                    val uri = Uri.parse(cursor.getString(1))
                    if (uri.scheme == "file") File(requireNotNull(uri.path)).isFile
                    else app.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
                }.getOrDefault(false)
                if (readable) ids += cursor.getString(0)
            }
        }
        return ids
    }

    private fun verifiedHashes(baseId: String?): Set<String> {
        val readable = availableBackups().toSet()
        val available = mutableSetOf<String>()
        var next = baseId
        while (next != null && next in readable && available.add(next)) {
            next = app.database.openHelper.readableDatabase.query("SELECT baseId FROM backup_manifest WHERE id = ?", arrayOf(next)).use {
                if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
            }
        }
        val hashes = mutableSetOf<String>()
        app.database.openHelper.readableDatabase.query("SELECT manifestId, contentHash FROM backup_item WHERE verifiedAt IS NOT NULL").use { cursor ->
            while (cursor.moveToNext()) if (cursor.getString(0) in available) hashes += cursor.getString(1)
        }
        return hashes
    }

    private fun keep() = app.getSharedPreferences("ui", 0).getInt("backup_keep", BackupPlanner.DEFAULT_KEEP).coerceAtLeast(0)

    private fun settingsSnapshot(): JSONObject {
        val root = JSONObject()
        for ((name, keys) in BackupPlanner.settings) {
            val prefs = app.getSharedPreferences(name, 0)
            val obj = JSONObject()
            for (key in keys) if (prefs.contains(key)) when (val value = prefs.all[key]) {
                is String -> obj.put(key, value)
                is Boolean -> obj.put(key, value)
                is Int -> obj.put(key, value)
                is Long -> obj.put(key, value)
            }
            if (obj.length() > 0) root.put(name, obj)
        }
        return root
    }

    private fun applySettings(manifest: JSONObject?) {
        val settings = manifest?.optJSONObject("settings") ?: return
        for ((name, keys) in BackupPlanner.settings) {
            val obj = settings.optJSONObject(name) ?: continue
            val edit = app.getSharedPreferences(name, 0).edit()
            for (key in keys) if (obj.has(key) && !obj.isNull(key)) when (val value = obj.get(key)) {
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
                is Number -> if (key == "overdue_minutes") edit.putInt(key, value.toInt()) else edit.putLong(key, value.toLong())
            }
            edit.apply()
        }
    }

    private suspend fun describe(db: EventDatabase, missing: List<String>): RestoreReport {
        val sql = db.openHelper.readableDatabase
        val kinds = linkedMapOf<String, Int>()
        sql.query("SELECT contentKind, COUNT(*) FROM media_item WHERE storage = 'MANAGED' GROUP BY contentKind ORDER BY contentKind").use { while (it.moveToNext()) kinds[it.getString(0)] = it.getInt(1) }
        val collections = mutableListOf<Pair<String, String>>()
        sql.query("SELECT id, name FROM media_collection ORDER BY name LIMIT 200").use { while (it.moveToNext()) collections += it.getString(0) to it.getString(1) }
        val items = mutableListOf<Pair<String, String>>()
        sql.query("SELECT id, name FROM media_item WHERE storage = 'MANAGED' AND availability != 'MISSING' ORDER BY importedAt DESC LIMIT 300").use { while (it.moveToNext()) items += it.getString(0) to it.getString(1) }
        return RestoreReport(0, missing, kinds, collections, items)
    }

    /** Reads only the manifest at the start of an archive, streaming; nothing is staged. */
    private fun manifestOf(password: CharArray, source: () -> InputStream): JSONObject = source().use { raw ->
        val input = DataInputStream(BufferedInputStream(raw))
        val header = ByteArray(24); input.readFully(header)
        require(header.copyOfRange(0, 8).contentEquals(prefix)) { "Unsupported backup format" }
        val key = try { BackupCrypto.derive(password, header.copyOfRange(8, 24)) } finally { password.fill('\u0000') }
        ZipInputStream(SequentialChunks(input, key)).use { zip ->
            require(zip.nextEntry?.name == "manifest.json") { "Backup manifest missing" }
            JSONObject(readSmall(zip))
        }
    }

}
