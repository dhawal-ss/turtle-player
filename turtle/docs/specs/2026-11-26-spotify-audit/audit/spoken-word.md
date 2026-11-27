# Turtle audit: Podcasts, audiobooks and spoken word

Domain key: `spoken-word`. Audited at commit 09ae8be7. Every row carries file:line evidence.

## Capabilities

| State | Capability | Evidence | Notes |
| --- | --- | --- | --- |
| TURTLE | Shell podcast show page (cover, host, notes, Follow, Play latest, episodes) | turtle/ui/.../screens/ShellPodcastsPage.kt:117 ShellPodcastPage; installed at turtle/ui/.../TurtleUi.kt:50 and entered from shared/.../PodcastContent.kt:160 (hook #54) | Both hosts route through PodcastContent: androidApp/.../PodcastScreen.kt:109, desktopApp/.../PodcastScreen.kt:31, so the hook applies on phone and desktop. |
| UPSTREAM-OK | Follow / unfollow a show | shared/.../state/PodcastLibrary.kt:116 setPodcastSaved; Shell FollowPill at turtle/ui/.../ShellPodcastsPage.kt:345 and :366 | Desktop uses its own path (desktopApp/.../state/content/PodcastState.kt:108) which writes nothing locally. |
| UPSTREAM-OK | Save an episode for later and see the "Episodes for later" list | shared/.../PodcastLibrary.kt:89 setEpisodeSaved; Shell save button turtle/ui/.../ShellPodcastsPage.kt:449-458; list surfaced by turtle/ui/.../ShellLibrary.kt:266-271 (types "podcasts","SE") |  |
| UPSTREAM-OK | Episode resume: position saved during playback and restored on play | shared/.../state/PodcastResumePosition.kt:7-20; shared/.../PodcastPlaybackProgress.kt:51; androidApp/.../MusicService.kt:466; desktopApp/.../PlayerState.kt:3137 | Turtle deliberately does not duplicate this (TurtleSpoken.kt:108-112 comment); Shell only adds its own resume for books. |
| UPSTREAM-OK | Episode progress bar and "Played" marker on the show page | shared/.../ui/components/EpisodePlayedIndicator.kt:25-35 episodeProgressFraction; used by Shell at turtle/ui/.../ShellPodcastsPage.kt:437-446 |  |
| UPSTREAM-OK | Download an episode | androidApp/.../PodcastScreen.kt:157, desktopApp/.../PodcastScreen.kt:51; Shell menu item turtle/ui/.../ShellPodcastsPage.kt:465-468 (only when slot.onDownload != null) |  |
| UPSTREAM-OK | Play next / add to queue / share an episode link | turtle/ui/.../ShellPodcastsPage.kt:462-469 wired to slot callbacks; hosts at androidApp/.../PodcastScreen.kt:155-170 and desktopApp/.../PodcastScreen.kt:49-52 |  |
| TURTLE | Episode paging and in-show episode search (forces full paging while searching) | turtle/ui/.../ShellPodcastsPage.kt:136-146 paging, :130-133 filter, :382 FindEpisodes field |  |
| TURTLE | Home Podcasts chip with four shelves | turtle/ui/.../ShellHome.kt:389-402 chip and takeover; turtle/ui/.../ShellPodcasts.kt:123-153 shelves | Chip is hidden when upstream's hidePodcasts is set (ShellHome.kt:389). |
| TURTLE | Continue listening shelf for part-heard episodes | turtle/bridge/.../TurtleSpoken.kt:185-196 continueEpisodes reads shared/.../daos/SongDao.kt:62-69; rendered at turtle/ui/.../ShellPodcasts.kt:139-141 |  |
| TURTLE | New episodes shelf, with a per-show fallback when the feed is empty | turtle/bridge/.../TurtleSpoken.kt:171-179 (feed then first 8 followed shows); shelf at turtle/ui/.../ShellPodcasts.kt:142-144 | Feed is shared/.../YouTubeRepository.kt:1658-1667 (browseId VLRDPN, setLogin=true), so signed out it is always empty. |
| TURTLE | Your shows shelf (YouTube Music library plus local bookmarks) | turtle/bridge/.../TurtleSpoken.kt:155-165 merges getLibraryPodcasts with dao().subscribedPodcasts(); shelf at turtle/ui/.../ShellPodcasts.kt:145-147 |  |
| TURTLE | Popular shows shelf for discovery (ten topic searches, interleaved) | turtle/bridge/.../TurtleSpoken.kt:203-214; merged with YouTube's own podcast shelf at turtle/ui/.../ShellPodcasts.kt:110 |  |
| TURTLE | Spoken-word playback speed kept separate from music speed | turtle/ui/.../ShellSpoken.kt:159-183 applySpeed/setSpeed/setDefaultSpeed; stored via turtle/bridge/.../TurtleSpoken.kt:356-360; settings entry turtle/ui/.../ShellSettingsTurtle.kt:510-532 |  |
| TURTLE | Back 15 s, forward 30 s and a sleep timer in the player's spoken controls | turtle/ui/.../ShellSpoken.kt:266-283 buttons, :224-236 sleep, :301-317 chips including "End of episode"; placed at turtle/ui/.../ShellPlayer.kt:599 and ShellDesktop.kt:698 |  |
| TURTLE | Controls self-gate to spoken word or any long-form video | turtle/ui/.../ShellSpoken.kt:108-112 showsControls (isSpokenWord, or isVideoSong with duration >= TurtleSpoken.MIN_BOOK_SECONDS) |  |
| UPSTREAM-OK | Skip silence during playback | shared/.../settings/Settings.kt:450; shared/.../PlaybackSettingsRegistration.kt:92-100; androidApp/.../SilenceSkippingSupport.kt:97; shared/src/desktopMain/.../ffmpeg/AudioRenderer.kt:33; Shell passes the Playback page through at ShellSettingsTurtle.kt:538-546 | Global, not spoken-word-specific, and not surfaced on Shell's Spoken word settings page. |
| UPSTREAM-OK | SponsorBlock sponsor-segment skipping inside podcast episodes | shared/.../sponsorblock/SponsorBlock.kt:31-37 canSkipPodcastSegments; androidApp/.../MusicService.kt:980-987; shared/src/desktopMain/.../MusicPlayer.kt:271-280; toggle at PlaybackSettingsRegistration.kt:85-90 | Default off (Settings.kt:449). Gated on musicVideoType == PODCAST_EPISODE only, so episodes identified by podcastId alone are skipped over. |
| TURTLE | Audiobook catalog by category, from YouTube search | turtle/bridge/.../TurtleSpoken.kt:38-48 categories, :221-243 audiobooks(); chips and grid at turtle/ui/.../ShellAudiobooks.kt:93-103, :186-201 |  |
| TURTLE | Per-book resume: saved chapter and second, surviving a restart | turtle/bridge/.../TurtleSpoken.kt:314-329 open, :335-347 record, :381-398 encode/decode; tracker at turtle/ui/.../ShellSpoken.kt:126-157 with a pending seek |  |
| TURTLE | Audiobook Continue listening row with part, time left and remove | turtle/ui/.../ShellAudiobooks.kt:164-171 row, :255-283 BookProgressCard with the Remove button at :281; fraction from TurtleSpoken.kt:79-84 |  |
| TURTLE | Spoken word never teaches music taste | turtle/bridge/.../TurtleSpoken.kt:304-305 isSpokenWord; gates at turtle/bridge/.../PlayerObserver.kt:274, TurtleHomeRows.kt:98 and MetrolistHistoryImport.kt:33 | Book chapters are only known as spoken word once Turtle has opened that book (bookOf map), so a chapter played from search still counts as music. |
| TURTLE | Podcast shelves kept out of Shell's music Home | turtle/bridge/.../HomeLayout.kt:129-130 drops PODCASTS_KEY and any shelf whose items are all isPodcast | Bypassed when the YouTube-rows setting is ALL (HomeLayout.kt:120), which lets podcast shelves back into the music Home. |
| TURTLE | Library Podcasts filter: followed shows plus New episodes and Episodes for later | turtle/ui/.../ShellLibrary.kt:266-276 auto playlists, :1330 UpFilter.PODCASTS -> PodcastItem only; chip list at :631 | Effectively desktop-only: Android bypasses the library hook for this filter (see the next row). |
| UPSTREAM-OK | Android podcast library with Episodes / Channels / Downloaded tabs and download sorting | androidApp/.../PodcastLibraryScreen.kt:109 tabs, :267-300 downloaded list; entered from androidApp/.../LibraryScreen.kt:452-470, which renders it instead of LibraryContent | It bypasses hook #37 (shared/.../LibraryContent.kt:257), so on Android this screen appears in upstream's Material look inside Shell. |
| UPSTREAM-HIDDEN | Downloaded-episodes view on desktop | The tabbed view lives only in androidApp/.../PodcastLibraryScreen.kt:267-300; desktop routes PODCASTS through shared/.../LibraryContent.kt:257 into turtle/ui/.../ShellLibrary.kt:1330, which keeps only PodcastItem | Shell is missing an episodes/downloaded list for the Podcasts filter: no episode rows, no downloaded tab, no "play all downloaded". |
| UPSTREAM-HIDDEN | Episode details sheet: full show notes, view count, go to channel | shared/.../PodcastContent.kt:188-205 EpisodeDetails and :727-763; episodeStats at :654-658. Shell never reads slot.detailsInDialog (no match in turtle/) | Shell shows two ellipsised lines of description (ShellPodcastsPage.kt:434-436) and no way to read the rest; desktop even passes detailsInDialog=true (desktopApp/.../PodcastScreen.kt:37). |
| UPSTREAM-OK | Podcast and Episode search filters | turtle/ui/.../ShellSearch.kt:172-173 maps SearchFilter.PODCAST and SearchFilter.EPISODE to Shell chips; result kind label at :707 |  |
| MISSING | In-episode chapters, markers and transcripts | No match for chapter|macroMarkers|chapterRenderer anywhere in shared/src, androidApp/src or desktopApp/src outside Turtle's own audiobook "chapters" (playlist items) | YouTube timed text is already fetched for lyrics (shared/.../repository/LyricsRepository.kt:848-861), and that path has no isPodcast/isEpisode gate, so an episode transcript could reuse it. |
| MISSING | Mark an episode played/unplayed, and clear one from Continue listening | No match for markPlayed|markAsPlayed|mark.*played in shared/src, androidApp/src, desktopApp/src or turtle/; Shell's episode menu is turtle/ui/.../ShellPodcastsPage.kt:461-470 (save, play next, queue, download, share) | Books can be removed (ShellAudiobooks.kt:281); episodes cannot. Also missing: audiobook bookmarks/notes, auto-download of new episodes, and a timestamped share (shared/.../ShareUrls.kt:6-9 accepts startSeconds but no caller passes it). |

## Weaknesses

### [BLOCKER] Songs YouTube mistypes as PODCAST_EPISODE are treated as spoken word end to end

- Evidence: shared/.../model/Models.kt:482-484 isPodcast; turtle/bridge/.../TurtleSpoken.kt:305; turtle/ui/.../ShellSpoken.kt:160-162; shared/.../PodcastResumePosition.kt:14-20; shared/.../PodcastLibrary.kt:172 sets isEpisode=1
- Why it matters: One wrong field from YouTube makes a pop song skip the taste log and the history import, play at the spoken-word speed (1.5x if set), show back-15/forward-30, save a resume position so it restarts mid-song, and appear in the podcast Continue listening shelf. Turtle's own status doc puts this at 24 of 100 songs on India's weekly chart.
- Fix sketch: Add one predicate in turtle/bridge/.../TurtleSpoken.kt that requires corroboration beyond musicVideoType (non-blank podcastId, an album/show that is a known show id, or duration above a spoken floor) and use it for isSpokenWord, ShellSpoken.isSpokenWord/showsControls and continueEpisodes' filter. Upstream's isPodcast stays untouched; episodes reached from a show page keep podcastId.

### [BLOCKER] Nothing can be removed from the podcast Continue listening shelf

- Evidence: turtle/ui/.../ShellPodcasts.kt:182-225 EpisodeCard offers only play and slot.onItemLongClick; turtle/bridge/.../TurtleSpoken.kt:185-196 derives the shelf from stored positions with no exclusion list
- Why it matters: An episode abandoned after two minutes, or a mistyped chart song, sits on Home forever. Books have a Remove button (ShellAudiobooks.kt:281); episodes have no equivalent, no mark-as-played and no dismiss, so the listener cannot repair their own Home.
- Fix sketch: Add a dismissed-episode set to turtle/bridge/.../TurtleSpoken.kt (ui pref, same shape as spoken_books), filter it in continueEpisodes, and add a Remove glyph plus a Mark as played item to EpisodeCard in turtle/ui/.../ShellPodcasts.kt. Mark as played should write playbackPosition = duration through PodcastLibrary.updatePlaybackPosition so upstream's indicator agrees.

### [MAJOR] Continue listening order and its 20-item cap come from an unordered SQL query

- Evidence: shared/.../db/daos/SongDao.kt:62-69 has no ORDER BY; turtle/bridge/.../TurtleSpoken.kt:190-192 comments "newer rows come last" and takes the last 20. SongEntity writes use @Upsert (SongDao.kt:671-672), so rowid never refreshes
- Why it matters: Row order is insertion order of the song row, not when the listener last listened. An episode saved months ago and resumed today keeps its old rowid, so it sorts low and can fall outside take(20) entirely: the episode you were listening to this morning is missing from Continue listening.
- Fix sketch: Add a last-listened column or order by an existing timestamp: extend the query in shared/.../daos/SongDao.kt to select and ORDER BY the newest event timestamp for each episode, then drop the asReversed() assumption in TurtleSpoken.continueEpisodes.

### [MAJOR] Spoken-word speed is applied late on cold start and the speed pill can show a speed that is not playing

- Evidence: turtle/ui/.../ShellSpoken.kt:119-123 loads the saved speed in a separate coroutine from follow(); :133-136 applies speed only when the song id changes; :257 draws ShellSpoken.speed for any item showsControls accepts, while :160 applies it only to spoken word
- Why it matters: A resumed episode plays the first stretch at 1x and only speeds up at the next item. On a long lecture the pill reads 1.5x while audio runs at 1x, and tapping a chip there both applies the speed to that video and silently rewrites the saved podcast default.
- Fix sketch: In turtle/ui/.../ShellSpoken.kt await the stored speed before follow() starts (or re-apply on the speed value changing, not only on song id), and make the pill reflect the speed actually in force: show 1x for non-spoken items, or treat long-form video as spoken for both display and application.

### [MAJOR] Book progress and spoken speed are outside Turtle's own backup archive

- Evidence: Stored as ui prefs in turtle.db (turtle/bridge/.../TurtleSpoken.kt:120-121, :272, TurtleModule.kt:361-368; turtle/bridge/src/desktopMain/.../TurtleDatabase.desktop.kt:9). The archive holds only song.db and settings.json (shared/.../backup/BackupFormat.kt:22-25)
- Why it matters: Backup and restore, and any desktop or cross-platform move, silently drops every audiobook position and the spoken-word speed. Android cloud backup happens to cover turtle.db (androidApp/src/main/res/xml/backup_rules.xml excludes it nowhere), so the loss is invisible until someone restores on another machine.
- Fix sketch: Add a turtle entry to shared/.../backup/BackupFormat.kt and the backup/restore writers (an exported ui-pref JSON is enough, and keeps the Metrolist format readable), or export spoken_books and spoken_speed into settings.json from turtle/bridge/.../TurtleModule.kt.

### [MAJOR] Following a show needs a successful YouTube Music call and fails silently on desktop

- Evidence: shared/.../YouTubeRepository.kt:1288-1308 returns false on any failure; shared/.../PodcastLibrary.kt:122-124 then returns before writing anything local; desktopApp/.../PodcastState.kt:108-117 ignores the false and writes no PodcastEntity
- Why it matters: There is no local-only follow anywhere (the only PodcastEntity writers are PodcastLibrary.kt:62 and :141, both behind signed-in paths). A listener without a YouTube account taps Follow, the pill stays grey on desktop with no message, and Shell's empty state keeps telling them to "Follow a show to start" (strings_spoken.xml:11-12).
- Fix sketch: Write the PodcastEntity bookmark locally whether or not the remote like succeeds (shared/.../PodcastLibrary.kt:116-152), and surface the remote failure as a non-blocking note. Desktop also needs the failure path and real isSaving (desktopApp/.../PodcastScreen.kt:34 hardcodes false, so Shell's FollowPill never dims).

### [MAJOR] Home episode cards ignore server and local played state

- Evidence: turtle/ui/.../ShellPodcasts.kt:214-222 uses only episode.playbackPositionMs; the show page instead uses episodeProgressFraction (ShellPodcastsPage.kt:437). The feed parser does set playbackProgressPercent (shared/.../YouTubePodcastEpisodeParser.kt:172-183)
- Why it matters: An episode already finished on another device shows up in New episodes with no progress line and no Played check, so the listener starts it again. The two surfaces disagree about the same episode.
- Fix sketch: Call episodeProgressFraction(episode.id, episode.duration, episode.playbackProgressPercent) in EpisodeCard (turtle/ui/.../ShellPodcasts.kt) and render the Played row exactly as ShellPodcastsPage.kt:438-446 does.

### [MAJOR] Full show notes are unreachable: Shell drops upstream's episode details sheet

- Evidence: turtle/ui/.../ShellPodcastsPage.kt:434-436 clips the description to two lines and no Shell file references detailsInDialog; upstream's sheet is shared/.../PodcastContent.kt:188-205 and :727-763
- Why it matters: Episode notes carry the guest list, chapter times and links. Spotify shows them in full. Shell's show page expands the show's own description (ShellPodcastsPage.kt:325-334) but gives no way at all to read an episode's.
- Fix sketch: Reuse the existing data by adding an episode sheet to turtle/ui/.../ShellPodcastsPage.kt: title tap (or a menu item) opens a Shell sheet with cleanDescription-style notes, episode stats and the channel link, honouring slot.detailsInDialog for desktop and slot.onChannelClick.

### [MAJOR] The audiobook catalog only finds English-labelled uploads and misses short books

- Evidence: turtle/bridge/.../TurtleSpoken.kt:38-48 English category queries, :124-125 BOOK_WORDS/NOT_BOOK_WORDS, :229 requires duration >= MIN_BOOK_SECONDS (45 min, :118)
- Why it matters: A reading titled in Hindi, Spanish or Japanese is filtered out because its title lacks "audiobook" or "librivox", so the whole feature is English-only regardless of the listener's content language. The 45-minute floor also empties the Kids chip of single uploads, since children's readings are short.
- Fix sketch: Derive the query language from Settings.shared.contentLanguage / the system locale instead of literals in TurtleSpoken.kt, keep title keywords as a ranking signal rather than a hard filter (trust SearchFilter.VIDEO plus duration), and give AudiobookCategory a per-category minimum duration so KIDS can accept short readings.

### [MAJOR] Series volumes collapse into one entry, so later books never appear

- Evidence: turtle/bridge/.../TurtleSpoken.kt:246-250 bookKey keeps only the text before the first | ( [ - : and the first five words; applied as distinctBy at :240
- Why it matters: "The Lord of the Rings: The Fellowship..." and "...: The Two Towers" produce the same key, so only the first survives the de-duplication. Any series published with a colon or dash shows exactly one volume and the rest are unreachable from the catalog.
- Fix sketch: In TurtleSpoken.bookKey keep the subtitle after the separator and strip only the known noise tokens (full, unabridged, audiobook, "by <author>"), or de-duplicate on normalised title plus author plus duration bucket instead of a truncated prefix.

### [MAJOR] Finishing a book deletes it, and a scrub to the end does the same

- Evidence: turtle/bridge/.../TurtleSpoken.kt:341-345: near the end of the last chapter record() calls forget(), which drops the book from state and persists (:350-353). progressOf then returns null, so :337 stops recording
- Why it matters: There is no finished-books history, so a book heard to the end vanishes with no record that it was ever read. Dragging the seek bar into the last 30 seconds of a single-upload book erases its progress permanently, and further listening is no longer saved even after rewinding.
- Fix sketch: In TurtleSpoken.record keep a finished flag on BookProgress instead of calling forget, require playback (not a seek) to reach the end, and let ShellAudiobooks render finished books in a separate "Finished" row with a restart action.

### [MAJOR] A changed chapter playlist silently restarts the book from the beginning

- Evidence: turtle/bridge/.../TurtleSpoken.kt:320-321: when the saved chapterId is absent from the new id list, index falls back to 0 and position to 0; the branch at :324-327 only re-persists
- Why it matters: YouTube chapter playlists change constantly (takedowns, re-uploads, re-ordering). Hours of listening become chapter 1 at 0:00 with no warning, and the card still shows a progress line until the first record() overwrites it.
- Fix sketch: Keep the saved chapterIndex as a fallback when the id is gone (clamped to the new size), and when neither the id nor a plausible index survives, tell the listener the book changed instead of restarting it: a notice in ShellAudiobooks like the existing shell_sp_book_failed path.

### [MAJOR] Tapping an episode queues the show on desktop but plays one episode on Android

- Evidence: androidApp/.../PodcastScreen.kt:143-145 playSong(episode) with no queue; desktopApp/.../PodcastScreen.kt:44-47 playTrack(it, songs) with the whole show
- Why it matters: Same Shell UI, two behaviours. On Android playback stops dead at the end of the episode (or autoplay wanders into music radio), while on desktop the show continues. Spotify always continues the show.
- Fix sketch: Make the Android host queue the loaded episodes from the tapped index, as desktop does, in androidApp/.../PodcastScreen.kt (it already has withAllEpisodes for onPlay).

### [MAJOR] "Popular shows" is not a popularity ranking

- Evidence: turtle/ui/.../ShellPodcasts.kt:110 prepends YouTube's personalised podcast shelf (built at shared/.../HomeShelfBuilder.kt:300-312 as "Podcasts for you"); the rest is the top four hits of ten English topic searches (turtle/bridge/.../TurtleSpoken.kt:203-214)
- Why it matters: The label claims popularity for a mix of personalised recommendations and search-relevance hits, and credits nobody. Commit 09ae8be7 renamed music's "Trending now" to "Popular picks" precisely to stop that kind of claim, so this row now contradicts Turtle's own honesty rule.
- Fix sketch: Split the shelf in turtle/ui/.../ShellPodcasts.kt: one row for YouTube's own recommendations, credited to YouTube Music as the music rows are, and one "Shows to try" row for the search results, with new keys in strings_spoken.xml.

### [MAJOR] An audiobook's chapter list is reachable only by an unlabelled long press

- Evidence: turtle/ui/.../ShellAudiobooks.kt:215-225: for a playlist book the long press opens the playlist page, and onLongClickLabel is null for that case (only the single-upload case gets shell_more)
- Why it matters: There is no book page: nothing shows the chapter list, lets the listener jump to a chapter, or shows which chapters are done. The one route in is a long press that TalkBack announces without a label and that has no visible affordance at all on desktop.
- Fix sketch: Give BookCard a visible overflow control (as EpisodeRow has) and label the long-press action; better, add a small Shell book page in turtle/ui/.../ShellAudiobooks.kt that lists TurtleSpoken.chapters(book) with the saved chapter highlighted and per-chapter play.

### [MINOR] Two controls are both called "Playback speed", and the music speed is reset to a hardcoded 1x

- Evidence: turtle/ui/.../ShellSettingsTurtle.kt:510-532 adds a "Playback speed" item on the Spoken word page while upstream's own playbackSpeed item (shared/.../PlaybackSettingsRegistration.kt:176-178) stays on the Playback page passed through at :538-546; turtle/ui/.../ShellSpoken.kt:166 sets 1f
- Why it matters: A listener who set a global music speed loses it the first time they play a podcast: Shell puts music back to literal 1x, not to Settings.shared.playbackSpeed. The duplicate label also makes it impossible to tell which control is which.
- Fix sketch: In turtle/ui/.../ShellSpoken.kt:159-169 restore Settings.shared.playbackSpeed instead of 1f, and rename the spoken item (strings_spoken.xml) to something like "Speed for podcasts and books".

### [MINOR] Spoken-word strings are not plural-aware and one separator is hardcoded

- Evidence: turtle/ui/.../composeResources/values/strings_spoken.xml:15-16 use %1$d in plain strings for "min" and "h min"; turtle/ui/.../ShellSpoken.kt:352 formats the speed with Float.toString; turtle/ui/.../ShellPodcastsPage.kt:279 builds " • $count" in code
- Why it matters: Languages with plural categories cannot translate "1 min" correctly, the speed always prints a dot decimal regardless of locale, and the bullet separator cannot be translated at all. Shell uses pluralStringResource elsewhere (ShellPlayer.kt:704), so this is inconsistent with the project's own practice and with CLAUDE.md's plural rule.
- Fix sketch: Convert shell_sp_minutes and shell_sp_hours_minutes to plurals and use pluralStringResource in spokenLength; format the speed with a locale-aware number formatter; replace the inline separator with the existing shell_sp_meta_pair.

### [MINOR] Book progress writes are launched concurrently, so an older snapshot can win

- Evidence: turtle/bridge/.../TurtleSpoken.kt:128 writer scope on Dispatchers.Default; :368-376 persist() captures a snapshot then launches, and the Mutex does not fix the order in which coroutines reach it
- Why it matters: Two writes close together (a record() then a forget(), or two record ticks) can land out of order, so a book the listener just removed from Continue listening reappears, or up to ten seconds of position is lost. Upstream's equivalent chains writes with join() (shared/.../PodcastPlaybackProgress.kt:77-80).
- Fix sketch: Serialise the writes in turtle/bridge/.../TurtleSpoken.kt the way upstream does: keep the previous Job and join() it inside the new one, or push snapshots through a Channel consumed by a single writer.

### [MINOR] Progress and timer state are not exposed to screen readers

- Evidence: turtle/ui/.../ShellSpoken.kt:367-372 SpokenProgress draws two Boxes with no progress semantics; the sleep button at :276-282 shows an active dot but its label is the static shell_sp_sleep; Home episode cards expose no played state (ShellPodcasts.kt:216-222)
- Why it matters: A screen-reader user cannot tell how far through an episode or book they are, nor whether the sleep timer is on, even though both are shown visually. The seek bar itself does announce position (ShellPlayer.kt:303), so the gap is specific to these shelves.
- Fix sketch: Add Modifier.semantics { progressBarRangeInfo = ... } (or a stateDescription) to SpokenProgress in turtle/ui/.../ShellSpoken.kt, give the sleep control a state-aware label, and include time left and played state in the EpisodeCard/BookCard content descriptions.

### [MINOR] Book positions are only saved while certain Shell surfaces are alive

- Evidence: ShellSpoken.track is called from turtle/ui/.../ShellHome.kt:294, ShellPodcasts.kt:125, ShellAudiobooks.kt:123, ShellSettingsTurtle.kt:288, ShellSpoken.kt:250 (player) and playBook (:188); ShellLibrary, ShellSearch and ShellExplore never call it
- Why it matters: The ten-second save loop (ShellSpoken.kt:148) belongs to the tracker. In a session that resumes an audiobook from the notification or widget and only ever shows Library or Search, no position is written and the listening is lost.
- Fix sketch: Start the tracker once from Shell's root (turtle/ui/.../TurtleUi.kt, next to the slot installation) or from TurtleBridge.attachController, instead of relying on individual screens to call track().

## Strengths

- **Spoken word is structurally barred from music taste** — turtle/bridge/.../TurtleSpoken.kt:304-305 with gates at PlayerObserver.kt:274 (the only writer of the play log), TurtleHomeRows.kt:98 and MetrolistHistoryImport.kt:33 | beats Spotify: A podcast binge cannot bend the music recommendations, and the separation is enforced in code the owner can read rather than being an opaque server-side choice the listener must trust.
- **The audiobook shelf says where the books come from** — turtle/ui/.../composeResources/values/strings_spoken.xml:20 "Full readings from YouTube. Every book picks up where you left off."; the source is plain search (TurtleSpoken.kt:221-243) | beats Spotify: Provenance is stated up front instead of presenting a licensed-catalogue illusion, so the listener knows what they are getting and why quality varies.
- **Per-book resume that survives a restart, chapter list included** — turtle/bridge/.../TurtleSpoken.kt:314-329 and :381-398 persist the chapter ids with the position; turtle/ui/.../ShellSpoken.kt:137-145 seeks once the item is really loaded | beats Spotify: Resume works for arbitrary YouTube uploads and chapter playlists, which no licensed audiobook player can offer, and the position lives on the device rather than on a server.
- **A spoken-word speed that never leaks into music** — turtle/ui/.../ShellSpoken.kt:159-183: the speed is applied only to podcast episodes and opened books, and music is put back; the default is set in Shell's Spoken word settings page (ShellSettingsTurtle.kt:510-532) | beats Spotify: One remembered speed for spoken word with music left alone, instead of a per-item control the listener has to re-set.
- **Sponsor-segment skipping inside podcast episodes, plus skip silence, with no ads of its own** — shared/.../sponsorblock/SponsorBlock.kt:31-37 wired at androidApp/.../MusicService.kt:980 and shared/src/desktopMain/.../MusicPlayer.kt:271-280; skip silence at PlaybackSettingsRegistration.kt:92-100 | beats Spotify: Host-read sponsor reads can be skipped automatically and the app inserts no advertising or promoted episodes of its own.
- **Spoken controls also appear for long-form video** — turtle/ui/.../ShellSpoken.kt:111-112 showsControls accepts any isVideoSong of at least 45 minutes; placed unconditionally at ShellPlayer.kt:599 and ShellDesktop.kt:698 | beats Spotify: Lectures, sermons, interviews and uploaded readings get skip and speed controls, a class of listening a music-plus-licensed-podcasts app has no surface for at all.
- **Discovery shelves come from the listener's follows and plain searches, with no paid placement** — turtle/bridge/.../TurtleSpoken.kt:155-165 followed shows, :171-179 new episodes, :203-214 topic searches; no promoted or sponsored path anywhere in turtle/ | beats Spotify: No promoted shows or exclusives are injected into the shelves, so what is surfaced reflects the listener's own library and query results.
- **The show page is complete and keyboard-reachable** — turtle/ui/.../ShellPodcastsPage.kt:336-379 Follow, find and Play latest; :136-146 paging; :437-446 progress and Played; focus ring from shellPressable (ShellComponents.kt:101-125) | beats Spotify: Every control carries a visible focus ring and a semantics label on desktop as well as phone, so the page is fully operable from the keyboard.

## Doc claims that are stale at HEAD

- Doc says: turtle/docs/plans/2026-11-23-status.md:101 — songs YouTube types PODCAST_EPISODE "count as spoken word ... so they never teach taste or reach Home rows".
  - Reality: Wider: they also play at the spoken speed (ShellSpoken.kt:160), draw spoken controls (:111), save a resume position so they restart mid-song (PodcastResumePosition.kt:14-20) and reach podcast Continue listening (TurtleSpoken.kt:185).
- Doc says: turtle/docs/plans/2026-11-23-spotify-comparison.md:83 — "the sidebar lacks a Podcasts chip; the full Library page has it".
  - Reality: Accurate but narrower: ShellDesktop.kt:181 has no Podcasts chip and maps PodcastItem to PLAYLISTS (:377), and the Library-page chip is desktop-only because Android renders upstream's PodcastLibraryScreen instead (LibraryScreen.kt:452-470).
- Doc says: turtle/docs/plans/2026-11-23-spotify-comparison.md:51-52, :56, :63 — Audiobooks hero MISSING; New-episode hero and the Podcasts/Audiobooks chips PARTIAL.
  - Reality: Re-verified unchanged: neither 3f1f557e nor 09ae8be7 touched a file in this domain, and both sections still open with plain shelves (ShellAudiobooks.kt:159-180, ShellPodcasts.kt:134-152).

## Open questions

- Does YouTube's likePlaylist actually fail for a signed-out client? It lives in InnerTubeX, outside this repo, so Follow could only be traced to YouTubeRepository.kt:1296-1308. If it succeeds anonymously, that weakness is desktop-only.
- Is a chart song's podcastId reliably blank when only musicVideoType is wrong? The corroboration fix depends on it, and no fixture in shared/src/commonTest covers a mistyped chart song.
- Should finished books and finished episodes get a visible history, or does the owner want Continue listening to stay strictly current? That decision changes the fix for the finish-deletes-the-book defect.
- New-episode surfacing: the roadmap keeps this a Home card, and "new-music notifications" is on CLAUDE.md's rejected list. Does that rejection extend to new-episode notifications for followed shows?
- Shell exists only on Android and desktop (turtle/ui/build.gradle.kts:17,27), so iOS still runs upstream Metrolist's podcast UI and none of these Turtle surfaces. Is iOS in scope for the spoken-word plan?
- Android's Library Podcasts filter renders upstream's PodcastLibraryScreen instead of Shell (LibraryScreen.kt:452-470). Accepted seam, or should Shell take it over and gain the Episodes/Downloaded tabs on both platforms?
- Should Turtle's own backup archive carry turtle.db, or should spoken_books and spoken_speed be exported into settings.json? Android cloud backup covers turtle.db today, which hides the gap until a desktop restore.
- Spotify specifics (audiobook hour allowances, speed steps, chapter behaviour) were not researched: no public documentation was fetched, so every beats_spotify_how claim here is qualitative.
