package app.turtleplayer.bridge

import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.LocalSource
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TurtleSearchTest {
    private fun song(id: String, title: String, artist: String, type: String? = null) =
        SongItem(id = id, title = title, thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 300, musicVideoType = type)

    private val karaoke = song("k1", "Bohemian Rhapsody (Karaoke Version)", "Sing King")
    private val official = song("q1", "Bohemian Rhapsody", "Queen", type = "MUSIC_VIDEO_TYPE_ATV")
    private val live = song("q2", "Bohemian Rhapsody (Live Aid 1985)", "Queen")
    private val queen = ArtistItem(id = "UCqueen", title = "Queen", thumbnail = null)
    private val videos = SearchSummary("Videos", listOf(song("v9", "Bohemian Rhapsody reaction", "Some Channel")))

    // Upstream's order is deliberately unhelpful: karaoke on top, official buried.
    private val page = SearchSummaryPage(
        listOf(
            SearchSummary("", listOf(karaoke), isTopResult = true),
            SearchSummary("Songs", listOf(live, official)),
            SearchSummary("Artists", listOf(queen)),
            videos,
        ),
    )

    private fun search(library: LocalLibrary = LocalLibrary()) = TurtleSearch(library, InMemoryPlayEventStore(), SongCache())

    @Test
    fun officialSongBecomesTopResultAndOtherSectionsPassThrough() = runBlocking {
        val result = assertNotNull(search().rerank("bohemian rhapsody", page))
        val top = result.summaries.first()
        assertTrue(top.isTopResult)
        assertEquals("q1", top.items.single().id, "official upload should beat karaoke and live versions")
        val songs = result.summaries.first { it.title == "Songs" }.items.map { it.id }
        assertTrue(songs.indexOf("q2") < songs.indexOf("k1"), "karaoke ranks below other versions: $songs")
        assertEquals(videos, result.summaries.last(), "sections we do not rank are kept as they are")
    }

    @Test
    fun typoFindsLibrarySongAndArtistQueryTopsWithArtist() = runBlocking {
        val library = LocalLibrary()
        val mine = song("lib1", "Under Pressure", "Queen")
        library.upsert(mine.toTrackMeta(), setOf(LocalSource.LIBRARY))
        val cache = SongCache().apply { replaceLibrary(listOf(mine)) }
        val turtle = TurtleSearch(library, InMemoryPlayEventStore(), cache)

        val typo = assertNotNull(turtle.rerank("undr presure", SearchSummaryPage(emptyList())))
        assertEquals("lib1", typo.summaries.first().items.single().id, "library song found despite typos, with no online results")

        val artist = assertNotNull(turtle.rerank("queen", page))
        assertEquals("UCqueen", artist.summaries.first().items.single().id)
    }

    @Test
    fun artistNameQueryTopsWithArtistOnARealisticPage() = runBlocking {
        val ts = ArtistItem(id = "UCts", title = "Taylor Swift", thumbnail = null)
        val atv = "MUSIC_VIDEO_TYPE_ATV"
        val tsPage = SearchSummaryPage(
            listOf(
                SearchSummary("", listOf(ts), isTopResult = true),
                SearchSummary(
                    "Songs",
                    listOf(
                        song("s1", "Patient Zero", "Taylor Swift", atv),
                        song("s2", "Taylor Swift - You Belong With Me (Taylor's Version) (Music Video 4K)", "Swift Leaks Backup"),
                        song("s3", "Look What You Made Me Do", "Taylor Swift", atv),
                        song("s4", "Jump Then Fall (Taylor’s Version)", "Taylor Swift", atv),
                    ),
                ),
                SearchSummary(
                    "Artists",
                    listOf(ts, ArtistItem(id = "UCsc", title = "Sabrina Carpenter", thumbnail = null), ArtistItem(id = "UCed", title = "Ed Sheeran", thumbnail = null)),
                ),
            ),
        )
        // From a phone library: "swift", read as a typo of the artist being typed, once made this "taylor" by SwitchOTR.
        val library = LocalLibrary().apply { upsert(song("lib-so", "Hunnid", "SwitchOTR").toTrackMeta(), setOf(LocalSource.LIBRARY)) }
        val result = assertNotNull(search(library).rerank("taylor swift", tsPage))
        assertEquals("UCts", result.summaries.first().items.single().id, "artist first: ${result.summaries.first().items}")
    }

    // ---- Title collisions (the Windows walkthrough: "lofi loft" and "lofi lof") -------------------

    private val lofiLoft = PlaylistItem(id = "VLlofiloft", title = "Lofi Loft", thumbnail = null, author = "Lofi Records")
    private val sameNameArtist = ArtistItem(id = "UClofiloft", title = "Lofi Loft", thumbnail = null)

    /** YouTube's page for the playlist's name: the playlist on top, a same-name artist and a few songs below. */
    private fun lofiPage(top: com.metrolist.shared.model.YTItem = lofiLoft) =
        SearchSummaryPage(
            listOf(
                SearchSummary("", listOf(top), isTopResult = true),
                SearchSummary("Songs", listOf(song("lf1", "Lofi Loft Sessions", "Chill Beats"), song("lf2", "Rainy Loft", "Lofi Loft"))),
                SearchSummary("Artists", listOf(sameNameArtist)),
                SearchSummary("Community playlists", listOf(PlaylistItem(id = "VLother", title = "Lofi Loft vibes", thumbnail = null))),
            ),
        )

    @Test
    fun rerankKeepsAnExactTitlePlaylistTopOverASameNameArtist() = runBlocking {
        val result = assertNotNull(search().rerank("lofi loft", lofiPage()))
        val top = result.summaries.first()
        assertTrue(top.isTopResult)
        assertEquals("VLlofiloft", top.items.single().id, "the playlist that was typed stays on top: ${top.items}")
        // The same-name artist is still found, just not above the playlist.
        assertEquals(listOf("UClofiloft"), result.summaries.first { it.title == "Artists" }.items.map { it.id })
        assertTrue(result.summaries.any { it.title == "Community playlists" }, "sections we do not rank pass through")
    }

    @Test
    fun aTypoOfThePlaylistTitleKeepsItOnTopToo() = runBlocking {
        val result = assertNotNull(search().rerank("lofi lof", lofiPage()))
        assertEquals("VLlofiloft", result.summaries.first().items.single().id, "top: ${result.summaries.first().items}")
    }

    @Test
    fun anUpstreamPlaylistTopThatIsNotTheQueryLeavesOurTop() = runBlocking {
        // A playlist on top whose title is not what was typed: our engine's pick stands.
        val other = PlaylistItem(id = "VLchill", title = "Chill Evening Mix", thumbnail = null)
        val result = assertNotNull(search().rerank("lofi loft", lofiPage(top = other)))
        assertTrue(result.summaries.first().items.single().id != "VLchill", "top: ${result.summaries.first().items}")
    }

    @Test
    fun localStageFindsLibrarySongsBeforeTheNetwork() = runBlocking {
        val library = LocalLibrary()
        val mine = song("lib1", "Under Pressure", "Queen")
        library.upsert(mine.toTrackMeta(), setOf(LocalSource.LIBRARY))
        val cache = SongCache().apply { replaceLibrary(listOf(mine)) }
        val turtle = TurtleSearch(library, InMemoryPlayEventStore(), cache)
        val local = assertNotNull(turtle.local("undr presure"))
        assertEquals("lib1", local.summaries.first().items.single().id)
        assertTrue(local.summaries.first().isTopResult)
        assertNull(turtle.local("zzzz qqqq"), "nothing in the library matches")
    }
}
