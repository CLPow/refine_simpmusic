package com.maxrave.media3.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.maxrave.domain.audio.OutputRoute
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the audio is going, for the per-output parts of "Enhance audio".
 *
 * - [OutputRoute.CAR] while Android Auto is connected to the media session. With Android Auto the
 *   phone does all decoding and processing and the car only plays what it is sent, so this is the
 *   one reliable signal that the listener is in the car.
 * - [OutputRoute.HEADPHONES] while a wired, USB, Bluetooth A2DP or LE headset is present.
 * - [OutputRoute.SPEAKER] otherwise.
 *
 * Also tracks the media volume (0..1) for low-volume compensation.
 */
class OutputRouteMonitor(
    context: Context,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)

    private val _route = MutableStateFlow(OutputRoute.SPEAKER)
    val route: StateFlow<OutputRoute> = _route.asStateFlow()

    private val _volumeFraction = MutableStateFlow(currentVolume())
    val volumeFraction: StateFlow<Float> = _volumeFraction.asStateFlow()

    @Volatile private var androidAutoConnected = false

    init {
        audioManager.registerAudioDeviceCallback(
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
            },
            Handler(Looper.getMainLooper()),
        )
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context?,
                    intent: Intent?,
                ) {
                    _volumeFraction.value = currentVolume()
                }
            },
            // Not a public constant, but the platform has sent it on every volume change since API 1.
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    /**
     * Called by the media session with the package names of the controllers connected right now.
     * Recomputed from the session's own list on every connect and disconnect rather than counted,
     * so a late or missing disconnect cannot leave the car route stuck on.
     */
    fun onControllersChanged(packageNames: Collection<String>) {
        androidAutoConnected = ANDROID_AUTO_PACKAGE in packageNames
        refresh()
    }

    /** The session went away: no controller is connected any more. */
    fun onSessionReleased() = onControllersChanged(emptyList())

    private fun refresh() {
        _route.value =
            when {
                androidAutoConnected -> OutputRoute.CAR
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES } -> OutputRoute.HEADPHONES
                else -> OutputRoute.SPEAKER
            }
    }

    private fun currentVolume(): Float = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxVolume

    private companion object {
        const val ANDROID_AUTO_PACKAGE = "com.google.android.projection.gearhead"

        val HEADSET_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
    }
}
