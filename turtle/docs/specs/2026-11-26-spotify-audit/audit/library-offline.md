# Turtle audit: Library, playlists, downloads and offline

Domain key: `library-offline`. Audited at commit 09ae8be7. Every row carries file:line evidence.

## Capabilities

| State | Capability | Evidence | Notes |
| --- | --- | --- | --- |
| TURTLE | Library page (filters, sort, grid/list, manual order, in-library search) | turtle/ui/.../screens/ShellLibrary.kt:220 ShellLibraryPage; hook at shared/.../ui/screens/LibraryContent.kt:257 returns into it | Shell replaces upstream's LibraryContent wholesale but passes every row through upstream's itemContainer (ShellLibrary.kt:407,466), so long-press menus and swipe-to-queue survive. |
| TURTLE | Library filter chips: Tracks, Playlists, Artists, Albums, Podcasts | ShellLibrary.kt:629-653 draws a ShellChip per UpFilter; enum at LibraryContent.kt:154-160; Podcasts dropped when Settings.hidePodcasts (ShellLibrary.kt:631) | No Downloaded chip; separate row. |
| TURTLE | Library sorting: 10 sorts, stored per filter, with direction | ShellLibrary.kt:699-763 sort sheet; availability ShellLibrary.kt:1335-1342 mirrors LibraryContent.kt:855-878; persisted via Settings.setLibrarySorting (ShellLibrary.kt:731) | shellLibSort (ShellLibrary.kt:1352-1389) re-implements upstream sortLibraryItems faithfully; a future upstream sort will silently not appear in Shell. |
| TURTLE | Manual library order: drag to reorder plus an accessible move menu | ShellLibrary.kt:1048-1198 (ShellLibHandle, ShellLibReorder), edge auto-scroll at :315-340; commits through upstream moveLibraryItems (:300) | Shell reimplements the drag because upstream uses sh.calvin.reorderable, invisible to :turtle-ui. Screen readers get the move menu (ShellLibrary.kt:1066-1072). |
| TURTLE | In-library search including "songs inside your playlists" | ShellLibrary.kt:521-578 songs section, paging at :345-352; ranked typo-tolerant index via bridge hook #4 (libraryContentSearchOverride in shared/.../viewmodels/LibraryViewModel.kt) | Turtle's engine replaces upstream's SQL LIKE and fails open to upstream when the index is not ready. |
| TURTLE | Desktop "Your Library" sidebar with filters, search and collapse | ShellDesktop.kt:383-495 LibraryCard; fed by DesktopLibrarySlot (TurtleUiSlots.kt:198-201) registered in desktop Main.kt (HOOKS.md #30) | Shell's own surface. It receives only items() and open(), so anything beyond opening an item has to be rebuilt here. |
| TURTLE | Playlist page: play, shuffle, smart shuffle, save, edit, add songs, reorder, more | ShellCollection.kt:341-409, ActionRow :1094-1118, drag reorder :319-329 and :484-487; upstream's search and sort controls drawn at :415-429; hook at PlaylistContent.kt:173 | Save is hidden for your own editable or local playlists (ShellCollection.kt:269), matching Spotify. No Download control; see weaknesses. |
| TURTLE | Add-to-playlist sheet: multi-select, duplicate warning, create new | ShellPlaylistPicker.kt:68-114; fired from MobilePlaylistActions.kt:119, which Android reaches via rememberSongsPlaylistAdder (MobilePlaylistWorkflow.kt:274-291, MetrolistApp.kt:721) | Android only. Desktop keeps upstream's AddToPlaylistDialog.kt; iOS never loads Shell. |
| UPSTREAM-HIDDEN | Desktop library grid column count (density menu) | Upstream menu LibraryContent.kt:797-815 and setting AppearanceSettingsRegistration.kt:176-186; Shell hardcodes GridCells.Adaptive(180.dp) at ShellLibrary.kt:373 | Cheapest win here: read Settings.shared.libraryGridColumns at ShellLibrary.kt:373. The setting is already visible in Shell's settings and does nothing. |
| UPSTREAM-HIDDEN | Phone pinch-to-change grid columns | Upstream gesture Modifier.libraryGridPinch at LibraryContent.kt:607-639; Shell reads the stored value at ShellLibrary.kt:369 but attaches no pointerInput | Shell honours libraryPinchColumns if a value exists, so pre-Shell installs keep their column count and nobody can change it. |
| UPSTREAM-HIDDEN | Add-to-playlist picker sort (6 sorts plus direction) | PlaylistPickerOrderControls.kt:57 is used only at PlaylistChooser.kt:205, which Shell bypasses (MobilePlaylistActions.kt:119). Shell gets editablePlaylistsByCreateDateAsc order (MobilePlaylistActions.kt:80) | Desktop keeps it (AddToPlaylistDialog.kt:91). On Android the sheet is stuck oldest-first and the stored playlistPickerSort (default LIBRARY, Settings.kt:745) is ignored. |
| UPSTREAM-OK | Auto playlists above the library (Liked, Downloaded, Top 100, Cached, Uploads) | Built at shared/.../ui/screens/ScreenContentText.kt:193-249; drawn by Shell at ShellLibrary.kt:399-423 with Shell tiles (:925-947) | Visibility toggles (showLikedPlaylist etc., Settings.kt:569-573) are registry settings, so they are reachable in Shell's settings. |
| UPSTREAM-OK | Playlist rename, description and custom cover (local file or YouTube upload) | androidApp/.../screens/PlaylistScreen.kt:226-330 (rename, editDescription, persistLocalCoverBytes, uploadPlaylistCover); opened by Shell's Edit button at ShellCollection.kt:1106 | Covers are stored as file paths in Settings (Settings.kt:756-767), not in the library database. |
| UPSTREAM-OK | Create a playlist, and import from Spotify, Apple, Amazon, Deezer, Tidal or CSV | Android FAB androidApp/.../screens/LibraryScreen.kt:778-812; desktop FAB desktopApp/.../screens/LibraryScreen.kt:389-420; importers in shared/.../playlistimport/ | Both FABs sit outside LibraryContent, so Shell's takeover does not hide them. Desktop routes Import into Settings -> Backup & restore. |
| UPSTREAM-OK | Download a song, album or playlist | Actions built in shared/.../ui/menu/MediaMenuModel.kt:235-237 (song), 559-604 (playlist); Shell restyles but keeps upstream's menus (HOOKS.md #50-53), opened from ShellCollection.kt:1110 and ShellLibrary.kt:466 | Reachable only inside the "more" menu. There is no header Download toggle; see weaknesses. |
| UPSTREAM-OK | Download queue screen: pause, resume, cancel, retry, typed errors | androidApp/.../screens/DownloadsScreen.kt:78-113, failure codes :803-814; reached from the Library top bar (MetrolistApp.kt:1540-1552) and DownloadQueuePanel on the Downloaded page (AutoPlaylistScreen.kt:828-839 via ShellCollection.kt:1165) | Android only. The top-bar entry appears only while a download is runnable. |
| UPSTREAM-OK | Keep a playlist downloaded (new songs sync automatically) | shared/.../storage/DownloadedPlaylists.kt:41-90; Android SongDownloadManager.kt:171-176; desktop DownloadManager.kt:294,680-688 | Matches Spotify, and desktop gets it too. Paired with autoDownloadOnLike / autoDownloadSavedCollections (PlaybackSettingsRegistration.kt:327-345). |
| UPSTREAM-OK | Song cache and download location (size caps, Cached collection, custom path) | Settings.kt:583-586 songCacheEnabled/maxSongCacheSize/downloadLocation/customDownloadPath; UI in androidApp/.../settings/StorageSettingsScreen.kt drawn as a custom Shell page (ShellSettings.kt:596-611, SettingsScreen.kt:507-509) | songCacheEnabled defaults to false (Settings.kt:583), so nothing is cached until the listener turns it on. Cached songs are browsable and individually removable (AutoPlaylistScreen.kt:471-481,939-954). |
| UPSTREAM-OK | Offline artist, album and playlist pages built from stored songs | shared/.../db/OfflineMedia.kt:21-70 loadOfflineArtist/loadOfflinePlaylist, OfflineAlbum.kt:13; availability test at OfflineMedia.kt:18-19 | Shell draws these through the same playlist, album and artist slots, so offline browsing keeps Shell's look. |
| UPSTREAM-OK | Backup and restore (settings plus the library database) | androidApp/.../backup/BackupRestoreManager.kt:142-188; desktop DesktopBackupRestoreManager.kt:91-112; entries named in BackupFormat.kt:22-24; page reachable at ShellSettings.kt:608 | Three entries only: backup.json, settings.json, song.db. |
| MISSING | Backup of Turtle's own listening data (turtle.db) | No turtle reference in BackupRestoreManager.kt, DesktopBackupRestoreManager.kt or shared/.../backup/; the store lives at TurtleDatabase.android.kt:8 and TurtleDatabase.desktop.kt:9 | Android cloud backup and device transfer do carry databases/turtle.db, because backup_rules.xml excludes no database but exoplayer_internal.db. The in-app zip path has no equivalent; desktop has neither. |
| MISSING | Search inside the add-to-playlist sheet | ShellPlaylistPicker.kt:96 is a bare LazyColumn capped at heightIn(max = 420.dp); upstream's PlaylistChooserContent (PlaylistChooser.kt:134-237) has no search field either | Not an unreachable row: nothing in the repository implements it. |
| MISSING | Playlist folders | No playlistFolder/folderId in shared/, androidApp/, desktopApp/ or turtle/; the only hit is AutomotiveMediaLibrary.kt:685 youtubePlaylistFolders, a media-browse grouping | Spotify has folders on desktop only. |
| MISSING | Collaborative playlists | No collaborative-playlist code; every "collaborative" hit is the taste engine's collaborative filtering (turtle/engines/taste/.../Generators.kt:151) |  |
| MISSING | Pin a playlist to the top of the library | No pin preference in Settings.kt beyond Android Auto (Settings.kt:441-444); Shell passes its pinned flag only for auto playlists (ShellLibrary.kt:419) | Manual sort plus drag is the nearest equivalent and already exists. |
| MISSING | "Downloaded" library filter chip | LibraryFilter has no DOWNLOADED member (LibraryContent.kt:154-160); DOWNLOADED exists only as a sort, and only while the Tracks filter is active (ShellLibrary.kt:1337) | The Downloaded auto playlist covers songs but not downloaded albums or playlists. |
| MISSING | Download queue screen on desktop | desktopApp/.../storage/DownloadManager.kt:73 DownloadState and :242-243 downloadProgress exist, but no screen lists the queue; StorageSettings.kt references neither | Desktop downloads can only be watched per row inside a playlist page. |
| MISSING | Separate audio quality for downloads | SongDownloadManager.kt:453-454 resolves with playbackAudioQuality(); the only quality setting is Settings.kt:423 audioQuality, registered once at PlaybackSettingsRegistration.kt:74-81 | Spotify exposes download quality separately from streaming quality. |
| MISSING | Download only on Wi-Fi / data saver | No metered-network gate in the repository: the only NET_CAPABILITY_NOT_METERED hit is a Listen Together debug report (ListenTogetherDebugReport.kt:99) | Cellular state is observed for bandwidth hints (ContentHints.kt:62-71) but never gates a download. |
| MISSING | Local on-device audio files as a library source | No MediaStore.Audio or local scan; "Uploads" uploads a picked file to YouTube Music instead (AutoPlaylistScreen.kt:408-420,654), and SongEntity.isLocal marks a device-only playlist row (PlaylistMutations.kt:615) | Spotify has Local Files on desktop and phone. |

## Weaknesses

### [BLOCKER] In-app backup and restore silently loses every piece of Turtle's own data

- Evidence: BackupRestoreManager.kt:155-178 writes only backup.json, settings.json and song.db (BackupFormat.kt:22-24); desktop identical (DesktopBackupRestoreManager.kt:91-112). The hidden list persists in turtle.db (TurtleRuntime.kt:568-572)
- Why it matters: Back up, reinstall, restore: playlists and likes return, but the Not-interested list, every feedback event, the taste profile and the discovery dial are gone. Plays are rebuilt from upstream history (MetrolistHistoryImport.kt:27-44), so the loss is partial and silent. Desktop has no recovery at all.
- Fix sketch: Add a turtle.db snapshot as a fourth zip entry. Expose snapshot/restore on SqlitePlayEventStore, then one hook line each in androidApp/.../backup/BackupRestoreManager.kt:155-178 and desktopApp/.../backup/DesktopBackupRestoreManager.kt:91-112, plus a name in shared/.../backup/BackupFormat.kt. Two new hooks: owner approval.

### [MAJOR] Desktop's "Library grid columns" setting has no effect

- Evidence: Setting registered at AppearanceSettingsRegistration.kt:176-186 and drawn by Shell's settings page; Shell's library hardcodes GridCells.Adaptive(180.dp) for desktop at ShellLibrary.kt:373
- Why it matters: A visible control that changes nothing reads as a broken app. Upstream's own density menu (LibraryContent.kt:797-815) is gone too, so desktop listeners have no way at all to make the library grid denser or looser.
- Fix sketch: In ShellLibrary.kt:370-376 use GridCells.Fixed(Settings.shared.libraryGridColumns) on desktop instead of Adaptive(180.dp), and add the same Tune affordance to ShellLibSortRow (ShellLibrary.kt:699-763). No hook needed.

### [MAJOR] Library rows drop a playlist's song count whenever it has an author

- Evidence: ShellLibrary.kt:955-960 builds the meta line as pair(kind, author ?: songCount), so the count is used only when author is blank. Upstream joins both: LibraryContent.kt:962-967 listOfNotNull(count, author).joinToString(" • ")
- Why it matters: Almost every saved YouTube Music playlist has an author, so in practice the library never shows how many songs a playlist holds. Size is the main thing people scan a playlist list for, and Spotify always shows it.
- Fix sketch: In ShellLibrary.kt:950-964 build the PlaylistItem meta from the kind plus a joined count-and-author string, matching LibraryContent.kt:962-967, keeping pairFormat for the kind prefix. No hook needed.

### [MAJOR] Add-to-playlist sheet lists playlists oldest-first with no sort and no search

- Evidence: Shell receives editablePlaylistsByCreateDateAsc order (MobilePlaylistActions.kt:80, through selectablePlaylistDetails which preserves order, PlaylistSelection.kt:44-50); ShellPlaylistPicker.kt:96 caps the list at 420.dp with no filter
- Why it matters: Saving a song is one of the most frequent actions in a music app. Someone with 40 playlists scrolls a 420dp window ordered by the playlists they made first, which are the ones they use least. Upstream's own sheet defaults to library order (Settings.kt:745) and offers six sorts.
- Fix sketch: Carry sort state on PlaylistPickerSlot (TurtleUiSlots.kt:226-238) and draw a sort chip; or, with no hook, sort inside Shell from Settings.shared.playlistPickerSort and libraryManualOrder via sortedForPlaylistPicker (shared/.../model/PlaylistPickerSorting.kt:19). Add a search field above the list.

### [MAJOR] The add-to-playlist sheet never says which playlist failed

- Evidence: PlaylistPickerSlot carries failedIds (TurtleUiSlots.kt:231) but ShellPlaylistPicker.kt:117-133 reads only selectedIds, completedIds and contains; one generic line shows at :99. Upstream marks each failed row (PlaylistChooser.kt:255-257)
- Why it matters: Adding one song to three playlists where one fails leaves the listener unable to tell which add to retry. They either re-add to all three, creating duplicates, or assume it worked and lose the song.
- Fix sketch: In ShellPlaylistPicker.kt PlaylistChoiceRow, add a `p.id in slot.failedIds` branch above the `done` branch and tint it c.danger, mirroring PlaylistChooser.kt:255-257. No hook needed.

### [MAJOR] Add-to-playlist ticks are invisible to screen readers

- Evidence: ShellPlaylistPicker.kt:164-165 uses shellPressable(onClick, label = title), setting Role.Button and an explicit contentDescription; the Tick at :142-149 has no semantics and `selected` is never passed
- Why it matters: TalkBack announces only the playlist name, so a blind listener cannot tell which playlists are ticked, which already hold the song, or which add completed. The subtitle carrying all of that ("Already in this playlist", "Added") is swallowed by the row's contentDescription.
- Fix sketch: In ShellPlaylistPicker.kt PickerRow, pass role = Role.Checkbox and selected = selected to shellPressable, add Modifier.semantics { toggleableState = ... }, and build the label from title plus subtitle. No hook needed.

### [MAJOR] No Download control on the playlist or album header

- Evidence: ShellCollection.kt:1094-1118 ActionRow draws play, shuffle, smart shuffle, save, edit, add-song and more; neither PlaylistPageSlot nor AlbumPageSlot carries a downloader (TurtlePageSlots.kt:34-87, 90-126). Download lives only in the menu (MediaMenuModel.kt:559-604)
- Why it matters: Spotify puts a download arrow next to Play on every playlist and album, filled when it is offline. In Turtle you must open the "more" menu and read a list to learn whether a playlist is downloaded, burying the most-used offline affordance two taps deep.
- Fix sketch: Add downloadState/downloadProgress/onToggleDownload to PlaylistPageSlot and AlbumPageSlot (TurtlePageSlots.kt), fill them from PlaylistScreen.kt, AlbumScreen.kt and the desktop screens, then render a toggle in ShellCollection.kt:1094-1118. Two hook-line amendments; HOOKS.md #53 is the precedent.

### [MAJOR] Shell's Backup & restore page carries a networked Spotify importer Turtle's rules forbid

- Evidence: Reachable at ShellSettings.kt:608 via SettingsPageId.BACKUP_RESTORE (SettingsScreen.kt:511-513). SpotifyImportManager.kt:39 requires an sp_dc cookie, :161 checks hasLoginCookie; status.md:67 records that opening the page restores a Spotify session
- Why it matters: turtle/AGENTS.md's rule is that imports read only files the listener downloaded themselves. Here, opening a settings page can make authenticated calls to Spotify with a stored browser cookie. That contradicts Turtle's own privacy promise, which is its main claim over Spotify.
- Fix sketch: Owner decision, not a unilateral code fix. Options are recorded in status.md:67-70: gate the Spotify provider behind an explicit file-only path in shared/.../spotifyimport/SpotifyImportManager.kt, or stop it restoring a session on page open. Do not drop Backup & restore from Shell's list, which would also hide backup and restore.

### [MAJOR] Desktop's Now Playing panel cannot download the current song

- Evidence: ShellDesktop.kt:580-581 passes download = null and isDownloaded = { false } into ShellPlayerServices, so the row is skipped at ShellPlayerSheets.kt:436-438. Desktop does have one: ItemContextMenu.kt:267-271 calls DownloadManager.download
- Why it matters: Desktop listeners get a More panel that silently omits download, and the downloaded state always reads false there even for songs already on disk. The only way to download on desktop is to right-click a row in a list, which the Now Playing panel is not.
- Fix sketch: Add download/isDownloaded to DesktopPlayerExtras (TurtleUiSlots.kt:207-220), set them from desktop Main.kt's hook #30 line using DownloadManager.download and LibraryState's download state, then wire them in ShellDesktop.kt:580-581. One hook-line amendment.

### [MINOR] The phone player sheet can download but never remove a download

- Evidence: ShellPlayerSheets.kt:436-438 renders one row with enabled = !done; ShellPlayer.kt:899 wires download only, and PlayerPlatform.kt:131-134 exposes just downloadedSongIds and rememberSongDownloader. Upstream's menu toggles: MobilePlayerPlatform.kt:277
- Why it matters: Once a song is downloaded the row goes dead, with no way to free the space from the player. Upstream's menu offered remove in the same slot, so this is a regression for anyone managing storage on a small phone.
- Fix sketch: Add removeDownloads to PlayerPlatform (shared/.../ui/player/PlayerPlatform.kt:131-134), implement it in AndroidPlayerPlatform, then make ShellPlayerSheets.kt:436-438 a toggle. One hook-line amendment to an upstream interface.

### [MINOR] Android's Library shows two titles: upstream's "Library" and Shell's "Your Library"

- Evidence: Screen.Library is a top-level screen so shouldShowTopBar is true (MetrolistApp.kt:812-814) and the bar titles it Res.string.filter_library (:1490-1492); Shell draws its own headline at ShellLibrary.kt:602
- Why it matters: The page opens with the same word twice in two different type styles, the first thing a listener sees on the tab. It also spends about 56dp of phone height that Spotify gives to content.
- Fix sketch: Either drop Shell's headline when a top bar is present, or gate the top-bar title on TurtleUiSlots.libraryPage == null the way HOOKS.md #39 gates Home's floating buttons. The second is one hook line in MetrolistApp.kt:1482-1503.

### [MINOR] Desktop sidebar rows have no context menu

- Evidence: ShellDesktop.kt:514-515 LibraryRowView uses shellPressable(row.onClick, label = row.title) only, with no onSecondaryClick. Upstream's desktop library rows carry ItemContextMenu (desktopApp/.../screens/LibraryScreen.kt:360-373)
- Why it matters: The sidebar is where desktop listeners spend most of their time, and right-clicking a playlist there does nothing. Download, remove from library, rename and play-next all need the full Library page first. Spotify's sidebar has a full context menu.
- Fix sketch: Add an opener to DesktopLibrarySlot (TurtleUiSlots.kt:198-201) that shows upstream's ItemContextMenu for a YTItem, set it from desktop Main.kt's hook #30 line, and attach Modifier.onSecondaryClick at ShellDesktop.kt:514. One hook-line amendment.

### [MINOR] Desktop sidebar lacks a Podcasts filter and any sort control

- Evidence: ShellDesktop.kt:181 enum LibraryFilter { ALL, PLAYLISTS, ARTISTS, ALBUMS }; chips drawn at :474-481; podcasts folded into PLAYLISTS at :377
- Why it matters: Saved shows are mixed into the Playlists chip with no way to isolate them, and the sidebar order cannot be changed at all while the full Library page offers ten sorts. Spotify's sidebar offers Recents, Recently added, Alphabetical and Creator.
- Fix sketch: Add PODCASTS to ShellDesktop.kt:181 and a chip at :474-481, honouring Settings.shared.hidePodcasts as ShellLibrary.kt:631 does; add a small sort menu reading Settings.shared.librarySorting(LibraryFilterType.ALL). No hook needed.

### [MINOR] No "recently played" sort anywhere in the library

- Evidence: ShellLibrary.kt:1366 sorts LAST_UPDATED by YTItem.libraryUpdatedAt, which is the entity's lastUpdateTime (LibraryViewModel.kt:704, PlaylistSelection.kt:37) — a content-change time. No sort reads a last-played timestamp
- Why it matters: "The playlist I had on yesterday" is how most people find things, and Spotify defaults its library to Recents. Turtle already has the data: its own event store records every play with an origin, so the gap is presentation only.
- Fix sketch: Expose a last-played-per-collection lookup from turtle/bridge (SqlitePlayEventStore and HistoryLens already hold play origins), then add a RECENTLY_PLAYED case to shellLibSort (ShellLibrary.kt:1352-1389) and to the sort sheet's option list (:728). Turtle-only, no upstream hook.

### [MINOR] Library list rows show no explicit marker while playlist rows do

- Evidence: ShellLibrary.kt:867-884 ShellLibMetaLine draws only pinned, downloaded and liked glyphs; ShellCollection.kt:120-121,1308-1309 draws an explicit badge on track rows
- Why it matters: Under the Tracks filter the library is a song list, so the same song is marked explicit on a playlist page but not here. Anyone filtering explicit content gets inconsistent signals between two screens one tap apart.
- Fix sketch: Add the explicit badge to ShellLibMetaLine (ShellLibrary.kt:867-884) for SongItem, reusing the glyph and string already used at ShellCollection.kt:1308. No hook needed. Already listed as a known issue in turtle/docs/plans/2026-11-23-status.md:109.

### [MINOR] The whole song table is re-mapped on every library change

- Evidence: LibrarySync.kt:56-58 collects dao().allSongs() ("SELECT * FROM song", SongDao.kt:329-331) debounced 2s and maps every row to a SongItem and a TrackMeta, then rebuilds byKey, byArtist and the cache (:59-83)
- Why it matters: Every play updates totalPlayTime, so the table changes constantly. On a library of tens of thousands of songs this re-allocates the whole set every two seconds of activity, competing with playback and UI. The index upsert is already diffed; the mapping is not.
- Fix sketch: Diff rows before mapping in LibrarySync.kt:56-58: keep the previous row snapshot and map only changed ids, or narrow the query to the columns the sync needs. Turtle-only file, no hook.

### [MINOR] SongCache keeps the whole library plus 20,000 items resident

- Evidence: SongCache.kt:13 capacity 20_000 for the LRU part; replaceLibrary (:30-33) clears and refills an unbounded map with every library song, called from LibrarySync.kt:59 on every table change
- Why it matters: On a phone, a large library plus the shelf and LRU maps is a lasting heap cost that grows with the listener's library and is rebuilt on every sync. On a low-memory device that raises the chance the OS kills the app mid-session.
- Fix sketch: Bound the library map, or store only the fields the engine needs (TrackMeta plus a thumbnail) rather than whole SongItems, in turtle/bridge/.../SongCache.kt:13-33, and have LibrarySync apply a diff instead of replaceLibrary.

### [MINOR] Custom collection and playlist covers are backed up as paths, not images

- Evidence: Settings.kt:756-767 stores collectionCovers as a type-to-file-path map; the backup writes only PortableSettings (BackupRestoreManager.kt:169-175) and never the files, which Android writes under filesDir/playlist_covers (PlaylistScreen.kt:270-272)
- Why it matters: Restoring on a new device brings back a setting pointing at files that do not exist, so every custom cover silently becomes a placeholder. There is no error and no way to tell which covers were lost.
- Fix sketch: Either add the playlist_covers directory to the archive (BackupRestoreManager.kt:155-178 and the desktop equivalent) or drop cover paths from the exported settings so the restored state is honest. Upstream file; worth reporting to Metrolist.

### [MINOR] Android never shows the download-by-playlist filter it builds

- Evidence: AutoPlaylistScreen.kt:317-318 creates the filter and feeds the prepare input (:361,368-374), and :739 renders an empty state for a chosen playlist, but DownloadPlaylistFilterBar is rendered only at StoredPlaylistContent.kt:331 and MobileLibrary.kt:903, neither of which Android uses
- Why it matters: The Downloaded collection on a phone can hold thousands of songs with no way to narrow it to one playlist, while desktop can. The filter's empty-state string is even reachable, so the code half-expects a control that does not exist.
- Fix sketch: Pre-existing upstream gap, not caused by Shell. Pass { DownloadPlaylistFilterBar(downloadFilter, database) } as aboveOrderControls when currentType == "downloaded" in AutoPlaylistScreen.kt:828-839, beside the existing DownloadQueuePanel. Shell already renders aboveOrderControls (ShellCollection.kt:1165).

### [MINOR] Library pages wear an upstream Material FAB over Shell's layout

- Evidence: androidApp/.../screens/LibraryScreen.kt:778-785 and desktopApp/.../screens/LibraryScreen.kt:389-393 draw ScrollingAddButton outside LibraryContent, so Shell never touches it; contrast HOOKS.md #39, which removes Home's floating buttons when Shell is installed
- Why it matters: The one place a listener creates a playlist is the one control on the page that is not Shell's. It takes Shell's palette through hook #29 but keeps upstream's shape and motion, and floats over Shell's own bottom padding. Spotify puts Create in the library header.
- Fix sketch: Move create and import into ShellLibTop (ShellLibrary.kt:589-626) as Shell controls driven by new LibraryPageSlot callbacks, and gate the upstream FAB on TurtleUiSlots.libraryPage == null as HOOKS.md #39 does. One hook line per platform.

## Strengths

- **Playlist import from five streaming services and arbitrary files** — shared/.../playlistimport/ holds SpotifyUrlPlaylistImporter, AppleMusicPlaylistImporter, AmazonMusicPlaylistImporter, DeezerPlaylistImporter, TidalPlaylistImporter, CsvPlaylist and UniversalPlaylistParser; matching at PlaylistImportManager.kt:675-730 | beats Spotify: Spotify offers no playlist import at all. Turtle also matches tracks by normalised title and co-artist overlap (PlaylistImportManager.kt:680-699), so imports survive YouTube's different feat. credits.
- **Liked Songs can be reordered by hand** — AutoPlaylistScreen.kt:808-816 calls SongLikeState.moveLike; the drag is Shell's (ShellCollection.kt:319-329, 484-487) and commits through upstream's onReorder | beats Spotify: Spotify's Liked Songs is sort-only and cannot be hand-ordered. Turtle also hand-orders the whole library (ShellLibrary.kt:1048-1198) with a move menu for screen readers.
- **The cache is a browsable collection, not a hidden blob** — Cached auto playlist built at AutoPlaylistScreen.kt:471-481, sortable by size, last used and most played (ScreenContentText.kt:256-264), with per-song remove (:939-954) and clear-all (:985-1010) | beats Spotify: Spotify exposes cache only as a "clear cache" button with a size. Turtle shows which songs are on the device, how large each is and when it was last used, and lets a single one be removed.
- **Keep-playlist-downloaded sync on both phone and desktop** — shared/.../storage/DownloadedPlaylists.kt:41-90 tracks ids and observes membership; wired at SongDownloadManager.kt:171-176 (Android) and DownloadManager.kt:294,680-688 (desktop) | beats Spotify: Matches Spotify's behaviour and desktop gets it too; Spotify's desktop client downloads only inside its own offline mode.
- **Offline pages are built from what is actually stored** — shared/.../db/OfflineMedia.kt:21-70 and OfflineAlbum.kt:13 assemble artist, album and playlist pages from stored songs, gated by Song.isAvailableOffline (OfflineMedia.kt:18-19) | beats Spotify: Turtle drops to real local content instead of an error page, and Shell draws those pages through the same slots, so offline does not look like a degraded mode.
- **Downloads are honest about failure** — DownloadsScreen.kt:78-113 lists the queue with pause-all, resume-all and cancel-all; typed causes at :803-814 (STORAGE_FULL, STORAGE_ACCESS, NETWORK, INCOMPLETE) with per-row retry | beats Spotify: Spotify shows a spinner and a generic retry. Turtle names the cause, so "the disk is full" and "you are offline" are not the same message.
- **Saving to a playlist teaches taste, and imports do not** — turtle/bridge/.../PlaylistAdds.kt:42-112: the first snapshot is only a baseline, a pair seen once never counts again, a burst over 50 pairs is treated as an import and dropped, and bulk saves of a Turtle mix count once (expectBulk, :64-71) | beats Spotify: Explicit saves are recorded even while listening history is paused (PlaylistAdds.kt:117-119), and the signal is computed on device. Nothing leaves the phone, and a one-off import cannot distort recommendations.
- **Library search is ranked and typo-tolerant, not SQL LIKE** — Bridge hook #4 sets libraryContentSearchOverride in shared/.../viewmodels/LibraryViewModel.kt; the engine is turtle/engines/search (LexicalIndex.kt, BitParallelMatcher.kt), fed by LibrarySync.kt:56-67 and failing open to upstream | beats Spotify: Searching the library, including songs inside playlists (ShellLibrary.kt:521-578), runs entirely on device with no network round trip and tolerates misspellings Spotify's library filter does not.
- **Songs show their own download and like state in the library list** — ShellLibrary.kt:489-496 sets downloaded for SongItem, PlaylistItem and AlbumItem and liked from SongLikeState, each with a contentDescription (:871-881). Upstream marks only playlists and albums (LibraryContent.kt:981,1004,1032) | beats Spotify: Shell improves on what it replaced rather than only preserving it, and the markers carry screen-reader text.
- **A downloaded copy overrides a stale unavailable flag** — ShellLibrary.kt:862-864: SongItem.unplayable is isUnavailable && downloadedAt == null, so a downloaded song stays tappable even when YouTube now reports it regionally unavailable | beats Spotify: Spotify greys out and removes tracks that leave its catalogue even when they sit on the device. Turtle keeps playing what the listener already downloaded.

## Doc claims that are stale at HEAD

- Doc says: spotify-comparison.md:82 — "Import banner | Import exists in Settings (playlists) and as history importers"
  - Reality: Import is reachable from the Library page itself on both platforms: Android LibraryScreen.kt:801-808 and desktop LibraryScreen.kt:409-416. The row understates reach. The dismissible banner it proposes is still missing.
- Doc says: status.md:67 — "nothing in Turtle calls" the Spotify playlist importer, left unadvertised pending an owner decision
  - Reality: True for direct calls, but it is two taps from the desktop Library FAB: Import sets openPlaylistImportRequested and opens Settings (LibraryScreen.kt:412-416), the same page hosting the cookie importer (SpotifyImportManager.kt:39,161).
- Doc says: status.md:57 — "Not yet checked: Save through the Shell picker"
  - Reality: Still unchecked, and this review found three defects in that path: no sort or search (ShellPlaylistPicker.kt:96, MobilePlaylistActions.kt:80), failedIds ignored (:117-133), ticks with no screen-reader state (:164-165).
- Doc says: status.md:109 — "Library rows lack the explicit tag that collection rows show", filed under "Smaller issues"
  - Reality: Confirmed (ShellLibrary.kt:867-884 vs ShellCollection.kt:1308-1309), and not purely cosmetic: under the Tracks filter the library is a song list, so the same song is marked on one screen and not the next.
- Doc says: spotify-comparison.md:83 — "Library filters | The sidebar lacks a Podcasts chip"
  - Reality: Still accurate. ShellDesktop.kt:181 defines LibraryFilter { ALL, PLAYLISTS, ARTISTS, ALBUMS } and the chips at :474-481 offer three. Recorded as re-verified, not as a change.
- Doc says: spotify-comparison.md:84 — "Recents sort | Not available ... Needs a last-played time per collection (M)"
  - Reality: Still accurate: LAST_UPDATED sorts by libraryUpdatedAt, the entity's lastUpdateTime (LibraryViewModel.kt:704). Re-verified, not a change — but Turtle's own event store already holds the play times it needs.

## Open questions

- Shell loads only on Android and desktop (TurtleUi.kt:66-68 via App.kt:249 and Main.kt:988); iOS runs MobileApp/MobileLibrary, though MobileLibrary.kt:276 does call LibraryContent. Is iOS in scope for Shell, or upstream-only by choice?
- Does the owner want turtle.db in the in-app backup at all? It holds the full listening history, so an exported zip becomes far more sensitive than today's archive. BackupRestoreScreen.kt:787-799 already warns about sensitive data.
- songCacheEnabled defaults to false (Settings.kt:583) and TurtleDefaults overrides only stopMusicOnTaskClear (TurtleDefaults.kt:18-22). Should Turtle default song caching on, as Spotify effectively does?
- Downloads reuse the playback audio-quality setting (SongDownloadManager.kt:453). Is a separate download-quality choice wanted, or is one quality setting the deliberate simpler model?
- The Library top-bar Downloads entry shows only while a download is runnable (MetrolistApp.kt:1540-1552). Should the queue have a permanent entry point so a paused or failed queue stays findable?
- Android cloud backup carries databases/turtle.db implicitly: backup_rules.xml excludes only exoplayer_internal.db. Intended, given the rules deliberately exclude the settings datastore?
