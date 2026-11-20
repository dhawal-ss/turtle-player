package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Everything a generator may look at. Vectors are computed by the caller so generators stay free
 * of shared mutable state and can run concurrently.
 */
class GenerationRequest(
    val profile: TasteProfile,
    val controls: UserControls,
    val nowMs: Long,
    /** Live session vector (or long-term taste when the session is empty). */
    val sessionVector: FloatArray?,
    val nowPlaying: TrackMeta? = null,
    val nowPlayingVector: FloatArray? = null,
    /** Track most responsible for the session direction, for "because you listened to X". */
    val anchorTrack: TrackKey? = null,
    val seed: RadioSeed? = null,
    val seedVector: FloatArray? = null,
    /** Tracks no generator may return (now playing, already queued, quick-skipped this session). */
    val exclude: Set<TrackKey> = emptySet(),
) {
    fun allowed(key: TrackKey): Boolean = key !in exclude && key !in controls.lessLikeThisTracks
}

/** One source of candidates. Implementations must be safe to run concurrently with each other. */
interface CandidateGenerator {
    val source: CandidateSource

    suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate>
}

private suspend fun neighborsToCandidates(
    store: EmbeddingStore,
    catalog: TrackCatalog,
    query: FloatArray,
    k: Int,
    request: GenerationRequest,
    source: CandidateSource,
    reason: (TrackMeta) -> Reason,
): List<Candidate> {
    val neighbors = store.nearestTracks(query, k) { !request.allowed(it) }
    val metas = catalog.metas(neighbors.map { it.key })
    return neighbors.mapNotNull { n -> metas[n.key]?.let { Candidate(it, source, reason(it), n.similarity) } }
}

/** Reason for something that matches long-term taste in general. */
private fun tasteReason(profile: TasteProfile, vector: FloatArray?): Reason? {
    val c = vector?.let { profile.nearestCluster(it)?.first } ?: return null
    return Reason.FromYourTaste(c.id, c.topArtists)
}

/** Nearest tracks to the live session vector and to the track playing now. */
class SessionNeighborsGenerator(private val store: EmbeddingStore, private val catalog: TrackCatalog) : CandidateGenerator {
    override val source = CandidateSource.SESSION

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        val out = ArrayList<Candidate>()
        val seedReason = request.seed?.let { Reason.RadioFrom(it) }
        val anchor = request.anchorTrack ?: request.nowPlaying?.key
        val sessionReason: (TrackMeta) -> Reason = { meta ->
            seedReason ?: anchor?.let { Reason.BecauseYouListened(it) }
                ?: tasteReason(request.profile, request.sessionVector) ?: Reason.SimilarTo(meta.artistKey)
        }
        val nowPlayingVector = request.nowPlayingVector
        val sessionShare = if (nowPlayingVector != null) 0.6 else 1.0
        request.sessionVector?.let { v ->
            out += neighborsToCandidates(store, catalog, v, (limit * sessionShare * 1.3).toInt() + 1, request, source, sessionReason)
        }
        if (nowPlayingVector != null) {
            val np = request.nowPlaying!!.key
            out += neighborsToCandidates(store, catalog, nowPlayingVector, (limit * 0.4 * 1.3).toInt() + 1, request, source) {
                seedReason ?: Reason.BecauseYouListened(np)
            }
        }
        return out.distinctBy { it.key }.take(limit)
    }
}

/** Nearest tracks to each long-term taste cluster, plus the cluster's own favourites. */
class TasteClusterGenerator(private val store: EmbeddingStore, private val catalog: TrackCatalog) : CandidateGenerator {
    override val source = CandidateSource.TASTE_CLUSTER

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        val clusters = request.profile.clusters.filterNot { request.controls.isInMutedCluster(it.centroid) }
        if (clusters.isEmpty()) return emptyList()
        // Square-root allocation so a small cluster still gets a fair share of candidates.
        val roots = clusters.map { sqrt(it.weight.coerceAtLeast(0.01)) }
        val total = roots.sum()
        val out = ArrayList<Candidate>()
        for ((i, c) in clusters.withIndex()) {
            val quota = maxOf(8, (limit * roots[i] / total).toInt())
            val reason = Reason.FromYourTaste(c.id, c.topArtists)
            val favourites = c.members.asSequence()
                .filter(request::allowed)
                .mapNotNull { request.profile.track(it) }
                .filter { it.isLoved }
                .take(quota * 3 / 10)
                .map { s ->
                    val r = when {
                        s.replays > 0 -> Reason.BecauseYouReplayed(s.key)
                        s.likes > 0 -> Reason.BecauseYouLiked(s.key)
                        else -> reason
                    }
                    Candidate(s.meta, source, r, tanh(s.affinity / 3).toFloat())
                }.toList()
            out += favourites
            out += neighborsToCandidates(store, catalog, c.centroid, quota - favourites.size, request, source) { reason }
        }
        return out.distinctBy { it.key }.take(limit)
    }
}

/** Heard tracks with positive affinity inside the request's clusters: the familiar side of a mix. */
class FavoritesGenerator : CandidateGenerator {
    override val source = CandidateSource.TASTE_CLUSTER

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> =
        request.profile.clusters.asSequence()
            .filterNot { request.controls.isInMutedCluster(it.centroid) }
            .flatMap { c -> c.members.asSequence().map { it to c } }
            .filter { request.allowed(it.first) }
            .mapNotNull { (key, c) -> request.profile.track(key)?.let { it to c } }
            .filter { (s, _) -> s.affinity > 0 && !s.lessLikeThis }
            .take(limit)
            .map { (s, c) ->
                val reason = when {
                    s.replays > 0 -> Reason.BecauseYouReplayed(s.key)
                    s.likes > 0 -> Reason.BecauseYouLiked(s.key)
                    else -> Reason.FromYourTaste(c.id, c.topArtists)
                }
                Candidate(s.meta, source, reason, tanh(s.affinity / 3).toFloat())
            }.toList()
}

/**
 * Collaborative filtering at artist level: artists whose listeners overlap (co-listening vectors
 * from the ListenBrainz pack) with the listener's favourites, then those artists' top tracks.
 */
class CollaborativeFilteringGenerator(
    private val store: EmbeddingStore,
    private val catalog: TrackCatalog,
    private val seedArtists: Int = 8,
    private val neighborsPerSeed: Int = 6,
    private val tracksPerArtist: Int = 4,
) : CandidateGenerator {
    override val source = CandidateSource.COLLABORATIVE

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        val controls = request.controls
        val seeds: List<Pair<ArtistKey, Float>> = when (val seed = request.seed) {
            is RadioSeed.Artist -> listOf(seed.artist to 1f)
            is RadioSeed.Track -> listOf(seed.track.artistKey to 1f)
            null -> {
                val top = request.profile.topArtists(seedArtists).filter { it.artist !in controls.mutedArtists }
                val maxAff = top.maxOfOrNull { it.affinity } ?: 1.0
                val fromHistory = top.map { it.artist to (0.5 + 0.5 * it.affinity / maxAff).toFloat() }
                val onboarding = controls.onboardingArtists.filter { a -> fromHistory.none { it.first == a } }.map { it to 0.8f }
                (fromHistory + onboarding).take(seedArtists + controls.onboardingArtists.size)
            }
        }
        return fromArtists(seeds, request, limit)
    }

    /** Neighbour-artist tracks for explicit seed artists with weights in 0..1. */
    suspend fun fromArtists(seeds: List<Pair<ArtistKey, Float>>, request: GenerationRequest, limit: Int): List<Candidate> {
        val controls = request.controls
        if (seeds.isEmpty()) return emptyList()
        val perSeed = maxOf(4, limit / seeds.size)
        val out = ArrayList<Candidate>()
        for ((seedArtist, seedWeight) in seeds) {
            val v = store.artistVector(seedArtist) ?: continue
            val neighbors = store.nearestArtists(v, neighborsPerSeed) { it == seedArtist || it in controls.mutedArtists }
            var taken = 0
            for (n in neighbors) {
                if (taken >= perSeed) break
                for (t in catalog.tracksByArtist(n.key, tracksPerArtist)) {
                    if (!request.allowed(t.key) || taken >= perSeed) continue
                    out += Candidate(t, source, Reason.ListenersAlsoPlay(seedArtist), n.similarity * seedWeight)
                    taken++
                }
            }
        }
        return out.sortedByDescending { it.retrieval }.distinctBy { it.key }.take(limit)
    }
}

/** New releases from artists the listener has affinity for (Release Radar style). */
class FreshReleaseGenerator(
    private val releases: FreshReleaseSource,
    private val windowDays: Int = 28,
    private val artists: Int = 40,
) : CandidateGenerator {
    override val source = CandidateSource.FRESH_RELEASE

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        if (request.seed != null) return emptyList()
        val controls = request.controls
        val affinity = request.profile.topArtists(artists).associate { it.artist to it.affinity }
        val wanted = (affinity.keys + controls.boostedArtists + controls.onboardingArtists).distinct()
            .filter { it !in controls.mutedArtists }
        if (wanted.isEmpty()) return emptyList()
        val since = request.nowMs - windowDays * DAY_MS
        val maxAff = affinity.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
        return releases.freshReleases(wanted, since, limit * 2)
            .filter { request.allowed(it.key) && it.artistKey in wanted && (it.releasedAtMs ?: request.nowMs) >= since }
            .map { Candidate(it, source, Reason.FreshFrom(it.artistKey), (0.3 + 0.7 * (affinity[it.artistKey] ?: 0.0) / maxAff).toFloat()) }
            .distinctBy { it.key }
            .take(limit)
    }
}

/** Songs they loved but have not heard for [minDaysAway]+ days. */
class RediscoveryGenerator(private val minDaysAway: Int = 60) : CandidateGenerator {
    override val source = CandidateSource.REDISCOVERY

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        val cutoff = request.nowMs - minDaysAway * DAY_MS
        return request.profile.tracks.values.asSequence()
            .filter { it.isLoved && it.lastPlayedMs <= cutoff && request.allowed(it.key) }
            .filter { it.meta.artistKey !in request.controls.mutedArtists }
            .sortedWith(compareByDescending<TrackStats> { it.affinity }.thenBy { it.key.value })
            .take(limit)
            .map { Candidate(it.meta, source, Reason.Rediscover(it.key, it.lastPlayedMs), tanh(it.affinity / 3).toFloat()) }
            .toList()
    }
}

/** Upstream (YouTube) radio for the seed or the track playing now. */
class UpstreamRadioGenerator(private val upstream: RecommendationSource) : CandidateGenerator {
    override val source = CandidateSource.UPSTREAM_RADIO

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        val seed = (request.seed as? RadioSeed.Track)?.track ?: request.nowPlaying
            ?: request.anchorTrack?.let { request.profile.track(it)?.meta } ?: return emptyList()
        val related = upstream.related(seed, limit).filter { request.allowed(it.key) }
        return related.mapIndexed { i, t ->
            Candidate(t, source, Reason.UpstreamRadio(seed.key), 1f - 0.5f * i / related.size.coerceAtLeast(1))
        }
    }
}

/**
 * Cold start: onboarding artists and their co-listening neighbours carry recommendations until
 * there is history. Its share fades out as finished plays reach [warmAfterPlays].
 */
class OnboardingGenerator(
    private val store: EmbeddingStore,
    private val catalog: TrackCatalog,
    private val warmAfterPlays: Int = 50,
) : CandidateGenerator {
    override val source = CandidateSource.ONBOARDING

    override suspend fun generate(request: GenerationRequest, limit: Int): List<Candidate> {
        val artists = request.controls.onboardingArtists.filter { it !in request.controls.mutedArtists }
        val coldness = 1.0 - request.profile.finishedPlays.toDouble() / warmAfterPlays
        if (artists.isEmpty() || coldness <= 0 || request.seed != null) return emptyList()
        val budget = (limit * coldness).toInt().coerceAtLeast(1)
        val perArtist = maxOf(4, budget / artists.size)
        val out = ArrayList<Candidate>()
        for (a in artists) {
            val own = catalog.tracksByArtist(a, perArtist / 2).filter { request.allowed(it.key) }
            own.forEachIndexed { i, t -> out += Candidate(t, source, Reason.BecauseYouPicked(a), 1f - 0.02f * i) }
            val v = store.artistVector(a) ?: continue
            for (n in store.nearestArtists(v, 4) { it == a || it in request.controls.mutedArtists }) {
                for (t in catalog.tracksByArtist(n.key, maxOf(1, perArtist / 8))) {
                    if (request.allowed(t.key)) out += Candidate(t, source, Reason.SimilarTo(a), n.similarity)
                }
            }
        }
        return out.distinctBy { it.key }.take(budget)
    }
}

/** Per-generator share of the candidate budget. Shares are relative; they are scaled to the target. */
data class GeneratorBudget(val shares: Map<CandidateSource, Double> = DEFAULT) {
    fun limitFor(source: CandidateSource, target: Int, active: Collection<CandidateSource>): Int {
        val total = active.sumOf { shares[it] ?: 0.0 }
        if (total <= 0) return 0
        return ((shares[source] ?: 0.0) / total * target).toInt().coerceAtLeast(1)
    }

    companion object {
        val DEFAULT = mapOf(
            CandidateSource.SESSION to 120.0,
            CandidateSource.TASTE_CLUSTER to 150.0,
            CandidateSource.COLLABORATIVE to 100.0,
            CandidateSource.FRESH_RELEASE to 40.0,
            CandidateSource.REDISCOVERY to 30.0,
            CandidateSource.UPSTREAM_RADIO to 60.0,
            CandidateSource.ONBOARDING to 80.0,
            CandidateSource.LIBRARY to 100.0,
        )
    }
}

/**
 * Runs all generators concurrently (about [target] candidates in total), isolates failures and
 * slow seams, applies the listener's hard controls and merges duplicates. Output order is
 * deterministic: generator order, then each generator's own order.
 */
class CandidateCollector(
    private val generators: List<CandidateGenerator>,
    private val budget: GeneratorBudget = GeneratorBudget(),
    private val target: Int = 500,
    private val timeoutMs: Long = 2_000,
) {
    suspend fun collect(request: GenerationRequest): List<Candidate> {
        val active = generators.map { it.source }
        val results = coroutineScope {
            generators.map { g ->
                async {
                    try {
                        withTimeoutOrNull(timeoutMs) { g.generate(request, budget.limitFor(g.source, target, active)) }.orEmpty()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyList() // a failing source (e.g. upstream radio offline) must not empty the queue
                    }
                }
            }.awaitAll()
        }
        val merged = LinkedHashMap<TrackKey, Candidate>()
        val controls = request.controls
        for (list in results) for (c in list) {
            if (!request.allowed(c.key)) continue
            if (c.track.artistKey in controls.mutedArtists) continue
            if (c.reason.key in controls.suppressedReasons) continue
            val prev = merged[c.key]
            merged[c.key] = if (prev == null) {
                c
            } else {
                // Keep the first generator's reason (generator order is priority); remember every source.
                prev.copy(retrieval = maxOf(prev.retrieval, c.retrieval), sources = prev.sources + c.source)
            }
        }
        return merged.values.toList()
    }
}
