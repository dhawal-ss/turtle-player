package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.NotInterested
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.Served
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_home_not_interested
import app.turtleplayer.ui.generated.resources.shell_home_not_interested_done
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Home's play attribution and "Not interested" (personal-home design, W9). Home calls into this file
 * in one line per place, so restructuring Home keeps every play attributed and every menu offered.
 */
internal object ShellHomeFeedback {
    // Outlives the menu: hiding must finish after the menu that started it has closed.
    private val scope = MainScope()

    /** Attributes [songs] to [place]/[row] (a YouTube row ordered by [youtubeOrder], null when Turtle never orders it), then runs [play] (which starts playback). */
    fun play(
        place: PlayPlace,
        row: String?,
        songs: List<SongItem>,
        youtubeOrder: Served? = null,
        play: () -> Unit,
    ) {
        TurtleBridge.attributePlay(place, row, songs, youtubeOrder)
        play()
    }

    /** Arms the "Not interested" latch for songs and artists (disarms for anything else), then opens upstream's menu. */
    fun openMenu(
        item: YTItem,
        open: (YTItem) -> Unit,
    ) {
        when (item) {
            is SongItem -> TurtleBridge.homeMenu.arm(NotInterested.Song(item))
            is ArtistItem -> TurtleBridge.homeMenu.arm(NotInterested.Artist(item))
            else -> TurtleBridge.homeMenu.disarm()
        }
        open(item)
    }

    fun hide(target: NotInterested) {
        scope.launch { TurtleBridge.notInterested(target) }
    }
}

/** Inside Shell's menu header: "Not interested" for the Home item this menu was opened for; nothing for any other menu. */
@Composable
internal fun HomeNotInterestedRow(
    title: String,
    popup: Boolean,
) {
    val target = remember(title) { TurtleBridge.homeMenu.claim(title) } ?: return
    val c = Shell.colors
    var done by remember(target) { mutableStateOf(false) }
    val (source, hovered) = rememberHover()
    val shape = RoundedCornerShape(if (popup) 6.dp else 0.dp)
    val bg by animateColorAsState(if (hovered && !done) c.ink.copy(alpha = 0.07f) else Color.Transparent, ShellMotion.emphasized())
    val label = stringResource(if (done) Res.string.shell_home_not_interested_done else Res.string.shell_home_not_interested)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = if (popup) 4.dp else 20.dp)
            .heightIn(min = if (popup) 40.dp else 54.dp)
            .clip(shape)
            .background(bg)
            .hoverable(source)
            .then(
                if (done) {
                    Modifier
                } else {
                    Modifier.shellPressable(
                        {
                            done = true
                            ShellHomeFeedback.hide(target)
                        },
                        label = label,
                        focusShape = shape,
                    )
                },
            ).padding(horizontal = if (popup) 8.dp else 0.dp, vertical = if (popup) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(if (done) Glyphs.Check else Glyphs.Less, if (done) c.accent else c.ink2, if (popup) 18.dp else 22.dp)
        Spacer(Modifier.width(if (popup) 12.dp else 16.dp))
        Text(
            label,
            style = if (popup) Shell.type.label.copy(fontSize = 14.sp) else Shell.type.body,
            color = if (done) c.ink2 else c.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A mix card's long-press menu: one entry, "Not interested". */
@Composable
internal fun MixMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    mix: NotInterested.Mix,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(Res.string.shell_home_not_interested)) },
            leadingIcon = { Glyph(Glyphs.Less, Shell.colors.ink2, 20.dp) },
            onClick = {
                onDismiss()
                ShellHomeFeedback.hide(mix)
            },
        )
    }
}
