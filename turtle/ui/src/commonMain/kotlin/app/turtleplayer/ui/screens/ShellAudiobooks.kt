package app.turtleplayer.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.turtleplayer.bridge.AudiobookCategory
import app.turtleplayer.bridge.BookProgress
import app.turtleplayer.bridge.SpokenBook
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.ui.components.Glyphs
import app.turtleplayer.ui.components.HoverPlayButton
import app.turtleplayer.ui.components.IndexLabel
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.components.ShellChip
import app.turtleplayer.ui.components.cardHover
import app.turtleplayer.ui.components.rememberHover
import app.turtleplayer.ui.components.shellPressable
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_more
import app.turtleplayer.ui.generated.resources.shell_sp_book_failed
import app.turtleplayer.ui.generated.resources.shell_sp_book_playlist
import app.turtleplayer.ui.generated.resources.shell_sp_books_empty
import app.turtleplayer.ui.generated.resources.shell_sp_books_label
import app.turtleplayer.ui.generated.resources.shell_sp_books_tagline
import app.turtleplayer.ui.generated.resources.shell_sp_cat_biography
import app.turtleplayer.ui.generated.resources.shell_sp_cat_classics
import app.turtleplayer.ui.generated.resources.shell_sp_cat_fiction
import app.turtleplayer.ui.generated.resources.shell_sp_cat_history
import app.turtleplayer.ui.generated.resources.shell_sp_cat_kids
import app.turtleplayer.ui.generated.resources.shell_sp_cat_mystery
import app.turtleplayer.ui.generated.resources.shell_sp_cat_popular
import app.turtleplayer.ui.generated.resources.shell_sp_cat_scifi
import app.turtleplayer.ui.generated.resources.shell_sp_cat_self_help
import app.turtleplayer.ui.generated.resources.shell_sp_continue
import app.turtleplayer.ui.generated.resources.shell_sp_forget
import app.turtleplayer.ui.generated.resources.shell_sp_left
import app.turtleplayer.ui.generated.resources.shell_sp_loading
import app.turtleplayer.ui.generated.resources.shell_sp_meta_pair
import app.turtleplayer.ui.generated.resources.shell_sp_part_of
import app.turtleplayer.ui.generated.resources.shell_sp_retry
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellMotion
import app.turtleplayer.ui.theme.ShellPlatform
import app.turtleplayer.ui.theme.ShellShapes
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val BOOK_CATEGORIES: List<Pair<AudiobookCategory, StringResource>> = listOf(
    AudiobookCategory.POPULAR to Res.string.shell_sp_cat_popular,
    AudiobookCategory.CLASSICS to Res.string.shell_sp_cat_classics,
    AudiobookCategory.FICTION to Res.string.shell_sp_cat_fiction,
    AudiobookCategory.MYSTERY to Res.string.shell_sp_cat_mystery,
    AudiobookCategory.SCIFI to Res.string.shell_sp_cat_scifi,
    AudiobookCategory.SELF_HELP to Res.string.shell_sp_cat_self_help,
    AudiobookCategory.BIOGRAPHY to Res.string.shell_sp_cat_biography,
    AudiobookCategory.HISTORY to Res.string.shell_sp_cat_history,
    AudiobookCategory.KIDS to Res.string.shell_sp_cat_kids,
)

private const val BOOK_NOTICE_MS = 3200L

/** The audiobook catalog as Home shows it, kept for the session (category and loaded shelves). */
internal object ShellAudiobooksHome {
    var category by mutableStateOf(AudiobookCategory.POPULAR)
    val results = mutableStateMapOf<AudiobookCategory, List<SpokenBook>>()
}

/**
 * Home's Audiobooks filter: Continue listening (books in progress, resumed at the saved chapter and
 * second), category chips, and a grid of full readings found on YouTube. Long uploads play as one
 * item; chapter playlists play as a queue. A long press opens upstream's menu (single uploads) or the
 * chapter list (playlists).
 */
@Composable
internal fun ShellAudiobooksSection(slot: HomeSlot, modifier: Modifier = Modifier) {
    val c = Shell.colors
    val playback = LocalPlaybackController.current
    LaunchedEffect(playback) {
        ShellSpoken.track(playback)
        TurtleBridge.spoken.load()
    }
    val inProgress by TurtleBridge.spoken.books.collectAsState()
    val category = ShellAudiobooksHome.category
    val shown = ShellAudiobooksHome.results[category]
    var loading by remember { mutableStateOf(false) }
    var retries by remember { mutableIntStateOf(0) }
    LaunchedEffect(category, retries) {
        if (ShellAudiobooksHome.results[category] != null && retries == 0) return@LaunchedEffect
        loading = true
        try {
            ShellAudiobooksHome.results[category] = TurtleBridge.spoken.audiobooks(category, refresh = retries > 0)
        } finally {
            loading = false
        }
    }
    val failedText = stringResource(Res.string.shell_sp_book_failed)
    var notice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(BOOK_NOTICE_MS)
            notice = null
        }
    }
    val onFailed = { notice = failedText }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val desktop = ShellPlatform.desktop
        val columns = when {
            desktop -> (maxWidth / 200.dp).toInt().coerceIn(3, 7)
            maxWidth > 600.dp -> 4
            else -> 2
        }
        Column {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                IndexLabel(stringResource(Res.string.shell_sp_books_label))
                Text(stringResource(Res.string.shell_sp_books_tagline), style = Shell.type.bodySmall, color = c.ink2)
            }

            if (inProgress.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SpokenSectionTitle(stringResource(Res.string.shell_sp_continue))
                Spacer(Modifier.height(12.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = if (desktop) 6.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(if (desktop) 4.dp else 12.dp)) {
                    items(inProgress, key = { it.bookId }) { p -> BookProgressCard(p, playback, onFailed, Modifier.width(300.dp)) }
                }
            }

            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BOOK_CATEGORIES.forEach { (cat, res) -> ShellChip(stringResource(res), cat == category, { ShellAudiobooksHome.category = cat; retries = 0 }) }
            }
            Spacer(Modifier.height(16.dp))

            notice?.let {
                Text(it, style = Shell.type.label, color = c.danger, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }

            when {
                shown == null || (loading && shown.isEmpty()) -> BookGridPlaceholder(columns)
                shown.isEmpty() -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(Res.string.shell_sp_books_empty), style = Shell.type.body, color = c.ink2)
                    ShellChip(stringResource(Res.string.shell_sp_retry), false, { retries++ })
                }
                else -> Column(Modifier.padding(horizontal = if (desktop) 6.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(if (desktop) 4.dp else 18.dp)) {
                    val progress = inProgress.associateBy { it.bookId }
                    shown.chunked(columns).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(if (desktop) 2.dp else 14.dp)) {
                            row.forEach { book -> BookCard(book, progress[book.id], slot, playback, onFailed, Modifier.weight(1f)) }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** A book in the grid: square cover, title, author and length (or chapter count); lime line once started. */
@Composable
private fun BookCard(book: SpokenBook, progress: BookProgress?, slot: HomeSlot, playback: PlaybackController, onFailed: () -> Unit, modifier: Modifier) {
    val c = Shell.colors
    val (hover, hovered) = rememberHover()
    val opening = ShellSpoken.opening == book.id
    val play = { ShellSpoken.playBook(playback, book, onFailed) }
    val video = book.video
    val playlistId = book.playlistId
    val longClick: (() -> Unit)? = when {
        video != null -> slot.onItemLongClick?.let { f -> { f(video) } }
        playlistId != null -> { { slot.onItemClick(PlaylistItem(id = playlistId, title = book.title, thumbnail = book.thumbnail, author = book.author)) } }
        else -> null
    }
    Column(
        modifier.hoverable(hover)
            .shellPressable(
                play, label = book.title, onLongClick = longClick,
                onLongClickLabel = if (video != null) stringResource(Res.string.shell_more) else null,
            )
            .cardHover(hovered),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
            ShellArt(book.thumbnail, Modifier.fillMaxSize(), shape = ShellShapes.Art)
            if (opening) {
                Box(Modifier.fillMaxSize().clip(ShellShapes.Art).background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = c.accent, strokeWidth = 2.5.dp)
                }
            } else {
                HoverPlayButton(hovered, 44.dp, play, Modifier.align(Alignment.BottomEnd).padding(8.dp))
            }
        }
        Text(book.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
        val length = book.durationSec?.let { spokenLength(it) }
            ?: book.partsText?.takeIf { it.isNotBlank() }
            ?: if (book.playlistId != null) stringResource(Res.string.shell_sp_book_playlist) else null
        val author = book.author?.takeIf { it.isNotBlank() }
        val meta = when {
            author != null && length != null -> stringResource(Res.string.shell_sp_meta_pair, author, length)
            else -> author ?: length
        }
        if (meta != null) Text(meta, style = Shell.type.bodySmall, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (progress != null && progress.fraction > 0f) SpokenProgress(progress.fraction)
    }
}

/** A book in progress: where the listener is (part, time left) and a remove button. */
@Composable
private fun BookProgressCard(p: BookProgress, playback: PlaybackController, onFailed: () -> Unit, modifier: Modifier) {
    val c = Shell.colors
    val (hover, hovered) = rememberHover()
    val bg by animateColorAsState(if (hovered) c.surface3 else c.surface2, ShellMotion.emphasized())
    val opening = ShellSpoken.opening == p.bookId
    val resume = { ShellSpoken.resume(playback, p, onFailed) }
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(bg).hoverable(hover).shellPressable(resume, label = p.title).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ShellArt(p.thumbnail, Modifier.size(72.dp), shape = ShellShapes.Art)
            if (opening) CircularProgressIndicator(Modifier.size(24.dp), color = c.accent, strokeWidth = 2.5.dp)
            else HoverPlayButton(hovered, 32.dp, resume, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.title, style = Shell.type.label.copy(fontWeight = FontWeight.SemiBold), color = c.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val where = when {
                p.chapterCount > 1 -> stringResource(Res.string.shell_sp_part_of, p.chapterIndex + 1, p.chapterCount)
                p.durationMs > 0 -> stringResource(Res.string.shell_sp_left, spokenLength((p.durationMs - p.positionMs).coerceAtLeast(0L) / 1000))
                else -> p.author
            }
            if (where != null) Text(where, style = Shell.type.bodySmall, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            SpokenProgress(p.fraction, Modifier.padding(top = 4.dp))
        }
        GlyphButton(Glyphs.Close, stringResource(Res.string.shell_sp_forget), { TurtleBridge.spoken.forget(p.bookId) }, c.ink2, size = 40.dp, iconSize = 14.dp)
    }
}

@Composable
private fun BookGridPlaceholder(columns: Int) {
    val c = Shell.colors
    val pulse = rememberShellPulse()
    val label = stringResource(Res.string.shell_sp_loading)
    Column(
        Modifier.padding(horizontal = 16.dp).semantics { contentDescription = label }.graphicsLayer { alpha = pulse.value },
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        repeat(2) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(columns) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(ShellShapes.Art).background(c.surface2))
                        Box(Modifier.fillMaxWidth(0.8f).height(12.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
                        Box(Modifier.fillMaxWidth(0.5f).height(10.dp).clip(RoundedCornerShape(3.dp)).background(c.surface2))
                    }
                }
            }
        }
    }
}
