package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayContext
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.SourceRange
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
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun aSaveRoundTripsAndAKindFromANewerBuildIsSkipped() =
        runTest {
            val file = File.createTempFile("turtle", ".db")
            file.delete()
            file.deleteOnExit()
            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val save =
                TrackFeedback(
                    track = TrackMeta(TrackKey.youtube("s"), "Title s", listOf("Artist A"), durationMs = 200_000, youtubeId = "s"),
                    kind = TrackFeedbackKind.SAVED,
                    atMs = 1_000,
                    detail = TrackFeedback.SAVED_LIBRARY,
                )
            store.appendFeedback(save)
            store.appendFeedback(save.copy(atMs = 2_000))
            store.close()
            val raw = JdbcSQLiteDriver().open(file.absolutePath)
            raw.execSQL("UPDATE track_feedback SET kind = 'FUTURE' WHERE at_ms = 2000")
            raw.close()

            val reopened = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val all = reopened.feedbackSince(0)
            assertEquals(listOf(save.copy(id = all.single().id)), all)
            reopened.close()
        }

    @Test
    fun versionSixDatabaseGainsLabelVersionsWithoutChangingStoredPlays() =
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
            // Back to a version 6 database, as installed before plays carried a label version.
            JdbcSQLiteDriver().open(file.absolutePath).apply {
                execSQL("ALTER TABLE play_event DROP COLUMN label_version")
                execSQL("ALTER TABLE open_play DROP COLUMN label_version")
                execSQL("PRAGMA user_version = 6")
                close()
            }

            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val old = store.since(0).single()
            assertEquals(event("old", 500).copy(id = old.id), old) // the stored play reads back unchanged
            assertNull(old.labelVersion)
            store.append(event("new", 1_000).copy(labelVersion = PlayEvent.LABEL_VERSION))
            val inProgress = event("playing", 2_000).copy(endReason = EndReason.STOPPED, labelVersion = PlayEvent.LABEL_VERSION)
            store.save(inProgress)
            store.close()

            val reopened = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            assertEquals(listOf(null, PlayEvent.LABEL_VERSION), reopened.since(0).map { it.labelVersion })
            assertEquals(inProgress, reopened.take())
            assertEquals(2L, reopened.lastPlayId())
            assertEquals(listOf("yt:old", "yt:new"), reopened.localPlays().map { it.trackKey.value })
            reopened.close()
        }

    @Test
    fun aFreshDatabaseOpensAgainAfterEveryMigration() =
        runTest {
            val file =
                File.createTempFile("turtle", ".db").apply {
                    delete()
                    deleteOnExit()
                }
            val fresh = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            assertNull(fresh.lastPlayId())
            fresh.append(event("a", 1_000).copy(labelVersion = PlayEvent.LABEL_VERSION))
            fresh.close()
            val again = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            assertEquals(PlayEvent.LABEL_VERSION, again.since(0).single().labelVersion)
            again.close()
            val version = JdbcSQLiteDriver().open(file.absolutePath).let { c ->
                c.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }.also { c.close() }
            }
            assertEquals(7L, version)
        }

    private fun tempDbFile() =
        File.createTempFile("turtle", ".db").apply {
            delete()
            deleteOnExit()
        }

    @Test
    fun replaceSourceRollsBackOnFailureWithoutHidingTheError() =
        runTest {
            val file = tempDbFile()
            SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath).apply {
                appendAll(listOf(event("b", 2_000, EventSource.SPOTIFY_IMPORT), event("c", 3_000, EventSource.SPOTIFY_IMPORT)))
                close()
            }
            // A write that fails half-way through the replacement, as a full disk would.
            JdbcSQLiteDriver().open(file.absolutePath).apply {
                execSQL(
                    "CREATE TRIGGER boom BEFORE INSERT ON play_event WHEN NEW.track_key = 'yt:boom' " +
                        "BEGIN SELECT RAISE(ABORT, 'boom'); END",
                )
                close()
            }
            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            val before = store.since(0)
            val error =
                assertFailsWith<Exception> {
                    store.replaceSource(
                        EventSource.SPOTIFY_IMPORT,
                        listOf(event("ok", 4_000, EventSource.SPOTIFY_IMPORT), event("boom", 5_000, EventSource.SPOTIFY_IMPORT)),
                    )
                }
            assertTrue(error.message.orEmpty().contains("boom"), "the original error surfaces: ${error.message}")
            assertEquals(before, store.since(0))
            assertEquals(listOf("yt:b", "yt:c"), store.since(0).map { it.trackKey.value })
            // The rollback ended the transaction: the store keeps working.
            store.append(event("after", 6_000))
            assertEquals(3L, store.count())
            store.close()
        }

    @Test
    fun replaceSourceLeavesOtherSourcesAndThePlayInProgress() =
        runTest {
            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), tempDbFile().absolutePath)
            val local = event("mine", 1_000)
            val localId = store.append(local)
            store.appendAll(listOf(event("old", 2_000, EventSource.SPOTIFY_IMPORT)))
            val inProgress = event("playing", 9_000).copy(endReason = EndReason.STOPPED)
            store.save(inProgress)

            store.replaceSource(
                EventSource.SPOTIFY_IMPORT,
                listOf(event("new1", 3_000, EventSource.SPOTIFY_IMPORT), event("new2", 500, EventSource.SPOTIFY_IMPORT)),
            )
            assertEquals(listOf("yt:new2", "yt:mine", "yt:new1"), store.since(0).map { it.trackKey.value })
            assertEquals(local.copy(id = localId), store.since(0).single { it.source == EventSource.LOCAL })
            assertEquals(inProgress, store.take())

            store.replaceSource(EventSource.SPOTIFY_IMPORT, emptyList())
            assertEquals(listOf("yt:mine"), store.since(0).map { it.trackKey.value })
            assertFailsWith<IllegalArgumentException> { store.replaceSource(EventSource.SPOTIFY_IMPORT, listOf(local)) }
            store.close()
        }

    @Test
    fun sourceRangeCountsOneSource() =
        runTest {
            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), tempDbFile().absolutePath)
            assertNull(store.sourceRange(EventSource.SPOTIFY_IMPORT))
            assertNull(store.firstStartedAt(EventSource.LOCAL))
            store.append(event("a", 1_000))
            store.appendAll(listOf(event("b", 7_000, EventSource.SPOTIFY_IMPORT), event("c", 3_000, EventSource.SPOTIFY_IMPORT)))
            assertEquals(SourceRange(2, 3_000, 7_000), store.sourceRange(EventSource.SPOTIFY_IMPORT))
            assertEquals(SourceRange(1, 1_000, 1_000), store.sourceRange(EventSource.LOCAL))
            assertEquals(1_000L, store.firstStartedAt(EventSource.LOCAL))
            assertNull(store.sourceRange(EventSource.LISTENBRAINZ_IMPORT))
            store.close()
        }

    /** Rolling forward after a bad release can run older code on a database a newer release already upgraded. */
    @Test
    fun olderCodeOpensANewerSchemaWithoutLosingHistory() =
        runTest {
            val file = tempDbFile()
            SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath).apply {
                append(event("a", 1_000))
                close()
            }
            JdbcSQLiteDriver().open(file.absolutePath).apply {
                execSQL("CREATE TABLE from_a_newer_release (id INTEGER PRIMARY KEY)")
                execSQL("PRAGMA user_version = 99")
                close()
            }

            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
            assertEquals(listOf("yt:a"), store.since(0).map { it.trackKey.value })
            store.append(event("b", 2_000))
            assertEquals(2, store.count())
            store.close()
            val version =
                JdbcSQLiteDriver().open(file.absolutePath).let { c ->
                    c.prepare("PRAGMA user_version").use { it.step(); it.getLong(0) }.also { c.close() }
                }
            assertEquals(99L, version, "older code never sets a newer schema's version back")
        }
}
