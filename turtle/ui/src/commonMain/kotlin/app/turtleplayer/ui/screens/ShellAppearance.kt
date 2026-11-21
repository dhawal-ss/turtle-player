package app.turtleplayer.ui.screens

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_theme_body
import app.turtleplayer.ui.generated.resources.shell_theme_title
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPalette
import app.turtleplayer.ui.theme.ShellPalettes
import app.turtleplayer.ui.theme.ShellTheme
import org.jetbrains.compose.resources.stringResource

/** Whether the theme sheet is open (opened from Home's palette button). */
internal object ShellAppearance {
    var open by mutableStateOf(false)
}

/** Spicetify-style colour schemes: pick one and the whole app re-skins at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShellThemeSheet() = ShellTheme {
    val c = Shell.colors
    val close = { ShellAppearance.open = false }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            ShellBottomSheet(visible = true, onDismiss = close) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    Text(stringResource(Res.string.shell_theme_title), style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.85f), color = c.ink)
                    Text(stringResource(Res.string.shell_theme_body), style = Shell.type.bodySmall, color = c.ink2)
                    Spacer(Modifier.height(16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                        ShellPalettes.all.forEach { p -> Swatch(p, p.id == ShellPalettes.selected.id) { ShellPalettes.choose(p) } }
                    }
                }
            }
        }
    }
}

/** A palette preview: its ground and surfaces, an ink line and its accent. */
@Composable
internal fun Swatch(p: ShellPalette, selected: Boolean, onClick: () -> Unit) {
    val c = Shell.colors
    val k = p.colors
    val ring by animateDpAsState(if (selected) 2.dp else 1.dp, ShellMotion.emphasized())
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier.width(104.dp).clip(shape).border(ring, if (selected) c.accent else c.hairline, shape)
            .shellPressable(onClick, label = p.name, focusShape = shape),
    ) {
        Box(Modifier.fillMaxWidth().height(64.dp).background(k.ground).padding(8.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.width(56.dp).height(6.dp).clip(CircleShape).background(k.ink))
                Box(Modifier.width(38.dp).height(6.dp).clip(CircleShape).background(k.ink2))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(k.surface2))
                    Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(k.surface3))
                }
            }
            val accent = if (p.dynamic) Brush.sweepGradient(listOf(Color(0xFFFF6B6B), Color(0xFFFFD166), Color(0xFF06D6A0), Color(0xFF4CC9F0), Color(0xFFB388FF), Color(0xFFFF6B6B))) else Brush.linearGradient(listOf(k.accent, k.accent))
            Box(Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(accent), contentAlignment = Alignment.Center) {
                if (selected) Glyph(Glyphs.Check, if (p.dynamic) Color.White else k.onAccent, 12.dp)
            }
        }
        Text(
            p.name, style = Shell.type.bodySmall.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) c.ink else c.ink2, maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
}
