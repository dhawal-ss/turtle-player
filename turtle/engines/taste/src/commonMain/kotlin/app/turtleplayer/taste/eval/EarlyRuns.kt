package app.turtleplayer.taste.eval

import app.turtleplayer.events.EventSource
import app.turtleplayer.events.PlayEvent
import app.turtleplayer.events.StartReason
import app.turtleplayer.taste.PlayOrigin
import app.turtleplayer.taste.PlayPlace
import app.turtleplayer.taste.SurfaceTag
import kotlin.math.roundToLong

/** One play with the listener's maturity at the time and its parsed origin (null when untagged). */
internal class Rated(val event: PlayEvent, val maturity: Maturity, val origin: PlayOrigin?)

/**
 * Every event in a fixed order (time, session, position, id), rated for maturity: [Maturity.COLD]
 * below [coldStartPlays] finished plays strictly before it, [Maturity.LIGHT] below
 * [establishedPlays], else [Maturity.ESTABLISHED]. Imports count toward maturity.
 */
internal fun rateAll(events: List<PlayEvent>, coldStartPlays: Int, establishedPlays: Int): List<Rated> {
    val out = ArrayList<Rated>(events.size)
    var finished = 0
    for (e in events.sortedWith(compareBy({ it.startedAtMs }, { it.sessionId }, { it.position }, { it.id }))) {
        val maturity = when {
            finished < coldStartPlays -> Maturity.COLD
            finished < establishedPlays -> Maturity.LIGHT
            else -> Maturity.ESTABLISHED
        }
        if (e.isFinished) finished++
        out += Rated(e, maturity, SurfaceTag.parse(e.surfaceDetail))
    }
    return out
}

/** Only the listener's own plays ([EventSource.LOCAL]), still in [rateAll]'s order. */
internal fun List<Rated>.local(): List<Rated> = filter { it.event.source == EventSource.LOCAL }

/**
 * One of the first five suggested plays of a run (see [OriginReporter]). [joined] is true when the
 * run was not entered deliberately: playback ran on ([StartReason.AUTOPLAY], [StartReason.USER_NEXT])
 * from an untagged play, or the session opened that way (resumed after a long pause, mid-queue), so
 * the run's true first songs went unrecorded or sit in another session.
 */
internal class Slot(val rated: Rated, val place: PlayPlace, val joined: Boolean)

private const val EARLY_PLAYS = 5
private val MANUAL_STARTS = setOf(StartReason.TAPPED, StartReason.USER_PREVIOUS, StartReason.REMOTE, StartReason.RESUMED)
private val DELIBERATE_STARTS = setOf(StartReason.TAPPED, StartReason.UNKNOWN)
private val RAN_ON = setOf(StartReason.AUTOPLAY, StartReason.USER_NEXT)
private val PICKED_PLACES = setOf(PlayPlace.HOME_TURTLE, PlayPlace.HOME_GRID, PlayPlace.HOME_RECENT, PlayPlace.HOME_YOUTUBE, PlayPlace.SEARCH)

/**
 * The run slots of [local] plays: each session, ordered by position, split into runs, and the first
 * five suggested plays of each run (interrupted plays included: they take their slot). The rules are
 * [OriginReporter]'s.
 */
internal fun earlySlots(local: List<Rated>): List<Slot> {
    val out = ArrayList<Slot>()
    for (session in local.groupBy { it.event.sessionId }.values) {
        val ordered = session.sortedWith(compareBy({ it.event.position }, { it.event.startedAtMs }, { it.event.id }))
        var place: PlayPlace? = null
        var taken = 0
        var joined = false
        for ((i, r) in ordered.withIndex()) {
            val p = r.origin?.place
            val reason = r.event.startReason
            val entered = i == 0 || p != place
            val restarted = !entered && reason in DELIBERATE_STARTS
            if (entered || restarted) {
                taken = 0
                // This play only ran on from one before it: an untagged play, or the last session (a
                // session opens mid-queue when playback resumes after a long pause).
                joined = entered && (i == 0 || place == null) && reason in RAN_ON
            }
            place = p
            if (p == null || taken >= EARLY_PLAYS) continue
            val manual = when {
                entered -> p in PICKED_PLACES
                restarted -> p in PICKED_PLACES || reason == StartReason.TAPPED
                else -> reason in MANUAL_STARTS
            }
            if (manual) continue
            taken++
            out += Slot(r, p, joined)
        }
    }
    return out
}

internal val Maturity.label: String get() = name.lowercase()

internal fun share(part: Int, whole: Int): Double = if (whole == 0) 0.0 else part.toDouble() / whole

/** One decimal, integer math: `Double.toString` differs between targets. */
internal fun percent(rate: Double): String {
    val tenths = (rate * 1000).roundToLong()
    return "${tenths / 10}.${tenths % 10}%"
}

/** Plain aligned text columns. */
internal fun StringBuilder.table(header: List<String>, rows: List<List<String>>) {
    val widths = header.indices.map { i -> (rows.map { it[i] } + header[i]).maxOf { it.length } }
    for (row in listOf(header) + rows) {
        appendLine(row.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString("  ").trimEnd())
    }
}
