package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.TrackKey
import com.metrolist.shared.settings.BlockedArtist
import kotlin.test.Test
import kotlin.test.assertEquals

class UpstreamBlocksTest {
    @Test
    fun blockedSongsAndArtistsBecomeEngineKeys() {
        val set =
            blockedSetOf(
                songIds = setOf("v1"),
                artists = listOf(BlockedArtist("UC1", "Coldplay - Topic"), BlockedArtist("UC2", "UC2"), BlockedArtist("UC3", "UC3")),
                resolved = mapOf("UC2" to "Adele"),
            )
        assertEquals(setOf(TrackKey.youtube("v1")), set.tracks)
        assertEquals(setOf(ArtistKey.of("Coldplay"), ArtistKey.of("Adele")), set.artists) // UC3 waits for its name
    }
}
