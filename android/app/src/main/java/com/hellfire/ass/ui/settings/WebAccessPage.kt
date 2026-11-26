package com.hellfire.ass.ui.settings

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkManager
import com.hellfire.ass.sync.WebSync
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Settings › Web access: mirror records to the owner's own server (web/ in the repository). */
@Composable
fun WebAccessPage() {
    val app = LocalEnv.current.app
    val scope = rememberCoroutineScope()
    val manager = remember { WorkManager.getInstance(app) }
    val periodic by manager.getWorkInfosForUniqueWorkFlow(WebSync.PERIODIC).collectAsStateWithLifecycle(emptyList())
    val immediate by manager.getWorkInfosForUniqueWorkFlow(WebSync.NOW).collectAsStateWithLifecycle(emptyList())
    var server by remember { mutableStateOf<String?>(null) }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmWipe by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var last by remember { mutableLongStateOf(0L) }
    var refresh by remember { mutableIntStateOf(0) }
    LaunchedEffect(periodic, immediate, refresh) {
        status = WebSync.readStatus(app); last = WebSync.lastSuccess(app)
        server = withContext(Dispatchers.IO) { runCatching { app.secrets.read(WebSync.CONFIG)?.getString("url") }.getOrNull() }
        if (url.isBlank()) url = server.orEmpty()
    }
    fun act(work: suspend () -> String) {
        busy = true
        scope.launch {
            message = try { work() } catch (e: CancellationException) { throw e } catch (e: Exception) { e.message ?: "That did not work." }
            busy = false; refresh++
        }
    }
    PushedPage(stringResource(R.string.ui_web_access), breadcrumb = "Settings") {
        message?.let { Banner(it, Tone.Info, null) }
        Note("Keep a read-only copy on your own server so you can look at Money, Tasks, Inbox, Recordings and Memory from a browser. " +
            "Everything is still worked out on this phone; confirming, sending and changing things stay here.")
        KvList(listOf("Status" to if (server == null) "Off" else status, "Server" to (server ?: "Not set"),
            "Last successful sync" to if (last == 0L) "Never" else Fmt.dayTime(last)))
        SectionCard("What is copied") {
            Note("Accounts, confirmed ledger entries and balances, payments waiting for review, tasks, email and WhatsApp messages, drafts, " +
                "recording transcripts and summaries, memory, and SMS or notifications that look like messages or payments.")
            Note("Never copied: security codes and anything captured alongside them, recording originals, photos and video, passwords and tokens, " +
                "on-device models and search vectors. Your server stores the copy unencrypted in its database; run it somewhere you trust.")
        }
        SectionCard(if (server == null) "Connect your server" else "Change server") {
            HfTextField(url, { url = it }, "Server address", supporting = "https:// address of your HellFire web site")
            HfTextField(token, { token = it }, "Sync token", password = true, supporting = "SYNC_TOKEN from the server’s settings")
            PrimaryButton(if (server == null) "Save and turn on" else "Save", {
                val secret = token
                act {
                    withContext(Dispatchers.IO) { WebSync.enable(app, url, secret) }
                    token = ""; "Web access is on. The first sync copies everything and can take a while."
                }
            }, Modifier.fillMaxWidth(), enabled = !busy && url.isNotBlank() && token.isNotBlank())
            Note("Syncs about every 15 minutes when the phone is online and the battery is not low. Android may delay it.")
        }
        if (server != null) SectionCard("Sync") {
            SecondaryButton("Sync now", { WebSync.now(app); message = "Sync queued." }, Modifier.fillMaxWidth(), enabled = !busy)
            SecondaryButton("Copy everything again", { act {
                withContext(Dispatchers.IO) { WebSync.mutex.withLock { app.secrets.remove(WebSync.STATE) } }
                WebSync.now(app); "The server copy will be replaced with a fresh full copy."
            } }, Modifier.fillMaxWidth(), enabled = !busy)
            QuietButton(stringResource(R.string.ui_turn_off), { act { WebSync.disable(app, wipe = false); "Web access is off. The server keeps the copy from the last sync." } },
                Modifier.fillMaxWidth(), enabled = !busy)
            QuietButton("Turn off and delete the web copy…", { confirmWipe = true }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
    if (confirmWipe) ConfirmDialog("Delete the web copy?", "This removes everything HellFire copied to your server and turns web access off. Nothing on this phone changes.",
        "Delete web copy", onConfirm = {
            confirmWipe = false
            act {
                try { WebSync.disable(app, wipe = true); "Web copy deleted and web access turned off." }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { "Could not reach the server, so nothing was deleted. Web access is still on." }
            }
        }, onDismiss = { confirmWipe = false }, destructive = true)
}
