package app.turtleplayer.ui.screens

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Which queue Shell started, and under what name. Upstream's controllers drop `queueTitle` on both
 * platforms, so "is the Infinity Mix playing?" and "Playing from …" can't be read back from the
 * player. We remember the queue's first song instead: the queue is still ours while that song is in
 * it (autoplay only appends), and playing anything else replaces it.
 */
object ShellQueue {
    private class Anchor(val songId: String, val title: String, val sourceId: String?)

    private var anchor by mutableStateOf<Anchor?>(null)

    /** Plays [songs] as a named queue and remembers the name and, when known, the playlist or book id. */
    fun play(controller: PlaybackController, songs: List<SongItem>, title: String, startIndex: Int = 0, sourceId: String? = null) {
        val first = songs.getOrNull(startIndex) ?: return
        anchor = Anchor(first.id, title, sourceId)
        source = null
        controller.playQueue(songs, startIndex, queueTitle = title)
    }

    private fun active(state: PlaybackUiState): Anchor? = anchor?.takeIf { a -> state.queue.any { it.id == a.songId } }

    /** The name of the Shell-started queue that is still playing, or null. */
    fun activeTitle(state: PlaybackUiState): String? = active(state)?.title

    /** The id of the library item the Shell-started queue came from, while it is still playing. */
    fun activeSourceId(state: PlaybackUiState): String? = active(state)?.sourceId

    private class Source(val id: String, val songIds: Set<String>)

    private var source by mutableStateOf<Source?>(null)

    /** Remembers that a playlist or album page started (or shuffled) [songs], for its play/pause button. */
    fun markSource(id: String, songs: List<SongItem>) {
        source = Source(id, songs.mapTo(HashSet()) { it.id })
    }

    /**
     * True while the queue playing came from the collection [id] holding [songs]: the page started it
     * and one of its songs is on, or (started elsewhere) the queue opens with that collection's songs.
     */
    fun isSourceActive(id: String, songs: List<SongItem>, state: PlaybackUiState): Boolean {
        val currentId = state.currentSong?.id ?: return false
        source?.let { s -> if (s.id == id) return currentId in s.songIds }
        if (songs.none { it.id == currentId }) return false
        val ids = songs.mapTo(HashSet()) { it.id }
        val head = state.queue.take(songs.size)
        return head.isNotEmpty() && head.count { it.id in ids } * 10 >= head.size * 9
    }
}

/** How to take back a queue removal: [restore] puts the songs back, [discard] lets the undo go. */
internal class QueueUndo(val restore: () -> Unit, val discard: () -> Unit = {})

/** Queue edits Shell's queue surfaces make on this platform; a null [reorder] hides drag to reorder. */
internal class ShellQueueEditor(
    /** Removes these queue indices; null when nothing was removed. */
    val remove: (Set<Int>) -> QueueUndo?,
    /** The whole queue in a new order (old index at each position); false when the platform refused. */
    val reorder: ((List<Int>) -> Boolean)?,
)

/**
 * Desktop's undo for a queue removal: upstream exposes no restore there, so the songs go back on the
 * end of the queue and are then moved to where they were.
 */
internal object ShellQueueRestore {
    private val scope = kotlinx.coroutines.MainScope()

    fun reinsert(controller: PlaybackController, original: List<SongItem>, removed: List<Pair<Int, SongItem>>) {
        scope.launch {
            val before = controller.state.value.queue
            // Only songs actually gone: the platform may have refused the removal (locked queue).
            val missing = removed.filter { (_, song) -> before.count { it.id == song.id } < original.count { it.id == song.id } }
            if (missing.isEmpty()) return@launch
            controller.addToQueue(missing.map { it.second })
            val after = kotlinx.coroutines.withTimeoutOrNull(RESTORE_WAIT_MS) {
                controller.state.first { it.queue.size >= before.size + missing.size }
            }?.queue ?: return@launch
            if (after.size != before.size + missing.size) return@launch
            val order = before.indices.toMutableList()
            missing.forEachIndexed { k, (index, _) -> order.add(index.coerceIn(0, order.size), before.size + k) }
            controller.reorderQueue(order)
        }
    }

    private const val RESTORE_WAIT_MS = 2_000L
}

private fun PlaybackUiState.calm() = copy(
    positionMs = 0L,
    bufferedPositionMs = 0L,
    sleepTimerState = sleepTimerState.copy(remainingMs = null),
)

/**
 * Playback state without the position and sleep-timer countdown ticks, which update continuously:
 * screens that collect this recompose on song, queue, play/pause and mode changes only. Read the
 * position in a leaf with [rememberLivePosition], and the countdown from `controller.state` in a leaf.
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
        // First time: let the listener pick genres and moods before anything plays.
        if (!app.turtleplayer.bridge.TurtleBridge.infinity.value.configured) {
            ShellInfinity.sheetOpen = true
            return
        }
        ShellInfinity.play(controller, title, onNothing = onEmpty)
    }

    /**
     * Starts a radio from [song] as a new queue occurrence, so the last queue's tags and short-term
     * learning stop applying even when the song playing does not change. Waits only briefly for that.
     */
    fun startRadio(controller: PlaybackController, song: SongItem) {
        scope.launch {
            kotlinx.coroutines.withTimeoutOrNull(OCCURRENCE_WAIT_MS) { app.turtleplayer.bridge.TurtleBridge.beginOccurrence() }
            controller.startRadio(song)
        }
    }

    private const val OCCURRENCE_WAIT_MS = 250L

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
