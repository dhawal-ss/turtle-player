package app.turtleplayer.bridge

import com.metrolist.shared.db.JdbcSQLiteDriver
import com.metrolist.shared.utils.XdgUtils
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant

/**
 * One-time copy of the desktop data Turtle used to keep in upstream Metrolist's folders into Turtle's
 * own folder (`com.metrolist.shared.turtle.TurtleDesktopPaths`, HOOKS.md #67 and #68).
 *
 * Copied trees: `metrolist/` and `turtleplayer/` from the data home (library database, downloads,
 * fonts, playlist covers, `turtle.db`), and `metrolist/` from the config home (settings) and the state
 * home (encrypted secrets, the download catalog, logs).
 *
 * Runs first thing in desktop `main` (HOOKS.md #70), before settings, Koin or any database opens. Each
 * tree is copied into a staging folder next to its destination and then renamed into place, so a crash
 * mid-copy never leaves a half-copied tree that a later launch would take as migrated. A destination
 * that already exists is never touched, which makes every later launch a no-op. The source is never
 * changed or deleted: upstream Metrolist keeps working from it, and it stays as a backup.
 *
 * Not copied: the cache home (songs and images, regenerable and often hundreds of MB), lock files,
 * SQLite `-shm` index files (SQLite rebuilds them from the copied `-wal` on first open, while a stale
 * copy could misdirect reads), and the Windows WebView2 profile (the signed-in account lives in the
 * copied settings and secrets).
 *
 * Every SQLite database in a copy is opened and checked (`PRAGMA quick_check`) before the copy is
 * renamed into place. Metrolist or an older Turtle build may still be running and writing, which can
 * tear a file-by-file copy; a torn copy is thrown away and taken again, up to [COPY_ATTEMPTS] times.
 *
 * Two fix-ups make the copy behave as Turtle's own data:
 * - absolute paths inside top-level `*.json` files (the download catalog) that point into an old tree
 *   are rewritten to the new tree, because upstream only trusts downloads under the current folder;
 * - `updateCacheSource` is dropped from `settings.properties`, so the copied update cache (upstream's
 *   release list) is reset and the updater never offers an upstream release as a Turtle update.
 */
object DesktopDataMigration {
    /** The three XDG-style homes that hold data worth carrying over. */
    class Homes(
        val data: File,
        val config: File,
        val state: File,
    )

    enum class Outcome {
        /** Copied and renamed into place in this run. */
        COPIED,

        /** The destination already exists (migrated before, or Turtle created it); left alone. */
        ALREADY_MIGRATED,

        /** There is no old tree to copy. */
        NOTHING_TO_COPY,

        /**
         * The copy failed; the staging folder is removed and the source is untouched. Turtle then starts
         * that part fresh, and because its destination exists from then on, later launches leave it
         * alone. To retry, close Turtle, delete the destination named in `data-migration.log` and start
         * Turtle again (turtle/docs/release.md).
         */
        FAILED,
    }

    data class Step(
        val from: File,
        val to: File,
        val outcome: Outcome,
        val error: Throwable? = null,
    )

    private const val STAGING_MARK = ".turtle-migrating-"
    private const val STALE_STAGING_MS = 60 * 60 * 1000L

    /** Copies taken of one tree before giving up on a database that keeps failing its check. */
    const val COPY_ATTEMPTS = 3
    private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(StandardCharsets.US_ASCII)
    private const val SETTINGS_FILE = "settings.properties"
    private val UPDATE_CACHE_SOURCE = Regex("""^\s*updateCacheSource\s*[=:].*""")
    private val windows = File.separatorChar == '\\'
    private val json = Json { prettyPrint = false }

    /** The startup hook: migrates from upstream's folders into Turtle's, and never throws. */
    fun runAtStartup() {
        runCatching {
            val current = Homes(XdgUtils.getXdgDataHome(), XdgUtils.getXdgConfigHome(), XdgUtils.getXdgStateHome())
            val steps = migrate(upstreamHomes(System::getenv, System.getProperty("user.home")), current)
            writeReport(steps, File(current.state, "turtleplayer/data-migration.log"))
        }.onFailure { System.err.println("Turtle: desktop data migration skipped: $it") }
    }

    /** Where upstream Metrolist keeps its data: the XDG variable when set and absolute, else its fallback. */
    fun upstreamHomes(
        env: (String) -> String?,
        userHome: String,
    ): Homes {
        fun home(
            variable: String,
            fallback: String,
        ): File = env(variable)?.let(::File)?.takeIf(File::isAbsolute) ?: File(userHome, fallback)
        return Homes(
            data = home("XDG_DATA_HOME", ".local/share"),
            config = home("XDG_CONFIG_HOME", ".config"),
            state = home("XDG_STATE_HOME", ".local/state"),
        )
    }

    /**
     * Copies each old tree into [new] unless its destination exists. Homes that resolve to the same
     * folder (XDG variables set, so Turtle and upstream share it) are skipped.
     */
    fun migrate(
        old: Homes,
        new: Homes,
        nowMs: Long = System.currentTimeMillis(),
    ): List<Step> {
        val trees =
            listOf(
                File(old.data, "metrolist") to File(new.data, "metrolist"),
                File(old.data, "turtleplayer") to File(new.data, "turtleplayer"),
                File(old.config, "metrolist") to File(new.config, "metrolist"),
                File(old.state, "metrolist") to File(new.state, "metrolist"),
            ).filterNot { (from, to) -> sameLocation(from.parentFile, to.parentFile) }
        val rewrites = trees.flatMap { (from, to) -> prefixes(from).map { it to to.absolutePath } }
        return trees.map { (from, to) -> copyTree(from, to, rewrites, nowMs) }
    }

    private fun copyTree(
        source: File,
        target: File,
        rewrites: List<Pair<String, String>>,
        nowMs: Long,
    ): Step {
        if (Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) return Step(source, target, Outcome.ALREADY_MIGRATED)
        if (!Files.isDirectory(source.toPath(), LinkOption.NOFOLLOW_LINKS)) return Step(source, target, Outcome.NOTHING_TO_COPY)
        val parent = target.absoluteFile.parentFile
        var staging: File? = null
        return try {
            Files.createDirectories(parent.toPath())
            removeStaleStaging(parent, target.name, nowMs)
            var copied: File? = null
            var lastFailure: Throwable? = null
            for (attempt in 1..COPY_ATTEMPTS) {
                val candidate = File(parent, ".${target.name}$STAGING_MARK$nowMs-${System.nanoTime()}")
                staging = candidate
                copyRecursively(source.toPath(), candidate.toPath())
                lastFailure = failedDatabaseCheck(candidate)
                if (lastFailure == null) {
                    copied = candidate
                    break
                }
                candidate.deleteRecursively()
                staging = null
            }
            if (copied == null) throw IOException("a database failed its integrity check in $COPY_ATTEMPTS copies of $source", lastFailure)
            fixUp(copied, rewrites)
            Files.move(copied.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Step(source, target, Outcome.COPIED)
        } catch (error: Exception) {
            staging?.deleteRecursively()
            // Another launch may have finished the same tree first.
            if (target.exists()) Step(source, target, Outcome.ALREADY_MIGRATED) else Step(source, target, Outcome.FAILED, error)
        }
    }

    private fun copyRecursively(
        source: Path,
        target: Path,
    ) {
        Files.walkFileTree(
            source,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (attrs.isSymbolicLink) return FileVisitResult.SKIP_SUBTREE
                    Files.createDirectories(target.resolve(source.relativize(dir)))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    // Links could point outside the tree; lock files belong to a running process.
                    val name = file.fileName.toString()
                    val skip = !attrs.isRegularFile || attrs.isSymbolicLink || isLockFile(name) || name.endsWith("-shm", ignoreCase = true)
                    if (skip) return FileVisitResult.CONTINUE
                    Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException,
                ): FileVisitResult = throw exc
            },
        )
    }

    /** Why the first SQLite database in [dir] fails `PRAGMA quick_check`, or null when every one passes. */
    private fun failedDatabaseCheck(dir: File): Throwable? {
        for (database in dir.walkTopDown().filter { it.isFile && isSqliteDatabase(it) }) {
            val verdict = runCatching { quickCheck(database) }
            if (verdict.getOrNull() != "ok") {
                return verdict.exceptionOrNull() ?: IOException("${database.name}: ${verdict.getOrNull()}")
            }
        }
        return null
    }

    private fun isSqliteDatabase(file: File): Boolean {
        val name = file.name.lowercase()
        if (name.endsWith("-wal") || name.endsWith("-journal") || file.length() < SQLITE_HEADER.size) return false
        val header = ByteArray(SQLITE_HEADER.size)
        val read = file.inputStream().use { it.read(header) }
        return read == header.size && header.contentEquals(SQLITE_HEADER)
    }

    /** Opens the copy, which also replays its copied WAL, and returns SQLite's verdict ("ok" when intact). */
    private fun quickCheck(database: File): String =
        JdbcSQLiteDriver().open(database.absolutePath).use { connection ->
            connection.prepare("PRAGMA quick_check").use { if (it.step()) it.getText(0) else "" }
        }

    private fun isLockFile(name: String): Boolean = name.endsWith(".lock", ignoreCase = true) || name.endsWith(".lck", ignoreCase = true)

    private fun fixUp(
        staging: File,
        rewrites: List<Pair<String, String>>,
    ) {
        File(staging, SETTINGS_FILE).takeIf(File::isFile)?.let(::dropUpdateCacheSource)
        staging
            .listFiles { file -> file.isFile && file.name.endsWith(".json", ignoreCase = true) }
            .orEmpty()
            .forEach { rewritePaths(it, rewrites) }
    }

    /** Line-based, byte-preserving: the rest of the settings file stays exactly as upstream wrote it. */
    private fun dropUpdateCacheSource(file: File) {
        val text = String(file.readBytes(), StandardCharsets.ISO_8859_1)
        val lines = text.split('\n')
        val kept = lines.filterNot { UPDATE_CACHE_SOURCE.matches(it.trimEnd('\r')) }
        if (kept.size != lines.size) file.writeBytes(kept.joinToString("\n").toByteArray(StandardCharsets.ISO_8859_1))
    }

    private fun rewritePaths(
        file: File,
        rewrites: List<Pair<String, String>>,
    ) {
        val element = runCatching { json.parseToJsonElement(file.readText()) }.getOrNull() ?: return
        var changed = false

        fun rewrite(value: JsonElement): JsonElement =
            when (value) {
                is JsonObject -> {
                    JsonObject(value.mapValues { rewrite(it.value) })
                }

                is JsonArray -> {
                    JsonArray(value.map(::rewrite))
                }

                is JsonPrimitive -> {
                    val moved = if (value.isString) movedPath(value.content, rewrites) else null
                    if (moved == null) {
                        value
                    } else {
                        changed = true
                        JsonPrimitive(moved)
                    }
                }
            }
        val rewritten = rewrite(element)
        if (changed) file.writeText(json.encodeToString(JsonElement.serializer(), rewritten))
    }

    private fun movedPath(
        value: String,
        rewrites: List<Pair<String, String>>,
    ): String? {
        for ((oldPrefix, newPrefix) in rewrites) {
            if (value.length < oldPrefix.length || !value.startsWith(oldPrefix, ignoreCase = windows)) continue
            val rest = value.substring(oldPrefix.length)
            if (rest.isEmpty() || rest[0] == '/' || rest[0] == '\\') return newPrefix + rest
        }
        return null
    }

    /** The spellings a stored path may use for [dir]: as built from the home, and as resolved on disk. */
    private fun prefixes(dir: File): List<String> =
        listOfNotNull(dir.absolutePath, runCatching { dir.canonicalPath }.getOrNull()).distinct()

    private fun sameLocation(
        a: File,
        b: File,
    ): Boolean {
        val left = runCatching { a.canonicalPath }.getOrDefault(a.absolutePath)
        val right = runCatching { b.canonicalPath }.getOrDefault(b.absolutePath)
        return left.equals(right, ignoreCase = windows)
    }

    private fun removeStaleStaging(
        parent: File,
        name: String,
        nowMs: Long,
    ) {
        parent
            .listFiles { file -> file.name.startsWith(".$name$STAGING_MARK") }
            .orEmpty()
            .filter { nowMs - it.lastModified() > STALE_STAGING_MS }
            .forEach { it.deleteRecursively() }
    }

    private fun writeReport(
        steps: List<Step>,
        log: File,
    ) {
        val notable = steps.filter { it.outcome == Outcome.COPIED || it.outcome == Outcome.FAILED }
        if (notable.isEmpty()) return
        val now = Instant.now()
        val lines =
            notable.joinToString("") { step ->
                val detail = step.error?.let { ": $it" }.orEmpty()
                "$now ${step.outcome} ${step.from.absolutePath} -> ${step.to.absolutePath}$detail\n"
            }
        for (step in notable.filter { it.outcome == Outcome.FAILED }) {
            System.err.println("Turtle: could not copy ${step.from} to ${step.to}: ${step.error}")
        }
        runCatching {
            log.parentFile.mkdirs()
            log.appendText(lines)
        }
    }
}

/** The desktop startup hook (HOOKS.md #70). Top level, so the upstream line is one plain call. */
fun migrateDesktopDataAtStartup() = DesktopDataMigration.runAtStartup()
