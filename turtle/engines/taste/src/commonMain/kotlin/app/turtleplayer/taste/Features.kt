package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.events.PlayContext
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Ranking features. The enum order is the feature schema shared with the server-trained tree
 * model (Phase 3): append only, never reorder or remove. Categorical features are encoded as
 * ordinals; missing numeric values use the documented sentinel.
 */
enum class Feature {
    /** Cosine to the live session vector, -1..1. */
    SESSION_SIM,
    /** Cosine to the long-term taste direction, -1..1. */
    LONG_TERM_SIM,
    /** Cosine to the nearest taste cluster, -1..1 (multi-modal taste: a mean of two genres sits between them). */
    CLUSTER_SIM,
    /** Cosine to the direction of disliked tracks, -1..1. */
    DISLIKE_SIM,
    /** tanh(track affinity / 3), -1..1; 0 when never heard. */
    TRACK_AFFINITY,
    /** tanh(artist affinity / 5), -1..1. */
    ARTIST_AFFINITY,
    /** 1 if the listener heard this track before. */
    HEARD_BEFORE,
    /** 1 if the listener heard this artist before. */
    ARTIST_HEARD_BEFORE,
    /** Days since release; -1 when unknown. */
    FRESHNESS_DAYS,
    /** exp(-days since release / 30); 0 when unknown. */
    FRESHNESS,
    /** Days since this track was last played; -1 when never. */
    DAYS_SINCE_PLAYED,
    /** Plays of this track in the last 24 h. */
    TRACK_PLAYS_DAY,
    ARTIST_PLAYS_HOUR,
    ARTIST_PLAYS_DAY,
    ALBUM_PLAYS_HOUR,
    ALBUM_PLAYS_DAY,
    /** Smoothed quick-skip rates, 0..1. */
    ARTIST_SKIP_RATE,
    CLUSTER_SKIP_RATE,
    SOURCE_SKIP_RATE,
    /** Context: [PlayContext.hourBucket] 0..3, ISO weekday 1..7, device ordinal; -1 when unknown. */
    HOUR_BUCKET,
    WEEKDAY,
    DEVICE,
    /** [CandidateSource] ordinal of the primary generator. */
    SOURCE,
    /** Number of generators that proposed it. */
    SOURCE_COUNT,
    /** Global popularity 0..1; 0.5 when unknown. */
    POPULARITY,
    /** Generator's own score. */
    RETRIEVAL,
    /** 1 if the artist or cluster is boosted by the listener. */
    USER_BOOST,
    /** "Less like this" marks on the artist. */
    LESS_LIKE_THIS_MARKS,
    /** Cosine to the radio seed, -1..1; 0 outside radio. */
    SEED_SIM,
}

/** Dense feature vector indexed by [Feature.ordinal]. */
class FeatureVector(val values: FloatArray = FloatArray(Feature.entries.size)) {
    operator fun get(f: Feature): Float = values[f.ordinal]

    operator fun set(f: Feature, v: Float) {
        values[f.ordinal] = v
    }

    fun toMap(): Map<Feature, Float> = Feature.entries.associateWith { values[it.ordinal] }
}

/**
 * Computes [FeatureVector]s for candidates against one profile, session and context. Construct
 * once per ranking pass: fatigue counts are precomputed here.
 */
class FeatureExtractor(
    private val profile: TasteProfile,
    private val controls: UserControls,
    private val sessionVector: FloatArray?,
    private val nowMs: Long,
    private val context: PlayContext?,
    /** Radio seed vector, when ranking a radio. */
    private val seedVector: FloatArray? = null,
) {
    private val artistHour = HashMap<ArtistKey, Int>()
    private val artistDay = HashMap<ArtistKey, Int>()
    private val albumHour = HashMap<String, Int>()
    private val albumDay = HashMap<String, Int>()
    private val trackDay = HashMap<TrackKey, Int>()

    init {
        for (p in profile.recentPlays) {
            val age = nowMs - p.startedAtMs
            if (age < 0 || age > DAY_MS) continue
            artistDay.inc(p.artist)
            trackDay.inc(p.track)
            p.album?.let { albumDay.inc(it) }
            if (age <= HOUR_MS) {
                artistHour.inc(p.artist)
                p.album?.let { albumHour.inc(it) }
            }
        }
    }

    private fun <K> HashMap<K, Int>.inc(k: K) {
        this[k] = (this[k] ?: 0) + 1
    }

    fun extract(candidate: Candidate, vector: FloatArray?): FeatureVector {
        val f = FeatureVector()
        val meta = candidate.track
        val artist = meta.artistKey
        val ts = profile.track(candidate.key)
        val ars = profile.artist(artist)

        if (vector != null) {
            sessionVector?.let { f[Feature.SESSION_SIM] = vector.cosine(it) }
            seedVector?.let { f[Feature.SEED_SIM] = vector.cosine(it) }
            profile.longTermVector?.let { f[Feature.LONG_TERM_SIM] = vector.cosine(it) }
            profile.dislikeVector?.let { f[Feature.DISLIKE_SIM] = vector.cosine(it) }
            profile.nearestCluster(vector)?.let { (c, sim) ->
                f[Feature.CLUSTER_SIM] = sim
                f[Feature.CLUSTER_SKIP_RATE] = c.skips.rate().toFloat()
            } ?: run { f[Feature.CLUSTER_SKIP_RATE] = SkipStats().rate().toFloat() }
        } else {
            f[Feature.CLUSTER_SKIP_RATE] = SkipStats().rate().toFloat()
        }

        f[Feature.TRACK_AFFINITY] = ts?.let { tanh(it.affinity / 3).toFloat() } ?: 0f
        f[Feature.ARTIST_AFFINITY] = ars?.let { tanh(it.affinity / 5).toFloat() } ?: 0f
        f[Feature.HEARD_BEFORE] = if ((ts?.plays ?: 0) > 0) 1f else 0f
        f[Feature.ARTIST_HEARD_BEFORE] = if ((ars?.plays ?: 0) > 0) 1f else 0f

        val released = meta.releasedAtMs
        if (released != null) {
            val days = ((nowMs - released).coerceAtLeast(0) / DAY_MS.toDouble())
            f[Feature.FRESHNESS_DAYS] = days.toFloat()
            f[Feature.FRESHNESS] = exp(-days / 30).toFloat()
        } else {
            f[Feature.FRESHNESS_DAYS] = -1f
        }
        f[Feature.DAYS_SINCE_PLAYED] = ts?.let { ((nowMs - it.lastPlayedMs).coerceAtLeast(0) / DAY_MS.toDouble()).toFloat() } ?: -1f

        f[Feature.TRACK_PLAYS_DAY] = (trackDay[candidate.key] ?: 0).toFloat()
        f[Feature.ARTIST_PLAYS_HOUR] = (artistHour[artist] ?: 0).toFloat()
        f[Feature.ARTIST_PLAYS_DAY] = (artistDay[artist] ?: 0).toFloat()
        val album = meta.albumKey
        f[Feature.ALBUM_PLAYS_HOUR] = album?.let { albumHour[it] ?: 0 }?.toFloat() ?: 0f
        f[Feature.ALBUM_PLAYS_DAY] = album?.let { albumDay[it] ?: 0 }?.toFloat() ?: 0f

        f[Feature.ARTIST_SKIP_RATE] = (ars?.skips ?: SkipStats()).rate().toFloat()
        f[Feature.SOURCE_SKIP_RATE] = (profile.sources[candidate.source] ?: SkipStats()).rate().toFloat()

        f[Feature.HOUR_BUCKET] = context?.hourBucket?.toFloat() ?: -1f
        f[Feature.WEEKDAY] = context?.weekday?.toFloat() ?: -1f
        f[Feature.DEVICE] = context?.device?.ordinal?.toFloat() ?: -1f
        f[Feature.SOURCE] = candidate.source.ordinal.toFloat()
        f[Feature.SOURCE_COUNT] = candidate.sources.size.toFloat()
        f[Feature.POPULARITY] = meta.popularity ?: 0.5f
        f[Feature.RETRIEVAL] = candidate.retrieval
        f[Feature.USER_BOOST] = if (artist in controls.boostedArtists || controls.isInBoostedCluster(vector)) 1f else 0f
        f[Feature.LESS_LIKE_THIS_MARKS] = (controls.lessLikeThisArtists[artist] ?: 0).toFloat()
        return f
    }
}
