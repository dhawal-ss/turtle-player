package app.turtleplayer.taste.eval

import app.turtleplayer.core.ArtistKey
import app.turtleplayer.core.Clock
import app.turtleplayer.core.DAY_MS
import app.turtleplayer.core.TrackKey
import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.events.TrackFeedback
import app.turtleplayer.events.TrackFeedbackKind
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.Served
import app.turtleplayer.taste.SongKeys
import app.turtleplayer.taste.TasteConfig

/*
 * Listener outcomes: the two measurement gates read before any learned ranker (developer
 * diagnostics, never shown in the app). Both are pure and read time only from the injected [Clock].
 * `notBeforeMs` is the measurement start: history recorded before it (older skip labelling, saves
 * that were never recorded) is left out.
 */

/**
 * First-five counts for one surface. [eligible] plays make the rate; [earlySkips] of them were
 * skipped before 30 seconds, [instantSkips] of those within a second (often a failed start).
 * [unknown] plays ended without a known reason before 30 seconds and [interrupted] ones were stopped
 * or failed: both take their slot but stay out of the rate. [joined] slots belong to a run that
 * playback ran on into, from an untagged play or from the last session, whose real first songs were
 * not recorded here: never eligible.
 */
data class FirstFiveCounts(
    val eligible: Int = 0,
    val earlySkips: Int = 0,
    val instantSkips: Int = 0,
    val unknown: Int = 0,
    val interrupted: Int = 0,
    val joined: Int = 0,
) {
    val rate: Double get() = share(earlySkips, eligible)

    /** Share of the classifiable slots whose end is unknown: how far to trust [rate]. */
    val unknownRate: Double get() = share(unknown, eligible + unknown)
}

data class FirstFiveReport(
    val fromMs: Long,
    val toMs: Long,
    val bySurface: Map<EarlyGroup, FirstFiveCounts>,
    /** The listener's own plays in the window with no origin tag. */
    val untaggedPlays: Int,
    /** The listener's own plays in the window. */
    val localPlays: Int,
) {
    val total: FirstFiveCounts get() = bySurface.values.fold(FirstFiveCounts()) { a, c ->
        FirstFiveCounts(
            a.eligible + c.eligible, a.earlySkips + c.earlySkips, a.instantSkips + c.instantSkips,
            a.unknown + c.unknown, a.interrupted + c.interrupted, a.joined + c.joined,
        )
    }

    fun render(): String = buildString {
        appendLine("first-five early skips, plays from $fromMs to $toMs")
        appendLine("local plays: $localPlays, untagged: $untaggedPlays (${percent(share(untaggedPlays, localPlays))})")
        val t = total
        appendLine(
            "all surfaces: eligible ${t.eligible}, early ${t.earlySkips} (${percent(t.rate)}), " +
                "unknown ${t.unknown} (${percent(t.unknownRate)})",
        )
        val rows = bySurface.entries.sortedWith(compareBy<Map.Entry<EarlyGroup, FirstFiveCounts>>(
            { it.key.place.ordinal },
            { it.key.served?.ordinal ?: -1 },
            { it.key.maturity.ordinal },
        )).map { (g, c) ->
            listOf(
                g.place.tag, g.served?.tag ?: "-", g.maturity.label, "${c.eligible}", "${c.earlySkips}", percent(c.rate),
                "${c.instantSkips}", "${c.unknown}", percent(c.unknownRate), "${c.interrupted}", "${c.joined}",
            )
        }
        table(listOf("place", "served", "maturity", "eligible", "early", "early%", "instant", "unknown", "unknown%", "interrupted", "joined"), rows)
    }
}

/**
 * The first five suggested plays of each run (the runs and slots of [OriginReporter]) and how many
 * were skipped early, per surface, over the last [windowDays] days and never before `notBeforeMs`.
 *
 * Runs are built over the whole local history, then slots are kept by their start time, so a run
 * cut by the window edge keeps its slot numbers. Unlike [OriginReporter]'s table, an end the player
 * could not classify ([EndReason.UNKNOWN]) is not a non-skip: under 30 seconds it is counted as
 * unknown, outside the rate; from 30 seconds on it is eligible and not a skip.
 *
 * Known limits: slots are run positions, not queue indexes. Playback errors are not recorded for
 * local plays, so a failed start may read as an early skip ([FirstFiveCounts.instantSkips]).
 */
object FirstFiveSkips {
    private const val INSTANT_MS = 1_000L
    private val INTERRUPTED = setOf(EndReason.ERROR, EndReason.STOPPED)

    fun report(
        events: List<PlayEvent>,
        clock: Clock,
        notBeforeMs: Long = 0,
        windowDays: Int = 28,
        coldStartPlays: Int = TasteConfig().coldStartPlays,
        establishedPlays: Int = 500,
    ): FirstFiveReport {
        val now = clock.nowMs()
        val from = maxOf(notBeforeMs, now - windowDays * DAY_MS)
        fun inWindow(e: PlayEvent) = e.startedAtMs in from..now

        val local = rateAll(events, coldStartPlays, establishedPlays).local()
        val groups = HashMap<EarlyGroup, FirstFiveCounts>()
        for (slot in earlySlots(local)) {
            val e = slot.rated.event
            if (!inWindow(e)) continue
            val group = EarlyGroup(slot.place, slot.rated.origin?.served, slot.rated.maturity)
            val c = groups[group] ?: FirstFiveCounts()
            groups[group] = when {
                slot.joined -> c.copy(joined = c.joined + 1)
                e.endReason in INTERRUPTED -> c.copy(interrupted = c.interrupted + 1)
                e.endReason == EndReason.UNKNOWN && e.msPlayed < PlayEvent.QUICK_SKIP_MS -> c.copy(unknown = c.unknown + 1)
                e.isQuickSkip -> c.copy(
                    eligible = c.eligible + 1,
                    earlySkips = c.earlySkips + 1,
                    instantSkips = c.instantSkips + if (e.msPlayed < INSTANT_MS) 1 else 0,
                )
                else -> c.copy(eligible = c.eligible + 1)
            }
        }
        val windowed = local.filter { inWindow(it.event) }
        return FirstFiveReport(from, now, groups, untaggedPlays = windowed.count { it.origin == null }, localPlays = windowed.size)
    }
}

/** A discovery pair's group: where the new song was first heard, and whether its artist was new too. */
data class DiscoveryGroup(val place: PlayPlace, val served: Served?, val maturity: Maturity, val newArtist: Boolean)

/**
 * [pairs] new songs whose window has closed; of them [saved] were liked, added to the library or a
 * playlist, [replayed] were played again on purpose, [either] one or both. [pending] pairs are still
 * inside their window and outside every rate.
 */
data class DiscoveryCounts(
    val pairs: Int = 0,
    val saved: Int = 0,
    val replayed: Int = 0,
    val either: Int = 0,
    val pending: Int = 0,
) {
    val savedRate: Double get() = share(saved, pairs)
    val replayedRate: Double get() = share(replayed, pairs)
    val eitherRate: Double get() = share(either, pairs)
}

data class DiscoveryReport(
    val nowMs: Long,
    val windowDays: Int,
    val groups: Map<DiscoveryGroup, DiscoveryCounts>,
    /** Songs first heard (since the measurement start) from an untagged play: no pair, origin unknown. */
    val unattributedFirstPlays: Int,
    /** Songs first heard (since the measurement start) in the listener's own plays, from anywhere. */
    val localFirstPlays: Int,
) {
    val total: DiscoveryCounts get() = groups.values.fold(DiscoveryCounts()) { a, c ->
        DiscoveryCounts(a.pairs + c.pairs, a.saved + c.saved, a.replayed + c.replayed, a.either + c.either, a.pending + c.pending)
    }

    fun render(): String = buildString {
        appendLine("$windowDays-day discovery save or replay, at $nowMs")
        appendLine(
            "new songs heard: $localFirstPlays, untagged first plays: $unattributedFirstPlays " +
                "(${percent(share(unattributedFirstPlays, localFirstPlays))})",
        )
        val t = total
        appendLine(
            "all surfaces: pairs ${t.pairs}, saved ${t.saved} (${percent(t.savedRate)}), replayed ${t.replayed} " +
                "(${percent(t.replayedRate)}), either ${t.either} (${percent(t.eitherRate)}), pending ${t.pending}",
        )
        for (newArtist in listOf(false, true)) {
            appendLine()
            appendLine(if (newArtist) "new artist" else "known artist")
            val rows = groups.entries.filter { it.key.newArtist == newArtist }
                .sortedWith(compareBy<Map.Entry<DiscoveryGroup, DiscoveryCounts>>(
                    { it.key.place.ordinal },
                    { it.key.served?.ordinal ?: -1 },
                    { it.key.maturity.ordinal },
                )).map { (g, c) ->
                    listOf(
                        g.place.tag, g.served?.tag ?: "-", g.maturity.label, "${c.pairs}", "${c.saved}", percent(c.savedRate),
                        "${c.replayed}", percent(c.replayedRate), "${c.either}", percent(c.eitherRate), "${c.pending}",
                    )
                }
            table(listOf("place", "served", "maturity", "pairs", "saved", "saved%", "replayed", "replay%", "either", "either%", "pending"), rows)
        }
    }
}

/**
 * Of the songs new to the listener that a suggestion surface played them, how many they saved or
 * played again on purpose within [windowDays] days.
 *
 * A song is one identity across uploads and imports ([SongKeys]). It is new until its first event,
 * imports included, or until an earlier playlist add or save. A pair is that first event when it is
 * the listener's own play, heard (more than 0 ms), from a suggestion surface ([PAIR_PLACES]) and not
 * before `notBeforeMs`; a first play from anywhere else, or untagged, makes no pair but the song is no
 * longer new. A local play of 0 ms was never heard and changes nothing.
 *
 * Saved: a local play of the song in `[exposure, exposure + window]` marked liked or added to a
 * playlist, or a local [TrackFeedbackKind.ADDED_TO_PLAYLIST] or [TrackFeedbackKind.SAVED] in that
 * range. Replayed: a later heard local play in the window started by a tap or "previous", or any heard
 * local play in the window flagged [PlayEvent.replayed] (seeking back to the start, repeat one). A surface
 * serving the song again on its own does not count. A pair whose window has not closed by
 * `clock.nowMs()` is pending.
 *
 * Known limits: plays while listening history was paused are not stored, so replays are undercounted
 * and a song can look new when it was not; saves made before the measurement build were never
 * recorded, and a song saved but never played is not known to the history.
 */
object DiscoveryReturn {
    /** Surfaces that choose songs for the listener. Search, Jump back in and Home's grid are the listener's picks. */
    val PAIR_PLACES: Set<PlayPlace> = setOf(
        PlayPlace.ENGINE, PlayPlace.INFINITY, PlayPlace.RADIO, PlayPlace.SMART_SHUFFLE, PlayPlace.HOME_TURTLE, PlayPlace.HOME_YOUTUBE,
    )
    private val DELIBERATE_REPLAYS = setOf(StartReason.TAPPED, StartReason.USER_PREVIOUS)
    private val SAVE_KINDS = setOf(TrackFeedbackKind.ADDED_TO_PLAYLIST, TrackFeedbackKind.SAVED)

    private class Exposure(val rated: Rated, val identity: TrackKey, val newArtist: Boolean)

    fun report(
        events: List<PlayEvent>,
        feedback: List<TrackFeedback>,
        clock: Clock,
        notBeforeMs: Long = 0,
        windowDays: Int = 7,
        coldStartPlays: Int = TasteConfig().coldStartPlays,
        establishedPlays: Int = 500,
    ): DiscoveryReport {
        val now = clock.nowMs()
        val window = windowDays * DAY_MS
        val keys = SongKeys()
        val rated = rateAll(events, coldStartPlays, establishedPlays)
        val saves = feedback.filter { it.kind in SAVE_KINDS }
            .sortedWith(compareBy({ it.atMs }, { it.id }, { it.kind.ordinal }, { it.track.key.value }, { it.detail }))

        val seenSongs = HashSet<TrackKey>()
        val seenArtists = HashSet<ArtistKey>()
        val pairs = ArrayList<Exposure>()
        var unattributed = 0
        var firstPlays = 0
        // Events and saves merged by time; at the same millisecond the play comes first.
        var f = 0
        for (r in rated) {
            val e = r.event
            while (f < saves.size && saves[f].atMs < e.startedAtMs) {
                seenSongs += keys.of(saves[f].track)
                seenArtists += saves[f].track.artistKey
                f++
            }
            val local = e.source == EventSource.LOCAL
            if (local && e.msPlayed <= 0) continue
            val identity = keys.of(e.track)
            if (!seenSongs.add(identity)) continue
            val newArtist = seenArtists.add(e.artistKey)
            if (!local || e.startedAtMs < notBeforeMs) continue
            firstPlays++
            val place = r.origin?.place
            when {
                place == null -> unattributed++
                place in PAIR_PLACES -> pairs += Exposure(r, identity, newArtist)
            }
        }

        // Later evidence, by identity: the listener's own plays and saves.
        val playsBySong = rated.filter { it.event.source == EventSource.LOCAL }.groupBy { keys.of(it.event.track) }
        val savesBySong = saves.filter { it.source == EventSource.LOCAL }.groupBy { keys.of(it.track) }

        val groups = HashMap<DiscoveryGroup, DiscoveryCounts>()
        for (p in pairs) {
            val origin = p.rated.origin!!
            val start = p.rated.event.startedAtMs
            val end = start + window
            val group = DiscoveryGroup(origin.place, origin.served, p.rated.maturity, p.newArtist)
            val c = groups[group] ?: DiscoveryCounts()
            if (end > now) {
                groups[group] = c.copy(pending = c.pending + 1)
                continue
            }
            val plays = playsBySong[p.identity].orEmpty().map { it.event }.filter { it.startedAtMs in start..end }
            val saved = plays.any { it.liked || it.addedToPlaylist } ||
                savesBySong[p.identity].orEmpty().any { it.atMs in start..end }
            // A replay must be heard: a tap that never played (a failed start) is not a return.
            val heard = plays.filter { it.msPlayed > 0 }
            val replayed = heard.any { it.replayed } ||
                heard.any { it.startedAtMs > start && it.startReason in DELIBERATE_REPLAYS }
            groups[group] = DiscoveryCounts(
                pairs = c.pairs + 1,
                saved = c.saved + if (saved) 1 else 0,
                replayed = c.replayed + if (replayed) 1 else 0,
                either = c.either + if (saved || replayed) 1 else 0,
                pending = c.pending,
            )
        }
        return DiscoveryReport(now, windowDays, groups, unattributedFirstPlays = unattributed, localFirstPlays = firstPlays)
    }
}
