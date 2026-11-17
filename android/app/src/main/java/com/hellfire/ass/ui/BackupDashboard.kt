package com.hellfire.ass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.security.*
import com.hellfire.ass.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun BackupDashboardScreen(app: HellFireApp) {
    val scope = rememberCoroutineScope()
    var usage by remember { mutableStateOf<StorageUsage?>(null) }
    var pending by remember { mutableStateOf<List<String>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var drive by remember { mutableStateOf("") }
    var driveFailure by remember { mutableStateOf<String?>(null) }
    val prefs = remember { app.getSharedPreferences("ui", 0) }
    var keep by remember { mutableStateOf(prefs.getInt("backup_keep", BackupPlanner.DEFAULT_KEEP)) }
    fun reload() {
        scope.launch {
            drive = DriveBackupWork.readStatus(app)
            driveFailure = DriveBackupWork.lastFailure(app)
            usage = withContext(Dispatchers.IO) { BackupStorage.usage(app) }
            pending = withContext(Dispatchers.IO) { app.database.sync().notBackedUp(20).map { "${it.name} · ${it.size / 1024} KiB · ${it.contentKind}" } }
        }
    }
    LaunchedEffect(Unit) { reload() }
    val usageNow = usage
    SectionCard(tr("Storage usage")) {
        if (usageNow == null) Note("Loading storage…") else KvList(listOf(
            "Managed originals" to "${usageNow.managedCount} · ${usageNow.managedBytes / (1024 * 1024)} MiB",
            "External index" to usageNow.externalCount.toString(),
            "Thumbnails" to "${usageNow.thumbnailBytes / 1024} KiB",
            "Cache" to "${usageNow.cacheBytes / (1024 * 1024)} MiB",
            "Models" to "${usageNow.modelBytes / (1024 * 1024)} MiB",
            "Database" to "${usageNow.databaseBytes / (1024 * 1024)} MiB",
            "Free space" to "${usageNow.freeBytes / (1024 * 1024)} MiB",
            "Verified backups" to "${usageNow.backedUp}",
            tr("Not backed up") to usageNow.notBackedUp.toString(),
        ))
        Note("Local originals stay on this phone. Remote availability is the last confirmed Drive upload, not a live quota from Google. Thumbnails are referenced by scenes and bookmarks and are not cleared.")
        SecondaryButton(tr("Clear cache"), {
            scope.launch {
                busy = true
                val freed = withContext(Dispatchers.IO) { BackupStorage.evictCache(app) }
                message = "Cleared ${freed / 1024} KiB of cache. Originals, thumbnails, pending shares and model downloads were not touched."
                busy = false
                reload()
            }
        }, Modifier.fillMaxWidth(), enabled = !busy)
    }
    SectionCard(tr("Google Drive backup")) {
        Note(drive.ifBlank { "Not configured" })
        driveFailure?.let { Banner("Last Drive backup problem: $it", Tone.Warning, null) }
    }
    SectionCard(tr("Backup records kept")) {
        Note("Backup history on this phone keeps the newest $keep archive records${if (keep == 0) " (all)" else ""}. Older records are forgotten, so the next incremental export includes those originals again.")
        Note("Archives you exported to a folder or drive are your files: HellFire cannot delete them. Google Drive archives this app created are pruned by the Drive setting below.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (option in listOf(3, 7, 14, 0)) SecondaryButton(if (option == 0) "All" else "$option", {
                keep = option; prefs.edit().putInt("backup_keep", option).apply()
                message = if (option == 0) "All backup records are kept." else "Keeping the newest $option backup records after the next backup."
            }, enabled = keep != option)
        }
    }
    SectionCard("Per-item backup status") {
        if (pending.isEmpty()) Note("Every managed original has a verified backup of its current hash, or there are no managed originals.")
        else pending.forEach { Note(it) }
        Note("An item is backed up only after its bytes were written and the archive was re-read and hash-checked. A blocked item is listed with a reason and is not marked backed up.")
    }
    message?.let { Banner(it, Tone.Info, null) }
    BackupScreen(app)
}

@Composable
fun StatusDashboardScreen(app: HellFireApp) {
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf<StatusSnapshot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    fun reload() { scope.launch { snap = withContext(Dispatchers.IO) { StatusReport.load(app) } } }
    LaunchedEffect(Unit) { reload() }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val secret = password.toCharArray(); password = ""
        scope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) { PortableBackup(app).verify(uri, secret) }
                message = "Archive verified. Current data was not changed."
            } catch (caught: Exception) {
                com.hellfire.ass.Diagnostics.warn(caught)
                message = "Verification failed. Nothing was marked backed up."
            } finally { secret.fill('\u0000'); busy = false; reload() }
        }
    }
    val current = snap
    if (current == null) Note("Loading status…") else {
        SectionCard(tr("Status dashboard")) {
            KvList(listOf(
                "Library items" to current.libraryItems.toString(),
                "Drawer captures" to current.drawerCaptures.toString(),
                "Waiting for analysis" to current.waitingAnalysis.toString(),
                "Active jobs" to current.activeJobs.toString(),
                "Failed jobs" to current.failedJobs.toString(),
                "Indexed items" to current.indexedItems.toString(),
                "Sync queue" to current.syncQueued.toString(),
                "Uploaded" to current.uploaded.toString(),
                "Pending uploads" to current.pendingUploads.toString(),
                "Pending bytes" to current.pendingBytes.toString(),
                "Backup" to current.backupState,
                tr("Not backed up") to current.notBackedUp.toString(),
                "Storage free" to "${current.storage.freeBytes / (1024 * 1024)} MiB",
                tr("Thermal state") to current.thermal,
                "Inference" to current.inference,
                "Analysis" to if (current.analysisPaused) "paused" else "running",
                "Library sync" to if (current.syncPaused) "paused" else "on",
                "Not in search index" to current.unindexed.toString(),
            ))
        }
        SectionCard(tr("Model availability")) {
            current.models.forEach { (name, state) -> Note("$name: $state") }
            Note("File present means the model file is on disk. It does not mean the model was loaded or timed on this device.")
        }
        SectionCard("Recent errors") {
            if (current.errors.isEmpty()) Note("No recorded errors.") else current.errors.forEach { Note(it) }
        }
        message?.let { Banner(it, Tone.Info, null) }
        SectionCard("Actions") {
            SecondaryButton(if (current.analysisPaused) tr("Resume analysis") else tr("Pause analysis"), {
                scope.launch {
                    busy = true
                    withContext(Dispatchers.IO) { if (current.analysisPaused) StatusReport.resumeAnalysis(app) else StatusReport.pauseAnalysis(app) }
                    busy = false; reload()
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            SecondaryButton(tr("Retry failures"), {
                scope.launch { busy = true; withContext(Dispatchers.IO) { StatusReport.retryFailures(app) }; busy = false; reload() }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            SecondaryButton(tr("Reindex"), {
                scope.launch { busy = true; withContext(Dispatchers.IO) { StatusReport.reindex(app, emptyList()) }; message = "Search index rebuilt from saved records."; busy = false; reload() }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            if (current.unindexed > 0) SecondaryButton("Reindex ${current.unindexed} unindexed items", {
                scope.launch {
                    busy = true
                    val ids = withContext(Dispatchers.IO) { StatusReport.unindexed(app).also { StatusReport.reindex(app, it) } }
                    message = "Reindexed ${ids.size} items from saved records."; busy = false; reload()
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            if (!current.syncPaused) SecondaryButton(tr("Pause sync"), {
                scope.launch { busy = true; withContext(Dispatchers.IO) { StatusReport.pauseSync(app) }; message = "Library sync paused; running uploads were stopped."; busy = false; reload() }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            else SecondaryButton(tr("Resume sync"), {
                scope.launch { busy = true; withContext(Dispatchers.IO) { StatusReport.resumeSync(app) }; message = "Library sync resumed."; busy = false; reload() }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            SecondaryButton(tr("Request backup"), {
                scope.launch {
                    busy = true
                    message = withContext(Dispatchers.IO) { StatusReport.requestBackup(app) }
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
            HfTextField(password, { password = it }, "Backup passphrase · at least 12 characters", password = true)
            SecondaryButton(tr("Verify backups"), { importer.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        }
    }
}
