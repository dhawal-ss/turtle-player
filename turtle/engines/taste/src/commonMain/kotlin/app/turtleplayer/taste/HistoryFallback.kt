package app.turtleplayer.taste

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.tanh

// Shelves from history plus upstream radio, for when there are no co-listening vectors yet.

/**
 * Upstream radio results for one shelf refresh. Upstream calls are network calls: they happen only
 * in [prefetch], for at most [maxSeeds] distinct seeds per instance, concurrently, each bounded by
 * [timeoutMs] and isolated from failures. [related] only reads what was prefetched, so generators
 * built on it never reach the network.
 */
class RadioCache(
    private val upstream: RecommendationSource?,
    private val maxSeeds: Int = 5,
    private val perSeed: Int = 50,
    private val timeoutMs: Long = 2_000,
) : RecommendationSource {
    private val results = LinkedHashMap<TrackKey, Pair<TrackMeta, List<TrackMeta>>>()

    /** Seeds fetched so far (including failed ones, which have empty radios), in fetch order. */
    val seeds: List<TrackMeta> get() = results.values.map { it.first }

    suspend fun prefetch(seeds: List<TrackMeta>) {
        val source = upstream ?: return
        val todo = seeds.distinctBy { it.key }.filter { it.key !in results }.take((maxSeeds - results.size).coerceAtLeast(0))
        if (todo.isEmpty()) return
        val fetched = coroutineScope {
            todo.map { seed ->
                async {
                    val radio = try {
                        withTimeoutOrNull(timeoutMs) { source.related(seed, perSeed) }.orEmpty()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList() // upstream offline or changed: the shelf falls back to history only
                    }
                    seed to radio.filter { it.key != seed.key }.distinctBy { it.key }
                }
            }.awaitAll()
        }
        for ((seed, radio) in fetched) results[seed.key] = seed to radio
    }

    fun cached(seed: TrackKey): List<TrackMeta> = results[seed]?.second.orEmpty()

    override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> = cached(seed.key).take(limit)
}

/**
 * The listener's own tracks around a seed (`request.nowPlaying`), for shelves without vectors:
 * their tracks by the seed's artist, that artist's catalog tracks they have not played, and their
 * favourites by artists that the seed's upstream radio leads to. Familiar tracks keep a replay or
 * like reason; everything else gets [reason].
 */
class LibraryGenerator(
    private val catalog: TrackCatalog,
    private val radios: RadioCache,
    private val reason: Reason,
    private val catalogTracks: Int = 12,
) : CandidateGenerator {
    override val source = CandidateSource.LIBRARY

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        val seed = request.nowPlaying ?: return emptyList()
        val artist = seed.artistKey
        val profile = request.profile
        val liked = profile.tracks.values.filter { it.affinity > 0 && !it.lessLikeThis && request.allowed(it.key) }
            .sortedWith(compareByDescending<TrackStats> { it.affinity }.thenBy { it.key.value })
        val out = ArrayList<Candidate>()
        for (s in liked) if (s.meta.artistKey == artist) out += Candidate(s.meta, source, reasonFor(s), tanh(s.affinity / 3).toFloat())
        catalog.tracksByArtist(artist, catalogTracks)
            .filter { request.allowed(it.key) && !profile.hasHeard(it.key) }
            .forEachIndexed { i, t -> out += Candidate(t, source, reason, 0.5f - 0.01f * i) }
        val related = radios.cached(seed.key).mapTo(HashSet()) { it.artistKey } - artist
        for (s in liked) if (s.meta.artistKey in related) out += Candidate(s.meta, source, reasonFor(s), 0.8f * tanh(s.affinity / 3).toFloat())
        return out.distinctBy { it.key }.take(limit)
    }

    private fun reasonFor(s: TrackStats): Reason = when {
        s.replays > 0 -> Reason.BecauseYouReplayed(s.key)
        s.likes > 0 -> Reason.BecauseYouLiked(s.key)
        else -> reason
    }
}

/** Every prefetched upstream radio in a [RadioCache], interleaved seed by seed. */
class CachedRadiosGenerator(private val radios: RadioCache) : CandidateGenerator {
    override val source = CandidateSource.UPSTREAM_RADIO

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        val lists = radios.seeds.map { seed ->
            val radio = radios.cached(seed.key).filter { request.allowed(it.key) }
            radio.mapIndexed { i, t -> Candidate(t, source, Reason.UpstreamRadio(seed.key), 1f - 0.5f * i / radio.size.coerceAtLeast(1)) }
        }
        val out = ArrayList<Candidate>()
        val longest = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until longest) for (l in lists) l.getOrNull(i)?.let(out::add)
        return out.distinctBy { it.key }.take(limit)
    }
}
