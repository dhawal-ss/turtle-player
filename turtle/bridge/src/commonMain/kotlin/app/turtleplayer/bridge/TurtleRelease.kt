package app.turtleplayer.bridge

/**
 * Where Turtle publishes its releases. Both in-app updaters read these through one-line hooks
 * (HOOKS.md #64 on Android, #65 on desktop), so Turtle never offers upstream Metrolist's releases as an
 * update. `.github/workflows/turtle-release.yml` refuses to publish from any other repository, and
 * `TurtleReleaseTest` keeps the three values consistent.
 */
object TurtleRelease {
    /** `owner/repo` on GitHub. */
    const val REPOSITORY = "dhawal-ss/turtle-player"

    /** GitHub REST base for the repository; the updaters append `/releases`. */
    const val RELEASES_API = "https://api.github.com/repos/$REPOSITORY"

    /** The releases page people open; the desktop updater links `$RELEASES_PAGE/tag/<tag>`. */
    const val RELEASES_PAGE = "https://github.com/$REPOSITORY/releases"
}
