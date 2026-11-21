package app.turtleplayer.mix.rack

import app.turtleplayer.mix.CueKind
import app.turtleplayer.mix.Curve
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.Segment

/*
 * A MixScript flattened into primitive arrays on the controller's thread, so the audio threads read
 * it without iterators, boxing or allocation. Immutable once built.
 */

internal const val CURVE_LINEAR = 0
internal const val CURVE_STEP = 1
internal const val CURVE_EASE_IN = 2
internal const val CURVE_EASE_OUT = 3
internal const val CURVE_SMOOTH = 4

/** Cue codes in the queues: 0 = empty, 1 = Seek, 2 + SampleId.ordinal = Sample. */
internal const val CUE_SEEK = 1
internal const val CUE_SAMPLE_BASE = 2

internal class CLane(val beats: DoubleArray, val values: FloatArray, val curves: IntArray) {
    /** Before the first point the first value holds; after the last, the last. */
    fun valueAt(b: Double): Float {
        val n = beats.size
        if (b < beats[0] || n == 1) return values[0]
        var hi = n - 1
        if (b >= beats[hi]) return values[hi]
        var lo = 0
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (beats[mid] <= b) lo = mid else hi = mid
        }
        // beats[lo] <= b < beats[hi]
        val curve = curves[hi]
        if (curve == CURVE_STEP) return values[lo]
        val span = beats[hi] - beats[lo]
        if (span <= 0.0) return values[hi]
        val t = ((b - beats[lo]) / span).toFloat()
        val s = when (curve) {
            CURVE_EASE_IN -> t * t
            CURVE_EASE_OUT -> 1f - (1f - t) * (1f - t)
            CURVE_SMOOTH -> t * t * (3f - 2f * t)
            else -> t
        }
        return values[lo] + (values[hi] - values[lo]) * s
    }
}

internal class CSegment(
    val length: Double,
    /** Index deck.ordinal * PARAMS + param.ordinal. */
    val lanes: Array<CLane?>,
    val cueBeats: DoubleArray,
    val cueCodes: IntArray,
    /** Sorted beats where something changes discretely: 0, every point, every cue, the length. */
    val events: DoubleArray,
) {
    fun lane(deck: Int, param: Int): CLane? = lanes[deck * PARAMS + param]

    /** The first event strictly after [b] (+ epsilon), or +infinity. */
    fun nextEvent(b: Double): Double {
        val e = events
        val key = b + BEAT_EPS
        var lo = 0
        var hi = e.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (e[mid] <= key) lo = mid + 1 else hi = mid
        }
        return if (lo < e.size) e[lo] else Double.POSITIVE_INFINITY
    }
}

internal class CompiledScript(val pre: CSegment, val post: CSegment, val manualFader: Boolean) {
    companion object {
        /** Null when the script is unusable (non-finite or negative lengths). */
        fun compile(script: MixScript): CompiledScript? {
            val pre = segment(script.pre) ?: return null
            val post = segment(script.post) ?: return null
            return CompiledScript(pre, post, script.manualFader)
        }

        private fun segment(s: Segment): CSegment? {
            if (!s.lengthBeats.isFinite() || s.lengthBeats < 0) return null
            val lanes = arrayOfNulls<CLane>(2 * PARAMS)
            val events = ArrayList<Double>()
            events.add(0.0)
            events.add(s.lengthBeats)
            for (lane in s.lanes) {
                val pts = lane.points.filter { it.beat.isFinite() && it.value.isFinite() }.sortedBy { it.beat }
                if (pts.isEmpty()) continue
                val p = lane.param
                val beats = DoubleArray(pts.size) { pts[it].beat }
                val values = FloatArray(pts.size) { pts[it].value.coerceIn(p.min, p.max) }
                val curves = IntArray(pts.size) {
                    when (pts[it].curve) {
                        Curve.Linear -> CURVE_LINEAR
                        Curve.Step -> CURVE_STEP
                        Curve.EaseIn -> CURVE_EASE_IN
                        Curve.EaseOut -> CURVE_EASE_OUT
                        Curve.Smooth -> CURVE_SMOOTH
                    }
                }
                lanes[lane.deck.ordinal * PARAMS + p.ordinal] = CLane(beats, values, curves)
                for (b in beats) events.add(b)
            }
            val cues = s.cues.filter { it.atBeat.isFinite() }.sortedBy { it.atBeat }
            for (c in cues) events.add(c.atBeat)
            val sorted = events.filter { it >= 0.0 }.distinct().sorted()
            return CSegment(
                length = s.lengthBeats,
                lanes = lanes,
                cueBeats = DoubleArray(cues.size) { cues[it].atBeat },
                cueCodes = IntArray(cues.size) {
                    when (val k = cues[it].kind) {
                        CueKind.Seek -> CUE_SEEK
                        is CueKind.Sample -> CUE_SAMPLE_BASE + k.id.ordinal
                    }
                },
                events = DoubleArray(sorted.size) { sorted[it] },
            )
        }
    }
}

// ---- parameter indices (FxParam ordinals) as constants for the hot path --------------------------

internal val PARAMS = FxParam.entries.size
internal val P_GAIN = FxParam.Gain.ordinal
internal val P_LOW = FxParam.Low.ordinal
internal val P_MID = FxParam.Mid.ordinal
internal val P_HIGH = FxParam.High.ordinal
internal val P_FILTER = FxParam.Filter.ordinal
internal val P_DRY = FxParam.Dry.ordinal
internal val P_SEND = FxParam.EchoSend.ordinal
internal val P_WET = FxParam.EchoWet.ordinal
internal val P_FEEDBACK = FxParam.EchoFeedback.ordinal
internal val P_ECHO_BEATS = FxParam.EchoBeats.ordinal
internal val P_GATE = FxParam.Gate.ordinal
internal val P_GATE_BEATS = FxParam.GateBeats.ordinal
internal val P_ROLL = FxParam.Roll.ordinal
internal val P_ROLL_BEATS = FxParam.RollBeats.ordinal
internal val P_BRAKE = FxParam.Brake.ordinal
internal val P_BRAKE_BEATS = FxParam.BrakeBeats.ordinal
internal val P_SPIN = FxParam.Spin.ordinal
internal val P_SPIN_BEATS = FxParam.SpinBeats.ordinal
internal val P_FLANGER = FxParam.Flanger.ordinal

internal val NEUTRAL = FloatArray(PARAMS) { FxParam.entries[it].neutral }

/** Beat comparisons tolerate this much (well under a microsecond at any tempo). */
internal const val BEAT_EPS = 1e-6
