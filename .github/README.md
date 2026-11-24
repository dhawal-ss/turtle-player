<div align="center">

<h1>Turtle</h1>

<p>A music player that learns what you like and tells you why it picked each song.<br>
A fork of <a href="https://github.com/MetrolistGroup/Metrolist">Metrolist</a> for Android and Windows.</p>

</div>

## Download

Get the latest version from [Turtle's releases](https://github.com/dhawal-ss/turtle-player/releases/latest).
Every release lists a `SHA256SUMS` file; check your download against it.

| Platform | File | Updates |
| --- | --- | --- |
| Android 7+ | [`androidApp-release.apk`](https://github.com/dhawal-ss/turtle-player/releases/latest/download/androidApp-release.apk) | Turtle checks its own releases and offers new versions in the app |
| Windows 10/11 (x64) | `Turtle-windows-x64-<version>.zip` from the [latest release](https://github.com/dhawal-ss/turtle-player/releases/latest) | Turtle tells you when a new version is out and opens its release page; unzip it over the old folder |

Turtle installs beside Metrolist, not over it: it has its own Android app id (`app.turtleplayer`) and
its own Windows data folder (`%LOCALAPPDATA%\TurtlePlayer`). On its first start on Windows, Turtle
copies your library, settings and sign-in from Metrolist's folder and leaves the original untouched.

What changed in each version: [changelog](../turtle/docs/changelog.md).

## How Turtle differs from Metrolist

- **Home made from your listening**, with the reason each song is there.
- **Search that forgives typos** and ranks your library and YouTube together.
- **Radio ranked by your taste**, leaving out what you marked Not interested.
- **Infinity**: an endless mix you can steer while it plays.
- **Shell**: Turtle's own design across the app.

Everything Turtle learns stays on your device.

## Credits and license

Turtle builds on [Metrolist](https://github.com/MetrolistGroup/Metrolist) and its contributors, and
is licensed under [GPL-3.0](../LICENSE) like Metrolist. Metrolist's own README is
[here](../README.md). Please report Turtle problems to
[Turtle's issues](https://github.com/dhawal-ss/turtle-player/issues), not to Metrolist.

For developers: [working on the fork](../turtle/FORK.md) · [release procedure](../turtle/docs/release.md)
