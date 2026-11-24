package app.turtleplayer.taste.eval

import app.turtleplayer.events.EndReason
import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.Served
import app.turtleplayer.taste.TasteConfig

/** How much finished listening a listener had before a play. */
enum class Maturity { COLD, LIGHT, ESTABLISHED }

/**
 * Where a play came from. [place] is null for an untagged play. [position] is the Home row position
 * bucketed as "1", "2", "3" or "4+", null when the tag has none.
 */
data class OriginGroup(val place: PlayPlace?, val served: Served?, val position: String?, val maturity: Maturity)

data class PlayCounts(val started: Int, val quickSkips: Int, val finished: Int) {
    val quickSkipRate: Double get() = if (started == 0) 0.0 else quickSkips.toDouble() / started

    internal operator fun plus(e: PlayEvent) =
        PlayCounts(started + 1, quickSkips + if (e.isQuickSkip) 1 else 0, finished + if (e.isFinished) 1 else 0)
}

data class EarlyGroup(val place: PlayPlace, val served: Served?, val maturity: Maturity)

data class EarlySkips(val eligible: Int, val quickSkips: Int) {
    val rate: Double get() = if (eligible == 0) 0.0 else quickSkips.toDouble() / eligible

    internal operator fun plus(e: PlayEvent) = EarlySkips(eligible + 1, quickSkips + if (e.isQuickSkip) 1 else 0)
}

/** Baseline of where the listener's own plays come from and how the first suggestions fare. */
data class OriginReport(
    val plays: Map<OriginGroup, PlayCounts>,
    val early: Map<EarlyGroup, EarlySkips>,
    val localPlays: Int,
    val untaggedPlays: Int,
) {
    /** Plain aligned text for a reviewer (developer diagnostics, never shown in the app). */
    fun render(): String = buildString {
        appendLine("local plays: $localPlays, untagged: $untaggedPlays (${percent(share(untaggedPlays, localPlays))})")
        appendLine()
        appendLine("plays by origin")
        val playRows = plays.entries.sortedWith(compareBy<Map.Entry<OriginGroup, PlayCounts>>(
            { it.key.place?.ordinal ?: Int.MAX_VALUE },
            { it.key.served?.ordinal ?: -1 },
            { POSITION_BUCKETS.indexOf(it.key.position) },
            { it.key.maturity.ordinal },
        )).map { (g, c) ->
            listOf(g.place?.tag ?: UNTAGGED, g.served?.tag ?: NONE, g.position ?: NONE, g.maturity.label, "${c.started}", "${c.quickSkips}", "${c.finished}", percent(c.quickSkipRate))
        }
        table(listOf("place", "served", "pos", "maturity", "started", "quick", "finished", "quick%"), playRows)
        appendLine()
        appendLine("early suggestion skips (first five suggested per run)")
        val earlyRows = early.entries.sortedWith(compareBy<Map.Entry<EarlyGroup, EarlySkips>>(
            { it.key.place.ordinal },
            { it.key.served?.ordinal ?: -1 },
            { it.key.maturity.ordinal },
        )).map { (g, s) ->
            listOf(g.place.tag, g.served?.tag ?: NONE, g.maturity.label, "${s.eligible}", "${s.quickSkips}", percent(s.rate))
        }
        table(listOf("place", "served", "maturity", "eligible", "quick", "quick%"), earlyRows)
    }

    private companion object {
        const val UNTAGGED = "(untagged)"
        const val NONE = "-"
        val POSITION_BUCKETS = listOf(null, "1", "2", "3", "4+")
    }
}

/**
 * Builds an [OriginReport] from a listener's events: the baseline a reviewer compares discovery
 * changes against. Pure and deterministic.
 *
 * Maturity reads every event, imports included: an event is [Maturity.COLD] below `coldStartPlays`
 * finished plays strictly before it, [Maturity.LIGHT] below `establishedPlays`, else
 * [Maturity.ESTABLISHED]. Only [EventSource.LOCAL] events are grouped and counted.
 *
 * Early skips (the plan's "first five suggested plays after a deliberate start"): each session,
 * ordered by position, is split into runs. A run starts when the place changes, and on a deliberate
 * start in the same place: a tap or a replaced queue ([StartReason.TAPPED], [StartReason.UNKNOWN]).
 * An untagged play ends a run and is never counted. Within a run, the first five suggested plays are
 * taken, and those not ended by an error or a stop are eligible. The play that starts a run is the
 * listener's own pick on a place where they choose the item (Home, Search) and after a tap in the
 * same place; entering a mix or radio, or restarting one, its first song is the system's pick however
 * playback started. Later in a run, a play with a manual start reason (previous, remote, resume) is
 * neither counted nor takes a slot; an error or a stop takes its slot but is not eligible. Groups
 * appear only with at least one eligible play.
 *
 * Known limits: [StartReason.UNKNOWN] also follows app start, which then reads as a deliberate start.
 * A Home row whose queue runs on into upstream's radio opens a second, radio run. [FirstFiveSkips]
 * is the stricter version of the early-skip table (unknown ends and joined runs kept apart).
 */
object OriginReporter {
    private val INTERRUPTED = setOf(EndReason.ERROR, EndReason.STOPPED)

    fun report(
        events: List<PlayEvent>,
        coldStartPlays: Int = TasteConfig().coldStartPlays,
        establishedPlays: Int = 500,
    ): OriginReport {
        val local = rateAll(events, coldStartPlays, establishedPlays).local()

        val plays = HashMap<OriginGroup, PlayCounts>()
        for (r in local) {
            val group = OriginGroup(r.origin?.place, r.origin?.served, r.origin?.position?.let(::bucket), r.maturity)
            plays[group] = (plays[group] ?: PlayCounts(0, 0, 0)) + r.event
        }

        val early = HashMap<EarlyGroup, EarlySkips>()
        for (slot in earlySlots(local)) {
            val r = slot.rated
            if (r.event.endReason in INTERRUPTED) continue
            val group = EarlyGroup(slot.place, r.origin?.served, r.maturity)
            early[group] = (early[group] ?: EarlySkips(0, 0)) + r.event
        }

        return OriginReport(plays, early, localPlays = local.size, untaggedPlays = local.count { it.origin == null })
    }

    private fun bucket(position: Int): String = if (position >= 4) "4+" else "$position"
}
