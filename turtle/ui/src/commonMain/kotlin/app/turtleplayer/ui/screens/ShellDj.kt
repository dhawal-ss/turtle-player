package app.turtleplayer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.turtleplayer.bridge.DjArc
import app.turtleplayer.bridge.DjEngine
import app.turtleplayer.bridge.DjMemory
import app.turtleplayer.bridge.DjRack
import app.turtleplayer.bridge.DjTrack
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.MixScript
import app.turtleplayer.mix.Pick
import app.turtleplayer.mix.PickReason
import app.turtleplayer.mix.Quantize
import app.turtleplayer.mix.SampleId
import app.turtleplayer.mix.TrackFacts
import app.turtleplayer.mix.TransitionPlan
import app.turtleplayer.mix.TransitionStyle
import app.turtleplayer.mix.VibeSettings
import app.turtleplayer.mix.CueKind
import app.turtleplayer.mix.brain.MixPoints
import app.turtleplayer.mix.brain.SetContext
import app.turtleplayer.mix.brain.SetPlanner
import app.turtleplayer.mix.brain.TransitionPlanner
import app.turtleplayer.mix.rack.TransitionPhase
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.random.Random
import kotlin.time.TimeSource

/** How a set runs; [Endless] repeats the arc every hour. */
internal enum class DjLength(val minutes: Int?) { M30(30), M60(60), M120(120), Endless(null) }

/** Auto: Turtle picks and mixes. You: you pick the next track and the move, Turtle suggests and keeps a safety net. */
internal enum class DjMode { Auto, You }

/** One track in the set: when it starts in set time, the energy the arc wanted there, and why it was picked. */
@Immutable
internal data class DjSlot(
    val track: DjTrack,
    val startMs: Long,
    val target: Double,
    val requested: Boolean = false,
    val reasons: List<PickReason> = emptyList(),
    /** How the DJ plans to mix into this track (set for the next track). */
    val plan: TransitionPlan? = null,
    val chosenByYou: Boolean = false,
)

/** What the DJ is doing right now, for the booth's narration line. */
internal sealed interface DjNow {
    data object Digging : DjNow
    data class OnAir(val slot: DjSlot) : DjNow
    data class Mixing(val style: TransitionStyle, val into: DjSlot, val manual: Boolean) : DjNow
    data class Skipped(val artist: String) : DjNow
    data class Liked(val artist: String) : DjNow
    data class Remixed(val vibe: VibeChange) : DjNow
    data class Requested(val title: String) : DjNow
    data object SafetyNet : DjNow
}

internal enum class VibeChange { Energy, Fresh, Flair, Pace, Variety, Several }

/** A running Turtle DJ set. Snapshot state, so the booth redraws as the set moves. */
@Stable
internal class DjSession(val genres: List<String>, val arc: DjArc, val length: DjLength, vibe: VibeSettings, mode: DjMode) {
    val played = mutableStateListOf<DjSlot>()
    var current by mutableStateOf<DjSlot?>(null)
    val upcoming = mutableStateListOf<DjSlot>()

    var vibe by mutableStateOf(vibe)
    var mode by mutableStateOf(mode)
    var now by mutableStateOf<DjNow>(DjNow.Digging)

    var loading by mutableStateOf(true)
    var planning by mutableStateOf(false)
    var mixing by mutableStateOf(false)
    var mixStyle by mutableStateOf<TransitionStyle?>(null)
    var manualMix by mutableStateOf(false)

    /** Crossfader: 0 is deck A (on air), 1 is deck B (the incoming track). Read it in draw. */
    var mixProgress by mutableFloatStateOf(0f)

    /** Media position at which the current track starts mixing out (Auto). */
    var mixPointMs by mutableLongStateOf(Long.MAX_VALUE)

    /** Set time: how long the set has been playing (pauses excluded). Read it in draw or a leaf. */
    var elapsedMs by mutableLongStateOf(0L)

    /** Playback rate applied now (tempo nudges keep pitch: both players time-stretch). */
    var speed by mutableFloatStateOf(1f)

    /** The listener's tempo on top of the automatic nudges (±8 %). */
    var userTempo by mutableFloatStateOf(1f)

    /** The on-air deck's tempo and energy as the rack measures them (0 until it knows). */
    var liveBpm by mutableFloatStateOf(0f)
    var liveBpmMeasured by mutableStateOf(false)
    var liveEnergy by mutableFloatStateOf(-1f)

    /** Whether the effects rack hears the audio (false: plain crossfades, no FX). */
    var fxAvailable by mutableStateOf(true)

    /** You mode: the move you picked for the next mix (null: the DJ's suggestion). */
    var youStyle by mutableStateOf<TransitionStyle?>(null)

    /** You mode: next-track choices, best first. */
    val suggestions = mutableStateListOf<Pick>()

    /** The listener's live effects layer, as the UI shows it. */
    val userFx = mutableStateMapOf<FxParam, Float>()

    /** Snap pads and samples to the beat. */
    var quantize by mutableStateOf(true)

    val skipped = HashSet<String>()
    val liked = HashSet<String>()
    val recentStyles = ArrayList<TransitionStyle>()

    internal var masterBpm = 0.0
    internal var speedTouched = false
    internal var remembered: String? = null

    val spanMs: Long get() = (length.minutes ?: 60) * 60_000L
    val finished: Boolean get() = length.minutes != null && elapsedMs >= spanMs

    /** The arc as drawn at [t] (0..1 of the graph's span). */
    fun arcAt(t: Double): Double = arc.energyAt(t)

    /** Target energy at [t] with the listener's vibe applied. */
    fun targetAtT(t: Double): Double = SetPlanner.target(arc.energyAt(t).toFloat(), vibe).toDouble()

    /** Target energy at set time [ms]. */
    fun targetAt(ms: Long): Double = targetAtT(if (length.minutes != null) ms.toDouble() / spanMs else (ms % spanMs).toDouble() / spanMs)

    /** Estimated on-air time of a slot: from its start to where it mixes out. */
    fun airtime(slot: DjSlot): Long {
        val f = slot.track.facts()
        val point = MixPoints.mixOutAtMs(f, f.bpm, vibe.pace, 8.0)
        return (point + 12_000L).coerceIn(45_000L, f.durationMs)
    }

    val primaryGenre: String get() = genres.firstOrNull() ?: DjEngine.YOURS
}

/**
 * Turtle DJ: builds and plays a continuous set from the listener's vibe. Lives in a MainScope, so the
 * set keeps mixing after the booth closes; it ends when the listener ends it or plays something else,
 * and then gives back their crossfade and tempo exactly as they were.
 *
 * How a mix sounds: the brain (turtle-mix) picks the next track and a transition style; at the mix
 * point the rack plays the style's script on the beat grid it measures from the audio: echo-outs,
 * loop rolls, spinbacks, risers, EQ blends with a bass swap. At the script's Seek cue the outgoing
 * track jumps to its crossfade window so the next player starts; the rack masks the jump and does all
 * the volume work while the players' own crossfade is held at full.
 */
internal object ShellDj {
    private val scope = MainScope()
    private var sessionJob = SupervisorJob()
    private val clock = TimeSource.Monotonic.markNow()
    private val random = Random.Default
    private val rack get() = DjRack.rack

    /** Whether the booth overlay is showing; any surface can open it. */
    var open by mutableStateOf(false)
    var session by mutableStateOf<DjSession?>(null)
        private set

    /** True after a start that found nothing to play (offline, or nothing for the vibe). */
    var failed by mutableStateOf(false)
        private set

    /** Composed hosts; only the first shows the booth, so it never opens twice. */
    internal val hosts = mutableStateListOf<Any>()

    private var title = ""
    private var restore: Restore? = null
    private var tempoJob: Job? = null
    private var nowJob: Job? = null
    private var replanJob: Job? = null
    private var recovered = false

    private class Restore(val crossfade: Boolean, val duration: Float, val gapless: Boolean, val speed: Float) {
        fun encode() = "$crossfade|$duration|$gapless|$speed"

        companion object {
            fun decode(raw: String): Restore? {
                val p = raw.split('|')
                if (p.size != 4) return null
                return Restore(p[0].toBoolean(), p[1].toFloatOrNull() ?: return null, p[2].toBoolean(), p[3].toFloatOrNull() ?: return null)
            }
        }
    }

    private fun now() = clock.elapsedNow().inWholeMilliseconds

    private fun log(message: String) = println("[Turtle] dj: $message")

    fun isLive(): Boolean = session != null

    /** Starts a set: digs the first tracks, takes over crossfade, plugs in the rack and starts mixing. */
    fun start(controller: PlaybackController, genres: List<String>, arc: DjArc, length: DjLength, vibe: VibeSettings, mode: DjMode, queueTitle: String) {
        end(controller)
        failed = false
        val s = DjSession(genres.ifEmpty { listOf(DjEngine.YOURS) }, arc, length, vibe, mode)
        session = s
        title = queueTitle
        sessionJob = SupervisorJob()
        scope.launch(sessionJob) {
            DjEngine.reset()
            val first = pickSlot(s, 0L, emptySet())
            if (first == null || session !== s) {
                if (session === s) {
                    session = null
                    failed = true
                }
                return@launch
            }
            s.upcoming += first
            pickSlot(s, s.airtime(first), emptySet())?.let { s.upcoming += it }
            takeOver(controller)
            DjRack.install()
            s.masterBpm = first.track.bpm.toDouble()
            ShellQueue.play(controller, s.upcoming.map { it.track.song }, title)
            s.loading = false
            drive(controller, s)
        }
    }

    /** Ends the set and gives the listener's settings back. The music keeps playing. */
    fun end(controller: PlaybackController?) {
        val s = session
        sessionJob.cancel()
        tempoJob?.cancel()
        nowJob?.cancel()
        replanJob?.cancel()
        DjRack.uninstall()
        restore?.let { r ->
            Settings.shared.crossfadeEnabled = r.crossfade
            Settings.shared.crossfadeDuration = r.duration
            Settings.shared.crossfadeGapless = r.gapless
            if (s?.speedTouched == true) controller?.setPlaybackSpeed(r.speed)
            scope.launch { TurtleBridge.setUiPref(PREF_RESTORE, "") }
        }
        restore = null
        session = null
    }

    /**
     * After a crash or a kill mid-set, the listener's crossfade and tempo are still the DJ's: the
     * saved originals are put back the next time a host appears.
     */
    fun recover(controller: PlaybackController) {
        if (recovered) return
        recovered = true
        scope.launch {
            val raw = TurtleBridge.uiPref(PREF_RESTORE).orEmpty()
            if (raw.isEmpty() || session != null) return@launch
            Restore.decode(raw)?.let { r ->
                Settings.shared.crossfadeEnabled = r.crossfade
                Settings.shared.crossfadeDuration = r.duration
                Settings.shared.crossfadeGapless = r.gapless
                if (Settings.shared.playbackSpeed != r.speed) controller.setPlaybackSpeed(r.speed)
            }
            TurtleBridge.setUiPref(PREF_RESTORE, "")
        }
    }

    private fun takeOver(controller: PlaybackController) {
        if (restore == null) {
            val r = Restore(Settings.shared.crossfadeEnabled, Settings.shared.crossfadeDuration, Settings.shared.crossfadeGapless, Settings.shared.playbackSpeed)
            restore = r
            scope.launch { TurtleBridge.setUiPref(PREF_RESTORE, r.encode()) }
        }
        Settings.shared.crossfadeEnabled = true
        Settings.shared.crossfadeDuration = DEFAULT_WINDOW_S
        Settings.shared.crossfadeGapless = false
        session?.speed = controller.state.value.playbackSpeed.takeIf { it > 0f } ?: 1f
    }

    // ---- The set's heartbeat ----------------------------------------------------------------

    private suspend fun drive(controller: PlaybackController, s: DjSession) {
        var misses = 0
        var last = now()
        var silentSince = now()
        var awaitingSince = -1L
        var wasRunning = false
        var mixFrom: String? = null
        var lastStatus = 0L
        var lastPhase = TransitionPhase.Idle
        while (session === s) {
            delay(TICK_MS)
            val st = controller.state.value
            val t = now()
            if (st.isPlaying) s.elapsedMs += t - last
            last = t
            // Playing anything else ends the set (after a grace for the opening queue to land).
            if (ShellQueue.activeTitle(st) != title) {
                if (++misses >= MISSES_TO_END) return end(controller)
                continue
            }
            misses = 0
            val song = st.currentSong ?: continue
            if (song.id != s.current?.track?.song?.id) {
                if (!onTrackChanged(controller, s, song)) return end(controller)
                // The incoming track became current without the rack seeing a second deck: settle up.
                if (rack.transition.running && rack.transition.phase != TransitionPhase.Post) rack.abort()
            }
            val slot = s.current ?: continue

            // Does the rack hear us? (Not when the platform has no processing path or audio is offloaded.)
            if (rack.receivingAudio) silentSince = t
            val fx = !st.isPlaying || t - silentSince < FX_LOST_MS
            if (fx != s.fxAvailable) {
                s.fxAvailable = fx
                log("effects rack ${if (fx) "hears the audio" else "hears nothing: plain crossfades"}")
            }

            readDeck(s, slot)
            if (t - lastStatus >= STATUS_MS) {
                lastStatus = t
                val d = rack.onAir
                log("status: pos ${st.positionMs}/${st.durationMs} mixPoint ${s.mixPointMs} hears=${rack.receivingAudio} deck bpm=${d?.bpm} measured=${d?.bpmMeasured} conf=${d?.confidence} energy=${d?.energy} phase=${rack.transition.phase} next=${s.upcoming.firstOrNull()?.plan?.style}")
            }
            handleCues(controller, s)

            // Mirror the transition.
            val tr = rack.transition
            val running = tr.running
            if (tr.phase != lastPhase) {
                log("phase ${lastPhase} -> ${tr.phase} at ${st.positionMs}/${st.durationMs} beat ${tr.beat}")
                lastPhase = tr.phase
            }
            if (running) {
                s.mixProgress = tr.progress
                if (tr.phase == TransitionPhase.AwaitingIncoming) {
                    if (awaitingSince < 0) awaitingSince = t
                    if (t - awaitingSince > AWAIT_INCOMING_MS) {
                        log("incoming deck never started; aborting the mix")
                        rack.abort()
                        controller.skipToNext()
                    }
                } else {
                    awaitingSince = -1L
                }
            } else {
                awaitingSince = -1L
            }
            if (wasRunning && !running) {
                DjRack.afterMix = st.currentSong?.id == mixFrom
                onMixDone(controller, s)
            }
            if (!wasRunning && running) mixFrom = song.id
            wasRunning = running

            ensurePlan(s)
            s.mixPointMs = mixPoint(s, slot, st.durationMs)
            val remaining = st.durationMs - st.positionMs
            // After a mix the old track runs on (muted) to the end of its window: never mix it twice.
            if (!s.mixing && !DjRack.afterMix && st.isPlaying && s.upcoming.isNotEmpty() && st.durationMs > 0 && st.positionMs > MIN_PLAY_MS) {
                when (s.mode) {
                    DjMode.Auto -> if (st.positionMs >= s.mixPointMs) startMix(controller, s, manualStyle = null)
                    DjMode.You -> if (remaining < SAFETY_NET_MS) {
                        setNow(s, DjNow.SafetyNet, hold = false)
                        startMix(controller, s, manualStyle = s.youStyle)
                    }
                }
            }
            if (s.upcoming.size < AHEAD && !s.planning && !s.finished) scope.launch(sessionJob) { planMore(controller, s) }
            if (s.mode == DjMode.You && s.suggestions.isEmpty() && !s.planning && s.upcoming.isNotEmpty()) refreshSuggestions(s)
        }
    }

    /** Copies the rack's live readings into the session; remembers a track's measured facts once. */
    private fun readDeck(s: DjSession, slot: DjSlot) {
        // Mid-mix and just after it, the rack's on-air deck may already be the next track.
        if (s.mixing || DjRack.afterMix) return
        val deck = rack.onAir ?: return
        if (!deck.active) return
        val measured = deck.bpmMeasured
        val native = if (measured) deck.bpm / s.speed.coerceAtLeast(0.5f) else 0f
        if (measured) {
            s.liveBpm = native
            s.liveBpmMeasured = true
            // Tempo-shifting songs can drop below the tracker's confidence for a while: the last
            // confident reading stays the deck's tempo, so scripts keep timing on the real beat.
            rack.setTempoHint(app.turtleplayer.mix.Deck.Out, deck.bpm)
        } else if (!s.liveBpmMeasured) {
            s.liveBpm = slot.track.bpm.toFloat()
        }
        if (deck.framesAnalyzed > 0) s.liveEnergy = deck.energy
        val id = slot.track.song.id
        if (measured && s.remembered != id && deck.framesAnalyzed > REMEMBER_AFTER_FRAMES) {
            s.remembered = id
            DjMemory.remember(id, DjMemory.Facts(native, deck.energy, deck.loudnessDb))
            log("measured \"${slot.track.song.title}\": ${native} BPM (guess ${slot.track.bpm}), energy ${deck.energy}, confidence ${deck.confidence}")
            // The measured tempo replaces the guess for the rest of this track (mix point, script).
            if (abs(native - slot.track.bpm) > 1.5f) {
                val updated = slot.copy(track = slot.track.copy(bpm = native.toInt(), bpmMeasured = true, energy = deck.energy.toDouble(), energyMeasured = true))
                s.current = updated
                s.upcoming.firstOrNull()?.let { next -> replaceFirst(s, next.copy(plan = null)) }
            }
        }
    }

    private fun handleCues(controller: PlaybackController, s: DjSession) {
        for (cue in rack.pollCues()) {
            when (cue) {
                CueKind.Seek -> {
                    val st = controller.state.value
                    val window = (Settings.shared.crossfadeDuration * 1000).toLong()
                    if (st.durationMs > 0 && st.durationMs - st.positionMs > window + SEEK_LEAD_MS) {
                        log("seek cue: ${st.positionMs} -> ${st.durationMs - window - SEEK_LEAD_MS / 2} of ${st.durationMs} (window ${window} ms)")
                        controller.seekTo(st.durationMs - window - SEEK_LEAD_MS / 2)
                    }
                }
                is CueKind.Sample -> Unit
            }
        }
    }

    /** Moves the decks along. Returns false when the set is over (time's up and autoplay took over). */
    private fun onTrackChanged(controller: PlaybackController, s: DjSession, song: SongItem): Boolean {
        s.current?.let { s.played += it }
        DjRack.afterMix = false
        // A fader left mid-way when the old track ran out: finish the move so the new one plays at full.
        if (s.manualMix) {
            rack.setFader(1f)
            s.mixProgress = 1f
        }
        val i = s.upcoming.indexOfFirst { it.track.song.id == song.id }
        if (i < 0 && s.finished) return false
        val slot = if (i >= 0) {
            repeat(i) { s.upcoming.removeAt(0) }
            s.upcoming.removeAt(0)
        } else {
            DjSlot(DjEngine.describe(song, s.primaryGenre), s.elapsedMs, s.targetAt(s.elapsedMs))
        }
        s.current = slot.copy(startMs = s.elapsedMs)
        s.mixPointMs = Long.MAX_VALUE
        s.liveBpm = slot.track.bpm.toFloat()
        s.liveBpmMeasured = slot.track.bpmMeasured
        s.youStyle = null
        s.suggestions.clear()
        rack.setTempoHint(app.turtleplayer.mix.Deck.Out, slot.track.bpm * s.speed)
        val bpm = slot.track.bpm.toDouble()
        s.masterBpm = if (s.masterBpm <= 0.0) bpm else s.masterBpm * 0.6 + nearestOctave(bpm, s.masterBpm) * 0.4
        if (!s.mixing) {
            glideTempo(controller, s)
            setNow(s, DjNow.OnAir(s.current!!), hold = false)
        }
        return true
    }

    private fun onMixDone(controller: PlaybackController, s: DjSession) {
        log("mix done: ${s.mixStyle}")
        s.mixing = false
        s.manualMix = false
        s.mixProgress = 0f
        rack.setFader(null)
        // Back to a window shorter than any mix point, so the next track never crossfades on its own early.
        Settings.shared.crossfadeDuration = DEFAULT_WINDOW_S
        s.mixStyle?.let { st ->
            s.recentStyles += st
            while (s.recentStyles.size > 6) s.recentStyles.removeAt(0)
        }
        s.mixStyle = null
        s.current?.let { setNow(s, DjNow.OnAir(it), hold = false) }
        glideTempo(controller, s)
    }

    private fun nearestOctave(bpm: Double, master: Double): Double =
        listOf(bpm, bpm * 2, bpm / 2).minBy { abs(it - master) }

    /** Plans how to mix into the next track (when it has no plan yet). */
    private fun ensurePlan(s: DjSession) {
        val cur = s.current ?: return
        val next = s.upcoming.firstOrNull() ?: return
        if (next.plan != null || s.mixing) return
        val plan = TransitionPlanner.plan(facts(s, cur), next.track.facts(next.requested), s.vibe, s.recentStyles, random, s.fxAvailable)
        replaceFirst(s, next.copy(plan = plan))
    }

    private fun replaceFirst(s: DjSession, slot: DjSlot) {
        if (s.upcoming.isNotEmpty() && s.upcoming[0].track.song.id == slot.track.song.id) s.upcoming[0] = slot
    }

    /** The current track's facts with what the rack has measured. */
    private fun facts(s: DjSession, slot: DjSlot): TrackFacts {
        val f = slot.track.facts(slot.requested)
        return if (s.liveBpmMeasured && s.liveBpm > 0f) f.copy(bpm = s.liveBpm, bpmMeasured = true) else f
    }

    /** Where the current track starts its transition out: from the brain, by the listener's pace. */
    private fun mixPoint(s: DjSession, slot: DjSlot, durationMs: Long): Long {
        if (durationMs <= 0L) return Long.MAX_VALUE
        val f = facts(s, slot).copy(durationMs = durationMs)
        val preBeats = s.upcoming.firstOrNull()?.plan?.script?.pre?.lengthBeats ?: 8.0
        return MixPoints.mixOutAtMs(f, f.bpm * s.speed, s.vibe.pace, preBeats)
    }

    /**
     * Starts the transition into the next track: [manualStyle] (You mode, or Mix now with a chosen
     * move) or the planned one. Without the rack it falls back to a plain crossfade.
     */
    private fun startMix(controller: PlaybackController, s: DjSession, manualStyle: TransitionStyle?, script: MixScript? = null, quantize: Quantize = Quantize.Bar) {
        if (s.mixing || DjRack.afterMix) return
        val cur = s.current ?: return
        val next = s.upcoming.firstOrNull() ?: return
        val outF = facts(s, cur)
        val inF = next.track.facts(next.requested)
        val planned = next.plan ?: TransitionPlanner.plan(outF, inF, s.vibe, s.recentStyles, random, s.fxAvailable)
        val style = manualStyle ?: planned.style
        val chosen = script ?: if (manualStyle != null && manualStyle != planned.style) TransitionPlanner.script(manualStyle, outF, inF) else planned.script
        // At least 8 s: Android needs a moment to start the next player inside the window. The script
        // silences the old track on time anyway; it just runs on muted until the window closes.
        val overlap = TransitionPlanner.overlapSeconds(chosen, outF.bpm * s.speed).coerceIn(MIN_WINDOW_S, DjRack.MAX_WINDOW_MS / 1000f)
        s.mixing = true
        s.mixStyle = style
        s.manualMix = chosen.manualFader
        s.mixProgress = 0f
        setNow(s, DjNow.Mixing(style, next, manual = manualStyle != null || chosen.manualFader), hold = false)
        if (!s.fxAvailable) {
            // No rack: a plain crossfade of the arc's length from near the end.
            Settings.shared.crossfadeDuration = s.arc.fadeSeconds
            scope.launch(sessionJob) { plainMix(controller, s) }
            return
        }
        Settings.shared.crossfadeDuration = overlap
        rack.setTempoHint(app.turtleplayer.mix.Deck.Out, outF.bpm * s.speed)
        rack.setTempoHint(app.turtleplayer.mix.Deck.In, inF.bpm * s.speed)
        log("mix: ${style} \"${cur.track.song.title}\" -> \"${next.track.song.title}\", pre ${chosen.pre.lengthBeats} + post ${chosen.post.lengthBeats} beats at ${outF.bpm} BPM, window ${overlap} s")
        if (!rack.run(chosen, quantize)) {
            log("mix refused: a script is already running")
            s.mixing = false
            s.mixStyle = null
        }
    }

    /** Fallback when the rack hears nothing: jump near the end and let the players crossfade. */
    private suspend fun plainMix(controller: PlaybackController, s: DjSession) {
        val from = controller.state.value.currentSong?.id
        try {
            val st = controller.state.value
            val window = (s.arc.fadeSeconds * 1000).toLong()
            if (st.durationMs - st.positionMs > window + SEEK_LEAD_MS) controller.seekTo(st.durationMs - window - SEEK_LEAD_MS / 2)
            val steps = 50
            for (i in 1..steps) {
                s.mixProgress = i.toFloat() / steps
                delay((window + SEEK_LEAD_MS) / steps)
                if (controller.state.value.currentSong?.id != from) break
            }
            withTimeoutOrNull(4_000) { while (controller.state.value.currentSong?.id == from) delay(100) }
        } finally {
            onMixDone(controller, s)
        }
    }

    /** Rides the tempo toward the current track's nudge times the listener's tempo, in small steps. */
    private fun glideTempo(controller: PlaybackController, s: DjSession) {
        val slot = s.current ?: return
        val target = (DjEngine.tempoNudge(slot.track.bpm, s.masterBpm) * s.userTempo).coerceIn(0.88f, 1.12f)
        if (abs(target - s.speed) < 0.004f) return
        tempoJob?.cancel()
        tempoJob = scope.launch(sessionJob) {
            val from = s.speed
            for (i in 1..TEMPO_STEPS) {
                val v = from + (target - from) * i / TEMPO_STEPS
                controller.setPlaybackSpeed(v)
                s.speed = v
                s.speedTouched = true
                delay(TEMPO_STEP_MS)
            }
        }
    }

    /** Shows [now] in the narration line; transient news falls back to what is on air after a while. */
    private fun setNow(s: DjSession, now: DjNow, hold: Boolean) {
        nowJob?.cancel()
        s.now = now
        if (hold) {
            nowJob = scope.launch(sessionJob) {
                delay(NEWS_MS)
                if (session === s) s.now = if (s.mixing) s.now else s.current?.let { DjNow.OnAir(it) } ?: DjNow.Digging
            }
        }
    }

    // ---- Planning ---------------------------------------------------------------------------

    private fun knownIds(s: DjSession): Set<String> = buildSet {
        s.played.forEach { add(it.track.song.id) }
        s.current?.let { add(it.track.song.id) }
        s.upcoming.forEach { add(it.track.song.id) }
    }

    /** The set so far as the planner sees it: the last planned track is the one the next pick follows. */
    private fun context(s: DjSession): SetContext {
        val sequence = (s.played + listOfNotNull(s.current) + s.upcoming).map { it.track.facts(it.requested) }
        return SetContext(onAir = sequence.lastOrNull(), history = sequence.dropLast(1), skipped = s.skipped, liked = s.liked)
    }

    /** Candidates, minus other uploads of songs already in the set ("X (Radio Edit)" after "X – Radio Edit"). */
    private suspend fun candidates(s: DjSession, target: Double, extra: Set<String>): Map<String, DjTrack> {
        val inSet = (s.played + listOfNotNull(s.current) + s.upcoming).mapTo(HashSet()) { songKey(it.track.song) }
        return DjEngine.candidates(s.genres, target, knownIds(s) + extra, s.vibe.fresh)
            .filter { songKey(it.song) !in inSet }
            .distinctBy { songKey(it.song) }
            .associateBy { it.song.id }
    }

    /** A song's identity across uploads: first artist and the title without versions, features or extras. */
    private fun songKey(song: SongItem): String {
        val title = song.title.lowercase()
            .replace(Regex("""\(.*?\)|\[.*?]"""), " ")
            .substringBefore(" - ").substringBefore(" – ").substringBefore(" feat").substringBefore(" ft.")
            .filter { it.isLetterOrDigit() }
        return (song.artists.firstOrNull()?.name?.lowercase()?.filter { it.isLetterOrDigit() }.orEmpty()) + "|" + title
    }

    private suspend fun pickSlot(s: DjSession, startMs: Long, extra: Set<String>): DjSlot? {
        val target = s.targetAt(startMs)
        val pool = candidates(s, target, extra)
        val pick = SetPlanner.pick(pool.values.map { it.facts() }, context(s), target.toFloat(), s.vibe, random) ?: return null
        val track = pool[pick.track.id] ?: return null
        DjEngine.consume(track.song.id, track.genre)
        return DjSlot(track, startMs, target, reasons = pick.reasons)
    }

    private fun plannedStart(s: DjSession): Long {
        val base = s.current?.let { it.startMs + s.airtime(it) } ?: s.elapsedMs
        return base + s.upcoming.sumOf { s.airtime(it) }
    }

    private suspend fun planMore(controller: PlaybackController, s: DjSession) {
        if (s.planning) return
        s.planning = true
        try {
            while (session === s && s.upcoming.size < AHEAD) {
                val start = plannedStart(s)
                if (s.length.minutes != null && start >= s.spanMs) break
                val queued = controller.state.value.queue.mapTo(HashSet()) { it.id }
                val slot = pickSlot(s, start, queued) ?: break
                if (session !== s) break
                s.upcoming += slot
                controller.addToQueue(listOf(slot.track.song))
            }
        } finally {
            s.planning = false
        }
    }

    /** Drops planned tracks (requests and the one mixing stay) and plans them again for the new vibe. */
    private suspend fun replan(controller: PlaybackController, s: DjSession, keepFirst: Boolean) {
        val remove = ShellInfinity.removeAt ?: { i: Int -> controller.removeQueueItem(i) }
        val from = if (keepFirst || s.mixing) 1 else 0
        val drop = s.upcoming.drop(from).filter { !it.requested && !it.chosenByYou }
        if (drop.isEmpty()) return
        val st = controller.state.value
        val indices = drop.mapNotNull { d ->
            st.queue.indexOfLast { it.id == d.track.song.id }.takeIf { it > st.currentQueueIndex }
        }.sortedDescending()
        indices.forEach(remove)
        val removedIds = indices.map { st.queue[it].id }.toSet()
        s.upcoming.removeAll { it.track.song.id in removedIds }
        delay(250)
        planMore(controller, s)
        // Plans exist only for the next track; a new one (or a new vibe) needs a new plan.
        s.upcoming.firstOrNull()?.let { if (!s.mixing) replaceFirst(s, it.copy(plan = null)) }
        if (s.mode == DjMode.You) refreshSuggestions(s)
    }

    /** You mode: the best five next tracks for this moment. */
    private fun refreshSuggestions(s: DjSession) {
        scope.launch(sessionJob) {
            val cur = s.current ?: return@launch
            val target = s.targetAt(cur.startMs + s.airtime(cur))
            val pool = candidates(s, target, emptySet())
            val ctx = SetContext(onAir = cur.track.facts(), history = s.played.map { it.track.facts() }, skipped = s.skipped, liked = s.liked)
            val picks = ArrayList<Pick>()
            var left = pool.values.map { it.facts() }
            repeat(SUGGESTIONS) {
                val p = SetPlanner.pick(left, ctx, target.toFloat(), s.vibe, random) ?: return@repeat
                picks += p
                left = left.filter { it.id != p.track.id && it.artist != p.track.artist }
            }
            suggestionTracks.putAll(pool.filterKeys { id -> picks.any { it.track.id == id } })
            s.suggestions.clear()
            s.suggestions.addAll(picks)
        }
    }

    private val suggestionTracks = HashMap<String, DjTrack>()

    fun suggestionSong(id: String): SongItem? = suggestionTracks[id]?.song

    // ---- Controls ---------------------------------------------------------------------------

    /** Mix into the next track now: the planned move (Auto) or the one you picked (You), on the next bar. */
    fun mixNow(controller: PlaybackController) {
        val s = session ?: return
        if (s.mixing || s.upcoming.isEmpty()) return
        startMix(controller, s, manualStyle = if (s.mode == DjMode.You) s.youStyle else null)
    }

    /** Mix now with a specific move (the move picker in either mode). */
    fun mixWith(controller: PlaybackController, style: TransitionStyle) {
        val s = session ?: return
        if (s.mixing || s.upcoming.isEmpty()) return
        startMix(controller, s, manualStyle = style)
    }

    /** Skip: the DJ learns (less of this artist and sound this set) and cuts to the next track on the beat. */
    fun skip(controller: PlaybackController) {
        val s = session ?: return controller.skipToNext()
        val cur = s.current
        if (cur != null && !cur.requested) {
            s.skipped += cur.track.song.id
            setNow(s, DjNow.Skipped(cur.track.song.artists.firstOrNull()?.name.orEmpty()), hold = true)
            // Skips change what fits: pick the rest again without that sound.
            replanJob?.cancel()
            replanJob = scope.launch(sessionJob) { replan(controller, s, keepFirst = true) }
        }
        if (s.mixing) return
        if (!s.fxAvailable || s.upcoming.isEmpty()) return controller.skipToNext()
        val next = s.upcoming.first()
        val outF = cur?.let { facts(s, it) } ?: return controller.skipToNext()
        startMix(controller, s, manualStyle = TransitionStyle.Cut, script = TransitionPlanner.script(TransitionStyle.Cut, outF, next.track.facts()), quantize = Quantize.Beat)
    }

    /** Like the track on air: saved to your likes, and the DJ leans toward this artist for the rest of the set. */
    fun like(): Boolean {
        val s = session ?: return false
        val cur = s.current ?: return false
        val now = SongLikeState.toggleLike(cur.track.song)
        if (now) {
            s.liked += cur.track.song.id
            setNow(s, DjNow.Liked(cur.track.song.artists.firstOrNull()?.name.orEmpty()), hold = true)
        } else {
            s.liked -= cur.track.song.id
        }
        return now
    }

    /** Hype (up) or Chill (down): moves the vibe's energy and re-plans what's next. */
    fun steer(controller: PlaybackController, up: Boolean) {
        val s = session ?: return
        val v = s.vibe
        setVibe(controller, v.copy(energy = (v.energy + if (up) 0.15f else -0.15f).coerceIn(0f, 1f)))
    }

    /** The listener retuned the vibe: shown at once, and up next is re-planned after they settle. */
    fun setVibe(controller: PlaybackController, vibe: VibeSettings) {
        val s = session ?: return
        val old = s.vibe
        if (old == vibe) return
        s.vibe = vibe
        val changed = buildList {
            if (old.energy != vibe.energy) add(VibeChange.Energy)
            if (old.fresh != vibe.fresh) add(VibeChange.Fresh)
            if (old.flair != vibe.flair) add(VibeChange.Flair)
            if (old.pace != vibe.pace) add(VibeChange.Pace)
            if (old.variety != vibe.variety) add(VibeChange.Variety)
        }
        replanJob?.cancel()
        replanJob = scope.launch(sessionJob) {
            delay(VIBE_SETTLE_MS)
            // Flair and pace only change how and when the next mix happens; the rest changes the picks.
            val picksChange = changed.any { it == VibeChange.Energy || it == VibeChange.Fresh || it == VibeChange.Variety }
            if (picksChange) replan(controller, s, keepFirst = false)
            else s.upcoming.firstOrNull()?.let { if (!s.mixing) replaceFirst(s, it.copy(plan = null)) }
            setNow(s, DjNow.Remixed(changed.singleOrNull() ?: VibeChange.Several), hold = true)
        }
    }

    fun setMode(mode: DjMode) {
        val s = session ?: return
        s.mode = mode
        if (mode == DjMode.You) refreshSuggestions(s)
    }

    /** You mode: play [pick] next instead of what was planned. */
    fun chooseNext(controller: PlaybackController, pick: Pick) {
        val s = session ?: return
        if (s.mixing) return
        val track = suggestionTracks[pick.track.id] ?: return
        scope.launch(sessionJob) {
            val remove = ShellInfinity.removeAt ?: { i: Int -> controller.removeQueueItem(i) }
            s.upcoming.firstOrNull()?.let { old ->
                val st = controller.state.value
                st.queue.indexOfLast { it.id == old.track.song.id }.takeIf { it > st.currentQueueIndex }?.let(remove)
                s.upcoming.removeAt(0)
            }
            DjEngine.consume(track.song.id, track.genre)
            controller.playNext(listOf(track.song))
            val start = s.current?.let { it.startMs + s.airtime(it) } ?: s.elapsedMs
            s.upcoming.add(0, DjSlot(track, start, s.targetAt(start), reasons = pick.reasons, chosenByYou = true))
            s.suggestions.removeAll { it.track.id == pick.track.id }
        }
    }

    /** Slots a listener's request right after the current track. */
    fun request(controller: PlaybackController, track: DjTrack) {
        val s = session ?: return
        controller.playNext(listOf(track.song))
        val start = s.current?.let { it.startMs + s.airtime(it) } ?: s.elapsedMs
        s.upcoming.add(0, DjSlot(track, start, s.targetAt(start), requested = true, reasons = listOf(PickReason.Requested)))
        setNow(s, DjNow.Requested(track.song.title), hold = true)
    }

    /** You mode: the crossfader. Moving it off A starts a fader transition into the next track. */
    fun fader(controller: PlaybackController, position: Float) {
        val s = session ?: return
        // A finger at the far end rarely reaches exactly 1: the last 3 % counts as B, so the mix completes.
        val x = position.coerceIn(0f, 1f).let { if (it > 0.97f) 1f else it }
        if (!s.mixing) {
            if (x < FADER_START || s.upcoming.isEmpty() || !s.fxAvailable) return
            val cur = s.current ?: return
            val next = s.upcoming.first()
            startMix(controller, s, manualStyle = TransitionStyle.Blend, script = TransitionPlanner.faderScript(facts(s, cur), next.track.facts()), quantize = Quantize.Beat)
        }
        if (s.manualMix) {
            s.mixProgress = x
            rack.setFader(x)
        }
    }

    // ---- Live effects (the listener's layer on the deck on air) -----------------------------

    fun fx(param: FxParam, value: Float, rampMs: Int = 30) {
        val s = session ?: return
        s.userFx[param] = value
        rack.setUser(param, value, rampMs)
    }

    fun resetFx() {
        val s = session ?: return
        s.userFx.clear()
        rack.resetUser(300)
    }

    fun sample(id: SampleId) {
        val s = session ?: return
        rack.trigger(id, if (s.quantize) Quantize.Beat else Quantize.None)
    }

    /** Speed up (+) or slow down (-) the whole set by 2 % per tap, within ±8 %. */
    fun nudgeTempo(controller: PlaybackController, up: Boolean) {
        val s = session ?: return
        s.userTempo = (s.userTempo + if (up) 0.02f else -0.02f).coerceIn(0.92f, 1.08f)
        glideTempo(controller, s)
    }

    fun resetTempo(controller: PlaybackController) {
        val s = session ?: return
        s.userTempo = 1f
        glideTempo(controller, s)
    }

    private const val PREF_RESTORE = "dj_restore"
    private const val TICK_MS = 40L
    private const val MISSES_TO_END = 125
    private const val SEEK_LEAD_MS = 600L
    private const val TEMPO_STEPS = 4
    private const val TEMPO_STEP_MS = 450L
    private const val AHEAD = 3
    private const val SUGGESTIONS = 5
    private const val DEFAULT_WINDOW_S = 12f
    private const val FX_LOST_MS = 3_000L
    private const val AWAIT_INCOMING_MS = 7_000L
    private const val MIN_PLAY_MS = 15_000L
    private const val SAFETY_NET_MS = 35_000L
    private const val REMEMBER_AFTER_FRAMES = 44_100L * 40
    private const val NEWS_MS = 4_500L
    private const val VIBE_SETTLE_MS = 700L
    private const val FADER_START = 0.04f
    private const val STATUS_MS = 30_000L
    private const val MIN_WINDOW_S = 8f
}

/** Shows the Turtle DJ booth when it is open; put one in every top-level surface. */
@Composable
internal fun TurtleDjHost() {
    val token = remember { Any() }
    val controller = LocalPlaybackController.current
    DisposableEffect(token) {
        ShellDj.hosts.add(token)
        onDispose { ShellDj.hosts.remove(token) }
    }
    LaunchedEffect(Unit) { ShellDj.recover(controller) }
    if (app.turtleplayer.ui.theme.ShellPlatform.desktop) {
        // Desktop: any "open Turtle DJ" lands on the sidebar page, never a dialog over the window.
        LaunchedEffect(ShellDj.open) {
            if (ShellDj.open) {
                ShellDesktop.explore = false
                ShellDesktop.dj = true
                ShellDj.open = false
            }
        }
        return
    }
    if (ShellDj.open && ShellDj.hosts.firstOrNull() === token) TurtleDjOverlay()
}
