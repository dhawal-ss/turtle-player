# Turtle audit: Home surfaces and mix details in the Shell UI (Turtle Player at 09ae8be7)

Domain key: `home-ui`. Audited at commit 09ae8be7. Every row carries file:line evidence.

## Capabilities

| State | Capability | Evidence | Notes |
| --- | --- | --- | --- |
| TURTLE | Home filter chips: All / Music / Mixes / Podcasts / Audiobooks | ShellHome.kt:386-392 renders ShellChip for shell_filter_all, shell_filter_music, shell_filter_mixes, shell_sp_filter_podcasts, shell_sp_filter_audiobooks; Podcasts hidden when Settings.shared.hidePodcasts (ShellHome.kt:389, 293) | Chips scroll; the palette button is pinned (ShellHome.kt:381-394). Role.Tab with `selected` so screen readers announce which is on (ShellComponents.kt:157-165). |
| TURTLE | Shortcut grid: Infinity tile first, Liked Songs second, then pins, recents, suggestions | ShellHome.kt:419 `listOf<Any?>(null, LikedSongsTile) + layout.grid + List(pendingTiles){PendingTile}`; order built at HomeLayout.kt:210-218 (pinned, then recents, then round-robin) | GRID_TILES = 6 after the two fixed tiles (HomeLayout.kt:90). Slots held for late suggestions pulse and expire after 4 s (HomeLayout.kt:84, ShellHome.kt:361-366). |
| TURTLE | Local-first grid: the listener's own tiles draw before YouTube answers | HomeLayout.kt:76-81 `localReady`, HomeLayout.kt:221 `recents != null && mode != null && !state.isRestoringCache`; ShellHome.kt:413 draws PlaceholderShortcuts only while !layout.localReady | Recents come from Turtle's own event log plus upstream's song table (TurtleHomeRows.kt:80-99), so no network is needed. Suggestions only append, never displace a tile. |
| TURTLE | "Made for you" row of Turtle mixes, tinted square cards with artist preview | ShellHome.kt:485-505, titled Res.string.shell_made_for_you ("Made for you"); MixTile ShellHome.kt:626-651; MIX_TINTS ShellHome.kt:147; MIX_GRID_COLUMNS = 5 grid on wide desktop (ShellHome.kt:150, 490-498) | Space held by PlaceholderMixes until Turtle's shelves publish or 4 s pass (ShellHome.kt:353-358, 473), so nothing below jumps. |
| TURTLE | Mix detail page: open separate from play, over a frozen snapshot | ShellMixPage at ShellMixDetails.kt:236-355; MixSnapshot copies the list at ShellMixDetails.kt:86; opened from card art/title at ShellHome.kt:636-638 while onPlay plays (ShellHome.kt:639-646) | Opens over Home; Back restores Home's scroll (ShellMixDetails.kt:110-118, 263). Any row plays the mix from there in the order shown (ShellMixDetails.kt:128-134). |
| TURTLE | Mix "about" line: what it is made of and how often it changes | aboutWhat / aboutRefresh / aboutLine at ShellMixDetails.kt:174-228; strings at strings_home.xml:19-38 (shell_mix_about_daily_share, shell_mix_about_weekly, shell_mix_refresh_weekly "Refreshes on Mondays") | MixAbout is typed data with names only; the UI writes the sentences (MixAbout.kt:31-54, built at TurtleHomeShelves.kt:155-166). |
| TURTLE | Known share counted over the songs the page actually lists | ShellMixDetails.kt:166-167 `MixAbout.over(shown)` recounts known against knownSongs; knownPercent clamps to 1..99 so it never says 0% or 100% of a mixed mix (ShellMixDetails.kt:154-159) | Covered by MixSnapshotTest.theShareIsCountedOverTheSongsThePageLists. |
| TURTLE | Infinity card: cube cover from real mix art, genre summary, play/pause | InfinityCard at ShellHome.kt:590-623 with shell_infinity_label "INFINITY MIX · ∞", shell_infinity_tagline "Never stops.", shell_inf_tune; InfinityCube at ShellComponents.kt:337-351 with colour fallbacks | Never gated on the network (ShellHome.kt:508-511). The card opens the tuning sheet; the round button plays. |
| TURTLE | Jump back in row from the on-device play log | ShellHome.kt:515-519 titled Res.string.shell_home_jump_back_in ("Jump back in"); built at TurtleHomeRows.kt:75-109 from taste.jumpBackIn, filtered for spoken word and upstream blocks | Shown only when at least JUMP_BACK_IN_MIN_ITEMS = 3 remain after the grid takes its tiles (HomeLayout.kt:91, 225-230). |
| TURTLE | "Because you replayed / liked / listened to X" rows | HomeLayout.kt:184-185 splits moreLike from mixes; rendered at ShellHome.kt:522-525; titles from turtle_shelf_because_replayed / _liked / _listened via TurtleHomeShelves.kt:248-253 | Rendered as a card row, not a mix card, so they have no details page (see weaknesses). |
| TURTLE | Matched mode: at most three provider rows, scored and re-ordered on device | HomeRows.kt:163-189 select() with maxRows = 3, minScore = 0.15, maxDislikedShare = 0.2 (HomeRows.kt:48-52); applied at HomeLayout.kt:234-237; in-row order at HomeLayout.kt:277-288 via orderRowSongs (HomeRows.kt:236-273) | Rows Turtle could order but leaves alone become `comparisonRows`, attributed as Served.PROVIDER (HomeLayout.kt:252-267, ShellHome.kt:531-539): a built-in holdout. |
| TURTLE | "Not interested", and the Matched / All / Off setting for provider rows | ShellHomeFeedback.kt:69-84 arms the latch then opens upstream's menu; HomeNotInterestedRow :88-133; MixMenu :137-152; hidden items and shelves removed at HomeLayout.kt:162-168. HomeYouTubeRows at HomeLayout.kt:24-43, chosen at ShellSettingsTurtle.kt:430-432 | shell_home_not_interested_done points at "Settings › Recommendations", matching shell_set_recs (strings_settings.xml:26). |
| TURTLE | Artist picks card in the Made for you slot for a sparse profile | ShellHome.kt:471-483 shows ArtistPicksCard when lists.mixes is empty and picks.offer; card at ShellArtistPicks.kt:164-190 with Pick / Play Infinity Mix / Skip | New at HEAD (ArtistPicks.kt added in 09ae8be7). The device note says 4 saved picks built Daily Mixes, Weekly Discovery and Fresh for you with no history (status.md:57). |
| UPSTREAM-OK | Pull-to-refresh on Android Home | androidApp/.../HomeScreen.kt:358-376 PullToRefreshBox wraps homeContent(), and the Turtle hook sits inside HomeContent (shared/.../HomeContent.kt:215), so Shell's LazyColumn is inside the refresh box | It refreshes upstream only. Turtle's shelves refresh on their own schedule: start, every 8 plays, every 2 h (TurtleHomeShelves.kt:94-108). |
| UPSTREAM-HIDDEN | YouTube's own Home mood/genre chips (uiState.chips, toggleChip) | Built inside the `header` lambda at androidApp/.../HomeScreen.kt:340-346 and desktopApp/.../HomeScreen.kt:159-170; HomeContent.kt:215 returns into the Turtle hook before `header?.invoke(this)` at HomeContent.kt:235 | Shell draws five fixed chips instead (ShellHome.kt:386-392). Missing in Shell: no entry point for uiState.chips or toggleChip. YouTube's "moods" shelf is filtered out too (HomeLayout.kt:130-132). |
| UPSTREAM-HIDDEN | Row subtitles: YouTube's own section subtitle on every row | HomeShelf.label carries section.subtitle (HomeShelfBuilder.kt:391, :571) and upstream renders it via SectionTitle(label = shelf.label) at HomeContent.kt:691-693. Shell's ItemRow draws only shelf.title (ShellHome.kt:674, 683-686) | Missing in Shell: HomeSectionTitle takes a bare String with no subtitle slot (ShellHome.kt:654-664). It also drops shelf.thumbnail and headerArtistId, which give "Similar to X" its artist avatar and link. |
| UPSTREAM-HIDDEN | Row header actions: See all / Browse, Play all, Shuffle | HomeContent.kt:698-731 wires HomeShelfHeaderAction.BROWSE / LIBRARY / LOCAL / PLAY_ALL / SHUFFLE onto the row header. Shell reads shelf.headerAction nowhere; HomeSectionTitle has no action (ShellHome.kt:654-664) | Turtle sets PLAY_ALL on its own shelves (TurtleHomeShelves.kt:235) and that is dropped too. SectionHeader already supports action/onAction (ShellComponents.kt:173-182). |
| UPSTREAM-HIDDEN | Per-row "see all" section page (HomeSectionScreen / Screen.HomeSection) | androidApp/.../HomeScreen.kt:269 `onLocalShelfClick = onNavigateToHomeSection`; desktopApp/.../HomeScreen.kt:117-119 navigates Screen.HomeSection. HomeSlot carries no onLocalShelfClick (TurtleUiSlots.kt:96-113) | Missing in Shell: the slot needs the callback and ItemRow needs a header action to invoke it. A Turtle row shows at most 24 items (TurtleHomeShelves.kt:263) with no way to see the rest. |
| UPSTREAM-HIDDEN | Speed Dial "Surprise me" tile and pin / unpin from the tile menu | androidApp/.../HomeScreen.kt:275 onShowSurpriseMenu and :437-443 HomeSpeedDialSurpriseMenu; desktopApp/.../HomeScreen.kt:138-146. HomeSlot exposes no onSpeedDialItemLongClick, onSpeedDialItemSecondaryClick or onShowSurpriseMenu (TurtleUiSlots.kt:96-113) | Shell honours existing pins (HomeLayout.kt:187-188, 210) but a listener cannot create or remove one from Home. |
| UPSTREAM-HIDDEN | A Speed Dial song tap starts a radio instead of a single track | androidApp/.../HomeScreen.kt:258-264 `onSpeedDialItemClick` calls playerConnection.startRadio(item) for a SongItem. Shell's tile tap uses the generic slot.onItemClick (ShellHome.kt:266-267) | Missing in Shell: HomeSlot has no onSpeedDialItemClick, so the grid cannot start a radio. |
| UPSTREAM-HIDDEN | Greying out items that cannot play offline (isItemEnabled) | androidApp/.../HomeScreen.kt:163-165 builds isHomeItemEnabled from cachedAudioIds and connectivity and passes it at :274; HomeSlot has no isItemEnabled field (TurtleUiSlots.kt:96-113) | Shell shows every item as tappable while offline, and Turtle's shelves are not cache-filtered the way upstream's rows are. |
| UPSTREAM-HIDDEN | Shuffle-all-Home and music recognition entry points | androidApp/.../HomeScreen.kt:383 `if (!isTv && TurtleUiSlots.home == null)` (HOOKS.md #39) hides the FAB column holding onNavigateToRecognition and ::shuffleHomeContent (:405-421). No match for "recogni" in turtle/**/*.kt | Missing in Shell: no shuffle-everything action and no recognition entry anywhere in the Shell UI. |
| UPSTREAM-HIDDEN | Recap / listening-review playlist row | HomeShelfBuilder.kt:289-296 builds key "recap_playlists" with no isPersonal; its PlaylistItems map to RowItem.Collection(emptyList()) (HomeLayout.kt:301-307), so scoredItems = 0 fails minScoredItems = 3 (HomeRows.kt:170-174) | Missing in Shell: any playlist row can reach Matched Home only through the one personal reserve slot (HomeRows.kt:175-180). Otherwise it needs the All setting. |
| UPSTREAM-HIDDEN | "Keep listening" row (albums and playlists you were in the middle of) | HomeShelfBuilder.kt:122-127 builds key "keep_listening"; HomeLayout.kt:122-123 drops it, and FEmusic_listen_again rows, from Matched candidacy because Turtle's Jump back in replaces them | Turtle's replacement holds songs only (TurtleHomeRows.kt:99), so resuming an album or playlist is unreachable in the default mode. |
| MISSING | Spotlight / hero card for one featured mix per view | No match for "spotlight" anywhere under turtle/. The only hero on Home is the Infinity card (ShellHome.kt:509-511), which is the same card every day | Spotify leads each Home view and each chip with one large featured card, e.g. "Made for you: Mac Miller Mix". |
| MISSING | Recommended stations row on Home | No station row on Home: `station` under turtle/ appears only as a queue-occurrence key (PlayerObserver.kt:468) and in InfinityMix.kt:823. Home's items are the grid, mixes, Infinity, Jump back in, More like X and provider rows (ShellHome.kt:378-553) | Radio starts only from the player's Start radio; genres and moods feed only the single Infinity Mix (ShellHome.kt:593-614). |
| MISSING | "Your top artists" row on Home | No top-artists row anywhere: the only topArtists in turtle/ are MixAbout's three names (MixAbout.kt:37-38) and the mix page byline (ShellMixDetails.kt:95-97). Home renders no ArtistItem row of its own | Artists reach Home only if one of YouTube's artist rows passes Matched scoring. The profile already ranks artists (HomeRows.kt:217-223). |
| MISSING | Daypart / daylist shelf | No match for "daylist" or "daypart" under turtle/ or shared/ | The comparison doc notes the ranker already carries hour and weekday context, so the input exists. |
| MISSING | "Popular here" row on Home | ShellHome.kt:528 holds only the comment `// W7: "Top in your country" row goes here`. The honest unranked equivalent shipped on Explore: ShellExplore.kt:287-295 (shell_popular_picks, shell_popular_picks_source) | Explore is desktop plus the Android Browse tab, not Home, so Home has no popular surface at all. |
| MISSING | Details page for a "More like X" row | ShellMixDetails.open has exactly one production caller, MixTile (ShellHome.kt:637), and MixTile renders only layout.mixes, which excludes moreLike (HomeLayout.kt:184-185) | So shell_mix_about_more_like, _replayed, _liked and _listened (strings_home.xml:27-30) and MixSeedTie never reach a listener. |

## Weaknesses

### [BLOCKER] Desktop Home is replaced wholesale by upstream's placeholder and error screens

- Evidence: desktopApp/.../ui/screens/HomeScreen.kt:89-92 returns HomeLoadingPlaceholder and :95-97 returns RetryableError, both before HomeContent (:113) reaches the Turtle hook at shared/.../HomeContent.kt:215
- Why it matters: On Windows the local-first promise fails exactly when it matters. While YouTube loads, and whenever it errors, the listener gets an upstream placeholder or a bare "Home unavailable / Try again" instead of the Infinity tile, Liked Songs, their recents and their mixes, none of which need the network. Shell's own OfflineCard (ShellHome.kt:407-409) can never appear on desktop.
- Fix sketch: Guard both early returns with `TurtleUiSlots.home == null` in desktopApp/src/jvmMain/kotlin/com/metrolist/desktop/ui/screens/HomeScreen.kt, as androidApp/.../HomeScreen.kt:383 already does, and record the hooks in turtle/HOOKS.md. Also set ShellBrowse.home from TurtleUi.kt rather than a SideEffect inside ShellHome (ShellHome.kt:282).

### [MAJOR] The shortcut grid's local gate has no timeout, unlike every other Home gate

- Evidence: ShellHome.kt:413 draws PlaceholderShortcuts while !layout.localReady and localReady has no cap (HomeLayout.kt:221); the two neighbouring gates both expire after SHELVES_WAIT_MS = 4_000L (ShellHome.kt:153, 353-366)
- Why it matters: localReady needs recents != null AND mode != null AND !isRestoringCache. Both Turtle inputs are set inside suspend calls that can stall rather than throw (TurtleHomeRows.kt:75-108 awaits spokenReady() then taste.jumpBackIn; ShellHomeRows.kt:21-25 awaits TurtleBridge.uiPref). If either never returns, the Infinity and Liked Songs tiles, which need no data at all, pulse grey forever.
- Fix sketch: Add a capped wait in ShellHome.kt beside shelvesWaitOver and OR it into the localReady read, or give HomeLayout a `localWaitOver` flag so the two fixed tiles always render and only pins and recents wait.

### [MAJOR] On a phone no shortcut tile has a visible Play button, the Infinity tile included

- Evidence: ShellComponents.kt:259 `HoverPlayButton(hovered, 32.dp, onPlay, ...)`, and HoverPlayButton is an AnimatedVisibility on that flag (ShellComponents.kt:205-209); `hovered` is always false on touch (ShellComponents.kt:195-201)
- Why it matters: The Infinity tile is the first thing on Home and its only working action on a phone is onClick, which opens the tuning sheet (ShellHome.kt:443-444, 257-259); its onPlay is wired but never drawn. The same holds for every recent or suggested song tile (ShellHome.kt:455). MixCard already solves this in the same file.
- Fix sketch: In ShortcutTile (turtle/ui/.../components/ShellComponents.kt:236-262) gate the button on `!ShellPlatform.desktop || hovered || focusWithin`, mirroring MixCard (ShellComponents.kt:313), and give it a 48 dp touch target through HoverPlayButton's touchSize.

### [MAJOR] Tapping a song tile in the grid yields a one-song finite queue

- Evidence: ShellHome.kt:266-267 routes a SongItem to slot.onItemClick. Android: HomeScreenItems.kt:109 playSong(…, songs = emptyList()) → PlaybackUtils.kt:17-21 → PlayerConnection.kt:707-714 playQueue(listOf(song), finite = true). Desktop: HomeScreen.kt:275 PlayerState.playTrack(item)
- Why it matters: Because the hover Play button is invisible on phones, this tap is the only way to start a grid song, and playback stops after one track. Upstream's Speed Dial deliberately started a radio for a song (androidApp/.../HomeScreen.kt:258-264), so Shell's Home plays worse than the Home it replaced on its most-used control.
- Fix sketch: In ShellHome.kt:261-268 play the whole grid for a SongItem (reuse playShortcuts with lists.gridSongs), or add onSpeedDialItemClick to HomeSlot (shared/.../turtle/TurtleUiSlots.kt:96-113) and start a radio as upstream does.

### [MAJOR] No provider row on Home says why it is there, although the subtitle is in hand

- Evidence: HomeShelf.label is set from YouTube's own section subtitle (HomeShelfBuilder.kt:391, :571) and upstream renders it (HomeContent.kt:691-693). Shell's ItemRow prints only `title = shelf.title` (ShellHome.kt:674) through HomeSectionTitle, which takes a bare String (ShellHome.kt:654-664)
- Why it matters: Every Spotify Home row carries a one-line reason. Turtle's own mixes explain themselves, but only on the details page; the provider rows beside them say nothing, so a listener cannot tell a personalised row from an editorial one. The data is already parsed and carried through the bridge, so this is pure rendering.
- Fix sketch: Give HomeSectionTitle a nullable subtitle (ShellHome.kt:654-664) and pass shelf.label from ItemRow (ShellHome.kt:668-686); render Turtle's own label ("Made for you by Turtle", TurtleHomeShelves.kt:235) under the Made for you header the same way.

### [MAJOR] "More like X" rows cannot be opened, so their explanation strings are dead

- Evidence: ShellMixDetails.open has one production caller, MixTile (ShellHome.kt:637); MixTile renders layout.mixes only, and moreLike is split out at HomeLayout.kt:184-185 and drawn as a plain ItemRow (ShellHome.kt:522-525)
- Why it matters: These rows are Turtle's strongest explainability claim, and the four sentences written for them (strings_home.xml:27-30) plus MixSeedTie (MixAbout.kt:14-23, 112-118) are exercised only by MixSnapshotTest. A listener can play such a row but never see what it holds, how often it changes, or save it.
- Fix sketch: Render moreLike through MixTile/MixCard (ShellHome.kt:522-525), or add an open action to ItemRow that builds a MixSnapshot from the shelf plus TurtleBridge.mixAbout(shelf.key), exactly as ShellHome.kt:637 already does.

### [MAJOR] No row offers See all, Play all or Shuffle, including Turtle's own mixes row

- Evidence: Upstream wires five header actions (HomeContent.kt:698-731) and Turtle marks its shelves PLAY_ALL (TurtleHomeShelves.kt:235). Shell reads shelf.headerAction nowhere and HomeSectionTitle has no action parameter (ShellHome.kt:654-664), though SectionHeader supports one (ShellComponents.kt:173-182)
- Why it matters: A Turtle row shows at most 24 items (TurtleHomeShelves.kt:263) and a provider row whatever arrived, with no way to see the rest, shuffle a row, or play it whole from the header. Spotify gives every shelf a "Show all". Upstream's section page exists and is simply unreachable.
- Fix sketch: Add action/onAction to HomeSectionTitle (ShellHome.kt:654-664); add onLocalShelfClick to HomeSlot (shared/.../turtle/TurtleUiSlots.kt:96-113) and pass it from both hosts; map shelf.headerAction in ItemRow (ShellHome.kt:668-686).

### [MAJOR] Shell Home has no refresh or retry when YouTube fails

- Evidence: ShellHome.kt:407-409 shows OfflineCard with no action, and HomeSlot carries no refresh callback (TurtleUiSlots.kt:96-113); only onLoadMore is exposed, used for the paging retry (ShellHome.kt:542-551). Upstream offers Try again at androidApp/.../HomeScreen.kt:331-333
- Why it matters: On Android the only recovery is pull-to-refresh, undiscoverable from a card that never mentions it, and it refreshes upstream only, never Turtle's shelves (TurtleHomeShelves.kt:94-108). On desktop there is no Shell path at all. "Offline Retry" is already listed as unchecked in the device notes (status.md:57).
- Fix sketch: Add onRefresh to HomeSlot and pass viewModel.shared::refresh and HomeState::refresh from androidApp/.../HomeScreen.kt and desktopApp/.../HomeScreen.kt; put a Try again button on OfflineCard (ShellHome.kt:722-733) that also calls TurtleHomeShelves.refresh.

### [MAJOR] Mix card titles are white text over arbitrary artwork on light tints

- Evidence: ShellComponents.kt:304-308 draws the title in Color.White over the tint, with the artwork at alpha 0.55 (ShellComponents.kt:301). MIX_TINTS includes amber 0xFFE8B04B (ShellHome.kt:147) and the artwork itself is unconstrained
- Why it matters: The title is the only label on the card, and the card is the main way into a mix. White on #E8B04B is about 1.9:1, far under WCAG AA even for large text, and bright artwork pushes other tints the same way. Shell already computes a readable tint elsewhere (ShellPlayer.kt:190-193), so the capability exists and is unused here.
- Fix sketch: In MixCard (turtle/ui/.../components/ShellComponents.kt:278-321) choose the text colour from the tint's luminance, or add a bottom scrim behind the title; reuse readableTint from ShellPlayer.kt:182-194.

### [MAJOR] A mix's colour is keyed to its position, so it changes when the row reorders

- Evidence: ShellHome.kt:196 `MIX_TINTS[i % MIX_TINTS.size]`, where i is the index in layout.mixes; the tint is carried into the snapshot and the details cover fallback (ShellHome.kt:637, ShellMixDetails.kt:286)
- Why it matters: Shelves rebuild on every refresh (TurtleHomeShelves.kt:94-108) and a mix dropped under MIN_ITEMS shifts everything after it, so yesterday's blue mix is today's green one. The roadmap asks for "stable color keyed to collection identity" (roadmap:57). Colour is doing the identity work Spotify's numbered covers do, and it is unstable.
- Fix sketch: Derive the tint from a hash of shelf.key in ShellHome.kt:187-197 instead of the loop index, and consider numbering the Daily Mix covers in MixCard.

### [MAJOR] The All and Music chips render byte-identical Homes

- Evidence: The two ordinals are only ever compared together: ShellHome.kt:513 `val music = current == ALL.ordinal || current == MUSIC.ordinal`, and ShellHome.kt:300 for paging. No other branch distinguishes them
- Why it matters: Two of the five chips do nothing, which teaches the listener that the chip row is decoration. The roadmap asks to consolidate All and Music while their contents are identical, or give All a real mixed-media role (roadmap:49), and the comparison doc flags it as well.
- Fix sketch: Either drop the Music chip from ShellHome.kt:386-392, or give All one spoken-word card by letting ShellPodcastsSection or ShellAudiobooksSection contribute a single item (ShellHome.kt:400-405).

### [MAJOR] The Mixes chip still shows the recents grid and hides the wrong rows

- Evidence: ShellHome.kt:400-405 diverts only Podcasts and Audiobooks, so under Mixes the chips, grid, Made for you, Infinity and the More like X rows all render; jump_back_in is gated on `music` (ShellHome.kt:515) but moreLike at ShellHome.kt:522-525 is not
- Why it matters: A listener who taps Mixes still gets a grid of individual recent songs and pinned items, which are not mixes, and loses Jump back in while keeping More like X. The gating reads as accidental. The device notes recorded the same confusion on a sparse profile (roadmap:135).
- Fix sketch: In ShellHome.kt:407-553 gate the shortcut grid, the Infinity card and Jump back in on `current != MIXES.ordinal`, and keep mixes plus moreLike under Mixes.

### [MAJOR] Hiding a mix gives no confirmation and no undo at the point of action

- Evidence: MixMenu (ShellHomeFeedback.kt:137-152) hides on click and dismisses. The song and artist path does better: HomeNotInterestedRow swaps to shell_home_not_interested_done, "Hidden. You can undo this in Settings › Recommendations" (ShellHomeFeedback.kt:98, strings_home.xml:14)
- Why it matters: A long press plus one menu entry silently removes a whole mix from Home (HomeLayout.kt:167 hidesShelf) with nothing on screen saying what happened or where to undo it. An accidental long press on a phone costs a mix with no feedback at all.
- Fix sketch: Show the same done state, or a Shell toast with an Undo, in MixMenu (turtle/ui/.../screens/ShellHomeFeedback.kt:137-152); ShellToast already carries a Polite live region (ShellComponents.kt:360).

### [MAJOR] Provider rows offer no "Not interested", only Turtle's own rows do

- Evidence: ShellHome.kt:696-700: `if (place == PlayPlace.HOME_YOUTUBE) f(item) else ShellHomeFeedback.openMenu(item, f)` — the YouTube branch skips the arming call, so HomeNotInterestedRow never claims a target (ShellHomeFeedback.kt:92)
- Why it matters: Matched mode keeps at most three provider rows picked from the listener's taste, and those are precisely the rows a listener wants to push back on. The mechanism exists and is withheld from them, so a disliked provider row can only be removed by turning every provider row off in Settings.
- Fix sketch: Arm the latch for provider rows too in ShellHome.kt:696-700; HiddenSet already filters items out of every row, not just Turtle's (HomeLayout.kt:162-168).

### [MAJOR] Matched Home kept no provider rows at all on the test device

- Evidence: turtle/docs/plans/2026-11-23-status.md:100 records "Matched Home keeps no YouTube rows on the test phone", from false skips (80 of 128). The gate is HomeRows.kt:170-174 with minScore = 0.15 on `value = knownArtistShare * meanAffinity` (HomeRows.kt:44-48)
- Why it matters: The default mode can silently empty the lower half of Home. Because value is a product, a row needs both broad artist familiarity and high affinity, so a young or noisy profile fails every row at once, and the listener sees neither an explanation nor an offer to loosen the filter.
- Fix sketch: Surface the shortfall: when pick.scored is true and rowKeys is empty, show a line under the Infinity card offering the All setting (ShellHome.kt:527-541), and revisit minScore and the product form in turtle/engines/taste/.../HomeRows.kt:44-48.

### [MINOR] The mix card's artist line and the mix page's byline disagree, and always say "and more"

- Evidence: Card: ShellHome.kt:190-195 takes the first three distinct artists in song order, and ShellHome.kt:632 always wraps them in shell_and_more ("%1$s and more"). Page: ShellMixDetails.kt:95-97 prefers about.topArtists, ordered by how often each appears (MixAbout.kt:99-111)
- Why it matters: Opening a card changes the artists shown, which undercuts the honesty the mix pages are built on. "and more" is appended even when the mix has three artists or fewer, so the card overstates the mix's breadth.
- Fix sketch: Build the card subtitle from TurtleBridge.mixAbout(shelf.key).topArtists in ShellHome.kt:187-197, and append shell_and_more only when more artists exist than are listed.

### [MINOR] Home's notice is invisible to screen readers

- Evidence: HomeNotice (ShellHome.kt:795-816) is a plain AnimatedVisibility with no liveRegion, while ShellToast sets `liveRegion = LiveRegionMode.Polite` (ShellComponents.kt:360)
- Why it matters: The notice carries the one message explaining why nothing played: shell_home_infinity_empty, "Infinity needs a few plays first. Search for something you love." (strings_home.xml:7). A TalkBack user taps Play, hears nothing and gets no reason. TalkBack is listed as unchecked (status.md:57).
- Fix sketch: Add `semantics { liveRegion = LiveRegionMode.Polite }` at ShellHome.kt:806-814, or replace HomeNotice with ShellToast.

### [MINOR] Right-click does nothing on any Home card, tile or mix card on desktop

- Evidence: No onSecondaryClick anywhere in ShellHome.kt, while ShellCollection.kt:1273 and ShellArtist.kt:697 both use com.metrolist.shared.ui.menu.onSecondaryClick for the same purpose
- Why it matters: On Windows the only way to reach an item menu or "Not interested" from Home is holding the mouse button down, which no desktop user tries. Upstream's desktop Home wired onSpeedDialItemSecondaryClick for exactly this (desktopApp/.../HomeScreen.kt:138-146). The rest of Shell is consistent; Home is the exception.
- Fix sketch: Add .onSecondaryClick(longClick) to ShortcutTile and ItemRow's card (ShellHome.kt:449-458, 702-705) and to MixTile's menu trigger (ShellHome.kt:647).

### [MINOR] Mix detail rows always render as not downloaded

- Evidence: ShellMixDetails.kt:344 passes `downloaded = false` unconditionally to TrackRow
- Why it matters: A listener who downloaded a song sees no download mark on the mix page, unlike every other collection page, which passes the real state. The page therefore misreports what is available offline, on the one surface built around honest description.
- Fix sketch: Read the download state the way ShellCollection does and pass it at turtle/ui/.../screens/ShellMixDetails.kt:333-351.

### [MINOR] Nothing explains an empty Made for you after the artist picks are skipped

- Evidence: ShellHome.kt:471-506: with no mixes and picks.offer false (after ShellArtistPicks::skip), none of the three branches renders, so the whole section vanishes with no text
- Why it matters: A new listener who skips the picks gets Home with the grid and the Infinity card and no sign that mixes are coming or how to get them, while the shelves quietly wait for plays. Spotify never leaves an unexplained gap where a personal shelf belongs.
- Fix sketch: Add a fourth branch in ShellHome.kt:471-506 with a one-line note and a route back to the picks sheet (ShellArtistPicks.open), with a new key in turtle/ui/src/commonMain/composeResources/values/strings_home.xml.

## Strengths

- **Mixes state what they hold, counted over the songs actually listed** — ShellMixDetails.kt:166-167 recounts known against the shown list; :174-209 picks the sentence from real counts and makes no claim when total is 0; knownPercent clamps to 1..99 (:154-159) | beats Spotify: Spotify says "Made for you" and nothing more. Turtle prints "Built around Drake. You know 25% of these songs." and will not say 0% or 100% of a mix that is mixed, verified in MixSnapshotTest.
- **An opened mix is frozen, so what plays is exactly what is shown** — MixSnapshot copies the list at ShellMixDetails.kt:86 while shelves refresh underneath (TurtleHomeShelves.kt:96-108); playFrom returns the shown list and index (:92) and play preserves both (:128-134) | beats Spotify: A refresh never reorders or swaps a row under the listener's finger, and the queue order equals the list order.
- **Open and Play are separate actions on every mix, card and page alike** — MixCard takes distinct onOpen and onPlay (ShellComponents.kt:278-321); ShellHome.kt:636-646 wires art and title to details and the button to playback; the page repeats the split as Play and Save (ShellMixDetails.kt:301-314) | beats Spotify: Matches Spotify's affordance and keeps playback untouched while browsing: "Opening it never touches playback" (ShellMixDetails.kt:103).
- **Home's own half works with no network and no account** — localReady is satisfied from on-device data only (HomeLayout.kt:76-81, 221); recents come from Turtle's event log and upstream's song table (TurtleHomeRows.kt:80-99); kept shelves and their songs restore from SQLite before any call (TurtleHomeShelves.kt:83-93, 124-140) | beats Spotify: Spotify's Home is empty without a connection. Turtle shows the Infinity tile, Liked Songs, pins, recents and yesterday's mixes offline, and says so (strings_home.xml:6).
- **"Not interested" takes effect across the whole page immediately** — HomeLayout.kt:162-168 drops hidden shelves and filters hidden items out of every row; :180-181 applies it before the grid takes a tile; hiddenSet is Compose state (NotInterested.kt:134) so Home recomposes at once | beats Spotify: One action removes the item from the grid, the rows, the mixes and Jump back in together, and the confirmation names where to undo it (strings_home.xml:14).
- **Row selection and scoring run on device, off the UI thread, with no server profile** — TurtleHomeRows.kt:111-170 scores on Dispatchers.Default under a mutex and skips unchanged input; a scoring failure falls back to the first rows unscored rather than blanking Home (:165-166) | beats Spotify: The ranking that decides Home lives in the listener's own storage; nothing about their taste leaves the device.
- **The listener can cap or switch off the provider's recommendations** — HomeYouTubeRows at HomeLayout.kt:24-43, with MATCHED capped at HomeRowConfig.maxRows = 3 (HomeRows.kt:50), chosen from Settings › Recommendations (ShellSettingsTurtle.kt:430-432) | beats Spotify: Spotify offers no way to reduce or remove its recommendation shelves. Off leaves Home entirely the listener's own, and there are no ads or promoted rows to put back.
- **Turtle keeps a built-in holdout against the provider's own ordering** — HomeLayout.kt:252-267 splits picked rows into orderedRows and comparisonRows; SongOrder.ALTERNATE orders a row only on alternate days (HomeRows.kt:60, 74-75); the choice is attributed as Served.RANKED or Served.PROVIDER (ShellHome.kt:531-539) | beats Spotify: The product measures its own ranking against the provider's on the same rows and the same listener, instead of asserting that it is better.
- **Every mix states its refresh cadence in plain words** — aboutRefresh at ShellMixDetails.kt:212-219 maps ShelfCadence to shell_mix_refresh_daily, _weekly ("Refreshes on Mondays") and _live ("Updates as you listen") (strings_home.xml:36-38); cadences at turtle/engines/taste/.../Surfaces.kt:54-62 | beats Spotify: Spotify leaves the listener to guess when Discover Weekly or a Daily Mix changes.
- **Saving a mix is one action, not a like for each song** — ShellMixDetails.kt:246-254 calls TurtleBridge.expectBulkPlaylistAdd before handing the songs to upstream's picker, with the reason stated in the comment at :244-245 | beats Spotify: Bulk-saving a mix cannot silently distort the on-device taste profile, so the learning signal stays honest.
- **Mix cards are keyboard-operable and expose Play as a screen-reader action** — MixCard tracks focus-within so Play stays visible while focus moves onto the button, and registers a CustomAccessibilityAction for Play (ShellComponents.kt:291-296); shellPressable draws a 2 dp accent focus ring (ShellComponents.kt:117) | beats Spotify: The primary action on Home's main card works without a pointer, and the play affordance is always visible on touch (ShellComponents.kt:313).
- **The mix page warns when a queue will start shuffled** — ShellMixDetails.kt:319-327 shows shell_mix_shuffle_on, "Shuffle is on, so this mix will play shuffled.", when Settings.shared.persistentShuffleAcrossQueues is on and the queue is shuffled | beats Spotify: Instead of surprising the listener with a reordered mix, the page says the order it shows is not the order it will play.

## Doc claims that are stale at HEAD

- Doc says: status.md:48 heads the discovery work "Done on 23 Nov 2026, not committed", including mix details (:52) and the first-session items (:54).
  - Reality: All of it is committed in HEAD 09ae8be7: MixAbout.kt, ArtistPicks.kt and the ShellMixDetails additions appear in `git show --stat 09ae8be7`.
- Doc says: roadmap:11 "The opening desktop radio path, mix details, artist onboarding, and true charts are still open."
  - Reality: Mix details exist (ShellMixDetails.kt:236-355, opened at ShellHome.kt:636-638) and so does artist onboarding (ShellArtistPicks.kt:164-190, offered at ShellHome.kt:471-483). Only charts remain open for Home.
- Doc says: roadmap:50 "The mix artwork/title currently triggers the same action as Play."
  - Reality: They are separate: MixCard's onOpen opens the details page (ShellHome.kt:636-638) while its own Play button plays (ShellHome.kt:639-646); MixCard takes the two callbacks independently (ShellComponents.kt:278-321).
- Doc says: roadmap:49 "The current shelf-ready gate can defer local controls behind provider loading for up to four seconds."
  - Reality: Local tiles have their own gate now, HomeLayout.localReady (HomeLayout.kt:76-81, 221), read at ShellHome.kt:413; the 4 s cap applies only to Made for you and pending tiles. The new problem is that localReady has no cap.
- Doc says: Comparison doc, Charts row: "Trending now ... takes 10 and prints 01-10 as if they were ranks."
  - Reality: Renamed and de-ranked: ShellExplore.kt:287-295 renders "Popular picks" with "Trending songs from YouTube Music, in no ranked order." and draws no position column. It sits on Explore, not Home.
- Doc says: Comparison doc, Discover Weekly row: closing it needs an "Optional fixed tagline instead of the artist list".
  - Reality: The tagline exists, but only on the details page: shell_mix_about_weekly (strings_home.xml:31-32), chosen at ShellMixDetails.kt:202-203. The card still shows the artist list plus "and more" (ShellHome.kt:632).
- Doc says: Comparison doc, Spotlight hero row: "Only the Infinity card ... PARTIAL".
  - Reality: Unchanged at HEAD, and now the last unaddressed Home-level item in that table: no match for "spotlight" under turtle/, and ShellHome.kt:509-511 still renders the same Infinity card every day.

## Open questions

- Can TurtleBridge.uiPref ever fail to return rather than throw before the database opens? That is the one path leaving ShellHomeRows.mode null and so holding the grid on placeholders forever (ShellHomeRows.kt:21-25, HomeLayout.kt:221).
- Was hiding Shell behind desktop's two early returns (desktopApp/.../HomeScreen.kt:89-97) deliberate, or two hook lines not yet added? turtle/HOOKS.md records 85 of under 90 used, so two more is tight.
- Owner decision: should a tapped grid song play the whole grid, start a radio as upstream's Speed Dial did, or stay a single track? It changes both playback and what the taste engine is taught (ShellHome.kt:261-268).
- Should the unranked "Popular picks" surface also reach Home as the W7 row (ShellHome.kt:528), or stay Explore-only? The comparison doc's recommended Spotify split puts a popular-here row on Home.
- Should provider rows get "Not interested" (ShellHome.kt:696-700)? It is the only feedback control deliberately withheld, and Matched mode already keeps very few such rows.
- Does Matched's product score (knownArtistShare x meanAffinity, HomeRows.kt:44) need a floor or a different form, given it kept zero rows on the test device (status.md:100)?
- Which identity should a mix's colour key off: shelf.key, the seed artist, or the mix kind? The roadmap asks for stability (roadmap:57) without saying stable against what.
