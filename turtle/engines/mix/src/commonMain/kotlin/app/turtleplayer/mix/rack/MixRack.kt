@file:OptIn(ExperimentalAtomicApi::class)

package app.turtleplayer.mix.rack

import app.turtleplayer.mix.CueKind
import app.turtleplayer.mix.Deck
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.Quantize
import app.turtleplayer.mix.SampleId
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicIntArray
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicLongArray
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.atomicArrayOfNulls
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Turtle DJ's hands: real-time effects, beat tracking and a sampler on every player's PCM, plus the
 * transport that plays [MixScript]s across the outgoing and incoming decks. See SPEC.md ("Rack").
 *
 * Threading: [process] runs on audio threads (up to two at once, one per player pipeline) and never
 * allocates after warm-up, blocks or throws. Everything else is called from the controller or UI
 * thread; hand-offs go through atomics and single-writer volatile fields.
 *
 * Notes for the controller:
 * - Call [warmUp] once off the audio thread: a sampler bank for an unseen rate is otherwise rendered
 *   on the audio thread the first time a sample plays (one-off, a few ms). Each deck allocates its
 *   buffers (~2 MB at 48 kHz stereo) the first time its pipeline is seen.
 * - Continuity is judged in audio time: each deck estimates how far its delivered audio runs ahead of
 *   playback (capped at 1.5 s), so bursty delivery (ExoPlayer: ~260 ms of buffers, then a ~255 ms
 *   wait) is continuous. Silence = the wall-clock gap beyond that estimate; >= 250 ms of it is a
 *   (re)activation, >= 100 ms a discontinuity (onset history dropped, tempo kept).
 * - `In` is recognised on the incoming pipeline's very first processed buffer (it must have been
 *   silent for >= 250 ms, or be new). Post starts on the first script beat after both pre has ended
 *   and `In` is playing; `In` is muted until then, already sitting at post's first values.
 * - Tempo: [DeckView.bpmMeasured] needs >= 6 s of a deck's audio (first reading after 3 s, then
 *   every 1.5 s); until then [DeckView.bpm] is the role's hint (120 without one). An incoming deck's
 *   analysis restarts when it is recognised as `In`. Tempos above 160 read at half time (174 -> 87)
 *   unless a hint within 6 % of the double is set, which picks that octave.
 * - When post ends, the `In` hint becomes the on-air (`Out`) hint and the `In` hint is cleared.
 * - The master clock follows `Out`, and hands over to `In` 150 ms after `Out`'s delivered audio runs
 *   out (e.g. while a seek rebuffers); with neither playing it pauses. Non-clock decks line their
 *   script beat up with the clock deck on the playback timeline. With no script, the on-air deck
 *   that has played longest drives the clock and hosts the sampler (a preloading player cannot).
 */
class MixRack(private val nanoTime: () -> Long) {

    // ---- decks: a fixed identity map of pipelines ------------------------------------------------
    internal val allocations = AtomicInt(0)

    /** Buffer allocations so far (deck buffers, sampler banks): constant in steady state. */
    internal fun allocationCount(): Int = allocations.load()
    internal val decks: Array<DeckState> = Array(MAX_DECKS) { DeckState(this, it) }
    private val keys = atomicArrayOfNulls<Any>(MAX_DECKS)

    // ---- transport (shared by the audio threads) -------------------------------------------------
    /** (generation shl 3) or phase. Every phase change is a compare-and-set on the whole word. */
    internal val state = AtomicInt(PH_IDLE)
    internal val script = AtomicReference<CompiledScript?>(null)
    @Volatile internal var quantizeMode = Q_BAR
    internal val outDeck = AtomicReference<DeckState?>(null)
    internal val inDeck = AtomicReference<DeckState?>(null)
    internal val retired = AtomicReference<DeckState?>(null)
    internal val clockOwner = AtomicReference<DeckState?>(null)
    internal val clockBeat = AtomicLong(0.0.toRawBits())
    @Volatile internal var clockBpm = 120.0

    /** When the owner's last published beat ([clockBeat]) will be heard; NEVER right after a (re)start. */
    @Volatile internal var clockUntil = NEVER
    internal val postStart = AtomicLong(NAN_BITS)
    internal val cueNextPre = AtomicInt(0)
    internal val cueNextPost = AtomicInt(0)
    @Volatile internal var lastEndKind = END_NORMAL
    internal val faderBits = AtomicInt(Float.NaN.toRawBits())

    // ---- the listener's layer and hints -----------------------------------------------------------
    internal val userTarget = AtomicIntArray(PARAMS) { NEUTRAL[it].toRawBits() }
    internal val userRamp = AtomicIntArray(PARAMS) { 30 }
    internal val userGen = AtomicInt(0)
    internal val hints = AtomicIntArray(2)
    internal val resetGen = AtomicInt(0)

    internal val triggerQ = IntRing(16)
    internal val cueQ = IntRing(64)
    internal val sampler = Sampler(allocations)

    @Volatile private var enabledFlag = false
    @Volatile private var volume = 0.8f

    /** Audio thread. In place. Never allocates after warm-up, never blocks, never throws. */
    fun process(pipeline: Any, samples: ShortArray, count: Int, sampleRate: Int, channels: Int) {
        if (!enabledFlag) return
        var deck: DeckState? = null
        try {
            if (channels < 1 || channels > MAX_CHANNELS || sampleRate < 8000 || sampleRate > 384000) return
            val n = if (count < samples.size) count else samples.size
            val frames = n / channels
            if (frames <= 0) return
            val now = nanoTime()
            deck = lookup(pipeline, now) ?: return
            deck.process(samples, frames, sampleRate, channels, now)
        } catch (t: Throwable) {
            if (deck != null) {
                deck.faulted = true
                clockOwner.compareAndSet(deck, null)
            }
        }
    }

    /** Off: process() returns immediately, all state cleared. Default false. */
    var enabled: Boolean
        get() = enabledFlag
        set(value) {
            if (value == enabledFlag) return
            enabledFlag = value
            reset()
        }

    /** Pre-renders the sampler's sounds for common rates (44100, 48000) off the audio thread. */
    fun warmUp() {
        sampler.bank(44100)
        sampler.bank(48000)
    }

    /** True when any pipeline processed audio in the last 400 ms. */
    val receivingAudio: Boolean
        get() {
            val now = nanoTime()
            for (i in 0 until MAX_DECKS) {
                if (keys.loadAt(i) == null) continue
                val until = decks[i].audibleUntil
                if (until != NEVER && now - until < RECEIVING_NS) return true
            }
            return false
        }

    /** The on-air deck (the outgoing one during a transition) and, mid-transition, the incoming one. */
    val onAir: DeckView?
        get() {
            val ph = state.load() and 7
            if (ph >= PH_PRE) outDeck.load()?.let { return it }
            val now = nanoTime()
            primaryOnAir(now, ACTIVE_NS, ph == PH_WAITING)?.let { return it }
            var best: DeckState? = null
            for (i in 0 until MAX_DECKS) {
                if (keys.loadAt(i) == null) continue
                val d = decks[i]
                if (d.lastNanos == NEVER || d === retired.load()) continue
                if (best == null || d.lastNanos > best.lastNanos) best = d
            }
            return best
        }

    val incoming: DeckView?
        get() = if ((state.load() and 7) != PH_IDLE) inDeck.load() else null

    /** Tempo to use while a deck's own measurement is not confident (from genre priors). */
    fun setTempoHint(deck: Deck, bpm: Float) {
        val v = if (bpm.isFinite() && bpm > 0f) bpm.coerceIn(40f, 250f).toRawBits() else 0
        hints.storeAt(deck.ordinal, v)
    }

    /** The listener's performance layer on the on-air deck, eased over [rampMs]. */
    fun setUser(param: FxParam, value: Float, rampMs: Int = 30) {
        val v = if (value.isFinite()) value.coerceIn(param.min, param.max) else param.neutral
        userRamp.storeAt(param.ordinal, rampMs.coerceIn(0, 60_000))
        userTarget.storeAt(param.ordinal, v.toRawBits())
        userGen.incrementAndFetch()
    }

    fun resetUser(rampMs: Int = 300) {
        for (p in 0 until PARAMS) {
            userRamp.storeAt(p, rampMs.coerceIn(0, 60_000))
            userTarget.storeAt(p, NEUTRAL[p].toRawBits())
        }
        userGen.incrementAndFetch()
    }

    /** Fires a sample now or on the next beat/bar of the on-air deck. */
    fun trigger(sample: SampleId, quantize: Quantize = Quantize.None) {
        triggerQ.push(1 + sample.ordinal + 8 * quantize.ordinal, nanoTime())
    }

    /** 0..1, default 0.8. */
    var sampleVolume: Float
        get() = volume
        set(value) {
            volume = if (value.isFinite()) value.coerceIn(0f, 1f) else 0.8f
        }

    /** Starts [script] on the next [quantize] boundary of the on-air deck. False if one is running. */
    fun run(script: MixScript, quantize: Quantize = Quantize.Bar): Boolean {
        val compiled = try {
            CompiledScript.compile(script)
        } catch (t: Throwable) {
            null
        } ?: return false
        while (true) {
            val st = state.load()
            if ((st and 7) != PH_IDLE) return false
            this.script.store(compiled)
            quantizeMode = when (quantize) {
                Quantize.None -> Q_NONE
                Quantize.Beat -> Q_BEAT
                Quantize.Bar -> Q_BAR
            }
            outDeck.store(null)
            inDeck.store(null)
            postStart.store(NAN_BITS)
            clockBeat.store(0.0.toRawBits())
            clockUntil = NEVER
            cueNextPre.store(0)
            cueNextPost.store(0)
            if (state.compareAndSet(st, nextGen(st) or PH_WAITING)) return true
        }
    }

    /** Eases every script-driven value back to neutral (300 ms) and forgets the script. */
    fun abort() {
        while (true) {
            val st = state.load()
            if ((st and 7) == PH_IDLE) return
            lastEndKind = END_ABORT
            if (state.compareAndSet(st, nextGen(st) or PH_IDLE)) return
        }
    }

    /** The listener's crossfader for scripts with manualFader: 0 all Out .. 1 all In; null = untouched. */
    fun setFader(position: Float?) {
        val v = if (position == null || !position.isFinite()) Float.NaN else position.coerceIn(0f, 1f)
        faderBits.store(v.toRawBits())
    }

    val transition: TransitionView = object : TransitionView {
        override val phase: TransitionPhase
            get() = PHASES[state.load() and 7]

        override val beat: Double
            get() {
                val ph = state.load() and 7
                val b = Double.fromBits(clockBeat.load())
                return when (ph) {
                    PH_PRE, PH_AWAIT -> b
                    PH_POST -> {
                        val ps = Double.fromBits(postStart.load())
                        if (ps.isNaN()) 0.0 else (b - ps).coerceAtLeast(0.0)
                    }
                    else -> 0.0
                }
            }

        override val progress: Float
            get() {
                val ph = state.load() and 7
                val s = script.load() ?: return 0f
                val total = s.pre.length + s.post.length
                if (total <= 0.0) return if (ph == PH_POST) 1f else 0f
                val b = Double.fromBits(clockBeat.load())
                val v = when (ph) {
                    PH_PRE -> b.coerceIn(0.0, s.pre.length) / total
                    PH_AWAIT -> s.pre.length / total
                    PH_POST -> {
                        val ps = Double.fromBits(postStart.load())
                        val pb = if (ps.isNaN()) 0.0 else (b - ps).coerceIn(0.0, s.post.length)
                        val p = (s.pre.length + pb) / total
                        if (s.manualFader) p.coerceAtMost(0.99) else p
                    }
                    else -> 0.0
                }
                return v.toFloat().coerceIn(0f, 1f)
            }

        override val running: Boolean
            get() = (state.load() and 7) != PH_IDLE
    }

    /** Cues fired since the last call (controller polls every ~20 ms). Seek and Sample cues. */
    fun pollCues(): List<CueKind> {
        var out: ArrayList<CueKind>? = null
        while (true) {
            val code = cueQ.pop()
            if (code == 0) break
            val kind: CueKind = when {
                code == CUE_SEEK -> CueKind.Seek
                code >= CUE_SAMPLE_BASE && code - CUE_SAMPLE_BASE < SAMPLE_CUES.size -> SAMPLE_CUES[code - CUE_SAMPLE_BASE]
                else -> continue
            }
            if (out == null) out = ArrayList(4)
            out.add(kind)
        }
        return out ?: emptyList()
    }

    /** Everything neutral, scripts and decks forgotten (start or end of a set). */
    fun reset() {
        while (true) {
            val st = state.load()
            lastEndKind = END_RESET
            if (state.compareAndSet(st, nextGen(st) or PH_IDLE)) break
        }
        script.store(null)
        outDeck.store(null)
        inDeck.store(null)
        retired.store(null)
        clockOwner.store(null)
        postStart.store(NAN_BITS)
        clockBeat.store(0.0.toRawBits())
        clockUntil = NEVER
        for (p in 0 until PARAMS) {
            userTarget.storeAt(p, NEUTRAL[p].toRawBits())
            userRamp.storeAt(p, 0)
        }
        userGen.incrementAndFetch()
        faderBits.store(Float.NaN.toRawBits())
        hints.storeAt(0, 0)
        hints.storeAt(1, 0)
        triggerQ.clear()
        cueQ.clear()
        sampler.clearGen++
        resetGen.incrementAndFetch()
    }

    // ---- internals used by the decks ---------------------------------------------------------------

    internal fun nowNanos(): Long = nanoTime()

    private fun lookup(pipeline: Any, now: Long): DeckState? {
        for (i in 0 until MAX_DECKS) if (keys.loadAt(i) === pipeline) return decks[i]
        for (i in 0 until MAX_DECKS) {
            if (keys.loadAt(i) == null && keys.compareAndSetAt(i, null, pipeline)) {
                decks[i].claim()
                return decks[i]
            }
        }
        // Evict the deck idle the longest (only if it has been idle at least 250 ms).
        var victim = -1
        var oldest = Long.MAX_VALUE
        for (i in 0 until MAX_DECKS) {
            val until = decks[i].audibleUntil
            val idle = until == NEVER || now - until >= ACTIVE_NS
            if (idle && (until == NEVER || until < oldest)) {
                victim = i
                oldest = if (until == NEVER) Long.MIN_VALUE else until
            }
        }
        if (victim < 0) return null
        val old = keys.loadAt(victim)
        if (!keys.compareAndSetAt(victim, old, pipeline)) return null
        val d = decks[victim]
        outDeck.compareAndSet(d, null)
        inDeck.compareAndSet(d, null)
        retired.compareAndSet(d, null)
        clockOwner.compareAndSet(d, null)
        d.claim()
        return d
    }

    internal fun inUse(d: DeckState): Boolean = keys.loadAt(d.slot) != null

    internal fun alive(d: DeckState?, now: Long, window: Long): Boolean {
        if (d == null || d.faulted || !inUse(d)) return false
        // Audio time: alive while its delivered audio plays, and [window] after.
        val until = d.audibleUntil
        return until != NEVER && now - until < window
    }

    internal fun isActive(d: DeckState): Boolean = alive(d, nanoTime(), ACTIVE_NS)

    internal fun anyOtherAlive(me: DeckState, now: Long): Boolean {
        for (i in 0 until MAX_DECKS) {
            val d = decks[i]
            if (d !== me && alive(d, now, ACTIVE_NS)) return true
        }
        return false
    }

    /**
     * The on-air deck that has been playing longest (not retired, not the waiting In). Longest, not
     * newest: a player that briefly preloads or renders the next track must not steal the clock,
     * the sampler or the Out role from the deck that is really on air.
     */
    internal fun primaryOnAir(now: Long, window: Long, excludeIn: Boolean): DeckState? {
        val ret = retired.load()
        val inn = if (excludeIn) inDeck.load() else null
        var best: DeckState? = null
        for (i in 0 until MAX_DECKS) {
            val d = decks[i]
            if (d === ret || d === inn || !alive(d, now, window)) continue
            if (best == null || d.activatedNanos < best.activatedNanos) best = d
        }
        return best
    }

    /** Which deck should drive the master clock (and host the sampler) right now. */
    internal fun preferredClock(now: Long, phase: Int): DeckState? {
        if (phase >= PH_PRE) {
            val o = outDeck.load()
            val i = inDeck.load()
            if (alive(o, now, CLOCK_NS)) return o
            if (alive(i, now, CLOCK_NS)) return i
            return o ?: i
        }
        return primaryOnAir(now, CLOCK_NS, phase == PH_WAITING)
    }

    internal fun clockBeatValue(): Double = Double.fromBits(clockBeat.load())

    /** The script beat that will be heard at wall-clock [heardAt], from the owner's last publish. */
    internal fun alignedClockBeat(heardAt: Long): Double {
        val b = clockBeatValue()
        val u = clockUntil
        if (u == NEVER) return b
        return b - (u - heardAt) * clockBpm / 60e9
    }

    internal fun postStartValue(): Double = Double.fromBits(postStart.load())

    internal fun hint(deckIndex: Int): Double {
        val b = hints.loadAt(deckIndex)
        return if (b == 0) 0.0 else Float.fromBits(b).toDouble()
    }

    internal fun fader(): Float {
        val f = Float.fromBits(faderBits.load())
        return if (f.isNaN()) 0f else f
    }

    /** Waiting -> Pre with [deck] as Out. */
    internal fun startPre(st: Int, deck: DeckState): Boolean {
        if ((st and 7) != PH_WAITING) return false
        outDeck.store(deck)
        clockBeat.store(0.0.toRawBits())
        clockUntil = NEVER
        if (state.compareAndSet(st, (st and 7.inv()) or PH_PRE)) return true
        outDeck.compareAndSet(deck, null)
        return false
    }

    internal fun setPhase(st: Int, phase: Int): Boolean = state.compareAndSet(st, (st and 7.inv()) or phase)

    /** Post ended normally: In goes on air, Out is muted until it stops, In's hint becomes the on-air hint. */
    internal fun endScript(st: Int): Boolean {
        if ((st and 7) != PH_POST) return false
        val prevRetired = retired.load()
        val prevHintOut = hints.loadAt(0)
        val prevHintIn = hints.loadAt(1)
        retired.store(outDeck.load())
        if (prevHintIn != 0) {
            hints.storeAt(0, prevHintIn)
            hints.storeAt(1, 0)
        }
        lastEndKind = END_NORMAL
        if (state.compareAndSet(st, nextGen(st) or PH_IDLE)) return true
        retired.store(prevRetired)
        hints.storeAt(0, prevHintOut)
        hints.storeAt(1, prevHintIn)
        return false
    }

    internal fun emitCue(code: Int, deck: DeckState, rate: Int) {
        cueQ.push(code, 0L)
        if (code >= CUE_SAMPLE_BASE) sampler.start(code - CUE_SAMPLE_BASE, rate)
    }

    internal companion object {
        const val MAX_DECKS = 4
        const val MAX_CHANNELS = 8
        const val NEVER = Long.MIN_VALUE
        const val ACTIVE_NS = 250_000_000L
        const val CLOCK_NS = 150_000_000L
        const val RECEIVING_NS = 400_000_000L
        const val TRIGGER_TTL_NS = 1_000_000_000L

        const val PH_IDLE = 0
        const val PH_WAITING = 1
        const val PH_PRE = 2
        const val PH_AWAIT = 3
        const val PH_POST = 4

        const val Q_NONE = 0
        const val Q_BEAT = 1
        const val Q_BAR = 2

        const val END_NORMAL = 0
        const val END_ABORT = 1
        const val END_RESET = 2

        val NAN_BITS: Long = Double.NaN.toRawBits()

        private val PHASES = arrayOf(
            TransitionPhase.Idle, TransitionPhase.Waiting, TransitionPhase.Pre,
            TransitionPhase.AwaitingIncoming, TransitionPhase.Post, TransitionPhase.Idle,
            TransitionPhase.Idle, TransitionPhase.Idle,
        )
        private val SAMPLE_CUES = Array(SampleId.entries.size) { CueKind.Sample(SampleId.entries[it]) }

        fun nextGen(st: Int): Int = ((st ushr 3) + 1) shl 3
    }
}

/**
 * A small lock-free multi-producer, multi-consumer queue of non-zero ints with a timestamp each.
 * Overflow (the consumer stalls for 16+ items) overwrites; fine for cues and triggers.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class IntRing(capacity: Int) {
    private val size = capacity
    private val mask = capacity - 1
    private val slots = AtomicIntArray(capacity)
    private val stamps = AtomicLongArray(capacity)
    private val write = AtomicInt(0)
    private val read = AtomicInt(0)

    init {
        require(capacity > 0 && (capacity and mask) == 0)
    }

    fun push(v: Int, stamp: Long) {
        val i = write.fetchAndAdd(1) and mask
        stamps.storeAt(i, stamp)
        slots.storeAt(i, v)
    }

    /** One value, or 0 when empty. [lastStamp] holds the popped value's timestamp. */
    fun pop(): Int {
        while (true) {
            val r = read.load()
            val i = r and mask
            val v = slots.loadAt(i)
            if (v == 0) return 0
            val s = stamps.loadAt(i)
            if (read.compareAndSet(r, r + 1)) {
                slots.storeAt(i, 0)
                lastStamp = s
                return v
            }
        }
    }

    @Volatile var lastStamp = 0L
        private set

    fun clear() {
        for (i in 0 until size) slots.storeAt(i, 0)
        read.store(write.load())
    }
}
