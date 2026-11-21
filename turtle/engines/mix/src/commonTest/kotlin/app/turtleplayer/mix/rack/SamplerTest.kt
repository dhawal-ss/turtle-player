package app.turtleplayer.mix.rack

import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.Quantize
import app.turtleplayer.mix.SampleId
import kotlin.math.abs
import kotlin.math.log10

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SamplerTest {
    private val sr = 48000

    private fun rmsDbOf(x: FloatArray, from: Int, to: Int): Double {
        var s = 0.0
        for (i in from until minOf(to, x.size)) s += x[i] * x[i]
        return 10 * log10(s / maxOf(1, to - from) + 1e-20)
    }

    private fun zeroCrossRate(x: FloatArray, from: Int, to: Int): Double {
        var z = 0
        for (i in from + 1 until to) if ((x[i] >= 0) != (x[i - 1] >= 0)) z++
        return z.toDouble() / (to - from) * sr
    }

    @Test
    fun everySoundRendersCleanly() {
        val expected = mapOf(
            SampleId.AirHorn to 1.08, SampleId.Siren to 3.0, SampleId.Riser to 4.0,
            SampleId.Impact to 1.4, SampleId.Laser to 0.82,
        )
        for (id in SampleId.entries) {
            for (rate in intArrayOf(44100, 48000)) {
                val x = SampleSynth.render(id, rate)
                assertEquals(expected.getValue(id), x.size.toDouble() / rate, 0.02, "$id length")
                var peak = 0f
                for (v in x) {
                    assertTrue(v.isFinite())
                    peak = maxOf(peak, abs(v))
                }
                assertTrue(peak in 0.5f..1f, "$id peak $peak")
                // Starts and ends without a click.
                assertTrue(abs(x[0]) < 0.05f && abs(x[x.size - 1]) < 0.05f, "$id edges")
            }
        }
    }

    @Test
    fun airHornHasThreeBlasts() {
        val x = SampleSynth.render(SampleId.AirHorn, sr)
        fun at(t: Double) = rmsDbOf(x, (t * sr).toInt(), ((t + 0.02) * sr).toInt())
        assertTrue(at(0.07) > -12 && at(0.28) > -12 && at(0.7) > -12, "blasts are loud")
        assertTrue(at(0.18) < -40 && at(0.39) < -40, "gaps between blasts")
        // Band-limited around 3 kHz: a horn's buzz, not a sine and not hiss.
        val zcr = zeroCrossRate(x, (0.5 * sr).toInt(), (0.9 * sr).toInt())
        assertTrue(zcr in 1000.0..9000.0, "zero crossings $zcr/s")
    }

    @Test
    fun riserRisesInLevelAndPitch() {
        val x = SampleSynth.render(SampleId.Riser, sr)
        val early = rmsDbOf(x, sr / 4, sr * 3 / 4)
        val late = rmsDbOf(x, (3.3 * sr).toInt(), (3.9 * sr).toInt())
        assertTrue(late - early > 15, "level rise ${late - early} dB")
        assertTrue(zeroCrossRate(x, (3.3 * sr).toInt(), (3.9 * sr).toInt()) > 3 * zeroCrossRate(x, sr / 4, sr * 3 / 4))
    }

    @Test
    fun impactIsLowAndDecays() {
        val x = SampleSynth.render(SampleId.Impact, sr)
        assertTrue(zeroCrossRate(x, (0.2 * sr).toInt(), (0.8 * sr).toInt()) < 120, "sub drop")
        assertTrue(rmsDbOf(x, 0, sr / 5) - rmsDbOf(x, sr, (1.3 * sr).toInt()) > 15, "decays")
    }

    @Test
    fun laserChirpsThreeTimes() {
        val x = SampleSynth.render(SampleId.Laser, sr)
        val starts = doubleArrayOf(0.0, 0.27, 0.54)
        for (s in starts) {
            val hi = zeroCrossRate(x, ((s + 0.005) * sr).toInt(), ((s + 0.04) * sr).toInt())
            val lo = zeroCrossRate(x, ((s + 0.18) * sr).toInt(), ((s + 0.23) * sr).toInt())
            assertTrue(hi > 2 * lo, "chirp at $s descends ($hi -> $lo)")
        }
    }

    private fun silentRack(): Triple<MixRack, FakeClock, Any> {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.warmUp()
        rack.setTempoHint(Deck.Out, 120f)
        return Triple(rack, clock, Any())
    }

    @Test
    fun triggerPlaysOnTheOnAirDeck() {
        val (rack, clock, p) = silentRack()
        val out = feed(rack, clock, p, ShortArray(2 * sr * 2), sr, 2) { f -> if (f == 9600) rack.trigger(SampleId.AirHorn) }
        var first = -1
        for (i in 0 until out.size / 2) if (out[i * 2].toInt() != 0) { first = i; break }
        assertTrue(first in 9600..9602, "fires at the start of the next buffer ($first)")
        // Same on both channels, at sampleVolume 0.8 of a 0.9 peak.
        for (i in 0 until out.size / 2) assertEquals(out[i * 2], out[i * 2 + 1])
        assertEquals(0.72 * 32767, peakAbs(out, 0, out.size / 2, 2).toDouble(), 0.03 * 32767)
    }

    @Test
    fun triggerQuantizedToTheNextBeat() {
        val (rack, clock, p) = silentRack()
        // The deck's beats (120 BPM hint, from its first frame) are every 24000 frames.
        val out = feed(rack, clock, p, ShortArray(2 * sr), sr, 1) { f -> if (f == 4800) rack.trigger(SampleId.Laser, Quantize.Beat) }
        var first = -1
        for (i in out.indices) if (out[i].toInt() != 0) { first = i; break }
        assertTrue(abs(first - 24_000) <= 2, "fired at $first")
    }

    @Test
    fun sampleVolumeScales() {
        val (rack, clock, p) = silentRack()
        rack.sampleVolume = 0.25f
        val out = feed(rack, clock, p, ShortArray(sr), sr, 1) { f -> if (f == 0) rack.trigger(SampleId.Impact) }
        assertEquals(0.25 * 0.9 * 32767, peakAbs(out, 0, sr, 1).toDouble(), 0.03 * 32767)
    }

    @Test
    fun voicesOverlapUpToFour() {
        val (rack, clock, p) = silentRack()
        rack.sampleVolume = 0.2f
        val out = feed(rack, clock, p, ShortArray(sr), sr, 1) { f ->
            if (f == 0) for (i in 0 until 4) rack.trigger(SampleId.Siren)
        }
        // Four identical voices in phase: four times one voice (0.2 * 0.7), soft-clipped below full scale.
        val one = 0.2 * 0.7
        val peak = peakAbs(out, 0, sr, 1) / 32767.0
        assertTrue(peak > 2.5 * one, "voices sum ($peak)")
    }

    @Test
    fun mixedIntoOneDeckOnlyWhenTwoAreOnAir() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.warmUp()
        val a = Any()
        val b = Any()
        val outA = ShortArray(sr)
        val outB = ShortArray(sr)
        val buf = ShortArray(480)
        var f = 0
        while (f < sr) {
            if (f == 9600) rack.trigger(SampleId.AirHorn)
            buf.fill(0); rack.process(a, buf, 480, sr, 1); buf.copyInto(outA, f)
            if (f >= 4800) {
                buf.fill(0); rack.process(b, buf, 480, sr, 1); buf.copyInto(outB, f)
            }
            clock.advanceFrames(480, sr)
            f += 480
        }
        val pa = peakAbs(outA, 0, sr, 1)
        val pb = peakAbs(outB, 0, sr, 1)
        assertTrue((pa > 10000) xor (pb > 10000), "exactly one deck carries the sampler (a=$pa b=$pb)")
        assertTrue(pa > 10000, "the deck on air longest hosts it (a preloading player must not steal it)")
    }
}
