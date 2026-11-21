package app.turtleplayer.mix.rack

/**
 * What the UI and the controller can read about one deck. Values are written by the deck's audio
 * thread at the end of each processed buffer and are safe to read from any thread.
 */
interface DeckView {
    /** Measured when confident, else the hint for the deck's role (120 when there is neither). */
    val bpm: Float

    /** Confidence high enough (>= 0.45 over >= 6 s of audio) to trust [bpm]. */
    val bpmMeasured: Boolean

    /** 0..1. */
    val confidence: Float

    /** 0..1 within the current beat, at the last processed sample. */
    val beatPhase: Float

    /** 0..3, 0 = downbeat (best effort). */
    val beatInBar: Int

    /** 0..1 measured: loudness, low end, brightness, onset density. */
    val energy: Float

    /** Short-term RMS in dBFS (pre-FX). */
    val loudnessDb: Float

    /** Processed audio in the last 250 ms. */
    val active: Boolean

    /** How much audio the measurements rest on (frames since the deck's analysis last started). */
    val framesAnalyzed: Long
}

enum class TransitionPhase { Idle, Waiting, Pre, AwaitingIncoming, Post }

interface TransitionView {
    val phase: TransitionPhase

    /** Beats into the current segment. */
    val beat: Double

    /** 0..1 across pre + post (post counts from incoming start). */
    val progress: Float

    /** phase != Idle; the crossfade override is on while true. */
    val running: Boolean
}
