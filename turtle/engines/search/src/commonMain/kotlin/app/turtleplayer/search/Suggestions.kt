package app.turtleplayer.search

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TextNormalizer
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.PlayEventStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

data class SearchHistoryEntry(val query: String, val lastUsedMs: Long, val count: Int = 1)

/** Searches the user committed (pressed enter or played a result). */
interface SearchHistory {
    suspend fun record(query: String)

    /** Most recent first. */
    suspend fun recent(limit: Int): List<SearchHistoryEntry>

    suspend fun remove(query: String)

    suspend fun clear()
}

/** Storage for [InMemorySearchHistory]; the app implements it on its own database or settings. */
interface SearchHistoryPersistence {
    suspend fun load(): List<SearchHistoryEntry>

    suspend fun save(entries: List<SearchHistoryEntry>)
}

/**
 * Bounded, deduplicated (by normalized text) search history kept in memory, loaded lazily from
 * and written through to an optional [SearchHistoryPersistence].
 */
class InMemorySearchHistory(
    private val clock: Clock = Clock.System,
    private val capacity: Int = 100,
    private val persistence: SearchHistoryPersistence? = null,
) : SearchHistory {
    private val mutex = Mutex()
    private var loaded = persistence == null
    private val entries = ArrayList<SearchHistoryEntry>() // most recent first

    override suspend fun record(query: String) {
        val trimmed = query.trim()
        val key = TextNormalizer.key(trimmed)
        if (key.isEmpty()) return
        mutate {
            val existing = entries.indexOfFirst { TextNormalizer.key(it.query) == key }
            val count = if (existing >= 0) entries.removeAt(existing).count + 1 else 1
            entries.add(0, SearchHistoryEntry(trimmed, clock.nowMs(), count))
            while (entries.size > capacity) entries.removeAt(entries.lastIndex)
        }
    }

    override suspend fun recent(limit: Int): List<SearchHistoryEntry> = mutex.withLock {
        ensureLoaded()
        entries.take(limit)
    }

    override suspend fun remove(query: String) {
        val key = TextNormalizer.key(query)
        mutate { entries.removeAll { TextNormalizer.key(it.query) == key } }
    }

    override suspend fun clear() = mutate { entries.clear() }

    private suspend fun mutate(block: () -> Unit) {
        val snapshot = mutex.withLock {
            ensureLoaded()
            block()
            entries.toList()
        }
        persistence?.save(snapshot)
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        entries += persistence!!.load().sortedByDescending { it.lastUsedMs }.take(capacity)
        loaded = true
    }
}

enum class SuggestionKind { RECENT_SEARCH, RECENTLY_PLAYED, LIBRARY_TRACK, LIBRARY_ARTIST, LIBRARY_ALBUM, ONLINE }

/**
 * One suggestion row. [text] is data (a past query, a title, a name or an online completion),
 * never UI copy; the UI decides how to label each [kind].
 */
data class Suggestion(
    val text: String,
    val kind: SuggestionKind,
    val track: TrackMeta? = null,
    val artist: ArtistKey? = null,
    val albumKey: String? = null,
)

data class SuggestionConfig(
    val recentSearches: Int = 5,
    val recentlyPlayed: Int = 5,
    val library: Int = 5,
    val online: Int = 6,
    val recentlyPlayedWindowMs: Long = 14 * DAY_MS,
    val onlineDeadline: Duration = 600.milliseconds,
    val minOnlineChars: Int = 2,
)

/**
 * As-you-type suggestions, personal first: matching recent searches and library/history items,
 * then online completions. An empty box shows recent searches and recently played tracks.
 */
class SearchSuggester(
    private val library: LocalLibrary,
    private val history: SearchHistory,
    private val online: OnlineSearchProvider = OnlineSearchProvider.None,
    private val events: PlayEventStore? = null,
    private val clock: Clock = Clock.System,
    private val config: SuggestionConfig = SuggestionConfig(),
) {
    /** Emits personal suggestions immediately, then personal + online when online completions arrive. */
    fun suggest(raw: String): Flow<List<Suggestion>> = flow {
        val tokens = TextNormalizer.normalize(raw).tokens
        if (tokens.isEmpty()) {
            emit(emptyBox())
            return@flow
        }
        val typing = raw.isNotEmpty() && !raw.last().isWhitespace()
        val personal = recentMatches(tokens, typing) + libraryMatches(raw)
        emit(personal)
        if (!online.enabled || raw.trim().length < config.minOnlineChars) return@flow
        val completions = try {
            withTimeoutOrNull(config.onlineDeadline) { online.suggestions(raw) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        val seen = personal.mapTo(HashSet()) { TextNormalizer.key(it.text) }
        val extra = completions.filter { seen.add(TextNormalizer.key(it)) }.take(config.online).map { Suggestion(it, SuggestionKind.ONLINE) }
        if (extra.isNotEmpty()) emit(personal + extra)
    }

    private suspend fun emptyBox(): List<Suggestion> {
        val searches = history.recent(config.recentSearches).map { Suggestion(it.query, SuggestionKind.RECENT_SEARCH) }
        val played = events?.let { store ->
            store.since(clock.nowMs() - config.recentlyPlayedWindowMs)
                .asReversed()
                .filter { !it.isQuickSkip }
                .distinctBy { it.trackKey }
                .take(config.recentlyPlayed)
                .map { Suggestion(it.track.title, SuggestionKind.RECENTLY_PLAYED, track = it.track) }
        }.orEmpty()
        return searches + played
    }

    /** Past queries whose tokens cover the typed tokens in order (typo tolerant, last token as prefix). */
    private suspend fun recentMatches(tokens: List<String>, typing: Boolean): List<Suggestion> {
        val matchers = tokens.mapIndexed { i, t -> TermMatcher(t, typing && i == tokens.lastIndex) }
        return history.recent(Int.MAX_VALUE).asSequence()
            .filter { entry ->
                val words = TextNormalizer.normalize(entry.query).tokens
                var from = 0
                matchers.all { m ->
                    val at = (from until words.size).firstOrNull { m.match(words[it])?.let { r -> r.kind != MatchKind.INFIX } == true }
                    if (at != null) from = at + 1
                    at != null
                }
            }
            .take(config.recentSearches)
            .map { Suggestion(it.query, SuggestionKind.RECENT_SEARCH) }
            .toList()
    }

    private suspend fun libraryMatches(raw: String): List<Suggestion> = library.read { index, _ ->
        index.search(LexicalQuery.of(raw, asYouType = raw.isNotEmpty() && !raw.last().isWhitespace()), limit = config.library * 2)
            .sortedByDescending { hit ->
                // Things the user played rank above things merely saved.
                val played = (hit.item as? LocalItem.Track)?.track?.sources?.contains(LocalSource.HISTORY) == true
                hit.score + if (played) 0.05f else 0f
            }
            .take(config.library)
            .map { hit ->
                when (val item = hit.item) {
                    is LocalItem.Track -> Suggestion(item.track.meta.title, SuggestionKind.LIBRARY_TRACK, track = item.track.meta)
                    is LocalItem.Artist -> Suggestion(item.artist.name, SuggestionKind.LIBRARY_ARTIST, artist = item.artist.key)
                    is LocalItem.Album -> Suggestion(item.album.title, SuggestionKind.LIBRARY_ALBUM, albumKey = item.album.key)
                }
            }
    }
}
