package com.hellfire.ass.ui.player

import androidx.compose.ui.res.stringResource
import com.hellfire.ass.R

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.session.MediaController
import com.hellfire.ass.library.LibraryRoutes
import com.hellfire.ass.player.PlaybackFlags
import com.hellfire.ass.player.PlaybackHub
import com.hellfire.ass.player.PlaybackLogic
import com.hellfire.ass.player.Presentation
import com.hellfire.ass.player.PresentationEvent
import com.hellfire.ass.ui.LocalEnv
import com.hellfire.ass.ui.LocalNav
import com.hellfire.ass.ui.Screen
import com.hellfire.ass.ui.components.SecondaryButton
import com.hellfire.ass.ui.theme.Hf
import kotlin.math.roundToInt

/** Persistent in-app presentation of the shared session. Hidden on the full player page. */
@Composable
fun MiniPlayerOverlay(modifier: Modifier = Modifier) {
    val nav = LocalNav.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val presentation by PlaybackFlags.presentation.collectAsState()
    val itemId by PlaybackFlags.itemId.collectAsState()
    val title by PlaybackFlags.title.collectAsState()
    val playing by PlaybackFlags.playing.collectAsState()
    val position by PlaybackFlags.positionMs.collectAsState()
    val duration by PlaybackFlags.durationMs.collectAsState()
    if (presentation == Presentation.CLOSED || itemId.isNullOrBlank() || nav.top?.screen == Screen.Player) return
    var dx by remember { mutableFloatStateOf(0f) }
    var dy by remember { mutableFloatStateOf(0f) }
    var controller by remember { mutableStateOf<MediaController?>(null) }
    LaunchedEffect(Unit) { controller = runCatching { PlaybackHub.controller(context) }.getOrNull() }
    Column(
        modifier.offset { IntOffset(dx.roundToInt(), dy.roundToInt()) }
            .padding(12.dp)
            .fillMaxWidth()
            .background(Hf.colors.surface)
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    change.consume()
                    dx = (dx + drag.x).coerceIn(-180f, 180f)
                    dy = (dy + drag.y).coerceIn(-520f, 40f)
                }
            }
            .padding(10.dp),
    ) {
        Text(title.ifBlank { stringResource(R.string.ui_player) }, style = Hf.type.cardTitle, color = Hf.colors.onSurface, maxLines = 1)
        val max = duration.coerceAtLeast(1).toFloat()
        Slider(
            value = position.coerceIn(0, duration.coerceAtLeast(0)).toFloat().coerceIn(0f, max),
            onValueChange = { controller?.seekTo(it.toLong()) },
            valueRange = 0f..max,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton(if (playing) stringResource(R.string.ui_pause) else stringResource(R.string.ui_play), { PlaybackHub.toggle(context) })
            SecondaryButton(stringResource(R.string.ui_seek), { controller?.seekTo(PlaybackLogic.skip(position, PlaybackFlags.skipMs.value, duration)) })
            SecondaryButton(stringResource(R.string.ui_open), {
                PlaybackFlags.present(PresentationEvent.MAXIMIZE)
                nav.push(LibraryRoutes.player(itemId!!))
            })
            SecondaryButton(stringResource(R.string.ui_close), { controller?.let { PlaybackHub.close(it) } })
        }
    }
}
