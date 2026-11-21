package app.turtleplayer.mix.rack

import app.turtleplayer.mix.Cue
import app.turtleplayer.mix.CueKind
import app.turtleplayer.mix.Curve
import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.Lane
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.Point
import app.turtleplayer.mix.Quantize
import app.turtleplayer.mix.SampleId
import app.turtleplayer.mix.Segment
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Two fake players driven in lock step (A then B every 10 ms buffer), as two audio threads would
 * during a crossfade. 120 BPM hints, so a beat is 0.5 s = 24000 frames at 48 kHz.
 */
class RackTransportTest {
    private val sr = 48000
    private val beat = 24_000

    private inner class Duo(seconds: Double, aSig: DoubleArray? = null, bSig: DoubleArray? = null) {
        val clock = FakeClock()
        val rack = newRack(clock).also {
            it.setTempoHint(Deck.Out, 120f)
            it.setTempoHint(Deck.In, 120f)
        }
        val a = Any()
        val b = Any()
        val total = (seconds * sr).toInt()
        val inA: ShortArray = toPcm(aSig ?: Signals.sine(440.0, seconds, sr, 0.5), 1)
        val inB: ShortArray = toPcm(bSig ?: Signals.sine(660.0, seconds, sr, 0.5), 1)
        val outA = ShortArray(total)
        val outB = ShortArray(total)
        val cues = ArrayList<Pair<Int, CueKind>>()
        var bStart = Int.MAX_VALUE
        var aStop = Int.MAX_VALUE
        private val buf = ShortArray(480)

        /** Runs until frame [until], calling [at] before each buffer. B plays from [bStart]; its own track starts at 0. */
        fun run(until: Int, at: (Int) -> Unit = {}) {
            var f = cursor
            while (f < until) {
                at(f)
                if (f < aStop) {
                    inA.copyInto(buf, 0, f, f + 480)
                    rack.process(a, buf, 480, sr, 1)
                    buf.copyInto(outA, f)
                }
                if (f >= bStart) {
                    val bf = f - bStart
                    inB.copyInto(buf, 0, bf, bf + 480)
                    rack.process(b, buf, 480, sr, 1)
                    buf.copyInto(outB, f)
                }
                for (c in rack.pollCues()) cues.add(f to c)
                clock.advanceFrames(480, sr)
                f += 480
            }
            cursor = until
        }

        var cursor = 0

        fun level(out: ShortArray, from: Int, to: Int) = rmsDb(out, from, to, 1)
    }

    private fun gainLane(deck: Deck, vararg pts: Point) = Lane(deck, FxParam.Gain, pts.toList())

    @Test
    fun gainStepLandsWithin3msOfItsBeat() {
        val duo = Duo(4.0, aSig = DoubleArray(4 * sr) { 0.5 })
        val script = MixScript(
            pre = Segment(4.0, lanes = listOf(gainLane(Deck.Out, Point(0.0, 1f), Point(2.0, 0f, Curve.Step)))),
            post = Segment(4.0),
        )
        duo.run(24_000)
        assertTrue(duo.rack.run(script, Quantize.None))
        duo.run(4 * sr - 480)
        val expected = 24_000 + 2 * beat
        var found = -1
        for (i in 24_000 until duo.total) if (duo.outA[i] < 16384 / 2) { found = i; break }
        println("gain step at $found, expected $expected (${(found - expected) * 1000.0 / sr} ms)")
        assertTrue(abs(found - expected) <= sr * 3 / 1000, "step landed at $found, expected $expected")
        // Held at its last point (0) after the segment, while waiting for the incoming deck.
        assertEquals(TransitionPhase.AwaitingIncoming, duo.rack.transition.phase)
        assertTrue(duo.level(duo.outA, expected + 480, 4 * sr - 480) < -80)
    }

    @Test
    fun quantizeBeatStartsOnTheDecksNextBeat() {
        val duo = Duo(3.0, aSig = DoubleArray(3 * sr) { 0.5 })
        val script = MixScript(pre = Segment(2.0, lanes = listOf(gainLane(Deck.Out, Point(0.0, 0f)))), post = Segment(2.0))
        duo.run(14_400) // 0.3 s into the deck: its beats are at 0, 0.5 s, 1 s ...
        assertTrue(duo.rack.run(script, Quantize.Beat))
        assertEquals(TransitionPhase.Waiting, duo.rack.transition.phase)
        duo.run(57_600)
        var found = -1
        for (i in 14_400 until duo.total) if (duo.outA[i] < 8192) { found = i; break }
        assertTrue(abs(found - beat) <= 144, "started at $found, expected $beat")
        assertEquals(TransitionPhase.Pre, duo.rack.transition.phase)
    }

    @Test
    fun preMutesInThenPostHandsOver() {
        val duo = Duo(6.0)
        val script = MixScript(
            pre = Segment(4.0, cues = listOf(Cue(3.0, CueKind.Seek))),
            post = Segment(
                4.0,
                lanes = listOf(
                    gainLane(Deck.In, Point(0.0, 0f), Point(2.0, 1f)),
                    gainLane(Deck.Out, Point(0.0, 1f), Point(4.0, 0f)),
                ),
            ),
        )
        duo.run(24_000)
        assertNull(duo.rack.incoming)
        assertTrue(duo.rack.run(script, Quantize.None))
        assertFalse(duo.rack.run(script, Quantize.None), "a second script while one runs")
        duo.bStart = 72_000 // incoming starts at pre beat 2
        duo.run(100_000)
        val preStart = 24_000
        assertEquals(TransitionPhase.Pre, duo.rack.transition.phase)
        val onAir = duo.rack.onAir
        val incoming = duo.rack.incoming
        assertNotNull(onAir); assertNotNull(incoming)
        assertTrue(onAir !== incoming)
        assertTrue(onAir.active && incoming.active)
        // Seek cue at pre beat 3.
        val seek = duo.cues.single { it.second == CueKind.Seek }.first
        assertTrue(abs(seek - (preStart + 3 * beat)) <= 480, "seek at ${seek}")
    }

    @Test
    fun inIsMutedUntilPostAndOutRetiresAfter() {
        val duo = Duo(6.0)
        val script = MixScript(
            pre = Segment(4.0, cues = listOf(Cue(3.0, CueKind.Seek))),
            post = Segment(
                4.0,
                lanes = listOf(
                    gainLane(Deck.In, Point(0.0, 0f), Point(2.0, 1f)),
                    gainLane(Deck.Out, Point(0.0, 1f), Point(4.0, 0.5f)),
                ),
            ),
        )
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        duo.bStart = 72_000
        val postStart = 24_000 + 4 * beat
        val postEnd = postStart + 4 * beat
        val phases = HashMap<Int, TransitionPhase>()
        var inView: DeckView? = null
        duo.run(6 * sr) { f ->
            phases[f] = duo.rack.transition.phase
            if (f == 100_800) inView = duo.rack.incoming
        }
        // In muted through pre (one buffer of tolerance for the two threads' offset).
        assertTrue(peakAbs(duo.outB, 72_000, postStart - 480, 1) == 0, "In must be silent before post")
        // Fades in over 2 beats of post, full after.
        assertTrue(duo.level(duo.outB, postStart + 2 * beat + 2400, postEnd - 2400) > -10)
        assertTrue(duo.level(duo.outB, postStart + 2400, postStart + 7200) < -20)
        // Out stays audible through pre and post (gain lane to 0.5), then is muted once post ends.
        assertTrue(duo.level(duo.outA, postEnd - 4800, postEnd - 960) > -20)
        assertTrue(peakAbs(duo.outA, postEnd + 960, 6 * sr, 1) < 2, "Out muted after post")
        // In now on air at full level.
        assertTrue(duo.level(duo.outB, postEnd + 4800, 6 * sr) > -10)
        assertEquals(TransitionPhase.Pre, phases[48_000])
        assertEquals(TransitionPhase.Post, phases[postStart + 4800])
        assertEquals(TransitionPhase.Idle, phases[postEnd + 4800])
        assertFalse(duo.rack.transition.running)
        assertNull(duo.rack.incoming)
        // The on-air deck is now the former incoming one (B is the only unmuted pipeline).
        val air = duo.rack.onAir!!
        assertTrue(air.active)
        assertSame(inView, air)
    }

    @Test
    fun awaitingIncomingThenPostOnTheNextBeat() {
        val duo = Duo(6.0)
        val script = MixScript(
            pre = Segment(2.0),
            post = Segment(2.0, lanes = listOf(gainLane(Deck.In, Point(0.0, 1f)))),
        )
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        val preEnd = 24_000 + 2 * beat
        duo.run(preEnd + 4800)
        assertEquals(TransitionPhase.AwaitingIncoming, duo.rack.transition.phase)
        assertEquals(2.0 / 4.0, duo.rack.transition.progress.toDouble(), 0.01)
        // Incoming starts at pre beat 3.3: post starts on beat 4.
        duo.bStart = 24_000 + (3.3 * beat).toInt() / 480 * 480
        val postStart = 24_000 + 4 * beat
        duo.run(5 * sr)
        assertTrue(peakAbs(duo.outB, duo.bStart, postStart - 480, 1) == 0)
        assertTrue(duo.level(duo.outB, postStart + 480, postStart + beat) > -10)
    }

    @Test
    fun abortEasesBackAndForgetsTheScript() {
        val duo = Duo(4.0)
        val script = MixScript(
            pre = Segment(8.0, lanes = listOf(Lane(Deck.Out, FxParam.Filter, listOf(Point(0.0, -1f))))),
            post = Segment(4.0),
        )
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        duo.bStart = 48_000
        duo.run(72_000)
        assertTrue(duo.level(duo.outA, 60_000, 72_000) < -25, "440 Hz low-passed down at 180 Hz")
        assertTrue(peakAbs(duo.outB, 48_000, 72_000, 1) == 0)
        duo.rack.abort()
        duo.run(4 * sr)
        assertEquals(TransitionPhase.Idle, duo.rack.transition.phase)
        // 300 ms later both are back to neutral and on air.
        assertTrue(duo.level(duo.outA, 72_000 + 19_200, 4 * sr) > -10)
        assertTrue(duo.level(duo.outB, 72_000 + 19_200, 4 * sr) > -10)
        // The ease is gradual: 50 ms after the abort, Out is still mostly filtered.
        assertTrue(duo.level(duo.outA, 72_000 + 1_000, 72_000 + 2_400) < -15)
        assertTrue(duo.rack.run(script, Quantize.None), "a new script can run after an abort")
    }

    @Test
    fun manualFaderCrossfadesAndEndsAfterABeatAtOne() {
        val duo = Duo(8.0)
        val script = MixScript(
            pre = Segment(1.0),
            post = Segment(8.0, lanes = listOf(gainLane(Deck.In, Point(0.0, 0f)))),
            manualFader = true,
        )
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        duo.bStart = 24_000
        val postStart = 24_000 + beat
        duo.run(postStart + beat)
        // Untouched fader = all Out.
        assertTrue(duo.level(duo.outA, postStart + 4800, postStart + beat) > -10)
        assertTrue(duo.level(duo.outB, postStart + 4800, postStart + beat) < -60)
        duo.rack.setFader(0.5f)
        duo.run(postStart + 3 * beat)
        val mid = postStart + 2 * beat
        assertEquals(-9.03 - 3.0, duo.level(duo.outA, mid, mid + beat), 1.0)
        assertEquals(-9.03 - 3.0, duo.level(duo.outB, mid, mid + beat), 1.0)
        assertEquals(TransitionPhase.Post, duo.rack.transition.phase)
        duo.rack.setFader(1f)
        val tOne = postStart + 3 * beat
        duo.run(tOne + beat / 2)
        assertEquals(TransitionPhase.Post, duo.rack.transition.phase, "not before a full beat at 1")
        duo.run(tOne + 2 * beat)
        assertEquals(TransitionPhase.Idle, duo.rack.transition.phase)
    }

    @Test
    fun manualFaderSwapsTheBassAtTheMiddle() {
        val duo = Duo(6.0, aSig = Signals.sine(50.0, 6.0, sr, 0.5), bSig = Signals.sine(55.0, 6.0, sr, 0.5))
        val script = MixScript(pre = Segment(1.0), post = Segment(8.0), manualFader = true)
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        duo.bStart = 24_000
        duo.rack.setFader(0.4f)
        duo.run(4 * sr)
        // x < 0.5: In's low is killed, Out's is not.
        assertTrue(duo.level(duo.outB, 3 * sr, 4 * sr) < -35)
        assertTrue(duo.level(duo.outA, 3 * sr, 4 * sr) > -15)
        duo.rack.setFader(0.6f)
        duo.run(6 * sr)
        assertTrue(duo.level(duo.outA, 5 * sr, 6 * sr) < -35)
        assertTrue(duo.level(duo.outB, 5 * sr, 6 * sr) > -15)
    }

    @Test
    fun scriptSampleCuePlaysOnTheOutDeckAndIsReported() {
        val duo = Duo(4.0, aSig = DoubleArray(4 * sr))
        duo.rack.warmUp()
        val script = MixScript(
            pre = Segment(4.0, cues = listOf(Cue(1.0, CueKind.Sample(SampleId.AirHorn)), Cue(3.0, CueKind.Seek))),
            post = Segment(2.0),
        )
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        duo.run(4 * sr)
        val cueAt = 24_000 + beat
        var first = -1
        for (i in 24_000 until duo.total) if (duo.outA[i].toInt() != 0) { first = i; break }
        assertTrue(abs(first - cueAt) <= 48, "sample started at $first, expected $cueAt")
        assertTrue(peakAbs(duo.outA, cueAt, cueAt + sr, 1) > 20000, "the horn is loud")
        val kinds = duo.cues.map { it.second }
        assertEquals(listOf(CueKind.Sample(SampleId.AirHorn), CueKind.Seek), kinds)
    }

    @Test
    fun unplannedIncomingIsOnAirWithTheUserLayer() {
        val duo = Duo(3.0)
        duo.rack.setUser(FxParam.Gain, 0.5f, 0)
        duo.bStart = 48_000
        duo.run(3 * sr)
        assertFalse(duo.rack.transition.running)
        assertEquals(-9.03 - 6.02, duo.level(duo.outA, 2 * sr, 3 * sr), 0.5)
        assertEquals(-9.03 - 6.02, duo.level(duo.outB, 2 * sr, 3 * sr), 0.5)
        assertNull(duo.rack.incoming)
    }

    @Test
    fun userLayerFollowsTheOnAirDeckOnly() {
        val duo = Duo(6.0)
        duo.rack.setUser(FxParam.Gain, 0.5f, 0)
        val script = MixScript(pre = Segment(1.0), post = Segment(2.0))
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        duo.bStart = 24_000
        duo.run(4 * sr)
        // During post: Out keeps the user layer, In is neutral; after: In is on air with the user layer.
        assertEquals(-9.03 - 6.02, duo.level(duo.outB, 4 * sr - 24_000, 4 * sr), 0.6)
    }

    @Test
    fun hintsHandOverWhenInGoesOnAir() {
        val duo = Duo(4.0, aSig = DoubleArray(4 * sr), bSig = DoubleArray(4 * sr))
        duo.rack.setTempoHint(Deck.In, 100f)
        val script = MixScript(pre = Segment(1.0), post = Segment(1.0))
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        duo.bStart = 24_000
        duo.run(48_000)
        assertEquals(100f, duo.rack.incoming!!.bpm)
        duo.run(4 * sr)
        assertEquals(TransitionPhase.Idle, duo.rack.transition.phase)
        assertEquals(100f, duo.rack.onAir!!.bpm)
    }

    @Test
    fun receivingAudioAndActivity() {
        val clock = FakeClock()
        val rack = newRack(clock)
        assertFalse(rack.receivingAudio)
        feed(rack, clock, Any(), toPcm(Signals.sine(440.0, 0.2, sr), 2), sr, 2)
        assertTrue(rack.receivingAudio)
        assertTrue(rack.onAir!!.active)
        clock.nanos += 300_000_000L
        assertFalse(rack.onAir!!.active)
        assertTrue(rack.receivingAudio)
        clock.nanos += 200_000_000L
        assertFalse(rack.receivingAudio)
    }

    @Test
    fun resetForgetsEverything() {
        val duo = Duo(3.0)
        duo.rack.setUser(FxParam.Gain, 0f, 0)
        duo.rack.run(MixScript(pre = Segment(8.0), post = Segment(2.0)), Quantize.None)
        duo.run(sr)
        duo.rack.reset()
        assertEquals(TransitionPhase.Idle, duo.rack.transition.phase)
        duo.run(3 * sr)
        assertEquals(-9.03, duo.level(duo.outA, 2 * sr, 3 * sr), 0.3)
    }

    @Test
    fun quantizeBarStartsOnTheMeasuredDownbeat() {
        val sig = Signals.hipHop(90.0, 14.0, sr)
        val duo = Duo(14.0, aSig = sig)
        duo.rack.setTempoHint(Deck.Out, 0f)
        duo.run(10 * sr)
        assertTrue(duo.rack.onAir!!.bpmMeasured)
        assertTrue(duo.rack.run(MixScript(pre = Segment(8.0), post = Segment(4.0)), Quantize.Bar))
        var started = -1
        duo.run(14 * sr) { f -> if (started < 0 && duo.rack.transition.phase == TransitionPhase.Pre) started = f }
        val bar = 4 * 60.0 / 90.0
        val t = started / sr.toDouble()
        val k = kotlin.math.round((t - 0.1) / bar)
        val err = (t - (0.1 + k * bar)) * 1000
        println("bar-quantized start at $t s, ${err} ms from a downbeat")
        // Phase is seen at buffer granularity (10 ms) here.
        assertTrue(kotlin.math.abs(err) <= 50, "started ${err} ms from the downbeat")
    }

    @Test
    fun inSitsAtItsPostStartValuesWhileMuted() {
        // FilterFade-like: In opens from a high-pass. A 100 Hz tone on In must not leak at the unmute.
        val duo = Duo(4.0, bSig = Signals.sine(100.0, 4.0, sr, 0.5))
        val script = MixScript(
            pre = Segment(2.0),
            post = Segment(
                4.0,
                lanes = listOf(
                    Lane(Deck.In, FxParam.Filter, listOf(Point(0.0, 0.8f), Point(4.0, 0f))),
                    gainLane(Deck.In, Point(0.0, 1f)),
                ),
            ),
        )
        duo.run(24_000)
        duo.rack.run(script, Quantize.None)
        duo.bStart = 24_000
        val postStart = 24_000 + 2 * beat
        duo.run(postStart + 4800)
        assertTrue(duo.level(duo.outB, postStart, postStart + 4800) < -35, "In opens high-passed, no leak")
    }
}
