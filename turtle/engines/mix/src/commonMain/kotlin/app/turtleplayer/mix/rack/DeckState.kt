@file:OptIn(ExperimentalAtomicApi::class)

package app.turtleplayer.mix.rack

import app.turtleplayer.mix.rack.MixRack.Companion.ACTIVE_NS
import app.turtleplayer.mix.rack.MixRack.Companion.END_ABORT
import app.turtleplayer.mix.rack.MixRack.Companion.NEVER
import app.turtleplayer.mix.rack.MixRack.Companion.PH_AWAIT
import app.turtleplayer.mix.rack.MixRack.Companion.PH_POST
import app.turtleplayer.mix.rack.MixRack.Companion.PH_PRE
import app.turtleplayer.mix.rack.MixRack.Companion.PH_WAITING
import app.turtleplayer.mix.rack.MixRack.Companion.Q_BEAT
import app.turtleplayer.mix.rack.MixRack.Companion.Q_NONE
import app.turtleplayer.mix.rack.MixRack.Companion.TRIGGER_TTL_NS
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One player pipeline: its history, analysis, effect chain and transport view. Everything here is
 * touched only by that pipeline's audio thread, except the @Volatile view fields (read by the UI) and
 * the fields the rack reads to pick roles (lastNanos, activatedNanos, faulted).
 */
internal class DeckState(private val rack: MixRack, val slot: Int) : DeckView {

    // ---- views (single writer: this deck's audio thread) ----------------------------------------
    @Volatile override var bpm: Float = 120f
        private set
    @Volatile override var bpmMeasured: Boolean = false
        private set
    @Volatile override var confidence: Float = 0f
        private set
    @Volatile override var beatPhase: Float = 0f
        private set
    @Volatile override var beatInBar: Int = 0
        private set
    @Volatile override var energy: Float = 0f
        private set
    @Volatile override var loudnessDb: Float = -90f
        private set
    @Volatile override var framesAnalyzed: Long = 0L
        private set
    override val active: Boolean get() = rack.isActive(this)

    @Volatile var lastNanos: Long = NEVER

    /** Wall-clock time when the audio delivered so far will have been played (delivery runs ahead in bursts). */
    @Volatile var audibleUntil: Long = NEVER
    @Volatile var activatedNanos: Long = NEVER
    @Volatile var faulted: Boolean = false
    @Volatile private var pendingInit = true
    private var resetSeen = -1

    fun claim() {
        lastNanos = NEVER
        audibleUntil = NEVER
        activatedNanos = NEVER
        faulted = false
        pendingInit = true
    }

    // ---- format and buffers ----------------------------------------------------------------------
    private var sr = 0
    private var ch = 0
    private var hist = ShortArray(0)
    private var histFrames = 0
    private var histMask = 0L
    private var framesIn = 0L
    private var x = DoubleArray(0)
    private var y = DoubleArray(0)
    private val samp = DoubleArray(BLOCK)
    private val tmp = DoubleArray(MixRack.MAX_CHANNELS)
    internal var tracker: BeatTracker? = null
        private set
    private val onePole = DoubleArray(BLOCK + 1)
    private val onePoleFader = DoubleArray(BLOCK + 1)

    // ---- parameters --------------------------------------------------------------------------------
    private val cur = FloatArray(PARAMS)
    private val tgt = FloatArray(PARAMS)
    private val glide = IntArray(PARAMS)
    private val userCur = FloatArray(PARAMS)
    private val userTgt = FloatArray(PARAMS)
    private val userStep = FloatArray(PARAMS)
    private var userGenSeen = Int.MIN_VALUE
    private var gainPrev = 1f
    private var lastGainTgt = 1f
    private var lastLowKill = false
    private var snapNext = true

    // ---- EQ, filter -------------------------------------------------------------------------------
    private val eqC = DoubleArray(15)
    private var eqS = DoubleArray(0)
    private val eqVal = FloatArray(3)
    private val eqOn = BooleanArray(3)
    private val fC = DoubleArray(10)
    private var fS = DoubleArray(0)
    private var fOn = false
    private var fType = 0
    private var fVal = 0f

    // ---- gate, flanger, echo ---------------------------------------------------------------------
    private var gateEnv = 1.0
    private var gateStep = 0.0
    private var fl = DoubleArray(0)
    private var flLen = 0
    private var flW = 0
    private var flOn = false
    private var ec = DoubleArray(0)
    private var ecLen = 0
    private var ecW = 0
    private var ecDelay = 0.0
    private var ecQuiet = Int.MAX_VALUE
    private var ecMax = 0.0
    private var ecGlide = 0.0
    private val ecC = DoubleArray(10)
    private var ecS = DoubleArray(0)

    // ---- source: live, roll, brake, spin ---------------------------------------------------------
    private var srcMode = SRC_LIVE
    private var rollStart = 0L
    private var rollPos = 0L
    private var rollLen = 1
    private var rollBeatsSet = 0f
    private var mainPos = 0.0
    private var modeElapsed = 0.0
    private var modeDur = 1.0
    private var xfLen = 1
    private var xfLeft = 0
    private var xfIn = DoubleArray(0)
    private var xfOut = DoubleArray(0)
    private var ghostPos = 0.0
    private var ghostRate = 1.0
    private var ghostSilent = false
    private val spinC = DoubleArray(5)
    private var spinS = DoubleArray(0)

    // ---- beat ---------------------------------------------------------------------------------------
    private var beatPos = 0.0
    private var prevBeatFloor = 0L
    private var barOffset = 0
    private var barCand = -1

    /** The downbeat estimate is trusted (tempo measured, bar position confirmed): Bar quantize waits for it. */
    private var barReliable = false
    private var locked = false
    private var trackerSeen = -1
    private var pendingCorr = 0.0
    private var deckBpm = 120.0

    // ---- transport (local view) ------------------------------------------------------------------
    private var sBeat = 0.0
    private var sGen = -1
    private var role = ROLE_NONE
    private var xFader = 0.0
    private var fullBeats = 0.0
    private var wasOwner = false

    private fun ms(v: Double): Int = max(1, (v * sr / 1000.0).roundToInt())

    // =================================================================================================

    fun process(samples: ShortArray, frames: Int, rate: Int, channels: Int, now: Long) {
        val rg = rack.resetGen.load()
        if (pendingInit || rg != resetSeen) {
            resetSeen = rg
            pendingInit = false
            reinit()
        }
        if (rate != sr || channels != ch) allocate(rate, channels)
        // Continuity in audio time, not wall-clock time: players deliver PCM in bursts (ExoPlayer
        // refills its AudioTrack, then waits ~250 ms), so a wall-clock gap only means silence for
        // the part that exceeds the audio already delivered ahead of playback.
        val prevUntil = audibleUntil
        val first = prevUntil == NEVER
        val silence = if (first) Long.MAX_VALUE else now - prevUntil
        val activated = first || silence >= ACTIVE_NS
        val discontinuity = !activated && silence >= DISCONTINUITY_NS
        val startHeard = if (first || prevUntil < now) now else prevUntil
        val until = startHeard + (frames * 1_000_000_000L) / rate
        audibleUntil = if (until > now + AHEAD_CAP_NS) now + AHEAD_CAP_NS else until
        lastNanos = now
        if (activated) activatedNanos = now
        if (faulted) return
        val tr = tracker!!

        var st = rack.state.load()
        var phase = st and 7
        var gen = st ushr 3
        val scr = rack.script.load()

        if (activated) {
            snapNext = true
            srcMode = SRC_LIVE
            xfLeft = 0
            if (rack.retired.load() === this) rack.retired.compareAndSet(this, null)
            var becameIn = false
            if (scr != null && phase >= PH_WAITING && phase <= PH_AWAIT &&
                rack.inDeck.load() == null && rack.outDeck.load() !== this &&
                (phase != PH_WAITING || rack.anyOtherAlive(this, now))
            ) {
                if (rack.inDeck.compareAndSet(null, this)) {
                    becameIn = true
                    tr.reset()
                    resetEffects()
                    beatPos = 0.0; prevBeatFloor = 0; locked = false; barOffset = 0; barCand = -1; barReliable = false
                    val det = if (phase == PH_WAITING) 0.0 else max(0.0, rack.alignedClockBeat(now))
                    val ps = ceil(max(scr.pre.length, det) - BEAT_EPS)
                    rack.postStart.compareAndSet(MixRack.NAN_BITS, ps.toRawBits())
                    sBeat = det
                    sGen = gen
                }
            }
            if (!becameIn) tr.discontinuity()
        } else if (discontinuity) {
            tr.discontinuity()
        }

        // Master clock ownership.
        val preferred = rack.preferredClock(now, phase)
        if (preferred === this) rack.clockOwner.store(this)
        val owner = rack.clockOwner.load() === this

        // Local script beat. A deck that is not (or was not) driving the clock lines up with the
        // owner on the playback timeline: the owner's published beat is heard at its audibleUntil, and
        // this buffer's first frame is heard at startHeard. Never backwards.
        val aligned = rack.alignedClockBeat(startHeard)
        if (gen != sGen) {
            sGen = gen
            sBeat = max(0.0, aligned)
            fullBeats = 0.0
        } else if (!owner || !wasOwner) {
            if (aligned > sBeat) sBeat = aligned
        }
        wasOwner = owner

        updateTempo(tr, computeRole(phase))

        var f = 0
        while (f < frames) {
            // ---- beat crossing on this deck
            val bf = floor(beatPos).toLong()
            var beatCross = false
            if (bf != prevBeatFloor) {
                beatCross = bf > prevBeatFloor
                prevBeatFloor = bf
            }
            applyPhaseCorrection(tr)

            var postStartV = rack.postStartValue()

            // ---- owner duties: start, cues, phase changes, triggers
            if (owner) {
                st = rack.state.load(); phase = st and 7; gen = st ushr 3
                if (gen != sGen) {
                    sGen = gen; sBeat = rack.clockBeatValue(); fullBeats = 0.0
                }
                if (phase == PH_WAITING && scr != null && rack.script.load() === scr) {
                    val q = rack.quantizeMode
                    val go = q == Q_NONE || (beatCross && (q == Q_BEAT || onDownbeat()))
                    if (go && rack.startPre(st, this)) {
                        st = rack.state.load(); phase = st and 7
                        sBeat = 0.0
                    }
                }
                if (phase >= PH_PRE && scr != null) {
                    fireCues(scr, postStartV)
                    if (phase == PH_PRE && sBeat >= scr.pre.length - BEAT_EPS && postStartV.isNaN()) {
                        if (rack.setPhase(st, PH_AWAIT)) {
                            st = rack.state.load(); phase = st and 7
                        }
                    }
                    if ((phase == PH_PRE || phase == PH_AWAIT) && !postStartV.isNaN() && sBeat >= postStartV - BEAT_EPS) {
                        if (rack.setPhase(st, PH_POST)) {
                            st = rack.state.load(); phase = st and 7
                        }
                    }
                    if (phase == PH_POST) {
                        val end = if (scr.manualFader) fullBeats >= 1.0 else sBeat >= postStartV + scr.post.length - BEAT_EPS
                        if (end && rack.endScript(st)) {
                            st = rack.state.load(); phase = st and 7; gen = st ushr 3
                        }
                    }
                }
                consumeTriggers(now, beatCross)
            } else {
                st = rack.state.load(); phase = st and 7
                if ((st ushr 3) != sGen) {
                    // A new script (or an end): restart from the published clock.
                    gen = st ushr 3; sGen = gen; sBeat = rack.clockBeatValue(); fullBeats = 0.0
                }
            }
            postStartV = rack.postStartValue()

            // ---- role and values
            val newRole = computeRole(phase)
            if (newRole != role) onRoleChange(role, newRole)
            role = newRole
            updateUserLayer(min(BLOCK, frames - f))
            val manual = scr != null && scr.manualFader
            val fdr = rack.fader().toDouble()
            computeTargets(scr, phase, postStartV, manual)

            // ---- block length: cut at events so they land on a block start
            var n = min(BLOCK, frames - f)
            if (owner && (phase == PH_WAITING || rack.sampler.hasPending())) {
                val toBeat = ceil((floor(beatPos) + 1.0 - beatPos) / beatsPerFrame()).toInt()
                n = min(n, max(1, toBeat))
            }
            if (phase >= PH_PRE && scr != null) {
                val bpf = clockBpf(owner)
                var ev = scr.pre.nextEvent(sBeat)
                if (!postStartV.isNaN()) {
                    if (postStartV > sBeat + BEAT_EPS) ev = min(ev, postStartV)
                    ev = min(ev, postStartV + scr.post.nextEvent(sBeat - postStartV))
                }
                if (ev.isFinite()) {
                    val fr = ceil((ev - sBeat) / bpf)
                    if (fr < n) n = max(1, fr.toInt())
                }
            }

            // ---- smoothing
            xFader += (fdr - xFader) * onePoleFader[n]
            if (abs(fdr - xFader) < 1e-4) xFader = fdr
            if (snapNext) {
                for (p in 0 until PARAMS) {
                    cur[p] = tgt[p]; glide[p] = 0
                }
                gainPrev = tgt[P_GAIN]
                xFader = fdr
                snapNext = false
            } else {
                gainPrev = cur[P_GAIN]
                smooth(n)
            }

            processBlock(samples, f, n, owner)

            // ---- advance clocks
            val inPostManual = manual && phase == PH_POST
            beatPos += n * beatsPerFrame()
            if (phase >= PH_PRE) {
                val db = n * clockBpf(owner)
                sBeat += db
                if (owner && inPostManual) {
                    if (xFader >= 0.999) fullBeats += db else fullBeats = 0.0
                }
            }
            f += n
        }

        if (owner && phase >= PH_PRE && (rack.state.load() ushr 3) == sGen) {
            rack.clockBeat.store(sBeat.toRawBits())
            rack.clockUntil = audibleUntil
        }
        if (owner) rack.clockBpm = deckBpm
        publish(tr)
    }

    // ---- roles -------------------------------------------------------------------------------------

    private fun computeRole(phase: Int): Int {
        if (rack.retired.load() === this) return ROLE_RETIRED
        return when {
            phase >= PH_PRE -> when {
                rack.outDeck.load() === this -> ROLE_OUT
                rack.inDeck.load() === this -> ROLE_IN
                else -> ROLE_OTHER
            }
            phase == PH_WAITING -> if (rack.inDeck.load() === this) ROLE_IN else ROLE_ON_AIR
            else -> ROLE_ON_AIR
        }
    }

    private fun onRoleChange(prev: Int, next: Int) {
        val g = when {
            prev == ROLE_NONE -> 0
            next == ROLE_RETIRED -> ms(5.0)
            (prev == ROLE_IN || prev == ROLE_OUT) && next != ROLE_OUT && next != ROLE_IN ->
                if (rack.lastEndKind == END_ABORT) ms(300.0) else ms(200.0)
            else -> 0
        }
        if (g > 0) for (p in 0 until PARAMS) glide[p] = g
    }

    // ---- values ------------------------------------------------------------------------------------

    private fun updateUserLayer(n: Int) {
        val ug = rack.userGen.load()
        if (ug != userGenSeen) {
            val first = userGenSeen == Int.MIN_VALUE
            userGenSeen = ug
            for (p in 0 until PARAMS) {
                val t = Float.fromBits(rack.userTarget.loadAt(p))
                if (first) {
                    userCur[p] = t; userTgt[p] = t; userStep[p] = 0f
                } else if (t != userTgt[p]) {
                    userTgt[p] = t
                    val frames = max(1.0, rack.userRamp.loadAt(p) * sr / 1000.0)
                    userStep[p] = (abs(t - userCur[p]) / frames).toFloat()
                }
            }
        }
        for (p in 0 until PARAMS) {
            val t = userTgt[p]
            val c = userCur[p]
            if (c == t) continue
            val step = userStep[p] * n
            userCur[p] = if (abs(t - c) <= step || step <= 0f) t else if (t > c) c + step else c - step
        }
    }

    private fun computeTargets(scr: CompiledScript?, phase: Int, postStartV: Double, manual: Boolean) {
        val r = role
        val laneOk = scr != null && ((phase >= PH_PRE && r == ROLE_OUT) || (phase >= PH_WAITING && r == ROLE_IN))
        val inPost = laneOk && phase >= PH_PRE && !postStartV.isNaN() && sBeat >= postStartV - BEAT_EPS
        val d = if (r == ROLE_IN) 1 else 0
        val userOk = r == ROLE_ON_AIR || r == ROLE_OUT
        for (p in 0 until PARAMS) {
            var v = Float.NaN
            if (laneOk) {
                if (!(manual && (p == P_GAIN || p == P_LOW))) {
                    if (inPost) {
                        val lane = scr.post.lane(d, p)
                        if (lane != null) v = lane.valueAt(sBeat - postStartV)
                    } else if (r == ROLE_IN) {
                        // Muted until post: sit at post's first values so the unmute is clean.
                        val lane = scr.post.lane(d, p)
                        if (lane != null) v = lane.valueAt(0.0)
                    }
                }
                if (v.isNaN() && phase >= PH_PRE) {
                    val lane = scr.pre.lane(d, p)
                    if (lane != null) v = lane.valueAt(sBeat)
                }
            }
            if (v.isNaN()) v = if (userOk) userCur[p] else NEUTRAL[p]
            tgt[p] = v
        }
        if (inPost && manual) {
            val xf = xFader.coerceIn(0.0, 1.0)
            tgt[P_GAIN] = (if (r == ROLE_OUT) cos(xf * PI / 2) else sin(xf * PI / 2)).toFloat()
            val kill = if (r == ROLE_OUT) xf > 0.5 else xf < 0.5
            if (kill) tgt[P_LOW] = -1f
            if (kill != lastLowKill) glide[P_LOW] = max(glide[P_LOW], ms(60.0))
            lastLowKill = kill
        }
        if (r == ROLE_RETIRED || (r == ROLE_IN && !inPost)) tgt[P_GAIN] = 0f
        // Gain jumps (Step points, mute flips) land sample accurately with a 2 ms ramp.
        if (abs(tgt[P_GAIN] - lastGainTgt) > 0.02f && glide[P_GAIN] <= 0) glide[P_GAIN] = ms(2.0)
        lastGainTgt = tgt[P_GAIN]
    }

    private fun smooth(n: Int) {
        val a = onePole[n].toFloat()
        for (p in 0 until PARAMS) {
            val t = tgt[p]
            var c = cur[p]
            val g = glide[p]
            if (g > 0) {
                c = if (n >= g) t else c + (t - c) * n / g
                glide[p] = if (n >= g) 0 else g - n
            } else if (c != t) {
                c += (t - c) * a
                if (abs(t - c) < 1e-5f) c = t
            }
            cur[p] = c
        }
    }

    // ---- tempo and beat ------------------------------------------------------------------------------

    private fun updateTempo(tr: BeatTracker, r: Int) {
        val hint = rack.hint(if (r == ROLE_IN) 1 else 0)
        deckBpm = if (tr.measured) {
            var m = tr.bpm
            if (hint > 0) {
                if (abs(m * 2 / hint - 1) < 0.06) m *= 2.0 else if (abs(m / 2 / hint - 1) < 0.06) m /= 2.0
            }
            m
        } else if (hint > 0) {
            hint
        } else if (tr.bpm > 0 && tr.confidence > 0.25) {
            tr.bpm
        } else {
            120.0
        }
        deckBpm = deckBpm.coerceIn(30.0, 300.0)
    }

    private fun beatsPerFrame(): Double = deckBpm / 60.0 / sr

    private fun clockBpf(owner: Boolean): Double = (if (owner) deckBpm else rack.clockBpm) / 60.0 / sr

    /** At a beat crossing: is it a downbeat? Any beat counts while the bar position is not reliable. */
    private fun onDownbeat(): Boolean = !barReliable || beatInBarNow() == 0

    private fun beatInBarNow(): Int = (((floor(beatPos).toLong() + barOffset) % 4 + 4) % 4).toInt()

    private fun applyPhaseCorrection(tr: BeatTracker) {
        if (tr.updates != trackerSeen) {
            trackerSeen = tr.updates
            val ref = tr.beatRefFrame
            if (!ref.isNaN() && tr.bpm > 0 && tr.confidence >= 0.2) {
                val pFrames = 60.0 * sr / deckBpm
                val target = (tr.framesAnalyzed - ref) / pFrames
                val err = Dsp.wrapHalf(target - beatPos)
                if (!locked || abs(err) > 0.25) {
                    beatPos += err
                    pendingCorr = 0.0
                    locked = true
                    prevBeatFloor = floor(beatPos).toLong()
                } else {
                    pendingCorr = err
                }
                // Bar position of the reference beat in this deck's beat count.
                val refIdx = floor(beatPos + pendingCorr - target + 0.5).toLong()
                val cand = (((tr.barIndexAtRef - refIdx) % 4 + 4) % 4).toInt()
                val accept = tr.downbeatConfidence > 0.3 || cand == barCand
                if (accept) barOffset = cand
                barReliable = accept && tr.measured
                barCand = cand
            }
        }
        if (pendingCorr != 0.0) {
            val step = pendingCorr.coerceIn(-0.004, 0.004)
            beatPos += step
            pendingCorr -= step
            if (abs(pendingCorr) < 1e-9) pendingCorr = 0.0
        }
    }

    // ---- owner duties ----------------------------------------------------------------------------------

    private fun fireCues(scr: CompiledScript, postStartV: Double) {
        val pre = scr.pre
        var i = rack.cueNextPre.load()
        while (i < pre.cueBeats.size && pre.cueBeats[i] <= sBeat + BEAT_EPS) {
            if (rack.cueNextPre.compareAndSet(i, i + 1)) rack.emitCue(pre.cueCodes[i], this, sr)
            i = rack.cueNextPre.load()
        }
        if (postStartV.isNaN() || sBeat < postStartV - BEAT_EPS) return
        val pb = sBeat - postStartV
        val post = scr.post
        i = rack.cueNextPost.load()
        while (i < post.cueBeats.size && post.cueBeats[i] <= pb + BEAT_EPS) {
            if (rack.cueNextPost.compareAndSet(i, i + 1)) rack.emitCue(post.cueCodes[i], this, sr)
            i = rack.cueNextPost.load()
        }
    }

    private fun consumeTriggers(now: Long, beatCross: Boolean) {
        val q = rack.triggerQ
        while (true) {
            val code = q.pop()
            if (code == 0) break
            if (now - q.lastStamp > TRIGGER_TTL_NS) continue
            val id = (code - 1) % 8
            when ((code - 1) / 8) {
                Q_NONE -> rack.sampler.start(id, sr)
                Q_BEAT -> rack.sampler.addPending(id, bar = false)
                else -> rack.sampler.addPending(id, bar = true)
            }
        }
        if (beatCross) rack.sampler.firePending(onDownbeat(), sr)
    }

    // ---- audio -------------------------------------------------------------------------------------------

    private fun processBlock(samples: ShortArray, off: Int, n: Int, owner: Boolean) {
        val c = ch
        val tr = tracker!!
        val t0 = framesIn
        val base = off * c
        val invCh = 1.0 / c
        // 1. History and analysis (pre-FX, always).
        for (i in 0 until n) {
            val hi = ((t0 + i) and histMask).toInt() * c
            val o = i * c
            var mono = 0.0
            for (k in 0 until c) {
                val s = samples[base + o + k]
                hist[hi + k] = s
                val d = s * INV_32768
                x[o + k] = d
                mono += d
            }
            tr.feed(mono * invCh)
        }

        updateSourceMode(t0)
        val host = owner && rack.sampler.active()
        if (isNeutral()) {
            if (host) {
                samp.fill(0.0, 0, n)
                rack.sampler.render(samp, n, sr)
                val vol = rack.sampleVolume.toDouble()
                for (i in 0 until n) {
                    val sv = samp[i] * vol
                    val o = i * c
                    for (k in 0 until c) samples[base + o + k] = Dsp.toPcm16(x[o + k] + sv)
                }
            }
            framesIn = t0 + n
            return
        }

        source(t0, n)
        eq(n)
        filter(n)
        gate(n)
        flanger(n)
        echo(n)

        // Gain (ramped per sample), sampler, clip.
        val g0 = gainPrev.toDouble()
        val dg = (cur[P_GAIN] - gainPrev).toDouble() / n
        if (host) {
            samp.fill(0.0, 0, n)
            rack.sampler.render(samp, n, sr)
        }
        val vol = if (host) rack.sampleVolume.toDouble() else 0.0
        for (i in 0 until n) {
            val g = g0 + dg * (i + 1)
            val sv = if (host) samp[i] * vol else 0.0
            val o = i * c
            for (k in 0 until c) samples[base + o + k] = Dsp.toPcm16(y[o + k] * g + sv)
        }
        framesIn = t0 + n
    }

    private fun isNeutral(): Boolean {
        if (cur[P_GAIN] != 1f || gainPrev != 1f || tgt[P_GAIN] != 1f) return false
        if (cur[P_DRY] != 1f) return false
        if (cur[P_LOW] != 0f || cur[P_MID] != 0f || cur[P_HIGH] != 0f) return false
        if (tgt[P_LOW] != 0f || tgt[P_MID] != 0f || tgt[P_HIGH] != 0f) return false
        if (abs(cur[P_FILTER]) >= 0.01f || fOn) return false
        if (cur[P_GATE] > 0f || gateEnv < 1.0) return false
        if (cur[P_FLANGER] > 0f) return false
        if (srcMode != SRC_LIVE || xfLeft > 0) return false
        if (echoActive()) return false
        return true
    }

    private fun echoActive(): Boolean = cur[P_SEND] > 0f || cur[P_WET] > 0f || ecQuiet < ecLen

    // ---- source stage ------------------------------------------------------------------------------------

    private fun updateSourceMode(t0: Long) {
        val want = when {
            tgt[P_SPIN] >= 0.5f -> SRC_SPIN
            tgt[P_BRAKE] >= 0.5f -> SRC_BRAKE
            tgt[P_ROLL] >= 0.5f -> SRC_ROLL
            else -> SRC_LIVE
        }
        if (want != srcMode) {
            when (want) {
                SRC_LIVE -> startXfade(t0)
                SRC_ROLL -> {
                    if (srcMode != SRC_LIVE) startXfade(t0)
                    rollStart = t0
                    rollPos = t0
                    rollBeatsSet = tgt[P_ROLL_BEATS]
                    rollLen = rollFrames(rollBeatsSet)
                }
                SRC_BRAKE -> {
                    if (srcMode != SRC_LIVE) startXfade(t0)
                    mainPos = t0.toDouble()
                    modeElapsed = 0.0
                    modeDur = max(1.0, tgt[P_BRAKE_BEATS] * 60.0 / deckBpm * sr)
                }
                SRC_SPIN -> {
                    startXfade(t0)
                    mainPos = t0.toDouble()
                    modeElapsed = 0.0
                    modeDur = max(1.0, tgt[P_SPIN_BEATS] * 60.0 / deckBpm * sr)
                    spinS.fill(0.0)
                }
            }
            srcMode = want
        } else if (srcMode == SRC_ROLL && abs(tgt[P_ROLL_BEATS] - rollBeatsSet) > 1e-4f) {
            // Re-slice from the roll's start.
            rollBeatsSet = tgt[P_ROLL_BEATS]
            val len = rollFrames(rollBeatsSet)
            val np = rollStart + (t0 - rollStart) % len
            if (np != rollPos) {
                startXfade(t0)
                rollPos = np
            }
            rollLen = len
        }
    }

    private fun rollFrames(beats: Float): Int =
        (beats * 60.0 / deckBpm * sr).roundToInt().coerceIn(32, histFrames / 2)

    /** Fades from the current read head (which keeps moving) to whatever the source becomes. */
    private fun startXfade(t0: Long) {
        when (srcMode) {
            SRC_LIVE -> { ghostPos = t0.toDouble(); ghostRate = 1.0; ghostSilent = false }
            SRC_ROLL -> { ghostPos = rollPos.toDouble(); ghostRate = 1.0; ghostSilent = false }
            SRC_BRAKE -> {
                val u = modeElapsed / modeDur
                ghostSilent = u >= 1.0
                ghostPos = mainPos; ghostRate = brakeRate(u)
            }
            else -> {
                val u = modeElapsed / modeDur
                ghostSilent = u >= 1.0
                ghostPos = mainPos; ghostRate = spinRate(u)
            }
        }
        xfLeft = xfLen
    }

    private fun brakeRate(u: Double) = if (u >= 1.0) 0.0 else 1.0 - u

    private fun spinRate(u: Double): Double {
        if (u >= 1.0) return 0.0
        val v = 1.0 - u
        return -3.0 * v * v
    }

    private fun source(t0: Long, n: Int) {
        val c = ch
        if (srcMode == SRC_LIVE && xfLeft == 0) {
            x.copyInto(y, 0, 0, n * c)
            return
        }
        val last = t0 + n - 1
        val oldest = t0 + n - histFrames + 2
        for (i in 0 until n) {
            val o = i * c
            when (srcMode) {
                SRC_LIVE -> for (k in 0 until c) y[o + k] = x[o + k]
                SRC_ROLL -> {
                    if (rollPos >= rollStart + rollLen) {
                        startXfade(t0 + i)
                        rollPos -= rollLen
                    }
                    readInt(rollPos, o, last, oldest, 1.0)
                    rollPos++
                }
                SRC_BRAKE -> {
                    val u = modeElapsed / modeDur
                    if (u >= 1.0) {
                        for (k in 0 until c) y[o + k] = 0.0
                    } else {
                        val env = min(1.0, (1.0 - u) / 0.15)
                        readHermite(mainPos, o, last, oldest, env, y)
                        mainPos += brakeRate(u)
                    }
                    modeElapsed += 1.0
                }
                else -> {
                    val u = modeElapsed / modeDur
                    if (u >= 1.0) {
                        for (k in 0 until c) y[o + k] = 0.0
                    } else {
                        val env = min(1.0, (1.0 - u) / 0.35) * min(1.0, u * modeDur / (0.004 * sr))
                        readHermite(mainPos, o, last, oldest, env, y)
                        // Vinyl-ish low-pass on the spinning record.
                        val b0 = spinC[0]; val b1 = spinC[1]; val b2 = spinC[2]; val a1 = spinC[3]; val a2 = spinC[4]
                        for (k in 0 until c) {
                            val xv = y[o + k]
                            val yv = b0 * xv + spinS[2 * k]
                            spinS[2 * k] = b1 * xv - a1 * yv + spinS[2 * k + 1]
                            spinS[2 * k + 1] = b2 * xv - a2 * yv
                            y[o + k] = yv
                        }
                        mainPos += spinRate(u)
                    }
                    modeElapsed += 1.0
                }
            }
            if (xfLeft > 0) {
                val j = xfLen - xfLeft
                val wm = xfIn[j]
                val wg = xfOut[j]
                if (ghostSilent) {
                    for (k in 0 until c) y[o + k] *= wm
                } else {
                    if (ghostRate == 1.0 && ghostPos == floor(ghostPos)) {
                        readIntInto(ghostPos.toLong(), last, oldest, tmp)
                    } else {
                        readHermite(ghostPos, 0, last, oldest, 1.0, tmp)
                    }
                    for (k in 0 until c) y[o + k] = y[o + k] * wm + tmp[k] * wg
                }
                ghostPos += ghostRate
                xfLeft--
            }
        }
    }

    private fun readInt(pos: Long, o: Int, last: Long, oldest: Long, gain: Double) {
        val c = ch
        if (pos > last || pos < oldest) {
            for (k in 0 until c) y[o + k] = 0.0
            return
        }
        val hi = (pos and histMask).toInt() * c
        for (k in 0 until c) y[o + k] = hist[hi + k] * INV_32768 * gain
    }

    private fun readIntInto(pos: Long, last: Long, oldest: Long, dst: DoubleArray) {
        val c = ch
        if (pos > last || pos < oldest) {
            for (k in 0 until c) dst[k] = 0.0
            return
        }
        val hi = (pos and histMask).toInt() * c
        for (k in 0 until c) dst[k] = hist[hi + k] * INV_32768
    }

    private fun readHermite(pos: Double, o: Int, last: Long, oldest: Long, gain: Double, dst: DoubleArray) {
        val c = ch
        val i = floor(pos).toLong()
        if (i < oldest + 1 || i > last) {
            for (k in 0 until c) dst[o + k] = 0.0
            return
        }
        val t = pos - i
        val im1 = ((i - 1) and histMask).toInt() * c
        val i0 = (i and histMask).toInt() * c
        val i1 = ((if (i + 1 > last) last else i + 1) and histMask).toInt() * c
        val i2 = ((if (i + 2 > last) last else i + 2) and histMask).toInt() * c
        for (k in 0 until c) {
            val v = Dsp.hermite(
                hist[im1 + k].toDouble(), hist[i0 + k].toDouble(), hist[i1 + k].toDouble(), hist[i2 + k].toDouble(), t,
            )
            dst[o + k] = v * INV_32768 * gain
        }
    }

    // ---- EQ, filter ----------------------------------------------------------------------------------

    /**
     * Three-band EQ. The shelves are two cascaded half-depth RBJ shelves (S = 1): a steeper transition,
     * so a kill reaches -40 dB in band while barely touching the neighbouring band. The mid is one wide
     * peak. State: two slots per channel for each of the five stages (low A, low B, mid, high A, high B).
     */
    private fun eq(n: Int) {
        val fs = sr.toDouble()
        val c2 = 2 * ch
        for (b in 0 until 3) {
            val p = P_LOW + b
            val v = cur[p]
            val st = if (b == 0) 0 else if (b == 1) 2 else 3
            val stages = if (b == 1) 1 else 2
            if (v == 0f && tgt[p] == 0f) {
                if (eqOn[b]) {
                    eqOn[b] = false
                    for (i in 0 until stages * c2) eqS[st * c2 + i] = 0.0
                }
                continue
            }
            if (!eqOn[b] || v != eqVal[b]) {
                val db = if (v >= 0f) 6.0 * v else 40.0 * v * (0.4 + 0.6 * -v)
                when (b) {
                    0 -> Bq.lowShelf(eqC, 0, fs, 200.0, db / 2)
                    1 -> Bq.peaking(eqC, 5, fs, 1000.0, 0.6, db)
                    else -> Bq.highShelf(eqC, 10, fs, 3500.0, db / 2)
                }
                eqVal[b] = v
                eqOn[b] = true
            }
            for (s in 0 until stages) Bq.run(y, n, ch, eqC, b * 5, eqS, (st + s) * c2)
        }
    }

    private fun filter(n: Int) {
        val v = cur[P_FILTER]
        if (abs(v) < 0.01f) {
            fOn = false
            return
        }
        val type = if (v < 0f) 0 else 1
        val fs = sr.toDouble()
        if (!fOn || type != fType || v != fVal) {
            val fc = if (type == 0) 20000.0 * (180.0 / 20000.0).pow(-v.toDouble()) else 20.0 * (5000.0 / 20.0).pow(v.toDouble())
            if (type == 0) {
                Bq.lowPass(fC, 0, fs, fc, FILTER_Q); Bq.lowPass(fC, 5, fs, fc, FILTER_Q)
            } else {
                Bq.highPass(fC, 0, fs, fc, FILTER_Q); Bq.highPass(fC, 5, fs, fc, FILTER_Q)
            }
            if (!fOn || type != fType) {
                // Switch in at steady state for the current input: no click.
                for (k in 0 until ch) {
                    val x0 = y[k]
                    Bq.primeDc(fC, 0, fS, 2 * k, x0)
                    Bq.primeDc(fC, 5, fS, 2 * ch + 2 * k, Bq.dcGain(fC, 0) * x0)
                }
            }
            fOn = true; fType = type; fVal = v
        }
        Bq.run(y, n, ch, fC, 0, fS, 0)
        Bq.run(y, n, ch, fC, 5, fS, 2 * ch)
    }

    // ---- gate, flanger, echo -------------------------------------------------------------------------

    private fun gate(n: Int) {
        val depth = cur[P_GATE].toDouble()
        if (depth <= 0.0 && gateEnv >= 1.0) return
        val c = ch
        val inv = 1.0 / max(0.0625, tgt[P_GATE_BEATS].toDouble())
        val bpf = beatsPerFrame()
        var bp = beatPos
        for (i in 0 until n) {
            val ph = Dsp.frac(bp * inv)
            val target = if (ph < 0.5) 1.0 else 1.0 - depth
            val e = gateEnv
            gateEnv = if (e < target) min(target, e + gateStep) else max(target, e - gateStep)
            val o = i * c
            for (k in 0 until c) y[o + k] *= gateEnv
            bp += bpf
        }
        if (depth <= 0.0 && gateEnv > 0.9999) gateEnv = 1.0
    }

    private fun flangerDelay(bp: Double): Double = (0.5 + 4.5 * (0.5 - 0.5 * cos(2 * PI * bp / 8.0))) * sr / 1000.0

    private fun flanger(n: Int) {
        val v = cur[P_FLANGER].toDouble()
        if (v <= 0.0) {
            flOn = false
            return
        }
        val c = ch
        if (!flOn) {
            fl.fill(0.0)
            flOn = true
        }
        val mix = 0.6 * v
        val norm = 1.0 / (1.0 + 0.5 * mix)
        val d0 = flangerDelay(beatPos)
        val d1 = flangerDelay(beatPos + n * beatsPerFrame())
        for (i in 0 until n) {
            val d = d0 + (d1 - d0) * i / n
            var rp = flW - d
            if (rp < 0) rp += flLen
            val ia = rp.toInt()
            val t = rp - ia
            val ib = if (ia + 1 >= flLen) 0 else ia + 1
            val o = i * c
            for (k in 0 until c) {
                val r = fl[ia * c + k] * (1 - t) + fl[ib * c + k] * t
                val s = y[o + k]
                fl[flW * c + k] = s + 0.5 * r
                y[o + k] = (s + mix * r) * norm
            }
            flW++
            if (flW >= flLen) flW = 0
        }
    }

    private fun echo(n: Int) {
        val c = ch
        val dry = cur[P_DRY].toDouble()
        if (!echoActive()) {
            if (dry != 1.0) for (i in 0 until n * c) y[i] *= dry
            return
        }
        val send = cur[P_SEND].toDouble()
        val wet = cur[P_WET].toDouble()
        val fb = cur[P_FEEDBACK].toDouble()
        val target = (cur[P_ECHO_BEATS] * 60.0 / deckBpm * sr).coerceIn(16.0, ecMax)
        if (ecQuiet >= ecLen) ecDelay = target
        val hb0 = ecC[0]; val hb1 = ecC[1]; val hb2 = ecC[2]; val ha1 = ecC[3]; val ha2 = ecC[4]
        val lb0 = ecC[5]; val lb1 = ecC[6]; val lb2 = ecC[7]; val la1 = ecC[8]; val la2 = ecC[9]
        var peak = 0.0
        for (i in 0 until n) {
            ecDelay += (target - ecDelay) * ecGlide
            var rp = ecW - ecDelay
            if (rp < 0) rp += ecLen
            val ia = rp.toInt()
            val t = rp - ia
            val ib = if (ia + 1 >= ecLen) 0 else ia + 1
            val o = i * c
            for (k in 0 until c) {
                val r = ec[ia * c + k] * (1 - t) + ec[ib * c + k] * t
                // Feedback path band-limited: HP 250 Hz then LP 5 kHz.
                val s0 = 4 * k
                val h = hb0 * r + ecS[s0]
                ecS[s0] = hb1 * r - ha1 * h + ecS[s0 + 1]
                ecS[s0 + 1] = hb2 * r - ha2 * h
                val l = lb0 * h + ecS[s0 + 2]
                ecS[s0 + 2] = lb1 * h - la1 * l + ecS[s0 + 3]
                ecS[s0 + 3] = lb2 * h - la2 * l
                val s = y[o + k]
                var w = send * s + fb * l
                if (w > 2.0) w = 2.0 else if (w < -2.0) w = -2.0
                ec[ecW * c + k] = w
                val aw = if (w < 0) -w else w
                if (aw > peak) peak = aw
                y[o + k] = dry * s + wet * r
            }
            ecW++
            if (ecW >= ecLen) ecW = 0
        }
        if (peak > 1e-4) ecQuiet = 0 else if (ecQuiet < ecLen) ecQuiet = min(ecLen, ecQuiet + n)
        if (ecQuiet >= ecLen) for (i in ecS.indices) ecS[i] = 0.0
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------

    private fun allocate(rate: Int, channels: Int) {
        sr = rate
        ch = channels
        val fs = rate.toDouble()
        histFrames = 1
        while (histFrames < HISTORY_SECONDS * rate) histFrames = histFrames shl 1
        histMask = (histFrames - 1).toLong()
        hist = ShortArray(histFrames * channels)
        x = DoubleArray(BLOCK * channels)
        y = DoubleArray(BLOCK * channels)
        eqS = DoubleArray(5 * 2 * channels)
        fS = DoubleArray(2 * 2 * channels)
        flLen = ceil(0.006 * rate).toInt() + 4
        fl = DoubleArray(flLen * channels)
        ecMax = 2.5 * rate
        ecLen = ceil(ecMax).toInt() + 64
        ec = DoubleArray(ecLen * channels)
        ecS = DoubleArray(4 * channels)
        spinS = DoubleArray(2 * channels)
        Bq.highPass(ecC, 0, fs, 250.0, 0.707)
        Bq.lowPass(ecC, 5, fs, 5000.0, 0.707)
        Bq.lowPass(spinC, 0, fs, 2800.0, 0.8)
        ecGlide = 1.0 - exp(-1.0 / (0.05 * rate))
        gateStep = 1.0 / (0.003 * rate)
        xfLen = max(8, (0.005 * rate).roundToInt())
        xfIn = DoubleArray(xfLen) { sin((it + 1.0) / xfLen * PI / 2) }
        xfOut = DoubleArray(xfLen) { cos((it + 1.0) / xfLen * PI / 2) }
        for (n in 0..BLOCK) {
            onePole[n] = 1.0 - exp(-n / (0.010 * rate))
            onePoleFader[n] = 1.0 - exp(-n / (0.030 * rate))
        }
        tracker = BeatTracker(rate)
        rack.allocations.incrementAndFetch()
        reinit()
    }

    private fun resetEffects() {
        eqS.fill(0.0); eqOn.fill(false)
        fS.fill(0.0); fOn = false
        fl.fill(0.0); flOn = false; flW = 0
        ec.fill(0.0); ecS.fill(0.0); ecW = 0; ecQuiet = Int.MAX_VALUE
        gateEnv = 1.0
        srcMode = SRC_LIVE; xfLeft = 0
    }

    private fun reinit() {
        if (sr > 0) {
            hist.fill(0)
            resetEffects()
            tracker?.reset()
        }
        framesIn = 0
        for (p in 0 until PARAMS) {
            cur[p] = NEUTRAL[p]; tgt[p] = NEUTRAL[p]; glide[p] = 0
        }
        userGenSeen = Int.MIN_VALUE
        gainPrev = 1f; lastGainTgt = 1f; lastLowKill = false; snapNext = true
        beatPos = 0.0; prevBeatFloor = 0; barOffset = 0; barCand = -1; barReliable = false; locked = false; trackerSeen = -1; pendingCorr = 0.0
        sBeat = 0.0; sGen = -1; role = ROLE_NONE; xFader = 0.0; fullBeats = 0.0
        lastNanos = NEVER
        audibleUntil = NEVER
        activatedNanos = NEVER
        bpm = 120f; bpmMeasured = false; confidence = 0f; beatPhase = 0f; beatInBar = 0
        energy = 0f; loudnessDb = -90f; framesAnalyzed = 0
    }

    private fun publish(tr: BeatTracker) {
        bpm = deckBpm.toFloat()
        bpmMeasured = tr.measured
        confidence = tr.confidence.toFloat()
        beatPhase = Dsp.frac(beatPos).toFloat()
        beatInBar = beatInBarNow()
        energy = tr.energy.toFloat()
        loudnessDb = tr.loudnessDb.toFloat()
        framesAnalyzed = tr.framesAnalyzed
    }

    companion object {
        const val BLOCK = 32
        private const val HISTORY_SECONDS = 8
        private const val INV_32768 = 1.0 / 32768.0
        private const val FILTER_Q = 0.9
        private const val DISCONTINUITY_NS = 100_000_000L

        /** Most audio assumed buffered ahead of playback (AudioTrack buffers hold well under this). */
        const val AHEAD_CAP_NS = 1_500_000_000L

        const val SRC_LIVE = 0
        const val SRC_ROLL = 1
        const val SRC_BRAKE = 2
        const val SRC_SPIN = 3

        const val ROLE_NONE = 0
        const val ROLE_ON_AIR = 1
        const val ROLE_OUT = 2
        const val ROLE_IN = 3
        const val ROLE_RETIRED = 4
        const val ROLE_OTHER = 5
    }
}
