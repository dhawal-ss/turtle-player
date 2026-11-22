package app.turtleplayer.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_smart_shuffle
import app.turtleplayer.ui.generated.resources.shell_smart_shuffle_busy
import app.turtleplayer.ui.theme.Shell
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Smart Shuffle on a playlist or album page: the songs shuffled with taste-matched recommendations
 * mixed in, played as that page's queue. Runs outside the page, so leaving it doesn't cancel the
 * play; the bridge answers within about 3 s, with a plain shuffle when it can't do better.
 */
internal object ShellSmartShuffle {
    private val scope = MainScope()

    /** True while recommendations are being gathered. */
    var busy by mutableStateOf(false)
        private set

    fun play(controller: PlaybackController, songs: List<SongItem>, title: String, sourceId: String?) {
        val playable = songs.filterNot { it.isUnavailable }
        if (busy || playable.isEmpty()) return
        busy = true
        scope.launch {
            try {
                val order = TurtleBridge.smartShuffle(playable)
                if (order.isNotEmpty()) {
                    ShellQueue.play(controller, order, title, sourceId = sourceId)
                    // The page shows pause while its smart-shuffled queue plays, recommendations included.
                    if (sourceId != null) ShellQueue.markSource(sourceId, order)
                }
            } finally {
                busy = false
            }
        }
    }
}

/** The sparkle beside Shuffle; lime while Smart Shuffle is gathering songs. */
@Composable
internal fun SmartShuffleButton(onClick: () -> Unit) {
    val c = Shell.colors
    val busy = ShellSmartShuffle.busy
    GlyphButton(
        Glyphs.Sparkle,
        stringResource(Res.string.shell_smart_shuffle),
        onClick,
        if (busy) c.accent else c.ink2,
        iconSize = 24.dp,
        state = if (busy) stringResource(Res.string.shell_smart_shuffle_busy) else null,
    )
}
