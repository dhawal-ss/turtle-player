package app.turtleplayer.mix.brain

import app.turtleplayer.mix.Familiarity
import app.turtleplayer.mix.Pick
import app.turtleplayer.mix.PickReason
import app.turtleplayer.mix.TrackFacts
import app.turtleplayer.mix.VibeSettings
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

/**
 * Picks the next track of a set like a DJ reading the room: energy first, then tempo flow, the
 * favourites/fresh-finds balance, the genre lane, artist spacing and what the listener skipped or liked.
 *
 * Every candidate gets a score that is the sum of clear terms ([ScoreBreakdown]); the pick samples
 * among the top few so two sets never come out identical, deterministically for a given Random.
 */
object SetPlanner {

    // ---- Weights (see ScoreBreakdown for what each term means) --------------------------------

    /** Per unit of |energy - target|: the dominant term (0.1 off costs 0.6). */
    internal const val ENERGY_WEIGHT = 6.0

    /** Unmeasured energies are estimates: their distance counts 70 %, plus a flat doubt cost. */
    internal const val UNMEASURED_DISTANCE_SHARE = 0.7
    internal const val UNMEASURED_DOUBT = 0.04

    /** Tempo within 6 % (or half/double): bonus; 6-12 %: fades to 0; beyond 12 %: penalty. */
    internal const val TEMPO_BONUS = 0.5
    internal const val TEMPO_PENALTY_SLOPE = 5.0
    internal const val TEMPO_PENALTY_MAX = 1.2

    /** Tempo matters less outside four-on-the-floor pairs, and less when tricks will hide the jump. */
    internal const val TEMPO_NON_DANCE_SCALE = 0.35
    internal const val TEMPO_FLAIR_RELIEF = 0.6

    /** Per unit of familiarity share deficit (desired - running share). */
    internal const val FAMILIARITY_WEIGHT = 3.0

    /** Genre lane: same-genre bonus is max(0, 0.5 - variety)·this; a hop after 2+ in a row earns variety·[GENRE_HOP_WEIGHT]. */
    internal const val GENRE_LANE_WEIGHT = 0.8
    internal const val GENRE_HOP_WEIGHT = 0.5

    /** Never the same artist within this many tracks. */
    const val ARTIST_SPACING = 4
    internal const val ARTIST_SPACING_PENALTY = -100.0
    internal const val REPLAY_PENALTY = -50.0

    internal const val SKIPPED_ARTIST_PENALTY = -1.5
    internal const val SKIPPED_GENRE_PENALTY = -0.3
    internal const val SKIPPED_TRACK_PENALTY = -3.0
    internal const val LIKED_ARTIST_BONUS = 0.45

    internal const val PEAK_BASE = 0.3
    internal const val PEAK_POPULARITY = 0.4

    internal const val REQUESTED_BONUS = 1000.0

    /** Sampling: among the top [TOP_FEW] within [TOP_MARGIN] of the best, weighted exp(Δ/[TEMPERATURE]). */
    internal const val TOP_FEW = 3
    internal const val TOP_MARGIN = 0.3
    internal const val TEMPERATURE = 0.12

    /** Energy direction threshold for reasons. */
    internal const val DIRECTION_THRESHOLD = 0.08

    fun pick(
        candidates: List<TrackFacts>,
        context: SetContext,
        targetEnergy: Float,
        vibe: VibeSettings,
        random: Random,
    ): Pick? {
        if (candidates.isEmpty()) return null
        val target = targetEnergy.coerceIn(0f, 1f).toDouble()
        val facts = SetFacts(context)
        val scored = candidates.mapIndexed { i, c -> i to explain(c, facts, target, vibe) }
            .sortedWith(compareByDescending<Pair<Int, ScoreBreakdown>> { it.second.total }.thenBy { it.first })
            .map { it.second }

        val chosen = scored.firstOrNull { it.track.requested } ?: run {
            val best = scored.first().total
            val few = scored.take(TOP_FEW).filter { it.total >= best - TOP_MARGIN }
            val weights = few.map { exp((it.total - best) / TEMPERATURE) }
            var r = random.nextDouble() * weights.sum()
            var idx = 0
            while (idx < few.size - 1 && r >= weights[idx]) {
                r -= weights[idx]
                idx++
            }
            few[idx]
        }
        return Pick(chosen.track, chosen.total, reasons(chosen, facts, target))
    }

    /** Energy the arc asks for: vibe.energy 0.5 = the arc as drawn; 1 lifts it halfway to the ceiling, 0 halves it. */
    fun target(arcEnergy: Float, vibe: VibeSettings): Float {
        val arc = arcEnergy.coerceIn(0f, 1f)
        val shift = vibe.energy.coerceIn(0f, 1f) - 0.5f
        val t = if (shift >= 0f) arc + (1f - arc) * shift else arc + arc * shift
        return t.coerceIn(0f, 1f)
    }

    // ---- Scoring ------------------------------------------------------------------------------

    /** Derived facts about the set, computed once per pick. */
    internal class SetFacts(val context: SetContext) {
        val previous: TrackFacts? = context.previous
        val sequence: List<TrackFacts> = context.sequence
        val playedIds: Set<String> = sequence.mapTo(HashSet()) { it.id }
        val recentArtists: Set<String> = sequence.takeLast(ARTIST_SPACING).flatMapTo(HashSet()) { MusicMath.artistKeys(it.artist) }
        val skippedTracks: List<TrackFacts> = sequence.filter { it.id in context.skipped }
        val skippedArtists: Set<String> = skippedTracks.flatMapTo(HashSet()) { MusicMath.artistKeys(it.artist) }
        val likedArtists: Set<String> = sequence.filter { it.id in context.liked }.flatMapTo(HashSet()) { MusicMath.artistKeys(it.artist) }
        val familiarCount: Int = sequence.count { isFamiliar(it) }

        /** How many tracks in a row (ending with [previous]) share its genre. */
        val genreRun: Int = run {
            val prev = previous ?: return@run 0
            sequence.asReversed().takeWhile { MusicMath.sameGenre(it, prev) }.count()
        }

        /** Running familiar share, smoothed toward [desired] so the first picks are not jerked around. */
        fun familiarShare(desired: Double): Double = (familiarCount + desired * 2.0) / (sequence.size + 2.0)
    }

    /** One candidate's score, term by term. */
    internal data class ScoreBreakdown(
        val track: TrackFacts,
        val energy: Double,
        val tempo: Double,
        val familiarity: Double,
        val genre: Double,
        val spacing: Double,
        val feedback: Double,
        val peak: Double,
        val requested: Double,
        val likedArtist: Boolean,
        val tempoMatched: Boolean,
        val genreHop: Boolean,
    ) {
        val total: Double = energy + tempo + familiarity + genre + spacing + feedback + peak + requested
    }

    internal fun explain(candidate: TrackFacts, context: SetContext, targetEnergy: Float, vibe: VibeSettings): ScoreBreakdown =
        explain(candidate, SetFacts(context), targetEnergy.coerceIn(0f, 1f).toDouble(), vibe)

    private fun explain(c: TrackFacts, f: SetFacts, target: Double, vibe: VibeSettings): ScoreBreakdown {
        val prev = f.previous

        // Energy fit.
        val d = abs(c.energy.coerceIn(0f, 1f) - target)
        val energy = -ENERGY_WEIGHT * if (c.energyMeasured) d else UNMEASURED_DISTANCE_SHARE * d + UNMEASURED_DOUBT

        // Tempo flow.
        var tempo = 0.0
        var tempoMatched = false
        if (prev != null) {
            val gap = MusicMath.tempoGap(prev.bpm, c.bpm)
            val raw = when {
                gap <= 0.06 -> TEMPO_BONUS
                gap <= 0.12 -> TEMPO_BONUS * (0.12 - gap) / 0.06
                else -> -minOf(TEMPO_PENALTY_MAX, (gap - 0.12) * TEMPO_PENALTY_SLOPE)
            }
            val scale = (if (prev.dance && c.dance) 1.0 else TEMPO_NON_DANCE_SCALE) *
                (1.0 - TEMPO_FLAIR_RELIEF * vibe.flair.coerceIn(0f, 1f))
            tempo = raw * scale
            tempoMatched = gap <= 0.06 && tempo >= 0.15
        }

        // Familiarity mix.
        val desired = 1.0 - vibe.fresh.coerceIn(0f, 1f)
        val deficit = desired - f.familiarShare(desired)
        var familiarity = FAMILIARITY_WEIGHT * deficit * (if (isFamiliar(c)) 1.0 else -1.0)
        familiarity += when (c.familiarity) {
            Familiarity.Favorite -> 0.08
            Familiarity.Liked -> 0.04
            else -> 0.0
        }

        // Genre lane.
        var genre = 0.0
        var genreHop = false
        if (prev != null) {
            val variety = vibe.variety.coerceIn(0f, 1f).toDouble()
            if (MusicMath.sameGenre(prev, c)) {
                // Low variety keeps the lane; high variety is neutral here (the hop bonus does the work).
                genre = maxOf(0.0, 0.5 - variety) * GENRE_LANE_WEIGHT
            } else if (f.genreRun >= 2) {
                genre = variety * GENRE_HOP_WEIGHT
                genreHop = genre >= 0.15
            }
        }

        // Spacing.
        var spacing = 0.0
        if (MusicMath.artistKeys(c.artist).any { it in f.recentArtists }) spacing += ARTIST_SPACING_PENALTY
        if (c.id in f.playedIds) spacing += REPLAY_PENALTY

        // Feedback.
        var feedback = 0.0
        if (c.id in f.context.skipped) feedback += SKIPPED_TRACK_PENALTY
        val keys = MusicMath.artistKeys(c.artist)
        if (keys.any { it in f.skippedArtists }) feedback += SKIPPED_ARTIST_PENALTY
        val skippedInGenre = f.skippedTracks.count { MusicMath.sameGenre(it, c) }.coerceAtMost(2)
        feedback += SKIPPED_GENRE_PENALTY * skippedInGenre
        val likedArtist = keys.any { it in f.likedArtists }
        if (likedArtist) feedback += LIKED_ARTIST_BONUS

        // Moments.
        val rising = prev == null || target > prev.energy + 0.02
        val peak = if (target >= 0.8 && rising && c.energy >= 0.7f && isFamiliar(c) && c.popularity >= 0.6f) {
            PEAK_BASE + PEAK_POPULARITY * c.popularity
        } else 0.0

        val requested = if (c.requested) REQUESTED_BONUS else 0.0

        return ScoreBreakdown(c, energy, tempo, familiarity, genre, spacing, feedback, peak, requested, likedArtist, tempoMatched, genreHop)
    }

    // ---- Reasons ------------------------------------------------------------------------------

    private fun reasons(s: ScoreBreakdown, f: SetFacts, target: Double): List<PickReason> {
        val out = ArrayList<PickReason>(3)
        val c = s.track
        if (c.requested) out += PickReason.Requested

        // Energy direction: what the arc asks for, confirmed by what the track actually does.
        val prev = f.previous
        if (s.peak > 0.0) {
            out += PickReason.PeakMoment
        } else if (prev != null && !c.requested) {
            val delta = c.energy - prev.energy
            val wantsUp = target > prev.energy + DIRECTION_THRESHOLD
            val wantsDown = target < prev.energy - DIRECTION_THRESHOLD
            out += when {
                wantsUp && delta > 0.03 -> PickReason.BuildingUp
                wantsDown && delta < -0.03 -> PickReason.CoolingDown
                abs(delta) <= 0.1 -> PickReason.HoldingTheGroove
                delta > 0 -> PickReason.BuildingUp
                else -> PickReason.CoolingDown
            }
        }

        // The strongest relationship with the listener.
        when {
            s.likedArtist && c.familiarity != Familiarity.Favorite -> PickReason.ArtistYouLike
            c.familiarity == Familiarity.Favorite -> PickReason.Favorite
            c.familiarity == Familiarity.Liked -> PickReason.Liked
            c.familiarity == Familiarity.New -> PickReason.FreshFind
            else -> null
        }?.let { out += it }

        // How it fits the flow.
        if (s.tempoMatched) out += PickReason.TempoMatch
        if (s.genreHop) out += PickReason.GenreHop

        if (out.isEmpty()) out += PickReason.HoldingTheGroove
        return out.distinct().take(3)
    }

    internal fun isFamiliar(t: TrackFacts): Boolean = t.familiarity != Familiarity.New
}
