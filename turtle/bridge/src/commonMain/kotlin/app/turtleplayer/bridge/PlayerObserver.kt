package app.turtleplayer.bridge

import app.turtleplayer.events.EndReason
import app.turtleplayer.events.FeedbackKind
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.PlaybackSignal
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.PlayOrigin
import app.turtleplayer.taste.SurfaceTag
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.abs
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Upstream's playback controller while Shell is on screen. It adds the queue order and the playhead,
 * which the player flows of the hook lines lack. Null once Shell is gone (Activity destroyed, Android
 * Auto), when the observer classifies song changes against the queue it last saw.
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
 * change without EOF is classified from the controller's queue, or the last one seen without it: the
 * next queue item is the next button (or a crossfade, near the end), the previous one is the back
 * button, and any other song is the listener choosing something else, which is not a verdict on the
 * old song. A change outside that queue stays unclassified.
 * Spoken word (TurtleSpoken.isSpokenWord) is not music taste and is not recorded; whether history is
 * paused is the tracker's concern.
 */
@OptIn(ExperimentalAtomicApi::class)
class PlayerObserver(
    private val tracker: ListeningSessionTracker,
    private val controller: () -> PlaybackController? = { PlaybackHints.controller },
    private val isBookChapter: (String) -> Boolean = { TurtleSpoken.bookOf(it) != null },
    /** Awaited once before the first song is classified, so a restored chapter is known as a book. */
    private val spokenReady: suspend () -> Unit = { TurtleSpoken.load() },
    /** Ages pending tags for pruning; tests replace it. */
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    private val mutex = Mutex()
    private var current: SongItem? = null
    private val currentId: String? get() = current?.id

    // The queue occurrence: one Home mix, search pick, station or Infinity Mix, from the moment it is
    // started until the next one. Learning is bound to it: a tag is only good inside the occurrence it
    // was written for, and the taste engine starts a fresh short-term session at each new one.
    private val occurrenceFlow = MutableStateFlow(0L)

    /** The current queue occurrence; moves on whenever a new one begins (see [beginOccurrence]). */
    val occurrences: StateFlow<Long> = occurrenceFlow.asStateFlow()

    // The occurrence when the current song started: a queue replaced while it is unchanged was
    // replaced outside Turtle (a playlist tapped in upstream's screens), which begins a new one.
    private var occurrenceAtStart = 0L

    // Why our engines queued a song (QueueItem.surfaceDetail), so its play is attributed to the right generator,
    // with the stamp it was given: the newer of a song's own tag and its queue's attribution wins.
    private class Tag(
        val detail: String,
        val stamp: Long,
        val occurrence: Long,
        val at: TimeMark,
    )

    private val pendingTags = LinkedHashMap<String, Tag>()
    private val stamps = AtomicLong(0)

    private class QueueTags(
        val tags: Map<String, String>,
        val stamp: Long,
        val occurrence: Long,
    )

    // The tag the playing song was bound to when it started (null: not attributed).
    private var currentTag: String? = null

    // The queue the listener started (song id to surface detail), until a song outside it plays.
    private val queueTags = AtomicReference<QueueTags?>(null)
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
    private var replacedQueueSeen = false

    // The playhead carried forward by playing time alone (pauses excluded), for changes no controller sampled.
    private var reckonedMs = 0L
    private var reckonedSince: TimeMark? = null

    fun attach(
        scope: CoroutineScope,
        currentSong: StateFlow<SongItem?>,
        isPlaying: StateFlow<Boolean>,
        onEndOfTrack: Flow<*>,
    ) {
        scope.launch {
            guarded("saved books") { spokenReady() }
            currentSong.map { it }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { song ->
                guarded("song change") { mutex.withLock { onSongChanged(song, isPlaying.value) } }
            }
        }
        scope.launch {
            isPlaying.collect { playing ->
                guarded("play state") {
                    mutex.withLock {
                        if (currentId != null) {
                            reckonFrom(playhead(), playing)
                            tracker.onSignal(PlaybackSignal.PlayingChanged(playing))
                        }
                    }
                }
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

    /** The current queue occurrence (see [occurrences]). Read it before async work that tags songs. */
    fun occurrence(): Long = occurrenceFlow.value

    /** Where the playing song came from, as bound when it started; null when it was not attributed. */
    suspend fun currentOrigin(): PlayOrigin? = mutex.withLock { SurfaceTag.parse(currentTag) }

    /**
     * A new queue occurrence begins (the Infinity Mix starts, Shell's Start radio): tags and the
     * attributed queue of the last one are dropped. Returns the new occurrence, to tag its songs with.
     */
    suspend fun beginOccurrence(): Long =
        mutex.withLock {
            pendingTags.clear()
            queueTags.store(null)
            nextOccurrence()
        }

    /**
     * Remembers why [songId] was queued until it starts playing. [gen] is the occurrence the song
     * was picked for (read before any slow work): a tag for an earlier one is dropped.
     */
    suspend fun tag(
        songId: String,
        surfaceDetail: String,
        gen: Long = occurrence(),
    ) = mutex.withLock { tagLocked(songId, surfaceDetail, gen) }

    /** [tag] for a whole page under one lock. */
    suspend fun tagAll(
        tags: List<Pair<String, String>>,
        gen: Long,
    ) = mutex.withLock { for ((id, detail) in tags) tagLocked(id, detail, gen) }

    private fun tagLocked(
        songId: String,
        surfaceDetail: String,
        gen: Long,
    ) {
        if (gen != occurrence()) return
        pendingTags.remove(songId)
        pendingTags[songId] = Tag(surfaceDetail, stamps.incrementAndFetch(), gen, timeSource.markNow())
        while (pendingTags.size > MAX_TAGS) pendingTags.remove(pendingTags.keys.first())
    }

    /**
     * The queue the listener is starting, song id → surface detail. It begins a new queue occurrence,
     * replaces the previous queue's attribution and lasts until a song outside it starts. Not
     * suspending: call it on the UI thread right before starting playback. A per-song [tag] made
     * later (in the same occurrence) wins over it, and an earlier one loses.
     */
    fun attribute(tags: Map<String, String>) {
        val occ = nextOccurrence()
        queueTags.store(QueueTags(tags.toMap(), stamps.incrementAndFetch(), occ))
    }

    private fun nextOccurrence(): Long = occurrenceFlow.updateAndGet { it + 1 }

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

    /**
     * Follows the listener's "pause listening history" setting live: the moment it turns on, the play in
     * progress is dropped (even if history is back on before it ends) and its saved checkpoint cleared.
     * Explicit votes (likes, playlist adds, Not interested) are recorded elsewhere and still apply.
     */
    fun followHistoryPause(
        scope: CoroutineScope,
        paused: Flow<Boolean>,
    ) {
        scope.launch {
            paused.distinctUntilChanged().collect { if (it) guarded("history paused") { tracker.onHistoryPaused() } }
        }
    }

    private suspend fun onSongChanged(song: SongItem?, playing: Boolean) {
        repeatCheck?.cancel()
        val music = song?.takeUnless { TurtleSpoken.isSpokenWord(it, isBookChapter) }
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
        // Was the queue the last song played in replaced? Read before start(), which forgets the flag. The
        // live queue catches a tap that changes queue and song within one sample. Only a state showing this
        // change (the old or the new song) is trusted: one nobody has collected lately (an Android state
        // shared while subscribed, Shell collapsed or backgrounded) holds an old queue without later songs.
        // For the same reason classify's "previous song not in its queue" is no evidence: that queue may
        // be from a song before it, when no fresh sample came while it played.
        val replaced =
            previous != null &&
                (
                    replacedQueueSeen ||
                        controller()?.state?.value?.takeIf { it.currentSong?.id == music.id || it.currentSong?.id == previous.id }
                            ?.queue?.let { q -> q.isNotEmpty() && q.none { it.id == previous.id } } == true
                )
        start(music, reason, playing, replaced)
    }

    /** How the listener got from [previous] to [next], judged from the queue they were in. */
    private fun classify(previous: SongItem, next: SongItem): StartReason {
        if (queue.isEmpty()) {
            // Never a skip (ListeningSessionTracker); the drive log says why the change went unclassified.
            TurtleLog.info("player observer: start reason unknown (no queue seen yet)")
            return StartReason.UNKNOWN
        }
        val at = queueIndex.takeIf { queue.getOrNull(it)?.id == previous.id } ?: queue.indexOfFirst { it.id == previous.id }
        if (at < 0) {
            // With Shell on screen the queue is current, so the one it played from was replaced: the listener
            // chose something else, a change of plan. Without Shell the queue may be stale: leave it unclassified.
            val live = controller() != null
            TurtleLog.info("player observer: previous song not in its queue (${if (live) "read as choosing another" else "no controller"})")
            return if (live) StartReason.TAPPED else StartReason.UNKNOWN
        }
        return when (next.id) {
            queue.getOrNull(at + 1)?.id -> {
                // Crossfade switches songs before the end without an EOF: near the end it is autoplay.
                val durationMs = previous.duration?.let { it * 1000 }
                when {
                    // Without Shell nothing reports the duration, so a crossfade cannot be ruled out.
                    durationMs == null -> if (controller() == null) StartReason.UNKNOWN else StartReason.USER_NEXT
                    durationMs - playhead() <= CROSSFADE_WINDOW_MS -> StartReason.AUTOPLAY
                    else -> StartReason.USER_NEXT
                }
            }
            queue.getOrNull(at - 1)?.id -> StartReason.USER_PREVIOUS
            else -> StartReason.TAPPED
        }
    }

    private suspend fun start(
        song: SongItem,
        reason: StartReason,
        playing: Boolean,
        replaced: Boolean = false,
    ) {
        current = song
        endedByEof = false
        startedAt = TimeSource.Monotonic.markNow()
        lastPositionMs = -1L
        lastPositionAt = null
        durationSent = false
        replacedQueueSeen = false
        reckonFrom(0, playing)
        if (replaced && occurrence() == occurrenceAtStart) {
            // The queue was replaced and Turtle began nothing since the last song started: the listener
            // started something outside Turtle. A new occurrence, and nothing queued for the last one
            // (a radio page's tags) may label it.
            pendingTags.clear()
            queueTags.store(null)
            nextOccurrence()
            TurtleLog.info("player observer: queue replaced outside Turtle, new occurrence")
        }
        val occ = occurrence()
        occurrenceAtStart = occ
        val own = pendingTags.remove(song.id)?.takeIf { it.occurrence == occ }
        val q = queueTags.load()
        if (q != null && q.occurrence != occ) queueTags.compareAndSet(q, null)
        val live = q?.takeIf { it.occurrence == occ }
        val fromQueue = live?.tags?.get(song.id)
        // A song outside the attributed queue: the listener moved on, so the attribution ends.
        if (live != null && fromQueue == null) queueTags.compareAndSet(live, null)
        val tag =
            when {
                own == null -> fromQueue
                fromQueue == null || own.stamp > live.stamp -> own.detail
                else -> fromQueue
            }
        currentTag = tag
        val origin = SurfaceTag.parse(tag)
        tracker.onSignal(
            PlaybackSignal.TrackStarted(
                track = song.toTrackMeta(),
                reason = reason,
                // Attributed plays know their surface; otherwise upstream does not tell us where a queue came from.
                surface = origin?.surface ?: if (reason == StartReason.AUTOPLAY) Surface.AUTOPLAY else Surface.QUEUE,
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
        // A new queue arrives before its first song starts: keep the one the current song plays in, so
        // the switch reads as choosing something else rather than an unexplained change.
        if (state.queue.none { it.id == id }) {
            if (!replacedQueueSeen) TurtleLog.info("player observer: new queue arrived before its first song")
            replacedQueueSeen = true
        } else {
            queue = state.queue
            queueIndex = state.currentQueueIndex
            pruneTags(state.queue)
        }
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
        reckonFrom(position, state.isPlaying)
    }

    /**
     * Drops tags of an earlier occurrence, and tags for songs that have not reached the live [queue]
     * within [TAG_GRACE_MS] (a radio page's songs that were never queued, or were taken off again).
     */
    private fun pruneTags(queue: List<SongItem>) {
        if (pendingTags.isEmpty()) return
        val occ = occurrence()
        val queued = queue.mapTo(HashSet()) { it.id }
        pendingTags.entries.removeAll { (id, t) ->
            t.occurrence != occ || (id !in queued && t.at.elapsedNow().inWholeMilliseconds > TAG_GRACE_MS)
        }
    }

    /** Where the current song's playhead should be: the last known point plus the time played since. */
    private fun playhead(): Long = reckonedMs + (reckonedSince?.elapsedNow()?.inWholeMilliseconds ?: 0L)

    private fun reckonFrom(positionMs: Long, playing: Boolean) {
        reckonedMs = positionMs
        reckonedSince = if (playing) TimeSource.Monotonic.markNow() else null
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

        // How long a tagged song may stay out of the live queue before its tag is dropped (queueing lags tagging).
        const val TAG_GRACE_MS = 10_000L
        const val SAMPLE_MS = 1_000L
        const val SEEK_TOLERANCE_MS = 3_000L
        const val STALE_EOF_MS = 2_000L
        const val CHECKPOINT_MS = 10_000L
        // Upstream caps crossfades at 15 s; a switch this close to the end is a transition, not a skip.
        const val CROSSFADE_WINDOW_MS = 16_000L
    }
}

/** The taste engine's key for queue occurrence [occurrence] ([app.turtleplayer.taste.TasteEngine.beginOccurrence]). */
internal fun stationKey(occurrence: Long): String = "q:$occurrence"
