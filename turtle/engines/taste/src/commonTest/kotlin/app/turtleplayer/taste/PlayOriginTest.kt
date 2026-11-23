package app.turtleplayer.taste

import app.turtleplayer.events.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PlayOriginTest {
    @Test
    fun legacyEngineTagsParseExactlyAsBefore() {
        assertEquals("taste/cf/d", SurfaceTag.format(CandidateSource.COLLABORATIVE, SlotKind.DISCOVERY))
        assertEquals(PlayOrigin(PlayPlace.ENGINE, CandidateSource.COLLABORATIVE, SlotKind.DISCOVERY), SurfaceTag.parse("taste/cf/d"))
        assertEquals("taste/session/f", SurfaceTag.format(PlayOrigin(PlayPlace.ENGINE, CandidateSource.SESSION, SlotKind.FAMILIAR)))
    }

    @Test
    fun homeTagsRoundTripAwkwardRowKeys() {
        for (row in listOf("daily_mix:artist:ac/dc", "section #3: a/b/c", "keep_listening")) {
            val o = PlayOrigin(PlayPlace.HOME_TURTLE, CandidateSource.LIBRARY, SlotKind.FAMILIAR, row)
            assertEquals(o, SurfaceTag.parse(SurfaceTag.format(o)))
        }
        assertEquals("home-yt/-/-/chip_x/y", SurfaceTag.format(PlayOrigin(PlayPlace.HOME_YOUTUBE, row = "chip_x/y")))
        assertEquals(PlayOrigin(PlayPlace.HOME_YOUTUBE, row = "chip_x/y"), SurfaceTag.parse("home-yt/-/-/chip_x/y"))
        assertEquals("search/-/-", SurfaceTag.format(PlayOrigin(PlayPlace.SEARCH, row = "")))
        assertEquals(PlayOrigin(PlayPlace.SEARCH), SurfaceTag.parse("search/-/-"))
        assertEquals("home-grid/-/-/a b", SurfaceTag.format(PlayOrigin(PlayPlace.HOME_GRID, row = "a\nb")))
    }

    @Test
    fun legacyInfinityTagsParse() {
        assertEquals(PlayOrigin(PlayPlace.INFINITY, CandidateSource.LIBRARY, SlotKind.FAMILIAR, "familiar"), SurfaceTag.parse("infinity/familiar"))
        assertEquals(PlayOrigin(PlayPlace.INFINITY, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, "genre:pop"), SurfaceTag.parse("infinity/genre:pop"))
        assertEquals(PlayOrigin(PlayPlace.INFINITY, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, "morelike"), SurfaceTag.parse("infinity/morelike"))
        assertEquals(PlayOrigin(PlayPlace.INFINITY, row = "foryou"), SurfaceTag.parse("infinity/foryou"))
    }

    @Test
    fun foreignDetailIsNotATag() {
        for (d in listOf(
            null, "", "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "Liked Songs", "My mix / 2024",
            "taste/session/discovery", "radio/bogus/d", "infinity", "home-yt/-", "nowhere/-/-",
        )) {
            assertNull(SurfaceTag.parse(d), "$d")
        }
    }

    @Test
    fun eachPlaceRecordsItsSurface() {
        assertEquals(Surface.SHELF, SurfaceTag.parse("home-grid/-/-")!!.surface)
        assertEquals(Surface.SEARCH, SurfaceTag.parse("search/-/-")!!.surface)
        assertEquals(Surface.RADIO, SurfaceTag.parse("radio/upstream/d")!!.surface)
        assertEquals(Surface.AUTOPLAY, SurfaceTag.parse("infinity/familiar")!!.surface)
        assertEquals(Surface.PLAYLIST, SurfaceTag.parse("shuffle/-/-")!!.surface)
    }
}
