package app.turtleplayer.bridge

// Desktop apps are not killed in the background, and the listener sees their window close.
internal actual fun previousExit(): CrashResume.Exit? = null

internal actual suspend fun resumeOwnPlayback(): Boolean = false
