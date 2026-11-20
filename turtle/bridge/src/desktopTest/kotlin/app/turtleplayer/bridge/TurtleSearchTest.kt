package app.turtleplayer.bridge

import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.LocalSource
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
}
