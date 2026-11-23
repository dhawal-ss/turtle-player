# Music discovery: first review evidence

**23 November 2026 · For the [music discovery handoff](2026-11-23-music-discovery.md) · Base `3b1f09b1`**

This review covers the handoff's first deliverables: chart acquisition evidence with a decision (step 1), plus one replay case each for a weak leading shelf item, a direct Infinity hit and a radio page with an unknown seed. No production code changed and nothing is committed.

## Decision: defer the ranked country songs chart

Contract item 6 applies. The provider path the app can reach is a weekly chart: for the current issue, its first ten match the official US and India *Weekly Top Music Videos* exactly. It establishes **country**, through a machine-readable market code, and **order**. But choosing a non-default country needs a request field that the app's InnerTubeX client cannot send today. It does not offer the **Top Songs** chart the plan names as the reference, and its response carries no **reporting period**. Ship steps 2–4 now. Hold steps 5–7 until one of the following holds:

- the owner accepts "Top music videos in [country]" labelled as this week's ranking, with no dates or movement, which needs a plan change; or
- a source for the songs chart that names its issue week is found and cleared for this use.

Until then, Explore can show these lists under a descriptive label without rank numbers or a week claim.

## Chart acquisition evidence

**Method.** All probes ran signed out on 23 November 2026, with gaps of at least two seconds between requests. Each one sent a `WEB_REMIX` `browse` for `FEmusic_charts`, the client and version InnerTubeX v0.12.0 uses. Each chart playlist was then fetched in two ways:

- a raw `browse` of its `VL…` ID;
- through the app's own parser, `YouTubeRepository.getPlaylistPageData` in a temporary opt-in desktop test, run with content filters both on and off.

The test has since been deleted. Raw responses and the probe scripts are in the session scratchpad and are not committed: they hold tracking parameters and have not been reduced.

### What the provider path returns

| Question | Result |
| --- | --- |
| How is the market chosen? | Only by `formData.selectedValues: ["<code>"]` on the `FEmusic_charts` browse. The request's `gl` does not choose it: signed out, `gl=IN` without `formData` returned the **United States** page. |
| Does the response say which market it is? | Yes. In `frameworkUpdates`, exactly one `musicFormBooleanChoice` entity is selected, and its entity key ends in the market code (`IN`, `US`, or `ZZ` for Global). The visible country label is localized (`भारत` with `hl=hi`), so compare the key, not the label. |
| Unsupported country | Requesting `PK` **silently returns Global**, the selected code is `ZZ`, with the Global playlists. (`ZZ` is Global's own code, and requesting it returns Global as expected.) The adapter must compare the requested and returned codes and treat a mismatch as unsupported. The selector holds 69 distinct codes: Global plus 68 markets, with the United States both pinned first and listed alphabetically. |
| Can the app's client select a market? | **Not today.** InnerTubeX v0.12.0's public `InnerTube.browse(client, browseId, params, continuation, setLogin)` cannot send `formData`, and `javap -public` shows no other method that takes a raw request body. Upstream code never sends `formData`. With the current client the app gets only the default market, the United States in these signed-out probes, whatever `gl` says. |
| Which chart kinds? | The *Video charts* carousel offers, for the US: Top 100 Music Videos (weekly by its description), Daily Top Music Videos, Trending 20 and Top 100 Live Performances. India has no Live Performances; Global offers only the two Top 100 / Daily video charts. **No Top Songs chart is offered.** The other shelves are Top artists, plus Top albums, Genres and Weekly top podcast shows for the US, and Languages for India. |
| Reporting period | **Not present.** The playlist header reads "Chart • 2026" and the description "This week's ranking of the most popular music videos on YouTube." There is no issue date or week anywhere in the browse or playlist response. |
| Order | Rank is playlist position; there is no rank field. All 100 items arrive in one page, with no continuation. Order and IDs were identical across `hl=en`/`hl=hi` and `gl=IN`/`gl=US` for the same playlist, so the playlist ID, not the locale, fixes the market. Titles and artist names are localized. |
| Playable IDs | 100 of 100 rows carry a `videoId` in each list checked, and none were greyed out. The app parser returned 100 songs with filters on and off, and none were unavailable. |
| Recording versions | All rows are videos, never audio tracks. US Top 100: 100 OMV. India Top 100: 74 OMV, **24 `PODCAST_EPISODE`** and 2 UGC. Global Top 100: 93 OMV, 6 `PODCAST_EPISODE`, 1 UGC. India's Trending 20 has 3 `PODCAST_EPISODE` rows, including No. 1. |

### Reproduce

Send `POST https://music.youtube.com/youtubei/v1/browse?prettyPrint=false` with `Content-Type: application/json`, `Origin: https://music.youtube.com`, and this body:

```json
{"context": {"client": {"clientName": "WEB_REMIX", "clientVersion": "1.20260707.12.00", "gl": "IN", "hl": "en"}},
 "browseId": "FEmusic_charts",
 "formData": {"selectedValues": ["IN"]}}
```

Reading the response:

- **Selected market.** Under `frameworkUpdates.entityBatchUpdate.mutations`, find the one `musicFormBooleanChoice` payload marked selected. URL-decode and base64-decode its `entityKey`; the decoded text ends in `explore_charts_country_menu_<digits><CODE>`.
- **Chart playlists.** These are the `musicTwoRowItemRenderer` items of the first carousel, *Video charts* for `hl=en`. A playlist is fetched with the same body, `browseId: "VL<playlistId>"` and no `formData`. Rank is position in `musicResponsiveListItemRenderer` order. The video type is `musicVideoType` under each item's watch endpoint.

A reduced fixture, which contract item 1 asks for, is deferred to step 5 alongside the parser that would use it. The raw responses carry tracking parameters and are not kept in the repository.

### Top ten as the app receives them

These lists were captured on 23 November 2026, 100 rows each.

US: Top 100 Music Videos United States (`PL4fGSI1pDJn69On1f-8NAvX_CYlx7QyZc`)

| Rank | Video ID | Type | Title | Artists |
| --- | --- | --- | --- | --- |
| 1 | `fcnDmrtj6Sk` | OMV | Dai Dai | Shakira & Burna Boy |
| 2 | `aAkMkVFwAoo` | OMV | All I Want for Christmas Is You (Make My Wish Come True Edition) | Mariah Carey |
| 3 | `nUsrYVxrDwI` | OMV | Choosin' Texas | Ella Langley |
| 4 | `RVsMPZ-EE5Y` | OMV | TAF (The Aura Farmer) | Horror Skunx |
| 5 | `yebNIHKAC4A` | OMV | Golden | HUNTR/X, EJAE, AUDREY NUNA & REI AMI |
| 6 | `ApXoWvfEYVU` | OMV | Sunflower | Post Malone & Swae Lee |
| 7 | `LJC7mgjp5FQ` | OMV | I Knew It, I Knew You | Taylor Swift |
| 8 | `kwwdY1bp-Ck` | OMV | Todo Lo Fue | Lenin Ramirez |
| 9 | `hX9S94XsySk` | OMV | Hong Kong (feat. 王菲季候風) | The Weeknd |
| 10 | `xxFjwI1qYmc` | OMV | WTF GOIN | 21 Savage & Belly Gang Kushington |

India: Top 100 Music Videos India (`PL4fGSI1pDJn40WjZ6utkIuj2rNg-7iGsq`)

| Rank | Video ID | Type | Title | Artists |
| --- | --- | --- | --- | --- |
| 1 | `CEbB9kdu8ng` | OMV | Guzarish | Satinder Sartaaj & Goldie Sohel |
| 2 | `vRjaGgDsWSo` | OMV | Casa Tupka Anthemo (feat. Priyanshi Srivastava) | Yo Yo Honey Singh |
| 3 | `ebZj_nrmH-c` | OMV | Barsaat | Banjaare & Roni |
| 4 | `Ddm7VUgw5Vw` | OMV | Dilruba Dilnasheen | Siddhant kaushal & Rito Riba |
| 5 | `xvT1jH8B9AM` | OMV | KALYANI (Remix) (feat. Shreya Ghoshal) | ARJN, KDS, FIFTY4 & RONN |
| 6 | `-ZnYdmreOrk` | OMV | Veezha Veera | Tippu & Sai Abhyankkar |
| 7 | `jhPPy-DT3sg` | OMV | Gulabi Nagar | Raja Raushan, Srishti Bharti, Gulli Gulshan & Vicky Vox |
| 8 | `YyepU5ztLf4` | OMV | Shararat | Madhubanti Bagchi, Jasmine Sandlas & Shashwat Sachdev |
| 9 | `BPZkZpGMpXA` | OMV | Tu Mil Jaaye | Arijit Singh, Madhubanti Bagchi, Sachin-Jigar & Kumaar |
| 10 | `A_RV2H2F9jw` | OMV | Basinga Balaalu | Srinidhi Nerella, Kalyan Keys & Swamy Naresh |

India's `PODCAST_EPISODE` rows are ranks 11, 17, 28, 29, 36–38, 42, 44, 48, 49, 57, 58, 64, 71, 72, 76, 88, 92–96 and 99. Rank 11 is "Jaiye Sajana" by Jasmine Sandlas, a song.

### Defects this exposes in today's code

- **Chart songs from India would be treated as spoken word.** The app parser marks all 24 `PODCAST_EPISODE` rows `isPodcast` (shared `Models.kt:482-485`). Through `TurtleSpoken.isSpokenWord` (`TurtleSpoken.kt:304-305`), Turtle would then:
  - leave a play of such a row out of taste (`PlayerObserver.kt:177`);
  - filter it out of Home rows (`TurtleHomeRows.kt:97`).

  So about a quarter of India's chart cannot reach a personal pick or teach the profile. This affects any music video a channel publishes as a podcast episode, not only charts. A fix needs a better spoken-word signal, such as a podcast show ID or page type, rather than the video type alone. That is a separate decision from charts.
- **Unlinked artists are dropped.** The parser returned three of the four artists at India rank 7 because "Gulli Gulshan" has no artist link. This matters for the plan's artist-spacing and "matching artist" resolution rules.

### Comparison with the official issues

The official chart pages were read on 23 November 2026, after the owner solved a reCAPTCHA. Every page compared shows the issue **Nov 13 – 19, 2026**.

| Official chart | App list | First-ten result |
| --- | --- | --- |
| US Weekly Top Music Videos | Top 100 Music Videos United States | **Ten of ten match in order**, and ranks 11–12 too (Tina Tuesday, Alma Rota). Titles and artists agree. The official artist line credits the *KPop Demon Hunters Cast* on Golden; the app's does not. |
| India Weekly Top Music Videos | Top 100 Music Videos India | **Ten of ten match in order**, and ranks 11–12 too: the official No. 11 is the row the app types `PODCAST_EPISODE`, and No. 12 is the UGC row. The official No. 11 credits three artists; the app row credits one. The official chart lists two separate *KALYANI (Remix)* uploads, at 5 and 14, so version twins chart separately. |
| Global Weekly Top Music Videos | Top 100 Music Videos Global | The first five match in order. The rest were not compared. |
| US Weekly Top Songs | none offered | Different chart. Of its first ten, No. 1 Choosin' Texas is the app's No. 3, and BbY WOW, Last Christmas, What You Need and Flower In A Hurricane are absent from the app's first ten. |
| India Weekly Top Songs | none offered | Different chart. For example, No. 4 Alfaaz (Hamza Malik & Zain Zohaib) is absent from the app's first ten, and Parvati is No. 6 here but No. 25 on the video chart. |
| Pakistan (unsupported) | Global lists | The official site **also redirects to Global** (`/pk/weekly` serves *Global Weekly Top Music Videos*), so Pakistan has no chart of its own. |

The app path therefore reads the **official weekly Top Music Videos chart** exactly, for the current issue. The response does not carry that chart's issue dates; only the official site shows them. The issue runs Friday 13 to Thursday 19 November. As of Monday the 23rd, it was the latest issue on the official pages.

### Not yet verified

- **Signed-in behavior.** No token was used. The owner's account was not used without their consent. Upstream drops regional chart sections from signed-in Home (`HomeSectionPolicy.kt:33-34`), but whether a signed-in `FEmusic_charts` browse behaves differently is untested.
- **Permission for the intended use.** The probe shows the app can read the lists. It cannot show whether Turtle may present them as a country chart. That remains an owner decision, as the contract says.

### What would make the ranked feature viable

1. The playlists match the official *Top Music Videos* issue, as shown above. The owner decides two things:
   - whether "Top music videos in [country]" is an acceptable substitute for a songs chart;
   - how to state the period. The contract forbids using download time as the chart date. The response gives no date, so the only honest label is "this week's ranking", which is the provider's own description, with no dates. Movement ("Rising") would compare consecutive snapshots whose issue boundaries are unknown, so it should stay off.
2. Otherwise, defer, and keep Explore's provider lists unranked under a descriptive label, per contract item 6.
3. Market selection needs new client support first:
   - either an InnerTubeX change that lets `browse` send `formData`, which is a separate repository and a separate PR;
   - or a bridge-owned request outside InnerTube, which duplicates client and session handling.

   Once a market's playlist ID is known, the existing `YouTubeRepository.getPlaylistPageData` reads it correctly. This was verified above for US and India, and the IDs stayed fixed across `gl` and `hl`. But discovering a market's ID still takes the `formData` browse, so step 5 costs more than the plan's "S/M". The client change also has to be owned outside this fork.
4. Either way, the adapter must:
   - send `formData` explicitly with the resolved `GlobalYouTube` country, never `gl` alone;
   - reject a response whose selected code differs from the request;
   - key snapshots by playlist ID;
   - classify spoken word with something other than `PODCAST_EPISODE` alone.

## Replay cases

Each case shows today's behavior so a fix can be compared against it. For each case:

- one agent traced the code read-only;
- a second agent re-read every cited line and tried to refute it;
- I re-checked the central lines myself.

Line numbers are for `3b1f09b1`. None of the draft tests was compiled or run; they are in the session scratchpad and are not committed. Where the code lives:

- Home layout, Infinity and the radio seed logic are bridge code, so their only test home is `turtle/bridge/src/desktopTest`.
- The engine replay (`ReplayEvaluator`) scores flat track lists and cannot hold rows or queues.
- Root `CLAUDE.md` forbids layout tests and one-fix suites. `turtle/AGENTS.md` only covers engines.

So whether these cases go into CI or stay recipes for the step-2 baseline is a maintainer decision.

**Before running any bridge test on a real machine:**
- Any bridge test that starts `turtleModule` writes to the owner's real `$XDG_DATA_HOME/turtleplayer/turtle.db`. That module provides `SqlitePlayEventStore` from `turtleDatabaseSpec()` (`TurtleModule.kt:91-95`, `TurtleDatabase.desktop.kt:9`), and `TurtleRuntime` takes that store directly (`TurtleRuntime.kt:57`). The existing `TurtleRuntimeTest` overrides only `MusicDatabase`.
- `SongLikeState` is a process-wide singleton read from `Settings.shared`, so real likes and dislikes can leak into a test.

Override both first, for step 2 and for any replay of cases 2 and 3.

### 1. A weak song leads a Matched shelf (confirmed)

**Why.** Matched mode keeps or drops whole rows on the row's average score:
- `HomeRows.kt:72-92` averages `tanh(affinity/3)` over the row;
- `HomeRows.kt:101-127` returns row keys only.

From there to the queue, every step preserves order:

| Location | What it does |
| --- | --- |
| `TurtleHomeRows.kt:144-147` | publishes row keys |
| `HomeLayout.kt:151-154` | removes hidden items |
| `HomeLayout.kt:205-223` | keeps the picked rows in pick order and removes grid duplicates |
| `ShellHome.kt:624-636` | `distinctBy` and `play(song, songs)` |

Nothing reorders the songs inside a row, so provider order is what the listener sees and hears.

**Case.** A warm profile of 60 finishes, all by one artist. The row `[g1, hid1, x1, n1, n2, lv1]` holds:

| Song | Listener history |
| --- | --- |
| `g1` | finished 30 minutes ago, so it becomes a grid tile |
| `hid1` | hidden |
| `x1` | quick-skipped three times |
| `n1`, `n2` | never played |
| `lv1` | finished six times |

A second row by an unknown artist completes the shelf.

**Today.**
- The row is kept: its value is about 0.59 against a minimum of 0.15, and the verifier recomputed this.
- The row reads `[x1, n1, n2, lv1]`: it **opens with the thrice-skipped song**, and the favourite is fourth.
- Tapping any song queues that order.

Three scoring details make this likely:
- The quick-skipped song does not count as disliked, because `isDisliked` only reads Not interested marks and artist-level negatives (`HomeRows.kt:141-150`).
- Never-heard songs by a loved artist score like loved tracks (`HomeRows.kt:153-159`).
- Row scoring includes hidden items the listener never sees (`ShellHome.kt:323`).

**Keep.**
- All mode: every row, unscored, in provider order.
- Off mode: no rows.
- Cold start below 50 finishes, and a scoring exception: the first three rows, unscored.
- Sticky slots and the personal-playlist slot.
- Removal of hidden items and grid duplicates.
- Playback follows the displayed order.
- Album, playlist and chart order.

**Reusable signals.**
- `TrackStats` affinity, `quickSkips` and recency, plus `SkipStats.rate` (`TasteProfile.kt:79-111`).
- `TasteProfile.hasHeard(TrackMeta)` for recording identity (`:221`).
- `RowScorer.matches`, which already checks each song but is only applied to grid suggestions (`HomeRows.kt:94-99`).

**Also found.**
- YouTube-row plays are tagged without the song's position (`TurtleModule.kt:281-288`), so on-device data cannot measure opener skips yet. Step 2 needs that.
- Long-press on a YouTube row item has no Not interested action (`ShellHome.kt:638-643`).

### 2. A direct Infinity hit skips ranking (partly confirmed, plus a crash-class bug)

**The plan's claim, corrected.** An excluded direct hit enters the pool, but it never *plays*. Every pick goes through `takeSpaced`, which drops songs that are:
- already taken;
- disliked;
- in `lessLikeThisTracks`, which includes upstream blocks merged in through `Seams.kt:55-60`;
- by a muted artist (`InfinityMix.kt:341, 349-356`).

What does hold:

- **Direct hits skip the taste ranker.** `InfinityMix.kt:443` checks only the ID against `taken`. Direct hits therefore skip:
  - `rankExternal`'s twin collapse and muted-cluster check (`TasteEngine.kt:298-316`);
  - the "already played three times" filter (`InfinityMix.kt:452`);
  - the session's passed-artist filter (`:453`).

  On a newly stocked pool, the first two songs are the raw shuffled search hits. At discovery dial 1.0 with one genre, the first song of the mix is a direct hit (`:283-293`).
- **Excluded hits still steer the mix.** An excluded hit can still become a radio seed (`:442`) and the ranking anchor (`:454`).
- **Upload twins get through.** Every exclusion matches the video ID, so a twin of an excluded or already-served song under another ID passes. A seed twin that `rankExternal` dropped is re-admitted on the next refill, unranked when it is the only fresh song (`:449-450`, `:477`). The verifier's trace shows **both twins served on every seed** in the reproduction.
- **Small pools can starve.** A purged hit never enters `taken`, so every refill re-adds it. Four such items count as "stocked" and stop `fillGenre` (`:397`, `:431`). The genre then yields nothing for that pick although eligible songs exist. That fails acceptance 4a ("a small genre pool still supplies the next track").

**The comparator bug, which is worse than the plan says.** `familiarCandidates` sorts with `sortedByDescending { it.value * (0.6 + random.nextDouble() * 0.8) }` (`InfinityMix.kt:374`). Kotlin re-evaluates the selector on every comparison, so one song gets a fresh key each time, which violates the comparator contract.

I reproduced this independently against JDK 25 with 2000 trials per size, keys 1–4 and fresh jitter on every comparison:

| Eligible favourites (n) | Sorts that threw `IllegalArgumentException` |
| --- | --- |
| 20 | 0 |
| 31 | 0 |
| 32 | 44 |
| 50 | 55 |
| 80 | 171 |
| 200 | 374 |

Below 32 the JDK uses insertion sort, which does not check the contract.

**Effect.** n counts favourite tracks plus every liked song (`:371`). Callers turn any exception into an empty list (`TurtleRuntime.kt:428-435`, `TurtleModule.kt:210-217`). So for a listener with 32 or more favourites and likes, an Infinity start or refill **intermittently returns nothing**, on Android and desktop. Production also cannot replay a seed: Koin builds `InfinityMix` with `Random.Default` (`TurtleModule.kt:131`), and `:362` reads `Clock.System` directly, contrary to `turtle/AGENTS.md`.

**Keep.**
- The pick-time purge in `takeSpaced`.
- The radio-side filters: three-play limit, passed artists and taste ranking.
- Artist spacing.
- No repeats within a session.
- Empty batches on failure, never a crash.
- Version variants stay distinct while upload twins collapse.

### 3. A radio page with no known seed (confirmed, with a wider leak)

**Why.** `rankRadio` takes only the page (`TurtleModule.kt:405`, `RadioQueuePage.kt:5-9`). It seeds with `observer.currentSong() ?: page.songs.first()` (`TurtleRuntime.kt:171`). Both callers have already removed the real station seed from the page:
- `AndroidQueueCoordinator.kt:541-566`;
- `DesktopQueueRadio.kt:93-103`.

`rankExternal` then drops the seed's recording (`TasteEngine.kt:298-300`).

**Case and today's behavior.**

1. **Cold observer, page `[X, Y, Z]`.** X becomes the seed and is **dropped**, leaving `{Y, Z}`. Before ranking, `coListening.observeRadio` records durable co-listening edges from X's artist to Y's and Z's (`TurtleRuntime.kt:174`, `CoListening.kt:46-51`).
2. **Station started on B while the observer holds A.** The page is ranked against A, and the co-listening edges run from A. On desktop the seed is the playing track (`PlayerState.kt:3373`), so this is mainly an Android race. Whether the observer still holds A when the first page arrives needs a device log.
3. **Song H marked Not interested.** A ranked page removes H. H **survives** on the paths that return the incoming page unfiltered:
   - a single-song page (`TurtleRuntime.kt:168`);
   - the 1.5-second timeout (`:185`, `RANK_TIMEOUT_MS` at `:465`, not injectable);
   - any exception (`:188-190`).

   The timeout is reachable at startup, because `refresh()` and `rankExternal` share one mutex (`TasteEngine.kt:168, 295, 683`).

**Wider than the plan says.**
- **Android's fallback paths leak artist blocks.** Android's radio filters check upstream-blocked **song IDs only** (`AndroidQueueCoordinator.kt:556, 902, 1068` → `BlockedContent.kt:97`). Blocked artists, the explicit filter and the video filter are enforced only by the engine's ranked path, through `UpstreamBlocks` merged into controls (`TasteEngine.kt:151-157`). So on Android's fallback paths, a song by an **upstream-blocked artist** plays, not just a Turtle Not interested song. Desktop uses the full `BlockedContent::isBlocked` and is unaffected.
- **Spoken word triggers the cold-observer path.** Spoken word clears the observer (`PlayerObserver.kt:177-180`), so radio pages ranked during a podcast or audiobook take the cold-observer path.
- **Desktop's first station page is never ranked.** On desktop, the first page of an explicit Start radio comes from `freshRadioRecommendations` and never reaches `rankRadio` (`PlayerState.kt:3747-3761`). The opening station page is neither taste-ranked nor filtered for Not interested.
- **No hook can carry the seed today.** The engine already has `startRadio(RadioSeed)` (`TasteEngine.kt:273-283`, `Candidates.kt:106-109`), but nothing calls it. Both apps know the seed (`AndroidQueueCoordinator.kt:88, 487, 586, 833`; `DesktopQueueRadio.load(seed)`). The plan's "reuse the existing hook" therefore means either:
  - a seed parameter on the two existing hook lines, a hook-ledger change; or
  - the bridge reading the seed from somewhere it already observes.

**Keep.**
- Upstream blocks stay out on every path, including the Android artist gap above, which the fix should close.
- `rankRadio` never adds songs, and chips and continuation pass through.
- Fail-open within about 1.5 seconds.
- Other uploads of the real seed are removed.
- `PlayOrigin` tags on ranked songs.
- No co-listening learning while history is paused.
- The listener's queue edits survive refill.

## What this changes in the plan

- **Step 1** is done; the decision is above. Steps 5–7 wait for the owner.
- **Step 2:** done in the working tree; see [Step 2 baseline](#step-2-baseline) below.
- **Step 3:** score and order only visible songs; use the per-song `matches` and skip signals.
- **Step 4a:**
  - draw jitter once per item, which also removes the intermittent empty batch. Done in commit `adfbd029`: `familiarCandidates` now draws each song's shuffle before sorting;
  - put purged hits into a "ruled out" set so they stop re-entering and starving the pool;
  - check recording identity, not video ID;
  - inject `Clock` and `Random` through Koin.
- **Step 4b:**
  - apply the merged controls on every fallback path, on both platforms;
  - pass the station seed;
  - decide whether desktop's unranked first station page is in scope.
- **Not in this plan:**
  - spoken-word detection that does not depend on `PODCAST_EPISODE` alone;
  - credits for unlinked artists.

  Both affect charts and existing Home and history behavior. Each needs its own owner decision.

## Step 2 baseline

Play tags now say how a suggested song was ordered, so local history can separate the paths the replay cases compare.

**Tag format.** `PlayEvent.surfaceDetail` tags gain an optional serving path and position in their first field: `<place>[+<served>][@<position>]/<source>/<slot>[/<row>]`. Existing tags and their `source` and `slot` are unchanged, so learning is unaffected. Builds from before this change read a tag with `+` or `@` as unattributed.

| Served | Meaning | Written by |
| --- | --- | --- |
| `ranked` | ordered by Turtle's taste ranking | radio pages ranked in time; Infinity genre and "more like" songs ranked in time |
| `direct` | a provider search hit queued as it came | Infinity's two search hits per genre fill |
| `provider` | the provider's order by design, or nothing to rank | Home's YouTube rows; one-song radio pages; Infinity fills with no seed or fewer than two songs |
| `timeout` | provider order because ranking ran out of time | radio and Infinity |
| `failed` | provider order because ranking threw | radio and Infinity |

Position is the song's 1-based place among the songs of the YouTube row the listener tapped (`home-yt+provider@3/-/-/<row>`). Other places carry no position: the session's own position already orders radio and Infinity plays, and other Home callers pass one tile or a whole mix queue.

**Report.** `OriginReporter` (`turtle/engines/taste/.../eval/OriginReport.kt`) groups local plays by place, serving path, row position and listener maturity. Maturity is cold below the engine's `coldStartPlays` (50) finished plays, light below 500, and established after that. For each group it reports started, quick-skipped and finished plays, the untagged share, and the plan's early-skip rate. The KDoc defines a run, a deliberate start and eligibility. To print it for a copy of a phone's database, as aggregates only:

```bash
adb exec-out run-as com.metrolist.music.debug.t2 cat databases/turtle.db > /tmp/turtle-copy.db
```

```bash
./gradlew :turtle-bridge:desktopTest --tests '*LocalOriginReportProbe*' -PturtleOriginReportDb=/tmp/turtle-copy.db
```

Opening the copy migrates it in place, so never point the probe at a live database.

**First reading.** The probe was run on 23 November against a copy of the test phone's history. Every tagged play predates this change, so none carries a serving path yet.

| Measure | Result |
| --- | --- |
| Local plays | 284 |
| Untagged | 198 (70%) |
| Early-skip rate, Infinity | 16 of 23 (70%) |
| Early-skip rate, Turtle's Home rows | 5 of 6 (83%) |
| Early-skip rate, engine Up Next | 11 of 22 |
| Early-skip rate, radio | 0 of 5 |

The samples are small. Treat these figures as a starting point, not a finding.

**Golden gates.** Unchanged before and after: recall@50 0.587 and NDCG@10 0.868 for all finished plays, and 0.960 and 0.641 for new tracks only.

**Known limits.**
- **Leftover radio tags.** Radio songs that are tagged but never queued keep their pending tag until 500 newer tags push it out. Desktop queues four to six songs of a page. A later play of such a song from the library or an artist page is then recorded with that radio tag. This already happened on the ranked path. It now also happens for `timeout`, `failed` and `provider` pages. Fixing it needs `PlayerObserver` to expire tags, which is not part of this change.
- **App start.** `StartReason.UNKNOWN` also follows app start, which therefore reads as a deliberate start.
- **Home row into radio.** A Home row whose queue continues into upstream's radio opens a second, radio run.
- **Untagged paths.** Desktop's first Start radio page never passes through `rankRadio`, so its plays stay untagged.
- **Thin history.** The phone's local history held 265 plays on 23 November, almost none with radio or YouTube-row tags. The new tags only accumulate from this build on, so the synthetic replay remains the comparison for step 3 until real history builds up.
