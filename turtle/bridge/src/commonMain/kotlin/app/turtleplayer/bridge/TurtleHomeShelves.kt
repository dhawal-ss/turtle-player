package app.turtleplayer.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.turtleplayer.bridge.generated.resources.Res
import app.turtleplayer.bridge.generated.resources.turtle_shelf_artist_mix
import app.turtleplayer.bridge.generated.resources.turtle_shelf_because_liked
import app.turtleplayer.bridge.generated.resources.turtle_shelf_because_listened
import app.turtleplayer.bridge.generated.resources.turtle_shelf_because_replayed
import app.turtleplayer.bridge.generated.resources.turtle_shelf_daily_mix
import app.turtleplayer.bridge.generated.resources.turtle_shelf_fresh_for_you
import app.turtleplayer.bridge.generated.resources.turtle_shelf_label
import app.turtleplayer.bridge.generated.resources.turtle_shelf_on_repeat
import app.turtleplayer.bridge.generated.resources.turtle_shelf_rediscover
import app.turtleplayer.bridge.generated.resources.turtle_shelf_weekly_discovery
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.taste.PlayOrigin
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.Reason
import app.turtleplayer.taste.Shelf
import app.turtleplayer.taste.ShelfKind
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfHeaderAction
import com.metrolist.shared.ui.screens.HomeShelfStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString
import kotlin.concurrent.Volatile

/**
 * The taste engine's shelves as upstream [HomeShelf]s, placed at the top of Home on both platforms.
 *
 * Shelves live in Compose state: upstream builds Home during composition, so reading [shelves]
 * there recomposes Home whenever we refresh. Refreshes happen at start, every few plays and on a
 * timer, never on the UI thread.
 *
 * Kept shelves and their songs survive restarts: the engine's kept shelves are saved with the
 * taste state after every build, and their upstream songs in our database, pinned in [cache]. At
 * start the saved songs come back first, so the day's and week's rows show before any network call.
 */
class TurtleHomeShelves(
    private val taste: TasteEngine,
    private val events: PlayEventStore,
    private val cache: SongCache,
    private val db: SqlitePlayEventStore,
) {
    var shelves: List<HomeShelf> by mutableStateOf(emptyList())
        private set

    /** Guards [restored], [savedSongIds], publishing and persisting. Not reentrant: the `Locked` functions do not take it. */
    private val refreshLock = Mutex()
    private val json = Json { ignoreUnknownKeys = true }
    private var restored = false
    private var savedSongIds: List<String> = emptyList()

    // The engine shelves on Home by home key, replaced whole when shelves are published (off the UI
    // thread) and read when a play starts (on it).
    @Volatile
    private var engineShelves: Map<String, Shelf> = emptyMap()

    fun start(scope: CoroutineScope) {
        scope.launch {
            // Saved songs, then Turtle's rows from disk, in one hold of the lock: a refresh() from
            // elsewhere (fresh releases) cannot slip a network build in before the first paint.
            refreshLock.withLock {
                restoreLocked()
                try {
                    publishLocked(taste.keptShelves())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TurtleLog.error("home: could not show the kept shelves", e)
                }
            }
            refresh()
            var playsSinceRefresh = 0
            events.newEvents.collect {
                if (++playsSinceRefresh >= PLAYS_PER_REFRESH) {
                    playsSinceRefresh = 0
                    refresh()
                }
            }
        }
        scope.launch {
            while (true) {
                delay(REFRESH_INTERVAL_MS)
                refresh()
            }
        }
    }

    /** Builds, shows and saves the shelves. Does nothing until [restore] has run, so startup never saves defaults over the saved state. */
    suspend fun refresh() = refreshLock.withLock {
        if (!restored) return@withLock
        val fromEngine = taste.homeShelves()
        publishLocked(fromEngine)
        persistLocked(fromEngine)
    }

    /** Loads the saved shelf songs into the cache, pinned. Until this has run, [refresh] does nothing. */
    internal suspend fun restore() {
        refreshLock.withLock { restoreLocked() }
    }

    private suspend fun restoreLocked() {
        try {
            val songs =
                db.loadShelfSongs().mapNotNull { row ->
                    runCatching { json.decodeFromString(SongItem.serializer(), row) }.getOrNull()
                }
            cache.replaceShelf(songs)
            savedSongIds = songs.map { it.id }
            TurtleLog.info("home: restored ${songs.size} shelf songs")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Home must never stay empty because the table is unreadable.
            TurtleLog.error("home: could not restore the shelf songs", e)
        }
        restored = true
    }

    /**
     * Pins the songs of [fromEngine]'s items, saves them when the set changed, then saves the
     * taste state (which now holds the kept shelves). Called after every build, so a kill right
     * after a build, with no play in between, keeps the day's shelves.
     */
    internal suspend fun persist(fromEngine: List<Shelf>) = refreshLock.withLock { persistLocked(fromEngine) }

    private suspend fun publishLocked(fromEngine: List<Shelf>) {
        val label = getString(Res.string.turtle_shelf_label)
        val built = fromEngine.mapIndexedNotNull { index, shelf -> toHomeShelf(shelf, index, label) }
        val shown = built.mapTo(HashSet()) { it.key }
        engineShelves = fromEngine.associateBy { homeKey(it) }.filterKeys { it in shown }
        shelves = built
        TurtleLog.info("home: engine ${fromEngine.size} shelves (${fromEngine.joinToString { "${it.kind}=${it.items.size}" }}), showing ${built.size} (${built.joinToString { "${it.key.substringAfter(':')}=${it.items.size}" }})")
    }

    private suspend fun persistLocked(fromEngine: List<Shelf>) {
        // Songs of items hidden by a mute at build time are not pinned; unmuting the same day is rare.
        try {
            val songs =
                fromEngine
                    .flatMap { it.items }
                    .mapNotNull { it.track.youtubeId }
                    .distinct()
                    .mapNotNull { cache.get(it) }
            cache.replaceShelf(songs)
            val ids = songs.map { it.id }
            if (ids != savedSongIds) {
                db.saveShelfSongs(songs.map { it.id to json.encodeToString(SongItem.serializer(), it) })
                savedSongIds = ids
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("home: could not save the shelf songs", e)
        }
        try {
            writeTasteState(db, taste)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("home: could not save taste state", e)
        }
    }

    /** The engine shelf behind Home row [homeKey] ("turtle:<shelf id>"), while it is on Home. */
    fun engineShelf(homeKey: String): Shelf? = engineShelves[homeKey]

    /** The origin of [songId] played from Turtle row [homeKey]: HOME_TURTLE, the item's source and slot when known, row = shelf id. */
    fun originOf(
        homeKey: String,
        songId: String,
    ): PlayOrigin {
        val shelf = engineShelf(homeKey)
        val item = shelf?.items?.firstOrNull { it.track.youtubeId == songId }
        return PlayOrigin(PlayPlace.HOME_TURTLE, item?.source, item?.slot, shelf?.id ?: homeKey.removePrefix(HOME_KEY_PREFIX))
    }

    private fun homeKey(shelf: Shelf) = HOME_KEY_PREFIX + shelf.id

    /** Upstream's shelves with ours inserted after Speed Dial (or at the top). */
    fun mergeInto(upstream: List<HomeShelf>): List<HomeShelf> {
        val ours = shelves
        if (ours.isEmpty()) return upstream
        val at = upstream.indexOfFirst { it.style == HomeShelfStyle.SPEED_DIAL }.let { if (it < 0) 0 else it + 1 }
        return upstream.take(at) + ours + upstream.drop(at)
    }

    private suspend fun toHomeShelf(shelf: Shelf, index: Int, label: String): HomeShelf? {
        val items = shelf.items.mapNotNull { item -> item.track.youtubeId?.let { cache.get(it) } }
        if (items.size < MIN_ITEMS) return null
        val title = title(shelf, index) ?: return null
        return HomeShelf(
            key = homeKey(shelf),
            title = title,
            items = items.take(MAX_ITEMS),
            style = HomeShelfStyle.SONG_GRID,
            headerAction = HomeShelfHeaderAction.PLAY_ALL,
            rows = 4,
            label = label,
            isPersonal = true,
        )
    }

    private suspend fun title(shelf: Shelf, index: Int): String? = when (shelf.kind) {
        ShelfKind.DAILY_MIX -> {
            val artist = (shelf.reason as? Reason.DailyMix)?.let { it.seedArtist ?: it.topArtists.firstOrNull() }
            val name = artist?.let { taste.displayName(it) }
            if (name != null) getString(Res.string.turtle_shelf_artist_mix, name) else getString(Res.string.turtle_shelf_daily_mix, index + 1)
        }
        ShelfKind.MORE_LIKE -> when (val r = shelf.reason) {
            is Reason.BecauseYouReplayed -> taste.displayName(r.track)?.let { getString(Res.string.turtle_shelf_because_replayed, it) }
            is Reason.BecauseYouLiked -> taste.displayName(r.track)?.let { getString(Res.string.turtle_shelf_because_liked, it) }
            is Reason.BecauseYouListened -> taste.displayName(r.track)?.let { getString(Res.string.turtle_shelf_because_listened, it) }
            else -> null
        }
        ShelfKind.ON_REPEAT -> getString(Res.string.turtle_shelf_on_repeat)
        ShelfKind.WEEKLY_DISCOVERY -> getString(Res.string.turtle_shelf_weekly_discovery)
        ShelfKind.FRESH_FOR_YOU -> getString(Res.string.turtle_shelf_fresh_for_you)
        ShelfKind.REDISCOVER -> getString(Res.string.turtle_shelf_rediscover)
        else -> null
    }

    private companion object {
        const val MIN_ITEMS = 4
        const val HOME_KEY_PREFIX = "turtle:"
        const val MAX_ITEMS = 24
        const val PLAYS_PER_REFRESH = 8
        const val REFRESH_INTERVAL_MS = 2 * HOUR_MS
    }
}
