package app.turtleplayer.bridge

import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.CoListeningStore
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
