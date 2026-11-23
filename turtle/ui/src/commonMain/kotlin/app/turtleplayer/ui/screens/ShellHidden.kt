package app.turtleplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.turtleplayer.bridge.HiddenEntry
import app.turtleplayer.bridge.HiddenKind
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_set_hidden_artists
import app.turtleplayer.ui.generated.resources.shell_set_hidden_empty
import app.turtleplayer.ui.generated.resources.shell_set_hidden_mixes
import app.turtleplayer.ui.generated.resources.shell_set_hidden_songs
import app.turtleplayer.ui.generated.resources.shell_set_hidden_undo
import app.turtleplayer.ui.theme.Shell
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

// Outlives the page, so "Show again" finishes even if the listener leaves right away.
private val hiddenScope = MainScope()

/** The "Hidden songs and artists" page: what the listener marked Not interested, each with "Show again". */
@Composable
internal fun HiddenList() {
    val c = Shell.colors
    val entries by TurtleBridge.hidden.collectAsState()
    if (entries.isEmpty()) {
        Text(
            stringResource(Res.string.shell_set_hidden_empty),
            style = Shell.type.bodySmall,
            color = c.ink2,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        return
    }
    val groups =
        listOf(
            HiddenKind.SONG to stringResource(Res.string.shell_set_hidden_songs),
            HiddenKind.ARTIST to stringResource(Res.string.shell_set_hidden_artists),
            HiddenKind.MIX to stringResource(Res.string.shell_set_hidden_mixes),
        )
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        groups.forEach { (kind, title) ->
            val rows = entries.filter { it.kind == kind }
            if (rows.isEmpty()) return@forEach
            Text(
                title,
                style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold),
                color = c.ink2,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            rows.forEach { entry -> HiddenRow(entry) }
        }
    }
}

@Composable
private fun HiddenRow(entry: HiddenEntry) {
    val c = Shell.colors
    val shape = if (entry.kind == HiddenKind.ARTIST) CircleShape else RoundedCornerShape(6.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val thumbnail = entry.thumbnail
        if (thumbnail != null) {
            ShellArt(thumbnail, Modifier.size(40.dp), shape = shape)
        } else {
            Box(Modifier.size(40.dp).clip(shape).background(c.surface3), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Less, c.ink2, 18.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = Shell.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            entry.artist?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(12.dp))
        val undo = stringResource(Res.string.shell_set_hidden_undo)
        Text(
            undo,
            style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold),
            color = c.ink,
            modifier =
                Modifier
                    .clip(CircleShape)
                    .background(c.surface2)
                    .shellPressable({ hiddenScope.launch { TurtleBridge.undoHidden(entry) } }, label = undo)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
