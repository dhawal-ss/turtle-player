package app.turtleplayer.taste

import kotlin.math.exp

/**
 * Scores a candidate with the probability that its play is completed, not skipped. Feature
 * extraction ([FeatureExtractor]) is separate so the Phase 3 LightGBM tree model can plug in by
 * implementing this interface over the same [Feature] schema.
 */
interface Ranker {
    val name: String

    /** P(completed, not skipped) in 0..1. */
    fun score(features: FeatureVector): Float
}

/**
 * v1 ranker: a logistic model with hand-set weights, documented below. Similarity to the session
 * and to long-term taste dominates; heard-before affinity rewards favourites; skip history,
 * fatigue and "less like this" push down; popularity is a small tie-breaker (personal over popular).
 *
 * Raw context features (hour, weekday, device) carry weight 0: they only help through interactions
 * with taste, which a linear model cannot express. The tree model will use them. CONTEXT_AFFINITY
 * is that interaction precomputed per artist, so it gets a modest weight here.
 */
class HandTunedRanker(
    private val weights: Map<Feature, Float> = DEFAULT_WEIGHTS,
    private val bias: Float = DEFAULT_BIAS,
    private val sourceBias: Map<CandidateSource, Float> = DEFAULT_SOURCE_BIAS,
) : Ranker {
    override val name = "hand-tuned-v1"

    private val dense = FloatArray(Feature.entries.size).also { w -> weights.forEach { (f, v) -> w[f.ordinal] = v } }

    fun logit(features: FeatureVector): Float {
        var z = bias
        val x = features.values
        for (i in x.indices) z += dense[i] * x[i]
        val source = CandidateSource.entries.getOrNull(features[Feature.SOURCE].toInt())
        if (source != null) z += sourceBias[source] ?: 0f
        return z
    }

    override fun score(features: FeatureVector): Float = (1.0 / (1.0 + exp(-logit(features).toDouble()))).toFloat()

    companion object {
        const val DEFAULT_BIAS = -0.5f

        val DEFAULT_WEIGHTS: Map<Feature, Float> = mapOf(
            // Taste match. Cosines are in -1..1, so these terms span about +-1.5 each.
            Feature.SESSION_SIM to 1.6f,
            Feature.LONG_TERM_SIM to 1.0f,
            Feature.CLUSTER_SIM to 1.4f,
            Feature.DISLIKE_SIM to -0.8f,
            // Personal history.
            Feature.TRACK_AFFINITY to 1.5f,
            Feature.ARTIST_AFFINITY to 0.8f,
            Feature.HEARD_BEFORE to 0.3f,
            Feature.ARTIST_HEARD_BEFORE to 0.2f,
            // A fresh release gets a small lift that fades over a month.
            Feature.FRESHNESS to 0.3f,
            // Fatigue: per play in the window. Hearing the same song twice a day is the strongest.
            Feature.TRACK_PLAYS_DAY to -0.8f,
            Feature.ARTIST_PLAYS_HOUR to -0.5f,
            Feature.ARTIST_PLAYS_DAY to -0.08f,
            Feature.ALBUM_PLAYS_HOUR to -0.6f,
            Feature.ALBUM_PLAYS_DAY to -0.1f,
            // Skip history (smoothed rates; the 0.2 prior costs about -0.9 in total).
            Feature.ARTIST_SKIP_RATE to -2.5f,
            Feature.CLUSTER_SKIP_RATE to -1.0f,
            Feature.SOURCE_SKIP_RATE to -1.0f,
            // Tie-breakers and explicit controls.
            Feature.POPULARITY to 0.3f,
            Feature.RETRIEVAL to 0.5f,
            Feature.USER_BOOST to 0.8f,
            Feature.LESS_LIKE_THIS_MARKS to -0.7f,
            // Radio: the listener asked for this seed, so closeness to it outweighs general taste.
            Feature.SEED_SIM to 3.5f,
            // When the listener plays this artist: reorders within taste (at most +-0.3), never above
            // a clear affinity gap, skip history or a "less like this" mark (-0.7).
            Feature.CONTEXT_AFFINITY to 0.3f,
            // Context, categorical and missing-value sentinels: tree model only.
            Feature.HOUR_BUCKET to 0f,
            Feature.WEEKDAY to 0f,
            Feature.DEVICE to 0f,
            Feature.SOURCE to 0f,
            Feature.SOURCE_COUNT to 0.1f,
            Feature.FRESHNESS_DAYS to 0f,
            Feature.DAYS_SINCE_PLAYED to 0f,
        )

        /** Upstream radio is a candidate source, not a ranker: a small handicap keeps our own picks first at equal match. */
        val DEFAULT_SOURCE_BIAS: Map<CandidateSource, Float> = mapOf(
            CandidateSource.UPSTREAM_RADIO to -0.2f,
            CandidateSource.REDISCOVERY to 0.1f,
        )
    }
}

/** A candidate with its vector, features and score. [slot] is FAMILIAR when the track was heard before. */
class ScoredCandidate(
    val candidate: Candidate,
    val vector: FloatArray?,
    val features: FeatureVector,
    val score: Float,
) {
    val slot: SlotKind get() = if (features[Feature.HEARD_BEFORE] > 0f) SlotKind.FAMILIAR else SlotKind.DISCOVERY
    val track get() = candidate.track
}
