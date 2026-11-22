package app.turtleplayer.search

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.CanonicalTitle
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta

/** Why a track is in the local index. */
enum class LocalSource { LIBRARY, HISTORY, CATALOG_CACHE }

data class ArtistRef(val key: ArtistKey, val name: String)

data class AlbumRef(
    /** Same format as [TrackMeta.albumKey]: `artistKey|normalized album`. */
    val key: String,
    val title: String,
    val artist: String,
)

/** A track as stored in the local index. */
data class IndexedTrack(val meta: TrackMeta, val canonical: CanonicalTitle, val sources: Set<LocalSource>)

/** Something the local index can return. */
sealed interface LocalItem {
    val type: DocType

    data class Track(val track: IndexedTrack) : LocalItem {
        override val type get() = DocType.TRACK
    }

    data class Artist(val artist: ArtistRef, val trackCount: Int) : LocalItem {
        override val type get() = DocType.ARTIST
    }

    data class Album(val album: AlbumRef, val trackCount: Int) : LocalItem {
        override val type get() = DocType.ALBUM
    }
}

data class LexicalHit(
    val item: LocalItem,
    /** In [0, 1]. */
    val score: Float,
    val matches: List<FieldMatch>,
    val coverage: Float,
    val fieldShares: Map<Field, Float>,
    val preferredAgreement: Float?,
)

/**
 * Typo-tolerant index over the local library, history and cached catalog. Tracks are documents;
 * their artists and albums become documents of their own (reference counted, so they disappear
 * with their last track).
 *
 * Search scans the term dictionary once per query token (length buckets + character-signature
 * prefilter + bit-parallel matcher), collects candidate documents from the postings of matching
 * terms, and scores only those candidates with [LexicalScorer].
 *
 * Not thread-safe: confine to one coroutine at a time; [LocalLibrary] does this with a mutex.
 */
class LexicalIndex {
    private class Doc(val type: DocType, val fields: List<IndexedField>) {
        var track: IndexedTrack? = null
        var artist: ArtistRef? = null
        var album: AlbumRef? = null
        var refCount = 0
        val nameKey: String = fields.firstOrNull { it.field == LexicalScorer.primaryField(type) }?.tokens?.joinToString(" ").orEmpty()

        fun item(): LocalItem = when (type) {
            DocType.TRACK -> LocalItem.Track(track!!)
            DocType.ARTIST -> LocalItem.Artist(artist!!, refCount)
            DocType.ALBUM -> LocalItem.Album(album!!, refCount)
        }
    }

    private val vocabulary = Vocabulary()
    private val docs = ArrayList<Doc?>()
    private val freeSlots = ArrayList<Int>()
    private val trackSlots = HashMap<TrackKey, Int>()
    private val artistSlots = HashMap<ArtistKey, Int>()
    private val albumSlots = HashMap<String, Int>()

    /** Exact-name lookup for the lexicons: normalized name (and its space-free form) -> slots. */
    private val namesByType = DocType.entries.associateWith { HashMap<String, MutableList<Int>>() }
    private val lookupCache = HashMap<String, LexiconMatch?>()

    val trackCount: Int get() = trackSlots.size
    val artistCount: Int get() = artistSlots.size
    val albumCount: Int get() = albumSlots.size

    fun track(key: TrackKey): IndexedTrack? = trackSlots[key]?.let { docs[it]?.track }

    fun tracks(): List<IndexedTrack> = trackSlots.values.mapNotNull { docs[it]?.track }

    /**
     * Adds or replaces [meta] with exactly [sources], so a song unsaved from the library and upserted
     * as HISTORY loses its LIBRARY source. Pass every source that applies (a saved song that was also
     * played is both); [removeSource] drops one.
     */
    fun upsert(meta: TrackMeta, sources: Set<LocalSource> = setOf(LocalSource.LIBRARY)) {
        if (trackSlots.containsKey(meta.key)) remove(meta.key)
        val (canonical, fields) = DocBuilder.track(meta)
        val slot = insert(Doc(DocType.TRACK, fields).also { it.track = IndexedTrack(meta, canonical, sources.ifEmpty { setOf(LocalSource.LIBRARY) }) })
        trackSlots[meta.key] = slot
        meta.artists.filter { it.isNotBlank() }.distinctBy { ArtistKey.of(it) }.forEach { retainArtist(it) }
        if (!meta.album.isNullOrBlank()) retainAlbum(meta)
    }

    fun upsertAll(tracks: Iterable<TrackMeta>, sources: Set<LocalSource> = setOf(LocalSource.LIBRARY)) = tracks.forEach { upsert(it, sources) }

    /** Removes the track and releases its artist and album documents. */
    fun remove(key: TrackKey): Boolean {
        val slot = trackSlots.remove(key) ?: return false
        val doc = docs[slot]!!
        val meta = doc.track!!.meta
        delete(slot)
        meta.artists.filter { it.isNotBlank() }.distinctBy { ArtistKey.of(it) }.forEach { releaseArtist(ArtistKey.of(it)) }
        meta.albumKey?.takeIf { !meta.album.isNullOrBlank() }?.let { releaseAlbum(it) }
        return true
    }

    /** Drops one source; the track is removed once it has none left (unsaved from library and never played). */
    fun removeSource(key: TrackKey, source: LocalSource) {
        val track = track(key) ?: return
        val rest = track.sources - source
        if (rest.isEmpty()) {
            remove(key)
        } else {
            docs[trackSlots[key]!!]!!.track = track.copy(sources = rest)
        }
    }

    fun search(query: LexicalQuery, limit: Int = 50, types: Set<DocType> = ALL_TYPES): List<LexicalHit> {
        val q = CompiledQuery(query.tokens)
        if (q.size == 0) return emptyList()
        val termMatches = Array(q.size) { HashMap<String, TermMatch>() }
        val hitMask = IntArray(docs.size)
        val touched = IntList(64)
        for (qi in 0 until q.size) {
            val required = q.isRequired(qi)
            vocabulary.forEachMatch(q.matchers[qi]) { term, match ->
                termMatches[qi][term.text] = match
                if (required) {
                    val bit = 1 shl qi
                    term.postings.forEach { d ->
                        if (hitMask[d] == 0) touched.add(d)
                        hitMask[d] = hitMask[d] or bit
                    }
                }
            }
        }
        // With 3+ query tokens, documents matching far fewer tokens than the best ones are noise.
        var maxHits = 0
        touched.forEach { d -> maxHits = maxOf(maxHits, hitMask[d].countOneBits()) }
        val minHits = if (maxHits >= 3) maxHits - 1 else 1
        val lookup = { qi: Int, term: String -> termMatches[qi][term] }
        val hits = ArrayList<Pair<Int, DocScore>>()
        touched.forEach { d ->
            if (hitMask[d].countOneBits() < minHits) return@forEach
            val doc = docs[d] ?: return@forEach
            if (doc.type !in types) return@forEach
            LexicalScorer.score(doc.type, doc.fields, q, lookup)?.let { hits += d to it }
        }
        hits.sortWith(compareByDescending<Pair<Int, DocScore>> { it.second.score }.thenBy { it.first })
        return hits.take(limit).map { (d, s) -> docs[d]!!.toHit(s) }
    }

    fun search(parsed: ParsedQuery, limit: Int = 50, types: Set<DocType> = ALL_TYPES): List<LexicalHit> =
        search(parsed.toLexicalQuery(), limit, types)

    /** Known artist names, for query understanding. */
    val artistLexicon: NameLexicon = NameLexicon { tokens, lastIsPrefix -> lookupName(DocType.ARTIST, tokens, lastIsPrefix) }
    val albumLexicon: NameLexicon = NameLexicon { tokens, lastIsPrefix -> lookupName(DocType.ALBUM, tokens, lastIsPrefix) }
    val titleLexicon: NameLexicon = NameLexicon { tokens, lastIsPrefix -> lookupName(DocType.TRACK, tokens, lastIsPrefix) }

    fun lexicons(): QueryLexicons = QueryLexicons(artistLexicon, albumLexicon, titleLexicon)

    private fun Doc.toHit(s: DocScore) = LexicalHit(item(), s.score, s.matches, s.coverage, s.fieldShares, s.preferredAgreement)

    /**
     * Best document of [type] whose primary field matches [tokens] token by token (each within its
     * typo tolerance; the last one as a prefix when [lastIsPrefix]).
     */
    private fun lookupName(type: DocType, tokens: List<String>, lastIsPrefix: Boolean): LexiconMatch? {
        if (tokens.isEmpty()) return null
        // Query understanding asks for the same spans on every keystroke; the cache is cleared on any change.
        val cacheKey = "${type.ordinal}${if (lastIsPrefix) 1 else 0}${tokens.joinToString(" ")}"
        if (lookupCache.containsKey(cacheKey)) return lookupCache[cacheKey]
        if (lookupCache.size >= LOOKUP_CACHE_SIZE) lookupCache.clear()
        return findName(type, tokens, lastIsPrefix).also { lookupCache[cacheKey] = it }
    }

    private fun findName(type: DocType, tokens: List<String>, lastIsPrefix: Boolean): LexiconMatch? {
        val names = namesByType.getValue(type)
        val joined = tokens.joinToString(" ")
        (names[joined] ?: names[tokens.joinToString("")])?.firstOrNull()?.let { slot ->
            return docs[slot]?.let { LexiconMatch(it.lexiconKey(), it.displayName(), exact = true, quality = 1f) }
        }
        // A single short typed token ("c") would match half the dictionary; exact only.
        if (lastIsPrefix && tokens.size == 1 && tokens[0].length < 3) return null
        val matchers = tokens.mapIndexed { i, t -> TermMatcher(t, lastIsPrefix && i == tokens.lastIndex, strict = true) }
        val field = LexicalScorer.primaryField(type)
        val candidates = HashSet<Int>()
        vocabulary.forEachMatch(matchers[0]) { term, _ ->
            term.postings.forEach { d -> if (docs[d]?.type == type) candidates += d }
        }
        var best: LexiconMatch? = null
        for (d in candidates) {
            val doc = docs[d] ?: continue
            val name = doc.fields.firstOrNull { it.field == field }?.tokens ?: continue
            if (name.size < tokens.size || (!lastIsPrefix && name.size != tokens.size)) continue
            var quality = 0f
            var ok = true
            for (i in tokens.indices) {
                val m = matchers[i].match(name[i])
                if (m == null || m.kind == MatchKind.INFIX) {
                    ok = false
                    break
                }
                quality += m.quality
            }
            if (!ok) continue
            val q = quality / tokens.size * tokens.size / name.size
            if (best == null || q > best.quality) best = LexiconMatch(doc.lexiconKey(), doc.displayName(), exact = false, quality = q)
        }
        return best
    }

    private fun Doc.lexiconKey(): String = when (type) {
        DocType.TRACK -> track!!.meta.key.value
        DocType.ARTIST -> artist!!.key.value
        DocType.ALBUM -> album!!.key
    }

    private fun Doc.displayName(): String = when (type) {
        DocType.TRACK -> track!!.meta.title
        DocType.ARTIST -> artist!!.name
        DocType.ALBUM -> album!!.title
    }

    private fun retainArtist(name: String) {
        val key = ArtistKey.of(name)
        if (key.value.isEmpty()) return
        val slot = artistSlots.getOrPut(key) {
            insert(Doc(DocType.ARTIST, DocBuilder.artist(name)).also { it.artist = ArtistRef(key, name) })
        }
        docs[slot]!!.refCount++
    }

    private fun releaseArtist(key: ArtistKey) {
        val slot = artistSlots[key] ?: return
        val doc = docs[slot]!!
        if (--doc.refCount <= 0) {
            artistSlots.remove(key)
            delete(slot)
        }
    }

    private fun retainAlbum(meta: TrackMeta) {
        val key = meta.albumKey ?: return
        val slot = albumSlots.getOrPut(key) {
            insert(Doc(DocType.ALBUM, DocBuilder.album(meta.album!!, meta.primaryArtist)).also { it.album = AlbumRef(key, meta.album!!, meta.primaryArtist) })
        }
        docs[slot]!!.refCount++
    }

    private fun releaseAlbum(key: String) {
        val slot = albumSlots[key] ?: return
        val doc = docs[slot]!!
        if (--doc.refCount <= 0) {
            albumSlots.remove(key)
            delete(slot)
        }
    }

    private fun insert(doc: Doc): Int {
        lookupCache.clear()
        val slot = if (freeSlots.isNotEmpty()) freeSlots.removeAt(freeSlots.lastIndex) else docs.size.also { docs.add(null) }
        docs[slot] = doc
        doc.fields.asSequence().flatMap { it.allTerms() }.distinct().forEach { vocabulary.addPosting(it, slot) }
        if (doc.nameKey.isNotEmpty()) {
            val names = namesByType.getValue(doc.type)
            names.getOrPut(doc.nameKey) { ArrayList(1) }.add(slot)
            val compact = doc.nameKey.replace(" ", "")
            if (compact != doc.nameKey) names.getOrPut(compact) { ArrayList(1) }.add(slot)
        }
        return slot
    }

    private fun delete(slot: Int) {
        val doc = docs[slot] ?: return
        doc.fields.asSequence().flatMap { it.allTerms() }.distinct().forEach { vocabulary.removePosting(it, slot) }
        if (doc.nameKey.isNotEmpty()) {
            val names = namesByType.getValue(doc.type)
            for (k in setOf(doc.nameKey, doc.nameKey.replace(" ", ""))) {
                names[k]?.let { list ->
                    list.remove(slot)
                    if (list.isEmpty()) names.remove(k)
                }
            }
        }
        docs[slot] = null
        lookupCache.clear()
        freeSlots += slot
    }

    companion object {
        val ALL_TYPES: Set<DocType> = DocType.entries.toSet()
        private const val LOOKUP_CACHE_SIZE = 512
    }
}
