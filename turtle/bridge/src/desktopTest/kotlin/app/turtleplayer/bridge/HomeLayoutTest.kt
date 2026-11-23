package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.taste.RowItem
import app.turtleplayer.taste.SongSupport
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.AlbumTiny
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.Chip
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfStyle
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeLayoutTest {
    private fun song(
        id: String,
        artist: String = "Queen",
        podcastId: String? = null,
        album: AlbumTiny? = null,
    ) = SongItem(
        id = id,
        title = "t$id",
        thumbnail = null,
        artists = listOf(ArtistTiny(null, artist)),
        album = album,
        duration = 200,
        podcastId = podcastId,
    )

    private val album = AlbumItem("al1", "A", null, listOf(ArtistTiny(null, "Queen")))

    // Every row carries feedChip, as upstream sets it while a chip is selected (Review Focus 5).
    private fun shelf(
        key: String,
        vararg items: YTItem,
        style: HomeShelfStyle = HomeShelfStyle.CAROUSEL,
        title: String = key,
        browseId: String? = null,
        personal: Boolean = false,
    ) = HomeShelf(
        key = key,
        title = title,
        items = items.toList(),
        style = style,
        browseId = browseId,
        isPersonal = personal,
        feedChip = Chip("Relax"),
    )

    private val state =
        HomeUiState(
            discoverySections = listOf(HomeSection(title = "Explore row", items = listOf(song("e1")), browseId = "FEmusic_x")),
            speedDialPinnedIds = listOf("p1"),
        )
    private val shelves =
        listOf(
            shelf("speed_dial", song("p1"), song("r1"), song("r2"), song("r3"), style = HomeShelfStyle.SPEED_DIAL),
            shelf("keep_listening", song("k1")),
            shelf("quick_picks", song("j1"), song("q1"), style = HomeShelfStyle.SONG_GRID),
            shelf("chip_0_0", song("c1")),
            shelf("trending", song("t1")),
            shelf("album_charts", album, style = HomeShelfStyle.ALBUM_CAROUSEL),
            shelf("new_releases", album),
            shelf("community_playlists", PlaylistItem("pl1", "P", null)),
            shelf("podcasts", song("ep1", podcastId = "P1")),
            shelf("moods", song("m1"), style = HomeShelfStyle.MOOD_AND_GENRES),
            shelf("section_FEmusic_x_3", song("e1"), title = "Explore row", browseId = "FEmusic_x"),
            shelf("section_mixed_4", song("r2")),
            shelf("section_Your mixes_5", PlaylistItem("pl2", "Mix", null), personal = true),
            shelf("similar_UC1_0", song("s1"), style = HomeShelfStyle.SIMILAR_TO),
            shelf("turtle:daily_mix:0", song("d1"), style = HomeShelfStyle.SONG_GRID),
            shelf("turtle:more_like:yt:q1", song("ml1"), style = HomeShelfStyle.SONG_GRID),
        )

    @Test
    fun candidatesDropSpeedDialRecentsChipsChartsExplorePodcastsAndMoods() {
        val matched = listOf("quick_picks", "section_mixed_4", "section_Your mixes_5", "similar_UC1_0")
        assertEquals(matched, HomeLayout.candidates(shelves, state, HomeYouTubeRows.MATCHED).map { it.key })
        assertEquals(matched, HomeLayout.candidates(shelves, state, HomeYouTubeRows.OFF).map { it.key })
        assertEquals(
            listOf(
                "keep_listening",
                "quick_picks",
                "chip_0_0",
                "trending",
                "album_charts",
                "new_releases",
                "community_playlists",
                "podcasts",
                "moods",
                "section_FEmusic_x_3",
                "section_mixed_4",
                "section_Your mixes_5",
                "similar_UC1_0",
            ),
            HomeLayout.candidates(shelves, state, HomeYouTubeRows.ALL).map { it.key },
        )
        assertEquals(listOf("r1", "r2", "r3"), HomeLayout.gridCandidates(shelves, state).map { it.id })
        assertEquals(listOf("q9"), HomeLayout.gridCandidates(emptyList(), HomeUiState(quickPicks = listOf(song("q9")))).map { it.id })
    }

    @Test
    fun gridTakesPinsThenRecentsThenMatchedSuggestionsWithoutRepeats() { // Review Focus 4
        val recents = listOf(song("p1"), song("j1"), song("j2"))
        val pick = HomeRowsPick(rowKeys = listOf("quick_picks", "section_mixed_4"), gridIds = setOf("r2", "r3"), scored = true)
        val matched = HomeLayout.of(shelves, state, recents, pick, HomeYouTubeRows.MATCHED)
        assertEquals(listOf("p1", "j1", "j2", "r2", "r3"), matched.grid.map { it.id })
        assertTrue(matched.gridReady)
        assertEquals(emptyList(), matched.jumpBackIn) // every recent is already a tile
        assertEquals(listOf("quick_picks"), matched.youtubeRows.map { it.key }) // section_mixed_4 emptied by dedupe
        val quickPicks = matched.youtubeRows.single()
        assertEquals(listOf("q1"), quickPicks.items.map { it.id }) // j1 removed: it is a tile
        assertEquals(listOf("turtle:daily_mix:0"), matched.mixes.map { it.key })
        assertEquals(listOf("turtle:more_like:yt:q1"), matched.moreLike.map { it.key })
        // Each tile knows where it came from, for play attribution: the round-robin tile names the YouTube row it is in.
        val origins = listOf("pin", "recent", "recent", "youtube:section_mixed_4", "youtube:speed_dial")
        assertEquals(origins, matched.grid.map(matched::gridOrigin))

        val all = HomeLayout.of(shelves, state, recents, null, HomeYouTubeRows.ALL)
        assertEquals(listOf("p1", "j1", "j2", "r1", "r2", "r3"), all.grid.map { it.id })
        val off = HomeLayout.of(shelves, state, recents, null, HomeYouTubeRows.OFF)
        assertEquals(listOf("p1", "j1", "j2"), off.grid.map { it.id })
        assertEquals(emptyList(), off.youtubeRows)

        // Not yet known: no YouTube rows, and a short grid waits rather than reshuffling after first paint.
        val unscored = HomeLayout.of(shelves, state, recents, null, HomeYouTubeRows.MATCHED)
        assertEquals(emptyList(), unscored.youtubeRows)
        assertFalse(unscored.gridReady)
        assertFalse(HomeLayout.of(shelves, state, recents, pick, null).gridReady)
        assertFalse(HomeLayout.of(shelves, state, null, pick, HomeYouTubeRows.MATCHED).gridReady)

        // W9's "Not interested": hidden items never take a tile, and a hidden mix leaves Made for you.
        val hidden = HiddenSet(songIds = setOf("j1", "q1"), homeShelfKeys = setOf("turtle:daily_mix:0"))
        val withHidden = HomeLayout.of(shelves, state, recents, pick, HomeYouTubeRows.MATCHED, hidden)
        assertEquals(listOf("p1", "j2", "r2", "r3"), withHidden.grid.map { it.id })
        assertEquals(emptyList(), withHidden.mixes)
        assertEquals(emptyList(), withHidden.youtubeRows) // quick_picks held only j1 and q1
    }

    @Test
    fun jumpBackInRowShowsRecentsNotInTheGrid() {
        val nine = (1..9).map { song("j$it") }
        val layout = HomeLayout.of(emptyList(), HomeUiState(), nine, null, HomeYouTubeRows.MATCHED)
        assertEquals((1..6).map { "j$it" }, layout.grid.map { it.id })
        assertTrue(layout.gridReady) // recents fill every tile: no pick needed
        assertEquals(listOf("j7", "j8", "j9"), layout.jumpBackIn.map { it.id })
        assertEquals(emptyList(), HomeLayout.of(emptyList(), HomeUiState(), nine.take(8), null, HomeYouTubeRows.MATCHED).jumpBackIn)
    }

    @Test
    fun rowItemsCarryArtistsOnlyWhereUpstreamNamesThem() {
        assertEquals(ArtistKey.of("Queen"), (song("x", artist = "Queen - Topic").toRowItem() as RowItem.Track).track.artistKey)
        assertEquals(RowItem.Collection(listOf(ArtistKey.of("Queen"))), album.toRowItem())
        assertEquals(RowItem.Artist(ArtistKey.of("Queen")), ArtistItem("UC1", "Queen", null).toRowItem())
        assertEquals(RowItem.Collection(emptyList()), PlaylistItem("pl", "P", null).toRowItem())
    }

    @Test
    fun matchedRowsAreReorderedOnlyWhenEverySongShownIsSupported() {
        fun support(
            id: String,
            artist: String,
            score: Double,
        ) = id to SongSupport(score, heard = true, disliked = false, artist = ArtistKey.of(artist), song = TrackKey.youtube(id))

        val supports = mapOf(support("a1", "A", 0.1), support("b1", "B", 0.9))

        fun layout(
            vararg items: YTItem,
            songSupport: Map<String, SongSupport> = supports,
            orderToday: Set<String> = setOf("mix"),
        ): HomeLayout {
            val pick =
                HomeRowsPick(
                    rowKeys = listOf("mix"),
                    gridIds = emptySet(),
                    scored = true,
                    songSupport = mapOf("mix" to songSupport),
                    orderToday = orderToday,
                )
            return HomeLayout.of(listOf(shelf("mix", *items)), HomeUiState(), emptyList(), pick, HomeYouTubeRows.MATCHED)
        }

        fun order(layout: HomeLayout) =
            layout.youtubeRows
                .single()
                .items
                .map { it.id }

        val reordered = layout(song("a1", "A"), song("b1", "B"))
        assertEquals(listOf("b1", "a1"), order(reordered))
        assertEquals(setOf("mix"), reordered.orderedRows)
        assertEquals(emptySet(), reordered.comparisonRows)
        // Not its day: YouTube's order, marked as the comparison.
        val comparison = layout(song("a1", "A"), song("b1", "B"), orderToday = emptySet())
        assertEquals(listOf("a1", "b1"), order(comparison))
        assertEquals(emptySet(), comparison.orderedRows)
        assertEquals(setOf("mix"), comparison.comparisonRows)

        // An album card in the row, a song the pick never saw, one album's tracks: YouTube's order.
        val withAlbum = layout(song("a1", "A"), song("b1", "B"), album)
        assertEquals(listOf("a1", "b1", "al1"), order(withAlbum))
        assertEquals(emptySet(), withAlbum.orderedRows)
        assertEquals(emptySet(), withAlbum.comparisonRows)
        val stale = layout(song("a1", "A"), song("b1", "B"), songSupport = mapOf(support("b1", "B", 0.9)))
        assertEquals(listOf("a1", "b1"), order(stale))
        assertEquals(emptySet(), stale.orderedRows)
        assertEquals(emptySet(), stale.comparisonRows)
        val oneAlbum = AlbumTiny("alb1", "A")
        val sequence = layout(song("a1", "A", album = oneAlbum), song("b1", "B", album = oneAlbum))
        assertEquals(listOf("a1", "b1"), order(sequence))
        assertEquals(emptySet(), sequence.orderedRows)
        assertEquals(emptySet(), sequence.comparisonRows)
    }
}
