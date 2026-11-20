package app.turtleplayer.core

import kotlin.jvm.JvmInline

/**
 * Canonical identity of a recording, stored as `scheme:id`.
 *
 * Preference order: ISRC, then MusicBrainz recording ID, then YouTube video ID. Tracks that only
 * have names (for example imported Spotify history that has not been matched yet) use a `meta:` key
 * built from the normalized primary artist and canonical title, so the same song from different
 * sources collapses to one key.
 */
@JvmInline
value class TrackKey(val value: String) {
    init {
        require(value.indexOf(':') > 0) { "TrackKey must be scheme:id, got '$value'" }
    }

    val scheme: String get() = value.substringBefore(':')
    val id: String get() = value.substringAfter(':')

    override fun toString(): String = value

    companion object {
        const val ISRC = "isrc"
        const val MBID = "mbid"
        const val YOUTUBE = "yt"
        const val SPOTIFY = "spotify"
        const val META = "meta"

        fun isrc(code: String) = TrackKey("$ISRC:${code.uppercase().filter { it.isLetterOrDigit() }}")
        fun mbid(id: String) = TrackKey("$MBID:${id.lowercase()}")
        fun youtube(videoId: String) = TrackKey("$YOUTUBE:$videoId")
        fun spotify(trackId: String) = TrackKey("$SPOTIFY:$trackId")

        /** Name-based key; identical for "Artist - Song (Official Video)" and "artist – song". */
        fun fromMeta(primaryArtist: String, title: String): TrackKey {
            val artistName = cleanArtistName(primaryArtist)
            val canonical = TitleCanonicalizer.canonicalize(title, artistName)
            val artist = TextNormalizer.normalize(artistName).folded
            val versions = canonical.versions.map { it.name.lowercase() }.sorted()
            val suffix = if (versions.isEmpty()) "" else "|" + versions.joinToString(",")
            return TrackKey("$META:$artist|${canonical.base}$suffix")
        }

        /** Picks the strongest identity available. */
        fun best(isrc: String? = null, mbid: String? = null, youtubeId: String? = null, artist: String? = null, title: String? = null): TrackKey =
            when {
                !isrc.isNullOrBlank() -> isrc(isrc)
                !mbid.isNullOrBlank() -> mbid(mbid)
                !youtubeId.isNullOrBlank() -> youtube(youtubeId)
                !artist.isNullOrBlank() && !title.isNullOrBlank() -> fromMeta(artist, title)
                else -> throw IllegalArgumentException("No identity available")
            }
    }
}

/** Normalized artist identity used for affinity, fatigue and spacing rules. */
@JvmInline
value class ArtistKey(val value: String) {
    override fun toString(): String = value

    companion object {
        fun of(name: String): ArtistKey = ArtistKey(TextNormalizer.normalize(cleanArtistName(name)).folded)
    }
}

private val channelSuffix = Regex("""(?i)\s*(?:-\s*topic|vevo|\s+official)\s*$""")

/** Removes YouTube channel decorations: "Coldplay - Topic", "ColdplayVEVO", "Coldplay Official". */
fun cleanArtistName(name: String): String = name.replace(channelSuffix, "").trim().ifEmpty { name.trim() }

/** What we know about a track, independent of where it came from. */
data class TrackMeta(
    val key: TrackKey,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Epoch milliseconds of the release date, when known. */
    val releasedAtMs: Long? = null,
    val youtubeId: String? = null,
    val isrc: String? = null,
    val explicit: Boolean = false,
    /** Global popularity in [0, 1], when a source provides one. */
    val popularity: Float? = null,
) {
    val primaryArtist: String get() = artists.firstOrNull().orEmpty()
    val artistKey: ArtistKey get() = ArtistKey.of(primaryArtist)
    val albumKey: String? get() = album?.let { "${artistKey.value}|${TextNormalizer.normalize(it).folded}" }
}

/** Injectable time source so engines are deterministic under test. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val System: Clock = Clock { kotlin.time.Clock.System.now().toEpochMilliseconds() }
    }
}

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS
