package com.hellfire.ass.ui

import android.accounts.AccountManager
import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.Identity
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.util.UUID

@Composable
fun DriveBackupScreen(app: HellFireApp) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val manager = remember { WorkManager.getInstance(app) }
    val daily by manager.getWorkInfosForUniqueWorkFlow(DriveBackupWork.DAILY).collectAsStateWithLifecycle(emptyList())
    val immediate by manager.getWorkInfosForUniqueWorkFlow(DriveBackupWork.NOW).collectAsStateWithLifecycle(emptyList())
    var account by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var enabled by remember { mutableStateOf(false) }
    var dailyAccount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(DriveBackupWork.DEFAULT_KEEP) }
    var message by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var snapshots by remember { mutableStateOf(emptyList<DriveSnapshot>()) }
    var restore by remember { mutableStateOf<DriveSnapshot?>(null) }
    LaunchedEffect(daily, immediate) {
        status = DriveBackupWork.readStatus(app)
        try {
            val config = withContext(Dispatchers.IO) { app.secrets.read(DriveBackupWork.CONFIG) }
            enabled = config?.optBoolean("enabled") == true
            dailyAccount = config?.optString("account").orEmpty()
            if (config != null) keep = config.optInt("keep", DriveBackupWork.DEFAULT_KEEP)
            if (account.isBlank()) account = config?.optString("account").orEmpty()
        } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); message = "Saved Drive settings unavailable. Reconnect to configure backups." }
    }
    fun authorized(result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        try {
            token = DriveAuthorization.token(result)
            message = "Google Drive authorized. Enter a passphrase to enable daily uploads, or find backups to restore."
        } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); token = null; message = "Google Drive access was not granted. Connect again when ready." }
        busy = false
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        try {
            if (result.resultCode == Activity.RESULT_OK) authorized(Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(result.data))
            else { busy = false; token = null; message = "Google Drive connection cancelled." }
        } catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); busy = false; token = null; message = "Google authorization failed. Check Google Play services and OAuth setup." }
    }
    val chooser = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
        if (result.resultCode == Activity.RESULT_OK && !email.isNullOrBlank()) {
            account = email; token = null; snapshots = emptyList(); busy = true
            scope.launch {
                try {
                    val authorization = withContext(Dispatchers.IO) { DriveAuthorization.authorize(app, email) }
                    if (authorization.hasResolution()) consent.launch(IntentSenderRequest.Builder(requireNotNull(authorization.pendingIntent)).build())
                    else authorized(authorization)
                } catch (e: CancellationException) { busy = false; throw e }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); busy = false; message = "Google authorization failed. Check Play services, network and the app’s registered signing certificate." }
            }
        } else message = "Google account selection cancelled."
    }
    Text("Daily Google Drive backup", style = MaterialTheme.typography.titleLarge)
    Text(if (enabled) "Daily backups enabled for $dailyAccount" else "Daily backups disabled")
    Text(status, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    Text("After you enable backups, HellFire uploads an encrypted archive about every 24 hours over unmetered Wi-Fi, with sufficient battery and storage. Android/ColorOS may delay jobs. Backup now uses the same conditions. Registered originals are included and every archive is complete, so each one counts against your Drive storage.")
    Text("Keep the newest backups on Drive")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (option in listOf(3, 7, 14, 0)) FilterChip(selected = keep == option, enabled = !busy, onClick = {
            keep = option
            scope.launch {
                message = try {
                    withContext(Dispatchers.IO) { DriveBackupWork.setKeep(app, option) }
                    if (option == 0) "All backups are kept. Delete old ones in Drive when storage runs low."
                    else "After each confirmed upload, HellFire deletes its own older backups beyond the newest $option."
                } catch (e: CancellationException) { throw e }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); "Could not save the retention setting." }
            }
        }, label = { Text(if (option == 0) "All" else "$option") })
    }
    Text("The backup passphrase is saved encrypted on this device for unattended backup. Keep a separate copy: a replacement phone needs it to restore. Google never receives it. Account credentials, app lock settings and model weights are excluded from the archive.")
    Text("Google account: ${account.ifBlank { "not connected" }}")
    OutlinedButton(enabled = !busy, onClick = {
        try { chooser.launch(AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)) }
        catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); message = "Google account chooser unavailable. This device needs Google Play services and a Google account." }
    }) { Text("Connect / reconnect Google Drive…") }
    OutlinedTextField(passphrase, { passphrase = it }, modifier = Modifier.fillMaxWidth(),
        label = { Text("Drive backup / restore passphrase · 12–1024 characters") },
        singleLine = true, visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
    Button(enabled = !busy && token != null && account.isNotBlank() && passphrase.length in 12..1024, onClick = {
        val secret = passphrase; passphrase = ""; busy = true
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    app.secrets.save(DriveBackupWork.CONFIG, JSONObject().put("account", account).put("passphrase", secret)
                        .put("enabled", true).put("generation", UUID.randomUUID().toString()).put("keep", keep))
                    DriveBackupWork.schedule(app); DriveBackupWork.now(app)
                }
                enabled = true; dailyAccount = account; message = "Daily backups enabled. First backup queued under Wi-Fi, battery and storage conditions."
            } catch (e: CancellationException) { throw e }
            catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); message = "Could not enable daily backups. Check encrypted storage and try again." }
            finally { busy = false }
        }
    }) { Text("Enable daily encrypted backups") }
    if (enabled) {
        OutlinedButton(enabled = !busy, onClick = { DriveBackupWork.now(app); message = "Backup queued under Wi-Fi, battery and storage conditions." }) { Text("Back up now") }
        TextButton(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        app.secrets.remove(DriveBackupWork.CONFIG); DriveBackupWork.stop(app)
                        DriveBackupWork.status(app, "Daily backups disabled; existing Drive backups retained")
                    }
                    enabled = false; status = DriveBackupWork.readStatus(app)
                    message = "Daily uploads stopped and saved passphrase removed. Existing Drive files retained."
                } catch (e: CancellationException) { throw e }
                catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); message = "Could not disable backup. Try again before leaving this screen." }
                finally { busy = false }
            }
        }) { Text("Disable daily backups") }
    }
    OutlinedButton(enabled = !busy && token != null, onClick = {
        busy = true; snapshots = emptyList()
        scope.launch {
            try {
                snapshots = withContext(Dispatchers.IO) { GoogleDriveBackup().list(requireNotNull(token)) }
                message = if (snapshots.isEmpty()) "No HellFire backups found for this Google account and app registration." else "Showing up to 100 newest backups. Select one to restore."
            } catch (e: CancellationException) { throw e }
            catch (_: DriveReconnectRequired) { token = null; message = "Reconnect Google Drive to list backups." }
            catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); message = "Could not list Drive backups. Check connection, Google setup or reconnect." }
            finally { busy = false; status = DriveBackupWork.readStatus(app) }
        }
    }) { Text("Find Google Drive backups to restore") }
    Text("Restore needs an empty installation. Disable SMS/notification capture and disconnect mail/gateway accounts first. Connect the same Google account and enter the backup’s original passphrase. Reconnect permissions and accounts after restore.")
    snapshots.forEach { snapshot ->
        Text("${snapshot.name}\n${snapshot.createdAt} · ${snapshot.size / (1024 * 1024)} MiB")
        OutlinedButton(enabled = !busy && passphrase.length in 12..1024, onClick = { restore = snapshot }) { Text("Restore ${snapshot.createdAt}…") }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    Text(message, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    restore?.let { selected ->
        AlertDialog(onDismissRequest = { restore = null }, title = { Text("Restore Google Drive backup?") },
            text = { Text("Restore ${selected.name} using the entered passphrase? Existing local data is never overwritten. Keep this screen open until finished.") },
            confirmButton = { TextButton(onClick = {
                restore = null; busy = true
                val password = passphrase.toCharArray(); passphrase = ""
                scope.launch {
                    val file = File(app.noBackupFilesDir, "drive-restore-${UUID.randomUUID()}.hfb")
                    try {
                        withContext(Dispatchers.IO) {
                            DriveBackupWork.mutex.withLock {
                                PortableBackup(app).checkRestoreAllowed()
                                val coroutine = currentCoroutineContext()
                                GoogleDriveBackup().download(selected, file, requireNotNull(token)) { coroutine.ensureActive() }
                                PortableBackup(app).restore(file, password)
                            }
                        }
                        message = "Backup authenticated and restored. Regrant permissions and reconnect accounts as needed."
                    } catch (e: CancellationException) { throw e }
                    catch (_: DriveReconnectRequired) { token = null; message = "Reconnect Google Drive and try restore again." }
                    catch (caught: Exception) { com.hellfire.ass.Diagnostics.warn(caught); message = "Restore failed: check passphrase, empty local database, disconnected capture/accounts and free space. Existing local data retained." }
                    finally { password.fill('\u0000'); file.delete(); busy = false }
                }
            }) { Text("Download and restore") } },
            dismissButton = { TextButton(onClick = { restore = null }) { Text(com.hellfire.ass.ui.tr("Cancel")) } })
    }
}
