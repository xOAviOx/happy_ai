package com.happy.assistant.handlers

import android.app.NotificationManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import com.happy.assistant.core.HappyLog
import com.happy.assistant.router.Command
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * The things the phone can do to itself: torch, volume, ringer, and the two
 * status questions. All offline, all instant.
 *
 * Note what is deliberately absent. WiFi, Bluetooth, mobile data and airplane
 * mode cannot be toggled programmatically on Android 10 and later - the APIs are
 * gone, and every workaround is fragile. Spec section 6 says do not attempt them,
 * so Happy does not pretend to.
 */
@Singleton
class DeviceHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val log: HappyLog,
) {

    private var torchOn = false

    fun torch(command: Command.Torch): String {
        val wanted = command.on ?: !torchOn
        val manager = context.getSystemService(CameraManager::class.java)
        return try {
            val id = manager.cameraIdList.firstOrNull { camera ->
                manager.getCameraCharacteristics(camera)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone has no torch."
            manager.setTorchMode(id, wanted)
            torchOn = wanted
            if (wanted) "Torch on." else "Torch off."
        } catch (t: Throwable) {
            log.e(TAG, "torch failed", t)
            "I could not reach the torch."
        }
    }

    /**
     * Which volume "volume up" actually means.
     *
     * The same one the hardware keys move: media while something is playing,
     * otherwise the ringer. Hard-coding STREAM_MUSIC meant the command adjusted a
     * stream that was already pinned at maximum with nothing playing through it,
     * so nothing audible ever happened.
     */
    private fun targetStream(audio: AudioManager): Int =
        if (audio.isMusicActive) AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING

    private fun streamLabel(stream: Int) =
        if (stream == AudioManager.STREAM_MUSIC) "Volume" else "Ringer volume"

    /**
     * Taking the ringer to zero is a Do Not Disturb change, which needs its own
     * grant. Without it, stop at one rather than throwing.
     */
    private fun floorFor(stream: Int): Int {
        if (stream == AudioManager.STREAM_MUSIC) return 0
        val notifications = context.getSystemService(NotificationManager::class.java)
        return if (notifications.isNotificationPolicyAccessGranted) 0 else 1
    }

    fun volumeStep(command: Command.VolumeStep): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = targetStream(audio)
        val max = audio.getStreamMaxVolume(stream)
        val before = audio.getStreamVolume(stream)
        val target = (before + if (command.up) 1 else -1).coerceIn(floorFor(stream), max)
        if (target == before) {
            return if (command.up) "Already at maximum." else "Already as low as I can go."
        }
        return apply(audio, stream, target, before, max)
    }

    fun volumeSet(command: Command.VolumeSet): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val stream = targetStream(audio)
        val max = audio.getStreamMaxVolume(stream)
        val before = audio.getStreamVolume(stream)
        val percent = command.percent.coerceIn(0, 100)
        val target = (max * percent / 100.0).roundToInt().coerceIn(floorFor(stream), max)
        return apply(audio, stream, target, before, max)
    }

    /**
     * Sets an explicit level rather than nudging, and verifies it landed.
     *
     * adjustStreamVolume was a silent no-op here: it returned normally and moved
     * nothing. Asking for a specific value and reading it back is the difference
     * between reporting what happened and reporting what was requested.
     */
    private fun apply(audio: AudioManager, stream: Int, target: Int, before: Int, max: Int): String {
        try {
            audio.setStreamVolume(stream, target, AudioManager.FLAG_SHOW_UI)
        } catch (t: Throwable) {
            log.e(TAG, "setStreamVolume(${streamLabel(stream)}, $target) threw", t)
            return "The system would not let me change the volume."
        }
        val after = audio.getStreamVolume(stream)
        log.d(TAG, "${streamLabel(stream)} $before to $after, asked $target, max $max")
        return if (after == target) {
            "${streamLabel(stream)} $after of $max."
        } else {
            "The system would not let me change the volume."
        }
    }

    fun ringer(command: Command.RingerMode): String {
        val audio = context.getSystemService(AudioManager::class.java)
        val notifications = context.getSystemService(NotificationManager::class.java)
        // Silent counts as a Do Not Disturb change, and that needs its own grant.
        if (command.mode == Command.Ringer.SILENT &&
            !notifications.isNotificationPolicyAccessGranted
        ) {
            return "I need Do Not Disturb access to do that. It is in Happy's settings."
        }
        return try {
            audio.ringerMode = when (command.mode) {
                Command.Ringer.SILENT -> AudioManager.RINGER_MODE_SILENT
                Command.Ringer.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                Command.Ringer.NORMAL -> AudioManager.RINGER_MODE_NORMAL
            }
            when (command.mode) {
                Command.Ringer.SILENT -> "Silent."
                Command.Ringer.VIBRATE -> "Vibrate only."
                Command.Ringer.NORMAL -> "Ringer on."
            }
        } catch (t: Throwable) {
            log.e(TAG, "ringer change failed", t)
            "I could not change the ringer."
        }
    }

    fun battery(): String {
        val manager = context.getSystemService(BatteryManager::class.java)
        val level = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level < 0) return "I could not read the battery."
        val charging = manager.isCharging
        return if (charging) "$level percent, charging." else "$level percent."
    }

    fun storage(): String = try {
        val stat = StatFs(Environment.getDataDirectory().path)
        val freeGb = stat.availableBytes / 1_000_000_000.0
        val totalGb = stat.totalBytes / 1_000_000_000.0
        "${freeGb.roundToInt()} of ${totalGb.roundToInt()} gigabytes free."
    } catch (t: Throwable) {
        log.e(TAG, "storage read failed", t)
        "I could not read the storage."
    }

    companion object {
        private const val TAG = "Device"
    }
}
