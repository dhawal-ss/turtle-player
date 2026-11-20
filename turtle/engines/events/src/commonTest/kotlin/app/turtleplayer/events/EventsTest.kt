package app.turtleplayer.events

import app.turtleplayer.core.Clock
import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.imports.ExportFile
import app.turtleplayer.events.imports.ListenBrainzHistoryImporter
import app.turtleplayer.events.imports.SpotifyHistoryImporter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeClock(var now: Long = 1_700_000_000_000L) : Clock {
    override fun nowMs() = now
    fun advance(ms: Long) {
        now += ms
    }
}

private fun track(id: String, durationMs: Long = 200_000) =
    TrackMeta(TrackKey.youtube(id), "Song $id", listOf("Artist $id"), durationMs = durationMs)

class ListeningSessionTrackerTest {
    @Test
    fun infersEndReasonsAndExcludesPausedTime() = runTest {
        val clock = FakeClock()
        val store = InMemoryPlayEventStore()
        val tracker = ListeningSessionTracker(store, clock)

        tracker.onSignal(PlaybackSignal.TrackStarted(track("a"), StartReason.TAPPED, Surface.SEARCH))
        clock.advance(10_000)
        tracker.onSignal(PlaybackSignal.PlayingChanged(false))
        clock.advance(60_000) // paused: must not count
        tracker.onSignal(PlaybackSignal.PlayingChanged(true))
        clock.advance(5_000)
        tracker.onSignal(PlaybackSignal.TrackStarted(track("b"), StartReason.USER_NEXT, Surface.AUTOPLAY))
        clock.advance(200_000)
        tracker.onSignal(PlaybackSignal.TrackStarted(track("c"), StartReason.AUTOPLAY, Surface.AUTOPLAY))
        clock.advance(1_000)
        tracker.flush()

        val events = store.since(0)
        assertEquals(listOf("yt:a", "yt:b", "yt:c"), events.map { it.trackKey.value })
        val a = events[0]
        assertEquals(15_000, a.msPlayed)
        assertEquals(EndReason.SKIPPED, a.endReason)
        assertTrue(a.isQuickSkip)
        assertEquals(EndReason.COMPLETED, events[1].endReason)
        assertTrue(events[1].isFinished)
        assertEquals(listOf(0, 1, 2), events.map { it.position })
        assertEquals(1, events.map { it.sessionId }.distinct().size)
    }

    @Test
    fun longGapStartsNewSessionAndRestartIsReplay() = runTest {
        val clock = FakeClock()
        val store = InMemoryPlayEventStore()
        val tracker = ListeningSessionTracker(store, clock)

        tracker.onSignal(PlaybackSignal.TrackStarted(track("a"), StartReason.TAPPED, Surface.SEARCH))
        clock.advance(150_000)
        tracker.onSignal(PlaybackSignal.Seeked(150_000, 0))
        tracker.onSignal(PlaybackSignal.Feedback(FeedbackKind.LIKED))
        clock.advance(30_000)
        tracker.onSignal(PlaybackSignal.TrackEnded(EndReason.STOPPED))
        clock.advance(2 * 60 * 60_000L)
        tracker.onSignal(PlaybackSignal.TrackStarted(track("b"), StartReason.TAPPED, Surface.SHELF))
        tracker.flush()

        val (a, b) = store.since(0)
        assertTrue(a.replayed)
        assertTrue(a.liked)
        assertEquals(1, a.seeks)
        assertTrue(a.sessionId != b.sessionId)
        assertEquals(0, b.position)
    }
}

class HistoryImportTest {
    // Reduced from a real Extended Streaming History export (identifying fields removed).
    private val extended = """
        [
          {"ts":"2024-03-01T20:03:30Z","platform":"android","ms_played":210000,"conn_country":"DE",
           "master_metadata_track_name":"Blinding Lights","master_metadata_album_artist_name":"The Weeknd",
           "master_metadata_album_album_name":"After Hours","spotify_track_uri":"spotify:track:0VjIjW4GlUZAMYd2vXMi3b",
           "episode_name":null,"reason_start":"clickrow","reason_end":"trackdone","shuffle":false,"skipped":false},
          {"ts":"2024-03-01T20:03:42Z","platform":"android","ms_played":12000,
           "master_metadata_track_name":"Save Your Tears","master_metadata_album_artist_name":"The Weeknd",
           "master_metadata_album_album_name":"After Hours","spotify_track_uri":"spotify:track:5QO79kh1waicV47BqGRL3g",
           "reason_start":"trackdone","reason_end":"fwdbtn","shuffle":false,"skipped":true},
          {"ts":"2024-03-01T20:40:00Z","ms_played":1800000,"master_metadata_track_name":null,
           "master_metadata_album_artist_name":null,"episode_name":"Some Podcast Episode",
           "reason_start":"clickrow","reason_end":"endplay"},
          {"ts":"2024-03-02T09:00:00Z","ms_played":180000,"master_metadata_track_name":"Blinding Lights (Official Audio)",
           "master_metadata_album_artist_name":"The Weeknd","reason_start":"clickrow","reason_end":"trackdone","unknown_new_field":1}
        ]
    """.trimIndent()

    @Test
    fun importsExtendedHistoryWithReasonsAndSessions() = runTest {
        val store = InMemoryPlayEventStore()
        val report = SpotifyHistoryImporter(store).import(
            listOf(
                ExportFile("Spotify Extended Streaming History/Streaming_History_Audio_2024.json", extended),
                ExportFile("Spotify Extended Streaming History/ReadMeFirst_ExtendedStreamingHistory.pdf", ""),
            ),
        )
        assertEquals(3, report.plays)
        assertEquals(1, report.rowsIgnored)
        assertEquals(1, report.filesSkipped.size)
        assertEquals(2, report.distinctTracks) // both "Blinding Lights" uploads share one key

        val events = store.since(0)
        assertEquals(EndReason.COMPLETED, events[0].endReason)
        assertEquals(StartReason.TAPPED, events[0].startReason)
        assertTrue(events[1].isQuickSkip)
        assertEquals(events[0].sessionId, events[1].sessionId)
        assertTrue(events[2].sessionId != events[1].sessionId)
        assertEquals(20, events[0].context?.hour)
        assertEquals(5, events[0].context?.weekday) // 1 March 2024 was a Friday
        // ts is the end of the play, so the start is ts - ms_played.
        assertEquals(1709323410000L - 210_000, events[0].startedAtMs)
    }

    @Test
    fun reimportReplacesInsteadOfDuplicating() = runTest {
        val store = InMemoryPlayEventStore()
        val importer = SpotifyHistoryImporter(store)
        val file = ExportFile("Streaming_History_Audio_2024.json", extended)
        importer.import(listOf(file))
        importer.import(listOf(file))
        assertEquals(3, store.count())
    }

    @Test
    fun importsAccountDataFormat() = runTest {
        val store = InMemoryPlayEventStore()
        val text = """[{"endTime":"2024-05-01 21:14","artistName":"Kino","trackName":"Группа крови","msPlayed":250000},
            {"endTime":"2024-05-01 21:20","artistName":"Unknown Artist","trackName":"Unknown Track","msPlayed":1000}]"""
        val report = SpotifyHistoryImporter(store).import(listOf(ExportFile("StreamingHistory_music_0.json", text)))
        assertEquals(1, report.plays)
        assertEquals(TrackKey.fromMeta("Kino", "Gruppa krovi"), store.since(0).single().trackKey)
    }

    @Test
    fun importsListenBrainzJsonLines() = runTest {
        val store = InMemoryPlayEventStore()
        val text = """
            {"listened_at":1700000000,"track_metadata":{"artist_name":"Radiohead","track_name":"Reckoner","release_name":"In Rainbows","additional_info":{"duration_ms":290000},"mbid_mapping":{"recording_mbid":"ABC-123"}}}
            {"listened_at":1700000400,"track_metadata":{"artist_name":"Radiohead","track_name":"Nude"}}
            {"listened_at":1700000800,"track_metadata":{}}
        """.trimIndent()
        val report = ListenBrainzHistoryImporter(store).import(listOf(ExportFile("listens.jsonl", text)))
        assertEquals(2, report.plays)
        assertEquals(1, report.rowsIgnored)
        val first = store.since(0).first()
        assertEquals(TrackKey.mbid("abc-123"), first.trackKey)
        assertTrue(first.isFinished)
        assertFalse(first.isQuickSkip)
    }
}
