package tv.prism.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * One thing the viewer watched, for the Home page. A show keeps a single entry that tracks its
 * latest episode; a channel keeps one entry per channel.
 */
data class HistoryEntry(
    val key: String,
    val updatedAt: Long,
    val vod: VodItem? = null,
    val season: VodSeason? = null,
    val episode: VodEpisode? = null,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val finished: Boolean = false,
    val channelId: String? = null,
    /** MOVIE, SERIES or TV_SHOW, for the Home rows. */
    val kind: String = "",
) {
    val isLive get() = channelId != null
    val isShow get() = episode != null
    val inProgress get() = vod != null && !finished && positionMs > 0
    val progress get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

    companion object {
        fun vodKey(itemId: String) = "vod:$itemId"
        fun liveKey(channelId: String) = "live:$channelId"

        const val MOVIE = "movie"
        const val SERIES = "series"
        const val TV_SHOW = "tvshow"

        /**
         * Which Home row a title belongs in, from the Video Club category it was opened from
         * ("ENGLISH | TV SHOWS", "TELUGU WEB SERIES", ...). Without a category, seasons mean series.
         */
        fun kindFor(categoryTitle: String?, hasEpisodes: Boolean): String {
            val c = categoryTitle.orEmpty().uppercase()
            return when {
                "TV SHOW" in c || "REALITY" in c || "SHOWS" in c -> TV_SHOW
                "SERIES" in c || "SERIAL" in c || "DRAMA" in c -> SERIES
                hasEpisodes -> SERIES
                else -> MOVIE
            }
        }
    }
}

/** Most-recent-first list with a cap; pure so it can be unit-tested. */
object HistoryList {
    const val MAX = 40

    fun upsert(list: List<HistoryEntry>, entry: HistoryEntry): List<HistoryEntry> =
        (listOf(entry) + list.filterNot { it.key == entry.key }).take(MAX)

    fun encode(list: List<HistoryEntry>): String = JSONArray().apply { list.forEach { put(entryJson(it)) } }.toString()

    fun decode(raw: String?): List<HistoryEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { parseEntry(arr.getJSONObject(it)) }.getOrNull() }
    }

    private fun entryJson(e: HistoryEntry) = JSONObject().apply {
        put("key", e.key); put("at", e.updatedAt); put("pos", e.positionMs); put("dur", e.durationMs); put("done", e.finished)
        e.channelId?.let { put("channel", it) }
        if (e.kind.isNotEmpty()) put("kind", e.kind)
        e.vod?.let { v ->
            put("vod", JSONObject().apply {
                put("id", v.id); put("name", v.name); put("poster", v.poster ?: ""); put("year", v.year)
                put("rating", v.rating); put("desc", v.description); put("min", v.durationMin); put("ref", v.ref)
                put("eps", JSONArray(v.episodes)); put("added", v.addedMs)
            })
        }
        e.season?.let { put("season", JSONObject().put("id", it.id).put("name", it.name).put("n", it.number)) }
        e.episode?.let { put("episode", JSONObject().put("id", it.id).put("name", it.name).put("n", it.number)) }
    }

    private fun parseEntry(o: JSONObject): HistoryEntry {
        val v = o.optJSONObject("vod")?.let {
            val eps = it.optJSONArray("eps")
            VodItem(
                id = it.getString("id"), name = it.getString("name"), poster = it.optString("poster").ifBlank { null },
                year = it.optString("year"), rating = it.optString("rating"), description = it.optString("desc"),
                durationMin = it.optInt("min"), ref = it.optString("ref"),
                episodes = if (eps == null) emptyList() else (0 until eps.length()).map { i -> eps.getInt(i) },
                addedMs = it.optLong("added"),
            )
        }
        return HistoryEntry(
            key = o.getString("key"),
            updatedAt = o.getLong("at"),
            vod = v,
            season = o.optJSONObject("season")?.let { VodSeason(it.getString("id"), it.getString("name"), it.optInt("n")) },
            episode = o.optJSONObject("episode")?.let { VodEpisode(it.getString("id"), it.getString("name"), it.optInt("n")) },
            positionMs = o.optLong("pos"),
            durationMs = o.optLong("dur"),
            finished = o.optBoolean("done"),
            channelId = o.optString("channel").ifBlank { null },
            kind = o.optString("kind"),
        )
    }
}
