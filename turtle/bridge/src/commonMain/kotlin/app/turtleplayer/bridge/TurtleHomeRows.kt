package app.turtleplayer.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.taste.SongSupport
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.ui.screens.HomeShelf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.round

/**
 * Shell Home's personal parts that need the taste engine: which of YouTube's rows (and round-robin
 * grid suggestions) match the listener's taste, and Jump back in from the on-device event log.
 * Both are Compose state; the work runs off the UI thread.
 */
class TurtleHomeRows(
    private val taste: TasteEngine,
    private val events: PlayEventStore,
    private val cache: SongCache,
    private val database: () -> MusicDatabase,
    /** Emits when the taste engine's view of upstream's Block list changed (W9's UpstreamBlocks.changes). */
    private val blockChanges: Flow<*>,
    /** Upstream's live Block list (blocked songs, artists and YouTube dislikes); injectable for tests. */
    private val isBlocked: (SongItem) -> Boolean = BlockedContent::isBlocked,
    /** W3's one spoken-word rule; plays stored before W3 landed may still be audiobook chapters. */
    private val isSpokenWord: (SongItem) -> Boolean = { TurtleSpoken.isSpokenWord(it) },
    /** Awaited once before the first Jump back in, so chapters of saved books are known. */
    private val spokenReady: suspend () -> Unit = { TurtleSpoken.load() },
) {
    /** Newest first; null until first loaded. Compose state. */
    var jumpBackIn: List<SongItem>? by mutableStateOf(null)
        private set

    /** Null until the first scoring of the current candidates. Compose state. */
    var pick: HomeRowsPick? by mutableStateOf(null)
        private set

    // One refresh and one scoring at a time, so an older result never lands after a newer one.
    private val jumpLock = Mutex()
    private val scoreLock = Mutex()
    private var lastInput: Pair<List<HomeShelf>, List<YTItem>>? = null
    private var lastSignature: Any? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            refreshJumpBackIn()
            // Conflated: a refresh reads the whole log, so plays that land during one need only one more.
            events.newEvents.filter { it.isFinished }.conflate().collect {
                refreshJumpBackIn()
                // A listener who just crossed the cold-start bar gets scored rows.
                if (pick?.scored == false) invalidate()
            }
        }
        // A StateFlow in the app: its current value is not a change.
        scope.launch { blockChanges.drop(1).collect { onControlsChanged() } }
    }

    suspend fun refreshJumpBackIn() {
        withContext(Dispatchers.Default) {
            jumpLock.withLock {
                try {
                    spokenReady()
                    val ids = taste.jumpBackIn(JUMP_BACK_IN_FETCH).mapNotNull { it.youtubeId }.distinct()
                    val cached = ids.associateWith { cache.get(it) }
                    val missing = ids.filter { cached[it] == null }
                    // Plays outside the library and the cache (older ones, imports) resolve from upstream's song table.
                    val stored =
                        if (missing.isEmpty()) {
                            emptyMap()
                        } else {
                            database()
                                .dao()
                                .getSongsByIds(missing)
                                .map { it.toSongItem() }
                                .associateBy { it.id }
                        }
                    // The event log and upstream's song table are never block-filtered, unlike upstream's own rows.
                    jumpBackIn =
                        ids
                            .mapNotNull { cached[it] ?: stored[it] }
                            .filterNot { isSpokenWord(it) || isBlocked(it) }
                            .take(JUMP_BACK_IN_SIZE)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    TurtleLog.error("home: could not load Jump back in", e)
                    // Never null after a try: the grid waits on it.
                    jumpBackIn = jumpBackIn ?: emptyList()
                }
            }
        }
    }

    /** Scores [rows] (HomeLayout.candidates) and grid round-robin [grid] on Dispatchers.Default; skips an unchanged input. */
    suspend fun score(
        rows: List<HomeShelf>,
        grid: List<YTItem>,
    ) {
        withContext(Dispatchers.Default) { scoreLock.withLock { scoreLocked(rows, grid, force = false) } }
    }

    /** Re-scores the last input (the listener's controls or cold-start state changed). No-op before the first [score]. */
    suspend fun invalidate() {
        withContext(Dispatchers.Default) {
            scoreLock.withLock {
                val (rows, grid) = lastInput ?: return@withLock
                scoreLocked(rows, grid, force = true)
            }
        }
    }

    /** Mutes, "less like this", not interested or a block changed: refreshes Jump back in and re-scores. */
    suspend fun onControlsChanged() {
        refreshJumpBackIn()
        invalidate()
    }

    private suspend fun scoreLocked(
        rows: List<HomeShelf>,
        grid: List<YTItem>,
        force: Boolean,
    ) {
        val signature = rows.map { it.key to it.items.map(YTItem::id) } to grid.map(YTItem::id)
        if (!force && signature == lastSignature) return
        try {
            // Rows already on screen keep their slots while they still pass; unscored rows hold none.
            val sticky = pick?.takeIf { it.scored }?.rowKeys.orEmpty()
            val selection = taste.selectHomeRows(rows.map { it.toRowCandidate() }, sticky)
            val matches = taste.matchesTaste(grid.map { it.toRowItem() })
            val gridIds = grid.filterIndexed { i, _ -> matches.getOrElse(i) { false } }.mapTo(LinkedHashSet()) { it.id }
            val picked = rows.filter { it.key in selection.ids }
            val songSupport = if (selection.coldStart) emptyMap() else songSupport(picked)
            val orderToday = songSupport.keys.filterTo(LinkedHashSet()) { taste.ordersRow(it) }
            pick = HomeRowsPick(selection.ids, gridIds, scored = !selection.coldStart, songSupport = songSupport, orderToday = orderToday)
            // The data for tuning HomeRowConfig's thresholds on device.
            val scores =
                rows.joinToString("; ") { row ->
                    val s = selection.scores[row.key] ?: return@joinToString row.key
                    "${row.key} value=${s.value.r2()} known=${s.knownArtistShare.r2()} disliked=${s.dislikedShare.r2()}"
                }
            val how = if (selection.coldStart) "cold start" else "scored"
            val tiles = "grid ${gridIds.size}/${grid.size}"
            TurtleLog.info("home rows: $how, kept ${selection.ids} of [$scores], $tiles, ordering $orderToday of ${songSupport.keys}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Home never goes blank: YouTube's first rows, unscored.
            pick = HomeRowsPick(rows.take(FALLBACK_ROWS).map { it.key }, grid.mapTo(LinkedHashSet()) { it.id }, scored = false)
            TurtleLog.error("home rows: scoring failed, showing the first rows unscored", e)
        }
        lastInput = rows to grid
        lastSignature = signature
    }

    /** Each of [rows] to its songs' support by id, ordered today or not; nothing at cold start. */
    private suspend fun songSupport(rows: List<HomeShelf>): Map<String, Map<String, SongSupport>> {
        val byRow = LinkedHashMap<String, Map<String, SongSupport>>()
        for (row in rows) {
            val songs = row.items.filterIsInstance<SongItem>().distinctBy { it.id }
            if (songs.isEmpty()) continue
            val supports = taste.rowSongSupport(songs.map { it.toTrackMeta() }) ?: continue
            byRow[row.key] = songs.zip(supports) { song, support -> song.id to support }.toMap()
        }
        return byRow
    }

    private fun Double.r2(): String = (round(this * 100) / 100).toString()

    private companion object {
        const val JUMP_BACK_IN_FETCH = 40
        const val JUMP_BACK_IN_SIZE = 20
        const val FALLBACK_ROWS = 3
    }
}
