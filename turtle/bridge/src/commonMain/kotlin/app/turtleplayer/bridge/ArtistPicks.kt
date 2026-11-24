package app.turtleplayer.bridge

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.turtleplayer.core.ArtistKey
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.taste.TasteEngine
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An artist the listener picked to start from, kept with its YouTube id and artwork for the picks sheet. */
data class PickedArtist(
    val id: String,
    val title: String,
    val thumbnail: String?,
)

/**
 * Artist picks for a young profile: [sparse] while history is too thin to rank on, the artists
 * [picked] so far, whether the listener [dismissed] the Home card, and whether listening history is
 * paused ([learningPaused]: picks are kept, but nothing related to them can be learned).
 */
data class ArtistPicksState(
    val sparse: Boolean,
    val picked: List<PickedArtist>,
    val dismissed: Boolean,
    val learningPaused: Boolean,
) {
    /** Home offers the picks: a young profile that has neither picked nor skipped. */
    val offer: Boolean get() = sparse && picked.isEmpty() && !dismissed
}

/** What artist picks read from YouTube: artist search, an artist's top songs, and a song's radio. */
internal interface ArtistPicksSource {
    suspend fun searchArtists(query: String): List<ArtistItem>

    suspend fun topSongs(artist: ArtistItem): List<SongItem>

    suspend fun radio(videoId: String): List<SongItem>
}

/** [ArtistPicksSource] on upstream's repository. Offline, search finds nothing instead of throwing. */
internal class YouTubeArtistPicksSource(
    private val repository: () -> YouTubeRepository = { GlobalYouTubeRepository.instance },
) : ArtistPicksSource {
    override suspend fun searchArtists(query: String): List<ArtistItem> =
        try {
            repository().searchFiltered(query, SearchFilter.ARTIST, allowOfflineFallback = false).items.filterIsInstance<ArtistItem>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // searchFiltered refuses with check(...) when the network is down.
            emptyList()
        }

    override suspend fun topSongs(artist: ArtistItem): List<SongItem> {
        val page = repository().getArtist(artist.id)
        val top = page.topTracksSection?.let(page.sections::get).orEmpty().filterIsInstance<SongItem>()
        if (top.isNotEmpty()) return top
        // Some artist pages have no top-songs shelf (thin topic channels): their songs by name instead.
        val key = ArtistKey.of(artist.title)
        return try {
            repository()
                .searchFiltered(artist.title, SearchFilter.SONG, allowOfflineFallback = false)
                .items
                .filterIsInstance<SongItem>()
                .filter { song -> song.artists.any { ArtistKey.of(it.name) == key } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            emptyList()
        }
    }

    override suspend fun radio(videoId: String): List<SongItem> = repository().startRadio(videoId)
}

/**
 * Skippable, editable artist picks: a young profile names a few artists, and Made for you builds
 * from them until listening history takes over. Picking writes no plays and no likes, so it is fully
 * reversible ([clear]). Each pick's top songs and their radio teach the co-listening graph (unless
 * listening history is paused), which is what lets the onboarding mixes find related music.
 */
class TurtleArtistPicks internal constructor(
    private val taste: TasteEngine,
    private val store: SqlitePlayEventStore,
    private val cache: SongCache,
    private val coListening: CoListeningSync,
    /** Waits for the saved taste state: a change made before it is read would be overwritten. */
    private val awaitRestored: suspend () -> Unit,
    /** Saves the taste state (the runtime's saveTasteState). */
    private val saveTaste: suspend () -> Unit,
    /** Rebuilds Home's Turtle shelves and re-scores its rows. */
    private val refreshHome: suspend () -> Unit,
    private val source: ArtistPicksSource = YouTubeArtistPicksSource(),
    private val learningPaused: () -> Boolean = ::listenHistoryPaused,
) {
    private val lock = Mutex()

    /** Null until first read. Compose state: read it during composition and Home follows changes. */
    var state: ArtistPicksState? by mutableStateOf(null)
        private set

    /** Reads the state after the saved taste state is back, then again after every finished play (conflated). */
    fun start(
        scope: CoroutineScope,
        events: Flow<PlayEvent>,
    ) {
        scope.launch {
            awaitRestored()
            refresh()
            events.filter { it.isFinished }.conflate().collect { refresh() }
        }
    }

    /** Reads the state again (the sheet opening, a history pause changing). */
    suspend fun refresh() {
        val sparse =
            try {
                taste.coldStart()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TurtleLog.error("artist picks: could not read the profile", e)
                false
            }
        state = ArtistPicksState(sparse, readPicks(), pref(PREF_DISMISSED) == "1", learningPaused())
    }

    /** Artists matching [query]; empty offline or on failure. */
    suspend fun search(query: String): List<ArtistItem> {
        if (query.isBlank()) return emptyList()
        return try {
            source.searchArtists(query.trim()).distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("artist picks: search failed", e)
            emptyList()
        }
    }

    /**
     * Saves [picks] (at most [MAX_PICKS]) as the listener's starting artists, replacing earlier ones:
     * learns each one's songs and neighbours, rebuilds the vectors, then Home's mixes.
     */
    suspend fun save(picks: List<ArtistItem>) {
        val chosen = picks.distinctBy { it.id }.take(MAX_PICKS)
        awaitRestored()
        lock.withLock {
            writePicks(chosen.map { PickedArtist(it.id, it.title, it.thumbnail) })
            for (artist in chosen) learnFrom(artist)
            taste.onboard(chosen.map { ArtistKey.of(it.title) })
            saveTaste()
            // In the sync loop, never beside it; bounded, as the loop may not run (tests, a failed start).
            withTimeoutOrNull(REBUILD_WAIT_MS) { coListening.rebuildNow() }
            // A daily mix kept for today would otherwise hide the picks until tomorrow.
            taste.refreshShelves()
            refreshHomeSafely()
            refresh()
        }
        TurtleLog.info("artist picks: saved ${chosen.size}")
    }

    /** "Skip" on Home's card: it does not come back (Turtle settings still open the picks). */
    suspend fun skip() {
        lock.withLock {
            setPref(PREF_DISMISSED, "1")
            refresh()
        }
    }

    /**
     * Forgets the picks. What the co-listening graph learned from them stays: it is how artists relate,
     * not the listener's history. Plays and likes were never written, so nothing else changes.
     */
    suspend fun clear() {
        awaitRestored()
        lock.withLock {
            taste.onboard(emptyList())
            saveTaste()
            writePicks(emptyList())
            taste.refreshShelves()
            refreshHomeSafely()
            refresh()
        }
    }

    /** An artist's top songs go in the song cache and the graph, and their radio links the artist to its neighbours. */
    private suspend fun learnFrom(artist: ArtistItem) {
        try {
            val top = source.topSongs(artist).filterNot { it.isUnavailable }
            val seed = top.firstOrNull() ?: return
            cache.putAll(top)
            val seedMeta = seed.toTrackMeta()
            coListening.observeRadio(seedMeta, top)
            val radio = source.radio(seed.id).filter { it.id != seed.id }
            cache.putAll(radio)
            coListening.observeRadio(seedMeta, radio)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One artist failing (offline, a removed channel) must not stop the others.
            TurtleLog.error("artist picks: could not learn from ${artist.id}", e)
        }
    }

    private suspend fun refreshHomeSafely() {
        try {
            refreshHome()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("artist picks: could not refresh Home", e)
        }
    }

    private suspend fun readPicks(): List<PickedArtist> {
        val raw = pref(PREF_PICKS)?.takeIf { it.isNotBlank() } ?: return emptyList()
        return try {
            Json.parseToJsonElement(raw).jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                val id = o["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                val title = o["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                PickedArtist(id, title, o["thumbnail"]?.jsonPrimitive?.contentOrNull)
            }
        } catch (e: Exception) {
            TurtleLog.error("artist picks: saved picks unreadable", e)
            emptyList()
        }
    }

    private suspend fun writePicks(picks: List<PickedArtist>) {
        val json =
            JsonArray(
                picks.map {
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(it.id),
                            "title" to JsonPrimitive(it.title),
                            "thumbnail" to (it.thumbnail?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    )
                },
            )
        setPref(PREF_PICKS, if (picks.isEmpty()) "" else json.toString())
    }

    private suspend fun pref(key: String): String? = runCatching { store.pref(key) }.getOrNull()

    private suspend fun setPref(
        key: String,
        value: String,
    ) {
        runCatching { store.setPref(key, value) }.onFailure { TurtleLog.error("artist picks: could not save $key", it) }
    }

    companion object {
        /** At most this many artists: each costs about two YouTube calls. */
        const val MAX_PICKS = 5
        private const val PREF_PICKS = "artist_picks"
        private const val PREF_DISMISSED = "artist_picks_dismissed"
        private const val REBUILD_WAIT_MS = 10_000L
    }
}
