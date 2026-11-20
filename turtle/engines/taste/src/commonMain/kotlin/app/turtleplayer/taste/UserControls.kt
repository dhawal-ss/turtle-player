package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey

/**
 * Bounds on the share of discovery slots. Thompson sampling picks the share per refresh; the dial
 * only clamps it, so the listener stays in control.
 */
data class DiscoveryDial(val minShare: Double, val maxShare: Double) {
    init {
        require(minShare in 0.0..1.0 && maxShare in 0.0..1.0 && minShare <= maxShare) { "Bad dial $minShare..$maxShare" }
    }

    fun clamp(share: Double): Double = share.coerceIn(minShare, maxShare)

    companion object {
        val FAMILIAR = DiscoveryDial(0.02, 0.20)
        val BALANCED = DiscoveryDial(0.10, 0.50)
        val ADVENTUROUS = DiscoveryDial(0.35, 0.80)

        /** Continuous dial position 0 (familiar) .. 0.5 (balanced) .. 1 (adventurous), linear between presets. */
        fun fromPosition(position: Double): DiscoveryDial {
            val p = position.coerceIn(0.0, 1.0)
            val (a, b, t) = if (p <= 0.5) Triple(FAMILIAR, BALANCED, p / 0.5) else Triple(BALANCED, ADVENTUROUS, (p - 0.5) / 0.5)
            if (t >= 1.0) return b
            return DiscoveryDial(a.minShare + (b.minShare - a.minShare) * t, a.maxShare + (b.maxShare - a.maxShare) * t)
        }
    }
}

/**
 * A taste cluster the listener muted or boosted. Cluster ids change when clusters are recomputed,
 * so we remember the centroid and match by similarity instead.
 */
data class ClusterAnchor(val centroid: List<Float>) {
    fun similarity(vector: FloatArray): Float =
        if (vector.size != centroid.size) 0f else centroid.toFloatArray().cosine(vector)

    companion object {
        fun of(vector: FloatArray) = ClusterAnchor(vector.normalized().toList())
    }
}

/** What the listener told us directly. Plain data so the app can persist it however it likes. */
data class UserControls(
    val discoveryDial: DiscoveryDial = DiscoveryDial.BALANCED,
    val mutedArtists: Set<ArtistKey> = emptySet(),
    val boostedArtists: Set<ArtistKey> = emptySet(),
    val mutedClusters: List<ClusterAnchor> = emptyList(),
    val boostedClusters: List<ClusterAnchor> = emptyList(),
    /** Tracks marked "less like this": never recommended again. */
    val lessLikeThisTracks: Set<TrackKey> = emptySet(),
    /** Artists marked "less like this" with how many times; each mark lowers their score further. */
    val lessLikeThisArtists: Map<ArtistKey, Int> = emptyMap(),
    /** [Reason.key]s of shelves or reasons the listener dismissed. */
    val suppressedReasons: Set<String> = emptySet(),
    /** Favourite artists from onboarding; carry recommendations until history exists. */
    val onboardingArtists: List<ArtistKey> = emptyList(),
) {
    /** Above this similarity to a muted/boosted anchor, a vector counts as inside that cluster. */
    fun isInMutedCluster(vector: FloatArray?, threshold: Float = CLUSTER_MATCH): Boolean =
        vector != null && mutedClusters.any { it.similarity(vector) >= threshold }

    fun isInBoostedCluster(vector: FloatArray?, threshold: Float = CLUSTER_MATCH): Boolean =
        vector != null && boostedClusters.any { it.similarity(vector) >= threshold }

    companion object {
        const val CLUSTER_MATCH = 0.75f
    }
}

/** Target of a "less like this" action. */
sealed interface LessLikeThis {
    data class Track(val track: TrackKey, val artist: ArtistKey) : LessLikeThis
    data class Artist(val artist: ArtistKey) : LessLikeThis
    /** Dismiss a whole shelf or reason, e.g. "Because you replayed X". */
    data class ForReason(val reason: Reason) : LessLikeThis
}

fun UserControls.apply(feedback: LessLikeThis): UserControls = when (feedback) {
    is LessLikeThis.Track -> copy(
        lessLikeThisTracks = lessLikeThisTracks + feedback.track,
        lessLikeThisArtists = lessLikeThisArtists + (feedback.artist to (lessLikeThisArtists[feedback.artist] ?: 0) + 1),
    )
    is LessLikeThis.Artist -> copy(
        lessLikeThisArtists = lessLikeThisArtists + (feedback.artist to (lessLikeThisArtists[feedback.artist] ?: 0) + 2),
    )
    is LessLikeThis.ForReason -> copy(suppressedReasons = suppressedReasons + feedback.reason.key)
}
