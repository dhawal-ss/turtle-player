package app.turtleplayer.bridge

import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Upstream [SongItem]s we have seen, by YouTube id, so engine results (keys and [TrackMeta]s) can be
 * turned back into playable upstream items. Library songs are pinned; everything else is a
 * bounded least-recently-used cache.
 */
// Holds radio pages and the co-listening graph's songs (up to ~12k), so shelves built from them stay playable.
class SongCache(private val capacity: Int = 20_000) {
    private val mutex = Mutex()
    private val library = HashMap<String, SongItem>()
    private val recent = LinkedHashMap<String, SongItem>()

    suspend fun get(id: String): SongItem? = mutex.withLock { library[id] ?: recent[id] }

    suspend fun putAll(songs: Iterable<SongItem>) = mutex.withLock {
        for (song in songs) {
            if (song.id.isBlank() || song.id in library) continue
            recent.remove(song.id)
            recent[song.id] = song
        }
        while (recent.size > capacity) recent.remove(recent.keys.first())
    }

    suspend fun replaceLibrary(songs: Collection<SongItem>) = mutex.withLock {
        library.clear()
        for (song in songs) library[song.id] = song
    }
}
