# How Spotify recommends, and where Turtle stands

**23 November 2026**

The owner shared 14 screenshots of Spotify's desktop app: Home, Browse, the Music hub, the Hip-Hop hub, Charts, a chart page and "Hot Hits USA". This note sets out how Spotify organizes its recommendations, checks each element against Turtle's code, and draws the conclusions for Turtle's plan, the country-chart decision in particular.

The mapping was done by reading Turtle's code at `3f1f557e`. A second reader then checked every mapping against the cited lines; two of 46 were corrected. Statuses mean:

| Status | Meaning |
| --- | --- |
| HAS | Turtle has an equivalent |
| PARTIAL | Turtle has something close, but a named piece is missing |
| MISSING | Turtle has nothing like it |
| N/A | Needs Spotify's editors or catalog |

## How Spotify organizes recommendations

Spotify does not lean on one recommender. It stacks several kinds of shelf, and each one says why it is there in a short subtitle.

1. **Made for you, the algorithmic core.**
   - Discover Weekly, refreshed weekly and made of never-heard songs.
   - Daily Mix 01–03: each is a cluster of about three of the listener's artists, numbered and colour-coded.
   - Release Radar, new songs from the listener's artists.
2. **Personal mixes by artist, genre or mood** ("Your top mixes"): "Gunna Mix", "Chill Mix", "Upbeat Mix". Each is subtitled with the artists inside.
3. **Stations**: "Non-stop music based on your favorite songs and artists".
4. **Context shelves.** "Based on your recent listening" ("Inspired by your recent activity"), "Throwback", "Popular albums and singles", and a row of the listener's artists.
5. **One spotlight hero card per view**, such as "Made for you: Mac Miller Mix". The Podcasts and Audiobooks chips lead with a new episode from a followed show or one audiobook pick.
6. **Uniquely yours**: daylist (time of day), On Repeat, Repeat Rewind, Summer Rewind.
7. **Hubs for exploring.**
   - Browse tiles lead to a Music hub (Discover new music, editors' playlists, Uniquely yours).
   - They also lead to genre hubs. The Hip-Hop hub has popular playlists, classics, "Subgenres and Moods" and regional scenes ("Coast to Coast").
8. **Charts, kept apart from Home.** "Popular here" works in two separate ways:
   - **On Home and in the hubs, an editorial playlist.** "Hot Hits USA" is described as the hottest tracks in the United States. It is a playlist: its row numbers are only playlist positions, and it makes no claim about rank, week or movement.
   - **In a dedicated Charts area, a ranked and dated issue.**
     - Featured Charts (Top Songs Global and USA weekly, Top 50 daily), then one weekly and one daily chart per country.
     - A chart page carries the update date ("Top Songs – USA … Nov 20, 2026"), "8 new entries", up and down arrows for movement, a dot for a new entry, and a Plays column.

## Where Turtle stands

### Home

| Spotify | Turtle | Status | What would close it (size) |
| --- | --- | --- | --- |
| Discover Weekly | "Weekly discovery" in Made for you: never-heard songs, Monday to Sunday | HAS | Optional fixed tagline instead of the artist list (S) |
| Daily Mix 01–03 | Daily mixes per taste cluster, or up to three artist mixes, with tinted covers and "X, Y, Z and more" | HAS | Cluster mixes are titled "<Artist> Mix", not "Daily Mix N", and covers have no number. The string already exists (S) |
| Your top mixes: artist mixes | "<Artist> Mix" exists only while the profile has no clusters, so artist mixes and Daily Mixes never coexist | PARTIAL | Build artist mixes alongside cluster mixes in their own row (M) |
| Your top mixes: genre and mood | Genres and moods feed only the one Infinity Mix | PARTIAL | Two to four mood or genre mix cards from the listener's Infinity picks, taste-ranked like `fillGenre` (M) |
| Recommended Stations | None on Home. Radio starts only from the player's Start radio | PARTIAL | A Stations row seeded from top artists and songs. Taps go through step 4b's seeded `rankRadio` (S–M) |
| Based on your recent listening | "More like X" rows of songs | PARTIAL | Let one or two taste-matched YouTube editorial or community playlists through (M) |
| Spotlight hero card | Only the Infinity card | PARTIAL | A daily spotlight of one mix, reusing the Infinity card layout (S–M) |
| Audiobooks hero | Continue listening and category chips, no pick | MISSING | A spoken-word preference kept apart from music taste, from book progress (M) |
| New-episode hero | A "New episodes" row exists | PARTIAL | Lead the Podcasts chip with the newest followed episode as a hero card. A Home card, not a notification (S) |
| Artists row | None of Turtle's own | PARTIAL | "Your top artists" row from the profile, without muted artists (S) |
| Throwback | "Rediscover" (loved, unheard for 60+ days) | PARTIAL | Promote forgotten favorites to a titled row, or era mixes once release years are mapped (M) |
| Popular albums and singles | Kept off Home on purpose in Matched mode (owner decision); on Explore | PARTIAL | One taste-matched album row below Turtle's rows, if wanted (S) |
| Chips All / Music / Following / Podcasts / Audiobooks | All, Music, Mixes, Podcasts, Audiobooks. All and Music show the same Home | PARTIAL | Give All one spoken-word card, or merge All and Music (S). Following: "Fresh for you" already covers followed artists. A Following view would add followed shows (M) |

### Browse, hubs and charts

| Spotify | Turtle | Status | What would close it (size) |
| --- | --- | --- | --- |
| Browse genre tiles | Search shows 8 fixed tiles that each run a text search | PARTIAL | Point each tile at its Infinity genre (S), or use YouTube Music's own Moods & genres items (M) |
| Category tiles (Podcasts, Audiobooks, Made for you, New releases) | Spread across Home chips, Home rows and desktop-only Explore | PARTIAL | Tiles that open those places. Needs the phone Explore entry, step 6 (S–M) |
| Explore all categories | Desktop Explore shows 3 rows of Moods & genres and no "See all" | PARTIAL | "See all" to YouTube Music's full Moods & genres page (S) |
| Music hub | Personal parts on Home, YouTube parts on desktop Explore | PARTIAL | Phone Explore, plus a decision on whether Explore also shows Made for you (M) |
| Release Radar | "Fresh for you" from followed and top artists, daily | HAS | Optional Friday cadence (S) |
| daylist | None. The ranker already has hour and weekday context | MISSING | A daypart shelf (W15) (M) |
| Summer and Repeat Rewind | "Rediscover" is close to Repeat Rewind; no seasonal recap | MISSING / PARTIAL | Past-window shelves from Turtle's own event log (S–M) |
| Genre hub | Desktop opens YouTube Music's own category page in upstream's generic screen; phones have no route | PARTIAL | A Shell-styled genre page with an "Infinity: <genre>" play action (M) |
| Subgenres and moods | Infinity offers 28 genres and 10 moods as stations, no subgenres | PARTIAL | Link genre surfaces to Infinity genres (S); subgenres from YouTube's category sections (M) |
| Charts tile | Desktop Explore has a "Trending now" list. It merges every chart section, deduplicates, takes 10 and prints 01–10 as if they were ranks | PARTIAL | An Explore "Charts" entry opening YouTube Music's own chart page, chart names as YouTube gives them (S) |
| "Hot Hits USA"-style popular playlist | Not present, and Trending now's numbers are Turtle's own positions, not source ranks | MISSING | Drop the numbers and relabel descriptively, e.g. "Popular on YouTube Music" (S). Ranks return only with verified snapshots |
| Editors' playlists, New Music Friday, classics, regional scenes, original videos, Live Events | Need Spotify's editors or catalog | N/A | Use YouTube Music's own substitutes: community playlists, New releases (add "See all"), category pages |

### Player, library and radio

| Spotify | Turtle | Status | What would close it (size) |
| --- | --- | --- | --- |
| Now playing panel | Desktop panel and phone player: art, like, Less like this, the discovery dial, next in queue | HAS | Turtle adds a "why this song" line and the dial |
| Lyrics preview in the panel | Lyrics only as a full surface | PARTIAL | A 3–4 line synced card near the playhead; the data is already there (S) |
| Related music videos | None. Upstream exposes no related-videos list | MISSING | Parse YouTube Music's Related tab (needs hook lines, M), or an artist Videos row (S) |
| Import banner | Import exists in Settings (playlists) and as history importers | PARTIAL | A dismissible Library banner opening Backup & import, plus a file-based history import entry (S) |
| Library filters | The sidebar lacks a Podcasts chip; the full Library page has it | PARTIAL | Add Podcasts to the sidebar (S) |
| Recents sort | Not available; "Jump back in" is the nearest | PARTIAL | Needs a last-played time per collection (M) |
| Song radio | Start radio; pages taste-ranked and seeded by the station (step 4b) | PARTIAL | Desktop's first station page is unranked: one new hook line (owner decision) |
| Artist radio | Upstream's artist radio, first page unranked and unseeded | PARTIAL | Rank its first page, artist-seeded (S–M) |
| Genre and mood stations | The Infinity Mix | PARTIAL | Spotify shows several mood cards; Turtle has one station tuned in a sheet (S–M) |
| Smart Shuffle | Has it: one recommendation after every three playlist songs, taste-ranked | HAS | Optional per-song "recommended" marker |

## What this means for the country-chart decision

Spotify separates the two promises the plan bundles together:

| Promise | Spotify's answer | What Turtle can do today |
| --- | --- | --- |
| "What's popular here" | An editorial **playlist** on Home and in the hubs (Hot Hits USA), with no rank, date or movement claims | **Yes.** YouTube's chart playlists for a market can be shown the same way: a named playlist ("Top music videos in India", as YouTube titles it) without rank numbers, opened and played like any playlist |
| "The chart, ranked, this week" | A separate **Charts** area: dated issues, movement, new entries, play counts | **Not yet.** The app reaches YouTube's weekly Top Music Videos, not Top Songs. The response carries no date, and choosing a market needs a request field InnerTubeX cannot send |

**Recommendation.** Follow Spotify's split. Ship the "popular here" half now and keep the ranked half for later.

1. **Now (S).**
   - Stop Explore's "Trending now" from printing its own positions as ranks, and give it a descriptive title.
   - Add a "Charts" entry that opens YouTube Music's own chart page. YouTube's chart playlists then open as playlists, titled as YouTube titles them.
   - This is the "Hot Hits" pattern. It needs no new data and makes no chart claims.
2. **When InnerTubeX can send the market field (M, outside the fork).** Add persisted snapshots per market, keyed by YouTube's playlist id. Movement can be honest without a date: treat a snapshot as a new issue only when its contents change, and compare consecutive issues. That yields Spotify-like "new entries" and up or down arrows. Show "this week's ranking", YouTube's own wording, rather than a date Turtle would have to invent.
3. **Fix before either:** India's chart has 24 of 100 songs typed `PODCAST_EPISODE`, which Turtle treats as spoken word.

The owner still decides whether presenting YouTube's chart playlists inside Turtle this way is acceptable.

## Suggested order of small wins

Each is a separate change and builds on what exists:

| Order | Change | Size |
| --- | --- | --- |
| 1 | Explore "Trending now": no rank numbers, descriptive title | S |
| 2 | Search Browse tiles open the matching Infinity genre | S |
| 3 | Stations row on Home, using step 4b's seeded radio | S–M |
| 4 | Charts entry to YouTube Music's own chart page | S |
| 5 | Daily Mix titles and numbered covers | S |
| 6 | Lyrics preview card | S |
| 7 | Library sidebar Podcasts chip | S |
| 8 | Import banner | S |
| 9 | Your top mixes row, mood mix cards, daylist (W15), past-window Rewind shelves | M |
| 10 | Phone Explore with genre hubs (plan step 6 and W6) | M |
