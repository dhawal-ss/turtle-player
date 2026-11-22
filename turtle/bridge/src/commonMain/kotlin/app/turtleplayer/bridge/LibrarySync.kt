package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.LocalSource
import app.turtleplayer.taste.SavedKind
import app.turtleplayer.taste.SavedTrack
import app.turtleplayer.taste.TrackCatalog
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
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
    private var indexed: Map<TrackKey, Pair<TrackMeta, Set<LocalSource>>> = emptyMap()
    private var synced = false
    private val savedFlow = MutableStateFlow<List<SavedTrack>?>(null)

    /**
     * The listener's liked and library songs (YouTube Music likes sync into the same table), for the
     * taste engine. Null until the first sync.
     */
    val saved: StateFlow<List<SavedTrack>?> = savedFlow.asStateFlow()

    /** True once the first full sync finished, so searches never see a half-built index. */
    suspend fun isReady(): Boolean = mutex.withLock { synced }

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope): Job = scope.launch {
        database().dao().allSongs().debounce(DEBOUNCE_MS).collect { rows ->
            val songs = rows.map { it.toSongItem() to sourcesOf(it.song.inLibrary != null, it.song.totalPlayTime) }
            val metas = songs.map { (song, sources) -> Triple(song, song.toTrackMeta(), sources) }
            cache.replaceLibrary(songs.map { it.first })
            val current = metas.associate { (_, meta, sources) -> meta.key to (meta to sources) }
            val previous = mutex.withLock { indexed }
            for ((key, state) in current) {
                if (previous[key] == state) continue
                // Upsert replaces the sources, so unsaving a song drops its library boost.
                library.upsert(state.first, state.second)
            }
            for (gone in previous.keys - current.keys) library.remove(gone)
            savedFlow.value = rows.indices.mapNotNull { i ->
                val song = rows[i].song
                val kind = when {
                    song.liked -> SavedKind.LIKED
                    song.inLibrary != null -> SavedKind.LIBRARY
                    else -> return@mapNotNull null
                }
                val at = (if (song.liked) song.likedDate else null) ?: song.inLibrary ?: song.dateModified ?: song.date
                SavedTrack(metas[i].second, kind, at?.epochMs() ?: 0L)
            }.sortedBy { it.meta.key.value }
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

    /** Saved songs are LIBRARY, played ones HISTORY (both when saved and played); rows that are neither stay HISTORY. */
    private fun sourcesOf(inLibrary: Boolean, playTimeMs: Long): Set<LocalSource> = buildSet {
        if (inLibrary) add(LocalSource.LIBRARY)
        if (playTimeMs > 0 || !inLibrary) add(LocalSource.HISTORY)
    }

    private fun LocalDateTime.epochMs(): Long = toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

    private companion object {
        const val DEBOUNCE_MS = 2_000L
    }
}
