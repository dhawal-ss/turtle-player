package app.turtleplayer.mix.brain

import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.TrackFacts
import app.turtleplayer.mix.TransitionPlan
import app.turtleplayer.mix.TransitionReason
import app.turtleplayer.mix.TransitionStyle
import app.turtleplayer.mix.VibeSettings
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow
import kotlin.random.Random

/**
 * Chooses how to mix from one track into the next, and writes the script.
 *
 * Style choice is a weighted draw: each style gets a weight from the pair (tempo gap, energy jump,
 * genre change, dance or not) and the listener's flair, then variety rules apply (see [weights]).
 */
object TransitionPlanner {

    /** Tempo gap (half/double allowed) up to which tempos count as close / beyond which they are far. */
    internal const val TEMPO_CLOSE = 0.06
    internal const val TEMPO_FAR = 0.08

    /** Energy change that counts as a big rise or fall. */
    internal const val BIG_JUMP = 0.2f

    /** Below this flair, Blend and FilterFade stay allowed across tempo gaps and genre changes. */
    internal const val SMOOTH_FLAIR = 0.15f

    /** Seconds of overlap margin on top of the post segment (incoming start-up, beat rounding). */
    internal const val OVERLAP_MARGIN_SECONDS = 1.5
    internal const val MAX_OVERLAP_SECONDS = 30.0

    /**
     * How to mix from [out] into [inn]. [recent] holds the styles of the previous transitions, oldest
     * first (the last element is the transition just before this one).
     */
    fun plan(
        out: TrackFacts,
        inn: TrackFacts,
        vibe: VibeSettings,
        recent: List<TransitionStyle>,
        random: Random,
        fxAvailable: Boolean = true,
    ): TransitionPlan {
        val pair = PairFacts(out, inn)
        val w = weights(pair, vibe, recent, fxAvailable)
        val style = draw(w, random) ?: if (fxAvailable) TransitionStyle.Blend else TransitionStyle.Cut

        val flair = vibe.flair.coerceIn(0f, 1f)
        val blendBeats = blendBeats(pair, vibe)
        val airHorn = style == TransitionStyle.Cut && fxAvailable && flair >= 0.6f && inn.energy >= 0.5f &&
            random.nextFloat() < (flair - 0.5f) * 1.5f
        val options = ScriptOptions(blendBeats = blendBeats, airHorn = airHorn, fx = fxAvailable)
        val script = Scripts.build(style, out, inn, options)
        val reasons = reasons(style, pair, vibe, blendBeats, airHorn)
        return TransitionPlan(style, script, overlapSeconds(script, out.bpm), reasons)
    }

    /** The script for a style the listener chose (manual mode); [beats] overrides the Blend/FilterFade post length. */
    fun script(style: TransitionStyle, out: TrackFacts, inn: TrackFacts, beats: Int? = null): MixScript {
        val dancePair = out.dance && inn.dance
        val blend = beats ?: if (dancePair) 16 else 8
        return Scripts.build(style, out, inn, ScriptOptions(blendBeats = blend, filterBeats = beats ?: 8))
    }

    /** Manual crossfader transition: a short masking pre (roll on the seek) and a manualFader post. */
    fun faderScript(out: TrackFacts, inn: TrackFacts): MixScript = Scripts.fader()

    /**
     * Seconds the incoming must overlap the outgoing for [script] at [outBpm]: the post segment plus one
     * beat (post starts on the first beat after the incoming starts) plus 1.5 s, at most 30 s. For a
     * manual-fader script the post length is only a hint: use [MAX_OVERLAP_SECONDS].
     */
    fun overlapSeconds(script: MixScript, outBpm: Float): Float {
        if (script.manualFader) return MAX_OVERLAP_SECONDS.toFloat()
        val beat = MusicMath.beatSeconds(outBpm)
        return minOf(MAX_OVERLAP_SECONDS, (script.post.lengthBeats + 1.0) * beat + OVERLAP_MARGIN_SECONDS).toFloat()
    }

    // ---- Choice -------------------------------------------------------------------------------

    internal class PairFacts(val out: TrackFacts, val inn: TrackFacts) {
        val gap: Double = MusicMath.tempoGap(out.bpm, inn.bpm)
        val tempoClose = gap <= TEMPO_CLOSE
        val tempoFar = gap > TEMPO_FAR
        val jump: Float = inn.energy - out.energy
        val bigRise = jump >= BIG_JUMP
        val bigFall = jump <= -BIG_JUMP
        val genreChange = !MusicMath.sameGenre(out, inn)
        val dancePair = out.dance && inn.dance
        val sameGroove = tempoClose && !genreChange && abs(jump) <= 0.12f
    }

    /** Flair -> weight of the showy tricks (Spinback, Brake, RiserDrop, LoopRoll): 0 up to 0.15, ~0.38 at 0.5, ~1.30 at 0.9. */
    internal fun trickFactor(flair: Float): Double {
        val x = ((flair - SMOOTH_FLAIR) / (1f - SMOOTH_FLAIR)).coerceIn(0f, 1f).toDouble()
        return x.pow(1.6) * 1.6
    }

    /**
     * Blend and FilterFade need the two tracks to sit on one groove: tempos within 8 % (half/double
     * allowed) and the same genre. A genre change between two dance tracks whose tempos are within 6 %
     * (house -> techno at 126) still blends, at half weight. Below flair 0.15 the listener asked for
     * smooth: always allowed.
     */
    internal fun blendAllowed(pair: PairFacts, flair: Float): Boolean =
        flair < SMOOTH_FLAIR || (!pair.tempoFar && (!pair.genreChange || (pair.dancePair && pair.tempoClose)))

    /** Longest run of Blends allowed in a row: 6 at flair 0.1, 4 at 0.5, 2 at 0.9. */
    internal fun maxBlendRun(flair: Float): Int = floor(6.5 - 5.0 * flair.coerceIn(0f, 1f)).toInt().coerceAtLeast(1)

    internal fun weights(pair: PairFacts, vibe: VibeSettings, recent: List<TransitionStyle>, fx: Boolean): Map<TransitionStyle, Double> {
        val f = vibe.flair.coerceIn(0f, 1f)
        val smooth = 1.0 - f
        val trick = trickFactor(f)
        val w = LinkedHashMap<TransitionStyle, Double>()
        for (s in TransitionStyle.entries) w[s] = 0.0

        if (!fx) {
            w[TransitionStyle.Blend] = 2.0 * (0.3 + smooth) * (if (pair.bigRise) 0.5 else 1.0)
            w[TransitionStyle.Cut] = (0.3 + (if (pair.bigRise) 0.9 else 0.0) + (if (pair.tempoFar) 0.4 else 0.0)) * (0.3 + 0.9 * f)
        } else {
            if (blendAllowed(pair, f)) {
                val base = when {
                    !pair.dancePair -> 1.0
                    pair.tempoClose -> 3.0
                    else -> 1.2
                }
                // A genre change blended (house -> techno on the same grid) is fine, but less of a given.
                val lane = if (pair.genreChange && f >= SMOOTH_FLAIR) 0.5 else 1.0
                w[TransitionStyle.Blend] = base * lane * (0.25 + smooth) * (if (pair.bigRise || pair.bigFall) 0.6 else 1.0)
                w[TransitionStyle.FilterFade] = lane * (0.9 * (0.4 + 0.6 * smooth) + (if (pair.bigFall) 1.0 else 0.0)) *
                    (if (pair.dancePair) 1.0 else 0.8)
            }
            w[TransitionStyle.EchoOut] = (
                0.5 + (if (pair.bigFall) 1.2 else 0.0) + (if (!pair.dancePair) 0.8 else 0.0) +
                    (if (pair.tempoFar || pair.genreChange) 1.0 else 0.0)
                ) * (0.5 + 0.5 * f)
            w[TransitionStyle.Cut] = (
                0.3 + (if (pair.bigRise) 0.9 else 0.0) + (if (!pair.dancePair) 0.5 else 0.0) +
                    (if (pair.tempoFar) 0.5 else 0.0)
                ) * (0.3 + 0.9 * f)
            // Spinbacks are at home in any genre (hip-hop, reggae); builds and rolls want a dance groove.
            val groove = if (pair.dancePair) 1.0 else 0.6
            w[TransitionStyle.Spinback] = trick * (0.6 + (if (pair.bigRise) 0.4 else 0.0) + (if (pair.genreChange) 0.3 else 0.0))
            w[TransitionStyle.Brake] = trick * (0.5 + (if (pair.bigFall) 1.2 else 0.0)) * groove
            w[TransitionStyle.RiserDrop] = trick * (0.5 + (if (pair.bigRise) 1.5 else 0.0)) * groove
            w[TransitionStyle.LoopRoll] = trick * (0.6 + (if (pair.bigRise) 1.0 else 0.0)) * groove * groove
        }

        // Variety: no tricky style twice in a row; recently used tricks are less likely; Blend runs are capped.
        val last = recent.lastOrNull()
        if (last != null && last != TransitionStyle.Blend) w[last] = 0.0
        for (s in recent.takeLast(4).dropLast(1).toSet()) {
            if (s != TransitionStyle.Blend) w[s] = w.getValue(s) * 0.5
        }
        val blendRun = recent.asReversed().takeWhile { it == TransitionStyle.Blend }.count()
        w[TransitionStyle.Blend] = w.getValue(TransitionStyle.Blend) * 0.85.pow(blendRun)
        if (blendRun >= maxBlendRun(f) && w.any { (s, v) -> s != TransitionStyle.Blend && v > 0.0 }) {
            w[TransitionStyle.Blend] = 0.0
        }
        return w
    }

    private fun draw(weights: Map<TransitionStyle, Double>, random: Random): TransitionStyle? {
        val total = weights.values.sum()
        if (total <= 0.0) return null
        var r = random.nextDouble() * total
        var chosen: TransitionStyle? = null
        for ((s, v) in weights) {
            if (v <= 0.0) continue
            chosen = s
            if (r < v) break
            r -= v
        }
        return chosen
    }

    /**
     * Blend post length: 16; 32 for smooth (flair < 0.2), unhurried (pace < 0.5) dance pairs of long
     * tracks (>= 4 min); 8 for non-dance pairs, tempos 6-8 % apart, flair > 0.7 or quick mixes (pace >= 0.7).
     * Never longer than fits 30 s of overlap.
     */
    internal fun blendBeats(pair: PairFacts, vibe: VibeSettings): Int {
        val f = vibe.flair.coerceIn(0f, 1f)
        val pace = vibe.pace.coerceIn(0f, 1f)
        var l = when {
            !pair.dancePair || !pair.tempoClose || f > 0.7f || pace >= 0.7f -> 8
            f < 0.2f && pace < 0.5f && pair.out.durationMs >= 240_000 && pair.inn.durationMs >= 240_000 -> 32
            else -> 16
        }
        val beat = MusicMath.beatSeconds(pair.out.bpm)
        while (l > 8 && (l + 1) * beat + OVERLAP_MARGIN_SECONDS > MAX_OVERLAP_SECONDS) l /= 2
        return l
    }

    private fun reasons(style: TransitionStyle, pair: PairFacts, vibe: VibeSettings, blendBeats: Int, airHorn: Boolean): List<TransitionReason> {
        val r = ArrayList<TransitionReason>(3)
        val f = vibe.flair.coerceIn(0f, 1f)
        val quick = vibe.pace >= 0.7f && blendBeats == 8 && pair.dancePair && pair.tempoClose && f <= 0.7f
        when (style) {
            TransitionStyle.Blend, TransitionStyle.FilterFade -> {
                if (pair.sameGroove) r += TransitionReason.SameGroove
                else if (!pair.tempoFar) r += TransitionReason.TempoClose
                if (style == TransitionStyle.FilterFade && pair.bigFall) r += TransitionReason.EnergyDropDown
                if (quick && style == TransitionStyle.Blend) r += TransitionReason.QuickMix
                if (pair.genreChange) r += TransitionReason.GenreChange
                // Blending a pair that is not on one groove only happens because the listener asked for smooth.
                if (!blendAllowed(pair, 1f)) r += TransitionReason.YourStyle
            }
            TransitionStyle.EchoOut -> {
                if (pair.bigFall) r += TransitionReason.EnergyDropDown
                if (pair.tempoFar) r += TransitionReason.TempoFar
                if (pair.genreChange) r += TransitionReason.GenreChange
            }
            TransitionStyle.Cut -> {
                if (pair.bigRise) r += TransitionReason.EnergyJumpUp
                if (pair.tempoFar) r += TransitionReason.TempoFar
                if (pair.genreChange) r += TransitionReason.GenreChange
                if (airHorn) r += TransitionReason.YourStyle
            }
            TransitionStyle.Spinback, TransitionStyle.RiserDrop, TransitionStyle.LoopRoll -> {
                if (pair.bigRise) r += TransitionReason.EnergyJumpUp
                if (pair.tempoFar) r += TransitionReason.TempoFar
                if (pair.genreChange) r += TransitionReason.GenreChange
                r += TransitionReason.YourStyle
            }
            TransitionStyle.Brake -> {
                if (pair.bigFall) r += TransitionReason.EnergyDropDown
                if (pair.tempoFar) r += TransitionReason.TempoFar
                if (pair.genreChange) r += TransitionReason.GenreChange
                r += TransitionReason.YourStyle
            }
        }
        if (r.isEmpty()) r += TransitionReason.YourStyle
        // Flair-driven tricks keep YourStyle even when three facts apply.
        val list = r.distinct()
        return if (list.size > 3 && TransitionReason.YourStyle in list) list.filter { it != TransitionReason.YourStyle }.take(2) + TransitionReason.YourStyle
        else list.take(3)
    }
}
