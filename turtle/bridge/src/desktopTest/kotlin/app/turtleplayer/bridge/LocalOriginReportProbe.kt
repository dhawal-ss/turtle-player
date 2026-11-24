package app.turtleplayer.bridge

import app.turtleplayer.core.Clock
import app.turtleplayer.events.HistoryLens
import app.turtleplayer.taste.eval.DiscoveryReturn
import app.turtleplayer.taste.eval.FirstFiveSkips
import app.turtleplayer.taste.eval.OriginReporter
import com.metrolist.shared.db.JdbcSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Opt-in: prints the origin report (where suggested plays came from, how early suggestions fared) and
 * the listener outcomes (first-five early skips and seven-day discovery save or replay, each with its
 * unknown or untagged share) for a COPY of a turtle.db, as aggregates only. Run it on an Android copy
 * and a Windows copy separately: that is the platform split. Opening the copy migrates it in place. Skipped unless the
 * path is given:
 *
 *     ./gradlew :turtle-bridge:desktopTest --tests '*LocalOriginReportProbe*' -PturtleOriginReportDb=/path/to/copy/turtle.db
 */
class LocalOriginReportProbe {
    @Test
    fun printsTheOriginReportOfACopiedDatabase() =
        runTest {
            val path = System.getProperty(DB_PROPERTY)?.takeIf { it.isNotBlank() } ?: return@runTest
            val store = SqlitePlayEventStore(JdbcSQLiteDriver(), path)
            try {
                val all = store.since(0, Int.MAX_VALUE)
                println("== As stored ==")
                println(OriginReporter.report(all).render())
                // Older skips read as neutral, as the "Repair older skips" setting does.
                println("== With older skips repaired (${HistoryLens.legacySkipCount(all)} read as neutral) ==")
                println(OriginReporter.report(HistoryLens(repairLegacySkips = true).read(all)).render())
                // Only plays labelled by this build's tracker: how new builds fare, apart from legacy history.
                println("== Labelled plays only ==")
                println(OriginReporter.report(all.filter { it.labelVersion != null }).render())
                // Listener outcomes since this install started measuring (0, all history, before that build).
                val since = store.pref(PREF_MEASURE_SINCE)?.toLongOrNull() ?: 0L
                println("== Outcomes since measurement start ($PREF_MEASURE_SINCE = ${if (since == 0L) "unset" else since}) ==")
                println(FirstFiveSkips.report(all, Clock.System, notBeforeMs = since).render())
                println(DiscoveryReturn.report(all, store.feedbackSince(0), Clock.System, notBeforeMs = since).render())
            } finally {
                store.close()
            }
        }

    private companion object {
        const val DB_PROPERTY = "turtleOriginReportDb"
    }
}
