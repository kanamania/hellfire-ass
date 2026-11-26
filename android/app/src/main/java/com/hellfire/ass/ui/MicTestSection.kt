package com.hellfire.ass.ui

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import com.hellfire.ass.ui.components.*
import com.hellfire.ass.ui.theme.Hf
import com.hellfire.ass.ui.theme.HellFireTheme
import com.hellfire.ass.ui.theme.HfIcons
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.PermissionStatus
import com.hellfire.ass.assistant.LocalAssistant
import com.hellfire.ass.ai.*
import com.hellfire.ass.audio.*
import com.hellfire.ass.events.NotificationSnapshot
import com.hellfire.ass.notifications.NotificationCaptureStatus
import com.hellfire.ass.processing.CaptureWork
import kotlinx.coroutines.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.hellfire.ass.Diagnostics

@Composable
fun MicTestSection(app: HellFireApp, permissions: PermissionStatus, onPermission: () -> Unit, onSettings: (String) -> Unit) {
    val state by MicrophoneStatus.state.collectAsStateWithLifecycle()
    val busy by MicrophoneStatus.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var playback by remember { mutableStateOf<Job?>(null) }
    var files by remember { mutableStateOf(emptyList<java.io.File>()) }
    var result by remember { mutableStateOf<String?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
            if (event == Lifecycle.Event.ON_STOP) { playback?.cancel(); playback = null }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); playback?.cancel() }
    }
    LaunchedEffect(busy, refresh) {
        files = withContext(Dispatchers.IO) {
            MicrophoneService.recordingDirectory(app).listFiles()?.filter { it.name.endsWith(".pcm.enc") }?.sortedByDescending { it.lastModified() }.orEmpty()
        }
    }
    Text(state, style = Hf.type.body)
    Text("Records up to 60 seconds of mono PCM audio, encrypted directly on this device. Start only when everyone involved is aware. Samples remain until you delete them.")
    if (!permissions.microphone || !permissions.notificationPosting) {
        Button(onClick = onPermission) { Text("Allow recording permissions") }
    }
    Button(enabled = !busy && playback == null && permissions.microphone && permissions.notificationPosting, onClick = {
        try {
            MicrophoneService.requestStart(app, Intent(app, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_START))
        } catch (caught: Exception) { Diagnostics.warn(caught); MicrophoneStatus.state.value = "Android blocked recording. Keep the app visible and check permissions." }
    }) { Text("Start 60-second recording") }
    OutlinedButton(enabled = busy, onClick = {
        app.startService(Intent(app, MicrophoneService::class.java).setAction(MicrophoneService.ACTION_STOP))
    }) { Text("Stop recording") }
    TextButton(onClick = { onSettings(Settings.ACTION_APP_NOTIFICATION_SETTINGS) }) { Text("Recording notification settings") }
    result?.let { Text(it) }
    if (playback != null) OutlinedButton(onClick = { playback?.cancel(); playback = null }) { Text(stringResource(R.string.ui_stop_playback)) }
    Text("${files.size} encrypted samples retained", style = MaterialTheme.typography.titleMedium)
    files.forEach { file ->
        Text("Sample · ${formatTime(file.lastModified())}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(enabled = !busy && playback == null, onClick = {
                playback = scope.launch {
                    try {
                        result = "Playing encrypted sample locally…"
                        withContext(Dispatchers.IO) { playSample(app, file) }
                        result = "Playback finished."
                    } catch (e: CancellationException) { result = "Playback stopped."; throw e }
                    catch (caught: Exception) { Diagnostics.warn(caught); result = "Sample could not be played. It may be incomplete or its device key is unavailable." }
                    finally { playback = null }
                }
            }) { Text("Play sample") }
            TextButton(enabled = !busy && playback == null, onClick = {
                scope.launch {
                    result = if (withContext(Dispatchers.IO) { file.delete() }) "Sample deleted." else "Sample could not be deleted."
                    refresh++
                }
            }) { Text("Delete sample") }
        }
    }
}

internal fun formatTime(millis: Long): String = DateTimeFormatter.ofPattern("d MMM, HH:mm:ss")
    .withZone(ZoneId.of("Africa/Dar_es_Salaam")).format(Instant.ofEpochMilli(millis))
