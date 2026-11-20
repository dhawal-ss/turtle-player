package app.turtleplayer.search

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The local indexes behind one lock. The app bridge feeds it library, history and cached catalog
 * tracks plus lyrics; search reads it.
 */
class LocalLibrary(
    private val lexical: LexicalIndex = LexicalIndex(),
    private val lyrics: LyricsIndex = LyricsIndex(),
) {
    private val mutex = Mutex()

    suspend fun upsert(track: TrackMeta, sources: Set<LocalSource> = setOf(LocalSource.LIBRARY)) = mutex.withLock { lexical.upsert(track, sources) }

    suspend fun upsertAll(tracks: Iterable<TrackMeta>, sources: Set<LocalSource> = setOf(LocalSource.LIBRARY)) =
        mutex.withLock { lexical.upsertAll(tracks, sources) }

    suspend fun remove(key: TrackKey) = mutex.withLock {
        lyrics.remove(key)
        lexical.remove(key)
    }

    suspend fun removeSource(key: TrackKey, source: LocalSource) = mutex.withLock {
        lexical.removeSource(key, source)
        if (lexical.track(key) == null) lyrics.remove(key)
    }

    suspend fun putLyrics(track: TrackMeta, text: String) = mutex.withLock { lyrics.put(track, text) }

    suspend fun removeLyrics(key: TrackKey) = mutex.withLock { lyrics.remove(key) }

    /** Runs [block] with exclusive access to the indexes. Keep it short: it blocks writers. */
    suspend fun <T> read(block: (LexicalIndex, LyricsIndex) -> T): T = mutex.withLock { block(lexical, lyrics) }
}

data class SearchConfig(
    /** How long the first emission waits for local results. Local work is never discarded; late results arrive in a later emission. */
    val localDeadline: Duration = 30.milliseconds,
    /** Hard deadlines: a stage that misses it is cancelled and reported as TIMED_OUT. */
    val semanticDeadline: Duration = 120.milliseconds,
    val onlineDeadline: Duration = 600.milliseconds,
    val localLimit: Int = 60,
    val lyricsLimit: Int = 10,
    val semanticLimit: Int = 30,
    val onlineLimit: Int = 30,
    /** Online search starts once the query has this many characters. */
    val minOnlineChars: Int = 2,
    val groups: GroupLimits = GroupLimits(),
    val weights: RankingWeights = RankingWeights(),
)

/**
 * One search box: understands the query, runs local, semantic and online retrieval in parallel
 * with their own deadlines, and merges them into one ranked, deduplicated, grouped result set.
 */
class SearchEngine(
    private val library: LocalLibrary,
    private val online: OnlineSearchProvider = OnlineSearchProvider.None,
    private val semantic: SemanticRetriever = SemanticRetriever.None,
    affinity: AffinityProvider = AffinityProvider.None,
    private val config: SearchConfig = SearchConfig(),
    /** Replaceable by a learned classifier. Its lexicons come from the library index. */
    private val classifier: IntentClassifier = RuleBasedIntentClassifier(),
    /** Extra known artists (for example from imported history) on top of the library's own. */
    private val extraArtists: NameLexicon = NameLexicon.Empty,
    /** Where CPU-bound local retrieval runs. */
    private val cpuDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val merger = ResultMerger(config.weights, affinity, config.groups)

    /** Parses [raw] against the current library lexicons. */
    suspend fun parse(raw: String): ParsedQuery = library.read { index, _ ->
        val own = index.lexicons()
        val artists = if (extraArtists === NameLexicon.Empty) own.artists else NameLexicon { t, p ->
            listOfNotNull(own.artists.lookup(t, p), extraArtists.lookup(t, p)).maxByOrNull { it.quality }
        }
        QueryParser(QueryLexicons(artists, own.albums, own.titles), classifier).parse(raw)
    }

    /**
     * Emits local results first, then an updated merged result each time another stage finishes.
     * The last emission has [SearchResults.isFinal] set. Collect with `collectLatest` per keystroke.
     */
    fun search(raw: String): Flow<SearchResults> = channelFlow {
        val query = parse(raw)
        val statuses = LinkedHashMap<Retriever, RetrieverStatus>()
        if (query.isBlank) {
            Retriever.entries.forEach { statuses[it] = RetrieverStatus.SKIPPED }
            send(SearchResults.empty(query, statuses.toMap()))
            return@channelFlow
        }
        val runOnline = online.enabled && query.raw.trim().length >= config.minOnlineChars
        statuses[Retriever.LOCAL] = RetrieverStatus.PENDING
        statuses[Retriever.SEMANTIC] = if (semantic.enabled) RetrieverStatus.PENDING else RetrieverStatus.SKIPPED
        statuses[Retriever.ONLINE] = if (runOnline) RetrieverStatus.PENDING else RetrieverStatus.SKIPPED

        val outcomes = Channel<Outcome>(Channel.UNLIMITED)
        val localDone = CompletableDeferred<Unit>()
        var running = 1

        launch(cpuDispatcher) {
            outcomes.send(stage(Retriever.LOCAL, deadline = null) { retrieveLocal(query) })
            localDone.complete(Unit)
        }
        if (semantic.enabled) {
            running++
            launch { outcomes.send(stage(Retriever.SEMANTIC, config.semanticDeadline) { semantic.retrieve(query, config.semanticLimit) }) }
        }
        if (runOnline) {
            running++
            launch { outcomes.send(stage(Retriever.ONLINE, config.onlineDeadline) { online.search(query, config.onlineLimit) }) }
        }

        var local: LocalRetrieval? = null
        var semanticHits: List<SemanticHit>? = null
        var onlineResults: OnlineResults? = null
        @Suppress("UNCHECKED_CAST")
        fun apply(o: Outcome) {
            running--
            statuses[o.retriever] = o.status
            when (o.retriever) {
                Retriever.LOCAL -> local = o.value as LocalRetrieval?
                Retriever.SEMANTIC -> semanticHits = o.value as List<SemanticHit>?
                Retriever.ONLINE -> onlineResults = o.value as OnlineResults?
            }
        }
        suspend fun emit() = send(merger.merge(query, RetrievalSnapshot(local, semanticHits, onlineResults), statuses.toMap()))

        // First emission: local results (or whatever is ready once the local budget runs out).
        withTimeoutOrNull(config.localDeadline) { localDone.await() }
        while (true) apply(outcomes.tryReceive().getOrNull() ?: break)
        emit()
        while (running > 0) {
            apply(outcomes.receive())
            emit()
        }
    }

    private class Outcome(val retriever: Retriever, val status: RetrieverStatus, val value: Any?)

    private suspend fun stage(retriever: Retriever, deadline: Duration?, block: suspend () -> Any): Outcome = try {
        val value = if (deadline == null) block() else withTimeoutOrNull(deadline) { block() }
        if (value == null) Outcome(retriever, RetrieverStatus.TIMED_OUT, null) else Outcome(retriever, RetrieverStatus.DONE, value)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome(retriever, RetrieverStatus.FAILED, null)
    }

    private suspend fun retrieveLocal(query: ParsedQuery): LocalRetrieval = library.read { index, lyrics ->
        val lexical = index.search(query, config.localLimit)
        val lyricHits = if (query.tokens.size >= LyricsIndex.MIN_FRAGMENT_TOKENS) {
            lyrics.search(query.tokens, lastIsPrefix = query.typedToken != null, limit = config.lyricsLimit)
        } else {
            emptyList()
        }
        LocalRetrieval(lexical, lyricHits)
    }
}
