package app.turtleplayer.bridge

import app.turtleplayer.core.Clock
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.BrowseItem
import com.metrolist.shared.model.HomeSection
import com.metrolist.shared.model.PlaylistItem
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.YTItem
import com.metrolist.shared.model.canonicalHomeItemId
import com.metrolist.shared.model.isNonMusicalVideo
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** YouTube Music's Explore content, typed for Turtle's Explore page. Each list is unique by item. */
data class ExploreData(
    /** Moods and genres: each opens its category page (upstream's Screen.Browse) through Home's opener. */
    val genres: List<BrowseItem> = emptyList(),
    val newReleases: List<AlbumItem> = emptyList(),
    /** Trending songs, shown unranked as Popular picks. */
    val trending: List<SongItem> = emptyList(),
    val albumCharts: List<AlbumItem> = emptyList(),
    val community: List<PlaylistItem> = emptyList(),
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && newReleases.isEmpty() && trending.isEmpty() && albumCharts.isEmpty() && community.isEmpty()
}

/**
 * Where Explore's own load stands. [data] stays after a later refresh fails (stale beats nothing);
 * [failed] says the last attempt failed or found nothing, so the page offers Retry and the next open
 * tries again. [loadedAtMs] is when [data] was fetched.
 */
data class ExploreState(
    val data: ExploreData? = null,
    val loading: Boolean = false,
    val failed: Boolean = false,
    val loadedAtMs: Long? = null,
)

/**
 * Upstream's Home rules for Explore content (HomeViewModel's findSectionsByBrowseId, newReleasesFrom,
 * findMoodsAndGenresByBrowseId and HomeSection.isMoodAndGenresSection, which are private or internal
 * to :shared), applied to YouTube Music's Explore page and, for charts, its charts page. Pure.
 */
object ExploreMapping {
    const val EXPLORE = "FEmusic_explore"
    const val CHARTS = "FEmusic_charts"
    private const val MOODS = "FEmusic_moods_and_genres"
    private const val NEW_RELEASES = "FEmusic_new_releases"
    private const val COMMUNITY = "FEmusic_community"
    private const val PLAYLIST = "VL"

    /**
     * [explore]: the sections of FEmusic_explore (or any Home-shaped page). [charts]: the sections of
     * FEmusic_charts, read only for what [explore] has no chart shelf for. Every shelf on the charts
     * page is a chart, whatever its header links to, so there it prefers shelves that link to
     * FEmusic_charts and otherwise takes the whole page.
     */
    fun from(explore: List<HomeSection>, charts: List<HomeSection> = emptyList()): ExploreData {
        val exploreCharts = sectionsByBrowseId(explore, CHARTS)
        val chartSections = sectionsByBrowseId(charts, CHARTS).ifEmpty { charts }
        return ExploreData(
            genres = explore.filter(::isMoodsAndGenres)
                .flatMap { it.items.filterIsInstance<BrowseItem>() }
                .distinctBy { it.id to it.params },
            newReleases = explore.filter { s -> s.browseId?.let { it.startsWith(NEW_RELEASES) && !it.endsWith("_videos") } == true }
                .itemsOf<AlbumItem>(),
            // Upstream's Home drops non-musical videos from every shelf (withoutNonMusicalVideos).
            trending = exploreCharts.itemsOf<SongItem>().filterNot { it.isNonMusicalVideo }
                .ifEmpty { chartSections.itemsOf<SongItem>().filterNot { it.isNonMusicalVideo } }
                // Signed in (seen live, Nov 2026), the charts page holds no songs and Explore's song chart is
                // its "Trending" shelf, which links to a playlist (VL...) rather than FEmusic_charts. Matched by
                // its link, not its title, so it works in every language.
                .ifEmpty { explore.filter { it.browseId?.startsWith(PLAYLIST) == true }.itemsOf<SongItem>().filterNot { it.isNonMusicalVideo } },
            albumCharts = exploreCharts.itemsOf<AlbumItem>().ifEmpty { chartSections.itemsOf<AlbumItem>() },
            community = sectionsByBrowseId(explore, COMMUNITY).itemsOf<PlaylistItem>(),
        )
    }

    /** Whether [explore] has no chart songs, so the charts page is worth a request. */
    fun needsCharts(explore: List<HomeSection>): Boolean = sectionsByBrowseId(explore, CHARTS).itemsOf<SongItem>().all { it.isNonMusicalVideo }

    /** Upstream's HomeSection.isMoodAndGenresSection. */
    fun isMoodsAndGenres(section: HomeSection): Boolean = section.browseId?.startsWith(MOODS) == true && section.items.any { it is BrowseItem }

    private fun sectionsByBrowseId(sections: List<HomeSection>, prefix: String): List<HomeSection> =
        sections.filter { it.browseId?.startsWith(prefix) == true }

    private inline fun <reified T : YTItem> List<HomeSection>.itemsOf(): List<T> =
        flatMap { it.items }.filterIsInstance<T>().distinctBy(::canonicalHomeItemId)
}

/**
 * Loads YouTube Music's Explore page for Turtle's Explore, independent of Home. Signed in, the personal
 * Home feed carries no moods, charts or new releases on its first page (upstream adds moods only once
 * the whole feed has been paged), so Explore reads them from FEmusic_explore itself, and from
 * FEmusic_charts when Explore has no chart songs.
 *
 * One load at a time: a load asked for while one runs joins it. The result is kept for the process and
 * reloaded when older than [maxAgeMs], after a failure or an empty answer (on the next open), or when
 * forced (Retry). Never throws to the caller: failures are logged and leave [ExploreState.failed] set.
 */
class TurtleExplore(
    private val fetch: suspend (browseId: String) -> List<HomeSection>,
    private val filter: (List<HomeSection>) -> List<HomeSection> = { it },
    private val clock: Clock = Clock.System,
    private val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, t -> TurtleLog.error("explore: load failed", t) },
    ),
    private val maxAgeMs: Long = MAX_AGE_MS,
) {
    private val mutable = MutableStateFlow(ExploreState())
    val state: StateFlow<ExploreState> = mutable.asStateFlow()

    private val lock = Mutex()
    private var inFlight: Deferred<ExploreState>? = null

    /** The last pages fetched, unfiltered, so a later open applies blocks made since. */
    private var raw: Pair<List<HomeSection>, List<HomeSection>>? = null

    /** Starts a load in the background unless the kept result is fresh (see the class). */
    fun load(force: Boolean = false) {
        scope.launch { refresh(force) }
    }

    /** As [load], waiting for the result. Joins a load already running. */
    suspend fun refresh(force: Boolean = false): ExploreState {
        val running = lock.withLock {
            inFlight?.takeIf { it.isActive }?.let { return@withLock it }
            if (!force && isFresh()) {
                refilter()
                return@withLock null
            }
            mutable.update { it.copy(loading = true) }
            scope.async { fetchAndPublish() }.also { inFlight = it }
        }
        return running?.await() ?: mutable.value
    }

    private fun isFresh(): Boolean {
        val s = mutable.value
        val at = s.loadedAtMs ?: return false
        return s.data != null && !s.failed && clock.nowMs() - at < maxAgeMs
    }

    /** Applies the block lists again to the kept pages (cheap; no network). */
    private fun refilter() {
        val (explore, charts) = raw ?: return
        val data = mapped(explore, charts) ?: return
        if (!data.isEmpty) mutable.update { it.copy(data = data) }
    }

    private suspend fun fetchAndPublish(): ExploreState {
        try {
            val explore = fetch(ExploreMapping.EXPLORE)
            val charts = if (ExploreMapping.needsCharts(explore)) chartsPage() else emptyList()
            val data = mapped(explore, charts) ?: ExploreData()
            val now = clock.nowMs()
            if (data.isEmpty) {
                TurtleLog.error("explore: YouTube Music returned no Explore content (${explore.size} sections, ${charts.size} chart sections)")
                mutable.update { it.copy(loading = false, failed = true) }
            } else {
                raw = explore to charts
                TurtleLog.info(
                    "explore: ${data.genres.size} genres, ${data.newReleases.size} new releases, ${data.trending.size} trending, " +
                        "${data.albumCharts.size} album charts, ${data.community.size} community",
                )
                mutable.value = ExploreState(data = data, loading = false, failed = false, loadedAtMs = now)
            }
        } catch (e: CancellationException) {
            mutable.update { it.copy(loading = false) }
            throw e
        } catch (e: Exception) {
            TurtleLog.error("explore: could not load YouTube Music's Explore page", e)
            mutable.update { it.copy(loading = false, failed = true) }
        }
        return mutable.value
    }

    /** The charts page, or nothing: Explore without charts is still Explore. */
    private suspend fun chartsPage(): List<HomeSection> =
        try {
            fetch(ExploreMapping.CHARTS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("explore: could not load the charts page", e)
            emptyList()
        }

    /** The pages through the block lists, then mapped; the block lists failing keeps the pages as they are. */
    private fun mapped(explore: List<HomeSection>, charts: List<HomeSection>): ExploreData? =
        try {
            ExploreMapping.from(safeFilter(explore), safeFilter(charts))
        } catch (e: Exception) {
            TurtleLog.error("explore: could not read the Explore page", e)
            null
        }

    private fun safeFilter(sections: List<HomeSection>): List<HomeSection> =
        try {
            filter(sections)
        } catch (e: Exception) {
            TurtleLog.error("explore: block lists unavailable; showing Explore unfiltered", e)
            sections
        }

    companion object {
        /** Explore changes slowly (new releases weekly, charts daily); a few hours keeps it current. */
        const val MAX_AGE_MS = 3 * 60 * 60 * 1000L

        /** The app's Explore: upstream's repository and block lists. Needs no Koin or Turtle runtime. */
        val shared: TurtleExplore by lazy {
            TurtleExplore(
                fetch = { browseId -> GlobalYouTubeRepository.instance.getHomePageData(browseId = browseId).sections },
                filter = { BlockedContent.filterSections(it) },
            )
        }
    }
}
