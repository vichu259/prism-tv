package tv.prism.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.TimeZone

/**
 * Stalker / Ministra portal client: the protocol MAG boxes and STB Emu speak.
 * Flow: handshake -> bearer token -> get_profile -> itv calls. Tokens expire, so every
 * call re-handshakes once on an auth failure.
 */
class StalkerProvider(private val source: Source.Stalker, private val http: OkHttpClient) : Provider {
    private val mac = source.mac.trim().uppercase()
    private val profile = StbProfile.of(source.model)
    private val portalUrl = normalizeBase(source.portal) + "/"
    private val lock = Mutex()
    private var endpoint: String? = null
    private var token: String? = null

    private val baseHeaders: Map<String, String>
        get() = buildMap {
            put("User-Agent", profile.userAgent)
            put("X-User-Agent", "Model: ${profile.model}; Link: WiFi")
            put("Referer", portalUrl)
            put("Cookie", "mac=$mac; stb_lang=en; timezone=${TimeZone.getDefault().id}")
            token?.let { put("Authorization", "Bearer $it") }
        }

    private suspend fun ensureSession() = lock.withLock {
        if (token != null) return@withLock
        var lastError: Exception? = null
        for (candidate in StalkerEndpoints.candidates(source.portal)) {
            try {
                val js = request(candidate, mapOf("type" to "stb", "action" to "handshake", "token" to ""))
                val t = (js as? JSONObject)?.optString("token").orEmpty()
                if (t.isNotEmpty()) {
                    endpoint = candidate
                    token = t
                    activateProfile()
                    return@withLock
                }
            } catch (e: RateLimited) {
                throw e
            } catch (e: PortalRefused) {
                // The portal answered and said no: trying other endpoints won't change its mind.
                token = null
                throw e
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw IOException("Couldn't reach the portal" + (lastError?.message?.let { " ($it)" } ?: ""))
    }

    private suspend fun activateProfile() {
        val profile = request(
            endpoint!!,
            mapOf(
                "type" to "stb", "action" to "get_profile", "hd" to "1", "ver" to profile.ver,
                "num_banks" to "2", "sn" to serial(), "stb_type" to profile.model, "client_type" to "STB",
                "image_version" to profile.imageVersion, "video_out" to "hdmi", "device_id" to source.deviceId.trim(),
                "device_id2" to source.deviceId.trim(), "signature" to "", "auth_second_step" to "1",
                "hw_version" to profile.hwVersion, "not_valid_token" to "0",
            ),
        ) as? JSONObject
        val blocked = profile?.optString("block_msg").orEmpty()
        if (blocked.isNotBlank()) throw PortalRefused("Portal says: " + plain(blocked))
    }

    /** Raw portal call; returns the `js` payload. */
    private suspend fun request(url: String, params: Map<String, String>): Any? {
        val full = url.toHttpUrl().newBuilder()
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .addQueryParameter("JsHttpRequest", "1-xml")
            .build().toString()
        val body = Http.get(http, full, baseHeaders)
        if (body.contains("Authorization failed", ignoreCase = true)) throw AuthExpired()
        return JSONObject(body).opt("js")
    }

    private suspend fun call(params: Map<String, String>): Any? {
        ensureSession()
        return try {
            request(endpoint!!, params)
        } catch (e: AuthExpired) {
            lock.withLock { token = null }
            ensureSession()
            request(endpoint!!, params)
        }
    }

    override suspend fun load(): Lineup {
        val genres = call(mapOf("type" to "itv", "action" to "get_genres")) as? JSONArray ?: JSONArray()
        val categories = (0 until genres.length())
            .map { genres.getJSONObject(it) }
            .filter { it.optString("id") != "*" }
            .map { Category(it.optString("id"), it.optString("title").ifBlank { "Genre" }.replaceFirstChar(Char::uppercase)) }

        val js = call(mapOf("type" to "itv", "action" to "get_all_channels"))
        val data = (js as? JSONObject)?.optJSONArray("data") ?: (js as? JSONArray) ?: JSONArray()
        val channels = (0 until data.length()).map {
            val c = data.getJSONObject(it)
            Channel(
                id = c.optString("id"),
                number = c.optString("number").toIntOrNull() ?: (it + 1),
                name = c.optString("name").trim(),
                logo = c.optString("logo").takeIf { u -> u.isNotBlank() }?.let(::absoluteLogo),
                categoryId = c.optString("tv_genre_id"),
                ref = c.optString("cmd"),
            )
        }
        return Lineup(categories, channels)
    }

    override suspend fun resolve(channel: Channel): Stream {
        val direct = StalkerEndpoints.stripCmd(channel.ref)
        val linked = runCatching {
            val js = call(mapOf("type" to "itv", "action" to "create_link", "cmd" to channel.ref, "forced_storage" to "undefined", "disable_ad" to "0"))
            StalkerEndpoints.stripCmd((js as? JSONObject)?.optString("cmd").orEmpty())
        }.getOrNull()?.takeIf { it.startsWith("http") || it.startsWith("rtmp") || it.startsWith("udp") }
        val url = linked ?: direct.takeIf { it.isNotBlank() } ?: throw IOException("Portal returned no stream for ${channel.name}")
        return Stream(url, headers = mapOf("User-Agent" to profile.userAgent))
    }

    override suspend fun nowNext(channel: Channel): List<Programme> {
        val js = call(mapOf("type" to "itv", "action" to "get_short_epg", "ch_id" to channel.id, "size" to "2"))
        val arr = (js as? JSONArray) ?: (js as? JSONObject)?.optJSONArray("data") ?: return emptyList()
        return (0 until arr.length()).map {
            val p = arr.getJSONObject(it)
            Programme(p.optString("name").trim(), p.optLong("start_timestamp") * 1000, p.optLong("stop_timestamp") * 1000)
        }
    }

    // --- Video Club ----------------------------------------------------------------------

    override val hasVod get() = true

    override suspend fun vodCategories(): List<Category> {
        val arr = call(mapOf("type" to "vod", "action" to "get_categories")) as? JSONArray ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            val id = it.optString("id")
            Category(id, if (id == "*") "All" else it.optString("title").ifBlank { "Category" })
        }
    }

    override suspend fun vodPage(categoryId: String, page: Int, query: String?): VodPage {
        val params = buildMap {
            put("type", "vod"); put("action", "get_ordered_list"); put("p", page.toString())
            put("genre", "*"); put("sortby", "added"); put("fav", "0")
            put("category", if (query.isNullOrBlank()) categoryId else "*")
            if (!query.isNullOrBlank()) put("search", query)
        }
        val js = call(params) as? JSONObject ?: return VodPage(emptyList(), page, page)
        val data = js.optJSONArray("data") ?: JSONArray()
        val total = js.optInt("total_items", data.length())
        val perPage = js.optInt("max_page_items", data.length().coerceAtLeast(1)).coerceAtLeast(1)
        val items = (0 until data.length()).map { data.getJSONObject(it) }.map { v ->
            val series = v.optJSONArray("series")
            VodItem(
                id = v.optString("id"),
                name = v.optString("name").trim(),
                poster = v.optString("screenshot_uri").takeIf { it.isNotBlank() }?.let(::absoluteLogo),
                year = v.optString("year").takeIf { it != "N/A" && it != "0" }.orEmpty(),
                rating = v.optString("rating_imdb").takeIf { it.isNotBlank() && it != "N/A" && it != "0" }.orEmpty(),
                description = v.optString("description").trim(),
                durationMin = v.optString("time").toIntOrNull() ?: 0,
                ref = v.optString("cmd"),
                episodes = if (series == null) emptyList() else (0 until series.length()).map { series.optInt(it) },
                addedMs = AddedDate.parse(v.optString("added")),
            )
        }
        return VodPage(items, page, ((total + perPage - 1) / perPage).coerceAtLeast(1))
    }

    override suspend fun resolveVod(item: VodItem, episode: Int?): Stream {
        // Older portals play "/media/<id>.mpg" directly. Newer Ministra ones answer that with
        // "nothing_to_play": the title's files have to be listed first and the link made for a file.
        val link = createVodLink(item.ref, episode)
            // Files get a temporary link by file id ("/media/file_<id>.mpg"), the way the portal's own
            // player asks; the file's raw URL is the last resort (some servers accept it directly).
            ?: firstFile(item, episode)?.let { linkForFile(it) }
            ?: throw IOException("Portal returned no stream for ${item.name}")
        return Stream(link.url, headers = mapOf("User-Agent" to profile.userAgent), subtitles = link.subtitles)
    }

    /** A playable link plus the subtitle files the portal attached to it. */
    private data class Link(val url: String, val subtitles: List<SubtitleFile> = emptyList())

    private suspend fun createVodLink(cmd: String, episode: Int?): Link? {
        val js = call(
            mapOf(
                "type" to "vod", "action" to "create_link", "cmd" to cmd,
                "series" to (episode?.toString() ?: ""), "forced_storage" to "undefined", "disable_ad" to "0",
                "download" to "0", "force_ch_link_check" to "0",
            ),
        ) as? JSONObject
        val url = StalkerEndpoints.stripCmd(js?.optString("cmd").orEmpty()).takeIf { it.startsWith("http") } ?: return null
        return Link(url, parseSubtitles(js?.optJSONArray("subtitles")))
    }

    /** Portals list subtitles as objects; field names vary ("file"/"url"/"src", "lang"/"language"). */
    private fun parseSubtitles(arr: JSONArray?): List<SubtitleFile> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull arr.optString(i).takeIf { it.isNotBlank() }?.let { SubtitleFile(absoluteLogo(it), null, null) }
            val file = listOf("file", "url", "src", "link").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } }
                ?: return@mapNotNull null
            SubtitleFile(
                url = absoluteLogo(file),
                language = listOf("lang", "language", "code").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } },
                label = listOf("label", "name", "title").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() } },
            )
        }
    }

    // Shows are stored as movie -> seasons -> episodes -> files, each level one get_ordered_list
    // call with movie_id / season_id / episode_id. Old-style shows instead carry a "series"
    // list of episode numbers on the title itself; both are mapped onto seasons/episodes here.

    override suspend fun vodSeasons(item: VodItem): List<VodSeason> {
        if (item.isSeries) return listOf(VodSeason(LEGACY, "Episodes", 1, item.episodes.size))
        return listLevel(item.id, "0", "0")
            .filter { it.optBoolean("is_season") }
            .map {
                VodSeason(
                    id = it.optString("id"),
                    name = it.optString("season_name").ifBlank { it.optString("name") },
                    number = it.optString("season_number").toIntOrNull() ?: 0,
                    episodeCount = it.optString("season_series").toIntOrNull() ?: 0,
                )
            }
            .sortedBy { it.number }
    }

    override suspend fun vodEpisodes(item: VodItem, season: VodSeason): List<VodEpisode> {
        if (season.id == LEGACY) return item.episodes.map { VodEpisode(it.toString(), "Episode $it", it) }
        return listLevel(item.id, season.id, "0")
            .filter { it.optBoolean("is_episode") || it.has("series_number") }
            .map {
                val n = it.optString("series_number").toIntOrNull() ?: 0
                VodEpisode(it.optString("id"), it.optString("name").ifBlank { "Episode $n" }, n)
            }
            .sortedBy { it.number }
    }

    override suspend fun resolveEpisode(item: VodItem, season: VodSeason, episode: VodEpisode): Stream {
        val link = if (season.id == LEGACY) {
            createVodLink(item.ref, episode.number)
        } else {
            val files = listLevel(item.id, season.id, episode.id, maxPages = 1)
            files.firstOrNull { it.optBoolean("is_file") || it.optString("cmd").isNotBlank() }
                // The portal resolves a file link to the title's first episode unless the episode
                // number rides along in "series", as a MAG box's own player sends it.
                ?.let { f -> linkForFile(f, episode.number) }
        } ?: throw IOException("Portal returned no stream for ${item.name} episode ${episode.number}")
        return Stream(link.url, headers = mapOf("User-Agent" to profile.userAgent), subtitles = link.subtitles)
    }

    private suspend fun linkForFile(f: JSONObject, episodeNumber: Int? = null): Link? =
        createVodLink("/media/file_${f.optString("id")}.mpg", episodeNumber)
            ?: f.optString("cmd").ifBlank { f.optString("url") }.let(StalkerEndpoints::stripCmd).takeIf { it.startsWith("http") }?.let { Link(it) }

    /** Every row of one level, following the portal's 14-per-page paging. */
    private suspend fun listLevel(movieId: String, seasonId: String, episodeId: String, maxPages: Int = 20): List<JSONObject> {
        val rows = ArrayList<JSONObject>()
        var page = 1
        while (page <= maxPages) {
            val js = call(
                mapOf(
                    "type" to "vod", "action" to "get_ordered_list", "movie_id" to movieId,
                    "season_id" to seasonId, "episode_id" to episodeId, "p" to page.toString(),
                    "genre" to "*", "category" to "*", "sortby" to "added", "fav" to "0",
                ),
            ) as? JSONObject ?: break
            val data = js.optJSONArray("data") ?: break
            (0 until data.length()).mapTo(rows) { data.getJSONObject(it) }
            val total = js.optString("total_items").toIntOrNull() ?: rows.size
            if (data.length() == 0 || rows.size >= total) break
            page++
        }
        return rows
    }

    /** First playable file of a movie (or of an episode, for series stored the new way). */
    private suspend fun firstFile(item: VodItem, episode: Int?): JSONObject? {
        val js = call(
            mapOf(
                "type" to "vod", "action" to "get_ordered_list", "movie_id" to item.id,
                "season_id" to "0", "episode_id" to (episode?.toString() ?: "0"), "p" to "1",
                "genre" to "*", "category" to "*", "sortby" to "added", "fav" to "0",
            ),
        ) as? JSONObject
        val data = js?.optJSONArray("data") ?: return null
        return (0 until data.length()).map { data.getJSONObject(it) }
            .firstOrNull { it.optBoolean("is_file") || it.optString("cmd").isNotBlank() }
    }

    private fun absoluteLogo(logo: String): String =
        if (logo.startsWith("http")) logo else portalUrl.toHttpUrl().resolve(logo)?.toString() ?: logo

    private fun serial() = source.serial.trim().ifBlank { md5(mac).uppercase().take(13) }

    private class AuthExpired : IOException("Session expired")
    private class PortalRefused(message: String) : IOException(message)

    /** Portal messages are HTML fragments ("…provider<br>to register…"). */
    private fun plain(html: String) =
        html.replace(Regex("(?i)<br\\s*/?>"), " ").replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val LEGACY = "__legacy"

        fun md5(s: String) = digest("MD5", s)
        fun digest(alg: String, s: String) =
            MessageDigest.getInstance(alg).digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** What a given MAG model reports about itself. The 250 is the classic; 4xx/5xx run the 2.20 firmware. */
data class StbProfile(val model: String, val userAgent: String, val ver: String, val imageVersion: String, val hwVersion: String) {
    companion object {
        val MODELS = listOf("MAG250", "MAG254", "MAG256", "MAG322", "MAG324", "MAG349", "MAG351", "MAG352", "MAG420", "MAG424", "MAG520", "MAG524")

        fun of(model: String): StbProfile {
            val m = model.trim().uppercase().ifBlank { "MAG250" }
            return if (m == "MAG250") StbProfile(
                model = m,
                userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
                ver = "ImageDescription: 0.2.18-r23-250; ImageDate: Thu Sep 13 11:31:16 EEST 2018; PORTAL version: 5.6.2; API Version: JS API version: 343",
                imageVersion = "218",
                hwVersion = "1.7-BD-00",
            ) else StbProfile(
                model = m,
                userAgent = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2116 Mobile Safari/533.3",
                ver = "ImageDescription: 2.20.02-pub-${m.removePrefix("MAG")}; ImageDate: Mon Jun 21 12:00:00 UTC 2021; PORTAL version: 5.6.2; API Version: JS API version: 343; STB API version: 146; Player Engine version: 0x58c",
                imageVersion = "220",
                hwVersion = "1.7-BD-00",
            )
        }
    }
}

/** Pure helpers, kept separate so they are unit-testable without Android. */
object StalkerEndpoints {
    /**
     * Users paste portal URLs in every shape: "host:8080", ".../c/", ".../stalker_portal/c/index.html".
     * Return the API endpoints worth trying, most likely first.
     */
    fun candidates(portal: String): List<String> {
        val base = normalizeBase(portal).removeSuffix("/index.html").trimEnd('/')
        val out = LinkedHashSet<String>()
        if (base.endsWith("/c")) {
            val root = base.removeSuffix("/c")
            out += "$root/server/load.php"
            out += "$root/portal.php"
        }
        if (base.endsWith(".php")) out += base
        out += "$base/portal.php"
        out += "$base/server/load.php"
        out += "$base/stalker_portal/server/load.php"
        return out.toList()
    }

    /** "ffmpeg http://x/y" -> "http://x/y"; Stalker prefixes stream commands with a player name. */
    fun stripCmd(cmd: String): String {
        val t = cmd.trim()
        val firstSpace = t.indexOf(' ')
        return if (firstSpace > 0 && !t.startsWith("http")) t.substring(firstSpace + 1).trim() else t
    }
}
