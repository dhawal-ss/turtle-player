package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_lib_clear_filter
import app.turtleplayer.ui.generated.resources.shell_lib_clear_search
import app.turtleplayer.ui.generated.resources.shell_lib_close_search
import app.turtleplayer.ui.generated.resources.shell_lib_downloaded
import app.turtleplayer.ui.generated.resources.shell_lib_empty
import app.turtleplayer.ui.generated.resources.shell_lib_error
import app.turtleplayer.ui.generated.resources.shell_lib_error_hint
import app.turtleplayer.ui.generated.resources.shell_lib_kind_album
import app.turtleplayer.ui.generated.resources.shell_lib_kind_episode
import app.turtleplayer.ui.generated.resources.shell_lib_kind_playlist
import app.turtleplayer.ui.generated.resources.shell_lib_kind_podcast
import app.turtleplayer.ui.generated.resources.shell_lib_kind_song
import app.turtleplayer.ui.generated.resources.shell_lib_meta_pair
import app.turtleplayer.ui.generated.resources.shell_lib_move_bottom
import app.turtleplayer.ui.generated.resources.shell_lib_move_down
import app.turtleplayer.ui.generated.resources.shell_lib_move_top
import app.turtleplayer.ui.generated.resources.shell_lib_move_up
import app.turtleplayer.ui.generated.resources.shell_lib_pinned
import app.turtleplayer.ui.generated.resources.shell_lib_reorder
import app.turtleplayer.ui.generated.resources.shell_lib_retry
import app.turtleplayer.ui.generated.resources.shell_lib_show_more
import app.turtleplayer.ui.generated.resources.shell_lib_song_count
import app.turtleplayer.ui.generated.resources.shell_lib_songs_in_playlists
import app.turtleplayer.ui.generated.resources.shell_lib_sort_ascending
import app.turtleplayer.ui.generated.resources.shell_lib_sort_by
import app.turtleplayer.ui.generated.resources.shell_lib_sort_descending
import app.turtleplayer.ui.generated.resources.shell_lib_title
import app.turtleplayer.ui.theme.LocalShellGround
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.model.exactSongCount
import com.metrolist.shared.model.filterForLibrarySearch
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.settings.ArtistNameAliases
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.settings.HiddenLibraryItem
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.settings.HiddenLibraryItemType
import com.metrolist.shared.settings.LibraryContentSort
import com.metrolist.shared.settings.LibraryFilterType
import com.metrolist.shared.settings.LibrarySorting
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.settings.displayName
import com.metrolist.shared.settings.displayTitle
import com.metrolist.shared.settings.moveLibraryItems
import com.metrolist.shared.settings.orderLibraryItems
import com.metrolist.shared.turtle.LibraryPageSlot
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.libraryArtwork
import com.metrolist.shared.ui.components.libraryCover
import com.metrolist.shared.ui.haptics.HapticEvent
import com.metrolist.shared.ui.haptics.rememberMetrolistHaptics
import com.metrolist.shared.ui.menu.ExpressiveSelectionMenu
import com.metrolist.shared.ui.menu.trackPressPosition
import com.metrolist.shared.ui.screens.LibraryAutoPlaylist
import com.metrolist.shared.ui.screens.LibraryContentText
import com.metrolist.shared.ui.screens.LibrarySortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
// Upstream's filter enum; this package has its own `LibraryFilter` (desktop sidebar), so alias it.
import com.metrolist.shared.ui.screens.LibraryFilter as UpFilter

/*
 * Shell's Library page (H-Library): Spotify's "Your Library" in Shell's language, replacing upstream's
 * `LibraryContent` on phones and desktop (turtle/HOOKS.md #37). Every upstream feature is kept: filters,
 * auto playlists, hidden items, sorting per filter, manual order with drag (and a move menu), grid or
 * list, in-library search with "songs inside your playlists", loading, error and empty states. Every
 * item goes through upstream's `itemContainer`, which supplies the long-press menu, the "more" button
 * and swipe-to-queue.
 */

@Composable
fun ShellLibraryPage(slot: LibraryPageSlot) = ShellTheme { ShellLibContent(slot) }

private const val SHELL_LIB_ITEM = "lib_item_"
private const val SHELL_LIB_RISE_MAX = 12

private fun shellLibItemKey(item: YTItem): String = SHELL_LIB_ITEM + item::class.simpleName + "_" + item.id

// ---------------------------------------------------------------------------------------------
// Page
// ---------------------------------------------------------------------------------------------

@Composable
private fun ShellLibContent(slot: LibraryPageSlot) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    val ground = LocalShellGround.current ?: c.ground
    val latest by rememberUpdatedState(slot)
    val haptics = rememberMetrolistHaptics()
    val density = LocalDensity.current

    // Filtering and sorting, as upstream: sorting is stored per filter; unavailable sorts fall back.
    val hidePodcasts = Settings.shared.hidePodcasts
    val active = slot.selectedFilter.takeUnless { it == UpFilter.PODCASTS && hidePodcasts }
    val filterType = slot.selectedFilter?.let { LibraryFilterType.valueOf(it.name) } ?: LibraryFilterType.ALL
    val sorting = Settings.shared.librarySorting(filterType)
    val sortType = LibrarySortType.valueOf(sorting.sort.name).takeIf { it.shellAvailableFor(active) } ?: LibrarySortType.RECENTLY_ADDED
    val direction = sorting.direction
    val blockedVersion by BlockedContent.version.collectAsState()
    val aliases =
        if (sortType == LibrarySortType.NAME && (active == null || active == UpFilter.ARTISTS)) ArtistNameAliases.aliases else emptyMap()
    val input =
        remember(slot.items, active, slot.hiddenItemKeys, sortType, direction, slot.manualOrder, blockedVersion, slot.searchQuery, slot.contentMatchedSongs, aliases) {
            ShellLibInput(slot.items, active, slot.hiddenItemKeys, sortType, direction, slot.manualOrder, slot.searchQuery, slot.contentMatchedSongs, aliases)
        }
    val prepared by produceState<ShellLibPrepared?>(null, input, slot.isLoading) {
        if (slot.isLoading && input.items.isEmpty()) return@produceState
        value = withContext(Dispatchers.Default) { prepareShellLibrary(input) }
    }
    LaunchedEffect(sortType, direction) {
        latest.onSearchDescendingChange(sortType == LibrarySortType.NAME && direction == SortDirection.DESCENDING)
    }

    val autos =
        remember(slot.autoPlaylists, slot.hiddenItemKeys, active, blockedVersion, hidePodcasts, slot.searchQuery) {
            if (active in setOf(UpFilter.TRACKS, UpFilter.ARTISTS, UpFilter.ALBUMS)) {
                emptyList()
            } else if (active == UpFilter.PODCASTS) {
                // Podcasts keeps upstream's "New episodes" and "Episodes for later" above the shows.
                slot.autoPlaylists.filter {
                    it.type in setOf("podcasts", "SE") && HiddenLibraryItemKey(HiddenLibraryItemType.AUTO, it.type) !in slot.hiddenItemKeys &&
                        (slot.searchQuery.isBlank() || it.title.contains(slot.searchQuery.trim(), ignoreCase = true))
                }
            } else {
                val term = slot.searchQuery.trim()
                slot.autoPlaylists
                    .filterNot {
                        (hidePodcasts && it.type in setOf("podcasts", "SE")) ||
                            HiddenLibraryItemKey(HiddenLibraryItemType.AUTO, it.type) in slot.hiddenItemKeys
                    }.filter { term.isEmpty() || it.title.contains(term, ignoreCase = true) }
            }
        }

    // Manual order: a local copy that follows drags at once, saved through onManualOrderChange.
    val reorderEnabled = sortType == LibrarySortType.MANUAL && slot.searchQuery.isBlank()
    var reorderOrder by remember(slot.manualOrder) { mutableStateOf(slot.manualOrder) }
    val orderable =
        remember(slot.items, sortType) {
            if (sortType == LibrarySortType.MANUAL) slot.items.filterNot { it is EpisodeItem || (it is SongItem && it.isPodcast) } else emptyList()
        }
    val shown =
        remember(prepared, reorderEnabled, reorderOrder) {
            prepared?.items?.let { if (reorderEnabled) orderLibraryItems(it, reorderOrder) else it } ?: emptyList()
        }
    val songs = prepared?.songs ?: emptyList()
    val reorder = remember { ShellLibReorder() }
    SideEffect {
        reorder.grid = slot.scrollState
        reorder.enabled = reorderEnabled && shown.size > 1
        reorder.shown = shown
        reorder.move = { from, to ->
            val next = moveLibraryItems(orderable, reorder.shown, reorderOrder, HiddenLibraryItem.from(from).key, HiddenLibraryItem.from(to).key)
            if (next == reorderOrder) {
                false
            } else {
                val state = slot.scrollState
                state.requestScrollToItem(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset)
                reorderOrder = next
                haptics.perform(HapticEvent.DRAG_SNAP)
                latest.onManualOrderChange(next)
                true
            }
        }
    }
    // Auto-scroll while an item is dragged near the top or bottom edge.
    val dragKey = reorder.key
    LaunchedEffect(reorder, dragKey) {
        if (dragKey == null) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (isActive && reorder.key == dragKey) {
            withFrameNanos { }
            val state = reorder.grid ?: break
            val layout = state.layoutInfo
            val me = layout.visibleItemsInfo.firstOrNull { it.key == dragKey } ?: continue
            val bottomInset = with(density) { (latest.bottomPadding + latest.extraBottomPadding).toPx() }
            val top = reorder.visualTop()
            val bottom = top + me.size.height
            val start = layout.viewportStartOffset.toFloat()
            val end = layout.viewportEndOffset - bottomInset
            val step =
                when {
                    reorder.total.y < 0f && top < start + edge -> -maxStep * ((start + edge - top) / edge).coerceIn(0f, 1f)
                    reorder.total.y > 0f && bottom > end - edge -> maxStep * ((bottom - end + edge) / edge).coerceIn(0f, 1f)
                    else -> 0f
                }
            if (step != 0f) {
                state.scrollBy(step)
                reorder.check()
            }
        }
    }

    // "Songs in your playlists": load more as the list nears its end, as upstream.
    val searchesSongs = active == null || active == UpFilter.TRACKS
    val ready = prepared != null
    LaunchedEffect(slot.scrollState, ready, songs.size, slot.contentSearchHasMore, slot.searchQuery, searchesSongs) {
        if (!ready || !searchesSongs || slot.searchQuery.trim().length < 2 || !slot.contentSearchHasMore) return@LaunchedEffect
        val state = slot.scrollState
        snapshotFlow {
            val layout = state.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: 0) >= layout.totalItemsCount - 12
        }.distinctUntilChanged().collect { nearEnd -> if (nearEnd) latest.onLoadMoreContentMatches() }
    }

    val strings =
        ShellLibStrings(
            pairFormat = stringResource(Res.string.shell_lib_meta_pair, "\u0001", "\u0002"),
            playlist = stringResource(Res.string.shell_lib_kind_playlist),
            album = stringResource(Res.string.shell_lib_kind_album),
            song = stringResource(Res.string.shell_lib_kind_song),
            podcast = stringResource(Res.string.shell_lib_kind_podcast),
            episode = stringResource(Res.string.shell_lib_kind_episode),
            artist = slot.text.artist,
        )
    val toggleSearch = {
        if (slot.isSearchOpen) slot.onSearchQueryChange?.invoke("")
        slot.onSearchToggle()
    }
    val grid = slot.isGridView
    val pinch = if (desktop) 0 else Settings.shared.libraryPinchColumns
    val columns =
        when {
            !grid -> GridCells.Fixed(1)
            desktop -> GridCells.Adaptive(180.dp)
            pinch > 0 -> GridCells.Fixed(pinch)
            else -> GridCells.Adaptive(150.dp)
        }
    val side = if (desktop) 8.dp else 16.dp
    val inset = if (desktop) 8.dp else 0.dp
    val rise = rememberShellLibRise(slot.scrollState, ready)
    val playableSongs = { shown.filterIsInstance<SongItem>() + songs }

    Box(slot.modifier.fillMaxSize().background(ground)) {
        LazyVerticalGrid(
            columns = columns,
            state = slot.scrollState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = side, end = side, top = slot.topPadding, bottom = slot.bottomPadding + slot.extraBottomPadding),
            horizontalArrangement = Arrangement.spacedBy(if (grid && !desktop) 12.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(if (grid && !desktop) 16.dp else 0.dp),
        ) {
            item(key = "lib_top", contentType = "top", span = { GridItemSpan(maxLineSpan) }) {
                ShellLibTop(slot, active, filterType, sorting, sortType, toggleSearch, Modifier.padding(horizontal = inset, vertical = 0.dp))
            }
            if (!slot.isLoading && slot.error) {
                item(key = "lib_error", contentType = "status", span = { GridItemSpan(maxLineSpan) }) {
                    ShellLibError(slot.onRetry, Modifier.padding(horizontal = inset).shellLibAnimated(this))
                }
            }
            items(
                count = autos.size,
                key = { "lib_auto_" + autos[it].type },
                span = { GridItemSpan(if (grid) 1 else maxLineSpan) },
                contentType = { "auto" },
            ) { i ->
                val playlist = autos[i]
                Box(Modifier.shellLibAnimated(this).shellLibRise(rise, "lib_auto_" + playlist.type, i)) {
                    slot.itemContainer(
                        HiddenLibraryItem(HiddenLibraryItemType.AUTO, playlist.type, playlist.title, subtitle = playlist.subtitle),
                        null,
                        grid,
                        { slot.onAutoPlaylistClick(playlist.type) },
                    ) {
                        ShellLibEntry(
                            title = playlist.title,
                            meta = playlist.subtitle,
                            art = { m -> ShellLibAutoArt(playlist, m, if (grid) 56.dp else 26.dp) },
                            grid = grid,
                            pinned = true,
                            badge = playlist.badge,
                        )
                    }
                }
            }
            val loading = prepared == null || (slot.isLoading && shown.isEmpty())
            if (loading && !slot.error) {
                items(
                    count = if (grid) 6 else 8,
                    key = { "lib_skeleton_$it" },
                    span = { GridItemSpan(if (grid) 1 else maxLineSpan) },
                    contentType = { "skeleton" },
                ) { ShellLibSkeleton(grid) }
            } else {
                if (shown.isEmpty() && autos.isEmpty() && songs.isEmpty() && !slot.error) {
                    item(key = "lib_empty", contentType = "status", span = { GridItemSpan(maxLineSpan) }) {
                        ShellLibEmpty(if (slot.searchQuery.isNotBlank()) slot.text.searchNoResults else stringResource(Res.string.shell_lib_empty))
                    }
                }
                items(
                    count = shown.size,
                    key = { shellLibItemKey(shown[it]) },
                    span = { GridItemSpan(if (grid) 1 else maxLineSpan) },
                    contentType = { shown[it]::class },
                ) { index ->
                    val item = shown[index]
                    val key = shellLibItemKey(item)
                    val dragging = reorder.key == key
                    val lift by animateFloatAsState(if (dragging) 1f else 0f, ShellMotion.emphasized())
                    val current = item is SongItem && item.id == slot.currentSongId
                    Box(
                        (if (dragging) Modifier.zIndex(1f) else Modifier.shellLibAnimated(this))
                            .shellLibRise(rise, key, autos.size + index)
                            .graphicsLayer {
                                if (dragging) {
                                    val t = reorder.translation(key)
                                    translationX = t.x
                                    translationY = t.y
                                }
                                val scale = 1f + 0.02f * lift
                                scaleX = scale
                                scaleY = scale
                                shadowElevation = 12.dp.toPx() * lift
                                shape = RoundedCornerShape(8.dp)
                                clip = false
                            }.then(if (lift > 0f) Modifier.background(c.surface2.copy(alpha = lift), RoundedCornerShape(8.dp)) else Modifier),
                    ) {
                        slot.itemContainer(
                            HiddenLibraryItem.from(item),
                            item,
                            grid,
                            {
                                if (item is SongItem) {
                                    val all = playableSongs()
                                    slot.onPlaySongs(all, all.indexOf(item))
                                } else {
                                    CollectionPreviews.record(item)
                                    slot.onItemClick(item)
                                }
                            },
                        ) {
                            ShellLibEntry(
                                title = item.displayTitle,
                                meta = shellLibMeta(item, strings),
                                art = { m -> ShellLibItemArt(item, m) },
                                grid = grid,
                                current = current,
                                playing = current && slot.isPlaying,
                                downloaded = (item as? PlaylistItem)?.isDownloaded == true || (item as? AlbumItem)?.isDownloaded == true,
                                onPlay = (item as? AlbumItem)?.let { album -> slot.onPlayAlbum?.let { play -> { play(album) } } },
                                handle =
                                    if (reorderEnabled && shown.size > 1) {
                                        {
                                            ShellLibHandle(
                                                reorder = reorder,
                                                key = key,
                                                title = item.displayTitle,
                                                index = index,
                                                last = shown.lastIndex,
                                                onDragStart = { haptics.perform(HapticEvent.DRAG_START) },
                                                onMove = { target ->
                                                    shown.getOrNull(target)?.takeIf { target != index }?.let { reorder.move(item, it) }
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                            )
                        }
                    }
                }
                if (songs.isNotEmpty()) {
                    item(key = "lib_songs_header", contentType = "section", span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            stringResource(Res.string.shell_lib_songs_in_playlists),
                            style = Shell.type.headline.copy(fontSize = 18.sp, lineHeight = 22.sp),
                            color = c.ink,
                            modifier = Modifier.shellLibAnimated(this).padding(start = inset, end = inset, top = 20.dp, bottom = 6.dp),
                        )
                    }
                    items(
                        count = songs.size,
                        key = { "lib_song_" + songs[it].id },
                        span = { GridItemSpan(maxLineSpan) },
                        contentType = { "song" },
                    ) { i ->
                        val song = songs[i]
                        val current = song.id == slot.currentSongId
                        Box(Modifier.shellLibAnimated(this)) {
                            slot.itemContainer(
                                HiddenLibraryItem.from(song),
                                song,
                                false,
                                {
                                    val all = playableSongs()
                                    slot.onPlaySongs(all, all.indexOf(song))
                                },
                            ) {
                                ShellLibEntry(
                                    title = song.displayTitle,
                                    meta = shellLibMeta(song, strings),
                                    art = { m -> ShellArt(song.thumbnail, m, shape = RoundedCornerShape(4.dp)) },
                                    grid = false,
                                    current = current,
                                    playing = current && slot.isPlaying,
                                )
                            }
                        }
                    }
                    if (slot.contentSearchHasMore) {
                        item(key = "lib_songs_more", contentType = "more", span = { GridItemSpan(maxLineSpan) }) {
                            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                Box(
                                    Modifier.height(36.dp).clip(CircleShape).border(1.dp, c.hairline, CircleShape)
                                        .shellPressable({ latest.onLoadMoreContentMatches() }, focusShape = CircleShape)
                                        .padding(horizontal = 18.dp),
                                    contentAlignment = Alignment.Center,
                                ) { Text(stringResource(Res.string.shell_lib_show_more), style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink) }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Header: title, filter chips, search, sort
// ---------------------------------------------------------------------------------------------

@Composable
private fun ShellLibTop(
    slot: LibraryPageSlot,
    active: UpFilter?,
    filterType: LibraryFilterType,
    sorting: LibrarySorting,
    sortType: LibrarySortType,
    onToggleSearch: () -> Unit,
    modifier: Modifier,
) {
    val c = Shell.colors
    val text = slot.text
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(Res.string.shell_lib_title), style = Shell.type.headline, color = c.ink, modifier = Modifier.weight(1f))
            if (slot.onSearchQueryChange != null) {
                ShellLibIconButton(
                    glyph = if (slot.isSearchOpen) Glyphs.Close else Glyphs.Search,
                    label = if (slot.isSearchOpen) stringResource(Res.string.shell_lib_close_search) else text.search,
                    onClick = onToggleSearch,
                    tint = c.ink,
                    size = 44.dp,
                    iconSize = if (slot.isSearchOpen) 18.dp else 22.dp,
                )
            }
        }
        ShellLibChips(slot.selectedFilter, text, slot.onFilterChange)
        slot.onSearchQueryChange?.let { onQueryChange ->
            AnimatedVisibility(
                slot.isSearchOpen,
                enter = expandVertically(ShellMotion.emphasized()) + fadeIn(ShellMotion.emphasized()),
                exit = shrinkVertically(ShellMotion.exit()) + fadeOut(ShellMotion.exit()),
            ) {
                ShellLibSearchField(slot.searchQuery, onQueryChange, text.searchPlaceholder, Modifier.padding(top = 12.dp))
            }
        }
        ShellLibSortRow(text, active, filterType, sorting, sortType, slot.isGridView, slot.onGridViewChange)
    }
}

@Composable
private fun ShellLibChips(selected: UpFilter?, text: LibraryContentText, onFilterChange: (UpFilter?) -> Unit) {
    val c = Shell.colors
    val options = UpFilter.entries.filterNot { it == UpFilter.PODCASTS && Settings.shared.hidePodcasts }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            selected != null,
            enter = expandHorizontally(ShellMotion.emphasized()) + fadeIn(ShellMotion.emphasized()),
            exit = shrinkHorizontally(ShellMotion.exit()) + fadeOut(ShellMotion.exit()),
        ) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(c.surface2)
                    .shellPressable({ onFilterChange(null) }, label = stringResource(Res.string.shell_lib_clear_filter), focusShape = CircleShape),
                contentAlignment = Alignment.Center,
            ) { Glyph(Glyphs.Close, c.ink, 14.dp) }
        }
        options.forEach { option ->
            val isSelected = selected == option
            ShellChip(text.filterLabels[option] ?: option.name, isSelected, { onFilterChange(if (isSelected) null else option) })
        }
    }
}

@Composable
private fun ShellLibSearchField(query: String, onQueryChange: (String) -> Unit, placeholder: String, modifier: Modifier) {
    val c = Shell.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val ring by animateColorAsState(
        if (focused || query.isNotEmpty()) c.accent else c.accent.copy(alpha = 0f),
        tween(200, easing = ShellMotion.Emphasized),
    )
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val style = Shell.type.body
    // H-Search's ring: a 2 dp lime border around a 2 dp gap.
    Box(modifier.fillMaxWidth().border(2.dp, ring, RoundedCornerShape(10.dp)).padding(2.dp)) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(start = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.Search, c.ink2, 18.dp)
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) Text(placeholder, style = style, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = style.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onFocusChanged { focused = it.isFocused },
                )
            }
            if (query.isNotEmpty()) {
                ShellLibIconButton(Glyphs.Close, stringResource(Res.string.shell_lib_clear_search), { onQueryChange("") }, c.ink2, 44.dp, 14.dp)
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

@Composable
private fun ShellLibSortRow(
    text: LibraryContentText,
    active: UpFilter?,
    filterType: LibraryFilterType,
    sorting: LibrarySorting,
    sortType: LibrarySortType,
    isGrid: Boolean,
    onGridViewChange: (Boolean) -> Unit,
) {
    val c = Shell.colors
    var expanded by remember { mutableStateOf(false) }
    var anchor by remember { mutableStateOf(IntOffset.Zero) }
    val ascending = sorting.direction == SortDirection.ASCENDING
    val sortLabel = text.sortLabels[sortType] ?: sortType.name
    Row(Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.trackPressPosition { anchor = it.round() }) {
            Row(
                Modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
                    .shellPressable({ expanded = true }, label = stringResource(Res.string.shell_lib_sort_by, sortLabel))
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Glyph(ShellLibGlyphs.Sort, c.ink, 16.dp)
                Spacer(Modifier.width(6.dp))
                Text(sortLabel, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ExpressiveSelectionMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                options = LibrarySortType.entries.filter { it.shellAvailableFor(active) },
                selectedOption = sortType,
                onOptionSelected = { option ->
                    Settings.shared.setLibrarySorting(filterType, LibrarySorting(LibraryContentSort.valueOf(option.name), option.shellDefaultDirection))
                },
                optionLabel = { text.sortLabels[it] ?: it.name },
                anchorOffset = anchor,
            )
        }
        if (sortType != LibrarySortType.MANUAL) {
            val rotation by animateFloatAsState(if (ascending) 180f else 0f, ShellMotion.emphasized())
            Box(
                Modifier.size(36.dp).shellPressable(
                    {
                        Settings.shared.setLibrarySorting(
                            filterType,
                            sorting.copy(direction = if (ascending) SortDirection.DESCENDING else SortDirection.ASCENDING),
                        )
                    },
                    label = stringResource(if (ascending) Res.string.shell_lib_sort_descending else Res.string.shell_lib_sort_ascending),
                    focusShape = CircleShape,
                ),
                contentAlignment = Alignment.Center,
            ) { Glyph(ShellLibGlyphs.ArrowDown, c.ink2, 16.dp, Modifier.graphicsLayer { rotationZ = rotation }) }
        }
        Spacer(Modifier.weight(1f))
        ShellLibIconButton(
            glyph = if (isGrid) ShellLibGlyphs.List else ShellLibGlyphs.Grid,
            label = if (isGrid) text.list else text.grid,
            onClick = { onGridViewChange(!isGrid) },
            tint = c.ink,
            size = 40.dp,
            iconSize = 18.dp,
        )
    }
}

@Composable
private fun ShellLibIconButton(glyph: ImageVector, label: String, onClick: () -> Unit, tint: Color, size: Dp, iconSize: Dp) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
    Box(
        Modifier.size(size).clip(CircleShape).background(bg).hoverable(source)
            .shellPressable(onClick, label = label, focusShape = CircleShape),
        contentAlignment = Alignment.Center,
    ) { Glyph(glyph, tint, iconSize) }
}

// ---------------------------------------------------------------------------------------------
// Rows and cards
// ---------------------------------------------------------------------------------------------

/**
 * One library entry, as a 64 dp row (the desktop sidebar's row) or a card. It draws no click of its
 * own: upstream's item container around it handles tap, long-press menu and swipe.
 */
@Composable
private fun ShellLibEntry(
    title: String,
    meta: String,
    art: @Composable (Modifier) -> Unit,
    grid: Boolean,
    current: Boolean = false,
    playing: Boolean = false,
    downloaded: Boolean = false,
    pinned: Boolean = false,
    badge: String? = null,
    onPlay: (() -> Unit)? = null,
    handle: (@Composable () -> Unit)? = null,
) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val titleColor = if (current) c.accent else c.ink
    if (grid) {
        Column(Modifier.fillMaxWidth().hoverable(source).cardHover(hovered)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
                art(Modifier.fillMaxSize())
                if (badge != null) ShellLibBadge(badge, Modifier.align(Alignment.TopEnd).padding(8.dp))
                if (handle != null) {
                    Box(Modifier.align(Alignment.TopStart).padding(4.dp).clip(CircleShape).background(c.ground.copy(alpha = 0.6f))) { handle() }
                }
                if (onPlay != null) HoverPlayButton(hovered, 44.dp, onPlay, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (playing) {
                    Spacer(Modifier.width(6.dp))
                    Equalizer(height = 12.dp)
                }
            }
            ShellLibMetaLine(meta, downloaded, pinned)
        }
    } else {
        val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
        Row(
            Modifier.fillMaxWidth().height(64.dp).clip(RoundedCornerShape(6.dp)).background(bg).hoverable(source)
                .padding(horizontal = if (ShellPlatform.desktop) 8.dp else 0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            art(Modifier.size(56.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Shell.type.body, color = titleColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                ShellLibMetaLine(meta, downloaded, pinned)
            }
            if (playing) {
                Spacer(Modifier.width(8.dp))
                Equalizer(height = 12.dp)
            }
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                ShellLibBadge(badge)
            }
            if (onPlay != null && ShellPlatform.desktop) HoverPlayButton(hovered, 32.dp, onPlay, Modifier.padding(start = 8.dp))
            handle?.invoke()
        }
    }
}

@Composable
private fun ShellLibMetaLine(meta: String, downloaded: Boolean, pinned: Boolean) {
    val c = Shell.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (pinned) {
            Glyph(ShellLibGlyphs.Pin, c.accent, 12.dp, contentDescription = stringResource(Res.string.shell_lib_pinned))
            Spacer(Modifier.width(6.dp))
        }
        if (downloaded) {
            Glyph(Glyphs.Download, c.accent, 12.dp, contentDescription = stringResource(Res.string.shell_lib_downloaded))
            Spacer(Modifier.width(6.dp))
        }
        Text(meta, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ShellLibBadge(label: String, modifier: Modifier = Modifier) {
    val c = Shell.colors
    Box(modifier.clip(CircleShape).background(c.surface3).padding(horizontal = 10.dp, vertical = 4.dp)) {
        Text(label, style = Shell.type.index, color = c.ink, maxLines = 1)
    }
}

@Composable
private fun ShellLibItemArt(item: YTItem, modifier: Modifier) {
    val shape = if (item is ArtistItem) CircleShape else RoundedCornerShape(4.dp)
    if (item is PlaylistItem) {
        val cover = libraryCover(item)
        val collage = item.takeIf { it.isLocal && it.customThumbnail.isNullOrBlank() }?.artworkThumbnails.orEmpty()
        when {
            cover != null -> Image(painterResource(cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
            collage.size >= 4 -> ShellLibCollage(collage, modifier.clip(shape))
            else -> ShellArt(libraryArtwork(item.id, item.thumbnail, item.customThumbnail) ?: collage.firstOrNull(), modifier, shape = shape)
        }
    } else {
        ShellArt(item.displayThumbnailUrl, modifier, shape = shape)
    }
}

@Composable
private fun ShellLibCollage(urls: List<String>, modifier: Modifier) {
    Column(modifier) {
        for (row in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 2) {
                    ShellArt(urls[row * 2 + col], Modifier.weight(1f).fillMaxHeight(), shape = RectangleShape)
                }
            }
        }
    }
}

/** Auto playlists as Shell tiles: Liked Songs is lime (it's yours), the rest tonal; custom covers win. */
@Composable
private fun ShellLibAutoArt(playlist: LibraryAutoPlaylist, modifier: Modifier, glyphSize: Dp) {
    val c = Shell.colors
    val custom = Settings.shared.collectionCovers[playlist.type]
    if (custom != null) {
        ShellArt(custom, modifier, shape = RoundedCornerShape(4.dp))
        return
    }
    val liked = playlist.type == "liked"
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(if (liked) c.accent else c.surface3), contentAlignment = Alignment.Center) {
        Glyph(shellLibAutoGlyph(playlist), if (liked) c.onAccent else c.ink, glyphSize)
    }
}

private fun shellLibAutoGlyph(playlist: LibraryAutoPlaylist): ImageVector =
    when (playlist.type) {
        "liked" -> Glyphs.Heart
        "downloaded" -> Glyphs.Download
        "top100" -> Glyphs.Stats
        "cached" -> Glyphs.History
        "uploaded" -> Glyphs.Share
        "podcasts" -> Glyphs.Radio
        else -> playlist.icon
    }

@Composable
private fun shellLibMeta(item: YTItem, s: ShellLibStrings): String =
    when (item) {
        is SongItem -> s.pair(s.song, item.artists.joinToString(", ") { it.displayName })
        is AlbumItem -> s.pair(s.album, item.artists.joinToString(", ") { it.displayName })
        is ArtistItem -> s.artist
        is PlaylistItem ->
            s.pair(
                s.playlist,
                item.author?.let { ArtistNameAliases.resolve(null, it) }?.takeIf(String::isNotBlank)
                    ?: item.exactSongCount?.let { pluralStringResource(Res.plurals.shell_lib_song_count, it, it) },
            )
        is PodcastItem -> s.pair(s.podcast, item.author?.let { ArtistNameAliases.resolve(null, it) })
        is EpisodeItem -> s.pair(s.episode, item.author?.displayName)
        else -> ""
    }

private class ShellLibStrings(
    private val pairFormat: String,
    val playlist: String,
    val album: String,
    val song: String,
    val podcast: String,
    val episode: String,
    val artist: String,
) {
    fun pair(a: String, b: String?): String = if (b.isNullOrBlank()) a else pairFormat.replace("\u0001", a).replace("\u0002", b)
}

// ---------------------------------------------------------------------------------------------
// States: loading, error, empty
// ---------------------------------------------------------------------------------------------

@Composable
private fun ShellLibSkeleton(grid: Boolean) {
    val c = Shell.colors
    val pulse by rememberInfiniteTransition().animateFloat(0.45f, 1f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    val faded = Modifier.graphicsLayer { alpha = pulse }
    if (grid) {
        Column(Modifier.fillMaxWidth().then(faded)) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(4.dp)).background(c.surface2))
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(0.45f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
        }
    } else {
        Row(Modifier.fillMaxWidth().height(64.dp).then(faded), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.fillMaxWidth(0.55f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
                Box(Modifier.fillMaxWidth(0.35f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
            }
        }
    }
}

@Composable
private fun ShellLibError(onRetry: (() -> Unit)?, modifier: Modifier) {
    val c = Shell.colors
    Column(
        modifier.fillMaxWidth().padding(vertical = 8.dp).clip(ShellShapes.Card).background(c.surface2).padding(16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(Res.string.shell_lib_error), style = Shell.type.body, color = c.ink)
        Text(stringResource(Res.string.shell_lib_error_hint), style = Shell.type.bodySmall, color = c.ink2)
        if (onRetry != null) {
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier.height(36.dp).clip(CircleShape).background(c.ink).shellPressable(onRetry, focusShape = CircleShape).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(Res.string.shell_lib_retry), style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ground) }
        }
    }
}

@Composable
private fun ShellLibEmpty(message: String) {
    val c = Shell.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp).semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Glyph(Glyphs.Library, c.ink2, 36.dp)
        Text(message, style = Shell.type.body, color = c.ink2, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------------------------
// Manual order: drag handle, move menu and the drag itself
// ---------------------------------------------------------------------------------------------

/**
 * Drag to reorder (press-and-drag the handle; the item lifts and follows the pointer), or tap it for
 * a move menu, which is also what screen readers get.
 */
@Composable
private fun ShellLibHandle(
    reorder: ShellLibReorder,
    key: String,
    title: String,
    index: Int,
    last: Int,
    onDragStart: () -> Unit,
    onMove: (Int) -> Unit,
) {
    val c = Shell.colors
    var menu by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.shell_lib_reorder, title)
    val latestStart by rememberUpdatedState(onDragStart)
    Box {
        Box(
            Modifier.size(40.dp)
                .pointerHoverIcon(PointerIcon.Hand)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                    onClick {
                        menu = true
                        true
                    }
                }.pointerInput(key) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Keep the press for the handle: no item click, long-press menu or list scroll.
                        down.consume()
                        var overSlop = Offset.Zero
                        val slop = awaitTouchSlopOrCancellation(down.id) { change, over ->
                            change.consume()
                            overSlop = over
                        }
                        if (slop == null) {
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && !up.pressed) {
                                up.consume()
                                menu = true
                            }
                        } else if (reorder.start(key)) {
                            try {
                                latestStart()
                                reorder.dragBy(overSlop)
                                drag(slop.id) { change ->
                                    reorder.dragBy(change.positionChange())
                                    change.consume()
                                }
                            } finally {
                                reorder.end()
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center,
        ) { Glyph(ShellLibGlyphs.Handle, c.ink2, 20.dp) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (index > 0) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_lib_move_top)) }, onClick = { menu = false; onMove(0) })
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_lib_move_up)) }, onClick = { menu = false; onMove(index - 1) })
            }
            if (index < last) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_lib_move_down)) }, onClick = { menu = false; onMove(index + 1) })
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_lib_move_bottom)) }, onClick = { menu = false; onMove(last) })
            }
        }
    }
}

/**
 * Drag state for manual order. The dragged item draws where the pointer has taken it (its start slot
 * plus the pointer's travel), whatever slot it currently occupies, so swaps and auto-scroll never make
 * it jump. When its centre enters another item, the two swap through upstream's `moveLibraryItems`.
 */
@Stable
private class ShellLibReorder {
    var key by mutableStateOf<String?>(null)
        private set
    var total by mutableStateOf(Offset.Zero)
        private set
    private var origin by mutableStateOf(Offset.Zero)

    // Refreshed after every composition.
    var grid: LazyGridState? = null
    var enabled = false
    var shown: List<YTItem> = emptyList()
    var move: (YTItem, YTItem) -> Boolean = { _, _ -> false }

    // After a swap, wait for the grid to lay the item out in its new slot before testing again.
    private var expectIndex: Int? = null
    private var expectSince: TimeMark? = null

    private fun info(k: String): LazyGridItemInfo? = grid?.layoutInfo?.visibleItemsInfo?.firstOrNull { it.key == k }

    fun start(k: String): Boolean {
        if (!enabled || key != null) return false
        val i = info(k) ?: return false
        origin = Offset(i.offset.x.toFloat(), i.offset.y.toFloat())
        total = Offset.Zero
        expectIndex = null
        key = k
        return true
    }

    fun dragBy(delta: Offset) {
        if (key == null) return
        total += delta
        check()
    }

    fun end() {
        key = null
        total = Offset.Zero
        expectIndex = null
    }

    fun visualTop(): Float = origin.y + total.y

    fun translation(k: String): Offset {
        val i = info(k) ?: return Offset.Zero
        return Offset(origin.x + total.x - i.offset.x, origin.y + total.y - i.offset.y)
    }

    fun check() {
        val k = key ?: return
        val layout = grid?.layoutInfo ?: return
        val me = layout.visibleItemsInfo.firstOrNull { it.key == k } ?: return
        val expected = expectIndex
        if (expected != null) {
            val waited = expectSince?.elapsedNow()?.let { it > 250.milliseconds } ?: true
            if (me.index != expected && !waited) return
            expectIndex = null
        }
        val cx = origin.x + total.x + me.size.width / 2f
        val cy = origin.y + total.y + me.size.height / 2f
        val target =
            layout.visibleItemsInfo.firstOrNull { info ->
                val tk = info.key
                tk != k && tk is String && tk.startsWith(SHELL_LIB_ITEM) &&
                    cx >= info.offset.x && cx < info.offset.x + info.size.width &&
                    cy >= info.offset.y && cy < info.offset.y + info.size.height
            } ?: return
        val from = shown.firstOrNull { shellLibItemKey(it) == k } ?: return
        val to = shown.firstOrNull { shellLibItemKey(it) == target.key } ?: return
        if (move(from, to)) {
            expectIndex = target.index
            expectSince = TimeSource.Monotonic.markNow()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------------------------

/** Filter changes crossfade: leaving items fade out, arriving ones fade in, the rest glide. */
private fun Modifier.shellLibAnimated(scope: LazyGridItemScope): Modifier =
    with(scope) {
        this@shellLibAnimated.animateItem(
            fadeInSpec = tween(ShellMotion.EMPHASIZED_MS, easing = ShellMotion.Emphasized),
            placementSpec = tween(ShellMotion.EMPHASIZED_MS, easing = ShellMotion.Emphasized),
            fadeOutSpec = tween(ShellMotion.EXIT_MS, easing = ShellMotion.Exit),
        )
    }

/** Remembers which items have risen in (H-Motion), so the page animates once, not on every scroll. */
private class ShellLibRise(var settled: Boolean) {
    val seen = HashSet<Any>()
}

@Composable
private fun rememberShellLibRise(state: LazyGridState, ready: Boolean): ShellLibRise {
    val rise = remember(state) { ShellLibRise(settled = state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0) }
    LaunchedEffect(rise) {
        if (rise.settled) return@LaunchedEffect
        snapshotFlow { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0 }.first { it }
        rise.settled = true
    }
    // Once the first screen has risen, later changes (filters, search) crossfade instead.
    LaunchedEffect(rise, ready) {
        if (!ready || rise.settled) return@LaunchedEffect
        delay((SHELL_LIB_RISE_MAX * ShellMotion.STAGGER_MS + ShellMotion.EMPHASIZED_MS + 200).toLong())
        rise.settled = true
    }
    return rise
}

/** Fade in and rise 10 dp, 300 ms Emphasized, 40 ms apart for the first 12; once per [key]. */
@Composable
private fun Modifier.shellLibRise(rise: ShellLibRise, key: Any, index: Int): Modifier {
    val animate = remember(rise, key) { rise.seen.add(key) && !rise.settled }
    if (!animate) return this
    val progress = remember(rise, key) { Animatable(0f) }
    LaunchedEffect(progress) {
        val delayMs = index.coerceIn(0, SHELL_LIB_RISE_MAX) * ShellMotion.STAGGER_MS
        progress.animateTo(1f, tween(ShellMotion.EMPHASIZED_MS, delayMs, ShellMotion.Emphasized))
    }
    return graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 10.dp.toPx()
    }
}

// ---------------------------------------------------------------------------------------------
// Glyphs this page adds (same line style as components/Glyphs.kt)
// ---------------------------------------------------------------------------------------------

private object ShellLibGlyphs {
    private fun vector(name: String, path: String, filled: Boolean, stroke: Float = 2f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(path),
                fill = if (filled) SolidColor(Color.Black) else null,
                stroke = if (filled) null else SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val Sort = vector("lib_sort", "M7 4v16M3 16l4 4 4-4M17 20V4M13 8l4-4 4 4", filled = false)
    val Grid = vector("lib_grid", "M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z", filled = false)
    val List = vector("lib_list", "M4 6h16M4 12h16M4 18h16", filled = false)
    val Pin = vector("lib_pin", "M15 3l6 6-3 1-4 4 1 5-2 2-4-4-5 5-1-1 5-5-4-4 2-2 5 1 4-4z", filled = true)
    val Handle = vector("lib_handle", "M5 9h14M5 15h14", filled = false)
    val ArrowDown = vector("lib_arrow_down", "M12 5v14M6 13l6 6 6-6", filled = false)
}

// ---------------------------------------------------------------------------------------------
// Preparation (off the main thread), matching upstream's prepareLibraryItems
// ---------------------------------------------------------------------------------------------

private class ShellLibInput(
    val items: List<YTItem>,
    val filter: UpFilter?,
    val hidden: Set<HiddenLibraryItemKey>,
    val sort: LibrarySortType,
    val direction: SortDirection,
    val manualOrder: String,
    val query: String,
    val matchedSongs: List<SongItem>,
    val aliases: Map<String, String>,
)

/** The library's own items, and songs found inside your playlists (search only). */
private class ShellLibPrepared(val items: List<YTItem>, val songs: List<SongItem>)

private fun prepareShellLibrary(input: ShellLibInput): ShellLibPrepared {
    val blocked = BlockedContent.snapshot()
    val visible =
        BlockedContent.filterItems(input.items, blocked)
            .filterNot { it is EpisodeItem || (it is SongItem && input.filter != UpFilter.TRACKS) }
            .shellLibFilter(input.filter, input.hidden)
    val query = input.query.trim()
    val matches = if (query.isEmpty()) visible else visible.filterForLibrarySearch(query)
    val sorted = shellLibSort(matches.distinctBy { it::class to it.id }, input.sort, input.manualOrder, input.direction, input.aliases)
    val songs =
        if (query.isEmpty() || (input.filter != null && input.filter != UpFilter.TRACKS)) {
            emptyList()
        } else {
            val listed = sorted.filterIsInstance<SongItem>().mapTo(HashSet()) { it.id }
            BlockedContent.filterSongs(input.matchedSongs, blocked)
                .shellLibFilter(input.filter, input.hidden)
                .filterIsInstance<SongItem>()
                .filter { it.id !in listed }
                .distinctBy { it.id }
        }
    return ShellLibPrepared(sorted, songs)
}

private fun PlaylistItem.isShellLibSystem(): Boolean =
    id.equals("LM", true) || id.equals("VLLM", true) || id.startsWith("LM:") ||
        id.equals("RDPN", true) || id.equals("VLRDPN", true) || id.equals("SE", true) || id.equals("VLSE", true)

private fun List<YTItem>.shellLibFilter(kind: UpFilter?, hidden: Set<HiddenLibraryItemKey>): List<YTItem> {
    val visible = filter { item -> (item !is PlaylistItem || !item.isShellLibSystem()) && HiddenLibraryItem.from(item).key !in hidden }
    return when (kind) {
        UpFilter.TRACKS -> visible.filterIsInstance<SongItem>().filterNot { it.isPodcast }
        UpFilter.PLAYLISTS -> visible.filterIsInstance<PlaylistItem>()
        UpFilter.ARTISTS -> visible.filterIsInstance<ArtistItem>()
        UpFilter.ALBUMS -> visible.filterIsInstance<AlbumItem>()
        UpFilter.PODCASTS -> visible.filterIsInstance<PodcastItem>()
        null -> visible
    }
}

private fun LibrarySortType.shellAvailableFor(active: UpFilter?): Boolean =
    when (this) {
        LibrarySortType.DOWNLOADED -> active == UpFilter.TRACKS
        LibrarySortType.ARTIST, LibrarySortType.YEAR, LibrarySortType.LENGTH -> active == UpFilter.ALBUMS
        LibrarySortType.SONG_COUNT, LibrarySortType.PLAYTIME ->
            active == null || active == UpFilter.ARTISTS || active == UpFilter.ALBUMS || active == UpFilter.PLAYLISTS
        else -> true
    }

private val LibrarySortType.shellDefaultDirection: SortDirection
    get() =
        if (this == LibrarySortType.RECENTLY_ADDED || this == LibrarySortType.LAST_UPDATED || this == LibrarySortType.DOWNLOADED) {
            SortDirection.DESCENDING
        } else {
            SortDirection.ASCENDING
        }

private fun shellLibSort(
    items: List<YTItem>,
    sort: LibrarySortType,
    manualOrder: String,
    direction: SortDirection,
    aliases: Map<String, String>,
): List<YTItem> =
    when (sort) {
        LibrarySortType.DOWNLOADED -> items.shellLibSortedBy(direction) { (it as? SongItem)?.downloadedAt }
        LibrarySortType.RECENTLY_ADDED -> items.shellLibSortedBy(direction) { it.libraryAddedAt }
        LibrarySortType.NAME ->
            items.shellLibSortedBy(direction) { item ->
                (if (item is ArtistItem) ArtistNameAliases.resolve(aliases, item.id, item.title) else item.title).lowercase()
            }
        LibrarySortType.LAST_UPDATED -> items.shellLibSortedBy(direction) { it.libraryUpdatedAt }
        LibrarySortType.ARTIST -> items.shellLibSortedBy(direction) { (it as? AlbumItem)?.artists?.firstOrNull()?.name?.lowercase() }
        LibrarySortType.YEAR -> items.shellLibSortedBy(direction) { (it as? AlbumItem)?.year?.toIntOrNull() }
        LibrarySortType.SONG_COUNT ->
            items.shellLibSortedBy(direction) {
                when (it) {
                    is ArtistItem -> it.songCount
                    is AlbumItem -> it.songCount
                    is PlaylistItem -> it.exactSongCount
                    else -> null
                }
            }
        LibrarySortType.LENGTH -> items.shellLibSortedBy(direction) { (it as? AlbumItem)?.duration }
        LibrarySortType.PLAYTIME ->
            items.shellLibSortedBy(direction) {
                when (it) {
                    is ArtistItem -> it.playtime
                    is AlbumItem -> it.playtime
                    is PlaylistItem -> it.playtime
                    else -> null
                }
            }
        LibrarySortType.MANUAL -> orderLibraryItems(items, manualOrder)
    }

/** Sorts by [value], missing values last, as upstream. */
private fun <T : Comparable<T>> List<YTItem>.shellLibSortedBy(direction: SortDirection, value: (YTItem) -> T?): List<YTItem> =
    map { it to value(it) }
        .sortedWith { first, second ->
            val left = first.second
            val right = second.second
            when {
                left == null && right == null -> 0
                left == null -> 1
                right == null -> -1
                direction == SortDirection.ASCENDING -> left.compareTo(right)
                else -> right.compareTo(left)
            }
        }.map { it.first }
