# W1 Personal Home Implementation Plan

> **For agentic workers:** Implement task-by-task; steps use checkbox (- [ ]) syntax.

**Goal:** Shell Home leads with the listener's own music, in this order: grid, Made for you, Infinity, Jump back in, More like X, then at most 3 YouTube rows that match their taste. Random chip feeds stop loading on Home, and a setting brings every YouTube row back.

**Architecture:** The taste engine gains pure, unit-tested APIs: row scoring and selection, an item-level taste match, and Jump back in built from the on-device event log. The bridge picks the candidate upstream `HomeShelf`s and assembles the Home layout as pure, desktop-tested functions; it also scores candidates off the UI thread and publishes the pick and Jump back in as Compose state. `ShellHome` only renders that layout, and a Turtle setting (Matched by default, All, Off) chooses how many YouTube rows appear.

**Tech Stack:** Kotlin Multiplatform (engines `commonMain`, JVM tests), Compose Multiplatform (`:turtle-ui`), Koin, androidx.sqlite / Room DAO reads (bridge only), kotlinx.coroutines.

**Spec:** /Users/dhawal/Desktop/Creations/turtle/turtle/docs/specs/2026-11-22-personal-home-design.md

Line numbers are at HEAD `61af6acc`. W3, W2 and W9 land first in the same checkout, so ranges will drift. Each range names its symbol anchor; go by the anchor.

## Global Constraints

- Engines (`turtle/engines/*`) are pure `commonMain`: no `java.*`, no Android, no upstream (`com.metrolist.*`) imports. Only `:turtle-bridge` touches upstream.
- Engine behavior stays deterministic: tests inject `Clock` (`MutableClock`) and `Random`. No new wall-clock or `Random.Default` reads.
- Upstream files change only through one-line hooks recorded in `turtle/HOOKS.md`. Wave 1 plans **zero** hook lines: this plan edits nothing outside `turtle/`, and `turtle/HOOKS.md` stays unchanged.
- No user-facing strings in engines; engines return typed values. UI text lives in Turtle's own resources (`turtle/ui/src/commonMain/composeResources/values/strings_*.xml`), base English only, accessed with `stringResource(Res.string.…)`. No English literals in Kotlin. Never create or edit `values-*/strings.xml`.
- No hardcoded language or country. Nothing in W1 reads a locale; row titles are never parsed.
- Listening data stays on device. W1 adds no network call. Scoring uses only rows upstream already fetched.
- Use imports, not inline fully qualified names, in Turtle code. Hook lines are the only exception.
- ktlint 1.8.0 style (root `build.gradle.kts`). `:turtle-bridge` and `:turtle-ui` have `ktlintCheck`/`ktlintFormat`; the engines included build has no ktlint task, and the ktlint CLI isn't installed. Format new files by hand to ktlint style. Do not run module-wide `ktlintFormat`: existing Turtle files aren't ktlint-clean, and it would reformat unrelated code.
- Engine tests run against public APIs. The recommendation offline replay (`OfflineReplayTest`) must not regress by more than 2%. W1 doesn't touch ranking, so expect identical numbers.
- No tests for UI composables or upstream code. UI tasks are verified by compiling.
- Conventional Commits with a `turtle-*` scope (`feat(turtle-taste): …`, `feat(turtle-bridge): …`, `feat(turtle-ui): …`). No commit until the product owner approves.

## Assumed interfaces from earlier workstreams

- **from W3:**
  - the `PlayEventStore` receives no new events while upstream's "Pause listen history" is on, and none for spoken word (audiobooks, podcast episodes). Jump back in relies on this.
  - W3 does not purge spoken-word plays stored before it landed, so the bridge also filters Jump back in with W3's one rule, `TurtleSpoken.isSpokenWord(song)`, after awaiting `TurtleSpoken.load()` once (until it runs, `bookOf` knows no chapters). W8 extends that rule; Task 4 needs no change then.
- **from W2:**
  - `TurtleHomeShelves.shelves` still publishes `HomeShelf`s keyed `"turtle:" + Shelf.id`, where `Shelf.id` is the engine's `"<ShelfKind lowercase>:<suffix>"` (`TasteEngine.shelfId`). W2's restored shelves use the same keys. W1's one-line edit to `TurtleHomeShelves.toHomeShelf` applies to W2's version of that function.
  - The Koin binding is `single { TurtleHomeShelves(get(), get(), get(), get()) }` (four arguments: the fourth is `SqlitePlayEventStore`).
  - `TurtleRuntime.start()`'s first launch begins with `try { restoreTasteState() } finally { tasteRestored.complete(Unit) }`.
- **from W9 (controls, blocks, hidden):**
  - The listener's controls change from the bridge in exactly three `TurtleRuntime` functions: the existing `lessLikeThis(song)`, and W9's `notInterested(target: NotInterested)` and `undoHidden(entry: HiddenEntry)`. (A grep of `turtle/bridge` and `turtle/ui` on 22 Nov 2026 found no other caller of `updateControls` or `TasteEngine.lessLikeThis`; every `ShellActions.lessLikeThis` goes through `TurtleBridge.lessLikeThis`.) Task 4 appends `homeRows.onControlsChanged()` to each of the three, so a muted artist's YouTube row and recents leave Home at once.
  - `TasteEngine.controls` is the effective set, including upstream's Block list (W9 Task 4). W9's `UpstreamBlocks.changes: StateFlow<BlockedSet>` emits after the engine sees a new block list; Home re-scores on it (Task 4). Jump back in still filters songs with `BlockedContent.isBlocked(song)` directly, which also covers artists blocked by id that W9 has not resolved to a name yet.
  - `TurtleBridge.hiddenSet: HiddenSet` (Compose state) is what Home must not show right now. W9 Task 11 filters `HomeLists` with it; W1 moves that filter into `HomeLayout.of` (Task 3).
- **from W9 (attribution and the menu):** W9 Task 11 leaves `ShellHome` with these calls, which W1 must keep on every tile, card and row it moves or adds:
  - `ShellHomeFeedback.play(place: PlayPlace, row: String?, songs: List<SongItem>, play: () -> Unit)` before a grid tile starts playback, with `PlayPlace.HOME_GRID` and `row = null`;
  - `ItemRow(..., place: PlayPlace)`, which attributes with `(place, shelf.key, songs)` and arms the "Not interested" latch for every place except `HOME_YOUTUBE`;
  - `MixTile`, which attributes with `PlayPlace.HOME_TURTLE` and the mix's shelf key, and wraps the card in W9's `MixMenu`;
  - the grid tile's `onLongClick = slot.onItemLongClick?.let { f -> { ShellHomeFeedback.openMenu(item, f) } }`.
  - Infinity is not attributed by Home: Infinity tags its own songs (W9 Task 11 decisions).

## Review Focus

These five input classes or failure modes are the most likely to reach a user without any task's main tests exercising them. Each one gets a test in the task named.

1. **Muted but loved.** An artist the listener played a lot and then muted (or "less like this") still has positive affinity. Their rows must not return, including the row already on screen when they mute. → Task 1 `mutedArtistCountsAsDislikedEvenWhenLoved`, Task 4 `controlsChangeRescoresTheShownRows`.
2. **Twins and unplayable history.** The same song appears as an official video and as a Topic upload, and import rows have no YouTube id or come from Spotify. Jump back in must show it once and never show an item that can't play. → Task 2 `jumpBackInListsRecentFinishedSongsNewestFirstOnce`, Task 4 `jumpBackInResolvesFromCacheAndDatabaseAndSkipsSpokenWord`.
3. **Late pages.** A continuation page or pull-to-refresh arrives after first paint carrying a higher-scoring row. Rows on screen must not be displaced. → Task 1, `shownRowsKeepTheirSlotsWhenBetterRowsArrive`.
4. **One item in many places.** A pinned tile can also be the newest recent and also sit in a YouTube row. It must show once, in the grid. A kept row that dedupe empties must disappear, not render blank. → Task 3, `gridTakesPinsThenRecentsThenMatchedSuggestionsWithoutRepeats`.
5. **Upstream signals that lie.**
   - While an upstream chip is selected, every row carries `feedChip`.
   - Signed-out Home merges Explore sections in under `section_*` keys.
   - Spoken word can sit in history.

   Only `chip_` keys, Explore matches and podcast rows may be dropped. → Task 3 `candidatesDropSpeedDialRecentsChipsChartsExplorePodcastsAndMoods` (every row has `feedChip` set), and the podcast assertion in Task 4.

## Open decisions flagged for the owner

These defaults are the plan's own. The spec is silent on each, and each is a one-line change.

- **Grid round-robin by setting:**
  - Matched: taste-filtered.
  - All: unfiltered, as today.
  - Off: no YouTube round-robin tiles, only pins and recents.
- **Cold start:** shows YouTube's first 3 eligible rows in upstream order, and the playlist-only rule does not apply. A new listener's signed-out Home is mostly playlists.
- **`new_releases` and `community_playlists`:** both are Explore-sourced (`HomeViewModel` loads `FEmusic_explore`), so they're excluded with charts. They reach Home under All, and through W7 later.
- **Jump back in:** covers finished plays from the last 30 days, from `EventSource.LOCAL` and `METROLIST_IMPORT` (both on-device). Other imports are excluded. The row shows only when at least 3 recents remain after the grid.
- **Off:** Home doesn't page upstream's continuation at all, since no YouTube row would show.
- **Initial thresholds:** score ≥ 0.15 and disliked share ≤ 0.2, tuned on device. The bridge logs every row's score for that tuning. The offline replay doesn't exercise row selection.

---

### Task 1: Engine row scoring and selection

**Files:**
- Create: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/HomeRows.kt`
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt`:
  - `TasteConfig` (lines 21-53): add the `homeRows` field;
  - after `recommendations(...)` (lines 486-494), before `// ---- Persistence` (line 496): add a `// ---- Home rows` section.
- Test: create `turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/HomeRowsTest.kt`.

**Interfaces:**
- Consumes: `TasteProfile.track/artist/hasHeard/hasHeardArtist/finishedPlays`, `UserControls.mutedArtists/lessLikeThisArtists/lessLikeThisTracks`, `TasteConfig.coldStartPlays` (existing bar, 50).
- Produces (package `app.turtleplayer.taste`):

```kotlin
sealed interface RowItem {
    data class Track(val track: TrackMeta) : RowItem
    data class Artist(val artist: ArtistKey) : RowItem
    /** An album or playlist with the artists its source names; empty for playlists, which name none. */
    data class Collection(val artists: List<ArtistKey>) : RowItem
}

data class RowCandidate(val id: String, val items: List<RowItem>, val personal: Boolean = false)

data class RowScore(
    /** Mean of tanh(affinity / affinityScale) over items whose track or artist the profile knows; 0 when none. */
    val meanAffinity: Double,
    /** Items by an artist the listener has heard or saved, over [scoredItems]. */
    val knownArtistShare: Double,
    /** Items by a muted, "less like this" or net-disliked artist (or a "less like this" track), over [scoredItems]. */
    val dislikedShare: Double,
    /** Items that name at least one artist. */
    val scoredItems: Int,
) {
    val value: Double get() = knownArtistShare * meanAffinity
}

data class HomeRowConfig(
    val minScore: Double = 0.15,
    val maxDislikedShare: Double = 0.2,
    val maxRows: Int = 3,
    val maxPersonalPlaylistRows: Int = 1,
    val minScoredItems: Int = 3,
    val affinityScale: Double = 3.0,
)

data class RowSelection(val ids: List<String>, val coldStart: Boolean, val scores: Map<String, RowScore>)

// TasteConfig gains: val homeRows: HomeRowConfig = HomeRowConfig(),

// TasteEngine:
suspend fun scoreRow(items: List<RowItem>): RowScore
suspend fun selectHomeRows(candidates: List<RowCandidate>, sticky: List<String> = emptyList()): RowSelection
suspend fun matchesTaste(items: List<RowItem>): List<Boolean>
```

- [ ] **Step 1: Write the failing tests** in `HomeRowsTest.kt`:

```kotlin
package app.turtleplayer.taste

// imports: ArtistKey, DAY_MS, HOUR_MS, MINUTE_MS, TrackKey, TrackMeta (core); EndReason, InMemoryPlayEventStore,
// PlayEvent (events); runTest; Random; kotlin.test.*

class HomeRowsTest {
    private val t0 = SyntheticWorld.T0

    private fun track(id: String, artist: String, title: String = "song $id") =
        TrackMeta(TrackKey.youtube(id), title, listOf(artist), durationMs = 200_000, youtubeId = id)

    private fun finishes(artist: String, n: Int, fromMs: Long) =
        List(n) { play(track("${artist.lowercase()}$it", artist), fromMs + it * 5 * MINUTE_MS) }

    private fun skips(artist: String, n: Int, fromMs: Long) =
        List(n) { play(track("${artist.lowercase()}s$it", artist), fromMs + it * 5 * MINUTE_MS, end = EndReason.SKIPPED) }

    /** Warm (60 finishes ≥ coldStartPlays 50): Alpha and Beta loved, Gamma quick-skipped, Delta never heard. */
    private val warm = finishes("Alpha", 30, t0) + finishes("Beta", 30, t0 + DAY_MS) + skips("Gamma", 5, t0 + 2 * DAY_MS)

    private suspend fun engine(events: List<PlayEvent> = warm, controls: UserControls = UserControls()) = TasteEngine(
        events = InMemoryPlayEventStore().also { it.appendAll(events) },
        store = InMemoryEmbeddingStore(), catalog = InMemoryTrackCatalog(emptyList()),
        clock = MutableClock((events.maxOfOrNull { it.startedAtMs } ?: t0) + HOUR_MS), random = Random(1), controls = controls,
    )

    /** [n] never-played songs by [artist]. */
    private fun tracks(artist: String, n: Int) = List(n) { RowItem.Track(track("new${artist.lowercase()}$it", artist)) }
    private fun row(id: String, items: List<RowItem>, personal: Boolean = false) = RowCandidate(id, items, personal)
    private val playlists = List(6) { RowItem.Collection(emptyList()) }

    private val dislikedRow = row("dislikedRow", tracks("Gamma", 4))
    private val unknownRow = row("unknownRow", tracks("Delta", 4))
    private val betaRow = row("betaRow", tracks("Beta", 3) + tracks("Delta", 1))
    private val alphaRow = row("alphaRow", tracks("Alpha", 4))
    private val mixedRow = row("mixedRow", tracks("Alpha", 2) + tracks("Delta", 2))
    private val smallRow = row("smallRow", tracks("Alpha", 2))
    private val mostlyAlphaOneGamma = row("mostlyAlphaOneGamma", tracks("Alpha", 3) + tracks("Gamma", 1))
    private val editorial = row("editorial", playlists)
    private val yourMixes = row("yourMixes", playlists, personal = true)
    private val all = listOf(dislikedRow, unknownRow, betaRow, alphaRow, mixedRow, smallRow, mostlyAlphaOneGamma, editorial, yourMixes)

    @Test fun scoreRowCountsKnownAndDislikedShares() = runTest {
        val s = engine().scoreRow(tracks("Alpha", 1) + RowItem.Artist(ArtistKey.of("Beta")) +
            RowItem.Collection(listOf(ArtistKey.of("Gamma"))) + tracks("Delta", 1))
        assertEquals(4, s.scoredItems)
        assertEquals(0.75, s.knownArtistShare, 1e-9)
        assertEquals(0.25, s.dislikedShare, 1e-9)
        assertTrue(s.meanAffinity > 0.0, "two loved artists outweigh one skipped: $s")
        assertEquals(0.75 * s.meanAffinity, s.value, 1e-9)
    }

    @Test fun playlistOnlyRowHasNothingToScore() = runTest {
        assertEquals(RowScore(0.0, 0.0, 0.0, 0), engine().scoreRow(playlists))
    }

    @Test fun selectionKeepsMatchingRowsByScoreCappedAtThree() = runTest {
        val e = engine()
        val picked = e.selectHomeRows(all)
        assertFalse(picked.coldStart)
        // alpha ≈ 1.0, beta ≈ 0.75 (mixed ≈ 0.5 loses its slot to the personal playlist row, which goes last).
        assertEquals(listOf("alphaRow", "betaRow", "yourMixes"), picked.ids)
        assertEquals(listOf("alphaRow", "betaRow", "mixedRow"), e.selectHomeRows(all - yourMixes - editorial).ids)
        // Too few scorable items, disliked share 0.25 > 0.2, unknown artists, non-personal playlists: never kept.
        val notKept = setOf("smallRow", "mostlyAlphaOneGamma", "unknownRow", "dislikedRow", "editorial")
        assertTrue(e.selectHomeRows(all - yourMixes).ids.none { it in notKept })
    }

    @Test fun mutedArtistCountsAsDislikedEvenWhenLoved() = runTest { // Review Focus 1
        val e = engine(controls = UserControls(mutedArtists = setOf(ArtistKey.of("Alpha"))))
        assertEquals(1.0, e.scoreRow(alphaRow.items).dislikedShare, 1e-9)
        assertEquals(emptyList(), e.selectHomeRows(listOf(alphaRow)).ids)
        // One "not interested" on a single song does not count the whole artist as disliked.
        val lessLike = engine(controls = UserControls(lessLikeThisArtists = mapOf(ArtistKey.of("Alpha") to 1)))
        assertEquals(listOf("alphaRow"), lessLike.selectHomeRows(listOf(alphaRow)).ids)
    }

    @Test fun coldStartShowsFirstThreeRowsUnscored() = runTest {
        val cold = engine(finishes("Alpha", 10, t0)).selectHomeRows(all)
        assertTrue(cold.coldStart)
        assertEquals(listOf("dislikedRow", "unknownRow", "betaRow"), cold.ids)
        assertTrue(engine(finishes("Alpha", 49, t0)).selectHomeRows(all).coldStart)
        assertFalse(engine(finishes("Alpha", 50, t0)).selectHomeRows(all).coldStart)
        assertEquals(emptyList(), engine(emptyList()).selectHomeRows(emptyList()).ids)
    }

    @Test fun shownRowsKeepTheirSlotsWhenBetterRowsArrive() = runTest { // Review Focus 3
        val e = engine()
        val first = e.selectHomeRows(listOf(betaRow, mixedRow)).ids
        assertEquals(listOf("betaRow", "mixedRow"), first)
        assertEquals(listOf("betaRow", "mixedRow", "alphaRow"), e.selectHomeRows(listOf(betaRow, mixedRow, alphaRow), sticky = first).ids)
        // A sticky row that left the candidates frees its slot.
        assertEquals(listOf("mixedRow", "alphaRow"), e.selectHomeRows(listOf(alphaRow, mixedRow), sticky = listOf("goneRow", "mixedRow")).ids)
        // A sticky row that no longer passes (artist muted since) is dropped.
        val muted = engine(controls = UserControls(mutedArtists = setOf(ArtistKey.of("Beta"))))
        assertEquals(listOf("alphaRow"), muted.selectHomeRows(listOf(betaRow, alphaRow), sticky = listOf("betaRow")).ids)
    }

    @Test fun matchesTasteKeepsKnownLikedItemsOnly() = runTest {
        val items = tracks("Alpha", 1) + RowItem.Artist(ArtistKey.of("Delta")) +
            RowItem.Collection(listOf(ArtistKey.of("Gamma"))) + RowItem.Collection(emptyList()) + RowItem.Artist(ArtistKey.of("Beta"))
        assertEquals(listOf(true, false, false, false, true), engine().matchesTaste(items))
        assertEquals(List(5) { true }, engine(emptyList()).matchesTaste(items)) // cold start: unfiltered
    }
}
```

- [ ] **Step 2: Run and see it fail.** `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.HomeRowsTest'` → compilation fails: unresolved `RowItem`, `RowCandidate`, `RowScore`, `scoreRow`, `selectHomeRows`, `matchesTaste`.

- [ ] **Step 3: Implement.**
  - `HomeRows.kt`: holds the public types above plus `internal class RowScorer(profile: TasteProfile, controls: UserControls, config: HomeRowConfig)` with `fun score(items: List<RowItem>): RowScore`, `fun matches(item: RowItem): Boolean` and `fun select(candidates: List<RowCandidate>, sticky: List<String>, coldStart: Boolean): RowSelection`.
  - `TasteEngine` wrappers each take `mutex.withLock { ensureLoaded(); … }` and build the scorer from `profile`, `controls` and `config.homeRows`. Cold start is `profile.finishedPlays < config.coldStartPlays`.

  Rules the signatures don't settle. Implement exactly these.

  **Scoring**
  - An item's artists:
    - `Track`: `track.artists.map(ArtistKey::of)`;
    - `Artist`: the one key;
    - `Collection`: its list.
  - An item counts as scored only when it names at least one artist.
  - **Affinity:** the track's own `profile.track(key)?.affinity` when present. Otherwise the max `profile.artist(a)?.affinity` over its artists. Items with neither are left out of `meanAffinity`. Normalize each value with `tanh(a / affinityScale)`, as `onRepeatLocked` does.
  - **Known:** `Track` items count when `profile.hasHeard(track)` or any of their artists is heard. Other items count when `profile.hasHeardArtist(a)` holds for any artist.
  - **Disliked:** any artist in `controls.mutedArtists`, or with `profile.artist(a)?.affinity < 0`; or, for a `Track`, its key is in `controls.lessLikeThisTracks`.
  - Shares are counted over scored items. With no scored items, every field is 0.

  **Selection, warm**
  1. A row passes when `scoredItems >= minScoredItems && value >= minScore && dislikedShare <= maxDislikedShare`.
  2. The personal playlist row is the first candidate in input order with `scoredItems == 0 && personal`. At most `maxPersonalPlaylistRows` such rows.
  3. Start the result with the sticky ids that are still kept (passing, or the personal row), in sticky order.
  4. Fill the remaining scored slots from the passing rows by `value` descending, with ties in input order. The scored rows can take up to `maxRows - (1 if a personal row exists)` slots.
  5. Append the personal row last if it isn't already in.
  6. Cap at `maxRows`.

  **Selection, cold start**
  - `candidates.take(maxRows)` in input order; `sticky` is ignored.
  - `scores` holds every candidate's `RowScore` in both modes (for the bridge log).

  **`matches`**
  - At cold start: `true`.
  - Otherwise: the item is not disliked, and it has a known affinity whose normalized value is above `0.0`. Artist-less items return `false`.

- [ ] **Step 4: Run and see it pass.** `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.HomeRowsTest'` → PASS (7 tests).

- [ ] **Step 5: Stage for commit** (don't commit). `git add turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/HomeRows.kt turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/HomeRowsTest.kt`. Suggested message: `feat(turtle-taste): score and select YouTube Home rows by taste`.

### Task 2: Engine Jump back in

**Files:**
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt`, in the `// ---- Home rows` section added in Task 1.
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/HomeRows.kt`, adding the pure helper.
- Test: `turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/HomeRowsTest.kt` (extend).

**Interfaces:**
- Consumes: `PlayEventStore.since(sinceMs)` (oldest first; never pass `limit`, which keeps the *oldest* rows), `PlayEvent.isFinished`, `PlayEvent.source`, `SongKeys`, `controls`.
- Produces:

```kotlin
// TasteEngine:
/** The listener's most recent distinct finished songs, newest first; reads the event log directly, without the engine lock. */
suspend fun jumpBackIn(limit: Int, days: Int = 30): List<TrackMeta>
```

- [ ] **Step 1: Write the failing test** (add to `HomeRowsTest`; import `EventSource`):

```kotlin
@Test fun jumpBackInListsRecentFinishedSongsNewestFirstOnce() = runTest { // Review Focus 2
    val a1 = track("a1", "Alpha"); val a2 = track("a2", "Alpha"); val a3 = track("a3", "Alpha")
    val a1Video = track("a1v", "Alpha", title = "song a1") // the same song as another upload
    val events = listOf(
        play(track("old", "Alpha"), t0 - 31 * DAY_MS), // outside the 30-day window
        play(a1, t0),
        play(track("m1", "Beta"), t0 + 2 * MINUTE_MS).copy(source = EventSource.METROLIST_IMPORT),
        play(a2, t0 + 5 * MINUTE_MS),
        play(a1, t0 + 10 * MINUTE_MS),
        play(track("b1", "Beta"), t0 + 15 * MINUTE_MS, end = EndReason.SKIPPED), // not finished
        play(a3, t0 + 20 * MINUTE_MS),
        play(a1Video, t0 + 25 * MINUTE_MS),
        play(track("sp", "Gamma"), t0 + 30 * MINUTE_MS).copy(source = EventSource.SPOTIFY_IMPORT), // not on-device
    )
    val keys = { list: List<TrackMeta> -> list.map { it.key.value } }
    assertEquals(listOf("yt:a1v", "yt:a3", "yt:a2", "yt:m1"), keys(engine(events).jumpBackIn(10)))
    assertEquals(listOf("yt:a1v", "yt:a3"), keys(engine(events).jumpBackIn(2)))
    assertEquals(listOf("yt:a1v", "yt:a2", "yt:m1"), keys(engine(events, UserControls(lessLikeThisTracks = setOf(a3.key))).jumpBackIn(10)))
    assertEquals(listOf("yt:m1"), keys(engine(events, UserControls(mutedArtists = setOf(ArtistKey.of("Alpha")))).jumpBackIn(10)))
}
```

- [ ] **Step 2: Run and see it fail.** `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.HomeRowsTest'` → compilation fails: unresolved `jumpBackIn`.

- [ ] **Step 3: Implement.**
  - Add `internal fun jumpBackInOf(events: List<PlayEvent>, controls: UserControls, limit: Int, songs: SongKeys): List<TrackMeta>` to `HomeRows.kt`. It keeps events that are `isFinished`, whose `source` is in `setOf(EventSource.LOCAL, EventSource.METROLIST_IMPORT)`, whose `trackKey !in controls.lessLikeThisTracks` and whose `artistKey !in controls.mutedArtists`. It walks them newest first by `startedAtMs`, keeps each `songs.of(track)` identity once (the newest upload wins) and takes `limit`.
  - Add `TasteEngine.jumpBackIn`. It reads `controls` without taking the mutex. That is a deliberate benign race: the profile refresh holds the lock for seconds, and Jump back in must not wait on it. After W9 Task 4, `controls` is a getter over a `@Volatile` own-controls field and one `@Volatile` merge-cache holder, which W9 pins as safe to call from any thread. It calls `events.since(clock.nowMs() - days * DAY_MS)` and passes a **new local** `SongKeys()`, because the engine's `songs` isn't thread-safe outside the lock.

- [ ] **Step 4: Run and see it pass.** Same command → PASS (8 tests).

- [ ] **Step 5: Stage for commit** (don't commit). `git add` the same three files as Task 1. Suggested message: `feat(turtle-taste): Jump back in from the on-device event log`.

### Task 3: Bridge Home layout and candidates

**Files:**
- Create: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/HomeLayout.kt`
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleHomeShelves.kt`, two lines:
  - in `toHomeShelf` (line 90 at HEAD, `key = "turtle:${shelf.id}"`): `key = "$TURTLE_SHELF_KEY_PREFIX${shelf.id}"`;
  - in W9's `originOf` fallback, `homeKey.removePrefix("turtle:")` becomes `homeKey.removePrefix(TURTLE_SHELF_KEY_PREFIX)`.
- Test: create `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/HomeLayoutTest.kt`.

**Interfaces:**
- Consumes:
  - upstream `HomeShelf`, `HomeShelfStyle`, `HomeUiState` (`speedDialPinnedIds`, `quickPicks`, `discoverySections`);
  - `canonicalHomeItemId` (`com.metrolist.shared.model`, public);
  - `YTItem.isPodcast`;
  - `SongItem.toTrackMeta()` (`SongItemMapping.kt`);
  - Task 1's `RowItem` and `RowCandidate`;
  - W9's `HiddenSet` (`hides(item)`, `hidesShelf(key)`, same package).
- Produces (package `app.turtleplayer.bridge`):

```kotlin
const val TURTLE_SHELF_KEY_PREFIX = "turtle:"

/** "YouTube Music suggestions on Home". Stored with TurtleBridge.setUiPref(PREF_KEY, prefValue). */
enum class HomeYouTubeRows(val prefValue: String) {
    MATCHED("matched"), ALL("all"), OFF("off");
    companion object {
        const val PREF_KEY = "home_youtube_rows"
        fun fromPref(value: String?): HomeYouTubeRows
    }
}

/** The bridge's decision for one set of candidate rows. [scored] false: cold start or scoring failed (rows shown unscored). */
data class HomeRowsPick(val rowKeys: List<String>, val gridIds: Set<String>, val scored: Boolean)

class HomeLayout(
    /** Up to [GRID_TILES] shortcut tiles after Infinity and Liked Songs. */
    val grid: List<YTItem>,
    /** False while recents, the setting or (Matched, tiles short) the pick are not known yet: draw the placeholder. */
    val gridReady: Boolean,
    val mixes: List<HomeShelf>,
    val moreLike: List<HomeShelf>,
    val jumpBackIn: List<SongItem>,
    val youtubeRows: List<HomeShelf>,
) {
    companion object {
        const val GRID_TILES = 6
        const val JUMP_BACK_IN_MIN_ITEMS = 3
        fun candidates(shelves: List<HomeShelf>, state: HomeUiState, mode: HomeYouTubeRows): List<HomeShelf>
        fun gridCandidates(shelves: List<HomeShelf>, state: HomeUiState): List<YTItem>
        /** [hidden]: W9's TurtleBridge.hiddenSet, applied before the grid is filled. */
        fun of(shelves: List<HomeShelf>, state: HomeUiState, jumpBackIn: List<SongItem>?, pick: HomeRowsPick?, mode: HomeYouTubeRows?, hidden: HiddenSet = HiddenSet()): HomeLayout
    }
}

fun HomeShelf.isMoreLike(): Boolean   // key starts with "turtle:" + ShelfKind.MORE_LIKE.name.lowercase() + ":"
internal fun YTItem.toRowItem(): RowItem
internal fun HomeShelf.toRowCandidate(): RowCandidate   // RowCandidate(key, items.map { it.toRowItem() }, personal = isPersonal)
```

- [ ] **Step 1: Write the failing tests** in `HomeLayoutTest.kt`:

```kotlin
class HomeLayoutTest {
    private fun song(id: String, artist: String = "Queen", podcastId: String? = null) =
        SongItem(id = id, title = "t$id", thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200, podcastId = podcastId)
    private val album = AlbumItem("al1", "A", null, listOf(ArtistTiny(null, "Queen")))
    // Every row carries feedChip, as upstream sets it while a chip is selected (Review Focus 5).
    private fun shelf(key: String, vararg items: YTItem, style: HomeShelfStyle = HomeShelfStyle.CAROUSEL, title: String = key, browseId: String? = null, personal: Boolean = false) =
        HomeShelf(key = key, title = title, items = items.toList(), style = style, browseId = browseId, isPersonal = personal, feedChip = Chip("Relax"))

    private val state = HomeUiState(
        discoverySections = listOf(HomeSection(title = "Explore row", items = listOf(song("e1")), browseId = "FEmusic_x")),
        speedDialPinnedIds = listOf("p1"),
    )
    private val shelves = listOf(
        shelf("speed_dial", song("p1"), song("r1"), song("r2"), song("r3"), style = HomeShelfStyle.SPEED_DIAL),
        shelf("keep_listening", song("k1")),
        shelf("quick_picks", song("j1"), song("q1"), style = HomeShelfStyle.SONG_GRID),
        shelf("chip_0_0", song("c1")),
        shelf("trending", song("t1")),
        shelf("album_charts", album, style = HomeShelfStyle.ALBUM_CAROUSEL),
        shelf("new_releases", album),
        shelf("community_playlists", PlaylistItem("pl1", "P", null)),
        shelf("podcasts", song("ep1", podcastId = "P1")),
        shelf("moods", song("m1"), style = HomeShelfStyle.MOOD_AND_GENRES),
        shelf("section_FEmusic_x_3", song("e1"), title = "Explore row", browseId = "FEmusic_x"),
        shelf("section_mixed_4", song("r2")),
        shelf("section_Your mixes_5", PlaylistItem("pl2", "Mix", null), personal = true),
        shelf("similar_UC1_0", song("s1"), style = HomeShelfStyle.SIMILAR_TO),
        shelf("turtle:daily_mix:0", song("d1"), style = HomeShelfStyle.SONG_GRID),
        shelf("turtle:more_like:yt:q1", song("ml1"), style = HomeShelfStyle.SONG_GRID),
    )

    @Test fun candidatesDropSpeedDialRecentsChipsChartsExplorePodcastsAndMoods() {
        val matched = listOf("quick_picks", "section_mixed_4", "section_Your mixes_5", "similar_UC1_0")
        assertEquals(matched, HomeLayout.candidates(shelves, state, HomeYouTubeRows.MATCHED).map { it.key })
        assertEquals(matched, HomeLayout.candidates(shelves, state, HomeYouTubeRows.OFF).map { it.key })
        assertEquals(
            listOf("keep_listening", "quick_picks", "chip_0_0", "trending", "album_charts", "new_releases", "community_playlists",
                "podcasts", "moods", "section_FEmusic_x_3", "section_mixed_4", "section_Your mixes_5", "similar_UC1_0"),
            HomeLayout.candidates(shelves, state, HomeYouTubeRows.ALL).map { it.key },
        )
        assertEquals(listOf("r1", "r2", "r3"), HomeLayout.gridCandidates(shelves, state).map { it.id })
        assertEquals(listOf("q9"), HomeLayout.gridCandidates(emptyList(), HomeUiState(quickPicks = listOf(song("q9")))).map { it.id })
    }

    @Test fun gridTakesPinsThenRecentsThenMatchedSuggestionsWithoutRepeats() { // Review Focus 4
        val recents = listOf(song("p1"), song("j1"), song("j2"))
        val pick = HomeRowsPick(rowKeys = listOf("quick_picks", "section_mixed_4"), gridIds = setOf("r2", "r3"), scored = true)
        val matched = HomeLayout.of(shelves, state, recents, pick, HomeYouTubeRows.MATCHED)
        assertEquals(listOf("p1", "j1", "j2", "r2", "r3"), matched.grid.map { it.id })
        assertTrue(matched.gridReady)
        assertEquals(emptyList(), matched.jumpBackIn)                                   // every recent is already a tile
        assertEquals(listOf("quick_picks"), matched.youtubeRows.map { it.key })        // section_mixed_4 emptied by dedupe
        assertEquals(listOf("q1"), matched.youtubeRows.single().items.map { it.id })   // j1 removed: it is a tile
        assertEquals(listOf("turtle:daily_mix:0"), matched.mixes.map { it.key })
        assertEquals(listOf("turtle:more_like:yt:q1"), matched.moreLike.map { it.key })

        assertEquals(listOf("p1", "j1", "j2", "r1", "r2", "r3"), HomeLayout.of(shelves, state, recents, null, HomeYouTubeRows.ALL).grid.map { it.id })
        val off = HomeLayout.of(shelves, state, recents, null, HomeYouTubeRows.OFF)
        assertEquals(listOf("p1", "j1", "j2"), off.grid.map { it.id })
        assertEquals(emptyList(), off.youtubeRows)

        // Not yet known: no YouTube rows, and a short grid waits rather than reshuffling after first paint.
        val unscored = HomeLayout.of(shelves, state, recents, null, HomeYouTubeRows.MATCHED)
        assertEquals(emptyList(), unscored.youtubeRows)
        assertFalse(unscored.gridReady)
        assertFalse(HomeLayout.of(shelves, state, recents, pick, null).gridReady)
        assertFalse(HomeLayout.of(shelves, state, null, pick, HomeYouTubeRows.MATCHED).gridReady)

        // W9's "Not interested": hidden items never take a tile, and a hidden mix leaves Made for you.
        val hidden = HiddenSet(songIds = setOf("j1", "q1"), homeShelfKeys = setOf("turtle:daily_mix:0"))
        val withHidden = HomeLayout.of(shelves, state, recents, pick, HomeYouTubeRows.MATCHED, hidden)
        assertEquals(listOf("p1", "j2", "r2", "r3"), withHidden.grid.map { it.id })
        assertEquals(emptyList(), withHidden.mixes)
        assertEquals(emptyList(), withHidden.youtubeRows) // quick_picks held only j1 and q1
    }

    @Test fun jumpBackInRowShowsRecentsNotInTheGrid() {
        val nine = (1..9).map { song("j$it") }
        val layout = HomeLayout.of(emptyList(), HomeUiState(), nine, null, HomeYouTubeRows.MATCHED)
        assertEquals((1..6).map { "j$it" }, layout.grid.map { it.id })
        assertTrue(layout.gridReady)                                     // recents fill every tile: no pick needed
        assertEquals(listOf("j7", "j8", "j9"), layout.jumpBackIn.map { it.id })
        assertEquals(emptyList(), HomeLayout.of(emptyList(), HomeUiState(), nine.take(8), null, HomeYouTubeRows.MATCHED).jumpBackIn)
    }

    @Test fun rowItemsCarryArtistsOnlyWhereUpstreamNamesThem() {
        assertEquals(ArtistKey.of("Queen"), (song("x", artist = "Queen - Topic").toRowItem() as RowItem.Track).track.artistKey)
        assertEquals(RowItem.Collection(listOf(ArtistKey.of("Queen"))), album.toRowItem())
        assertEquals(RowItem.Artist(ArtistKey.of("Queen")), ArtistItem("UC1", "Queen", null).toRowItem())
        assertEquals(RowItem.Collection(emptyList()), PlaylistItem("pl", "P", null).toRowItem())
    }
}
```

- [ ] **Step 2: Run and see it fail.** `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.HomeLayoutTest'` → compilation fails: unresolved `HomeLayout`, `HomeYouTubeRows`, `HomeRowsPick`, `toRowItem`.

- [ ] **Step 3: Implement** `HomeLayout.kt`.

  **`candidates`**
  - Keep only shelves where the key doesn't start with `TURTLE_SHELF_KEY_PREFIX`, the style isn't `SPEED_DIAL`, and `items` isn't empty; then `distinctBy { it.key }`, keeping upstream order.
  - Under `ALL`, return that list.
  - Otherwise also drop:
    - `key == "keep_listening"` or `browseId` starting with `"FEmusic_listen_again"`;
    - keys starting with `"chip_"`. Never use `feedChip`, which upstream sets on every row while a chip is selected (`HomeShelfBuilder.kt:384-387`);
    - keys in `setOf("trending", "album_charts", "new_releases", "community_playlists")`;
    - rows matching any `state.discoverySections` entry on both `title` and `browseId` (Explore merged into signed-out Home, `HomeViewModel.kt:579-640`);
    - `key == "podcasts"` or rows where every item is a podcast (`items.all { it.isPodcast }`);
    - `style == MOOD_AND_GENRES` or `key == "moods"`.

  **`gridCandidates`**
  - The `SPEED_DIAL` shelf's items whose `id` isn't in `state.speedDialPinnedIds` (upstream's round-robin).
  - Without that shelf: `state.quickPicks`.

  **`of`**
  0. Apply W9's `hidden` first, as `HomeLists.of` does after W9 Task 11: drop Turtle shelves where `hidden.hidesShelf(key)`, and drop items where `hidden.hides(item)` from every shelf, from the Speed Dial items and from `jumpBackIn`. Everything below works on the filtered lists, so a hidden item never takes a tile and a row it empties disappears.
  1. Split mixes from More like X:
     - `turtle` = shelves with the prefix, `distinctBy(key)`;
     - `moreLike = turtle.filter { it.isMoreLike() }`;
     - `mixes` = the rest, in engine order.
  2. Build the grid:
     - `pinned` = Speed Dial items whose id is pinned;
     - `roundRobin` depends on `mode`: `MATCHED` keeps ids in `pick?.gridIds.orEmpty()`, `ALL` keeps all, `OFF` and `null` keep none;
     - `grid = (pinned + jumpBackIn.orEmpty() + roundRobin).distinctBy(::canonicalHomeItemId).take(GRID_TILES)`.
  3. `gridReady` is `jumpBackIn != null && mode != null && (pinned + recents fill GRID_TILES || mode != MATCHED || pick != null)`.
  4. The Jump back in row is `jumpBackIn` minus grid ids. Use it only when its size is at least `JUMP_BACK_IN_MIN_ITEMS`; otherwise it's empty.
  5. YouTube rows:
     - `MATCHED`: take `pick.rowKeys` in order, mapped to current candidates by key; keys no longer present are skipped. Empty when `pick == null`.
     - `ALL`: every candidate.
     - `OFF` and `null`: none.
     - Then remove grid items from each row by `canonicalHomeItemId` and drop rows that end up empty.

  **Row items** (`toRowItem`)
  - `SongItem`: `RowItem.Track(toTrackMeta())`.
  - `ArtistItem`: `RowItem.Artist(ArtistKey.of(title))`.
  - `AlbumItem`: `RowItem.Collection(artists.map { ArtistKey.of(it.name) })`.
  - Anything else: `RowItem.Collection(emptyList())`.

  **Setting value:** `fromPref` falls back to `MATCHED` for an unknown or null value.

  **`TurtleHomeShelves`:** make the two one-line prefix changes listed under Files.

- [ ] **Step 4: Run and see it pass.** `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.HomeLayoutTest'` → PASS (4 tests).

- [ ] **Step 5: Stage for commit** (don't commit). `git add turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/HomeLayout.kt turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleHomeShelves.kt turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/HomeLayoutTest.kt`. Suggested message: `feat(turtle-bridge): Home layout with taste-matched YouTube rows`.

### Task 4: Bridge scoring and Jump back in state

**Files:**
- Create: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleHomeRows.kt`
- Modify `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt`:
  - after W2's `single { TurtleHomeShelves(get(), get(), get(), get()) }` (line 112 at HEAD): add `single { TurtleHomeRows(get(), get(), get(), database = { get<MusicDatabase>() }, blockChanges = get<UpstreamBlocks>().changes) }`. Pass the lambda by name: a trailing lambda would bind to the last parameter, `spokenReady`;
  - the `TurtleRuntime(...)` line (114 at HEAD): one more `get()`, after the two W9 added;
  - the `TurtleBridge` object, after `val homeShelves` (lines 260-261 at HEAD): add the accessors below.
- Modify `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt`:
  - the constructor (lines 32-45 at HEAD): append `private val homeRows: TurtleHomeRows` after W9's `playlistAdds` and `blocks`;
  - `start()` (lines 52-95): the first launch (lines 60-67) becomes, in this order: W2's `try { restoreTasteState() } finally { tasteRestored.complete(Unit) }`; W9's hidden-record load and `publishHidden()`; then `homeRows.start(scope)`; then the existing `restoreDiscoveryDial()` and the rest, with one added line right after `history.runOnce()`: `homeRows.refreshJumpBackIn()`, so a listener coming from Metrolist sees the imported plays in Jump back in without waiting for their next play. The first Jump back in then sees the listener's saved mutes. Don't wait for library sync, because resolution falls back to upstream's database;
  - call `homeRows.onControlsChanged()` at the end of each of the three functions that change the listener's controls: `lessLikeThis(song)` (lines 170-177 at HEAD), and W9's `notInterested(target)` and `undoHidden(entry)`.
- Test: create `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/TurtleHomeRowsTest.kt`.

**Interfaces:**
- Consumes:
  - Task 1 and 2 engine APIs;
  - Task 3's `toRowCandidate`, `toRowItem` and `HomeRowsPick`;
  - `SongCache.get`;
  - `MusicDatabase.dao().getSongsByIds(ids)` (`SongDao.kt:298`) with `com.metrolist.shared.db.entities.toSongItem`;
  - upstream `BlockedContent.isBlocked(song: SongItem)` (`shared/settings/BlockedContent.kt`);
  - W9's `UpstreamBlocks.changes: StateFlow<BlockedSet>`, which emits only after the taste engine sees a new block list. `BlockedContent.version` is not used: it fires before W9's rebuild, so a re-score on it could still pass a just-blocked artist's row;
  - W3's `TurtleSpoken.isSpokenWord(song)` and `TurtleSpoken.load()`;
  - `TasteEngine.lessLikeThis(LessLikeThis)` (test only);
  - `PlayEventStore.newEvents`;
  - `TurtleLog`.
- Produces:

```kotlin
class TurtleHomeRows(
    private val taste: TasteEngine,
    private val events: PlayEventStore,
    private val cache: SongCache,
    private val database: () -> MusicDatabase,
    /** Emits when the taste engine's view of upstream's Block list changed (W9's UpstreamBlocks.changes). */
    private val blockChanges: Flow<*>,
    /** Upstream's live Block list (blocked songs, artists and YouTube dislikes); injectable for tests. */
    private val isBlocked: (SongItem) -> Boolean = BlockedContent::isBlocked,
    /** W3's one spoken-word rule; plays stored before W3 landed may still be audiobook chapters. */
    private val isSpokenWord: (SongItem) -> Boolean = { TurtleSpoken.isSpokenWord(it) },
    /** Awaited once before the first Jump back in, so chapters of saved books are known. */
    private val spokenReady: suspend () -> Unit = { TurtleSpoken.load() },
) {
    /** Newest first; null until first loaded. Compose state. */
    var jumpBackIn: List<SongItem>? by mutableStateOf(null); private set
    /** Null until the first scoring of the current candidates. Compose state. */
    var pick: HomeRowsPick? by mutableStateOf(null); private set
    fun start(scope: CoroutineScope)
    suspend fun refreshJumpBackIn()
    /** Scores [rows] (HomeLayout.candidates) and grid round-robin [grid] on Dispatchers.Default; skips an unchanged input. */
    suspend fun score(rows: List<HomeShelf>, grid: List<YTItem>)
    /** Re-scores the last input (the listener's controls or cold-start state changed). No-op before the first [score]. */
    suspend fun invalidate()
    /** Mutes, "less like this", not interested or a block changed: refreshes Jump back in and re-scores. */
    suspend fun onControlsChanged()
}

// TurtleBridge:
val homeJumpBackIn: List<SongItem>?
val homeRowsPick: HomeRowsPick?
suspend fun scoreHomeRows(rows: List<HomeShelf>, grid: List<YTItem>)
```

- [ ] **Step 1: Write the failing tests** in `TurtleHomeRowsTest.kt`:

```kotlin
class TurtleHomeRowsTest {
    private val t0 = 1_793_577_600_000L

    private fun song(id: String, podcastId: String? = null) =
        SongItem(id = id, title = "t$id", thumbnail = null, artists = listOf(ArtistTiny(null, "Queen")), album = null, duration = 200, podcastId = podcastId)

    private fun finished(id: String, atMs: Long) = PlayEvent(
        track = TrackMeta(TrackKey.youtube(id), "t$id", listOf("Queen"), durationMs = 200_000, youtubeId = id),
        sessionId = "s", position = 0, surface = Surface.AUTOPLAY, startReason = StartReason.AUTOPLAY,
        endReason = EndReason.COMPLETED, startedAtMs = atMs, msPlayed = 200_000, durationMs = 200_000,
    )

    private fun engine(store: PlayEventStore) =
        TasteEngine(store, InMemoryEmbeddingStore(), InMemoryTrackCatalog(emptyList()), clock = Clock { t0 + HOUR_MS })

    @Test fun jumpBackInResolvesFromCacheAndDatabaseAndSkipsSpokenWord() = runBlocking { // Review Focus 2 and 5
        val dbFile = File.createTempFile("metrolist", ".db").apply { delete(); deleteOnExit() }
        val db = getDatabaseBuilder(dbFile).build()
        db.dao().insert(SongEntity(id = "b1", title = "tb1", customArtist = "Queen", duration = 200)) // only in upstream's table
        val cache = SongCache().apply { putAll(listOf(song("a1"), song("pod1", podcastId = "P1"))) }
        val store = InMemoryPlayEventStore().apply {
            appendAll(listOf(finished("a1", t0), finished("b1", t0 + 5 * MINUTE_MS), finished("pod1", t0 + 10 * MINUTE_MS), finished("gone1", t0 + 15 * MINUTE_MS)))
        }
        // A chapter of a Turtle audiobook, stored before W3 kept spoken word out of the log.
        store.appendAll(listOf(finished("ch1", t0 + 17 * MINUTE_MS)))
        cache.putAll(listOf(song("ch1")))
        store.appendAll(listOf(finished("blk1", t0 + 20 * MINUTE_MS))) // on upstream's Block list
        cache.putAll(listOf(song("blk1")))
        val home = TurtleHomeRows(
            engine(store), store, cache, { db }, blockChanges = emptyFlow(), isBlocked = { it.id == "blk1" },
            isSpokenWord = { it.isPodcast || it.id == "ch1" }, spokenReady = {},
        )
        assertNull(home.jumpBackIn)
        home.refreshJumpBackIn()
        assertEquals(listOf("b1", "a1"), home.jumpBackIn?.map { it.id }) // blocked, chapter, podcast and unresolvable dropped
    }

    private fun rows(store: PlayEventStore, taste: TasteEngine = engine(store)) =
        TurtleHomeRows(taste, store, SongCache(), { error("no database needed") }, blockChanges = emptyFlow(), isBlocked = { false }, spokenReady = {})

    private fun shelf(key: String, vararg ids: String) =
        HomeShelf(key = key, title = key, items = ids.map { song(it) }, style = HomeShelfStyle.CAROUSEL)

    @Test fun scoringPublishesTheFirstRowsAtColdStart() = runBlocking {
        val home = rows(InMemoryPlayEventStore())
        home.score(listOf(shelf("a", "sa"), shelf("b", "sb"), shelf("c", "sc"), shelf("d", "sd")), listOf(song("r1"), song("r2")))
        assertEquals(HomeRowsPick(rowKeys = listOf("a", "b", "c"), gridIds = setOf("r1", "r2"), scored = false), home.pick)
    }

    @Test fun controlsChangeRescoresTheShownRows() = runBlocking { // Review Focus 1
        // 60 finished Queen plays: warm, and Queen is loved. All song() items are by Queen.
        val store = InMemoryPlayEventStore().apply { appendAll(List(60) { finished("h$it", t0 - 2 * DAY_MS + it * 5 * MINUTE_MS) }) }
        val taste = engine(store)
        val home = rows(store, taste)
        home.score(listOf(shelf("queen", "n1", "n2", "n3", "n4")), emptyList())
        assertEquals(HomeRowsPick(listOf("queen"), emptySet(), scored = true), home.pick)
        taste.lessLikeThis(LessLikeThis.Artist(ArtistKey.of("Queen")))
        home.onControlsChanged()
        assertEquals(HomeRowsPick(emptyList(), emptySet(), scored = true), home.pick)
    }
}
```

- [ ] **Step 2: Run and see it fail.** `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.TurtleHomeRowsTest'` → compilation fails: unresolved `TurtleHomeRows`.

- [ ] **Step 3: Implement.**

  **`start`**
  - Launch on `scope`: `refreshJumpBackIn()` once (it awaits `spokenReady` first, see below).
  - Then collect `events.newEvents`. On each finished event, call `refreshJumpBackIn()`, and also `invalidate()` while `pick?.scored == false`, so a listener who crosses the cold-start bar gets scored rows. `TurtleHomeShelves` refreshes only every 8 plays or 2 h, which is too slow for recents.
  - Launch a second collector: `blockChanges.drop(1).collect { onControlsChanged() }`. In the app this is W9's `UpstreamBlocks.changes` (a `StateFlow`, so `drop(1)` skips its current value), so blocking or unblocking in upstream updates Home live, after the engine already sees it.

  **`refreshJumpBackIn`**
  1. Await `spokenReady()` (W3's `TurtleSpoken.load()` returns at once after its first run). Then fetch `taste.jumpBackIn(JUMP_BACK_IN_FETCH = 40)` and keep the `youtubeId`s.
  2. Resolve each id from `cache.get(id)`. Ids the cache misses go in one batch through `database().dao().getSongsByIds(missing).map { it.toSongItem() }`.
  3. Keep event order, drop `isSpokenWord` (podcast episodes and book chapters, W3's rule) and `isBlocked` songs, and take `JUMP_BACK_IN_SIZE = 20`. The event log and the DAO are never block-filtered by upstream, unlike its own rows.
  4. On a non-cancellation exception, log `TurtleLog.error` and set `emptyList()`. Never leave the state `null` after a failure: `gridReady` waits on it.

  **`invalidate` and `onControlsChanged`**
  - Keep the last `(rows, grid)` input.
  - `invalidate()` clears the stored signature and calls `score` with that input.
  - `onControlsChanged()` is `refreshJumpBackIn()` followed by `invalidate()`.

  **`score`**
  1. Run inside `withContext(Dispatchers.Default)`.
  2. The input signature is `rows.map { it.key to it.items.map(YTItem::id) } to grid.map(YTItem::id)`. Return early when it equals the last one.
  3. Call `taste.selectHomeRows(rows.map { it.toRowCandidate() }, sticky = pick?.takeIf { it.scored }?.rowKeys.orEmpty())` and `taste.matchesTaste(grid.map { it.toRowItem() })`.
  4. Publish `HomeRowsPick(selection.ids, ids of grid items whose match is true, scored = !selection.coldStart)`.
  5. Log one `TurtleLog.info("home rows: …")` line listing each key with `value`, `knownArtistShare` and `dislikedShare` rounded to 2 places. This is the data for tuning the thresholds on device.
  6. On a non-cancellation exception, publish the fallback `HomeRowsPick(rows.take(3).map { it.key }, grid.map { it.id }.toSet(), scored = false)` and log the error. Home never goes blank.

  **`TurtleBridge`**
  - `homeJumpBackIn`: `GlobalContext.getOrNull()?.getOrNull<TurtleHomeRows>()?.jumpBackIn`, or `emptyList()` when Koin or the module is absent (fail open).
  - `homeRowsPick` reads the same way.
  - `scoreHomeRows` catches like `infinityNext`.
  - `turtleAppModule` always includes `turtleModule`, so in practice `TurtleHomeRows` exists whenever Shell's Home does.

- [ ] **Step 4: Run and see it pass.** `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.TurtleHomeRowsTest'` → PASS (3 tests). Then `./gradlew :turtle-bridge:desktopTest` → all bridge tests pass, including `TurtleRuntimeTest`, which boots the real Koin module and so checks the new wiring.

- [ ] **Step 5: Stage for commit** (don't commit). `git add turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleHomeRows.kt turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/TurtleHomeRowsTest.kt`. Suggested message: `feat(turtle-bridge): score Home rows off the UI thread and keep Jump back in`.

### Task 5: The "YouTube Music suggestions on Home" setting

**Files:**
- Create: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellHomeRows.kt`
- Modify: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/TurtleUi.kt`: add `import app.turtleplayer.ui.screens.ShellHomeRows` and, in `install()` (line 25), after `ShellPalettes.restore()`, call `ShellHomeRows.restore()`. Use the import, not an inline fully qualified name (Global Constraints; the line above it predates that rule).
- Modify `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellSettingsTurtle.kt`:
  - imports (lines 43-44): add `import app.turtleplayer.bridge.HomeYouTubeRows`;
  - the `recommendations` page (lines 325-372): add a group after the `"dial"` group.
- Modify: `turtle/ui/src/commonMain/composeResources/values/strings_settings.xml`, appending before `</resources>` (line 68).

**Interfaces:**
- Consumes: `TurtleBridge.uiPref/setUiPref`, Task 3's `HomeYouTubeRows`, `SettingsItemRegistration(control = SettingsControl.CHOICE, choices, selectedChoiceId, onChoiceSelected)` (the same pattern as `spokenSpeed`, lines 387-396).
- Produces:

```kotlin
internal object ShellHomeRows {
    /** Null until restored from our database (then the saved value, or MATCHED). Compose state. */
    var mode: HomeYouTubeRows? by mutableStateOf(null); private set
    fun restore()          // MainScope: mode = HomeYouTubeRows.fromPref(runCatching { TurtleBridge.uiPref(HomeYouTubeRows.PREF_KEY) }.getOrNull())
    fun choose(m: HomeYouTubeRows)   // sets mode, then saves m.prefValue with TurtleBridge.setUiPref
}
```

- [ ] **Step 1: Add the strings** (English only):

```xml
    <string name="shell_set_home_youtube">YouTube Music suggestions on Home</string>
    <string name="shell_set_home_youtube_body">Which of YouTube Music's own rows Home shows, below yours.</string>
    <string name="shell_set_home_youtube_matched">Matched to your taste</string>
    <string name="shell_set_home_youtube_all">All</string>
    <string name="shell_set_home_youtube_off">Off</string>
```

- [ ] **Step 2: Implement** `ShellHomeRows`, following `ShellPalettes.restore/choose` (`ShellPalettes.kt:74-94`), plus the settings group:
  - `group("homeYouTube", "", listOf(SettingsItemRegistration(id = "homeYouTubeRows", title = stringResource(Res.string.shell_set_home_youtube), description = stringResource(Res.string.shell_set_home_youtube_body), control = SettingsControl.CHOICE, valueLabel = <label of current>, choices = HomeYouTubeRows.entries.map { SettingsChoice(it.prefValue, <its label>) }, selectedChoiceId = (ShellHomeRows.mode ?: HomeYouTubeRows.MATCHED).prefValue, onChoiceSelected = { ShellHomeRows.choose(HomeYouTubeRows.fromPref(it)) })))`.
  - Map labels with a `when` over the enum to the three `shell_set_home_youtube_*` strings.

- [ ] **Step 3: Compile.** `./gradlew :turtle-ui:compileKotlinDesktop` → BUILD SUCCESSFUL. This generates `Res.string.shell_set_home_youtube*`.

- [ ] **Step 4: Stage for commit** (don't commit). `git add turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellHomeRows.kt turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/TurtleUi.kt turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellSettingsTurtle.kt turtle/ui/src/commonMain/composeResources/values/strings_settings.xml`. Suggested message: `feat(turtle-ui): setting for YouTube Music rows on Home`.

### Task 6: Restructure Shell Home

**Files:**
- Modify `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellHome.kt`:
  - imports (72-137);
  - `KEEP_LISTENING_KEY` and `SHORTCUT_TILES` (140-143): delete;
  - `HomeLists` (155-186): replace;
  - `ShellHome` body (196-428);
  - `LoadMoreNearEnd` (430-452).

**Interfaces:**
- Consumes:
  - Task 3: `HomeLayout`, `HomeYouTubeRows`;
  - Task 4: `TurtleBridge.homeJumpBackIn`, `homeRowsPick`, `scoreHomeRows`;
  - Task 5: `ShellHomeRows.mode`;
  - from W9 (Task 11): `ShellHomeFeedback.play` / `openMenu`, `ItemRow`'s `place: PlayPlace` parameter, `MixTile` with `MixMenu`, `PlayPlace`, and `TurtleBridge.hiddenSet`.
- Produces: no new public API. It leaves a `// W7: "Top in your country" row goes here` marker right before the YouTube rows.

- [ ] **Step 1: Replace `HomeLists` with a thin wrapper over `HomeLayout`.**
  - Keep `MixEntry` (now built from `layout.mixes`, with the same tint rotation) and `artUrls`: thumbnails of `layout.mixes + layout.moreLike` items plus `ui.quickPicks`, distinct, take 9.
  - Build it with `remember(shelves, ui, jumpBackIn, pick, mode, hidden) { HomeLayout.of(shelves, ui, jumpBackIn, pick, mode, hidden) … }`. W9's `remember(shelves, ui, hidden)` (line 272 at HEAD) would miss bridge and setting changes.
  - `jumpBackIn = TurtleBridge.homeJumpBackIn`, `pick = TurtleBridge.homeRowsPick`, `mode = ShellHomeRows.mode` and W9's `hidden = TurtleBridge.hiddenSet` are all read in composition, so changes recompose. The hidden filter W9 Task 11 put in `HomeLists.of` now lives in `HomeLayout.of` (Task 3); don't filter twice.

- [ ] **Step 2: Trigger scoring.**
  - `val candidates = remember(shelves, ui, mode) { HomeLayout.candidates(shelves, ui, mode ?: HomeYouTubeRows.MATCHED) }`.
  - `val gridCandidates = remember(shelves, ui) { HomeLayout.gridCandidates(shelves, ui) }`.
  - `LaunchedEffect(candidates, gridCandidates, mode) { if (mode == HomeYouTubeRows.MATCHED) TurtleBridge.scoreHomeRows(candidates, gridCandidates) }`.

- [ ] **Step 3: Lay out the `LazyColumn`, top to bottom.** Item keys are unchanged where the item exists today. `current` is the selected filter chip.
  1. `"chips"`, unchanged.
  2. The Podcasts/Audiobooks early return, unchanged.
  3. `"offline"`, with `noContent = !ui.hasHomeFeedContent()`. Base `failed` and `waiting` on upstream state, as today: the grid is now mostly Turtle recents and can't tell whether YouTube loaded.
  4. The grid:
     - while `waiting || !layout.gridReady`, draw `"shortcuts_loading"` (`PlaceholderShortcuts`);
     - otherwise draw `"shortcuts"`: tiles are `listOf(null, LikedSongsTile) + layout.grid`, so Infinity stays first, and song tiles play within `layout.grid.filterIsInstance<SongItem>()` through W9's `ShellHomeFeedback.play(PlayPlace.HOME_GRID, origin, songs) { … }`, where `origin` is the tile's source as `HomeLayout` knows it: `"pin"`, `"recent"` or `"youtube:<row key>"`.
  5. `"made_for_you"` when `layout.mixes` isn't empty, for ALL, MUSIC and MIXES. This removes the `current != MUSIC` check at line 383.
  6. `"infinity"`, which moves below Made for you. While `waiting`, show `"infinity_loading"` here.
  7. `"jump_back_in"` for ALL and MUSIC when `layout.jumpBackIn` isn't empty:
     - render it through `ItemRow(..., place = PlayPlace.HOME_RECENT)` on a `HomeShelf(key = "turtle_jump_back_in", title = …, items = layout.jumpBackIn, style = HomeShelfStyle.CAROUSEL)`, built in `remember`;
     - the title is `stringResource(Res.string.shell_home_jump_back_in)`.
  8. `"more_like:${shelf.key}"` for each `layout.moreLike`, via `ItemRow(..., place = PlayPlace.HOME_TURTLE)`, for ALL, MUSIC and MIXES. (Today every Turtle shelf, More like X included, is a mix card; W1 moves More like X into rows.)
  9. The `// W7` marker, then `"row:${shelf.key}"` for each `layout.youtubeRows`, via `ItemRow(..., place = PlayPlace.HOME_YOUTUBE)`, for ALL and MUSIC.
  10. `"load_more_retry"`, unchanged.

  `riseIn` indexes follow this order.

- [ ] **Step 4: Gate chip feeds.**
  - `LoadMoreNearEnd(slot, enabled = (current == ALL || current == MUSIC) && mode != HomeYouTubeRows.OFF, allowChipFeeds = mode == HomeYouTubeRows.ALL)`.
  - Inside, call `loadMore` only when `(allowChipFeeds || current.continuation != null) && trigger.shouldLoad(current, nearEnd, revision)`. The check comes first because `shouldLoad` records the page.
  - Add `allowChipFeeds` to the `LaunchedEffect` keys.
  - This works because upstream falls back to `loadMoreChipSections()` only when `continuation == null` (`HomeViewModel.kt:803-813`). Shell therefore stops once the main continuation runs out, with no hook.

- [ ] **Step 5: Keep W9's wiring on everything that moved or is new** (W9 Task 11's names):
  - grid song tiles, `onClick` and `onPlay`: `ShellHomeFeedback.play(PlayPlace.HOME_GRID, layout.gridOrigin(item), layout.grid.filterIsInstance<SongItem>()) { … }` (add `fun gridOrigin(item: YTItem): String` to `HomeLayout`, returning `"pin"`, `"recent"` or `"youtube:<row key>"`); `onLongClick = slot.onItemLongClick?.let { f -> { ShellHomeFeedback.openMenu(item, f) } }`;
  - mix cards: W9's `MixTile` unchanged (attributes with `PlayPlace.HOME_TURTLE` and the shelf key, wraps `MixCard` with `MixMenu`);
  - the Jump back in row: `place = PlayPlace.HOME_RECENT` (row key `turtle_jump_back_in`);
  - More like X: `place = PlayPlace.HOME_TURTLE` (row = the `turtle:` shelf key, which `TurtleHomeShelves.originOf` resolves to the item's source and slot);
  - YouTube rows: `place = PlayPlace.HOME_YOUTUBE` (row = the row key);
  - the Infinity tile and card: no Home attribution; Infinity tags its own songs.

  Every grid tile, mix card and Turtle row card (Jump back in and More like X included) keeps W9's long-press "Not interested" entry; `ItemRow` arms it for every place except `HOME_YOUTUBE`.

- [ ] **Step 6: Compile.** `./gradlew :turtle-ui:compileKotlinDesktop :desktopApp:compileKotlinJvm :androidApp:compileDebugKotlin` → BUILD SUCCESSFUL.

- [ ] **Step 7: Stage for commit** (don't commit). `git add turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellHome.kt`. Suggested message: `feat(turtle-ui): personal Home order with taste-matched YouTube rows`.

### Task 7: Verify the whole workstream

**Files:** none new.

- [ ] **Step 1: Engines.** `./gradlew -p turtle/engines check` → BUILD SUCCESSFUL.
  - Compare the `REPLAY …` lines that `OfflineReplayTest` prints with the run before W1 (W2, W3 and W9 applied). recall@50 and nDCG@10 must be within 2%. W1 changes no ranking code, so expect equal values.
- [ ] **Step 2: Bridge.** `./gradlew :turtle-bridge:desktopTest` → all tests pass.
- [ ] **Step 3: Apps.** `./gradlew :desktopApp:compileKotlinJvm :androidApp:compileDebugKotlin` → BUILD SUCCESSFUL.
- [ ] **Step 4: Style.**
  - `./gradlew :turtle-bridge:ktlintCheck :turtle-ui:ktlintCheck`. Both already fail on existing files (about 1,000 violations in the bridge alone), so read the reports under `build/reports/ktlint/`: fix findings only in lines this plan added, and leave pre-existing findings alone.
  - `git diff --check` → no output.
- [ ] **Step 5: Zero hook lines.**
  - `git status --porcelain -- . ':!turtle'` → no output: no upstream file changed and `turtle/HOOKS.md` is unchanged.
  - `bash turtle/scripts/check-hooks.sh origin/main` only sees commits, so run it after the owner commits.
- [ ] **Step 6: Device checks for the owner** (not automated). On the OnePlus, signed in and signed out:
  - Home shows the grid, Made for you, Infinity, Jump back in and More like X before any YouTube row;
  - at most 3 YouTube rows, no unlabeled chip feeds;
  - All brings today's rows back, chip feeds included;
  - Off shows none;
  - `drive.log` and `TurtleLog` "home rows:" lines give the scores for tuning `HomeRowConfig`.
- [ ] **Step 7: Stage.** Review the full `git diff --cached` for unrelated changes and debug code. No commit until the owner approves.
