package app.turtleplayer.search

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.core.VersionTag

/** Retrieval stages that run in parallel for each query. */
enum class Retriever {
    /** Lexical index and lyrics index over the library, history and cached catalog. */
    LOCAL,
    SEMANTIC,
    ONLINE,
}

enum class RetrieverStatus { PENDING, DONE, TIMED_OUT, FAILED, SKIPPED }

/** Why a result matched, for debugging and for "matched in lyrics" style hints in the UI. */
sealed interface MatchReason {
    /** A query token matched a field. [source] says whether the metadata came from the local index or an online result. */
    data class Lexical(val match: FieldMatch, val source: Retriever) : MatchReason

    data class Lyrics(val lineIndex: Int, val line: String, val score: Float) : MatchReason

    data class Semantic(val similarity: Float) : MatchReason

    /** Position in the online provider's own ranking (0 = its top result). */
    data class Online(val rank: Int) : MatchReason
}

/** Components of a result's final score, for debugging and tuning. */
data class ScoreBreakdown(
    val relevance: Float,
    val intentAgreement: Float,
    val affinity: Float,
    val popularity: Float,
    /** Version preference: penalties for covers/live/karaoke, boosts for requested versions. */
    val version: Float,
    /** Boost for items in the user's library or history. */
    val personal: Float,
    /** Release year inside or outside a requested year range. */
    val year: Float,
)

enum class ResultKind { TRACK, ARTIST, ALBUM, MIX }

sealed interface ResultItem {
    val kind: ResultKind
    val score: Float
    val reasons: List<MatchReason>

    /** Stable identity within one result set (TrackKey, ArtistKey or album key). */
    val id: String
}

data class LyricsSnippet(val lineIndex: Int, val line: String)

data class TrackResult(
    /** The preferred upload: official over unofficial, then the user's own copy, then the most popular. */
    val track: TrackMeta,
    override val score: Float,
    override val reasons: List<MatchReason>,
    val breakdown: ScoreBreakdown,
    val versions: Set<VersionTag>,
    val official: Boolean,
    val localSources: Set<LocalSource>,
    val lyrics: LyricsSnippet?,
    /** Other uploads of the same recording that were merged into this row. */
    val alternates: List<TrackKey>,
) : ResultItem {
    override val kind get() = ResultKind.TRACK
    override val id get() = track.key.value
}

data class ArtistResult(
    val artist: ArtistKey,
    val name: String,
    val browseId: String?,
    /** The artist has tracks in the local index. */
    val local: Boolean,
    override val score: Float,
    override val reasons: List<MatchReason>,
    val breakdown: ScoreBreakdown,
) : ResultItem {
    override val kind get() = ResultKind.ARTIST
    override val id get() = artist.value
}

data class AlbumResult(
    val key: String,
    val title: String,
    val artist: String,
    val browseId: String?,
    val year: Int?,
    val local: Boolean,
    override val score: Float,
    override val reasons: List<MatchReason>,
    val breakdown: ScoreBreakdown,
) : ResultItem {
    override val kind get() = ResultKind.ALBUM
    override val id get() = key
}

/** What a generated mix for a mood or activity query should be built from. */
data class MixSeed(val tokens: List<String>, val mood: MoodTerms?, val filters: QueryFilters)

/** Top-result marker for MOOD queries: the UI offers a generated mix built from [seed]. */
data class MixResult(val seed: MixSeed, override val score: Float) : ResultItem {
    override val kind get() = ResultKind.MIX
    override val reasons: List<MatchReason> get() = emptyList()
    override val id get() = "mix:" + seed.tokens.joinToString(" ")
}

data class SearchResults(
    val query: ParsedQuery,
    /** One result matched to the intent: an artist, a song, an album or a generated mix. */
    val top: ResultItem?,
    val songs: List<TrackResult>,
    val artists: List<ArtistResult>,
    val albums: List<AlbumResult>,
    /** Tracks found mainly through their lyrics, with the matched line. */
    val lyrics: List<TrackResult>,
    /** Everything in final order, [top] first. */
    val ranked: List<ResultItem>,
    val retrievers: Map<Retriever, RetrieverStatus>,
) {
    /** No stage is still running; this is the last emission for the query. */
    val isFinal: Boolean get() = retrievers.values.none { it == RetrieverStatus.PENDING }

    companion object {
        fun empty(query: ParsedQuery, retrievers: Map<Retriever, RetrieverStatus>) =
            SearchResults(query, null, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), retrievers)
    }
}
