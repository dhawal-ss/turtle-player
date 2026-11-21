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
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_dj_arc
import app.turtleplayer.ui.generated.resources.shell_dj_arc_club
import app.turtleplayer.ui.generated.resources.shell_dj_arc_club_body
import app.turtleplayer.ui.generated.resources.shell_dj_arc_house
import app.turtleplayer.ui.generated.resources.shell_dj_arc_house_body
import app.turtleplayer.ui.generated.resources.shell_dj_arc_lounge
import app.turtleplayer.ui.generated.resources.shell_dj_arc_lounge_body
import app.turtleplayer.ui.generated.resources.shell_dj_arc_sunrise
import app.turtleplayer.ui.generated.resources.shell_dj_arc_sunrise_body
import app.turtleplayer.ui.generated.resources.shell_dj_card_back
import app.turtleplayer.ui.generated.resources.shell_dj_card_body
import app.turtleplayer.ui.generated.resources.shell_dj_card_live
import app.turtleplayer.ui.generated.resources.shell_dj_card_open
import app.turtleplayer.ui.generated.resources.shell_dj_card_tagline
import app.turtleplayer.ui.generated.resources.shell_dj_exit
import app.turtleplayer.ui.generated.resources.shell_dj_genre_amapiano
import app.turtleplayer.ui.generated.resources.shell_dj_genre_dancehall
import app.turtleplayer.ui.generated.resources.shell_dj_genre_disco
import app.turtleplayer.ui.generated.resources.shell_dj_genre_dnb
import app.turtleplayer.ui.generated.resources.shell_dj_genre_edm
import app.turtleplayer.ui.generated.resources.shell_dj_genre_trance
import app.turtleplayer.ui.generated.resources.shell_dj_label
import app.turtleplayer.ui.generated.resources.shell_dj_length
import app.turtleplayer.ui.generated.resources.shell_dj_length_endless
import app.turtleplayer.ui.generated.resources.shell_dj_length_hour
import app.turtleplayer.ui.generated.resources.shell_dj_length_hours
import app.turtleplayer.ui.generated.resources.shell_dj_length_minutes
import app.turtleplayer.ui.generated.resources.shell_dj_nothing
import app.turtleplayer.ui.generated.resources.shell_dj_queue_title
import app.turtleplayer.ui.generated.resources.shell_dj_setup_body
import app.turtleplayer.ui.generated.resources.shell_dj_setup_title
import app.turtleplayer.ui.generated.resources.shell_dj_start
import app.turtleplayer.ui.generated.resources.shell_dj_vibe
import app.turtleplayer.ui.generated.resources.shell_dj_vibe_hint
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.ui.playback.LocalPlaybackController
import org.jetbrains.compose.resources.stringResource

/** Display name for a DJ genre: our club extras, then the shared genre and mood names. */
@Composable
internal fun djGenreName(id: String): String = when (id) {
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
        DjArc.Sunrise -> Res.string.shell_dj_arc_sunrise
    },
)

@Composable
private fun djArcBody(arc: DjArc): String = stringResource(
    when (arc) {
        DjArc.Lounge -> Res.string.shell_dj_arc_lounge_body
        DjArc.HouseParty -> Res.string.shell_dj_arc_house_body
        DjArc.ClubPeak -> Res.string.shell_dj_arc_club_body
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

/** Setup: vibe chips, the energy arc, the set's length, Start. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DjSetup(onClose: () -> Unit) {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val haptics = LocalHapticFeedback.current
    var picks by remember { mutableStateOf(emptyList<String>()) }
    var arc by remember { mutableStateOf(DjArc.HouseParty) }
    var length by remember { mutableStateOf(DjLength.M60) }
    val queueTitle = stringResource(Res.string.shell_dj_queue_title)
    val vibe = arc.vibe()
    val glow by animateColorAsState(vibe.copy(alpha = 0.18f), tween(ShellMotion.TINT_MS))
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
                    IndexLabel(stringResource(Res.string.shell_dj_label), color = vibe)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.shell_dj_setup_title),
                    style = if (wide) Shell.type.display.copy(fontSize = 44.sp, lineHeight = 46.sp) else Shell.type.display,
                    color = c.ink,
                )
                Spacer(Modifier.height(8.dp))
                Text(stringResource(Res.string.shell_dj_setup_body), style = Shell.type.body, color = c.ink2)

                Spacer(Modifier.height(24.dp))
                IndexLabel(stringResource(Res.string.shell_dj_vibe))
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DjEngine.genreIds.forEach { id ->
                        val on = id in picks
                        DjChip(djGenreName(id), on, vibe) {
                            haptics.performHapticFeedback(if (on) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                            picks = if (on) picks - id else picks + id
                        }
                    }
                }
                AnimatedVisibility(picks.isEmpty()) {
                    Text(stringResource(Res.string.shell_dj_vibe_hint), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp))
                }

                Spacer(Modifier.height(24.dp))
                IndexLabel(stringResource(Res.string.shell_dj_arc))
                Spacer(Modifier.height(10.dp))
                val arcs = DjArc.entries
                arcs.chunked(if (wide) 4 else 2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { a ->
                            DjArcCard(a, a == arc, Modifier.weight(1f)) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                arc = a
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))
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
                        DjChip(label, l == length, vibe) {
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            length = l
                        }
                    }
                }

                AnimatedVisibility(ShellDj.failed) {
                    Text(stringResource(Res.string.shell_dj_nothing), style = Shell.type.bodySmall, color = c.danger, modifier = Modifier.padding(top = 16.dp))
                }
                Spacer(Modifier.height(28.dp))
                val start = stringResource(Res.string.shell_dj_start)
                Box(
                    Modifier.fillMaxWidth().height(64.dp).shadow(18.dp, CircleShape, ambientColor = vibe, spotColor = vibe).clip(CircleShape).background(vibe)
                        .shellPressable({
                            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                            ShellDj.start(controller, DjEngine.genreIds.filter { it in picks }, arc, length, queueTitle)
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

/** An arc to pick: its energy curve drawn in its own light, the name and what the night feels like. */
@Composable
private fun DjArcCard(arc: DjArc, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Shell.colors
    val color = arc.vibe()
    val border by animateColorAsState(if (selected) color else c.hairline, ShellMotion.emphasized())
    val name = djArcName(arc)
    Column(
        modifier.clip(ShellShapes.Card).background(if (selected) color.copy(alpha = 0.10f) else c.surface1)
            .border(if (selected) 2.dp else 1.dp, border, ShellShapes.Card)
            .shellPressable(onClick, label = name, focusShape = ShellShapes.Card)
            .padding(14.dp),
    ) {
        Canvas(Modifier.fillMaxWidth().height(44.dp)) {
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
