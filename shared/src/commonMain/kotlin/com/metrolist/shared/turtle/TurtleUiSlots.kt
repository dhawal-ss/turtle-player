package com.metrolist.shared.turtle

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.screens.HomeContentText
import com.metrolist.shared.ui.screens.HomeShelfCache
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import com.metrolist.shared.viewmodels.PlayerViewModel
import com.metrolist.shared.viewmodels.SearchViewModel.SearchUiState

/**
 * Turtle Player's UI override points (turtle/HOOKS.md). This file is ours: a new file in the
 * upstream tree, so it never conflicts with upstream merges. Each upstream screen calls into it with
 * a single line; when a slot is null, upstream's own UI renders unchanged.
 */
object TurtleUiSlots {
    var home: (@Composable (HomeSlot) -> Unit)? = null
    var search: (@Composable (SearchSlot) -> Unit)? = null
    var miniPlayer: (@Composable (MiniPlayerSlot) -> Unit)? = null
    var player: (@Composable (PlayerSlot) -> Unit)? = null
    var navigation: (@Composable (NavigationSlot) -> Unit)? = null
    var desktopPlayerBar: (@Composable (DesktopPlayerBarSlot) -> Unit)? = null

    /**
     * Replaces upstream's generated Material color scheme everywhere (Shell's palette). Called with
     * `darkTheme`; returning null keeps upstream's scheme (Shell is dark only, so light theme stays upstream's).
     */
    var colorScheme: ((Boolean) -> ColorScheme?)? = null

    /** Desktop data Shell's sidebar needs; the desktop app registers it at startup. Non-null means desktop. */
    var desktopLibrary: DesktopLibrarySlot? = null

    /** While true, upstream's full player shows instead of ours (Shell hands over for lyrics, queue, more). */
    var showUpstreamPlayer by mutableStateOf(false)
}

/** Everything upstream's HomeContent receives that our Home needs; callbacks keep upstream's wiring. */
class HomeSlot(
    val uiState: HomeUiState,
    val text: HomeContentText,
    val onItemClick: (YTItem) -> Unit,
    val onItemLongClick: ((YTItem) -> Unit)?,
    val onPlaySongs: ((List<SongItem>) -> Unit)?,
    val onShuffleSongs: ((List<SongItem>) -> Unit)?,
    val onArtistClick: ((String) -> Unit)?,
    val onAlbumClick: ((String) -> Unit)?,
    val onPlayAlbum: ((AlbumItem) -> Unit)?,
    val onLoadMore: (() -> Unit)?,
    val currentSongId: State<String?>,
    val isPlaying: State<Boolean>,
    val modifier: Modifier,
    val listState: LazyListState,
    val contentPadding: PaddingValues,
    val shelfCache: HomeShelfCache?,
)

/** Everything upstream's SearchScreen receives that our Search needs. */
class SearchSlot(
    val uiState: SearchUiState,
    val onQueryChange: (String) -> Unit,
    val onSearch: (String) -> Unit,
    val onBack: () -> Unit,
    val onItemClick: (YTItem) -> Unit,
    val onItemLongClick: (YTItem) -> Unit,
    val onHistoryItemClick: (String) -> Unit,
    val onRemoveHistoryItem: (String) -> Unit,
    val onFilterSelected: (SearchFilter?) -> Unit,
    val onLoadMore: () -> Unit,
    val onArtistClick: ((String) -> Unit)?,
    val onAlbumClick: ((String) -> Unit)?,
    val currentSongId: String?,
    val isPlaying: Boolean,
    val showSearchBar: Boolean,
    val focusRequester: FocusRequester,
    val modifier: Modifier,
    val contentPadding: PaddingValues,
)

/** Android's collapsed player. */
class MiniPlayerSlot(
    val modifier: Modifier,
    val onExpand: () -> Unit,
)

/** The full player (Android and iOS). [collapsed] is true while the sheet is collapsed. */
class PlayerSlot(
    val viewModel: PlayerViewModel,
    val onClose: () -> Unit,
    val onNavigateToAlbum: (String) -> Unit,
    val onNavigateToArtist: (String) -> Unit,
    val collapsed: Boolean,
)

class NavigationSlotItem(val key: String, val label: String, val icon: ImageVector)

/**
 * The app's top-level navigation (Android tabs, desktop sidebar). [floating] mirrors upstream's
 * floating bar: content fills the screen and the bar reports the height it occupies, which upstream
 * uses to place the mini player.
 */
class NavigationSlot(
    val items: List<NavigationSlotItem>,
    val selectedIndex: Int,
    val onSelect: (Int) -> Unit,
    val navigationVisible: Boolean,
    val bottomBar: Boolean,
    val floating: Boolean,
    val onOccupiedHeightChanged: (Dp) -> Unit,
    val header: @Composable () -> Unit,
    val footer: (@Composable () -> Unit)?,
    val modifier: Modifier,
    val content: @Composable () -> Unit,
    /** Upstream's per-tab modifier (long-press Search for music recognition, TV focus), by item index. */
    val itemModifier: @Composable (Int) -> Modifier = { Modifier },
)

/** Desktop's bottom player bar, with the actions that live in desktop-only state. */
class DesktopPlayerBarSlot(
    val onExpand: () -> Unit,
    val onToggleQueue: () -> Unit,
    val onToggleLyrics: () -> Unit,
    val onToggleDevices: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val volume: Float,
    val onVolumeChange: (Float) -> Unit,
    /** The restored track before playback starts (desktop loads it into the player only on play). */
    val restoredSong: SongItem?,
    val isShuffled: () -> Boolean = { false },
    val onToggleShuffle: (() -> Unit)? = null,
    val repeatMode: () -> com.metrolist.shared.model.RepeatMode = { com.metrolist.shared.model.RepeatMode.OFF },
    val onCycleRepeat: (() -> Unit)? = null,
    val isQueueOpen: () -> Boolean = { false },
    val isLyricsOpen: () -> Boolean = { false },
)

/**
 * Desktop's library for Shell's "Your Library" sidebar: the items upstream's Library screen shows
 * and upstream's own opener (which loads playlists, albums and artists before navigating).
 */
class DesktopLibrarySlot(
    val items: () -> List<YTItem>,
    val open: (YTItem) -> Unit,
)
