package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

/** Which generator proposed a candidate. Tags are persisted in [app.turtleplayer.events.PlayEvent.surfaceDetail]; never rename. */
enum class CandidateSource(val tag: String) {
    SESSION("session"),
    TASTE_CLUSTER("cluster"),
    COLLABORATIVE("cf"),
    FRESH_RELEASE("fresh"),
    REDISCOVERY("rediscover"),
    UPSTREAM_RADIO("upstream"),
    ONBOARDING("onboarding"),
    /** The listener's own history and library (On repeat; their tracks around a seed without vectors). */
    LIBRARY("library"),
    ;

    companion object {
        fun fromTag(tag: String): CandidateSource? = entries.firstOrNull { it.tag == tag }
    }
}

/** Whether a queue slot (or a candidate) is something the listener already knows. */
enum class SlotKind(val tag: String) {
    /** A track they have heard before. */
    FAMILIAR("f"),
    /** A track they have never heard. */
    DISCOVERY("d"),
}

/**
 * The string the app stores in `PlayEvent.surfaceDetail` for plays the engine suggested, so later
 * events can be attributed to a generator (per-source skip rates) and to a discovery slot
 * (exploration feedback). Format: `taste/<source>/<slot>`.
 */
object SurfaceTag {
    private const val PREFIX = "taste/"

    fun format(source: CandidateSource, slot: SlotKind): String = "$PREFIX${source.tag}/${slot.tag}"

    fun parse(detail: String?): Pair<CandidateSource, SlotKind>? {
        if (detail == null || !detail.startsWith(PREFIX)) return null
        val parts = detail.removePrefix(PREFIX).split('/')
        if (parts.size != 2) return null
        val source = CandidateSource.fromTag(parts[0]) ?: return null
        val slot = SlotKind.entries.firstOrNull { it.tag == parts[1] } ?: return null
        return source to slot
    }
}

/** What a radio is seeded from. */
sealed interface RadioSeed {
    data class Track(val track: TrackMeta) : RadioSeed
    data class Artist(val artist: ArtistKey) : RadioSeed
}

/**
 * Why something is recommended. Typed, never a string: the UI localizes it ("Because you replayed
 * X"). [key] identifies the reason for "less like this" suppression.
 */
sealed interface Reason {
    val key: String

    /** Close to what is playing in this session. */
    data class BecauseYouListened(val track: TrackKey) : Reason {
        override val key get() = "listened:$track"
    }

    data class BecauseYouReplayed(val track: TrackKey) : Reason {
        override val key get() = "replayed:$track"
    }

    data class BecauseYouLiked(val track: TrackKey) : Reason {
        override val key get() = "liked:$track"
    }

    data class SimilarTo(val artist: ArtistKey) : Reason {
        override val key get() = "similar:$artist"
    }

    /** Collaborative filtering: listeners of [artist] also play this. */
    data class ListenersAlsoPlay(val artist: ArtistKey) : Reason {
        override val key get() = "cf:$artist"
    }

    /** Matches one of the listener's long-term taste clusters. */
    data class FromYourTaste(val clusterId: Int, val topArtists: List<ArtistKey>) : Reason {
        override val key get() = "cluster:${topArtists.firstOrNull() ?: clusterId}"
    }

    data class FreshFrom(val artist: ArtistKey) : Reason {
        override val key get() = "fresh:$artist"
    }

    data class Rediscover(val track: TrackKey, val lastPlayedMs: Long) : Reason {
        override val key get() = "rediscover:$track"
    }

    data class RadioFrom(val seed: RadioSeed) : Reason {
        override val key
            get() = when (seed) {
                is RadioSeed.Track -> "radio:${seed.track.key}"
                is RadioSeed.Artist -> "radio:${seed.artist}"
            }
    }

    /** From the upstream (YouTube) radio for [seed]. */
    data class UpstreamRadio(val seed: TrackKey) : Reason {
        override val key get() = "upstream:$seed"
    }

    /** One of the artists picked during onboarding. */
    data class BecauseYouPicked(val artist: ArtistKey) : Reason {
        override val key get() = "picked:$artist"
    }

    /**
     * Shelf-level: a Daily Mix for one taste cluster or, while there are no co-listening vectors
     * (hence no clusters), an artist mix. An artist mix has [seedArtist] set (also the single entry
     * of [topArtists]) and [clusterId] is its rank 0..2; the UI titles it "<artist> Mix" using
     * [TasteEngine.displayName]. A cluster mix has [seedArtist] null and is titled "Daily Mix <n>".
     */
    data class DailyMix(val clusterId: Int, val topArtists: List<ArtistKey>, val seedArtist: ArtistKey? = null) : Reason {
        override val key get() = "mix:${topArtists.firstOrNull() ?: clusterId}"
    }

    /** Shelf-level: the tracks they have been finishing most over the last month. */
    data object OnRepeat : Reason {
        override val key get() = "on_repeat"
    }

    /** Shelf-level: never-heard tracks picked for this listener. */
    data object NewForYou : Reason {
        override val key get() = "new_for_you"
    }

    /** Shelf-level: new releases from artists they like. */
    data object FreshForYou : Reason {
        override val key get() = "fresh_for_you"
    }

    /** Shelf-level: songs they loved but have not heard for a while. */
    data object RediscoverShelf : Reason {
        override val key get() = "rediscover_shelf"
    }

    /** Shelf-level: what plays next, driven by the live session. */
    data object UpNext : Reason {
        override val key get() = "up_next"
    }
}

/**
 * A proposed track. [retrieval] is the generator's own similarity-like score in [0, 1]-ish range,
 * used as a ranking feature. [sources] holds every generator that proposed it.
 */
data class Candidate(
    val track: TrackMeta,
    val source: CandidateSource,
    val reason: Reason,
    val retrieval: Float,
    val sources: Set<CandidateSource> = setOf(source),
) {
    val key: TrackKey get() = track.key
}
