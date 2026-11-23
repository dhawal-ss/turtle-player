package app.turtleplayer.bridge

import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.Surface
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerObserverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun song(
        id: String,
        podcastId: String? = null,
        videoType: String? = null,
    ) = SongItem(
        id = id,
        title = "Title $id",
        thumbnail = null,
        artists = listOf(ArtistTiny(null, "Artist $id")),
        album = null,
        duration = 200,
        podcastId = podcastId,
        musicVideoType = videoType,
    )

    /** Waits until the observer has taken in the latest song (null: nothing it learns from is playing). */
    private suspend fun PlayerObserver.settle(id: String?) = withTimeout(5_000) { while (currentSong()?.id != id) delay(10) }

    @Test
    fun spokenWordNeverReachesTheListeningLog() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val observer =
                PlayerObserver(
                    ListeningSessionTracker(store),
                    controller = { null },
                    isBookChapter = { it == "b1" },
                    spokenReady = {},
                )
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())

            current.value = song("m1")
            observer.settle("m1")
            current.value = song("p1", podcastId = "MPSPshow")
            observer.settle(null)
            current.value = song("m2")
            observer.settle("m2")
            current.value = song("p2", videoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE")
            observer.settle(null) // no show id
            current.value = song("m3")
            observer.settle("m3")
            current.value = song("b1")
            observer.settle(null) // a chapter of a book Turtle opened

            val events = store.since(0)
            assertEquals(listOf("yt:m1", "yt:m2", "yt:m3"), events.map { it.trackKey.value })
            assertTrue(events.all { it.endReason == EndReason.STOPPED })
        }

    @Test
    fun aChapterRestoredAtLaunchWaitsForSavedBooks() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val booksLoaded = CompletableDeferred<Unit>()
            var known = emptySet<String>()
            val observer =
                PlayerObserver(
                    ListeningSessionTracker(store),
                    controller = { null },
                    isBookChapter = { it in known },
                    spokenReady = { booksLoaded.await() },
                )
            // Upstream restored a book chapter before Turtle's saved books were read.
            val current = MutableStateFlow<SongItem?>(song("b1"))
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            delay(200)
            known = setOf("b1")
            booksLoaded.complete(Unit)

            current.value = song("m1")
            observer.settle("m1")
            current.value = null
            observer.settle(null)

            assertEquals(listOf("yt:m1"), store.since(0).map { it.trackKey.value })
        }

    @Test
    fun queueAttributionEndsWhenTheQueueMovesOn() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val observer =
                PlayerObserver(
                    ListeningSessionTracker(store),
                    controller = { null },
                    isBookChapter = { false },
                    spokenReady = {},
                )
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            observer.attribute(mapOf("a" to "home-yt/-/-/row1", "b" to "home-yt/-/-/row1"))
            current.value = song("a")
            observer.settle("a")
            current.value = song("b")
            observer.settle("b")
            current.value = song("c")
            observer.settle("c") // radio after the row: not in it
            current.value = song("a")
            observer.settle("a") // played again from the Library
            current.value = null
            observer.settle(null)
            val events = store.since(0)
            assertEquals(listOf("home-yt/-/-/row1", "home-yt/-/-/row1", null, null), events.map { it.surfaceDetail })
            assertEquals(listOf(Surface.SHELF, Surface.SHELF, Surface.QUEUE, Surface.QUEUE), events.map { it.surface })
        }

    @Test
    fun attributionSetJustBeforePlaybackTagsTheTappedSong() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val observer =
                PlayerObserver(
                    ListeningSessionTracker(store),
                    controller = { null },
                    isBookChapter = { false },
                    spokenReady = {},
                )
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            observer.attribute(mapOf("s" to "search/-/-"))
            current.value = song("s") // no suspension in between
            observer.settle("s")
            current.value = null
            observer.settle(null)
            assertEquals(Surface.SEARCH, store.since(0).single().surface)
        }

    @Test
    fun theNewerOfQueueAndSongTagWins() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val observer =
                PlayerObserver(
                    ListeningSessionTracker(store),
                    controller = { null },
                    isBookChapter = { false },
                    spokenReady = {},
                )
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            observer.attribute(mapOf("x" to "home-grid/-/-", "y" to "home-grid/-/-"))
            observer.tag("x", "infinity/library/f/familiar") // Infinity queued it after the Home tap
            current.value = song("x")
            observer.settle("x")
            observer.tag("z", "radio/upstream/d") // radio ranked it earlier...
            observer.attribute(mapOf("z" to "search/-/-")) // ...then the listener tapped it in Search
            current.value = song("z")
            observer.settle("z")
            current.value = null
            observer.settle(null)
            assertEquals(listOf("infinity/library/f/familiar", "search/-/-"), store.since(0).map { it.surfaceDetail })
            assertEquals(listOf(Surface.AUTOPLAY, Surface.SEARCH), store.since(0).map { it.surface })
        }
}
