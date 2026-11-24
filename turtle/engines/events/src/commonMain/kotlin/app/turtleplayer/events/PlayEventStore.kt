package app.turtleplayer.events

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Persistent log of [PlayEvent]s. The app implements this on its own database in `:turtle-bridge`;
 * engines and tests use [InMemoryPlayEventStore].
 */
interface PlayEventStore {
    /** Live stream of newly recorded events (not replayed history). */
    val newEvents: Flow<PlayEvent>

    suspend fun append(event: PlayEvent): Long

    suspend fun appendAll(events: List<PlayEvent>)

    /** Events with `startedAtMs >= sinceMs`, oldest first. */
    suspend fun since(sinceMs: Long, limit: Int = Int.MAX_VALUE): List<PlayEvent>

    suspend fun forTrack(key: TrackKey, limit: Int = 200): List<PlayEvent>

    suspend fun forArtist(artist: ArtistKey, limit: Int = 500): List<PlayEvent>

    /**
     * Replaces every stored play from [source] with [events], all or nothing: on any failure the
     * previous plays of [source] stay exactly as they were. Plays from other sources are untouched.
     * An empty list removes the source. Every event must come from [source].
     */
    suspend fun replaceSource(source: EventSource, events: List<PlayEvent>)

    /** How many plays from [source] are stored and the span they cover, or null without any. */
    suspend fun sourceRange(source: EventSource): SourceRange?

    /** Deletes everything from one import source (an all-or-nothing [replaceSource] with nothing). */
    suspend fun deleteSource(source: EventSource) = replaceSource(source, emptyList())

    suspend fun count(): Long

    /** Records a non-play feedback event. Not emitted on [newEvents]. Returns its id. */
    suspend fun appendFeedback(feedback: TrackFeedback): Long

    /** Feedback with `atMs >= sinceMs`, oldest first. */
    suspend fun feedbackSince(sinceMs: Long, limit: Int = Int.MAX_VALUE): List<TrackFeedback>
}

/** The stored plays of one source: how many, and when the first and the last started. */
data class SourceRange(val count: Long, val firstMs: Long, val lastMs: Long)

class InMemoryPlayEventStore : PlayEventStore {
    private val mutex = Mutex()
    private val events = ArrayList<PlayEvent>()
    private var nextId = 1L
    private val feedback = ArrayList<TrackFeedback>()
    private var nextFeedbackId = 1L
    private val stream = MutableSharedFlow<PlayEvent>(extraBufferCapacity = 64)

    override val newEvents: SharedFlow<PlayEvent> = stream.asSharedFlow()

    override suspend fun append(event: PlayEvent): Long {
        val stored = mutex.withLock {
            val e = event.copy(id = nextId++)
            insertSorted(e)
            e
        }
        stream.emit(stored)
        return stored.id
    }

    override suspend fun appendAll(events: List<PlayEvent>) {
        mutex.withLock {
            for (e in events) insertSorted(e.copy(id = nextId++))
        }
    }

    private fun insertSorted(e: PlayEvent) {
        if (events.isEmpty() || events.last().startedAtMs <= e.startedAtMs) {
            events += e
        } else {
            val idx = events.binarySearch { it.startedAtMs.compareTo(e.startedAtMs) }.let { if (it < 0) -it - 1 else it + 1 }
            events.add(idx, e)
        }
    }

    override suspend fun since(sinceMs: Long, limit: Int): List<PlayEvent> = mutex.withLock {
        events.asSequence().filter { it.startedAtMs >= sinceMs }.take(limit).toList()
    }

    override suspend fun forTrack(key: TrackKey, limit: Int): List<PlayEvent> = mutex.withLock {
        events.asReversed().asSequence().filter { it.trackKey == key }.take(limit).toList()
    }

    override suspend fun forArtist(artist: ArtistKey, limit: Int): List<PlayEvent> = mutex.withLock {
        events.asReversed().asSequence().filter { it.artistKey == artist }.take(limit).toList()
    }

    override suspend fun replaceSource(source: EventSource, events: List<PlayEvent>) {
        require(events.all { it.source == source }) { "every replacing event must come from $source" }
        mutex.withLock {
            // Built aside first, swapped in only once complete: a throw leaves the old list.
            var id = nextId
            val next = ArrayList<PlayEvent>(this.events.size + events.size)
            this.events.filterTo(next) { it.source != source }
            events.mapTo(next) { it.copy(id = id++) }
            next.sortBy { it.startedAtMs }
            this.events.clear()
            this.events.addAll(next)
            nextId = id
        }
    }

    override suspend fun sourceRange(source: EventSource): SourceRange? = mutex.withLock {
        val own = events.filter { it.source == source }
        if (own.isEmpty()) null else SourceRange(own.size.toLong(), own.first().startedAtMs, own.last().startedAtMs)
    }

    override suspend fun count(): Long = mutex.withLock { events.size.toLong() }

    override suspend fun appendFeedback(feedback: TrackFeedback): Long = mutex.withLock {
        val f = feedback.copy(id = nextFeedbackId++)
        val idx = this.feedback.indexOfFirst { it.atMs > f.atMs }
        if (idx < 0) this.feedback += f else this.feedback.add(idx, f)
        f.id
    }

    override suspend fun feedbackSince(sinceMs: Long, limit: Int): List<TrackFeedback> = mutex.withLock {
        feedback.asSequence().filter { it.atMs >= sinceMs }.take(limit).toList()
    }
}
