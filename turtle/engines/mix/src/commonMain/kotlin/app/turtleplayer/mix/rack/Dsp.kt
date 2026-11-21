package app.turtleplayer.mix.rack

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/*
 * Small DSP toolkit: RBJ cookbook biquads (Robert Bristow-Johnson's Audio EQ Cookbook), run in
 * transposed direct form II over interleaved blocks, plus helpers. Coefficients are stored as five
 * doubles [b0, b1, b2, a1, a2] (a0 normalised to 1) at an offset in a DoubleArray; filter state is two
 * doubles per channel.
 */
internal object Bq {
    private const val DENORMAL = 1e-20

    fun identity(c: DoubleArray, o: Int) {
        c[o] = 1.0; c[o + 1] = 0.0; c[o + 2] = 0.0; c[o + 3] = 0.0; c[o + 4] = 0.0
    }

    private fun set(c: DoubleArray, o: Int, b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) {
        val i = 1.0 / a0
        c[o] = b0 * i; c[o + 1] = b1 * i; c[o + 2] = b2 * i; c[o + 3] = a1 * i; c[o + 4] = a2 * i
    }

    private fun w0(fs: Double, f: Double): Double = 2.0 * PI * f.coerceIn(1.0, fs * 0.49) / fs

    fun lowPass(c: DoubleArray, o: Int, fs: Double, f: Double, q: Double) {
        val w = w0(fs, f); val cs = cos(w); val al = sin(w) / (2.0 * q)
        set(c, o, (1 - cs) / 2, 1 - cs, (1 - cs) / 2, 1 + al, -2 * cs, 1 - al)
    }

    fun highPass(c: DoubleArray, o: Int, fs: Double, f: Double, q: Double) {
        val w = w0(fs, f); val cs = cos(w); val al = sin(w) / (2.0 * q)
        set(c, o, (1 + cs) / 2, -(1 + cs), (1 + cs) / 2, 1 + al, -2 * cs, 1 - al)
    }

    /** Constant 0 dB peak gain band-pass. */
    fun bandPass(c: DoubleArray, o: Int, fs: Double, f: Double, q: Double) {
        val w = w0(fs, f); val cs = cos(w); val al = sin(w) / (2.0 * q)
        set(c, o, al, 0.0, -al, 1 + al, -2 * cs, 1 - al)
    }

    fun peaking(c: DoubleArray, o: Int, fs: Double, f: Double, q: Double, db: Double) {
        val a = 10.0.pow(db / 40.0)
        val w = w0(fs, f); val cs = cos(w); val al = sin(w) / (2.0 * q)
        set(c, o, 1 + al * a, -2 * cs, 1 - al * a, 1 + al / a, -2 * cs, 1 - al / a)
    }

    /** Shelf slope S = 1. */
    fun lowShelf(c: DoubleArray, o: Int, fs: Double, f: Double, db: Double) {
        val a = 10.0.pow(db / 40.0)
        val w = w0(fs, f); val cs = cos(w); val al = sin(w) / 2.0 * sqrt(2.0)
        val sa = 2.0 * sqrt(a) * al
        set(
            c, o,
            a * ((a + 1) - (a - 1) * cs + sa), 2 * a * ((a - 1) - (a + 1) * cs), a * ((a + 1) - (a - 1) * cs - sa),
            (a + 1) + (a - 1) * cs + sa, -2 * ((a - 1) + (a + 1) * cs), (a + 1) + (a - 1) * cs - sa,
        )
    }

    fun highShelf(c: DoubleArray, o: Int, fs: Double, f: Double, db: Double) {
        val a = 10.0.pow(db / 40.0)
        val w = w0(fs, f); val cs = cos(w); val al = sin(w) / 2.0 * sqrt(2.0)
        val sa = 2.0 * sqrt(a) * al
        set(
            c, o,
            a * ((a + 1) + (a - 1) * cs + sa), -2 * a * ((a - 1) + (a + 1) * cs), a * ((a + 1) + (a - 1) * cs - sa),
            (a + 1) - (a - 1) * cs + sa, 2 * ((a - 1) - (a + 1) * cs), (a + 1) - (a - 1) * cs - sa,
        )
    }

    /** Runs one biquad in place over [n] interleaved frames of [ch] channels. State at [so] + 2 * channel. */
    fun run(buf: DoubleArray, n: Int, ch: Int, c: DoubleArray, co: Int, s: DoubleArray, so: Int) {
        val b0 = c[co]; val b1 = c[co + 1]; val b2 = c[co + 2]; val a1 = c[co + 3]; val a2 = c[co + 4]
        for (k in 0 until ch) {
            var z1 = s[so + 2 * k]
            var z2 = s[so + 2 * k + 1]
            var i = k
            for (f in 0 until n) {
                val x = buf[i]
                val y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                buf[i] = y
                i += ch
            }
            if (abs(z1) < DENORMAL) z1 = 0.0
            if (abs(z2) < DENORMAL) z2 = 0.0
            s[so + 2 * k] = z1
            s[so + 2 * k + 1] = z2
        }
    }

    /** Gain at DC. */
    fun dcGain(c: DoubleArray, o: Int): Double {
        val den = 1.0 + c[o + 3] + c[o + 4]
        return if (abs(den) < 1e-12) 0.0 else (c[o] + c[o + 1] + c[o + 2]) / den
    }

    /** Sets one channel's state to the steady state for a constant input [x] (no click when a filter switches in). */
    fun primeDc(c: DoubleArray, co: Int, s: DoubleArray, si: Int, x: Double) {
        val y = dcGain(c, co) * x
        val z2 = c[co + 2] * x - c[co + 4] * y
        val z1 = c[co + 1] * x - c[co + 3] * y + z2
        s[si] = z1
        s[si + 1] = z2
    }
}

/** A mono biquad with its own state, for analysis and synthesis (not the per-channel hot path). */
internal class Bq1 {
    val c = DoubleArray(5).also { Bq.identity(it, 0) }
    private var z1 = 0.0
    private var z2 = 0.0

    fun step(x: Double): Double {
        val y = c[0] * x + z1
        z1 = c[1] * x - c[3] * y + z2
        z2 = c[2] * x - c[4] * y
        if (abs(z1) < 1e-25) z1 = 0.0
        if (abs(z2) < 1e-25) z2 = 0.0
        return y
    }

    fun clear() {
        z1 = 0.0; z2 = 0.0
    }
}

internal object Dsp {
    /** Soft knee above about -1 dBFS, then hard limit: overs are rounded off instead of wrapping or squaring. */
    fun toPcm16(v: Double): Short {
        var y = v
        val a = if (y < 0) -y else y
        if (a > KNEE) {
            val over = (a - KNEE) / (1.0 - KNEE)
            val s = KNEE + (1.0 - KNEE) * tanh(over)
            y = if (y < 0) -s else s
        }
        val i = (y * 32767.0).let { if (it >= 0) (it + 0.5).toInt() else (it - 0.5).toInt() }
        return when {
            i > 32767 -> 32767
            i < -32768 -> -32768
            else -> i
        }.toShort()
    }

    private const val KNEE = 0.891

    /** Fractional part in [0, 1). */
    fun frac(x: Double): Double {
        val f = x - kotlin.math.floor(x)
        return if (f >= 1.0) 0.0 else f
    }

    /** Wraps to [-0.5, 0.5). */
    fun wrapHalf(x: Double): Double = x - kotlin.math.floor(x + 0.5)

    /** 4-point, 3rd-order Hermite interpolation. */
    fun hermite(xm1: Double, x0: Double, x1: Double, x2: Double, t: Double): Double {
        val c1 = 0.5 * (x1 - xm1)
        val c2 = xm1 - 2.5 * x0 + 2.0 * x1 - 0.5 * x2
        val c3 = 0.5 * (x2 - xm1) + 1.5 * (x0 - x1)
        return ((c3 * t + c2) * t + c1) * t + x0
    }
}
