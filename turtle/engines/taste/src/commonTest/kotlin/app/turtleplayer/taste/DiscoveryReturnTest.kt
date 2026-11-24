package app.turtleplayer.taste

import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import app.turtleplayer.taste.eval.DiscoveryCounts
import app.turtleplayer.taste.eval.DiscoveryGroup
import app.turtleplayer.taste.eval.DiscoveryReport
import app.turtleplayer.taste.eval.DiscoveryReturn
import app.turtleplayer.taste.eval.Maturity
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiscoveryReturnTest {
    private val t0 = 1_793_577_600_000L
    private var position = 0

    private fun song(id: String, artist: String = "artist $id", title: String = "song $id", key: TrackKey = TrackKey.youtube(id)) =
        TrackMeta(key, title, listOf(artist), durationMs = 200_000)

    private fun play(
        track: TrackMeta,
        at: Long,
        origin: PlayOrigin?,
        start: StartReason = StartReason.AUTOPLAY,
        end: EndReason = EndReason.COMPLETED,
        msPlayed: Long = 200_000,
        liked: Boolean = false,
        replayed: Boolean = false,
        source: EventSource = EventSource.LOCAL,
    ) = PlayEvent(
        track = track,
        sessionId = "s",
        position = position++,
        surface = origin?.surface ?: Surface.UNKNOWN,
        startReason = start,
        endReason = end,
        startedAtMs = at,
        msPlayed = msPlayed,
        durationMs = 200_000,
        liked = liked,
        replayed = replayed,
        source = source,
        surfaceDetail = origin?.let(SurfaceTag::format),
    )

    private fun saved(track: TrackMeta, at: Long, kind: TrackFeedbackKind = TrackFeedbackKind.SAVED) =
        TrackFeedback(track = track, kind = kind, atMs = at, detail = if (kind == TrackFeedbackKind.SAVED) TrackFeedback.SAVED_LIKED else "PL1")

    private val radio = PlayOrigin(PlayPlace.RADIO, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, served = Served.RANKED)
    private val search = PlayOrigin(PlayPlace.SEARCH)

    private fun report(
        events: List<PlayEvent>,
        feedback: List<TrackFeedback> = emptyList(),
        now: Long = t0 + 30 * DAY_MS,
        notBeforeMs: Long = 0,
        coldStartPlays: Int = 1_000,
        establishedPlays: Int = 2_000,
    ): DiscoveryReport = DiscoveryReturn.report(events, feedback, Clock { now }, notBeforeMs, coldStartPlays = coldStartPlays, establishedPlays = establishedPlays)

    private val newRadio = DiscoveryGroup(PlayPlace.RADIO, Served.RANKED, Maturity.COLD, newArtist = true)

    @Test
    fun aSongLikedOnItsFirstRadioPlayIsSavedOnceItsWindowCloses() {
        val events = listOf(play(song("a"), t0, radio, liked = true))

        assertEquals(mapOf(newRadio to DiscoveryCounts(pairs = 1, saved = 1, either = 1)), report(events, now = t0 + 7 * DAY_MS).groups)
        assertEquals(mapOf(newRadio to DiscoveryCounts(pending = 1)), report(events, now = t0 + 6 * DAY_MS).groups)
    }

    @Test
    fun aSongHeardBeforeInAnImportOrPickedInSearchMakesNoPair() {
        val imported = song("x", artist = "Band", title = "Hit", key = TrackKey.fromMeta("Band", "Hit"))
        val fromImport = listOf(
            play(imported, t0 - DAY_MS, null, source = EventSource.SPOTIFY_IMPORT),
            play(song("x", artist = "Band", title = "Hit"), t0, radio),
        )
        val r = report(fromImport)
        assertEquals(emptyMap(), r.groups)
        assertEquals(0, r.localFirstPlays)

        val fromSearch = listOf(play(song("y"), t0, search), play(song("y"), t0 + HOUR_MS, radio))
        val s = report(fromSearch)
        assertEquals(emptyMap(), s.groups)
        assertEquals(1, s.localFirstPlays)
    }

    @Test
    fun anotherUploadOfTheSameSongIsNotNewAgain() {
        val video = song("v1", artist = "Band", title = "Hit (Official Video)")
        val audio = song("a1", artist = "Band", title = "Hit")
        val r = report(listOf(play(video, t0, radio), play(audio, t0 + DAY_MS, radio, start = StartReason.TAPPED)))

        assertEquals(DiscoveryCounts(pairs = 1, replayed = 1, either = 1), r.total)
    }

    @Test
    fun onlyDeliberateReplaysInsideTheWindowCount() {
        val events = listOf(
            play(song("tap"), t0, radio), play(song("tap"), t0 + 3 * DAY_MS, null, start = StartReason.TAPPED),
            play(song("prev"), t0, radio), play(song("prev"), t0 + HOUR_MS, radio, start = StartReason.USER_PREVIOUS),
            play(song("repeat"), t0, radio), play(song("repeat"), t0 + DAY_MS, radio, replayed = true),
            play(song("seek"), t0, radio, replayed = true),
            play(song("served"), t0, radio), play(song("served"), t0 + 2 * DAY_MS, radio),
            play(song("late"), t0, radio), play(song("late"), t0 + 8 * DAY_MS, null, start = StartReason.TAPPED),
            // A tap that never played (a failed start) is not a return.
            play(song("failed"), t0, radio),
            play(song("failed"), t0 + DAY_MS, null, start = StartReason.TAPPED, end = EndReason.ERROR, msPlayed = 0, replayed = true),
        )

        assertEquals(DiscoveryCounts(pairs = 7, replayed = 4, either = 4), report(events).total)
    }

    @Test
    fun savesFromFeedbackCountInsideTheWindowAndAnEarlierSaveMeansTheSongWasKnown() {
        val added = song("added")
        val before = song("before")
        val late = song("late")
        val events = listOf(play(added, t0, radio), play(before, t0, radio), play(late, t0, radio))
        val feedback = listOf(
            saved(added, t0 + 2 * DAY_MS, TrackFeedbackKind.ADDED_TO_PLAYLIST),
            saved(before, t0 - DAY_MS),
            saved(late, t0 + 8 * DAY_MS),
            TrackFeedback(track = late, kind = TrackFeedbackKind.NOT_INTERESTED, atMs = t0 + DAY_MS),
        )

        assertEquals(DiscoveryCounts(pairs = 2, saved = 1, either = 1), report(events, feedback).total)
    }

    @Test
    fun savedAndReplayedCountOnceAsEitherAndASecondExposureIsNoSecondPair() {
        val a = song("a")
        val events = listOf(
            play(a, t0, radio, liked = true),
            play(a, t0 + DAY_MS, null, start = StartReason.TAPPED),
            play(a, t0 + 2 * DAY_MS, radio),
        )

        assertEquals(DiscoveryCounts(pairs = 1, saved = 1, replayed = 1, either = 1), report(events).total)
    }

    @Test
    fun newArtistsAndMaturityAreSplitAndUntaggedFirstPlaysCounted() {
        val events = listOf(
            play(song("one", artist = "Band"), t0, radio),
            play(song("two", artist = "Band"), t0 + HOUR_MS, radio),
            play(song("three"), t0 + 2 * HOUR_MS, null),
        )
        val r = report(events, coldStartPlays = 1, establishedPlays = 10)

        assertEquals(
            mapOf(
                newRadio to DiscoveryCounts(pairs = 1),
                DiscoveryGroup(PlayPlace.RADIO, Served.RANKED, Maturity.LIGHT, newArtist = false) to DiscoveryCounts(pairs = 1),
            ),
            r.groups,
        )
        assertEquals(1, r.unattributedFirstPlays)
        assertEquals(3, r.localFirstPlays)
        val text = r.render()
        assertTrue("new artist" in text && "known artist" in text && "light" in text && "cold" in text, text)
    }

    @Test
    fun anUnheardPlayChangesNothing() {
        val a = song("a")
        val r = report(listOf(play(a, t0, search, end = EndReason.ERROR, msPlayed = 0), play(a, t0 + HOUR_MS, radio)))

        assertEquals(DiscoveryCounts(pairs = 1), r.total)
    }

    @Test
    fun anExposureBeforeTheMeasurementStartIsNotCountedButTheSongIsKnown() {
        val a = song("a")
        val events = listOf(play(a, t0, radio, liked = true), play(a, t0 + DAY_MS, radio))
        val r = report(events, notBeforeMs = t0 + 12 * HOUR_MS)

        assertEquals(emptyMap(), r.groups)
        assertEquals(0, r.localFirstPlays)
    }

    @Test
    fun inputOrderAndAFixedClockGiveTheSameReport() {
        val events = listOf(
            play(song("a"), t0, radio, liked = true),
            play(song("b", artist = "artist a"), t0 + HOUR_MS, radio),
            play(song("b", artist = "artist a"), t0 + DAY_MS, null, start = StartReason.TAPPED),
            play(song("c"), t0 + 2 * HOUR_MS, null),
            play(song("d"), t0 + 25 * DAY_MS, radio),
            play(song("i"), t0 - DAY_MS, null, source = EventSource.LASTFM_IMPORT),
        )
        val feedback = listOf(saved(song("c"), t0 + HOUR_MS), saved(song("d"), t0 + 26 * DAY_MS, TrackFeedbackKind.ADDED_TO_PLAYLIST))
        val expected = report(events, feedback, coldStartPlays = 2, establishedPlays = 4)
        assertEquals(DiscoveryCounts(pairs = 2, saved = 1, replayed = 1, either = 2, pending = 1), expected.total)

        repeat(5) { seed ->
            val shuffled = report(events.shuffled(Random(seed)), feedback.shuffled(Random(seed)), coldStartPlays = 2, establishedPlays = 4)
            assertEquals(expected, shuffled)
            assertEquals(expected.render(), shuffled.render())
        }
    }
}
