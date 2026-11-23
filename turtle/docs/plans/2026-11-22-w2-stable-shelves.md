# W2 Shelves That Survive Restarts Implementation Plan

> **For agentic workers:** Implement task-by-task; steps use checkbox (- [ ]) syntax.

**Goal:** Turtle's day mixes and week's discovery stay the same across process kills and restarts, and they show on Home before any network call.

**Architecture:** The taste engine's kept shelves (the `stable` map, `TasteEngine.kt:111`) become part of `TasteState`. They are written whole, with track meta, source, slot, score and reason, through the existing hand-written encoder, and restored by `importState`. The bridge saves each shelf song's upstream `SongItem` in a new table in our SQLite file and pins those songs in `SongCache`. On start it restores them and publishes the restored shelves (`TasteEngine.keptShelves()`) before the normal refresh. The taste state is saved after every shelf build, as well as after every play.

**Tech Stack:** Kotlin Multiplatform (engines: pure `commonMain`, `kotlin.test`, `kotlinx-coroutines-test`); `:turtle-bridge` desktop tests on androidx.sqlite (`JdbcSQLiteDriver`) and kotlinx.serialization JSON for `SongItem`.

**Spec:** /Users/dhawal/Desktop/Creations/turtle/turtle/docs/specs/2026-11-22-personal-home-design.md (section "W2 Shelves that survive restarts")

Build order: W3, **W2**, W9, W1, in one checkout. No commits until the owner approves.

## Global Constraints

- Engines (`turtle/engines/*`) stay pure Kotlin `commonMain`: no `java.*`, no Android, no upstream (`com.metrolist.*`) imports.
- Inject `Clock` and `Random` into anything time- or chance-dependent. Tests use `MutableClock` / `Clock { now }` and `Random(7)`.
- Change no upstream files. W2 plans **0** hook lines, and `turtle/HOOKS.md` is not touched. `git status --short` must show only `turtle/` paths.
- Engines hold no user-facing strings. W2 adds no strings at all: shelf titles already come from `:turtle-bridge` compose resources. Never put English literals in code, and never edit `values-*/strings.xml`.
- Keep the existing encoder's contract: `TasteState.decode` never throws, and returns null only for text it cannot read at all. A bad shelf payload must cost only the shelves, never the controls.
- Listening data stays on device. Shelf songs go into our own `turtle.db`, never upstream's `MusicDatabase`.
- Formatting: `:turtle-bridge` has ktlint, applied by the root `build.gradle.kts` `subprojects {}`. Run `./gradlew :turtle-bridge:ktlintCheck` and fix only violations in lines this plan adds. Do **not** run `ktlintFormat`, because it rewrites unrelated files. The engines (an included build) have no ktlint task, so match the surrounding style there.
- Commit scopes are `turtle-taste` and `turtle-bridge`, for example `feat(turtle-taste): keep Home's day and week shelves across restarts`. Only stage files; the owner approves commits.
- Engine tests use public APIs only. The offline replay (`OfflineReplayTest`) must not regress by more than 2%. W2 does not touch ranking, so it must pass unchanged.
- Leave `TasteEngine.refreshShelves` without a caller, as the spec requires. Upstream's pull-to-refresh (`androidApp/.../HomeScreen.kt:359`) and `ShellHome.kt:437` need no change, because neither reaches Turtle's shelves.

## Review Focus

These five input classes and failure modes are the ones most likely to bite a listener. Each has a test in the task that owns it.

1. **State saved by today's release, and today's release reading the new state.** The v1 text has no shelves and must load with every control intact. The header stays `turtle-taste-state 1`, so an older build (a rollback, or the side-by-side debug install) still reads the new text, because it ignores unknown keys. Test: Task 1 `versionOneStateWithoutShelvesStillLoads`.
2. **Free text that collides with the format.** This covers titles, artists and albums containing a separator or the null marker, an empty album versus a null one, `meta:` keys containing `|` and `,`, and reasons that cannot be written (`RadioFrom`, `UpNext`). Each must drop only its own item, and the fields after it must stay aligned. Test: Task 1 `unsafeTextOrAnUnreadableItemCostsOnlyThatItem`.
3. **A damaged, future-format or empty saved shelf payload.** The listener's mutes and "less like this" marks must survive. A restored day with nothing in it must be rebuilt, not pinned empty all day. Tests: Task 1 `unreadableShelvesKeepTheListenersControls` and Task 2 `aRestoredDayWithNothingInItIsRebuilt`.
4. **A kill right after a build, and the startup race.** If the process dies after shelves are built and before any play, the build must already be saved. A build made in this run before `importState` must not be replaced by the restored shelves, or rows would jump. Tests: Task 5 `shelfSongsComeBackAfterARestartWithoutTheNetwork`, which saves state from `persist` with no play, and Task 2 `aBuildInThisRunWinsOverRestoredShelves`.
5. **A week of radio pages evicting shelf songs.** `SongCache` is an LRU whose `get` does not refresh recency, so Weekly Discovery's songs, loaded at start, would be the first to go. Shelf songs must stay resolvable for the whole period. Test: Task 5, which uses `SongCache(capacity = 1)` flooded with 50 songs after the restore.

---

### Task 1: Write kept shelves into `TasteState`

**Files:**
- Create: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/KeptShelves.kt`
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteState.kt`:
  - lines 6-14: KDoc, adding "and Home's kept shelves";
  - lines 15-19: new property;
  - lines 20-32: `encode`;
  - lines 39-70: `decode`.
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/Surfaces.kt`:
  - line 3: add "Persisted by name in `TasteState`; never rename." to the `ShelfKind` KDoc;
  - line 22: Fresh for You refreshes daily, not on Fridays (the spec's fix);
  - lines 48-52: the same "never rename" note on the `ShelfCadence` KDoc.
- Test: `turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/TasteEngineTest.kt`. Insert after `tasteStateRoundTripsEveryControl` (ends at line 380). Add imports `app.turtleplayer.taste` types as needed; they are in the same package.

**Interfaces:**
- Consumes: `Shelf`, `ShelfKind`, `ShelfCadence` (Surfaces.kt); `QueueItem` (Queue.kt:26); `Reason`, `RadioSeed`, `CandidateSource.fromTag`, `SlotKind` (Candidates.kt); `TrackMeta`, `TrackKey`, `ArtistKey` (core). Nothing from W1, W3 or W9.
- Produces:
  ```kotlin
  /** One cadence's kept shelves: the period they were built in (a local day, or the Monday of a week) and the shelves as built. */
  data class KeptShelves(val cadence: ShelfCadence, val period: Long, val shelves: List<Shelf>)

  data class TasteState(
      val controls: UserControls = UserControls(),
      val exploration: ExplorationState = ExplorationState(),
      val recent: List<TrackKey> = emptyList(),
      val kept: List<KeptShelves> = emptyList(),
  )
  ```
  The codec is `internal object KeptShelvesCodec { fun encode(kept: List<KeptShelves>): List<String>; fun decode(lines: List<String>): List<KeptShelves> }` in `KeptShelves.kt`. It is internal; other workstreams use only `TasteState`.

**Wire format (pinned; the implementer must not change it):**
- The header stays `turtle-taste-state 1`. The existing ten lines are unchanged.
- The shelves follow the existing lines, always starting with a payload-version line:
  ```
  shelves\t1
  kept\t<ShelfCadence.name>␟<period>
  shelf\t<id>␟<ShelfKind.name>␟<reason>
  item\t<trackKey>␟<title>␟<artists>␟<album>␟<durationMs>␟<releasedAtMs>␟<youtubeId>␟<isrc>␟<explicit 1|0>␟<popularity>␟<source tag>␟<slot tag>␟<score>␟<reason>
  ```
- `␟` is `ITEM` (`\u001f`). A `shelf` line belongs to the latest `kept` line, and an `item` line to the latest `shelf` line. Groups are written in `kept` list order.
- Separators:
  - `PAIR` (`\u001d`) separates the parts of a reason;
  - `LIST` (`\u001e`) separates list entries inside one field (artists, `topArtists`);
  - `NULL` (`\u001c`) as the whole field means null (album, durationMs, releasedAtMs, youtubeId, isrc, popularity, a `DailyMix` seed).
  - The empty string is a real empty value, never null. An empty list is written as the empty string.
- Numbers use `toString()` and are read back with `toLong()`, `toFloat()` or `toInt()`. `Float.toString()` round-trips exactly.
- Reserved characters are `\n`, `\t`, `ITEM`, `PAIR`, `LIST` and `NULL`. When writing:
  - an item is skipped if any of its text contains a reserved character, or if its reason has no encoding;
  - a shelf is skipped, with all its items, if its id or reason does.
  - The existing `line()` helper is **not** used for these positional lines, because it drops single fields and would shift the ones after.
- When reading:
  - shelf lines are parsed only if a `shelves` line with value exactly `1` is present; any other value means no shelves;
  - the shelf lines are walked in order, without the `filter { it.isNotEmpty() }` that the other keys use;
  - each `kept`, `shelf` and `item` line is read in its own `runCatching`, so a bad line drops only itself. An orphaned `shelf` or `item` (its header was dropped) is dropped too;
  - an unknown cadence, kind, source tag, slot tag or reason tag counts as a bad line, and so does a `TrackKey` without a scheme, since its `init` throws;
  - a `kept` group left with **no shelves** after decoding is dropped. A shelf left with **zero items** is kept, so `importState`'s empty-day rule (Task 2) is what decides about it;
  - the whole shelves decode is wrapped once more in `runCatching { … }.getOrDefault(emptyList())`, so it can never null the state.
- Reason codec. Use an exhaustive `when (reason)` with **no `else`**, so a new `Reason` subtype breaks the build until it gets a tag. Tags are persisted; never rename them.

  | Reason | Encoded parts (joined by `PAIR`) |
  | --- | --- |
  | `BecauseYouListened(track)` | `listened`, track |
  | `BecauseYouReplayed(track)` | `replayed`, track |
  | `BecauseYouLiked(track)` | `liked`, track |
  | `SimilarTo(artist)` | `similar`, artist |
  | `ListenersAlsoPlay(artist)` | `cf`, artist |
  | `FromYourTaste(clusterId, topArtists)` | `cluster`, clusterId, topArtists joined by `LIST` |
  | `FreshFrom(artist)` | `fresh`, artist |
  | `Rediscover(track, lastPlayedMs)` | `rediscover`, track, lastPlayedMs |
  | `UpstreamRadio(seed)` | `upstream`, seed |
  | `BecauseYouPicked(artist)` | `picked`, artist |
  | `DailyMix(clusterId, topArtists, seedArtist)` | `mix`, clusterId, topArtists joined by `LIST`, seedArtist or `NULL` |
  | `OnRepeat` / `NewForYou` / `FreshForYou` / `RediscoverShelf` | `on_repeat` / `new_for_you` / `fresh_for_you` / `rediscover_shelf` |
  | `RadioFrom`, `UpNext` | no encoding (null): they are never on Home shelves, so such an item or shelf is skipped |

- The artist key is not stored separately. `TrackMeta.artistKey` is derived from `artists[0]` (core `Track.kt:89`), so writing `artists` verbatim and in order restores it. That keeps mutes working on a restored shelf (`TasteEngine.kt:401-402`).

**Steps:**

- [ ] Baseline before any change: run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.OfflineReplayTest'` on the untouched tree. Write down the metrics it prints or asserts, for the 2% comparison in Task 7.
- [ ] Write the failing tests in `TasteEngineTest.kt` after line 380:
  ```kotlin
  private val fullTrack = TrackMeta(
      TrackKey("yt:a1"), "Song", listOf("Artist A", "Guest"), album = "LP", durationMs = 201_000,
      releasedAtMs = 1_700_000_000_000, youtubeId = "a1", isrc = "USX1", explicit = true, popularity = 0.25f,
  )
  private val bareTrack = TrackMeta(TrackKey("meta:artist b|song,live"), "Bare", listOf("Artist B"), album = "")
  private fun item(t: TrackMeta, reason: Reason = Reason.SimilarTo(ArtistKey("a"))) =
      QueueItem(t, CandidateSource.SESSION, reason, SlotKind.FAMILIAR, 1f)

  @Test
  fun tasteStateRoundTripsKeptShelves() {
      val items = listOf(
          QueueItem(fullTrack, CandidateSource.UPSTREAM_RADIO, Reason.UpstreamRadio(TrackKey("yt:seed")), SlotKind.DISCOVERY, 0.8125f),
          QueueItem(bareTrack, CandidateSource.LIBRARY, Reason.Rediscover(TrackKey("yt:r"), 1_600_000_000_000), SlotKind.FAMILIAR, -1.5f),
      )
      val mix = Shelf("daily_mix:artist:artist a", ShelfKind.DAILY_MIX, Reason.DailyMix(0, listOf(ArtistKey("artist a")), seedArtist = ArtistKey("artist a")), items)
      val weekly = Shelf("weekly_discovery:weekly", ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, items.reversed())
      val state = TasteState(
          UserControls(mutedArtists = setOf(ArtistKey("m"))),
          kept = listOf(KeptShelves(ShelfCadence.DAILY, 20_000, listOf(mix)), KeptShelves(ShelfCadence.WEEKLY, 19_997, listOf(weekly))),
      )
      assertEquals(state, TasteState.decode(state.encode()))
      assertTrue(state.encode().startsWith("turtle-taste-state 1\n"), "header unchanged, so older builds still read it")
  }

  @Test
  fun everyHomeReasonKeepsItsKeyAcrossARoundTrip() {
      val a = ArtistKey("a")
      val b = ArtistKey("b")
      val t = TrackKey("yt:t")
      val reasons = listOf(
          Reason.BecauseYouListened(t), Reason.BecauseYouReplayed(t), Reason.BecauseYouLiked(t), Reason.SimilarTo(a),
          Reason.ListenersAlsoPlay(a), Reason.FromYourTaste(2, listOf(a, b)), Reason.FreshFrom(a), Reason.Rediscover(t, 42),
          Reason.UpstreamRadio(t), Reason.BecauseYouPicked(a), Reason.DailyMix(1, listOf(a, b)), Reason.DailyMix(0, listOf(a), seedArtist = a),
          Reason.OnRepeat, Reason.NewForYou, Reason.FreshForYou, Reason.RediscoverShelf,
      )
      for (r in reasons) {
          val shelf = Shelf("s", ShelfKind.MORE_LIKE, r, listOf(item(fullTrack, r)))
          val decoded = TasteState.decode(TasteState(kept = listOf(KeptShelves(ShelfCadence.DAILY_MORE_LIKE, 1, listOf(shelf)))).encode())!!
          val back = decoded.kept.single().shelves.single()
          assertEquals(r, back.reason)
          assertEquals(r.key, back.reason.key, "suppressedReasons match by key")
          assertEquals(r, back.items.single().reason)
      }
  }

  @Test
  fun versionOneStateWithoutShelvesStillLoads() {
      val v1 = "turtle-taste-state 1\nmuted\tartist x\nboosted\t\nlessTracks\tyt:gone\nlessArtists\tartist y\u001d2\n" +
          "suppressed\tmix:artist z\nonboarding\t\nmutedClusters\t\nboostedClusters\t\nexploration\t2.0\u001f3.0\nrecent\tyt:1\u001fyt:2"
      val expected = TasteState(
          UserControls(
              mutedArtists = setOf(ArtistKey("artist x")), lessLikeThisTracks = setOf(TrackKey("yt:gone")),
              lessLikeThisArtists = mapOf(ArtistKey("artist y") to 2), suppressedReasons = setOf("mix:artist z"),
          ),
          ExplorationState(2.0, 3.0),
          listOf(TrackKey("yt:1"), TrackKey("yt:2")),
      )
      assertEquals(expected, TasteState.decode(v1))
      assertNull(TasteState.decode(v1.replace("turtle-taste-state 1", "turtle-taste-state 9")))
  }

  @Test
  fun unsafeTextOrAnUnreadableItemCostsOnlyThatItem() {
      val good1 = item(TrackMeta(TrackKey("yt:g1"), "Fine", listOf("A")))
      val good2 = item(bareTrack) // '|' and ',' in a meta key, and an empty (not null) album
      val bad = listOf(
          item(TrackMeta(TrackKey("yt:b1"), "Tab\there", listOf("A"))),
          item(TrackMeta(TrackKey("yt:b2"), "Fine", listOf("A\u001eB"))),
          item(TrackMeta(TrackKey("yt:b3"), "Fine", listOf("A"), album = "\u001c")),
          item(TrackMeta(TrackKey("yt:b4"), "Fine", listOf("A")), Reason.RadioFrom(RadioSeed.Artist(ArtistKey("a")))),
          item(TrackMeta(TrackKey("yt:b5"), "Fine", listOf("A")), Reason.UpNext),
      )
      val shelf = Shelf("more_like:yt:g1", ShelfKind.MORE_LIKE, Reason.BecauseYouReplayed(TrackKey("yt:g1")), listOf(good1) + bad + good2)
      val unsafeShelf = shelf.copy(id = "bad\nid")
      val state = TasteState(kept = listOf(KeptShelves(ShelfCadence.DAILY_MORE_LIKE, 20_000, listOf(shelf, unsafeShelf))))
      assertEquals(listOf(shelf.copy(items = listOf(good1, good2))), TasteState.decode(state.encode())!!.kept.single().shelves)

      // A damaged item line (its key lost the scheme) drops only that item.
      val clean = TasteState(kept = listOf(KeptShelves(ShelfCadence.DAILY_MORE_LIKE, 20_000, listOf(shelf.copy(items = listOf(good1, good2))))))
      val damaged = clean.encode().replace("item\tyt:g1\u001f", "item\tnoscheme\u001f")
      assertEquals(listOf(good2), TasteState.decode(damaged)!!.kept.single().shelves.single().items)
  }

  @Test
  fun unreadableShelvesKeepTheListenersControls() {
      val shelf = Shelf("weekly_discovery:weekly", ShelfKind.WEEKLY_DISCOVERY, Reason.NewForYou, listOf(item(fullTrack)))
      val state = TasteState(
          UserControls(mutedArtists = setOf(ArtistKey("m")), lessLikeThisTracks = setOf(TrackKey("yt:x"))),
          kept = listOf(KeptShelves(ShelfCadence.WEEKLY, 19_997, listOf(shelf))),
      )
      val text = state.encode()
      val withoutShelves = state.copy(kept = emptyList())
      assertEquals(withoutShelves, TasteState.decode(text.replace("shelves\t1", "shelves\t2")), "future shelf format")
      assertEquals(withoutShelves, TasteState.decode(text.replace("kept\tWEEKLY\u001f19997", "kept\tNOT_A_CADENCE\u001fx")), "unknown cadence")
      assertEquals(withoutShelves, TasteState.decode(text.replace("\u001fWEEKLY_DISCOVERY\u001f", "\u001fGONE_KIND\u001f")), "unknown kind")
  }
  ```
  Add `import kotlin.test.assertNull` to `TasteEngineTest.kt`.
- [ ] Run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.TasteEngineTest'`. Expected: compilation fails with `Unresolved reference 'KeptShelves'` and `No parameter with name 'kept'`.
- [ ] Implement:
  - in `KeptShelves.kt`, `data class KeptShelves` and `internal object KeptShelvesCodec` exactly as described under **Wire format**;
  - in `TasteState.kt`, add `val kept: List<KeptShelves> = emptyList()`;
  - in `encode()`, after the `recent` line: `KeptShelvesCodec.encode(kept).forEach { append('\n').append(it) }`. The codec's first line is `shelves\t1`, and it emits that line even when `kept` is empty;
  - in `decode`, pass `kept = runCatching { KeptShelvesCodec.decode(lines.drop(1)) }.getOrDefault(emptyList())`. Leave the existing `fields` map and the controls parsing unchanged: the repeated `kept`/`shelf`/`item` keys only collapse inside that map, and nothing reads them from it;
  - keep `VERSION = "turtle-taste-state 1"`;
  - make the `Surfaces.kt` comment edits listed under **Files**.
- [ ] Run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.TasteEngineTest'`. Expected: PASS, including the existing `tasteStateRoundTripsEveryControl` and `lessLikeThisSurvivesARestartAndKeepsTheTrackOutOfRadio`.
- [ ] Stage for commit (do not commit): `git add turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/KeptShelves.kt turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteState.kt turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/Surfaces.kt turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/TasteEngineTest.kt`

---

### Task 2: Export, import and show kept shelves in the engine

**Files:**
- Modify: `turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt`:
  - line 64: class KDoc, "persist [exportState] (controls, exploration, recent recommendations and kept shelves) between runs";
  - insert `keptShelves()` after `homeShelves()` (lines 351-387);
  - lines 389-390: `refreshShelves` KDoc, removing "(pull to refresh)" and saying it is deliberately not wired to pull-to-refresh, because Turtle's mixes are the day's (personal-home design, W2);
  - lines 496-509: `exportState` / `importState`.
- Test: `turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/HistoryShelvesTest.kt`. Insert after `mixesStayForTheDayAndDiscoveryForTheWeek` (ends at line 275).

**Interfaces:**
- Consumes: `KeptShelves` and `TasteState.kept` (Task 1); the private `stable` map (line 111), `keptLocked` (lines 398-404) and `onRepeatLocked` (lines 835-856).
- Produces:
  ```kotlin
  /**
   * Home's shelves that are already decided for their period (kept or restored), plus On repeat,
   * in [homeShelves] order and with the same filters. Builds nothing and calls nothing upstream,
   * so Home can show Turtle's rows at once after a restart.
   */
  suspend fun keptShelves(): List<Shelf>

  suspend fun exportState(): String  // now also writes `stable`, ordered by ShelfCadence.ordinal
  suspend fun importState(encoded: String): Boolean  // now also restores kept shelves (rules below)
  ```
- Restore rules for `importState`, which the implementer may not change:
  - a group is ignored when `shelves.none { it.items.isNotEmpty() }`. This mirrors `stableLocked`'s "an empty build is retried, not kept" (line 409);
  - a group is ignored when `stable` already holds that cadence: a build made in this run wins over the restored one;
  - otherwise `stable[cadence] = period to shelves`. The raw shelves are restored, not the filtered output, so controls still apply at read time and unmuting brings items back.
  - Restoring a period older than today is harmless: `keptLocked` already ignores it.
- `keptShelves()` uses the same sequence as lines 378-385: `keptLocked(DAILY)`, `keptLocked(DAILY_MORE_LIKE)`, `onRepeatLocked()`, `keptLocked(WEEKLY)`, `keptLocked(DAILY_FRESH)`, `keptLocked(DAILY_REDISCOVER)`, each `.orEmpty()`. It applies the same final `filter`, so a missing cadence just leaves a gap and nothing is built. Extract the shared final filter into a private `visibleLocked(shelves)` used by both functions.

**Steps:**

- [ ] Write the failing tests in `HistoryShelvesTest.kt` after line 275. Add `import app.turtleplayer.core.ArtistKey` and `import app.turtleplayer.core.DAY_MS` if missing (`DAY_MS` is already imported).
  ```kotlin
  private fun List<Shelf>.ofKind(kind: ShelfKind) = filter { it.kind == kind }

  @Test
  fun keptShelvesComeBackAfterARestartUntilTheirPeriodEnds() = runTest {
      clock.now = SyntheticWorld.T0 + 10 * 60 * MINUTE_MS // Monday 10:00 UTC
      val before = engine(upstream = FakeRadio(world)).let { first ->
          first.homeShelves().also { assertTrue(it.ofKind(ShelfKind.DAILY_MIX).isNotEmpty() && it.ofKind(ShelfKind.WEEKLY_DISCOVERY).isNotEmpty()) } to first.exportState()
      }
      val radio = FakeRadio(world)
      val restarted = engine(upstream = radio)
      assertTrue(restarted.importState(before.second))
      assertEquals(before.first, restarted.keptShelves(), "shown before any build")
      assertEquals(before.first, restarted.homeShelves())
      assertEquals(0, radio.calls.size, "restored shelves need no upstream calls")
      assertEquals(
          before.first.flatMap { s -> s.items.map { it.surfaceDetail } },
          restarted.homeShelves().flatMap { s -> s.items.map { it.surfaceDetail } },
          "plays keep their attribution",
      )

      clock.now += DAY_MS // Tuesday, same week
      val tuesday = restarted.homeShelves()
      assertTrue(radio.calls.isNotEmpty(), "a new day rebuilds the mixes")
      assertEquals(before.first.ofKind(ShelfKind.WEEKLY_DISCOVERY), tuesday.ofKind(ShelfKind.WEEKLY_DISCOVERY), "the week's discovery stays")
  }

  @Test
  fun aRestoredShelfStillDropsWhatTheListenerRulesOutLater() = runTest {
      clock.now = SyntheticWorld.T0 + 10 * 60 * MINUTE_MS
      val first = engine()
      val mixes = first.homeShelves().ofKind(ShelfKind.DAILY_MIX)
      first.lessLikeThis(mixes[1].lessLikeThis) // dismissed before the restart
      val restarted = engine()
      assertTrue(restarted.importState(first.exportState()))
      assertTrue(restarted.keptShelves().none { it.id == mixes[1].id }, "a dismissed shelf stays dismissed")

      val mix = restarted.keptShelves().first { it.id == mixes[0].id }
      val seed = (mix.reason as Reason.DailyMix).seedArtist!!
      val other = mix.items.first { it.track.artistKey != seed }
      restarted.updateControls { it.copy(mutedArtists = it.mutedArtists + other.track.artistKey) }
      assertTrue(restarted.keptShelves().flatMap { it.items }.none { it.track.artistKey == other.track.artistKey }, "mutes apply to restored shelves")
      restarted.updateControls { it.copy(mutedArtists = it.mutedArtists - other.track.artistKey) }
      assertTrue(restarted.keptShelves().first { it.id == mix.id }.items.any { it.key == other.key }, "unmuting brings it back")
  }

  @Test
  fun aBuildInThisRunWinsOverRestoredShelves() = runTest {
      clock.now = SyntheticWorld.T0 + 10 * 60 * MINUTE_MS
      val t = world.tracks[0]
      val sentinel = Shelf(
          "daily_mix:artist:sentinel", ShelfKind.DAILY_MIX, Reason.DailyMix(0, listOf(t.artistKey), seedArtist = t.artistKey),
          listOf(QueueItem(t, CandidateSource.LIBRARY, Reason.OnRepeat, SlotKind.FAMILIAR, 1f)),
      )
      val saved = TasteState(kept = listOf(KeptShelves(ShelfCadence.DAILY, clock.now.floorDiv(DAY_MS), listOf(sentinel)))).encode()

      val fresh = engine()
      assertTrue(fresh.importState(saved))
      assertEquals(listOf(sentinel.id), fresh.keptShelves().ofKind(ShelfKind.DAILY_MIX).map { it.id })

      val built = engine()
      val ids = built.homeShelves().ofKind(ShelfKind.DAILY_MIX).map { it.id }
      assertTrue(built.importState(saved))
      assertEquals(ids, built.homeShelves().ofKind(ShelfKind.DAILY_MIX).map { it.id }, "rows never jump to restored shelves")
  }

  @Test
  fun aRestoredDayWithNothingInItIsRebuilt() = runTest {
      clock.now = SyntheticWorld.T0 + 10 * 60 * MINUTE_MS
      val empty = Shelf("daily_mix:artist:x", ShelfKind.DAILY_MIX, Reason.DailyMix(0, listOf(ArtistKey("x")), seedArtist = ArtistKey("x")), emptyList())
      val saved = TasteState(kept = listOf(KeptShelves(ShelfCadence.DAILY, clock.now.floorDiv(DAY_MS), listOf(empty)))).encode()
      val radio = FakeRadio(world)
      val engine = engine(upstream = radio)
      assertTrue(engine.importState(saved))
      assertTrue(engine.homeShelves().ofKind(ShelfKind.DAILY_MIX).isNotEmpty())
      assertTrue(radio.calls.isNotEmpty(), "an empty restored day is built, not pinned empty")
  }
  ```
  `engine()` is the file's helper at lines 46-54. It uses the default `localDay` (UTC day), so `clock.now.floorDiv(DAY_MS)` is today's period.
- [ ] Run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.HistoryShelvesTest'`. Expected: compilation fails with `Unresolved reference 'keptShelves'`. Once that exists, `keptShelvesComeBackAfterARestartUntilTheirPeriodEnds` fails until `exportState`/`importState` carry `stable`.
- [ ] Implement in `TasteEngine.kt`:
  - `exportState()`: `TasteState(controls, explorationState, recent.toList(), stable.entries.sortedBy { it.key.ordinal }.map { (c, p) -> KeptShelves(c, p.first, p.second) }).encode()`;
  - `importState(...)`: after the existing assignments, apply the restore rules above;
  - add `keptShelves()` and `visibleLocked`;
  - update the two KDocs.
- [ ] Run `./gradlew -p turtle/engines :turtle-taste:jvmTest --tests 'app.turtleplayer.taste.HistoryShelvesTest'`. Expected: PASS, including the existing `mixesStayForTheDayAndDiscoveryForTheWeek`.
- [ ] Run `./gradlew -p turtle/engines check`. Expected: PASS. `OfflineReplayTest` passes unchanged, because ranking is untouched.
- [ ] Stage for commit (do not commit): `git add turtle/engines/taste/src/commonMain/kotlin/app/turtleplayer/taste/TasteEngine.kt turtle/engines/taste/src/commonTest/kotlin/app/turtleplayer/taste/HistoryShelvesTest.kt`

---

### Task 3: A `shelf_song` table in our database

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/SqlitePlayEventStore.kt`:
  - `migrate()`: add a `version < 5` step after line 71;
  - insert `saveShelfSongs` / `loadShelfSongs` after `loadCoListening` (ends at line 166).
- Test: `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/SqlitePlayEventStoreTest.kt`. Insert after line 79.

**Interfaces:**
- Consumes: the existing `db {}` and `transaction {}` helpers (lines 168-191).
- Produces:
  ```kotlin
  /** Replaces the saved songs of Home's Turtle shelves: (YouTube id, upstream SongItem JSON). */
  suspend fun saveShelfSongs(songs: List<Pair<String, String>>)

  /** The saved shelf songs' JSON, in the order they were saved. */
  suspend fun loadShelfSongs(): List<String>
  ```
- Schema: W2 takes the next free `user_version`, which is **5** unless an earlier workstream already claimed it, in which case take the next one and say so in the PR. **W9 must use the version after W2's (6).**
  ```kotlin
  if (version < 5) {
      // Songs on Home's Turtle shelves, so the day's and week's shelves stay playable after a restart.
      connection.execSQL("CREATE TABLE IF NOT EXISTS shelf_song (id TEXT PRIMARY KEY, json TEXT NOT NULL)")
      connection.execSQL("PRAGMA user_version = 5")
  }
  ```
  `saveShelfSongs` does `DELETE FROM shelf_song`, then `INSERT OR REPLACE` each row inside one `transaction {}`. It mirrors `saveCoListening` (lines 129-151). `loadShelfSongs` runs `SELECT json FROM shelf_song ORDER BY rowid`.

**Steps:**

- [ ] Write the failing test after line 79:
  ```kotlin
  @Test
  fun shelfSongsSurviveAReopenAndAnUpgradeFromVersionFour() = runTest {
      val file = File.createTempFile("turtle", ".db").apply { delete(); deleteOnExit() }
      SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath).apply { append(event("old", 500)); close() }
      // Back to a version 4 database, as installed before shelf songs were kept.
      JdbcSQLiteDriver().open(file.absolutePath).apply {
          execSQL("DROP TABLE shelf_song")
          execSQL("PRAGMA user_version = 4")
          close()
      }

      val store = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
      store.saveShelfSongs(listOf("a" to """{"id":"a"}""", "b" to """{"id":"b"}"""))
      store.saveShelfSongs(listOf("b" to """{"id":"b2"}""", "c" to """{"id":"c"}""")) // replaces, never accumulates
      store.close()

      val reopened = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
      assertEquals(listOf("""{"id":"b2"}""", """{"id":"c"}"""), reopened.loadShelfSongs())
      assertEquals(listOf("yt:old"), reopened.since(0).map { it.trackKey.value })
      reopened.close()
  }
  ```
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.SqlitePlayEventStoreTest'`. Expected: compilation fails with `Unresolved reference 'saveShelfSongs'`.
- [ ] Implement the migration step and the two functions as specified.
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.SqlitePlayEventStoreTest'`. Expected: PASS (3 tests).
- [ ] Stage for commit (do not commit): `git add turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/SqlitePlayEventStore.kt turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/SqlitePlayEventStoreTest.kt`

---

### Task 4: Pin shelf songs in `SongCache`

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/SongCache.kt` (lines 7-33): add a pinned shelf map, look it up in `get`, add `replaceShelf`, and update the KDoc.
- Test: covered by Task 5's test (Review Focus 5). No separate test file.

**Interfaces:**
- Produces:
  ```kotlin
  /**
   * Songs on Home's Turtle shelves, kept out of the least-recently-used part so a week's Weekly
   * Discovery stays playable however many radio pages load meanwhile. Replaces the previous set.
   */
  suspend fun replaceShelf(songs: Collection<SongItem>)
  ```
  `get(id)` becomes `library[id] ?: shelf[id] ?: recent[id]`. `putAll` and `replaceLibrary` are unchanged.

**Steps:**

- [ ] Implement as specified. There is no test of its own; Task 5's test fails without it.
- [ ] Run `./gradlew :turtle-bridge:compileKotlinDesktop`. Expected: BUILD SUCCESSFUL.
- [ ] Stage for commit (do not commit): `git add turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/SongCache.kt`

---

### Task 5: Restore and save shelves in `TurtleHomeShelves`

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleHomeShelves.kt`:
  - lines 33-39: class KDoc, adding "kept shelves and their songs survive restarts";
  - lines 40-44: constructor;
  - lines 50-67: `start`;
  - lines 69-75: `refresh`, split into publish and persist;
  - add `restore` and `persist`.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt`:
  - lines 244-250: remove `PREF_TASTE_STATE` from the private companion;
  - after line 251, add the top-level `internal const val PREF_TASTE_STATE = "taste_state"`. The value is unchanged, so states saved today still load;
  - lines 186-195: `saveTasteState` / `restoreTasteState` use it, with no other change.
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt` line 112: `single { TurtleHomeShelves(get(), get(), get(), get()) }`. The fourth argument is `SqlitePlayEventStore`.
- Create: `turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/TurtleHomeShelvesTest.kt`

**Interfaces:**
- Consumes: `TasteEngine.keptShelves()`, `exportState()` and `importState()` (Task 2); `SqlitePlayEventStore.saveShelfSongs` / `loadShelfSongs` / `setPref` / `pref` (Task 3); `SongCache.replaceShelf` (Task 4); `SongItem.serializer()` (upstream model, already used by `CoListeningSync.kt:71,86`).
- Produces (W1 restructures this file next and must keep this behavior):
  ```kotlin
  class TurtleHomeShelves(
      private val taste: TasteEngine,
      private val events: PlayEventStore,
      private val cache: SongCache,
      private val db: SqlitePlayEventStore,
  ) {
      var shelves: List<HomeShelf>                 // unchanged
      fun start(scope: CoroutineScope)             // restore(), publish keptShelves(), refresh(), then the existing loops
      suspend fun refresh()                        // no-op until restore() has run; then build, publish, persist
      fun mergeInto(upstream: List<HomeShelf>): List<HomeShelf>  // unchanged

      /** Loads the saved shelf songs into the cache, pinned. Until this has run, refresh() does nothing. */
      internal suspend fun restore()

      /**
       * Pins the songs of [fromEngine]'s items, saves them when the set changed, then saves the
       * taste state (which now holds the kept shelves). Called after every build, so a kill right
       * after a build, with no play in between, keeps the day's shelves.
       */
      internal suspend fun persist(fromEngine: List<Shelf>)
  }

  internal const val PREF_TASTE_STATE = "taste_state"   // TurtleRuntime.kt, top level
  ```
- Decisions:
  - One `refreshLock` (line 48) guards `restored`, `savedSongIds`, publishing and persisting. `kotlinx.coroutines.sync.Mutex` is not reentrant, so the public and internal functions take the lock, and they call private `publishLocked(fromEngine)` / `persistLocked(fromEngine)` that do not.
  - `restore()` does these steps:
    1. `db.loadShelfSongs()`;
    2. decodes each row with `Json { ignoreUnknownKeys = true }` and `SongItem.serializer()`, skipping rows that fail, as `CoListeningSync.load` does;
    3. calls `cache.replaceShelf(songs)`;
    4. sets `savedSongIds` to their ids, then `restored = true`.
    
    A failure is logged with `TurtleLog.error` and still sets `restored = true`: Home must never stay empty because the table is unreadable. A `CancellationException` is rethrown.
  - `persistLocked(fromEngine)` does these steps:
    1. `songs = fromEngine.flatMap { it.items }.mapNotNull { it.track.youtubeId }.distinct().mapNotNull { cache.get(it) }`;
    2. `cache.replaceShelf(songs)`;
    3. when `songs` ids != `savedSongIds`, `db.saveShelfSongs(songs.map { it.id to json.encodeToString(SongItem.serializer(), it) })`;
    4. always `db.setPref(PREF_TASTE_STATE, taste.exportState())`.
    
    Failures are logged and swallowed, so publishing never waits on them. Songs of items hidden by a mute at build time are not pinned. That is acceptable, because unmuting the same day is rare.
  - `start(scope)`, first launch: `restore()`; then `refreshLock.withLock { publishLocked(taste.keptShelves()) }` (Turtle's rows from disk, no network); then `refresh()`; then the existing `events.newEvents` loop. The timer launch (lines 61-66) is unchanged.
  - `refresh()` is `refreshLock.withLock { if (!restored) return@withLock; val fromEngine = taste.homeShelves(); publishLocked(fromEngine); persistLocked(fromEngine) }`. That keeps `YouTubeFreshReleases.refreshHome()` (`YouTubeFreshReleases.kt:302-304`) from building and saving defaults before startup has restored the state.
  - `TurtleRuntime.start()` ordering, which is the startup race in Review Focus 4. This is done in Task 6.

**Steps:**

- [ ] Write the failing test `TurtleHomeShelvesTest.kt`. It stays below `refresh()`/`getString` and calls `restore`/`persist` directly, so it does not depend on compose resources at test time and does not open the real `turtle.db`:
  ```kotlin
  package app.turtleplayer.bridge

  // imports: Clock, HOUR_MS, TrackMeta (core); EndReason, InMemoryPlayEventStore, PlayEvent, StartReason, Surface (events);
  // InMemoryEmbeddingStore, InMemoryTrackCatalog, RecommendationSource, TasteEngine (taste);
  // JdbcSQLiteDriver, ArtistTiny, SongItem (shared); runTest; File; Random; kotlin.test.*

  class TurtleHomeShelvesTest {
      private val now = 1_800_000_000_000L
      private fun song(id: String, artist: String) =
          SongItem(id = id, title = "Song $id", thumbnail = null, artists = listOf(ArtistTiny(null, artist)), album = null, duration = 200)
      private val heard = (0 until 12).map { song("h$it", "Artist ${it / 4}") }
      private val radioSongs = (0 until 40).map { song("r$it", "Radio ${it % 10}") }
      private val history = heard.flatMapIndexed { i, s ->
          (1..3).map { k ->
              PlayEvent(
                  track = s.toTrackMeta(), sessionId = "s", position = 0, surface = Surface.AUTOPLAY, startReason = StartReason.AUTOPLAY,
                  endReason = EndReason.COMPLETED, startedAtMs = now - (k * 24 + i) * HOUR_MS, msPlayed = 200_000, durationMs = 200_000,
              )
          }
      }

      /** YouTube radio as the bridge wires it: songs land in the cache, metas go to the engine. */
      private class Radio(private val cache: SongCache, private val songs: List<SongItem>) : RecommendationSource {
          var calls = 0
          override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> {
              calls++
              cache.putAll(songs)
              return songs.take(limit).map { it.toTrackMeta() }
          }
      }

      private suspend fun engine(upstream: RecommendationSource) = TasteEngine(
          events = InMemoryPlayEventStore().also { it.appendAll(history) }, store = InMemoryEmbeddingStore(),
          catalog = InMemoryTrackCatalog(heard.map { it.toTrackMeta() }), upstream = upstream, clock = Clock { now }, random = Random(7),
      )

      @Test
      fun shelfSongsComeBackAfterARestartWithoutTheNetwork() = runTest {
          val file = File.createTempFile("turtle", ".db").apply { delete(); deleteOnExit() }

          // First run: shelves built from history and radio, then the process dies before any play.
          val db1 = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
          val cache1 = SongCache().also { it.replaceLibrary(heard) }
          val engine1 = engine(Radio(cache1, radioSongs))
          val home1 = TurtleHomeShelves(engine1, InMemoryPlayEventStore(), cache1, db1)
          home1.restore()
          val built = engine1.homeShelves()
          home1.persist(built)
          val ids = built.flatMap { it.items }.mapNotNull { it.track.youtubeId }.distinct()
          assertTrue(ids.any { it.startsWith("r") }, "a kept shelf holds never-heard radio songs: $ids")
          val songs = ids.map { assertNotNull(cache1.get(it)) }
          db1.close()

          // New runtime: library songs only, the network down, and a long session flooding the LRU.
          val db2 = SqlitePlayEventStore(JdbcSQLiteDriver(), file.absolutePath)
          val cache2 = SongCache(capacity = 1).also { it.replaceLibrary(heard) }
          val offline = object : RecommendationSource {
              var calls = 0
              override suspend fun related(seed: TrackMeta, limit: Int): List<TrackMeta> {
                  calls++
                  error("offline")
              }
          }
          val engine2 = engine(offline)
          assertTrue(engine2.importState(assertNotNull(db2.pref(PREF_TASTE_STATE))))
          TurtleHomeShelves(engine2, InMemoryPlayEventStore(), cache2, db2).restore()
          cache2.putAll((0 until 50).map { song("f$it", "Filler") })

          val kept = engine2.keptShelves()
          assertEquals(built, kept)
          assertEquals(songs, kept.flatMap { it.items }.mapNotNull { it.track.youtubeId }.distinct().map { assertNotNull(cache2.get(it), it) })
          assertEquals(0, offline.calls)
          db2.close()
      }
  }
  ```
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.TurtleHomeShelvesTest'`. Expected: compilation fails with `Too many arguments for constructor TurtleHomeShelves` and `Unresolved reference 'restore'`.
- [ ] Implement the constructor, `restore`, `persist`, `publishLocked`/`persistLocked`, the new `start`/`refresh` and the `PREF_TASTE_STATE` move, as decided above, plus the `TurtleModule.kt:112` wiring.
- [ ] Run `./gradlew :turtle-bridge:desktopTest --tests 'app.turtleplayer.bridge.TurtleHomeShelvesTest'`. Expected: PASS. Temporarily reverting Task 4's `shelf[id]` lookup must make it fail on the `cache2.get` assertion, because the filler evicts the restored songs. Check this once, then restore the lookup.
- [ ] Stage for commit (do not commit): `git add turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleHomeShelves.kt turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleModule.kt turtle/bridge/src/desktopTest/kotlin/app/turtleplayer/bridge/TurtleHomeShelvesTest.kt`

---

### Task 6: Start Home only after the taste state is restored

**Files:**
- Modify: `turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt`:
  - line 46: add a field;
  - lines 60-67: complete it after the restore;
  - lines 81-85: await it before `home.start`;
  - lines 187-190: `saveTasteState` awaits it first.

**Interfaces:**
- Consumes: `TurtleHomeShelves.start` (Task 5).
- Produces: none public. `private val tasteRestored = CompletableDeferred<Unit>()` (import `kotlinx.coroutines.CompletableDeferred`).
- Decisions:
  - In the first background launch (line 60): `try { restoreTasteState() } finally { tasteRestored.complete(Unit) }`, then the rest of the block as today. An unreadable state therefore never blocks Home.
  - In the Home launch (lines 81-85): after `while (!librarySync.isReady()) delay(500)`, call `tasteRestored.await()`, then `home.start(scope)`. `home.start` then restores the shelf songs (`restore()`) before it publishes. Together this keeps Home from building fresh shelves before the restored ones are in place, so rows don't jump, and from rendering shelves whose songs aren't in the cache yet (fewer than `MIN_ITEMS`, so hidden).
  - `saveTasteState()` (lines 186-190) starts with `tasteRestored.await()`. Otherwise a play event or a crash-resume play recorded at startup, before the restore has read the pref, would overwrite the saved state (now including the day's shelves) with defaults. `lessLikeThis` saves through the same function, so it waits too. That is harmless, because the restore takes milliseconds.
  - Overlap: W3 does not edit `TurtleRuntime`. W9 then adds its hidden-record load right after this `try`/`finally`, and W1 adds `homeRows.start(scope)` after that; both rely on `tasteRestored` being completed first.

**Steps:**

- [ ] This is wiring with no new test. The ordering's effect on the engine is covered by Task 2's `aBuildInThisRunWinsOverRestoredShelves`, and the save-after-build by Task 5.
- [ ] Implement as decided.
- [ ] Run `./gradlew :turtle-bridge:desktopTest`. Expected: PASS for every bridge test, including the existing `TurtleRuntimeTest`, which boots the runtime through Koin.
- [ ] Stage for commit (do not commit): `git add turtle/bridge/src/commonMain/kotlin/app/turtleplayer/bridge/TurtleRuntime.kt`

---

### Task 7: Verify the workstream

**Files:** none changed.

**Steps:**

- [ ] `./gradlew -p turtle/engines check`. Expected: PASS. Note `OfflineReplayTest`'s metrics in the PR and compare them with the run before W2 (within 2%).
- [ ] `./gradlew :turtle-bridge:desktopTest`. Expected: PASS.
- [ ] `./gradlew :turtle-bridge:ktlintCheck`. Expected: no violations in lines W2 added. Report any violation that was already there; don't fix it.
- [ ] `./gradlew :desktopApp:compileKotlinJvm`. Expected: BUILD SUCCESSFUL.
- [ ] `./gradlew :androidApp:compileDebugKotlin`. Expected: BUILD SUCCESSFUL.
- [ ] `git status --short`. Expected: only `turtle/` paths, and none under `turtle/HOOKS.md` (0 hook lines).
- [ ] Device checks, not automated (report them as pending if not done):
  - on the OnePlus, open Home, note the mixes, force-stop the app (`adb shell am force-stop` on the debug package from the phone test setup) and reopen. The same mixes and Weekly Discovery show at once, with airplane mode on;
  - pull to refresh: Turtle's rows stay the same.
- [ ] Stage for commit (do not commit): nothing new. Every file was staged in Tasks 1-6. The suggested commit messages, for the owner to approve, are `feat(turtle-taste): keep Home's day and week shelves across restarts` and `feat(turtle-bridge): keep shelf songs playable after a restart`.

## Interfaces other workstreams may use

- From W2, for W1 and W9:
  - `TasteEngine.keptShelves(): List<Shelf>`: Turtle's rows that are already decided, with no network. W1 can lay them out at first paint.
  - Restored `QueueItem`s keep `source`, `slot` and `reason`, so `QueueItem.surfaceDetail` is stable across restarts for W9's attribution.
  - `TurtleHomeShelves(taste, events, cache, db: SqlitePlayEventStore)`.
  - `refresh()` is a no-op until `restore()`; `internal restore()`; `internal persist(fromEngine: List<Shelf>)`. W1 must keep `start()` calling `restore()` → publish `keptShelves()` → `refresh()`, and must keep `persist` after every build.
- From W2, for W9: `SqlitePlayEventStore` `user_version` 5 is taken by `shelf_song`. W9's events migration uses 6.
- From W2, for W9: `publishLocked(fromEngine)` is the single place a `Shelf` becomes a `HomeShelf`, on first paint (restored `keptShelves()`) and on every `refresh()`. W9 records its engine-shelf map there.
- From W2: `SongCache.replaceShelf(songs: Collection<SongItem>)`, and the top-level `internal const val PREF_TASTE_STATE = "taste_state"` in `TurtleRuntime.kt`.
- W2 consumes nothing from W1, W3 or W9.

## Self-review against the spec

- "The `stable` map becomes part of the saved taste state" is covered by Tasks 1-2.
- "Track meta, including the artist key": `artists` are written verbatim, and Task 2 tests a mute after restore.
- "Source and slot" and "reason": these are item fields. Task 2 checks `surfaceDetail` after restore, and Task 1 checks every reason and its key.
- "Shelf id, kind, reason and period" is covered by the `shelf` and `kept` lines.
- "Hand-written encoder, versioned, old state still loads": the existing encoder is extended, with a `shelves\t1` payload version and an unchanged header (Task 1 `versionOneStateWithoutShelvesStillLoads`).
- "Bridge persists `SongItem`s in its own SQLite": Tasks 3-5. "Home shows Turtle's rows before any network call after a restart": `keptShelves()` is published first in Task 5, and the songs resolve offline in Task 5's test.
- "Pull-to-refresh rescores YouTube's rows, Turtle's stay; `refreshShelves` stays unused": nothing is wired, and the KDoc is updated in Task 2.
- "Surfaces.kt Fresh for you comment, daily" is Task 1.
- Spec tests: the engine round trip (same day, then next day) is Task 2; the bridge desktop test for persisted songs after a new runtime is Task 5.
