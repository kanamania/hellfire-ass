package com.hellfire.ass.ui.player

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView
import com.hellfire.ass.library.*
import com.hellfire.ass.media.MediaItem
import com.hellfire.ass.player.*
import com.hellfire.ass.sharing.MediaReadAccess
import com.hellfire.ass.ui.LocalEnv
import com.hellfire.ass.ui.LocalNav
import com.hellfire.ass.ui.components.HfTextField
import com.hellfire.ass.ui.components.Note
import com.hellfire.ass.ui.components.SecondaryButton
import com.hellfire.ass.ui.components.SectionCard
import com.hellfire.ass.ui.theme.Hf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import com.hellfire.ass.HellFireApp
import com.hellfire.ass.media.TranscriptSegment


@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(itemId: String, startMs: Long?) {
    val env = LocalEnv.current
    val nav = LocalNav.current
    val app = env.app
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val scope = rememberCoroutineScope()
    val item by remember(itemId) { app.database.library().observeItem(itemId) }.collectAsStateWithLifecycle(null)
    val bookmarks by remember(itemId) { app.database.playback().bookmarks(itemId, true) }.collectAsStateWithLifecycle(emptyList())
    val segments by remember(itemId) { app.database.library().segments(itemId) }.collectAsStateWithLifecycle(emptyList())
    val transcript by remember(itemId) { app.database.media().segments(itemId) }.collectAsStateWithLifecycle(emptyList())
    val categories by remember { app.database.library().categories() }.collectAsStateWithLifecycle(emptyList())
    val history by remember { app.database.playback().history(30) }.collectAsStateWithLifecycle(emptyList())
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var connectError by remember { mutableStateOf<String?>(null) }
    var edits by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var started by remember(itemId, startMs) { mutableStateOf(false) }
    val playbackError by PlaybackFlags.error.collectAsState()
    val position by PlaybackFlags.positionMs.collectAsState()
    val duration by PlaybackFlags.durationMs.collectAsState()
    val playing by PlaybackFlags.playing.collectAsState()
    val speed by PlaybackFlags.speed.collectAsState()
    val video by PlaybackFlags.video.collectAsState()
    var locked by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Long?>(null) }
    var thumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var waveform by remember { mutableStateOf<WaveformResult?>(null) }
    var kinds by remember { mutableStateOf(setOf(MarkerKind.BOOKMARK, MarkerKind.AI, MarkerKind.CHAPTER, MarkerKind.SCENE)) }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<TimestampHit>>(emptyList()) }
    var searchNote by remember { mutableStateOf<String?>(null) }
    var pointA by remember { mutableStateOf<Long?>(null) }
    var editor by remember { mutableStateOf<MediaBookmark?>(null) }
    var correct by remember { mutableStateOf<TranscriptSegment?>(null) }
    var correctText by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var pipNote by remember { mutableStateOf<String?>(null) }
    var tracks by remember { mutableStateOf(Tracks.EMPTY) }
    var queue by remember { mutableStateOf<List<String>>(emptyList()) }
    var queueIndex by remember { mutableStateOf(-1) }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    val aspect by PlaybackFlags.videoAspect.collectAsState()
    val listState = rememberLazyListState()
    val skip = remember { context.getSharedPreferences("player", 0).getLong("skip_ms", 10_000L) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val media = item
        if (media != null && MediaReadAccess.granted(granted)) scope.launch { open(app, controller, media, startMs) { started = true } }
        else connectError = context.getString(R.string.ui_storage_permission_was_not_granted_the)
    }
    /** Live position for actions; the published flow can lag by one tick. */
    fun now(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: position

    LaunchedEffect(Unit) { PlaybackFlags.skipMs.value = skip }
    LaunchedEffect(itemId) {
        controller = runCatching { PlaybackHub.controller(context) }.onFailure { connectError = context.getString(R.string.ui_playback_session_could_not_start) }.getOrNull()
        edits = PlayerStore.transcriptEdits(app, itemId)
        FrameScrubber.purgeOrphans(app)
    }
    DisposableEffect(controller) {
        val player = controller
        fun read(p: Player) {
            tracks = p.currentTracks
            queue = (0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaMetadata.title?.toString().orEmpty() }
            queueIndex = p.currentMediaItemIndex
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = read(player)
        }
        player?.let { read(it); it.addListener(listener) }
        onDispose { player?.removeListener(listener) }
    }
    DisposableEffect(activity) {
        val host = activity as? ComponentActivity
        val consumer = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
            inPip = info.isInPictureInPictureMode
            if (PlaybackLogic.pipDismissed(inPip, host?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true)) controller?.pause()
        }
        host?.addOnPictureInPictureModeChangedListener(consumer)
        onDispose { host?.removeOnPictureInPictureModeChangedListener(consumer) }
    }
    LaunchedEffect(aspect, playing, video) {
        val host = activity ?: return@LaunchedEffect
        if (video && host.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) runCatching {
            host.setPictureInPictureParams(PipControls.params(host, PlaybackLogic.shouldAutoEnterPip(video, playing, PlaybackFlags.presentation.value), playing))
        }
    }
    LaunchedEffect(item?.id, controller, started) {
        val media = item ?: return@LaunchedEffect
        val player = controller ?: return@LaunchedEffect
        if (started) return@LaunchedEffect
        val kind = MediaReadAccess.kindOf(media.contentKind, media.mime)
        if (media.storage == StorageMode.EXTERNAL.name && MediaReadAccess.requiresMediaPermission(media.externalUri) && !MediaReadAccess.canRead(context, kind)) {
            permission.launch(MediaReadAccess.request(kind))
            return@LaunchedEffect
        }
        open(app, player, media, startMs) { started = true }
    }
    LaunchedEffect(item?.id) {
        val media = item ?: return@LaunchedEffect
        if (media.contentKind == ContentKind.AUDIO.name || media.mime.startsWith("audio/")) waveform = WaveformLoader.load(app, media)
    }
    DisposableEffect(itemId) {
        PlaybackFlags.present(PresentationEvent.OPEN)
        onDispose {
            activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            restoreBrightness(activity)
            if (PlaybackFlags.presentation.value == Presentation.FULL && PlaybackFlags.itemId.value != null) PlaybackFlags.present(PresentationEvent.MINIMIZE)
        }
    }
    LaunchedEffect(fullscreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controllerInsets = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controllerInsets.hide(WindowInsetsCompat.Type.systemBars())
            controllerInsets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            activity.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controllerInsets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    val lines = PlayerStore.lines(transcript, edits)
    val shownLines = PlaybackLogic.filterTranscript(lines, query)
    val active = PlaybackLogic.segmentIndexAt(lines, scrub ?: position)
    val visibleBookmarks = bookmarks.filter { !it.hidden }
    val freeSegments = segments.filter { segment -> bookmarks.none { it.segmentId == segment.id } }
    val activeRow = PlaybackLogic.transcriptListIndex(visibleBookmarks.size + freeSegments.size + 1, shownLines, lines.getOrNull(active)?.id)
    LaunchedEffect(activeRow) { activeRow?.let { listState.animateScrollToItem(it) } }
    val markers = PlaybackLogic.timeline(bookmarks, segments, kinds)
    val drawn = PlaybackLogic.drawMarkers(markers)
    val ab by PlaybackFlags.ab.collectAsState()
    fun shareClip(startMs: Long, endMs: Long) = nav.push(LibraryRoutes.shareWithAi(itemId, startMs, endMs))

    if (inPip) {
        // Picture-in-picture shows only the video; controls come from the PiP window and the session.
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { useController = false } },
            update = { it.player = controller },
            modifier = Modifier.fillMaxSize().background(Color.Black),
        )
        return
    }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton({ if (fullscreen) fullscreen = false else nav.pop() }) { Text(stringResource(R.string.ui_back)) }
                Text(item?.name ?: stringResource(R.string.ui_player), Modifier.weight(1f), style = Hf.type.cardTitle, color = Hf.colors.onSurface, maxLines = 1)
                TextButton({ locked = !locked }) { Text(if (locked) stringResource(R.string.ui_unlock) else stringResource(R.string.ui_lock_screen)) }
            }
            if (item == null && connectError == null) Note(stringResource(R.string.ui_original_is_not_available))
            connectError?.let { Note(it) }
            playbackError?.let { Note(it) }
            pipNote?.let { Note(it) }
            if (!env.permissions.notificationPosting) Note(stringResource(R.string.ui_notifications_are_off_so_lock_screen))
            Note(stringResource(OverlayEvaluation.SUMMARY))
            if (video) {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { useController = false; setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING) } },
                    update = { it.player = controller },
                    modifier = Modifier.fillMaxWidth().height(if (fullscreen) 280.dp else 200.dp).background(Color.Black),
                )
            }
            waveform?.let { result ->
                when (result) {
                    is WaveformResult.Unavailable -> Note(stringResource(result.reason))
                    is WaveformResult.Ready -> Waveform(result.peaks, position, duration) { controller?.seekTo(it) }
                }
            }
            val span = duration.coerceAtLeast(1).toFloat()
            val shown = (scrub ?: position).coerceIn(0, duration.coerceAtLeast(0)).toFloat().coerceIn(0f, span)
            Slider(shown, {
                scrub = it.toLong()
                val media = item
                if (media != null) scope.launch { thumb = FrameScrubber.frame(app, media, it.toLong()) }
            }, Modifier.fillMaxWidth(), valueRange = 0f..span, onValueChangeFinished = {
                scrub?.let { controller?.seekTo(it) }
                scrub = null
            })
            thumb?.let { androidx.compose.foundation.Image(it.asImageBitmap(), null, Modifier.height(48.dp)) }
            if (scrub != null && thumb == null && video) Note(stringResource(R.string.ui_thumbnail_unavailable))
            Text("${PlaybackLogic.formatTimestamp(scrub ?: position)} / ${PlaybackLogic.formatTimestamp(duration)}", style = Hf.type.caption, color = Hf.colors.muted)
            Timeline(drawn, markers.size, ab, position, duration) { controller?.seekTo(it) }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(if (playing) stringResource(R.string.ui_pause) else stringResource(R.string.ui_play), { controller?.let { if (it.isPlaying) it.pause() else it.play() } })
                SecondaryButton(stringResource(R.string.ui_skip_back), { controller?.seekTo(PlaybackLogic.skip(now(), -PlaybackFlags.skipMs.value, duration)) })
                SecondaryButton(stringResource(R.string.ui_skip_forward), { controller?.seekTo(PlaybackLogic.skip(now(), PlaybackFlags.skipMs.value, duration)) })
                SecondaryButton(stringResource(R.string.ui_previous), { if (controller?.hasPreviousMediaItem() == true) controller?.seekToPreviousMediaItem() })
                SecondaryButton(stringResource(R.string.ui_next), { if (controller?.hasNextMediaItem() == true) controller?.seekToNextMediaItem() })
                SecondaryButton(if (fullscreen) stringResource(R.string.ui_exit_full_screen) else stringResource(R.string.ui_full_screen), { fullscreen = !fullscreen })
                SecondaryButton(stringResource(R.string.ui_picture_in_picture), {
                    val host = activity
                    if (host == null || !host.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                        pipNote = context.getString(R.string.ui_picture_in_picture_is_not_available_on)
                    } else {
                        val entered = host.enterPictureInPictureMode(PipControls.params(host, false, playing))
                        if (entered) PlaybackFlags.present(PresentationEvent.ENTER_PIP) else {
                            PlaybackFlags.present(PresentationEvent.PIP_REFUSED)
                            pipNote = context.getString(R.string.ui_picture_in_picture_was_refused_by_this)
                        }
                    }
                })
            }
            val skipNow by PlaybackFlags.skipMs.collectAsState()
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (choice in PlaybackLogic.SKIP_CHOICES_MS) {
                    TextButton({
                        PlaybackFlags.skipMs.value = choice
                        context.getSharedPreferences("player", 0).edit().putLong("skip_ms", choice).apply()
                    }) { Text(if (skipNow == choice) "·${choice / 1000}s" else "${choice / 1000}s") }
                }
            }
            Text(stringResource(R.string.ui_speed), style = Hf.type.caption, color = Hf.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (value in PlaybackLogic.SPEEDS) {
                    TextButton({
                        controller?.setPlaybackSpeed(value)
                        PlaybackFlags.speed.value = value
                        scope.launch { persistChoice(app, itemId) { it.copy(speed = value) } }
                    }) { Text(if (speed == value) "·${value}x" else "${value}x") }
                }
            }
            VolumeBrightness(activity)
            TrackRow(controller, tracks, scope, app, itemId)
            if (queue.size > 1) {
                Text("Queue", style = Hf.type.caption, color = Hf.colors.muted)
                queue.forEachIndexed { index, name ->
                    val label = "${index + 1}. ${name.ifBlank { "Untitled" }}"
                    Text(if (index == queueIndex) "· $label" else label, Modifier.fillMaxWidth().clickable { controller?.seekTo(index, C.TIME_UNSET) },
                        color = if (index == queueIndex) Hf.colors.primary else Hf.colors.onSurface, maxLines = 1)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(stringResource(R.string.ui_repeat), {
                    val next = when (controller?.repeatMode) {
                        Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_OFF
                        else -> Player.REPEAT_MODE_ONE
                    }
                    controller?.repeatMode = next
                })
                SecondaryButton(stringResource(R.string.ui_shuffle), { controller?.shuffleModeEnabled = controller?.shuffleModeEnabled != true })
                SecondaryButton(if (item?.favorite == true) stringResource(R.string.ui_unfavorite) else stringResource(R.string.ui_favorite), {
                    scope.launch {
                        val next = item?.favorite != true
                        app.database.library().setFavorite(itemId, next)
                        app.database.sync().markChanged("media_item", itemId, false, System.currentTimeMillis())
                    }
                })
            }
            Text(stringResource(R.string.ui_sleep_timer), style = Hf.type.caption, color = Hf.colors.muted)
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton({ PlaybackFlags.sleepUntil.value = null; PlaybackFlags.sleepAtEnd.value = false }) { Text(stringResource(R.string.ui_off)) }
                for (minutes in PlaybackLogic.SLEEP_CHOICES_MIN) {
                    TextButton({
                        PlaybackFlags.sleepAtEnd.value = false
                        PlaybackFlags.sleepUntil.value = PlaybackLogic.sleepDeadline(System.currentTimeMillis(), minutes)
                    }) { Text("${minutes}m") }
                }
                TextButton({ PlaybackFlags.sleepUntil.value = null; PlaybackFlags.sleepAtEnd.value = true }) { Text(stringResource(R.string.ui_end_of_item)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(stringResource(R.string.ui_set_a), { pointA = now() })
                SecondaryButton(stringResource(R.string.ui_set_b), {
                    val start = pointA
                    val end = now()
                    if (start != null && end > start) PlaybackFlags.ab.value = AbRange(start, end)
                })
                SecondaryButton(stringResource(R.string.ui_clear_loop), { PlaybackFlags.ab.value = null })
                SecondaryButton(stringResource(R.string.ui_save_range), {
                    val range = PlaybackFlags.ab.value ?: return@SecondaryButton
                    scope.launch {
                        val now = System.currentTimeMillis()
                        PlayerStore.saveBookmark(app, MediaBookmark(UUID.randomUUID().toString(), itemId, range.startMs, range.endMs, title = "Loop", origin = Origin.USER.name, createdAt = now, updatedAt = now))
                    }
                })
            }
            ab?.let { range ->
                Note("A–B ${PlaybackLogic.formatTimestamp(range.startMs)}–${PlaybackLogic.formatTimestamp(range.endMs)}")
                SecondaryButton("Share A–B clip", { shareClip(range.startMs, range.endMs) })
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton(stringResource(R.string.ui_previous_chapter), {
                    PlaybackLogic.neighbor(markers.filter { it.kind == MarkerKind.CHAPTER || it.kind == MarkerKind.SCENE }, now(), false)?.let { controller?.seekTo(it.startMs) }
                })
                SecondaryButton(stringResource(R.string.ui_next_chapter), {
                    PlaybackLogic.neighbor(markers.filter { it.kind == MarkerKind.CHAPTER || it.kind == MarkerKind.SCENE }, now(), true)?.let { controller?.seekTo(it.startMs) }
                })
                val scene = PlaybackLogic.markerAt(markers.filter { it.kind != MarkerKind.BOOKMARK }, position)
                SecondaryButton("Share current scene", {
                    val current = PlaybackLogic.markerAt(markers.filter { it.kind != MarkerKind.BOOKMARK }, now())
                    current?.endMs?.let { shareClip(current.startMs, it) }
                }, enabled = scene != null)
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (kind in MarkerKind.entries) {
                    TextButton({ kinds = if (kind in kinds) kinds - kind else kinds + kind }) { Text(if (kind in kinds) "·${kind.name}" else kind.name) }
                }
            }
            if (markers.size > drawn.size) Note("Showing ${drawn.size} of ${markers.size} markers.")
            HfTextField(query, { query = it }, stringResource(R.string.ui_search_in_this_item))
            SecondaryButton(stringResource(R.string.ui_indexed_search), {
                scope.launch {
                    hits = LibrarySearchWithin(app.database).searchWithin(itemId, query)
                    searchNote = if (hits.isEmpty()) context.getString(R.string.ui_no_indexed_match_analysis_was_not) else null
                }
            })
            searchNote?.let { Note(it) }
            for (hit in hits) {
                Text("${PlaybackLogic.formatTimestamp(hit.startMs)} ${hit.text}", Modifier.clickable { controller?.seekTo(hit.startMs) }, color = Hf.colors.onSurface)
            }
            SecondaryButton(stringResource(R.string.ui_bookmark), {
                title = ""
                note = ""
                editor = MediaBookmark(UUID.randomUUID().toString(), itemId, scrub ?: now(), null, createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis())
            })
            LazyColumn(Modifier.weight(1f), state = listState, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(visibleBookmarks, key = { it.id }) { bookmark ->
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            BookmarkThumb(app, bookmark.thumbnailPath)
                            Text("${PlaybackLogic.formatTimestamp(bookmark.startMs)} ${bookmark.title.ifBlank { stringResource(R.string.ui_bookmark) }} · ${bookmark.origin}", Modifier.clickable { controller?.seekTo(bookmark.startMs) }, color = Hf.colors.onSurface)
                        }
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            TextButton({ controller?.seekTo(bookmark.startMs) }) { Text(stringResource(R.string.ui_seek)) }
                            TextButton({ editor = bookmark; title = bookmark.title; note = bookmark.note }) { Text(stringResource(R.string.ui_edit)) }
                            bookmark.endMs?.takeIf { it > bookmark.startMs }?.let { end -> TextButton({ shareClip(bookmark.startMs, end) }) { Text("Share clip") } }
                            TextButton({ scope.launch { PlayerStore.setHidden(app, bookmark.id, true, System.currentTimeMillis()) } }) { Text(stringResource(R.string.ui_hide)) }
                            TextButton({ scope.launch { PlayerStore.deleteBookmark(app, bookmark.id, System.currentTimeMillis()) } }) { Text(stringResource(R.string.ui_delete)) }
                            if (bookmark.origin == Origin.AI.name) TextButton({ scope.launch { PlayerStore.convertToManual(app, bookmark.id, System.currentTimeMillis()) } }) { Text(stringResource(R.string.ui_convert_to_manual)) }
                        }
                    }
                }
                items(freeSegments, key = { "seg-${it.id}" }) { segment ->
                    Text("${segment.kind} ${PlaybackLogic.formatTimestamp(segment.startMs)} ${segment.title.ifBlank { segment.description }}", Modifier.clickable { controller?.seekTo(segment.startMs) }, color = Hf.colors.onSurface)
                    Row {
                        TextButton({
                            scope.launch {
                                val now = System.currentTimeMillis()
                                PlayerStore.saveBookmark(app, MediaBookmark(UUID.randomUUID().toString(), itemId, segment.startMs, segment.endMs, segment.title.ifBlank { segment.description }, origin = Origin.USER.name, segmentId = segment.id, createdAt = now, updatedAt = now))
                            }
                        }) { Text(stringResource(R.string.ui_convert_to_manual)) }
                        TextButton({ scope.launch { PlayerStore.dismissSegment(app, itemId, segment, System.currentTimeMillis()) } }) { Text(stringResource(R.string.ui_remove)) }
                    }
                }
                item { Text(stringResource(R.string.ui_transcript), style = Hf.type.cardTitle, color = Hf.colors.onSurface) }
                items(shownLines, key = { it.id }) { line ->
                    val on = line.id == lines.getOrNull(active)?.id
                    Column(Modifier.background(if (on) Hf.colors.primaryContainer else Color.Transparent).clickable { controller?.seekTo(line.startMs) }.padding(4.dp)) {
                        Text("${line.speaker} ${PlaybackLogic.formatTimestamp(line.startMs)}", style = Hf.type.caption, color = Hf.colors.muted)
                        Text(line.text, color = Hf.colors.onSurface)
                        Row {
                            TextButton({ copy(context, line.text) }) { Text(stringResource(R.string.ui_copy)) }
                            TextButton({
                                correct = transcript.firstOrNull { it.id == line.id }
                                correctText = line.text
                            }) { Text(stringResource(R.string.ui_correct)) }
                            TextButton({
                                scope.launch {
                                    val now = System.currentTimeMillis()
                                    PlayerStore.saveBookmark(app, MediaBookmark(UUID.randomUUID().toString(), itemId, line.startMs, line.endMs, line.text.take(80), origin = Origin.USER.name, createdAt = now, updatedAt = now))
                                }
                            }) { Text(stringResource(R.string.ui_bookmark)) }
                        }
                    }
                }
                item {
                    SectionCard(stringResource(R.string.ui_playback_history)) {
                        for (entry in history.filter { it.mediaId == itemId }.take(8)) {
                            Text("${PlaybackLogic.formatTimestamp(entry.fromMs)} → ${entry.toMs?.let(PlaybackLogic::formatTimestamp) ?: "…"}", color = Hf.colors.onSurface)
                        }
                        if (history.none { it.mediaId == itemId }) Note(stringResource(R.string.ui_no_playback_history_yet))
                    }
                }
            }
        }
        if (locked) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { }, contentAlignment = Alignment.Center) {
            SecondaryButton(stringResource(R.string.ui_unlock), { locked = false })
        }
    }
    editor?.let { draft ->
        AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(stringResource(R.string.ui_bookmark)) },
            text = {
                Column {
                    HfTextField(title, { title = it }, stringResource(R.string.ui_title))
                    HfTextField(note, { note = it }, stringResource(R.string.ui_notes))
                    for (category in categories.take(12)) {
                        Text(category.name, Modifier.clickable { editor = draft.copy(categoryId = category.id) }, color = if (draft.categoryId == category.id) Hf.colors.primary else Hf.colors.onSurface)
                    }
                }
            },
            confirmButton = {
                TextButton({
                    scope.launch {
                        val now = System.currentTimeMillis()
                        val frame = item?.let { FrameScrubber.frame(app, it, draft.startMs) }
                        val path = frame?.let { FrameScrubber.writeThumb(app, it, itemId, draft.id) }
                        PlayerStore.saveBookmark(app, draft.copy(title = title, note = note, thumbnailPath = path ?: draft.thumbnailPath, updatedAt = now, origin = Origin.USER.name))
                        editor = null
                    }
                }) { Text(stringResource(R.string.ui_save_bookmark)) }
            },
            dismissButton = { TextButton({ editor = null }) { Text(stringResource(R.string.ui_close)) } },
        )
    }
    correct?.let { segment ->
        AlertDialog(
            onDismissRequest = { correct = null },
            title = { Text(stringResource(R.string.ui_correct)) },
            text = { HfTextField(correctText, { correctText = it }, stringResource(R.string.ui_transcript), singleLine = false) },
            confirmButton = {
                TextButton({
                    scope.launch {
                        PlayerStore.saveTranscriptEdit(app, itemId, segment, correctText, System.currentTimeMillis())
                        edits = PlayerStore.transcriptEdits(app, itemId)
                        correct = null
                    }
                }) { Text(stringResource(R.string.ui_save)) }
            },
            dismissButton = { TextButton({ correct = null }) { Text(stringResource(R.string.ui_close)) } },
        )
    }
}

private suspend fun open(app: HellFireApp, controller: MediaController?, item: MediaItem, startMs: Long?, started: () -> Unit) {
    val player = controller ?: return
    PlaybackHub.open(app, player, item.id, startMs)
    started()
}

internal suspend fun persistChoice(app: HellFireApp, itemId: String, edit: (PlaybackState) -> PlaybackState) {
    withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val existing = app.database.playback().playback(itemId) ?: PlaybackState(itemId, lastPlayedAt = now)
        app.database.playback().savePlayback(edit(existing.copy(lastPlayedAt = now)))
    }
}
