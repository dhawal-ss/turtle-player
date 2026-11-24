package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_albums
import app.turtleplayer.ui.generated.resources.shell_artists
import app.turtleplayer.ui.generated.resources.shell_browse_all
import app.turtleplayer.ui.generated.resources.shell_browse_see_all
import app.turtleplayer.ui.generated.resources.shell_search_from_library
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import app.turtleplayer.ui.generated.resources.shell_clear_search
import app.turtleplayer.ui.generated.resources.shell_filter_top
import app.turtleplayer.ui.generated.resources.shell_genre_1
import app.turtleplayer.ui.generated.resources.shell_genre_2
import app.turtleplayer.ui.generated.resources.shell_genre_3
import app.turtleplayer.ui.generated.resources.shell_genre_4
import app.turtleplayer.ui.generated.resources.shell_genre_5
import app.turtleplayer.ui.generated.resources.shell_genre_6
import app.turtleplayer.ui.generated.resources.shell_genre_7
import app.turtleplayer.ui.generated.resources.shell_genre_8
import app.turtleplayer.ui.generated.resources.shell_kind_album
import app.turtleplayer.ui.generated.resources.shell_kind_artist
import app.turtleplayer.ui.generated.resources.shell_kind_playlist
import app.turtleplayer.ui.generated.resources.shell_kind_song
import app.turtleplayer.ui.generated.resources.shell_meta_pair
import app.turtleplayer.ui.generated.resources.shell_playlists
import app.turtleplayer.ui.generated.resources.shell_recent_searches
import app.turtleplayer.ui.generated.resources.shell_search_failed
import app.turtleplayer.ui.generated.resources.shell_search_hint
import app.turtleplayer.ui.generated.resources.shell_search_liked_badge
import app.turtleplayer.ui.generated.resources.shell_search_liked_song
import app.turtleplayer.ui.generated.resources.shell_search_loading
import app.turtleplayer.ui.generated.resources.shell_search_no_results
import app.turtleplayer.ui.generated.resources.shell_search_no_results_hint
import app.turtleplayer.ui.generated.resources.shell_search_remove_history
import app.turtleplayer.ui.generated.resources.shell_search_retry
import app.turtleplayer.ui.generated.resources.shell_search_title
import app.turtleplayer.ui.generated.resources.shell_set_explicit_badge
import app.turtleplayer.ui.generated.resources.shell_songs
import app.turtleplayer.ui.generated.resources.shell_sp_episodes
import app.turtleplayer.ui.generated.resources.shell_sp_kind_episode
import app.turtleplayer.ui.generated.resources.shell_sp_kind_podcast
import app.turtleplayer.ui.generated.resources.shell_sp_podcasts
import app.turtleplayer.ui.generated.resources.shell_top_result
import app.turtleplayer.ui.generated.resources.shell_vibe_1
import app.turtleplayer.ui.generated.resources.shell_vibe_2
import app.turtleplayer.ui.generated.resources.shell_vibe_3
import app.turtleplayer.ui.generated.resources.shell_vibe_4
import app.turtleplayer.ui.generated.resources.shell_videos
import app.turtleplayer.ui.generated.resources.shell_vibes_title
import app.turtleplayer.ui.theme.LocalShellGround
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.generated.resources.explicit
import com.metrolist.shared.generated.resources.Res as SharedRes
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.state.SongLikeState
import com.metrolist.shared.turtle.SearchSlot
import kotlinx.coroutines.flow.distinctUntilChanged
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val GENRE_TINTS = listOf(0xFF3E6FD8, 0xFFC4577A, 0xFF8A4FD1, 0xFF2E7E9E, 0xFFB7792B, 0xFFC2412E, 0xFF3B3F8F, 0xFF2E9E7A).map { Color(it) }
private val GENRES = listOf(Res.string.shell_genre_1, Res.string.shell_genre_2, Res.string.shell_genre_3, Res.string.shell_genre_4, Res.string.shell_genre_5, Res.string.shell_genre_6, Res.string.shell_genre_7, Res.string.shell_genre_8)
private val VIBES = listOf(Res.string.shell_vibe_1, Res.string.shell_vibe_2, Res.string.shell_vibe_3, Res.string.shell_vibe_4)
private val FILTERS: List<Pair<SearchFilter?, StringResource>> = listOf(
    null to Res.string.shell_filter_top,
    SearchFilter.SONG to Res.string.shell_songs,
    SearchFilter.ARTIST to Res.string.shell_artists,
    SearchFilter.ALBUM to Res.string.shell_albums,
    SearchFilter.FEATURED_PLAYLIST to Res.string.shell_playlists,
)

/** Shows and episodes, unless upstream's "hide podcasts" setting is on. */
private val PODCAST_FILTERS: List<Pair<SearchFilter?, StringResource>> = listOf(
    SearchFilter.PODCAST to Res.string.shell_sp_podcasts,
    SearchFilter.EPISODE to Res.string.shell_sp_episodes,
)

/** Only the first results rise in (H-Motion); the rest of a long list appears without delay. */
private const val RISE_COUNT = 8

/**
 * Shell's Search, in Spotify's familiar shape: a white search field, Browse all tiles before typing,
 * suggestions while typing, and after searching a top result followed by songs and card rows.
 * Results are upstream's search page, already re-ranked by our engine (HOOKS.md #20).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShellSearch(slot: SearchSlot) = ShellTheme {
    val c = Shell.colors
    val s = slot.uiState
    BoxWithConstraints(slot.modifier.fillMaxSize().background(LocalShellGround.current ?: c.ground)) {
        val wide = maxWidth > 840.dp
        val listState = rememberLazyListState()
        // A new search or filter starts at the top; coming back to the same results keeps the place.
        val resultsKey = if (s.isQuerySubmitted) "${s.query}\u0000${s.selectedFilter}" else null
        var scrolledFor by rememberSaveable { mutableStateOf(resultsKey) }
        LaunchedEffect(resultsKey) {
            if (resultsKey != null && resultsKey != scrolledFor) listState.scrollToItem(0)
            scrolledFor = resultsKey
        }
        OnTabReselected("search") { listState.animateScrollToItem(0) }

        // Results, de-duplicated by id once per page so LazyColumn keys stay unique.
        val filtered = s.filteredResults
        val filteredItems = remember(filtered) { filtered?.items.orEmpty().distinctBy { it.id } }
        // Turtle's local stage: the listener's own songs for the submitted query, there before YouTube
        // answers. Keyed to the query, so a newer search cancels an older one, and tagged with it, so a
        // late answer never shows under another query. The online page (already re-ranked with the
        // library, HOOKS.md #20) replaces it when it lands; filtered searches never use it.
        val localPage by produceState<Pair<String, SearchSummaryPage?>?>(null, s.query, s.isQuerySubmitted) {
            value = null
            val q = s.query
            if (s.isQuerySubmitted && q.isNotBlank()) value = q to ShellSearchLocal.search(q)
        }
        val local = localPage?.takeIf { it.first == s.query && s.isQuerySubmitted }?.second?.summaries.orEmpty()
        val online = s.searchSummary?.summaries.orEmpty()
        // Offline, upstream's page is a plain SQL match of the library; ours is typo-tolerant and ranked.
        val network by remember { PlaybackNetworkStateStore.state }.collectAsState()
        val fromLibrary = s.selectedFilter == null && local.isNotEmpty() && (online.isEmpty() || !network.connected)
        val summaries = remember(online, local, fromLibrary) {
            (if (fromLibrary) local else online).map { it.copy(items = it.items.distinctBy { item -> item.id }) }
        }
        val top = remember(summaries) { summaries.firstOrNull { it.isTopResult }?.items?.firstOrNull() }
        val rest = remember(summaries) { summaries.filterNot { it.isTopResult } }
        val songsBeside = remember(rest, top, wide) {
            if (wide && top != null) rest.firstOrNull { sm -> sm.items.isNotEmpty() && sm.items.all { it is SongItem } } else null
        }
        val sections = remember(rest, songsBeside) { rest.filter { it !== songsBeside && it.items.isNotEmpty() } }
        val suggestionQueries = remember(s.searchSuggestions) { s.searchSuggestions?.queries.orEmpty().distinct().take(6) }
        val suggestionItems = remember(s.searchSuggestions) { s.searchSuggestions?.recommendedItems.orEmpty().distinctBy { it.id }.take(5) }

        val showingFiltered = s.selectedFilter != null && filtered != null
        val hasResults = if (s.selectedFilter != null) filteredItems.isNotEmpty() else summaries.any { it.items.isNotEmpty() }
        // Mirrors upstream's showNoResults (SearchContent.kt).
        val showNoResults = s.hasSearched && !s.isSearching && !s.searchFailed &&
            if (showingFiltered) filteredItems.isEmpty() && filtered?.continuation == null else summaries.all { it.items.isEmpty() }
        // Keys of results that already rose in for this query and filter, so scrolling back never replays it.
        val risen = remember(s.query, s.selectedFilter) { mutableSetOf<Any>() }

        // Paging, keyed like upstream so a page that still leaves us near the end asks for the next one.
        LaunchedEffect(s.selectedFilter, filtered?.continuation, filteredItems.size, s.isLoadingMore, s.loadMoreFailed) {
            if (s.selectedFilter == null || filtered?.continuation == null || s.isLoadingMore || s.loadMoreFailed) return@LaunchedEffect
            snapshotFlow {
                val info = listState.layoutInfo
                info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 3
            }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) slot.onLoadMore() }
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = slot.contentPadding.calculateTopPadding() + 12.dp, bottom = slot.contentPadding.calculateBottomPadding() + 24.dp),
            modifier = Modifier.fillMaxSize().padding(horizontal = if (ShellPlatform.desktop) 8.dp else 0.dp),
        ) {
            if (slot.showSearchBar) {
                item(key = "title") {
                    Text(stringResource(Res.string.shell_search_title), style = Shell.type.headline, color = c.ink, modifier = Modifier.padding(horizontal = 16.dp))
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "field") { SearchField(slot) }
            }

            when {
                !s.isQuerySubmitted && s.query.isBlank() -> {
                    val history = s.searchHistory.distinct().take(8)
                    if (history.isNotEmpty()) {
                        item(key = "recent") { SectionTitle(stringResource(Res.string.shell_recent_searches)) }
                        items(history, key = { "h:$it" }) { q -> HistoryRow(q, slot) }
                    }
                    item(key = "vibes") {
                        SectionTitle(stringResource(Res.string.shell_vibes_title))
                        VibeChips(slot)
                    }
                    item(key = "browse") {
                        val openBrowse = rememberOpenBrowse()
                        Row(verticalAlignment = Alignment.Bottom) {
                            Box(Modifier.weight(1f)) { SectionTitle(stringResource(Res.string.shell_browse_all)) }
                            // The whole of Browse, over Search: Back returns here. A 48 dp touch target; the
                            // text names it, so no separate label (it would be read twice).
                            Box(
                                Modifier.padding(end = 8.dp).heightIn(min = 48.dp).clip(CircleShape)
                                    .shellPressable(openBrowse, focusShape = CircleShape).padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(stringResource(Res.string.shell_browse_see_all), style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink2)
                            }
                        }
                        val cols = if (wide) 4 else 2
                        // YouTube Music's own categories once Home has them, or (signed in, when Home's feed has
                        // none) once Explore's own load has: each opens its Browse page as a real route through
                        // Home's opener, so Back returns to Search. Until then, the fixed tiles search for their name.
                        val homeMoods = ShellBrowse.home?.uiState?.moodsAndGenres
                        val explore = rememberExploreState(wanted = homeMoods != null && homeMoods.isEmpty())
                        val categories = remember(homeMoods, explore.data) { ShellBrowse.categories(explore.data).take(GENRES.size) }
                        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (categories.isNotEmpty()) {
                                categories.withIndex().chunked(cols).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { (i, item) -> BrowseTile(item.title, GENRE_TINTS[i % GENRE_TINTS.size], { ShellBrowse.openItem(item) }, Modifier.weight(1f)) }
                                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            } else {
                                GENRES.withIndex().chunked(cols).forEach { row ->
                                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        row.forEach { (i, res) -> val g = stringResource(res); BrowseTile(g, GENRE_TINTS[i], { slot.onSearch(g) }, Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }
                }

                !s.isQuerySubmitted -> {
                    items(suggestionQueries, key = { "q:$it" }) { q ->
                        Row(Modifier.widthIn(max = 760.dp).fillMaxWidth().shellPressable({ slot.onSearch(q) }).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(Glyphs.Search, c.ink2, 18.dp)
                            Spacer(Modifier.width(14.dp))
                            Text(q, style = Shell.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    items(suggestionItems, key = { "r:${it.id}" }) { item -> Box(Modifier.widthIn(max = 760.dp)) { ResultRow(item, slot) } }
                }

                else -> {
                    item(key = "filters") {
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(16.dp, 14.dp, 16.dp, 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            val filters = if (Settings.shared.hidePodcasts) FILTERS else FILTERS + PODCAST_FILTERS
                            filters.forEach { (f, res) -> ShellChip(stringResource(res), s.selectedFilter == f, { slot.onFilterSelected(f) }) }
                        }
                    }
                    if (fromLibrary) {
                        item(key = "from_library") {
                            Text(
                                stringResource(Res.string.shell_search_from_library), style = Shell.type.bodySmall, color = c.ink2,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp),
                            )
                        }
                    }
                    when {
                        s.isSearching && !hasResults -> item(key = "skeleton") { SkeletonRows() }
                        s.searchFailed && !hasResults -> item(key = "failed") {
                            Message(stringResource(Res.string.shell_search_failed), null) {
                                ShellChip(stringResource(Res.string.shell_search_retry), false, {
                                    val f = s.selectedFilter
                                    if (f != null) slot.onFilterSelected(f) else slot.onSearch(s.query)
                                })
                            }
                        }
                        showNoResults -> item(key = "empty") {
                            Message(stringResource(Res.string.shell_search_no_results, s.query), stringResource(Res.string.shell_search_no_results_hint)) {
                                VibeChips(slot, Modifier)
                            }
                        }
                        s.selectedFilter != null -> {
                            itemsIndexed(filteredItems, key = { _, it -> "f:${it.id}" }) { i, item ->
                                ResultRow(item, slot, modifier = Modifier.rise("f:${item.id}", i, risen))
                            }
                            if (s.isLoadingMore || s.loadMoreFailed) {
                                item(key = "more") {
                                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                        if (s.loadMoreFailed) {
                                            ShellChip(stringResource(Res.string.shell_search_retry), false, { slot.onLoadMore() })
                                        } else {
                                            CircularProgressIndicator(Modifier.size(20.dp), color = c.ink2, strokeWidth = 2.dp)
                                        }
                                    }
                                }
                            }
                        }
                        else -> {
                            var n = 0
                            if (top != null && songsBeside != null) {
                                n = 1 + minOf(4, songsBeside.items.size)
                                // Spotify's desktop shape: the top result beside the first four songs.
                                item(key = "top") {
                                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Column(Modifier.weight(0.42f)) {
                                            SectionTitle(stringResource(Res.string.shell_top_result))
                                            TopResultCard(top, slot, wide, Modifier.weight(1f).rise("top:${top.id}", 0, risen))
                                        }
                                        Column(Modifier.weight(0.58f)) {
                                            SectionTitle(sectionTitle(songsBeside), start = 0.dp)
                                            songsBeside.items.take(4).forEachIndexed { i, item ->
                                                ResultRow(item, slot, compact = true, modifier = Modifier.rise("sb:${item.id}", i + 1, risen))
                                            }
                                        }
                                    }
                                }
                            } else if (top != null) {
                                n = 1
                                item(key = "top") {
                                    SectionTitle(stringResource(Res.string.shell_top_result))
                                    TopResultCard(top, slot, wide, Modifier.rise("top:${top.id}", 0, risen))
                                }
                            }
                            sections.forEachIndexed { si, summary ->
                                if (summary.items.all { it is SongItem }) {
                                    val rows = summary.items.take(6)
                                    val base = n
                                    n += rows.size
                                    item(key = "t:$si:${summary.title}") { SectionTitle(sectionTitle(summary)) }
                                    itemsIndexed(rows, key = { _, it -> "s:$si:${it.id}" }) { i, item ->
                                        ResultRow(item, slot, modifier = Modifier.rise("s:$si:${item.id}", base + i, risen))
                                    }
                                } else {
                                    val index = n++
                                    item(key = "c:$si:${summary.title}") {
                                        Column(Modifier.rise("c:$si:${summary.title}", index, risen)) {
                                            SectionTitle(sectionTitle(summary))
                                            CardRow(summary.items, slot, wide)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * H-Motion's rise: fade in and lift 10dp, 300ms emphasized, staggered 80 + i×40ms. Only the first
 * [RISE_COUNT] results animate, and each key only once per result set ([risen]).
 */
@Composable
private fun Modifier.rise(key: Any, index: Int, risen: MutableSet<Any>): Modifier {
    val progress = remember(key) { Animatable(if (index < RISE_COUNT && risen.add(key)) 0f else 1f) }
    LaunchedEffect(key) {
        if (progress.value < 1f) {
            progress.animateTo(1f, tween(ShellMotion.EMPHASIZED_MS, 80 + index * ShellMotion.STAGGER_MS, ShellMotion.Emphasized))
        }
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 10.dp.toPx()
    }
}

@Composable
private fun SearchField(slot: SearchSlot) {
    val c = Shell.colors
    val q = slot.uiState.query
    val ring by animateColorAsState(if (q.isNotEmpty()) c.accent else c.accent.copy(alpha = 0f), tween(200, easing = ShellMotion.Emphasized))
    val textStyle = Shell.type.body.copy(fontSize = 16.sp, fontWeight = FontWeight.Medium)
    val hint = stringResource(Res.string.shell_search_hint)
    // The ring sits just outside the field, like H-Search's box-shadow: a 2dp border around a 2dp gap.
    Box(Modifier.padding(horizontal = 14.dp).fillMaxWidth().border(2.dp, ring, RoundedCornerShape(10.dp)).padding(2.dp)) {
        Row(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).background(c.ink)
                .padding(start = 12.dp, end = if (q.isEmpty()) 12.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.Search, c.ground, 22.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                // The field carries the hint as its label, so screen readers don't read the placeholder twice.
                if (q.isEmpty()) Text(hint, style = textStyle, color = c.ground.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {})
                BasicTextField(
                    value = q,
                    onValueChange = slot.onQueryChange,
                    singleLine = true,
                    textStyle = textStyle.copy(color = c.ground),
                    cursorBrush = SolidColor(c.ground),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { slot.onSearch(q) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(slot.focusRequester).semantics { contentDescription = hint },
                )
            }
            if (q.isNotEmpty()) {
                GlyphButton(Glyphs.Close, stringResource(Res.string.shell_clear_search), { slot.onQueryChange("") }, c.ground, size = 48.dp, iconSize = 16.dp)
            }
        }
    }
}

/** A recent search: tap to search again, ✕ (48dp target) to forget it. */
@Composable
private fun HistoryRow(q: String, slot: SearchSlot) {
    val c = Shell.colors
    Row(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().shellPressable({ slot.onHistoryItemClick(q) }).padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.History, c.ink2, 18.dp)
        Spacer(Modifier.width(14.dp))
        Text(q, style = Shell.type.body, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        GlyphButton(Glyphs.Close, stringResource(Res.string.shell_search_remove_history, q), { slot.onRemoveHistoryItem(q) }, c.ink2, size = 48.dp, iconSize = 14.dp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VibeChips(slot: SearchSlot, modifier: Modifier = Modifier.padding(horizontal = 16.dp)) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        VIBES.forEach { res -> val v = stringResource(res); ShellChip(v, false, { slot.onSearch(v) }) }
    }
}

/** A short centered-left message (failed / no results) with an action below it. */
@Composable
private fun Message(title: String, hint: String?, action: @Composable () -> Unit) {
    val c = Shell.colors
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(16.dp, 28.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = Shell.type.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = c.ink)
        if (hint != null) Text(hint, style = Shell.type.bodySmall, color = c.ink2)
        Spacer(Modifier.height(6.dp))
        action()
    }
}

/** Six placeholder rows pulsing while the first results load. */
@Composable
private fun SkeletonRows() {
    val c = Shell.colors
    val pulse = rememberInfiniteTransition()
    val alpha by pulse.animateFloat(0.5f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse))
    val label = stringResource(Res.string.shell_search_loading)
    Column(Modifier.fillMaxWidth().semantics { contentDescription = label }.graphicsLayer { this.alpha = alpha }.padding(top = 8.dp)) {
        repeat(6) {
            Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.55f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
                    Box(Modifier.fillMaxWidth(0.35f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, start: Dp = 16.dp) {
    Text(text, style = Shell.type.label.copy(fontSize = Shell.type.body.fontSize * 1.15f, fontWeight = FontWeight.Bold), color = Shell.colors.ink, modifier = Modifier.padding(start, 22.dp, 16.dp, 10.dp))
}

@Composable
private fun sectionTitle(summary: SearchSummary): String = when (summary.title.lowercase()) {
    "songs" -> stringResource(Res.string.shell_songs)
    "artists" -> stringResource(Res.string.shell_artists)
    "albums" -> stringResource(Res.string.shell_albums)
    "playlists" -> stringResource(Res.string.shell_playlists)
    "videos" -> stringResource(Res.string.shell_videos)
    "podcasts" -> stringResource(Res.string.shell_sp_podcasts)
    "episodes" -> stringResource(Res.string.shell_sp_episodes)
    else -> summary.title
}

@Composable
private fun BrowseTile(name: String, tint: Color, onClick: () -> Unit, modifier: Modifier) {
    Box(modifier.height(96.dp).clip(RoundedCornerShape(8.dp)).background(tint).shellPressable(onClick).padding(12.dp)) {
        Text(name, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = Color.White)
        Box(Modifier.align(Alignment.BottomEnd).offset(20.dp, 14.dp).rotate(25f).size(62.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x590B0B0C)))
    }
}

@Composable
private fun TopResultCard(item: YTItem, slot: SearchSlot, wide: Boolean, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface3 else c.surface2, ShellMotion.emphasized())
    val isSong = item is SongItem
    val liked = isSong && SongLikeState.isLiked(item.id)
    Box(
        modifier.padding(start = 16.dp).then(if (wide) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().padding(end = 16.dp))
            .clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover)
            .shellPressable({ playResult(slot, item) }, onLongClick = { slot.onItemLongClick(item) })
            .padding(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            ShellArt(item.displayThumbnailUrl, Modifier.size(92.dp), shape = if (item is ArtistItem) CircleShape else ShellShapes.Art)
            // Songs keep clear of the play button in the corner.
            Column(Modifier.padding(end = if (isSong) 64.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    item.title,
                    style = Shell.type.title.copy(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.02).em),
                    color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(metaOf(item), style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (liked) Text(stringResource(Res.string.shell_search_liked_song), style = Shell.type.bodySmall, color = c.accent, maxLines = 1)
            }
        }
        // Touch has no hover, so a song's play button is always there (H-Search).
        if (isSong) HoverPlayButton(true, 52.dp, { playResult(slot, item) }, Modifier.align(Alignment.BottomEnd))
    }
}

@Composable
private fun ResultRow(item: YTItem, slot: SearchSlot, compact: Boolean = false, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val playing = slot.isPlaying && slot.currentSongId == item.id
    val liked = item is SongItem && SongLikeState.isLiked(item.id)
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
    val artSize = if (compact) 44.dp else 48.dp
    Row(
        modifier.fillMaxWidth().padding(horizontal = if (compact) 0.dp else 8.dp).clip(RoundedCornerShape(6.dp)).background(bg).hoverable(hover)
            .shellPressable({ playResult(slot, item) }, onLongClick = { slot.onItemLongClick(item) })
            .padding(horizontal = 8.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ShellArt(item.displayThumbnailUrl, Modifier.size(artSize), shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(4.dp))
            if (item is SongItem && hovered) {
                Box(Modifier.size(artSize).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                    Glyph(Glyphs.Play, Color.White, 20.dp)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = Shell.type.body, color = if (playing) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = if (compact) searchSubtitle(item).orEmpty() else metaOf(item)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Explicit and clean versions are separate results; the tag tells them apart.
                if (item is SongItem && item.isExplicit) {
                    ShellExplicitTag(stringResource(Res.string.shell_set_explicit_badge), stringResource(SharedRes.string.explicit))
                    Spacer(Modifier.width(6.dp))
                }
                Text(meta, style = Shell.type.bodySmall, color = if (hovered) c.ink else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (playing) {
            Spacer(Modifier.width(12.dp))
            Equalizer(height = 12.dp)
        }
        if (liked) {
            Spacer(Modifier.width(12.dp))
            LikedBadge()
        }
        val seconds = (item as? SongItem)?.duration ?: (item as? EpisodeItem)?.duration
        if (seconds != null && seconds > 0 && ShellPlatform.desktop) {
            Spacer(Modifier.width(16.dp))
            Text(formatTime(seconds * 1000), style = Shell.type.bodySmall, color = c.ink2)
        }
    }
}

/** Mono "LIKED" tag: lime on lime 12%, because lime means yours. */
@Composable
private fun LikedBadge() {
    val c = Shell.colors
    Text(
        stringResource(Res.string.shell_search_liked_badge).uppercase(),
        style = Shell.type.index.copy(fontSize = 10.sp, letterSpacing = 0.04.em),
        color = c.accent,
        maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 4.dp),
    )
}

@Composable
private fun CardRow(items: List<YTItem>, slot: SearchSlot, wide: Boolean) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    LazyRow(contentPadding = PaddingValues(horizontal = if (desktop) 6.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (desktop) 2.dp else 14.dp)) {
        items(items, key = { it.id }) { item ->
            val size = if (wide) 160.dp else 132.dp
            val (hover, hovered) = rememberHover()
            Column(
                Modifier.hoverable(hover).cardHover(hovered).width(size)
                    .shellPressable({ playResult(slot, item) }, onLongClick = { slot.onItemLongClick(item) }),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box {
                    ShellArt(item.displayThumbnailUrl, Modifier.size(size), shape = if (item is ArtistItem) CircleShape else ShellShapes.Art)
                    if (item is SongItem) HoverPlayButton(hovered, 44.dp, { playResult(slot, item) }, Modifier.align(Alignment.BottomEnd).padding(8.dp))
                }
                Text(item.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                kindOf(item)?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1) }
            }
        }
    }
}

/** "Kind · subtitle", either part optional. */
@Composable
private fun metaOf(item: YTItem): String {
    val kind = kindOf(item)
    val sub = searchSubtitle(item)?.takeIf { it.isNotBlank() }
    return if (kind != null && sub != null) stringResource(Res.string.shell_meta_pair, kind, sub) else kind ?: sub.orEmpty()
}

/** Home's subtitles, plus the host of a show and the show (or host) of an episode. */
private fun searchSubtitle(item: YTItem): String? = when (item) {
    is PodcastItem -> item.author
    is EpisodeItem -> listOfNotNull(item.author?.name, item.publishDateText).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { null }
    else -> subtitleOf(item)
}

/** Opens a result; a song is attributed to Search first, so its play records where it came from. */
private fun playResult(
    slot: SearchSlot,
    item: YTItem,
) {
    if (item is SongItem) TurtleBridge.attributePlay(PlayPlace.SEARCH, null, listOf(item))
    slot.onItemClick(item)
}

@Composable
private fun kindOf(item: YTItem): String? = when (item) {
    is ArtistItem -> stringResource(Res.string.shell_kind_artist)
    is AlbumItem -> stringResource(Res.string.shell_kind_album)
    is PlaylistItem -> stringResource(Res.string.shell_kind_playlist)
    is PodcastItem -> stringResource(Res.string.shell_sp_kind_podcast)
    is EpisodeItem -> stringResource(Res.string.shell_sp_kind_episode)
    is SongItem -> if (item.isPodcast) stringResource(Res.string.shell_sp_kind_episode) else stringResource(Res.string.shell_kind_song)
    else -> null
}

/** Turtle's local search stage as ShellSearch reaches it: the bridge in the app, a fake in headless UI tests. */
internal object ShellSearchLocal {
    val bridge: suspend (String) -> SearchSummaryPage? = { q -> TurtleBridge.searchLocal(q) }
    var search: suspend (String) -> SearchSummaryPage? = bridge
}
