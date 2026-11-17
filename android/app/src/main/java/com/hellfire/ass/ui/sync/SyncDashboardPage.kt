package com.hellfire.ass.ui.sync

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.library.ContentKind
import com.hellfire.ass.library.LibraryRoutes
import com.hellfire.ass.sync.LibrarySync
import com.hellfire.ass.sync.WebSync
import com.hellfire.ass.sync.applyDoc
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SyncDashboardPage() {
    val app = LocalEnv.current.app
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val counts by app.database.sync().counts().collectAsStateWithLifecycle(emptyList())
    val conflicts by app.database.sync().openConflicts().collectAsStateWithLifecycle(emptyList())
    var status by remember { mutableStateOf("") }
    var paused by remember { mutableStateOf(false) }
    var wifi by remember { mutableStateOf(false) }
    var server by remember { mutableStateOf<String?>(null) }
    var passphrase by remember { mutableStateOf("") }
    var hasPass by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var external by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(counts, conflicts) {
        status = LibrarySync.readStatus(app)
        paused = LibrarySync.paused(app)
        wifi = LibrarySync.wifiOnly(app)
        withContext(Dispatchers.IO) {
            server = runCatching { app.secrets.read(WebSync.CONFIG)?.getString("url") }.getOrNull()
            hasPass = app.secrets.read(LibrarySync.PASS)?.optString("passphrase").orEmpty().length >= 12
            external = app.database.library().page(ContentKind.entries.map { it.name }, listOf("EXTERNAL"), false, Long.MAX_VALUE, "", 12)
                .map { it.id to it.name }
        }
    }
    PushedPage(tr("Sync"), breadcrumb = tr("Settings")) {
        message?.let { Banner(it, Tone.Info, null) }
        Note("Drawer and share captures are queued when web access is on. Photos and videos found on the phone stay local until you mark them. Originals and analysis are encrypted on this phone; the server stores ciphertext and cannot search it unless you opt in on the website.")
        KvList(listOf(
            "Web access" to (server ?: "Off"),
            "Library sync" to if (paused) "Paused" else status,
            "Passphrase" to if (hasPass) "Saved on this phone" else "Not set",
            "Network" to if (wifi) "Wi-Fi only" else "Any connection",
        ) + counts.map { it.state to it.count.toString() })
        if (server == null) SecondaryButton(tr("Web access"), { nav.push(com.hellfire.ass.ui.Screen.WebAccess) }, Modifier.fillMaxWidth())
        SectionCard("Passphrase") {
            HfTextField(passphrase, { passphrase = it }, "Library passphrase", password = true, supporting = "At least 12 characters. It never leaves this phone. The website asks for the same passphrase to decrypt.")
            PrimaryButton("Save passphrase", {
                if (passphrase.length < 12) message = "Use at least 12 characters."
                else scope.launch {
                    withContext(Dispatchers.IO) { app.secrets.save(LibrarySync.PASS, org.json.JSONObject().put("passphrase", passphrase)) }
                    passphrase = ""
                    hasPass = true
                    LibrarySync.now(app)
                    message = "Passphrase saved on this phone. It is not sent to the server."
                }
            }, Modifier.fillMaxWidth(), enabled = passphrase.isNotBlank())
        }
        SectionCard("Queue") {
            SecondaryButton(if (paused) "Resume" else "Pause", {
                LibrarySync.setPaused(app, !paused)
                paused = !paused
                message = if (paused) "Library sync is paused." else "Library sync will continue."
            }, Modifier.fillMaxWidth())
            SecondaryButton(if (wifi) "Use any connection" else "Wi-Fi only", {
                LibrarySync.setWifiOnly(app, !wifi)
                wifi = !wifi
            }, Modifier.fillMaxWidth())
            SecondaryButton("Sync now", { LibrarySync.now(app); message = "Library sync queued." }, Modifier.fillMaxWidth(), enabled = server != null && !paused)
        }
        if (conflicts.isNotEmpty()) SectionCard("Conflicts") {
            Note("The same field changed on two devices and neither edit is clearly later. Nothing was overwritten until you choose.")
            conflicts.forEach { conflict ->
                ListRow(conflict.entityType, subtitle = conflict.entityId)
                SecondaryButton("Keep this phone", {
                    scope.launch {
                        withContext(Dispatchers.IO) { app.database.sync().resolve(conflict.id, "local", System.currentTimeMillis()); app.database.sync().markChanged(conflict.entityType, conflict.entityId, false, System.currentTimeMillis()) }
                        LibrarySync.now(app)
                    }
                }, Modifier.fillMaxWidth())
                SecondaryButton("Keep the other device", {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            val remote = LibrarySync.parse(conflict.remoteJson)
                            applyDoc(app, conflict.entityType, conflict.entityId, remote)
                            app.database.sync().resolve(conflict.id, "remote", System.currentTimeMillis())
                            val row = app.database.sync().record(conflict.entityType, conflict.entityId)
                            if (row != null) app.database.sync().synced(conflict.entityType, conflict.entityId, conflict.entityId, row.version, LibrarySync.sha256(conflict.remoteJson), remote.updatedAt, System.currentTimeMillis())
                        }
                    }
                }, Modifier.fillMaxWidth())
            }
        }
        SectionCard("Discovered library") {
            Note("These items were indexed from the phone and are not uploaded. Sync original copies the file into managed storage and queues it.")
            if (external.isEmpty()) Note("No discovered items are waiting.")
            external.forEach { (id, name) ->
                ListRow(name, subtitle = "Not synced", onClick = { nav.push(LibraryRoutes.mediaDetail(id)) })
                SecondaryButton("Sync original", {
                    scope.launch {
                        message = try {
                            withContext(Dispatchers.IO) { app.media.promote(id, true) }
                            LibrarySync.now(app)
                            "Queued the original for encrypted sync."
                        } catch (e: Exception) { e.message ?: "Could not copy that item." }
                    }
                }, Modifier.fillMaxWidth())
            }
        }
    }
}
