package com.maxrave.data.control

import android.app.Activity
import android.app.Application
import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
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
    val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    val broadcastLock = runCatching { wifi?.createMulticastLock("refine-control")?.apply { setReferenceCounted(false) } }.getOrNull()
    var scanning = false
    var visible = false
    fun updateLock() {
        runCatching {
            if (scanning || visible) broadcastLock?.acquire() else if (broadcastLock?.isHeld == true) broadcastLock.release()
        }
    }
    val repository =
        RefineControlRepositoryImpl(
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
            onScanning = {
                scanning = it
                updateLock()
            },
        )
    (context as? Application)?.registerActivityLifecycleCallbacks(
        object : Application.ActivityLifecycleCallbacks {
            private var started = 0

            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0) {
                    visible = true
                    updateLock()
                    repository.wake()
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (started > 0 && --started == 0) {
                    visible = false
                    updateLock()
                }
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?,
            ) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle,
            ) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        },
    )
    runCatching {
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)?.registerDefaultNetworkCallback(
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) = repository.wake()

                override fun onLost(network: Network) = repository.wake()
            },
        )
    }
    return repository
}
