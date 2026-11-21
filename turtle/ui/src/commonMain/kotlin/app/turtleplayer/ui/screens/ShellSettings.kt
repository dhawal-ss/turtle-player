package app.turtleplayer.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_set_results
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellColors
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.generated.resources.back
import com.metrolist.shared.generated.resources.close
import com.metrolist.shared.generated.resources.settings
import com.metrolist.shared.generated.resources.settings_search_no_results
import com.metrolist.shared.generated.resources.settings_search_no_results_desc
import com.metrolist.shared.turtle.SettingsScreenSlot
import com.metrolist.shared.turtle.TurtleSettingsNavigation
import com.metrolist.shared.turtle.TurtleSettingsSearchHit
import com.metrolist.shared.ui.LocalIsTv
import com.metrolist.shared.ui.components.SwipeBackLayout
import com.metrolist.shared.ui.navigation.PredictiveNavigationContent
import com.metrolist.shared.ui.settings.LocalSettingsPageBottomPadding
import com.metrolist.shared.ui.settings.LocalSettingsPageHasTopBar
import com.metrolist.shared.ui.settings.SettingsPageId
import com.metrolist.shared.ui.settings.SettingsPageRegistration
import com.metrolist.shared.ui.settings.SettingsPlatform
import com.metrolist.shared.ui.settings.SettingsUiRegistry
import com.metrolist.shared.ui.settings.SharedSettingsHost
import org.jetbrains.compose.resources.stringResource
import com.metrolist.shared.generated.resources.Res as SharedRes

/*
 * Shell's Settings (turtle/HOOKS.md, settings screen). Upstream's settings registry stays the source of
 * every page, group and control (Appearance, Player, Content, Lyrics, Privacy, Integrations, custom
 * pages such as Storage and About); Shell only draws it: a large Space Grotesk title, Shell's search,
 * numbered mono section labels, 60dp rows with hairlines, the lime switch and inline sliders. Phones get
 * a single stack with predictive back; wide windows (the desktop overlay) get a sidebar and a detail pane.
 * Navigation and search use upstream's own rules (TurtleSettingsNavigation), so deep links, external
 * pages, highlighting a search hit and the host callbacks behave exactly as before.
 */
@Composable
fun ShellSettingsScreen(slot: SettingsScreenSlot) = ShellTheme { ShellSettingsContent(slot) }

private fun String.toPageId(): SettingsPageId? = runCatching { SettingsPageId.valueOf(this) }.getOrNull()

@Composable
private fun ShellSettingsContent(slot: SettingsScreenSlot) {
    val host = slot.host
    val c = Shell.colors
    var settingsRevision by remember { mutableIntStateOf(0) }
    var pageStack by rememberSaveable { mutableStateOf(host.initialPage?.let { listOf(it.name) }.orEmpty()) }
    var highlightedItemId by rememberSaveable { mutableStateOf<String?>(null) }
    val registry = slot.registryFactory(settingsRevision)
    LaunchedEffect(host.initialPage, registry) {
        val initialPage = host.initialPage
        if (initialPage != null && pageStack == listOf(initialPage.name)) {
            pageStack = TurtleSettingsNavigation.stackFor(registry, initialPage)
        }
    }
    val selectedPageId = pageStack.lastOrNull()?.toPageId()
    SideEffect { host.onPageChanged(selectedPageId) }
    val stateHolder = rememberSaveableStateHolder()
    val homeListState = rememberLazyListState()
    val pageListStates = registry.pages.associate { page -> key(page.id) { page.id to rememberLazyListState() } }

    val openPage: (SettingsPageId, SettingsPageId?, String?) -> Unit = { pageId, parentPageId, itemId ->
        if (registry[pageId] != null) {
            val next = TurtleSettingsNavigation.stackOnNavigate(registry, pageStack, pageId, parentPageId, host.externalPages)
            if (next == null) {
                host.onOpenPage(pageId)
            } else {
                highlightedItemId = itemId
                pageStack = next
            }
        }
    }
    val closePage: () -> Unit = {
        highlightedItemId = null
        pageStack = pageStack.dropLast(1)
    }
    val onSettingValueChanged: () -> Unit = { settingsRevision++ }

    var containerWidth by remember { mutableIntStateOf(0) }
    val expanded = !host.singlePane && with(LocalDensity.current) { containerWidth.toDp() >= 840.dp }
    val ground = if (ShellPlatform.desktop) c.panel else c.ground
    val previousPageId = pageStack.dropLast(1).lastOrNull()?.toPageId()
    Box(slot.modifier.fillMaxSize().background(ground).onSizeChanged { containerWidth = it.width }) {
        if (expanded) {
            val firstPageId = registry.pages.firstOrNull { !it.custom }?.id
            val detailPageId = selectedPageId ?: firstPageId
            val rootPageId = pageStack.firstOrNull()?.toPageId()?.takeIf { registry[it] != null } ?: detailPageId
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                SettingsSidebar(
                    registry = registry,
                    host = host,
                    selectedPageId = rootPageId,
                    onPageSelected = { openPage(it, null, null) },
                    onSearchHit = { pageId, itemId -> openPage(pageId, null, itemId) },
                    modifier = Modifier.width(320.dp).fillMaxHeight(),
                )
                Box(Modifier.width(1.dp).fillMaxHeight().background(c.hairlineSoft))
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Row(
                        Modifier.fillMaxWidth().height(76.dp).padding(start = 20.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (pageStack.size > 1) {
                            GlyphButton(Glyphs.ArrowLeft, stringResource(SharedRes.string.back), closePage, c.ink, iconSize = 22.dp)
                            Spacer(Modifier.width(4.dp))
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                        Crossfade(detailPageId, Modifier.weight(1f), animationSpec = ShellMotion.emphasized()) { pageId ->
                            Text(
                                pageId?.let(registry::get)?.title.orEmpty(),
                                style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 1.1f),
                                color = c.ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading() },
                            )
                        }
                        GlyphButton(Glyphs.Close, stringResource(SharedRes.string.close), host.onClose, c.ink, iconSize = 16.dp)
                    }
                    PredictiveNavigationContent(
                        targetState = detailPageId,
                        previousState = previousPageId ?: firstPageId,
                        backEnabled = pageStack.size > 1,
                        onBack = closePage,
                        modifier = Modifier.fillMaxSize(),
                    ) { pageId ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            pageId?.let(registry::get)?.let { page ->
                                if (page.custom) {
                                    CustomSettingsPage(page, host, closePage) { openPage(it, page.id, null) }
                                } else {
                                    ShellSettingsPageList(
                                        page = page,
                                        listState = pageListStates.getValue(page.id),
                                        host = host,
                                        highlightedItemId = highlightedItemId,
                                        onNavigate = { openPage(it, page.id, null) },
                                        onSettingValueChanged = onSettingValueChanged,
                                        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp + host.bottomContentPadding),
                                        modifier = Modifier.widthIn(max = ShellSettingsMaxWidth),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            val compactContent: @Composable (SettingsPageId?) -> Unit = { pageId ->
                val page = pageId?.let(registry::get)
                if (page == null) {
                    SettingsHome(
                        registry = registry,
                        host = host,
                        listState = homeListState,
                        onPageSelected = { openPage(it, null, null) },
                        onSearchHit = { resultPageId, itemId -> openPage(resultPageId, null, itemId) },
                    )
                } else {
                    CompactSettingsPage(
                        page = page,
                        host = host,
                        listState = pageListStates.getValue(page.id),
                        highlightedItemId = highlightedItemId,
                        onBack = closePage,
                        onNavigate = { openPage(it, page.id, null) },
                        onSettingValueChanged = onSettingValueChanged,
                    )
                }
            }
            PredictiveNavigationContent(
                targetState = selectedPageId,
                previousState = previousPageId,
                backEnabled = pageStack.isNotEmpty(),
                onBack = closePage,
                modifier = Modifier.fillMaxSize(),
            ) { pageId ->
                stateHolder.SaveableStateProvider(pageId?.name ?: "settingsRoot") {
                    Box(Modifier.fillMaxSize().background(ground)) {
                        if (pageId != null && host.platform == SettingsPlatform.IOS) {
                            SwipeBackLayout(enabled = true, onBack = closePage, previousContent = { compactContent(previousPageId) }) {
                                compactContent(pageId)
                            }
                        } else {
                            compactContent(pageId)
                        }
                    }
                }
            }
        }
    }
}

/** True once the large title has scrolled under the top bar. */
@Composable
private fun rememberTitleCollapsed(listState: LazyListState): Boolean {
    val threshold = with(LocalDensity.current) { 56.dp.roundToPx() }
    val collapsed by remember(listState, threshold) {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > threshold }
    }
    return collapsed
}

/** Phones (and narrow desktop windows): the settings home with search and the grouped pages. */
@Composable
private fun SettingsHome(
    registry: SettingsUiRegistry,
    host: SharedSettingsHost,
    listState: LazyListState,
    onPageSelected: (SettingsPageId) -> Unit,
    onSearchHit: (SettingsPageId, String?) -> Unit,
) {
    val desktop = host.platform == SettingsPlatform.DESKTOP
    val title = host.title ?: stringResource(SharedRes.string.settings)
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(registry, query) { TurtleSettingsNavigation.search(registry, query) }
    Column(Modifier.fillMaxSize()) {
        ShellSettingsTopBar(
            title = title,
            titleVisible = rememberTitleCollapsed(listState),
            onBack = if (desktop) null else host.onClose,
            onClose = if (desktop) host.onClose else null,
            insetStatusBar = !desktop,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 32.dp + host.bottomContentPadding),
        ) {
            item(key = "title", contentType = "title") { ShellSettingsLargeTitle(title) }
            item(key = "search", contentType = "search") {
                ShellSettingsSearchField(query, { query = it }, Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp))
            }
            if (query.isBlank()) {
                settingsRootGroups(registry, host, selectedPageId = null, compact = false, onPageSelected = onPageSelected)
            } else {
                settingsSearchResults(hits, query, onSearchHit)
            }
            item(key = "bottom", contentType = "spacer") { Spacer(Modifier.windowInsetsPadding(WindowInsets.navigationBars)) }
        }
    }
}

/** Wide windows: the settings title, search and every page in a sidebar beside the detail pane. */
@Composable
private fun SettingsSidebar(
    registry: SettingsUiRegistry,
    host: SharedSettingsHost,
    selectedPageId: SettingsPageId?,
    onPageSelected: (SettingsPageId) -> Unit,
    onSearchHit: (SettingsPageId, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Shell.colors
    var query by rememberSaveable { mutableStateOf("") }
    val hits = remember(registry, query) { TurtleSettingsNavigation.search(registry, query) }
    val listState = rememberLazyListState()
    Column(modifier) {
        Text(
            host.title ?: stringResource(SharedRes.string.settings),
            style = Shell.type.display.copy(fontSize = Shell.type.display.fontSize * 0.85f),
            color = c.ink,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp).semantics { heading() },
        )
        ShellSettingsSearchField(query, { query = it }, Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp))
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp + host.bottomContentPadding)) {
            if (query.isBlank()) {
                settingsRootGroups(registry, host, selectedPageId, compact = true, onPageSelected = onPageSelected)
            } else {
                settingsSearchResults(hits, query, onSearchHit)
            }
        }
    }
}

/** The settings home's grouped pages: "01 / INTERFACE" labels over rows with icon tiles. */
private fun LazyListScope.settingsRootGroups(
    registry: SettingsUiRegistry,
    host: SharedSettingsHost,
    selectedPageId: SettingsPageId?,
    compact: Boolean,
    onPageSelected: (SettingsPageId) -> Unit,
) {
    registry.rootGroups.filter { group -> group.pages.any { registry[it] != null } }.forEachIndexed { groupIndex, group ->
        item(key = "root:${group.title}:$groupIndex", contentType = "header") {
            ShellSettingsSectionLabel(groupIndex, group.title, first = groupIndex == 0)
        }
        val pages = group.pages.mapNotNull { registry[it] }
        itemsIndexed(pages, key = { _, page -> "page:${page.id.name}" }, contentType = { _, _ -> "page" }) { index, page ->
            SettingsPageRow(
                page = page,
                customIcon = host.customPageIcons[page.id],
                selected = page.id == selectedPageId,
                first = index == 0,
                compact = compact,
                onClick = { onPageSelected(page.id) },
            )
        }
    }
}

/** Search hits (pages and settings) with the matched text marked in lime, or Shell's empty state. */
private fun LazyListScope.settingsSearchResults(
    hits: List<TurtleSettingsSearchHit>,
    query: String,
    onSearchHit: (SettingsPageId, String?) -> Unit,
) {
    if (hits.isEmpty()) {
        item(key = "no-results", contentType = "empty") { SettingsNoResults(query) }
        return
    }
    item(key = "results-label", contentType = "header") {
        IndexLabel(stringResource(Res.string.shell_set_results, hits.size), Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
    }
    itemsIndexed(hits, key = { _, hit -> "hit:${hit.page.id.name}:${hit.item?.id}" }, contentType = { _, _ -> "hit" }) { index, hit ->
        SettingsSearchHitRow(hit, query, first = index == 0) { onSearchHit(hit.page.id, hit.item?.id) }
    }
}

@Composable
private fun SettingsNoResults(query: String) {
    val c = Shell.colors
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Glyph(Glyphs.Search, c.ink2, 32.dp)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(SharedRes.string.settings_search_no_results), style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.8f), color = c.ink)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(SharedRes.string.settings_search_no_results_desc, query), style = Shell.type.bodySmall, color = c.ink2)
    }
}

/** A numbered mono section label ("02 / PLAYBACK"), Shell's index language. */
@Composable
internal fun ShellSettingsSectionLabel(index: Int, title: String, first: Boolean, description: String? = null, enabled: Boolean = true) {
    val c = Shell.colors
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = if (first) 8.dp else 28.dp, bottom = 6.dp)
            .semantics { heading() },
    ) {
        if (title.isNotBlank()) {
            IndexLabel("${(index + 1).toString().padStart(2, '0')} / $title", color = if (enabled) c.index else c.index.copy(alpha = 0.5f))
        }
        if (!description.isNullOrBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(description, style = Shell.type.bodySmall, color = c.ink2.copy(alpha = if (enabled) 1f else 0.6f))
        }
    }
}

/** Hover tint for list rows on desktop (transparent on touch screens). */
@Composable
internal fun shellRowBackground(hovered: Boolean, highlighted: Boolean = false, selected: Boolean = false, colors: ShellColors = Shell.colors): Color {
    val bg by animateColorAsState(
        when {
            highlighted -> colors.accentSoft
            selected -> colors.ink.copy(alpha = 0.08f)
            hovered -> colors.ink.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        ShellMotion.emphasized(),
    )
    return bg
}

/** A hairline between rows, inset to the text column. */
@Composable
internal fun ShellRowDivider(start: Dp = 20.dp) {
    Box(Modifier.fillMaxWidth().padding(start = start, end = 20.dp).height(1.dp).background(Shell.colors.hairlineSoft))
}

/** A settings page on the home list or the sidebar: icon tile, title, summary, chevron. */
@Composable
private fun SettingsPageRow(
    page: SettingsPageRegistration,
    customIcon: (@Composable (Color) -> Unit)?,
    selected: Boolean,
    first: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg = shellRowBackground(hovered, selected = selected)
    Column(Modifier.fillMaxWidth()) {
        if (!first && !compact) ShellRowDivider(start = 76.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compact) 1.dp else 0.dp)
                .heightIn(min = if (compact) 56.dp else 68.dp).clip(RoundedCornerShape(10.dp)).background(bg).hoverable(source)
                .shellPressable(onClick, role = if (compact) Role.Tab else Role.Button, label = page.title, focusShape = RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShellSettingsIconTile(page.icon, customIcon, selected = selected, size = if (compact) 36.dp else 40.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    page.title,
                    style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold),
                    color = if (selected) c.accent else c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                page.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (!compact) {
                Spacer(Modifier.width(8.dp))
                Glyph(Glyphs.ChevronRight, c.ink2, 18.dp)
            }
        }
    }
}

@Composable
private fun SettingsSearchHitRow(hit: TurtleSettingsSearchHit, query: String, first: Boolean, onClick: () -> Unit) {
    val c = Shell.colors
    val (source, hovered) = rememberHover()
    val bg = shellRowBackground(hovered)
    val title = hit.item?.title ?: hit.page.title
    val supporting = hit.context ?: if (hit.item != null) hit.page.title else hit.page.subtitle
    Column(Modifier.fillMaxWidth()) {
        if (!first) ShellRowDivider(start = 76.dp)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 64.dp).clip(RoundedCornerShape(10.dp)).background(bg)
                .hoverable(source).shellPressable(onClick, label = title, focusShape = RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ShellSettingsIconTile(hit.page.icon, null, size = 36.dp)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(markMatches(title, query, c.accent), style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                supporting?.takeIf { it.isNotBlank() }?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(markMatches(it, query, c.accent), style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (hit.item != null && supporting != hit.page.title) {
                    Spacer(Modifier.height(4.dp))
                    IndexLabel(hit.page.title)
                }
            }
            Spacer(Modifier.width(8.dp))
            Glyph(Glyphs.ChevronRight, c.ink2, 18.dp)
        }
    }
}

private fun markMatches(text: String, query: String, accent: Color): AnnotatedString {
    val match = query.trim()
    if (match.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var index = 0
        while (index < text.length) {
            val found = text.indexOf(match, startIndex = index, ignoreCase = true)
            if (found < 0) {
                append(text.substring(index))
                break
            }
            append(text.substring(index, found))
            withStyle(SpanStyle(color = accent, fontWeight = FontWeight.Bold)) { append(text.substring(found, found + match.length)) }
            index = found + match.length
        }
    }
}

/** A settings page on phones: back, a large title that collapses into the bar, the page's rows. */
@Composable
private fun CompactSettingsPage(
    page: SettingsPageRegistration,
    host: SharedSettingsHost,
    listState: LazyListState,
    highlightedItemId: String?,
    onBack: () -> Unit,
    onNavigate: (SettingsPageId) -> Unit,
    onSettingValueChanged: () -> Unit,
) {
    val desktop = host.platform == SettingsPlatform.DESKTOP
    val collapsed = rememberTitleCollapsed(listState)
    // Lyrics pins upstream's live preview at the top, so its title stays in the bar.
    val largeTitle = page.id != SettingsPageId.LYRICS
    val titleHeader: LazyListScope.() -> Unit = {
        item(key = "title", contentType = "title") { ShellSettingsLargeTitle(page.title, page.subtitle) }
    }
    Column(Modifier.fillMaxSize()) {
        ShellSettingsTopBar(
            title = page.title,
            titleVisible = page.custom || !largeTitle || collapsed,
            onBack = onBack,
            onClose = if (desktop) host.onClose else null,
            insetStatusBar = !desktop,
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (page.custom) {
                CustomSettingsPage(page, host, onBack, onNavigate)
            } else {
                ShellSettingsPageList(
                    page = page,
                    listState = listState,
                    host = host,
                    highlightedItemId = highlightedItemId,
                    onNavigate = onNavigate,
                    onSettingValueChanged = onSettingValueChanged,
                    contentPadding = PaddingValues(bottom = 32.dp + host.bottomContentPadding),
                    modifier = Modifier.widthIn(max = ShellSettingsMaxWidth),
                    header = titleHeader.takeIf { largeTitle },
                )
            }
        }
    }
}

/**
 * A page upstream draws itself (Storage, Backup, Updates, About, MetroEq...): its own content under
 * Shell's header, told there is a top bar already, with upstream's bottom padding.
 */
@Composable
private fun CustomSettingsPage(
    page: SettingsPageRegistration,
    host: SharedSettingsHost,
    onBack: () -> Unit,
    onNavigate: (SettingsPageId) -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalSettingsPageBottomPadding provides host.bottomContentPadding,
            LocalSettingsPageHasTopBar provides true,
        ) {
            host.pageContent(page.id, onBack, onNavigate)
        }
    }
}

/** Lyrics settings keep upstream's live preview pinned above the list (not on TV, where it scrolls). */
@Composable
internal fun ShellLyricsPreviewFrame(host: SharedSettingsHost, pinned: Boolean, content: @Composable (previewHeight: Dp) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewHeight = minOf(300.dp, maxHeight * 0.4f)
        Column(Modifier.fillMaxSize()) {
            if (pinned) {
                host.lyricsPreview(Modifier.fillMaxWidth().height(previewHeight).padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)))
                Spacer(Modifier.height(12.dp))
            }
            Box(Modifier.weight(1f).fillMaxWidth()) { content(previewHeight) }
        }
    }
}

/** True when the lyrics preview is pinned (everywhere but TV). */
@Composable
internal fun lyricsPreviewPinned(): Boolean = !LocalIsTv.current
