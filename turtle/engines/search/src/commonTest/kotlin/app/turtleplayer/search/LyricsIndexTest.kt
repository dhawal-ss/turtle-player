package app.turtleplayer.search

import app.turtleplayer.core.TextNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricsIndexTest {
    // Invented lyrics.
    private val harbor = track("harbor", "Paper Lanterns", "Harbor Lights")
    private val static = track("static", "The Velvet Owls", "Static Hearts")
    private val index = LyricsIndex().apply {
        put(
            harbor,
            """
            [00:12.30] we left the porch light burning for the boats that never came
            [00:18.00] salt is in the letters that you folded with my name
            [00:24.10] every gull above the water knows the way you used to sing
            """.trimIndent(),
        )
        put(
            static,
            """
            radio hums a secret only midnight understands
            neon on the pavement like a fortune on our hands
            you know the way
            """.trimIndent(),
        )
    }

    private fun search(text: String, prefix: Boolean = false) = index.search(TextNormalizer.normalize(text).tokens, prefix)

    @Test
    fun findsFragmentAndReportsTheLine() {
        val hit = search("salt is in the letters").single()
        assertEquals(harbor.key, hit.track.key)
        assertEquals(1, hit.lineIndex)
        assertEquals("salt is in the letters that you folded with my name", hit.line, "timestamps stripped, casing kept")
        assertTrue(hit.score > 0.95f)
    }

    @Test
    fun toleratesTyposAndSkippedWords() {
        val hit = search("every gull abvoe the watter knows").first()
        assertEquals(harbor.key, hit.track.key)
        assertEquals(2, hit.lineIndex)
        assertEquals(harbor.key, search("letters you folded with my name").first().track.key, "a skipped word")
    }

    @Test
    fun matchesAcrossALineBreak() {
        val hit = search("only midnight understands neon on the pavement").first()
        assertEquals(static.key, hit.track.key)
        assertEquals(0, hit.lineIndex)
    }

    @Test
    fun lastTokenAsPrefixWhileTyping() {
        assertEquals(harbor.key, search("the boats that nev", prefix = true).first().track.key)
    }

    @Test
    fun ignoresShortOrWeakFragments() {
        assertTrue(search("salt letters").isEmpty(), "fewer than three words")
        assertTrue(search("the way you dance in moonlight tonight").isEmpty(), "too few of the words appear together")
    }

    @Test
    fun wordOrderMatters() {
        val inOrder = search("porch light burning boats").first().score
        val shuffled = search("boats burning light porch").firstOrNull()?.score ?: 0f
        assertTrue(inOrder > shuffled)
    }

    @Test
    fun removeAndReplace() {
        index.remove(harbor.key)
        assertTrue(search("salt is in the letters").isEmpty())
        index.put(harbor, "a completely new verse about salt and letters\nsecond line")
        assertEquals(0, search("new verse about salt").single().lineIndex)
        assertEquals(2, index.size)
    }
}
