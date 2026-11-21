package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.mix.FxParam
import app.turtleplayer.mix.Pick
import app.turtleplayer.mix.PickReason
import app.turtleplayer.mix.SampleId
import app.turtleplayer.mix.TransitionStyle
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.*
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.displayThumbnailUrl
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.abs
import kotlin.math.roundToInt

// ---- Words and icons ----------------------------------------------------------------------------

internal fun TransitionStyle.nameRes(): StringResource = when (this) {
    TransitionStyle.Blend -> Res.string.shell_dj_move_blend
    TransitionStyle.FilterFade -> Res.string.shell_dj_move_filter
    TransitionStyle.EchoOut -> Res.string.shell_dj_move_echo
    TransitionStyle.Cut -> Res.string.shell_dj_move_cut
    TransitionStyle.Spinback -> Res.string.shell_dj_move_spin
    TransitionStyle.Brake -> Res.string.shell_dj_move_brake
    TransitionStyle.RiserDrop -> Res.string.shell_dj_move_riser
    TransitionStyle.LoopRoll -> Res.string.shell_dj_move_roll
}

internal fun TransitionStyle.bodyRes(): StringResource = when (this) {
    TransitionStyle.Blend -> Res.string.shell_dj_move_blend_body
    TransitionStyle.FilterFade -> Res.string.shell_dj_move_filter_body
    TransitionStyle.EchoOut -> Res.string.shell_dj_move_echo_body
    TransitionStyle.Cut -> Res.string.shell_dj_move_cut_body
    TransitionStyle.Spinback -> Res.string.shell_dj_move_spin_body
    TransitionStyle.Brake -> Res.string.shell_dj_move_brake_body
    TransitionStyle.RiserDrop -> Res.string.shell_dj_move_riser_body
    TransitionStyle.LoopRoll -> Res.string.shell_dj_move_roll_body
}

internal fun TransitionStyle.glyph(): ImageVector = when (this) {
    TransitionStyle.Blend -> Glyphs.MoveBlend
    TransitionStyle.FilterFade -> Glyphs.MoveFilter
    TransitionStyle.EchoOut -> Glyphs.MoveEcho
    TransitionStyle.Cut -> Glyphs.MoveCut
    TransitionStyle.Spinback -> Glyphs.MoveSpin
    TransitionStyle.Brake -> Glyphs.MoveBrake
    TransitionStyle.RiserDrop -> Glyphs.MoveRiser
    TransitionStyle.LoopRoll -> Glyphs.MoveRoll
}

internal fun PickReason.res(): StringResource = when (this) {
    PickReason.BuildingUp -> Res.string.shell_dj_why_up
    PickReason.CoolingDown -> Res.string.shell_dj_why_down
    PickReason.HoldingTheGroove -> Res.string.shell_dj_why_hold
    PickReason.PeakMoment -> Res.string.shell_dj_why_peak
    PickReason.Favorite -> Res.string.shell_dj_why_favorite
    PickReason.Liked -> Res.string.shell_dj_why_liked
    PickReason.FreshFind -> Res.string.shell_dj_why_fresh
    PickReason.TempoMatch -> Res.string.shell_dj_why_tempo
    PickReason.GenreHop -> Res.string.shell_dj_why_hop
    PickReason.Requested -> Res.string.shell_dj_why_request
    PickReason.ArtistYouLike -> Res.string.shell_dj_why_artist
}

@Composable
internal fun reasonsText(reasons: List<PickReason>): String = reasons.take(3).map { stringResource(it.res()) }.joinToString(" · ")

/** The booth's narration: what the DJ is doing and why, in plain words. */
@Composable
internal fun djNowText(now: DjNow): String = when (now) {
    DjNow.Digging -> stringResource(Res.string.shell_dj_now_digging)
    is DjNow.OnAir -> reasonsText(now.slot.reasons).ifEmpty { stringResource(Res.string.shell_dj_now_opening, now.slot.track.song.title) }
    is DjNow.Mixing -> stringResource(Res.string.shell_dj_now_mixing, stringResource(now.style.nameRes()), now.into.track.song.title)
    is DjNow.Skipped -> if (now.artist.isBlank()) stringResource(Res.string.shell_dj_now_skipped_plain) else stringResource(Res.string.shell_dj_now_skipped, now.artist)
    is DjNow.Liked -> if (now.artist.isBlank()) stringResource(Res.string.shell_dj_now_liked_plain) else stringResource(Res.string.shell_dj_now_liked, now.artist)
    is DjNow.Remixed -> stringResource(
        when (now.vibe) {
            VibeChange.Flair -> Res.string.shell_dj_now_remixed_flair
            VibeChange.Pace -> Res.string.shell_dj_now_remixed_pace
            else -> Res.string.shell_dj_now_remixed
        },
    )
    is DjNow.Requested -> stringResource(Res.string.shell_dj_now_requested, now.title)
    DjNow.SafetyNet -> stringResource(Res.string.shell_dj_now_safety)
}

// ---- Vibe ---------------------------------------------------------------------------------------

/**
 * The vibe pad: one dot on a map. Left-right is energy (Chill to Hype), down-up is how much new music
 * (Favourites to New music). Haptic ticks at the middle lines, so it feels like a real control.
 */
@Composable
internal fun DjVibePad(energy: Float, fresh: Float, vibe: Color, modifier: Modifier = Modifier, onChange: (energy: Float, fresh: Float) -> Unit, onRelease: () -> Unit = {}) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val change by rememberUpdatedState(onChange)
    val release by rememberUpdatedState(onRelease)
    var dragging by remember { mutableStateOf(false) }
    val glow by animateFloatAsState(if (dragging) 1f else 0f, tween(ShellMotion.TAP_MS))
    val shape = RoundedCornerShape(18.dp)
    val hint = stringResource(Res.string.shell_dj_vibe_pad_hint)
    Box(
        modifier.clip(shape).background(c.surface1).border(1.dp, c.hairline, shape)
            .semantics { contentDescription = hint }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    var lastQx = -1
                    var lastQy = -1
                    fun at(p: Offset) {
                        val x = (p.x / size.width).coerceIn(0f, 1f)
                        val y = 1f - (p.y / size.height).coerceIn(0f, 1f)
                        val qx = if (x < 0.5f) 0 else 1
                        val qy = if (y < 0.5f) 0 else 1
                        if ((lastQx >= 0 && qx != lastQx) || (lastQy >= 0 && qy != lastQy)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        lastQx = qx
                        lastQy = qy
                        change(x, y)
                    }
                    at(down.position)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val p = event.changes.firstOrNull() ?: break
                        if (!p.pressed) break
                        at(p.position)
                        p.consume()
                    }
                    dragging = false
                    release()
                }
            },
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width
            val h = size.height
            // Warmth grows to the right (energy), sparkle to the top (new music).
            drawRect(Brush.horizontalGradient(listOf(Color(0xFF6FE3C8).copy(alpha = 0.10f), Color.Transparent, vibe.copy(alpha = 0.16f))))
            drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.05f), Color.Transparent)))
            val grid = c.hairlineSoft
            for (k in 1..3) {
                drawLine(grid, Offset(w * k / 4, 0f), Offset(w * k / 4, h), 1f)
                drawLine(grid, Offset(0f, h * k / 4), Offset(w, h * k / 4), 1f)
            }
            drawLine(c.hairline, Offset(w / 2, 0f), Offset(w / 2, h), 1.5f)
            drawLine(c.hairline, Offset(0f, h / 2), Offset(w, h / 2), 1.5f)
            val at = Offset(energy * w, (1f - fresh) * h)
            drawCircle(vibe.copy(alpha = 0.18f + 0.12f * glow), (34f + 10f * glow).dp.toPx(), at)
            drawCircle(vibe.copy(alpha = 0.35f), 20.dp.toPx(), at)
            drawCircle(vibe, 11.dp.toPx(), at)
            drawCircle(c.onAccent, 4.dp.toPx(), at)
        }
        val label = Shell.type.index
        Text(stringResource(Res.string.shell_dj_vibe_chill).uppercase(), style = label, color = c.ink2, modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp))
        Text(stringResource(Res.string.shell_dj_vibe_hype).uppercase(), style = label, color = vibe, modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp))
        Text(stringResource(Res.string.shell_dj_vibe_fresh).uppercase(), style = label, color = c.ink2, modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        Text(stringResource(Res.string.shell_dj_vibe_favourites).uppercase(), style = label, color = c.ink2, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp))
    }
}

/** A labelled 0..1 slider with words at both ends ("Smooth ... Tricks"), snapping to tenths with a tick. */
@Composable
internal fun DjVibeSlider(title: String, left: String, right: String, value: Float, vibe: Color, modifier: Modifier = Modifier, onChange: (Float) -> Unit, onRelease: () -> Unit = {}) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val change by rememberUpdatedState(onChange)
    val release by rememberUpdatedState(onRelease)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.weight(1f))
            Text(if (value < 0.5f) left else right, style = Shell.type.bodySmall, color = if (value < 0.5f) c.ink2 else vibe)
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth().height(36.dp)
                .semantics { contentDescription = "$title ${(value * 100).roundToInt()}%" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var last = -1
                        fun at(x: Float) {
                            val v = (x / size.width).coerceIn(0f, 1f)
                            val tenth = (v * 10).roundToInt()
                            if (last >= 0 && tenth != last) haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                            last = tenth
                            change(v)
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
                        release()
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val y = size.height / 2
                val h = 6.dp.toPx()
                drawRoundRect(c.surface3, Offset(0f, y - h / 2), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(vibe.copy(alpha = 0.7f), Offset(0f, y - h / 2), Size(size.width * value, h), CornerRadius(h / 2))
                for (k in 0..10) {
                    val x = size.width * k / 10
                    drawCircle(if (k / 10f <= value) vibe else c.hairline, 1.5.dp.toPx(), Offset(x, y + 12.dp.toPx()))
                }
                val kx = (size.width * value).coerceIn(10.dp.toPx(), size.width - 10.dp.toPx())
                drawCircle(c.ink, 10.dp.toPx(), Offset(kx, y))
                drawCircle(vibe, 4.dp.toPx(), Offset(kx, y))
            }
        }
        Row {
            Text(left, style = Shell.type.bodySmall.copy(fontSize = 11.sp), color = c.ink2, modifier = Modifier.weight(1f))
            Text(right, style = Shell.type.bodySmall.copy(fontSize = 11.sp), color = c.ink2)
        }
    }
}

// ---- Moves --------------------------------------------------------------------------------------

/** The eight moves as chips: the DJ's pick is tagged, yours is lit. */
@Composable
internal fun DjMovePicker(selected: TransitionStyle?, suggested: TransitionStyle?, vibe: Color, enabled: Boolean, onSelect: (TransitionStyle) -> Unit) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TransitionStyle.entries.forEach { style ->
            val on = style == (selected ?: suggested)
            val bg by animateColorAsState(if (on) vibe else c.surface2, ShellMotion.emphasized())
            val fg by animateColorAsState(if (on) c.onAccent else c.ink, ShellMotion.emphasized())
            val name = stringResource(style.nameRes())
            Column(
                Modifier.width(86.dp).clip(RoundedCornerShape(14.dp)).background(bg)
                    .border(1.dp, if (on) Color.Transparent else c.hairline, RoundedCornerShape(14.dp))
                    .shellPressable({
                        if (enabled) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            onSelect(style)
                        }
                    }, label = name, focusShape = RoundedCornerShape(14.dp))
                    .padding(vertical = 10.dp, horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Glyph(style.glyph(), fg, 24.dp)
                Spacer(Modifier.height(6.dp))
                Text(name, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (style == suggested) {
                    Text(stringResource(Res.string.shell_dj_dj_pick), style = Shell.type.index.copy(fontSize = 9.sp), color = if (on) c.onAccent else vibe)
                }
            }
        }
    }
}

// ---- Live effects -------------------------------------------------------------------------------

internal enum class PadFx { Echo, Flanger, Stutter }

/**
 * The FX pad (like a Kaoss pad): hold and slide. Left-right is the filter (left muffled, right thin),
 * up is more of the chosen effect. Letting go puts everything back (an echo rings out naturally).
 */
@Composable
internal fun DjFxPad(vibe: Color, enabled: Boolean, modifier: Modifier = Modifier) {
    val c = Shell.colors
    var mode by remember { mutableStateOf(PadFx.Echo) }
    var touch by remember { mutableStateOf<Offset?>(null) }
    val haptics = LocalHapticFeedback.current
    val currentMode by rememberUpdatedState(mode)
    val shape = RoundedCornerShape(18.dp)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IndexLabel(stringResource(Res.string.shell_dj_fx_pad), Modifier.weight(1f))
            PadFx.entries.forEach { m ->
                val label = stringResource(
                    when (m) {
                        PadFx.Echo -> Res.string.shell_dj_fx_echo
                        PadFx.Flanger -> Res.string.shell_dj_fx_flanger
                        PadFx.Stutter -> Res.string.shell_dj_fx_stutter
                    },
                )
                val on = m == mode
                Box(
                    Modifier.padding(start = 6.dp).height(30.dp).clip(CircleShape).background(if (on) vibe else c.surface2)
                        .shellPressable({ mode = m }, label = label, focusShape = CircleShape).padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = Shell.type.label, color = if (on) c.onAccent else c.ink) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.7f).heightIn(max = 260.dp).clip(shape).background(c.surface1).border(1.dp, if (touch != null) vibe else c.hairline, shape)
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                        fun at(p: Offset) {
                            touch = p
                            val x = (p.x / size.width).coerceIn(0f, 1f)
                            val y = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            val filter = (x * 2f - 1f).let { if (abs(it) < 0.08f) 0f else it }
                            ShellDj.fx(FxParam.Filter, filter)
                            when (currentMode) {
                                PadFx.Echo -> {
                                    ShellDj.fx(FxParam.EchoBeats, if (y > 0.66f) 0.5f else 0.75f, 0)
                                    ShellDj.fx(FxParam.EchoSend, y)
                                    ShellDj.fx(FxParam.EchoWet, 0.35f + 0.55f * y)
                                    ShellDj.fx(FxParam.EchoFeedback, 0.35f + 0.4f * y)
                                }
                                PadFx.Flanger -> ShellDj.fx(FxParam.Flanger, y)
                                PadFx.Stutter -> {
                                    ShellDj.fx(FxParam.GateBeats, if (y > 0.66f) 0.125f else 0.25f, 0)
                                    ShellDj.fx(FxParam.Gate, y)
                                }
                            }
                        }
                        at(down.position)
                        down.consume()
                        while (true) {
                            val e = awaitPointerEvent()
                            val p = e.changes.firstOrNull() ?: break
                            if (!p.pressed) break
                            at(p.position)
                            p.consume()
                        }
                        touch = null
                        ShellDj.fx(FxParam.Filter, 0f, 250)
                        ShellDj.fx(FxParam.EchoSend, 0f, 40)
                        ShellDj.fx(FxParam.EchoWet, 0f, 2_500)
                        ShellDj.fx(FxParam.Flanger, 0f, 300)
                        ShellDj.fx(FxParam.Gate, 0f, 60)
                    }
                },
        ) {
            Canvas(Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                drawRect(Brush.horizontalGradient(listOf(Color(0xFF3B5BFF).copy(alpha = 0.14f), Color.Transparent, Color(0xFFFFD166).copy(alpha = 0.12f))))
                drawRect(Brush.verticalGradient(listOf(vibe.copy(alpha = 0.14f), Color.Transparent)))
                for (k in 1..5) drawLine(c.hairlineSoft, Offset(w * k / 6, 0f), Offset(w * k / 6, h), 1f)
                for (k in 1..3) drawLine(c.hairlineSoft, Offset(0f, h * k / 4), Offset(w, h * k / 4), 1f)
                drawLine(c.hairline, Offset(w / 2, 0f), Offset(w / 2, h), 1.5f)
                touch?.let { t ->
                    drawLine(vibe.copy(alpha = 0.5f), Offset(t.x, 0f), Offset(t.x, h), 1.5f)
                    drawLine(vibe.copy(alpha = 0.5f), Offset(0f, t.y), Offset(w, t.y), 1.5f)
                    drawCircle(vibe.copy(alpha = 0.25f), 40.dp.toPx(), t)
                    drawCircle(vibe, 14.dp.toPx(), t)
                }
            }
            if (touch == null) {
                Text(
                    stringResource(if (enabled) Res.string.shell_dj_fx_pad_hint else Res.string.shell_dj_fx_unavailable),
                    style = Shell.type.bodySmall, color = c.ink2, textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
        }
    }
}

/** Three EQ knobs: drag up or down, tap the name to cut the band, double tap the knob to reset. */
@Composable
internal fun DjEqKnobs(s: DjSession, vibe: Color, enabled: Boolean) {
    Column {
        IndexLabel(stringResource(Res.string.shell_dj_eq))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            DjKnob(stringResource(Res.string.shell_dj_eq_low), s.userFx[FxParam.Low] ?: 0f, vibe, enabled) { v, ramp -> ShellDj.fx(FxParam.Low, v, ramp) }
            DjKnob(stringResource(Res.string.shell_dj_eq_mid), s.userFx[FxParam.Mid] ?: 0f, vibe, enabled) { v, ramp -> ShellDj.fx(FxParam.Mid, v, ramp) }
            DjKnob(stringResource(Res.string.shell_dj_eq_high), s.userFx[FxParam.High] ?: 0f, vibe, enabled) { v, ramp -> ShellDj.fx(FxParam.High, v, ramp) }
        }
        Text(stringResource(Res.string.shell_dj_eq_hint), style = Shell.type.bodySmall.copy(fontSize = 11.sp), color = Shell.colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
    }
}

@Composable
private fun DjKnob(label: String, value: Float, vibe: Color, enabled: Boolean, onChange: (Float, Int) -> Unit) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val killed = value <= -0.98f
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp)
                .semantics { contentDescription = "$label ${(value * 100).roundToInt()}" }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    var lastTap = 0L
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val t = down.uptimeMillis
                        if (t - lastTap < 300) {
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            change(0f, 150)
                        }
                        lastTap = t
                        var v = current
                        var lastY = down.position.y
                        down.consume()
                        while (true) {
                            val e = awaitPointerEvent()
                            val p = e.changes.firstOrNull() ?: break
                            if (!p.pressed) break
                            val dy = lastY - p.position.y
                            lastY = p.position.y
                            val before = v
                            v = (v + dy / (140.dp.toPx())).coerceIn(-1f, 1f)
                            if ((before < 0f) != (v < 0f)) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            change(v, 30)
                            p.consume()
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.matchParentSize()) {
                val stroke = 5.dp.toPx()
                val inset = stroke / 2 + 2.dp.toPx()
                val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                drawArc(c.surface3, 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val sweep = 135f * value
                drawArc(if (killed) c.danger else vibe, 270f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                val r = size.minDimension / 2 - 12.dp.toPx()
                drawCircle(c.surface2, r)
                val angle = (270.0 + sweep) * kotlin.math.PI / 180.0
                val center = Offset(size.width / 2, size.height / 2)
                val tip = center + Offset((kotlin.math.cos(angle) * r * 0.75).toFloat(), (kotlin.math.sin(angle) * r * 0.75).toFloat())
                drawLine(c.ink, center, tip, 3.dp.toPx(), StrokeCap.Round)
            }
        }
        val bg by animateColorAsState(if (killed) c.danger else Color.Transparent, ShellMotion.emphasized())
        Box(
            Modifier.padding(top = 4.dp).clip(CircleShape).background(bg)
                .shellPressable({
                    if (enabled) {
                        haptics.performHapticFeedback(if (killed) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        change(if (killed) 0f else -1f, 40)
                    }
                }, label = label, focusShape = CircleShape)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) { Text(label, style = Shell.type.index, color = if (killed) c.ground else c.ink) }
    }
}

/** A pad that does its thing while you hold it (loop rolls, brake, spinback, stutter, echo out). */
@Composable
internal fun DjHoldPad(glyph: ImageVector, label: String, vibe: Color, enabled: Boolean, modifier: Modifier, onPress: () -> Unit, onRelease: () -> Unit) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    var held by remember { mutableStateOf(false) }
    val press by rememberUpdatedState(onPress)
    val release by rememberUpdatedState(onRelease)
    val bg by animateColorAsState(if (held) vibe else c.surface2, tween(80))
    val fg by animateColorAsState(if (held) c.onAccent else c.ink, tween(80))
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.height(68.dp)
            .shadow(if (held) 16.dp else 0.dp, shape, ambientColor = vibe, spotColor = vibe)
            .clip(shape).background(bg).border(1.dp, if (held) Color.Transparent else c.hairline, shape)
            .semantics { contentDescription = label }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    held = true
                    haptics.performHapticFeedback(HapticFeedbackType.ToggleOn)
                    press()
                    while (true) {
                        val e = awaitPointerEvent()
                        val p = e.changes.firstOrNull() ?: break
                        p.consume()
                        if (!p.pressed) break
                    }
                    held = false
                    release()
                }
            }
            .padding(10.dp),
    ) {
        Glyph(glyph, fg, 20.dp, Modifier.align(Alignment.TopStart))
        Text(label, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** The hold pads, beat-synced: four loop lengths, brake, spinback, stutter, echo out. */
@Composable
internal fun DjPerformancePads(vibe: Color, enabled: Boolean, columns: Int) {
    val rolls = listOf(1f to "1", 0.5f to "½", 0.25f to "¼", 0.125f to "⅛")
    val pads = buildList<@Composable (Modifier) -> Unit> {
        rolls.forEach { (beats, name) ->
            add { m ->
                DjHoldPad(Glyphs.MoveRoll, stringResource(Res.string.shell_dj_pad_roll, name), vibe, enabled, m,
                    onPress = { ShellDj.fx(FxParam.RollBeats, beats, 0); ShellDj.fx(FxParam.Roll, 1f, 0) },
                    onRelease = { ShellDj.fx(FxParam.Roll, 0f, 0) })
            }
        }
        add { m ->
            DjHoldPad(Glyphs.MoveBrake, stringResource(Res.string.shell_dj_pad_brake), vibe, enabled, m,
                onPress = { ShellDj.fx(FxParam.BrakeBeats, 2f, 0); ShellDj.fx(FxParam.Brake, 1f, 0) },
                onRelease = { ShellDj.fx(FxParam.Brake, 0f, 0) })
        }
        add { m ->
            DjHoldPad(Glyphs.MoveSpin, stringResource(Res.string.shell_dj_pad_spin), vibe, enabled, m,
                onPress = { ShellDj.fx(FxParam.SpinBeats, 1f, 0); ShellDj.fx(FxParam.Spin, 1f, 0) },
                onRelease = { ShellDj.fx(FxParam.Spin, 0f, 0) })
        }
        add { m ->
            DjHoldPad(Glyphs.Stutter, stringResource(Res.string.shell_dj_pad_stutter), vibe, enabled, m,
                onPress = { ShellDj.fx(FxParam.GateBeats, 0.125f, 0); ShellDj.fx(FxParam.Gate, 1f, 20) },
                onRelease = { ShellDj.fx(FxParam.Gate, 0f, 20) })
        }
        add { m ->
            DjHoldPad(Glyphs.MoveEcho, stringResource(Res.string.shell_dj_pad_echo), vibe, enabled, m,
                onPress = {
                    ShellDj.fx(FxParam.EchoBeats, 0.75f, 0)
                    ShellDj.fx(FxParam.EchoFeedback, 0.62f, 0)
                    ShellDj.fx(FxParam.EchoWet, 0.9f, 20)
                    ShellDj.fx(FxParam.EchoSend, 1f, 20)
                    ShellDj.fx(FxParam.Dry, 0f, 180)
                },
                onRelease = {
                    ShellDj.fx(FxParam.EchoSend, 0f, 20)
                    ShellDj.fx(FxParam.Dry, 1f, 40)
                    ShellDj.fx(FxParam.EchoWet, 0f, 2_500)
                })
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IndexLabel(stringResource(Res.string.shell_dj_pads))
        pads.chunked(columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** One-shot samples, synthesized by the rack: tap to fire (on the beat when Snap is on). */
@Composable
internal fun DjSamplePads(vibe: Color, enabled: Boolean) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    val samples = listOf(
        Triple(SampleId.AirHorn, Glyphs.AirHorn, Res.string.shell_dj_sample_horn),
        Triple(SampleId.Siren, Glyphs.Siren, Res.string.shell_dj_sample_siren),
        Triple(SampleId.Riser, Glyphs.MoveRiser, Res.string.shell_dj_sample_riser),
        Triple(SampleId.Impact, Glyphs.Impact, Res.string.shell_dj_sample_impact),
        Triple(SampleId.Laser, Glyphs.Laser, Res.string.shell_dj_sample_laser),
    )
    Column {
        IndexLabel(stringResource(Res.string.shell_dj_samples))
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            samples.forEach { (id, glyph, res) ->
                val label = stringResource(res)
                var flash by remember { mutableFloatStateOf(0f) }
                val lit by animateFloatAsState(flash, tween(if (flash > 0f) 40 else 600), finishedListener = { if (flash > 0f) flash = 0f })
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                        .drawBehind {
                            drawRect(c.surface2)
                            drawRect(vibe.copy(alpha = lit * 0.9f))
                        }
                        .border(1.dp, c.hairline, RoundedCornerShape(14.dp))
                        .shellPressable({
                            if (enabled) {
                                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                                flash = 1f
                                ShellDj.sample(id)
                            }
                        }, label = label, focusShape = RoundedCornerShape(14.dp))
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Glyph(glyph, if (lit > 0.5f) c.onAccent else c.ink, 22.dp)
                    Spacer(Modifier.height(4.dp))
                    Text(label, style = Shell.type.label.copy(fontSize = 11.sp), color = if (lit > 0.5f) c.onAccent else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ---- You mode: what's next ----------------------------------------------------------------------

/** A next-track choice: why it fits, and how well its tempo meets the one on air. */
@Composable
internal fun DjSuggestionRow(pick: Pick, song: SongItem?, liveBpm: Float, liveEnergy: Float, cued: Boolean, vibe: Color, onClick: () -> Unit) {
    val c = Shell.colors
    val f = pick.track
    // Only a measured tempo on both sides makes a match worth a number; otherwise show the estimate.
    val tempoMatch = if (liveBpm > 0f && f.bpmMeasured) {
        val ratio = listOf(f.bpm, f.bpm * 2, f.bpm / 2).minOf { abs(it - liveBpm) / liveBpm }
        ((1f - ratio * 5f).coerceIn(0f, 1f) * 100).roundToInt()
    } else null
    val dir = when {
        liveEnergy < 0f -> null
        f.energy > liveEnergy + 0.08f -> Glyphs.Hype
        f.energy < liveEnergy - 0.08f -> Glyphs.Chill
        else -> null
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (cued) vibe.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, if (cued) vibe else Color.Transparent, RoundedCornerShape(12.dp))
            .shellPressable(onClick, label = song?.title ?: f.id).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellArt(song?.displayThumbnailUrl, Modifier.size(48.dp), shape = RoundedCornerShape(6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song?.title.orEmpty(), style = Shell.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(reasonsText(pick.reasons), style = Shell.type.bodySmall, color = vibe, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (cued) {
                IndexLabel(stringResource(Res.string.shell_dj_cued), color = vibe)
            } else if (tempoMatch != null) {
                IndexLabel(stringResource(Res.string.shell_dj_match_tempo, "$tempoMatch%"), color = if (tempoMatch >= 80) vibe else c.ink2)
            } else {
                IndexLabel(stringResource(Res.string.shell_dj_bpm_est, f.bpm.roundToInt()), color = c.ink2)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                dir?.let { Glyph(it, if (it == Glyphs.Hype) vibe else c.ink2, 14.dp) }
                Spacer(Modifier.width(4.dp))
                DjEnergyMeter(f.energy.toDouble(), vibe)
            }
        }
    }
}

/** Five rising bars: a track's energy at a glance. */
@Composable
internal fun DjEnergyMeter(energy: Double, vibe: Color, size: Dp = 30.dp) {
    val off = Shell.colors.surface3
    Canvas(Modifier.size(size, size * 0.4f)) {
        val lit = (energy * 5).roundToInt().coerceIn(1, 5)
        val w = this.size.width / 5 - 2.dp.toPx()
        for (i in 0 until 5) {
            val h = this.size.height * (0.4f + 0.15f * i)
            drawRoundRect(if (i < lit) vibe else off, Offset(i * (w + 2.dp.toPx()), this.size.height - h), Size(w, h), CornerRadius(1.dp.toPx()))
        }
    }
}

/** Two-way segmented switch (Auto / You DJ). */
@Composable
internal fun DjSegmented(options: List<Pair<String, ImageVector>>, selected: Int, vibe: Color, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val c = Shell.colors
    val haptics = LocalHapticFeedback.current
    BoxWithConstraints(modifier.height(44.dp).clip(CircleShape).background(c.surface2).border(1.dp, c.hairline, CircleShape).padding(4.dp)) {
        val w = maxWidth / options.size
        val x by animateFloatAsState(selected.toFloat(), ShellMotion.emphasized())
        Box(Modifier.padding(start = w * x).width(w).height(36.dp).clip(CircleShape).background(vibe))
        Row(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (label, glyph) ->
                val on = i == selected
                Row(
                    Modifier.weight(1f).height(36.dp).clip(CircleShape)
                        .shellPressable({
                            if (!on) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onSelect(i)
                            }
                        }, label = label, focusShape = CircleShape),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(glyph, if (on) c.onAccent else c.ink, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(label, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = if (on) c.onAccent else c.ink)
                }
            }
        }
    }
}
