package tv.prism.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.IOException

class M3uProvider(private val source: Source.M3u, private val http: OkHttpClient) : Provider {

    override suspend fun load(): Lineup = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(normalizeBase(source.url)).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} loading playlist")
            // Stream the body: playlists can run to tens of megabytes on a 1 GB TV.
            val reader = resp.body?.charStream()?.buffered() ?: throw IOException("Empty playlist")
            M3uParser.parse(reader)
        }
    }

    override suspend fun resolve(channel: Channel) = Stream(channel.ref)
}

object M3uParser {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")
    const val UNCATEGORISED = "Uncategorised"

    fun parse(reader: BufferedReader): Lineup {
        val channels = ArrayList<Channel>()
        val categories = LinkedHashMap<String, Category>()
        var pendingName: String? = null
        var pendingAttrs: Map<String, String> = emptyMap()
        var pendingGroup: String? = null

        reader.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    pendingAttrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    pendingName = extinfName(line)
                    pendingGroup = null
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
                line.isEmpty() || line.startsWith("#") -> Unit
                pendingName != null -> {
                    val group = (pendingAttrs["group-title"] ?: pendingGroup)?.takeIf { it.isNotBlank() } ?: UNCATEGORISED
                    categories.getOrPut(group) { Category(group, group) }
                    val name = pendingName!!.ifBlank { pendingAttrs["tvg-name"] ?: "Channel ${channels.size + 1}" }
                    channels += Channel(
                        id = pendingAttrs["tvg-id"]?.takeIf { it.isNotBlank() }?.let { "$it|$name" } ?: line,
                        number = pendingAttrs["tvg-chno"]?.toIntOrNull() ?: (channels.size + 1),
                        name = name,
                        logo = pendingAttrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        categoryId = group,
                        ref = line,
                    )
                    pendingName = null
                }
            }
        }
        return Lineup(categories.values.toList(), channels)
    }

    /** The title follows the first comma after the attribute list; titles may contain commas themselves. */
    internal fun extinfName(line: String): String {
        val attrsEnd = line.lastIndexOf('"').coerceAtLeast(line.indexOf(':'))
        val comma = line.indexOf(',', attrsEnd + 1)
        return if (comma < 0) "" else line.substring(comma + 1).trim()
    }
}
