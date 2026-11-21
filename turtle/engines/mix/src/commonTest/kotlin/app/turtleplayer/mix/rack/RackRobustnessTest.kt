package app.turtleplayer.mix.rack

import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.Lane
import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.Point
import app.turtleplayer.mix.Quantize
import app.turtleplayer.mix.Segment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RackRobustnessTest {
    private val sr = 48000

    @Test
    fun badArgumentsAreIgnored() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setUser(FxParam.Gain, 0.5f, 0)
        val p = Any()
        val s = ShortArray(960) { (it * 31).toShort() }
        val copy = s.copyOf()
        rack.process(p, s, s.size, sr, 0)
        rack.process(p, s, s.size, sr, 9)
        rack.process(p, s, s.size, 0, 2)
        rack.process(p, s, -5, sr, 2)
        rack.process(p, s, 1, sr, 2)
        rack.process(p, ShortArray(0), 0, sr, 2)
        assertTrue(s.contentEquals(copy))
        // count larger than the array: only the array is processed.
        rack.process(p, s, 100_000, sr, 2)
        assertTrue(rack.receivingAudio)
    }

    @Test
    fun oddCountsAndBigBuffers() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setUser(FxParam.Filter, -0.5f, 0)
        val p = Any()
        val big = toPcm(Signals.whiteNoise(1.0, sr), 2)
        rack.process(p, big, big.size - 1, sr, 2) // 47999.5 frames: the half frame is left alone
        assertEquals(big[big.size - 1], toPcm(Signals.whiteNoise(1.0, sr), 2)[big.size - 1])
    }

    @Test
    fun formatChangeOnAPipelineReallocates() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setUser(FxParam.Low, -1f, 0)
        val p = Any()
        feed(rack, clock, p, toPcm(Signals.sine(50.0, 0.5, 44100), 2), 44100, 2)
        val out = feed(rack, clock, p, toPcm(Signals.sine(50.0, 1.0, sr), 1), sr, 1)
        assertTrue(rmsDb(out, sr / 2, sr, 1) < -35)
        for (d in rack.decks) assertFalse(d.faulted)
    }

    @Test
    fun fifthPipelineEvictsTheLongestIdle() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setUser(FxParam.Gain, 0.5f, 0)
        val pipes = List(5) { Any() }
        val buf = ShortArray(480)
        fun play(p: Any): ShortArray {
            buf.fill(10000)
            rack.process(p, buf, 480, sr, 1)
            return buf
        }
        for (i in 0 until 4) {
            play(pipes[i]); clock.advanceFrames(480, sr)
        }
        // All four active: the fifth is passed through untouched.
        assertEquals(10000, play(pipes[4])[479].toInt())
        clock.nanos += 300_000_000L
        for (i in 1 until 4) play(pipes[i])
        clock.advanceFrames(480, sr)
        // Pipeline 0 has been idle longest: the fifth takes its deck and is processed (gain 0.5).
        repeat(10) {
            play(pipes[4]); clock.advanceFrames(480, sr)
        }
        assertEquals(5000.0, play(pipes[4])[479].toDouble(), 2.0)
    }

    @Test
    fun malformedScriptsAreRefused() {
        val rack = newRack(FakeClock())
        assertFalse(rack.run(MixScript(Segment(Double.NaN), Segment(4.0))))
        assertFalse(rack.run(MixScript(Segment(-1.0), Segment(4.0))))
        // Unsorted points and out-of-range values are fine (sorted, clamped).
        assertTrue(
            rack.run(
                MixScript(
                    Segment(4.0, lanes = listOf(Lane(Deck.Out, FxParam.Gain, listOf(Point(2.0, 9f), Point(0.0, -3f))))),
                    Segment(1.0),
                ),
                Quantize.None,
            ),
        )
    }

    @Test
    fun laneCurvesShapeTheMove() {
        val s = CompiledScript.compile(
            MixScript(
                Segment(
                    8.0,
                    lanes = listOf(
                        Lane(Deck.Out, FxParam.Filter, listOf(Point(0.0, 0f), Point(4.0, 1f, app.turtleplayer.mix.Curve.EaseIn))),
                        Lane(Deck.In, FxParam.Filter, listOf(Point(2.0, 0f), Point(4.0, 1f, app.turtleplayer.mix.Curve.Step))),
                    ),
                ),
                Segment(1.0),
            ),
        )!!
        val ease = s.pre.lane(0, FxParam.Filter.ordinal)!!
        assertEquals(0.25f, ease.valueAt(2.0), 1e-6f)
        assertEquals(1f, ease.valueAt(6.0))
        val step = s.pre.lane(1, FxParam.Filter.ordinal)!!
        assertEquals(0f, step.valueAt(0.0), "before the first point the first value holds")
        assertEquals(0f, step.valueAt(3.999))
        assertEquals(1f, step.valueAt(4.0))
        assertEquals(4.0, s.pre.nextEvent(2.0))
        assertEquals(8.0, s.pre.nextEvent(4.0))
    }

    @Test
    fun disablingClearsState() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setUser(FxParam.Gain, 0f, 0)
        rack.run(MixScript(Segment(8.0), Segment(4.0)), Quantize.None)
        feed(rack, clock, Any(), toPcm(Signals.sine(440.0, 0.2, sr), 1), sr, 1)
        rack.enabled = false
        assertFalse(rack.transition.running)
        rack.enabled = true
        val pcm = toPcm(Signals.sine(440.0, 0.5, sr), 1)
        val out = feed(rack, clock, Any(), pcm, sr, 1)
        assertTrue(pcm.contentEquals(out), "user layer cleared by the off/on")
    }
}
