# Hook ledger

Every line we change in an upstream (Metrolist) file is listed here, with the reason. Everything
else we build lives under `turtle/` or in new files. CI (`turtle/scripts/check-hooks.sh`) fails a PR
that edits an upstream path without also updating this file.

Budget: **under 30 hook lines in total**, counting the import lines a hook needs.
Current total: **21**.

Paths below abbreviate `androidApp/src/main/kotlin/com/metrolist/android/` as `android/`,
`desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/` as `desktop/`, and
`shared/src/commonMain/kotlin/com/metrolist/shared/` as `shared/`.

| # | File | Lines | What | Why | Owner |
| --- | --- | --- | --- | --- | --- |
| 1 | `settings.gradle.kts` | 1 | `includeBuild("turtle/engines")` at the end | Makes our engine modules (`app.turtleplayer:turtle-*`) resolvable by the app modules and `:turtle-bridge` | KMP lead |
| 2 | `AGENTS.md` | 1 | Pointer to `turtle/AGENTS.md` at the top | Upstream's agent rules (test policy, rejected features) do not apply to our modules; agents must read ours first | KMP lead |
| 3 | `settings.gradle.kts` | 2 | `include(":turtle-bridge")` and its `projectDir` | Registers the bridge, the only Turtle module that depends on upstream `:shared` | KMP lead |
| 4 | `shared/viewmodels/LibraryViewModel.kt` | 3 | `libraryContentSearchOverride` (comment + declaration) and one call at the top of `contentMatchedSongs` | Replaces the SQL `LIKE` library search with our ranked, typo-tolerant index. Shared cannot depend on the bridge, so the bridge sets this function at startup; when unset or not ready, upstream's query runs | Search engineer |
| 5 | `androidApp/build.gradle.kts`, `desktopApp/build.gradle.kts` | 2 | `implementation(project(":turtle-bridge"))` | The apps load our Koin module and call the hooks | KMP lead |
| 6 | `android/App.kt` | 2 | `turtleModule` appended to `startKoin { modules(...) }`, plus import | Our bindings load after upstream's, so they override where they overlap | KMP lead |
| 7 | `desktop/Main.kt` | 2 | Same for desktop `startKoin` | Same | KMP lead |
| 8 | `android/playback/MusicService.kt` | 2 | `TurtleBridge.observePlayer(serviceScope, player.currentSong, player.isPlaying, player.onEOFPlaybackInstance)` after `AndroidListeningHistory(...)`, plus import | Feeds the listening-event log; also starts our runtime (index sync, taste updates) | Recommendations engineer |
| 9 | `desktop/state/playback/PlayerState.kt` | 2 | Same call before the `player.onEOFPlaybackInstance` collector, plus import | Same, for desktop | Recommendations engineer |
| 10 | `android/playback/AndroidQueueCoordinator.kt` | 2 | `?.let { TurtleBridge.rankRadio(it) }` on the result of `recommendationHistory.select(...)` in `freshRadioPage`, plus import | Autoplay: the taste engine orders YouTube's already-filtered radio candidates; upstream's filters (blocked, queued, songs-only) stay authoritative | Recommendations engineer |
| 11 | `desktop/state/playback/DesktopQueueRadio.kt` | 2 | Same on the desktop `recommendationHistory.select(...)` result, plus import | Same, for desktop | Recommendations engineer |

Every hook fails open: if Koin has no Turtle runtime, our code throws, or ranking misses its
1.5-second budget, upstream's own behavior runs unchanged.

## Planned hooks (not yet applied)

| Seam | Upstream location | Planned hook |
| --- | --- | --- |
| Home shelves | `shared/viewmodels/HomeViewModel.kt:200` constructs `HomeShelfCache()` directly | `HomeShelfBuilder` is an `object`, not in Koin; needs a 1-line delegation to our shelf provider, plus our own string resources for shelf titles and reasons |
| Search screen | `shared/viewmodels/SearchViewModel.kt` (constructed directly by the Android wrapper and desktop) | Swap in `SearchEngine` results; needs a design for the intent-aware top result first |
| App identity | `androidApp/build.gradle.kts:255` `applicationId`, `desktopApp/build.gradle.kts:738` `packageName` | Read from `turtle/branding.properties` (2 lines) |
| Update channel | `android/update/AppUpdateManager.kt:122`, `desktop/update/DesktopUpdateManager.kt:42` | Point at our releases (2 lines) |

The planned hooks need about 8 more lines, which leaves little room under the budget. Prefer
consolidating (for example one shared override point that several hooks reuse) over adding new
call sites.
