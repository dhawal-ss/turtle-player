package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import com.metrolist.shared.db.JdbcSQLiteDriver
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SqlitePlayEventStoreTest {
    private fun event(id: String, at: Long, source: EventSource = EventSource.LOCAL) = PlayEvent(
        track = TrackMeta(TrackKey.youtube(id), "Title $id", listOf("Artist A", "Artist B"), album = "Album", durationMs = 200_000),
        sessionId = "s1",
        position = 0,
        surface = Surface.AUTOPLAY,
        startReason = StartReason.AUTOPLAY,
        endReason = EndReason.SKIPPED,
        startedAtMs = at,
        msPlayed = 12_000,
        durationMs = 200_000,
        shuffle = null,
        liked = true,
        context = PlayContext(21, 5),
        source = source,
        surfaceDetail = "taste/session/discovery",
    )

    @Test
    fun roundTripsEventsAcrossReopenAndReplacesImports() = runTest {
        val file = File.createTempFile("turtle", ".db").apply { delete(); deleteOnExit() }
        val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
        val original = event("a", 1_000)
        store.append(original)
        store.appendAll(listOf(event("b", 2_000, EventSource.SPOTIFY_IMPORT), event("c", 3_000, EventSource.SPOTIFY_IMPORT)))
        store.close()

        val reopened = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
        val all = reopened.since(0)
        assertEquals(listOf("yt:a", "yt:b", "yt:c"), all.map { it.trackKey.value })
        assertEquals(original.copy(id = all[0].id), all[0])
        assertEquals(3, reopened.forArtist(ArtistKey.of("Artist A")).size)

        reopened.deleteSource(EventSource.SPOTIFY_IMPORT)
        assertEquals(1, reopened.count())
        reopened.close()
    }
}
