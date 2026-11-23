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
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import androidx.sqlite.execSQL
import com.metrolist.shared.db.JdbcSQLiteDriver
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    @Test
    fun upgradedDatabaseKeepsThePlayInProgressAcrossARestart() = runTest {
        val file = File.createTempFile("turtle", ".db").apply { delete(); deleteOnExit() }
        SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath).apply { append(event("old", 500)); close() }
        // Turn it back into a version 2 database, as installed before the play-in-progress table.
        JdbcSQLiteDriver().open(file.absolutePath).apply {
            execSQL("DROP TABLE open_play")
            execSQL("PRAGMA user_version = 2")
            close()
        }

        val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
        val inProgress = event("playing", 4_000).copy(endReason = EndReason.STOPPED)
        store.save(inProgress)
        store.save(inProgress.copy(msPlayed = 30_000)) // replaces, never accumulates
        store.close()

        val restarted = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
        assertEquals(inProgress.copy(msPlayed = 30_000), restarted.take())
        assertNull(restarted.take())
        assertEquals(listOf("yt:old"), restarted.since(0).map { it.trackKey.value })
        restarted.close()
    }

    @Test
    fun shelfSongsSurviveAReopenAndAnUpgradeFromVersionFour() =
        runTest {
            val file =
                File.createTempFile("turtle", ".db").apply {
                    delete()
                    deleteOnExit()
                }
            SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath).apply {
                append(event("old", 500))
                close()
            }
            // Back to a version 4 database, as installed before shelf songs were kept.
            JdbcSQLiteDriver().open(file.absolutePath).apply {
                execSQL("DROP TABLE shelf_song")
                execSQL("PRAGMA user_version = 4")
                close()
            }

            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            store.saveShelfSongs(listOf("a" to """{"id":"a"}""", "b" to """{"id":"b"}"""))
            store.saveShelfSongs(listOf("b" to """{"id":"b2"}""", "c" to """{"id":"c"}""")) // replaces, never accumulates
            store.close()

            val reopened = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            assertEquals(listOf("""{"id":"b2"}""", """{"id":"c"}"""), reopened.loadShelfSongs())
            assertEquals(listOf("yt:old"), reopened.since(0).map { it.trackKey.value })
            reopened.close()
        }

    @Test
    fun feedbackSurvivesAReopenAndAnUpgradeFromVersionFive() =
        runTest {
            val file = File.createTempFile("turtle", ".db")
            file.delete()
            file.deleteOnExit()
            val first = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            first.append(event("old", 500))
            first.close()
            val downgrade = JdbcSQLiteDriver().open(file.absolutePath)
            downgrade.execSQL("DROP TABLE track_feedback")
            downgrade.execSQL("PRAGMA user_version = 5")
            downgrade.close()
            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val added =
                TrackFeedback(
                    track =
                        TrackMeta(
                            TrackKey.youtube("a"),
                            "Title a",
                            listOf("Artist A", "Artist B"),
                            album = "Album",
                            durationMs = 200_000,
                        ),
                    kind = TrackFeedbackKind.ADDED_TO_PLAYLIST,
                    atMs = 1_000,
                    detail = "PLx",
                )
            store.appendFeedback(added)
            store.appendFeedback(added.copy(kind = TrackFeedbackKind.NOT_INTERESTED, atMs = 2_000, detail = null))
            store.close()

            val reopened = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val all = reopened.feedbackSince(0)
            assertEquals(added.copy(id = all[0].id), all[0])
            assertEquals(listOf(TrackFeedbackKind.ADDED_TO_PLAYLIST, TrackFeedbackKind.NOT_INTERESTED), all.map { it.kind })
            assertEquals(1, reopened.feedbackSince(1_500).size)
            assertEquals(1L, reopened.count())
            reopened.close()
        }
}
