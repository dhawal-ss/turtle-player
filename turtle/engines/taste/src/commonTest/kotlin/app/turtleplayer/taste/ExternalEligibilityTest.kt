package app.turtleplayer.taste

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExternalEligibilityTest {
    private val world = SyntheticWorld()
    private val tracks = world.tracks.distinctBy { it.artistKey }.take(8)

    /** Another upload of [track]: a new id, the same title and artist (the official video next to the audio upload). */
    private fun otherUpload(
        track: TrackMeta,
        id: String = "alt",
    ) = track.copy(key = TrackKey.youtube("$id-${track.key.value}"), youtubeId = "$id-${track.key.value}")

    private fun recording(track: TrackMeta) = SongKeys().of(track)

    @Test
    fun everythingEligibleKeepsTheGivenOrder() {
        val shuffled = tracks.reversed()
        assertEquals(shuffled, eligibleExternal(null, shuffled, UserControls()))
    }

    @Test
    fun aSecondUploadOfAHiddenRecordingStaysOut() {
        val hidden = tracks[2]
        val candidates = tracks + otherUpload(hidden)
        val eligible = eligibleExternal(null, candidates, UserControls(), hiddenRecordings = setOf(recording(hidden)))
        assertTrue(eligible.none { recording(it) == recording(hidden) }, "no upload of the hidden recording")
        assertEquals(tracks.size - 1, eligible.size)
    }

    @Test
    fun duplicateUploadsKeepTheFirst() {
        val alt = otherUpload(tracks[1])
        val candidates = listOf(tracks[0], alt, tracks[1], tracks[2])
        assertEquals(listOf(tracks[0], alt, tracks[2]), eligibleExternal(null, candidates, UserControls()))
    }

    @Test
    fun theSeedsOtherUploadStaysOut() {
        val seed = tracks[0]
        val candidates = listOf(otherUpload(seed)) + tracks.drop(1)
        assertEquals(tracks.drop(1), eligibleExternal(seed, candidates, UserControls()))
    }

    @Test
    fun withoutASeedNothingIsDroppedAsTheSeed() {
        assertEquals(tracks, eligibleExternal(null, tracks, UserControls()))
        // A seed that is not on the page drops nothing either.
        val absent = world.tracks.last()
        assertEquals(tracks, eligibleExternal(absent, tracks, UserControls()))
    }

    @Test
    fun mutedArtistsAndLessLikeThisTracksStayOut() {
        val controls = UserControls(mutedArtists = setOf(tracks[1].artistKey), lessLikeThisTracks = setOf(tracks[3].key))
        assertEquals(tracks - tracks[1] - tracks[3], eligibleExternal(null, tracks, controls))
    }

    @Test
    fun aHiddenFirstUploadAlsoTakesItsTwinAlong() {
        // The page's first upload of a recording is the hidden one: the later upload of it is a duplicate.
        val alt = otherUpload(tracks[0])
        val controls = UserControls(lessLikeThisTracks = setOf(tracks[0].key))
        assertEquals(tracks.drop(1), eligibleExternal(null, listOf(tracks[0], alt) + tracks.drop(1), controls))
    }
}
