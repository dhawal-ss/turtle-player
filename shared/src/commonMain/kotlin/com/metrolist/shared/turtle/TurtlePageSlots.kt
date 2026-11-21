package com.metrolist.shared.turtle

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.HiddenLibraryItem
import com.metrolist.shared.settings.HiddenLibraryItemKey
import com.metrolist.shared.ui.screens.AlbumContentText
import com.metrolist.shared.ui.screens.LibraryAutoPlaylist
import com.metrolist.shared.ui.screens.LibraryContentText
import com.metrolist.shared.ui.screens.LibraryFilter
import com.metrolist.shared.ui.screens.PlaylistContentText
import com.metrolist.shared.viewmodels.LibraryPresentation
import org.jetbrains.compose.resources.DrawableResource

/*
 * Arguments of upstream's shared page composables, passed whole to Shell's pages (one hook line each,
 * turtle/HOOKS.md). Field names and meanings match upstream's parameters exactly, so a Shell page can
 * keep every feature: upstream's own composable pieces (header, selection controls, pagination,
 * track containers for swipe and selection) are passed through and drawn inside Shell's layout.
 */

/** `PlaylistContent` (online, local and auto playlists on phone and desktop). */
class PlaylistPageSlot(
    val playlist: PlaylistItem?,
    val songs: List<SongItem>,
    val artworkSongs: List<SongItem>,
    val durationSongs: List<SongItem>,
    val hasMoreSongs: Boolean,
    val hiddenSongCount: Int,
    val loadedSongCount: Int,
    val isLoading: Boolean,
    val previewThumbnail: String?,
    val loadingHeader: (@Composable () -> Unit)?,
    val paginationContent: (@Composable () -> Unit)?,
    val text: PlaylistContentText,
    val onPlayAll: () -> Unit,
    val onShuffle: () -> Unit,
    val onTrackClick: (SongItem, Int) -> Unit,
    val onTrackMenuClick: (SongItem, Int) -> Unit,
    val onTrackLongClick: ((SongItem) -> Unit)?,
    val onBackClick: (() -> Unit)?,
    val fallbackIcon: ImageVector?,
    val cover: DrawableResource?,
    val isFavorite: Boolean,
    val onFavorite: (() -> Unit)?,
    val modifier: Modifier,
    val listState: LazyListState,
    val preparedSongKeys: List<String>?,
    val contentPadding: PaddingValues,
    val currentSongId: String?,
    val isPlaying: Boolean,
    val suggestions: List<SongItem>,
    val downloadedSongIds: Set<String>,
    val onArtistClick: ((String) -> Unit)?,
    val header: (@Composable () -> Unit)?,
    val orderControls: (@Composable () -> Unit)?,
    val aboveOrderControls: (@Composable () -> Unit)?,
    val selectionActive: Boolean,
    val selectionControls: (@Composable () -> Unit)?,
    val emptyContent: (@Composable () -> Unit)?,
    val reorderMode: Boolean,
    val searchEnabled: Boolean,
    val trackContainer: @Composable (SongItem, Boolean, @Composable () -> Unit) -> Unit,
    val isTrackSelected: (SongItem) -> Boolean,
    val trackTrailingContent: (@Composable (SongItem, Int) -> Unit)?,
    val onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    val onReorderDragStarted: () -> Unit,
    val onReorderFinished: () -> Unit,
    val searchQuery: String,
    val onSearchQueryChange: ((String) -> Unit)?,
    val searchOpen: Boolean?,
    val onSearchOpenChange: (Boolean) -> Unit,
    val onMoreOptions: (() -> Unit)?,
    val onEdit: (() -> Unit)?,
    val onAddSong: (() -> Unit)?,
)

/** `AlbumContent` (phone and desktop). */
class AlbumPageSlot(
    val album: AlbumItem,
    val songs: List<SongItem>,
    val text: AlbumContentText,
    val paginationContent: (@Composable () -> Unit)?,
    val onPlayAll: (List<SongItem>) -> Unit,
    val onShuffle: (List<SongItem>) -> Unit,
    val onMoreOptions: (() -> Unit)?,
    val isFavorite: Boolean,
    val onFavorite: (() -> Unit)?,
    val onArtistClick: (String) -> Unit,
    val onTrackClick: (SongItem, List<SongItem>, Int) -> Unit,
    val onTrackMenuClick: (SongItem) -> Unit,
    val onBackClick: (() -> Unit)?,
    val modifier: Modifier,
    val listState: LazyListState,
    val contentPadding: PaddingValues,
    val selectionBottomPadding: Dp,
    val currentSongId: String?,
    val isPlaying: Boolean,
    val downloadedSongIds: Set<String>,
    val description: String?,
    val otherVersions: List<AlbumItem>,
    val onAlbumClick: (String) -> Unit,
    val onAlbumArtworkClick: ((String, String) -> Unit)?,
    val header: (@Composable () -> Unit)?,
    val trackContainer: @Composable (SongItem, @Composable () -> Unit) -> Unit,
    val searchQuery: String,
    val onSearchQueryChange: ((String) -> Unit)?,
    val searchAvailable: Boolean,
    val sortAvailable: Boolean,
    val onTrackLongClick: ((SongItem) -> Unit)?,
    val selectionActive: Boolean,
    val selectionControls: (@Composable () -> Unit)?,
    val trackTrailingContent: (@Composable (SongItem, Int) -> Unit)?,
    val isTrackSelected: (SongItem) -> Boolean,
)

/** `ArtistScreen` (phone and desktop). */
class ArtistPageSlot(
    val artist: ArtistItem?,
    val artistId: String,
    val initialArtistName: String?,
    val currentSongId: String?,
    val isPlaying: Boolean,
    val onItemsVisible: ((List<YTItem>) -> Unit)?,
    val sections: Map<String, List<YTItem>>,
    val sectionBrowseIds: Map<String, Pair<String?, String?>>,
    val loadableSections: Set<String>,
    val sectionContinuation: (@Composable (String) -> Unit)?,
    val isLoading: Boolean,
    val errorMessage: String?,
    val onRefresh: () -> Unit,
    val onItemClick: (YTItem) -> Unit,
    val onVideoItemClick: (YTItem) -> Unit,
    val onAlbumArtworkClick: ((AlbumItem, String) -> Unit)?,
    val onItemLongClick: (YTItem) -> Unit,
    val songContainer: @Composable (SongItem, @Composable () -> Unit) -> Unit,
    val onSectionClick: ((String, String?, String) -> Unit)?,
    val onVideoSectionClick: ((String, String?, String) -> Unit)?,
    val onPlayItem: ((YTItem) -> Unit)?,
    val isSongLiked: ((String) -> Boolean)?,
    val onLikeSong: ((SongItem) -> Unit)?,
    val onBackClick: (() -> Unit)?,
    val onMenuClick: (() -> Unit)?,
    val onPlayAllClick: () -> Unit,
    val onShuffleClick: () -> Unit,
    val onMixClick: () -> Unit,
    val onRefreshClick: (() -> Unit)?,
    val modifier: Modifier,
    val listState: LazyListState?,
    val contentPadding: PaddingValues,
    val headerWindowInsets: WindowInsets,
    val isSubscribed: Boolean,
    val subscribeSuccessCount: Int,
    val showSubscribe: Boolean,
    val onSubscribeClick: () -> Unit,
    val onUnblockClick: () -> Unit,
    val description: String?,
    val aboutImageUrl: String?,
    val onAboutClick: (() -> Unit)?,
    val headerVideoLayer: (@Composable (Modifier) -> Unit)?,
    val onAppBarVisibleChange: (Boolean) -> Unit,
    val overlay: @Composable () -> Unit,
)

/** `LibraryContent` (phone and desktop Library page). */
class LibraryPageSlot(
    val text: LibraryContentText,
    val items: List<YTItem>,
    val autoPlaylists: List<LibraryAutoPlaylist>,
    val hiddenItemKeys: Set<HiddenLibraryItemKey>,
    val isLoading: Boolean,
    val error: Boolean,
    val onRetry: (() -> Unit)?,
    val isGridView: Boolean,
    val selectedFilter: LibraryFilter?,
    val manualOrder: String,
    val topPadding: Dp,
    val compactGrid: Boolean,
    val useGridItemSize: Boolean,
    val onFilterChange: (LibraryFilter?) -> Unit,
    val onGridViewChange: (Boolean) -> Unit,
    val onManualOrderChange: (String) -> Unit,
    val onItemClick: (YTItem) -> Unit,
    val onPlaySongs: (List<SongItem>, Int) -> Unit,
    val currentSongId: String?,
    val isPlaying: Boolean,
    val onAutoPlaylistClick: (String) -> Unit,
    val itemContainer: @Composable (HiddenLibraryItem, YTItem?, Boolean, () -> Unit, @Composable () -> Unit) -> Unit,
    val bottomPadding: Dp,
    val extraBottomPadding: Dp,
    val scrollState: LazyGridState,
    val modifier: Modifier,
    val searchQuery: String,
    val isSearchOpen: Boolean,
    val onSearchQueryChange: ((String) -> Unit)?,
    val onSearchToggle: () -> Unit,
    val contentMatchedSongs: List<SongItem>,
    val contentSearchHasMore: Boolean,
    val onLoadMoreContentMatches: () -> Unit,
    val onSearchDescendingChange: (Boolean) -> Unit,
    val onPlayAlbum: ((AlbumItem) -> Unit)?,
    val presentation: LibraryPresentation,
)
