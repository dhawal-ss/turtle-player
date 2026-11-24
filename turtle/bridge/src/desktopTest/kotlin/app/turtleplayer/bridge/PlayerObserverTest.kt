package app.turtleplayer.bridge

import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.OpenPlayCheckpoint
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource

class PlayerObserverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun song(
        id: String,
        podcastId: String? = null,
        videoType: String? = null,
        duration: Long = 200,
    ) = SongItem(
        id = id,
        title = "Title $id",
        thumbnail = null,
        artists = listOf(ArtistTiny(null, "Artist $id")),
        album = null,
        duration = duration,
        podcastId = podcastId,
        musicVideoType = videoType,
    )

    /**
     * Shell's controller showing [queue], playing its first song; [sampled] completes once the observer
     * reads it, and [samples] counts the reads. [show] swaps what it shows, as a new queue does.
     */
    private class ShellController(
        queue: List<SongItem>,
    ) : PlaybackController {
        val sampled = CompletableDeferred<Unit>()
        val samples = AtomicInteger(0)
        private val shown =
            MutableStateFlow(PlaybackUiState(currentSong = queue.first(), isPlaying = true, queue = queue, currentQueueIndex = 0))
        override val state: StateFlow<PlaybackUiState> = shown

        fun show(
            queue: List<SongItem>,
            index: Int = 0,
        ) {
            shown.value = PlaybackUiState(currentSong = queue[index], isPlaying = true, queue = queue, currentQueueIndex = index)
        }

        override fun currentPositionMs(): Long {
            sampled.complete(Unit)
            samples.incrementAndGet()
            return 5_000
        }

        override fun play(
            song: SongItem,
            queue: List<SongItem>,
            songsOnly: Boolean,
        ) = Unit

        override fun shuffleQueue(
            songs: List<SongItem>,
            queueTitle: String?,
            songsOnly: Boolean,
        ) = Unit

        override fun togglePlayPause() = Unit

        override fun seekTo(positionMs: Long) = Unit

        override fun skipToNext() = Unit

        override fun skipToPrevious() = Unit

        override fun setPlaybackSpeed(speed: Float) = Unit

        override fun startRadio(song: SongItem) = Unit

        override fun playNext(songs: List<SongItem>) = Unit

        override fun addToQueue(songs: List<SongItem>) = Unit
    }

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

    @Test
    fun songChangesAfterShellIsGoneAreJudgedFromTheLastQueue() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val shell = ShellController(listOf(song("a"), song("b"), song("c")))
            val hints = MutableStateFlow<PlaybackController?>(shell)
            val observer =
                PlayerObserver(
                    ListeningSessionTracker(store),
                    controller = { hints.value },
                    isBookChapter = { false },
                    spokenReady = {},
                )
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            current.value = song("a")
            observer.settle("a")
            withTimeout(5_000) { shell.sampled.await() }
            hints.value = null // Shell's composition disposed: Android Auto drives playback now

            current.value = song("b") // next button
            observer.settle("b")
            current.value = song("x") // picked from Android Auto
            observer.settle("x")
            current.value = song("y") // outside any queue seen: cannot tell
            observer.settle("y")
            current.value = null
            observer.settle(null)

            val ends = store.since(0).map { it.endReason }
            assertEquals(listOf(EndReason.SKIPPED, EndReason.STOPPED, EndReason.UNKNOWN, EndReason.STOPPED), ends)
        }

    @Test
    fun aCrossfadeAfterShellIsGoneIsNotASkip() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            // 17 s songs: just outside the crossfade window when they start, inside it after a second of play.
            val queue = listOf(song("a"), song("b", duration = 17), song("c", duration = 17), song("d"))
            val shell = ShellController(queue)
            val hints = MutableStateFlow<PlaybackController?>(shell)
            val observer =
                PlayerObserver(
                    ListeningSessionTracker(store),
                    controller = { hints.value },
                    isBookChapter = { false },
                    spokenReady = {},
                )
            val current = MutableStateFlow<SongItem?>(null)
            val playing = MutableStateFlow(true)
            observer.attach(scope, current, playing, MutableSharedFlow<Unit>())
            current.value = queue[0]
            observer.settle("a")
            withTimeout(5_000) { shell.sampled.await() }
            hints.value = null

            current.value = queue[1] // next button
            observer.settle("b")
            delay(1_500)
            current.value = queue[2] // crossfaded in near the end of b
            observer.settle("c")
            playing.value = false
            delay(1_500) // paused time is not playing time
            playing.value = true
            current.value = queue[3] // next button
            observer.settle("d")
            current.value = null
            observer.settle(null)

            val ends = store.since(0).map { it.endReason }
            assertEquals(listOf(EndReason.SKIPPED, EndReason.COMPLETED, EndReason.SKIPPED, EndReason.STOPPED), ends)
        }

    // ---- Queue occurrences ----------------------------------------------------------------------

    private val radioTag = "radio+ranked/upstream/d"
    private val infinityTag = "infinity/library/f/familiar"

    private fun observer(
        store: InMemoryPlayEventStore,
        controller: () -> PlaybackController? = { null },
        timeSource: TimeSource = TimeSource.Monotonic,
    ) = PlayerObserver(
        ListeningSessionTracker(store),
        controller = controller,
        isBookChapter = { false },
        spokenReady = {},
        timeSource = timeSource,
    )

    @Test
    fun aTagFromAnEarlierOccurrenceNeverLabelsALaterPlay() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val observer = observer(store)
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            val first = observer.beginOccurrence()
            observer.tag("x", radioTag, first)
            val second = observer.beginOccurrence()
            assertTrue(second > first)
            observer.tag("y", radioTag, first) // a page ranked for the first occurrence, back late
            current.value = song("x")
            observer.settle("x")
            assertNull(observer.currentOrigin())
            current.value = song("y")
            observer.settle("y")
            assertNull(observer.currentOrigin())
            current.value = null
            observer.settle(null)
            assertEquals(listOf(null, null), store.since(0).map { it.surfaceDetail })
        }

    @Test
    fun continuationPagesKeepTheirOccurrence() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val observer = observer(store)
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            val occ = observer.beginOccurrence()
            observer.tag("p1", radioTag, occ) // the first page
            current.value = song("p1")
            observer.settle("p1")
            observer.tagAll(listOf("p2" to radioTag), observer.occurrence()) // a continuation, same occurrence
            current.value = song("p2")
            observer.settle("p2")
            assertEquals(occ, observer.occurrence())
            assertEquals("radio", observer.currentOrigin()?.place?.tag)
            current.value = null
            observer.settle(null)
            assertEquals(listOf(radioTag, radioTag), store.since(0).map { it.surfaceDetail })
        }

    @Test
    fun aPlaylistTappedBeforeAnySampleStillDropsRadioTags() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val shell = ShellController(listOf(song("a"), song("b")))
            val observer = observer(store, controller = { shell })
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            current.value = song("a")
            observer.settle("a")
            observer.tag("c", radioTag) // the radio ranked c; the listener then taps a playlist holding it
            val before = observer.occurrence()
            // Queue and song change together, within one sample.
            shell.show(listOf(song("c"), song("d")))
            current.value = song("c")
            observer.settle("c")
            assertNull(observer.currentOrigin(), "a radio tag labelled a playlist play")
            assertTrue(observer.occurrence() > before, "a queue replaced outside Turtle is a new occurrence")

            // Control: the Infinity Mix begins its occurrence, tags its first song, then replaces the queue.
            val mix = observer.beginOccurrence()
            observer.tag("i1", infinityTag, mix)
            shell.show(listOf(song("i1"), song("i2")))
            current.value = song("i1")
            observer.settle("i1")
            assertEquals("infinity", observer.currentOrigin()?.place?.tag)
            assertEquals(mix, observer.occurrence())
        }

    @Test
    fun aStaleControllerStateIsNoQueueReplacement() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            // Shell's state stopped updating while "old" played (nothing collects it): it never shows a or b.
            val shell = ShellController(listOf(song("old"), song("x")))
            val observer = observer(store, controller = { shell })
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            current.value = song("old")
            observer.settle("old")
            withTimeout(5_000) { shell.sampled.await() }
            // The radio carries on: a, then b, appended and tagged while the state stays stale.
            current.value = song("a")
            observer.settle("a")
            val before = observer.occurrence()
            observer.tag("b", radioTag)
            current.value = song("b")
            observer.settle("b")
            assertEquals(before, observer.occurrence(), "a stale queue was read as the listener replacing it")
            assertEquals("radio", observer.currentOrigin()?.place?.tag)
        }

    @Test
    fun unqueuedTagsArePrunedAfterGrace() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val time = TestTimeSource()
            val shell = ShellController(listOf(song("a"), song("b"), song("w")))
            val observer = observer(store, controller = { shell }, timeSource = time)
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            current.value = song("a")
            observer.settle("a")
            observer.tag("w", radioTag) // queued
            observer.tag("z", radioTag) // ranked but never queued (desktop keeps a few of a page)
            time += 11.seconds
            val seen = shell.samples.get()
            withTimeout(5_000) { while (shell.samples.get() < seen + 2) delay(20) }
            shell.show(listOf(song("a"), song("b"), song("w"), song("z")), index = 2)
            current.value = song("w")
            observer.settle("w")
            assertEquals("radio", observer.currentOrigin()?.place?.tag, "a queued song keeps its tag")
            shell.show(listOf(song("a"), song("b"), song("w"), song("z")), index = 3)
            current.value = song("z")
            observer.settle("z")
            assertNull(observer.currentOrigin(), "a tag for a song that never reached the queue was kept")
        }

    @Test
    fun anOlderAttributionDoesNotLabelAfterANewOccurrence() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val observer = observer(store)
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            observer.attribute(mapOf("x" to "home-yt/-/-/row1", "y" to "home-yt/-/-/row1"))
            current.value = song("x")
            observer.settle("x")
            observer.beginOccurrence() // Shell's Start radio on x
            current.value = song("y")
            observer.settle("y")
            current.value = null
            observer.settle(null)
            assertEquals(listOf("home-yt/-/-/row1", null), store.since(0).map { it.surfaceDetail })
        }

    /** The play in progress as last saved; starts as a play saved by an earlier checkpoint. */
    private class SavedPlay : OpenPlayCheckpoint {
        @Volatile
        var play: PlayEvent? =
            PlayEvent(
                track = song("m1").toTrackMeta(),
                sessionId = "s",
                position = 0,
                surface = Surface.QUEUE,
                startReason = StartReason.UNKNOWN,
                endReason = EndReason.STOPPED,
                startedAtMs = 0,
                msPlayed = 30_000,
                durationMs = 200_000,
            )

        override suspend fun save(play: PlayEvent?) {
            this.play = play
        }

        override suspend fun take(): PlayEvent? = play.also { play = null }

        private companion object {
            fun song(id: String) = SongItem(id = id, title = "Title $id", thumbnail = null, artists = listOf(ArtistTiny(null, "Artist $id")), album = null, duration = 200)
        }
    }

    @Test
    fun pausingHistoryMidSongDropsItEvenWhenResumedBeforeItEnds() =
        runBlocking {
            val store = InMemoryPlayEventStore()
            val saved = SavedPlay()
            val paused = MutableStateFlow(false)
            val tracker = ListeningSessionTracker(store, checkpoint = saved, recording = { !paused.value })
            val observer = PlayerObserver(tracker, controller = { null }, spokenReady = {})
            val current = MutableStateFlow<SongItem?>(null)
            observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
            observer.followHistoryPause(scope, paused)

            current.value = song("m1")
            observer.settle("m1")
            paused.value = true
            // The saved play is cleared at once, before any periodic checkpoint.
            withTimeout(5_000) { while (saved.play != null) delay(10) }
            paused.value = false
            current.value = song("m2")
            observer.settle("m2")
            current.value = null
            observer.settle(null)

            assertEquals(listOf("yt:m2"), store.since(0).map { it.trackKey.value })
            assertNull(saved.take())
        }
}
