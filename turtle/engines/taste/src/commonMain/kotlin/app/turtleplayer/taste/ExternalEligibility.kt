package app.turtleplayer.taste

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

/**
 * Which of a provider's candidates (a radio page) the listener may be offered, in the given order:
 * one upload per recording (the first one), never the [seed]'s recording, no muted artist, no track
 * marked "less like this", and no other upload of a hidden recording ([hiddenRecordings], song
 * identities as [SongKeys.of] gives them).
 *
 * Pure and lock-free, so a caller that cannot wait for ranking applies the same rule ranking does.
 * Muted clusters are not checked here: they need track vectors, which only ranking computes.
 *
 * [keys] is not thread-safe: pass one owned by the caller (the engine passes its own under its lock).
 */
fun eligibleExternal(
    seed: TrackMeta?,
    tracks: List<TrackMeta>,
    controls: UserControls,
    hiddenRecordings: Set<TrackKey> = emptySet(),
    keys: SongKeys = SongKeys(),
): List<TrackMeta> {
    val seedSong = seed?.let { keys.of(it) }
    val seen = HashSet<TrackKey>()
    return tracks.filter { track ->
        val song = keys.of(track)
        seen.add(song) &&
            song != seedSong &&
            song !in hiddenRecordings &&
            track.artistKey !in controls.mutedArtists &&
            track.key !in controls.lessLikeThisTracks
    }
}
