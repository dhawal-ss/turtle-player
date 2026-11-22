package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.PlayEvent
import kotlin.concurrent.Volatile
import kotlin.math.sqrt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Which artists go together, learned on device from what the app already sees: every radio page
 * (YouTube's "listeners of this also play" list for a seed song) and the songs the listener finishes
 * back to back. This is the co-listening signal Spotify builds from everyone's playlists, gathered
 * from YouTube's own recommendations instead, so it works without downloaded packs.
 *
 * It also remembers a few songs seen per artist, so neighbour artists have something to play.
 * Bounded: [maxArtists] artists (least connected dropped first), [maxNeighbours] links and
 * [maxTracksPerArtist] songs each. Turn it into vectors with [CoListeningStore].
 */
class CoListeningGraph(
    private val maxArtists: Int = 2_000,
    private val maxNeighbours: Int = 40,
    private val maxTracksPerArtist: Int = 6,
) {
    data class Edge(val a: ArtistKey, val b: ArtistKey, val weight: Float)

    /** Everything the graph holds, for the app to persist and [load] at the next start. */
    data class Data(val edges: List<Edge>, val tracks: List<TrackMeta>)

    private val mutex = Mutex()
    private val links = HashMap<ArtistKey, HashMap<ArtistKey, Float>>()
    private val tracks = HashMap<ArtistKey, ArrayDeque<TrackMeta>>()
    private val trackIndex = HashMap<TrackKey, TrackMeta>()
    private val lastBySession = HashMap<String, PlayEvent>()
    private var changes = 0L

    /** Increases on every change; compare to decide when to rebuild vectors or save. */
    suspend fun version(): Long = mutex.withLock { changes }

    /**
     * A radio page for [seed]: its artist goes with each page artist, closer near the top. Page
     * songs are remembered as playable songs of their artists.
     */
    suspend fun observeRadio(seed: TrackMeta, page: List<TrackMeta>) = mutex.withLock {
        val from = seed.artistKey
        page.forEachIndexed { rank, t ->
            remember(t)
            if (t.artistKey != from) link(from, t.artistKey, RADIO_WEIGHT / (1f + RADIO_RANK_DECAY * rank))
        }
        remember(seed)
        changes++
        trim()
    }

    /** A recorded play: two songs finished one after the other in a session link their artists. */
    suspend fun observePlay(event: PlayEvent) = mutex.withLock {
        val good = !event.endReason.isSkip && !event.lessLikeThis && (event.isFinished || event.isListen)
        val previous = lastBySession[event.sessionId]
        if (good) {
            if (previous != null && previous.artistKey != event.artistKey) {
                link(previous.artistKey, event.artistKey, SESSION_WEIGHT)
                changes++
            }
            lastBySession.clear() // only the current session matters
            lastBySession[event.sessionId] = event
        }
        if (good) trim()
    }

    suspend fun export(): Data = mutex.withLock {
        val edges = ArrayList<Edge>()
        for ((a, ns) in links) for ((b, w) in ns) if (a.value < b.value) edges += Edge(a, b, w)
        Data(edges, tracks.values.flatten())
    }

    /** Replaces the graph with saved [data]. */
    suspend fun load(data: Data) = mutex.withLock {
        links.clear()
        tracks.clear()
        trackIndex.clear()
        for (e in data.edges) if (e.a != e.b && e.weight > 0f) link(e.a, e.b, e.weight)
        data.tracks.forEach(::remember)
        changes++
        trim()
    }

    /** A remembered song, by key. */
    suspend fun track(key: TrackKey): TrackMeta? = mutex.withLock { trackIndex[key] }

    internal suspend fun <T> read(block: (Map<ArtistKey, Map<ArtistKey, Float>>, Map<ArtistKey, List<TrackMeta>>) -> T): T =
        mutex.withLock { block(links, tracks) }

    private fun link(a: ArtistKey, b: ArtistKey, w: Float) {
        links.getOrPut(a) { HashMap() }.let { it[b] = (it[b] ?: 0f) + w }
        links.getOrPut(b) { HashMap() }.let { it[a] = (it[a] ?: 0f) + w }
    }

    private fun remember(t: TrackMeta) {
        if (t.youtubeId == null) return // only songs the app can play
        val list = tracks.getOrPut(t.artistKey) { ArrayDeque() }
        list.removeAll { it.key == t.key }
        list.addLast(t)
        trackIndex[t.key] = t
        while (list.size > maxTracksPerArtist) trackIndex.remove(list.removeFirst().key)
    }

    private fun trim() {
        for ((a, ns) in links) {
            if (ns.size <= maxNeighbours) continue
            val keep = ns.entries.sortedByDescending { it.value }.take(maxNeighbours).mapTo(HashSet()) { it.key }
            val drop = ns.keys - keep
            for (b in drop) {
                ns.remove(b)
                links[b]?.remove(a)
            }
        }
        if (links.size > maxArtists) {
            val weakest = links.entries.sortedBy { e -> e.value.values.sum() }.take(links.size - maxArtists).map { it.key }
            for (a in weakest) {
                links.remove(a)?.keys?.forEach { b -> links[b]?.remove(a) }
            }
        }
        links.entries.removeAll { it.value.isEmpty() }
        // Songs of artists with no links left are dropped, except the newest radio seeds' (linked soon).
        val orphaned = tracks.keys.filter { it !in links }
        if (tracks.size > maxArtists) {
            for (a in orphaned) tracks.remove(a)?.forEach { trackIndex.remove(it.key) }
        }
    }

    private companion object {
        const val RADIO_WEIGHT = 1f
        const val RADIO_RANK_DECAY = 0.08f
        const val SESSION_WEIGHT = 0.5f
    }
}

/**
 * [EmbeddingStore] over a [CoListeningGraph], so the engine's vector features (taste clusters,
 * session similarity, "listeners also play", radio seeds) work without downloaded packs.
 *
 * Artist vectors use random indexing: each artist has a fixed sparse random signature, and its
 * vector is its own signature plus its neighbours', weighted by link strength normalised by both
 * artists' totals. Linked artists, and artists sharing neighbours, end up close. A remembered song's
 * vector is its artist's plus a little of its own signature, so an artist's songs are near-equal.
 *
 * Reads use an immutable snapshot taken by [rebuild]; call it after the graph changed (it is cheap:
 * a few milliseconds for a full graph), then tell the engine via [TasteEngine.embeddingsChanged].
 */
class CoListeningStore(private val graph: CoListeningGraph, override val dim: Int = EMBEDDING_DIM) : EmbeddingStore {
    private class Snapshot(
        val artists: Map<ArtistKey, FloatArray>,
        val tracks: Map<TrackKey, FloatArray>,
    ) {
        val artistList = artists.entries.toList()
        val trackList = tracks.entries.toList()
    }

    @Volatile
    private var snapshot = Snapshot(emptyMap(), emptyMap())

    override val trackCount: Int get() = snapshot.tracks.size
    override val artistCount: Int get() = snapshot.artists.size

    /** Recomputes vectors from the graph. */
    suspend fun rebuild() {
        snapshot = graph.read { links, tracks ->
            val totals = links.mapValues { (_, ns) -> ns.values.sum() }
            val artists = HashMap<ArtistKey, FloatArray>(links.size * 2)
            for ((a, ns) in links) {
                val ta = totals.getValue(a)
                val v = FloatArray(dim)
                var strongest = 0f
                for ((b, w) in ns) {
                    val norm = w / sqrt(ta * (totals[b] ?: w))
                    strongest = maxOf(strongest, norm)
                    addSignature(v, b.value, norm)
                }
                addSignature(v, a.value, maxOf(strongest, MIN_SELF))
                artists[a] = v.normalized()
            }
            // A couple of rounds of smoothing toward the neighbours' vectors: artists settle into their
            // community, so a lightly linked artist is not defined by one noisy radio page.
            repeat(SMOOTHING_ROUNDS) {
                val before = HashMap(artists)
                for ((a, ns) in links) {
                    val own = before[a] ?: continue
                    val ta = totals.getValue(a)
                    val mixed = own.copyOf()
                    var mass = 0f
                    for ((b, w) in ns) mass += w / sqrt(ta * (totals[b] ?: w))
                    if (mass <= 0f) continue
                    for ((b, w) in ns) {
                        val vb = before[b] ?: continue
                        val share = SMOOTHING * (w / sqrt(ta * (totals[b] ?: w))) / mass
                        for (i in mixed.indices) mixed[i] += share * vb[i]
                    }
                    artists[a] = mixed.normalized()
                }
            }
            val trackVectors = HashMap<TrackKey, FloatArray>()
            for ((a, ts) in tracks) {
                val av = artists[a] ?: continue
                for (t in ts) {
                    val v = av.copyOf()
                    addSignature(v, t.key.value, TRACK_SELF)
                    trackVectors[t.key] = v.normalized()
                }
            }
            Snapshot(artists, trackVectors)
        }
    }

    override fun trackVector(key: TrackKey): FloatArray? = snapshot.tracks[key]

    override fun artistVector(key: ArtistKey): FloatArray? = snapshot.artists[key]

    override fun nearestTracks(query: FloatArray, k: Int, exclude: (TrackKey) -> Boolean): List<Neighbor<TrackKey>> =
        nearest(snapshot.trackList, query, k, exclude)

    override fun nearestArtists(query: FloatArray, k: Int, exclude: (ArtistKey) -> Boolean): List<Neighbor<ArtistKey>> =
        nearest(snapshot.artistList, query, k, exclude)

    private fun <K> nearest(items: List<Map.Entry<K, FloatArray>>, query: FloatArray, k: Int, exclude: (K) -> Boolean): List<Neighbor<K>> {
        if (k <= 0 || query.size != dim) return emptyList()
        val q = query.normalized()
        val best = ArrayList<Neighbor<K>>(k + 1)
        for ((key, v) in items) {
            val s = q.cosine(v)
            if (best.size == k && s <= best.last().similarity) continue
            if (exclude(key)) continue
            val at = best.indexOfFirst { it.similarity < s }.let { if (it < 0) best.size else it }
            best.add(at, Neighbor(key, s))
            if (best.size > k) best.removeAt(best.lastIndex)
        }
        return best
    }

    /** Adds [weight] times the fixed sparse random signature of [id] (FNV-1a seeded, platform independent). */
    private fun addSignature(v: FloatArray, id: String, weight: Float) {
        var h = 0x811C9DC5.toInt()
        for (ch in id) {
            h = (h xor ch.code) * 0x01000193
        }
        repeat(SIGNATURE_NONZEROS) {
            h = h xor (h shl 13)
            h = h xor (h ushr 17)
            h = h xor (h shl 5)
            val index = (h ushr 1) % dim
            v[index] += if (h and 1 == 0) weight else -weight
        }
    }

    private companion object {
        const val SIGNATURE_NONZEROS = 8
        const val MIN_SELF = 0.5f
        const val TRACK_SELF = 0.25f
        const val SMOOTHING_ROUNDS = 2
        const val SMOOTHING = 1f
    }
}

/**
 * The app's catalog plus the songs the co-listening graph has seen, so neighbour artists that are
 * not in the library still have songs to recommend. Library songs come first.
 */
class CatalogWithObserved(private val base: TrackCatalog, private val graph: CoListeningGraph) : TrackCatalog {
    override suspend fun meta(key: TrackKey): TrackMeta? =
        base.meta(key) ?: graph.track(key)

    override suspend fun tracksByArtist(artist: ArtistKey, limit: Int): List<TrackMeta> {
        val own = base.tracksByArtist(artist, limit)
        if (own.size >= limit) return own
        val seen = graph.read { _, tracks -> tracks[artist].orEmpty().asReversed().toList() }
        val keys = own.mapTo(HashSet()) { it.key }
        return own + seen.filter { it.key !in keys }.take(limit - own.size)
    }
}
