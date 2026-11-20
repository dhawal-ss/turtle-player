package app.turtleplayer.taste

import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QueueAssemblerTest {
    private val world = SyntheticWorld()
    private val now = SyntheticWorld.T0
    private val assembler = QueueAssembler()

    private fun scored(track: TrackMeta, score: Float, heard: Boolean = true): ScoredCandidate {
        val f = FeatureVector()
        f[Feature.HEARD_BEFORE] = if (heard) 1f else 0f
        return ScoredCandidate(Candidate(track, CandidateSource.SESSION, Reason.SimilarTo(track.artistKey), 1f), null, f, score)
    }

    /** One artist dominates the ranking; others trail. */
    private val dominant = world.tracksOf(world.artists[0])
    private val ranked: List<ScoredCandidate> =
        dominant.mapIndexed { i, t -> scored(t, 0.99f - i * 0.001f) } +
            world.artists.drop(1).take(12).mapIndexed { i, a -> scored(world.tracksOf(a)[0], 0.5f - i * 0.01f) }

    private fun familiarSlots(n: Int) = List(n) { SlotKind.FAMILIAR }

    private fun assertSpacing(queue: List<QueueItem>, lead: List<TrackMeta> = emptyList()) {
        val artists = lead.map { it.artistKey } + queue.map { it.track.artistKey }
        for (i in artists.indices) {
            val window = artists.subList(maxOf(0, i - 3), i)
            assertFalse(artists[i] in window, "artist ${artists[i]} repeats within 3 tracks at $i: $artists")
        }
    }

    @Test
    fun neverTheSameArtistWithinThreeTracks() {
        val q = assembler.assemble(emptyList(), ranked, familiarSlots(12), emptyList(), null, RecentlyRecommended(), now)
        assertEquals(12, q.size)
        assertSpacing(q)
        // The dominant artist is still used as often as spacing allows (positions 0, 4, 8).
        assertEquals(listOf(0, 4, 8), q.indices.filter { q[it].track.artistKey == world.artists[0] })
    }

    @Test
    fun spacingCountsNowPlayingAndRecentHistory() {
        val nowPlaying = dominant[5]
        val history = listOf(world.tracksOf(world.artists[1])[3]).map {
            RecentPlay(it.key, it.artistKey, it.albumKey, now - 5 * MINUTE_MS, false)
        }
        val q = assembler.assemble(emptyList(), ranked, familiarSlots(8), history, nowPlaying, RecentlyRecommended(), now)
        assertSpacing(q, lead = listOf(world.tracksOf(world.artists[1])[3], nowPlaying))
        assertTrue(q.take(3).none { it.track.artistKey == world.artists[0] })
    }

    @Test
    fun atMostTwoTracksPerAlbumPerHourIncludingRecentPlays() {
        // Album "a0 album 0" = dominant[0..2]; give it the top scores and loosen spacing to isolate the album cap.
        val rules = QueueRules(artistSpacing = 0)
        val q = QueueAssembler(rules).assemble(emptyList(), ranked, familiarSlots(10), emptyList(), null, RecentlyRecommended(), now)
        val album = dominant[0].albumKey
        assertEquals(2, q.count { it.track.albumKey == album })

        val history = listOf(RecentPlay(dominant[2].key, dominant[2].artistKey, album, now - 20 * MINUTE_MS, false))
        val q2 = QueueAssembler(rules).assemble(emptyList(), ranked, familiarSlots(10), history, null, RecentlyRecommended(), now)
        assertEquals(1, q2.count { it.track.albumKey == album })

        // An hour later the album is allowed again.
        val oldHistory = listOf(RecentPlay(dominant[2].key, dominant[2].artistKey, album, now - 70 * MINUTE_MS, false))
        val q3 = QueueAssembler(rules).assemble(emptyList(), ranked, familiarSlots(10), oldHistory, null, RecentlyRecommended(), now)
        assertEquals(2, q3.count { it.track.albumKey == album })
    }

    @Test
    fun noRepeatsOfRecentlyRecommendedOrRecentlyPlayed() {
        val recent = RecentlyRecommended()
        recent.add(dominant[0].key)
        val history = listOf(RecentPlay(dominant[1].key, dominant[1].artistKey, dominant[1].albumKey, now - 2 * 60 * MINUTE_MS, false))
        val q = QueueAssembler(QueueRules(artistSpacing = 0, maxPerAlbumPerHour = 10))
            .assemble(emptyList(), ranked, familiarSlots(5), history, null, recent, now)
        assertFalse(q.any { it.key == dominant[0].key || it.key == dominant[1].key })
        // Tracks of the tail being re-ranked may come back.
        val q2 = QueueAssembler(QueueRules(artistSpacing = 0, maxPerAlbumPerHour = 10))
            .assemble(emptyList(), ranked, familiarSlots(5), emptyList(), null, recent, now, allowAgain = setOf(dominant[0].key))
        assertEquals(dominant[0].key, q2.first().key)
    }

    @Test
    fun recentMemoryIsBounded() {
        val recent = RecentlyRecommended(500)
        val keys = List(501) { TrackKey("yt:$it") }
        recent.addAll(keys)
        assertEquals(500, recent.size)
        assertFalse(keys[0] in recent)
        assertTrue(keys[500] in recent)
        val restored = RecentlyRecommended(500).also { it.restore(recent.toList()) }
        assertEquals(recent.toList(), restored.toList())
    }

    @Test
    fun followsTheSlotPlanAndFallsBackWhenAPoolRunsDry() {
        val familiar = world.artists.take(6).map { scored(world.tracksOf(it)[0], 0.9f, heard = true) }
        val discovery = world.artists.drop(6).take(2).map { scored(world.tracksOf(it)[0], 0.4f, heard = false) }
        val plan = listOf(SlotKind.FAMILIAR, SlotKind.DISCOVERY, SlotKind.FAMILIAR, SlotKind.DISCOVERY, SlotKind.FAMILIAR, SlotKind.DISCOVERY)
        val q = assembler.assemble(emptyList(), familiar + discovery, plan, emptyList(), null, RecentlyRecommended(), now)
        assertEquals(listOf(SlotKind.FAMILIAR, SlotKind.DISCOVERY, SlotKind.FAMILIAR, SlotKind.DISCOVERY, SlotKind.FAMILIAR, SlotKind.FAMILIAR), q.map { it.slot })
        assertTrue(q.all { it.surfaceDetail == SurfaceTag.format(CandidateSource.SESSION, it.slot) })
    }

    @Test
    fun frozenItemsStayAtTheHead() {
        val frozen = ranked.drop(8).take(2).map { QueueItem.of(it) }
        val q = assembler.assemble(frozen, ranked, familiarSlots(6), emptyList(), null, RecentlyRecommended(), now)
        assertEquals(frozen, q.take(2))
        assertEquals(8, q.size)
        assertSpacing(q)
    }
}
