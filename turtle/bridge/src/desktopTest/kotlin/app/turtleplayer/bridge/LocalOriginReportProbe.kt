package app.turtleplayer.bridge

import app.turtleplayer.taste.eval.OriginReporter
import com.metrolist.shared.db.JdbcSQLiteDriver
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Opt-in: prints the origin report (where suggested plays came from, how early suggestions fared) for
 * a COPY of a turtle.db, as aggregates only. Opening the copy migrates it in place. Skipped unless the
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
                println(OriginReporter.report(store.since(0, Int.MAX_VALUE)).render())
            } finally {
                store.close()
            }
        }

    private companion object {
        const val DB_PROPERTY = "turtleOriginReportDb"
    }
}
