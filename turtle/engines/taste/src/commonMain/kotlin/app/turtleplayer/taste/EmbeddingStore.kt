package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey

/** What a vector (or a pack of vectors) describes. Codes are part of the pack format; never renumber. */
enum class EmbeddingKind(val code: Int) {
    ARTIST(1),
    TRACK(2),
    ;

    companion object {
        fun fromCode(code: Int): EmbeddingKind? = entries.firstOrNull { it.code == code }
    }
}

data class Neighbor<K>(val key: K, val similarity: Float)

/**
 * Co-listening vectors for artists (whole catalog) and tracks (most-played tracks), plus nearest
 * neighbour search over them. All stored vectors are unit length, so similarity is cosine.
 *
 * Artist ids are [ArtistKey.value]; track ids are [TrackKey.value].
 */
interface EmbeddingStore {
    val dim: Int
    val trackCount: Int
    val artistCount: Int

    fun trackVector(key: TrackKey): FloatArray?

    fun artistVector(key: ArtistKey): FloatArray?

    /** Up to [k] tracks most similar to [query], best first. */
    fun nearestTracks(query: FloatArray, k: Int, exclude: (TrackKey) -> Boolean = { false }): List<Neighbor<TrackKey>>

    /** Up to [k] artists most similar to [query], best first. */
    fun nearestArtists(query: FloatArray, k: Int, exclude: (ArtistKey) -> Boolean = { false }): List<Neighbor<ArtistKey>>
}

/**
 * Brute-force in-memory store. Exact search costs `count * dim` multiply-adds per query, about a
 * millisecond per 100k vectors on a phone, which is fine for v1 pack sizes; an ANN index can
 * replace it behind [EmbeddingStore] later.
 *
 * Not safe for concurrent mutation; load packs before handing the store to the engine, or swap the
 * whole store.
 */
class InMemoryEmbeddingStore(override val dim: Int = EMBEDDING_DIM) : EmbeddingStore {
    private val tracks = VectorTable(dim)
    private val artists = VectorTable(dim)

    override val trackCount: Int get() = tracks.size
    override val artistCount: Int get() = artists.size

    fun putTrack(key: TrackKey, vector: FloatArray) = tracks.put(key.value, vector)

    fun putArtist(key: ArtistKey, vector: FloatArray) = artists.put(key.value, vector)

    /** Adds every vector of a parsed pack; existing ids are overwritten. */
    fun load(pack: EmbeddingPack) {
        require(pack.dim == dim) { "Pack dim ${pack.dim} does not match store dim $dim" }
        val table = if (pack.kind == EmbeddingKind.TRACK) tracks else artists
        table.ensureCapacity(table.size + pack.size)
        for (i in 0 until pack.size) table.put(pack.ids[i], pack.vector(i))
    }

    override fun trackVector(key: TrackKey): FloatArray? = tracks.get(key.value)

    override fun artistVector(key: ArtistKey): FloatArray? = artists.get(key.value)

    override fun nearestTracks(query: FloatArray, k: Int, exclude: (TrackKey) -> Boolean): List<Neighbor<TrackKey>> =
        tracks.nearest(query, k) { exclude(TrackKey(it)) }.map { Neighbor(TrackKey(it.key), it.similarity) }

    override fun nearestArtists(query: FloatArray, k: Int, exclude: (ArtistKey) -> Boolean): List<Neighbor<ArtistKey>> =
        artists.nearest(query, k) { exclude(ArtistKey(it)) }.map { Neighbor(ArtistKey(it.key), it.similarity) }
}

/** Flat, growable table of unit vectors keyed by string id. */
private class VectorTable(private val dim: Int) {
    private val ids = ArrayList<String>()
    private val index = HashMap<String, Int>()
    private var data = FloatArray(dim * 16)

    val size: Int get() = ids.size

    fun ensureCapacity(n: Int) {
        if (n * dim > data.size) data = data.copyOf(maxOf(n * dim, data.size * 2))
    }

    fun put(id: String, vector: FloatArray) {
        require(vector.size == dim) { "Vector for '$id' has dim ${vector.size}, expected $dim" }
        require(vector.isFiniteVector()) { "Vector for '$id' is not finite" }
        val unit = vector.normalized()
        val row = index[id] ?: run {
            ensureCapacity(ids.size + 1)
            ids += id
            index[id] = ids.size - 1
            ids.size - 1
        }
        unit.copyInto(data, row * dim)
    }

    fun get(id: String): FloatArray? = index[id]?.let { data.copyOfRange(it * dim, it * dim + dim) }

    fun nearest(query: FloatArray, k: Int, exclude: (String) -> Boolean): List<Neighbor<String>> {
        require(query.size == dim) { "Query dim ${query.size}, expected $dim" }
        if (k <= 0 || ids.isEmpty()) return emptyList()
        val q = query.normalized()
        if (q.isZero()) return emptyList()
        val top = TopK(k)
        for (row in ids.indices) {
            var s = 0f
            val base = row * dim
            for (j in 0 until dim) s += q[j] * data[base + j]
            // Exclusion is checked only for rows that would enter the heap: it may be expensive.
            if (top.wouldAccept(s, row) && !exclude(ids[row])) top.offer(s, row)
        }
        return top.drainDescending().map { (score, row) -> Neighbor(ids[row], score) }
    }
}

/**
 * Bounded min-heap keeping the [k] best (score, id) pairs. Ties break toward the lower id so
 * results are deterministic regardless of float noise in the input order.
 */
internal class TopK(private val k: Int) {
    private val scores = FloatArray(k)
    private val ids = IntArray(k)
    var size = 0
        private set

    /** True when (score, id) ranks below (s2, id2). */
    private fun worse(s1: Float, id1: Int, s2: Float, id2: Int) = s1 < s2 || (s1 == s2 && id1 > id2)

    fun wouldAccept(score: Float, id: Int): Boolean = size < k || worse(scores[0], ids[0], score, id)

    fun offer(score: Float, id: Int) {
        if (size < k) {
            scores[size] = score
            ids[size] = id
            siftUp(size++)
        } else if (worse(scores[0], ids[0], score, id)) {
            scores[0] = score
            ids[0] = id
            siftDown(0)
        }
    }

    private fun siftUp(start: Int) {
        var i = start
        while (i > 0) {
            val p = (i - 1) / 2
            if (!worse(scores[i], ids[i], scores[p], ids[p])) break
            swap(i, p)
            i = p
        }
    }

    private fun siftDown(start: Int) {
        var i = start
        while (true) {
            val l = 2 * i + 1
            val r = l + 1
            var m = i
            if (l < size && worse(scores[l], ids[l], scores[m], ids[m])) m = l
            if (r < size && worse(scores[r], ids[r], scores[m], ids[m])) m = r
            if (m == i) return
            swap(i, m)
            i = m
        }
    }

    private fun swap(a: Int, b: Int) {
        val s = scores[a]; scores[a] = scores[b]; scores[b] = s
        val d = ids[a]; ids[a] = ids[b]; ids[b] = d
    }

    /** Best first. Empties the heap. */
    fun drainDescending(): List<Pair<Float, Int>> {
        val out = ArrayList<Pair<Float, Int>>(size)
        while (size > 0) {
            out += scores[0] to ids[0]
            size--
            if (size > 0) {
                scores[0] = scores[size]
                ids[0] = ids[size]
                siftDown(0)
            }
        }
        out.reverse()
        return out
    }
}
