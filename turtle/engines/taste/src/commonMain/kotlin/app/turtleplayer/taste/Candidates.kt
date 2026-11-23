package app.turtleplayer.taste

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.Surface

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

/** Where in the app a play started. [tag] is persisted in [app.turtleplayer.events.PlayEvent.surfaceDetail]: never rename. */
enum class PlayPlace(val tag: String, val surface: Surface) {
    /** Up Next, and every legacy `taste/<source>/<slot>` tag. */
    ENGINE("taste", Surface.AUTOPLAY),
    INFINITY("infinity", Surface.AUTOPLAY),
    SMART_SHUFFLE("shuffle", Surface.PLAYLIST),
    RADIO("radio", Surface.RADIO),
    SEARCH("search", Surface.SEARCH),
    /** Turtle's mixes and rows on Home; the row is the shelf id. */
    HOME_TURTLE("home-turtle", Surface.SHELF),
    /** Home's shortcut grid. */
    HOME_GRID("home-grid", Surface.SHELF),
    /** Home's "Jump back in"; the row is its row key. */
    HOME_RECENT("home-recent", Surface.SHELF),
    /** One of YouTube's rows on Home; the row is its row key. */
    HOME_YOUTUBE("home-yt", Surface.SHELF),
    ;

    companion object {
        fun fromTag(tag: String): PlayPlace? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Who put a suggested song in that order: Turtle's taste ranking, or the provider's list as it came.
 * [tag] is persisted in [app.turtleplayer.events.PlayEvent.surfaceDetail]: never rename.
 */
enum class Served(val tag: String) {
    /** Ordered by Turtle's taste ranking from a provider's list. */
    RANKED("ranked"),
    /** A provider search hit queued as it came, without ranking (Infinity's genre hits). */
    DIRECT("direct"),
    /** The provider's order by design or because there was nothing to rank (Home's YouTube rows, a one-song page). */
    PROVIDER("provider"),
    /** The provider's order because ranking ran out of time. */
    TIMEOUT("timeout"),
    /** The provider's order because ranking failed. */
    FAILED("failed"),
    ;

    companion object {
        fun fromTag(tag: String): Served? = entries.firstOrNull { it.tag == tag }
    }
}

/**
 * Where a play came from: the place, the generator and slot that proposed the track (when one did),
 * the row, who ordered it ([served], when a provider list was involved) and its 1-based [position]
 * among the songs of the row the listener tapped (Home only: elsewhere the session position says it).
 */
data class PlayOrigin(
    val place: PlayPlace,
    val source: CandidateSource? = null,
    val slot: SlotKind? = null,
    val row: String? = null,
    val served: Served? = null,
    val position: Int? = null,
) {
    val surface: Surface get() = place.surface
}

/**
 * The string the app stores in `PlayEvent.surfaceDetail` for plays it can attribute, so later
 * events can be attributed to a place, a generator (per-source skip rates) and a discovery slot
 * (exploration feedback). Format: `<place>[+<served>][@<position>]/<source|->/<slot|->`, then
 * `/<row>` when there is a row. The row keeps its own slashes; line breaks and tabs in it become
 * spaces. Legacy Infinity tags (`infinity/<pick>`) still parse; anything else Turtle did not write
 * parses to null. Builds before [Served] read a tag with `+` or `@` as unattributed.
 */
object SurfaceTag {
    private const val NONE = "-"

    fun format(source: CandidateSource, slot: SlotKind): String = format(PlayOrigin(PlayPlace.ENGINE, source, slot))

    fun format(origin: PlayOrigin): String {
        val served = origin.served?.let { "+${it.tag}" }.orEmpty()
        val position = origin.position?.takeIf { it > 0 }?.let { "@$it" }.orEmpty()
        val head = "${origin.place.tag}$served$position/${origin.source?.tag ?: NONE}/${origin.slot?.tag ?: NONE}"
        val row = origin.row?.replace('\n', ' ')?.replace('\r', ' ')?.replace('\t', ' ')
        return if (row.isNullOrEmpty()) head else "$head/$row"
    }

    fun parse(detail: String?): PlayOrigin? {
        if (detail.isNullOrEmpty()) return null
        val parts = detail.split('/', limit = 4)
        val head = parseHead(parts[0]) ?: return null
        val place = head.place
        if (parts.size == 2) return if (place == PlayPlace.INFINITY && head.plain) legacyInfinity(parts[1]) else null
        if (parts.size < 3) return null
        val source = if (parts[1] == NONE) null else CandidateSource.fromTag(parts[1]) ?: return null
        val slot = if (parts[2] == NONE) null else SlotKind.entries.firstOrNull { it.tag == parts[2] } ?: return null
        return PlayOrigin(place, source, slot, parts.getOrNull(3)?.takeIf { it.isNotEmpty() }, head.served, head.position)
    }

    private class Head(val place: PlayPlace, val served: Served?, val position: Int?) {
        val plain get() = served == null && position == null
    }

    /** `<place>[+<served>][@<position>]`; null when any part is not one Turtle writes. */
    private fun parseHead(head: String): Head? {
        val at = head.indexOf('@')
        val position = if (at < 0) null else head.substring(at + 1).toIntOrNull()?.takeIf { it > 0 } ?: return null
        val rest = if (at < 0) head else head.substring(0, at)
        val plus = rest.indexOf('+')
        val served = if (plus < 0) null else Served.fromTag(rest.substring(plus + 1)) ?: return null
        val place = PlayPlace.fromTag(if (plus < 0) rest else rest.substring(0, plus)) ?: return null
        return Head(place, served, position)
    }

    /** `infinity/<pick>`, written before plays carried a full origin. "foryou" never stored its generator. */
    private fun legacyInfinity(pick: String): PlayOrigin? = when {
        pick == "familiar" -> PlayOrigin(PlayPlace.INFINITY, CandidateSource.LIBRARY, SlotKind.FAMILIAR, pick)
        pick == "morelike" || pick.startsWith("genre:") -> PlayOrigin(PlayPlace.INFINITY, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, pick)
        pick == "foryou" -> PlayOrigin(PlayPlace.INFINITY, row = pick)
        else -> null
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
