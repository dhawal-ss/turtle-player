package app.turtleplayer.bridge

import kotlinx.coroutines.CoroutineScope

// Desktop keeps its own application log, and its playback does not depend on the platform's power rules.
internal actual fun driveLogSink(): DriveLogSink? = null

internal actual fun startPlatformSignals(scope: CoroutineScope) = Unit
