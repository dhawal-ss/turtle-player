package app.turtleplayer.bridge

import app.turtleplayer.core.TextNormalizer
import app.turtleplayer.events.PlayEventStore
import app.turtleplayer.search.AlbumResult
import app.turtleplayer.search.AlignMode
import app.turtleplayer.search.EditDistance
import app.turtleplayer.search.TypoTolerance
import app.turtleplayer.search.ArtistResult
import app.turtleplayer.search.HistoryAffinityProvider
import app.turtleplayer.search.LocalLibrary
import app.turtleplayer.search.OnlineAlbum
import app.turtleplayer.search.OnlineArtist
import app.turtleplayer.search.OnlineResults
import app.turtleplayer.search.OnlineSearchProvider
import app.turtleplayer.search.OnlineTrack
import app.turtleplayer.search.ParsedQuery
import app.turtleplayer.search.ResultItem
import app.turtleplayer.search.SearchConfig
import app.turtleplayer.search.SearchEngine
import app.turtleplayer.search.TrackResult
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SearchSummary
import com.metrolist.shared.model.SearchSummaryPage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Re-ranks upstream's search summary with our engine: YouTube's results plus the user's library,
 * typo-tolerant, deduplicated across uploads, personally ranked, with an intent-aware top result.
 *
 * It reuses the page upstream already fetched (no extra network) and keeps upstream's section
 * shape, whose English category titles the UI already localizes, so no new strings are needed.
 * Sections our engine does not rank (videos, playlists, podcasts) pass through unchanged.
 */
class TurtleSearch(
    private val library: LocalLibrary,
    private val events: PlayEventStore,
    private val cache: SongCache,
) {
    private val affinity by lazy { HistoryAffinityProvider(events) }

    /** How taste reads history changed (repair or reset): rebuild history affinity on the next search. */
    suspend fun historyChanged() = affinity.invalidate()

    suspend fun rerank(query: String, page: SearchSummaryPage): SearchSummaryPage? {
        val items = page.summaries.flatMap { it.items }
        val songs = items.filterIsInstance<SongItem>().distinctBy { it.id }
        val artists = items.filterIsInstance<ArtistItem>().distinctBy { it.id }
        val albums = items.filterIsInstance<AlbumItem>().distinctBy { it.id }
        cache.putAll(songs)
        val online = OnlineResults(
            tracks = songs.map { OnlineTrack(it.toTrackMeta(), official = it.isOfficialUpload) },
            artists = artists.map { OnlineArtist(it.title, browseId = it.id) },
            albums = albums.map { OnlineAlbum(it.title, it.artists.firstOrNull()?.name.orEmpty(), browseId = it.id, year = it.year?.toIntOrNull()) },
        )
        val engine = SearchEngine(library = library, online = PageResults(online), affinity = affinity, config = PAGE_CONFIG)
        val results = withTimeoutOrNull(RERANK_TIMEOUT_MS) { engine.search(query).first { it.isFinal } } ?: return null

        val songById = songs.associateBy { it.id }
        val artistById = artists.associateBy { it.id }
        val albumById = albums.associateBy { it.id }
        suspend fun songOf(r: TrackResult): SongItem? = r.track.youtubeId?.let { songById[it] ?: cache.get(it) }
        fun artistOf(r: ArtistResult): ArtistItem? = r.browseId?.let(artistById::get)
        fun albumOf(r: AlbumResult): AlbumItem? = r.browseId?.let(albumById::get)
        suspend fun itemOf(r: ResultItem?): YTItem? = when (r) {
            is TrackResult -> songOf(r)
            is ArtistResult -> artistOf(r)
            is AlbumResult -> albumOf(r)
            else -> null // a generated mix has no upstream item; keep upstream's top result
        }

        val ourTop = itemOf(results.top)
        val upstreamTop = page.summaries.firstOrNull { it.isTopResult }?.items?.firstOrNull()
        // Title collisions: upstream's top is something our engine cannot rank (a playlist) whose title is
        // what was typed, typos allowed ("lofi lof" for the playlist "Lofi Loft"). It stays on top: our
        // pick there would be a same-name artist or another song, which is not what was asked for.
        val top = if (upstreamTop != null && !isRanked(upstreamTop) && titleIsQuery(query, upstreamTop.title)) upstreamTop else ourTop ?: upstreamTop
        // Our engine lists its top in no section; when it lost the top spot, it leads its own section instead.
        val demoted = ourTop?.takeIf { it !== top }
        val rankedSongs = (listOfNotNull(demoted as? SongItem) + results.songs.mapNotNull { songOf(it) }).filter { it.id != top?.id }.distinctBy { it.id }
        val rankedArtists = (listOfNotNull(demoted as? ArtistItem) + results.artists.mapNotNull(::artistOf)).filter { it.id != top?.id }.distinctBy { it.id }
        val rankedAlbums = (listOfNotNull(demoted as? AlbumItem) + results.albums.mapNotNull(::albumOf)).filter { it.id != top?.id }.distinctBy { it.id }
        // Nothing ranked (an emoji, or text our matching cannot read): keep upstream's page rather than drop its sections.
        if (ourTop == null && rankedSongs.isEmpty() && rankedArtists.isEmpty() && rankedAlbums.isEmpty()) return null

        val ours = setOf(SONGS, ARTISTS, ALBUMS)
        val summaries = buildList {
            if (top != null) add(SearchSummary("", listOf(top), isTopResult = true))
            if (rankedSongs.isNotEmpty()) add(SearchSummary(SONGS, rankedSongs))
            if (rankedArtists.isNotEmpty()) add(SearchSummary(ARTISTS, rankedArtists))
            if (rankedAlbums.isNotEmpty()) add(SearchSummary(ALBUMS, rankedAlbums))
            page.summaries.filterNot { it.isTopResult || it.title in ours }.forEach(::add)
        }
        val topFrom = if (top === upstreamTop && top !== ourTop) "upstream" else results.top?.kind ?: "upstream"
        TurtleLog.info("search: ${rankedSongs.size} songs, ${rankedArtists.size} artists, top=$topFrom")
        return page.copy(summaries = summaries)
    }

    /**
     * The local stage: the listener's own library for [query] before (or without) the network, as a
     * page of a top result and Songs. Artists and albums come only from the network page, which
     * replaces this one when it lands. Null when nothing matches.
     */
    suspend fun local(query: String): SearchSummaryPage? = rerank(query, SearchSummaryPage(emptyList()))

    /** Songs, artists and albums: what our engine ranks. Anything else (playlists, podcasts) passes through. */
    private fun isRanked(item: YTItem): Boolean = item is SongItem || item is ArtistItem || item is AlbumItem

    /** [title] reads as [query]: equal once normalized, or within the typos a query of that length may have. */
    private fun titleIsQuery(
        query: String,
        title: String,
    ): Boolean {
        val q = TextNormalizer.normalize(query).folded
        val t = TextNormalizer.normalize(title).folded
        if (q.isEmpty() || t.isEmpty()) return false
        return q == t || EditDistance.distance(q, t, AlignMode.WHOLE) <= TypoTolerance.maxTypos(q.length)
    }

    /** The page upstream already fetched, served to our engine as its online stage. */
    private class PageResults(private val results: OnlineResults) : OnlineSearchProvider {
        override suspend fun search(query: ParsedQuery, limit: Int) = results
        override suspend fun suggestions(prefix: String) = emptyList<String>()
    }

    private companion object {
        // Category titles upstream's search UI maps to localized labels.
        const val SONGS = "Songs"
        const val ARTISTS = "Artists"
        const val ALBUMS = "Albums"
        const val RERANK_TIMEOUT_MS = 800L

        // The online stage reads the page upstream already fetched, so even one character costs no request.
        val PAGE_CONFIG = SearchConfig(minOnlineChars = 1)
    }
}
