package app.turtleplayer.bridge

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import app.turtleplayer.taste.SavedKind
import app.turtleplayer.taste.SavedTrack
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SaveWatcherTest {
    private val file =
        File.createTempFile("turtle", ".db").apply {
            delete()
            deleteOnExit()
        }
    private val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
    private val saved = MutableStateFlow<List<SavedTrack>?>(null)
    private val likes = MutableSharedFlow<SongItem>(extraBufferCapacity = 16)

    @Volatile private var now = 1_000_000L

    @Volatile private var recording = true

    private val watcher = SaveWatcher(store, saved, likes = { likes }, recording = { recording }, nowMs = { now }, pollMs = 10)

    @AfterTest
    fun close() = store.close()

    private fun meta(id: String) = TrackMeta(TrackKey.youtube(id), "Title $id", listOf("Artist"), durationMs = 200_000)

    private fun library(vararg ids: String) = ids.map { SavedTrack(meta(it), SavedKind.LIBRARY, atMs = 0) }

    private fun song(id: String) =
        SongItem(
            id = id,
            title = "Title $id",
            thumbnail = null,
            artists = listOf(ArtistTiny(null, "Artist")),
            album = null,
            duration = 200,
        )

    private suspend fun saves() = store.feedbackSince(0)

    private suspend fun settle() {
        now += PlaylistAdds.SETTLE_MS
        watcher.recordDue()
    }

    @Test
    fun theFirstSnapshotIsTheBaselineAndALaterLibraryAddIsRecordedOnceSettled() =
        runBlocking {
            watcher.observeLibrary(library("a"))
            settle()
            assertEquals(emptyList(), saves())

            now += 1_000
            watcher.observeLibrary(library("a", "b"))
            watcher.recordDue()
            assertEquals(emptyList(), saves(), "waits for the library to settle")
            val settledAt = now + PlaylistAdds.SETTLE_MS
            settle()
            val recorded = saves().single()
            assertEquals(TrackFeedback(recorded.id, meta("b"), TrackFeedbackKind.SAVED, settledAt, TrackFeedback.SAVED_LIBRARY), recorded)

            watcher.observeLibrary(library("a"))
            watcher.observeLibrary(library("a", "b"))
            settle()
            assertEquals(1, saves().size, "a re-add in the same run is not a second save")
        }

    @Test
    fun aBulkLibrarySyncIsDropped() =
        runBlocking {
            watcher.observeLibrary(emptyList())
            watcher.observeLibrary(library(*Array(PlaylistAdds.MAX_BURST + 1) { "s$it" }))
            settle()
            assertEquals(emptyList(), saves())
        }

    @Test
    fun likedRowsInTheLibraryListAreNotLibraryAdds() =
        runBlocking {
            watcher.observeLibrary(emptyList())
            watcher.observeLibrary(listOf(SavedTrack(meta("l"), SavedKind.LIKED, atMs = 0)))
            settle()
            assertEquals(emptyList(), saves())
        }

    @Test
    fun nothingIsRecordedWhileHistoryIsPaused() =
        runBlocking {
            recording = false
            watcher.onLike(song("x"))
            watcher.observeLibrary(emptyList())
            watcher.observeLibrary(library("y"))
            settle()
            assertEquals(emptyList(), saves())
            recording = true
            settle()
            assertEquals(emptyList(), saves(), "an add settled while paused is not recorded later")
        }

    @Test
    fun aLibraryAddMadeWhileHistoryWasPausedIsNotRecordedAfterItResumes() =
        runBlocking {
            watcher.observeLibrary(library("a"))
            recording = false
            now += 1_000
            watcher.observeLibrary(library("a", "p"))
            recording = true
            now += 1_000
            watcher.observeLibrary(library("a", "p", "r"))
            now += PlaylistAdds.SETTLE_MS
            watcher.recordDue()
            assertEquals(listOf(meta("r")), saves().map { it.track }, "only the add made while recording")
        }

    @Test
    fun aBrokenLikeSourceDoesNotStopLibraryAdds() =
        runBlocking {
            val failing: () -> Nothing = { throw ExceptionInInitializerError("no settings") }
            val broken = SaveWatcher(store, saved, likes = failing, recording = { true }, nowMs = { now }, pollMs = 10)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                saved.value = library("a")
                broken.start(scope)
                withTimeout(5_000) { while (store.pref(PREF_MEASURE_SINCE) == null) delay(10) }
                delay(100)
                saved.value = library("a", "b")
                delay(100)
                now += PlaylistAdds.SETTLE_MS
                withTimeout(5_000) { while (saves().isEmpty()) delay(10) }
                assertEquals(listOf(meta("b")), saves().map { it.track })
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun aRunningWatcherRecordsInAppLikesAndMarksTheMeasurementStartOnce() =
        runBlocking {
            store.setPref("unrelated", "1")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                watcher.start(scope)
                withTimeout(5_000) { while (store.pref(PREF_MEASURE_SINCE) == null) delay(10) }
                assertEquals(now.toString(), store.pref(PREF_MEASURE_SINCE))

                val likedAt = now
                // The collector may not be subscribed yet: keep offering until it is recorded.
                withTimeout(5_000) {
                    while (saves().isEmpty()) {
                        likes.tryEmit(song("x"))
                        delay(20)
                    }
                }
                val like = saves().first()
                assertEquals(TrackFeedbackKind.SAVED, like.kind)
                assertEquals(TrackFeedback.SAVED_LIKED, like.detail)
                assertEquals(TrackKey.youtube("x"), like.track.key)
                assertEquals(likedAt, like.atMs)
            } finally {
                scope.cancel()
            }

            now += 5_000
            watcher.markMeasurementStart()
            SaveWatcher(store, saved, likes = { likes }, recording = { true }, nowMs = { now }).markMeasurementStart()
            assertEquals((now - 5_000).toString(), store.pref(PREF_MEASURE_SINCE), "written once, never moved")
            assertTrue(saves().all { it.kind == TrackFeedbackKind.SAVED })
        }
}
