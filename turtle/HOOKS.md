# Hook ledger

Every line we change in an upstream (Metrolist) file is listed here, with the reason. Everything
else we build lives under `turtle/` or in new files. CI (`turtle/scripts/check-hooks.sh`) fails a PR
that edits an upstream path without also updating this file.

Budget: **under 55 hook lines in total**, counting the import lines a hook needs. (Raised from 30 on
20 Nov 2026 when we chose to replace upstream's UI with Shell, from 40 the same day when the
product owner asked that the whole app, player surfaces and dialogs included, use Shell's design,
and from 50 the same day because the product owner scrapped Listen Together (20 Nov 2026), which
needs four lines (#41 to #43) to switch off without deleting upstream's ~200 files;
every further screen takeover goes
through `shared/.../turtle/TurtleUiSlots.kt` as a single line with fully qualified names, so it
needs no import lines.)
Current total: **51** hook lines, plus about 55 lines of behavior patches below.

Paths below abbreviate `androidApp/src/main/kotlin/com/metrolist/android/` as `android/`,
`desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/` as `desktop/`, and
`shared/src/commonMain/kotlin/com/metrolist/shared/` as `shared/`.

| # | File | Lines | What | Why | Owner |
| --- | --- | --- | --- | --- | --- |
| 1 | `settings.gradle.kts` | 1 | `includeBuild("turtle/engines")` at the end | Makes our engine modules (`app.turtleplayer:turtle-*`) resolvable by the app modules and `:turtle-bridge` | KMP lead |
| 2 | `AGENTS.md` | 1 | Pointer to `turtle/AGENTS.md` at the top | Upstream's agent rules (test policy, rejected features) do not apply to our modules; agents must read ours first | KMP lead |
| 3 | `settings.gradle.kts` | 2 | `include(":turtle-bridge")` and its `projectDir` | Registers the bridge, the only Turtle module that depends on upstream `:shared` | KMP lead |
| 4 | `shared/viewmodels/LibraryViewModel.kt` | 3 | `libraryContentSearchOverride` (comment + declaration) and one call at the top of `contentMatchedSongs` | Replaces the SQL `LIKE` library search with our ranked, typo-tolerant index. Shared cannot depend on the bridge, so the bridge sets this function at startup; when unset or not ready, upstream's query runs | Search engineer |
| 5 | `androidApp/build.gradle.kts`, `desktopApp/build.gradle.kts` | 2 | `implementation(project(":turtle-ui"))` (which exposes the bridge) | The apps load our Koin module and call the hooks | KMP lead |
| 6 | `android/App.kt` | 2 | `turtleAppModule` (bridge plus Shell) appended to `startKoin { modules(...) }`, plus import | Our bindings load after upstream's, so they override where they overlap | KMP lead |
| 7 | `desktop/Main.kt` | 2 | Same for desktop `startKoin` | Same | KMP lead |
| 8 | `android/playback/MusicService.kt` | 2 | `TurtleBridge.observePlayer(serviceScope, player.currentSong, player.isPlaying, player.onEOFPlaybackInstance)` after `AndroidListeningHistory(...)`, plus import | Feeds the listening-event log; also starts our runtime (index sync, taste updates) | Recommendations engineer |
| 9 | `desktop/state/playback/PlayerState.kt` | 2 | Same call before the `player.onEOFPlaybackInstance` collector, plus import | Same, for desktop | Recommendations engineer |
| 10 | `android/playback/AndroidQueueCoordinator.kt` | 2 | `?.let { TurtleBridge.rankRadio(it) }` on the result of `recommendationHistory.select(...)` in `freshRadioPage`, plus import | Autoplay: the taste engine orders YouTube's already-filtered radio candidates; upstream's filters (blocked, queued, songs-only) stay authoritative | Recommendations engineer |
| 11 | `desktop/state/playback/DesktopQueueRadio.kt` | 2 | Same on the desktop `recommendationHistory.select(...)` result, plus import | Same, for desktop | Recommendations engineer |
| 20 | `shared/api/YouTubeRepository.kt` | 3 | `searchSummaryOverride` (comment + declaration) and its use where `getSearchSummary` returns the online page | Search: our engine re-ranks YouTube's page together with the library (typo-tolerant, deduplicated, personal, intent-aware top result) inside upstream's section shape, so the UI and its strings are unchanged. Videos, playlists and podcasts pass through. Fails open to upstream's page | Search engineer |
| 21 | `shared/ui/screens/HomeShelfBuilder.kt` | 3 | `homeShelvesOverride` (comment + declaration) and its use where `HomeShelfCache.build` returns | Home: our shelves (artist mixes or Daily Mixes, More like X, On repeat, Weekly discovery, Fresh for you, Rediscover) go after Speed Dial. They live in Compose state, so Home recomposes when they refresh | Recommendations engineer |
| 22 | `shared/ui/screens/HomeContent.kt` | 1 | First line of `HomeContent`: `TurtleUiSlots.home?.let { return it(HomeSlot(...)) }`, fully qualified, no imports | Shell's Home replaces upstream's on Android, desktop and iOS, keeping upstream's callbacks (navigation, menus, loading) | Product designer |
| 23 | `shared/ui/screens/SearchScreen.kt` | 1 | First line of `SearchScreen`: `TurtleUiSlots.search?.let { return it(SearchSlot(...)) }`, fully qualified, no imports | Shell's Search replaces upstream's on all platforms; desktop keeps its title-bar field (`showSearchBar = false`) | Product designer |
| 24 | `settings.gradle.kts` | 2 | `include(":turtle-ui")` and its `projectDir` | Registers Shell, our Compose design system and screens | Product designer |
| 25 | `android/ui/components/MiniPlayer.kt` | 1 | Before `val playerConnection = ...`: `TurtleUiSlots.miniPlayer?.let { return it(MiniPlayerSlot(modifier, onExpand)) }` | Shell's mini player (artwork tint, swipe to skip, progress line) on Android | Product designer |
| 26 | `shared/ui/player/PlayerScreen.kt` | 1 | `TurtleUiSlots.player?.takeUnless { showUpstreamPlayer && !collapsed }?.let { return it(PlayerSlot(...)) }` | Shell's Now Playing. Lyrics, queue, devices and the More menu hand over to upstream's player (`showUpstreamPlayer`), which shows until the sheet collapses | Product designer |
| 27 | `shared/ui/navigation/AdaptiveNavigationScaffold.kt` | 1 | `TurtleUiSlots.navigation?.takeUnless { isEditing }?.let { ... return slot(NavigationSlot(...)) }` | Shell's bottom tabs (phones) and rail (desktop, tablets). Keeps upstream's header, footer, floating-bar height reporting, per-tab modifier (long-press Search) and tab-reorder edit mode; slim bars count as tabs | Product designer |
| 28 | `desktop/ui/components/Player.kt` | 1 | First line of `StandardBottomPlayer`: `TurtleUiSlots.desktopPlayerBar?.let { return it(DesktopPlayerBarSlot(...)) }` | Shell's Spotify-layout desktop player bar; queue, lyrics, devices, fullscreen, shuffle and repeat use upstream's desktop state | Product designer |
| 29 | `shared/ui/theme/CommonTheme.kt` | 1 | `baseColorScheme` first asks `TurtleUiSlots.colorScheme?.invoke(darkTheme)`, fully qualified | Shell's palette replaces the generated Material scheme app-wide in dark theme, so upstream's title bar, frame, pages, menus and dialogs match Shell. On desktop `surfaceContainer` is the black frame and `surface` the panel. Light theme keeps upstream's scheme | Product designer |
| 30 | `desktop/Main.kt` | 1 | After `startKoin`: `TurtleUiSlots.desktopLibrary = DesktopLibrarySlot({ LibraryContentState.librarySections... }, { openHomeItem(it) })`, fully qualified | Feeds Shell's Your Library sidebar with upstream's library list and upstream's own opener (which loads a playlist, album or artist before navigating). The same line sets `TurtleUiSlots.desktopExtras`: desktop lyrics, queue removal, sleep timer and add-to-playlist for Shell's right-panel surfaces. Its presence also tells Shell it runs on desktop | Product designer |
| 31 | `desktop/ui/screens/HomeScreen.kt` | 1 | `private fun openHomeItem` made `internal` | So hook #30 can reuse upstream's opener, including built-in playlists such as Liked Songs | Product designer |
| 32 | `shared/ui/theme/CommonTheme.kt` | 1 | `typography = TurtleUiSlots.typography?.invoke() ?: MetrolistTypography()` | Shell's type (Space Grotesk headings, Inter text) for the whole app, so upstream's own screens and dialogs read as Turtle Player | Product designer |
| 33 | `shared/ui/mobile/MobilePlaylistActions.kt` | 1 | The chooser's `PopupContent(...)` line becomes `if (TurtleUiSlots.playlistPicker?.let { ... } == null) PopupContent(...)` | Shell's add-to-playlist sheet on phones, driven by upstream's workflow (selection, YouTube sync, duplicates, create) | Product designer |
| 34 | `shared/ui/screens/PlaylistContent.kt` | 1 | First line: `TurtleUiSlots.playlistPage?.let { return it(PlaylistPageSlot(...every argument...)) }` | Shell's playlist page on phone and desktop; upstream's selection, reorder, search, pagination and swipe containers are passed through | Product designer |
| 35 | `shared/ui/screens/AlbumContent.kt` | 1 | First line: `TurtleUiSlots.albumPage?.let { return it(AlbumPageSlot(...)) }` | Shell's album page on phone and desktop | Product designer |
| 36 | `shared/ui/screens/ArtistScreen.kt` | 1 | First line: `TurtleUiSlots.artistPage?.let { return it(ArtistPageSlot(...)) }` | Shell's artist page on phone and desktop | Product designer |
| 37 | `shared/ui/screens/LibraryContent.kt` | 1 | First line: `TurtleUiSlots.libraryPage?.let { return it(LibraryPageSlot(...)) }` | Shell's Library page on phone and desktop; upstream's item containers (menus, swipe), sorting and reorder are passed through | Product designer |
| 38 | `shared/ui/screens/SupportPrompt.kt` | 1 | First line: `if (TurtleUiSlots.hideSupportPrompt) return` | Turtle Player doesn't show Metrolist's "support the developers" launch prompt; upstream's credits stay in About | Product designer |
| 39 | `android/ui/screens/HomeScreen.kt` | 1 | `if (!isTv)` around the floating buttons becomes `if (!isTv && TurtleUiSlots.home == null)` | Shell's Home has no floating buttons (as in Spotify); music recognition stays on a long-press of the Search tab | Product designer |
| 40 | `shared/ui/components/AlertDialogs.kt` | 1 | First line of `AlertDialog`: `TurtleUiSlots.dialog?.let { return it(DialogSlot(...)) }` | Every upstream dialog (create playlist, duplicates, confirmations, settings prompts) drawn as Shell's dialog card, keeping upstream's title, text and actions | Product designer |
| 41 | `shared/settings/Settings.kt` | 2 | `listenTogetherEnabled` becomes a getter/setter that reads false while `TurtleUiSlots.hideListenTogether` is set; the stored preference moves to `private var listenTogetherEnabledStored` under the same key | The product owner scrapped Listen Together (20 Nov 2026). Upstream's own off switch already stops the manager connecting, restoring or auto-joining a room, sending, and notifying, and hides its gated entry points (desktop sidebar, Android top-bar button, player and queue menus, Android/mobile/desktop invite deep links, settings search); forcing it off at the source covers them all and a stored `true` from an earlier install. Shell sets the flag in `TurtleUi.install()` | Product designer |
| 42 | `shared/ui/settings/SharedIntegrationsScreen.kt` | 1 | First line of `SharedIntegrationsScreen`: shadows `host` with `host.copy(listenTogetherManager = null, onOpenListenTogether = null)` while the flag is set | Removes the Listen Together card from Integrations settings on Android, desktop and mobile (upstream shows it even when switched off, leading to its enable toggle) | Product designer |
| 43 | `shared/ui/navigation/Screen.kt` | 1 | `screenForNavigationId`: `NavigationId.LISTEN_TOGETHER -> Screen.ListenTogether.takeUnless { TurtleUiSlots.hideListenTogether }` | The large-screen Android rail (`visibleSidebarScreens`) ignores upstream's off switch; this drops the tab there and in every other id-to-screen lookup | Product designer |

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

**Upstream-tree files we own** (new files, so they never conflict on merge): `shared/src/commonMain/kotlin/com/metrolist/shared/turtle/TurtleUiSlots.kt`, the single home for UI override points.

Every hook fails open: if Koin has no Turtle runtime, our code throws, or ranking misses its
1.5-second budget, upstream's own behavior runs unchanged.

## Planned hooks (not yet applied)

| Seam | Upstream location | Planned hook |
| --- | --- | --- |
| App identity | `androidApp/build.gradle.kts:255` `applicationId`, `desktopApp/build.gradle.kts:738` `packageName` | Read from `turtle/branding.properties` (2 lines) |
| Update channel | `android/update/AppUpdateManager.kt:122`, `desktop/update/DesktopUpdateManager.kt:42` | Point at our releases (2 lines) |

The remaining planned hooks need about 4 more lines, which would reach the budget of 55; prefer to fold the app identity into one shared `turtle/branding.properties` read (1 line per app is the floor), or raise the budget deliberately. Prefer
consolidating (for example one shared override point that several hooks reuse) over adding new
call sites.
