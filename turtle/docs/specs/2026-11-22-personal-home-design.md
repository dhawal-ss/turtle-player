# Personal Home and recommendations: design

Status: draft for product-owner approval, 22 Nov 2026.

**23 November implementation update.** W1, W2, W3 and W9 are present at checkout `3b1f09b1`. The [music discovery handoff](../plans/2026-11-23-music-discovery.md) updates the next steps for country charts, phone Explore, session quality and evaluation. The description of Home below records the pre-Wave 1 baseline.

## Why

On opening Turtle, the product owner sees songs they never listen to, such as Indian regional
music they don't follow. The app reads as a skin of YouTube Music. The goal is a Home, search and
library that learn from what they play, like Spotify and Apple Music do. They should also show what
people in their country are playing, and play the music, audiobooks and podcasts already on their
phone.

### What Home does today

These facts were mapped from the code on 22 Nov 2026 and checked on the phone (`gl=US`, en-US
locale).

- Home is about 95% YouTube's own feed, unranked. Turtle adds one "Made for you" row and the
  Infinity card. See `ShellHome.kt` `HomeLists.of`.
- After YouTube's Home continuation runs out, upstream appends whole feeds of a **random** YouTube
  chip as you scroll (`HomeChipFeed.kt:64`, `HomeViewModel.kt:802-815`). The rows are unlabeled in
  Shell, and this repeats for every chip. It is the most likely source of the unrelated regional
  music, and it happens with any country setting.
- Signed in, YouTube's own rows follow the whole Google account's history, including the main
  YouTube app. YouTube's shelves fill 6 of the 8 grid tiles through Speed Dial's round-robin.
- Turtle's mixes rebuild on every cold start, because the `stable` map in `TasteEngine` lives only
  in memory. Android kills the process often.
- Turtle ignores upstream's "Pause listen history". Audiobooks and episodes that have no
  `podcastId` are learned as music.

## Decisions the product owner made (22 Nov 2026)

1. **YouTube's rows on Home**:
   - Filter them to the 2–3 rows that best match the listener's taste, placed below Turtle's rows.
   - Move the random chip feeds to Explore.
   - A setting can show all of YouTube's rows again. Nothing is deleted.
2. **Where the listener is**:
   - Country only, with no location permission. Content country comes first; the phone's
     network/SIM country is a hint.
   - City and state are out of scope. YouTube offers no city charts, and a licensed source (such as
     Apple's city charts through an official MusicKit token) would be a separate decision.
3. **Local files**: the listener picks folders with Android's folder picker. Turtle asks for no
   storage permission and sees only those folders.
4. **Recognizing files**:
   - Read tags and filenames on the device.
   - Optionally (opt-in), match the title and artist on YouTube Music for artwork and to link the
     file to taste. Only that text leaves the phone.
   - No audio fingerprinting.

## Goals

- Opening Home shows the listener's own music first. Turtle's personal rows lead. Every YouTube row
  shown has earned its place by matching their taste. No unlabeled random feeds.
- Turtle's mixes are the day's mixes, and Discovery is the week's, across restarts, like Spotify's
  Daily Mixes and Discover Weekly.
- Turtle learns only from music the listener chose to have learned:
  - paused history records nothing;
  - audiobooks and podcasts never shape music taste.
- Every play records the surface it came from, and one "not interested" list covers all of Turtle.
  The ranker can then be trained later (Wave 4).

## Non-goals (this design)

- City or state trends, location permission.
- A learned ranker or new embeddings (Wave 4, after attribution data exists).
- Removing upstream features. YouTube's rows stay reachable through a setting and Explore.

## Anti-goals (a failure even if it "works")

- A Home that goes blank or near-blank for a new listener. With too little history, YouTube's top
  rows still show until onboarding (Wave 2) exists.
- Home rows that jump around after first paint.
- Any listening data leaving the device without an opt-in.
- Hardcoded English or country lists. The country and language always come from settings or the
  system.

## Approaches considered for Home

1. **Filter and rank YouTube's rows by taste, Turtle first.** Chosen by the product owner.
   - Turtle's rows lead.
   - Each YouTube row is scored by how well its items match the listener's taste, and at most 3
     rows that pass are shown.
   - YouTube's rows are still the discovery input. A new listener still gets a full Home.
2. **Turtle-only Home, YouTube's rows on Explore.**
   - The cleanest Spotify feel.
   - Thin for new or light listeners until onboarding and charts exist, and loses YouTube's
     personal rows for signed-in users.
3. **Keep YouTube's rows; only add Turtle rows above.** Smallest change, but leaves the random feeds
   and the "skin of YouTube" feel.

## Program (one PR per workstream, in order)

| Wave | Workstream | Size | Upstream hook lines |
| --- | --- | --- | --- |
| 1 | W1 Personal Home | M | 0 |
| 1 | W2 Shelves that survive restarts | S–M | 0 |
| 1 | W3 Learn only from chosen music | S | 0 |
| 1 | W9 Play attribution and one "not interested" list | M | 0 |
| 2 | W4 First-run taste onboarding (country, artists, history import) | M | 0 |
| 2 | W5 Personal search (wire the built suggester, taste-based affinity, filter tabs) | M | 3 |
| 2 | W7 "Top in your country", ranked by taste, on Home and Explore | M | 0 |
| 2 | W8 Local files: folder picker, tags, music / audiobook / podcast, opt-in online match | L (3 PRs) | 1 |
| 3 | W6 Personal browse and a phone Explore page; W11 mix pages and covers; W12 a real offline eval | M each | 0 |
| 4 | W10 Learned ranker and better track vectors; W13 spoken word; W14 stats and Wrapped; W15 time-of-day shelves | M–L | 0 |

The hook budget has 5 lines left (69 used; the budget is under 75, so at most 74). Waves 2–4 plan to use 4 of them, so any hook
must earn its line. Wave 2 onward gets its own design pass before building. This document details
Wave 1.

## Wave 1 design

### W1 Personal Home

All changes are in `turtle/` (ShellHome, the bridge and the taste engine). They need no hook,
because hook #22 already hands Home to Shell on every platform.

**Order, top to bottom**:
1. Filter chips.
2. The 2×4 grid.
3. "Made for you".
4. The Infinity card.
5. Jump back in.
6. "More like X".
7. At most 3 YouTube rows that match the listener's taste.

The "Top in your country" slot is reserved for W7. The Infinity card moves below "Made for you",
which today it sits above, so the mixes are reachable without scrolling on a phone. Infinity
also stays the first grid tile. *(My call.)*

**Made for you.** One row of mix cards, as on Spotify: the Daily or artist mixes, Weekly Discovery,
Fresh for you (Release Radar), On repeat and Rediscover.
- It moves directly under the grid.
- The Music chip shows it too. Today `ShellHome.kt:383` hides it there.
- Tapping a card plays it, as today. Mix pages come in W11.

**Jump back in.** Built from Turtle's own on-device event log: the listener's most recent distinct
finished tracks, newest first. Today, when the listener is signed in, upstream fills this row from
YouTube's account-wide Listen Again (`SpeedDialPolicy.kt:41`, `HomeShelfBuilder.kt:113-117`), which
mixes in the main YouTube app's history. Upstream's row is no longer shown; under the "All" setting
it shows as one of YouTube's rows. *(My call.)*

**Grid** (Liked Songs, Infinity and 6 tiles):
- Speed Dial items the listener pinned keep their tiles.
- Jump back in's most recent tracks fill the remaining tiles.
- When those run short, YouTube round-robin items fill tiles if they pass the taste filter. A
  cold-start listener gets them unfiltered.
- Mixes are not grid tiles; they stay in "Made for you". *(My call.)*
- Grid items are not repeated in Jump back in or the YouTube rows.

**YouTube rows.**
- Candidates are upstream's non-Turtle rows, except:
  - Speed Dial;
  - upstream's Listen Again / keep-listening row;
  - chip feeds (key prefix `chip_`; `feedChip` is not reliable, since `HomeShelfBuilder.kt:384-387`
    sets it on every row while a chip is selected);
  - charts and Explore-origin rows (W7 and Explore);
  - podcasts and moods rows.
- The engine gains a pure scoring API:
  - the bridge passes a row's items as tracks, artists, or collections with their artists;
  - the engine returns a typed row score: mean known affinity, the share of items by known artists,
    and the share by disliked or muted artists.
- Playlist carousels carry no artists (`PlaylistItem` has only author and subtitle), and parsing
  subtitles would depend on the locale. The rule:
  - a playlist-only row is kept only when upstream marks it personal (`isPersonal`, such as "Your
    mixes"), and takes at most one of the 3 slots;
  - other playlist-only rows are dropped under the default setting.
  - *(My call. Fetching each playlist's contents to score it costs a network call per row.)*
- The bridge scores the rows off the UI thread whenever upstream's set of row keys changes, and
  publishes, in Compose state, the ordered keys to keep.
- Rows not yet scored don't show. They sit below Turtle's rows, so they are off screen at first
  paint and nothing jumps above the fold.
- Kept rows must have a score at or above the threshold and no more than a set share of disliked
  artists. They are capped at 3 and ordered by score.
- With too little history, below the engine's existing cold-start bar, YouTube's first 3 eligible
  rows show unscored. Home never goes blank.

**Random chip feeds.** Under the default and "Off" settings, Home stops requesting chip feeds.
- Upstream's main Home continuation still pages in, because each page brings more candidate rows to
  score. Once it runs out, Shell stops calling load-more.
- Upstream loads chip feeds only through Home's load-more, and phones have no Explore page until W6.
  So until W6, the "All" setting is how a listener gets them back on Home. *(My call, so that
  nothing is deleted.)*
- Desktop Explore keeps its charts, new releases and moods.

**Setting.** "YouTube Music suggestions on Home", on Turtle's settings page:
- **Matched to your taste** (default).
- **All**: exactly today's Home rows, chip feeds included.
- **Off**.

The labels are new strings in Turtle's own resources; there are no English literals in code.

### W2 Shelves that survive restarts

- The engine's kept shelves (the `stable` map) become part of the saved taste state.
- Each item is saved whole:
  - track meta, including the artist key, so mutes still apply to a restored shelf
    (`TasteEngine.kt:401-402`);
  - source and slot, so plays keep their attribution after a restart (`Queue.kt:34`);
  - reason.
- Each shelf also saves its id, kind, reason and period.
- Everything is encoded with the existing hand-written `TasteState` encoder and versioned, so old
  state still loads.
- Turning a track key back into a playable song needs its upstream `SongItem`. Today `SongCache`
  keeps those only in memory, and never-heard Weekly Discovery tracks aren't stored anywhere else.
  The bridge persists the `SongItem`s of shelf items in its own SQLite database, so that Home shows
  Turtle's rows before any network call after a restart.
- Upstream's own refresh, Android's pull-to-refresh at `androidApp/.../HomeScreen.kt:359`, reloads
  YouTube's feed. That rescores YouTube's rows, but Turtle's day's mixes and week's discovery stay,
  as on Spotify. `TasteEngine.refreshShelves`, which clears them and has no caller, stays unused.
- Fix while here: the comment in `Surfaces.kt` says Fresh for you refreshes on Fridays, but its
  cadence is daily. Make them agree (daily).

### W3 Learn only from chosen music

- While upstream's "Pause listen history" setting is on:
  - Turtle records no play events;
  - no co-listening or session learning happens;
  - no exploration or Infinity tuning updates;
  - Up Next ranking may still use the existing profile.
  - Explicit actions still apply (likes, "not interested", mutes), because the listener chose them.
    *(My call.)*
- Spoken word is excluded from music taste: Turtle audiobooks, podcast episodes (by `podcastId` or
  episode video type) and, after W8, local files classified as books or podcasts. Their progress and
  resume keep working; they are only kept out of the taste tracker.

### W9 Play attribution and one "not interested" list

- Every play started from Shell Home records the surface it came from:
  - Turtle mixes and rows, with the shelf id;
  - the grid, Jump back in, and each YouTube row, with the row key, so Wave 4 can learn which
    YouTube rows the listener actually plays;
  - Infinity, Smart Shuffle, radio and search.
- Where the surface is recorded:
  - the existing `PlayEvent.surface` column, which already has SHELF, SEARCH and RADIO, stops being
    hardcoded to AUTOPLAY/QUEUE (`PlayerObserver.kt:191`);
  - the detail goes in `surfaceDetail` in a format `SurfaceTag.parse` accepts;
  - existing tags keep parsing;
  - Infinity's `infinity/…` tag, rejected today, is fixed.
- Adding any song to a playlist, playing or not, records a new non-play feedback event, a strong
  positive signal:
  - the events engine gains this event type, with a store schema migration;
  - the bridge detects adds by watching upstream's playlist membership, which covers phone and
    desktop without a hook. *(My call.)*
- One "not interested" list:
  - Turtle reads upstream's local Block list live (`BlockedContent.kt`), not as a copy, so
    unblocking in upstream unmutes in Turtle too. Upstream's dislikes are read the same way.
  - Turtle never writes to YouTube's dislikes: that sends the vote to YouTube.
- "Not interested":
  - It is added to the item menu Shell already draws for Turtle rows, mix cards and grid tiles. It
    never replaces the menu.
  - It drops the item, and the engine records less-like-this for it.
  - A "Hidden songs and artists" page in Turtle's settings lists them and can undo them.
- Fix: every time Infinity refills its "for you" pool it calls `startAutoplay(continuous = false)`
  with no track (`InfinityMix.kt:353`). That resets the shared session and queue even while music
  plays (`TasteEngine.kt:204`, `:571-575`). Infinity must get its candidates without resetting the
  live session.

## Testing and verification

- Engine:
  - unit tests for row scoring, cold-start fallback, the stable-shelf round trip (encode, then
    decode, then the same shelves on the same day, rebuilt the next day) and the paused-history
    rule;
  - the offline replay must not regress by more than 2% (turtle/AGENTS.md).
- Bridge: a desktop test that persisted shelf songs come back after a new runtime starts.
- Device:
  - on the OnePlus, signed in and signed out: Home shows Turtle's rows first, at most 3 YouTube
    rows, and no chip feeds;
  - after a process kill, the same mixes show;
  - with history paused, the event log doesn't grow.
- Each PR is built with `./gradlew -p turtle/engines check` and the Android and desktop app builds.

## Left to the builder

- The score threshold and disliked-share cutoff, tuned on the offline replay and the device.
- How Shell learns that upstream's main continuation is exhausted, read from `HomeSlot` and
  upstream state, without a new hook.
- How the setting is stored. Turtle's existing settings mechanism is preferred.
- The bridge table layout for persisted shelf songs.
