package app.turtleplayer.search

import kotlin.math.min
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BitParallelMatcherTest {
    @Test
    fun wholeTokenDistances() {
        assertEquals(0, EditDistance.distance("coldplay", "coldplay"))
        assertEquals(1, EditDistance.distance("colplay", "coldplay"))
        assertEquals(1, EditDistance.distance("teh", "the"), "adjacent transposition costs one edit")
        assertEquals(1, EditDistance.distance("bohemain", "bohemian"))
        assertEquals(2, EditDistance.distance("monkies", "monkeys"))
        assertEquals(3, EditDistance.distance("abc", ""))
    }

    @Test
    fun prefixModeMatchesBestPrefix() {
        assertEquals(0, EditDistance.distance("bohem", "bohemian", AlignMode.PREFIX))
        assertEquals(1, EditDistance.distance("bohen", "bohemian", AlignMode.PREFIX))
        assertEquals(1, EditDistance.distance("yelo", "yellow", AlignMode.PREFIX))
        // A prefix cannot start in the middle of the text.
        assertTrue(EditDistance.distance("mian", "bohemian", AlignMode.PREFIX) >= 3)
    }

    @Test
    fun substringModeFindsTokenInsideText() {
        assertEquals(0, EditDistance.distance("night", "midnight", AlignMode.SUBSTRING))
        assertEquals(1, EditDistance.distance("nigth", "midnight", AlignMode.SUBSTRING))
        assertEquals(0, EditDistance.distance("mian", "bohemian", AlignMode.SUBSTRING))
    }

    @Test
    fun nonAsciiCharactersMatch() {
        assertEquals(0, EditDistance.distance("夜に", "夜に駆ける", AlignMode.PREFIX))
        assertEquals(1, EditDistance.distance("ab夜", "ab夜x"))
    }

    @Test
    fun agreesWithReferenceDynamicProgrammingOnRandomStrings() {
        val random = Random(42)
        val alphabet = "abcde"
        repeat(4000) {
            val p = randomString(random, alphabet, 1 + random.nextInt(12))
            val t = randomString(random, alphabet, random.nextInt(16))
            for (mode in AlignMode.entries) {
                assertEquals(
                    referenceOsa(p, t, mode),
                    BitParallelPattern(p).distance(t, mode),
                    "pattern=$p text=$t mode=$mode",
                )
            }
        }
    }

    @Test
    fun handlesFullWordPatternsAndLongFallback() {
        val random = Random(7)
        repeat(300) {
            val p = randomString(random, "abc", 60 + random.nextInt(10)) // straddles the 64-bit word limit
            val t = randomString(random, "abc", random.nextInt(80))
            for (mode in AlignMode.entries) {
                assertEquals(referenceOsa(p, t, mode), EditDistance.distance(p, t, mode), "len=${p.length} mode=$mode")
            }
        }
    }

    @Test
    fun typoToleranceByLength() {
        assertEquals(listOf(0, 0, 0, 1, 1, 1, 1, 2, 2), (1..9).map { TypoTolerance.maxTypos(it) })
    }

    private fun randomString(random: Random, alphabet: String, length: Int) =
        buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }

    /** Textbook optimal-string-alignment DP, written independently of the production fallback. */
    private fun referenceOsa(p: String, t: String, mode: AlignMode): Int {
        val m = p.length
        val n = t.length
        val d = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) d[i][0] = i
        for (j in 0..n) d[0][j] = if (mode == AlignMode.SUBSTRING) 0 else j
        for (i in 1..m) for (j in 1..n) {
            var v = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + if (p[i - 1] == t[j - 1]) 0 else 1)
            if (i > 1 && j > 1 && p[i - 1] == t[j - 2] && p[i - 2] == t[j - 1]) v = min(v, d[i - 2][j - 2] + 1)
            d[i][j] = v
        }
        return if (mode == AlignMode.WHOLE) d[m][n] else (0..n).minOf { d[m][it] }
    }
}
