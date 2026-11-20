package app.turtleplayer.taste

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Weighted spherical k-means (cosine distance) with k-means++ seeding. Deterministic for a given
 * input order and [Random] seed.
 */
object SphericalKMeans {
    class Result(val centroids: List<FloatArray>, val assignments: IntArray)

    /** 4 to 8 clusters once there is enough data; fewer for a thin history (`sqrt(n / 2)`, capped at 8). */
    fun chooseK(points: Int): Int = if (points <= 0) 0 else sqrt(points / 2.0).toInt().coerceIn(1, 8)

    fun cluster(points: List<FloatArray>, weights: DoubleArray, k: Int, random: Random, iterations: Int = 25): Result {
        require(points.size == weights.size) { "weights must match points" }
        val n = points.size
        if (n == 0 || k <= 0) return Result(emptyList(), IntArray(n))
        val units = points.map { it.normalized() }
        val dim = units[0].size
        val centroids = seed(units, weights, minOf(k, n), random)
        val assign = IntArray(n) { -1 }
        for (iteration in 0 until iterations) {
            var changed = false
            for (i in 0 until n) {
                val best = nearest(units[i], centroids)
                if (best != assign[i]) {
                    assign[i] = best
                    changed = true
                }
            }
            // Recompute centroids as normalized weighted means; an emptied cluster keeps its old centroid.
            for (c in centroids.indices) {
                val acc = FloatArray(dim)
                var any = false
                for (i in 0 until n) if (assign[i] == c) {
                    acc.addScaled(units[i], weights[i].toFloat())
                    any = true
                }
                if (any && !acc.isZero()) centroids[c] = acc.normalizeInPlace()
            }
            if (!changed) break
        }
        // Drop empty clusters and re-index.
        val used = (0 until centroids.size).filter { c -> assign.any { it == c } }
        val remap = IntArray(centroids.size) { -1 }
        used.forEachIndexed { newIdx, old -> remap[old] = newIdx }
        return Result(used.map { centroids[it] }, IntArray(n) { remap[assign[it]] })
    }

    fun nearest(v: FloatArray, centroids: List<FloatArray>): Int {
        var best = 0
        var bestSim = Float.NEGATIVE_INFINITY
        for ((c, centroid) in centroids.withIndex()) {
            val s = v dot centroid
            if (s > bestSim) {
                bestSim = s
                best = c
            }
        }
        return best
    }

    /** k-means++: next seed drawn with probability proportional to weight x squared cosine distance. */
    private fun seed(units: List<FloatArray>, weights: DoubleArray, k: Int, random: Random): MutableList<FloatArray> {
        val n = units.size
        val chosen = ArrayList<FloatArray>(k)
        chosen += units[sample(DoubleArray(n) { weights[it].coerceAtLeast(0.0) }, random)].copyOf()
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        while (chosen.size < k) {
            val last = chosen.last()
            for (i in 0 until n) {
                val d = (1.0 - (units[i] dot last)).coerceAtLeast(0.0)
                if (d < dist[i]) dist[i] = d
            }
            val probs = DoubleArray(n) { weights[it].coerceAtLeast(0.0) * dist[it] * dist[it] }
            if (probs.sum() <= 1e-12) break // every point coincides with a seed already
            chosen += units[sample(probs, random)].copyOf()
        }
        return chosen
    }

    private fun sample(weights: DoubleArray, random: Random): Int {
        val total = weights.sum()
        if (total <= 0.0) return random.nextInt(weights.size)
        var r = random.nextDouble() * total
        for (i in weights.indices) {
            r -= weights[i]
            if (r <= 0) return i
        }
        return weights.lastIndex
    }
}
