package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.turtleplayer.bridge.DjArc
import app.turtleplayer.bridge.DjEngine
import app.turtleplayer.mix.VibeSettings
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.*
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.ui.playback.LocalPlaybackController
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Ready-made vibes: the arc, the genres and the tuning a good DJ would bring to that night. */
internal enum class DjPreset(
    val arc: DjArc,
    val genres: List<String>,
    val vibe: VibeSettings,
    val color: Color,
    val title: StringResource,
    val body: StringResource,
) {
    Party(DjArc.HouseParty, listOf("house", "dance", "pop", "hiphop"), VibeSettings(0.5f, 0.4f, 0.55f, 0.35f, 0.5f), Color(0xFFD4F55A), Res.string.shell_dj_preset_party, Res.string.shell_dj_preset_party_body),
    Pregame(DjArc.Waves, listOf("hiphop", "pop", "reggaeton", "afrobeats"), VibeSettings(0.6f, 0.35f, 0.78f, 0.7f, 0.6f), Color(0xFFFF7A59), Res.string.shell_dj_preset_pregame, Res.string.shell_dj_preset_pregame_body),
    Club(DjArc.ClubPeak, listOf("techno", "house", "edm"), VibeSettings(0.6f, 0.6f, 0.35f, 0.1f, 0.3f), Color(0xFFFF4FA3), Res.string.shell_dj_preset_club, Res.string.shell_dj_preset_club_body),
    Workout(DjArc.Workout, listOf("workout", "edm", "hiphop"), VibeSettings(0.65f, 0.4f, 0.5f, 0.55f, 0.4f), Color(0xFFFF5A5F), Res.string.shell_dj_preset_workout, Res.string.shell_dj_preset_workout_body),
    Chill(DjArc.Lounge, listOf("chill", "rnb", "soul", "lofi"), VibeSettings(0.4f, 0.45f, 0.12f, 0.15f, 0.4f), Color(0xFF6FE3C8), Res.string.shell_dj_preset_chill, Res.string.shell_dj_preset_chill_body),
    Focus(DjArc.Focus, listOf("lofi", "ambient", "chill"), VibeSettings(0.4f, 0.5f, 0.03f, 0.05f, 0.2f), Color(0xFF7FA7FF), Res.string.shell_dj_preset_focus, Res.string.shell_dj_preset_focus_body),
    Throwback(DjArc.HouseParty, listOf("throwback", "funk", "disco", "pop"), VibeSettings(0.5f, 0.15f, 0.5f, 0.4f, 0.6f), Color(0xFFFFC94A), Res.string.shell_dj_preset_throwback, Res.string.shell_dj_preset_throwback_body),
    Sunrise(DjArc.Sunrise, listOf("house", "chill", "ambient"), VibeSettings(0.45f, 0.5f, 0.2f, 0.15f, 0.3f), Color(0xFFFFB347), Res.string.shell_dj_preset_sunrise, Res.string.shell_dj_preset_sunrise_body),
}

/** Display name for a DJ genre: our club extras, then the shared genre and mood names. */
@Composable
internal fun djGenreName(id: String): String = when (id) {
    DjEngine.YOURS -> stringResource(Res.string.shell_dj_genre_yours)
    "dnb" -> stringResource(Res.string.shell_dj_genre_dnb)
    "edm" -> stringResource(Res.string.shell_dj_genre_edm)
    "disco" -> stringResource(Res.string.shell_dj_genre_disco)
    "trance" -> stringResource(Res.string.shell_dj_genre_trance)
    "amapiano" -> stringResource(Res.string.shell_dj_genre_amapiano)
    "dancehall" -> stringResource(Res.string.shell_dj_genre_dancehall)
    else -> genreName(id)
}

@Composable
internal fun djArcName(arc: DjArc): String = stringResource(
    when (arc) {
        DjArc.Lounge -> Res.string.shell_dj_arc_lounge
        DjArc.HouseParty -> Res.string.shell_dj_arc_house
        DjArc.ClubPeak -> Res.string.shell_dj_arc_club
        DjArc.Waves -> Res.string.shell_dj_arc_waves
        DjArc.Workout -> Res.string.shell_dj_arc_workout
        DjArc.Focus -> Res.string.shell_dj_arc_focus
        DjArc.Sunrise -> Res.string.shell_dj_arc_sunrise
    },
)

@Composable
private fun djArcBody(arc: DjArc): String = stringResource(
    when (arc) {
        DjArc.Lounge -> Res.string.shell_dj_arc_lounge_body
        DjArc.HouseParty -> Res.string.shell_dj_arc_house_body
        DjArc.ClubPeak -> Res.string.shell_dj_arc_club_body
        DjArc.Waves -> Res.string.shell_dj_arc_waves_body
        DjArc.Workout -> Res.string.shell_dj_arc_workout_body
        DjArc.Focus -> Res.string.shell_dj_arc_focus_body
        DjArc.Sunrise -> Res.string.shell_dj_arc_sunrise_body
    },
)

/** The full-screen booth: setup when no set runs, the booth while one does. Exit keeps the music. */
@Composable
internal fun TurtleDjOverlay() = ShellTheme {
    val close = { ShellDj.open = false }
    // Phones and tablets: a full-screen booth. Desktop shows Turtle DJ as a page instead (TurtleDjPage
    // from the sidebar), with no dialog layer over the window.
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        DjKeepScreenOn()
        TurtleDjPage(onClose = close, modifier = Modifier.fillMaxSize())
    }
}

/** Turtle DJ itself: setup until a set starts, then the booth. */
@Composable
internal fun TurtleDjPage(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.background(Shell.colors.ground)) {
        val session = ShellDj.session
        Crossfade(session, animationSpec = tween(ShellMotion.EMPHASIZED_MS)) { s ->
            if (s == null) DjSetup(onClose = onClose) else DjBooth(s, onExit = onClose)
        }
    }
}

/**
 * Setup, built so anyone can start in one tap: "Start my mix" (your taste), or a preset and Start.
 * Customize opens everything a DJ would choose: who mixes, genres, energy arc, tuning, length.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DjSetup(onClose: () -> Unit) {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val haptics = LocalHapticFeedback.current
    var preset by remember { mutableStateOf(DjPreset.Party) }
    var picks by remember { mutableStateOf(preset.genres) }
    var arc by remember { mutableStateOf(preset.arc) }
    var vibe by remember { mutableStateOf(preset.vibe) }
    var length by remember { mutableStateOf(DjLength.M60) }
    var mode by remember { mutableStateOf(DjMode.Auto) }
    var customize by remember { mutableStateOf(false) }
    val queueTitle = stringResource(Res.string.shell_dj_queue_title)
    val color = preset.color
    val glow by animateColorAsState(color.copy(alpha = 0.18f), tween(ShellMotion.TINT_MS))
    fun choose(p: DjPreset) {
        preset = p
        picks = p.genres
        arc = p.arc
        vibe = p.vibe
    }
    BoxWithConstraints(
        Modifier.fillMaxSize().drawBehind {
            drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), Offset(size.width / 2, 0f), size.maxDimension * 0.8f))
        },
    ) {
        val wide = maxWidth >= 720.dp
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(Glyphs.ChevronDown, stringResource(Res.string.shell_dj_exit), onClose, c.ink, iconSize = 26.dp)
                    Spacer(Modifier.width(6.dp))
                    IndexLabel(stringResource(Res.string.shell_dj_label), color = color)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.shell_dj_setup_title2),
                    style = if (wide) Shell.type.display.copy(fontSize = 44.sp, lineHeight = 46.sp) else Shell.type.display,
                    color = c.ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.shell_dj_setup_body2), style = Shell.type.body, color = c.ink2)

                Spacer(Modifier.height(20.dp))
                DjYoursCard {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    ShellDj.start(controller, emptyList(), DjArc.Waves, length, VibeSettings(fresh = 0.35f), mode, queueTitle)
                }

                Spacer(Modifier.height(24.dp))
                IndexLabel(stringResource(Res.string.shell_dj_presets))
                Spacer(Modifier.height(10.dp))
                DjPreset.entries.chunked(if (wide) 4 else 2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { p ->
                            DjPresetCard(p, p == preset, Modifier.weight(1f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                choose(p)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.clip(CircleShape).shellPressable({ customize = !customize }, label = stringResource(Res.string.shell_dj_customize), focusShape = CircleShape)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Glyph(Glyphs.Sliders, c.ink, 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(if (customize) Res.string.shell_dj_customize_hide else Res.string.shell_dj_customize),
                        style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink,
                    )
                }
                AnimatedVisibility(customize) {
                    Column {
                        Spacer(Modifier.height(12.dp))
                        IndexLabel(stringResource(Res.string.shell_dj_mode))
                        Spacer(Modifier.height(10.dp))
                        DjSegmented(
                            listOf(stringResource(Res.string.shell_dj_mode_auto) to Glyphs.Turntable, stringResource(Res.string.shell_dj_mode_you) to Glyphs.Crossfader),
                            if (mode == DjMode.Auto) 0 else 1, color, Modifier.fillMaxWidth(),
                        ) { mode = if (it == 0) DjMode.Auto else DjMode.You }
                        Text(
                            stringResource(if (mode == DjMode.Auto) Res.string.shell_dj_mode_auto_body else Res.string.shell_dj_mode_you_body),
                            style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 6.dp),
                        )

                        Spacer(Modifier.height(22.dp))
                        IndexLabel(stringResource(Res.string.shell_dj_genres))
                        Spacer(Modifier.height(10.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DjEngine.genreIds.forEach { id ->
                                val on = id in picks
                                DjChip(djGenreName(id), on, color) {
                                    haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                                    picks = if (on) picks - id else picks + id
                                }
                            }
                        }
                        AnimatedVisibility(picks.isEmpty()) {
                            Text(stringResource(Res.string.shell_dj_genres_hint), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp))
                        }

                        Spacer(Modifier.height(22.dp))
                        IndexLabel(stringResource(Res.string.shell_dj_arc))
                        Spacer(Modifier.height(10.dp))
                        DjArc.entries.chunked(if (wide) 4 else 2).forEach { row ->
                            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                row.forEach { a ->
                                    DjArcCard(a, a == arc, color, Modifier.weight(1f)) {
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        arc = a
                                    }
                                }
                                repeat((if (wide) 4 else 2) - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }

                        Spacer(Modifier.height(12.dp))
                        IndexLabel(stringResource(Res.string.shell_dj_tuning))
                        Spacer(Modifier.height(10.dp))
                        DjTuning(vibe, color) { vibe = it }

                        Spacer(Modifier.height(22.dp))
                        IndexLabel(stringResource(Res.string.shell_dj_length))
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DjLength.entries.forEach { l ->
                                val label = when (l) {
                                    DjLength.M30 -> stringResource(Res.string.shell_dj_length_minutes, 30)
                                    DjLength.M60 -> stringResource(Res.string.shell_dj_length_hour)
                                    DjLength.M120 -> stringResource(Res.string.shell_dj_length_hours, 2)
                                    DjLength.Endless -> stringResource(Res.string.shell_dj_length_endless)
                                }
                                DjChip(label, l == length, color) {
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    length = l
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(ShellDj.failed) {
                    Text(stringResource(Res.string.shell_dj_nothing), style = Shell.type.bodySmall, color = c.danger, modifier = Modifier.padding(top = 16.dp))
                }
                Spacer(Modifier.height(24.dp))
                val start = stringResource(Res.string.shell_dj_start) + " · " + stringResource(preset.title)
                Box(
                    Modifier.fillMaxWidth().height(64.dp).shadow(18.dp, CircleShape, ambientColor = color, spotColor = color).clip(CircleShape).background(color)
                        .shellPressable({
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            ShellDj.start(controller, DjEngine.genreIds.filter { it in picks }, arc, length, vibe, mode, queueTitle)
                        }, label = start, focusShape = CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Glyph(Glyphs.Turntable, c.onAccent, 24.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(start, style = Shell.type.body.copy(fontWeight = FontWeight.Bold, fontSize = 18.sp), color = c.onAccent)
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

/** The vibe tuning sliders, shared by setup and the booth. */
@Composable
internal fun DjTuning(vibe: VibeSettings, color: Color, showFresh: Boolean = true, onRelease: () -> Unit = {}, onChange: (VibeSettings) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        DjVibeSlider(
            stringResource(Res.string.shell_dj_flair), stringResource(Res.string.shell_dj_flair_low), stringResource(Res.string.shell_dj_flair_high),
            vibe.flair, color, onChange = { onChange(vibe.copy(flair = it)) }, onRelease = onRelease,
        )
        DjVibeSlider(
            stringResource(Res.string.shell_dj_pace), stringResource(Res.string.shell_dj_pace_low), stringResource(Res.string.shell_dj_pace_high),
            vibe.pace, color, onChange = { onChange(vibe.copy(pace = it)) }, onRelease = onRelease,
        )
        DjVibeSlider(
            stringResource(Res.string.shell_dj_variety), stringResource(Res.string.shell_dj_variety_low), stringResource(Res.string.shell_dj_variety_high),
            vibe.variety, color, onChange = { onChange(vibe.copy(variety = it)) }, onRelease = onRelease,
        )
        if (showFresh) {
            DjVibeSlider(
                stringResource(Res.string.shell_dj_vibe_fresh), stringResource(Res.string.shell_dj_vibe_favourites), stringResource(Res.string.shell_dj_vibe_fresh),
                vibe.fresh, color, onChange = { onChange(vibe.copy(fresh = it)) }, onRelease = onRelease,
            )
        }
    }
}

/** "Start my mix": the one-tap way in, from the listener's own taste. */
@Composable
private fun DjYoursCard(onStart: () -> Unit) {
    val c = Shell.colors
    val label = stringResource(Res.string.shell_dj_yours_title)
    Row(
        Modifier.fillMaxWidth().clip(ShellShapes.Card).background(c.surface1)
            .drawBehind {
                drawRect(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.22f), Color(0xFFFF4FA3).copy(alpha = 0.10f), Color.Transparent)))
            }
            .border(1.dp, c.accent.copy(alpha = 0.5f), ShellShapes.Card)
            .shellPressable(onStart, label = label, focusShape = ShellShapes.Card)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DjVinyl(null, spinning = true, size = 64.dp, ringColor = c.accent)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Shell.type.headline.copy(fontSize = 22.sp), color = c.ink)
            Text(stringResource(Res.string.shell_dj_yours_body), style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.size(52.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
            Glyph(Glyphs.Play, c.onAccent, 24.dp)
        }
    }
}

@Composable
private fun DjPresetCard(p: DjPreset, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Shell.colors
    val border by animateColorAsState(if (selected) p.color else c.hairline, ShellMotion.emphasized())
    val name = stringResource(p.title)
    Column(
        modifier.clip(ShellShapes.Card).background(if (selected) p.color.copy(alpha = 0.12f) else c.surface1)
            .border(if (selected) 2.dp else 1.dp, border, ShellShapes.Card)
            .shellPressable(onClick, label = name, focusShape = ShellShapes.Card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DjArcSpark(p.arc, p.color, selected, Modifier.weight(1f).height(34.dp))
            if (selected) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(22.dp).clip(CircleShape).background(p.color), contentAlignment = Alignment.Center) { Glyph(Glyphs.Check, c.onAccent, 12.dp) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(name, style = Shell.type.title.copy(fontSize = 16.sp, lineHeight = 20.sp), color = c.ink)
        Text(stringResource(p.body), style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(p.genres.take(3).map { djGenreName(it) }.joinToString(" · "), style = Shell.type.bodySmall.copy(fontSize = 11.sp), color = if (selected) p.color else c.index, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DjArcSpark(arc: DjArc, color: Color, selected: Boolean, modifier: Modifier) {
    Canvas(modifier) {
        val path = Path()
        val n = 40
        for (i in 0..n) {
            val x = size.width * i / n
            val y = size.height - arc.energyAt(i.toDouble() / n).toFloat() * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply {
            addPath(path)
            lineTo(size.width, size.height)
            lineTo(0f, size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = if (selected) 0.35f else 0.15f), Color.Transparent)))
        drawPath(path, if (selected) color else color.copy(alpha = 0.6f), style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
    }
}

@Composable
private fun DjChip(label: String, on: Boolean, vibe: Color, onClick: () -> Unit) {
    val c = Shell.colors
    val bg by animateColorAsState(if (on) vibe else c.surface2, ShellMotion.emphasized())
    val fg by animateColorAsState(if (on) c.onAccent else c.ink, ShellMotion.emphasized())
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(bg).border(1.dp, if (on) Color.Transparent else c.hairline, CircleShape)
            .shellPressable(onClick, label = label, focusShape = CircleShape).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(on) {
            Row {
                Glyph(Glyphs.Check, fg, 14.dp)
                Spacer(Modifier.size(6.dp))
            }
        }
        Text(label, style = Shell.type.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = fg)
    }
}

/** An arc to pick: its energy curve, the name and what the night feels like. */
@Composable
private fun DjArcCard(arc: DjArc, selected: Boolean, color: Color, modifier: Modifier, onClick: () -> Unit) {
    val c = Shell.colors
    val border by animateColorAsState(if (selected) color else c.hairline, ShellMotion.emphasized())
    val name = djArcName(arc)
    Column(
        modifier.clip(ShellShapes.Card).background(if (selected) color.copy(alpha = 0.10f) else c.surface1)
            .border(if (selected) 2.dp else 1.dp, border, ShellShapes.Card)
            .shellPressable(onClick, label = name, focusShape = ShellShapes.Card)
            .padding(14.dp),
    ) {
        DjArcSpark(arc, color, selected, Modifier.fillMaxWidth().height(40.dp))
        Spacer(Modifier.height(10.dp))
        Text(name, style = Shell.type.title.copy(fontSize = 15.sp, lineHeight = 19.sp), color = c.ink)
        Text(djArcBody(arc), style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

/** Home's way into Turtle DJ: a spinning record when a set is live, the pitch when not. */
@Composable
internal fun TurtleDjCard(wide: Boolean, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    val controller = LocalPlaybackController.current
    val calm by controller.rememberCalmState()
    val s = ShellDj.session
    val live = s != null && !s.loading
    val vibe = s?.arc?.vibe() ?: c.accent
    val current = s?.current?.track?.song
    val action = stringResource(if (live) Res.string.shell_dj_card_back else Res.string.shell_dj_card_open)
    Row(
        modifier.padding(16.dp, 12.dp, 16.dp, 0.dp).fillMaxWidth()
            .clip(if (desktop) RoundedCornerShape(8.dp) else ShellShapes.Card)
            .background(if (desktop) c.surface2 else c.surface1)
            .drawBehind {
                drawRect(Brush.radialGradient(listOf(vibe.copy(alpha = if (live) 0.22f else 0.12f), Color.Transparent), Offset(0f, size.height / 2), size.height * 1.6f))
            }
            .shellPressable({ ShellDj.open = true }, label = action)
            .padding(if (desktop) 24.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (desktop) 24.dp else 14.dp),
    ) {
        DjVinyl(current?.displayThumbnailUrl, spinning = live && calm.isPlaying, size = if (wide) 124.dp else 80.dp, ringColor = vibe)
        Column(Modifier.weight(1f)) {
            IndexLabel(stringResource(if (live) Res.string.shell_dj_card_live else Res.string.shell_dj_label), color = if (live) vibe else c.index)
            Text(
                stringResource(Res.string.shell_dj_card_tagline),
                style = if (wide) Shell.type.display.copy(fontSize = Shell.type.display.fontSize * 1.2f, lineHeight = Shell.type.display.lineHeight * 1.2f) else Shell.type.headline,
                color = c.ink,
            )
            Text(
                if (live && current != null) current.title + " · " + current.artists.joinToString(", ") { it.name } else stringResource(Res.string.shell_dj_card_body),
                style = Shell.type.bodySmall, color = if (live) vibe else c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(action, style = Shell.type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.padding(top = 6.dp))
        }
        Box(
            Modifier.size(if (wide) 64.dp else 52.dp).clip(CircleShape).background(vibe)
                .shellPressable({ ShellDj.open = true }, label = action, focusShape = CircleShape),
            contentAlignment = Alignment.Center,
        ) { Glyph(Glyphs.Turntable, c.onAccent, if (wide) 28.dp else 24.dp) }
    }
}
