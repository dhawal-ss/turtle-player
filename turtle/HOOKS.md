# Hook ledger

Every line we change in an upstream (Metrolist) file is listed here, with the reason. Everything
else we build lives under `turtle/` or in new files. CI (`turtle/scripts/check-hooks.sh`) fails a PR
that edits an upstream path without also updating this file.

Budget: **under 30 hook lines in total.** Current total: **4**.

| # | File | Lines | What | Why | Owner |
| --- | --- | --- | --- | --- | --- |
| 1 | `settings.gradle.kts` | 1 | `includeBuild("turtle/engines")` at the end | Makes our engine modules (`app.turtleplayer:turtle-*`) resolvable by the app modules and `:turtle-bridge` | KMP lead |
| 2 | `AGENTS.md` | 1 | Pointer to `turtle/AGENTS.md` at the top | Upstream's agent rules (test policy, rejected features) do not apply to our modules; agents must read ours first | KMP lead |
| 3 | `settings.gradle.kts` | 2 | `include(":turtle-bridge")` and its `projectDir` | Registers the bridge, the only Turtle module that depends on upstream `:shared` | KMP lead |

## Planned hooks (not yet applied)

These are the integration points found while mapping upstream on 20 Nov 2026. Each becomes a row
above when it lands. Paths are relative to `shared/src/commonMain/kotlin/com/metrolist/shared/`
unless stated.

| Seam | Upstream location | Planned hook |
| --- | --- | --- |
| `PlaybackEvents` (Android) | `androidApp/.../playback/MusicService.kt:515`, next to `AndroidListeningHistory(...)` | `TurtleBridge.observePlayer(serviceScope, player.currentSong, player.isPlaying, player.onEOFPlaybackInstance)` (1 line) |
| `PlaybackEvents` (desktop) | `desktopApp/.../state/playback/PlayerState.kt`, next to the `player.onEOFPlaybackInstance` collector (~2048) | Same call with the desktop `MusicPlayer` flows (1 line) |
| Koin wiring | `androidApp/.../App.kt:246-249`, `desktopApp/.../Main.kt:981-987` | Append `turtleModule` after upstream modules so our bindings override (1 line each) |
| Home shelves | `viewmodels/HomeViewModel.kt:200` constructs `HomeShelfCache()` directly | `HomeShelfBuilder` is an `object`, not in Koin; needs a 1-line delegation to our shelf provider |
| Autoplay / radio | `RadioRecommendationHistory` constructed in `AndroidQueueCoordinator.kt:94`, `PlayerState.kt:1129` | Route `select(...)` through the taste engine (1 line each) |
| Search | `viewmodels/SearchViewModel.kt` (constructed directly by Android wrapper and desktop) | Prefer a Koin override of the Android wrapper; desktop needs 1 line |
| Library search | `viewmodels/LibraryViewModel.kt:993` `contentMatchedSongs(...)` (SQL `LIKE`) | 1-line delegation to `:turtle-search` local index |
| App identity | `androidApp/build.gradle.kts:255` `applicationId`, `desktopApp/build.gradle.kts:738` `packageName` | Read from `turtle/branding.properties` (2 lines) |
| Update channel | `androidApp/.../update/AppUpdateManager.kt:122`, `desktopApp/.../update/DesktopUpdateManager.kt:42` | Point at our releases (2 lines) |
