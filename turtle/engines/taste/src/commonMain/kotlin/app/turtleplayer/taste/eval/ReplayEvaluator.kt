package app.turtleplayer.taste.eval

import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.taste.TasteEngine
import kotlin.math.ln

/** Anything that turns a listener's training history into a ranked list of tracks. */
fun interface Recommender {
    suspend fun recommend(userId: String, training: List<PlayEvent>, nowMs: Long, k: Int): List<TrackKey>
}

/** Adapts [TasteEngine]: one fresh engine per user over its training events, clock frozen at the cutoff. */
class TasteEngineRecommender(
    /** False to recommend only never-heard tracks (pair with [HoldoutTarget.NEW_ONLY]). */
    private val includeHeard: Boolean = true,
    private val factory: (events: PlayEventStore, clock: Clock) -> TasteEngine,
) : Recommender {
    override suspend fun recommend(userId: String, training: List<PlayEvent>, nowMs: Long, k: Int): List<TrackKey> {
        val store = InMemoryPlayEventStore()
        store.appendAll(training)
        val engine = factory(store, Clock { nowMs })
        return engine.recommendations(k, includeHeard).map { it.candidate.key }
    }
}

enum class HoldoutTarget {
    /** Every track finished in the holdout window, including re-listens. */
    ALL_FINISHED,
    /** Only finished tracks never heard in training: pure discovery. */
    NEW_ONLY,
}

data class EvalMetrics(
    /**
     * Hits in the top K divided by min(|held-out|, K), averaged over users. Historical capped recall
     * (the 2% gate). Label kept for comparison with earlier REPLAY lines.
     */
    val recallAtK: Double,
    /** Binary-relevance NDCG at [ndcgK], averaged over users. */
    val ndcg: Double,
    /** Mean self-information -log2(p) of recommended tracks, p = share of users who played it in training. */
    val novelty: Double,
    /** Distinct recommended tracks over catalog size. */
    val coverage: Double,
    val users: Int,
    val k: Int,
    val ndcgK: Int,
    /** Standard recall: hits in the top K divided by |held-out|, averaged over users. Never above [recallAtK]. */
    val standardRecallAtK: Double,
) {
    override fun toString(): String =
        "recall@$k=${fmt(recallAtK)} ndcg@$ndcgK=${fmt(ndcg)} novelty=${fmt(novelty)} coverage=${fmt(coverage)} users=$users standardRecall@$k=${fmt(standardRecallAtK)}"

    private fun fmt(d: Double): String = ((d * 1000).toLong() / 1000.0).toString()
}

/**
 * Offline replay: hide each user's last [holdoutDays] days, recommend from the rest, and measure
 * how well the hidden plays were predicted. This is the CI golden set: a regression over 2% on
 * recall or NDCG blocks the merge.
 */
class ReplayEvaluator(
    private val holdoutDays: Int = 30,
    private val k: Int = 50,
    private val ndcgK: Int = 10,
    private val target: HoldoutTarget = HoldoutTarget.ALL_FINISHED,
) {
    suspend fun evaluate(users: Map<String, List<PlayEvent>>, catalogSize: Int, recommender: Recommender): EvalMetrics {
        val splits = users.mapValues { (_, events) ->
            val sorted = events.sortedBy { it.startedAtMs }
            val cutoff = (sorted.lastOrNull()?.startedAtMs ?: 0L) - holdoutDays * DAY_MS
            val train = sorted.filter { it.startedAtMs < cutoff }
            val trainKeys = train.mapTo(HashSet()) { it.trackKey }
            val test = sorted.asSequence().filter { it.startedAtMs >= cutoff && it.isFinished && !it.isQuickSkip }
                .map { it.trackKey }
                .filter { target == HoldoutTarget.ALL_FINISHED || it !in trainKeys }
                .toSet()
            Triple(cutoff, train, test)
        }
        // Training-set item popularity across users, for novelty.
        val usersPerItem = HashMap<TrackKey, Int>()
        for ((_, split) in splits) for (key in split.second.mapTo(HashSet()) { it.trackKey }) usersPerItem[key] = (usersPerItem[key] ?: 0) + 1
        val userCount = splits.size

        var recall = 0.0
        var standardRecall = 0.0
        var ndcg = 0.0
        var novelty = 0.0
        var noveltyN = 0
        var evaluated = 0
        val recommended = HashSet<TrackKey>()
        for ((userId, split) in splits.entries.sortedBy { it.key }) {
            val (cutoff, train, test) = split
            if (test.isEmpty() || train.isEmpty()) continue
            val recs = recommender.recommend(userId, train, cutoff, k).distinct().take(k)
            evaluated++
            recommended += recs
            val hits = recs.count { it in test }
            recall += hits.toDouble() / minOf(test.size, k)
            standardRecall += hits.toDouble() / test.size
            var dcg = 0.0
            recs.take(ndcgK).forEachIndexed { i, key -> if (key in test) dcg += 1.0 / log2(i + 2.0) }
            var idcg = 0.0
            for (i in 0 until minOf(test.size, ndcgK)) idcg += 1.0 / log2(i + 2.0)
            ndcg += if (idcg > 0) dcg / idcg else 0.0
            for (key in recs) {
                val p = ((usersPerItem[key] ?: 0) + 1.0) / (userCount + 1.0)
                novelty += -log2(p)
                noveltyN++
            }
        }
        val n = evaluated.coerceAtLeast(1)
        return EvalMetrics(
            recallAtK = recall / n,
            ndcg = ndcg / n,
            novelty = if (noveltyN > 0) novelty / noveltyN else 0.0,
            coverage = if (catalogSize > 0) recommended.size.toDouble() / catalogSize else 0.0,
            users = evaluated,
            k = k,
            ndcgK = ndcgK,
            standardRecallAtK = standardRecall / n,
        )
    }

    private fun log2(x: Double) = ln(x) / ln(2.0)
}
