package app.turtleplayer.search

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.PlayEventStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.exp

/** A track from the online catalog. */
data class OnlineTrack(
    val meta: TrackMeta,
    /** The upload is the official audio or video (a "Topic" or label channel, or marked official). */
    val official: Boolean = false,
    /** Global popularity in [0, 1], when known. Falls back to [TrackMeta.popularity]. */
    val popularity: Float? = null,
)

data class OnlineArtist(
    val name: String,
    val key: ArtistKey = ArtistKey.of(name),
    /** Provider id for opening the artist page (YouTube Music browse id). */
    val browseId: String? = null,
    val popularity: Float? = null,
)

data class OnlineAlbum(
    val title: String,
    val artist: String,
    val browseId: String? = null,
    val year: Int? = null,
    val popularity: Float? = null,
)

/** Online results in provider rank order (index 0 is the provider's best). */
data class OnlineResults(
    val tracks: List<OnlineTrack> = emptyList(),
    val artists: List<OnlineArtist> = emptyList(),
    val albums: List<OnlineAlbum> = emptyList(),
)

/** Online catalog search (YouTube Music through upstream). Implemented by `:turtle-bridge`. */
interface OnlineSearchProvider {
    /** False for the no-op default, so the engine skips the stage entirely. */
    val enabled: Boolean get() = true

    suspend fun search(query: ParsedQuery, limit: Int): OnlineResults

    /** Query completions for [prefix] (raw text as typed). */
    suspend fun suggestions(prefix: String): List<String>

    companion object {
        val None: OnlineSearchProvider = object : OnlineSearchProvider {
            override val enabled get() = false
            override suspend fun search(query: ParsedQuery, limit: Int) = OnlineResults()
            override suspend fun suggestions(prefix: String) = emptyList<String>()
        }
    }
}

/** How much the user likes an artist or track, in [0, 1]. The taste engine implements this. */
interface AffinityProvider {
    suspend fun artistAffinity(artist: ArtistKey): Float

    suspend fun trackAffinity(track: TrackKey): Float

    companion object {
        val None: AffinityProvider = object : AffinityProvider {
            override suspend fun artistAffinity(artist: ArtistKey) = 0f
            override suspend fun trackAffinity(track: TrackKey) = 0f
        }
    }
}

data class SemanticHit(val track: TrackMeta, /** Cosine similarity mapped to [0, 1]. */ val similarity: Float)

/** Query text -> tracks via the shared text embedding (Phase 3). */
interface SemanticRetriever {
    val enabled: Boolean get() = true

    suspend fun retrieve(query: ParsedQuery, limit: Int): List<SemanticHit>

    companion object {
        val None: SemanticRetriever = object : SemanticRetriever {
            override val enabled get() = false
            override suspend fun retrieve(query: ParsedQuery, limit: Int) = emptyList<SemanticHit>()
        }
    }
}

/**
 * Stand-in affinity from raw play counts until the taste engine provides a real profile:
 * `1 - exp(-listens / scale)` over a recent window, so a handful of listens already counts and
 * the value saturates for favorites. Quick skips do not count.
 */
class HistoryAffinityProvider(
    private val events: PlayEventStore,
    private val clock: Clock = Clock.System,
    private val windowMs: Long = 180 * DAY_MS,
    private val refreshMs: Long = 10 * 60_000L,
    private val artistScale: Float = 8f,
    private val trackScale: Float = 3f,
) : AffinityProvider {
    private val mutex = Mutex()
    private var builtAt = Long.MIN_VALUE
    private var artists: Map<ArtistKey, Int> = emptyMap()
    private var tracks: Map<TrackKey, Int> = emptyMap()

    override suspend fun artistAffinity(artist: ArtistKey): Float = saturate(snapshot().first[artist] ?: 0, artistScale)

    override suspend fun trackAffinity(track: TrackKey): Float = saturate(snapshot().second[track] ?: 0, trackScale)

    /** Forces a rebuild on the next lookup. */
    suspend fun invalidate() = mutex.withLock { builtAt = Long.MIN_VALUE }

    private fun saturate(count: Int, scale: Float) = if (count <= 0) 0f else 1f - exp(-count / scale)

    private suspend fun snapshot(): Pair<Map<ArtistKey, Int>, Map<TrackKey, Int>> = mutex.withLock {
        val now = clock.nowMs()
        if (builtAt == Long.MIN_VALUE || now - builtAt > refreshMs) {
            val a = HashMap<ArtistKey, Int>()
            val t = HashMap<TrackKey, Int>()
            for (e in events.since(now - windowMs)) {
                if (e.isQuickSkip || !e.isListen) continue
                a[e.artistKey] = (a[e.artistKey] ?: 0) + 1
                t[e.trackKey] = (t[e.trackKey] ?: 0) + 1
            }
            artists = a
            tracks = t
            builtAt = now
        }
        artists to tracks
    }
}
