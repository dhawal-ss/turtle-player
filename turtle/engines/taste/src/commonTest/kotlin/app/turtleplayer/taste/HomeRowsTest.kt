package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEvent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeRowsTest {
    private val t0 = SyntheticWorld.T0

    private fun track(
        id: String,
        artist: String,
        title: String = "song $id",
    ) = TrackMeta(TrackKey.youtube(id), title, listOf(artist), durationMs = 200_000, youtubeId = id)

    private fun finishes(
        artist: String,
        n: Int,
        fromMs: Long,
    ) = List(n) { play(track("${artist.lowercase()}$it", artist), fromMs + it * 5 * MINUTE_MS) }

    private fun skips(
        artist: String,
        n: Int,
        fromMs: Long,
    ) = List(n) { play(track("${artist.lowercase()}s$it", artist), fromMs + it * 5 * MINUTE_MS, end = EndReason.SKIPPED) }

    /** Warm (60 finishes ≥ coldStartPlays 50): Alpha and Beta loved, Gamma quick-skipped, Delta never heard. */
    private val warm = finishes("Alpha", 30, t0) + finishes("Beta", 30, t0 + DAY_MS) + skips("Gamma", 5, t0 + 2 * DAY_MS)

    private suspend fun engine(
        events: List<PlayEvent> = warm,
        controls: UserControls = UserControls(),
    ) = TasteEngine(
        events = InMemoryPlayEventStore().also { it.appendAll(events) },
        store = InMemoryEmbeddingStore(),
        catalog = InMemoryTrackCatalog(emptyList()),
        clock = MutableClock((events.maxOfOrNull { it.startedAtMs } ?: t0) + HOUR_MS),
        random = Random(1),
        controls = controls,
    )

    /** [n] never-played songs by [artist]. */
    private fun tracks(
        artist: String,
        n: Int,
    ) = List(n) { RowItem.Track(track("new${artist.lowercase()}$it", artist)) }

    private fun row(
        id: String,
        items: List<RowItem>,
        personal: Boolean = false,
    ) = RowCandidate(id, items, personal)

    private val playlists = List(6) { RowItem.Collection(emptyList()) }

    private val dislikedRow = row("dislikedRow", tracks("Gamma", 4))
    private val unknownRow = row("unknownRow", tracks("Delta", 4))
    private val betaRow = row("betaRow", tracks("Beta", 3) + tracks("Delta", 1))
    private val alphaRow = row("alphaRow", tracks("Alpha", 4))
    private val mixedRow = row("mixedRow", tracks("Alpha", 2) + tracks("Delta", 2))
    private val smallRow = row("smallRow", tracks("Alpha", 2))
    private val mostlyAlphaOneGamma = row("mostlyAlphaOneGamma", tracks("Alpha", 3) + tracks("Gamma", 1))
    private val editorial = row("editorial", playlists)
    private val yourMixes = row("yourMixes", playlists, personal = true)
    private val all = listOf(dislikedRow, unknownRow, betaRow, alphaRow, mixedRow, smallRow, mostlyAlphaOneGamma, editorial, yourMixes)

    @Test
    fun scoreRowCountsKnownAndDislikedShares() = runTest {
        val s =
            engine().scoreRow(
                tracks("Alpha", 1) + RowItem.Artist(ArtistKey.of("Beta")) +
                    RowItem.Collection(listOf(ArtistKey.of("Gamma"))) + tracks("Delta", 1),
            )
        assertEquals(4, s.scoredItems)
        assertEquals(0.75, s.knownArtistShare, 1e-9)
        assertEquals(0.25, s.dislikedShare, 1e-9)
        assertTrue(s.meanAffinity > 0.0, "two loved artists outweigh one skipped: $s")
        assertEquals(0.75 * s.meanAffinity, s.value, 1e-9)
    }

    @Test
    fun playlistOnlyRowHasNothingToScore() = runTest {
        assertEquals(RowScore(0.0, 0.0, 0.0, 0), engine().scoreRow(playlists))
    }

    @Test
    fun selectionKeepsMatchingRowsByScoreCappedAtThree() = runTest {
        val e = engine()
        val picked = e.selectHomeRows(all)
        assertFalse(picked.coldStart)
        // alpha ≈ 1.0, beta ≈ 0.75 (mixed ≈ 0.5 loses its slot to the personal playlist row, which goes last).
        assertEquals(listOf("alphaRow", "betaRow", "yourMixes"), picked.ids)
        assertEquals(listOf("alphaRow", "betaRow", "mixedRow"), e.selectHomeRows(all - yourMixes - editorial).ids)
        // Too few scorable items, disliked share 0.25 > 0.2, unknown artists, non-personal playlists: never kept.
        val notKept = setOf("smallRow", "mostlyAlphaOneGamma", "unknownRow", "dislikedRow", "editorial")
        assertTrue(e.selectHomeRows(all - yourMixes).ids.none { it in notKept })
    }

    @Test
    fun mutedArtistCountsAsDislikedEvenWhenLoved() = runTest { // Review Focus 1
        val e = engine(controls = UserControls(mutedArtists = setOf(ArtistKey.of("Alpha"))))
        assertEquals(1.0, e.scoreRow(alphaRow.items).dislikedShare, 1e-9)
        assertEquals(emptyList(), e.selectHomeRows(listOf(alphaRow)).ids)
        // One "not interested" on a single song does not count the whole artist as disliked.
        val lessLike = engine(controls = UserControls(lessLikeThisArtists = mapOf(ArtistKey.of("Alpha") to 1)))
        assertEquals(listOf("alphaRow"), lessLike.selectHomeRows(listOf(alphaRow)).ids)
        // "Less like this" on the artist itself (two marks) does.
        val lessArtist = engine(controls = UserControls().apply(LessLikeThis.Artist(ArtistKey.of("Alpha"))))
        assertEquals(emptyList(), lessArtist.selectHomeRows(listOf(alphaRow)).ids)
    }

    @Test
    fun coldStartShowsFirstThreeRowsUnscored() = runTest {
        val cold = engine(finishes("Alpha", 10, t0)).selectHomeRows(all)
        assertTrue(cold.coldStart)
        assertEquals(listOf("dislikedRow", "unknownRow", "betaRow"), cold.ids)
        assertTrue(engine(finishes("Alpha", 49, t0)).selectHomeRows(all).coldStart)
        assertFalse(engine(finishes("Alpha", 50, t0)).selectHomeRows(all).coldStart)
        assertEquals(emptyList(), engine(emptyList()).selectHomeRows(emptyList()).ids)
    }

    @Test
    fun shownRowsKeepTheirSlotsWhenBetterRowsArrive() = runTest { // Review Focus 3
        val e = engine()
        val first = e.selectHomeRows(listOf(betaRow, mixedRow)).ids
        assertEquals(listOf("betaRow", "mixedRow"), first)
        val later = e.selectHomeRows(listOf(betaRow, mixedRow, alphaRow), sticky = first)
        assertEquals(listOf("betaRow", "mixedRow", "alphaRow"), later.ids)
        // A sticky row that left the candidates frees its slot.
        val gone = e.selectHomeRows(listOf(alphaRow, mixedRow), sticky = listOf("goneRow", "mixedRow"))
        assertEquals(listOf("mixedRow", "alphaRow"), gone.ids)
        // A sticky row that no longer passes (artist muted since) is dropped.
        val muted = engine(controls = UserControls(mutedArtists = setOf(ArtistKey.of("Beta"))))
        assertEquals(listOf("alphaRow"), muted.selectHomeRows(listOf(betaRow, alphaRow), sticky = listOf("betaRow")).ids)
    }

    @Test
    fun matchesTasteKeepsKnownLikedItemsOnly() = runTest {
        val items =
            tracks("Alpha", 1) + RowItem.Artist(ArtistKey.of("Delta")) +
                RowItem.Collection(listOf(ArtistKey.of("Gamma"))) + RowItem.Collection(emptyList()) + RowItem.Artist(ArtistKey.of("Beta"))
        assertEquals(listOf(true, false, false, false, true), engine().matchesTaste(items))
        assertEquals(List(5) { true }, engine(emptyList()).matchesTaste(items)) // cold start: unfiltered
    }

    @Test
    fun jumpBackInListsRecentFinishedSongsNewestFirstOnce() = runTest { // Review Focus 2
        val a1 = track("a1", "Alpha")
        val a2 = track("a2", "Alpha")
        val a3 = track("a3", "Alpha")
        val a1Video = track("a1v", "Alpha", title = "song a1") // the same song as another upload
        val events =
            listOf(
                play(track("old", "Alpha"), t0 - 31 * DAY_MS), // outside the 30-day window
                play(a1, t0),
                play(track("m1", "Beta"), t0 + 2 * MINUTE_MS).copy(source = EventSource.METROLIST_IMPORT),
                play(a2, t0 + 5 * MINUTE_MS),
                play(a1, t0 + 10 * MINUTE_MS),
                play(track("b1", "Beta"), t0 + 15 * MINUTE_MS, end = EndReason.SKIPPED), // not finished
                play(a3, t0 + 20 * MINUTE_MS),
                play(a1Video, t0 + 25 * MINUTE_MS),
                play(track("sp", "Gamma"), t0 + 30 * MINUTE_MS).copy(source = EventSource.SPOTIFY_IMPORT), // not on-device
            )
        val keys = { list: List<TrackMeta> -> list.map { it.key.value } }
        assertEquals(listOf("yt:a1v", "yt:a3", "yt:a2", "yt:m1"), keys(engine(events).jumpBackIn(10)))
        assertEquals(listOf("yt:a1v", "yt:a3"), keys(engine(events).jumpBackIn(2)))
        val lessA3 = engine(events, UserControls(lessLikeThisTracks = setOf(a3.key)))
        assertEquals(listOf("yt:a1v", "yt:a2", "yt:m1"), keys(lessA3.jumpBackIn(10)))
        assertEquals(listOf("yt:m1"), keys(engine(events, UserControls(mutedArtists = setOf(ArtistKey.of("Alpha")))).jumpBackIn(10)))
    }
}
