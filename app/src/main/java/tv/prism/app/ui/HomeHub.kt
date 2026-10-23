package tv.prism.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import tv.prism.app.data.Channel
import tv.prism.app.data.HistoryEntry

/**
 * The Home page: a hero for the last thing watched, then rows built purely from local history
 * and favorites, so it opens instantly and never calls the portal.
 */
@Composable
fun HomeHub(vm: AppViewModel, state: UiState) {
    val firstFocus = remember { FocusRequester() }
    val history = state.history.filter { it.vod != null || vm.channelFor(it) != null }
    val continueRow = history.filter { it.inProgress }
    val favoriteTitles = state.vodFavorites
    val favorites = state.lineup.channels.filter { it.id in state.favorites }
    // What the remote is on; the info area at the top describes it (Netflix TV style).
    var focusedEntry by remember { mutableStateOf<HistoryEntry?>(null) }
    var focusedChannel by remember { mutableStateOf<Channel?>(null) }
    val onEntry: (HistoryEntry) -> Unit = { focusedEntry = it; focusedChannel = null }
    val onChannel: (Channel) -> Unit = { focusedChannel = it; focusedEntry = null }

    // Kept deliberately short: what you're in the middle of, and what you saved. Browsing by
    // category belongs to Video Club; the last channel already reopens on Live TV.
    val rows = buildList<@Composable () -> Unit> {
        if (continueRow.isNotEmpty()) add { VodRow("Continue watching", continueRow, vm, onEntry) }
        if (favoriteTitles.isNotEmpty()) add { VodRow("Favorites", favoriteTitles, vm, onEntry, onClick = vm::openFromHistory) }
        if (favorites.isNotEmpty()) add { ChannelRow("Favorite channels", favorites, state, vm, onChannel) }
    }

    if (continueRow.isEmpty() && favorites.isEmpty() && favoriteTitles.isEmpty()) {
        Welcome(vm, state, firstFocus)
        LaunchedEffect(Unit) { androidx.compose.runtime.withFrameNanos { }; runCatching { firstFocus.requestFocus() } }
        return
    }

    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstFocus.requestFocus() }
    }

    Column(Modifier.fillMaxSize()) {
        InfoArea(vm, state, focusedEntry, focusedChannel)
        // No horizontal padding here: each row pads itself, so a card growing on focus isn't clipped.
        LazyColumn(
            contentPadding = PaddingValues(top = 0.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            // The first row's first card takes the initial focus.
            items(rows.size) { i ->
                androidx.compose.runtime.CompositionLocalProvider(LocalFirstFocus provides if (i == 0) firstFocus else null) {
                    rows[i]()
                }
            }
        }
    }
}

/** Hands the initial-focus requester to the first card of the first row only. */
private val LocalFirstFocus = androidx.compose.runtime.compositionLocalOf<FocusRequester?> { null }

/** Top of Home: artwork and details for the focused card. Not focusable itself. */
@Composable
private fun InfoArea(vm: AppViewModel, state: UiState, entry: HistoryEntry?, channel: Channel?) {
    val ch = channel ?: entry?.let { vm.channelFor(it) }
    val item = entry?.vod
    Box(Modifier.fillMaxWidth().height(150.dp)) {
        val art = item?.poster ?: ch?.logo
        if (art != null) {
            // Artwork anchored right, fading into the backdrop on the left and bottom.
            // Portals only have portrait posters; crop from the top, where faces and title art sit.
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.5f).fillMaxHeight().alpha(0.5f),
            )
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0.45f to Palette.Bg, 0.8f to Palette.Bg.copy(alpha = 0.5f), 1f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Palette.Bg)))
        }
        Column(Modifier.padding(start = 48.dp, end = 48.dp, top = 4.dp).fillMaxWidth(0.6f)) {
            when {
                item != null && entry != null -> {
                    item.network?.let { Text(it.uppercase(), color = Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
                    Text(item.displayName, color = Palette.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle(entry, null), color = Palette.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.durationMs > 0 && !entry.finished) {
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ProgressLine(entry.progress, Modifier.width(240.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(timeLeft(entry), color = Palette.Muted, fontSize = 14.sp)
                        }
                    }
                    if (item.description.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(item.description, color = Palette.Text.copy(alpha = 0.7f), fontSize = 14.sp, lineHeight = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                ch != null -> {
                    val now = state.epg[ch.id]?.firstOrNull { it.startMs <= System.currentTimeMillis() }
                    Text(ch.name, color = Palette.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text("Channel ${ch.number}" + (now?.let { "  ·  Now: ${it.title}" } ?: ""), color = Palette.Muted, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                else -> {
                    Text("Welcome back", color = Palette.Text, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("Pick up where you left off.", color = Palette.Muted, fontSize = 16.sp)
                }
            }
        }
    }
}

// --- Welcome ---------------------------------------------------------------------------------

@Composable
private fun Welcome(vm: AppViewModel, state: UiState, focus: FocusRequester) {
    Column(Modifier.fillMaxWidth().padding(end = 48.dp, top = 24.dp)) {
        Text("Welcome to Prism", color = Palette.Text, fontSize = 38.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "What you watch shows up here, so you can pick up where you left off.",
            color = Palette.Muted, fontSize = 17.sp,
        )
        Spacer(Modifier.height(22.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { vm.setSection(Section.Live) }, modifier = Modifier.focusRequester(focus)) { Text("Browse Live TV") }
            if (state.hasVod) OutlinedButton(onClick = { vm.setSection(Section.Vod) }) { Text("Open Video Club") }
        }
    }
}

// --- Rows ------------------------------------------------------------------------------------

@Composable
private fun VodRow(
    title: String,
    entries: List<HistoryEntry>,
    vm: AppViewModel,
    onFocus: (HistoryEntry) -> Unit,
    onClick: (HistoryEntry) -> Unit = vm::playFromHistory,
) {
    val first = LocalFirstFocus.current
    Column {
        SectionTitle(title, Modifier.padding(start = 48.dp, bottom = 2.dp))
        // Vertical/horizontal padding leaves room for the focused card's 1.08x scale and outline.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 10.dp)) {
            itemsIndexed(entries, key = { _, e -> e.key }) { i, e ->
                VodCard(e, vm, onFocus, onClick, if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

@Composable
private fun VodCard(e: HistoryEntry, vm: AppViewModel, onFocus: (HistoryEntry) -> Unit, onClick: (HistoryEntry) -> Unit, modifier: Modifier) {
    val item = e.vod ?: return
    Column(Modifier.width(112.dp)) {
        Surface(
            onClick = { onClick(e) },
            onLongClick = { vm.openFromHistory(e) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Palette.Text), shape = RoundedCornerShape(12.dp)),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Panel, focusedContainerColor = Palette.PanelHi),
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f).onFocusChanged { if (it.isFocused) onFocus(e) },
        ) {
            Box(Modifier.fillMaxSize()) {
                Poster(item, Modifier.fillMaxSize())
                if (e.durationMs > 0 && !e.finished) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                        ProgressLine(e.progress)
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(item.displayName, color = Palette.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            listOfNotNull(
                e.episode?.let { episodeLabel(e) },
                if (e.episode == null && e.durationMs > 0 && !e.finished) timeLeft(e) else null,
            ).joinToString(" · "),
            color = Palette.Muted, fontSize = 12.sp, maxLines = 1,
        )
    }
}

@Composable
private fun ChannelRow(title: String, channels: List<Channel>, state: UiState, vm: AppViewModel, onFocus: (Channel) -> Unit) {
    val first = LocalFirstFocus.current
    Column {
        SectionTitle(title, Modifier.padding(start = 48.dp, bottom = 2.dp))
        // Vertical/horizontal padding leaves room for the focused card's 1.08x scale and outline.
        LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 10.dp, bottom = 10.dp)) {
            itemsIndexed(channels, key = { _, ch -> ch.id }) { i, ch ->
                ChannelCard(ch, state, vm, onFocus, if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier)
            }
        }
    }
}

@Composable
private fun ChannelCard(ch: Channel, state: UiState, vm: AppViewModel, onFocus: (Channel) -> Unit, modifier: Modifier) {
    val now = state.epg[ch.id]?.firstOrNull { it.startMs <= System.currentTimeMillis() }
    Column(Modifier.width(176.dp)) {
        Surface(
            onClick = { vm.openPlayer(ch) },
            onLongClick = { vm.toggleFavorite(ch) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(2.dp, Palette.Text), shape = RoundedCornerShape(12.dp)),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Panel, focusedContainerColor = Palette.PanelHi),
            modifier = modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { if (it.isFocused) onFocus(ch) },
        ) {
            Box(Modifier.fillMaxSize().background(Palette.Panel), contentAlignment = Alignment.Center) {
                Logo(ch.logo, ch.name, 52.dp)
                Text("${ch.number}", color = Palette.Muted, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopStart).padding(10.dp))
                if (ch.id in state.favorites) {
                    Icon(Icons.Default.Star, "Favorite", tint = Palette.Fav, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).width(16.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(ch.name, color = Palette.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (now != null) Text(now.title, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// --- Labels ----------------------------------------------------------------------------------

private fun episodeLabel(e: HistoryEntry): String {
    val s = e.season?.number?.takeIf { it > 0 }?.let { "S$it · " }.orEmpty()
    return s + "E${e.episode?.number ?: 0}"
}

private fun subtitle(e: HistoryEntry, channel: Channel?): String = when {
    channel != null -> "Channel ${channel.number}"
    e.episode != null -> episodeLabel(e) + (e.episode.name.takeIf { !it.startsWith("Episode") }?.let { "  ·  $it" } ?: "")
    else -> listOfNotNull(e.vod?.year?.ifBlank { null }, e.vod?.rating?.ifBlank { null }?.let { "IMDb $it" }).joinToString("  ·  ")
}

private fun timeLeft(e: HistoryEntry): String {
    val left = ((e.durationMs - e.positionMs) / 60_000).coerceAtLeast(1)
    return if (left >= 60) "${left / 60}h ${left % 60}m left" else "${left}m left"
}
