package app.turtleplayer.search

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TextNormalizer
import app.turtleplayer.core.TitleCanonicalizer
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.core.VersionTag
import kotlin.math.max

/** Re-ranking weights. Tuned against the golden query set; change them together with it. */
data class RankingWeights(
    val relevance: Float = 0.62f,
    val intent: Float = 0.12f,
    val affinity: Float = 0.14f,
    val popularity: Float = 0.05f,
    /** Lyrics evidence relative to a title match, when the intent is not LYRICS. */
    val lyricsFactor: Float = 0.85f,
    val semanticFactor: Float = 0.8f,
    /** Online rank evidence for the provider's top result. */
    val onlineFactor: Float = 0.7f,
    /**
     * Share of the online rank evidence kept for a result that shares no word with the query although
     * both are written in the same script; it grows to 1 with the share of query words matched.
     */
    val onlineUnmatchedFactor: Float = 0.35f,
    /** Query understanding must be this sure of a song before a track tops an artist named exactly like the query. */
    val trackOverArtistConfidence: Float = 0.6f,
    /** Confidence of ARTIST intent when only an online artist (not the library) is named exactly like the query. */
    val onlineArtistConfidence: Float = 0.7f,
    /** Extra relevance per additional retriever that found the same item. */
    val corroboration: Float = 0.04f,
    val libraryBoost: Float = 0.04f,
    val historyBoost: Float = 0.03f,
    val officialBoost: Float = 0.03f,
    val requestedVersionBoost: Float = 0.12f,
    val missingRequestedVersionPenalty: Float = 0.08f,
    val unrequestedVersionPenalty: Float = 0.07f,
    val coverOrKaraokePenalty: Float = 0.12f,
    val yearMatchBoost: Float = 0.08f,
    val yearMismatchPenalty: Float = 0.04f,
    /** Items below this relevance are dropped as noise. */
    val minRelevance: Float = 0.15f,
    /** An intent-matched top result may trail the best item by at most this factor. */
    val topIntentSlack: Float = 0.85f,
)

/** Maximum results per group. */
data class GroupLimits(val songs: Int = 20, val artists: Int = 6, val albums: Int = 6, val lyrics: Int = 5)

/** Local stage output. */
class LocalRetrieval(val lexical: List<LexicalHit>, val lyrics: List<LyricsHit>)

/** Everything retrieved so far for one query. */
internal class RetrievalSnapshot(
    val local: LocalRetrieval?,
    val semantic: List<SemanticHit>?,
    val online: OnlineResults?,
)

/**
 * Merges retriever outputs into one result set: dedupe by canonical identity, re-rank, choose a
 * top result for the intent, and group the rest.
 */
internal class ResultMerger(
    private val weights: RankingWeights,
    private val affinity: AffinityProvider,
    private val limits: GroupLimits,
) {
    /** One upload of a track with the evidence that found it. */
    private class Candidate(val meta: TrackMeta) {
        var lexical: DocScore? = null
        var lexicalSource = Retriever.LOCAL
        var lyrics: LyricsHit? = null
        var semantic = 0f
        var onlineRank: Int? = null
        var official = false
        var popularity: Float? = meta.popularity
        var localSources: Set<LocalSource> = emptySet()
        val canonical = TitleCanonicalizer.canonicalize(meta.title, cleanArtistName(meta.primaryArtist))
    }

    suspend fun merge(parsed: ParsedQuery, snapshot: RetrievalSnapshot, statuses: Map<Retriever, RetrieverStatus>): SearchResults {
        val query = withOnlineArtistEvidence(parsed, snapshot.online)
        val compiled = CompiledQuery(query.toLexicalQuery().tokens)
        val tracks = mergeTracks(query, compiled, snapshot)
        val artists = mergeArtists(query, compiled, snapshot)
        val albums = mergeAlbums(query, compiled, snapshot)

        val all = (tracks + artists + albums).filter { it.score > 0f }.sortedWith(resultOrder)
        val top = chooseTop(query, all)
        val rest = all.filter { it !== top }
        val ranked = listOfNotNull(top) + rest

        val trackRest = rest.filterIsInstance<TrackResult>()
        val (lyricsLed, songs) = trackRest.partition { t -> t.lyrics != null && lyricsLed(t) }
        return SearchResults(
            query = query,
            top = top,
            songs = songs.take(limits.songs),
            artists = rest.filterIsInstance<ArtistResult>().take(limits.artists),
            albums = rest.filterIsInstance<AlbumResult>().take(limits.albums),
            lyrics = lyricsLed.take(limits.lyrics),
            ranked = ranked,
            retrievers = statuses,
        )
    }

    /** Lyrics were the main reason this track matched. */
    private fun lyricsLed(t: TrackResult): Boolean {
        val lyric = t.reasons.filterIsInstance<MatchReason.Lyrics>().maxOfOrNull { it.score } ?: return false
        val lexicalCoverage = t.reasons.count { it is MatchReason.Lexical && !it.match.optional }
        return lexicalCoverage == 0 || lyric * weights.lyricsFactor >= t.breakdown.relevance - 0.01f
    }

    /**
     * Query understanding only knows the library's names. When an online artist is named exactly like
     * the query ("taylor swift" with none of her songs saved) and the query is not confidently a known
     * song title, the user is most likely looking for that artist.
     */
    private fun withOnlineArtistEvidence(query: ParsedQuery, online: OnlineResults?): ParsedQuery {
        if (online == null || online.artists.isEmpty()) return query
        if (query.intent != Intent.SONG && query.intent != Intent.UNKNOWN) return query
        if (query.confidence >= weights.trackOverArtistConfidence) return query
        if (online.artists.none { namedLike(it.name, query) }) return query
        val c = weights.onlineArtistConfidence
        return query.copy(intent = Intent.ARTIST, confidence = c, intentScores = query.intentScores + (Intent.ARTIST to c))
    }

    /** [name] normalizes to the query's words, spaces aside ("AC/DC" for "acdc"). */
    private fun namedLike(name: String, query: ParsedQuery): Boolean {
        if (query.tokens.isEmpty()) return false
        val key = TextNormalizer.normalize(cleanArtistName(name)).tokens
        return key == query.tokens || key.joinToString("") == query.tokens.joinToString("")
    }

    private fun chooseTop(query: ParsedQuery, all: List<ResultItem>): ResultItem? {
        if (query.intent == Intent.MOOD) {
            return MixResult(MixSeed(query.tokens, query.mood, query.filters), score = 1f)
        }
        val best = all.firstOrNull() ?: return null
        val preferred: ResultItem? = when (query.intent) {
            // Filters describe recordings: "taylor swift 2014" wants her 2014 songs, not her page.
            Intent.ARTIST -> if (query.filters.isEmpty) {
                all.firstOrNull { it is ArtistResult && namedLike(it.name, query) } ?: all.firstOrNull { it is ArtistResult }
            } else {
                all.firstOrNull { it is TrackResult }
            }
            Intent.ALBUM -> all.firstOrNull { it is AlbumResult }
            Intent.SONG, Intent.SONG_BY_ARTIST -> all.firstOrNull { it is TrackResult }
            Intent.LYRICS -> all.firstOrNull { it is TrackResult && it.lyrics != null } ?: all.firstOrNull { it is TrackResult }
            else -> null
        }
        return if (preferred != null && preferred.score >= best.score * weights.topIntentSlack) preferred else best
    }

    // ---------------------------------------------------------------- tracks

    private suspend fun mergeTracks(query: ParsedQuery, q: CompiledQuery, snapshot: RetrievalSnapshot): List<TrackResult> {
        val candidates = ArrayList<Candidate>()
        snapshot.local?.lexical?.forEach { hit ->
            val item = hit.item as? LocalItem.Track ?: return@forEach
            candidates += Candidate(item.track.meta).apply {
                lexical = DocScore(hit.score, hit.matches, hit.coverage, hit.fieldShares, hit.preferredAgreement)
                localSources = item.track.sources
                official = item.track.canonical.official
            }
        }
        snapshot.local?.lyrics?.forEach { hit -> candidates += Candidate(hit.track).apply { lyrics = hit } }
        snapshot.semantic?.forEach { hit -> candidates += Candidate(hit.track).apply { semantic = hit.similarity } }
        snapshot.online?.tracks?.forEachIndexed { rank, t ->
            candidates += Candidate(t.meta).apply {
                onlineRank = rank
                official = t.official || canonical.official
                popularity = t.popularity ?: t.meta.popularity
            }
        }
        // Score metadata the local index has not seen (online, semantic, lyrics-only) with the same scorer.
        for (c in candidates) {
            if (c.lexical == null) {
                c.lexical = LexicalScorer.score(DocType.TRACK, DocBuilder.track(c.meta).second, q, q::matchAdHoc)
                c.lexicalSource = if (c.onlineRank != null) Retriever.ONLINE else Retriever.LOCAL
            }
        }
        return groupByIdentity(candidates).mapNotNull { scoreTrackGroup(query, it) }
    }

    /**
     * Union-find over every identity a candidate has: its key, ISRC, YouTube id and canonical name key.
     * The same upload or ISRC always merges, but a name match never joins an explicit upload with a
     * clean one: "Hide explicit" drops a group's representative, so a merged pair could vanish whole.
     */
    private fun groupByIdentity(candidates: List<Candidate>): List<List<Candidate>> {
        val parent = IntArray(candidates.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) {
                parent[x] = parent[parent[x]]
                x = parent[x]
            }
            return x
        }
        val owner = HashMap<String, Int>()
        candidates.forEachIndexed { i, c ->
            for (key in uploadKeys(c)) {
                val other = owner[key]
                if (other == null) owner[key] = i else parent[find(i)] = find(other)
            }
        }
        // Flag per upload, not per candidate, so a stale clean copy of an explicit upload cannot bridge the two.
        val explicitRoots = candidates.indices.filter { candidates[it].meta.explicit }.mapTo(HashSet()) { find(it) }
        val nameOwner = HashMap<Pair<Boolean, String>, Int>()
        candidates.forEachIndexed { i, c ->
            val explicit = find(i) in explicitRoots
            for (key in nameKeys(c)) {
                val other = nameOwner[explicit to key]
                if (other == null) nameOwner[explicit to key] = i else parent[find(i)] = find(other)
            }
        }
        return candidates.indices.groupBy { find(it) }.values.map { idx -> idx.map { candidates[it] } }
    }

    /** Identities of one recording or upload. */
    private fun uploadKeys(c: Candidate): List<String> = buildList {
        if (c.meta.key.scheme != TrackKey.META) add(c.meta.key.value)
        c.meta.isrc?.takeIf { it.isNotBlank() }?.let { add(TrackKey.isrc(it).value) }
        c.meta.youtubeId?.takeIf { it.isNotBlank() }?.let { add(TrackKey.youtube(it).value) }
    }

    /** Name-based identities; a `meta:` key shares [canonicalIdentity]'s format on purpose. */
    private fun nameKeys(c: Candidate): List<String> = buildList {
        if (c.meta.key.scheme == TrackKey.META) add(c.meta.key.value)
        add(canonicalIdentity(c.meta.primaryArtist, c.canonical.base, c.canonical.versions))
    }

    private suspend fun scoreTrackGroup(query: ParsedQuery, group: List<Candidate>): TrackResult? {
        val inLibrary = { c: Candidate -> LocalSource.LIBRARY in c.localSources || LocalSource.HISTORY in c.localSources }
        val ordered = group.sortedWith(
            compareByDescending<Candidate> { it.official }
                .thenByDescending { inLibrary(it) }
                .thenByDescending { it.popularity ?: 0f }
                .thenByDescending { it.lexical?.score ?: 0f }
                .thenBy { it.meta.key.value },
        )
        val rep = ordered.first()
        val bestLexical = group.mapNotNull { it.lexical }.maxByOrNull { it.score }
        val lexicalSource = group.filter { it.lexical === bestLexical }.minOfOrNull { it.lexicalSource } ?: Retriever.LOCAL
        val lyrics = group.mapNotNull { it.lyrics }.maxByOrNull { it.score }
        val semantic = group.maxOf { it.semantic }
        val onlineRank = group.mapNotNull { it.onlineRank }.minOrNull()
        val sources = group.flatMapTo(HashSet()) { it.localSources }
        val popularity = group.mapNotNull { it.popularity }.maxOrNull() ?: 0f
        val official = group.any { it.official }

        val l = bestLexical?.score ?: 0f
        val y = (lyrics?.score ?: 0f) * if (query.intent == Intent.LYRICS) 1f else weights.lyricsFactor
        val s = semantic * weights.semanticFactor
        val coverage = group.maxOf { it.lexical?.coverage ?: 0f }
        val o = onlineRank?.let { onlineEvidence(query, it, coverage, listOf(rep.meta.title) + rep.meta.artists) } ?: 0f
        val evidence = listOf(l >= 0.2f, y > 0f, s > 0f, o > 0f).count { it }
        val relevance = maxOf(l, y, s, o) + weights.corroboration * (evidence - 1).coerceAtLeast(0)
        if (relevance < weights.minRelevance) return null

        val shares = bestLexical?.fieldShares.orEmpty()
        val agreement = when (query.intent) {
            Intent.SONG -> if (bestLexical == null) 0.3f else (shares[Field.TITLE] ?: 0f) + 0.5f * (shares[Field.VERSION] ?: 0f)
            Intent.SONG_BY_ARTIST -> bestLexical?.preferredAgreement ?: ((shares[Field.TITLE] ?: 0f) + (shares[Field.ARTIST] ?: 0f))
            Intent.ARTIST -> if ((shares[Field.ARTIST] ?: 0f) > 0f) (if (query.filters.isEmpty) 0.3f else 0.8f) else 0.1f
            Intent.ALBUM -> if ((shares[Field.ALBUM] ?: 0f) > 0f) 0.3f else 0.1f
            Intent.LYRICS -> if (lyrics != null) 1f else 0.2f
            Intent.MOOD -> if (semantic > 0f) 1f else 0.3f
            Intent.UNKNOWN -> 0.5f
        }
        val versions = rep.canonical.versions
        val affinityValue = max(affinity.trackAffinity(rep.meta.key), 0.8f * affinity.artistAffinity(ArtistKey.of(cleanArtistName(rep.meta.primaryArtist))))
        val breakdown = ScoreBreakdown(
            relevance = relevance,
            intentAgreement = blend(query, agreement),
            affinity = affinityValue,
            popularity = popularity,
            version = versionAdjustment(query, versions) + if (official) weights.officialBoost else 0f,
            personal = when {
                LocalSource.LIBRARY in sources -> weights.libraryBoost
                LocalSource.HISTORY in sources -> weights.historyBoost
                else -> 0f
            },
            year = yearAdjustment(query, group.firstNotNullOfOrNull { it.meta.releasedAtMs }?.let(::yearOfEpochMs)),
        )
        val reasons = buildList {
            bestLexical?.matches?.forEach { add(MatchReason.Lexical(it, lexicalSource)) }
            lyrics?.let { add(MatchReason.Lyrics(it.lineIndex, it.line, it.score)) }
            if (semantic > 0f) add(MatchReason.Semantic(semantic))
            onlineRank?.let { add(MatchReason.Online(it)) }
        }
        return TrackResult(
            track = rep.meta,
            score = total(breakdown),
            reasons = reasons,
            breakdown = breakdown,
            versions = versions,
            official = official,
            localSources = sources,
            lyrics = lyrics?.let { LyricsSnippet(it.lineIndex, it.line) },
            alternates = ordered.drop(1).map { it.meta.key }.distinct().filter { it != rep.meta.key },
        )
    }

    private fun versionAdjustment(query: ParsedQuery, versions: Set<VersionTag>): Float {
        val requested = query.filters.versions
        return when {
            requested.isNotEmpty() -> if (versions.any { it in requested }) weights.requestedVersionBoost else -weights.missingRequestedVersionPenalty
            VersionTag.COVER in versions || VersionTag.KARAOKE in versions -> -weights.coverOrKaraokePenalty
            versions.isNotEmpty() -> -weights.unrequestedVersionPenalty
            else -> 0f
        }
    }

    private fun yearAdjustment(query: ParsedQuery, year: Int?): Float {
        val range = query.filters.years ?: return 0f
        year ?: return 0f
        return if (year in range) weights.yearMatchBoost else -weights.yearMismatchPenalty
    }

    // ---------------------------------------------------------------- artists and albums

    private suspend fun mergeArtists(query: ParsedQuery, q: CompiledQuery, snapshot: RetrievalSnapshot): List<ArtistResult> {
        class Acc(val key: ArtistKey, var name: String) {
            var lexical: DocScore? = null
            var lexicalSource = Retriever.LOCAL
            var onlineRank: Int? = null
            var browseId: String? = null
            var popularity = 0f
            var local = false
        }
        val byKey = LinkedHashMap<ArtistKey, Acc>()
        snapshot.local?.lexical?.forEach { hit ->
            val a = (hit.item as? LocalItem.Artist)?.artist ?: return@forEach
            byKey.getOrPut(a.key) { Acc(a.key, a.name) }.apply {
                lexical = DocScore(hit.score, hit.matches, hit.coverage, hit.fieldShares, hit.preferredAgreement)
                local = true
            }
        }
        snapshot.online?.artists?.forEachIndexed { rank, a ->
            val key = ArtistKey.of(cleanArtistName(a.name))
            byKey.getOrPut(key) { Acc(key, a.name) }.apply {
                if (onlineRank == null) onlineRank = rank
                browseId = browseId ?: a.browseId
                popularity = max(popularity, a.popularity ?: 0f)
                if (lexical == null) {
                    lexical = LexicalScorer.score(DocType.ARTIST, DocBuilder.artist(a.name), q, q::matchAdHoc)
                    lexicalSource = Retriever.ONLINE
                }
            }
        }
        return byKey.values.mapNotNull { acc ->
            val l = acc.lexical?.score ?: 0f
            val o = acc.onlineRank?.let { onlineEvidence(query, it, acc.lexical?.coverage ?: 0f, listOf(acc.name)) } ?: 0f
            val relevance = max(l, o) + if (l >= 0.2f && o > 0f) weights.corroboration else 0f
            if (relevance < weights.minRelevance) return@mapNotNull null
            val agreement = when (query.intent) {
                Intent.ARTIST -> if (query.filters.isEmpty) 1f else 0.5f
                Intent.UNKNOWN -> 0.5f
                Intent.SONG -> 0.25f
                else -> 0.2f
            }
            val breakdown = ScoreBreakdown(
                relevance = relevance,
                intentAgreement = blend(query, agreement),
                affinity = affinity.artistAffinity(acc.key),
                popularity = acc.popularity,
                version = 0f,
                personal = if (acc.local) weights.libraryBoost else 0f,
                year = 0f,
            )
            ArtistResult(
                artist = acc.key,
                name = acc.name,
                browseId = acc.browseId,
                local = acc.local,
                score = total(breakdown),
                reasons = buildList {
                    acc.lexical?.matches?.forEach { add(MatchReason.Lexical(it, acc.lexicalSource)) }
                    acc.onlineRank?.let { add(MatchReason.Online(it)) }
                },
                breakdown = breakdown,
            )
        }
    }

    private suspend fun mergeAlbums(query: ParsedQuery, q: CompiledQuery, snapshot: RetrievalSnapshot): List<AlbumResult> {
        class Acc(val key: String, val title: String, val artist: String) {
            var lexical: DocScore? = null
            var lexicalSource = Retriever.LOCAL
            var onlineRank: Int? = null
            var browseId: String? = null
            var year: Int? = null
            var popularity = 0f
            var local = false
        }
        val byKey = LinkedHashMap<String, Acc>()
        snapshot.local?.lexical?.forEach { hit ->
            val a = (hit.item as? LocalItem.Album)?.album ?: return@forEach
            byKey.getOrPut(a.key) { Acc(a.key, a.title, a.artist) }.apply {
                lexical = DocScore(hit.score, hit.matches, hit.coverage, hit.fieldShares, hit.preferredAgreement)
                local = true
            }
        }
        snapshot.online?.albums?.forEachIndexed { rank, a ->
            val key = "${ArtistKey.of(cleanArtistName(a.artist)).value}|${TextNormalizer.key(a.title)}"
            byKey.getOrPut(key) { Acc(key, a.title, a.artist) }.apply {
                if (onlineRank == null) onlineRank = rank
                browseId = browseId ?: a.browseId
                year = year ?: a.year
                popularity = max(popularity, a.popularity ?: 0f)
                if (lexical == null) {
                    lexical = LexicalScorer.score(DocType.ALBUM, DocBuilder.album(a.title, a.artist), q, q::matchAdHoc)
                    lexicalSource = Retriever.ONLINE
                }
            }
        }
        return byKey.values.mapNotNull { acc ->
            val l = acc.lexical?.score ?: 0f
            val o = acc.onlineRank?.let { onlineEvidence(query, it, acc.lexical?.coverage ?: 0f, listOf(acc.title, acc.artist)) } ?: 0f
            val relevance = max(l, o) + if (l >= 0.2f && o > 0f) weights.corroboration else 0f
            if (relevance < weights.minRelevance) return@mapNotNull null
            val agreement = when (query.intent) {
                Intent.ALBUM -> 1f
                Intent.UNKNOWN -> 0.4f
                else -> 0.15f
            }
            val breakdown = ScoreBreakdown(
                relevance = relevance,
                intentAgreement = blend(query, agreement),
                affinity = 0.8f * affinity.artistAffinity(ArtistKey.of(cleanArtistName(acc.artist))),
                popularity = acc.popularity,
                version = 0f,
                personal = if (acc.local) weights.libraryBoost else 0f,
                year = yearAdjustment(query, acc.year),
            )
            AlbumResult(
                key = acc.key,
                title = acc.title,
                artist = acc.artist,
                browseId = acc.browseId,
                year = acc.year,
                local = acc.local,
                score = total(breakdown),
                reasons = buildList {
                    acc.lexical?.matches?.forEach { add(MatchReason.Lexical(it, acc.lexicalSource)) }
                    acc.onlineRank?.let { add(MatchReason.Online(it)) }
                },
                breakdown = breakdown,
            )
        }
    }

    // ---------------------------------------------------------------- shared

    /**
     * Evidence from the provider's rank, scaled by the share of query words the result matched
     * ([coverage]). A result written in another script than the query (a romaji query, a kanji
     * title) cannot be compared word by word and keeps its full rank evidence, and so do lyrics
     * queries, whose words are rarely in titles.
     */
    private fun onlineEvidence(query: ParsedQuery, rank: Int, coverage: Float, texts: List<String>): Float {
        val evidence = weights.onlineFactor / (1f + 0.3f * rank)
        if (query.intent == Intent.LYRICS || !sharesScript(query.normalizedTokens, texts)) return evidence
        return evidence * (weights.onlineUnmatchedFactor + (1f - weights.onlineUnmatchedFactor) * coverage)
    }

    private fun sharesScript(queryTokens: List<String>, texts: List<String>): Boolean {
        val queryScripts = scriptsOf(queryTokens.asSequence())
        if (queryScripts.isEmpty()) return false
        val textScripts = scriptsOf(texts.asSequence().flatMap { TextNormalizer.normalize(it).tokens })
        return queryScripts.any { it in textScripts }
    }

    /** Intent agreement counts in proportion to how sure query understanding is. */
    private fun blend(query: ParsedQuery, agreement: Float): Float = query.confidence * agreement + (1f - query.confidence) * 0.5f

    private fun total(b: ScoreBreakdown): Float =
        weights.relevance * b.relevance + weights.intent * b.intentAgreement + weights.affinity * b.affinity +
            weights.popularity * b.popularity + b.version + b.personal + b.year

    private companion object {
        val resultOrder = compareByDescending<ResultItem> { it.score }.thenBy { it.kind }.thenBy { it.id }
    }
}

/** Writing systems of normalized text (Latin after transliteration, Han, Hangul, ...); digits have none. */
internal fun scriptsOf(tokens: Sequence<String>): Set<Int> {
    val scripts = HashSet<Int>()
    for (token in tokens) for (c in token) {
        if (!c.isLetter()) continue
        scripts += when {
            c.code < 0x0250 -> 0
            c in '\u3400'..'\u9FFF' || c in '\uF900'..'\uFAFF' -> 1
            c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F' || c in '\uAC00'..'\uD7AF' -> 2
            else -> c.code shr 8 shl 8
        }
    }
    return scripts
}

/** Strips channel decorations so "Coldplay - Topic" and "ColdplayVEVO" collapse to "Coldplay". */
internal fun cleanArtistName(name: String): String {
    var n = name.trim()
    n = n.replace(topicSuffix, "").replace(vevoSuffix, "").replace(officialSuffix, "").trim()
    return n.ifEmpty { name.trim() }
}

private val topicSuffix = Regex("""\s*-\s*topic$""", RegexOption.IGNORE_CASE)
private val vevoSuffix = Regex("""\s*vevo$""", RegexOption.IGNORE_CASE)
private val officialSuffix = Regex("""\s+official$""", RegexOption.IGNORE_CASE)

/**
 * Name-based identity matching [TrackKey.fromMeta]'s format, but built from a title canonicalized
 * with the artist known, so "Coldplay - Yellow (Official Video)" and "Yellow" collapse.
 */
internal fun canonicalIdentity(primaryArtist: String, canonicalBase: String, versions: Set<VersionTag>): String {
    val artist = ArtistKey.of(cleanArtistName(primaryArtist)).value
    val v = versions.map { it.name.lowercase() }.sorted()
    return "${TrackKey.META}:$artist|$canonicalBase" + if (v.isEmpty()) "" else "|" + v.joinToString(",")
}

/** Gregorian year of an epoch-milliseconds instant (UTC), without a date-time library. */
internal fun yearOfEpochMs(ms: Long): Int {
    // Howard Hinnant's civil_from_days.
    val days = ms.floorDiv(86_400_000L)
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    return year.toInt()
}
