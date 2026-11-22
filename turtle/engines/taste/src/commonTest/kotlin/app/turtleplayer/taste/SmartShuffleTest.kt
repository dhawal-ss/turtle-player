package app.turtleplayer.taste

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.InMemoryPlayEventStore
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SmartShuffleTest {
    private fun track(id: String, artist: String, title: String = "song $id") =
        TrackMeta(TrackKey.youtube(id), title, listOf(artist))

    private fun rec(track: TrackMeta, score: Float = 0.5f) =
        QueueItem(track, CandidateSource.UPSTREAM_RADIO, Reason.UpstreamRadio(track.key), SlotKind.DISCOVERY, score)

    /** 30 songs by 10 artists, three each. */
    private val playlist = List(30) { track("p$it", "artist ${it % 10}") }

    /** 40 recommendations by 20 other artists, two each, best first. */
    private val recommendations = List(40) { rec(track("r$it", "guest ${it % 20}"), 1f - it / 100f) }

    private fun arrange(
        seed: Int = 7,
        config: SmartShuffleConfig = SmartShuffleConfig(),
        tracks: List<TrackMeta> = playlist,
        recs: List<QueueItem> = recommendations,
        controls: UserControls = UserControls(),
    ) = SmartShuffle(Random(seed), config).arrange(tracks, recs, controls)

    private fun assertNoArtistTwiceInARow(items: List<SmartShuffleItem>) {
        val artists = items.map { it.track.artistKey }
        for (i in 1 until artists.size) assertNotEquals(artists[i - 1], artists[i], "same artist back to back at $i: $artists")
    }

    @Test
    fun keepsEveryPlaylistSongExactlyOnce() {
        val withDuplicate = playlist + playlist[4]
        val result = arrange(tracks = withDuplicate)
        val own = result.filterNot { it.isRecommendation }.map { it.track.key }
        assertEquals(playlist.map { it.key.value }.sorted(), own.map { it.value }.sorted())
    }

    @Test
    fun insertsOneRecommendationAfterEveryThreePlaylistSongs() {
        val result = arrange()
        assertEquals(30 + 10, result.size)
        // Positions 3, 7, 11, ...: three playlist songs, then a recommendation.
        result.forEachIndexed { i, item -> assertEquals((i + 1) % 4 == 0, item.isRecommendation, "position $i") }
    }

    @Test
    fun ratioIsConfigurable() {
        val result = arrange(config = SmartShuffleConfig(playlistPerRecommendation = 2))
        assertEquals(15, result.count { it.isRecommendation })
        result.forEachIndexed { i, item -> assertEquals((i + 1) % 3 == 0, item.isRecommendation, "position $i") }
    }

    @Test
    fun recommendationsKeepTheirRankingOrderAndTags() {
        val recs = arrange().filter { it.isRecommendation }
        val ranks = recs.map { r -> recommendations.indexOfFirst { it.key == r.track.key } }
        assertEquals(ranks.sorted(), ranks)
        assertTrue(recs.all { it.surfaceDetail == SurfaceTag.format(CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY) })
        assertTrue(arrange().filterNot { it.isRecommendation }.all { it.surfaceDetail == null })
    }

    @Test
    fun noDuplicatesAndNoRepeatsOfPlaylistSongs() {
        val sameSongOtherUpload = rec(track("other-upload", "artist 3", title = playlist[3].title))
        val recs = listOf(rec(playlist[0]), sameSongOtherUpload, recommendations[0], recommendations[0]) + recommendations.drop(1)
        val result = arrange(recs = recs)
        val keys = result.map { it.track.key }
        assertEquals(keys.size, keys.toSet().size)
        val recKeys = result.filter { it.isRecommendation }.map { it.track.key }
        assertFalse(playlist[0].key in recKeys)
        assertFalse(sameSongOtherUpload.key in recKeys)
    }

    @Test
    fun neverTheSameArtistTwiceInARowWhenAvoidable() {
        repeat(20) { seed ->
            assertNoArtistTwiceInARow(arrange(seed = seed))
        }
        // Half the playlist by one artist is still arrangeable: A ? A ? A ...
        val heavy = List(8) { track("h$it", "heavy") } + List(8) { track("o$it", "other $it") }
        repeat(20) { seed -> assertNoArtistTwiceInARow(arrange(seed = seed, tracks = heavy)) }
    }

    @Test
    fun recommendationNeverRepeatsANeighboursArtist() {
        // Recommendations only by playlist artists: each must differ from the songs around it.
        val recs = List(20) { rec(track("x$it", "artist ${it % 10}", title = "extra $it")) }
        val result = arrange(recs = recs, config = SmartShuffleConfig(maxPerRecommendedArtist = 5))
        assertNoArtistTwiceInARow(result)
        assertTrue(result.any { it.isRecommendation })
    }

    @Test
    fun capsRecommendationsPerArtist() {
        val oneArtist = List(10) { rec(track("s$it", "single guest", title = "guest song $it")) }
        val result = arrange(recs = oneArtist)
        assertEquals(2, result.count { it.isRecommendation })
    }

    @Test
    fun dropsMutedAndLessLikeThisRecommendations() {
        val muted = recommendations[0].track.artistKey
        val lessArtist = recommendations[1].track.artistKey
        val lessTrack = recommendations[2].key
        val controls = UserControls(
            mutedArtists = setOf(muted),
            lessLikeThisArtists = mapOf(lessArtist to 2),
            lessLikeThisTracks = setOf(lessTrack),
        )
        val recs = arrange(controls = controls).filter { it.isRecommendation }.map { it.track }
        assertTrue(recs.isNotEmpty())
        assertTrue(recs.none { it.artistKey == muted || it.artistKey == lessArtist || it.key == lessTrack })
    }

    @Test
    fun deterministicForTheSameRandomAndShuffledAcrossSeeds() {
        assertEquals(arrange(seed = 3), arrange(seed = 3))
        val orders = (0 until 5).map { seed -> arrange(seed = seed).filterNot { it.isRecommendation }.map { it.track.key } }
        assertTrue(orders.toSet().size > 1, "different seeds should give different orders")
        assertNotEquals(playlist.map { it.key }, orders[0], "the playlist order should be shuffled")
    }

    @Test
    fun withoutRecommendationsItIsAPlainSpacedShuffle() {
        val result = arrange(recs = emptyList())
        assertEquals(playlist.size, result.size)
        assertTrue(result.none { it.isRecommendation })
        assertNoArtistTwiceInARow(result)
    }

    @Test
    fun oneArtistPlaylistKeepsEverySong() {
        val solo = List(5) { track("solo$it", "solo") }
        val result = arrange(tracks = solo, recs = emptyList())
        assertEquals(solo.map { it.key }.toSet(), result.map { it.track.key }.toSet())
        assertEquals(5, result.size)
    }

    @Test
    fun rankedByTheEngineThenArranged() = runTest {
        val world = SyntheticWorld()
        val engine = TasteEngine(InMemoryPlayEventStore(), world.store, world.catalog, random = Random(1))
        val own = world.tracksOfGenre(0).filterIndexed { i, _ -> i % 4 == 0 }
        val candidates = world.tracksOfGenre(1)
        val muted = candidates.first().artistKey
        engine.updateControls { it.copy(mutedArtists = setOf(muted)) }
        val ranked = engine.rankExternal(own.first(), candidates)
        val result = SmartShuffle(Random(1)).arrange(own, ranked, engine.controls, engine.profile())
        assertEquals(own.map { it.key }.toSet(), result.filterNot { it.isRecommendation }.map { it.track.key }.toSet())
        assertEquals(4, result.count { it.isRecommendation })
        assertTrue(result.filter { it.isRecommendation }.none { it.track.artistKey == muted })
        assertNoArtistTwiceInARow(result)
    }
}
