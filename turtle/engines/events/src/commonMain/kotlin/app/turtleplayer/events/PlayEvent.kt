package app.turtleplayer.events

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

/** Where a play came from. */
enum class Surface { SEARCH, AUTOPLAY, SHELF, RADIO, PLAYLIST, ALBUM, ARTIST, LIBRARY, QUEUE, EXTERNAL, UNKNOWN }

/** Why playback of a track started. */
enum class StartReason {
    TAPPED,
    AUTOPLAY,
    /** Previous track was skipped forward. */
    USER_NEXT,
    USER_PREVIOUS,
    RESUMED,
    REMOTE,
    UNKNOWN,
}

/** Why playback of a track ended. */
enum class EndReason {
    COMPLETED,
    SKIPPED,
    SKIPPED_BACK,
    STOPPED,
    ERROR,
    UNKNOWN,
    ;

    val isSkip: Boolean get() = this == SKIPPED || this == SKIPPED_BACK
}

enum class EventSource { LOCAL, SPOTIFY_IMPORT, LASTFM_IMPORT, LISTENBRAINZ_IMPORT, METROLIST_IMPORT }

enum class DeviceType { PHONE, TABLET, DESKTOP, CAR, TV, SPEAKER, UNKNOWN }

enum class NetworkType { WIFI, CELLULAR, ETHERNET, OFFLINE, UNKNOWN }

data class PlayContext(
    /** Local hour of day, 0..23. */
    val hour: Int,
    /** ISO weekday, 1 = Monday .. 7 = Sunday. */
    val weekday: Int,
    val device: DeviceType = DeviceType.UNKNOWN,
    val network: NetworkType = NetworkType.UNKNOWN,
) {
    /** Coarse bucket used as a ranking feature: night, morning, afternoon, evening. */
    val hourBucket: Int get() = when (hour) {
        in 0..5 -> 0
        in 6..11 -> 1
        in 12..17 -> 2
        else -> 3
    }
}

/**
 * One play of one track: the fuel for every recommendation and search-personalization signal.
 *
 * Fields deliberately mirror Spotify's Extended Streaming History so imported history lands in the
 * same table as our own events.
 */
data class PlayEvent(
    val id: Long = 0,
    val track: TrackMeta,
    val sessionId: String,
    /** Index of this play within its session. */
    val position: Int,
    val surface: Surface,
    val startReason: StartReason,
    val endReason: EndReason,
    /** Epoch milliseconds when playback started. */
    val startedAtMs: Long,
    val msPlayed: Long,
    val durationMs: Long?,
    val seeks: Int = 0,
    val replayed: Boolean = false,
    val shuffle: Boolean? = null,
    val liked: Boolean = false,
    val addedToPlaylist: Boolean = false,
    val lessLikeThis: Boolean = false,
    val context: PlayContext? = null,
    val source: EventSource = EventSource.LOCAL,
    /** Free-form origin detail, e.g. the shelf or generator that suggested this track. */
    val surfaceDetail: String? = null,
) {
    val trackKey: TrackKey get() = track.key
    val artistKey: ArtistKey get() = track.artistKey

    /** Fraction of the track heard, when the duration is known. */
    val completion: Float? get() = durationMs?.takeIf { it > 0 }?.let { (msPlayed.toFloat() / it).coerceIn(0f, 1f) }

    /** Skipped before 30 seconds: the strongest negative signal we get. */
    val isQuickSkip: Boolean get() = endReason.isSkip && msPlayed < QUICK_SKIP_MS

    /** Heard to the end, or to at least 80% when the end reason is unknown (imports). */
    val isFinished: Boolean get() =
        endReason == EndReason.COMPLETED || (endReason != EndReason.SKIPPED && (completion ?: 0f) >= 0.8f)

    /** A play that counts as a listen for history and scrobbling purposes. */
    val isListen: Boolean get() = msPlayed >= LISTEN_MS || (completion ?: 0f) >= 0.5f

    companion object {
        const val QUICK_SKIP_MS = 30_000L
        const val LISTEN_MS = 30_000L
    }
}
