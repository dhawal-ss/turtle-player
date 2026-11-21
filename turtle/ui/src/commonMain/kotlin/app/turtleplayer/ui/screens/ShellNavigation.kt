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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
import com.metrolist.shared.turtle.TurtleUiSlots

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
            // Report the full height, system navigation bar included, so the mini player sits above the tabs.
            Row(
                Modifier.fillMaxWidth().onSizeChanged { with(density) { slot.onOccupiedHeightChanged(it.height.toDp()) } }
                    // Content fades out behind the mini player above the tabs, as in Spotify.
                    .drawBehind {
                        val fade = 88.dp.toPx()
                        drawRect(
                            Brush.verticalGradient(0f to Color.Transparent, 1f to c.ground.copy(alpha = 0.96f), startY = -fade, endY = 0f),
                            topLeft = Offset(0f, -fade), size = Size(size.width, fade),
                        )
                    }
                    .background(c.ground.copy(alpha = 0.96f)).windowInsetsPadding(WindowInsets.navigationBars).height(68.dp),
            ) {
                slot.items.forEachIndexed { i, item ->
                    val selected = i == slot.selectedIndex
                    val tint by animateColorAsState(if (selected) c.ink else c.ink2, ShellMotion.emphasized())
                    val pop by animateFloatAsState(if (selected) 1f else 0.94f, ShellMotion.spring())
                    // A soft pill grows behind the selected glyph (H-Motion 07); lime stays for "yours / now".
                    val pill by animateFloatAsState(if (selected) 1f else 0f, ShellMotion.emphasized())
                    Column(
                        Modifier.weight(1f).fillMaxHeight().then(slot.itemModifier(i)).shellPressable({ slot.onSelect(i) }, Role.Tab, item.label),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(56.dp, 32.dp).graphicsLayer { scaleX = 0.6f + 0.4f * pill; alpha = pill }.clip(CircleShape).background(c.surface3))
                            Glyph(glyphFor(item, selected), tint, 24.dp, Modifier.scale(pop))
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            item.label, style = Shell.type.bodySmall.copy(fontSize = 11.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                            color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
    if (slot.floating) {
        Box(slot.modifier.fillMaxSize()) {
            SlotContent(slot)
            bar(Modifier.align(Alignment.BottomCenter))
        }
    } else {
        Column(slot.modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { SlotContent(slot) }
            bar(Modifier)
        }
    }
}

@Composable
private fun Rail(slot: NavigationSlot) {
    TurtleUiSlots.desktopLibrary?.let { return ShellDesktopLayout(slot, it) }
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
                        Modifier.padding(vertical = 4.dp).size(72.dp, 64.dp).clip(RoundedCornerShape(18.dp)).background(bg).then(slot.itemModifier(i)).shellPressable({ slot.onSelect(i) }, Role.Tab, item.label),
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
        Box(Modifier.weight(1f).fillMaxHeight()) { SlotContent(slot) }
    }
}

/**
 * Upstream pages read their text colour from [LocalContentColor]; upstream's own scaffold provides
 * it, so our takeover must too or their titles render black on the dark ground.
 */
@Composable
internal fun SlotContent(slot: NavigationSlot) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.material3.LocalContentColor provides androidx.compose.material3.MaterialTheme.colorScheme.onSurface,
        content = slot.content,
    )
}
