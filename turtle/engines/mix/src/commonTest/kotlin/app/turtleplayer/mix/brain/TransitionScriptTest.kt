package app.turtleplayer.mix.brain

import app.turtleplayer.mix.CueKind
import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.SampleId
import app.turtleplayer.mix.TransitionStyle
import app.turtleplayer.mix.VibeSettings
import app.turtleplayer.mix.brain.ScriptRender.Companion.lane
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class TransitionScriptTest {

    private val tempos = listOf(85f, 100f, 124f, 128f, 140f, 174f)

    private fun assertMusical(script: MixScript, label: String) {
        val v = ScriptChecks.violations(script)
        if (v.isNotEmpty()) fail("$label:\n  " + v.joinToString("\n  "))
    }

    @Test
    fun everyStyleIsMusicalAtEveryTempoAndLength() {
        for (style in TransitionStyle.entries) for (bpm in tempos) for (beats in listOf(null, 4, 6, 8, 12, 16, 32)) {
            for ((oe, ie) in listOf(0.5f to 0.55f, 0.9f to 0.3f, 0.3f to 0.9f)) for (dance in listOf(true, false)) {
                val out = track(bpm = bpm, energy = oe, dance = dance)
                val inn = track(bpm = bpm * 1.02f, energy = ie, dance = dance)
                assertMusical(TransitionPlanner.script(style, out, inn, beats), "$style bpm=$bpm beats=$beats e=$oe->$ie dance=$dance")
            }
        }
    }

    @Test
    fun gainOnlyAndAirHornVariantsAreMusical() {
        for (style in TransitionStyle.entries) for (bpm in tempos) {
            val out = track(bpm = bpm)
            val inn = track(bpm = bpm)
            for (beats in listOf(4, 8, 16, 32)) {
                val noFx = Scripts.build(style, out, inn, ScriptOptions(blendBeats = beats, fx = false))
                assertMusical(noFx, "$style no-fx bpm=$bpm beats=$beats")
                val params = (noFx.pre.lanes + noFx.post.lanes).map { it.param }.toSet()
                assertEquals(setOf(FxParam.Gain), params, "$style without FX may only use Gain")
            }
            assertMusical(Scripts.build(style, out, inn, ScriptOptions(blendBeats = 16, airHorn = true)), "$style horn bpm=$bpm")
        }
    }

    @Test
    fun plannedTransitionsAreMusicalAcrossScenarios() {
        val rnd = Random(7)
        val genres = listOf("house", "techno", "pop", "acoustic")
        var count = 0
        for (flair in listOf(0f, 0.1f, 0.3f, 0.6f, 0.9f, 1f)) for (pace in listOf(0f, 0.5f, 1f)) for (fx in listOf(true, false)) {
            val recent = ArrayList<TransitionStyle>()
            repeat(30) {
                val g1 = genres[rnd.nextInt(genres.size)]
                val g2 = if (rnd.nextBoolean()) g1 else genres[rnd.nextInt(genres.size)]
                val out = track(genre = g1, bpm = 70f + rnd.nextFloat() * 110f, energy = rnd.nextFloat(), durationMs = 150_000L + rnd.nextLong(400_000))
                val inn = track(genre = g2, bpm = out.bpm * (0.85f + rnd.nextFloat() * 0.3f), energy = rnd.nextFloat())
                val plan = TransitionPlanner.plan(out, inn, VibeSettings(flair = flair, pace = pace), recent, rnd, fx)
                val label = "${plan.style} flair=$flair pace=$pace fx=$fx ${out.bpm}->${inn.bpm} ${out.energy}->${inn.energy}"
                assertMusical(plan.script, label)
                assertTrue(plan.reasons.size in 1..3, "1-3 reasons: $label ${plan.reasons}")
                val postSeconds = plan.script.post.lengthBeats * 60.0 / out.bpm
                assertTrue(plan.overlapSeconds <= 30f && plan.overlapSeconds >= minOf(30.0, postSeconds + 1.5), "overlap $label ${plan.overlapSeconds}")
                if (!fx) assertTrue(plan.style == TransitionStyle.Blend || plan.style == TransitionStyle.Cut, label)
                recent += plan.style
                count++
            }
        }
        assertTrue(count > 1000)
    }

    @Test
    fun faderScriptMasksTheSeekAndHandsOverToTheListener() {
        for (bpm in tempos) {
            val s = TransitionPlanner.faderScript(track(bpm = bpm), track(bpm = bpm))
            assertTrue(s.manualFader)
            assertMusical(s, "fader bpm=$bpm")
            assertTrue(s.post.lanes.none { it.param == FxParam.Gain || it.param == FxParam.Low }, "fader owns Gain and Low")
            assertEquals(30f, TransitionPlanner.overlapSeconds(s, bpm))
        }
    }

    @Test
    fun blendSwapsTheBassOnTheMiddleBeat() {
        for (beats in listOf(8, 16, 32)) {
            val s = TransitionPlanner.script(TransitionStyle.Blend, track(), track(), beats)
            val r = ScriptRender(s)
            val h = beats / 2.0
            assertEquals(-1f, r.post(Deck.In, FxParam.Low, h - 1.0 / 64))
            assertEquals(0f, r.post(Deck.In, FxParam.Low, h))
            assertTrue(r.post(Deck.Out, FxParam.Low, h - 1.0 / 64) > -0.5f)
            assertEquals(-1f, r.post(Deck.Out, FxParam.Low, h))
            assertEquals(beats.toDouble(), s.post.lengthBeats)
            assertEquals(4.0, s.pre.cues.single { it.kind == CueKind.Seek }.atBeat)
        }
    }

    @Test
    fun samplesAreCuedWhereTheStyleNeedsThem() {
        val out = track(bpm = 90f)
        val riser = TransitionPlanner.script(TransitionStyle.RiserDrop, out, track())
        val riserCue = riser.pre.cues.single { it.kind == CueKind.Sample(SampleId.Riser) }
        // 4 s riser at 90 BPM = 6 beats: it starts on beat 2 so it peaks at the end of the 8-beat build.
        assertEquals(2.0, riserCue.atBeat)
        assertTrue(riser.post.cues.any { it.kind == CueKind.Sample(SampleId.Impact) && it.atBeat == 0.0 })
        val fast = TransitionPlanner.script(TransitionStyle.RiserDrop, track(bpm = 128f), track())
        assertEquals(0.0, fast.pre.cues.single { it.kind == CueKind.Sample(SampleId.Riser) }.atBeat)

        val horn = Scripts.build(TransitionStyle.Cut, out, track(), ScriptOptions(blendBeats = 16, airHorn = true))
        assertTrue(horn.post.cues.any { it.kind == CueKind.Sample(SampleId.AirHorn) && it.atBeat == 0.0 })
        val plain = TransitionPlanner.script(TransitionStyle.Cut, out, track())
        assertTrue(plain.post.cues.isEmpty())
    }

    @Test
    fun brakeLastsAboutASecondAndAHalf() {
        assertEquals(2, Scripts.brakeBeats(85f))
        assertEquals(3, Scripts.brakeBeats(128f))
        assertEquals(4, Scripts.brakeBeats(174f))
        val s = TransitionPlanner.script(TransitionStyle.Brake, track(bpm = 128f), track())
        assertEquals(3.0, s.pre.lengthBeats)
        assertEquals(3f, s.pre.lane(Deck.Out, FxParam.BrakeBeats)!!.points.single().value)
    }

    @Test
    fun loopRollHalvesTheSlice() {
        val s = TransitionPlanner.script(TransitionStyle.LoopRoll, track(), track())
        val r = ScriptRender(s)
        assertEquals(listOf(1f, 1f, 0.5f, 0.25f, 0.125f), listOf(0.0, 1.5, 2.0, 3.0, 3.5).map { r.pre(Deck.Out, FxParam.RollBeats, it) })
        assertEquals(3.0, ScriptRender(s).seekBeat)
    }

    @Test
    fun echoOutLetsTheTailRingIntoTheDrop() {
        val s = TransitionPlanner.script(TransitionStyle.EchoOut, track(), track())
        val r = ScriptRender(s)
        assertEquals(0.9f, r.pre(Deck.Out, FxParam.EchoWet, 3.5))
        assertEquals(0f, r.pre(Deck.Out, FxParam.Dry, 3.0))
        assertEquals(0f, r.pre(Deck.Out, FxParam.EchoSend, 3.0))
        assertEquals(1f, r.post(Deck.In, FxParam.Gain, 0.0))
        assertEquals(0f, r.post(Deck.Out, FxParam.EchoWet, 4.0))
    }

    @Test
    fun overlapIsPostPlusABeatPlusMarginCappedAt30s() {
        val s = TransitionPlanner.script(TransitionStyle.Blend, track(), track(), 16)
        assertEquals(17 * 0.5 + 1.5, TransitionPlanner.overlapSeconds(s, 120f).toDouble(), 1e-4)
        val long = TransitionPlanner.script(TransitionStyle.Blend, track(), track(), 64)
        assertEquals(30f, TransitionPlanner.overlapSeconds(long, 80f))
    }

    @Test
    fun theCheckerCatchesUnmusicalScripts() {
        val good = TransitionPlanner.script(TransitionStyle.Blend, track(), track(), 16)
        assertTrue(ScriptChecks.violations(good).isEmpty())

        fun MixScript.withoutLane(segPost: Boolean, deck: Deck, param: FxParam): MixScript =
            if (segPost) copy(post = post.copy(lanes = post.lanes.filterNot { it.deck == deck && it.param == param }))
            else copy(pre = pre.copy(lanes = pre.lanes.filterNot { it.deck == deck && it.param == param }))

        fun assertCaught(s: MixScript, fragment: String) {
            val v = ScriptChecks.violations(s)
            assertTrue(v.any { fragment in it }, "expected '$fragment' in $v")
        }
        assertCaught(good.withoutLane(false, Deck.Out, FxParam.Roll), "jump audible in pre")
        assertCaught(good.withoutLane(true, Deck.In, FxParam.Low), "bass doubles")
        assertCaught(good.withoutLane(true, Deck.Out, FxParam.Gain), "not silent")
        assertCaught(good.withoutLane(true, Deck.In, FxParam.High).let { s ->
            s.copy(post = s.post.copy(lanes = s.post.lanes + app.turtleplayer.mix.Lane(Deck.In, FxParam.High, listOf(app.turtleplayer.mix.Point(0.0, -0.5f)))))
        }, "High ends at -0.5")
        assertCaught(good.copy(pre = good.pre.copy(cues = emptyList())), "0 Seek cues")
        // A roll that starts on the Seek would loop post-seek audio.
        val late = TransitionPlanner.script(TransitionStyle.Cut, track(), track()).let { s ->
            s.copy(pre = s.pre.copy(cues = listOf(app.turtleplayer.mix.Cue(0.25, CueKind.Seek))))
        }
        assertCaught(late, "jump audible in pre")
    }

    @Test
    fun plansAreDeterministicForASeed() {
        val out = track(id = "a", bpm = 126f, energy = 0.6f)
        val inn = track(id = "b", bpm = 128f, energy = 0.75f, genre = "techno")
        val vibe = VibeSettings(flair = 0.7f)
        val a = (0 until 20).map { TransitionPlanner.plan(out, inn, vibe, emptyList(), Random(it)) }
        val b = (0 until 20).map { TransitionPlanner.plan(out, inn, vibe, emptyList(), Random(it)) }
        assertEquals(a, b)
        assertTrue(a.map { it.style }.toSet().size > 1, "different seeds give different styles")
    }
}
