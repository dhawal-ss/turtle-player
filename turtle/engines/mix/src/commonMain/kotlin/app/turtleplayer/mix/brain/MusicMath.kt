package app.turtleplayer.mix.brain

import app.turtleplayer.mix.TrackFacts
import kotlin.math.abs
import kotlin.math.min

/** Small shared helpers: tempo relations, genre and artist identity. */
internal object MusicMath {
    /** Tempo used when a track's BPM is missing or absurd. */
    const val FALLBACK_BPM = 120f

    fun safeBpm(bpm: Float): Float = if (bpm.isFinite() && bpm in 40f..250f) bpm else FALLBACK_BPM

    /** Seconds per beat at [bpm]. */
    fun beatSeconds(bpm: Float): Double = 60.0 / safeBpm(bpm)

    /**
     * Relative tempo gap from [from] to [to], allowing half and double time (a 64 BPM track sits on
     * a 128 BPM grid): `min |to·k / from - 1|` for k in {1/2, 1, 2}.
     */
    fun tempoGap(from: Float, to: Float): Double {
        val a = safeBpm(from).toDouble()
        val b = safeBpm(to).toDouble()
        return min(abs(b / a - 1), min(abs(b * 0.5 / a - 1), abs(b * 2.0 / a - 1)))
    }

    fun sameGenre(a: TrackFacts, b: TrackFacts): Boolean = genreKey(a.genre) == genreKey(b.genre)

    fun genreKey(genre: String): String = genre.trim().lowercase()

    /** Normalized artist names of a credit like "A & B feat. C" -> {a, b, c}. */
    fun artistKeys(artist: String): Set<String> {
        val lower = " " + artist.lowercase() + " "
        var parts = listOf(lower)
        for (sep in ARTIST_SEPARATORS) parts = parts.flatMap { it.split(sep) }
        return parts.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }

    fun shareArtist(a: TrackFacts, b: TrackFacts): Boolean = artistKeys(a.artist).any { it in artistKeys(b.artist) }

    private val ARTIST_SEPARATORS = listOf(",", " & ", " feat. ", " feat ", " ft. ", " ft ", " x ", ";")
}
