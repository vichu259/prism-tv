package tv.prism.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.ListItemDefaults
import androidx.tv.material3.Text
import tv.prism.app.data.CategoryGroup
import tv.prism.app.data.Channel
import tv.prism.app.data.Programme
import tv.prism.app.player.PlayState

@Composable
fun HomeScreen(vm: AppViewModel, state: UiState) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var lastBack by remember { mutableStateOf(0L) }
    // Back: Live TV / Video Club -> Home; on Home it takes two presses to leave, so a stray press doesn't exit.
    BackHandler {
        when {
            state.section != Section.Home -> vm.setSection(Section.Home)
            System.currentTimeMillis() - lastBack < 2_000 -> (context as? android.app.Activity)?.finish()
            else -> {
                lastBack = System.currentTimeMillis()
                android.widget.Toast.makeText(context, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    Column(Modifier.fillMaxSize().appBackground()) {
        Header(vm, state)
        when (state.section) {
            Section.Home -> HomeHub(vm, state)
            Section.Live -> LiveSection(vm, state)
            Section.Vod -> VodSection(vm, state)
        }
    }
}

/** Top bar: app name, the Home / Live TV / Video Club tabs (OK switches), clock and source actions. */
@Composable
private fun Header(vm: AppViewModel, state: UiState) {
    val tabs = buildList {
        add(Section.Home to "Home")
        add(Section.Live to "Live TV")
        if (state.hasVod) add(Section.Vod to "Video Club")
    }
    Row(
        Modifier.fillMaxWidth().padding(start = 36.dp, end = 24.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Prism", fontSize = 26.sp, fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(brush = androidx.compose.ui.graphics.Brush.linearGradient(listOf(Palette.Text, Palette.Accent, Palette.AccentWarm))),
        )
        Spacer(Modifier.width(28.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tabs.forEach { (section, title) ->
                // OK switches; focus alone never does (focus also lands here on its own when the
                // content below is replaced, e.g. opening a title or leaving the player).
                SectionTab(title, selected = state.section == section) { vm.setSection(section) }
            }
        }
        Spacer(Modifier.weight(1f))
        Clock()
        Spacer(Modifier.width(18.dp))
        Button(onClick = vm::reload, colors = ButtonDefaults.colors(containerColor = Palette.Panel)) { Icon(Icons.Default.Refresh, "Reload channels", Modifier.size(18.dp)) }
        Spacer(Modifier.width(8.dp))
        Button(onClick = vm::showSetup, colors = ButtonDefaults.colors(containerColor = Palette.Panel)) { Icon(Icons.Default.Settings, "Sources", Modifier.size(18.dp)) }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(15_000); now = System.currentTimeMillis() } }
    Text(clock(now), color = Palette.Text, fontSize = 20.sp, fontWeight = FontWeight.Light)
}

/** One header tab: focused = white pill; current section = bold text + accent underline; otherwise muted text. */
@Composable
private fun SectionTab(title: String, selected: Boolean, onClick: () -> Unit) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(50)),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            // Only focus ever gets a background (Netflix / Google TV style); the current section is
            // marked by bright bold text and an underline, so two pills never show at once.
            containerColor = Color.Transparent,
            contentColor = if (selected) Palette.Text else Palette.Muted,
            focusedContainerColor = Palette.Text,
            focusedContentColor = Palette.Bg,
            pressedContainerColor = Palette.Text,
            pressedContentColor = Palette.Bg,
        ),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        Column(Modifier.padding(horizontal = 22.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 17.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Spacer(Modifier.height(3.dp))
            Box(
                Modifier.width(18.dp).height(2.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(1.dp))
                    .background(if (selected) Palette.Accent else Color.Transparent),
            )
        }
    }
}

@Composable
private fun LiveSection(vm: AppViewModel, state: UiState) {
    val railFocused = remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize().padding(start = 24.dp, top = 8.dp, bottom = 16.dp)) {
        CategoryRail(vm, state, Modifier.width(190.dp).fillMaxHeight().onFocusChanged { railFocused.value = it.hasFocus })
        Spacer(Modifier.width(12.dp))
        when {
            state.loading -> CenterMessage("Loading channels…", Modifier.weight(1f))
            state.loadError != null -> ErrorPane(state.loadError, vm, Modifier.weight(1f))
            else -> {
                ChannelColumn(vm, state, { railFocused.value }, Modifier.weight(0.55f).fillMaxHeight())
                Spacer(Modifier.width(16.dp))
                PreviewPane(vm, state, Modifier.weight(0.45f).fillMaxHeight().padding(end = 24.dp))
            }
        }
    }
}

@Composable
private fun CategoryRail(vm: AppViewModel, state: UiState, modifier: Modifier) {
    val fixed = listOf(UiState.FAVORITES to "Favorites", UiState.ALL to "All channels")
    Column(modifier) {
        GroupedRail(
            fixed = fixed,
            groups = state.groups,
            expanded = state.expanded,
            selectedId = state.categoryId.takeIf { state.query.isBlank() },
            allEntry = true,
            selectOnFocus = true,
            onSelect = vm::selectCategory,
            onToggle = vm::toggleLiveGroup,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Two-level category rail: fixed entries, then languages (or themes) that open on OK to show their
 * categories. [selectOnFocus] makes categories select as focus passes over them (cheap for Live,
 * where everything is already loaded); Video Club selects on OK so scrolling doesn't hit the portal.
 */
@Composable
fun GroupedRail(
    fixed: List<Pair<String, String>>,
    groups: List<CategoryGroup>,
    expanded: Set<String>,
    selectedId: String?,
    allEntry: Boolean,
    selectOnFocus: Boolean,
    onSelect: (String) -> Unit,
    onToggle: (CategoryGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(fixed, key = { it.first }) { (id, title) ->
            RailItem(title, selected = selectedId == id, selectOnFocus = selectOnFocus) { onSelect(id) }
        }
        groups.forEach { g ->
            if (g.single) {
                val c = g.items.first().category
                item(key = g.id) { RailItem(g.title, selected = selectedId == c.id, selectOnFocus = selectOnFocus) { onSelect(c.id) } }
                return@forEach
            }
            val open = g.id in expanded
            val inside = selectedId == g.id || g.items.any { it.category.id == selectedId }
            item(key = g.id) {
                ListItem(
                    // A closed group lights up when the current category is inside it, so you can find your way back.
                    selected = !open && inside,
                    onClick = { onToggle(g) },
                    headlineContent = { Text(g.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
                    trailingContent = { Text(if (open) "▾" else "▸  ${g.items.size}", fontSize = 13.sp) },
                    colors = ListItemDefaults.colors(selectedContainerColor = Palette.PanelHi, selectedContentColor = Palette.Text, contentColor = Palette.Text),
                )
            }
            if (open) {
                if (allEntry) item(key = "${g.id}#all") {
                    RailItem("All ${g.title}", selected = selectedId == g.id, selectOnFocus = selectOnFocus, indent = true) { onSelect(g.id) }
                }
                items(g.items, key = { "${g.id}#${it.category.id}" }) { gc ->
                    RailItem(gc.label, selected = selectedId == gc.category.id, selectOnFocus = selectOnFocus, indent = true) { onSelect(gc.category.id) }
                }
            }
        }
    }
}

@Composable
fun RailItem(title: String, selected: Boolean, selectOnFocus: Boolean = true, indent: Boolean = false, onSelect: () -> Unit) {
    ListItem(
        selected = selected,
        onClick = onSelect,
        headlineContent = {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = if (indent) 15.sp else 16.sp,
                modifier = if (indent) Modifier.padding(start = 14.dp) else Modifier)
        },
        colors = ListItemDefaults.colors(
            selectedContainerColor = Palette.PanelHi,
            selectedContentColor = Palette.Text,
            contentColor = Palette.Muted,
        ),
        // Selecting on focus makes the channel list follow the rail as you scroll, like a set-top box.
        modifier = if (selectOnFocus) Modifier.onFocusChanged { if (it.isFocused) onSelect() } else Modifier,
    )
}

@Composable
private fun ChannelColumn(vm: AppViewModel, state: UiState, railFocused: () -> Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val focusTarget = remember { FocusRequester() }
    val searchFocused = remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()

    val targetId = state.focused?.id?.takeIf { id -> state.channels.any { it.id == id } } ?: state.channels.firstOrNull()?.id

    // Land on the current channel (not the search box) when the list appears or we return from fullscreen.
    LaunchedEffect(state.channels.size, state.categoryId, state.liveGridMode) {
        val idx = state.channels.indexOfFirst { it.id == targetId }
        if (idx > 0) {
            if (state.liveGridMode) gridState.scrollToItem(idx) else listState.scrollToItem((idx - 3).coerceAtLeast(0))
        }
        withFrameNanos { }
        if (!railFocused() && !searchFocused.value) runCatching { focusTarget.requestFocus() }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 10.dp)) {
            TvTextField(
                label = "",
                value = state.query,
                onValueChange = vm::search,
                placeholder = "Search ${state.lineup.channels.size} channels",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Text,
                modifier = Modifier.weight(1f).onFocusChanged { searchFocused.value = it.hasFocus },
            )
            Spacer(Modifier.width(10.dp))
            ViewModeButton(listMode = !state.liveGridMode, onClick = vm::toggleLiveGridMode)
        }
        if (state.channels.isEmpty()) {
            val msg = when {
                state.query.isNotBlank() -> "No channels match \"${state.query}\""
                state.categoryId == UiState.FAVORITES -> "No favorites yet.\nHold OK on a channel to add it."
                else -> "No channels here"
            }
            CenterMessage(msg, Modifier.fillMaxWidth().weight(1f))
            return@Column
        }
        if (state.liveGridMode) {
            // Card mode: channel tiles; same focus/preview/fullscreen/favorite behaviour as the list.
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(150.dp),
                state = gridState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                items(state.channels.size, key = { state.channels[it].id }) { i ->
                    val ch = state.channels[i]
                    ChannelTile(
                        ch = ch,
                        favorite = ch.id in state.favorites,
                        playing = ch.id == state.playing?.id,
                        nowTitle = state.epg[ch.id]?.firstOrNull { it.startMs <= now }?.title,
                        onFocus = { vm.focus(ch) },
                        onClick = { vm.openPlayer(ch) },
                        onLongClick = { vm.toggleFavorite(ch) },
                        modifier = if (ch.id == targetId) Modifier.focusRequester(focusTarget) else Modifier,
                    )
                }
            }
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(state.channels, key = { it.id }) { ch ->
                val isFocusedTarget = ch.id == targetId
                ChannelRow(
                    ch = ch,
                    favorite = ch.id in state.favorites,
                    playing = ch.id == state.playing?.id,
                    now = state.epg[ch.id]?.firstOrNull { it.startMs <= now },
                    nowMs = now,
                    onFocus = { vm.focus(ch) },
                    onClick = { vm.openPlayer(ch) },
                    onLongClick = { vm.toggleFavorite(ch) },
                    modifier = if (isFocusedTarget) Modifier.focusRequester(focusTarget) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ChannelTile(
    ch: Channel,
    favorite: Boolean,
    playing: Boolean,
    nowTitle: String?,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        onLongClick = onLongClick,
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        border = androidx.tv.material3.ClickableSurfaceDefaults.border(
            border = if (playing) androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Palette.Accent), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)) else androidx.tv.material3.Border.None,
            focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Palette.Text), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)),
        ),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(containerColor = Palette.Panel, focusedContainerColor = Palette.PanelHi, contentColor = Palette.Text, focusedContentColor = Palette.Text),
        modifier = modifier.fillMaxWidth().onFocusChanged { if (it.isFocused) onFocus() },
    ) {
        Column(Modifier.padding(10.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = Alignment.Center) {
                Logo(ch.logo, ch.name, 52.dp)
                Text("${ch.number}", color = Palette.Muted, fontSize = 11.sp, modifier = Modifier.align(Alignment.TopStart))
                if (favorite) Icon(Icons.Default.Star, "Favorite", tint = Palette.Fav, modifier = Modifier.align(Alignment.TopEnd).size(14.dp))
            }
            Text(ch.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(nowTitle ?: " ", color = Palette.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ChannelRow(
    ch: Channel,
    favorite: Boolean,
    playing: Boolean,
    now: Programme?,
    nowMs: Long,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier,
) {
    ListItem(
        selected = playing,
        onClick = onClick,
        onLongClick = onLongClick,
        leadingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${ch.number}", fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.width(40.dp))
                Logo(ch.logo, ch.name, 40.dp)
            }
        },
        headlineContent = { Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp) },
        supportingContent = now?.let { p ->
            {
                Column {
                    Text(p.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 13.sp)
                    Spacer(Modifier.height(4.dp))
                    ProgressLine(p.progress(nowMs))
                }
            }
        },
        trailingContent = if (favorite) ({ Icon(Icons.Default.Star, "Favorite", tint = Palette.Fav, modifier = Modifier.size(18.dp)) }) else null,
        colors = ListItemDefaults.colors(
            selectedContainerColor = Palette.Accent.copy(alpha = 0.18f),
            selectedContentColor = Palette.Text,
            contentColor = Palette.Text,
        ),
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocus() },
    )
}

@Composable
private fun PreviewPane(vm: AppViewModel, state: UiState, modifier: Modifier) {
    val playState by vm.player.state.collectAsState()
    val playerError by vm.player.error.collectAsState()
    val resolveError by vm.playError.collectAsState()
    val ch = state.focused
    val now = System.currentTimeMillis()

    Column(modifier) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(14.dp)).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (state.playing != null) VideoSurface(vm.player.player, Modifier.fillMaxSize(), showSubtitles = false)
            val err = resolveError ?: playerError
            when {
                ch == null -> Text("Pick a channel", color = Palette.Muted)
                err != null && state.playing?.id == ch.id -> Text(err, color = Palette.Error, modifier = Modifier.padding(24.dp))
                playState == PlayState.Loading || state.playing?.id != ch.id -> Text("Tuning…", color = Palette.Muted)
                else -> Unit
            }
        }
        if (ch != null) {
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(ch.logo, ch.name, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(ch.name, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Channel ${ch.number}", color = Palette.Muted, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
            NowNext(state.epg[ch.id].orEmpty(), now)
            Spacer(Modifier.weight(1f))
            Text("OK  Watch fullscreen     Hold OK  Favorite", color = Palette.Muted, fontSize = 13.sp)
        }
    }
}

@Composable
fun NowNext(epg: List<Programme>, nowMs: Long) {
    if (epg.isEmpty()) {
        Text("No guide information", color = Palette.Muted, fontSize = 15.sp)
        return
    }
    epg.take(2).forEachIndexed { i, p ->
        val live = p.startMs <= nowMs
        Column(Modifier.padding(bottom = 12.dp)) {
            Text(
                (if (live) "NOW  " else "NEXT  ") + "${clock(p.startMs)} – ${clock(p.endMs)}",
                color = if (live) Palette.Accent else Palette.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(p.title, color = Palette.Text, fontSize = if (i == 0) 19.sp else 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (live) { Spacer(Modifier.height(6.dp)); ProgressLine(p.progress(nowMs)) }
        }
    }
}

@Composable
private fun ErrorPane(message: String, vm: AppViewModel, modifier: Modifier) {
    Column(modifier.fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Couldn't load channels", fontSize = 24.sp, color = Palette.Text)
        Spacer(Modifier.height(8.dp))
        Text(message, color = Palette.Error, fontSize = 15.sp)
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = vm::reload) { Text("Try again") }
            Button(onClick = vm::showSetup) { Text("Edit sources") }
        }
    }
}

@Composable
fun CenterMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxHeight(), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.Muted, fontSize = 18.sp)
    }
}
