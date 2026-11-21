package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.PlayGlyph
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_artist_about
import app.turtleplayer.ui.generated.resources.shell_artist_back
import app.turtleplayer.ui.generated.resources.shell_artist_blocked
import app.turtleplayer.ui.generated.resources.shell_artist_error_body
import app.turtleplayer.ui.generated.resources.shell_artist_error_title
import app.turtleplayer.ui.generated.resources.shell_artist_follow
import app.turtleplayer.ui.generated.resources.shell_artist_following
import app.turtleplayer.ui.generated.resources.shell_artist_item_more
import app.turtleplayer.ui.generated.resources.shell_artist_latest_release
import app.turtleplayer.ui.generated.resources.shell_artist_like
import app.turtleplayer.ui.generated.resources.shell_artist_loading
import app.turtleplayer.ui.generated.resources.shell_artist_meta_pair
import app.turtleplayer.ui.generated.resources.shell_artist_monthly_listeners
import app.turtleplayer.ui.generated.resources.shell_artist_more
import app.turtleplayer.ui.generated.resources.shell_artist_play
import app.turtleplayer.ui.generated.resources.shell_artist_play_item
import app.turtleplayer.ui.generated.resources.shell_artist_popular
import app.turtleplayer.ui.generated.resources.shell_artist_radio
import app.turtleplayer.ui.generated.resources.shell_artist_refresh
import app.turtleplayer.ui.generated.resources.shell_artist_retry
import app.turtleplayer.ui.generated.resources.shell_artist_see_more
import app.turtleplayer.ui.generated.resources.shell_artist_show_all
import app.turtleplayer.ui.generated.resources.shell_artist_show_less
import app.turtleplayer.ui.generated.resources.shell_artist_shuffle
import app.turtleplayer.ui.generated.resources.shell_artist_subscribers
import app.turtleplayer.ui.generated.resources.shell_artist_unblock
import app.turtleplayer.ui.generated.resources.shell_artist_unlike
import app.turtleplayer.ui.theme.LocalShellGround
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistPageResult
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.model.isVideoShelf
import com.metrolist.shared.settings.displayTitle
import com.metrolist.shared.turtle.ArtistPageSlot
import com.metrolist.shared.ui.components.CollectionPreviews
import com.metrolist.shared.ui.components.RefreshIndicator
import com.metrolist.shared.ui.menu.onSecondaryClick
import com.metrolist.shared.utils.isPullToRefreshSupported
import com.metrolist.shared.utils.monthlyAudienceText
import com.metrolist.shared.utils.parseAudienceCount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.compose.resources.stringResource

/** Popular shows five songs, ten after "See more" (Spotify). */
private const val POPULAR_COLLAPSED = 5
private const val POPULAR_EXPANDED = 10

/** Upstream's key for the single-album "latest release" shelf (ArtistScreen.kt). */
private const val LATEST_RELEASE = "latest_release"

private enum class ArtistShelfKind { Popular, Release, Videos, Cards }

/** One of upstream's artist sections, classified once per page so composition only lays it out. */
private class ArtistShelf(
    val index: Int,
    val title: String,
    val items: List<YTItem>,
    val kind: ArtistShelfKind,
    val browseId: String?,
    val browseParams: String?,
    val loadable: Boolean,
    val showReleaseType: Boolean,
) {
    val songs: List<SongItem> = if (kind == ArtistShelfKind.Popular) items.filterIsInstance<SongItem>() else emptyList()
}

/**
 * Upstream's sections in display order. Popular is the top-tracks shelf (or, failing that, the first
 * song shelf); the one-album "latest_release" shelf becomes a feature card; video shelves are 16:9.
 */
private fun artistShelves(
    sections: Map<String, List<YTItem>>,
    browseIds: Map<String, Pair<String?, String?>>,
    loadable: Set<String>,
): List<ArtistShelf> {
    val page = ArtistPageResult(sections, browseIds)
    val videoSections = page.videoSections
    val popular = page.topTracksSection ?: sections.entries.firstOrNull { (title, items) ->
        title != "header" && items.isNotEmpty() && items.all { it is SongItem } && !items.isVideoShelf(allowUntypedSongs = title in videoSections)
    }?.key
    return sections.entries
        .filter { (title, items) -> title != "header" && items.isNotEmpty() }
        .mapIndexed { i, (title, items) ->
            val kind = when {
                title == popular -> ArtistShelfKind.Popular
                title == LATEST_RELEASE && items.singleOrNull() is AlbumItem -> ArtistShelfKind.Release
                items.isVideoShelf(allowUntypedSongs = title in videoSections) -> ArtistShelfKind.Videos
                else -> ArtistShelfKind.Cards
            }
            val browse = browseIds[title]
            ArtistShelf(
                index = i,
                title = title,
                items = items,
                kind = kind,
                browseId = browse?.first,
                browseParams = browse?.second,
                loadable = title in loadable,
                showReleaseType = items.filterIsInstance<AlbumItem>().mapNotNull { it.type }.distinct().size > 1,
            )
        }
}

/**
 * Shell's Artist page, in Spotify's familiar shape: a full-bleed hero with the name set huge over it,
 * Follow / Radio / Shuffle / Play, numbered Popular songs, then card rows and an About card. It takes
 * every argument of upstream's `ArtistScreen` (HOOKS.md #36), so all of its features keep working.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellArtistPage(slot: ArtistPageSlot) = ShellTheme {
    val c = Shell.colors
    val ground = LocalShellGround.current ?: c.ground
    val state = slot.listState ?: rememberLazyListState()
    val fallbackArtist = remember(slot.artistId, slot.initialArtistName) {
        CollectionPreviews.artist(slot.artistId) ?: ArtistItem(slot.artistId, slot.initialArtistName.orEmpty(), null)
    }
    val artist = slot.artist ?: fallbackArtist
    val shelves = remember(slot.sections, slot.sectionBrowseIds, slot.loadableSections) {
        artistShelves(slot.sections, slot.sectionBrowseIds, slot.loadableSections)
    }
    Box(slot.modifier.fillMaxSize().background(ground)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val desktop = ShellPlatform.desktop
            val wide = maxWidth > 840.dp
            val heroHeight = when {
                desktop -> 340.dp
                constraints.hasBoundedHeight -> maxOf(maxHeight * 0.42f, 280.dp)
                else -> 360.dp
            }
            val gutter = if (desktop) 32.dp else 16.dp
            ArtistRefreshBox(
                enabled = isPullToRefreshSupported && !desktop,
                isLoading = slot.isLoading && slot.artist != null,
                onRefresh = slot.onRefresh,
                insets = slot.headerWindowInsets,
            ) {
                ArtistList(slot, artist, shelves, state, heroHeight, gutter, wide)
            }
            ArtistTopBar(slot, artist, state, heroHeight)
        }
        slot.overlay()
    }
}

/** Upstream's pull to refresh (phones): the spinner waits for the reload to start and finish. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ArtistRefreshBox(enabled: Boolean, isLoading: Boolean, onRefresh: () -> Unit, insets: WindowInsets, content: @Composable () -> Unit) {
    if (!enabled) {
        content()
    } else {
        var userRefreshing by remember { mutableStateOf(false) }
        val loadingNow by rememberUpdatedState(isLoading)
        LaunchedEffect(userRefreshing) {
            if (!userRefreshing) return@LaunchedEffect
            withTimeoutOrNull(1_500L) { snapshotFlow { loadingNow }.first { it } }
            snapshotFlow { loadingNow }.first { !it }
            userRefreshing = false
        }
        val refreshState = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = userRefreshing,
            onRefresh = {
                userRefreshing = true
                if (!loadingNow) onRefresh()
            },
            state = refreshState,
            indicator = {
                Box(Modifier.align(Alignment.TopCenter).windowInsetsPadding(insets)) {
                    RefreshIndicator(state = refreshState, isRefreshing = userRefreshing)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}

@Composable
private fun ArtistList(
    slot: ArtistPageSlot,
    artist: ArtistItem,
    shelves: List<ArtistShelf>,
    state: LazyListState,
    heroHeight: Dp,
    gutter: Dp,
    wide: Boolean,
) {
    val placeholder = slot.artist == null
    val failed = placeholder && !slot.isLoading
    val loadingShelves = !failed && (placeholder || (slot.isLoading && shelves.isEmpty()))
    val rise = rememberRiseIn(state)
    var popularExpanded by remember(slot.artistId) { mutableStateOf(false) }
    val description = slot.description?.takeIf { it.isNotBlank() }
    val showAbout = !placeholder && (description != null || slot.onAboutClick != null)
    val errorTitle = stringResource(Res.string.shell_artist_error_title)
    val errorBody = slot.errorMessage?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.shell_artist_error_body)

    LazyColumn(
        state = state,
        contentPadding = PaddingValues(top = slot.contentPadding.calculateTopPadding(), bottom = slot.contentPadding.calculateBottomPadding() + 32.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hero", contentType = "hero") { ArtistHero(slot, artist, placeholder, heroHeight, state, gutter) }
        if (failed) {
            item(key = "error", contentType = "error") { ArtistMessage(errorTitle, errorBody, slot.onRefresh, gutter) }
        } else {
            item(key = "actions", contentType = "actions") { ArtistActions(slot, artist, placeholder, gutter) }
            val error = slot.errorMessage
            if (!placeholder && error != null) {
                item(key = "error-inline", contentType = "error") { ArtistInlineError(error, slot.onRefresh, gutter) }
            }
        }

        if (loadingShelves) {
            item(key = "skeleton", contentType = "skeleton") { ArtistSkeleton(gutter) }
        } else if (!failed) {
            var n = 0
            shelves.forEach { shelf ->
                val headerKey = "h:${shelf.index}:${shelf.title}"
                when (shelf.kind) {
                    ArtistShelfKind.Popular -> {
                        val shown = shelf.songs.take(if (popularExpanded) POPULAR_EXPANDED else POPULAR_COLLAPSED)
                        val titleIndex = n++
                        item(key = headerKey, contentType = "title") {
                            // Upstream asks for the visible songs' metadata (play counts, durations) in one batch.
                            LaunchedEffect(shown) { slot.onItemsVisible?.invoke(shown) }
                            ArtistShelfTitle(shelf, slot, gutter, Modifier.riseIn(rise, headerKey, titleIndex))
                        }
                        shown.forEachIndexed { i, song ->
                            val key = "p:${shelf.index}:$i:${song.id}"
                            val rowIndex = n++
                            item(key = key, contentType = "song") {
                                ArtistPopularRow(song, i + 1, slot, gutter, Modifier.riseIn(rise, key, rowIndex))
                            }
                        }
                        if (shelf.songs.size > POPULAR_COLLAPSED) {
                            item(key = "p:${shelf.index}:toggle", contentType = "toggle") {
                                ArtistMoreToggle(popularExpanded, { popularExpanded = !popularExpanded }, gutter)
                            }
                        }
                    }

                    ArtistShelfKind.Release -> {
                        val album = shelf.items.single() as AlbumItem
                        val index = n++
                        item(key = headerKey, contentType = "release") {
                            Column(Modifier.riseIn(rise, headerKey, index)) {
                                ArtistShelfTitle(shelf, slot, gutter)
                                ArtistReleaseCard(album, shelf, slot, gutter, wide)
                            }
                        }
                    }

                    else -> {
                        val index = n++
                        item(key = headerKey, contentType = if (shelf.kind == ArtistShelfKind.Videos) "videos" else "cards") {
                            Column(Modifier.riseIn(rise, headerKey, index)) {
                                ArtistShelfTitle(shelf, slot, gutter)
                                ArtistCardRow(shelf, slot, gutter, wide)
                            }
                        }
                    }
                }
            }
            if (showAbout) {
                val index = n++
                item(key = "about", contentType = "about") {
                    ArtistAboutCard(artist, description, slot.aboutImageUrl, slot.onAboutClick, gutter, Modifier.riseIn(rise, "about", index))
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Hero, top bar and actions
// ---------------------------------------------------------------------------------------------

/**
 * Full-bleed picture with the name set huge over a gradient into the ground. The picture drifts at
 * half the scroll speed and fades; scroll is read in graphicsLayer, so scrolling only redraws it.
 */
@Composable
private fun ArtistHero(slot: ArtistPageSlot, artist: ArtistItem, placeholder: Boolean, height: Dp, state: LazyListState, gutter: Dp) {
    val c = Shell.colors
    val ground = LocalShellGround.current ?: c.ground
    val desktop = ShellPlatform.desktop
    val pulse = rememberShellPulse()
    // Phones crop the square profile picture; desktop's wide hero prefers the banner.
    val image = if (desktop) artist.banner ?: artist.thumbnail else artist.thumbnail ?: artist.banner
    val pulsing = placeholder && image == null && slot.isLoading
    val name = artist.displayTitle
    Box(Modifier.fillMaxWidth().height(height).clipToBounds()) {
        Box(
            Modifier.matchParentSize().graphicsLayer {
                val scrolled = if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat() else size.height
                val fraction = if (size.height > 0f) (scrolled / size.height).coerceIn(0f, 1f) else 0f
                translationY = scrolled * 0.5f
                alpha = (1f - fraction * 0.85f) * (if (pulsing) pulse.value else 1f)
            },
        ) {
            ShellArt(image, Modifier.fillMaxSize(), shape = RectangleShape, fallback = c.surface2, sizePx = if (desktop) 1440 else 1080)
            // Apple Music's animated artist video plays over the still once it is ready (upstream's order).
            slot.headerVideoLayer?.invoke(Modifier.matchParentSize())
        }
        // Top scrim for the back button and status bar, bottom fade into the page.
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.26f to Color.Transparent,
                    0.5f to Color.Transparent,
                    0.82f to ground.copy(alpha = 0.72f),
                    1f to ground,
                ),
            ),
        )
        val nameModifier = Modifier.align(Alignment.BottomStart).padding(start = gutter, end = gutter, bottom = 8.dp).graphicsLayer {
            val scrolled = if (state.firstVisibleItemIndex == 0) state.firstVisibleItemScrollOffset.toFloat() else size.height
            alpha = 1f - (scrolled / (height.toPx() * 0.6f)).coerceIn(0f, 1f)
        }
        if (placeholder && name.isBlank()) {
            Box(nameModifier.graphicsLayer { alpha = pulse.value }.fillMaxWidth(0.6f).height(if (desktop) 72.dp else 44.dp).clip(RoundedCornerShape(6.dp)).background(c.surface3))
        } else {
            val base = if (desktop) 88.sp else 48.sp
            val long = name.length > (if (desktop) 22 else 13)
            val size = if (long) base * 0.7f else base
            Text(
                name,
                style = Shell.type.display.copy(fontSize = size, lineHeight = size * 1.02f),
                color = c.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = nameModifier,
            )
        }
    }
}

/**
 * Collapsing bar: a back chevron on a scrim over the picture, which turns into a ground-colored bar
 * with the artist's name once the name scrolls under it (upstream reports this through
 * onAppBarVisibleChange, which Android uses for the status bar icons).
 */
@Composable
private fun ArtistTopBar(slot: ArtistPageSlot, artist: ArtistItem, state: LazyListState, heroHeight: Dp) {
    val c = Shell.colors
    val ground = LocalShellGround.current ?: c.ground
    val density = LocalDensity.current
    var barPx by remember { mutableIntStateOf(0) }
    val heroPx = with(density) { heroHeight.toPx() }
    val allowance = with(density) { 48.dp.toPx() }
    val show by remember(state, heroPx, allowance) {
        derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > heroPx - barPx - allowance }
    }
    val onChange by rememberUpdatedState(slot.onAppBarVisibleChange)
    LaunchedEffect(show) { onChange(show) }
    val progress by animateFloatAsState(if (show) 1f else 0f, ShellMotion.emphasized())
    Column(
        Modifier.fillMaxWidth().onSizeChanged { barPx = it.height }
            .drawBehind { drawRect(ground, alpha = progress) }
            .windowInsetsPadding(slot.headerWindowInsets),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            val back = slot.onBackClick
            if (back != null) ArtistBarButton(Glyphs.ChevronDown, 90f, stringResource(Res.string.shell_artist_back), back) { progress }
            Text(
                artist.displayTitle,
                style = Shell.type.title.copy(fontSize = 17.sp, lineHeight = 22.sp),
                color = c.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).graphicsLayer { alpha = progress },
            )
            val refresh = slot.onRefreshClick
            if (refresh != null) ArtistBarButton(Glyphs.Repeat, 0f, stringResource(Res.string.shell_artist_refresh), refresh) { progress }
        }
    }
}

@Composable
private fun ArtistBarButton(glyph: ImageVector, rotation: Float, label: String, onClick: () -> Unit, progress: () -> Float) {
    Box(
        Modifier.size(40.dp)
            .drawBehind { drawCircle(Color.Black, alpha = 0.4f * (1f - progress())) }
            .shellPressable(onClick, label = label, focusShape = CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Glyph(glyph, Shell.colors.ink, 22.dp, Modifier.rotate(rotation))
    }
}

/** "1.2M monthly listeners · 3M subscribers", either part optional (upstream's parsing). */
@Composable
private fun audienceLine(artist: ArtistItem): String? {
    val monthly = monthlyAudienceText(artist.monthlyListeners, stringResource(Res.string.shell_artist_monthly_listeners))
    val subscribers = parseAudienceCount(artist.subscribers)?.withLabel { stringResource(Res.string.shell_artist_subscribers, it) }
    return when {
        monthly != null && subscribers != null -> stringResource(Res.string.shell_artist_meta_pair, monthly, subscribers)
        else -> monthly ?: subscribers
    }
}

/**
 * Phones: Follow, Radio and more on the left, shuffle and the lime Play on the right (Spotify mobile).
 * Desktop: Play first, then shuffle, Follow, Radio and more (Spotify desktop).
 */
@Composable
private fun ArtistActions(slot: ArtistPageSlot, artist: ArtistItem, placeholder: Boolean, gutter: Dp) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    Column(Modifier.fillMaxWidth().padding(horizontal = gutter)) {
        if (!placeholder) {
            audienceLine(artist)?.let {
                Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(8.dp))
        if (placeholder) {
            ArtistActionsPlaceholder()
        } else {
            val follow: @Composable () -> Unit = {
                if (slot.showSubscribe) ArtistFollowPill(slot.isSubscribed, slot.subscribeSuccessCount, slot.onSubscribeClick)
            }
            val radio: @Composable () -> Unit = { ArtistRadioButton(slot.onMixClick, labeled = desktop) }
            val more: @Composable () -> Unit = {
                val onMenu = slot.onMenuClick
                if (onMenu != null) GlyphButton(Glyphs.More, stringResource(Res.string.shell_artist_more), onMenu, c.ink2, size = 40.dp, iconSize = 22.dp)
            }
            val shuffle: @Composable () -> Unit = {
                GlyphButton(Glyphs.Shuffle, stringResource(Res.string.shell_artist_shuffle), slot.onShuffleClick, c.ink2, size = 48.dp, iconSize = 24.dp)
            }
            val play: @Composable () -> Unit = { ArtistPlayButton(slot.onPlayAllClick) }
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                if (desktop) {
                    play()
                    Spacer(Modifier.width(12.dp))
                    shuffle()
                    Spacer(Modifier.width(12.dp))
                    follow()
                    Spacer(Modifier.width(8.dp))
                    radio()
                    Spacer(Modifier.width(4.dp))
                    more()
                } else {
                    follow()
                    Spacer(Modifier.width(6.dp))
                    radio()
                    more()
                    Spacer(Modifier.weight(1f))
                    shuffle()
                    Spacer(Modifier.width(4.dp))
                    play()
                }
            }
            if (artist.isBlocked) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(Res.string.shell_artist_blocked), style = Shell.type.bodySmall, color = c.ink2, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(12.dp))
                    ShellChip(stringResource(Res.string.shell_artist_unblock), false, slot.onUnblockClick)
                }
            }
        }
    }
}

@Composable
private fun ArtistActionsPlaceholder() {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    Row(
        Modifier.fillMaxWidth().height(64.dp).graphicsLayer { alpha = pulse.value },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.width(88.dp).height(32.dp).clip(CircleShape).background(c.surface2))
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.surface2))
        Spacer(Modifier.weight(1f))
        Box(Modifier.size(56.dp).clip(CircleShape).background(c.surface2))
    }
}

/** The page's one lime button. */
@Composable
private fun ArtistPlayButton(onClick: () -> Unit) {
    val c = Shell.colors
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(c.accent)
            .shellPressable(onClick, label = stringResource(Res.string.shell_artist_play), focusShape = CircleShape),
        contentAlignment = Alignment.Center,
    ) { PlayGlyph(c.onAccent, 24.dp) }
}

/** Outline pill that fills with ink once followed, popping on a spring when it changes. */
@Composable
private fun ArtistFollowPill(subscribed: Boolean, successCount: Int, onClick: () -> Unit) {
    val c = Shell.colors
    val bg by animateColorAsState(if (subscribed) c.ink else c.ink.copy(alpha = 0f), ShellMotion.emphasized())
    val fg by animateColorAsState(if (subscribed) c.ground else c.ink, ShellMotion.emphasized())
    val edge by animateColorAsState(if (subscribed) c.ink else c.ink2, ShellMotion.emphasized())
    val pop = remember { Animatable(1f) }
    var last by remember { mutableStateOf(subscribed to successCount) }
    LaunchedEffect(subscribed, successCount) {
        val now = subscribed to successCount
        if (now != last) {
            last = now
            pop.snapTo(0.88f)
            pop.animateTo(1f, ShellMotion.spring())
        }
    }
    val label = stringResource(if (subscribed) Res.string.shell_artist_following else Res.string.shell_artist_follow)
    Box(
        Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .height(32.dp).clip(CircleShape).background(bg).border(1.dp, edge, CircleShape)
            .shellPressable(onClick, label = label, focusShape = CircleShape)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** Artist radio: a labeled chip on desktop, an outline circle on phones where the row is tight. */
@Composable
private fun ArtistRadioButton(onClick: () -> Unit, labeled: Boolean) {
    val c = Shell.colors
    val label = stringResource(Res.string.shell_artist_radio)
    if (labeled) {
        Row(
            Modifier.height(32.dp).clip(CircleShape).background(c.surface2).shellPressable(onClick, label = label, focusShape = CircleShape)
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Glyph(Glyphs.Radio, c.ink, 18.dp)
            Spacer(Modifier.width(6.dp))
            Text(label, style = Shell.type.label, color = c.ink)
        }
    } else {
        Box(Modifier.size(40.dp).shellPressable(onClick, label = label, focusShape = CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(32.dp).border(1.dp, c.ink2, CircleShape), contentAlignment = Alignment.Center) {
                Glyph(Glyphs.Radio, c.ink, 18.dp)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sections
// ---------------------------------------------------------------------------------------------

@Composable
private fun ArtistShelfTitle(shelf: ArtistShelf, slot: ArtistPageSlot, gutter: Dp, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val title = when {
        shelf.kind == ArtistShelfKind.Popular -> stringResource(Res.string.shell_artist_popular)
        shelf.title == LATEST_RELEASE -> stringResource(Res.string.shell_artist_latest_release)
        else -> shelf.title
    }
    val browse = shelf.browseId
    val onBrowse = if (shelf.kind == ArtistShelfKind.Videos) slot.onVideoSectionClick else slot.onSectionClick
    val desktop = ShellPlatform.desktop
    Row(modifier.fillMaxWidth().padding(start = gutter, end = gutter - 4.dp, top = 28.dp, bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Text(
            title,
            style = if (desktop) Shell.type.sectionTitle.copy(fontSize = 24.sp, lineHeight = 28.sp) else Shell.type.sectionTitle,
            color = c.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (browse != null && onBrowse != null) {
            Text(
                stringResource(Res.string.shell_artist_show_all),
                style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold),
                color = c.ink2,
                modifier = Modifier.shellPressable({ onBrowse(browse, shelf.browseParams, title) }).padding(4.dp),
            )
        }
    }
}

/**
 * A Popular song. Phones: rank, art, title over play count, like and more. Desktop: a table row
 * whose rank turns into a play glyph on hover, with plays, duration, and like/more on hover.
 * Wrapped in upstream's song container (swipe to queue on Android).
 */
@Composable
private fun ArtistPopularRow(song: SongItem, rank: Int, slot: ArtistPageSlot, gutter: Dp, modifier: Modifier) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    val current = slot.currentSongId == song.id
    val playing = current && slot.isPlaying
    val isLiked = slot.isSongLiked
    val onLike = slot.onLikeSong
    val liked = isLiked?.invoke(song.id) == true
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface2 else c.surface2.copy(alpha = 0f), ShellMotion.emphasized())
    val menu = { slot.onItemLongClick(song) }
    val artists = remember(song) { song.artists.joinToString(", ") { it.name } }
    Box(modifier.fillMaxWidth()) {
        slot.songContainer(song) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = gutter - 8.dp).clip(RoundedCornerShape(6.dp)).background(bg).hoverable(hover)
                    .shellPressable({ slot.onItemClick(song) }, label = song.title, onLongClick = menu)
                    .onSecondaryClick(menu)
                    .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.width(if (desktop) 32.dp else 26.dp), contentAlignment = Alignment.CenterStart) {
                    when {
                        playing -> Equalizer(height = 12.dp)
                        hovered -> Glyph(Glyphs.Play, c.ink, 16.dp)
                        else -> Text(rank.toString(), style = Shell.type.index.copy(fontSize = 13.sp), color = if (current) c.accent else c.ink2)
                    }
                }
                ShellArt(song.displayThumbnailUrl, Modifier.size(if (desktop) 40.dp else 48.dp), shape = RoundedCornerShape(4.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(song.title, style = Shell.type.body, color = if (current) c.accent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = if (desktop) artists else song.playCountText ?: artists
                    if (sub.isNotBlank()) {
                        Text(sub, style = Shell.type.bodySmall, color = if (hovered) c.ink else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (desktop) {
                    Text(
                        song.playCountText.orEmpty(),
                        style = Shell.type.bodySmall,
                        color = c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.5f).padding(horizontal = 12.dp),
                    )
                }
                val showSide = !desktop || hovered
                if (isLiked != null && onLike != null) {
                    Box(Modifier.graphicsLayer { alpha = if (showSide || liked) 1f else 0f }) {
                        ArtistLikeToggle(liked) { onLike(song) }
                    }
                }
                if (desktop) {
                    Text(
                        song.duration?.takeIf { it > 0 }?.let { formatTime(it * 1000) }.orEmpty(),
                        style = Shell.type.bodySmall,
                        color = c.ink2,
                        maxLines = 1,
                        modifier = Modifier.width(48.dp).padding(start = 8.dp),
                    )
                }
                Box(Modifier.graphicsLayer { alpha = if (showSide) 1f else 0f }) {
                    GlyphButton(Glyphs.More, stringResource(Res.string.shell_artist_item_more, song.title), menu, c.ink2, size = 40.dp, iconSize = 18.dp)
                }
            }
        }
    }
}

/** Upstream's like (onLikeSong): a "+" that springs into a lime check, like Now Playing's. */
@Composable
private fun ArtistLikeToggle(liked: Boolean, onToggle: () -> Unit) {
    val c = Shell.colors
    val scale = remember { Animatable(1f) }
    var pulse by remember { mutableIntStateOf(0) }
    LaunchedEffect(pulse) {
        if (pulse > 0) {
            scale.snapTo(0.82f)
            scale.animateTo(1f, ShellMotion.spring())
        }
    }
    Box(
        Modifier.size(40.dp).shellPressable(
            { pulse++; onToggle() },
            label = stringResource(if (liked) Res.string.shell_artist_unlike else Res.string.shell_artist_like),
            focusShape = CircleShape,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.graphicsLayer { scaleX = scale.value; scaleY = scale.value }, contentAlignment = Alignment.Center) {
            if (liked) {
                Box(Modifier.size(18.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) {
                    Glyph(Glyphs.Check, c.onAccent, 10.dp)
                }
            } else {
                Glyph(Glyphs.Add, c.ink2, 20.dp)
            }
        }
    }
}

@Composable
private fun ArtistMoreToggle(expanded: Boolean, onClick: () -> Unit, gutter: Dp) {
    Text(
        stringResource(if (expanded) Res.string.shell_artist_show_less else Res.string.shell_artist_see_more),
        style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold),
        color = Shell.colors.ink2,
        modifier = Modifier.padding(start = gutter - 4.dp, top = 8.dp).shellPressable(onClick).padding(4.dp),
    )
}

/** Latest release as a feature card: art, kind and year, title, and a play button. */
@Composable
private fun ArtistReleaseCard(album: AlbumItem, shelf: ArtistShelf, slot: ArtistPageSlot, gutter: Dp, wide: Boolean) {
    val c = Shell.colors
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface2 else c.surface1, ShellMotion.emphasized())
    val menu = { slot.onItemLongClick(album) }
    val artClick = slot.onAlbumArtworkClick
    val onPlay = slot.onPlayItem
    val artSize = if (wide) 120.dp else 96.dp
    Row(
        Modifier.padding(horizontal = gutter).widthIn(max = 720.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover)
            .shellPressable({ slot.onItemClick(album) }, label = album.title, onLongClick = menu)
            .onSecondaryClick(menu)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val token = "artist:${slot.artistId}:${shelf.title}:0"
        ShellArt(
            album.thumbnail,
            Modifier.size(artSize).then(
                if (artClick != null) Modifier.shellPressable({ artClick(album, token) }, label = album.title, onLongClick = menu) else Modifier,
            ),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val meta = listOfNotNull(album.type, album.year).joinToString(" · ")
            if (meta.isNotBlank()) IndexLabel(meta)
            Text(
                album.title,
                style = Shell.type.body.copy(fontSize = 18.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
                color = c.ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (onPlay != null) {
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(c.ink)
                    .shellPressable({ onPlay(album) }, label = stringResource(Res.string.shell_artist_play_item, album.title), focusShape = CircleShape),
                contentAlignment = Alignment.Center,
            ) { PlayGlyph(c.ground, 20.dp) }
        }
    }
}

/** A horizontal row of cards: square albums and singles, 16:9 videos, round artists. */
@Composable
private fun ArtistCardRow(shelf: ArtistShelf, slot: ArtistPageSlot, gutter: Dp, wide: Boolean) {
    val desktop = ShellPlatform.desktop
    val video = shelf.kind == ArtistShelfKind.Videos
    val width = when {
        video && wide -> 300.dp
        video -> 240.dp
        wide -> 168.dp
        else -> 136.dp
    }
    val continuation = slot.sectionContinuation?.takeIf { shelf.loadable }
    LazyRow(
        contentPadding = PaddingValues(horizontal = if (desktop) gutter - 10.dp else gutter),
        horizontalArrangement = Arrangement.spacedBy(if (desktop) 2.dp else 14.dp),
    ) {
        itemsIndexed(shelf.items, key = { i, item -> "$i:${item.id}" }, contentType = { _, item -> if (item is ArtistItem) "artist" else "card" }) { i, item ->
            ArtistCard(item, i, shelf, slot, width, video)
        }
        if (continuation != null) {
            item(key = "continuation", contentType = "continuation") {
                Box(Modifier.width(width).height(if (video) width * 9f / 16f else width), contentAlignment = Alignment.Center) {
                    continuation(shelf.title)
                }
            }
        }
    }
}

@Composable
private fun ArtistCard(item: YTItem, index: Int, shelf: ArtistShelf, slot: ArtistPageSlot, width: Dp, video: Boolean) {
    val c = Shell.colors
    if (item is SongItem) {
        LaunchedEffect(item.id, item.duration) { slot.onItemsVisible?.invoke(listOf(item)) }
    }
    val (hover, hovered) = rememberHover()
    val open: () -> Unit = { if (video) slot.onVideoItemClick(item) else slot.onItemClick(item) }
    val menu = { slot.onItemLongClick(item) }
    val current = item is SongItem && item.id == slot.currentSongId
    val onPlay = slot.onPlayItem
    val play: (() -> Unit)? = if (item is SongItem) open else if (item is AlbumItem && onPlay != null) ({ onPlay(item) }) else null
    val artClick = slot.onAlbumArtworkClick
    Column(
        Modifier.hoverable(hover)
            .shellPressable(open, label = item.title, onLongClick = menu)
            .onSecondaryClick(menu)
            .cardHover(hovered).width(width),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box {
            val shape = if (item is ArtistItem) CircleShape else ShellShapes.Art
            val artModifier = if (video) Modifier.width(width).aspectRatio(16f / 9f) else Modifier.size(width)
            val tapArt = if (item is AlbumItem && artClick != null) {
                Modifier.shellPressable({ artClick(item, "artist:${slot.artistId}:${shelf.title}:$index") }, label = item.title, onLongClick = menu)
            } else {
                Modifier
            }
            ShellArt(item.displayThumbnailUrl, artModifier.then(tapArt), shape = shape)
            if (current && slot.isPlaying) {
                Equalizer(modifier = Modifier.align(Alignment.BottomStart).padding(10.dp))
            }
            if (play != null) HoverPlayButton(hovered, 44.dp, play, Modifier.align(Alignment.BottomEnd).padding(8.dp))
        }
        Text(
            item.displayTitle,
            style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold),
            color = if (current) c.accent else c.ink,
            maxLines = if (video) 2 else 1,
            overflow = TextOverflow.Ellipsis,
        )
        cardSubtitle(item, shelf, video)?.let {
            Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun cardSubtitle(item: YTItem, shelf: ArtistShelf, video: Boolean): String? {
    val text: String? = when (item) {
        is AlbumItem -> listOfNotNull(item.type.takeIf { shelf.showReleaseType }, item.year).joinToString(" · ")
        is SongItem -> (if (video) item.playCountText else null) ?: subtitleOf(item)
        is ArtistItem -> item.subscribers
        is PlaylistItem -> item.author ?: item.songCount
        else -> subtitleOf(item)
    }
    return text?.takeIf { it.isNotBlank() }
}

/**
 * About: the photo full-bleed with the bio over a dark fade. Opens upstream's About page when it has
 * one; otherwise tapping expands the description in place.
 */
@Composable
private fun ArtistAboutCard(artist: ArtistItem, description: String?, imageUrl: String?, onClick: (() -> Unit)?, gutter: Dp, modifier: Modifier) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    var expanded by remember { mutableStateOf(false) }
    val (hover, hovered) = rememberHover()
    val hasImage = !imageUrl.isNullOrBlank()
    val shape = if (desktop) RoundedCornerShape(8.dp) else ShellShapes.Card
    val zoom by animateFloatAsState(if (hovered) 1.03f else 1f, ShellMotion.emphasized())
    Column(modifier.fillMaxWidth()) {
        Text(
            stringResource(Res.string.shell_artist_about),
            style = if (desktop) Shell.type.sectionTitle.copy(fontSize = 24.sp, lineHeight = 28.sp) else Shell.type.sectionTitle,
            color = c.ink,
            modifier = Modifier.padding(start = gutter, end = gutter, top = 28.dp, bottom = 12.dp),
        )
        Box(
            Modifier.padding(horizontal = gutter).widthIn(max = 720.dp).fillMaxWidth().clip(shape).background(c.surface2).hoverable(hover)
                .shellPressable(onClick ?: { expanded = !expanded }, label = artist.displayTitle),
        ) {
            if (hasImage) {
                ShellArt(imageUrl, Modifier.matchParentSize().graphicsLayer { scaleX = zoom; scaleY = zoom }, shape = RectangleShape, sizePx = 1080)
                Box(
                    Modifier.matchParentSize().background(
                        Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.88f)),
                    ),
                )
            }
            Column(
                Modifier.fillMaxWidth().heightIn(min = if (hasImage) (if (desktop) 360.dp else 300.dp) else 0.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
            ) {
                audienceLine(artist)?.let {
                    Text(it, style = Shell.type.label.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (description != null) {
                    Text(
                        description,
                        style = Shell.type.bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp),
                        color = c.ink.copy(alpha = 0.86f),
                        maxLines = if (expanded) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (onClick != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(artist.displayTitle, style = Shell.type.title.copy(fontSize = 15.sp), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Glyph(Glyphs.ArrowRight, c.ink, 22.dp)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Loading and errors
// ---------------------------------------------------------------------------------------------

/** Pulsing placeholders shaped like Popular and a card row while the page loads. */
@Composable
private fun ArtistSkeleton(gutter: Dp) {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    val label = stringResource(Res.string.shell_artist_loading)
    Column(
        Modifier.fillMaxWidth().semantics { contentDescription = label }.graphicsLayer { alpha = pulse.value }
            .padding(start = gutter, end = gutter, top = 28.dp),
    ) {
        Box(Modifier.width(120.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2))
        Spacer(Modifier.height(12.dp))
        repeat(POPULAR_COLLAPSED) {
            Row(Modifier.fillMaxWidth().height(60.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(26.dp))
                Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.fillMaxWidth(0.55f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
                    Box(Modifier.fillMaxWidth(0.3f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
                }
            }
        }
        Spacer(Modifier.height(28.dp))
        Box(Modifier.width(96.dp).height(22.dp).clip(RoundedCornerShape(4.dp)).background(c.surface2))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(3) { Box(Modifier.weight(1f).aspectRatio(1f).clip(ShellShapes.Art).background(c.surface2)) }
        }
    }
}

/** Nothing loaded: a short message and Retry (upstream's error page). */
@Composable
private fun ArtistMessage(title: String, body: String, onRetry: () -> Unit, gutter: Dp) {
    val c = Shell.colors
    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(start = gutter, end = gutter, top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = Shell.type.body.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold), color = c.ink)
        Text(body, style = Shell.type.bodySmall, color = c.ink2)
        Spacer(Modifier.height(6.dp))
        ShellChip(stringResource(Res.string.shell_artist_retry), false, onRetry)
    }
}

/** A refresh failed but the page has content: keep it, and offer Retry above the sections. */
@Composable
private fun ArtistInlineError(message: String, onRetry: () -> Unit, gutter: Dp) {
    val c = Shell.colors
    Row(Modifier.fillMaxWidth().padding(start = gutter, end = gutter, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = Shell.type.bodySmall, color = c.danger, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        ShellChip(stringResource(Res.string.shell_artist_retry), false, onRetry)
    }
}
