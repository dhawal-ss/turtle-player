package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.CoListeningStore
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * Runs the on-device co-listening graph: feeds it every radio page and finished play, rebuilds the
 * vectors the taste engine reads when it changed, and keeps it in our database across launches.
 * Songs it learned are put back into the song cache at startup, so shelves built from them stay
 * playable with their artwork. Radio pages are seeded by the listener's own songs, so they teach
 * nothing while listening history is paused.
 */
class CoListeningSync(
    private val graph: CoListeningGraph,
    private val store: CoListeningStore,
    private val db: SqlitePlayEventStore,
    private val events: PlayEventStore,
    private val cache: SongCache,
    /** False while listening history is paused: radio pages teach the graph nothing. */
    private val learning: () -> Boolean = { true },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** A radio page the app loaded for [seed]. Never throws. */
    suspend fun observeRadio(seed: TrackMeta, page: List<SongItem>) {
        if (!learning()) return
        guarded("radio page") { graph.observeRadio(seed, page.map { it.toTrackMeta() }) }
    }

    // Rebuilds asked for out of turn (artist picks), each completed once the loop has handled it.
    // A request a cancelled receive took but never delivered goes back in, so no caller waits forever.
    private val rebuildRequests: Channel<CompletableDeferred<Unit>> =
        Channel(Channel.UNLIMITED) { lost -> requeue(lost) }

    private fun requeue(request: CompletableDeferred<Unit>) {
        if (rebuildRequests.trySend(request).isFailure) request.complete(Unit)
    }

    /**
     * Rebuilds the vectors now, in the sync loop (never beside it), if the graph changed since the
     * last build; returns once that is done. Waits for [start]'s loop, so callers bound it with a timeout.
     */
    suspend fun rebuildNow() {
        val done = CompletableDeferred<Unit>()
        rebuildRequests.send(done)
        done.await()
    }

    fun start(scope: CoroutineScope, taste: TasteEngine) {
        scope.launch {
            guarded("load") { load() }
            var built = -1L
            var builtAt = TimeSource.Monotonic.markNow()
            var saved = graph.version()
            val requests = ArrayList<CompletableDeferred<Unit>>()
            while (isActive) {
                val now = graph.version()
                // Rebuilding the vectors is the costly part, so it waits; the first data is used at once.
                val due = requests.isNotEmpty() || built < 0 || store.artistCount == 0 || builtAt.elapsedNow().inWholeMilliseconds >= REBUILD_INTERVAL_MS
                if (now != built && due) {
                    guarded("rebuild") {
                        store.rebuild()
                        taste.embeddingsChanged()
                        TurtleLog.info("co-listening: ${store.artistCount} artists, ${store.trackCount} songs")
                    }
                    built = now
                    builtAt = TimeSource.Monotonic.markNow()
                }
                // Saved often: phones kill the app at any time, and what was learned since the last save is lost.
                if (now != saved) {
                    guarded("save") { save() }
                    saved = now
                }
                requests.forEach { it.complete(Unit) }
                requests.clear()
                // Sleeps until the next save, or until a rebuild is asked for.
                withTimeoutOrNull(SAVE_INTERVAL_MS) { rebuildRequests.receive() }?.let(requests::add)
                while (true) requests += rebuildRequests.tryReceive().getOrNull() ?: break
            }
        }
        scope.launch { events.newEvents.collect { guarded("play") { graph.observePlay(it) } } }
    }

    private suspend fun load() {
        val (links, songJson) = db.loadCoListening()
        if (links.isEmpty() && songJson.isEmpty()) return
        val songs = songJson.mapNotNull { runCatching { json.decodeFromString(SongItem.serializer(), it) }.getOrNull() }
        cache.putAll(songs)
        graph.load(
            CoListeningGraph.Data(
                edges = links.map { (a, b, w) -> CoListeningGraph.Edge(ArtistKey(a), ArtistKey(b), w) },
                tracks = songs.map { it.toTrackMeta() },
            ),
        )
        TurtleLog.info("co-listening: restored ${links.size} links, ${songs.size} songs")
    }

    private suspend fun save() {
        val data = graph.export()
        val songs = data.tracks.mapNotNull { t ->
            val id = t.youtubeId ?: return@mapNotNull null
            cache.get(id)?.let { id to json.encodeToString(SongItem.serializer(), it) }
        }
        db.saveCoListening(data.edges.map { Triple(it.a.value, it.b.value, it.weight) }, songs)
    }

    private inline fun guarded(what: String, block: () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("co-listening: $what failed", e)
        }
    }

    private companion object {
        /** Rebuilding re-reads listening history for the profile, so not more often than this. */
        const val REBUILD_INTERVAL_MS = 15 * 60_000L
        const val SAVE_INTERVAL_MS = 60_000L
    }
}
