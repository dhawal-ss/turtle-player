package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import app.turtleplayer.ui.generated.resources.shell_explore_loading
import app.turtleplayer.ui.generated.resources.shell_explore_offline
import app.turtleplayer.ui.generated.resources.shell_explore_subtitle
import app.turtleplayer.ui.generated.resources.shell_explore_title
import app.turtleplayer.ui.generated.resources.shell_explore_waiting
import app.turtleplayer.ui.generated.resources.shell_moods_genres
import app.turtleplayer.ui.generated.resources.shell_new_releases
import app.turtleplayer.ui.generated.resources.shell_trending
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellShapes
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.ui.playback.LocalPlaybackController
import org.jetbrains.compose.resources.stringResource

/** Tile colors for moods and genres; [genreTint] picks one per grid cell. */
internal val GenreTints = listOf(0xFF8A4FD1, 0xFFD9704A, 0xFF2E7E9E, 0xFFC2412E, 0xFF2E9E7A, 0xFF5B4BD8, 0xFFB7792B, 0xFFC4577A, 0xFF3E6FD8, 0xFF6B8E23, 0xFFAD3E8C).map { Color(it) }

/** Steps through [GenreTints] down a column; coprime to its size (11), like the step of 1 along a row. */
private const val GENRE_ROW_STEP = 4

/**
 * The tint for the genre tile at [row], [col]. Moving one tile right advances 1 through the palette
 * and one tile down advances [GENRE_ROW_STEP]; both are non-zero modulo the palette size, so a tile
 * never shares a hue with the one beside it or below it, at any column count up to the palette size.
 */
internal fun genreTint(row: Int, col: Int): Color = GenreTints[(row * GENRE_ROW_STEP + col) % GenreTints.size]

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
        // YouTube can repeat an item within a section; keyed rows need each id once.
        val genres = remember(s.moodsAndGenres) {
            s.moodsAndGenres.distinctBy { it.id to it.params }.map { BrowseItem(id = it.id, title = it.name, thumbnail = it.thumbnail, params = it.params) }
        }
        val newReleases = remember(s.newReleases) { s.newReleases.distinctBy { it.id } }
        val trending = remember(s.trending) { s.trending.distinctBy { it.id }.take(10) }
        val albumCharts = remember(s.albumCharts) { s.albumCharts.distinctBy { it.id } }
        val community = remember(s.communityPlaylist) { s.communityPlaylist.distinctBy { it.id } }
        val nothingYet = genres.isEmpty() && newReleases.isEmpty() && trending.isEmpty() && albumCharts.isEmpty() && community.isEmpty()
        val listState = rememberLazyListState()
        val rise = rememberRiseIn(listState)
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
                when {
                    genres.isNotEmpty() -> item(key = "genres") { GenreGrid(genres, columns, home, rise) }
                    nothingYet && s.error.isNullOrBlank() -> item(key = "genres_loading") { GenrePlaceholders(columns) }
                    nothingYet -> item(key = "offline") {
                        Text(
                            stringResource(Res.string.shell_explore_offline), style = Shell.type.body, color = c.ink2,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp),
                        )
                    }
                }
                if (newReleases.isNotEmpty()) item(key = "new") { ExploreRow(stringResource(Res.string.shell_new_releases), newReleases, home) }
                if (trending.isNotEmpty()) item(key = "trending") { ChartList(stringResource(Res.string.shell_trending), trending, home, twoColumns = maxWidth > 1000.dp) }
                if (albumCharts.isNotEmpty()) item(key = "charts") { ExploreRow(stringResource(Res.string.shell_charts), albumCharts, home) }
                if (community.isNotEmpty()) item(key = "community") { ExploreRow(stringResource(Res.string.shell_community), community, home) }
            }
        }
    }
}

@Composable
private fun GenreGrid(genres: List<BrowseItem>, columns: Int, home: HomeSlot, rise: RiseIn) {
    val rows = remember(genres, columns) { genres.take(columns * 3).chunked(columns) }
    Column {
        Spacer(Modifier.height(28.dp))
        SectionHeader(stringResource(Res.string.shell_moods_genres))
        Spacer(Modifier.height(12.dp))
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            rows.forEachIndexed { row, tiles ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    tiles.forEachIndexed { col, g ->
                        val id = "genre:${g.id}:${g.params}"
                        key(id) {
                            GenreTile(g, genreTint(row, col), { home.onItemClick(g) }, Modifier.weight(1f).riseIn(rise, id, row * columns + col))
                        }
                    }
                    repeat(columns - tiles.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Two pulsing rows of genre-sized tiles while YouTube Music's Explore data is still on its way. */
@Composable
private fun GenrePlaceholders(columns: Int) {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    val label = stringResource(Res.string.shell_explore_loading)
    Column {
        Spacer(Modifier.height(28.dp))
        SectionHeader(stringResource(Res.string.shell_moods_genres))
        Spacer(Modifier.height(12.dp))
        Column(
            Modifier.padding(horizontal = 16.dp).semantics { contentDescription = label }.graphicsLayer { alpha = pulse.value },
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            repeat(2) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    repeat(columns) { Box(Modifier.weight(1f).height(108.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2)) }
                }
            }
        }
    }
}

@Composable
private fun GenreTile(item: BrowseItem, base: Color, onClick: () -> Unit, modifier: Modifier) {
    val (hover, hovered) = rememberHover()
    val tint by animateColorAsState(if (hovered) lerp(base, Color.White, 0.08f) else base, ShellMotion.emphasized())
    val turn by animateFloatAsState(if (hovered) 18f else 25f, ShellMotion.emphasized())
    Box(modifier.height(108.dp).clip(RoundedCornerShape(8.dp)).background(tint).hoverable(hover).shellPressable(onClick, label = item.title).padding(14.dp)) {
        Text(item.title, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Box(
            Modifier.align(Alignment.BottomEnd).offset(22.dp, 16.dp).rotate(turn).size(70.dp)
                .clip(RoundedCornerShape(4.dp)).background(Color(0x400B0B0C)),
        )
    }
}

/** A row of cards; [items] must already be unique by id (they key the row). */
@Composable
private fun ExploreRow(title: String, items: List<YTItem>, home: HomeSlot) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    val songs = remember(items) { items.filterIsInstance<SongItem>() }
    Spacer(Modifier.height(28.dp))
    SectionHeader(title)
    Spacer(Modifier.height(10.dp))
    LazyRow(contentPadding = PaddingValues(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items(items, key = { it.id }) { item ->
            val (hover, hovered) = rememberHover()
            val open: () -> Unit = { if (item is SongItem) playback.play(item, songs) else home.onItemClick(item) }
            Column(
                Modifier.hoverable(hover)
                    .shellPressable(open, label = item.title, onLongClick = home.onItemLongClick?.let { f -> { f(item) } })
                    .cardHover(hovered).width(168.dp),
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

/**
 * Charts the indie way: mono rank numbers, hairline rows. The top three are set in full ink, the rest
 * in ink2; lime is kept for the song that is playing (it means "now", never "top").
 */
@Composable
private fun ChartList(title: String, songs: List<SongItem>, home: HomeSlot, twoColumns: Boolean) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    Spacer(Modifier.height(28.dp))
    SectionHeader(title)
    Spacer(Modifier.height(8.dp))
    val columns = remember(songs, twoColumns) { if (twoColumns) songs.chunked((songs.size + 1) / 2) else listOf(songs) }
    Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        var offset = 0
        columns.forEach { column ->
            val start = offset
            offset += column.size
            Column(Modifier.weight(1f)) {
                column.forEachIndexed { i, song ->
                    key(song.id) {
                        val rank = start + i + 1
                        val playing = home.isPlaying.value && home.currentSongId.value == song.id
                        val (hover, hovered) = rememberHover()
                        val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(bg).hoverable(hover)
                                .shellPressable({ playback.play(song, songs) }, label = song.title, onLongClick = home.onItemLongClick?.let { f -> { f(song) } })
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(28.dp), contentAlignment = Alignment.CenterStart) {
                                when {
                                    playing -> Equalizer(height = 12.dp)
                                    hovered -> Glyph(Glyphs.Play, c.ink, 16.dp)
                                    else -> Text(rank.toString().padStart(2, '0'), style = Shell.type.index, color = if (rank <= 3) c.ink else c.ink2)
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
}
