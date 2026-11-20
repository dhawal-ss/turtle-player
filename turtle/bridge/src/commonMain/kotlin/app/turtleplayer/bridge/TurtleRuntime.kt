package app.turtleplayer.bridge

import app.turtleplayer.search.DocType
import app.turtleplayer.search.LexicalQuery
import app.turtleplayer.search.LocalItem
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.LocalSource
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.viewmodels.libraryContentSearchOverride
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Wires our engines into the running app once, and serves the upstream hook calls. */
@OptIn(ExperimentalAtomicApi::class)
class TurtleRuntime(
    private val turtleScope: TurtleScope,
    val observer: PlayerObserver,
    private val librarySync: LibrarySync,
    private val library: LocalLibrary,
    private val taste: TasteEngine,
    private val cache: SongCache,
) {
    private val started = AtomicBoolean(false)

    /** Idempotent. Called from the first player hook, which runs early on both platforms. */
    fun start() {
        if (!started.compareAndSet(expectedValue = false, newValue = true)) return
        val scope = turtleScope.scope
        librarySync.start(scope)
        // Load history and build the taste profile now, so the first radio page is not ranked cold.
        scope.launch { runCatching { taste.refresh() } }
        taste.observe(scope)
        libraryContentSearchOverride = ::searchLibrary
        TurtleLog.info("runtime started")
    }

    /**
     * Orders upstream's radio candidates (already filtered for blocked and queued songs) with the
     * taste engine. Keeps upstream's page whenever we cannot do better in time.
     */
    suspend fun rankRadio(page: RadioQueuePage): RadioQueuePage {
        if (page.songs.size < 2) return page
        return try {
            val seed = observer.currentSong() ?: return page
            withTimeoutOrNull(RANK_TIMEOUT_MS) {
                cache.putAll(page.songs)
                val byId = page.songs.associateBy { it.id }
                val ranked = taste.rankExternal(seed.toTrackMeta(), page.songs.map { it.toTrackMeta() })
                val ordered = ranked.mapNotNull { item ->
                    item.track.youtubeId?.let(byId::get)?.also { observer.tag(it.id, item.surfaceDetail) }
                }
                page.copy(songs = ordered)
            } ?: page
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            page
        }
    }

    /**
     * Typo-tolerant, relevance-ranked library search replacing upstream's SQL `LIKE`. Returns null
     * (keep upstream's query) until our index has finished its first sync.
     */
    suspend fun searchLibrary(query: String, offset: Int): List<SongItem>? {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        if (!librarySync.isReady()) return null
        val hits = library.read { lexical, _ ->
            // History-only songs share the index, so over-fetch before keeping library songs.
            lexical.search(LexicalQuery.of(term, asYouType = false), limit = (offset + PAGE_SIZE) * 2, types = setOf(DocType.TRACK))
        }
        return hits.asSequence()
            .mapNotNull { (it.item as? LocalItem.Track)?.track }
            .filter { LocalSource.LIBRARY in it.sources }
            .drop(offset)
            .take(PAGE_SIZE)
            .mapNotNull { it.meta.youtubeId }
            .toList()
            .mapNotNull { cache.get(it) }
    }

    private companion object {
        const val RANK_TIMEOUT_MS = 1_500L
        const val PAGE_SIZE = 200
    }
}
