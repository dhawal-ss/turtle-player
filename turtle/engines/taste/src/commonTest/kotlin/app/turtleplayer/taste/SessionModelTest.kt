package app.turtleplayer.taste

import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionModelTest {
    private val world = SyntheticWorld()
    private val genreA = world.tracksOfGenre(0)
    private val genreB = world.tracksOfGenre(1)
    private val longTerm = world.genreCentroids[1]

    private fun vec(i: Int, tracks: List<TrackMeta>) = world.trackVectors.getValue(tracks[i].key)

    private fun sessionInGenreA(): SessionModel {
        val s = SessionModel()
        s.longTerm = longTerm
        for (i in 0 until 5) s.onPlay(play(genreA[i], i * 4 * MINUTE_MS), vec(i, genreA))
        return s
    }

    @Test
    fun finishedTracksPullTheSessionTowardThem() {
        val s = sessionInGenreA()
        assertTrue(s.vector()!!.cosine(world.genreCentroids[0]) > 0.8f)
        assertTrue(s.vector()!!.cosine(longTerm) < 0.5f)
    }

    @Test
    fun emptySessionFallsBackToLongTermTaste() {
        val s = SessionModel()
        s.longTerm = longTerm
        assertEquals(1f, s.vector()!!.cosine(longTerm), 1e-5f)
    }

    @Test
    fun quickSkipsPushAway() {
        val s = sessionInGenreA()
        val before = s.vector()!!.cosine(world.genreCentroids[0])
        s.onPlay(play(genreA[6], 30 * MINUTE_MS, EndReason.SKIPPED, msPlayed = 5_000), vec(6, genreA))
        assertTrue(s.vector()!!.cosine(world.genreCentroids[0]) < before)
    }

    @Test
    fun threeQuickSkipsInARowTriggerAVibeChangeTowardLongTermTaste() {
        val s = sessionInGenreA()
        val before = s.vector()!!.cosine(longTerm)
        val skips = (6..8).map { i ->
            s.onPlay(play(genreA[i], (30 + i) * MINUTE_MS, EndReason.SKIPPED, msPlayed = 4_000), vec(i, genreA))
        }
        assertEquals(listOf(false, false, true), skips)
        assertEquals(1, s.vibeChanges)
        assertEquals(0, s.consecutiveQuickSkips)
        val after = s.vector()!!.cosine(longTerm)
        assertTrue(after > 0.9f && after > before + 0.4f, "after vibe change cos to long-term $after (before $before)")
    }

    @Test
    fun aFinishBetweenSkipsResetsTheCounter() {
        val s = sessionInGenreA()
        s.onPlay(play(genreA[6], 0, EndReason.SKIPPED, msPlayed = 4_000), vec(6, genreA))
        s.onPlay(play(genreA[7], 0, EndReason.SKIPPED, msPlayed = 4_000), vec(7, genreA))
        s.onPlay(play(genreB[0], 0), vec(0, genreB))
        assertFalse(s.onPlay(play(genreA[8], 0, EndReason.SKIPPED, msPlayed = 4_000), vec(8, genreA)))
        assertEquals(0, s.vibeChanges)
    }

    @Test
    fun lateSkipsAreNotQuickSkips() {
        val s = sessionInGenreA()
        repeat(3) { i -> s.onPlay(play(genreA[6 + i], 0, EndReason.SKIPPED, msPlayed = 90_000), vec(6 + i, genreA)) }
        assertEquals(0, s.vibeChanges)
    }

    @Test
    fun moodChipNudgesTowardTarget() {
        val s = sessionInGenreA()
        val mood = world.genreCentroids[2]
        val before = s.vector()!!.cosine(mood)
        s.setMood(mood, 0.5f)
        assertTrue(s.vector()!!.cosine(mood) > before + 0.3f)
        s.setMood(null)
        assertEquals(before, s.vector()!!.cosine(mood), 1e-5f)
    }
}
