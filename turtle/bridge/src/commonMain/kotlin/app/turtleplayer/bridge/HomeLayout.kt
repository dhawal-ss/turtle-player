package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.taste.RowCandidate
import app.turtleplayer.taste.RowItem
import app.turtleplayer.taste.ShelfKind
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfStyle
import com.metrolist.shared.viewmodels.HomeViewModel.HomeUiState

/** Home keys of Turtle's own rows: this prefix, then the engine's shelf id ("daily_mix:0", "more_like:yt:…"). */
const val TURTLE_SHELF_KEY_PREFIX = "turtle:"

/** "YouTube Music suggestions on Home". Stored with TurtleBridge.setUiPref(PREF_KEY, prefValue). */
enum class HomeYouTubeRows(
    val prefValue: String,
) {
    /** At most three of YouTube's rows, those that match the listener's taste (the default). */
    MATCHED("matched"),

    /** Every row YouTube sends, chip feeds included: Home as upstream builds it. */
    ALL("all"),

    /** None of YouTube's rows. */
    OFF("off"),
    ;

    companion object {
        const val PREF_KEY = "home_youtube_rows"

        /** The saved value; [MATCHED] when nothing (or something unknown) is saved. */
        fun fromPref(value: String?): HomeYouTubeRows = entries.firstOrNull { it.prefValue == value } ?: MATCHED
    }
}

/** The bridge's decision for one set of candidate rows. [scored] false: cold start or scoring failed (rows shown unscored). */
data class HomeRowsPick(
    val rowKeys: List<String>,
    val gridIds: Set<String>,
    val scored: Boolean,
)

/**
 * Shell Home's content, top to bottom after the filter chips: the shortcut grid, Turtle's mixes
 * ("Made for you"), Jump back in, More like X, then YouTube's rows. Pure: built from upstream's
 * shelves and state, the listener's recents, the bridge's pick and the setting.
 */
class HomeLayout(
    /** Up to [GRID_TILES] shortcut tiles after Infinity and Liked Songs. */
    val grid: List<YTItem>,
    /** False while recents, the setting or (Matched, tiles short) the pick are not known yet: draw the placeholder. */
    val gridReady: Boolean,
    val mixes: List<HomeShelf>,
    val moreLike: List<HomeShelf>,
    val jumpBackIn: List<SongItem>,
    val youtubeRows: List<HomeShelf>,
    /** Each grid tile's source by canonical id, see [gridOrigin]. */
    private val origins: Map<String, String> = emptyMap(),
) {
    /** Where a grid tile came from, for play attribution: "pin", "recent" or "youtube:<row key>". */
    fun gridOrigin(item: YTItem): String = origins[canonicalHomeItemId(item)] ?: ORIGIN_RECENT

    companion object {
        const val GRID_TILES = 6
        const val JUMP_BACK_IN_MIN_ITEMS = 3

        private const val ORIGIN_PIN = "pin"
        private const val ORIGIN_RECENT = "recent"
        private const val ORIGIN_YOUTUBE = "youtube:"
        private const val KEEP_LISTENING_KEY = "keep_listening"
        private const val LISTEN_AGAIN_BROWSE_PREFIX = "FEmusic_listen_again"
        private const val QUICK_PICKS_KEY = "quick_picks"
        private const val CHIP_FEED_KEY_PREFIX = "chip_"
        private const val PODCASTS_KEY = "podcasts"
        private const val MOODS_KEY = "moods"

        /** Charts and Explore-sourced rows: they belong to Explore and W7's "Top in your country". */
        private val EXPLORE_KEYS = setOf("trending", "album_charts", "new_releases", "community_playlists")

        /**
         * Upstream's rows that may show as YouTube rows: not Turtle's, not Speed Dial, not empty. Under
         * [HomeYouTubeRows.ALL] that is all of them; otherwise recents (Turtle's Jump back in replaces
         * them), chip feeds, charts and Explore rows, podcasts and moods are left out too.
         */
        fun candidates(
            shelves: List<HomeShelf>,
            state: HomeUiState,
            mode: HomeYouTubeRows,
        ): List<HomeShelf> {
            val upstream =
                shelves
                    .filter { isUpstreamRow(it) && it.items.isNotEmpty() }
                    .distinctBy { it.key }
            if (mode == HomeYouTubeRows.ALL) return upstream
            return upstream.filterNot { shelf ->
                shelf.key == KEEP_LISTENING_KEY ||
                    shelf.browseId?.startsWith(LISTEN_AGAIN_BROWSE_PREFIX) == true ||
                    // Never feedChip: upstream sets it on every row while a chip is selected.
                    shelf.key.startsWith(CHIP_FEED_KEY_PREFIX) ||
                    shelf.key in EXPLORE_KEYS ||
                    // Signed out, upstream merges Explore's sections into Home.
                    state.discoverySections.any { it.title == shelf.title && it.browseId == shelf.browseId } ||
                    shelf.key == PODCASTS_KEY ||
                    shelf.items.all { it.isPodcast } ||
                    shelf.style == HomeShelfStyle.MOOD_AND_GENRES ||
                    shelf.key == MOODS_KEY
            }
        }

        /** Upstream's round-robin grid suggestions: Speed Dial's unpinned items, or quick picks without Speed Dial. */
        fun gridCandidates(
            shelves: List<HomeShelf>,
            state: HomeUiState,
        ): List<YTItem> = gridSource(shelves, state, state.quickPicks).second

        /** The key of the row grid suggestions come from, and the suggestions. */
        private fun gridSource(
            shelves: List<HomeShelf>,
            state: HomeUiState,
            quickPicks: List<YTItem>,
        ): Pair<String, List<YTItem>> {
            val speedDial = shelves.firstOrNull { it.style == HomeShelfStyle.SPEED_DIAL } ?: return QUICK_PICKS_KEY to quickPicks
            return speedDial.key to speedDial.items.filterNot { isPinned(it, state) }
        }

        /** Upstream's own rows: not Turtle's, not Speed Dial. */
        private fun isUpstreamRow(shelf: HomeShelf) =
            !shelf.key.startsWith(TURTLE_SHELF_KEY_PREFIX) && shelf.style != HomeShelfStyle.SPEED_DIAL

        private fun isPinned(
            item: YTItem,
            state: HomeUiState,
        ) = item.id in state.speedDialPinnedIds || canonicalHomeItemId(item) in state.speedDialPinnedIds

        /** [hidden]: W9's TurtleBridge.hiddenSet, applied before the grid is filled. */
        fun of(
            shelves: List<HomeShelf>,
            state: HomeUiState,
            jumpBackIn: List<SongItem>?,
            pick: HomeRowsPick?,
            mode: HomeYouTubeRows?,
            hidden: HiddenSet = HiddenSet(),
        ): HomeLayout {
            // What the listener hid leaves Home at once: it never takes a tile, and a row it empties disappears.
            val visible =
                shelves
                    .filterNot { it.key.startsWith(TURTLE_SHELF_KEY_PREFIX) && hidden.hidesShelf(it.key) }
                    .map { shelf -> shelf.copy(items = shelf.items.filterNot(hidden::hides)) }
            val recents = jumpBackIn?.filterNot(hidden::hides)

            val turtle = visible.filter { it.key.startsWith(TURTLE_SHELF_KEY_PREFIX) && it.items.isNotEmpty() }.distinctBy { it.key }
            val moreLike = turtle.filter { it.isMoreLike() }
            val mixes = turtle.filterNot { it.isMoreLike() }

            val speedDial = visible.firstOrNull { it.style == HomeShelfStyle.SPEED_DIAL }
            val pinned = speedDial?.items.orEmpty().filter { isPinned(it, state) }
            val (sourceKey, suggestions) = gridSource(visible, state, state.quickPicks.filterNot(hidden::hides))
            val roundRobin =
                when (mode) {
                    HomeYouTubeRows.MATCHED -> pick?.gridIds.orEmpty().let { ids -> suggestions.filter { it.id in ids } }
                    HomeYouTubeRows.ALL -> suggestions
                    HomeYouTubeRows.OFF, null -> emptyList()
                }
            val upstreamRows = visible.filter(::isUpstreamRow)

            val origins = LinkedHashMap<String, String>()
            val grid = ArrayList<YTItem>()

            fun offer(
                item: YTItem,
                origin: () -> String,
            ) {
                val id = canonicalHomeItemId(item)
                if (grid.size >= GRID_TILES || id in origins) return
                origins[id] = origin()
                grid += item
            }
            pinned.forEach { offer(it) { ORIGIN_PIN } }
            recents.orEmpty().forEach { offer(it) { ORIGIN_RECENT } }
            for (item in roundRobin) {
                offer(item) {
                    val id = canonicalHomeItemId(item)
                    val row = upstreamRows.firstOrNull { row -> row.items.any { canonicalHomeItemId(it) == id } }?.key ?: sourceKey
                    ORIGIN_YOUTUBE + row
                }
            }

            val ownTiles = (pinned + recents.orEmpty()).distinctBy(::canonicalHomeItemId).size
            val gridReady = recents != null && mode != null && (ownTiles >= GRID_TILES || mode != HomeYouTubeRows.MATCHED || pick != null)

            val gridIds = origins.keys
            val jumpBackInRow =
                recents
                    .orEmpty()
                    .filter { canonicalHomeItemId(it) !in gridIds }
                    .takeIf { it.size >= JUMP_BACK_IN_MIN_ITEMS }
                    .orEmpty()

            val shown =
                when (mode) {
                    HomeYouTubeRows.MATCHED -> {
                        val byKey = candidates(visible, state, HomeYouTubeRows.MATCHED).associateBy { it.key }
                        pick?.rowKeys.orEmpty().mapNotNull(byKey::get)
                    }

                    HomeYouTubeRows.ALL -> {
                        candidates(visible, state, HomeYouTubeRows.ALL)
                    }

                    HomeYouTubeRows.OFF, null -> {
                        emptyList()
                    }
                }
            val youtubeRows =
                shown
                    .map { row -> row.copy(items = row.items.filterNot { canonicalHomeItemId(it) in gridIds }) }
                    .filter { it.items.isNotEmpty() }

            return HomeLayout(grid, gridReady, mixes, moreLike, jumpBackInRow, youtubeRows, origins)
        }
    }
}

private val MORE_LIKE_KEY_PREFIX = TURTLE_SHELF_KEY_PREFIX + ShelfKind.MORE_LIKE.name.lowercase() + ":"

/** One of Turtle's "More like X" rows (shown as a row of cards, not a mix). */
fun HomeShelf.isMoreLike(): Boolean = key.startsWith(MORE_LIKE_KEY_PREFIX)

/** An item as the taste engine scores it: songs with their artists, artists, and albums or playlists with the artists upstream names. */
internal fun YTItem.toRowItem(): RowItem =
    when (this) {
        is SongItem -> RowItem.Track(toTrackMeta())
        is ArtistItem -> RowItem.Artist(ArtistKey.of(title))
        is AlbumItem -> RowItem.Collection(artists.map { ArtistKey.of(it.name) })
        else -> RowItem.Collection(emptyList())
    }

internal fun HomeShelf.toRowCandidate(): RowCandidate = RowCandidate(key, items.map { it.toRowItem() }, personal = isPersonal)
