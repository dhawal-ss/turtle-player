package app.turtleplayer.bridge

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * Minimal logging to stdout/stderr, which the desktop app log and Android logcat both capture.
 * Fail-open hooks must still leave a trace, or a broken integration looks like upstream behavior.
 */
internal object TurtleLog {
    fun info(message: String) {
        println("[Turtle] $message")
        DriveLog.line("turtle", message)
    }

    fun error(message: String, t: Throwable? = null) {
        println("[Turtle] ERROR $message" + t?.let { "\n" + it.stackTraceToString() }.orEmpty())
        DriveLog.line("turtle", "ERROR $message" + t?.let { ": $it" }.orEmpty())
    }
}

/**
 * A child of [parent] that our hooks launch into: it is cancelled with upstream's scope, but our
 * failures are logged and never cancel upstream's scope or its other coroutines.
 */
internal fun isolatedChild(parent: CoroutineScope, name: String): CoroutineScope = CoroutineScope(
    parent.coroutineContext +
        SupervisorJob(parent.coroutineContext[Job]) +
        CoroutineExceptionHandler { _, t -> TurtleLog.error("$name failed", t) },
)
