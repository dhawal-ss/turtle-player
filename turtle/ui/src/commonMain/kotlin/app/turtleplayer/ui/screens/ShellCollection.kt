package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.turtleplayer.ui.components.CheckGlyph
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.PlayPauseButton
import com.metrolist.shared.ui.playback.LocalPlaybackController
import app.turtleplayer.ui.generated.resources.shell_col_pause
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_col_about
import app.turtleplayer.ui.generated.resources.shell_col_add_song
import app.turtleplayer.ui.generated.resources.shell_col_back
import app.turtleplayer.ui.generated.resources.shell_col_close_search
import app.turtleplayer.ui.generated.resources.shell_col_downloaded
import app.turtleplayer.ui.generated.resources.shell_col_edit
import app.turtleplayer.ui.generated.resources.shell_col_explicit
import app.turtleplayer.ui.generated.resources.shell_col_explicit_badge
import app.turtleplayer.ui.generated.resources.shell_col_find
import app.turtleplayer.ui.generated.resources.shell_col_find_album
import app.turtleplayer.ui.generated.resources.shell_col_find_playlist
import app.turtleplayer.ui.generated.resources.shell_col_head_album
import app.turtleplayer.ui.generated.resources.shell_col_head_duration
import app.turtleplayer.ui.generated.resources.shell_col_head_number
import app.turtleplayer.ui.generated.resources.shell_col_head_title
import app.turtleplayer.ui.generated.resources.shell_col_kind_album
import app.turtleplayer.ui.generated.resources.shell_col_kind_playlist
import app.turtleplayer.ui.generated.resources.shell_col_loading
import app.turtleplayer.ui.generated.resources.shell_col_more
import app.turtleplayer.ui.generated.resources.shell_col_move_bottom
import app.turtleplayer.ui.generated.resources.shell_col_move_down
import app.turtleplayer.ui.generated.resources.shell_col_move_top
import app.turtleplayer.ui.generated.resources.shell_col_move_up
import app.turtleplayer.ui.generated.resources.shell_col_no_results
import app.turtleplayer.ui.generated.resources.shell_col_play
import app.turtleplayer.ui.generated.resources.shell_col_reorder
import app.turtleplayer.ui.generated.resources.shell_col_save
import app.turtleplayer.ui.generated.resources.shell_col_saved
import app.turtleplayer.ui.generated.resources.shell_col_shuffle
import app.turtleplayer.ui.theme.LocalShellGround
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.generated.resources.album_order_original
import com.metrolist.shared.generated.resources.days
import com.metrolist.shared.generated.resources.duration_three_units
import com.metrolist.shared.generated.resources.duration_two_units
import com.metrolist.shared.generated.resources.hours
import com.metrolist.shared.generated.resources.less_than_one_minute
import com.metrolist.shared.generated.resources.minutes
import com.metrolist.shared.generated.resources.n_hidden_song
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.generated.resources.partial_song_count
import com.metrolist.shared.generated.resources.song_count_with_hidden
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PlaylistSongSort
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.SortDirection
import com.metrolist.shared.model.albumTotalDuration
import com.metrolist.shared.model.canEdit
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.model.filterForPlaylistSearch
import com.metrolist.shared.model.indexOfPlaylistOccurrence
import com.metrolist.shared.model.playlistLazyItemKeys
import com.metrolist.shared.model.playlistSearchIndices
import com.metrolist.shared.model.playlistTotalDuration
import com.metrolist.shared.model.sortedForPlaylist
import com.metrolist.shared.model.withLocalArtwork
import com.metrolist.shared.state.SongMetadataState
import com.metrolist.shared.turtle.AlbumPageSlot
import com.metrolist.shared.turtle.PlaylistPageSlot
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.LazyListScrollbar
import com.metrolist.shared.ui.components.PlaylistOrderControls
import com.metrolist.shared.ui.components.UnavailableSongTaps
import com.metrolist.shared.ui.components.libraryCover
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.ui.navigation.AlbumArtworkRole
import com.metrolist.shared.ui.navigation.AlbumArtworkSource
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.navigation.albumArtworkTransition
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.screens.playlistOrderControlsText
import com.metrolist.shared.ui.utils.carouselScroll
import com.metrolist.shared.ui.utils.desktopArrowScroll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import com.metrolist.shared.generated.resources.Res as SharedRes

/*
 * Shell's Playlist and Album pages (turtle/HOOKS.md #34, #35). They take every argument of upstream's
 * PlaylistContent / AlbumContent and keep each feature: upstream's own pieces (custom header, loading
 * header, order and selection controls, pagination, empty state, trailing content) are drawn inside
 * our layout, and every track row goes through upstream's track container (swipe to queue, selection).
 */

private val CoverShape = RoundedCornerShape(8.dp)
private val RowShape = RoundedCornerShape(6.dp)
private val ContentMaxWidth = 1400.dp

private val AlbumSortOptions =
    setOf(
        PlaylistSongSort.ORIGINAL,
        PlaylistSongSort.TITLE,
        PlaylistSongSort.ARTIST,
        PlaylistSongSort.DURATION,
        PlaylistSongSort.LISTENING_TIME,
    )

/** Line glyphs this page needs beyond the shared set, drawn in the same style as [Glyphs]. */
private object CollectionGlyphs {
    private fun line(name: String, path: String, stroke: Float = 1.8f): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            addPath(
                pathData = addPathNodes(path),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }.build()

    val ChevronLeft = line("chevron_left", "M15 5l-7 7 7 7", stroke = 2.2f)
    val Edit = line("edit", "M4 20h4L19 9l-4-4L4 16zM13.5 6.5l4 4")
    val AddSong = line("add_song", "M3 6h12M3 11h12M3 16h7M17.5 13v8M13.5 17h8", stroke = 2f)
    val Clock = line("clock", "M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18zM12 7v5l3 2")
    val Drag = Glyphs.Drag
}

// ---------------------------------------------------------------------------------------------
// Playlist
// ---------------------------------------------------------------------------------------------

@Composable
fun ShellPlaylistPage(slot: PlaylistPageSlot) = ShellTheme {
    val desktop = ShellPlatform.desktop
    val playlist = slot.playlist
    val header = slot.header
    val loadingHeader = slot.loadingHeader
    val orderControls = slot.orderControls
    val aboveOrderControls = slot.aboveOrderControls
    val emptyContent = slot.emptyContent
    val paginationContent = slot.paginationContent
    val trackTrailingContent = slot.trackTrailingContent
    val onTrackLongClick = slot.onTrackLongClick
    val onArtistClick = slot.onArtistClick
    val onSearchQueryChange = slot.onSearchQueryChange
    val searchOpen = slot.searchOpen
    val reorderMode = slot.reorderMode

    val canEdit = playlist?.canEdit == true
    val onAddSong = slot.onAddSong.takeIf { canEdit }
    val onEdit = slot.onEdit.takeIf { canEdit }
    val onAddToLibrary = slot.onFavorite.takeUnless { playlist?.isEditable == true || playlist?.isLocal == true }
    val headerPlaylist = remember(playlist, slot.artworkSongs) { playlist?.withLocalArtwork(slot.artworkSongs) }
    val meta = playlistMeta(slot)

    // Search, exactly as upstream: the caller may own the open state and the query, or leave both to us.
    var internalSearchOpen by remember(playlist?.id) { mutableStateOf(slot.searchQuery.isNotBlank()) }
    val isSearchOpen = searchOpen ?: internalSearchOpen
    val setSearchOpen: (Boolean) -> Unit = { if (searchOpen != null) slot.onSearchOpenChange(it) else internalSearchOpen = it }
    var internalSearchQuery by remember(playlist?.id) { mutableStateOf("") }
    val effectiveQuery = if (onSearchQueryChange != null) slot.searchQuery else internalSearchQuery
    val searchListState = rememberLazyListState()
    val activeListState = if (effectiveQuery.isBlank()) slot.listState else searchListState
    val updateQuery: (String) -> Unit = { q -> if (onSearchQueryChange != null) onSearchQueryChange(q) else internalSearchQuery = q }
    val updateSearchQuery: (String) -> Unit = { q ->
        // Searching starts where the full list was, then the find row scrolls to the top.
        if (effectiveQuery.isBlank() && q.isNotBlank()) {
            searchListState.requestScrollToItem(slot.listState.firstVisibleItemIndex, slot.listState.firstVisibleItemScrollOffset)
        }
        updateQuery(q)
    }
    val toggleSearch: () -> Unit = {
        setSearchOpen(!isSearchOpen)
        if (isSearchOpen) updateQuery("")
    }
    NavigationBackHandler(enabled = isSearchOpen, onBack = toggleSearch)

    val displayedIndices =
        remember(slot.songs, effectiveQuery, onSearchQueryChange == null) {
            if (onSearchQueryChange == null && effectiveQuery.isNotBlank()) slot.songs.playlistSearchIndices(effectiveQuery) else null
        }
    val displayedSongs = remember(slot.songs, displayedIndices) { displayedIndices?.map(slot.songs::get) ?: slot.songs }
    val computedKeys = remember(displayedSongs) { displayedSongs.playlistLazyItemKeys() }
    val songKeys = slot.preparedSongKeys ?: computedKeys
    val suggestions = if (effectiveQuery.isBlank()) slot.suggestions else emptyList()
    val showPlayback = displayedSongs.isNotEmpty()
    val playback = LocalPlaybackController.current

    LaunchedEffect(reorderMode, isSearchOpen) {
        if (reorderMode && isSearchOpen) {
            setSearchOpen(false)
            updateQuery("")
        }
    }

    val headerIndex = if (header != null) 1 else 0
    val showFindRow = loadingHeader == null && (orderControls != null || aboveOrderControls != null || slot.searchEnabled || isSearchOpen)
    LaunchedEffect(isSearchOpen, activeListState, loadingHeader, displayedSongs.size) {
        if (isSearchOpen) activeListState.animateScrollToItem(headerIndex + 1)
    }

    // Drag to reorder (upstream uses a library :turtle-ui can't see, so Shell drives the same callbacks).
    val scope = rememberCoroutineScope()
    val edgePx = with(LocalDensity.current) { 64.dp.toPx() }
    val reorder = remember(activeListState) { CollectionReorder(activeListState, scope) }
    SideEffect { reorder.update(songKeys, slot.onReorder, slot.onReorderDragStarted, slot.onReorderFinished, edgePx) }
    LaunchedEffect(reorderMode) { if (!reorderMode) reorder.end() }
    val moveAndCommit: (Int, Int) -> Unit = { from, to ->
        activeListState.requestScrollToItem(activeListState.firstVisibleItemIndex, activeListState.firstVisibleItemScrollOffset)
        slot.onReorder(from, to)
        slot.onReorderFinished()
    }

    val rise = rememberRiseIn(slot.listState)
    val itemModifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()
    val rowModifier = itemModifier.padding(horizontal = 8.dp)
    val tintUrl = playlistTintUrl(headerPlaylist, slot)
    val kind = stringResource(Res.string.shell_col_kind_playlist)
    val findLabel = stringResource(Res.string.shell_col_find_playlist)
    // Read only in the header, so play/pause and song changes recompose the header alone.
    val calm = playback.rememberCalmState()
    val markPlaylist: () -> Unit = { playlist?.id?.let { ShellQueue.markSource(it, slot.songs) } }

    CollectionFrame(
        modifier = slot.modifier,
        listState = activeListState,
        contentPadding = slot.contentPadding,
        tintUrl = tintUrl,
        title = playlist?.title.orEmpty(),
        collapseAfter = headerIndex + 1,
        onBack = if (isSearchOpen) toggleSearch else slot.onBackClick,
        selectionControls = slot.selectionControls.takeIf { slot.selectionActive },
        busy = slot.isLoading && displayedSongs.isNotEmpty(),
        arrowScroll = reorder.draggingKey == null,
    ) {
        if (header != null) item(key = "custom_header") { Box(itemModifier) { header() } }

        item(key = "header") {
            Box(itemModifier) {
                when {
                    loadingHeader != null -> loadingHeader()
                    playlist == null && slot.isLoading -> SkeletonHeader()
                    else -> {
                        val author = playlist?.author?.takeIf { it.isNotBlank() }
                        val authorId = playlist?.authorId
                        val byline: (@Composable () -> Unit)? =
                            if (author != null) {
                                {
                                    AuthorLine(
                                        name = author,
                                        thumbnail = playlist?.authorThumbnail,
                                        onClick = if (authorId != null && onArtistClick != null) ({ onArtistClick(authorId) }) else null,
                                    )
                                }
                            } else {
                                null
                            }
                        val description = playlist?.description?.takeIf { it.isNotBlank() }
                        val now = calm.value
                        val active = playlist != null && ShellQueue.isSourceActive(playlist.id, slot.songs, now)
                        CollectionHeader(
                            artKey = playlist?.id,
                            kind = kind,
                            title = playlist?.title.orEmpty(),
                            cover = { m -> PlaylistCover(headerPlaylist, slot, m) },
                            byline = byline,
                            meta = meta,
                            description = if (description != null) ({ Description(description, collapsedLines = 2) }) else null,
                            actions =
                                CollectionActions(
                                    showPlayback = showPlayback,
                                    playing = active && now.isPlaying,
                                    onPlay = {
                                        if (active) {
                                            playback.togglePlayPause()
                                        } else {
                                            markPlaylist()
                                            slot.onPlayAll()
                                        }
                                    },
                                    onShuffle = {
                                        markPlaylist()
                                        slot.onShuffle()
                                    },
                                    isFavorite = slot.isFavorite,
                                    onFavorite = onAddToLibrary,
                                    onEdit = onEdit,
                                    onAddSong = onAddSong,
                                    onMore = slot.onMoreOptions,
                                    onSmartShuffle = { ShellSmartShuffle.play(playback, slot.songs, playlist?.title.orEmpty(), playlist?.id, hasMore = slot.hasMoreSongs) },
                                ),
                        )
                    }
                }
            }
        }

        if (showFindRow) {
            item(key = "order_controls") {
                FindRow(
                    open = isSearchOpen,
                    query = effectiveQuery,
                    placeholder = findLabel,
                    onQueryChange = updateSearchQuery,
                    onClose = toggleSearch,
                    onToggle = toggleSearch.takeIf { slot.searchEnabled && !reorderMode && searchOpen == null },
                    above = aboveOrderControls,
                    controls = orderControls,
                    modifier = itemModifier,
                )
            }
        }

        if (displayedSongs.isEmpty() && effectiveQuery.isNotBlank() && paginationContent == null) {
            item(key = "search_no_results") { NoResults(effectiveQuery, itemModifier) }
        } else if (displayedSongs.isEmpty() && emptyContent != null) {
            item(key = "empty") { Box(itemModifier, contentAlignment = Alignment.Center) { emptyContent() } }
        } else if (displayedSongs.isEmpty() && slot.isLoading && loadingHeader == null) {
            item(key = "skeleton") { SkeletonRows(itemModifier) }
        }

        if (desktop && displayedSongs.isNotEmpty()) {
            item(key = "table_head") { TableHead(numbered = true, albumColumn = true, modifier = rowModifier) }
        }

        itemsIndexed(displayedSongs, key = { index, _ -> songKeys[index] }, contentType = { _, _ -> "song" }) { index, song ->
            val key = songKeys[index]
            val sourceIndex = displayedIndices?.get(index) ?: index
            val dragging = reorderMode && reorder.draggingKey == key
            val onMenu = { slot.onTrackMenuClick(song, sourceIndex) }
            val longClick: (() -> Unit)? =
                when {
                    reorderMode -> null
                    onTrackLongClick != null -> { { onTrackLongClick(song) } }
                    else -> onMenu
                }
            val itemMod =
                rowModifier.riseIn(rise, key, index).then(
                    when {
                        !reorderMode -> Modifier
                        dragging -> Modifier.zIndex(1f).graphicsLayer { translationY = reorder.offsetOf(key) }
                        else -> Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)
                    },
                )
            Box(itemMod) {
                slot.trackContainer(song, reorderMode) {
                    TrackRow(
                        song = song,
                        number = if (desktop) index + 1 else null,
                        showArt = true,
                        albumColumn = desktop,
                        table = desktop,
                        currentSongId = slot.currentSongId,
                        isPlaying = slot.isPlaying,
                        downloaded = song.id in slot.downloadedSongIds,
                        selected = slot.isTrackSelected(song),
                        dragging = dragging,
                        onClick = {
                            markPlaylist()
                            slot.onTrackClick(song, sourceIndex)
                        },
                        longClick = longClick,
                        onMenu = onMenu,
                        onArtistClick = onArtistClick,
                        trailing = if (trackTrailingContent != null) ({ trackTrailingContent(song, sourceIndex) }) else null,
                        leading =
                            if (reorderMode) {
                                { ReorderHandle(key, reorder, index, displayedSongs.size, moveAndCommit) }
                            } else {
                                null
                            },
                        modifier =
                            if (reorderMode) {
                                Modifier.pointerInput(key, reorder) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { _: Offset -> reorder.start(key) },
                                        onDragEnd = { reorder.end() },
                                        onDragCancel = { reorder.end() },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            reorder.drag(amount.y)
                                        },
                                    )
                                }
                            } else {
                                Modifier
                            },
                    )
                }
            }
        }

        if (paginationContent != null) item(key = "pagination") { Box(itemModifier) { paginationContent() } }

        if (suggestions.isNotEmpty()) {
            item(key = "suggestions_title") { SectionTitle(slot.text.suggestions, itemModifier) }
            itemsIndexed(suggestions, key = { index, song -> "suggestion_${song.id}_$index" }, contentType = { _, _ -> "suggestion" }) { index, song ->
                val position = displayedSongs.size + index
                val onMenu = { slot.onTrackMenuClick(song, position) }
                Box(rowModifier) {
                    slot.trackContainer(song, true) {
                        TrackRow(
                            song = song,
                            number = null,
                            showArt = true,
                            albumColumn = desktop,
                            table = desktop,
                            currentSongId = slot.currentSongId,
                            isPlaying = slot.isPlaying,
                            downloaded = song.id in slot.downloadedSongIds,
                            selected = false,
                            onClick = { slot.onTrackClick(song, position) },
                            longClick = onMenu,
                            onMenu = onMenu,
                            onArtistClick = onArtistClick,
                        )
                    }
                }
            }
        }

        // Keeps the find row at the top even when the results don't fill the screen.
        item(key = "bottom_spacer") { Spacer(if (isSearchOpen) Modifier.fillParentMaxHeight() else Modifier.height(32.dp)) }
    }
}

/** "views • N songs (+ hidden) • duration", built exactly like upstream's header label. */
@Composable
private fun playlistMeta(slot: PlaylistPageSlot): String {
    val playlist = slot.playlist
    val reported = playlist?.songCount?.takeIf { it.isNotBlank() }
    val reportedCount = reported?.toIntOrNull()
    val total =
        when {
            reportedCount != null -> slot.text.songCount(reportedCount)
            reported != null -> reported
            slot.hasMoreSongs -> stringResource(SharedRes.string.partial_song_count, slot.text.songCount(slot.loadedSongCount))
            else -> slot.text.songCount(slot.loadedSongCount)
        }
    val withHidden =
        if (slot.hiddenSongCount > 0) {
            stringResource(
                SharedRes.string.song_count_with_hidden,
                total,
                pluralStringResource(SharedRes.plurals.n_hidden_song, slot.hiddenSongCount, slot.hiddenSongCount),
            )
        } else {
            total
        }
    val seconds =
        remember(slot.durationSongs, slot.hasMoreSongs, slot.loadedSongCount) {
            playlistTotalDuration(slot.durationSongs, slot.hasMoreSongs, slot.loadedSongCount)
        }
    // YouTube's own text is only a fallback until every song has loaded and the total is known.
    val duration = if (seconds != null) durationText(seconds) else playlist?.durationText?.takeIf { it.isNotBlank() }
    return listOfNotNull(playlist?.viewCount, withHidden, duration).joinToString(" • ")
}

private fun playlistTintUrl(playlist: PlaylistItem?, slot: PlaylistPageSlot): String? =
    playlist?.customThumbnail?.takeIf { it.isNotBlank() }
        ?: playlist?.thumbnail?.takeIf { it.isNotBlank() }
        ?: playlist?.artworkThumbnails?.firstOrNull()
        ?: slot.previewThumbnail
        ?: slot.artworkSongs.firstNotNullOfOrNull { it.thumbnail?.takeIf(String::isNotBlank) }

/** Upstream's cover order: built-in cover, custom cover, local mosaic, fallback icon, then the thumbnail. */
@Composable
private fun PlaylistCover(playlist: PlaylistItem?, slot: PlaylistPageSlot, modifier: Modifier) {
    val c = Shell.colors
    val drawable = slot.cover ?: playlist?.let { libraryCover(it) }
    val custom = playlist?.customThumbnail?.takeIf { it.isNotBlank() }
    val local = playlist?.isLocal == true
    val localArt = playlist?.artworkThumbnails.orEmpty()
    val thumbnail = playlist?.thumbnail?.takeIf { it.isNotBlank() }
    val fallbackIcon = slot.fallbackIcon
    val songArt =
        remember(slot.artworkSongs) {
            slot.artworkSongs.asSequence().mapNotNull { it.thumbnail?.takeIf(String::isNotBlank) }.distinct().take(4).toList()
        }
    when {
        drawable != null -> Image(painterResource(drawable), contentDescription = null, modifier = modifier.clip(CoverShape), contentScale = ContentScale.Crop)
        custom != null -> ShellArt(custom, modifier, shape = CoverShape, sizePx = 720)
        local && localArt.size >= 4 -> Mosaic(localArt, modifier)
        local && localArt.isNotEmpty() -> ShellArt(localArt.first(), modifier, shape = CoverShape, sizePx = 720)
        thumbnail == null && fallbackIcon != null ->
            Box(modifier.clip(CoverShape).background(c.surface3), contentAlignment = Alignment.Center) {
                Icon(fallbackIcon, contentDescription = null, modifier = Modifier.fillMaxSize(0.4f), tint = c.ink2)
            }
        thumbnail == null && slot.previewThumbnail == null && songArt.size >= 4 -> Mosaic(songArt, modifier)
        else -> ShellArt(thumbnail ?: slot.previewThumbnail ?: songArt.firstOrNull(), modifier, shape = CoverShape, sizePx = 720)
    }
}

@Composable
internal fun Mosaic(urls: List<String>, modifier: Modifier) {
    Column(modifier.clip(CoverShape)) {
        for (row in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 2) {
                    ShellArt(urls.getOrNull(row * 2 + col), Modifier.weight(1f).fillMaxHeight(), shape = RectangleShape, sizePx = 360)
                }
            }
        }
    }
}

@Composable
private fun AuthorLine(name: String, thumbnail: String?, onClick: (() -> Unit)?) {
    val c = Shell.colors
    Row(
        Modifier.then(if (onClick != null) Modifier.shellPressable(onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (thumbnail != null) ShellArt(thumbnail, Modifier.size(24.dp), shape = CircleShape)
        Text(name, style = Shell.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ---------------------------------------------------------------------------------------------
// Album
// ---------------------------------------------------------------------------------------------

@Composable
fun ShellAlbumPage(slot: AlbumPageSlot) = ShellTheme {
    val desktop = ShellPlatform.desktop
    val playback = LocalPlaybackController.current
    val album = slot.album
    val header = slot.header
    val paginationContent = slot.paginationContent
    val trackTrailingContent = slot.trackTrailingContent
    val onTrackLongClick = slot.onTrackLongClick
    val onSearchQueryChange = slot.onSearchQueryChange

    var sort by remember(album.id) { mutableStateOf(PlaylistSongSort.ORIGINAL) }
    var sortDirection by remember(album.id) { mutableStateOf(SortDirection.ASCENDING) }
    var isSearchOpen by remember(album.id, slot.searchAvailable) { mutableStateOf(slot.searchAvailable && slot.searchQuery.isNotBlank()) }
    var internalSearchQuery by remember(album.id) { mutableStateOf("") }
    val effectiveQuery =
        when {
            !slot.searchAvailable -> ""
            onSearchQueryChange != null -> slot.searchQuery
            else -> internalSearchQuery
        }
    val searchListState = rememberLazyListState()
    val activeListState = if (effectiveQuery.isBlank()) slot.listState else searchListState
    val updateQuery: (String) -> Unit = { q -> if (onSearchQueryChange != null) onSearchQueryChange(q) else internalSearchQuery = q }
    val toggleSearch: () -> Unit = {
        isSearchOpen = !isSearchOpen
        if (!isSearchOpen) updateQuery("")
    }
    NavigationBackHandler(enabled = isSearchOpen, onBack = toggleSearch)

    val listeningTimes = if (sort == PlaylistSongSort.LISTENING_TIME) SongMetadataState.listeningTimes else emptyMap()
    val sortedSongs =
        remember(slot.songs, slot.sortAvailable, sort, sortDirection, listeningTimes) {
            if (slot.sortAvailable) slot.songs.sortedForPlaylist(sort, sortDirection, listeningTimes = listeningTimes) else slot.songs
        }
    val displayedSongs = remember(sortedSongs, effectiveQuery) { sortedSongs.filterForPlaylistSearch(effectiveQuery) }
    val baseOrderText = playlistOrderControlsText()
    val albumOrderLabel = stringResource(SharedRes.string.album_order_original)
    val orderText =
        baseOrderText.copy(
            sortLabels = baseOrderText.sortLabels.filterKeys { it in AlbumSortOptions } + (PlaylistSongSort.ORIGINAL to albumOrderLabel),
        )

    val headerIndex = if (header != null) 1 else 0
    val findIndex = headerIndex + 1
    LaunchedEffect(effectiveQuery.isNotBlank()) {
        if (effectiveQuery.isNotBlank()) searchListState.animateScrollToItem(findIndex)
    }
    LaunchedEffect(isSearchOpen, activeListState, displayedSongs.size) {
        if (isSearchOpen) activeListState.animateScrollToItem(findIndex)
    }

    val rise = rememberRiseIn(slot.listState)
    val itemModifier = Modifier.widthIn(max = ContentMaxWidth).fillMaxWidth()
    val rowModifier = itemModifier.padding(horizontal = 8.dp)
    val kind = album.type?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.shell_col_kind_album)
    val meta = albumMeta(album, slot.songs, withType = !desktop)
    val previewThumbnail = remember(album.id) { CollectionPreviews.album(album.id)?.thumbnail }
    val coverUrl = album.thumbnail ?: previewThumbnail
    val findLabel = stringResource(Res.string.shell_col_find_album)
    val numbersAreTracks = sort == PlaylistSongSort.ORIGINAL && effectiveQuery.isBlank()
    // Read only in the header, so play/pause and song changes recompose the header alone.
    val calm = playback.rememberCalmState()

    CollectionFrame(
        modifier = slot.modifier,
        listState = activeListState,
        contentPadding = slot.contentPadding,
        tintUrl = coverUrl,
        title = album.title,
        collapseAfter = headerIndex + 1,
        onBack = if (isSearchOpen) toggleSearch else slot.onBackClick,
        selectionControls = slot.selectionControls.takeIf { slot.selectionActive },
        busy = false,
        arrowScroll = true,
    ) {
        if (header != null) item(key = "custom_header") { Box(itemModifier) { header() } }

        item(key = "header") {
            val albumNow = calm.value
            val albumActive = ShellQueue.isSourceActive(album.id, slot.songs, albumNow)
            Box(itemModifier) {
                CollectionHeader(
                    artKey = album.id,
                    kind = kind,
                    title = album.title,
                    cover = { m ->
                        ShellArt(
                            coverUrl,
                            Modifier.albumArtworkTransition(album.id, AlbumArtworkRole.Header, 8.dp).then(m),
                            shape = CoverShape,
                            sizePx = 720,
                        )
                    },
                    byline = if (album.artists.isNotEmpty()) ({ ArtistByline(album.artists, slot.onArtistClick) }) else null,
                    meta = meta,
                    description = null,
                    actions =
                        CollectionActions(
                            showPlayback = displayedSongs.isNotEmpty(),
                            playing = albumActive && albumNow.isPlaying,
                            onPlay = {
                                if (albumActive) {
                                    playback.togglePlayPause()
                                } else {
                                    ShellQueue.markSource(album.id, slot.songs)
                                    slot.onPlayAll(displayedSongs)
                                }
                            },
                            onShuffle = {
                                ShellQueue.markSource(album.id, slot.songs)
                                slot.onShuffle(displayedSongs)
                            },
                            isFavorite = slot.isFavorite,
                            onFavorite = slot.onFavorite,
                            onEdit = null,
                            onAddSong = null,
                            onMore = slot.onMoreOptions,
                            onSmartShuffle = { ShellSmartShuffle.play(playback, displayedSongs, album.title, album.id) },
                        ),
                )
            }
        }

        if (slot.sortAvailable || slot.searchAvailable) {
            item(key = "order_controls") {
                FindRow(
                    open = isSearchOpen,
                    query = effectiveQuery,
                    placeholder = findLabel,
                    onQueryChange = updateQuery,
                    onClose = toggleSearch,
                    onToggle = toggleSearch.takeIf { slot.searchAvailable },
                    above = null,
                    controls =
                        if (slot.sortAvailable) {
                            {
                                PlaylistOrderControls(
                                    text = orderText,
                                    sort = sort,
                                    sortDirection = sortDirection,
                                    reorderMode = false,
                                    canReorder = false,
                                    onSortChange = { sort = it },
                                    onSortDirectionChange = { sortDirection = it },
                                    onReorderModeChange = {},
                                )
                            }
                        } else {
                            null
                        },
                    modifier = itemModifier,
                )
            }
        }

        if (displayedSongs.isEmpty() && effectiveQuery.isNotBlank()) {
            item(key = "search_no_results") { NoResults(effectiveQuery, itemModifier) }
        } else {
            if (desktop && displayedSongs.isNotEmpty()) {
                item(key = "table_head") { TableHead(numbered = true, albumColumn = false, modifier = rowModifier) }
            }
            itemsIndexed(displayedSongs, key = { index, song -> "${song.id}_$index" }, contentType = { _, _ -> "song" }) { index, song ->
                val onMenu = { slot.onTrackMenuClick(song) }
                Box(rowModifier.riseIn(rise, "${song.id}_$index", index)) {
                    slot.trackContainer(song) {
                        TrackRow(
                            song = song,
                            number = if (numbersAreTracks) song.trackNumber ?: (index + 1) else index + 1,
                            showArt = false,
                            albumColumn = false,
                            table = desktop,
                            currentSongId = slot.currentSongId,
                            isPlaying = slot.isPlaying,
                            downloaded = song.id in slot.downloadedSongIds,
                            selected = slot.isTrackSelected(song),
                            onClick = {
                                ShellQueue.markSource(album.id, slot.songs)
                                slot.onTrackClick(song, sortedSongs, sortedSongs.indexOfPlaylistOccurrence(song))
                            },
                            longClick = if (onTrackLongClick != null) ({ onTrackLongClick(song) }) else onMenu,
                            onMenu = onMenu,
                            onArtistClick = slot.onArtistClick,
                            trailing = if (trackTrailingContent != null) ({ trackTrailingContent(song, index) }) else null,
                        )
                    }
                }
            }
        }

        if (paginationContent != null) item(key = "pagination") { Box(itemModifier) { paginationContent() } }

        val description = slot.description?.takeIf { it.isNotBlank() }
        if (description != null && effectiveQuery.isBlank()) {
            item(key = "about") {
                Column(itemModifier) {
                    SectionTitle(stringResource(Res.string.shell_col_about), Modifier)
                    Box(Modifier.padding(horizontal = 16.dp)) {
                        Description(description, collapsedLines = 3, showMore = slot.text.showMore, showLess = slot.text.showLess)
                    }
                }
            }
        }

        if (slot.otherVersions.isNotEmpty() && effectiveQuery.isBlank()) {
            item(key = "other_versions_title") { SectionTitle(slot.text.otherVersions, itemModifier) }
            item(key = "other_versions_list") {
                Box(itemModifier) {
                    OtherVersions(slot.otherVersions, album.id, slot.onAlbumArtworkClick, slot.onAlbumClick)
                }
            }
        }

        item(key = "bottom_spacer") { Spacer(if (isSearchOpen) Modifier.fillParentMaxHeight() else Modifier.height(32.dp)) }
    }
}

/** "type • genre • year • N songs • duration", like upstream's album header (type moves to the label on desktop). */
@Composable
private fun albumMeta(album: AlbumItem, songs: List<SongItem>, withType: Boolean): String {
    val count = album.songCount ?: songs.size.takeIf { it > 0 }
    val countText = if (count != null) pluralStringResource(SharedRes.plurals.n_song, count, count) else null
    val seconds = remember(album, songs) { albumTotalDuration(album, songs) }
    val duration = if (seconds != null) durationText(seconds) else null
    val genre = remember(songs) { songs.firstNotNullOfOrNull { it.genre?.takeIf(String::isNotBlank) } }
    return listOfNotNull(album.type.takeIf { withType }, genre, album.year, countText, duration).joinToString(" • ")
}

@Composable
private fun ArtistByline(artists: List<ArtistTiny>, onArtistClick: (String) -> Unit) {
    val c = Shell.colors
    val text = remember(artists, onArtistClick, c.ink) { artistLinks(artists, onArtistClick, c.ink) }
    Text(text, style = Shell.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun OtherVersions(
    albums: List<AlbumItem>,
    sourceAlbumId: String,
    onAlbumArtworkClick: ((String, String) -> Unit)?,
    onAlbumClick: (String) -> Unit,
) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    val state = rememberLazyListState()
    val size = if (desktop) 168.dp else 140.dp
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = if (desktop) 6.dp else 16.dp),
        horizontalArrangement = Arrangement.spacedBy(if (desktop) 2.dp else 14.dp),
        modifier = Modifier.fillMaxWidth().carouselScroll(state),
    ) {
        items(albums, key = { it.id }, contentType = { "album" }) { other ->
            val token = "album:$sourceAlbumId:${other.id}"
            val source =
                remember(other, token, onAlbumArtworkClick, onAlbumClick) {
                    AlbumArtworkSource(other, token) {
                        if (onAlbumArtworkClick != null) onAlbumArtworkClick(other.id, token) else onAlbumClick(other.id)
                    }
                }
            val (hover, hovered) = rememberHover()
            Column(
                Modifier.hoverable(hover).cardHover(hovered).width(size).shellPressable({ source.select() }, label = other.title),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ShellArt(other.thumbnail, source.modifier(8.dp).size(size), shape = CoverShape)
                Text(other.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                other.year?.let { Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Shared frame, header and rows
// ---------------------------------------------------------------------------------------------

/**
 * The page: ground, a header glow tinted from the cover that scrolls away with the header, the list,
 * a back bar that gains the title once the header has scrolled off, and upstream's selection bar.
 */
@Composable
internal fun CollectionFrame(
    modifier: Modifier,
    listState: LazyListState,
    contentPadding: PaddingValues,
    tintUrl: String?,
    title: String,
    collapseAfter: Int,
    onBack: (() -> Unit)?,
    selectionControls: (@Composable () -> Unit)?,
    busy: Boolean,
    arrowScroll: Boolean,
    content: LazyListScope.() -> Unit,
) {
    val c = Shell.colors
    val ground = LocalShellGround.current ?: c.ground
    val tint = artworkTint(tintUrl, 0.35f, ground)
    val layoutDirection = LocalLayoutDirection.current
    val showBar = onBack != null && selectionControls == null
    val padding =
        PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            top = contentPadding.calculateTopPadding() + (if (showBar) 64.dp else 0.dp),
            end = contentPadding.calculateEndPadding(layoutDirection),
            bottom = contentPadding.calculateBottomPadding(),
        )
    Column(modifier.fillMaxSize().background(ground)) {
        selectionControls?.invoke()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            // Scroll is read in the layer, so scrolling never recomposes the page.
            Box(
                Modifier.fillMaxWidth().height(padding.calculateTopPadding() + 460.dp)
                    .graphicsLayer {
                        translationY = if (listState.firstVisibleItemIndex > 0) -size.height else -listState.firstVisibleItemScrollOffset.toFloat()
                    }
                    .drawBehind { drawRect(Brush.verticalGradient(listOf(tint, ground))) },
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().desktopArrowScroll(listState, enabled = arrowScroll),
                contentPadding = padding,
                horizontalAlignment = Alignment.CenterHorizontally,
                content = content,
            )
            LazyListScrollbar(state = listState, modifier = Modifier.align(Alignment.CenterEnd).padding(padding))
            if (showBar && onBack != null) TopBar(onBack, title, listState, collapseAfter, ground)
            if (busy) {
                CircularProgressIndicator(
                    Modifier.align(Alignment.TopCenter).padding(top = padding.calculateTopPadding() + 8.dp).size(24.dp),
                    color = c.accent,
                    strokeWidth = 2.5.dp,
                )
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, title: String, listState: LazyListState, collapseAfter: Int, ground: Color) {
    val c = Shell.colors
    val collapsed by remember(listState, collapseAfter) { derivedStateOf { listState.firstVisibleItemIndex >= collapseAfter } }
    val bg by animateColorAsState(if (collapsed) ground else ground.copy(alpha = 0f), ShellMotion.emphasized())
    Row(Modifier.fillMaxWidth().height(64.dp).background(bg).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(CollectionGlyphs.ChevronLeft, stringResource(Res.string.shell_col_back), onBack, c.ink, iconSize = 24.dp)
        AnimatedVisibility(collapsed, enter = fadeIn(ShellMotion.emphasized()), exit = fadeOut(ShellMotion.exit())) {
            Text(
                title,
                style = Shell.type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, end = 16.dp),
            )
        }
    }
}

internal class CollectionActions(
    val showPlayback: Boolean,
    val onPlay: () -> Unit,
    /** Null hides Shuffle (a Turtle mix plays in the order it shows). */
    val onShuffle: (() -> Unit)?,
    val isFavorite: Boolean,
    val onFavorite: (() -> Unit)?,
    val onEdit: (() -> Unit)?,
    val onAddSong: (() -> Unit)?,
    val onMore: (() -> Unit)?,
    val onSmartShuffle: (() -> Unit)? = null,
    /** True while this collection is what's playing: the play button shows pause and [onPlay] pauses. */
    val playing: Boolean = false,
    /** The save button's label in place of Save to / Remove from Your Library (a mix saves to a playlist). */
    val favoriteLabel: String? = null,
)

/** Phone: centred cover over a left-aligned title. Desktop: Spotify's horizontal header. */
@Composable
internal fun CollectionHeader(
    artKey: Any?,
    kind: String,
    title: String,
    cover: @Composable (Modifier) -> Unit,
    byline: (@Composable () -> Unit)?,
    meta: String,
    description: (@Composable () -> Unit)?,
    actions: CollectionActions,
) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    val scale = remember(artKey) { Animatable(0.96f) }
    LaunchedEffect(scale) { scale.animateTo(1f, ShellMotion.emphasized()) }
    val artModifier = Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }.shadow(24.dp, CoverShape)
    if (desktop) {
        Column(Modifier.fillMaxWidth().onSecondaryClick(actions.onMore).padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                cover(artModifier.size(232.dp))
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IndexLabel(kind, color = c.ink)
                    Text(title, style = titleStyle(title, desktop = true), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    description?.invoke()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        byline?.invoke()
                        if (byline != null && meta.isNotBlank()) Text(" • ", style = Shell.type.bodySmall, color = c.ink2)
                        Text(meta, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            ActionRow(actions, desktop = true)
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                cover(artModifier.size(minOf(maxWidth * 0.62f, 320.dp)))
            }
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = titleStyle(title, desktop = false), color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                description?.invoke()
                byline?.invoke()
                if (meta.isNotBlank()) Text(meta, style = Shell.type.bodySmall, color = c.ink2)
            }
            Spacer(Modifier.height(4.dp))
            ActionRow(actions, desktop = false)
        }
    }
}

/** Space Grotesk display, scaled down as titles get longer. */
@Composable
private fun titleStyle(title: String, desktop: Boolean): TextStyle {
    val n = title.length
    val size =
        if (desktop) {
            when {
                n <= 18 -> 72.sp
                n <= 32 -> 56.sp
                else -> 40.sp
            }
        } else {
            when {
                n <= 20 -> 34.sp
                n <= 40 -> 28.sp
                else -> 22.sp
            }
        }
    return Shell.type.display.copy(fontSize = size, lineHeight = size * 1.05f)
}

/** Secondary actions on the left; shuffle and the lime play button on the right (play leads on desktop). */
@Composable
private fun ActionRow(a: CollectionActions, desktop: Boolean) {
    val c = Shell.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        if (desktop && a.showPlayback) {
            PlayButton(a.playing, a.onPlay)
            Spacer(Modifier.width(12.dp))
            a.onShuffle?.let { GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_col_shuffle), it, c.ink2, iconSize = 26.dp) }
            a.onSmartShuffle?.let { SmartShuffleButton(it) }
        }
        val onFavorite = a.onFavorite
        if (onFavorite != null) FavoriteButton(a.isFavorite, onFavorite, a.favoriteLabel)
        val onEdit = a.onEdit
        if (onEdit != null) GlyphButton(CollectionGlyphs.Edit, stringResource(Res.string.shell_col_edit), onEdit, c.ink2)
        val onAddSong = a.onAddSong
        if (onAddSong != null) GlyphButton(CollectionGlyphs.AddSong, stringResource(Res.string.shell_col_add_song), onAddSong, c.ink2)
        val onMore = a.onMore
        if (onMore != null) GlyphButton(Glyphs.More, stringResource(Res.string.shell_col_more), onMore, c.ink2)
        Spacer(Modifier.weight(1f))
        if (!desktop && a.showPlayback) {
            a.onShuffle?.let { GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_col_shuffle), it, c.ink2, iconSize = 26.dp) }
            a.onSmartShuffle?.let { SmartShuffleButton(it) }
            Spacer(Modifier.width(8.dp))
            PlayButton(a.playing, a.onPlay)
        }
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val c = Shell.colors
    PlayPauseButton(
        playing, onClick, 56.dp, c.accent, c.onAccent,
        playLabel = stringResource(Res.string.shell_col_play), pauseLabel = stringResource(Res.string.shell_col_pause),
    )
}

/** Spotify's save control: an outlined plus, or a lime check once it's yours. */
@Composable
private fun FavoriteButton(saved: Boolean, onClick: () -> Unit, labelOverride: String? = null) {
    val c = Shell.colors
    val label = labelOverride ?: stringResource(if (saved) Res.string.shell_col_saved else Res.string.shell_col_save)
    Box(Modifier.size(48.dp).shellPressable(onClick, label = label, focusShape = CircleShape), contentAlignment = Alignment.Center) {
        Crossfade(saved, animationSpec = tween(ShellMotion.EMPHASIZED_MS, easing = ShellMotion.Emphasized)) { isSaved ->
            if (isSaved) {
                Box(Modifier.size(26.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) { CheckGlyph(c.onAccent, 15.dp) }
            } else {
                Glyph(Glyphs.Add, c.ink2, 28.dp)
            }
        }
    }
}

/** Upstream's order controls with a search toggle, or the find field while searching. */
@Composable
private fun FindRow(
    open: Boolean,
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    onToggle: (() -> Unit)?,
    above: (@Composable () -> Unit)?,
    controls: (@Composable () -> Unit)?,
    modifier: Modifier,
) {
    val c = Shell.colors
    Box(modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp), contentAlignment = Alignment.CenterStart) {
        if (open) {
            FindField(query, onQueryChange, onClose, placeholder)
        } else {
            Column {
                above?.invoke()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { controls?.invoke() }
                    if (onToggle != null) GlyphButton(Glyphs.Search, stringResource(Res.string.shell_col_find), onToggle, c.ink2, iconSize = 20.dp)
                }
            }
        }
    }
}

@Composable
private fun FindField(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit, placeholder: String) {
    val c = Shell.colors
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { focus.requestFocus() }
    val textStyle = Shell.type.body.copy(color = c.ink)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.Search, c.ink2, 18.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, style = textStyle, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = textStyle,
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        }
        GlyphButton(Glyphs.Close, stringResource(Res.string.shell_col_close_search), onClose, c.ink2, size = 44.dp, iconSize = 14.dp)
    }
}

/** Desktop column headings over a hairline: # / Title / Album / duration. */
@Composable
internal fun TableHead(numbered: Boolean, albumColumn: Boolean, modifier: Modifier) {
    val c = Shell.colors
    val style = Shell.type.label.copy(fontSize = 12.sp)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().height(36.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (numbered) {
                Text(stringResource(Res.string.shell_col_head_number), style = style, color = c.ink2, textAlign = TextAlign.Center, modifier = Modifier.width(32.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(stringResource(Res.string.shell_col_head_title), style = style, color = c.ink2, modifier = Modifier.weight(1f))
            if (albumColumn) {
                Spacer(Modifier.width(16.dp))
                Text(stringResource(Res.string.shell_col_head_album), style = style, color = c.ink2, modifier = Modifier.weight(0.6f))
            }
            Spacer(Modifier.width(16.dp))
            Box(Modifier.width(56.dp), contentAlignment = Alignment.CenterEnd) {
                Glyph(CollectionGlyphs.Clock, c.ink2, 16.dp, contentDescription = stringResource(Res.string.shell_col_head_duration))
            }
            Spacer(Modifier.width(48.dp))
        }
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(1.dp).background(c.hairline))
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One track. Phones: art (or the track number on albums), title, artists, more. Desktop: a table row
 * whose number turns into a play glyph on hover, with album and duration columns.
 */
@Composable
internal fun TrackRow(
    song: SongItem,
    number: Int?,
    showArt: Boolean,
    albumColumn: Boolean,
    table: Boolean,
    currentSongId: String?,
    isPlaying: Boolean,
    downloaded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    longClick: (() -> Unit)?,
    onMenu: () -> Unit,
    onArtistClick: ((String) -> Unit)?,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    dragging: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val c = Shell.colors
    val shown = SongMetadataState.apply(song)
    val current = song.id == currentSongId
    val playing = current && isPlaying
    val enabled = !song.isUnavailable
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(
        when {
            dragging -> c.surface3
            selected || hovered -> c.surface2
            else -> Color.Transparent
        },
        ShellMotion.emphasized(),
    )
    val click = { if (enabled) onClick() else UnavailableSongTaps.tapped() }
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RowShape).background(bg).hoverable(hover)
            // Long press starts selection on some pages, so it is not announced as the menu.
            .shellPressable(click, onLongClick = longClick, label = null, onLongClickLabel = null)
            .onSecondaryClick(onMenu)
            .padding(start = if (leading != null) 0.dp else 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        if (number != null) {
            Box(Modifier.width(32.dp).alpha(if (enabled) 1f else 0.38f), contentAlignment = Alignment.Center) {
                when {
                    table && hovered -> Glyph(Glyphs.Play, c.ink, 16.dp)
                    playing -> Equalizer(height = 12.dp)
                    else -> Text("$number", style = Shell.type.index.copy(fontSize = 13.sp), color = if (current) c.accent else c.ink2, maxLines = 1)
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Row(Modifier.weight(1f).alpha(if (enabled) 1f else 0.38f), verticalAlignment = Alignment.CenterVertically) {
            if (showArt) {
                val artSize = if (table) 40.dp else 48.dp
                Box(contentAlignment = Alignment.Center) {
                    ShellArt(song.displayThumbnailUrl, Modifier.size(artSize), shape = RoundedCornerShape(4.dp))
                    if (number == null && (playing || (hovered && table))) {
                        Box(Modifier.size(artSize).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                            if (hovered) Glyph(Glyphs.Play, Color.White, 18.dp) else Equalizer(height = 14.dp)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(shown.title, style = Shell.type.body, color = if (current) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (shown.isExplicit) {
                        ExplicitBadge()
                        Spacer(Modifier.width(6.dp))
                    }
                    if (downloaded) {
                        Glyph(Glyphs.Download, c.accent, 14.dp, contentDescription = stringResource(Res.string.shell_col_downloaded))
                        Spacer(Modifier.width(4.dp))
                    }
                    // Artist names are links on desktop, like upstream's clickable metadata.
                    val linkTarget = if (table) onArtistClick else null
                    val artists = remember(shown.artists, linkTarget, c.ink) { artistLinks(shown.artists, linkTarget, c.ink) }
                    Text(artists, style = Shell.type.bodySmall, color = if (hovered) c.ink else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (albumColumn) {
            Spacer(Modifier.width(16.dp))
            Text(shown.album?.name.orEmpty(), style = Shell.type.bodySmall, color = if (hovered) c.ink else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.6f))
        }
        if (table) {
            Spacer(Modifier.width(16.dp))
            val seconds = shown.duration?.takeIf { it > 0 }
            Text(
                if (seconds != null) formatTime(seconds * 1000) else "",
                style = Shell.type.bodySmall,
                color = c.ink2,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(56.dp),
            )
        }
        if (trailing != null) {
            // Upstream's trailing content (play counts, menus) sits apart from the duration, in Shell's quiet ink.
            Spacer(Modifier.width(16.dp))
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides c.ink2,
                androidx.compose.material3.LocalTextStyle provides Shell.type.bodySmall,
            ) { trailing() }
        } else {
            GlyphButton(Glyphs.More, stringResource(Res.string.shell_col_more), onMenu, c.ink2, iconSize = 20.dp)
        }
    }
}

@Composable
private fun ExplicitBadge() {
    val c = Shell.colors
    val label = stringResource(Res.string.shell_col_explicit)
    Box(
        Modifier.size(16.dp).clip(RoundedCornerShape(3.dp)).background(c.ink2).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(Res.string.shell_col_explicit_badge),
            style = Shell.type.index.copy(fontSize = 10.sp, lineHeight = 10.sp, fontWeight = FontWeight.Bold),
            color = c.ground,
        )
    }
}

private fun artistLinks(artists: List<ArtistTiny>, onArtistClick: ((String) -> Unit)?, hoverColor: Color): AnnotatedString =
    buildAnnotatedString {
        val styles = TextLinkStyles(hoveredStyle = SpanStyle(color = hoverColor, textDecoration = TextDecoration.Underline))
        artists.forEachIndexed { i, artist ->
            if (i > 0) append(", ")
            val id = artist.id
            if (id != null && onArtistClick != null) {
                withLink(LinkAnnotation.Clickable(id, styles) { onArtistClick(id) }) { append(artist.name) }
            } else {
                append(artist.name)
            }
        }
    }

@Composable
private fun SectionTitle(text: String, modifier: Modifier) {
    Text(text, style = Shell.type.sectionTitle, color = Shell.colors.ink, modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 10.dp))
}

/** Text that expands on tap; [showMore]/[showLess] add an explicit toggle below it. */
@Composable
private fun Description(text: String, collapsedLines: Int, showMore: String? = null, showLess: String? = null) {
    val c = Shell.colors
    var expanded by remember(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    val toggle = { expanded = !expanded }
    Column(Modifier.animateContentSize(ShellMotion.emphasized())) {
        Text(
            text,
            style = Shell.type.bodySmall,
            color = c.ink2,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            modifier = if (overflows || expanded) Modifier.shellPressable(toggle) else Modifier,
        )
        if (showMore != null && showLess != null && (overflows || expanded)) {
            Text(
                if (expanded) showLess else showMore,
                style = Shell.type.label.copy(fontWeight = FontWeight.Bold),
                color = c.ink,
                modifier = Modifier.padding(top = 6.dp).shellPressable(toggle),
            )
        }
    }
}

@Composable
private fun NoResults(query: String, modifier: Modifier) {
    Box(modifier.padding(horizontal = 16.dp, vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(stringResource(Res.string.shell_col_no_results, query), style = Shell.type.body, color = Shell.colors.ink2, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SkeletonHeader() {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    val desktop = ShellPlatform.desktop
    val label = stringResource(Res.string.shell_col_loading)
    Column(
        Modifier.fillMaxWidth().padding(16.dp).semantics { contentDescription = label }.graphicsLayer { alpha = pulse.value },
        horizontalAlignment = if (desktop) Alignment.Start else Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(if (desktop) 232.dp else 240.dp).clip(CoverShape).background(c.surface2))
        Spacer(Modifier.height(20.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.fillMaxWidth(0.6f).height(28.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2))
            Box(Modifier.fillMaxWidth(0.4f).height(14.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
        }
    }
}

@Composable
private fun SkeletonRows(modifier: Modifier) {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    Column(modifier.graphicsLayer { alpha = pulse.value }.padding(top = 8.dp)) {
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

/** "1 hour, 12 minutes", from upstream's shared strings (its formatter is internal to :shared). */
@Composable
internal fun durationText(seconds: Long): String {
    val totalMinutes = seconds / 60
    val days = (totalMinutes / (24 * 60)).toInt()
    val hours = (totalMinutes / 60 % 24).toInt()
    val minutes = (totalMinutes % 60).toInt()
    val units =
        listOfNotNull(
            if (days > 0) pluralStringResource(SharedRes.plurals.days, days, days) else null,
            if (hours > 0) pluralStringResource(SharedRes.plurals.hours, hours, hours) else null,
            if (minutes > 0) pluralStringResource(SharedRes.plurals.minutes, minutes, minutes) else null,
        )
    return when (units.size) {
        0 -> stringResource(SharedRes.string.less_than_one_minute)
        1 -> units[0]
        2 -> stringResource(SharedRes.string.duration_two_units, units[0], units[1])
        else -> stringResource(SharedRes.string.duration_three_units, units[0], units[1], units[2])
    }
}

// ---------------------------------------------------------------------------------------------
// Reorder
// ---------------------------------------------------------------------------------------------

/** Drag handle (drag to move) that also opens a move menu on tap, for keyboard and TV users. */
@Composable
internal fun ReorderHandle(key: Any, reorder: CollectionReorder, index: Int, count: Int, onMove: (Int, Int) -> Unit) {
    val c = Shell.colors
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(40.dp, 48.dp)
                .pointerInput(key, reorder) {
                    detectDragGestures(
                        onDragStart = { _: Offset -> reorder.start(key) },
                        onDragEnd = { reorder.end() },
                        onDragCancel = { reorder.end() },
                        onDrag = { change, amount ->
                            change.consume()
                            reorder.drag(amount.y)
                        },
                    )
                }
                .shellPressable({ menu = true }, label = stringResource(Res.string.shell_col_reorder)),
            contentAlignment = Alignment.Center,
        ) { Glyph(CollectionGlyphs.Drag, c.ink2, 20.dp) }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (index > 0) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_col_move_top)) }, onClick = { menu = false; onMove(index, 0) })
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_col_move_up)) }, onClick = { menu = false; onMove(index, index - 1) })
            }
            if (index < count - 1) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_col_move_down)) }, onClick = { menu = false; onMove(index, index + 1) })
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_col_move_bottom)) }, onClick = { menu = false; onMove(index, count - 1) })
            }
        }
    }
}

/**
 * Drag-to-reorder over a LazyColumn, calling upstream's callbacks the way its reorderable list does:
 * started once, [onMove] for every step past a neighbour, finished once on release. The dragged row
 * is drawn at the pointer ([offsetOf]) whatever the list's layout, so the parent can update async.
 */
internal class CollectionReorder(private val listState: LazyListState, private val scope: CoroutineScope) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    private var top by mutableFloatStateOf(0f)
    private var keys: List<String> = emptyList()
    private var indexCache: Map<Any, Int>? = null
    private var onMove: (Int, Int) -> Unit = { _, _ -> }
    private var onStarted: () -> Unit = {}
    private var onFinished: () -> Unit = {}
    private var edgePx = 1f
    private var pendingKeys: List<String>? = null
    private var pendingAt: TimeMark = TimeSource.Monotonic.markNow()
    private var autoScroll: Job? = null

    fun update(keys: List<String>, onMove: (Int, Int) -> Unit, onStarted: () -> Unit, onFinished: () -> Unit, edgePx: Float) {
        if (keys !== this.keys) {
            this.keys = keys
            indexCache = null
        }
        this.onMove = onMove
        this.onStarted = onStarted
        this.onFinished = onFinished
        this.edgePx = edgePx.coerceAtLeast(1f)
    }

    fun start(key: Any) {
        if (draggingKey != null) return
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        top = info.offset.toFloat()
        draggingKey = key
        pendingKeys = null
        onStarted()
        autoScroll =
            scope.launch {
                while (isActive) {
                    withFrameNanos { }
                    val dragged = draggingKey ?: break
                    val layout = listState.layoutInfo
                    val size = layout.visibleItemsInfo.firstOrNull { it.key == dragged }?.size ?: continue
                    val start = layout.viewportStartOffset + edgePx
                    val end = layout.viewportEndOffset - edgePx
                    val speed = edgePx / 4f
                    val delta =
                        when {
                            top < start -> -((start - top) / edgePx).coerceAtMost(1f) * speed
                            top + size > end -> ((top + size - end) / edgePx).coerceAtMost(1f) * speed
                            else -> 0f
                        }
                    if (delta != 0f) {
                        listState.scrollBy(delta)
                        swapIfNeeded()
                    }
                }
            }
    }

    fun drag(dy: Float) {
        if (draggingKey == null) return
        top += dy
        swapIfNeeded()
    }

    fun end() {
        if (draggingKey == null) return
        autoScroll?.cancel()
        autoScroll = null
        draggingKey = null
        pendingKeys = null
        onFinished()
    }

    /** How far the dragged row is drawn from its laid-out position (0 for every other row). */
    fun offsetOf(key: Any): Float {
        if (key != draggingKey) return 0f
        val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return top - info.offset
    }

    private fun indexes(): Map<Any, Int> =
        indexCache ?: keys.withIndex().associateTo(HashMap<Any, Int>()) { (i, k) -> k to i }.also { indexCache = it }

    private fun swapIfNeeded() {
        val key = draggingKey ?: return
        // One step at a time: wait for the parent to apply the last move (or give up after a moment).
        if (pendingKeys === keys && pendingAt.elapsedNow() < 400.milliseconds) return
        val items = listState.layoutInfo.visibleItemsInfo
        val dragged = items.firstOrNull { it.key == key } ?: return
        val index = indexes()
        val center = top + dragged.size / 2f
        val target =
            items.firstOrNull { it.key != key && index.containsKey(it.key) && center >= it.offset && center < it.offset + it.size } ?: return
        val from = index[key] ?: return
        val to = index[target.key] ?: return
        if (from == to) return
        pendingKeys = keys
        pendingAt = TimeSource.Monotonic.markNow()
        onMove(from, to)
    }
}
