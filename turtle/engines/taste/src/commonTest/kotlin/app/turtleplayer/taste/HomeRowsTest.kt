package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
        config: TasteConfig = TasteConfig(),
        clock: Clock = MutableClock((events.maxOfOrNull { it.startedAtMs } ?: t0) + HOUR_MS),
    ) = TasteEngine(
        events = InMemoryPlayEventStore().also { it.appendAll(events) },
        store = InMemoryEmbeddingStore(),
        catalog = InMemoryTrackCatalog(emptyList()),
        config = config,
        clock = clock,
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

    // ---- Song order on Matched rows ------------------------------------------------------------

    /** [warm] plus [extra], in time order. */
    private fun warmWith(vararg extra: PlayEvent) = (warm + extra).sortedBy { it.startedAtMs }

    private fun liked(
        t: TrackMeta,
        atMs: Long,
    ) = play(t, atMs, liked = true)

    /** The row's display order; always a complete permutation of its indices. */
    private suspend fun TasteEngine.order(tracks: List<TrackMeta>): List<Int> {
        val order = orderRowSongs(assertNotNull(rowSongSupport(tracks)), HomeRowConfig().openerSize)
        assertEquals(tracks.indices.toList(), order.sorted(), "not a permutation: $order")
        return order
    }

    private val newDelta = track("newdelta", "Delta")

    @Test
    fun betterSupportedKnownSongLeadsAMixedRow() = runTest { // replay case 1
        val skipped = track("x", "Alpha")
        val events = warmWith(*Array(3) { play(skipped, t0 + 12 * HOUR_MS + it * MINUTE_MS, end = EndReason.SKIPPED) })
        // The thrice quick-skipped song came first from the provider; a finished one now leads.
        assertEquals(listOf(2, 1, 0), engine(events).order(listOf(skipped, newDelta, track("alpha5", "Alpha"))))
    }

    @Test
    fun theArtistCapNeverPutsASkippedSongAheadOfADeferredOne() {
        val a2 = SongSupport(0.7, heard = true, disliked = false, artist = ArtistKey("a"), song = TrackKey("meta:a2"))
        val a1 = SongSupport(0.8, heard = true, disliked = false, artist = ArtistKey("a"), song = TrackKey("meta:a1"))
        val skippedB = SongSupport(-0.4, heard = true, disliked = false, artist = ArtistKey("b"), song = TrackKey("meta:b1"))
        val neutralC = SongSupport(0.0, heard = false, disliked = false, artist = ArtistKey("c"), song = TrackKey("meta:c1"))
        assertEquals(listOf(1, 0, 2), orderRowSongs(listOf(a2, a1, skippedB), 3))
        // A neutral song may take the slot the cap left open; the skipped one still waits.
        assertEquals(listOf(1, 3, 0, 2), orderRowSongs(listOf(a2, a1, skippedB, neutralC), 3))
    }

    @Test
    fun aLovedSongByANetDislikedArtistKeepsItsPlace() = runTest {
        // Gamma's five quick skips outweigh one liked Gamma song, so the artist is net negative.
        val loved = track("gamma9", "Gamma")
        val e = engine(warmWith(liked(loved, t0 + 3 * DAY_MS)))
        val row = listOf(newDelta, loved, track("gammas0", "Gamma"))
        val supports = assertNotNull(e.rowSongSupport(row))
        assertFalse(supports[1].disliked)
        assertTrue(supports[2].disliked)
        assertEquals(listOf(1, 0, 2), e.order(row))
    }

    @Test
    fun recencyIsMeasuredFromTheClockNotTheProfile() = runTest {
        val loved = track("alpha29", "Alpha")
        val events = warmWith(liked(loved, t0 + 3 * DAY_MS))
        val clock = MutableClock(t0 + 3 * DAY_MS + HOUR_MS)
        val e = engine(events, clock = clock)
        val justPlayed = assertNotNull(e.rowSongSupport(listOf(loved))).single().score
        // Nothing new is played, so the profile's own time stays put; the clock moves on.
        clock.now = t0 + 3 * DAY_MS + 10 * HOUR_MS
        val later = assertNotNull(e.rowSongSupport(listOf(loved))).single().score
        assertTrue(later > justPlayed, "$justPlayed then $later")
    }

    @Test
    fun tiesAndUnsupportedRowsKeepProviderOrder() = runTest {
        val unknown = List(4) { track("u$it", "Unknown$it") }
        assertEquals(listOf(0, 1, 2, 3), engine().order(unknown))
        val equal = List(5) { SongSupport(0.5, heard = true, disliked = false, artist = ArtistKey("a$it"), song = TrackKey("meta:s$it")) }
        assertEquals(listOf(0, 1, 2, 3, 4), orderRowSongs(equal, 3))
    }

    @Test
    fun openerHoldsOneSongPerArtistTopicChannelIncluded() = runTest {
        val loved = track("alpha29", "Alpha")
        val e = engine(warmWith(liked(loved, t0 + 3 * HOUR_MS)))
        val row = listOf(track("alpha0", "Alpha - Topic"), loved, track("beta5", "Beta"), newDelta)
        // The "- Topic" upload outscores the unknown Delta song but is the same artist as the leader.
        assertEquals(listOf(1, 2, 3, 0), e.order(row))
        // A single-artist row is simply best first.
        val alphas = listOf(track("alpha0", "Alpha"), loved, track("alpha1", "Alpha - Topic"))
        assertEquals(1, e.order(alphas).first())
    }

    @Test
    fun twoUploadsOfOneSongNeverBothOpen() = runTest {
        val audio = track("alpha29", "Alpha")
        val video = track("alpha29v", "Alpha", title = "song alpha29")
        val e = engine(warmWith(liked(audio, t0 + 3 * HOUR_MS)))
        val row = listOf(audio, video, track("beta5", "Beta"), newDelta)
        val supports = assertNotNull(e.rowSongSupport(row))
        assertEquals(supports[0].song, supports[1].song)
        assertTrue(supports[1].heard, "a heard twin counts as heard")
        val order = e.order(row)
        assertFalse(0 in order.take(3) && 1 in order.take(3), "$order")
        // The song cap holds on its own, even across artist keys.
        val twins =
            listOf(
                SongSupport(0.9, heard = true, disliked = false, artist = ArtistKey("a"), song = TrackKey("meta:x")),
                SongSupport(0.8, heard = true, disliked = false, artist = ArtistKey("b"), song = TrackKey("meta:x")),
                SongSupport(0.7, heard = true, disliked = false, artist = ArtistKey("c"), song = TrackKey("meta:y")),
                SongSupport(0.6, heard = true, disliked = false, artist = ArtistKey("d"), song = TrackKey("meta:z")),
            )
        assertEquals(listOf(0, 2, 3, 1), orderRowSongs(twins, 3))
    }

    @Test
    fun supportedUnheardSongTakesTheLastOpenerSlot() = runTest {
        val a = track("alpha5", "Alpha")
        val b = track("beta5", "Beta")
        val e5 = track("eps5", "Eps")
        val events =
            warmWith(
                *finishes("Eps", 10, t0 + 30 * HOUR_MS).toTypedArray(),
                *finishes("Zeta", 10, t0 + 33 * HOUR_MS).toTypedArray(),
                liked(a, t0 + 36 * HOUR_MS),
                liked(b, t0 + 36 * HOUR_MS + MINUTE_MS),
                liked(e5, t0 + 36 * HOUR_MS + 2 * MINUTE_MS),
            )
        val e = engine(events)
        val withZeta = e.order(listOf(a, b, e5, track("newzeta", "Zeta"), newDelta))
        assertEquals(3, withZeta[2], "the new Zeta song opens: $withZeta")
        assertEquals(setOf(0, 1, 2), (withZeta.take(2) + withZeta[3]).toSet())
        assertEquals(4, withZeta[4])
        // An unheard song by an unknown artist has no support and does not displace a liked one.
        val withDelta = e.order(listOf(a, b, e5, newDelta, track("newdelta2", "Delta")))
        assertEquals(setOf(0, 1, 2), withDelta.take(3).toSet())
        assertEquals(listOf(3, 4), withDelta.drop(3))
    }

    @Test
    fun dislikedSongsGoLastAndAreNeverDropped() = runTest {
        val ruledOut = track("alpha6", "Alpha")
        val e = engine(controls = UserControls(lessLikeThisTracks = setOf(ruledOut.key)))
        val row = listOf(track("newgamma", "Gamma"), ruledOut, track("alpha5", "Alpha"), newDelta)
        val supports = assertNotNull(e.rowSongSupport(row))
        assertEquals(listOf(true, true, false, false), supports.map { it.disliked })
        assertEquals(listOf(2, 3, 0, 1), e.order(row))
        // Even when the artist cap leaves the opener short, a disliked song does not fill it.
        val alphas = listOf(track("alpha5", "Alpha"), track("alpha6x", "Alpha"), track("alpha7", "Alpha"), track("newgamma", "Gamma"))
        assertEquals(3, e.order(alphas).last())
    }

    @Test
    fun aSongHeardInTheLastHoursIsDemoted() = runTest {
        val replayed = track("beta5", "Beta")
        val events = warmWith(play(replayed, t0 + 2 * DAY_MS + 2 * HOUR_MS)) // an hour before now
        val row = listOf(replayed, track("alpha5", "Alpha"))
        assertEquals(listOf(1, 0), engine(events).order(row))
        val noGap = TasteConfig(homeRows = HomeRowConfig(recentGapMs = 0))
        assertEquals(listOf(0, 1), engine(events, config = noGap).order(row))
    }

    @Test
    fun coldStartKeepsProviderOrder() = runTest {
        val row = listOf(track("alpha5", "Alpha"))
        assertNull(engine(finishes("Alpha", 49, t0)).rowSongSupport(row))
        assertNotNull(engine(finishes("Alpha", 50, t0)).rowSongSupport(row))
    }

    @Test
    fun ordersRowFollowsTheSettingAndAlternatesDaily() = runTest {
        val clock = MutableClock(t0 + 10 * HOUR_MS)
        suspend fun with(order: SongOrder) = engine(config = TasteConfig(homeRows = HomeRowConfig(songOrder = order)), clock = clock)
        val keys = List(20) { "row$it" }
        assertTrue(keys.all(with(SongOrder.ON)::ordersRow))
        assertTrue(keys.none(with(SongOrder.OFF)::ordersRow))
        val alternate = with(SongOrder.ALTERNATE)
        val today = keys.map(alternate::ordersRow)
        assertEquals(today, keys.map(alternate::ordersRow), "deterministic within a day")
        assertTrue(true in today && false in today, "both arms on one day: $today")
        clock.now += DAY_MS
        assertEquals(today.map { !it }, keys.map(alternate::ordersRow), "each row flips the next day")
    }
}
