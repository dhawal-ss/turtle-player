package app.turtleplayer.search

import kotlin.math.min

/** How a pattern is aligned against a text when measuring edit distance. */
enum class AlignMode {
    /** Pattern against the entire text: "colplay" vs "coldplay". */
    WHOLE,

    /** Pattern against the best prefix of the text, for as-you-type tokens: "bohem" vs "bohemian". */
    PREFIX,

    /** Pattern against the best substring of the text: "night" inside "midnight". */
    SUBSTRING,
}

/**
 * A query token compiled for bit-parallel approximate matching.
 *
 * Computes optimal string alignment distance (Levenshtein plus adjacent transpositions, so "teh"
 * vs "the" costs 1) in O(n) word operations for patterns up to 64 characters. This is Myers'
 * bit-vector algorithm (1999) in Hyyrö's formulation, with Hyyrö's transposition extension (2003).
 *
 * The dynamic-programming matrix D has one row per pattern character and one column per text
 * character. Instead of storing it, we keep the vertical deltas of the current column as two bit
 * vectors (VP: +1, VN: -1), one bit per pattern row, and update a whole column with a handful of
 * word operations. The score of the last row is tracked explicitly from the horizontal deltas.
 * The three [AlignMode]s differ only in the top boundary row: D[0][j] = j for WHOLE and PREFIX
 * (the pattern must start at the text start), D[0][j] = 0 for SUBSTRING (it may start anywhere),
 * and in whether the final or the minimal last-row score is reported.
 *
 * Instances are immutable and can be reused across threads and texts.
 */
class BitParallelPattern(val pattern: String) {
    private val length = pattern.length
    private val lastRowBit: Long
    private val asciiMasks = LongArray(ASCII_SIZE)
    private val otherMasks: Map<Char, Long>

    init {
        require(length in 1..MAX_LENGTH) { "Pattern length must be 1..$MAX_LENGTH, got $length" }
        lastRowBit = 1L shl (length - 1)
        val other = HashMap<Char, Long>()
        pattern.forEachIndexed { i, c ->
            val bit = 1L shl i
            if (c.code < ASCII_SIZE) asciiMasks[c.code] = asciiMasks[c.code] or bit else other[c] = (other[c] ?: 0L) or bit
        }
        otherMasks = other
    }

    /** Bit i is set when pattern[i] == c. */
    private fun equalityMask(c: Char): Long = if (c.code < ASCII_SIZE) asciiMasks[c.code] else otherMasks[c] ?: 0L

    /** Edit distance of the pattern against [text] under [mode]. Never exceeds the pattern length for PREFIX and SUBSTRING. */
    fun distance(text: CharSequence, mode: AlignMode): Int {
        val anchored = mode != AlignMode.SUBSTRING
        var vp = -1L // all vertical deltas +1: column 0 is D[i][0] = i
        var vn = 0L
        var score = length
        var best = length // empty prefix / substring: delete every pattern character
        var prevEq = 0L
        var prevD0 = 0L
        for (j in 0 until text.length) {
            val eq = equalityMask(text[j])
            // Transposition: pattern[i-1..i] == text[j]..text[j-1], and the previous column had no diagonal zero there.
            val tr = ((prevD0.inv() and eq) shl 1) and prevEq
            val x = eq or vn
            val d0 = (((eq and vp) + vp) xor vp) or x or tr
            val hp = vn or (d0 or vp).inv()
            val hn = d0 and vp
            if (hp and lastRowBit != 0L) score++ else if (hn and lastRowBit != 0L) score--
            // Shift horizontal deltas down one row. Row 0 grows by 1 per column when anchored.
            val hpShift = if (anchored) (hp shl 1) or 1L else hp shl 1
            val hnShift = hn shl 1
            vp = hnShift or (d0 or hpShift).inv()
            vn = hpShift and d0
            prevD0 = d0
            prevEq = eq
            if (score < best) best = score
        }
        return if (mode == AlignMode.WHOLE) score else best
    }

    companion object {
        const val MAX_LENGTH = 64
        private const val ASCII_SIZE = 128
    }
}

/** Entry point that also handles empty and over-long patterns. */
object EditDistance {
    /** Optimal string alignment distance of [pattern] against [text] under [mode]. */
    fun distance(pattern: String, text: String, mode: AlignMode = AlignMode.WHOLE): Int = when {
        pattern.isEmpty() -> if (mode == AlignMode.WHOLE) text.length else 0
        pattern.length <= BitParallelPattern.MAX_LENGTH -> BitParallelPattern(pattern).distance(text, mode)
        else -> dynamicProgramming(pattern, text, mode)
    }

    /** Plain O(m*n) fallback for patterns longer than one machine word (very rare for tokens). */
    internal fun dynamicProgramming(pattern: String, text: String, mode: AlignMode): Int {
        val m = pattern.length
        val n = text.length
        // Three rolling columns are enough for transpositions (needs column j-2).
        var prev2 = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        var best = m
        for (j in 1..n) {
            cur[0] = if (mode == AlignMode.SUBSTRING) 0 else j
            for (i in 1..m) {
                val cost = if (pattern[i - 1] == text[j - 1]) 0 else 1
                var v = min(min(prev[i] + 1, cur[i - 1] + 1), prev[i - 1] + cost)
                if (i > 1 && j > 1 && pattern[i - 1] == text[j - 2] && pattern[i - 2] == text[j - 1]) v = min(v, prev2[i - 2] + 1)
                cur[i] = v
            }
            if (cur[m] < best) best = cur[m]
            val t = prev2
            prev2 = prev
            prev = cur
            cur = t
        }
        return if (mode == AlignMode.WHOLE) prev[m] else best
    }
}

/** How many typos a query token of a given length may contain. */
object TypoTolerance {
    /** 0 for tokens of 1-3 characters, 1 for 4-7, 2 for 8 and longer. */
    fun maxTypos(length: Int): Int = when {
        length <= 3 -> 0
        length <= 7 -> 1
        else -> 2
    }
}

/**
 * 64-bit set of the characters in [s]: a-z and 0-9 get their own bit, everything else is hashed
 * into the remaining bits. Used as an exact-safe prefilter: every distinct query character missing
 * from a term costs at least one edit, so `popcount(query and not term) > k` rules the term out.
 */
internal fun charSignature(s: CharSequence): Long {
    var sig = 0L
    for (c in s) {
        val bit = when (c) {
            in 'a'..'z' -> c - 'a'
            in '0'..'9' -> 26 + (c - '0')
            else -> 36 + (c.code % 28)
        }
        sig = sig or (1L shl bit)
    }
    return sig
}
