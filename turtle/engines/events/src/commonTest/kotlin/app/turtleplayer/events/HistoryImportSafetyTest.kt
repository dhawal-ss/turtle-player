package app.turtleplayer.events

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.events.imports.ExportFile
import app.turtleplayer.events.imports.FileProblem
import app.turtleplayer.events.imports.ImportLimits
import app.turtleplayer.events.imports.ListenBrainzHistoryImporter
import app.turtleplayer.events.imports.PastPlay
import app.turtleplayer.events.imports.PlayerHistoryImporter
import app.turtleplayer.events.imports.SpotifyHistoryImporter
import app.turtleplayer.events.imports.SpotifyImportOptions
import app.turtleplayer.events.imports.commit
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Import is stage, preview, then an explicit commit: nothing but a good, complete import changes stored history. */
class HistoryImportSafetyTest {
    private fun extendedFile(
        name: String = "Spotify Extended Streaming History/Streaming_History_Audio_2024.json",
        text: String = ImportFixtures.extended,
    ) = ExportFile(name, text)

    /** Why each file of [files] failed when staged within [limits]. */
    private suspend fun problems(
        store: PlayEventStore,
        files: List<ExportFile>,
        limits: ImportLimits,
    ) = SpotifyHistoryImporter(store).stage(files, limits = limits).preview.filesFailed.map { it.problem }

    /** A store holding the Extended fixture (three plays), and its contents exactly as stored. */
    private suspend fun storeWithExtended(): Pair<InMemoryPlayEventStore, List<PlayEvent>> {
        val store = InMemoryPlayEventStore()
        assertTrue(SpotifyHistoryImporter(store).import(listOf(extendedFile())).committed)
        assertEquals(3, store.count())
        return store to store.since(0)
    }

    @Test
    fun wrongFileKeepsPriorImport() = runTest {
        val (store, before) = storeWithExtended()
        val importer = SpotifyHistoryImporter(store)
        val wrong = listOf(ExportFile("Streaming_History_Audio_2025.json", "not json"))

        val preview = importer.stage(wrong).preview
        assertFalse(preview.canCommit)
        assertEquals(listOf("Streaming_History_Audio_2025.json"), preview.filesFailed.map { it.name })
        assertEquals(FileProblem.UNREADABLE, preview.filesFailed.single().problem)

        val report = importer.import(wrong)
        assertFalse(report.committed)
        assertEquals(before, store.since(0))
    }

    @Test
    fun aBrokenFileBesideGoodOnesBlocksTheWholeImport() = runTest {
        val (store, before) = storeWithExtended()
        val files = listOf(
            extendedFile("Streaming_History_Audio_2024_0.json", ImportFixtures.extendedPrivate),
            extendedFile("Streaming_History_Audio_2024_1.json", "[{\"ts\":"),
        )
        val staged = SpotifyHistoryImporter(store).stage(files, SpotifyImportOptions(includePrivateSessions = true))
        assertEquals(1, staged.preview.accepted)
        assertFalse(staged.preview.canCommit)
        assertFailsWith<IllegalStateException> { store.commit(staged) }
        assertEquals(before, store.since(0))
    }

    @Test
    fun emptyArrayKeepsPriorImportAndDoesNotClaimAShrink() = runTest {
        val (store, before) = storeWithExtended()
        val importer = SpotifyHistoryImporter(store)
        val empty = listOf(ExportFile("Streaming_History_Audio_2025.json", "[]"))

        val preview = importer.stage(empty).preview
        assertEquals(0, preview.accepted)
        assertTrue(preview.filesFailed.isEmpty())
        assertFalse(preview.canCommit)
        assertFalse(preview.shrinksHistory)
        assertEquals(3L, preview.existing?.count)

        assertFalse(importer.import(empty).committed)
        assertEquals(before, store.since(0))
    }

    @Test
    fun unrecognisedFilesKeepPriorImport() = runTest {
        val (store, before) = storeWithExtended()
        val files = listOf(
            ExportFile("Spotify Account Data/Userdata.json", "{\"username\":\"x\"}"),
            ExportFile("ReadMeFirst_ExtendedStreamingHistory.pdf", ""),
        )
        val report = SpotifyHistoryImporter(store).import(files)
        assertEquals(listOf("Userdata.json", "ReadMeFirst_ExtendedStreamingHistory.pdf"), report.filesSkipped)
        assertFalse(report.committed)
        assertEquals(before, store.since(0))
    }

    @Test
    fun listenBrainzGarbageKeepsPriorImport() = runTest {
        val store = InMemoryPlayEventStore()
        val importer = ListenBrainzHistoryImporter(store)
        val first = importer.stage(listOf(ExportFile("listens.jsonl", ImportFixtures.listenBrainz)))
        assertEquals(2, first.preview.accepted)
        assertEquals(1, first.preview.duplicates) // the same listen twice
        store.commit(first)
        val before = store.since(0)

        val garbage = importer.stage(listOf(ExportFile("listens.jsonl", "hello\nworld"))).preview
        assertEquals(listOf("listens.jsonl"), garbage.filesFailed.map { it.name })
        assertEquals(2, garbage.rejected)
        assertFalse(importer.import(listOf(ExportFile("listens.jsonl", "hello\nworld"))).committed)
        assertFalse(importer.import(listOf(ExportFile("listens.json", "[{\"listened_at\":"))).committed)
        assertEquals(before, store.since(0))
    }

    @Test
    fun listenBrainzExportExtrasAreSkippedNotFailed() = runTest {
        val store = InMemoryPlayEventStore()
        val files = listOf(
            ExportFile("export/user.json", "{\n  \"user_id\": 1,\n  \"username\": \"someone\"\n}"),
            ExportFile("export/feedback.jsonl", "{\"created\":1700000000,\"recording_msid\":\"x\",\"score\":1}"),
            ExportFile("export/pinned_recording.jsonl", ""),
            ExportFile("export/listens/2023/11.jsonl", ImportFixtures.listenBrainz),
        )
        val preview = ListenBrainzHistoryImporter(store).stage(files).preview
        assertEquals(listOf("user.json", "feedback.jsonl", "pinned_recording.jsonl"), preview.filesSkipped)
        assertTrue(preview.filesFailed.isEmpty())
        assertEquals(0, preview.rejected)
        assertEquals(2, preview.accepted)
        assertTrue(preview.canCommit)
    }

    @Test
    fun pickingOnlyTheOldestFileOfAnExportIsAShrink() = runTest {
        val (store, before) = storeWithExtended()
        val importer = SpotifyHistoryImporter(store)
        // Only the first play of the stored history: it starts at the same time but ends earlier.
        val oldest = listOf(
            ExportFile(
                "Streaming_History_Audio_2024_0.json",
                """[{"ts":"2024-03-01T20:03:30Z","ms_played":210000,"master_metadata_track_name":"Blinding Lights",
                "master_metadata_album_artist_name":"The Weeknd","reason_start":"clickrow","reason_end":"trackdone"}]""",
            ),
        )
        val staged = importer.stage(oldest)
        assertEquals(before.first().startedAtMs, staged.preview.firstPlayMs)
        assertTrue(staged.preview.canCommit)
        assertTrue(staged.preview.shrinksHistory)
        assertFalse(importer.import(oldest).committed)
        assertEquals(before, store.since(0))
    }

    @Test
    fun eachOddRowLandsInItsOwnCount() = runTest {
        val odd = listOf(extendedFile(text = ImportFixtures.extendedOdd))
        val preview = SpotifyHistoryImporter(InMemoryPlayEventStore()).stage(odd).preview
        assertEquals(1, preview.rejected) // "ts":"yesterday" has no readable time: rejected, not ignored
        assertEquals(1, preview.nonMusic) // the audiobook chapter
        assertEquals(1, preview.unresolved) // a time but no song
        assertEquals(0, preview.accepted)
        assertEquals(0, preview.duplicates)
    }

    @Test
    fun overlappingExtendedFilesDeduplicate() = runTest {
        val store = InMemoryPlayEventStore()
        val staged = SpotifyHistoryImporter(store).stage(
            listOf(extendedFile("Streaming_History_Audio_2024_0.json"), extendedFile("MyData/endsong_0.json")),
        )
        assertEquals(3, staged.preview.accepted)
        assertEquals(4, staged.preview.duplicates) // every row of the second copy, the podcast too
        assertEquals(1, staged.preview.nonMusic)
        store.commit(staged)
        assertEquals(3, store.count())
    }

    @Test
    fun accountInsideExtendedRangeIsADuplicateAndExtendedFieldsWin() = runTest {
        val store = InMemoryPlayEventStore()
        val files = listOf(ExportFile("StreamingHistory_music_0.json", ImportFixtures.accountInsideExtended), extendedFile())
        val report = SpotifyHistoryImporter(store).import(files)
        assertTrue(report.committed)
        assertEquals(3, report.plays)
        val first = store.since(0).first()
        assertEquals("Blinding Lights", first.track.title)
        assertEquals(StartReason.TAPPED, first.startReason)
        assertEquals(1, SpotifyHistoryImporter(InMemoryPlayEventStore()).stage(files).preview.duplicates)
    }

    @Test
    fun accountOutsideExtendedRangeIsKeptOnce() = runTest {
        val store = InMemoryPlayEventStore()
        val account = ExportFile("StreamingHistory_music_0.json", ImportFixtures.account)
        val again = ExportFile("copy/StreamingHistory_music_0.json", ImportFixtures.account)
        val preview = SpotifyHistoryImporter(store).stage(listOf(extendedFile(), account, again)).preview
        assertEquals(4, preview.accepted) // three Extended plays and Kino
        assertEquals(1, preview.unresolved) // "Unknown Artist" / "Unknown Track"
        assertEquals(2, preview.duplicates) // the second copy of the Account file
    }

    @Test
    fun privateSessionExcludedByDefault() = runTest {
        val store = InMemoryPlayEventStore()
        val files = listOf(extendedFile(), extendedFile("Streaming_History_Audio_2024_1.json", ImportFixtures.extendedPrivate))
        val importer = SpotifyHistoryImporter(store)
        val preview = importer.stage(files).preview
        assertEquals(1, preview.privateSession)
        assertEquals(3, preview.accepted)
        importer.import(files)
        assertTrue(store.since(0).none { it.track.title == "Secret Song" })

        importer.import(files, SpotifyImportOptions(includePrivateSessions = true))
        assertEquals(1, store.since(0).count { it.track.title == "Secret Song" })
    }

    @Test
    fun spotifyIdentifiersAreNotStored() = runTest {
        val (store, _) = storeWithExtended()
        assertTrue(store.since(0).all { it.surfaceDetail == null })
    }

    @Test
    fun previewReportsExistingHistoryAndAShrinkNeedsAnExplicitCommit() = runTest {
        val (store, before) = storeWithExtended()
        val importer = SpotifyHistoryImporter(store)
        val accountOnly = listOf(ExportFile("StreamingHistory_music_0.json", ImportFixtures.account))

        val staged = importer.stage(accountOnly)
        assertEquals(3L, staged.preview.existing?.count)
        assertEquals(before.first().startedAtMs, staged.preview.existing?.firstMs)
        assertTrue(staged.preview.canCommit)
        assertTrue(staged.preview.shrinksHistory)

        // The one-step import never shrinks history on its own...
        assertFalse(importer.import(accountOnly).committed)
        assertEquals(before, store.since(0))
        // ...a listener who confirms the preview can.
        assertTrue(store.commit(staged).committed)
        assertEquals(listOf("Группа крови"), store.since(0).map { it.track.title })
    }

    @Test
    fun aStalePreviewNeverOverwritesANewerImport() = runTest {
        val store = InMemoryPlayEventStore()
        val importer = SpotifyHistoryImporter(store)
        val stale = importer.stage(listOf(ExportFile("StreamingHistory_music_0.json", ImportFixtures.account)))
        importer.import(listOf(extendedFile()))
        val newer = store.since(0)
        assertFailsWith<IllegalStateException> { store.commit(stale) }
        assertEquals(newer, store.since(0))
    }

    @Test
    fun replacingASourceKeepsOtherSources() = runTest {
        val store = InMemoryPlayEventStore()
        val local = PlayEvent(
            track = TrackMeta(TrackKey.youtube("mine"), "Mine", listOf("Me"), durationMs = 200_000),
            sessionId = "s",
            position = 0,
            surface = Surface.SEARCH,
            startReason = StartReason.TAPPED,
            endReason = EndReason.COMPLETED,
            startedAtMs = 1_709_320_000_000L,
            msPlayed = 200_000,
            durationMs = 200_000,
        )
        val id = store.append(local)
        SpotifyHistoryImporter(store).import(listOf(extendedFile()))
        SpotifyHistoryImporter(store).import(listOf(extendedFile()))
        assertEquals(4, store.count())
        assertEquals(local.copy(id = id), store.since(0).single { it.source == EventSource.LOCAL })

        store.deleteSource(EventSource.SPOTIFY_IMPORT)
        assertEquals(listOf(local.copy(id = id)), store.since(0))
        assertNull(store.sourceRange(EventSource.SPOTIFY_IMPORT))
        assertFailsWith<IllegalArgumentException> { store.replaceSource(EventSource.SPOTIFY_IMPORT, listOf(local)) }
    }

    @Test
    fun oversizeFilesFailWithoutBeingDecoded() = runTest {
        val (store, before) = storeWithExtended()
        var reads = 0
        val big = ExportFile("Streaming_History_Audio_2025.json", sizeChars = 10_000) { reads++; ImportFixtures.extended }
        val preview = SpotifyHistoryImporter(store).stage(listOf(big), limits = ImportLimits(maxFileChars = 1_000)).preview
        assertEquals(0, reads)
        assertEquals(FileProblem.TOO_LARGE, preview.filesFailed.single().problem)
        assertFalse(preview.canCommit)

        // A size hint that lies is caught on the text read.
        val lying = ExportFile("Streaming_History_Audio_2025.json", sizeChars = 10) { ImportFixtures.extended }
        val lyingPreview = SpotifyHistoryImporter(store).stage(listOf(lying), limits = ImportLimits(maxFileChars = 1_000)).preview
        assertEquals(FileProblem.TOO_LARGE, lyingPreview.filesFailed.single().problem)

        // The total over every file is bounded too, and so are the file and row counts.
        val total = ImportLimits(maxTotalChars = ImportFixtures.extended.length + 10L)
        val two = listOf(extendedFile("Streaming_History_Audio_2024_0.json"), extendedFile("Streaming_History_Audio_2024_1.json"))
        assertEquals(listOf(FileProblem.TOO_LARGE), problems(store, two, total))
        assertEquals(listOf(FileProblem.TOO_MANY_FILES), problems(store, two, ImportLimits(maxFiles = 1)))
        assertEquals(listOf(FileProblem.TOO_MANY_ROWS), problems(store, two, ImportLimits(maxRows = 5)))
        assertEquals(before, store.since(0))
    }

    @Test
    fun anUnreadableFileFailsTheImport() = runTest {
        val (store, before) = storeWithExtended()
        val broken = ExportFile("Streaming_History_Audio_2025.json", sizeChars = 10) { throw IllegalStateException("zip entry cut short") }
        val report = SpotifyHistoryImporter(store).import(listOf(extendedFile(), broken))
        assertFalse(report.committed)
        assertEquals(1, report.malformedRows)
        assertEquals(before, store.since(0))
    }

    @Test
    fun canceledImportWritesNothing() = runTest {
        val (store, before) = storeWithExtended()
        lateinit var job: Job
        var secondRead = false
        val files = listOf(
            ExportFile("Streaming_History_Audio_2025_0.json", sizeChars = 10) {
                job.cancel()
                ImportFixtures.extendedPrivate
            },
            ExportFile("Streaming_History_Audio_2025_1.json", sizeChars = 10) {
                secondRead = true
                ImportFixtures.extended
            },
        )
        job = launch { SpotifyHistoryImporter(store).import(files, SpotifyImportOptions(includePrivateSessions = true)) }
        job.join()
        assertTrue(job.isCancelled)
        assertFalse(secondRead)
        assertEquals(before, store.since(0))
    }

    @Test
    fun playerHistoryWithNothingToImportKeepsThePreviousImport() = runTest {
        val store = InMemoryPlayEventStore()
        val importer = PlayerHistoryImporter(store, LocalTimeResolver.fixedOffset())
        val t0 = 1_700_000_000_000L
        val track = TrackMeta(TrackKey.youtube("a"), "Song a", listOf("Artist a"), durationMs = 200_000)
        val plays = listOf(PastPlay(track, t0, 190_000), PastPlay(track, 0, null), PastPlay(track, t0 + 10_000_000, null))
        val first = importer.import(plays, before = t0 + 1_000_000)
        assertTrue(first.committed)
        assertEquals(2, first.rowsIgnored) // no time, and after Turtle's first play
        assertEquals(2, importer.stage(plays, before = t0 + 1_000_000).preview.excluded)
        val before = store.since(0)

        assertFalse(importer.import(emptyList(), before = t0 + 1_000_000).committed)
        assertEquals(before, store.since(0))
    }
}
