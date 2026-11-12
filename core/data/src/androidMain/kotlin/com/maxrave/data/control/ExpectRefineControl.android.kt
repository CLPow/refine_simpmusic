package com.maxrave.data.control

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.domain.repository.RefineControlRepository
import com.maxrave.refinecontrol.DEVICE_PHONE
import kotlinx.coroutines.CoroutineScope
import org.koin.mp.KoinPlatform.getKoin
import kotlin.math.roundToInt

actual fun createRefineControlRepository(
    dataStoreManager: DataStoreManager,
    localPlayer: MediaPlayerHandler,
    coroutineScope: CoroutineScope,
): RefineControlRepository {
    val context = getKoin().get<Context>().applicationContext
    val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    // The phone app has no volume of its own: a remote changes the media volume, as the volume
    // keys do.
    val volume =
        object : SystemVolume {
            override fun get(): Float {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                return if (max <= 0) 1f else audio.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
            }

            override fun set(volume: Float) {
                val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (volume * max).roundToInt().coerceIn(0, max), 0) }
            }
        }
    // Many phones drop broadcast packets unless an app asks for them. Held only while the devices
    // list is on screen; the rest of the time the other device finds the phone by the phone's own
    // beacons.
    val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val broadcastLock = runCatching { wifi?.createMulticastLock("refine-control")?.apply { setReferenceCounted(false) } }.getOrNull()
    return RefineControlRepositoryImpl(
        dataStore = dataStoreManager,
        local = localPlayer,
        scope = coroutineScope,
        deviceType = DEVICE_PHONE,
        defaultDeviceName =
            runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?: Build.MODEL
                ?: "Phone",
        systemVolume = volume,
        onScanning = { scanning ->
            runCatching {
                if (scanning) broadcastLock?.acquire() else if (broadcastLock?.isHeld == true) broadcastLock.release()
            }
        },
    )
}
