package app.turtleplayer.bridge

import androidx.sqlite.execSQL
import app.turtleplayer.bridge.DesktopDataMigration.Homes
import app.turtleplayer.bridge.DesktopDataMigration.Outcome
import com.metrolist.shared.db.JdbcSQLiteDriver
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesktopDataMigrationTest {
    private val root: File = Files.createTempDirectory("turtle-migration").toFile()
    private val old = homes("old")
    private val new = homes("new")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun homes(name: String) = Homes(File(root, "$name/data"), File(root, "$name/config"), File(root, "$name/state"))

    private fun File.write(
        relative: String,
        text: String,
    ): File = File(this, relative).apply { parentFile.mkdirs() }.also { it.writeText(text) }

    /** Every file under [dir] with its bytes, so a tree can be compared before and after. */
    private fun snapshot(dir: File): Map<String, String> =
        dir
            .walkTopDown()
            .filter(File::isFile)
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readText() }

    private fun seedOldInstall() {
        old.data.write("metrolist/metrolist.db", "library")
        old.data.write("metrolist/metrolist.db-wal", "wal")
        old.data.write("metrolist/metrolist.db.lck", "held by a running app")
        old.data.write("metrolist/downloads/song1.m4a", "audio")
        old.data.write("turtleplayer/turtle.db", "turtle history")
        old.config.write(
            "metrolist/settings.properties",
            "a=1\nupdateCacheSource=desktop-github-releases-metrolist-v1\nsentryEnabled=false\n",
        )
        old.state.write("metrolist/secrets.properties", "innerTubeCookie=encrypted")
        old.state.write("metrolist/metrolist.lock", "")
        val song = File(old.data, "metrolist/downloads/song1.m4a").absolutePath
        old.state.write("metrolist/downloads.json", """[{"songId":"abc","audioFilePath":${jsonString(song)},"size":5}]""")
    }

    private fun jsonString(value: String) = "\"" + value.replace("\\", "\\\\") + "\""

    @Test
    fun copiesEveryOldTreeIntoTurtlesOwnFolderAndLeavesTheSourceUntouched() {
        seedOldInstall()
        val before = snapshot(File(root, "old"))

        val steps = DesktopDataMigration.migrate(old, new)

        assertEquals(List(4) { Outcome.COPIED }, steps.map { it.outcome })
        assertEquals(before, snapshot(File(root, "old")), "the old install is never changed")
        assertEquals("library", File(new.data, "metrolist/metrolist.db").readText())
        assertEquals("wal", File(new.data, "metrolist/metrolist.db-wal").readText(), "the WAL travels with its database")
        assertEquals("audio", File(new.data, "metrolist/downloads/song1.m4a").readText())
        assertEquals("turtle history", File(new.data, "turtleplayer/turtle.db").readText())
        assertEquals("innerTubeCookie=encrypted", File(new.state, "metrolist/secrets.properties").readText(), "sign-in carries over")
    }

    @Test
    fun lockFilesOfARunningAppAreNotCopied() {
        seedOldInstall()
        DesktopDataMigration.migrate(old, new)
        assertFalse(File(new.data, "metrolist/metrolist.db.lck").exists())
        assertFalse(File(new.state, "metrolist/metrolist.lock").exists())
    }

    @Test
    fun theCopiedUpdateCacheIsResetSoUpstreamReleasesAreNeverOfferedAsTurtleUpdates() {
        seedOldInstall()
        DesktopDataMigration.migrate(old, new)
        val settings = File(new.config, "metrolist/settings.properties").readText()
        assertEquals("a=1\nsentryEnabled=false\n", settings, "only updateCacheSource is dropped; the Sentry opt-in is kept as it was")
    }

    @Test
    fun downloadPathsPointAtTheCopiedFiles() {
        seedOldInstall()
        DesktopDataMigration.migrate(old, new)
        val catalog = File(new.state, "metrolist/downloads.json").readText()
        val copied = File(new.data, "metrolist/downloads/song1.m4a").absolutePath
        assertTrue(jsonString(copied) in catalog, catalog)
        assertFalse(jsonString(File(old.data, "metrolist").absolutePath) in catalog, catalog)
        assertTrue("\"size\":5" in catalog, "other values are kept")
    }

    @Test
    fun aSecondRunDoesNothing() {
        seedOldInstall()
        DesktopDataMigration.migrate(old, new)
        // Turtle has run since: its own data must win over the old install's.
        File(new.data, "metrolist/metrolist.db").writeText("newer library")
        old.data.write("metrolist/added-later.txt", "x")
        val afterFirst = snapshot(File(root, "new"))

        val steps = DesktopDataMigration.migrate(old, new)

        assertEquals(List(4) { Outcome.ALREADY_MIGRATED }, steps.map { it.outcome })
        assertEquals(afterFirst, snapshot(File(root, "new")))
    }

    @Test
    fun aMissingOldTreeIsSkippedAndTheOthersStillCopy() {
        old.data.write("turtleplayer/turtle.db", "turtle history")
        val steps = DesktopDataMigration.migrate(old, new)
        assertEquals(
            listOf(Outcome.NOTHING_TO_COPY, Outcome.COPIED, Outcome.NOTHING_TO_COPY, Outcome.NOTHING_TO_COPY),
            steps.map { it.outcome },
        )
        assertFalse(File(new.data, "metrolist").exists(), "nothing is created for a tree that was never there")
    }

    @Test
    fun noStagingFoldersAreLeftBehind() {
        seedOldInstall()
        DesktopDataMigration.migrate(old, new)
        val leftovers = listOf(new.data, new.config, new.state).flatMap { it.listFiles().orEmpty().filter { f -> f.name.startsWith(".") } }
        assertEquals(emptyList(), leftovers)
    }

    @Test
    fun aStagingFolderFromACrashedRunIsReplacedByACompleteCopy() {
        seedOldInstall()
        val crashed = File(new.data, ".metrolist.turtle-migrating-1-1").apply { mkdirs() }
        File(crashed, "metrolist.db").writeText("half")
        crashed.setLastModified(0)

        val steps = DesktopDataMigration.migrate(old, new, nowMs = 10 * 60 * 60 * 1000L)

        assertEquals(Outcome.COPIED, steps.first().outcome)
        assertFalse(crashed.exists())
        assertEquals("library", File(new.data, "metrolist/metrolist.db").readText())
    }

    @Test
    fun homesSharedWithUpstreamThroughXdgVariablesAreLeftAlone() {
        seedOldInstall()
        val steps = DesktopDataMigration.migrate(old, old)
        assertEquals(emptyList(), steps)
    }

    @Test
    fun upstreamHomesFollowXdgVariablesAndOtherwiseItsFallbacks() {
        val absolute = File(root, "xdg-data").absolutePath
        val env = mapOf("XDG_DATA_HOME" to absolute, "XDG_CONFIG_HOME" to "relative/ignored")
        val homes = DesktopDataMigration.upstreamHomes(env::get, "/home/listener")
        assertEquals(File(absolute), homes.data)
        assertEquals(File("/home/listener", ".config"), homes.config, "a relative XDG value is ignored, as upstream does")
        assertEquals(File("/home/listener", ".local/state"), homes.state)
    }

    @Test
    fun aDatabaseStillOpenInARunningAppCopiesWithTheRowsInItsWal() {
        val database = File(old.data, "metrolist/metrolist.db").apply { parentFile.mkdirs() }
        // Metrolist (or an older Turtle) is still running: its connection holds the WAL and -shm open.
        JdbcSQLiteDriver().open(database.absolutePath).use { running ->
            running.execSQL("PRAGMA journal_mode = WAL")
            running.execSQL("PRAGMA wal_autocheckpoint = 0")
            running.execSQL("CREATE TABLE song (id TEXT PRIMARY KEY)")
            running.execSQL("INSERT INTO song VALUES ('a'), ('b')")
            assertTrue(File(database.path + "-wal").length() > 0, "the rows are only in the WAL")

            val steps = DesktopDataMigration.migrate(old, new)

            assertEquals(Outcome.COPIED, steps.first().outcome)
        }
        val copy = File(new.data, "metrolist/metrolist.db")
        val count =
            JdbcSQLiteDriver().open(copy.absolutePath).use { connection ->
                connection.prepare("SELECT COUNT(*) FROM song").use { if (it.step()) it.getLong(0) else -1L }
            }
        assertEquals(2L, count)
    }

    @Test
    fun aDatabaseThatFailsItsCheckIsNeverRenamedIntoPlace() {
        val database = File(old.data, "metrolist/metrolist.db").apply { parentFile.mkdirs() }
        // A torn copy: a SQLite header followed by pages that are not a database.
        database.writeBytes("SQLite format 3\u0000".toByteArray() + ByteArray(8192) { 0x5A })
        val before = snapshot(File(root, "old"))

        val steps = DesktopDataMigration.migrate(old, new)

        assertEquals(Outcome.FAILED, steps.first().outcome)
        assertFalse(File(new.data, "metrolist").exists(), "Turtle starts fresh rather than on a broken library")
        val leftovers =
            new.data
                .listFiles()
                .orEmpty()
                .filter { it.name.startsWith(".") }
        assertEquals(emptyList(), leftovers, "no staging is left behind")
        assertEquals(before, snapshot(File(root, "old")), "the old install is never changed")
    }
}
