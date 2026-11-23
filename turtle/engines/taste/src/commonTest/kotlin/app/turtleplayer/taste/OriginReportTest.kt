package app.turtleplayer.taste

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.eval.EarlyGroup
import app.turtleplayer.taste.eval.EarlySkips
import app.turtleplayer.taste.eval.Maturity
import app.turtleplayer.taste.eval.OriginGroup
import app.turtleplayer.taste.eval.OriginReporter
import app.turtleplayer.taste.eval.PlayCounts
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

class OriginReportTest {
    private var clock = 0L

    private fun play(
        session: String,
        position: Int,
        origin: PlayOrigin?,
        start: StartReason = StartReason.AUTOPLAY,
        end: EndReason = EndReason.COMPLETED,
        source: EventSource = EventSource.LOCAL,
        detail: String? = origin?.let(SurfaceTag::format),
    ): PlayEvent {
        clock += 1_000
        val quick = end == EndReason.SKIPPED
        return PlayEvent(
            track = TrackMeta(TrackKey("test:$session-$position"), "song $position", listOf("artist"), durationMs = 200_000),
            sessionId = session,
            position = position,
            surface = origin?.surface ?: Surface.UNKNOWN,
            startReason = start,
            endReason = end,
            startedAtMs = clock,
            msPlayed = if (quick) 5_000 else 200_000,
            durationMs = 200_000,
            source = source,
            surfaceDetail = detail,
        )
    }

    private val radio = PlayOrigin(PlayPlace.RADIO, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, served = Served.RANKED)

    private fun homeYt(position: Int) = PlayOrigin(PlayPlace.HOME_YOUTUBE, row = "section_0", served = Served.PROVIDER, position = position)

    @Test
    fun importsCountTowardMaturityButAreNotGrouped() {
        val events = listOf(
            play("a", 0, radio),
            play("import", 0, null, source = EventSource.SPOTIFY_IMPORT),
            play("import", 1, null, end = EndReason.SKIPPED, source = EventSource.SPOTIFY_IMPORT),
            play("b", 0, radio),
            play("b", 1, radio),
            play("b", 2, radio),
        )
        val report = OriginReporter.report(events, coldStartPlays = 2, establishedPlays = 4)

        assertEquals(4, report.localPlays)
        assertEquals(0, report.untaggedPlays)
        assertEquals(
            mapOf(
                OriginGroup(PlayPlace.RADIO, Served.RANKED, null, Maturity.COLD) to PlayCounts(1, 0, 1),
                OriginGroup(PlayPlace.RADIO, Served.RANKED, null, Maturity.LIGHT) to PlayCounts(2, 0, 2),
                OriginGroup(PlayPlace.RADIO, Served.RANKED, null, Maturity.ESTABLISHED) to PlayCounts(1, 0, 1),
            ),
            report.plays,
        )
    }

    @Test
    fun untaggedPlaysAreCountedApart() {
        val events = listOf(
            play("a", 0, null),
            play("a", 1, null, detail = "Liked Songs", end = EndReason.SKIPPED),
            play("a", 2, radio),
        )
        val report = OriginReporter.report(events)

        assertEquals(3, report.localPlays)
        assertEquals(2, report.untaggedPlays)
        assertEquals(PlayCounts(2, 1, 1), report.plays[OriginGroup(null, null, null, Maturity.COLD)])
        assertEquals(0.5, report.plays.getValue(OriginGroup(null, null, null, Maturity.COLD)).quickSkipRate)
    }

    @Test
    fun homeRowTapIsManualAndTheNextFiveAreCounted() {
        val events = listOf(play("s", 0, homeYt(1), start = StartReason.UNKNOWN)) +
            (1..6).map { play("s", it, homeYt(it + 1), end = if (it % 2 == 0) EndReason.SKIPPED else EndReason.COMPLETED) }
        val report = OriginReporter.report(events)

        // Plays 1..5 (row positions 2..6) are the first five suggestions; plays 2 and 4 were quick skips.
        assertEquals(mapOf(EarlyGroup(PlayPlace.HOME_YOUTUBE, Served.PROVIDER, Maturity.COLD) to EarlySkips(5, 2)), report.early)
        assertEquals(0.4, report.early.values.single().rate)
        val byPosition = report.plays.entries.associate { it.key.position to it.value.started }
        assertEquals(mapOf<String?, Int>("1" to 1, "2" to 1, "3" to 1, "4+" to 4), byPosition)
    }

    @Test
    fun enteringARadioCountsItsFirstSongHoweverPlaybackStarted() {
        // From silence the start is UNKNOWN; over another song it is TAPPED. Either way the radio chose the song.
        for (start in listOf(StartReason.UNKNOWN, StartReason.TAPPED)) {
            val events = listOf(play("s", 0, null), play("s", 1, radio, start = start), play("s", 2, radio, end = EndReason.SKIPPED))
            assertEquals(EarlySkips(2, 1), OriginReporter.report(events).early[EarlyGroup(PlayPlace.RADIO, Served.RANKED, Maturity.COLD)], "$start")
        }
    }

    @Test
    fun aTapInTheSamePlaceStartsANewWindowWithTheListenersPick() {
        val events = listOf(play("s", 0, homeYt(1), start = StartReason.UNKNOWN)) +
            (1..5).map { play("s", it, homeYt(it + 1)) } +
            play("s", 6, homeYt(3), start = StartReason.TAPPED, end = EndReason.SKIPPED) +
            play("s", 7, homeYt(4), end = EndReason.SKIPPED) +
            play("s", 8, radio, start = StartReason.TAPPED, end = EndReason.SKIPPED) +
            play("s", 9, radio, start = StartReason.TAPPED) +
            play("s", 10, radio, end = EndReason.SKIPPED)
        val report = OriginReporter.report(events)

        assertEquals(
            mapOf(
                // Five from the first tap, then the second tap's pick is skipped and its next song counted.
                EarlyGroup(PlayPlace.HOME_YOUTUBE, Served.PROVIDER, Maturity.COLD) to EarlySkips(6, 1),
                // Entering radio counts its first song; a tap inside the radio is the listener's pick.
                EarlyGroup(PlayPlace.RADIO, Served.RANKED, Maturity.COLD) to EarlySkips(2, 2),
            ),
            report.early,
        )
    }

    @Test
    fun manualPicksAreSkippedAndInterruptedPlaysTakeASlotWithoutCounting() {
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
        val report = OriginReporter.report(events)

        assertEquals(mapOf(EarlyGroup(PlayPlace.RADIO, Served.RANKED, Maturity.COLD) to EarlySkips(3, 1)), report.early)
    }

    @Test
    fun aChangeOfPlaceOrAnUntaggedPlayStartsANewRun() {
        val events = (0..5).map { play("s", it, radio) } +
            play("s", 6, homeYt(1), start = StartReason.TAPPED) +
            play("s", 7, homeYt(2), end = EndReason.SKIPPED) +
            play("s", 8, null) +
            play("s", 9, radio) +
            play("s", 10, radio, end = EndReason.SKIPPED)
        val report = OriginReporter.report(events)

        assertEquals(
            mapOf(
                EarlyGroup(PlayPlace.RADIO, Served.RANKED, Maturity.COLD) to EarlySkips(7, 1),
                EarlyGroup(PlayPlace.HOME_YOUTUBE, Served.PROVIDER, Maturity.COLD) to EarlySkips(1, 1),
            ),
            report.early,
        )
    }

    @Test
    fun sessionsDoNotBleedIntoEachOther() {
        // Interleaved in time: merged into one session the radio run would stop at five.
        val events = (0..3).flatMap { listOf(play("a", it, radio), play("b", it, radio)) }
        val report = OriginReporter.report(events)

        assertEquals(EarlySkips(8, 0), report.early[EarlyGroup(PlayPlace.RADIO, Served.RANKED, Maturity.COLD)])
    }

    @Test
    fun inputOrderDoesNotChangeTheReport() {
        val events = listOf(play("import", 0, null, source = EventSource.LASTFM_IMPORT)) +
            (0..4).map { play("a", it, homeYt(it + 1), end = if (it == 2) EndReason.SKIPPED else EndReason.COMPLETED) } +
            (0..4).map { play("b", it, radio, end = if (it == 1) EndReason.ERROR else EndReason.COMPLETED) } +
            play("b", 5, null)
        val expected = OriginReporter.report(events, coldStartPlays = 3, establishedPlays = 6)

        repeat(5) { seed ->
            val shuffled = OriginReporter.report(events.shuffled(Random(seed)), coldStartPlays = 3, establishedPlays = 6)
            assertEquals(expected, shuffled)
            assertEquals(expected.render(), shuffled.render())
        }
    }
}
