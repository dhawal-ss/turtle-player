package app.turtleplayer.bridge

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import org.koin.core.context.GlobalContext
import java.io.File

private fun appContext(): Context? = GlobalContext.getOrNull()?.getOrNull<Context>()

/** `files/turtle-drive.log`, rotated to `turtle-drive.1.log` past [MAX_BYTES]: at most about 6 MB, some 6 hours of playback. */
internal actual fun driveLogSink(): DriveLogSink? {
    val dir = appContext()?.filesDir ?: return null
    return object : DriveLogSink {
        private val current = File(dir, "turtle-drive.log")
        private val previous = File(dir, "turtle-drive.1.log")

        override fun append(lines: List<String>) {
            if (current.length() > MAX_BYTES) {
                previous.delete()
                current.renameTo(previous)
            }
            current.appendText(lines.joinToString("\n", postfix = "\n"))
        }
    }
}

private const val MAX_BYTES = 3_000_000L

internal actual fun startPlatformSignals(scope: CoroutineScope) {
    val context = appContext() ?: return
    logStartState(context)
    logPreviousExits(context)
    registerBroadcasts(context)
    registerAudioDevices(context)
    registerAudioActivity(context)
    registerNetwork(context)
}

private fun log(message: String) = DriveLog.line("android", message)

private fun logStartState(context: Context) {
    val power = context.getSystemService(PowerManager::class.java)
    log(
        "process ${android.os.Process.myPid()} up; " +
            "battery optimization ${if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) "off (unrestricted)" else "on"}, " +
            "power save ${power?.isPowerSaveMode}, " +
            "device ${Build.MANUFACTURER} ${Build.MODEL} Android ${Build.VERSION.RELEASE}",
    )
}

/** Why Android ended this app's earlier processes (killed, frozen, crashed, low memory...). */
private fun logPreviousExits(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    val am = context.getSystemService(ActivityManager::class.java) ?: return
    for (exit in am.getHistoricalProcessExitReasons(context.packageName, 0, 5).asReversed()) {
        log(
            "earlier exit: ${java.time.Instant.ofEpochMilli(exit.timestamp)} ${exit.processName} pid ${exit.pid} " +
                "reason ${exitReason(exit.reason)} importance ${exit.importance} \"${exit.description.orEmpty()}\"",
        )
    }
}

private fun exitReason(reason: Int): String = when (reason) {
    ApplicationExitInfo.REASON_ANR -> "ANR"
    ApplicationExitInfo.REASON_CRASH -> "CRASH"
    ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
    ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
    ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
    ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
    ApplicationExitInfo.REASON_OTHER -> "OTHER"
    ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "PACKAGE_STATE_CHANGE"
    ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "PACKAGE_UPDATED"
    ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
    ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
    ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
    ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
    else -> "UNKNOWN($reason)"
}

private fun registerBroadcasts(context: Context) {
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_USER_PRESENT)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        addAction(AudioManager.ACTION_HEADSET_PLUG)
        addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        // Delivered only with the Nearby devices permission; harmless without it.
        addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        addAction(BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED)
    }
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action?.substringAfterLast('.') ?: return
            val extra = when (intent.action) {
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED, BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED ->
                    " state ${intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)} (was ${intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1)})"
                AudioManager.ACTION_HEADSET_PLUG -> " state ${intent.getIntExtra("state", -1)}"
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    val power = context.getSystemService(PowerManager::class.java)
                    " power save ${power?.isPowerSaveMode}, idle ${power?.isDeviceIdleMode}"
                }
                else -> ""
            }
            log("$action$extra")
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        context.registerReceiver(receiver, filter)
    }
}

private fun registerAudioDevices(context: Context) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    fun describe(devices: Array<out AudioDeviceInfo>) = devices.joinToString { "${deviceType(it.type)} \"${it.productName}\"" }
    audio.registerAudioDeviceCallback(
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = log("audio devices added: ${describe(added.filter { it.isSink }.toTypedArray())}")
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = log("audio devices removed: ${describe(removed.filter { it.isSink }.toTypedArray())}")
        },
        null,
    )
}

/**
 * What kinds of audio are playing on the phone (navigation prompts, calls, other media) and the
 * audio mode: who takes the audio away when music stops. Other apps' players are anonymous.
 */
private fun registerAudioActivity(context: Context) {
    val audio = context.getSystemService(AudioManager::class.java) ?: return
    audio.registerAudioPlaybackCallback(
        object : AudioManager.AudioPlaybackCallback() {
            private var last: String? = null

            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                val now = configs.map { usage(it.audioAttributes.usage) }.sorted().joinToString().ifEmpty { "none" }
                if (now != last) log("audio players: $now").also { last = now }
            }
        },
        null,
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        audio.addOnModeChangedListener(context.mainExecutor) { mode -> log("audio mode ${audioMode(mode)}") }
    }
    log("audio mode ${audioMode(audio.mode)}, music active ${audio.isMusicActive}")
}

private fun usage(usage: Int): String = when (usage) {
    AudioAttributes.USAGE_MEDIA -> "media"
    AudioAttributes.USAGE_GAME -> "game"
    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE -> "navigation"
    AudioAttributes.USAGE_ASSISTANT -> "assistant"
    AudioAttributes.USAGE_VOICE_COMMUNICATION -> "voice-call"
    AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING -> "call-signalling"
    AudioAttributes.USAGE_NOTIFICATION, AudioAttributes.USAGE_NOTIFICATION_EVENT -> "notification"
    AudioAttributes.USAGE_NOTIFICATION_RINGTONE -> "ringtone"
    AudioAttributes.USAGE_ALARM -> "alarm"
    AudioAttributes.USAGE_ASSISTANCE_SONIFICATION -> "ui-sound"
    AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY -> "accessibility"
    else -> "usage-$usage"
}

private fun audioMode(mode: Int): String = when (mode) {
    AudioManager.MODE_NORMAL -> "normal"
    AudioManager.MODE_RINGTONE -> "ringtone"
    AudioManager.MODE_IN_CALL -> "in-call"
    AudioManager.MODE_IN_COMMUNICATION -> "in-communication"
    AudioManager.MODE_CALL_SCREENING -> "call-screening"
    else -> "mode-$mode"
}

private fun deviceType(type: Int): String = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth-a2dp"
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth-sco"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble-headset"
    AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble-speaker"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb-device"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "usb-accessory"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb-headset"
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired-headphones"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired-headset"
    else -> "type-$type"
}

private fun registerNetwork(context: Context) {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return
    connectivity.registerDefaultNetworkCallback(
        object : ConnectivityManager.NetworkCallback() {
            private var last: String? = null

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val kind = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "other"
                }
                val state = "$kind validated=${caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)}"
                if (state != last) log("network $state").also { last = state }
            }

            override fun onLost(network: Network) {
                last = null
                log("network lost")
            }
        },
    )
}
