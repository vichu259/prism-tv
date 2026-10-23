package tv.prism.app.data

import org.json.JSONObject
import java.util.UUID

/** One way of reaching a provider. Stored as JSON in [SourceStore]. */
sealed class Source {
    abstract val id: String
    abstract val name: String

    data class M3u(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        val url: String,
    ) : Source()

    data class Xtream(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        val server: String,
        val username: String,
        val password: String,
    ) : Source()

    /** Stalker / Ministra middleware: what STB Emu and MAG boxes talk to. */
    data class Stalker(
        override val id: String = UUID.randomUUID().toString(),
        override val name: String,
        val portal: String,
        val mac: String,
        /** Blank = derive one from the MAC. Set it to match a box/STB Emu the portal already knows. */
        val serial: String = "",
        /** Blank = send none, which is what STB Emu does by default. */
        val deviceId: String = "",
        /** The MAG model to present as; portals check it against the device they registered. */
        val model: String = "MAG250",
    ) : Source()

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        when (this@Source) {
            is M3u -> { put("type", "m3u"); put("url", url) }
            is Xtream -> {
                put("type", "xtream"); put("server", server)
                put("username", username); put("password", password)
            }
            is Stalker -> {
                put("type", "stalker"); put("portal", portal); put("mac", mac)
                put("serial", serial); put("deviceId", deviceId); put("model", model)
            }
        }
    }

    companion object {
        fun fromJson(o: JSONObject): Source? = when (o.optString("type")) {
            "m3u" -> M3u(o.getString("id"), o.getString("name"), o.getString("url"))
            "xtream" -> Xtream(
                o.getString("id"), o.getString("name"), o.getString("server"),
                o.getString("username"), o.getString("password"),
            )
            "stalker" -> Stalker(
                o.getString("id"), o.getString("name"), o.getString("portal"), o.getString("mac"),
                o.optString("serial"), o.optString("deviceId"),
                o.optString("model").ifBlank { "MAG250" },
            )
            else -> null
        }
    }
}

data class Category(val id: String, val title: String)

data class Channel(
    /** Stable within a source; used for favorites and "last watched". */
    val id: String,
    val number: Int,
    val name: String,
    val logo: String?,
    val categoryId: String,
    /**
     * Whatever the provider needs to produce a playable URL: a direct URL for M3U,
     * a stream id for Xtream, the portal `cmd` for Stalker.
     */
    val ref: String,
)

data class Programme(val title: String, val startMs: Long, val endMs: Long) {
    fun progress(nowMs: Long): Float =
        if (endMs <= startMs) 0f else ((nowMs - startMs).toFloat() / (endMs - startMs)).coerceIn(0f, 1f)
}

/** A resolved stream: URL plus any headers the server insists on. */
data class Stream(val url: String, val headers: Map<String, String> = emptyMap(), val fallbackUrl: String? = null)

data class Lineup(val categories: List<Category>, val channels: List<Channel>)

/** A movie or show in the provider's video library ("Video Club" in STB Emu). */
data class VodItem(
    val id: String,
    val name: String,
    val poster: String?,
    val year: String = "",
    val rating: String = "",
    val description: String = "",
    val durationMin: Int = 0,
    /** Provider-specific play reference (Stalker cmd, Xtream "id.ext"). */
    val ref: String,
    /** Episode numbers for series; empty for a movie. */
    val episodes: List<Int> = emptyList(),
    /** When the provider added it, epoch millis; 0 if unknown. */
    val addedMs: Long = 0,
) {
    val isSeries get() = episodes.isNotEmpty()

    /** "Star Maa : Bigg Boss" -> "Bigg Boss": portals often prefix the channel/network. */
    val displayName get() = TitleParts.split(name).second

    /** The network prefix, if any ("Star Maa"); shown as a small label. */
    val network get() = TitleParts.split(name).first
}

object TitleParts {
    private val separator = Regex("""\s+[:|]\s+""")

    /** (network, title); only splits when the prefix is short enough to be a channel name. */
    fun split(name: String): Pair<String?, String> {
        val m = separator.find(name) ?: return null to name
        val prefix = name.substring(0, m.range.first).trim()
        val rest = name.substring(m.range.last + 1).trim()
        return if (prefix.isNotEmpty() && rest.isNotEmpty() && prefix.length <= 24 && prefix.split(' ').size <= 3) prefix to rest else null to name
    }
}

data class VodPage(val items: List<VodItem>, val page: Int, val totalPages: Int) {
    val hasMore get() = page < totalPages
}

data class VodSeason(val id: String, val name: String, val number: Int, val episodeCount: Int = 0)

data class VodEpisode(val id: String, val name: String, val number: Int)

object AddedDate {
    private val portalFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)

    /** Stalker sends "2026-10-22 19:30:08"; Xtream sends unix seconds. */
    fun parse(raw: String?): Long {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s.startsWith("0000")) return 0
        s.toLongOrNull()?.let { return if (it < 100_000_000_000) it * 1000 else it }
        return runCatching { synchronized(portalFormat) { portalFormat.parse(s)?.time } }.getOrNull() ?: 0
    }

    /** "today", "yesterday", "3d ago", or "22 Oct 2026". */
    fun label(ms: Long, now: Long = System.currentTimeMillis()): String {
        if (ms <= 0) return ""
        val days = ((now - ms) / 86_400_000L).toInt()
        return when {
            days <= 0 -> "today"
            days == 1 -> "yesterday"
            days < 7 -> "${days}d ago"
            else -> java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ms))
        }
    }

    fun isNew(ms: Long, now: Long = System.currentTimeMillis()) = ms > 0 && now - ms < 7 * 86_400_000L
}
