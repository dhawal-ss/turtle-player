package app.turtleplayer.mix.brain

import app.turtleplayer.mix.TrackFacts
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Where in a track to start mixing out. */
object MixPoints {

    /** Radio edits: keep this share of the end out (fade-outs), at most [RADIO_TAIL_MAX_MS]. */
    internal const val RADIO_TAIL_SHARE = 0.08
    internal const val RADIO_TAIL_MAX_MS = 25_000L

    /** Quick mix: about a minute in, after a first chorus. */
    internal const val QUICK_MS = 65_000L

    /** Quick-mix floor: never before 45 s or 40 % of the track, whichever comes first. */
    internal const val QUICK_FLOOR_MS = 45_000L
    internal const val QUICK_FLOOR_SHARE = 0.4

    /** Always start at least this long before the end. */
    internal const val MIN_BEFORE_END_MS = 30_000L

    /**
     * Media position (ms) to start the transition's pre segment on [track] at [bpm].
     *
     * pace 0: the pre segment ends where the track's own ending starts: for dance tracks the last 32 to 64
     * beats (DJ outro; 32 for tracks up to 4 min, 64 from 7 min, a multiple of 16 between, at most a
     * quarter of the track), for others the last 8 % (max 25 s; fade-outs). pace 1: about 65 s in, but
     * never before min(45 s, 40 % of the track). Between: linear. Always at least 30 s before the end
     * (and never before 0).
     */
    fun mixOutAtMs(track: TrackFacts, bpm: Float, pace: Float, preBeats: Double): Long {
        val dur = track.durationMs.coerceAtLeast(0L)
        val beatMs = MusicMath.beatSeconds(bpm) * 1000.0
        val preMs = (preBeats.coerceAtLeast(0.0) * beatMs)

        val tailMs = if (track.dance) {
            val minutes = dur / 60_000.0
            val beats = when {
                minutes <= 4.0 -> 32
                minutes >= 7.0 -> 64
                else -> 32 + (((minutes - 4.0) / 3.0 * 32.0) / 16.0).toInt() * 16
            }
            min(beats * beatMs, dur * 0.25)
        } else {
            min(dur * RADIO_TAIL_SHARE, RADIO_TAIL_MAX_MS.toDouble())
        }
        val natural = dur - tailMs - preMs
        val quickFloor = min(QUICK_FLOOR_MS.toDouble(), dur * QUICK_FLOOR_SHARE)
        val quick = max(QUICK_MS.toDouble(), quickFloor)

        val p = pace.coerceIn(0f, 1f).toDouble()
        var at = natural + (quick - natural) * p
        at = max(at, quickFloor)
        at = min(at, (dur - MIN_BEFORE_END_MS).toDouble())
        return max(0.0, at).roundToLong()
    }
}
