package app.turtleplayer.bridge

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.context.GlobalContext
import kotlin.coroutines.resume

private fun context(): Context? = GlobalContext.getOrNull()?.getOrNull<Context>()

internal actual fun previousExit(): CrashResume.Exit? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    val context = context() ?: return null
    val am = context.getSystemService(ActivityManager::class.java) ?: return null
    // The main process only: the WebView and token helper processes end on their own all the time.
    val exit = am.getHistoricalProcessExitReasons(context.packageName, 0, 10).firstOrNull { it.processName == context.packageName } ?: return null
    val cause = when (exit.reason) {
        ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR -> CrashResume.Cause.CRASH
        ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_FREEZER, ApplicationExitInfo.REASON_SIGNALED,
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE, ApplicationExitInfo.REASON_OTHER -> CrashResume.Cause.SYSTEM_KILL
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> CrashResume.Cause.USER
        ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> CrashResume.Cause.UPDATE
        else -> CrashResume.Cause.OTHER
    }
    return CrashResume.Exit(cause, exit.timestamp)
}

/** Upstream's playback service; its session handles play exactly as it does for a car or headset. */
private const val MUSIC_SERVICE = "com.metrolist.android.playback.MusicService"
private const val CONNECT_TIMEOUT_MS = 5_000L

internal actual suspend fun resumeOwnPlayback(): Boolean = withContext(Dispatchers.Main) {
    val context = context() ?: return@withContext false
    val token = SessionToken(context, ComponentName(context.packageName, MUSIC_SERVICE))
    val future = MediaController.Builder(context, token).buildAsync()
    val controller = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
        suspendCancellableCoroutine { cont ->
            future.addListener({ cont.resume(runCatching { future.get() }.getOrNull()) }, context.mainExecutor)
            cont.invokeOnCancellation { future.cancel(true) }
        }
    }
    if (controller == null) {
        MediaController.releaseFuture(future)
        return@withContext false
    }
    try {
        controller.play()
        true
    } finally {
        controller.release()
    }
}
