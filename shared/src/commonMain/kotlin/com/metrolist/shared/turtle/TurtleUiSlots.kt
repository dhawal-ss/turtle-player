package com.metrolist.shared.turtle

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.ui.screens.HomeContentText
import com.metrolist.shared.ui.screens.HomeShelfCache
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState
import com.metrolist.shared.viewmodels.SearchViewModel.SearchUiState

/**
 * Turtle Player's UI override points (turtle/HOOKS.md). This file is ours: a new file in the
 * upstream tree, so it never conflicts with upstream merges. Each upstream screen calls into it with
 * a single line; when a slot is null, upstream's own UI renders unchanged.
 */
object TurtleUiSlots {
    var home: (@Composable (HomeSlot) -> Unit)? = null
    var search: (@Composable (SearchSlot) -> Unit)? = null
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
