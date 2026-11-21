@file:OptIn(ExperimentalAtomicApi::class)

package app.turtleplayer.mix.rack

import app.turtleplayer.mix.SampleId
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.atomicArrayOfNulls
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** Every sampler sound rendered at one sample rate (mono, -1..1). */
internal class SampleBank(val rate: Int, val data: Array<FloatArray>)

/**
 * The synthesized sampler: banks rendered once per sample rate (shared, published atomically) and up
 * to [VOICES] overlapping voices. Voices and pending quantized triggers are touched only by the clock
 * owner deck's audio thread (one at a time), which mixes them into its output.
 */
internal class Sampler(private val allocations: AtomicInt) {
    private val banks = atomicArrayOfNulls<SampleBank>(BANKS)

    private val vId = IntArray(VOICES) { -1 }
    private val vPos = IntArray(VOICES)
    private val vRate = IntArray(VOICES)
    private val vStart = LongArray(VOICES)
    private var starts = 0L

    private val pId = IntArray(VOICES) { -1 }
    private val pBar = BooleanArray(VOICES)

    /** Bumped by reset() on the control thread; the host stops everything when it sees a new value. */
    @Volatile var clearGen = 0
    private var clearSeen = 0

    fun bankIfReady(rate: Int): SampleBank? {
        for (i in 0 until BANKS) {
            val b = banks.loadAt(i) ?: continue
            if (b.rate == rate) return b
        }
        return null
    }

    /** Renders (once) and returns the bank for [rate]. Allocates when it renders: call warmUp() early. */
    fun bank(rate: Int): SampleBank {
        bankIfReady(rate)?.let { return it }
        val data = Array(SampleId.entries.size) { SampleSynth.render(SampleId.entries[it], rate) }
        val b = SampleBank(rate, data)
        allocations.incrementAndFetch()
        for (i in 0 until BANKS) {
            if (banks.compareAndSetAt(i, null, b)) return b
        }
        banks.storeAt(BANKS - 1, b)
        return b
    }

    private fun checkClear() {
        val g = clearGen
        if (g != clearSeen) {
            clearSeen = g
            vId.fill(-1); pId.fill(-1)
        }
    }

    fun start(id: Int, rate: Int) {
        checkClear()
        if (id < 0 || id >= SampleId.entries.size) return
        bank(rate)
        var slot = -1
        for (i in 0 until VOICES) if (vId[i] < 0) { slot = i; break }
        if (slot < 0) {
            slot = 0
            for (i in 1 until VOICES) if (vStart[i] < vStart[slot]) slot = i
        }
        vId[slot] = id; vPos[slot] = 0; vRate[slot] = rate; vStart[slot] = ++starts
    }

    fun addPending(id: Int, bar: Boolean) {
        checkClear()
        for (i in 0 until VOICES) if (pId[i] < 0) {
            pId[i] = id; pBar[i] = bar; return
        }
    }

    fun hasPending(): Boolean {
        for (i in 0 until VOICES) if (pId[i] >= 0) return true
        return false
    }

    /** At a beat crossing: fires Beat-quantized triggers, and Bar ones on a downbeat. */
    fun firePending(downbeat: Boolean, rate: Int) {
        for (i in 0 until VOICES) {
            val id = pId[i]
            if (id < 0) continue
            if (!pBar[i] || downbeat) {
                pId[i] = -1
                start(id, rate)
            }
        }
    }

    fun active(): Boolean {
        checkClear()
        for (i in 0 until VOICES) if (vId[i] >= 0) return true
        return false
    }

    /** Adds the playing voices (mono) into out[0 until n]. */
    fun render(out: DoubleArray, n: Int, rate: Int) {
        val bank = bankIfReady(rate) ?: return
        for (v in 0 until VOICES) {
            val id = vId[v]
            if (id < 0) continue
            if (vRate[v] != rate) {
                vPos[v] = (vPos[v].toLong() * rate / max(1, vRate[v])).toInt()
                vRate[v] = rate
            }
            val d = bank.data[id]
            val pos = vPos[v]
            val m = min(n, d.size - pos)
            for (i in 0 until m) out[i] += d[pos + i].toDouble()
            if (m <= 0 || pos + m >= d.size) vId[v] = -1 else vPos[v] = pos + m
        }
    }

    companion object {
        const val VOICES = 4
        private const val BANKS = 4
    }
}

/** Offline synthesis of the sampler's sounds (no recordings, so no licences). */
internal object SampleSynth {
    fun render(id: SampleId, rate: Int): FloatArray {
        val fs = rate.toDouble()
        val out = when (id) {
            SampleId.AirHorn -> airHorn(fs)
            SampleId.Siren -> siren(fs)
            SampleId.Riser -> riser(fs)
            SampleId.Impact -> impact(fs)
            SampleId.Laser -> laser(fs)
        }
        normalize(out, PEAK.getValue(id))
        return FloatArray(out.size) { out[it].toFloat() }
    }

    private val PEAK = mapOf(
        SampleId.AirHorn to 0.9, SampleId.Siren to 0.7, SampleId.Riser to 0.7,
        SampleId.Impact to 0.9, SampleId.Laser to 0.6,
    )

    private fun normalize(x: DoubleArray, peak: Double) {
        var m = 0.0
        for (v in x) m = max(m, abs(v))
        if (m < 1e-9) return
        val g = peak / m
        for (i in x.indices) x[i] *= g
    }

    /** PolyBLEP residual for band-limited saw/square edges. */
    private fun blep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    private fun saw(t: Double, dt: Double) = 2.0 * t - 1.0 - blep(t, dt)

    private fun square(t: Double, dt: Double): Double {
        var t2 = t + 0.5
        if (t2 >= 1.0) t2 -= 1.0
        return (if (t < 0.5) 1.0 else -1.0) + blep(t, dt) - blep(t2, dt)
    }

    private class Noise(seed: Int) {
        private var s = seed.toLong() or 1L
        fun next(): Double {
            s = s xor (s shl 13); s = s xor (s ushr 7); s = s xor (s shl 17)
            return ((s ushr 11).toDouble() / (1L shl 53).toDouble()) * 2.0 - 1.0
        }
    }

    /** Reggae sound-system horn: three blasts of a detuned saw chord bending up into pitch. */
    private fun airHorn(fs: Double): DoubleArray {
        val blasts = doubleArrayOf(0.0, 0.15, 0.21, 0.15, 0.42, 0.60)
        val total = 1.08
        val n = (total * fs).toInt()
        val out = DoubleArray(n)
        val root = 415.0
        val freqs = doubleArrayOf(root, root * 2.0.pow(4.0 / 12), root * 2.0.pow(7.0 / 12))
        val detune = doubleArrayOf(-0.08, 0.07)
        val phases = DoubleArray(6) { (it * 0.137) % 1.0 }
        for (i in 0 until n) {
            val t = i / fs
            var env = 0.0
            var since = 0.0
            var b = 0
            while (b < blasts.size) {
                val st = blasts[b]; val len = blasts[b + 1]
                if (t >= st && t < st + len + 0.02) {
                    since = t - st
                    val a = min(1.0, since / 0.005)
                    val r = if (since > len) max(0.0, 1.0 - (since - len) / 0.02) else 1.0
                    env = a * r
                    break
                }
                b += 2
            }
            if (env <= 0.0) continue
            val bendU = (1.0 - min(1.0, since / 0.04))
            val bend = 2.0.pow(-3.0 / 12.0 * bendU * bendU)
            var s = 0.0
            for (v in 0 until 3) for (d in 0 until 2) {
                val k = v * 2 + d
                val f = freqs[v] * bend * 2.0.pow(detune[d] / 12.0)
                val dt = f / fs
                phases[k] += dt
                if (phases[k] >= 1.0) phases[k] -= 1.0
                s += saw(phases[k], dt)
            }
            out[i] = s * env / 6.0
        }
        val hp = Bq1().also { Bq.highPass(it.c, 0, fs, 450.0, 0.707) }
        val pk = Bq1().also { Bq.peaking(it.c, 0, fs, 3000.0, 1.0, 6.0) }
        val lp = Bq1().also { Bq.lowPass(it.c, 0, fs, 4800.0, 0.707) }
        for (i in 0 until n) out[i] = tanh(2.5 * lp.step(pk.step(hp.step(out[i]))))
        return out
    }

    /** Dub siren: a square swept 600..1200 Hz at 4 Hz for 1.6 s, with a darkening echo tail baked in. */
    private fun siren(fs: Double): DoubleArray {
        val dur = 1.6
        val n = (3.0 * fs).toInt()
        val dry = DoubleArray(n)
        var ph = 0.0
        val lp = Bq1().also { Bq.lowPass(it.c, 0, fs, 3500.0, 0.707) }
        for (i in 0 until (dur * fs).toInt()) {
            val t = i / fs
            val lfo = sin(2 * PI * 4.0 * t)
            val f = 600.0 * 2.0.pow(0.5 + 0.5 * lfo)
            val dt = f / fs
            ph += dt
            if (ph >= 1.0) ph -= 1.0
            val env = min(1.0, t / 0.01) * min(1.0, (dur - t) / 0.03)
            dry[i] = lp.step(square(ph, dt)) * env * 0.5
        }
        // Echo: 300 ms, feedback 0.45, each repeat darker.
        val d = (0.3 * fs).toInt()
        val out = DoubleArray(n)
        var z = 0.0
        val a = exp(-2 * PI * 2500.0 / fs)
        for (i in 0 until n) {
            val fb = if (i >= d) out[i - d] else 0.0
            z = (1 - a) * fb + a * z
            out[i] = dry[i] + 0.45 * z
        }
        return out
    }

    /** 4 s noise riser: band-pass sweeping 300 Hz to 9 kHz, rising level, a slowly rising sine underneath. */
    private fun riser(fs: Double): DoubleArray {
        val dur = 4.0
        val n = (dur * fs).toInt()
        val out = DoubleArray(n)
        val noise = Noise(0x51ED)
        val bp = Bq1()
        var sph = 0.0
        for (i in 0 until n) {
            val u = i.toDouble() / n
            if (i % 64 == 0) Bq.bandPass(bp.c, 0, fs, 300.0 * 30.0.pow(u), 1.8)
            val level = 0.04 + 0.96 * u.pow(2.2)
            val f = 110.0 * 4.0.pow(u)
            sph += 2 * PI * f / fs
            if (sph > 2 * PI) sph -= 2 * PI
            val tail = min(1.0, (n - i) / (0.005 * fs))
            out[i] = (bp.step(noise.next()) * 2.2 * level + 0.25 * u * sin(sph)) * tail
        }
        return out
    }

    /** Impact: sub drop 60 to 35 Hz over a 1.2 s decay, an 80 ms noise burst and a low thump. */
    private fun impact(fs: Double): DoubleArray {
        val n = (1.4 * fs).toInt()
        val out = DoubleArray(n)
        val noise = Noise(0x1A7)
        val lp = Bq1().also { Bq.lowPass(it.c, 0, fs, 6000.0, 0.707) }
        var ps = 0.0
        var pt = 0.0
        for (i in 0 until n) {
            val t = i / fs
            val fSub = 35.0 + 25.0 * exp(-t / 0.15)
            ps += 2 * PI * fSub / fs
            val sub = sin(ps) * exp(-3.0 * t / 1.2)
            val fTh = 50.0 + 70.0 * exp(-t / 0.02)
            pt += 2 * PI * fTh / fs
            val thump = sin(pt) * exp(-t / 0.06) * 0.8
            val nb = if (t < 0.08) lp.step(noise.next()) * exp(-t / 0.02) * 0.6 else 0.0
            val atk = min(1.0, t / 0.001)
            val end = min(1.0, (n - i) / (0.01 * fs))
            out[i] = tanh(1.5 * (sub + thump + nb)) * atk * end
        }
        return out
    }

    /** Laser: three quick 250 ms sine/saw chirps falling 2.5 kHz to 200 Hz. */
    private fun laser(fs: Double): DoubleArray {
        val n = (0.82 * fs).toInt()
        val out = DoubleArray(n)
        val starts = doubleArrayOf(0.0, 0.27, 0.54)
        val amps = doubleArrayOf(1.0, 0.75, 0.55)
        for (c in 0 until 3) {
            val s0 = (starts[c] * fs).toInt()
            val len = (0.25 * fs).toInt()
            var ph = 0.0
            for (j in 0 until len) {
                val i = s0 + j
                if (i >= n) break
                val u = j.toDouble() / len
                val f = 2500.0 * (200.0 / 2500.0).pow(u)
                val dt = f / fs
                ph += dt
                ph -= floor(ph)
                val env = min(1.0, j / (0.002 * fs)) * (1.0 - u).pow(0.6)
                out[i] += (0.6 * sin(2 * PI * ph) + 0.4 * saw(ph, dt)) * env * amps[c]
            }
        }
        return out
    }
}
