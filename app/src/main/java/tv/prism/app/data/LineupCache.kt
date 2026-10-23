package tv.prism.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The channel list on disk, per source. Portals rate-limit (HTTP 429) clients that re-download
 * thousands of channels on every launch, so start from this and refresh only when it's stale.
 */
class LineupCache(context: Context) {
    private val dir = File(context.filesDir, "lineups").apply { mkdirs() }

    data class Entry(val lineup: Lineup, val savedAtMs: Long) {
        fun isFresh(nowMs: Long = System.currentTimeMillis()) = nowMs - savedAtMs < MAX_AGE_MS
    }

    fun load(sourceId: String): Entry? = runCatching {
        val o = JSONObject(file(sourceId).readText())
        Entry(LineupJson.decode(o), o.getLong("savedAt"))
    }.getOrNull()

    fun save(sourceId: String, lineup: Lineup) {
        runCatching {
            val tmp = File(dir, "$sourceId.tmp")
            tmp.writeText(LineupJson.encode(lineup).put("savedAt", System.currentTimeMillis()).toString())
            tmp.renameTo(file(sourceId))
        }
    }

    fun clear(sourceId: String) {
        file(sourceId).delete()
    }

    private fun file(sourceId: String) = File(dir, "$sourceId.json")

    companion object {
        const val MAX_AGE_MS = 6 * 60 * 60 * 1000L
    }
}

/** Compact JSON for a lineup; kept free of Android types so it's unit-testable. */
object LineupJson {
    fun encode(lineup: Lineup): JSONObject = JSONObject().apply {
        put("categories", JSONArray().apply {
            lineup.categories.forEach { put(JSONArray().put(it.id).put(it.title)) }
        })
        put("channels", JSONArray().apply {
            lineup.channels.forEach { c ->
                put(JSONArray().put(c.id).put(c.number).put(c.name).put(c.logo ?: "").put(c.categoryId).put(c.ref))
            }
        })
    }

    fun decode(o: JSONObject): Lineup {
        val cats = o.getJSONArray("categories")
        val chans = o.getJSONArray("channels")
        return Lineup(
            categories = (0 until cats.length()).map { cats.getJSONArray(it).let { a -> Category(a.getString(0), a.getString(1)) } },
            channels = (0 until chans.length()).map {
                val a = chans.getJSONArray(it)
                Channel(a.getString(0), a.getInt(1), a.getString(2), a.getString(3).ifBlank { null }, a.getString(4), a.getString(5))
            },
        )
    }
}
