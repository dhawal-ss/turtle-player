package app.turtleplayer.mix.brain

import app.turtleplayer.mix.Familiarity
import app.turtleplayer.mix.TrackFacts
import app.turtleplayer.mix.VibeSettings
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/** Whole sets picked from a synthetic pool along an energy arc. */
class SetSimulationTest {

    /** 0.4 -> 0.9 (pick 18) -> 0.5 (pick 29). */
    private fun arc(i: Int): Float = if (i <= 18) 0.4f + 0.5f * i / 18f else 0.9f - 0.4f * (i - 18) / 11f

    private class SetRun(val tracks: List<TrackFacts>, val targets: List<Float>) {
        val energyError = tracks.zip(targets).map { (t, e) -> abs(t.energy - e).toDouble() }.average()
        val familiarShare = tracks.count { it.familiarity != Familiarity.New }.toDouble() / tracks.size
        val tempoGaps = tracks.zipWithNext().map { (a, b) -> MusicMath.tempoGap(a.bpm, b.bpm) }
        fun spacingViolations(): Int = tracks.indices.count { i ->
            val keys = MusicMath.artistKeys(tracks[i].artist)
            tracks.subList(maxOf(0, i - SetPlanner.ARTIST_SPACING), i).any { p -> MusicMath.artistKeys(p.artist).any { it in keys } }
        }
    }

    private fun runSet(seed: Int, vibe: VibeSettings, genres: List<String>? = null, picks: Int = 30): SetRun {
        val rnd = Random(seed)
        val pool = (if (genres == null) syntheticPool(seed) else syntheticPool(seed, genres = genres)).toMutableList()
        val played = ArrayList<TrackFacts>()
        val targets = ArrayList<Float>()
        for (i in 0 until picks) {
            val target = SetPlanner.target(arc(i), vibe)
            val ctx = SetContext(played.lastOrNull(), played.dropLast(1), emptySet(), emptySet())
            val pick = SetPlanner.pick(pool, ctx, target, vibe, rnd)!!
            pool.remove(pick.track)
            played += pick.track
            targets += target
        }
        return SetRun(played, targets)
    }

    @Test
    fun setsFollowTheArcKeepArtistsApartAndMixFamiliarity() {
        val report = StringBuilder("Set simulation (30 picks, arc 0.4 -> 0.9 -> 0.5, 5 seeds each):\n")
        for (fresh in listOf(0.2f, 0.4f, 0.5f, 0.8f)) for (flair in listOf(0.1f, 0.5f, 0.9f)) {
            val vibe = VibeSettings(fresh = fresh, flair = flair)
            val runs = (1..5).map { runSet(it, vibe) }
            for ((i, r) in runs.withIndex()) {
                assertTrue(r.spacingViolations() == 0, "artist spacing violated, seed ${i + 1}")
                assertTrue(r.energyError < 0.06, "fresh $fresh flair $flair seed ${i + 1}: mean energy error ${r.energyError}")
                assertTrue(abs(r.familiarShare - (1 - fresh)) <= 0.15, "fresh $fresh flair $flair seed ${i + 1}: familiar share ${r.familiarShare}")
            }
            report.append(
                "  fresh $fresh flair $flair: energy error %.3f, familiar share %.2f (want %.2f), tempo jumps <= 8%%: %.0f%%\n".fmt(
                    runs.map { it.energyError }.average(), runs.map { it.familiarShare }.average(), 1.0 - fresh,
                    100.0 * runs.flatMap { it.tempoGaps }.count { it <= 0.08 } / runs.sumOf { it.tempoGaps.size },
                ),
            )
        }
        println(report)
    }

    @Test
    fun danceSetsAtLowFlairKeepTheTempo() {
        val report = StringBuilder("Dance sets, tempo flow:\n")
        for (flair in listOf(0.1f, 0.9f)) {
            val runs = (1..5).map { runSet(it, VibeSettings(flair = flair), genres = listOf("house", "techno", "disco")) }
            val gaps = runs.flatMap { it.tempoGaps }
            val within = gaps.count { it <= 0.08 }.toDouble() / gaps.size
            report.append("  flair $flair: within 8%% %.0f%%, mean energy error %.3f\n".fmt(within * 100, runs.map { it.energyError }.average()))
            if (flair < 0.5f) assertTrue(within >= 0.8, "flair $flair: only ${within * 100}% of tempo jumps within 8%")
            runs.forEach { assertTrue(it.energyError < 0.06) }
        }
        println(report)
    }

    @Test
    fun skippingAnArtistKeepsThemOut() {
        val pool = syntheticPool(11, genres = listOf("house"))
        val vibe = VibeSettings()
        val played = ArrayList<TrackFacts>()
        val skipped = HashSet<String>()
        val rnd = Random(5)
        val avoided = HashSet<String>()
        repeat(25) { i ->
            val p = SetPlanner.pick(pool - played.toSet(), SetContext(played.lastOrNull(), played.dropLast(1), skipped, emptySet()), arc(i), vibe, rnd)!!
            assertTrue(MusicMath.artistKeys(p.track.artist).none { it in avoided }, "picked a skipped artist again at $i")
            if (i % 4 == 1) {
                skipped += p.track.id
                avoided += MusicMath.artistKeys(p.track.artist)
            }
            played += p.track
        }
    }

    private fun String.fmt(vararg args: Any): String {
        var i = 0
        return Regex("%(\\.\\d)?([fd%])").replace(this) { m ->
            when (m.groupValues[2]) {
                "%" -> "%"
                else -> {
                    val v = (args[i++] as Number).toDouble()
                    val digits = m.groupValues[1].drop(1).toIntOrNull() ?: 0
                    val scale = (1..digits).fold(1.0) { acc, _ -> acc * 10 }
                    val r = kotlin.math.round(v * scale) / scale
                    if (digits == 0) r.toLong().toString() else r.toString().let { s ->
                        val dot = s.indexOf('.')
                        if (dot < 0) s + "." + "0".repeat(digits) else s.padEnd(dot + 1 + digits, '0')
                    }
                }
            }
        }
    }
}
