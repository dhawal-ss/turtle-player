package app.turtleplayer.bridge

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import com.metrolist.shared.model.SongItem

/**
 * Upstream's [SongItem] as our [TrackMeta]. User edits to title and artist win, because they are
 * what the listener recognises and searches for. `SongItem.duration` is in seconds.
 */
fun SongItem.toTrackMeta(): TrackMeta {
    val artistNames = customArtist?.takeIf { it.isNotBlank() }?.let { listOf(it) }
        ?: (canonicalArtists ?: artists).map { it.name }.filter { it.isNotBlank() }
    return TrackMeta(
        key = TrackKey.youtube(id),
        title = customTitle?.takeIf { it.isNotBlank() } ?: canonicalTitle ?: title,
        artists = artistNames,
        album = album?.name,
        durationMs = duration?.takeIf { it > 0 }?.times(1000),
        youtubeId = id,
        explicit = isExplicit,
    )
}
