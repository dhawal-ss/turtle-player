package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.DjArc
import app.turtleplayer.bridge.DjEngine
import app.turtleplayer.bridge.DjRack
import app.turtleplayer.bridge.DjTrack
import app.turtleplayer.mix.TransitionStyle
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.PlayPauseButton
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellToast
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.*
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import com.metrolist.shared.equalizer.AudioSpectrumMeter
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.state.SongLikeState
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
 * The booth's beat: the app's own post-EQ spectrum ([AudioSpectrumMeter]) with a kick detector on the
 * low bands. When the rack has measured the tempo, the pulse locks to its beat phase instead. With no
 * audio reaching the meter it falls back to a pulse on the estimated tempo, and [real] says so.
 * Values change every frame: read them in draw.
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
        var lastPhase = 0f
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
                val deck = DjRack.rack.onAir
                if (deck != null && deck.active && deck.bpmMeasured) {
                    // Locked to the measured beat grid: a kick on each beat the rack tracks.
                    val ph = deck.beatPhase
                    if (ph < lastPhase - 0.5f) env = 1f
                    lastPhase = ph
                } else {
                    val bass = if (n >= 4) (levels[0] + levels[1] + levels[2] + levels[3]) / 4f else 0f
                    val onset = bass - slowBass
                    slowBass = slowBass * 0.9f + bass * 0.1f
                    if (loud && onset > 0.02f) env = max(env, (onset * 8f).coerceAtMost(1f))
                }
            }
            val real = playing && now - lastLoud < 1_500
            if (pulse.real != real) pulse.real = real
            if (!real) {
                if (playing) {
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
internal fun DjArc.vibe(): Color = when (this) {
    DjArc.Lounge -> Color(0xFF6FE3C8)
    DjArc.HouseParty -> Color(0xFFD4F55A)
    DjArc.ClubPeak -> Color(0xFFFF4FA3)
    DjArc.Waves -> Color(0xFFFF7A59)
    DjArc.Workout -> Color(0xFFFF5A5F)
    DjArc.Focus -> Color(0xFF7FA7FF)
    DjArc.Sunrise -> Color(0xFFFFB347)
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
                    val half = k % (spokes / 2)
                    val band = ((if (half < spokes / 4) half else spokes / 2 - 1 - half) * DJ_BANDS / (spokes / 4)).coerceIn(0, DJ_BANDS - 1)
                    val v = ring.bars[band]
                    val len = reach * (0.12f + 0.88f * v.pow(1.4f)) * (0.85f + 0.15f * ring.beat)
                    val a = (k.toFloat() / spokes) * 2f * PI.toFloat() - PI.toFloat() / 2
                    val dir = Offset(cos(a), sin(a))
                    drawLine(ringColor.copy(alpha = 0.25f + 0.65f * v), center + dir * inner, center + dir * (inner + len), strokeWidth = stroke, cap = StrokeCap.Round)
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
                    drawLine(Color.White.copy(alpha = 0.12f), Offset(r, r * 0.12f), Offset(r, r * 0.42f), 2.dp.toPx(), StrokeCap.Round)
                },
            contentAlignment = Alignment.Center,
        ) {
            ShellArt(artUrl, Modifier.size(disc * 0.42f), shape = CircleShape, fallback = ringColor.copy(alpha = 0.55f))
            Box(Modifier.size(disc * 0.05f).clip(CircleShape).background(Color(0xFF0B0B0C)))
        }
        Canvas(Modifier.size(disc)) {
            val w = this.size.minDimension * 0.26f
            val inset = w / 2 + this.size.minDimension * 0.06f
            val arcSize = Size(this.size.width - inset * 2, this.size.height - inset * 2)
            drawArc(Color.White.copy(alpha = 0.05f), -150f, 55f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            drawArc(Color.White.copy(alpha = 0.035f), 30f, 55f, false, Offset(inset, inset), arcSize, style = Stroke(w))
        }
    }
}

/** The booth: Auto (the DJ mixes, you steer the vibe) or You DJ (you pick the track, the move and the moment). */
@Composable
internal fun DjBooth(s: DjSession, onExit: () -> Unit) {
    val controller = LocalPlaybackController.current
    val calm by controller.rememberCalmState()
    val playing = calm.isPlaying
    val vibe = s.arc.vibe()
    val pulse = rememberDjPulse(playing) { s.liveBpm.takeIf { it > 0f }?.times(s.speed) ?: ((s.current?.track?.bpm ?: 120) * s.speed) }
    var requestOpen by remember { mutableStateOf(false) }
    var toast by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(toast) {
        if (toast != null) {
            delay(2_400)
            toast = null
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
                    BoothContent(s, controller, playing, vibe, pulse, wide, boothWidth, onExit, onRequest = { requestOpen = true })
                }
            }
        }
        val slotted = stringResource(Res.string.shell_dj_request_slotted, "%s")
        DjRequestSheet(requestOpen, s, controller, onDismiss = { requestOpen = false }) { title -> toast = slotted.replace("%s", title) }
        ShellToast(toast, Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.navigationBars).widthIn(max = 520.dp))
    }
}

@Composable
private fun BoothContent(
    s: DjSession,
    controller: PlaybackController,
    playing: Boolean,
    vibe: Color,
    pulse: DjPulse,
    wide: Boolean,
    width: Dp,
    onExit: () -> Unit,
    onRequest: () -> Unit,
) {
    val you = s.mode == DjMode.You
    var fxOpen by remember { mutableStateOf(false) }
    if (wide) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 12.dp)) {
            DjTopBar(s, vibe, pulse, controller, onExit)
            Row(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.weight(1.3f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    DjModeSwitch(s, vibe)
                    DjDecks(s, controller, playing, pulse, vibe, 260.dp)
                    DjNarration(s, vibe, pulse)
                    DjCrossfader(s, controller, vibe, Modifier.fillMaxWidth())
                    DjTransport(s, controller, playing, vibe, big = true)
                    DjFxSection(s, controller, vibe, open = you || fxOpen, onToggle = { fxOpen = !fxOpen }, showToggle = !you, columns = 4)
                }
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    if (you) DjYouPanel(s, controller, vibe) else DjAutoPanel(s, controller, vibe)
                    DjEnergyPanel(s, pulse, vibe, graphHeight = 140.dp)
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
            DjModeSwitch(s, vibe)
            DjDecks(s, controller, playing, pulse, vibe, vinylA)
            DjNarration(s, vibe, pulse)
            DjCrossfader(s, controller, vibe, Modifier.fillMaxWidth())
            DjTransport(s, controller, playing, vibe, big = false)
            if (you) DjYouPanel(s, controller, vibe) else DjAutoPanel(s, controller, vibe)
            DjFxSection(s, controller, vibe, open = you || fxOpen, onToggle = { fxOpen = !fxOpen }, showToggle = !you, columns = 4)
            DjEnergyPanel(s, pulse, vibe, graphHeight = 96.dp)
            DjUpNext(s, vibe, onRequest)
        }
    }
}

@Composable
private fun DjModeSwitch(s: DjSession, vibe: Color) {
    DjSegmented(
        listOf(stringResource(Res.string.shell_dj_auto_tab) to Glyphs.Turntable, stringResource(Res.string.shell_dj_you_tab) to Glyphs.Crossfader),
        if (s.mode == DjMode.Auto) 0 else 1, vibe, Modifier.fillMaxWidth(),
    ) { ShellDj.setMode(if (it == 0) DjMode.Auto else DjMode.You) }
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
            // The label and the set clock share a line, so the set's name keeps the full width below.
            Row(verticalAlignment = Alignment.CenterVertically) {
                IndexLabel(stringResource(Res.string.shell_dj_label), color = vibe)
                Spacer(Modifier.width(8.dp))
                DjSetClock(s)
            }
            Text(
                djArcName(s.arc) + " · " + s.genres.take(3).map { djGenreName(it) }.joinToString(", "),
                style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        DjEndButton { ShellDj.end(controller) }
    }
}

/** "SET 12:04 / 1:00:00": recomposes once a second, not on every tick. */
@Composable
private fun DjSetClock(s: DjSession) {
    val seconds by remember(s) { derivedStateOf { s.elapsedMs / 1000 } }
    val now = formatSetTime(seconds)
    // Elapsed only: it sits beside the label on phones, and the energy graph shows what is left.
    val text = stringResource(Res.string.shell_dj_set_time, now)
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

// ---- Decks ----------------------------------------------------------------------------------------

@Composable
private fun DjDecks(s: DjSession, controller: PlaybackController, playing: Boolean, pulse: DjPulse, vibe: Color, vinyl: Dp) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        DjDeck(s.current, onAir = true, s, playing, pulse, vinyl, vibe, controller, Modifier.weight(1.15f))
        DjDeck(s.upcoming.firstOrNull(), onAir = false, s, playing, pulse, vinyl * 0.74f, vibe, controller, Modifier.weight(0.85f))
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            shown.track.song.title, style = Shell.type.title.copy(fontSize = if (onAir) 17.sp else 14.sp, lineHeight = if (onAir) 21.sp else 18.sp),
                            color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.weight(1f, fill = false),
                        )
                        if (onAir) DjLikeButton(shown, vibe)
                    }
                    Text(
                        shown.track.song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink2,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(8.dp))
                    if (onAir) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            DjBpmTag(s, vibe)
                            DjMixCountdown(s, controller, playing, shown.track.song.id, vibe)
                        }
                        Spacer(Modifier.height(8.dp))
                        DjBeatDots(pulse, vibe, s.liveBpmMeasured)
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            val bpm = (shown.track.bpm * s.speed).roundToInt()
                            DjTag(stringResource(if (shown.track.bpmMeasured) Res.string.shell_dj_bpm_live else Res.string.shell_dj_bpm_est, bpm), c.ink2)
                            if (shown.requested) DjTag(stringResource(Res.string.shell_dj_requested), vibe, filled = true)
                        }
                    }
                }
            }
        }
    }
}

/** The on-air tempo: measured from the audio (solid) or the estimate (≈) until the rack is sure. */
@Composable
private fun DjBpmTag(s: DjSession, vibe: Color) {
    val bpm = ((if (s.liveBpm > 0f) s.liveBpm else 120f) * s.speed).roundToInt()
    if (s.liveBpmMeasured) {
        DjTag(stringResource(Res.string.shell_dj_bpm_live, bpm), vibe, filled = true)
    } else {
        DjTag(stringResource(Res.string.shell_dj_bpm_est, bpm), Shell.colors.ink2)
    }
}

/** Four dots, one per beat of the bar, lit on the beat the rack is tracking. */
@Composable
private fun DjBeatDots(pulse: DjPulse, vibe: Color, measured: Boolean) {
    val off = Shell.colors.surface3
    Canvas(Modifier.size(64.dp, 10.dp)) {
        pulse.frame
        val deck = DjRack.rack.onAir
        val beat = if (measured && deck != null && deck.active) deck.beatInBar else -1
        val r = 4.dp.toPx()
        val gap = (size.width - r * 2 * 4) / 3
        for (i in 0 until 4) {
            val lit = i == beat
            val x = r + i * (r * 2 + gap)
            val color = when {
                lit && i == 0 -> vibe
                lit -> vibe.copy(alpha = 0.7f)
                else -> off
            }
            drawCircle(color, if (lit) r * (1f + 0.25f * pulse.beat) else r * 0.8f, Offset(x, size.height / 2))
        }
    }
}

@Composable
private fun DjLikeButton(slot: DjSlot, vibe: Color) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val liked = slot.track.song.id in SongLikeState.likedSongIds
    val label = stringResource(if (liked) Res.string.shell_dj_unlike else Res.string.shell_dj_like)
    Box(
        Modifier.padding(start = 4.dp).size(32.dp).clip(CircleShape)
            .shellPressable({
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                ShellDj.like()
            }, label = label, focusShape = CircleShape),
        contentAlignment = Alignment.Center,
    ) { Glyph(if (liked) Glyphs.Heart else Glyphs.HeartLine, if (liked) vibe else c.ink2, 18.dp) }
}

/** "BLEND IN 1:32", or MIXING: the only part of a deck that follows the position. */
@Composable
private fun DjMixCountdown(s: DjSession, controller: PlaybackController, playing: Boolean, songId: String, vibe: Color) {
    if (s.mixing) return DjTag(stringResource(Res.string.shell_dj_mixing), vibe, filled = true)
    if (s.mode == DjMode.You) return
    val live = rememberLivePosition(controller, playing, songId)
    val left = s.mixPointMs - live.ms
    if (s.mixPointMs == Long.MAX_VALUE || left < 0) return
    // Short on purpose: the move itself is named on the Mix button and the Next move card.
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

/** The DJ's voice: what's happening and why, with the move's explanation while it mixes. */
@Composable
private fun DjNarration(s: DjSession, vibe: Color, pulse: DjPulse) {
    val c = Shell.colors
    val now = s.now
    val text = djNowText(now)
    val sub = (now as? DjNow.Mixing)?.let { stringResource(it.style.bodyRes()) }
    val glyph: ImageVector = when (now) {
        is DjNow.Mixing -> now.style.glyph()
        is DjNow.Liked -> Glyphs.Heart
        is DjNow.Skipped -> Glyphs.Next
        is DjNow.Remixed -> Glyphs.Sliders
        is DjNow.Requested -> Glyphs.Search
        DjNow.SafetyNet -> Glyphs.Crossfader
        else -> Glyphs.Sparkle
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface1)
            .drawBehind {
                if (now is DjNow.Mixing) drawRect(vibe.copy(alpha = 0.14f), size = Size(size.width * s.mixProgress, size.height))
            }
            .border(1.dp, if (now is DjNow.Mixing) vibe.copy(alpha = 0.6f) else c.hairline, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(vibe.copy(alpha = 0.18f)).drawBehind {
                pulse.frame
                drawCircle(vibe.copy(alpha = 0.25f * pulse.beat), size.minDimension / 2)
            },
            contentAlignment = Alignment.Center,
        ) { Glyph(glyph, vibe, 18.dp) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Crossfade(text, animationSpec = tween(ShellMotion.EMPHASIZED_MS)) { t ->
                Text(t, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (sub != null) Text(sub, style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The crossfader. In Auto it shows each mix as it happens. In You DJ it is yours: drag it toward B
 * and the next track comes in, bass swapping at the middle.
 */
@Composable
private fun DjCrossfader(s: DjSession, controller: PlaybackController, vibe: Color, modifier: Modifier) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val pos = remember { Animatable(0f) }
    var dragging by remember { mutableStateOf(false) }
    val you = s.mode == DjMode.You
    LaunchedEffect(s) {
        snapshotFlow { s.mixProgress }.collect { p ->
            if (dragging) return@collect
            if (p < pos.value - 0.2f) pos.animateTo(p, tween(700, easing = ShellMotion.Emphasized)) else pos.snapTo(p)
        }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            IndexLabel(stringResource(Res.string.shell_dj_side_a), color = vibe)
            Box(
                Modifier.weight(1f).fillMaxHeight().padding(horizontal = 14.dp)
                    .semantics { contentDescription = "Crossfader" }
                    .pointerInput(you, s.fxAvailable) {
                        if (!you || !s.fxAvailable) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            dragging = true
                            haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                            var crossed = pos.value > 0.5f
                            fun at(x: Float) {
                                val v = (x / size.width).coerceIn(0f, 1f)
                                scope.launch { pos.snapTo(v) }
                                if ((v > 0.5f) != crossed) {
                                    crossed = v > 0.5f
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }
                                ShellDj.fader(controller, v)
                            }
                            at(down.position.x)
                            down.consume()
                            while (true) {
                                val e = awaitPointerEvent()
                                val p = e.changes.firstOrNull() ?: break
                                if (!p.pressed) break
                                at(p.position.x)
                                p.consume()
                            }
                            dragging = false
                        }
                    },
            ) {
                Canvas(Modifier.matchParentSize()) {
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
                    val knobW = 24.dp.toPx()
                    val knobH = 38.dp.toPx()
                    val left = (kx - knobW / 2).coerceIn(0f, size.width - knobW)
                    drawRoundRect(vibe.copy(alpha = if (you) 0.32f else 0.22f), Offset(left - 6.dp.toPx(), y - knobH / 2 - 6.dp.toPx()), Size(knobW + 12.dp.toPx(), knobH + 12.dp.toPx()), CornerRadius(10.dp.toPx()))
                    drawRoundRect(if (you) vibe else c.ink, Offset(left, y - knobH / 2), Size(knobW, knobH), CornerRadius(6.dp.toPx()))
                    drawLine(c.ground, Offset(left + knobW / 2, y - knobH / 2 + 7.dp.toPx()), Offset(left + knobW / 2, y + knobH / 2 - 7.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
                }
            }
            IndexLabel(stringResource(Res.string.shell_dj_side_b), color = c.ink2)
        }
        if (you && !s.mixing) {
            Text(stringResource(Res.string.shell_dj_fader_hint), style = Shell.type.bodySmall.copy(fontSize = 11.sp), color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun DjTransport(s: DjSession, controller: PlaybackController, playing: Boolean, vibe: Color, big: Boolean) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val round = if (big) 60.dp else 52.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (big) 14.dp else 8.dp), verticalAlignment = Alignment.Top) {
        DjRoundButton(Glyphs.Chill, stringResource(Res.string.shell_dj_chill), round) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            ShellDj.steer(controller, up = false)
        }
        PlayPauseButton(
            playing = playing,
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                controller.togglePlayPause()
            },
            size = round, background = c.ink, contentColor = c.ground,
            playLabel = stringResource(Res.string.shell_dj_play), pauseLabel = stringResource(Res.string.shell_dj_pause),
        )
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
        }
    }
}

@Composable
private fun DjRoundButton(glyph: ImageVector, label: String, size: Dp, tint: Color = Shell.colors.ink, onClick: () -> Unit) {
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

/** The big button: fills with the mix's progress while a transition runs; names the move it will do. */
@Composable
private fun DjMixNowButton(s: DjSession, vibe: Color, height: Dp, modifier: Modifier, onClick: () -> Unit) {
    val c = Shell.colors
    val mixing = s.mixing
    val style = when {
        mixing -> s.mixStyle
        s.mode == DjMode.You -> s.youStyle ?: s.upcoming.firstOrNull()?.plan?.style
        else -> s.upcoming.firstOrNull()?.plan?.style
    }
    val label = if (mixing) stringResource(Res.string.shell_dj_mixing) else stringResource(Res.string.shell_dj_mix_now)
    Box(
        modifier.height(height).shadow(if (mixing) 0.dp else 14.dp, CircleShape, ambientColor = vibe, spotColor = vibe).clip(CircleShape)
            .drawBehind {
                if (mixing) {
                    // A dim track with a soft fill, so the label stays readable at any progress.
                    drawRect(c.surface3)
                    drawRect(vibe.copy(alpha = 0.4f), size = Size(size.width * s.mixProgress, size.height))
                } else {
                    drawRect(vibe)
                }
            }
            .shellPressable(onClick, label = label, focusShape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        val fg = if (mixing) c.ink else c.onAccent
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 10.dp)) {
            Glyph(style?.glyph() ?: Glyphs.Crossfader, if (mixing) vibe else fg, 22.dp)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = Shell.type.body.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp), color = fg, maxLines = 1)
                if (style != null) Text(stringResource(style.nameRes()), style = Shell.type.bodySmall.copy(fontSize = 11.sp), color = fg.copy(alpha = 0.75f), maxLines = 1)
            }
        }
    }
}

// ---- Auto: steer the vibe --------------------------------------------------------------------------

@Composable
private fun DjAutoPanel(s: DjSession, controller: PlaybackController, vibe: Color) {
    val c = Shell.colors
    // Local copy while dragging, so the controls move at finger speed; the DJ re-plans once you settle.
    var draft by remember(s) { mutableStateOf(s.vibe) }
    LaunchedEffect(s.vibe) { draft = s.vibe }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        s.upcoming.firstOrNull()?.plan?.takeUnless { s.mixing }?.let { plan ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface1).border(1.dp, c.hairline, RoundedCornerShape(14.dp)).padding(14.dp),
            ) {
                IndexLabel(stringResource(Res.string.shell_dj_next_move))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Glyph(plan.style.glyph(), vibe, 26.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(plan.style.nameRes()), style = Shell.type.title.copy(fontSize = 17.sp), color = c.ink)
                        Text(stringResource(plan.style.bodyRes()), style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IndexLabel(stringResource(Res.string.shell_dj_vibe_tune), Modifier.weight(1f))
            IndexLabel(stringResource(Res.string.shell_dj_energy_pct, "${(SetPlannerTarget.energyNow(s) * 100).roundToInt()}%"), color = vibe)
            Spacer(Modifier.width(10.dp))
            IndexLabel(stringResource(Res.string.shell_dj_fresh_pct, "${(draft.fresh * 100).roundToInt()}%"), color = c.ink2)
        }
        DjVibePad(
            draft.energy, draft.fresh, vibe, Modifier.fillMaxWidth().aspectRatio(1.6f).heightIn(max = 280.dp),
            onChange = { e, f -> draft = draft.copy(energy = e, fresh = f) },
            onRelease = { ShellDj.setVibe(controller, draft) },
        )
        // The pad already sets new music (its vertical axis), so the booth leaves that slider out.
        DjTuning(draft, vibe, showFresh = false, onRelease = { ShellDj.setVibe(controller, draft) }) { draft = it }
    }
}

/** Target energy right now, for the vibe readout. */
private object SetPlannerTarget {
    fun energyNow(s: DjSession): Double = s.targetAt(s.elapsedMs)
}

// ---- You DJ: pick the next track and the move ---------------------------------------------------

@Composable
private fun DjYouPanel(s: DjSession, controller: PlaybackController, vibe: Color) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val next = s.upcoming.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IndexLabel(stringResource(Res.string.shell_dj_pick_next))
        Text(stringResource(Res.string.shell_dj_pick_next_hint), style = Shell.type.bodySmall, color = c.ink2)
        if (next != null) {
            // What's cued now, then the alternatives.
            DjSuggestionRow(
                app.turtleplayer.mix.Pick(next.track.facts(next.requested), 0.0, next.reasons), next.track.song,
                s.liveBpm * s.speed, s.liveEnergy, cued = true, vibe = vibe,
            ) {}
        }
        if (s.suggestions.isEmpty()) {
            Text(stringResource(Res.string.shell_dj_deck_empty), style = Shell.type.bodySmall, color = c.ink2)
        }
        s.suggestions.forEach { pick ->
            key(pick.track.id) {
                DjSuggestionRow(pick, suggestionSong(pick.track.id), s.liveBpm * s.speed, s.liveEnergy, cued = false, vibe = vibe) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    ShellDj.chooseNext(controller, pick)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        IndexLabel(stringResource(Res.string.shell_dj_your_move))
        DjMovePicker(s.youStyle, next?.plan?.style, vibe, enabled = !s.mixing) { s.youStyle = it }
        (s.youStyle ?: next?.plan?.style)?.let { st ->
            Text(stringResource(st.bodyRes()), style = Shell.type.bodySmall, color = c.ink2)
        }
    }
}

/** Songs behind the You-mode suggestions (kept by the controller while they are offered). */
@Composable
private fun suggestionSong(id: String) = ShellDj.suggestionSong(id)

// ---- Live effects -----------------------------------------------------------------------------------

@Composable
private fun DjFxSection(s: DjSession, controller: PlaybackController, vibe: Color, open: Boolean, onToggle: () -> Unit, showToggle: Boolean, columns: Int) {
    val c = Shell.colors
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (showToggle) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface1).border(1.dp, c.hairline, RoundedCornerShape(14.dp))
                    .shellPressable(onToggle, label = stringResource(Res.string.shell_dj_jump_in), focusShape = RoundedCornerShape(14.dp))
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(Glyphs.XyPad, vibe, 20.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    stringResource(if (open) Res.string.shell_dj_jump_out else Res.string.shell_dj_jump_in),
                    style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f),
                )
                Glyph(if (open) Glyphs.ChevronDown else Glyphs.Add, c.ink2, 18.dp)
            }
        }
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                val fx = s.fxAvailable
                if (!fx) Text(stringResource(Res.string.shell_dj_fx_unavailable), style = Shell.type.bodySmall, color = c.danger)
                DjFxPad(vibe, fx, Modifier.fillMaxWidth())
                DjEqKnobs(s, vibe, fx)
                DjPerformancePads(vibe, fx, columns)
                DjSamplePads(vibe, fx)
                DjFxFooter(s, controller, vibe)
            }
        }
    }
}

/** Tempo, snap to beat and reset: the small switches under the pads. */
@Composable
private fun DjFxFooter(s: DjSession, controller: PlaybackController, vibe: Color) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val tempoPct = ((s.userTempo - 1f) * 100f).roundToInt()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        DjSmallButton(Glyphs.TempoDown, stringResource(Res.string.shell_dj_tempo_down)) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            ShellDj.nudgeTempo(controller, up = false)
        }
        Box(
            Modifier.clip(CircleShape).shellPressable({ ShellDj.resetTempo(controller) }, label = stringResource(Res.string.shell_dj_tempo_reset), focusShape = CircleShape)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            IndexLabel(stringResource(Res.string.shell_dj_tempo, (if (tempoPct > 0) "+" else "") + "$tempoPct%"), color = if (tempoPct != 0) vibe else c.ink2)
        }
        DjSmallButton(Glyphs.TempoUp, stringResource(Res.string.shell_dj_tempo_up)) {
            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
            ShellDj.nudgeTempo(controller, up = true)
        }
        Spacer(Modifier.weight(1f))
        val snap = stringResource(Res.string.shell_dj_snap)
        Row(
            Modifier.height(36.dp).clip(CircleShape).background(if (s.quantize) vibe.copy(alpha = 0.18f) else c.surface2)
                .shellPressable({ s.quantize = !s.quantize }, label = snap, focusShape = CircleShape).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (s.quantize) {
                Glyph(Glyphs.Check, vibe, 14.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(snap, style = Shell.type.label, color = if (s.quantize) vibe else c.ink2)
        }
        DjSmallButton(Glyphs.Reset, stringResource(Res.string.shell_dj_reset_fx)) {
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            ShellDj.resetFx()
        }
    }
}

@Composable
private fun DjSmallButton(glyph: ImageVector, label: String, onClick: () -> Unit) {
    val c = Shell.colors
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(c.surface2).border(1.dp, c.hairline, CircleShape)
            .shellPressable(onClick, label = label, focusShape = CircleShape),
        contentAlignment = Alignment.Center,
    ) { Glyph(glyph, c.ink, 18.dp) }
}

// ---- Energy and up next -----------------------------------------------------------------------------

/** The set's energy arc (as tuned) with each track as a dot and a playhead at set time. */
@Composable
private fun DjEnergyPanel(s: DjSession, pulse: DjPulse, vibe: Color, graphHeight: Dp) {
    val c = Shell.colors
    Column(Modifier.fillMaxWidth()) {
        IndexLabel(stringResource(Res.string.shell_dj_energy) + " · " + djArcName(s.arc))
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
            val n = 64
            // The arc as drawn (faint) and as your vibe bends it (bright).
            val drawn = Path()
            val tuned = Path()
            for (i in 0..n) {
                val px = size.width * i / n
                val t = i.toDouble() / n
                if (i == 0) drawn.moveTo(px, y(s.arcAt(t))) else drawn.lineTo(px, y(s.arcAt(t)))
                if (i == 0) tuned.moveTo(px, y(s.targetAtT(t))) else tuned.lineTo(px, y(s.targetAtT(t)))
            }
            val fill = Path().apply {
                addPath(tuned)
                lineTo(size.width, size.height)
                lineTo(0f, size.height)
                close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(vibe.copy(alpha = 0.3f), Color.Transparent)))
            drawPath(drawn, c.ink2.copy(alpha = 0.35f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
            drawPath(tuned, vibe, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            val head = x(elapsed)
            drawRect(c.ground.copy(alpha = 0.35f), size = Size(head, size.height))
            for (p in s.played) if (p.startMs >= base) drawCircle(c.ink2, 3.dp.toPx(), Offset(x(p.startMs), y(p.track.energy)))
            for (u in s.upcoming) drawCircle(vibe, 3.5.dp.toPx(), Offset(x(u.startMs), y(u.track.energy)), style = Stroke(1.5.dp.toPx()))
            s.current?.let { cur ->
                val e = if (s.liveEnergy >= 0f) s.liveEnergy.toDouble() else cur.track.energy
                val at = Offset(x(cur.startMs), y(e))
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
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        ShellArt(slot.track.song.displayThumbnailUrl, Modifier.size(46.dp), shape = RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (first) IndexLabel(stringResource(Res.string.shell_dj_next_badge), color = vibe)
                if (first) slot.plan?.let { IndexLabel(stringResource(it.style.nameRes()).uppercase(), color = c.ink2) }
            }
            Text(slot.track.song.title, style = Shell.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val why = reasonsText(slot.reasons)
            Text(
                if (why.isNotEmpty()) why else slot.track.song.artists.joinToString(", ") { it.name },
                style = Shell.type.bodySmall, color = if (why.isNotEmpty()) vibe.copy(alpha = 0.9f) else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val bpm = (slot.track.bpm * speed).roundToInt()
            IndexLabel(stringResource(if (slot.track.bpmMeasured) Res.string.shell_dj_bpm_live else Res.string.shell_dj_bpm_est, bpm), color = c.ink2)
            Spacer(Modifier.height(5.dp))
            DjEnergyMeter(slot.track.energy, vibe)
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
