# W3 Learn Only From Chosen Music Implementation Plan

> **For agentic workers:** Implement task-by-task; steps use checkbox (- [ ]) syntax.

**Goal:** While upstream's "Pause listening history" is on, Turtle learns nothing from plays, and spoken word (podcast episodes and Turtle audiobooks) never shapes music taste.

**Architecture:** The listening log is the only input to session learning, exploration, Infinity's implicit tuning and play-based co-listening (`TasteEngine.observe`, `TurtleRuntime.kt:73-76`, `CoListeningSync.kt:65` and `TurtleHomeShelves.kt:54` all collect `PlayEventStore.newEvents`). So one predicate on `ListeningSessionTracker` stops all of them, and the same predicate gates the one learning path outside the log, radio pages in `CoListeningSync.observeRadio`. Spoken word is filtered in the bridge (`PlayerObserver`) by one rule in `TurtleSpoken`, and book chapter lists are saved so the rule still holds after Android restarts the process.

**Tech Stack:** Kotlin Multiplatform (`commonMain` engines), kotlinx.coroutines, Koin, upstream `Settings.shared`, kotlin.test with desktop (JVM) tests in `:turtle-bridge`.

**Spec:** /Users/dhawal/Desktop/Creations/turtle/turtle/docs/specs/2026-11-22-personal-home-design.md (section "W3 Learn only from chosen music")

## Global Constraints

- Engines (`turtle/engines/*`) stay pure `commonMain`: no `java.*`, no Android, no upstream imports. The pause rule enters the engine only as an injected `() -> Boolean`.
- Inject `Clock` (and `Random` where used), so engine behavior is deterministic under test. W3 adds no new time or randomness.
- Only `:turtle-bridge` reads upstream (`Settings.shared.pauseListenHistory`, `SongItem.isPodcast`).
- No upstream file changes and no hook lines. Upstream code is changed only through one-line hooks recorded in `turtle/HOOKS.md`, and Wave 1 plans none. Once committed, `bash turtle/scripts/check-hooks.sh origin/main` must print "No upstream files changed."
- No user-facing strings. Any future string goes in Turtle's own compose resources (`turtle/ui` or `turtle/bridge` `composeResources/values/strings.xml`), never as English literals in code. Never edit `values-*/strings.xml`.
- ktlint 1.8.0: new test files must show no violations of their own in `:turtle-bridge:ktlintDesktopTestSourceSetCheck`, and edited lines match the surrounding style. Never run module-wide `ktlintFormat`: the bridge already fails `ktlintCheck` with about 1,000 violations, and formatting would rewrite files W2, W9 and W1 also edit.
- Engine tests are written against public APIs. The recommendation offline replay (`OfflineReplayTest`) must not regress by more than 2%. W3 changes no ranking code, so its metrics should not move at all.
- No tests for UI composables or upstream code. Never set `Settings.shared.*` in a desktop test, because it writes the developer's real desktop settings.
- Commits (after the owner approves, not in this plan) use the scopes `feat(turtle-events)`, `feat(turtle-bridge)` or `feat(turtle)`.
- Listening data stays on the device. W3 sends nothing anywhere.

## Review Focus

These are the inputs most likely to hurt a listener that no existing test covers. Each now has a test in the owning task.

1. **Pause toggled mid-song.** A song that started before the pause and ends during it, or starts during the pause and ends after it, must not be stored. Test: Task 1, `pauseToggledMidPlayDropsThatPlay`.
2. **App killed around a pause:** a checkpoint saved before the pause must not be recovered once its play was dropped, or when the app restarts with history paused. Tests: Task 1, `droppedPlayIsNotRecoveredAfterARestart` and `playSavedBeforePausingIsDiscardedIfTheAppRestartsPaused`.
3. **Radio pages fetched while paused.** They are seeded by the song playing now, and must not grow the co-listening graph. Test: Task 2, `radioPagesTeachNothingWhileListeningHistoryIsPaused`.
4. **A book chapter after a process restart or a background-only start:** only `open()` registers chapters today, and Shell, which calls `load()`, may never run when Android restarts the service, so auto-advance to the next chapter is learned as music. Tests: Task 3, `savedBooksRecognizeEveryChapterAfterARestart` and `bookLinesSavedBeforeChapterListsStillLoad`; Task 4, `aChapterRestoredAtLaunchWaitsForSavedBooks`.
5. **An episode identified only by its video type** (`MUSIC_VIDEO_TYPE_PODCAST_EPISODE` with `podcastId == null`). Today `PlayerObserver.kt:146` checks only `podcastId`, so these episodes are learned as music. Test: Task 4, `spokenWordNeverReachesTheListeningLog`.

## Decisions this plan makes (within the spec)

- **When a play counts as paused.** A play is stored only when recording was on both when it started and when it ended. Turning the pause on or off mid-song drops that song. Upstream likewise skips the history row when paused at the end; Turtle is stricter, so no paused listening leaks in.
- **What still runs while paused.**
  - The observer keeps tracking the current song while paused. Only storage is suppressed.
  - Explicit actions keep their existing routes and still apply, as the spec requires:
    - likes go through `LibrarySync.saved` → `TasteEngine.setSavedTracks`, and through `TurtleRuntime.watchLikes` → `infinity.feedback`;
    - "less like this" goes through `TurtleRuntime.lessLikeThis`;
    - mutes go through `TasteEngine.updateControls`.
  - A `PlaybackSignal.Feedback` on a paused play is dropped with that play, which is expected.
- **"No Infinity tuning updates"** means Infinity's implicit, play-driven tuning (`InfinityMix.onPlayEvent`), which stops because no events arrive. Explicit like and pass tuning (`InfinityMix.feedback`) still applies.
- **Co-listening from radio pages stops while paused**, from both callers: `TurtleRuntime.kt:124` and `YouTubeSeams.kt:57`. Their seeds are the listener's own songs. Building Up Next and shelves still works, from the existing profile and graph.
- **One spoken-word rule**, `TurtleSpoken.isSpokenWord`:
  - `PlayerObserver` and `ShellSpoken` both use it;
  - W8 extends it for local books and podcasts.
- **Already-stored spoken-word plays are not purged.** The spec doesn't ask for it.
- **Upstream's one-time history import** (`MetrolistHistoryImport`) also uses `isPodcast`. It already ran on existing installs, so this only matters for new ones.

## Interfaces other workstreams may use

```kotlin
// turtle/engines/events — ListeningSessionTracker.kt
class ListeningSessionTracker(
    store: PlayEventStore,
    clock: Clock = Clock.System,
    time: LocalTimeResolver = LocalTimeResolver.fixedOffset(),
    sessionGapMs: Long = 30 * MINUTE_MS,
    checkpoint: OpenPlayCheckpoint? = null,
    /** False while the listener paused listening history: plays are followed but never stored. */
    recording: () -> Boolean = { true },
)

// turtle/bridge — TurtleModule.kt
/** Upstream's "Pause listening history" (Privacy settings). Read on each use, never cached. */
internal fun listenHistoryPaused(): Boolean

// turtle/bridge — CoListeningSync.kt
class CoListeningSync(
    graph: CoListeningGraph, store: CoListeningStore, db: SqlitePlayEventStore,
    events: PlayEventStore, cache: SongCache,
    /** False while listening history is paused: radio pages teach the graph nothing. */
    learning: () -> Boolean = { true },
)

// turtle/bridge — TurtleSpoken.kt (object TurtleSpoken)
/** Never music taste: podcast episodes (show id or episode video type) and chapters of books Turtle opened. */
fun isSpokenWord(song: SongItem, isBookChapter: (String) -> Boolean = { bookOf(it) != null }): Boolean
internal fun encodeLine(p: BookProgress, chapterIds: List<String>): String
internal fun decodeLine(line: String): Pair<BookProgress, List<String>>?
internal fun restore(raw: String?)

// turtle/bridge — PlayerObserver.kt
class PlayerObserver(
    tracker: ListeningSessionTracker,
    controller: () -> PlaybackController? = { PlaybackHints.controller },
    isBookChapter: (String) -> Boolean = { TurtleSpoken.bookOf(it) != null },
    /** Awaited once before the first song is classified, so a restored chapter is known as a book. */
    spokenReady: suspend () -> Unit = { TurtleSpoken.load() },
)
```

Notes for later workstreams:

- **W9.**
  - `recording` gates only plays built from `PlaybackSignal`s. W9's new non-play "added to playlist" feedback event is an explicit action and must not consult `recording` or `listenHistoryPaused()`.
  - W3 edits `PlayerObserver`'s KDoc and constructor (lines 38-51), the song collector in `attach` (lines 75-79) and `onSongChanged` (lines 144-152).
  - W3 does not touch `start()` (lines 178-196), where W9 replaces the hardcoded surface at line 191.
  - W9 should append any new constructor parameters after `spokenReady`.
- **W8** adds local books and podcasts inside `TurtleSpoken.isSpokenWord`. No other call site needs changing.
- **W1** also calls `TurtleSpoken.isSpokenWord` (after `TurtleSpoken.load()`) to keep spoken-word plays stored before W3 out of Jump back in, since W3 does not purge them.

---

### Task 1: The listening log stores nothing while history is paused (engine)

**Files:**
- Modify: `turtle/engines/events/src/commonMain/kotlin/app/turtleplayer/events/ListeningSessionTracker.kt`:
  - class KDoc (lines 70-80);
  - constructor (lines 81-87);
  - `Open` (lines 88-104);
  - `onSignal`, `checkpoint` and `recover` (lines 115-143);
  - the `TrackStarted` branch of `handle` (lines 151-167);
  - `close` (lines 232-238).
- Test: `turtle/engines/events/src/commonTest/kotlin/app/turtleplayer/events/EventsTest.kt`. Add to `class ListeningSessionTrackerTest`, before its closing brace at line 198, reusing `FakeClock`, `track()` and `MemoryCheckpoint`.

**Interfaces:**
- Consumes: nothing new.
- Produces: `ListeningSessionTracker(..., recording: () -> Boolean = { true })`. Existing callers compile unchanged.

- [ ] **Step 1: Write the failing tests** (in `ListeningSessionTrackerTest`)

```kotlin
@Test
fun pausedHistoryRecordsNoPlays() = runTest {
    val clock = FakeClock()
    val store = InMemoryPlayEventStore()
    val saved = MemoryCheckpoint()
    val tracker = ListeningSessionTracker(store, clock, checkpoint = saved, recording = { false })

    tracker.onSignal(PlaybackSignal.TrackStarted(track("a"), StartReason.TAPPED, Surface.SEARCH))
    clock.advance(60_000)
    tracker.checkpoint()
    assertEquals(null, saved.take())
    clock.advance(140_000)
    tracker.onSignal(PlaybackSignal.TrackStarted(track("b"), StartReason.AUTOPLAY, Surface.AUTOPLAY))
    clock.advance(10_000)
    tracker.flush()

    assertEquals(0L, store.count())
}

@Test
fun pauseToggledMidPlayDropsThatPlay() = runTest {
    val clock = FakeClock()
    val store = InMemoryPlayEventStore()
    var paused = false
    val tracker = ListeningSessionTracker(store, clock, recording = { !paused })

    tracker.onSignal(PlaybackSignal.TrackStarted(track("a"), StartReason.TAPPED, Surface.QUEUE))
    clock.advance(30_000)
    paused = true // turned on while "a" plays
    tracker.onSignal(PlaybackSignal.TrackStarted(track("b"), StartReason.USER_NEXT, Surface.QUEUE))
    clock.advance(30_000)
    paused = false // turned off while "b" plays
    tracker.onSignal(PlaybackSignal.TrackStarted(track("c"), StartReason.TAPPED, Surface.QUEUE))
    clock.advance(200_000)
    tracker.flush()

    assertEquals(listOf("yt:c"), store.since(0).map { it.trackKey.value })
}

@Test
fun droppedPlayIsNotRecoveredAfterARestart() = runTest {
    val clock = FakeClock()
    val store = InMemoryPlayEventStore()
    val saved = MemoryCheckpoint()
    var paused = false
    val tracker = ListeningSessionTracker(store, clock, checkpoint = saved, recording = { !paused })

    tracker.onSignal(PlaybackSignal.TrackStarted(track("a"), StartReason.TAPPED, Surface.QUEUE))
    clock.advance(60_000)
    tracker.checkpoint() // "a" saved while recording
    paused = true
    tracker.onSignal(PlaybackSignal.TrackStarted(track("b"), StartReason.USER_NEXT, Surface.QUEUE))
    // Killed before the next periodic checkpoint, then reopened with history on again.
    paused = false

    assertEquals(null, ListeningSessionTracker(store, clock, checkpoint = saved, recording = { !paused }).recover())
    assertEquals(0L, store.count())
}

@Test
fun playSavedBeforePausingIsDiscardedIfTheAppRestartsPaused() = runTest {
    val clock = FakeClock()
    val store = InMemoryPlayEventStore()
    val saved = MemoryCheckpoint()
    val before = ListeningSessionTracker(store, clock, checkpoint = saved)

    before.onSignal(PlaybackSignal.TrackStarted(track("a"), StartReason.TAPPED, Surface.QUEUE))
    clock.advance(60_000)
    before.checkpoint()
    // Killed; the listener paused history before the app came back.

    val after = ListeningSessionTracker(store, clock, checkpoint = saved, recording = { false })
    assertEquals(null, after.recover())
    assertEquals(0L, store.count())
    assertEquals(null, saved.take()) // discarded, not kept for a later launch
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew -p turtle/engines :turtle-events:jvmTest`

Expected: compilation fails in `EventsTest.kt` with "No parameter with name 'recording' found".

- [ ] **Step 3: Implement**
  - Constructor: add `private val recording: () -> Boolean = { true }` as the last parameter. Extend the class KDoc with one sentence: plays are followed but stored only while `recording` is true, both at their start and at their end.
  - `Open`: add `val recordable: Boolean`. In the `TrackStarted` branch, set it to `recording()`.
  - `close`: after the `everPlayed` check and the `lastTrackKey` update, return null when `!o.recordable || !recording()`.
  - `onSignal`: refresh the checkpoint (call `checkpoint()`) whenever a play closed, whether it was stored or dropped. Today it refreshes only when an event was returned, which would leave a dropped play's checkpoint to be recovered (pinned by `droppedPlayIsNotRecoveredAfterARestart`). How `handle` reports "closed but dropped" is the implementer's choice, for example a private result holder.
  - `checkpoint()`: save null unless `o.recordable && recording()`. The existing `MIN_CHECKPOINT_MS` check stays.
  - `recover()`: take the saved play first, so it is always cleared. If `!recording()`, return null without appending.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew -p turtle/engines :turtle-events:jvmTest`

Expected: BUILD SUCCESSFUL. All `ListeningSessionTrackerTest`, `HistoryImportTest` and `PlayerHistoryImportTest` tests pass, including the 4 new ones.

- [ ] **Step 5: Stage for commit** (no commit; the owner approves later)
  - `turtle/engines/events/src/commonMain/kotlin/app/turtleplayer/events/ListeningSessionTracker.kt`
  - `turtle/engines/events/src/commonTest/kotlin/app/turtleplayer/events/EventsTest.kt`

---

### Task 2: Wire the pause into the bridge; radio pages teach co-listening nothing while paused

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/CoListeningSync.kt`:
  - KDoc (lines 18-23);
  - constructor (lines 24-30);
  - `observeRadio` (lines 33-36).
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt`:
  - imports (top of file): add `com.metrolist.shared.settings.Settings`;
  - add `listenHistoryPaused()` after `TurtleScope` (line 69), before the `turtleModule` KDoc (line 71);
  - the tracker binding (line 86);
  - the `CoListeningSync` binding (line 102).
- Test (create): `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/CoListeningSyncTest.kt`

**Interfaces:**
- Consumes: `ListeningSessionTracker(..., recording: () -> Boolean)` from Task 1.
- Produces:
  - `internal fun listenHistoryPaused(): Boolean = Settings.shared.pauseListenHistory`;
  - `CoListeningSync(..., learning: () -> Boolean = { true })`.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.turtleplayer.bridge

import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.taste.CoListeningGraph
import app.turtleplayer.taste.CoListeningStore
import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CoListeningSyncTest {
    private fun song(id: String, artist: String) =
        SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)

    @Test
    fun radioPagesTeachNothingWhileListeningHistoryIsPaused() = runTest {
        val file = File.createTempFile("turtle", ".db").apply { delete(); deleteOnExit() }
        var paused = true
        val graph = CoListeningGraph()
        val sync = CoListeningSync(
            graph, CoListeningStore(graph), SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath),
            InMemoryPlayEventStore(), SongCache(), learning = { !paused },
        )
        val seed = song("s", "Artist A").toTrackMeta()
        val page = listOf(song("r1", "Artist B"), song("r2", "Artist C"))
        val before = graph.version()

        sync.observeRadio(seed, page)
        assertEquals(before, graph.version())

        paused = false
        sync.observeRadio(seed, page)
        assertTrue(graph.version() > before)
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails**

Run: `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.CoListeningSyncTest'`

Expected: compilation fails with "No parameter with name 'learning' found".

- [ ] **Step 3: Implement**
  - `CoListeningSync`:
    - add `private val learning: () -> Boolean = { true }` as the last constructor parameter;
    - `observeRadio` returns at once when `!learning()`;
    - add one KDoc sentence: radio pages are seeded by the listener's own songs, so they teach nothing while listening history is paused.
    - Play-driven learning (line 65) needs no change, because Task 1 stops the events.
  - `TurtleModule.kt`:
    - `internal fun listenHistoryPaused(): Boolean = Settings.shared.pauseListenHistory`, with a one-line KDoc ("Upstream's Pause listening history; read on each use so toggling it applies at once").
    - Line 86 becomes `single { ListeningSessionTracker(get(), Clock.System, deviceTime, checkpoint = get<SqlitePlayEventStore>(), recording = { !listenHistoryPaused() }) }`.
    - Line 102 becomes `single { CoListeningSync(get(), get(), get(), get(), get(), learning = { !listenHistoryPaused() }) }`.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :turtle-bridge:desktopTest`

Expected: BUILD SUCCESSFUL, with `CoListeningSyncTest` and all existing bridge tests passing.

Caveat: `TurtleRuntimeTest` boots the real Koin module, so it reads the developer's desktop "Pause listening history". If its "a played song lands in the event log" step times out, check that setting before debugging the code.

- [ ] **Step 5: Check formatting of the new file**

Run: `./gradlew :turtle-bridge:ktlintDesktopTestSourceSetCheck`

Expected: the report under `turtle/bridge/build/reports/ktlint/ktlintDesktopTestSourceSetCheck/` lists nothing for `CoListeningSyncTest.kt`. Pre-existing violations in other files are not ours to fix here.

- [ ] **Step 6: Stage for commit**
  - `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/CoListeningSync.kt`
  - `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt`
  - `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/CoListeningSyncTest.kt`

---

### Task 3: One spoken-word rule, and book chapters that survive a restart

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleSpoken.kt`:
  - the `chapterBooks` and `bookChapters` maps (lines 140-142);
  - `load` and `bookOf` (lines 262-278);
  - `open` (lines 287-298);
  - `persist`, `encode` and `decode` (lines 337-360), renamed `encodeLine` and `decodeLine`.
- Modify: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellSpoken.kt`:
  - `isSpokenWord` (line 109);
  - the `isPodcast` import (line 62), dropped if it becomes unused.
- Test (create): `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/TurtleSpokenTest.kt`

**Interfaces:**
- Consumes: upstream `SongItem.isPodcast` (`shared/.../model/Models.kt:467`, which covers `podcastId` and the `PODCAST_EPISODE` video type). It is already imported in `TurtleSpoken.kt:13`.
- Produces:
  - `fun isSpokenWord(song: SongItem, isBookChapter: (String) -> Boolean = { bookOf(it) != null }): Boolean`;
  - `internal fun encodeLine(p: BookProgress, chapterIds: List<String>): String`;
  - `internal fun decodeLine(line: String): Pair<BookProgress, List<String>>?`;
  - `internal fun restore(raw: String?)`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package app.turtleplayer.bridge

import com.metrolist.shared.model.SongItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TurtleSpokenTest {
    @Test
    fun savedBooksRecognizeEveryChapterAfterARestart() {
        val book = BookProgress("PL-w3-test", "Moby Dick", "Herman Melville", null, "PL-w3-test", "ch2-w3", 1, 3, 61_000L, 1_800_000L, 1_700_000_000_000L)
        val line = TurtleSpoken.encodeLine(book, listOf("ch1-w3", "ch2-w3", "ch3-w3"))

        TurtleSpoken.restore(line) // what load() does with the saved preference after a process restart

        assertEquals("PL-w3-test", TurtleSpoken.bookOf("ch1-w3"))
        assertEquals("PL-w3-test", TurtleSpoken.bookOf("ch3-w3"))
        assertTrue(TurtleSpoken.isSpokenWord(SongItem(id = "ch3-w3", title = "Chapter 3", thumbnail = null, artists = emptyList(), album = null)))
    }

    @Test
    fun bookLinesSavedBeforeChapterListsStillLoad() {
        val legacy = listOf("PL-old", "Moby Dick", "Herman Melville", "", "PL-old", "ch2-old", "1", "3", "61000", "1800000", "1700000000000")
            .joinToString("\u001F")

        val (progress, chapters) = assertNotNull(TurtleSpoken.decodeLine(legacy))

        assertEquals("ch2-old", progress.chapterId)
        assertEquals(1, progress.chapterIndex)
        assertEquals(61_000L, progress.positionMs)
        assertEquals(emptyList(), chapters)
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.TurtleSpokenTest'`

Expected: compilation fails with "Unresolved reference 'encodeLine'" (and `restore`, `decodeLine`, `isSpokenWord`).

- [ ] **Step 3: Implement**
  - **Line format.**
    - `encodeLine` writes today's 11 fields, then a 12th: the chapter video ids joined with `,`. Ids pass through `clean()`. Video ids never contain `,`.
    - `decodeLine` accepts 11 or more fields, so lines saved before W3 still load with an empty chapter list. The rest of its validation is unchanged.
  - **Persisting.** `persist()` encodes each book with `bookChapters[p.bookId].orEmpty()`.
  - **`restore(raw)`.** Move the body of `load()`, except the lock, the `uiPref` read and `loaded = true`, into `restore`. For each decoded line:
    - merge it into `booksState` (the existing rule still applies: entries registered before the load win);
    - map the current `chapterId` → `bookId` as today;
    - when the chapter list is non-empty, also set `bookChapters[bookId]` (if absent) and map every chapter id → `bookId` (if absent).

    `load()` becomes: lock, `loaded` check, read the pref, `restore(raw)`, set `loaded = true`.
  - **`open()`.** When the chapter ids differ from the stored `bookChapters[book.id]`, call `persist()` even if saved progress exists. That way a kill right after opening a book does not lose its chapter list.
  - **Thread safety.** `bookOf` is now also read from the player observer's thread (Task 4). Make `chapterBooks` and `bookChapters` `@kotlin.concurrent.Volatile` (import `kotlin.concurrent.Volatile`, as `PlayerObserver.kt:23` does) read-only maps, replaced copy-on-write on each write.
  - **The rule.** Add `isSpokenWord` with this KDoc: "Spoken word is never music taste: podcast episodes (show id or episode video type) and chapters of books Turtle opened; local books and podcasts join in W8." Body: `song.isPodcast || isBookChapter(song.id)`.
  - **`ShellSpoken.kt:109`.** It becomes `fun isSpokenWord(song: SongItem?): Boolean = song != null && TurtleBridge.spoken.isSpokenWord(song)`.

- [ ] **Step 4: Run the tests and confirm they pass; compile Shell**

Run: `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.TurtleSpokenTest'`

Expected: BUILD SUCCESSFUL, 2 tests passed.

Run: `./gradlew :turtle-ui:compileKotlinDesktop`

Expected: BUILD SUCCESSFUL. This is a compile-only check for the `ShellSpoken` change; there are no UI tests.

- [ ] **Step 5: Check formatting of the new file**

Run: `./gradlew :turtle-bridge:ktlintDesktopTestSourceSetCheck`

Expected: no entries for `TurtleSpokenTest.kt` in the report.

- [ ] **Step 6: Stage for commit**
  - `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleSpoken.kt`
  - `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellSpoken.kt`
  - `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/TurtleSpokenTest.kt`

---

### Task 4: The player observer keeps spoken word out of the listening log

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/PlayerObserver.kt`:
  - KDoc and constructor (lines 38-51);
  - the song-change collector in `attach` (lines 75-79);
  - `onSongChanged` (lines 144-152).
  - Do **not** touch `start()` (lines 178-196); W9 edits it.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/MetrolistHistoryImport.kt`, line 33 (`takeIf { it.podcastId == null }`).
- Test (create): `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/PlayerObserverTest.kt`

**Interfaces:**
- Consumes:
  - `TurtleSpoken.isSpokenWord`, `TurtleSpoken.bookOf` and `TurtleSpoken.load()` (Task 3);
  - `ListeningSessionTracker` (Task 1, unchanged API for this task).
- Produces: `PlayerObserver(tracker, controller, isBookChapter: (String) -> Boolean = { TurtleSpoken.bookOf(it) != null }, spokenReady: suspend () -> Unit = { TurtleSpoken.load() })`. The Koin binding `PlayerObserver(get())` at `TurtleModule.kt:87` stays as is.

- [ ] **Step 1: Write the failing tests**

```kotlin
package app.turtleplayer.bridge

import app.turtleplayer.events.EndReason
import app.turtleplayer.events.InMemoryPlayEventStore
import app.turtleplayer.events.ListeningSessionTracker
import com.metrolist.shared.model.ArtistTiny
import com.metrolist.shared.model.SongItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlayerObserverTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private fun song(id: String, podcastId: String? = null, videoType: String? = null) = SongItem(
        id = id, title = "Title $id", thumbnail = null, artists = listOf(ArtistTiny(null, "Artist $id")), album = null,
        duration = 200, podcastId = podcastId, musicVideoType = videoType,
    )

    /** Waits until the observer has taken in the latest song (null: nothing it learns from is playing). */
    private suspend fun PlayerObserver.settle(id: String?) = withTimeout(5_000) { while (currentSong()?.id != id) delay(10) }

    @Test
    fun spokenWordNeverReachesTheListeningLog() = runBlocking {
        val store = InMemoryPlayEventStore()
        val observer = PlayerObserver(ListeningSessionTracker(store), controller = { null }, isBookChapter = { it == "b1" }, spokenReady = {})
        val current = MutableStateFlow<SongItem?>(null)
        observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())

        current.value = song("m1"); observer.settle("m1")
        current.value = song("p1", podcastId = "MPSPshow"); observer.settle(null)
        current.value = song("m2"); observer.settle("m2")
        current.value = song("p2", videoType = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"); observer.settle(null) // no show id
        current.value = song("m3"); observer.settle("m3")
        current.value = song("b1"); observer.settle(null) // a chapter of a book Turtle opened

        val events = store.since(0)
        assertEquals(listOf("yt:m1", "yt:m2", "yt:m3"), events.map { it.trackKey.value })
        assertTrue(events.all { it.endReason == EndReason.STOPPED })
    }

    @Test
    fun aChapterRestoredAtLaunchWaitsForSavedBooks() = runBlocking {
        val store = InMemoryPlayEventStore()
        val booksLoaded = CompletableDeferred<Unit>()
        var known = emptySet<String>()
        val observer = PlayerObserver(
            ListeningSessionTracker(store), controller = { null },
            isBookChapter = { it in known }, spokenReady = { booksLoaded.await() },
        )
        // Upstream restored a book chapter before Turtle's saved books were read.
        val current = MutableStateFlow<SongItem?>(song("b1"))
        observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
        delay(200)
        known = setOf("b1")
        booksLoaded.complete(Unit)

        current.value = song("m1"); observer.settle("m1")
        current.value = null; observer.settle(null)

        assertEquals(listOf("yt:m1"), store.since(0).map { it.trackKey.value })
    }
}
```

- [ ] **Step 2: Run the tests and confirm they fail**

Run: `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.PlayerObserverTest'`

Expected: compilation fails with "No parameter with name 'isBookChapter' found". Once the constructor exists but before the rule changes, `spokenWordNeverReachesTheListeningLog` fails with `TimeoutCancellationException` at the `p2` step, and `aChapterRestoredAtLaunchWaitsForSavedBooks` fails with `[yt:b1, yt:m1]`.

- [ ] **Step 3: Implement**
  - **Constructor.** Add `isBookChapter` and `spokenReady`, with the defaults above, after `controller`.
  - **KDoc.** Replace "Podcast episodes are not music taste and are not recorded." (line 46) with: "Spoken word (TurtleSpoken.isSpokenWord) is not music taste and is not recorded; whether history is paused is the tracker's concern."
  - **`attach`.** In the song-change coroutine (line 75), run `guarded("saved books") { spokenReady() }` once before collecting, outside the mutex. A failure must not stop the collector; `guarded` already ensures that.
  - **`onSongChanged`** (line 146): `val music = song?.takeUnless { TurtleSpoken.isSpokenWord(it, isBookChapter) }`. The STOPPED close of the previous music play is unchanged.
  - **`MetrolistHistoryImport.kt:33`.** `row.song.toSongItem().takeUnless { it.isPodcast }`, importing `com.metrolist.shared.model.isPodcast`. Upstream maps stored episodes to the `PODCAST_EPISODE` video type (`shared/.../db/entities/SongItemMapping.kt:42-48`), so this also catches episodes without an album id. No test: the import needs upstream's database and runs once per install.

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `./gradlew :turtle-bridge:desktopTest`

Expected: BUILD SUCCESSFUL. `PlayerObserverTest` (2), `TurtleSpokenTest` (2), `CoListeningSyncTest` (1) and every existing bridge test pass, including `TurtleRuntimeTest`, whose observer uses the real defaults.

- [ ] **Step 5: Check formatting of the new file**

Run: `./gradlew :turtle-bridge:ktlintDesktopTestSourceSetCheck`

Expected: no entries for `PlayerObserverTest.kt` in the report.

- [ ] **Step 6: Stage for commit**
  - `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/PlayerObserver.kt`
  - `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/MetrolistHistoryImport.kt`
  - `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/PlayerObserverTest.kt`

---

### Task 5: Whole-workstream verification

**Files:** none changed.

**Interfaces:** none.

- [ ] **Step 1: Engines, including the offline replay**

Run: `./gradlew -p turtle/engines check`

Expected: BUILD SUCCESSFUL. `OfflineReplayTest` passes. W3 touches no taste code, so its metrics must equal the pre-W3 run, well inside the 2% bar. If they moved, something outside this plan changed.

- [ ] **Step 2: Bridge tests**

Run: `./gradlew :turtle-bridge:desktopTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: App builds** (compile only)

Run: `./gradlew :desktopApp:compileKotlinJvm :androidApp:compileDebugKotlin`

Expected: BUILD SUCCESSFUL. The task names were checked with `--dry-run` on 22 Nov 2026.

- [ ] **Step 4: Hook ledger and diff review**

Run: `git status --porcelain | grep -v ' turtle/'` and `git diff --check`

Expected: the first prints nothing, because no path outside `turtle/` changed and so the hook ledger needs no row. The second passes. `git status` shows only the 11 code and test files listed below, plus `turtle/docs/` (this plan and the spec).

`turtle/scripts/check-hooks.sh` compares committed history only (`git diff merge-base..HEAD`). There is no local `main`, and `origin/main` (82d5d38) is behind this branch, so the script cannot check uncommitted W3 work. Run `bash turtle/scripts/check-hooks.sh origin/main` after the owner-approved commit, where it must print "No upstream files changed." for the W3 commit.

- [ ] **Step 5: Device check** (product owner's OnePlus, side-by-side debug build per `phone-test-setup`)
  - With Settings > Privacy > "Pause listening history" on, play three songs to the end. Turtle's play-event count must not grow. Read it from `databases/turtle.db` with `run-as`, copying the `-wal` file too.
  - Turn the pause off and play one song to the end: the count grows by one.
  - Play a podcast episode, and a chapter of a Turtle audiobook. Swipe the app away during the chapter so OnePlus kills it, let it auto-resume into the next chapter, and finish it. The count must not grow. Episode and book resume still work.
  - Like a song while paused: it still shows as liked, and Infinity still tunes to it.

- [ ] **Step 6: Stage for commit**

Nothing new beyond Tasks 1-4. Hand these 11 code and test files, plus the plan, to the owner for commit approval:

1. `turtle/engines/events/src/commonMain/kotlin/app/turtleplayer/events/ListeningSessionTracker.kt`
2. `turtle/engines/events/src/commonTest/kotlin/app/turtleplayer/events/EventsTest.kt`
3. `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/CoListeningSync.kt`
4. `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt`
5. `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleSpoken.kt`
6. `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/PlayerObserver.kt`
7. `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/MetrolistHistoryImport.kt`
8. `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellSpoken.kt`
9. `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/CoListeningSyncTest.kt`
10. `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/TurtleSpokenTest.kt`
11. `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/PlayerObserverTest.kt`
Plus the plan itself: `turtle/docs/plans/2026-11-22-w3-learn-only-chosen-music.md`

Suggested commit after approval: `feat(turtle): learn only from chosen music`.

## Self-review against the spec

- **"Turtle records no play events while paused":** Task 1 (tracker), wired in Task 2.
- **"No co-listening or session learning":**
  - session learning, play-based co-listening and Infinity's implicit tuning stop because no events arrive (Task 1);
  - radio-page co-listening stops through the Task 2 gate.
- **"No exploration or Infinity tuning updates":** both are driven only by `newEvents` (`TasteEngine.onPlayEvent`, `InfinityMix.onPlayEvent`), so Task 1 covers them.
- **"Up Next ranking may still use the existing profile":** `rankRadio` and `rankExternal` are untouched.
- **"Explicit actions still apply":** their routes are untouched (see Decisions).
- **Spoken word excluded** (podcast id or episode video type; Turtle audiobooks): Tasks 3-4. Local files follow in W8, through `TurtleSpoken.isSpokenWord`.
- **"Progress and resume keep working":** upstream episode resume is untouched. Book resume goes through `ShellSpoken`, which is unchanged apart from delegating `isSpokenWord`, and now also survives a restart for later chapters (Task 3).
- **Spec testing line, "engine unit tests for … the paused-history rule":** Task 1.
- **Spec device line, "with history paused, the event log doesn't grow":** Task 5, Step 5.
- **Hooks and strings:** none needed.
