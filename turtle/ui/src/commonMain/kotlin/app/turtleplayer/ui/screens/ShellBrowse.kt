package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import app.turtleplayer.bridge.ExploreData
import app.turtleplayer.bridge.ExploreState
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.ui.navigation.LocalNavigator
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import kotlinx.coroutines.flow.StateFlow

/**
 * Browse (Explore) as a stable destination on both platforms: the Windows sidebar row and a tab on
 * Android. It is Shell's own page over the current route, anchored to the route history ([ExploreNav]):
 * a category opened from it pushes a real upstream route (Screen.Browse), and Back from there shows
 * Browse again where it was scrolled. Upstream has no Browse route of its own (adding one would be a
 * hook), so this state lives in memory and is not restored after the process is killed.
 */
internal object ShellBrowse {
    /** Where Browse opened and its scroll, shared by the desktop sidebar and the Android tab. */
    val nav = ExploreNav()

    /** The last Home Shell drew on either platform: Browse and Search read its YouTube Music data and opener. */
    var home by mutableStateOf<HomeSlot?>(null)

    /** Items on Home right now (suggestions for the artist picks sheet); empty before Home has drawn. */
    fun homeItems(): List<YTItem> {
        val ui = home?.uiState ?: return emptyList()
        return ui.quickPicks + ui.speedDialItems + ui.data.orEmpty().flatMap { it.items }
    }

    /**
     * YouTube Music's moods and genres as openable categories: Home's once it has them (signed out),
     * else those of Explore's own load ([explore], signed in). Empty until Home has drawn, since they
     * open through Home's opener.
     */
    fun categories(explore: ExploreData? = null): List<BrowseItem> {
        val ui = home?.uiState ?: return emptyList()
        return ui.moodsAndGenres
            .map { BrowseItem(id = it.id, title = it.name, thumbnail = it.thumbnail, params = it.params) }
            .ifEmpty { explore?.genres.orEmpty() }
            .distinctBy { it.id to it.params }
    }

    /** Opens [item] (a category, a playlist) through Home's opener: a real route, so Back comes back here. */
    fun openItem(item: YTItem) {
        home?.onItemClick?.invoke(item)
    }
}

/** Explore's own load as Shell reaches it: [state] to read, [load] to ask for one ([force]: Retry). */
internal interface ExploreSource {
    val state: StateFlow<ExploreState>

    fun load(force: Boolean)
}

/** Where Explore's own data comes from: the bridge in the app, a fake in headless UI tests. */
internal object ShellExploreSource {
    val bridge: ExploreSource = object : ExploreSource {
        override val state: StateFlow<ExploreState> get() = TurtleBridge.explore

        override fun load(force: Boolean) = TurtleBridge.loadExplore(force)
    }
    var source: ExploreSource = bridge
}

/**
 * Explore's own load: signed in, Home's feed carries no moods and genres (and, on its first page, no
 * charts or new releases), so Explore and Search's tiles read YouTube Music's Explore page through the
 * bridge. Asks for a load while [wanted]; the bridge keeps a fresh result and joins a running load.
 */
@Composable
internal fun rememberExploreState(wanted: Boolean): ExploreState {
    val source = ShellExploreSource.source
    val flow = remember(source) { source.state }
    val state by flow.collectAsState()
    LaunchedEffect(wanted, source) { if (wanted) source.load(false) }
    return state
}

/** Where navigation stands: the top-level route and its back stack, as Browse anchors to them. */
internal class NavPosition(val top: Any, val stack: List<Any>)

/** The current navigation position; recomposes when it changes. */
@Composable
internal fun rememberNavPosition(): NavPosition {
    val state = LocalNavigator.current.state
    val top: Any = state.topLevelRoute
    val stack: List<Any> = state.backStacks.getValue(state.topLevelRoute).toList()
    return remember(top, stack) { NavPosition(top, stack) }
}

/**
 * The page under Browse, while Browse [covered] it: hidden from screen readers, which would otherwise
 * read and focus the page behind. It stays composed, so it keeps its state for Back.
 */
internal fun Modifier.hiddenUnderBrowse(covered: Boolean): Modifier = if (covered) clearAndSetSemantics {} else this

/** Opens Browse over the current page (Search's "See all", the Android tab). */
@Composable
internal fun rememberOpenBrowse(): () -> Unit {
    val position by rememberUpdatedState(rememberNavPosition())
    return remember { { ShellBrowse.nav.open(position.top, position.stack) } }
}

/**
 * Android's Browse page over the tab content: fades in where it was opened, hides (keeping its
 * scroll) under pages opened from it, and closes on system Back. [bottom] keeps its end clear of
 * the mini player and a floating tab bar.
 */
@Composable
internal fun BrowseOverlay(
    position: NavPosition,
    bottom: Dp,
) {
    LaunchedEffect(position) { ShellBrowse.nav.follow(position.top, position.stack) }
    val visible = ShellBrowse.nav.visible(position.top, position.stack)
    val entry = ShellBrowse.nav.entry
    // The last entry, so the exit animation keeps its content.
    val last = remember { arrayOfNulls<ExploreEntry>(1) }
    if (entry != null) last[0] = entry
    // Composed after upstream's content, so it handles Back first while Browse is showing.
    NavigationBackHandler(enabled = visible) { ShellBrowse.nav.close() }
    AnimatedVisibility(visible, enter = fadeIn(ShellMotion.emphasized()), exit = fadeOut(ShellMotion.exit())) {
        val shown = last[0] ?: return@AnimatedVisibility
        ShellTheme {
            Box(Modifier.fillMaxSize().background(Shell.colors.panel).windowInsetsPadding(WindowInsets.statusBars).padding(bottom = bottom)) {
                ShellExplorePage(ShellBrowse.home, shown.list, shown.rise, Modifier.fillMaxSize())
            }
        }
    }
}
