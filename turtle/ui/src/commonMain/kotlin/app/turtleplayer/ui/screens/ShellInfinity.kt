package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.turtleplayer.bridge.InfinityGenres
import app.turtleplayer.bridge.InfinityWhy
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.InfinityCube
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_inf_thumb_up
import app.turtleplayer.ui.generated.resources.shell_inf_thumb_down
import app.turtleplayer.ui.generated.resources.shell_inf_fresh
import app.turtleplayer.ui.generated.resources.shell_inf_fresh_confirm
import app.turtleplayer.ui.generated.resources.shell_inf_fresh_body
import app.turtleplayer.ui.generated.resources.shell_add_to_playlist
import app.turtleplayer.ui.generated.resources.shell_infinity_title
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.turtle.TurtleUiSlots
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import app.turtleplayer.ui.generated.resources.shell_inf_sheet_title
import app.turtleplayer.ui.generated.resources.shell_inf_sheet_body
import app.turtleplayer.ui.generated.resources.shell_inf_genres
import app.turtleplayer.ui.generated.resources.shell_inf_moods
import app.turtleplayer.ui.generated.resources.shell_inf_start
import app.turtleplayer.ui.generated.resources.shell_inf_update
import app.turtleplayer.ui.generated.resources.shell_inf_clear
import app.turtleplayer.ui.generated.resources.shell_inf_none_picked
import app.turtleplayer.ui.generated.resources.shell_inf_tuned
import app.turtleplayer.ui.generated.resources.shell_inf_leaning
import app.turtleplayer.ui.generated.resources.shell_inf_tune
import app.turtleplayer.ui.generated.resources.shell_inf_why_familiar
import app.turtleplayer.ui.generated.resources.shell_inf_why_genre
import app.turtleplayer.ui.generated.resources.shell_inf_why_more_like
import app.turtleplayer.ui.generated.resources.shell_inf_why_for_you
import app.turtleplayer.ui.generated.resources.shell_inf_blend_familiar
import app.turtleplayer.ui.generated.resources.shell_inf_blend_new
import app.turtleplayer.ui.generated.resources.shell_inf_resteered
import app.turtleplayer.ui.generated.resources.shell_genre_pop
import app.turtleplayer.ui.generated.resources.shell_genre_hiphop
import app.turtleplayer.ui.generated.resources.shell_genre_rnb
import app.turtleplayer.ui.generated.resources.shell_genre_rock
import app.turtleplayer.ui.generated.resources.shell_genre_indie
import app.turtleplayer.ui.generated.resources.shell_genre_alternative
import app.turtleplayer.ui.generated.resources.shell_genre_electronic
import app.turtleplayer.ui.generated.resources.shell_genre_house
import app.turtleplayer.ui.generated.resources.shell_genre_techno
import app.turtleplayer.ui.generated.resources.shell_genre_dance
import app.turtleplayer.ui.generated.resources.shell_genre_lofi
import app.turtleplayer.ui.generated.resources.shell_genre_jazz
import app.turtleplayer.ui.generated.resources.shell_genre_soul
import app.turtleplayer.ui.generated.resources.shell_genre_funk
import app.turtleplayer.ui.generated.resources.shell_genre_classical
import app.turtleplayer.ui.generated.resources.shell_genre_country
import app.turtleplayer.ui.generated.resources.shell_genre_latin
import app.turtleplayer.ui.generated.resources.shell_genre_reggaeton
import app.turtleplayer.ui.generated.resources.shell_genre_afrobeats
import app.turtleplayer.ui.generated.resources.shell_genre_kpop
import app.turtleplayer.ui.generated.resources.shell_genre_metal
import app.turtleplayer.ui.generated.resources.shell_genre_punk
import app.turtleplayer.ui.generated.resources.shell_genre_folk
import app.turtleplayer.ui.generated.resources.shell_genre_blues
import app.turtleplayer.ui.generated.resources.shell_genre_reggae
import app.turtleplayer.ui.generated.resources.shell_genre_ambient
import app.turtleplayer.ui.generated.resources.shell_genre_bollywood
import app.turtleplayer.ui.generated.resources.shell_genre_punjabi
import app.turtleplayer.ui.generated.resources.shell_genre_chill
import app.turtleplayer.ui.generated.resources.shell_genre_focus
import app.turtleplayer.ui.generated.resources.shell_genre_workout
import app.turtleplayer.ui.generated.resources.shell_genre_party
import app.turtleplayer.ui.generated.resources.shell_genre_sleep
import app.turtleplayer.ui.generated.resources.shell_genre_romance
import app.turtleplayer.ui.generated.resources.shell_genre_sad
import app.turtleplayer.ui.generated.resources.shell_genre_feelgood
import app.turtleplayer.ui.generated.resources.shell_genre_throwback
import app.turtleplayer.ui.generated.resources.shell_genre_roadtrip

/**
 * The Infinity Mix outside of any screen: it starts the mix, keeps the queue topped up from our
 * engine (genres, favourites and the discovery dial blend), and re-mixes Up Next when the listener
 * steers. Lives in a MainScope, so it outlives the sheet or card that started it.
 */
internal object ShellInfinity {
    private val scope = MainScope()
    private var watcher: Job? = null
    private var filling = false
    private var title: String? = null

    /** Whether the Infinity sheet is showing; any surface can open it. */
    var sheetOpen by mutableStateOf(false)

    /**
     * Removes a queue index through upstream's phone player view model. Android's controller has no
     * removeQueueItems, so the phone player sheet registers this whenever it is composed, collapsed too.
     */
    var removeAt: ((Int) -> Unit)? = null

    /** Composed hosts; only the first one shows the sheet, so it never opens twice. */
    internal val hosts = mutableStateListOf<Any>()

    /** Starts a fresh mix and keeps it going. [onEmpty] runs when our engine has nothing to play. */
    fun play(controller: PlaybackController, mixTitle: String, onEmpty: (suspend () -> List<SongItem>)? = null, onNothing: () -> Unit = {}) {
        scope.launch {
            val songs = TurtleBridge.infinityMix().ifEmpty { onEmpty?.invoke().orEmpty() }
            if (songs.isEmpty()) return@launch onNothing()
            ShellQueue.play(controller, songs, mixTitle)
            watch(controller, mixTitle)
        }
    }

    /** True while the queue playing is the Infinity Mix. */
    fun isActive(controller: PlaybackController): Boolean = title != null && ShellQueue.activeTitle(controller.state.value) == title

    private fun watch(controller: PlaybackController, mixTitle: String) {
        title = mixTitle
        watcher?.cancel()
        watcher = scope.launch {
            // Let the opening queue land before judging how much is left.
            delay(SETTLE_MS)
            controller.state
                .map { s -> Triple(ShellQueue.activeTitle(s) == mixTitle, s.queue.size, s.currentQueueIndex) }
                .distinctUntilChanged()
                .collect { (active, size, index) ->
                    if (!active) {
                        title = null
                        watcher?.cancel()
                        return@collect
                    }
                    if (size - index - 1 < REFILL_AT) refill(controller)
                }
        }
    }

    private suspend fun refill(controller: PlaybackController) {
        if (filling) return
        filling = true
        try {
            val queued = controller.state.value.queue.mapTo(HashSet()) { it.id }
            val more = TurtleBridge.infinityNext(BATCH, queued)
            if (more.isNotEmpty() && isActive(controller)) controller.addToQueue(more)
        } finally {
            filling = false
        }
    }

    /**
     * After the dial or the picks change: drops the mix songs after the next two and refills them
     * with the new blend, so steering is heard within a song. Songs the listener added stay (the
     * bridge decides which go). Returns false when no mix is playing.
     */
    fun resteer(controller: PlaybackController): Boolean {
        if (!isActive(controller)) return false
        scope.launch {
            val s = controller.state.value
            removeQueued(controller, TurtleBridge.infinityResteerRemovals(s.queue.map { it.id }, s.currentQueueIndex))
            delay(RESTEER_SETTLE_MS)
            refill(controller)
        }
        return true
    }

    /** Saves the picks, then starts the mix or re-mixes what is queued. */
    fun applyPicks(controller: PlaybackController, genres: List<String>, mixTitle: String, playing: Boolean) {
        scope.launch {
            TurtleBridge.setInfinityGenres(genres)
            if (!playing || !resteer(controller)) play(controller, mixTitle)
        }
    }

    /** Forgets the mix's tuning, outliving the sheet that asked. */
    fun startFresh() {
        scope.launch { TurtleBridge.infinityStartFresh() }
    }

    /**
     * Removes queue indices, highest first. Desktop's and Android's controllers don't implement
     * removeQueueItems, so desktop goes through its player state (registered at startup) and phones
     * through [removeAt]; iOS's controller removes them itself.
     */
    private fun removeQueued(controller: PlaybackController, indices: Set<Int>) {
        if (indices.isEmpty()) return
        val descending = indices.sortedDescending()
        val desktopRemove = TurtleUiSlots.desktopExtras?.removeQueueIndex
        when {
            desktopRemove != null -> descending.forEach(desktopRemove)
            controller.removeQueueItems(indices) != null -> Unit
            else -> removeAt?.let { remove -> descending.forEach(remove) }
        }
    }

    private const val SETTLE_MS = 1_500L
    private const val RESTEER_SETTLE_MS = 300L
    private const val REFILL_AT = 3
    private const val BATCH = 6
}

/** Shows the Infinity sheet when it is open; put one in every top-level surface. */
@Composable
internal fun InfinitySheetHost() {
    val token = remember { Any() }
    DisposableEffect(token) {
        ShellInfinity.hosts.add(token)
        onDispose { ShellInfinity.hosts.remove(token) }
    }
    if (ShellInfinity.sheetOpen && ShellInfinity.hosts.firstOrNull() === token) ShellInfinitySheet()
}

/** "NEW · HIP-HOP" under a mix song's title: why the mix picked it, so the dial's effect is visible. */
@Composable
internal fun InfinityWhyLabel(songId: String, modifier: Modifier = Modifier, color: Color = Shell.colors.accent) {
    val state by TurtleBridge.infinity.collectAsState()
    val why = state.why[songId] ?: return
    val text = when (why) {
        InfinityWhy.Familiar -> stringResource(Res.string.shell_inf_why_familiar)
        InfinityWhy.ForYou -> stringResource(Res.string.shell_inf_why_for_you)
        is InfinityWhy.Genre -> stringResource(Res.string.shell_inf_why_genre, genreName(why.id).uppercase())
        is InfinityWhy.MoreLike -> stringResource(Res.string.shell_inf_why_more_like, why.title.uppercase())
    }
    IndexLabel(text, modifier, color = color)
}

@Composable
internal fun genreName(id: String): String = genreRes(id)?.let { stringResource(it) } ?: id

private fun genreRes(id: String): StringResource? = when (id) {
        "pop" -> Res.string.shell_genre_pop
        "hiphop" -> Res.string.shell_genre_hiphop
        "rnb" -> Res.string.shell_genre_rnb
        "rock" -> Res.string.shell_genre_rock
        "indie" -> Res.string.shell_genre_indie
        "alternative" -> Res.string.shell_genre_alternative
        "electronic" -> Res.string.shell_genre_electronic
        "house" -> Res.string.shell_genre_house
        "techno" -> Res.string.shell_genre_techno
        "dance" -> Res.string.shell_genre_dance
        "lofi" -> Res.string.shell_genre_lofi
        "jazz" -> Res.string.shell_genre_jazz
        "soul" -> Res.string.shell_genre_soul
        "funk" -> Res.string.shell_genre_funk
        "classical" -> Res.string.shell_genre_classical
        "country" -> Res.string.shell_genre_country
        "latin" -> Res.string.shell_genre_latin
        "reggaeton" -> Res.string.shell_genre_reggaeton
        "afrobeats" -> Res.string.shell_genre_afrobeats
        "kpop" -> Res.string.shell_genre_kpop
        "metal" -> Res.string.shell_genre_metal
        "punk" -> Res.string.shell_genre_punk
        "folk" -> Res.string.shell_genre_folk
        "blues" -> Res.string.shell_genre_blues
        "reggae" -> Res.string.shell_genre_reggae
        "ambient" -> Res.string.shell_genre_ambient
        "bollywood" -> Res.string.shell_genre_bollywood
        "punjabi" -> Res.string.shell_genre_punjabi
        "chill" -> Res.string.shell_genre_chill
        "focus" -> Res.string.shell_genre_focus
        "workout" -> Res.string.shell_genre_workout
        "party" -> Res.string.shell_genre_party
        "sleep" -> Res.string.shell_genre_sleep
        "romance" -> Res.string.shell_genre_romance
        "sad" -> Res.string.shell_genre_sad
        "feelgood" -> Res.string.shell_genre_feelgood
        "throwback" -> Res.string.shell_genre_throwback
        "roadtrip" -> Res.string.shell_genre_roadtrip
    else -> null
}

/** The Infinity sheet: genres and moods to put in, the Steer dial, and Start or Update. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShellInfinitySheet() = ShellTheme {
    val c = Shell.colors
    val controller = LocalPlaybackController.current
    val state by TurtleBridge.infinity.collectAsState()
    val mixTitle = stringResource(Res.string.shell_infinity_title)
    // The listener's picks while the sheet is open; saved on Start or Update.
    var picks by remember(state.configured) { mutableStateOf(state.genres.toSet()) }
    val playing = ShellQueue.activeTitle(controller.state.value) == mixTitle
    val close = { ShellInfinity.sheetOpen = false }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            ShellBottomSheet(visible = true, onDismiss = close) {
                Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        InfinityCube(emptyList(), playing = playing, modifier = Modifier.width(56.dp))
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.shell_inf_sheet_title), style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.85f), color = c.ink)
                            Text(stringResource(Res.string.shell_inf_sheet_body), style = Shell.type.bodySmall, color = c.ink2)
                        }
                    }
                    TuningLine(state.likes, state.passes, state.weights, picks)
                    Spacer(Modifier.height(16.dp))
                    IndexLabel(stringResource(Res.string.shell_inf_genres))
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(InfinityGenres.all.filter { !it.mood }.map { it.id }, picks) { id -> picks = if (id in picks) picks - id else picks + id }
                    Spacer(Modifier.height(16.dp))
                    IndexLabel(stringResource(Res.string.shell_inf_moods))
                    Spacer(Modifier.height(8.dp))
                    ChipFlow(InfinityGenres.all.filter { it.mood }.map { it.id }, picks) { id -> picks = if (id in picks) picks - id else picks + id }
                    SteerCard(c.surface2, modifier = Modifier.padding(top = 12.dp))
                    if (state.configured || state.likes + state.passes > 0) StartFresh { picks = emptySet() }
                    if (picks.isEmpty()) {
                        Text(stringResource(Res.string.shell_inf_none_picked), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(top = 10.dp, start = 4.dp))
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (picks.isNotEmpty()) {
                            Box(
                                Modifier.height(52.dp).clip(CircleShape).shellPressable({ picks = emptySet() }, focusShape = CircleShape).padding(horizontal = 16.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text(stringResource(Res.string.shell_inf_clear), style = Shell.type.body, color = c.ink2) }
                        }
                        val label = stringResource(if (playing) Res.string.shell_inf_update else Res.string.shell_inf_start)
                        Box(
                            Modifier.weight(1f).height(52.dp)
                                .shellPressable({
                                    val chosen = InfinityGenres.all.map { it.id }.filter { it in picks }
                                    close()
                                    ShellInfinity.applyPicks(controller, chosen, mixTitle, playing)
                                }, label = label, focusShape = CircleShape)
                                .drawBehind { drawRoundRect(c.accent, cornerRadius = CornerRadius(size.height / 2)) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Glyph(Glyphs.Infinity, c.onAccent, 20.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(label, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = c.onAccent)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TuningLine(likes: Int, passes: Int, weights: Map<String, Double>, picks: Set<String>) {
    val c = Shell.colors
    val leaning = weights.filter { it.key in picks && it.value >= LEANING_AT }.entries.sortedByDescending { it.value }.take(2).map { it.key }
    val parts = buildList {
        if (likes + passes > 0) add(stringResource(Res.string.shell_inf_tuned, likes, passes))
        if (leaning.isNotEmpty()) add(stringResource(Res.string.shell_inf_leaning, leaning.map { genreName(it) }.joinToString(", ")))
    }
    AnimatedVisibility(parts.isNotEmpty(), enter = fadeIn(ShellMotion.emphasized()), exit = fadeOut(ShellMotion.exit())) {
        Row(Modifier.padding(top = 12.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.12f)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Glyph(Glyphs.Check, c.accent, 14.dp)
            Spacer(Modifier.width(6.dp))
            Text(parts.joinToString(" · "), style = Shell.type.bodySmall, color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipFlow(ids: List<String>, picks: Set<String>, onToggle: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ids.forEach { id -> GenreChip(genreName(id), id in picks) { onToggle(id) } }
    }
}

@Composable
private fun GenreChip(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Shell.colors
    val bg by animateColorAsState(if (on) c.accent else c.surface2, ShellMotion.emphasized())
    val fg by animateColorAsState(if (on) c.onAccent else c.ink, ShellMotion.emphasized())
    Row(
        Modifier.height(36.dp).clip(CircleShape).background(bg).border(1.dp, if (on) Color.Transparent else c.hairline, CircleShape)
            .shellPressable(onClick, label = label, focusShape = CircleShape).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(on) {
            Row {
                Glyph(Glyphs.Check, fg, 14.dp)
                Spacer(Modifier.size(6.dp))
            }
        }
        Text(label, style = Shell.type.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = fg)
    }
}

private const val LEANING_AT = 1.3

/**
 * The Infinity Mix's only two reactions: thumbs down skips the song and steers away from it, thumbs
 * up likes it (Liked Songs) and pulls more like it into the mix. Both retune the mix at once.
 */
@Composable
internal fun InfinityThumbs(
    song: SongItem,
    controller: PlaybackController,
    tint: Color,
    size: androidx.compose.ui.unit.Dp = 48.dp,
    iconSize: androidx.compose.ui.unit.Dp = 24.dp,
    onFeedback: (liked: Boolean) -> Unit = {},
) {
    val c = Shell.colors
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val liked = com.metrolist.shared.state.SongLikeState.isLiked(song.id)
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    GlyphButton(Glyphs.ThumbDown, stringResource(Res.string.shell_inf_thumb_down), {
        haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Reject)
        onFeedback(false)
        ShellActions.lessLikeThis(controller, song)
    }, tint, size = size, iconSize = iconSize)
    Box(Modifier.graphicsLayerScale(pop.value)) {
        GlyphButton(Glyphs.ThumbUp, stringResource(Res.string.shell_inf_thumb_up), {
            haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.Confirm)
            val now = com.metrolist.shared.state.SongLikeState.toggleLike(song)
            if (now) scope.launch { pop.snapTo(0.8f); pop.animateTo(1f, ShellMotion.spring()) }
            onFeedback(now)
        }, if (liked) c.accent else tint, size = size, iconSize = iconSize, dot = liked, state = onOffState(liked))
    }
}

private fun Modifier.graphicsLayerScale(s: Float) = this.graphicsLayer { scaleX = s; scaleY = s }

/** Add to playlist in one tap, from any player surface. */
@Composable
internal fun AddToPlaylistButton(song: SongItem, onAdd: (SongItem) -> Unit, tint: Color, size: androidx.compose.ui.unit.Dp = 48.dp, iconSize: androidx.compose.ui.unit.Dp = 22.dp) {
    GlyphButton(Glyphs.PlaylistAdd, stringResource(Res.string.shell_add_to_playlist), { onAdd(song) }, tint, size = size, iconSize = iconSize)
}

/** "Start fresh" with a second tap to confirm, so nobody wipes their tuning by accident. */
@Composable
private fun StartFresh(onDone: () -> Unit) {
    val c = Shell.colors
    var armed by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(armed) { if (armed) { delay(4_000); armed = false } }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) {
        Row(
            Modifier.clip(CircleShape).shellPressable({
                if (!armed) armed = true else {
                    armed = false
                    ShellInfinity.startFresh()
                    onDone()
                }
            }, focusShape = CircleShape).padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.Repeat, if (armed) c.danger else c.ink2, 16.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(if (armed) Res.string.shell_inf_fresh_confirm else Res.string.shell_inf_fresh),
                style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = if (armed) c.danger else c.ink,
            )
        }
        Text(stringResource(Res.string.shell_inf_fresh_body), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.padding(start = 4.dp))
    }
}
