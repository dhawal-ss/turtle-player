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
import app.turtleplayer.taste.Reason
import app.turtleplayer.taste.Shelf
import app.turtleplayer.taste.ShelfKind
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.ui.screens.HomeShelf
import com.metrolist.shared.ui.screens.HomeShelfHeaderAction
import com.metrolist.shared.ui.screens.HomeShelfStyle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jetbrains.compose.resources.getString

/**
 * The taste engine's shelves as upstream [HomeShelf]s, placed at the top of Home on both platforms.
 *
 * Shelves live in Compose state: upstream builds Home during composition, so reading [shelves]
 * there recomposes Home whenever we refresh. Refreshes happen at start, every few plays and on a
 * timer, never on the UI thread.
 */
class TurtleHomeShelves(
    private val taste: TasteEngine,
    private val events: PlayEventStore,
    private val cache: SongCache,
) {
    var shelves: List<HomeShelf> by mutableStateOf(emptyList())
        private set

    private val refreshLock = Mutex()

    fun start(scope: CoroutineScope) {
        scope.launch {
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

    suspend fun refresh() = refreshLock.withLock {
        val label = getString(Res.string.turtle_shelf_label)
        val fromEngine = taste.homeShelves()
        val built = fromEngine.mapIndexedNotNull { index, shelf -> toHomeShelf(shelf, index, label) }
        shelves = built
        TurtleLog.info("home: engine ${fromEngine.size} shelves (${fromEngine.joinToString { "${it.kind}=${it.items.size}" }}), showing ${built.size} (${built.joinToString { "${it.key.substringAfter(':')}=${it.items.size}" }})")
    }

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
            key = "turtle:${shelf.id}",
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
        const val MAX_ITEMS = 24
        const val PLAYS_PER_REFRESH = 8
        const val REFRESH_INTERVAL_MS = 2 * HOUR_MS
    }
}
