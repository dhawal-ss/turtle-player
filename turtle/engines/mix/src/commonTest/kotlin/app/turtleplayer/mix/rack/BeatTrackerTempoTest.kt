package app.turtleplayer.mix.rack

import app.turtleplayer.mix.Deck
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class BeatTrackerTempoTest {
    private val sr = 48000

    private class Reading(val bpm: Float, val measured: Boolean, val confidence: Float, val phaseErrMs: Double, val view: DeckView)

    private fun analyse(signal: DoubleArray, trueBpm: Double, offset: Double = 0.1, rate: Int = sr, ch: Int = 2): Reading {
        val clock = FakeClock()
        val rack = newRack(clock)
        val p = Any()
        feed(rack, clock, p, toPcm(signal, ch), rate, ch)
        val v = rack.onAir ?: fail("no deck")
        val t = signal.size.toDouble() / rate
        val err = phaseErrorSeconds(v, trueBpm, offset, t) * 1000
        println("true=$trueBpm read=${v.bpm} measured=${v.bpmMeasured} conf=${v.confidence} phaseErr=${err}ms beatInBar=${v.beatInBar}")
        return Reading(v.bpm, v.bpmMeasured, v.confidence, err, v)
    }

    private fun assertTempo(r: Reading, expected: Double, tolBpm: Double = 1.5, tolMs: Double = 40.0) {
        assertTrue(r.measured, "bpm should be measured (confidence ${r.confidence})")
        assertEquals(expected, r.bpm.toDouble(), tolBpm, "bpm")
        assertTrue(abs(r.phaseErrMs) <= tolMs, "phase error ${r.phaseErrMs} ms")
    }

    @Test
    fun clickTracksReadTheirTempo() {
        for (bpm in doubleArrayOf(90.0, 120.0, 128.0, 140.0)) {
            assertTempo(analyse(Signals.clicks(bpm, 14.0, sr), bpm), bpm)
        }
    }

    @Test
    fun clicksAt174ReadAsHalfTime87() {
        val r = analyse(Signals.clicks(174.0, 14.0, sr), 174.0)
        // Octave correction resolves toward 85..160: 174 reads as 87 (phase is still on a click).
        assertTempo(r, 87.0)
    }

    @Test
    fun fourOnTheFloorWithHatsBassAndNoise() {
        for (bpm in doubleArrayOf(90.0, 120.0, 128.0, 140.0)) {
            assertTempo(analyse(Signals.fourOnFloor(bpm, 14.0, sr), bpm), bpm)
        }
    }

    @Test
    fun drumAndBassAt174ReadsAs87() {
        assertTempo(analyse(Signals.fourOnFloor(174.0, 14.0, sr), 174.0), 87.0)
    }

    @Test
    fun aHintAt174ResolvesTheOctave() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 172f)
        feed(rack, clock, Any(), toPcm(Signals.clicks(174.0, 14.0, sr), 2), sr, 2)
        val v = rack.onAir!!
        assertTrue(v.bpmMeasured)
        assertEquals(174.0, v.bpm.toDouble(), 1.5)
    }

    @Test
    fun swungHipHopAt90() {
        assertTempo(analyse(Signals.hipHop(90.0, 14.0, sr), 90.0), 90.0)
    }

    @Test
    fun swungHipHopAt94And86() {
        assertTempo(analyse(Signals.hipHop(94.0, 14.0, sr, seed = 2), 94.0), 94.0)
        assertTempo(analyse(Signals.hipHop(86.0, 14.0, sr, seed = 3), 86.0), 86.0)
    }

    @Test
    fun worksAt44100AndMono() {
        assertTempo(analyse(Signals.fourOnFloor(124.0, 14.0, 44100), 124.0, rate = 44100, ch = 1), 124.0)
    }

    @Test
    fun hipHopDownbeatIsTheKickBeat() {
        val r = analyse(Signals.hipHop(90.0, 16.0, sr), 90.0)
        // Bar starts at 0.1 s; the deck's beatInBar at the end should match the true beat of the bar.
        val t = 16.0 * sr / sr
        val beat = 60.0 / 90.0
        val sinceStart = (t - r.view.beatPhase * beat - 0.1) / beat
        val trueBeatInBar = ((kotlin.math.round(sinceStart).toInt() % 4) + 4) % 4
        assertEquals(trueBeatInBar, r.view.beatInBar, "beat in bar")
    }

    @Test
    fun noiseIsNotMeasured() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 100f)
        feed(rack, clock, Any(), toPcm(Signals.whiteNoise(14.0, sr), 2), sr, 2)
        val v = rack.onAir!!
        println("noise: bpm=${v.bpm} conf=${v.confidence}")
        assertFalse(v.bpmMeasured)
        assertEquals(100f, v.bpm, "falls back to the hint")
    }

    @Test
    fun notMeasuredBeforeSixSeconds() {
        val clock = FakeClock()
        val rack = newRack(clock)
        feed(rack, clock, Any(), toPcm(Signals.fourOnFloor(128.0, 5.5, sr), 2), sr, 2)
        assertFalse(rack.onAir!!.bpmMeasured)
    }

    @Test
    fun readingDoesNotFlickerOverTime() {
        val clock = FakeClock()
        val rack = newRack(clock)
        val p = Any()
        val readings = ArrayList<Float>()
        val pcm = toPcm(Signals.hipHop(90.0, 30.0, sr), 2)
        feed(rack, clock, p, pcm, sr, 2) { f -> if (f > 8 * sr && f % (sr / 2) < 480) readings.add(rack.onAir!!.bpm) }
        val spread = readings.max() - readings.min()
        println("hip-hop readings 8..30 s: min=${readings.min()} max=${readings.max()}")
        assertTrue(spread < 1.0f, "reading moved by $spread BPM")
    }

    @Test
    fun followsATempoChangeAfterASwitch() {
        val clock = FakeClock()
        val rack = newRack(clock)
        val p = Any()
        feed(rack, clock, p, toPcm(Signals.fourOnFloor(124.0, 14.0, sr), 2), sr, 2)
        assertEquals(124.0, rack.onAir!!.bpm.toDouble(), 1.5)
        feed(rack, clock, p, toPcm(Signals.fourOnFloor(140.0, 16.0, sr, seed = 9), 2), sr, 2)
        assertEquals(140.0, rack.onAir!!.bpm.toDouble(), 1.5)
    }

    @Test
    fun energyIsCalibrated() {
        fun energyOf(sig: DoubleArray): Float {
            val clock = FakeClock()
            val rack = newRack(clock)
            feed(rack, clock, Any(), toPcm(sig, 2), sr, 2)
            val tr = (rack.onAir as DeckState).tracker!!
            println("loud=${tr.featLoud} low=${tr.featLow} bright=${tr.featBright} dens=${tr.featDensity}")
            return rack.onAir!!.energy
        }
        val quiet = energyOf(Signals.acoustic(12.0, sr))
        val pop = energyOf(Signals.hipHop(100.0, 12.0, sr).also { s -> for (i in s.indices) s[i] *= 0.55 })
        val club = energyOf(Signals.fourOnFloor(128.0, 12.0, sr).also { s -> for (i in s.indices) s[i] *= 1.4 })
        println("energy quiet=$quiet pop=$pop club=$club")
        assertEquals(0.2, quiet.toDouble(), 0.12)
        assertEquals(0.55, pop.toDouble(), 0.15)
        assertEquals(0.85, club.toDouble(), 0.12)
        assertTrue(quiet < pop && pop < club)
    }

    @Test
    fun loudnessIsRmsDbfs() {
        val clock = FakeClock()
        val rack = newRack(clock)
        feed(rack, clock, Any(), toPcm(Signals.sine(440.0, 3.0, sr, amp = 0.5), 2), sr, 2)
        // A 0.5 amplitude sine is -9 dBFS RMS.
        assertEquals(-9.03, rack.onAir!!.loudnessDb.toDouble(), 0.5)
    }
}
