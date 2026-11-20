package app.turtleplayer.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.InfinityCube
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.MixCard
import app.turtleplayer.ui.components.PlayPauseButton
import app.turtleplayer.ui.components.SectionHeader
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.ShortcutTile
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_and_more
import app.turtleplayer.ui.generated.resources.shell_filter_all
import app.turtleplayer.ui.generated.resources.shell_filter_mixes
import app.turtleplayer.ui.generated.resources.shell_filter_music
import app.turtleplayer.ui.generated.resources.shell_infinity_label
import app.turtleplayer.ui.generated.resources.shell_infinity_subtitle
import app.turtleplayer.ui.generated.resources.shell_infinity_tagline
import app.turtleplayer.ui.generated.resources.shell_infinity_title
import app.turtleplayer.ui.generated.resources.shell_made_for_you
import app.turtleplayer.ui.generated.resources.shell_pause_infinity
import app.turtleplayer.ui.generated.resources.shell_play_infinity
import app.turtleplayer.ui.theme.Shell
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** How many of upstream's own rows the calm Home keeps below ours. */
private const val UPSTREAM_ROWS_ALL = 3
private val MIX_TINTS = listOf(0xFFC4577A, 0xFF3E6FD8, 0xFF2E9E7A, 0xFFE8B04B, 0xFF8A4FD1, 0xFFD9704A, 0xFF2E7E9E).map { Color(it) }

private enum class HomeFilter { ALL, MUSIC, MIXES }

/**
 * Shell's Home, in Spotify's familiar order: filter chips, a 2×4 grid of shortcuts (Infinity Mix
 * first), the Infinity card, our mixes, then a few of upstream's rows. Upstream's callbacks are kept,
 * so navigation, menus and the like behave exactly as before.
 */
@Composable
fun ShellHome(slot: HomeSlot) = ShellTheme {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    val state by playback.state.collectAsState()
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableIntStateOf(HomeFilter.ALL.ordinal) }
    val localCache = remember { HomeShelfCache() }
    val infinityTitle = stringResource(Res.string.shell_infinity_title)
    val infinityPlaying = state.isPlaying && state.queueTitle == infinityTitle

    fun playInfinity() {
        if (state.queueTitle == infinityTitle) return playback.togglePlayPause()
        scope.launch {
            val songs = TurtleBridge.infinityMix().ifEmpty { slot.uiState.quickPicks.shuffled() }
            if (songs.isNotEmpty()) playback.playQueue(songs, queueTitle = infinityTitle)
        }
    }

    BoxWithConstraints(slot.modifier.fillMaxSize().background(c.ground)) {
        // Desktop passes no cache (upstream builds one inside HomeContent), so keep our own. Not wrapped in
        // remember: the cache memoizes upstream's part, and our shelves are Compose state read in build().
        val cache = slot.shelfCache ?: localCache
        val shelves = cache.build(slot.uiState, slot.text, maxWidth)
        val ours = shelves.filter { it.key.startsWith("turtle:") }
        val speedDial = shelves.firstOrNull { it.style == HomeShelfStyle.SPEED_DIAL }?.items.orEmpty()
        val upstreamRows = shelves.filter { !it.key.startsWith("turtle:") && it.style != HomeShelfStyle.SPEED_DIAL && it.items.isNotEmpty() }
        val shortcuts = (speedDial + slot.uiState.keepListening + slot.uiState.quickPicks).distinctBy { it.id }.take(7)
        val wide = maxWidth > 840.dp
        val gridColumns = if (wide) 4 else 2
        val current = filter

        LazyColumn(
            state = slot.listState,
            contentPadding = PaddingValues(top = slot.contentPadding.calculateTopPadding() + 12.dp, bottom = slot.contentPadding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "chips") {
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShellChip(stringResource(Res.string.shell_filter_all), current == HomeFilter.ALL.ordinal, { filter = HomeFilter.ALL.ordinal })
                    ShellChip(stringResource(Res.string.shell_filter_music), current == HomeFilter.MUSIC.ordinal, { filter = HomeFilter.MUSIC.ordinal })
                    ShellChip(stringResource(Res.string.shell_filter_mixes), current == HomeFilter.MIXES.ordinal, { filter = HomeFilter.MIXES.ordinal })
                }
                Spacer(Modifier.height(14.dp))
            }

            item(key = "shortcuts") {
                val tiles = listOf<YTItem?>(null) + shortcuts
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(gridColumns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { item ->
                                if (item == null) {
                                    ShortcutTile(
                                        title = infinityTitle,
                                        art = { m -> Box(m.background(c.accent), contentAlignment = Alignment.Center) { Glyph(Glyphs.Infinity, c.onAccent, 26.dp) } },
                                        playing = infinityPlaying,
                                        onClick = ::playInfinity,
                                        modifier = Modifier.weight(1f),
                                    )
                                } else {
                                    ShortcutTile(
                                        title = item.title,
                                        art = { m -> ShellArt(item.displayThumbnailUrl, m, shape = RectangleShape) },
                                        playing = state.isPlaying && state.currentSong?.id == item.id,
                                        onClick = { slot.onItemClick(item) },
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            repeat(gridColumns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            item(key = "infinity") {
                val artUrls = (ours.flatMap { it.items } + slot.uiState.quickPicks).map { it.displayThumbnailUrl }.distinct().take(9)
                Row(
                    Modifier.padding(16.dp, 20.dp, 16.dp, 0.dp).fillMaxWidth().clip(ShellShapes.Card).background(c.surface1).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    InfinityCube(artUrls, playing = infinityPlaying, modifier = Modifier.width(if (wide) 132.dp else 84.dp))
                    Column(Modifier.weight(1f)) {
                        IndexLabel(stringResource(Res.string.shell_infinity_label))
                        Text(stringResource(Res.string.shell_infinity_tagline), style = if (wide) Shell.type.display.copy(fontSize = Shell.type.display.fontSize * 1.5f, lineHeight = Shell.type.display.lineHeight * 1.5f) else Shell.type.headline, color = c.ink)
                        Text(stringResource(Res.string.shell_infinity_subtitle), style = Shell.type.bodySmall, color = c.ink2)
                    }
                    PlayPauseButton(
                        playing = infinityPlaying, onClick = ::playInfinity, size = if (wide) 64.dp else 52.dp,
                        background = c.accent, contentColor = c.onAccent,
                        playLabel = stringResource(Res.string.shell_play_infinity), pauseLabel = stringResource(Res.string.shell_pause_infinity),
                    )
                }
            }

            if (current != HomeFilter.MUSIC.ordinal && ours.isNotEmpty()) {
                item(key = "made_for_you") {
                    Spacer(Modifier.height(24.dp))
                    SectionHeader(stringResource(Res.string.shell_made_for_you))
                    Spacer(Modifier.height(12.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(ours, key = { it.key }) { shelf ->
                            val index = ours.indexOf(shelf)
                            val songs = shelf.items.filterIsInstance<SongItem>()
                            val names = songs.flatMap { it.artists.map { a -> a.name } }.distinct().take(3).joinToString(", ")
                            MixCard(
                                title = shelf.title,
                                subtitle = if (names.isBlank()) shelf.label.orEmpty() else stringResource(Res.string.shell_and_more, names),
                                artUrl = songs.firstOrNull()?.displayThumbnailUrl,
                                tint = MIX_TINTS[index % MIX_TINTS.size],
                                onClick = { if (songs.isNotEmpty()) playback.playQueue(songs, queueTitle = shelf.title) },
                                modifier = Modifier.width(if (wide) 180.dp else 142.dp),
                            )
                        }
                    }
                }
            }

            if (current != HomeFilter.MIXES.ordinal) {
                val rows = if (current == HomeFilter.MUSIC.ordinal) upstreamRows else upstreamRows.take(UPSTREAM_ROWS_ALL)
                rows.forEach { shelf ->
                    item(key = "row:${shelf.key}") { ItemRow(shelf, slot, wide) }
                }
            }
        }
    }
}

/** One of upstream's rows as Spotify-style cards: songs play in the row's context, the rest navigate. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemRow(shelf: HomeShelf, slot: HomeSlot, wide: Boolean) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    val songs = shelf.items.filterIsInstance<SongItem>()
    Spacer(Modifier.height(24.dp))
    SectionHeader(shelf.title)
    Spacer(Modifier.height(12.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(shelf.items, key = { it.id }) { item ->
            val round = item is ArtistItem
            Column(
                Modifier.width(if (wide) 168.dp else 136.dp)
                    .combinedClickable(
                        onClick = { if (item is SongItem) playback.play(item, songs) else slot.onItemClick(item) },
                        onLongClick = slot.onItemLongClick?.let { { it(item) } },
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShellArt(item.displayThumbnailUrl, Modifier.size(if (wide) 168.dp else 136.dp), shape = if (round) CircleShape else ShellShapes.Art)
                Text(item.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitleOf(item)?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

internal fun subtitleOf(item: YTItem): String? = when (item) {
    is SongItem -> item.artists.joinToString(", ") { it.name }
    is AlbumItem -> item.artists.joinToString(", ") { it.name }
    is PlaylistItem -> item.author
    else -> null
}
