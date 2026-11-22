package app.turtleplayer.search

import kotlin.math.abs
import kotlin.math.min

/** How one query token matched one indexed term. */
enum class MatchKind {
    EXACT,

    /** Query token is an exact prefix of the term ("bohem" -> "bohemian"). */
    PREFIX,

    /** Whole-token match within the typo tolerance. */
    FUZZY,

    /** A prefix of the term matches within the typo tolerance ("bohen" -> "bohemian"). */
    FUZZY_PREFIX,

    /** The query token appears inside a longer term ("night" in "midnight"). */
    INFIX,
}

/** Result of matching a query token against a term. [quality] is in (0, 1]; 1 is an exact match. */
class TermMatch(val kind: MatchKind, val distance: Int, val quality: Float) {
    override fun toString(): String = "$kind/d$distance/${quality.toString().take(4)}"
}

/**
 * Matches one normalized query token against indexed terms with the product's tolerance rules:
 * exact beats prefix beats fuzzy, typos allowed by [TypoTolerance], and the token being typed
 * ([asPrefix]) also matches as a prefix.
 *
 * Quality bands (chosen so the ordering holds for any lengths):
 * EXACT 1.0 > PREFIX (typed token) 0.76..0.96 > FUZZY 0.75 (1 typo) / 0.6 (2 typos)
 * > FUZZY_PREFIX 0.5..0.62 and PREFIX of a non-typed token 0.5..0.7 > INFIX 0.45.
 */
class TermMatcher(
    val token: String,
    val asPrefix: Boolean,
    /** Strict mode for name lookups: no partial prefixes of finished tokens and no infix matches. */
    strict: Boolean = false,
) {
    val maxTypos: Int = TypoTolerance.maxTypos(token.length)
    internal val signature: Long = charSignature(token)
    private val pattern: BitParallelPattern? =
        if (maxTypos > 0 && token.length <= BitParallelPattern.MAX_LENGTH) BitParallelPattern(token) else null

    /**
     * Adjacent-swap spellings of a 3-character token ("teh" -> "eth", "the"). Such short tokens allow
     * no other typo: one substitution turns too many real words into each other ("cat", "car").
     */
    internal val transpositions: List<String> =
        if (maxTypos == 0 && token.length == SWAP_LENGTH) {
            (0 until token.length - 1).filter { token[it] != token[it + 1] }.map { i ->
                token.substring(0, i) + token[i + 1] + token[i] + token.substring(i + 2)
            }
        } else {
            emptyList()
        }

    /** Whether a non-typed token may still match as a prefix ("bohem rhapsody"). */
    private val partialPrefix = !strict && !asPrefix && token.length >= PARTIAL_PREFIX_MIN
    private val infix = !strict && token.length >= INFIX_MIN

    /** Shortest term length that could match. */
    internal val minTermLength: Int get() = maxOf(1, token.length - maxTypos)

    /** Longest term length that could match, or [Int.MAX_VALUE] when prefixes/infixes are allowed. */
    internal val maxTermLength: Int get() =
        if (asPrefix || partialPrefix || infix) Int.MAX_VALUE else token.length + maxTypos

    fun match(term: String, termSignature: Long = charSignature(term)): TermMatch? {
        val q = token.length
        val t = term.length
        if (q == 0) return null
        if (term == token) return EXACT
        if (t > q && term.startsWith(token)) {
            val ratio = q.toFloat() / t
            if (asPrefix) return TermMatch(MatchKind.PREFIX, 0, 0.76f + 0.2f * ratio)
            if (partialPrefix) return TermMatch(MatchKind.PREFIX, 0, 0.5f + 0.2f * ratio)
        }
        if (t == q && term in transpositions) return SWAPPED
        val p = pattern ?: return null
        // Prefilter: each distinct query character absent from the term needs at least one edit.
        if ((signature and termSignature.inv()).countOneBits() > maxTypos) return null
        if (abs(t - q) <= maxTypos) {
            val d = p.distance(term, AlignMode.WHOLE)
            if (d <= maxTypos) return TermMatch(MatchKind.FUZZY, d, 0.9f - 0.15f * d)
        }
        if (asPrefix && t > q) {
            val d = p.distance(term, AlignMode.PREFIX)
            if (d <= maxTypos) return TermMatch(MatchKind.FUZZY_PREFIX, d, 0.55f + 0.15f * min(1f, q.toFloat() / t) - 0.08f * d)
        }
        if (infix && t > q) {
            // Infix matches are weak evidence, so they get one typo less than whole tokens.
            val d = p.distance(term, AlignMode.SUBSTRING)
            if (d <= maxTypos - 1) return TermMatch(MatchKind.INFIX, d, 0.45f - 0.08f * d)
        }
        return null
    }

    companion object {
        /** Non-typed tokens this long may still match as a prefix of a term. */
        const val PARTIAL_PREFIX_MIN = 4

        /** Tokens this long may match inside longer terms. */
        const val INFIX_MIN = 5

        /** Short tokens of this length may swap two adjacent characters. */
        const val SWAP_LENGTH = 3

        private val EXACT = TermMatch(MatchKind.EXACT, 0, 1f)
        private val SWAPPED = TermMatch(MatchKind.FUZZY, 1, 0.75f)
    }
}

/** Minimal growable int list (postings), avoiding boxed collections on the hot path. */
internal class IntList(capacity: Int = 4) {
    private var data = IntArray(capacity)
    var size: Int = 0
        private set

    operator fun get(i: Int): Int = data[i]

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(maxOf(4, size * 2))
        data[size++] = v
    }

    /** Removes every occurrence of [v], keeping order. */
    fun removeValue(v: Int) {
        var w = 0
        for (r in 0 until size) if (data[r] != v) data[w++] = data[r]
        size = w
    }

    fun isEmpty() = size == 0

    inline fun forEach(action: (Int) -> Unit) {
        for (i in 0 until size) action(this[i])
    }
}

/** A distinct normalized token with its postings. */
internal class Term(val text: String) {
    val signature: Long = charSignature(text)
    val postings = IntList()
}

/**
 * Term dictionary shared by the lexical and lyrics indexes. Terms are bucketed by length so a
 * fuzzy query only scans lengths that can match, and each term carries a character signature for
 * a cheap exact-safe prefilter before the bit-parallel matcher runs.
 *
 * This is the hot loop the plan earmarks for Rust; the Kotlin version stays as the reference.
 */
internal class Vocabulary {
    private val terms = HashMap<String, Term>()
    private val buckets = Array(MAX_BUCKET + 1) { ArrayList<Term>() }
    private var deadTerms = 0

    val size: Int get() = terms.size

    operator fun get(text: String): Term? = terms[text]

    fun intern(text: String): Term = terms.getOrPut(text) { Term(text).also { buckets[bucket(text.length)].add(it) } }

    fun addPosting(text: String, posting: Int) = intern(text).postings.add(posting)

    fun removePosting(text: String, posting: Int) {
        val term = terms[text] ?: return
        term.postings.removeValue(posting)
        if (term.postings.isEmpty()) {
            deadTerms++
            if (deadTerms > 1024 && deadTerms > terms.size / 4) compact()
        }
    }

    /** Calls [onMatch] for every term with postings that [matcher] accepts. */
    fun forEachMatch(matcher: TermMatcher, onMatch: (Term, TermMatch) -> Unit) {
        if (matcher.maxTypos == 0 && matcher.maxTermLength == matcher.token.length) {
            // Exact-only token (and its adjacent swaps): hash lookups are enough.
            for (text in listOf(matcher.token) + matcher.transpositions) {
                terms[text]?.takeIf { !it.postings.isEmpty() }?.let { term -> matcher.match(term.text, term.signature)?.let { onMatch(term, it) } }
            }
            return
        }
        val from = bucket(matcher.minTermLength)
        val to = if (matcher.maxTermLength == Int.MAX_VALUE) MAX_BUCKET else bucket(matcher.maxTermLength)
        for (b in from..to) {
            for (term in buckets[b]) {
                if (term.postings.isEmpty()) continue
                val m = matcher.match(term.text, term.signature) ?: continue
                onMatch(term, m)
            }
        }
    }

    private fun compact() {
        terms.values.removeAll { it.postings.isEmpty() }
        for (b in buckets) b.removeAll { it.postings.isEmpty() }
        deadTerms = 0
    }

    private fun bucket(length: Int) = min(length, MAX_BUCKET)

    private companion object {
        const val MAX_BUCKET = 32
    }
}
