package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.CoListeningStore
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.time.TimeSource

/**
 * Runs the on-device co-listening graph: feeds it every radio page and finished play, rebuilds the
 * vectors the taste engine reads when it changed, and keeps it in our database across launches.
 * Songs it learned are put back into the song cache at startup, so shelves built from them stay
 * playable with their artwork.
 */
class CoListeningSync(
    private val graph: CoListeningGraph,
    private val store: CoListeningStore,
    private val db: SqlitePlayEventStore,
    private val events: PlayEventStore,
    private val cache: SongCache,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** A radio page the app loaded for [seed]. Never throws. */
    suspend fun observeRadio(seed: TrackMeta, page: List<SongItem>) {
        guarded("radio page") { graph.observeRadio(seed, page.map { it.toTrackMeta() }) }
    }

    fun start(scope: CoroutineScope, taste: TasteEngine) {
        scope.launch {
            guarded("load") { load() }
            var built = -1L
            var builtAt = TimeSource.Monotonic.markNow()
            var saved = graph.version()
            while (isActive) {
                val now = graph.version()
                // Rebuilding the vectors is the costly part, so it waits; the first data is used at once.
                val due = built < 0 || store.artistCount == 0 || builtAt.elapsedNow().inWholeMilliseconds >= REBUILD_INTERVAL_MS
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
                delay(SAVE_INTERVAL_MS)
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
