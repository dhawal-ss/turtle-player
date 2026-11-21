package app.turtleplayer.mix.rack

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Time that only moves when the test feeds audio. */
internal class FakeClock(var nanos: Long = 5_000_000_000L) {
    fun now(): Long = nanos
    fun advanceFrames(frames: Int, sr: Int) {
        nanos += frames * 1_000_000_000L / sr
    }
}

internal fun newRack(clock: FakeClock): MixRack = MixRack(clock::now).also { it.enabled = true }

/** Mono -1..1 to interleaved 16-bit (same signal on every channel). */
internal fun toPcm(mono: DoubleArray, ch: Int): ShortArray {
    val out = ShortArray(mono.size * ch)
    for (i in mono.indices) {
        val v = (mono[i] * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
        for (k in 0 until ch) out[i * ch + k] = v
    }
    return out
}

/**
 * Feeds [pcm] through [rack] in buffers of [bufFrames] for [pipeline], advancing the clock with the
 * audio. Returns the processed copy.
 */
internal fun feed(
    rack: MixRack, clock: FakeClock, pipeline: Any, pcm: ShortArray, sr: Int, ch: Int, bufFrames: Int = 480,
    each: ((frame: Int) -> Unit)? = null,
): ShortArray {
    val out = pcm.copyOf()
    val buf = ShortArray(bufFrames * ch)
    var f = 0
    val total = pcm.size / ch
    while (f < total) {
        val n = minOf(bufFrames, total - f)
        each?.invoke(f)
        pcm.copyInto(buf, 0, f * ch, (f + n) * ch)
        rack.process(pipeline, buf, n * ch, sr, ch)
        buf.copyInto(out, f * ch, 0, n * ch)
        clock.advanceFrames(n, sr)
        f += n
    }
    return out
}

internal object Signals {
    fun silence(seconds: Double, sr: Int) = DoubleArray((seconds * sr).toInt())

    fun sine(freq: Double, seconds: Double, sr: Int, amp: Double = 0.5): DoubleArray {
        val n = (seconds * sr).toInt()
        return DoubleArray(n) { amp * sin(2 * PI * freq * it / sr) }
    }

    /** Short clicks (2 ms decaying 2 kHz burst) on every beat from [offset] s. */
    fun clicks(bpm: Double, seconds: Double, sr: Int, offset: Double = 0.1): DoubleArray {
        val out = DoubleArray((seconds * sr).toInt())
        var t = offset
        while (t < seconds) {
            val s0 = (t * sr).roundToInt()
            for (j in 0 until (0.004 * sr).toInt()) {
                val i = s0 + j
                if (i >= out.size) break
                out[i] += 0.8 * sin(2 * PI * 2000.0 * j / sr) * exp(-j / (0.001 * sr))
            }
            t += 60.0 / bpm
        }
        return out
    }

    fun addKick(out: DoubleArray, t: Double, sr: Int, amp: Double = 0.8) {
        val s0 = (t * sr).roundToInt()
        var ph = 0.0
        val len = (0.25 * sr).toInt()
        for (j in 0 until len) {
            val i = s0 + j
            if (i < 0 || i >= out.size) continue
            val tt = j.toDouble() / sr
            val f = 50.0 + 100.0 * exp(-tt / 0.03)
            ph += 2 * PI * f / sr
            out[i] += amp * sin(ph) * exp(-tt / 0.12) * minOf(1.0, j / (0.001 * sr))
        }
    }

    fun addNoiseHit(out: DoubleArray, t: Double, sr: Int, amp: Double, decay: Double, rnd: Random, tone: Double = 0.0) {
        val s0 = (t * sr).roundToInt()
        val len = (decay * 6 * sr).toInt()
        var prev = 0.0
        for (j in 0 until len) {
            val i = s0 + j
            if (i < 0 || i >= out.size) continue
            val tt = j.toDouble() / sr
            val n = rnd.nextDouble(-1.0, 1.0)
            val hp = if (tone > 0) n else n - prev // differentiated noise is bright (hat)
            prev = n
            val body = if (tone > 0) 0.6 * sin(2 * PI * tone * tt) + 0.5 * n else hp
            out[i] += amp * body * exp(-tt / decay)
        }
    }

    fun addNoise(out: DoubleArray, amp: Double, rnd: Random) {
        for (i in out.indices) out[i] += amp * rnd.nextDouble(-1.0, 1.0)
    }

    /** Four-on-the-floor: kick on every beat, open hat on the off-beats, a bass line and noise. */
    fun fourOnFloor(bpm: Double, seconds: Double, sr: Int, offset: Double = 0.1, seed: Int = 7): DoubleArray {
        val rnd = Random(seed)
        val out = DoubleArray((seconds * sr).toInt())
        val beat = 60.0 / bpm
        var k = 0
        while (offset + k * beat < seconds) {
            val t = offset + k * beat
            addKick(out, t, sr)
            addNoiseHit(out, t + beat / 2, sr, 0.22, 0.03, rnd)
            if (k % 4 == 1 || k % 4 == 3) addNoiseHit(out, t, sr, 0.3, 0.06, rnd, tone = 220.0)
            k++
        }
        // Bass: sustained 55/41 Hz notes, two beats each, off the kick.
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val b = floor((t - offset) / beat)
            val f = if (b.toInt() / 2 % 2 == 0) 55.0 else 41.2
            out[i] += 0.12 * sin(2 * PI * f * t)
        }
        addNoise(out, 0.05, rnd)
        return out
    }

    /**
     * Boom-bap at [bpm]: kick on 1, the "a" of 2 and the "and" of 3; snare on 2 and 4; swung 8th hats
     * (off-beats at 62% of the beat), a bass line and noise.
     */
    fun hipHop(bpm: Double, seconds: Double, sr: Int, offset: Double = 0.1, seed: Int = 11): DoubleArray {
        val rnd = Random(seed)
        val out = DoubleArray((seconds * sr).toInt())
        val beat = 60.0 / bpm
        var bar = 0
        while (offset + bar * 4 * beat < seconds) {
            val t0 = offset + bar * 4 * beat
            for (pos in doubleArrayOf(0.0, 1.75, 2.5)) addKick(out, t0 + pos * beat, sr, if (pos == 0.0) 0.85 else 0.6)
            for (pos in doubleArrayOf(1.0, 3.0)) addNoiseHit(out, t0 + pos * beat, sr, 0.5, 0.07, rnd, tone = 190.0)
            for (b in 0 until 4) {
                addNoiseHit(out, t0 + b * beat, sr, 0.12, 0.025, rnd)
                addNoiseHit(out, t0 + (b + 0.62) * beat, sr, 0.09, 0.025, rnd)
            }
            bar++
        }
        for (i in out.indices) {
            val t = i.toDouble() / sr
            val b = floor((t - offset) / (4 * beat)).toInt()
            val f = doubleArrayOf(49.0, 55.0, 41.2, 46.2)[((b % 4) + 4) % 4]
            out[i] += 0.1 * sin(2 * PI * f * t)
        }
        addNoise(out, 0.04, rnd)
        return out
    }

    /** Quiet "acoustic" plucks: decaying harmonic tones, about two per second, no low end. */
    fun acoustic(seconds: Double, sr: Int, seed: Int = 3): DoubleArray {
        val rnd = Random(seed)
        val out = DoubleArray((seconds * sr).toInt())
        var t = 0.05
        val notes = doubleArrayOf(196.0, 247.0, 294.0, 330.0, 392.0)
        while (t < seconds) {
            val f = notes[rnd.nextInt(notes.size)]
            val s0 = (t * sr).toInt()
            for (j in 0 until (1.5 * sr).toInt()) {
                val i = s0 + j
                if (i >= out.size) break
                val tt = j.toDouble() / sr
                out[i] += 0.07 * (sin(2 * PI * f * tt) + 0.4 * sin(4 * PI * f * tt)) * exp(-tt / 0.5)
            }
            t += 0.45 + rnd.nextDouble(0.0, 0.2)
        }
        addNoise(out, 0.002, rnd)
        return out
    }

    fun whiteNoise(seconds: Double, sr: Int, amp: Double = 0.3, seed: Int = 5): DoubleArray {
        val rnd = Random(seed)
        return DoubleArray((seconds * sr).toInt()) { amp * rnd.nextDouble(-1.0, 1.0) }
    }
}

internal fun rmsDb(pcm: ShortArray, fromFrame: Int, toFrame: Int, ch: Int, channel: Int = 0): Double {
    var s = 0.0
    var n = 0
    for (f in fromFrame until toFrame) {
        val v = pcm[f * ch + channel] / 32768.0
        s += v * v
        n++
    }
    return 10 * log10(s / maxOf(1, n) + 1e-20)
}

internal fun peakAbs(pcm: ShortArray, fromFrame: Int, toFrame: Int, ch: Int, channel: Int = 0): Int {
    var m = 0
    for (f in fromFrame until toFrame) m = maxOf(m, abs(pcm[f * ch + channel].toInt()))
    return m
}

/** Phase error in seconds of [view] against a beat grid at [trueBpm] starting at [offset], at time [t]. */
internal fun phaseErrorSeconds(view: DeckView, trueBpm: Double, offset: Double, t: Double): Double {
    val tk = 60.0 / trueBpm
    val readPeriod = 60.0 / view.bpm
    val sinceReadBeat = view.beatPhase * readPeriod // time since the deck's last beat
    val lastBeatTime = t - sinceReadBeat
    val d = (lastBeatTime - offset) / tk
    return (d - floor(d + 0.5)) * tk
}

internal fun sqr(x: Double) = x * x

internal fun rms(x: DoubleArray) = sqrt(x.sumOf { it * it } / x.size)
