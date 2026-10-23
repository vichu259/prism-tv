package tv.prism.app.data

import android.content.Context
import org.json.JSONArray

/** Small persistent state: sources, the active one, favorites and the last channel watched. */
class SourceStore(context: Context) {
    private val prefs = context.getSharedPreferences("prism", Context.MODE_PRIVATE)

    fun sources(): List<Source> {
        val raw = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { Source.fromJson(arr.getJSONObject(it)) }
    }

    fun saveSource(source: Source) {
        val list = sources().filterNot { it.id == source.id } + source
        writeSources(list)
        activeSourceId = source.id
    }

    fun removeSource(id: String) {
        writeSources(sources().filterNot { it.id == id })
        if (activeSourceId == id) activeSourceId = sources().firstOrNull()?.id
        prefs.edit().remove(favKey(id)).remove(lastKey(id)).remove("history_$id").remove("vodfav_$id").apply()
    }

    private fun writeSources(list: List<Source>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        prefs.edit().putString(KEY_SOURCES, arr.toString()).apply()
    }

    var activeSourceId: String?
        get() = prefs.getString(KEY_ACTIVE, null)
        set(value) = prefs.edit().putString(KEY_ACTIVE, value).apply()

    fun activeSource(): Source? = sources().let { all -> all.firstOrNull { it.id == activeSourceId } ?: all.firstOrNull() }

    fun favorites(sourceId: String): Set<String> = prefs.getStringSet(favKey(sourceId), emptySet())!!.toSet()

    fun setFavorites(sourceId: String, ids: Set<String>) {
        prefs.edit().putStringSet(favKey(sourceId), ids).apply()
    }

    fun lastChannel(sourceId: String): String? = prefs.getString(lastKey(sourceId), null)

    fun setLastChannel(sourceId: String, channelId: String) {
        prefs.edit().putString(lastKey(sourceId), channelId).apply()
    }

    /** Where a movie/episode was left off, so it can resume. 0 = from the start. */
    fun resumePosition(sourceId: String, key: String): Long = prefs.getLong("pos_${sourceId}_$key", 0)

    fun setResumePosition(sourceId: String, key: String, ms: Long) {
        prefs.edit().apply { if (ms > 0) putLong("pos_${sourceId}_$key", ms) else remove("pos_${sourceId}_$key") }.apply()
    }

    /** The season and episode last played for a show, as "seasonId|episodeId". */
    fun lastEpisode(sourceId: String, itemId: String): Pair<String, String>? =
        prefs.getString("lastep_${sourceId}_$itemId", null)?.split('|')?.takeIf { it.size == 2 }?.let { it[0] to it[1] }

    fun setLastEpisode(sourceId: String, itemId: String, seasonId: String, episodeId: String) {
        prefs.edit().putString("lastep_${sourceId}_$itemId", "$seasonId|$episodeId").apply()
    }

    fun history(sourceId: String): List<HistoryEntry> = HistoryList.decode(prefs.getString("history_$sourceId", null))

    fun setHistory(sourceId: String, list: List<HistoryEntry>) {
        prefs.edit().putString("history_$sourceId", HistoryList.encode(list)).apply()
    }

    /** Favorite movies/shows, stored as history-style entries (title snapshot + kind). */
    fun vodFavorites(sourceId: String): List<HistoryEntry> = HistoryList.decode(prefs.getString("vodfav_$sourceId", null))

    fun setVodFavorites(sourceId: String, list: List<HistoryEntry>) {
        prefs.edit().putString("vodfav_$sourceId", HistoryList.encode(list)).apply()
    }

    /** Display preferences, shared by all sources. */
    var vodListMode: Boolean
        get() = prefs.getBoolean("vod_list", false)
        set(v) = prefs.edit().putBoolean("vod_list", v).apply()

    var liveGridMode: Boolean
        get() = prefs.getBoolean("live_grid", false)
        set(v) = prefs.edit().putBoolean("live_grid", v).apply()

    private fun favKey(id: String) = "fav_$id"
    private fun lastKey(id: String) = "last_$id"

    private companion object {
        const val KEY_SOURCES = "sources"
        const val KEY_ACTIVE = "active"
    }
}
