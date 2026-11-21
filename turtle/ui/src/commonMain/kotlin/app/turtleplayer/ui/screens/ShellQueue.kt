package app.turtleplayer.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Which queue Shell started, and under what name. Upstream's controllers drop `queueTitle` on both
 * platforms, so "is the Infinity Mix playing?" and "Playing from …" can't be read back from the
 * player. We remember the queue's first song instead: the queue is still ours while that song is in
 * it (autoplay only appends), and playing anything else replaces it.
 */
object ShellQueue {
    private class Anchor(val songId: String, val title: String)

    private var anchor by mutableStateOf<Anchor?>(null)

    /** Plays [songs] as a named queue and remembers the name. */
    fun play(controller: PlaybackController, songs: List<SongItem>, title: String, startIndex: Int = 0) {
        val first = songs.getOrNull(startIndex) ?: return
        anchor = Anchor(first.id, title)
        controller.playQueue(songs, startIndex, queueTitle = title)
    }

    /** The name of the Shell-started queue that is still playing, or null. */
    fun activeTitle(state: PlaybackUiState): String? {
        val a = anchor ?: return null
        return a.title.takeIf { state.queue.any { it.id == a.songId } }
    }
}

private fun PlaybackUiState.calm() = copy(positionMs = 0L, bufferedPositionMs = 0L)

/**
 * Playback state without the position tick, which both platforms update continuously: screens that
 * collect this recompose on song, queue, play/pause and mode changes only. Read the position in a
 * leaf with [rememberLivePosition].
 */
@androidx.compose.runtime.Composable
internal fun PlaybackController.rememberCalmState(): androidx.compose.runtime.State<PlaybackUiState> {
    val flow = androidx.compose.runtime.remember(this) {
        state.map { it.calm() }.distinctUntilChanged()
    }
    return flow.collectAsState(androidx.compose.runtime.remember(this) { state.value.calm() })
}

/** The playback position, polled while playing; only composables that read [ms] recompose. */
@androidx.compose.runtime.Stable
internal class LivePosition(initial: Long) {
    var ms by androidx.compose.runtime.mutableLongStateOf(initial)
}

@androidx.compose.runtime.Composable
internal fun rememberLivePosition(controller: PlaybackController, playing: Boolean, songId: String?): LivePosition {
    val live = androidx.compose.runtime.remember(controller) { LivePosition(controller.currentPositionMs()) }
    androidx.compose.runtime.LaunchedEffect(controller, songId, playing) {
        live.ms = controller.currentPositionMs()
        while (playing) {
            kotlinx.coroutines.delay(250)
            live.ms = controller.currentPositionMs()
        }
    }
    return live
}

/**
 * "Less like this" from any surface: one at a time, outliving the sheet or panel that asked, and
 * skipping only if that song is still the one playing.
 */
internal object ShellActions {
    private val scope = kotlinx.coroutines.MainScope()
    private var pending = false

    /** Starts the Infinity Mix; returns false (via [onEmpty]) when our engine has nothing yet. */
    fun playInfinity(controller: PlaybackController, title: String, onEmpty: () -> Unit = {}) {
        scope.launch {
            val songs = app.turtleplayer.bridge.TurtleBridge.infinityMix()
            if (songs.isNotEmpty()) ShellQueue.play(controller, songs, title) else onEmpty()
        }
    }

    fun lessLikeThis(controller: PlaybackController, song: SongItem) {
        if (pending) return
        pending = true
        scope.launch {
            try {
                app.turtleplayer.bridge.TurtleBridge.lessLikeThis(song)
                kotlinx.coroutines.delay(250)
                if (controller.state.value.currentSong?.id == song.id) controller.skipToNext()
            } finally {
                pending = false
            }
        }
    }
}
