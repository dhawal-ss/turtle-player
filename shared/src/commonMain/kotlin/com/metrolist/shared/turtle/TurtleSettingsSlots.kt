package com.metrolist.shared.turtle

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.ui.components.PlayerMenuAction
import com.metrolist.shared.ui.menu.MediaMenuHeaderAction
import com.metrolist.shared.ui.settings.SettingsItemRegistration
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPageRegistration
import com.metrolist.shared.ui.settings.SettingsUiRegistry
import com.metrolist.shared.ui.settings.SharedSettingsHost
import com.metrolist.shared.ui.settings.settingsPageStack
import com.metrolist.shared.ui.settings.settingsPageStackOnNavigate
import com.metrolist.shared.ui.settings.settingsSearchResults

/*
 * Turtle Player's settings and menu override points (turtle/HOOKS.md). Ours: a new file in the upstream
 * tree. Shell draws upstream's settings registry and upstream's menu actions; these classes carry
 * upstream's arguments unchanged, and [TurtleSettingsNavigation] exposes upstream's own (internal)
 * settings navigation and search so Shell behaves exactly like upstream's screen.
 */

/** Everything upstream's `SharedSettingsRegistryScreen` receives (Settings and Integrations, every platform). */
class SettingsScreenSlot(
    val host: SharedSettingsHost,
    val modifier: Modifier,
    val registryFactory: @Composable (Int) -> SettingsUiRegistry,
)

/** One settings search hit: a page, or an item on it, with the text that matched ([context]). */
class TurtleSettingsSearchHit(
    val page: SettingsPageRegistration,
    val item: SettingsItemRegistration?,
    val context: String?,
)

/** Upstream's settings navigation and search, so Shell's screen follows the same rules. */
object TurtleSettingsNavigation {
    /** The page stack (names) that leads to [pageId] from the settings home. */
    fun stackFor(registry: SettingsUiRegistry, pageId: SettingsPageId): List<String> = registry.settingsPageStack(pageId)

    /** The next page stack when opening [pageId], or null when the host opens it itself (external page). */
    fun stackOnNavigate(
        registry: SettingsUiRegistry,
        stack: List<String>,
        pageId: SettingsPageId,
        parentPageId: SettingsPageId?,
        externalPages: Set<SettingsPageId>,
    ): List<String>? = registry.settingsPageStackOnNavigate(stack, pageId, parentPageId, externalPages)

    /** Upstream's settings search for [query] (pages, items, choices and values). */
    fun search(registry: SettingsUiRegistry, query: String): List<TurtleSettingsSearchHit> =
        settingsSearchResults(registry, query.trim().lowercase(), Settings.shared.listenTogetherEnabled)
            .map { TurtleSettingsSearchHit(it.page, it.item, it.context) }
}

/**
 * A media menu's header (artwork, title, subtitle and its round actions). [popup] is true in cursor and
 * desktop context menus, false in phone sheets.
 */
class MenuHeaderSlot(
    val title: String,
    val subtitle: String?,
    val thumbnail: String?,
    val modifier: Modifier,
    val thumbnailAspectRatio: Float,
    val fallbackIcon: ImageVector?,
    val isExplicit: Boolean,
    val artistId: String?,
    val actions: List<MediaMenuHeaderAction>,
    val subtitleLinks: AnnotatedString?,
    val popup: Boolean,
)

/**
 * A row of primary menu actions or a group of menu actions. [popup] is true in cursor and desktop
 * context menus; [groupIndex] and [groupCount] place a popup group among its siblings.
 */
class MenuActionsSlot(
    val actions: List<PlayerMenuAction>,
    val modifier: Modifier,
    val popup: Boolean,
    val groupIndex: Int = 0,
    val groupCount: Int = 1,
)

/** The panel of a desktop dropdown or context menu; upstream keeps positioning, animation and dismissal. */
class MenuSurfaceSlot(
    val maxHeight: Dp,
    val modifier: Modifier,
    val content: @Composable ColumnScope.() -> Unit,
)
