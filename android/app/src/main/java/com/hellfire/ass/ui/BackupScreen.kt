package com.hellfire.ass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
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
    var verifyOnly by rememberSaveable { mutableStateOf(false) }
    var incremental by rememberSaveable { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    val backup = remember { PortableBackup(app) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val secret = pending.take(); password = ""
            scope.launch {
                busy = true
                try {
                    backup.export(uri, secret, incremental)
                    app.getSharedPreferences("ui", 0).edit().putLong("last_backup_at", System.currentTimeMillis()).apply()
                    result = "Encrypted portable backup saved. Keep its passphrase separately; there is no recovery without it. Test restore on an empty installation before relying on this backup."
                }
                catch (e: CancellationException) { result = "Backup interrupted; the selected destination may contain an incomplete encrypted file."; throw e }
                catch (blocked: com.hellfire.ass.security.BackupBlocked) { result = blocked.message ?: "Backup blocked." }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); result = "Backup failed. Check free storage, destination, passphrase length and encrypted staging space and the 1 GiB database limit. Partial output is incomplete." }
                finally { secret.fill('\u0000'); busy = false }
            }
        } else pending.clear()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val secret = pending.take(); password = ""
            scope.launch {
                busy = true
                try { if (verifyOnly) { backup.verify(uri, secret); result = "Backup authenticated: all tables and original hashes verified. Current data was unchanged. A replacement-phone restore still needs its own device test." }
                    else { backup.restore(uri, secret); result = "Backup authenticated and restored. Reconnect accounts, regrant Android permissions and reimport model files as needed. No message was sent." } }
                catch (e: CancellationException) { result = "Restore interrupted. Reopen the app to recover any pending import."; throw e }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); result = "Restore failed: wrong passphrase, invalid/incomplete backup, unavailable storage, or local data/access still present. Existing data is retained. Restore needs an empty database with SMS, notifications and account integrations disconnected." }
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
    SectionCard(com.hellfire.ass.ui.tr("Recovery readiness")) { KvList(readiness); Note("A backup includes registered originals. Archives are streamed with bounded memory, up to 500 GiB of originals; free storage and destination limits still apply.") }
    SectionCard(com.hellfire.ass.ui.tr("Daily Google Drive backup and restore")) { DriveBackupScreen(app) }
    result?.let { Banner(it, Tone.Info, null) }
    SectionCard(com.hellfire.ass.ui.tr("Back up or restore")) {
        HfTextField(password, { password = it }, "Backup passphrase · at least 12 characters", password = true)
        PrimaryButton(com.hellfire.ass.ui.tr("Export encrypted backup…"), { pending.set(password); password = ""; exporter.launch("HellFire-Ass-backup.hfb") }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        QuietButton(if (incremental) "Incremental export on" else "Incremental export off", { incremental = !incremental }, enabled = !busy)
        if (incremental) Note("Incremental archives omit originals already verified in a retained backup. Keep those older archives. A full export is one file and restores alone.")
        SecondaryButton(com.hellfire.ass.ui.tr("Restore into empty local database…"), { verifyOnly = false; confirmRestore = true }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        SecondaryButton(com.hellfire.ass.ui.tr("Verify an archive without restoring…"), { verifyOnly = true; pending.set(password); password = ""; importer.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Hf.colors.primary); Note("Working with encrypted storage. Keep this screen open until finished.") }
    }
    SectionCard(com.hellfire.ass.ui.tr("What is included")) {
        Note("Portable backups include source events, finance, tasks, messages and drafts, memory, transcripts, library records and managed originals. The archive is encrypted with your passphrase. The passphrase and derived key are not stored in the archive or beside it. Restore re-encrypts originals with this phone's key. An incremental archive restores only together with the older archives it omitted.")
        Note("Account passwords and tokens, Android permissions, the gateway private key, model weights, folder grants and microphone test samples are left out. Restore needs an empty database. It clears stale send approvals and marks interrupted sends unconfirmed.")
        Note("Choose any Android document destination you trust. Manual export is separate from the daily Google Drive option above. Stop recording before export; turn off notification and SMS capture and disconnect mail and gateway accounts before restore.")
    }
    if (confirmRestore) ConfirmDialog("Restore encrypted backup?", "Needs empty local data and disconnected capture and accounts. After authentication, saved tasks can be re-armed under this phone's current alarm permissions. Existing local data is never overwritten.",
        "Select backup", onConfirm = { confirmRestore = false; pending.set(password); password = ""; importer.launch(arrayOf("*/*")) }, onDismiss = { confirmRestore = false })
}
