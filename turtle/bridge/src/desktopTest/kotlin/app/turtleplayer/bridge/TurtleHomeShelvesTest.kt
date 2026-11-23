package app.turtleplayer.bridge

import app.turtleplayer.core.Clock
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.InMemoryEmbeddingStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.RecommendationSource
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TurtleHomeShelvesTest {
    private val now = 1_800_000_000_000L

    private fun song(
        id: String,
        artist: String,
    ) = SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)

    private val heard = (0 until 12).map { song("h$it", "Artist ${it / 4}") }
    private val radioSongs = (0 until 40).map { song("r$it", "Radio ${it % 10}") }
    private val history =
        heard.flatMapIndexed { i, s ->
            (1..3).map { k ->
                PlayEvent(
                    track = s.toTrackMeta(),
                    sessionId = "s",
                    position = 0,
                    surface = Surface.AUTOPLAY,
                    startReason = StartReason.AUTOPLAY,
                    endReason = EndReason.COMPLETED,
                    startedAtMs = now - (k * 24 + i) * HOUR_MS,
                    msPlayed = 200_000,
                    durationMs = 200_000,
                )
            }
        }

    /** YouTube radio as the bridge wires it: songs land in the cache, metas go to the engine. */
    private class Radio(
        private val cache: SongCache,
        private val songs: List<SongItem>,
    ) : RecommendationSource {
        var calls = 0

        override suspend fun related(
            seed: TrackMeta,
            limit: Int,
        ): List<TrackMeta> {
            calls++
            cache.putAll(songs)
            return songs.take(limit).map { it.toTrackMeta() }
        }
    }

    private suspend fun engine(upstream: RecommendationSource) =
        TasteEngine(
            events = InMemoryPlayEventStore().also { it.appendAll(history) },
            store = InMemoryEmbeddingStore(),
            catalog = InMemoryTrackCatalog(heard.map { it.toTrackMeta() }),
            upstream = upstream,
            clock = Clock { now },
            random = Random(7),
        )

    @Test
    fun shelfSongsComeBackAfterARestartWithoutTheNetwork() =
        runTest {
            val file =
                File.createTempFile("turtle", ".db").apply {
                    delete()
                    deleteOnExit()
                }

            // First run: shelves built from history and radio, then the process dies before any play.
            val db1 = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val cache1 = SongCache().also { it.replaceLibrary(heard) }
            val engine1 = engine(Radio(cache1, radioSongs))
            val home1 = TurtleHomeShelves(engine1, InMemoryPlayEventStore(), cache1, db1)
            home1.restore()
            val built = engine1.homeShelves()
            home1.persist(built)
            val ids = built.flatMap { it.items }.mapNotNull { it.track.youtubeId }.distinct()
            assertTrue(ids.any { it.startsWith("r") }, "a kept shelf holds never-heard radio songs: $ids")
            val songs = ids.map { assertNotNull(cache1.get(it)) }
            db1.close()

            // New runtime: library songs only, the network down, and a long session flooding the LRU.
            val db2 = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val cache2 = SongCache(capacity = 1).also { it.replaceLibrary(heard) }
            val offline =
                object : RecommendationSource {
                    var calls = 0

                    override suspend fun related(
                        seed: TrackMeta,
                        limit: Int,
                    ): List<TrackMeta> {
                        calls++
                        error("offline")
                    }
                }
            val engine2 = engine(offline)
            assertTrue(engine2.importState(assertNotNull(db2.pref(PREF_TASTE_STATE))))
            TurtleHomeShelves(engine2, InMemoryPlayEventStore(), cache2, db2).restore()
            cache2.putAll((0 until 50).map { song("f$it", "Filler") })

            val kept = engine2.keptShelves()
            assertEquals(built, kept)
            val restoredIds = kept.flatMap { it.items }.mapNotNull { it.track.youtubeId }.distinct()
            assertEquals(songs, restoredIds.map { assertNotNull(cache2.get(it), it) })
            assertEquals(0, offline.calls)
            db2.close()
        }
}
