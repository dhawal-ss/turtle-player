# Turtle audit: Explore, Browse, charts and the Infinity Mix

Domain key: `explore-charts`. Audited at commit 09ae8be7. Every row carries file:line evidence.

## Capabilities

| State | Capability | Evidence | Notes |
| --- | --- | --- | --- |
| TURTLE | Explore as a desktop sidebar destination | turtle/ui/.../screens/ShellDesktop.kt:329 NavRow(Glyphs.Explore, shell_explore, exploreSelected, ...); opened at :224-227 onExplore = { ShellDesktop.explore.open(top, stack) }; page drawn at :775-783 | Shell's own page over upstream's route, inside upstream's 8 dp gutters, so the page underneath keeps its state. |
| TURTLE | Explore as a phone bottom tab and a tablet rail item | turtle/ui/.../screens/ShellNavigation.kt:139 BottomTab(Glyphs.Explore, browseLabel, browseSelected, ... onClick = openBrowse); tablet rail at :296 RailItem(Glyphs.Explore, ...) | Answers the audit question directly: phones DO have an Explore entry point at HEAD. The tab is appended after upstream's items and never passed to upstream's itemModifier/onSelect (:138). |
| TURTLE | Browse opened over Search ("See all") | turtle/ui/.../screens/ShellSearch.kt:271-284 shell_browse_see_all button calling rememberOpenBrowse(); turtle/ui/.../screens/ShellBrowse.kt:127-130 | Back from Browse returns to Search; corroborated by turtle/ui/src/desktopTest/.../screens/BrowseTabTest.kt:17-32. |
| TURTLE | Explore anchored to route history; Back returns at the same scroll | turtle/ui/.../screens/ShellDesktopState.kt:98-129 (ExploreEntry keeps LazyListState + RiseIn; ExploreNav.open/visible/selected/follow); ShellBrowse.kt:138-157 BrowseOverlay with NavigationBackHandler | Fixes the roadmap's recorded "Back from Focus returned to Home, losing Explore" defect. State is in memory only (ShellBrowse.kt:37-43). |
| TURTLE | Explore's own FEmusic_explore load with 3h cache, single-flight and Retry | turtle/bridge/.../TurtleExplore.kt:118-153 (refresh joins an in-flight load, isFresh at :155-159), MAX_AGE_MS = 3h at :225, shared loader at :228-233 | Needed because signed-in Home carries no moods/charts/new releases on its first page. Stale data is kept when a later refresh fails (test aFailedRefreshKeepsWhatWasShown, TurtleExploreTest.kt:274). |
| TURTLE | Second request to FEmusic_charts when Explore has no chart songs | turtle/bridge/.../TurtleExplore.kt:96 needsCharts(), :171 charts = if (needsCharts) chartsPage(), :196-204 chartsPage() swallows failure | Explore without charts is still Explore: a charts-page failure is logged and the rest of Explore still renders. |
| TURTLE | Moods & genres tile grid | turtle/ui/.../screens/ShellExplore.kt:174-194 GenreGrid; tint algorithm at :84-95; columns 2/4/5/6 by width at :136-141 | Tiles come from Home's moodsAndGenres when present, else from Explore's own load (:124). |
| UPSTREAM-OK | A genre/mood category page opens as a real upstream route | Shell entry: ShellExplore.kt:186 home.onItemClick(g). Upstream impl: androidApp/.../ui/components/HomeScreenItems.kt:133-135 onNavigateToBrowse; desktopApp/.../ui/screens/BrowseScreen.kt:176 BrowseState.fetchBrowseData | Desktop route asserted in turtle/ui/src/desktopTest/.../shot/ShellExploreSignedInShotTest.kt:70 (Screen.Browse("FEmusic_moods_and_genres_category", "focus-params", "Focus")). The page is upstream's generic Browse screen, not Shell-styled. |
| TURTLE | New releases row on Explore | turtle/ui/.../screens/ShellExplore.kt:164 ExploreRow(shell_new_releases, ...); mapping at turtle/bridge/.../TurtleExplore.kt:81-82 (FEmusic_new_releases, excluding _videos) | Albums only; the "_videos" variant is deliberately excluded. |
| TURTLE | Popular picks: trending songs shown with no rank numbers | turtle/ui/.../screens/ShellExplore.kt:281-334 (doc comment :281-286, "No position column" at :313); source line string shell_popular_picks_source at values/strings.xml:102 | Plays as a queue of the shown songs; lime equalizer marks the playing one. The honest-labelling step of the roadmap is implemented. |
| TURTLE | Top albums (album charts) row | turtle/ui/.../screens/ShellExplore.kt:166 ExploreRow(shell_charts, albumCharts, home); mapping at turtle/bridge/.../TurtleExplore.kt:90 (chart shelves win over the charts page) | Labelled "Top albums" (values/strings.xml:103), again without positions. |
| TURTLE | Community playlists row | turtle/ui/.../screens/ShellExplore.kt:167; mapping at turtle/bridge/.../TurtleExplore.kt:91 sectionsByBrowseId(explore, FEmusic_community) | Only FEmusic_community sections feed it; playlists found on the charts page are not included. |
| TURTLE | Upstream block lists reapplied to Explore on every open, failing open | turtle/bridge/.../TurtleExplore.kt:161-166 refilter(), :206-221 mapped()/safeFilter(); filter wired at :231 BlockedContent.filterSections | Test: turtle/bridge/src/desktopTest/.../TurtleExploreTest.kt:296 blockListsApplyOnEachOpenAndFailOpen. Covers upstream blocks only, not Turtle's own controls. |
| UPSTREAM-OK | Context menu (long press) on Explore items | turtle/ui/.../screens/ShellExplore.kt:266 and :309 pass home.onItemLongClick; the callback is upstream's, handed over in shared/.../turtle/TurtleUiSlots.kt:100 and wired in androidApp/.../ui/screens/HomeScreen.kt:265 | So Not interested / add to playlist / share work from Explore with no Turtle code. |
| TURTLE | Search category tiles backed by YouTube Music's own categories | turtle/ui/.../screens/ShellSearch.kt:289-299 (categories from ShellBrowse.categories(explore.data)), fixed text-search tiles only as fallback at :300-306; ShellBrowse.kt:62-68 | Supersedes the comparison doc's "8 fixed tiles that each run a text search". |
| UPSTREAM-HIDDEN | "See all" to YouTube Music's full Moods & genres page | Upstream: shared/.../ui/screens/HomeContent.kt:699-705 header click -> BrowseItem(shelf.browseId); shelf built with browseId FEmusic_moods_and_genres in HomeShelfBuilder.kt:369-377; any browseId is fetchable (YouTubeBrowseOperations.kt:136-148) | Shell gap: ShellExplore.kt:178 uses SectionHeader(title) with no action, although SectionHeader already takes action/onAction (turtle/ui/.../components/ShellComponents.kt:173). Cheapest win in this domain. |
| UPSTREAM-HIDDEN | "See all" to the full New releases page | Upstream: shared/.../ui/screens/HomeShelfBuilder.kt:182-189 headerAction = BROWSE with browseId FEmusic_new_releases, rendered clickable at HomeContent.kt:699-705 | Shell gap: ShellExplore.kt:164 passes no action, so Explore shows only the first page of new releases with no way to the full page. |
| UPSTREAM-HIDDEN | A Charts destination (YouTube Music's own chart page) | Upstream can render it: YouTubeBrowseOperations.kt:136-148 getBrowsePageData takes any browseId; shelves carry browseId FEmusic_charts (HomeShelfBuilder.kt:201-207, 212-225) | Shell gap: Turtle consumes FEmusic_charts as data only (TurtleExplore.kt:74-93) and offers no navigable Charts entry. This is roadmap step 4 / P2's "provider Charts destination". |
| MISSING | Chart or "popular here" playlists openable as playlists | turtle/bridge/.../TurtleExplore.kt:84-91 keeps only SongItem and AlbumItem from the charts page; community takes PlaylistItem only from FEmusic_community sections | The "Hot Hits USA" pattern the comparison doc recommends (2026-11-23-spotify-comparison.md:96) has no implementation: chart playlists reaching the app are discarded. |
| MISSING | Ranked, dated chart issue with market, movement and plays | No rank, date, market or movement field exists: ExploreData (TurtleExplore.kt:30-41) holds flat lists only; deferred by design in turtle/docs/plans/2026-11-23-discovery-release-roadmap.md:94-98 | Blocked on a verified source: InnerTubeX cannot send the market-selecting request field and the response carries no issue date. |
| TURTLE | Infinity Mix genre and mood picker (28 genres, 10 moods) | turtle/bridge/.../InfinityMix.kt:77-116 InfinityGenres.all; sheet chips at turtle/ui/.../screens/ShellInfinity.kt:350-356; picks saved at InfinityMix.kt:232-238 | Ids are stable and persisted in the play-event store prefs; names are localized separately (ShellInfinity.kt:282-322). |
| TURTLE | Endless Infinity queue with automatic refill | turtle/ui/.../screens/ShellInfinity.kt:171-201 (watcher refills when fewer than REFILL_AT=3 remain, BATCH=6); batch builder at turtle/bridge/.../InfinityMix.kt:365-419 | Nothing repeats within a session, upload twins included (Taken, InfinityMix.kt:507-536). |
| TURTLE | Discovery dial with five stops that re-steers Up Next | turtle/ui/.../screens/ShellPlayer.kt:622-681 SteerCard; share curve at turtle/bridge/.../InfinityMix.kt:70; re-steer removal set at InfinityMix.kt:428-440 (keeps user-added songs and the next RESTEER_KEEP=2 mix songs) | Reachable from the phone player (ShellPlayer.kt:606), the desktop panel (ShellDesktop.kt:699), the Infinity sheet (ShellInfinity.kt:357) and Settings (ShellSettingsTurtle.kt:795). |
| TURTLE | Per-pick "why" label on mix songs | turtle/ui/.../screens/ShellInfinity.kt:265-277 InfinityWhyLabel; reasons at turtle/bridge/.../InfinityMix.kt:124-136; strings shell_inf_why_* at values/strings_infinity.xml:14-17 | "FROM YOUR FAVOURITES", "NEW · HIP-HOP", "BECAUSE YOU LIKED X", "NEW FOR YOU". |
| TURTLE | Blend target and actual batch accounting | turtle/bridge/.../InfinityMix.kt:144-149 InfinityBlend, set at :415; shown at turtle/ui/.../screens/ShellPlayer.kt:663-715 with strings shell_inf_blend_familiar/new/actual/sparse (values/strings_infinity.xml:19-27) | Says "Aim: 47% favourites" plus "Up next: 4 of 12 favourites" and explains a sparse profile, instead of claiming a blend it cannot reach. |
| TURTLE | Thumbs tune genre weights and seed "more like this" | turtle/bridge/.../InfinityMix.kt:268-300 feedback(); wired from likes at turtle/bridge/.../TurtleRuntime.kt:455-470 and from Less like this at :474-486; UI at ShellInfinity.kt:444-471 | Only tunes for the mix's own picks, judged by the bound surface tag, so an external replay of a mix song teaches nothing. |
| TURTLE | Implicit tuning from finished and quick-skipped plays | turtle/bridge/.../InfinityMix.kt:308-331 onPlayEvent (FINISH_STEP, KEEP_STEP after 60 s, SKIP_FACTOR on a quick skip); replays and step-backs excluded at :311 | A new song kept a minute becomes a "more like this" seed with no tap (:314-319). |
| TURTLE | Start fresh: forget the mix's tuning without losing history | turtle/bridge/.../InfinityMix.kt:337-352 startFresh(); two-tap confirm UI at turtle/ui/.../screens/ShellInfinity.kt:482-506 with a 4 s arm timeout | Clears picks, weights, likes, passes and pools; listening history is explicitly kept. |
| MISSING | Separate genre or mood station cards (several stations at once) | One saved station only: turtle/bridge/.../InfinityMix.kt:232-238 setGenres replaces the pick list and drops the dropped genres' pools; InfinityState.genres is a single list (:152-162) | Spotify's Browse/Home shows several mood and genre mixes side by side (2026-11-23-spotify-comparison.md:24, :87); Turtle has one tunable station. |
| MISSING | Starting a genre or mood as a station from Explore or a category page | turtle/ui/.../screens/ShellExplore.kt has no Infinity reference (imports :46-82, actions :186, :263); the only entry points are Home, the queue, the player and Settings (ShellHome.kt:247-259, ShellQueue.kt:150-157) | A tile opens upstream's category page; nothing maps a YouTube category to an InfinityGenre id. |

## Weaknesses

### [MAJOR] Signed in, Explore can show genres only, with no charts, new releases or Retry

- Evidence: ShellExplore.kt:121 ownLoad = homeGenres.isEmpty(); :131-132 ownPending/ownFailed also require genres.isEmpty(). Upstream appends Explore's moods to a fully paged signed-in Home (HomeMoodsFallback.kt:19-25, HomeViewModel.kt:951)
- Why it matters: Signed-in Home never carries chart sections (withoutRegionalCharts, HomeSectionPolicy.kt:33-34, applied at HomeViewModel.kt:625 and :965) and the fallback merges moods only. If a listener pages Home to the end before opening Explore, Explore becomes a bare genre grid: no Popular picks, no Top albums, no failure message, and no load is started.
- Fix sketch: In turtle/ui/.../screens/ShellExplore.kt make the own load unconditional (or condition it on any of newReleases/trending/albumCharts/community being empty rather than on homeGenres) and decouple ownPending/ownFailed from genres.isEmpty() so each empty section reports its own state. Add a bridge test beside turtle/bridge/src/desktopTest/.../TurtleExploreTest.kt:176.

### [MAJOR] Explore renders and loads nothing until Home has drawn

- Evidence: ShellExplore.kt:106-108 returns early with shell_explore_waiting when home == null; ShellBrowse.home is set only in ShellHome.kt:282 SideEffect. rememberExploreState is reached only after that early return (:122)
- Why it matters: Upstream has a default-open-tab setting (shared/.../ui/settings/AppearanceSettingsRegistration.kt:251 "defaultOpenTab"), so a listener who starts on Library or Search and taps Explore sees only "Explore fills in once Home has loaded." with no spinner, no Retry and no request in flight. It also makes item opening depend on a screen never visited.
- Fix sketch: Start the load from ShellBrowse/BrowseOverlay (turtle/ui/.../screens/ShellBrowse.kt:138-157) rather than inside ShellExplorePage, render Explore's own data with home == null, and open items through LocalNavigator (as rememberNavPosition already does, ShellBrowse.kt:111-117) instead of HomeSlot.onItemClick.

### [MAJOR] Six genre tiles on a phone out of about fifty loaded, and no "See all" anywhere

- Evidence: ShellExplore.kt:136-141 gives columns = 2 below 760 dp; :175 genres.take(columns * 3). Status handoff records a live signed-in load of 50 genres (turtle/docs/plans/2026-11-23-status.md:57)
- Why it matters: The phone Explore tab shows 6 of ~50 categories (desktop at most 18) and the page offers no route to YouTube Music's full Moods & genres page, so most of the catalog's browse surface is unreachable. Spotify's Browse exposes the full category list (2026-11-23-spotify-comparison.md:30, :64).
- Fix sketch: Pass action/onAction to SectionHeader in turtle/ui/.../screens/ShellExplore.kt:178 (the component already supports it, turtle/ui/.../components/ShellComponents.kt:173) and open BrowseItem("FEmusic_moods_and_genres") through home.onItemClick; raise the phone grid to 4 rows or let the grid scroll horizontally.

### [MAJOR] Popular picks is capped at ten songs and cannot open its source

- Evidence: ShellExplore.kt:126 .distinctBy { it.id }.take(10). ExploreData carries no source browseId or title (TurtleExplore.kt:30-41), and the signed-in source is a VL-linked "Trending" shelf (TurtleExplore.kt:86-89)
- Why it matters: The listener sees ten songs with no "See all" and no way to open the YouTube playlist they came from, so they cannot save it, shuffle the full list or return to it. The status handoff records 20 trending songs loaded (status.md:57), so half are discarded. This is the "popular playlist" pattern the comparison doc recommends (:96).
- Fix sketch: Add sourceBrowseId/sourceTitle to ExploreData in turtle/bridge/.../TurtleExplore.kt:30-41 and set them in ExploreMapping.from (:74-93); in ShellExplore.kt:165/288 give PopularPicks a See all that calls home.onItemClick with that source's PlaylistItem/BrowseItem.

### [MAJOR] Turtle's own "Not interested" songs and muted artists still appear on Explore

- Evidence: TurtleExplore.kt:231 filters only with BlockedContent.filterSections. Turtle's controls live elsewhere: muted artists at TurtleRuntime.kt:504-509, lessLikeThisTracks used by the mix at InfinityMix.kt:551-563. UpstreamBlocks.kt:63-64 flows upstream->Turtle only
- Why it matters: A listener who taps "Not for me" on a song or mutes an artist sees them again in Popular picks, New releases and Top albums. The Infinity Mix honours those controls and the radio paths honour them; Explore does not, so the control looks broken exactly where the listener is browsing.
- Fix sketch: Give TurtleExplore a second filter sourced from TasteEngine.controls (lessLikeThisTracks, mutedArtists) alongside BlockedContent in turtle/bridge/.../TurtleExplore.kt:228-233, reusing the recording-identity matching of InfinityMix.Exclusions (:543-564) so other uploads are caught; refilter on the controls version as refilter() already does for blocks (:161-166).

### [MAJOR] Podcast-typed chart rows reach Popular picks even with "Hide podcasts" on

- Evidence: TurtleExplore.kt:84-89 filters only isNonMusicalVideo; that is false for PODCAST_EPISODE because isVideoType excludes it (Models.kt:426-430, VideoType.kt:33-37). isPodcast is true for it (Models.kt:482-485). BlockedContent drops whole podcast sections only (BlockedContent.kt:169)
- Why it matters: The project's own chart probe found 24 of 100 rows in India's weekly chart typed PODCAST_EPISODE (status.md:101, roadmap:65). Those rows render as songs in Popular picks, play into a music queue, ignore the Hide-podcasts setting, and teach the taste engine nothing because TurtleSpoken.isSpokenWord excludes them (TurtleSpoken.kt:304-305).
- Fix sketch: In turtle/bridge/.../TurtleExplore.kt:84-89 also drop songs where isPodcast is true (or honour Settings.shared.hidePodcasts per item), and add a fixture to turtle/bridge/src/desktopTest/.../TurtleExploreTest.kt beside nonMusicalVideosAreNotPopularPicks (:149) covering a PODCAST_EPISODE-typed chart row and a genuine episode.

### [MAJOR] "Start Infinity Mix" from the sheet fails silently

- Evidence: ShellInfinity.kt:159-166 play() takes onEmpty = null and onNothing = {} by default; applyPicks calls play(controller, mixTitle) with both defaults at :223. Home's own entry does pass them (ShellHome.kt:254 onEmpty/onNothing)
- Why it matters: The sheet closes before the mix is built (ShellInfinity.kt:372-375). Offline, or with no playable candidates, nothing plays and nothing is said: the listener taps the primary button, the sheet disappears, and the app looks broken. The Home card path shows shell_home_infinity_empty; the sheet path shows nothing.
- Fix sketch: Thread onEmpty/onNothing through ShellInfinity.applyPicks (turtle/ui/.../screens/ShellInfinity.kt:220-225) from the sheet, keep the sheet open (or show a Shell toast/snackbar) until the first batch lands, and surface the same empty message used at ShellHome.kt:254.

### [MAJOR] Up to 20 seconds of silence with no progress while the first batch builds

- Evidence: InfinityMix.kt:387 withTimeoutOrNull(BATCH_TIMEOUT_MS) with BATCH_TIMEOUT_MS = 20_000L (:860); each genre fill does a search plus two radio calls (fillGenre :733-788). The sheet closes immediately at ShellInfinity.kt:374 close()
- Why it matters: On a slow connection the listener gets no spinner, no disabled button and no cancel between tapping Start and the first song. The roadmap's own device log recorded the mix replacing the queue 26 seconds in (roadmap:138).
- Fix sketch: Expose a building flag on InfinityState (turtle/bridge/.../InfinityMix.kt:152-162), set it around batchLocked, and render it in the sheet's primary button and on the Home Infinity card (turtle/ui/.../screens/ShellInfinity.kt:362-386).

### [MAJOR] Infinity genre seeds are hard-coded English search phrases

- Evidence: InfinityMix.kt:77-116 InfinityGenre("bollywood", "bollywood hits"), ("punjabi", "punjabi hits"), ("throwback", "2000s hits") etc., used verbatim at :744 source.search(genre.query). Acknowledged in turtle/docs/plans/2026-11-23-status.md:103
- Why it matters: Breaks the project's own rule against hardcoded English defaults (CLAUDE.md "No hardcoded fallback values"). For a listener whose content language is not English the seeds return the wrong catalog slice, so a mood like "focus" or a genre like "folk" returns anglophone results regardless of market and discovery quality degrades outside English markets.
- Fix sketch: Move the retrieval seeds into localized resources separate from the visible labels (roadmap:51 asks for exactly this), or seed from YouTube Music's own mood/genre category params already present in ExploreData.genres (turtle/bridge/.../TurtleExplore.kt:78-80) instead of a text search; keep InfinityGenre ids stable because they are persisted (:75).

### [MAJOR] Thumbs up on an already-liked mix song unlikes it and reports "Less like this"

- Evidence: ShellInfinity.kt:466-469 toggleLike(song) then onFeedback(now); ShellPlayer.kt:568 toast = if (up) infUpText else infDownText; strings at values/strings_infinity.xml:83-84 ("Got it. More like this." / "Got it. Less like this.")
- Why it matters: The thumbs-up button is drawn as a toggle (dot = liked) but the mix has no "undo my like" concept. Pressing it twice removes the song from Liked Songs and then tells the listener the mix will play less like it, which is not what happened: TurtleRuntime.watchLikes only feeds Infinity on a new like (TurtleRuntime.kt:462-467) and nothing records a pass.
- Fix sketch: In turtle/ui/.../screens/ShellInfinity.kt:444-471 report the actual action (liked / removed from Liked Songs) rather than mapping an unlike to the thumbs-down copy, and either disable the second press or add a third "no opinion" state; the caller strings are at ShellPlayer.kt:513-514.

### [MAJOR] White genre-tile labels fail WCAG AA on 4 of 11 tints at rest and 7 of 11 on hover

- Evidence: ShellExplore.kt:85 GenreTints; :243 fixed Color.White at Shell.type.body (15.sp, ShellTheme.kt:123) bold; hover blends toward white at :240 lerp(base, White, 0.08f). Computed: D9704A 3.30 -> 2.99, 2E9E7A 3.34 -> 3.00, B7792B 3.63 -> 3.22, 6B8E23 3.81 -> 3.35
- Why it matters: 15.sp bold is not WCAG large text, so 4.5:1 is required. Four tints already fail and hover pushes C4577A (4.21 -> 3.72), 2E7E9E (4.57 -> 3.97) and 3E6FD8 (4.70 -> 4.06) under as well. Search's tiles share the problem (ShellSearch.kt:159: 2E9E7A 3.34, B7792B 3.63). The roadmap flagged these numbers (roadmap:58) and they are unchanged.
- Fix sketch: Choose the label ink from the composited tile colour (the readableTint helper already used at ShellPlayer.kt:637) in turtle/ui/.../screens/ShellExplore.kt:238-248 and turtle/ui/.../screens/ShellSearch.kt:550-555, check hover and focus states too, and darken the failing entries of GenreTints (:85) and GENRE_TINTS (ShellSearch.kt:159).

### [MINOR] "See all" from Search opens a page with fewer categories than Search

- Evidence: ShellSearch.kt:285 cols = if (wide) 4 else 2 and :291 .take(GENRES.size) i.e. 8 tiles; Explore's grid shows columns * 3 = 6 on a phone (ShellExplore.kt:136-141, :175)
- Why it matters: Tapping "See all" next to eight category tiles lands on a page with six. The affordance promises more and delivers less, which reads as a bug.
- Fix sketch: Raise the phone row count in turtle/ui/.../screens/ShellExplore.kt:175 (at least ceil(8/columns) rows) or make the genre grid the full list with its own See all; keep the two surfaces' caps derived from one constant.

### [MINOR] Explore cannot be refreshed while it holds data, and stays stale after sign-in or a locale change

- Evidence: TurtleExplore.kt:155-159 isFresh(), MAX_AGE_MS = 3h (:225). The only caller asks for a non-forced load (ShellBrowse.kt:103 source.load(false)); force = true is reachable only from the failure Retry (ShellExplore.kt:160)
- Why it matters: After signing in, signing out, or changing content language/country, Explore keeps showing the previous account's or market's page for up to three hours with no pull-to-refresh and no Retry (the Retry button renders only in the ownFailed branch). Upstream invalidates its own caches on an account change (LibraryViewModel.kt:288-296); Turtle's Explore does not.
- Fix sketch: Add an invalidate() to turtle/bridge/.../TurtleExplore.kt (clear loadedAtMs and raw) and call it on an account or content-locale change next to upstream's existing signals; add pull-to-refresh or a header refresh to turtle/ui/.../screens/ShellExplore.kt:144.

### [MINOR] Two independent FEmusic_explore requests per session

- Evidence: Upstream requests it for its mood fallback at shared/.../viewmodels/HomeViewModel.kt:942 (and :584); Turtle requests it again at turtle/bridge/.../TurtleExplore.kt:230
- Why it matters: Signed in, the same page is fetched and parsed twice on a cold start, doubling the request and decode cost of the slowest part of Explore for no added content, and the two copies can disagree (upstream's is merged into Home, Turtle's is not).
- Fix sketch: Have TurtleExplore consume Home's already-fetched sections when available (HomeSlot.uiState.data) before issuing its own request, or share one cached fetch keyed by browseId in turtle/bridge/.../TurtleExplore.kt:228-233.

### [MINOR] "Up next re-mixed" can appear although nothing was removed

- Evidence: ShellInfinity.kt:208-217 resteer() returns true from isActive alone; the removal path may no-op when neither desktopExtras.removeQueueIndex nor controller.removeQueueItems nor removeAt is available (:237-246). The label is driven by that return at ShellPlayer.kt:657
- Why it matters: The dial tells the listener Up Next was re-mixed even where the queue could not be edited, so the one piece of feedback that proves the control worked is not tied to the work actually happening.
- Fix sketch: Make ShellInfinity.resteer report the removal outcome (turtle/ui/.../screens/ShellInfinity.kt:208-217 and removeQueued :237-246) and only increment resteered in ShellPlayer.kt:657 when removals or a refill actually landed.

### [MINOR] Only the genre grid has a loading state; the other four sections pop in

- Evidence: ShellExplore.kt:154-163 shows GenrePlaceholders/ExploreFailed for genres only; :164-167 render newReleases/trending/albumCharts/community with a bare isNotEmpty() check and no placeholder or reserved height
- Why it matters: On a signed-in cold open the page shows a header plus pulsing genre tiles, then four sections appear at once and shove the content the listener was reading. The roadmap asks for reserved artwork space and progressive loading (roadmap:49).
- Fix sketch: Reserve card-height placeholder rows for the four ExploreRow sections in turtle/ui/.../screens/ShellExplore.kt:164-167 while explore.loading, reusing the pulse used by GenrePlaceholders (:216-235).

### [MINOR] No duration bound on Infinity picks, so a 44-minute track can land in a mix

- Evidence: InfinityMix.kt:575 usable(song) = !song.isLive && song !in taken && song !in excluded; no duration test anywhere in the batch path (:365-419, :677-719)
- Why it matters: The project's own Windows walkthrough recorded Focus starting a 44:32 track (roadmap:138). A single long upload consumes a whole listening session, starves the dial's blend of its next picks, and reads as a broken mix.
- Fix sketch: Add an upper duration bound (with a mix-intent exception for Sleep/Focus/ambient) to Batch.usable in turtle/bridge/.../InfinityMix.kt:574-575, and extend turtle/bridge/src/desktopTest/.../InfinityMixTest.kt with a long-upload fixture.

### [MINOR] Every radio candidate is ranked against the first seed only

- Evidence: InfinityMix.kt:763-775 flat-maps the radios of all seeds, then calls rankByTaste(seeds.firstOrNull(), fresh); rankByTaste uses that single seed as the station anchor (:813-829)
- Why it matters: When a genre fill uses two seeds (SEEDS_PER_FILL = 2, :842) plus a liked song, songs pulled from the second seed's radio are scored against an unrelated anchor, so the taste ranking is weakest precisely where the pool is most diverse.
- Fix sketch: Rank each seed's radio against its own seed and interleave the ranked lists in turtle/bridge/.../InfinityMix.kt:763-781, keeping the Served bookkeeping per seed via noteServed (:480-488).

### [MINOR] Infinite pulse animation on the Explore placeholders with no reduced-motion path

- Evidence: ShellExplore.kt:218 rememberShellPulse() applied as a graphicsLayer alpha at :225; the helper is an unbounded infiniteRepeatable (ShellHome.kt:820-823). Upstream's rememberReducedMotion is hard-coded false on both shipping platforms (AndroidTheme.kt:45, DesktopTheme.kt:189)
- Why it matters: A listener with a motion sensitivity who has "remove animations" set at the OS level still gets a continuously pulsing grid, and it never stops while a load is slow or wedged. Shell's rise-in and hover transitions share the same gap.
- Fix sketch: Add a Shell-level reduced-motion read (platform expect/actual beside turtle/ui/.../theme/ShellMotion) and make rememberShellPulse (ShellHome.kt:820-823) and riseIn return static values when it is set; apply at ShellExplore.kt:218-230.

### [MINOR] Explore's position and scroll are lost after process death

- Evidence: ShellBrowse.kt:37-43 states it: "this state lives in memory and is not restored after the process is killed"; ExploreNav.entry is a plain mutableStateOf (ShellDesktopState.kt:106-108) and ExploreEntry holds a non-saved LazyListState (:99-103)
- Why it matters: On Android, returning after the system reclaims the app drops the listener out of Explore back onto the tab underneath, losing a deep browse position. Upstream has no Browse route to anchor to, which is why it is in memory, but the result is still a lost place.
- Fix sketch: Persist the Explore anchor and first visible index with rememberSaveable (or a small saver on ExploreNav in turtle/ui/.../screens/ShellDesktopState.kt:106-129) and restore it in BrowseOverlay (turtle/ui/.../screens/ShellBrowse.kt:138-157).

## Strengths

- **Popular picks refuses to print a rank it cannot source** — ShellExplore.kt:281-286 (doc comment: no chart issue, market or reporting period, so the positions are not a chart), :313 "No position column"; source line at values/strings.xml:102 "Trending songs from YouTube Music, in no ranked order." | beats Spotify: The owner's Spotify captures show dated, ranked chart issues with movement arrows and a Plays column (2026-11-23-spotify-comparison.md:34-36). Turtle has the same flattened data and chooses to show no ranks and name the source rather than imply a chart it cannot verify.
- **The discovery dial states a target and then reports what actually happened** — InfinityMix.kt:144-149 InfinityBlend(target, familiar, total, favouritesAvailable), set at :415; shown as "Aim: N% favourites" plus "Up next: 4 of 12 favourites" and a sparse-history note (ShellPlayer.kt:663-715, values/strings_infinity.xml:19-27) | beats Spotify: No surface in the owner's 14-screenshot Spotify inventory (comparison.md:24-36) shows a target-versus-actual recommendation blend. Turtle tells the listener when its own aim could not be met and why.
- **Every mix pick says why it was chosen** — ShellInfinity.kt:265-277 InfinityWhyLabel renders FROM YOUR FAVOURITES / NEW · <GENRE> / BECAUSE YOU LIKED <TITLE> / NEW FOR YOU from InfinityWhy (InfinityMix.kt:124-136); labels at values/strings_infinity.xml:14-17 | beats Spotify: Spotify's generated items in the owner's captures carry no per-track reason (comparison.md:24-36, :88 on Smart Shuffle). Turtle's reason is per song, visible in the queue and the player, and derived from the generator that actually produced it.
- **The dial takes effect inside the queue that is already playing** — InfinityMix.kt:428-440 resteerRemovals keeps songs the listener added and the next RESTEER_KEEP=2 mix songs, then drops the rest; ShellInfinity.kt:208-217 resteer refills with the new blend; triggered from the dial at ShellPlayer.kt:652-658 | beats Spotify: A familiar-to-new control that re-mixes Up Next within a song, while protecting manually added entries, has no counterpart in the owner's Spotify references (comparison.md:87-88).
- **Back from a category returns to Explore where it was scrolled, on both platforms** — ShellDesktopState.kt:98-129 (ExploreEntry keeps the LazyListState and entrance state; anchor visible/inSubtree/alive); ShellBrowse.kt:138-157 BrowseOverlay; asserted by BrowseTabTest.kt:17-32 and ShellExploreSignedInShotTest.kt:70 | beats Spotify: Not a Spotify win: it fixes the behaviour the project's own device walkthrough recorded as broken (roadmap:136), and it is done without adding an upstream route, so no hook budget is spent.
- **Explore degrades honestly: stale beats nothing, with offline and failure copy plus Retry** — TurtleExplore.kt:43-53 and :168-193 (data kept after a later refresh fails; failed set on an empty answer too); ShellExplore.kt:157-162 picks shell_explore_offline vs shell_explore_failed and offers Retry; tests at TurtleExploreTest.kt:250-295 | beats Spotify: An empty answer is treated as a failure rather than shown as an empty page, and the charts page failing still renders the rest of Explore, so a degraded provider never produces a blank browse surface.
- **Genre discovery is ranked on the device, against a local profile** — InfinityMix.kt:733-788 fillGenre seeds radios then orders them with taste.rankExternal (:813-829), with a 1.5 s timeout falling back to the provider's order and recording which happened (Served.RANKED/TIMEOUT/FAILED) | beats Spotify: Personalization runs against a profile stored on the device (turtle/engines/taste), so no listening profile leaves the machine to get a personalized mix; the equivalent service-side ranking requires a server profile.
- **Block lists are reapplied on every open and fail open** — TurtleExplore.kt:161-166 refilter() re-maps the kept pages so blocks made since are applied with no network; :215-221 safeFilter logs and shows Explore unfiltered rather than blanking the page; test blockListsApplyOnEachOpenAndFailOpen (TurtleExploreTest.kt:296) | beats Spotify: Blocking an artist from a menu takes effect the next time Explore is opened, with no refetch and no risk of an empty page if the filter itself fails.
- **One Explore implementation serves desktop, tablet, phone and Search's tiles** — ShellExplorePage is drawn by ShellDesktop.kt:782, ShellBrowse.kt:154 (phone/tablet overlay) and its data is reused by Search at ShellSearch.kt:289-296 via ShellBrowse.categories (ShellBrowse.kt:62-68) | beats Spotify: Nav state is shared too (ShellDesktop.kt:153 explore get() = ShellBrowse.nav), so the three surfaces cannot drift apart as the feature grows.
- **Explore carries no promoted, sponsored or self-dealing slot** — ExploreMapping.from builds the page from exactly five YouTube Music section kinds and nothing else (TurtleExplore.kt:74-93); ExploreData has no promotion or ad field (:30-41) | beats Spotify: Explore's order is YouTube Music's own section order with de-duplication only. There is no paid-placement path in the code and no ads anywhere on the page.
- **"Start fresh" is a real, confirmed reset of the recommender** — InfinityMix.kt:337-352 clears pools, liked seeds, more-like, weights, likes, passes and picks and writes UNSET so Play opens the picker again; two-tap confirm with a 4 s arm at ShellInfinity.kt:482-506; copy at values/strings_infinity.xml:85-87 | beats Spotify: The listener can discard everything the mix learned without losing their library or history, and the copy says exactly what is kept.

## Doc claims that are stale at HEAD

- Doc says: spotify-comparison.md:62 — "Browse genre tiles | Search shows 8 fixed tiles that each run a text search"
  - Reality: Search's tiles are YouTube Music's own categories and open their Browse page (ShellSearch.kt:289-299); the 8 fixed text-search tiles are only the fallback when no categories are loaded (:300-306).
- Doc says: spotify-comparison.md:63 and :65 — the category tiles and Music hub "Needs the phone Explore entry, step 6" / "YouTube parts on desktop Explore"
  - Reality: Explore is a phone bottom tab (ShellNavigation.kt:139) and a tablet rail item (:296), not desktop-only; the desktop sidebar row is ShellDesktop.kt:329.
- Doc says: spotify-comparison.md:69 — "Genre hub | Desktop opens YouTube Music's own category page in upstream's generic screen; phones have no route"
  - Reality: Android routes a BrowseItem from Explore to upstream's Browse screen (HomeScreenItems.kt:133-135 via HomeScreen.kt:232-243; route IncomingDestination.Browse at MetrolistApp.kt:2298). The page is still upstream's generic screen, not Shell-styled.
- Doc says: spotify-comparison.md:71 — "Desktop Explore has a 'Trending now' list. It merges every chart section, deduplicates, takes 10 and prints 01-10 as if they were ranks"
  - Reality: The section is "Popular picks" and prints no positions (ShellExplore.kt:288-334, "No position column" at :313). The 10-item cap is still there (:126).
- Doc says: spotify-comparison.md:72 — "Trending now's numbers are Turtle's own positions, not source ranks" (status MISSING, drop the numbers)
  - Reality: Done: no numbers, and a source line "Trending songs from YouTube Music, in no ranked order." (values/strings.xml:102, rendered at ShellExplore.kt:292-296).
- Doc says: discovery-release-roadmap.md:96 — "Remove the current 01-10 numbering…" citing ShellExplore.kt#L246 as "Current rank display"
  - Reality: There is no rank display at that or any line; ShellExplore.kt:246 is the decorative square inside GenreTile, and PopularPicks (:281-334) is unranked.
- Doc says: discovery-release-roadmap.md:136 — "Back from Focus then returned to Home, losing Explore and its position"
  - Reality: Explore is anchored to the route history and keeps its scroll (ShellDesktopState.kt:98-129, ShellBrowse.kt:138-157), with BrowseTabTest.kt:17-32 covering return from a category.
- Doc says: status.md:48 — "Done on 23 Nov 2026, not committed (discovery and release roadmap, steps 1 to 5)", covering Explore, signed-in Explore, the Browse tab and the Infinity blend target
  - Reality: All of it is committed: HEAD is 09ae8be7 "feat(turtle): discovery and release roadmap, steps 1 to 5".
- Doc says: status.md:57 — "Signed-in Explore tab fills (50 genres, 24 new releases, 20 Popular picks)"
  - Reality: Those are the loaded counts the loader logs (TurtleExplore.kt:179-182). The phone page displays 6 genres (ShellExplore.kt:136-141, :175) and 10 Popular picks (:126), with no See all.
- Doc says: spotify-comparison.md:64 — "Explore all categories | Desktop Explore shows 3 rows of Moods & genres and no 'See all'"
  - Reality: Still 3 rows and still no See all, but row width is now per-breakpoint: 6 columns above 1300 dp down to 2 on a phone, so a phone shows 6 tiles, not 18 (ShellExplore.kt:136-141, :175).

## Open questions

- Owner decision is still open on whether presenting YouTube's chart playlists inside Turtle (unranked, named as YouTube names them) is acceptable, and on consent for a signed-in chart probe (status.md:63-66).
- Should the discovery dial be hidden on non-Infinity queues and on podcasts/audiobooks? It renders unconditionally at ShellPlayer.kt:606 and ShellDesktop.kt:699, and the actual blend line only appears while the mix plays (ShellPlayer.kt:707).
- Should Explore also show Made for you / personal rows, or stay strictly "outside your usual" as its own copy promises (values/strings.xml:94-96, spotify-comparison.md:65)?
- Should a YouTube category tile be able to start an Infinity station for that category, and how would YouTube's category params map to the persisted InfinityGenre ids (InfinityMix.kt:75-121)?
- Does upstream's mood fallback actually fire before a typical listener opens Explore? It needs Home paged to the end (HomeMoodsFallback.kt:11, HomeViewModel.kt:951), so the severity of the genres-only Explore case needs a signed-in device check.
- Should Explore honour Turtle's own "Not interested" list and muted artists, or is "Explore is outside your taste" a reason to show them anyway? The two readings conflict and the code currently does neither deliberately.
- Should Popular picks page beyond 10, or open its source playlist instead? That decision sets whether ExploreData needs to carry source ids.
- Should Explore's category grid link to YouTube Music's full Moods & genres page, or should Turtle build its own full-category page (the comparison doc sizes the former S, the latter M)?
