@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.hellfire.ass.ui.drawer

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.view.DragEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.drawer.*
import com.hellfire.ass.library.CaptureOrigin
import com.hellfire.ass.library.LibraryRoutes
import com.hellfire.ass.ui.*
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HfIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DrawerHost() {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val app = env.app
    val scope = rememberCoroutineScope()
    val prefs = remember { app.getSharedPreferences("ui", 0) }
    var side by remember { mutableStateOf(if (prefs.getString("drawer_side", "right") == "left") "left" else "right") }
    var fraction by remember { mutableFloatStateOf(prefs.getFloat("drawer_width", 0.86f).coerceIn(0.55f, 0.95f)) }
    var handleY by remember { mutableFloatStateOf(prefs.getFloat("drawer_handle", 180f)) }
    var busy by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val inboxId = DrawerController.inboxId
    val item by remember(inboxId) { app.database.inbox().observe(inboxId ?: "") }.collectAsStateWithLifecycle(null)
    val actions by remember(inboxId) { app.database.inbox().actions(inboxId ?: "") }.collectAsStateWithLifecycle(emptyList())
    val messages by remember(inboxId) { app.database.inbox().conversation(inboxId ?: "") }.collectAsStateWithLifecycle(emptyList())
    var followUp by remember { mutableStateOf("") }
    val density = LocalDensity.current.density
    val screenWidth = LocalConfiguration.current.screenWidthDp
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { ingest(app, uris.map { DropPart.UriPart(it, name = it.lastPathSegment ?: "File") }, CaptureOrigin.DRAWER) { busy = it } }
    }
    fun acceptDrop(event: DragAndDropEvent): Boolean {
        val drag = event.toAndroidDragEvent()
        if (drag.action != DragEvent.ACTION_DROP) return false
        (context as? Activity)?.requestDragAndDropPermissions(drag)
        val clip = drag.clipData ?: return false
        val parts = (0 until clip.itemCount).mapNotNull { index -> clipPart(clip.getItemAt(index), context) }
        if (parts.isEmpty()) return false
        scope.launch { ingest(app, parts, CaptureOrigin.DRAWER) { busy = it } }
        return true
    }
    val dropTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean = acceptDrop(event)
        }
    }
    BackHandler(DrawerController.expanded) { DrawerController.collapse() }
    Box(Modifier.fillMaxSize()) {
        val align = if (side == "left") Alignment.CenterStart else Alignment.CenterEnd
        Box(
            Modifier.align(align).offset(y = handleY.dp).padding(vertical = 8.dp)
                .size(width = 28.dp, height = 72.dp)
                .background(Hf.colors.primary, RoundedCornerShape(if (side == "left") 0.dp else 14.dp, if (side == "left") 14.dp else 0.dp, if (side == "left") 14.dp else 0.dp, if (side == "left") 0.dp else 14.dp))
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .pointerInput(side) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        handleY = (handleY + drag.y / density).coerceIn(40f, 520f)
                        prefs.edit().putFloat("drawer_handle", handleY).apply()
                        val inward = if (side == "right") drag.x < -24 else drag.x > 24
                        if (inward) DrawerController.expanded = true
                    }
                }
                .clickableRow { DrawerController.expanded = true },
            contentAlignment = Alignment.Center,
        ) { Text(if (inboxId == null) "AI" else "•", color = Hf.colors.onPrimary) }
        DrawerController.miniPlayerId?.let { mediaId ->
            Row(
                Modifier.align(Alignment.BottomCenter).padding(12.dp).background(Hf.colors.surface, RoundedCornerShape(16.dp)).border(1.dp, Hf.colors.outline, RoundedCornerShape(16.dp)).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("Open floating player"), color = Hf.colors.onSurface, modifier = Modifier.clickableRow { nav.push(LibraryRoutes.player(mediaId)) })
                QuietButton(tr("Close"), { DrawerController.miniPlayerId = null })
            }
        }
        if (!DrawerController.expanded) return@Box
        val width = (LocalConfiguration.current.screenWidthDp * fraction).dp.coerceIn(280.dp, 520.dp)
        Column(
            Modifier.align(if (side == "left") Alignment.CenterStart else Alignment.CenterEnd)
                .width(width).fillMaxHeight()
                .background(Hf.colors.background)
                .border(1.dp, Hf.colors.outline)
                .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = dropTarget)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(tr("Drawer"), style = Hf.type.rowTitle, color = Hf.colors.onSurface, modifier = Modifier.weight(1f))
                QuietButton(if (side == "right") tr("Left") else tr("Right"), {
                    side = if (side == "right") "left" else "right"
                    prefs.edit().putString("drawer_side", side).apply()
                })
                QuietButton(tr("Minimize"), { DrawerController.collapse() })
                IconCircleButton(HfIcons.Close, tr("Close drawer"), { DrawerController.collapse() })
            }
            Box(
                Modifier.fillMaxWidth().height(8.dp).background(Hf.colors.handle, RoundedCornerShape(4.dp))
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val delta = if (side == "right") -drag.x else drag.x
                            fraction = (fraction + delta / screenWidth).coerceIn(0.55f, 0.95f)
                            prefs.edit().putFloat("drawer_width", fraction).apply()
                        }
                    },
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (busy) Note(tr("Saving the original before analysis."))
                DrawerController.status?.let { Banner(it) }
                if (item == null) Note(tr("Drop, paste or choose files. Nothing is read from the clipboard until you tap Paste."))
                item?.let { row ->
                    Text(row.title.ifBlank { tr("Saved") }, style = Hf.type.rowTitle, color = Hf.colors.onSurface)
                    Note(row.summary.ifBlank { row.captureError ?: tr("Saved") })
                    if (row.captureState != "COMPLETE") Banner(tr("Incomplete capture") + ": " + (row.captureError ?: ""), com.hellfire.ass.ui.components.Tone.Warning, HfIcons.Info)
                    Text(tr("Saved") + " · " + row.captureState, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                    Text(tr("Processing") + ": " + row.processingState, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                    Text(tr("Sync") + ": " + row.syncState, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                    Text(tr("Backup") + ": " + row.backupState, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                    if (messages.isNotEmpty()) Text(tr("Saved") + " · " + messages.size, style = Hf.type.rowSubtitle, color = Hf.colors.muted)
                    messages.takeLast(4).forEach { Text("${it.role}: ${it.text.take(240)}", style = Hf.type.body, color = Hf.colors.onSurface) }
                    if (row.contentKind == "IMAGE") Note("QR codes are not decoded: no on-device barcode decoder is included.")
                    actions.filter { it.state in setOf("PROPOSED", "FAILED") }.distinctBy { it.actionType }.take(8).forEach { record ->
                        val needs = record.actionType in confirmTypes
                        val shown = record.label + when (record.state) { "EXECUTED" -> " · done"; "FAILED" -> " · failed"; else -> "" }
                        SecondaryButton(shown, {
                            if (needs && confirm != record.actionType) confirm = record.actionType
                            else scope.launch {
                                val outcome = withContext(Dispatchers.IO) { DrawerActions(app).execute(row.id, record.actionType, confirmed = true, session = DrawerTalk.session(row.id), actionId = record.id) }
                                confirm = null
                                outcome.route?.let { nav.push(it) }
                                if (record.actionType == "floating_player") outcome.route?.let { DrawerController.miniPlayerId = LibraryRoutes.id(it) }
                                outcome.intent?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { DrawerController.status = it.message } }
                                if (record.actionType == "copy_address" || record.actionType == "copy_snippet") runCatching { copyText(context, outcome.message) }
                                DrawerController.status = outcome.message
                            }
                        }, enabled = !busy)
                    }
                    if (confirm != null) Note(tr("Needs a live source").let { "Confirmation required. Tap the action again to continue. External apps are not opened until then." })
                    SecondaryButton(tr("Universal inbox"), { nav.push(LibraryRoutes.inboxItem(row.id)) })
                    QuietButton(tr("Clear context"), {
                        scope.launch { withContext(Dispatchers.IO) { DrawerTalk(app.database.inbox()).clear(DrawerTalk.session(row.id)) }; DrawerController.status = "Context cleared for this item. The saved item is still here." }
                    })
                }
            }
            HfTextField(followUp, { followUp = it.take(2000) }, tr("Follow-up"), singleLine = false, minLines = 2, enabled = !busy && inboxId != null)
            PrimaryButton(tr("Follow-up"), {
                val id = inboxId ?: return@PrimaryButton
                val question = followUp.trim()
                if (question.isBlank()) return@PrimaryButton
                busy = true
                scope.launch {
                    val reply = withContext(Dispatchers.IO) {
                        DrawerTalk(app.database.inbox()).ask(id, DrawerTalk.session(id), question, DrawerContent.text(app, id)) { prompt -> DrawerAi.answer(app, prompt) }
                    }
                    followUp = ""
                    DrawerController.status = reply.text.take(300)
                    busy = false
                }
            }, Modifier.fillMaxWidth(), enabled = !busy && inboxId != null && followUp.isNotBlank())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(tr("Paste"), {
                    val text = pasteExplicit(context)
                    if (text.isNullOrBlank()) DrawerController.status = "Clipboard is empty."
                    else scope.launch { ingest(app, listOf(DropPart.Text(text)), CaptureOrigin.DRAWER) { busy = it } }
                }, enabled = !busy)
                SecondaryButton(tr("Choose files"), { picker.launch(arrayOf("*/*")) }, enabled = !busy)
            }
        }
    }
}

private val confirmTypes = setOf("open_map", "directions", "navigate", "nearby_restaurants", "nearby_hotels", "nearby_attractions", "share_location", "share_sanitized", "open_url", "snapshot_url")

private fun pasteExplicit(context: android.content.Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()?.take(60_000)
}

private fun clipPart(item: ClipData.Item, context: android.content.Context): DropPart? {
    item.uri?.let { return DropPart.UriPart(it, name = it.lastPathSegment ?: "Dropped file", mime = context.contentResolver.getType(it).orEmpty()) }
    val text = item.text?.toString()?.takeIf { it.isNotBlank() } ?: return null
    return DropPart.Text(text.take(60_000))
}

private suspend fun ingest(app: com.hellfire.ass.HellFireApp, parts: List<DropPart>, origin: CaptureOrigin, busy: (Boolean) -> Unit) {
    busy(true)
    try {
        val outcome = withContext(Dispatchers.IO) { DrawerPipeline(app).ingest(parts, origin) }
        DrawerController.show(outcome.inboxId)
        DrawerController.status = if (outcome.captureState == "COMPLETE") "Saved before analysis." else "Incomplete capture. ${outcome.captureError.orEmpty()}"
    } catch (error: Exception) {
        DrawerController.status = error.message ?: "Could not save."
    } finally { busy(false) }
}
