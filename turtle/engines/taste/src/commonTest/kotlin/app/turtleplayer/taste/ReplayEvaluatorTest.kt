package app.turtleplayer.taste

import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.Surface
import app.turtleplayer.taste.eval.Recommender
import app.turtleplayer.taste.eval.ReplayEvaluator
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ReplayEvaluatorTest {
    private fun play(user: String, key: String, at: Long) = PlayEvent(
        track = TrackMeta(TrackKey("test:$user-$key"), "song $key", listOf("artist $user"), durationMs = 200_000),
        sessionId = user,
        position = 0,
        surface = Surface.AUTOPLAY,
        startReason = StartReason.AUTOPLAY,
        endReason = EndReason.COMPLETED,
        startedAtMs = at,
        msPlayed = 200_000,
        durationMs = 200_000,
    )

    /** One training play long before the holdout, then [held] finished tracks in the last days. */
    private fun history(user: String, held: Int): List<PlayEvent> =
        listOf(play(user, "train", 0)) + (0 until held).map { play(user, "h$it", 40 * DAY_MS + it * 1_000L) }

    @Test
    fun standardRecallDividesByTheWholeHoldoutWhileTheHistoricalMetricCapsAtK() = runTest {
        val users = mapOf("a" to history("a", 60), "b" to history("b", 4))
        // User a: 50 of 60 held-out tracks; user b: 2 of 4 plus two misses.
        val recommender = Recommender { userId, _, _, _ ->
            when (userId) {
                "a" -> (0 until 50).map { TrackKey("test:a-h$it") }
                else -> listOf(TrackKey("test:b-h0"), TrackKey("test:b-miss1"), TrackKey("test:b-h1"), TrackKey("test:b-miss2"))
            }
        }
        val metrics = ReplayEvaluator(holdoutDays = 30, k = 50, ndcgK = 10).evaluate(users, catalogSize = 100, recommender)

        assertEquals(2, metrics.users)
        assertEquals(0.75, metrics.recallAtK, 1e-9)
        assertEquals((50.0 / 60 + 0.5) / 2, metrics.standardRecallAtK, 1e-9)
        assertTrue(metrics.standardRecallAtK <= metrics.recallAtK)
        val line = metrics.toString()
        assertTrue(line.startsWith("recall@50=0.75 ndcg@10="), line)
        assertTrue(line.endsWith(" standardRecall@50=0.666"), line)
    }

    @Test
    fun bothRecallsAgreeWhenEveryHoldoutFitsInK() = runTest {
        val users = mapOf("b" to history("b", 4))
        val recommender = Recommender { _, _, _, _ -> listOf(TrackKey("test:b-h0"), TrackKey("test:b-h1"), TrackKey("test:b-h2")) }
        val metrics = ReplayEvaluator(holdoutDays = 30, k = 50, ndcgK = 10).evaluate(users, catalogSize = 10, recommender)

        assertEquals(0.75, metrics.recallAtK, 1e-9)
        assertEquals(metrics.recallAtK, metrics.standardRecallAtK, 1e-9)
    }
}
