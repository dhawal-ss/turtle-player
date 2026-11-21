package app.turtleplayer.mix.brain

import app.turtleplayer.mix.TrackFacts

/**
 * What the DJ knows about the set so far, for [SetPlanner.pick].
 *
 * The played order is `history + onAir + upcoming`: [history] oldest first (excluding [onAir]), then the
 * track on air, then the tracks already planned after it, in order. The pick is for the slot after the
 * last of those.
 */
class SetContext(
    val onAir: TrackFacts?,
    /** Oldest first, excluding [onAir]. */
    val history: List<TrackFacts>,
    /** Ids the listener skipped early this set. */
    val skipped: Set<String>,
    /** Ids liked this set. */
    val liked: Set<String>,
    /** Already planned after [onAir], in order. */
    val upcoming: List<TrackFacts> = emptyList(),
) {
    /** Every track of the set in play order: history, on air, planned. */
    internal val sequence: List<TrackFacts> = buildList {
        addAll(history)
        onAir?.let { add(it) }
        addAll(upcoming)
    }

    /** The track the pick follows: the last planned one, else the on-air one. */
    internal val previous: TrackFacts? = upcoming.lastOrNull() ?: onAir
}
