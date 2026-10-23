package tv.prism.app.data

import android.util.Base64
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class XtreamProvider(private val source: Source.Xtream, private val http: OkHttpClient) : Provider {
    private val base = normalizeBase(source.server)

    private fun api(action: String?, vararg extra: Pair<String, String>): String =
        "$base/player_api.php".toHttpUrl().newBuilder()
            .addQueryParameter("username", source.username)
            .addQueryParameter("password", source.password)
            .apply { if (action != null) addQueryParameter("action", action) }
            .apply { extra.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build().toString()

    override suspend fun load(): Lineup {
        // A bad login returns {"user_info":{"auth":0}} rather than an HTTP error.
        val info = JSONObject(Http.get(http, api(null)))
        if (info.optJSONObject("user_info")?.optInt("auth", 1) == 0) throw IOException("Login rejected: check username and password")

        val cats = JSONArray(Http.get(http, api("get_live_categories")))
        val categories = (0 until cats.length()).map {
            val c = cats.getJSONObject(it)
            Category(c.getString("category_id"), c.optString("category_name").ifBlank { "Category" })
        }
        val streams = JSONArray(Http.get(http, api("get_live_streams")))
        val channels = (0 until streams.length()).map {
            val s = streams.getJSONObject(it)
            val id = s.get("stream_id").toString()
            Channel(
                id = id,
                number = s.optInt("num", it + 1),
                name = s.optString("name").trim(),
                logo = s.optString("stream_icon").takeIf { u -> u.startsWith("http") },
                categoryId = s.optString("category_id"),
                ref = id,
            )
        }
        return Lineup(categories, channels)
    }

    override suspend fun resolve(channel: Channel): Stream {
        val path = "$base/live/${source.username}/${source.password}/${channel.ref}"
        // HLS starts faster and copes better with Wi-Fi; some panels only serve MPEG-TS.
        return Stream(url = "$path.m3u8", fallbackUrl = "$path.ts")
    }

    override suspend fun nowNext(channel: Channel): List<Programme> {
        val body = JSONObject(Http.get(http, api("get_short_epg", "stream_id" to channel.ref, "limit" to "2")))
        val list = body.optJSONArray("epg_listings") ?: return emptyList()
        return (0 until list.length()).map {
            val p = list.getJSONObject(it)
            Programme(
                title = decode(p.optString("title")),
                startMs = p.optLong("start_timestamp") * 1000,
                endMs = p.optLong("stop_timestamp") * 1000,
            )
        }
    }

    // --- Video library (movies) -------------------------------------------------------------

    private val vodCache = HashMap<String, List<VodItem>>()

    override val hasVod get() = true

    override suspend fun vodCategories(): List<Category> {
        val cats = JSONArray(Http.get(http, api("get_vod_categories")))
        return listOf(Category(ALL, "All")) + (0 until cats.length()).map {
            val c = cats.getJSONObject(it)
            Category(c.getString("category_id"), c.optString("category_name").ifBlank { "Category" })
        }
    }

    override suspend fun vodPage(categoryId: String, page: Int, query: String?): VodPage {
        // Xtream returns a whole category at once; page it locally so the grid stays light.
        val key = if (query.isNullOrBlank()) categoryId else ALL
        val all = vodCache.getOrPut(key) {
            val extra = if (key == ALL) emptyArray() else arrayOf("category_id" to key)
            val arr = JSONArray(Http.get(http, api("get_vod_streams", *extra)))
            (0 until arr.length()).map { arr.getJSONObject(it) }.map { v ->
                VodItem(
                    id = v.get("stream_id").toString(),
                    name = v.optString("name").trim(),
                    poster = v.optString("stream_icon").takeIf { it.startsWith("http") },
                    rating = v.optString("rating").takeIf { it.isNotBlank() && it != "0" }.orEmpty(),
                    ref = v.get("stream_id").toString() + "." + v.optString("container_extension").ifBlank { "mp4" },
                    addedMs = AddedDate.parse(v.optString("added")),
                )
            }
        }
        val list = if (query.isNullOrBlank()) all else all.filter { it.name.contains(query, ignoreCase = true) }
        val pages = ((list.size + PAGE - 1) / PAGE).coerceAtLeast(1)
        return VodPage(list.drop((page - 1) * PAGE).take(PAGE), page, pages)
    }

    override suspend fun resolveVod(item: VodItem, episode: Int?) =
        Stream("$base/movie/${source.username}/${source.password}/${item.ref}")

    private companion object {
        const val ALL = "__all"
        const val PAGE = 60
    }

    private fun decode(b64: String): String =
        runCatching { String(Base64.decode(b64, Base64.DEFAULT)) }.getOrDefault(b64).trim()
}
