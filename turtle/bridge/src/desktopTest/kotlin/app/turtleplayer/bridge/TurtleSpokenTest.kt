package app.turtleplayer.bridge

import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TurtleSpokenTest {
    @Test
    fun savedBooksRecognizeEveryChapterAfterARestart() {
        val book =
            BookProgress(
                bookId = "PL-w3-test",
                title = "Moby Dick",
                author = "Herman Melville",
                thumbnail = null,
                playlistId = "PL-w3-test",
                chapterId = "ch2-w3",
                chapterIndex = 1,
                chapterCount = 3,
                positionMs = 61_000L,
                durationMs = 1_800_000L,
                updatedMs = 1_700_000_000_000L,
            )
        val line = TurtleSpoken.encodeLine(book, listOf("ch1-w3", "ch2-w3", "ch3-w3"))

        TurtleSpoken.restore(line) // what load() does with the saved preference after a process restart

        assertEquals("PL-w3-test", TurtleSpoken.bookOf("ch1-w3"))
        assertEquals("PL-w3-test", TurtleSpoken.bookOf("ch3-w3"))
        val chapter = SongItem(id = "ch3-w3", title = "Chapter 3", thumbnail = null, artists = emptyList(), album = null)
        assertTrue(TurtleSpoken.isSpokenWord(chapter))
    }

    @Test
    fun bookLinesSavedBeforeChapterListsStillLoad() {
        val legacy =
            listOf("PL-old", "Moby Dick", "Herman Melville", "", "PL-old", "ch2-old", "1", "3", "61000", "1800000", "1700000000000")
                .joinToString("\u001F")

        val (progress, chapters) = assertNotNull(TurtleSpoken.decodeLine(legacy))

        assertEquals("ch2-old", progress.chapterId)
        assertEquals(1, progress.chapterIndex)
        assertEquals(61_000L, progress.positionMs)
        assertEquals(emptyList(), chapters)
    }
}
