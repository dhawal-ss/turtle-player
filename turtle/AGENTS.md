# Turtle Player agent guide

This guide covers everything under `turtle/` and overrides the root `AGENTS.md` for that code.
For upstream files (everything outside `turtle/`), the root `AGENTS.md` still applies, plus the
hook rules below.

The product and engineering plan is the source of truth for what we build and why.

## Fork discipline

- Build features in our modules under `turtle/`. Never edit upstream files to add behavior.
- When an upstream file must change, keep it to a one-line call into our code and add a row to
  `turtle/HOOKS.md` in the same PR. CI fails the PR otherwise. Budget: under 90 hook lines (raised
  from 75 on 23 Nov 2026 for Turtle's own release identity; the ledger records each raise and the
  running total; reconcile with the owner before adding one). Release identity lives in
  `turtle/branding.properties` and `TurtleRelease`; see `turtle/docs/release.md`.
- Write hook lines with fully qualified names (`com.metrolist.shared.turtle.TurtleUiSlots...`)
  instead of adding imports: one line per hook keeps the budget and merge conflicts small.
- UI takeovers go through `shared/.../turtle/TurtleUiSlots.kt` (a file we own): add a nullable
  slot there, call it from upstream in one line, and set it from `:turtle-ui`.
- Engines (`turtle/engines/*`) are pure Kotlin `commonMain`: no `java.*`, no Android, no upstream
  imports. Only `:turtle-bridge` may depend on upstream (`:shared`).
- Engines expose seams as interfaces (`OnlineSearchProvider`, `RecommendationSource`,
  `PlayEventStore`, ...). The bridge implements them. When upstream refactors, only the bridge
  changes.
- Inject `Clock` and `Random` so engine behavior is deterministic under test.

## Branches

All work happens on one local branch, `main`. Don't create feature branches or extra worktrees for ongoing work.

If a session needs isolation, merge its result into `main` straight away and delete the branch. Work left on side branches has been lost from builds, and fixed twice.

Before starting, check `git branch` and `git worktree list`. Anything other than `main` should be merged, not worked on. Commit and push only when the owner asks.

[Turtle status and handoff](docs/plans/2026-11-23-status.md) records where the work stands.

## Tests

Unlike upstream, our engines are tested thoroughly: they are pure logic and the product depends
on their quality.

- Unit tests for engine behavior, written against public APIs, not implementation details.
- Golden sets: the search golden query set and the recommendation offline replay. A regression of
  more than 2% on their metrics blocks the merge.
- Parsers (history imports, model packs) get fixture tests from real, reduced files.
- Do not add tests for upstream code; follow the root `AGENTS.md` there.

## Strings, privacy, licenses

- No user-facing strings in engines. Engines return typed reasons and enums; the UI localizes
  them through upstream's shared string resources.
- Listening history stays on device. Anything sent off-device is opt-in and documented.
- No Namida code. No MusicBrainz genre or tag data (non-commercial license). Check every model,
  dataset and font license before shipping.
- We never scrape Spotify; imports read only files the user downloads themselves.

## Commands

```
./gradlew -p turtle/engines check                 # all engine tests
./gradlew -p turtle/engines :turtle-search:jvmTest  # one module
bash turtle/scripts/check-hooks.sh origin/main     # hook-ledger guard (HEAD: uncommitted work only)
bash turtle/scripts/check-release-endpoints.sh     # updaters point at Turtle's releases
```

## Commits

Conventional Commits with a `turtle` scope for our code, for example
`feat(turtle-search): typo-tolerant library index`.
