package app.turtleplayer.mix.rack

import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.FxParam
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RackDspTest {
    private val sr = 48000
    private val ch = 2

    /** Plays [freq] through a rack with [setup] applied; returns the RMS (dBFS) of the last second. */
    private fun toneThrough(freq: Double, setup: (MixRack) -> Unit): Double {
        val clock = FakeClock()
        val rack = newRack(clock)
        setup(rack)
        val out = feed(rack, clock, Any(), toPcm(Signals.sine(freq, 2.0, sr, 0.5), ch), sr, ch)
        return rmsDb(out, sr, 2 * sr, ch)
    }

    private val ref = -9.03 // 0.5 amplitude sine

    @Test
    fun untouchedWhenNeutral() {
        val clock = FakeClock()
        val rack = newRack(clock)
        val pcm = toPcm(Signals.fourOnFloor(124.0, 3.0, sr), ch)
        val out = feed(rack, clock, Any(), pcm, sr, ch)
        assertTrue(pcm.contentEquals(out), "neutral rack must not touch the samples")
    }

    @Test
    fun disabledRackDoesNothing() {
        val clock = FakeClock()
        val rack = MixRack(clock::now)
        rack.setUser(FxParam.Gain, 0f, 0)
        val pcm = toPcm(Signals.sine(440.0, 0.5, sr), ch)
        val out = feed(rack, clock, Any(), pcm, sr, ch)
        assertTrue(pcm.contentEquals(out))
        assertEquals(null, rack.onAir)
    }

    @Test
    fun eqKillsAreRealKills() {
        val low = toneThrough(50.0) { it.setUser(FxParam.Low, -1f, 0) }
        val mid = toneThrough(1000.0) { it.setUser(FxParam.Mid, -1f, 0) }
        val high = toneThrough(10000.0) { it.setUser(FxParam.High, -1f, 0) }
        println("kill: low ${low - ref} dB, mid ${mid - ref} dB, high ${high - ref} dB")
        assertTrue(low - ref <= -30, "low kill ${low - ref}")
        assertTrue(mid - ref <= -30, "mid kill ${mid - ref}")
        assertTrue(high - ref <= -30, "high kill ${high - ref}")
        // A low kill leaves the highs alone, a high kill the lows.
        assertEquals(ref, toneThrough(5000.0) { it.setUser(FxParam.Low, -1f, 0) }, 1.0)
        assertEquals(ref, toneThrough(80.0) { it.setUser(FxParam.High, -1f, 0) }, 1.0)
        // Neighbouring bands keep their body: a low kill barely touches 800 Hz, a high kill 1.2 kHz.
        assertEquals(ref, toneThrough(800.0) { it.setUser(FxParam.Low, -1f, 0) }, 1.5)
        assertEquals(ref, toneThrough(1200.0) { it.setUser(FxParam.High, -1f, 0) }, 2.0)
    }

    @Test
    fun eqBoostIsAboutSixDb() {
        val boosted = toneThrough(50.0) { it.setUser(FxParam.Low, 1f, 0) }
        assertEquals(ref + 6.0, boosted, 1.0)
    }

    @Test
    fun djFilterExtremes() {
        val lpStop = toneThrough(2000.0) { it.setUser(FxParam.Filter, -1f, 0) }
        val lpPass = toneThrough(60.0) { it.setUser(FxParam.Filter, -1f, 0) }
        val hpStop = toneThrough(500.0) { it.setUser(FxParam.Filter, 1f, 0) }
        val hpPass = toneThrough(12000.0) { it.setUser(FxParam.Filter, 1f, 0) }
        println("filter: lp stop ${lpStop - ref} pass ${lpPass - ref}; hp stop ${hpStop - ref} pass ${hpPass - ref}")
        assertTrue(lpStop - ref <= -40)
        assertTrue(hpStop - ref <= -40)
        assertEquals(ref, lpPass, 2.0)
        assertEquals(ref, hpPass, 2.0)
        // Half-way low-pass (about 1.9 kHz): 8 kHz is well down, 200 Hz untouched.
        assertTrue(toneThrough(8000.0) { it.setUser(FxParam.Filter, -0.5f, 0) } - ref < -20)
        assertEquals(ref, toneThrough(200.0) { it.setUser(FxParam.Filter, -0.5f, 0) }, 1.0)
    }

    @Test
    fun filterSweepHasNoClicks() {
        // A 220 Hz sine through a filter swept over 2 s: the output's sample-to-sample step stays
        // within what the sine itself does (no zipper noise or discontinuities).
        val clock = FakeClock()
        val rack = newRack(clock)
        val pcm = toPcm(Signals.sine(220.0, 3.0, sr, 0.5), ch)
        val out = feed(rack, clock, Any(), pcm, sr, ch) { f ->
            if (f > sr / 2) rack.setUser(FxParam.Filter, ((f - sr / 2) / (2.0 * sr)).toFloat().coerceAtMost(1f) * -0.8f, 10)
        }
        val maxSineStep = 0.5 * 2 * kotlin.math.PI * 220 / sr * 32768 * 1.3
        var worst = 0.0
        for (i in 1 until out.size / ch) worst = maxOf(worst, abs(out[i * ch] - out[(i - 1) * ch]).toDouble())
        assertTrue(worst <= maxSineStep, "max step $worst > $maxSineStep")
    }

    @Test
    fun gainChangesAreSmooth() {
        val clock = FakeClock()
        val rack = newRack(clock)
        val pcm = toPcm(DoubleArray(sr) { 0.5 }, 1) // DC: any discontinuity shows as a jump
        val out = feed(rack, clock, Any(), pcm, sr, 1) { f -> if (f == 24000) rack.setUser(FxParam.Gain, 0f, 0) }
        var worst = 0
        for (i in 1 until out.size) worst = maxOf(worst, abs(out[i] - out[i - 1]))
        assertTrue(worst < 600, "gain step jumped by $worst")
        assertEquals(0, out[out.size - 1].toInt())
    }

    @Test
    fun echoMakesACopyAtTheBeatDelay() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.EchoSend, 1f, 0)
        rack.setUser(FxParam.EchoWet, 1f, 0)
        rack.setUser(FxParam.EchoFeedback, 0f, 0)
        rack.setUser(FxParam.EchoBeats, 0.5f, 0) // 0.25 s at 120
        val sig = DoubleArray(sr)
        val at = 12_000
        for (j in 0 until 48) sig[at + j] = 0.6 * kotlin.math.sin(2 * kotlin.math.PI * 1000.0 * j / sr)
        val out = feed(rack, clock, Any(), toPcm(sig, 1), sr, 1)
        val delay = 12_000
        var err = 0
        var energy = 0
        for (j in 0 until 48) {
            err = maxOf(err, abs(out[at + delay + j] - out[at + j]))
            energy = maxOf(energy, abs(out[at + j].toInt()))
        }
        assertTrue(energy > 15000)
        assertTrue(err < 400, "echo copy differs by $err")
        // Nothing else rings (feedback 0).
        assertTrue(peakAbs(out, at + delay + 200, at + 2 * delay - 200, 1) < 50)
    }

    @Test
    fun echoFeedbackRepeatsDecayAndThin() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.EchoSend, 1f, 0)
        rack.setUser(FxParam.EchoWet, 1f, 0)
        rack.setUser(FxParam.EchoFeedback, 0.7f, 0)
        rack.setUser(FxParam.EchoBeats, 0.25f, 0) // 0.125 s
        val sig = DoubleArray(2 * sr)
        for (j in 0 until 480) sig[6000 + j] = 0.5 * kotlin.math.sin(2 * kotlin.math.PI * 2000.0 * j / sr)
        val out = feed(rack, clock, Any(), toPcm(sig, 1), sr, 1)
        val d = 6000
        val p1 = peakAbs(out, 6000 + d, 6000 + d + 480, 1)
        val p2 = peakAbs(out, 6000 + 2 * d, 6000 + 2 * d + 600, 1)
        val p4 = peakAbs(out, 6000 + 4 * d, 6000 + 4 * d + 600, 1)
        println("echo repeats: $p1 $p2 $p4")
        assertTrue(p2 < p1 && p4 < p2)
        assertTrue(p2 > 0.4 * p1)
    }

    @Test
    fun echoOutKeepsRingingWithDryAndSendOff() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.EchoSend, 1f, 0)
        rack.setUser(FxParam.EchoWet, 1f, 0)
        rack.setUser(FxParam.EchoFeedback, 0.6f, 0)
        val pcm = toPcm(Signals.fourOnFloor(120.0, 4.0, sr), 1)
        val out = feed(rack, clock, Any(), pcm, sr, 1) { f ->
            if (f == 2 * sr) {
                rack.setUser(FxParam.Dry, 0f, 0)
                rack.setUser(FxParam.EchoSend, 0f, 0)
            }
        }
        val tail = rmsDb(out, 2 * sr + 4800, 2 * sr + 24000, 1)
        println("echo-out tail $tail dB")
        assertTrue(tail > -40, "the echo tail should ring after the dry signal is cut")
    }

    @Test
    fun rollRepeatsTheCapturedSlice() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.RollBeats, 0.25f, 0) // 0.125 s = 6000 frames
        val sig = Signals.whiteNoise(2.0, sr, 0.4)
        val pcm = toPcm(sig, ch)
        val start = 48_000
        val out = feed(rack, clock, Any(), pcm, sr, ch) { f -> if (f == start) rack.setUser(FxParam.Roll, 1f, 0) }
        val len = 6000
        val xf = 240
        for (rep in 1..3) {
            for (j in xf + 10 until len - 10) {
                val o = out[(start + rep * len + j) * ch]
                val i = pcm[(start + j) * ch]
                if (o != i) error("repeat $rep frame $j: $o != $i")
            }
        }
    }

    @Test
    fun rollExitReturnsToLiveAudio() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        val pcm = toPcm(Signals.whiteNoise(2.0, sr, 0.4), ch)
        val out = feed(rack, clock, Any(), pcm, sr, ch) { f ->
            if (f == 24_000) rack.setUser(FxParam.Roll, 1f, 0)
            if (f == 48_000) rack.setUser(FxParam.Roll, 0f, 0)
        }
        // After the 5 ms crossfade the output is the live input again (slip: it kept moving).
        for (j in 48_000 + 300 until 60_000) assertEquals(pcm[j * ch], out[j * ch], "frame $j")
    }

    @Test
    fun brakeSlowsDownAndEndsSilent() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.BrakeBeats, 2f, 0) // 1 s
        val pcm = toPcm(Signals.sine(440.0, 3.0, sr, 0.5), 1)
        val start = 48_000
        val out = feed(rack, clock, Any(), pcm, sr, 1) { f -> if (f == start) rack.setUser(FxParam.Brake, 1f, 0) }
        assertTrue(peakAbs(out, start + 50_000, start + 96_000, 1) < 4, "silent after the stop, while held")
        // Early in the brake it is still loud (no click to silence).
        assertTrue(rmsDb(out, start + 2400, start + 9600, 1) > -12)
        var worst = 0
        for (i in start - 10 until start + 48_000) worst = maxOf(worst, abs(out[i] - out[i - 1]))
        assertTrue(worst < 3000, "discontinuity $worst during the brake")
    }

    @Test
    fun brakeReleaseResumesLive() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        val pcm = toPcm(Signals.sine(440.0, 3.0, sr, 0.5), 1)
        val out = feed(rack, clock, Any(), pcm, sr, 1) { f ->
            if (f == 24_000) rack.setUser(FxParam.Brake, 1f, 0)
            if (f == 96_000) rack.setUser(FxParam.Brake, 0f, 0)
        }
        for (j in 96_000 + 300 until 110_000) assertEquals(pcm[j], out[j])
    }

    @Test
    fun backspinRunsBackwardsThenSilent() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.SpinBeats, 1f, 0) // 0.5 s
        val pcm = toPcm(Signals.fourOnFloor(120.0, 3.0, sr), 1)
        val start = 72_000
        val out = feed(rack, clock, Any(), pcm, sr, 1) { f -> if (f == start) rack.setUser(FxParam.Spin, 1f, 0) }
        assertTrue(rmsDb(out, start + 1000, start + 8000, 1) > -30, "audible while spinning")
        assertTrue(peakAbs(out, start + 26_000, start + 60_000, 1) < 4, "silent after the spin, while held")
    }

    @Test
    fun gateChopsOnTheBeatGrid() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.GateBeats, 0.5f, 0) // 0.25 s period: 0.125 s on, 0.125 s off
        rack.setUser(FxParam.Gate, 1f, 0)
        val out = feed(rack, clock, Any(), toPcm(DoubleArray(2 * sr) { 0.5 }, 1), sr, 1)
        var on = 0
        var off = 0
        for (i in sr until 2 * sr) if (out[i] > 15000) on++ else if (abs(out[i].toInt()) < 200) off++
        println("gate on=$on off=$off")
        assertEquals(0.5, on.toDouble() / sr, 0.05)
        assertEquals(0.5, off.toDouble() / sr, 0.05)
    }

    @Test
    fun flangerChangesTheSoundWithoutBlowingUp() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setUser(FxParam.Flanger, 1f, 0)
        val pcm = toPcm(Signals.whiteNoise(3.0, sr, 0.3), ch)
        val out = feed(rack, clock, Any(), pcm, sr, ch)
        var diff = 0.0
        for (i in sr * ch until out.size) diff += abs(out[i] - pcm[i]).toDouble()
        assertTrue(diff / (out.size - sr * ch) > 100)
        assertTrue(rmsDb(out, sr, 3 * sr, ch) < rmsDb(pcm, sr, 3 * sr, ch) + 3)
    }

    @Test
    fun hotSignalsAreSoftClippedNotWrapped() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setUser(FxParam.Gain, 1.25f, 0)
        rack.setUser(FxParam.Low, 1f, 0)
        val pcm = toPcm(Signals.sine(60.0, 1.0, sr, 0.98), 1)
        val out = feed(rack, clock, Any(), pcm, sr, 1)
        // Same sign as the input everywhere it is loud: no wrap-around.
        for (i in 4800 until out.size) if (abs(pcm[i].toInt()) > 20000) assertTrue(out[i] * pcm[i] > 0, "wrapped at $i")
        assertTrue(peakAbs(out, 4800, out.size, 1) <= 32767)
    }
}
