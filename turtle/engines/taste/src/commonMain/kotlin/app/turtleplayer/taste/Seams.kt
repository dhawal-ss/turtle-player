package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

// Interfaces the app implements in :turtle-bridge. Everything else in the taste engine is pure logic.

/**
 * Track metadata the engine can recommend: the app's song database (library, history, cached
 * YouTube metadata) joined with pack metadata. Every candidate must resolve here to be playable.
 */
interface TrackCatalog {
    suspend fun meta(key: TrackKey): TrackMeta?

    suspend fun metas(keys: Collection<TrackKey>): Map<TrackKey, TrackMeta> {
        val out = LinkedHashMap<TrackKey, TrackMeta>()
        for (k in keys) meta(k)?.let { out[k] = it }
        return out
    }

    /** An artist's tracks, most popular first. */
    suspend fun tracksByArtist(artist: ArtistKey, limit: Int): List<TrackMeta>
}

/** New releases per artist (ListenBrainz fresh releases API, or YouTube new uploads). */
interface FreshReleaseSource {
    /** Releases by any of [artists] on or after [sinceMs], newest first. */
    suspend fun freshReleases(artists: List<ArtistKey>, sinceMs: Long, limit: Int): List<TrackMeta>
}

/**
 * An upstream recommendation feed: YouTube radio / related for a seed track. One source among
 * several; it only proposes candidates, our ranker decides.
 */
interface RecommendationSource {
    suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta>
}

/** Songs and artists the app's own block list rules out. */
data class BlockedSet(val tracks: Set<TrackKey> = emptySet(), val artists: Set<ArtistKey> = emptySet()) {
    companion object {
        val NONE = BlockedSet()
    }
}

/**
 * The app's block list, read live and never copied into [UserControls]: unblocking there takes
 * effect here. Return the same instance while unchanged; it is read often, inside the engine's lock.
 */
fun interface ExternalBlocks {
    fun current(): BlockedSet
}

/** [this] with [blocks] merged in (tracks into lessLikeThisTracks, artists into mutedArtists); [this] itself when [blocks] is empty. */
fun UserControls.withBlocks(blocks: BlockedSet): UserControls =
    if (blocks.tracks.isEmpty() && blocks.artists.isEmpty()) {
        this
    } else {
        copy(lessLikeThisTracks = lessLikeThisTracks + blocks.tracks, mutedArtists = mutedArtists + blocks.artists)
    }

/** Simple catalog for tests, tools and the offline evaluator. */
class InMemoryTrackCatalog(tracks: Collection<TrackMeta>) : TrackCatalog {
    private val byKey = LinkedHashMap<TrackKey, TrackMeta>()
    private val byArtist = HashMap<ArtistKey, MutableList<TrackMeta>>()

    init {
        tracks.forEach(::put)
    }

    val all: Collection<TrackMeta> get() = byKey.values

    fun put(track: TrackMeta) {
        byKey.put(track.key, track)?.let { old -> byArtist[old.artistKey]?.remove(old) }
        val list = byArtist.getOrPut(track.artistKey) { ArrayList() }
        list += track
        list.sortWith(compareByDescending<TrackMeta> { it.popularity ?: 0f }.thenBy { it.key.value })
    }

    override suspend fun meta(key: TrackKey): TrackMeta? = byKey[key]

    override suspend fun tracksByArtist(artist: ArtistKey, limit: Int): List<TrackMeta> =
        byArtist[artist]?.take(limit).orEmpty()
}
