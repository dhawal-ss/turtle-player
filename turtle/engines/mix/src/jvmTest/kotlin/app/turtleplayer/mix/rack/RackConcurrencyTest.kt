package app.turtleplayer.mix.rack

import app.turtleplayer.mix.Cue
import app.turtleplayer.mix.CueKind
import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.Lane
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.Point
import app.turtleplayer.mix.Quantize
import app.turtleplayer.mix.SampleId
import app.turtleplayer.mix.Segment
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM only: two real audio threads (one per pipeline) and a control thread hammering the API, with the
 * real clock. Nothing may throw, no deck may fault, and the transport must keep moving.
 */
class RackConcurrencyTest {
    @Test
    fun twoAudioThreadsAndAControlThread() {
        val sr = 48000
        val rack = MixRack(System::nanoTime)
        rack.enabled = true
        rack.warmUp()
        rack.setTempoHint(Deck.Out, 128f)
        rack.setTempoHint(Deck.In, 124f)
        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val bRunning = AtomicBoolean(false)

        fun audio(pipeline: Any, sig: ShortArray, gate: AtomicBoolean?) = Thread {
            try {
                val buf = ShortArray(480 * 2)
                var f = 0
                while (!stop.get()) {
                    if (gate != null && !gate.get()) {
                        LockSupport.parkNanos(2_000_000); continue
                    }
                    if ((f + 480) * 2 > sig.size) f = 0
                    sig.copyInto(buf, 0, f * 2, (f + 480) * 2)
                    rack.process(pipeline, buf, buf.size, sr, 2)
                    f += 480
                    LockSupport.parkNanos(2_500_000) // ~4x real time
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }

        val a = audio(Any(), toPcm(Signals.fourOnFloor(128.0, 20.0, sr), 2), null)
        val b = audio(Any(), toPcm(Signals.hipHop(90.0, 20.0, sr), 2), bRunning)
        a.start(); b.start()
        val script = MixScript(
            pre = Segment(
                2.0,
                lanes = listOf(Lane(Deck.Out, FxParam.Roll, listOf(Point(0.0, 0f), Point(1.0, 1f, app.turtleplayer.mix.Curve.Step)))),
                cues = listOf(Cue(0.0, CueKind.Sample(SampleId.Riser)), Cue(1.5, CueKind.Seek)),
            ),
            post = Segment(
                4.0,
                lanes = listOf(
                    Lane(Deck.In, FxParam.Gain, listOf(Point(0.0, 0f), Point(2.0, 1f))),
                    Lane(Deck.Out, FxParam.Gain, listOf(Point(0.0, 1f), Point(4.0, 0f))),
                    Lane(Deck.Out, FxParam.Roll, listOf(Point(0.0, 0f))),
                ),
            ),
        )
        // One full transition: run, start B on the Seek cue, wait for the end.
        var completed = 0
        var sawPost = false
        assertTrue(rack.run(script, Quantize.Beat))
        val deadline = System.nanoTime() + 6_000_000_000L
        var i = 0
        while (System.nanoTime() < deadline && failure.get() == null) {
            val cues = rack.pollCues()
            if (cues.contains(CueKind.Seek)) bRunning.set(true)
            if (rack.transition.phase == TransitionPhase.Post) sawPost = true
            if (sawPost && !rack.transition.running && completed == 0) completed = 1
            if (completed == 1) {
                when (i % 7) {
                    0 -> rack.setUser(FxParam.Filter, ((i % 40) - 20) / 20f, 30)
                    1 -> rack.trigger(SampleId.Laser, Quantize.Beat)
                    2 -> rack.onAir?.let { v -> check(v.bpm > 0f && v.beatPhase in 0f..1f) }
                    3 -> rack.setFader((i % 10) / 10f)
                    4 -> rack.transition.progress.let { p -> check(p in 0f..1f) }
                    5 -> rack.setUser(FxParam.Gate, (i % 3) / 2f, 10)
                    else -> rack.incoming?.energy
                }
            }
            i++
            Thread.sleep(5)
        }
        // Abort and reset while audio is running.
        rack.run(script, Quantize.None)
        Thread.sleep(50)
        rack.abort()
        Thread.sleep(50)
        rack.reset()
        Thread.sleep(50)
        stop.set(true)
        a.join(); b.join()
        assertNull(failure.get(), "audio thread threw: ${failure.get()}")
        for (d in rack.decks) assertFalse(d.faulted, "deck ${d.slot} faulted")
        println("concurrency: $completed transitions completed, $i control calls")
        assertTrue(completed >= 1, "at least one transition went all the way through")
    }
}
