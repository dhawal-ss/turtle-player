package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.InfinityCube
import app.turtleplayer.ui.components.MixCard
import app.turtleplayer.ui.components.PlayPauseButton
import app.turtleplayer.ui.components.SectionHeader
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.ShortcutTile
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_and_more
import app.turtleplayer.ui.generated.resources.shell_filter_all
import app.turtleplayer.ui.generated.resources.shell_filter_mixes
import app.turtleplayer.ui.generated.resources.shell_filter_music
import app.turtleplayer.ui.generated.resources.shell_home_infinity_empty
import app.turtleplayer.ui.generated.resources.shell_home_loading
import app.turtleplayer.ui.generated.resources.shell_home_offline_body
import app.turtleplayer.ui.generated.resources.shell_home_offline_title
import app.turtleplayer.ui.generated.resources.shell_infinity_label
import app.turtleplayer.ui.generated.resources.shell_infinity_subtitle
import app.turtleplayer.ui.generated.resources.shell_infinity_tagline
import app.turtleplayer.ui.generated.resources.shell_infinity_title
import app.turtleplayer.ui.generated.resources.shell_made_for_you
import app.turtleplayer.ui.generated.resources.shell_pause_infinity
import app.turtleplayer.ui.generated.resources.shell_play_infinity
import app.turtleplayer.ui.theme.LocalShellGround
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfCache
import com.metrolist.shared.ui.screens.HomeShelfStyle
import com.metrolist.shared.viewmodels.HomePaginationTrigger
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** How many of upstream's own rows the calm Home keeps below ours. */
private const val UPSTREAM_ROWS_ALL = 3
private val MIX_TINTS = listOf(0xFFC4577A, 0xFF3E6FD8, 0xFF2E9E7A, 0xFFE8B04B, 0xFF8A4FD1, 0xFFD9704A, 0xFF2E7E9E).map { Color(it) }

/** "Made for you" turns from a scrolling row into a grid of this many columns on wide desktop windows. */
private const val MIX_GRID_COLUMNS = 5
private const val NOTICE_MS = 2800L

private enum class HomeFilter { ALL, MUSIC, MIXES }

/** One of our mixes with what its card needs, worked out once per shelf list rather than per frame. */
private class MixEntry(val shelf: HomeShelf, val songs: List<SongItem>, val names: String, val tint: Color)

/** Everything Home derives from the shelves; rebuilt only when the shelves or the feed change. */
private class HomeLists(
    val mixes: List<MixEntry>,
    val upstreamRows: List<HomeShelf>,
    val shortcuts: List<YTItem>,
    val shortcutSongs: List<SongItem>,
    val artUrls: List<String?>,
) {
    companion object {
        fun of(shelves: List<HomeShelf>, s: HomeUiState): HomeLists {
            val ours = shelves.filter { it.key.startsWith("turtle:") }.distinctBy { it.key }
            val mixes = ours.mapIndexed { i, shelf ->
                val songs = shelf.items.filterIsInstance<SongItem>()
                val names = songs.flatMap { song -> song.artists.map { it.name } }.distinct().take(3).joinToString(", ")
                MixEntry(shelf, songs, names, MIX_TINTS[i % MIX_TINTS.size])
            }
            val speedDial = shelves.firstOrNull { it.style == HomeShelfStyle.SPEED_DIAL }?.items.orEmpty()
            val upstreamRows = shelves
                .filter { !it.key.startsWith("turtle:") && it.style != HomeShelfStyle.SPEED_DIAL && it.items.isNotEmpty() }
                .distinctBy { it.key }
            val shortcuts = (speedDial + s.keepListening + s.quickPicks).distinctBy { it.id }.take(7)
            val artUrls = (ours.flatMap { it.items } + s.quickPicks).map { it.displayThumbnailUrl }.distinct().take(9)
            return HomeLists(mixes, upstreamRows, shortcuts, shortcuts.filterIsInstance<SongItem>(), artUrls)
        }
    }
}

/**
 * Shell's Home, in Spotify's familiar order: filter chips, a 2×4 grid of shortcuts (Infinity Mix
 * first), the Infinity card, our mixes, then a few of upstream's rows. Upstream's callbacks are kept,
 * so navigation, menus and the like behave exactly as before.
 */
@Composable
fun ShellHome(slot: HomeSlot) = ShellTheme {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    // Without the position tick: Home only cares about song, queue and play/pause changes.
    val calm = playback.rememberCalmState()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableIntStateOf(HomeFilter.ALL.ordinal) }
    val localCache = remember { HomeShelfCache() }
    val infinityTitle = stringResource(Res.string.shell_infinity_title)
    // The one place Home decides "the Infinity Mix is what's playing". Read it only inside leaves.
    val infinityPlaying by remember(infinityTitle) {
        derivedStateOf { calm.value.isPlaying && ShellQueue.activeTitle(calm.value) == infinityTitle }
    }
    val emptyMessage = stringResource(Res.string.shell_home_infinity_empty)
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    fun playInfinity() {
        if (ShellQueue.activeTitle(playback.state.value) == infinityTitle) return playback.togglePlayPause()
        scope.launch {
            val songs = TurtleBridge.infinityMix().ifEmpty { slot.uiState.quickPicks.shuffled() }
            if (songs.isNotEmpty()) ShellQueue.play(playback, songs, infinityTitle) else notice = emptyMessage
        }
    }

    val ground = LocalShellGround.current ?: c.ground
    val desktop = ShellPlatform.desktop
    SideEffect { if (desktop) ShellDesktop.home = slot }
    // Spotify's header glow: takes the color of the shortcut under the pointer, calm lime otherwise.
    var glowArt by remember { mutableStateOf<String?>(null) }
    var glowInfinity by remember { mutableStateOf(false) }
    val glowSeed = artworkTint(glowArt, 0.38f, base = ground)
    val glow by animateColorAsState(
        if (glowInfinity || glowArt == null) lerp(ground, c.accent, 0.10f) else glowSeed,
        tween(ShellMotion.TINT_MS, easing = ShellMotion.Emphasized),
    )
    val rise = rememberRiseIn(slot.listState)
    val current = filter
    LoadMoreNearEnd(slot, enabled = current == HomeFilter.MUSIC.ordinal)

    BoxWithConstraints(slot.modifier.fillMaxSize().background(ground)) {
        if (desktop) {
            val list = slot.listState
            // Scroll position and the glow color are read in the layer and draw phases, so neither
            // scrolling nor the tint animation recomposes Home.
            Box(
                Modifier.fillMaxWidth().height(300.dp)
                    .graphicsLayer { translationY = if (list.firstVisibleItemIndex > 0) -size.height else -list.firstVisibleItemScrollOffset.toFloat() }
                    .drawBehind { drawRect(Brush.verticalGradient(listOf(glow, ground))) },
            )
        }
        // Desktop passes no cache (upstream builds one inside HomeContent), so keep our own. build() is
        // not wrapped in remember: it memoizes upstream's part, and our shelves are Compose state read in it.
        val cache = slot.shelfCache ?: localCache
        val ui = slot.uiState
        val shelves = cache.build(ui, slot.text, maxWidth)
        val lists = remember(shelves, ui) { HomeLists.of(shelves, ui) }
        val noContent = lists.shortcuts.isEmpty() && lists.upstreamRows.isEmpty()
        val failed = noContent && !ui.error.isNullOrBlank()
        val waiting = noContent && !failed &&
            (ui.isLoading || ui.isContentLoading || ui.isAllContentPending || ui.isRestoringCache || (ui.data == null && ui.sections.isEmpty()))
        val wide = maxWidth > 840.dp
        val gridColumns = if (wide) 4 else 2
        val mixGrid = desktop && maxWidth - 16.dp >= 900.dp

        LazyColumn(
            state = slot.listState,
            contentPadding = PaddingValues(top = slot.contentPadding.calculateTopPadding() + 12.dp, bottom = slot.contentPadding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = if (desktop) 8.dp else 0.dp),
        ) {
            item(key = "chips") {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShellChip(stringResource(Res.string.shell_filter_all), current == HomeFilter.ALL.ordinal, { filter = HomeFilter.ALL.ordinal })
                    ShellChip(stringResource(Res.string.shell_filter_music), current == HomeFilter.MUSIC.ordinal, { filter = HomeFilter.MUSIC.ordinal })
                    ShellChip(stringResource(Res.string.shell_filter_mixes), current == HomeFilter.MIXES.ordinal, { filter = HomeFilter.MIXES.ordinal })
                }
                Spacer(Modifier.height(14.dp))
            }

            if (failed) {
                item(key = "offline") { OfflineCard(Modifier.riseIn(rise, "offline", 1)) }
            }

            if (waiting) {
                item(key = "shortcuts_loading") { PlaceholderShortcuts(gridColumns) }
                item(key = "infinity_loading") { PlaceholderInfinity(wide) }
            } else {
                item(key = "shortcuts") {
                    val now = calm.value
                    val tiles = remember(lists) { listOf<YTItem?>(null) + lists.shortcuts }
                    Column(Modifier.riseIn(rise, "shortcuts", 1).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        tiles.chunked(gridColumns).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { item ->
                                    if (item == null) {
                                        val playing = infinityPlaying
                                        ShortcutTile(
                                            title = infinityTitle,
                                            art = { m -> Box(m.background(c.accent), contentAlignment = Alignment.Center) { Glyph(Glyphs.Infinity, c.onAccent, 26.dp) } },
                                            playing = playing,
                                            onClick = ::playInfinity,
                                            modifier = Modifier.weight(1f),
                                            onPlay = if (playing) null else ::playInfinity,
                                            onHover = { if (it) { glowInfinity = true; glowArt = null } },
                                        )
                                    } else {
                                        ShortcutTile(
                                            title = item.title,
                                            art = { m -> ShellArt(item.displayThumbnailUrl, m, shape = RectangleShape) },
                                            playing = now.isPlaying && now.currentSong?.id == item.id,
                                            onClick = { slot.onItemClick(item) },
                                            modifier = Modifier.weight(1f),
                                            onPlay = (item as? SongItem)?.let { s -> { playback.play(s, lists.shortcutSongs) } },
                                            onHover = { if (it) { glowInfinity = false; glowArt = item.displayThumbnailUrl } },
                                            onLongClick = slot.onItemLongClick?.let { f -> { f(item) } },
                                        )
                                    }
                                }
                                repeat(gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                item(key = "infinity") {
                    InfinityCard(lists.artUrls, infinityPlaying, wide, ::playInfinity, Modifier.riseIn(rise, "infinity", 2))
                }
            }

            if (current != HomeFilter.MUSIC.ordinal && lists.mixes.isNotEmpty()) {
                item(key = "made_for_you") {
                    Column(Modifier.riseIn(rise, "made_for_you", 3)) {
                        Spacer(Modifier.height(24.dp))
                        HomeSectionTitle(stringResource(Res.string.shell_made_for_you))
                        Spacer(Modifier.height(12.dp))
                        if (mixGrid) {
                            Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                lists.mixes.chunked(MIX_GRID_COLUMNS).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                        row.forEach { mix -> MixTile(mix, Modifier.weight(1f)) }
                                        repeat(MIX_GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        } else {
                            LazyRow(contentPadding = PaddingValues(horizontal = if (desktop) 6.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (desktop) 2.dp else 14.dp)) {
                                items(lists.mixes, key = { it.shelf.key }) { mix -> MixTile(mix, Modifier.width(if (wide) 180.dp else 142.dp)) }
                            }
                        }
                    }
                }
            }

            if (current != HomeFilter.MIXES.ordinal) {
                val rows = if (current == HomeFilter.MUSIC.ordinal) lists.upstreamRows else lists.upstreamRows.take(UPSTREAM_ROWS_ALL)
                rows.forEachIndexed { i, shelf ->
                    val key = "row:${shelf.key}"
                    item(key = key) { ItemRow(shelf, slot, wide, Modifier.riseIn(rise, key, 4 + i)) }
                }
                val retry = slot.onLoadMore
                if (current == HomeFilter.MUSIC.ordinal && ui.loadMoreError && retry != null) {
                    item(key = "load_more_retry") {
                        Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
                            Text(
                                slot.text.tryAgain, style = Shell.type.label, color = c.ink,
                                modifier = Modifier.clip(CircleShape).background(c.surface2).shellPressable(retry).padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        HomeNotice(notice, Modifier.align(Alignment.BottomCenter).padding(bottom = slot.contentPadding.calculateBottomPadding()))
    }
}

/** Upstream's paging, for the filter that shows all of its rows: load the next page near the end. */
@Composable
private fun LoadMoreNearEnd(slot: HomeSlot, enabled: Boolean) {
    val state by rememberUpdatedState(slot.uiState)
    val loadMore by rememberUpdatedState(slot.onLoadMore)
    if (!enabled || slot.onLoadMore == null) return
    val list = slot.listState
    LaunchedEffect(list, slot.uiState.selectedChip, slot.uiState.isRefreshing) {
        val trigger = HomePaginationTrigger()
        var revision = 0L
        var previous = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        snapshotFlow {
            val layout = list.layoutInfo
            val nearEnd = layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 3 } == true
            Triple(state, nearEnd, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        }.collect { (current, nearEnd, position) ->
            // Only a forward scroll asks for another page after the first, as upstream does.
            if (position.first > previous.first || (position.first == previous.first && position.second > previous.second)) revision++
            previous = position
            if (trigger.shouldLoad(current, nearEnd, revision)) loadMore?.invoke()
        }
    }
}

@Composable
private fun InfinityCard(artUrls: List<String?>, playing: Boolean, wide: Boolean, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    Row(
        modifier.padding(16.dp, 20.dp, 16.dp, 0.dp).fillMaxWidth()
            .clip(if (desktop) RoundedCornerShape(8.dp) else ShellShapes.Card)
            .background(if (desktop) c.surface2 else c.surface1)
            .padding(if (desktop) 24.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (desktop) 24.dp else 14.dp),
    ) {
        InfinityCube(artUrls, playing = playing, modifier = Modifier.width(if (wide) 132.dp else 84.dp))
        // On desktop the text hugs its content, so the play button follows it instead of being stranded
        // at the far edge of a wide window.
        Column(Modifier.weight(1f, fill = !desktop).then(if (desktop) Modifier.widthIn(max = 640.dp) else Modifier)) {
            IndexLabel(stringResource(Res.string.shell_infinity_label))
            Text(stringResource(Res.string.shell_infinity_tagline), style = if (wide) Shell.type.display.copy(fontSize = Shell.type.display.fontSize * 1.5f, lineHeight = Shell.type.display.lineHeight * 1.5f) else Shell.type.headline, color = c.ink)
            Text(stringResource(Res.string.shell_infinity_subtitle), style = Shell.type.bodySmall, color = c.ink2)
        }
        PlayPauseButton(
            playing = playing, onClick = onPlay, size = if (wide) 64.dp else 52.dp,
            background = c.accent, contentColor = c.onAccent,
            playLabel = stringResource(Res.string.shell_play_infinity), pauseLabel = stringResource(Res.string.shell_pause_infinity),
        )
    }
}

@Composable
private fun MixTile(mix: MixEntry, modifier: Modifier) {
    val playback = LocalPlaybackController.current
    MixCard(
        title = mix.shelf.title,
        subtitle = if (mix.names.isBlank()) mix.shelf.label.orEmpty() else stringResource(Res.string.shell_and_more, mix.names),
        artUrl = mix.songs.firstOrNull()?.displayThumbnailUrl,
        tint = mix.tint,
        onClick = { if (mix.songs.isNotEmpty()) ShellQueue.play(playback, mix.songs, mix.shelf.title) },
        modifier = modifier,
    )
}

/** Home's section titles: the shared header on phones, the larger 24 sp desktop title otherwise. */
@Composable
private fun HomeSectionTitle(title: String) {
    if (ShellPlatform.desktop) {
        Text(
            title, style = Shell.type.sectionTitle.copy(fontSize = 24.sp, lineHeight = 28.sp), color = Shell.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        )
    } else {
        SectionHeader(title)
    }
}

/** One of upstream's rows as Spotify-style cards: songs play in the row's context, the rest navigate. */
@Composable
private fun ItemRow(shelf: HomeShelf, slot: HomeSlot, wide: Boolean, modifier: Modifier) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    val unique = remember(shelf.items) { shelf.items.distinctBy { it.id } }
    val songs = remember(unique) { unique.filterIsInstance<SongItem>() }
    val desktop = ShellPlatform.desktop
    val art = if (wide) 168.dp else 136.dp
    Column(modifier) {
        Spacer(Modifier.height(24.dp))
        HomeSectionTitle(shelf.title)
        Spacer(Modifier.height(12.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = if (desktop) 6.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (desktop) 2.dp else 14.dp)) {
            items(unique, key = { it.id }) { item ->
                val round = item is ArtistItem
                val (hover, hovered) = rememberHover()
                val open: () -> Unit = { if (item is SongItem) playback.play(item, songs) else slot.onItemClick(item) }
                Column(
                    Modifier.hoverable(hover)
                        .shellPressable(open, label = item.title, onLongClick = slot.onItemLongClick?.let { f -> { f(item) } })
                        .cardHover(hovered).width(art),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box {
                        ShellArt(item.displayThumbnailUrl, Modifier.size(art), shape = if (round) CircleShape else ShellShapes.Art)
                        if (item is SongItem) HoverPlayButton(hovered, 44.dp, { playback.play(item, songs) }, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                    }
                    Text(item.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    subtitleOf(item)?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
    }
}

/** Shown when YouTube Music can't be reached and there is nothing cached: Infinity still works. */
@Composable
private fun OfflineCard(modifier: Modifier) {
    val c = Shell.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp).fillMaxWidth()
            .clip(shape).background(c.surface1).border(1.dp, c.hairline, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(Res.string.shell_home_offline_title), style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = c.ink)
        Text(stringResource(Res.string.shell_home_offline_body), style = Shell.type.bodySmall, color = c.ink2)
    }
}

@Composable
private fun PlaceholderShortcuts(columns: Int) {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    val label = stringResource(Res.string.shell_home_loading)
    Column(
        Modifier.padding(horizontal = 16.dp).semantics { contentDescription = label }.graphicsLayer { alpha = pulse.value },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(8 / columns) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(columns) { Box(Modifier.weight(1f).height(56.dp).clip(ShellShapes.Tile).background(c.surface2)) }
            }
        }
    }
}

@Composable
private fun PlaceholderInfinity(wide: Boolean) {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    val desktop = ShellPlatform.desktop
    Box(
        Modifier.padding(16.dp, 20.dp, 16.dp, 0.dp).fillMaxWidth().height(if (wide) 180.dp else 112.dp)
            .graphicsLayer { alpha = pulse.value }
            .clip(if (desktop) RoundedCornerShape(8.dp) else ShellShapes.Card)
            .background(c.surface2),
    )
}

/** A short note that rises from the bottom, e.g. when the Infinity Mix has nothing to play yet. */
@Composable
private fun HomeNotice(message: String?, modifier: Modifier) {
    val c = Shell.colors
    // Keep the last text while the note animates away, so it doesn't blank out mid-exit.
    val last = remember { arrayOf("") }
    if (message != null) last[0] = message
    AnimatedVisibility(
        visible = message != null,
        modifier = modifier,
        enter = slideInVertically(ShellMotion.spring()) { it } + fadeIn(ShellMotion.emphasized()),
        exit = slideOutVertically(ShellMotion.exit()) { it } + fadeOut(ShellMotion.exit()),
    ) {
        Row(
            Modifier.widthIn(max = 520.dp).padding(12.dp).clip(RoundedCornerShape(10.dp)).background(c.surface3)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.Infinity, c.accent, 18.dp)
            Spacer(Modifier.width(12.dp))
            Text(last[0], style = Shell.type.label, color = c.ink)
        }
    }
}

/** Placeholder pulse: alpha 0.5 ↔ 1 over 900 ms. Read it in a graphicsLayer so only drawing updates. */
@Composable
internal fun rememberShellPulse(): State<Float> {
    val transition = rememberInfiniteTransition()
    return transition.animateFloat(0.5f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse))
}

/**
 * Remembers which items have already risen in (H-Motion), so a shelf animates once: scrolling it
 * away and back, or anything first shown by scrolling, appears in place.
 */
internal class RiseIn(var settled: Boolean) {
    val seen = HashSet<Any>()
}

private const val RISE_MAX_STAGGERED = 12

@Composable
internal fun rememberRiseIn(listState: LazyListState): RiseIn {
    val rise = remember(listState) { RiseIn(settled = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0) }
    LaunchedEffect(rise) {
        if (rise.settled) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0 }.first { it }
        rise.settled = true
    }
    return rise
}

/** Fade in and rise 10 dp, 300 ms Emphasized, [index] × 40 ms after the first item; once per [key]. */
@Composable
internal fun Modifier.riseIn(rise: RiseIn, key: Any, index: Int): Modifier {
    val animate = remember(rise, key) { rise.seen.add(key) && !rise.settled }
    if (!animate) return this
    val progress = remember(rise, key) { Animatable(0f) }
    LaunchedEffect(progress) {
        val delayMs = index.coerceIn(0, RISE_MAX_STAGGERED) * ShellMotion.STAGGER_MS
        progress.animateTo(1f, tween(ShellMotion.EMPHASIZED_MS, delayMs, ShellMotion.Emphasized))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 10.dp.toPx()
    }
}

internal fun subtitleOf(item: YTItem): String? = when (item) {
    is SongItem -> item.artists.joinToString(", ") { it.name }
    is AlbumItem -> item.artists.joinToString(", ") { it.name }
    is PlaylistItem -> item.author
    else -> null
}
