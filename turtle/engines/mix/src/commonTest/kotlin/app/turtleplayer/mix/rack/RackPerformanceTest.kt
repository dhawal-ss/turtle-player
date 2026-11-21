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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource

class RackPerformanceTest {
    private val sr = 48000
    private val ch = 2

    private fun allOn(rack: MixRack) {
        rack.setUser(FxParam.Low, -0.5f, 0)
        rack.setUser(FxParam.Mid, 0.3f, 0)
        rack.setUser(FxParam.High, -0.3f, 0)
        rack.setUser(FxParam.Filter, -0.4f, 0)
        rack.setUser(FxParam.Gate, 0.5f, 0)
        rack.setUser(FxParam.Flanger, 0.7f, 0)
        rack.setUser(FxParam.EchoSend, 0.6f, 0)
        rack.setUser(FxParam.EchoWet, 0.5f, 0)
        rack.setUser(FxParam.EchoFeedback, 0.7f, 0)
        rack.setUser(FxParam.Gain, 0.9f, 0)
    }

    /** 10 s of 48 kHz stereo with every effect on, the source cycling roll / brake / spin, samples playing. */
    private fun tenSeconds(rack: MixRack, clock: FakeClock, p: Any, pcm: ShortArray, buf: ShortArray): Long {
        val frames = buf.size / ch
        val mark = TimeSource.Monotonic.markNow()
        var f = 0
        while (f < 10 * sr) {
            when (f) {
                0 -> { rack.setUser(FxParam.Spin, 0f, 0); rack.setUser(FxParam.Roll, 1f, 0) }
                3 * sr -> { rack.setUser(FxParam.Roll, 0f, 0); rack.setUser(FxParam.Brake, 1f, 0) }
                6 * sr -> { rack.setUser(FxParam.Brake, 0f, 0); rack.setUser(FxParam.Spin, 1f, 0) }
            }
            if (f % (2 * sr) == 0) rack.trigger(SampleId.Riser)
            pcm.copyInto(buf, 0, f * ch, (f + frames) * ch)
            rack.process(p, buf, buf.size, sr, ch)
            clock.advanceFrames(frames, sr)
            f += frames
        }
        return mark.elapsedNow().inWholeMilliseconds
    }

    @Test
    fun tenSecondsOfStereoWithEverythingOnIsFast() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.warmUp()
        rack.setTempoHint(Deck.Out, 128f)
        allOn(rack)
        val pcm = toPcm(Signals.fourOnFloor(128.0, 10.0, sr), ch)
        val buf = ShortArray(960 * ch)
        val p = Any()
        val warm = tenSeconds(rack, clock, p, pcm, buf) // JIT warm-up
        val times = LongArray(3) { tenSeconds(rack, clock, p, pcm, buf) }
        println("10 s of 48 kHz stereo, all effects: warm-up ${warm} ms, then ${times.joinToString()} ms")
        assertTrue(times.min() < 150, "best of three ${times.min()} ms")
    }

    @Test
    fun neutralPathIsCheap() {
        val clock = FakeClock()
        val rack = newRack(clock)
        val pcm = toPcm(Signals.fourOnFloor(128.0, 10.0, sr), ch)
        val buf = ShortArray(960 * ch)
        val p = Any()
        fun once(): Long {
            val mark = TimeSource.Monotonic.markNow()
            var f = 0
            while (f < 10 * sr) {
                pcm.copyInto(buf, 0, f * ch, (f + 960) * ch)
                rack.process(p, buf, buf.size, sr, ch)
                clock.advanceFrames(960, sr)
                f += 960
            }
            return mark.elapsedNow().inWholeMilliseconds
        }
        once()
        val t = minOf(once(), once())
        println("10 s of 48 kHz stereo, neutral (analysis + history only): $t ms")
        assertTrue(t < 60)
    }

    @Test
    fun noAllocationInSteadyState() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.warmUp()
        rack.setTempoHint(Deck.Out, 120f)
        rack.setTempoHint(Deck.In, 120f)
        allOn(rack)
        val a = Any()
        val b = Any()
        val pa = toPcm(Signals.fourOnFloor(120.0, 12.0, sr), ch)
        val pb = toPcm(Signals.hipHop(90.0, 12.0, sr), ch)
        val buf = ShortArray(480 * ch)
        var f = 0
        fun step(withB: Boolean) {
            pa.copyInto(buf, 0, f * ch, (f + 480) * ch)
            rack.process(a, buf, buf.size, sr, ch)
            if (withB) {
                pb.copyInto(buf, 0, f * ch, (f + 480) * ch)
                rack.process(b, buf, buf.size, sr, ch)
            }
            clock.advanceFrames(480, sr)
            f += 480
        }
        // Warm-up: both decks seen once (their buffers are allocated then).
        repeat(20) { step(true) }
        val before = rack.allocationCount()
        val script = MixScript(
            pre = Segment(
                4.0,
                lanes = listOf(Lane(Deck.Out, FxParam.Roll, listOf(Point(0.0, 1f), Point(2.0, 0f, Curve.Step)))),
                cues = listOf(Cue(1.0, CueKind.Sample(SampleId.Impact)), Cue(3.0, CueKind.Seek)),
            ),
            post = Segment(4.0, lanes = listOf(Lane(Deck.In, FxParam.Filter, listOf(Point(0.0, 0.8f), Point(4.0, 0f))))),
        )
        assertTrue(rack.run(script, Quantize.None))
        repeat(1000) { i ->
            if (i % 100 == 0) rack.trigger(SampleId.Laser, Quantize.Beat)
            if (i % 50 == 0) rack.setUser(FxParam.Filter, (i % 200) / 400f, 30)
            step(true)
        }
        assertEquals(before, rack.allocationCount(), "deck/sampler buffers allocated in steady state")
        for (d in rack.decks) assertTrue(!d.faulted)
    }
}

