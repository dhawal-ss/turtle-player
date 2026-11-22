package app.turtleplayer.bridge

import app.turtleplayer.events.EndReason
import app.turtleplayer.events.FeedbackKind
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.PlaybackSignal
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Upstream's playback controller while Shell is on screen. It adds the queue order and the playhead,
 * which the player flows of the hook lines lack. Null in the background on Android (no UI), when the
 * observer falls back to inferring skips from listening time alone.
 */
internal object PlaybackHints {
    @Volatile
    var controller: PlaybackController? = null
}

/**
 * Feeds the listening-event log from upstream's player flows. Android (`AndroidPlaybackPlayer`) and
 * desktop (`MusicPlayer`) expose the same three flows, so one hook line per platform attaches it.
 *
 * End reasons: a natural end of track (EOF) is COMPLETED and the next track is AUTOPLAY. A track
 * change without EOF is classified from the queue when the controller is known: the next queue item
 * is the next button (or a crossfade, near the end), the previous one is the back button, and any
 * other song is the listener choosing something else, which is not a verdict on the old song.
 * Podcast episodes are not music taste and are not recorded.
 */
class PlayerObserver(
    private val tracker: ListeningSessionTracker,
    private val controller: () -> PlaybackController? = { PlaybackHints.controller },
) {
    private val mutex = Mutex()
    private var current: SongItem? = null
    private val currentId: String? get() = current?.id
    // Why our engines queued a song (QueueItem.surfaceDetail), so its play is attributed to the right generator.
    private val pendingTags = LinkedHashMap<String, String>()
    private var endedByEof = false
    private var repeatCheck: Job? = null
    private var startedAt: TimeMark? = null

    // What the controller showed while the current song played: its queue and the last playhead.
    private var queue: List<SongItem> = emptyList()
    private var queueIndex = -1
    private var lastPositionMs = -1L
    private var lastPositionAt: TimeMark? = null
    private var lastPlaying = false
    private var durationSent = false

    fun attach(
        scope: CoroutineScope,
        currentSong: StateFlow<SongItem?>,
        isPlaying: StateFlow<Boolean>,
        onEndOfTrack: Flow<*>,
    ) {
        scope.launch {
            currentSong.map { it }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { song ->
                guarded("song change") { mutex.withLock { onSongChanged(song, isPlaying.value) } }
            }
        }
        scope.launch {
            isPlaying.collect { playing ->
                guarded("play state") { mutex.withLock { if (currentId != null) tracker.onSignal(PlaybackSignal.PlayingChanged(playing)) } }
            }
        }
        scope.launch {
            onEndOfTrack.collect {
                guarded("end of track") {
                    mutex.withLock {
                        // A stale end-of-track event from the previous song can arrive just after a switch.
                        val fresh = (startedAt?.elapsedNow()?.inWholeMilliseconds ?: Long.MAX_VALUE) >= STALE_EOF_MS
                        if (currentId != null && !endedByEof && fresh) {
                            endedByEof = true
                            tracker.onSignal(PlaybackSignal.TrackEnded(EndReason.COMPLETED))
                        }
                    }
                }
                // Repeat-one replays the same song, so no song change follows the EOF.
                repeatCheck?.cancel()
                repeatCheck = scope.launch {
                    delay(REPEAT_DETECT_MS)
                    guarded("repeat check") {
                        mutex.withLock {
                            val song = currentSong.value
                            if (endedByEof && song != null && song.id == currentId && isPlaying.value) {
                                start(song, StartReason.AUTOPLAY, playing = true)
                            }
                        }
                    }
                }
            }
        }
        scope.launch {
            while (isActive) {
                delay(SAMPLE_MS)
                guarded("playhead") { mutex.withLock { sample() } }
            }
        }
    }

    /** Explicit feedback on the song playing now (like, less like this), recorded on its play event. */
    suspend fun feedback(kind: FeedbackKind) = mutex.withLock { if (currentId != null) tracker.onSignal(PlaybackSignal.Feedback(kind)) }

    /** The song playing now, if any. */
    suspend fun currentSong(): SongItem? = mutex.withLock { current }

    /** Remembers why [songId] was queued until it starts playing. */
    suspend fun tag(songId: String, surfaceDetail: String) = mutex.withLock {
        pendingTags.remove(songId)
        pendingTags[songId] = surfaceDetail
        while (pendingTags.size > MAX_TAGS) pendingTags.remove(pendingTags.keys.first())
    }

    /** Records the play that was in progress when the app last stopped, then saves the open play every few seconds. */
    fun startCheckpoints(scope: CoroutineScope) {
        scope.launch {
            guarded("recover interrupted play") { tracker.recover()?.let { TurtleLog.info("recovered an interrupted play") } }
            while (isActive) {
                delay(CHECKPOINT_MS)
                guarded("checkpoint") { tracker.checkpoint() }
            }
        }
    }

    private suspend fun onSongChanged(song: SongItem?, playing: Boolean) {
        repeatCheck?.cancel()
        val music = song?.takeUnless { it.podcastId != null }
        if (music == null) {
            if (currentId != null && !endedByEof) tracker.onSignal(PlaybackSignal.TrackEnded(EndReason.STOPPED))
            current = null
            endedByEof = false
            return
        }
        val previous = current
        val reason = when {
            endedByEof -> StartReason.AUTOPLAY
            previous == null -> StartReason.UNKNOWN
            else -> classify(previous, music)
        }
        start(music, reason, playing)
    }

    /** How the listener got from [previous] to [next], judged from the queue they were in. */
    private fun classify(previous: SongItem, next: SongItem): StartReason {
        if (controller() == null || queue.isEmpty()) return StartReason.UNKNOWN
        val at = queueIndex.takeIf { queue.getOrNull(it)?.id == previous.id } ?: queue.indexOfFirst { it.id == previous.id }
        if (at < 0) return StartReason.UNKNOWN
        return when (next.id) {
            queue.getOrNull(at + 1)?.id -> {
                // Crossfade switches songs before the end without an EOF: near the end it is autoplay.
                val remaining = previous.duration?.let { it * 1000 - lastPositionMs }
                if (lastPositionMs >= 0 && remaining != null && remaining <= CROSSFADE_WINDOW_MS) StartReason.AUTOPLAY else StartReason.USER_NEXT
            }
            queue.getOrNull(at - 1)?.id -> StartReason.USER_PREVIOUS
            else -> StartReason.TAPPED
        }
    }

    private suspend fun start(song: SongItem, reason: StartReason, playing: Boolean) {
        current = song
        endedByEof = false
        startedAt = TimeSource.Monotonic.markNow()
        lastPositionMs = -1L
        lastPositionAt = null
        durationSent = false
        val tag = pendingTags.remove(song.id)
        tracker.onSignal(
            PlaybackSignal.TrackStarted(
                track = song.toTrackMeta(),
                reason = reason,
                // Upstream does not tell us where a queue came from yet; the queue hook will.
                surface = if (reason == StartReason.AUTOPLAY) Surface.AUTOPLAY else Surface.QUEUE,
                surfaceDetail = tag ?: song.queueSourceTitle,
                playing = playing,
            ),
        )
    }

    /** Reads the controller's queue and playhead for the current song, reporting seeks and a late duration. */
    private suspend fun sample() {
        val c = controller() ?: return
        val state = c.state.value
        val id = currentId ?: return
        if (state.currentSong?.id != id) return
        queue = state.queue
        queueIndex = state.currentQueueIndex
        if (!durationSent && current?.duration == null && state.durationMs > 0) {
            durationSent = true
            tracker.onSignal(PlaybackSignal.DurationKnown(state.durationMs))
        }
        val position = c.currentPositionMs()
        val previousAt = lastPositionAt
        if (previousAt != null && lastPositionMs >= 0 && !endedByEof) {
            val expected = if (lastPlaying && state.isPlaying) previousAt.elapsedNow().inWholeMilliseconds else 0L
            if (abs(position - lastPositionMs - expected) > SEEK_TOLERANCE_MS) {
                tracker.onSignal(PlaybackSignal.Seeked(lastPositionMs, position))
            }
        }
        lastPositionMs = position
        lastPositionAt = TimeSource.Monotonic.markNow()
        lastPlaying = state.isPlaying
    }

    /** One failing signal (a locked database, a bad song) must not stop recording for the rest of the run. */
    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("player observer: $what failed", e)
        }
    }

    private companion object {
        const val REPEAT_DETECT_MS = 1_500L
        const val MAX_TAGS = 500
        const val SAMPLE_MS = 1_000L
        const val SEEK_TOLERANCE_MS = 3_000L
        const val STALE_EOF_MS = 2_000L
        const val CHECKPOINT_MS = 10_000L
        // Upstream caps crossfades at 15 s; a switch this close to the end is a transition, not a skip.
        const val CROSSFADE_WINDOW_MS = 16_000L
    }
}
