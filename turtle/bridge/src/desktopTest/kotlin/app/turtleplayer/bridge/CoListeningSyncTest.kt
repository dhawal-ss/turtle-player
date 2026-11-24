package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.CoListeningStore
import app.turtleplayer.taste.InMemoryTrackCatalog
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoListeningSyncTest {
    private fun song(
        id: String,
        artist: String,
    ) = SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)

    @Test
    fun radioPagesTeachNothingWhileListeningHistoryIsPaused() =
        runTest {
            val file =
                File.createTempFile("turtle", ".db").apply {
                    delete()
                    deleteOnExit()
                }
            var paused = true
            val graph = CoListeningGraph()
            val sync =
                CoListeningSync(
                    graph,
                    CoListeningStore(graph),
                    SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath),
                    InMemoryPlayEventStore(),
                    SongCache(),
                    learning = { !paused },
                )
            val seed = song("s", "Artist A").toTrackMeta()
            val page = listOf(song("r1", "Artist B"), song("r2", "Artist C"))
            val before = graph.version()

            sync.observeRadio(seed, page)
            assertEquals(before, graph.version())

            paused = false
            sync.observeRadio(seed, page)
            assertTrue(graph.version() > before)
        }

    @Test
    fun rebuildNowRebuildsBeforeTheInterval(): Unit =
        runBlocking {
            val file =
                File.createTempFile("turtle", ".db").apply {
                    delete()
                    deleteOnExit()
                }
            val graph = CoListeningGraph()
            val store = CoListeningStore(graph)
            val db = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val events = InMemoryPlayEventStore()
            val sync = CoListeningSync(graph, store, db, events, SongCache())
            val taste = TasteEngine(events = events, store = store, catalog = InMemoryTrackCatalog(emptyList()))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                sync.observeRadio(song("s", "Artist A").toTrackMeta(), listOf(song("r1", "Artist B")))
                sync.start(scope, taste)
                withTimeout(5_000) { sync.rebuildNow() }
                assertNotNull(store.artistVector(ArtistKey.of("Artist B")), "the first build")
                // Vectors exist now, so the loop's own rebuild is 15 minutes away; asking makes it happen at once.
                sync.observeRadio(song("s2", "Artist C").toTrackMeta(), listOf(song("r2", "Artist D")))
                assertNull(store.artistVector(ArtistKey.of("Artist D")))
                withTimeout(5_000) { sync.rebuildNow() }
                assertNotNull(store.artistVector(ArtistKey.of("Artist D")))
            } finally {
                scope.cancel()
                db.close()
            }
        }
}
