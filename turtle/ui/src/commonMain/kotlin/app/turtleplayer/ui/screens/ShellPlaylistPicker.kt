package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_pick_add
import app.turtleplayer.ui.generated.resources.shell_pick_added
import app.turtleplayer.ui.generated.resources.shell_pick_already
import app.turtleplayer.ui.generated.resources.shell_pick_count
import app.turtleplayer.ui.generated.resources.shell_pick_create
import app.turtleplayer.ui.generated.resources.shell_pick_failed
import app.turtleplayer.ui.generated.resources.shell_pick_none
import app.turtleplayer.ui.generated.resources.shell_pick_title
import app.turtleplayer.ui.generated.resources.shell_pick_songs
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.turtle.PickerPlaylist
import com.metrolist.shared.turtle.PlaylistPickerSlot
import org.jetbrains.compose.resources.stringResource

/**
 * Shell's "Add to playlist" (turtle/HOOKS.md #33): upstream's workflow (selection, YouTube sync,
 * duplicates, create) drawn as a Shell sheet. Tick playlists, then Add; ones that already hold the
 * song say so.
 */
@Composable
fun ShellPlaylistPicker(slot: PlaylistPickerSlot) = ShellTheme {
    Dialog(onDismissRequest = { if (!slot.busy) slot.onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            ShellBottomSheet(visible = true, onDismiss = { if (!slot.busy) slot.onDismiss() }) { PickerContent(slot) }
        }
    }
}

@Composable
private fun PickerContent(slot: PlaylistPickerSlot) {
    val c = Shell.colors
    val song = slot.songs.firstOrNull()
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(Res.string.shell_pick_title), style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.85f), color = c.ink, modifier = Modifier.weight(1f))
        if (song != null) ShellArt(song.thumbnail, Modifier.size(40.dp), shape = RoundedCornerShape(4.dp))
    }
    if (slot.songs.size > 1) IndexLabel(stringResource(Res.string.shell_pick_count, slot.songs.size), Modifier.padding(horizontal = 8.dp))
    // New playlist first, as in Spotify.
    PickerRow(
        title = stringResource(Res.string.shell_pick_create),
        subtitle = null,
        art = { Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(c.surface3), contentAlignment = Alignment.Center) { Glyph(Glyphs.Add, c.ink, 22.dp) } },
        trailing = {},
        onClick = slot.onCreate,
    )
    if (slot.playlists.isEmpty()) {
        Text(stringResource(Res.string.shell_pick_none), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(12.dp))
    }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
        items(slot.playlists, key = { it.id }) { p -> PlaylistChoiceRow(p, slot) }
    }
    if (slot.failed) Text(stringResource(Res.string.shell_pick_failed), style = Shell.type.bodySmall, color = c.danger, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
    val canAdd = slot.selectedIds.isNotEmpty() && !slot.busy
    val bg by animateColorAsState(if (canAdd) c.accent else c.surface3, ShellMotion.emphasized())
    val fg by animateColorAsState(if (canAdd) c.onAccent else c.ink2, ShellMotion.emphasized())
    Box(
        Modifier.padding(horizontal = 8.dp, vertical = 12.dp).fillMaxWidth().height(52.dp)
            .shellPressable({ if (canAdd) slot.onAdd() }, focusShape = CircleShape)
            .drawBehind { drawRoundRect(bg, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)) },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(slot.busy, transitionSpec = { fadeIn(ShellMotion.emphasized()) togetherWith fadeOut(ShellMotion.exit()) }) { busy ->
            if (busy) app.turtleplayer.ui.components.Equalizer(color = fg, height = 14.dp)
            else Text(stringResource(Res.string.shell_pick_add), style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = fg)
        }
    }
}

@Composable
private fun PlaylistChoiceRow(p: PickerPlaylist, slot: PlaylistPickerSlot) {
    val c = Shell.colors
    val selected = p.id in slot.selectedIds
    val done = p.id in slot.completedIds
    val subtitle = when {
        done -> stringResource(Res.string.shell_pick_added)
        p.contains -> stringResource(Res.string.shell_pick_already)
        else -> pluralSongs(p.songCount)
    }
    PickerRow(
        title = p.name,
        subtitle = subtitle,
        subtitleAccent = done,
        art = { ShellArt(p.thumbnail, Modifier.size(48.dp), shape = RoundedCornerShape(4.dp)) },
        trailing = { Tick(selected || done) },
        onClick = { if (!slot.busy && !done) slot.onToggle(p.id) },
    )
}

@Composable
private fun pluralSongs(count: Int): String =
    org.jetbrains.compose.resources.pluralStringResource(Res.plurals.shell_pick_songs, count, count)

/** Spotify's round tick: an outline that fills lime with a check. */
@Composable
private fun Tick(on: Boolean) {
    val c = Shell.colors
    val fill by animateColorAsState(if (on) c.accent else Color.Transparent, ShellMotion.emphasized())
    Box(
        Modifier.size(24.dp).clip(CircleShape).background(fill).border(1.5.dp, if (on) Color.Transparent else c.ink2, CircleShape),
        contentAlignment = Alignment.Center,
    ) { if (on) Glyph(Glyphs.Check, c.onAccent, 13.dp) }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    art: @Composable () -> Unit,
    trailing: @Composable () -> Unit,
    onClick: () -> Unit,
    subtitleAccent: Boolean = false,
) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.ink.copy(alpha = 0.06f) else Color.Transparent, ShellMotion.emphasized())
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(8.dp)).background(bg).hoverable(source)
            .shellPressable(onClick, label = title).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        art()
        Spacer(Modifier.width(12.dp))
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            Text(title, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Shell.type.bodySmall, color = if (subtitleAccent) c.accent else c.ink2, maxLines = 1)
        }
        trailing()
    }
}
