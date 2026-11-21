package app.turtleplayer.mix.brain

import app.turtleplayer.mix.Cue
import app.turtleplayer.mix.CueKind
import app.turtleplayer.mix.Curve
import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.Lane
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.Point
import app.turtleplayer.mix.SampleId
import app.turtleplayer.mix.Segment
import app.turtleplayer.mix.TrackFacts
import app.turtleplayer.mix.TransitionStyle
import kotlin.math.floor
import kotlin.math.roundToInt

/** How a style's script is shaped beyond the tracks themselves. */
internal data class ScriptOptions(
    /** Post length of Blend (and FilterFade when [filterBeats] is null), beats. */
    val blendBeats: Int,
    /** Post length of FilterFade, beats (default 8). */
    val filterBeats: Int = 8,
    /** Air horn on the drop of a Cut. */
    val airHorn: Boolean = false,
    /** False: Gain lanes only (Blend becomes a duck-and-fade, Cut a quick fade); other styles fall back to those. */
    val fx: Boolean = true,
)

/**
 * The transition scripts. Every script is timed in beats at the outgoing tempo, starts on a bar (the
 * controller runs it with Quantize.Bar), and follows the same contract (checked by the brain's tests):
 *
 * - **Seek masked.** After the Seek cue the outgoing jumps to its last seconds; until the incoming is
 *   audibly entering, the outgoing is covered by a roll whose slice was captured before the cue, a
 *   ringing echo with Dry and EchoSend at 0, a brake or spin (they read pre-seek history), or a duck
 *   (Gain <= 0.15, no-FX only). Masks are in place at least 1/8 beat before the cue.
 * - **Bass never doubles.** The incoming's low end is killed (Low -1 or high-pass) while the outgoing's
 *   is audible; the swap is a Step.
 * - **The incoming arrives whole.** Every incoming lane ends neutral.
 * - **The outgoing ends silent.** Out Gain is 0 at the end of post.
 * - **No jumps between segments.** A post lane of the outgoing starts where its pre lane ended
 *   (except Out Gain dropping to 0 on a drop, which is the cut itself).
 */
internal object Scripts {

    /** Seconds the riser sample lasts (rack: 4 s). */
    private const val RISER_SECONDS = 4.0

    fun build(style: TransitionStyle, out: TrackFacts, inn: TrackFacts, options: ScriptOptions): MixScript {
        if (!options.fx) {
            return when (style) {
                TransitionStyle.Blend, TransitionStyle.FilterFade -> blendGainOnly(options.blendBeats)
                else -> cutGainOnly()
            }
        }
        return when (style) {
            TransitionStyle.Blend -> blend(options.blendBeats)
            TransitionStyle.FilterFade -> filterFade(options.filterBeats)
            TransitionStyle.EchoOut -> echoOut(out, inn)
            TransitionStyle.Cut -> cut(options.airHorn)
            TransitionStyle.Spinback -> spinback()
            TransitionStyle.Brake -> brake(out, inn)
            TransitionStyle.RiserDrop -> riserDrop(out)
            TransitionStyle.LoopRoll -> loopRoll()
        }
    }

    // ---- Styles -------------------------------------------------------------------------------

    /**
     * EQ blend. Pre 4: Out Low eases to -0.3, a 1-beat roll on beat 3 holds the bar while the Seek (beat 4)
     * jumps. Post L: In fades in with its bass killed and highs tucked, Out highs ease down, bass swap
     * (Step) at L/2, Out fades over the last 3L/8. The roll lets go on post beat 1, with In already at 40 %.
     */
    fun blend(beats: Int): MixScript {
        val l = beats.coerceIn(4, 64).toDouble()
        val q = l / 4
        val h = l / 2
        val pre = SegmentBuilder(4.0).apply {
            lane(Deck.Out, FxParam.Low, p(0.0, 0f), p(4.0, -0.3f, Curve.Smooth))
            lane(Deck.Out, FxParam.RollBeats, p(0.0, 1f))
            lane(Deck.Out, FxParam.Roll, p(0.0, 0f), p(3.0, 1f, Curve.Step))
            cue(4.0, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(l).apply {
            lane(Deck.Out, FxParam.Roll, p(0.0, 1f), p(1.0, 0f, Curve.Step))
            lane(Deck.Out, FxParam.Low, p(0.0, -0.3f), p(h, -1f, Curve.Step))
            lane(Deck.Out, FxParam.High, p(0.0, 0f), p(q, 0f), p(h, -0.6f, Curve.Smooth), p(l, -0.8f))
            lane(Deck.Out, FxParam.Gain, p(0.0, 1f), p(l - 3 * l / 8, 1f), p(l, 0f, Curve.Smooth))
            if (q > 1.0) {
                lane(Deck.In, FxParam.Gain, p(0.0, 0f), p(1.0, 0.4f, Curve.EaseOut), p(q, 1f, Curve.Smooth))
            } else {
                lane(Deck.In, FxParam.Gain, p(0.0, 0f), p(q, 1f, Curve.Smooth))
            }
            lane(Deck.In, FxParam.Low, p(0.0, -1f), p(h, 0f, Curve.Step))
            lane(Deck.In, FxParam.High, p(0.0, -0.5f), p(q, -0.5f), p(h, 0f, Curve.Smooth))
        }.build()
        return MixScript(pre, post)
    }

    /**
     * Pre 8: Out low-passes to -0.7 (EaseIn), 1-beat roll from beat 7, Seek at 8. Post L (8): In opens
     * from a high-pass (0.8 -> 0) while fading in; Out sinks to -1 and fades out by 3L/4, before In's
     * filter is low enough to let its bass through.
     */
    fun filterFade(beats: Int): MixScript {
        val l = beats.coerceIn(4, 64).toDouble()
        val s = l / 8
        val pre = SegmentBuilder(8.0).apply {
            lane(Deck.Out, FxParam.Filter, p(0.0, 0f), p(8.0, -0.7f, Curve.EaseIn))
            lane(Deck.Out, FxParam.RollBeats, p(0.0, 1f))
            lane(Deck.Out, FxParam.Roll, p(0.0, 0f), p(7.0, 1f, Curve.Step))
            cue(8.0, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(l).apply {
            lane(Deck.Out, FxParam.Roll, p(0.0, 1f), p(1.0, 0f, Curve.Step))
            lane(Deck.Out, FxParam.Filter, p(0.0, -0.7f), p(6 * s, -1f))
            lane(Deck.Out, FxParam.Gain, p(0.0, 1f), p(2 * s, 1f), p(6 * s, 0f))
            val inRamp = 4 * s
            if (inRamp > 1.0) {
                lane(Deck.In, FxParam.Gain, p(0.0, 0f), p(1.0, 0.4f, Curve.EaseOut), p(inRamp, 1f, Curve.Smooth))
            } else {
                lane(Deck.In, FxParam.Gain, p(0.0, 0f), p(inRamp, 1f, Curve.Smooth))
            }
            lane(Deck.In, FxParam.Filter, p(0.0, 0.8f), p(3 * s, 0.7f), p(6 * s, 0.45f), p(l, 0f, Curve.Smooth))
        }.build()
        return MixScript(pre, post)
    }

    /**
     * Pre 4: Out bass eases out (so the echo is not boomy), at 2 the dotted-eighth echo opens (send 1,
     * wet 0.9, feedback 0.6), at 3 Dry and send drop (the tail rings), Seek at 3.25. Post 4: In drops on
     * beat 0 (fades in over 2 when the energy falls hard into a non-dance track); the tail fades.
     */
    fun echoOut(out: TrackFacts, inn: TrackFacts): MixScript {
        val pre = SegmentBuilder(4.0).apply {
            lane(Deck.Out, FxParam.Low, p(0.0, 0f), p(1.0, 0f), p(3.0, -0.7f, Curve.Smooth))
            lane(Deck.Out, FxParam.EchoBeats, p(0.0, 0.75f))
            lane(Deck.Out, FxParam.EchoFeedback, p(0.0, 0.6f))
            lane(Deck.Out, FxParam.EchoWet, p(0.0, 0f), p(2.0, 0.9f, Curve.Step))
            lane(Deck.Out, FxParam.EchoSend, p(0.0, 0f), p(2.0, 1f, Curve.Step), p(3.0, 0f, Curve.Step))
            lane(Deck.Out, FxParam.Dry, p(0.0, 1f), p(3.0, 0f, Curve.Step))
            cue(3.25, CueKind.Seek)
        }.build()
        val softLanding = inn.energy < out.energy - 0.3f && !inn.dance
        val post = SegmentBuilder(4.0).apply {
            lane(Deck.Out, FxParam.Dry, p(0.0, 0f))
            lane(Deck.Out, FxParam.EchoWet, p(0.0, 0.9f), p(4.0, 0f))
            lane(Deck.Out, FxParam.Gain, p(0.0, 1f), p(2.0, 1f), p(4.0, 0f))
            if (softLanding) lane(Deck.In, FxParam.Gain, p(0.0, 0f), p(2.0, 1f, Curve.Smooth))
            else lane(Deck.In, FxParam.Gain, p(0.0, 1f))
        }.build()
        return MixScript(pre, post)
    }

    /** Pre 1: the downbeat rolls in half beats (slice captured by 0.5), Seek at 0.5. Post 2: In slams in, Out gone. Air horn optional. */
    fun cut(airHorn: Boolean): MixScript {
        val pre = SegmentBuilder(1.0).apply {
            lane(Deck.Out, FxParam.RollBeats, p(0.0, 0.5f))
            lane(Deck.Out, FxParam.Roll, p(0.0, 1f))
            cue(0.5, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(2.0).apply {
            lane(Deck.Out, FxParam.Gain, p(0.0, 0f, Curve.Step))
            lane(Deck.In, FxParam.Gain, p(0.0, 1f, Curve.Step))
            if (airHorn) cue(0.0, CueKind.Sample(SampleId.AirHorn))
        }.build()
        return MixScript(pre, post)
    }

    /**
     * Pre 2: Out spins back over 1.5 beats on the downbeat, feeding a half-beat echo that fills the gap
     * before the drop; Seek at 0.5 (the spin reads pre-seek history). Post 2: In drops, the echo fades.
     */
    fun spinback(): MixScript {
        val pre = SegmentBuilder(2.0).apply {
            lane(Deck.Out, FxParam.SpinBeats, p(0.0, 1.5f))
            lane(Deck.Out, FxParam.Spin, p(0.0, 1f))
            lane(Deck.Out, FxParam.EchoBeats, p(0.0, 0.5f))
            lane(Deck.Out, FxParam.EchoFeedback, p(0.0, 0.55f))
            lane(Deck.Out, FxParam.EchoWet, p(0.0, 0.6f))
            lane(Deck.Out, FxParam.EchoSend, p(0.0, 0.8f), p(1.5, 0f, Curve.Step))
            cue(0.5, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(2.0).apply {
            lane(Deck.Out, FxParam.EchoWet, p(0.0, 0.6f), p(2.0, 0f))
            lane(Deck.Out, FxParam.Gain, p(0.0, 1f), p(2.0, 0f))
            lane(Deck.In, FxParam.Gain, p(0.0, 1f, Curve.Step))
        }.build()
        return MixScript(pre, post)
    }

    /** Brake length: about 1.5 s, 2 to 4 beats. */
    fun brakeBeats(bpm: Float): Int = (1.5 * MusicMath.safeBpm(bpm) / 60.0).roundToInt().coerceIn(2, 4)

    /**
     * Pre B (2-4 beats, ~1.5 s): tape stop on the downbeat with the filter closing to -0.4; Seek at B, when
     * the stop has read its last pre-seek audio. Post 2: In drops (fades in over 2 of 4 when the energy
     * falls a lot).
     */
    fun brake(out: TrackFacts, inn: TrackFacts): MixScript {
        val b = brakeBeats(out.bpm).toDouble()
        val pre = SegmentBuilder(b).apply {
            lane(Deck.Out, FxParam.BrakeBeats, p(0.0, b.toFloat()))
            lane(Deck.Out, FxParam.Brake, p(0.0, 1f))
            lane(Deck.Out, FxParam.Filter, p(0.0, 0f), p(b, -0.4f, Curve.EaseIn))
            cue(b, CueKind.Seek)
        }.build()
        val soft = inn.energy < out.energy - 0.25f
        val post = SegmentBuilder(if (soft) 4.0 else 2.0).apply {
            lane(Deck.Out, FxParam.Gain, p(0.0, 0f, Curve.Step))
            if (soft) lane(Deck.In, FxParam.Gain, p(0.0, 0f), p(2.0, 1f, Curve.Smooth))
            else lane(Deck.In, FxParam.Gain, p(0.0, 1f, Curve.Step))
        }.build()
        return MixScript(pre, post)
    }

    /**
     * Pre 8: the riser starts so it peaks at the end of pre (beat 0 at >= 120 BPM), Out high-passes to
     * 0.85 (EaseIn), the gate opens over the last 2 beats, a half-beat roll from 6.5 covers the Seek at 7,
     * and Out fades to silence on beat 8 (a breath before the drop). Post 4: Impact + In drops at full.
     */
    fun riserDrop(out: TrackFacts): MixScript {
        val riserBeats = RISER_SECONDS * MusicMath.safeBpm(out.bpm) / 60.0
        val riserAt = (floor((8.0 - riserBeats) * 2) / 2).coerceIn(0.0, 4.0)
        val pre = SegmentBuilder(8.0).apply {
            cue(riserAt, CueKind.Sample(SampleId.Riser))
            lane(Deck.Out, FxParam.Filter, p(0.0, 0f), p(7.0, 0.85f, Curve.EaseIn))
            lane(Deck.Out, FxParam.GateBeats, p(0.0, 0.25f))
            lane(Deck.Out, FxParam.Gate, p(0.0, 0f), p(6.0, 0f), p(8.0, 0.6f))
            lane(Deck.Out, FxParam.RollBeats, p(0.0, 0.5f))
            lane(Deck.Out, FxParam.Roll, p(0.0, 0f), p(6.5, 1f, Curve.Step))
            lane(Deck.Out, FxParam.Gain, p(0.0, 1f), p(7.0, 1f), p(8.0, 0f, Curve.EaseIn))
            cue(7.0, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(4.0).apply {
            cue(0.0, CueKind.Sample(SampleId.Impact))
            lane(Deck.Out, FxParam.Gain, p(0.0, 0f))
            lane(Deck.In, FxParam.Gain, p(0.0, 1f, Curve.Step))
        }.build()
        return MixScript(pre, post)
    }

    /**
     * Pre 4: the downbeat rolls, 1 beat, then 1/2 (beat 2), 1/4 (3), 1/8 (3.5), while a high-pass rises to
     * 0.5; Seek at 3. Post 2: In drops on 0, Out gone.
     */
    fun loopRoll(): MixScript {
        val pre = SegmentBuilder(4.0).apply {
            lane(Deck.Out, FxParam.Roll, p(0.0, 1f))
            lane(
                Deck.Out, FxParam.RollBeats,
                p(0.0, 1f), p(2.0, 0.5f, Curve.Step), p(3.0, 0.25f, Curve.Step), p(3.5, 0.125f, Curve.Step),
            )
            lane(Deck.Out, FxParam.Filter, p(0.0, 0f), p(4.0, 0.5f, Curve.EaseIn))
            cue(3.0, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(2.0).apply {
            lane(Deck.Out, FxParam.Gain, p(0.0, 0f, Curve.Step))
            lane(Deck.In, FxParam.Gain, p(0.0, 1f, Curve.Step))
        }.build()
        return MixScript(pre, post)
    }

    /**
     * No effects available: Out ducks to 15 % by beat 7.5, Seek at 8 (the jump is heard only ducked),
     * then In fades in over min(L/2, 4) beats while Out's last 15 % goes. Gain lanes only.
     */
    fun blendGainOnly(beats: Int): MixScript {
        val l = beats.coerceIn(4, 64).toDouble()
        val fade = minOf(l / 2, 4.0)
        val pre = SegmentBuilder(8.0).apply {
            lane(Deck.Out, FxParam.Gain, p(0.0, 1f), p(7.5, DUCK, Curve.Smooth))
            cue(8.0, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(l).apply {
            lane(Deck.Out, FxParam.Gain, p(0.0, DUCK), p(fade, 0f))
            lane(Deck.In, FxParam.Gain, p(0.0, 0f), p(fade, 1f, Curve.Smooth))
        }.build()
        return MixScript(pre, post)
    }

    /** No effects available: Out fades out over a quarter beat, Seek at 0.5, In drops. Gain lanes only. */
    fun cutGainOnly(): MixScript {
        val pre = SegmentBuilder(1.0).apply {
            lane(Deck.Out, FxParam.Gain, p(0.0, 1f), p(0.25, 0f, Curve.Smooth))
            cue(0.5, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(2.0).apply {
            lane(Deck.Out, FxParam.Gain, p(0.0, 0f))
            lane(Deck.In, FxParam.Gain, p(0.0, 1f, Curve.Step))
        }.build()
        return MixScript(pre, post)
    }

    /**
     * Manual crossfader. Pre 2: a 1-beat roll from beat 1 masks the Seek at 2. Post (16 beats, a progress
     * hint only): the listener's fader drives Gain and the bass swap; the roll lets go after 2 beats.
     */
    fun fader(): MixScript {
        val pre = SegmentBuilder(2.0).apply {
            lane(Deck.Out, FxParam.RollBeats, p(0.0, 1f))
            lane(Deck.Out, FxParam.Roll, p(0.0, 0f), p(1.0, 1f, Curve.Step))
            cue(2.0, CueKind.Seek)
        }.build()
        val post = SegmentBuilder(16.0).apply {
            lane(Deck.Out, FxParam.Roll, p(0.0, 1f), p(2.0, 0f, Curve.Step))
        }.build()
        return MixScript(pre, post, manualFader = true)
    }

    private const val DUCK = 0.15f

    private fun p(beat: Double, value: Float, curve: Curve = Curve.Linear) = Point(beat, value, curve)
}

/** Collects lanes and cues for one segment; values are clamped to their parameter's range. */
internal class SegmentBuilder(private val length: Double) {
    private val lanes = ArrayList<Lane>()
    private val cues = ArrayList<Cue>()

    fun lane(deck: Deck, param: FxParam, vararg points: Point) {
        require(points.isNotEmpty()) { "empty lane $deck.$param" }
        require(lanes.none { it.deck == deck && it.param == param }) { "duplicate lane $deck.$param" }
        val clamped = points.map { it.copy(beat = it.beat.coerceIn(0.0, length), value = it.value.coerceIn(param.min, param.max)) }
        lanes += Lane(deck, param, clamped.sortedBy { it.beat })
    }

    fun cue(beat: Double, kind: CueKind) {
        cues += Cue(beat.coerceIn(0.0, length), kind)
    }

    fun build(): Segment = Segment(length, lanes.toList(), cues.sortedBy { it.atBeat })
}
