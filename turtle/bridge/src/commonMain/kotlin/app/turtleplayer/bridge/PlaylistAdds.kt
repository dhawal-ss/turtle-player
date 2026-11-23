package app.turtleplayer.bridge

import androidx.room3.useReaderConnection
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.db.isHistoryPlaylistId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** One song in one of upstream's playlists. */
data class Membership(
    val playlistId: String,
    val songId: String,
)

/**
 * Turns snapshots of upstream's playlist membership into the listener's own adds. The first snapshot
 * is only the baseline. A pair seen in any earlier snapshot of this run never counts again (reorders,
 * removals then re-adds, delete-and-reinsert syncs). New pairs wait per playlist until it has been
 * quiet for [settleMs]; a playlist that took more than [maxBurst] new pairs in that burst is an import
 * or a sync, and the whole burst is dropped. System playlists ([counts] false) never count.
 */
class PlaylistAdds(
    private val maxBurst: Int = MAX_BURST,
    private val settleMs: Long = SETTLE_MS,
) {
    private class Burst(
        var lastMs: Long,
    ) {
        val pairs = LinkedHashSet<Membership>()
    }

    private var baselined = false
    private val seen = HashSet<Membership>()
    private val bursts = LinkedHashMap<String, Burst>()

    fun observe(
        snapshot: Set<Membership>,
        nowMs: Long,
    ) {
        if (!baselined) {
            baselined = true
            seen += snapshot
            return
        }
        for (pair in snapshot) {
            if (!seen.add(pair) || !counts(pair.playlistId)) continue
            val burst = bursts.getOrPut(pair.playlistId) { Burst(nowMs) }
            burst.pairs += pair
            burst.lastMs = nowMs
        }
    }

    /** The adds whose playlist has settled by [nowMs], each returned once. */
    fun due(nowMs: Long): List<Membership> {
        val out = ArrayList<Membership>()
        val settled = bursts.entries.filter { nowMs - it.value.lastMs >= settleMs }
        for ((playlist, burst) in settled) {
            bursts.remove(playlist)
            if (burst.pairs.size <= maxBurst) out += burst.pairs
        }
        return out
    }

    companion object {
        const val MAX_BURST = 50
        const val SETTLE_MS = 60_000L

        /** False for upstream's own playlists: built-ins ("LP_" ids, e.g. Liked, Downloaded) and the most-played history playlists. */
        fun counts(playlistId: String): Boolean = !isHistoryPlaylistId(playlistId) && !playlistId.startsWith("LP_")
    }
}

/**
 * Watches upstream's playlist membership (read only, no hook) and records each song the listener
 * adds to one of their playlists as [TrackFeedbackKind.ADDED_TO_PLAYLIST], then hands it to the taste
 * engine. An add is an explicit action, so it is recorded while listening history is paused. What
 * was seen lives in memory: an add made in the minute before the app is killed is lost.
 */
@OptIn(ExperimentalTime::class)
class PlaylistAddWatcher(
    private val database: () -> MusicDatabase,
    private val store: PlayEventStore,
    private val taste: TasteEngine,
    private val cache: SongCache,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val adds = PlaylistAdds()
    private val lock = Mutex()

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            launch {
                // Emits once at start (the baseline), then after each change, settled for a moment.
                database().invalidationTracker.createFlow(MAP_TABLE, PLAYLIST_TABLE).debounce(DEBOUNCE_MS).collect {
                    guarded("read playlists") {
                        val pairs = readPairs()
                        lock.withLock { adds.observe(pairs, nowMs()) }
                    }
                }
            }
            launch {
                while (isActive) {
                    delay(POLL_MS)
                    guarded("record adds") { recordDue() }
                }
            }
        }

    private suspend fun readPairs(): Set<Membership> =
        database().useReaderConnection { connection ->
            connection.usePrepared(PAIRS_SQL) { s ->
                val out = HashSet<Membership>()
                while (s.step()) out += Membership(s.getText(0), s.getText(1))
                out
            }
        }

    private suspend fun recordDue() {
        val due = lock.withLock { adds.due(nowMs()) }
        for (add in due) {
            guarded("record an add") {
                val track =
                    cache.get(add.songId)?.toTrackMeta()
                        ?: database()
                            .dao()
                            .getSongById(add.songId)
                            ?.toSongItem()
                            ?.toTrackMeta()
                if (track == null) {
                    TurtleLog.info("playlist add: song ${add.songId} not found; skipped")
                    return@guarded
                }
                val feedback =
                    TrackFeedback(track = track, kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = nowMs(), detail = add.playlistId)
                val id = store.appendFeedback(feedback)
                taste.onFeedback(feedback.copy(id = id))
            }
        }
        if (due.isNotEmpty()) TurtleLog.info("playlist adds: recorded ${due.size}")
    }

    private inline fun guarded(
        what: String,
        block: () -> Unit,
    ) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("playlist adds: $what failed", e)
        }
    }

    private companion object {
        const val MAP_TABLE = "playlist_song_map"
        const val PLAYLIST_TABLE = "playlist"
        const val PAIRS_SQL =
            "SELECT m.playlistId, m.songId FROM playlist_song_map m " +
                "JOIN playlist p ON p.id = m.playlistId WHERE p.isEditable = 1"
        const val DEBOUNCE_MS = 1_000L
        const val POLL_MS = 15_000L
    }
}
