package app.turtleplayer.taste

import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.StartReason
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExplorationTest {
    @Test
    fun gammaAndBetaSamplersHaveTheRightMoments() {
        val r = Random(5)
        for (shape in listOf(0.5, 1.0, 2.5, 9.0)) {
            val xs = List(20_000) { Distributions.gamma(shape, r) }
            val mean = xs.average()
            val variance = xs.sumOf { (it - mean) * (it - mean) } / xs.size
            assertTrue(abs(mean - shape) < 0.05 * shape + 0.02, "gamma($shape) mean $mean")
            assertTrue(abs(variance - shape) < 0.1 * shape + 0.03, "gamma($shape) variance $variance")
            assertTrue(xs.all { it > 0 })
        }
        val betas = List(20_000) { Distributions.beta(2.0, 6.0, r) }
        assertEquals(0.25, betas.average(), 0.01)
        assertTrue(betas.all { it in 0.0..1.0 })
    }

    @Test
    fun samplingIsDeterministicForASeed() {
        val a = List(10) { ExplorationPolicy(Random(9)).sampleShare(ExplorationState(), DiscoveryDial.BALANCED) }
        val b = List(10) { ExplorationPolicy(Random(9)).sampleShare(ExplorationState(), DiscoveryDial.BALANCED) }
        assertEquals(a, b)
    }

    private fun meanShare(policy: ExplorationPolicy, state: ExplorationState, dial: DiscoveryDial) =
        List(4000) { policy.sampleShare(state, dial) }.average()

    @Test
    fun shareRisesOnSuccessesFallsOnQuickSkipsAndStaysWithinTheDial() {
        val policy = ExplorationPolicy(Random(1))
        val dial = DiscoveryDial.BALANCED
        val prior = ExplorationState()
        val priorShare = meanShare(policy, prior, dial)

        var good = prior
        repeat(30) { good = policy.update(good, DiscoveryOutcome.SUCCESS) }
        var bad = prior
        repeat(30) { bad = policy.update(bad, DiscoveryOutcome.FAILURE) }

        assertTrue(good.mean > prior.mean && bad.mean < prior.mean)
        val goodShare = meanShare(policy, good, dial)
        val badShare = meanShare(policy, bad, dial)
        assertTrue(goodShare > priorShare + 0.1, "successes: $goodShare vs prior $priorShare")
        assertTrue(badShare < priorShare - 0.05, "quick skips: $badShare vs prior $priorShare")

        // Clamped by the dial no matter how extreme the posterior gets.
        repeat(500) { good = policy.update(good, DiscoveryOutcome.SUCCESS) }
        repeat(500) { bad = policy.update(bad, DiscoveryOutcome.FAILURE) }
        repeat(2000) {
            assertTrue(policy.sampleShare(good, dial) in dial.minShare..dial.maxShare)
            assertTrue(policy.sampleShare(bad, dial) in dial.minShare..dial.maxShare)
        }
        assertEquals(dial.maxShare, meanShare(policy, good, dial), 0.01)
        assertEquals(dial.minShare, meanShare(policy, bad, dial), 0.01)
        assertTrue(good.alpha + good.beta <= 200.0 + 1e-9, "evidence saturates so the policy stays responsive")
    }

    @Test
    fun outcomesFromPlayEvents() {
        val policy = ExplorationPolicy(Random(1))
        val t = SyntheticWorld().tracks[0]
        assertEquals(DiscoveryOutcome.SUCCESS, policy.outcomeOf(play(t, 0)))
        assertEquals(DiscoveryOutcome.SUCCESS, policy.outcomeOf(play(t, 0, EndReason.SKIPPED, msPlayed = 90_000, liked = true)))
        assertEquals(DiscoveryOutcome.FAILURE, policy.outcomeOf(play(t, 0, EndReason.SKIPPED, msPlayed = 5_000)))
        assertEquals(DiscoveryOutcome.NEUTRAL, policy.outcomeOf(play(t, 0, EndReason.SKIPPED, msPlayed = 90_000)))
        // Only a play the pick itself started is an outcome: replays, steps back and imports are not.
        assertEquals(DiscoveryOutcome.NEUTRAL, policy.outcomeOf(play(t, 0, replayed = true)))
        assertEquals(DiscoveryOutcome.NEUTRAL, policy.outcomeOf(play(t, 0, EndReason.SKIPPED, msPlayed = 5_000, replayed = true)))
        assertEquals(DiscoveryOutcome.NEUTRAL, policy.outcomeOf(play(t, 0, EndReason.SKIPPED, msPlayed = 5_000, startReason = StartReason.USER_PREVIOUS)))
        assertEquals(DiscoveryOutcome.NEUTRAL, policy.outcomeOf(play(t, 0, source = EventSource.SPOTIFY_IMPORT)))
        // An explicit Less like this stays a failure, even on a replay.
        assertEquals(DiscoveryOutcome.FAILURE, policy.outcomeOf(play(t, 0, EndReason.SKIPPED, msPlayed = 5_000, replayed = true, lessLikeThis = true)))
    }

    @Test
    fun slotPlanSpreadsDiscoveriesEvenly() {
        val policy = ExplorationPolicy(Random(1))
        val plan = policy.planSlots(20, 0.3)
        assertEquals(6, plan.count { it == SlotKind.DISCOVERY })
        assertEquals(SlotKind.FAMILIAR, plan.first())
        assertTrue(plan.zipWithNext().none { (a, b) -> a == SlotKind.DISCOVERY && b == SlotKind.DISCOVERY })
        assertTrue(policy.planSlots(10, 0.0).all { it == SlotKind.FAMILIAR })
        assertTrue(policy.planSlots(10, 1.0).all { it == SlotKind.DISCOVERY })
    }

    @Test
    fun dialPositionsInterpolateBetweenPresets() {
        assertEquals(DiscoveryDial.FAMILIAR, DiscoveryDial.fromPosition(0.0))
        assertEquals(DiscoveryDial.BALANCED, DiscoveryDial.fromPosition(0.5))
        assertEquals(DiscoveryDial.ADVENTUROUS, DiscoveryDial.fromPosition(1.0))
    }
}
