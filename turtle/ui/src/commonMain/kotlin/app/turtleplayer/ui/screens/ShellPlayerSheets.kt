package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import app.turtleplayer.ui.components.ShellSnackbar
import app.turtleplayer.ui.generated.resources.shell_col_move_down
import app.turtleplayer.ui.generated.resources.shell_col_move_up
import app.turtleplayer.ui.generated.resources.shell_np_queue_clear
import app.turtleplayer.ui.generated.resources.shell_np_queue_cleared
import app.turtleplayer.ui.generated.resources.shell_np_queue_removed
import app.turtleplayer.ui.generated.resources.shell_undo
import app.turtleplayer.ui.theme.ShellPlatform
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_np_add_to_playlist
import app.turtleplayer.ui.generated.resources.shell_np_close
import app.turtleplayer.ui.generated.resources.shell_np_connected
import app.turtleplayer.ui.generated.resources.shell_np_devices_none
import app.turtleplayer.ui.generated.resources.shell_np_devices_searching
import app.turtleplayer.ui.generated.resources.shell_np_disconnect
import app.turtleplayer.ui.generated.resources.shell_np_download
import app.turtleplayer.ui.generated.resources.shell_np_downloaded
import app.turtleplayer.ui.generated.resources.shell_np_go_to_album
import app.turtleplayer.ui.generated.resources.shell_np_go_to_artist
import app.turtleplayer.ui.generated.resources.shell_np_minutes
import app.turtleplayer.ui.generated.resources.shell_np_now_playing
import app.turtleplayer.ui.generated.resources.shell_np_queue_empty
import app.turtleplayer.ui.generated.resources.shell_np_remove_from_queue
import app.turtleplayer.ui.generated.resources.shell_np_share
import app.turtleplayer.ui.generated.resources.shell_pause
import app.turtleplayer.ui.generated.resources.shell_play
import app.turtleplayer.ui.generated.resources.shell_np_sleep_cancel
import app.turtleplayer.ui.generated.resources.shell_np_sleep_end_of_song
import app.turtleplayer.ui.generated.resources.shell_np_sleep_on
import app.turtleplayer.ui.generated.resources.shell_np_sleep_timer
import app.turtleplayer.ui.generated.resources.shell_np_never_song
import app.turtleplayer.ui.generated.resources.shell_np_never_done
import app.turtleplayer.ui.generated.resources.shell_np_queue_left
import app.turtleplayer.ui.generated.resources.shell_np_never_artist
import app.turtleplayer.ui.generated.resources.shell_np_start_radio
import app.turtleplayer.ui.generated.resources.shell_np_this_device
import app.turtleplayer.ui.generated.resources.shell_next_in_queue
import app.turtleplayer.ui.generated.resources.shell_sp_hours_minutes
import app.turtleplayer.ui.generated.resources.shell_sp_minutes
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import com.metrolist.shared.cast.CastConnectionStatus
import com.metrolist.shared.model.LyricsEntry
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.ui.playback.PlaybackController
import com.metrolist.shared.ui.playback.PlaybackUiState
import org.jetbrains.compose.resources.stringResource

/**
 * What Shell's own player surfaces (lyrics, queue, more, sleep timer) need from the platform, so the
 * same Shell UI runs on phones (upstream's PlayerViewModel) and desktop (TurtleUiSlots.desktopExtras)
 * without ever handing over to upstream's player.
 */
internal class ShellPlayerServices(
    val lyrics: () -> List<LyricsEntry>,
    val lyricsLoading: () -> Boolean,
    val lyricsOffsetMs: () -> Int,
    /** Loads lyrics for the current song if they are not loaded yet. */
    val ensureLyrics: (SongItem) -> Unit,
    /** Removes the queue entry at this index, or null when the platform can't. */
    val removeQueueIndex: ((Int) -> Unit)?,
    val addToPlaylist: (SongItem) -> Unit,
    val share: ((SongItem) -> Unit)?,
    val download: ((SongItem) -> Unit)?,
    val isDownloaded: (String) -> Boolean,
    val openArtist: (String) -> Unit,
    val openAlbum: ((String) -> Unit)?,
    val startSleepTimer: (durationMs: Long, endOfSong: Boolean) -> Unit,
    val cancelSleepTimer: () -> Unit,
    val sleepTimerActive: () -> Boolean,
    /** Queue edits (remove with undo, reorder) this platform supports; null hides them. */
    val queueEditor: ShellQueueEditor? = null,
)

/** The player surface showing over (phone) or beside (desktop) Now Playing. */
internal enum class PlayerSurface { LYRICS, QUEUE, MORE, SLEEP, DEVICES }

// ---------------------------------------------------------------------------------------------
// Queue
// ---------------------------------------------------------------------------------------------

/** Now playing on top, then what plays next: tap to jump, drag the handle to reorder, swipe or ✕ to remove. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ShellQueueList(
    state: PlaybackUiState,
    controller: PlaybackController,
    services: ShellPlayerServices,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    // Desktop passes one it keeps, so the scroll survives the panel moving between docked and overlay
    // (a drag in progress does not: its order lives in this composition).
    listState: LazyListState = rememberLazyListState(),
) {
    val c = Shell.colors
    val queue = state.queue
    val current = state.currentQueueIndex
    val editor = services.queueEditor
    val upcoming = remember(queue, current) { if (current >= 0) (current + 1 until queue.size).toList() else queue.indices.toList() }
    val played = remember(queue, current) { if (current >= 0) (0..current.coerceAtMost(queue.lastIndex)).toList() else emptyList() }

    // While a drag runs, and until the platform applies it, rows show in the order being built.
    var localOrder by remember { mutableStateOf<List<Int>?>(null) }
    var localFor by remember { mutableStateOf<List<SongItem>?>(null) }
    val shown = localOrder?.takeIf { localFor == queue && it.size == upcoming.size && it.toSet() == upcoming.toSet() } ?: upcoming
    // Keyed by song id and its occurrence, not position, so rows below a removal or move keep their keys.
    val rowKeys = remember(shown, queue) {
        val seen = HashMap<String, Int>()
        shown.map { index ->
            val id = queue[index].id
            val n = seen[id] ?: 0
            seen[id] = n + 1
            "q-$id-$n"
        }
    }

    val removedText = stringResource(Res.string.shell_np_queue_removed)
    val clearedText = stringResource(Res.string.shell_np_queue_cleared)
    val undoText = stringResource(Res.string.shell_undo)
    fun remove(indices: Set<Int>, message: String): Boolean {
        val edit = editor ?: return false
        // Settle the last undo first: the platforms keep one pending undo at a time.
        ShellSnackbar.dismiss()
        val undo = edit.remove(indices) ?: return false
        ShellSnackbar.show(message, undoText, onAction = undo.restore, onDismiss = undo.discard)
        return true
    }
    fun commit(order: List<Int>) {
        val apply = editor?.reorder
        if (apply == null || order == upcoming || !apply(played + order)) {
            localOrder = null
            return
        }
        localOrder = order
        localFor = queue
    }

    val scope = rememberCoroutineScope()
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }
    val reorder = remember(listState) { CollectionReorder(listState, scope) }
    SideEffect {
        reorder.update(
            rowKeys,
            { from, to ->
                localOrder = shown.moved(from, to)
                localFor = queue
            },
            {
                localOrder = shown
                localFor = queue
            },
            { localOrder?.let(::commit) },
            edgePx,
        )
    }

    LazyColumn(modifier, state = listState, contentPadding = contentPadding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val now = queue.getOrNull(current)
        if (now != null) {
            item(key = "now-label") { IndexLabel(stringResource(Res.string.shell_np_now_playing), Modifier.padding(vertical = 8.dp)) }
            item(key = "now-$current") { QueueRow(now, playing = true, isPlaying = state.isPlaying, onClick = controller::togglePlayPause, onRemove = null) }
        }
        item(key = "next-label") {
            // Spicetify's "queue time": how long what is queued will play.
            val totalSec = upcoming.sumOf { queue[it].duration ?: 0L }
            // The title owns its line with Clear beside it (wrapping below it when text is large), and the
            // summary gets its own line, so "Next in queue" never breaks inside a word in a narrow panel.
            Column(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    // FlowRow breaks lines on a weighted item's minimum width (its longest word), which kept
                    // Clear beside the title and squeezed the title onto two lines; its one-line width counts instead.
                    IndexLabel(stringResource(Res.string.shell_next_in_queue), Modifier.weight(1f).width(androidx.compose.foundation.layout.IntrinsicSize.Max))
                    if (editor != null && upcoming.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.shell_np_queue_clear), style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink,
                            maxLines = 1,
                            modifier = Modifier.clip(CircleShape).background(c.ink.copy(alpha = 0.1f))
                                .shellPressable({ remove(upcoming.toSet(), clearedText) }, focusShape = CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                }
                if (upcoming.isNotEmpty() && totalSec > 0) IndexLabel(stringResource(Res.string.shell_np_queue_left, upcoming.size, queueLength(totalSec)))
            }
        }
        if (upcoming.isEmpty()) {
            item(key = "empty") { Text(stringResource(Res.string.shell_np_queue_empty), style = Shell.type.bodySmall, color = c.ink2) }
        }
        itemsIndexed(shown, key = { i, _ -> rowKeys[i] }) { position, index ->
            val key = rowKeys[position]
            val dragging = reorder.draggingKey == key
            val canReorder = editor?.reorder != null
            QueueRow(
                queue[index], playing = false, isPlaying = false,
                onClick = { controller.playQueueIndex(index) },
                onRemove = if (editor != null) ({ remove(setOf(index), removedText) }) else null,
                onMove = if (canReorder) ({ to: Int -> commit(shown.moved(position, to)) }) else null,
                position = position,
                count = shown.size,
                handle = if (canReorder) ({ ReorderHandle(key, reorder, position, shown.size) { from, to -> commit(shown.moved(from, to)) } }) else null,
                modifier = if (dragging) Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(key) } else Modifier.animateItem(fadeInSpec = null),
            )
        }
    }
}

private fun List<Int>.moved(from: Int, to: Int): List<Int> =
    if (from !in indices || to !in indices) this else toMutableList().apply { add(to, removeAt(from)) }

@Composable
private fun QueueRow(
    song: SongItem,
    playing: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onRemove: (() -> Boolean)?,
    modifier: Modifier = Modifier,
    onMove: ((Int) -> Unit)? = null,
    position: Int = 0,
    count: Int = 0,
    handle: (@Composable () -> Unit)? = null,
) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.ink.copy(alpha = 0.08f) else Color.Transparent, ShellMotion.emphasized())
    val removeLabel = stringResource(Res.string.shell_np_remove_from_queue)
    val upLabel = stringResource(Res.string.shell_col_move_up)
    val downLabel = stringResource(Res.string.shell_col_move_down)
    // Screen readers can't drag or swipe: the same edits as custom actions.
    val actions = buildList {
        if (onRemove != null) add(CustomAccessibilityAction(removeLabel) { onRemove() })
        if (onMove != null && position > 0) add(CustomAccessibilityAction(upLabel) { onMove(position - 1); true })
        if (onMove != null && position < count - 1) add(CustomAccessibilityAction(downLabel) { onMove(position + 1); true })
    }
    SwipeToRemove(enabled = onRemove != null && !ShellPlatform.desktop, onRemove = { onRemove?.invoke() == true }, modifier = modifier) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(6.dp)).background(bg).hoverable(source)
                // The playing row pauses and resumes, so it says so rather than "activate".
                .shellPressable(
                    onClick, label = song.title,
                    onClickLabel = if (playing) stringResource(if (isPlaying) Res.string.shell_pause else Res.string.shell_play) else null,
                )
                .semantics { if (actions.isNotEmpty()) customActions = actions }
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShellArt(song.displayThumbnailUrl, Modifier.size(44.dp), shape = RoundedCornerShape(4.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = if (playing) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Infinity Mix songs say why they are queued, so steering is visible in Up next.
                InfinityWhyLabel(song.id, Modifier.padding(top = 2.dp))
            }
            if (playing && isPlaying) Equalizer(height = 12.dp, modifier = Modifier.padding(horizontal = 12.dp))
            if (onRemove != null) {
                Box(Modifier.size(48.dp).shellPressable({ onRemove() }, label = removeLabel, focusShape = CircleShape), contentAlignment = Alignment.Center) {
                    Glyph(Glyphs.Close, c.ink2, 14.dp)
                }
            }
            handle?.invoke()
        }
    }
}

/**
 * Swipe sideways to remove (phones), as in Spotify's queue: a danger strip opens behind the row and,
 * past a third of its width, the row slides away. Short of that it springs back.
 */
@Composable
private fun SwipeToRemove(enabled: Boolean, onRemove: () -> Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!enabled) {
        Box(modifier) { content() }
        return
    }
    val c = Shell.colors
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableIntStateOf(1) }
    val danger = c.danger
    Box(
        modifier.fillMaxWidth().onSizeChanged { width = it.width.coerceAtLeast(1) }
            .drawBehind {
                val dx = offset.value
                if (dx != 0f) {
                    val strip = abs(dx)
                    val alpha = 0.35f + 0.5f * (strip / (size.width * SWIPE_THRESHOLD)).coerceIn(0f, 1f)
                    drawRect(danger.copy(alpha = alpha), topLeft = Offset(if (dx > 0) 0f else size.width - strip, 0f), size = Size(strip, size.height))
                }
            },
    ) {
        if (offset.value != 0f) {
            Glyph(Glyphs.Close, c.ink, 16.dp, Modifier.align(if (offset.value > 0) Alignment.CenterStart else Alignment.CenterEnd).padding(horizontal = 20.dp))
        }
        Box(
            Modifier.offset { IntOffset(offset.value.roundToInt(), 0) }.pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            val dx = offset.value
                            if (abs(dx) > width * SWIPE_THRESHOLD) {
                                offset.animateTo(if (dx > 0) width.toFloat() else -width.toFloat(), tween(ShellMotion.EXIT_MS, easing = ShellMotion.Exit))
                                if (!onRemove()) offset.animateTo(0f, ShellMotion.spring())
                            } else {
                                offset.animateTo(0f, ShellMotion.spring())
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offset.animateTo(0f, ShellMotion.spring()) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { offset.snapTo(offset.value + amount) }
                }
            },
        ) { content() }
    }
}

private const val SWIPE_THRESHOLD = 0.35f

// ---------------------------------------------------------------------------------------------
// More, sleep timer, devices
// ---------------------------------------------------------------------------------------------

@Composable
internal fun ShellMoreActions(
    song: SongItem,
    controller: PlaybackController,
    services: ShellPlayerServices,
    onShow: (PlayerSurface) -> Unit,
    onDone: () -> Unit,
    onToast: (String) -> Unit,
) {
    val sleepOn = services.sleepTimerActive()
    // Blocking takes effect at once, so say so (and where to undo it) rather than closing silently.
    val blockedText = stringResource(Res.string.shell_np_never_done)
    SheetHeader(song)
    ActionRow(Glyphs.Add, stringResource(Res.string.shell_np_add_to_playlist)) { onDone(); services.addToPlaylist(song) }
    song.artists.firstOrNull()?.id?.let { artistId ->
        ActionRow(Glyphs.Artist, stringResource(Res.string.shell_np_go_to_artist)) { onDone(); services.openArtist(artistId) }
    }
    val albumId = song.album?.id
    val openAlbum = services.openAlbum
    if (albumId != null && openAlbum != null) {
        ActionRow(Glyphs.Album, stringResource(Res.string.shell_np_go_to_album)) { onDone(); openAlbum(albumId) }
    }
    ActionRow(Glyphs.Radio, stringResource(Res.string.shell_np_start_radio)) { onDone(); ShellActions.startRadio(controller, song) }
    services.download?.let { download ->
        val done = services.isDownloaded(song.id)
        ActionRow(Glyphs.Download, stringResource(if (done) Res.string.shell_np_downloaded else Res.string.shell_np_download), enabled = !done) { onDone(); download(song) }
    }
    services.share?.let { share -> ActionRow(Glyphs.Share, stringResource(Res.string.shell_np_share)) { onDone(); share(song) } }
    ActionRow(Glyphs.Sleep, stringResource(if (sleepOn) Res.string.shell_np_sleep_on else Res.string.shell_np_sleep_timer), accent = sleepOn) { onShow(PlayerSurface.SLEEP) }
    // Spicetify's Trash Bin, built in: a hard block that holds everywhere (radio, mixes, search), unlike "less like this".
    ActionRow(Glyphs.Less, stringResource(Res.string.shell_np_never_song)) {
        onDone()
        com.metrolist.shared.state.SongLikeState.dislike(song)
        ShellActions.lessLikeThis(controller, song)
        onToast(blockedText)
    }
    song.artists.firstOrNull()?.takeIf { !it.id.isNullOrBlank() }?.let { artist ->
        ActionRow(Glyphs.Close, stringResource(Res.string.shell_np_never_artist, artist.name)) {
            onDone()
            com.metrolist.shared.settings.BlockedContent.blockArtist(artist)
            ShellActions.lessLikeThis(controller, song)
            onToast(blockedText)
        }
    }
}

@Composable
internal fun ShellSleepTimer(services: ShellPlayerServices, onDone: () -> Unit) {
    val c = Shell.colors
    Text(stringResource(Res.string.shell_np_sleep_timer), style = Shell.type.title.copy(fontSize = Shell.type.body.fontSize), color = c.ink, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
    listOf(15, 30, 45, 60, 90).forEach { minutes ->
        ActionRow(null, stringResource(Res.string.shell_np_minutes, minutes)) { services.startSleepTimer(minutes * 60_000L, false); onDone() }
    }
    ActionRow(null, stringResource(Res.string.shell_np_sleep_end_of_song)) { services.startSleepTimer(0L, true); onDone() }
    if (services.sleepTimerActive()) {
        ActionRow(Glyphs.Close, stringResource(Res.string.shell_np_sleep_cancel), accent = true) { services.cancelSleepTimer(); onDone() }
    }
}

/** Speakers and TVs on the network; this device first. Discovery runs while the list is showing. */
@Composable
internal fun ShellDevices(state: PlaybackUiState, controller: PlaybackController) {
    val c = Shell.colors
    DisposableEffect(controller) {
        controller.startCastDiscovery()
        onDispose { controller.stopCastDiscovery() }
    }
    val cast = state.castState
    val connected = cast.status == CastConnectionStatus.CONNECTED
    val local = !connected && cast.status != CastConnectionStatus.CONNECTING
    ActionRow(Glyphs.Devices, stringResource(Res.string.shell_np_this_device), accent = local, selected = local) {
        if (cast.device != null) controller.disconnectCast()
    }
    state.castDevices.forEach { device ->
        val active = cast.device?.id == device.id
        ActionRow(
            Glyphs.Speaker,
            device.name,
            accent = active && connected,
            trailing = if (active && connected) stringResource(Res.string.shell_np_connected) else null,
        ) { if (active) controller.disconnectCast() else controller.castTo(device) }
    }
    if (state.castDevices.isEmpty()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Equalizer(color = c.ink2, height = 10.dp)
            Spacer(Modifier.width(12.dp))
            Text(stringResource(Res.string.shell_np_devices_searching), style = Shell.type.bodySmall, color = c.ink2)
        }
        Text(stringResource(Res.string.shell_np_devices_none), style = Shell.type.bodySmall, color = c.ink2.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 12.dp))
    }
    if (connected) {
        ActionRow(Glyphs.Close, stringResource(Res.string.shell_np_disconnect)) { controller.disconnectCast() }
    }
}

@Composable
private fun SheetHeader(song: SongItem) {
    val c = Shell.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        ShellArt(song.displayThumbnailUrl, Modifier.size(48.dp), shape = RoundedCornerShape(4.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.title, style = Shell.type.label.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(c.hairline))
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun ActionRow(glyph: ImageVector?, label: String, accent: Boolean = false, enabled: Boolean = true, trailing: String? = null, selected: Boolean? = null, onClick: () -> Unit) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.ink.copy(alpha = 0.06f) else Color.Transparent, ShellMotion.emphasized())
    val tint = when {
        !enabled -> c.ink2.copy(alpha = 0.6f)
        accent -> c.accent
        else -> c.ink
    }
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(8.dp)).background(bg).hoverable(source)
            .then(if (enabled) Modifier.shellPressable(onClick, label = label, selected = selected) else Modifier)
            // The label replaces the row's text for screen readers, so the trailing status is said as its state.
            .then(if (enabled && trailing != null) Modifier.semantics { stateDescription = trailing } else Modifier)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != null) {
            Glyph(glyph, tint, 22.dp)
            Spacer(Modifier.width(16.dp))
        }
        Text(label, style = Shell.type.body, color = tint, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) IndexLabel(trailing, color = c.accent)
    }
}

/**
 * Shell's bottom sheet for phones: a scrim and a rounded panel that springs up (H-Motion), closing on
 * scrim tap or Back. The caller keeps it composed and toggles [visible].
 */
@Composable
internal fun BoxScope.ShellBottomSheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val c = Shell.colors
    if (visible) com.metrolist.shared.ui.navigation.NavigationBackHandler(enabled = true) { onDismiss() }
    AnimatedVisibility(visible, enter = fadeIn(ShellMotion.emphasized()), exit = fadeOut(ShellMotion.exit()), modifier = Modifier.matchParentSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        )
    }
    AnimatedVisibility(
        visible,
        enter = slideInVertically(ShellMotion.spring()) { it } + fadeIn(ShellMotion.emphasized()),
        exit = slideOutVertically(ShellMotion.exit()) { it } + fadeOut(ShellMotion.exit()),
        modifier = Modifier.align(Alignment.BottomCenter),
    ) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).background(c.surface1)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .windowInsetsPadding(WindowInsets.navigationBars).padding(horizontal = 8.dp).padding(bottom = 8.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(c.ink2.copy(alpha = 0.5f)))
            }
            content()
        }
    }
}

/** Header for a full-screen surface (lyrics, queue): the song's title with a close chevron. */
@Composable
internal fun SurfaceHeader(title: String, subtitle: String?, onClose: () -> Unit) {
    val c = Shell.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(Glyphs.ChevronDown, stringResource(Res.string.shell_np_close), onClose, c.ink, iconSize = 24.dp)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = Shell.type.label.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = Shell.type.bodySmall, color = c.ink.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.size(48.dp))
    }
}

/** Lyrics for whatever is playing, loading them on first show. */
@Composable
internal fun ShellLyricsFor(song: SongItem, services: ShellPlayerServices, live: LivePosition, onSeek: (Long) -> Unit, modifier: Modifier, compact: Boolean, contentPadding: PaddingValues = PaddingValues(0.dp)) {
    LaunchedEffect(song.id) { services.ensureLyrics(song) }
    ShellLyricsView(
        entries = services.lyrics(),
        loading = services.lyricsLoading(),
        position = { live.ms },
        offsetMs = services.lyricsOffsetMs(),
        onSeek = onSeek,
        modifier = modifier,
        contentPadding = contentPadding,
        compact = compact,
    )
}

/** "1 h 12 min" or "38 min" for a queue's total length in seconds. */
@Composable
private fun queueLength(seconds: Long): String {
    val minutes = ((seconds + 30) / 60).toInt()
    return if (minutes >= 60) stringResource(Res.string.shell_sp_hours_minutes, minutes / 60, minutes % 60) else stringResource(Res.string.shell_sp_minutes, minutes)
}
