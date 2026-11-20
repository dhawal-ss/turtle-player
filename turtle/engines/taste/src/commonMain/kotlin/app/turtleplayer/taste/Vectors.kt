package app.turtleplayer.taste

import kotlin.math.sqrt

/** Dimension of every taste vector: co-listening, sound, text and the combined track vector. */
const val EMBEDDING_DIM = 64

// Small, allocation-conscious vector math over FloatArray. Accumulation runs in Double so long sums
// (decaying averages over thousands of plays) do not drift.

infix fun FloatArray.dot(other: FloatArray): Float {
    require(size == other.size) { "Dimension mismatch: $size vs ${other.size}" }
    var s = 0.0
    for (i in indices) s += this[i].toDouble() * other[i].toDouble()
    return s.toFloat()
}

fun FloatArray.norm(): Float {
    var s = 0.0
    for (v in this) s += v.toDouble() * v.toDouble()
    return sqrt(s).toFloat()
}

fun FloatArray.isZero(): Boolean = all { it == 0f }

fun FloatArray.isFiniteVector(): Boolean = all { it.isFinite() }

/** Scales in place to unit length. A zero vector stays zero. Returns `this`. */
fun FloatArray.normalizeInPlace(): FloatArray {
    val n = norm()
    if (n > 0f && n.isFinite()) for (i in indices) this[i] /= n
    return this
}

/** Unit-length copy. A zero vector yields a zero copy. */
fun FloatArray.normalized(): FloatArray = copyOf().normalizeInPlace()

/** Cosine similarity in [-1, 1]; 0 when either vector is zero. */
fun FloatArray.cosine(other: FloatArray): Float {
    require(size == other.size) { "Dimension mismatch: $size vs ${other.size}" }
    var d = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in indices) {
        val a = this[i].toDouble()
        val b = other[i].toDouble()
        d += a * b
        na += a * a
        nb += b * b
    }
    if (na == 0.0 || nb == 0.0) return 0f
    return (d / (sqrt(na) * sqrt(nb))).toFloat().coerceIn(-1f, 1f)
}

/** `this += scale * src`, in place. Returns `this`. */
fun FloatArray.addScaled(src: FloatArray, scale: Float): FloatArray {
    require(size == src.size) { "Dimension mismatch: $size vs ${src.size}" }
    for (i in indices) this[i] += scale * src[i]
    return this
}

fun FloatArray.scaleInPlace(scale: Float): FloatArray {
    for (i in indices) this[i] *= scale
    return this
}

/**
 * Weighted mean of [vectors] (each normalized first so loud vectors do not dominate), returned
 * normalized. Null when there is nothing with positive weight.
 */
fun weightedMeanDirection(vectors: List<FloatArray>, weights: List<Double>? = null, dim: Int = EMBEDDING_DIM): FloatArray? {
    require(weights == null || weights.size == vectors.size) { "weights must match vectors" }
    val acc = DoubleArray(dim)
    var total = 0.0
    for ((i, v) in vectors.withIndex()) {
        val w = weights?.get(i) ?: 1.0
        if (w <= 0.0) continue
        val n = v.norm()
        if (n == 0f || !n.isFinite()) continue
        for (j in 0 until dim) acc[j] += w * v[j] / n
        total += w
    }
    if (total == 0.0) return null
    val out = FloatArray(dim) { (acc[it] / total).toFloat() }
    return if (out.isZero()) null else out.normalizeInPlace()
}

/**
 * Exponentially decaying sum of vectors: `sum = decay * sum + weight * v` on each [add]. Used by
 * the session model; the direction of [sum] is the decaying average.
 */
class DecayingVectorSum(val dim: Int = EMBEDDING_DIM) {
    val sum = FloatArray(dim)
    var mass = 0.0
        private set

    fun decay(factor: Float) {
        sum.scaleInPlace(factor)
        mass *= factor
    }

    fun add(v: FloatArray, weight: Float) {
        val n = v.norm()
        if (n == 0f) return
        sum.addScaled(v, weight / n)
        mass += weight
    }

    fun set(v: FloatArray?, mass: Double) {
        sum.fill(0f)
        this.mass = 0.0
        if (v != null && mass > 0) {
            sum.addScaled(v.normalized(), mass.toFloat())
            this.mass = mass
        }
    }

    /** Replaces the raw (unnormalized) sum and its mass. */
    fun setRaw(sum: FloatArray, mass: Double) {
        require(sum.size == dim)
        sum.copyInto(this.sum)
        this.mass = mass
    }

    fun clear() = set(null, 0.0)
}
