package app.turtleplayer.taste

import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** Samplers for the exploration policy. Implemented here (Marsaglia–Tsang) because common Kotlin has none. */
object Distributions {
    /** Standard normal via Box–Muller. */
    fun normal(random: Random): Double {
        var u1: Double
        do {
            u1 = random.nextDouble()
        } while (u1 <= 0.0)
        val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2.0 * PI * u2)
    }

    /**
     * Gamma(shape, 1) by Marsaglia and Tsang (2000). For shape < 1 uses the boost
     * `Gamma(a) = Gamma(a + 1) * U^(1/a)`.
     */
    fun gamma(shape: Double, random: Random): Double {
        require(shape > 0.0 && shape.isFinite()) { "shape must be positive: $shape" }
        if (shape < 1.0) {
            var u: Double
            do {
                u = random.nextDouble()
            } while (u <= 0.0)
            return gamma(shape + 1.0, random) * u.pow(1.0 / shape)
        }
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            val x = normal(random)
            var v = 1.0 + c * x
            if (v <= 0.0) continue
            v = v * v * v
            val u = random.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (u > 0.0 && ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    /** Beta(alpha, beta) = X / (X + Y) with X ~ Gamma(alpha), Y ~ Gamma(beta). */
    fun beta(alpha: Double, beta: Double, random: Random): Double {
        val x = gamma(alpha, random)
        val y = gamma(beta, random)
        return if (x + y == 0.0) 0.5 else x / (x + y)
    }
}

/**
 * Beta posterior over "a discovery slot succeeds". Plain data: persist the two numbers.
 * The prior Beta(2, 6) has mean 0.25.
 */
data class ExplorationState(val alpha: Double = PRIOR_ALPHA, val beta: Double = PRIOR_BETA) {
    val mean: Double get() = alpha / (alpha + beta)

    companion object {
        const val PRIOR_ALPHA = 2.0
        const val PRIOR_BETA = 6.0
    }
}

enum class DiscoveryOutcome { SUCCESS, FAILURE, NEUTRAL }

/**
 * Thompson sampling for the discovery share. Discoveries that are finished or saved are
 * successes, quick skips are failures. Old evidence is discounted by [forgetting] per update so
 * the share keeps adapting as the listener's mood shifts, but never below the prior.
 */
class ExplorationPolicy(
    private val random: Random,
    private val forgetting: Double = 0.99,
    /** Successes and failures saturate at this much pseudo-evidence, keeping the policy responsive. */
    private val maxEvidence: Double = 200.0,
) {
    /**
     * Classifies a play of a discovery-slot track. An explicit Less like this is always a failure.
     * Otherwise only a play the pick itself started counts: an imported play, a replay or a step
     * back to it is neutral, because the listener chose to hear it again rather than the pick
     * landing. Trade-off: `replayed` is also set when the listener seeks back to the start of a
     * mostly heard play, so a real engagement success on the pick's own play is dropped too. That
     * is the conservative side: never infer an outcome from a manual replay.
     */
    fun outcomeOf(event: PlayEvent): DiscoveryOutcome = when {
        event.lessLikeThis -> DiscoveryOutcome.FAILURE
        event.source != EventSource.LOCAL || event.replayed || event.startReason == StartReason.USER_PREVIOUS -> DiscoveryOutcome.NEUTRAL
        event.isQuickSkip -> DiscoveryOutcome.FAILURE
        event.isFinished || event.liked || event.addedToPlaylist -> DiscoveryOutcome.SUCCESS
        else -> DiscoveryOutcome.NEUTRAL
    }

    fun update(state: ExplorationState, outcome: DiscoveryOutcome): ExplorationState {
        if (outcome == DiscoveryOutcome.NEUTRAL) return state
        var a = ExplorationState.PRIOR_ALPHA + (state.alpha - ExplorationState.PRIOR_ALPHA) * forgetting
        var b = ExplorationState.PRIOR_BETA + (state.beta - ExplorationState.PRIOR_BETA) * forgetting
        if (outcome == DiscoveryOutcome.SUCCESS) a += 1.0 else b += 1.0
        val evidence = a + b
        if (evidence > maxEvidence) {
            a *= maxEvidence / evidence
            b *= maxEvidence / evidence
        }
        return ExplorationState(a, b)
    }

    /** One Thompson draw of the discovery share, clamped by the listener's dial. */
    fun sampleShare(state: ExplorationState, dial: DiscoveryDial): Double =
        dial.clamp(Distributions.beta(state.alpha, state.beta, random))

    /**
     * Familiar/discovery pattern for [slots] queue positions with the given share, spread evenly
     * (error diffusion) so discoveries never clump. The first slot is familiar unless share >= 0.5,
     * which keeps the start of a queue grounded.
     */
    fun planSlots(slots: Int, share: Double): List<SlotKind> {
        var acc = if (share >= 0.5) 0.5 else 0.0
        return List(slots) {
            acc += share
            if (acc >= 1.0 - 1e-9) {
                acc -= 1.0
                SlotKind.DISCOVERY
            } else {
                SlotKind.FAMILIAR
            }
        }
    }
}
