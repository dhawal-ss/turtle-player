package app.turtleplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.turtleplayer.bridge.TurtleArtistPicks
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyph
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_picks_body
import app.turtleplayer.ui.generated.resources.shell_picks_card_body
import app.turtleplayer.ui.generated.resources.shell_picks_card_pick
import app.turtleplayer.ui.generated.resources.shell_picks_card_title
import app.turtleplayer.ui.generated.resources.shell_picks_clear
import app.turtleplayer.ui.generated.resources.shell_picks_clear_search
import app.turtleplayer.ui.generated.resources.shell_picks_done
import app.turtleplayer.ui.generated.resources.shell_picks_none_found
import app.turtleplayer.ui.generated.resources.shell_picks_paused
import app.turtleplayer.ui.generated.resources.shell_picks_remove
import app.turtleplayer.ui.generated.resources.shell_picks_results
import app.turtleplayer.ui.generated.resources.shell_picks_saving
import app.turtleplayer.ui.generated.resources.shell_picks_search
import app.turtleplayer.ui.generated.resources.shell_picks_selected
import app.turtleplayer.ui.generated.resources.shell_picks_skip
import app.turtleplayer.ui.generated.resources.shell_picks_suggested
import app.turtleplayer.ui.generated.resources.shell_picks_title
import app.turtleplayer.ui.generated.resources.shell_play_infinity
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellTheme
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Skippable artist picks for a young profile: a few artists Made for you starts from until the
 * listener's own history takes over. Opened from Home's card (sparse profiles) and from Turtle
 * settings. Saving runs in its own scope, so closing the sheet never cancels it. File import is
 * deliberately not offered here until imports are atomic and previewable.
 */
internal object ShellArtistPicks {
    private val scope = MainScope()

    var sheetOpen by mutableStateOf(false)
        private set

    /** True while picks are being learned and Home rebuilt (about two YouTube calls per artist). */
    var saving by mutableStateOf(false)
        private set

    /** Artists offered without a search: those on Home when the sheet opened. */
    var suggestions by mutableStateOf<List<ArtistItem>>(emptyList())
        private set

    internal val hosts = mutableStateListOf<Any>()

    /** Opens the sheet, suggesting the artists among [onScreen] (Home's items) and the artists of its songs. */
    fun open(onScreen: List<YTItem> = emptyList()) {
        suggestions = suggestionsOf(onScreen)
        sheetOpen = true
    }

    fun close() {
        sheetOpen = false
    }

    fun save(picks: List<ArtistItem>) {
        close()
        saving = true
        scope.launch {
            try {
                TurtleBridge.saveArtistPicks(picks)
            } finally {
                saving = false
            }
        }
    }

    fun skip() {
        close()
        scope.launch { TurtleBridge.skipArtistPicks() }
    }

    fun clear() {
        scope.launch { TurtleBridge.clearArtistPicks() }
    }

    private fun suggestionsOf(items: List<YTItem>): List<ArtistItem> {
        val artists = items.filterIsInstance<ArtistItem>()
        val fromSongs =
            items.filterIsInstance<SongItem>().flatMap { song ->
                song.artists.mapNotNull { a -> a.id?.takeIf { it.startsWith("UC") }?.let { ArtistItem(id = it, title = a.name, thumbnail = a.thumbnail) } }
            }
        return (artists + fromSongs).filter { it.id.isNotBlank() && it.title.isNotBlank() }.distinctBy { it.id }.take(MAX_SUGGESTIONS)
    }

    private const val MAX_SUGGESTIONS = 12
}

/** Shows the picks sheet when it is open; put one wherever it can be opened (Home, Turtle settings). */
@Composable
internal fun ArtistPicksSheetHost() {
    val token = remember { Any() }
    DisposableEffect(token) {
        ShellArtistPicks.hosts.add(token)
        onDispose { ShellArtistPicks.hosts.remove(token) }
    }
    if (ShellArtistPicks.sheetOpen && ShellArtistPicks.hosts.firstOrNull() === token) ArtistPicksSheet()
}

/**
 * Home's card for a young profile with no mixes yet, in Made for you's slot: pick artists, skip,
 * or just play Infinity now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtistPicksCard(
    onPick: () -> Unit,
    onSkip: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Shell.colors
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp).fillMaxWidth()
            .clip(shape).background(c.surface1).border(1.dp, c.hairline, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(Res.string.shell_picks_card_title), style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = c.ink)
        if (ShellArtistPicks.saving) {
            Text(stringResource(Res.string.shell_picks_saving), style = Shell.type.bodySmall, color = c.accent)
            return@Column
        }
        Text(stringResource(Res.string.shell_picks_card_body), style = Shell.type.bodySmall, color = c.ink2)
        // Wraps: the three labels are wider than a 360 dp phone's card in one row.
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton(stringResource(Res.string.shell_picks_card_pick), filled = true, onClick = onPick)
            PillButton(stringResource(Res.string.shell_play_infinity), filled = false, onClick = onPlay)
            PillButton(stringResource(Res.string.shell_picks_skip), filled = false, quiet = true, onClick = onSkip)
        }
    }
}

@Composable
private fun PillButton(
    label: String,
    filled: Boolean,
    quiet: Boolean = false,
    onClick: () -> Unit,
) {
    val c = Shell.colors
    // A 48 dp touch target around the 36 dp pill.
    Box(
        Modifier.height(48.dp).shellPressable(onClick, label = label, focusShape = CircleShape).padding(vertical = 6.dp)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(c.accent) else if (quiet) Modifier else Modifier.border(1.dp, c.hairline, CircleShape))
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold),
            color = if (filled) c.onAccent else if (quiet) c.ink2 else c.ink, maxLines = 1,
        )
    }
}

/** The picks sheet: search, suggestions, the picked artists, then Done (or Skip / Clear). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ArtistPicksSheet() = ShellTheme {
    val c = Shell.colors
    val state = TurtleBridge.artistPicks
    LaunchedEffect(Unit) { TurtleBridge.loadArtistPicks() }
    // Prefilled with the saved picks (Settings opens the sheet to edit them).
    val chosen = remember { mutableStateListOf<ArtistItem>() }
    var edited by remember { mutableStateOf(false) }
    val saved = state?.picked
    LaunchedEffect(saved) {
        if (edited || saved == null) return@LaunchedEffect
        chosen.clear()
        saved.forEach { chosen.add(ArtistItem(id = it.id, title = it.title, thumbnail = it.thumbnail)) }
    }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ArtistItem>?>(null) }
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = null
            return@LaunchedEffect
        }
        // Typed, not yet settled: a search per pause, not per letter.
        delay(SEARCH_DEBOUNCE_MS)
        results = TurtleBridge.searchArtists(query)
    }
    val max = TurtleArtistPicks.MAX_PICKS
    fun toggle(artist: ArtistItem) {
        edited = true
        val at = chosen.indexOfFirst { it.id == artist.id }
        if (at >= 0) chosen.removeAt(at) else if (chosen.size < max) chosen.add(artist)
    }
    val close = ShellArtistPicks::close
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize()) {
            ShellBottomSheet(visible = true, onDismiss = close) {
                Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).verticalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(c.accent), contentAlignment = Alignment.Center) { Glyph(Glyphs.Artist, c.onAccent, 24.dp) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(Res.string.shell_picks_title), style = Shell.type.headline.copy(fontSize = Shell.type.headline.fontSize * 0.85f), color = c.ink)
                            Text(stringResource(Res.string.shell_picks_body), style = Shell.type.bodySmall, color = c.ink2)
                        }
                    }
                    if (state?.learningPaused == true) {
                        Text(stringResource(Res.string.shell_picks_paused), style = Shell.type.bodySmall, color = c.accent, modifier = Modifier.padding(top = 10.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    SearchField(query, { query = it })
                    if (chosen.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        IndexLabel(stringResource(Res.string.shell_picks_selected, chosen.size, max))
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            chosen.forEach { a -> ArtistChip(a, on = true, removeLabel = stringResource(Res.string.shell_picks_remove, a.title)) { toggle(a) } }
                        }
                    }
                    val found = results
                    val offered = if (found == null) ShellArtistPicks.suggestions else found
                    Spacer(Modifier.height(14.dp))
                    if (found != null || offered.isNotEmpty()) {
                        IndexLabel(stringResource(if (found == null) Res.string.shell_picks_suggested else Res.string.shell_picks_results))
                        Spacer(Modifier.height(8.dp))
                    }
                    if (found != null && found.isEmpty()) {
                        Text(stringResource(Res.string.shell_picks_none_found), style = Shell.type.bodySmall, color = c.ink2)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        offered.filter { o -> chosen.none { it.id == o.id } }.forEach { a -> ArtistChip(a, on = false) { toggle(a) } }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val hadPicks = state?.picked?.isNotEmpty() == true
                        val quiet = if (hadPicks) Res.string.shell_picks_clear else Res.string.shell_picks_skip
                        Box(
                            Modifier.height(52.dp).clip(CircleShape).shellPressable({
                                if (hadPicks) {
                                    chosen.clear()
                                    ShellArtistPicks.clear()
                                    close()
                                } else {
                                    ShellArtistPicks.skip()
                                }
                            }, focusShape = CircleShape).padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) { Text(stringResource(quiet), style = Shell.type.body, color = c.ink2) }
                        val done = stringResource(Res.string.shell_picks_done)
                        Box(
                            Modifier.weight(1f).height(52.dp)
                                .shellPressable({ ShellArtistPicks.save(chosen.toList()) }, label = done, focusShape = CircleShape)
                                .drawBehind { drawRoundRect(c.accent, cornerRadius = CornerRadius(size.height / 2)) },
                            contentAlignment = Alignment.Center,
                        ) { Text(done, style = Shell.type.body.copy(fontWeight = FontWeight.Bold), color = c.onAccent) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    val c = Shell.colors
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(8.dp)).background(c.surface2).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Glyph(Glyphs.Search, c.ink2, 16.dp)
        Spacer(Modifier.width(10.dp))
        val hint = stringResource(Res.string.shell_picks_search)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(hint, style = Shell.type.label, color = c.ink2, maxLines = 1)
            BasicTextField(
                value = query, onValueChange = onQueryChange, singleLine = true,
                textStyle = Shell.type.label.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                // Screen readers name the field even once the hint is gone.
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = hint },
            )
        }
        if (query.isNotEmpty()) GlyphButton(Glyphs.Close, stringResource(Res.string.shell_picks_clear_search), { onQueryChange("") }, c.ink2, size = 48.dp, iconSize = 14.dp)
    }
}

@Composable
private fun ArtistChip(
    artist: ArtistItem,
    on: Boolean,
    removeLabel: String? = null,
    onClick: () -> Unit,
) {
    val c = Shell.colors
    // A 48 dp touch target around the 40 dp chip (chip rows add no spacing of their own).
    Row(
        Modifier.height(48.dp).shellPressable(onClick, label = removeLabel ?: artist.title, focusShape = CircleShape).padding(vertical = 4.dp)
            .clip(CircleShape).background(if (on) c.accent else c.surface2)
            .border(1.dp, if (on) c.accent else c.hairline, CircleShape)
            .padding(start = 4.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShellArt(artist.thumbnail, Modifier.size(32.dp), shape = CircleShape)
        Spacer(Modifier.width(8.dp))
        Text(
            artist.title, style = Shell.type.body.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
            color = if (on) c.onAccent else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (on) {
            Spacer(Modifier.width(6.dp))
            Glyph(Glyphs.Close, c.onAccent, 12.dp)
        }
    }
}

private const val SEARCH_DEBOUNCE_MS = 350L
