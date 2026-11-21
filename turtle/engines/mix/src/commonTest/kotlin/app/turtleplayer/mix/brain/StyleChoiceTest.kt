package app.turtleplayer.mix.brain

import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.TrackFacts
import app.turtleplayer.mix.TransitionReason
import app.turtleplayer.mix.TransitionStyle
import app.turtleplayer.mix.TransitionStyle.Blend
import app.turtleplayer.mix.TransitionStyle.Brake
import app.turtleplayer.mix.TransitionStyle.Cut
import app.turtleplayer.mix.TransitionStyle.EchoOut
import app.turtleplayer.mix.TransitionStyle.FilterFade
import app.turtleplayer.mix.TransitionStyle.LoopRoll
import app.turtleplayer.mix.TransitionStyle.RiserDrop
import app.turtleplayer.mix.TransitionStyle.Spinback
import app.turtleplayer.mix.VibeSettings
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StyleChoiceTest {

    private val tricks = setOf(Spinback, Brake, RiserDrop, LoopRoll)

    private fun styles(out: TrackFacts, inn: TrackFacts, vibe: VibeSettings, n: Int = 400, recent: List<TransitionStyle> = emptyList(), fx: Boolean = true) =
        (0 until n).map { TransitionPlanner.plan(out, inn, vibe, recent, Random(it), fx).style }

    private fun share(styles: List<TransitionStyle>, set: Set<TransitionStyle>) = styles.count { it in set }.toDouble() / styles.size

    @Test
    fun aTempoGapRulesOutBlendingUnlessTheListenerWantsItSmooth() {
        val out = track(bpm = 124f)
        val far = track(bpm = 138f) // +11 %
        assertFalse(styles(out, far, VibeSettings(flair = 0.5f)).any { it == Blend || it == FilterFade })
        assertFalse(styles(out, far, VibeSettings(flair = 0.2f)).any { it == Blend || it == FilterFade })
        assertTrue(styles(out, far, VibeSettings(flair = 0.1f)).any { it == Blend || it == FilterFade })
    }

    @Test
    fun halfAndDoubleTimeCountAsClose() {
        val s = styles(track(bpm = 128f), track(bpm = 64.5f), VibeSettings(flair = 0.3f))
        assertTrue(share(s, setOf(Blend)) > 0.4, "128 -> 64.5 blends")
        val plan = TransitionPlanner.plan(track(bpm = 128f), track(bpm = 64.5f), VibeSettings(flair = 0.3f), emptyList(), Random(1))
        assertFalse(TransitionReason.TempoFar in plan.reasons)
    }

    @Test
    fun aGenreChangeRulesOutBlending() {
        val s = styles(track(genre = "house"), track(genre = "pop", bpm = 124f), VibeSettings(flair = 0.5f))
        assertFalse(s.any { it == Blend || it == FilterFade })
        assertTrue(EchoOut in s && Cut in s)
        // Two dance genres 7 % apart do not blend either.
        assertFalse(styles(track(genre = "house", bpm = 124f), track(genre = "techno", bpm = 132.5f), VibeSettings(flair = 0.5f)).any { it == Blend })
        // House into techno on the same grid still blends, less often than house into house.
        val lane = styles(track(genre = "house"), track(genre = "techno", bpm = 125f), VibeSettings(flair = 0.3f))
        val same = styles(track(genre = "house"), track(genre = "house", bpm = 125f), VibeSettings(flair = 0.3f))
        assertTrue(share(lane, setOf(Blend)) in 0.15..share(same, setOf(Blend)) - 0.15, "${share(lane, setOf(Blend))} vs ${share(same, setOf(Blend))}")
        val plan = (0 until 100).map { TransitionPlanner.plan(track(genre = "house"), track(genre = "techno", bpm = 125f), VibeSettings(flair = 0.3f), emptyList(), Random(it)) }
            .first { it.style == Blend }
        assertEquals(listOf(TransitionReason.TempoClose, TransitionReason.GenreChange), plan.reasons)
    }

    @Test
    fun closeDancePairsMostlyBlendAtLowFlair() {
        val s = styles(track(bpm = 124f, energy = 0.6f), track(bpm = 125f, energy = 0.65f), VibeSettings(flair = 0.2f))
        assertTrue(share(s, setOf(Blend)) > 0.55, "blend share ${share(s, setOf(Blend))}")
        assertTrue(share(s, setOf(Blend, FilterFade)) > 0.75)
    }

    @Test
    fun aBigEnergyRiseDrops() {
        val s = styles(track(energy = 0.45f), track(energy = 0.85f), VibeSettings(flair = 0.8f))
        assertTrue(share(s, setOf(RiserDrop, LoopRoll, Cut)) > 0.5, "rise: ${s.groupingBy { it }.eachCount()}")
        assertTrue(share(s, setOf(RiserDrop)) > share(s, setOf(Brake)) * 3)
        val plan = (0 until 50).map { TransitionPlanner.plan(track(energy = 0.45f), track(energy = 0.85f), VibeSettings(flair = 0.8f), emptyList(), Random(it)) }
            .first { it.style == RiserDrop }
        assertEquals(TransitionReason.EnergyJumpUp, plan.reasons.first())
    }

    @Test
    fun aBigEnergyFallBrakesOrEchoesOut() {
        val s = styles(track(energy = 0.85f), track(energy = 0.4f), VibeSettings(flair = 0.7f))
        assertTrue(share(s, setOf(Brake, EchoOut, FilterFade)) > 0.5, "fall: ${s.groupingBy { it }.eachCount()}")
        assertTrue(share(s, setOf(Brake)) > share(s, setOf(RiserDrop)) * 2)
        val plan = (0 until 50).map { TransitionPlanner.plan(track(energy = 0.85f), track(energy = 0.4f), VibeSettings(flair = 0.7f), emptyList(), Random(it)) }
            .first { it.style == Brake }
        assertEquals(listOf(TransitionReason.EnergyDropDown, TransitionReason.YourStyle), plan.reasons)
    }

    @Test
    fun nonDancePairsUseShortMoves() {
        val out = track(genre = "pop", bpm = 100f)
        val inn = track(genre = "pop", bpm = 102f)
        val vibe = VibeSettings(flair = 0.4f)
        val s = styles(out, inn, vibe)
        assertTrue(share(s, setOf(EchoOut, Cut, FilterFade, Blend)) > 0.8, "non-dance: ${s.groupingBy { it }.eachCount()}")
        (0 until 100).map { TransitionPlanner.plan(out, inn, vibe, emptyList(), Random(it)) }.filter { it.style == Blend }
            .forEach { assertEquals(8.0, it.script.post.lengthBeats) }
    }

    @Test
    fun noTricksAtLowFlair() {
        for (pair in listOf(0.4f to 0.9f, 0.9f to 0.3f, 0.6f to 0.6f)) {
            val s = styles(track(energy = pair.first), track(energy = pair.second, genre = "techno", bpm = 140f), VibeSettings(flair = 0.1f))
            assertFalse(s.any { it in tricks }, "flair 0.1 $pair: ${s.toSet()}")
        }
    }

    @Test
    fun withoutFxOnlyGainBlendsAndCuts() {
        val s = styles(track(energy = 0.3f), track(energy = 0.9f, genre = "techno", bpm = 150f), VibeSettings(flair = 0.9f), fx = false)
        assertTrue(s.all { it == Blend || it == Cut })
        assertTrue(Blend in s && Cut in s)
        for (seed in 0 until 50) {
            val plan = TransitionPlanner.plan(track(), track(), VibeSettings(flair = 0.9f), emptyList(), Random(seed), fxAvailable = false)
            assertTrue((plan.script.pre.lanes + plan.script.post.lanes).all { it.param == FxParam.Gain })
            assertTrue(plan.script.pre.cues.none { it.kind is app.turtleplayer.mix.CueKind.Sample } && plan.script.post.cues.none { it.kind is app.turtleplayer.mix.CueKind.Sample })
        }
    }

    @Test
    fun theSameTrickNeverRepeatsAndBlendRunsEnd() {
        val vibe = VibeSettings(flair = 0.9f)
        for (last in TransitionStyle.entries.filter { it != Blend }) {
            assertFalse(styles(track(energy = 0.4f), track(energy = 0.9f), vibe, recent = listOf(last)).contains(last), "$last twice")
        }
        val blends = List(TransitionPlanner.maxBlendRun(0.5f)) { Blend }
        assertFalse(styles(track(), track(), VibeSettings(flair = 0.5f), recent = blends).contains(Blend))
        assertTrue(styles(track(), track(), VibeSettings(flair = 0.5f), recent = listOf(Blend)).contains(Blend))
    }

    @Test
    fun blendLengthFollowsFlairAndPace() {
        fun len(vibe: VibeSettings, dur: Long = 360_000) = TransitionPlanner.blendBeats(
            TransitionPlanner.PairFacts(track(durationMs = dur), track(durationMs = dur)), vibe,
        )
        assertEquals(32, len(VibeSettings(flair = 0f, pace = 0f)))
        assertEquals(16, len(VibeSettings(flair = 0f, pace = 0f), dur = 200_000))
        assertEquals(16, len(VibeSettings(flair = 0.5f, pace = 0.25f)))
        assertEquals(8, len(VibeSettings(flair = 0.9f)))
        assertEquals(8, len(VibeSettings(flair = 0.3f, pace = 0.8f)))
        // 32 beats at 60 BPM would need 34.5 s of overlap: halved.
        assertEquals(16, TransitionPlanner.blendBeats(TransitionPlanner.PairFacts(track(bpm = 60f, durationMs = 400_000), track(bpm = 61f, durationMs = 400_000)), VibeSettings(flair = 0f, pace = 0f)))

        val quick = (0 until 40).map { TransitionPlanner.plan(track(), track(), VibeSettings(flair = 0.3f, pace = 0.9f), emptyList(), Random(it)) }
            .first { it.style == Blend }
        assertTrue(TransitionReason.QuickMix in quick.reasons, "${quick.reasons}")
    }

    @Test
    fun reasonsDescribeThePair() {
        fun reasonsOf(style: TransitionStyle, out: TrackFacts, inn: TrackFacts, vibe: VibeSettings) =
            (0 until 400).map { TransitionPlanner.plan(out, inn, vibe, emptyList(), Random(it)) }.first { it.style == style }.reasons
        assertEquals(listOf(TransitionReason.SameGroove), reasonsOf(Blend, track(), track(bpm = 125f), VibeSettings(flair = 0.3f, pace = 0.2f)))
        val echo = reasonsOf(EchoOut, track(genre = "house", bpm = 124f), track(genre = "hiphop", bpm = 92f, dance = false), VibeSettings(flair = 0.5f))
        assertEquals(listOf(TransitionReason.TempoFar, TransitionReason.GenreChange), echo)
        val smooth = reasonsOf(Blend, track(bpm = 124f), track(bpm = 140f), VibeSettings(flair = 0.05f))
        assertEquals(TransitionReason.YourStyle, smooth.last())
        assertFalse(TransitionReason.TempoClose in smooth)
    }

    /** A 40-transition set picked by the SetPlanner along an arc, mixed at each flair. */
    private fun simulatedSet(flair: Float, seed: Int): List<TransitionStyle> {
        val rnd = Random(seed)
        val pool = syntheticPool(seed, genres = listOf("house", "techno", "disco", "pop")).toMutableList()
        val vibe = VibeSettings(flair = flair, variety = 0.5f)
        val history = ArrayList<TrackFacts>()
        var onAir = pool.removeAt(0)
        val recent = ArrayList<TransitionStyle>()
        repeat(40) { i ->
            val arc = 0.45f + 0.4f * kotlin.math.sin(i / 40.0 * kotlin.math.PI).toFloat()
            val pick = SetPlanner.pick(pool, SetContext(onAir, history.toList(), emptySet(), emptySet()), SetPlanner.target(arc, vibe), vibe, rnd)!!
            pool.remove(pick.track)
            recent += TransitionPlanner.plan(onAir, pick.track, vibe, recent, rnd).style
            history += onAir
            onAir = pick.track
        }
        return recent
    }

    @Test
    fun aSetHasVarietyThatScalesWithFlair() {
        val report = StringBuilder("Style distribution over 5 simulated 40-transition sets:\n")
        for (flair in listOf(0.1f, 0.5f, 0.9f)) {
            val all = ArrayList<TransitionStyle>()
            for (seed in 1..5) {
                val set = simulatedSet(flair, seed)
                set.zipWithNext().forEach { (a, b) -> assertFalse(a == b && a != Blend, "flair $flair seed $seed: $a twice in $set") }
                var run = 0
                for (s in set) {
                    run = if (s == Blend) run + 1 else 0
                    assertTrue(run <= TransitionPlanner.maxBlendRun(flair), "flair $flair: blend run $run in $set")
                }
                assertTrue(set.toSet().size >= 3, "flair $flair seed $seed too samey: $set")
                all += set
            }
            val counts = TransitionStyle.entries.associateWith { s -> all.count { it == s } }
            report.append("  flair $flair: ").append(counts.entries.joinToString { "${it.key} ${it.value * 100 / all.size}%" }).append('\n')
            println("flair $flair: $counts")
            val smooth = share(all, setOf(Blend, FilterFade))
            val trick = share(all, tricks)
            when (flair) {
                0.1f -> {
                    assertTrue(smooth >= 0.6, "flair 0.1 smooth share $smooth")
                    assertEquals(0.0, trick)
                }
                0.5f -> {
                    assertTrue(smooth in 0.25..0.75, "flair 0.5 smooth share $smooth")
                    assertTrue(trick in 0.05..0.45, "flair 0.5 trick share $trick")
                    assertTrue(all.toSet().size >= 6)
                }
                0.9f -> {
                    assertTrue(trick >= 0.35, "flair 0.9 trick share $trick")
                    assertTrue(share(all, setOf(Blend)) <= 0.35)
                    assertEquals(8, all.toSet().size)
                }
            }
        }
        println(report)
    }
}
