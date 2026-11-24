package app.turtleplayer.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.turtleplayer.bridge.MixAbout
import app.turtleplayer.bridge.MixSeedTie
import app.turtleplayer.bridge.TurtleBridge
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.ShelfKind
import app.turtleplayer.ui.components.ShellArt
import app.turtleplayer.ui.generated.resources.Res
import app.turtleplayer.ui.generated.resources.shell_mix_about_artist
import app.turtleplayer.ui.generated.resources.shell_mix_about_artist_known
import app.turtleplayer.ui.generated.resources.shell_mix_about_artist_new
import app.turtleplayer.ui.generated.resources.shell_mix_about_artist_share
import app.turtleplayer.ui.generated.resources.shell_mix_about_daily
import app.turtleplayer.ui.generated.resources.shell_mix_about_daily_known
import app.turtleplayer.ui.generated.resources.shell_mix_about_daily_new
import app.turtleplayer.ui.generated.resources.shell_mix_about_daily_share
import app.turtleplayer.ui.generated.resources.shell_mix_about_fresh
import app.turtleplayer.ui.generated.resources.shell_mix_about_more_like
import app.turtleplayer.ui.generated.resources.shell_mix_about_more_like_liked
import app.turtleplayer.ui.generated.resources.shell_mix_about_more_like_listened
import app.turtleplayer.ui.generated.resources.shell_mix_about_more_like_replayed
import app.turtleplayer.ui.generated.resources.shell_mix_about_on_repeat
import app.turtleplayer.ui.generated.resources.shell_mix_about_rediscover
import app.turtleplayer.ui.generated.resources.shell_mix_about_weekly
import app.turtleplayer.ui.generated.resources.shell_mix_about_weekly_share
import app.turtleplayer.ui.generated.resources.shell_mix_kind
import app.turtleplayer.ui.generated.resources.shell_mix_refresh_daily
import app.turtleplayer.ui.generated.resources.shell_mix_refresh_live
import app.turtleplayer.ui.generated.resources.shell_mix_refresh_weekly
import app.turtleplayer.ui.generated.resources.shell_mix_save
import app.turtleplayer.ui.generated.resources.shell_mix_shuffle_on
import app.turtleplayer.ui.theme.Shell
import app.turtleplayer.ui.theme.ShellPlatform
import com.metrolist.shared.generated.resources.n_song
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.displayThumbnailUrl
import com.metrolist.shared.settings.Settings
import com.metrolist.shared.turtle.HomeSlot
import com.metrolist.shared.turtle.TurtleUiSlots
import com.metrolist.shared.ui.navigation.NavigationBackHandler
import com.metrolist.shared.ui.playback.LocalPlaybackController
import com.metrolist.shared.ui.playback.PlaybackController
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt
import com.metrolist.shared.generated.resources.Res as SharedRes

/**
 * One of Turtle's mixes as it was when it was opened. Home's shelves refresh every few plays and
 * every two hours; the page keeps this copy, so the list never reorders or swaps songs under the
 * listener, and what plays is exactly what it shows.
 */
internal class MixSnapshot(
    /** The Home row key ("turtle:<shelf id>"): plays are attributed to it, as from the card. */
    val key: String,
    val title: String,
    val label: String?,
    val tint: Color,
    songs: List<SongItem>,
    val about: MixAbout?,
) {
    val songs: List<SongItem> = songs.toList()

    /** The songs the page lists, in the mix's order, without any [hides] has hidden since it opened. */
    fun visible(hides: (SongItem) -> Boolean): List<SongItem> = songs.filterNot(hides)

    /** What row [index] of [shown] plays: the whole list as shown, starting at that row; null outside it. */
    fun playFrom(shown: List<SongItem>, index: Int): MixPlay? = if (index in shown.indices) MixPlay(shown.toList(), index) else null

    /** Its main artists: the mix's own when known, otherwise the first names on its songs. */
    fun artists(): List<String> =
        about?.topArtists?.takeIf { it.isNotEmpty() }
            ?: songs.flatMap { song -> song.artists.map { it.name } }.filter { it.isNotBlank() }.distinct().take(MixAbout.MAX_TOP_ARTISTS)
}

/** A play request: [songs] in this order, starting at [startIndex]. */
internal data class MixPlay(val songs: List<SongItem>, val startIndex: Int)

/** The mix details page open over Home, on both platforms. Opening it never touches playback. */
internal object ShellMixDetails {
    var current by mutableStateOf<MixSnapshot?>(null)
        private set

    /**
     * The open page's scroll, held here: Home leaves composition while a page opened from the mix
     * (an artist) shows, and Back must find the list where it was.
     */
    var listState = LazyListState()
        private set

    fun open(snapshot: MixSnapshot) {
        listState = LazyListState()
        current = snapshot
    }

    fun close() {
        current = null
    }

    /**
     * Plays [shown] from row [index] in the order shown, credited to the mix's Home row like a play
     * from its card. The source is marked after the queue starts: starting a queue clears it.
     */
    fun play(playback: PlaybackController, snapshot: MixSnapshot, shown: List<SongItem>, index: Int) {
        val play = snapshot.playFrom(shown, index) ?: return
        ShellHomeFeedback.play(PlayPlace.HOME_TURTLE, snapshot.key, play.songs) {
            ShellQueue.play(playback, play.songs, snapshot.title, startIndex = play.startIndex, sourceId = snapshot.key)
            ShellQueue.markSource(snapshot.key, play.songs)
        }
    }
}

/** Save: upstream's add-to-playlist flow (pick a playlist or create one). Null where the platform has none. */
@Composable
private fun rememberMixSaver(): ((List<SongItem>) -> Unit)? =
    if (ShellPlatform.desktop) {
        // LocalPlayerPlatform is not provided on desktop; hook #30 hands over upstream's dialog instead.
        TurtleUiSlots.desktopExtras?.addSongsToPlaylist
    } else {
        com.metrolist.shared.ui.player.LocalPlayerPlatform.current.rememberPlaylistAdder()
    }

/** One sentence of a mix's about line: a string resource and its format arguments. */
internal data class AboutPhrase(val res: StringResource, val args: List<Any> = emptyList())

/**
 * The share of a mix's [total] songs the listener knows, in whole percent. While some but not all
 * are known it stays within 1..99, so rounding never says "0%" or "100%" of a mix that has both.
 */
internal fun knownPercent(known: Int, total: Int): Int =
    when {
        total <= 0 || known <= 0 -> 0
        known >= total -> 100
        else -> (known * 100.0 / total).roundToInt().coerceIn(1, 99)
    }

/**
 * This about counted over the songs the page lists ([shown]), not every song the engine built: Home
 * shows at most 24 of a mix, only songs it can play, and drops the ones the listener hid. Without
 * counts (total 0) it stays as it is and makes no claim.
 */
internal fun MixAbout.over(shown: List<SongItem>): MixAbout =
    if (total <= 0) this else copy(known = shown.count { it.id in knownSongs }, total = shown.size)

/**
 * What the mix is made of, told from what it holds now ([MixAbout.known] of [MixAbout.total]) and
 * never from the share the engine aimed for: a cold profile's mix is all new songs. A mix described
 * without counts (total 0) makes no claim about how familiar it is.
 */
internal fun aboutWhat(about: MixAbout): AboutPhrase? {
    val seed = about.seed
    val total = about.total
    val known = about.known.coerceIn(0, total.coerceAtLeast(0))
    val percent = knownPercent(known, total)
    return when (about.kind) {
        ShelfKind.DAILY_MIX ->
            when {
                total <= 0 -> if (seed != null) AboutPhrase(Res.string.shell_mix_about_artist, listOf(seed)) else AboutPhrase(Res.string.shell_mix_about_daily)
                seed == null && known == 0 -> AboutPhrase(Res.string.shell_mix_about_daily_new)
                seed == null && known == total -> AboutPhrase(Res.string.shell_mix_about_daily_known)
                seed == null -> AboutPhrase(Res.string.shell_mix_about_daily_share, listOf(percent))
                known == 0 -> AboutPhrase(Res.string.shell_mix_about_artist_new, listOf(seed))
                known == total -> AboutPhrase(Res.string.shell_mix_about_artist_known, listOf(seed))
                else -> AboutPhrase(Res.string.shell_mix_about_artist_share, listOf(seed, percent))
            }
        ShelfKind.MORE_LIKE ->
            seed?.let {
                val res =
                    when (about.seedTie) {
                        MixSeedTie.REPLAYED -> Res.string.shell_mix_about_more_like_replayed
                        MixSeedTie.LIKED -> Res.string.shell_mix_about_more_like_liked
                        MixSeedTie.LISTENED -> Res.string.shell_mix_about_more_like_listened
                        null -> Res.string.shell_mix_about_more_like
                    }
                AboutPhrase(res, listOf(it))
            }
        // Built from unheard songs only; a week-long shelf can hold songs played since.
        ShelfKind.WEEKLY_DISCOVERY ->
            if (known > 0) AboutPhrase(Res.string.shell_mix_about_weekly_share, listOf(percent)) else AboutPhrase(Res.string.shell_mix_about_weekly)
        ShelfKind.FRESH_FOR_YOU -> AboutPhrase(Res.string.shell_mix_about_fresh)
        ShelfKind.REDISCOVER -> AboutPhrase(Res.string.shell_mix_about_rediscover)
        ShelfKind.ON_REPEAT -> AboutPhrase(Res.string.shell_mix_about_on_repeat)
        else -> null
    }
}

/** How often the mix changes: live when it has no cadence, else weekly or daily. */
internal fun aboutRefresh(about: MixAbout): StringResource {
    val cadence = about.cadence
    return when {
        cadence == null -> Res.string.shell_mix_refresh_live
        cadence.weekly -> Res.string.shell_mix_refresh_weekly
        else -> Res.string.shell_mix_refresh_daily
    }
}

/** What the mix is made of and how often it changes, as one line under the title. */
@Composable
private fun aboutLine(about: MixAbout?): String? {
    if (about == null) return null
    val what = aboutWhat(about)?.let { stringResource(it.res, *it.args.toTypedArray()) }
    val refresh = stringResource(aboutRefresh(about))
    return listOfNotNull(what, refresh).joinToString(" • ")
}

/**
 * A Turtle mix's own page, built from the collection page's pieces: its cover, where it comes from
 * and how often it changes, Play and Save as separate buttons, and its songs in order. Any row plays
 * the mix from there, in the order shown.
 */
@Composable
internal fun ShellMixPage(snapshot: MixSnapshot, slot: HomeSlot, onBack: () -> Unit) {
    val desktop = ShellPlatform.desktop
    val playback = LocalPlaybackController.current
    val calm = playback.rememberCalmState()
    val hidden = TurtleBridge.hiddenSet
    val shown = remember(snapshot, hidden) { snapshot.visible(hidden::hides) }
    val listState = ShellMixDetails.listState
    val save = rememberMixSaver()
    // Save always opens the picker (pick a playlist or create one), so it never reads as "Saved".
    // Saving the whole mix is one action: Turtle's learning must not take it as a like for every song.
    val onSave: (() -> Unit)? =
        if (save != null && shown.isNotEmpty()) {
            {
                TurtleBridge.expectBulkPlaylistAdd(shown.map { it.id })
                save(shown)
            }
        } else {
            null
        }
    val itemModifier = Modifier.widthIn(max = 1400.dp).fillMaxWidth()
    val rowModifier = itemModifier.padding(horizontal = 8.dp)
    val art = remember(shown) { shown.mapNotNull { it.displayThumbnailUrl?.takeIf(String::isNotBlank) }.distinct().take(4) }
    val seconds = remember(shown) { shown.sumOf { it.duration ?: 0L } }
    val countText = pluralStringResource(SharedRes.plurals.n_song, shown.size, shown.size)
    val meta = listOfNotNull(countText, if (seconds > 0) durationText(seconds) else null).joinToString(" • ")
    val about = aboutLine(snapshot.about?.over(shown))
    val artists = remember(snapshot) { snapshot.artists().joinToString(", ") }
    NavigationBackHandler(enabled = true, onBack = onBack)

    CollectionFrame(
        modifier = Modifier.fillMaxSize(),
        listState = listState,
        contentPadding = slot.contentPadding,
        tintUrl = art.firstOrNull(),
        title = snapshot.title,
        collapseAfter = 1,
        onBack = onBack,
        selectionControls = null,
        busy = false,
        arrowScroll = true,
    ) {
        item(key = "header") {
            val now = calm.value
            val active = ShellQueue.isSourceActive(snapshot.key, shown, now)
            Box(itemModifier) {
                CollectionHeader(
                    artKey = snapshot.key,
                    kind = snapshot.label ?: stringResource(Res.string.shell_mix_kind),
                    title = snapshot.title,
                    cover = { m ->
                        if (art.size >= 4) Mosaic(art, m) else ShellArt(art.firstOrNull(), m, shape = RoundedCornerShape(8.dp), fallback = snapshot.tint, sizePx = 720)
                    },
                    byline =
                        if (artists.isNotBlank()) {
                            {
                                Text(
                                    artists, style = Shell.type.body.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold), color = Shell.colors.ink,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        } else {
                            null
                        },
                    meta = meta,
                    description = if (about != null) ({ Text(about, style = Shell.type.bodySmall, color = Shell.colors.ink2) }) else null,
                    actions =
                        CollectionActions(
                            showPlayback = shown.isNotEmpty(),
                            playing = active && now.isPlaying,
                            onPlay = { if (active) playback.togglePlayPause() else ShellMixDetails.play(playback, snapshot, shown, 0) },
                            // A mix plays in the order it shows; there is no shuffle here.
                            onShuffle = null,
                            isFavorite = false,
                            onFavorite = onSave,
                            favoriteLabel = stringResource(Res.string.shell_mix_save),
                            onEdit = null,
                            onAddSong = null,
                            onMore = null,
                        ),
                )
            }
        }

        // With upstream's persistent shuffle on, a new queue keeps shuffle: say so instead of surprising.
        if (Settings.shared.persistentShuffleAcrossQueues && calm.value.isShuffled) {
            item(key = "shuffle_note") {
                Text(
                    stringResource(Res.string.shell_mix_shuffle_on), style = Shell.type.bodySmall, color = Shell.colors.ink2,
                    modifier = itemModifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        if (desktop && shown.isNotEmpty()) {
            item(key = "table_head") { TableHead(numbered = true, albumColumn = true, modifier = rowModifier) }
        }

        itemsIndexed(shown, key = { index, song -> "${song.id}_$index" }, contentType = { _, _ -> "song" }) { index, song ->
            val onMenu = { ShellHomeFeedback.openMenu(song) { slot.onItemLongClick?.invoke(it) } }
            Box(rowModifier) {
                TrackRow(
                    song = song,
                    number = if (desktop) index + 1 else null,
                    showArt = true,
                    albumColumn = desktop,
                    table = desktop,
                    currentSongId = slot.currentSongId.value,
                    isPlaying = slot.isPlaying.value,
                    downloaded = false,
                    selected = false,
                    onClick = { ShellMixDetails.play(playback, snapshot, shown, index) },
                    longClick = onMenu,
                    onMenu = onMenu,
                    onArtistClick = slot.onArtistClick,
                )
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(32.dp)) }
    }
}
