package com.hellfire.ass.ui

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.security.PortableBackup
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.*

/** Keeps a selected archive passphrase in memory while Android's document picker is open. */
class BackupPassphraseState : ViewModel() {
    private var pending: CharArray? = null
    fun set(value: String) { clear(); pending = value.toCharArray() }
    fun take(): CharArray = pending.also { pending = null } ?: charArrayOf()
    fun clear() { pending?.fill('\u0000'); pending = null }
    override fun onCleared() { clear() }
}
@Composable
fun BackupScreen(app: HellFireApp) {
    val scope = rememberCoroutineScope()
    val owner = requireNotNull(LocalActivity.current) as ViewModelStoreOwner
    val pending = remember(owner) { ViewModelProvider(owner)[BackupPassphraseState::class.java] }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var incremental by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val backup = remember { PortableBackup(app) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val secret = pending.take(); password = ""
            scope.launch {
                busy = true
                try {
                    val outcome = backup.export(uri, secret, incremental)
                    app.getSharedPreferences("ui", 0).edit().putLong("last_backup_at", System.currentTimeMillis()).apply()
                    result = (if (outcome.blocked.isEmpty()) "" else "Partial backup: ${outcome.blocked.size} originals were unavailable and are not backed up. Other records and originals can be restored. ") + "Encrypted portable backup saved. Keep its passphrase separately; there is no recovery without it. Test restore on an empty installation before relying on this backup."
                }
                catch (e: CancellationException) { result = "Backup interrupted; the selected destination may contain an incomplete encrypted file."; throw e }
                catch (blocked: com.hellfire.ass.security.BackupBlocked) { result = blocked.message ?: "Backup blocked." }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); result = "Backup failed. Check free storage, destination, passphrase length and encrypted staging space and the 1 GiB database limit. Partial output is incomplete." }
                finally { secret.fill('\u0000'); busy = false }
            }
        } else pending.clear()
    }
    var inspected by remember { mutableStateOf<com.hellfire.ass.security.RestoreReport?>(null) }
    var inspectedUris by remember { mutableStateOf<List<android.net.Uri>>(emptyList()) }
    var pickKinds by remember { mutableStateOf(setOf<String>()) }
    var pickCollections by remember { mutableStateOf(setOf<String>()) }
    var pickItems by remember { mutableStateOf(setOf<String>()) }
    fun missingNote(report: com.hellfire.ass.security.RestoreReport) = if (report.missing.isEmpty()) "" else
        " ${report.missing.size} item(s) had no original in the archive and were restored as records only (marked missing): ${report.missing.take(5).joinToString(", ")}${if (report.missing.size > 5) "…" else ""}."
    val verifier = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val secret = pending.take(); password = ""
            scope.launch {
                busy = true
                try { backup.verify(uri, secret); result = "Backup authenticated: all tables and the originals stored in this archive were hash-checked. Originals an incremental archive omitted live in its older archives. Current data was unchanged. A replacement-phone restore still needs its own device test." }
                catch (e: CancellationException) { throw e }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); result = "Verification failed: wrong passphrase or an invalid or incomplete archive. Nothing was changed." }
                finally { secret.fill('\u0000'); busy = false }
            }
        } else pending.clear()
    }
    val inspector = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val secret = pending.take(); password = ""
            scope.launch {
                busy = true
                try {
                    inspected = backup.inspect(uris, secret); inspectedUris = uris
                    pickKinds = emptySet(); pickCollections = emptySet(); pickItems = emptySet()
                    result = "Archive verified. Choose what to restore below, then enter the passphrase again."
                }
                catch (e: CancellationException) { throw e }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); inspected = null; result = "Could not open the archive: wrong passphrase or an invalid or incomplete backup." }
                finally { secret.fill('\u0000'); busy = false }
            }
        } else pending.clear()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val secret = pending.take(); password = ""
            scope.launch {
                busy = true
                try { val report = backup.restore(uris, secret); result = "Backup authenticated and restored (${report.restoredOriginals} originals).${missingNote(report)} Reconnect accounts, regrant Android permissions and reimport model files as needed. No message was sent." }
                catch (e: CancellationException) { result = "Restore interrupted. Reopen the app to recover any pending import."; throw e }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); result = "Restore failed: wrong passphrase, invalid/incomplete backup, missing older archives for an incremental backup, unavailable storage, or local data/access still present. Existing data is retained. Restore needs an empty database with SMS, notifications and account integrations disconnected." + (caught.message?.takeIf { it.contains("older archives") }?.let { " $it." } ?: "") }
                finally { secret.fill('\u0000'); busy = false }
            }
        } else pending.clear()
    }
    var readiness by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(busy) {
        if (!busy) readiness = withContext(Dispatchers.IO) {
            val originals = app.database.media().all().fold(0L) { n, m -> Math.addExact(n, m.size) }
            val bytes = app.getDatabasePath("hellfire.db").length()
            val required = runCatching { com.hellfire.ass.security.BackupReadiness.stagingBytes(originals, bytes) }.getOrNull()
            val verified = app.getSharedPreferences("ui", 0).getLong("last_backup_verified_at", 0)
            listOf("Originals" to "${originals / (1024 * 1024)} MiB", "Free space" to "${app.noBackupFilesDir.usableSpace / (1024 * 1024)} MiB",
                "Estimated staging space" to (required?.let { "${it / (1024 * 1024)} MiB" } ?: "Exceeds archive limits"),
                "Backup verification" to if (verified == 0L) "No archive verified on this installation" else Fmt.full(verified))
        }
    }
    SectionCard(stringResource(R.string.ui_recovery_readiness)) { KvList(readiness); Note("A backup includes registered originals. Archives are streamed with bounded memory, up to 500 GiB of originals; free storage and destination limits still apply.") }
    SectionCard(stringResource(R.string.ui_daily_google_drive_backup_and_restore)) { DriveBackupScreen(app) }
    result?.let { Banner(it, Tone.Info, null) }
    SectionCard(stringResource(R.string.ui_back_up_or_restore)) {
        HfTextField(password, { password = it }, stringResource(R.string.ui_backup_passphrase_at_least_12_characters), password = true)
        PrimaryButton(stringResource(R.string.ui_export_encrypted_backup), { pending.set(password); password = ""; exporter.launch("HellFire-Ass-backup.hfb") }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        QuietButton(if (incremental) "Incremental export on" else "Incremental export off", { incremental = !incremental }, enabled = !busy)
        if (incremental) Note("Incremental archives omit originals already verified in a retained backup. Keep those older archives; to restore, select the incremental archive together with them. A full export is one file and restores alone.")
        SecondaryButton(stringResource(R.string.ui_restore_into_empty_local_database), { confirmRestore = true }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        SecondaryButton(stringResource(R.string.ui_verify_an_archive_without_restoring), { pending.set(password); password = ""; verifier.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        SecondaryButton("Restore selected items…", { pending.set(password); password = ""; inspector.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Hf.colors.primary); Note("Working with encrypted storage. Keep this screen open until finished.") }
    }
    inspected?.let { report ->
        SectionCard("Restore selected items") {
            Note("Selected items are added to this phone with their bookmarks, categories, transcripts, scenes and drawer records. Existing items are kept. Choose by kind, collection or item.")
            if (report.kinds.isNotEmpty()) Text("Kinds", style = Hf.type.label, color = Hf.colors.muted)
            report.kinds.forEach { (kind, count) -> SelectRow("$kind · $count", kind in pickKinds) { pickKinds = pickKinds.toggle(kind) } }
            if (report.collections.isNotEmpty()) Text("Collections", style = Hf.type.label, color = Hf.colors.muted)
            report.collections.forEach { (id, name) -> SelectRow(name, id in pickCollections) { pickCollections = pickCollections.toggle(id) } }
            if (report.items.isNotEmpty()) Text("Items (newest ${report.items.size})", style = Hf.type.label, color = Hf.colors.muted)
            report.items.forEach { (id, name) -> SelectRow(name, id in pickItems) { pickItems = pickItems.toggle(id) } }
            if (report.missing.isNotEmpty()) Note("${report.missing.size} item(s) have no original in this archive and cannot be restored selectively.")
            val chosen = pickKinds.isNotEmpty() || pickCollections.isNotEmpty() || pickItems.isNotEmpty()
            PrimaryButton("Restore selected", {
                val secret = password.toCharArray(); password = ""
                val selection = com.hellfire.ass.security.BackupSelection(pickItems, pickKinds, pickCollections)
                scope.launch {
                    busy = true
                    try { val done = backup.restore(inspectedUris, secret, selection); inspected = null; result = "Restored ${done.restoredOriginals} originals with their records. Search was rebuilt." }
                    catch (e: CancellationException) { throw e }
                    catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); result = "Selective restore failed. Nothing was changed. Check the passphrase, free space and that older archives were selected for an incremental backup." }
                    finally { secret.fill('\u0000'); busy = false }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy && chosen && password.length >= 12)
            if (chosen && password.length < 12) Note("Enter the backup passphrase above to restore.")
            QuietButton(stringResource(R.string.ui_cancel), { inspected = null })
        }
    }
    SectionCard(stringResource(R.string.ui_what_is_included)) {
        Note("Portable backups include source events, finance, tasks, messages and drafts, memory, transcripts, library records and managed originals. The archive is encrypted with your passphrase. The passphrase and derived key are not stored in the archive or beside it. Restore re-encrypts originals with this phone's key. An incremental archive restores only together with the older archives it omitted.")
        Note("Account passwords and tokens, Android permissions, the gateway private key, model weights, folder grants and microphone test samples are left out. Restore needs an empty database. It clears stale send approvals and marks interrupted sends unconfirmed.")
        Note("Choose any Android document destination you trust. Manual export is separate from the daily Google Drive option above. Stop recording before export; turn off notification and SMS capture and disconnect mail and gateway accounts before restore.")
    }
    if (confirmRestore) ConfirmDialog("Restore encrypted backup?", "Needs empty local data and disconnected capture and accounts. For an incremental backup, select it together with the older archives it builds on. After authentication, saved tasks can be re-armed under this phone's current alarm permissions. Existing local data is never overwritten.",
        "Select backup", onConfirm = { confirmRestore = false; pending.set(password); password = ""; importer.launch(arrayOf("*/*")) }, onDismiss = { confirmRestore = false })
}

private fun Set<String>.toggle(value: String) = if (value in this) this - value else this + value

@Composable
private fun SelectRow(label: String, selected: Boolean, onClick: () -> Unit) {
    QuietButton((if (selected) "✓ " else "○ ") + label, onClick)
}
