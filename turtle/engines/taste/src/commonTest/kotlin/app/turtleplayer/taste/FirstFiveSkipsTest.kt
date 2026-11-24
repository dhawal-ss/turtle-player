package app.turtleplayer.taste

import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.eval.EarlyGroup
import app.turtleplayer.taste.eval.EarlySkips
import app.turtleplayer.taste.eval.FirstFiveCounts
import app.turtleplayer.taste.eval.FirstFiveReport
import app.turtleplayer.taste.eval.FirstFiveSkips
import app.turtleplayer.taste.eval.Maturity
import app.turtleplayer.taste.eval.OriginReporter
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FirstFiveSkipsTest {
    private var clock = BASE

    private fun play(
        session: String,
        position: Int,
        origin: PlayOrigin?,
        start: StartReason = StartReason.AUTOPLAY,
        end: EndReason = EndReason.COMPLETED,
        source: EventSource = EventSource.LOCAL,
        msPlayed: Long = if (end.isSkip) 5_000 else 200_000,
        key: String = "test:$session-$position",
        at: Long? = null,
    ): PlayEvent {
        clock += 1_000
        return PlayEvent(
            track = TrackMeta(TrackKey(key), "song $position", listOf("artist"), durationMs = 200_000),
            sessionId = session,
            position = position,
            surface = origin?.surface ?: Surface.UNKNOWN,
            startReason = start,
            endReason = end,
            startedAtMs = at ?: clock,
            msPlayed = msPlayed,
            durationMs = 200_000,
            source = source,
            surfaceDetail = origin?.let(SurfaceTag::format),
        )
    }

    private val radio = PlayOrigin(PlayPlace.RADIO, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, served = Served.RANKED)
    private val radioGroup = EarlyGroup(PlayPlace.RADIO, Served.RANKED, Maturity.COLD)

    private fun homeYt(position: Int) = PlayOrigin(PlayPlace.HOME_YOUTUBE, row = "section_0", served = Served.PROVIDER, position = position)
    private val homeGroup = EarlyGroup(PlayPlace.HOME_YOUTUBE, Served.PROVIDER, Maturity.COLD)

    /** Clock a day after the last play, cold all along, so nothing falls out of the window by accident. */
    private fun report(events: List<PlayEvent>, notBeforeMs: Long = 0, now: Long = clock + DAY_MS): FirstFiveReport =
        FirstFiveSkips.report(events, Clock { now }, notBeforeMs = notBeforeMs, coldStartPlays = 1_000, establishedPlays = 2_000)

    @Test
    fun aRadioRunCountsOnlyItsFirstFiveSlots() {
        val skipped = setOf(1, 3, 6)
        val events = (0..6).map { play("s", it, radio, start = if (it == 0) StartReason.UNKNOWN else StartReason.AUTOPLAY, end = if (it in skipped) EndReason.SKIPPED else EndReason.COMPLETED) }
        val counts = report(events).bySurface.getValue(radioGroup)

        assertEquals(FirstFiveCounts(eligible = 5, earlySkips = 2), counts)
        assertEquals(0.4, counts.rate)
    }

    @Test
    fun manualStartsTakeNoSlotAndInterruptedPlaysStayOutOfTheRate() {
        val events = listOf(
            play("s", 0, radio, start = StartReason.UNKNOWN),
            play("s", 1, radio, start = StartReason.USER_PREVIOUS, end = EndReason.SKIPPED),
            play("s", 2, radio, start = StartReason.RESUMED, end = EndReason.SKIPPED),
            play("s", 3, radio, end = EndReason.STOPPED),
            play("s", 4, radio, end = EndReason.ERROR),
            play("s", 5, radio, start = StartReason.USER_NEXT, end = EndReason.SKIPPED),
            play("s", 6, radio),
            play("s", 7, radio, end = EndReason.SKIPPED),
        )
        val counts = report(events).bySurface.getValue(radioGroup)

        assertEquals(FirstFiveCounts(eligible = 3, earlySkips = 1, interrupted = 2), counts)
        assertEquals(1.0 / 3, counts.rate, 1e-9)
    }

    @Test
    fun anUnknownEndIsUnknownUnderThirtySecondsAndEligibleFromThenOn() {
        val events = listOf(
            play("s", 0, radio, start = StartReason.UNKNOWN),
            play("s", 1, radio, end = EndReason.UNKNOWN, msPlayed = 10_000),
            play("s", 2, radio, end = EndReason.UNKNOWN, msPlayed = 40_000),
            play("s", 3, radio, end = EndReason.SKIPPED),
        )
        val counts = report(events).bySurface.getValue(radioGroup)

        assertEquals(FirstFiveCounts(eligible = 3, earlySkips = 1, unknown = 1), counts)
        assertEquals(0.25, counts.unknownRate)
    }

    @Test
    fun onlySkipsBeforeThirtySecondsAreEarlyAndTheFastestAreInstant() {
        val events = listOf(
            play("s", 0, radio, start = StartReason.UNKNOWN, end = EndReason.SKIPPED, msPlayed = 30_000),
            play("s", 1, radio, end = EndReason.SKIPPED_BACK, msPlayed = 10_000),
            play("s", 2, radio, end = EndReason.SKIPPED, msPlayed = 500),
        )

        assertEquals(FirstFiveCounts(eligible = 3, earlySkips = 2, instantSkips = 1), report(events).bySurface.getValue(radioGroup))
    }

    @Test
    fun aReplacedQueueClosesTheRunAndTheNewStationGetsItsOwnFive() {
        val events = listOf(
            play("s", 0, radio, start = StartReason.UNKNOWN),
            play("s", 1, radio, end = EndReason.STOPPED),
            // A new station over the old one: the tap is the listener's, the next five are the station's.
            play("s", 2, radio, start = StartReason.TAPPED, end = EndReason.SKIPPED),
        ) + (3..8).map { play("s", it, radio, end = if (it == 8) EndReason.SKIPPED else EndReason.COMPLETED) }

        assertEquals(FirstFiveCounts(eligible = 6, interrupted = 1), report(events).bySurface.getValue(radioGroup))
    }

    @Test
    fun importsAreIgnoredAndUntaggedPlaysAreReported() {
        val events = listOf(
            play("import", 0, null, end = EndReason.SKIPPED, source = EventSource.SPOTIFY_IMPORT),
            play("import", 1, radio, end = EndReason.SKIPPED, source = EventSource.SPOTIFY_IMPORT),
            play("s", 0, null),
            play("s", 1, radio, start = StartReason.TAPPED),
            play("s", 2, radio, end = EndReason.SKIPPED),
        )
        val r = report(events)

        assertEquals(mapOf(radioGroup to FirstFiveCounts(eligible = 2, earlySkips = 1)), r.bySurface)
        assertEquals(1, r.untaggedPlays)
        assertEquals(3, r.localPlays)
    }

    @Test
    fun aRunEnteredByRunningOnFromAnUntaggedPlayIsJoinedAndNeverEligible() {
        val joined = listOf(play("a", 0, null), play("a", 1, radio), play("a", 2, radio, end = EndReason.SKIPPED))
        assertEquals(mapOf(radioGroup to FirstFiveCounts(joined = 2)), report(joined).bySurface)

        // From a tagged Home row the radio run is not joined: its first song counts.
        val fromHome = listOf(play("b", 0, homeYt(1), start = StartReason.UNKNOWN), play("b", 1, radio), play("b", 2, radio, end = EndReason.SKIPPED))
        assertEquals(mapOf(radioGroup to FirstFiveCounts(eligible = 2, earlySkips = 1)), report(fromHome).bySurface)
    }

    @Test
    fun aSessionThatOpensByRunningOnIsJoined() {
        // Radio resumed after a long pause: the new session opens mid-station, past its first five.
        val resumed = listOf(play("r", 0, radio), play("r", 1, radio, end = EndReason.SKIPPED), play("r", 2, radio, start = StartReason.USER_NEXT))
        assertEquals(mapOf(radioGroup to FirstFiveCounts(joined = 3)), report(resumed).bySurface)

        val nextAfterPause = listOf(play("n", 0, radio, start = StartReason.USER_NEXT, end = EndReason.SKIPPED), play("n", 1, radio))
        assertEquals(mapOf(radioGroup to FirstFiveCounts(joined = 2)), report(nextAfterPause).bySurface)

        // A deliberate restart inside it opens a fresh run that counts.
        val restarted = resumed + play("r", 3, radio, start = StartReason.TAPPED) + play("r", 4, radio, end = EndReason.SKIPPED)
        assertEquals(mapOf(radioGroup to FirstFiveCounts(eligible = 1, earlySkips = 1, joined = 3)), report(restarted).bySurface)
    }

    @Test
    fun theWindowAndTheMeasurementStartKeepSlotNumbersFromTheWholeHistory() {
        val now = BASE + 100 * DAY_MS
        val edge = now - 28 * DAY_MS
        // Slot 1 falls before the window; slots 2..5 are inside it, the last two plays are past slot five.
        val events = listOf(play("w", 0, radio, start = StartReason.UNKNOWN, at = edge - 1)) +
            (1..6).map { play("w", it, radio, at = edge + (it - 1) * 1_000L) }
        val inWindow = report(events, now = now)
        assertEquals(FirstFiveCounts(eligible = 4), inWindow.bySurface.getValue(radioGroup))
        assertEquals(edge, inWindow.fromMs)
        assertEquals(now, inWindow.toMs)

        // A later measurement start drops slots 2..4 (at edge, +1 s, +2 s) even inside the window.
        val measured = report(events, notBeforeMs = edge + 2_500, now = now)
        assertEquals(FirstFiveCounts(eligible = 1), measured.bySurface.getValue(radioGroup))
        assertEquals(edge + 2_500, measured.fromMs)
    }

    @Test
    fun inputOrderDoesNotChangeTheReport() {
        val events = listOf(play("import", 0, null, source = EventSource.LASTFM_IMPORT)) +
            (0..4).map { play("a", it, homeYt(it + 1), end = if (it == 2) EndReason.SKIPPED else EndReason.COMPLETED) } +
            (0..6).map { play("b", it, radio, end = if (it == 1) EndReason.ERROR else if (it == 3) EndReason.UNKNOWN else EndReason.COMPLETED, msPlayed = if (it == 3) 2_000 else 200_000) } +
            play("b", 7, null) + play("b", 8, radio) + play("b", 9, radio, end = EndReason.SKIPPED)
        val expected = report(events)

        repeat(5) { seed ->
            val shuffled = report(events.shuffled(Random(seed)))
            assertEquals(expected, shuffled)
            assertEquals(expected.render(), shuffled.render())
        }
        assertTrue(expected.render().contains("unknown%"))
    }

    @Test
    fun withoutUnknownEndsOrJoinedRunsItAgreesWithTheOriginReport() {
        val fixtures = listOf(
            listOf(play("s", 0, homeYt(1), start = StartReason.UNKNOWN)) +
                (1..6).map { play("s", it, homeYt(it + 1), end = if (it % 2 == 0) EndReason.SKIPPED else EndReason.COMPLETED) },
            listOf(play("t", 0, homeYt(1), start = StartReason.UNKNOWN)) +
                (1..5).map { play("t", it, homeYt(it + 1)) } +
                play("t", 6, homeYt(3), start = StartReason.TAPPED, end = EndReason.SKIPPED) +
                play("t", 7, homeYt(4), end = EndReason.SKIPPED) +
                play("t", 8, radio, start = StartReason.TAPPED, end = EndReason.SKIPPED) +
                play("t", 9, radio, start = StartReason.TAPPED) +
                play("t", 10, radio, end = EndReason.SKIPPED),
            listOf(
                play("u", 0, radio, start = StartReason.UNKNOWN),
                play("u", 1, radio, start = StartReason.USER_PREVIOUS, end = EndReason.SKIPPED),
                play("u", 2, radio, end = EndReason.STOPPED),
                play("u", 3, radio, end = EndReason.ERROR),
                play("u", 4, radio, start = StartReason.USER_NEXT, end = EndReason.SKIPPED),
                play("u", 5, radio),
            ),
            (0..3).flatMap { i ->
                val start = if (i == 0) StartReason.UNKNOWN else StartReason.AUTOPLAY
                listOf(play("x", i, radio, start = start), play("y", i, radio, start = start))
            },
        )
        for (events in fixtures) {
            val old = OriginReporter.report(events, coldStartPlays = 1_000, establishedPlays = 2_000).early
            val new = report(events).bySurface.filterValues { it.eligible > 0 }.mapValues { (_, c) -> EarlySkips(c.eligible, c.earlySkips) }
            assertEquals(old, new)
            assertTrue(old.isNotEmpty())
        }
        assertEquals(setOf(homeGroup, radioGroup), report(fixtures[1]).bySurface.keys)
    }

    private companion object {
        const val BASE = 1_793_577_600_000L
    }
}
