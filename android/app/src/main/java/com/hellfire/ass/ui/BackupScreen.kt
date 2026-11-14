package com.hellfire.ass.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.text.input.*
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.security.PortableBackup
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
    val activity = LocalContext.current as ComponentActivity
    val pending = remember(activity) { ViewModelProvider(activity)[BackupPassphraseState::class.java] }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    val backup = remember { PortableBackup(app) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) {
            val secret = pending.take(); password = ""
            scope.launch {
                busy = true
                try { backup.export(uri, secret); result = "Encrypted portable backup saved. Keep its passphrase separately; there is no recovery without it. Test restore on an empty installation before relying on this backup." }
                catch (e: CancellationException) { result = "Backup interrupted; the selected destination may contain an incomplete encrypted file."; throw e }
                catch (_: Exception) { result = "Backup failed. Check free storage, destination, passphrase length and the 7 GiB originals / 1 GiB database limits. Partial output is incomplete." }
                finally { secret.fill('\u0000'); busy = false }
            }
        } else pending.clear()
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val secret = pending.take(); password = ""
            scope.launch {
                busy = true
                try { backup.restore(uri, secret); result = "Backup authenticated and restored. Reconnect accounts, regrant Android permissions and reimport model files as needed. No message was sent." }
                catch (e: CancellationException) { result = "Restore interrupted. Reopen the app to recover any pending import."; throw e }
                catch (_: Exception) { result = "Restore failed: wrong passphrase, invalid/incomplete backup, unavailable storage, or local data/access still present. Existing data is retained. Restore needs an empty database with SMS, notifications and account integrations disconnected." }
                finally { secret.fill('\u0000'); busy = false }
            }
        } else pending.clear()
    }
    DriveBackupScreen(app)
    HorizontalDivider()
    Text("Manual encrypted backup", style = MaterialTheme.typography.titleLarge)
    Text("Portable backups include source events, finance, tasks, messages/drafts, memory, transcripts and registered media originals. Payloads and originals are re-encrypted for the owner passphrase, then protected with authenticated chunks. Restore re-encrypts them with this device’s key.")
    Text("Account passwords/tokens, Android permissions, gateway private key, model weights, folder grants and Phase 0 microphone test samples are excluded. Restore accepts the same backup schema and an empty database. It clears stale send approvals and marks interrupted sends unconfirmed.")
    Text("Choose any Android document destination you trust, including a self-hosted document provider if you have configured one. This manual export is separate from the daily Google Drive option above. Stop recording before export; disable notification/SMS capture and disconnect mail/gateway accounts before restore.")
    OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Backup passphrase · at least 12 characters") },
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false), singleLine = true)
    Button(enabled = !busy && password.length >= 12, onClick = { pending.set(password); password = ""; exporter.launch("HellFire-Ass-backup.hfb") }) { Text("Export encrypted backup…") }
    OutlinedButton(enabled = !busy && password.length >= 12, onClick = { confirmRestore = true }) { Text("Restore into empty local database…") }
    if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Working with encrypted storage. Keep this screen open until finished.") }
    Text(result)
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false }, title = { Text("Restore encrypted backup?") },
        text = { Text("Requires empty local data and disconnected capture/accounts. After authentication, saved tasks can be re-armed under this device’s current alarm permissions. Existing local data is never overwritten.") },
        confirmButton = { TextButton(onClick = { confirmRestore = false; pending.set(password); password = ""; importer.launch(arrayOf("*/*")) }) { Text("Select backup") } },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text("Cancel") } })
}
