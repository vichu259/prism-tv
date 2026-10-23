package tv.prism.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ParsersTest {
    @Test
    fun parsesM3uWithGroupsLogosAndNumbers() {
        val playlist = """
            #EXTM3U x-tvg-url="http://epg/x.xml"
            #EXTINF:-1 tvg-id="bbc1.uk" tvg-logo="http://logo/bbc1.png" tvg-chno="101" group-title="UK",BBC One
            http://stream/bbc1.m3u8
            #EXTINF:-1 group-title="News",Some News, Live
            #EXTVLCOPT:http-user-agent=VLC
            http://stream/news.ts

            #EXTINF:-1,No Group
            #EXTGRP:Misc
            http://stream/misc
        """.trimIndent()
        val lineup = M3uParser.parse(playlist.reader().buffered())

        assertEquals(listOf("UK", "News", "Misc"), lineup.categories.map { it.title })
        assertEquals(3, lineup.channels.size)
        val bbc = lineup.channels[0]
        assertEquals("BBC One", bbc.name)
        assertEquals(101, bbc.number)
        assertEquals("http://logo/bbc1.png", bbc.logo)
        assertEquals("http://stream/bbc1.m3u8", bbc.ref)
        // Commas inside a title survive.
        assertEquals("Some News, Live", lineup.channels[1].name)
        assertNull(lineup.channels[1].logo)
        assertEquals("Misc", lineup.channels[2].categoryId)
    }

    @Test
    fun stalkerEndpointCandidatesHandleCommonPortalShapes() {
        assertEquals(
            "http://host:8080/stalker_portal/server/load.php",
            StalkerEndpoints.candidates("http://host:8080/stalker_portal/c/").first(),
        )
        assertEquals("http://host/server/load.php", StalkerEndpoints.candidates("host/c/index.html").first())
        assertEquals("http://host:80/portal.php", StalkerEndpoints.candidates("host:80").first())
    }

    @Test
    fun stripsPlayerPrefixFromStalkerCmd() {
        assertEquals("http://x/y.ts", StalkerEndpoints.stripCmd("ffmpeg http://x/y.ts"))
        assertEquals("http://x/y.ts", StalkerEndpoints.stripCmd("ffrt http://x/y.ts"))
        assertEquals("http://x/y.ts", StalkerEndpoints.stripCmd("http://x/y.ts"))
    }

    @Test
    fun sourceRoundTripsThroughJson() {
        val s = Source.Stalker(name = "Home", portal = "http://p/c/", mac = "00:1A:79:AA:BB:CC", serial = "000000N000000", model = "MAG424")
        assertEquals(s, Source.fromJson(s.toJson()))
        // Sources saved before serial/deviceId existed still load.
        val old = s.toJson().apply { remove("serial"); remove("deviceId"); remove("model") }
        assertEquals(s.copy(serial = "", model = "MAG250"), Source.fromJson(old))
    }

    @Test
    fun stbProfileReportsChosenModel() {
        val p = StbProfile.of("mag424")
        assertEquals("MAG424", p.model)
        assertEquals("220", p.imageVersion)
        assertEquals("218", StbProfile.of("").imageVersion)
    }

    @Test
    fun lineupSurvivesCacheRoundTrip() {
        val l = Lineup(
            listOf(Category("1", "News")),
            listOf(Channel("10", 101, "BBC, One", null, "1", "ffmpeg http://x/1"), Channel("11", 102, "CNN", "http://l/c.png", "1", "r")),
        )
        assertEquals(l, LineupJson.decode(LineupJson.encode(l)))
    }

    @Test
    fun historyKeepsOneEntryPerTitleMostRecentFirst() {
        val show = VodItem("1", "Bigg Boss", "http://p/1.jpg", ref = "/media/1.mpg")
        val a = HistoryEntry(HistoryEntry.vodKey("1"), 1, show, VodSeason("s4", "Season 4", 4), VodEpisode("e1", "Ep 1", 1), 60_000, 3_600_000)
        val b = HistoryEntry(HistoryEntry.liveKey("ch9"), 2, channelId = "ch9")
        val c = a.copy(updatedAt = 3, episode = VodEpisode("e2", "Ep 2", 2))
        val list = HistoryList.upsert(HistoryList.upsert(HistoryList.upsert(emptyList(), a), b), c)
        assertEquals(listOf(c.key, b.key), list.map { it.key })
        assertEquals("e2", list.first().episode?.id)
        assertEquals(list, HistoryList.decode(HistoryList.encode(list)))
    }

    @Test
    fun splitsNetworkPrefixFromTitles() {
        assertEquals("Star Maa" to "Bigg Boss", TitleParts.split("Star Maa : Bigg Boss"))
        assertEquals(null to "Mission: Impossible", TitleParts.split("Mission: Impossible"))
        assertEquals(null to "The Lord of the Rings : The Return of the King",
            TitleParts.split("The Lord of the Rings : The Return of the King"))
        assertEquals("Zee5" to "Show", TitleParts.split("Zee5 | Show"))
    }

    @Test
    fun classifiesTitlesForHomeRows() {
        assertEquals(HistoryEntry.TV_SHOW, HistoryEntry.kindFor("ENGLISH | TV SHOWS", true))
        assertEquals(HistoryEntry.SERIES, HistoryEntry.kindFor("TELUGU | WEB SERIES", true))
        assertEquals(HistoryEntry.SERIES, HistoryEntry.kindFor("ENGLISH | K-DRAMAS", true))
        assertEquals(HistoryEntry.MOVIE, HistoryEntry.kindFor("ENGLISH MOVIES | 4K", false))
        assertEquals(HistoryEntry.SERIES, HistoryEntry.kindFor(null, true))
    }

    @Test
    fun groupsCategoriesByLanguage() {
        val cats = listOf("ENGLISH | NEWS", "TELUGU | MOVIES", "ENGLISH | USA LOCALS", "SPORTS", "SPORTS | CRICKET", "ENGLISH MOVIES | 4K", "TELUGU WEB SERIES", "KIDS")
            .mapIndexed { i, t -> Category(i.toString(), t) }
        val groups = CategoryGroups.group(cats)
        assertEquals(listOf("English", "Telugu", "Sports", "Kids"), groups.map { it.title })
        assertEquals(listOf("News", "USA Locals", "Movies · 4K"), groups[0].items.map { it.label })
        assertEquals(listOf("Movies", "Web Series"), groups[1].items.map { it.label })
        assertEquals(listOf("All", "Cricket"), groups[2].items.map { it.label })
        assertEquals(true, groups[3].single)
    }

    @Test
    fun parsesAndLabelsAddedDates() {
        assertEquals(1_700_000_000_000L, AddedDate.parse("1700000000"))
        assertEquals(0L, AddedDate.parse("0000-00-00 00:00:00"))
        val now = AddedDate.parse("2026-10-22 19:30:08")
        assertEquals("today", AddedDate.label(now, now + 3_600_000))
        assertEquals("3d ago", AddedDate.label(now, now + 3 * 86_400_000L))
        assertEquals(true, AddedDate.isNew(now, now + 86_400_000L))
    }
}
