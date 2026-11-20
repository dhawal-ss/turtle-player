# Hook ledger

Every line we change in an upstream (Metrolist) file is listed here, with the reason. Everything
else we build lives under `turtle/` or in new files. CI (`turtle/scripts/check-hooks.sh`) fails a PR
that edits an upstream path without also updating this file.

Budget: **under 30 hook lines in total**, counting the import lines a hook needs.
Current total: **27** hook lines, plus about 55 lines of behavior patches below.

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
| 20 | `shared/api/YouTubeRepository.kt` | 3 | `searchSummaryOverride` (comment + declaration) and its use where `getSearchSummary` returns the online page | Search: our engine re-ranks YouTube's page together with the library (typo-tolerant, deduplicated, personal, intent-aware top result) inside upstream's section shape, so the UI and its strings are unchanged. Videos, playlists and podcasts pass through. Fails open to upstream's page | Search engineer |
| 21 | `shared/ui/screens/HomeShelfBuilder.kt` | 3 | `homeShelvesOverride` (comment + declaration) and its use where `HomeShelfCache.build` returns | Home: our shelves (artist mixes or Daily Mixes, More like X, On repeat, Weekly discovery, Fresh for you, Rediscover) go after Speed Dial. They live in Compose state, so Home recomposes when they refresh | Recommendations engineer |

## Behavior patches

Deliberate improvements to upstream code itself, not integration points. We improve our fork
directly rather than waiting on upstream; each patch stays as small as possible and carries a
`Turtle:` comment pointing here, so merge conflicts are easy to spot and re-apply. Patches do not
count toward the hook budget but should stay rare.

| # | File | Lines | What | Why | Owner |
| --- | --- | --- | --- | --- | --- |
| 12 | `desktop/state/playback/PlayerState.kt` | 2 | Drop `!selection.enabled` from the next-track pre-cache gate, plus comment | Resolve the next track 5 s into the current one even with song caching off (desktop default), so a manual skip reuses a warm stream instead of a full resolution. Byte prefetch still follows the cache setting. From Echo Music's `preloadUpcomingItems` | Windows and performance engineer |
| 13 | `shared/src/jvmSharedMain/.../playback/PlaybackPreCache.kt` | 4 | Clamp the pre-cache chunk to `resource.rangeChunkSizeBytes` | The fixed 1 MB chunk ignored per-client limits; some clients (ANDROID_VR, TVHTML5_SIMPLY) refuse ranges over 512 KB. Matches what the main player stream already does | Windows and performance engineer |
| 14 | `android/playback/ExoPlaybackPlayer.kt` | 1 | `createPlayer` default `bufferForPlaybackMs` 2000 to 750 | Audio starts sooner; switches already use 500 ms. Echo Music uses 150 ms. Compiled; on-device check pending | KMP lead |
| 15 | `shared/src/jvmSharedMain/.../playback/ffmpeg/HttpRangeInputStream.kt` | ~45 | For streams up to 32 MB (audio): fetch the whole file in the background (3 requests at a time, stopping at the first refusal), fill gaps before the play position, keep every chunk instead of the last 4; a running fetch is not restarted at each chunk edge | Seeks, replays and skip-backs are served from memory instead of a new request to YouTube. Upstream kept about 512 KB and read 3 chunks ahead. Covered by `WholeTrackStreamTest` in `:turtle-bridge` | Windows and performance engineer |
| 16 | `desktop/state/playback/PlayerState.kt` | 3 | Pre-open the next track once the current one passes 1.5 s, not only in its last 30 s (`TURTLE_EARLY_PRELOAD_POSITION_MS`) | Manual skips swap to an already-open, buffering player (`transitions.promoteNext`) instead of resolving and opening cold. Crossfade still waits for the real end | Windows and performance engineer |
| 17 | `android/playback/AndroidPlaybackPolicies.kt` | 2 | `NEXT_TRACK_PRELOAD_DELAY_MS` 5000 to 1500, plus comment | Android pre-resolves and queues the next track 1.5 s into a song instead of 5 s, mirroring desktop #16 | KMP lead |
| 18 | `android/playback/ExoPlaybackPlayer.kt` (`buildDirectDataSourceFactory`) | 2 | `prefetchAhead = !video` on the direct audio `HttpRangeInputStream` | Turns on the whole-track background fetch from #15 for Android audio, so forward seeks are local too (backward seeks already were) | KMP lead |
| 19 | `android/playback/ExoPlaybackPlayer.kt` (`createPlayer`) | 2 | `preloadConfiguration = ExoPlayer.PreloadConfiguration(5 s)` | With crossfade off (default) the next track sits unloaded in the same ExoPlayer playlist; Media3 now loads it ahead so a skip starts from buffered audio | KMP lead |

The desktop stream-resolver warm-up at startup lives in `:turtle-bridge` (`TurtleRuntime`), so it
needs no upstream edit.

Every hook fails open: if Koin has no Turtle runtime, our code throws, or ranking misses its
1.5-second budget, upstream's own behavior runs unchanged.

## Planned hooks (not yet applied)

| Seam | Upstream location | Planned hook |
| --- | --- | --- |
| App identity | `androidApp/build.gradle.kts:255` `applicationId`, `desktopApp/build.gradle.kts:738` `packageName` | Read from `turtle/branding.properties` (2 lines) |
| Update channel | `android/update/AppUpdateManager.kt:122`, `desktop/update/DesktopUpdateManager.kt:42` | Point at our releases (2 lines) |

The remaining planned hooks need about 4 more lines, which reaches the budget. Prefer
consolidating (for example one shared override point that several hooks reuse) over adding new
call sites.
