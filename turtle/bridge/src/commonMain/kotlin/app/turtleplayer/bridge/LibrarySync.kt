package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.LocalSource
import app.turtleplayer.taste.TrackCatalog
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Mirrors upstream's song table into our search index and the taste engine's catalog. Songs in the
 * library are indexed as LIBRARY, songs only played before as HISTORY. Re-runs whenever the table
 * changes (debounced), so edits, likes and downloads show up without app restarts.
 */
class LibrarySync(
    private val database: () -> MusicDatabase,
    private val library: LocalLibrary,
    private val cache: SongCache,
) : TrackCatalog {
    private val mutex = Mutex()
    private var byKey: Map<TrackKey, TrackMeta> = emptyMap()
    private var byArtist: Map<ArtistKey, List<TrackMeta>> = emptyMap()
    // What is in the search index now, so a table change (which happens on every play) only re-indexes the diff.
    private var indexed: Map<TrackKey, Pair<TrackMeta, Boolean>> = emptyMap()
    private var synced = false

    /** True once the first full sync finished, so searches never see a half-built index. */
    suspend fun isReady(): Boolean = mutex.withLock { synced }

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        database().dao().allSongs().debounce(DEBOUNCE_MS).collect { rows ->
            val songs = rows.map { it.toSongItem() to (it.song.inLibrary != null) }
            val metas = songs.map { (song, inLibrary) -> Triple(song, song.toTrackMeta(), inLibrary) }
            cache.replaceLibrary(songs.map { it.first })
            val current = metas.associate { (_, meta, inLibrary) -> meta.key to (meta to inLibrary) }
            val previous = mutex.withLock { indexed }
            for ((key, state) in current) {
                if (previous[key] == state) continue
                library.upsert(state.first, setOf(if (state.second) LocalSource.LIBRARY else LocalSource.HISTORY))
            }
            for (gone in previous.keys - current.keys) library.remove(gone)
            mutex.withLock {
                indexed = current
                synced = true
                byKey = metas.associate { it.second.key to it.second }
                byArtist = metas.map { it.second }.groupBy { it.artistKey }
            }
        }
    }

    override suspend fun meta(key: TrackKey): TrackMeta? = mutex.withLock { byKey[key] }
        ?: if (key.scheme == TrackKey.YOUTUBE) cache.get(key.id)?.toTrackMeta() else null

    override suspend fun tracksByArtist(artist: ArtistKey, limit: Int): List<TrackMeta> =
        mutex.withLock { byArtist[artist].orEmpty().take(limit) }

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
