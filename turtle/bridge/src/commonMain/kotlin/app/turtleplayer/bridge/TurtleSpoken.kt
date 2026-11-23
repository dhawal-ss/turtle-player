package app.turtleplayer.bridge

import app.turtleplayer.core.Clock
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.api.playlistSongs
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.PodcastItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.toSongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.context.GlobalContext
import kotlin.concurrent.Volatile

/**
 * Audiobook shelves. YouTube Music has no audiobook catalog, but YouTube hosts many full readings
 * (long uploads and chapter playlists); each category is a search through upstream's repository.
 * The queries are search terms, not UI text.
 */
enum class AudiobookCategory(internal val query: String) {
    POPULAR("full audiobook"),
    CLASSICS("classic novel full audiobook"),
    FICTION("novel full audiobook"),
    MYSTERY("mystery thriller full audiobook"),
    SCIFI("science fiction fantasy full audiobook"),
    SELF_HELP("self help full audiobook"),
    BIOGRAPHY("biography memoir full audiobook"),
    HISTORY("history full audiobook"),
    KIDS("children's stories full audiobook"),
}

/** A full audiobook: one long upload ([video]) or a playlist of chapters ([playlistId]). */
class SpokenBook(
    val id: String,
    val title: String,
    val author: String?,
    val thumbnail: String?,
    /** Length of a single-upload book, in seconds. */
    val durationSec: Long?,
    val playlistId: String?,
    /** Upstream's count text for a playlist ("24 videos"), shown as is. */
    val partsText: String?,
    val video: SongItem?,
)

/** Where the listener stopped in a book: the chapter (a video id) and the position in it. */
class BookProgress(
    val bookId: String,
    val title: String,
    val author: String?,
    val thumbnail: String?,
    val playlistId: String?,
    val chapterId: String,
    val chapterIndex: Int,
    val chapterCount: Int,
    val positionMs: Long,
    val durationMs: Long,
    val updatedMs: Long,
) {
    /** Share of the whole book heard, 0..1 (chapters count equally). */
    val fraction: Float
        get() {
            val inChapter = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
            val count = chapterCount.coerceAtLeast(1)
            return ((chapterIndex.coerceIn(0, count - 1) + inChapter) / count).coerceIn(0f, 1f)
        }

    /** The book again, for playing it from Continue listening. */
    fun toBook(): SpokenBook {
        val video = if (playlistId == null) chapterSong() else null
        return SpokenBook(bookId, title, author, thumbnail, durationMs.takeIf { it > 0 }?.div(1000), playlistId, null, video)
    }

    /** A playable stand-in for the saved chapter (single-upload books). */
    fun chapterSong(): SongItem =
        SongItem(
            id = chapterId,
            title = title,
            thumbnail = thumbnail,
            artists = listOfNotNull(author?.let { ArtistTiny(null, it) }),
            album = null,
            duration = durationMs.takeIf { it > 0 }?.div(1000),
        )

    internal fun copyWith(chapterId: String, chapterIndex: Int, chapterCount: Int, positionMs: Long, durationMs: Long, updatedMs: Long) =
        BookProgress(bookId, title, author, thumbnail, playlistId, chapterId, chapterIndex, chapterCount, positionMs, durationMs, updatedMs)
}

/**
 * Spoken word for Shell: podcast shelves (new episodes, continue listening, followed and popular
 * shows) and audiobooks (catalog by category, per-book resume). Podcast episodes already resume
 * through upstream (`PodcastLibrary`); books are ordinary videos, so their positions are ours and
 * live in our database through the UI-preference store.
 *
 * Book registration and progress updates are meant for the main thread (Shell's player tracker);
 * network and database reads are suspend functions that fail soft to empty results.
 */
object TurtleSpoken {
    /** Single uploads shorter than this aren't full books (and summaries usually are). */
    const val MIN_BOOK_SECONDS = 45 * 60L

    private const val PREF_BOOKS = "spoken_books"
    private const val PREF_SPEED = "spoken_speed"
    private const val MAX_BOOKS = 40
    private const val FIELD = '\u001F'
    private val BOOK_WORDS = listOf("audiobook", "audio book", "audio-book", "librivox", "unabridged", "full book")
    private val NOT_BOOK_WORDS = listOf("summary", "sample", "trailer", "preview", "review", "reaction")

    private val repository: () -> YouTubeRepository = { GlobalYouTubeRepository.instance }
    private val writer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadLock = Mutex()
    private val writeLock = Mutex()
    private var loaded = false

    private val catalog = HashMap<AudiobookCategory, List<SpokenBook>>()
    private var popularShowsCache: List<PodcastItem>? = null

    private val booksState = MutableStateFlow<List<BookProgress>>(emptyList())

    /** Books in progress, most recently heard first. */
    val books: StateFlow<List<BookProgress>> = booksState.asStateFlow()

    // Read-only maps replaced on each write: the player observer reads them off the main thread.
    /** Chapter video id → book id, for every book opened or saved. */
    @Volatile
    private var chapterBooks: Map<String, String> = emptyMap()

    /** Book id → its chapter video ids, in order. */
    @Volatile
    private var bookChapters: Map<String, List<String>> = emptyMap()

    // ---------------------------------------------------------------------------------------------
    // Podcasts
    // ---------------------------------------------------------------------------------------------

    /** Shows the listener follows: YouTube Music's library (signed in) plus upstream's local bookmarks. */
    suspend fun followedShows(): List<PodcastItem> = coroutineScope {
        val remote = async { soft { repository().getLibraryPodcasts(limit = 50) }.orEmpty() }
        val local = async {
            soft {
                database()?.dao()?.subscribedPodcasts()?.first()?.map {
                    PodcastItem(id = it.id, title = it.title, thumbnail = it.thumbnailUrl, author = it.author, channelId = it.channelId)
                }
            }.orEmpty()
        }
        (remote.await() + local.await()).distinctBy { it.id }
    }

    /**
     * New episodes from followed shows: YouTube Music's "New episodes" feed when signed in, otherwise
     * the latest episode of each of the first few [followed] shows.
     */
    suspend fun newEpisodes(followed: List<PodcastItem>): List<SongItem> {
        val feed = soft { repository().getNewPodcastEpisodes() }.orEmpty()
        if (feed.isNotEmpty()) return feed.distinctBy { it.id }.take(24)
        return coroutineScope {
            followed.take(8).map { show ->
                async { soft { repository().getLatestPodcastEpisode(show.id) }?.toSongItem(show.title) }
            }.awaitAll().filterNotNull()
        }
    }

    /**
     * Episodes started but not finished, from upstream's saved positions. Each carries its position
     * in [SongItem.playbackPositionMs], so a card can show how much is left.
     */
    suspend fun continueEpisodes(): List<SongItem> {
        val db = database() ?: return emptyList()
        return soft {
            val rows = db.dao().episodeListeningProgress().first()
                .filter { (it.playbackPosition ?: 0L) > 0L && !it.isComplete() }
            // The query has no time order; newer rows come last.
            val ids = rows.map { it.id }.asReversed().take(20)
            val positions = rows.associate { it.id to it.playbackPosition }
            val songs = db.dao().getSongsByIds(ids).associateBy { it.id }
            ids.mapNotNull { id -> songs[id]?.toSongItem()?.copy(playbackPositionMs = positions[id]) }
        }.orEmpty()
    }

    /**
     * Shows to discover (cached for the session): the top results of several topic searches,
     * interleaved, so the shelf leads with the biggest show in each topic. A single "popular"
     * search matches shows with "popular" in their title instead.
     */
    suspend fun popularShows(): List<PodcastItem> {
        popularShowsCache?.let { return it }
        val topics = listOf("comedy", "true crime", "news", "interview", "technology", "sports", "business", "health", "history", "music")
        val perTopic = coroutineScope {
            topics.map { topic ->
                async { soft { repository().searchFiltered("$topic podcast", SearchFilter.PODCAST).items }.orEmpty().filterIsInstance<PodcastItem>().take(4) }
            }.awaitAll()
        }
        val found = (0 until 4).flatMap { rank -> perTopic.mapNotNull { it.getOrNull(rank) } }.distinctBy { it.id }
        if (found.isNotEmpty()) popularShowsCache = found
        return found
    }

    // ---------------------------------------------------------------------------------------------
    // Audiobooks
    // ---------------------------------------------------------------------------------------------

    /** Full audiobooks for [category]: long uploads and chapter playlists, interleaved (cached per session). */
    suspend fun audiobooks(category: AudiobookCategory, refresh: Boolean = false): List<SpokenBook> {
        if (!refresh) catalog[category]?.let { return it }
        val (videos, playlists) = coroutineScope {
            val v = async { soft { repository().searchFiltered(category.query, SearchFilter.VIDEO).items }.orEmpty() }
            val p = async { soft { repository().searchFiltered(category.query, SearchFilter.COMMUNITY_PLAYLIST).items }.orEmpty() }
            v.await() to p.await()
        }
        val singles = videos.filterIsInstance<SongItem>()
            .filter { !it.isPodcast && looksLikeBook(it.title) && (it.duration ?: 0L) >= MIN_BOOK_SECONDS }
            .map { SpokenBook(it.id, it.title, it.artists.firstOrNull()?.name, it.thumbnail, it.duration, null, null, it) }
        val sets = playlists.filterIsInstance<PlaylistItem>()
            .filter { looksLikeBook(it.title) }
            .map { SpokenBook(it.id, it.title, it.author, it.thumbnail, null, it.id, it.songCount, null) }
        val mixed = buildList {
            val n = maxOf(singles.size, sets.size)
            for (i in 0 until n) {
                singles.getOrNull(i)?.let { add(it) }
                sets.getOrNull(i)?.let { add(it) }
            }
        }.distinctBy { it.id }.distinctBy { bookKey(it.title) }
        if (mixed.isNotEmpty()) catalog[category] = mixed
        return mixed
    }

    /** The same book uploaded twice ("As a Man Thinketh by James Allen | Full Audiobook" and "... (Slightly Modernized)") shows once. */
    private fun bookKey(title: String): String =
        title.lowercase().split('|', '(', '[', '-', '–', ':').first()
            .replace(Regex("\\b(full|unabridged|audiobook|audio book|by .*)\\b"), " ")
            .replace(Regex("[^a-z0-9 ]"), " ").split(' ').filter { it.isNotBlank() }.take(5).joinToString(" ")
            .ifBlank { title.lowercase() }

    /** The book's chapters in order: the upload itself, or the playlist's videos. */
    suspend fun chapters(book: SpokenBook): List<SongItem> {
        book.video?.let { return listOf(it) }
        val id = book.playlistId ?: return emptyList()
        return soft { repository().getPlaylist(id).playlistSongs() }.orEmpty().filter { !it.isUnavailable }
    }

    private fun looksLikeBook(title: String): Boolean {
        val t = title.lowercase()
        return BOOK_WORDS.any { it in t } && NOT_BOOK_WORDS.none { it in t }
    }

    // ---------------------------------------------------------------------------------------------
    // Resume
    // ---------------------------------------------------------------------------------------------

    /** Restores saved book positions once; safe to call often. */
    suspend fun load() {
        loadLock.withLock {
            if (loaded) return
            restore(soft { TurtleBridge.uiPref(PREF_BOOKS) })
            loaded = true
        }
    }

    /** Merges the saved books ([raw], the stored preference) into memory, with every saved chapter of each. */
    internal fun restore(raw: String?) {
        val restored = raw?.lineSequence()?.mapNotNull { decodeLine(it) }?.toList().orEmpty()
        // Anything registered before the load finished wins over the stored copy.
        val current = booksState.value
        booksState.value = (current + restored.map { it.first }.filter { r -> current.none { it.bookId == r.bookId } })
            .sortedByDescending { it.updatedMs }
        val books = chapterBooks.toMutableMap()
        val lists = bookChapters.toMutableMap()
        restored.forEach { (progress, chapters) ->
            books.getOrPut(progress.chapterId) { progress.bookId }
            if (chapters.isNotEmpty()) {
                lists.getOrPut(progress.bookId) { chapters }
                chapters.forEach { books.getOrPut(it) { progress.bookId } }
            }
        }
        chapterBooks = books
        bookChapters = lists
    }

    /** The book a playing video belongs to, or null when it isn't one we opened. */
    fun bookOf(songId: String): String? = chapterBooks[songId]

    /**
     * Spoken word is never music taste: podcast episodes (show id or episode video type) and chapters
     * of books Turtle opened; local books and podcasts join in W8.
     */
    fun isSpokenWord(song: SongItem, isBookChapter: (String) -> Boolean = { bookOf(it) != null }): Boolean =
        song.isPodcast || isBookChapter(song.id)

    /** Saved progress for [bookId], if any. */
    fun progressOf(bookId: String): BookProgress? = booksState.value.firstOrNull { it.bookId == bookId }

    /**
     * Registers [book]'s [chapters] so playback of any of them updates the book, and returns where to
     * start: the saved chapter index and position, or the beginning.
     */
    fun open(book: SpokenBook, chapters: List<SongItem>): Pair<Int, Long> {
        val ids = chapters.map { it.id }
        val known = bookChapters[book.id]
        bookChapters = bookChapters + (book.id to ids)
        chapterBooks = chapterBooks + ids.associateWith { book.id }
        val saved = progressOf(book.id)
        val index = saved?.let { s -> ids.indexOf(s.chapterId).takeIf { it >= 0 } } ?: 0
        val position = if (saved != null && ids.getOrNull(index) == saved.chapterId) saved.positionMs else 0L
        if (saved == null && ids.isNotEmpty()) {
            put(BookProgress(book.id, book.title, book.author, book.thumbnail, book.playlistId, ids.first(), 0, ids.size, 0L, (book.durationSec ?: 0L) * 1000L, now()))
        } else if (saved != null && ids != known) {
            // Saved with its chapters, so a later chapter is still known as this book after a restart.
            persist()
        }
        return index to position
    }

    /**
     * Records the position in a chapter of an opened book. Near the end of the last chapter the book
     * is finished and leaves Continue listening.
     */
    fun record(songId: String, positionMs: Long, durationMs: Long) {
        val bookId = chapterBooks[songId] ?: return
        val saved = progressOf(bookId) ?: return
        val chapters = bookChapters[bookId]
        val index = chapters?.indexOf(songId)?.takeIf { it >= 0 } ?: saved.chapterIndex
        val count = chapters?.size ?: saved.chapterCount
        val finished = index >= count - 1 && durationMs > 0 && durationMs - positionMs < FINISHED_MARGIN_MS
        if (finished) {
            forget(bookId)
            return
        }
        put(saved.copyWith(songId, index, count, positionMs.coerceAtLeast(0L), durationMs, now()))
    }

    /** Removes a book from Continue listening. */
    fun forget(bookId: String) {
        booksState.value = booksState.value.filterNot { it.bookId == bookId }
        persist()
    }

    /** The playback speed the listener chose for spoken word (1.0 when never set). */
    suspend fun speed(): Float = soft { TurtleBridge.uiPref(PREF_SPEED) }?.toFloatOrNull()?.takeIf { it in 0.5f..3f } ?: 1f

    fun setSpeed(speed: Float) {
        writer.launch { soft { TurtleBridge.setUiPref(PREF_SPEED, speed.toString()) } }
    }

    private fun put(progress: BookProgress) {
        booksState.value = (listOf(progress) + booksState.value.filterNot { it.bookId == progress.bookId }).take(MAX_BOOKS)
        chapterBooks = chapterBooks + (progress.chapterId to progress.bookId)
        persist()
    }

    private fun persist() {
        val snapshot = booksState.value
        val chapters = bookChapters
        writer.launch {
            writeLock.withLock {
                soft { TurtleBridge.setUiPref(PREF_BOOKS, snapshot.joinToString("\n") { encodeLine(it, chapters[it.bookId].orEmpty()) }) }
            }
        }
    }

    private fun clean(s: String?): String = s.orEmpty().map { if (it == FIELD || it == '\n' || it == '\r') ' ' else it }.joinToString("")

    /** One saved book: its progress fields, then its chapter video ids (which never contain a comma). */
    internal fun encodeLine(p: BookProgress, chapterIds: List<String>): String =
        listOf(
            clean(p.bookId), clean(p.title), clean(p.author), clean(p.thumbnail), clean(p.playlistId), clean(p.chapterId),
            p.chapterIndex.toString(), p.chapterCount.toString(), p.positionMs.toString(), p.durationMs.toString(), p.updatedMs.toString(),
            chapterIds.joinToString(",") { clean(it) },
        ).joinToString(FIELD.toString())

    /** A saved book and its chapter ids; lines saved before chapter lists were kept have none. */
    internal fun decodeLine(line: String): Pair<BookProgress, List<String>>? {
        val f = line.split(FIELD)
        if (f.size < 11 || f[0].isBlank() || f[5].isBlank()) return null
        val chapters = f.getOrNull(11)?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        return BookProgress(
            bookId = f[0], title = f[1], author = f[2].ifBlank { null }, thumbnail = f[3].ifBlank { null }, playlistId = f[4].ifBlank { null },
            chapterId = f[5], chapterIndex = f[6].toIntOrNull() ?: 0, chapterCount = f[7].toIntOrNull() ?: 1,
            positionMs = f[8].toLongOrNull() ?: 0L, durationMs = f[9].toLongOrNull() ?: 0L, updatedMs = f[10].toLongOrNull() ?: 0L,
        ) to chapters
    }

    private fun now(): Long = Clock.System.nowMs()

    private fun database(): MusicDatabase? = GlobalContext.getOrNull()?.getOrNull<MusicDatabase>()

    /** Runs [block], turning failures (not cancellation) into null. */
    private suspend fun <T> soft(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("spoken word: request failed", e)
            null
        }

    private const val FINISHED_MARGIN_MS = 30_000L
}
