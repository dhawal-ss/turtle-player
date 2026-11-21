package app.turtleplayer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.turtleplayer.bridge.DjArc
import app.turtleplayer.bridge.DjEngine
import app.turtleplayer.bridge.DjTrack
import app.turtleplayer.bridge.TurtleBridge
import com.metrolist.shared.equalizer.EqualizerConfiguration
import com.metrolist.shared.equalizer.EqualizerProfile
import com.metrolist.shared.equalizer.EqualizerProfiles
import com.metrolist.shared.equalizer.FilterType
import com.metrolist.shared.equalizer.MAX_EQUALIZER_BANDS
import com.metrolist.shared.equalizer.ParametricEqBand
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.turtle.TurtleAudioFx
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.time.TimeSource

/** How long a set runs; [Endless] repeats the arc every hour. */
internal enum class DjLength(val minutes: Int?) { M30(30), M60(60), M120(120), Endless(null) }

/** One track in the set: when it starts in set time, and the energy the arc wanted there. */
@Immutable
internal data class DjSlot(val track: DjTrack, val startMs: Long, val target: Double, val requested: Boolean = false)

/** A running Turtle DJ set. Snapshot state, so the booth redraws as the set moves. */
@Stable
internal class DjSession(val genres: List<String>, val arc: DjArc, val length: DjLength) {
    val played = mutableStateListOf<DjSlot>()
    var current by mutableStateOf<DjSlot?>(null)
    val upcoming = mutableStateListOf<DjSlot>()

    /** Hype (+) and Chill (-) shift the rest of the arc. */
    var bias by mutableDoubleStateOf(0.0)
    var loading by mutableStateOf(true)
    var planning by mutableStateOf(false)
    var mixing by mutableStateOf(false)

    /** Crossfader: 0 is deck A (on air), 1 is deck B (the incoming track). Read it in draw. */
    var mixProgress by mutableFloatStateOf(0f)

    /** Media position at which the current track starts mixing out. */
    var mixPointMs by mutableLongStateOf(Long.MAX_VALUE)

    /** Set time: how long the set has been playing (pauses excluded). Read it in draw or a leaf. */
    var elapsedMs by mutableLongStateOf(0L)

    /** Playback rate applied now (tempo nudges keep pitch: both players time-stretch). */
    var speed by mutableFloatStateOf(1f)

    /** The listener's Speed up / Slow down on top of the automatic nudges. */
    var userTempo by mutableFloatStateOf(1f)
    var filterOn by mutableStateOf(false)
    var boostOn by mutableStateOf(false)
    var killOn by mutableStateOf(false)

    internal var masterBpm = 0.0
    internal var speedTouched = false

    val fadeMs: Long get() = (arc.fadeSeconds * 1000).toLong()
    val spanMs: Long get() = (length.minutes ?: 60) * 60_000L
    val finished: Boolean get() = length.minutes != null && elapsedMs >= spanMs

    /** Target energy at [t] (0..1 of the graph's span), with Hype/Chill applied. */
    fun arcAt(t: Double): Double = (arc.energyAt(t) + bias).coerceIn(0.05, 1.0)

    /** Target energy at set time [ms]. */
    fun targetAt(ms: Long): Double = arcAt(if (length.minutes != null) ms.toDouble() / spanMs else (ms % spanMs).toDouble() / spanMs)

    /** Estimated on-air time of a slot: its length minus the outro the DJ skips. */
    fun airtime(slot: DjSlot): Long {
        val durationMs = (slot.track.song.duration ?: 210L) * 1000L
        return (durationMs - slot.track.outroTrimMs).coerceAtLeast(60_000L)
    }

    val primaryGenre: String get() = genres.firstOrNull() ?: "party"
}

/**
 * Turtle DJ: builds and plays a continuous set from the listener's vibe. Lives in a MainScope, so the
 * set keeps mixing after the booth closes; it ends when the listener ends it or plays something else,
 * and then gives back their crossfade, tempo and equalizer exactly as they were.
 *
 * How a mix sounds: at each track's mix point (before its outro) the outgoing track is filtered and
 * its bass cut ([DjFx], live EQ), then it jumps to its last seconds so the players' own crossfade
 * (DJ-length, set here) overlaps it with the next track while the filter opens and the bass swaps in.
 */
internal object ShellDj {
    private val scope = MainScope()
    private var sessionJob = SupervisorJob()
    private val clock = TimeSource.Monotonic.markNow()

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

    /** True while a set is playing (its queue is still the one on air). */
    fun isLive(): Boolean = session != null

    /** Starts a set: digs the first tracks, takes over crossfade, and starts mixing. */
    fun start(controller: PlaybackController, genres: List<String>, arc: DjArc, length: DjLength, queueTitle: String) {
        end(controller)
        failed = false
        val s = DjSession(genres.ifEmpty { arc.defaultGenres }, arc, length)
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
            takeOver(controller, arc)
            s.masterBpm = first.track.bpm.toDouble()
            DjFx.activate()
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
        DjFx.deactivate()
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

    private fun takeOver(controller: PlaybackController, arc: DjArc) {
        if (restore == null) {
            val r = Restore(Settings.shared.crossfadeEnabled, Settings.shared.crossfadeDuration, Settings.shared.crossfadeGapless, Settings.shared.playbackSpeed)
            restore = r
            scope.launch { TurtleBridge.setUiPref(PREF_RESTORE, r.encode()) }
        }
        Settings.shared.crossfadeEnabled = true
        Settings.shared.crossfadeDuration = arc.fadeSeconds
        Settings.shared.crossfadeGapless = false
        session?.speed = controller.state.value.playbackSpeed.takeIf { it > 0f } ?: 1f
    }

    // ---- The set's heartbeat ----------------------------------------------------------------

    private suspend fun drive(controller: PlaybackController, s: DjSession) {
        var misses = 0
        var last = now()
        var pendingIntro: Pair<String, Long>? = s.upcoming.firstOrNull()?.let { it.track.song.id to it.track.introSkipMs }
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
            }
            val slot = s.current ?: continue
            pendingIntro?.let { (id, ms) ->
                if (id == song.id && st.durationMs > 0 && !st.isLoading) {
                    pendingIntro = null
                    if (ms > 0 && st.positionMs < 5_000 && ms < st.durationMs - 90_000) controller.seekTo(ms)
                }
            }
            s.mixPointMs = mixPoint(s, slot, st.durationMs)
            // The 15 s floor guards against a stale duration right after a track change.
            if (!s.mixing && st.isPlaying && s.upcoming.isNotEmpty() && st.positionMs >= max(s.mixPointMs, 15_000L)) {
                scope.launch(sessionJob) { blend(controller, s, SWEEP_MS) }
            }
            if (s.upcoming.size < AHEAD && !s.planning && !s.finished) scope.launch(sessionJob) { planMore(controller, s) }
        }
    }

    /** Moves the decks along. Returns false when the set is over (time's up and autoplay took over). */
    private fun onTrackChanged(controller: PlaybackController, s: DjSession, song: SongItem): Boolean {
        s.current?.let { s.played += it }
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
        val bpm = slot.track.bpm.toDouble()
        s.masterBpm = if (s.masterBpm <= 0.0) bpm else s.masterBpm * 0.6 + nearestOctave(bpm, s.masterBpm) * 0.4
        if (!s.mixing) glideTempo(controller, s)
        return true
    }

    private fun nearestOctave(bpm: Double, master: Double): Double =
        listOf(bpm, bpm * 2, bpm / 2).minBy { abs(it - master) }

    /** Where the current track starts mixing out: before its outro, earlier when the energy is high. */
    private fun mixPoint(s: DjSession, slot: DjSlot, durationMs: Long): Long {
        if (durationMs <= 0L) return Long.MAX_VALUE
        val factor = 0.7 + s.targetAt(s.elapsedMs) * 0.6
        val trim = (slot.track.outroTrimMs * factor).toLong()
        val point = durationMs - trim - SWEEP_MS
        val natural = (durationMs - s.fadeMs - SWEEP_MS - 600).coerceAtLeast(durationMs / 2)
        return if (trim <= s.fadeMs || point < durationMs / 2) natural else point
    }

    /**
     * One DJ mix. The outgoing track is filtered and its bass cut over [sweepMs]; it then jumps to its
     * last seconds (the players only crossfade at a file's end), and while the players blend the two
     * the filter opens and the bass swaps back in, so the incoming track arrives whole.
     */
    private suspend fun blend(controller: PlaybackController, s: DjSession, sweepMs: Long) {
        if (s.mixing) return
        s.mixing = true
        val from = controller.state.value.currentSong?.id
        try {
            DjFx.ramp(DjFx.Fx.MixFilter, 0.7f, sweepMs)
            DjFx.ramp(DjFx.Fx.MixKill, 0.65f, sweepMs * 3 / 4)
            DjFx.ramp(DjFx.Fx.MixDuck, 0.2f, sweepMs)
            animateMix(s, 0f, 0.3f, sweepMs)
            val st = controller.state.value
            if (st.currentSong?.id == from && st.durationMs - st.positionMs > s.fadeMs + PRELOAD_LEAD_MS) {
                // Leave room for the next track's player to (re)open before the blend window starts.
                controller.seekTo(st.durationMs - s.fadeMs - PRELOAD_LEAD_MS + 500)
            }
            // Hold the filtered groove until the players' crossfade window opens.
            withTimeoutOrNull(PRELOAD_LEAD_MS + 1_500) {
                while (true) {
                    val now = controller.state.value
                    if (now.currentSong?.id != from || now.durationMs - now.positionMs <= s.fadeMs + 200) break
                    delay(80)
                }
            }
            val fade = s.fadeMs
            DjFx.ramp(DjFx.Fx.MixFilter, 0f, fade * 7 / 10)
            DjFx.ramp(DjFx.Fx.MixDuck, 0f, fade / 2)
            scope.launch(sessionJob) {
                delay(fade * 55 / 100)
                DjFx.ramp(DjFx.Fx.MixKill, 0f, (fade / 5).coerceAtLeast(400))
            }
            animateMix(s, 0.3f, 1f, fade + 400)
            withTimeoutOrNull(4_000) { while (controller.state.value.currentSong?.id == from) delay(100) }
        } finally {
            DjFx.ramp(DjFx.Fx.MixFilter, 0f, 400)
            DjFx.ramp(DjFx.Fx.MixKill, 0f, 400)
            DjFx.ramp(DjFx.Fx.MixDuck, 0f, 400)
            s.mixing = false
            s.mixProgress = 0f
            if (session === s) glideTempo(controller, s)
        }
    }

    private suspend fun animateMix(s: DjSession, from: Float, to: Float, ms: Long) {
        val steps = (ms / MIX_STEP_MS).coerceAtLeast(1)
        for (i in 1..steps) {
            s.mixProgress = from + (to - from) * i / steps
            delay(MIX_STEP_MS)
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

    // ---- Planning ---------------------------------------------------------------------------

    private fun knownIds(s: DjSession): Set<String> = buildSet {
        s.played.forEach { add(it.track.song.id) }
        s.current?.let { add(it.track.song.id) }
        s.upcoming.forEach { add(it.track.song.id) }
    }

    private suspend fun pickSlot(s: DjSession, startMs: Long, extra: Set<String>): DjSlot? {
        val recent = (s.played + listOfNotNull(s.current) + s.upcoming)
        val target = s.targetAt(startMs)
        val track = DjEngine.pick(
            genres = s.genres,
            target = target,
            exclude = knownIds(s) + extra,
            recentArtists = recent.takeLast(ARTIST_SPACING).mapNotNull { it.track.song.artists.firstOrNull()?.name },
            recentGenres = recent.takeLast(3).map { it.track.genre },
        ) ?: return null
        return DjSlot(track, startMs, target)
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

    // ---- Controls ---------------------------------------------------------------------------

    /** Transition to the next track right now (a short filter sweep, then the blend). */
    fun mixNow(controller: PlaybackController) {
        val s = session ?: return
        if (s.mixing || s.upcoming.isEmpty()) return
        scope.launch(sessionJob) { blend(controller, s, MIX_NOW_SWEEP_MS) }
    }

    /** A clean cut to the next track: a quick dip, skip, past a long intro, back up. */
    fun skip(controller: PlaybackController) {
        val s = session ?: return controller.skipToNext()
        scope.launch(sessionJob) {
            val from = controller.state.value.currentSong?.id
            val next = s.upcoming.firstOrNull()
            DjFx.ramp(DjFx.Fx.MixDuck, 1f, 160).join()
            controller.skipToNext()
            withTimeoutOrNull(3_000) { while (controller.state.value.currentSong?.id == from) delay(40) }
            val intro = next?.track?.introSkipMs ?: 0L
            if (next != null && intro > 0) {
                withTimeoutOrNull(2_000) {
                    while (controller.state.value.durationMs <= 0L || controller.state.value.isLoading) delay(40)
                }
                val st = controller.state.value
                if (st.currentSong?.id == next.track.song.id && intro < st.durationMs - 90_000) controller.seekTo(intro)
            }
            DjFx.ramp(DjFx.Fx.MixDuck, 0f, 700)
        }
    }

    /** Hype (up) or Chill (down): shifts the arc and re-plans everything after the next track. */
    fun steer(controller: PlaybackController, up: Boolean) {
        val s = session ?: return
        s.bias = (s.bias + if (up) BIAS_STEP else -BIAS_STEP).coerceIn(-MAX_BIAS, MAX_BIAS)
        scope.launch(sessionJob) {
            val remove = ShellInfinity.removeAt
            if (remove != null && s.upcoming.size > 1) {
                val drop = s.upcoming.drop(1).filter { !it.requested }
                val st = controller.state.value
                val indices = drop.mapNotNull { d ->
                    st.queue.indexOfLast { it.id == d.track.song.id }.takeIf { it > st.currentQueueIndex + 1 }
                }.sortedDescending()
                indices.forEach(remove)
                val removedIds = indices.map { st.queue[it].id }.toSet()
                s.upcoming.removeAll { it.track.song.id in removedIds }
                delay(300)
            }
            planMore(controller, s)
        }
    }

    /** Slots a listener's request right after the current track. */
    fun request(controller: PlaybackController, track: DjTrack) {
        val s = session ?: return
        controller.playNext(listOf(track.song))
        val start = s.current?.let { it.startMs + s.airtime(it) } ?: s.elapsedMs
        s.upcoming.add(0, DjSlot(track, start, s.targetAt(start), requested = true))
    }

    fun setFilter(on: Boolean) {
        val s = session ?: return
        s.filterOn = on
        DjFx.ramp(DjFx.Fx.PadFilter, if (on) 1f else 0f, if (on) 2_400 else 1_200)
    }

    fun setBoost(on: Boolean) {
        val s = session ?: return
        s.boostOn = on
        DjFx.ramp(DjFx.Fx.PadBoost, if (on) 1f else 0f, 500)
    }

    fun setKill(on: Boolean) {
        val s = session ?: return
        s.killOn = on
        DjFx.ramp(DjFx.Fx.PadKill, if (on) 1f else 0f, if (on) 250 else 600)
    }

    /** Speed up (+) or slow down (-) the whole set by 2% per tap, within ±8%. */
    fun nudgeTempo(controller: PlaybackController, up: Boolean) {
        val s = session ?: return
        s.userTempo = (s.userTempo + if (up) 0.02f else -0.02f).coerceIn(0.92f, 1.08f)
        glideTempo(controller, s)
    }

    private const val PREF_RESTORE = "dj_restore"
    private const val TICK_MS = 200L
    private const val MISSES_TO_END = 25
    private const val SWEEP_MS = 4_000L
    private const val MIX_NOW_SWEEP_MS = 1_400L
    private const val PRELOAD_LEAD_MS = 2_500L
    private const val MIX_STEP_MS = 40L
    private const val TEMPO_STEPS = 4
    private const val TEMPO_STEP_MS = 450L
    private const val AHEAD = 3
    private const val ARTIST_SPACING = 4
    private const val BIAS_STEP = 0.15
    private const val MAX_BIAS = 0.45
}

/**
 * Turtle DJ's live effects, played through the app's equalizer stage (Android's
 * EqualizerAudioProcessor, desktop's AudioRenderer) via [TurtleAudioFx]: the listener's own EQ plus
 * three shelves (bass cut, bass boost, high-cut sweep) and a volume ride. The listener's saved
 * equalizer is never written; ending the set drops the override.
 */
internal object DjFx {
    enum class Fx { PadFilter, PadBoost, PadKill, MixFilter, MixKill, MixDuck }

    private val scope = MainScope()
    private val values = FloatArray(Fx.entries.size)
    private val jobs = arrayOfNulls<Job>(Fx.entries.size)
    private var active = false

    fun activate() {
        active = true
        publish()
    }

    fun deactivate() {
        active = false
        jobs.forEach { it?.cancel() }
        values.fill(0f)
        TurtleAudioFx.equalizerOverride = null
    }

    /** Eases [fx] to [to] (0..1) over [ms]; a new ramp on the same effect replaces the old one. */
    fun ramp(fx: Fx, to: Float, ms: Long): Job {
        jobs[fx.ordinal]?.cancel()
        val from = values[fx.ordinal]
        return scope.launch {
            val steps = (ms / STEP_MS).coerceAtLeast(1)
            for (i in 1..steps) {
                val u = i.toFloat() / steps
                values[fx.ordinal] = from + (to - from) * (u * u * (3 - 2 * u))
                publish()
                delay(STEP_MS)
            }
            values[fx.ordinal] = to
            publish()
        }.also { jobs[fx.ordinal] = it }
    }

    private fun publish() {
        if (!active) {
            TurtleAudioFx.equalizerOverride = null
            return
        }
        val filter = max(values[Fx.PadFilter.ordinal] * 0.85f, values[Fx.MixFilter.ordinal])
        val kill = max(values[Fx.PadKill.ordinal], values[Fx.MixKill.ordinal])
        val boost = values[Fx.PadBoost.ordinal] * (1f - kill)
        val duck = values[Fx.MixDuck.ordinal]
        val base = EqualizerProfiles.selectedConfiguration.takeIf { Settings.shared.equalizerEnabled }
        // Same band layout every time while the set runs, so the filters keep their state (no clicks).
        val bands = base?.profile?.bands.orEmpty().take(MAX_EQUALIZER_BANDS - 3) + listOf(
            ParametricEqBand(KILL_HZ, gain(-24f * kill), 0.707, FilterType.LSC),
            ParametricEqBand(BOOST_HZ, gain(6f * boost), 0.9, FilterType.LSC),
            ParametricEqBand(FILTER_TOP_HZ * (FILTER_BOTTOM_HZ / FILTER_TOP_HZ).pow(filter.toDouble()), gain(-26f * filter), 0.707, FilterType.HSC),
        )
        val duckDb = if (duck <= 0f) 0.0 else 20.0 * log10(max(1.0 - duck, 0.003))
        TurtleAudioFx.equalizerOverride = EqualizerConfiguration(
            EqualizerProfile(id = "turtle_dj", name = "Turtle DJ", bands = bands, preamp = (base?.profile?.preamp ?: 0.0) + duckDb, source = "turtle"),
            base?.preampDb ?: 0.0,
        )
    }

    private fun gain(db: Float): Double = if (abs(db) < 0.05f) 0.0 else db.toDouble()

    private const val STEP_MS = 20L
    private const val KILL_HZ = 250.0
    private const val BOOST_HZ = 95.0
    private const val FILTER_TOP_HZ = 9_000.0
    private const val FILTER_BOTTOM_HZ = 500.0
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
