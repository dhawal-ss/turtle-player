package app.turtleplayer.taste

import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TasteProfileTest {
    private val world = SyntheticWorld()
    private val now = SyntheticWorld.T0
    private val rep = TrackRepresentation(world.store)

    @Test
    fun signalsRaiseAndLowerAffinity() {
        val (a, b, c, d) = world.tracks.take(4)
        val builder = TasteProfileBuilder()
        builder.add(play(a, now - HOUR_MS))
        builder.add(play(b, now - HOUR_MS, liked = true, replayed = true))
        builder.add(play(c, now - HOUR_MS, EndReason.SKIPPED, msPlayed = 5_000))
        builder.add(play(d, now - HOUR_MS, lessLikeThis = true, end = EndReason.SKIPPED, msPlayed = 5_000))
        val p = builder.snapshot(now, rep)
        val aff = { t: TrackMeta -> p.track(t.key)!!.affinity }
        assertTrue(aff(b) > aff(a) && aff(a) > 0)
        assertTrue(aff(c) < 0 && aff(d) < aff(c))
        assertTrue(p.track(d.key)!!.lessLikeThis)
    }

    @Test
    fun hearingOneUploadOfASongMeansHearingTheOther() {
        val video = TrackMeta(TrackKey.youtube("vid"), "Song Name (Official Music Video)", listOf("Some Artist"))
        val audio = TrackMeta(TrackKey.youtube("aud"), "Song Name", listOf("Some Artist"))
        val p = TasteProfileBuilder().apply { add(play(video, now - HOUR_MS)) }.snapshot(now, null)
        assertTrue(p.hasHeard(audio))
        assertFalse(p.hasHeard(audio.key))
    }

    @Test
    fun firstPlaySkippedBeforeHalfwayIsNotANewFind() {
        val (skippedEarly, heldOn) = world.tracks.take(2)
        val p = TasteProfileBuilder().apply {
            // Both are first plays that held past a minute of a 200 s song.
            add(play(skippedEarly, now - HOUR_MS, EndReason.SKIPPED, msPlayed = 61_000))
            add(play(heldOn, now - HOUR_MS, EndReason.SKIPPED, msPlayed = 150_000))
        }.snapshot(now, rep)
        assertEquals(null, p.track(skippedEarly.key)!!.foundMs)
        assertTrue(p.track(skippedEarly.key)!!.affinity < 0)
        assertTrue(p.track(heldOn.key)!!.foundMs != null)
        assertTrue(p.track(heldOn.key)!!.affinity > 0)
    }

    @Test
    fun affinityHalvesEveryNinetyDaysRegardlessOfEventOrder() {
        val t = world.tracks[0]
        val inOrder = TasteProfileBuilder().apply {
            add(play(t, now - 90 * DAY_MS))
            add(play(t, now))
        }.snapshot(now, null)
        val reversed = TasteProfileBuilder().apply {
            add(play(t, now))
            add(play(t, now - 90 * DAY_MS)) // an import arriving late
        }.snapshot(now, null)
        assertEquals(1.5, inOrder.track(t.key)!!.affinity, 1e-6)
        assertEquals(1.5, reversed.track(t.key)!!.affinity, 1e-6)
        val later = TasteProfileBuilder().apply { add(play(t, now)) }.snapshot(now + 90 * DAY_MS, null)
        assertEquals(0.5, later.track(t.key)!!.affinity, 1e-6)
    }

    @Test
    fun skipRatesPerArtistAndSourceAndFatigueWindow() {
        val artistTracks = world.tracksOf(world.artists[0])
        val builder = TasteProfileBuilder()
        val tag = SurfaceTag.format(CandidateSource.COLLABORATIVE, SlotKind.DISCOVERY)
        artistTracks.take(4).forEachIndexed { i, t ->
            builder.add(play(t, now - (i + 1) * 10 * 60_000L, EndReason.SKIPPED, msPlayed = 3_000, surfaceDetail = tag))
        }
        builder.add(play(artistTracks[4], now - 3 * DAY_MS))
        val p = builder.snapshot(now, rep)
        val artist = p.artist(world.artists[0])!!
        assertEquals(5, artist.plays)
        assertEquals(4, artist.quickSkips)
        assertTrue(artist.skips.rate() > 0.5)
        assertEquals(SkipStats(4, 4), p.sources[CandidateSource.COLLABORATIVE])
        assertEquals(4, p.recentPlays.size, "only the last 24 h count for fatigue")
    }

    @Test
    fun rediscoveryWindowIsSixtyDays() = runTest {
        val loved70 = world.tracks[0]
        val loved30 = world.tracks[1]
        val skipped70 = world.tracks[2]
        val onceFinished70 = world.tracks[3]
        val builder = TasteProfileBuilder()
        repeat(4) { builder.add(play(loved70, now - (70 + it) * DAY_MS)) }
        repeat(4) { builder.add(play(loved30, now - (30 + it) * DAY_MS)) }
        repeat(4) { builder.add(play(skipped70, now - (70 + it) * DAY_MS, EndReason.SKIPPED, msPlayed = 3_000)) }
        builder.add(play(onceFinished70, now - 70 * DAY_MS))
        val profile = builder.snapshot(now, rep)
        val request = GenerationRequest(profile, UserControls(), now, sessionVector = null)
        val got = RediscoveryGenerator().generate(request, 10)
        assertEquals(listOf(loved70.key), got.map { it.key })
        val reason = got.single().reason as Reason.Rediscover
        assertEquals(now - 70 * DAY_MS, reason.lastPlayedMs)
        assertTrue(got.single().source == CandidateSource.REDISCOVERY)
        // "Less like this" removes it.
        val muted = GenerationRequest(profile, UserControls(lessLikeThisTracks = setOf(loved70.key)), now, sessionVector = null)
        assertTrue(RediscoveryGenerator().generate(muted, 10).isEmpty())
    }

    @Test
    fun clustersFollowGenresAndAreDeterministic() {
        val user = world.user(0)
        val events = world.history(user)
        val p1 = TasteProfileBuilder().apply { addAll(events) }.build(now, rep)
        val p2 = TasteProfileBuilder().apply { addAll(events.reversed()) }.build(now, rep)
        assertTrue(p1.clusters.size in 4..8, "k=${p1.clusters.size}")
        assertEquals(p1.clusters.map { it.members }, p2.clusters.map { it.members })
        // Every cluster sits in one of the two liked genres.
        for (c in p1.clusters) {
            val genre = world.genreCentroids.indices.maxBy { world.genreCentroids[it].cosine(c.centroid) }
            assertTrue(genre in user.likedGenres, "cluster ${c.id} nearest genre $genre not in ${user.likedGenres}")
        }
        assertEquals(1.0, p1.clusters.sumOf { it.weight }, 1e-6)
        assertTrue(p1.clusters.zipWithNext().all { (a, b) -> a.weight >= b.weight })
        // Long-term vector lies between the liked genres; dislikes point at the skipped ones.
        val liked = user.likedGenres.map { world.genreCentroids[it] }
        assertTrue(liked.all { p1.longTermVector!!.cosine(it) > 0.4f })
        val disliked = world.genreCentroids.indices.filter { it !in user.likedGenres }
        assertTrue(disliked.any { p1.dislikeVector!!.cosine(world.genreCentroids[it]) > 0.3f })
    }

    @Test
    fun chooseKScalesWithData() {
        assertEquals(0, SphericalKMeans.chooseK(0))
        assertEquals(1, SphericalKMeans.chooseK(3))
        assertEquals(4, SphericalKMeans.chooseK(32))
        assertEquals(8, SphericalKMeans.chooseK(5000))
        assertFalse(SphericalKMeans.chooseK(40) > 8)
    }
}
