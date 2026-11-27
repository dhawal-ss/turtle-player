# Turtle against Spotify: audit evidence

**26 November 2026 · audited at `09ae8be7`**

This directory holds the evidence from a review of Turtle against Spotify. It is raw material, not a
plan. The review was asked for as "a comprehensive review and audit of Turtle while researching and
comparing to Spotify", with an updated build plan as the deliverable. The evidence is complete; the
plan is not written, because the run that was to produce it stopped on a weekly usage limit. What is
missing, and how to finish it, is in [What is still owed](#what-is-still-owed) below.

It supersedes the Turtle-side status columns of
[the 23 November Spotify comparison](../../plans/2026-11-23-spotify-comparison.md), which was written
against `3f1f557e` from 14 screenshots. The commit after it, `09ae8be7`, implemented much of the
roadmap, so several of that document's rows are now wrong. Where the two disagree, this directory is
the later reading.

## How it was produced

Seventeen readers each took one subsystem and read the code at `09ae8be7`, following call sites into
upstream Metrolist, with file:line evidence required for every claim. Fifteen researchers each took
one Spotify domain and worked only from public documentation, with a source URL required for every
capability; all 427 capabilities carry one. Then each of the 23 defects the readers rated as blockers
was given to two independent verifiers, both briefed to refute it: one reading the code, one
establishing whether a real listener reaches the behaviour and what it costs them.

No subsystem was read twice, no Spotify capability was accepted without a source, and no blocker was
accepted on one reading. Nothing was built or run: these are code findings, and the frequency of each
path in real use is unmeasured.

| Pass | Agents | Result |
| --- | --- | --- |
| Turtle subsystem audit | 17 of 17 | 510 capabilities, 335 weaknesses, 170 strengths, 139 stale doc claims |
| Spotify research | 15 of 15 | 427 capabilities, every one with a source URL |
| Adversarial verification | 40 of 46 runs | 22 of 23 blockers settled; none refuted |
| Gap mapping, plan drafts, synthesis | 0 of 22 | did not run |

## What is in here

| Path | What it holds |
| --- | --- |
| `audit/` | One file per Turtle subsystem: capabilities with their availability state, weaknesses with severity and a fix sketch, strengths, and the claims in Turtle's own documents that are stale at `09ae8be7` |
| `spotify/` | One file per Spotify domain: capabilities with source URL, launch date, Premium and market gating, plus mechanics notes and Spotify's own documented weaknesses |
| `verification.md` | Both verifiers' findings for each blocker, with the correction where the original claim overstated its scope |

Every capability in `audit/` carries one of four states, because Turtle inherits most of Metrolist and
replaces its UI, so a feature can exist and still be out of a listener's reach:

| State | Count | Meaning |
| --- | --- | --- |
| `TURTLE` | 282 | Implemented in Turtle's own code under `turtle/` |
| `UPSTREAM-OK` | 99 | Upstream Metrolist code, reachable through Shell |
| `UPSTREAM-HIDDEN` | 59 | Upstream code that works, which Shell's takeover gives no way to reach |
| `MISSING` | 70 | Not implemented anywhere in the repository |

The 59 hidden ones are the cheapest work in the whole comparison: the behaviour exists and is tested
upstream, and only a Shell entry point is missing.

## What the verification settled

Of 23 blockers, one was confirmed as stated, 21 were confirmed with a correction to their scope or
cause, and none was refuted. Six verifier runs were lost to the usage limit, which left
`taste-ranking-2` unverified by the workflow; it was then checked by hand, and the result is below.

The corrections matter. Three examples, in full in `verification.md`:

- **The Spotify import key split is real but narrower than claimed.** Local plays key as `yt:<id>`
  and imports as `meta:artist|title`, and nothing re-keys them, so per-track accumulators do split.
  But identity-level merging already happens for the heard set, replay spacing, artist affinity and
  finished plays, and fatigue only looks back 24 hours, so historical imports never reach it. The
  split bites at `profile.track(candidate.key)` in `Features.kt` and `InfinityMix.kt:582-583` and
  nowhere else. It is also unreachable today, because no shipped UI can run an import at all.
- **Scope was reclassified downward on nine blockers**, from "every listener" to "common" or "edge
  case", once a verifier established the conditions needed to reach the behaviour.
- **The one blocker confirmed exactly as stated** is that first run asks consent for crash reports
  and Shell then offers no way to switch them off.

### The finding that needed checking by hand

The audit claimed the taste engine's own queue-building pipeline has no caller. The verifiers for it
were lost to the usage limit, so it was checked directly. Production code calls 24 `TasteEngine`
methods. These are not among them:

`startAutoplay`, `stopAutoplay`, `upNext`, `setMood`, `nowPlaying`, `radio(seed)`, `startRadio(seed)`,
`recommendations`, `scoreRow`.

What production does call is `rankExternal` (three sites), `homeShelves`, `forYou`, `keptShelves`,
`jumpBackIn`, `selectHomeRows`, `matchesTaste`, `rowSongSupport`, `lessLikeThis` and the control and
profile methods. So shelf composition is live, and so is reranking, but **Turtle never builds a queue
itself**. It reorders the page YouTube returned, which means it cannot surface a song that page left
out. `dailyMixes`, `weeklyDiscovery`, `onRepeat`, `freshForYou` and `rediscover` have no direct
caller either, but they are composed inside `homeShelves`, so those shelves do reach listeners.

This is the one finding that changes what the plan has to be about, so it is stated plainly: the
engine's seven generators, slot plan, artist spacing, album caps and frozen queue head are written,
tested, and never used to choose what plays next.

## The other findings worth reading first

Each is argued with evidence in the file named beside it.

| Finding | Where |
| --- | --- |
| Shell's UI is English-only: 532 Turtle string keys with no `values-XX` sibling, behind a reachable picker offering 57 languages, while upstream ships 58 locales | `audit/code-health.md` |
| Everything Turtle learns is both unbackupable and leaking: `turtle.db` is absent from the in-app backup zip, so a reinstall wipes the profile, while `allowBackup="true"` with no exclusion sends the same history to Google's cloud backup | `audit/events-history.md`, `audit/privacy-legal.md`, `audit/release-ops.md` |
| Search bypasses every content block: with "hide explicit" on, explicit songs appear on the first paint of every search, and blocked artists and Not-interested songs come back | `audit/search.md` |
| Offline search offers songs that are not downloaded and cannot play, a behaviour upstream got right and Shell's takeover regressed | `audit/search.md` |
| The history importers are built, tested and unreachable: no file picker, no preview screen, no caller outside a test | `audit/events-history.md` |
| Weekly discovery can only propose songs already seen on a radio page, from a graph capped at 2000 artists | `audit/taste-candidates.md` |
| Desktop Home falls back to upstream's placeholder and error screens, so the local-first promise fails exactly when the network does | `audit/home-ui.md` |
| One malformed byte in the saved taste state discards every listener control at once | `audit/taste-ranking.md` |
| Songs YouTube mistypes as `PODCAST_EPISODE` are treated as spoken word end to end, and also break run splitting in measurement | `audit/spoken-word.md`, `audit/measurement.md` |

## What the Spotify research changes about the comparison

Two things reframe it, and both are documented with sources in `spotify/`.

**Much of what Spotify is compared against is Premium-only.** From Spotify's own support pages: the
mobile play queue, playing songs in any order, crossfade, Automix, gapless, downloads and music videos
are all Premium benefits. AI DJ is Premium across about 75 markets in six languages; AI Playlist is
Premium in 47 markets, English only; Spotify Studio is macOS-only. A comparison that treats these as
"what Spotify does" overstates what a listener actually gets, and it understates Turtle, which asks
for no tier. `spotify/sp-gating.md` lists each one with its gating.

**Spotify publishes its own recommender's limitations, and they are the openings.** Verbatim from the
BaRT paper's limitations: exploration runs "over a candidate set not the full item set", and the
ranking model is "not defined to promote diversity". Their own field experiment found that
history-based personalisation raised podcast streams 28.90% while *reducing* individual listening
diversity 11.51%. Discovery Mode places a paid promotion signal inside personalised recommendations
and inside Radio and Autoplay. And across every Spotify research and engineering page read for this
review, 2018 to 2026, every recommender is server-side: nothing describes recommendation computed on
the listener's device or working without a network. `spotify/sp-research-arch.md` and
`spotify/sp-weaknesses.md` carry the citations.

Two of their published techniques need no trained model and map onto code Turtle already has: the
2025 Home work's KL penalty between a slate's running content-type histogram and a target
distribution, and SPDD (KDD 2026), which adds an auxiliary objective to any greedy slate builder with
no retraining, using a dual variable updated after each pick. Both are described in
`spotify/sp-research-arch.md`.

Also relevant to the import work: Spotify's Extended Streaming History export covers the whole
lifetime of an account, one record per play, with 21 documented fields including `ms_played`,
`skipped`, `reason_start` and `reason_end`. Turtle's `SpotifyHistoryImporter` already reads them, so
an import can seed real skip and completion signals, not only plays.

## What is still owed

The plan was to be produced by mapping each product area against the evidence here, drafting three
rival plans from different strategies, scoring them with a judge panel and synthesising the winner.
None of that ran: the weekly usage limit stopped the workflow after verification. What failed, and
would need to run again:

| Step | Agents | Status |
| --- | --- | --- |
| Gap mapping, one per product area | 15 | none completed |
| Rival plan drafts (foundation first, parity sprint, differentiate) | 3 | none completed |
| Judge panel (owner, engineer, skeptic lenses) | 3 | none completed |
| Synthesis into a plan document | 1 | did not run |

Six verification runs were also lost, leaving `taste-candidates-1` with one lens instead of two and
`taste-ranking-2` with none; the latter was checked by hand, as recorded above.

The script that produces the remainder is not in this repository: it lived in the session scratchpad
and is gone with it. Rebuilding it is straightforward from this directory, because every input it
needs is here. The shape it expects is 15 product areas, each pointing at the relevant `audit/` and
`spotify/` files, then three drafts, three judges and one synthesis.

Before any of it, two constraints decide what a plan may contain:

- **The hook budget is the binding constraint.** `turtle/HOOKS.md` allows under 90 upstream lines and
  85 are used, so five remain. A `TurtleUiSlots` slot still costs one. Several findings here,
  including the desktop Home fallback and deleting a play from Turtle's log, need upstream lines that
  do not exist yet.
- **Nothing in this directory is a product decision.** The items waiting on the owner in
  [the status document](../../plans/2026-11-23-status.md) are still waiting, and this review adds
  more, among them whether to exclude `turtle.db` from cloud backup, whether to restore the
  crash-report switch, and how far to carry localisation.
