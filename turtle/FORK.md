# Working on the Turtle Player fork

Turtle Player is a fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist) (GPL-3.0). We
inherit playback, platforms and YouTube resilience from upstream, and own search,
recommendations and design. Our code lives beside upstream, never inside it.

## Layout

```
turtle/
  engines/          Standalone Gradle build, pure Kotlin Multiplatform (commonMain only)
    core/           :turtle-core    TrackKey, TrackMeta, TextNormalizer, TitleCanonicalizer
    events/         :turtle-events  PlayEvent log, ListeningSessionTracker, history importers
    search/         :turtle-search  Fuzzy, lyrics and merged search with intent detection
    taste/          :turtle-taste   Candidate generation, ranking, exploration, surfaces
  bridge/           :turtle-bridge  Our seams on upstream APIs: SQLite event store, player observer, Koin module
  scripts/          CI helpers (hook-ledger guard)
  HOOKS.md          Every line changed in an upstream file
  AGENTS.md         Rules for humans and agents working on our code
```

The engines build on their own, without the Android SDK, so their tests are fast:

```
./gradlew -p turtle/engines check
```

## Branches and remotes

| Name | Meaning |
| --- | --- |
| `upstream` remote | `MetrolistGroup/Metrolist` |
| `origin` remote | Our fork (to be created once the GitHub organization exists) |
| `upstream-mirror` | Tracks `upstream/main` untouched. Never commit to it. |
| `main` | Ours. PRs only, one review, green CI, no force pushes. |

## Merging upstream

Automated daily by `.github/workflows/turtle-upstream-sync.yml`. By hand:

```
git fetch upstream
git checkout upstream-mirror && git merge --ff-only upstream/main
git checkout -b sync/upstream-$(date +%Y%m%d) main
git merge upstream-mirror
```

1. Conflicts can only happen at the lines in `HOOKS.md`. Re-apply the hook on top of upstream's
   new code; the owner listed in `HOOKS.md` reviews.
2. If upstream refactored an API we use, fix it in `:turtle-bridge` only. Engines never import
   upstream code.
3. Run `./gradlew -p turtle/engines check` plus the app builds and playback canaries.
4. Stable releases are cut only from commits whose upstream merge is at most seven days old.

## Contributing back

Generic fixes (crashes, parsing, performance) go to Metrolist as PRs first, following their
`AGENTS.md`. Features on their rejected list (other playback backends, new-music notifications,
audio visualizers, custom app icons) stay in our modules and are never proposed upstream.

## License

GPL-3.0, same as upstream. Credit Metrolist in About and the README, publish all source, and
check every model, dataset and font license before shipping (no Namida code; MusicBrainz genres
and tags are non-commercial, so we derive our own).
