package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.turtle.NavigationSlot
import com.metrolist.shared.turtle.NavigationSlotItem

/** Our glyph for well-known destinations; upstream's own icon for everything else. */
private fun glyphFor(item: NavigationSlotItem, selected: Boolean): ImageVector {
    val key = item.key.substringAfterLast('.').lowercase()
    return when {
        key.startsWith("home") -> if (selected) Glyphs.HomeFilled else Glyphs.Home
        key.startsWith("search") -> Glyphs.Search
        key.startsWith("library") -> Glyphs.Library
        key.startsWith("explore") -> Glyphs.Explore
        else -> item.icon
    }
}

/**
 * Shell's top-level navigation: a Spotify-style tab bar on phones, a calm rail on desktop and
 * tablets. Upstream's header and footer (desktop sidebar extras) are kept in the rail.
 */
@Composable
fun ShellNavigation(slot: NavigationSlot) = ShellTheme {
    if (slot.bottomBar) BottomTabs(slot) else Rail(slot)
}

@Composable
private fun BottomTabs(slot: NavigationSlot) {
    val c = Shell.colors
    val density = LocalDensity.current
    LaunchedEffect(slot.navigationVisible) { if (!slot.navigationVisible) slot.onOccupiedHeightChanged(0.dp) }
    val bar: @Composable (Modifier) -> Unit = { modifier ->
        AnimatedVisibility(
            slot.navigationVisible,
            enter = slideInVertically(ShellMotion.emphasized()) { it } + fadeIn(ShellMotion.emphasized()),
            exit = slideOutVertically(ShellMotion.exit()) { it } + fadeOut(ShellMotion.exit()),
            modifier = modifier,
        ) {
            Row(
                Modifier.fillMaxWidth().background(c.ground.copy(alpha = 0.96f)).windowInsetsPadding(WindowInsets.navigationBars)
                    .height(64.dp).onSizeChanged { with(density) { slot.onOccupiedHeightChanged(it.height.toDp()) } },
            ) {
                slot.items.forEachIndexed { i, item ->
                    val selected = i == slot.selectedIndex
                    val tint by animateColorAsState(if (selected) c.ink else c.ink2, ShellMotion.emphasized())
                    val pop by animateFloatAsState(if (selected) 1f else 0.94f, ShellMotion.spring())
                    Column(
                        Modifier.weight(1f).fillMaxHeight().shellPressable({ slot.onSelect(i) }, Role.Tab, item.label),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Glyph(glyphFor(item, selected), tint, 24.dp, Modifier.scale(pop))
                        Spacer(Modifier.height(4.dp))
                        Text(item.label, style = Shell.type.bodySmall.copy(fontSize = Shell.type.index.fontSize), color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (slot.floating) {
        Box(slot.modifier.fillMaxSize()) {
            slot.content()
            bar(Modifier.align(Alignment.BottomCenter))
        }
    } else {
        Column(slot.modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { slot.content() }
            bar(Modifier)
        }
    }
}

@Composable
private fun Rail(slot: NavigationSlot) {
    val c = Shell.colors
    Row(slot.modifier.fillMaxSize()) {
        if (slot.navigationVisible) {
            Column(Modifier.width(92.dp).fillMaxHeight().background(c.ground).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                slot.header()
                Spacer(Modifier.height(8.dp))
                slot.items.forEachIndexed { i, item ->
                    val selected = i == slot.selectedIndex
                    val bg by animateColorAsState(if (selected) c.accent else c.ground, ShellMotion.emphasized())
                    val fg by animateColorAsState(if (selected) c.onAccent else c.ink2, ShellMotion.emphasized())
                    Column(
                        Modifier.padding(vertical = 4.dp).size(72.dp, 64.dp).clip(RoundedCornerShape(18.dp)).background(bg).shellPressable({ slot.onSelect(i) }, Role.Tab, item.label),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Glyph(glyphFor(item, selected), fg, 22.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(item.label, style = Shell.type.index.copy(letterSpacing = Shell.type.bodySmall.letterSpacing), color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Spacer(Modifier.weight(1f))
                slot.footer?.invoke()
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) { slot.content() }
    }
}
