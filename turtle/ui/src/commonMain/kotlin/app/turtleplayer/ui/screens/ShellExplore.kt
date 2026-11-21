package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.SectionHeader
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_charts
import app.turtleplayer.ui.generated.resources.shell_community
import app.turtleplayer.ui.generated.resources.shell_explore_label
import app.turtleplayer.ui.generated.resources.shell_explore_subtitle
import app.turtleplayer.ui.generated.resources.shell_explore_title
import app.turtleplayer.ui.generated.resources.shell_explore_waiting
import app.turtleplayer.ui.generated.resources.shell_moods_genres
import app.turtleplayer.ui.generated.resources.shell_new_releases
import app.turtleplayer.ui.generated.resources.shell_trending
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellShapes
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.ui.playback.LocalPlaybackController
import org.jetbrains.compose.resources.stringResource

/** Tile colors for moods and genres, cycled in order so neighbours never share a hue. */
internal val GenreTints = listOf(0xFF8A4FD1, 0xFFD9704A, 0xFF2E7E9E, 0xFFC2412E, 0xFF2E9E7A, 0xFF5B4BD8, 0xFFB7792B, 0xFFC4577A, 0xFF3E6FD8, 0xFF6B8E23, 0xFFAD3E8C).map { Color(it) }

/**
 * Explore: where you wander outside your taste. Home stays "yours"; this page gathers YouTube Music's
 * moods and genres, new releases, charts and community playlists. Items open through upstream's
 * opener, exactly as from Home.
 */
@Composable
internal fun ShellExplorePage(home: HomeSlot?, modifier: Modifier = Modifier) {
    val c = Shell.colors
    Box(modifier.background(c.panel)) {
        if (home == null) {
            Text(stringResource(Res.string.shell_explore_waiting), style = Shell.type.body, color = c.ink2, modifier = Modifier.align(Alignment.Center))
            return@Box
        }
        val s = home.uiState
        val genres = s.moodsAndGenres.distinctBy { it.id to it.params }.map { BrowseItem(id = it.id, title = it.name, thumbnail = it.thumbnail, params = it.params) }
        val listState = rememberLazyListState()
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val columns = when {
                maxWidth > 1300.dp -> 6
                maxWidth > 1000.dp -> 5
                maxWidth > 760.dp -> 4
                else -> 2
            }
            // A cool, out-of-taste glow (the opposite of Home's lime): the page reads as "somewhere else".
            Box(Modifier.fillMaxWidth().height(320.dp).background(Brush.verticalGradient(listOf(lerp(c.panel, Color(0xFF3E6FD8), 0.22f), c.panel))))
            LazyColumn(state = listState, contentPadding = PaddingValues(top = 28.dp, bottom = 32.dp), modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                item(key = "header") {
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        IndexLabel(stringResource(Res.string.shell_explore_label))
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(Res.string.shell_explore_title), style = Shell.type.display.copy(fontSize = Shell.type.display.fontSize * 1.6f, lineHeight = Shell.type.display.lineHeight * 1.6f), color = c.ink)
                        Spacer(Modifier.height(6.dp))
                        Text(stringResource(Res.string.shell_explore_subtitle), style = Shell.type.body, color = c.ink2)
                    }
                }
                if (genres.isNotEmpty()) {
                    item(key = "genres") {
                        Spacer(Modifier.height(28.dp))
                        SectionHeader(stringResource(Res.string.shell_moods_genres))
                        Spacer(Modifier.height(12.dp))
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            genres.take(columns * 3).withIndex().chunked(columns).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    row.forEach { (i, g) -> GenreTile(g, GenreTints[i % GenreTints.size], { home.onItemClick(g) }, Modifier.weight(1f)) }
                                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        }
                    }
                }
                if (s.newReleases.isNotEmpty()) item(key = "new") { ExploreRow(stringResource(Res.string.shell_new_releases), s.newReleases, home) }
                if (s.trending.isNotEmpty()) item(key = "trending") { ChartList(stringResource(Res.string.shell_trending), s.trending.take(10), home, twoColumns = maxWidth > 1000.dp) }
                if (s.albumCharts.isNotEmpty()) item(key = "charts") { ExploreRow(stringResource(Res.string.shell_charts), s.albumCharts, home) }
                if (s.communityPlaylist.isNotEmpty()) item(key = "community") { ExploreRow(stringResource(Res.string.shell_community), s.communityPlaylist, home) }
            }
        }
    }
}

@Composable
private fun GenreTile(item: BrowseItem, base: Color, onClick: () -> Unit, modifier: Modifier) {
    val (hover, hovered) = rememberHover()
    val tint by animateColorAsState(if (hovered) lerp(base, Color.White, 0.08f) else base)
    Box(modifier.height(108.dp).clip(RoundedCornerShape(8.dp)).background(tint).hoverable(hover).shellPressable(onClick, label = item.title).padding(14.dp)) {
        Text(item.title, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.align(Alignment.BottomEnd).offset(22.dp, 16.dp).rotate(if (hovered) 18f else 25f).size(70.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0x400B0B0C)),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ExploreRow(title: String, items: List<YTItem>, home: HomeSlot) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    val songs = items.filterIsInstance<SongItem>()
    Spacer(Modifier.height(28.dp))
    SectionHeader(title)
    Spacer(Modifier.height(10.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items, key = { it.id }) { item ->
            val (hover, hovered) = rememberHover()
            Column(
                Modifier.hoverable(hover).cardHover(hovered).width(168.dp)
                    .combinedClickable(onClick = { if (item is SongItem) playback.play(item, songs) else home.onItemClick(item) }, onLongClick = home.onItemLongClick?.let { { it(item) } }),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    ShellArt(item.displayThumbnailUrl, Modifier.size(168.dp), shape = if (item is ArtistItem) CircleShape else ShellShapes.Art)
                    if (item is SongItem) HoverPlayButton(hovered, 44.dp, { playback.play(item, songs) }, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                Text(item.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                subtitleOf(item)?.takeIf { it.isNotBlank() }?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

/** Charts the indie way: mono rank numbers, hairline rows, the playing one in lime. */
@Composable
private fun ChartList(title: String, songs: List<SongItem>, home: HomeSlot, twoColumns: Boolean) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    Spacer(Modifier.height(28.dp))
    SectionHeader(title)
    Spacer(Modifier.height(8.dp))
    val columns = if (twoColumns) songs.chunked((songs.size + 1) / 2) else listOf(songs)
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        var offset = 0
        columns.forEach { column ->
            val start = offset
            offset += column.size
            Column(Modifier.weight(1f)) {
                column.forEachIndexed { i, song ->
                    val rank = start + i + 1
                    val playing = home.isPlaying.value && home.currentSongId.value == song.id
                    val (hover, hovered) = rememberHover()
                    val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(bg).hoverable(hover)
                            .shellPressable({ playback.play(song, songs) }, label = song.title).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                            when {
                                playing -> Equalizer(height = 12.dp)
                                hovered -> Glyph(Glyphs.Play, c.ink, 16.dp)
                                else -> Text(rank.toString().padStart(2, '0'), style = Shell.type.index, color = if (rank <= 3) c.accent else c.index)
                            }
                        }
                        ShellArt(song.displayThumbnailUrl, Modifier.size(44.dp), shape = RoundedCornerShape(4.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(song.title, style = Shell.type.body, color = if (playing) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(song.artists.joinToString(", ") { it.name }, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        song.duration?.takeIf { it > 0 }?.let { Text(formatTime(it * 1000), style = Shell.type.bodySmall, color = c.ink2) }
                    }
                }
            }
        }
    }
}
