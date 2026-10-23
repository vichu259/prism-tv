package tv.prism.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text
import tv.prism.app.player.PlayerManager
import tv.prism.app.player.TrackOption

/**
 * Side panel over the video: subtitles (Off + every text track) and, when there's a choice, audio
 * language. OK picks and closes; Back closes. Focus starts on the current choice.
 */
@Composable
fun TrackPanel(player: PlayerManager, onClose: () -> Unit) {
    val text by player.textTracks.collectAsState()
    val audio by player.audioTracks.collectAsState()
    val start = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { start.requestFocus() }
    }
    val subsOff = text.none { it.selected }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.width(380.dp).fillMaxHeight().background(Color.Black.copy(alpha = 0.92f)).padding(horizontal = 20.dp, vertical = 28.dp),
        ) {
            item { PanelHeader("Subtitles") }
            item {
                Choice("Off", selected = subsOff, modifier = if (subsOff) Modifier.focusRequester(start) else Modifier) {
                    player.selectSubtitles(null); onClose()
                }
            }
            if (text.isEmpty()) {
                item { Text("This stream has no subtitles", color = Palette.Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp)) }
            }
            items(text) { t ->
                Choice(t.label, selected = t.selected, modifier = if (t.selected) Modifier.focusRequester(start) else Modifier) {
                    player.selectSubtitles(t); onClose()
                }
            }
            if (audio.size > 1) {
                item { Spacer(Modifier.height(16.dp)); PanelHeader("Audio") }
                items(audio) { a ->
                    Choice(a.label, selected = a.selected) { player.selectAudio(a); onClose() }
                }
            }
        }
    }
}

@Composable
private fun PanelHeader(title: String) {
    Text(title, color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp, top = 4.dp))
}

@Composable
private fun Choice(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onClick,
        headlineContent = { Text(label, fontSize = 16.sp) },
        trailingContent = if (selected) ({ Text("✓", fontSize = 16.sp) }) else null,
        colors = ListItemDefaults.colors(selectedContainerColor = Palette.PanelHi, selectedContentColor = Palette.Text, contentColor = Palette.Text),
        modifier = modifier,
    )
}

/** Small "CC" tag for info bars when subtitles are showing. */
@Composable
fun CcBadge(options: List<TrackOption>) {
    if (options.none { it.selected }) return
    Text(
        "CC", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.background(Palette.Text, androidx.compose.foundation.shape.RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}
