package tv.prism.app.data

/** A language (or theme like Sports) and the portal categories under it. */
data class CategoryGroup(val id: String, val title: String, val items: List<GroupedCategory>) {
    val single get() = items.size == 1
}

/** A portal category with a short label for inside its group ("ENGLISH | NEWS" -> "News"). */
data class GroupedCategory(val category: Category, val label: String)

/**
 * Groups portal categories by language for a two-level rail. Portals name categories like
 * "ENGLISH | NEWS", "ENGLISH MOVIES | 4K", "TELUGU WEB SERIES", "SPORTS | CRICKET": a leading
 * language word wins; otherwise the part before "|" is the group. Order follows the portal.
 */
object CategoryGroups {
    private val languages = setOf(
        "ENGLISH", "TELUGU", "HINDI", "TAMIL", "KANNADA", "MALAYALAM", "MARATHI", "BENGALI", "BANGLA",
        "PUNJABI", "GUJARATI", "ODIA", "URDU", "NEPALI", "SINHALA", "ARABIC", "PERSIAN", "TURKISH",
        "SPANISH", "LATINO", "PORTUGUESE", "FRENCH", "GERMAN", "ITALIAN", "DUTCH", "POLISH", "RUSSIAN",
        "ROMANIAN", "GREEK", "ALBANIAN", "SERBIAN", "CROATIAN", "KOREAN", "CHINESE", "JAPANESE", "THAI",
        "VIETNAMESE", "FILIPINO", "INDONESIAN", "MALAY", "AFRICAN", "SOMALI", "AMHARIC", "SWAHILI",
    )

    private val acronyms = setOf(
        "USA", "US", "UK", "UAE", "CA", "AU", "NZ", "EU", "TV", "HD", "FHD", "UHD", "SD", "PPV", "VOD", "OTT", "VIP",
        "NBA", "NFL", "NHL", "MLB", "MLS", "UFC", "WWE", "EPL", "IPL", "BBC", "CNN", "HBO", "ESPN", "DAZN", "BT", "SKY",
    )

    fun group(categories: List<Category>): List<CategoryGroup> {
        val order = LinkedHashMap<String, MutableList<GroupedCategory>>()
        val titles = HashMap<String, String>()
        for (c in categories) {
            val (key, title, label) = classify(c.title)
            order.getOrPut(key) { mutableListOf() } += GroupedCategory(c, label)
            titles.putIfAbsent(key, title)
        }
        return order.map { (key, items) -> CategoryGroup("grp:$key", titles.getValue(key), items) }
    }

    /** (group key, group title, label within the group). */
    internal fun classify(raw: String): Triple<String, String, String> {
        val title = raw.trim()
        val firstWord = title.takeWhile { it.isLetter() }.uppercase()
        if (firstWord in languages) {
            val rest = title.drop(firstWord.length).trim().trimStart('|', ':', '-', '/').trim()
            return Triple(firstWord, pretty(firstWord), pretty(rest).ifBlank { "All" })
        }
        val bar = title.indexOf('|')
        if (bar > 0) {
            val head = title.substring(0, bar).trim()
            val rest = title.substring(bar + 1).trim()
            return Triple(head.uppercase(), pretty(head), pretty(rest).ifBlank { "All" })
        }
        // A standalone category ("SPORTS", "KIDS") is its own group; it may collect "SPORTS | ..." too.
        return Triple(title.uppercase(), pretty(title), "All")
    }

    /** "USA LOCALS | 4K" -> "USA Locals · 4K": title case, short codes and numbers stay as-is. */
    fun pretty(s: String): String =
        s.split('|').map { part ->
            part.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { w ->
                when {
                    w.any { it.isDigit() } || w.uppercase().trimEnd('+') in acronyms -> w.uppercase()
                    else -> w.lowercase().replaceFirstChar { it.uppercase() }
                }
            }
        }.filter { it.isNotEmpty() }.joinToString(" · ")
}
