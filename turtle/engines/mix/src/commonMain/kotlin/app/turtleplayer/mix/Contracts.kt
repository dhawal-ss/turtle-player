package app.turtleplayer.mix

/*
 * Turtle DJ's shared vocabulary. The rack (app.turtleplayer.mix.rack) plays MixScripts; the brain
 * (app.turtleplayer.mix.brain) writes them and picks tracks. Semantics are specified in
 * turtle/engines/mix/SPEC.md; read it before changing anything here.
 */

/** A deck in a transition: the outgoing (on air when it starts) and the incoming track. */
enum class Deck { Out, In }

/**
 * Every automatable effect parameter of a deck, with its neutral (no effect) value and range.
 * Values outside the range are clamped by the rack.
 */
enum class FxParam(val neutral: Float, val min: Float, val max: Float) {
    /** Deck volume, linear. 0 silent, 1 unity. */
    Gain(1f, 0f, 1.25f),

    /** Three-band EQ: -1 kills the band (about -40 dB), 0 flat, +1 boosts it (+6 dB). */
    Low(0f, -1f, 1f),
    Mid(0f, -1f, 1f),
    High(0f, -1f, 1f),

    /** One-knob DJ filter: -1 full low-pass (about 180 Hz), 0 off, +1 full high-pass (about 5 kHz). */
    Filter(0f, -1f, 1f),

    /** The deck's live signal into the output; 0 with an echo tail ringing is an echo-out. */
    Dry(1f, 0f, 1f),

    /** How much of the deck's signal feeds the echo. */
    EchoSend(0f, 0f, 1f),

    /** Echo return level. */
    EchoWet(0f, 0f, 1f),
    EchoFeedback(0.45f, 0f, 0.92f),

    /** Echo delay in beats of the deck's tempo (0.75 is the classic dotted-eighth DJ echo). */
    EchoBeats(0.75f, 0.125f, 2f),

    /** Stutter gate depth: 0 off, 1 fully chopped. */
    Gate(0f, 0f, 1f),

    /** Gate period in beats. */
    GateBeats(0.25f, 0.0625f, 1f),

    /** Loop roll: on while >= 0.5. Captures from the moment it turns on and repeats that slice. */
    Roll(0f, 0f, 1f),

    /** Loop roll length in beats; changing it while rolling re-slices from the roll's start. */
    RollBeats(0.5f, 0.0625f, 4f),

    /** Tape stop: rising through 0.5 starts a stop lasting [BrakeBeats]; silent while held after; falling resumes live. */
    Brake(0f, 0f, 1f),
    BrakeBeats(2f, 0.25f, 8f),

    /** Backspin: rising through 0.5 spins the record back over [SpinBeats]; silent while held after; falling resumes live. */
    Spin(0f, 0f, 1f),
    SpinBeats(1f, 0.25f, 4f),

    /** Flanger depth (tempo-synced sweep): 0 off. */
    Flanger(0f, 0f, 1f),
}

/** How a value moves from one automation point to the next. */
enum class Curve { Linear, Step, EaseIn, EaseOut, Smooth }

/** One automation point: at [beat] (from the segment's start) the value is [value]; [curve] shapes the move *to* this point. */
data class Point(val beat: Double, val value: Float, val curve: Curve = Curve.Linear)

/** An automation lane: [param] of [deck] over a segment. Before the first point the first value holds; after the last, the last. */
data class Lane(val deck: Deck, val param: FxParam, val points: List<Point>)

/** Sounds the sampler synthesizes (no recordings, so no licences): mixed into the on-air output. */
enum class SampleId { AirHorn, Siren, Riser, Impact, Laser }

sealed interface CueKind {
    /** The controller should now jump the outgoing track to its crossfade window, so the incoming deck starts. */
    data object Seek : CueKind

    /** The rack plays this sample itself at the cue's beat (sample accurate); the controller is told too. */
    data class Sample(val id: SampleId) : CueKind
}

data class Cue(val atBeat: Double, val kind: CueKind)

/** Part of a script, timed in beats from its start. */
data class Segment(val lengthBeats: Double, val lanes: List<Lane> = emptyList(), val cues: List<Cue> = emptyList())

/**
 * A transition as the rack plays it. [pre] runs on the outgoing deck alone (the incoming deck is muted
 * until [post] starts). [post] starts on the first beat after both [pre] has ended and the incoming
 * deck is playing; it times its beats on the master clock. With [manualFader], [post]'s Gain and Low
 * lanes are replaced by the listener's crossfader (MixRack.setFader). At the end of [post] the
 * incoming deck becomes the on-air deck and every parameter of it is eased back to neutral.
 */
data class MixScript(val pre: Segment, val post: Segment, val manualFader: Boolean = false)

/** Where a script or a trigger starts. */
enum class Quantize { None, Beat, Bar }

// ---- The brain's vocabulary --------------------------------------------------------------------

enum class Familiarity { Favorite, Liked, Known, New }

/** What the DJ knows about a track. BPM and energy are measured when [bpmMeasured]/[energyMeasured], else estimates. */
data class TrackFacts(
    val id: String,
    val artist: String,
    val genre: String,
    val bpm: Float,
    val bpmMeasured: Boolean,
    val energy: Float,
    val energyMeasured: Boolean,
    val durationMs: Long,
    val familiarity: Familiarity,
    /** 0..1: how well known the track is (search rank, play counts). */
    val popularity: Float,
    /** Four-on-the-floor genres (house, techno, ...) that blend long; others prefer short moves. */
    val dance: Boolean,
    val requested: Boolean = false,
)

/** The listener's tuning of the set. All 0..1. */
data class VibeSettings(
    /** Shifts the energy arc: 0 calmer than the arc, 0.5 as drawn, 1 wilder. */
    val energy: Float = 0.5f,
    /** 0 favourites only .. 1 all new music. */
    val fresh: Float = 0.4f,
    /** 0 smooth long blends .. 1 tricks: echo-outs, drops, spinbacks, risers. */
    val flair: Float = 0.5f,
    /** 0 full songs .. 1 quick mix (about a minute of each song). */
    val pace: Float = 0.25f,
    /** 0 stay in one genre lane .. 1 hop between the chosen genres. */
    val variety: Float = 0.4f,
)

/** Why the DJ picked a track; the UI turns these into words. */
enum class PickReason {
    BuildingUp, CoolingDown, HoldingTheGroove, PeakMoment,
    Favorite, Liked, FreshFind, TempoMatch, GenreHop, Requested, ArtistYouLike,
}

data class Pick(val track: TrackFacts, val score: Double, val reasons: List<PickReason>)

enum class TransitionStyle {
    /** EQ blend with a bass swap, 8 to 32 beats. */
    Blend,

    /** Low-pass the outgoing away while the incoming opens from a high-pass. */
    FilterFade,

    /** The outgoing echoes out; the incoming drops on the bar. */
    EchoOut,

    /** A clean cut on the downbeat. */
    Cut,

    /** The outgoing spins back; the incoming drops. */
    Spinback,

    /** Tape stop on the outgoing; the incoming drops. */
    Brake,

    /** A noise riser and a rising high-pass build; impact; the incoming drops. */
    RiserDrop,

    /** The outgoing's last beat rolls, getting shorter (1, 1/2, 1/4, 1/8): the incoming drops. */
    LoopRoll,
}

enum class TransitionReason { TempoClose, TempoFar, EnergyJumpUp, EnergyDropDown, GenreChange, SameGroove, YourStyle, QuickMix }

data class TransitionPlan(
    val style: TransitionStyle,
    val script: MixScript,
    /** Seconds the incoming must overlap the outgoing for [script]'s post segment at the planned tempo, margin included. */
    val overlapSeconds: Float,
    val reasons: List<TransitionReason>,
)
