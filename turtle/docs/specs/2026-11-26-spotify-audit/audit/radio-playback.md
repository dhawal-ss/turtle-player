# Turtle audit: Radio and stations, and playback integration

Domain key: `radio-playback`. Audited at commit 09ae8be7. Every row carries file:line evidence.

## Capabilities

| State | Capability | Evidence | Notes |
| --- | --- | --- | --- |
| UPSTREAM-OK | Song radio ("Start radio") from the now-playing sheet | Impl androidApp/.../playback/PlayerConnection.kt:1219 -> MusicService playMix; desktopApp/.../playback/PlayerState.kt:3722. Shell entry turtle/ui/.../screens/ShellPlayerSheets.kt:435 | Only Shell-owned station entry point for the playing song. It alone calls beginOccurrence first (ShellQueue.kt:163). |
| UPSTREAM-OK | "Start radio" on any song row, via upstream's menu restyled by Shell | shared/.../ui/menu/SongMenuActions.kt:36 adds the Start radio primary action; Shell draws it at turtle/ui/.../screens/ShellMenus.kt:223 via TurtleUiSlots.menuPrimaryActions (shared/.../ui/menu/ContextMediaMenu.kt:534) | Reachable from search rows (ShellSearch.kt:567), Home rows (ShellHome.kt:457,697), collection pages (ShellCollection.kt:406) and artist pages (ShellArtist.kt:690). |
| UPSTREAM-HIDDEN | "Start radio" from Library page rows | Upstream impl androidApp/.../ui/screens/LibraryScreen.kt:608 (onStartRadio). LibraryPageSlot carries no long-click or menu callback: shared/.../turtle/TurtlePageSlots.kt:177-214 | Shell's library page (ShellLibrary.kt) renders rows with no per-item menu, so no station can be started from the library at all. Adding one callback to LibraryPageSlot is the cheapest win here. |
| TURTLE | Taste ranking of radio pages, seeded by the station's own song | turtle/bridge/.../TurtleRuntime.kt:236-333 (seed lookup 257, rankExternal 302); engine turtle/engines/taste/.../TasteEngine.kt:333-375 | Seed comes from the player, then the song cache; an unknown seed ranks seedless rather than dropping a candidate. |
| TURTLE | Turtle exclusions applied on every radio return path (ranked, timed out, failed, single-song) | turtle/bridge/.../TurtleRuntime.kt:351-372 (eligibleSongs), 374-392 (basicExclusions), 282-296 (single-song and stale-occurrence paths) | Drops Not interested songs, their other uploads, muted artists, duplicate uploads, the seed itself, upstream content settings, and videos/episodes for a songs-only queue. |
| TURTLE | Hidden-song identity matching across other uploads of the same recording | turtle/bridge/.../RadioEligibility.kt:29-48 (hiddenRecordings), resolved through the taste profile, the song cache and saved Hidden records | Survives a restart because a Hidden record keeps the raw title and first artist. |
| TURTLE | Bounded paging past a radio page where everything was ruled out | turtle/bridge/.../RadioEligibility.kt:78-111 (pageUntilEligible); called at turtle/bridge/.../TurtleRuntime.kt:270-277 with MAX_EXTRA_PAGES=3, PAGING_DEADLINE_MS=2000 (786-794) | Never repeats a token; on failure or deadline it returns the last token so the next attempt starts after the pages already seen. |
| TURTLE | Desktop's opening Start-radio page is excluded and ranked (hook #61) | desktopApp/.../playback/PlayerState.kt:3745-3760 (rankRadio at 3759); turtle/HOOKS.md:84 | The station's own song is prepended after ranking (PlayerState.kt:3760), so it stays first. |
| TURTLE | Android radio continuations ranked (hook #10) | androidApp/.../playback/AndroidQueueCoordinator.kt:566 in freshRadioPage; turtle/HOOKS.md:41 | Seed is the station's song only while isRadioQueue; otherwise null and the bridge uses the playing song. |
| TURTLE | Desktop radio continuations ranked (hook #11) | desktopApp/.../playback/DesktopQueueRadio.kt:103; turtle/HOOKS.md:42 | Always passes seed.id, even when the queue is a running playlist rather than a station. |
| TURTLE | Android end-of-queue stall fix: the station's token advances past an empty page (#62) | androidApp/.../playback/AndroidQueueCoordinator.kt:907-909 (guard then store then empty return) and 662; turtle/HOOKS.md:112 | Before this, a page with nothing to add returned before storing the token, so playback stopped at the end of the queue. |
| TURTLE | Honest "why this page was not ranked" tagging (ranked / provider / timeout / failed) | turtle/bridge/.../TurtleRuntime.kt:395-425 (served, tagServed) with Served.PROVIDER / TIMEOUT / FAILED; tags carry no source or slot so per-source learning ignores them | Makes degraded radio visible in the event log instead of silently crediting the ranker. |
| TURTLE | Queue-occurrence isolation: one station does not steer the next | turtle/bridge/.../PlayerObserver.kt:200-205 (beginOccurrence), 240-245 (attribute), 348-356 (queue replaced outside Turtle); engine turtle/engines/taste/.../TasteEngine.kt:387-392 | Tags are valid only inside the occurrence they were written for (PlayerObserver.kt:223-232). |
| TURTLE | Co-listening graph learned from radio pages, only from a known seed | turtle/bridge/.../TurtleRuntime.kt:300 (coListening.observeRadio inside the rank budget, guarded by seed != null) | An unknown seed learns nothing, so a wrong artist is never linked. |
| UPSTREAM-OK | Artist radio | Impl shared/.../api/YouTubeRepository.kt:2463-2486; hosts androidApp/.../ui/screens/ArtistScreenHost.kt:259-267 and desktopApp/.../state/content/ArtistState.kt:405-416. Shell entry turtle/ui/.../screens/ShellArtist.kt:517 and 615-637 | Shell draws the button and hands straight to slot.onMixClick; the resulting queue never passes through rankRadio. |
| MISSING | Taste exclusions and ranking on the artist-radio page | No rankRadio on that path; the only callers are AndroidQueueCoordinator.kt:566, DesktopQueueRadio.kt:103 and PlayerState.kt:3759. startArtistRadio applies only BlockedContent.filterSongs (shared/.../api/YouTubeRepository.kt:2479) | Songs and artists hidden from Home (TurtleRuntime.kt:497-508) never reach BlockedContent, so they can play on artist radio. |
| UPSTREAM-HIDDEN | Station filter chips (YouTube Music's radio variants) | Android impl androidApp/.../playback/AndroidQueueCoordinator.kt:571-607 (selectQueueChip), upstream UI shared/.../ui/player/QueueScreen.kt:1838. Shell's queue draws no chips: turtle/ui/.../screens/ShellPlayerSheets.kt:169-290 | rankRadio already preserves page.chips (TurtleRuntime.kt:104, 395-400), so only a chip row in ShellQueueList plus controller.selectQueueChip is missing. Desktop has no implementation (PlaybackController.kt:141 is a no-op default). |
| UPSTREAM-HIDDEN | Desktop "Related songs" / suggested-next list in the queue | Impl desktopApp/.../playback/PlayerState.kt:2435-2443 and DesktopRelatedSongs.kt:13; UI desktopApp/.../ui/components/QueueSideSheet.kt:64,83. Shell replaces the desktop layout at turtle/ui/.../screens/ShellNavigation.kt:279 | DesktopPlayerExtras exposes no relatedSongs (shared/.../turtle/TurtleUiSlots.kt:207-220), so fetchRelated is never called under Shell and the list is dead. It is also unranked, filtered only by BlockedContent. |
| UPSTREAM-OK | Start radio from the Android media notification / Android Auto | androidApp/.../playback/MusicService.kt:943-953 (createRadioButton) and 3179-3184 (ACTION_TOGGLE_START_RADIO -> playMix) | Reaches playMix directly, so no new queue occurrence is begun (see weaknesses). |
| UPSTREAM-OK | Start radio from the desktop window menu bar | desktopApp/.../ui/components/DesktopMenuBar.kt:354 (PlayerState.startRadio(currentTrack)); the menu bar is drawn outside Shell at desktopApp/.../Main.kt:1380 | Also bypasses beginOccurrence. |
| UPSTREAM-HIDDEN | "Surprise me" dice that starts a station from a random playlist song | shared/.../ui/components/SpeedDialGrid.kt:188-198 (startRadio on a random playlist song). No reference to SpeedDialGrid under turtle/; Shell's Home draws its own grid (turtle/ui/.../screens/ShellHome.kt:412-460) | Turtle keeps the SPEED_DIAL shelf's items (turtle/bridge/.../HomeLayout.kt:148) but not upstream's grid composable, so the surprise action is lost. |
| MISSING | Playlist and album radio ("go to radio" for a collection) | radioEndpointParams is parsed and stored only: shared/.../model/Models.kt:109,129, shared/.../db/entities/PlaylistEntity.kt:33, shared/.../db/PlaylistSync.kt:71-111. No call site plays it | Shell's collection header (ShellCollection.kt:1109-1110) offers play, shuffle and more, never a station. |
| TURTLE | Genre and mood stations (the Infinity Mix) | turtle/bridge/.../InfinityMix.kt:52 (radio seam) and 820-824 (ranked with the occurrence station key); Shell entry turtle/ui/.../screens/ShellInfinity.kt and ShellQueue.kt:150-157 | Turtle's own endless station, tuned by genre/mood picks and the discovery dial. The only station offered outside the player and the artist page. |
| TURTLE | Smart Shuffle (a recommendation after every few playlist songs) | turtle/bridge/.../SmartShuffleBridge.kt:27-33 with YouTubeRadioSource (turtle/bridge/.../YouTubeSeams.kt:55); engine filter turtle/engines/taste/.../SmartShuffle.kt:91 | Its own candidate source, separate from rankRadio; it does apply mutedArtists. |
| MISSING | A "Stations" row or any station catalog on Home or Explore | No station surface exists: the only Glyphs.Radio uses under turtle/ui are ShellPlayerSheets.kt:435, ShellArtist.kt:626,633 and a podcasts icon at ShellLibrary.kt:945 | Matches the comparison doc's "Recommended Stations: none on Home" row (turtle/docs/plans/2026-11-23-spotify-comparison.md:48). |
| MISSING | Offline or library-only radio | Every station path needs the network: AndroidQueueCoordinator.kt:495 returns when disconnected; desktop goes straight to GlobalYouTubeRepository (PlayerState.kt:3752). The engine's own TasteEngine.startRadio (TasteEngine.kt:306) has no production caller | Its only caller is turtle/engines/taste/src/commonTest/.../TasteEngineTest.kt:275. The engine already builds a shelf from a RadioSeed, so an on-device station over the local library is close. |
| UPSTREAM-OK | Station name shown as "Playing from" | Android sets the title at androidApp/.../playback/AndroidQueueCoordinator.kt:441 (song_mix_title); it flows via PlayerConnection.kt:289 -> PlayerViewModel.kt:542 -> turtle/ui/.../screens/ShellPlayer.kt:519 | Desktop emits no queueTitle at all, so turtle/ui/.../screens/ShellDesktop.kt:618 falls back to the song title. |
| UPSTREAM-OK | Guards that stop radio extension (repeat mode, cast follower, Listen Together guest, offline) | androidApp/.../playback/AndroidQueueCoordinator.kt:621-631 (isCurrent) and 494-497; desktopApp/.../playback/DesktopQueueRadio.kt:78-81 | Correct behavior, but silent: nothing tells the listener why a station stopped growing. |
| UPSTREAM-OK | "Auto load more" switch that governs station extension | shared/.../ui/settings/PlaybackSettingsRegistration.kt:316-320; Shell keeps the whole PLAYER page, only renamed, at turtle/ui/.../screens/ShellSettingsTurtle.kt:537-545 | Default true (shared/.../settings/Settings.kt:685). A station started with it off still gets its first page because playMix sets finite=false (AndroidPlaybackPolicies.kt:185). |
| UPSTREAM-HIDDEN | Loading indicator while a station's first page loads | The state exists at shared/.../ui/playback/PlaybackController.kt:34 (isQueueLoading, fed by AndroidQueueCoordinator.withQueueLoading) but no file under turtle/ reads it | Shell's queue shows the "queue is empty" string instead (ShellPlayerSheets.kt:275). |

## Weaknesses

### [MAJOR] Artist radio bypasses every Turtle exclusion, the ranker and the station seed

- Evidence: turtle/ui/.../screens/ShellArtist.kt:517 -> slot.onMixClick -> androidApp/.../ui/screens/ArtistScreenHost.kt:262 or desktopApp/.../state/content/ArtistState.kt:409, which only call YouTubeRepository.startArtistRadio (shared/.../api/YouTubeRepository.kt:2463)
- Why it matters: A listener who used Home's "Not interested" on a song or artist (turtle/bridge/.../TurtleRuntime.kt:497-508, which writes taste controls only, never BlockedContent) hears exactly those songs again on the very next artist station. The page is also unranked, so the discovery dial and the profile have no effect on it.
- Fix sketch: Give ShellArtist its own handler instead of slot.onMixClick: fetch the artist radio page, wrap it in a RadioQueuePage, pass it through TurtleBridge.rankRadio with the artist's top track as the seed, then ShellQueue.play with a "<Artist> radio" title. Files: turtle/ui/.../ShellArtist.kt, turtle/bridge/.../TurtleModule.kt (a startArtistRadio entry), turtle/bridge/.../TurtleRuntime.kt.

### [MAJOR] The "upstream order" that every fallback keeps is a random shuffle, not YouTube's order

- Evidence: shared/.../model/RadioQueuePage.kt:22-25 varyRadioRecommendations = songs.shuffled(), applied before Turtle sees the page at shared/.../model/RadioRecommendationHistory.kt:36,54; rankRadio runs on that result (AndroidQueueCoordinator.kt:566, PlayerState.kt:3759)
- Why it matters: Two things break. The provider prior fed to the ranker (1f - 0.5f*i/size at turtle/engines/taste/.../TasteEngine.kt:358) is noise rather than YouTube's relevance signal. And on a timeout, a failure or a one-song page the listener gets a shuffled page while the log and the docs say "kept upstream order" (TurtleRuntime.kt:233, 287-296).
- Fix sketch: Either hand rankRadio the unshuffled page (move the rankRadio call so it sees firstPage.songs before varyRadioRecommendations, or pass the raw order alongside) and let Turtle own the ordering, or drop the positional prior and stop claiming "upstream order" in the log lines and HOOKS.md. Files: turtle/bridge/.../TurtleRuntime.kt, turtle/engines/taste/.../TasteEngine.kt, turtle/HOOKS.md.

### [MAJOR] Radio ranking runs on the main thread and its 1.5 s budget cannot actually be enforced

- Evidence: androidApp/.../playback/MusicService.kt:271 and desktopApp/.../playback/PlayerState.kt:434 both use Dispatchers.Main. rankExternal's scoring loop (turtle/engines/taste/.../TasteEngine.kt:355-374) has no suspension point, so withTimeoutOrNull at TurtleRuntime.kt:299 cannot interrupt it
- Why it matters: Up to a whole 50-song page is vectorised, feature-extracted, scored and assembled on the UI/service thread for every radio page and continuation. A slow device drops frames or risks an ANR, and the stated 1.5 s cap silently does not hold once the work is CPU-bound, so the "upstream behavior runs unchanged within 1.5 s" promise in turtle/HOOKS.md:120 is not true.
- Fix sketch: Wrap the ranking block in withContext(Dispatchers.Default) inside TurtleRuntime.rankRadio (around lines 297-312) and add currentCoroutineContext().ensureActive() to the scoring loop in TasteEngine.rankExternal so the timeout can fire. Also move cache.putAll and the toTrackMeta mapping in eligibleSongs (TurtleRuntime.kt:351-372) off the caller's thread.

### [MAJOR] A station switched while a page is in flight gets the previous station's songs and token (Android)

- Evidence: androidApp/.../playback/AndroidQueueCoordinator.kt:633 is the last isCurrent() check; 660-664 then write queueChipsState, radioQueueContinuation and radioQueuePlaylistId and 672 appends the songs with no re-check, after rankRadio has had up to ~3.5 s
- Why it matters: Tap Start radio on a second song while the first station's page is ranking and the new queue is extended with the old station's songs and inherits the old station's continuation token and chips. The listener hears the station they left. rankRadio detects the moved-on occurrence and declines to tag (TurtleRuntime.kt:289-293), but the caller still applies the page.
- Fix sketch: Re-check isCurrent() in applyMixPage right after freshRadioPage returns and before the chips/continuation writes and player.addToQueue, mirroring the guard already used at AndroidQueueCoordinator.kt:907. One file: androidApp/.../playback/AndroidQueueCoordinator.kt.

### [MAJOR] Same stale-page race on desktop's continuation path

- Evidence: desktopApp/.../playback/DesktopQueueRadio.kt:101 passes ::canLoad to select, then 103 calls rankRadio and 104-112 writes continuation and adds songs to the queue without calling canLoad()/shouldCommit() again
- Why it matters: The same wrong-station append and token carry-over as on Android, on the path that fires at the end of every desktop queue.
- Fix sketch: After the rankRadio call in DesktopQueueRadio.load, return early unless canLoad() still holds before writing `continuation` and calling queue.addToQueue. One file: desktopApp/.../playback/DesktopQueueRadio.kt.

### [MAJOR] Turtle's own artist mutes match the primary artist only, so features leak through

- Evidence: turtle/engines/taste/.../ExternalEligibility.kt:31 checks track.artistKey, and TrackMeta.artistKey is ArtistKey.of(primaryArtist) (turtle/engines/core/.../Track.kt:88-89). Upstream's block checks every artist: shared/.../settings/BlockedContent.kt:282
- Why it matters: "Not interested" on an artist from Home (TurtleRuntime.kt:505-507) silences them as a lead artist but not as a featured one, so the listener keeps hearing the artist they just muted. The same gap is in the last-resort path (TurtleRuntime.kt:384).
- Fix sketch: Match any credited artist: give TrackMeta an artistKeys list (turtle/engines/core/.../Track.kt) and use artistKeys.none { it in controls.mutedArtists } in ExternalEligibility.kt:31, SmartShuffle.kt:91 and TurtleRuntime.kt:384.

### [MAJOR] No station filter chips anywhere in Shell

- Evidence: Chips are parsed, ranked through and stored (shared/.../model/RadioQueuePage.kt:19, turtle/bridge/.../TurtleRuntime.kt:104, AndroidQueueCoordinator.kt:660-661) and Android can switch them (AndroidQueueCoordinator.kt:571), but no file under turtle/ui reads queueChips
- Why it matters: The one steering control a YouTube Music station offers is invisible. The listener cannot narrow or widen a station, so the only way to change its character is to start a different one. Desktop has no implementation either (shared/.../ui/playback/PlaybackController.kt:141 is a no-op default).
- Fix sketch: Add a chip row to ShellQueueList (turtle/ui/.../screens/ShellPlayerSheets.kt:169-290) reading state.queueChips and calling controller.selectQueueChip, and begin a new occurrence on selection. Then implement selectQueueChip in desktopApp/.../playback/DesktopPlaybackController.kt on top of DesktopQueueRadio.

### [MAJOR] Artist radio fails silently: tapping the button can do nothing at all

- Evidence: shared/.../api/YouTubeRepository.kt:2484-2485 swallows every exception and returns emptyList; androidApp/.../ui/screens/ArtistScreenHost.kt:262 uses takeIf { it.isNotEmpty() } and desktopApp/.../state/content/ArtistState.kt:412-415 catches and ignores
- Why it matters: On a flaky network, a signed-out session or an artist whose page has no radio endpoint, the Shell artist-radio button (ShellArtist.kt:615) gives no playback, no error and no spinner. The listener cannot tell the button from a dead control.
- Fix sketch: Have the Turtle-owned artist-radio handler report failure and show ShellSnackbar (the pattern at turtle/ui/.../screens/ShellPlayerSheets.kt:206-210), and add a disabled/pending state to ArtistRadioButton. Files: turtle/ui/.../ShellArtist.kt, turtle/bridge/.../TurtleModule.kt.

### [MAJOR] Starting a station from the notification, Auto or the menu bar carries the last queue's learning in

- Evidence: androidApp/.../playback/MusicService.kt:3183 and desktopApp/.../ui/components/DesktopMenuBar.kt:354 call playMix/startRadio directly; only ShellActions.startRadio calls beginOccurrence (turtle/ui/.../screens/ShellQueue.kt:163-168)
- Why it matters: With preserveCurrent the playing song does not change, so PlayerObserver's replaced-queue detection (PlayerObserver.kt:293-299, 348-356) never fires. Exactly what ShellQueue.kt:160-161 says it prevents: the old queue's pending tags and short-term session keep steering the new station's first pages, and plays are attributed to the old surface.
- Fix sketch: Begin the occurrence inside the bridge rather than the UI: call observer.beginOccurrence() at the top of TurtleRuntime.rankRadio when seedId differs from the station last ranked, or add a TurtleBridge hook on MusicService.playMix and PlayerState.startRadio. Files: turtle/bridge/.../TurtleRuntime.kt, turtle/bridge/.../PlayerObserver.kt.

### [MAJOR] Worst-case time from tapping Start radio to hearing the station is unbounded in practice

- Evidence: shared/.../model/RadioRecommendationHistory.kt:29-50 fetches up to 10 pages; turtle/bridge/.../TurtleRuntime.kt:270-277 then adds up to 3 more within 2 s; ranking adds up to 1.5 s (TurtleRuntime.kt:785-794); Shell ignores isQueueLoading
- Why it matters: On a slow connection the listener taps a station and waits, with the queue reading "empty" (ShellPlayerSheets.kt:275) and no spinner, with no idea whether the tap registered.
- Fix sketch: Put one overall deadline on the whole station bootstrap and show progress: read state.isQueueLoading in ShellQueueList and in ShellPlayer's "Playing from" header, and cap RadioRecommendationHistory.select's page budget when it is feeding a first page. Files: turtle/ui/.../ShellPlayerSheets.kt, turtle/ui/.../ShellPlayer.kt, turtle/bridge/.../TurtleRuntime.kt.

### [MAJOR] No station on Home, Explore or any collection: radio is hidden two taps deep

- Evidence: The only station entries are turtle/ui/.../screens/ShellPlayerSheets.kt:435 (player More sheet), ShellArtist.kt:517 and upstream song menus (SongMenuActions.kt:36). ShellCollection's header offers play, shuffle and more only (ShellCollection.kt:1104-1110)
- Why it matters: Stations are a main way listeners start endless music, and in Turtle they are reachable only once something is already playing or from an artist page. Infinity is the one station offered on Home (ShellHome.kt:418).
- Fix sketch: Add a Stations row to Home seeded from the profile's top artists and songs, each tap going through a seeded rankRadio, and a "Go to radio" action in the collection header. Files: turtle/bridge/.../TurtleHomeRows.kt, turtle/ui/.../ShellHomeRows.kt, turtle/ui/.../ShellCollection.kt.

### [MINOR] Muted taste clusters only apply when ranking finishes

- Evidence: isInMutedCluster is checked only in the ranked path (turtle/engines/taste/.../TasteEngine.kt:357, UserControls.kt:62-63); eligibleExternal has no cluster check (ExternalEligibility.kt:26-33) and neither does basicExclusions (TurtleRuntime.kt:374-392)
- Why it matters: Contradicts the contract stated at TurtleRuntime.kt:231-234 ("every way out applies the same exclusions"). On a timeout or error the listener gets songs from a cluster they dismissed.
- Fix sketch: Move the cluster test into eligibleExternal (it needs the representation vector, so pass a vector lookup in or expose a cheap engine-side filter) and call it from TurtleRuntime.eligibleSongs. Files: turtle/engines/taste/.../ExternalEligibility.kt, turtle/engines/taste/.../TasteEngine.kt, turtle/bridge/.../TurtleRuntime.kt.

### [MINOR] Radio songs carry no "why this song" line even though the data exists

- Evidence: Each ranked radio song is tagged with its source and slot (turtle/bridge/.../TurtleRuntime.kt:302-307 via SurfaceTag.format(PlayOrigin(PlayPlace.RADIO, ...))), but Shell's queue row renders only InfinityWhyLabel (turtle/ui/.../screens/ShellPlayerSheets.kt:340-341)
- Why it matters: The comparison doc claims "Turtle adds a 'why this song' line" (turtle/docs/plans/2026-11-23-spotify-comparison.md:79) but on a station there is none, so the ranking is invisible and unauditable by the listener.
- Fix sketch: Read the pending tag for a queued radio song (expose a lookup on PlayerObserver next to currentOrigin, PlayerObserver.kt:194) and render a short reason under the title in QueueRow, reusing the Infinity label's styling. Files: turtle/bridge/.../PlayerObserver.kt, turtle/ui/.../ShellPlayerSheets.kt.

### [MINOR] Desktop never names the station

- Evidence: No desktop code emits PlaybackUiState.queueTitle (only the playQueue parameter exists, desktopApp/.../playback/DesktopPlaybackController.kt:68), and Shell falls back to the song title at turtle/ui/.../screens/ShellDesktop.kt:618
- Why it matters: On desktop a listener cannot tell a station from a one-song queue, so they cannot tell whether autoplay will continue.
- Fix sketch: Set and expose a queue title in DesktopRadioPlayback.start / PlayerState.startRadio and surface it on DesktopPlaybackController's state, or have Shell call ShellQueue.play with a station title from its own artist/song radio handler. Files: desktopApp/.../playback/PlayerState.kt, desktopApp/.../playback/DesktopPlaybackController.kt, turtle/ui/.../ShellQueue.kt.

### [MINOR] Desktop's opening station page is ranked without songsOnly

- Evidence: desktopApp/.../playback/PlayerState.kt:3759 calls TurtleBridge.rankRadio(it, seedId) with no songsOnly, unlike DesktopQueueRadio.kt:103 and AndroidQueueCoordinator.kt:566
- Why it matters: On a songs-only queue the first station page can still be seeded with music videos and podcast episodes, and the extra pages fetched by pageUntilEligible will not filter them either (TurtleRuntime.kt:351-372).
- Fix sketch: Pass queue.recommendationsSongsOnly through freshRadioRecommendations at desktopApp/.../playback/PlayerState.kt:3745-3760 and update turtle/HOOKS.md:84.

### [MINOR] Desktop treats a running playlist queue as a station seeded by its last song

- Evidence: desktopApp/.../playback/DesktopQueueRadio.kt:103 always passes seed.id, while Android deliberately passes null unless isRadioQueue (AndroidQueueCoordinator.kt:566)
- Why it matters: Autoplay after a finite playlist is ranked as if the listener had asked for a station on that one song, and co-listening learns an artist link from a page the listener never asked for (TurtleRuntime.kt:300).
- Fix sketch: Track whether the desktop queue is a station and pass seed.id only then, mirroring Android. Files: desktopApp/.../playback/DesktopQueueRadio.kt, desktopApp/.../playback/PlayerState.kt.

### [MINOR] "Never play this artist" is only offered for the first credited artist

- Evidence: turtle/ui/.../screens/ShellPlayerSheets.kt:449 uses song.artists.firstOrNull(), so a collaboration offers no way to block the other artist
- Why it matters: On a station full of features and remixes the listener cannot stop the artist actually driving the recommendation.
- Fix sketch: Offer one row per credited artist with a non-blank id in the More sheet (turtle/ui/.../ShellPlayerSheets.kt:449-455), capped at a few rows.

### [MINOR] Playlist and album radio endpoints are stored but never used

- Evidence: radioEndpointParams is round-tripped through the model, database and sync (shared/.../model/Models.kt:129, shared/.../db/entities/PlaylistEntity.kt:33, shared/.../db/PlaylistSync.kt:71-111) with no playback consumer anywhere
- Why it matters: A station based on a playlist or album, which Turtle already has the token for, cannot be started; the data cost is paid for nothing.
- Fix sketch: Add a Turtle-owned startCollectionRadio that calls startRadioQueue with the stored playlistId and radioEndpointParams, ranks the page, and expose it from ShellCollection's header. Files: turtle/bridge/.../TurtleModule.kt, turtle/bridge/.../TurtleRuntime.kt, turtle/ui/.../ShellCollection.kt.

### [MINOR] Desktop's related-songs list is dead under Shell and would be unfiltered if revived

- Evidence: PlayerState.fetchRelated is called only from upstream's queue sheet (desktopApp/.../ui/components/QueueSideSheet.kt:83), which Shell replaces (turtle/ui/.../screens/ShellNavigation.kt:279); the source applies only BlockedContent (PlayerState.kt:2436-2437)
- Why it matters: Desktop listeners lose the "what comes after this" list entirely, and a naive re-exposure would ship a suggestion list with none of Turtle's exclusions.
- Fix sketch: Expose relatedSongs through DesktopPlayerExtras (shared/.../turtle/TurtleUiSlots.kt:207-220), route the fetch through TurtleBridge.rankRadio with the playing song as the seed, and render a "More like this" section at the end of ShellQueueList.

### [MINOR] A station that stops growing, or cannot start offline, says nothing

- Evidence: Every bail-out is silent: androidApp/.../playback/AndroidQueueCoordinator.kt:494-497 and 621-631 return without a message, desktopApp/.../playback/DesktopQueueRadio.kt:119-121 only logs, and TurtleRuntime's degraded paths only write to TurtleLog (TurtleRuntime.kt:284, 294, 330)
- Why it matters: Repeat-one, a cast follower session, "auto load more" off or no network each silently turn an endless station into a one-song queue, which reads as a bug to the listener.
- Fix sketch: Surface a single reason line in Shell's queue header when the queue is a station and extension is blocked, from a small state on the bridge fed by those guards. Files: turtle/bridge/.../TurtleModule.kt, turtle/ui/.../ShellPlayerSheets.kt.

## Strengths

- **Listener exclusions hold on every radio path, including the degraded ones** — turtle/bridge/.../TurtleRuntime.kt:282-296 (single-song and stale-occurrence), 299-331 (timeout and failure), 351-392 (eligibleSongs and basicExclusions) | beats Spotify: Only ranking is optional; hides, mutes and duplicate uploads are dropped even when ranking times out or throws, so a degraded station never quietly re-serves what the listener ruled out.
- **Degradation is labelled rather than hidden** — turtle/bridge/.../TurtleRuntime.kt:395-425 tags unranked pages with Served.PROVIDER / TIMEOUT / FAILED and deliberately carries no source or slot so per-source learning ignores them | beats Spotify: The event log distinguishes "the ranker chose this" from "we served the provider's page", so later quality measurement cannot credit the ranker for pages it never ordered.
- **Hidden songs are matched by recording identity, not by video id** — turtle/bridge/.../RadioEligibility.kt:29-48 resolves each hidden key through the taste profile, the in-memory song cache and the saved Hidden records (raw title plus first artist), keeping both identities when they differ | beats Spotify: "Never play this" survives a restart and catches the other uploads of the same recording that YouTube Music radio is full of.
- **A station of ruled-out songs moves on instead of stalling** — turtle/bridge/.../RadioEligibility.kt:78-111 pages up to 3 continuations inside a 2 s deadline, never repeats a token, and returns the last token reached; paired with the Android token fix at AndroidQueueCoordinator.kt:907-909 | beats Spotify: Heavy use of "not interested" does not dead-end playback, which is the usual failure mode of a client-side blocklist bolted onto someone else's radio.
- **Ranking follows the station the listener actually started** — turtle/bridge/.../TurtleRuntime.kt:254-262 resolves the asked-for station's song (player, then song cache) and never substitutes the playing song; an unknown seed ranks seedless rather than dropping a real candidate
- **One station's short-term learning cannot poison the next** — turtle/bridge/.../PlayerObserver.kt:200-205, 223-232 and 348-356; the engine starts a fresh short-term session per occurrence at turtle/engines/taste/.../TasteEngine.kt:342-347, 387-392
- **Station ranking happens entirely on the device** — turtle/engines/taste/.../TasteEngine.kt:333-375 scores candidates from the local profile and the SqlitePlayEventStore log; the only network calls on the path are YouTube's own radio pages (turtle/bridge/.../RadioEligibility.kt:57-59) | beats Spotify: Nothing about which station was started, or which of its songs were hidden, leaves the device; the taste state is a local SQLite pref (TurtleRuntime.kt:801-810).

## Doc claims that are stale at HEAD

- Doc says: turtle/docs/plans/2026-11-23-spotify-comparison.md:85 — "Song radio ... PARTIAL | Desktop's first station page is unranked: one new hook line (owner decision)"
  - Reality: It is now excluded and ranked: desktopApp/.../playback/PlayerState.kt:3759 calls TurtleBridge.rankRadio(it, seedId) in freshRadioRecommendations, registered as hook #61 (turtle/HOOKS.md:84). Remaining gap: no songsOnly argument.
- Doc says: turtle/docs/plans/2026-11-23-status.md:48,53 — "Done on 23 Nov 2026, not committed ... Radio exclusions on desktop's first Start radio page (hook #61) and every continuation"
  - Reality: Committed at HEAD 09ae8be7, whose message lists the #61 and #62 radio work. Both hooks exist in the tree (turtle/HOOKS.md:84 and :112) with call sites at PlayerState.kt:3759 and AndroidQueueCoordinator.kt:907-909.
- Doc says: turtle/bridge/.../TurtleRuntime.kt:233 and turtle/HOOKS.md:120 — when ranking cannot finish in time, "upstream's order is kept" inside a 1.5-second budget
  - Reality: The kept order is a shuffle (RadioRecommendationHistory.kt:36,54 applies varyRadioRecommendations = songs.shuffled(), RadioQueuePage.kt:25), and the cap is unenforceable: TasteEngine.kt:355-374 never suspends while ranking on Main.
- Doc says: turtle/docs/plans/2026-11-23-spotify-comparison.md:79 — "Turtle adds a 'why this song' line and the dial" to the now-playing surfaces
  - Reality: A why line exists only for Infinity Mix songs (turtle/ui/.../ShellPlayerSheets.kt:340-341). Radio songs are tagged with source and slot (TurtleRuntime.kt:302-307) but nothing in Shell renders a reason for them.

## Open questions

- Should artist radio become Turtle-owned (fetch, rankRadio with an artist seed, ShellQueue.play with a title) or stay upstream's unranked call? It is the largest exclusion leak and needs no new hook line, only ShellArtist.kt plus a bridge entry.
- Is upstream's shuffle of each radio page before ranking (varyRadioRecommendations) meant to stay? If so the positional prior in rankExternal and the "kept upstream order" wording should go; if not, rankRadio needs the raw page.
- Is moving radio ranking to Dispatchers.Default acceptable given the taste engine's mutex is also held by Home shelf builds, or would that trade jank for lock contention on the first page?
- Does the owner want the Home Stations row from the comparison doc (line 118, step 3), and should its taps reuse rankRadio or the engine's unwired TasteEngine.startRadio (TasteEngine.kt:306)?
- Should station filter chips appear in Shell's queue, and does selecting one count as a new queue occurrence for learning?
- Is desktop's Related songs list worth reviving in Shell as a ranked "More like this" section, or is it deliberately dropped?
- Should a station's first page be capped by one overall deadline (today: up to 10 upstream pages plus 3 Turtle pages plus ranking) with a visible loading state?
