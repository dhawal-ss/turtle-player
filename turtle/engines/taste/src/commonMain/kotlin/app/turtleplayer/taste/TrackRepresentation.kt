package app.turtleplayer.taste

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

/** One part of a track's representation, with how much we trust it (0..1). */
class PartVector(val vector: FloatArray, val confidence: Float = 1f)

/** Co-listening (collaborative filtering) vector, trained on servers from ListenBrainz dumps. */
fun interface CoListeningVectors {
    fun vectorFor(track: TrackMeta): PartVector?
}

/** Sound vector computed on device from audio the user played (open audio model, tempo, key). */
fun interface SoundVectors {
    fun vectorFor(track: TrackKey): PartVector?
}

/** Text vector from title, artist and our derived tags (small multilingual text model). */
fun interface TextVectors {
    fun vectorFor(track: TrackMeta): PartVector?
}

/**
 * Co-listening from an [EmbeddingStore]: the track-level vector when the track is in the top-tracks
 * pack, else the artist-level vector (the artist pack covers the whole catalog) at reduced
 * confidence, since all of an artist's songs then look alike.
 */
class StoreCoListeningVectors(
    private val store: EmbeddingStore,
    private val artistFallbackConfidence: Float = 0.6f,
) : CoListeningVectors {
    override fun vectorFor(track: TrackMeta): PartVector? {
        store.trackVector(track.key)?.let { return PartVector(it) }
        return store.artistVector(track.artistKey)?.let { PartVector(it, artistFallbackConfidence) }
    }
}

data class RepresentationWeights(val coListening: Float = 0.6f, val sound: Float = 0.25f, val text: Float = 0.15f)

/**
 * Combines the three parts into one unit-length 64-number vector. Each part must already live in
 * the shared 64-d space (the server trains the projections so the spaces are aligned). Missing
 * parts drop out and the remaining weights are renormalized, so a track with only a sound vector
 * still gets a usable representation.
 */
class TrackRepresentation(
    private val coListening: CoListeningVectors,
    private val sound: SoundVectors? = null,
    private val text: TextVectors? = null,
    private val weights: RepresentationWeights = RepresentationWeights(),
    val dim: Int = EMBEDDING_DIM,
    private val cacheSize: Int = 8192,
) {
    constructor(store: EmbeddingStore) : this(StoreCoListeningVectors(store), dim = store.dim)

    // Insertion-ordered; evicts the oldest entry (FIFO) once full. Misses are cached too.
    private val cache = LinkedHashMap<TrackKey, FloatArray?>()

    fun vectorFor(track: TrackMeta): FloatArray? {
        if (cache.containsKey(track.key)) return cache[track.key]
        val v = combine(track)
        if (cache.size >= cacheSize) cache.remove(cache.keys.first())
        cache[track.key] = v
        return v
    }

    /** Forget a cached vector, e.g. after the on-device sound model analysed the track. */
    fun invalidate(key: TrackKey) {
        cache.remove(key)
    }

    fun clearCache() = cache.clear()

    private fun combine(track: TrackMeta): FloatArray? {
        val parts = listOfNotNull(
            coListening.vectorFor(track)?.let { it to weights.coListening },
            sound?.vectorFor(track.key)?.let { it to weights.sound },
            text?.vectorFor(track)?.let { it to weights.text },
        ).filter { (p, w) -> p.vector.size == dim && p.vector.isFiniteVector() && !p.vector.isZero() && w * p.confidence > 0f }
        if (parts.isEmpty()) return null
        val out = FloatArray(dim)
        for ((p, w) in parts) out.addScaled(p.vector.normalized(), w * p.confidence)
        return if (out.isZero()) null else out.normalizeInPlace()
    }
}
