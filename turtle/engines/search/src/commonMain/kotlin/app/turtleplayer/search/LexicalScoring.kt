package app.turtleplayer.search

import app.turtleplayer.core.CanonicalTitle
import app.turtleplayer.core.TextNormalizer
import app.turtleplayer.core.TitleCanonicalizer
import app.turtleplayer.core.TrackMeta
import kotlin.math.min

/** Kind of thing a search result points at. */
enum class DocType { TRACK, ARTIST, ALBUM }

/** Indexed field of a document. */
enum class Field {
    /** Canonical title: upload noise, featured artists and version markers removed. */
    TITLE,
    ARTIST,

    /** Artists featured in the title ("feat. X") but not credited. */
    FEATURED,
    ALBUM,

    /** Title words outside the canonical title: version markers such as "live" or "acoustic". */
    VERSION,
}

/** One token of a lexical query. */
data class QueryToken(
    val text: String,
    /** Match as a prefix too: the token is still being typed. */
    val prefix: Boolean = false,
    /** Adds score when present but is not required (plain-word filters like "live", "2024"). */
    val optional: Boolean = false,
    /** Field this token is expected in ("x by y": x in TITLE, y in ARTIST). Other fields still match, with a penalty. */
    val preferredField: Field? = null,
)

/** A query for [LexicalIndex], usually built from a [ParsedQuery]. */
class LexicalQuery(tokens: List<QueryToken>) {
    /** Capped: very long inputs are lyrics, which the lyrics index handles. */
    val tokens: List<QueryToken> = tokens.filter { it.text.isNotEmpty() }.take(MAX_TOKENS)

    companion object {
        const val MAX_TOKENS = 16

        /** Plain text query; the last token is treated as being typed when [asYouType]. */
        fun of(text: String, asYouType: Boolean = true): LexicalQuery {
            val t = TextNormalizer.normalize(text).tokens
            return LexicalQuery(t.mapIndexed { i, s -> QueryToken(s, prefix = asYouType && i == t.lastIndex) })
        }
    }
}

/** Why a document matched: which query token hit which term in which field. */
data class FieldMatch(
    val field: Field,
    val kind: MatchKind,
    val distance: Int,
    val queryToken: String,
    val term: String,
    val optional: Boolean,
)

/** A tokenized field. [joined] holds adjacent token pairs glued together so "acdc" finds "AC/DC". */
internal class IndexedField(val field: Field, val tokens: List<String>) {
    val joined: List<String> = if (field == Field.VERSION || field == Field.FEATURED) emptyList() else tokens.zipWithNext { a, b -> a + b }

    /** The whole field glued together when it is an initialism of 3+ letters: "mia" finds "M.I.A.", "rem" "R.E.M.". */
    val compact: String? =
        if (field != Field.VERSION && tokens.size >= 3 && tokens.all { it.length <= 2 }) tokens.joinToString("") else null

    fun allTerms(): Sequence<String> = tokens.asSequence() + joined.asSequence() + listOfNotNull(compact).asSequence()
}

/** Builds the indexed fields of each document type. Shared by the index and ad-hoc scoring of online results. */
internal object DocBuilder {
    fun track(meta: TrackMeta): Pair<CanonicalTitle, List<IndexedField>> {
        val canonical = TitleCanonicalizer.canonicalize(meta.title, meta.primaryArtist)
        val title = canonical.base.split(' ').filter { it.isNotEmpty() }
        val artists = meta.artists.flatMap { TextNormalizer.normalize(it).tokens }
        val artistSet = artists.toSet()
        val featured = canonical.featured.flatMap { TextNormalizer.normalize(it).tokens }.filter { it !in artistSet }
        val album = meta.album?.let { TextNormalizer.normalize(it).tokens }.orEmpty()
        val known = title.toSet() + artistSet + featured
        val version = TextNormalizer.normalize(meta.title).tokens.filter { it !in known }.distinct()
        val fields = listOf(
            IndexedField(Field.TITLE, title),
            IndexedField(Field.ARTIST, artists),
            IndexedField(Field.FEATURED, featured),
            IndexedField(Field.ALBUM, album),
            IndexedField(Field.VERSION, version),
        ).filter { it.tokens.isNotEmpty() }
        return canonical to fields
    }

    fun artist(name: String): List<IndexedField> = listOf(IndexedField(Field.ARTIST, TextNormalizer.normalize(name).tokens))

    fun album(title: String, artist: String): List<IndexedField> = listOf(
        IndexedField(Field.ALBUM, TitleCanonicalizer.canonicalize(title).base.split(' ').filter { it.isNotEmpty() }),
        IndexedField(Field.ARTIST, TextNormalizer.normalize(artist).tokens),
    ).filter { it.tokens.isNotEmpty() }
}

/** Score of one document for one query. [score] is normalized to [0, 1]. */
internal class DocScore(
    val score: Float,
    val matches: List<FieldMatch>,
    /** Share of required query tokens that matched anything. */
    val coverage: Float,
    /** Share of required query tokens whose best match was in each field. */
    val fieldShares: Map<Field, Float>,
    /** Share of tokens with a preferred field that matched there; null when no token had one. */
    val preferredAgreement: Float?,
)

/** A query compiled once and scored against many documents. */
internal class CompiledQuery(val tokens: List<QueryToken>) {
    val size = tokens.size
    val matchers = tokens.map { TermMatcher(it.text, it.prefix) }
    private val hasRequired = tokens.any { !it.optional }

    /** Required unless all tokens are optional (a query that is only "live" still has to match something). */
    fun isRequired(qi: Int) = !hasRequired || !tokens[qi].optional

    private val cache = Array(size) { HashMap<String, TermMatch?>() }

    /** Direct matching for documents outside the index (online results). */
    fun matchAdHoc(qi: Int, term: String): TermMatch? {
        val c = cache[qi]
        if (c.containsKey(term)) return c[term]
        return matchers[qi].match(term).also { c[term] = it }
    }
}

/**
 * Field-weighted scoring: title > artist > album, exact > prefix > fuzzy, a bonus when every query
 * token matched, when tokens appear in query order, and when the query covers the whole primary
 * field (title for tracks, name for artists and albums).
 */
internal object LexicalScorer {
    private const val ALL_MATCHED_BONUS = 0.1f
    private const val ORDER_BONUS = 0.04f
    private const val ADJACENT_BONUS = 0.04f
    private const val FULL_FIELD_BONUS = 0.15f
    private const val UNMATCHED_PRIMARY_PENALTY = 0.025f
    private const val MAX_PRIMARY_PENALTY = 0.1f
    private const val OPTIONAL_BONUS = 0.06f
    private const val MAX_OPTIONAL_BONUS = 0.12f
    private const val JOINED_FACTOR = 0.95f
    private const val PREFERENCE_MISMATCH = 0.8f
    private const val MAX_RAW = 1f + ALL_MATCHED_BONUS + ORDER_BONUS + ADJACENT_BONUS + FULL_FIELD_BONUS + MAX_OPTIONAL_BONUS

    fun weight(type: DocType, field: Field): Float = when (type) {
        DocType.TRACK -> when (field) {
            Field.TITLE -> 1f
            Field.ARTIST -> 0.8f
            Field.FEATURED -> 0.6f
            Field.ALBUM -> 0.5f
            Field.VERSION -> 0.35f
        }
        DocType.ARTIST -> if (field == Field.ARTIST) 1f else 0f
        DocType.ALBUM -> when (field) {
            Field.ALBUM -> 1f
            Field.ARTIST -> 0.6f
            else -> 0f
        }
    }

    fun primaryField(type: DocType): Field = when (type) {
        DocType.TRACK -> Field.TITLE
        DocType.ARTIST -> Field.ARTIST
        DocType.ALBUM -> Field.ALBUM
    }

    private fun inPreferred(pref: Field?, field: Field) = pref == null || pref == field || (pref == Field.ARTIST && field == Field.FEATURED)

    fun score(type: DocType, fields: List<IndexedField>, q: CompiledQuery, lookup: (Int, String) -> TermMatch?): DocScore? {
        val n = q.size
        if (n == 0) return null
        val best = FloatArray(n)
        val bestField = arrayOfNulls<IndexedField>(n)
        val bestPos = IntArray(n) { -1 }
        val bestSpan = IntArray(n)
        val bestMatch = arrayOfNulls<TermMatch>(n)
        val bestTerm = arrayOfNulls<String>(n)

        for (qi in 0 until n) {
            val pref = q.tokens[qi].preferredField
            for (f in fields) {
                val w = weight(type, f.field) * if (inPreferred(pref, f.field)) 1f else PREFERENCE_MISMATCH
                if (w <= best[qi]) continue
                for (pos in f.tokens.indices) {
                    val m = lookup(qi, f.tokens[pos]) ?: continue
                    val s = w * m.quality
                    if (s > best[qi]) {
                        best[qi] = s; bestField[qi] = f; bestPos[qi] = pos; bestSpan[qi] = 1; bestMatch[qi] = m; bestTerm[qi] = f.tokens[pos]
                    }
                }
                for (pos in f.joined.indices) {
                    val m = lookup(qi, f.joined[pos]) ?: continue
                    if (m.kind == MatchKind.INFIX) continue
                    val s = w * m.quality * JOINED_FACTOR
                    if (s > best[qi]) {
                        best[qi] = s; bestField[qi] = f; bestPos[qi] = pos; bestSpan[qi] = 2; bestMatch[qi] = m; bestTerm[qi] = f.joined[pos]
                    }
                }
                f.compact?.let { term ->
                    val m = lookup(qi, term)
                    if (m != null && m.kind != MatchKind.INFIX && w * m.quality * JOINED_FACTOR > best[qi]) {
                        best[qi] = w * m.quality * JOINED_FACTOR
                        bestField[qi] = f; bestPos[qi] = 0; bestSpan[qi] = f.tokens.size; bestMatch[qi] = m; bestTerm[qi] = term
                    }
                }
            }
        }

        var required = 0
        var matchedRequired = 0
        var sum = 0f
        for (qi in 0 until n) {
            if (!q.isRequired(qi)) continue
            required++
            if (best[qi] > 0f) {
                matchedRequired++
                sum += best[qi]
            }
        }
        if (matchedRequired == 0) return null
        val coverage = matchedRequired.toFloat() / required
        var s = (sum / matchedRequired) * coverage * coverage
        if (matchedRequired == required) s += ALL_MATCHED_BONUS

        // Token order: consecutive required tokens matched in the same field, in order and adjacent.
        var pairs = 0
        var ordered = 0
        var adjacent = 0
        var prev = -1
        for (qi in 0 until n) {
            if (!q.isRequired(qi) || best[qi] <= 0f) continue
            if (prev >= 0) {
                pairs++
                if (bestField[prev] === bestField[qi]) {
                    if (bestPos[qi] > bestPos[prev]) ordered++
                    if (bestPos[qi] == bestPos[prev] + bestSpan[prev]) adjacent++
                }
            }
            prev = qi
        }
        if (pairs > 0) s += ORDER_BONUS * ordered / pairs + ADJACENT_BONUS * adjacent / pairs

        // Primary field coverage: "yellow" should prefer "Yellow" over "Yellow Submarine".
        val primary = fields.firstOrNull { it.field == primaryField(type) }
        if (primary != null) {
            val covered = BooleanArray(primary.tokens.size)
            var qualitySum = 0f
            var hits = 0
            for (qi in 0 until n) {
                if (bestField[qi] !== primary) continue
                hits++
                qualitySum += bestMatch[qi]!!.quality
                for (p in bestPos[qi] until min(bestPos[qi] + bestSpan[qi], covered.size)) covered[p] = true
            }
            if (hits > 0) {
                val unmatched = covered.count { !it }
                s += if (unmatched == 0) FULL_FIELD_BONUS * (qualitySum / hits) else -min(MAX_PRIMARY_PENALTY, UNMATCHED_PRIMARY_PENALTY * unmatched)
            }
        }

        var optionalBonus = 0f
        for (qi in 0 until n) if (!q.isRequired(qi) && best[qi] > 0f) optionalBonus += OPTIONAL_BONUS * best[qi]
        s += min(optionalBonus, MAX_OPTIONAL_BONUS)

        val shares = HashMap<Field, Float>()
        var preferred = 0
        var preferredHit = 0
        val matches = ArrayList<FieldMatch>(n)
        for (qi in 0 until n) {
            val f = bestField[qi]
            val token = q.tokens[qi]
            if (q.isRequired(qi) && token.preferredField != null) {
                preferred++
                if (f != null && inPreferred(token.preferredField, f.field)) preferredHit++
            }
            if (f == null) continue
            if (q.isRequired(qi)) shares[f.field] = (shares[f.field] ?: 0f) + 1f / required
            val m = bestMatch[qi]!!
            matches += FieldMatch(f.field, m.kind, m.distance, token.text, bestTerm[qi]!!, optional = !q.isRequired(qi))
        }
        return DocScore(
            score = (s / MAX_RAW).coerceIn(0f, 1f),
            matches = matches,
            coverage = coverage,
            fieldShares = shares,
            preferredAgreement = if (preferred == 0) null else preferredHit.toFloat() / preferred,
        )
    }
}
