package app.turtleplayer.mix.rack

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tempo, beat phase, downbeat and energy from mono PCM, all preallocated and allocation-free per sample.
 *
 * Onset strength: three bands (low < 150 Hz, mid around 1 kHz, high > 4 kHz) are squared and summed
 * per hop (~86 Hz frame rate; the low band over two hops so a 50 Hz note does not ripple), log
 * compressed, and the rectified rise of each band is summed with the low band weighted most (a
 * multi-band spectral flux). Every 1.5 s the last 10 s of that envelope, with its local mean removed,
 * is autocorrelated; the beat lag is the strongest peak in 70..180 BPM under a log-normal prior around
 * 120, octave-corrected toward 85..160 when the other octave is supported, then refined on the peak
 * near four beats (four times the resolution) with parabolic interpolation. Readings pass through
 * hysteresis: close readings are averaged, octave flips are ignored, and a different tempo has to be
 * read twice in a row before it replaces the current one.
 *
 * Phase: a comb at the tempo over the last 6 s of onsets (recent beats weigh more) picks the beat
 * offset; downbeat: among the four beats of the bar, the one with the most low-band onset (kick) and
 * the least mid-band onset (snare) over the last 4 bars.
 */
internal class BeatTracker(val sampleRate: Int) {
    val hop: Int = max(32, (sampleRate / 86.0).roundToInt())
    val frameRate: Double = sampleRate.toDouble() / hop

    private val nMax = (10.0 * frameRate).roundToInt()
    private val minL = floor(60.0 * frameRate / 180.0).toInt()
    private val maxL = ceil(60.0 * frameRate / 70.0).toInt()
    private val lCap = 4 * maxL + 8
    private val minHops = (3.0 * frameRate).roundToInt()
    private val updateHops = (1.5 * frameRate).roundToInt()
    private val phaseHops = (6.0 * frameRate).roundToInt()

    private val onset = FloatArray(RING)
    private val lowOn = FloatArray(RING)
    private val midOn = FloatArray(RING)
    private val e = DoubleArray(nMax)
    private val h = DoubleArray(nMax)
    private val prefix = DoubleArray(nMax + 1)
    private val r = DoubleArray(lCap + 2)
    private val comb = DoubleArray(4 * (maxL + 2) + 8)
    internal val bins = DoubleArray(4)

    private val lp = Bq1().also { Bq.lowPass(it.c, 0, sampleRate.toDouble(), 150.0, 0.707) }
    private val bp = Bq1().also { Bq.bandPass(it.c, 0, sampleRate.toDouble(), 1000.0, 0.5) }
    private val hp = Bq1().also { Bq.highPass(it.c, 0, sampleRate.toDouble(), 4000.0, 0.707) }

    private var accL = 0.0
    private var accM = 0.0
    private var accH = 0.0
    private var accA = 0.0
    private var prevAccL = 0.0
    private var count = 0
    private var pL = 0.0
    private var pM = 0.0
    private var pH = 0.0

    /** Completed hops in the onset history (since the last reset or discontinuity). */
    private var hops = 0L
    private var lastAnalyzeHop = 0L

    // ---- results ----
    var framesAnalyzed = 0L; private set

    /** Smoothed tempo, 0 when none yet. */
    var bpm = 0.0; private set
    var confidence = 0.0; private set

    /** Absolute frame (since reset) of a recent beat, NaN if unknown. */
    var beatRefFrame = Double.NaN; private set

    /** The beat at [beatRefFrame] is this many beats after a downbeat (0..3). */
    var barIndexAtRef = 0; private set
    var downbeatConfidence = 0.0; private set
    var updates = 0; private set

    private var candBpm = 0.0
    private var candCount = 0

    /** Absolute frame index of hop 0 of the current onset history. */
    private var historyStartFrame = 0L

    // ---- energy ----
    private val aSlow = 1.0 - exp(-1.0 / (4.0 * frameRate))
    private val aShort = 1.0 - exp(-1.0 / (0.4 * frameRate))
    private val aSec = 1.0 - exp(-1.0 / frameRate)
    private var statsInit = false
    private var msA = 0.0
    private var msL = 0.0
    private var msH = 0.0
    private var msShort = 0.0
    private var oMean = 0.0
    private var peakRate = 0.0
    private var lastPeakHop = -100L
    private var energySmooth = 0.0
    var energy = 0.0; private set

    /** Energy ingredients (diagnostics). */
    var featLoud = 0.0; private set
    var featLow = 0.0; private set
    var featBright = 0.0; private set
    var featDensity = 0.0; private set
    var loudnessDb = -90.0; private set

    val measured: Boolean get() = confidence >= MEASURED_CONFIDENCE && framesAnalyzed >= 6L * sampleRate

    fun reset() {
        framesAnalyzed = 0
        discontinuity()
        bpm = 0.0; confidence = 0.0; beatRefFrame = Double.NaN; barIndexAtRef = 0; downbeatConfidence = 0.0
        candBpm = 0.0; candCount = 0
        statsInit = false; msA = 0.0; msL = 0.0; msH = 0.0; msShort = 0.0; oMean = 0.0; peakRate = 0.0
        energySmooth = 0.0; energy = 0.0; loudnessDb = -90.0
        lp.clear(); bp.clear(); hp.clear()
        updates++
    }

    /** The audio jumped (seek, resume): forget the onset history but keep the tempo reading. */
    fun discontinuity() {
        onset.fill(0f); lowOn.fill(0f); midOn.fill(0f)
        hops = 0; lastAnalyzeHop = 0; count = 0
        accL = 0.0; accM = 0.0; accH = 0.0; accA = 0.0; prevAccL = 0.0
        historyStartFrame = framesAnalyzed
        lastPeakHop = -100
    }

    /** One mono sample in -1..1. */
    fun feed(x: Double) {
        val l = lp.step(x)
        val m = bp.step(x)
        val hh = hp.step(x)
        accL += l * l
        accM += m * m
        accH += hh * hh
        accA += x * x
        framesAnalyzed++
        if (++count >= hop) completeHop()
    }

    private fun completeHop() {
        val inv = 1.0 / hop
        val eL = (accL + prevAccL) * 0.5 * inv
        val eM = accM * inv
        val eH = accH * inv
        val eA = accA * inv
        prevAccL = accL
        accL = 0.0; accM = 0.0; accH = 0.0; accA = 0.0; count = 0

        val cL = ln(1.0 + COMPRESS * eL)
        val cM = ln(1.0 + COMPRESS * eM)
        val cH = ln(1.0 + COMPRESS * eH)
        val first = hops == 0L
        val dL = if (first) 0.0 else max(0.0, cL - pL)
        val dM = if (first) 0.0 else max(0.0, cM - pM)
        val dH = if (first) 0.0 else max(0.0, cH - pH)
        pL = cL; pM = cM; pH = cH
        val o = W_LOW * dL + W_MID * dM + W_HIGH * dH
        val idx = (hops and MASK).toInt()
        onset[idx] = o.toFloat()
        lowOn[idx] = dL.toFloat()
        midOn[idx] = dM.toFloat()
        stats(eA, eL, eH, o)
        hops++
        if (hops >= minHops && hops - lastAnalyzeHop >= updateHops) {
            lastAnalyzeHop = hops
            analyze()
        }
    }

    private fun stats(eA: Double, eL: Double, eH: Double, o: Double) {
        if (!statsInit) {
            statsInit = true
            msA = eA; msL = eL; msH = eH; msShort = eA; oMean = o
        } else {
            msA += (eA - msA) * aSlow
            msL += (eL - msL) * aSlow
            msH += (eH - msH) * aSlow
            msShort += (eA - msShort) * aShort
        }
        // Onset peaks for density: the previous hop is a peak above an adaptive threshold.
        var impulse = 0.0
        if (hops >= 2) {
            val o1 = onset[((hops - 1) and MASK).toInt()].toDouble()
            val o2 = onset[((hops - 2) and MASK).toInt()].toDouble()
            if (o1 > o2 && o1 >= o && o1 > 1.5 * oMean + 0.3 && hops - lastPeakHop >= 4) {
                lastPeakHop = hops
                impulse = frameRate
            }
        }
        oMean += (o - oMean) * aSec
        peakRate += (impulse - peakRate) * aSlow

        loudnessDb = (10.0 * log10(msShort + 1e-12)).coerceAtLeast(-90.0)
        val dbSlow = 10.0 * log10(msA + 1e-12)
        val loud = ((dbSlow + 32.0) / 26.0).coerceIn(0.0, 1.0)
        val lowShare = msL / (msA + 1e-12)
        val lowN = ((lowShare - 0.05) / 0.5).coerceIn(0.0, 1.0)
        val bright = msH / (msA + 1e-12)
        val brightN = (bright / 0.06).coerceIn(0.0, 1.0)
        val densN = (peakRate / 6.0).coerceIn(0.0, 1.0)
        featLoud = loud; featLow = lowShare; featBright = bright; featDensity = peakRate
        val raw = if (dbSlow < -60.0) 0.0 else 0.08 + 0.5 * loud + 0.12 * lowN + 0.1 * brightN + 0.2 * densN
        energySmooth += (raw - energySmooth) * (if (updates == 0 && framesAnalyzed < 2L * sampleRate) aShort else aSlow)
        energy = energySmooth.coerceIn(0.0, 1.0)
    }

    private fun bpmOfLag(l: Double) = 60.0 * frameRate / l

    private fun analyze() {
        val n = min(hops, nMax.toLong()).toInt()
        val start = hops - n
        for (i in 0 until n) e[i] = onset[((start + i) and MASK).toInt()].toDouble()

        // Remove the local mean (+-0.2 s) and rectify: keeps the onsets, drops slow loudness swells.
        prefix[0] = 0.0
        for (i in 0 until n) prefix[i + 1] = prefix[i] + e[i]
        val w = max(2, (0.2 * frameRate).roundToInt())
        var mean = 0.0
        for (i in 0 until n) {
            val lo = max(0, i - w)
            val hi = min(n - 1, i + w)
            val lm = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            val v = e[i] - lm
            h[i] = if (v > 0) v else 0.0
            mean += h[i]
        }
        mean /= n
        for (i in 0 until n) h[i] -= mean

        val lc = min(lCap, (n * 2) / 3)
        if (lc < maxL + 2) return
        for (l in 0..lc) {
            var s = 0.0
            val m = n - l
            for (i in 0 until m) s += h[i] * h[i + l]
            r[l] = s / m
        }
        val r0 = r[0]
        if (r0 < 1e-9) {
            confidence *= 0.7
            updates++
            return
        }

        // Strongest local maximum in 70..180 BPM under the tempo prior.
        var best = -1
        var bestS = 0.0
        for (l in max(2, minL)..min(maxL, lc - 1)) {
            val v = r[l]
            if (v <= 0 || v < r[l - 1] || v < r[l + 1]) continue
            val s = v * prior(bpmOfLag(l.toDouble()))
            if (s > bestS) {
                bestS = s; best = l
            }
        }
        if (best < 0) {
            confidence *= 0.7
            updates++
            return
        }
        val lf = best + parabolic(best)
        var b0 = bpmOfLag(lf)

        // Octave correction toward 85..160, when the other octave has support in the autocorrelation.
        if (b0 > 160.0) {
            val l2 = 2.0 * lf
            if (l2 + 2 < lc && peakNear(l2) > 0.2 * r[best]) b0 /= 2.0
        } else if (b0 < 85.0) {
            val lh = lf / 2.0
            if (lh >= 3 && peakNear(lh) > 0.2 * r[best]) b0 *= 2.0
        }

        // Refine on the peak near k beats (k = 4 if it fits): k times the lag resolution.
        var p = bpmOfLag(b0)
        for (k in 4 downTo 1) {
            val c = k * p
            val ci = c.roundToInt()
            val win = max(1, k)
            if (ci + win + 1 > lc || ci - win - 1 < 1) continue
            var m = ci - win
            for (l in ci - win..ci + win) if (r[l] > r[m]) m = l
            if (k > 1 && (m == ci - win || m == ci + win)) continue
            p = (m + parabolic(m)) / k
            break
        }
        val measuredBpm = bpmOfLag(p)

        // Salience: the normalized autocorrelation at the beat and at the bar.
        val rb = interp(p) / r0
        val r4 = if (4 * p + 1 < lc) interp(4 * p) / r0 else rb
        val sal = 0.5 * rb + 0.5 * max(rb, r4)
        val raw = ((sal - 0.06) / 0.30).coerceIn(0.0, 1.0)
        accept(measuredBpm, raw)
        if (bpm > 0) phase(n, start)
        updates++
    }

    private fun accept(m: Double, c: Double) {
        if (bpm <= 0.0) {
            if (c > 0.12) {
                bpm = m; confidence = c * 0.8
            }
            return
        }
        val ratio = m / bpm
        when {
            abs(ratio - 1.0) < 0.03 -> {
                bpm += (m - bpm) * 0.5
                confidence = 0.5 * confidence + 0.5 * min(1.0, c * 1.1)
                candCount = 0
            }
            related(ratio) -> {
                confidence = 0.75 * confidence + 0.25 * c * 0.7
            }
            else -> {
                if (candCount > 0 && abs(m / candBpm - 1.0) < 0.03) candCount++ else {
                    candBpm = m; candCount = 1
                }
                if (candCount >= 2) {
                    bpm = (m + candBpm) * 0.5; confidence = c * 0.8; candCount = 0
                } else {
                    confidence *= 0.8
                }
            }
        }
    }

    private fun related(ratio: Double): Boolean {
        for (k in RELATED) if (abs(ratio / k - 1.0) < 0.035) return true
        return false
    }

    private fun phase(n: Int, start: Long) {
        val p = bpmOfLag(bpm)
        val m = min(n, phaseHops)
        val k = ((m - 3) / p).toInt()
        if (k < 2) return
        val steps = min(comb.size, ceil(p / PHASE_STEP).toInt())
        var bestI = 0
        for (s in 0 until steps) {
            val phi = s * PHASE_STEP
            var sum = 0.0
            var wk = 1.0
            for (j in 0 until k) {
                sum += wk * tri(n, n - 1 - phi - j * p)
                wk *= RECENCY
            }
            comb[s] = sum
            if (sum > comb[bestI]) bestI = s
        }
        // Parabolic refinement with wrap-around.
        val a = comb[(bestI - 1 + steps) % steps]
        val b = comb[bestI]
        val c = comb[(bestI + 1) % steps]
        val den = a - 2 * b + c
        val off = if (abs(den) > 1e-12) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
        val phi = (bestI + off) * PHASE_STEP
        val beatHop = (start + n - 1) - phi
        beatRefFrame = historyStartFrame + (beatHop + ONSET_OFFSET_HOPS) * hop

        // Downbeat: kick (low-band onset) on each beat of the bar, minus snare-ness (mid onset well above low).
        // Whole bars only, over the full history (up to 4 bars), so every beat of the bar counts equally.
        bins.fill(0.0)
        val bars = min(4, ((n - 3) / p).toInt() / 4)
        for (j in 0 until bars * 4) {
            val pos = (start + n - 1) - phi - j * p
            val lo = maxNear(lowOn, pos)
            bins[j % 4] += lo - 0.5 * max(0.0, maxNear(midOn, pos) - lo)
        }
        var bj = 0
        for (j in 1 until 4) if (bins[j] > bins[bj]) bj = j
        var second = Double.NEGATIVE_INFINITY
        for (j in 0 until 4) if (j != bj && bins[j] > second) second = bins[j]
        barIndexAtRef = bj
        downbeatConfidence = if (bins[bj] > 1e-9) ((bins[bj] - second) / abs(bins[bj])).coerceIn(0.0, 1.0) else 0.0
    }

    /** Triangular-kernel read of the onset envelope at fractional index [x] of e[0 until n]. */
    private fun tri(n: Int, x: Double): Double {
        val j0 = floor(x).toInt()
        var s = 0.0
        for (j in j0 - 1..j0 + 2) {
            if (j < 0 || j >= n) continue
            val wgt = 1.0 - abs(j - x) / 1.5
            if (wgt > 0) s += e[j] * wgt
        }
        return s
    }

    private fun maxNear(ring: FloatArray, absHop: Double): Double {
        val c = absHop.roundToInt().toLong()
        var m = 0.0
        for (j in c - 1..c + 1) {
            if (j < 0 || j >= hops || hops - j >= RING) continue
            val v = ring[(j and MASK).toInt()].toDouble()
            if (v > m) m = v
        }
        return m
    }

    private fun parabolic(i: Int): Double {
        if (i <= 0) return 0.0
        val a = r[i - 1]; val b = r[i]; val c = r[i + 1]
        val den = a - 2 * b + c
        return if (abs(den) > 1e-15) (0.5 * (a - c) / den).coerceIn(-0.5, 0.5) else 0.0
    }

    private fun peakNear(x: Double): Double {
        val c = x.roundToInt()
        var m = Double.NEGATIVE_INFINITY
        for (l in c - 1..c + 1) if (l >= 0 && l < r.size) m = max(m, r[l])
        return m
    }

    private fun interp(x: Double): Double {
        val i = floor(x).toInt()
        if (i < 0 || i + 1 >= r.size) return 0.0
        val t = x - i
        return r[i] * (1 - t) + r[i + 1] * t
    }

    private fun prior(b: Double): Double {
        val o = log2(b / 120.0) / PRIOR_OCTAVES
        return exp(-0.5 * o * o)
    }

    companion object {
        private const val RING = 1024
        private const val MASK = (RING - 1).toLong()
        private const val COMPRESS = 1e5
        private const val W_LOW = 1.0
        private const val W_MID = 0.7
        private const val W_HIGH = 0.4
        private const val PRIOR_OCTAVES = 0.9
        private const val PHASE_STEP = 0.25
        private const val RECENCY = 0.88
        const val MEASURED_CONFIDENCE = 0.45

        /** Where in its hop an onset lands on average (calibrated on click and kick tracks). */
        private const val ONSET_OFFSET_HOPS = 0.5
        private val RELATED = doubleArrayOf(2.0, 0.5, 1.5, 2.0 / 3.0, 3.0, 1.0 / 3.0)
    }
}
