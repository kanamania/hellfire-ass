package com.hellfire.ass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.security.PortableBackup
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.*

/** Settings › Encrypted backup (not drawn). Same behaviour and wording as before, grouped into cards. */
@Composable
fun BackupScreen(app: HellFireApp) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val backup = remember { PortableBackup(app) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val secret = password.toCharArray(); password = ""
            scope.launch {
                busy = true
                try {
                    backup.export(uri, secret)
                    app.getSharedPreferences("ui", 0).edit().putLong("last_backup_at", System.currentTimeMillis()).apply()
                    result = "Encrypted portable backup saved. Keep its passphrase separately; there is no recovery without it. Test restore on an empty installation before relying on this backup."
                }
                catch (e: CancellationException) { result = "Backup interrupted; the selected destination may contain an incomplete encrypted file."; throw e }
                catch (_: Exception) { result = "Backup failed. Check free storage, destination, passphrase length and the 7 GiB originals / 1 GiB database limits. Partial output is incomplete." }
                finally { secret.fill('\u0000'); busy = false }
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val secret = password.toCharArray(); password = ""
            scope.launch {
                busy = true
                try { backup.restore(uri, secret); result = "Backup authenticated and restored. Reconnect accounts, regrant Android permissions and reimport model files as needed. No message was sent." }
                catch (e: CancellationException) { result = "Restore interrupted. Reopen the app to recover any pending import."; throw e }
                catch (_: Exception) { result = "Restore failed: wrong passphrase, invalid/incomplete backup, unavailable storage, or local data/access still present. Existing data is retained. Restore needs an empty database with SMS, notifications and account integrations disconnected." }
                finally { secret.fill('\u0000'); busy = false }
            }
        }
    }
    result?.let { Banner(it, Tone.Info, null) }
    SectionCard("Back up or restore") {
        HfTextField(password, { password = it }, "Backup passphrase · at least 12 characters", password = true)
        PrimaryButton("Export encrypted backup…", { exporter.launch("HellFire-Ass-backup.hfb") }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        SecondaryButton("Restore into empty local database…", { confirmRestore = true }, Modifier.fillMaxWidth(), enabled = !busy && password.length >= 12)
        if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth(), color = Hf.colors.primary); Note("Working with encrypted storage. Keep this screen open until finished.") }
    }
    SectionCard("What is included") {
        Note("Portable backups include source events, finance, tasks, messages and drafts, memory, transcripts and registered media originals. Payloads and originals are re-encrypted for your passphrase and protected with authenticated chunks. Restore re-encrypts them with this phone's key.")
        Note("Account passwords and tokens, Android permissions, the gateway private key, model weights, folder grants and microphone test samples are left out. Restore needs an empty database. It clears stale send approvals and marks interrupted sends unconfirmed.")
        Note("Choose any Android document destination you trust. Nothing is uploaded automatically. Stop recording before export; turn off notification and SMS capture and disconnect mail and gateway accounts before restore.")
    }
    if (confirmRestore) ConfirmDialog("Restore encrypted backup?", "Needs empty local data and disconnected capture and accounts. After authentication, saved tasks can be re-armed under this phone's current alarm permissions. Existing local data is never overwritten.",
        "Select backup", onConfirm = { confirmRestore = false; importer.launch(arrayOf("*/*")) }, onDismiss = { confirmRestore = false })
}
