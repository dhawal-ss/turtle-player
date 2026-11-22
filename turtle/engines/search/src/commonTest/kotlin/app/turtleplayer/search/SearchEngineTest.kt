package app.turtleplayer.search

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.core.VersionTag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchEngineTest {
    private val yellow = track("yellow", "Coldplay", "Yellow", "Parachutes", 2000)
    private val fixYou = track("fixyou", "Coldplay", "Fix You", "X&Y", 2005)
    private val creep = track("creep", "Radiohead", "Creep", "Pablo Honey", 1993)
    private val harbor = track("harbor", "Paper Lanterns", "Harbor Lights", "Low Tide", 2021)

    private val onlineCatalog = listOf(
        OnlineTrack(track("yellow-ov", "Coldplay", "Coldplay - Yellow (Official Video)"), official = true, popularity = 0.95f),
        OnlineTrack(track("yellow-topic", "Coldplay - Topic", "Yellow"), official = true, popularity = 0.8f),
        OnlineTrack(track("yellow-lyrics", "Coldplay", "Yellow (Lyrics)"), popularity = 0.4f),
        OnlineTrack(track("yellow-live", "Coldplay", "Yellow (Live at Glastonbury 2016)"), popularity = 0.4f),
        OnlineTrack(track("yellow-karaoke", "Sing King", "Yellow (Karaoke Version)"), popularity = 0.3f),
        OnlineTrack(track("yellow-cover", "Boyce Avenue", "Yellow (Coldplay Cover)"), popularity = 0.35f),
        OnlineTrack(track("viva", "Coldplay", "Viva la Vida"), official = true, popularity = 0.9f),
    )

    private suspend fun library(): LocalLibrary = LocalLibrary().apply {
        upsertAll(listOf(yellow, fixYou, creep))
        upsert(harbor, setOf(LocalSource.HISTORY))
        putLyrics(harbor, "we left the porch light burning for the boats that never came\nsalt is in the letters that you folded with my name")
    }

    private suspend fun TestScope.engine(
        online: OnlineSearchProvider = FakeOnlineProvider(onlineCatalog, delayMs = 200),
        semantic: SemanticRetriever = SemanticRetriever.None,
        affinity: AffinityProvider = AffinityProvider.None,
        config: SearchConfig = SearchConfig(),
        lib: LocalLibrary? = null,
    ) = SearchEngine(lib ?: library(), online, semantic, affinity, config, cpuDispatcher = StandardTestDispatcher(testScheduler))

    private fun SearchResults.trackIds() = ranked.filterIsInstance<TrackResult>().map { it.track.youtubeId }

    @Test
    fun emitsLocalResultsFirstThenMergedOnlineResults() = runTest {
        val emissions = engine().search("yellow").toList()
        assertEquals(2, emissions.size)
        val (first, final) = emissions
        assertEquals(RetrieverStatus.DONE, first.retrievers[Retriever.LOCAL])
        assertEquals(RetrieverStatus.PENDING, first.retrievers[Retriever.ONLINE])
        assertEquals(RetrieverStatus.SKIPPED, first.retrievers[Retriever.SEMANTIC])
        assertEquals(false, first.isFinal)
        assertEquals("yellow", (first.top as TrackResult).track.youtubeId)
        assertTrue(final.isFinal)
        assertTrue(final.trackIds().contains("yellow-karaoke"), "online results merged in")
        assertEquals(200L, currentTime, "final emission waited for the online stage only")
    }

    @Test
    fun onlineDeadlineIsEnforced() = runTest {
        val slow = FakeOnlineProvider(onlineCatalog, delayMs = 5_000)
        val final = engine(online = slow).search("yellow").last()
        assertEquals(RetrieverStatus.TIMED_OUT, final.retrievers[Retriever.ONLINE])
        assertEquals("yellow", (final.top as TrackResult).track.youtubeId, "local results survive")
        assertEquals(600L, currentTime)
    }

    @Test
    fun failingRetrieverDoesNotBreakSearch() = runTest {
        val broken = object : OnlineSearchProvider {
            override suspend fun search(query: ParsedQuery, limit: Int): OnlineResults = error("network down")
            override suspend fun suggestions(prefix: String) = emptyList<String>()
        }
        val final = engine(online = broken).search("creep").last()
        assertEquals(RetrieverStatus.FAILED, final.retrievers[Retriever.ONLINE])
        assertEquals("creep", (final.top as TrackResult).track.youtubeId)
    }

    @Test
    fun uploadsOfOneSongCollapseToOneRowPreferringTheOfficialOne() = runTest {
        val final = engine().search("yellow coldplay").last()
        val rows = final.ranked.filterIsInstance<TrackResult>()
        val original = rows.filter { it.versions.isEmpty() && it.track.primaryArtist.startsWith("Coldplay") && "Yellow" in it.track.title }
        assertEquals(1, original.size, "library copy, official video, topic upload and lyric upload are one row: ${rows.map { it.track.youtubeId }}")
        val row = original.single()
        assertEquals("yellow-ov", row.track.youtubeId)
        assertTrue(row.official)
        assertEquals(setOf("yellow", "yellow-topic", "yellow-lyrics"), row.alternates.map { it.id }.toSet())
        assertTrue(LocalSource.LIBRARY in row.localSources)
        assertEquals(row, final.top)
        // The live version is a different recording and stays its own row.
        assertTrue(rows.any { it.track.youtubeId == "yellow-live" && VersionTag.LIVE in it.versions })
    }

    @Test
    fun originalBeatsCoversLiveAndKaraokeUnlessAsked() = runTest {
        val e = engine()
        val plain = e.search("yellow").last().trackIds()
        val originalAt = plain.indexOf("yellow-ov")
        for (other in listOf("yellow-live", "yellow-karaoke", "yellow-cover")) {
            assertTrue(originalAt < plain.indexOf(other), "original before $other in $plain")
        }
        assertEquals("yellow-live", e.search("yellow live").last().trackIds().first())
        assertEquals("yellow-karaoke", e.search("yellow karaoke").last().trackIds().first())
        assertEquals("yellow-cover", e.search("yellow cover").last().trackIds().first())
    }

    @Test
    fun affinityBreaksTiesBetweenEqualMatches() = runTest {
        val lib = LocalLibrary().apply {
            upsertAll(listOf(track("fm", "Fleetwood Mac", "Dreams", popularity = 0.9f), track("cr", "The Cranberries", "Dreams", popularity = 0.7f)))
        }
        val likesCranberries = object : AffinityProvider {
            override suspend fun artistAffinity(artist: ArtistKey) = if (artist == ArtistKey.of("The Cranberries")) 0.9f else 0f
            override suspend fun trackAffinity(track: TrackKey) = 0f
        }
        assertEquals("fm", engine(online = OnlineSearchProvider.None, lib = lib).search("dreams").last().trackIds().first())
        val personal = engine(online = OnlineSearchProvider.None, lib = lib, affinity = likesCranberries).search("dreams").last()
        assertEquals("cr", personal.trackIds().first())
        assertTrue((personal.top as TrackResult).breakdown.affinity > 0.5f)
    }

    @Test
    fun topResultFollowsIntent() = runTest {
        val e = engine(online = OnlineSearchProvider.None)
        val artist = e.search("coldplay").last()
        assertIs<ArtistResult>(artist.top)
        assertEquals(ArtistKey.of("Coldplay"), artist.top.artist)
        assertTrue(artist.songs.map { it.track.youtubeId }.containsAll(listOf("yellow", "fixyou")), "artist's songs grouped below")

        val album = e.search("parachutes album").last()
        assertIs<AlbumResult>(album.top)

        val mood = e.search("rainy night indie").last()
        val mix = assertIs<MixResult>(mood.top)
        assertEquals(listOf("rainy", "night"), mix.seed.mood!!.moods)
    }

    @Test
    fun lyricsMatchesCarryTheMatchedLine() = runTest {
        val final = engine(online = OnlineSearchProvider.None).search("salt is in the letters that you folded").last()
        val top = assertIs<TrackResult>(final.top)
        assertEquals("harbor", top.track.youtubeId)
        assertEquals(LyricsSnippet(1, "salt is in the letters that you folded with my name"), top.lyrics)
        assertTrue(top.reasons.any { it is MatchReason.Lyrics })

        val second = engine(online = OnlineSearchProvider.None).search("porch light burning for the boats").last()
        assertTrue(second.lyrics.isNotEmpty() || (second.top as? TrackResult)?.lyrics != null)
    }

    @Test
    fun semanticHitsAreMergedWithTheirReason() = runTest {
        val semantic = object : SemanticRetriever {
            override suspend fun retrieve(query: ParsedQuery, limit: Int): List<SemanticHit> {
                delay(50)
                return listOf(SemanticHit(creep, 0.9f), SemanticHit(track("new", "Bon Iver", "Holocene"), 0.8f))
            }
        }
        val emissions = engine(online = OnlineSearchProvider.None, semantic = semantic).search("sad indie for a rainy night").toList()
        val final = emissions.last()
        assertEquals(RetrieverStatus.DONE, final.retrievers[Retriever.SEMANTIC])
        assertIs<MixResult>(final.top)
        val holocene = final.songs.first { it.track.youtubeId == "new" }
        assertEquals(listOf<MatchReason>(MatchReason.Semantic(0.8f)), holocene.reasons)
        assertTrue(emissions.first().songs.none { it.track.youtubeId == "new" }, "semantic results arrive after local ones")
    }

    @Test
    fun semanticDeadlineIsEnforced() = runTest {
        val slow = object : SemanticRetriever {
            override suspend fun retrieve(query: ParsedQuery, limit: Int): List<SemanticHit> {
                delay(1_000)
                return emptyList()
            }
        }
        val final = engine(online = OnlineSearchProvider.None, semantic = slow).search("workout").last()
        assertEquals(RetrieverStatus.TIMED_OUT, final.retrievers[Retriever.SEMANTIC])
        assertEquals(120L, currentTime)
    }

    @Test
    fun onlineOnlyResultsExplainThatTheyCameFromOnline() = runTest {
        val final = engine().search("viva la vida").last()
        val viva = final.songs.firstOrNull { it.track.youtubeId == "viva" } ?: final.top as TrackResult
        assertTrue(viva.reasons.contains(MatchReason.Online(0)))
        assertTrue(viva.reasons.filterIsInstance<MatchReason.Lexical>().all { it.source == Retriever.ONLINE })
        assertTrue(viva.localSources.isEmpty())
    }

    @Test
    fun yearFilterPrefersTracksFromThatYear() = runTest {
        val lib = LocalLibrary().apply {
            upsertAll(listOf(track("a", "Taylor Swift", "Shake It Off", year = 2014), track("b", "Taylor Swift", "Anti-Hero", year = 2022)))
        }
        val final = engine(online = OnlineSearchProvider.None, lib = lib).search("taylor swift 2022").last()
        assertEquals("b", (final.top as TrackResult).track.youtubeId)
    }

    @Test
    fun blankQueryEmitsOnceWithEverythingSkipped() = runTest {
        val online = FakeOnlineProvider(onlineCatalog)
        val emissions = engine(online = online).search("   ").toList()
        assertEquals(1, emissions.size)
        assertTrue(emissions.single().ranked.isEmpty())
        assertTrue(emissions.single().retrievers.values.all { it == RetrieverStatus.SKIPPED })
        assertEquals(0, online.calls)
    }

    @Test
    fun singleCharacterSkipsOnline() = runTest {
        val online = FakeOnlineProvider(onlineCatalog)
        val final = engine(online = online).search("y").last()
        assertEquals(RetrieverStatus.SKIPPED, final.retrievers[Retriever.ONLINE])
        assertEquals(0, online.calls)
        assertNotNull(final.top, "local prefix results on the first keystroke")
    }

    @Test
    fun singleIdeographSearchesOnline() = runTest {
        val online = FakeOnlineProvider(listOf(OnlineTrack(track("ame", "森高千里", "雨"), popularity = 0.6f)))
        val final = engine(online = online).search("雨").last()
        assertEquals(RetrieverStatus.DONE, final.retrievers[Retriever.ONLINE])
        assertEquals("ame", (final.top as TrackResult).track.youtubeId)
    }

    @Test
    fun onlineArtistNamedLikeTheQueryIsTheTopResult() = runTest {
        // Her songs rank first online and none is in the library, so only the online artist says "artist".
        val online = FixedOnline(
            OnlineResults(
                tracks = listOf(OnlineTrack(track("shake", "Taylor Swift", "Shake It Off"), official = true, popularity = 0.95f)),
                artists = listOf(OnlineArtist("Taylor Swift", browseId = "UC_taylor")),
            ),
        )
        val final = engine(online = online).search("taylor swift").last()
        val top = assertIs<ArtistResult>(final.top, final.ranked.toString())
        assertEquals("UC_taylor", top.browseId)
        assertEquals(Intent.ARTIST, final.query.intent)
        assertIs<TrackResult>(engine(online = online).search("taylor swift 2014").last().top, "a year asks for her songs")
    }

    @Test
    fun knownTitleStaysOnTopOfAnArtistWithTheSameName() = runTest {
        val online = FixedOnline(
            OnlineResults(
                tracks = listOf(OnlineTrack(track("creep-ov", "Radiohead", "Creep (Official Video)"), official = true, popularity = 0.9f)),
                artists = listOf(OnlineArtist("Creep", browseId = "UC_creep")),
            ),
        )
        val final = engine(online = online).search("creep").last()
        val top = assertIs<TrackResult>(final.top, final.ranked.toString())
        assertTrue(top.track.youtubeId == "creep" || top.alternates.any { it.id == "creep" }, "the library's Creep")
        assertEquals(Intent.SONG, final.query.intent)
    }

    @Test
    fun onlineResultsSharingNoWordWithTheQueryRankBelowLibraryMatches() = runTest {
        val lib = LocalLibrary().apply { upsert(track("lana", "Lana Del Rey", "Video Games", "Born to Die")) }
        val online = FixedOnline(
            OnlineResults(
                tracks = listOf(
                    OnlineTrack(track("other", "Someone Else", "Completely Different"), official = true, popularity = 0.95f),
                    OnlineTrack(track("kanji", "米津玄師", "感電"), popularity = 0.9f),
                ),
            ),
        )
        val final = engine(online = online, lib = lib).search("video games").last()
        assertEquals("lana", (final.top as TrackResult).track.youtubeId)
        val unrelated = final.songs.single { it.track.youtubeId == "other" }
        val otherScript = final.songs.single { it.track.youtubeId == "kanji" }
        // Same script and no shared word: the provider's rank alone counts for little. Another script cannot be compared.
        assertTrue(unrelated.breakdown.relevance < otherScript.breakdown.relevance, "${unrelated.breakdown} vs ${otherScript.breakdown}")
    }

    @Test
    fun initialismArtistIsFoundWithoutDots() = runTest {
        val lib = LocalLibrary().apply { upsert(track("planes", "M.I.A.", "Paper Planes", "Kala")) }
        val top = engine(online = OnlineSearchProvider.None, lib = lib).search("mia").last().top
        assertEquals("M.I.A.", assertIs<ArtistResult>(top).name)
    }

    /** Returns the same results for any query, like a provider whose ranking is not purely lexical. */
    private class FixedOnline(private val results: OnlineResults) : OnlineSearchProvider {
        override suspend fun search(query: ParsedQuery, limit: Int) = results
        override suspend fun suggestions(prefix: String) = emptyList<String>()
    }

    @Test
    fun lateLocalResultsArriveInALaterEmission() = runTest {
        // A local budget of zero forces the first emission before the local stage has run.
        val emissions = engine(online = OnlineSearchProvider.None, config = SearchConfig(localDeadline = kotlin.time.Duration.ZERO)).search("creep").toList()
        assertEquals(RetrieverStatus.PENDING, emissions.first().retrievers[Retriever.LOCAL])
        assertEquals("creep", (emissions.last().top as TrackResult).track.youtubeId)
    }
}
