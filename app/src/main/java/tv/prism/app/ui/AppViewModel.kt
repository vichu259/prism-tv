package tv.prism.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tv.prism.app.data.Category
import tv.prism.app.data.CategoryGroup
import tv.prism.app.data.CategoryGroups
import tv.prism.app.data.Channel
import tv.prism.app.data.HistoryEntry
import tv.prism.app.data.HistoryList
import tv.prism.app.data.Lineup
import tv.prism.app.data.LineupCache
import tv.prism.app.data.Programme
import tv.prism.app.data.Provider
import tv.prism.app.data.Source
import tv.prism.app.data.SourceStore
import tv.prism.app.data.VodEpisode
import tv.prism.app.data.VodItem
import tv.prism.app.data.VodSeason
import tv.prism.app.player.PlayerManager

enum class Screen { Setup, Home, Player, VodPlayer }

enum class Section { Home, Live, Vod }

/** Video Club browsing state. */
data class VodState(
    val categories: List<Category> = emptyList(),
    /** Categories grouped by language for the rail, and which groups are open. */
    val groups: List<CategoryGroup> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val categoryId: String? = null,
    val query: String = "",
    val items: List<VodItem> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
    /** The movie/show whose detail page is open, and its seasons once known (empty = movie). */
    val selected: VodItem? = null,
    /** The last title opened, so the grid can put focus back on it after the detail page closes. */
    val lastOpenedId: String? = null,
    /** Category the open title was picked from; decides Series vs TV shows on Home. */
    val openedCategory: String? = null,
    val detailLoading: Boolean = false,
    val seasons: List<VodSeason> = emptyList(),
    val season: VodSeason? = null,
    val episodes: List<VodEpisode> = emptyList(),
    val episodesLoading: Boolean = false,
    /** What's playing in the movie player; season/episode are null for a movie. */
    val playing: VodItem? = null,
    val playingSeason: VodSeason? = null,
    val playingEpisode: VodEpisode? = null,
    /** The season's episode list at the time playback started, for next/previous. */
    val playingEpisodes: List<VodEpisode> = emptyList(),
)

data class UiState(
    val screen: Screen = Screen.Setup,
    val sources: List<Source> = emptyList(),
    val active: Source? = null,
    val loading: Boolean = false,
    val loadError: String? = null,
    val lineup: Lineup = Lineup(emptyList(), emptyList()),
    val categories: List<Category> = emptyList(),
    /** Live categories grouped by language, and which groups are open in the rail. */
    val groups: List<CategoryGroup> = emptyList(),
    val expanded: Set<String> = emptySet(),
    val categoryId: String = ALL,
    val query: String = "",
    val channels: List<Channel> = emptyList(),
    val favorites: Set<String> = emptySet(),
    val focused: Channel? = null,
    val playing: Channel? = null,
    val epg: Map<String, List<Programme>> = emptyMap(),
    /** Digits typed on the remote while in the player, before the jump fires. */
    val numberEntry: String = "",
    val section: Section = Section.Home,
    /** Watch history for the Home page, most recent first. */
    val history: List<HistoryEntry> = emptyList(),
    /** Video Club as a list instead of a poster grid; Live TV as a channel grid instead of a list. */
    val vodListMode: Boolean = false,
    val liveGridMode: Boolean = false,
    /** Favorite movies/shows ("My list"). */
    val vodFavorites: List<HistoryEntry> = emptyList(),
    val hasVod: Boolean = false,
    val vod: VodState = VodState(),
) {
    companion object {
        const val ALL = "__all"
        const val FAVORITES = "__fav"
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SourceStore(app)
    private val cache = LineupCache(app)
    val player = PlayerManager(app)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Resolve-stage errors (portal refused the link); player errors come from [PlayerManager.error]. */
    val playError = MutableStateFlow<String?>(null)

    private var provider: Provider? = null
    private var previewJob: Job? = null
    private var numberJob: Job? = null
    private var vodJob: Job? = null
    private val epgFetchedAt = HashMap<String, Long>()

    init {
        val sources = store.sources()
        val active = store.activeSource()
        _ui.update {
            it.copy(
                sources = sources, active = active, screen = if (active == null) Screen.Setup else Screen.Home,
                vodListMode = store.vodListMode, liveGridMode = store.liveGridMode,
            )
        }
        active?.let(::loadSource)
    }

    // --- sources -------------------------------------------------------------------------

    fun addSource(source: Source) {
        store.saveSource(source)
        _ui.update { it.copy(sources = store.sources(), active = source, screen = Screen.Home) }
        loadSource(source)
    }

    fun switchSource(source: Source) {
        store.activeSourceId = source.id
        _ui.update { it.copy(active = source, screen = Screen.Home) }
        loadSource(source)
    }

    fun removeSource(source: Source) {
        store.removeSource(source.id)
        val next = store.activeSource()
        _ui.update { it.copy(sources = store.sources(), active = next) }
        if (next == null) {
            player.stop()
            _ui.update { UiState(screen = Screen.Setup) }
        } else loadSource(next)
    }

    fun showSetup() = _ui.update { it.copy(screen = Screen.Setup) }

    /** The ⟳ button: always go back to the portal for a fresh channel list. */
    fun reload() = _ui.value.active?.let { loadSource(it, force = true) }

    private fun loadSource(source: Source, force: Boolean = false) {
        player.stop()
        provider = Provider.of(source)
        epgFetchedAt.clear()
        _ui.update {
            it.copy(
                loading = true, loadError = null, lineup = Lineup(emptyList(), emptyList()),
                channels = emptyList(), focused = null, playing = null, epg = emptyMap(),
                favorites = store.favorites(source.id), query = "",
                section = Section.Home, hasVod = provider!!.hasVod, vod = VodState(),
                history = store.history(source.id),
                vodFavorites = store.vodFavorites(source.id),
            )
        }
        // Start from the copy on disk; only go to the portal when it's missing, stale or forced.
        val cached = if (force) null else cache.load(source.id)
        cached?.let { applyLineup(source, it.lineup, resume = true) }
        if (cached != null && cached.isFresh()) return

        val p = provider!!
        viewModelScope.launch {
            runCatching { p.load() }
                .onSuccess { lineup ->
                    cache.save(source.id, lineup)
                    if (provider === p) applyLineup(source, lineup, resume = cached == null)
                }
                .onFailure { e ->
                    // A failed background refresh keeps the cached list; only an empty screen shows the error.
                    if (provider === p && cached == null) _ui.update { it.copy(loading = false, loadError = e.message ?: "Couldn't load channels") }
                }
        }
    }

    private fun applyLineup(source: Source, lineup: Lineup, resume: Boolean) {
        val favs = store.favorites(source.id)
        // Drop empty categories: many portals list dozens with nothing in them.
        val used = lineup.channels.mapTo(HashSet()) { it.categoryId }
        val cats = lineup.categories.filter { it.id in used }
        _ui.update {
            val groups = CategoryGroups.group(cats)
            val keep = !resume && (it.categoryId == UiState.ALL || it.categoryId == UiState.FAVORITES ||
                cats.any { c -> c.id == it.categoryId } || groups.any { g -> g.id == it.categoryId })
            val start = if (keep) it.categoryId else if (favs.isNotEmpty()) UiState.FAVORITES else UiState.ALL
            it.copy(loading = false, loadError = null, lineup = lineup, categories = cats, groups = groups, categoryId = start)
        }
        refilter()
        if (resume) {
            // Resume where the viewer left off.
            store.lastChannel(source.id)
                ?.let { id -> lineup.channels.firstOrNull { it.id == id } }
                ?.let(::focus)
        }
    }

    // --- browsing ------------------------------------------------------------------------

    fun selectCategory(id: String) {
        if (_ui.value.categoryId == id) return
        _ui.update { it.copy(categoryId = id) }
        refilter()
    }

    /** OK on a language in the Live rail: open/close it; opening also shows all its channels. */
    fun toggleLiveGroup(group: CategoryGroup) {
        val open = group.id in _ui.value.expanded
        _ui.update { it.copy(expanded = if (open) it.expanded - group.id else it.expanded + group.id) }
        if (!open) selectCategory(if (group.single) group.items.first().category.id else group.id)
    }

    fun toggleVodListMode() {
        store.vodListMode = !_ui.value.vodListMode
        _ui.update { it.copy(vodListMode = !it.vodListMode) }
    }

    fun toggleLiveGridMode() {
        store.liveGridMode = !_ui.value.liveGridMode
        _ui.update { it.copy(liveGridMode = !it.liveGridMode) }
    }

    fun search(query: String) {
        _ui.update { it.copy(query = query) }
        refilter()
    }

    private fun refilter() = _ui.update { s ->
        val q = s.query.trim()
        val base = when (s.categoryId) {
            UiState.ALL -> s.lineup.channels
            UiState.FAVORITES -> s.lineup.channels.filter { it.id in s.favorites }
            else -> s.groups.firstOrNull { it.id == s.categoryId }
                // "All English": every channel in the language's categories.
                ?.let { g -> g.items.mapTo(HashSet()) { it.category.id } }
                ?.let { ids -> s.lineup.channels.filter { it.categoryId in ids } }
                ?: s.lineup.channels.filter { it.categoryId == s.categoryId }
        }
        s.copy(channels = if (q.isEmpty()) base else s.lineup.channels.filter { it.name.contains(q, ignoreCase = true) })
    }

    /** Focus moves preview after a short pause, so scrolling fast doesn't hammer the server. */
    fun focus(channel: Channel) {
        _ui.update { it.copy(focused = channel) }
        loadEpg(channel)
        previewJob?.cancel()
        if (_ui.value.playing?.id == channel.id) return
        // Previews only belong on Live TV; elsewhere (Home on startup, say) just remember the channel.
        if (_ui.value.section != Section.Live || _ui.value.screen != Screen.Home) return
        previewJob = viewModelScope.launch {
            delay(PREVIEW_DELAY_MS)
            tune(channel)
        }
    }

    fun toggleFavorite(channel: Channel) {
        val source = _ui.value.active ?: return
        val favs = _ui.value.favorites.toMutableSet().apply { if (!add(channel.id)) remove(channel.id) }
        store.setFavorites(source.id, favs)
        _ui.update { it.copy(favorites = favs) }
        if (_ui.value.categoryId == UiState.FAVORITES) refilter()
    }

    // --- playback ------------------------------------------------------------------------

    fun openPlayer(channel: Channel) {
        previewJob?.cancel()
        _ui.update { it.copy(focused = channel, screen = Screen.Player) }
        if (_ui.value.playing?.id != channel.id) viewModelScope.launch { tune(channel) }
        loadEpg(channel)
    }

    fun closePlayer() {
        // Only Live TV has a preview to keep showing the channel in; elsewhere, stop streaming.
        if (_ui.value.section != Section.Live) {
            player.stop()
            _ui.update { it.copy(playing = null) }
        }
        _ui.update { it.copy(screen = Screen.Home, numberEntry = "") }
    }

    /** Channel up/down within whatever list the viewer is browsing. */
    fun zap(step: Int) {
        val s = _ui.value
        val list = s.channels.ifEmpty { s.lineup.channels }
        if (list.isEmpty()) return
        val idx = list.indexOfFirst { it.id == s.playing?.id }.coerceAtLeast(0)
        val next = list[(idx + step).mod(list.size)]
        _ui.update { it.copy(focused = next) }
        viewModelScope.launch { tune(next) }
        loadEpg(next)
    }

    fun typeDigit(d: Int) {
        val entry = (_ui.value.numberEntry + d).takeLast(4)
        _ui.update { it.copy(numberEntry = entry) }
        numberJob?.cancel()
        numberJob = viewModelScope.launch {
            delay(1_500)
            val ch = _ui.value.lineup.channels.firstOrNull { it.number == entry.toInt() }
            _ui.update { it.copy(numberEntry = "") }
            if (ch != null) {
                _ui.update { it.copy(focused = ch) }
                tune(ch)
                loadEpg(ch)
            }
        }
    }

    private suspend fun tune(channel: Channel) {
        val p = provider ?: return
        _ui.update { it.copy(playing = channel) }
        // Only fullscreen viewing counts as "watched"; browsing previews doesn't.
        if (_ui.value.screen == Screen.Player) recordHistory(HistoryEntry(HistoryEntry.liveKey(channel.id), System.currentTimeMillis(), channelId = channel.id))
        playError.value = null
        _ui.value.active?.let { store.setLastChannel(it.id, channel.id) }
        runCatching { p.resolve(channel) }
            .onSuccess { if (_ui.value.playing?.id == channel.id) player.play(it) }
            .onFailure { e ->
                if (_ui.value.playing?.id == channel.id) {
                    player.stop()
                    playError.value = e.message ?: "Couldn't open channel"
                }
            }
    }

    private fun loadEpg(channel: Channel) {
        val now = System.currentTimeMillis()
        val cached = _ui.value.epg[channel.id]
        val stale = cached == null || cached.firstOrNull()?.let { now > it.endMs } == true
        if (!stale && now - (epgFetchedAt[channel.id] ?: 0) < 10 * 60_000) return
        epgFetchedAt[channel.id] = now
        val p = provider ?: return
        viewModelScope.launch {
            val list = runCatching { p.nowNext(channel) }.getOrDefault(emptyList())
                .filter { it.endMs > now }
            _ui.update { it.copy(epg = it.epg + (channel.id to list)) }
        }
    }

    // --- sections ------------------------------------------------------------------------

    /** Keep a title's kind once known; otherwise derive it from where it was opened. */
    private fun kindOf(item: VodItem, hasEpisodes: Boolean): String {
        val known = (_ui.value.history + _ui.value.vodFavorites).firstOrNull { it.key == HistoryEntry.vodKey(item.id) }?.kind
        val v = _ui.value.vod
        val fromDetail = if (v.selected?.id == item.id || v.lastOpenedId == item.id) v.openedCategory else null
        return if (fromDetail == null && !known.isNullOrEmpty()) known
        else HistoryEntry.kindFor(fromDetail, hasEpisodes || item.isSeries || v.seasons.isNotEmpty() && v.selected?.id == item.id)
    }

    fun isVodFavorite(item: VodItem) = _ui.value.vodFavorites.any { it.key == HistoryEntry.vodKey(item.id) }

    /** Hold OK on a poster, or the ♥ button on a detail page. */
    fun toggleVodFavorite(item: VodItem) {
        val source = _ui.value.active ?: return
        val key = HistoryEntry.vodKey(item.id)
        val list = if (isVodFavorite(item)) _ui.value.vodFavorites.filterNot { it.key == key }
        else HistoryList.upsert(_ui.value.vodFavorites, HistoryEntry(key, System.currentTimeMillis(), item, kind = kindOf(item, false)))
        store.setVodFavorites(source.id, list)
        _ui.update { it.copy(vodFavorites = list) }
    }

    /** OK on a Favorites card: continue it if it was watched; otherwise play a movie, or open a show's episodes. */
    fun playFavorite(fav: HistoryEntry) {
        val watched = _ui.value.history.firstOrNull { it.key == fav.key }
        when {
            watched != null -> playFromHistory(watched)
            fav.kind == HistoryEntry.MOVIE && fav.vod != null -> playVod(fav.vod)
            else -> openFromHistory(fav)
        }
    }

    private fun recordHistory(entry: HistoryEntry) {
        val source = _ui.value.active ?: return
        val list = HistoryList.upsert(_ui.value.history, entry)
        store.setHistory(source.id, list)
        _ui.update { it.copy(history = list) }
    }

    fun removeFromHistory(entry: HistoryEntry) {
        val source = _ui.value.active ?: return
        val list = _ui.value.history.filterNot { it.key == entry.key }
        store.setHistory(source.id, list)
        _ui.update { it.copy(history = list) }
    }

    fun channelFor(entry: HistoryEntry): Channel? = entry.channelId?.let { id -> _ui.value.lineup.channels.firstOrNull { it.id == id } }

    /** OK on a Home card: resume the episode/movie, or tune the channel fullscreen. */
    fun playFromHistory(entry: HistoryEntry) {
        channelFor(entry)?.let { openPlayer(it); return }
        val item = entry.vod ?: return
        val season = entry.season
        val episode = entry.episode
        if (season != null && episode != null) {
            // Finished episode: the natural next thing is the following one, which the episode list will give us.
            startVod(item, season, episode, emptyList(), fromStart = entry.finished) { it.resolveEpisode(item, season, episode) }
        } else {
            startVod(item, null, null, emptyList(), fromStart = entry.finished) { it.resolveVod(item) }
        }
    }

    /** Hold OK on a Home card: open the title's detail page in Video Club. */
    fun openFromHistory(entry: HistoryEntry) {
        val item = entry.vod ?: return
        setSection(Section.Vod)
        openVod(item)
    }

    fun setSection(section: Section) {
        if (_ui.value.section == section) return
        previewJob?.cancel()
        // Each section owns the player: don't keep a live preview streaming behind the movie grid.
        player.stop()
        _ui.update { it.copy(section = section, playing = null) }
        if (section == Section.Vod && _ui.value.vod.categories.isEmpty()) loadVodCategories()
        // Arriving on Live TV: start the preview for the channel the list is on.
        if (section == Section.Live) _ui.value.focused?.let(::focus)
    }

    // --- Video Club ----------------------------------------------------------------------

    private fun updateVod(f: (VodState) -> VodState) = _ui.update { it.copy(vod = f(it.vod)) }

    private fun loadVodCategories() {
        val p = provider ?: return
        updateVod { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { p.vodCategories() }
                .onSuccess { cats ->
                    // "All" (id "*") stays a plain entry on top; everything else is grouped by language.
                    val groups = CategoryGroups.group(cats.filter { it.id != "*" })
                    updateVod { it.copy(categories = cats, groups = groups, loading = false) }
                    cats.firstOrNull()?.let { selectVodCategory(it.id) }
                }
                .onFailure { e -> updateVod { it.copy(loading = false, error = e.message ?: "Couldn't load the video library") } }
        }
    }

    fun selectVodCategory(id: String) {
        if (_ui.value.vod.categoryId == id && _ui.value.vod.query.isBlank() && _ui.value.vod.items.isNotEmpty()) return
        updateVod { it.copy(categoryId = id, query = "", items = emptyList(), page = 0, hasMore = true) }
        loadMoreVod()
    }

    /** OK on a language in the Video Club rail: open/close it, loading its first category on open. */
    fun toggleVodGroup(group: CategoryGroup) {
        val open = group.id in _ui.value.vod.expanded
        updateVod { it.copy(expanded = if (open) it.expanded - group.id else it.expanded + group.id) }
        if (!open) selectVodCategory(group.items.first().category.id)
    }

    fun searchVod(query: String) {
        updateVod { it.copy(query = query, items = emptyList(), page = 0, hasMore = true) }
        vodJob?.cancel()
        // Debounce: wait until typing pauses before asking the portal.
        vodJob = viewModelScope.launch { delay(600); fetchVodPage() }
    }

    /** Called as the grid nears its end. */
    fun loadMoreVod() {
        val v = _ui.value.vod
        if (v.loading || !v.hasMore) return
        vodJob?.cancel()
        vodJob = viewModelScope.launch { fetchVodPage() }
    }

    private suspend fun fetchVodPage() {
        val p = provider ?: return
        val v = _ui.value.vod
        val cat = v.categoryId ?: return
        val next = v.page + 1
        updateVod { it.copy(loading = true, error = null) }
        runCatching { p.vodPage(cat, next, v.query.takeIf { it.isNotBlank() }) }
            .onSuccess { page ->
                updateVod {
                    // Ignore a page that belongs to a category/search the viewer already left.
                    if (it.categoryId != cat || it.query != v.query) it.copy(loading = false)
                    else it.copy(items = it.items + page.items, page = next, hasMore = page.hasMore && page.items.isNotEmpty(), loading = false)
                }
            }
            .onFailure { e -> updateVod { it.copy(loading = false, hasMore = false, error = e.message ?: "Couldn't load titles") } }
    }

    private var detailJob: Job? = null

    /** Open a title's detail page; for shows, fetch the seasons and the first season's episodes. */
    fun openVod(item: VodItem) {
        val p = provider ?: return
        updateVod {
            val cat = if (it.query.isBlank()) it.categories.firstOrNull { c -> c.id == it.categoryId }?.title else null
            it.copy(selected = item, lastOpenedId = item.id, openedCategory = cat, detailLoading = true, seasons = emptyList(), season = null, episodes = emptyList())
        }
        detailJob?.cancel()
        detailJob = viewModelScope.launch {
            val seasons = runCatching { p.vodSeasons(item) }.getOrDefault(emptyList())
            updateVod { if (it.selected?.id != item.id) it else it.copy(detailLoading = false, seasons = seasons) }
            // Reopen on the season the viewer was last watching.
            val lastSeason = lastEpisodeOf(item)?.first
            (seasons.firstOrNull { it.id == lastSeason } ?: seasons.firstOrNull())?.let { selectSeason(it) }
        }
    }

    fun selectSeason(season: VodSeason) {
        val p = provider ?: return
        val item = _ui.value.vod.selected ?: return
        if (_ui.value.vod.season?.id == season.id && _ui.value.vod.episodes.isNotEmpty()) return
        updateVod { it.copy(season = season, episodes = emptyList(), episodesLoading = true) }
        viewModelScope.launch {
            val eps = runCatching { p.vodEpisodes(item, season) }.getOrDefault(emptyList())
            updateVod { if (it.season?.id != season.id) it else it.copy(episodes = eps, episodesLoading = false) }
        }
    }

    fun closeVod() {
        detailJob?.cancel()
        updateVod { it.copy(selected = null, seasons = emptyList(), season = null, episodes = emptyList()) }
    }

    /** (seasonId, episodeId) last played for a show. */
    fun lastEpisodeOf(item: VodItem): Pair<String, String>? = _ui.value.active?.let { store.lastEpisode(it.id, item.id) }

    fun resumeMs(item: VodItem, episode: VodEpisode? = null): Long =
        _ui.value.active?.let { store.resumePosition(it.id, resumeKey(item, episode)) } ?: 0

    /** Play a movie. */
    fun playVod(item: VodItem, fromStart: Boolean = false) =
        startVod(item, null, null, emptyList(), fromStart) { it.resolveVod(item) }

    /** Play an episode of the season currently shown on the detail page. */
    fun playEpisode(item: VodItem, season: VodSeason, episode: VodEpisode, fromStart: Boolean = false) {
        val list = _ui.value.vod.episodes.takeIf { _ui.value.vod.season?.id == season.id } ?: _ui.value.vod.playingEpisodes
        startVod(item, season, episode, list, fromStart) { it.resolveEpisode(item, season, episode) }
    }

    private fun startVod(
        item: VodItem, season: VodSeason?, episode: VodEpisode?, episodes: List<VodEpisode>, fromStart: Boolean,
        resolve: suspend (Provider) -> tv.prism.app.data.Stream,
    ) {
        val p = provider ?: return
        val start = if (fromStart) 0 else resumeMs(item, episode)
        val source = _ui.value.active
        if (source != null && season != null && episode != null) store.setLastEpisode(source.id, item.id, season.id, episode.id)
        updateVod { it.copy(playing = item, playingSeason = season, playingEpisode = episode, playingEpisodes = episodes) }
        val previous = _ui.value.history.firstOrNull { it.key == HistoryEntry.vodKey(item.id) }
        recordHistory(
            HistoryEntry(
                HistoryEntry.vodKey(item.id), System.currentTimeMillis(), item, season, episode, start,
                // Same episode/movie as before: keep its known duration so the progress bar stays right.
                durationMs = previous?.takeIf { it.episode?.id == episode?.id }?.durationMs ?: 0,
                kind = kindOf(item, season != null),
            ),
        )
        // Started from Home there's no episode list yet; fetch it so CH+/CH− work.
        if (season != null && episodes.isEmpty()) viewModelScope.launch {
            val eps = runCatching { provider?.vodEpisodes(item, season) }.getOrNull().orEmpty()
            updateVod { if (it.playing?.id == item.id && it.playingSeason?.id == season.id) it.copy(playingEpisodes = eps) else it }
        }
        playError.value = null
        _ui.update { it.copy(screen = Screen.VodPlayer) }
        viewModelScope.launch {
            runCatching { resolve(p) }
                .onSuccess { player.play(it, start) }
                .onFailure { e -> playError.value = e.message ?: "Couldn't open ${item.name}" }
        }
    }

    /** Next/previous episode of the show that's playing; no-op for a movie or at either end. */
    fun playAdjacentEpisode(step: Int) {
        val v = _ui.value.vod
        val item = v.playing ?: return
        val season = v.playingSeason ?: return
        val idx = v.playingEpisodes.indexOfFirst { it.id == v.playingEpisode?.id }
        val next = v.playingEpisodes.getOrNull(idx + step) ?: return
        saveVodPosition()
        startVod(item, season, next, v.playingEpisodes, fromStart = false) { it.resolveEpisode(item, season, next) }
    }

    /** Remember where the viewer stopped; near the end counts as finished. */
    fun saveVodPosition() {
        val source = _ui.value.active ?: return
        val v = _ui.value.vod
        val item = v.playing ?: return
        val pos = player.player.currentPosition
        val dur = player.player.duration
        val finished = dur > 0 && pos > dur - 3 * 60_000
        store.setResumePosition(source.id, resumeKey(item, v.playingEpisode), if (finished || pos < 30_000) 0 else pos)
        if (dur > 0 || pos > 0) recordHistory(
            HistoryEntry(
                HistoryEntry.vodKey(item.id), System.currentTimeMillis(), item, v.playingSeason, v.playingEpisode,
                positionMs = if (finished) dur else pos, durationMs = dur.coerceAtLeast(0), finished = finished,
                kind = kindOf(item, v.playingSeason != null),
            ),
        )
    }

    fun closeVodPlayer() {
        saveVodPosition()
        player.stop()
        updateVod { it.copy(playing = null, playingEpisode = null, playingSeason = null) }
        _ui.update { it.copy(screen = Screen.Home) }
    }

    private fun resumeKey(item: VodItem, episode: VodEpisode?) = "vod_${item.id}" + (episode?.let { "_e${it.id}" } ?: "")

    override fun onCleared() {
        player.release()
    }

    private companion object {
        const val PREVIEW_DELAY_MS = 650L
    }
}
