package app.turtleplayer.ui.screens

import androidx.compose.ui.graphics.Color
import app.turtleplayer.bridge.MixAbout
import app.turtleplayer.bridge.MixSeedTie
import app.turtleplayer.taste.ShelfCadence
import app.turtleplayer.taste.ShelfKind
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MixSnapshotTest {
    private fun song(id: String, artist: String = "Artist $id") =
        SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 180)

    private fun snapshot(songs: List<SongItem>, about: MixAbout? = null) =
        MixSnapshot("turtle:daily_mix:0", "Daily Mix 1", "Made for you by Turtle", Color(0xFF3E6FD8), songs, about)

    @Test
    fun playKeepsTheOrderTheListShows() {
        val songs = listOf(song("c"), song("a"), song("b"), song("d"))
        val snap = snapshot(songs)
        val shown = snap.visible { false }
        assertEquals(songs, shown)
        for (i in shown.indices) {
            val play = snap.playFrom(shown, i)!!
            assertEquals(shown, play.songs)
            assertEquals(i, play.startIndex)
            assertEquals(shown[i], play.songs[play.startIndex])
        }
    }

    @Test
    fun hiddenSongsLeaveWithoutReorderingAndRowsStillMapToTheirSong() {
        val songs = listOf(song("a"), song("b"), song("c"), song("d"), song("e"))
        val snap = snapshot(songs)
        val shown = snap.visible { it.id == "b" || it.id == "d" }
        assertEquals(listOf("a", "c", "e"), shown.map { it.id })
        val play = snap.playFrom(shown, 2)!!
        assertEquals("e", play.songs[play.startIndex].id)
        assertEquals(listOf("a", "c", "e"), play.songs.map { it.id })
    }

    @Test
    fun rowsOutsideTheListPlayNothing() {
        val snap = snapshot(listOf(song("a")))
        val shown = snap.visible { false }
        assertNull(snap.playFrom(shown, 1))
        assertNull(snap.playFrom(shown, -1))
        assertNull(snap.playFrom(emptyList(), 0))
    }

    @Test
    fun aRefreshOfTheShelvesLeavesAnOpenSnapshotAsItWas() {
        val shelf = mutableListOf(song("a"), song("b"), song("c"))
        val snap = snapshot(shelf)
        // Home's shelves refresh (every few plays, every two hours): the row's songs change.
        shelf.clear()
        shelf += listOf(song("x"), song("y"))
        val refreshed = snapshot(shelf)
        assertEquals(listOf("a", "b", "c"), snap.songs.map { it.id })
        assertEquals(listOf("a", "b", "c"), snap.visible { false }.map { it.id })
        assertEquals(listOf("x", "y"), refreshed.songs.map { it.id })
    }

    @Test
    fun artistsComeFromTheMixWhenKnownOtherwiseFromItsSongs() {
        val songs = listOf(song("a", "Ada"), song("b", "Ada"), song("c", "Bo"), song("d", "Cy"), song("e", "Di"))
        val about = MixAbout(ShelfKind.DAILY_MIX, ShelfCadence.DAILY, seed = null, topArtists = listOf("Cy", "Bo"))
        assertEquals(listOf("Cy", "Bo"), snapshot(songs, about).artists())
        assertEquals(listOf("Ada", "Bo", "Cy"), snapshot(songs).artists())
        assertEquals(listOf("Ada", "Bo", "Cy"), snapshot(songs, about.copy(topArtists = emptyList())).artists())
    }

    private fun about(
        kind: ShelfKind,
        known: Int,
        total: Int,
        seed: String? = null,
        tie: MixSeedTie? = null,
    ) = MixAbout(kind, ShelfCadence.DAILY, seed, listOf("Drake"), known, total, tie)

    /** The sentence [about] gets, rendered from the real string resources. */
    private suspend fun said(about: MixAbout): String? = aboutWhat(about)?.let { getString(it.res, *it.args.toTypedArray()) }

    @Test
    fun knownSharesAreWholePercentsAndNeverReadAsNoneOrAllWhenMixed() {
        assertEquals(0, knownPercent(0, 30))
        assertEquals(100, knownPercent(30, 30))
        assertEquals(3, knownPercent(1, 30))
        assertEquals(1, knownPercent(1, 200)) // 0.5% would round to 0: some are known, so 1
        assertEquals(97, knownPercent(29, 30))
        assertEquals(99, knownPercent(199, 200)) // 99.5% would round to 100: one is new, so 99
        assertEquals(70, knownPercent(21, 30))
        assertEquals(50, knownPercent(1, 2))
        assertEquals(0, knownPercent(0, 0))
    }

    @Test
    fun aColdDailyMixSaysItIsAllNewNotMostlyKnown() =
        runBlocking {
            // The owner's case: a brand-new profile, "Drake Mix" built from onboarding picks, none of it heard.
            assertEquals("All new to you, picked for your taste.", said(about(ShelfKind.DAILY_MIX, known = 0, total = 30)))
            assertEquals("Built around Drake. All new to you so far.", said(about(ShelfKind.DAILY_MIX, known = 0, total = 30, seed = "Drake")))
        }

    @Test
    fun dailyMixesStateTheShareTheyHold() =
        runBlocking {
            assertEquals("You know 70% of these songs, the rest are new to you.", said(about(ShelfKind.DAILY_MIX, known = 21, total = 30)))
            assertEquals("You know 3% of these songs, the rest are new to you.", said(about(ShelfKind.DAILY_MIX, known = 1, total = 30)))
            assertEquals("You know 97% of these songs, the rest are new to you.", said(about(ShelfKind.DAILY_MIX, known = 29, total = 30)))
            assertEquals("You know all of these songs.", said(about(ShelfKind.DAILY_MIX, known = 30, total = 30)))
            assertEquals("Built around Drake. You know 60% of these songs.", said(about(ShelfKind.DAILY_MIX, known = 18, total = 30, seed = "Drake")))
            assertEquals("Built around Drake. You know all of these songs.", said(about(ShelfKind.DAILY_MIX, known = 12, total = 12, seed = "Drake")))
            // Without counts nothing is claimed about familiarity.
            assertEquals("Picked for your taste.", said(MixAbout(ShelfKind.DAILY_MIX, ShelfCadence.DAILY, null, emptyList())))
            assertEquals("Built around Drake.", said(MixAbout(ShelfKind.DAILY_MIX, ShelfCadence.DAILY, "Drake", emptyList())))
        }

    @Test
    fun otherMixesOnlyClaimWhatTheyHold() =
        runBlocking {
            assertEquals("Only songs you have never played, picked for your taste.", said(about(ShelfKind.WEEKLY_DISCOVERY, known = 0, total = 25)))
            assertEquals("Picked for your taste. You know 20% of it by now.", said(about(ShelfKind.WEEKLY_DISCOVERY, known = 5, total = 25)))
            assertEquals("New releases from your artists.", said(about(ShelfKind.FRESH_FOR_YOU, known = 0, total = 10)))
            assertEquals(
                "Songs like Hotline Bling, which you keep coming back to.",
                said(about(ShelfKind.MORE_LIKE, 3, 30, "Hotline Bling", MixSeedTie.REPLAYED)),
            )
            assertEquals("Songs like Hotline Bling, which you liked.", said(about(ShelfKind.MORE_LIKE, 3, 30, "Hotline Bling", MixSeedTie.LIKED)))
            assertEquals("Songs like Hotline Bling, which you played lately.", said(about(ShelfKind.MORE_LIKE, 3, 30, "Hotline Bling", MixSeedTie.LISTENED)))
            assertEquals("Songs like Hotline Bling.", said(about(ShelfKind.MORE_LIKE, 3, 30, "Hotline Bling")))
            assertNull(said(about(ShelfKind.MORE_LIKE, 3, 30)))
            assertEquals("Songs you loved but have not played for 60 days or more.", said(about(ShelfKind.REDISCOVER, 20, 20)))
            assertEquals("The songs you have finished most in the last 30 days.", said(about(ShelfKind.ON_REPEAT, 20, 20)))
        }

    @Test
    fun theShareIsCountedOverTheSongsThePageLists() =
        runBlocking {
            // The engine built 30 songs and knew 3 of them; Home lists 4 of the 30 and the listener hid one.
            val built = about(ShelfKind.DAILY_MIX, known = 3, total = 30, seed = "Drake").copy(knownSongs = setOf("a", "x", "y"))
            val songs = listOf(song("a"), song("b"), song("c"), song("d"))
            assertEquals(1 to 4, built.over(songs).let { it.known to it.total })
            assertEquals("Built around Drake. You know 25% of these songs.", said(built.over(songs)))
            // The one known song is hidden: none of the listed songs is known, whatever the engine counted.
            val shown = snapshot(songs, built).visible { it.id == "a" }
            assertEquals("Built around Drake. All new to you so far.", said(built.over(shown)))
            // Every listed song is known.
            assertEquals("You know all of these songs.", said(built.copy(seed = null).over(listOf(song("a")))))
            // Without counts the about makes no claim, whatever the page lists.
            val uncounted = MixAbout(ShelfKind.DAILY_MIX, ShelfCadence.DAILY, null, emptyList())
            assertEquals(uncounted, uncounted.over(songs))
            assertEquals("Picked for your taste.", said(uncounted.over(songs)))
        }

    @Test
    fun cadenceStaysAsItWas() =
        runBlocking {
            assertEquals("Refreshes daily", getString(aboutRefresh(about(ShelfKind.DAILY_MIX, 0, 30))))
            assertEquals("Refreshes on Mondays", getString(aboutRefresh(MixAbout(ShelfKind.WEEKLY_DISCOVERY, ShelfCadence.WEEKLY, null, emptyList()))))
            assertEquals("Updates as you listen", getString(aboutRefresh(MixAbout(ShelfKind.ON_REPEAT, null, null, emptyList()))))
        }
}
