package app.turtleplayer.mix.brain

import app.turtleplayer.mix.CueKind
import app.turtleplayer.mix.Curve
import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.Lane
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.Segment
import kotlin.math.abs
import kotlin.math.round

/**
 * Renders a script's lanes the way the rack plays them (SPEC "Values: layers"): in pre, the pre lane or
 * neutral; while waiting for the incoming and in post, the post lane if there is one, else the pre lane
 * held at its last point, else neutral. Curves: Linear, Step (jump on the point), EaseIn x², EaseOut
 * 1-(1-x)², Smooth x²(3-2x).
 */
class ScriptRender(val script: MixScript) {

    fun pre(deck: Deck, param: FxParam, beat: Double): Float =
        script.pre.lane(deck, param)?.let { valueAt(it, beat) } ?: param.neutral

    /** The value held while waiting for the incoming: the pre lane's last point, or neutral. */
    fun held(deck: Deck, param: FxParam): Float =
        script.pre.lane(deck, param)?.points?.last()?.value ?: param.neutral

    fun post(deck: Deck, param: FxParam, beat: Double): Float =
        script.post.lane(deck, param)?.let { valueAt(it, beat) } ?: held(deck, param)

    val seekBeat: Double? get() = script.pre.cues.firstOrNull { it.kind == CueKind.Seek }?.atBeat

    companion object {
        const val STEP = 1.0 / 64

        fun Segment.lane(deck: Deck, param: FxParam): Lane? = lanes.firstOrNull { it.deck == deck && it.param == param }

        fun valueAt(lane: Lane, beat: Double): Float {
            val pts = lane.points
            if (beat <= pts.first().beat) {
                // A Step point at the very first beat applies from that beat on.
                return pts.first().value
            }
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                if (beat < b.beat) {
                    val span = b.beat - a.beat
                    val x = if (span <= 0.0) 1.0 else ((beat - a.beat) / span).coerceIn(0.0, 1.0)
                    val shaped = when (b.curve) {
                        Curve.Linear -> x
                        Curve.Step -> 0.0
                        Curve.EaseIn -> x * x
                        Curve.EaseOut -> 1 - (1 - x) * (1 - x)
                        Curve.Smooth -> x * x * (3 - 2 * x)
                    }
                    return (a.value + (b.value - a.value) * shaped).toFloat()
                }
            }
            return pts.last().value
        }

        fun beats(from: Double, to: Double): Sequence<Double> {
            val n = round((to - from) / STEP).toInt()
            return (0..n).asSequence().map { from + it * STEP }
        }
    }
}

/**
 * The musical contract every script must keep (see Scripts' doc). Returns violations, empty when the
 * script is fine.
 */
object ScriptChecks {
    private const val EPS = 1e-4

    fun violations(script: MixScript): List<String> {
        val v = ArrayList<String>()
        wellFormed("pre", script.pre, v)
        wellFormed("post", script.post, v)
        if (abs(script.pre.lengthBeats - round(script.pre.lengthBeats)) > 1e-9) v += "pre length ${script.pre.lengthBeats} is not whole beats"
        val seeks = script.pre.cues.filter { it.kind == CueKind.Seek }
        if (seeks.size != 1) v += "pre has ${seeks.size} Seek cues"
        if (script.post.cues.any { it.kind == CueKind.Seek }) v += "Seek cue in post"
        if (v.isNotEmpty()) return v

        val r = ScriptRender(script)
        val seek = seeks.single().atBeat
        val roll = gateOnset(script, FxParam.Roll, seek)
        val brake = gateOnset(script, FxParam.Brake, seek)
        val spin = gateOnset(script, FxParam.Spin, seek)

        fun gateValid(onset: Double?, lengthParam: FxParam?, value: Float): Boolean {
            if (value < 0.5f || onset == null) return false
            if (onset > seek + EPS) return false
            if (lengthParam == null) return true
            val len = ScriptRender(script).pre(Deck.Out, lengthParam, onset)
            return onset + len <= seek + EPS
        }

        fun masked(get: (FxParam) -> Float): Boolean =
            get(FxParam.Gain) <= 0.15f + EPS ||
                (get(FxParam.Dry) <= 0.05f && get(FxParam.EchoSend) <= 0.05f) ||
                gateValid(roll, FxParam.RollBeats, get(FxParam.Roll)) ||
                gateValid(brake, FxParam.BrakeBeats, get(FxParam.Brake)) ||
                gateValid(spin, null, get(FxParam.Spin))

        // The jump is masked from 1/8 beat before the Seek until post starts (pre values held while waiting).
        for (t in ScriptRender.beats(maxOf(0.0, seek - 0.125), script.pre.lengthBeats)) {
            if (!masked { r.pre(Deck.Out, it, t) }) { v += "outgoing jump audible in pre at beat $t"; break }
        }

        // A post Roll gate only counts while it stays on continuously from pre.
        val postRollOffAt = script.post.lanesFor(Deck.Out, FxParam.Roll)?.let { lane ->
            ScriptRender.beats(0.0, script.post.lengthBeats).firstOrNull { ScriptRender.valueAt(lane, it) < 0.5f }
        }

        if (!script.manualFader) {
            for (t in ScriptRender.beats(0.0, script.post.lengthBeats)) {
                val out = { p: FxParam -> if (p == FxParam.Roll && postRollOffAt != null && t >= postRollOffAt) 0f else r.post(Deck.Out, p, t) }
                val inn = { p: FxParam -> r.post(Deck.In, p, t) }
                if (!masked(out) && inn(FxParam.Gain) < 0.25f) { v += "outgoing jump audible in post at beat $t (In gain ${inn(FxParam.Gain)})"; break }
                if (bass(out) && bass(inn)) { v += "bass doubles at post beat $t"; break }
            }
            val end = script.post.lengthBeats
            if (r.post(Deck.Out, FxParam.Gain, end) > EPS) v += "outgoing not silent at the end (Gain ${r.post(Deck.Out, FxParam.Gain, end)})"
            if (ScriptRender.beats(0.0, minOf(4.0, end)).none { r.post(Deck.In, FxParam.Gain, it) >= 0.4f }) v += "incoming not entering within 4 beats"
        }
        for (p in FxParam.entries) {
            val end = r.post(Deck.In, p, script.post.lengthBeats)
            if (abs(end - p.neutral) > EPS) v += "incoming $p ends at $end, not neutral"
        }

        // No jumps between segments on the outgoing (Gain may drop: that is the cut itself).
        for (lane in script.post.lanes.filter { it.deck == Deck.Out }) {
            val before = r.held(Deck.Out, lane.param)
            val start = lane.points.first().value
            val ok = abs(start - before) <= EPS || (lane.param == FxParam.Gain && start < before)
            if (!ok) v += "Out ${lane.param} jumps from $before to $start at post start"
        }
        return v
    }

    private fun Segment.lanesFor(deck: Deck, param: FxParam): Lane? = lanes.firstOrNull { it.deck == deck && it.param == param }

    /** Bass audible: loud enough, low band not killed, not high-passed, live (not echo-only, not stopped). */
    private fun bass(get: (FxParam) -> Float): Boolean =
        get(FxParam.Gain) >= 0.25f && get(FxParam.Low) > -0.6f && get(FxParam.Filter) < 0.35f &&
            get(FxParam.Dry) >= 0.2f && get(FxParam.Brake) < 0.5f && get(FxParam.Spin) < 0.5f

    /** The last beat before [seek] where the Out gate rose through 0.5 in pre (0 if on from the start). */
    private fun gateOnset(script: MixScript, param: FxParam, seek: Double): Double? {
        val lane = script.pre.lanes.firstOrNull { it.deck == Deck.Out && it.param == param } ?: return null
        var onset: Double? = null
        var prev = param.neutral
        for (t in ScriptRender.beats(0.0, script.pre.lengthBeats)) {
            val x = ScriptRender.valueAt(lane, t)
            if (x >= 0.5f && prev < 0.5f && t <= seek + EPS) onset = t
            if (x < 0.5f && t <= seek + EPS) onset = null
            prev = x
        }
        return onset
    }

    private fun wellFormed(name: String, s: Segment, v: MutableList<String>) {
        if (s.lengthBeats <= 0.0) v += "$name has no length"
        val seen = HashSet<Pair<Deck, FxParam>>()
        for (lane in s.lanes) {
            if (!seen.add(lane.deck to lane.param)) v += "$name: duplicate lane ${lane.deck}.${lane.param}"
            if (lane.points.isEmpty()) v += "$name: empty lane ${lane.deck}.${lane.param}"
            lane.points.zipWithNext().forEach { (a, b) -> if (b.beat < a.beat) v += "$name: ${lane.deck}.${lane.param} not sorted" }
            for (p in lane.points) {
                if (p.beat < 0.0 || p.beat > s.lengthBeats + 1e-9) v += "$name: ${lane.deck}.${lane.param} point at ${p.beat} outside segment"
                if (!p.value.isFinite() || p.value < lane.param.min || p.value > lane.param.max) v += "$name: ${lane.deck}.${lane.param} value ${p.value} out of range"
                if (!onGrid(p.beat)) v += "$name: ${lane.deck}.${lane.param} point at ${p.beat} off the 1/8-beat grid"
            }
        }
        s.cues.zipWithNext().forEach { (a, b) -> if (b.atBeat < a.atBeat) v += "$name: cues not sorted" }
        for (c in s.cues) {
            if (c.atBeat < 0.0 || c.atBeat > s.lengthBeats + 1e-9) v += "$name: cue at ${c.atBeat} outside segment"
            if (!onGrid(c.atBeat)) v += "$name: cue at ${c.atBeat} off the 1/8-beat grid"
        }
    }

    private fun onGrid(beat: Double): Boolean = abs(beat * 8 - round(beat * 8)) < 1e-6
}
