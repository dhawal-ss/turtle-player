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

    /** Deletes everything from one import source, so a re-import replaces rather than duplicates. */
    suspend fun deleteSource(source: EventSource)

    suspend fun count(): Long
}

class InMemoryPlayEventStore : PlayEventStore {
    private val mutex = Mutex()
    private val events = ArrayList<PlayEvent>()
    private var nextId = 1L
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

    override suspend fun deleteSource(source: EventSource) {
        mutex.withLock { events.removeAll { it.source == source } }
    }

    override suspend fun count(): Long = mutex.withLock { events.size.toLong() }
}
