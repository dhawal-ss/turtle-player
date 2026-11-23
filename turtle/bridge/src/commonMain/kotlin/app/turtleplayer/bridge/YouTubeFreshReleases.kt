package app.turtleplayer.bridge

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.HOUR_MS
import app.turtleplayer.core.MINUTE_MS
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.taste.FreshReleaseSource
import com.metrolist.shared.api.YouTubeRepository
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.ArtistEntity
import com.metrolist.shared.db.entities.toSongItem
import com.metrolist.shared.model.AlbumItem
import com.metrolist.shared.model.ArtistItem
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.state.GlobalYouTubeRepository
import com.metrolist.shared.youtube.playback.PlaybackNetworkStateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.context.GlobalContext
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** One album or single on an artist's YouTube Music page. YouTube gives only its year. */
internal data class ArtistRelease(
    val id: String,
    val year: Int?,
    /** YouTube's own "new release" badge. */
    val isNew: Boolean = false,
    /** Shown as the page's "latest release" card. */
    val latest: Boolean = false,
)

/** Upstream's side of Release Radar, so the freshness logic can be tested without YouTube. */
internal interface ReleaseCatalog {
    /** Artists the listener follows in upstream's library, newest follow first. */
    suspend fun followedArtists(): List<ArtistKey>

    /** The artist's YouTube channel id, or null when no artist matches. Throws on network failure. */
    suspend fun channelId(artist: ArtistKey): String?

    /** The artist's own albums and singles in page order. Throws on network failure. */
    suspend fun releases(channelId: String, artist: ArtistKey): List<ArtistRelease>

    /** A release's tracks in album order. Throws on network failure. */
    suspend fun tracks(releaseId: String): List<SongItem>
}

/**
 * Release Radar on YouTube Music: new albums and singles from the artists the listener follows and
 * their top artists, for the taste engine's Fresh for You shelf.
 *
 * YouTube only gives a release's year, so freshness comes from when we first saw a release on the
 * artist's page, persisted in our pref table:
 * - a release from before the window's year is never fresh;
 * - for an artist we scanned recently, a release that appears since counts as fresh from then;
 * - on an artist's first scan (first launch, or a new top artist) the existing catalogue is only
 *   recorded, except a release YouTube badges as new or shows as the latest-release card, so the
 *   shelf is not flooded with this year's older releases.
 *
 * The engine calls this under a 2 s budget, so it answers from memory and refreshes in the
 * background: at most [MAX_ARTISTS] artists, [PARALLELISM] requests at a time, each with a
 * timeout. Results are kept for [CACHE_MS] (failures retry after [RETRY_MS]); when a refresh
 * finds something, Home is rebuilt. Each refresh takes the next artists not checked lately, so
 * every requested artist is checked within a few refreshes, restarts included. Every failure is
 * logged and swallowed.
 */
class YouTubeFreshReleases internal constructor(
    private val store: SqlitePlayEventStore,
    private val cache: SongCache,
    private val scope: CoroutineScope,
    private val catalog: ReleaseCatalog,
    private val clock: Clock = Clock.System,
    private val onUpdated: suspend () -> Unit = ::refreshHome,
) : FreshReleaseSource {
    constructor(store: SqlitePlayEventStore, cache: SongCache, turtleScope: TurtleScope, database: () -> MusicDatabase) :
        this(store, cache, turtleScope.scope, YouTubeReleaseCatalog(database))

    private class Sighting(val firstSeenMs: Long, val fresh: Boolean)

    private class ArtistState(val channelId: String?, val lastScanMs: Long)

    /** A fresh release's tracks, with the time the release was first seen. */
    private class FreshTrack(val song: SongItem, val firstSeenMs: Long)

    private class Entry(val fetchedAtMs: Long, val ok: Boolean, val tracks: List<FreshTrack>) {
        fun current(now: Long) = now - fetchedAtMs < if (ok) CACHE_MS else RETRY_MS
    }

    private val mutex = Mutex()
    private val entries = HashMap<ArtistKey, Entry>()
    private var job: Job? = null

    // Persisted state, loaded on the first refresh and only touched by the (single) refresh job.
    private var loaded = false
    private val sightings = HashMap<String, Sighting>()
    private val artistStates = HashMap<ArtistKey, ArtistState>()

    /** When each artist was last checked, failures included: the rotation across refreshes. */
    private val checkedAt = HashMap<ArtistKey, Long>()
    private val releaseTracks = LinkedHashMap<String, List<SongItem>>()

    override suspend fun freshReleases(artists: List<ArtistKey>, sinceMs: Long, limit: Int): List<TrackMeta> = try {
        val now = clock.nowMs()
        val found = mutex.withLock {
            val stale = artists.filter { entries[it]?.current(now) != true }
            if (stale.isNotEmpty() && job?.isActive != true) job = scope.launch { refresh(stale, sinceMs) }
            artists.flatMap { entries[it]?.tracks.orEmpty() }
        }
        val picked = found.filter { it.firstSeenMs >= sinceMs }
            .sortedByDescending { it.firstSeenMs }
            .distinctBy { it.song.id }
            .take(limit)
        cache.putAll(picked.map { it.song })
        picked.map { it.song.toTrackMeta().copy(releasedAtMs = it.firstSeenMs) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TurtleLog.error("release radar: lookup failed", e)
        emptyList()
    }

    override suspend fun followedArtists(): List<ArtistKey> = try {
        catalog.followedArtists()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TurtleLog.error("release radar: could not read followed artists", e)
        emptyList()
    }

    /** Waits for a running background refresh; for tests. */
    internal suspend fun awaitRefresh() {
        mutex.withLock { job }?.join()
    }

    private suspend fun refresh(stale: List<ArtistKey>, sinceMs: Long) {
        try {
            val updated = withTimeoutOrNull(REFRESH_TIMEOUT_MS) { scan(stale, sinceMs) } ?: false
            if (updated) onUpdated()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("release radar: refresh failed", e)
        }
    }

    /** One background pass over the next batch of [stale] artists; true when it found fresh tracks. */
    private suspend fun scan(stale: List<ArtistKey>, sinceMs: Long): Boolean {
        load()
        val now = clock.nowMs()
        val artists = nextBatch(stale)
        val windowYear = yearOf(sinceMs)
        val gate = Semaphore(PARALLELISM)

        // 1. Channel ids and release lists, concurrently.
        val scans = coroutineScope {
            artists.map { artist ->
                async { gate.withPermit { scanArtist(artist) } }
            }.awaitAll()
        }

        // 2. Decide freshness for releases we see for the first time.
        val freshByArtist = LinkedHashMap<ArtistKey, List<Pair<String, Long>>>()
        for (s in scans) {
            val releases = s.releases ?: continue
            val previous = artistStates[s.artist]
            val known = previous != null && previous.lastScanMs > 0 && now - previous.lastScanMs <= KNOWN_ARTIST_MS
            for (r in releases) {
                if (r.id in sightings || r.year.let { it != null && it < windowYear }) continue
                sightings[r.id] = Sighting(now, firstSightFresh(r, known, windowYear))
            }
            artistStates[s.artist] = ArtistState(s.channelId, now)
            freshByArtist[s.artist] = releases.mapNotNull { r ->
                sightings[r.id]?.takeIf { it.fresh && it.firstSeenMs >= sinceMs }?.let { r.id to it.firstSeenMs }
            }.sortedByDescending { it.second }.take(MAX_RELEASES_PER_ARTIST)
        }
        for (s in scans) if (s.releases == null && s.channelId != null) {
            artistStates[s.artist] = ArtistState(s.channelId, artistStates[s.artist]?.lastScanMs ?: 0)
        }
        for (s in scans) checkedAt[s.artist] = now

        // 3. Tracks of fresh releases we have not loaded yet, bounded per refresh.
        val missing = freshByArtist.values.flatten().map { it.first }.filter { it !in releaseTracks }.distinct().take(MAX_RELEASES_PER_REFRESH)
        val loadedTracks = coroutineScope {
            missing.map { id -> async { gate.withPermit { id to soft("tracks of $id") { catalog.tracks(id) } } } }.awaitAll()
        }
        for ((id, songs) in loadedTracks) if (songs != null) remember(id, songs.filter { !it.isUnavailable }.take(MAX_TRACKS_PER_RELEASE))

        // 4. Publish.
        var found = 0
        mutex.withLock {
            for (s in scans) {
                val fresh = freshByArtist[s.artist].orEmpty()
                val tracks = fresh.flatMap { (id, seen) -> releaseTracks[id].orEmpty().map { FreshTrack(it, seen) } }
                // Releases whose tracks failed to load (or were over the per-refresh bound) retry soon.
                val complete = fresh.all { (id, _) -> id in releaseTracks }
                val ok = complete && (s.releases != null || (s.channelId == null && !s.failed))
                // A failed artist keeps what it had, so a flaky network does not empty the shelf.
                val kept = if (s.releases != null) tracks else entries[s.artist]?.tracks.orEmpty()
                entries[s.artist] = Entry(now, ok, kept)
                found += kept.size
            }
        }
        save(now)
        TurtleLog.info("release radar: scanned ${scans.count { it.releases != null }}/${artists.size} artists, $found fresh tracks")
        return found > 0
    }

    /**
     * Up to [MAX_ARTISTS] of [stale], most wanted first, except that [ROTATING] are the ones checked
     * longest ago. In-memory results are lost on restart, so without those the most wanted artists
     * would take every refresh after a restart and the rest would never be checked.
     */
    private fun nextBatch(stale: List<ArtistKey>): List<ArtistKey> =
        (stale.sortedBy { checkedAt[it] ?: 0L }.take(ROTATING) + stale).distinct().take(MAX_ARTISTS)

    private class ArtistScan(val artist: ArtistKey, val channelId: String?, val releases: List<ArtistRelease>?, val failed: Boolean)

    private suspend fun scanArtist(artist: ArtistKey): ArtistScan {
        val savedId = artistStates[artist]?.channelId
        if (!PlaybackNetworkStateStore.current.connected) return ArtistScan(artist, savedId, null, failed = true)
        val channelId = savedId ?: run {
            // "" marks "no such artist on YouTube", which is not retried as soon as a failure.
            val found = soft("artist id for $artist") { catalog.channelId(artist) ?: "" }
                ?: return ArtistScan(artist, null, null, failed = true)
            found.ifEmpty { return ArtistScan(artist, null, null, failed = false) }
        }
        val releases = soft("releases of $artist") { catalog.releases(channelId, artist) }
        return ArtistScan(artist, channelId, releases, failed = releases == null)
    }

    private suspend fun <T> soft(what: String, block: suspend () -> T): T? = try {
        withTimeoutOrNull(CALL_TIMEOUT_MS) { block() }.also { if (it == null) TurtleLog.info("release radar: $what timed out") }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        TurtleLog.error("release radar: $what failed", e)
        null
    }

    private fun remember(id: String, songs: List<SongItem>) {
        releaseTracks.remove(id)
        releaseTracks[id] = songs
        while (releaseTracks.size > MAX_CACHED_RELEASES) releaseTracks.remove(releaseTracks.keys.first())
    }

    private suspend fun load() {
        if (loaded) return
        loaded = true
        store.pref(PREF_SEEN)?.lineSequence()?.forEach { line ->
            val f = line.split(FIELD)
            val at = f.getOrNull(1)?.toLongOrNull()
            if (f.size == 3 && f[0].isNotEmpty() && at != null) sightings[f[0]] = Sighting(at, f[2] == "1")
        }
        store.pref(PREF_ARTISTS)?.lineSequence()?.forEach { line ->
            val f = line.split(FIELD)
            val at = f.getOrNull(2)?.toLongOrNull()
            if (f.size == 3 && f[0].isNotEmpty() && at != null) artistStates[ArtistKey(f[0])] = ArtistState(f[1].ifEmpty { null }, at)
        }
        store.pref(PREF_CHECKED)?.lineSequence()?.forEach { line ->
            val f = line.split(FIELD)
            val at = f.getOrNull(1)?.toLongOrNull()
            if (f.size == 2 && f[0].isNotEmpty() && at != null) checkedAt[ArtistKey(f[0])] = at
        }
    }

    private suspend fun save(now: Long) {
        val keep = sightings.entries.filter { now - it.value.firstSeenMs <= SIGHTING_TTL_MS }
            .sortedByDescending { it.value.firstSeenMs }.take(MAX_SIGHTINGS)
        sightings.keys.retainAll(keep.map { it.key }.toSet())
        // An artist not checked for this long sorts like one never checked, so its time can go.
        checkedAt.values.removeAll { now - it > KNOWN_ARTIST_MS }
        try {
            store.setPref(PREF_SEEN, keep.joinToString("\n") { (id, s) -> "$id$FIELD${s.firstSeenMs}$FIELD${if (s.fresh) 1 else 0}" })
            store.setPref(
                PREF_ARTISTS,
                artistStates.entries.filter { (k, _) -> FIELD !in k.value && '\n' !in k.value }
                    .joinToString("\n") { (k, s) -> "${k.value}$FIELD${s.channelId.orEmpty()}$FIELD${s.lastScanMs}" },
            )
            store.setPref(
                PREF_CHECKED,
                checkedAt.entries.filter { (k, _) -> FIELD !in k.value && '\n' !in k.value }
                    .joinToString("\n") { (k, at) -> "${k.value}$FIELD$at" },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TurtleLog.error("release radar: could not save sightings", e)
        }
    }

    internal companion object {
        const val MAX_ARTISTS = 15

        /** Of each refresh's [MAX_ARTISTS], how many go to the artists checked longest ago. */
        const val ROTATING = 5
        const val PARALLELISM = 3
        const val MAX_RELEASES_PER_ARTIST = 2
        const val MAX_RELEASES_PER_REFRESH = 12
        const val MAX_TRACKS_PER_RELEASE = 3
        const val MAX_CACHED_RELEASES = 64
        const val MAX_SIGHTINGS = 3_000
        const val CACHE_MS = 12 * HOUR_MS
        const val RETRY_MS = HOUR_MS
        const val CALL_TIMEOUT_MS = 20_000L
        const val REFRESH_TIMEOUT_MS = 3 * MINUTE_MS

        /** An artist scanned within this long is "known": releases appearing since are new. */
        const val KNOWN_ARTIST_MS = 30 * DAY_MS

        /** Longer than a year, so a release from this year never looks new again next year. */
        const val SIGHTING_TTL_MS = 400 * DAY_MS
        const val PREF_SEEN = "release_radar_seen"
        const val PREF_ARTISTS = "release_radar_artists"
        const val PREF_CHECKED = "release_radar_checked"
        const val FIELD = '\t'

        /** Whether a release seen for the first time counts as fresh. */
        fun firstSightFresh(release: ArtistRelease, artistKnown: Boolean, windowYear: Int): Boolean {
            val year = release.year
            if (year != null && year < windowYear) return false
            if (release.isNew) return true
            if (year == null) return false
            return artistKnown || release.latest
        }

        @OptIn(ExperimentalTime::class)
        fun yearOf(epochMs: Long): Int = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.UTC).year

        private suspend fun refreshHome() {
            GlobalContext.getOrNull()?.getOrNull<TurtleHomeShelves>()?.refresh()
        }
    }
}

/** [ReleaseCatalog] on upstream's YouTube Music repository and song database. */
internal class YouTubeReleaseCatalog(
    private val database: () -> MusicDatabase,
    private val repository: () -> YouTubeRepository = { GlobalYouTubeRepository.instance },
) : ReleaseCatalog {
    private val libraryLock = Mutex()
    private var libraryIds: Map<ArtistKey, String>? = null
    private var libraryIdsAtMs = 0L

    override suspend fun followedArtists(): List<ArtistKey> =
        followedRows().map { ArtistKey.of(it.name) }.distinct()

    override suspend fun channelId(artist: ArtistKey): String? =
        libraryChannelIds()[artist] ?: repository()
            .searchFiltered(artist.value, SearchFilter.ARTIST, allowOfflineFallback = false)
            .items.filterIsInstance<ArtistItem>()
            .firstOrNull { ArtistKey.of(it.title) == artist && it.id.startsWith(CHANNEL_PREFIX) }?.id

    override suspend fun releases(channelId: String, artist: ArtistKey): List<ArtistRelease> {
        val page = repository().getArtist(channelId)
        val skip = setOfNotNull(page.librarySection, page.topTracksSection) + page.videoSections
        val latest = page.sections[LATEST_RELEASE].orEmpty().map { it.id }.toSet()
        return page.sections.filterKeys { it !in skip }.values.flatten()
            .filterIsInstance<AlbumItem>()
            // Artist shelves list own releases without credits; "Appears on" credits other artists.
            .filter { album -> album.artists.isEmpty() || album.artists.any { it.id == channelId || ArtistKey.of(it.name) == artist } }
            .distinctBy { it.id }
            .map { ArtistRelease(it.id, it.year?.trim()?.toIntOrNull(), isNew = it.isNew, latest = it.id in latest) }
    }

    override suspend fun tracks(releaseId: String): List<SongItem> =
        repository().getAlbum(releaseId, loadAllPages = false)?.songs.orEmpty()

    /** Followed artists, newest follow first; podcast channels are followed shows, not artists. */
    private suspend fun followedRows(): List<ArtistEntity> =
        database().dao().artistsBookmarkedByCreateDateAsc().first()
            .map { it.artist }.filter { !it.isPodcastChannel && it.name.isNotBlank() }.asReversed()

    /** Channel ids of followed artists and of artists in upstream's song table (library and history), rebuilt twice a day. */
    private suspend fun libraryChannelIds(): Map<ArtistKey, String> = libraryLock.withLock {
        val now = Clock.System.nowMs()
        libraryIds?.takeIf { now - libraryIdsAtMs < YouTubeFreshReleases.CACHE_MS }?.let { return it }
        val ids = HashMap<ArtistKey, String>()
        for (a in followedRows()) if (a.id.startsWith(CHANNEL_PREFIX)) ids.getOrPut(ArtistKey.of(a.name)) { a.id }
        for (row in database().dao().allSongs().first()) {
            val song = row.toSongItem()
            for (a in song.artists + song.canonicalArtists.orEmpty()) {
                val id = a.id ?: continue
                if (id.startsWith(CHANNEL_PREFIX) && a.name.isNotBlank()) ids.getOrPut(ArtistKey.of(a.name)) { id }
            }
        }
        libraryIds = ids
        libraryIdsAtMs = now
        ids
    }

    private companion object {
        const val CHANNEL_PREFIX = "UC"
        const val LATEST_RELEASE = "latest_release"
    }
}
