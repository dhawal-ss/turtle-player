# Turtle changelog

Turtle's own release notes, newest first. Upstream Metrolist's changes that arrive through upstream
merges are not repeated here; name the upstream merge each release is built on instead.

Every release needs a `## <version>` section matching `version` in `turtle/branding.properties`.
`.github/workflows/turtle-release.yml` refuses to publish without one, and `TurtleReleaseTest` checks it
on every bridge test run. The workflow publishes the section's text as the release notes.

## 0.1.0

First Turtle release with its own identity. Built on upstream Metrolist 14.0.1.

- **Its own app.** Android installs as `app.turtleplayer`, beside Metrolist rather than over it.
  Windows keeps its settings, library, history and sign-in in its own folder
  (`%LOCALAPPDATA%\TurtlePlayer`) and copies what it used to share with Metrolist on first start.
  Turtle and Metrolist can now run side by side on Windows.
- **Its own updates.** Both apps check Turtle's releases, never Metrolist's.
- **Shell.** Turtle's design for Home, Search, Library, playlist, album, artist and podcast pages, the
  player, menus, dialogs and settings.
- **Personal Home.** Mixes and shelves from your own listening, with the reason each song is there.
- **Search.** Typo-tolerant search that ranks your library and YouTube together.
- **Radio.** Autoplay and Start radio are ranked by your taste, and leave out songs and artists you
  marked Not interested.
- **Infinity and Steer.** An endless mix you can steer while it plays.

Known limits: existing Android test installs do not carry Turtle's own history to the new app id
(see `turtle/docs/release.md`); Windows ships as a portable zip without in-app installation.
