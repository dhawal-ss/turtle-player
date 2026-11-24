package app.turtleplayer.bridge

import app.turtleplayer.core.TrackKey
import app.turtleplayer.core.TrackMeta
import app.turtleplayer.taste.SongKeys
import app.turtleplayer.taste.TasteProfile
import app.turtleplayer.taste.UserControls
import com.metrolist.shared.model.RadioQueuePage
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.model.isPodcast
import com.metrolist.shared.model.isVideoSong
import com.metrolist.shared.settings.BlockedContent
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The song identities ([SongKeys.of]) of what the listener marked "not interested", so other uploads
 * of a hidden song are caught too. Each hidden key is resolved through the taste [profile] (when the
 * caller may wait for it) or the in-memory song [cache], and through its saved Hidden page [records]:
 * a record keeps the raw title and first artist, which survives a restart when the cache does not.
 * Both identities are kept when they differ (the cache prefers the listener's or the canonical title).
 */
internal suspend fun hiddenRecordings(
    controls: UserControls,
    profile: TasteProfile?,
    records: List<HiddenEntry>,
    cache: SongCache,
): Set<TrackKey> {
    if (controls.lessLikeThisTracks.isEmpty()) return emptySet()
    val keys = SongKeys()
    val recordsByKey = records.filter { it.kind == HiddenKind.SONG }.associateBy { it.key }
    val out = HashSet<TrackKey>()
    for (key in controls.lessLikeThisTracks) {
        val meta = profile?.track(key)?.meta ?: if (key.scheme == TrackKey.YOUTUBE) cache.get(key.id)?.toTrackMeta() else null
        if (meta != null) out += keys.of(meta)
        val record = recordsByKey[key.value] ?: continue
        val artist = record.artist?.takeIf { it.isNotBlank() } ?: continue
        if (record.title.isBlank()) continue
        runCatching { TrackKey.fromMeta(artist, record.title) }.getOrNull()?.let(out::add)
    }
    return out
}

/** Upstream's own content settings, read again when the page is served (the listener may have changed them since the fetch). */
internal val upstreamBlocked: (SongItem) -> Boolean = { it.isUnavailable || BlockedContent.isBlocked(it) }

/**
 * The next page of a YouTube radio station, fetched off the caller's thread as upstream's own
 * callers do (Android ranks radio from the main thread; parsing a page is not free).
 */
internal val youTubeRadioContinuation: suspend (String) -> RadioQueuePage = { token ->
    withContext(Dispatchers.IO) { GlobalYouTubeRepository.instance.continueRadioQueue(token) }
}

/** A songs-only queue (upstream's `recommendationsSongsOnly`) takes no videos or podcast episodes. */
internal fun SongItem.notASong(): Boolean = isVideoSong || isPodcast

/** A radio page reached by [pageUntilEligible]: the page served, its eligible songs, and how many pages were fetched. */
internal class PagedRadio(
    val page: RadioQueuePage,
    val eligible: List<SongItem>,
    val fetched: Int,
)

/**
 * When everything on [first] was ruled out, fetches up to [maxPages] continuation pages within
 * [deadlineMs] in all, and returns the first one with an eligible song (with [first]'s chips), so a
 * station whose page held only hidden songs moves on instead of stalling. Never repeats a token.
 * A failed fetch or the deadline ends paging: the result then has no songs and the last token
 * reached, so the caller's next attempt starts after the pages already seen.
 */
internal suspend fun pageUntilEligible(
    first: RadioQueuePage,
    maxPages: Int,
    deadlineMs: Long,
    fetch: suspend (String) -> RadioQueuePage,
    eligibleOf: suspend (RadioQueuePage) -> List<SongItem>,
): PagedRadio {
    var page = first
    var fetched = 0
    val seen = HashSet<String>()
    val found =
        withTimeoutOrNull(deadlineMs) {
            while (fetched < maxPages) {
                val token = page.continuation ?: break
                if (!seen.add(token)) break
                currentCoroutineContext().ensureActive()
                val next =
                    try {
                        fetch(token)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        TurtleLog.error("radio: could not load the next page while skipping ruled-out songs", e)
                        break
                    }
                fetched++
                page = next.copy(chips = first.chips)
                val eligible = eligibleOf(page)
                if (eligible.isNotEmpty()) return@withTimeoutOrNull PagedRadio(page, eligible, fetched)
            }
            null
        }
    return found ?: PagedRadio(page.copy(songs = emptyList()), emptyList(), fetched)
}

/** [TrackMeta] of each song, for [app.turtleplayer.taste.eligibleExternal]; mapped back by YouTube id. */
internal fun List<TrackMeta>.backTo(songs: List<SongItem>): List<SongItem> {
    val byId = songs.associateBy { it.id }
    return mapNotNull { meta -> meta.youtubeId?.let(byId::get) }
}
