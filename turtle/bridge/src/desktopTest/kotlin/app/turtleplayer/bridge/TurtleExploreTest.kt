package app.turtleplayer.bridge

import app.turtleplayer.core.Clock
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Explore loaded on its own (TurtleExplore.kt): upstream's Home rules applied to reduced Explore and
 * charts pages in the shape upstream's parser produces (a section's browseId is its header's link),
 * and the loader's caching, joining, failure and retry.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TurtleExploreTest {
    // ---------------------------------------------------------------------------------------------
    // Fixtures: FEmusic_explore and FEmusic_charts as upstream's getHomePageData returns their sections.
    // ---------------------------------------------------------------------------------------------

    private fun album(id: String, title: String = "Album $id") = AlbumItem(id = id, title = title, thumbnail = null, artists = listOf(ArtistTiny("UC$id", "Artist $id")))

    private fun song(id: String, title: String = "Song $id") = SongItem(id = id, title = title, thumbnail = null, artists = listOf(ArtistTiny("UC$id", "Artist $id")), album = null, duration = 200)

    private fun genre(title: String, params: String) = BrowseItem(id = "FEmusic_moods_and_genres_category", title = title, params = params)

    private val moodsShelf = HomeSection(
        title = "Moods & genres",
        browseId = "FEmusic_moods_and_genres",
        isMultiRow = true,
        items = listOf(
            genre("Chill", "ggMPOg1uX1JOQWZFeDByc2Jm"),
            genre("Focus", "ggMPOg1uX0NvNGNhWThMYWRh"),
            genre("Workout", "ggMPOg1uX1hvOUFnZTNzaGpV"),
            // YouTube repeats a tile across rows now and then; it shows once.
            genre("Chill", "ggMPOg1uX1JOQWZFeDByc2Jm"),
            genre("Hip-hop", "ggMPOg1uX1dyM2RwNmU5S3ZK"),
        ),
    )

    private val newAlbums = HomeSection(
        title = "New albums & singles",
        browseId = "FEmusic_new_releases_albums",
        items = listOf(album("MPREb_new1"), album("MPREb_new2"), album("MPREb_new1")),
    )

    private val newVideos = HomeSection(
        title = "New music videos",
        browseId = "FEmusic_new_releases_videos",
        // An album on the videos shelf still is not a new release: upstream skips *_videos shelves.
        items = listOf(song("vid1"), album("MPREb_onVideoShelf")),
    )

    /** Explore's "Trending" shelf links to a playlist, not to FEmusic_charts, so upstream's rule does not read it as charts. */
    private val trendingPlaylistShelf = HomeSection(
        title = "Trending",
        browseId = "VLOLAK5uy_trending",
        items = listOf(song("trend1"), song("trend2")),
    )

    private val community = HomeSection(
        title = "Community playlists",
        browseId = "FEmusic_community_playlists",
        items = listOf(PlaylistItem(id = "VLPLcommunity1", title = "Late night", thumbnail = null), PlaylistItem(id = "VLPLcommunity2", title = "Road trip", thumbnail = null)),
    )

    private val explorePage = listOf(newAlbums, moodsShelf, trendingPlaylistShelf, newVideos, community)

    private val chartsPage = listOf(
        HomeSection(title = "Video charts", browseId = "VLPLchart_videos", items = listOf(PlaylistItem(id = "VLPLtop100", title = "Top 100 music videos", thumbnail = null))),
        HomeSection(title = "Top songs", browseId = "VLPLtop_songs", items = listOf(song("c1"), song("c2"), song("c1"), album("MPREb_chart1"))),
        HomeSection(title = "Top artists", browseId = "FEmusic_top_artists", items = listOf(ArtistItem(id = "UCtop", title = "Top artist", thumbnail = null))),
    )

    /** A signed-in personal Home feed's first page: no moods, no charts, no new releases. */
    private val signedInHome = listOf(
        HomeSection(title = "Quick picks", browseId = "FEmusic_quickpicks", items = listOf(song("qp1"), song("qp2"))),
        HomeSection(title = "Listen again", browseId = "FEmusic_listen_again", items = listOf(song("la1"))),
        HomeSection(title = "Mixed for you", items = listOf(PlaylistItem(id = "RDTMAK5uy_mix", title = "My Supermix", thumbnail = null)), isPersonal = true),
    )

    // ---------------------------------------------------------------------------------------------
    // Mapping
    // ---------------------------------------------------------------------------------------------

    @Test
    fun explorePageMapsToGenresNewReleasesAndCommunity() {
        val data = ExploreMapping.from(explorePage)
        assertEquals(listOf("Chill", "Focus", "Workout", "Hip-hop"), data.genres.map { it.title })
        assertEquals("ggMPOg1uX0NvNGNhWThMYWRh", data.genres[1].params, "a genre keeps the params its category page opens with")
        assertEquals(listOf("MPREb_new1", "MPREb_new2"), data.newReleases.map { it.id })
        assertEquals(listOf("VLPLcommunity1", "VLPLcommunity2"), data.community.map { it.id })
        // No FEmusic_charts shelf and no charts page: Popular picks falls back to Explore's playlist-linked
        // "Trending" shelf, never to the new music videos shelf.
        assertEquals(listOf("trend1", "trend2"), data.trending.map { it.id })
        assertTrue(data.albumCharts.isEmpty())
        assertTrue(ExploreMapping.needsCharts(explorePage))
    }

    /** The shapes seen live, signed in (Nov 2026): Explore's songs sit on a playlist-linked shelf, the charts page has none. */
    @Test
    fun signedInLivePagesFillPopularPicksFromExploreTrending() {
        val episodes = HomeSection(title = "Popular episodes", browseId = "FEmusic_top_non_music_audio_episodes", items = emptyList())
        val explore = listOf(newAlbums, moodsShelf, episodes, trendingPlaylistShelf, newVideos)
        val charts = listOf(
            HomeSection(title = "Video charts", items = listOf(PlaylistItem(id = "VLPLtop100", title = "Top 100 music videos", thumbnail = null))),
            HomeSection(title = "Top albums", items = listOf(album("MPREb_top1"), album("MPREb_top2"))),
            HomeSection(title = "Top artists", items = listOf(ArtistItem(id = "UCtop", title = "Top artist", thumbnail = null))),
        )
        val data = ExploreMapping.from(explore, charts)
        assertEquals(listOf("trend1", "trend2"), data.trending.map { it.id }, "the Trending shelf, not the new music videos")
        assertEquals(listOf("MPREb_top1", "MPREb_top2"), data.albumCharts.map { it.id })
        assertEquals(listOf("MPREb_new1", "MPREb_new2"), data.newReleases.map { it.id })
    }

    @Test
    fun chartsPageFillsTrendingWhenExploreHasNoChartShelf() {
        val data = ExploreMapping.from(explorePage, chartsPage)
        assertEquals(listOf("c1", "c2"), data.trending.map { it.id })
        assertEquals(listOf("MPREb_chart1"), data.albumCharts.map { it.id })
        assertEquals(4, data.genres.size, "charts do not change the rest")
    }

    @Test
    fun exploreChartShelvesWinOverTheChartsPage() {
        val chartsShelf = HomeSection(title = "Charts", browseId = "FEmusic_charts", items = listOf(song("e1"), album("MPREb_e1")))
        val page = explorePage + chartsShelf
        assertFalse(ExploreMapping.needsCharts(page))
        val data = ExploreMapping.from(page, chartsPage)
        assertEquals(listOf("e1"), data.trending.map { it.id })
        assertEquals(listOf("MPREb_e1"), data.albumCharts.map { it.id })
    }

    @Test
    fun nonMusicalVideosAreNotPopularPicks() {
        // Upstream's Home drops them from every shelf; a chart shelf of only such videos still asks for the charts page.
        val vlog = song("vlog1").copy(musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
        val omv = song("omv1").copy(musicVideoType = "MUSIC_VIDEO_TYPE_OMV")
        val shelf = HomeSection(title = "Charts", browseId = "FEmusic_charts", items = listOf(vlog, omv, song("e1")))
        assertEquals(listOf("omv1", "e1"), ExploreMapping.from(explorePage + shelf).trending.map { it.id })
        val onlyVlogs = shelf.copy(items = listOf(vlog))
        assertTrue(ExploreMapping.needsCharts(explorePage + onlyVlogs))
        val charts = chartsPage + HomeSection(title = "Trending", browseId = "VLPLtrend", items = listOf(vlog))
        assertEquals(listOf("c1", "c2"), ExploreMapping.from(explorePage + onlyVlogs, charts).trending.map { it.id })
    }

    @Test
    fun chartShelvesOnTheChartsPageAreTakenBeforeTheRestOfIt() {
        val linked = HomeSection(title = "Top songs", browseId = "FEmusic_charts_top_songs", items = listOf(song("linked")))
        val data = ExploreMapping.from(emptyList(), chartsPage + linked)
        assertEquals(listOf("linked"), data.trending.map { it.id })
    }

    @Test
    fun aMoodsShelfWithoutCategoriesIsNotGenres() {
        val notGenres = HomeSection(title = "Moods & genres", browseId = "FEmusic_moods_and_genres", items = listOf(PlaylistItem(id = "VLPLx", title = "x", thumbnail = null)))
        assertFalse(ExploreMapping.isMoodsAndGenres(notGenres))
        assertTrue(ExploreMapping.from(listOf(notGenres)).genres.isEmpty())
    }

    @Test
    fun signedInHomeHasNoExploreButTheExplorePageDoes() = runTest {
        // What Shell's Explore read before: Home's sections. Signed in, they hold nothing for it.
        assertTrue(ExploreMapping.from(signedInHome).isEmpty)
        val loader = loader(backgroundScope, pages = mapOf(ExploreMapping.EXPLORE to explorePage, ExploreMapping.CHARTS to chartsPage))
        val state = loader.refresh()
        val data = assertNotNull(state.data)
        assertEquals(4, data.genres.size)
        assertEquals(2, data.trending.size)
        assertFalse(state.failed)
        assertFalse(state.loading)
        assertEquals(listOf(ExploreMapping.EXPLORE, ExploreMapping.CHARTS), calls)
    }

    // ---------------------------------------------------------------------------------------------
    // Loader
    // ---------------------------------------------------------------------------------------------

    private var now = 1_000_000L
    private val clock = Clock { now }
    private val calls = mutableListOf<String>()

    private fun loader(
        scope: CoroutineScope,
        pages: Map<String, List<HomeSection>> = mapOf(ExploreMapping.EXPLORE to explorePage, ExploreMapping.CHARTS to chartsPage),
        gate: CompletableDeferred<Unit>? = null,
        failWith: (String) -> Exception? = { null },
        filter: (List<HomeSection>) -> List<HomeSection> = { it },
    ) = TurtleExplore(
        fetch = { id ->
            calls += id
            gate?.await()
            failWith(id)?.let { throw it }
            pages[id].orEmpty()
        },
        filter = filter,
        clock = clock,
        scope = scope,
    )

    @Test
    fun aFreshResultIsKeptAndAnOldOneReloaded() = runTest {
        val l = loader(backgroundScope)
        l.refresh()
        l.refresh()
        assertEquals(2, calls.size, "a fresh result was fetched again: $calls")
        now += TurtleExplore.MAX_AGE_MS - 1
        l.refresh()
        assertEquals(2, calls.size)
        now += 2
        l.refresh()
        assertEquals(4, calls.size, "an old result was not reloaded: $calls")
        l.refresh(force = true)
        assertEquals(6, calls.size, "Retry did not reload")
    }

    @Test
    fun loadsAskedForTogetherJoinOneRequest() = runTest {
        val gate = CompletableDeferred<Unit>()
        val l = loader(backgroundScope, gate = gate)
        val a = async { l.refresh() }
        val b = async { l.refresh() }
        l.load()
        runCurrent()
        assertTrue(l.state.value.loading, "no loading state while the request runs")
        assertEquals(listOf(ExploreMapping.EXPLORE), calls)
        gate.complete(Unit)
        assertEquals(a.await(), b.await())
        runCurrent()
        assertEquals(listOf(ExploreMapping.EXPLORE, ExploreMapping.CHARTS), calls)
        assertFalse(l.state.value.loading)
        assertNotNull(l.state.value.data)
    }

    @Test
    fun aFailureIsReportedAndTheNextOpenRetries() = runTest {
        var offline = true
        val l = loader(backgroundScope, failWith = { if (offline) IOException("offline") else null })
        val failed = l.refresh()
        assertTrue(failed.failed)
        assertFalse(failed.loading)
        assertNull(failed.data)
        offline = false
        val ok = l.refresh() // not forced: a failure is never fresh
        assertFalse(ok.failed)
        assertEquals(4, ok.data?.genres?.size)
    }

    @Test
    fun anEmptyAnswerCountsAsAFailure() = runTest {
        val l = loader(backgroundScope, pages = mapOf(ExploreMapping.EXPLORE to signedInHome))
        val s = l.refresh()
        assertTrue(s.failed)
        assertNull(s.data)
        l.refresh()
        assertEquals(4, calls.size, "an empty answer was kept as fresh: $calls")
    }

    @Test
    fun aFailedRefreshKeepsWhatWasShown() = runTest {
        var offline = false
        val l = loader(backgroundScope, failWith = { if (offline) IOException("offline") else null })
        val first = assertNotNull(l.refresh().data)
        offline = true
        val after = l.refresh(force = true)
        assertTrue(after.failed)
        assertEquals(first, after.data)
    }

    @Test
    fun theChartsPageFailingStillShowsExplore() = runTest {
        val l = loader(backgroundScope, failWith = { if (it == ExploreMapping.CHARTS) IOException("charts down") else null })
        val s = l.refresh()
        assertFalse(s.failed)
        assertEquals(4, s.data?.genres?.size)
        // Without the charts page, Popular picks still comes from Explore's own Trending shelf.
        assertEquals(listOf("trend1", "trend2"), s.data!!.trending.map { it.id })
        assertTrue(s.data!!.albumCharts.isEmpty())
    }

    @Test
    fun blockListsApplyOnEachOpenAndFailOpen() = runTest {
        var blocked = emptySet<String>()
        val l = loader(backgroundScope, filter = { sections -> sections.map { s -> s.copy(items = s.items.filterNot { it.id in blocked }) } })
        assertEquals(2, l.refresh().data?.newReleases?.size)
        blocked = setOf("MPREb_new1")
        // Fresh, so no request, but the kept pages go through the block lists again.
        assertEquals(listOf("MPREb_new2"), l.refresh().data?.newReleases?.map { it.id })
        assertEquals(2, calls.size)

        calls.clear()
        val broken = loader(backgroundScope, filter = { error("settings not ready") })
        assertEquals(2, broken.refresh().data?.newReleases?.size, "block lists failing hid Explore")
    }
}
