package com.metrolist.shared.turtle

import java.io.File

/**
 * Turtle Player's own desktop data folder, so Turtle never shares settings, library, history,
 * sign-in or the single-instance lock with an upstream Metrolist install on the same account.
 *
 * Upstream code reaches this only through one-line hooks (HOOKS.md #67 and #68): `XdgUtils` falls back
 * to these homes when no XDG variable is set, and the Windows `%LOCALAPPDATA%` sites use
 * [WINDOWS_DIR]. On first launch `app.turtleplayer.bridge.DesktopDataMigration` (HOOKS.md #70)
 * copies the data Turtle used to keep in upstream's folders into these.
 *
 * - Windows: `%LOCALAPPDATA%\TurtlePlayer\{data,config,state,cache}`, next to its `WebView2` profile.
 * - macOS and Linux: `~/.turtleplayer/{data,config,state,cache}`. XDG variables still win when set.
 *
 * A file we own in upstream's tree; it never conflicts on merge.
 */
object TurtleDesktopPaths {
    /** The folder under `%LOCALAPPDATA%` on Windows. */
    const val WINDOWS_DIR = "TurtlePlayer"

    /** The folder under the home directory on macOS and Linux. */
    const val HOME_DIRECTORY = ".turtleplayer"

    fun root(): File =
        root(
            osName = System.getProperty("os.name").orEmpty(),
            localAppData = System.getenv("LOCALAPPDATA"),
            userHome = System.getProperty("user.home"),
        )

    fun root(
        osName: String,
        localAppData: String?,
        userHome: String,
    ): File =
        if (osName.startsWith("Windows", ignoreCase = true)) {
            val base = localAppData?.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isAbsolute) ?: File(userHome, "AppData/Local")
            File(base, WINDOWS_DIR)
        } else {
            File(userHome, HOME_DIRECTORY)
        }

    /** Replaces upstream's `~/.local/share` fallback. */
    val dataHome: File get() = File(root(), "data")

    /** Replaces upstream's `~/.config` fallback. */
    val configHome: File get() = File(root(), "config")

    /** Replaces upstream's `~/.local/state` fallback. */
    val stateHome: File get() = File(root(), "state")

    /** Replaces upstream's `~/.cache` fallback. */
    val cacheHome: File get() = File(root(), "cache")
}
