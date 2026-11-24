package app.turtleplayer.bridge

import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Release identity stays consistent across the updaters, branding.properties, the changelog and the README. */
class TurtleReleaseTest {
    // Gradle runs these tests from turtle/bridge.
    private val turtleDir = File("..").canonicalFile
    private val repoDir = turtleDir.parentFile

    private fun branding(): Properties = Properties().apply { File(turtleDir, "branding.properties").reader().use(::load) }

    @Test
    fun updaterEndpointsAreBuiltFromTheRepository() {
        assertEquals("https://api.github.com/repos/" + TurtleRelease.REPOSITORY, TurtleRelease.RELEASES_API)
        assertEquals("https://github.com/" + TurtleRelease.REPOSITORY + "/releases", TurtleRelease.RELEASES_PAGE)
    }

    @Test
    fun theRepositoryIsAnOwnerSlashRepoNameAndNotUpstreams() {
        assertTrue(Regex("[A-Za-z0-9-]+/[A-Za-z0-9._-]+").matches(TurtleRelease.REPOSITORY), TurtleRelease.REPOSITORY)
        for (value in listOf(TurtleRelease.REPOSITORY, TurtleRelease.RELEASES_API, TurtleRelease.RELEASES_PAGE)) {
            assertFalse("MetrolistGroup" in value, value)
        }
    }

    @Test
    fun brandingHoldsAPermanentAppIdAPositiveVersionCodeAndASemanticVersion() {
        val branding = branding()
        val applicationId = branding.getProperty("androidApplicationId")
        assertEquals("app.turtleplayer", applicationId, "the Android application id is permanent once shipped")
        val versionCode = branding.getProperty("androidVersionCode").toInt()
        assertTrue(versionCode >= 1)
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(branding.getProperty("version")), branding.getProperty("version"))
    }

    @Test
    fun theChangelogHasASectionForTheCurrentVersion() {
        val version = branding().getProperty("version")
        val changelog = File(turtleDir, "docs/changelog.md").readLines()
        val heading = Regex("^## ${Regex.escape(version)}(\\s.*)?$")
        assertTrue(changelog.any(heading::matches), "turtle/docs/changelog.md needs a '## $version' section")
    }

    @Test
    fun theReadmeDownloadLinksPointAtTurtlesReleases() {
        val readme = File(repoDir, ".github/README.md").readText()
        assertTrue("github.com/${TurtleRelease.REPOSITORY}/releases" in readme)
        assertFalse("MetrolistGroup/Metrolist/releases" in readme)
    }
}
