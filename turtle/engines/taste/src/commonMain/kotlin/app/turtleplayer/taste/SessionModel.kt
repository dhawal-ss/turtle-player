package app.turtleplayer.taste

import app.turtleplayer.core.TrackKey
import app.turtleplayer.events.PlayEvent

data class SessionConfig(
    /** Per-event decay of older evidence: after 5 tracks a play weighs about a third. */
    val decay: Float = 0.8f,
    /** How hard a quick skip pushes away, relative to a finish pulling in. */
    val skipPenalty: Float = 0.5f,
    /** Extra pull for a like or replay on top of finishing. */
    val engagementBonus: Float = 0.5f,
    /** Quick skips in a row that count as a vibe change. */
    val vibeChangeSkips: Int = 3,
    /** Weight of the long-term taste after a vibe change, relative to the (decayed) session. */
    val vibeResetMass: Double = 2.0,
    /** What remains of the session's own evidence after a vibe change. */
    val vibeKeep: Float = 0.2f,
)

/**
 * Real-time session model. The session vector is a decaying average of finished tracks minus
 * tracks skipped in the first 30 seconds. Three quick skips in a row trigger a vibe change: the
 * session resets toward the listener's long-term taste. Mood chips nudge it toward a target.
 *
 * Not thread-safe; the engine serializes access.
 */
class SessionModel(
    val dim: Int = EMBEDDING_DIM,
    private val config: SessionConfig = SessionConfig(),
) {
    private val positive = DecayingVectorSum(dim)
    private val negative = DecayingVectorSum(dim)
    private var mood: FloatArray? = null
    private var moodStrength = 0f

    /** Long-term taste to fall back on when the session is empty or the vibe changes. */
    var longTerm: FloatArray? = null

    var consecutiveQuickSkips = 0
        private set
    var vibeChanges = 0
        private set

    /** Tracks finished in this session, most recent last. */
    val finished = ArrayList<TrackKey>()

    /** Tracks quick-skipped in this session; never re-proposed within it. */
    val quickSkipped = LinkedHashSet<TrackKey>()

    /** Most recent event's track, if any. */
    var lastTrack: TrackKey? = null
        private set

    /**
     * Updates the model with one play. [vector] is the track's representation (null if unknown:
     * the counters still update). Returns true if this event triggered a vibe change.
     */
    fun onPlay(event: PlayEvent, vector: FloatArray?): Boolean {
        lastTrack = event.trackKey
        if (event.isQuickSkip || event.lessLikeThis) {
            consecutiveQuickSkips++
            quickSkipped += event.trackKey
            if (vector != null) {
                positive.decay(config.decay)
                negative.decay(config.decay)
                negative.add(vector, 1f)
            }
            if (consecutiveQuickSkips >= config.vibeChangeSkips) {
                vibeChange()
                return true
            }
            return false
        }
        if (event.isFinished || event.isListen) consecutiveQuickSkips = 0
        if (event.isFinished) {
            finished += event.trackKey
            if (vector != null) {
                positive.decay(config.decay)
                negative.decay(config.decay)
                var w = 1f
                if (event.liked || event.replayed || event.addedToPlaylist) w += config.engagementBonus
                positive.add(vector, w)
            }
        }
        return false
    }

    /** Reset toward long-term taste, keeping a little of the session so it does not feel random. */
    fun vibeChange() {
        consecutiveQuickSkips = 0
        vibeChanges++
        val lt = longTerm
        if (lt != null) {
            val sum = positive.sum.copyOf().scaleInPlace(config.vibeKeep).addScaled(lt.normalized(), config.vibeResetMass.toFloat())
            positive.setRaw(sum, positive.mass * config.vibeKeep + config.vibeResetMass)
        } else {
            // No long-term taste yet (cold start): forget the session and start fresh.
            positive.clear()
        }
        negative.clear()
    }

    /** Mood chip: blend [target] into the session vector with [strength] in 0..1; null clears it. */
    fun setMood(target: FloatArray?, strength: Float = 0.5f) {
        require(target == null || target.size == dim) { "Mood vector dim ${target?.size}, expected $dim" }
        mood = target?.normalized()
        moodStrength = strength.coerceIn(0f, 1f)
    }

    /** Seed the session (radio, or the track the user tapped to start). */
    fun seed(vector: FloatArray, mass: Double = 2.0) {
        positive.set(vector, mass)
        negative.clear()
        consecutiveQuickSkips = 0
    }

    fun reset() {
        positive.clear()
        negative.clear()
        finished.clear()
        quickSkipped.clear()
        consecutiveQuickSkips = 0
        lastTrack = null
    }

    /** Unit session vector, or [longTerm] (with mood) when the session has no evidence yet. */
    fun vector(): FloatArray? {
        val base: FloatArray? = if (positive.mass > 0 && !positive.sum.isZero()) {
            val v = positive.sum.normalized()
            if (negative.mass > 0) {
                v.addScaled(negative.sum.normalized(), -config.skipPenalty * (negative.mass / (negative.mass + positive.mass)).toFloat())
            }
            v.normalizeInPlace().takeIf { !it.isZero() }
        } else {
            longTerm?.normalized()
        }
        val m = mood ?: return base
        if (base == null) return m.copyOf()
        return base.scaleInPlace(1f - moodStrength).addScaled(m, moodStrength).normalizeInPlace()
    }
}
