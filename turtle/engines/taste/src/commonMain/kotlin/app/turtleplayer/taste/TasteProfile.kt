package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.PlayEvent
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * How much each signal moves affinity. Affinity is a sum of signals, each decayed with a
 * [halfLifeDays] half-life, so a song loved last year counts half as much as one loved today.
 */
data class AffinityWeights(
    val finished: Double = 1.0,
    /** Heard at least 30 s but neither finished nor skipped (stopped, error). */
    val partialListen: Double = 0.3,
    val liked: Double = 2.0,
    val replayed: Double = 1.5,
    val addedToPlaylist: Double = 1.5,
    /** Skipped after the first 30 s: mild disinterest. */
    val lateSkip: Double = -0.3,
    /** Skipped within 30 s: the strongest negative implicit signal. */
    val quickSkip: Double = -1.0,
    val lessLikeThis: Double = -4.0,
    val halfLifeDays: Double = 90.0,
    /** Half-life of [TrackStats.recentFinishes], which ranks "On repeat". */
    val recentHalfLifeDays: Double = 14.0,
) {
    fun signal(e: PlayEvent): Double {
        var s = when {
            e.isQuickSkip -> quickSkip
            e.endReason.isSkip -> lateSkip
            e.isFinished -> finished
            e.isListen -> partialListen
            else -> 0.0
        }
        if (e.liked) s += liked
        if (e.replayed) s += replayed
        if (e.addedToPlaylist) s += addedToPlaylist
        if (e.lessLikeThis) s += lessLikeThis
        return s
    }

    /** Multiplier for a signal [ageMs] old. */
    fun decay(ageMs: Long): Double = decay(ageMs, halfLifeDays)

    /** Multiplier for a finished play [ageMs] old in [TrackStats.recentFinishes]. */
    fun recentDecay(ageMs: Long): Double = decay(ageMs, recentHalfLifeDays)

    private fun decay(ageMs: Long, halfLife: Double) = exp(-ln(2.0) * ageMs.coerceAtLeast(0) / (halfLife * DAY_MS))
}

/** Plays and quick skips, with a smoothed rate so three plays do not make a 100% skip rate. */
data class SkipStats(val plays: Int = 0, val quickSkips: Int = 0) {
    fun rate(prior: Double = 0.2, priorWeight: Double = 4.0): Double = (quickSkips + prior * priorWeight) / (plays + priorWeight)

    operator fun plus(o: SkipStats) = SkipStats(plays + o.plays, quickSkips + o.quickSkips)
}

data class TrackStats(
    val meta: TrackMeta,
    /** Decayed affinity at the profile's `asOfMs`; positive = liked, negative = disliked. */
    val affinity: Double,
    val plays: Int,
    val finishes: Int,
    val quickSkips: Int,
    val likes: Int,
    val replays: Int,
    val lessLikeThis: Boolean,
    val firstPlayedMs: Long,
    val lastPlayedMs: Long,
    /**
     * Finished plays decayed with [AffinityWeights.recentHalfLifeDays] (14 days by default), i.e.
     * "finished plays lately": one finish today is 1.0, one finish 28 days ago is 0.25.
     */
    val recentFinishes: Double = 0.0,
) {
    val key: TrackKey get() = meta.key
    val skips: SkipStats get() = SkipStats(plays, quickSkips)

    /** "Loved": a clear positive history, the bar for Rediscover and familiar picks. */
    val isLoved: Boolean get() = !lessLikeThis && affinity > 0 && (likes > 0 || replays > 0 || finishes >= 3)
}

data class ArtistStats(
    val artist: ArtistKey,
    val affinity: Double,
    val plays: Int,
    val finishes: Int,
    val quickSkips: Int,
    val lastPlayedMs: Long,
) {
    val skips: SkipStats get() = SkipStats(plays, quickSkips)
}

/** One recent play, kept for fatigue features and album caps. */
data class RecentPlay(
    val track: TrackKey,
    val artist: ArtistKey,
    val album: String?,
    val startedAtMs: Long,
    val quickSkip: Boolean,
)

/** One long-term taste cluster. [id] is the rank by weight (0 = biggest) and may change between rebuilds. */
class TasteCluster(
    val id: Int,
    val centroid: FloatArray,
    /** Share of positive affinity in this cluster, 0..1. */
    val weight: Double,
    /** Member tracks, highest affinity first. */
    val members: List<TrackKey>,
    val topArtists: List<ArtistKey>,
    val skips: SkipStats,
)

/** Immutable snapshot of a listener's taste. Built by [TasteProfileBuilder]. */
class TasteProfile(
    val asOfMs: Long,
    val tracks: Map<TrackKey, TrackStats>,
    val artists: Map<ArtistKey, ArtistStats>,
    val sources: Map<CandidateSource, SkipStats>,
    /** Plays in the 24 h before [asOfMs], oldest first. */
    val recentPlays: List<RecentPlay>,
    val clusters: List<TasteCluster>,
    /** Affinity-weighted mean direction of liked tracks. */
    val longTermVector: FloatArray?,
    /** Weighted mean direction of disliked tracks (quick skips, less like this). */
    val dislikeVector: FloatArray?,
    /** Undecayed count of finished plays; drives cold-start blending. */
    val finishedPlays: Int,
) {
    fun track(key: TrackKey): TrackStats? = tracks[key]

    fun artist(key: ArtistKey): ArtistStats? = artists[key]

    fun hasHeard(key: TrackKey): Boolean = (tracks[key]?.plays ?: 0) > 0

    fun hasHeardArtist(key: ArtistKey): Boolean = (artists[key]?.plays ?: 0) > 0

    /** Nearest cluster and its similarity, or null with no clusters. */
    fun nearestCluster(vector: FloatArray): Pair<TasteCluster, Float>? =
        clusters.maxByOrNull { it.centroid.cosine(vector) }?.let { it to it.centroid.cosine(vector) }

    /** Artists by affinity, best first, positive only. */
    fun topArtists(limit: Int): List<ArtistStats> =
        artists.values.filter { it.affinity > 0 }.sortedWith(compareByDescending<ArtistStats> { it.affinity }.thenBy { it.artist.value }).take(limit)

    fun copy(clusters: List<TasteCluster> = this.clusters, longTermVector: FloatArray? = this.longTermVector): TasteProfile =
        TasteProfile(asOfMs, tracks, artists, sources, recentPlays, clusters, longTermVector, dislikeVector, finishedPlays)

    companion object {
        fun empty(nowMs: Long = 0) = TasteProfile(nowMs, emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyList(), null, null, 0)
    }
}

data class ClusteringConfig(
    /** Fixed seed: the same history always yields the same clusters. */
    val seed: Int = 0x7A57E,
    val maxPoints: Int = 2000,
    val iterations: Int = 25,
)

/**
 * Accumulates play events into decayed affinities. Events may arrive in any order (history imports
 * land after live plays): each accumulator stores its value at a reference time and decays toward
 * whichever time is later.
 */
class TasteProfileBuilder(val weights: AffinityWeights = AffinityWeights()) {
    /** [recent] decays with [AffinityWeights.recentHalfLifeDays] instead of [AffinityWeights.halfLifeDays]. */
    private class Decayed(private val recent: Boolean = false) {
        var value = 0.0
        var refMs = Long.MIN_VALUE

        private fun decay(ageMs: Long, w: AffinityWeights) = if (recent) w.recentDecay(ageMs) else w.decay(ageMs)

        fun add(s: Double, atMs: Long, w: AffinityWeights) {
            if (refMs == Long.MIN_VALUE) {
                value = s
                refMs = atMs
            } else if (atMs >= refMs) {
                value = value * decay(atMs - refMs, w) + s
                refMs = atMs
            } else {
                value += s * decay(refMs - atMs, w)
            }
        }

        fun at(nowMs: Long, w: AffinityWeights) = if (refMs == Long.MIN_VALUE) 0.0 else value * decay(nowMs - refMs, w)
    }

    private class TrackAcc(var meta: TrackMeta) {
        val affinity = Decayed()
        val recentFinishes = Decayed(recent = true)
        var plays = 0
        var finishes = 0
        var quickSkips = 0
        var likes = 0
        var replays = 0
        var lessLikeThis = false
        var first = Long.MAX_VALUE
        var last = Long.MIN_VALUE
    }

    private class ArtistAcc {
        val affinity = Decayed()
        var plays = 0
        var finishes = 0
        var quickSkips = 0
        var last = Long.MIN_VALUE
    }

    private val tracks = LinkedHashMap<TrackKey, TrackAcc>()
    private val artists = LinkedHashMap<ArtistKey, ArtistAcc>()
    private val sources = HashMap<CandidateSource, SkipStats>()
    private val recent = ArrayList<RecentPlay>()
    private var finished = 0
    private var latestMs = Long.MIN_VALUE

    val eventCount: Int get() = tracks.values.sumOf { it.plays }

    fun addAll(events: Iterable<PlayEvent>) = events.forEach(::add)

    fun add(e: PlayEvent) {
        val s = weights.signal(e)
        val t = tracks.getOrPut(e.trackKey) { TrackAcc(e.track) }
        if (e.startedAtMs >= t.last) t.meta = e.track // keep the freshest metadata
        t.affinity.add(s, e.startedAtMs, weights)
        t.plays++
        if (e.isFinished) {
            t.finishes++
            t.recentFinishes.add(1.0, e.startedAtMs, weights)
        }
        if (e.isQuickSkip) t.quickSkips++
        if (e.liked) t.likes++
        if (e.replayed) t.replays++
        if (e.lessLikeThis) t.lessLikeThis = true
        t.first = minOf(t.first, e.startedAtMs)
        t.last = maxOf(t.last, e.startedAtMs)

        val a = artists.getOrPut(e.artistKey) { ArtistAcc() }
        a.affinity.add(s, e.startedAtMs, weights)
        a.plays++
        if (e.isFinished) a.finishes++
        if (e.isQuickSkip) a.quickSkips++
        a.last = maxOf(a.last, e.startedAtMs)

        SurfaceTag.parse(e.surfaceDetail)?.let { (source, _) ->
            sources[source] = (sources[source] ?: SkipStats()) + SkipStats(1, if (e.isQuickSkip) 1 else 0)
        }
        if (e.isFinished) finished++

        latestMs = maxOf(latestMs, e.startedAtMs)
        recent += RecentPlay(e.trackKey, e.artistKey, e.track.albumKey, e.startedAtMs, e.isQuickSkip)
        if (recent.size > 2 * RECENT_CAP) pruneRecent()
    }

    private fun pruneRecent() {
        val cutoff = latestMs - 2 * DAY_MS
        recent.removeAll { it.startedAtMs < cutoff }
        if (recent.size > RECENT_CAP) {
            recent.sortBy { it.startedAtMs }
            val drop = recent.size - RECENT_CAP
            repeat(drop) { recent.removeAt(0) }
        }
    }

    /**
     * Cheap snapshot that reuses existing [clusters] (no k-means). Use after each live event; call
     * [build] periodically (daily, or after imports) to recompute clusters.
     */
    fun snapshot(nowMs: Long, representation: TrackRepresentation?, clusters: List<TasteCluster> = emptyList()): TasteProfile {
        val trackStats = LinkedHashMap<TrackKey, TrackStats>(tracks.size * 2)
        for ((k, t) in tracks) {
            trackStats[k] = TrackStats(
                meta = t.meta, affinity = t.affinity.at(nowMs, weights), plays = t.plays, finishes = t.finishes,
                quickSkips = t.quickSkips, likes = t.likes, replays = t.replays, lessLikeThis = t.lessLikeThis,
                firstPlayedMs = t.first, lastPlayedMs = t.last, recentFinishes = t.recentFinishes.at(nowMs, weights),
            )
        }
        val artistStats = LinkedHashMap<ArtistKey, ArtistStats>(artists.size * 2)
        for ((k, a) in artists) {
            artistStats[k] = ArtistStats(k, a.affinity.at(nowMs, weights), a.plays, a.finishes, a.quickSkips, a.last)
        }
        val recentPlays = recent.filter { it.startedAtMs in (nowMs - DAY_MS)..nowMs }.sortedBy { it.startedAtMs }

        var longTerm: FloatArray? = null
        var dislike: FloatArray? = null
        if (representation != null) {
            val positive = trackStats.values.filter { it.affinity > 0 }.sortedByDescending { it.affinity }.take(500)
            val posVecs = positive.mapNotNull { s -> representation.vectorFor(s.meta)?.let { it to s.affinity } }
            longTerm = weightedMeanDirection(posVecs.map { it.first }, posVecs.map { it.second }, representation.dim)
            val negative = trackStats.values.filter { it.affinity < 0 }.sortedBy { it.affinity }.take(500)
            val negVecs = negative.mapNotNull { s -> representation.vectorFor(s.meta)?.let { it to -s.affinity } }
            dislike = weightedMeanDirection(negVecs.map { it.first }, negVecs.map { it.second }, representation.dim)
        }
        return TasteProfile(nowMs, trackStats, artistStats, sources.toMap(), recentPlays, clusters, longTerm, dislike, finished)
    }

    /** Full rebuild: snapshot plus 1..8 taste clusters over affinity-weighted track vectors. */
    fun build(nowMs: Long, representation: TrackRepresentation, config: ClusteringConfig = ClusteringConfig()): TasteProfile {
        val base = snapshot(nowMs, representation)
        val clusters = computeClusters(base, representation, config)
        return TasteProfile(
            base.asOfMs, base.tracks, base.artists, base.sources, base.recentPlays, clusters,
            base.longTermVector, base.dislikeVector, base.finishedPlays,
        )
    }

    companion object {
        private const val RECENT_CAP = 2000

        fun computeClusters(profile: TasteProfile, representation: TrackRepresentation, config: ClusteringConfig = ClusteringConfig()): List<TasteCluster> {
            // Sorted by key so the result does not depend on event order.
            val positive = profile.tracks.values.filter { it.affinity > 0 && !it.lessLikeThis }
                .sortedWith(compareByDescending<TrackStats> { it.affinity }.thenBy { it.key.value })
                .take(config.maxPoints)
                .mapNotNull { s -> representation.vectorFor(s.meta)?.let { Triple(s, it, s.affinity) } }
                .sortedBy { it.first.key.value }
            val k = SphericalKMeans.chooseK(positive.size)
            if (k == 0) return emptyList()
            val result = SphericalKMeans.cluster(
                positive.map { it.second }, DoubleArray(positive.size) { positive[it].third }, k, Random(config.seed), config.iterations,
            )
            val totalWeight = positive.sumOf { it.third }
            // Skip stats per cluster use every played track with a vector, including disliked ones.
            val clusterSkips = Array(result.centroids.size) { SkipStats() }
            if (result.centroids.isNotEmpty()) {
                for (s in profile.tracks.values) {
                    val v = representation.vectorFor(s.meta) ?: continue
                    val c = SphericalKMeans.nearest(v, result.centroids)
                    clusterSkips[c] = clusterSkips[c] + s.skips
                }
            }
            val raw = result.centroids.indices.map { c ->
                val members = positive.indices.filter { result.assignments[it] == c }.map { positive[it] }
                    .sortedWith(compareByDescending<Triple<TrackStats, FloatArray, Double>> { it.third }.thenBy { it.first.key.value })
                val artistWeight = LinkedHashMap<ArtistKey, Double>()
                for (m in members) artistWeight[m.first.meta.artistKey] = (artistWeight[m.first.meta.artistKey] ?: 0.0) + m.third
                val topArtists = artistWeight.entries.sortedWith(compareByDescending<Map.Entry<ArtistKey, Double>> { it.value }.thenBy { it.key.value })
                    .take(5).map { it.key }
                Triple(c, members, topArtists)
            }
            return raw.sortedWith(compareByDescending<Triple<Int, List<Triple<TrackStats, FloatArray, Double>>, List<ArtistKey>>> { r -> r.second.sumOf { it.third } }
                .thenBy { it.third.firstOrNull()?.value.orEmpty() })
                .mapIndexed { id, (c, members, topArtists) ->
                    TasteCluster(
                        id = id,
                        centroid = result.centroids[c],
                        weight = if (totalWeight > 0) members.sumOf { it.third } / totalWeight else 0.0,
                        members = members.map { it.first.key },
                        topArtists = topArtists,
                        skips = clusterSkips[c],
                    )
                }
        }
    }
}
