# turtle-mix specification

Turtle DJ is meant to sound like a paid DJ, not a crossfade setting. This module holds both of its
halves: the **rack** (`app.turtleplayer.mix.rack`) is the DJ's hands, real-time DSP on each deck's
PCM, and the **brain** (`app.turtleplayer.mix.brain`) is its judgement: which track next, how to mix
into it, and when. Shared types live in `Contracts.kt` and are frozen: add new types in your own
files rather than editing it.

Pure Kotlin `commonMain`, no dependencies, no `java.*` (see `turtle/AGENTS.md`). Inject time and
randomness. Run: `./gradlew -p turtle/engines :turtle-mix:jvmTest` (JDK 21 at
`C:\Program Files\Eclipse Adoptium\jdk-21.0.13.10002-hotspot`).

## How the app drives audio (facts the design rests on)

- Every player sends 16-bit interleaved PCM through `ParametricEqualizer.processPcm16`, one
  instance per player pipeline. Our hook calls `MixRack.process(pipeline, samples, count,
  sampleRate, channels)` **first**, in place, before the app's EQ and limiter. `pipeline` is the
  equalizer instance: a stable identity per player, called from that player's audio thread.
- During a crossfade two players run at once, so two pipelines call `process` concurrently from
  two audio threads. Outside crossfades one pipeline runs. A player's pipeline can pause (no calls)
  and resume; after a seek it just continues with later audio.
- The incoming player only starts when the outgoing track is within the crossfade window of its
  end. So to mix early the controller **seeks** the outgoing to `duration - window`, which starts
  the incoming within about 0.3 to 1.5 s. Scripts mask that jump (roll, echo, brake, riser...).
- While a script runs, the app's own crossfade curve is overridden to (1, 1): the rack's Gain
  lanes do all the volume work, sample accurately.
- Buffers are typically 5 to 40 ms. The audio is heard ~100 to 300 ms after processing; the UI
  offsets itself, the rack needs not care.

## Rack

### API (implement exactly; the controller is being written against it)

```kotlin
package app.turtleplayer.mix.rack

class MixRack(nanoTime: () -> Long) {
    /** Audio thread. In place. Never allocates after warm-up, never blocks, never throws. */
    fun process(pipeline: Any, samples: ShortArray, count: Int, sampleRate: Int, channels: Int)

    /** Off: process() returns immediately, all state cleared. Default false. */
    var enabled: Boolean

    /** Pre-renders the sampler's sounds for common rates (44100, 48000) off the audio thread. */
    fun warmUp()

    /** True when any pipeline processed audio in the last 400 ms. */
    val receivingAudio: Boolean

    /** The on-air deck (the outgoing one during a transition) and, mid-transition, the incoming one. */
    val onAir: DeckView?
    val incoming: DeckView?

    /** Tempo to use while a deck's own measurement is not confident (from genre priors). */
    fun setTempoHint(deck: Deck, bpm: Float)

    /** The listener's performance layer on the on-air deck, eased over [rampMs]. */
    fun setUser(param: FxParam, value: Float, rampMs: Int = 30)
    fun resetUser(rampMs: Int = 300)

    /** Fires a sample now or on the next beat/bar of the on-air deck. */
    fun trigger(sample: SampleId, quantize: Quantize = Quantize.None)
    var sampleVolume: Float   // 0..1, default 0.8

    /** Starts [script] on the next [quantize] boundary of the on-air deck. False if one is running. */
    fun run(script: MixScript, quantize: Quantize = Quantize.Bar): Boolean

    /** Eases every script-driven value back to neutral (300 ms) and forgets the script. */
    fun abort()

    /** The listener's crossfader for scripts with manualFader: 0 all Out .. 1 all In; null = untouched. */
    fun setFader(position: Float?)

    val transition: TransitionView

    /** Cues fired since the last call (controller polls every ~20 ms). Seek and Sample cues. */
    fun pollCues(): List<CueKind>

    /** Everything neutral, scripts and decks forgotten (start or end of a set). */
    fun reset()
}

interface DeckView {
    val bpm: Float              // measured when confident, else the hint
    val bpmMeasured: Boolean    // confidence high enough to trust bpm
    val confidence: Float       // 0..1
    val beatPhase: Float        // 0..1 within the current beat (at the last processed sample)
    val beatInBar: Int          // 0..3, 0 = downbeat (best effort)
    val energy: Float           // 0..1 measured: loudness, low end, brightness, onset density
    val loudnessDb: Float       // short-term RMS in dBFS (pre-FX)
    val active: Boolean         // processed audio in the last 250 ms
    val framesAnalyzed: Long    // how much audio the measurements rest on
}

enum class TransitionPhase { Idle, Waiting, Pre, AwaitingIncoming, Post }

interface TransitionView {
    val phase: TransitionPhase
    val beat: Double            // beats into the current segment
    val progress: Float         // 0..1 across pre + post (post counts from incoming start)
    val running: Boolean        // phase != Idle; the crossfade override is on while true
}
```

`DeckView`, `TransitionView` values are read from the UI thread: back them with `@Volatile`
fields (`kotlin.concurrent.Volatile`). Cross-thread hand-offs: use `kotlin.concurrent.atomics`
(Kotlin 2.4, opt in to `ExperimentalAtomicApi`) or single-writer volatile fields. No locks on the
audio path.

### Decks and roles

- Each pipeline gets a deck state (effects, analysis, history buffer). Keep at most 4; evict the
  one idle longest. Buffers are allocated for the deck's sample rate the first time it is seen
  (once per deck: fine) and reused.
- **On air**: with no script, every active pipeline is "on air" and uses the user layer. (Two at
  once only happens in an unplanned crossfade; see below.)
- **During a script**: the pipeline that was on air when the script started is `Out`. The first
  *other* pipeline that becomes active (processes audio after >= 250 ms of silence, or for the
  first time) while the script runs is `In`. Until `post` starts, `In` is muted (output zeroed).
- When `post` ends, `In` becomes the on-air deck; its script values ease to neutral over 200 ms;
  `Out` is forgotten (if it still plays, it is muted until it stops).
- **Unplanned incoming**: a second pipeline becoming active with no script running (the app
  crossfaded on its own) gets no special treatment: both are on air with the user layer.

### Master clock and beats

- Beat timing comes from the clock deck: `Out` while it is active, else `In`, else the on-air
  deck. Beats advance with that deck's processed frames at its tempo (`bpm`, measured or hint).
  Segments count beats from their start on this clock. `Quantize.Beat`/`Bar` wait for the clock
  deck's next beat / downbeat (if `beatInBar` is unreliable, a beat is fine).
- Tempo-synced effects (echo, gate, roll, brake, spin, flanger) use their own deck's tempo.

### Values: layers

For each deck and param the effective value is: the script lane value if a running script has a
lane for that deck and param (held at its last point after the segment, until the script ends),
else (on-air deck only) the user layer value, else neutral. Every effective value is smoothed
(~10 ms one-pole) except `Curve.Step` jumps of Gain, which apply sample accurately with a 2 ms ramp
to avoid clicks. `Roll`, `Brake`, `Spin` are gates (>= 0.5 on).

With `manualFader`: during `post`, `Out` Gain = `cos(x·π/2)`, `In` Gain = `sin(x·π/2)` (x = fader,
smoothed 30 ms), and the bass swaps at the middle: `Out` Low = -1 for x > 0.5, `In` Low = -1 for
x < 0.5 (each eased over 60 ms). `post` then never ends by itself until x reaches 1 for 1 beat
(then it ends normally); its length is only a hint for progress.

### Signal chain per deck (float internally, interleaved, any channel count, 1 or 2 typical)

1. Input → history ring buffer (last 8 s, for roll/brake/spin) and the analyser (mono, pre-FX).
2. Source: live input, or the roll slice, or the brake/spin read head (fractional, interpolated,
   reading history at a rate falling 1 → 0 over BrakeBeats; spin reads backwards starting fast,
   about -3x, decaying to 0 over SpinBeats, with its own low-pass so it sounds like vinyl).
   Leaving roll/brake/spin returns to live input with a 5 ms crossfade ("slip": live audio kept
   moving underneath).
3. EQ: low shelf ~200 Hz, mid peak ~1 kHz (wide), high shelf ~3.5 kHz. -1 must be a real kill
   (at least -30 dB in band). Recompute coefficients per 32-frame block only when a value moved.
4. DJ filter: low-pass (cutoff exponential 20 kHz → 180 Hz over 0 → -1) or high-pass (20 Hz →
   5 kHz over 0 → +1), 24 dB/oct (two biquads), mild resonance (Q ~0.9). Bypassed at |x| < 0.01.
5. Gate: chop at GateBeats synced to the deck's beat phase, 3 ms edges.
6. Flanger: 0.5 to 5 ms modulated delay, LFO one cycle per 8 beats, feedback 0.5, mix = value·0.6.
7. Output = Dry·signal + EchoWet·echo; echo input = EchoSend·signal (post filter). Echo delay =
   EchoBeats at the deck's tempo (glide 50 ms on change); feedback path band-limited (HP 250 Hz,
   LP 5 kHz) so repeats thin out like a DJ mixer's echo. The echo keeps ringing when Dry and
   EchoSend are 0 (that is the echo-out); max delay 2.5 s.
8. × Gain (deck volume) → sampler mix (on-air deck only, once even if two decks are on air) →
   clamp to 16-bit.

When every value is neutral, no sample is playing and nothing is ringing, `process` should only
analyse and keep history (cheap path). Budget: all of it for 10 s of 48 kHz stereo in under
150 ms on the JVM in tests.

### Analysis (per deck, on the input)

- **Tempo**: onset strength envelope (spectral-flux-like: rectified rise of band energies, low band
  weighted) at ~86 Hz frame rate; every ~1.5 s autocorrelate the last ~10 s over 70–180 BPM with a
  log-normal prior around 120 (tempo octave errors resolved toward 85–160), parabolic peak
  interpolation. `confidence` from peak salience; `bpmMeasured` when confidence ≥ 0.45 and
  ≥ 6 s analysed. Smooth across updates (no jumping by octaves).
- **Beat phase**: comb alignment of the onset envelope at the tempo; `beatPhase` advances
  per sample between updates. **Downbeat**: the beat of four with the strongest low-band onsets.
- **Energy** (0..1): combine short-term loudness (dBFS), low-band share, spectral brightness and
  onset density into one number calibrated so a quiet acoustic track reads ~0.2, a pop song
  ~0.55, a club banger ~0.85. Smooth over ~4 s.

### Sampler (synthesized, rendered once per sample rate, mono → all channels)

- **AirHorn**: the reggae-sound-system horn: three blasts (150 ms, 150 ms, 600 ms, 60 ms gaps),
  detuned sawtooth chord around 415 Hz (root, major third, fifth), 40 ms pitch bend into each
  blast, band-limited around 3 kHz, soft-clipped, loud.
- **Siren**: dub siren, square wave swept 600↔1200 Hz at 4 Hz for 1.6 s with echo-friendly tail.
- **Riser**: 4 s white noise through a band-pass sweeping 300 Hz → 9 kHz with rising level and a
  slowly rising sine underneath; designed to end on an Impact.
- **Impact**: sub drop (60 → 35 Hz sine, 1.2 s decay) + noise burst (80 ms) + low thump.
- **Laser**: 250 ms descending sine/saw chirp 2.5 kHz → 200 Hz, three quick repeats.

Several samples may overlap (up to 4 voices). Sample cues inside scripts are played by the rack.

### Tests the rack needs

Synthetic signals only (no fixtures needed): click/kick tracks at 90, 120, 128, 140, 174 BPM →
tempo within ±1.5 BPM (174 may read as 87 or 174 – either is fine, but say which), phase within
40 ms; sine sweeps → EQ kill ≥ 30 dB, filter ±1 attenuations; echo produces a copy at the right
delay; roll repeats the captured slice; brake ends silent; a script's Gain Step lands within 3 ms
of its beat; pre/post/In-muted semantics with two fake pipelines; performance budget; no
allocation in steady state (check by processing 1000 buffers and asserting no new deck state
arrays were created, or similar).

## Brain

```kotlin
package app.turtleplayer.mix.brain

class SetContext(
    val onAir: TrackFacts?,
    val history: List<TrackFacts>,          // oldest first, excluding onAir
    val skipped: Set<String>,               // ids the listener skipped early this set
    val liked: Set<String>,                 // ids liked this set
    val upcoming: List<TrackFacts> = emptyList(), // already planned after onAir, in order
)

object SetPlanner {
    /** Best next track for [targetEnergy] (0..1), or null if [candidates] is empty. Deterministic for a given [random]. */
    fun pick(candidates: List<TrackFacts>, context: SetContext, targetEnergy: Float, vibe: VibeSettings, random: kotlin.random.Random): Pick?

    /** Energy the arc asks for, given the listener's vibe energy (0.5 = the arc as drawn). */
    fun target(arcEnergy: Float, vibe: VibeSettings): Float
}

object TransitionPlanner {
    /** How to mix from [out] into [inn]: style chosen by tempo gap, energy jump, genres, flair; never the same tricky style twice in a row. */
    fun plan(out: TrackFacts, inn: TrackFacts, vibe: VibeSettings, recent: List<TransitionStyle>, random: kotlin.random.Random, fxAvailable: Boolean = true): TransitionPlan

    /** The script for a style the listener chose (manual mode); [beats] overrides the blend length. */
    fun script(style: TransitionStyle, out: TrackFacts, inn: TrackFacts, beats: Int? = null): MixScript

    /** Manual crossfader transition: a short masking pre (roll on the seek) and a manualFader post. */
    fun faderScript(out: TrackFacts, inn: TrackFacts): MixScript
}

object MixPoints {
    /** Media position (ms) to start the transition's pre segment on [track] at [bpm]. */
    fun mixOutAtMs(track: TrackFacts, bpm: Float, pace: Float, preBeats: Double): Long
}
```

### Picking (SetPlanner.pick)

Score every candidate (higher is better), then sample among the top few with a little randomness
so sets differ. Ingredients, each a clear term you can test:

- **Energy fit**: closeness to `targetEnergy` (dominant term). Measured energy is trusted more.
- **Tempo flow**: BPM within ±6 % of `onAir` (or half/double) is a bonus, beyond ±12 % a penalty,
  scaled down for non-dance genres and when the flair is high (tricks hide tempo jumps).
- **Familiarity mix**: keep the running share of Favorite/Liked/Known in the set near
  `1 - vibe.fresh`: favour whichever side is behind.
- **Genre lane**: `variety` low favours `onAir`'s genre; high favours a change after 2 in a row.
- **Spacing**: never the same artist within 4 tracks; avoid re-picking the onAir or history ids.
- **Feedback**: candidates sharing artist or genre with skipped tracks are penalised; sharing an
  artist with liked ones gets a bonus (reason ArtistYouLike).
- **Moments**: when `targetEnergy` ≥ 0.8 and rising, popular favourites get a "PeakMoment" bonus.
- `requested` candidates always win (reason Requested).

Reasons: 1 to 3, most important first: energy direction (BuildingUp if target > onAir energy +0.08,
CoolingDown if < -0.08, else HoldingTheGroove; PeakMoment when applicable), then the strongest of
Favorite/Liked/FreshFind/ArtistYouLike, then TempoMatch or GenreHop when they applied.

### Transitions (TransitionPlanner)

Beats are at the outgoing tempo. Typical shapes (tune by ear and keep them musical):

- **Blend** (dance → dance, tempo within 6 %): pre 4 beats (Out Low eases to -0.3, Seek cue at
  pre end with Out Roll on 1 beat to mask the jump); post 16 beats (8 at low flair, 32 at flair 0
  with long tracks): In Gain 0→1 over 4 beats with In Low = -1; Out High eases down; at beat 8
  bass swap (Step: Out Low -1, In Low 0); Out Gain 1→0 over the last 6; Out Roll off at post beat 1.
- **FilterFade**: pre 8 beats Out Filter 0→-0.7 (EaseIn), Seek cue at the end with a 1-beat
  roll; post 8 beats: In Filter +0.8→0, In Gain 0→1 (4 beats), Out Filter -0.7→-1, Out Gain →0.
- **EchoOut**: pre 4 beats: at beat 2 Out EchoSend 1, EchoWet 0.9, EchoFeedback 0.6, EchoBeats
  0.75; at beat 3 Out Dry Step→0 and EchoSend Step→0 (tail rings) and the Seek cue; post 4
  beats: In drops at beat 0 at full Gain; Out EchoWet fades to 0 over 4 beats.
- **Cut**: pre 1 beat with Seek cue at beat 0 and Out Roll on (masks); post: In Gain Step 1 at 0,
  Out Gain Step 0 at 0. Optional AirHorn at high flair.
- **Spinback**: pre 2 beats: Out Spin on at 0, Seek at 0.5; post 2 beats: In drops at 0.
- **Brake**: pre BrakeBeats (2–4): Out Brake on at 0 (with Filter easing to -0.4), Seek at the end;
  post: In drops at 0 (or fades in over 2 beats when the energy falls a lot).
- **RiserDrop**: pre 8 beats: Riser sample at 0, Out Filter 0→+0.85 EaseIn, Out Gate 0→0.6 in the
  last 2 beats, Seek at 7; post 4 beats: Impact sample at 0, In drops at 0 at full.
- **LoopRoll**: pre 4 beats: Out Roll on at 0 with RollBeats 1 → 0.5 (beat 2) → 0.25 (3) → 0.125
  (3.5), Out Filter rising to +0.5, Seek at 3; post 2 beats: In drops at 0, Out Gain Step 0.

Choice: tempo gap > 8 % (not half/double) or a genre change rules out Blend/FilterFade unless
flair < 0.15; big energy rise → RiserDrop/LoopRoll/Cut; big fall → Brake/EchoOut/FilterFade;
non-dance pairs → EchoOut/Cut/FilterFade/short Blend; flair scales the chance of tricks (Spinback,
Brake, RiserDrop, LoopRoll, AirHorn on cuts). With `fxAvailable = false` only Blend with Gain lanes
and Cut (no other params) are allowed. `overlapSeconds` = post length in seconds at the outgoing
tempo + 1.5 s, at most 30 s.

`MixPoints.mixOutAtMs`: pace 0 → start the pre segment so post ends near the natural end, keeping
roughly the last 8 % (max 25 s) of radio edits out (fade-outs), and for dance tracks the last 32 to
64 beats (DJ outros); pace 1 → about 60–75 s into the track (after a first chorus), never before
45 s or 40 % of the track. Between: interpolate. Always at least 30 s before the end.

### Tests the brain needs

Unit tests for every bullet above (energy fit dominates, tempo flow, familiarity ratio converging
over a simulated 30-track set, artist spacing, skip penalty, request wins, reasons), style choice
across scenarios, every style's script being well-formed (lanes sorted by beat, values in range,
In deck neutral at the end of post, a Seek cue in pre), determinism with a seeded Random.
