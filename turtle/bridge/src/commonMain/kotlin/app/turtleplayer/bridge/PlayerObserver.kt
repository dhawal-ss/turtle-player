package app.turtleplayer.bridge

import app.turtleplayer.events.EndReason
import app.turtleplayer.events.ListeningSessionTracker
import app.turtleplayer.events.PlaybackSignal
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Feeds the listening-event log from upstream's player flows. Android (`AndroidPlaybackPlayer`) and
 * desktop (`MusicPlayer`) expose the same three flows, so one hook line per platform attaches it.
 *
 * End reasons: a natural end of track (EOF) is COMPLETED and the next track is AUTOPLAY. A track
 * change without EOF lets the tracker infer skip vs completion from how much was heard.
 */
class PlayerObserver(private val tracker: ListeningSessionTracker) {
    private val mutex = Mutex()
    private var currentId: String? = null
    private var endedByEof = false
    private var repeatCheck: Job? = null

    fun attach(
        scope: CoroutineScope,
        currentSong: StateFlow<SongItem?>,
        isPlaying: StateFlow<Boolean>,
        onEndOfTrack: Flow<*>,
    ) {
        scope.launch {
            currentSong.map { it }.distinctUntilChanged { a, b -> a?.id == b?.id }.collect { song ->
                mutex.withLock { onSongChanged(song) }
            }
        }
        scope.launch {
            isPlaying.collect { playing ->
                mutex.withLock { if (currentId != null) tracker.onSignal(PlaybackSignal.PlayingChanged(playing)) }
            }
        }
        scope.launch {
            onEndOfTrack.collect {
                mutex.withLock {
                    if (currentId != null && !endedByEof) {
                        endedByEof = true
                        tracker.onSignal(PlaybackSignal.TrackEnded(EndReason.COMPLETED))
                    }
                }
                // Repeat-one replays the same song, so no song change follows the EOF.
                repeatCheck?.cancel()
                repeatCheck = scope.launch {
                    delay(REPEAT_DETECT_MS)
                    mutex.withLock {
                        val song = currentSong.value
                        if (endedByEof && song != null && song.id == currentId && isPlaying.value) {
                            start(song, StartReason.AUTOPLAY)
                        }
                    }
                }
            }
        }
    }

    private suspend fun onSongChanged(song: SongItem?) {
        repeatCheck?.cancel()
        if (song == null) {
            if (currentId != null && !endedByEof) tracker.onSignal(PlaybackSignal.TrackEnded(EndReason.STOPPED))
            currentId = null
            endedByEof = false
            return
        }
        start(song, if (endedByEof) StartReason.AUTOPLAY else StartReason.UNKNOWN)
    }

    private suspend fun start(song: SongItem, reason: StartReason) {
        currentId = song.id
        endedByEof = false
        tracker.onSignal(
            PlaybackSignal.TrackStarted(
                track = song.toTrackMeta(),
                reason = reason,
                // Upstream does not tell us where a queue came from yet; the queue hook will.
                surface = if (reason == StartReason.AUTOPLAY) Surface.AUTOPLAY else Surface.QUEUE,
                surfaceDetail = song.queueSourceTitle,
            ),
        )
    }

    private companion object {
        const val REPEAT_DETECT_MS = 1_500L
    }
}
