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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Audio delivered the way ExoPlayer does it on a phone: bursts of 13 x 960-frame buffers (20 ms each)
 * back to back, then a ~255 ms wall-clock pause (253..259 ms), real time on average. Wall-clock gaps
 * longer than 250 ms between calls must not read as silence.
 */
class RackBurstTest {
    private val sr = 48000
    private val ch = 2

    /** One player pipeline delivering [pcm] in bursts from wall-clock [startNs]. */
    private inner class Player(val pcm: ShortArray, var next: Long, val burstPhase: Int = 0) {
        val pipeline = Any()
        val out = ShortArray(pcm.size)
        val frames = pcm.size / ch
        var pos = 0
        var stopped = false
        private var inBurst = 0
        private var cycle = burstPhase
        private val buf = ShortArray(BUF * ch)

        val done get() = stopped || pos + BUF > frames

        fun step(rack: MixRack) {
            pcm.copyInto(buf, 0, pos * ch, (pos + BUF) * ch)
            rack.process(pipeline, buf, buf.size, sr, ch)
            buf.copyInto(out, pos * ch)
            pos += BUF
            if (++inBurst == BURST) {
                inBurst = 0
                // 13 x 20 ms = 260 ms of audio per cycle; the pause jitters 253..259 ms.
                val jitter = longArrayOf(0, 3_000_000, -3_000_000, 2_000_000)[cycle++ % 4]
                next += 260_000_000L - (BURST - 1) * CALL_NS + jitter
            } else {
                next += CALL_NS
            }
        }
    }

    private fun run(rack: MixRack, clock: FakeClock, players: List<Player>, untilNs: Long, hook: (Player) -> Unit = {}) {
        while (true) {
            val p = players.filter { !it.done && it.next <= untilNs }.minByOrNull { it.next } ?: break
            clock.nanos = p.next
            hook(p)
            p.step(rack)
        }
        clock.nanos = untilNs
    }

    private fun audioSeconds(p: Player) = p.pos.toDouble() / sr

    private fun assertTempoUnderBursts(sig: DoubleArray, trueBpm: Double) {
        val clock = FakeClock()
        val rack = newRack(clock)
        val p = Player(toPcm(sig, ch), clock.nanos)
        var at8: DeckView? = null
        var measuredAt = -1.0
        run(rack, clock, listOf(p), clock.nanos + 13_000_000_000L) { pl ->
            val v = rack.onAir
            if (v != null && v.bpmMeasured && measuredAt < 0) measuredAt = audioSeconds(pl)
            if (at8 == null && audioSeconds(pl) >= 8.5) at8 = v
        }
        val v = rack.onAir!!
        val err = phaseErrorSeconds(v, trueBpm, 0.1, audioSeconds(p)) * 1000
        println("bursty $trueBpm: measured at $measuredAt s, bpm ${v.bpm}, conf ${v.confidence}, phase err $err ms")
        assertTrue(measuredAt in 0.0..8.0, "measured by 8 s (at $measuredAt)")
        assertEquals(trueBpm, v.bpm.toDouble(), 1.5)
        assertTrue(abs(err) <= 40, "phase error $err ms")
    }

    @Test
    fun tempoBecomesMeasuredWithBurstyDelivery() {
        assertTempoUnderBursts(Signals.fourOnFloor(128.0, 14.0, sr), 128.0)
        assertTempoUnderBursts(Signals.hipHop(90.0, 14.0, sr), 90.0)
    }

    @Test
    fun steadyBurstsNeverLookInactive() {
        val clock = FakeClock()
        val rack = newRack(clock)
        val p = Player(toPcm(Signals.sine(440.0, 6.0, sr), ch), clock.nanos)
        var inactive = 0
        run(rack, clock, listOf(p), clock.nanos + 5_500_000_000L) { _ ->
            if (p.pos > 0 && !rack.onAir!!.active) inactive++
            if (p.pos > 0 && !rack.receivingAudio) inactive++
        }
        assertEquals(0, inactive)
    }

    @Test
    fun rollHeldAcrossBurstsKeepsRepeating() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.RollBeats, 0.25f, 0) // 6000 frames
        val p = Player(toPcm(Signals.whiteNoise(4.0, sr, 0.4), ch), clock.nanos)
        val start = 48_000 // a buffer boundary (50 x 960)
        run(rack, clock, listOf(p), clock.nanos + 4_000_000_000L) { pl -> if (pl.pos == start) rack.setUser(FxParam.Roll, 1f, 0) }
        val len = 6000
        // 1.5 s of repeats spans several bursts and their pauses.
        for (rep in 1..12) {
            for (j in 300 until len - 10) {
                val o = p.out[(start + rep * len + j) * ch]
                val i = p.pcm[(start + j) * ch]
                if (o != i) error("repeat $rep frame $j: roll was cancelled or moved ($o != $i)")
            }
        }
    }

    @Test
    fun brakeHeldAcrossBurstsStaysStopped() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.setTempoHint(Deck.Out, 120f)
        rack.setUser(FxParam.BrakeBeats, 1f, 0) // 0.5 s
        val p = Player(toPcm(Signals.sine(440.0, 5.0, sr, 0.5), ch), clock.nanos)
        val start = 48_000
        run(rack, clock, listOf(p), clock.nanos + 5_000_000_000L) { pl -> if (pl.pos == start) rack.setUser(FxParam.Brake, 1f, 0) }
        assertTrue(peakAbs(p.out, start + 26_000, start + 150_000, ch) < 4, "silent while held, across bursts")
    }

    @Test
    fun scriptWithTwoBurstyPipelines() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.warmUp()
        rack.setTempoHint(Deck.Out, 124f)
        rack.setTempoHint(Deck.In, 124f)
        val a = Player(toPcm(Signals.fourOnFloor(124.0, 16.0, sr), ch), clock.nanos)
        val b = Player(toPcm(Signals.sine(660.0, 12.0, sr, 0.5), ch), Long.MAX_VALUE, burstPhase = 1)
        val script = MixScript(
            pre = Segment(4.0, cues = listOf(Cue(3.0, CueKind.Seek))),
            post = Segment(
                8.0,
                lanes = listOf(
                    Lane(Deck.In, FxParam.Gain, listOf(Point(0.0, 0f), Point(4.0, 1f))),
                    Lane(Deck.Out, FxParam.Gain, listOf(Point(4.0, 1f), Point(8.0, 0f))),
                ),
            ),
        )
        var seekSeen = false
        var outView: DeckView? = null
        var inView: DeckView? = null
        var falseIn = false
        val phases = LinkedHashSet<TransitionPhase>()
        run(rack, clock, listOf(a, b), clock.nanos + 14_000_000_000L) { pl ->
            if (pl === a && pl.pos == 3 * 48_000 - 3 * 48_000 % 960 && !rack.transition.running) {
                assertTrue(rack.run(script, Quantize.Beat))
            }
            if (rack.pollCues().contains(CueKind.Seek) && !seekSeen) {
                seekSeen = true
                b.next = clock.nanos + 400_000_000L // the incoming player starts 0.4 s after the seek
            }
            phases.add(rack.transition.phase)
            if (rack.transition.phase == TransitionPhase.Pre && outView == null) outView = rack.onAir
            val inc = rack.incoming
            if (inc != null) {
                if (b.pos == 0 && pl !== b) falseIn = true // an In before B ever played
                if (inView == null) inView = inc
                if (inc === outView) falseIn = true
            }
        }
        assertTrue(seekSeen)
        assertFalse(falseIn, "Out's bursts were taken for a new incoming deck")
        assertNotNull(inView)
        assertTrue(phases.containsAll(listOf(TransitionPhase.Waiting, TransitionPhase.Pre, TransitionPhase.Post, TransitionPhase.Idle)))
        assertEquals(TransitionPhase.Idle, rack.transition.phase)
        assertNull(rack.incoming)
        assertSame(inView, rack.onAir, "In went on air")
        // B audible at the end, A (still delivering) muted.
        val bEnd = b.pos
        assertTrue(rmsDb(b.out, bEnd - 24_000, bEnd, ch) > -10)
        val aEnd = a.pos
        assertTrue(peakAbs(a.out, aEnd - 24_000, aEnd, ch) < 2, "Out muted after post")
        // B muted from its very first buffer until post starts.
        assertTrue(peakAbs(b.out, 0, 960, ch) == 0)
        var unmute = 0
        while (unmute < b.pos && b.out[unmute * ch].toInt() == 0) unmute++
        println("bursty script: phases $phases, B unmuted after ${unmute * 1000 / sr} ms of its audio")
    }

    @Test
    fun preloadingPlayerDoesNotStealOnAirAndARealPauseReactivates() {
        val clock = FakeClock()
        val rack = newRack(clock)
        rack.warmUp()
        rack.setTempoHint(Deck.Out, 120f)
        rack.setTempoHint(Deck.In, 120f)
        val a = Player(toPcm(DoubleArray(14 * sr), ch), clock.nanos)
        val b = Player(toPcm(Signals.sine(660.0, 10.0, sr, 0.5), ch), clock.nanos + 2_000_000_000L, burstPhase = 2)
        val t0 = clock.nanos
        val script = MixScript(pre = Segment(4.0), post = Segment(2.0, lanes = listOf(Lane(Deck.In, FxParam.Gain, listOf(Point(0.0, 1f))))))
        var aView: DeckView? = null
        var triggeredAt = -1
        var scriptRun = false
        var bWasIn = false
        run(rack, clock, listOf(a, b), t0 + 10_000_000_000L) { pl ->
            val t = clock.nanos - t0
            if (aView == null && a.pos > 0) aView = rack.onAir
            // B preloads: three bursts from 2 s, then stops (a real pause), then plays for real at 6.5 s.
            if (pl === b && b.pos == 3 * BURST * BUF && b.next < t0 + 6_500_000_000L) {
                b.next = t0 + 6_500_000_000L
            }
            if (t in 2_000_000_000L..2_900_000_000L) {
                assertSame(aView, rack.onAir, "a preloading player must not take over on air")
                if (triggeredAt < 0 && pl === a) {
                    rack.trigger(SampleId.AirHorn)
                    triggeredAt = a.pos
                }
            }
            if (!scriptRun && t >= 4_000_000_000L) {
                scriptRun = rack.run(script, Quantize.None)
                assertTrue(scriptRun)
            }
            if (t in 4_500_000_000L..6_400_000_000L) {
                assertNull(rack.incoming, "the paused preloader is not In until it plays again")
                if (t > 4_600_000_000L) assertFalse(b.let { _ -> rack.decks.any { d -> d !== aView && d.active } }, "B inactive while paused")
            }
            if (rack.incoming != null && rack.incoming !== aView) bWasIn = true
        }
        // The horn went to A (on air longest), not to the preloading B.
        assertTrue(peakAbs(a.out, triggeredAt, triggeredAt + 24_000, ch) > 10_000)
        assertTrue(bWasIn, "B playing again after a 2+ s pause is a new activation: the incoming deck")
        assertEquals(TransitionPhase.Idle, rack.transition.phase)
        assertTrue(rmsDb(b.out, b.pos - 24_000, b.pos, ch) > -10)
    }

    @Test
    fun aRealTwoSecondPauseIsAReactivation() {
        val clock = FakeClock()
        val rack = newRack(clock)
        val a = Player(toPcm(Signals.fourOnFloor(124.0, 8.0, sr), ch), clock.nanos)
        val t0 = clock.nanos
        var activeDuringPause = false
        var paused = false
        run(rack, clock, listOf(a), t0 + 9_000_000_000L) { pl ->
            if (!paused && pl.pos >= 3 * 48_000) {
                paused = true
                pl.next = clock.nanos + 2_000_000_000L
                clock.nanos += 1_000_000_000L
                activeDuringPause = rack.onAir!!.active || rack.receivingAudio
                clock.nanos = pl.next
            }
        }
        val d = rack.onAir as DeckState
        assertFalse(activeDuringPause, "inactive during a real pause")
        // The re-activation was seen as one: the deck's activation time is after the pause.
        assertTrue(d.activatedNanos > t0 + 3_000_000_000L)
    }

    private companion object {
        const val BUF = 960
        const val BURST = 13
        const val CALL_NS = 300_000L
    }
}
