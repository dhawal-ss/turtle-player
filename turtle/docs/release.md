# Releasing Turtle

Turtle ships under its own identity, from its own repository, with its own signing key. This page is
the procedure. Owner decisions behind it: 23 Nov 2026 (roadmap step 5b).

## What defines a release

| What | Where | Notes |
| --- | --- | --- |
| Version (`0.1.0`), Android `versionCode` (`1`), Android app id (`app.turtleplayer`) | `turtle/branding.properties` | The single source. The app id is permanent once shipped |
| Release repository (`dhawal-ss/turtle-player`) | `turtle/bridge/.../TurtleRelease.kt` | Both in-app updaters read it (HOOKS.md #64, #65) |
| Release notes | `turtle/docs/changelog.md` | One `## <version>` section per release |
| Build and draft release | `.github/workflows/turtle-release.yml` | Runs on a `v*.*.*` tag |
| Pinned Android signing certificate | `turtle/release/signing-cert.sha256` | Create it with the first release (below) |

How the values reach the apps:

- **Android app id and versionCode**: `turtle/release/identity.android.gradle`, applied by one line at
  the end of `androidApp/build.gradle.kts` (HOOKS.md #63). Debug builds are `app.turtleplayer.debug`,
  benchmark builds `app.turtleplayer.benchmark`.
- **Version name** (Android `versionName`, `BuildConfig.APP_VERSION` on both platforms, the Windows
  package version): the release workflow passes `-PappVersion=<version>`. A local build without that
  flag still reports upstream's `appVersion` from `gradle.properties` (14.0.1 today). The updaters
  compare that number with Turtle's release tags, so a local build never sees a `0.x` release as an
  update. Test the updater with `-PappVersion=0.0.1`.

## One-time setup (maintainer)

1. **Disable upstream's workflows in the fork.** `nightly.yml` publishes a release tagged `nightly`
   to whatever repository it runs in, and both updaters treat `nightly` specially. A tag check
   cannot stop that; only disabling the workflow can. On GitHub: Actions, select the workflow, `...`,
   **Disable workflow**. Do this for **Nightly** (`nightly.yml`), **Release** (`release.yml`) and
   **CI** (`ci.yml`): they need upstream's Blacksmith runners and secrets. Or with the CLI:
   `gh workflow disable nightly.yml -R dhawal-ss/turtle-player` (same for `release.yml`, `ci.yml`).
   The disabled state survives upstream merges.
2. **Create the Android signing key offline**, never on a CI runner:
   ```
   keytool -genkeypair -v -keystore turtle-release.jks -alias turtle -keyalg RSA -keysize 4096 -validity 36500
   ```
3. **Add repository secrets** (Settings, Secrets and variables, Actions):
   - `TURTLE_KEYSTORE_BASE64`: `base64 -w0 turtle-release.jks`
   - `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
   - Optional: `LASTFM_API_KEY`, `LASTFM_SECRET`, `LYPRIO_BASE_URL`, `LYPRIO_HMAC_KEY`. Without them
     Last.fm scrobbling and Lyprio lyrics build with empty keys and do not work. Upstream's keys
     belong to Metrolist; ask before reusing them.
4. **Pin the certificate.** After the first release builds, run
   `apksigner verify --print-certs androidApp-release.apk`, and commit the `SHA-256 digest` line's
   value to `turtle/release/signing-cert.sha256`. From then on the workflow fails if an APK is
   signed by any other key.

### Signing custody

- Keep two offline copies of `turtle-release.jks` and its passwords (for example an encrypted USB
  drive and a password manager). Lose the key and no installed copy can ever update again.
- The repository never holds the key, its passwords or the base64 text. The workflow decodes the key
  into the runner's temporary folder and deletes it after the build.
- To change keys, use APK Signature Scheme v3 rotation (`apksigner rotate`), which the Android updater
  accepts. Do not simply sign with a new key: installs would refuse the update.
- Windows builds are not Authenticode-signed (upstream's are not either); users check `SHA256SUMS`
  and GitHub's asset digests. Buying a code-signing certificate is a separate owner decision.

## Cutting a release

1. Merge upstream first: unless every `upstream-mirror` commit is merged, the workflow refuses a
   release whose newest upstream commit is more than seven days old (`turtle/FORK.md`). For an
   urgent fix, set the repository variable
   `TURTLE_ALLOW_STALE_UPSTREAM=true`, and clear it afterwards.
2. In one commit on `main`:
   - raise `version` in `turtle/branding.properties` (semantic versioning);
   - raise `androidVersionCode` by 1 (it must grow with every release, rollbacks included);
   - add a `## <version>` section at the top of `turtle/docs/changelog.md`, including the upstream
     Metrolist version the release is built on.
3. Run the checks locally: `./gradlew -p turtle/engines check`, `./gradlew :turtle-bridge:desktopTest`
   (includes `TurtleReleaseTest`, which checks the changelog section and the identity values) and
   `bash turtle/scripts/check-release-endpoints.sh`.
4. Tag and push: `git tag v<version> && git push origin v<version>`.
5. `turtle-release.yml` checks that the tag is `v<version>`, that `androidVersionCode` is higher than in
   every earlier Turtle tag (upstream's own `v*` tags, which have no `branding.properties`, are
   ignored), that the changelog has the section, that `TurtleRelease.REPOSITORY` is this
   repository, and that the updaters point at Turtle. It then runs the engine and bridge tests,
   builds the signed APK (checking its app id, version and certificate) and the Windows zip, writes
   `SHA256SUMS` and creates a **draft** release.
6. Download the draft's assets, install them (Android over the previous release; Windows over the
   previous folder), and check the on-device list below. Then publish the draft on GitHub.

Drafts are invisible to the updaters (GitHub's unauthenticated releases API leaves them out), so
nothing reaches users before step 6.

## Rolling back means rolling forward

Android cannot install an older `versionCode` over a newer one without uninstalling, which deletes
the user's data, and upstream's database refuses to open after a downgrade. So a bad release is
replaced, never reverted in place:

1. **Fix forward as soon as possible.** Revert the bad change on `main` (keep any database migration
   it added: Room refuses downgrades, and Turtle's own `SqlitePlayEventStore` migrations only add
   tables and indexes), raise `version` to the next patch and `androidVersionCode` by 1, and release.
   This is the only path that reaches every install.
2. **Meanwhile, hide the bad release.** Marking it a pre-release is **not** enough: Android's normal
   check skips pre-releases, but the desktop updater and both platforms' crash recovery still offer
   them. Turn it back into a draft instead:
   `gh api -X PATCH repos/dhawal-ss/turtle-player/releases/<release id> -F draft=true`
   (find the id with `gh api repos/dhawal-ss/turtle-player/releases --jq '.[] | [.id, .tag_name] | @tsv'`).
   Try this once in a drill before it is needed.
3. Note the bad version in `turtle/docs/changelog.md` under the fix's section.

## Data and identity notes

### Android: existing test installs move to a new app id

`app.turtleplayer` (and `app.turtleplayer.debug`) is a new app on the phone. It installs beside the
older test builds (`com.metrolist.music.debug`, `com.metrolist.music.debug.t2`) and beside Metrolist,
and starts empty.

- **Upstream data** (library, playlists, upstream history, settings, sign-in): make a backup in the
  old app (Settings, Backup and restore) and restore it in the new one. On its next start Turtle
  imports the restored plays through `MetrolistHistoryImport`.
- **Turtle-only data** (`turtle.db`: Turtle's play history, feedback, Not interested, artist links,
  kept shelves) is **not** in that backup. Until Turtle's own export and import ship (roadmap area 5),
  the only way across is a developer copy with adb between two debuggable builds. With the new
  debug build installed and the backup restored, but before opening the new app again:
  ```
  adb shell am force-stop com.metrolist.music.debug.t2
  adb shell am force-stop app.turtleplayer.debug
  for f in turtle.db turtle.db-wal turtle.db-shm; do
    adb exec-out run-as com.metrolist.music.debug.t2 sh -c "cat databases/$f 2>/dev/null" > "$f"
  done
  adb shell run-as app.turtleplayer.debug sh -c 'mkdir -p databases && rm -f databases/turtle.db databases/turtle.db-wal databases/turtle.db-shm'
  for f in turtle.db turtle.db-wal turtle.db-shm; do
    [ -s "$f" ] && adb exec-in run-as app.turtleplayer.debug sh -c "cat > databases/$f" < "$f"
  done
  ```
  Keep the copies off the repository and delete them afterwards: they hold listening history. A
  release build (`app.turtleplayer`) is not debuggable, so `run-as` cannot write into it.
- Shortcuts and links (`metrolist://`, `metrolist.cc`, YouTube links, Discord sign-in) still use
  upstream's schemes and action names. With Metrolist also installed, Android may ask which app to
  open, or open Metrolist. Turtle cannot verify `metrolist.cc` or YouTube's domains, so those links
  never open it by default.

### Windows: its own data folder

Turtle keeps everything under `%LOCALAPPDATA%\TurtlePlayer` (`data`, `config`, `state`, `cache`, the
`WebView2` sign-in profile and the startup `cds` cache), instead of Metrolist's `%USERPROFILE%\.local`,
`.config`, `.cache` folders and `%LOCALAPPDATA%\Metrolist`. On macOS and Linux the folder is
`~/.turtleplayer`; set XDG variables and both apps share them again.

On its first start, `DesktopDataMigration` copies the old trees into it before anything opens a
database: the library database, downloads, fonts, playlist covers, `turtle.db`, settings, the
encrypted sign-in secrets and the download catalog. It copies each tree to a staging folder and then
renames it into place, never deletes or changes the source, and does nothing once the destination
exists. It skips lock files, SQLite `-shm` index files (rebuilt on open), the song and image caches (they refill as you listen), and the WebView2
profile (the account itself is in the copied secrets). It resets the copied update cache so Metrolist's
releases are never offered as a Turtle update. Each copy is logged in
`%LOCALAPPDATA%\TurtlePlayer\state\turtleplayer\data-migration.log`.

- Close Metrolist and any older Turtle build before the first start of this version, so the database
  is copied while nothing writes to it. If one is still running, every copied database is opened and
  checked (`PRAGMA quick_check`) before the copy is used; a torn copy is taken again, up to three
  times, and then reported as FAILED instead of being used.
- If a copy failed (see the log), Turtle starts that part empty and does not retry by itself, because
  the part now exists. Close Turtle, delete the tree the log lists under `TurtlePlayer`, and start
  Turtle again to retry. The Metrolist original is never changed.
- Turtle and Metrolist can now run at the same time; they no longer share the single-instance lock,
  library, history, settings or update cache. Device Connect and casting listen on local ports, so
  use those in one app at a time (not checked on a device).
- Still shared with Metrolist: the `metrolist://` and `metrolist-auth://` link handlers (whichever app
  started last owns them) and the installer registry key that upstream's manager uses. Turtle ships no
  installer, so its updater opens the release page instead of installing.
- The window's taskbar identity is `Turtle.Desktop` (HOOKS.md #66), so Turtle and Metrolist group
  separately and media controls name Turtle.
- Playlist covers referenced from the library database keep pointing at Metrolist's folder; they still
  show while that folder exists.
- Linux, not part of the pilot: with no `XDG_DATA_HOME`, the Discord sign-in `.desktop` file is
  written under `~/.turtleplayer/data/applications`, where desktop environments do not look.

### Crash reporting (Sentry)

Unchanged from upstream: off by default, and a user who turns it on reports to **Metrolist's** Sentry
project with upstream's DSN. On Windows the migration carries over a choice made in Metrolist or an
older Turtle build. Giving Turtle its own Sentry project needs its own DSN (two upstream lines) and is
a separate owner decision.

## Deliberately not done (yet)

| Item | Why |
| --- | --- |
| Rename the Windows executable, package name and zip (`Metrolist.exe`, `packageName = "Metrolist"`) | About 20 upstream lines across packaging; the hook budget (under 90, 85 used) has no room. The workflow renames the zip instead |
| Turtle's own link schemes and Discord app | Needs new upstream lines in the Android manifest, desktop registration and Discord configuration, plus a Discord application |
| Windows installer and in-app installation | Upstream's `MetrolistManager` contract is Metrolist's; Turtle ships a portable zip |
| Windows ARM64 and the GraalVM native x64 build | Upstream builds these with Blacksmith and extra toolchains; the JVM x64 zip covers the pilot |
| iOS (`LatestRelease.kt`) | Out of the pilot's scope; the endpoint check allows it |
| Android `turtle.db` export and import | Roadmap area 5 |
