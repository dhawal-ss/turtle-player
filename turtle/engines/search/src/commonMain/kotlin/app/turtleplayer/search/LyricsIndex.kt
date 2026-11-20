package app.turtleplayer.search

import app.turtleplayer.core.TextNormalizer
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlin.math.max
import kotlin.math.min

/** A lyrics fragment match. */
data class LyricsHit(
    val track: TrackMeta,
    /** Index of the first matched line in the track's lyrics. */
    val lineIndex: Int,
    /** The matched line as stored (original casing), for display. */
    val line: String,
    /** In [0, 1]: share of query tokens found in order, weighted by match quality. */
    val score: Float,
    val matchedTokens: Int,
)

/**
 * Lyrics of tracks the user played or saved. A fragment of [MIN_FRAGMENT_TOKENS]+ words is matched
 * against windows of two consecutive lines (fragments often span a line break), tolerating the
 * same typos as the lexical index, and scored by a quality-weighted in-order alignment.
 *
 * Not thread-safe; [LocalLibrary] serializes access.
 */
class LyricsIndex {
    private class Doc(val track: TrackMeta, val lines: List<String>, val lineTokens: List<List<String>>)

    private val vocabulary = Vocabulary()
    private val docs = ArrayList<Doc?>()
    private val freeSlots = ArrayList<Int>()
    private val slots = HashMap<TrackKey, Int>()

    val size: Int get() = slots.size

    operator fun contains(key: TrackKey): Boolean = key in slots

    /** Adds or replaces lyrics. Plain text with one lyric line per text line; LRC timestamps are stripped. */
    fun put(track: TrackMeta, lyrics: String) {
        remove(track.key)
        val lines = lyrics.lines().map { it.replace(lrcTimestamp, "").trim() }.filter { it.isNotEmpty() }.take(MAX_LINES)
        if (lines.isEmpty()) return
        val tokens = lines.map { TextNormalizer.normalize(it).tokens }
        val slot = if (freeSlots.isNotEmpty()) freeSlots.removeAt(freeSlots.lastIndex) else docs.size.also { docs.add(null) }
        require(slot < MAX_DOCS) { "Lyrics index is full" }
        docs[slot] = Doc(track, lines, tokens)
        slots[track.key] = slot
        tokens.forEachIndexed { line, lineTokens -> lineTokens.distinct().forEach { vocabulary.addPosting(it, posting(slot, line)) } }
    }

    fun remove(key: TrackKey): Boolean {
        val slot = slots.remove(key) ?: return false
        val doc = docs[slot]!!
        doc.lineTokens.forEachIndexed { line, lineTokens -> lineTokens.distinct().forEach { vocabulary.removePosting(it, posting(slot, line)) } }
        docs[slot] = null
        freeSlots += slot
        return true
    }

    /** Best matching line per track, best first. Returns nothing for fragments shorter than [MIN_FRAGMENT_TOKENS]. */
    fun search(fragment: List<String>, lastIsPrefix: Boolean = false, limit: Int = 10): List<LyricsHit> {
        val tokens = fragment.take(MAX_QUERY_TOKENS)
        if (tokens.size < MIN_FRAGMENT_TOKENS) return emptyList()
        val matchers = tokens.mapIndexed { i, t -> TermMatcher(t, lastIsPrefix && i == tokens.lastIndex) }
        val termMatches = Array(tokens.size) { HashMap<String, TermMatch>() }
        // Window (slot, start line) -> bitmask of query tokens seen in lines start and start + 1.
        val windows = HashMap<Int, Int>()
        for (qi in tokens.indices) {
            val bit = 1 shl qi
            vocabulary.forEachMatch(matchers[qi]) { term, match ->
                if (match.kind == MatchKind.INFIX) return@forEachMatch
                termMatches[qi][term.text] = match
                term.postings.forEach { p ->
                    windows[p] = (windows[p] ?: 0) or bit
                    if (lineOf(p) > 0) windows[p - 1] = (windows[p - 1] ?: 0) or bit
                }
            }
        }
        val needed = max(MIN_FRAGMENT_TOKENS, (tokens.size * MIN_TOKEN_SHARE).toInt())
        val bestPerDoc = HashMap<Int, LyricsHit>()
        for ((window, mask) in windows) {
            if (mask.countOneBits() < needed) continue
            val slot = slotOf(window)
            val doc = docs[slot] ?: continue
            val start = lineOf(window)
            val first = doc.lineTokens.getOrNull(start) ?: continue
            val text = first + doc.lineTokens.getOrElse(start + 1) { emptyList() }
            val (weight, count, firstMatchPos) = align(tokens.size, text) { qi, term -> termMatches[qi][term] }
            if (count < needed) continue
            val score = weight / tokens.size
            if (score < MIN_SCORE) continue
            val line = if (firstMatchPos >= first.size) start + 1 else start
            val hit = LyricsHit(doc.track, line, doc.lines[line], score, count)
            val current = bestPerDoc[slot]
            if (current == null || hit.score > current.score || (hit.score == current.score && hit.lineIndex < current.lineIndex)) bestPerDoc[slot] = hit
        }
        return bestPerDoc.values.sortedWith(compareByDescending<LyricsHit> { it.score }.thenBy { it.track.key.value }).take(limit)
    }

    /**
     * Quality-weighted longest common subsequence of query tokens and window tokens.
     * Returns (weight, matched count, window position of the first matched token).
     */
    private inline fun align(q: Int, text: List<String>, match: (Int, String) -> TermMatch?): Triple<Float, Int, Int> {
        val w = text.size
        val dp = Array(q + 1) { FloatArray(w + 1) }
        val count = Array(q + 1) { IntArray(w + 1) }
        val firstPos = Array(q + 1) { IntArray(w + 1) { Int.MAX_VALUE } }
        for (i in 1..q) for (j in 1..w) {
            var best = dp[i - 1][j]
            var bestCount = count[i - 1][j]
            var bestFirst = firstPos[i - 1][j]
            if (dp[i][j - 1] > best) {
                best = dp[i][j - 1]; bestCount = count[i][j - 1]; bestFirst = firstPos[i][j - 1]
            }
            val m = match(i - 1, text[j - 1])
            if (m != null && dp[i - 1][j - 1] + m.quality > best) {
                best = dp[i - 1][j - 1] + m.quality
                bestCount = count[i - 1][j - 1] + 1
                bestFirst = min(firstPos[i - 1][j - 1], j - 1)
            }
            dp[i][j] = best
            count[i][j] = bestCount
            firstPos[i][j] = bestFirst
        }
        return Triple(dp[q][w], count[q][w], firstPos[q][w])
    }

    private fun posting(slot: Int, line: Int) = (slot shl LINE_BITS) or line
    private fun slotOf(posting: Int) = posting ushr LINE_BITS
    private fun lineOf(posting: Int) = posting and ((1 shl LINE_BITS) - 1)

    companion object {
        const val MIN_FRAGMENT_TOKENS = 3
        private const val MAX_QUERY_TOKENS = 24
        private const val MIN_TOKEN_SHARE = 0.6f
        private const val MIN_SCORE = 0.55f
        private const val LINE_BITS = 11
        private const val MAX_LINES = (1 shl LINE_BITS) - 1
        private const val MAX_DOCS = 1 shl (31 - LINE_BITS)
        private val lrcTimestamp = Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]""")
    }
}
