package app.turtleplayer.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.DjEngine
import app.turtleplayer.bridge.DjTrack
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.PlayPauseButton
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellToast
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_dj_beat_estimated
import app.turtleplayer.ui.generated.resources.shell_dj_beat_live
import app.turtleplayer.ui.generated.resources.shell_dj_bpm
import app.turtleplayer.ui.generated.resources.shell_dj_chill
import app.turtleplayer.ui.generated.resources.shell_dj_chilled
import app.turtleplayer.ui.generated.resources.shell_dj_deck_a
import app.turtleplayer.ui.generated.resources.shell_dj_deck_b
import app.turtleplayer.ui.generated.resources.shell_dj_deck_b_mixing
import app.turtleplayer.ui.generated.resources.shell_dj_deck_empty
import app.turtleplayer.ui.generated.resources.shell_dj_digging
import app.turtleplayer.ui.generated.resources.shell_dj_end
import app.turtleplayer.ui.generated.resources.shell_dj_end_confirm
import app.turtleplayer.ui.generated.resources.shell_dj_energy
import app.turtleplayer.ui.generated.resources.shell_dj_exit
import app.turtleplayer.ui.generated.resources.shell_dj_fx
import app.turtleplayer.ui.generated.resources.shell_dj_fx_boost
import app.turtleplayer.ui.generated.resources.shell_dj_fx_filter
import app.turtleplayer.ui.generated.resources.shell_dj_fx_kill
import app.turtleplayer.ui.generated.resources.shell_dj_hype
import app.turtleplayer.ui.generated.resources.shell_dj_hyped
import app.turtleplayer.ui.generated.resources.shell_dj_label
import app.turtleplayer.ui.generated.resources.shell_dj_mix_in
import app.turtleplayer.ui.generated.resources.shell_dj_mix_now
import app.turtleplayer.ui.generated.resources.shell_dj_mixing
import app.turtleplayer.ui.generated.resources.shell_dj_next_badge
import app.turtleplayer.ui.generated.resources.shell_dj_pause
import app.turtleplayer.ui.generated.resources.shell_dj_play
import app.turtleplayer.ui.generated.resources.shell_dj_request
import app.turtleplayer.ui.generated.resources.shell_dj_request_empty
import app.turtleplayer.ui.generated.resources.shell_dj_request_hint
import app.turtleplayer.ui.generated.resources.shell_dj_request_slotted
import app.turtleplayer.ui.generated.resources.shell_dj_request_title
import app.turtleplayer.ui.generated.resources.shell_dj_requested
import app.turtleplayer.ui.generated.resources.shell_dj_set_time
import app.turtleplayer.ui.generated.resources.shell_dj_set_time_of
import app.turtleplayer.ui.generated.resources.shell_dj_side_a
import app.turtleplayer.ui.generated.resources.shell_dj_side_b
import app.turtleplayer.ui.generated.resources.shell_dj_skip
import app.turtleplayer.ui.generated.resources.shell_dj_tempo
import app.turtleplayer.ui.generated.resources.shell_dj_tempo_down
import app.turtleplayer.ui.generated.resources.shell_dj_tempo_up
import app.turtleplayer.ui.generated.resources.shell_dj_up_next
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

internal const val DJ_BANDS = 18

/**
 * The booth's beat: the app's own post-EQ spectrum ([AudioSpectrumMeter], fed by Android's
 * EqualizerAudioProcessor and desktop's AudioRenderer) with a kick detector on the low bands. When
 * no audio reaches the meter (another output path, or a platform without the tap) it falls back to a
 * pulse on the estimated tempo, and [real] says so. Values change every frame: read them in draw.
 */
@Stable
internal class DjPulse {
    val bars = FloatArray(DJ_BANDS)
    var beat = 0f
    var real by mutableStateOf(false)

    /** Ticks every frame while anything moves; read it in a draw block to redraw with the beat. */
    var frame by mutableLongStateOf(0L)
}

@Composable
internal fun rememberDjPulse(playing: Boolean, bpm: () -> Float): DjPulse {
    val pulse = remember { DjPulse() }
    val currentBpm by rememberUpdatedState(bpm)
    DisposableEffect(Unit) {
        AudioSpectrumMeter.addConsumer()
        onDispose { AudioSpectrumMeter.removeConsumer() }
    }
    LaunchedEffect(playing) {
        val target = FloatArray(DJ_BANDS)
        var lastPoll = 0L
        var lastLoud = -10_000L
        var slowBass = 0f
        var env = pulse.beat
        var lastFrame = -1L
        var origin = -1L
        while (true) {
            val now = withFrameNanos { it } / 1_000_000L
            if (origin < 0) origin = now
            val dt = if (lastFrame < 0) 16L else (now - lastFrame).coerceIn(1L, 100L)
            lastFrame = now
            env *= 0.86f.pow(dt / 16f)
            if (now - lastPoll >= METER_POLL_MS) {
                lastPoll = now
                val levels = withContext(Dispatchers.Default) { AudioSpectrumMeter.levels() }
                val n = min(levels.size, DJ_BANDS)
                var loud = false
                for (i in 0 until n) {
                    target[i] = levels[i]
                    if (levels[i] > 0.12f) loud = true
                }
                if (loud) lastLoud = now
                val bass = if (n >= 4) (levels[0] + levels[1] + levels[2] + levels[3]) / 4f else 0f
                val onset = bass - slowBass
                slowBass = slowBass * 0.9f + bass * 0.1f
                if (loud && onset > 0.02f) env = max(env, (onset * 8f).coerceAtMost(1f))
            }
            val real = playing && now - lastLoud < 1_500
            if (pulse.real != real) pulse.real = real
            if (!real) {
                if (playing) {
                    // Estimated: a kick on every beat of the tempo prior and a gently moving spectrum.
                    val phase = ((now - origin) * currentBpm() / 60_000f) % 1f
                    env = exp(-phase * 7f)
                    for (i in 0 until DJ_BANDS) {
                        val tilt = 0.62f - i * 0.022f
                        val wobble = 0.5f + 0.5f * sin(now / (260f + i * 37f) + i)
                        target[i] = (tilt * (0.45f + 0.55f * if (i < 5) env else wobble)).coerceIn(0f, 1f)
                    }
                } else {
                    target.fill(0f)
                }
            }
            var quiet = true
            for (i in 0 until DJ_BANDS) {
                val d = target[i] - pulse.bars[i]
                pulse.bars[i] += d * if (d > 0) 0.45f else 0.12f
                if (pulse.bars[i] > 0.01f) quiet = false
            }
            pulse.beat = env
            pulse.frame = now
            if (!playing && quiet && env < 0.01f) break
        }
    }
    return pulse
}

/** Arc colours: the light the booth throws. */
internal fun app.turtleplayer.bridge.DjArc.vibe(): Color = when (this) {
    app.turtleplayer.bridge.DjArc.Lounge -> Color(0xFF6FE3C8)
    app.turtleplayer.bridge.DjArc.HouseParty -> Color(0xFFD4F55A)
    app.turtleplayer.bridge.DjArc.ClubPeak -> Color(0xFFFF4FA3)
    app.turtleplayer.bridge.DjArc.Sunrise -> Color(0xFFFFB347)
}

/**
 * A record on a platter: the cover art as its label, grooves, a fixed sheen, and inertia when the
 * deck starts and stops. Rotation lives in a layer, so spinning never recomposes. With [ring], a
 * ring of spectrum spokes pulses around it.
 */
@Composable
internal fun DjVinyl(
    artUrl: String?,
    spinning: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
    speed: () -> Float = { 1f },
    ring: DjPulse? = null,
    ringColor: Color = Shell.colors.accent,
) {
    val angle = remember { mutableFloatStateOf(0f) }
    val motor = remember { Animatable(if (spinning) 1f else 0f) }
    val currentSpeed by rememberUpdatedState(speed)
    LaunchedEffect(spinning) {
        launch { motor.animateTo(if (spinning) 1f else 0f, tween(if (spinning) 450 else 1_400, easing = ShellMotion.Emphasized)) }
        var last = -1L
        while (spinning || motor.value > 0.001f) {
            withFrameNanos { n ->
                if (last > 0) angle.floatValue = (angle.floatValue + (n - last) / 1e9f * DEG_PER_SECOND * currentSpeed() * motor.value) % 360f
                last = n
            }
        }
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val disc = if (ring != null) size * 0.82f else size
        if (ring != null) {
            Canvas(Modifier.matchParentSize()) {
                ring.frame
                val center = Offset(this.size.width / 2, this.size.height / 2)
                val inner = disc.toPx() / 2 + 4.dp.toPx()
                val reach = this.size.minDimension / 2 - inner
                val spokes = 72
                val stroke = 2.dp.toPx()
                for (k in 0 until spokes) {
                    // Mirror the spectrum around the ring: bass at the top and bottom.
                    val half = k % (spokes / 2)
                    val band = ((if (half < spokes / 4) half else spokes / 2 - 1 - half) * DJ_BANDS / (spokes / 4)).coerceIn(0, DJ_BANDS - 1)
                    val v = ring.bars[band]
                    val len = reach * (0.12f + 0.88f * v.pow(1.4f)) * (0.85f + 0.15f * ring.beat)
                    val a = (k.toFloat() / spokes) * 2f * PI.toFloat() - PI.toFloat() / 2
                    val dir = Offset(cos(a), sin(a))
                    drawLine(
                        ringColor.copy(alpha = 0.25f + 0.65f * v),
                        center + dir * inner,
                        center + dir * (inner + len),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        Box(
            Modifier.size(disc)
                .graphicsLayer { rotationZ = angle.floatValue }
                .drawBehind {
                    val r = this.size.minDimension / 2
                    drawCircle(Color(0xFF0F0F11), r)
                    val groove = Color.White.copy(alpha = 0.05f)
                    for (k in 0 until 16) drawCircle(groove, r * (0.47f + k * 0.032f), style = Stroke(1f))
                    drawCircle(Color.White.copy(alpha = 0.10f), r - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    // A pressing mark, so the spin reads even on a plain label.
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(r, r * 0.12f), Offset(r, r * 0.42f), 2.dp.toPx(), StrokeCap.Round)
                },
            contentAlignment = Alignment.Center,
        ) {
            ShellArt(artUrl, Modifier.size(disc * 0.42f), shape = CircleShape, fallback = ringColor.copy(alpha = 0.55f))
            Box(Modifier.size(disc * 0.05f).clip(CircleShape).background(Color(0xFF0B0B0C)))
        }
        // Light from above: fixed, so the record turns under it.
        Canvas(Modifier.size(disc)) {
            val w = this.size.minDimension * 0.26f
            val inset = w / 2 + this.size.minDimension * 0.06f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(Color.White.copy(alpha = 0.05f), -150f, 55f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(Color.White.copy(alpha = 0.035f), 30f, 55f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        }
    }
}

/** The booth: two decks, crossfader, visualizer, transport, FX pads, energy graph and up next. */
@Composable
internal fun DjBooth(s: DjSession, onExit: () -> Unit) {
    val controller = LocalPlaybackController.current
    val calm by controller.rememberCalmState()
    val playing = calm.isPlaying
    val vibe = s.arc.vibe()
    val pulse = rememberDjPulse(playing) { (s.current?.track?.bpm ?: 120) * s.speed }
    var requestOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_400)
            toast = null
        }
    }
    val toastText = toast?.let { (kind, arg) ->
        when (kind) {
            TOAST_SLOTTED -> stringResource(Res.string.shell_dj_request_slotted, arg)
            TOAST_HYPE -> stringResource(Res.string.shell_dj_hyped)
            else -> stringResource(Res.string.shell_dj_chilled)
        }
    }
    val ground = Shell.colors.ground
    Box(
        Modifier.fillMaxSize().background(ground).drawBehind {
            pulse.frame
            val glow = 0.16f + 0.14f * pulse.beat
            drawRect(Brush.radialGradient(listOf(vibe.copy(alpha = glow), Color.Transparent), Offset(size.width / 2, 0f), size.maxDimension * 0.75f))
            drawRect(Brush.radialGradient(listOf(vibe.copy(alpha = glow * 0.45f), Color.Transparent), Offset(size.width, size.height), size.maxDimension * 0.6f))
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)) {
            val wide = maxWidth >= 840.dp && maxWidth > maxHeight
            val boothWidth = maxWidth
            Crossfade(s.loading, animationSpec = tween(ShellMotion.EMPHASIZED_MS)) { loading ->
                if (loading) {
                    DjLoading(s, vibe, onExit)
                } else {
                    BoothContent(
                        s = s,
                        controller = controller,
                        playing = playing,
                        fxAvailable = calm.isAudioProcessingAvailable,
                        vibe = vibe,
                        pulse = pulse,
                        wide = wide,
                        width = boothWidth,
                        onExit = onExit,
                        onRequest = { requestOpen = true },
                        onToast = { toast = it },
                    )
                }
            }
        }
        DjRequestSheet(requestOpen, s, controller, onDismiss = { requestOpen = false }) { title -> toast = TOAST_SLOTTED to title }
        ShellToast(toastText, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).widthIn(max = 520.dp))
    }
}

@Composable
private fun BoothContent(
    s: DjSession,
    controller: PlaybackController,
    playing: Boolean,
    fxAvailable: Boolean,
    vibe: Color,
    pulse: DjPulse,
    wide: Boolean,
    width: Dp,
    onExit: () -> Unit,
    onRequest: () -> Unit,
    onToast: (Pair<Int, String>) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    if (wide) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp)) {
            DjTopBar(s, vibe, pulse, controller, onExit)
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1.35f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                        DjDeck(s.current, onAir = true, s, playing, pulse, 280.dp, vibe, controller, Modifier.weight(1.15f))
                        DjDeck(s.upcoming.firstOrNull(), onAir = false, s, playing, pulse, 200.dp, vibe, controller, Modifier.weight(0.85f))
                    }
                    DjCrossfader(s, vibe, Modifier.fillMaxWidth())
                    DjVisualizer(pulse, vibe, Modifier.fillMaxWidth().weight(1f).heightIn(min = 56.dp))
                    DjTransport(s, controller, playing, vibe, haptics, onToast, big = true)
                    DjPads(s, controller, vibe, haptics, fxAvailable, onRequest, columns = 6)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    DjEnergyPanel(s, pulse, vibe, graphHeight = 150.dp)
                    DjUpNext(s, vibe, onRequest)
                }
            }
        }
    } else {
        val vinylA = (width * 0.42f).coerceIn(116.dp, 220.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            DjTopBar(s, vibe, pulse, controller, onExit)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                DjDeck(s.current, onAir = true, s, playing, pulse, vinylA, vibe, controller, Modifier.weight(1.15f))
                DjDeck(s.upcoming.firstOrNull(), onAir = false, s, playing, pulse, vinylA * 0.74f, vibe, controller, Modifier.weight(0.85f))
            }
            DjCrossfader(s, vibe, Modifier.fillMaxWidth())
            DjVisualizer(pulse, vibe, Modifier.fillMaxWidth().height(56.dp))
            DjTransport(s, controller, playing, vibe, haptics, onToast, big = false)
            DjPads(s, controller, vibe, haptics, fxAvailable, onRequest, columns = 3)
            DjEnergyPanel(s, pulse, vibe, graphHeight = 96.dp)
            DjUpNext(s, vibe, onRequest)
        }
    }
}

@Composable
private fun DjLoading(s: DjSession, vibe: Color, onExit: () -> Unit) {
    val c = Shell.colors
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.padding(8.dp)) { GlyphButton(Glyphs.ChevronDown, stringResource(Res.string.shell_dj_exit), onExit, c.ink, iconSize = 26.dp) }
        Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            DjVinyl(null, spinning = true, size = 180.dp, ringColor = vibe)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(Res.string.shell_dj_digging), style = Shell.type.headline, color = c.ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(s.genres.map { djGenreName(it) }.joinToString(" · "), style = Shell.type.body, color = vibe, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DjTopBar(s: DjSession, vibe: Color, pulse: DjPulse, controller: PlaybackController, onExit: () -> Unit) {
    val c = Shell.colors
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyphs.ChevronDown, stringResource(Res.string.shell_dj_exit), onExit, c.ink, iconSize = 26.dp)
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier.size(10.dp).drawBehind {
                pulse.frame
                drawCircle(vibe.copy(alpha = 0.35f + 0.65f * pulse.beat), size.minDimension / 2 * (0.8f + 0.2f * pulse.beat))
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            IndexLabel(stringResource(Res.string.shell_dj_label), color = vibe)
            Text(
                djArcName(s.arc) + " · " + s.genres.take(3).map { djGenreName(it) }.joinToString(", "),
                style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        DjSetClock(s)
        Spacer(Modifier.width(10.dp))
        DjEndButton { ShellDj.end(controller) }
    }
}

/** "SET 12:04 / 1:00:00": recomposes once a second, not on every tick. */
@Composable
private fun DjSetClock(s: DjSession) {
    val seconds by remember(s) { derivedStateOf { s.elapsedMs / 1000 } }
    val now = formatSetTime(seconds)
    val text = s.length.minutes?.let { stringResource(Res.string.shell_dj_set_time_of, now, formatSetTime(it * 60L)) }
        ?: stringResource(Res.string.shell_dj_set_time, now)
    IndexLabel(text, color = Shell.colors.ink2)
}

private fun formatSetTime(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val sec = seconds % 60
    return if (h > 0) "$h:${m.toString().padStart(2, '0')}:${sec.toString().padStart(2, '0')}" else "$m:${sec.toString().padStart(2, '0')}"
}

/** End set with a second tap to confirm, so nobody ends the party by accident. */
@Composable
private fun DjEndButton(onEnd: () -> Unit) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            delay(3_500)
            armed = false
        }
    }
    val bg by animateColorAsState(if (armed) c.danger else c.surface2, ShellMotion.emphasized())
    val fg by animateColorAsState(if (armed) c.ground else c.ink, ShellMotion.emphasized())
    val label = stringResource(if (armed) Res.string.shell_dj_end_confirm else Res.string.shell_dj_end)
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(bg)
            .shellPressable({
                if (!armed) {
                    armed = true
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                } else {
                    armed = false
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onEnd()
                }
            }, label = label, focusShape = CircleShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.Power, fg, 16.dp)
        Spacer(Modifier.width(6.dp))
        Text(label, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

@Composable
private fun DjDeck(
    slot: DjSlot?,
    onAir: Boolean,
    s: DjSession,
    playing: Boolean,
    pulse: DjPulse,
    vinyl: Dp,
    vibe: Color,
    controller: PlaybackController,
    modifier: Modifier,
) {
    val c = Shell.colors
    val mixing = s.mixing
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Crossfade(slot, animationSpec = tween(ShellMotion.EMPHASIZED_MS * 2)) { shown ->
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                DjVinyl(
                    artUrl = shown?.track?.song?.displayThumbnailUrl,
                    spinning = if (onAir) playing else playing && mixing,
                    size = if (onAir) vinyl * 1.18f else vinyl,
                    speed = { s.speed },
                    ring = if (onAir) pulse else null,
                    ringColor = vibe,
                )
                Spacer(Modifier.height(10.dp))
                val label = when {
                    onAir -> stringResource(Res.string.shell_dj_deck_a)
                    mixing -> stringResource(Res.string.shell_dj_deck_b_mixing)
                    else -> stringResource(Res.string.shell_dj_deck_b)
                }
                IndexLabel(label, color = if (onAir || mixing) vibe else c.index)
                Spacer(Modifier.height(4.dp))
                if (shown == null) {
                    Text(stringResource(Res.string.shell_dj_deck_empty), style = Shell.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center)
                } else {
                    Text(
                        shown.track.song.title, style = Shell.type.title.copy(fontSize = if (onAir) 17.sp else 14.sp, lineHeight = if (onAir) 21.sp else 18.sp),
                        color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    Text(
                        shown.track.song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        DjTag("≈ " + stringResource(Res.string.shell_dj_bpm, (shown.track.bpm * s.speed).roundToInt()), c.ink2)
                        if (onAir) {
                            DjMixCountdown(s, controller, playing, shown.track.song.id, vibe)
                        } else if (shown.requested) {
                            DjTag(stringResource(Res.string.shell_dj_requested), vibe, filled = true)
                        }
                    }
                }
            }
        }
    }
}

/** "MIX IN 1:32", or MIXING: the only part of a deck that follows the position. */
@Composable
private fun DjMixCountdown(s: DjSession, controller: PlaybackController, playing: Boolean, songId: String, vibe: Color) {
    if (s.mixing) return DjTag(stringResource(Res.string.shell_dj_mixing), vibe, filled = true)
    val live = rememberLivePosition(controller, playing, songId)
    val left = s.mixPointMs - live.ms
    if (s.mixPointMs == Long.MAX_VALUE || left < 0) return
    DjTag(stringResource(Res.string.shell_dj_mix_in, formatTime(left)), vibe)
}

@Composable
private fun DjTag(text: String, color: Color, filled: Boolean = false) {
    val c = Shell.colors
    Box(
        Modifier.clip(CircleShape).background(if (filled) color else Color.Transparent)
            .border(1.dp, if (filled) Color.Transparent else color.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Text(text.uppercase(), style = Shell.type.index, color = if (filled) c.onAccent else color, maxLines = 1) }
}

/** A crossfader that slides across during each mix and springs back as the decks swap. */
@Composable
private fun DjCrossfader(s: DjSession, vibe: Color, modifier: Modifier) {
    val c = Shell.colors
    val pos = remember { Animatable(0f) }
    LaunchedEffect(s) {
        snapshotFlow { s.mixProgress }.collect { p ->
            if (p < pos.value - 0.2f) pos.animateTo(p, tween(700, easing = ShellMotion.Emphasized)) else pos.snapTo(p)
        }
    }
    Row(modifier.height(44.dp), verticalAlignment = Alignment.CenterVertically) {
        IndexLabel(stringResource(Res.string.shell_dj_side_a), color = vibe)
        Canvas(Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp)) {
            val y = size.height / 2
            val trackH = 6.dp.toPx()
            drawRoundRect(c.surface3, Offset(0f, y - trackH / 2), Size(size.width, trackH), CornerRadius(trackH / 2))
            for (k in 0..8) {
                val x = size.width * k / 8
                val tall = k % 4 == 0
                drawLine(c.hairline, Offset(x, y - (if (tall) 14 else 9).dp.toPx()), Offset(x, y - 6.dp.toPx()), 1.dp.toPx())
            }
            val kx = pos.value * size.width
            drawRoundRect(vibe.copy(alpha = 0.55f), Offset(0f, y - trackH / 2), Size(kx, trackH), CornerRadius(trackH / 2))
            val knobW = 22.dp.toPx()
            val knobH = 36.dp.toPx()
            val left = (kx - knobW / 2).coerceIn(0f, size.width - knobW)
            drawRoundRect(vibe.copy(alpha = 0.22f), Offset(left - 6.dp.toPx(), y - knobH / 2 - 6.dp.toPx()), Size(knobW + 12.dp.toPx(), knobH + 12.dp.toPx()), CornerRadius(10.dp.toPx()))
            drawRoundRect(c.ink, Offset(left, y - knobH / 2), Size(knobW, knobH), CornerRadius(6.dp.toPx()))
            drawLine(c.ground, Offset(left + knobW / 2, y - knobH / 2 + 7.dp.toPx()), Offset(left + knobW / 2, y + knobH / 2 - 7.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
        }
        IndexLabel(stringResource(Res.string.shell_dj_side_b), color = c.ink2)
    }
}

/** Mirrored spectrum bars (bass in the middle), with whether the beat is live audio or estimated. */
@Composable
private fun DjVisualizer(pulse: DjPulse, vibe: Color, modifier: Modifier) {
    val accent = Shell.colors.accent
    Box(modifier) {
        Canvas(Modifier.matchParentSize()) {
            pulse.frame
            val n = DJ_BANDS * 2
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (n - 1)) / n
            for (i in 0 until n) {
                val band = if (i < DJ_BANDS) DJ_BANDS - 1 - i else i - DJ_BANDS
                val v = pulse.bars[band].pow(1.3f)
                val h = max(3.dp.toPx(), v * size.height)
                drawRoundRect(
                    lerp(vibe, accent, v).copy(alpha = 0.45f + 0.55f * v),
                    Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 2),
                )
            }
        }
        IndexLabel(
            stringResource(if (pulse.real) Res.string.shell_dj_beat_live else Res.string.shell_dj_beat_estimated),
            Modifier.align(Alignment.TopEnd), color = Shell.colors.index,
        )
    }
}

@Composable
private fun DjTransport(
    s: DjSession,
    controller: PlaybackController,
    playing: Boolean,
    vibe: Color,
    haptics: HapticFeedback,
    onToast: (Pair<Int, String>) -> Unit,
    big: Boolean,
) {
    val c = Shell.colors
    val round = if (big) 60.dp else 52.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (big) 14.dp else 8.dp), verticalAlignment = Alignment.Top) {
        DjRoundButton(Glyphs.Chill, stringResource(Res.string.shell_dj_chill), round) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            ShellDj.steer(controller, up = false)
            onToast(TOAST_CHILL to "")
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            PlayPauseButton(
                playing = playing,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    controller.togglePlayPause()
                },
                size = round, background = c.ink, contentColor = c.ground,
                playLabel = stringResource(Res.string.shell_dj_play), pauseLabel = stringResource(Res.string.shell_dj_pause),
            )
        }
        DjMixNowButton(s, vibe, round, Modifier.weight(1f)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            ShellDj.mixNow(controller)
        }
        DjRoundButton(Glyphs.Next, stringResource(Res.string.shell_dj_skip), round) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            ShellDj.skip(controller)
        }
        DjRoundButton(Glyphs.Hype, stringResource(Res.string.shell_dj_hype), round, tint = vibe) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            ShellDj.steer(controller, up = true)
            onToast(TOAST_HYPE to "")
        }
    }
}

@Composable
private fun DjRoundButton(glyph: androidx.compose.ui.graphics.vector.ImageVector, label: String, size: Dp, tint: Color = Shell.colors.ink, onClick: () -> Unit) {
    val c = Shell.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(size).clip(CircleShape).background(c.surface2).border(1.dp, c.hairline, CircleShape)
                .shellPressable(onClick, label = label, focusShape = CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(glyph, tint, size * 0.42f) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = Shell.type.bodySmall.copy(fontSize = 11.sp), color = c.ink2, maxLines = 1)
    }
}

/** The big lime button: fills with the mix's progress while a transition runs. */
@Composable
private fun DjMixNowButton(s: DjSession, vibe: Color, height: Dp, modifier: Modifier, onClick: () -> Unit) {
    val c = Shell.colors
    val mixing = s.mixing
    val label = stringResource(if (mixing) Res.string.shell_dj_mixing else Res.string.shell_dj_mix_now)
    Box(
        modifier.height(height).shadow(if (mixing) 0.dp else 14.dp, CircleShape, ambientColor = vibe, spotColor = vibe).clip(CircleShape)
            .drawBehind {
                if (mixing) {
                    drawRect(c.surface3)
                    drawRect(vibe, size = Size(size.width * s.mixProgress, size.height))
                } else {
                    drawRect(vibe)
                }
            }
            .shellPressable(onClick, label = label, focusShape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(Glyphs.Crossfader, c.onAccent, 22.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, style = Shell.type.body.copy(fontWeight = FontWeight.Bold, fontSize = 17.sp), color = c.onAccent, maxLines = 1)
        }
    }
}

@Composable
private fun DjPads(
    s: DjSession,
    controller: PlaybackController,
    vibe: Color,
    haptics: HapticFeedback,
    fxAvailable: Boolean,
    onRequest: () -> Unit,
    columns: Int,
) {
    val tempoPct = ((s.userTempo - 1f) * 100f).roundToInt()
    val tempo = stringResource(Res.string.shell_dj_tempo, (if (tempoPct > 0) "+" else "") + "$tempoPct%")
    fun toggle(on: Boolean) = haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
    val pads = listOf<@Composable (Modifier) -> Unit>(
        { m -> FxPad(Glyphs.FilterSweep, stringResource(Res.string.shell_dj_fx_filter), s.filterOn, vibe, m, enabled = fxAvailable) { toggle(!s.filterOn); ShellDj.setFilter(!s.filterOn) } },
        { m -> FxPad(Glyphs.BassWave, stringResource(Res.string.shell_dj_fx_boost), s.boostOn, vibe, m, enabled = fxAvailable) { toggle(!s.boostOn); ShellDj.setBoost(!s.boostOn) } },
        { m -> FxPad(Glyphs.BassCut, stringResource(Res.string.shell_dj_fx_kill), s.killOn, vibe, m, enabled = fxAvailable) { toggle(!s.killOn); ShellDj.setKill(!s.killOn) } },
        { m ->
            FxPad(Glyphs.TempoDown, stringResource(Res.string.shell_dj_tempo_down), s.userTempo < 1f, vibe, m, value = tempo.takeIf { s.userTempo < 1f }) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                ShellDj.nudgeTempo(controller, up = false)
            }
        },
        { m ->
            FxPad(Glyphs.TempoUp, stringResource(Res.string.shell_dj_tempo_up), s.userTempo > 1f, vibe, m, value = tempo.takeIf { s.userTempo > 1f }) {
                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                ShellDj.nudgeTempo(controller, up = true)
            }
        },
        { m -> FxPad(Glyphs.Search, stringResource(Res.string.shell_dj_request), false, vibe, m) { onRequest() } },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IndexLabel(stringResource(Res.string.shell_dj_fx))
        pads.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { pad -> pad(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** A performance pad: lit in the vibe colour while its effect is on. */
@Composable
private fun FxPad(
    glyph: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    vibe: Color,
    modifier: Modifier,
    value: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Shell.colors
    val bg by animateColorAsState(if (on) vibe else c.surface2, ShellMotion.emphasized())
    val fg by animateColorAsState(if (on) c.onAccent else c.ink, ShellMotion.emphasized())
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.height(72.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .shadow(if (on) 16.dp else 0.dp, shape, ambientColor = vibe, spotColor = vibe)
            .clip(shape).background(bg).border(1.dp, if (on) Color.Transparent else c.hairline, shape)
            .shellPressable({ if (enabled) onClick() }, label = label, focusShape = shape)
            .padding(10.dp),
    ) {
        Glyph(glyph, fg, 22.dp, Modifier.align(Alignment.TopStart))
        if (value != null) {
            Text(value, style = Shell.type.index, color = fg, modifier = Modifier.align(Alignment.TopEnd), maxLines = 1)
        } else if (on) {
            Box(Modifier.align(Alignment.TopEnd).size(8.dp).clip(CircleShape).background(c.onAccent))
        }
        Text(
            label, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

/** The set's energy arc with each track as a dot and a playhead at set time. */
@Composable
private fun DjEnergyPanel(s: DjSession, pulse: DjPulse, vibe: Color, graphHeight: Dp) {
    val c = Shell.colors
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IndexLabel(stringResource(Res.string.shell_dj_energy) + " · " + djArcName(s.arc), Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(graphHeight).clip(RoundedCornerShape(10.dp)).background(c.surface1)) {
            pulse.frame
            val span = s.spanMs
            val elapsed = s.elapsedMs
            val base = if (s.length.minutes == null) (elapsed / span) * span else 0L
            val pad = 6.dp.toPx()
            fun x(ms: Long) = ((ms - base).toFloat() / span).coerceIn(0f, 1f) * size.width
            fun y(e: Double) = size.height - pad - e.toFloat().coerceIn(0f, 1f) * (size.height - pad * 2)
            for (k in 1..3) drawLine(c.hairlineSoft, Offset(0f, size.height * k / 4), Offset(size.width, size.height * k / 4), 1f)
            val path = Path()
            val n = 64
            for (i in 0..n) {
                val px = size.width * i / n
                val py = y(s.arcAt(i.toDouble() / n))
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            val fill = Path().apply {
                addPath(path)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(vibe.copy(alpha = 0.3f), Color.Transparent)))
            drawPath(path, vibe, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            val head = x(elapsed)
            drawRect(c.ground.copy(alpha = 0.35f), size = Size(head, size.height))
            for (p in s.played) if (p.startMs >= base) drawCircle(c.ink2, 3.dp.toPx(), Offset(x(p.startMs), y(p.track.energy)))
            for (u in s.upcoming) drawCircle(vibe, 3.5.dp.toPx(), Offset(x(u.startMs), y(u.track.energy)), style = Stroke(1.5.dp.toPx()))
            s.current?.let { cur ->
                val at = Offset(x(cur.startMs), y(cur.track.energy))
                drawCircle(c.accent.copy(alpha = 0.3f), (7f + 5f * pulse.beat).dp.toPx(), at)
                drawCircle(c.accent, 4.5.dp.toPx(), at)
            }
            drawLine(c.ink, Offset(head, 0f), Offset(head, size.height), 1.5.dp.toPx())
        }
    }
}

@Composable
private fun DjUpNext(s: DjSession, vibe: Color, onRequest: () -> Unit) {
    val c = Shell.colors
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IndexLabel(stringResource(Res.string.shell_dj_up_next), Modifier.weight(1f))
            Row(
                Modifier.height(34.dp).clip(CircleShape).background(c.surface2)
                    .shellPressable(onRequest, label = stringResource(Res.string.shell_dj_request), focusShape = CircleShape)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(Glyphs.Search, c.ink, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.shell_dj_request), style = Shell.type.label, color = c.ink)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (s.upcoming.isEmpty()) {
            Text(stringResource(Res.string.shell_dj_deck_empty), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(vertical = 10.dp))
        }
        s.upcoming.forEachIndexed { i, slot ->
            key(slot.track.song.id) { DjUpNextRow(slot, first = i == 0, vibe = vibe, speed = s.speed) }
        }
    }
}

@Composable
private fun DjUpNextRow(slot: DjSlot, first: Boolean, vibe: Color, speed: Float) {
    val c = Shell.colors
    Row(Modifier.fillMaxWidth().height(62.dp), verticalAlignment = Alignment.CenterVertically) {
        ShellArt(slot.track.song.displayThumbnailUrl, Modifier.size(46.dp), shape = RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (first || slot.requested) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (first) IndexLabel(stringResource(Res.string.shell_dj_next_badge), color = vibe)
                    if (slot.requested) IndexLabel(stringResource(Res.string.shell_dj_requested), color = c.accent)
                }
            }
            Text(slot.track.song.title, style = Shell.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(slot.track.song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            IndexLabel("≈ " + stringResource(Res.string.shell_dj_bpm, (slot.track.bpm * speed).roundToInt()), color = c.ink2)
            Spacer(Modifier.height(5.dp))
            DjEnergyBars(slot.track.energy, vibe)
        }
    }
}

/** Five rising bars: a track's estimated energy at a glance. */
@Composable
private fun DjEnergyBars(energy: Double, vibe: Color) {
    val off = Shell.colors.surface3
    Canvas(Modifier.size(30.dp, 12.dp)) {
        val lit = (energy * 5).roundToInt().coerceIn(1, 5)
        val w = size.width / 5 - 2.dp.toPx()
        for (i in 0 until 5) {
            val h = size.height * (0.4f + 0.15f * i)
            drawRoundRect(if (i < lit) vibe else off, Offset(i * (w + 2.dp.toPx()), size.height - h), Size(w, h), CornerRadius(1.dp.toPx()))
        }
    }
}

/** Request: search YouTube Music and slot a song right after the current track. */
@Composable
private fun BoxScope.DjRequestSheet(visible: Boolean, s: DjSession, controller: PlaybackController, onDismiss: () -> Unit, onSlotted: (String) -> Unit) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    ShellBottomSheet(visible = visible, onDismiss = onDismiss) {
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<DjTrack>>(emptyList()) }
        var searching by remember { mutableStateOf(false) }
        val focus = remember { FocusRequester() }
        LaunchedEffect(query) {
            if (query.isBlank()) {
                results = emptyList()
                return@LaunchedEffect
            }
            delay(350)
            searching = true
            results = DjEngine.search(query, s.primaryGenre)
            searching = false
        }
        LaunchedEffect(Unit) {
            delay(300)
            runCatching { focus.requestFocus() }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).padding(bottom = 8.dp)) {
            Text(stringResource(Res.string.shell_dj_request_title), style = Shell.type.headline.copy(fontSize = 20.sp), color = c.ink)
            Spacer(Modifier.height(12.dp))
            val textStyle = Shell.type.body.copy(color = c.ink)
            Row(
                Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(10.dp)).background(c.surface2).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(Glyphs.Search, c.ink2, 18.dp)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) Text(stringResource(Res.string.shell_dj_request_hint), style = textStyle, color = c.ink2, maxLines = 1)
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = textStyle,
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            if (!searching && query.isNotBlank() && results.isEmpty()) {
                Text(stringResource(Res.string.shell_dj_request_empty), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(12.dp))
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                items(results, key = { it.song.id }) { t ->
                    Row(
                        Modifier.fillMaxWidth().height(60.dp).clip(RoundedCornerShape(8.dp))
                            .shellPressable({
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                ShellDj.request(controller, t)
                                onSlotted(t.song.title)
                                onDismiss()
                            }, label = t.song.title)
                            .padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ShellArt(t.song.displayThumbnailUrl, Modifier.size(44.dp), shape = RoundedCornerShape(4.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.song.title, style = Shell.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Glyph(Glyphs.PlaylistAdd, c.ink2, 20.dp)
                    }
                }
            }
        }
    }
}

private const val METER_POLL_MS = 30L
private const val DEG_PER_SECOND = 200f
private const val TOAST_SLOTTED = 0
private const val TOAST_HYPE = 1
private const val TOAST_CHILL = 2

