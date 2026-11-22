package app.turtleplayer.taste

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

/**
 * One song, whichever upload it comes from. YouTube often has the same recording as an official
 * video and as an audio-only upload with different ids; the identity (primary artist, canonical
 * title and version markers such as live or remix, see [TrackKey.fromMeta]) is the same for both,
 * so recommendations never offer both and a heard twin counts as heard.
 *
 * Canonicalizing a title is not free, so identities are cached ([capacity] most recent). Not
 * thread-safe: each engine uses its own, under its lock.
 */
class SongKeys(private val capacity: Int = 20_000) {
    private val cache = LinkedHashMap<TrackKey, TrackKey>()

    fun of(track: TrackMeta): TrackKey {
        cache[track.key]?.let { return it }
        val artist = track.artists.firstOrNull()
        val identity = if (artist.isNullOrBlank() || track.title.isBlank()) {
            track.key
        } else {
            runCatching { TrackKey.fromMeta(artist, track.title) }.getOrDefault(track.key)
        }
        if (cache.size >= capacity) cache.remove(cache.keys.first())
        cache[track.key] = identity
        return identity
    }
}
