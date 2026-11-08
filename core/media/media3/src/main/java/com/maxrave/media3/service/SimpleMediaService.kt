package com.maxrave.media3.service

import com.maxrave.media3.audio.OutputRouteMonitor
import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import androidx.media3.ui.DefaultMediaDescriptionAdapter
import androidx.media3.ui.PlayerNotificationManager
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.utils.CoilBitmapLoader
import com.maxrave.media3.utils.sizeLimitedForSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.system.exitProcess

@UnstableApi
internal class SimpleMediaService :
    MediaLibraryService(),
    KoinComponent {
    // Session-level player from DI: the ForwardingPlayer wrapped with Cast support in the
    // full build (plain ForwardingPlayer in the FOSS build).
    private val player: Player by inject<Player>(qualifier = named(com.maxrave.common.Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject<CoilBitmapLoader>()

    private var mediaSession: MediaLibrarySession? = null

    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject<MediaLibrarySession.Callback>()

    private val simpleMediaServiceHandler: MediaPlayerHandler by inject<MediaPlayerHandler>()
    private val dataStoreManager: DataStoreManager by inject<DataStoreManager>()
    private val routeMonitor: OutputRouteMonitor by inject<OutputRouteMonitor>()

    private val binder = MusicBinder()

    /** Work started by the service itself (off the creation path); cancelled in [onDestroy]. */
    private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var playerNotificationManager: PlayerNotificationManager? = null

    /** The controller this service keeps on its own session; released in [onDestroy]. */
    private var selfControllerFuture: ListenableFuture<MediaController>? = null

    /** Whether the keep-alive notification currently holds this service in the foreground. */
    private var keepAliveForeground = false

    inner class MusicBinder : Binder() {
        val service: SimpleMediaService
            get() = this@SimpleMediaService

        fun setActivitySession(
            context: Context,
            activity: Class<out Activity>,
        ) {
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, activity),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.w("Service", "Simple Media Service Bound")
        return super.onBind(intent) ?: binder
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        Logger.w("Service", "Simple Media Service Created")

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { MEDIA_NOTIFICATION.NOTIFICATION_ID },
                MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                R.string.notification_channel_name,
            ).apply {
                setSmallIcon(R.drawable.mono)
            },
        )

        if (mediaSession == null) {
            mediaSession =
                provideMediaLibrarySession(
                    this,
                    player,
                    simpleMediaSessionCallback,
                )
        }

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            val commandButtonList = list.map { it.toCommandButton(this) }
            mediaSession?.setMediaButtonPreferences(
                commandButtonList,
            )
        }

        val sessionToken = SessionToken(this, ComponentName(this, SimpleMediaService::class.java))
        selfControllerFuture = MediaController.Builder(this, sessionToken).buildAsync()

        // Read off the service-creation path. When a media button starts this service the
        // system is already counting down to the foreground-start deadline, and a blocking
        // DataStore read on the main thread spends part of that budget on disk I/O.
        coroutineScope.launch {
            if (dataStoreManager.keepServiceAlive.first() != DataStoreManager.TRUE) return@launch
            val notificationManager = getSystemService<NotificationManager>()
            notificationManager?.run {
                createNotificationChannel(
                    NotificationChannel(
                        "media_playback_channel",
                        "Now playing",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                    },
                )
            }
            playerNotificationManager =
                PlayerNotificationManager
                    .Builder(this@SimpleMediaService, 2026, "media_playback_channel")
                    .setNotificationListener(
                        object : PlayerNotificationManager.NotificationListener {
                            // Posted on every metadata, play/pause and artwork change. It used to
                            // launch a new endless `startForeground` loop each time, so a few songs
                            // in there were hundreds of loops on Main. Promoting once per post is
                            // all a foreground service needs; it stays foreground until stopped.
                            override fun onNotificationPosted(
                                notificationId: Int,
                                notification: Notification,
                                ongoing: Boolean,
                            ) {
                                if (!ongoing && keepAliveForeground) return
                                try {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        startForeground(notificationId, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                                    } else {
                                        startForeground(notificationId, notification)
                                    }
                                    keepAliveForeground = true
                                } catch (e: Exception) {
                                    // ForegroundServiceStartNotAllowedException (Android 12+) when
                                    // promoted from the background; Media3 keeps its own
                                    // foreground state for playback, so this is not fatal.
                                    Logger.w("Service", "Keep-alive startForeground refused: ${e.message}")
                                }
                            }

                            override fun onNotificationCancelled(
                                notificationId: Int,
                                dismissedByUser: Boolean,
                            ) {
                                keepAliveForeground = false
                            }
                        },
                    ).setMediaDescriptionAdapter(DefaultMediaDescriptionAdapter(mediaSession?.sessionActivity))
                    .build()
                    .apply {
                        setPlayer(player)
                        setSmallIcon(R.drawable.mono)
                        mediaSession?.platformToken?.let { setMediaSessionToken(it) }
                    }
        }
    }

    @UnstableApi
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Logger.w("Service", "Simple Media Service Received Action: ${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    @UnstableApi
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    /**
     * Releases what this service instance owns: its session, its self-controller and the
     * keep-alive notification. The player, its handler and the shared coroutine scope are
     * process-wide singletons and outlive the service — Android Auto, a widget or a media button
     * can bring a new service up in the same process, and it must find them working.
     */
    @UnstableApi
    private fun releaseServiceResources() {
        selfControllerFuture?.let { MediaController.releaseFuture(it) }
        selfControllerFuture = null
        playerNotificationManager?.setPlayer(null)
        playerNotificationManager = null
        mediaSession?.release()
        mediaSession = null
        // A released session reports no disconnects; the car route must not outlive it.
        routeMonitor.onSessionReleased()
    }

    /**
     * Tears down the process-wide playback singletons. Their scope is cancelled for good, so this
     * is only done right before the process exits (the "kill service on exit" setting).
     */
    @UnstableApi
    private fun releaseEverythingAndExit() {
        Logger.w("Service", "Releasing playback and exiting")
        try {
            player.pause()
            player.playWhenReady = false
            releaseServiceResources()
            simpleMediaServiceHandler.release()
        } catch (e: Exception) {
            Logger.e("Service", "Error during release: ${e.message}")
        }
        exitProcess(0)
    }

    @UnstableApi
    override fun onDestroy() {
        Logger.w("Service", "Simple Media Service Destroyed")
        coroutineScope.cancel()
        val killOnExit = simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()
        releaseServiceResources()
        super.onDestroy()
        if (killOnExit) releaseEverythingAndExit()
    }

    override fun onTrimMemory(level: Int) {
        Logger.w("Service", "Simple Media Service Trim Memory Level: $level")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
    }

    @UnstableApi
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w("Service", "Simple Media Service Task Removed")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            super.onTaskRemoved(rootIntent)
            releaseEverythingAndExit()
        }
    }

    // Can't inject by Koin because it depend on service
    @UnstableApi
    private fun provideMediaLibrarySession(
        service: MediaLibraryService,
        player: Player,
        callback: MediaLibrarySession.Callback,
    ): MediaLibrarySession =
        MediaLibrarySession
            .Builder(
                service,
                player,
                callback,
            ).setId(this.javaClass.name)
            // Capped at the platform's own artwork limit so the framework never rescales the shared
            // bitmap in setMetadata — see sizeLimitedForSession (#2500). Falls back to the plain
            // loader if the limit cannot be read.
            .setBitmapLoader(sizeLimitedForSession(service, coilBitmapLoader))
            // Tapping the media notification opens whatever the session activity points at, and the
            // only other place that sets it is MainActivity's bind. A service started without that
            // bind — a headset/Bluetooth play, Android Auto, a widget, a restart after the process
            // was killed — would otherwise post a notification that opens nothing. The launcher
            // activity is resolved at runtime, so this module never has to name MainActivity.
            .apply {
                service.packageManager.getLaunchIntentForPackage(service.packageName)?.let { launch ->
                    setSessionActivity(
                        PendingIntent.getActivity(service, 0, launch, PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }
            .build()

    private fun isAppInForeground(): Boolean {
        val appProcessInfo = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}