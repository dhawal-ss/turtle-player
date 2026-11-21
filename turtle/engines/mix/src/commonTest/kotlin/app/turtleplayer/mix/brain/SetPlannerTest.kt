package app.turtleplayer.mix.brain

import app.turtleplayer.mix.Familiarity
import app.turtleplayer.mix.PickReason
import app.turtleplayer.mix.TrackFacts
import app.turtleplayer.mix.VibeSettings
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetPlannerTest {

    private val vibe = VibeSettings()
    private fun ctx(onAir: TrackFacts?, history: List<TrackFacts> = emptyList(), skipped: Set<String> = emptySet(), liked: Set<String> = emptySet(), upcoming: List<TrackFacts> = emptyList()) =
        SetContext(onAir, history, skipped, liked, upcoming)

    /** The candidate picked most often over [n] seeds. */
    private fun winner(cands: List<TrackFacts>, c: SetContext, target: Float, v: VibeSettings = vibe, n: Int = 50): TrackFacts =
        (0 until n).map { SetPlanner.pick(cands, c, target, v, Random(it))!!.track }.groupingBy { it }.eachCount().maxBy { it.value }.key

    private fun alwaysPicks(expected: TrackFacts, cands: List<TrackFacts>, c: SetContext, target: Float, v: VibeSettings = vibe) {
        repeat(50) { assertEquals(expected.id, SetPlanner.pick(cands, c, target, v, Random(it))!!.track.id, "seed $it") }
    }

    @Test
    fun emptyCandidatesGiveNull() {
        assertNull(SetPlanner.pick(emptyList(), ctx(null), 0.5f, vibe, Random(1)))
    }

    @Test
    fun energyFitDominates() {
        val onAir = track(id = "on", bpm = 124f, energy = 0.6f, genre = "house")
        // The off-energy one has everything else going for it: same genre, exact tempo, a favourite.
        val fits = track(id = "fit", energy = 0.8f, bpm = 140f, genre = "techno", familiarity = Familiarity.New)
        val tempting = track(id = "tempting", energy = 0.5f, bpm = 124f, genre = "house", familiarity = Familiarity.Favorite)
        alwaysPicks(fits, listOf(tempting, fits), ctx(onAir), 0.8f)
        val e = SetPlanner.explain(fits, ctx(onAir), 0.8f, vibe)
        val t = SetPlanner.explain(tempting, ctx(onAir), 0.8f, vibe)
        assertTrue(e.energy - t.energy > (t.tempo + t.genre + t.familiarity) - (e.tempo + e.genre + e.familiarity))
    }

    @Test
    fun measuredEnergyIsTrustedMore() {
        val measured = track(id = "m", energy = 0.7f)
        val guessed = track(id = "g", energy = 0.7f, energyMeasured = false)
        assertEquals(measured, winner(listOf(guessed, measured), ctx(null), 0.7f))
        // Far from the target, an estimate costs less than a measurement that is surely off.
        val c = ctx(null)
        assertTrue(SetPlanner.explain(track(energy = 0.2f, energyMeasured = false), c, 0.7f, vibe).energy >
            SetPlanner.explain(track(energy = 0.2f), c, 0.7f, vibe).energy)
    }

    @Test
    fun tempoFlowPrefersCloseTemposForDance() {
        val onAir = track(id = "on", bpm = 126f, energy = 0.7f)
        val close = track(id = "close", bpm = 128f, energy = 0.7f)
        val far = track(id = "far", bpm = 145f, energy = 0.7f)
        val half = track(id = "half", bpm = 63.5f, energy = 0.7f)
        val c = ctx(onAir)
        alwaysPicks(close, listOf(far, close), c, 0.7f)
        val ex = { t: TrackFacts, v: VibeSettings -> SetPlanner.explain(t, c, 0.7f, v).tempo }
        assertEquals(SetPlanner.TEMPO_BONUS, ex(close, VibeSettings(flair = 0f)), 1e-9)
        assertEquals(ex(close, vibe), ex(half, vibe), 1e-9)
        assertTrue(ex(far, vibe) < 0.0)
        // Tricks hide tempo jumps: high flair cares less.
        assertTrue(ex(far, VibeSettings(flair = 1f)) > ex(far, VibeSettings(flair = 0f)))
        // Non-dance pairs care less.
        val pop = ctx(track(bpm = 126f, genre = "pop"))
        assertTrue(SetPlanner.explain(track(bpm = 145f, genre = "pop"), pop, 0.7f, vibe).tempo > ex(far, vibe))
        assertEquals(listOf(PickReason.HoldingTheGroove, PickReason.TempoMatch), SetPlanner.pick(listOf(close), c, 0.7f, vibe, Random(1))!!.reasons)
    }

    @Test
    fun familiarityLeansTowardWhicheverSideIsBehind() {
        val newOnes = List(6) { track(familiarity = Familiarity.New) }
        val knownOnes = List(6) { track(familiarity = Familiarity.Known) }
        val fav = track(id = "fav", familiarity = Familiarity.Known, energy = 0.6f)
        val fresh = track(id = "fresh", familiarity = Familiarity.New, energy = 0.6f)
        val v = VibeSettings(fresh = 0.5f)
        alwaysPicks(fav, listOf(fresh, fav), ctx(newOnes.last(), newOnes.dropLast(1)), 0.6f, v)
        alwaysPicks(fresh, listOf(fav, fresh), ctx(knownOnes.last(), knownOnes.dropLast(1)), 0.6f, v)
    }

    @Test
    fun genreLaneFollowsVariety() {
        val house = List(3) { track(genre = "house") }
        val c = ctx(house.last(), house.dropLast(1))
        val sameGenre = track(id = "same", genre = "house")
        val other = track(id = "other", genre = "disco")
        alwaysPicks(sameGenre, listOf(other, sameGenre), c, 0.6f, VibeSettings(variety = 0f))
        alwaysPicks(other, listOf(sameGenre, other), c, 0.6f, VibeSettings(variety = 1f))
        assertTrue(PickReason.GenreHop in SetPlanner.pick(listOf(other), c, 0.6f, VibeSettings(variety = 1f, flair = 1f), Random(1))!!.reasons)
        // After one track in a genre, high variety does not push away yet.
        val one = ctx(track(genre = "house"))
        assertEquals(0.0, SetPlanner.explain(other, one, 0.6f, VibeSettings(variety = 1f)).genre)
        assertEquals(0.0, SetPlanner.explain(sameGenre, one, 0.6f, VibeSettings(variety = 1f)).genre)
    }

    @Test
    fun artistsAreSpacedAndTracksNotReplayed() {
        val a = track(artist = "Solomun")
        val history = listOf(a, track(), track())
        val onAir = track()
        val c = ctx(onAir, history)
        val sameArtist = track(id = "same", artist = "Solomun", energy = 0.7f)
        val featuring = track(id = "feat", artist = "Dixon feat. Solomun", energy = 0.7f)
        val ok = track(id = "ok", energy = 0.3f)
        alwaysPicks(ok, listOf(sameArtist, featuring, ok), c, 0.7f)
        // Five tracks later the artist may come back.
        val later = ctx(onAir, listOf(a, track(), track(), track(), track()))
        assertEquals(0.0, SetPlanner.explain(sameArtist, later, 0.7f, vibe).spacing)
        // Planned tracks count too.
        val planned = ctx(onAir, emptyList(), upcoming = listOf(track(artist = "Solomun")))
        assertTrue(SetPlanner.explain(sameArtist, planned, 0.7f, vibe).spacing < -50)
        // No replays.
        alwaysPicks(ok, listOf(history[1].copy(energy = 0.7f), onAir.copy(energy = 0.7f), ok), c, 0.7f)
    }

    @Test
    fun skipsPenaliseArtistAndGenre() {
        val skippedTrack = track(id = "sk", artist = "Boring", genre = "deep house")
        val skipped2 = track(id = "sk2", genre = "deep house")
        val c = ctx(track(genre = "house"), listOf(skippedTrack, skipped2), skipped = setOf("sk", "sk2"))
        val sameArtist = track(id = "a", artist = "Boring", genre = "house", energy = 0.6f)
        val sameGenre = track(id = "g", genre = "deep house", energy = 0.6f)
        val clean = track(id = "c", genre = "disco", energy = 0.6f)
        alwaysPicks(clean, listOf(sameArtist, sameGenre, clean), c, 0.6f)
        assertTrue(SetPlanner.explain(sameArtist, c, 0.6f, vibe).feedback <= SetPlanner.SKIPPED_ARTIST_PENALTY)
        assertEquals(2 * SetPlanner.SKIPPED_GENRE_PENALTY, SetPlanner.explain(sameGenre, c, 0.6f, vibe).feedback, 1e-9)
    }

    @Test
    fun likedArtistsComeBack() {
        val loved = track(id = "lv", artist = "Bicep")
        val c = ctx(track(), listOf(loved, track(), track(), track()), liked = setOf("lv"))
        val bicep = track(id = "b", artist = "Bicep", energy = 0.6f, familiarity = Familiarity.New)
        val other = track(id = "o", energy = 0.6f, familiarity = Familiarity.New)
        alwaysPicks(bicep, listOf(other, bicep), c, 0.6f)
        val pick = SetPlanner.pick(listOf(bicep), c, 0.6f, vibe, Random(1))!!
        assertEquals(PickReason.ArtistYouLike, pick.reasons[1])
    }

    @Test
    fun peakMomentsGoToPopularFavourites() {
        val onAir = track(energy = 0.75f)
        val anthem = track(id = "anthem", energy = 0.86f, familiarity = Familiarity.Favorite, popularity = 0.95f)
        val unknown = track(id = "unknown", energy = 0.88f, familiarity = Familiarity.New, popularity = 0.2f)
        // A balanced set so far (3 of 5 familiar = 1 - fresh): only the moment decides.
        val history = listOf(Familiarity.Known, Familiarity.New, Familiarity.Known, Familiarity.New).map { track(familiarity = it) }
        val c = ctx(onAir, history)
        alwaysPicks(anthem, listOf(unknown, anthem), c, 0.88f)
        assertEquals(listOf(PickReason.PeakMoment, PickReason.Favorite, PickReason.TempoMatch), SetPlanner.pick(listOf(anthem), c, 0.88f, vibe, Random(1))!!.reasons)
        // Not when the arc is coming down from the peak.
        assertEquals(0.0, SetPlanner.explain(anthem, ctx(track(energy = 0.95f)), 0.85f, vibe).peak)
    }

    @Test
    fun requestsAlwaysWin() {
        val req = track(id = "req", energy = 0.1f, requested = true, artist = "x")
        val c = ctx(track(energy = 0.8f, artist = "x"))
        val good = track(id = "good", energy = 0.8f)
        repeat(20) {
            val p = SetPlanner.pick(listOf(good, req), c, 0.8f, vibe, Random(it))!!
            assertEquals("req", p.track.id)
            assertEquals(PickReason.Requested, p.reasons.first())
        }
    }

    @Test
    fun reasonsAreAccurate() {
        val onAir = track(energy = 0.5f, bpm = 124f)
        val c = ctx(onAir)
        fun reasons(t: TrackFacts, target: Float) = SetPlanner.pick(listOf(t), c, target, vibe, Random(1))!!.reasons
        assertEquals(listOf(PickReason.BuildingUp, PickReason.FreshFind, PickReason.TempoMatch), reasons(track(energy = 0.7f, familiarity = Familiarity.New), 0.7f))
        assertEquals(listOf(PickReason.CoolingDown, PickReason.Liked), reasons(track(energy = 0.3f, familiarity = Familiarity.Liked, bpm = 90f, dance = false), 0.3f))
        assertEquals(listOf(PickReason.HoldingTheGroove, PickReason.Favorite, PickReason.TempoMatch), reasons(track(energy = 0.52f, familiarity = Familiarity.Favorite), 0.52f))
        // The arc wants up but the only track goes down: say what actually happens.
        assertEquals(PickReason.CoolingDown, reasons(track(energy = 0.3f), 0.8f).first())
        for (seed in 0 until 30) {
            val pool = syntheticPool(seed, size = 60)
            val p = SetPlanner.pick(pool, c, Random(seed).nextFloat(), vibe, Random(seed))!!
            assertTrue(p.reasons.size in 1..3 && p.reasons.distinct() == p.reasons)
            if (PickReason.FreshFind in p.reasons) assertEquals(Familiarity.New, p.track.familiarity)
            if (PickReason.Favorite in p.reasons) assertEquals(Familiarity.Favorite, p.track.familiarity)
            if (PickReason.TempoMatch in p.reasons) assertTrue(MusicMath.tempoGap(onAir.bpm, p.track.bpm) <= 0.06)
        }
    }

    @Test
    fun picksAreDeterministicButSetsDiffer() {
        val pool = syntheticPool(3)
        val c = ctx(pool[0], pool.subList(1, 4))
        val a = (0 until 20).map { SetPlanner.pick(pool, c, 0.7f, vibe, Random(it)) }
        val b = (0 until 20).map { SetPlanner.pick(pool, c, 0.7f, vibe, Random(it)) }
        assertEquals(a, b)
        assertTrue(a.map { it!!.track.id }.toSet().size > 1, "a little randomness among the top few")
        assertNotEquals(a[0]!!.track.id, "")
    }

    @Test
    fun targetShiftsTheArc() {
        for (arc in listOf(0f, 0.3f, 0.5f, 0.9f, 1f)) {
            assertEquals(arc, SetPlanner.target(arc, VibeSettings(energy = 0.5f)), 1e-6f)
            assertTrue(SetPlanner.target(arc, VibeSettings(energy = 0.9f)) >= arc)
            assertTrue(SetPlanner.target(arc, VibeSettings(energy = 0.1f)) <= arc)
            assertTrue(SetPlanner.target(arc, VibeSettings(energy = 1f)) <= 1f)
            assertTrue(SetPlanner.target(arc, VibeSettings(energy = 0f)) >= 0f)
        }
        assertEquals(0.7f, SetPlanner.target(0.4f, VibeSettings(energy = 1f)), 1e-6f)
        assertEquals(0.2f, SetPlanner.target(0.4f, VibeSettings(energy = 0f)), 1e-6f)
        // The arc's shape survives: higher arc, higher target.
        assertTrue(SetPlanner.target(0.8f, VibeSettings(energy = 0.2f)) > SetPlanner.target(0.4f, VibeSettings(energy = 0.2f)))
        assertFalse(SetPlanner.target(0.6f, VibeSettings(energy = 0.8f)) == SetPlanner.target(0.6f, VibeSettings(energy = 0.2f)))
    }
}
