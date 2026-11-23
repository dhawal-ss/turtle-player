package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_set_explicit_badge
import app.turtleplayer.ui.generated.resources.shell_set_selected
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.turtle.MenuActionsSlot
import com.metrolist.shared.turtle.MenuHeaderSlot
import com.metrolist.shared.turtle.MenuSurfaceSlot
import com.metrolist.shared.ui.components.AutoPlaylistThumbnail
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.components.isAutoPlaylistIcon
import com.metrolist.shared.ui.menu.MediaMenuHeaderAction
import com.metrolist.shared.ui.navigation.navigationPopupSource
import org.jetbrains.compose.resources.stringResource
import com.metrolist.shared.generated.resources.Res as SharedRes

/*
 * Shell's look for every upstream media menu (turtle/HOOKS.md, menus): the phone sheets for songs,
 * albums, artists, playlists, podcasts, the queue, the player and lyrics, and the desktop and cursor
 * context menus. Upstream still builds the menus (which actions, their order and callbacks, nested
 * popups, dismissal); Shell draws the header, the primary action tiles, the action rows and the desktop
 * menu panel, so every action keeps working.
 */

/** The desktop menu panel: Spotify's raised dark card with a hairline, scrolling when it is tall. */
@Composable
fun ShellMenuSurface(slot: MenuSurfaceSlot) = ShellTheme {
    val c = Shell.colors
    val shape = RoundedCornerShape(10.dp)
    Column(
        slot.modifier.shadow(18.dp, shape).clip(shape).background(c.surface2).border(1.dp, c.hairline, shape)
            .widthIn(min = 200.dp, max = 400.dp).heightIn(max = slot.maxHeight).verticalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
        content = slot.content,
    )
}

/** A menu's header: artwork, title and subtitle (with upstream's artist and album links), round actions. */
@Composable
fun ShellMenuHeader(slot: MenuHeaderSlot) = ShellTheme {
    val c = Shell.colors
    val popup = slot.popup
    val artist = slot.artistId != null
    val artSize = when {
        popup -> 44.dp
        artist -> 64.dp
        else -> 56.dp
    }
    val artShape: Shape = if (artist) CircleShape else RoundedCornerShape(if (popup) 4.dp else 6.dp)
    Column(slot.modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (popup) 4.dp else 20.dp, vertical = if (popup) 8.dp else 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MenuArtwork(slot.thumbnail, slot.fallbackIcon, slot.thumbnailAspectRatio, artSize, artShape)
            Spacer(Modifier.width(if (popup) 12.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        slot.title,
                        style = if (artist && !popup) {
                            Shell.type.headline.copy(fontSize = 20.sp, lineHeight = 24.sp)
                        } else {
                            Shell.type.body.copy(fontWeight = FontWeight.Bold, fontSize = if (popup) 14.sp else 16.sp)
                        },
                        color = c.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (slot.isExplicit && slot.subtitle.isNullOrBlank()) {
                        Spacer(Modifier.width(6.dp))
                        ExplicitTag()
                    }
                }
                slot.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (slot.isExplicit) {
                            ExplicitTag()
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            slot.subtitleLinks ?: AnnotatedString(subtitle),
                            style = Shell.type.bodySmall.copy(fontSize = if (popup) 12.sp else 13.sp),
                            color = c.ink2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                }
            }
            if (slot.actions.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    slot.actions.forEach { HeaderActionButton(it, if (popup) 36.dp else 44.dp) }
                }
            }
        }
        Box(
            Modifier.fillMaxWidth().padding(horizontal = if (popup) 4.dp else 20.dp).padding(top = if (popup) 2.dp else 12.dp)
                .height(1.dp).background(c.hairline),
        )
        HomeNotInterestedRow(slot.title, slot.popup)
        if (!popup) Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ExplicitTag() {
    ShellExplicitTag(stringResource(Res.string.shell_set_explicit_badge), stringResource(SharedRes.string.explicit))
}

@Composable
private fun MenuArtwork(thumbnail: String?, fallbackIcon: ImageVector?, aspectRatio: Float, size: Dp, shape: Shape) {
    val c = Shell.colors
    val wide = aspectRatio > 1f
    val box = if (wide) Modifier.width(size).height(size / aspectRatio) else Modifier.size(size)
    when {
        thumbnail.isAutoPlaylistIcon() -> Box(Modifier.size(size).clip(shape)) { AutoPlaylistThumbnail(thumbnail, Modifier.size(size), shape) }
        thumbnail != null -> ShellArt(thumbnail, box, shape = shape)
        fallbackIcon != null -> {
            Box(Modifier.size(size).clip(shape).background(c.surface3), contentAlignment = Alignment.Center) {
                Icon(fallbackIcon, contentDescription = null, tint = c.ink2, modifier = Modifier.size(size * 0.45f))
            }
        }
    }
}

/** A round header action (like, save, play): lime when it is on or primary. */
@Composable
private fun HeaderActionButton(action: MediaMenuHeaderAction, size: Dp) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val on = action.isToggle && action.isChecked
    val bg by animateColorAsState(
        when {
            action.isPrimary -> c.accent
            hovered -> c.surface3
            else -> if (ShellPlatform.desktop) c.surface3.copy(alpha = 0.6f) else c.surface2
        },
        ShellMotion.emphasized(),
    )
    val tint = when {
        action.isPrimary -> c.onAccent
        on -> action.tint ?: c.accent
        else -> c.ink
    }
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).hoverable(source)
            .shellPressable(action.onClick, role = if (action.isToggle) Role.Switch else Role.Button, label = action.contentDescription, focusShape = CircleShape)
            .then(if (action.isToggle) Modifier.semantics { toggleableState = ToggleableState(action.isChecked) } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (action.isLoading) {
            CircularProgressIndicator(Modifier.size(size * 0.45f), color = tint, strokeWidth = 2.dp)
        } else {
            Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/**
 * The primary actions (play, shuffle, share, play next...): Shell tiles in a row. Context menus show
 * three tiles and list any others as rows, so no action is dropped.
 */
@Composable
fun ShellMenuPrimaryActions(slot: MenuActionsSlot) = ShellTheme {
    val actions = slot.actions
    if (actions.isEmpty()) return@ShellTheme
    val tiles = if (slot.popup) actions.take(3) else actions
    val rest = if (slot.popup) actions.drop(3) else emptyList()
    Column(slot.modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (slot.popup) 4.dp else 0.dp, vertical = if (slot.popup) 6.dp else 0.dp),
            horizontalArrangement = Arrangement.spacedBy(if (slot.popup) 6.dp else 8.dp),
        ) {
            tiles.forEach { action -> PrimaryTile(action, Modifier.weight(1f), compact = slot.popup) }
        }
        if (rest.isNotEmpty()) MenuRows(rest, popup = true)
    }
}

@Composable
private fun PrimaryTile(action: PlayerMenuAction, modifier: Modifier, compact: Boolean) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(12.dp)
    val selected = action.selected == true
    val bg by animateColorAsState(
        when {
            selected -> c.accentContainer
            hovered -> c.surface3
            else -> if (compact) c.ink.copy(alpha = 0.05f) else c.surface2
        },
        ShellMotion.emphasized(),
    )
    val tint = when {
        !action.enabled -> c.ink2.copy(alpha = 0.5f)
        selected -> c.accent
        else -> c.ink
    }
    Column(
        modifier.height(if (compact) 64.dp else 76.dp).navigationPopupSource(shape).clip(shape).background(bg).hoverable(source)
            .then(if (action.enabled) Modifier.shellPressable(action.onClick, label = action.title, onLongClick = action.onLongClick, focusShape = shape) else Modifier)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ActionIcon(action, tint, if (compact) 20.dp else 24.dp)
        Spacer(Modifier.height(if (compact) 6.dp else 8.dp))
        Text(
            action.title,
            style = Shell.type.label.copy(fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.SemiBold),
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A group of actions. Phone sheets draw it as a rounded Shell card with hairlines between rows; context
 * menus draw flat rows, with a hairline before every group after the first.
 */
@Composable
fun ShellMenuActionGroup(slot: MenuActionsSlot) = ShellTheme {
    val c = Shell.colors
    if (slot.actions.isEmpty()) return@ShellTheme
    if (slot.popup) {
        Column(slot.modifier.fillMaxWidth()) {
            if (slot.groupIndex > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp).height(1.dp).background(c.hairline))
            MenuRows(slot.actions, popup = true)
        }
    } else {
        val shape = RoundedCornerShape(14.dp)
        Column(slot.modifier.fillMaxWidth().clip(shape).background(c.surface2)) {
            MenuRows(slot.actions, popup = false)
        }
    }
}

@Composable
private fun MenuRows(actions: List<PlayerMenuAction>, popup: Boolean) {
    val c = Shell.colors
    actions.forEachIndexed { index, action ->
        if (!popup && index > 0) Box(Modifier.fillMaxWidth().padding(start = 54.dp).height(1.dp).background(c.hairlineSoft))
        MenuRow(action, popup)
    }
}

@Composable
private fun MenuRow(action: PlayerMenuAction, popup: Boolean) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(if (popup) 6.dp else 0.dp)
    val bg by animateColorAsState(if (hovered && action.enabled) c.ink.copy(alpha = 0.07f) else Color.Transparent, ShellMotion.emphasized())
    val selected = action.selected == true
    val tint = when {
        !action.enabled -> c.ink2.copy(alpha = 0.5f)
        selected -> c.accent
        else -> c.ink
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = if (popup) 4.dp else 0.dp)
            .heightIn(min = if (popup) 40.dp else 54.dp).navigationPopupSource(shape).clip(shape).background(bg).hoverable(source)
            .then(
                if (action.enabled) {
                    Modifier.shellPressable(
                        action.onClick,
                        role = if (action.selected != null) Role.Switch else Role.Button,
                        onLongClick = action.onLongClick,
                        focusShape = shape,
                    )
                } else {
                    Modifier
                },
            )
            .then(if (action.selected != null) Modifier.semantics { toggleableState = ToggleableState(selected) } else Modifier)
            .graphicsLayer { alpha = if (action.enabled) 1f else 0.6f }
            .padding(horizontal = if (popup) 8.dp else 16.dp, vertical = if (popup) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionIcon(action, if (selected) c.accent else if (action.enabled) c.ink2 else c.ink2.copy(alpha = 0.5f), if (popup) 18.dp else 22.dp)
        Spacer(Modifier.width(if (popup) 12.dp else 16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                action.title,
                style = if (popup) Shell.type.label.copy(fontSize = 14.sp) else Shell.type.body,
                color = tint,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            action.description?.takeIf { it.isNotBlank() }?.let {
                Spacer(Modifier.height(1.dp))
                Text(
                    it,
                    style = Shell.type.bodySmall.copy(fontSize = if (popup) 12.sp else 13.sp),
                    color = c.ink2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.Check, c.accent, if (popup) 14.dp else 16.dp, contentDescription = stringResource(Res.string.shell_set_selected))
        }
    }
}

@Composable
private fun ActionIcon(action: PlayerMenuAction, tint: Color, size: Dp) {
    if (action.isLoading) {
        CircularProgressIndicator(Modifier.size(size * 0.9f), color = Shell.colors.accent, strokeWidth = 2.dp)
    } else {
        Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(size))
    }
}
