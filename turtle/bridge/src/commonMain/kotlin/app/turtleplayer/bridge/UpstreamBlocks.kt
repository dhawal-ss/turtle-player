package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.taste.BlockedSet
import app.turtleplayer.taste.ExternalBlocks
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.settings.BlockedArtist
import com.metrolist.shared.settings.BlockedContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * Upstream's block list (blocked songs, blocked artists and its local dislikes, which BlockedContent
 * already folds into songIds) as the taste engine's live [ExternalBlocks]. Never copied into Turtle's controls.
 */
class UpstreamBlocks(
    private val database: () -> MusicDatabase,
) : ExternalBlocks {
    private val _changes = MutableStateFlow(BlockedSet.NONE)

    /**
     * The engine's view of the block list; emits after each rebuild, so it changes only once the
     * engine already sees the new set. W1's Home rows re-score on this, not on BlockedContent.version,
     * which fires before the rebuild (and before id-only artists are resolved).
     */
    val changes: StateFlow<BlockedSet> = _changes.asStateFlow()

    // Names of artists upstream stored by id only, looked up once each. Replaced whole.
    @Volatile
    private var resolved: Map<String, String> = emptyMap()
    private val rebuildLock = Mutex()

    /** Only reads the last rebuilt set: the engine calls this inside its lock, in loops. */
    override fun current(): BlockedSet = _changes.value

    /** Resolves names of artists blocked by id only, whenever the block list changes. */
    fun start(scope: CoroutineScope): Job =
        scope.launch {
            launch { BlockedContent.version.collect { rebuild() } }
            // Settings can change without a version bump (a backup restore, for one).
            launch {
                while (isActive) {
                    delay(RECHECK_MS)
                    rebuild()
                }
            }
        }

    private suspend fun rebuild() {
        try {
            rebuildLock.withLock {
                val songIds = BlockedContent.songIds()
                val artists = BlockedContent.artists()
                val known = resolved
                val found =
                    artists
                        .filter { it.name == it.id && it.id !in known }
                        .mapNotNull { a ->
                            database()
                                .dao()
                                .getArtistById(a.id)
                                ?.name
                                ?.takeIf { it.isNotBlank() }
                                ?.let { a.id to it }
                        }
                if (found.isNotEmpty()) resolved = known + found
                val next = blockedSetOf(songIds, artists, resolved)
                // A new instance only on a real change, so the engine's merge cache holds.
                if (next != _changes.value) _changes.value = next
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("blocks: could not read upstream's block list", e)
        }
    }

    private companion object {
        const val RECHECK_MS = 60_000L
    }
}

/** Songs by YouTube id; artists by name, using [resolved] (id → name) for entries upstream stored by id only, which are dropped until resolved. */
internal fun blockedSetOf(
    songIds: Set<String>,
    artists: List<BlockedArtist>,
    resolved: Map<String, String>,
): BlockedSet {
    val tracks = songIds.filter { it.isNotBlank() }.mapTo(HashSet()) { TrackKey.youtube(it) }
    val names =
        artists
            .mapNotNull { a -> if (a.name == a.id) resolved[a.id] else a.name }
            .filter { it.isNotBlank() }
            .mapTo(HashSet()) { ArtistKey.of(it) }
    return if (tracks.isEmpty() && names.isEmpty()) BlockedSet.NONE else BlockedSet(tracks, names)
}
