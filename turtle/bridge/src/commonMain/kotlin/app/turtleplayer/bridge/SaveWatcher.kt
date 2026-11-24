package app.turtleplayer.bridge

import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import app.turtleplayer.taste.SavedKind
import app.turtleplayer.taste.SavedTrack
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** When outcome measurement started on this install (epoch ms), written once: see [SaveWatcher]. Bump the suffix when event labelling changes. */
const val PREF_MEASURE_SINCE = "measure_since_v1"

/**
 * Records the listener's saves with their time, for the discovery save-or-replay measurement only
 * ([TrackFeedbackKind.SAVED]; never handed to the taste engine, which reads saves from the library).
 *
 * Likes come from [likes] (upstream's in-app like events: a single toggle, bulk likes, the media
 * session, the queue sheet), recorded as [TrackFeedback.SAVED_LIKED] when they happen. Library adds
 * come from diffing [saved] snapshots ([SavedKind.LIBRARY] rows only) through the same settle and
 * burst rules as playlist adds ([PlaylistAdds]): a bulk remote library sync is a burst and dropped;
 * recorded as [TrackFeedback.SAVED_LIBRARY] once settled. Nothing is recorded while [recording] is
 * false (listening history paused), like plays: a library add first seen while paused is never
 * recorded, even when it settles after history resumes.
 *
 * Likes made before [start], or while the app is closed, are not seen; what was seen of the library
 * lives in memory, so an add made in the minute before the app is killed is lost.
 */
@OptIn(ExperimentalTime::class)
class SaveWatcher(
    private val store: SqlitePlayEventStore,
    private val saved: StateFlow<List<SavedTrack>?>,
    // Read when started, never at construction: upstream's like state is a lazily built object, and a failure there must not stop the runtime.
    private val likes: () -> Flow<SongItem>,
    private val recording: () -> Boolean,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val pollMs: Long = POLL_MS,
) {
    private val adds = PlaylistAdds()
    private val lock = Mutex()

    // The latest library snapshot's tracks by key, to record a settled add with its metadata.
    private var library: Map<String, TrackMeta> = emptyMap()
    private var baselined = false

    // Library rows that appeared while history was paused: never recorded, even when they settle after it resumes.
    private val pausedAdds = HashSet<String>()

    fun start(scope: CoroutineScope): Job =
        scope.launch {
            guarded("mark the measurement start") { markMeasurementStart() }
            launch {
                // Upstream's like state can fail to build (an Error, not an Exception): that must not stop library adds.
                val flow =
                    try {
                        likes()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        TurtleLog.error("saves: could not follow in-app likes", e)
                        null
                    }
                guarded("follow in-app likes") { flow?.collect { song -> guarded("record a like") { onLike(song) } } }
            }
            launch { saved.collect { snapshot -> if (snapshot != null) guarded("read the library") { observeLibrary(snapshot) } } }
            launch {
                while (isActive) {
                    delay(pollMs)
                    guarded("record library adds") { recordDue() }
                }
            }
        }

    /** Writes [PREF_MEASURE_SINCE] the first time only. */
    internal suspend fun markMeasurementStart() {
        if (store.pref(PREF_MEASURE_SINCE) == null) store.setPref(PREF_MEASURE_SINCE, nowMs().toString())
    }

    internal suspend fun onLike(song: SongItem) {
        if (recording()) record(song.toTrackMeta(), TrackFeedback.SAVED_LIKED)
    }

    /** One [saved] snapshot: the first is the baseline; later new library rows wait to settle. */
    internal suspend fun observeLibrary(snapshot: List<SavedTrack>) {
        val rows = snapshot.filter { it.kind == SavedKind.LIBRARY }.associate { it.meta.key.value to it.meta }
        val paused = !recording()
        lock.withLock {
            if (paused && baselined) rows.keys.filterTo(pausedAdds) { it !in library }
            baselined = true
            library = rows
            adds.observe(rows.keys.mapTo(HashSet()) { Membership(LIBRARY_ID, it) }, nowMs())
        }
    }

    /** Records the library adds that have settled, each once. */
    internal suspend fun recordDue() {
        val due =
            lock.withLock {
                // pausedAdds.remove is true for an add first seen while paused: dropped, and forgotten.
                adds.due(nowMs()).mapNotNull { add -> if (pausedAdds.remove(add.songId)) null else library[add.songId] }
            }
        if (due.isEmpty() || !recording()) return
        for (track in due) record(track, TrackFeedback.SAVED_LIBRARY)
        TurtleLog.info("saves: recorded ${due.size} library adds")
    }

    private suspend fun record(
        track: TrackMeta,
        detail: String,
    ) {
        store.appendFeedback(TrackFeedback(track = track, kind = TrackFeedbackKind.SAVED, atMs = nowMs(), detail = detail))
    }

    private inline fun guarded(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("saves: could not $what", e)
        }
    }

    private companion object {
        /** The library as one pseudo-playlist for [PlaylistAdds] (not "LP_" and not a history id, so it counts). */
        const val LIBRARY_ID = "turtle:saved"
        const val POLL_MS = 15_000L
    }
}
