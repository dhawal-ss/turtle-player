package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NotInterestedTest {
    private fun song(id: String) =
        SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, "Artist $id")), album = null)

    @Test
    fun onlyTheMenuOpenedForTheItemGetsIt() {
        var now = 0L
        val latch = HomeMenuLatch({ now })
        val target = NotInterested.Song(song("a"))
        latch.arm(target)
        assertNull(latch.claim("Another song")) // a different menu opened first: the arm is spent
        assertNull(latch.claim("Song a"))
        latch.arm(target)
        now += 2_999
        assertEquals(target, latch.claim("Song a"))
        assertNull(latch.claim("Song a")) // consumed
        latch.arm(target)
        now += 3_001
        assertNull(latch.claim("Song a")) // too late: some other surface's menu
        latch.arm(target)
        latch.disarm()
        assertNull(latch.claim("Song a"))
    }

    @Test
    fun hiddenRecordsRoundTripAndSurviveDamage() {
        val entries =
            listOf(
                HiddenEntry(HiddenKind.SONG, "yt:a", "Tab\tand\nnewline", "Artist a", "https://x/y.jpg"),
                HiddenEntry(HiddenKind.ARTIST, "coldplay", "Coldplay"),
                HiddenEntry(HiddenKind.MIX, "mix:coldplay", "Coldplay Mix"),
            )
        val decoded = HiddenRecords.decode(HiddenRecords.encode(entries))
        assertEquals(entries.drop(1), decoded.drop(1))
        assertEquals("Tab and newline", decoded[0].title)
        assertEquals(emptyList(), HiddenRecords.decode(null))
        assertEquals(emptyList(), HiddenRecords.decode("garbage\u0000"))
    }

    @Test
    fun homeHidesSongsArtistsAndMixes() {
        val set = HiddenSet(songIds = setOf("a"), artists = setOf(ArtistKey.of("Artist b")), homeShelfKeys = setOf("turtle:daily_mix:0"))
        assertTrue(set.hides(song("a")))
        assertTrue(set.hides(song("b")))
        assertFalse(set.hides(song("c")))
        assertTrue(set.hides(ArtistItem(id = "UCb", title = "Artist b", thumbnail = null)))
        assertTrue(set.hidesShelf("turtle:daily_mix:0"))
    }
}
