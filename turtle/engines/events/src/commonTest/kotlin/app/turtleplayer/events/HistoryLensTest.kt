package app.turtleplayer.events

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class HistoryLensTest {
    private var nextId = 1L
    private var nextStart = 1_700_000_000_000L

    private fun play(
        session: String,
        position: Int,
        start: StartReason = StartReason.AUTOPLAY,
        end: EndReason = EndReason.COMPLETED,
        msPlayed: Long = if (end == EndReason.SKIPPED) 10_000 else 200_000,
        label: Int? = null,
        source: EventSource = EventSource.LOCAL,
        id: Long = nextId++,
        startedAtMs: Long = nextStart.also { nextStart += 60_000 },
    ) = PlayEvent(
        id = id,
        track = TrackMeta(TrackKey.youtube("t$id"), "Song $id", listOf("Artist $id"), durationMs = 200_000),
        sessionId = session,
        position = position,
        surface = Surface.QUEUE,
        startReason = start,
        endReason = end,
        startedAtMs = startedAtMs,
        msPlayed = msPlayed,
        durationMs = 200_000,
        source = source,
        labelVersion = label,
    )

    private val repair = HistoryLens(repairLegacySkips = true)

    @Test
    fun legacySkipsBeforeAnUnclassifiedChangeReadAsNeutral() {
        // The test phone: 128 quick skips from older builds, 80 of them followed by an unclassified start.
        val events = (0 until 128).flatMap { i ->
            listOf(
                play("s$i", 0, start = StartReason.TAPPED, end = EndReason.SKIPPED),
                play("s$i", 1, start = if (i < 80) StartReason.UNKNOWN else StartReason.USER_NEXT),
            )
        }
        assertEquals(128, events.count { it.isQuickSkip })
        assertEquals(80, HistoryLens.legacySkipCount(events))
        assertEquals(80, repair.repairedCount(events))

        val read = repair.read(events)
        assertEquals(48, read.count { it.isQuickSkip })
        assertEquals(events.size, read.size)
        assertEquals(80, read.count { it.endReason == EndReason.UNKNOWN })
        // Same rows, only the reading changes.
        assertEquals(events.map { it.id }, read.map { it.id })
    }

    @Test
    fun labelledSkipsBackSkipsAndImportsAreUntouched() {
        val events = listOf(
            play("a", 0, end = EndReason.SKIPPED, label = PlayEvent.LABEL_VERSION),
            play("a", 1, start = StartReason.UNKNOWN),
            play("b", 0, end = EndReason.SKIPPED_BACK),
            play("b", 1, start = StartReason.UNKNOWN),
            play("c", 0, end = EndReason.SKIPPED, source = EventSource.SPOTIFY_IMPORT),
            play("c", 1, start = StartReason.UNKNOWN, source = EventSource.SPOTIFY_IMPORT),
        )
        assertEquals(events, repair.read(events))
        assertEquals(0, HistoryLens.legacySkipCount(events))
    }

    @Test
    fun aSkipWithNoNextPlayIsNeutralized() {
        val events = listOf(
            play("a", 0, start = StartReason.TAPPED),
            play("a", 1, end = EndReason.SKIPPED),
        )
        assertEquals(EndReason.UNKNOWN, repair.read(events)[1].endReason)
    }

    @Test
    fun aNextPlayInANewSessionIsFound() {
        val heard = listOf(
            play("a", 3, end = EndReason.SKIPPED),
            play("b", 0, start = StartReason.USER_NEXT),
        )
        assertEquals(EndReason.SKIPPED, repair.read(heard)[0].endReason)

        val unclassified = listOf(
            play("c", 3, end = EndReason.SKIPPED),
            play("d", 0, start = StartReason.UNKNOWN),
        )
        assertEquals(EndReason.UNKNOWN, repair.read(unclassified)[0].endReason)

        // The next row is not the start of a session: its predecessor was never stored.
        val gap = listOf(
            play("e", 3, end = EndReason.SKIPPED),
            play("f", 2, start = StartReason.USER_NEXT),
        )
        assertEquals(EndReason.UNKNOWN, repair.read(gap)[0].endReason)
    }

    @Test
    fun repairOffKeepsEveryLabel() {
        val events = listOf(play("a", 0, end = EndReason.SKIPPED), play("a", 1, start = StartReason.UNKNOWN))
        assertSame(events, HistoryLens().read(events))
        assertEquals(0, HistoryLens().repairedCount(events))
        assertEquals(1, HistoryLens.legacySkipCount(events))
    }

    @Test
    fun resetHidesEverySourceUpToTheWatermarkAndKeepsLaterRows() = runTest {
        val store = InMemoryPlayEventStore()
        store.appendAll(
            listOf(
                play("a", 0, id = 0),
                play("a", 1, id = 0),
                play("x", 0, source = EventSource.SPOTIFY_IMPORT, id = 0),
            ),
        )
        val watermark = store.since(0).maxOf { it.id }
        // After the reset: a new play, and an import whose plays are older than everything else.
        store.append(play("b", 0, id = 0))
        store.appendAll(listOf(play("y", 0, source = EventSource.LASTFM_IMPORT, id = 0, startedAtMs = 1_000L)))

        val lens = HistoryLens(ignoreThroughId = watermark)
        val read = LensedPlayEventStore(store) { lens }.since(0)
        assertEquals(listOf(EventSource.LASTFM_IMPORT, EventSource.LOCAL), read.map { it.source })
        assertEquals(5L, store.count()) // stored history is unchanged
        assertEquals(5, LensedPlayEventStore(store) { HistoryLens() }.since(0).size)
    }

    @Test
    fun aLimitedReadStillSeesTheLastPlaysSuccessor() = runTest {
        val store = InMemoryPlayEventStore()
        store.appendAll(
            listOf(
                play("a", 0, end = EndReason.SKIPPED, id = 0),
                play("a", 1, start = StartReason.USER_NEXT, id = 0),
            ),
        )
        val read = LensedPlayEventStore(store) { repair }.since(0, limit = 1)
        assertEquals(1, read.size)
        assertEquals(EndReason.SKIPPED, read.single().endReason)
    }

    @Test
    fun aLimitedReadAfterAResetStillReturnsLaterPlays() = runTest {
        val store = InMemoryPlayEventStore()
        store.appendAll((0 until 3).map { play("a", it, id = 0) })
        val watermark = store.since(0).maxOf { it.id }
        store.append(play("b", 0, id = 0))
        store.append(play("b", 1, id = 0))

        val read = LensedPlayEventStore(store) { HistoryLens(ignoreThroughId = watermark) }.since(0, limit = 1)
        assertEquals(listOf("b"), read.map { it.sessionId })
    }

    @Test
    fun encodesAndDecodes() {
        for (lens in listOf(HistoryLens(), repair, HistoryLens(ignoreThroughId = 42), HistoryLens(true, 7))) {
            assertEquals(lens, HistoryLens.decode(lens.encode()))
        }
        assertEquals(HistoryLens(), HistoryLens.decode(null))
        assertEquals(HistoryLens(), HistoryLens.decode("garbage"))
    }
}
