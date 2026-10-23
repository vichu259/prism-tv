package tv.prism.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Everything the UI needs from a source, regardless of protocol. */
interface Provider {
    suspend fun load(): Lineup
    suspend fun resolve(channel: Channel): Stream
    /** Now and next, or empty when the source has no guide. */
    suspend fun nowNext(channel: Channel): List<Programme> = emptyList()

    /** Whether this source has a video library (movies / shows) at all. */
    val hasVod: Boolean get() = false
    suspend fun vodCategories(): List<Category> = emptyList()
    /** One page (1-based) of a category, or of a search across the library when [query] is set. */
    suspend fun vodPage(categoryId: String, page: Int, query: String? = null): VodPage = VodPage(emptyList(), 1, 1)
    suspend fun resolveVod(item: VodItem, episode: Int? = null): Stream = throw UnsupportedOperationException()
    /** Seasons of a show; empty means the title is a movie. */
    suspend fun vodSeasons(item: VodItem): List<VodSeason> = emptyList()
    suspend fun vodEpisodes(item: VodItem, season: VodSeason): List<VodEpisode> = emptyList()
    suspend fun resolveEpisode(item: VodItem, season: VodSeason, episode: VodEpisode): Stream = throw UnsupportedOperationException()

    companion object {
        fun of(source: Source, http: OkHttpClient = Http.client): Provider = when (source) {
            is Source.M3u -> M3uProvider(source, http)
            is Source.Xtream -> XtreamProvider(source, http)
            is Source.Stalker -> StalkerProvider(source, http)
        }
    }
}

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun get(client: OkHttpClient, url: String, headers: Map<String, String> = emptyMap()): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
            client.newCall(req).execute().use { resp ->
                if (resp.code == 429) throw RateLimited()
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} from ${req.url.host}")
                resp.body?.string() ?: throw IOException("Empty response from ${req.url.host}")
            }
        }
}

/** HTTP 429: the provider wants fewer requests. Never retry automatically; it only makes it worse. */
class RateLimited : IOException("The portal is limiting requests right now. Wait a few minutes, then press Try again.")

/** Turns "example.com:8080" or "http://example.com:8080/" into "http://example.com:8080". */
internal fun normalizeBase(raw: String): String {
    var s = raw.trim().trimEnd('/')
    if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
    return s
}
