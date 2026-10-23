package tv.prism.app.ui

import android.view.KeyEvent as AndroidKey
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.tv.material3.Button
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil.compose.SubcomposeAsyncImage
import kotlinx.coroutines.delay
import tv.prism.app.data.AddedDate
import tv.prism.app.data.VodItem
import tv.prism.app.player.PlayState

// --- Browser ---------------------------------------------------------------------------------

@Composable
fun VodSection(vm: AppViewModel, state: UiState) {
    val v = state.vod
    v.selected?.let {
        VodDetail(vm, it, v)
        return
    }
    Row(Modifier.fillMaxSize().padding(start = 24.dp, top = 8.dp, bottom = 16.dp)) {
        GroupedRail(
            fixed = v.categories.filter { it.id == "*" }.map { it.id to it.title },
            groups = v.groups,
            expanded = v.expanded,
            selectedId = v.categoryId.takeIf { v.query.isBlank() },
            // The portal can't page several categories as one, so no "All English" here.
            allEntry = false,
            // Each category is a portal request: load on OK, not as focus scrolls past.
            selectOnFocus = false,
            onSelect = vm::selectVodCategory,
            onToggle = vm::toggleVodGroup,
            modifier = Modifier.width(220.dp).fillMaxHeight(),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f).fillMaxHeight().padding(end = 24.dp)) {
            Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 10.dp)) {
                TvTextField(
                    label = "",
                    value = v.query,
                    onValueChange = vm::searchVod,
                    placeholder = "Search movies and shows",
                    keyboardType = KeyboardType.Text,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                ViewModeButton(listMode = state.vodListMode, onClick = vm::toggleVodListMode)
            }
            when {
                v.error != null && v.items.isEmpty() -> CenterMessage(v.error, Modifier.fillMaxWidth())
                v.items.isEmpty() && v.loading -> CenterMessage("Loading titles…", Modifier.fillMaxWidth())
                v.items.isEmpty() && v.categories.isNotEmpty() && !v.hasMore -> CenterMessage(
                    if (v.query.isNotBlank()) "Nothing matches \"${v.query}\"" else "Nothing in this category", Modifier.fillMaxWidth(),
                )
                state.vodListMode -> TitleList(vm, v)
                else -> PosterGrid(vm, v)
            }
        }
    }
}

@Composable
private fun PosterGrid(vm: AppViewModel, v: VodState) {
    val grid = rememberLazyGridState()
    val returnFocus = remember { FocusRequester() }
    val returnIndex = v.items.indexOfFirst { it.id == v.lastOpenedId }
    // Fetch the next page while the viewer is still a couple of rows away from the end.
    LaunchedEffect(grid, v.items.size) {
        androidx.compose.runtime.snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= v.items.size - 12) vm.loadMoreVod() }
    }
    // Coming back from a detail page: scroll to the title that was opened and focus it again.
    LaunchedEffect(Unit) {
        if (returnIndex >= 0) {
            grid.scrollToItem(returnIndex)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { returnFocus.requestFocus() }
        }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        state = grid,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        // Room around the edges so a focused poster's 1.08x scale and outline aren't clipped.
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(v.items, key = { i, it -> "${it.id}#$i" }) { i, item ->
            PosterCard(
                item,
                favorite = vm.isVodFavorite(item),
                onLongClick = { vm.toggleVodFavorite(item) },
                modifier = if (i == returnIndex) Modifier.focusRequester(returnFocus) else Modifier,
            ) { vm.openVod(item) }
        }
    }
}

/** Video Club as a list: small poster, title, year/rating/added and a short description. */
@Composable
private fun TitleList(vm: AppViewModel, v: VodState) {
    val list = androidx.compose.foundation.lazy.rememberLazyListState()
    val returnFocus = remember { FocusRequester() }
    val returnIndex = v.items.indexOfFirst { it.id == v.lastOpenedId }
    LaunchedEffect(list, v.items.size) {
        androidx.compose.runtime.snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .collect { last -> if (last >= v.items.size - 6) vm.loadMoreVod() }
    }
    LaunchedEffect(Unit) {
        if (returnIndex >= 0) {
            list.scrollToItem(returnIndex)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { returnFocus.requestFocus() }
        }
    }
    LazyColumn(
        state = list,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 24.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(v.items, key = { i, it -> "${it.id}#$i" }) { i, item ->
            Surface(
                onClick = { vm.openVod(item) },
                onLongClick = { vm.toggleVodFavorite(item) },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Palette.PanelHi),
                modifier = (if (i == returnIndex) Modifier.focusRequester(returnFocus) else Modifier).fillMaxWidth(),
            ) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Poster(item, Modifier.width(64.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(item.displayName, color = Palette.Text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (vm.isVodFavorite(item)) Text("  ♥", color = Palette.Text, fontSize = 14.sp)
                            if (AddedDate.isNew(item.addedMs)) NewBadge(Modifier.padding(start = 10.dp))
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(metaLine(item, long = true), color = Palette.Muted, fontSize = 13.sp, maxLines = 1)
                        if (item.description.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(item.description, color = Palette.Text.copy(alpha = 0.7f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

/** "2024 · ★ 7.1 · Added 3d ago" (cards drop the rating to stay short). */
private fun metaLine(item: VodItem, long: Boolean): String = listOfNotNull(
    item.year.ifBlank { null },
    if (long) item.rating.ifBlank { null }?.let { "★ $it" } else null,
    if (long && item.isSeries) "Series" else null,
    AddedDate.label(item.addedMs).ifBlank { null }?.let { "Added $it" },
).joinToString(" · ")

@Composable
private fun NewBadge(modifier: Modifier = Modifier) {
    Text(
        "NEW", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
        modifier = modifier.clip(RoundedCornerShape(4.dp)).background(Palette.Text).padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** ☰ / ▦ switch between list and cards. */
@Composable
fun ViewModeButton(listMode: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, colors = androidx.tv.material3.ButtonDefaults.colors(containerColor = Palette.Panel)) {
        Text(if (listMode) "▦  Cards" else "☰  List", fontSize = 14.sp)
    }
}

@Composable
private fun PosterCard(
    item: VodItem,
    favorite: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Column {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.08f),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(2.dp, Palette.Text), shape = RoundedCornerShape(10.dp)),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Panel, focusedContainerColor = Palette.PanelHi),
            modifier = modifier.fillMaxWidth().aspectRatio(2f / 3f),
        ) {
            Box(Modifier.fillMaxSize()) {
                Poster(item, Modifier.fillMaxSize())
                if (AddedDate.isNew(item.addedMs)) NewBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
                if (favorite) {
                    Text(
                        "♥", color = Palette.Text, fontSize = 14.sp,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(item.displayName, color = Palette.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val meta = metaLine(item, long = false)
        if (meta.isNotEmpty()) Text(meta, color = Palette.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Poster with a title card fallback; many portals have gaps in their artwork. */
@Composable
fun Poster(item: VodItem, modifier: Modifier) {
    val fallback: @Composable () -> Unit = {
        Box(
            Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Palette.PanelHi, Palette.Panel))).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(item.displayName, color = Palette.Muted, fontSize = 15.sp, textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
    if (item.poster == null) Box(modifier) { fallback() }
    else SubcomposeAsyncImage(
        model = item.poster, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = modifier,
        loading = { fallback() }, error = { fallback() },
    )
}

// --- Detail ----------------------------------------------------------------------------------

@Composable
private fun VodDetail(vm: AppViewModel, item: VodItem, v: VodState) {
    val playFocus = remember { FocusRequester() }
    BackHandler { vm.closeVod() }
    // Land on Play (movie) or the first season (show) once we know which it is.
    // Only on first arrival: picking another season later must leave focus on the season chips.
    var placed by remember(item.id) { mutableStateOf(false) }
    LaunchedEffect(item.id, v.detailLoading, v.episodesLoading) {
        if (!placed && !v.detailLoading && !v.episodesLoading) {
            androidx.compose.runtime.withFrameNanos { }
            placed = runCatching { playFocus.requestFocus() }.isSuccess
        }
    }

    Row(Modifier.fillMaxSize().padding(start = 48.dp, end = 48.dp, top = 12.dp, bottom = 24.dp)) {
        Poster(item, Modifier.width(240.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.width(32.dp))
        Column(Modifier.weight(1f).fillMaxHeight()) {
            item.network?.let { Text(it.uppercase(), color = Palette.Muted, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp) }
            Text(item.displayName, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Palette.Text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            val meta = listOfNotNull(
                item.year.ifBlank { null },
                item.rating.ifBlank { null }?.let { "IMDb $it" },
                item.durationMin.takeIf { it > 0 && v.seasons.isEmpty() }?.let { "${it / 60}h ${it % 60}m".removePrefix("0h ") },
                v.seasons.size.takeIf { it > 1 }?.let { "$it seasons" },
                AddedDate.label(item.addedMs).ifBlank { null }?.let { "Added $it" },
            )
            if (meta.isNotEmpty()) Text(meta.joinToString("  ·  "), color = Palette.Muted, fontSize = 16.sp)
            if (item.description.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    item.description, color = Palette.Text.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 22.sp,
                    maxLines = if (v.seasons.isEmpty()) 8 else 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(18.dp))
            when {
                v.detailLoading -> Text("Loading…", color = Palette.Muted, fontSize = 16.sp)
                v.seasons.isEmpty() -> MovieActions(vm, item, playFocus)
                else -> SeasonsAndEpisodes(vm, item, v, playFocus)
            }
        }
    }
}

@Composable
private fun MovieActions(vm: AppViewModel, item: VodItem, playFocus: FocusRequester) {
    val resume = vm.resumeMs(item)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { vm.playVod(item) }, modifier = Modifier.focusRequester(playFocus)) {
            Text(if (resume > 0) "▶  Resume from ${formatTime(resume)}" else "▶  Play")
        }
        if (resume > 0) OutlinedButton(onClick = { vm.playVod(item, fromStart = true) }) { Text("Start over") }
        FavoriteButton(vm, item)
    }
}

@Composable
private fun FavoriteButton(vm: AppViewModel, item: VodItem) {
    val fav = vm.isVodFavorite(item)
    OutlinedButton(onClick = { vm.toggleVodFavorite(item) }) { Text(if (fav) "♥  In favorites" else "♡  Favorite") }
}

@Composable
private fun SeasonsAndEpisodes(vm: AppViewModel, item: VodItem, v: VodState, firstFocus: FocusRequester) {
    val last = vm.lastEpisodeOf(item)
    val unnamedSingle = v.seasons.size == 1 && v.seasons.first().name == "Episodes"
    val season = v.season
    val lastIndex = if (season != null && last?.first == season.id) v.episodes.indexOfFirst { it.id == last.second } else -1
    val list = androidx.compose.foundation.lazy.rememberLazyListState()

    // Show where the viewer left off: scroll the episode list to the last-watched episode.
    LaunchedEffect(season?.id, v.episodes.size) { if (lastIndex > 2) list.scrollToItem(lastIndex - 2) }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (last != null && season != null && last.first == season.id && lastIndex >= 0) {
            val ep = v.episodes[lastIndex]
            val resume = vm.resumeMs(item, ep)
            Button(onClick = { vm.playEpisode(item, season, ep) }, modifier = Modifier.focusRequester(firstFocus)) {
                Text(
                    "▶  Continue " + (if (season.number > 0) "S${season.number} · " else "") + "E${ep.number}" +
                        (if (resume > 0) "  from ${formatTime(resume)}" else ""),
                )
            }
        }
        FavoriteButton(vm, item)
    }
    Spacer(Modifier.height(12.dp))
    // The Continue button takes initial focus when present; otherwise the first season chip / episode does.
    val continueShown = last != null && season != null && last.first == season.id && lastIndex >= 0

    if (!unnamedSingle) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(v.seasons, key = { _, s -> s.id }) { i, s ->
                FilterChip(
                    selected = season?.id == s.id,
                    onClick = { vm.selectSeason(s) },
                    colors = FilterChipDefaults.colors(
                        selectedContainerColor = Palette.Accent, selectedContentColor = Color.White,
                        focusedSelectedContainerColor = Palette.Accent, focusedSelectedContentColor = Color.White,
                    ),
                    modifier = if (i == 0 && !continueShown) Modifier.focusRequester(firstFocus) else Modifier,
                ) { Text(if (s.number > 0) "Season ${s.number}" else s.name) }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
    season ?: return
    when {
        v.episodesLoading -> Text("Loading episodes…", color = Palette.Muted, fontSize = 15.sp)
        v.episodes.isEmpty() -> Text("No episodes in this season", color = Palette.Muted, fontSize = 15.sp)
        else -> LazyColumn(state = list, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
            itemsIndexed(v.episodes, key = { _, e -> e.id }) { i, ep ->
                val resume = vm.resumeMs(item, ep)
                val isLast = i == lastIndex
                ListItem(
                    selected = isLast,
                    onClick = { vm.playEpisode(item, season, ep) },
                    onLongClick = if (resume > 0) ({ vm.playEpisode(item, season, ep, fromStart = true) }) else null,
                    leadingContent = { Text("E${ep.number}", color = Palette.Muted, fontSize = 15.sp, modifier = Modifier.width(48.dp)) },
                    headlineContent = { Text(ep.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp) },
                    supportingContent = if (isLast) ({ Text("Last watched", fontSize = 12.sp) }) else null,
                    trailingContent = if (resume > 0) ({ Text("Resume ${formatTime(resume)}", color = Palette.Accent, fontSize = 13.sp) }) else null,
                    colors = androidx.tv.material3.ListItemDefaults.colors(
                        selectedContainerColor = Palette.Accent.copy(alpha = 0.18f), selectedContentColor = Palette.Text,
                    ),
                    modifier = if (i == 0 && unnamedSingle && !continueShown) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

// --- Movie player ----------------------------------------------------------------------------

@Composable
fun VodPlayerScreen(vm: AppViewModel, state: UiState) {
    val item = state.vod.playing ?: return
    val playState by vm.player.state.collectAsState()
    val playerError by vm.player.error.collectAsState()
    val resolveError by vm.playError.collectAsState()
    val focus = remember { FocusRequester() }
    var poke by remember { mutableIntStateOf(1) }
    var barVisible by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var paused by remember { mutableStateOf(false) }

    BackHandler { vm.closeVodPlayer() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(poke) {
        barVisible = true
        delay(4_000)
        if (!paused) barVisible = false
    }
    // Poll the clock for the progress bar, and save the resume point every so often.
    LaunchedEffect(item.id, state.vod.playingEpisode?.id) {
        var ticks = 0
        while (true) {
            val p = vm.player.player
            position = p.currentPosition
            duration = p.duration.coerceAtLeast(0)
            paused = !p.playWhenReady
            if (++ticks % 30 == 0) vm.saveVodPosition()
            delay(500)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val ne = e.nativeKeyEvent
                // Holding a skip key speeds up: 10 s steps become 60 s after a moment.
                val boost = if (ne.repeatCount > 5) 6 else 1
                when (ne.keyCode) {
                    AndroidKey.KEYCODE_DPAD_CENTER, AndroidKey.KEYCODE_ENTER,
                    AndroidKey.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKey.KEYCODE_SPACE -> if (ne.repeatCount == 0) vm.player.togglePause()
                    AndroidKey.KEYCODE_MEDIA_PLAY -> vm.player.player.play()
                    AndroidKey.KEYCODE_MEDIA_PAUSE -> vm.player.player.pause()
                    AndroidKey.KEYCODE_DPAD_LEFT, AndroidKey.KEYCODE_MEDIA_REWIND -> vm.player.seekBy(-10_000L * boost)
                    AndroidKey.KEYCODE_DPAD_RIGHT, AndroidKey.KEYCODE_MEDIA_FAST_FORWARD -> vm.player.seekBy(30_000L * boost)
                    AndroidKey.KEYCODE_CHANNEL_UP, AndroidKey.KEYCODE_MEDIA_NEXT -> vm.playAdjacentEpisode(+1)
                    AndroidKey.KEYCODE_CHANNEL_DOWN, AndroidKey.KEYCODE_MEDIA_PREVIOUS -> vm.playAdjacentEpisode(-1)
                    AndroidKey.KEYCODE_DPAD_UP, AndroidKey.KEYCODE_DPAD_DOWN, AndroidKey.KEYCODE_INFO -> Unit
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
            Text("Loading…", color = Palette.Text.copy(alpha = 0.8f), fontSize = 20.sp, modifier = Modifier.align(Alignment.Center))
        }

        AnimatedVisibility(
            visible = barVisible || paused || err != null,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.92f))))
                    .padding(start = 48.dp, end = 48.dp, top = 80.dp, bottom = 32.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.displayName + (state.vod.playingEpisode?.let { ep ->
                            listOfNotNull(state.vod.playingSeason?.number?.takeIf { it > 0 }?.let { "S$it" }, "E${ep.number}").joinToString("") +
                                (ep.name.takeIf { n -> !n.startsWith("Episode") }?.let { "  $it" } ?: "")
                        }?.let { "  ·  $it" } ?: ""),
                        color = Palette.Text, fontSize = 26.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (paused) Text("Paused", color = Palette.Accent, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(14.dp))
                ProgressLine(if (duration > 0) position.toFloat() / duration else 0f)
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(formatTime(position), color = Palette.Muted, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    if (duration > 0) Text("-" + formatTime(duration - position), color = Palette.Muted, fontSize = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "OK Pause/Play    ◀ −10s    ▶ +30s (hold to go faster)" +
                        (if (state.vod.playingEpisode != null) "    CH+/CH− Next/previous episode" else "") + "    Back Close",
                    color = Palette.Muted, fontSize = 13.sp,
                )
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
