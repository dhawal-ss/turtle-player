package app.turtleplayer.bridge

import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.HistoryLens
import app.turtleplayer.events.LensedPlayEventStore
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.ExplorationState
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.JdbcSQLiteDriver
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TasteHistoryLensTest {
    private val t0 = 1_793_577_600_000L

    private fun play(
        id: String,
        at: Long,
        session: String,
        position: Int,
        start: StartReason = StartReason.AUTOPLAY,
        end: EndReason = EndReason.COMPLETED,
        msPlayed: Long = 200_000,
        source: EventSource = EventSource.LOCAL,
    ) = PlayEvent(
        track = TrackMeta(TrackKey.youtube(id), "t$id", listOf("Artist $id"), durationMs = 200_000, youtubeId = id),
        sessionId = session,
        position = position,
        surface = Surface.QUEUE,
        startReason = start,
        endReason = end,
        startedAtMs = at,
        msPlayed = msPlayed,
        durationMs = 200_000,
        source = source,
    )

    private fun tempDb() =
        File.createTempFile("turtle", ".db").apply {
            delete()
            deleteOnExit()
        }

    @Test
    fun repairAndResetChangeOnlyHowTasteReadsHistoryAndBothUndo() =
        runTest {
            val file = tempDb()
            val db = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            // An older build: a replaced queue read as a quick skip of "x", three times.
            db.appendAll(
                (1..3).flatMap { d ->
                    val at = t0 - d * DAY_MS
                    listOf(
                        play("x", at, "s$d", 0, start = StartReason.TAPPED, end = EndReason.SKIPPED, msPlayed = 5_000),
                        play("y", at + 5_000, "s$d", 1, start = StartReason.UNKNOWN),
                    )
                },
            )
            val stored = db.since(0)
            val taste =
                TasteEngine(
                    LensedPlayEventStore(db) { HistoryLens.decode(db.pref(PREF_HISTORY_LENS)) },
                    InMemoryEmbeddingStore(),
                    InMemoryTrackCatalog(emptyList()),
                    clock = Clock { t0 },
                )
            val lens = TasteHistoryLens(db, taste)
            val x = TrackKey.youtube("x")

            assertEquals(TasteRepairState(repairOlderSkips = false, olderSkips = 3, reset = false), lens.load())
            assertTrue(taste.profile().tracks.getValue(x).affinity < 0)

            assertTrue(lens.setRepairOlderSkips(true))
            assertFalse(lens.setRepairOlderSkips(true))
            taste.refresh()
            assertEquals(0.0, taste.profile().tracks.getValue(x).affinity)
            assertTrue(lens.state.value.repairOlderSkips)

            // Reset: everything stored so far stops counting, exploration starts fresh; later plays count.
            val learned = ExplorationState(alpha = 6.0, beta = 2.0)
            taste.setExploration(learned)
            assertTrue(lens.reset())
            assertFalse(lens.reset())
            assertEquals(ExplorationState(), taste.explorationState)
            db.append(play("z", t0 - 1_000, "s9", 0))
            taste.refresh()
            assertEquals(setOf(TrackKey.youtube("z")), taste.profile().tracks.keys)
            assertTrue(lens.state.value.reset)

            // Saved: a new run reads the same options.
            assertEquals(TasteRepairState(repairOlderSkips = true, olderSkips = 3, reset = true), TasteHistoryLens(db, taste).load())

            assertTrue(lens.undoReset())
            assertFalse(lens.undoReset())
            assertEquals(learned, taste.explorationState)
            taste.refresh()
            assertEquals(setOf("x", "y", "z").map(TrackKey::youtube).toSet(), taste.profile().tracks.keys)
            assertEquals(0.0, taste.profile().tracks.getValue(x).affinity) // the repair stays on

            assertTrue(lens.setRepairOlderSkips(false))
            taste.refresh()
            assertTrue(taste.profile().tracks.getValue(x).affinity < 0)

            // Stored history was never changed.
            assertEquals(stored, db.since(0).filter { it.trackKey != TrackKey.youtube("z") })
            assertEquals(TasteRepairState(olderSkips = 3), lens.state.value)
            db.close()
        }

    @Test
    fun importsAddedAfterAResetStillCount() =
        runTest {
            val db = SqlitePlayEventStore(JdbcSQLiteDriver(), tempDb().absolutePath)
            db.append(play("before", t0 - 2 * DAY_MS, "s1", 0))
            val taste =
                TasteEngine(
                    LensedPlayEventStore(db) { HistoryLens.decode(db.pref(PREF_HISTORY_LENS)) },
                    InMemoryEmbeddingStore(),
                    InMemoryTrackCatalog(emptyList()),
                    clock = Clock { t0 },
                )
            val lens = TasteHistoryLens(db, taste)
            assertTrue(lens.reset())
            // Imported after the reset, with plays older than it.
            db.appendAll(listOf(play("imported", t0 - 3 * DAY_MS, "i1", 0, source = EventSource.SPOTIFY_IMPORT)))
            taste.refresh()
            assertEquals(setOf(TrackKey.youtube("imported")), taste.profile().tracks.keys)
            assertNull(taste.profile().tracks[TrackKey.youtube("before")])
            db.close()
        }
}
