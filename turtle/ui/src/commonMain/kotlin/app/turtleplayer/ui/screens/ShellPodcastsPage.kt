package app.turtleplayer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.ui.components.CheckGlyph
import app.turtleplayer.ui.components.Equalizer
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.PlayGlyph
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_sp_add_queue
import app.turtleplayer.ui.generated.resources.shell_sp_back
import app.turtleplayer.ui.generated.resources.shell_sp_download
import app.turtleplayer.ui.generated.resources.shell_sp_episodes
import app.turtleplayer.ui.generated.resources.shell_sp_find
import app.turtleplayer.ui.generated.resources.shell_sp_find_close
import app.turtleplayer.ui.generated.resources.shell_sp_follow
import app.turtleplayer.ui.generated.resources.shell_sp_following
import app.turtleplayer.ui.generated.resources.shell_sp_kind_podcast
import app.turtleplayer.ui.generated.resources.shell_sp_load_failed
import app.turtleplayer.ui.generated.resources.shell_sp_meta_pair
import app.turtleplayer.ui.generated.resources.shell_sp_more
import app.turtleplayer.ui.generated.resources.shell_sp_no_matches
import app.turtleplayer.ui.generated.resources.shell_sp_play_latest
import app.turtleplayer.ui.generated.resources.shell_sp_play_next
import app.turtleplayer.ui.generated.resources.shell_sp_played
import app.turtleplayer.ui.generated.resources.shell_sp_retry
import app.turtleplayer.ui.generated.resources.shell_sp_save_episode
import app.turtleplayer.ui.generated.resources.shell_sp_saved_episode
import app.turtleplayer.ui.generated.resources.shell_sp_share
import app.turtleplayer.ui.generated.resources.shell_sp_show_less
import app.turtleplayer.ui.generated.resources.shell_sp_show_more
import app.turtleplayer.ui.theme.LocalShellGround
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.EpisodeItem
import com.metrolist.shared.model.PodcastPage
import com.metrolist.shared.turtle.PodcastPageSlot
import com.metrolist.shared.ui.components.episodeProgressFraction
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.utils.desktopArrowScroll
import org.jetbrains.compose.resources.stringResource

private val ShowContentMaxWidth = 1400.dp

/**
 * Shell's podcast show page, in place of upstream's `PodcastContent` (one hook line, turtle/HOOKS.md):
 * a tinted header (cover, title, host, description), Follow, find, More and a lime Play latest, then
 * the episodes with progress, save and an episode menu. Every action is upstream's callback.
 */
@Composable
fun ShellPodcastPage(slot: PodcastPageSlot) = ShellTheme {
    val c = Shell.colors
    val ground = LocalShellGround.current ?: c.ground
    val page = slot.page
    val listState = rememberLazyListState()
    val showId = page?.podcast?.id
    var searchOpen by remember(showId) { mutableStateOf(false) }
    var query by remember(showId) { mutableStateOf("") }
    val closeSearch = {
        searchOpen = false
        query = ""
    }
    NavigationBackHandler(enabled = searchOpen, onBack = closeSearch)
    val episodes = remember(page?.episodes, query) {
        val term = query.trim()
        page?.episodes.orEmpty().let { all -> if (term.isEmpty()) all else all.filter { it.title.contains(term, ignoreCase = true) } }
    }

    // Paging, as upstream: near the end of the list, and through every page while searching.
    LaunchedEffect(listState, page?.continuation, page?.episodes?.size, slot.isLoading, slot.error) {
        val more = slot.onLoadMore
        if (page?.continuation == null || slot.isLoading || slot.error != null || more == null) return@LaunchedEffect
        snapshotFlow {
            val layout = listState.layoutInfo
            (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 5
        }.collect { nearEnd -> if (nearEnd) more() }
    }
    LaunchedEffect(query.isNotBlank(), page?.continuation, slot.isLoading, slot.error) {
        if (query.isNotBlank() && page?.continuation != null && !slot.isLoading && slot.error == null) slot.onLoadMore?.invoke()
    }

    val tint = artworkTint(page?.podcast?.thumbnail, 0.35f, ground)
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val showBar = slot.onBackClick != null
    val top = slot.contentPadding.calculateTopPadding() + statusTop + (if (showBar) 56.dp else 0.dp)
    val itemModifier = Modifier.widthIn(max = ShowContentMaxWidth).fillMaxWidth()

    Box(slot.modifier.fillMaxSize().background(ground)) {
        Box(
            Modifier.fillMaxWidth().height(top + 420.dp)
                .graphicsLayer { translationY = if (listState.firstVisibleItemIndex > 0) -size.height else -listState.firstVisibleItemScrollOffset.toFloat() }
                .drawBehind { drawRect(Brush.verticalGradient(listOf(tint, ground))) },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().desktopArrowScroll(listState),
            contentPadding = PaddingValues(top = top, bottom = slot.contentPadding.calculateBottomPadding() + 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when {
                page == null && slot.isLoading -> item(key = "loading") {
                    Box(itemModifier.padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.5.dp)
                    }
                }

                page == null -> item(key = "failed") {
                    Column(itemModifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(Res.string.shell_sp_load_failed), style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = c.ink)
                        ShellChip(stringResource(Res.string.shell_sp_retry), false, slot.onRetry)
                    }
                }

                else -> {
                    item(key = "header") {
                        Box(itemModifier) {
                            ShowHeader(page, slot, onFind = { if (searchOpen) closeSearch() else searchOpen = true })
                        }
                    }
                    if (searchOpen) {
                        item(key = "find") { Box(itemModifier) { FindEpisodes(query, { query = it }, closeSearch) } }
                    }
                    item(key = "episodes_title") {
                        Text(
                            stringResource(Res.string.shell_sp_episodes),
                            style = Shell.type.sectionTitle, color = c.ink,
                            modifier = itemModifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
                        )
                    }
                    if (episodes.isEmpty() && query.isNotBlank() && (page.continuation == null || slot.onLoadMore == null)) {
                        item(key = "no_matches") {
                            Text(
                                stringResource(Res.string.shell_sp_no_matches, query.trim()), style = Shell.type.body, color = c.ink2,
                                modifier = itemModifier.padding(16.dp),
                            )
                        }
                    }
                    items(episodes, key = { it.id }) { episode ->
                        Box(itemModifier) { EpisodeRow(episode, slot) }
                    }
                    val error = slot.error
                    if (error != null) {
                        item(key = "load_error") {
                            Column(itemModifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(error, style = Shell.type.bodySmall, color = c.danger)
                                ShellChip(stringResource(Res.string.shell_sp_retry), false, slot.onRetry)
                            }
                        }
                    } else if (page.continuation != null && slot.onLoadMore != null && slot.isLoading) {
                        item(key = "load_more") {
                            Box(itemModifier.padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(22.dp), color = c.ink2, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
        val back = slot.onBackClick
        if (back != null) ShowTopBar(back, page?.podcast?.title.orEmpty(), listState, ground, statusTop)
    }
}

/** Back button that gains the show's name once the header scrolls away. */
@Composable
private fun ShowTopBar(onBack: () -> Unit, title: String, listState: LazyListState, ground: Color, statusTop: androidx.compose.ui.unit.Dp) {
    val c = Shell.colors
    val collapsed by remember(listState) { derivedStateOf { listState.firstVisibleItemIndex >= 1 } }
    val bg by animateColorAsState(if (collapsed) ground else ground.copy(alpha = 0f), ShellMotion.emphasized())
    Row(
        Modifier.fillMaxWidth().background(bg).padding(top = statusTop).height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphButton(Glyphs.ArrowLeft, stringResource(Res.string.shell_sp_back), onBack, c.ink, iconSize = 22.dp)
        AnimatedVisibility(collapsed, enter = fadeIn(ShellMotion.emphasized()), exit = fadeOut(ShellMotion.exit())) {
            Text(
                title, style = Shell.type.body.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold), color = c.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp, end = 16.dp),
            )
        }
    }
}

/** Phone: centred cover over the title. Desktop: Spotify's horizontal header. */
@Composable
private fun ShowHeader(page: PodcastPage, slot: PodcastPageSlot, onFind: () -> Unit) {
    val c = Shell.colors
    val desktop = ShellPlatform.desktop
    val show = page.podcast
    val channelId = show.channelId
    val onChannel = slot.onChannelClick
    val author: @Composable () -> Unit = {
        show.author?.takeIf { it.isNotBlank() }?.let { name ->
            val clickable = channelId != null && onChannel != null
            Text(
                name, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (clickable) Modifier.shellPressable({ onChannel?.invoke(channelId.orEmpty()) }, label = name) else Modifier,
            )
        }
    }
    val count = show.episodeCount?.takeIf { it.isNotBlank() }
    val cover: @Composable (Modifier) -> Unit = { m -> ShellArt(show.thumbnail, m.shadow(24.dp, RoundedCornerShape(8.dp)), shape = RoundedCornerShape(8.dp), sizePx = 640) }
    if (desktop) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                cover(Modifier.size(232.dp))
                Spacer(Modifier.width(24.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    IndexLabel(stringResource(Res.string.shell_sp_kind_podcast), color = c.ink)
                    Text(show.title, style = showTitleStyle(show.title, desktop = true), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        author()
                        if (count != null) Text("  •  $count", style = Shell.type.bodySmall, color = c.ink2, maxLines = 1)
                    }
                }
            }
            page.description?.takeIf { it.isNotBlank() }?.let { ShowDescription(it, Modifier.padding(top = 16.dp).widthIn(max = 760.dp)) }
            Spacer(Modifier.height(16.dp))
            ShowActions(page, slot, onFind, desktop = true)
        }
    } else {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) {
                cover(Modifier.size(minOf(maxWidth * 0.6f, 300.dp)))
            }
            Spacer(Modifier.height(20.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(show.title, style = showTitleStyle(show.title, desktop = false), color = c.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                author()
                if (count != null) Text(count, style = Shell.type.bodySmall, color = c.ink2)
                page.description?.takeIf { it.isNotBlank() }?.let { ShowDescription(it, Modifier.padding(top = 4.dp)) }
            }
            ShowActions(page, slot, onFind, desktop = false)
        }
    }
}

@Composable
private fun showTitleStyle(title: String, desktop: Boolean): androidx.compose.ui.text.TextStyle {
    val n = title.length
    val size = if (desktop) {
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

/** The show's description, three lines until tapped. */
@Composable
private fun ShowDescription(text: String, modifier: Modifier = Modifier) {
    val c = Shell.colors
    var expanded by remember(text) { mutableStateOf(false) }
    val label = stringResource(if (expanded) Res.string.shell_sp_show_less else Res.string.shell_sp_show_more)
    Text(
        text, style = Shell.type.bodySmall, color = c.ink2,
        maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
        modifier = modifier.shellPressable({ expanded = !expanded }, label = label),
    )
}

/** Follow, find and More on the left; Play latest on the right (leading on desktop). */
@Composable
private fun ShowActions(page: PodcastPage, slot: PodcastPageSlot, onFind: () -> Unit, desktop: Boolean) {
    val c = Shell.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        if (desktop) {
            PlayLatestButton(slot.onPlay)
            Spacer(Modifier.width(16.dp))
        }
        FollowPill(page.saved, slot.isSaving, slot.onToggleSaved)
        Spacer(Modifier.width(4.dp))
        GlyphButton(Glyphs.Search, stringResource(Res.string.shell_sp_find), onFind, c.ink2)
        GlyphButton(Glyphs.More, stringResource(Res.string.shell_sp_more), slot.onPodcastMenuClick, c.ink2)
        Spacer(Modifier.weight(1f))
        if (!desktop) PlayLatestButton(slot.onPlay)
    }
}

@Composable
private fun PlayLatestButton(onClick: () -> Unit) {
    val c = Shell.colors
    Box(
        Modifier.size(56.dp).clip(CircleShape).background(c.accent)
            .shellPressable(onClick, label = stringResource(Res.string.shell_sp_play_latest), focusShape = CircleShape),
        contentAlignment = Alignment.Center,
    ) { PlayGlyph(c.onAccent, 26.dp) }
}

/** Spotify's Follow: an outlined pill, lime-edged once followed; dimmed while saving. */
@Composable
private fun FollowPill(saved: Boolean, saving: Boolean, onClick: () -> Unit) {
    val c = Shell.colors
    val edge by animateColorAsState(if (saved) c.accent else c.ink2, ShellMotion.emphasized())
    Box(
        Modifier.height(32.dp).graphicsLayer { alpha = if (saving) 0.5f else 1f }.clip(CircleShape).border(1.dp, edge, CircleShape)
            .shellPressable({ if (!saving) onClick() }, focusShape = CircleShape).padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            stringResource(if (saved) Res.string.shell_sp_following else Res.string.shell_sp_follow),
            style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = if (saved) c.accent else c.ink,
        )
    }
}

@Composable
private fun FindEpisodes(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    val c = Shell.colors
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp)).background(c.surface3).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.Search, c.ink2, 16.dp)
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(stringResource(Res.string.shell_sp_find), style = Shell.type.label, color = c.ink2, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onQueryChange, singleLine = true,
                textStyle = Shell.type.label.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        GlyphButton(Glyphs.Close, stringResource(Res.string.shell_sp_find_close), onClose, c.ink2, size = 40.dp, iconSize = 14.dp)
    }
}

/** An episode: art, title, date and length, two lines of description, progress, save and a menu. */
@Composable
private fun EpisodeRow(episode: EpisodeItem, slot: PodcastPageSlot) {
    val c = Shell.colors
    val active = episode.id == slot.currentEpisodeId
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface2 else Color.Transparent, ShellMotion.emphasized())
    var menu by remember { mutableStateOf(false) }
    val desktop = ShellPlatform.desktop
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(6.dp)).background(bg).hoverable(hover)
            .shellPressable({ slot.onEpisodeClick(episode) }, label = episode.title, onLongClick = { menu = true })
            .padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        ShellArt(episode.thumbnail, Modifier.size(if (desktop) 72.dp else 60.dp), shape = ShellShapes.Art)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active && slot.isPlaying) {
                    Equalizer(height = 12.dp)
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    episode.title, style = Shell.type.body.copy(fontWeight = FontWeight.SemiBold), color = if (active) c.accent else c.ink,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            val length = episode.durationText?.takeIf { it.isNotBlank() } ?: episode.duration?.takeIf { it > 0 }?.let { spokenLength(it) }
            val date = episode.publishDateText?.takeIf { it.isNotBlank() }
            val meta = if (date != null && length != null) stringResource(Res.string.shell_sp_meta_pair, date, length) else date ?: length
            if (meta != null) Text(meta, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1)
            episode.description?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val fraction = episodeProgressFraction(episode.id, episode.duration, episode.playbackProgressPercent)
            if (fraction != null && fraction >= 1f) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckGlyph(c.accent, 12.dp)
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(Res.string.shell_sp_played), style = Shell.type.bodySmall, color = c.accent)
                }
            } else if (fraction != null && fraction > 0f) {
                SpokenProgress(fraction, Modifier.padding(top = 4.dp).widthIn(max = 220.dp))
            }
        }
        Spacer(Modifier.width(4.dp))
        val saveLabel = stringResource(if (episode.saved) Res.string.shell_sp_saved_episode else Res.string.shell_sp_save_episode)
        Box(Modifier.size(40.dp).shellPressable({ slot.onToggleEpisodeSaved(episode) }, label = saveLabel, focusShape = CircleShape), contentAlignment = Alignment.Center) {
            Crossfade(episode.saved, animationSpec = tween(ShellMotion.EMPHASIZED_MS, easing = ShellMotion.Emphasized)) { saved ->
                if (saved) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) { CheckGlyph(c.onAccent, 13.dp) }
                } else {
                    Glyph(Glyphs.Add, c.ink2, 24.dp)
                }
            }
        }
        Box {
            GlyphButton(Glyphs.More, stringResource(Res.string.shell_sp_more), { menu = true }, c.ink2, size = 40.dp, iconSize = 20.dp)
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_sp_play_next)) }, onClick = { menu = false; slot.onPlayNext(episode) })
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_sp_add_queue)) }, onClick = { menu = false; slot.onAddToQueue(episode) })
                DropdownMenuItem(text = { Text(saveLabel) }, onClick = { menu = false; slot.onToggleEpisodeSaved(episode) })
                val download = slot.onDownload
                if (download != null) {
                    DropdownMenuItem(text = { Text(stringResource(Res.string.shell_sp_download)) }, onClick = { menu = false; download(episode) })
                }
                DropdownMenuItem(text = { Text(stringResource(Res.string.shell_sp_share)) }, onClick = { menu = false; slot.onShare(episode) })
            }
        }
    }
}
