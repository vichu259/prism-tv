package tv.prism.app.ui

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import tv.prism.app.player.PlayState

@Composable
fun PlayerScreen(vm: AppViewModel, state: UiState) {
    val playState by vm.player.state.collectAsState()
    val playerError by vm.player.error.collectAsState()
    val resolveError by vm.playError.collectAsState()
    val focus = remember { FocusRequester() }
    // Bumped on every interaction; the info bar shows for a few seconds after the last one.
    var poke by remember { mutableIntStateOf(1) }
    var infoVisible by remember { mutableStateOf(true) }
    var panelOpen by remember { mutableStateOf(false) }
    val subtitleTracks by vm.player.textTracks.collectAsState()

    BackHandler { vm.closePlayer() }
    // Back to the video (and its keys) whenever the subtitle panel closes.
    LaunchedEffect(panelOpen) { if (!panelOpen) runCatching { focus.requestFocus() } }
    LaunchedEffect(poke, state.playing?.id) {
        infoVisible = true
        delay(4_000)
        infoVisible = false
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                // While the subtitle panel is open its list handles the D-pad; don't zap channels underneath.
                if (panelOpen || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val code = e.nativeKeyEvent.keyCode
                when (code) {
                    AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_CAPTIONS, AndroidKey.KEYCODE_MENU -> panelOpen = true
                    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_CHANNEL_UP, AndroidKey.KEYCODE_PAGE_UP -> vm.zap(+1)
                    AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_CHANNEL_DOWN, AndroidKey.KEYCODE_PAGE_DOWN -> vm.zap(-1)
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER, AndroidKey.KEYCODE_INFO,
                    AndroidKey.KEYCODE_DPAD_RIGHT -> {
                        // The first auto-repeat arrives ~0.5 s into a hold: treat it as a long press, once.
                        if (code == AndroidKey.KEYCODE_DPAD_CENTER && e.nativeKeyEvent.repeatCount == 1) {
                            state.playing?.let(vm::toggleFavorite)
                        }
                    }
                    in AndroidKey.KEYCODE_0..AndroidKey.KEYCODE_9 -> vm.typeDigit(code - AndroidKey.KEYCODE_0)
                    in AndroidKey.KEYCODE_NUMPAD_0..AndroidKey.KEYCODE_NUMPAD_9 -> vm.typeDigit(code - AndroidKey.KEYCODE_NUMPAD_0)
                    else -> return@onKeyEvent false
                }
                poke++
                true
            },
    ) {
        VideoSurface(vm.player.player, Modifier.fillMaxSize())

        val err = resolveError ?: playerError
        if (err != null) {
            Text(err, color = Palette.Error, fontSize = 22.sp, modifier = Modifier.align(Alignment.Center).padding(48.dp))
        } else if (playState == PlayState.Loading) {
            Text("Tuning…", color = Palette.Text.copy(alpha = 0.8f), fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
        }

        if (state.numberEntry.isNotEmpty()) {
            Text(
                state.numberEntry, fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Palette.Text,
                modifier = Modifier.align(Alignment.TopEnd).padding(40.dp)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }

        AnimatedVisibility(
            visible = infoVisible || err != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            InfoBar(state, subtitleTracks)
        }

        if (panelOpen) TrackPanel(vm.player) { panelOpen = false }
    }
}

@Composable
private fun InfoBar(state: UiState, subtitleTracks: List<tv.prism.app.player.TrackOption>) {
    val ch = state.playing ?: return
    val now = System.currentTimeMillis()
    Box(
        Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
            .padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 36.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Logo(ch.logo, ch.name, 72.dp)
            Spacer(Modifier.width(20.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${ch.number}", color = Palette.Muted, fontSize = 22.sp)
                    Spacer(Modifier.width(12.dp))
                    Text(ch.name, color = Palette.Text, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                    if (ch.id in state.favorites) {
                        Spacer(Modifier.width(10.dp))
                        Icon(Icons.Default.Star, "Favorite", tint = Palette.Fav, modifier = Modifier.size(22.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    CcBadge(subtitleTracks)
                    Spacer(Modifier.weight(1f))
                    Text(clock(now), color = Palette.Muted, fontSize = 20.sp)
                }
                Spacer(Modifier.height(10.dp))
                NowNext(state.epg[ch.id].orEmpty(), now)
                Text("▲▼ Change channel    0–9 Jump to number    ◀ Subtitles & audio    Hold OK Favorite    Back Guide", color = Palette.Muted, fontSize = 13.sp)
            }
        }
    }
}

/** Attaches a PlayerView to the shared player; detaching only clears this view's own surface. */
@OptIn(UnstableApi::class)
@Composable
fun VideoSurface(player: Player, modifier: Modifier = Modifier, showSubtitles: Boolean = true) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                setKeepContentOnPlayerReset(true)
                isFocusable = false
                // TV-readable subtitles: white on a translucent black box, a little larger than default.
                subtitleView?.apply {
                    visibility = if (showSubtitles) android.view.View.VISIBLE else android.view.View.GONE
                    setStyle(
                        androidx.media3.ui.CaptionStyleCompat(
                            android.graphics.Color.WHITE, 0xB3000000.toInt(), android.graphics.Color.TRANSPARENT,
                            androidx.media3.ui.CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.BLACK, null,
                        ),
                    )
                    setFractionalTextSize(androidx.media3.ui.SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.15f)
                    setBottomPaddingFraction(0.06f)
                }
                this.player = player
            }
        },
        onRelease = { it.player = null },
        modifier = modifier,
    )
}
