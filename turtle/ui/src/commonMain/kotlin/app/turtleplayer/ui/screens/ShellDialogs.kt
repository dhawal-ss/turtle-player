package app.turtleplayer.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.turtle.DialogSlot

/**
 * Every upstream dialog as Shell's card (turtle/HOOKS.md #40): a raised panel with a hairline, the
 * title in Space Grotesk, body in Inter, and upstream's own actions bottom-right. It springs in from
 * 0.96 (H-Motion), so confirmations feel like the rest of the app.
 */
@Composable
fun ShellDialog(slot: DialogSlot) = ShellTheme {
    val c = Shell.colors
    Dialog(onDismissRequest = slot.onDismissRequest, properties = slot.properties) {
        val enter = remember { Animatable(0f) }
        LaunchedEffect(Unit) { enter.animateTo(1f, ShellMotion.spring()) }
        Column(
            Modifier.widthIn(min = 280.dp, max = 460.dp).fillMaxWidth()
                .graphicsLayer {
                    val s = 0.96f + 0.04f * enter.value
                    scaleX = s
                    scaleY = s
                    alpha = enter.value.coerceIn(0f, 1f)
                }
                .clip(RoundedCornerShape(16.dp)).background(c.surface2).border(1.dp, c.hairline, RoundedCornerShape(16.dp))
                .padding(start = 24.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
        ) {
            slot.icon?.let { icon ->
                CompositionLocalProvider(LocalContentColor provides c.accent) { Box(Modifier.padding(bottom = 12.dp, end = 8.dp)) { icon() } }
            }
            slot.title?.let { title ->
                CompositionLocalProvider(LocalContentColor provides c.ink, LocalTextStyle provides Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.9f)) {
                    Box(Modifier.padding(end = 8.dp)) { title() }
                }
                Spacer(Modifier.height(10.dp))
            }
            slot.text?.let { text ->
                CompositionLocalProvider(LocalContentColor provides c.ink2, LocalTextStyle provides Shell.type.body) {
                    Box(Modifier.padding(end = 8.dp)) { text() }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
                slot.dismissButton?.invoke()
                slot.confirmButton()
            }
        }
    }
}
