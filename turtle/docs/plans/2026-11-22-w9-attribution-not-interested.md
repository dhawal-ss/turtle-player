# W9 Play attribution and one "not interested" list Implementation Plan

> **For agentic workers:** Implement task-by-task; steps use checkbox (- [ ]) syntax.

**Goal:** Every play started from Shell Home, Search, Infinity, Smart Shuffle or radio records where it came from, adding a song to a playlist is stored as feedback, and "Not interested" is one undoable list that Turtle honours everywhere, upstream's block list included.

**Architecture:** The taste engine gains a typed play origin (`PlayOrigin`) that `SurfaceTag` formats and parses, keeping every legacy `taste/…` tag and now accepting Infinity's `infinity/…` tags. `PlayerObserver` derives the surface from the tag: Shell sets a whole-queue attribution synchronously before it starts playback, engines keep tagging single songs, and the newer of the two wins until the queue moves on. Non-play feedback (`TrackFeedback`: added to playlist, not interested, undo) gets its own table and profile rules, and upstream's block list reaches the engine through a live seam that is merged into ranking and never saved.

**Tech Stack:** Kotlin Multiplatform (`commonMain` engines, `:turtle-bridge` on Android and desktop), androidx.sqlite (our own `turtle.db`), Room 3 `InvalidationTracker` (read-only, on upstream's `MusicDatabase`), Compose Multiplatform (Shell), kotlin.test with kotlinx-coroutines-test.

**Spec:** /Users/dhawal/Desktop/Creations/turtle/turtle/docs/specs/2026-11-22-personal-home-design.md (section "W9 Play attribution and one 'not interested' list")

**Build order:** W3, then W2, then **W9**, then W1, all in the same checkout. Line numbers below are from `61af6acc`. W3 and W2 shift some of them, so locate code by the symbol named next to each range.

## Global Constraints

- Engines (`turtle/engines/*`) stay pure `commonMain`: no `java.*`, no Android, and no upstream (`com.metrolist.*`) imports. Only `:turtle-bridge` touches upstream.
- Inject time: engine code takes `Clock`, and new bridge classes take `nowMs: () -> Long`. No hidden `Random`.
- No upstream file changes. Wave 1 plans 0 hook lines, so `turtle/HOOKS.md` is untouched and `bash turtle/scripts/check-hooks.sh origin/main` must stay green. Upstream data is only read: `BlockedContent`, `MusicDatabase` through `invalidationTracker` and `useReaderConnection`, and `isHistoryPlaylistId`.
- **Turtle never writes to YouTube's dislikes.** No new code calls `SongLikeState.dislike` or `undislike`. Task 13 removes the one existing call in Turtle code.
- **Strings.**
  - User-facing text goes only in Turtle's own compose resources (`turtle/ui/src/commonMain/composeResources/values/strings_home.xml`, `strings_settings.xml`), in English only.
  - No English literals in Kotlin.
  - Never edit any `values-*/strings.xml`.
  - Engines return typed values, never text.
- **Privacy.** Attribution, feedback and hidden lists stay in `turtle.db` on the device. Nothing new leaves the device.
- **Formatting.**
  - `:turtle-bridge` and `:turtle-ui` have ktlint. Run `./gradlew :turtle-bridge:ktlintCheck :turtle-ui:ktlintCheck` and fix violations only in lines you add.
  - Never run a module-wide `ktlintFormat`: it rewrites files W1 also edits.
  - Engines have no ktlint task, so match the surrounding style there.
- **Commits.**
  - Conventional Commits with `turtle-*` scopes (`feat(turtle-taste)`, `feat(turtle-events)`, `feat(turtle-bridge)`, `feat(turtle-ui)`).
  - **Nothing is committed in this plan.** Each task ends by staging; the owner approves commits.
- **Tests.**
  - Engines get unit tests through public APIs.
  - The bridge gets desktop tests (`:turtle-bridge:desktopTest`).
  - Shell composables get no tests. Their check is a compile.
  - The recommendation offline replay must not regress by more than 2% (`OfflineReplayTest`, Task 5).
- W3's paused-history gate applies to plays only. `TrackFeedback` events are explicit actions and are recorded while history is paused (W3 plan, "Notes for later workstreams").

## Review Focus

Five failure modes the spec-driven tests would not catch; each now has a test in its owning task.

1. **Stale attribution:** a song tagged when Home started a row is later played from the Library and still reads as a Home play. Test: Task 7, `queueAttributionEndsWhenTheQueueMovesOn`.
2. **Tap-to-play race:** Shell sets attribution after upstream already announced the song, so the tapped song is unattributed. Test: Task 7, `attributionSetJustBeforePlaybackTagsTheTappedSong` (`attribute()` is not suspending, so Shell calls it on the UI thread with no hop).
3. **Detail text Turtle didn't write:** Spotify URIs, upstream's free-form `queueSourceTitle` ("My mix / 2024"), row keys with `/`, `:`, `#` or a newline, and stored fixture tags such as `taste/session/discovery`, any of which could parse into a wrong generator. Test: Task 1, `foreignDetailIsNotATag`, `homeTagsRoundTripAwkwardRowKeys`.
4. **Bulk membership changes read as adds:** the first snapshot after launch, YouTube syncs (delete and reinsert in one transaction), playlist imports (250 per transaction locally, one song per transaction with network calls between for remote ones), history playlists, reorders and re-adds. Test: Task 8, `firstSnapshotIsOnlyTheBaseline`, `importsAndSyncsAreNotAdds`, `systemPlaylistsNeverCount`, `reordersRemovalsAndReaddsAreNotAdds`.
5. **"Not interested" on the wrong item:** a menu opened later elsewhere (desktop queue panel, Now Playing) offers to hide a stale Home item. Test: Task 10, `onlyTheMenuOpenedForTheItemGetsIt`.

---

### Task 1: Play origins and tags that parse (engine)

**Files:**
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/Candidates.kt`. Replace lines 33-51 (`SurfaceTag`) and add `PlayPlace` and `PlayOrigin` above it.
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt` line 173 (`onPlayEvent`): `SurfaceTag.parse(event.surfaceDetail)?.slot ?: queuedSlots[event.trackKey]`.
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteProfile.kt` lines 391-393 (`TasteProfileBuilder.add`): `SurfaceTag.parse(e.surfaceDetail)?.source?.let { source -> … }`, with the body unchanged.
- Create: `turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/PlayOriginTest.kt`
- Test (modify): `turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/TasteEngineTest.kt`. Add one test after `discoveryOutcomesMoveTheExplorationPosterior` (line 257).

**Interfaces:**
- Consumes: `Surface` (`turtle-events`, `PlayEvent.kt:8`), `CandidateSource`, `SlotKind`.
- Produces:
  ```kotlin
  /** Where in the app a play started. [tag] is persisted in PlayEvent.surfaceDetail: never rename. */
  enum class PlayPlace(val tag: String, val surface: Surface) {
      ENGINE("taste", Surface.AUTOPLAY),          // Up Next and every legacy taste/<source>/<slot> tag
      INFINITY("infinity", Surface.AUTOPLAY),
      SMART_SHUFFLE("shuffle", Surface.PLAYLIST),
      RADIO("radio", Surface.RADIO),
      SEARCH("search", Surface.SEARCH),
      HOME_TURTLE("home-turtle", Surface.SHELF),  // Turtle's mixes and rows; row = shelf id
      HOME_GRID("home-grid", Surface.SHELF),      // the 2×4 shortcut grid
      HOME_RECENT("home-recent", Surface.SHELF),  // Jump back in; row = its row key
      HOME_YOUTUBE("home-yt", Surface.SHELF),     // one of YouTube's rows; row = its row key
      ;
      companion object { fun fromTag(tag: String): PlayPlace? }
  }

  /** Where a play came from: the place, the generator and slot that proposed the track (when one did), and the row. */
  data class PlayOrigin(val place: PlayPlace, val source: CandidateSource? = null, val slot: SlotKind? = null, val row: String? = null) {
      val surface: Surface get() = place.surface
  }

  object SurfaceTag {
      fun format(source: CandidateSource, slot: SlotKind): String  // unchanged: "taste/<source>/<slot>"
      fun format(origin: PlayOrigin): String
      fun parse(detail: String?): PlayOrigin?                       // was Pair<CandidateSource, SlotKind>?
  }
  ```
- **Wire format** (pinned):
  - `<place>/<source|->/<slot|->`, followed by `/<row>` only when the row is non-empty. Each `\n`, `\r` and `\t` in the row becomes a space.
  - `parse` splits on `/` with `limit = 4`, so a row keeps its own slashes.
  - A 3- or 4-part string with a known place parses. Its source and slot are `-` or a known tag; anything else returns null.
  - **Legacy Infinity (2 parts):**

    | Tag | Parses to |
    | --- | --- |
    | `infinity/familiar` | `PlayOrigin(INFINITY, LIBRARY, FAMILIAR, "familiar")` |
    | `infinity/genre:<id>` | `PlayOrigin(INFINITY, UPSTREAM_RADIO, DISCOVERY, "genre:<id>")` |
    | `infinity/morelike` | `PlayOrigin(INFINITY, UPSTREAM_RADIO, DISCOVERY, "morelike")` |
    | `infinity/foryou` | `PlayOrigin(INFINITY, null, null, "foryou")`, because which generator it came from was never stored |

  - Any other shape returns null.
- **Decision.** Infinity and Home shelf plays now move the exploration posterior and the per-source skip stats, the same as Up Next plays. This is deliberate: it is the attribution the spec asks for. `Surface` is read by no ranking code (checked with grep), so the new surfaces change no metric.

- [ ] Before changing anything, record the replay baseline: `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.OfflineReplayTest' --rerun -i | grep REPLAY`. Keep the 6 lines for Task 5.
- [ ] Write the failing tests. `PlayOriginTest.kt`:
  ```kotlin
  class PlayOriginTest {
      @Test fun legacyEngineTagsParseExactlyAsBefore() {
          assertEquals("taste/cf/d", SurfaceTag.format(CandidateSource.COLLABORATIVE, SlotKind.DISCOVERY))
          assertEquals(PlayOrigin(PlayPlace.ENGINE, CandidateSource.COLLABORATIVE, SlotKind.DISCOVERY), SurfaceTag.parse("taste/cf/d"))
          assertEquals("taste/session/f", SurfaceTag.format(PlayOrigin(PlayPlace.ENGINE, CandidateSource.SESSION, SlotKind.FAMILIAR)))
      }
      @Test fun homeTagsRoundTripAwkwardRowKeys() {
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
      @Test fun legacyInfinityTagsParse() {
          assertEquals(PlayOrigin(PlayPlace.INFINITY, CandidateSource.LIBRARY, SlotKind.FAMILIAR, "familiar"), SurfaceTag.parse("infinity/familiar"))
          assertEquals(PlayOrigin(PlayPlace.INFINITY, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, "genre:pop"), SurfaceTag.parse("infinity/genre:pop"))
          assertEquals(PlayOrigin(PlayPlace.INFINITY, CandidateSource.UPSTREAM_RADIO, SlotKind.DISCOVERY, "morelike"), SurfaceTag.parse("infinity/morelike"))
          assertEquals(PlayOrigin(PlayPlace.INFINITY, row = "foryou"), SurfaceTag.parse("infinity/foryou"))
      }
      @Test fun foreignDetailIsNotATag() {
          for (d in listOf(null, "", "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "Liked Songs", "My mix / 2024",
                  "taste/session/discovery", "radio/bogus/d", "infinity", "home-yt/-", "nowhere/-/-")) {
              assertNull(SurfaceTag.parse(d), "$d")
          }
      }
      @Test fun eachPlaceRecordsItsSurface() {
          assertEquals(Surface.SHELF, SurfaceTag.parse("home-grid/-/-")!!.surface)
          assertEquals(Surface.SEARCH, SurfaceTag.parse("search/-/-")!!.surface)
          assertEquals(Surface.RADIO, SurfaceTag.parse("radio/upstream/d")!!.surface)
          assertEquals(Surface.AUTOPLAY, SurfaceTag.parse("infinity/familiar")!!.surface)
          assertEquals(Surface.PLAYLIST, SurfaceTag.parse("shuffle/-/-")!!.surface)
      }
  }
  ```
  In `TasteEngineTest`:
  ```kotlin
  @Test
  fun homeShelfTagsAttributeDiscoveryLikeEngineTags() = runTest {
      val track = world.tracks.last()
      val viaEngine = engineWithHistory()
      val viaHome = engineWithHistory()
      viaEngine.onPlayEvent(play(track, clock.now, EndReason.SKIPPED, msPlayed = 3_000, surfaceDetail = "taste/cf/d"))
      viaHome.onPlayEvent(play(track, clock.now, EndReason.SKIPPED, msPlayed = 3_000, surfaceDetail = "home-turtle/cf/d/weekly_discovery:weekly"))
      assertTrue(viaHome.explorationState != ExplorationState())
      assertEquals(viaEngine.explorationState, viaHome.explorationState)
  }
  ```
- [ ] Run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.PlayOriginTest' --tests 'app.turtleplayer.taste.TasteEngineTest'`. Expected: compilation fails with `Unresolved reference 'PlayOrigin'`.
- [ ] Implement `PlayPlace`, `PlayOrigin` and the new `SurfaceTag` in `Candidates.kt` to the wire format above, then update the two callers. The existing `format(source, slot)` delegates to `format(PlayOrigin(PlayPlace.ENGINE, source, slot))`.
- [ ] Run the same command. Expected: PASS. Then `./gradlew -p turtle/engines :turtle-taste:jvmTest`. Expected: PASS, including `SmartShuffleTest`, `QueueAssemblerTest` and `TasteProfileTest`, which use `format`.
- [ ] Stage for commit (no commit): `Candidates.kt`, `TasteEngine.kt`, `TasteProfile.kt`, `PlayOriginTest.kt`, `TasteEngineTest.kt`.

### Task 2: Non-play feedback events (events engine)

**Files:**
- Create: `turtle/engines/events/src/commonMain/kotlin/app/turtleplayer/events/TrackFeedback.kt`
- Modify: `turtle/engines/events/src/commonMain/kotlin/app/turtleplayer/events/PlayEventStore.kt`. Add two members to the interface (lines 16-35) and implement them in `InMemoryPlayEventStore` (lines 37-87) with its own list, sorted by `atMs`, under the same mutex.
- Test (modify): `turtle/engines/events/src/commonTest/kotlin/app/turtleplayer/events/EventsTest.kt`. Add a new `class TrackFeedbackStoreTest` at the end, reusing `track()`.

**Interfaces:**
- Produces:
  ```kotlin
  /** Something the listener did to a track outside a play. Stored by name: never rename. */
  enum class TrackFeedbackKind { ADDED_TO_PLAYLIST, NOT_INTERESTED, UNDO_NOT_INTERESTED }

  /** One non-play feedback event, kept beside the play log. [detail]: the playlist id for ADDED_TO_PLAYLIST. */
  data class TrackFeedback(
      val id: Long = 0,
      val track: TrackMeta,
      val kind: TrackFeedbackKind,
      val atMs: Long,
      val detail: String? = null,
      val source: EventSource = EventSource.LOCAL,
  )

  // PlayEventStore, new members (abstract; both implementations updated):
  suspend fun appendFeedback(feedback: TrackFeedback): Long
  /** Feedback with `atMs >= sinceMs`, oldest first. */
  suspend fun feedbackSince(sinceMs: Long, limit: Int = Int.MAX_VALUE): List<TrackFeedback>
  ```
- **Decision.** Feedback is not emitted on `newEvents`. Plays and feedback have separate consumers, and the engine is fed explicitly in Task 3. That also keeps W3's paused-history gate, which sits on plays only, from touching feedback.

- [ ] Write the failing test:
  ```kotlin
  class TrackFeedbackStoreTest {
      @Test
      fun feedbackIsKeptApartFromPlaysOldestFirst() = runTest {
          val store = InMemoryPlayEventStore()
          store.appendFeedback(TrackFeedback(track = track("b"), kind = TrackFeedbackKind.NOT_INTERESTED, atMs = 2_000))
          val id = store.appendFeedback(TrackFeedback(track = track("a"), kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = 1_000, detail = "PL1"))
          assertTrue(id > 0)
          assertEquals(listOf("yt:a", "yt:b"), store.feedbackSince(0).map { it.track.key.value })
          assertEquals("PL1", store.feedbackSince(0).first().detail)
          assertEquals(listOf("yt:b"), store.feedbackSince(1_500).map { it.track.key.value })
          assertEquals(0L, store.count())
      }
  }
  ```
- [ ] Run `./gradlew -p turtle/engines :turtle-events:jvmTest --tests 'app.turtleplayer.events.TrackFeedbackStoreTest'`. Expected: compilation fails with `Unresolved reference 'TrackFeedback'`.
- [ ] Implement the file and the two members. The bridge does not compile until Task 6 implements them in `SqlitePlayEventStore`, so build the bridge only from Task 6 onward.
- [ ] Run the same command, then `./gradlew -p turtle/engines check`. Expected: PASS.
- [ ] Stage for commit (no commit): `TrackFeedback.kt`, `PlayEventStore.kt`, `EventsTest.kt`.

### Task 3: Feedback shapes taste, and "not interested" can be undone (taste engine)

**Files:**
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteProfile.kt`:
  - `TrackAcc` (lines 303-317): replace `var lessLikeThis = false` with `val lessMarks = ArrayList<Long>()` and `var undoneAt = Long.MIN_VALUE`;
  - `add` (lines 360-397): line 374 becomes `if (e.lessLikeThis) t.lessMarks += e.startedAtMs`;
  - add `addFeedback` after `addSaved` (lines 346-358);
  - `snapshot` (lines 412-447): derive the `lessLikeThis` flag and the undo compensation.
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/UserControls.kt`. Add `Hidden`, `hidden()` and `undo()` after `apply` (lines 81-90).
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt`:
  - add `onFeedback` after `onPlayEvent` (lines 168-181);
  - `refreshLocked` (lines 531-543) also adds `events.feedbackSince(now - config.historyDays * DAY_MS)` after the saved tracks.
- Test (modify): `TasteProfileTest.kt` (two tests) and `TasteEngineTest.kt` (three tests, after `tasteStateRoundTripsEveryControl`, line 363).

**Interfaces:**
- Consumes: `TrackFeedback`, `TrackFeedbackKind` and `PlayEventStore.feedbackSince` (Task 2).
- Produces:
  ```kotlin
  // TasteProfileBuilder
  /** A non-play feedback event. Order-independent with plays: marks and undos compare by time. */
  fun addFeedback(f: TrackFeedback)

  // UserControls.kt
  /** One thing the listener ruled out, as the engine keeps it. */
  sealed interface Hidden {
      data class Track(val track: TrackKey, val artist: ArtistKey?) : Hidden   // artist null: unknown, no mark count to take back
      data class Artist(val artist: ArtistKey) : Hidden                        // a muted artist
      data class Shelf(val reasonKey: String) : Hidden                         // a dismissed shelf or mix ([Reason.key])
  }
  /** What is hidden: tracks (artist null), then muted artists, then dismissed shelves, each in insertion order. */
  fun UserControls.hidden(): List<Hidden>
  /** Takes one back: the track leaves lessLikeThisTracks and its artist's mark count drops by one (removed at 0); an artist is unmuted; a shelf reason is no longer suppressed. */
  fun UserControls.undo(target: Hidden): UserControls

  // TasteEngine
  /**
   * Applies a non-play feedback event the app has already stored: ADDED_TO_PLAYLIST raises affinity;
   * NOT_INTERESTED rules the track out (as LessLikeThis.Track) and drops it from Up Next;
   * UNDO_NOT_INTERESTED brings it back (controls and profile). Not collected by [observe].
   */
  suspend fun onFeedback(f: TrackFeedback)
  ```
- **Profile rules** (pinned):

  | Kind | Effect |
  | --- | --- |
  | `ADDED_TO_PLAYLIST` | Adds `weights.addedToPlaylist` (1.5) at `f.atMs` to the track and its artist. Sets `savedAt` when it is null, so the song counts as known (`hasHeard`) and Weekly Discovery won't offer it. Does not count as a play. |
  | `NOT_INTERESTED` | Appends `f.atMs` to `lessMarks`, and adds `weights.lessLikeThis` (-4.0) at `f.atMs` to the track and its artist, as a play-level mark does. |
  | `UNDO_NOT_INTERESTED` | Sets `undoneAt = max(undoneAt, f.atMs)`. |

  - In `snapshot`, `TrackStats.lessLikeThis` is `lessMarks.any { it > undoneAt }`.
  - Every mark at or before `undoneAt` is compensated: `-weights.lessLikeThis * weights.decay(nowMs - mark)` is added to the track's affinity and to its artist's affinity. Undo therefore restores exactly what the marks took, and later marks hide the track again.
  - **Why this rule:** a hide made from Now Playing is stored on the play event (`PlayEvent.lessLikeThis`) forever. Removing a key from `UserControls` alone would never unhide it (`usable()`, `HistoryFallback`, `SmartShuffle.blocked` all read the play-level flag).
- **Decision.** The open play is never also marked `addedToPlaylist`: an add counts once, through `TrackFeedback`. `AffinityWeights.signal` is unchanged.
- **`onFeedback` guard.** It calls `builder.addFeedback(f)` only if the engine was already loaded before the call (`val wasLoaded = loaded; ensureLoaded(); if (wasLoaded) builder.addFeedback(f)`). Otherwise the stored event would count twice: once from the refresh, once live.
  - `NOT_INTERESTED` also does `own = own.apply(LessLikeThis.Track(f.track.key, f.track.artistKey))` and drops the track from `queue`.
  - `UNDO_NOT_INTERESTED` does `own = own.undo(Hidden.Track(f.track.key, f.track.artistKey))`.
  - Then `updateProfileLocked()`, and `rerankLocked()` when `autoplayActive` and the kind isn't `ADDED_TO_PLAYLIST`. (`own` is introduced in Task 4. Until then, write to `controls`.)

- [ ] Write the failing tests. `TasteProfileTest`:
  ```kotlin
  @Test fun playlistAddCountsOnceAsAStrongPositive() {
      val t = world.tracks[0]
      val at = now - 10 * DAY_MS
      val p = TasteProfileBuilder().apply { addFeedback(TrackFeedback(track = t, kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = at)) }.snapshot(now, rep)
      val w = AffinityWeights()
      assertEquals(w.addedToPlaylist * w.decay(now - at), p.track(t.key)!!.affinity, 1e-9)
      assertEquals(0, p.track(t.key)!!.plays)
      assertTrue(p.hasHeard(t))
  }
  @Test fun undoTakesBackEveryEarlierNotInterestedMark() {
      val a = world.tracks[0]
      val skip = play(a, now - 3 * DAY_MS, EndReason.SKIPPED, msPlayed = 5_000)
      val plain = TasteProfileBuilder().apply { add(skip) }.snapshot(now, rep)
      fun build(feedbackFirst: Boolean) = TasteProfileBuilder().apply {
          val fb = listOf(
              TrackFeedback(track = a, kind = TrackFeedbackKind.NOT_INTERESTED, atMs = now - 2 * DAY_MS),
              TrackFeedback(track = a, kind = TrackFeedbackKind.UNDO_NOT_INTERESTED, atMs = now - DAY_MS),
          )
          if (feedbackFirst) fb.forEach(::addFeedback)
          add(skip.copy(lessLikeThis = true))   // hidden from Now Playing while it played
          if (!feedbackFirst) fb.forEach(::addFeedback)
      }.snapshot(now, rep)
      for (undone in listOf(build(false), build(true))) {
          assertFalse(undone.track(a.key)!!.lessLikeThis)
          assertEquals(plain.track(a.key)!!.affinity, undone.track(a.key)!!.affinity, 1e-9)
          assertEquals(plain.artist(a.artistKey)!!.affinity, undone.artist(a.artistKey)!!.affinity, 1e-9)
      }
      val hiddenAgain = TasteProfileBuilder().apply {
          add(skip.copy(lessLikeThis = true))
          addFeedback(TrackFeedback(track = a, kind = TrackFeedbackKind.UNDO_NOT_INTERESTED, atMs = now - DAY_MS))
          addFeedback(TrackFeedback(track = a, kind = TrackFeedbackKind.NOT_INTERESTED, atMs = now - HOUR_MS))
      }.snapshot(now, rep)
      assertTrue(hiddenAgain.track(a.key)!!.lessLikeThis)
  }
  ```
  `TasteEngineTest`:
  ```kotlin
  @Test fun undoRestoresWhatWasRuledOut() {
      val a = ArtistKey("a"); val t = TrackKey.youtube("t"); val m = ArtistKey("m")
      val c = UserControls().apply(LessLikeThis.Track(t, a)).apply(LessLikeThis.Track(t, a))
          .copy(mutedArtists = setOf(m), suppressedReasons = setOf("mix:x"))
      assertEquals(listOf(Hidden.Track(t, null), Hidden.Artist(m), Hidden.Shelf("mix:x")), c.hidden())
      val u = c.undo(Hidden.Track(t, a)).undo(Hidden.Artist(m)).undo(Hidden.Shelf("mix:x"))
      assertEquals(UserControls(lessLikeThisArtists = mapOf(a to 1)), u)
      // The mark count never goes below zero.
      assertEquals(emptyMap(), c.undo(Hidden.Track(t, a)).undo(Hidden.Track(t, a)).undo(Hidden.Track(t, a)).lessLikeThisArtists)
  }
  @Test fun notInterestedFeedbackRulesOutAndUndoBringsBack() = runTest {
      val engine = engineWithHistory()
      val target = engine.startAutoplay(user.heavyRotation[0])[2].track
      engine.onFeedback(TrackFeedback(track = target, kind = TrackFeedbackKind.NOT_INTERESTED, atMs = clock.now))
      assertTrue(target.key in engine.controls.lessLikeThisTracks)
      assertTrue(engine.upNext().none { it.key == target.key })
      assertTrue(engine.profile().track(target.key)!!.lessLikeThis)
      clock.now += MINUTE_MS
      engine.onFeedback(TrackFeedback(track = target, kind = TrackFeedbackKind.UNDO_NOT_INTERESTED, atMs = clock.now))
      assertFalse(target.key in engine.controls.lessLikeThisTracks)
      assertFalse(engine.profile().track(target.key)!!.lessLikeThis)
  }
  @Test fun storedFeedbackShapesTheProfileAfterARestartAndCountsOnce() = runTest {
      val store = InMemoryPlayEventStore().also { it.appendAll(history) }
      val fresh = world.tracks.first { t -> history.none { it.trackKey == t.key } }
      val w = AffinityWeights()
      store.appendFeedback(TrackFeedback(track = fresh, kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = clock.now - DAY_MS))
      assertEquals(w.addedToPlaylist * w.decay(DAY_MS), world.engine(store, clock).profile().track(fresh.key)!!.affinity, 1e-9)
      // The app stores an add, then hands it to an engine that has not loaded yet: it must count once, not twice.
      val live = TrackFeedback(track = fresh, kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = clock.now)
      store.appendFeedback(live)
      val engine = world.engine(store, clock)
      engine.onFeedback(live)
      assertEquals(w.addedToPlaylist * (w.decay(DAY_MS) + 1.0), engine.profile().track(fresh.key)!!.affinity, 1e-9)
  }
  ```
- [ ] Run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.TasteProfileTest' --tests 'app.turtleplayer.taste.TasteEngineTest'`. Expected: compilation fails with `Unresolved reference 'addFeedback'` / `'Hidden'` / `'onFeedback'`.
- [ ] Implement as pinned above.
- [ ] Run the same command, then `./gradlew -p turtle/engines :turtle-taste:jvmTest`. Expected: PASS.
- [ ] Stage for commit (no commit): `TasteProfile.kt`, `UserControls.kt`, `TasteEngine.kt`, `TasteProfileTest.kt`, `TasteEngineTest.kt`.

### Task 4: Upstream's block list, read live (taste engine seam)

**Files:**
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/Seams.kt`. Add after `RecommendationSource` (line 38).
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt`:
  - constructor (lines 68-89): add `blocks` as the last parameter;
  - `controls` (lines 129-130);
  - every write to `controls`: lines 172, 306, 316, 504;
  - `exportState` (line 499).
- Modify (test helper): `turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/SyntheticWorld.kt`. In `fun engine(...)` (about line 145), add `blocks: ExternalBlocks? = null` and pass it on.
- Test (modify): `TasteEngineTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  // Seams.kt
  /** Songs and artists the app's own block list rules out. */
  data class BlockedSet(val tracks: Set<TrackKey> = emptySet(), val artists: Set<ArtistKey> = emptySet()) {
      companion object { val NONE = BlockedSet() }
  }
  /** The app's block list, read live and never copied into [UserControls]: unblocking there takes effect here. Return the same instance while unchanged. */
  fun interface ExternalBlocks { fun current(): BlockedSet }
  /** [this] with [blocks] merged in (tracks into lessLikeThisTracks, artists into mutedArtists); [this] itself when [blocks] is empty. */
  fun UserControls.withBlocks(blocks: BlockedSet): UserControls

  // TasteEngine
  class TasteEngine(..., private val contextAt: ((Long) -> PlayContext)? = null, private val blocks: ExternalBlocks? = null)
  /** What ranking uses: the listener's controls plus the app's block list. */
  val controls: UserControls            // was `var … private set`; now a getter
  /** Only what the listener set in Turtle: what [exportState] saves and the Hidden page lists. */
  val listenerControls: UserControls
  ```
- **Rules:**
  - The private field `own` holds the listener's controls. Every mutation goes to `own`: `onPlayEvent`, `lessLikeThis`, `updateControls`, `importState` and `onFeedback`. `updateControls(transform)` applies `transform(own)`.
  - `exportState()` encodes `own`, so the block list is never saved. W2 has already extended it to `TasteState(controls, explorationState, recent.toList(), <kept shelves>)`; replace only `controls` with `own` there and keep W2's kept shelves.
  - The `controls` getter caches the merge: it recomputes only when `own` or `blocks.current()` changed identity (`!==`). `usable()` and `topArtistSeedsLocked` read it in loops.
  - `own` is `@Volatile`, and the cache is one `@Volatile` immutable holder (`own` snapshot, blocks snapshot, merged result) replaced as a whole, never three separate fields. W1's `TasteEngine.jumpBackIn` reads `controls` outside the engine mutex (deliberately, so it never waits on a profile refresh), so the getter must be safe to call from any thread: at worst two threads compute the same merge. The engines are `commonMain`: use `kotlin.concurrent.Volatile` (as `PlayerObserver.kt:23` does in the bridge), never `kotlin.jvm.Volatile`.
  - Everything that already reads `controls` now honours blocks automatically: `rankExternal`, `keptLocked`, the generators, `SmartShuffleBridge` (which reads `taste.controls`), and W1's row scorer.

- [ ] Write the failing test (`TasteEngineTest`):
  ```kotlin
  @Test fun appBlocksApplyLiveAndAreNeverSaved() = runTest {
      var blocked = BlockedSet.NONE
      val store = InMemoryPlayEventStore().also { it.appendAll(history) }
      val engine = world.engine(store, clock, blocks = ExternalBlocks { blocked })
      val seed = user.heavyRotation[0]
      val tracks = engine.startAutoplay(seed).map { it.track }
      val artist = tracks.first().artistKey
      val song = tracks.last { it.artistKey != artist }
      blocked = BlockedSet(tracks = setOf(song.key), artists = setOf(artist))
      val ranked = engine.rankExternal(seed, tracks)
      assertTrue(ranked.none { it.track.artistKey == artist || it.key == song.key })
      assertTrue(artist in engine.controls.mutedArtists)
      assertFalse(artist in engine.listenerControls.mutedArtists)
      val saved = TasteState.decode(engine.exportState())!!
      assertFalse(artist in saved.controls.mutedArtists || song.key in saved.controls.lessLikeThisTracks)
      blocked = BlockedSet.NONE   // unblocked upstream
      val again = engine.rankExternal(seed, tracks)
      assertTrue(again.any { it.track.artistKey == artist } && again.any { it.key == song.key })
  }
  ```
- [ ] Run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.TasteEngineTest'`. Expected: compilation fails with `Unresolved reference 'BlockedSet'`.
- [ ] Implement as above.
- [ ] Run the same command, then `./gradlew -p turtle/engines check`. Expected: PASS. `lessLikeThisSurvivesARestartAndKeepsTheTrackOutOfRadio` and `stateRoundTripsThroughPlainData` must still pass unchanged.
- [ ] Stage for commit (no commit): `Seams.kt`, `TasteEngine.kt`, `SyntheticWorld.kt`, `TasteEngineTest.kt`.

### Task 5: "For you" picks without resetting the live session (taste engine), and the replay check

**Files:**
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt`. Add `forYou` after `upNext` (lines 284-291).
- Test (modify): `TasteEngineTest.kt`.

**Interfaces:**
- Produces:
  ```kotlin
  /**
   * Long-term-taste picks laid out with the queue rules, for callers that play them themselves (the
   * Infinity Mix). Read-only: the session, Up Next, the radio seed and the recently-recommended
   * memory are untouched, so calling it while music plays changes nothing.
   */
  suspend fun forYou(limit: Int = config.upNextSize): List<QueueItem>
  ```
- **Body:**
  - `GenerationRequest(profile, controls, now, sessionVector = profile.longTermVector)`;
  - `rankLocked(autoplayCollector, request)`;
  - `assembler.assemble(emptyList(), ranked, policy.planSlots(limit, policy.sampleShare(explorationState, controls.discoveryDial)), profile.recentPlays, null, recent, now)`.

  `recent` is passed for reading only. Nothing is added to `recent` or `queuedSlots`.

- [ ] Write the failing test:
  ```kotlin
  @Test fun forYouLeavesTheLiveSessionAlone() = runTest {
      val engine = engineWithHistory()
      val upNext = engine.startAutoplay(user.heavyRotation[0])
      val recent = engine.recentlyRecommended()
      val picks = engine.forYou(12)
      assertEquals(12, picks.size)
      assertQueueRules(picks)
      assertEquals(upNext, engine.upNext())
      assertEquals(recent, engine.recentlyRecommended())
  }
  ```
- [ ] Run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.TasteEngineTest'`. Expected: compilation fails with `Unresolved reference 'forYou'`.
- [ ] Implement.
- [ ] Run the same command. Expected: PASS.
- [ ] Offline replay: run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.OfflineReplayTest' --rerun -i | grep REPLAY` and compare against the Task 1 baseline. Expected: the engine's recall and nDCG lines are within 2% of the baseline (they should be identical: no replay event carries a feedback event, a block, or a new tag). A regression beyond 2% blocks the PR.
- [ ] Run `./gradlew -p turtle/engines check`. Expected: PASS.
- [ ] Stage for commit (no commit): `TasteEngine.kt`, `TasteEngineTest.kt`.

### Task 6: The feedback table in `turtle.db` (bridge)

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/SqlitePlayEventStore.kt`:
  - `migrate()` (lines 45-72): a new step after W2's `version < 5`;
  - implement `appendFeedback` and `feedbackSince` beside `append` and `since` (lines 193-218).
- Test (modify): `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/SqlitePlayEventStoreTest.kt`.

**Interfaces:**
- Consumes (from W2): `user_version` 5 is W2's `shelf_song` table. If W2 landed without a migration, renumber this step to 5 and say so in the PR.
- Produces: `PlayEventStore.appendFeedback` / `feedbackSince` on `SqlitePlayEventStore`.
- Schema (pinned):
  ```sql
  -- if (version < 6)
  CREATE TABLE IF NOT EXISTS track_feedback (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      kind TEXT NOT NULL,
      at_ms INTEGER NOT NULL,
      track_key TEXT NOT NULL, title TEXT NOT NULL, artists TEXT NOT NULL, artist_key TEXT NOT NULL,
      album TEXT, track_duration_ms INTEGER, youtube_id TEXT, explicit INTEGER NOT NULL,
      detail TEXT,
      source TEXT NOT NULL
  );
  CREATE INDEX IF NOT EXISTS idx_track_feedback_at ON track_feedback(at_ms);
  PRAGMA user_version = 6;
  ```
  - Artists join with the existing `ARTIST_SEPARATOR`.
  - An unknown `kind` read back skips the row; never fall back to a default kind.
  - Unknown `source` → `EventSource.LOCAL` (`enumOr`).

- [ ] Write the failing test:
  ```kotlin
  @Test
  fun feedbackSurvivesAReopenAndAnUpgradeFromVersionFive() = runTest {
      val file = File.createTempFile("turtle", ".db").apply { delete(); deleteOnExit() }
      SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath).apply { append(event("old", 500)); close() }
      JdbcSQLiteDriver().open(file.absolutePath).apply {
          execSQL("DROP TABLE track_feedback")
          execSQL("PRAGMA user_version = 5")
          close()
      }
      val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
      val added = TrackFeedback(
          track = TrackMeta(TrackKey.youtube("a"), "Title a", listOf("Artist A", "Artist B"), album = "Album", durationMs = 200_000),
          kind = TrackFeedbackKind.ADDED_TO_PLAYLIST, atMs = 1_000, detail = "PLx",
      )
      store.appendFeedback(added)
      store.appendFeedback(added.copy(kind = TrackFeedbackKind.NOT_INTERESTED, atMs = 2_000, detail = null))
      store.close()

      val reopened = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
      val all = reopened.feedbackSince(0)
      assertEquals(added.copy(id = all[0].id), all[0])
      assertEquals(listOf(TrackFeedbackKind.ADDED_TO_PLAYLIST, TrackFeedbackKind.NOT_INTERESTED), all.map { it.kind })
      assertEquals(1, reopened.feedbackSince(1_500).size)
      assertEquals(1L, reopened.count())
      reopened.close()
  }
  ```
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.SqlitePlayEventStoreTest'`. Expected: compilation fails, because `SqlitePlayEventStore` does not implement the abstract `appendFeedback`.
- [ ] Implement.
- [ ] Run the same command. Expected: PASS. Then `./gradlew :turtle-bridge:desktopTest`. Expected: PASS.
- [ ] Stage for commit (no commit): `SqlitePlayEventStore.kt`, `SqlitePlayEventStoreTest.kt`.

### Task 7: Plays record their surface (bridge: observer, Home shelves, radio, Smart Shuffle, Infinity)

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/PlayerObserver.kt`:
  - fields (lines 55-56);
  - `tag` (lines 126-131);
  - new `attribute`;
  - `start()` (lines 178-196, which W3 leaves untouched).

  W3 edits the constructor, `attach` and `onSongChanged`. Don't touch those.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleHomeShelves.kt`:
  - keep the engine shelves by home key in W2's private `publishLocked(fromEngine)`, where each `Shelf` becomes a `HomeShelf` through `toHomeShelf`. `publishLocked` is the one path both W2's first paint (restored `keptShelves()`, before any build) and every `refresh()` go through, so a tap on a restored shelf right after a restart already gets its source and slot. Do not fill the map only in `refresh()`;
  - add `engineShelf` and `originOf`.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt` line 128 (`rankRadio`): `observer.tag(it.id, SurfaceTag.format(PlayOrigin(PlayPlace.RADIO, item.source, item.slot)))`.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/SmartShuffleBridge.kt`:
  - `mixInto` (lines 63-78) and `plain` (lines 108-113) build one map, song id → `SurfaceTag.format(PlayOrigin(PlayPlace.SMART_SHUFFLE, rec?.source, rec?.slot))`, and call `observer.attribute(map)` once before returning. The per-song `observer.tag` call at line 73 is removed.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/InfinityMix.kt`:
  - `batchLocked` line 287 and `tagOf` (lines 295-300);
  - `forYou` (line 130) becomes `ArrayDeque<Pair<SongItem, QueueItem>>`;
  - `discoveryPick` lines 352-358 use `taste.forYou(FOR_YOU_BATCH)` instead of `taste.startAutoplay(continuous = false)`.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt`. Add `attributePlay` to `object TurtleBridge`, after `detachController` (lines 243-246).
- Test (modify): `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/PlayerObserverTest.kt` (W3 creates it, with `scope`, `song()` and `settle()`).

**Interfaces:**
- Consumes: `PlayOrigin`, `PlayPlace`, `SurfaceTag` (Task 1); `TasteEngine.forYou` (Task 5). From W2: restored `QueueItem`s keep `source` and `slot`, and `toHomeShelf` (or its W2 successor) is the one place a `Shelf` becomes a `HomeShelf`. From W3: `PlayerObserver(tracker, controller, isBookChapter, spokenReady)`. W9 adds no constructor parameter.
- Produces:
  ```kotlin
  // PlayerObserver
  /**
   * The queue the listener is starting, song id → surface detail. Replaces the previous queue's and
   * lasts until a song outside it starts. Not suspending: call it on the UI thread right before
   * starting playback. A per-song [tag] made later wins over it, and an earlier one loses.
   */
  fun attribute(tags: Map<String, String>)

  // TurtleHomeShelves
  /** The engine shelf behind Home row [homeKey] ("turtle:<shelf id>"), while it is on Home. */
  fun engineShelf(homeKey: String): Shelf?
  /** The origin of [songId] played from Turtle row [homeKey]: HOME_TURTLE, the item's source and slot when known, row = shelf id. */
  fun originOf(homeKey: String, songId: String): PlayOrigin

  // TurtleBridge
  /**
   * Shell: [songs] are about to play from [place] (row [row], a Home row key, or null). Call right
   * before starting playback. HOME_TURTLE rows carry each song's generator and slot. Fails open.
   */
  fun attributePlay(place: PlayPlace, row: String?, songs: List<SongItem>)
  ```
- **Surface mapping** (pinned): in `start()`, `val origin = SurfaceTag.parse(tag)`.

  | Tag | `PlayEvent.surface` |
  | --- | --- |
  | `home-turtle`, `home-grid`, `home-recent`, `home-yt` | SHELF |
  | `search` | SEARCH |
  | `radio` | RADIO |
  | `infinity`, `taste` | AUTOPLAY |
  | `shuffle` | PLAYLIST |
  | none parsable (untagged, Spotify URI, `queueSourceTitle`) | AUTOPLAY when `reason == StartReason.AUTOPLAY`, else QUEUE (today's rule) |

  `surfaceDetail` stays `tag ?: song.queueSourceTitle`.
- **Precedence** (pinned):
  - A shared `AtomicLong` (`kotlin.concurrent.atomics`, `@OptIn(ExperimentalAtomicApi::class)` as in `TurtleRuntime`) stamps every `tag()` and every `attribute()`.
  - `pendingTags` becomes `LinkedHashMap<String, Pair<String, Long>>`.
  - The queue attribution lives in an `AtomicReference<QueueTags?>` (`class QueueTags(val tags: Map<String, String>, val stamp: Long)`), so `attribute` needs no mutex.
  - In `start(song)`:
    1. Take the per-song tag `own = pendingTags.remove(song.id)`.
    2. Read the queue's `q?.tags?.get(song.id)`.
    3. If `q != null` and the song is not in it, `compareAndSet(q, null)`: the queue moved on.
    4. Use the candidate with the larger stamp.
- **Infinity tags (new writer):**

  | Pick | Tag |
  | --- | --- |
  | Familiar | `PlayOrigin(INFINITY, LIBRARY, FAMILIAR, "familiar")` |
  | Genre | `(INFINITY, UPSTREAM_RADIO, DISCOVERY, "genre:<id>")` |
  | More like | `(INFINITY, UPSTREAM_RADIO, DISCOVERY, "morelike")` |
  | For you | `(INFINITY, item.source, item.slot, "foryou")`, from the kept `QueueItem` |

  `FOR_YOU_BATCH = 20`. Infinity keeps per-song `observer.tag`, because its queue grows batch by batch.

- [ ] Write the failing tests (in W3's `PlayerObserverTest`, with the same `scope`, `song()` and `settle()`; observer built as `PlayerObserver(ListeningSessionTracker(store), controller = { null }, isBookChapter = { false }, spokenReady = {})`):
  ```kotlin
  @Test fun queueAttributionEndsWhenTheQueueMovesOn() = runBlocking {
      val store = InMemoryPlayEventStore()
      val observer = PlayerObserver(ListeningSessionTracker(store), controller = { null }, isBookChapter = { false }, spokenReady = {})
      val current = MutableStateFlow<SongItem?>(null)
      observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
      observer.attribute(mapOf("a" to "home-yt/-/-/row1", "b" to "home-yt/-/-/row1"))
      current.value = song("a"); observer.settle("a")
      current.value = song("b"); observer.settle("b")
      current.value = song("c"); observer.settle("c")   // radio after the row: not in it
      current.value = song("a"); observer.settle("a")   // played again from the Library
      current.value = null; observer.settle(null)
      val events = store.since(0)
      assertEquals(listOf("home-yt/-/-/row1", "home-yt/-/-/row1", null, null), events.map { it.surfaceDetail })
      assertEquals(listOf(Surface.SHELF, Surface.SHELF, Surface.QUEUE, Surface.QUEUE), events.map { it.surface })
  }
  @Test fun attributionSetJustBeforePlaybackTagsTheTappedSong() = runBlocking {
      val store = InMemoryPlayEventStore()
      val observer = PlayerObserver(ListeningSessionTracker(store), controller = { null }, isBookChapter = { false }, spokenReady = {})
      val current = MutableStateFlow<SongItem?>(null)
      observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
      observer.attribute(mapOf("s" to "search/-/-")); current.value = song("s")   // no suspension in between
      observer.settle("s"); current.value = null; observer.settle(null)
      assertEquals(Surface.SEARCH, store.since(0).single().surface)
  }
  @Test fun theNewerOfQueueAndSongTagWins() = runBlocking {
      val store = InMemoryPlayEventStore()
      val observer = PlayerObserver(ListeningSessionTracker(store), controller = { null }, isBookChapter = { false }, spokenReady = {})
      val current = MutableStateFlow<SongItem?>(null)
      observer.attach(scope, current, MutableStateFlow(true), MutableSharedFlow<Unit>())
      observer.attribute(mapOf("x" to "home-grid/-/-", "y" to "home-grid/-/-"))
      observer.tag("x", "infinity/library/f/familiar")      // Infinity queued it after the Home tap
      current.value = song("x"); observer.settle("x")
      observer.tag("z", "radio/upstream/d")                  // radio ranked it earlier...
      observer.attribute(mapOf("z" to "search/-/-"))        // ...then the listener tapped it in Search
      current.value = song("z"); observer.settle("z")
      current.value = null; observer.settle(null)
      assertEquals(listOf("infinity/library/f/familiar", "search/-/-"), store.since(0).map { it.surfaceDetail })
      assertEquals(listOf(Surface.AUTOPLAY, Surface.SEARCH), store.since(0).map { it.surface })
  }
  ```
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.PlayerObserverTest'`. Expected: compilation fails with `Unresolved reference 'attribute'`.
- [ ] Implement as pinned. `TurtleBridge.attributePlay`:
  - resolve `PlayerObserver` and `TurtleHomeShelves` with `GlobalContext.getOrNull()?.getOrNull<…>()`;
  - for `HOME_TURTLE` use `home.originOf(row, id)`, else `PlayOrigin(place, row = row)`;
  - call `observer.attribute(...)`;
  - catch `Exception` and log with `TurtleLog.error`, so Shell's tap never fails.

  `originOf` falls back to `PlayOrigin(HOME_TURTLE, row = homeKey.removePrefix("turtle:"))` when the shelf or item is gone.
  - `TurtleHomeShelves` keeps the engine shelves as a `@Volatile` immutable `Map<String, Shelf>`, replaced whole. It is written off the UI thread when shelves are published, and read by `attributePlay` on the UI thread.
  - Radio cannot outrank a tapped song. Upstream's radio filter drops songs already in the queue before `rankRadio` tags anything on both platforms: `AndroidQueueCoordinator.kt:548-557`, and `DesktopQueueRadio.kt:92-95`, which also excludes the seed.
- [ ] Run the same command, then `./gradlew :turtle-bridge:desktopTest`. Expected: PASS.
- [ ] `./gradlew :turtle-bridge:compileAndroidMain`. Expected: BUILD SUCCESSFUL.
- [ ] Stage for commit (no commit): `PlayerObserver.kt`, `TurtleHomeShelves.kt`, `TurtleRuntime.kt`, `SmartShuffleBridge.kt`, `InfinityMix.kt`, `TurtleModule.kt`, `PlayerObserverTest.kt`.

### Task 8: Adding a song to a playlist is recorded (bridge)

**Files:**
- Create: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/PlaylistAdds.kt` (the pure diff plus the watcher)
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt`:
  - `turtleModule` (lines 76-115): `single { PlaylistAddWatcher({ get<MusicDatabase>() }, get(), get(), get()) }`;
  - add one more `get()` to the `TurtleRuntime` binding.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt`. Add the constructor parameter `private val playlistAdds: PlaylistAddWatcher` and call `playlistAdds.start(scope)` in `start()` right after `observer.startCheckpoints(scope)` (line 57).
- Create: `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/PlaylistAddsTest.kt`

**Interfaces:**
- Consumes:
  - `TrackFeedback` (Task 2);
  - `PlayEventStore.appendFeedback` (Task 6);
  - `TasteEngine.onFeedback` (Task 3);
  - upstream, read only: `MusicDatabase.invalidationTracker.createFlow("playlist_song_map", "playlist")` (Room 3, emits once at start), `androidx.room3.useReaderConnection`, `dao().getSongById(id)` (`SongDao.kt:291`), `Song.toSongItem()`, `isHistoryPlaylistId` (`HistoryPlaylists.kt`).
- Produces:
  ```kotlin
  data class Membership(val playlistId: String, val songId: String)

  /**
   * Turns snapshots of upstream's playlist membership into the listener's own adds. The first snapshot
   * is only the baseline. A pair seen in any earlier snapshot of this run never counts again (reorders,
   * removals then re-adds, delete-and-reinsert syncs). New pairs wait per playlist until it has been
   * quiet for [settleMs]; a playlist that took more than [maxBurst] new pairs in that burst is an import
   * or a sync, and the whole burst is dropped. System playlists ([counts] false) never count.
   */
  class PlaylistAdds(private val maxBurst: Int = MAX_BURST, private val settleMs: Long = SETTLE_MS) {
      fun observe(snapshot: Set<Membership>, nowMs: Long)
      /** The adds whose playlist has settled by [nowMs], each returned once. */
      fun due(nowMs: Long): List<Membership>
      companion object {
          const val MAX_BURST = 50
          const val SETTLE_MS = 60_000L
          /** False for upstream's own playlists: built-ins ("LP_" ids, e.g. Liked, Downloaded) and the most-played history playlists. */
          fun counts(playlistId: String): Boolean
      }
  }

  class PlaylistAddWatcher(database: () -> MusicDatabase, store: PlayEventStore, taste: TasteEngine, cache: SongCache, nowMs: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() }) {
      fun start(scope: CoroutineScope): Job
  }
  ```
- **Why a settle window.** Upstream's write patterns were checked:
  - `PlaylistSync.kt:116-121` resyncs a YouTube playlist with `clearPlaylist` and a reinsert in one transaction, so the pair set doesn't change;
  - `PlaylistImportManager.kt:316-330` imports a local playlist 250 songs per transaction, so a 260-song import ends with a 10-song transaction;
  - `PlaylistImportManager.kt:370-385` imports into a YouTube playlist one song per transaction, with a network call between songs.

  Only a per-playlist burst rule sees the last two as one import.
- **Watcher** (no test, it is upstream-bound):
  - Debounce the invalidation flow by 1 s, read the pairs with `SELECT m.playlistId, m.songId FROM playlist_song_map m JOIN playlist p ON p.id = m.playlistId WHERE p.isEditable = 1`, and call `observe(pairs, nowMs())`.
  - A second loop calls `due(nowMs())` every 15 s.
  - For each due pair, resolve the track: `cache.get(songId)?.toTrackMeta() ?: dao().getSongById(songId)?.toSongItem()?.toTrackMeta()`. When neither finds it, skip the pair and log with `TurtleLog.info`.
  - Then `store.appendFeedback(TrackFeedback(track = …, kind = ADDED_TO_PLAYLIST, atMs = nowMs(), detail = playlistId))` and `taste.onFeedback(it)`.
  - One `PlaylistAdds` instance is shared by both loops under a `Mutex`.
  - Every step is `runCatching`, logged; `CancellationException` is rethrown.
  - It never consults W3's `listenHistoryPaused()`: an add is explicit.
  - The baseline and the seen set live in memory only. Adds can only happen while the app runs, and the watcher starts with the runtime. An add made in the last minute before the app is killed is lost, which is accepted.

- [ ] Write the failing tests:
  ```kotlin
  class PlaylistAddsTest {
      private fun m(p: String, s: String) = Membership(p, s)
      private val settle = PlaylistAdds.SETTLE_MS

      @Test fun firstSnapshotIsOnlyTheBaseline() {
          val adds = PlaylistAdds()
          adds.observe(setOf(m("PL1", "a"), m("PL1", "b")), 0)
          assertEquals(emptyList(), adds.due(10 * settle))
          adds.observe(setOf(m("PL1", "a"), m("PL1", "b"), m("PL1", "c")), 1_000)
          assertEquals(emptyList(), adds.due(1_000 + settle - 1))
          assertEquals(listOf(m("PL1", "c")), adds.due(1_000 + settle))
          assertEquals(emptyList(), adds.due(1_000 + 2 * settle))   // returned once
      }
      @Test fun reordersRemovalsAndReaddsAreNotAdds() {
          val adds = PlaylistAdds().apply { observe(setOf(m("PL1", "a"), m("PL1", "b")), 0) }
          adds.observe(setOf(m("PL1", "b"), m("PL1", "a")), 1_000)
          adds.observe(setOf(m("PL1", "a")), 2_000)
          adds.observe(setOf(m("PL1", "a"), m("PL1", "b")), 3_000)   // added back, or a resync: seen before
          assertEquals(emptyList(), adds.due(10 * settle))
      }
      @Test fun importsAndSyncsAreNotAdds() {
          val adds = PlaylistAdds().apply { observe(emptySet(), 0) }
          // A local import of 260 songs: 250 in one transaction, then 10.
          val local = (1..260).map { m("PLlocal", "l$it") }
          adds.observe(local.take(250).toSet(), 1_000)
          adds.observe(local.toSet(), 3_000)
          // A remote import: one song per transaction, a few seconds apart.
          var snapshot = local.toSet()
          for (i in 1..60) { snapshot = snapshot + m("PLremote", "r$i"); adds.observe(snapshot, 3_000 + i * 2_000L) }
          assertEquals(emptyList(), adds.due(130_000 + settle))
          // The listener adds an album of 12 to their playlist: 12 adds.
          adds.observe(snapshot + (1..12).map { m("PL1", "t$it") }, 400_000)
          assertEquals(12, adds.due(400_000 + settle).size)
      }
      @Test fun systemPlaylistsNeverCount() {
          val adds = PlaylistAdds().apply { observe(emptySet(), 0) }
          adds.observe(setOf(m("LP_WEEKLY_MOST", "a"), m("LP_MONTHLY_MOST", "b"), m("LP_LIKED", "c")), 1_000)
          assertEquals(emptyList(), adds.due(10 * settle))
          assertTrue(PlaylistAdds.counts("PLabc123"))
          assertFalse(PlaylistAdds.counts("LP_DOWNLOADED"))
      }
  }
  ```
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.PlaylistAddsTest'`. Expected: compilation fails with `Unresolved reference 'PlaylistAdds'`.
- [ ] Implement `PlaylistAdds` (`counts`: `!isHistoryPlaylistId(id) && !id.startsWith("LP_")`), then the watcher and its wiring.
- [ ] Run the same command, then `./gradlew :turtle-bridge:desktopTest`. Expected: PASS. `TurtleRuntimeTest` must still construct the runtime; give it the new parameter if it builds one by hand.
- [ ] Stage for commit (no commit): `PlaylistAdds.kt`, `PlaylistAddsTest.kt`, `TurtleModule.kt`, `TurtleRuntime.kt`.

### Task 9: Upstream's block list feeds the engine (bridge)

**Files:**
- Create: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/UpstreamBlocks.kt`
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt`:
  - `single { UpstreamBlocks({ get<MusicDatabase>() }) }`;
  - the `TasteEngine(...)` binding (lines 103-110) gains `blocks = get<UpstreamBlocks>()`.
- Modify: `TurtleRuntime.kt`. Add the constructor parameter `private val blocks: UpstreamBlocks` (after `playlistAdds`, one more `get()` in the binding) and call `blocks.start(scope)` in `start()` right after `playlistAdds.start(scope)`.
- Modify: `InfinityMix.kt`.
  - `batchLocked` (line 256) reads `val ruled = taste.controls` once per batch.
  - `takeSpaced` (lines 303-310) also drops songs with `TrackKey.youtube(id) in ruled.lessLikeThisTracks` or `ArtistKey.of(artists.firstOrNull()?.name.orEmpty()) in ruled.mutedArtists`. Pass `ruled` as a parameter.
  - Infinity's own pools are therefore covered by the one list too. It already filters `SongLikeState.isDisliked`.
- Create: `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/UpstreamBlocksTest.kt`

**Interfaces:**
- Consumes: `BlockedSet`, `ExternalBlocks` (Task 4); upstream `BlockedContent.songIds()`, `BlockedContent.artists()`, `BlockedContent.version`, `BlockedArtist`, `dao().getArtistById(id)` (`SongDao.kt:416`).
- Produces:
  ```kotlin
  /**
   * Upstream's block list (blocked songs, blocked artists and its local dislikes, which BlockedContent
   * already folds into songIds) as the taste engine's live [ExternalBlocks]. Never copied into Turtle's controls.
   */
  class UpstreamBlocks(private val database: () -> MusicDatabase) : ExternalBlocks {
      /**
       * The engine's view of the block list; emits after each rebuild, so it changes only once the
       * engine already sees the new set. W1's Home rows re-score on this, not on BlockedContent.version,
       * which fires before the rebuild (and before id-only artists are resolved).
       */
      val changes: StateFlow<BlockedSet>
      override fun current(): BlockedSet   // = changes.value
      /** Resolves names of artists blocked by id only, whenever the block list changes. */
      fun start(scope: CoroutineScope): Job
  }

  /** Songs by YouTube id; artists by name, using [resolved] (id → name) for entries upstream stored by id only, which are dropped until resolved. */
  internal fun blockedSetOf(songIds: Set<String>, artists: List<BlockedArtist>, resolved: Map<String, String>): BlockedSet
  ```
- **Rules:**
  - `current()` only reads `changes.value` (a `MutableStateFlow<BlockedSet>`, starting at `BlockedSet.NONE`) and never touches `Settings`. The engine calls it inside its mutex, in loops (`usable()`), and each `BlockedContent.songIds()` call reads `Settings.shared` five times under a lock.
  - The `start` collector rebuilds that set from `BlockedContent.songIds()`, `artists()` and `resolved`:
    - on every `BlockedContent.version` change;
    - every 60 s, which catches settings that change without bumping the version, such as a backup restore.

    It publishes a new instance to `changes` only when the content changed, so the engine's merge cache (Task 4) holds.
  - An entry is id-only when `name == id`. `TrackMeta` has no artist ids, so such an artist must be mapped to a name.
  - `start` collects `BlockedContent.version`. For id-only entries not yet in `resolved`, it calls `getArtistById(id)?.name` and stores the result in a `@Volatile` immutable map.

- [ ] Write the failing test:
  ```kotlin
  class UpstreamBlocksTest {
      @Test fun blockedSongsAndArtistsBecomeEngineKeys() {
          val set = blockedSetOf(
              songIds = setOf("v1"),
              artists = listOf(BlockedArtist("UC1", "Coldplay - Topic"), BlockedArtist("UC2", "UC2"), BlockedArtist("UC3", "UC3")),
              resolved = mapOf("UC2" to "Adele"),
          )
          assertEquals(setOf(TrackKey.youtube("v1")), set.tracks)
          assertEquals(setOf(ArtistKey.of("Coldplay"), ArtistKey.of("Adele")), set.artists)   // UC3 waits for its name
      }
  }
  ```
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.UpstreamBlocksTest'`. Expected: compilation fails with `Unresolved reference 'blockedSetOf'`.
- [ ] Implement it and the wiring.
- [ ] Run the same command, then `./gradlew :turtle-bridge:desktopTest`. Expected: PASS.
- [ ] Stage for commit (no commit): `UpstreamBlocks.kt`, `UpstreamBlocksTest.kt`, `TurtleModule.kt`, `TurtleRuntime.kt`, `InfinityMix.kt`.

### Task 10: "Not interested", the hidden list and undo (bridge)

**Files:**
- Create: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/NotInterested.kt` (target types, hidden records, the menu latch, the Compose state)
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt`:
  - add `notInterested`, `undoHidden` and `publishHidden`;
  - in `start()`'s first launch (lines 60-67), right after W2's `try { restoreTasteState() } finally { tasteRestored.complete(Unit) }`, load the records and `publishHidden()` (W1 then adds `homeRows.start(scope)` after these);
  - `lessLikeThis` (lines 170-177) also saves a SONG record and calls `publishHidden()`.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt`. Add to `object TurtleBridge`, next to `lessLikeThis` (line 249): `notInterested`, `undoHidden`, `hidden`, `hiddenSet`, `homeMenu`.
- Create: `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/NotInterestedTest.kt`

**Interfaces:**
- Consumes:
  - `TrackFeedback` (Task 2), `TasteEngine.onFeedback`, `Hidden`, `hidden()` and `undo()` (Task 3);
  - `listenerControls` and `updateControls` (Task 4);
  - `TurtleHomeShelves.engineShelf` (Task 7);
  - `Shelf.lessLikeThis` (`Surfaces.kt:43`);
  - `SqlitePlayEventStore.pref` / `setPref`;
  - `saveTasteState()` (W2 makes it await the restore).
- Produces:
  ```kotlin
  /** What "Not interested" was chosen on. */
  sealed interface NotInterested {
      val title: String
      data class Song(val song: SongItem) : NotInterested { override val title get() = song.title }
      /** Upstream's artist menu is titled with the alias-resolved name, so the latch must match that. */
      data class Artist(val artist: ArtistItem) : NotInterested { override val title get() = artist.displayTitle }   // com.metrolist.shared.settings.displayTitle
      /** One of Turtle's mixes on Home ([homeKey] "turtle:<shelf id>"). */
      data class Mix(val homeKey: String, override val title: String) : NotInterested
  }

  enum class HiddenKind { SONG, ARTIST, MIX }
  /** One row of the Hidden page. [key]: TrackKey value, ArtistKey value or Reason.key. [artist]: a song's primary artist. */
  data class HiddenEntry(val kind: HiddenKind, val key: String, val title: String, val artist: String? = null, val thumbnail: String? = null)

  /** What Home must not show right now (YouTube ids, artist keys, Turtle row keys). Compose state, read during composition. */
  data class HiddenSet(val songIds: Set<String> = emptySet(), val artists: Set<ArtistKey> = emptySet(), val homeShelfKeys: Set<String> = emptySet()) {
      fun hides(item: YTItem): Boolean        // SongItem: id, or primary artist's ArtistKey.of(name); ArtistItem: ArtistKey.of(title); else false
      fun hidesShelf(homeKey: String): Boolean
  }

  /**
   * Display data for hidden entries, saved in our pref table ("hidden_items"). First line "turtle-hidden 1",
   * then one entry per line: kind, key, title, artist, thumbnail, tab-separated ("" for null). Tabs and
   * newlines inside fields become spaces. Never throws: an unknown header gives empty; unknown kinds skip the line.
   */
  internal object HiddenRecords { fun encode(entries: List<HiddenEntry>): String; fun decode(text: String?): List<HiddenEntry> }

  /** Which Home item the menu about to open belongs to, so Shell's menu header offers "Not interested" for it only. */
  class HomeMenuLatch(private val nowMs: () -> Long, private val windowMs: Long = WINDOW_MS) {
      fun arm(target: NotInterested)
      fun disarm()
      /** The armed target if a menu titled [title] opens within [windowMs]; consumed either way, so no later menu gets it. */
      fun claim(title: String): NotInterested?
      companion object { const val WINDOW_MS = 3_000L }
  }

  // TurtleRuntime
  suspend fun notInterested(target: NotInterested)
  suspend fun undoHidden(entry: HiddenEntry)

  // TurtleBridge
  suspend fun notInterested(target: NotInterested)
  suspend fun undoHidden(entry: HiddenEntry)
  val hidden: StateFlow<List<HiddenEntry>>     // for the Hidden page
  val hiddenSet: HiddenSet                     // Compose state; Home filters with it
  val homeMenu: HomeMenuLatch                  // one instance, real clock
  ```
- **What "Not interested" does** (pinned):

  | Target | Effect | Record (`HiddenEntry`) |
  | --- | --- | --- |
  | Song | `TrackFeedback(NOT_INTERESTED)` appended to the store, then `taste.onFeedback`. Its play is not marked, and the song is not skipped even if it is playing. | `SONG`, `track.key.value`, title, primary artist, thumbnail |
  | Artist | `taste.updateControls { it.copy(mutedArtists = it.mutedArtists + ArtistKey.of(title)) }` | `ARTIST` |
  | Mix | `taste.lessLikeThis(shelf.lessLikeThis)` via `engineShelf(homeKey)`. A no-op if the shelf is gone. | `MIX`, `reason.key`, title |

  Albums, playlists and other items get no entry. The engine can't rule them out, and a hidden tile that came back next refresh would break the listener's trust.
  - Then `saveTasteState()`, save the records, and `publishHidden()`.
- **Undo** (pinned):

  | Kind | Effect |
  | --- | --- |
  | SONG | `TrackFeedback(UNDO_NOT_INTERESTED)` with `cache.get(id)?.toTrackMeta() ?: TrackMeta(TrackKey(key), title, listOfNotNull(artist))`, then `taste.onFeedback` |
  | ARTIST | `updateControls { it.undo(Hidden.Artist(ArtistKey(key))) }` |
  | MIX | `updateControls { it.undo(Hidden.Shelf(key)) }` |

  Then drop the record, save, and publish.
- **`publishHidden()`:**
  - `hidden.value` is `taste.listenerControls.hidden()` mapped to entries. Each takes the record with the same kind and key when one exists. Otherwise the title is `taste.displayName(TrackKey)` / `displayName(ArtistKey)`, and failing that the key itself.
  - Records whose key is no longer hidden are dropped.
  - `hiddenSet` (a `mutableStateOf` in the bridge) is built from the same controls. `homeShelfKeys` are the current Turtle row keys whose `engineShelf(key)?.reason?.key` is suppressed.
  - Upstream's own block list is not listed: it keeps upstream's Blocked songs and Blocked artists pages, which stay reachable in Settings (`KEEP_SUBPAGES`).

- [ ] Write the failing tests:
  ```kotlin
  class NotInterestedTest {
      private fun song(id: String) = SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, "Artist $id")), album = null)

      @Test fun onlyTheMenuOpenedForTheItemGetsIt() {
          var now = 0L
          val latch = HomeMenuLatch({ now })
          val target = NotInterested.Song(song("a"))
          latch.arm(target)
          assertNull(latch.claim("Another song"))     // a different menu opened first: the arm is spent
          assertNull(latch.claim("Song a"))
          latch.arm(target); now += 2_999
          assertEquals(target, latch.claim("Song a"))
          assertNull(latch.claim("Song a"))           // consumed
          latch.arm(target); now += 3_001
          assertNull(latch.claim("Song a"))           // too late: some other surface's menu
          latch.arm(target); latch.disarm()
          assertNull(latch.claim("Song a"))
      }
      @Test fun hiddenRecordsRoundTripAndSurviveDamage() {
          val entries = listOf(
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
      @Test fun homeHidesSongsArtistsAndMixes() {
          val set = HiddenSet(songIds = setOf("a"), artists = setOf(ArtistKey.of("Artist b")), homeShelfKeys = setOf("turtle:daily_mix:0"))
          assertTrue(set.hides(song("a")))
          assertTrue(set.hides(song("b")))
          assertFalse(set.hides(song("c")))
          assertTrue(set.hides(ArtistItem(id = "UCb", title = "Artist b", thumbnail = null)))
          assertTrue(set.hidesShelf("turtle:daily_mix:0"))
      }
  }
  ```
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.NotInterestedTest'`. Expected: compilation fails with `Unresolved reference 'HomeMenuLatch'`.
- [ ] Implement as pinned. `TurtleBridge.notInterested` and `undoHidden` are no-ops when the runtime is unavailable (as `lessLikeThis` is, line 249).
- [ ] Run the same command, then `./gradlew :turtle-bridge:desktopTest`. Expected: PASS.
- [ ] `./gradlew :turtle-bridge:compileAndroidMain`. Expected: BUILD SUCCESSFUL.
- [ ] `./gradlew :turtle-bridge:ktlintCheck`. It already fails on about 1,000 violations in existing files (W3 plan), so the task result is not the check. Expected: the report under `turtle/bridge/build/reports/ktlint/` lists nothing in lines this workstream added.
- [ ] Stage for commit (no commit): `NotInterested.kt`, `NotInterestedTest.kt`, `TurtleRuntime.kt`, `TurtleModule.kt`.

### Task 11: Shell Home attributes plays and offers "Not interested" (UI)

**Files:**
- Create: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellHomeFeedback.kt`. It holds `ShellHomeFeedback` (open-menu and play helpers), `HomeNotInterestedRow` and `MixMenu`. W1 restructures `ShellHome.kt` next, so every `ShellHome.kt` change below is a one-line call into this file.
- Modify: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellHome.kt`:

  | Lines | Code | Change |
  | --- | --- | --- |
  | 166-184 | `HomeLists.of` | gains a `hidden: HiddenSet` parameter; drops hidden mixes and items in mixes, shortcuts, `upstreamRows` and `jumpBackIn` (`shelf.copy(items = …)`) |
  | 272 | | `remember(shelves, ui, hidden)`, with `val hidden = TurtleBridge.hiddenSet` read just above |
  | 357, 359 | grid tile | `onClick` / `onPlay` for songs call `ShellHomeFeedback.play(PlayPlace.HOME_GRID, null, songs) { … }` first |
  | 361 | grid tile | `onLongClick` = `ShellHomeFeedback.openMenu(item, f)` |
  | 379 | Jump back in | passes `place = PlayPlace.HOME_RECENT` |
  | 410 | YouTube rows | pass `place = PlayPlace.HOME_YOUTUBE` |
  | 490-501 | `MixTile` | attributes with `PlayPlace.HOME_TURTLE, mix.shelf.key`; wraps `MixCard` in a `Box` with `MixMenu` |
  | 518-550 | `ItemRow` | new `place: PlayPlace` parameter; the song `open` lambda and the hover play button attribute with `(place, shelf.key, songs)`; long-click arms the latch only when `place != PlayPlace.HOME_YOUTUBE` |
- Modify: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/components/ShellComponents.kt`. `MixCard` (lines 251-263) gains `onLongClick: (() -> Unit)? = null`, passed to `shellPressable(onClick, onLongClick = onLongClick)`.
- Modify: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellMenus.kt`. `ShellMenuHeader`: one line after the divider `Box` (lines 155-158): `HomeNotInterestedRow(slot.title, slot.popup)`.
- Modify: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellSearch.kt`. Lines 506, 523, 537, 594 and 599 call `slot.onItemClick(item)`. Each goes through one private helper: `TurtleBridge.attributePlay(PlayPlace.SEARCH, null, listOf(item))` when `item is SongItem`, then `slot.onItemClick(item)`.
- Modify: `turtle/ui/src/commonMain/composeResources/values/strings_home.xml`. Add:
  - `shell_home_not_interested` = "Not interested";
  - `shell_home_not_interested_done` = "Hidden. You can undo this in Settings › Recommendations".

**Interfaces:**
- Consumes: `TurtleBridge.attributePlay` (Task 7); `notInterested`, `hiddenSet`, `homeMenu` and `NotInterested` (Task 10); `PlayPlace` (Task 1).
- Produces (for W1, who must keep these calls when restructuring `HomeLists`, the order, the grid and rows):
  ```kotlin
  internal object ShellHomeFeedback {
      /** Attributes [songs] to [place]/[row], then runs [play] (which starts playback). */
      fun play(place: PlayPlace, row: String?, songs: List<SongItem>, play: () -> Unit)
      /** Arms the "Not interested" latch for songs and artists (disarms for anything else), then opens upstream's menu. */
      fun openMenu(item: YTItem, open: (YTItem) -> Unit)
  }
  /** Inside Shell's menu header: "Not interested" for the Home item this menu was opened for; nothing for any other menu. */
  @Composable internal fun HomeNotInterestedRow(title: String, popup: Boolean)
  /** A mix card's long-press menu: one entry, "Not interested". */
  @Composable internal fun MixMenu(expanded: Boolean, onDismiss: () -> Unit, mix: NotInterested.Mix)
  ```
- **Decisions:**
  - `HomeNotInterestedRow` claims with `remember(title) { TurtleBridge.homeMenu.claim(title) }`, once per header.
  - Upstream's headers use `song.title` (`MediaMenuModel.kt:344`) and `artist.displayTitle` (`:511`), which is what `NotInterested.title` returns.
  - Headers compose only while a menu is open (sheet or context menu), so no persistent header consumes an armed target.
  - Upstream's header slot has no dismiss callback. After a tap, the row turns into a disabled row with a check and the text `shell_home_not_interested_done`, and the item disappears from Home behind the menu.
  - The row copies `MenuRow`'s look (`ShellMenus.kt:298-357`): `Glyphs.Less` icon, popup vs sheet sizes.
  - `MixMenu` is a Material3 `DropdownMenu` with one `DropdownMenuItem`, as `ShellCollection.kt:1498` does. Mix cards had no menu, so this adds one and replaces nothing.
  - The grid's Infinity and Liked Songs tiles are not attributed by Home: Infinity tags its own songs, and Liked Songs opens a page.

- [ ] Write the code (UI: no tests by policy).
- [ ] Compile: `./gradlew :turtle-ui:compileKotlinDesktop :turtle-ui:compileAndroidMain`. Expected: BUILD SUCCESSFUL.
- [ ] `./gradlew :turtle-ui:ktlintCheck`. Existing Turtle files are not ktlint-clean, so the task may fail on them. Expected: the report under `turtle/ui/build/reports/ktlint/` lists nothing in lines this workstream added.
- [ ] Stage for commit (no commit): `ShellHomeFeedback.kt`, `ShellHome.kt`, `ShellComponents.kt`, `ShellMenus.kt`, `ShellSearch.kt`, `strings_home.xml`.

### Task 12: The "Hidden songs and artists" page (UI)

**Files:**
- Create: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellHidden.kt`. It holds `@Composable internal fun HiddenList()`, which collects `TurtleBridge.hidden`, groups the rows by kind (Songs, Artists, Mixes) and gives each a "Show again" button that calls `settingsScope.launch { TurtleBridge.undoHidden(entry) }`. With no entries it shows the empty text.
- Modify: `turtle/ui/src/commonMain/kotlin/app/turtleplayer/ui/screens/ShellSettingsTurtle.kt`:
  - the `recommendations` page (lines 325-367) gets one more group with `navigationItem("recsHidden", stringResource(Res.string.shell_set_hidden), SettingsPageId.APPEARANCE_NAVIGATION, stringResource(Res.string.shell_set_hidden_sub))`;
  - a new `val hiddenPage = turtlePage(SettingsPageId.APPEARANCE_NAVIGATION, …, Glyphs.Less, listOf(group("hidden", "", listOf(customItem("hiddenList", title) { HiddenList() }))))`;
  - add `hiddenPage` to `pages` (line 515), not to any root group.
- Modify: `turtle/ui/src/commonMain/composeResources/values/strings_settings.xml`. Add:

  | Key | English |
  | --- | --- |
  | `shell_set_hidden` | Hidden songs and artists |
  | `shell_set_hidden_sub` | What you marked Not interested |
  | `shell_set_hidden_empty` | Nothing hidden. Choose Not interested on Home to hide a song, artist or mix. |
  | `shell_set_hidden_songs` | Songs |
  | `shell_set_hidden_artists` | Artists |
  | `shell_set_hidden_mixes` | Mixes |
  | `shell_set_hidden_undo` | Show again |

**Interfaces:**
- Consumes: `TurtleBridge.hidden`, `undoHidden`, `HiddenEntry` and `HiddenKind` (Task 10).
- **Decision (page id).** Turtle reuses upstream's `SettingsPageId`s for its pages, and adding an enum value would be an upstream edit.
  - `APPEARANCE_NAVIGATION` is a subpage only in upstream's own registry (`SettingsOrganization.kt:20`, applied before Turtle reshapes it). Turtle reads its items into Advanced (`ShellSettingsTurtle.kt:477`) and never registers it as a page. No platform routes it specially (checked with grep).
  - `LISTEN_TOGETHER` is filtered out of settings search (`SettingsSearch.kt:342`). `UPDATE_DETAILS` has Android routing (`SettingsScreen.kt:519`). `MINI_PLAYER_STYLE` sits in Turtle's root group list.
  - W1 must not reuse `APPEARANCE_NAVIGATION`.

- [ ] Write the code.
- [ ] Compile: `./gradlew :turtle-ui:compileKotlinDesktop :turtle-ui:compileAndroidMain`. Expected: BUILD SUCCESSFUL.
- [ ] Stage for commit (no commit): `ShellHidden.kt`, `ShellSettingsTurtle.kt`, `strings_settings.xml`.

### Task 13: (dropped by the lead)

Now Playing's "Never play this song" keeps upstream's behaviour (a YouTube dislike). The spec's "never write YouTube dislikes" applies to Turtle's own "Not interested", not to upstream's existing button. Nothing to do.

### Task 14: Whole-workstream verification

- [ ] `./gradlew -p turtle/engines check`. Expected: PASS. The offline replay stays within 2% of Task 1's baseline.
- [ ] `./gradlew :turtle-bridge:desktopTest`. Expected: PASS.
- [ ] `./gradlew :desktopApp:compileKotlinJvm`. Expected: BUILD SUCCESSFUL.
- [ ] `./gradlew :androidApp:compileDebugKotlin`. Expected: BUILD SUCCESSFUL. (Task name confirmed with `--dry-run` on 22 Nov 2026.)
- [ ] `bash turtle/scripts/check-hooks.sh origin/main` and `git status --short`. Expected: the hook check passes and only `turtle/` paths change. `turtle/HOOKS.md` is untouched.
- [ ] Device checks (report as pending if not done):
  - on the OnePlus, tap a song in a YouTube row, a mix card, a grid tile and a Search result. Pull the drive log or query `turtle.db`. Expected: `surface` is SHELF, SHELF, SHELF and SEARCH, and `surface_detail` is `home-yt/-/-/<row key>`, `home-turtle/<src>/<slot>/<shelf id>`, `home-grid/-/-` and `search/-/-`;
  - add a song to a playlist. Expected: one `track_feedback` row. A YouTube playlist sync adds none;
  - long-press a mix card and choose Not interested. Expected: the mix disappears, it is listed on the Hidden page, and "Show again" brings it back at the next refresh;
  - block an artist in upstream's settings. Expected: their songs leave radio ranking. Unblock: they come back without a restart;
  - play the Infinity Mix until it refills "for you". Expected: Up Next of the playing queue is not reset.
- [ ] Stage for commit (no commit). Nothing new. Suggested commits, for the owner to approve:
  - `feat(turtle-taste): play origins, feedback events and live blocks`;
  - `feat(turtle-events): non-play feedback events`;
  - `feat(turtle-bridge): play attribution, playlist adds and one not-interested list`;
  - `feat(turtle-ui): attribute Home plays and add Not interested`.

## Interfaces other workstreams may use

- **Engine (`turtle-taste`):**
  - `PlayPlace`, `PlayOrigin`, `SurfaceTag.format(origin)` and `SurfaceTag.parse(detail): PlayOrigin?`. The return type changed from a `Pair`, so read `.source` and `.slot`.
  - `ExternalBlocks` and `BlockedSet`.
  - `TasteEngine.controls`, now the *effective* controls including upstream blocks. W1's row scorer should read this for "share by disliked or muted artists".
  - `TasteEngine.listenerControls`.
  - `TasteEngine.onFeedback(f: TrackFeedback)` and `TasteEngine.forYou(limit)`.
  - `Hidden`, `UserControls.hidden()` and `UserControls.undo(target)`.
- **Engine (`turtle-events`):** `TrackFeedback`, `TrackFeedbackKind`, `PlayEventStore.appendFeedback` / `feedbackSince`.
- **Bridge, for W1:**
  - `TurtleBridge.attributePlay(place: PlayPlace, row: String?, songs: List<SongItem>)`. Home rows use HOME_TURTLE (row = the Turtle `HomeShelf.key`), HOME_GRID, HOME_RECENT (Jump back in) and HOME_YOUTUBE (row = the YouTube row key).
  - `TurtleBridge.hiddenSet` (`HiddenSet.hides(item)` and `hidesShelf(key)`); Home must keep filtering with it (W1 moves the filter from `HomeLists.of` into `HomeLayout.of(..., hidden)`, applied before the grid is filled).
  - `TurtleBridge.homeMenu: HomeMenuLatch`, `TurtleBridge.notInterested(NotInterested)`, `TurtleBridge.undoHidden(HiddenEntry)`, `TurtleBridge.hidden`.
  - `TurtleHomeShelves.engineShelf(homeKey)` and `originOf(homeKey, songId)`. W1 must keep the engine shelves recorded where a `Shelf` becomes a `HomeShelf`.
  - `PlayerObserver.attribute(tags)`.
  - `UpstreamBlocks.changes: StateFlow<BlockedSet>`: re-score Home rows on this, not on `BlockedContent.version`.
  - `TurtleRuntime.notInterested(target)` and `undoHidden(entry)`, next to the existing `lessLikeThis(song)`: the three places the listener's controls change from the bridge.
- **UI, for W1:**
  - `ShellHomeFeedback.play` and `openMenu`, `HomeNotInterestedRow` and `MixMenu`. Every Home play and long-press must keep going through them.
  - Turtle rows W1 adds (More like X, Jump back in as Turtle's own row) pass `PlayPlace.HOME_TURTLE` or `PlayPlace.HOME_RECENT`.
  - The settings page id `APPEARANCE_NAVIGATION` is taken.
- **Schema:** `turtle.db` `user_version` 6 is `track_feedback`. Later workstreams take 7.

## Self-review against the spec

| Spec item | Covered by |
| --- | --- |
| Every Shell Home play records its surface: Turtle mixes and rows with the shelf id; grid, Jump back in and each YouTube row with the row key; Infinity, Smart Shuffle, radio and search | Tasks 7 and 11 (Home, Search), Task 7 (Infinity, Smart Shuffle, radio), Task 1 (format) |
| `PlayEvent.surface` no longer hardcoded | Task 7's mapping table |
| Detail in a format `SurfaceTag.parse` accepts; existing tags keep parsing; Infinity's tag fixed | Task 1 tests |
| Adding to a playlist records a non-play feedback event, a strong positive; new event type plus store migration; detected by watching membership without a hook | Tasks 2, 3, 6, 8 |
| Upstream's block list and dislikes read live, never copied; unblocking unmutes | Tasks 4 and 9. Dislikes are folded into `BlockedContent.songIds()` |
| Turtle never writes to YouTube's dislikes | Global Constraints; Task 13 removes the existing call |
| "Not interested" added to the existing menu for Turtle rows, grid tiles and mix cards, never replacing it; drops the item; records less-like-this | Tasks 10 and 11. Mix cards had no menu; `MixMenu` is a one-entry menu |
| Hidden songs and artists page with undo | Tasks 10 and 12. Undo also reverses play-level marks (Task 3) |
| Infinity's refill no longer resets the shared session | Tasks 5 and 7 |
| Offline replay no worse than 2% | Tasks 1 and 5 |
| Hook budget untouched | Task 14 |
