package app.turtleplayer.taste

import app.turtleplayer.core.TrackMeta
import app.turtleplayer.taste.eval.EvalMetrics
import app.turtleplayer.taste.eval.HoldoutTarget
import app.turtleplayer.taste.eval.Recommender
import app.turtleplayer.taste.eval.ReplayEvaluator
import app.turtleplayer.taste.eval.TasteEngineRecommender
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Offline replay on the synthetic world: the seed of the CI gate ("a regression over 2% blocks
 * the merge"). Golden values are the engine's scores when the gate was set; lower them only with a
 * written reason in the PR, raise them when the engine improves.
 */
class OfflineReplayTest {
    private val world = SyntheticWorld()
    private val users = (0 until 30).map(world::user).associate { it.id to world.history(it) }

    private fun engine(includeHeard: Boolean) =
        TasteEngineRecommender(includeHeard = includeHeard) { events, clock -> world.engine(events, clock) }

    private val byPopularity = world.tracks.sortedWith(compareByDescending<TrackMeta> { it.popularity }.thenBy { it.key.value })

    private fun popularity(includeHeard: Boolean) = Recommender { _, training, _, k ->
        val heard = training.mapTo(HashSet()) { it.trackKey }
        byPopularity.filter { includeHeard || it.key !in heard }.take(k).map { it.key }
    }

    private fun random(includeHeard: Boolean) = Recommender { userId, training, _, k ->
        val heard = training.mapTo(HashSet()) { it.trackKey }
        world.tracks.filter { includeHeard || it.key !in heard }.shuffled(Random(userId.hashCode())).take(k).map { it.key }
    }

    private suspend fun evaluate(r: Recommender, target: HoldoutTarget): EvalMetrics =
        ReplayEvaluator(holdoutDays = 30, k = 50, ndcgK = 10, target = target).evaluate(users, world.tracks.size, r)

    @Test
    fun engineClearlyBeatsPopularityAndRandom() = runTest {
        val ours = evaluate(engine(true), HoldoutTarget.ALL_FINISHED)
        val pop = evaluate(popularity(true), HoldoutTarget.ALL_FINISHED)
        val rnd = evaluate(random(true), HoldoutTarget.ALL_FINISHED)
        println("REPLAY all-finished engine     $ours")
        println("REPLAY all-finished popularity $pop")
        println("REPLAY all-finished random     $rnd")

        assertBeats(ours, pop, "popularity")
        assertBeats(ours, rnd, "random")
        assertNoRegression(ours, GOLDEN_RECALL_ALL, GOLDEN_NDCG_ALL)
    }

    @Test
    fun discoveryOfNeverHeardTracksBeatsBaselines() = runTest {
        val ours = evaluate(engine(false), HoldoutTarget.NEW_ONLY)
        val pop = evaluate(popularity(false), HoldoutTarget.NEW_ONLY)
        val rnd = evaluate(random(false), HoldoutTarget.NEW_ONLY)
        println("REPLAY new-only engine     $ours")
        println("REPLAY new-only popularity $pop")
        println("REPLAY new-only random     $rnd")

        assertBeats(ours, pop, "popularity")
        assertBeats(ours, rnd, "random")
        assertNoRegression(ours, GOLDEN_RECALL_NEW, GOLDEN_NDCG_NEW)
    }

    @Test
    fun replayIsDeterministic() = runTest {
        assertEquals(evaluate(engine(true), HoldoutTarget.ALL_FINISHED), evaluate(engine(true), HoldoutTarget.ALL_FINISHED))
    }

    private fun assertBeats(ours: EvalMetrics, baseline: EvalMetrics, name: String) {
        assertTrue(ours.recallAtK > 1.5 * baseline.recallAtK, "recall@50: engine ${ours.recallAtK} vs $name ${baseline.recallAtK}")
        assertTrue(ours.ndcg > 1.5 * baseline.ndcg, "ndcg@10: engine ${ours.ndcg} vs $name ${baseline.ndcg}")
    }

    /** The gate: no metric more than 2% below its golden value. */
    private fun assertNoRegression(ours: EvalMetrics, goldenRecall: Double, goldenNdcg: Double) {
        assertTrue(ours.recallAtK >= goldenRecall * 0.98, "recall@50 regressed: ${ours.recallAtK} < golden $goldenRecall")
        assertTrue(ours.ndcg >= goldenNdcg * 0.98, "ndcg@10 regressed: ${ours.ndcg} < golden $goldenNdcg")
    }

    private companion object {
        const val GOLDEN_RECALL_ALL = 0.587
        const val GOLDEN_NDCG_ALL = 0.868
        const val GOLDEN_RECALL_NEW = 0.960
        const val GOLDEN_NDCG_NEW = 0.641
    }
}
